package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.ExpressionPrinter;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.ArgumentBinder;
import net.cumba.corej.core.expr.eval.ComputedVector;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.DatasetExpressionCache;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.ExprCompiler;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.expr.eval.FunctionRegistry;
import net.cumba.corej.core.expr.eval.Parameter;
import net.cumba.corej.core.expr.eval.Primitives;
import net.cumba.corej.core.expr.eval.TypedValue;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.GroupKeyPolicy;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;

/**
 * ⭐ Runbook W7 ({@code PLAN-distinct-function}) — the registry function
 * {@code distinct(name, domain=, filter=, group=, keep_missings=)}, the one KEEP-both callable of
 * the programme (runbook R6): its sole positional target is either a <b>column</b>, answering the
 * set of the column's distinct present values, or a <b>list of columns</b>, answering the set of
 * the distinct row tuples over those columns. One name, two result shapes (the audit's W7 row).
 *
 * <p>
 * The plan is W5's ({@link GroupedAggregate}): the call is read at load by {@link #spec} — its
 * column arguments are <b>names</b>, of the OTHER dataset under {@code domain=} (D10 / D13 Q4 /
 * D14) — and evaluated per execution. The answers are W0's list shape ({@code
 * PLAN-binding-expressions} §4.1, option 2): ungrouped, ONE dataset-level {@code List} inside a
 * {@link ConstVector}; grouped (a column target only), each row of the evaluated dataset its
 * group's {@code List} as the cell of an untyped {@link ComputedVector} — the shape every consumer
 * already reads for a compiled list binding ({@code ExprCompiler.boundMembership},
 * {@code Primitives.substring}'s collection arm, {@code contains_all}, {@code minus},
 * {@code upper}). No per-row result object leaves this function (runbook R2).
 * </p>
 *
 * <p>
 * <b>The value set</b> is the retired executor's {@code evalDistinct}, verbatim (D-W7-2): the text
 * ({@code getValueAsString()}) of the present, non-blank cells in row order — a missing or
 * {@code ""} cell is not a member. <b>The tuple set</b> is the retired {@code evalDistinctTuples},
 * verbatim (D-W7-3; {@code PLAN-member-set-identity-hardening}): one unmodifiable {@code List} of
 * components per distinct kept row, in list order — a present cell as its text, a missing cell as
 * its {@link Primitives.MissingMember} (D11 / D34 #5-2, never {@code ""} nor {@code "."}), an
 * ABSENT column as the NVE constant of its type's default ({@code ""} for character, {@code MIS}
 * for a column the rule expects numeric), and an {@code IDVARVAL} component as its SUPP-- / RELREC
 * join token ({@link ChildMatchIndex#normalizeJoinToken}, review H1 of 2026-08-19) — exactly the
 * components the {@code tuple(…)} probe side builds, so a row tuple and a reference tuple compare
 * {@link List#equals List-equal}.
 * </p>
 *
 * <p>
 * A set is never missing (EC-45's SET codomain, Q17-a): an absent {@code domain=} dataset, an
 * absent target column, a group with no present value and a row of the evaluated dataset whose key
 * has no group all answer the <b>empty list</b> — so {@code X not in $s} fires and {@code X in $s}
 * does not, as the operation always answered. The dataset-level answer and the grouped block map
 * are memoised for the execution under the canonical call text (the aggregate memo, W5; no
 * {@code $} member is admitted in {@code group=}, so the text is the whole key).
 * </p>
 */
public final class Distinct
{

    /** The function name. */
    public static final String NAME = "distinct";

    /** The empty answer — one instance, so a run of rows without a group folds to one set. */
    private static final List<Object> EMPTY = List.of();

    /** The column whose contract is the SUPP-- / RELREC join-token contract. */
    private static final String JOIN_TOKEN_COLUMN = "IDVARVAL";

    private Distinct()
    {
    }


    /**
     * Whether {@code c} is a grouped {@code distinct} call — one that authors {@code group=} (an
     * ungrouped call answers one dataset-level list). An empty {@code group=[]} is no third case:
     * the reader refuses it at load ({@code GroupedAggregate.readGroup}, "must name at least one
     * column"), so a rule carrying one never runs.
     *
     * @param c
     *            a call
     * @return {@code true} for a grouped call
     */
    public static boolean isGrouped(Expr.Call c)
    {
        return c.kwargs().get(GroupedAggregate.GROUP_PARAMETER) != null;
    }

    /**
     * A {@code distinct} call as authored, read at load.
     *
     * @param target
     *            the column target — a column reference or a VALUE expression over the target table
     *            — or {@code null} for the tuple shape
     * @param tupleColumns
     *            the tuple shape's column names, or {@code null} for the column shape
     * @param dataset
     *            the {@code domain=} dataset, or {@code null} for the evaluated dataset
     * @param filter
     *            the boolean filter over the target table's own columns, or {@code null}
     * @param group
     *            the group column names; empty for an ungrouped call
     * @param policy
     *            the missing-key policy
     * @param canonical
     *            the canonical call text (the memo key)
     */
    public record Spec(@Nullable Expr target, @Nullable List<String> tupleColumns,
            @Nullable String dataset, @Nullable Expr filter, List<String> group,
            GroupKeyPolicy policy, String canonical)
    {

        public Spec
        {
            group = List.copyOf(group);
            tupleColumns = tupleColumns == null ? null : List.copyOf(tupleColumns);
        }


        /** Whether this is the tuple shape (a list target). */
        public boolean isTuple()
        {
            return tupleColumns != null;
        }


        /**
         * Evaluates the call for this run.
         *
         * @param run
         *            the evaluation run
         * @return the list vector — a {@link ConstVector} when ungrouped, a per-row vector of lists
         *         when grouped
         */
        public Vector evaluate(EvalRun run)
        {
            return Distinct.evaluate(this, run);
        }
    }

    // -----------------------------------------------------------------------
    // The strict reader (load time)
    // -----------------------------------------------------------------------

    /**
     * Reads the call at load time, refusing every shape the plan cannot mean (the messages name the
     * spelling the author must use): no target or a second positional (the binder); a string
     * literal target (R1); a list target holding anything but column references; a {@code group=}
     * beside a list target (the operation ignored it silently — P-Q8); {@code keep_missings}
     * without {@code group=}; a {@code --} reference under {@code domain=}; a dotted or {@code $}
     * reference anywhere; the retired {@code filter(K="v")} call form; an unknown keyword (D19a).
     *
     * @param c
     *            the authored call
     * @return the read
     * @throws ExpressionException
     *             when the call is malformed — the rule's load error, with the reader's own message
     */
    public static Spec spec(Expr.Call c)
    {
        if (!NAME.equals(c.name()))
        {
            throw new ExpressionException("not " + NAME + ": '" + c.name() + "'");
        }
        FunctionDescriptor descriptor = FunctionRegistry.descriptor(NAME);
        if (descriptor == null)
        {
            throw new ExpressionException("no registry function '" + NAME + "'");
        }
        // W5/W6 M1's shape on distinct: a positional domain= would bind (D9) while every load-time
        // reader reads the keyword only.
        GroupedAggregate.rejectPositionalAfterTarget(NAME, c,
                "domain=, filter=, group= and keep_missings=");
        List<@Nullable Expr> bound = ArgumentBinder.bind(descriptor, c);
        List<Parameter> params = descriptor.parameters();
        Expr targetArg = GroupedAggregate.slot(bound, params, GroupedAggregate.NAME_PARAMETER);
        String dataset = GroupedAggregate.readDataset(NAME,
                GroupedAggregate.slot(bound, params, GroupedAggregate.DOMAIN_PARAMETER));
        boolean foreign = dataset != null;
        Expr target = null;
        List<String> tupleColumns = null;
        if (targetArg == null)
        {
            throw new ExpressionException(NAME
                    + " requires its target: the column whose distinct values are collected, or a"
                    + " list of columns whose distinct row tuples are");
        }
        if (targetArg instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.LIST)
        {
            tupleColumns = readTupleColumns(lit, foreign);
        }
        else
        {
            target = GroupedAggregate.readTarget(NAME, targetArg, foreign);
        }
        Expr filter = GroupedAggregate.readFilter(NAME,
                GroupedAggregate.slot(bound, params, GroupedAggregate.FILTER_PARAMETER), foreign);
        List<String> group = GroupedAggregate.readGroup(NAME,
                GroupedAggregate.slot(bound, params, GroupedAggregate.GROUP_PARAMETER), foreign,
                false);
        if (tupleColumns != null && !group.isEmpty())
        {
            throw new ExpressionException(NAME + "([…]) answers the distinct row tuples of the"
                    + " whole " + (foreign ? "domain" : "dataset")
                    + " and takes no group=: collect one column per group, or add the group"
                    + " columns to the list");
        }
        Expr keepMissings = GroupedAggregate.slot(bound, params,
                GroupedAggregate.KEEP_MISSINGS_PARAMETER);
        if (keepMissings != null && group.isEmpty())
        {
            throw new ExpressionException("`" + GroupedAggregate.KEEP_MISSINGS_PARAMETER + "` on `"
                    + NAME + "` requires a non-empty `" + GroupedAggregate.GROUP_PARAMETER
                    + "=`; with no grouping key it would have no effect");
        }
        GroupKeyPolicy policy = GroupedAggregate.readPolicy(NAME, keepMissings);
        return new Spec(target, tupleColumns, dataset, filter, group, policy,
                ExpressionPrinter.print(c));
    }


    /** The tuple shape's columns: a non-empty list of bare column references. */
    private static List<String> readTupleColumns(Expr.Lit lit, boolean foreign)
    {
        List<String> out = new ArrayList<>();
        if (lit.value() instanceof List<?> items)
        {
            for (Object item : items)
            {
                if (!(item instanceof Expr e))
                {
                    throw new ExpressionException("argument '" + GroupedAggregate.NAME_PARAMETER
                            + "' of '" + NAME + "' must be a list of column references");
                }
                out.add(GroupedAggregate.columnName(NAME, GroupedAggregate.NAME_PARAMETER, e,
                        foreign));
            }
        }
        if (out.isEmpty())
        {
            throw new ExpressionException(NAME + "([]) names no column: list the columns whose"
                    + " distinct row tuples are collected");
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // Evaluation
    // -----------------------------------------------------------------------


    private static Vector evaluate(Spec spec, EvalRun run)
    {
        EvaluationContext ctx = run.ctx();
        IDataTable primary = ctx.getTable();
        if (spec.isTuple() || spec.group().isEmpty())
        {
            Object memo = ctx.getAggregateMemo().computeIfAbsent(
                    DatasetExpressionCache.keyOf(primary, spec.canonical(), ctx.getDomainPrefix()),
                    () -> datasetLevel(ctx, spec));
            return (Vector) java.util.Objects.requireNonNull(memo, "the memo never stores null");
        }
        List<String> names = GroupedAggregate.resolvePrefixes(spec.group(), ctx);
        Object memo = ctx.getAggregateMemo().computeIfAbsent(
                DatasetExpressionCache.keyOf(primary, spec.canonical(), ctx.getDomainPrefix()),
                () -> perGroup(ctx, spec, names));
        @SuppressWarnings("unchecked")
        Map<Object, List<Object>> byKey = (Map<Object, List<Object>>) java.util.Objects
                .requireNonNull(memo, "the memo never stores null");
        if (byKey.isEmpty())
        {
            // One answer for every row (XCUT PERF 2): the constant, not a per-row carrier.
            return ConstVector.of(EMPTY, () -> NAME);
        }
        // The one key derivation both sides use (GroupKeyIdentity.identityKey): the block side
        // keyed the target table's groups by it, the evaluated dataset's rows read theirs by it —
        // over the key columns resolved once, never per row (XCUT PERF 3).
        int[] keyColumns = GroupKeyIdentity.columnIndices(primary.getMetaData(), names);
        return new ComputedVector(run.rowCount(), DataValueType.STRING, row -> byKey
                .getOrDefault(GroupKeyIdentity.identityKey(primary, keyColumns, row), EMPTY));
    }


    /** The ungrouped answer: the value set or the tuple set over the whole target table. */
    private static Vector datasetLevel(EvaluationContext ctx, Spec spec)
    {
        IDataTable table = GroupedAggregate.resolveTarget(ctx, spec.dataset());
        if (table == null)
        {
            return ConstVector.of(EMPTY, () -> NAME); // Q17-a: an absent dataset matched nothing
        }
        BitSet keep = GroupedAggregate.filterMask(ctx, table, spec.filter());
        int rowCount = (int) Math.min(Integer.MAX_VALUE, table.getRowCount());
        if (spec.isTuple())
        {
            List<String> columns = GroupedAggregate
                    .resolvePrefixes(java.util.Objects.requireNonNull(spec.tupleColumns()), ctx);
            return ConstVector.of(
                    tupleSet(table, columns, keep, rowCount, ctx.getNumericExpectedColumns()),
                    () -> NAME);
        }
        IntFunction<IDataValue> cellAt = targetCells(ctx, table,
                java.util.Objects.requireNonNull(spec.target()));
        int[] rows = new int[rowCount];
        for (int r = 0; r < rowCount; r++)
        {
            rows[r] = r;
        }
        return ConstVector.of(valueSet(cellAt, rows, keep), () -> NAME);
    }


    /**
     * The target cell by absolute row of the target table: a direct column read for a bare column
     * (every corpus site), a compiled VALUE expression over the table's own context otherwise (D14
     * / D119c). An absent column, or an expression the compiler cannot serve, reads the computed
     * missing on every row — which the value set never admits, so it answers exactly as the retired
     * evaluator's "absent column ⇒ empty" did. Never {@code null}, and no cell it produces is (the
     * 0..N producer contract of {@code ScalarSemanticsComputedMissingTest}).
     */
    private static IntFunction<IDataValue> targetCells(EvaluationContext ctx, IDataTable table,
            Expr target)
    {
        DataTableMeta meta = table.getMetaData();
        if (target instanceof Expr.Ref ref)
        {
            int colIdx = meta.getColumnIndex(GroupedAggregate.resolvePrefix(ref.name(), ctx));
            if (colIdx < 0)
            {
                return ignored -> ScalarSemantics.computedMissing();
            }
            IDataTableColumn column = table.getColumn(colIdx);
            return column::getDataValue;
        }
        Vector v = ExprCompiler.evaluateValueExpression(target,
                GroupedAggregate.tableContext(ctx, table, List.of(target)));
        if (v == null)
        {
            return ignored -> ScalarSemantics.computedMissing();
        }
        return row -> v.value(row).cell();
    }


    /**
     * The distinct present, non-blank values of the target over {@code rows} (those the filter
     * keeps), each as its text, in row order.
     */
    private static List<Object> valueSet(IntFunction<IDataValue> cellAt, int[] rows,
            @Nullable BitSet keep)
    {
        Set<Object> seen = new LinkedHashSet<>();
        for (int r : rows)
        {
            if (keep != null && !keep.get(r))
            {
                continue;
            }
            IDataValue dv = cellAt.apply(r);
            if (dv.isMissingOrInvalid())
            {
                continue;
            }
            String val = dv.getValueAsString();
            // CDISC convention: an empty string is not a value (the retired evaluator's
            // contract, carried verbatim — D-W7-2).
            if (val != null && !val.isEmpty())
            {
                seen.add(val);
            }
        }
        return seen.isEmpty() ? EMPTY : List.copyOf(seen);
    }


    /**
     * The distinct row tuples of {@code columns} over the kept rows of {@code table}, each an
     * unmodifiable list of key components (see the class comment), in first-seen order.
     */
    private static List<Object> tupleSet(IDataTable table, List<String> columns,
            @Nullable BitSet keep, int rowCount, Set<String> numericExpectedColumns)
    {
        DataTableMeta meta = table.getMetaData();
        int n = columns.size();
        int[] idx = new int[n];
        boolean[] joinToken = new boolean[n];
        for (int c = 0; c < n; c++)
        {
            idx[c] = meta.getColumnIndex(columns.get(c));
            joinToken[c] = JOIN_TOKEN_COLUMN.equals(columns.get(c));
        }
        Set<List<Object>> seen = new LinkedHashSet<>();
        for (int r = 0; r < rowCount; r++)
        {
            if (keep != null && !keep.get(r))
            {
                continue;
            }
            List<Object> tuple = new ArrayList<>(n);
            for (int c = 0; c < n; c++)
            {
                int col = idx[c];
                if (col < 0)
                {
                    // NVE: an absent column is a CONSTANT column of its type's default — the same
                    // rule the tuple(…) probe side's operand plan applies ("" for character, MIS
                    // for a numeric expectation), so the two sides agree.
                    tuple.add(numericExpectedColumns.contains(columns.get(c))
                            ? new Primitives.MissingMember(MissingValue.MIS)
                            : "");
                    continue;
                }
                IDataValue dv = table.getColumn(col).getDataValue(r);
                // A MissingValue component keeps its identity (D11 / D34 #5-2 / NVE §4.4) as a
                // MissingMember, exactly as BuiltinFunctions.tupleKey builds the probe side —
                // never "" (the present blank's key) and never normalised as a join token.
                MissingValue missing = TypedValue.missingIdentityOf(dv);
                if (missing != null)
                {
                    tuple.add(new Primitives.MissingMember(missing));
                    continue;
                }
                String cell = dv.getValueAsString();
                tuple.add(joinToken[c] ? ChildMatchIndex.normalizeJoinToken(cell, true) : cell);
            }
            seen.add(java.util.Collections.unmodifiableList(tuple));
        }
        return seen.isEmpty() ? EMPTY : new ArrayList<>(seen);
    }


    /** The grouped value sets over the target table: one list per group key. */
    private static Map<Object, List<Object>> perGroup(EvaluationContext ctx, Spec spec,
            List<String> names)
    {
        IDataTable primary = ctx.getTable();
        IDataTable table = GroupedAggregate.resolveTarget(ctx, spec.dataset());
        if (table == null)
        {
            return Map.of();
        }
        IntFunction<IDataValue> cellAt = targetCells(ctx, table,
                java.util.Objects.requireNonNull(spec.target()));
        BitSet keep = GroupedAggregate.filterMask(ctx, table, spec.filter());
        // EC-44: absent group columns are ignored; all absent ⇒ the dataset is one group.
        IndexHelper.Grouping grouping = IndexHelper.groupByPresent(table, names,
                GroupedAggregate.logContext(ctx, NAME), spec.policy());
        if (grouping == null)
        {
            return Map.of(); // an unexpanded $-ref in the group list — the reader refuses those
        }
        // Every block claims its key with a value (never skip): a key is never shared silently
        // (the tripwire), and a row of the block always reads its own block's answer.
        IndexHelper.BlockResults results = new IndexHelper.BlockResults(grouping);
        for (IndexHelper.GroupBlock block : grouping.blocks())
        {
            List<Object> values = valueSet(cellAt, block.rows(), keep);
            // Register NNL §1 — the birth site of a per-row list value.
            ListValueGuard.requireNoNullElement(values, () -> NAME + " group " + block.key());
            results.put(block, values);
        }
        if (table != primary)
        {
            // GKI Q2: a Char/Num key pair between the grouped table and the evaluated one ERRORs
            // the rule instead of silently matching no row. Once per evaluation, never per row.
            GroupKeyIdentity.requireCompatibleKeyColumns(table, names, primary, names);
        }
        Map<Object, List<Object>> byKey = new LinkedHashMap<>();
        results.results().forEach((key, value) ->
        {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) value;
            byKey.put(key, list);
        });
        return byKey;
    }
}
