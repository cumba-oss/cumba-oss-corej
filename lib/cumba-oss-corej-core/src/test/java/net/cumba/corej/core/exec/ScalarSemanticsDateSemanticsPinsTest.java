package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Survivor pins for {@link ScalarSemantics}' date semantics: the {@code 1e-9}-epsilon numeric date
 * comparator, the complete/masked-date validators and the ISO normalization helpers. A boundary
 * defect here silently mis-orders visit dates or mis-classifies partial dates — a clean-looking
 * report with wrong contents — so the epsilon tests sit EXACTLY on the tolerance boundary.
 */
class ScalarSemanticsDateSemanticsPinsTest
{

    private static final double EPS = ScalarSemantics.DATE_EPSILON;

    // -------------------------------------------------------------------------
    // matchNumeric — the numeric (SAS) date comparator
    // -------------------------------------------------------------------------

    /**
     * Equality direction: two dates are equal iff they differ by strictly less than the epsilon. A
     * difference of EXACTLY 1e-9 is NOT equal — this input sits on the boundary the
     * {@code <}→{@code <=} mutant (line 678) moves.
     */
    @Test
    void matchNumericEqualityIsStrictlyInsideTheEpsilon()
    {
        assertTrue(ScalarSemantics.matchNumeric(5.0, 5.0, 0, false));
        assertFalse(ScalarSemantics.matchNumeric(5.0, 6.0, 0, false));
        assertFalse(ScalarSemantics.matchNumeric(EPS, 0.0, 0, false),
                "a difference of exactly the epsilon is NOT equal");
    }


    /**
     * Greater-than family (line 682), pinned at the exact epsilon boundary in all four shapes:
     * or-equal accepts a shortfall of exactly the epsilon; strict rejects an excess of exactly the
     * epsilon. Equal operands discriminate the {@code +}/{@code -} MathMutator swaps.
     */
    @Test
    void matchNumericGreaterThanBoundary()
    {
        // orEqual: lhs + EPS >= rhs.
        assertTrue(ScalarSemantics.matchNumeric(5.0, 5.0, 1, true),
                "equal values satisfy >= (kills lhs+EPS -> lhs-EPS)");
        assertTrue(ScalarSemantics.matchNumeric(0.0, EPS, 1, true),
                "a shortfall of exactly the epsilon still satisfies >= (boundary)");
        assertFalse(ScalarSemantics.matchNumeric(0.0, 3 * EPS, 1, true));
        assertTrue(ScalarSemantics.matchNumeric(6.0, 5.0, 1, true));
        // strict: lhs - EPS > rhs.
        assertFalse(ScalarSemantics.matchNumeric(5.0, 5.0, 1, false),
                "equal values are NOT strictly greater (kills lhs-EPS -> lhs+EPS and return-true)");
        assertFalse(ScalarSemantics.matchNumeric(EPS, 0.0, 1, false),
                "an excess of exactly the epsilon is NOT strictly greater (boundary)");
        assertTrue(ScalarSemantics.matchNumeric(6.0, 5.0, 1, false));
        assertFalse(ScalarSemantics.matchNumeric(4.0, 5.0, 1, false));
    }


    /** Less-than family (line 684), the mirror of the greater-than pins. */
    @Test
    void matchNumericLessThanBoundary()
    {
        // orEqual: lhs <= rhs + EPS.
        assertTrue(ScalarSemantics.matchNumeric(5.0, 5.0, -1, true),
                "equal values satisfy <= (kills rhs+EPS -> rhs-EPS)");
        assertTrue(ScalarSemantics.matchNumeric(EPS, 0.0, -1, true),
                "an excess of exactly the epsilon still satisfies <= (boundary)");
        assertFalse(ScalarSemantics.matchNumeric(3 * EPS, 0.0, -1, true));
        assertFalse(ScalarSemantics.matchNumeric(6.0, 5.0, -1, true));
        // strict: lhs + EPS < rhs.
        assertFalse(ScalarSemantics.matchNumeric(5.0, 5.0, -1, false),
                "equal values are NOT strictly less (kills lhs+EPS -> lhs-EPS)");
        assertFalse(ScalarSemantics.matchNumeric(0.0, EPS, -1, false),
                "a shortfall of exactly the epsilon is NOT strictly less (boundary)");
        assertTrue(ScalarSemantics.matchNumeric(4.0, 5.0, -1, false));
    }

    // -------------------------------------------------------------------------
    // isCompleteDate — the structural complete-date gate (all branches)
    // -------------------------------------------------------------------------


    /** The three complete precisions, with and without the decorations Python tolerates. */
    @Test
    void isCompleteDateAcceptsTheThreeCompletePrecisions()
    {
        assertTrue(ScalarSemantics.isCompleteDate("2024-01-01"));
        assertTrue(ScalarSemantics.isCompleteDate("2024-01-01T12:30"));
        assertTrue(ScalarSemantics.isCompleteDate("2024-01-01T12:30:45"));
        // Timezone / fractional-second decorations are stripped first.
        assertTrue(ScalarSemantics.isCompleteDate("2024-01-01T12:30:45Z"));
        assertTrue(ScalarSemantics.isCompleteDate("2024-01-01T12:30:45.123"));
        assertTrue(ScalarSemantics.isCompleteDate("2024-01-01T12:30+02:00"));
    }


    /**
     * One rejection per structural position — each input is wrong in EXACTLY one character, so each
     * kills the replaced-with-true mutant of that branch (lines 730-776). A validator that answers
     * true on these silently accepts corrupt --DTC values as complete dates.
     */
    @Test
    void isCompleteDateRejectsEachCorruptPositionIndividually()
    {
        assertFalse(ScalarSemantics.isCompleteDate(null));
        assertFalse(ScalarSemantics.isCompleteDate(""));
        assertFalse(ScalarSemantics.isCompleteDate("2024"), "year-only is partial, not complete");
        assertFalse(ScalarSemantics.isCompleteDate("2024-01"));
        assertFalse(ScalarSemantics.isCompleteDate("2024-01-011"), "length 11 is no tier");
        assertFalse(ScalarSemantics.isCompleteDate("202X-01-01"), "non-digit year");
        assertFalse(ScalarSemantics.isCompleteDate("2024/01/01"), "wrong separator after year");
        assertFalse(ScalarSemantics.isCompleteDate("2024-X1-01"), "non-digit month");
        assertFalse(ScalarSemantics.isCompleteDate("2024-01X01"), "wrong separator after month");
        assertFalse(ScalarSemantics.isCompleteDate("2024-01-X1"), "non-digit day");
        assertFalse(ScalarSemantics.isCompleteDate("2024-01-01X12:30"), "missing 'T'");
        assertFalse(ScalarSemantics.isCompleteDate("2024-01-01TX2:30"), "non-digit hour");
        assertFalse(ScalarSemantics.isCompleteDate("2024-01-01T12X30"), "missing first ':'");
        assertFalse(ScalarSemantics.isCompleteDate("2024-01-01T12:X0"), "non-digit minute");
        assertFalse(ScalarSemantics.isCompleteDate("2024-01-01T12:30X45"), "missing second ':'");
        assertFalse(ScalarSemantics.isCompleteDate("2024-01-01T12:30:X5"), "non-digit second");
    }

    // -------------------------------------------------------------------------
    // Masked dates — the year-masked shape reads its digits at lead..lead+2
    // -------------------------------------------------------------------------


    /**
     * Pins isYearMasked's positional reads (line 1120/1121): the month digits sit at
     * {@code [lead, lead+2)} and the separator at {@code lead+2}. The MathMutator's
     * {@code lead+2}→{@code lead-2} swap re-points the separator check into the leading hyphen run
     * — which is ALWAYS a hyphen — so a value whose real separator position is corrupt ("--06X15")
     * would be accepted as masked. That is the mutant the negative cases kill; the positives pin
     * both legal year-masked widths.
     */
    @Test
    void yearMaskedShapeChecksTheSeparatorAtItsRealPosition()
    {
        assertTrue(ScalarSemantics.isMaskedDate("--06-15"));
        assertTrue(ScalarSemantics.isMaskedDate("----06-15"));
        assertFalse(ScalarSemantics.isMaskedDate("--06X15"),
                "the char between month and day must be '-', not any filler");
        assertFalse(ScalarSemantics.isMaskedDate("----06X15"));
        assertFalse(ScalarSemantics.isMaskedDate("--0615"));
        assertFalse(ScalarSemantics.isMaskedDate("--0X-15"), "month digits must be digits");
        assertFalse(ScalarSemantics.isMaskedDate("--06-1X"), "day digits must be digits");
    }

    // -------------------------------------------------------------------------
    // ISO normalization helpers
    // -------------------------------------------------------------------------


    /** stripTimezone (line 476): null passes through as null — never as "". */
    @Test
    void stripTimezoneNullAndEmptyPassThrough()
    {
        assertNull(ScalarSemantics.stripTimezone(null));
        assertEquals("", ScalarSemantics.stripTimezone(""));
        assertEquals("2024-01-01T10:00", ScalarSemantics.stripTimezone("2024-01-01T10:00Z"));
        assertEquals("2024-01-01T10:00", ScalarSemantics.stripTimezone("2024-01-01T10:00+05:30"));
        assertEquals("2024-01-01", ScalarSemantics.stripTimezone("2024-01-01"));
    }


    /**
     * stripFractionalSeconds (lines 492/495): null passes through; a dot at index 0 IS a fractional
     * tail when only digits follow (the {@code dot < 0}→{@code dot <= 0} boundary), and a dot
     * followed by non-digits is left intact.
     */
    @Test
    void stripFractionalSecondsBoundaries()
    {
        assertNull(ScalarSemantics.stripFractionalSeconds(null));
        assertEquals("", ScalarSemantics.stripFractionalSeconds(".123"),
                "a leading '.' followed by digits is a fractional tail (dot index 0 boundary)");
        assertEquals("2024-01-01T10:00:00",
                ScalarSemantics.stripFractionalSeconds("2024-01-01T10:00:00.123"));
        assertEquals("1.2.3", ScalarSemantics.stripFractionalSeconds("1.2.3"),
                "a '.' not followed exclusively by digits is not a fractional tail");
    }


    /** detectIsoPrecision (line 641): a sub-year-length string reports its own length, not 0. */
    @Test
    void detectIsoPrecisionSubYearLengthsReportTheirLength()
    {
        assertEquals(3, ScalarSemantics.detectIsoPrecision("202"));
        assertEquals(0, ScalarSemantics.detectIsoPrecision(""));
        assertEquals(4, ScalarSemantics.detectIsoPrecision("2024"));
        assertEquals(10, ScalarSemantics.detectIsoPrecision("2024-01-01"));
        assertEquals(19, ScalarSemantics.detectIsoPrecision("2024-01-01T10:00:00"));
    }

    // -------------------------------------------------------------------------
    // isPartialDate interval handling (documents the line-820 equivalence basis)
    // -------------------------------------------------------------------------


    /**
     * An interval is partial iff BOTH halves are; a leading-slash value is invalid either way — the
     * {@code slash >= 0}→{@code slash > 0} mutant is equivalent because the empty first half and
     * the un-split "/..." string are both rejected. These pins document that basis.
     */
    @Test
    void isPartialDateIntervalNeedsBothHalvesValid()
    {
        assertTrue(ScalarSemantics.isPartialDate("2024-01/2024-02"));
        assertFalse(ScalarSemantics.isPartialDate("2024-01/garbage"));
        assertFalse(ScalarSemantics.isPartialDate("/2024"));
        assertFalse(ScalarSemantics.isPartialDate("2024/"));
    }

    // -------------------------------------------------------------------------
    // differsFromStringPart — does_not_equal_string_part verdict
    // -------------------------------------------------------------------------


    /**
     * Pins the no-match / no-group gate (line 1188): a target the pattern does not fully match is
     * NOT a violation — replacing that return with true would fire the rule on every non-matching
     * target row.
     */
    @Test
    void differsFromStringPartNoMatchIsNoViolation()
    {
        Pattern group = Pattern.compile("AB(\\d+)");
        assertFalse(ScalarSemantics.differsFromStringPart("12", "XY99", group),
                "a target that does not match the pattern is not a violation");
        assertFalse(ScalarSemantics.differsFromStringPart("12", "AB12XX", group),
                "matches() is anchored — a partial match is no match");
        Pattern noGroup = Pattern.compile("AB\\d+");
        assertFalse(ScalarSemantics.differsFromStringPart("12", "AB12", noGroup),
                "a pattern without a capture group is not a violation");
        // Positive twins: extracted part "12" — equal value passes, different value fires.
        assertFalse(ScalarSemantics.differsFromStringPart("12", "AB12", group));
        assertTrue(ScalarSemantics.differsFromStringPart("13", "AB12", group));
        // An optional group that did not participate folds to "".
        Pattern optional = Pattern.compile("AB(\\d+)?");
        assertFalse(ScalarSemantics.differsFromStringPart("", "AB", optional));
        assertTrue(ScalarSemantics.differsFromStringPart("x", "AB", optional));
    }
}
