package net.cumba.corej.core.exec;

import java.util.Map;
import java.util.function.Supplier;
import net.cumba.corej.core.expr.eval.BindingProgram;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.NativeExprEvaluator;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.corej.core.model.CompiledBinding;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;

/**
 * The runtime value of one <b>compiled binding</b> in one (rule × dataset) execution
 * ({@code PLAN-binding-expressions}, wave 0) — what the rule's variable map holds under the
 * binding's {@code $}-name, where an operation binding holds a {@link LazyValue}.
 *
 * <p>
 * ⭐ <b>Evaluated lazily, in the reading context</b> (ruling D-W0-3: <i>"a never-read binding never
 * runs"</i>). Not a {@link LazyValue}: an operation is dataset-level and computed once, but a
 * compiled binding may read the row cursor, the variable cursor or the synthetic broadcast row, so
 * its value is a function of the {@link EvalRun} that reads it. {@link #vector(EvalRun)} evaluates
 * the binding's {@link BindingProgram} over that run, memoised in one slot keyed by the run's
 * variables map, table and range — the Check's contexts share one variables map (a hit), while a
 * per-variable context carries its own (a recomputation, which is the point).
 * </p>
 *
 * <p>
 * <b>Three readers, three forms</b> (§5.0's hand-over contract):
 * </p>
 * <ul>
 * <li>the compiler ({@code $}-value, name and membership plans) reads the {@link Vector}
 * itself;</li>
 * <li>every other reader goes through {@link EvaluationContext#resolveVariable}, which answers
 * {@link #handOver}: a dataset-level binding as its raw value (the {@code List} / scalar an
 * operation would have produced), a per-row binding as its {@link Vector};</li>
 * <li>an <b>operation</b> reads {@link #forOperation}: the raw dataset-level value, and an
 * {@link IllegalStateException} for a binding that needs a cursor — a stage-A load error rejects
 * that shape first, so the throw is a backstop, never a {@code vector.toString()} member.</li>
 * </ul>
 *
 * <p>
 * Not thread-safe; one instance belongs to one (rule × dataset) execution, as its
 * {@link LazyValue}s do.
 * </p>
 */
public final class BindingValue
{

    private final CompiledBinding binding;

    private final BindingProgram program;

    private @Nullable Map<String, Object> memoVariables;

    private @Nullable IDataTable memoTable;

    private int memoFrom = -1;

    private int memoTo = -1;

    private @Nullable Vector memo;

    /**
     * Re-entrancy depth: a binding that (transitively) reads itself re-enters {@link #vector}
     * before its first evaluation finished, and fails loud instead of recursing forever.
     */
    private int depth;

    /**
     * A holder for one compiled binding of one (rule × dataset) execution.
     *
     * @param aBinding
     *            the compiled binding (already {@code --}-specialised for the dataset)
     */
    public BindingValue(CompiledBinding aBinding)
    {
        binding = aBinding;
        program = NativeExprEvaluator.bindingProgram(aBinding.expression());
    }


    /**
     * The binding this value evaluates.
     *
     * @return the binding this value evaluates
     */
    public CompiledBinding binding()
    {
        return binding;
    }


    /**
     * The binding's Vector over {@code run}, memoised for the run's (variables, table, range).
     *
     * @param run
     *            the reading run
     * @return the value; never {@code null}
     */
    public Vector vector(EvalRun run)
    {
        EvaluationContext ctx = run.ctx();
        Vector cached = memo;
        if (cached != null && memoVariables == ctx.getVariables() && memoTable == ctx.getTable()
                && memoFrom == run.from() && memoTo == run.to())
        {
            return cached;
        }
        if (depth > 0)
        {
            throw new IllegalStateException("binding " + binding.name()
                    + " reads itself — a forward or cyclic binding reference must be a stage-A"
                    + " load error");
        }
        depth++;
        try
        {
            Vector v = program.evaluate(run);
            memo = v;
            memoVariables = ctx.getVariables();
            memoTable = ctx.getTable();
            memoFrom = run.from();
            memoTo = run.to();
            return v;
        }
        finally
        {
            depth--;
        }
    }


    /**
     * The form {@link EvaluationContext#resolveVariable} answers: a binding whose derived domain
     * reads the row cursor is its {@link Vector}; any other binding is the raw value of its first
     * row — the {@code List} / scalar an operation would have produced, so {@code BroadcastFold},
     * the grouped-operator readers and the report keep their vocabulary.
     *
     * @param ctx
     *            the reading context
     * @return the hand-over value; {@code null} for a missing value
     */
    public @Nullable Object handOver(EvaluationContext ctx)
    {
        Vector v = vector(EvalRun.fullRange(ctx));
        if (binding.domain() == null || binding.domain().rowCursor())
        {
            return v;
        }
        return firstValue(v, ctx.rowCount());
    }


    /**
     * The value an <b>operation</b> reads (§5.0): the raw dataset-level value.
     *
     * @param ctx
     *            the rule's context
     * @return the raw value; {@code null} for a missing value
     * @throws IllegalStateException
     *             for a binding that needs a cursor — an operation's fields are dataset-level, and
     *             stage A rejects the shape at load, so reaching here is an engine defect
     */
    public @Nullable Object forOperation(EvaluationContext ctx)
    {
        if (binding.needsCursor())
        {
            throw new IllegalStateException("an operation cannot read the per-row or per-variable"
                    + " binding " + binding.name() + " — stage A should have rejected this rule at"
                    + " load");
        }
        return firstValue(vector(EvalRun.fullRange(ctx)), ctx.rowCount());
    }


    /**
     * The raw value of a dataset-level Vector, in the vocabulary an operation result uses: a
     * broadcast constant's own value (a {@code List}, a {@code Long}, a {@code String}, …),
     * otherwise row 0's value — its payload, or for a typed numeric cell (arithmetic, a numeric
     * column) the cell's number rather than its text, so {@code record_count() + 0} hands over
     * {@code 3.0} as {@code record_count()} hands over {@code 3}. {@code null} for a missing value
     * and over an empty table (a computed vector has no row 0 there).
     */
    private static @Nullable Object firstValue(Vector v, int rowCount)
    {
        if (v instanceof ConstVector constant)
        {
            return constant.value();
        }
        if (rowCount <= 0)
        {
            return null;
        }
        net.cumba.corej.core.expr.eval.TypedValue first = v.value(0);
        Object resolved = first.resolved();
        if (resolved instanceof String && first.cell().getValue() instanceof Number number)
        {
            return number;
        }
        return resolved;
    }


    /**
     * ⭐ The <b>one</b> hand-over helper for an operation reading a prior {@code $}-entry of the
     * variable map (§5.0 / I5): a {@link LazyValue} is forced, a {@link BindingValue} answers
     * {@link #forOperation}, anything else passes through. Every place an operation's priors are
     * gathered — {@code RuleRunner}'s supplier and unknown-type branch, {@code ExprCompiler}'s
     * inline operations — calls this, never a local unwrap.
     *
     * @param entry
     *            the variable-map entry, may be {@code null}
     * @param ctx
     *            supplies the context a compiled binding evaluates in; consulted only for a
     *            {@link BindingValue}
     * @return the value the operation reads
     */
    public static @Nullable Object forOperation(@Nullable Object entry,
            Supplier<EvaluationContext> ctx)
    {
        if (entry instanceof LazyValue<?> lazy)
        {
            return lazy.get();
        }
        if (entry instanceof BindingValue compiled)
        {
            return compiled.forOperation(ctx.get());
        }
        return entry;
    }

}
