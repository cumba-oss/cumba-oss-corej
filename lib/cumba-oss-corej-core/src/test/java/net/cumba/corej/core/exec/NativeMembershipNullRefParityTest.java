package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.NativeExprEvaluator;
import net.cumba.corej.core.model.Operation;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Parity guard for CDISC-CG0370 ({@code IDVAR is_not_contained_by $rdomain_variables}, where
 * {@code $rdomain_variables} is a {@code distinct} operation with
 * {@code value_is_reference: true}).
 *
 * <p>
 * When the SUPP-- table has no {@code RDOMAIN} column,
 * {@code OperationExecutor.evalDistinctVariableNames} returns {@code null}, so the operation result
 * is absent and the {@code $rdomain_variables} reference resolves to {@code null}. Legacy treats a
 * null-resolving {@code $}-reference as the empty list, so {@code is_not_contained_by} fires every
 * non-missing row. The native membership compiler previously threw {@code ExpressionException} at
 * evaluation time for this case instead of mirroring legacy; this test pins the corrected empty-set
 * behaviour.
 * </p>
 */
class NativeMembershipNullRefParityTest
{

    private static Operation distinctVariableNamesOp()
    {
        Operation op = new Operation();
        op.setId("$rdomain_variables");
        op.setOperator("distinct");
        op.setName("IDVAR");
        op.setValueIsReference(true);
        return op;
    }


    private static Expr membershipExpr()
    {
        return CheckExpressionParser.parse("IDVAR not in $rdomain_variables");
    }


    @Test
    void groupedRefStillEvaluatesPerRow()
    {
        // RDOMAIN present + the parent dataset resolvable -> the operation yields a GroupedResult;
        // the native groupedMembership path resolves the per-row column-name set. AESEQ is a real
        // AE column (no violation); AEBOGUS is not (row 1 fires).
        IDataTable supp = MockTable.of().col("RDOMAIN", "AE", "AE").col("IDVAR", "AESEQ", "AEBOGUS")
                .build();
        IDataTable ae = MockTable.of().col("STUDYID", "S1").col("USUBJID", "001")
                .colLong("AESEQ", 1L).build();
        DatasetResolver resolver = name -> "AE".equals(name) ? ae : null;
        Map<String, Object> vars = OperationExecutor.execute(List.of(distinctVariableNamesOp()),
                supp, resolver);

        EvaluationContext ctx = EvaluationContext.builder().table(supp).variables(vars).build();
        BitSet nativ = NativeExprEvaluator.evaluate(membershipExpr(), ctx);
        assertEquals(bits(1), nativ, "AEBOGUS is not an AE column -> row 1 fires");
    }


    @Test
    void nullResolvingRefMatchesLegacyEmptySet()
    {
        // No RDOMAIN column -> the operation returns null -> $rdomain_variables resolves to null.
        // Legacy treats it as the empty set, so is_not_contained_by fires every non-missing IDVAR.
        // The native path must do the same instead of throwing.
        IDataTable supp = MockTable.of().col("IDVAR", "AESEQ", "AEBOGUS").build();
        DatasetResolver resolver = _ -> null;
        Map<String, Object> vars = OperationExecutor.execute(List.of(distinctVariableNamesOp()),
                supp, resolver);

        Expr e = membershipExpr();
        EvaluationContext ctx = EvaluationContext.builder().table(supp).variables(vars).build();
        BitSet nativ = NativeExprEvaluator.evaluate(e, ctx);
        assertEquals(bits(0, 1), nativ, "empty membership set -> every non-missing IDVAR fires");
    }


    private static BitSet bits(int... rows)
    {
        BitSet bs = new BitSet();
        for (int r : rows)
        {
            bs.set(r);
        }
        return bs;
    }

}
