package net.cumba.corej.ruletest.cdt.ruletest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.cumba.datatable.report.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plan C phase 4 — the {@code #runLevel} scenario directive: a scenario's <b>run severity
 * threshold</b> (§3.4, ruling 4).
 *
 * <p>
 * The {@code .cdt} face of the CLI's {@code --severity-level} and the REST
 * {@code CheckRunRequest.severityThreshold}; all three set the same value. A scenario that declares
 * none runs at the engine default ({@code Warning}), so every existing scenario in the suites is a
 * default-threshold baseline and this directive cannot move one silently.
 * </p>
 */
class RuleTestCdtRunLevelTest
{

    private static final String AE_DATASET = """
            dataset AE
            col AEDECOD type=Char
            ---
            Headache
            ---
            """;

    private static String scenario(String aDirectives)
    {
        return "#!RuleTest\n" + aDirectives + "\n" + AE_DATASET;
    }


    @Test
    @DisplayName("#runLevel selects the threshold, case-insensitively")
    void runLevelIsParsed()
    {
        assertEquals(Severity.INFO, RuleTestCdt.parse(scenario("""
                #test FDA-X expect=violation domain=AE
                #runLevel Info"""), "t").getRunLevel());
        assertEquals(Severity.REJECT, RuleTestCdt.parse(scenario("""
                #test FDA-X expect=violation domain=AE
                #runLevel reject"""), "t").getRunLevel());
    }


    @Test
    @DisplayName("absent #runLevel means the engine default — the directive never invents one")
    void absentRunLevelIsNull()
    {
        assertNull(
                RuleTestCdt.parse(scenario("#test FDA-X expect=violation domain=AE"), "t")
                        .getRunLevel(),
                "null is 'the run decides'; resolving it to Warning is the engine's job, not the "
                        + "parser's");
    }


    @Test
    @DisplayName("#runLevel round-trips through the writer")
    void runLevelRoundTrips()
    {
        RuleTestScenario s = RuleTestCdt.parse(scenario("""
                #test FDA-X expect=violation domain=AE
                #runLevel Info"""), "t");

        String written = RuleTestCdt.toString(s);
        assertTrue(written.contains("#runLevel Info"),
                "writer must re-emit the directive, got:\n" + written);
        assertEquals(Severity.INFO, RuleTestCdt.parse(written, "roundtrip").getRunLevel());
    }


    @Test
    @DisplayName("an unknown level, a missing value, a duplicate and NOTICE are all rejected")
    void malformedRunLevelIsRejected()
    {
        assertTrue(assertThrows(RuleTestCdtException.class, () -> RuleTestCdt.parse(scenario("""
                #test FDA-X expect=violation domain=AE
                #runLevel Severe"""), "t")).getMessage().contains("unknown level 'Severe'"));

        assertTrue(assertThrows(RuleTestCdtException.class, () -> RuleTestCdt.parse(scenario("""
                #test FDA-X expect=violation domain=AE
                #runLevel Notice"""), "t")).getMessage().contains("unknown level 'Notice'"),
                "NOTICE is a report-only kind and is not a rung of the ladder");

        assertTrue(assertThrows(RuleTestCdtException.class, () -> RuleTestCdt.parse(scenario("""
                #test FDA-X expect=violation domain=AE
                #runLevel"""), "t")).getMessage().contains("expects exactly one value"));

        assertTrue(assertThrows(RuleTestCdtException.class, () -> RuleTestCdt.parse(scenario("""
                #test FDA-X expect=violation domain=AE
                #runLevel Info
                #runLevel Error"""), "t")).getMessage().contains("duplicate #runLevel"));
    }


    @Test
    @DisplayName("the unknown-directive message lists #runLevel, so a typo names the real spelling")
    void unknownDirectiveMessageNamesRunLevel()
    {
        String message = assertThrows(RuleTestCdtException.class,
                () -> RuleTestCdt.parse(scenario("""
                        #test FDA-X expect=violation domain=AE
                        #runLevl Info"""), "t")).getMessage();

        assertTrue(message.contains("unknown directive: #runLevl"), message);
        assertTrue(message.contains("#runLevel"),
                () -> "the default arm must list the directive it gained: " + message);
    }


    @Test
    @DisplayName("#expectViolationAt severity= survives a write/read round trip")
    void expectViolationAtSeverityRoundTrips()
    {
        RuleTestScenario s = RuleTestCdt.parse(scenario("""
                #test FDA-X expect=violation domain=AE
                #expectViolationAt row=1 severity=Error"""), "t");

        assertEquals(Severity.ERROR, s.getExpectedViolations().getFirst().getSeverity());
        assertTrue(s.getExpectedViolations().getFirst().getConstraints().isEmpty(),
                "⛔ severity is RESERVED — it must never become a constraint on a column named "
                        + "'severity'");

        String written = RuleTestCdt.toString(s);
        assertTrue(written.contains("severity=Error"),
                "the writer must re-emit the reserved pin, got:\n" + written);
        assertEquals(Severity.ERROR, RuleTestCdt.parse(written, "roundtrip").getExpectedViolations()
                .getFirst().getSeverity());
    }

}
