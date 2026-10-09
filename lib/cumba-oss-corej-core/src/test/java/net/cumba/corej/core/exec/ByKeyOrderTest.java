package net.cumba.corej.core.exec;

import static net.cumba.corej.core.exec.IsSortedByDirectionTest.fired;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-by-key-numeric-order} (owner BK1, 2026-10-09: <i>"CellOrder agreed."</i>) — the two
 * single-key readers that sort a group, {@code has_next_corresponding_record} and
 * {@code empty_within_except_last_row}, order their {@code by=} key by {@link CellOrder}, the order
 * of {@code is_sorted_by} and {@code sequence} ({@code SortKeys.sortRowsByColumn}): a numeric key
 * by value, any other key ISO part by part with the UTC offset applied first, then text; blank and
 * missing cells first, tied, in file order. Before, both compared the key cells' text whatever the
 * column's type, so {@code 10} sorted between {@code 1} and {@code 2} and a subject with ten or
 * more elements was walked in the wrong order.
 *
 * <p>
 * ⚠ Wherever the order is the point (T1, T3) the file order is neither text nor numeric order, so a
 * no-op sort, a text sort and a numeric sort give three different answers and only the last passes.
 * </p>
 */
class ByKeyOrderTest
{

    private static final String NEXT = "not has_next_corresponding_record(SEENDTC, SESTDTC,"
            + " by=SESEQ, within=USUBJID)";

    private static final String EMPTY = "empty_within_except_last_row(SEENDTC, USUBJID, by=SESEQ)";

    /** A ruling handle in parentheses: {@code (… O17)}, {@code (… W4 DI2)}, {@code (D84)}. */
    static final Pattern RULING_HANDLE = Pattern.compile("\\([^()]*\\b[A-Z]{1,3}\\d+\\)");

    private static String day(int d)
    {
        return String.format(java.util.Locale.ROOT, "2014-01-%02d", d);
    }


    /**
     * One subject's SE, {@code SESEQ} a LONG key written in {@code fileOrder}; element {@code k}
     * starts on day {@code start(k)} and ends on day {@code end(k)}.
     */
    private static IDataTable chain(long[] fileOrder, java.util.function.LongUnaryOperator start,
            java.util.function.LongUnaryOperator end)
    {
        int n = fileOrder.length;
        String[] subjects = new String[n];
        Arrays.fill(subjects, "S1");
        Long[] seq = Arrays.stream(fileOrder).boxed().toArray(Long[]::new);
        String[] starts = new String[n];
        String[] ends = new String[n];
        for (int i = 0; i < n; i++)
        {
            starts[i] = day((int) start.applyAsLong(fileOrder[i]));
            ends[i] = day((int) end.applyAsLong(fileOrder[i]));
        }
        return RealTableFixture.of("SE").str("USUBJID", subjects).lng("SESEQ", seq)
                .str("SESTDTC", starts).str("SEENDTC", ends).build();
    }


    /** The row positions of {@code values} as {@code sortByOrderColumn} orders them. */
    private static List<Integer> order(DataValueType type, Object... values)
    {
        IDataTable t = RealTables.of("SE").typed("K", type, values).build();
        IDataTableColumn column = t.getColumn(0);
        int[] rows = new int[values.length];
        for (int i = 0; i < rows.length; i++)
        {
            rows[i] = i;
        }
        GroupSemantics.sortByOrderColumn(rows, column);
        List<Integer> out = new ArrayList<>(rows.length);
        for (int r : rows)
        {
            out.add(r);
        }
        return out;
    }


    private static List<Integer> textOrder(String... values)
    {
        return order(DataValueType.STRING, (Object[]) values);
    }

    // ---------------------------------------------------------------- T1-T3: the readers


    @Test
    void t1ElevenElementsInACleanChainFireNothing()
    {
        // file order neither text (1, 10, 11, 2, ...) nor numeric; element k: day k -> day k+1
        long[] file =
        {
                5, 1, 10, 3, 11, 2, 9, 4, 8, 6, 7
        };
        assertEquals(List.of(), fired(chain(file, k -> k, k -> k + 1), NEXT),
                "numeric SESEQ order is a clean end->start chain");
    }


    @Test
    void t2OneRealGapFiresOnItsRowOnly()
    {
        // the gap: element 2 ends on day 3, element 3 starts on day 4 (every later one shifted)
        long[] file =
        {
                5, 1, 10, 3, 11, 2, 9, 4, 8, 6, 7
        };
        IDataTable se = chain(file, k -> k <= 2 ? k : k + 1, k -> k <= 2 ? k + 1 : k + 2);
        assertEquals(List.of(6L), fired(se, NEXT), "only SESEQ=2 (file row 6) has no next match");
    }


    @Test
    void t3TheEmptyEndOnTheNumericallyLastElementIsAllowed()
    {
        long[] file =
        {
                4, 10, 1, 7, 2, 9, 3, 6, 5, 8
        };
        int n = file.length;
        String[] subjects = new String[n];
        Arrays.fill(subjects, "S1");
        Long[] seq = Arrays.stream(file).boxed().toArray(Long[]::new);
        String[] ends = new String[n];
        for (int i = 0; i < n; i++)
        {
            ends[i] = file[i] == 10 ? "" : day((int) file[i] + 1);
        }
        IDataTable se = RealTableFixture.of("SE").str("USUBJID", subjects).lng("SESEQ", seq)
                .str("SEENDTC", ends).build();
        assertEquals(List.of(), fired(se, EMPTY), "SESEQ=10 is the last element, not 9");
    }

    // ---------------------------------------------------------------- T4-T8: the order itself


    @Test
    void t4ANumericKeyOrdersByValueWhateverItsColumnType()
    {
        assertEquals(List.of(1, 2, 0), order(DataValueType.DOUBLE, 10.0, 1.5, 2.0),
                "DOUBLE 1.5, 2.0, 10.0");
        assertEquals(List.of(2, 1, 0), textOrder("10", "9", "1"),
                "a CHAR key of numeric text orders numerically too (BK1: CellOrder) - neither file"
                        + " order (0, 1, 2) nor text order (2, 0, 1)");
    }


    @Test
    void t4bAOneRowGroupIsLeftAsItIs()
    {
        assertEquals(List.of(0), textOrder("10"), "one row: nothing to order");
        assertEquals(List.of(), fired(chain(new long[]
        {
                1
        }, k -> k, k -> k + 5), NEXT), "a one-element group has no next record to miss");
    }


    @Test
    void t5BlankAndMissingCellsGoFirstTiedInFileOrder()
    {
        // " " is blank (O24 #2) - before, it sorted as " ", after "" and the missing
        assertEquals(List.of(0, 1, 2, 4, 3),
                order(DataValueType.STRING, " ", "", MissingValue.MIS, "2", "1"));
    }


    @Test
    void t6AMixedCharKeyStaysText()
    {
        assertEquals(List.of(2, 3, 1, 0), textOrder("X", "2", "1", "10"),
                "one non-number makes the key text: 1, 10, 2, X");
    }


    @Test
    void t7aAUtcOffsetIsAppliedFirst()
    {
        // 10:00+02:00 is 08:00Z, before 09:00Z - text put T09:00Z first
        assertEquals(List.of(1, 0), textOrder("2015-01-01T09:00Z", "2015-01-01T10:00+02:00"));
    }


    @Test
    void t7bAnOffsetDatetimeAcrossMidnightFollowsTheDate()
    {
        // 2015-01-01T23:00-02:00 is 2015-01-02T01:00Z, after the date 2015-01-02
        assertEquals(List.of(1, 0), textOrder("2015-01-01T23:00-02:00", "2015-01-02"));
    }


    @Test
    void t7cAnIntervalOrdersByItsStart()
    {
        assertEquals(List.of(1, 0), textOrder("2015-01-05", "2015-01/2015-03"));
    }


    @Test
    void t7dNonIsoTextSortsAfterEveryDatedCell()
    {
        // text put 2015-UN-UN between 2015-06-01 and 2016-01-01
        assertEquals(List.of(1, 2, 0), textOrder("2015-UN-UN", "2015-06-01", "2016-01-01"));
    }


    @Test
    void t7eAFourDigitNumberInAMixedKeyIsAYear()
    {
        // O22 (a): 2021 is placed on the calendar, before the other text: 2021, "10", "UNK"
        assertEquals(List.of(2, 1, 0), textOrder("UNK", "10", "2021"));
    }


    @Test
    void t7ControlPartialAndMaskedDatesKeepTheirTextOrder()
    {
        // no move: text already is O24's part order - 2012, 2012---15, 2012-01, 2012-01-01
        assertEquals(List.of(2, 1, 3, 0), textOrder("2012-01-01", "2012---15", "2012", "2012-01"));
    }


    @Test
    void t8AMixedGroupOfMoreThanThirtyTwoRowsHasOneOrderUnderEveryShuffle()
    {
        // the expected order of the present cells, ascending (CellOrder: dated part by part,
        // UTC first; then text)
        List<String> dated = new ArrayList<>(List.of("----06-15", "2012", "2012---15", "2012-01",
                "2012-01-01", "2012-01-01T09:30+02:00", "2012-01-01T08:00", "2012-02/2012-03",
                "2012-02-15", "2013"));
        for (int d = 1; d <= 12; d++)
        {
            dated.add(String.format(java.util.Locale.ROOT, "2016-01-%02d", d));
        }
        dated.addAll(List.of("2020-06-15", "2021", "2021-01-01T00:00Z"));
        List<String> text = List.of("10", "2015-UN-UN", "2020-02-30", "ABC", "UNK", "ZZZ");
        List<Object> blanks = List.of("", " ", "  ", MissingValue.MIS, MissingValue.MIS);
        List<Object> expected = new ArrayList<>(dated);
        expected.addAll(text);
        assertTrue(expected.size() + blanks.size() >= 32, "a group of 32 rows or more");
        Random random = new Random(20261009L);
        for (int shuffle = 0; shuffle < 20; shuffle++)
        {
            List<Object> cells = new ArrayList<>(expected);
            cells.addAll(blanks);
            Collections.shuffle(cells, random);
            List<Integer> order = order(DataValueType.STRING, cells.toArray());
            List<Object> prefix = new ArrayList<>();
            List<Object> rest = new ArrayList<>();
            for (int i = 0; i < order.size(); i++)
            {
                (i < blanks.size() ? prefix : rest).add(cells.get(order.get(i)));
            }
            final int s = shuffle;
            assertEquals(new HashSet<>(blanks), new HashSet<>(prefix),
                    () -> "shuffle " + s + ": the blank and missing cells first");
            assertEquals(expected, rest, () -> "shuffle " + s + ": the present cells in order");
            // tied in file order: the blanks keep the order the shuffle wrote them in
            List<Integer> blankRows = new ArrayList<>(order.subList(0, blanks.size()));
            List<Integer> sorted = new ArrayList<>(blankRows);
            Collections.sort(sorted);
            assertEquals(sorted, blankRows, () -> "shuffle " + s + ": ties keep file order");
        }
    }

    // ---------------------------------------------------------------- T9-T10: load surface


    @Test
    void t9NoNewFeatureDescTwoKeysOrNullsStayLoadErrors()
    {
        for (String call : List.of(NEXT, EMPTY))
        {
            for (String by : List.of("by=[desc(SESEQ)]", "by=[SESEQ, SESTDTC]",
                    "by=[asc(SESEQ, nulls=\"last\")]"))
            {
                String error = SequenceFunctionTest.loadError(call.replace("by=SESEQ", by));
                assertTrue(error.contains("is not supported for"), call + " " + by + ": " + error);
            }
        }
    }


    @Test
    void t10TheRetiredOrderingErrorNamesNoRulingHandle()
    {
        Set<String> seen = new HashSet<>();
        for (String call : List.of(NEXT, EMPTY))
        {
            String error = SequenceFunctionTest.loadError(call.replace("by=", "ordering="));
            assertTrue(error.contains("ordering= is retired"), error);
            assertTrue(error.contains("write by=SESEQ (the same one ascending key)"), error);
            assertFalse(RULING_HANDLE.matcher(error).find(), error);
            seen.add(error);
        }
        assertEquals(2, seen.size(), "one message per reader");
    }
}
