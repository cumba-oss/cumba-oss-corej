package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.cumba.corej.core.expr.eval.Primitives;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * Runbook W1b RO, coordinator D11 / D19, owner O22: {@link CellOrder} — the ONE reusable ordering
 * ({@code sequence}'s sort keys, {@code is_increasing}; {@code is_sorted_by} adopts it in W2 SB2) —
 * classifies a sort key over its cells (numeric iff every present cell is a number, else ISO then
 * text, a four-digit cell a year) and is a <b>total order</b>: the engine's ISO relations are not
 * ({@code ScalarSemantics.compareIso} truncates to the shorter precision, so {@code 2020-01-15}
 * equals both {@code T08:00} and {@code T10:00} — not transitive — and the hull comparison leaves
 * partial vs complete unordered), and {@code TimSort} throws on a group of 32+ rows under such a
 * comparator. Here ISO cells are ordered by (lower bound, upper bound, text), the rest as text, a
 * blank and a missing last — one class after another, transitive and antisymmetric over every
 * triple.
 */
class CellOrderTest
{

    /** 40 ISO cells of mixed precision, with masks, intervals, offsets, duplicates and blanks. */
    private static List<Object> mixedPrecision()
    {
        List<Object> cells = new ArrayList<>(List.of("2020-01-15", "2020-01-15T08:00",
                "2020-01-15T10:00", "2020-01", "2020", "2020-01-15T08:00:00", "2020-01-14",
                "2020-01-16", "2020-01-15T23:59:59", "2020-01-15T00:00", "2019-12-31", "2020-02",
                "2020-01-15/2020-01-20", "2020-01-10/2020-01-15", "2020-01--", "2020---15",
                "2020-01-15T08:00:00+02:00", "2020-01-15T08:00:00Z", "2021", "2020-01-15T07:59",
                "2020-01-15T08", "2020-01-1", "UNK", "", "2020-01-15", "2020-01", "NOT DONE",
                "2020-13-45", "2020-01-15T08:00", "2020-06-30", "2020-01-15T12:00:00",
                "2020-01-15T12", "2020-01-15T12:00", "1999-12-31T23:59:59", "2020-01-15/2020-01-15",
                "2020-01/2020-02", "2020-01-15T08:00:01", "2020-01-15T07", "2020-01-02",
                "2020-01-03"));
        assertEquals(40, cells.size(), "the 40-row group");
        return cells;
    }


    /** {@code a} against {@code b} in the key made of {@code cells} plus the two. */
    private static int compare(List<?> cells, Object a, Object b)
    {
        List<Object> all = new ArrayList<>(cells);
        all.add(a);
        all.add(b);
        return CellOrder.key(all).compare(all.size() - 2, all.size() - 1);
    }


    /** {@code a} against {@code b} in the key made of the two alone. */
    private static int compare(Object a, Object b)
    {
        return compare(List.of(), a, b);
    }


    private static void assertTotalOrder(List<Object> cells)
    {
        CellOrder.Key key = CellOrder.key(cells);
        int n = cells.size();
        for (int i = 0; i < n; i++)
        {
            assertEquals(0, key.compare(i, i), "reflexive " + i);
            for (int j = 0; j < n; j++)
            {
                int ij = key.compare(i, j);
                int ji = key.compare(j, i);
                assertEquals(Integer.signum(ij), -Integer.signum(ji),
                        "antisymmetric: " + cells.get(i) + " vs " + cells.get(j));
                for (int k = 0; k < n; k++)
                {
                    int jk = key.compare(j, k);
                    int ik = key.compare(i, k);
                    if (ij <= 0 && jk <= 0)
                    {
                        assertTrue(ik <= 0, "transitive: " + cells.get(i) + " <= " + cells.get(j)
                                + " <= " + cells.get(k) + " but not <= " + cells.get(k));
                    }
                    if (ij == 0 && jk == 0)
                    {
                        assertEquals(0, ik, "equal cells stay equal through a third");
                    }
                }
            }
        }
    }


    @Test
    void aFortyRowMixedPrecisionGroupIsATotalOrderAndSortsStably()
    {
        List<Object> cells = mixedPrecision();
        assertTotalOrder(cells);
        // the stable merge sort of the decorated key (R5 A-M2) over shuffles of the group — the
        // "Comparison method violates its general contract" failure mode of a non-total order
        // (D19) is TimSort's; the order must hold whatever the input order
        Random random = new Random(19L);
        for (int round = 0; round < 25; round++)
        {
            List<Object> shuffled = new ArrayList<>(cells);
            Collections.shuffle(shuffled, random);
            CellOrder.Key key = CellOrder.key(shuffled);
            int[] positions = new int[shuffled.size()];
            for (int i = 0; i < positions.length; i++)
            {
                positions[i] = i;
            }
            SortKeys.stableSort(positions, key::compare);
            for (int i = 1; i < positions.length; i++)
            {
                int c = key.compare(positions[i - 1], positions[i]);
                assertTrue(c < 0 || (c == 0 && positions[i - 1] < positions[i]),
                        shuffled.get(positions[i - 1]) + " before " + shuffled.get(positions[i])
                                + " (a tie keeps the given order)");
            }
        }
    }


    @Test
    void isoCellsOrderByLowerBoundThenUpperBoundThenText()
    {
        assertTrue(compare("2020-01", "2020-01-15") < 0, "a partial before the complete");
        assertTrue(compare("2020-01-15", "2020-01-15T08:00") < 0,
                "a date (hull from 00:00) before its datetime");
        assertTrue(compare("2020-01-15T08:00", "2020-01-15T10:00") < 0,
                "two datetimes by instant — never equal through the date they share");
        assertTrue(compare("2020-01-15T08:00", "2020-01-15") > 0);
        assertTrue(compare("2020-01-15", "2020-01-15/2020-01-20") < 0,
                "same lower bound: the shorter upper bound first");
        assertTrue(compare("2020-01-15T08:00", "2020-01-15T08:00:00") > 0,
                "same lower bound at two precisions: the wider hull (upper 08:00:59) after the"
                        + " point (08:00:00), never equal");
        assertEquals(0, compare("2020-01-15", "2020-01-15"));
        assertTrue(compare("2020-01-15T08:00:00+02:00", "2020-01-15T08:00:00Z") < 0,
                "an offset is applied instant-preserving");
        assertTrue(compare("UNK", "2020-01-15") > 0, "an unpositionable text after dates");
        assertTrue(compare("2020-13-45", "2020-01-15") > 0, "an invalid date is text");
    }


    @Test
    void aYearAmongDatesIsAYearAndAnAllNumberKeyIsNumeric()
    {
        // R5 A-M1, owner O22 (a): the class is the KEY's — judged per cell, "2021" was a number
        // and sorted before every date (the cannot-fail "2019" < "2020-01-01" pinned nothing)
        assertTrue(compare("2021", "2020-06-15") > 0, "the year 2021 after 2020-06-15");
        assertTrue(compare("2020", "2020-06-15") < 0, "the year 2020 spans and starts first");
        assertTrue(compare("2020-06-15", "2021") < 0);
        assertFalse(CellOrder.key(List.of("2021", "2020-06-15")).numeric());
        // an all-number key is numeric — the years too, and "9" before "10"
        CellOrder.Key numbers = CellOrder.key(List.of("9", "10", "2021", "2020"));
        assertTrue(numbers.numeric());
        assertTrue(numbers.compare(0, 1) < 0, "9 before 10");
        assertTrue(numbers.compare(3, 2) < 0, "2020 before 2021 as numbers");
        // a number in a mixed key is text (D11: numeric iff EVERY present cell is a number)
        assertTrue(compare(List.of("UNK"), "9", "10") > 0, "\"9\" after \"10\" as text");
        assertTrue(compare(List.of("UNK"), "2", "UNK") < 0);
        // blanks and missings do not make a key non-numeric
        CellOrder.Key withGaps = CellOrder.key(java.util.Arrays.asList("10", "", null, "9"));
        assertTrue(withGaps.numeric());
        assertTrue(withGaps.compare(3, 0) < 0);
    }


    @Test
    void numbersThenTextThenBlankThenMissing()
    {
        assertTrue(compare("9", "10") < 0, "numeric text orders numerically");
        assertTrue(compare(9.0, "10") < 0, "a numeric cell and numeric text agree");
        assertTrue(compare("2020-01-15", "b") < 0, "a date before plain text");
        assertTrue(compare("a", "b") < 0);
        assertTrue(compare("b", "") < 0, "a blank is last, with the missing");
        assertTrue(compare("b", new Primitives.MissingMember(MissingValue.MIS)) < 0);
        assertTrue(compare("", new Primitives.MissingMember(MissingValue.MIS)) < 0,
                "a blank before a missing marker");
        assertEquals(0, compare(new Primitives.MissingMember(MissingValue.MIS),
                new Primitives.MissingMember(MissingValue.MIS)));
        assertTrue(compare("1.0", "1") > 0, "equal numbers tie-break by text (total)");
        assertTotalOrder(new ArrayList<>(List.of("9", "10", 9.0, 10L, "2", "2020-01-15", "b", "a",
                "", new Primitives.MissingMember(MissingValue.MIS),
                new Primitives.MissingMember(MissingValue.NA), "1.0", "1", "-0", "0", "1e2", "100",
                "x1", "2020")));
        assertTotalOrder(new ArrayList<>(List.of("9", "10", 9.0, 10L, "2", "",
                new Primitives.MissingMember(MissingValue.MIS),
                new Primitives.MissingMember(MissingValue.NA), "1.0", "1", "-0", "0", "1e2")));
    }
}
