package net.cumba.corej.core.expr.eval;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Describes one registered callable under the one-descriptor model (phase 6b, D16/D17/D19a; SPEC
 * §3.1): {@code name → ordered parameter list}. ⛔ <b>One descriptor per name</b> — the
 * {@code (name, arity)} overload key is retired; what used to be an arity overload is now an
 * optional parameter ({@link Parameter}), and argument binding ({@link ArgumentBinder}) runs
 * <em>before</em> any resolution, so a defaulted parameter and an arity can no longer diverge. This
 * is what makes D91e's {@code record_count} collision — two namespaces sharing
 * {@code (name, arity=0)}, tiebroken by "does the call carry a keyword argument" — unrepresentable.
 *
 * @param name
 *            the function name as it appears in expression text (canonical or alias)
 * @param parameters
 *            the ordered parameter list; empty for a nullary callable
 * @param kind
 *            whether {@link #fn()} returns a {@link Vector} or a {@link java.util.BitSet}
 * @param fn
 *            the vectorized implementation, invoked with one (possibly {@code null}) argument
 *            vector per declared parameter — {@code null} at an optional parameter's position means
 *            "not bound; apply your documented default". For a {@linkplain Parameter#collector()
 *            collector} descriptor the list is instead the flat element vectors. {@code null} for
 *            an operation-kind descriptor whose calls compile through the {@code OperationExecutor}
 *            bridge instead of the registry ({@code OperationDescriptors})
 */
public record FunctionDescriptor(String name, List<Parameter> parameters, FunctionKind kind,
        @Nullable EvalFunction fn)
{

    public FunctionDescriptor
    {
        if (name == null || name.isEmpty())
        {
            throw new IllegalArgumentException("function name must be non-empty");
        }
        if (kind == null)
        {
            throw new IllegalArgumentException("kind must be non-null");
        }
        parameters = List.copyOf(parameters);
        for (int i = 0; i < parameters.size(); i++)
        {
            if (parameters.get(i).collector() && i != parameters.size() - 1)
            {
                throw new IllegalArgumentException("collector parameter '"
                        + parameters.get(i).name() + "' must be the trailing parameter");
            }
        }
    }


    /** The number of required parameters — the fewest arguments a call may bind. */
    public int minArity()
    {
        return (int) parameters.stream().filter(Parameter::required).count();
    }


    /**
     * The largest positional argument count a call may carry — {@link Integer#MAX_VALUE} for a
     * collector descriptor.
     */
    public int maxArity()
    {
        return parameters.stream().anyMatch(Parameter::collector) ? Integer.MAX_VALUE
                : parameters.size();
    }


    /** The declared parameter with the given name, or {@code null}. */
    public @Nullable Parameter parameter(String parameterName)
    {
        for (Parameter p : parameters)
        {
            if (p.name().equals(parameterName))
            {
                return p;
            }
        }
        return null;
    }
}
