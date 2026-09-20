package net.cumba.corej.core.exec;

import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.Domain;
import net.cumba.corej.core.expr.eval.MetadataExprScan;

/**
 * The <b>binding scope</b> of one (rule × dataset) execution — which unit the rule's cursor
 * iterates, and therefore which native loop runs (phase 6 of
 * {@code plans/PLAN-typed-expression-engine.md}, D8/D41a).
 *
 * <p>
 * This is D41a's leaf-scope dispatch — <i>"{@code {VAR,ROW}} ⇒ per-(variable, row); {@code {VAR}} ⇒
 * per variable; {@code {}} ⇒ one broadcast verdict; {@code {ROW}} ⇒ the row path"</i> — lifted out
 * of {@code RuleRunner.executeUnified}'s if-cascade into the one named derivation the runner routes
 * on. D74a(iii) verified the cascade was already the <b>sole</b> dispatch (the legacy Step-1/3/4
 * cascade and the {@code Rule_Type} gates are retired); this type makes that fact a value rather
 * than a shape of the code, and gives D8's <i>"a rule executes once per binding"</i> a first-class
 * carrier ({@link BindingLedger}).
 * </p>
 *
 * <p>
 * Two routing decisions deliberately stay <b>outside</b> the scope and are not dispatch:
 * </p>
 * <ul>
 * <li><b>The {@code BroadcastFold} pre-pass</b> is the dataset-decidable fast path, not a fifth
 * scope: where it decides, the decision is Kleene-sound for every binding at once. D106b measured
 * its only deviation from this scope's static prediction ({@code FOLD_EXCEEDED}) at
 * {@code verdict=FALSE} in all 440 occurrences — observationally identical to the row path — and
 * the TRUE direction, where emission would differ, at zero everywhere.</li>
 * <li><b>Group sensitivity</b> ({@code executeGrouped}) is emission granularity, not a cursor: the
 * rule level of SPEC §8 / D28, keyed on {@code Grouping_Variables}, orthogonal to what the Check
 * varies over.</li>
 * </ul>
 */
public enum BindingScope
{

    /**
     * {@code {VAR,ROW}} — the cursor iterates (variable × row); one binding per variable, one
     * verdict per (variable, row). {@code RuleRunner.evaluateVariableValueNative}.
     */
    PER_VARIABLE_ROW,

    /**
     * {@code {VAR}} — the cursor iterates variables; one binding and one verdict per variable.
     * {@code RuleRunner.evaluateMetadataNative(perVariable=true)}.
     */
    PER_VARIABLE,

    /**
     * {@code {}} on a pure-metadata Check — no cursor; the single dataset binding, evaluated once
     * as a broadcast verdict. {@code RuleRunner.evaluateMetadataNative(perVariable=false)}. Per
     * D41b, per-binding <em>is</em> per-dataset here, so the per-(rule, dataset) result is itself
     * the binding's carrier and no {@link BindingLedger} is attached.
     */
    BROADCAST_METADATA,

    /**
     * {@code {ROW}} — and every {@code {}} Check the broadcast evaluator does not own (row-operand
     * whole-column verdicts, {@code $}-set operators): the row path, with the non-row-based
     * collapse to one dataset verdict. The bindings are rows; their carrier is the violation list
     * itself.
     */
    ROW_PATH;

    /**
     * Derives the scope of one execution from the rule's evaluation {@link Domain} and its Check —
     * a faithful transcription of the dispatch conditions {@code executeUnified} routed on since
     * the leaf-scope plan's phase 4, changed in no case.
     *
     * @param domain
     *            the rule's evaluation domain (the join of the Check leaves' cursor demands)
     * @param checkExpr
     *            the (specialised) Check expression of this execution
     * @return the binding scope
     */
    public static BindingScope of(Domain domain, Expr checkExpr)
    {
        if (domain.varCursor())
        {
            return domain.rowCursor() ? PER_VARIABLE_ROW : PER_VARIABLE;
        }
        // {} — a dataset-level verdict the fold pre-pass could not decide: a metadata-accessor
        // Check evaluates once (evaluateBroadcast); anything else takes the row path and
        // collapses — exactly the two paths the retired type gates routed these shapes to.
        if (domain.isBroadcast()
                && (MetadataExprScan.containsMetadataFunction(checkExpr)
                        || MetadataExprScan.containsVarname(checkExpr)
                        || MetadataExprScan.containsVariableNameAnchor(checkExpr))
                && MetadataExprScan.isPureMetadata(checkExpr))
        {
            return BROADCAST_METADATA;
        }
        return ROW_PATH;
    }
}
