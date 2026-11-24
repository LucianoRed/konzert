package io.konzert.core.intake;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The seven gates between a Kafka message and a token (ADR-0007).
 *
 *   subscription -> rules -> dedup -> correlation -> debounce -> lane -> budget
 *
 * Every stage is deterministic and costs nothing. The model is the last resort,
 * not the first responder.
 *
 * This class is pure logic with an injectable clock, which is what makes the
 * panel's dry run possible: the same code that decides live traffic can be run
 * over recent topic history without calling anything.
 */
public class IntakePipeline {

    /** Tells the pipeline whether a correlation ID is already being worked on. */
    public interface IncidentState {
        boolean inFlight(String correlationId);
        int openIncidents();
        int turnsSoFar(String correlationId);
    }

    private static final IncidentState NO_INCIDENTS = new IncidentState() {
        public boolean inFlight(String correlationId) { return false; }
        public int openIncidents() { return 0; }
        public int turnsSoFar(String correlationId) { return 0; }
    };

    private volatile IntakeConfig config;
    private final Funnel funnel = new Funnel();
    private final LongSupplier clock;
    private final IncidentState incidents;

    /** fingerprint -> last time it was admitted */
    private final Map<String, Long> recentFingerprints = new ConcurrentHashMap<>();
    /** minute/hour buckets for the budget stage */
    private final AtomicLong minuteBucket = new AtomicLong();
    private final AtomicLong minuteCount = new AtomicLong();
    private final AtomicLong hourBucket = new AtomicLong();
    private final AtomicLong hourCalls = new AtomicLong();
    private final AtomicLong hourTokens = new AtomicLong();

    public IntakePipeline(IntakeConfig config) {
        this(config, System::currentTimeMillis, NO_INCIDENTS);
    }

    public IntakePipeline(IntakeConfig config, LongSupplier clock, IncidentState incidents) {
        this.config = config == null ? IntakeConfig.denyAll("agent") : config;
        this.clock = clock;
        this.incidents = incidents == null ? NO_INCIDENTS : incidents;
    }

    public void reconfigure(IntakeConfig updated) {
        this.config = updated == null ? IntakeConfig.denyAll("agent") : updated;
        // Counters survive a config change on purpose: the panel's whole workflow
        // is "tighten a rule and watch the funnel move".
        this.recentFingerprints.clear();
    }

    public IntakeConfig config() {
        return config;
    }

    public Funnel funnel() {
        return funnel;
    }

    /**
     * @param topic  where it arrived
     * @param event  the CloudEvent
     * @param record whether to move the counters — false for a dry run, so
     *               testing a candidate filter never pollutes the real funnel
     */
    public Verdict evaluate(String topic, JsonNode event, boolean record) {
        IntakeConfig cfg = config;
        if (record) {
            funnel.seen();
        }

        if (!cfg.enabled) {
            return Verdict.drop("subscription", "intake disabled (autonomy off)");
        }
        if (event == null || !event.isObject()) {
            return Verdict.drop("subscription", "not a JSON object");
        }

        // ---- 1. Subscription: topic and type, before anything is interpreted.
        Map<String, List<String>> byTopic = cfg.typesByTopic();
        if (!byTopic.isEmpty()) {
            if (topic != null && !byTopic.containsKey(topic)) {
                return Verdict.drop("subscription", "topic not subscribed: " + topic);
            }
            List<String> types = topic == null ? List.of() : byTopic.get(topic);
            String type = text(event, "type");
            if (types != null && !types.isEmpty() && !types.contains(type)) {
                return Verdict.drop("subscription", "type not subscribed: " + type);
            }
        }

        // An agent that answers itself is an agent that never stops.
        String from = text(event, "data.from");
        if (from != null && from.equals(cfg.selfName)) {
            return Verdict.drop("subscription", "own message");
        }
        if (record) {
            funnel.subscribed();
        }

        // ---- 2. Rules: ordered, first match wins, default deny.
        IntakeConfig.Rule hit = null;
        for (IntakeConfig.Rule rule : cfg.rules) {
            if (rule.when == null || rule.when.test(event)) {
                hit = rule;
                break;
            }
        }
        if (hit == null) {
            return Verdict.drop("rules", "no rule matched (default deny)");
        }
        if (record) {
            funnel.rule(hit.name);
        }
        String lane = hit.then == null ? Verdict.DROP : hit.then.trim();
        if (Verdict.DROP.equals(lane)) {
            return Verdict.of(Verdict.DROP, "rules", hit.name, "rule says drop");
        }
        if (record) {
            funnel.matched();
        }

        // Cheap lanes skip every remaining stage — they cost nothing to admit.
        if (Verdict.COUNT.equals(lane) || lane.startsWith(Verdict.DETERMINISTIC)) {
            if (record) {
                funnel.lane(lane);
            }
            return Verdict.of(lane, "lane", hit.name, "no model involved");
        }

        // ---- 3. Dedup: the same fact again is not new information.
        if (cfg.dedup != null && cfg.dedup.enabled) {
            String fp = fingerprint(event, cfg.dedup.fingerprint);
            long now = clock.getAsLong();
            long window = cfg.dedupWindowMillis();
            Long last = recentFingerprints.get(fp);
            if (last != null && now - last < window) {
                if (record) {
                    funnel.deduped();
                    if ("count".equals(cfg.dedup.onDuplicate)) {
                        funnel.lane(Verdict.COUNT);
                    }
                }
                return Verdict.of("count".equals(cfg.dedup.onDuplicate) ? Verdict.COUNT : Verdict.DROP,
                        "dedup", hit.name, "same fingerprint within " + cfg.dedup.window);
            }
            if (record) {
                recentFingerprints.put(fp, now);
                pruneFingerprints(now, window);
            }
        }

        // ---- 4. Correlation join: an event about a story already being worked
        // on is context for that story, not a reason to start reasoning again.
        String correlation = text(event, "correlationid");
        if (correlation != null && incidents.inFlight(correlation)) {
            if (incidents.turnsSoFar(correlation) >= cfg.turnBudget) {
                if (record) {
                    funnel.throttled();
                }
                return Verdict.of(Verdict.COUNT, "correlation", hit.name,
                        "turn budget of " + cfg.turnBudget + " reached for " + correlation);
            }
            if (record) {
                funnel.joined();
            }
            return Verdict.of(lane, "correlation", hit.name, "joins incident " + correlation);
        }
        if (incidents.openIncidents() >= cfg.maxConcurrentIncidents) {
            if (record) {
                funnel.throttled();
            }
            return Verdict.of(Verdict.COUNT, "correlation", hit.name,
                    "already working on " + incidents.openIncidents() + " incident(s)");
        }

        // ---- 5. Debounce is a timing decision the caller applies; the pipeline
        // only says whether this event is eligible to be batched with others.
        if (record && cfg.debounce != null && cfg.debounce.enabled) {
            funnel.batched();
        }

        // ---- 6/7. Lane and budget.
        Verdict throttle = checkBudget(cfg, record);
        if (throttle != null) {
            if (record) {
                funnel.lane(throttle.lane());
            }
            return throttle;
        }

        if (record) {
            funnel.lane(lane);
        }
        return Verdict.of(lane, "lane", hit.name, "admitted");
    }

    /** @return null when within budget, otherwise what to do instead. */
    private Verdict checkBudget(IntakeConfig cfg, boolean record) {
        if (cfg.budget == null) {
            return null;
        }
        long now = clock.getAsLong();
        long minute = now / 60_000L;
        long hour = now / 3_600_000L;

        if (minuteBucket.getAndSet(minute) != minute) {
            minuteCount.set(0);
        }
        if (hourBucket.getAndSet(hour) != hour) {
            hourCalls.set(0);
            hourTokens.set(0);
        }

        long inMinute = record ? minuteCount.incrementAndGet() : minuteCount.get() + 1;
        long inHour = record ? hourCalls.incrementAndGet() : hourCalls.get() + 1;

        String exceeded = null;
        if (cfg.budget.eventsPerMinute > 0 && inMinute > cfg.budget.eventsPerMinute) {
            exceeded = "eventsPerMinute " + cfg.budget.eventsPerMinute;
        } else if (cfg.budget.llmCallsPerHour > 0 && inHour > cfg.budget.llmCallsPerHour) {
            exceeded = "llmCallsPerHour " + cfg.budget.llmCallsPerHour;
        } else if (cfg.budget.tokensPerHour > 0 && hourTokens.get() > cfg.budget.tokensPerHour) {
            exceeded = "tokensPerHour " + cfg.budget.tokensPerHour;
        }
        if (exceeded == null) {
            return null;
        }

        if (record) {
            funnel.throttled();
        }
        // Whatever it does, it must be visible. An agent silently throttled is
        // the worst thing to debug on a stage.
        String lane = switch (cfg.budget.onExceed == null ? "downgrade" : cfg.budget.onExceed) {
            case "drop" -> Verdict.DROP;
            case "queue" -> Verdict.COUNT;
            default -> Verdict.TRIAGE;
        };
        return Verdict.of(lane, "budget", null, "over budget: " + exceeded);
    }

    /** Called by the LLM client after each call so token budgets are real. */
    public void recordTokens(long prompt, long completion) {
        hourTokens.addAndGet(prompt + completion);
        funnel.tokens(prompt, completion);
    }

    private void pruneFingerprints(long now, long window) {
        if (recentFingerprints.size() < 500) {
            return;
        }
        recentFingerprints.entrySet().removeIf(e -> now - e.getValue() > window);
    }

    static String fingerprint(JsonNode event, List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return event.toString();
        }
        StringBuilder sb = new StringBuilder();
        for (String f : fields) {
            sb.append(f).append('=').append(text(event, f)).append('|');
        }
        return sb.toString();
    }

    static String text(JsonNode event, String path) {
        JsonNode n = Predicate.resolve(event, path);
        return n == null || n.isNull() ? null : (n.isValueNode() ? n.asText() : n.toString());
    }

    /**
     * Runs a candidate config over a batch of real events and reports what it
     * would have done — without touching the live funnel and without calling a
     * model. This is the panel's "Dry run".
     */
    public static Map<String, Object> dryRun(IntakeConfig candidate, List<Sample> samples) {
        IntakePipeline probe = new IntakePipeline(candidate);
        var perEvent = new java.util.ArrayList<Map<String, Object>>();
        for (Sample s : samples) {
            Verdict v = probe.evaluate(s.topic(), s.event(), true);
            var row = new LinkedHashMap<String, Object>();
            row.put("topic", s.topic());
            row.put("type", text(s.event(), "type"));
            row.put("correlationid", text(s.event(), "correlationid"));
            row.put("lane", v.lane());
            row.put("stage", v.stage());
            row.put("rule", v.rule());
            row.put("reason", v.reason());
            perEvent.add(row);
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("sampled", samples.size());
        out.put("funnel", probe.funnel().snapshot());
        out.put("events", perEvent);
        return out;
    }

    public record Sample(String topic, JsonNode event) {}
}
