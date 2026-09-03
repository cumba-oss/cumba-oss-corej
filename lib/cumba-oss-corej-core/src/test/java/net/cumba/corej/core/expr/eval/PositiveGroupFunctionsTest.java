package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExprLowering;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.CheckConditionNot;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Engine-work Task EA — the four positive group functions ({@code is_unique_relationship},
 * {@code contains_all}, {@code shares_elements_with}, {@code is_ordered_subset_of}) introduced as
 * the logical complement of their existing negative operators (change #1). Verifies the native
 * complement property and the paired {@link ExprLowering} reversal that keeps the
 * {@code Check → Expr → Check} round-trip intact.
 */
class PositiveGroupFunctionsTest
{

    /**
     * Asserts the positive function is the exact per-row complement of the negative: both
     * expressions are evaluated natively and their verdicts must be bitwise complements over
     * {@code rowCount}.
     */
    private static void assertComplement(String negativeExpr, String positiveExpr,
            EvaluationContext c, int rowCount)
    {
        BitSet neg = NativeExprEvaluator.evaluate(CheckExpressionParser.parse(negativeExpr), c);
        BitSet pos = NativeExprEvaluator.evaluate(CheckExpressionParser.parse(positiveExpr), c);
        BitSet expected = (BitSet) neg.clone();
        expected.flip(0, rowCount);
        assertEquals(expected, pos,
                positiveExpr + " must be the per-row complement of " + negativeExpr);
    }


    @Test
    void isUniqueRelationshipComplementsTheNegative()
    {
        // "x" maps to both 1 and 2 -> not_unique rows {0,1}; unique -> {2}.
        IDataTable t = MockTable.of().col("A", "x", "x", "y").col("B", "1", "2", "3").build();
        EvaluationContext c = EvaluationContext.builder().table(t).build();
        assertComplement("is_not_unique_relationship(A, B)", "is_unique_relationship(A, B)", c, 3);
    }


    @Test
    void containsAllComplementsTheNegative()
    {
        // distinct {INTMODEL, INTTYPE} lacks PCLASS -> not_contains_all fires all rows; the
        // positive
        // contains_all fires none.
        IDataTable t = MockTable.of().col("ANY", "r0", "r1").build();
        EvaluationContext c = EvaluationContext.builder().table(t)
                .variables(Map.of("$src", List.of("INTMODEL", "INTTYPE"), "$required",
                        List.of("INTMODEL", "INTTYPE", "PCLASS")))
                .build();
        assertComplement("not_contains_all($src, $required)", "contains_all($src, $required)", c,
                2);
    }


    @Test
    void sharesElementsWithComplementsTheNegative()
    {
        IDataTable t = MockTable.of().col("ANY", "r0", "r1").build();
        EvaluationContext c = EvaluationContext.builder().table(t)
                .variables(Map.of("$datasets", List.of("DM", "AE"), "$disjoint", List.of("XX")))
                .build();
        // disjoint -> shares_no_elements_with fires all rows; shares_elements_with fires none.
        assertComplement("shares_no_elements_with($datasets, $disjoint)",
                "shares_elements_with($datasets, $disjoint)", c, 2);
    }


    @Test
    void isOrderedSubsetOfComplementsTheNegative()
    {
        IDataTable t = MockTable.of().col("ANY", "r0", "r1").build();
        EvaluationContext c = EvaluationContext.builder().table(t).variables(
                Map.of("$outOfOrder", List.of("C", "A"), "$library", List.of("A", "B", "C")))
                .build();
        // out of order -> is_not_ordered_subset_of fires all rows; is_ordered_subset_of fires none.
        assertComplement("is_not_ordered_subset_of($outOfOrder, $library)",
                "is_ordered_subset_of($outOfOrder, $library)", c, 2);
    }


    @Test
    void isUniqueValueComplementsTheNegative()
    {
        // Task ED: is_not_unique_value(U) == is_not_unique_set(U) with no keys. "S1" duplicated ->
        // not_unique rows {0,1}; is_unique_value -> {2}.
        IDataTable t = MockTable.of().col("U", "S1", "S1", "S2").build();
        EvaluationContext c = EvaluationContext.builder().table(t).build();
        assertComplement("is_not_unique_value(U)", "is_unique_value(U)", c, 3);
    }


    @Test
    void isUniqueValueLowersThroughTheNegative()
    {
        // not is_unique_value(U) -> is_not_unique_value operator-leaf (change #1, Task ED).
        CheckCondition lowered = ExprLowering
                .toCheckCondition(CheckExpressionParser.parse("not is_unique_value(U)"));
        CheckConditionLeaf leaf = assertInstanceOf(CheckConditionLeaf.class, lowered);
        assertEquals("is_not_unique_value", leaf.getOperator());
        assertEquals("U", leaf.getName());
        // a bare is_unique_value(U) -> Not(is_not_unique_value leaf).
        CheckCondition bare = ExprLowering
                .toCheckCondition(CheckExpressionParser.parse("is_unique_value(U)"));
        CheckConditionNot not = assertInstanceOf(CheckConditionNot.class, bare);
        assertEquals("is_not_unique_value",
                assertInstanceOf(CheckConditionLeaf.class, not.getCondition()).getOperator());
    }


    @Test
    void notPositiveLowersToTheNegativeOperatorLeaf()
    {
        // change #1 round-trip: the converter emits not <positive>(…) for the negative operator;
        // lowering must reverse it back to the negative operator-leaf.
        assertNotPositiveLowersToNegative("is_unique_relationship", "is_not_unique_relationship");
        assertNotPositiveLowersToNegative("contains_all", "not_contains_all");
        assertNotPositiveLowersToNegative("shares_elements_with", "shares_no_elements_with");
        assertNotPositiveLowersToNegative("is_ordered_subset_of", "is_not_ordered_subset_of");
    }


    private static void assertNotPositiveLowersToNegative(String positive, String negative)
    {
        CheckCondition lowered = ExprLowering
                .toCheckCondition(CheckExpressionParser.parse("not " + positive + "(A, B)"));
        CheckConditionLeaf leaf = assertInstanceOf(CheckConditionLeaf.class, lowered,
                "not " + positive + " lowers to a leaf");
        assertEquals(negative, leaf.getOperator());
        assertEquals("A", leaf.getName());
    }


    @Test
    void barePositiveLowersToNotNegativeLeaf()
    {
        CheckCondition lowered = ExprLowering
                .toCheckCondition(CheckExpressionParser.parse("is_unique_relationship(A, B)"));
        CheckConditionNot not = assertInstanceOf(CheckConditionNot.class, lowered,
                "a bare positive lowers to Not(<negative>)");
        CheckConditionLeaf leaf = assertInstanceOf(CheckConditionLeaf.class, not.getCondition());
        assertEquals("is_not_unique_relationship", leaf.getOperator());
        assertEquals("A", leaf.getName());
    }

}
