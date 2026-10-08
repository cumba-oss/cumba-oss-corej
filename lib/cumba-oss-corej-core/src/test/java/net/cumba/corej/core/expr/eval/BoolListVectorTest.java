package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import net.cumba.corej.core.exec.JoinedRowFold.Quantifier;
import net.cumba.corej.core.expr.RuleDefinitionException;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * {@link BoolListVector} ({@code PLAN-elementwise-boolean-predicates} §3.3, owner Q2; runbook W1a
 * EB1): the three forms, each quantifier over empty / K / mixed rows, the empty conventions
 * ({@code any([])} false, {@code all([])} true, {@code none([])} true), the singleton row of the
 * flattened form (P6), the broadcast form over a reader's run (P8), and the diagnostic rendering.
 */
class BoolListVectorTest
{

    private static BitSet bits(int... set)
    {
        BitSet out = new BitSet();
        for (int i : set)
        {
            out.set(i);
        }
        return out;
    }

    // ------------------------------------------------------------------ the columns form


    @Test
    void theColumnsFormFoldsColumnWide()
    {
        // rows: 0 → [T, T], 1 → [T, F], 2 → [F, F]
        BoolListVector v = BoolListVector.ofColumns(3, List.of(bits(0, 1), bits(0)));
        assertEquals(bits(0, 1), v.reduce(Quantifier.ANY));
        assertEquals(bits(0), v.reduce(Quantifier.ALL));
        assertEquals(bits(2), v.reduce(Quantifier.NONE));
        assertEquals(List.of(true, false), v.value(1).resolved());
        assertEquals(DataValueType.BOOLEAN, v.declaredType());
    }


    @Test
    void theColumnsFormWithNoColumnAnswersTheEmptyConventions()
    {
        BoolListVector v = BoolListVector.ofColumns(2, List.of());
        assertEquals(bits(), v.reduce(Quantifier.ANY), "any([]) is false");
        assertEquals(bits(0, 1), v.reduce(Quantifier.ALL), "all([]) is true");
        assertEquals(bits(0, 1), v.reduce(Quantifier.NONE), "none([]) is true");
        assertEquals(List.of(), v.value(0).resolved());
    }


    @Test
    void theReductionNeverHandsOutAnInputSet()
    {
        BitSet column = bits(0);
        BoolListVector v = BoolListVector.ofColumns(1, List.of(column));
        BitSet any = v.reduce(Quantifier.ANY);
        assertNotSame(column, any);
        any.clear();
        assertTrue(column.get(0), "the input set is read only");
    }

    // ------------------------------------------------------------------ the flattened form


    @Test
    void theFlattenedFormFoldsEachRowsRangeAndAnEmptyRowAnswersTheConventions()
    {
        // row 0: [T, F] (elements 0-1); row 1: [] ; row 2: [T] (element 2, a singleton); row 3:
        // [F, F] (elements 3-4); row 4: [T, T] (elements 5-6)
        int[] offsets =
        {
                0, 2, 2, 3, 5, 7
        };
        BoolListVector v = BoolListVector.flattened(5, offsets, bits(0, 2, 5, 6));
        assertEquals(bits(0, 2, 4), v.reduce(Quantifier.ANY));
        assertEquals(bits(1, 2, 4), v.reduce(Quantifier.ALL), "the empty row 1 is all([])");
        assertEquals(bits(1, 3), v.reduce(Quantifier.NONE), "the empty row 1 is none([])");
        assertEquals(List.of(true, false), v.value(0).resolved());
        assertEquals(List.of(), v.value(1).resolved());
        assertEquals(List.of(true), v.value(2).resolved());
    }


    @Test
    void theFlattenedFormChecksItsOffsets()
    {
        assertThrows(IllegalArgumentException.class, () -> BoolListVector.flattened(2, new int[]
        {
                0, 1
        }, bits()));
    }

    // ------------------------------------------------------------------ the broadcast form


    @Test
    void theBroadcastFormIsOneListForEveryRow()
    {
        BoolListVector v = BoolListVector.broadcast(3, 2, bits(0));
        assertTrue(v.isBroadcast());
        assertEquals(bits(0, 1, 2), v.reduce(Quantifier.ANY));
        assertEquals(bits(), v.reduce(Quantifier.ALL));
        assertEquals(bits(), v.reduce(Quantifier.NONE));
        assertEquals(List.of(true, false), v.value(0).resolved());
        assertEquals(v.value(0), v.value(2), "one rendering, memoised");
        // P8: a dataset-level binding is evaluated over the whole table, its reader may run a
        // range — the broadcast form answers the reader's rows
        assertEquals(bits(0, 1, 2, 3, 4), v.reduce(Quantifier.ANY, 5));
        assertEquals(bits(), v.reduce(Quantifier.ALL, 5));
        BoolListVector empty = BoolListVector.broadcast(2, 0, bits());
        assertEquals(bits(), empty.reduce(Quantifier.ANY), "any([]) is false");
        assertEquals(bits(0, 1), empty.reduce(Quantifier.ALL), "all([]) is true");
        assertEquals(bits(0, 1), empty.reduce(Quantifier.NONE), "none([]) is true");
        BoolListVector full = BoolListVector.broadcast(2, 2, bits(0, 1));
        assertEquals(bits(0, 1), full.reduce(Quantifier.ALL));
        assertFalse(BoolListVector.ofColumns(1, List.of()).isBroadcast());
    }


    @Test
    void aPerRowFormSpansExactlyItsRows()
    {
        BoolListVector v = BoolListVector.ofColumns(3, List.of(bits(0)));
        // W1a fold (R5 review B-LOW-1): a mismatch is the rule's ERROR, never an engine crash
        RuleDefinitionException e = assertThrows(RuleDefinitionException.class,
                () -> v.reduce(Quantifier.ANY, 2));
        assertTrue(e.getMessage().contains("cannot answer a run of 2 rows"), e.getMessage());
        assertEquals(3, v.rowCount());
    }
}
