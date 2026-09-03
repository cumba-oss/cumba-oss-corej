package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code Fix #330} ({@code M3-D18} / {@code M2-D24}, owner ruling 2026-08-22) — a bare
 * {@code empty(X)} over an <b>absent</b> column is <b>true on every row</b> of the row channel, and
 * nothing between the authored Check and the compiled program re-introduces a guard.
 *
 * <p>
 * The two halves are asserted together on purpose. {@code NonEmptyGuardInlinerTest} (deleted
 * 2026-08-26) proved the converter no longer wraps the leaf; this test proves what the engine then
 * <em>does</em> with the bare leaf end-to-end through {@code RulePackageLoader.installNativeExpr}
 * (the load-time re-application of the same pass) and {@code ExprCompiler}'s
 * {@code FIRES_ON_ABSENT_COLUMN} special case — the all-rows {@code BitSet} returned when
 * {@code nameRefPlan} yields {@code null} for an absent column. Before the retirement the injected
 * {@code var_exists("TSVAL")} was false and the same rule reported nothing; a regression to that
 * shape turns every assertion here red.
 * </p>
 *
 * <p>
 * ⚠ The Check is a {@link CheckConditionExpression} on purpose — the form every shipped
 * {@code rules/} rule deserialises to. {@code RuleRunner} routes a non-expression Check (the
 * operator-leaf model) through {@code BroadcastFold} first, whose {@code empty(absent)}
 * short-circuit yields <em>one</em> dataset-level finding; an expression Check takes the broadcast
 * path only when {@code isBroadcastCheckExpr()} is set, which an {@code empty} call never is, so it
 * evaluates <b>per row</b>. The measurement report (§3b) states exactly this, and the first test
 * pins it with a row count.
 * </p>
 */
class AbsentColumnBareEmptinessTest
{

    /** TS with TSPARMCD populated over 4 rows; TSVAL absent. */
    private static IDataTable ts()
    {
        return MockTable.of().name("TS").col("TSPARMCD", "A", "B", "C", "D").build();
    }


    private static CheckCondition expression(String text)
    {
        return new CheckConditionExpression(CheckExpressionParser.parse(text), text);
    }


    private static Rule rule(String id, CheckCondition check)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId(id);
        rule.setCore(core);
        rule.setCheck(check);
        rule.setSensitivity(Sensitivity.RECORD);
        Outcome outcome = new Outcome();
        outcome.setMessage("m");
        outcome.setOutputVariables(List.of());
        rule.setOutcome(outcome);
        net.cumba.corej.core.RulePackageLoader.installNativeExpr(rule);
        return rule;
    }


    @Test
    @DisplayName("bare empty(absent) carries no injected guard and fires on every row")
    void bareEmptinessOverAnAbsentColumnFiresOnEveryRow()
    {
        Rule bare = rule("CORE-BARE-1", expression("empty(TSVAL)"));

        assertFalse(String.valueOf(bare.getCheckExpr()).contains("var_exists"),
                () -> "the load-time pass must not re-inject the retired guard — "
                        + bare.getCheckExpr());

        RuleExecutionResult result = RuleRunner.execute(bare, ts());
        assertTrue(result.hasViolations(), "absent = all-missing: empty(TSVAL) is true");
        assertEquals(4, result.getViolationCount(), "one finding per row, not one per dataset");
    }


    @Test
    @DisplayName("bucket 1 — a restricting sibling selects the subset; the bare leaf does not flood")
    void restrictingSiblingSelectsTheSubset()
    {
        // The measurement report's bucket-1 shape: the other conjunct genuinely restricts rows, so
        // the rule reports the defect on exactly the rows it selects — here one of four.
        Rule subset = rule("CORE-BARE-2", expression("TSPARMCD == \"B\" and empty(TSVAL)"));

        RuleExecutionResult result = RuleRunner.execute(subset, ts());
        assertEquals(1, result.getViolationCount(), () -> "expected the single TSPARMCD == B row; "
                + "check expr was " + subset.getCheckExpr());
        assertEquals(1L, result.getViolations().getFirst().getRow());
    }


    @Test
    @DisplayName("a present column keeps its row-wise answer — only the blank rows fire")
    void presentColumnIsUnchanged()
    {
        IDataTable present = MockTable.of().name("TS").col("TSPARMCD", "A", "B", "C", "D")
                .col("TSVAL", "1", "", "3", "").build();
        Rule bare = rule("CORE-BARE-3", expression("empty(TSVAL)"));

        assertEquals(2, RuleRunner.execute(bare, present).getViolationCount());
    }
}
