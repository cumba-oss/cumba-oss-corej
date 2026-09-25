package net.cumba.corej.core.expr;

/**
 * Raised when an expression-syntax {@code Check} leaf cannot be lexed, parsed, or lowered to the
 * existing {@link net.cumba.corej.core.model.CheckCondition} AST. The expression rule format
 * deliberately <em>fails loudly</em>: a malformed expression, an unknown function or built-in
 * reference, an arity error, or an {@code Expr} construct that the v1 lowering pass cannot map are
 * all surfaced as this exception rather than degrading to a silent no-op.
 *
 * <p>
 * A 0-based character offset into the source expression, when one applies, is folded into the
 * message ({@code "(at position N)"}). Callers that load rule packages route this through the
 * established per-rule load-error channel so a bad expression appears as a rule load error, not a
 * hard crash.
 * </p>
 */
public class ExpressionException extends RuntimeException
{

    private static final long serialVersionUID = 1L;

    /**
     * @param position
     *            0-based character offset into the source expression, folded into the message; or
     *            {@code -1} when not applicable
     */
    public ExpressionException(String message, int position)
    {
        super(position >= 0 ? message + " (at position " + position + ")" : message);
    }


    public ExpressionException(String message)
    {
        this(message, -1);
    }

}
