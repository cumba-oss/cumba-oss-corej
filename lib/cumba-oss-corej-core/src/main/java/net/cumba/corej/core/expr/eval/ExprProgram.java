package net.cumba.corej.core.expr.eval;

import java.util.BitSet;

/**
 * A compiled, reusable native-evaluation plan for one boolean
 * {@link net.cumba.corej.core.expr.ast.Expr}. Produced by {@link ExprCompiler} once per rule
 * (operand kinds classified, regex {@code Pattern}s compiled, literals folded, functions bound by
 * {@code (name, arity)}), then evaluated many times over the live table via
 * {@link #evaluate(EvalRun)} which returns the violation {@link BitSet}.
 *
 * <p>
 * The plan is an immutable tree of closures and is therefore thread-safe: the cohort fan-out may
 * evaluate the same program on many datasets concurrently. Per-evaluation mutable scratch (computed
 * columns) lives in the {@link EvalRun} / {@link ComputedVector}s created during {@link #evaluate}.
 * </p>
 */
public final class ExprProgram
{

    /** A compiled boolean node: produces a violation {@link BitSet} for a run. */
    @FunctionalInterface
    public interface BoolPlan
    {

        BitSet eval(EvalRun run);
    }

    private final BoolPlan root;

    ExprProgram(BoolPlan root)
    {
        this.root = root;
    }


    /** Evaluates the program over {@code run}, returning the violation {@link BitSet}. */
    public BitSet evaluate(EvalRun run)
    {
        return root.eval(run);
    }

}
