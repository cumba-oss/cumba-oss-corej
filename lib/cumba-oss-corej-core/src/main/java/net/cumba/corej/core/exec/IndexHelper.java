package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringJoiner;

import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.index.DataTableIndexFactory;
import net.cumba.datatable.index.IDataTableIndex;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.view.IDataTableView;
import org.jspecify.annotations.Nullable;

/**
 * Shared utilities for using {@link IDataTableIndex} in the CDISC CORE engine. Centralizes index
 * creation, block-to-row extraction, missing-key detection, and GroupedResult-compatible key
 * building.
 */
final class IndexHelper
{

    private static final System.Logger LOGGER = System.getLogger(IndexHelper.class.getName());

    private static final String KEY_SEPARATOR = "\0";

    private IndexHelper()
    {
    }


    /**
     * Create a hash-based index on the given columns. All columns must exist in the table; returns
     * {@code null} if any column is not found.
     */
    static @Nullable IDataTableIndex createIndex(IDataTable table, String... columns)
    {
        DataTableMeta meta = table.getMetaData();
        for (String col : columns)
        {
            if (meta.getColumnIndex(col) < 0)
            {
                return null;
            }
        }
        return DataTableIndexFactory.createInstance().createIndex(table, columns);
    }

    /**
     * One group produced by {@link #groupByPresent}: the group key — built over the <b>full
     * declared</b> column list, so an absent column contributes {@code ""} exactly as
     * {@link GroupedResult#buildKey(DataTableMeta, IDataTable, List, long)} does on the per-row
     * lookup side — and the absolute row indices of the group's members.
     *
     * <p>
     * A class rather than a record because {@code rows} is an {@code int[]}: the row-index arrays
     * are the same shape {@link GroupSemantics#partition} already hands around, and array
     * components would give a record broken {@code equals}/{@code hashCode} semantics (Error Prone
     * {@code ArrayRecordComponent}). Instances are never compared or hashed.
     * </p>
     */
    static final class GroupBlock
    {

        private final String key;

        private final int[] rows;

        GroupBlock(String aKey, int[] aRows)
        {
            key = aKey;
            rows = aRows;
        }


        /**
         * The {@code NUL}-joined group key, in {@link GroupedResult}'s encoding.
         */
        String key()
        {
            return key;
        }


        /**
         * The absolute row indices belonging to this group. Not defensively copied —
         * package-private and consumed read-only by the grouped evaluators, exactly as
         * {@link IndexHelper#blockRows} is elsewhere.
         */
        int[] rows()
        {
            return rows;
        }
    }


    /**
     * The outcome of {@link #groupByPresent}: which of the declared group columns actually exist on
     * the table, and the resulting groups.
     *
     * @param declared
     *            the group columns the operation asked for
     * @param present
     *            the subset that exists on the table — the columns the index was built over
     * @param blocks
     *            the groups, in index-block order (a single whole-table group when {@code present}
     *            is empty)
     */
    record Grouping(List<String> declared, List<String> present, List<GroupBlock> blocks)
    {

        /**
         * The declared columns that are absent from the table and were therefore ignored.
         */
        List<String> dropped()
        {
            if (present.size() == declared.size())
            {
                return List.of();
            }
            List<String> out = new ArrayList<>(declared);
            out.removeAll(present);
            return out;
        }
    }

    /**
     * EC-44 — partition {@code table} by {@code groupCols}, <b>ignoring the columns that are absent
     * from the table</b>.
     *
     * <p>
     * The contract: <em>an absent column cannot differentiate any row from any other, so every row
     * is homogeneous with respect to that key and it partitions nothing.</em> Dropping it is
     * therefore exact, not an approximation — and when <b>no</b> declared column is present the
     * whole table is one group, mirroring {@code RuleRunner.executeGrouped} (a pandas
     * {@code groupby} of an empty key list) and the fork's {@code actions.py} fallback. The
     * alternative — the pre-EC-44 all-or-nothing {@link #createIndex} — silently produced a
     * {@code null} operation result, and the rule then reported SUCCESS with no findings on every
     * study that simply did not collect an optional grouping variable.
     * </p>
     *
     * <p>
     * <b>Missingness is not absence</b> and is deliberately untouched: a <em>missing value</em> in
     * a column that <em>exists</em> is a key that is unknown relative to known peers, and the
     * callers' existing handling of that (a {@code ""} key here, {@link #isBlockKeyMissing} on the
     * {@code GroupSemantics} paths) still applies to the surviving columns.
     * </p>
     *
     * <p>
     * Keys are built over the <b>full declared</b> {@code groupCols}, so they stay compatible with
     * {@link GroupedResult#getForRow} without any change to {@link GroupedResult}: both sides
     * render an absent column as {@code ""}.
     * </p>
     *
     * <p>
     * <b>Not every unknown name is an absent column.</b> An entry still carrying the {@code $}
     * sigil is an operation reference that {@code OperationExecutor.expandGroupRefs} could not
     * expand into column names — a resolution failure in the rule's operation chain, not a fact
     * about the study's data. Silently widening the grouping there would let a broken chain produce
     * dataset-wide aggregates, so this returns {@code null} and the caller degrades exactly as it
     * did before EC-44.
     * </p>
     *
     * @param table
     *            the dataset to partition
     * @param groupCols
     *            the declared group columns (already {@code --}-resolved and {@code $}-expanded by
     *            {@code OperationExecutor}). May be <b>empty</b> (EC-45 §1.3(3)): an operation with
     *            no {@code group:} at all is the dataset-wide reading, which falls into the same
     *            "nothing survives" branch as an all-absent list and yields one whole-table block
     * @param context
     *            an optional label for the INFO log emitted when columns are dropped (the operation
     *            id, prefixed with the rule id when known)
     * @return the grouping, empty only for an empty table; {@code null} when a group entry is an
     *         unexpanded {@code $}-reference
     */
    static @Nullable Grouping groupByPresent(IDataTable table, List<String> groupCols,
            @Nullable String context)
    {
        return groupByPresent(table, groupCols, context, GroupKeyPolicy.KEEP_MISSING_KEYS);
    }


    /**
     * {@link #groupByPresent(IDataTable, List, String)} under an explicit {@link GroupKeyPolicy}.
     *
     * <p>
     * The three-argument overload is this one under {@link GroupKeyPolicy#KEEP_MISSING_KEYS}, the
     * shipped behaviour of every {@code Operations[].group:} evaluator except
     * {@code is_last_in_group}: a blank key component renders its identity in the reporting key
     * ({@code ""} for an empty cell, the marker token for a genuine missing — {@code W38-A1} / Fix
     * #249) and the group is still formed. With {@link GroupKeyPolicy#keepMissings()} {@code false}
     * a block whose representative row carries a blank key component is dropped instead, which is
     * what lets the {@code group:} surface answer an authored {@code keep_missings: false} — the
     * half of the fold/discard asymmetry that lives here.
     * </p>
     *
     * @param table
     *            the dataset to partition
     * @param groupCols
     *            the declared group columns
     * @param context
     *            an optional label for the INFO log emitted when columns are dropped
     * @param policy
     *            the grouping-key policy
     * @return the grouping, or {@code null} when a group entry is an unexpanded {@code $}-reference
     */
    static @Nullable Grouping groupByPresent(IDataTable table, List<String> groupCols,
            @Nullable String context, GroupKeyPolicy policy)
    {
        for (String col : groupCols)
        {
            if (col != null && col.startsWith("$"))
            {
                return null;
            }
        }
        DataTableMeta meta = table.getMetaData();
        List<String> present = new ArrayList<>(groupCols.size());
        for (String col : groupCols)
        {
            if (col != null && meta.getColumnIndex(col) >= 0)
            {
                present.add(col);
            }
        }
        long rowCountL = table.getRowCount();
        Grouping grouping;
        if (present.isEmpty())
        {
            // Every declared column is absent ⇒ no row is distinguishable from any other ⇒ one
            // group over the whole table. Its key is what GroupedResult.buildKey computes for a
            // row of a table carrying none of the columns: one empty component per declared
            // column.
            List<GroupBlock> blocks = rowCountL == 0 ? List.of()
                    : List.of(new GroupBlock(
                            GroupedResult
                                    .buildKey(Collections.nCopies(groupCols.size(), (String) null)),
                            allRows(rowCountL)));
            grouping = new Grouping(List.copyOf(groupCols), List.of(), blocks);
        }
        else
        {
            IDataTableIndex index = DataTableIndexFactory.createInstance().createIndex(table,
                    present.toArray(String[]::new));
            int[] presentIdx = new int[present.size()];
            for (int i = 0; i < present.size(); i++)
            {
                presentIdx[i] = meta.getColumnIndex(present.get(i));
            }
            long blockCount = index.getBlockCount();
            List<GroupBlock> blocks = new ArrayList<>((int) blockCount);
            for (long b = 0; b < blockCount; b++)
            {
                IDataTableView block = index.getBlock(b);
                if (!policy.keepMissings() && isBlockKeyMissing(block, table, presentIdx, policy))
                {
                    continue;
                }
                blocks.add(new GroupBlock(buildGroupKey(block, table, meta, groupCols, policy),
                        blockRows(block, table)));
            }
            grouping = new Grouping(List.copyOf(groupCols), List.copyOf(present), blocks);
        }
        logDropped(grouping, context);
        return grouping;
    }


    private static void logDropped(Grouping grouping, @Nullable String context)
    {
        List<String> dropped = grouping.dropped();
        if (dropped.isEmpty() || !LOGGER.isLoggable(System.Logger.Level.INFO))
        {
            return;
        }
        LOGGER.log(System.Logger.Level.INFO,
                "[{0}] group column(s) {1} absent from the dataset — ignored for grouping; "
                        + "grouping by {2}",
                context != null ? context : "?", dropped,
                grouping.present().isEmpty() ? "the whole dataset (one group)"
                        : grouping.present());
    }


    private static int[] allRows(long rowCount)
    {
        int n = Math.toIntExact(rowCount);
        int[] rows = new int[n];
        for (int i = 0; i < n; i++)
        {
            rows[i] = i;
        }
        return rows;
    }


    /**
     * Extract real row indices from an {@link IDataTableView} block as an int array.
     */
    static int[] blockRows(IDataTableView block, IDataTable table)
    {
        int count = (int) block.getRowCount(table);
        int[] rows = new int[count];
        for (int i = 0; i < count; i++)
        {
            rows[i] = (int) block.getRealRow(table, i);
        }
        return rows;
    }


    /**
     * Check whether the representative row (first row) of a block has any blank value in the given
     * key columns — i.e. <b>whether the group should be formed at all</b>.
     *
     * <p>
     * ⚠⚠ <b>This is not {@link #buildGroupKey} and must never be merged with it.</b> This decides
     * <em>existence</em>; {@code buildGroupKey} builds the <em>reporting key</em> of a group that
     * has already been formed. They answer different questions and merely happen to read the same
     * cells through the same {@link GroupKeyPolicy#isBlankKeyComponent} predicate. A caller that
     * wants the blank kept asks {@link GroupKeyPolicy#keepMissings()} and simply does not call this
     * — the predicate itself is disposition-free.
     * </p>
     *
     * <p>
     * ⚑ <b>The representative-row invariant.</b> Reading only the block's <b>first</b> row is exact
     * <em>only because</em> the default index groups by raw-value equality
     * ({@code DataTableIndexFactoryImpl} / {@code GroupRepMatcher}), so every row of a block agrees
     * with the first on every key column. The factory is resolved from a <b>system property</b> and
     * is pluggable: an alternative implementation keying on {@code getValueAsString()} would fold
     * distinct missing markers into one block and break this invariant <em>silently</em> — the
     * block would then contain rows that disagree about blankness and the verdict would depend on
     * physical record order. The coupling is real, correct today, and was undocumented until this
     * refactor moved the code that depends on it.
     * </p>
     *
     * @param block
     *            the index block
     * @param table
     *            the source table
     * @param keyColIndices
     *            column indices to check
     * @param policy
     *            supplies the blankness notion via
     *            {@link GroupKeyPolicy#isBlankKeyComponent(IDataValue)}
     * @return {@code true} if any key column value is blank in the first row
     */
    static boolean isBlockKeyMissing(IDataTableView block, IDataTable table, int[] keyColIndices,
            GroupKeyPolicy policy)
    {
        long firstRow = block.getRealRow(table, 0);
        for (int colIdx : keyColIndices)
        {
            IDataValue dv = table.getColumn(colIdx).getDataValue(firstRow);
            if (policy.isBlankKeyComponent(dv))
            {
                return true;
            }
        }
        return false;
    }


    /**
     * {@link #isBlockKeyMissing(IDataTableView, IDataTable, int[], GroupKeyPolicy)} under
     * {@link GroupKeyPolicy#DROP_MISSING_KEYS} — the shipped notion, where a genuine
     * missing/invalid marker <em>or</em> {@code ""} is blank ({@code W32-E3} / Fix #241 moved the
     * policy's blankness to {@code MISSING_OR_EMPTY}; the pre-ruling notion that {@code ""} was a
     * real key here is retired).
     */
    static boolean isBlockKeyMissing(IDataTableView block, IDataTable table, int[] keyColIndices)
    {
        return isBlockKeyMissing(block, table, keyColIndices, GroupKeyPolicy.DROP_MISSING_KEYS);
    }


    /**
     * Build a {@link GroupedResult}-compatible string key from the representative row (first row)
     * of a block. Uses the same format as {@link GroupedResult#buildKey} — ⚑ <b>lockstep</b>: this
     * is the block-side half of the key encoding and {@code GroupedResult.buildKey} the per-row
     * lookup half; both render each component through
     * {@link GroupKeyPolicy.KeyPart#reportingForm()} from the same
     * {@link GroupKeyPolicy#keyPart(IDataValue)} classification, so a blank-keyed row's lookup
     * always lands on its own block's key.
     *
     * <p>
     * Since {@code W38-A1} (Fix #249) a blank component renders its <em>identity</em> — {@code ""}
     * for an empty cell, the SOH-prefixed marker token for a genuine missing — so two groups the
     * grouping distinguishes are never reported under one key. ⚠⚠ The rendering is presentation,
     * derived from the {@code KeyPart}; it must never be re-parsed to recover identity. An
     * <em>absent</em> column still contributes {@code ""} (the EC-44 contract: absent-column keys
     * stay compatible with {@code GroupedResult.getForRow}).
     * </p>
     *
     * <p>
     * ⚠⚠ <b>Not {@link #isBlockKeyMissing}.</b> This <em>keeps</em> the group and only has to name
     * it. See that method's warning; the pair is deliberate.
     * </p>
     *
     * @param policy
     *            supplies the blankness notion via {@link GroupKeyPolicy#keyPart(IDataValue)}
     */
    static String buildGroupKey(IDataTableView block, IDataTable table, DataTableMeta meta,
            List<String> groupCols, GroupKeyPolicy policy)
    {
        long firstRow = block.getRealRow(table, 0);
        StringJoiner sj = new StringJoiner(KEY_SEPARATOR);
        for (String col : groupCols)
        {
            int idx = meta.getColumnIndex(col);
            if (idx < 0)
            {
                sj.add("");
            }
            else
            {
                IDataValue dv = table.getColumn(idx).getDataValue(firstRow);
                sj.add(policy.keyPart(dv).reportingForm());
            }
        }
        return sj.toString();
    }


    /**
     * {@link #buildGroupKey(IDataTableView, IDataTable, DataTableMeta, List, GroupKeyPolicy)} under
     * {@link GroupKeyPolicy#KEEP_MISSING_KEYS} — the shipped notion for the reporting key.
     */
    static String buildGroupKey(IDataTableView block, IDataTable table, DataTableMeta meta,
            List<String> groupCols)
    {
        return buildGroupKey(block, table, meta, groupCols, GroupKeyPolicy.KEEP_MISSING_KEYS);
    }


    /**
     * Resolve column names to column indices. Returns {@code null} if any column is not found in
     * the table metadata.
     */
    static int @Nullable [] resolveColumnIndices(DataTableMeta meta, List<String> colNames)
    {
        int[] indices = new int[colNames.size()];
        for (int i = 0; i < colNames.size(); i++)
        {
            int idx = meta.getColumnIndex(colNames.get(i));
            if (idx < 0)
            {
                return null;
            }
            indices[i] = idx;
        }
        return indices;
    }


    /**
     * Resolve column names to column indices. Returns {@code null} if any column is not found in
     * the table metadata.
     */
    static int @Nullable [] resolveColumnIndices(DataTableMeta meta, String... colNames)
    {
        int[] indices = new int[colNames.length];
        for (int i = 0; i < colNames.length; i++)
        {
            int idx = meta.getColumnIndex(colNames[i]);
            if (idx < 0)
            {
                return null;
            }
            indices[i] = idx;
        }
        return indices;
    }

}
