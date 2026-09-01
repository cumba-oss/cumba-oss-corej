package net.cumba.cdisc.core.expr.eval;

/**
 * Describes one registered function overload, keyed by {@code (name, arity)}. A name may be
 * registered at several arities (overload by parameter count, per the SPI requirement).
 *
 * @param name
 *            the function name as it appears in expression text (canonical or alias)
 * @param arity
 *            the exact argument count this overload accepts
 * @param kind
 *            whether {@link #fn()} returns a {@link Vector} or a {@link java.util.BitSet}
 * @param fn
 *            the vectorized implementation
 */
public record FunctionDescriptor(String name, int arity, FunctionKind kind, EvalFunction fn)
{

    public FunctionDescriptor
    {
        if (name == null || name.isEmpty())
        {
            throw new IllegalArgumentException("function name must be non-empty");
        }
        if (arity < 0)
        {
            throw new IllegalArgumentException("arity must be >= 0");
        }
        if (kind == null || fn == null)
        {
            throw new IllegalArgumentException("kind and fn must be non-null");
        }
    }
}
