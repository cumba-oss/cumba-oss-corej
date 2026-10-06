package net.cumba.corej.core.exec;

import java.util.BitSet;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.NativeExprEvaluator;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;

/**
 * The runtime half of the {@code Match_Datasets} pre-merge {@code Filter} (phase 5b-J — spec §3.3 /
 * D88c, {@code PLAN-join-match-flag.md} §8): evaluates the entry's boolean filter against the
 * joined dataset's <b>own</b> rows and hands back a row-projected view holding only the passing
 * rows — <b>before</b> any key index is built, so a dropped row can never become a join partner.
 *
 * <p>
 * The merged evaluation view ({@code MergedEvaluation}) takes the {@link #mask} and hands it to the
 * datatable's shared matching as the keyed part's partner mask, applied at probe time over an index
 * of the <b>unfiltered</b> table — which is what lets filtered and unfiltered joins share one index
 * ({@code PLAN-keymatch-shared-join-index} Q1) — so a dropped row can never become a join partner.
 * (The filtered row projection the retired key-join lookups indexed went with them in W6 of
 * {@code PLAN-merged-evaluation-table}.) The filter expression is the ordinary Check expression
 * language, compiled and evaluated by the native evaluator over a minimal context on the joined
 * table; stage A guarantees it references only the joined side's plain columns, and stage B has
 * already decided the unresolvable-column question (D89), so an exception escaping here is an
 * engine defect surfacing on the rule's ERROR channel — loud, never a silently unapplied filter.
 * </p>
 */
final class MatchFilter
{

    private MatchFilter()
    {
    }


    /**
     * The rows of {@code right} passing the entry's {@code Filter}, as a mask over {@code right}'s
     * <b>own</b> row numbers: {@code null} when the entry declares no filter or every row passes,
     * so {@code null} always means "no row is dropped".
     *
     * <p>
     * ⭐ A mask rather than a filtered copy ({@code PLAN-keymatch-shared-join-index} Q1/D3): the
     * partner's index is shared across rules and built over the <b>unfiltered</b> table, so the
     * filter is applied per rule by skipping the matches the mask does not keep — the passing rows
     * in ascending order, exactly the rows a filtered copy would have matched, in the same order.
     * </p>
     *
     * <p>
     * The filter's inventory functions ({@code dataset_names()}, {@code study_domains()}) read the
     * study's datasets through {@code study}, and its CDISC Library functions read {@code library}
     * (combined review of runbook W2–W8, W4 L3: without them such a filter hit the loud
     * no-inventory arm, D-W4-5, or an unusable-provider throw).
     * </p>
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
        // (PLAN-operation-replacements §2.3 / §7) — a bare filter name is a column of the JOINED
        // dataset, never a qualifier of its own SUPP-- (this context carries no merged view).
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
