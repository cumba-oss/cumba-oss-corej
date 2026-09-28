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
 * @param provider
 *            the <b>provider capability</b> ({@link ProviderNeed}) — the run-level provider the
 *            function needs before it can answer, or {@code null} when it needs none
 *            ({@code PLAN-binding-expressions} §5.2)
 * @param aggregate
 *            whether the call raises its operands' level to {@code dataset} (SPEC §1.4) — see
 *            {@link #aggregating()}
 */
public record FunctionDescriptor(String name, List<Parameter> parameters, FunctionKind kind,
        @Nullable EvalFunction fn, @Nullable ProviderNeed provider, boolean aggregate)
{

    /**
     * The pre-wave-0 shape: a function that needs no provider and does not raise its level.
     *
     * @param name
     *            the function name
     * @param parameters
     *            the ordered parameter list
     * @param kind
     *            the result shape
     * @param fn
     *            the implementation, or {@code null} for a compiler-dispatched / operation-kind
     *            descriptor
     */
    public FunctionDescriptor(String name, List<Parameter> parameters, FunctionKind kind,
            @Nullable EvalFunction fn)
    {
        this(name, parameters, kind, fn, null, false);
    }


    /**
     * This descriptor declaring a <b>provider capability</b> ({@link ProviderNeed};
     * {@code PLAN-binding-expressions} §5.2) — the one key every provider gate reads for a registry
     * function, beside the {@code OperationType} predicates it reads for an operation.
     *
     * @param aProvider
     *            the provider the function needs
     * @return the copy
     */
    public FunctionDescriptor withProvider(ProviderNeed aProvider)
    {
        return new FunctionDescriptor(name, parameters, kind, fn, aProvider, aggregate);
    }


    /**
     * This descriptor declared an <b>aggregate</b> (SPEC §1.4 raising): the call folds every row of
     * its operands into one broadcast value, so its level is {@code dataset} — the operands'
     * variable cursor survives — whatever its operands' granularity. {@code record_count()} is the
     * historical, name-keyed instance; a ported list-valued callable such as
     * {@code get_codelist_attributes(TSVCDREF, TSVCDVER, …)} declares it here, which is what lets
     * stage A, {@code DomainScan} and the hand-over contract read its binding as dataset-level.
     *
     * @return the copy
     */
    public FunctionDescriptor aggregating()
    {
        return new FunctionDescriptor(name, parameters, kind, fn, provider, true);
    }


    /** Validates the descriptor; see the record javadoc. */
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
