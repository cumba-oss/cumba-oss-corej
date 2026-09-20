package net.cumba.corej.core.expr.typed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Set;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.Operation;
import net.cumba.corej.core.model.Rule;
import org.junit.jupiter.api.Test;

/**
 * {@link StageAChecker#deriveTyped} — the phase-5 bind-time derivation entry: the same walk as the
 * load-time checker, with a {@link ColumnLevelResolver} refining bare column references (D39a: an
 * absent column is a dataset-level constant), findings discarded, and failure yielding {@code null}
 * instead of propagating.
 */
class LevelDerivationTest
{

    private static Expr parse(String expression)
    {
        return CheckExpressionParser.parse(expression);
    }


    @Test
    void theDefaultResolverKeepsTheStageALevel()
    {
        TypedExpr typed = StageAChecker.deriveTyped(new Rule(), parse("AETERM == \"x\""),
                ColumnLevelResolver.STAGE_A);
        assertNotNull(typed);
        assertEquals(Level.RECORD, typed.level());
    }


    @Test
    void aResolverRefinementLiftsAColumnToDatasetLevel()
    {
        // D39a through D5: refine the one record-level operand and the comparison joins to
        // dataset with no extra machinery.
        ColumnLevelResolver absent = ref -> "ZZFOO".equals(ref.name()) ? Level.DATASET : null;
        TypedExpr typed = StageAChecker.deriveTyped(new Rule(), parse("ZZFOO != \"x\""), absent);
        assertNotNull(typed);
        assertEquals(Level.DATASET, typed.level());
    }


    @Test
    void aPartialRefinementLeavesTheJoinAtRecord()
    {
        ColumnLevelResolver absent = ref -> "ZZFOO".equals(ref.name()) ? Level.DATASET : null;
        TypedExpr typed = StageAChecker.deriveTyped(new Rule(),
                parse("ZZFOO != \"x\" and AETERM == \"y\""), absent);
        assertNotNull(typed);
        assertEquals(Level.RECORD, typed.level());
    }


    @Test
    void aThrowingResolverFallsBackToTheStageADefault()
    {
        ColumnLevelResolver broken = ref ->
        {
            throw new IllegalStateException("boom");
        };
        TypedExpr typed = StageAChecker.deriveTyped(new Rule(), parse("AETERM == \"x\""), broken);
        assertNotNull(typed);
        assertEquals(Level.RECORD, typed.level());
    }


    @Test
    void bindingLevelsFollowTheRuleOperations()
    {
        Rule rule = new Rule();
        Operation op = new Operation();
        op.setId("$m");
        op.setOperator("max");
        op.setName("AESEQ");
        op.setGroup(List.of("USUBJID"));
        rule.setOperations(List.of(op));
        TypedExpr typed = StageAChecker.deriveTyped(rule, parse("$m > 5"),
                ColumnLevelResolver.STAGE_A);
        assertNotNull(typed);
        assertEquals(new Granularity.Group(Set.of("USUBJID")), typed.granularity());
    }


    @Test
    void aWalkFailureYieldsNullInsteadOfPropagating()
    {
        // A LIST literal whose payload is not a List<Expr> blows the walker's cast; the
        // derivation must answer null, never throw into the execution path.
        Expr broken = new Expr.Lit(Expr.LitKind.LIST, "not a list");
        assertNull(StageAChecker.deriveTyped(new Rule(), broken, ColumnLevelResolver.STAGE_A));
    }

}
