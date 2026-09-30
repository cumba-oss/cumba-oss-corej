package net.cumba.corej.core.exec;

import java.util.BitSet;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.NativeExprEvaluator;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.databuffer.DataBufferFactory;
import net.cumba.datatable.impl.databuffer.IDataBufferNumeric;
import org.jspecify.annotations.Nullable;

/**
 * The runtime half of the {@code Match_Datasets} pre-merge {@code Filter} (phase 5b-J — spec §3.3 /
 * D88c, {@code PLAN-join-match-flag.md} §8): evaluates the entry's boolean filter against the
 * joined dataset's <b>own</b> rows and hands back a row-projected view holding only the passing
 * rows — <b>before</b> any key index is built, so a dropped row can never become a join partner.
 *
 * <p>
 * Both join shapes evaluate the filter here. {@code RuleRunner.buildJoinedDatasets} filters the
 * joined table through {@link #apply} before {@link DatasetLookup} indexes it (bypassing the
 * {@link JoinCache} — that filtered index is per-rule state and must never be shared with
 * unfiltered joins of the same dataset). {@code KeyMatchRowExpander} instead takes the
 * {@link #mask} and applies it per rule over a child index built on the <b>unfiltered</b> table,
 * which is what lets filtered and unfiltered joins share one index
 * ({@code PLAN-keymatch-shared-join-index} Q1). Either way a dropped row can never become a join
 * partner. The filter expression is the ordinary Check expression language, compiled and evaluated
 * by the native evaluator over a minimal context on the joined table; stage A guarantees it
 * references only the joined side's plain columns, and stage B has already decided the
 * unresolvable-column question (D89), so an exception escaping here is an engine defect surfacing
 * on the rule's ERROR channel — loud, never a silently unapplied filter.
 * </p>
 */
final class MatchFilter
{

    /** No study: the sub-context of a caller that has no run resolver to hand over. */
    private static final DatasetResolver NO_STUDY = name -> null;

    private MatchFilter()
    {
    }


    /**
     * The joined table restricted to the rows passing the entry's {@code Filter}: the table itself
     * when the entry declares no filter (or every row passes), {@code null} when {@code right} is
     * {@code null}, and otherwise a row-projected view.
     *
     * @param md
     *            the {@code Match_Datasets} entry
     * @param right
     *            the resolved joined dataset, or {@code null} when unavailable
     * @param ruleId
     *            rule id for diagnostics
     * @return the effective joined table for index building
     */
    static @Nullable IDataTable apply(MatchDataset md, @Nullable IDataTable right,
            @Nullable String ruleId)
    {
        return apply(md, right, ruleId, NO_STUDY, null);
    }


    /**
     * {@link #apply(MatchDataset, IDataTable, String)} with the run's study: the filter's inventory
     * functions ({@code dataset_names()}, {@code study_domains()}) read the study's datasets
     * through {@code study}, and its CDISC Library functions read {@code library} — the sub-context
     * carried neither, so such a filter hit the loud no-inventory arm (D-W4-5) or an
     * unusable-provider throw (combined review of runbook W2–W8, W4 L3).
     *
     * @param md
     *            the {@code Match_Datasets} entry
     * @param right
     *            the resolved joined dataset, or {@code null} when unavailable
     * @param ruleId
     *            rule id for diagnostics
     * @param study
     *            the run's dataset resolver
     * @param library
     *            the run's CDISC Library provider, or {@code null}
     * @return the effective joined table for index building
     */
    static @Nullable IDataTable apply(MatchDataset md, @Nullable IDataTable right,
            @Nullable String ruleId, DatasetResolver study, @Nullable MetadataProvider library)
    {
        if (right == null)
        {
            return null;
        }
        BitSet keep = mask(md, right, ruleId, study, library);
        if (keep == null)
        {
            return right; // no filter, or nothing dropped — byte-identical to no filter
        }
        IDataBufferNumeric rowMap = DataBufferFactory.get().createForRange(0,
                Math.max(0, right.getRowCount() - 1L));
        rowMap.setExpectedSize(keep.cardinality());
        for (int r = keep.nextSetBit(0); r >= 0; r = keep.nextSetBit(r + 1))
        {
            rowMap.addValue(r);
        }
        return new RelrecExpandedTable(right, rowMap);
    }


    /**
     * The rows of {@code right} passing the entry's {@code Filter}, as a mask over {@code right}'s
     * <b>own</b> row numbers: {@code null} when the entry declares no filter or every row passes,
     * so {@code null} always means "no row is dropped".
     *
     * <p>
     * ⭐ The key-match path uses this instead of {@link #apply}
     * ({@code PLAN-keymatch-shared-join-index} Q1/D3): its child index is shared across rules and
     * built over the <b>unfiltered</b> table, so the filter is applied per rule by skipping the
     * matches the mask does not keep. The view {@link #apply} builds keeps the passing rows in
     * ascending order, so skipping rows in an ascending match list selects exactly the rows the
     * view would have matched, in the same order.
     * </p>
     *
     * @param md
     *            the {@code Match_Datasets} entry
     * @param right
     *            the resolved joined dataset
     * @param ruleId
     *            rule id for diagnostics
     * @return the kept rows, or {@code null} when none is dropped
     */
    static @Nullable BitSet mask(MatchDataset md, IDataTable right, @Nullable String ruleId)
    {
        return mask(md, right, ruleId, NO_STUDY, null);
    }


    /**
     * {@link #mask(MatchDataset, IDataTable, String)} with the run's study (see
     * {@link #apply(MatchDataset, IDataTable, String, DatasetResolver, MetadataProvider)}).
     *
     * @param md
     *            the {@code Match_Datasets} entry
     * @param right
     *            the resolved joined dataset
     * @param ruleId
     *            rule id for diagnostics
     * @param study
     *            the run's dataset resolver
     * @param library
     *            the run's CDISC Library provider, or {@code null}
     * @return the kept rows, or {@code null} when none is dropped
     */
    static @Nullable BitSet mask(MatchDataset md, IDataTable right, @Nullable String ruleId,
            DatasetResolver study, @Nullable MetadataProvider library)
    {
        Expr filter = md.filterExpr();
        if (filter == null)
        {
            return null;
        }
        // D76: the filter is its own expression, so its own type expectations decide the
        // absent-column default (numeric-expected ⇒ MissingValue.MIS, otherwise "").
        // suppMerge(false): the declared SUPP merge serves the PRIMARY table only
        // (PLAN-operation-replacements §2.3 / §7); with the study's resolver in hand the pivot
        // would otherwise read the JOINED dataset's own SUPP-- for a bare filter name.
        net.cumba.corej.core.expr.typed.TypeExpectations expectations = net.cumba.corej.core.expr.typed.TypeExpectations
                .of(java.util.List.of(filter));
        EvaluationContext ctx = EvaluationContext.builder().table(right).ruleId(ruleId)
                .suppMerge(false).datasetResolver(study).libraryProvider(library)
                .domainName(right.getMetaData().getName())
                .numericExpectedColumns(expectations.numericDefaultColumns())
                .numericExpectedDynamicSites(expectations.numericDynamicSites()).build();
        BitSet keep = NativeExprEvaluator.evaluate(filter, ctx);
        int rowCount = Math.toIntExact(right.getRowCount());
        return keep.cardinality() == rowCount ? null : keep;
    }

}
