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
 * ⚠⚠ <b>CORRECTED BY PHASE 7 of {@code PLAN-typed-expression-engine} — this paragraph used to
 * assert the opposite, on a premise that was false when it was written.</b> It said the Check is a
 * {@link CheckConditionExpression} <i>"on purpose — the form every shipped {@code rules/} rule
 * deserialises to"</i>, and pinned <b>four</b> findings, one per row. A shipped rule did
 * <em>not</em> deserialise to that form: until phase 7 the loader <b>lowered</b> an
 * {@code expression:} Check into the operator-leaf AST, and {@code empty(TSVAL)} lowers cleanly
 * (measured — it round-trips byte-identical), so every shipped {@code empty(X)} rule took
 * {@code BroadcastFold} and reported <b>one dataset-level finding</b>. Building the
 * {@code CheckConditionExpression} by hand here bypassed the deserialiser and measured a path no
 * shipped rule ever took.
 * </p>
 *
 * <p>
 * ⭐ The corpus carries this shape abundantly: <b>75</b> shipped rules author a bare
 * {@code empty(X)} as their whole Check (re-derived 2026-09-19 over all 3,435 check files under the
 * rules repository's {@code rules-src/checks}, matching a whole Check that is one {@code
 * empty(…)} call over a single argument). ⚠ <b>73 of the 75 name a column of the dataset under
 * test</b>; the other two, {@code FDA-SD1016} and {@code PMDA-SD1016}, take the dotted
 * cross-dataset form {@code empty(TI.IETESTCD)} and so do not exercise this class's absent-column
 * path at all — exclude them and the count is 73, which is the narrower figure this paragraph used
 * to state without saying so. ⚠ The scenario that pinned it <em>directly</em> —
 * {@code CORE-000701-invalid-AE-1.cdt}, {@code empty(EPOCH)} on an AE with no {@code EPOCH} column
 * and two rows, {@code expectViolationCount 1} — was <b>retired with the CORE family</b> on
 * 2026-09-19 and resolves only in git history; the reading it recorded stands, but this class no
 * longer has a corpus scenario standing behind it. One dataset-level finding is also what
 * D111/D111a want: the absent-column fact reported once per dataset, the gate-plus-sister-rule
 * idiom collapsed into the engine, and the flood the fold exists to prevent (a 1 000-row AE would
 * otherwise carry 1 000 identical findings).
 * </p>
 *
 * <p>
 * ⛔ <b>What this class still pins, and it is the part that matters</b>: the load-time pass does not
 * re-inject the retired {@code var_exists} guard, and the bare leaf is TRUE over an absent column
 * rather than silently false. The granularity of the resulting finding is the fold's business — Fix
 * #330's ruling is about the guard, and the row count was collateral measured through a hand-built
 * path.
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
        // ⭐ ONE dataset-level finding, not one per row — see the class javadoc. The rule is built
        // through installNativeExpr, exactly as the loader builds a shipped rule, so it takes the
        // same BroadcastFold a shipped `empty(X)` rule has always taken.
        assertEquals(1, result.getViolationCount(),
                "the absent-column fact is reported ONCE per dataset (D111a), not once per row");
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
