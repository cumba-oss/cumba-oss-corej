package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.index.DataTableIndexFactory;
import net.cumba.datatable.index.IDataTableIndex;
import net.cumba.datatable.values.GroupKeyPolicy;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.view.IDataTableView;
import org.jspecify.annotations.Nullable;

/**
 * Shared utilities for using {@link IDataTableIndex} in the CDISC CORE engine. Centralizes index
 * creation, block-to-row extraction, missing-key detection, lookup-compatible key building, and the
 * block-keyed result map ({@link BlockResults}) that refuses two blocks with one key.
 */
final class IndexHelper
{

    private static final System.Logger LOGGER = System.getLogger(IndexHelper.class.getName());

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
     * One group produced by {@link #groupByPresent}: the group key — the
     * {@link GroupKeyIdentity#identityKey identity key} over the <b>full declared</b> column list,
     * so an absent column contributes {@code ""} exactly as it does on the per-row lookup side —
     * and the absolute row indices of the group's members.
     *
     * <p>
     * A class rather than a record because {@code rows} is an {@code int[]}: the row-index arrays
     * are the same shape {@link GroupSemantics#group} already hands around, and array components
     * would give a record broken {@code equals}/{@code hashCode} semantics (Error Prone
     * {@code ArrayRecordComponent}). Instances are never compared or hashed.
     * </p>
     */
    static final class GroupBlock
    {

        private final Object key;

        private final int[] rows;

        GroupBlock(Object aKey, int[] aRows)
        {
            key = aKey;
            rows = aRows;
        }


        /**
         * The group key, in the identity encoding every grouped reader shares
         * ({@link GroupKeyIdentity#identityKey}).
         */
        Object key()
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
     * Keys are built over the <b>full declared</b> {@code groupCols} by the one derivation the
     * lookup uses ({@link GroupKeyIdentity#identityKey}), so they stay compatible with the per-row
     * lookups of the grouped functions: both sides key an absent column as {@code ""}.
     * </p>
     *
     * <p>
     * <b>Not every unknown name is an absent column.</b> An entry still carrying the {@code $}
     * sigil is a binding reference that the group splice could not expand into column names — a
     * resolution failure in the rule's operation chain, not a fact about the study's data. Silently
     * widening the grouping there would let a broken chain produce dataset-wide aggregates, so this
     * returns {@code null} and the caller degrades exactly as it did before EC-44.
     * </p>
     *
     * <p>
     * Under {@link GroupKeyPolicy#KEEP_MISSING_KEYS} — the shipped behaviour of every
     * {@code Operations[].group:} evaluator (the one operator that discarded a blank key,
     * {@code is_last_in_group}, is a registry function since wave 1 and still does, through
     * {@code GroupSemantics.group} in {@code GroupedPredicates}) — a blank key component keys under
     * its own identity ({@code ""} for an empty cell, the {@code MissingValue} constant for a
     * genuine missing — {@code W38-A1} / Fix #249) and the group is still formed. With
     * {@link GroupKeyPolicy#keepMissings()} {@code false} a block whose representative row carries
     * a blank key component is dropped instead, which is what lets the {@code group:} surface
     * answer an authored {@code keep_missings: false} — the half of the fold/discard asymmetry that
     * lives here.
     * </p>
     *
     * @param table
     *            the dataset to partition
     * @param groupCols
     *            the declared group columns (already {@code --}-resolved and {@code $}-expanded by
     *            the grouped functions). May be <b>empty</b> (EC-45 §1.3(3)): a grouping with no
     *            {@code group:} at all is the dataset-wide reading, which falls into the same
     *            "nothing survives" branch as an all-absent list and yields one whole-table block
     * @param context
     *            an optional label for the INFO log emitted when columns are dropped (the operation
     *            id, prefixed with the rule id when known)
     * @param policy
     *            the grouping-key policy
     * @return the grouping, empty only for an empty table; {@code null} when a group entry is an
     *         unexpanded {@code $}-reference
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
            // group over the whole table. Its key is exactly what the lookup computes for any row
            // of a table carrying none of the columns — the same derivation, so it cannot differ:
            // one "" component per declared column, i.e. GroupKey.of("" ×k). ⚠ H1 of
            // PLAN-grouping-key-identity: this branch used to build the TEXT key "\0" separately;
            // with the lookup keyed by identity that key would miss every row once k >= 2
            // (record_count reading 0 instead of N), so it goes through the one derivation.
            List<GroupBlock> blocks = rowCountL == 0 ? List.of()
                    : List.of(
                            new GroupBlock(GroupKeyIdentity.identityKey(meta, table, groupCols, 0),
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
                blocks.add(new GroupBlock(buildGroupKey(block, table, meta, groupCols),
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
     * <em>only because</em> every row of a block agrees with the first on the key <b>identity</b>
     * of every key column: the default index partitions by {@code KeyHashSupport}'s exact,
     * {@code -0.0}-aware equality ({@code DataTableIndexFactoryImpl} /
     * {@code KeyHashSupport.RepRowMatcher}), which is the {@code KeyPart} identity on every axis
     * this stack can produce ({@code PLAN-grouping-key-identity} §3a.1), and blankness is a
     * function of that identity. The factory is resolved from a <b>system property</b> and is
     * pluggable: an alternative implementation keying on {@code getValueAsString()} would fold
     * distinct missing markers into one block and break this invariant <em>silently</em> — the
     * block would then contain rows that disagree about blankness and the verdict would depend on
     * physical record order.
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
     * The lookup-compatible key of a block: the {@link GroupKeyIdentity#identityKey identity key}
     * of its representative (first) row — ⚑ the very derivation the grouped functions' per-row
     * lookups apply to every probed row, so a row's lookup always lands on its own block's key
     * ({@code PLAN-grouping-key-identity}). Until that plan the two sides rendered the key as text,
     * in lockstep, and two blocks that rendered alike shared a key.
     *
     * <p>
     * A blank component keys under its own identity — {@code ""} for an empty cell, the
     * {@code MissingValue} constant for a genuine missing ({@code W38-A1} / Fix #249) — so two
     * groups the grouping distinguishes never share a key. An <em>absent</em> column contributes
     * {@code ""} (the EC-44 contract: absent-column keys stay compatible with the grouped
     * functions' per-row lookups).
     * </p>
     *
     * <p>
     * ⚠⚠ <b>Not {@link #isBlockKeyMissing}.</b> This <em>keeps</em> the group and only has to name
     * it. See that method's warning; the pair is deliberate.
     * </p>
     */
    static Object buildGroupKey(IDataTableView block, IDataTable table, DataTableMeta meta,
            List<String> groupCols)
    {
        return GroupKeyIdentity.identityKey(meta, table, groupCols, block.getRealRow(table, 0));
    }

    /**
     * ⭐ <b>The tripwire</b> ({@code PLAN-grouping-key-identity} §3a.1): the result map of a
     * block-keyed evaluator, built block by block, that <b>refuses a second block with the same
     * key</b> instead of overwriting. Two blocks sharing one key is the exact shape of the defect
     * that plan closed — the later block's value overwrote the earlier one's and the earlier
     * block's rows read it with nothing logged — so it can never again be silent: the rule ERRORs,
     * naming the key and both blocks' first rows.
     *
     * <p>
     * ⭐ <b>Every block is claimed, not only those that write a value</b> (review round 1, M1b). An
     * evaluator that leaves a block without a result (no resolvable date, no numeric value, an
     * empty set) still {@link #skip}s it: the key is entered with a private placeholder, so a
     * value-writing block under the same key collides with it too — otherwise the value-less
     * block's rows would silently read the other block's value. The placeholders are removed by
     * {@link #results()}, only when a block was skipped.
     * </p>
     *
     * <p>
     * Cost on the healthy path: nothing beyond the map the evaluator built anyway ({@link Map#put}
     * already returns the previous value); a skipped block costs one transient entry.
     * </p>
     */
    static final class BlockResults
    {

        /** The placeholder of a claimed block without a result; never leaves this class. */
        private static final Object NO_VALUE = new Object();

        private final Grouping grouping;

        private final Map<Object, Object> results = new LinkedHashMap<>();

        private int skipped;

        BlockResults(Grouping aGrouping)
        {
            grouping = aGrouping;
        }


        /**
         * Writes {@code aValue} as {@code aBlock}'s result.
         *
         * @throws IllegalStateException
         *             when another block of the grouping already claimed the same key
         */
        void put(GroupBlock aBlock, Object aValue)
        {
            claim(aBlock, aValue);
        }


        /**
         * Claims {@code aBlock}'s key without a result — the block reads the operator's declared
         * empty result, as it always did.
         *
         * @throws IllegalStateException
         *             when another block of the grouping already claimed the same key
         */
        void skip(GroupBlock aBlock)
        {
            claim(aBlock, NO_VALUE);
            skipped++;
        }


        private void claim(GroupBlock aBlock, Object aValue)
        {
            Object previous = results.put(aBlock.key(), aValue);
            if (previous != null)
            {
                int earlierRow = -1;
                for (GroupBlock other : grouping.blocks())
                {
                    if (other != aBlock && other.key().equals(aBlock.key()))
                    {
                        earlierRow = other.rows()[0];
                        break;
                    }
                }
                throw new IllegalStateException("two groups share the key " + aBlock.key()
                        + " (the block of row " + earlierRow + " and the block of row "
                        + aBlock.rows()[0] + "): the grouping index and the key identity disagree,"
                        + " and one group would silently read the other's value");
            }
        }


        /**
         * The per-block results, placeholders removed.
         *
         * @return the result map, in block order
         */
        Map<Object, Object> results()
        {
            if (skipped > 0)
            {
                results.values().removeIf(v -> v == NO_VALUE);
                skipped = 0;
            }
            return results;
        }
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
}
