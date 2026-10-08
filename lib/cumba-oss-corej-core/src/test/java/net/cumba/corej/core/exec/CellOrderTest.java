package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.cumba.corej.core.expr.eval.Primitives;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * Runbook W1b RO, coordinator D11 / D19: {@link CellOrder} — the ONE reusable ordering comparator
 * ({@code sequence}'s sort keys, {@code is_increasing}; {@code is_sorted_by} adopts it in W2 SB2) —
 * is a <b>total order</b>: the engine's ISO relations are not ({@code ScalarSemantics.compareIso}
 * truncates to the shorter precision, so {@code 2020-01-15} equals both {@code T08:00} and
 * {@code T10:00} — not transitive — and the hull comparison leaves partial vs complete unordered),
 * and {@code TimSort} throws on a group of 32+ rows under such a comparator. Here ISO cells are
 * ordered by (lower bound, upper bound, text), numbers numerically, the rest as text, a missing
 * last — one class after another, transitive and antisymmetric over every triple.
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


    private static void assertTotalOrder(List<Object> cells)
    {
        int n = cells.size();
        for (int i = 0; i < n; i++)
        {
            assertEquals(0, CellOrder.compare(cells.get(i), cells.get(i)), "reflexive " + i);
            for (int j = 0; j < n; j++)
            {
                int ij = CellOrder.compare(cells.get(i), cells.get(j));
                int ji = CellOrder.compare(cells.get(j), cells.get(i));
                assertEquals(Integer.signum(ij), -Integer.signum(ji),
                        "antisymmetric: " + cells.get(i) + " vs " + cells.get(j));
                for (int k = 0; k < n; k++)
                {
                    int jk = CellOrder.compare(cells.get(j), cells.get(k));
                    int ik = CellOrder.compare(cells.get(i), cells.get(k));
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
    void aFortyRowMixedPrecisionGroupIsATotalOrderAndSortsWithoutThrowing()
    {
        List<Object> cells = mixedPrecision();
        assertTotalOrder(cells);
        // TimSort over the full group in several shuffles — the "Comparison method violates its
        // general contract" failure mode of a non-total comparator (D19)
        Random random = new Random(19L);
        for (int round = 0; round < 25; round++)
        {
            List<Object> shuffled = new ArrayList<>(cells);
            Collections.shuffle(shuffled, random);
            shuffled.sort(CellOrder::compare);
            for (int i = 1; i < shuffled.size(); i++)
            {
                assertTrue(CellOrder.compare(shuffled.get(i - 1), shuffled.get(i)) <= 0,
                        shuffled.get(i - 1) + " before " + shuffled.get(i));
            }
        }
    }


    @Test
    void isoCellsOrderByLowerBoundThenUpperBoundThenText()
    {
        assertTrue(CellOrder.compare("2020-01", "2020-01-15") < 0, "a partial before the complete");
        assertTrue(CellOrder.compare("2020-01-15", "2020-01-15T08:00") < 0,
                "a date (hull from 00:00) before its datetime");
        assertTrue(CellOrder.compare("2020-01-15T08:00", "2020-01-15T10:00") < 0,
                "two datetimes by instant — never equal through the date they share");
        assertTrue(CellOrder.compare("2020-01-15T08:00", "2020-01-15") > 0);
        assertTrue(CellOrder.compare("2020-01-15", "2020-01-15/2020-01-20") < 0,
                "same lower bound: the shorter upper bound first");
        assertTrue(CellOrder.compare("2020-01-15T08:00", "2020-01-15T08:00:00") > 0,
                "same lower bound at two precisions: the wider hull (upper 08:00:59) after the"
                        + " point (08:00:00), never equal");
        assertEquals(0, CellOrder.compare("2020-01-15", "2020-01-15"));
        assertTrue(CellOrder.compare("2020-01-15T08:00:00+02:00", "2020-01-15T08:00:00Z") < 0,
                "an offset is applied instant-preserving");
        assertTrue(CellOrder.compare("2019", "2020-01-01") < 0);
        assertTrue(CellOrder.compare("UNK", "2020-01-15") > 0,
                "an unpositionable text after dates");
        assertTrue(CellOrder.compare("2020-13-45", "2020-01-15") > 0, "an invalid date is text");
    }


    @Test
    void numbersBeforeDatesBeforeTextBeforeMissing()
    {
        assertTrue(CellOrder.compare("9", "10") < 0, "numeric text orders numerically");
        assertTrue(CellOrder.compare(9.0, "10") < 0, "a numeric cell and numeric text agree");
        assertTrue(CellOrder.compare("2", "2020-01-15") < 0, "a number before a date");
        assertTrue(CellOrder.compare("2020-01-15", "b") < 0, "a date before plain text");
        assertTrue(CellOrder.compare("a", "b") < 0);
        assertTrue(CellOrder.compare("b", "") < 0, "a blank is last, with the missing");
        assertTrue(CellOrder.compare("b", new Primitives.MissingMember(MissingValue.MIS)) < 0);
        assertTrue(CellOrder.compare("", new Primitives.MissingMember(MissingValue.MIS)) < 0,
                "a blank before a missing marker");
        assertEquals(0, CellOrder.compare(new Primitives.MissingMember(MissingValue.MIS),
                new Primitives.MissingMember(MissingValue.MIS)));
        assertTrue(CellOrder.compare("1.0", "1") > 0, "equal numbers tie-break by text (total)");
        assertTotalOrder(new ArrayList<>(List.of("9", "10", 9.0, 10L, "2", "2020-01-15", "b", "a",
                "", new Primitives.MissingMember(MissingValue.MIS),
                new Primitives.MissingMember(MissingValue.NA), "1.0", "1", "-0", "0", "1e2", "100",
                "x1", "2020")));
    }
}
