package net.cumba.corej.ruletest.cdt.ruletest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-finding-values-record-level-only} P1 — {@code nameOnly=} is a RESERVED, repeatable
 * key of {@code #expectViolationAt}: it collects into {@link ExpectedViolation#getNameOnly()} and
 * never reaches {@code constraints} (the same quiet-bug shape {@code RuleTestCdtSeverityKeyTest}
 * pins for {@code severity=}), and the writer round-trips it.
 */
class RuleTestCdtNameOnlyKeyTest
{

    private static final String AE_DATASET = """
            dataset AE
            col DOMAIN type=Char
            col AETERM type=Char
            ---
            AE | Headache
            ---
            """;

    private static String scenario(String aDirectives)
    {
        return "#!RuleTest\n#test CORE-1 expect=violation domain=AE\n" + aDirectives + "\n"
                + AE_DATASET;
    }


    @Test
    @DisplayName("nameOnly= is reserved and repeatable; constraints never see it")
    void nameOnlyIsReservedAndRepeatable()
    {
        RuleTestScenario s = RuleTestCdt.parse(scenario(
                "#expectViolationAt variable_name=AETERM nameOnly=DOMAIN" + " nameOnly=AETERM"),
                "t", null);

        assertEquals(1, s.getExpectedViolations().size());
        ExpectedViolation at = s.getExpectedViolations().get(0);
        assertEquals(List.of("DOMAIN", "AETERM"), at.getNameOnly(), "file order, both tokens");
        assertEquals(Map.of("variable_name", "AETERM"), at.getConstraints(),
                "⛔ nameOnly= must NOT land in constraints — that would constrain a column named"
                        + " 'nameOnly'");
    }


    @Test
    @DisplayName("a bare nameOnly= pin is a complete directive")
    void aBareNameOnlyPinIsLegal()
    {
        RuleTestScenario s = RuleTestCdt.parse(scenario("#expectViolationAt nameOnly=DOMAIN"), "t",
                null);
        ExpectedViolation at = s.getExpectedViolations().get(0);
        assertEquals(List.of("DOMAIN"), at.getNameOnly());
        assertTrue(at.getConstraints().isEmpty());
    }


    @Test
    @DisplayName("a column pinned both by name and by value is a parse error")
    void aColumnPinnedTwiceIsRejected()
    {
        assertThrows(RuleTestCdtException.class, () -> RuleTestCdt
                .parse(scenario("#expectViolationAt nameOnly=DOMAIN DOMAIN=AE"), "t", null));
        assertThrows(RuleTestCdtException.class, () -> RuleTestCdt
                .parse(scenario("#expectViolationAt DOMAIN=AE nameOnly=DOMAIN"), "t", null));
        assertThrows(RuleTestCdtException.class, () -> RuleTestCdt
                .parse(scenario("#expectViolationAt nameOnly=DOMAIN nameOnly=DOMAIN"), "t", null));
        assertThrows(RuleTestCdtException.class,
                () -> RuleTestCdt.parse(scenario("#expectViolationAt nameOnly="), "t", null));
    }


    @Test
    @DisplayName("the writer emits nameOnly= and the result re-parses to the same pin")
    void writerRoundTripsNameOnly()
    {
        RuleTestScenario s = RuleTestCdt.parse(scenario(
                "#expectViolationAt row=1 severity=ERROR nameOnly=DOMAIN variable_name=AETERM"),
                "t", null);

        String written = RuleTestCdt.toString(s);

        assertTrue(written.contains(
                "#expectViolationAt row=1 severity=Error nameOnly=DOMAIN variable_name=AETERM"),
                written);
        RuleTestScenario reparsed = RuleTestCdt.parse(written, "t2", null);
        assertEquals(s.getExpectedViolations(), reparsed.getExpectedViolations());
    }
}
