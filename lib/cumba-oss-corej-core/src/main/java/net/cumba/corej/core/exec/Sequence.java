package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.GroupKeyPolicy;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * ⭐ Runbook W1b RO ({@code PLAN-engine-gap-row-ordinal} §5 R1, ruled Q1 / Q3; owner O17) — the
 * registry function {@code sequence(name, domain=, filter=, group=, by=[asc(COL), desc(COL)],
 * keep_missings=)}: per row, its group's {@code name} values in sort-key order, duplicates kept, a
 * missing cell at its position — the ordered sibling of {@link Distinct}{@code (name, group=)} on
 * the same grouped plan ({@link GroupedAggregate}), so {@code domain=}, {@code filter=}, the splice
 * of {@code group=}, an absent group column (EC-44), the no-group row (the empty list) and the memo
 * behave as there. Without {@code group=} it is one dataset-level list in that order.
 *
 * <p>
 * The order is {@link SortKeys#sortRowsPlacingNulls}' — {@code is_sorted_by}'s order (runbook W2
 * fold, R5 PC-1; O16 "nulls= and direction honoured" in both readers): {@link CellOrder} per key,
 * each key classified over its cells (owner O22: a numeric key numerically, any other ISO cells by
 * (lower bound, text) with a four-digit cell a year — owner O23: {@code 2015} before
 * {@code 2015-01} — then text; a total order), {@code desc} reversing it; a blank or missing key
 * cell placed by the key's {@code nulls=} — FIRST unless {@code nulls="last"}, under {@code desc}
 * too (W2-D3), two of them tied; a tie keeping file order (stable), no {@code by=} file order. An
 * element is a present cell's text (a numeric cell its canonical text, as {@code distinct} lists
 * it), a missing cell its {@link net.cumba.corej.core.expr.eval.Primitives.MissingMember} (D81, as
 * {@code colref(list)} keeps it). The list is read by {@code is_prefix_of}, {@code is_increasing},
 * {@code collapse_runs}, {@code count} and the membership operators.
 * </p>
 */
public final class Sequence
{

    /** The function name. */
    public static final String NAME = "sequence";

    private static final List<Object> EMPTY = List.of();

    private Sequence()
    {
    }


    /**
     * Whether {@code c} is a grouped {@code sequence} call (authors {@code group=}).
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
     * A {@code sequence} call as authored, read at load.
     *
     * @param target
     *            the column target — a column reference or a VALUE expression over the target table
     * @param dataset
     *            the {@code domain=} dataset, or {@code null} for the evaluated dataset
     * @param filter
     *            the boolean filter over the target table's own columns, or {@code null}
     * @param members
     *            the {@code group=} members ({@link GroupSplice.Member}); empty when ungrouped
     * @param keys
     *            the sort keys ({@link SortKeys.Key}); empty for file order
     * @param policy
     *            the missing-key policy
     * @param canonical
     *            the canonical call text (the memo key)
     */
    public record Spec(Expr target, @Nullable String dataset, @Nullable Expr filter,
            List<GroupSplice.Member> members, List<SortKeys.Key> keys, GroupKeyPolicy policy,
            String canonical)
    {

        public Spec
        {
            members = List.copyOf(members);
            keys = List.copyOf(keys);
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
            return Sequence.evaluate(this, run);
        }
    }

    /**
     * Reads the call at load time, refusing every shape the plan cannot mean (the reader's own
     * message is the rule's load error): a list target ({@code sequence} keeps one column's values
     * in order — {@code distinct([...])} is the tuple set), a string literal target (R1),
     * {@code keep_missings} without {@code group=}, the {@code group=} shapes
     * {@link GroupedAggregate#readGroup} refuses, a sort key that is not a column, a descriptor of
     * one or a spliceable member ({@link SortKeys#read}).
     *
     * @param c
     *            the authored call
     * @return the read
     * @throws ExpressionException
     *             when the call is malformed
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
        GroupedAggregate.rejectPositionalAfterTarget(NAME, c,
                "domain=, filter=, group=, by= and keep_missings=");
        List<@Nullable Expr> bound = ArgumentBinder.bind(descriptor, c);
        List<Parameter> params = descriptor.parameters();
        Expr targetArg = GroupedAggregate.slot(bound, params, GroupedAggregate.NAME_PARAMETER);
        String dataset = GroupedAggregate.readDataset(NAME,
                GroupedAggregate.slot(bound, params, GroupedAggregate.DOMAIN_PARAMETER));
        if (targetArg == null)
        {
            throw new ExpressionException(
                    NAME + " requires its target: the column whose values are listed in order");
        }
        if (targetArg instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.LIST)
        {
            throw new ExpressionException(NAME + " takes one column, not a list of columns —"
                    + " distinct([...]) collects row tuples; sequence orders one column's values");
        }
        Expr target = GroupedAggregate
                .stripQualifier(GroupedAggregate.readTarget(NAME, targetArg, dataset), dataset);
        Expr filter = GroupedAggregate.readFilter(NAME,
                GroupedAggregate.slot(bound, params, GroupedAggregate.FILTER_PARAMETER), dataset);
        filter = filter == null ? null : GroupedAggregate.stripQualifier(filter, dataset);
        List<GroupSplice.Member> members = GroupedAggregate.readGroup(NAME,
                GroupedAggregate.slot(bound, params, GroupedAggregate.GROUP_PARAMETER), dataset,
                false);
        List<SortKeys.Key> keys = SortKeys.read(NAME,
                GroupedAggregate.slot(bound, params, SortKeys.PARAMETER));
        for (SortKeys.Key key : keys)
        {
            if (key.member() instanceof GroupSplice.Name name)
            {
                GroupedAggregate.columnName(NAME, SortKeys.PARAMETER, new Expr.Ref(name.name(),
                        net.cumba.corej.core.expr.OperandClassifier.classify(name.name(), -1)),
                        dataset);
            }
        }
        Expr keepMissings = GroupedAggregate.slot(bound, params,
                GroupedAggregate.KEEP_MISSINGS_PARAMETER);
        if (keepMissings != null && members.isEmpty())
        {
            throw new ExpressionException("`" + GroupedAggregate.KEEP_MISSINGS_PARAMETER + "` on `"
                    + NAME + "` requires a non-empty `" + GroupedAggregate.GROUP_PARAMETER
                    + "=`; with no grouping key it would have no effect");
        }
        GroupKeyPolicy policy = GroupedAggregate.readPolicy(NAME, keepMissings);
        return new Spec(target, dataset, filter, members, keys, policy, ExpressionPrinter.print(c));
    }

    // -----------------------------------------------------------------------
    // Evaluation (the shape of Distinct.evaluate, W7)
    // -----------------------------------------------------------------------


    private static Vector evaluate(Spec spec, EvalRun run)
    {
        EvaluationContext ctx = run.ctx();
        IDataTable primary = ctx.getTable();
        boolean foreign = spec.dataset() != null;
        List<SortKeys.ResolvedKey> keys = resolvedKeys(spec, ctx, foreign);
        if (spec.members().isEmpty())
        {
            Object memo = ctx.getAggregateMemo()
                    .computeIfAbsent(DatasetExpressionCache.keyOf(primary,
                            memoKey(spec.canonical(), keys), ctx.getDomainPrefix()),
                            () -> datasetLevel(ctx, spec, keys));
            return (Vector) java.util.Objects.requireNonNull(memo, "the memo never stores null");
        }
        List<String> names = GroupedAggregate.groupNames(NAME, spec.members(), ctx, foreign);
        Object memo = ctx.getAggregateMemo()
                .computeIfAbsent(
                        DatasetExpressionCache.keyOf(primary,
                                memoKey(GroupedAggregate.memoKey(spec.canonical(), spec.members(),
                                        names), keys),
                                ctx.getDomainPrefix()),
                        () -> perGroup(ctx, spec, names, keys));
        GroupedAggregate.KeyedLists lists = (GroupedAggregate.KeyedLists) java.util.Objects
                .requireNonNull(memo, "the memo never stores null");
        Map<Object, List<Object>> byKey = lists.byKey();
        // Under domain= the grouping decided its SidedKey (REG-Q3 / QK14 / QK16) inside the memo,
        // before forming any group, and carries the record side's keyer.
        GroupedAggregate.RowKey keyer = lists.keyer();
        if (byKey.isEmpty())
        {
            return ConstVector.of(EMPTY, () -> NAME);
        }
        if (keyer != null)
        {
            DataTableMeta meta = primary.getMetaData();
            return new ComputedVector(run.rowCount(), DataValueType.STRING,
                    row -> byKey.getOrDefault(keyer.of(meta, primary, row), EMPTY));
        }
        IDataTable keyTable = GroupReadTable.of(ctx, names);
        int[] keyColumns = GroupKeyIdentity.columnIndices(keyTable.getMetaData(), names);
        return new ComputedVector(run.rowCount(), DataValueType.STRING, row -> byKey
                .getOrDefault(GroupKeyIdentity.identityKey(keyTable, keyColumns, row), EMPTY));
    }


    /** The memo key with the resolved sort keys (a spliced key may differ per context, D-W6-8). */
    private static String memoKey(String base, List<SortKeys.ResolvedKey> keys)
    {
        return keys.isEmpty() ? base : base + " by=" + keys;
    }


    private static List<SortKeys.ResolvedKey> resolvedKeys(Spec spec, EvaluationContext ctx,
            boolean foreign)
    {
        List<SortKeys.ResolvedKey> resolved = SortKeys.resolve(NAME, spec.keys(), ctx, foreign);
        List<SortKeys.ResolvedKey> out = new ArrayList<>(resolved.size());
        for (SortKeys.ResolvedKey key : resolved)
        {
            // R5 PC-1: the key's nulls= rides along (it used to be dropped here)
            out.add(new SortKeys.ResolvedKey(GroupedAggregate.resolvePrefix(key.column(), ctx),
                    key.descending(), key.nulls()));
        }
        return out;
    }


    /** The ungrouped answer: the ordered values over the whole target table. */
    private static Vector datasetLevel(EvaluationContext ctx, Spec spec,
            List<SortKeys.ResolvedKey> keys)
    {
        boolean foreign = spec.dataset() != null;
        IDataTable table = GroupedAggregate.resolveTarget(ctx, spec.dataset(),
                GroupedAggregate.targetNames(spec.target(), SortKeys.columns(keys)));
        if (table == null)
        {
            return ConstVector.of(EMPTY, () -> NAME);
        }
        BitSet keep = GroupedAggregate.filterMask(ctx, table, spec.filter(), foreign);
        int rowCount = (int) Math.min(Integer.MAX_VALUE, table.getRowCount());
        int[] rows = new int[rowCount];
        for (int r = 0; r < rowCount; r++)
        {
            rows[r] = r;
        }
        List<Object> values = ordered(targetCells(ctx, table, spec.target(), foreign), table, keys,
                rows, keep);
        ListValueGuard.requireNoNullElement(values, () -> NAME);
        return ConstVector.of(values, () -> NAME);
    }


    /** The grouped ordered lists over the target table: one list per group key. */
    private static GroupedAggregate.KeyedLists perGroup(EvaluationContext ctx, Spec spec,
            List<String> names, List<SortKeys.ResolvedKey> keys)
    {
        boolean foreign = spec.dataset() != null;
        List<String> read = new ArrayList<>(GroupedAggregate.targetNames(spec.target(), names));
        read.addAll(SortKeys.columns(keys));
        IDataTable table = GroupedAggregate.resolveTarget(ctx, spec.dataset(), read);
        if (table == null)
        {
            return GroupedAggregate.KeyedLists.NONE;
        }
        IntFunction<IDataValue> cellAt = targetCells(ctx, table, spec.target(), foreign);
        BitSet keep = GroupedAggregate.filterMask(ctx, table, spec.filter(), foreign);
        GroupedAggregate.SidedKey sided = foreign
                ? GroupedAggregate.sidedKey(ctx, NAME, table, names)
                : null;
        IndexHelper.Grouping grouping = IndexHelper.groupByPresent(table,
                sided != null ? sided.groupedNames() : names,
                GroupedAggregate.logContext(ctx, NAME), spec.policy());
        if (grouping == null)
        {
            return GroupedAggregate.KeyedLists.NONE;
        }
        IndexHelper.BlockResults results = new IndexHelper.BlockResults(grouping);
        for (IndexHelper.GroupBlock block : grouping.blocks())
        {
            List<Object> values = ordered(cellAt, table, keys, block.rows(), keep);
            ListValueGuard.requireNoNullElement(values, () -> NAME + " group " + block.key());
            results.put(block, values);
        }
        if (sided != null)
        {
            // GKI Q2: a Char/Num key pair between the grouped table and the evaluated one ERRORs
            // the rule instead of silently matching no row. Once per evaluation, never per row.
            sided.requireCompatible(table);
        }
        Map<Object, List<Object>> byKey = new LinkedHashMap<>();
        results.results().forEach((key, value) ->
        {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) value;
            byKey.put(key, list);
        });
        return new GroupedAggregate.KeyedLists(byKey, sided != null ? sided.keyer() : null);
    }


    /**
     * The target's values over the kept rows, in key order — the stable sort over the rows of the
     * block, so a tie keeps file order and no key is file order.
     */
    private static List<Object> ordered(IntFunction<IDataValue> cellAt, IDataTable table,
            List<SortKeys.ResolvedKey> keys, int[] rows, @Nullable BitSet keep)
    {
        int[] kept = new int[rows.length];
        int size = 0;
        for (int r : rows)
        {
            if (keep == null || keep.get(r))
            {
                kept[size++] = r;
            }
        }
        if (size < kept.length)
        {
            kept = java.util.Arrays.copyOf(kept, size);
        }
        if (!keys.isEmpty())
        {
            kept = SortKeys.sortRowsPlacingNulls(table, keys, kept);
        }
        List<Object> out = new ArrayList<>(kept.length);
        for (int r : kept)
        {
            IDataValue dv = cellAt.apply(r);
            Object value = CellOrder.valueOf(dv);
            out.add(value instanceof Number n ? ExprCompiler.canonicalNumberText(n) : value);
        }
        return out.isEmpty() ? EMPTY : List.copyOf(out);
    }


    /** As {@code Distinct.targetCells}: a direct column read, or a compiled VALUE expression. */
    private static IntFunction<IDataValue> targetCells(EvaluationContext ctx, IDataTable table,
            Expr target, boolean foreign)
    {
        DataTableMeta meta = table.getMetaData();
        if (target instanceof Expr.Ref ref
                && ref.kind() != net.cumba.corej.core.expr.OperandKind.OPERATION_REF)
        {
            // a $-binding target (W1b DT) is a computed target, pre-resolved — never a column
            // named `$x`
            int colIdx = meta.getColumnIndex(GroupedAggregate.resolvePrefix(ref.name(), ctx));
            if (colIdx < 0)
            {
                return ignored -> ScalarSemantics.computedMissing();
            }
            IDataTableColumn column = table.getColumn(colIdx);
            return column::getDataValue;
        }
        Vector v = ExprCompiler.evaluateValueExpression(target,
                GroupedAggregate.targetContext(ctx, table, foreign, List.of(target)));
        if (v == null)
        {
            return ignored -> ScalarSemantics.computedMissing();
        }
        return row -> v.value(row).cell();
    }
}
