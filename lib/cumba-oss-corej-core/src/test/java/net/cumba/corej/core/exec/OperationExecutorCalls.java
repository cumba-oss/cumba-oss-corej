package net.cumba.corej.core.exec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.metadata.RuntimeDictionaryProvider;
import net.cumba.corej.core.model.Operation;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;

/**
 * Test shorthand for {@link OperationExecutor}: the multi-Operation driver and the short
 * {@code executeOne} / {@code resolvePrefixes} forms the tests call, forwarding to the production
 * entry points with the defaults the removed overloads used to supply.
 *
 * <p>
 * ⭐ {@code PLAN-retire-dead-multi-match-lookup} U1 (A2, A3, A4) removed them from {@code src/main}:
 * production runs Operations one at a time through {@code RuleRunner}'s lazy wrapper, which calls
 * the 8-argument {@code executeOne}, and resolves {@code --} through the three-argument
 * {@code resolvePrefixes} ({@code RuleSpecialiser.specialise},
 * {@code ExprCompiler.inlineOperationResult}). The list driver and the defaulting forms had test
 * callers only, which is what this class is: test infrastructure, in the test tree.
 * </p>
 *
 * <p>
 * ⛔ Pure forwarders only — see {@link RuleRunnerCalls}.
 * </p>
 */
public final class OperationExecutorCalls
{

    private OperationExecutorCalls()
    {
    }


    /** Executes the Operations in order, feeding each its predecessors' results, no library. */
    public static Map<String, Object> execute(List<Operation> operations, IDataTable table,
            DatasetResolver resolver)
    {
        return execute(operations, table, resolver, null);
    }


    /**
     * Executes the Operations in order over one prior-results map: an Operation's result is stored
     * under its id and visible to the Operations after it (the former multi-op driver, verbatim).
     */
    public static Map<String, Object> execute(List<Operation> operations, IDataTable table,
            DatasetResolver resolver, @Nullable MetadataProvider libraryProvider)
    {
        Map<String, Object> variables = new LinkedHashMap<>();
        for (Operation op : operations)
        {
            Object result = executeOne(op, table, resolver, libraryProvider, variables);
            if (result != null && op.getId() != null)
            {
                variables.put(op.getId(), result);
            }
        }
        return variables;
    }


    public static @Nullable Object executeOne(Operation op, IDataTable table,
            DatasetResolver resolver, @Nullable MetadataProvider libraryProvider,
            Map<String, Object> priorResults)
    {
        return executeOne(op, table, resolver, libraryProvider, priorResults, null);
    }


    public static @Nullable Object executeOne(Operation op, IDataTable table,
            DatasetResolver resolver, @Nullable MetadataProvider libraryProvider,
            Map<String, Object> priorResults, @Nullable String ruleId)
    {
        return executeOne(op, table, resolver, libraryProvider, priorResults, ruleId, null);
    }


    public static @Nullable Object executeOne(Operation op, IDataTable table,
            DatasetResolver resolver, @Nullable MetadataProvider libraryProvider,
            Map<String, Object> priorResults, @Nullable String ruleId,
            @Nullable RuntimeDictionaryProvider dictionaryProvider)
    {
        return executeOne(op, table, resolver, libraryProvider, priorResults, ruleId,
                dictionaryProvider, null);
    }


    /** The production entry point, passed through. */
    public static @Nullable Object executeOne(Operation op, IDataTable table,
            DatasetResolver resolver, @Nullable MetadataProvider libraryProvider,
            Map<String, Object> priorResults, @Nullable String ruleId,
            @Nullable RuntimeDictionaryProvider dictionaryProvider,
            @Nullable MetadataProvider defineProvider)
    {
        return OperationExecutor.executeOne(op, table, resolver, libraryProvider, priorResults,
                ruleId, dictionaryProvider, defineProvider);
    }


    /**
     * {@code --} resolution with a {@code null} variable prefix, i.e. the Fix #33
     * SUPP/SQAP-stripped domain prefix for the variable fields too — the pre-EC-36 contract the
     * former two-argument overload kept.
     */
    public static Operation resolvePrefixes(Operation op, @Nullable String prefix)
    {
        return resolvePrefixes(op, prefix, null);
    }


    /** The production entry point, passed through. */
    public static Operation resolvePrefixes(Operation op, @Nullable String domainCodePrefix,
            @Nullable String variablePrefix)
    {
        return OperationExecutor.resolvePrefixes(op, domainCodePrefix, variablePrefix);
    }
}
