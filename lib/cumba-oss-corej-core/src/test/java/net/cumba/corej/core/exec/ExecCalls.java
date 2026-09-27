package net.cumba.corej.core.exec;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.values.GroupKeyPolicy;
import org.jspecify.annotations.Nullable;

/**
 * Test shorthand for the smaller {@code exec} entry points whose defaulting overloads
 * {@code PLAN-retire-dead-multi-match-lookup} U1 removed from {@code src/main}:
 * {@link IndexHelper#groupByPresent} (A17), {@link AbsentDatasetSkip#decide} (A21),
 * {@link KeyMatchRowExpander#expand} (A22) and the {@link Violation} constructors with a row
 * identity but no level / unit (A26). Each forwards the default the removed overload supplied. ⛔
 * Pure forwarders only — see {@link RuleRunnerCalls}.
 */
public final class ExecCalls
{

    private ExecCalls()
    {
    }


    /** {@code groupByPresent} under {@link GroupKeyPolicy#KEEP_MISSING_KEYS}. */
    static IndexHelper.@Nullable Grouping groupByPresent(IDataTable table, List<String> groupCols,
            @Nullable String context)
    {
        return IndexHelper.groupByPresent(table, groupCols, context,
                GroupKeyPolicy.KEEP_MISSING_KEYS);
    }


    static IndexHelper.@Nullable Grouping groupByPresent(IDataTable table, List<String> groupCols,
            @Nullable String context, GroupKeyPolicy policy)
    {
        return IndexHelper.groupByPresent(table, groupCols, context, policy);
    }


    /** {@code decide} with no cross-standard coverage. */
    public static AbsentDatasetSkip.Decision decide(Rule rule, DatasetResolver resolver,
            Set<String> reportedDatasets, @Nullable String primaryDataset,
            @Nullable String domainPrefix)
    {
        return AbsentDatasetSkip.decide(rule, resolver, reportedDatasets, Set.of(), primaryDataset,
                domainPrefix);
    }


    public static AbsentDatasetSkip.Decision decide(Rule rule, DatasetResolver resolver,
            Set<String> reportedDatasets, Set<String> crossStandardDatasets,
            @Nullable String primaryDataset, @Nullable String domainPrefix)
    {
        return AbsentDatasetSkip.decide(rule, resolver, reportedDatasets, crossStandardDatasets,
                primaryDataset, domainPrefix);
    }


    /** {@code expand} with every child index built locally (no shared cache). */
    static KeyMatchRowExpander.@Nullable KeyMatchExpansion expand(IDataTable primaryTable,
            @Nullable List<MatchDataset> matchDatasets, DatasetResolver resolver,
            @Nullable String ruleId)
    {
        return KeyMatchRowExpander.expand(primaryTable, matchDatasets, resolver, ruleId, null);
    }


    static KeyMatchRowExpander.@Nullable KeyMatchExpansion expand(IDataTable primaryTable,
            @Nullable List<MatchDataset> matchDatasets, DatasetResolver resolver,
            @Nullable String ruleId, JoinCache.@Nullable SharedIndexCache aShared)
    {
        return KeyMatchRowExpander.expand(primaryTable, matchDatasets, resolver, ruleId, aShared);
    }


    /** A violation with a row identity, no record key, no level, no unit. */
    public static Violation violation(long row, Map<String, String> values,
            @Nullable String usubjid, @Nullable String seq)
    {
        return new Violation(row, values, usubjid, seq, Map.of(), null, null);
    }


    /** A violation with a row identity and record key, no level, no unit. */
    public static Violation violation(long row, Map<String, String> values,
            @Nullable String usubjid, @Nullable String seq, Map<String, String> keys)
    {
        return new Violation(row, values, usubjid, seq, keys, null, null);
    }


    /** A violation with a level but no finding unit. */
    public static Violation violation(long row, Map<String, String> values,
            @Nullable String usubjid, @Nullable String seq, Map<String, String> keys,
            @Nullable Severity level)
    {
        return new Violation(row, values, usubjid, seq, keys, level, null, null);
    }
}
