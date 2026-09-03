package net.cumba.corej.core.expr;

/**
 * Raised when an expression is not merely <em>unsupported by the native backend</em> (that is
 * {@link ExpressionException}, which at LOAD time leaves {@code checkExpr == null}, so the rule is
 * reported as an {@code ERROR} at execution — the legacy engine that once absorbed those is gone)
 * but is <strong>definitionally wrong</strong> — a rule that can never be correct regardless of the
 * data. The canonical case is a metadata accessor function ({@code var_*} / {@code ds_*}) asking
 * for an attribute at a level that does not model it (e.g. {@code var_role(X, "DATA")}) or naming
 * an unknown level.
 *
 * <p>
 * It is deliberately <strong>not</strong> a subclass of {@link ExpressionException}: load-time call
 * sites catch {@code ExpressionException} to decline native retention, and a rule definition error
 * must <em>not</em> be silently declined — it must surface as a
 * {@link net.cumba.corej.core.exec.RuleExecutionStatus#ERROR}. Because the offending
 * {@code (attribute, level)} pair is fully static (the level is a string literal and the attribute
 * is the function name), this is thrown at <em>compile</em> time and routed through the established
 * per-rule load-error channel ({@code Rule.loadError}) before any cohort is built.
 * </p>
 */
public class RuleDefinitionException extends RuntimeException
{

    private static final long serialVersionUID = 1L;

    public RuleDefinitionException(String message)
    {
        super(message);
    }

}
