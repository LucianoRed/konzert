package io.konzert.core.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the Control Panel edits, except credentials.
 *
 * Credentials live in {@link AgentSecrets} and in a Kubernetes Secret. Keeping
 * them in a different class and a different object is not tidiness — it is what
 * makes it structurally impossible to leak a key into a ConfigMap by adding a
 * field here.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentConfig {

    public Identity identity = new Identity();
    public Model model = new Model();
    public String systemPrompt = "";
    public Rag rag = new Rag();
    public Memory memory = new Memory();
    public Tools tools = new Tools();

    /** off = consume nothing · propose = reason but never execute · act = execute */
    public String autonomy = "propose";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Identity {
        public String name = "agent";
        public String displayName = "Agent";
        public String avatar = "";
        public String emoji = "🤖";
        public String color = "#6b7280";
        public String role = "";
        /** Character, tone and boundaries — distinct from the system prompt (ADR-0004). */
        public String soul = "";
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Model {
        /** aigateway | deepseek | openai | openrouter | custom */
        public String provider = "deepseek";
        public String baseUrl = "https://api.deepseek.com";
        public String model = "deepseek-v4-flash";
        /** Used by the triage lane. May be the same model with a much smaller prompt. */
        public String triageModel = "deepseek-v4-flash";
        /** low | high — honoured by reasoning models that accept it. */
        public String reasoningEffort = "low";
        public double temperature = 0.2;
        /**
         * Reasoning tokens are spent from this budget BEFORE any content is
         * produced: too small and the model returns an empty string, not a short
         * answer. Verified against deepseek-v4-flash. Do not lower casually.
         */
        public int maxTokens = 1200;
        public int timeoutSeconds = 60;
        public String fallbackText = "I could not think that through in time — holding position.";
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Rag {
        public boolean enabled = false;
        public String embeddingModel = "";
        public int topK = 4;
        public double minScore = 0.6;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Memory {
        public int windowMessages = 20;
        public boolean compaction = false;
        public int compactionThreshold = 40;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Tools {
        public List<McpServer> mcpServers = new ArrayList<>();
        /**
         * A disabled tool is not described to the model at all: it costs no
         * prompt tokens and cannot be hallucinated into existence.
         */
        public List<String> disabledTools = new ArrayList<>();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class McpServer {
        public String name = "";
        public String sseUrl = "";
        public boolean enabled = true;
    }

    public boolean mayExecute() {
        return "act".equals(autonomy);
    }

    public boolean consumesEvents() {
        return !"off".equals(autonomy);
    }
}
