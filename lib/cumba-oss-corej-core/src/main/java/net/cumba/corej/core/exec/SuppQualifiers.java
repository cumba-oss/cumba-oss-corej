package net.cumba.corej.core.exec;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.databuffer.DataBufferFactory;
import net.cumba.datatable.impl.databuffer.IDataBufferNumeric;
import net.cumba.datatable.merge.MergeRequest;
import net.cumba.datatable.merge.MergedTables;
import net.cumba.datatable.merge.SuppMergeSpec;
import org.jspecify.annotations.Nullable;

/**
 * The supplemental qualifiers a dataset's SUPP sources deliver — the {@code QNAM} set of the merged
 * schema's SUPP parts ({@code PLAN-merged-evaluation-table} W3d, M8 / MQ9), read for the EXISTENCE
 * surfaces: the dotted {@code var_exists} of an undeclared dataset, a
 * {@code Requirements.Variables} entry ({@link ScopeVariableSource}), the scope gate. The sources
 * are the one chooser's ({@link SplitDomainResolution#suppMergeSources}, D4 (b) of
 * {@code PLAN-split-supp-resolution}): the dataset's own {@code SUPP<dataset>} and the domain-level
 * members of its {@code SUPP<domain>} / {@code SQAP<xx>} family — exactly the datasets the merged
 * view feeds, so what this set reports present is what a value read finds. The set is the union
 * over the members of what the datatable's shared matching keeps once the row maps are built (W1:
 * the index is released, the QNAM set stays), each taken from a merge of the member over the
 * dataset's <b>empty</b> primary — one pass over the SUPP table, no row map — and memoised per
 * member for the run through {@link JoinCache.SharedIndexCache}. The members are read one by one,
 * never as a union, so an {@code Invalid} family (a type clash across members) still answers its
 * members' qualifiers here; the view refuses it (D3). (The engine's own QNAM index retired with the
 * SUPP pivot in W6.)
 */
final class SuppQualifiers
{

    private SuppQualifiers()
    {
    }


    /**
     * The qualifiers delivered for {@code table}: the union over its SUPP sources
     * ({@link SplitDomainResolution#suppMergeSources}).
     *
     * @param table
     *            the dataset (the primary, or a foreign dataset named by a qualified entry)
     * @param resolver
     *            the run's resolver
     * @param cache
     *            the run's shared index cache, or {@code null} to build uncached
     * @param ruleId
     *            for messages
     * @return the QNAM set, possibly empty
     */
    static Set<String> of(IDataTable table, DatasetResolver resolver,
            JoinCache.@Nullable SharedIndexCache cache, @Nullable String ruleId)
    {
        String dataset = table.getMetaData().getName();
        if (dataset == null || dataset.isEmpty())
        {
            return Set.of();
        }
        String domain = DatasetIdentity.domainOfDataset(dataset, resolver);
        return domain == null || domain.isEmpty() ? Set.of()
                : of(table, dataset, domain, resolver, cache, ruleId);
    }


    /**
     * {@link #of(IDataTable, DatasetResolver, JoinCache.SharedIndexCache, String)} for a dataset
     * named by a qualifier that the study may not ship ({@code var_exists("AE.AETRTEM")} on a
     * submission with SUPPAE and no AE — the Check-side scan this keeps): {@code table} is the
     * dataset when it resolves, else {@code null}, and the SUPP table then stands in as the base of
     * the (row-less) matching.
     *
     * @param table
     *            the dataset, or {@code null} when the study does not ship it
     * @param dataset
     *            its name as written
     * @param domain
     *            its domain code
     * @param resolver
     *            the run's resolver
     * @param cache
     *            the run's shared index cache, or {@code null}
     * @param ruleId
     *            for messages
     * @return the QNAM set, possibly empty
     */
    static Set<String> of(@Nullable IDataTable table, String dataset, String domain,
            DatasetResolver resolver, JoinCache.@Nullable SharedIndexCache cache,
            @Nullable String ruleId)
    {
        Set<String> out = null;
        List<IDataTable> sources = SplitDomainResolution.suppMergeSources(resolver, table, dataset,
                domain, ruleId);
        for (IDataTable supp : sources)
        {
            Set<String> qnams = ofSupp(table != null ? table : supp, domain, supp, cache);
            if (out == null)
            {
                out = new LinkedHashSet<>(qnams);
            }
            else
            {
                out.addAll(qnams);
            }
        }
        return out == null ? Set.of() : out;
    }


    /**
     * The {@code QNAM} set of one SUPP table as the merged schema lists it for {@code table},
     * memoised per SUPP table for the run — every {@code QNAM} the table carries, whether or not it
     * became a column of the base (P-10 skips a name the base owns as a column, the existence set
     * keeps it), so the set does not depend on the base ({@code PLAN-split-supp-resolution}
     * confirmation look F3, measured false).
     *
     * @param table
     *            the dataset the SUPP qualifies
     * @param domain
     *            its domain code
     * @param supp
     *            the resolved SUPP table
     * @param cache
     *            the run's shared index cache, or {@code null}
     * @return the QNAM set, possibly empty
     */
    static Set<String> ofSupp(IDataTable table, String domain, IDataTable supp,
            JoinCache.@Nullable SharedIndexCache cache)
    {
        return cache != null ? cache.getOrBuildSuppQnams(supp, _ -> build(table, domain, supp))
                : build(table, domain, supp);
    }


    private static Set<String> build(IDataTable table, String domain, IDataTable supp)
    {
        // the dataset with no rows: the matching derives the QNAM set from the SUPP table alone
        IDataBufferNumeric empty = DataBufferFactory.get().createForRange(0,
                Math.max(0, table.getRowCount() - 1));
        empty.setExpectedSize(0);
        IDataTable primary = new RowMappedTable(table, empty);
        String suppName = Objects.requireNonNullElse(supp.getMetaData().getName(), "");
        MergeRequest request = MergeRequest.builder().primary(primary).primaryDomain(domain)
                .supp(SuppMergeSpec.builder().suppDataset(suppName).build())
                .resolver(name -> name.equalsIgnoreCase(suppName) ? supp : null).matchedFlags(false)
                .build();
        return Set.copyOf(MergedTables.build(request).suppQnams());
    }
}
