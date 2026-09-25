package net.cumba.corej.core.exec;

import java.util.Arrays;
import java.util.List;
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
     * {@link #ensureJoinMap}). For a same-named join these equal the joined-side names backing
     * {@link #joinedKeyColIds}; for a sided join (EC-18 / P5c) they are the left names while
     * {@link #joinedKeyColIds} carries the paired right names.
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
     * Pre-computed mapping: primaryRow &rarr; joinedRow (or -1 if no match), together with the
     * primary table it was built for. Built lazily on the first {@link #lookup} call. Backed by a
     * plain int- or long-array buffer from {@link DataBufferFactory#createForRange(long, long)}.
     *
     * <p>
     * ⭐⭐ <b>ONE volatile holding BOTH, and {@link #ensureJoinMap} RETURNS the map it validated</b>
     * ({@code PLAN-identity-safe-join-caches} D9). The map and its table used to be two volatile
     * fields, and every caller re-read the map field after {@code ensureJoinMap} returned. With
     * {@code ruleThreads > 1}, rules of one dataset share this lookup through the {@link JoinCache}
     * over DIFFERENT primary tables (the raw table, or a key-match-expanded one), so another thread
     * could publish its own map between the check and the read — and a rule would read another
     * rule's row map: a wrong joined row, or an index out of bounds. Now the pair is published
     * atomically, and a caller only ever uses the map it checked.
     * </p>
     */
    private volatile @Nullable JoinMap joinMap;

    /** A row map and the primary table it maps; published as one unit. */
    private record JoinMap(IDataTable primaryTable, IDataBufferNumeric rows)
    {
    }

    /**
     * Test seam for D9, run on the lock-free fast path after {@link #ensureJoinMap} validated the
     * published map and before the caller reads it — lets a test hold one thread exactly there
     * while another publishes a different primary table's map. Never run under the monitor.
     * {@code null} in production.
     */
    private volatile @Nullable Runnable afterJoinMapEnsuredForTest;

    /** The joined table this lookup was built over — {@link JoinCache}'s D5 identity check. */
    IDataTable dataset()
    {
        return dataset;
    }


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
        // ⚠⚠ The two lists are paired POSITIONALLY, so different lengths silently pair the wrong
        // columns (or drop the tail). The only producer of unequal lists is a malformed sided
        // `Keys` element, which `RulePackageLoader.checkSidedKeys` rejects at load -- but this is
        // the site BOTH join paths funnel through, and the expander's own backstop cannot see a
        // rule that reaches here without being expandable at all (PLAN-join-key-type-identity,
        // review round 2).
        if (leftKeyColumns.size() != rightKeyColumns.size())
        {
            // ⚠ MalformedSidedKeyException, NOT IllegalArgumentException: this site is inside rule
            // execution, and every other join defect reaches the operator through the `__error__`
            // sentinel channel (RuleRunner's multi-catch). A generic RuntimeException would land
            // wherever the nearest broad catch happens to be, which is not a contract.
            throw new MalformedSidedKeyException(datasetName,
                    "the left side declares " + leftKeyColumns.size()
                            + " key column(s) and the right side " + rightKeyColumns.size()
                            + ", so they cannot be paired positionally");
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

        // Phase 3b: readers built once for the loop, never per row.
        @Nullable
        KeyCellReader[] readers = KeyCellReader.of(dataset, joinedKeyColIds);
        for (int r = 0; r < rowCount; r++)
        {
            int h = KeyHashing.computeKeyHashSafe(readers, r);
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
        IDataBufferNumeric rows = ensureJoinMap(primaryTable);
        long joinedRow = rows.getValueAsLong((int) row);
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
     * {@inheritDoc}
     *
     * <p>
     * Step B: the same match as {@link #lookup}, handed back as the parent cell's own
     * {@link IDataValue}. {@code lookup} renders that cell through
     * {@link ScalarSemantics#resolvedString}, which for a numeric column ends in
     * {@code getValueAsString()} and therefore in {@code getAsDoubleCleaned}'s noise folding (12
     * significant digits within {@code 1e-12} of the decade) — so the joined half of the precision
     * defect and the engine-wide half (Step A) are the very same line.
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
        long joinedRow = ensureJoinMap(primaryTable).getValueAsLong((int) row);
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


    /**
     * ⭐⭐ {@code §2c}'s <b>omit-an-absent-column</b> half needs a truthful answer here, and without
     * this override it never got one: {@link JoinLookup#hasColumn} defaults to {@code true}
     * (<i>"assume present, preserving the historical behaviour for key-based joins"</i>), and only
     * the two row-expanded lookups overrode it. ⇒ for a plain named {@code Match_Datasets} key join
     * — <b>the corpus's dominant shape</b> — a dotted output variable naming a column the joined
     * dataset does not have was reported as {@code ""} instead of being omitted.
     *
     * <p>
     * That is exactly the case the owner's ruling excludes (2026-09-21): <i>"if it's not present,
     * there is no need to mention the default value."</i> Found by this plan's terminal review
     * (MED-4), which noted the behaviour was unchanged by the phase that claims to implement §2c —
     * true, and the reason was that the phase corrected the QUESTION while the answer stayed
     * hard-coded.
     * </p>
     *
     * <p>
     * ⚠ The answer is row-independent for this lookup — a joined dataset's column set is a property
     * of the dataset, not of a primary row — so the {@code row} parameter is unused, as it is in
     * the interface's own default.
     * </p>
     */
    @Override
    public boolean hasColumn(IDataTable primaryTable, long row, String columnName)
    {
        return datasetMeta.getColumnIndex(columnName) >= 0;
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
        return ensureJoinMap(primaryTable).getValueAsLong((int) row) >= 0;
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
     * Returns the dataset name this lookup was built from.
     */
    @Override
    public String getDatasetName()
    {
        return datasetName;
    }


    /**
     * ⛔⛔ {@code JKM R7}: <b>every</b> key component absent from <b>both</b> sides is a rule
     * <b>ERROR</b>, not a match-everything.
     *
     * <p>
     * Owner, 2026-09-21: <i>"if all columns are absent, then the rule should fail with an
     * error."</i> Without this, {@link KeyHashing#computeKeyHashSafe} skips every component,
     * returns its constant {@code 1}, and {@code KeyHashing.KeyMatcher} is all-true — so
     * {@code _matched_} would read <b>true for every row</b> and a dotted read would answer joined
     * row 0's values for every primary row. An empty key is a cartesian product, not a join.
     * </p>
     *
     * <p>
     * ⚠⚠ <b>This arm was almost missed, and the reason is worth keeping.</b> The first
     * implementation put the check only in {@code KeyMatchRowExpander.keySpec} and justified the
     * omission here with <i>"caught by the expander's KeySpec, which runs first for every entry the
     * expander accepts"</i>. That argument is <b>false</b>: {@code expandableEntries}
     * <em>excludes</em> {@code Child:true}, {@code RELREC}, {@code SUPP*}, {@code SQ*} and
     * {@code --} names, which was exactly the population that reached this class — so <b>none</b>
     * of those entries was protected. Found by the plan's non-harm review pass. ⚑ Since 2026-09-25
     * ({@code PLAN-hashed-join-arm-absent-columns}, option A) a {@code Child: true} entry builds no
     * lookup at all, so the population here is the keyed {@code RELREC} / {@code --} /
     * {@code SUPP*} / {@code SQ*} entries — none in either shipped corpus, reachable by a user
     * package.
     * </p>
     */
    private void requireUsableKey(int[] primaryKeyColIds)
    {
        for (int i = 0; i < primaryKeyColIds.length; i++)
        {
            if (primaryKeyColIds[i] >= 0 || joinedKeyColIds[i] >= 0)
            {
                return; // at least one component can still discriminate
            }
        }
        throw new DegenerateJoinKeyException(datasetName, keyColumns);
    }


    /**
     * Builds the join map for the given primary table. The map is cached and reused for subsequent
     * lookups against the same primary table.
     * <p>
     * Walks the primary table once, computing a 32-bit key hash per row and probing the
     * {@link HashLookup} — no composite {@code String} keys are allocated.
     * <p>
     * Synchronised so concurrent rule threads (Phase 2 fan-out) building the map for the same
     * primary table see exactly one build. The lock-free fast-path reads the one {@code volatile}
     * {@link JoinMap} pair to avoid the monitor on every call once the map is built.
     *
     * @return the row map for {@code primaryTable} — the caller must use THIS, never re-read the
     *         field (D9)
     */

    private IDataBufferNumeric ensureJoinMap(IDataTable primaryTable)
    {
        // Lock-free fast-path: ONE volatile read of the published pair — the map returned is the
        // one whose table was just checked (D9).
        JoinMap published = joinMap;
        if (published != null && published.primaryTable() == primaryTable)
        {
            return ensured(published.rows());
        }
        synchronized (this)
        {
            published = joinMap;
            if (published != null && published.primaryTable() == primaryTable)
            {
                return published.rows();
            }
            int rowCount = Math.toIntExact(primaryTable.getRowCount());
            DataTableMeta primaryMeta = primaryTable.getMetaData();
            int[] primaryKeyColIds = KeyHashing.resolveColIds(primaryMeta, keyColumns);
            requireUsableKey(primaryKeyColIds);

            // -1 encodes "no match", positives are joined row ids.
            IDataBufferNumeric map = DataBufferFactory.get().createForRange(-1,
                    dataset.getRowCount() - 1);
            map.setExpectedSize(rowCount);

            // Single matcher instance reused for every probe — zero allocation per row.
            KeyMatcher matcher = new KeyMatcher(dataset, joinedKeyColIds, primaryTable,
                    primaryKeyColIds);

            @Nullable
            KeyCellReader[] readers = KeyCellReader.of(primaryTable, primaryKeyColIds);
            for (int r = 0; r < rowCount; r++)
            {
                int h = KeyHashing.computeKeyHashSafe(readers, r);
                int matchedRow = index.get(r, h, matcher);
                map.addValue(matchedRow);
            }
            // Publish the map and its table as ONE unit (D9).
            joinMap = new JoinMap(primaryTable, map);
            return map;
        }
    }


    /** Sets (or, with {@code null}, clears) the D9 test seam — test use only. */
    void setAfterJoinMapEnsuredForTest(@Nullable Runnable aHook)
    {
        afterJoinMapEnsuredForTest = aHook;
    }


    /** Runs the D9 test seam, if set, and hands back the validated map. */
    private IDataBufferNumeric ensured(IDataBufferNumeric aRows)
    {
        Runnable hook = afterJoinMapEnsuredForTest;
        if (hook != null)
        {
            hook.run();
        }
        return aRows;
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
