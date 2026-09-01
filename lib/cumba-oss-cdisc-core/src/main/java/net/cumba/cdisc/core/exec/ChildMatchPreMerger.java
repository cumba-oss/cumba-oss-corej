package net.cumba.cdisc.core.exec;

import java.lang.System.Logger.Level;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.CustomLog;
import net.cumba.cdisc.core.model.MatchDataset;
import net.cumba.datatable.DataTableColumnMeta;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.impl.ColumnCachedDataTable;
import net.cumba.datatable.impl.databuffer.DataBufferFactory;
import net.cumba.datatable.impl.databuffer.IDataBufferNumeric;
import net.cumba.datatable.impl.view.MergeDataTable;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * Pre-merges Child:true Match_Datasets entries into the primary table. Handles SUPP--/CO/RELREC
 * primaries whose rows carry a {@code RDOMAIN} pointer plus {@code IDVAR}/{@code IDVARVAL} that
 * name the parent-row column and value.
 * <p>
 * For each primary row: resolves the parent domain (via {@code RDOMAIN} column if present, else by
 * stripping the {@code SUPP} prefix of the primary table name), locates the matching parent row
 * where {@code parent[row.IDVAR] == row.IDVARVAL} (filtered by the rule's declared <em>standard
 * keys</em> — the {@code Match_Datasets} keys minus {@code IDVAR}/{@code IDVARVAL}, e.g.
 * {@code USUBJID}), and augments the primary row with the parent row's column values. The standard
 * keys come from the rule, not hard-coded; {@code STUDYID} participates only when declared —
 * matching the Python reference engine (which never adds it implicitly).
 * </p>
 * <p>
 * Fix #6: Enables rules like CORE-000206 to do a two-hop value reference
 * ({@code IDVARVAL not_equal_to row[row.IDVAR]}) that otherwise would have no parent column on the
 * row to look up. Columns already present on the primary take precedence (child wins — matches
 * Python's {@code _find_parent_dataset} merge order for the specific columns the rule reads from
 * the primary, e.g. {@code IDVAR}/{@code IDVARVAL} themselves).
 * </p>
 * <p>
 * The augmented columns are exposed through a {@link PolymorphicMergedColumn} view backed by two
 * per-call dispatch buffers ({@code byte[]} parent index + bit-packed numeric buffer for the parent
 * row) — no per-cell {@code HashMap} storage, no string round-trip on native types.
 * Per-{@code (parent, IDVAR)} {@link ChildMatchIndex} instances are cached cross-rule via
 * {@link JoinCache.SharedIndexCache} so SDTM validation runs that drive multiple Child-rules
 * through the same SUPPAE/CO/RELREC parent index it only once.
 * </p>
 */
@CustomLog
public final class ChildMatchPreMerger
{

    /** Maximum number of distinct parent domains a single primary may reference. */
    private static final int MAX_PARENT_DOMAINS = 127;

    private ChildMatchPreMerger()
    {
    }


    /**
     * Returns the primary table unchanged unless any Match_Datasets entry is {@code Child:true}, in
     * which case it returns an augmented view carrying the matched parent columns.
     *
     * @param primaryTable
     *            the primary table being evaluated (the SUPP--/CO/RELREC dataset).
     * @param matchDatasets
     *            the Match_Datasets entries from the rule definition.
     * @param resolver
     *            resolves parent domain names to their {@link IDataTable}.
     * @param ruleId
     *            CORE id of the rule being evaluated, used as a leading {@code [<ruleId>]} prefix
     *            on diagnostic logs. {@code null} renders as {@code [?]}.
     * @param joinCache
     *            optional shared join cache; {@link ChildMatchIndex} instances are cached on its
     *            {@link JoinCache.SharedIndexCache}. Pass {@code null} (or a {@code JoinCache}
     *            built with a {@code null} shared-index cache) to disable cross-rule sharing.
     */
    public static IDataTable preMerge(IDataTable primaryTable,
            @Nullable List<MatchDataset> matchDatasets, DatasetResolver resolver,
            @Nullable String ruleId, @Nullable JoinCache joinCache)
    {
        // E1, E2: no matchDatasets, or none carry Child:true.
        if (primaryTable == null || matchDatasets == null || matchDatasets.isEmpty())
        {
            return primaryTable;
        }
        if (!hasChildEntry(matchDatasets))
        {
            return primaryTable;
        }

        // E3: required columns missing — SUPP-style merge can't proceed. IDVAR/IDVARVAL drive the
        // dynamic parent-column match; the equi-join scope columns come from the declared keys.
        DataTableMeta meta = primaryTable.getMetaData();
        int rdomainIdx = meta.getColumnIndex("RDOMAIN");
        int idvarIdx = meta.getColumnIndex("IDVAR");
        int idvarvalIdx = meta.getColumnIndex("IDVARVAL");
        if (idvarIdx < 0 || idvarvalIdx < 0)
        {
            return primaryTable;
        }

        // Honor the rule's declared Match_Datasets keys (Python parity). The equi-join columns are
        // the declared keys minus IDVAR/IDVARVAL — e.g. [USUBJID] — derived from the rule, NOT
        // hard-coded; STUDYID participates only if explicitly declared. The IDVAR-path trigger is
        // the presence of IDVAR/IDVARVAL in the declared keys (Python has_idvar_keys).
        List<String> declaredKeys = applicableChildKeys(matchDatasets, meta);
        if (!declaredKeys.contains("IDVAR") && !declaredKeys.contains("IDVARVAL"))
        {
            // Non-IDVAR child path — not handled by this SUPP-style pre-merge.
            return primaryTable;
        }
        List<String> standardKeys = ChildMatchIndex.standardKeysOf(declaredKeys);

        // E4: no per-row parent pointer and no implicit parent derivable from a SUPP<x> name.
        String implicitParent = resolveImplicitParent(meta, rdomainIdx);
        if (rdomainIdx < 0 && implicitParent == null)
        {
            return primaryTable;
        }

        // E5: empty primary.
        long rowCountL = primaryTable.getRowCount();
        if (rowCountL == 0)
        {
            return primaryTable;
        }

        // E18 primary side: HashLookup indexes rows as int.
        if (rowCountL > Integer.MAX_VALUE)
        {
            LOGGER.log(Level.WARNING,
                    "[{0}] preMerge: primary row count {1} exceeds int range — returning primary unchanged",
                    ruleIdOr(ruleId), rowCountL);
            return primaryTable;
        }
        int rowCount = (int) rowCountL;

        // Per-row parent resolution (E6, E7).
        @Nullable
        String[] perRowParent = new String[rowCount];
        Set<String> parentDomains = resolveParentsPerRow(primaryTable, rdomainIdx, rowCount,
                implicitParent, perRowParent);
        if (parentDomains.isEmpty())
        {
            return primaryTable;
        }

        // Resolve parent tables (E8).
        Map<String, IDataTable> parentTables = resolveParentTables(parentDomains, resolver, ruleId);
        if (parentTables.isEmpty())
        {
            return primaryTable;
        }

        // E18 parent side: every resolved parent must also fit in int.
        for (Map.Entry<String, IDataTable> e : parentTables.entrySet())
        {
            if (e.getValue().getRowCount() > Integer.MAX_VALUE)
            {
                LOGGER.log(Level.WARNING,
                        "[{0}] preMerge: parent ''{1}'' row count exceeds int range — returning primary unchanged",
                        ruleIdOr(ruleId), e.getKey());
                return primaryTable;
            }
        }

        // byte[] parent-index width guard — fall back rather than throw (E7/E8/E18 style).
        if (parentTables.size() > MAX_PARENT_DOMAINS)
        {
            LOGGER.log(Level.WARNING,
                    "[{0}] preMerge: primary ''{1}'' references {2} distinct parent domains "
                            + "(max supported: {3}) — returning primary unchanged",
                    ruleIdOr(ruleId), meta.getName(), parentTables.size(), MAX_PARENT_DOMAINS);
            return primaryTable;
        }

        // Augmented column set (E9, E10).
        Set<String> augmentedCols = computeAugmentedCols(meta, parentTables);
        if (augmentedCols.isEmpty())
        {
            return primaryTable;
        }

        // Per-(parent, standardKeys, IDVAR) child-match indexes, cached cross-rule when joinCache
        // permits.
        Map<ParentIndexKey, ChildMatchIndex> indexes = buildOrFetchIndexes(primaryTable, idvarIdx,
                rowCount, perRowParent, parentTables, joinCache, standardKeys);

        // Pre-stringify primary standard-key columns + IDVARVAL once per call. The same coercions
        // as
        // ChildMatchIndex.build are applied so build- and probe-side keys agree (E16, E17).
        String[][] primaryKeyStr = buildPrimaryKeyStr(primaryTable, standardKeys, rowCount);
        @Nullable
        String[] primaryIdvarvalStr = buildPrimaryNullableStr(primaryTable, idvarvalIdx, rowCount);
        // J5: the child IDVARVAL is coerced to the matched parent's IDVAR-column type in the
        // dispatch
        // loop below (where the resolving ChildMatchIndex — and thus the parent type — is known).
        // The coerced array is then exposed as the IDVARVAL column of the merged view (so the
        // value-reference comparison sees e.g. "1" instead of the SAS-padded " 1"). It is
        // mutated in place; ProbeMatcher and childMatchHash read the same array reference, so they
        // see the coerced value at probe time.

        // One ProbeMatcher per distinct (parent, idvarCol) — per-index fields differ.
        Map<ParentIndexKey, ChildMatchIndex.ProbeMatcher> matchers = buildProbeMatchers(indexes,
                primaryKeyStr, primaryIdvarvalStr);

        // Per-call dispatch buffers.
        IDataTable[] parentArr = parentTables.values().toArray(IDataTable[]::new);
        Map<String, Byte> parentNameToIdx = nameToIdx(parentTables);
        int maxParentRows = maxRowCount(parentArr);

        byte[] perRowParentIdx = new byte[rowCount];
        // -1 encodes "no match", positives are parent row ids.
        IDataBufferNumeric perRowParentRow = DataBufferFactory.get().createForRange(-1,
                (long) maxParentRows - 1);
        perRowParentRow.setExpectedSize(rowCount);

        // Single-pass dispatch.
        for (int r = 0; r < rowCount; r++)
        {
            String parentName = perRowParent[r];
            if (parentName == null)
            {
                perRowParentIdx[r] = -1;
                perRowParentRow.addValue(-1);
                continue;
            }
            String idvarCol = stringAt(primaryTable, idvarIdx, r);
            if (idvarCol == null)
            {
                perRowParentIdx[r] = -1;
                perRowParentRow.addValue(-1);
                continue;
            }
            ParentIndexKey key = new ParentIndexKey(parentName, standardKeys, idvarCol);
            ChildMatchIndex idx = indexes.get(key);
            if (idx == null || primaryIdvarvalStr[r] == null)
            {
                // E14 (no index for this combination) and E12 (missing primary IDVARVAL — those
                // rows are excluded by the rule's non_empty(IDVARVAL) guard, so the merged value is
                // never read). Original code skipped these primary rows as well.
                perRowParentIdx[r] = -1;
                perRowParentRow.addValue(-1);
                continue;
            }
            // J5: coerce the child IDVARVAL to this parent's IDVAR-column type (in place), so the
            // hash/probe below match the parent's coerced join value AND the merged view exposes
            // the
            // coerced value to the value-reference comparison. Mutating the array is seen by the
            // (reference-holding) ProbeMatcher.
            primaryIdvarvalStr[r] = ChildMatchIndex.normalizeJoinToken(primaryIdvarvalStr[r],
                    idx.parentIdvarNumeric);
            String[] primaryKeySlots = slotsAtRow(primaryKeyStr, r);
            int parentRow;
            if (allPresent(primaryKeySlots))
            {
                int h = ChildMatchIndex.childMatchHash(primaryKeySlots, primaryIdvarvalStr[r]);
                // matchers is keyed identically to indexes; idx was just resolved non-null for key.
                parentRow = idx.lookup.get(r, h, Objects.requireNonNull(matchers.get(key)));
            }
            else
            {
                // Per-key null semantics (Python parity): a standard key whose primary value is
                // missing is dropped for this row → linear-scan fallback over the present keys.
                parentRow = idx.scanFallback(primaryKeySlots, primaryIdvarvalStr[r]);
            }
            if (parentRow < 0)
            {
                // E13: no matching parent row.
                perRowParentIdx[r] = -1;
                perRowParentRow.addValue(-1);
                continue;
            }
            // parentName came from perRowParent → parentDomains → parentTables → nameToIdx, so it
            // is always present here.
            perRowParentIdx[r] = Objects.requireNonNull(parentNameToIdx.get(parentName));
            perRowParentRow.addValue(parentRow);
        }

        IDataTable augmented = buildSyntheticAugmentedTable(augmentedCols, parentArr,
                perRowParentIdx, perRowParentRow);

        // J5: expose the coerced IDVARVAL (built per matched-parent type above) as the IDVARVAL
        // column of the merged view, so the value-reference comparison `str(IDVARVAL) !=
        // str(colref(IDVAR))` compares the coerced token against the parent value. The wrap reuses
        // every other primary column by reference (no data copy); MergeDataTable forbids duplicate
        // names, so the coerced column must replace the primary's rather than be added to
        // augmented.
        IDataTable wrappedPrimary = wrapWithCoercedIdvarval(primaryTable, idvarvalIdx,
                primaryIdvarvalStr);

        // Pass the primary's meta as aBaseMeta to keep the original table name (MergeDataTable
        // appends "+" otherwise).
        return new MergeDataTable(meta, wrappedPrimary, augmented);
    }


    /**
     * Returns a view of {@code primaryTable} whose {@code IDVARVAL} column (at {@code idvarvalIdx})
     * returns the J5-coerced join tokens in {@code coercedIdvarval}; every other column is reused
     * by reference (no data copy). The replacement column reports {@link DataValueType#STRING}; a
     * {@code null} coerced value reads as missing.
     *
     * @param primaryTable
     *            the original primary (SUPP--/CO/RELREC) table
     * @param idvarvalIdx
     *            the {@code IDVARVAL} column index
     * @param coercedIdvarval
     *            per-row coerced {@code IDVARVAL} tokens (aligned to {@code primaryTable} rows)
     * @return a column-replacing view of {@code primaryTable}
     */
    private static IDataTable wrapWithCoercedIdvarval(IDataTable primaryTable, int idvarvalIdx,
            @Nullable String[] coercedIdvarval)
    {
        DataTableMeta pmeta = primaryTable.getMetaData();
        int ncols = pmeta.getColumnCount();
        IDataTableColumn[] cols = new IDataTableColumn[ncols];
        for (int c = 0; c < ncols; c++)
        {
            cols[c] = c == idvarvalIdx ? new StringArrayColumn(c, coercedIdvarval)
                    : primaryTable.getColumn(c);
        }
        return new ColumnCachedDataTable(pmeta, cols);
    }

    /**
     * The SDTM-standard relationship keys assumed when a {@code Child} entry omits {@code Keys}.
     * Every shipped SUPP/CO/RELREC rule declares exactly these.
     */
    private static final List<String> CANONICAL_CHILD_KEYS = List.of("USUBJID", "IDVAR",
            "IDVARVAL");

    /**
     * Returns the declared {@code Keys} of the {@code Child} entry applicable to the in-scope
     * primary dataset. Mirrors the Python engine's per-entry association
     * ({@code dataset_preprocessor.preprocess}, which matches each {@code datasets} entry to the
     * current dataset by {@code domain_name}): the entry whose {@code Name} matches the primary
     * (with {@code SUPP--}/{@code SQAP--} wildcard) wins, else the first {@code Child} entry. Falls
     * back to {@link #CANONICAL_CHILD_KEYS} when the chosen entry omits {@code Keys}.
     */
    private static List<String> applicableChildKeys(List<MatchDataset> matchDatasets,
            DataTableMeta meta)
    {
        String primaryName = meta.getName();
        MatchDataset firstChild = null;
        for (MatchDataset md : matchDatasets)
        {
            if (!Boolean.TRUE.equals(md.getChild()))
            {
                continue;
            }
            if (firstChild == null)
            {
                firstChild = md;
            }
            if (childEntryMatchesPrimary(md, primaryName))
            {
                return keysOrCanonical(md);
            }
        }
        return firstChild != null ? keysOrCanonical(firstChild) : CANONICAL_CHILD_KEYS;
    }


    private static List<String> keysOrCanonical(MatchDataset md)
    {
        List<String> keys = md.getKeys();
        return keys == null || keys.isEmpty() ? CANONICAL_CHILD_KEYS : keys;
    }


    private static boolean childEntryMatchesPrimary(MatchDataset md, @Nullable String primaryName)
    {
        String name = md.getName();
        if (name == null || primaryName == null)
        {
            return false;
        }
        if (name.equals(primaryName))
        {
            return true;
        }
        // SUPP--/SQAP-- wildcard entry matches any SUPP<x>/SQAP<x> primary.
        return name.endsWith("--") && primaryName.startsWith(name.substring(0, name.length() - 2));
    }


    private static boolean hasChildEntry(List<MatchDataset> matchDatasets)
    {
        for (MatchDataset md : matchDatasets)
        {
            if (Boolean.TRUE.equals(md.getChild()))
            {
                return true;
            }
        }
        return false;
    }


    private static @Nullable String resolveImplicitParent(DataTableMeta meta, int rdomainIdx)
    {
        if (rdomainIdx >= 0)
        {
            return null;
        }
        String name = meta.getName();
        if (name == null || !name.startsWith("SUPP") || name.length() <= 4)
        {
            return null;
        }
        return SplitDatasetUtil.unsplitName(name.substring(4));
    }


    private static Set<String> resolveParentsPerRow(IDataTable primaryTable, int rdomainIdx,
            int rowCount, @Nullable String implicitParent, @Nullable String[] perRowParent)
    {
        Set<String> parentDomains = new LinkedHashSet<>();
        for (int r = 0; r < rowCount; r++)
        {
            String parent = implicitParent;
            if (rdomainIdx >= 0)
            {
                IDataValue dv = primaryTable.getColumn(rdomainIdx).getDataValue(r);
                if (!dv.isMissingOrInvalid())
                {
                    String v = dv.getValueAsString();
                    if (v != null && !v.isEmpty())
                    {
                        parent = v;
                    }
                }
            }
            perRowParent[r] = parent;
            if (parent != null)
            {
                parentDomains.add(parent);
            }
        }
        return parentDomains;
    }


    private static Map<String, IDataTable> resolveParentTables(Set<String> parentDomains,
            DatasetResolver resolver, @Nullable String ruleId)
    {
        Map<String, IDataTable> parentTables = new LinkedHashMap<>();
        for (String p : parentDomains)
        {
            // J7 / Fix #358 (split parent): a parent domain like "LB" has no standalone dataset
            // when it is split into lbch/lbhe/lbur. SplitDomainResolution resolves the exact name
            // first (unchanged), else the row-stacked UNION of the members, so every
            // supplbch/supplbhe/supplbur row can reach its own parent record. This is a
            // deliberate, filed parity divergence (PLAN-match-datasets-split-union.md §7):
            // Python's DatasetPreprocessor merges only the FIRST member whose .domain matches
            // (merged_domains dedups by domain) and its own comment conceded the resulting
            // per-dataset counts were wrong — orphan findings now appear only where NO member
            // matches. An un-unionable split (column type clash) throws → rule ERROR (ruling 1).
            IDataTable t = SplitDomainResolution.resolveTableOrThrow(resolver, p, ruleId);
            if (t != null)
            {
                parentTables.put(p, t);
            }
            else
            {
                LOGGER.log(Level.DEBUG,
                        "[{0}] Child-match parent dataset ''{1}'' not available — skipping",
                        ruleIdOr(ruleId), p);
            }
        }
        return parentTables;
    }


    private static Set<String> computeAugmentedCols(DataTableMeta meta,
            Map<String, IDataTable> parentTables)
    {
        Set<String> primaryCols = new LinkedHashSet<>();
        int primaryColCount = meta.getColumnCount();
        for (int c = 0; c < primaryColCount; c++)
        {
            primaryCols.add(meta.getColumn(c).getName());
        }
        Set<String> augmentedCols = new LinkedHashSet<>();
        for (IDataTable parent : parentTables.values())
        {
            DataTableMeta pm = parent.getMetaData();
            for (int c = 0; c < pm.getColumnCount(); c++)
            {
                String name = pm.getColumn(c).getName();
                // Child wins — primary value takes precedence over the augmented one.
                if (!primaryCols.contains(name))
                {
                    augmentedCols.add(name);
                }
            }
        }
        return augmentedCols;
    }


    private static Map<ParentIndexKey, ChildMatchIndex> buildOrFetchIndexes(IDataTable primaryTable,
            int idvarIdx, int rowCount, @Nullable String[] perRowParent,
            Map<String, IDataTable> parentTables, @Nullable JoinCache joinCache,
            List<String> standardKeys)
    {
        JoinCache.SharedIndexCache cache = joinCache != null ? joinCache.getSharedIndexCache()
                : null;
        Map<ParentIndexKey, ChildMatchIndex> out = new LinkedHashMap<>();
        for (int r = 0; r < rowCount; r++)
        {
            String parentName = perRowParent[r];
            if (parentName == null)
            {
                continue;
            }
            IDataTable parent = parentTables.get(parentName);
            if (parent == null)
            {
                continue;
            }
            String idvarCol = stringAt(primaryTable, idvarIdx, r);
            if (idvarCol == null)
            {
                continue;
            }
            ParentIndexKey key = new ParentIndexKey(parentName, standardKeys, idvarCol);
            if (out.containsKey(key))
            {
                continue;
            }
            // Null is a valid value (parent lacks the named IDVAR or a declared standard-key
            // column)
            // and is cached accordingly — the dispatch loop reads out.get(key) and falls through to
            // MISSING.
            ChildMatchIndex idx = cache != null
                    ? cache.getOrBuildChildMatchIndex(parent, standardKeys, idvarCol)
                    : ChildMatchIndex.build(parent, standardKeys, idvarCol);
            out.put(key, idx);
        }
        return out;
    }


    /**
     * Pre-stringify a primary column whose missing-cell shape is {@code null} (USUBJID, IDVARVAL).
     */
    private static @Nullable String[] buildPrimaryNullableStr(IDataTable primaryTable, int colIdx,
            int rowCount)
    {
        @Nullable
        String[] out = new String[rowCount];
        IDataTableColumn col = primaryTable.getColumn(colIdx);
        for (int r = 0; r < rowCount; r++)
        {
            IDataValue dv = col.getDataValue(r);
            out[r] = dv.isMissingOrInvalid() ? null : dv.getValueAsString();
        }
        return out;
    }


    /**
     * Pre-stringify the primary's standard-key columns (declared keys minus IDVAR/IDVARVAL), in
     * declared order. {@code out[k][r]} is the {@code k}-th standard key for primary row {@code r};
     * missing cell or absent column becomes {@code null} (the key is then dropped for that row by
     * the per-key-null fallback, mirroring Python's {@code pd.notna(child_value)} guard).
     */
    private static String[][] buildPrimaryKeyStr(IDataTable primaryTable, List<String> standardKeys,
            int rowCount)
    {
        DataTableMeta meta = primaryTable.getMetaData();
        String[][] out = new String[standardKeys.size()][rowCount];
        for (int k = 0; k < standardKeys.size(); k++)
        {
            int ci = meta.getColumnIndex(standardKeys.get(k));
            if (ci < 0)
            {
                continue; // absent column → all null → key dropped per-row
            }
            IDataTableColumn col = primaryTable.getColumn(ci);
            for (int r = 0; r < rowCount; r++)
            {
                IDataValue dv = col.getDataValue(r);
                out[k][r] = dv.isMissingOrInvalid() ? null : dv.getValueAsString();
            }
        }
        return out;
    }


    private static String[] slotsAtRow(String[][] keyStr, int row)
    {
        String[] out = new String[keyStr.length];
        for (int k = 0; k < keyStr.length; k++)
        {
            out[k] = keyStr[k][row];
        }
        return out;
    }


    private static boolean allPresent(String[] slots)
    {
        for (String s : slots)
        {
            if (s == null)
            {
                return false;
            }
        }
        return true;
    }


    private static Map<ParentIndexKey, ChildMatchIndex.ProbeMatcher> buildProbeMatchers(
            Map<ParentIndexKey, ChildMatchIndex> indexes, String[][] primaryKeyStr,
            @Nullable String[] primaryIdvarvalStr)
    {
        Map<ParentIndexKey, ChildMatchIndex.ProbeMatcher> out = new LinkedHashMap<>();
        for (Map.Entry<ParentIndexKey, ChildMatchIndex> e : indexes.entrySet())
        {
            ChildMatchIndex idx = e.getValue();
            if (idx == null)
            {
                continue;
            }
            out.put(e.getKey(), new ChildMatchIndex.ProbeMatcher(idx.keyStr, idx.joinValueStr,
                    primaryKeyStr, primaryIdvarvalStr));
        }
        return out;
    }


    private static IDataTable buildSyntheticAugmentedTable(Set<String> augmentedCols,
            IDataTable[] parentArr, byte[] perRowParentIdx, IDataBufferNumeric perRowParentRow)
    {
        final int n = augmentedCols.size();
        PolymorphicMergedColumn[] cols = new PolymorphicMergedColumn[n];
        DataTableColumnMeta[] metaCols = new DataTableColumnMeta[n];
        int colIdx = 0;
        for (String name : augmentedCols)
        {
            @Nullable
            IDataTableColumn[] perParent = new IDataTableColumn[parentArr.length];
            for (int p = 0; p < parentArr.length; p++)
            {
                int c = parentArr[p].getMetaData().getColumnIndex(name);
                perParent[p] = c >= 0 ? parentArr[p].getColumn(c) : null;
            }
            cols[colIdx] = new PolymorphicMergedColumn(colIdx, perRowParentIdx, perRowParentRow,
                    perParent);
            metaCols[colIdx] = DataTableColumnMeta.builder().index(colIdx).name(name).label(name)
                    .type(DataValueType.STRING).build();
            colIdx++;
        }
        DataTableMeta augmentedMeta = DataTableMeta.builder().name("augmented").label("augmented")
                .rowCount(perRowParentIdx.length).totalRowCount(perRowParentIdx.length)
                .columns(metaCols).build();
        return new ColumnCachedDataTable(augmentedMeta, cols);
    }


    private static Map<String, Byte> nameToIdx(Map<String, IDataTable> parentTables)
    {
        Map<String, Byte> out = new LinkedHashMap<>();
        byte i = 0;
        for (String name : parentTables.keySet())
        {
            out.put(name, i++);
        }
        return out;
    }


    private static int maxRowCount(IDataTable[] parents)
    {
        int max = 0;
        for (IDataTable p : parents)
        {
            int rc = (int) p.getRowCount();
            if (rc > max)
            {
                max = rc;
            }
        }
        return max;
    }


    private static @Nullable String stringAt(IDataTable table, int colIdx, long row)
    {
        if (colIdx < 0)
        {
            return null;
        }
        IDataValue dv = table.getColumn(colIdx).getDataValue(row);
        return dv.isMissingOrInvalid() ? null : dv.getValueAsString();
    }


    private static String ruleIdOr(@Nullable String ruleId)
    {
        return ruleId != null ? ruleId : "?";
    }

    /**
     * Composite key for the per-call {@code (parent, standardKeys, idvarCol)} index map. The
     * standard-key list participates so two rules declaring different keys on the same
     * {@code (parent, IDVAR)} do not share an index.
     */
    private record ParentIndexKey(String parent, List<String> standardKeys, String idvarCol)
    {
    }

}
