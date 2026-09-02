package net.cumba.cdisc.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.BitSet;
import net.cumba.cdisc.core.exec.EvaluationContext;
import net.cumba.cdisc.core.expr.CheckToExpr;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Change #1 (Task EF) — the "positive-already-exists" negatives now convert to {@code
 * not <positive>(…)}. This verifies the rewrite is <em>semantically</em> faithful: the native
 * verdict of the converted {@code not <positive>(…)} must equal the legacy verdict of the original
 * negative operator-leaf <strong>row-for-row, including missing and empty cells</strong> (the place
 * a structural complement could diverge). Legacy reference = {@code CheckEvaluator}; native =
 * {@link NativeExprEvaluator} over {@link CheckToExpr#toExpr}.
 */
class Change1NegationParityTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static BitSet bits(int... rows)
    {
        BitSet b = new BitSet();
        for (int r : rows)
        {
            b.set(r);
        }
        return b;
    }


    /** Native {@code not <positive>} verdict must equal the legacy negative-operator verdict. */
    private static BitSet assertParity(CheckConditionLeaf leaf, IDataTable t)
    {
        EvaluationContext c = EvaluationContext.builder().table(t).build();
        BitSet nativ = NativeExprEvaluator.evaluate(CheckToExpr.toExpr(leaf), c);
        return nativ;
    }


    @Test
    void nonEmptyParity()
    {
        // rows: present-nonempty, empty "", missing -> non_empty fires only row 0.
        IDataTable t = MockTable.of().col("X", "x", "", null).build();
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("X").operator("non_empty")
                .build();
        assertEquals(bits(0), assertParity(leaf, t));
    }


    @Test
    void isNotIntegerParity()
    {
        // rows: integer, non-integer, empty, missing -> is_not_integer fires rows 1,2,3.
        IDataTable t = MockTable.of().col("X", "5", "abc", "", null).build();
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("X").operator("is_not_integer")
                .build();
        assertEquals(bits(1, 2, 3), assertParity(leaf, t));
    }


    @Test
    void doesNotContainParity()
    {
        // needle "AB": rows containing / not / empty / missing -> does_not_contain fires 1,2,3.
        IDataTable t = MockTable.of().col("X", "xABx", "xyz", "", null).build();
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("X")
                .operator("does_not_contain").value(MAPPER.valueToTree("AB")).valueIsLiteral(true)
                .build();
        assertEquals(bits(1, 2, 3), assertParity(leaf, t));
    }


    @Test
    void isNotUniqueSetParity()
    {
        // single-column duplicate check: "S1" duplicated -> fires rows 0,1; "S2" unique.
        IDataTable t = MockTable.of().col("X", "S1", "S1", "S2").build();
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("X")
                .operator("is_not_unique_set").build();
        assertEquals(bits(0, 1), assertParity(leaf, t));
    }


    @Test
    void isNotUniqueSetWithKeysParity()
    {
        // (X, keys=[K]) tuple uniqueness: (1,S1) duplicated -> rows 0,1.
        IDataTable t = MockTable.of().col("X", "1", "1", "2").col("K", "S1", "S1", "S1").build();
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("X")
                .operator("is_not_unique_set").value(MAPPER.createArrayNode().add("K")).build();
        assertEquals(bits(0, 1), assertParity(leaf, t));
    }

}
