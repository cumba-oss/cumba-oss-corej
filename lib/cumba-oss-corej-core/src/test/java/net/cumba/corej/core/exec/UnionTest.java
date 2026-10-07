package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import net.cumba.corej.core.expr.RuleDefinitionException;
import net.cumba.corej.core.expr.eval.ComputedVector;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-union-function} §3.1 (runbook W1a U1): {@link Union#evaluate} — order of first
 * occurrence, duplicates within and across operands, missing identities, structural tuples, empty
 * and zero-row operands, and the two evaluation-time refusals (a scalar operand, a mixed shape).
 */
class UnionTest
{

    private static EvalRun run(int rows)
    {
        IDataTable table = rows == 0 ? MockTable.of().name("DM").col("STUDYID").build()
                : MockTable.of().name("DM").col("STUDYID", "S1").build();
        return EvalRun.fullRange(EvaluationContext.builder().table(table).build());
    }


    private static List<Object> union(Object... operands)
    {
        Vector[] vectors = new Vector[operands.length];
        for (int i = 0; i < operands.length; i++)
        {
            vectors[i] = ConstVector.of(operands[i]);
        }
        Vector answer = Union.evaluate(run(1), Arrays.asList(vectors));
        return list(answer);
    }


    @SuppressWarnings("unchecked")
    private static List<Object> list(Vector answer)
    {
        return (List<Object>) assertInstanceOf(ConstVector.class, answer).value();
    }


    @Test
    void theUnionKeepsTheOrderOfFirstOccurrence()
    {
        assertEquals(List.of("A", "B", "C", "D"), union(List.of("A", "B"), List.of("C", "B", "D")));
        assertEquals(List.of("B", "A"), union(List.of("B"), List.of("A", "B")),
                "the first operand's order leads");
        assertEquals(List.of("A", "B", "C", "E"),
                union(List.of("A"), List.of("B", "A"), List.of("C"), List.of("E", "B")),
                "a collector form with four operands");
    }


    @Test
    void duplicatesWithinAndAcrossOperandsOccurOnce()
    {
        assertEquals(List.of("A", "B"), union(List.of("A", "A", "B", "A"), List.of("B", "B")));
        assertEquals(List.of("A"), union(List.of("A"), List.of("A")));
    }


    @Test
    void emptyAndAbsentOperandsContributeNothing()
    {
        assertEquals(List.of(), union(List.of(), List.of()));
        assertEquals(List.of("A"), union(List.of(), List.of("A")));
        assertEquals(List.of("A"), union(List.of("A"), null),
                "an operand binding that evaluates to nothing adds nothing");
    }


    @Test
    void aZeroRowNonConstantOperandReadsAsTheEmptyList()
    {
        EvalRun empty = run(0);
        assertEquals(0, empty.rowCount());
        Vector perRow = new ComputedVector(0, DataValueType.STRING, _ -> List.of("X"));
        assertEquals(List.of("A"),
                list(Union.evaluate(empty, List.of(ConstVector.of(List.of("A")), perRow))));
    }


    @Test
    void aMissingKeepsItsIdentityAndATupleComparesStructurally()
    {
        // D34 #5-2 / I6: the missing identities and the present "." are three distinct members;
        // a tuple is a member by its components, never by its rendering
        assertEquals(List.of("X", MissingValue.MIS_A, ".", MissingValue.MIS_B),
                union(List.of("X", MissingValue.MIS_A),
                        List.of(".", MissingValue.MIS_B, MissingValue.MIS_A)));
        List<Object> ab = List.of("A", "B");
        assertEquals(List.of(List.of("A", "B"), List.of("C", "D")),
                union(List.of(ab), List.of(List.of("A", "B"), List.of("C", "D"))));
        assertEquals(List.of(List.of("A", "B"), MissingValue.MIS),
                union(List.of(ab, MissingValue.MIS), List.of(List.of("A", "B"))),
                "a missing is a member of a tuple set too");
    }


    @Test
    void aScalarOperandIsARuleDefinitionError()
    {
        RuleDefinitionException e = assertThrows(RuleDefinitionException.class,
                () -> union(List.of("A"), "SOLO"));
        assertTrue(e.getMessage().contains("operand 2 is not a list"), e.getMessage());
        assertTrue(e.getMessage().contains("[\"SOLO\"]"), "the remedy names the singleton");
        assertThrows(RuleDefinitionException.class, () -> union(3.0, List.of("A")));
    }


    @Test
    void mixedShapesAreARuleDefinitionError()
    {
        assertThrows(RuleDefinitionException.class,
                () -> union(List.of("A"), List.of(List.of("A", "B"))), "text ∪ tuple");
        assertThrows(RuleDefinitionException.class,
                () -> union(List.of(List.of("A")), List.of(List.of("A", "B"))),
                "tuples of different arity");
        assertThrows(IllegalStateException.class, () -> union(new String[]
        {
                "A"
        }, List.of("B")), "an array is not a list value (NNL §1)");
    }
}
