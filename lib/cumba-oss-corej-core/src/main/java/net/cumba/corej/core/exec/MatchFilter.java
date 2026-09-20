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
 * Both join shapes route through {@link #apply}: {@code KeyMatchRowExpander} filters the child
 * before building its key index, and {@code RuleRunner.buildJoinedDatasets} filters the joined
 * table before {@link DatasetLookup} indexes it (bypassing the {@link JoinCache} — a filtered index
 * is per-rule state and must never be shared with unfiltered joins of the same dataset). The filter
 * expression is the ordinary Check expression language, compiled and evaluated by the native
 * evaluator over a minimal context on the joined table; stage A guarantees it references only the
 * joined side's plain columns, and stage B has already decided the unresolvable-column question
 * (D89), so an exception escaping here is an engine defect surfacing on the rule's ERROR channel —
 * loud, never a silently unapplied filter.
 * </p>
 */
final class MatchFilter
{

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
        if (right == null)
        {
            return null;
        }
        Expr filter = md.filterExpr();
        if (filter == null)
        {
            return right;
        }
        // D76: the filter is its own expression, so its own type expectations decide the
        // absent-column default (numeric-expected ⇒ MissingValue.MIS, otherwise "").
        EvaluationContext ctx = EvaluationContext.builder().table(right).ruleId(ruleId)
                .domainName(right.getMetaData().getName())
                .numericExpectedColumns(net.cumba.corej.core.expr.typed.TypeExpectations
                        .of(java.util.List.of(filter)).numericDefaultColumns())
                .build();
        BitSet keep = NativeExprEvaluator.evaluate(filter, ctx);
        int rowCount = Math.toIntExact(right.getRowCount());
        if (keep.cardinality() == rowCount)
        {
            return right; // nothing dropped — no view needed, byte-identical to no filter
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

}
