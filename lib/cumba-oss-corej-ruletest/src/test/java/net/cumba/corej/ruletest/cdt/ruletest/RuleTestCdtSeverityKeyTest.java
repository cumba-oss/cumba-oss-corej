package net.cumba.corej.ruletest.cdt.ruletest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.cumba.datatable.report.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⛔ Plan C phase 2's <b>named deliverable</b> — {@code severity=} is a RESERVED key in
 * {@code parseExpectAt}, and this test pins that it never reaches {@code constraints}.
 *
 * <p>
 * ⚠⚠ The {@code constraints.isEmpty()} assertion is the point of the whole class. A test that only
 * checked the parsed level would pass while the key <em>also</em> sat in {@code constraints},
 * silently constraining a column named {@code severity} — i.e. the quiet bug would survive its own
 * test. That is the single most likely way for this work to ship a defect, so it is asserted
 * explicitly rather than implied.
 * </p>
 */
class RuleTestCdtSeverityKeyTest
{

    private static final String VS_DATASET = """
            dataset VS
            col VSPOS type=Char
            ---
            SUPINE
            ---
            """;

    private static String scenario(String aDirectives)
    {
        return "#!RuleTest\n" + aDirectives + "\n" + VS_DATASET;
    }


    @Test
    @DisplayName("row= and severity= are BOTH reserved; constraints stays EMPTY")
    void severityIsReservedAndNeverBecomesAColumnConstraint()
    {
        RuleTestScenario s = RuleTestCdt.parse(scenario("""
                #test CORE-1 expect=violation domain=VS
                #expectViolationAt row=3 severity=ERROR"""), "t");

        assertEquals(1, s.getExpectedViolations().size());
        ExpectedViolation at = s.getExpectedViolations().get(0);
        assertEquals(3, at.getRow(), "row= still parses");
        assertEquals(Severity.ERROR, at.getSeverity(), "severity= parses to the level");
        assertTrue(at.getConstraints().isEmpty(),
                "⛔ severity= must NOT land in constraints — that would silently constrain a column"
                        + " named 'severity'");
    }


    @Test
    @DisplayName("a BARE severity= is legal — reserving the key must not break a working spelling")
    void bareSeverityIsAValidPinOnItsOwn()
    {
        // The guard used to read `row == null && constraints.isEmpty()` -> throw. Reserving the key
        // without relaxing that guard would turn this spelling into a parse error.
        RuleTestScenario s = RuleTestCdt.parse(scenario("""
                #test CORE-1 expect=violation domain=VS
                #expectViolationAt severity=WARNING"""), "t");

        ExpectedViolation at = s.getExpectedViolations().get(0);
        assertNull(at.getRow());
        assertEquals(Severity.WARNING, at.getSeverity());
        assertTrue(at.getConstraints().isEmpty());
    }


    @Test
    @DisplayName("a real column constraint still works, and coexists with severity=")
    void ordinaryColumnConstraintsAreUnaffected()
    {
        RuleTestScenario s = RuleTestCdt.parse(scenario("""
                #test CORE-1 expect=violation domain=VS
                #expectViolationAt severity=INFO VSPOS=SUPINE"""), "t");

        ExpectedViolation at = s.getExpectedViolations().get(0);
        assertEquals(Severity.INFO, at.getSeverity());
        assertEquals(java.util.Map.of("VSPOS", "SUPINE"), at.getConstraints(),
                "a genuine COL=value token is still a constraint");
    }


    @Test
    @DisplayName("an unknown or non-authorable level is a parse error, not a column constraint")
    void unknownSeverityIsRejected()
    {
        assertThrows(RuleTestCdtException.class, () -> RuleTestCdt.parse(scenario("""
                #test CORE-1 expect=violation domain=VS
                #expectViolationAt row=1 severity=SEVERE"""), "t"));
        // NOTICE parses as a report enum constant but no rule may author it and no scenario may
        // pin it — the generic "does it parse" test would have let it through.
        assertThrows(RuleTestCdtException.class, () -> RuleTestCdt.parse(scenario("""
                #test CORE-1 expect=violation domain=VS
                #expectViolationAt row=1 severity=NOTICE"""), "t"));
    }


    @Test
    @DisplayName("a duplicate severity= is rejected, as a duplicate row= is")
    void duplicateSeverityIsRejected()
    {
        assertThrows(RuleTestCdtException.class, () -> RuleTestCdt.parse(scenario("""
                #test CORE-1 expect=violation domain=VS
                #expectViolationAt severity=ERROR severity=INFO"""), "t"));
    }

}
