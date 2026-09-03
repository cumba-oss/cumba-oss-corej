package net.cumba.corej.core.expr.eval;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.expr.ast.Expr;

/**
 * A vectorized, pluggable function. Implementations receive their arguments already compiled to
 * {@link Vector}s over the current row range and return either a {@link Vector} (when the
 * descriptor's {@link FunctionKind} is {@link FunctionKind#VALUE}) or a {@link java.util.BitSet} of
 * violating rows (when it is {@link FunctionKind#BOOLEAN}).
 *
 * <p>
 * Implementations must be <b>pure</b> and side-effect free: the engine evaluates rules concurrently
 * across a cohort thread pool, so a function may run on many threads at once. They may inspect the
 * {@link EvalRun} for the row range and the {@link net.cumba.corej.core.exec.EvaluationContext}.
 * </p>
 */
@FunctionalInterface
public interface EvalFunction
{

    /**
     * Applies this function to the given arguments over the run's row range.
     *
     * @param run
     *            the per-evaluation run state (row range + context)
     * @param args
     *            the arguments, already evaluated to {@link Vector}s
     * @return a {@link Vector} (VALUE) or a {@link java.util.BitSet} (BOOLEAN)
     */
    Object apply(EvalRun run, List<Vector> args);


    /**
     * Kwargs-aware entry point (plan unified-callable-surface §3.1): the compiler invokes this
     * form, passing the call's keyword arguments as <b>raw {@link Expr} nodes</b> — predicate /
     * column kwargs ({@code filter=}, {@code group=}, {@code within=}, …) stay unevaluated so the
     * function decides how to compile or resolve them, mirroring how
     * {@code OperationExpressionParser} consumes operation kwargs. Plain functions ignore kwargs:
     * this {@code default} delegates to {@link #apply(EvalRun, List)}, so every existing
     * implementation (lambdas included) is unchanged. A kwarg-consuming function overrides this
     * form instead.
     *
     * @param kwargs
     *            the call's keyword arguments, keyed by kwarg name, in source order; never
     *            {@code null}, empty for a plain positional call
     */
    default Object apply(EvalRun run, List<Vector> args, Map<String, Expr> kwargs)
    {
        return apply(run, args);
    }
}
