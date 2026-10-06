package net.cumba.corej.core.expr.eval;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.GroupReadTable;
import net.cumba.corej.core.exec.RealRows;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import org.jspecify.annotations.Nullable;

/**
 * ⭐ OD-6 ({@code PLAN-merged-evaluation-table} §3.8, W3e; owner 2026-10-04): an aggregate or
 * uniqueness operator whose operands are all <b>primary</b> columns counts each primary record
 * <b>once — by real row</b>, never by value; an aggregate that reads a merged column still sees
 * every merged row.
 *
 * <p>
 * A one-to-many merge gives a primary record one evaluation row per related record (D72
 * uniformity), so an operator over the expanded rows sees the record several times:
 * {@code is_unique_set([MIDSTYPE, USUBJID])} on SM joined {@code n:m} to TM fires whenever TM
 * repeats a MIDSTYPE ({@code CDISC-CG0545}), {@code record_count()} is inflated, and
 * {@code is_last_in_group} calls the earlier copies of the last record "not last"
 * ({@code CDISC-SEND-0127}, {@code SEND-0283}). Two distinct records equal in every operand stay
 * two — that is the true positive the uniqueness operators exist for.
 * </p>
 *
 * <p>
 * <b>How.</b> One wrapper around the compiled plan of every grouped / uniqueness operator and of
 * the grouped aggregates ({@link #BOOLEAN_OPERATORS}, {@link #VALUE_AGGREGATES}): when the
 * evaluated table repeats a real row ({@link RealRows#ofRepeating}) and every name the call reads
 * is a column of the primary itself (or resolves nowhere — the absent-column machinery is
 * row-independent), the operator is evaluated over {@link RealRows#table()} — one row per primary
 * record the merge kept, in record order — and its answer is handed to every merged row of that
 * record. Exactly the answer the operator gives over the records themselves; the evaluated table,
 * its row count and its row order are untouched, so a non-expanded table is provably unchanged (the
 * wrapper hands the plan straight through).
 * </p>
 *
 * <p>
 * <b>What counts as a merged column</b> (the operator then runs over every merged row, as before):
 * a qualified {@code J.X} or {@code J._matched_}, a {@code SUPP--} qualifier read bare (a column of
 * the SUPP part, not of the primary — the ruling's words), a {@code $} reference (its value can
 * read either), a {@code *} / {@code **} pattern, a {@code domain=} call (its operands are another
 * dataset's; the record side only keys the broadcast, which reads the same answer for every copy of
 * a record anyway), and a <b>dynamic read</b> — {@code colref} or any other declared row reader
 * ({@link FunctionDescriptor#readingRows()}): the column it reads is known per row only, so it can
 * be a merged column (T2 review MED-2: {@code record_count(group=[USUBJID],
 * filter=(colref("CM.CMTRT") == "ASPIRIN"))} counted another record's partner, silently).
 * </p>
 *
 * <p>
 * ⚠ The reduced evaluation runs <b>without the merged view</b>: its rows are the distinct real
 * rows, not the view's, so nothing on it may read a part (a lookup is positional on the view's
 * rows). Only the primary's own columns are read there, which the checks above guarantee; a read
 * that slipped past them finds no join and no qualifier rather than another record's cells.
 * </p>
 *
 * <p>
 * The {@code $} / {@code *} / {@code **} operands are the gap OD-6 cannot close statically (the
 * operator then counts merged rows): the stage-A lint {@code AGGREGATE_OVER_REPEATED_ROWS}
 * (observe-only) reports them on a rule whose view can repeat a record.
 * </p>
 *
 * <p>
 * Costs: the check reads the call's names once per evaluation; the projection is computed once per
 * cached view ({@link RealRows}); the reduced evaluation runs over fewer rows than the expanded
 * one; the hand-back is one pass over the merged rows (a bit per row, or one lazily mapped vector).
 * </p>
 */
final class RealRowAggregates
{

    /**
     * The boolean grouped / uniqueness operators (and their positive twins). Not
     * {@code inconsistent_enumerated_columns}: it reads a column family beyond its written operand
     * (the base name suffixed 1, 2, …) and judges each row on its own, so no count is involved.
     */
    static final Set<String> BOOLEAN_OPERATORS = GroupReadTable.REAL_ROW_OPERATORS;

    /**
     * The value aggregates: the grouped extremes, {@code record_count}, {@code distinct}. Not
     * {@code read_value}: it always names its dataset ({@code domain=}), so it reads no primary
     * column to count by real row (T2 review LOW-2).
     */
    static final Set<String> VALUE_AGGREGATES = Set.of("max", "max_date", "min_date",
            "record_count", "distinct");

    private RealRowAggregates()
    {
    }


    /**
     * The real-row form of a boolean operator's plan.
     *
     * @param c
     *            the call
     * @param inner
     *            its compiled plan
     * @return a plan answering per primary record on an expanded table
     */
    static ExprProgram.BoolPlan bool(Expr.Call c, ExprProgram.BoolPlan inner)
    {
        @Nullable
        List<String> names = primaryNames(c);
        if (names == null)
        {
            return inner;
        }
        return run ->
        {
            RealRows real = reducible(run, names);
            if (real == null)
            {
                return inner.eval(run);
            }
            BitSet perRecord = inner.eval(reduced(run, real));
            BitSet out = new BitSet(run.rowCount());
            for (int row = 0; row < run.rowCount(); row++)
            {
                if (perRecord.get(real.representative(row)))
                {
                    out.set(row);
                }
            }
            return out;
        };
    }


    /**
     * The real-row form of a value aggregate's plan.
     *
     * @param c
     *            the call
     * @param inner
     *            its compiled plan
     * @return a plan answering per primary record on an expanded table
     */
    static ExprCompiler.ValuePlan value(Expr.Call c, ExprCompiler.ValuePlan inner)
    {
        @Nullable
        List<String> names = primaryNames(c);
        if (names == null)
        {
            return inner;
        }
        return run ->
        {
            RealRows real = reducible(run, names);
            if (real == null)
            {
                return inner.eval(run);
            }
            Vector perRecord = inner.eval(reduced(run, real));
            if (perRecord == null || perRecord instanceof ConstVector)
            {
                // One answer for the whole table (an ungrouped count, an absent target): the same
                // for every merged row.
                return perRecord;
            }
            return ComputedVector.remapped(perRecord, run.rowCount(), real::representative);
        };
    }


    /** The run over the projection: the same context on the table of the distinct real rows. */
    private static EvalRun reduced(EvalRun run, RealRows real)
    {
        // without the merged view (MED-2): a part's lookup is positional on the view's rows, and
        // these rows are the projection's
        EvaluationContext reducedCtx = run.ctx().toBuilder().table(real.table()).mergedView(null)
                .build();
        return new EvalRun(reducedCtx, 0, real.count());
    }


    /**
     * The projection when this evaluation can count by real row: the table repeats a real row and
     * every name the call reads is a column of the primary or resolves nowhere; else {@code null}.
     */
    private static @Nullable RealRows reducible(EvalRun run, List<String> names)
    {
        EvaluationContext ctx = run.ctx();
        RealRows real = RealRows.ofRepeating(ctx.getTable());
        if (real == null || run.rowCount() > ctx.rowCount())
        {
            return null;
        }
        return GroupReadTable.readsOnlyItsOwnColumns(ctx, names) ? real : null;
    }


    /**
     * The bare column names the call reads, or {@code null} when it reads something that is never a
     * primary column (a qualified or {@code _matched_} reference, a {@code $} reference, a pattern)
     * or names another dataset ({@code domain=}).
     */
    private static @Nullable List<String> primaryNames(Expr.Call c)
    {
        if (c.kwargs().containsKey("domain"))
        {
            return null;
        }
        List<String> out = new ArrayList<>();
        return collect(c, out) ? List.copyOf(out) : null;
    }


    private static boolean collect(Expr e, List<String> out)
    {
        return switch (e)
        {
        case Expr.Ref r -> collectRef(r, out);
        case Expr.Lit lit -> collectLiteral(lit, out);
        case Expr.Call call -> !call.kwargs().containsKey("domain") && !readsDynamically(call)
                && call.args().stream().allMatch(a -> collect(a, out))
                && call.kwargs().values().stream().allMatch(a -> collect(a, out));
        case Expr.Binary b -> collect(b.left(), out) && collect(b.right(), out);
        case Expr.And a -> a.parts().stream().allMatch(p -> collect(p, out));
        case Expr.Or o -> o.parts().stream().allMatch(p -> collect(p, out));
        case Expr.Not n -> collect(n.inner(), out);
        };
    }


    /**
     * Whether a call reads a column chosen per row — {@code colref} and every other declared row
     * reader ({@link FunctionDescriptor#readingRows()}, e.g. {@code row_max}): which column it
     * reads is not a name of the call, so it may be a merged one (MED-2).
     */
    private static boolean readsDynamically(Expr.Call call)
    {
        FunctionDescriptor descriptor = FunctionRegistry.descriptor(call.name());
        return descriptor != null && descriptor.perRow();
    }


    private static boolean collectRef(Expr.Ref r, List<String> out)
    {
        OperandKind kind = r.kind();
        if (kind == OperandKind.COLUMN)
        {
            out.add(r.name());
            return true;
        }
        // A built-in (variable_name, a dataset fact) reads no column of either side.
        return kind == OperandKind.BUILTIN;
    }


    private static boolean collectLiteral(Expr.Lit lit, List<String> out)
    {
        if (lit.kind() != Expr.LitKind.LIST)
        {
            return true;
        }
        Object value = lit.value();
        if (!(value instanceof List<?> items))
        {
            return true;
        }
        for (Object item : items)
        {
            if (item instanceof Expr e && !collect(e, out))
            {
                return false;
            }
        }
        return true;
    }

}
