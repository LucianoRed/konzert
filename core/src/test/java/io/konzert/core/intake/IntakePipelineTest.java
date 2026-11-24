package io.konzert.core.intake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The intake filter decides what costs money. It gets tested, not assumed.
 */
class IntakePipelineTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode event(String json) {
        try {
            return M.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static JsonNode memoryEvent(String workload) {
        return event("""
                {"specversion":"1.0",
                 "type":"io.konzert.MemoryPressureDetected",
                 "source":"/konzert/sensor","correlationid":"incident-1",
                 "data":{"namespace":"konzert-demo","workload":"%s","from":"sensor",
                         "severity":"high","evidence":{"podMemoryBytes":1932735283}}}
                """.formatted(workload));
    }

    private static IntakeConfig config(String rulesJson) {
        try {
            IntakeConfig c = IntakeConfig.denyAll("atlas");
            IntakeConfig.Subscription s = new IntakeConfig.Subscription();
            s.topic = "konzert.events";
            s.types = List.of("io.konzert.MemoryPressureDetected");
            c.subscriptions = List.of(s);
            c.rules = List.of(M.readValue(rulesJson, IntakeConfig.Rule[].class));
            return c;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void admitsNothingWithoutRules() {
        IntakeConfig cfg = IntakeConfig.denyAll("atlas");
        Verdict v = new IntakePipeline(cfg).evaluate("konzert.events", memoryEvent("checkout-api"), true);
        assertEquals(Verdict.DROP, v.lane());
        assertEquals("rules", v.stage());
        assertFalse(v.reachesModel(), "default deny must not reach the model");
    }

    @Test
    void dropsUnsubscribedTypesBeforeAnythingElse() {
        IntakeConfig cfg = config("""
                [{"name":"anything","then":"reason"}]
                """);
        JsonNode other = event("""
                {"type":"io.konzert.SomethingElse","data":{"namespace":"konzert-demo"}}
                """);
        Verdict v = new IntakePipeline(cfg).evaluate("konzert.events", other, true);
        assertEquals("subscription", v.stage());
        assertFalse(v.reachesModel());
    }

    @Test
    void neverReactsToItsOwnMessages() {
        IntakeConfig cfg = config("""
                [{"name":"anything","then":"reason"}]
                """);
        JsonNode mine = event("""
                {"type":"io.konzert.MemoryPressureDetected",
                 "data":{"namespace":"konzert-demo","from":"atlas"}}
                """);
        Verdict v = new IntakePipeline(cfg).evaluate("konzert.events", mine, true);
        assertEquals("own message", v.reason());
    }

    @Test
    void firstMatchingRuleWins() {
        IntakeConfig cfg = config("""
                [{"name":"not mine",
                  "when":{"field":"data.namespace","op":"neq","value":"konzert-demo"},
                  "then":"drop"},
                 {"name":"high severity only",
                  "when":{"all":[{"field":"data.severity","op":"in","value":["high","critical"]}]},
                  "then":"reason"},
                 {"name":"rest","then":"count"}]
                """);
        IntakePipeline p = new IntakePipeline(cfg);
        assertEquals("high severity only",
                p.evaluate("konzert.events", memoryEvent("checkout-api"), true).rule());

        JsonNode low = event("""
                {"type":"io.konzert.MemoryPressureDetected",
                 "data":{"namespace":"konzert-demo","severity":"info","workload":"x"}}
                """);
        Verdict v = p.evaluate("konzert.events", low, true);
        assertEquals("rest", v.rule());
        assertEquals(Verdict.COUNT, v.lane());
        assertFalse(v.reachesModel(), "the count lane must not cost a token");
    }

    @Test
    void sameFactTwiceIsOneReasoningTurn() {
        IntakeConfig cfg = config("""
                [{"name":"all","then":"reason"}]
                """);
        cfg.dedup.window = "5m";
        AtomicLong now = new AtomicLong(1_000_000L);
        IntakePipeline p = new IntakePipeline(cfg, now::get, null);

        assertTrue(p.evaluate("konzert.events", memoryEvent("checkout-api"), true).reachesModel());
        assertFalse(p.evaluate("konzert.events", memoryEvent("checkout-api"), true).reachesModel(),
                "a repeat inside the window must not reach the model");

        // A different workload is a different fact.
        assertTrue(p.evaluate("konzert.events", memoryEvent("payments-api"), true).reachesModel());

        // Past the window it is news again.
        now.addAndGet(6 * 60_000L);
        assertTrue(p.evaluate("konzert.events", memoryEvent("checkout-api"), true).reachesModel());
    }

    @Test
    void budgetDowngradesInsteadOfBurningTokens() {
        IntakeConfig cfg = config("""
                [{"name":"all","then":"reason"}]
                """);
        cfg.dedup.enabled = false;
        cfg.budget.eventsPerMinute = 2;
        cfg.budget.onExceed = "downgrade";
        AtomicLong now = new AtomicLong(0L);
        IntakePipeline p = new IntakePipeline(cfg, now::get, null);

        assertEquals(Verdict.REASON, p.evaluate("konzert.events", memoryEvent("a"), true).lane());
        assertEquals(Verdict.REASON, p.evaluate("konzert.events", memoryEvent("b"), true).lane());
        Verdict third = p.evaluate("konzert.events", memoryEvent("c"), true);
        assertEquals(Verdict.TRIAGE, third.lane(), "over budget must downgrade, not stay on the big model");
        assertTrue(third.reason().contains("over budget"));

        // Next minute, the allowance is back.
        now.addAndGet(61_000L);
        assertEquals(Verdict.REASON, p.evaluate("konzert.events", memoryEvent("d"), true).lane());
    }

    @Test
    void oneIncidentAtATime() {
        IntakeConfig cfg = config("""
                [{"name":"all","then":"reason"}]
                """);
        cfg.maxConcurrentIncidents = 1;
        IntakePipeline.IncidentState busy = new IntakePipeline.IncidentState() {
            public boolean inFlight(String c) { return false; }
            public int openIncidents() { return 1; }
            public int turnsSoFar(String c) { return 0; }
        };
        Verdict v = new IntakePipeline(cfg, System::currentTimeMillis, busy)
                .evaluate("konzert.events", memoryEvent("checkout-api"), true);
        assertFalse(v.reachesModel());
        assertEquals("correlation", v.stage());
    }

    @Test
    void turnBudgetClosesARunawayConversation() {
        IntakeConfig cfg = config("""
                [{"name":"all","then":"reason"}]
                """);
        cfg.turnBudget = 3;
        IntakePipeline.IncidentState chatty = new IntakePipeline.IncidentState() {
            public boolean inFlight(String c) { return true; }
            public int openIncidents() { return 1; }
            public int turnsSoFar(String c) { return 3; }
        };
        Verdict v = new IntakePipeline(cfg, System::currentTimeMillis, chatty)
                .evaluate("konzert.events", memoryEvent("checkout-api"), true);
        assertFalse(v.reachesModel(), "at the turn cap the story must stop costing money");
        assertTrue(v.reason().contains("turn budget"));
    }

    @Test
    void dryRunReportsWhatWouldHappenWithoutTouchingTheLiveFunnel() {
        IntakeConfig live = config("""
                [{"name":"all","then":"reason"}]
                """);
        IntakePipeline p = new IntakePipeline(live);
        p.evaluate("konzert.events", memoryEvent("checkout-api"), true);
        long reasonedBefore = (long) p.funnel().snapshot().get("reasoned");

        IntakeConfig candidate = config("""
                [{"name":"only payments",
                  "when":{"field":"data.workload","op":"eq","value":"payments-api"},
                  "then":"reason"},
                 {"name":"rest","then":"count"}]
                """);
        var result = IntakePipeline.dryRun(candidate, List.of(
                new IntakePipeline.Sample("konzert.events", memoryEvent("checkout-api")),
                new IntakePipeline.Sample("konzert.events", memoryEvent("payments-api")),
                new IntakePipeline.Sample("konzert.events", memoryEvent("orders-api"))));

        @SuppressWarnings("unchecked")
        var funnel = (java.util.Map<String, Object>) result.get("funnel");
        assertEquals(3, result.get("sampled"));
        assertEquals(1L, funnel.get("reasoned"), "only the payments event would reach the model");
        assertEquals(2L, funnel.get("counted"));
        assertEquals(reasonedBefore, (long) p.funnel().snapshot().get("reasoned"),
                "a dry run must not move the live counters");
    }

    @Test
    void predicatesNeverThrowOnBadInput() {
        IntakeConfig cfg = config("""
                [{"name":"broken regex",
                  "when":{"field":"data.workload","op":"matches","value":"[unclosed"},
                  "then":"reason"},
                 {"name":"missing field",
                  "when":{"field":"data.nope.deeper","op":"eq","value":"x"},
                  "then":"reason"},
                 {"name":"rest","then":"count"}]
                """);
        Verdict v = new IntakePipeline(cfg).evaluate("konzert.events", memoryEvent("checkout-api"), true);
        assertEquals("rest", v.rule(), "a broken rule must fail closed, not take the agent down");
    }
}
