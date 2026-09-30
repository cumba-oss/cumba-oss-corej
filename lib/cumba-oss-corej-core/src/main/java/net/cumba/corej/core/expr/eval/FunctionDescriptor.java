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
 *            (until runbook W8) an operation-kind descriptor whose calls compiled through the
 *            executor bridge instead of the registry
 * @param provider
 *            the <b>provider capability</b> ({@link ProviderNeed}) — the run-level provider the
 *            function needs before it can answer, or {@code null} when it needs none
 *            ({@code PLAN-binding-expressions} §5.2)
 * @param aggregate
 *            whether the call raises its operands' level to {@code dataset} (SPEC §1.4) — see
 *            {@link #aggregating()}
 * @param perRow
 *            whether the call <b>reads the row</b> of the evaluated table whatever its operands say
 *            — see {@link #readingRows()}
 * @param perVariableMap
 *            whether the call's one value is a <b>per-variable map</b> the per-variable loop
 *            projects onto the variable cursor — see {@link #answeringPerVariable()}
 */
public record FunctionDescriptor(String name, List<Parameter> parameters, FunctionKind kind,
        @Nullable EvalFunction fn, @Nullable ProviderNeed provider, boolean aggregate,
        boolean perRow, boolean perVariableMap)
{

    /**
     * The seven-component shape: no per-variable map unless the flag says so.
     *
     * @param name
     *            the function name
     * @param parameters
     *            the ordered parameter list
     * @param kind
     *            the result shape
     * @param fn
     *            the implementation
     * @param provider
     *            the provider capability, or {@code null}
     * @param aggregate
     *            whether the call raises its operands' level to {@code dataset}
     * @param perRow
     *            whether the call reads the row
     */
    public FunctionDescriptor(String name, List<Parameter> parameters, FunctionKind kind,
            @Nullable EvalFunction fn, @Nullable ProviderNeed provider, boolean aggregate,
            boolean perRow)
    {
        this(name, parameters, kind, fn, provider, aggregate, perRow, false);
    }


    /**
     * The pre-row-reader shape: neither an aggregate nor a row reader unless the flag says so.
     *
     * @param name
     *            the function name
     * @param parameters
     *            the ordered parameter list
     * @param kind
     *            the result shape
     * @param fn
     *            the implementation
     * @param provider
     *            the provider capability, or {@code null}
     * @param aggregate
     *            whether the call raises its operands' level to {@code dataset}
     */
    public FunctionDescriptor(String name, List<Parameter> parameters, FunctionKind kind,
            @Nullable EvalFunction fn, @Nullable ProviderNeed provider, boolean aggregate)
    {
        this(name, parameters, kind, fn, provider, aggregate, false, false);
    }


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
        this(name, parameters, kind, fn, null, false, false, false);
    }


    /**
     * This descriptor declaring a <b>provider capability</b> ({@link ProviderNeed};
     * {@code PLAN-binding-expressions} §5.2) — the one key every provider gate reads for a registry
     * function.
     *
     * @param aProvider
     *            the provider the function needs
     * @return the copy
     */
    public FunctionDescriptor withProvider(ProviderNeed aProvider)
    {
        return new FunctionDescriptor(name, parameters, kind, fn, aProvider, aggregate, perRow,
                perVariableMap);
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
        if (perRow)
        {
            throw new IllegalArgumentException(
                    "function " + name + " cannot be both an aggregate and a row reader");
        }
        return new FunctionDescriptor(name, parameters, kind, fn, provider, true, perRow,
                perVariableMap);
    }


    /**
     * This descriptor declared a <b>row reader</b>: the call answers one value <em>per row of the
     * evaluated table</em> whatever its operands' granularity, because it reads the row through a
     * channel its operands do not show — {@code row_max(name_pattern=)} selects its columns by a
     * static regex and has no column operand at all. The level calculus otherwise sees only the
     * operands (a string literal: {@code dataset}), which is what classified a {@code row_max}
     * binding {@code {}} and handed row 0's maximum to every row (combined review of runbook W2–W8,
     * XCUT H1: CDISC-/PMDA-AD0084 decided from row 0). Stage A, {@link DomainScan} and the
     * hand-over contract all read this flag, so a per-row function without a column operand is
     * declared once, here, instead of by name in three places.
     *
     * @return the copy
     */
    public FunctionDescriptor readingRows()
    {
        if (aggregate)
        {
            throw new IllegalArgumentException(
                    "function " + name + " cannot be both an aggregate and a row reader");
        }
        return new FunctionDescriptor(name, parameters, kind, fn, provider, aggregate, true,
                perVariableMap);
    }


    /**
     * This descriptor declared to answer a <b>per-variable map</b>: one value for the dataset
     * ({@code aggregate}) that is a map from variable name to value
     * ({@code VariableMetadataResult}), which the per-variable loop projects onto the variable
     * cursor — {@code cross_dataset_variable_metadata}'s shape (wave 4b, D-W4b-1). The four readers
     * that used to key this shape on the function's NAME — {@code DomainScan}'s VARIABLE arm,
     * {@code RuleClassifier}'s variable-scoped set, the loader's inline refusal and the runner's
     * per-column hand-over — read the flag (combined review of runbook W2–W8, W3+W4b L5), so a
     * second such function is declared once, here.
     *
     * @return the copy, also an aggregate
     */
    public FunctionDescriptor answeringPerVariable()
    {
        if (perRow)
        {
            throw new IllegalArgumentException(
                    "function " + name + " cannot be both a row reader and a per-variable map");
        }
        return new FunctionDescriptor(name, parameters, kind, fn, provider, true, perRow, true);
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
