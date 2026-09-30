package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.BitSet;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.NativeExprEvaluator;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Parity guard for CDISC-CG0370 ({@code IDVAR not in $rdomain_variables}, where
 * {@code $rdomain_variables} is {@code referenced_dataset_variables(RDOMAIN)} — runbook W7's
 * replacement of the {@code distinct} operation with {@code value_is_reference: true}).
 *
 * <p>
 * When the SUPP-- table has no {@code RDOMAIN} column the function answers the empty list on every
 * row (the retired operation answered {@code null}, which the {@code $}-reference resolved to the
 * empty set), so {@code not in} fires every non-missing row. The native membership compiler once
 * threw {@code ExpressionException} at evaluation time for this case; this test pins the empty-set
 * behaviour, now through the inline function call.
 * </p>
 */
class NativeMembershipNullRefParityTest
{

    private static Expr membershipExpr()
    {
        return CheckExpressionParser.parse("IDVAR not in referenced_dataset_variables(RDOMAIN)");
    }


    @Test
    void groupedRefStillEvaluatesPerRow()
    {
        // RDOMAIN present + the parent dataset resolvable -> the function answers each row its
        // dataset's column-name list; membership reads it per row. AESEQ is a real AE column (no
        // violation); AEBOGUS is not (row 1 fires).
        IDataTable supp = MockTable.of().col("RDOMAIN", "AE", "AE").col("IDVAR", "AESEQ", "AEBOGUS")
                .build();
        IDataTable ae = MockTable.of().col("STUDYID", "S1").col("USUBJID", "001")
                .colLong("AESEQ", 1L).build();
        DatasetResolver resolver = name -> "AE".equals(name) ? ae : null;
        EvaluationContext ctx = EvaluationContext.builder().table(supp).datasetResolver(resolver)
                .build();
        BitSet nativ = NativeExprEvaluator.evaluate(membershipExpr(), ctx);
        assertEquals(bits(1), nativ, "AEBOGUS is not an AE column -> row 1 fires");
    }


    @Test
    void nullResolvingRefMatchesLegacyEmptySet()
    {
        // No RDOMAIN column -> the absent column folds to "" per row -> the function answers the
        // empty list -> `not in` fires on every non-missing IDVAR (the retired operation's null
        // result folded to the empty set the same way).
        IDataTable supp = MockTable.of().col("IDVAR", "AESEQ", "AEBOGUS").build();
        DatasetResolver resolver = _ -> null;
        Expr e = membershipExpr();
        EvaluationContext ctx = EvaluationContext.builder().table(supp).datasetResolver(resolver)
                .build();
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
