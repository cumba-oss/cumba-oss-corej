package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
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
        Extremes.DateExtreme extreme = new Extremes.DateExtreme(findMax, indeterminate);
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
        Extremes.DateExtreme skip = new Extremes.DateExtreme(false, false);
        skip.addCell(cell(""));
        skip.addCell(cell("2024-06-01"));
        skip.addCell(cell("   "));
        assertEquals("2024-06-01", skip.result(), "a blank must not make the min indeterminate");

        Extremes.DateExtreme indeterminate = new Extremes.DateExtreme(false, true);
        indeterminate.addCell(cell(""));
        indeterminate.addCell(cell("2024-06-01"));
        assertNull(indeterminate.result(), "EC-51 Half B: the blank makes the min undeterminable");

        Extremes.DateExtreme none = new Extremes.DateExtreme(true, false);
        none.addCell(cell(" "));
        none.addCell(cell("\t"));
        assertNull(none.result(), "no candidate at all");
    }
}
