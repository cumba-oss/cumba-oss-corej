package net.cumba.cdisc.core.expr.eval;

import static net.cumba.cdisc.core.expr.eval.VectorLayerTest.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import net.cumba.cdisc.core.exec.ScalarSemantics;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Survivor pins for {@link Primitives} — the vectorized comparison/primitive layer. Each test
 * asserts the exact violation BitSet (or scalar verdict) with a negative twin per branch; the
 * date-part tests sit exactly on the {@code 1e-9} epsilon and the {@code 'T'}-split boundaries,
 * where an off-by-one silently mis-classifies partial dates and times.
 */
class PrimitivesSurvivorPinsTest
{

    private static BitSet bits(int... rows)
    {
        BitSet bs = new BitSet();
        for (int r : rows)
        {
            bs.set(r);
        }
        return bs;
    }

    // -------------------------------------------------------------------------
    // datePartComparison — numeric branch (datePartCode, lines 300-350)
    // -------------------------------------------------------------------------


    /**
     * Missing-LHS and null-RHS short-circuits (lambda lines 300/305) answer {@code negate} — a
     * blank cell makes {@code date_part_not_equal_to} fire and {@code date_part_equal_to} stay
     * silent, in BOTH polarities.
     */
    @Test
    void datePartMissingOperandsAnswerNegate()
    {
        ConstVector missing = ConstVector.of(null);
        ConstVector date = ConstVector.of("2024-01-01");
        assertEquals(bits(0), Primitives.datePartComparison(missing, date, 1, false, true));
        assertEquals(bits(), Primitives.datePartComparison(missing, date, 1, false, false));
        assertEquals(bits(0), Primitives.datePartComparison(date, missing, 1, false, true));
        assertEquals(bits(), Primitives.datePartComparison(date, missing, 1, false, false));
    }


    /**
     * Numeric time-part equality is strict inside the epsilon (line 337): a *DTM whose time part
     * differs from the target by EXACTLY 1e-9 is NOT equal. The boundary input kills the
     * {@code <}→{@code <=} mutant that would silently widen the tolerance.
     */
    @Test
    void numericTimePartEqualityIsStrictlyInsideTheEpsilon()
    {
        ConstVector midnight = ConstVector.of(0.0);
        assertEquals(bits(),
                Primitives.datePartComparison(midnight,
                        ConstVector.of(ScalarSemantics.DATE_EPSILON), 1, true, false),
                "a difference of exactly the epsilon is NOT an equal time part");
        // Positive twin: identical time parts fire the (non-negated) equality.
        assertEquals(bits(0),
                Primitives.datePartComparison(midnight, ConstVector.of(0.0), 1, true, false));
    }


    /**
     * A mixed numeric/ISO pair is malformed data and fires regardless of direction (line 350) —
     * replacing that return with NO_FIRE would silence the report on the corrupt rows.
     */
    @Test
    void mixedNumericIsoDatePartPairFires()
    {
        assertEquals(bits(0), Primitives.datePartComparison(ConstVector.of(5.0),
                ConstVector.of("2024-01-01"), 1, false, false));
        assertEquals(bits(0), Primitives.datePartComparison(ConstVector.of("2024-01-01"),
                ConstVector.of(5.0), 1, false, false));
    }

    // -------------------------------------------------------------------------
    // datePartComparison — ISO branch (datePartIsoCode, lines 367-394)
    // -------------------------------------------------------------------------


    /**
     * A bare time with a leading 'T' has its time part at index 1 — 'T' at index 0 is a real time
     * part, not "no time part" (line 367 boundary: {@code aT < 0}→{@code aT <= 0} would answer
     * UNDEFINED and drop the match).
     */
    @Test
    void leadingTLhsStillHasATimePart()
    {
        ConstVector lhs = ConstVector.of("T13:30");
        assertEquals(bits(0),
                Primitives.datePartComparison(lhs, ConstVector.of("13:30"), 1, true, false));
        // Negative twin: a different time must not fire.
        assertEquals(bits(),
                Primitives.datePartComparison(lhs, ConstVector.of("14:30"), 1, true, false));
    }


    /**
     * A date-only LHS has no time part: the verdict is UNDEFINED, which behaves like missing — the
     * negated operator fires, the plain one does not (line 369: replacing UNDEFINED with NO_FIRE
     * would silence {@code time_part_not_equal_to} on date-only *DTC values).
     */
    @Test
    void dateOnlyLhsTimePartIsUndefined()
    {
        ConstVector dateOnly = ConstVector.of("2024-01-01");
        ConstVector time = ConstVector.of("13:30");
        assertEquals(bits(0), Primitives.datePartComparison(dateOnly, time, 1, true, true));
        assertEquals(bits(), Primitives.datePartComparison(dateOnly, time, 1, true, false));
    }


    /**
     * A full-datetime RHS contributes the substring AFTER its 'T' as the time part (line 373):
     * negating the {@code bT >= 0} check would compare against the whole RHS string and never
     * match.
     */
    @Test
    void datetimeRhsContributesItsTimePart()
    {
        ConstVector lhs = ConstVector.of("2024-01-02T13:30");
        assertEquals(bits(0), Primitives.datePartComparison(lhs, ConstVector.of("2024-01-01T13:30"),
                1, true, false), "same clock time on different days IS an equal time part");
        assertEquals(bits(), Primitives.datePartComparison(lhs, ConstVector.of("2024-01-01T14:30"),
                1, true, false));
    }


    /**
     * The ISO date-part verdict itself (line 394): equal date parts of two datetimes fire the
     * equality, different ones do not — replacing the return with 0 would kill the whole
     * {@code date_part_equal_to} family on ISO data.
     */
    @Test
    void isoDatePartEqualityBothVerdicts()
    {
        ConstVector rhs = ConstVector.of("2024-01-01T23:59");
        assertEquals(bits(0), Primitives.datePartComparison(ConstVector.of("2024-01-01T10:00"), rhs,
                1, false, false));
        assertEquals(bits(), Primitives.datePartComparison(ConstVector.of("2024-01-02T10:00"), rhs,
                1, false, false));
    }


    /**
     * A bare-time LHS has an EMPTY date part: the verdict is UNDEFINED, behaving like missing — the
     * negated operator fires, the plain one does not (line 394: replacing UNDEFINED with NO_FIRE
     * would silence {@code date_part_not_equal_to} on time-only values).
     */
    @Test
    void bareTimeLhsDatePartIsUndefined()
    {
        ConstVector bareTime = ConstVector.of("T13:30");
        ConstVector date = ConstVector.of("2024-01-01");
        assertEquals(bits(0), Primitives.datePartComparison(bareTime, date, 1, false, true));
        assertEquals(bits(), Primitives.datePartComparison(bareTime, date, 1, false, false));
    }

    // -------------------------------------------------------------------------
    // compareCells — the date_* per-pair verdict
    // -------------------------------------------------------------------------


    /**
     * A null right operand answers {@code negate} (line 245): {@code date_not_equal_to} fires on
     * it, {@code date_equal_to} does not. The replaced-with-false mutant would silence the negated
     * operator on every missing comparand.
     */
    @Test
    void compareCellsNullRhsAnswersNegate()
    {
        assertTrue(
                Primitives.compareCells(DataValues.of("2024-01-01"), null, 0, false, true, true));
        assertFalse(
                Primitives.compareCells(DataValues.of("2024-01-01"), null, 0, false, false, true));
    }

    // -------------------------------------------------------------------------
    // containsAlpha / containsDigit — has_alpha / has_digit charset boundaries
    // -------------------------------------------------------------------------


    /**
     * The four charset corners 'A','Z','a','z' each count as a letter, and their four ASCII
     * neighbours '@','[','`','{' do not (line 1083: four boundary mutants and one negation). A
     * digit-only probe pins the conjunction against the negation mutant.
     */
    @Test
    void hasAlphaCharsetCorners()
    {
        IDataTable t = MockTable.of().col("X", "A", "Z", "a", "z", "@", "[", "`", "{", "5", "")
                .build();
        assertEquals(bits(0, 1, 2, 3), Primitives.hasAlpha(col(t, "X"), 10));
    }


    /** The digit corners '0' and '9' count; their neighbours '/' and ':' do not (line 1097). */
    @Test
    void hasDigitCharsetCorners()
    {
        IDataTable t = MockTable.of().col("X", "0", "9", "/", ":", "A", "").build();
        assertEquals(bits(0, 1), Primitives.hasDigit(col(t, "X"), 6));
    }

    // -------------------------------------------------------------------------
    // isNumeric — hand-rolled decimal scan boundaries
    // -------------------------------------------------------------------------


    /**
     * '0' and '9' are digits in both the integer and the fraction scan (lines 966/975), and the
     * documented rejections stay rejected. An off-by-one on the digit range would silently
     * reclassify numeric lab values as text.
     */
    @Test
    void isNumericDigitBoundariesAndRejections()
    {
        IDataTable t = MockTable.of().col("X", "0", "9", "1.0", "1.9", "-3", ".5", "007", // numeric
                "", "abc", "1.", ".", "+5", " 1", "1 ", "1e5") // not numeric
                .build();
        assertEquals(bits(0, 1, 2, 3, 4, 5, 6), Primitives.isNumeric(col(t, "X"), 15, false));
        // The negated operator fires on exactly the complement.
        assertEquals(bits(7, 8, 9, 10, 11, 12, 13, 14),
                Primitives.isNumeric(col(t, "X"), 15, true));
    }

    // -------------------------------------------------------------------------
    // isValidTestcd / isValidName — identifier charset and length boundaries
    // -------------------------------------------------------------------------


    /**
     * Test-code charset corners (lines 1040-1042): 'A'/'Z'/'a'/'z' lead, digits '0'/'9' only from
     * position 1, length 1..8 inclusive. "0A" pins the digit-not-first rule against the
     * {@code i > 0}→{@code i >= 0} boundary.
     */
    @Test
    void isValidTestcdCharsetAndLengthCorners()
    {
        IDataTable t = MockTable.of().col("X", "A", "Z", "a", "z", "_", "A0", "A9", "ABCDEFGH", // valid
                "@", "[", "`", "{", "0A", "A-", "ABCDEFGHI", "") // invalid
                .build();
        assertEquals(bits(0, 1, 2, 3, 4, 5, 6, 7), Primitives.isValidTestcd(col(t, "X"), 16));
    }


    /** Variable names are uppercase-only: the lowercase corners must NOT be valid. */
    @Test
    void isValidNameRejectsLowercase()
    {
        IDataTable t = MockTable.of().col("X", "A", "Z", "_", "A0", "a", "z", "0A").build();
        assertEquals(bits(0, 1, 2, 3), Primitives.isValidName(col(t, "X"), 7));
    }

    // -------------------------------------------------------------------------
    // lengthCompare / comparison — direction routing
    // -------------------------------------------------------------------------


    /**
     * longer_than / shorter_than are strict (line 776): a value of exactly the probed length fires
     * neither. The direction-0 row pins the current routing (non-positive directions take the
     * less-than arm) so a boundary mutant cannot silently reroute it.
     */
    @Test
    void lengthCompareIsStrictAtTheExactLength()
    {
        IDataTable t = MockTable.of().col("X", "AB", "ABC", "A", (String) null).build();
        assertEquals(bits(1), Primitives.lengthCompare(col(t, "X"), 2, 4, 1),
                "longer_than 2: only the length-3 value; length 2 sits ON the boundary");
        assertEquals(bits(2, 3), Primitives.lengthCompare(col(t, "X"), 2, 4, -1),
                "shorter_than 2: length 1 and the missing cell (folds to length 0)");
        // direction 0 is not a compiled form today; pin that it routes to the less-than arm.
        assertEquals(bits(2, 3), Primitives.lengthCompare(col(t, "X"), 2, 4, 0));
    }


    /**
     * Numeric comparison direction routing (line 166): direction 0 is not a compiled form; pin that
     * non-positive directions take the less-than arm so the {@code >}→{@code >=} boundary mutant
     * cannot silently flip it to greater-than.
     */
    @Test
    void comparisonDirectionZeroRoutesToTheLessThanArm()
    {
        IDataTable t = MockTable.of().colLong("AGE", 10L, 20L, 30L).build();
        ConstVector twenty = ConstVector.of(20L);
        assertEquals(bits(0), Primitives.comparison(col(t, "AGE"), twenty, 3, 0, false));
        assertEquals(bits(0, 1), Primitives.comparison(col(t, "AGE"), twenty, 3, 0, true));
    }

    // -------------------------------------------------------------------------
    // extractPrefix / extractSuffix — affix length edges
    // -------------------------------------------------------------------------


    /** A null / zero / negative / over-long length mirrors the whole string (line 523). */
    @Test
    void extractPrefixEdges()
    {
        assertEquals("ABC", Primitives.extractPrefix("ABC", null));
        assertEquals("ABC", Primitives.extractPrefix("ABC", 0),
                "length 0 sits on the <= 0 boundary and must mirror the whole string");
        assertEquals("ABC", Primitives.extractPrefix("ABC", -1));
        assertEquals("AB", Primitives.extractPrefix("ABC", 2));
        assertEquals("ABC", Primitives.extractPrefix("ABC", 3));
        assertEquals("ABC", Primitives.extractPrefix("ABC", 4));
    }


    /** The suffix mirror of {@link #extractPrefixEdges()} (line 537). */
    @Test
    void extractSuffixEdges()
    {
        assertEquals("ABC", Primitives.extractSuffix("ABC", null));
        assertEquals("ABC", Primitives.extractSuffix("ABC", 0));
        assertEquals("BC", Primitives.extractSuffix("ABC", 2));
        assertEquals("ABC", Primitives.extractSuffix("ABC", 3));
        assertEquals("ABC", Primitives.extractSuffix("ABC", 4));
    }

    // -------------------------------------------------------------------------
    // listMembership / anyInSet — list-LHS membership
    // -------------------------------------------------------------------------


    /**
     * A non-list operand contains nothing (line 889): {@code is_contained_by} never fires on it and
     * {@code is_not_contained_by} always does. The exact-BitSet assert on the negated form also
     * kills the loop-bound mutant (line 837, {@code r < rowCount}→{@code <=}) which would set a bit
     * past the last row.
     */
    @Test
    void listMembershipNonListOperandContainsNothing()
    {
        ConstVector scalar = ConstVector.of("Y");
        assertEquals(bits(), Primitives.listMembership(scalar, Set.of("Y"), 2, false, false));
        assertEquals(bits(0, 1), Primitives.listMembership(scalar, Set.of("Y"), 2, true, false));
    }


    /**
     * Null elements in the list are skipped, and a present member still matches past them (line
     * 897) — negating the null-skip either NPEs or drops the real member.
     */
    @Test
    void listMembershipSkipsNullElementsAndMatchesTheRest()
    {
        ConstVector list = ConstVector.of(Arrays.asList(null, "Y"));
        assertEquals(bits(0), Primitives.listMembership(list, Set.of("Y"), 1, false, false));
        assertEquals(bits(), Primitives.listMembership(list, Set.of("N"), 1, false, false));
        // Case-insensitive variant folds the element, not the (pre-folded) set.
        ConstVector lower = ConstVector.of(List.of("y"));
        assertEquals(bits(0), Primitives.listMembership(lower, Set.of("Y"), 1, false, true));
        assertEquals(bits(), Primitives.listMembership(lower, Set.of("Y"), 1, false, false));
    }

    // -------------------------------------------------------------------------
    // contains — collection-valued LHS with a per-row needle (line 634)
    // -------------------------------------------------------------------------


    /**
     * A collection-valued LHS with a vector needle is exact membership, both polarities (line 634
     * was fully uncovered: a mutant could fire {@code contains} on every codelist row or none).
     */
    @Test
    void containsWithCollectionLhsAndVectorNeedleIsMembership()
    {
        ConstVector codes = ConstVector.of(List.of("A", "B"));
        assertEquals(bits(0), Primitives.contains(codes, ConstVector.of("A"), 1, false));
        assertEquals(bits(), Primitives.contains(codes, ConstVector.of("C"), 1, false));
        assertEquals(bits(0), Primitives.contains(codes, ConstVector.of("C"), 1, true));
        assertEquals(bits(), Primitives.contains(codes, ConstVector.of("B"), 1, true));
    }
}
