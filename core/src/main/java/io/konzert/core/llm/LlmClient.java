package io.konzert.core.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.konzert.core.config.AgentConfig;
import io.konzert.core.config.ConfigStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * One OpenAI-compatible chat call, with the guards that keep a live demo alive.
 *
 * Three of these were learned the hard way against deepseek-v4-flash and are
 * documented in docs/agent-control-panel.md:
 *
 *  - reasoning tokens are spent from maxTokens BEFORE any content exists, so a
 *    tight budget returns an empty string rather than a short answer;
 *  - an empty content with a non-empty reasoning trace is a FAILED call, not an
 *    answer, and must never be published as a turn;
 *  - the prompt prefix must be byte-stable or the provider's prompt cache is
 *    thrown away, and every call pays full price.
 */
@ApplicationScoped
public class LlmClient {

    private static final Logger LOG = Logger.getLogger(LlmClient.class);

    public record ToolCall(String id, String name, String arguments) {}

    /**
     * @param status ok | empty | timeout | error — the caller decides what a
     *               non-ok reply means; this class never invents content.
     */
    public record Reply(
            String status,
            String content,
            String reasoning,
            List<ToolCall> toolCalls,
            int promptTokens,
            int completionTokens,
            long latencyMs) {

        public boolean ok() {
            return "ok".equals(status);
        }

        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    @Inject
    ConfigStore store;
    @Inject
    ObjectMapper mapper;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Fired after every call so the funnel and the token budget stay honest. */
    private final List<java.util.function.BiConsumer<Integer, Integer>> tokenListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public void onTokens(java.util.function.BiConsumer<Integer, Integer> listener) {
        tokenListeners.add(listener);
    }

    public Reply chat(List<Message> messages) {
        return chat(messages, null, false);
    }

    /**
     * @param tools JSON array of OpenAI-style tool definitions, or null
     * @param triage use the cheap lane's model and a smaller budget
     */
    public Reply chat(List<Message> messages, ArrayNode tools, boolean triage) {
        AgentConfig.Model cfg = store.config().model;
        String apiKey = store.secrets().llmApiKey;
        if (apiKey == null || apiKey.isBlank()) {
            return new Reply("error", null, null, List.of(), 0, 0, 0);
        }

        int budget = triage ? Math.max(300, cfg.maxTokens / 3) : cfg.maxTokens;
        Reply first = call(messages, tools, triage ? cfg.triageModel : cfg.model, budget, cfg);

        // A reset connection or a slow gateway is not a decision. One retry, a
        // second later, before we publish the fallback line on a projector.
        if ("error".equals(first.status()) || "timeout".equals(first.status())) {
            LOG.warnf("llm %s — retrying once", first.status());
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return first;
            }
            first = call(messages, tools, triage ? cfg.triageModel : cfg.model, budget, cfg);
        }

        // The one retry that is worth it: the model thought itself out of room.
        // Retrying with a bigger budget is cheap next to publishing nothing.
        if ("empty".equals(first.status())) {
            LOG.warnf("empty content after %d reasoning tokens — retrying with double the budget",
                    first.completionTokens());
            return call(messages, tools, triage ? cfg.triageModel : cfg.model, budget * 2, cfg);
        }
        return first;
    }

    private Reply call(List<Message> messages, ArrayNode tools, String model,
                       int maxTokens, AgentConfig.Model cfg) {
        long t0 = System.currentTimeMillis();
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", cfg.temperature);
            body.put("max_tokens", maxTokens);
            if (cfg.reasoningEffort != null && !cfg.reasoningEffort.isBlank()) {
                body.put("reasoning_effort", cfg.reasoningEffort);
            }
            ArrayNode msgs = body.putArray("messages");
            for (Message m : messages) {
                ObjectNode n = msgs.addObject();
                n.put("role", m.role());
                n.put("content", m.content());
            }
            if (tools != null && !tools.isEmpty()) {
                body.set("tools", tools);
            }

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint(cfg.baseUrl)))
                    .timeout(Duration.ofSeconds(cfg.timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + store.secrets().llmApiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - t0;

            if (res.statusCode() >= 300) {
                LOG.errorf("llm %d: %s", res.statusCode(), abbreviate(res.body()));
                return new Reply("error", null, null, List.of(), 0, 0, latency);
            }
            return parse(mapper.readTree(res.body()), latency);

        } catch (java.net.http.HttpTimeoutException e) {
            LOG.warnf("llm timeout after %ds", cfg.timeoutSeconds);
            return new Reply("timeout", null, null, List.of(), 0, 0,
                    System.currentTimeMillis() - t0);
        } catch (Exception e) {
            LOG.errorf("llm call failed: %s", e.toString());
            return new Reply("error", null, null, List.of(), 0, 0,
                    System.currentTimeMillis() - t0);
        }
    }

    private Reply parse(JsonNode root, long latency) {
        JsonNode choice = root.path("choices").path(0).path("message");
        String content = choice.path("content").asText("").trim();
        // DeepSeek calls it reasoning_content; OpenRouter normalises it to reasoning.
        String reasoning = choice.path("reasoning_content").asText("").trim();
        if (reasoning.isEmpty()) {
            reasoning = choice.path("reasoning").asText("").trim();
        }

        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode tc : choice.path("tool_calls")) {
            calls.add(new ToolCall(
                    tc.path("id").asText(""),
                    tc.path("function").path("name").asText(""),
                    tc.path("function").path("arguments").asText("{}")));
        }

        JsonNode usage = root.path("usage");
        int prompt = usage.path("prompt_tokens").asInt(0);
        int completion = usage.path("completion_tokens").asInt(0);
        tokenListeners.forEach(l -> l.accept(prompt, completion));

        // Empty content AND no tool call, after the model produced reasoning:
        // it ran out of budget mid-thought. That is a failure with a receipt.
        String status = (content.isEmpty() && calls.isEmpty()) ? "empty" : "ok";
        if ("empty".equals(status) && reasoning.isEmpty()) {
            status = "error";
        }
        return new Reply(status, content, reasoning, calls, prompt, completion, latency);
    }

    /** Providers differ on whether the base URL already carries /v1. */
    static String endpoint(String baseUrl) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b.endsWith("/chat/completions") ? b : b + "/chat/completions";
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 400 ? s : s.substring(0, 400) + "…";
    }

    public record Message(String role, String content) {
        public static Message system(String c) { return new Message("system", c); }
        public static Message user(String c) { return new Message("user", c); }
        public static Message assistant(String c) { return new Message("assistant", c); }
    }
}
