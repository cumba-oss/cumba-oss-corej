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
 * {@link IndexHelper#groupByPresent} (A17), {@link AbsentDatasetSkip#decide} (A21) and the
 * {@link Violation} constructors with a row identity but no level / unit (A26). Each forwards the
 * default the removed overload supplied. ⛔ Pure forwarders only — see {@link RuleRunnerCalls}.
 * (Three fixture builders joined them in W6 of {@code PLAN-merged-evaluation-table}:
 * {@link #expand}, the view standing where the retired key-match expander's entry point stood,
 * {@link #viewLookup}, a keyed part's lookup where the retired {@code DatasetLookup} stood, and
 * {@link #joinsView}, a view whose joins are hand-built lookups.)
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


    /**
     * The merged evaluation view of {@code primaryTable} over {@code matchDatasets} alone (no SUPP
     * merge) — the W6 successor of the retired key-match expander's test entry point
     * ({@code PLAN-merged-evaluation-table}): its {@link MergedEvaluation.Built#table() table} and
     * its parts' {@link MergedEvaluation.Built#lookups() lookups} answer what the expansion's did,
     * so the expander's expectations now pin the view. {@code null} when nothing is merged.
     */
    static MergedEvaluation.@Nullable Built expand(IDataTable primaryTable,
            @Nullable List<MatchDataset> matchDatasets, DatasetResolver resolver,
            @Nullable String ruleId)
    {
        return expand(primaryTable, matchDatasets, resolver, ruleId, null);
    }


    /** {@link #expand} through a dataset cache over {@code aShared} (the cached view). */
    static MergedEvaluation.@Nullable Built expand(IDataTable primaryTable,
            @Nullable List<MatchDataset> matchDatasets, DatasetResolver resolver,
            @Nullable String ruleId, JoinCache.@Nullable SharedIndexCache aShared)
    {
        Rule rule = new Rule();
        rule.setMatchDatasets(matchDatasets);
        rule.setSuppMerge(Boolean.FALSE);
        // a MockTable's metadata is real since T1 (the testkit), so the merge copies it directly
        return MergedEvaluation.build(rule, primaryTable, primaryTable, resolver,
                aShared == null ? null : new JoinCache(aShared), ruleId, null);
    }


    /**
     * A context over the merged view {@code rule} builds on {@code primary} — its evaluation table,
     * its parts and its SUPP qualifiers — for a reader test that drives the readers the way
     * {@code RuleRunner} does (W6 of {@code PLAN-merged-evaluation-table}: the view is the one
     * source of joins and qualifiers; a context without one has neither). Read the table back from
     * the built context.
     *
     * @throws IllegalArgumentException
     *             when the rule merges nothing on this data
     */
    public static EvaluationContext.EvaluationContextBuilder viewContext(Rule rule,
            IDataTable primary, DatasetResolver resolver, @Nullable String domain)
    {
        MergedEvaluation.Built built = MergedEvaluation.build(rule, primary, primary, resolver,
                null, "R", domain);
        if (built == null)
        {
            throw new IllegalArgumentException("the fixture merges nothing");
        }
        return EvaluationContext.builder().table(built.table()).mergedView(built)
                .datasetResolver(resolver).suppMerge(rule.isSuppMergeEnabled());
    }


    /**
     * The merged view's lookup of one {@code left} keyed part — {@code joined} under {@code name},
     * keyed on {@code keys} — over {@code primary}: the W6 successor of the retired
     * {@code DatasetLookup.build} fixture. The view reads the merged row, so the fixture must not
     * expand a row (every primary row meets at most one partner): its rows ARE the primary's, and a
     * reader asked at primary row {@code i} reads the partner of row {@code i}.
     *
     * @throws IllegalArgumentException
     *             when the fixture expands a row (a duplicate partner key)
     */
    public static JoinLookup viewLookup(IDataTable primary, String name, IDataTable joined,
            List<String> keys)
    {
        MatchDataset md = new MatchDataset();
        md.setName(name);
        md.setKeys(keys);
        md.setJoinType("left");
        MergedEvaluation.Built built = expand(primary, List.of(md),
                n -> n.equalsIgnoreCase(name) ? joined : null, "FIXTURE");
        if (built == null)
        {
            throw new IllegalArgumentException("the fixture merged nothing");
        }
        if (built.view().isRowMapped())
        {
            throw new IllegalArgumentException("the fixture expands a row: its view rows are not"
                    + " the primary's — read it through a view context instead");
        }
        return java.util.Objects.requireNonNull(built.lookups().get(name));
    }


    /**
     * A view whose joins are {@code joins} — test lookups standing for the merged view's parts
     * ({@link EvaluationContext#joins}), for the reader tests that drive a reader with a hand-built
     * {@link JoinLookup} ({@code .mergedView(ExecCalls.joinsView(joins))} where a context used to
     * take {@code .joinedDatasets(joins)} before W6 of {@code PLAN-merged-evaluation-table}). The
     * table behind them is an empty stand-in, never the context's, so the view serves no SUPP
     * qualifier and is never the dataset's shared one — only the joins are read.
     */
    public static MergedEvaluation.Built joinsView(Map<String, ? extends JoinLookup> joins)
    {
        return joinsView(RealTables.of("JOINS").str("_").build(), joins);
    }


    /** {@link #joinsView(Map)} over {@code table}'s own rows (the view's evaluation table). */
    public static MergedEvaluation.Built joinsView(IDataTable table,
            Map<String, ? extends JoinLookup> joins)
    {
        net.cumba.datatable.merge.MergedTable alone = net.cumba.datatable.merge.MergedTables
                .build(net.cumba.datatable.merge.MergeRequest.builder().primary(table)
                        .resolver(_ -> null).build());
        return new MergedEvaluation.Built(alone, table, new java.util.LinkedHashMap<>(joins),
                List.of(), Set.of(), Map.of(), false, false, false);
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
