package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Survivor pins for {@link ScalarSemantics#isInvalidDuration} and its post-match tail (lines
 * 1270-1314) — the ISO-8601 duration grammar behind {@code invalid_duration}. The decimal-placement
 * rule (a decimal only in the smallest populated component) was previously uncovered, so a mutant
 * could accept {@code P1.5Y2M} as valid and the operator would silently stop reporting malformed
 * exposure durations.
 */
class ScalarSemanticsDurationPinsTest
{

    private static boolean valid(String s)
    {
        return !ScalarSemantics.isInvalidDuration(s, false);
    }


    /**
     * One valid probe per populated component, so that whichever null-check of the
     * hours/minutes/seconds/T-designator condition (line 1270) a mutant negates, one of these flips
     * to invalid and kills it.
     */
    @Test
    void everySingleComponentDurationIsValid()
    {
        assertTrue(valid("P1Y"));
        assertTrue(valid("P2M"));
        assertTrue(valid("P3D"));
        assertTrue(valid("P1W"));
        assertTrue(valid("PT1H"));
        assertTrue(valid("PT1M"));
        assertTrue(valid("PT30S"));
        assertTrue(valid("P1Y2M3DT4H5M6S"));
    }


    /** The pre-gate rejects the degenerate designator-only shapes. */
    @Test
    void degenerateShapesAreInvalid()
    {
        assertFalse(valid(""));
        assertTrue(ScalarSemantics.isInvalidDuration(null, false));
        assertFalse(valid("P"));
        assertFalse(valid("PT"));
        assertFalse(valid("T1H"), "the P designator is mandatory");
        assertFalse(valid("P1H"), "an hour component without T fails the grammar");
        assertFalse(valid("PT5"), "digits after T without an H/M/S designator never match");
        assertFalse(valid("P1YT"), "T must be followed by a digit");
    }


    /**
     * The decimal-placement tail (lines 1307-1314): a decimal point or comma is legal ONLY in the
     * smallest (last) populated component, and only once. Kills the loop-condition negation (line
     * 1307, which would skip the check entirely) and the replaced-with-true mutant of the violation
     * return (line 1314).
     */
    @Test
    void aDecimalIsLegalOnlyInTheSmallestPopulatedComponent()
    {
        assertTrue(valid("P0.5D"));
        assertTrue(valid("P1Y2M3.5D"));
        assertTrue(valid("PT1H30.5S"));
        assertTrue(valid("P2M1,5D"), "the comma form is the ISO decimal mark too");
        assertFalse(valid("P1.5Y2M"), "a decimal in a non-smallest component is invalid");
        assertFalse(valid("P1,5Y2M"));
        assertFalse(valid("PT1.5H30S"));
        assertFalse(valid("P1.5Y2.5M"), "two decimals are invalid even if the last is smallest");
    }


    /** The negative-sign variant is accepted only when the operator allows it. */
    @Test
    void leadingMinusIsGatedByAllowNegative()
    {
        assertFalse(ScalarSemantics.isInvalidDuration("-P1Y", true));
        assertTrue(ScalarSemantics.isInvalidDuration("-P1Y", false));
    }
}
