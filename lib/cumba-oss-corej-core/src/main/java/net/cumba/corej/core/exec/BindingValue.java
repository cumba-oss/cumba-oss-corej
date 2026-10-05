package net.cumba.corej.core.exec;

import java.util.Map;
import net.cumba.corej.core.expr.eval.BindingProgram;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.NativeExprEvaluator;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.corej.core.expr.typed.ExprType;
import net.cumba.corej.core.model.CompiledBinding;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;

/**
 * The runtime value of one <b>compiled binding</b> in one (rule × dataset) execution
 * ({@code PLAN-binding-expressions}, wave 0) — what the rule's variable map holds under the
 * binding's {@code $}-name.
 *
 * <p>
 * ⭐ <b>Evaluated lazily, in the reading context</b> (ruling D-W0-3: <i>"a never-read binding never
 * runs"</i>). Not a memoised scalar: a compiled binding may read the row cursor, the variable
 * cursor or the synthetic broadcast row, so its value is a function of the {@link EvalRun} that
 * reads it. {@link #vector(EvalRun)} evaluates the binding's {@link BindingProgram} over that run,
 * memoised in one slot keyed by the run's variables map, table and range — the Check's contexts
 * share one variables map (a hit), while a per-variable context carries its own (a recomputation,
 * which is the point for a binding that reads the VAR cursor).
 * </p>
 *
 * <p>
 * ⭐ <b>A binding without the VAR cursor ignores the variables map</b> (combined review of runbook
 * W2–W8, confirmation look L-c): a ROW-only binding answers the same Vector for every column, and
 * keyed on the per-column map the {VAR,ROW} loop evaluated it once per column. It is memoised on
 * (table, range) alone.
 * </p>
 *
 * <p>
 * ⭐ <b>A dataset-level binding is a property of the TABLE, not of the reading run</b> (review round
 * 1, H1 / M1). A binding whose derived domain reads no cursor
 * ({@link CompiledBinding#needsCursor()} is {@code false}) — an aggregate such as
 * {@code get_codelist_attributes}, or arithmetic over one — is always evaluated over the context's
 * whole table, whatever range the reader spans, and memoised on the table alone. Two readers made
 * both halves necessary: {@code BroadcastFold} folds a dataset-level leaf over ONE synthetic row
 * ({@code NativeExprEvaluator.evaluateBroadcast}), which re-ran an aggregate over row 0 only
 * (CDISC-CG0288 ERRORed whenever row 0 named no CT package) and evicted the memo; and the
 * per-variable loops give every column a fresh variables map, which recomputed the binding once per
 * column. It is now computed <b>once per (rule × dataset) execution</b>. The one exception is a
 * table with fewer rows than the reading run spans — a synthetic broadcast row over a 0-row dataset
 * — which evaluates over the run, as before.
 * </p>
 *
 * <p>
 * <b>Two readers, two forms</b> (§5.0's hand-over contract; the third row — an operation reading
 * the raw dataset-level value — went with the operation surface in runbook W8):
 * </p>
 * <ul>
 * <li>the compiler ({@code $}-value, name and membership plans) reads the {@link Vector}
 * itself;</li>
 * <li>every other reader goes through {@link EvaluationContext#resolveVariable}, which answers
 * {@link #handOver}: a dataset-level binding as its raw value (the {@code List} / scalar), a
 * per-row binding as its {@link Vector}.</li>
 * </ul>
 *
 * <p>
 * Not thread-safe; one instance belongs to one (rule × dataset) execution.
 * </p>
 */
public final class BindingValue
{

    private final CompiledBinding binding;

    private final BindingProgram program;

    /**
     * The binding's static type (stage A, {@code StageAChecker.bindingTypes}) — the type source of
     * {@link #reportedHandOver} when a missing's carrier lost it; {@code null} when the caller had
     * none (a test constructing the value directly; a rule whose binding walk failed and that
     * reports a binding is refused at load), and {@link #reportedDefault} then has only the
     * evaluation's carrier and {@code columnTyped} to go by.
     */
    private final @Nullable ExprType staticType;

    /**
     * Whether the binding's value is the type of a column it reads
     * ({@code StageAChecker.columnTypedBindings}: {@code read_value}, {@code max}, {@code row_max},
     * {@code coalesce}, {@code colref}, {@code value()}, a column reference, a case fold or a
     * {@code $}-reference over one) — then the evaluation's carrier is that column's type and is
     * the finding's type source when stage A leaves the binding unknown (P3 review MED-1).
     */
    private final boolean columnTyped;

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
        this(aBinding, null);
    }


    public BindingValue(CompiledBinding aBinding, @Nullable ExprType aStaticType)
    {
        this(aBinding, aStaticType, false);
    }


    /**
     * A holder for one compiled binding of one (rule × dataset) execution, with its type sources.
     *
     * @param aBinding
     *            the compiled binding (already {@code --}-specialised for the dataset)
     * @param aStaticType
     *            the binding's stage-A type, or {@code null} when the caller has none
     * @param aColumnTyped
     *            whether the binding's value is the type of a column it reads
     *            ({@code StageAChecker.columnTypedBindings})
     */
    public BindingValue(CompiledBinding aBinding, @Nullable ExprType aStaticType,
            boolean aColumnTyped)
    {
        binding = aBinding;
        program = NativeExprEvaluator.bindingProgram(aBinding.expression());
        staticType = aStaticType;
        columnTyped = aColumnTyped;
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
     * The binding's Vector over {@code run}, memoised for the run's (variables, table, range) — or,
     * for a dataset-level binding, over the context's whole table, memoised for the table alone.
     *
     * @param run
     *            the reading run
     * @return the value; never {@code null}
     */
    public Vector vector(EvalRun run)
    {
        EvaluationContext ctx = run.ctx();
        boolean datasetLevel = !binding.needsCursor();
        // H1: a dataset-level binding folds the whole table, never the reader's range.
        EvalRun evaluated = datasetLevel ? run.wholeTable() : run;
        Vector cached = memo;
        if (cached != null && memoTable == ctx.getTable() && memoFrom == evaluated.from()
                && memoTo == evaluated.to()
                && (datasetLevel || !readsVariableCursor() || memoVariables == ctx.getVariables()))
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
            Vector v = program.evaluate(evaluated);
            if (datasetLevel)
            {
                requireUniform(v, evaluated.to() - evaluated.from());
            }
            memo = v;
            memoVariables = ctx.getVariables();
            memoTable = ctx.getTable();
            memoFrom = evaluated.from();
            memoTo = evaluated.to();
            return v;
        }
        finally
        {
            depth--;
        }
    }


    /**
     * Whether the binding's value may differ between two variables maps over the same table and
     * range: its derived domain carries the VAR cursor, or no domain is installed (conservatively).
     * Only RuleRunner swaps the variables map, and only per column (the per-variable loops); a
     * binding without the VAR cursor reads no {@code variable_name} and — the domain being derived
     * transitively over its {@code $}-references — no per-column binding either.
     */
    private boolean readsVariableCursor()
    {
        return binding.domain() == null || binding.domain().varCursor();
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
        if (binding.domain() == null)
        {
            // Every binding a rule executes has its derived domain installed
            // (RulePackageLoader.installNativeExpr: BindingDomains.forRule); a binding without one
            // never came through the loader, and reading it as per-row here would let a
            // dataset-level list reach `contains_all` / `in` as an empty set — a silent
            // every-row verdict (combined review of runbook W2–W8, W7 L3).
            throw new IllegalStateException("binding " + binding.name()
                    + " has no derived domain — a rule's bindings are installed by"
                    + " RulePackageLoader.installNativeExpr before they are evaluated");
        }
        Vector v = vector(EvalRun.fullRange(ctx));
        if (binding.domain().rowCursor())
        {
            return v;
        }
        return firstValue(v, ctx.rowCount());
    }


    /**
     * The form a <b>finding</b> reports ({@code PLAN-output-variable-null-key-provenance} §2.1 /
     * H3): exactly {@link #handOver}, except that a missing value is never {@code null} — it is the
     * binding's typed default absence value, decided by the type the binding evaluated to:
     * {@code ""} for a string, the {@link MissingValue} the value carries ({@code MIS} for a
     * function result that answered nothing) for every other type. A per-row binding is still its
     * {@link Vector} (the caller renders the finding's row). {@code handOver} itself keeps
     * answering {@code null} for a missing: it is the <em>evaluation</em> accessor, and a verdict
     * must not move because the report learnt to spell absence. When the carrier lost the type, the
     * binding's stage-A type decides ({@link #reportedDefault}); with neither, it throws (P3).
     *
     * @param ctx
     *            the reading context
     * @return the hand-over value or the typed default; never {@code null}
     * @throws IllegalStateException
     *             when a missing has no type source ({@link #reportedDefault})
     */
    public Object reportedHandOver(EvaluationContext ctx)
    {
        Object value = handOver(ctx);
        if (value != null)
        {
            return value;
        }
        Vector v = vector(EvalRun.fullRange(ctx));
        int rows = ctx.rowCount();
        DataValueType type = v.declaredType();
        MissingValue identity = null;
        if (!(v instanceof ConstVector) && rows > 0)
        {
            net.cumba.corej.core.expr.eval.TypedValue first = v.value(0);
            type = first.type();
            identity = first.missing();
        }
        if (identity != null && (type == DataValueType.LONG || type == DataValueType.DOUBLE
                || type == DataValueType.BOOLEAN))
        {
            // The carrier kept the evaluated type: the MissingValue it carries.
            return identity;
        }
        return reportedDefault(type);
    }


    /**
     * The typed default absence value a finding reports for this binding when it has no value,
     * given the type the evaluation carried ({@code PLAN-output-variable-null-key-provenance} §2.1
     * / H3 / P3) — the one decision {@link #reportedHandOver} and the record path's empty-table arm
     * share (T2 LOW-5): {@code ""} for a string; {@link MissingValue#MIS} for a number or a
     * boolean; for an untyped carrier ({@code ConstVector.of(null)} — a metadata accessor with no
     * cell, a function that answered nothing — or a per-row binding with no row to read) the
     * binding's <em>static</em> type decides: {@code ""} for a string or a date / time (a temporal
     * value's carrier is its ISO-8601 text, SPEC §1.2 — a char value), {@code MIS} for a number, a
     * boolean or a list.
     *
     * <p>
     * ⛔ <b>No untyped default</b> (owner ruling OPEN-T (c), 2026-10-05: <i>"#3: yes"</i>). Every
     * value function whose semantics pin its result declares it ({@code ElementTable}), so stage A
     * types the binding. A binding stage A leaves unknown whose value is the type of a column it
     * reads ({@code columnTyped}: {@code read_value}, {@code max}, {@code row_max},
     * {@code coalesce}, {@code colref}, {@code value()}, …) takes its default from the column the
     * evaluation carried: {@code ""} for a char column (answered above), {@code MIS} for every
     * other column type — a number, a COMPLEX (internal datatable), an OTHER (parquet / RDS), a
     * column with no type at all (MISSING, a SQL {@code NULL} column), as
     * {@code OutputEntryKind.defaultValue(DataValueType)} spells a column's type (P3 review MED-1,
     * "their carrier type"). Any other binding with no type source — an {@code extract_metadata}
     * key no reader documents — is refused at <b>load</b> when it is reported
     * ({@code RulePackageLoader.validateReportedBindingTypes}), so the throw below is a defensive
     * assertion no loaded rule reaches. Until 2026-10-05 such a binding printed the record path's
     * historical untyped {@code ""}; P3 made it a data-dependent run-time ERROR; MED-1 moved the
     * refusal to the rule text.
     * </p>
     *
     * @param evaluated
     *            the type the evaluation carried
     * @return {@code ""} or {@link MissingValue#MIS}; never {@code null}
     * @throws IllegalStateException
     *             when neither the evaluation, nor stage A, nor a read column types the binding —
     *             unreachable from a loaded rule (the load gate refuses it)
     */
    public Object reportedDefault(DataValueType evaluated)
    {
        if (evaluated == DataValueType.STRING)
        {
            return "";
        }
        if (evaluated == DataValueType.LONG || evaluated == DataValueType.DOUBLE
                || evaluated == DataValueType.BOOLEAN)
        {
            return MissingValue.MIS;
        }
        if (staticType == ExprType.Primitive.STRING || staticType == ExprType.Primitive.DATE
                || staticType == ExprType.Primitive.TIME)
        {
            return "";
        }
        if (staticType == ExprType.Primitive.NUMBER || staticType == ExprType.Primitive.BOOLEAN
                || staticType instanceof ExprType.ListOf)
        {
            return MissingValue.MIS;
        }
        if (columnTyped)
        {
            // The value is the type of the column it read, and that column is not a char one
            // (STRING returned above): MIS, whatever the column's non-char type (P3 review MED-1).
            return MissingValue.MIS;
        }
        throw new IllegalStateException("binding " + binding.name() + " answered no value and"
                + " has no type source for the finding's default: its evaluation carried "
                + evaluated + ", its stage-A type is "
                + (staticType == null ? "absent" : staticType.describe())
                + " and it reads no column — unreachable from a loaded rule, whose load gate"
                + " (RulePackageLoader.validateReportedBindingTypes) refuses a reported binding"
                + " with no type source; a binding is reported \"\" (char) or MIS, never an"
                + " untyped value (PLAN-output-variable-null-key-provenance P3 review MED-1)");
    }


    /**
     * ⛔ <b>A dataset-level binding is one value for the table, and this proves it</b> (combined
     * review of runbook W2–W8, XCUT H1). A non-constant vector whose rows disagree is a per-row
     * value the level calculus mis-classified as {@code {}}: every dataset-level reader — the
     * broadcast fold's synthetic row, {@link #handOver}'s first value, the report — then reads row
     * 0 for the whole table, which is exactly how a {@code row_max} binding decided
     * CDISC-/PMDA-AD0084 from row 0. It fails loud instead, naming the binding. The pass is one
     * read per row, once per (rule × dataset) (the memo above); a function answering one value for
     * the table should broadcast a {@link ConstVector} and pay nothing here.
     */
    private void requireUniform(Vector v, int rowCount)
    {
        if (v instanceof ConstVector || rowCount <= 1)
        {
            return;
        }
        Object first = v.value(0).resolved();
        for (int r = 1; r < rowCount; r++)
        {
            if (!java.util.Objects.equals(v.value(r).resolved(), first))
            {
                throw new IllegalStateException("binding " + binding.name()
                        + " is classified dataset-level but answers a different value at row " + r
                        + " than at row 0 — a per-row function must declare itself"
                        + " (FunctionDescriptor.readingRows) or take a column operand");
            }
        }
    }


    /**
     * The raw value of a dataset-level Vector, in the vocabulary an operation result uses: a
     * broadcast constant's own value (a {@code List}, a {@code Long}, a {@code String}, …),
     * otherwise the one value every row carries ({@link #requireUniform}) — its payload, or for a
     * typed numeric cell (arithmetic, a numeric column) the cell's number rather than its text, so
     * {@code record_count() + 0} hands over {@code 3.0} as {@code record_count()} hands over
     * {@code 3}. {@code null} for a missing value and over an empty table (a computed vector has no
     * row 0 there).
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

}
