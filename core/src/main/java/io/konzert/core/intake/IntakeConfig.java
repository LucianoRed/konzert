package io.konzert.core.intake;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The agent's filter, as the Control Panel edits it and as Helm seeds it.
 *
 * Everything here is data. There is no place in this object for code, and that
 * is the point: an operator can read a filter and know what it does.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class IntakeConfig {

    /** Stop consuming entirely. Autonomy "off" sets this. */
    public boolean enabled = true;

    /** Who this agent is, so it can ignore its own messages. */
    public String selfName = "agent";

    public List<Subscription> subscriptions = new ArrayList<>();
    public List<Rule> rules = new ArrayList<>();
    public Dedup dedup = new Dedup();
    public Debounce debounce = new Debounce();
    public Budget budget = new Budget();

    /** Max conversation turns per correlation ID before the story is closed. */
    public int turnBudget = 12;
    /** How many incidents the agent will work on at once. */
    public int maxConcurrentIncidents = 1;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Subscription {
        public String topic;
        /** CloudEvent types admitted from this topic. Empty means "any type". */
        public List<String> types = new ArrayList<>();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Rule {
        public String name;
        public Predicate when;
        /** reason | triage | deterministic:&lt;handler&gt; | count | drop */
        public String then = "drop";
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Dedup {
        public boolean enabled = true;
        /** Fields whose values, joined, identify "the same fact again". */
        public List<String> fingerprint = List.of("type", "data.namespace", "data.workload");
        public String window = "5m";
        /** count = still counted in the funnel; drop = invisible. */
        public String onDuplicate = "count";
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Debounce {
        public boolean enabled = true;
        public String window = "10s";
        public int maxBatch = 5;
        public List<String> groupBy = List.of("data.workload");
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Budget {
        public int eventsPerMinute = 6;
        public int llmCallsPerHour = 60;
        public int tokensPerHour = 100_000;
        /** queue | drop | downgrade */
        public String onExceed = "downgrade";
    }

    /**
     * Default deny, spelled out. A config with no rules admits nothing, which is
     * the safe state for an agent pointed at a shared topic and a real API key.
     */
    public static IntakeConfig denyAll(String selfName) {
        IntakeConfig c = new IntakeConfig();
        c.selfName = selfName;
        return c;
    }

    /** Parsed once per config load rather than per event. */
    public long dedupWindowMillis() {
        return parseDuration(dedup == null ? null : dedup.window, 300_000);
    }

    public long debounceWindowMillis() {
        return parseDuration(debounce == null ? null : debounce.window, 10_000);
    }

    /** Accepts "30s", "5m", "1h", or plain milliseconds. */
    static long parseDuration(String s, long fallback) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        String v = s.trim().toLowerCase();
        try {
            char unit = v.charAt(v.length() - 1);
            if (Character.isDigit(unit)) {
                return Long.parseLong(v);
            }
            long n = Long.parseLong(v.substring(0, v.length() - 1).trim());
            return switch (unit) {
                case 's' -> n * 1000L;
                case 'm' -> n * 60_000L;
                case 'h' -> n * 3_600_000L;
                case 'd' -> n * 86_400_000L;
                default -> fallback;
            };
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** The topics this agent should actually subscribe to. */
    public List<String> topics() {
        List<String> out = new ArrayList<>();
        for (Subscription s : subscriptions) {
            if (s.topic != null && !s.topic.isBlank() && !out.contains(s.topic)) {
                out.add(s.topic);
            }
        }
        return out;
    }

    public Map<String, List<String>> typesByTopic() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Subscription s : subscriptions) {
            if (s.topic != null) {
                out.put(s.topic, s.types == null ? List.of() : s.types);
            }
        }
        return out;
    }
}
