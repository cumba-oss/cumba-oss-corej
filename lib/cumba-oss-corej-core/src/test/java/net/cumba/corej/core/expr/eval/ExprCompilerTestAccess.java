package net.cumba.corej.core.expr.eval;

import java.util.BitSet;

/**
 * Public test access to {@link ExprCompiler}'s package-private wave-0 membership arm
 * ({@code PLAN-binding-expressions} I4), for the runner-level tests in {@code exec}. Test source
 * only.
 */
public final class ExprCompilerTestAccess
{

    private ExprCompilerTestAccess()
    {
    }


    /**
     * Delegates to {@code ExprCompiler.boundMembership}.
     *
     * @param v
     *            the probe
     * @param bound
     *            the compiled binding's Vector
     * @param rowCount
     *            the rows to decide
     * @param negate
     *            {@code not in}
     * @param caseInsensitive
     *            the case-insensitive surface
     * @param listLhs
     *            a list-valued probe
     * @return the verdicts
     */
    public static BitSet boundMembership(Vector v, Vector bound, int rowCount, boolean negate,
            boolean caseInsensitive, boolean listLhs)
    {
        return ExprCompiler.boundMembership(v, bound, rowCount, negate, caseInsensitive, listLhs);
    }

}
