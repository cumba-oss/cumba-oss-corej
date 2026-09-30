package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import net.cumba.corej.core.expr.eval.ComputedVector;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * {@code minus} over a zero-row table (combined review W4 M2): a non-constant list operand has no
 * row 0 there, and reading it must not throw {@code ArrayIndexOutOfBoundsException}.
 */
class MinusTest
{

    /**
     * The SEND-0012 shape {@code minus($required_variables, subtract=$dataset_variables_upper)}
     * with {@code $dataset_variables_upper = upper($dataset_variables)} on a zero-row dataset:
     * {@code upper} over a broadcast list answers a per-row {@link ComputedVector}, which has no
     * row 0 over zero rows. Pre-fix {@code Minus.listOperand} called {@code value(0)} on it and
     * threw {@code ArrayIndexOutOfBoundsException} (a rule ERROR); post-fix the operand reads as
     * the empty list, the answer {@code BindingValue}'s hand-over gives the same operand.
     */
    @Test
    void aZeroRowNonConstantOperandReadsAsTheEmptyList()
    {
        IDataTable empty = MockTable.of().col("STUDYID").col("DOMAIN").name("DM").build();
        EvalRun run = EvalRun.fullRange(EvaluationContext.builder().table(empty).build());
        assertEquals(0, run.rowCount(), "the fixture is a zero-row dataset");
        // The shape the finding named: `upper($dataset_variables)` used to be a per-row vector
        // over a broadcast list (it is a ConstVector since the same review round —
        // ListFunctionsTest pins that); the guard is pinned on a hand-built per-row vector so it
        // stays a statement about Minus, not about upper.
        Vector datasetVariablesUpper = new ComputedVector(0, DataValueType.STRING,
                _ -> List.of("STUDYID", "DOMAIN"));

        Vector answer = Minus.evaluate(run, List.of(
                ConstVector.of(List.of("STUDYID", "DOMAIN", "USUBJID")), datasetVariablesUpper));

        assertEquals(List.of("STUDYID", "DOMAIN", "USUBJID"),
                assertInstanceOf(ConstVector.class, answer).value());
    }


    /** The minuend side of the same guard: a zero-row non-constant minuend is the empty list. */
    @Test
    void aZeroRowNonConstantMinuendAnswersTheEmptyDifference()
    {
        IDataTable empty = MockTable.of().col("STUDYID").name("DM").build();
        EvalRun run = EvalRun.fullRange(EvaluationContext.builder().table(empty).build());
        Vector perRow = new ComputedVector(0, DataValueType.STRING, _ -> List.of("A"));

        Vector answer = Minus.evaluate(run, List.of(perRow, ConstVector.of(List.of("B"))));

        assertEquals(List.of(), assertInstanceOf(ConstVector.class, answer).value());
    }

}
