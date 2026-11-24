package io.konzert.core.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import io.konzert.core.config.ConfigStore;
import io.konzert.core.events.KafkaBus;
import io.konzert.core.intake.Funnel;
import io.konzert.core.intake.IntakeConfig;
import io.konzert.core.intake.IntakePipeline;
import io.konzert.core.intake.Verdict;
import io.konzert.core.llm.LlmClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Wires the bus to the intake filter to the agent's own logic, so that an agent
 * only has to say what to do with what survived.
 *
 * Every agent in this platform gets this for free, which is the point of
 * ADR-0007: there is one filter implementation, not one per agent.
 */
@ApplicationScoped
public class AgentRuntime {

    private static final Logger LOG = Logger.getLogger(AgentRuntime.class);

    @Inject
    ConfigStore store;
    @Inject
    KafkaBus bus;
    @Inject
    LlmClient llm;

    private volatile IntakePipeline pipeline;
    private final Incidents incidents = new Incidents();
    private volatile BiConsumer<String, JsonNode> handler;
    /** The last verdicts, so the panel can show what the filter has been doing. */
    private final java.util.concurrent.ConcurrentLinkedDeque<Map<String, Object>> recent =
            new java.util.concurrent.ConcurrentLinkedDeque<>();

    @PostConstruct
    void init() {
        pipeline = new IntakePipeline(store.intake(), System::currentTimeMillis, incidents);
        // Live reconfiguration: the panel edits a ConfigMap, the filter changes
        // mid-demo without a restart.
        store.onChange(s -> {
            pipeline.reconfigure(s.intake());
            LOG.infof("intake reconfigured: %d rule(s), %s",
                    s.intake().rules.size(), s.intake().enabled ? "enabled" : "disabled");
        });
        llm.onTokens((prompt, completion) -> pipeline.recordTokens(prompt, completion));
    }

    /** Start consuming. The handler only ever sees what the filter admitted. */
    public void start(BiConsumer<String, JsonNode> onAdmitted) {
        this.handler = onAdmitted;
        List<String> topics = store.intake().topics();
        if (topics.isEmpty()) {
            LOG.warn("intake declares no subscriptions — this agent will hear nothing");
            return;
        }
        bus.consume(topics, this::route);
    }

    private void route(String topic, JsonNode event) {
        Verdict v = pipeline.evaluate(topic, event, true);
        remember(topic, event, v);
        if (!v.reachesModel()) {
            return;
        }
        try {
            handler.accept(topic, event);
        } catch (RuntimeException e) {
            LOG.errorf(e, "agent handler failed on %s", topic);
        }
    }

    private void remember(String topic, JsonNode event, Verdict v) {
        recent.addLast(Map.of(
                "at", java.time.Instant.now().toString(),
                "topic", topic,
                "type", String.valueOf(IntakeSupport.type(event)),
                "lane", v.lane(),
                "stage", v.stage(),
                "rule", String.valueOf(v.rule()),
                "reason", String.valueOf(v.reason())));
        while (recent.size() > 100) {
            recent.pollFirst();
        }
    }

    public IntakePipeline pipeline() {
        return pipeline;
    }

    public Funnel funnel() {
        return pipeline.funnel();
    }

    public Incidents incidents() {
        return incidents;
    }

    public List<Map<String, Object>> recentVerdicts() {
        return List.copyOf(recent);
    }

    /**
     * Replays real traffic from the subscribed topics through a candidate
     * filter. No model is called and the live funnel is untouched — this is what
     * makes tuning a filter measured instead of guessed.
     */
    public Map<String, Object> dryRun(IntakeConfig candidate, int sampleSize) {
        List<String> topics = candidate.topics().isEmpty()
                ? store.intake().topics() : candidate.topics();
        var samples = bus.readRecent(topics, sampleSize, 3_600_000L).stream()
                .map(s -> new IntakePipeline.Sample(s.topic(), s.event()))
                .toList();
        return IntakePipeline.dryRun(candidate, samples);
    }

    /** Which incidents are open, and how many turns each has burned. */
    public static class Incidents implements IntakePipeline.IncidentState {
        private final Set<String> open = ConcurrentHashMap.newKeySet();
        private final Map<String, Integer> turns = new ConcurrentHashMap<>();

        public void open(String correlationId) {
            open.add(correlationId);
        }

        public void close(String correlationId) {
            open.remove(correlationId);
        }

        public int turn(String correlationId) {
            return turns.merge(correlationId, 1, Integer::sum);
        }

        @Override
        public boolean inFlight(String correlationId) {
            return correlationId != null && open.contains(correlationId);
        }

        @Override
        public int openIncidents() {
            return open.size();
        }

        @Override
        public int turnsSoFar(String correlationId) {
            return turns.getOrDefault(correlationId, 0);
        }

        public Map<String, Object> snapshot() {
            return Map.of("open", List.copyOf(open), "turns", Map.copyOf(turns));
        }
    }

    /** Small helper kept package-visible so the runtime does not depend on Jackson paths. */
    static final class IntakeSupport {
        static String type(JsonNode event) {
            JsonNode n = event == null ? null : event.get("type");
            return n == null ? null : n.asText();
        }
    }
}
