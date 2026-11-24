package io.konzert.core.intake;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What the Control Panel draws, and the honest answer to "what is this agent
 * costing me".
 *
 * Every stage that rejects an event increments a counter here. A filter that
 * drops the wrong thing shows up as a number, not as an agent that mysteriously
 * says nothing.
 */
public class Funnel {

    private final AtomicLong seen = new AtomicLong();
    private final AtomicLong subscribed = new AtomicLong();
    private final AtomicLong matched = new AtomicLong();
    private final AtomicLong deduped = new AtomicLong();
    private final AtomicLong joined = new AtomicLong();
    private final AtomicLong batched = new AtomicLong();
    private final AtomicLong throttled = new AtomicLong();
    private final AtomicLong counted = new AtomicLong();
    private final AtomicLong deterministic = new AtomicLong();
    private final AtomicLong triaged = new AtomicLong();
    private final AtomicLong reasoned = new AtomicLong();
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();

    /** Which rule decided, and how often — so a dead rule is visible as a zero. */
    private final Map<String, AtomicLong> byRule = new LinkedHashMap<>();

    void seen() { seen.incrementAndGet(); }
    void subscribed() { subscribed.incrementAndGet(); }
    void matched() { matched.incrementAndGet(); }
    void deduped() { deduped.incrementAndGet(); }
    void joined() { joined.incrementAndGet(); }
    void batched() { batched.incrementAndGet(); }
    void throttled() { throttled.incrementAndGet(); }

    void lane(String lane) {
        switch (lane) {
            case Verdict.REASON -> reasoned.incrementAndGet();
            case Verdict.TRIAGE -> triaged.incrementAndGet();
            case Verdict.COUNT -> counted.incrementAndGet();
            default -> {
                if (lane != null && lane.startsWith(Verdict.DETERMINISTIC)) {
                    deterministic.incrementAndGet();
                }
            }
        }
    }

    void rule(String name) {
        if (name == null) {
            return;
        }
        synchronized (byRule) {
            byRule.computeIfAbsent(name, k -> new AtomicLong()).incrementAndGet();
        }
    }

    /** Called by the LLM client so the funnel ends in tokens, not in guesses. */
    public void tokens(long prompt, long completion) {
        promptTokens.addAndGet(prompt);
        completionTokens.addAndGet(completion);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seen", seen.get());
        m.put("subscribed", subscribed.get());
        m.put("matched", matched.get());
        m.put("deduped", deduped.get());
        m.put("joined", joined.get());
        m.put("batched", batched.get());
        m.put("throttled", throttled.get());
        m.put("counted", counted.get());
        m.put("deterministic", deterministic.get());
        m.put("triaged", triaged.get());
        m.put("reasoned", reasoned.get());
        m.put("promptTokens", promptTokens.get());
        m.put("completionTokens", completionTokens.get());
        Map<String, Long> rules = new LinkedHashMap<>();
        synchronized (byRule) {
            byRule.forEach((k, v) -> rules.put(k, v.get()));
        }
        m.put("byRule", rules);
        return m;
    }

    public void reset() {
        seen.set(0); subscribed.set(0); matched.set(0); deduped.set(0);
        joined.set(0); batched.set(0); throttled.set(0); counted.set(0);
        deterministic.set(0); triaged.set(0); reasoned.set(0);
        promptTokens.set(0); completionTokens.set(0);
        synchronized (byRule) {
            byRule.clear();
        }
    }
}
