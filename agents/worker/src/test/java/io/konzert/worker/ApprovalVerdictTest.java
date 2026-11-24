package io.konzert.worker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The operator's verdict on a proposed plan is matched without a model: there
 * must be nothing probabilistic between a "yes" and a playbook run. These are
 * the words that decide it.
 */
class ApprovalVerdictTest {

    @Test
    void approvalsInEnglishAndPortuguese() {
        for (String s : new String[] {"approved", "Approved", "approve", "yes", "ok", "go", "sim", "aprovado", "pode"}) {
            assertEquals("approve", WorkerAgent.approvalVerdict(s), s);
        }
    }

    @Test
    void denialsInEnglishAndPortuguese() {
        for (String s : new String[] {"denied", "deny", "no", "No.", "cancel", "abort", "não", "nao", "negado"}) {
            assertEquals("deny", WorkerAgent.approvalVerdict(s), s);
        }
    }

    @Test
    void leadingSymbolsAndWhitespaceAreIgnored() {
        assertEquals("approve", WorkerAgent.approvalVerdict("  ✅ approved"));
        assertEquals("deny", WorkerAgent.approvalVerdict("⛔ denied"));
    }

    @Test
    void anythingElseIsAQuestionNotAVerdict() {
        // "now" starts with "no" but is not a denial; "okay then why" is still an approval.
        assertEquals("question", WorkerAgent.approvalVerdict("now what happens?"));
        assertEquals("question", WorkerAgent.approvalVerdict("why 1Gi and not 2Gi?"));
        assertEquals("question", WorkerAgent.approvalVerdict(""));
        assertEquals("question", WorkerAgent.approvalVerdict(null));
    }
}
