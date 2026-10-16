package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.cumba.corej.core.expr.eval.IsoDateBounds;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.IDataValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The shared extreme machinery ({@link Extremes}), moved out of {@code OperationExecutor} by
 * runbook W5 — the EC-46 determinability matrix and the EC-51 candidate filter, which used to be
 * pinned through the retired {@code min_date} / {@code max_date} / {@code max} operations in
 * {@code OperationExecutorTest}. The claims are the same; the vehicle is the accumulator itself
 * (the functions' own behaviour is pinned in {@link GroupedAggregateFunctionsTest}).
 */
class ExtremesTest
{

    private static String min(String... raws)
    {
        return extreme(false, false, raws);
    }


    private static String max(String... raws)
    {
        return extreme(true, false, raws);
    }


    private static String extreme(boolean findMax, boolean indeterminate, String... raws)
    {
        Extremes.DateExtreme extreme = new Extremes.DateExtreme(findMax, indeterminate, false);
        for (String raw : raws)
        {
            extreme.add(raw);
        }
        return extreme.result();
    }


    private static IDataValue cell(String text)
    {
        IDataTable t = RealTableFixture.of("T").str("X", text).build();
        return t.getColumn(0).getDataValue(0);
    }

    // -----------------------------------------------------------------------
    // EC-46 — determinability
    // -----------------------------------------------------------------------


    @Test
    void ec46_allCompleteIsUnchanged()
    {
        assertEquals("2012-05-31", min("2012-06-15", "2012-06-01", "2012-06-30", "2012-05-31"));
        assertEquals("2012-06-30", max("2012-06-15", "2012-06-01", "2012-06-30", "2012-05-31"));
    }


    /**
     * <b>Defect A's headline pair.</b> {@code max{2012-06, 2012-06-15}} used to return
     * {@code 2012-06-15}; the partial's latest completion is {@code 2012-06-30}, which is later, so
     * the max cannot be determined.
     */
    @Test
    void ec46_maxIsIndeterminateWhenAPartialCouldBeLater()
    {
        assertNull(max("2012-06", "2012-06-15"), "2012-06 could be the 30th");
    }


    /** The benign MAX tie: the partial cannot end after the last day of its own month. */
    @Test
    void ec46_maxResolvesWhenThePartialCannotReachPastTheCompleteDate()
    {
        assertEquals("2012-06-30", max("2012-06", "2012-06-30"));
    }


    @Test
    void ec46_maxResolvesForANonPrefixPair()
    {
        assertEquals("2012-06-15", max("2012-05", "2012-06-15"),
                "May can never reach past a June date");
    }


    /** OQ1: {@code min{2012-06, 2012-06-01}} — the partial cannot precede the 1st. */
    @Test
    void ec46_oq1TheCompleteDateWinsWhenThePartialCannotBeatIt()
    {
        assertEquals("2012-06-01", min("2012-06", "2012-06-01"));
    }


    /** OQ1's explicit exclusion: {@code min{2012-06, 2012-06-02}} — the partial may be the 1st. */
    @Test
    void ec46_oq1ExclusionThePartialCanStillWin()
    {
        assertNull(min("2012-06", "2012-06-02"));
    }


    /** The order of arrival must not change the answer — the rule is not a pairwise fold. */
    @Test
    void ec46_resultIsIndependentOfRowOrder()
    {
        assertEquals("2012-06-01", min("2012-06-02", "2012-06-01", "2012-06"));
        assertEquals("2012-06-01", min("2012-06", "2012-06-01", "2012-06-02"));
    }


    /** Defect C: lexically {@code …T00:00:00Z} sorts first, but the {@code +02:00} is earlier. */
    @Test
    void ec46_offsetsAreNormalisedBeforeSelection()
    {
        assertEquals("2012-06-15T01:00:00+02:00",
                min("2012-06-15T00:00:00Z", "2012-06-15T01:00:00+02:00"));
    }


    @Test
    void ec46_maskedDayIsAMonthWideHull()
    {
        assertNull(min("2012-06--", "2012-06-15"), "the masked day could be the 1st");
        assertNull(max("2012-06--", "2012-06-15"), "the masked day could be the 30th");
    }


    @Test
    void ec46_maskedDayResolvesAgainstTheMonthEnd()
    {
        assertEquals("2012-06-30", max("2012-06--", "2012-06-30"));
    }


    @ParameterizedTest
    @ValueSource(strings =
    {
            "UNK", "2012", "2012-13-01", "not a date"
    })
    void ec46_aPresentUnpositionableValueMakesBothExtremesIndeterminate(String junk)
    {
        assertNull(min(junk, "2012-06-15", "2012-07-20"),
                junk + " must make the min indeterminate");
        assertNull(max(junk, "2012-06-15", "2012-07-20"),
                junk + " must make the max indeterminate");
    }


    @Test
    void ec46_dayPrecisionSpansItsDayAgainstASameDayTimestamp()
    {
        assertEquals("2012-06-15", min("2012-06-15", "2012-06-15T10:30:00"),
                "midnight is the earliest instant of the day");
        assertEquals("2012-06-15", max("2012-06-15", "2012-06-15T10:30:00"),
                "the day-precision cell could be as late as 23:59:59, so it takes the max");
    }


    /** OQ4: the generic string branch is NOT date-only — a category column keeps text order. */
    @Test
    void ec46_oq4GenericMaxKeepsLexicographicOrderForNonDates()
    {
        assertEquals("NORMAL",
                Extremes.genericStringExtreme(List.of("NORMAL", "HIGH", "LOW"), true));
        assertEquals("HIGH",
                Extremes.genericStringExtreme(List.of("NORMAL", "HIGH", "LOW"), false));
    }


    /**
     * …and a date-looking group on the generic branch ranks as TEXT too (owner K3, 2026-09-30:
     * <i>"no, special handling for special texts. Text is text."</i>). ⚑ MOVED ANSWER: until then
     * this test pinned EC-46's date rule here ({@code max{2012-06, 2012-06-15}} indeterminate,
     * {@code null}); the date rule is {@code max_date} / {@code min_date}'s alone.
     */
    @Test
    void k3_genericMaxRanksADateLookingGroupAsText()
    {
        assertEquals("2012-06-15",
                Extremes.genericStringExtreme(List.of("2012-06", "2012-06-15"), true));
        assertEquals("2012-06-30",
                Extremes.genericStringExtreme(List.of("2012-06", "2012-06-30"), true));
        assertEquals("2012-06",
                Extremes.genericStringExtreme(List.of("2012-06", "2012-06-15"), false),
                "the shorter text is the minimum — plain text order, no calendar");
        assertNull(Extremes.genericStringExtreme(List.of(), true), "no candidate, no answer");
    }

    // -----------------------------------------------------------------------
    // PLAN-date-extreme-precision-tie — the default (hull) mode is row-order free (T2, DT3)
    // -----------------------------------------------------------------------


    /** {@code raws} reversed — every tie row below is asserted in both row orders. */
    private static String[] reversed(String... raws)
    {
        List<String> out = new ArrayList<>(List.of(raws));
        Collections.reverse(out);
        return out.toArray(String[]::new);
    }


    private static void assertMinInBothOrders(String expected, String... raws)
    {
        assertEquals(expected, min(raws), () -> "min" + List.of(raws));
        assertEquals(expected, min(reversed(raws)), () -> "min" + List.of(reversed(raws)));
    }


    private static void assertMaxInBothOrders(String expected, String... raws)
    {
        assertEquals(expected, max(raws), () -> "max" + List.of(raws));
        assertEquals(expected, max(reversed(raws)), () -> "max" + List.of(reversed(raws)));
    }


    /**
     * T2: a nested pair on the same edge — {@code 2020-01-01} and {@code …T00:00} share the lower
     * bound {@code T00:00:00}, {@code 2020-01-01} and {@code …T23:59} the upper {@code T23:59:59}.
     * The first-seen candidate used to win (strict {@code beats}); the default answers the wider
     * hull — the date, as the rule guide documents — in both row orders.
     */
    @Test
    void t2_aNestedSameEdgePairAnswersTheWiderHullInEitherRowOrder()
    {
        assertMinInBothOrders("2020-01-01", "2020-01-01", "2020-01-01T00:00");
        assertMaxInBothOrders("2020-01-01", "2020-01-01", "2020-01-01T23:59");
        assertMaxInBothOrders("2020-01-01", "2020-01-01T23:59", "2020-01-01", "2019-12-31");
        assertMinInBothOrders("2020-01-01T08:00", "2020-01-01T08:00", "2020-01-01T08:00:00",
                "2020-01-01T09:00");
        assertMaxInBothOrders("2020-01-01T08:00", "2020-01-01T08:00", "2020-01-01T08:00:59");
    }


    /**
     * DT3 / T8: an equal hull spelt differently answers the longer trimmed text (the explicit
     * offset, the fraction), then code-point order — ascending for a min, descending for a max.
     */
    @Test
    void dt3_anEqualHullAnswersTheLongerTextThenTheDirectionalCodePointOrder()
    {
        assertMinInBothOrders("2020-01-01T09:00+01:00", "2020-01-01T08:00Z",
                "2020-01-01T09:00+01:00");
        assertMaxInBothOrders("2020-01-01T09:00+01:00", "2020-01-01T08:00Z",
                "2020-01-01T09:00+01:00");
        assertMinInBothOrders("2020-01-01T08:00Z", "2020-01-01T08:00", "2020-01-01T08:00Z");
        assertMaxInBothOrders("2020-01-01T08:00:00.5", "2020-01-01T08:00:00",
                "2020-01-01T08:00:00.5");
        assertMinInBothOrders("2020-01-01T08:00:00.1", "2020-01-01T08:00:00.1",
                "2020-01-01T08:00:00.9");
        assertMaxInBothOrders("2020-01-01T08:00:00.9", "2020-01-01T08:00:00.1",
                "2020-01-01T08:00:00.9");
        // the trimmed length: a leading blank is not "more explicit"
        assertMinInBothOrders("2020-01-01T08:00Z", " 2020-01-01T08:00", "2020-01-01T08:00Z");
        // the one instant across midnight in two offsets (the date_diff_days shape, T7 (ii))
        assertMinInBothOrders("2020-01-01T23:30-01:00", "2020-01-02T00:30Z",
                "2020-01-01T23:30-01:00");
    }


    /** §4.2's worked table, default (hull) mode — the date keeps its EC-46 answer. */
    @Test
    void workedTable_theDefaultModeKeepsTheHullAnswers()
    {
        assertMinInBothOrders("2020-01-01", "2020-01-01", "2020-01-01T08:00");
        assertMaxInBothOrders("2020-01-01", "2020-01-01", "2020-01-01T08:00");
        assertMinInBothOrders("2020-01-01", "2020-01-01", "2020-01-01T08", "2020-01-01T09:15");
        assertMaxInBothOrders("2020-01-01", "2020-01-01", "2020-01-01T08", "2020-01-01T09:15");
        assertNull(min("2020-01", "2020-01-01T08:00"), "DT2 (a): the partial could be earlier");
        assertNull(max("2020-01", "2020-01-01T08:00"));
        assertMinInBothOrders("2020-01-01", "2020-01", "2020-01-01", "2020-01-01T08:00");
        assertNull(max("2020-01", "2020-01-01", "2020-01-01T08:00"), "the 31st");
        assertMinInBothOrders("2020-01-01", "2020-01-01", "2020-01-02T08:00");
        assertMaxInBothOrders("2020-01-02T08:00", "2020-01-01", "2020-01-02T08:00");
        // hour precision is NOT determined (isCompleteDate: lengths 10 / 16 / 19 only), so the
        // EC-46 gate answers nothing for a pair it nests in — in both modes (the plan's §4.2 row
        // "…T08, …T08:30 → …T08" assumed otherwise)
        assertNull(min("2020-01-01T08", "2020-01-01T08:30"));
        assertNull(max("2020-01-01T08", "2020-01-01T08:30"));
    }

    // -----------------------------------------------------------------------
    // PLAN-date-extreme-precision-tie — ties="precise" (DT1 (a), DT2 (a), DT3 (a), T3, T4, T11)
    // -----------------------------------------------------------------------


    private static String precise(boolean findMax, boolean indeterminate, String... raws)
    {
        Extremes.DateExtreme extreme = new Extremes.DateExtreme(findMax, indeterminate, true);
        for (String raw : raws)
        {
            extreme.add(raw);
        }
        String answer = extreme.result();
        // T4: the answer is always one candidate's own raw text
        if (answer != null)
        {
            assertTrue(List.of(raws).contains(answer), answer + " is not an input");
        }
        return answer;
    }


    private static void assertPreciseMin(String expected, String... raws)
    {
        assertEquals(expected, precise(false, false, raws), () -> "precise min" + List.of(raws));
        assertEquals(expected, precise(false, false, reversed(raws)),
                () -> "precise min" + List.of(reversed(raws)));
    }


    private static void assertPreciseMax(String expected, String... raws)
    {
        assertEquals(expected, precise(true, false, raws), () -> "precise max" + List.of(raws));
        assertEquals(expected, precise(true, false, reversed(raws)),
                () -> "precise max" + List.of(reversed(raws)));
    }


    /** The precise twin of {@link #ec46_dayPrecisionSpansItsDayAgainstASameDayTimestamp}. */
    @Test
    void precise_aSameDayTimestampWinsBothExtremesOverItsDay()
    {
        assertPreciseMin("2012-06-15T10:30:00", "2012-06-15", "2012-06-15T10:30:00");
        assertPreciseMax("2012-06-15T10:30:00", "2012-06-15", "2012-06-15T10:30:00");
    }


    /** §4.2's worked table, precise mode, every row in both row orders. */
    @Test
    void workedTable_thePreciseModeAnswersTheEarliestOrLatestAtom()
    {
        assertPreciseMin("2020-01-01T08:00", "2020-01-01", "2020-01-01T08:00");
        assertPreciseMax("2020-01-01T08:00", "2020-01-01", "2020-01-01T08:00");
        assertPreciseMin("2020-01-01T08:00", "2020-01-01", "2020-01-01T08:00", "2020-01-01T09:15");
        assertPreciseMax("2020-01-01T09:15", "2020-01-01", "2020-01-01T08:00", "2020-01-01T09:15");
        assertPreciseMin("2020-01-01T08:00:30", "2020-01-01", "2020-01-01T08:00",
                "2020-01-01T08:00:30");
        assertPreciseMax("2020-01-01T08:00:30", "2020-01-01", "2020-01-01T08:00",
                "2020-01-01T08:00:30");
        assertPreciseMin("2020-01-01T00:00", "2020-01-01", "2020-01-01T00:00");
        assertPreciseMax("2020-01-01T00:00", "2020-01-01", "2020-01-01T00:00");
        assertPreciseMax("2020-01-01T23:59", "2020-01-01", "2020-01-01T23:59");
        assertPreciseMin("2020-01-01T09:00+01:00", "2020-01-01T08:00Z", "2020-01-01T09:00+01:00");
        assertPreciseMin("2020-01-01T08:00:00.1", "2020-01-01T08:00:00.1", "2020-01-01T08:00:00.9");
        assertPreciseMax("2020-01-01T08:00:00.9", "2020-01-01T08:00:00.1", "2020-01-01T08:00:00.9");
        // DT2 (a): determinability is unchanged
        assertNull(precise(false, false, "2020-01", "2020-01-01T08:00"));
        assertNull(precise(true, false, "2020-01", "2020-01-01T08:00"));
        assertPreciseMin("2020-01-01T08:00", "2020-01", "2020-01-01", "2020-01-01T08:00");
        assertNull(precise(true, false, "2020-01", "2020-01-01", "2020-01-01T08:00"));
        assertNull(precise(false, false, "2020-01-01T08", "2020-01-01T08:30"));
        // no tie: unchanged
        assertPreciseMin("2020-01-01", "2020-01-01", "2020-01-02T08:00");
        assertPreciseMax("2020-01-02T08:00", "2020-01-01", "2020-01-02T08:00");
    }


    /**
     * T11: an undetermined value (hour precision, an interval) inside the winning day never wins
     * (DT2 (a)), and where it could reach the refined value the precise mode keeps the hull answer
     * rather than answer a value that is provably not the extreme.
     */
    @Test
    void t11_anUndeterminedValueInsideTheDayKeepsTheHullAnswerWhereItCouldReachTheRefinedOne()
    {
        // …T08 is certainly earlier than …T09:15: the earliest determined atom is not the min
        assertPreciseMin("2020-01-01", "2020-01-01", "2020-01-01T08", "2020-01-01T09:15");
        assertPreciseMax("2020-01-01", "2020-01-01", "2020-01-01T10", "2020-01-01T09:15");
        // …T08 contains …T08:30 — it could be 08:05
        assertPreciseMin("2020-01-01", "2020-01-01", "2020-01-01T08", "2020-01-01T08:30");
        // an interval inside the day, starting before the refined value
        assertPreciseMin("2020-01-01", "2020-01-01", "2020-01-01T07:00/2020-01-01T07:30",
                "2020-01-01T08:00");
        // ... and one that cannot reach it does not block
        assertPreciseMin("2020-01-01T08:00", "2020-01-01", "2020-01-01T10", "2020-01-01T08:00");
        assertPreciseMax("2020-01-01T09:15", "2020-01-01", "2020-01-01T08", "2020-01-01T09:15");
        // a partial CONTAINING the winning day does not block (DT2 (a)'s consequence)
        assertPreciseMin("2020-01-01T08:00", "2020", "2020-01-01", "2020-01-01T08:00");
    }


    /**
     * DT review L1 (PACK H fold): an undetermined candidate whose nearest bound only TOUCHES the
     * refined value cannot beat it — for a min {@code U.lower == R.upper} means {@code U >= R}, so
     * at point equality it does not block; one second closer and it could be the extreme, and the
     * hull answer stands. The hulls are hand-built: the real bounds never put a determined atom's
     * upper exactly on another candidate's lower.
     */
    @Test
    void anUndeterminedCandidateTouchingTheRefinedValueDoesNotBlock()
    {
        Extremes.DateExtreme min = new Extremes.DateExtreme(false, false, true);
        min.add("W", new IsoDateBounds.Hull("2020-01-01T00:00:00", "2020-01-01T23:59:59", true));
        min.add("R", new IsoDateBounds.Hull("2020-01-01T08:00:00", "2020-01-01T08:00:00", true));
        min.add("U", new IsoDateBounds.Hull("2020-01-01T08:00:00", "2020-01-01T08:59:59", false));
        assertEquals("R", min.result(), "U is at or after R: it cannot be the min");
        Extremes.DateExtreme blockedMin = new Extremes.DateExtreme(false, false, true);
        blockedMin.add("W",
                new IsoDateBounds.Hull("2020-01-01T00:00:00", "2020-01-01T23:59:59", true));
        blockedMin.add("R",
                new IsoDateBounds.Hull("2020-01-01T08:00:00", "2020-01-01T08:00:00", true));
        blockedMin.add("U",
                new IsoDateBounds.Hull("2020-01-01T07:59:59", "2020-01-01T08:59:59", false));
        assertEquals("W", blockedMin.result(), "U could be 07:59:59, before R");

        Extremes.DateExtreme max = new Extremes.DateExtreme(true, false, true);
        max.add("W", new IsoDateBounds.Hull("2020-01-01T00:00:00", "2020-01-01T23:59:59", true));
        max.add("R", new IsoDateBounds.Hull("2020-01-01T20:00:00", "2020-01-01T20:00:00", true));
        max.add("U", new IsoDateBounds.Hull("2020-01-01T19:00:00", "2020-01-01T20:00:00", false));
        assertEquals("R", max.result(), "U is at or before R: it cannot be the max");
        Extremes.DateExtreme blockedMax = new Extremes.DateExtreme(true, false, true);
        blockedMax.add("W",
                new IsoDateBounds.Hull("2020-01-01T00:00:00", "2020-01-01T23:59:59", true));
        blockedMax.add("R",
                new IsoDateBounds.Hull("2020-01-01T20:00:00", "2020-01-01T20:00:00", true));
        blockedMax.add("U",
                new IsoDateBounds.Hull("2020-01-01T19:00:00", "2020-01-01T20:00:01", false));
        assertEquals("W", blockedMax.result(), "U could be 20:00:01, after R");
    }


    /**
     * DT review M1 (PACK H fold): the blocking test is decided from O(1) state — the undetermined
     * candidates' best primary bound, the best secondary bound among those sharing it, and the
     * second-best primary — never from a list of hulls. These cases exercise each piece: a
     * candidate sharing the winner's lower that does not contain the winner ({@code T00}), one that
     * contains it beside a second-best that reaches ({@code 2020} + {@code T07}), and the same
     * second-best out of reach ({@code T09}); the max arm mirrors them.
     */
    @Test
    void theUndeterminedBlockIsDecidedFromTheBestAndSecondBestBounds()
    {
        // lower == the winner's lower, upper inside the day: it could be 00:30, before 08:00
        assertPreciseMin("2020-01-01", "2020-01-01", "2020-01-01T00", "2020-01-01T08:00");
        // the year contains the day (no block of its own); T07 is the second-best lower and reaches
        assertPreciseMin("2020-01-01", "2020", "2020-01-01", "2020-01-01T07", "2020-01-01T08:00");
        // ... and T09 cannot reach 08:00
        assertPreciseMin("2020-01-01T08:00", "2020", "2020-01-01", "2020-01-01T09",
                "2020-01-01T08:00");
        // two sharing the winner's lower: the smaller upper decides (T00 does not contain the day)
        assertPreciseMin("2020-01-01", "2020-01", "2020-01-01", "2020-01-01T00",
                "2020-01-01T08:00");
        // the mirror: upper == the winner's upper, lower inside the day (the containing partial
        // is the MONTH of the month's LAST day — a year's upper would be later than the day's,
        // and the day could not reach the limit: indeterminate, EC-46)
        assertPreciseMax("2020-01-31", "2020-01-31", "2020-01-31T23", "2020-01-31T20:00");
        assertPreciseMax("2020-01-31", "2020-01", "2020-01-31", "2020-01-31T21",
                "2020-01-31T20:00");
        assertPreciseMax("2020-01-31T20:00", "2020-01", "2020-01-31", "2020-01-31T19",
                "2020-01-31T20:00");
        assertPreciseMax("2020-01-31", "2020-01", "2020-01-31", "2020-01-31T23",
                "2020-01-31T20:00");
    }


    /** T3: indeterminate short-circuits before any tie-break, in both modes. */
    @Test
    void t3_anIndeterminateMissingWinsOverATie()
    {
        for (boolean findMax : new boolean[]
        {
                false, true
        })
        {
            Extremes.DateExtreme e = new Extremes.DateExtreme(findMax, true, true);
            e.addCell(cell("2020-01-01"));
            e.addCell(cell("2020-01-01T08:00"));
            e.addCell(cell(""));
            assertNull(e.result(), "a missing candidate under indeterminate, findMax=" + findMax);
            Extremes.DateExtreme skip = new Extremes.DateExtreme(findMax, false, true);
            skip.addCell(cell("2020-01-01"));
            skip.addCell(cell(""));
            skip.addCell(cell("2020-01-01T08:00"));
            assertEquals("2020-01-01T08:00", skip.result(), "skip, findMax=" + findMax);
        }
    }


    /**
     * The refinement stays inside the hull winner by construction — even for hand-built hulls that
     * are not laminar (the real bounds are): the winner {@code W} is a candidate of the refined
     * search and the refined {@code Y} one of {@code W}'s, so {@code Y.upper <= W.upper} and
     * {@code Y.lower >= W.lower} (a min). The plan's "within-best" guard would be unreachable.
     */
    @Test
    void theRefinedAnswerLiesInsideTheHullWinnerEvenForNonLaminarHulls()
    {
        Extremes.DateExtreme min = new Extremes.DateExtreme(false, false, true);
        min.add("W", new IsoDateBounds.Hull("2020-01-01T00:00:00", "2020-01-01T10:00:00", true));
        min.add("Y", new IsoDateBounds.Hull("2020-01-01T05:00:00", "2020-01-01T06:00:00", true));
        min.add("Z", new IsoDateBounds.Hull("2020-01-01T05:30:00", "2020-01-02T00:00:00", true));
        assertEquals("Y", min.result());
        Extremes.DateExtreme max = new Extremes.DateExtreme(true, false, true);
        max.add("W", new IsoDateBounds.Hull("2020-01-01T00:00:00", "2020-01-01T23:00:00", true));
        max.add("Z", new IsoDateBounds.Hull("2019-12-31T00:00:00", "2020-01-01T21:00:00", true));
        max.add("Y", new IsoDateBounds.Hull("2020-01-01T20:00:00", "2020-01-01T20:30:00", true));
        assertEquals("Y", max.result());
    }

    // -----------------------------------------------------------------------
    // EC-51 — the candidate filter and the disposition
    // -----------------------------------------------------------------------


    @Test
    void extremeCandidate_emptyAndWhitespaceOnlyCellsAreNotCandidates()
    {
        assertNull(Extremes.extremeCandidate(cell("")));
        assertNull(Extremes.extremeCandidate(cell(" ")));
        assertNull(Extremes.extremeCandidate(cell("\t")));
        assertNull(Extremes.extremeCandidate(cell(" ")), "NBSP is blank");
        assertNull(Extremes.extremeCandidate(cell(" ")), "narrow NBSP is blank");
        assertNull(Extremes.extremeCandidate(cell(null)), "a missing cell is no candidate");
        assertNull(Extremes.extremeCandidate(null));
    }


    @Test
    void extremeCandidate_returnsTheRawTextNotAStrippedOne()
    {
        assertEquals(" 2024-06-01 ", Extremes.extremeCandidate(cell(" 2024-06-01 ")));
        assertEquals("2024-06-01", Extremes.extremeCandidate(cell("2024-06-01")));
    }


    @Test
    void ec51_aMissingCellIsSkippedByDefaultAndUndeterminableWhenDeclared()
    {
        Extremes.DateExtreme skip = new Extremes.DateExtreme(false, false, false);
        skip.addCell(cell(""));
        skip.addCell(cell("2024-06-01"));
        skip.addCell(cell("   "));
        assertEquals("2024-06-01", skip.result(), "a blank must not make the min indeterminate");

        Extremes.DateExtreme indeterminate = new Extremes.DateExtreme(false, true, false);
        indeterminate.addCell(cell(""));
        indeterminate.addCell(cell("2024-06-01"));
        assertNull(indeterminate.result(), "EC-51 Half B: the blank makes the min undeterminable");

        Extremes.DateExtreme none = new Extremes.DateExtreme(true, false, false);
        none.addCell(cell(" "));
        none.addCell(cell("\t"));
        assertNull(none.result(), "no candidate at all");
    }
}
