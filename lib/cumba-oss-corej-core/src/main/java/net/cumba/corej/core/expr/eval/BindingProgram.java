package net.cumba.corej.core.expr.eval;

import java.util.BitSet;
import net.cumba.datatable.values.DataValueType;
import org.jspecify.annotations.Nullable;

/**
 * The compiled plan of one <b>compiled binding</b> ({@code PLAN-binding-expressions}, wave 0): the
 * binding's expression compiled on exactly the path the {@code Check} compiles on
 * ({@link ExprCompiler#compileBinding}), so {@code $x: upper(AETERM)} and an inline
 * {@code upper(AETERM)} are one plan.
 *
 * <p>
 * A value expression evaluates to its own {@link Vector}. A <b>condition</b> (a comparison, a
 * boolean function, {@code and}/{@code or}/{@code not}, a join-match flag) evaluates to the per-row
 * verdict as a {@link ComputedVector} of {@link Boolean}s over the {@link BitSet} — no array beyond
 * the vector's own memo, so a boolean binding costs what the inline condition costs (D-W0-3).
 * Thread-safe and shareable across rule executions, like {@link ExprProgram}: every per-evaluation
 * value lives in the {@link EvalRun}.
 * </p>
 */
public final class BindingProgram
{

    private final ExprCompiler.@Nullable ValuePlan value;

    private final ExprProgram.@Nullable BoolPlan condition;

    private BindingProgram(ExprCompiler.@Nullable ValuePlan aValue,
            ExprProgram.@Nullable BoolPlan aCondition)
    {
        value = aValue;
        condition = aCondition;
    }


    static BindingProgram value(ExprCompiler.ValuePlan aValue)
    {
        return new BindingProgram(aValue, null);
    }


    static BindingProgram condition(ExprProgram.BoolPlan aCondition)
    {
        return new BindingProgram(null, aCondition);
    }


    /**
     * Whether the binding is a condition (a per-row boolean verdict) rather than a value.
     *
     * @return whether the binding is a condition
     */
    public boolean isCondition()
    {
        return condition != null;
    }


    /**
     * Evaluates the binding over {@code run}.
     *
     * @param run
     *            the reading run — the Check's own, so a cursor context and the synthetic broadcast
     *            row are honoured
     * @return the binding's Vector; never {@code null} (an unresolvable operand is an all-missing
     *         constant, as in any value position)
     */
    public Vector evaluate(EvalRun run)
    {
        if (condition != null)
        {
            BitSet verdict = condition.eval(run);
            return new ComputedVector(run.rowCount(), DataValueType.BOOLEAN, verdict::get);
        }
        ExprCompiler.ValuePlan plan = java.util.Objects.requireNonNull(value,
                "a binding program is a value or a condition");
        Vector v = plan.eval(run);
        return v != null ? v : ConstVector.of(null);
    }

}
