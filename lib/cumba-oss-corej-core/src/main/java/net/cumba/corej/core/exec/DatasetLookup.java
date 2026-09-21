package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.cumba.corej.core.exec.KeyHashing.KeyMatcher;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.databuffer.DataBufferFactory;
import net.cumba.datatable.impl.databuffer.IDataBufferNumeric;
import net.cumba.datatable.impl.view.HashLookup;
import net.cumba.datatable.values.DataValueSupport;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * Pre-built lookup index for a joined dataset. Maps composite join key values to row indices in the
 * joined table, enabling efficient per-row lookups during Check evaluation.
 * <p>
 * The joined-side index is a primitive {@link HashLookup} keyed by a 32-bit hash of the join
 * columns — no composite {@code String} keys are materialized, neither at build time nor on lookup.
 * For a 10M-row reference dataset, this typically uses ~50 MB instead of ~1.5 GB.
 * </p>
 * <p>
 * On the first {@link #lookup} call for a given primary table, a join map is built that maps each
 * primary row directly to the matched joined row index. Subsequent lookups use this map for O(1)
 * array access with zero allocation per call.
 * </p>
 *
 * <h2>Equality semantics</h2>
 * <p>
 * Key equality is determined by {@link Objects#equals} on the raw column values (via
 * {@link IDataTable#getValue(long, int)}). This means a STRING column holding {@code "5"} and a
 * LONG column holding {@code 5L} no longer compare equal, in contrast to the previous
 * String-coerced implementation. CDISC join keys (USUBJID, STUDYID, etc.) are always STRING, so
 * this change has no practical effect for clinical data.
 * </p>
 */
public class DatasetLookup implements JoinLookup
{

    private final String datasetName;

    /**
     * Primary/left-side join key column names, resolved against each primary table on demand (see
     * {@link #ensureJoinMap} / {@link #lookupAll}). For a same-named join these equal the
     * joined-side names backing {@link #joinedKeyColIds}; for a sided join (EC-18 / P5c) they are
     * the left names while {@link #joinedKeyColIds} carries the paired right names.
     */
    private final List<String> keyColumns;

    /** The joined dataset, retained for on-demand column value resolution. */
    private final IDataTable dataset;

    /** Cached metadata of the joined dataset. */
    private final DataTableMeta datasetMeta;

    /** Resolved column indices for {@link #keyColumns} in the joined dataset; -1 if missing. */
    private final int[] joinedKeyColIds;

    /** Open-addressed primitive hash table: hash32 → joined row index. */
    private final HashLookup index;

    /**
     * Pre-computed mapping: primaryRow &rarr; joinedRow (or -1 if no match). Built lazily on the
     * first {@link #lookup} call. Backed by a plain int- or long-array buffer from
     * {@link DataBufferFactory#createForRange(long, long)}.
     * <p>
     * {@code volatile} so the lock-free fast-path in {@link #ensureJoinMap} can safely observe a
     * fully-published {@code joinMap}/{@code joinMapTable} pair set by another thread.
     */
    private volatile @Nullable IDataBufferNumeric joinMap;

    /** The primary table that {@link #joinMap} was built for. */
    private volatile @Nullable IDataTable joinMapTable;

    /**
     * All joined rows that share a hash bucket, built once per joined dataset and shared across all
     * primary tables. Used by {@link #lookupAll(IDataTable, long, String)} (Fix #7) so that rules
     * joining DM to a child domain (AE, CE, SUPPDM) see every matching child row, not just the
     * first-wins pick stored in {@link #index}.
     */
    private volatile @Nullable Map<Integer, int[]> joinedRowsByHash;

    private DatasetLookup(String datasetName, List<String> keyColumns, int[] joinedKeyColIds,
            HashLookup index, IDataTable dataset)
    {
        this.datasetName = datasetName;
        this.keyColumns = keyColumns;
        this.joinedKeyColIds = joinedKeyColIds;
        this.index = index;
        this.dataset = dataset;
        this.datasetMeta = dataset.getMetaData();
    }


    /**
     * Builds a lookup index for the given dataset, keyed by the specified join columns. Only the
     * row index is stored per key — column values are resolved on demand from the retained dataset
     * reference.
     *
     * @param datasetName
     *            the name of the dataset (for reference)
     * @param dataset
     *            the dataset to index
     * @param keyColumns
     *            the join key column names
     * @return a new DatasetLookup, or {@code null} if the dataset is null
     */
    public static @Nullable DatasetLookup build(String datasetName, IDataTable dataset,
            List<String> keyColumns)
    {
        return build(datasetName, dataset, keyColumns, keyColumns);
    }


    /**
     * Builds a lookup index with <b>sided</b> join keys (EC-18 / P5c): the primary/left side is
     * matched on {@code leftKeyColumns} and the joined/right side on {@code rightKeyColumns},
     * positionally paired. When the two lists are identical this is exactly the same-named join of
     * {@link #build(String, IDataTable, List)} (which delegates here with {@code leftKeyColumns ==
     * rightKeyColumns}, so the historical single-key path is byte-identical). Mirrors the Python
     * reference engine's sided {@code match_key} merge ({@code left_on}/{@code right_on} in
     * {@code dataset_preprocessor.py}).
     *
     * @param datasetName
     *            the name of the dataset (for reference)
     * @param dataset
     *            the dataset to index (the joined/right side)
     * @param leftKeyColumns
     *            the join key column names on the primary (left) side
     * @param rightKeyColumns
     *            the join key column names on the joined (right) side, positionally paired with
     *            {@code leftKeyColumns}
     * @return a new DatasetLookup, or {@code null} if the dataset is null
     */
    public static @Nullable DatasetLookup build(String datasetName, IDataTable dataset,
            List<String> leftKeyColumns, List<String> rightKeyColumns)
    {
        if (dataset == null)
        {
            return null;
        }
        int[] joinedKeyColIds = KeyHashing.resolveColIds(dataset.getMetaData(), rightKeyColumns);
        HashLookup index = buildIndex(dataset, joinedKeyColIds);
        return new DatasetLookup(datasetName, leftKeyColumns, joinedKeyColIds, index, dataset);
    }


    /**
     * Builds a lookup using a pre-built {@link SharedJoinedIndex}. Use this when the joined-side
     * index has been cached (e.g., via {@link JoinCache.SharedIndexCache}) to avoid re-scanning the
     * joined dataset.
     *
     * @param datasetName
     *            the name of the dataset
     * @param dataset
     *            the dataset (retained for on-demand column value resolution)
     * @param keyColumns
     *            the join key column names
     * @param prebuilt
     *            the pre-built joined-side index
     * @return a new DatasetLookup, or {@code null} if the dataset is null
     */
    public static @Nullable DatasetLookup build(String datasetName, IDataTable dataset,
            List<String> keyColumns, SharedJoinedIndex prebuilt)
    {
        if (dataset == null)
        {
            return null;
        }
        return new DatasetLookup(datasetName, keyColumns, prebuilt.joinedKeyColIds(),
                prebuilt.lookup(), dataset);
    }


    /**
     * Builds the joined-side index. Result is independent of the primary table and can be cached
     * and shared across multiple lookups that join to the same dataset with the same keys.
     *
     * @param dataset
     *            the dataset to index
     * @param keyColumns
     *            the join key column names
     * @return the joined-side index, ready for sharing
     */
    public static SharedJoinedIndex buildSharedIndex(IDataTable dataset, List<String> keyColumns)
    {
        int[] joinedKeyColIds = KeyHashing.resolveColIds(dataset.getMetaData(), keyColumns);
        HashLookup lookup = buildIndex(dataset, joinedKeyColIds);
        return new SharedJoinedIndex(joinedKeyColIds, lookup);
    }


    private static HashLookup buildIndex(IDataTable dataset, int[] joinedKeyColIds)
    {
        int rowCount = Math.toIntExact(dataset.getRowCount());
        HashLookup lookup = new HashLookup(Math.max(1, rowCount), 0.75f);

        // Self-matcher used to detect duplicate keys during build. Both "tables" and both
        // colId arrays are the joined dataset itself.
        KeyMatcher selfMatcher = new KeyMatcher(dataset, joinedKeyColIds, dataset, joinedKeyColIds);

        for (int r = 0; r < rowCount; r++)
        {
            int h = KeyHashing.computeKeyHashSafe(dataset, r, joinedKeyColIds);
            // First-wins semantics: skip if a row with an equal key is already present.
            if (lookup.get(r, h, selfMatcher) == -1)
            {
                lookup.put(h, r);
            }
        }
        return lookup;
    }


    /**
     * Looks up a column value from the joined dataset for the given row in the primary table.
     *
     * @param primaryTable
     *            the primary table being evaluated
     * @param row
     *            the row index in the primary table
     * @param columnName
     *            the column to look up in the joined dataset
     * @return the value, or {@code null} if no match or column not found
     */
    @Override
    public @Nullable String lookup(IDataTable primaryTable, long row, String columnName)
    {
        ensureJoinMap(primaryTable);

        // ensureJoinMap publishes a non-null joinMap before returning.
        long joinedRow = Objects.requireNonNull(joinMap, "joinMap set by ensureJoinMap")
                .getValueAsLong((int) row);
        if (joinedRow < 0)
        {
            return null;
        }
        int colIdx = datasetMeta.getColumnIndex(columnName);
        if (colIdx < 0)
        {
            return null;
        }
        // A blank resolves per ScalarSemantics.resolvedString, which is type-INDEPENDENT.
        // ⭐ CORRECTED (owner ruling, 2026-09-18): this comment used to say a blank character cell
        // "reads '' whether the file wrote an empty string or an explicit null, so a joined
        // character value is blind to the difference". That blindness is exactly what the ruling
        // retired as dangerous. A MISSING char cell — marker or raw null (the datatable
        // repository's d4edd59) — now reads null here, like a blank numeric one; only a stored ""
        // reads "".
        return ScalarSemantics.resolvedString(dataset.getColumn(colIdx),
                datasetMeta.getColumn(colIdx).getType(), joinedRow);
    }


    /**
     * Returns every matched joined row's value for {@code columnName}. Fix #7 replaces the
     * first-wins behaviour for callers that must scan all matches (e.g. DM joining AE by USUBJID,
     * where a subject typically has many AE rows and the rule must fire if <em>any</em> AE row
     * satisfies the Check).
     * <p>
     * Order of returned values is unspecified (hash-bucket order). An empty list is returned when
     * no child row matches or the column is absent from the joined dataset.
     * </p>
     */
    @Override
    public List<String> lookupAll(IDataTable primaryTable, long row, String columnName)
    {
        int colIdx = datasetMeta.getColumnIndex(columnName);
        if (colIdx < 0)
        {
            return List.of();
        }
        ensureMultiMap();
        DataTableMeta primaryMeta = primaryTable.getMetaData();
        int[] primaryKeyColIds = KeyHashing.resolveColIds(primaryMeta, keyColumns);
        int h = KeyHashing.computeKeyHashSafe(primaryTable, row, primaryKeyColIds);
        // ensureMultiMap publishes a non-null joinedRowsByHash before returning.
        int[] candidates = Objects
                .requireNonNull(joinedRowsByHash, "joinedRowsByHash set by ensureMultiMap").get(h);
        if (candidates == null || candidates.length == 0)
        {
            return List.of();
        }
        KeyMatcher matcher = new KeyMatcher(dataset, joinedKeyColIds, primaryTable,
                primaryKeyColIds);
        List<String> out = new ArrayList<>();
        for (int joinedRow : candidates)
        {
            if (!matcher.matches(joinedRow, (int) row))
            {
                continue;
            }
            IDataValue dv = dataset.getColumn(colIdx).getDataValue(joinedRow);
            if (!dv.isMissingOrInvalid())
            {
                out.add(dv.getValueAsString());
            }
        }
        return out;
    }


    /**
     * {@inheritDoc}
     *
     * <p>
     * Step B: the same match as {@link #lookup}, handed back as the parent cell's own
     * {@link IDataValue}. {@code lookup} renders that cell through
     * {@link ScalarSemantics#resolvedString}, which for a numeric column ends in
     * {@code getValueAsString()} and therefore in {@code getAsDoubleCleaned}'s 12-significant-digit
     * rounding — so the joined half of the precision defect and the engine-wide half (Step A) are
     * the very same line.
     * </p>
     */
    @Override
    public IDataValue lookupValue(IDataTable primaryTable, long row, String columnName,
            boolean numericExpected)
    {
        int colIdx = datasetMeta.getColumnIndex(columnName);
        if (colIdx < 0)
        {
            // ⭐⭐ §9c DOTTED PARITY (owner ruling, 2026-09-18): the column is absent from the
            // joined dataset ENTIRELY, and a joined variable behaves like a first-class primary
            // one in every respect but the dotted access form. So it takes the RULE's expected
            // default exactly as an absent primary column does (D72/D76/§1b): numeric ->
            // MissingValue.MIS, otherwise the CONSTANT "" — a present empty string, never a
            // computed MIS for a char read (the D96a absent-vs-blank regression, where a MIS was
            // sorted below every value by phase 6c's D34 #5 order arm).
            //
            // ⚑ Until 2026-09-18 this answered "" UNCONDITIONALLY, and the recorded reason was
            // that the expectation is UNREADABLE FROM HERE — JoinLookup.lookupValue was handed no
            // EvaluationContext. §9c makes that a reason to CHANGE THE CHANNEL rather than accept
            // the answer, which is what the numericExpected parameter is. ⛔ Do not re-derive the
            // flag here or read a declared type for it: an absent column HAS no declared type, so
            // the expectation is a property of the RULE and can only arrive from the call site.
            //
            // ⚠ computedMissing() is the engine's single spelling of MissingValue.MIS (its own
            // javadoc), and is byte-identical to what ExprCompiler.dottedNotSuppliedDefault
            // answers for the sibling "no such joined dataset" case — the two must not drift.
            return JoinLookup.absentJoinedColumnValue(numericExpected);
        }
        ensureJoinMap(primaryTable);
        long joinedRow = Objects.requireNonNull(joinMap, "joinMap set by ensureJoinMap")
                .getValueAsLong((int) row);
        if (joinedRow < 0)
        {
            // ⭐ D72/D72a-1: an unmatched join row yields the column's TYPE default — a merged
            // column behaves like a primary column, and a primary char column never has "no
            // value": char -> "", numeric -> MissingValue.MIS (D34 #3/#4).
            return DataValueSupport.defaultForType(datasetMeta.getColumn(colIdx).getType());
        }
        // ⛔ D75a case 4 rides here too: a matched parent cell that is a genuine MissingValue is a
        // SUPPLIED value, distinct from "" (D11/D12), and passes through unchanged. (Until D72 a
        // missing char cell here was rewritten to "" and a missing numeric one to null.)
        return dataset.getColumn(colIdx).getDataValue(joinedRow);
    }


    /** {@inheritDoc} Step B — the typed sibling of {@link #lookupAll}, same 0..N contract. */
    @Override
    public List<IDataValue> lookupAllValues(IDataTable primaryTable, long row, String columnName)
    {
        int colIdx = datasetMeta.getColumnIndex(columnName);
        if (colIdx < 0)
        {
            return List.of();
        }
        ensureMultiMap();
        DataTableMeta primaryMeta = primaryTable.getMetaData();
        int[] primaryKeyColIds = KeyHashing.resolveColIds(primaryMeta, keyColumns);
        int h = KeyHashing.computeKeyHashSafe(primaryTable, row, primaryKeyColIds);
        int[] candidates = Objects
                .requireNonNull(joinedRowsByHash, "joinedRowsByHash set by ensureMultiMap").get(h);
        if (candidates == null)
        {
            return List.of();
        }
        KeyMatcher matcher = new KeyMatcher(dataset, joinedKeyColIds, primaryTable,
                primaryKeyColIds);
        List<IDataValue> out = new ArrayList<>();
        for (int joinedRow : candidates)
        {
            if (!matcher.matches(joinedRow, (int) row))
            {
                continue;
            }
            IDataValue dv = dataset.getColumn(colIdx).getDataValue(joinedRow);
            // Same filter as lookupAll: a missing matched cell contributes nothing.
            if (!dv.isMissingOrInvalid())
            {
                out.add(dv);
            }
        }
        return out;
    }


    /**
     * {@inheritDoc}
     *
     * <p>
     * Answered from the per-primary-row join map this lookup already builds (and caches) for
     * {@link #lookup} — the BitSet-shaped by-product D88 §3.3 asks for: one build pass per (primary
     * table, joined dataset), then an O(1) array read per row. First-wins index semantics are
     * irrelevant here: the map holds <em>a</em> partner row iff at least one exists, which is
     * exactly the flag's contract.
     * </p>
     */
    @Override
    public boolean matchedRow(IDataTable primaryTable, long row)
    {
        ensureJoinMap(primaryTable);
        return Objects.requireNonNull(joinMap, "joinMap set by ensureJoinMap")
                .getValueAsLong((int) row) >= 0;
    }


    /** {@inheritDoc} D1 — answered from the foreign metadata this lookup already holds. */
    @Override
    public DataValueType declaredTypeOf(String columnName)
    {
        int colIdx = datasetMeta.getColumnIndex(columnName);
        // An absent column is UNKNOWN, not Char -- see JoinLookup.declaredTypeOf.
        return colIdx < 0 ? DataValueType.MISSING : datasetMeta.getColumn(colIdx).getType();
    }


    /**
     * Builds the hash-bucket → joined-row-array map once per lookup instance. Equal hashes may
     * still have different composite keys, so callers must verify equality with a
     * {@link KeyMatcher} before using a candidate row.
     */
    private synchronized void ensureMultiMap()
    {
        if (joinedRowsByHash != null)
        {
            return;
        }
        int rc = Math.toIntExact(dataset.getRowCount());
        Map<Integer, List<Integer>> builder = new HashMap<>();
        for (int r = 0; r < rc; r++)
        {
            int h = KeyHashing.computeKeyHashSafe(dataset, r, joinedKeyColIds);
            builder.computeIfAbsent(h, _ -> new ArrayList<>()).add(r);
        }
        Map<Integer, int[]> compact = HashMap.newHashMap(builder.size());
        for (Map.Entry<Integer, List<Integer>> e : builder.entrySet())
        {
            List<Integer> list = e.getValue();
            int[] arr = new int[list.size()];
            for (int i = 0; i < arr.length; i++)
            {
                arr[i] = list.get(i);
            }
            compact.put(e.getKey(), arr);
        }
        joinedRowsByHash = compact;
    }


    /**
     * Returns the dataset name this lookup was built from.
     */
    @Override
    public String getDatasetName()
    {
        return datasetName;
    }


    /**
     * Builds the join map for the given primary table. The map is cached and reused for subsequent
     * lookups against the same primary table.
     * <p>
     * Walks the primary table once, computing a 32-bit key hash per row and probing the
     * {@link HashLookup} — no composite {@code String} keys are allocated.
     * <p>
     * Synchronised so concurrent rule threads (Phase 2 fan-out) building the map for the same
     * primary table see exactly one build. The lock-free fast-path uses the {@code volatile}
     * {@code joinMap}/{@code joinMapTable} fields to avoid the monitor on every call once the map
     * is built.
     */
    private void ensureJoinMap(IDataTable primaryTable)
    {
        // Lock-free fast-path: once another thread has published joinMap+joinMapTable, every
        // subsequent caller observes them via volatile reads and skips the synchronized block.
        if (joinMap != null && joinMapTable == primaryTable)
        {
            return;
        }
        synchronized (this)
        {
            if (joinMap != null && joinMapTable == primaryTable)
            {
                return;
            }
            int rowCount = Math.toIntExact(primaryTable.getRowCount());
            DataTableMeta primaryMeta = primaryTable.getMetaData();
            int[] primaryKeyColIds = KeyHashing.resolveColIds(primaryMeta, keyColumns);

            // -1 encodes "no match", positives are joined row ids.
            IDataBufferNumeric map = DataBufferFactory.get().createForRange(-1,
                    dataset.getRowCount() - 1);
            map.setExpectedSize(rowCount);

            // Single matcher instance reused for every probe — zero allocation per row.
            KeyMatcher matcher = new KeyMatcher(dataset, joinedKeyColIds, primaryTable,
                    primaryKeyColIds);

            for (int r = 0; r < rowCount; r++)
            {
                int h = KeyHashing.computeKeyHashSafe(primaryTable, r, primaryKeyColIds);
                int matchedRow = index.get(r, h, matcher);
                map.addValue(matchedRow);
            }
            // Publish in this order so a reader that observes joinMapTable == primaryTable always
            // sees a non-null joinMap.
            joinMap = map;
            joinMapTable = primaryTable;
        }
    }

    /**
     * Immutable joined-side index, safe to share across threads. Held by
     * {@link JoinCache.SharedIndexCache} for cross-dataset reuse.
     */
    // int[] kept for no-boxing key-column id storage; equals/hashCode hand-rolled below.
    @SuppressWarnings("ArrayRecordComponent")
    public record SharedJoinedIndex(int[] joinedKeyColIds, HashLookup lookup)
    {

        @Override
        public boolean equals(Object o)
        {
            if (this == o)
            {
                return true;
            }
            if (!(o instanceof SharedJoinedIndex(int[] otherKeyColIds, HashLookup otherLookup)))
            {
                return false;
            }
            return Arrays.equals(joinedKeyColIds, otherKeyColIds)
                    && Objects.equals(lookup, otherLookup);
        }


        @Override
        public int hashCode()
        {
            return 31 * Arrays.hashCode(joinedKeyColIds) + Objects.hashCode(lookup);
        }


        @Override
        public String toString()
        {
            return "SharedJoinedIndex[joinedKeyColIds=" + Arrays.toString(joinedKeyColIds)
                    + ", lookup=" + lookup + "]";
        }
    }

}
