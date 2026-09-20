package net.cumba.corej.core.expr.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.ast.Expr;
import org.jspecify.annotations.Nullable;

/**
 * Binds a call's arguments to a descriptor's ordered parameter list — <b>before</b> any overload
 * resolution, which no longer exists: one descriptor per name (phase 6b, D19a; SPEC §3.1).
 *
 * <p>
 * D19a verbatim: positional arguments bind by position; once a named argument appears no positional
 * may follow (that rule lives in {@code CheckExpressionParser.parseArg}, which rejects the shape at
 * parse time); and a name that a positional already bound may not be given again — <b>two distinct
 * errors, two distinct messages</b>. This class raises the second, plus the binding errors the
 * retired {@code (name, arity)} key used to express structurally: too many positionals, a missing
 * required parameter, an unknown argument name.
 * </p>
 *
 * <p>
 * The result is one slot per declared parameter, in declaration order; an unbound optional
 * parameter's slot is {@code null} ("absent — apply the implementation's documented default"). For
 * a {@linkplain Parameter#collector() collector} descriptor the result is instead the flat element
 * list (the §1.5 {@code tuple} sugar), never containing {@code null}.
 * </p>
 */
public final class ArgumentBinder
{

    private ArgumentBinder()
    {
    }


    /**
     * Binds {@code c}'s arguments against {@code d}'s parameter list.
     *
     * @return one expression per declared parameter ({@code null} = optional parameter left
     *         absent), or the flat element list for a collector descriptor
     * @throws ExpressionException
     *             on any D19a binding violation; the message names the callable and its parameters
     */
    public static List<@Nullable Expr> bind(FunctionDescriptor d, Expr.Call c)
    {
        List<Parameter> params = d.parameters();
        List<Expr> positionals = c.args();
        Map<String, Expr> named = c.kwargs();
        if (!params.isEmpty() && params.get(params.size() - 1).collector())
        {
            return bindCollector(d, c);
        }
        if (positionals.size() > params.size())
        {
            throw new ExpressionException("'" + d.name() + "' accepts at most " + params.size()
                    + " argument(s)" + signature(d) + ", got " + positionals.size()
                    + " positional argument(s)");
        }
        List<@Nullable Expr> bound = new ArrayList<>(params.size());
        for (int i = 0; i < params.size(); i++)
        {
            bound.add(i < positionals.size() ? positionals.get(i) : null);
        }
        for (Map.Entry<String, Expr> kw : named.entrySet())
        {
            String name = kw.getKey();
            int idx = indexOf(params, name);
            if (idx < 0)
            {
                throw new ExpressionException(
                        "'" + d.name() + "' has no parameter '" + name + "'" + signature(d));
            }
            if (idx < positionals.size())
            {
                // D19a's second error: a name a positional already bound may not be given again.
                throw new ExpressionException("argument '" + name + "' of '" + d.name()
                        + "' is already bound by position " + (idx + 1)
                        + " and may not be given again by name");
            }
            bound.set(idx, kw.getValue());
        }
        for (int i = 0; i < params.size(); i++)
        {
            if (params.get(i).required() && bound.get(i) == null)
            {
                throw new ExpressionException("'" + d.name() + "' requires argument '"
                        + params.get(i).name() + "'" + signature(d));
            }
        }
        return bound;
    }


    /**
     * The §1.5 collector sugar: every positional beyond the fixed leading parameters is an element
     * of the trailing list-typed parameter ({@code tuple(A, B, C)} ≡ one
     * {@code list<column-reference>} of three elements). Named arguments cannot address a
     * collector's elements, so any named argument here must name a leading parameter — with none
     * declared before the collector today, a named argument is simply unknown.
     */
    private static List<@Nullable Expr> bindCollector(FunctionDescriptor d, Expr.Call c)
    {
        List<Parameter> params = d.parameters();
        int fixed = params.size() - 1;
        if (!c.kwargs().isEmpty())
        {
            String name = c.kwargs().keySet().iterator().next();
            int idx = indexOf(params, name);
            if (idx < 0 || idx >= fixed)
            {
                throw new ExpressionException(
                        "'" + d.name() + "' has no parameter '" + name + "'" + signature(d));
            }
        }
        if (c.args().size() < fixed)
        {
            throw new ExpressionException("'" + d.name() + "' requires at least " + fixed
                    + " argument(s)" + signature(d) + ", got " + c.args().size());
        }
        return new ArrayList<>(c.args());
    }


    private static int indexOf(List<Parameter> params, String name)
    {
        for (int i = 0; i < params.size(); i++)
        {
            if (params.get(i).name().equals(name))
            {
                return i;
            }
        }
        return -1;
    }


    private static String signature(FunctionDescriptor d)
    {
        return " (parameters: "
                + d.parameters().stream().map(p -> p.required() ? p.name() : p.name() + "?")
                        .collect(Collectors.joining(", "))
                + ")";
    }
}
