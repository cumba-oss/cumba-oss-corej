package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.BitSet;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Change #1 (Task EF) — the "positive-already-exists" negatives are spelled {@code
 * not <positive>(…)}. This verifies the spelling is <em>semantically</em> faithful: the native
 * verdict of {@code not <positive>(…)} must equal the retired negative operator's verdict
 * <strong>row-for-row, including missing and empty cells</strong> (the place a structural
 * complement could diverge). The expected BitSets are the legacy engine's, pinned before its
 * retirement (phase 7d, D121); the expressions are exactly what the retired converter emitted for
 * each negative operator-leaf.
 */
class Change1NegationParityTest
{

    private static BitSet bits(int... rows)
    {
        BitSet b = new BitSet();
        for (int r : rows)
        {
            b.set(r);
        }
        return b;
    }


    private static BitSet eval(String source, IDataTable t)
    {
        EvaluationContext c = EvaluationContext.builder().table(t).build();
        return NativeExprEvaluator.evaluate(CheckExpressionParser.parse(source), c);
    }


    @Test
    void nonEmptyParity()
    {
        // rows: present-nonempty, empty "", missing -> non_empty fires only row 0.
        IDataTable t = MockTable.of().col("X", "x", "", null).build();
        assertEquals(bits(0), eval("not empty(X)", t));
    }


    @Test
    void isNotIntegerParity()
    {
        // rows: integer, non-integer, empty, missing -> is_not_integer fires rows 1,2,3.
        IDataTable t = MockTable.of().col("X", "5", "abc", "", null).build();
        assertEquals(bits(1, 2, 3), eval("not is_integer(X)", t));
    }


    @Test
    void doesNotContainParity()
    {
        // needle "AB": rows containing / not / empty / missing -> does_not_contain fires 1,2,3.
        IDataTable t = MockTable.of().col("X", "xABx", "xyz", "", null).build();
        assertEquals(bits(1, 2, 3), eval("not contains(X, \"AB\")", t));
    }


    @Test
    void isNotUniqueSetParity()
    {
        // single-column duplicate check: "S1" duplicated -> fires rows 0,1; "S2" unique.
        IDataTable t = MockTable.of().col("X", "S1", "S1", "S2").build();
        assertEquals(bits(0, 1), eval("not is_unique_set([X])", t));
    }


    @Test
    void isNotUniqueSetWithKeysParity()
    {
        // [X, K] tuple uniqueness: (1,S1) duplicated -> rows 0,1.
        IDataTable t = MockTable.of().col("X", "1", "1", "2").col("K", "S1", "S1", "S1").build();
        assertEquals(bits(0, 1), eval("not is_unique_set([X, K])", t));
    }

}
