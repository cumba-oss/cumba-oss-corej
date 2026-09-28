package net.cumba.corej.core.expr.eval;

import net.cumba.corej.core.exec.EvaluationContext;

/**
 * Per-evaluation run state handed to {@link EvalFunction}s and the compiled program. Carries the
 * {@link EvaluationContext} (table, resolved {@code $}-operations, joins, metadata) and the row
 * range {@code [from, to)} the current evaluation spans.
 *
 * <p>
 * v1 always passes the full range ({@code from == 0}, {@code to == rowCount}); the range parameters
 * are kept as a cheap seam should paged/chunked loading ever arrive (decision #9). A {@code null}
 * context is permitted for pure-function unit tests that only need {@link #rowCount()}.
 * </p>
 */
public final class EvalRun
{

    private final EvaluationContext ctx;

    private final int from;

    private final int to;

    public EvalRun(EvaluationContext ctx, int from, int to)
    {
        this.ctx = ctx;
        this.from = from;
        this.to = to;
    }


    /** Builds a full-range run over {@code ctx}'s table. */
    public static EvalRun fullRange(EvaluationContext ctx)
    {
        return new EvalRun(ctx, 0, ctx.rowCount());
    }


    /** Builds a context-free run spanning {@code [0, rowCount)} for unit tests. */
    // Intentionally passes a null context: this factory is for pure-function unit tests that
    // only read rowCount() and never touch ctx(). Production code always constructs EvalRun with
    // a real context, so the field is kept @NonNull for the many ctx() deref call sites.
    @SuppressWarnings("NullAway")
    public static EvalRun ofRowCount(int rowCount)
    {
        return new EvalRun(null, 0, rowCount);
    }


    public EvaluationContext ctx()
    {
        return ctx;
    }


    /**
     * ⭐ The ONE rule for a <b>dataset-level</b> value ({@code PLAN-binding-expressions} review
     * rounds 1 and 2, H1 / MED-1): it is a property of the context's TABLE, not of the range a
     * reader happens to span, so it is always computed over every row. Two readers span less — the
     * dataset-level fold ({@code NativeExprEvaluator.evaluateBroadcast}) and the per-variable
     * metadata loops evaluate over ONE synthetic row — and an aggregate computed over that row
     * alone answered for row 0 only. Applied where a dataset-level value is produced: a
     * dataset-level compiled binding ({@code BindingValue}) and an aggregate registry call
     * ({@code ExprCompiler}). A table with fewer rows than this run spans (the synthetic row over a
     * 0-row dataset) keeps this run's range.
     *
     * @return a run over {@code [0, max(table rows, to))} of the same context
     */
    public EvalRun wholeTable()
    {
        int rows = Math.max(ctx.rowCount(), to);
        return from == 0 && to == rows ? this : new EvalRun(ctx, 0, rows);
    }


    public int from()
    {
        return from;
    }


    public int to()
    {
        return to;
    }


    /** The number of rows spanned; v1 evaluates {@code [0, rowCount())}. */
    public int rowCount()
    {
        return to;
    }

}
