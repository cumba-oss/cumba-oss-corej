package net.cumba.corej.core.exec;

import org.jspecify.annotations.Nullable;

/**
 * The outcome of one <b>binding</b> of a cursor rule's iteration — D41's unit: <i>"detect per
 * binding, report per binding"</i> (phase 6 of {@code plans/PLAN-typed-expression-engine.md}).
 * Under both variable-cursor scopes ({@link BindingScope#PER_VARIABLE} and
 * {@link BindingScope#PER_VARIABLE_ROW}) a binding is a variable — the dataset's column, or the
 * Define-XML ItemDef under the DEFINE universe — matching D41's own example, which keys the
 * required message by the variable that errored.
 *
 * <p>
 * ⚠ {@code status} is structurally the full {@link RuleExecutionStatus} but is
 * {@link RuleExecutionStatus#EXECUTED} on every production path today: per-binding <b>detection</b>
 * is shipped (this carrier), per-binding <b>erroring</b> is a semantic change D104b defers to its
 * own ruling — the shipped {@code ColumnTypeGate} still aborts the whole (rule × dataset) at its
 * first raise, deliberately. When that ruling lands, an erroring binding carries {@code ERROR} here
 * with {@code message} naming what failed, without a report-shape change.
 * </p>
 *
 * @param variable
 *            the binding's variable name
 * @param status
 *            the binding's own outcome ({@code EXECUTED} everywhere today, see above)
 * @param message
 *            the per-binding diagnostic, or {@code null} — reserved for the D41 error channel
 * @param violationCount
 *            violations this binding contributed (uncapped — the true count, like
 *            {@code RuleExecutionResult.totalViolationCount})
 */
public record BindingOutcome(String variable, RuleExecutionStatus status, @Nullable String message,
        long violationCount)
{

    /** An executed binding that fired {@code violationCount} violations. */
    public static BindingOutcome fired(String variable, long violationCount)
    {
        return new BindingOutcome(variable, RuleExecutionStatus.EXECUTED, null, violationCount);
    }
}
