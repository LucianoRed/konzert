package io.konzert.core.intake;

/**
 * What the intake pipeline decided about one event, and — as importantly — why.
 *
 * The reason travels with the verdict so the panel can show "dropped by rule
 * 'not mine'" instead of leaving an operator to guess why their agent is quiet.
 */
public record Verdict(String lane, String stage, String rule, String reason) {

    public static final String REASON = "reason";
    public static final String TRIAGE = "triage";
    public static final String DETERMINISTIC = "deterministic";
    public static final String COUNT = "count";
    public static final String DROP = "drop";

    public boolean reachesModel() {
        return REASON.equals(lane) || TRIAGE.equals(lane);
    }

    public static Verdict drop(String stage, String reason) {
        return new Verdict(DROP, stage, null, reason);
    }

    public static Verdict of(String lane, String stage, String rule, String reason) {
        return new Verdict(lane, stage, rule, reason);
    }
}
