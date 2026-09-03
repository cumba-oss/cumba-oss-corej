package net.cumba.corej.core.exec;

import java.util.List;

import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.Domain;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.datatable.report.Severity;
import org.jspecify.annotations.Nullable;

/**
 * One rung of a <b>multi-level</b> rule's evaluation (Plan C &#167;3.4): everything
 * {@link RuleRunner}'s per-level pass needs that a single-level execution reads straight off the
 * {@link net.cumba.corej.core.model.Rule}.
 *
 * <p>
 * &#9733; <b>Built only for a rule that declares more than one level.</b> A single-level rule —
 * which is the entire shipped corpus — runs with {@code levelPlan == null}, down the same code path
 * and reading the same rule fields as it did before per-level Checks existed. That is the point:
 * the single-level path is not "a loop that happens to run once", it is literally unchanged.
 * </p>
 *
 * @param level
 *            the ladder level this rung reports at, and the value stamped on every
 *            {@link Violation} it claims
 * @param condition
 *            the level's Check condition, already {@code --}-prefix resolved for this dataset —
 *            what {@code projectedOutputVariables} and the {@code CheckConditionExpression} fold
 *            gate read in place of {@code rule.getCheck()}
 * @param expr
 *            the level's <b>effective</b> compiled expression: its own, or the {@code Fix #222}
 *            dependency-scoped rewrite when this level reads an absent, already-reported dataset
 * @param broadcast
 *            whether {@code expr} is a fold-equivalent dataset-broadcast verdict — the per-level
 *            {@code Rule.broadcastCheckExpr}
 * @param collapsed
 *            whether {@code Fix #222} collapsed this level's whole Check. A collapsed level is
 *            constant-{@code false} and contributes nothing; the <em>rule</em> reports
 *            {@code SKIPPED} only when every runnable level collapsed
 * @param domain
 *            the evaluation domain <b>shared by every level</b> — the join, so the finding unit
 *            keeps one shape across the ladder and first-claim compares like with like
 * @param outputVariables
 *            the projected {@code Output_Variables}, computed once over the join of the levels'
 *            conditions: &#167;3.3 makes {@code Outcome.Output_Variables} shared across levels, so
 *            a finding must carry the same columns whichever level claimed it
 * @param message
 *            the level's own {@code Message}, or {@code null} to fall back to the rule's
 *            {@code Outcome.Message} at report time (&#167;3.6)
 */
public record CheckLevelPlan(Severity level, CheckCondition condition, Expr expr, boolean broadcast,
        boolean collapsed, Domain domain, List<String> outputVariables, @Nullable String message)
{

    /**
     * Defensive copy of {@link #outputVariables} — built per (rule &#215; dataset) execution from
     * the join of the levels' conditions, then read once per claimed row.
     */
    public CheckLevelPlan
    {
        outputVariables = List.copyOf(outputVariables);
    }

}
