package net.cumba.corej.core.expr.eval;

import net.cumba.corej.core.expr.typed.ExprType;

/**
 * One declared parameter of a callable — the unit of the one-descriptor model (phase 6b of
 * {@code plans/PLAN-typed-expression-engine.md}, D16/D17/D19a; SPEC §3.1): a callable is
 * {@code name → ordered parameter list}, each parameter {@code (name, type, required | optional)}.
 *
 * <p>
 * The <b>type</b> is the parameter's declared expression type (SPEC §1.6's mapping rule: what the
 * element's semantics require, not what the old positional surface happened to tolerate).
 * {@link ExprType.Unknown#UNKNOWN} means "not yet specified" — the stage-A checker reports only
 * known-vs-known conflicts, so an unknown parameter type can only make the checker quieter, never
 * wrong (the phase-2 partiality contract).
 * </p>
 *
 * <p>
 * A parameter is either <b>required</b> or <b>optional</b>. An optional parameter left unbound is
 * handed to the implementation as an absent argument ({@code null} at its position); the default
 * <em>behaviour</em> lives with the implementation, exactly where the old per-arity overloads kept
 * it — so {@code substring(X, 2)} and the retired arity-2 registration are byte-identical. This is
 * D19a's core distinction: a defaulted parameter and an arity are not the same thing, and argument
 * binding runs <em>before</em> overload resolution ({@link ArgumentBinder}).
 * </p>
 *
 * <p>
 * A <b>collector</b> parameter (legal only as the trailing parameter, and only with a list type)
 * absorbs every remaining positional argument — the §1.5 sugar that keeps {@code tuple(A, B, …)}
 * spellable while its descriptor declares one {@code list<column-reference>} parameter (D59: a
 * collection argument, not a variadic parameter; D91: {@code tuple} is the composite key of a
 * {@code list<column-reference>}).
 * </p>
 *
 * @param name
 *            the parameter name, as authors may spell it in {@code name=value} form (D19a)
 * @param type
 *            the declared expression type; {@link ExprType.Unknown#UNKNOWN} when unspecified
 * @param required
 *            whether a call must bind this parameter
 * @param collector
 *            whether this (trailing, list-typed) parameter absorbs the remaining positionals
 */
public record Parameter(String name, ExprType type, boolean required, boolean collector)
{

    public Parameter
    {
        if (name == null || name.isEmpty())
        {
            throw new IllegalArgumentException("parameter name must be non-empty");
        }
        if (type == null)
        {
            throw new IllegalArgumentException("parameter type must be non-null");
        }
        if (collector && !(type instanceof ExprType.ListOf))
        {
            throw new IllegalArgumentException(
                    "collector parameter '" + name + "' must have a list type");
        }
    }


    /** A required parameter. */
    public static Parameter required(String name, ExprType type)
    {
        return new Parameter(name, type, true, false);
    }


    /** An optional parameter (absent ⇒ the implementation's documented default). */
    public static Parameter optional(String name, ExprType type)
    {
        return new Parameter(name, type, false, false);
    }


    /**
     * A trailing collector parameter (§1.5 sugar; see class javadoc). Not itself required: the
     * fixed leading parameters carry the minimum, and the collector absorbs whatever follows
     * (possibly nothing — {@code tuple(A, B)} has an empty collector).
     */
    public static Parameter collector(String name, ExprType.ListOf type)
    {
        return new Parameter(name, type, false, true);
    }
}
