package net.cumba.corej.ruletest.cdt.ruletest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleExecutionStatus;
import net.cumba.corej.core.exec.Violation;
import net.cumba.corej.ruletest.cdt.ruletest.ExecutionVerdictCheck.Outcome;
import net.cumba.corej.ruletest.cdt.ruletest.ExecutionVerdictCheck.Result;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestScenario.Verdict;
import org.junit.jupiter.api.Test;

/**
 * The {@code expect=executionError} verdict (ruling R7) — parsing, round-trip, and the execution
 * state judgement, including the <b>negative controls</b>: every new check is exercised once where
 * it must fail, because a verdict that cannot fail is not a check.
 */
class ExecutionVerdictCheckTest
{

    private static final String MISMATCH = "AGE: numeric comparison against a Char column; use num(AGE)";

    // ---- fixtures -----------------------------------------------------------------

    private static String cdt(String aVerdict, String aExtraDirective)
    {
        return """
                #!RuleTest
                #test CDISC-CG0040 expect=%s domain=AE
                %s
                dataset AE
                col STUDYID type=Char
                col AGE type=Char
                ---
                CDISC01 | 25
                ---
                """.formatted(aVerdict, aExtraDirective);
    }


    private static RuleTestScenario scenario(String aVerdict, String aExtraDirective)
    {
        return RuleTestCdt.parse(cdt(aVerdict, aExtraDirective), "test.cdt");
    }


    /** The engine's shape for an errored rule: ERROR status plus the {@code __error__} sentinel. */
    private static Outcome errored(String aMessage)
    {
        return ExecutionVerdictCheck.outcomeOf(RuleExecutionResult.builder().ruleId("CDISC-CG0040")
                .violations(List.of(new Violation(0, Map.of("__error__", aMessage)))).totalRows(1)
                .status(RuleExecutionStatus.ERROR).statusMessage(aMessage).build());
    }


    private static Outcome executed(int aViolations)
    {
        return new Outcome(true, false, aViolations > 0, aViolations, null);
    }


    private static Outcome skipped(String aReason)
    {
        return new Outcome(false, false, false, 0, aReason);
    }

    // ---- parsing ------------------------------------------------------------------


    @Test
    void parse_executionErrorVerdict()
    {
        RuleTestScenario s = scenario("executionError", "#note \"AGE is Char\"");
        assertEquals(Verdict.EXECUTION_ERROR, s.getExpect());
        assertNull(s.getExpectErrorMessage());
    }


    @Test
    void parse_executionErrorVerdict_snakeCaseAndMixedCaseAccepted()
    {
        assertEquals(Verdict.EXECUTION_ERROR, Verdict.parse("execution_error"));
        assertEquals(Verdict.EXECUTION_ERROR, Verdict.parse("EXECUTIONERROR"));
        assertEquals(Verdict.EXECUTION_ERROR, Verdict.parse("executionError"));
    }


    /**
     * ⛔ The collision the longer token exists to prevent: {@code severity=ERROR} is a check
     * severity LEVEL, so a scenario spelled {@code expect=error} must be refused outright rather
     * than quietly meaning something else.
     */
    @Test
    void parse_bareErrorTokenIsRejected()
    {
        assertNull(Verdict.parse("error"));
        RuleTestCdtException e = assertThrows(RuleTestCdtException.class,
                () -> scenario("error", "#note x"));
        assertTrue(e.getMessage().contains("executionError"),
                "the failure must name the token the author should have written: "
                        + e.getMessage());
    }


    @Test
    void parse_expectExecutionErrorMessage()
    {
        RuleTestScenario s = scenario("executionError",
                "#expect-execution-error \"" + MISMATCH + "\"");
        assertEquals(Verdict.EXECUTION_ERROR, s.getExpect());
        assertEquals(MISMATCH, s.getExpectErrorMessage());
    }


    @Test
    void parse_expectExecutionErrorMessage_rejectedOnOtherVerdicts()
    {
        RuleTestCdtException e = assertThrows(RuleTestCdtException.class,
                () -> scenario("noViolation", "#expect-execution-error \"" + MISMATCH + "\""));
        assertTrue(e.getMessage().contains("requires expect=executionError"), e.getMessage());
    }


    @Test
    void parse_duplicateExpectExecutionErrorRejected()
    {
        assertThrows(RuleTestCdtException.class, () -> scenario("executionError",
                "#expect-execution-error a\n" + "#expect-execution-error b"));
    }


    @Test
    void write_roundTripsVerdictAndMessage()
    {
        RuleTestScenario s = scenario("executionError",
                "#expect-execution-error \"" + MISMATCH + "\"");
        String written = RuleTestCdt.toString(s);
        assertTrue(written.contains("expect=executionError"), written);
        RuleTestScenario again = RuleTestCdt.parse(written, "round-trip.cdt");
        assertEquals(Verdict.EXECUTION_ERROR, again.getExpect());
        assertEquals(MISMATCH, again.getExpectErrorMessage());
    }

    // ---- the new verdict passes ---------------------------------------------------


    @Test
    void executionError_passes_whenTheRuleErrored()
    {
        Result r = ExecutionVerdictCheck.verify(scenario("executionError", "#note x"),
                errored(MISMATCH));
        assertTrue(r.pass(), r.detail());
        assertEquals("", r.detail());
    }


    @Test
    void executionError_passes_whenTheMessageSubstringMatches()
    {
        Result r = ExecutionVerdictCheck.verify(
                scenario("executionError", "#expect-execution-error \"use num(AGE)\""),
                errored(MISMATCH));
        assertTrue(r.pass(), r.detail());
    }

    // ---- NEGATIVE CONTROLS: the new verdict must be able to fail -------------------


    @Test
    void executionError_fails_whenTheRuleRanCleanly()
    {
        Result r = ExecutionVerdictCheck.verify(scenario("executionError", "#note x"), executed(0));
        assertFalse(r.pass(), "a rule that ran normally must NOT satisfy expect=executionError");
        assertTrue(r.detail().contains("did not error"), r.detail());
        assertTrue(r.detail().contains("executed normally"), r.detail());
    }


    @Test
    void executionError_fails_whenTheRuleFired()
    {
        Result r = ExecutionVerdictCheck.verify(scenario("executionError", "#note x"), executed(3));
        assertFalse(r.pass());
        assertTrue(r.detail().contains("3 violation(s)"), r.detail());
    }


    /** A skip is not a failure: it must not satisfy the verdict, and must say which to write. */
    @Test
    void executionError_fails_whenTheRuleMerelySkipped()
    {
        Result r = ExecutionVerdictCheck.verify(scenario("executionError", "#note x"),
                skipped("Requirements.Variables.All variable AGE not present in dataset"));
        assertFalse(r.pass());
        assertTrue(r.detail().contains("never ran at all"), r.detail());
        assertTrue(r.detail().contains("AGE not present"), r.detail());
        assertTrue(r.detail().contains("expect=skipped"), r.detail());
    }


    @Test
    void executionError_fails_whenTheMessageSubstringDoesNotMatch()
    {
        Result r = ExecutionVerdictCheck.verify(
                scenario("executionError", "#expect-execution-error \"use num(AGE)\""),
                errored("Match_Datasets: RDOMAIN resolved to a split domain that cannot be "
                        + "unioned"));
        assertFalse(r.pass(), "an unrelated failure must not satisfy a scenario pinning a "
                + "type-mismatch error");
        assertTrue(r.detail().contains("use num(AGE)"), r.detail());
        assertTrue(r.detail().contains("Match_Datasets"), r.detail());
    }


    @Test
    void executionError_fails_whenTheEngineGaveNoMessageAtAll()
    {
        Result r = ExecutionVerdictCheck.verify(
                scenario("executionError", "#expect-execution-error \"use num(AGE)\""),
                new Outcome(false, true, true, 1, null));
        assertFalse(r.pass());
        assertTrue(r.detail().contains("<none>"), r.detail());
    }

    // ---- violation / noViolation must fail LOUDLY on an error ---------------------


    @Test
    void violation_failsNamingTheErrorMessage()
    {
        Result r = ExecutionVerdictCheck.verify(scenario("violation", "#note x"),
                errored(MISMATCH));
        assertFalse(r.pass(), "the __error__ sentinel must not read as a fired violation");
        assertTrue(r.detail().contains("ERRORED"), r.detail());
        assertTrue(r.detail().contains(MISMATCH), r.detail());
        assertTrue(r.detail().contains("expect=executionError"), r.detail());
    }


    @Test
    void noViolation_failsNamingTheErrorMessage()
    {
        Result r = ExecutionVerdictCheck.verify(scenario("noViolation", "#note x"),
                errored(MISMATCH));
        assertFalse(r.pass(), "an errored rule reports zero violations — expect=noViolation must "
                + "not pass vacuously on it");
        assertTrue(r.detail().contains("ERRORED"), r.detail());
        assertTrue(r.detail().contains(MISMATCH), r.detail());
    }


    /** The error branch must not reuse the skip advice — 'expect=skipped' would fail too. */
    @Test
    void noViolation_errorFailure_doesNotAdviseSkipped()
    {
        Result r = ExecutionVerdictCheck.verify(scenario("noViolation", "#note x"),
                errored(MISMATCH));
        assertFalse(r.detail().contains("expect=skipped"), r.detail());
    }

    // ---- the pre-existing verdicts still behave ----------------------------------


    @Test
    void violation_passesAndFailsOnTheViolationItself()
    {
        assertTrue(
                ExecutionVerdictCheck.verify(scenario("violation", "#note x"), executed(2)).pass());
        Result none = ExecutionVerdictCheck.verify(scenario("violation", "#note x"), executed(0));
        assertFalse(none.pass());
        assertTrue(none.detail().contains("expected a violation but got none"), none.detail());
    }


    @Test
    void noViolation_passesAndFailsOnTheViolationItself()
    {
        assertTrue(ExecutionVerdictCheck.verify(scenario("noViolation", "#note x"), executed(0))
                .pass());
        Result fired = ExecutionVerdictCheck.verify(scenario("noViolation", "#note x"),
                executed(4));
        assertFalse(fired.pass());
        assertTrue(fired.detail().contains("4 violation(s)"), fired.detail());
    }


    @Test
    void noViolation_failsWhenTheRuleNeverRan()
    {
        Result r = ExecutionVerdictCheck.verify(scenario("noViolation", "#note x"),
                skipped("no Library access"));
        assertFalse(r.pass());
        assertTrue(r.detail().contains("never executed"), r.detail());
        assertTrue(r.detail().contains("no Library access"), r.detail());
    }


    @Test
    void skipped_passesOnASkipAndFailsOnAnError()
    {
        assertTrue(ExecutionVerdictCheck
                .verify(scenario("skipped", "#note x"), skipped("variable absent")).pass());
        Result r = ExecutionVerdictCheck.verify(scenario("skipped", "#note x"), errored(MISMATCH));
        assertFalse(r.pass(), "a rule broken into unconditional ERROR must not satisfy a skip");
        assertTrue(r.detail().contains("expect=executionError"), r.detail());
    }


    @Test
    void skipped_failsWhenTheRuleExecuted()
    {
        Result r = ExecutionVerdictCheck.verify(scenario("skipped", "#note x"), executed(0));
        assertFalse(r.pass());
        assertTrue(r.detail().contains("but the rule executed"), r.detail());
        assertTrue(r.detail().contains("(and produced no violation)"), r.detail());

        // The other half of the same message: a skip contract broken by a rule that FIRED must
        // say how often, or the author cannot tell "now in scope" from "now failing".
        Result fired = ExecutionVerdictCheck.verify(scenario("skipped", "#note x"), executed(2));
        assertFalse(fired.pass());
        assertTrue(fired.detail().contains("and produced 2 violation(s)"), fired.detail());
    }

    // ---- outcomeOf ----------------------------------------------------------------


    @Test
    void outcomeOf_readsTheErrorSentinelAsErroredNotExecuted()
    {
        Outcome o = errored(MISMATCH);
        assertFalse(o.executed(), "ERROR is not execution");
        assertTrue(o.errored());
        assertTrue(o.violated(), "the __error__ sentinel IS counted as a violation by the engine");
        assertEquals(MISMATCH, o.statusMessage());
    }


    @Test
    void outcomeOf_readsACleanRun()
    {
        Outcome o = ExecutionVerdictCheck.outcomeOf(RuleExecutionResult.builder()
                .ruleId("CDISC-CG0040").violations(List.of()).totalRows(1).build());
        assertTrue(o.executed());
        assertFalse(o.errored());
        assertFalse(o.violated());
    }
}
