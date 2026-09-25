package net.cumba.corej.core.exec;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.metadata.RuntimeDictionaryProvider;
import net.cumba.corej.core.metadata.VlmResolver;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.report.Severity;
import org.jspecify.annotations.Nullable;

/**
 * Test shorthand for {@link RuleRunner}: the convenience overloads the tests call, forwarding to
 * the one production entry point with the defaults the removed overloads used to supply.
 *
 * <p>
 * ⭐ {@code PLAN-retire-dead-multi-match-lookup} U1 (A1, A23, A24) and K5 removed
 * {@code RuleRunner}'s telescoping {@code execute} chain (2 … 13 arguments), the 5-argument
 * {@code stageBGate} and the 4-argument {@code buildJoinedDatasets} from {@code src/main}: no
 * production code called any of them — {@code LibraryValidator.executeRule} calls the terminal
 * 14-argument {@code execute}, the 7-argument gate and the 5-argument join build. They lived on
 * only as test conveniences, which is what this class now is: <b>test infrastructure</b>, in the
 * test tree, forwarding the same defaults ({@code Integer.MAX_VALUE} findings, no caches, no
 * providers, empty coverage sets, {@link EngineLimits#DEFAULT_SEVERITY_THRESHOLD}). A test that
 * needs a different value passes it to the full form.
 * </p>
 *
 * <p>
 * ⛔ Keep every method here a pure forwarder. The engine's behaviour is defined by the production
 * signatures; this class must never hold a default the product does not.
 * </p>
 */
public final class RuleRunnerCalls
{

    private RuleRunnerCalls()
    {
    }


    public static RuleExecutionResult execute(Rule rule, IDataTable table)
    {
        return execute(rule, table, _ -> null);
    }


    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver)
    {
        return execute(rule, table, resolver, null);
    }


    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix)
    {
        return execute(rule, table, resolver, domainPrefix, (MetadataProvider) null);
    }


    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, @Nullable MetadataProvider libraryProvider)
    {
        return execute(rule, table, resolver, domainPrefix, libraryProvider, null);
    }


    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, @Nullable MetadataProvider libraryProvider,
            @Nullable JoinCache joinCache)
    {
        return execute(rule, table, resolver, domainPrefix, libraryProvider, joinCache, null);
    }


    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, @Nullable MetadataProvider libraryProvider,
            @Nullable JoinCache joinCache, @Nullable MetadataProvider defineProvider)
    {
        return execute(rule, table, resolver, domainPrefix, libraryProvider, joinCache,
                defineProvider, Integer.MAX_VALUE);
    }


    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, @Nullable MetadataProvider libraryProvider,
            @Nullable JoinCache joinCache, @Nullable MetadataProvider defineProvider,
            int maxErrorsPerRule)
    {
        return execute(rule, table, resolver, domainPrefix, libraryProvider, joinCache,
                defineProvider, maxErrorsPerRule, null);
    }


    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, @Nullable MetadataProvider libraryProvider,
            @Nullable JoinCache joinCache, @Nullable MetadataProvider defineProvider,
            int maxErrorsPerRule, @Nullable ExpressionResultCache exprCache)
    {
        return execute(rule, table, resolver, domainPrefix, libraryProvider, joinCache,
                defineProvider, maxErrorsPerRule, exprCache, null);
    }


    /** Unlimited findings, no caches — the VLM convenience the former 8-argument overload was. */
    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, @Nullable MetadataProvider libraryProvider,
            @Nullable JoinCache joinCache, @Nullable MetadataProvider defineProvider,
            @Nullable VlmResolver vlmResolver)
    {
        return execute(rule, table, resolver, domainPrefix, libraryProvider, joinCache,
                defineProvider, Integer.MAX_VALUE, null, null, vlmResolver);
    }


    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, @Nullable MetadataProvider libraryProvider,
            @Nullable JoinCache joinCache, @Nullable MetadataProvider defineProvider,
            int maxErrorsPerRule, @Nullable ExpressionResultCache exprCache,
            @Nullable RuntimeDictionaryProvider dictionaryProvider)
    {
        return execute(rule, table, resolver, domainPrefix, libraryProvider, joinCache,
                defineProvider, maxErrorsPerRule, exprCache, dictionaryProvider, null);
    }


    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, @Nullable MetadataProvider libraryProvider,
            @Nullable JoinCache joinCache, @Nullable MetadataProvider defineProvider,
            int maxErrorsPerRule, @Nullable ExpressionResultCache exprCache,
            @Nullable RuntimeDictionaryProvider dictionaryProvider,
            @Nullable VlmResolver vlmResolver)
    {
        return execute(rule, table, resolver, domainPrefix, libraryProvider, joinCache,
                defineProvider, maxErrorsPerRule, exprCache, dictionaryProvider, vlmResolver,
                Set.of());
    }


    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, @Nullable MetadataProvider libraryProvider,
            @Nullable JoinCache joinCache, @Nullable MetadataProvider defineProvider,
            int maxErrorsPerRule, @Nullable ExpressionResultCache exprCache,
            @Nullable RuntimeDictionaryProvider dictionaryProvider,
            @Nullable VlmResolver vlmResolver, Set<String> reportedDatasets)
    {
        return execute(rule, table, resolver, domainPrefix, libraryProvider, joinCache,
                defineProvider, maxErrorsPerRule, exprCache, dictionaryProvider, vlmResolver,
                reportedDatasets, Set.of());
    }


    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, @Nullable MetadataProvider libraryProvider,
            @Nullable JoinCache joinCache, @Nullable MetadataProvider defineProvider,
            int maxErrorsPerRule, @Nullable ExpressionResultCache exprCache,
            @Nullable RuntimeDictionaryProvider dictionaryProvider,
            @Nullable VlmResolver vlmResolver, Set<String> reportedDatasets,
            Set<String> crossStandardDatasets)
    {
        return execute(rule, table, resolver, domainPrefix, libraryProvider, joinCache,
                defineProvider, maxErrorsPerRule, exprCache, dictionaryProvider, vlmResolver,
                reportedDatasets, crossStandardDatasets, EngineLimits.DEFAULT_SEVERITY_THRESHOLD);
    }


    /** The production entry point, passed through so every test call reads the same. */
    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, @Nullable MetadataProvider libraryProvider,
            @Nullable JoinCache joinCache, @Nullable MetadataProvider defineProvider,
            int maxErrorsPerRule, @Nullable ExpressionResultCache exprCache,
            @Nullable RuntimeDictionaryProvider dictionaryProvider,
            @Nullable VlmResolver vlmResolver, Set<String> reportedDatasets,
            Set<String> crossStandardDatasets, Severity severityThreshold)
    {
        return RuleRunner.execute(rule, table, resolver, domainPrefix, libraryProvider, joinCache,
                defineProvider, maxErrorsPerRule, exprCache, dictionaryProvider, vlmResolver,
                reportedDatasets, crossStandardDatasets, severityThreshold);
    }


    /** The stage-B gate without a foreign inventory and with nothing suppressed. */
    static @Nullable RuleExecutionResult stageBGate(Rule rule, IDataTable table,
            boolean concreteContract, @Nullable String ruleId, @Nullable String message)
    {
        return stageBGate(rule, table, concreteContract, ruleId, message, null, Set.of());
    }


    static @Nullable RuleExecutionResult stageBGate(Rule rule, IDataTable table,
            boolean concreteContract, @Nullable String ruleId, @Nullable String message,
            net.cumba.corej.core.expr.typed.@Nullable ForeignDatasetInventory foreignInventory,
            Set<String> suppressedDatasets)
    {
        return RuleRunner.stageBGate(rule, table, concreteContract, ruleId, message,
                foreignInventory, suppressedDatasets);
    }


    /** The join build with no rule id ({@code [?]} in the per-Match_Dataset DEBUG lines). */
    static Map<String, JoinLookup> buildJoinedDatasets(@Nullable List<MatchDataset> matchDatasets,
            IDataTable primaryTable, DatasetResolver resolver, @Nullable JoinCache joinCache)
    {
        return buildJoinedDatasets(matchDatasets, primaryTable, resolver, joinCache, null);
    }


    static Map<String, JoinLookup> buildJoinedDatasets(@Nullable List<MatchDataset> matchDatasets,
            IDataTable primaryTable, DatasetResolver resolver, @Nullable JoinCache joinCache,
            @Nullable String ruleId)
    {
        return RuleRunner.buildJoinedDatasets(matchDatasets, primaryTable, resolver, joinCache,
                ruleId);
    }
}
