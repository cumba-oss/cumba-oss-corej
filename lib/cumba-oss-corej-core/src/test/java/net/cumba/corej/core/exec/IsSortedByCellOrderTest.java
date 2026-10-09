package net.cumba.corej.core.exec;

import static net.cumba.corej.core.exec.IsSortedByDirectionTest.fired;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Runbook W2 SB2 ({@code PLAN-is-sorted-by-direction} Q2, §5 hazard; owner O22): the sort keys of
 * {@code is_sorted_by} are ordered by {@link CellOrder} — the ONE total ordering {@code sequence}
 * and {@code is_increasing} read — instead of {@code String.compareTo}: a key whose every present
 * cell is a number orders numerically, any other key orders ISO cells by (lower bound, upper bound,
 * text) and then other text, each key classified over its cells in the group. SB1's placement
 * stands: a blank or missing key cell sorts first unless {@code nulls="last"}, whatever the
 * direction. ⚠ A comparator built on the engine's ISO relations is not a total order (TimSort
 * throws on 32+ rows); CellOrder is.
 */
class IsSortedByCellOrderTest
{

    private static List<Long> sorted(IDataTable table, String by)
    {
        return fired(table, "not is_sorted_by(SESEQ, by=" + by + ", within=USUBJID)");
    }


    /** One subject per pair: SESEQ 1 on the first cell, 2 on the second. */
    private static IDataTable pairs(String... keys)
    {
        int n = keys.length;
        String[] subjects = new String[n];
        Double[] seq = new Double[n];
        for (int i = 0; i < n; i++)
        {
            subjects[i] = "S" + (i / 2);
            seq[i] = (double) (i % 2 + 1);
        }
        return RealTableFixture.of("SE").str("USUBJID", subjects).dbl("SESEQ", seq)
                .str("SESTDTC", keys).build();
    }


    @Test
    void aNumericKeyOrdersByValue()
    {
        // text order put "10" before "9"
        IDataTable se = RealTableFixture.of("SE").str("USUBJID", "S1", "S1", "S1")
                .dbl("SESEQ", 1.0, 2.0, 3.0).str("VISITNUM", "2", "9", "10").build();
        assertEquals(List.of(), sorted(se, "[asc(VISITNUM)]"), "2, 9, 10 by value");
        assertEquals(List.of(1L, 2L, 3L), sorted(se, "[desc(VISITNUM)]"), "desc reverses it");
    }


    @Test
    void aPartialAgainstACompleteDateOrdersByItsBounds()
    {
        // (lower, upper, text): "2020-01" and "2020-01-01" share their lower bound, the complete
        // date's upper bound is earlier — text order put the partial first
        assertEquals(List.of(1L, 2L), sorted(pairs("2020-01", "2020-01-01"), "[SESTDTC]"),
                "the complete first day precedes the month");
        assertEquals(List.of(), sorted(pairs("2020-01-01", "2020-01"), "[SESTDTC]"),
                "so this order is sorted");
        assertEquals(List.of(), sorted(pairs("2020-01", "2020-01-15"), "[SESTDTC]"),
                "a partial precedes a complete value its lower bound precedes");
    }


    @Test
    void aDateAgainstItsDatetimesOrdersByItsBounds()
    {
        assertEquals(List.of(), sorted(pairs("2020-01-15", "2020-01-15T08:00"), "[SESTDTC]"),
                "a date precedes a later datetime of its day");
        // midnight's upper bound precedes the whole day's — text order put the date first
        assertEquals(List.of(1L, 2L), sorted(pairs("2020-01-15", "2020-01-15T00:00"), "[SESTDTC]"),
                "midnight precedes the whole day");
    }


    @Test
    void anOffsetIsAppliedWhereTextOrderDisagrees()
    {
        // 10:00+02:00 is 08:00Z, before 09:00Z — text order put T09 first
        assertEquals(List.of(1L, 2L),
                sorted(pairs("2020-01-15T09:00Z", "2020-01-15T10:00+02:00"), "[SESTDTC]"),
                "the instant decides, not the text");
    }


    @Test
    void aYearCellSortsAmongDatesAsAYear()
    {
        // O22 (a): a non-numeric key reads a four-digit cell as a year
        IDataTable se = RealTableFixture.of("SE").str("USUBJID", "S1", "S1", "S1")
                .dbl("SESEQ", 1.0, 2.0, 3.0).str("SESTDTC", "2019-03-01", "2020-06-15", "2021")
                .build();
        assertEquals(List.of(), sorted(se, "[SESTDTC]"), "2021 after 2020-06-15");
        IDataTable years = RealTableFixture.of("SE").str("USUBJID", "S1", "S1")
                .dbl("SESEQ", 1.0, 2.0).str("SESTDTC", "2020", "2021").build();
        assertEquals(List.of(), sorted(years, "[SESTDTC]"), "an all-year key is numeric");
    }


    @Test
    void blanksAndMissingsKeepSb1sPlacement()
    {
        IDataTable se = RealTableFixture.of("SE").str("USUBJID", "S1", "S1", "S1")
                .dbl("SESEQ", 1.0, 2.0, 3.0).str("SESTDTC", null, "", "2020-01-01").build();
        assertEquals(List.of(), sorted(se, "[SESTDTC]"), "nulls first, tied in file order");
        assertEquals(List.of(), sorted(se, "[desc(SESTDTC)]"), "first under desc too");
        assertEquals(List.of(1L, 2L, 3L), sorted(se, "[asc(SESTDTC, nulls=\"last\")]"),
                "last on request");
    }


    @Test
    void aFortyRowMixedPrecisionGroupDoesNotThrowAndFollowsCellOrder()
    {
        String[] cells =
        {
                "2020-01-15T10:00", "2020-01", "2020", "2020-01-15T08:00:00", "2020-01-14",
                "2020-01-15T08", "UNK", "", "2020-01-15", "2020-02", "NOT DONE", "2019-12-31",
                "2020-01-15T09:30", "2020-01-16", "2020-01-15T23:59:59", "2021", "2020-01-01",
                "2020-01-15T00:00", "2020-03", "2020-01-31", "2020-01-15T08:00", "2019",
                "2020-01-15T12", "2020-01-02", "2020-02-29", "2020-01-15T08:00:01", "2020-12",
                "2020-01-20", "2020-01-15T07:59", "2022-01-01", "2020-01-15T16:00", "2020-06",
                "2020-01-03", "2020-01-15T20", "2020-04-01", "2020-01-15T01:00", "2020-05",
                "2020-01-04", "2020-01-15T02:00", "2020-07-04"
        };
        assertEquals(40, cells.length);
        // SESEQ = each cell's rank under CellOrder, so the group is sorted exactly when the plan
        // orders its keys by CellOrder (text order disagrees on several pairs)
        List<Object> values = new ArrayList<>(Arrays.asList((Object[]) cells));
        CellOrder.Key key = CellOrder.key(values);
        Integer[] order = new Integer[cells.length];
        for (int i = 0; i < order.length; i++)
        {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) ->
        {
            boolean na = cells[a].isEmpty();
            boolean nb = cells[b].isEmpty();
            if (na || nb)
            {
                return na == nb ? 0 : na ? -1 : 1;
            }
            return key.compare(a, b);
        });
        Double[] seq = new Double[cells.length];
        String[] subjects = new String[cells.length];
        for (int rank = 0; rank < order.length; rank++)
        {
            seq[order[rank]] = (double) (rank + 1);
        }
        Arrays.fill(subjects, "S1");
        IDataTable se = RealTableFixture.of("SE").str("USUBJID", subjects).dbl("SESEQ", seq)
                .str("SESTDTC", cells).build();
        assertEquals(List.of(), sorted(se, "[SESTDTC]"), "sorted under CellOrder, no throw");
    }
}
