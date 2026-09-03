package net.cumba.corej.core.expr.eval;

/**
 * The result shape of an {@link EvalFunction}: a {@link Vector} (a value transform such as
 * {@code lower}/{@code len}) or a {@link java.util.BitSet} (a boolean predicate such as
 * {@code contains}/{@code is_integer}).
 */
public enum FunctionKind
{
    /** Returns a {@link Vector}. */
    VALUE,

    /** Returns a {@link java.util.BitSet} of violating rows. */
    BOOLEAN
}
