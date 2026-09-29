package net.cumba.corej.core.expr.eval;

import static net.cumba.corej.core.expr.eval.VectorLayerTest.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * Register {@code NNL §1}, {@code PLAN-no-null-list-elements} §3(c): the per-row list producers are
 * <b>not</b> guarded per row — a {@code GroupedResult} becomes a per-row {@code ComputedVector},
 * and rescanning a list on every row is the cost §3 rejected — so their null-freedom is a property
 * of their construction, and this test is what pins it (review round 1, L-3: the deletion of plan
 * row 15's {@code caseFold} null test had claimed the guard covers this channel; it does not).
 *
 * <p>
 * Each producer is driven over every input shape that could tempt it to emit a {@code null}: a
 * present blank, {@code MIS}, {@code .A}, an empty token, an empty delimiter. Each assertion also
 * counts the list rows it inspected, so a producer that stopped emitting lists cannot pass
 * vacuously. Mockito-free: {@link MissingCellTables} is a real table that can hold a {@code .A}.
 * </p>
 *
 * <p>
 * {@code split_by} of a missing cell is that missing — the input's own cell, a <b>scalar</b>, so
 * there is no list and hence no element ({@code PLAN-missing-identity-nonstring-functions}, closing
 * the missing half of {@code FINDINGS-unowned-residuals} I1); a present blank keeps its answer, the
 * computed {@code MIS}. The missing rows below are asserted to be <b>not a list</b> and to carry
 * their identity.
 * </p>
 */
class PerRowListProducersNullFreeTest
{

    private static Vector value(String name, int rowCount, Vector... args)
    {
        List<Vector> list = new ArrayList<>(List.of(args));
        FunctionDescriptor d = FunctionRegistry.descriptor(name);
        if (d != null && d.maxArity() != Integer.MAX_VALUE)
        {
            while (list.size() < d.parameters().size())
            {
                list.add(null);
            }
        }
        return (Vector) FunctionRegistryCalls.resolve(name).apply(EvalRun.ofRowCount(rowCount),
                list);
    }


    /**
     * Asserts that no row's list holds a {@code null} element.
     *
     * @return the number of rows whose value was a list — the caller pins it, so the check is not
     *         vacuous
     */
    private static int assertNoNullElement(String producer, Vector v, int rowCount)
    {
        int lists = 0;
        for (int r = 0; r < rowCount; r++)
        {
            if (v.value(r).resolved() instanceof Collection<?> col)
            {
                lists++;
                int i = 0;
                for (Object element : col)
                {
                    assertFalse(element == null,
                            producer + " row " + r + " holds a null element at index " + i);
                    i++;
                }
            }
        }
        return lists;
    }


    @Test
    void splitByEmitsNoNullElement()
    {
        IDataTable t = MissingCellTables.strings("X", "A,,B", "A,", "", ",", "AB", MissingValue.MIS,
                MissingValue.MIS_A);
        Vector comma = value("split_by", 7, col(t, "X"), ConstVector.of(","));
        assertEquals(4, assertNoNullElement("split_by(X, \",\")", comma, 7),
                "the four non-blank rows are lists; the blank (Vector.isMissing's F3 fold) and the"
                        + " two missing rows are not");
        assertEquals(List.of("A", "", "B"), comma.value(0).resolved(),
                "an empty token is a present \"\", never a null");
        assertEquals(List.of("", ""), comma.value(3).resolved(), "a lone delimiter: two \"\"");

        Vector empty = value("split_by", 7, col(t, "X"), ConstVector.of(""));
        assertEquals(4, assertNoNullElement("split_by(X, \"\")", empty, 7),
                "an empty delimiter answers the singleton [x] for each present row");
        for (int r : new int[]
        {
                2, 5, 6
        })
        {
            assertFalse(comma.value(r).resolved() instanceof Collection<?>,
                    "row " + r + ": a blank or missing x is the scalar channel, not a list");
        }
        assertSame(MissingValue.MIS, comma.value(2).missing(),
                "split_by(\"\", \",\") keeps its answer, the computed MIS");
        assertSame(MissingValue.MIS, comma.value(5).missing(), "split_by(MIS, \",\") is MIS");
        assertSame(MissingValue.MIS_A, comma.value(6).missing(),
                "split_by(.A, \",\") is .A — the input's own cell, not a fresh MIS (D85c)");
    }


    @Test
    void tupleEmitsNoNullElement()
    {
        IDataTable t = MissingCellTables.of("T").str("A", "a", "", MissingValue.MIS, "d")
                .str("B", MissingValue.MIS_A, "b", "", "").build();
        Vector tuples = value("tuple", 4, col(t, "A"), col(t, "B"));
        assertEquals(4, assertNoNullElement("tuple(A, B)", tuples, 4), "every row is a tuple");
        assertEquals(List.of("a", new Primitives.MissingMember(MissingValue.MIS_A)),
                tuples.value(0).resolved(), "a missing component is its MissingMember");
        assertEquals(List.of(new Primitives.MissingMember(MissingValue.MIS), ""),
                tuples.value(2).resolved(), "a present blank component is \"\"");
    }


    @Test
    void theListFormOfUpperAndLowerEmitsNoNullElement()
    {
        Vector lists = new ComputedVector(2, DataValueType.STRING,
                row -> row == 0 ? List.of("a", MissingValue.MIS, "") : List.of(MissingValue.MIS_A));
        for (String fn : List.of("upper", "lower"))
        {
            Vector folded = value(fn, 2, lists);
            assertEquals(2, assertNoNullElement(fn, folded, 2), fn + ": both rows stay lists");
            List<?> row0 = assertInstanceOf(List.class, folded.value(0).resolved());
            assertEquals(MissingValue.MIS, row0.get(1),
                    fn + ": the missing element is carried through as itself (D36)");
            assertEquals("", row0.get(2), fn + ": a present \"\" stays \"\"");
        }
    }

}
