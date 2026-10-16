package net.cumba.corej.core.expr.eval;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import net.cumba.corej.core.exec.BindingValue;
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
 * <b>Three shapes classified since {@code PLAN-bugfix-engine-define} AG (T4):</b> (i) a dynamic
 * read whose column arguments are string literals naming unqualified columns
 * ({@code colref("CMTRT")}) is collected as those names; a qualified literal or a non-literal stays
 * merged. (ii) A {@code $} reference is collected as the names its binding's expression reads,
 * recursively through binding chains, resolved per evaluation against the run's bindings
 * ({@link #namesAt}); a binding the walk cannot see through stays merged. (iii) A {@code *} /
 * {@code **} column-enumeration pattern never reaches evaluation: {@code WildcardExpander} expands
 * it to concrete rules at load, whose operands are plain column refs, and the compiler rejects a
 * residual pattern as unsupported — so it is classified by the expansion, not here. What stays
 * unclassifiable (a qualified or dynamic binding, a {@code ${*}} set) is what the stage-A lint
 * {@code AGGREGATE_OVER_REPEATED_ROWS} (observe-only) reports on a rule whose view can repeat a
 * record ({@link #readsOnlyPrimaryColumns} is its view of (ii)).
 * </p>
 *
 * <p>
 * Costs: the check reads the call's names once per evaluation; the projection is computed once per
 * cached view ({@link RealRows}); the reduced evaluation runs over fewer rows than the expanded
 * one; the hand-back is one pass over the merged rows (a bit per row, or one lazily mapped vector).
 * </p>
 */
public final class RealRowAggregates
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
            "record_count", "distinct", "sequence");

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
        Names names = primaryNames(c);
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
        Names names = primaryNames(c);
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
    private static @Nullable RealRows reducible(EvalRun run, Names names)
    {
        EvaluationContext ctx = run.ctx();
        RealRows real = RealRows.ofRepeating(ctx.getTable());
        if (real == null || run.rowCount() > ctx.rowCount())
        {
            return null;
        }
        List<String> resolved = namesAt(run, names);
        return resolved != null && GroupReadTable.readsOnlyItsOwnColumns(ctx, resolved) ? real
                : null;
    }


    /**
     * The names the call reads — a static part (bare column refs, dynamic-read literals) and the
     * {@code $} references, resolved per evaluation against the run's bindings ({@link #namesAt});
     * or {@code null} when the call reads something that is never a primary column (a qualified or
     * {@code _matched_} reference, a pattern, a dynamic read of a non-literal) or names another
     * dataset ({@code domain=}).
     */
    private static @Nullable Names primaryNames(Expr.Call c)
    {
        if (c.kwargs().containsKey("domain"))
        {
            return null;
        }
        Names out = new Names();
        return collect(c, out) ? out : null;
    }

    /**
     * The names a call reads: the static column names plus the {@code $} bindings whose reads are
     * resolved per evaluation ({@code PLAN-bugfix-engine-define} AG, T4 (ii)).
     */
    private static final class Names
    {

        final List<String> columns = new ArrayList<>();

        final List<String> bindings = new ArrayList<>();
    }

    /**
     * The concrete names the call reads in this evaluation: the static names plus every column a
     * referenced {@code $} binding's expression reads, recursively through binding chains; or
     * {@code null} when a binding the walk cannot see through is read (a binding over a qualified
     * column, a dynamic read, a pattern) — the operator then counts merged rows. A variable that is
     * not a compiled binding (a dataset-level constant) reads no column and contributes nothing.
     */
    private static @Nullable List<String> namesAt(EvalRun run, Names names)
    {
        if (names.bindings.isEmpty())
        {
            return names.columns;
        }
        Names resolved = new Names();
        resolved.columns.addAll(names.columns);
        Set<String> visited = new java.util.HashSet<>();
        java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>(names.bindings);
        while (!pending.isEmpty())
        {
            String id = pending.poll();
            if (!visited.add(id))
            {
                continue;
            }
            Object raw = run.ctx().getVariables().get(id);
            if (!(raw instanceof BindingValue compiled))
            {
                continue; // a dataset-level constant: no column read
            }
            Names inner = new Names();
            if (!collect(compiled.binding().expression(), inner))
            {
                return null;
            }
            resolved.columns.addAll(inner.columns);
            pending.addAll(inner.bindings);
        }
        return resolved.columns;
    }


    /**
     * Whether {@code expr} reads only unqualified primary columns, with every {@code $} reference
     * resolved statically through {@code bindingOf} ({@code null} for an unknown binding, which is
     * then unclassifiable) — the stage-A lint's view of T4 (ii)
     * ({@code AGGREGATE_OVER_REPEATED_ROWS} reports only what stays unclassifiable).
     *
     * @param expr
     *            an operand expression
     * @param bindingOf
     *            the compiled expression of a {@code $} binding by id, or {@code null}
     * @return whether every read is an unqualified column of the evaluation dataset
     */
    public static boolean readsOnlyPrimaryColumns(Expr expr,
            java.util.function.Function<String, @Nullable Expr> bindingOf)
    {
        Names names = new Names();
        if (!collect(expr, names))
        {
            return false;
        }
        Set<String> visited = new java.util.HashSet<>();
        java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>(names.bindings);
        while (!pending.isEmpty())
        {
            String id = pending.poll();
            if (!visited.add(id))
            {
                continue;
            }
            Expr bound = bindingOf.apply(id);
            if (bound == null)
            {
                return false;
            }
            Names inner = new Names();
            if (!collect(bound, inner))
            {
                return false;
            }
            pending.addAll(inner.bindings);
        }
        return true;
    }


    private static boolean collect(Expr e, Names out)
    {
        return switch (e)
        {
        case Expr.Ref r -> collectRef(r, out);
        case Expr.Lit lit -> collectLiteral(lit, out);
        case Expr.Call call -> !call.kwargs().containsKey("domain")
                && (readsDynamically(call) ? collectDynamicRead(call, out)
                        : call.args().stream().allMatch(a -> collect(a, out))
                                && call.kwargs().values().stream().allMatch(a -> collect(a, out)));
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

    /** A plain, unqualified column name: no qualifier dot, no {@code ${…}} template, no pattern. */
    private static final java.util.regex.Pattern PLAIN_NAME = java.util.regex.Pattern
            .compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * T4 (i): a dynamic read whose column arguments are <b>string literals naming unqualified
     * columns</b> ({@code colref("CMTRT")}) reads exactly those columns — collected by name. A
     * qualified literal ({@code colref("CM.CMTRT")}), a template or a non-literal argument — a
     * column reference ({@code colref(VARCOL)}, whose VALUES name the columns read) or a {@code $}
     * binding — keeps the read dynamic (merged), as before (T2 review MED-2; B3 review MED-1: a
     * reference used to be collected as the column it NAMES, which is not the column it reads).
     */
    private static boolean collectDynamicRead(Expr.Call call, Names out)
    {
        List<Expr> operands = new ArrayList<>(call.args());
        operands.addAll(call.kwargs().values());
        for (Expr operand : operands)
        {
            if (operand instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.STRING
                    && lit.value() instanceof String name && PLAIN_NAME.matcher(name).matches())
            {
                out.columns.add(name);
            }
            else
            {
                return false;
            }
        }
        return true;
    }


    private static boolean collectRef(Expr.Ref r, Names out)
    {
        OperandKind kind = r.kind();
        if (kind == OperandKind.COLUMN)
        {
            out.columns.add(r.name());
            return true;
        }
        if (kind == OperandKind.OPERATION_REF && r.name().startsWith("$"))
        {
            // T4 (ii): resolved against the binding's expression at evaluation (namesAt) or
            // statically (readsOnlyPrimaryColumns)
            out.bindings.add(r.name());
            return true;
        }
        // A built-in (variable_name, a dataset fact) reads no column of either side.
        return kind == OperandKind.BUILTIN;
    }


    private static boolean collectLiteral(Expr.Lit lit, Names out)
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
