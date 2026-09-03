package net.cumba.corej.core.exec;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.cumba.corej.core.model.MatchDataset;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.databuffer.DataBufferFactory;
import net.cumba.datatable.impl.databuffer.IDataBufferNumeric;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * Builds a row-EXPANDED evaluation table for a forward RELREC join, mirroring the Python engine's
 * {@code merge_relrec_datasets} preprocessing. One expanded row per (primary record, matched
 * related record) pair (inner-join semantics). The related columns are exposed via the returned
 * {@link RelrecExpandedLookup} (registered under {@code "RELREC"} in the rule's joined-datasets
 * map), so the existing dot-qualified {@code RELREC.*} consumption resolves each expanded row's
 * bound target.
 *
 * <p>
 * Returns {@code null} when the rule declares no forward RELREC join, leaving evaluation unchanged.
 * Returns an expansion with 0 rows when a forward join is declared but no pairs match (faithful to
 * Python's inner merge producing an empty frame &rarr; no violations).
 * </p>
 *
 * <p>
 * The pairing is computed as <b>one hash join per related {@code RDOMAIN}</b>: each table is
 * scanned once to build a per-{@code IDVAR}-column value index (keyed by {@code (USUBJID, value)}
 * for record-level links, by {@code (STUDYID, USUBJID, value)} for dataset-level), and every RELREC
 * link is resolved by index lookup rather than re-scanning the tables — the same deduplicated set
 * of {@code (primaryRow, targetOrdinal, targetRow)} triples as a per-link nested loop, in
 * {@code O(P + T + |RELREC|)} per related domain. <b>This logic is a deliberate copy of
 * {@code net.cumba.datatable.manager.local.RelrecRelationshipResolver} — any change to the join
 * rules in either class must be mirrored in the other.</b>
 * </p>
 */
final class RelrecRowExpander
{

    static final String RELREC = "RELREC";

    private static final Logger LOGGER = System.getLogger(RelrecRowExpander.class.getName());

    private static final String STUDYID = "STUDYID";

    private static final String USUBJID = "USUBJID";

    private static final String RDOMAIN = "RDOMAIN";

    private static final String IDVAR = "IDVAR";

    private static final String IDVARVAL = "IDVARVAL";

    private static final String RELID = "RELID";

    /**
     * Result bundle. {@code forwardEntry} is the matched {@link MatchDataset} (to exclude from
     * key-join building since forward RELREC joins are handled here by row expansion).
     */
    record RelrecExpansion(IDataTable table, JoinLookup lookup, MatchDataset forwardEntry)
    {
    }


    /**
     * One {@code (left primary-domain RELREC row, right related-domain RELREC row)} link within a
     * {@code (STUDYID, USUBJID, RELID)} group, classified by join mode.
     */
    private record LinkSpec(String rdomain, boolean recordLevel, @Nullable String study,
            @Nullable String usubj, String srcIdvar, @Nullable String srcIdvarval, String tgtIdvar,
            @Nullable String tgtIdvarval)
    {
    }

    private RelrecRowExpander()
    {
    }


    /**
     * Builds the row expansion for the rule's forward RELREC join, if one is declared.
     *
     * @return an expansion, or {@code null} if no forward RELREC join is declared.
     */
    static @Nullable RelrecExpansion expand(IDataTable primaryTable,
            @Nullable List<MatchDataset> matchDatasets, DatasetResolver resolver,
            @Nullable String ruleId)
    {
        MatchDataset forward = findForwardRelrec(matchDatasets);
        if (forward == null)
        {
            return null;
        }

        int primaryRowCount = Math.toIntExact(primaryTable.getRowCount());
        String primaryDomain = primaryTable.getMetaData().getName();
        IDataTable relrec = resolver.resolve(RELREC);

        // Growable expansion rows: {primaryRow, targetOrdinal, targetRow}. Dedup identical triples
        // (canonical-equivalent to Python's concat; the parity harness set-dedups identical
        // violations), so duplicates from overlapping RELID pairs collapse here.
        List<long[]> expanded = new ArrayList<>();
        List<IDataTable> targetTables = new ArrayList<>();

        if (relrec != null)
        {
            buildPairs(relrec, primaryTable, primaryDomain, resolver, targetTables, expanded,
                    ruleId);
        }

        // Deterministic order: primary asc, ordinal asc, target asc. Parity-neutral (the harness
        // set-dedups), but the three parallel arrays below MUST be materialised from this single
        // sorted list so primaryMap/ord/tgtRow stay co-indexed.
        expanded.sort(Comparator.<long[]> comparingLong(e -> e[0]).thenComparingLong(e -> e[1])
                .thenComparingLong(e -> e[2]));

        int n = expanded.size();
        IDataBufferNumeric primaryMap = DataBufferFactory.get().createForRange(0,
                Math.max(0, primaryRowCount - 1L));
        primaryMap.setExpectedSize(n);
        int[] ord = new int[n];
        long[] tgtRow = new long[n];
        for (int i = 0; i < n; i++)
        {
            long[] e = expanded.get(i);
            primaryMap.addValue(e[0]);
            ord[i] = (int) e[1];
            tgtRow[i] = e[2];
        }
        IDataTable expandedTable = new RelrecExpandedTable(primaryTable, primaryMap);
        JoinLookup lookup = new RelrecExpandedLookup(List.copyOf(targetTables), ord, tgtRow);
        return new RelrecExpansion(expandedTable, lookup, forward);
    }


    /**
     * Detects a forward RELREC join entry: {@code Name == "RELREC"}, no {@code Keys}, and not
     * {@code Child:true}. The {@code Child:true} / keyed RELREC entries use the
     * {@link ChildMatchPreMerger} path instead and are left untouched.
     */
    static @Nullable MatchDataset findForwardRelrec(@Nullable List<MatchDataset> matchDatasets)
    {
        if (matchDatasets == null)
        {
            return null;
        }
        for (MatchDataset md : matchDatasets)
        {
            if (md.getName() != null && RELREC.equalsIgnoreCase(md.getName())
                    && (md.getKeys() == null || md.getKeys().isEmpty())
                    && !Boolean.TRUE.equals(md.getChild()))
            {
                return md;
            }
        }
        return null;
    }

    // ---- link collection ----


    private static void buildPairs(IDataTable relrec, IDataTable primary,
            @Nullable String primaryDomain, DatasetResolver resolver, List<IDataTable> targetTables,
            List<long[]> out, @Nullable String ruleId)
    {
        DataTableMeta rm = relrec.getMetaData();
        int cStudy = rm.getColumnIndex(STUDYID);
        int cUsubj = rm.getColumnIndex(USUBJID);
        int cRdom = rm.getColumnIndex(RDOMAIN);
        int cIdvar = rm.getColumnIndex(IDVAR);
        int cIdvarval = rm.getColumnIndex(IDVARVAL);
        int cRelid = rm.getColumnIndex(RELID);
        if (cRdom < 0 || cIdvar < 0 || cIdvarval < 0 || cRelid < 0 || cUsubj < 0)
        {
            return; // RELREC missing required columns -> no expansion (empty frame).
        }

        // Group RELREC rows by (STUDYID, USUBJID, RELID).
        Map<String, List<Long>> groups = new LinkedHashMap<>();
        long rrCount = relrec.getRowCount();
        for (long rr = 0; rr < rrCount; rr++)
        {
            String relid = cell(relrec, cRelid, rr);
            if (relid == null)
            {
                continue;
            }
            String key = nz(cStudy >= 0 ? cell(relrec, cStudy, rr) : "") + '\0'
                    + nz(cell(relrec, cUsubj, rr)) + '\0' + relid;
            groups.computeIfAbsent(key, _ -> new ArrayList<>()).add(rr);
        }

        // Collect the link specs per related RDOMAIN, in first-appearance order.
        Map<String, List<LinkSpec>> byDomain = new LinkedHashMap<>();
        for (List<Long> group : groups.values())
        {
            for (long lrr : group)
            {
                String ldom = cell(relrec, cRdom, lrr);
                if (ldom == null || !ldom.equalsIgnoreCase(primaryDomain))
                {
                    continue; // left side must be the primary domain
                }
                for (long rrr : group)
                {
                    collectLink(relrec, cRdom, cIdvar, cIdvarval, cStudy, cUsubj, primaryDomain,
                            lrr, rrr, byDomain);
                }
            }
        }

        TableIndex primaryIndex = new TableIndex(primary); // built once, reused across domains
        for (Map.Entry<String, List<LinkSpec>> e : byDomain.entrySet())
        {
            joinDomain(e.getKey(), e.getValue(), primary, primaryIndex, resolver, targetTables, out,
                    ruleId);
        }
    }


    @SuppressWarnings("checkstyle:ParameterNumber")
    private static void collectLink(IDataTable relrec, int cRdom, int cIdvar, int cIdvarval,
            int cStudy, int cUsubj, @Nullable String primaryDomain, long lrr, long rrr,
            Map<String, List<LinkSpec>> byDomain)
    {
        String rdom = cell(relrec, cRdom, rrr);
        if (rdom == null || rdom.equalsIgnoreCase(primaryDomain))
        {
            return; // right side is every other-domain row in the group
        }
        String srcIdvar = cell(relrec, cIdvar, lrr);
        String tgtIdvar = cell(relrec, cIdvar, rrr);
        if (srcIdvar == null || tgtIdvar == null)
        {
            return;
        }
        String srcIdvarval = cell(relrec, cIdvarval, lrr);
        String tgtIdvarval = cell(relrec, cIdvarval, rrr);
        boolean recordLevel = isNonBlank(srcIdvarval) && isNonBlank(tgtIdvarval);
        // STUDYID/USUBJID come from the left (primary) RELREC row; left == right within a
        // (STUDYID, USUBJID, RELID) group. Blank for dataset-level relationships.
        LinkSpec spec = new LinkSpec(rdom, recordLevel, cell(relrec, cStudy, lrr),
                cell(relrec, cUsubj, lrr), srcIdvar, srcIdvarval, tgtIdvar, tgtIdvarval);
        byDomain.computeIfAbsent(rdom, _ -> new ArrayList<>()).add(spec);
    }

    // ---- per-domain hash join ----


    private static void joinDomain(String rdomain, List<LinkSpec> specs, IDataTable primary,
            TableIndex primaryIndex, DatasetResolver resolver, List<IDataTable> targetTables,
            List<long[]> out, @Nullable String ruleId)
    {
        boolean hasRecord = false;
        boolean hasDataset = false;
        for (LinkSpec s : specs)
        {
            if (s.recordLevel())
            {
                hasRecord = true;
            }
            else
            {
                hasDataset = true;
            }
        }
        if (hasRecord && hasDataset)
        {
            LOGGER.log(Level.INFO,
                    "RELREC relationship for {0} mixes record- and dataset-level links; skipped.",
                    rdomain);
            return;
        }

        // Fix #358: exact name first, else the row-stacked union of a split domain (a forward
        // RELREC pointing at a split LB expands rows targeting EITHER member — ruling 2). An
        // un-unionable split throws → rule ERROR (ruling 1).
        IDataTable target = SplitDomainResolution.resolveTableOrThrow(resolver, rdomain, ruleId);
        if (target == null)
        {
            return; // unresolved related domain -> empty for this domain
        }
        int ordinal = targetTables.size();
        targetTables.add(target);

        TableIndex targetIndex = new TableIndex(target);
        Set<String> seen = new HashSet<>(); // ordinal constant within a domain -> key on (pr,tr)

        for (LinkSpec s : specs)
        {
            if (s.recordLevel())
            {
                joinRecordLevel(s, ordinal, primary, target, primaryIndex, targetIndex, out, seen);
            }
            else
            {
                joinDatasetLevel(s, ordinal, primary, target, primaryIndex, targetIndex, out, seen);
            }
        }
    }


    private static void joinRecordLevel(LinkSpec spec, int ordinal, IDataTable primary,
            IDataTable target, TableIndex primaryIndex, TableIndex targetIndex, List<long[]> out,
            Set<String> seen)
    {
        String usubj = spec.usubj();
        if (usubj == null || usubj.isBlank() || !primaryIndex.hasUsubj() || !targetIndex.hasUsubj())
        {
            joinByScan(spec, ordinal, primary, target, out, seen);
            return;
        }
        // srcIdvarval/tgtIdvarval are non-blank (record-level) so normKey is non-null. Key purely
        // on (USUBJID, value) — USUBJID scopes the subject (and hence study), mirroring the legacy
        // scan which joins on the dataset rows' keys and never on the RELREC row's STUDYID.
        String pKey = subjectKey(usubj, Objects.requireNonNull(normKey(spec.srcIdvarval())));
        String tKey = subjectKey(usubj, Objects.requireNonNull(normKey(spec.tgtIdvarval())));
        emitCross(primaryIndex.bySubject(spec.srcIdvar()).get(pKey),
                targetIndex.bySubject(spec.tgtIdvar()).get(tKey), ordinal, out, seen);
    }


    private static void joinDatasetLevel(LinkSpec spec, int ordinal, IDataTable primary,
            IDataTable target, TableIndex primaryIndex, TableIndex targetIndex, List<long[]> out,
            Set<String> seen)
    {
        if (isNonBlank(spec.usubj()))
        {
            joinByScan(spec, ordinal, primary, target, out, seen);
            return;
        }
        Map<String, List<Long>> tIndex = targetIndex.byStudySubject(spec.tgtIdvar());
        for (Map.Entry<String, List<Long>> e : primaryIndex.byStudySubject(spec.srcIdvar())
                .entrySet())
        {
            emitCross(e.getValue(), tIndex.get(e.getKey()), ordinal, out, seen);
        }
    }


    private static void emitCross(@Nullable List<Long> primaryRows, @Nullable List<Long> targetRows,
            int ordinal, List<long[]> out, Set<String> seen)
    {
        if (primaryRows == null || targetRows == null)
        {
            return;
        }
        for (long pr : primaryRows)
        {
            for (long tr : targetRows)
            {
                if (seen.add(pr + "|" + tr))
                {
                    out.add(new long[]
                    {
                            pr, ordinal, tr
                    });
                }
            }
        }
    }


    /**
     * Faithful full-scan join for one link, used for the rare cases the indexed fast path does not
     * cover (record-level with blank RELREC {@code STUDYID}/{@code USUBJID}, or a subject-scoped
     * dataset-level link). Record-level: filter each side by {@code IDVAR == IDVARVAL}, join on
     * {@code (STUDYID, USUBJID)}. Dataset-level: join on
     * {@code (STUDYID, USUBJID, str(IDVAR-cell))}.
     */
    private static void joinByScan(LinkSpec spec, int ordinal, IDataTable primary,
            IDataTable target, List<long[]> out, Set<String> seen)
    {
        boolean recordLevel = spec.recordLevel();
        String usubjId = spec.usubj();

        DataTableMeta tm = target.getMetaData();
        int tStudyIdx = tm.getColumnIndex(STUDYID);
        int tUsubjIdx = tm.getColumnIndex(USUBJID);
        int tIdvarIdx = tm.getColumnIndex(spec.tgtIdvar());
        if (tUsubjIdx < 0 || tIdvarIdx < 0)
        {
            return;
        }
        Map<String, List<Long>> targetIndex = new HashMap<>();
        long tRows = target.getRowCount();
        for (long r = 0; r < tRows; r++)
        {
            String usubj = nz(cell(target, tUsubjIdx, r));
            if (isNonBlank(usubjId) && !usubj.equals(usubjId))
            {
                continue;
            }
            String idv = cell(target, tIdvarIdx, r);
            if (idv == null)
            {
                continue;
            }
            String idvNorm = Objects.requireNonNull(normKey(idv));
            if (recordLevel && !idvNorm.equals(normKey(spec.tgtIdvarval())))
            {
                continue;
            }
            String base = nz(tStudyIdx >= 0 ? cell(target, tStudyIdx, r) : "") + '\0' + usubj;
            String key = recordLevel ? base : base + '\0' + idvNorm;
            targetIndex.computeIfAbsent(key, _ -> new ArrayList<>()).add(r);
        }

        DataTableMeta pm = primary.getMetaData();
        int pStudyIdx = pm.getColumnIndex(STUDYID);
        int pUsubjIdx = pm.getColumnIndex(USUBJID);
        int pIdvarIdx = pm.getColumnIndex(spec.srcIdvar());
        if (pUsubjIdx < 0 || pIdvarIdx < 0)
        {
            return;
        }
        long pRows = primary.getRowCount();
        for (long r = 0; r < pRows; r++)
        {
            String usubj = nz(cell(primary, pUsubjIdx, r));
            if (isNonBlank(usubjId) && !usubj.equals(usubjId))
            {
                continue;
            }
            String idv = cell(primary, pIdvarIdx, r);
            if (idv == null)
            {
                continue;
            }
            String idvNorm = Objects.requireNonNull(normKey(idv));
            if (recordLevel && !idvNorm.equals(normKey(spec.srcIdvarval())))
            {
                continue;
            }
            String base = nz(pStudyIdx >= 0 ? cell(primary, pStudyIdx, r) : "") + '\0' + usubj;
            String key = recordLevel ? base : base + '\0' + idvNorm;
            List<Long> matches = targetIndex.get(key);
            if (matches == null)
            {
                continue;
            }
            for (long tr : matches)
            {
                if (seen.add(r + "|" + tr))
                {
                    out.add(new long[]
                    {
                            r, ordinal, tr
                    });
                }
            }
        }
    }

    // ---- table index ----

    /**
     * Lazily builds, per {@code IDVAR} column, a value index for one table (rows with a
     * {@code null} value cell are skipped), built once and reused across every link of the related
     * domain. Two keyings are offered: {@link #byStudySubject} keyed by
     * {@code (STUDYID, USUBJID, normKey)} for the dataset-level equi-join, and {@link #bySubject}
     * keyed by {@code (USUBJID, normKey)} for the record-level join (USUBJID alone scopes the
     * subject and hence its study).
     */
    private static final class TableIndex
    {

        private final IDataTable table;

        private final int studyIdx;

        private final int usubjIdx;

        private final Map<String, Map<String, List<Long>>> byStudySubjectCol = new HashMap<>();

        private final Map<String, Map<String, List<Long>>> bySubjectCol = new HashMap<>();

        TableIndex(IDataTable table)
        {
            this.table = table;
            DataTableMeta m = table.getMetaData();
            studyIdx = m.getColumnIndex(STUDYID);
            usubjIdx = m.getColumnIndex(USUBJID);
        }


        boolean hasUsubj()
        {
            return usubjIdx >= 0;
        }


        Map<String, List<Long>> byStudySubject(String column)
        {
            return byStudySubjectCol.computeIfAbsent(column, c -> build(c, true));
        }


        Map<String, List<Long>> bySubject(String column)
        {
            return bySubjectCol.computeIfAbsent(column, c -> build(c, false));
        }


        private Map<String, List<Long>> build(String column, boolean withStudy)
        {
            Map<String, List<Long>> index = new HashMap<>();
            int colIdx = table.getMetaData().getColumnIndex(column);
            if (colIdx < 0 || usubjIdx < 0)
            {
                return index;
            }
            long rows = table.getRowCount();
            for (long r = 0; r < rows; r++)
            {
                String v = cell(table, colIdx, r);
                if (v == null)
                {
                    continue;
                }
                // v is non-null, so normKey is non-null.
                String vn = Objects.requireNonNull(normKey(v));
                String usubj = cell(table, usubjIdx, r);
                String key = withStudy ? studySubjectKey(cell(table, studyIdx, r), usubj, vn)
                        : subjectKey(usubj, vn);
                index.computeIfAbsent(key, _ -> new ArrayList<>()).add(r);
            }
            return index;
        }
    }

    private static String studySubjectKey(@Nullable String study, @Nullable String usubj,
            String valueNorm)
    {
        // STUDYID/USUBJID are nz-collapsed (mirroring the legacy join base); the normalised value
        // is kept verbatim to match the legacy scan and the Python oracle's float-merge keys.
        return nz(study) + '\0' + nz(usubj) + '\0' + valueNorm;
    }


    private static String subjectKey(@Nullable String usubj, String valueNorm)
    {
        return nz(usubj) + '\0' + valueNorm;
    }

    // ---- shared helpers (kept byte-identical to the manager twin, except nz) ----


    private static @Nullable String cell(IDataTable t, int col, long row)
    {
        if (col < 0)
        {
            return null;
        }
        IDataValue dv = t.getColumn(col).getDataValue(row);
        return dv.isMissingOrInvalid() ? null : dv.getValueAsString();
    }


    private static boolean isNonBlank(@Nullable String s)
    {
        return s != null && !s.isEmpty();
    }


    /**
     * Normalises a join-key value to mirror the reference engine's coercion
     * ({@code DataProcessor.convert_float_merge_keys} = {@code astype(float, errors="ignore")
     * .astype(str)} and {@code filter_if_present}'s {@code str(float(value))}): a numeric value
     * compares by its float form so {@code "1"}, {@code "1.0"} and {@code 1} all match, while a
     * non-numeric value compares verbatim. Applied to both record-level {@code IDVAR == IDVARVAL}
     * filters and the dataset-level {@code IDVAR}-value equi-join so numeric link variables typed
     * differently across domains still join.
     */
    private static @Nullable String normKey(@Nullable String v)
    {
        if (v == null)
        {
            return null;
        }
        try
        {
            return Double.toString(Double.parseDouble(v));
        }
        catch (NumberFormatException _)
        {
            return v;
        }
    }


    private static String nz(@Nullable String s)
    {
        return s == null ? "" : s;
    }
}
