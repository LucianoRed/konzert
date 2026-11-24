package io.konzert.maestro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.konzert.core.config.ConfigStore;
import io.konzert.core.events.CloudEvent;
import io.konzert.core.events.KafkaBus;
import io.konzert.core.llm.LlmClient;
import io.konzert.core.runtime.AgentRuntime;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Maestro 🎼 — the dispatcher.
 *
 * It fixes nothing. It decides who does, it answers humans, and it declares
 * incidents over. Having no hands is what makes the rest of the team safe to
 * trust: the agent that decides is structurally unable to act.
 */
@ApplicationScoped
public class MaestroAgent {

    private static final Logger LOG = Logger.getLogger(MaestroAgent.class);

    @Inject
    AgentRuntime runtime;
    @Inject
    ConfigStore store;
    @Inject
    LlmClient llm;
    @Inject
    KafkaBus bus;
    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "konzert.conversation-topic", defaultValue = "konzert.conversation")
    String conversationTopic;

    /**
     * The agents Maestro may delegate to, by name. Configuration, not code: adding
     * a member to the team must not require a new Maestro image.
     */
    @ConfigProperty(name = "konzert.team", defaultValue = "atlas,sentinel")
    List<String> team;

    /** Recent turns per incident, so a reply has the thread it belongs to. */
    private final Map<String, Deque<String>> threads = new ConcurrentHashMap<>();
    /** The sensor's evidence per incident: forwarded as `context` so the owner sees what we saw. */
    private final Map<String, JsonNode> evidence = new ConcurrentHashMap<>();
    /** Last activity per incident, for the idle close that keeps a stuck story from blocking the next. */
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();
    /** Incidents already declared over: a late confirmation must not reopen the story. */
    private final Deque<String> closed = new ArrayDeque<>();

    @ConfigProperty(name = "konzert.idle-close-minutes", defaultValue = "10")
    int idleCloseMinutes;

    private final java.util.concurrent.ScheduledExecutorService housekeeping =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "maestro-housekeeping");
                t.setDaemon(true);
                return t;
            });

    /**
     * One at a time, deliberately. Reasoning is slow and the room is watching a
     * conversation: two turns racing would render out of order and read as
     * nonsense.
     */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "maestro");
        t.setDaemon(true);
        return t;
    });

    void onStart(@Observes StartupEvent ev) {
        runtime.start((topic, event) -> worker.submit(() -> handle(topic, event)));
        housekeeping.scheduleAtFixedRate(this::closeIdle, 1, 1, java.util.concurrent.TimeUnit.MINUTES);
        LOG.infof("Maestro awake — autonomy=%s, %d intake rule(s)",
                store.config().autonomy, store.intake().rules.size());
    }

    private void handle(String topic, JsonNode event) {
        String correlation = text(event, "correlationid");
        if (correlation == null) {
            return;
        }
        String type = text(event, "type");
        JsonNode data = event.path("data");
        synchronized (closed) {
            if (closed.contains(correlation) && !"human".equals(data.path("from").asText(""))) {
                LOG.infof("ignoring %s for closed incident %s", type, correlation);
                return;
            }
        }

        lastSeen.put(correlation, System.currentTimeMillis());
        String intent = data.path("intent").asText("");
        if (!"human".equals(data.path("from").asText("")) && ("ack".equals(intent) || "observe".equals(intent))) {
            // Courtesy is not a decision. Remember it, do not answer it.
            remember(correlation, describe(type, data));
            return;
        }
        if (type != null && type.endsWith("Detected")) {
            evidence.put(correlation, data);
        }
        String from = data.path("from").asText("");
        boolean humanChat = "human".equals(from) && !runtime.incidents().inFlight(correlation);
        if (!humanChat) {
            // A human saying hello is a conversation, not an incident: it must not
            // occupy the one incident slot and block the next real one.
            runtime.incidents().open(correlation);
        }
        int turn = runtime.incidents().turn(correlation);

        remember(correlation, describe(type, data));

        LlmClient.Reply reply = llm.chat(List.of(
                LlmClient.Message.system(systemPrompt()),
                LlmClient.Message.user(prompt(correlation, type, data))));

        if (!reply.ok()) {
            // A failed call is never published as a turn — the fallback says so
            // plainly instead of inventing a decision.
            LOG.warnf("llm %s on %s; using fallback", reply.status(), correlation);
            // To the human only: a fallback line must not wake the whole team.
            post(correlation, List.of("human"), "observe", turn,
                    store.config().model.fallbackText, null);
            return;
        }

        Decision d = parse(reply.content());
        if (d == null && "human".equals(from)) {
            // Silence is never an answer to a person. Ask once more, plainly.
            reply = llm.chat(List.of(
                    LlmClient.Message.system(systemPrompt()),
                    LlmClient.Message.user(prompt(correlation, type, data)
                            + "\nThe last message is from the human operator. Answer them directly, "
                            + "in their language, with intent \"answer\" and to [\"human\"].\n")));
            d = reply.ok() ? parse(reply.content()) : null;
            if (d == null) {
                d = new Decision(List.of("human"), "answer", "I am here. No incident is open right now.");
            }
        }
        if (d == null) {
            LOG.infof("maestro stays silent on %s", correlation);
            return;
        }
        if ("close".equals(d.intent())) {
            // A close is for everyone: the owners drop the incident from their
            // own books on hearing it, which is what frees them for the next one.
            d = new Decision(List.of("all"), "close", d.text());
        }
        post(correlation, d.to(), d.intent(), turn, d.text(), reply.reasoning());
        remember(correlation, "maestro → " + String.join(",", d.to()) + ": " + d.text());

        if ("close".equals(d.intent())) {
            closeIncident(correlation, "closed after " + turn + " turn(s)");
        }
    }

    private void closeIncident(String correlation, String why) {
        runtime.incidents().close(correlation);
        evidence.remove(correlation);
        lastSeen.remove(correlation);
        synchronized (closed) {
            closed.addLast(correlation);
            while (closed.size() > 50) {
                closed.removeFirst();
            }
        }
        LOG.infof("incident %s %s", correlation, why);
    }

    /** An incident nobody closed must not block the next one forever. */
    private void closeIdle() {
        long cutoff = System.currentTimeMillis() - idleCloseMinutes * 60_000L;
        for (var e : lastSeen.entrySet()) {
            if (e.getValue() < cutoff && runtime.incidents().inFlight(e.getKey())) {
                worker.submit(() -> {
                    int turn = runtime.incidents().turn(e.getKey());
                    post(e.getKey(), List.of("all"), "close", turn,
                            "No activity for " + idleCloseMinutes + " minutes. Closing this incident as stale.", null);
                    closeIncident(e.getKey(), "closed as idle");
                });
            }
        }
    }

    /** The prompt says what to do; the soul (ADR-0004) says who is doing it. Both go in. */
    private String systemPrompt() {
        String configured = store.config().systemPrompt;
        String base = configured != null && !configured.isBlank() ? configured : Prompts.DEFAULT_SYSTEM;
        String soul = store.config().identity.soul;
        return soul == null || soul.isBlank() ? base : base + "\nWHO YOU ARE\n" + soul.trim() + "\n";
    }

    /**
     * Volatile content goes LAST. The system prompt and the soul are a stable
     * prefix so the provider's prompt cache actually hits — on deepseek that was
     * 384 of 477 prompt tokens on a repeat call.
     */
    private String prompt(String correlation, String type, JsonNode data) {
        StringBuilder sb = new StringBuilder();
        sb.append("Incident: ").append(correlation).append('\n');
        Deque<String> thread = threads.get(correlation);
        if (thread != null && thread.size() > 1) {
            sb.append("\nWhat has happened so far:\n");
            thread.forEach(line -> sb.append("- ").append(line).append('\n'));
        }
        sb.append("\nLatest: ").append(type).append('\n');
        sb.append(data.toPrettyString()).append('\n');
        String first = team.isEmpty() ? "human" : team.get(0);
        sb.append("""

                Reply with ONLY a JSON object, no prose around it:
                {"to":["%s"],"intent":"delegate","text":"one or two sentences"}

                to      — %s
                intent  — delegate | ask | answer | observe | close | none (post nothing)
                text    — what you say. Short. No markdown.
                """.formatted(first, addressees()));
        return sb.toString();
    }

    /** Everyone Maestro may address: the configured team, then the human and the whole room. */
    private String addressees() {
        List<String> names = new ArrayList<>(team);
        names.add("human");
        names.add("all");
        return String.join(" | ", names);
    }

    /** Tolerant on purpose: a model that wraps JSON in prose still gets understood. */
    private Decision parse(String content) {
        String c = content == null ? "" : content.trim();
        int start = c.indexOf('{');
        int end = c.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                JsonNode n = mapper.readTree(c.substring(start, end + 1));
                List<String> to = new ArrayList<>();
                n.path("to").forEach(v -> to.add(v.asText()));
                if (to.isEmpty()) {
                    to.add("all");
                }
                String text = n.path("text").asText("").trim();
                String intent = n.path("intent").asText("observe").trim();
                if ("none".equals(intent) || "silent".equals(intent) || text.isEmpty()) {
                    return null; // valid JSON that says nothing is a decision to stay quiet
                }
                return new Decision(to, intent, text);
            } catch (Exception e) {
                LOG.debugf("reply was not JSON, using it as prose: %s", e.getMessage());
            }
        }
        // A reply that starts as JSON but never closes is a model that ran out
        // of budget mid-sentence. Salvage the intent rather than posting braces
        // on a projector.
        if (start >= 0) {
            java.util.regex.Matcher mi = java.util.regex.Pattern.compile("\"intent\"\\s*:\\s*\"(\\w+)\"").matcher(c);
            if (mi.find()) {
                String intent = mi.group(1);
                java.util.regex.Matcher mt = java.util.regex.Pattern.compile("\"text\"\\s*:\\s*\"([^\"]*)").matcher(c);
                String text = mt.find() ? mt.group(1).trim() : "";
                if ("none".equals(intent)) {
                    return null;
                }
                if (text.isEmpty()) {
                    text = "close".equals(intent) ? "Incident closed." : "(cut off)";
                }
                LOG.warnf("salvaged a truncated reply: intent=%s", intent);
                return new Decision(List.of("all"), intent, text);
            }
            return null;
        }
        // Still say something real rather than dropping the turn.
        return new Decision(List.of("all"), "observe", c.isEmpty() ? "(no reply)" : c);
    }

    private void post(String correlation, List<String> to, String intent, int turn,
                      String text, String reasoning) {
        ObjectNode data = mapper.createObjectNode();
        data.put("from", "maestro");
        var arr = data.putArray("to");
        to.forEach(arr::add);
        data.put("intent", intent);
        data.put("turn", turn);
        data.put("text", text);
        if (reasoning != null && !reasoning.isBlank()) {
            data.put("reasoning", reasoning);
        }
        JsonNode ctx = evidence.get(correlation);
        if (ctx != null) {
            data.set("context", ctx);
        }
        bus.publish(conversationTopic, CloudEvent.of(
                "io.konzert.MessagePosted", "/konzert/agents/maestro", correlation, data));
    }

    private void remember(String correlation, String line) {
        threads.computeIfAbsent(correlation, k -> new ArrayDeque<>()).addLast(line);
        Deque<String> t = threads.get(correlation);
        while (t.size() > 12) {
            t.removeFirst();
        }
    }

    private static String describe(String type, JsonNode data) {
        String from = data.path("from").asText("");
        String text = data.path("text").asText("");
        if (!text.isEmpty()) {
            return (from.isEmpty() ? "someone" : from) + ": " + text;
        }
        String shortType = type == null ? "event" : type.substring(type.lastIndexOf('.') + 1);
        return shortType + " " + data.toString();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private record Decision(List<String> to, String intent, String text) {}

    /** Exposed for the panel so an operator can see the thread Maestro is holding. */
    public Map<String, Object> threads() {
        return Map.copyOf(threads);
    }
}
