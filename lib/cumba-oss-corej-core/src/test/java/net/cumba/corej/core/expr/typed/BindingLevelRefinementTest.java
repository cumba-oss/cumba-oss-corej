package net.cumba.corej.core.expr.typed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Set;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.Operation;
import net.cumba.corej.core.model.Rule;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Phase 6, D106d — the {@code $}-binding-level refinement from the run's dataset inventory: SPEC
 * §1.3's level is an <b>upper bound</b> on runtime decidability, and a cross-dataset operation over
 * an absent foreign domain degenerates to a scalar at execution, so its binding is typed
 * {@code dataset} on the refined path. The load path (no inventory) keeps the declaration-derived
 * level.
 */
class BindingLevelRefinementTest
{

    private static Rule crossDatasetRule(String operator, @Nullable String domain,
            @Nullable List<String> group)
    {
        Rule rule = new Rule();
        Operation op = new Operation();
        op.setId("$n");
        op.setOperator(operator);
        op.setName("DSSTDTC");
        op.setDomain(domain);
        op.setGroup(group);
        rule.setOperations(List.of(op));
        return rule;
    }


    private static Level levelOf(Rule rule, @Nullable ForeignDatasetInventory inventory)
    {
        Expr expr = CheckExpressionParser.parse("$n == 0");
        TypedExpr typed = StageAChecker.deriveTyped(rule, expr, ColumnLevelResolver.STAGE_A,
                inventory);
        assertNotNull(typed);
        return typed.level();
    }


    @Test
    void anAbsentForeignDomainDegeneratesTheBindingToDataset()
    {
        Rule rule = crossDatasetRule("record_count", "DS", List.of("USUBJID"));
        // The D106d population: record_count(domain="DS", group=[USUBJID]) with DS absent — at
        // run time the operation folds to one scalar, so the refined level is dataset, not
        // group(K).
        assertEquals(Level.DATASET, levelOf(rule, _ -> null));
    }


    @Test
    void aResolvableForeignDomainKeepsTheDeclarationDerivedLevel()
    {
        Rule rule = crossDatasetRule("record_count", "DS", List.of("USUBJID"));
        Level level = levelOf(rule, _ -> Set.of("USUBJID", "DSSTDTC"));
        assertEquals(new Granularity.Group(Set.of("USUBJID")), level.granularity());
    }


    @Test
    void theLoadPathIsUnrefined()
    {
        // No inventory (stage A proper / the plain deriveTyped): the declaration's level stands.
        Rule rule = crossDatasetRule("record_count", "DS", List.of("USUBJID"));
        Level level = levelOf(rule, null);
        assertEquals(new Granularity.Group(Set.of("USUBJID")), level.granularity());
    }


    @Test
    void dateDiffDaysKeepsThePrimaryAsItsTargetAndNeverDegenerates()
    {
        // Mirrors OperationExecutor.resolveTargetTable's exemption: date_diff_days runs against
        // the primary; its domain names only the foreign reference dataset.
        Rule rule = crossDatasetRule("date_diff_days", "DS", null);
        Level withAbsent = levelOf(rule, _ -> null);
        Level without = levelOf(rule, null);
        assertEquals(without, withAbsent);
    }


    @Test
    void wildcardAndUnspecialisedDomainsAreNotConcreteReferences()
    {
        Rule star = crossDatasetRule("record_count", "*", null);
        assertEquals(levelOf(star, null), levelOf(star, _ -> null));
        Rule dashes = crossDatasetRule("record_count", "SUPP--", null);
        assertEquals(levelOf(dashes, null), levelOf(dashes, _ -> null));
    }

}
