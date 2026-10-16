package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.ExecCalls;
import net.cumba.corej.core.exec.JoinLookup;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.datatable.testkit.SyntheticDataTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-bugfix-engine-define} QL (T6): a quantifier over joined rows ({@code any} /
 * {@code all} / {@code none} of {@code joined(c)}) is never a dataset fact — {@code all} /
 * {@code none} over a record with no joined rows answer TRUE, over a matched record they answer the
 * condition — so {@code BroadcastFold.callLevel} classifies it {@code ROW} even when the condition
 * reads an absent column, exactly as {@code EmptyColumnCollapse.collectLive} already kept
 * quantifiers out. Until 2026-10-16 the call joined its operand levels and a {@code Message_Folded}
 * rule could have folded the quantifier to one constant (0 carriers: the two quantifier rules,
 * CDISC-CG0217 / CG0218, declare no {@code Message_Folded}).
 */
class BroadcastFoldQuantifierLevelTest
{

    /** AE (the primary, no AESEV) joined to EX (no AESEV either). */
    private static EvaluationContext joinedCtx()
    {
        SyntheticDataTable ex = new SyntheticDataTable("EX", List.of("USUBJID", "EXTRT"),
                new String[]
                {
                        "S1"
                }, 1);
        SyntheticDataTable ae = new SyntheticDataTable("AE", List.of("USUBJID", "AETERM"),
                new String[]
                {
                        "S1"
                }, 1);
        JoinLookup lookup = ExecCalls.viewLookup(ae, "EX", ex, List.of("USUBJID"));
        return EvaluationContext.builder().table(ae)
                .mergedView(ExecCalls.joinsView(Map.of("EX", lookup)))
                .datasetResolver(n -> "EX".equals(n) ? ex : null).build();
    }


    private static Expr parse(String source)
    {
        return CheckExpressionParser.parse(source);
    }


    @Test
    void aQuantifierOverAnAbsentColumnIsRowLevel()
    {
        EvaluationContext ctx = joinedCtx();
        // the language's own shape (CDISC-CG0218: none(joined(…))), the joined column absent
        for (String quantifier : List.of("any", "all", "none"))
        {
            assertEquals(BroadcastFold.AbsentLeafLevel.ROW, BroadcastFold.absentColumnLeafLevel(
                    parse(quantifier + "(joined(EX.AESEV == \"X\"))"), ctx), quantifier);
            // and a bare absent column of the primary inside the quantifier
            assertEquals(BroadcastFold.AbsentLeafLevel.ROW, BroadcastFold.absentColumnLeafLevel(
                    parse(quantifier + "(joined(AESEV == \"X\"))"), ctx), quantifier);
        }
    }


    /** The control: the same absent leaf outside a quantifier still folds. */
    @Test
    void aPlainAbsentColumnLeafIsStillDatasetWithAbsent()
    {
        EvaluationContext ctx = joinedCtx();
        assertEquals(BroadcastFold.AbsentLeafLevel.DATASET_WITH_ABSENT,
                BroadcastFold.absentColumnLeafLevel(parse("AESEV == \"X\""), ctx));
        assertEquals(BroadcastFold.AbsentLeafLevel.DATASET_WITH_ABSENT,
                BroadcastFold.absentColumnLeafLevel(parse("upper(AESEV) == \"X\""), ctx),
                "a pure value function of an absent operand stays dataset-level");
    }
}
