package net.cumba.cdisc.core.expr.eval;

import java.util.BitSet;
import java.util.concurrent.ConcurrentHashMap;

import net.cumba.cdisc.core.exec.EvaluationContext;
import net.cumba.cdisc.core.expr.ExpressionException;
import net.cumba.cdisc.core.expr.ast.Expr;

/**
 * The native expression evaluation entry point — the sibling of {@code CheckEvaluator.evaluate}.
 * Compiles a boolean {@link Expr} to an {@link ExprProgram} once (cached per {@code Expr}) and runs
 * it over the supplied {@link EvaluationContext}'s full row range, returning the violation
 * {@link BitSet} using the same 0-based row indices as the legacy evaluator (so the downstream
 * finding construction is unchanged).
 *
 * <p>
 * Stateless and thread-safe: the program cache is a {@link ConcurrentHashMap} and compiled programs
 * resolve columns against the live table at evaluation time, so a single program is safely shared
 * across the cohort fan-out and across datasets.
 * </p>
 */
public final class NativeExprEvaluator
{

    private static final ConcurrentHashMap<Expr, ExprProgram> CACHE = new ConcurrentHashMap<>();

    private NativeExprEvaluator()
    {
    }


    /**
     * Evaluates {@code expr} over {@code ctx}, returning the violation {@link BitSet}.
     *
     * @throws ExpressionException
     *             if the expression contains a construct the native backend does not implement
     */
    public static BitSet evaluate(Expr expr, EvaluationContext ctx)
    {
        return program(expr).evaluate(EvalRun.fullRange(ctx));
    }


    /**
     * {@code true} iff {@code expr} compiles on the native backend (no unsupported construct). The
     * compiled program is cached, so a subsequent {@link #evaluate} reuses it.
     */
    public static boolean isSupported(Expr expr)
    {
        if (expr == null)
        {
            return false;
        }
        try
        {
            program(expr);
            return true;
        }
        catch (ExpressionException _)
        {
            return false;
        }
    }


    /**
     * Evaluates a <em>row-independent</em> (broadcast) boolean condition once and returns its
     * verdict. Used for variable- / dataset-metadata checks, whose {@code var_*}/{@code ds_*}
     * operands are constant across rows: the program is evaluated over a single synthetic row, so
     * the verdict is well-defined even on a 0-row dataset. Must only be used for expressions with
     * no per-row data operands.
     */
    public static boolean evaluateBroadcast(Expr expr, EvaluationContext ctx)
    {
        return program(expr).evaluate(new EvalRun(ctx, 0, 1)).get(0);
    }


    private static ExprProgram program(Expr expr)
    {
        return CACHE.computeIfAbsent(expr, ExprCompiler::compile);
    }


    /** Test-only: clears the compiled-program cache. */
    static void clearCacheForTesting()
    {
        CACHE.clear();
    }

}
