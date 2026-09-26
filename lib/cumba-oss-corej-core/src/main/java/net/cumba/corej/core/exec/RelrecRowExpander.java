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
 * scanned once to build a per-{@code IDVAR}-column value index keyed by
 * {@code STUDYID → (USUBJID, value)} (record-level and dataset-level alike), and every RELREC link
 * is resolved by index lookup rather than re-scanning the tables — the same deduplicated set of
 * {@code (primaryRow, targetOrdinal, targetRow)} triples as a per-link nested loop, in
 * {@code O(P + T + |RELREC|)} per related domain. <b>This logic is a deliberate copy of
 * {@code net.cumba.datatable.manager.local.RelrecRelationshipResolver} — any change to the join
 * rules in either class must be mirrored in the other.</b>
 * </p>
 *
 * <p>
 * <b>{@code STUDYID} is part of the link identity</b> (PLAN-relrec-studyid-link, owner 2026-09-25,
 * T1-1..T1-3). SDTMIG 3.4 §3.2.1 lists RELREC's keys as {@code STUDYID, RDOMAIN, USUBJID, IDVAR,
 * IDVARVAL, RELID}, §8.2 says a RELREC row names its record <i>"using the key variables STUDYID,
 * RDOMAIN, and USUBJID, along with IDVAR and IDVARVAL"</i>, and §4.2.3 keeps one {@code USUBJID}
 * for one person <i>across</i> trials — so {@code USUBJID} never scopes the study, and a pooled
 * package (a parent study plus its extension) holds the same subject with the same {@code --SEQ}
 * under two {@code STUDYID}s. Two rows link iff, besides the {@code USUBJID} / {@code IDVAR}
 * conditions: <b>(b)</b> {@code keyCell(primary.STUDYID) == keyCell(target.STUDYID)} on every path,
 * and <b>(c)</b> when the RELREC row's own {@code STUDYID} is populated, both rows are in
 * <i>that</i> study. A blank or missing RELREC {@code STUDYID} (Required by the IG, so the data is
 * already non-conformant) names no study — (b) still holds, nothing narrows to a named study (T1-3
 * (i); the blank is reported by the Required-null rules). {@code keyCell} keeps
 * {@code JKM R5}/{@code R7}: a missing dataset {@code STUDYID} matches neither {@code ""} nor a
 * present value, and an absent column reads {@code ""} — so a populated RELREC {@code STUDYID}
 * never links a dataset that has no {@code STUDYID} column. ⚠ The CDISC CORE engine does (b) but
 * not (c) ({@code merge_on_relrec_record} joins on the datasets' {@code STUDYID} and never reads
 * the RELREC row's); parity is retired and the IG text decides.
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
     * {@code (STUDYID, USUBJID, RELID)} group, classified by join mode. {@code study} and
     * {@code usubj} are the RAW cell texts ({@code null} when missing) that classify the link — is
     * a study named, is it subject-scoped — while {@code studyKey} / {@code usubjKey} are the same
     * cells in the {@link #keyCell} encoding, which is what every probe compares against the
     * keyCell-built indexes (E1). The related domain is the {@code byDomain} map key, not carried
     * here.
     */
    private record LinkSpec(boolean recordLevel, @Nullable String study, String studyKey,
            @Nullable String usubj, String usubjKey, String srcIdvar, @Nullable String srcIdvarval,
            String tgtIdvar, @Nullable String tgtIdvarval)
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

        // Growable expansion rows: {primaryRow, targetOrdinal, targetRow}. Dedup identical triples,
        // so duplicates from overlapping RELID pairs collapse here.
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
            String key = keyCell(relrec, cStudy, rr) + '\0' + keyCell(relrec, cUsubj, rr) + '\0'
                    + relid;
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
        // (STUDYID, USUBJID, RELID) group. USUBJID is blank for dataset-level relationships.
        // Both are carried twice, on purpose (E1): the RAW cell() value classifies the link (a
        // blank RELREC USUBJID marks a dataset-level / cross-subject link, a blank STUDYID names
        // no study), while the keyCell encoding is what every probe compares against the
        // keyCell-built indexes. Probing with the raw text keyed a present numeric USUBJID one
        // way on the probe side and another on the index side (Long.toString vs the rendering
        // through the double), losing its pairs.
        LinkSpec spec = new LinkSpec(recordLevel, cell(relrec, cStudy, lrr),
                keyCell(relrec, cStudy, lrr), cell(relrec, cUsubj, lrr),
                keyCell(relrec, cUsubj, lrr), srcIdvar, srcIdvarval, tgtIdvar, tgtIdvarval);
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


    /**
     * Record-level link (both IDVARVALs present): each side filtered by {@code IDVAR == IDVARVAL}
     * within the RELREC row's subject, joined on {@code STUDYID}. The probe is
     * {@code (USUBJID, normKey(IDVARVAL))}, key-encoded exactly like the index it looks up (JKM R5
     * / keyCell), inside the RELREC row's study bucket when a study is named (c) and inside each
     * study's own bucket otherwise (b alone, T1-3 (i)). ⛔ Until 2026-09-26 this path keyed on
     * {@code (USUBJID, value)} with no study at all — a regression of the 2026-06-27 hash-join
     * rewrite whose comment claimed the legacy scan never joined on STUDYID; it did. A blank RELREC
     * {@code USUBJID} (or a table lacking {@code USUBJID}) takes the full scan.
     */
    private static void joinRecordLevel(LinkSpec spec, int ordinal, IDataTable primary,
            IDataTable target, TableIndex primaryIndex, TableIndex targetIndex, List<long[]> out,
            Set<String> seen)
    {
        if (!isNonBlank(spec.usubj()) || !primaryIndex.hasUsubj() || !targetIndex.hasUsubj())
        {
            joinByScan(spec, ordinal, primary, target, out, seen);
            return;
        }
        // srcIdvarval/tgtIdvarval are non-blank (record-level) so normKey is non-null.
        String pKey = subjectKey(spec.usubjKey(),
                Objects.requireNonNull(normKey(spec.srcIdvarval())));
        String tKey = subjectKey(spec.usubjKey(),
                Objects.requireNonNull(normKey(spec.tgtIdvarval())));
        Map<String, Map<String, List<Long>>> pIndex = primaryIndex.byStudy(spec.srcIdvar());
        Map<String, Map<String, List<Long>>> tIndex = targetIndex.byStudy(spec.tgtIdvar());
        if (isNonBlank(spec.study()))
        {
            emitCross(rows(pIndex, spec.studyKey(), pKey), rows(tIndex, spec.studyKey(), tKey),
                    ordinal, out, seen);
            return;
        }
        for (Map.Entry<String, Map<String, List<Long>>> study : pIndex.entrySet())
        {
            emitCross(study.getValue().get(pKey), rows(tIndex, study.getKey(), tKey), ordinal, out,
                    seen);
        }
    }


    /**
     * Dataset-level link (either IDVARVAL blank): equi-join on
     * {@code (STUDYID, USUBJID, normKey(IDVAR-cell))} — the RELREC row's study bucket only when a
     * study is named (c), every study against its own bucket otherwise (b). A (rare) subject-scoped
     * dataset-level link takes the full scan.
     */
    private static void joinDatasetLevel(LinkSpec spec, int ordinal, IDataTable primary,
            IDataTable target, TableIndex primaryIndex, TableIndex targetIndex, List<long[]> out,
            Set<String> seen)
    {
        if (isNonBlank(spec.usubj()))
        {
            joinByScan(spec, ordinal, primary, target, out, seen);
            return;
        }
        Map<String, Map<String, List<Long>>> pIndex = primaryIndex.byStudy(spec.srcIdvar());
        Map<String, Map<String, List<Long>>> tIndex = targetIndex.byStudy(spec.tgtIdvar());
        if (isNonBlank(spec.study()))
        {
            joinStudyBucket(pIndex.get(spec.studyKey()), tIndex.get(spec.studyKey()), ordinal, out,
                    seen);
            return;
        }
        for (Map.Entry<String, Map<String, List<Long>>> study : pIndex.entrySet())
        {
            joinStudyBucket(study.getValue(), tIndex.get(study.getKey()), ordinal, out, seen);
        }
    }


    /** Equi-join of one study's primary bucket with the same study's target bucket. */
    private static void joinStudyBucket(@Nullable Map<String, List<Long>> primaryBucket,
            @Nullable Map<String, List<Long>> targetBucket, int ordinal, List<long[]> out,
            Set<String> seen)
    {
        if (primaryBucket == null || targetBucket == null)
        {
            return;
        }
        for (Map.Entry<String, List<Long>> e : primaryBucket.entrySet())
        {
            emitCross(e.getValue(), targetBucket.get(e.getKey()), ordinal, out, seen);
        }
    }


    private static @Nullable List<Long> rows(Map<String, Map<String, List<Long>>> index,
            String studyKey, String subjectValueKey)
    {
        Map<String, List<Long>> bucket = index.get(studyKey);
        return bucket == null ? null : bucket.get(subjectValueKey);
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
     * cover (record-level with a blank RELREC {@code USUBJID}, or a subject-scoped dataset-level
     * link). Record-level: filter each side by {@code IDVAR == IDVARVAL}, join on
     * {@code (STUDYID, USUBJID)}. Dataset-level: join on
     * {@code (STUDYID, USUBJID, normKey(IDVAR-cell))}. When the RELREC row names a study, both
     * sides are filtered to it (c); the composite key gives (b) either way.
     */
    private static void joinByScan(LinkSpec spec, int ordinal, IDataTable primary,
            IDataTable target, List<long[]> out, Set<String> seen)
    {
        boolean recordLevel = spec.recordLevel();
        // Classified on the RAW values (a blank RELREC USUBJID means "not subject-scoped", a
        // blank STUDYID "no study named"), then filtered on the key encoding, which is how both
        // scanned tables' STUDYID and USUBJID are keyed.
        boolean subjectScoped = isNonBlank(spec.usubj());
        String usubjKey = spec.usubjKey();
        boolean studyScoped = isNonBlank(spec.study());
        String studyKey = spec.studyKey();
        // Record-level: the IDVAR == IDVARVAL filters; canonicalised once, not per row.
        String tgtNorm = normKey(spec.tgtIdvarval());
        String srcNorm = normKey(spec.srcIdvarval());

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
            String study = keyCell(target, tStudyIdx, r);
            if (studyScoped && !study.equals(studyKey))
            {
                continue;
            }
            String usubj = keyCell(target, tUsubjIdx, r);
            if (subjectScoped && !usubj.equals(usubjKey))
            {
                continue;
            }
            String idv = cell(target, tIdvarIdx, r);
            if (idv == null)
            {
                continue;
            }
            String idvNorm = Objects.requireNonNull(normKey(idv));
            if (recordLevel && !idvNorm.equals(tgtNorm))
            {
                continue;
            }
            String base = study + '\0' + usubj;
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
            String study = keyCell(primary, pStudyIdx, r);
            if (studyScoped && !study.equals(studyKey))
            {
                continue;
            }
            String usubj = keyCell(primary, pUsubjIdx, r);
            if (subjectScoped && !usubj.equals(usubjKey))
            {
                continue;
            }
            String idv = cell(primary, pIdvarIdx, r);
            if (idv == null)
            {
                continue;
            }
            String idvNorm = Objects.requireNonNull(normKey(idv));
            if (recordLevel && !idvNorm.equals(srcNorm))
            {
                continue;
            }
            String base = study + '\0' + usubj;
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
     * domain. One keying serves both join modes: {@link #byStudy} maps the key-encoded
     * {@code STUDYID} to a bucket keyed by {@code (USUBJID, normKey)}, so a link that names a study
     * reads one bucket (c) and a link that names none joins each study's bucket with the same
     * study's bucket on the other side (b). ⛔ There is no study-less keying any more: the former
     * {@code bySubject} index, keyed by {@code (USUBJID, normKey)} alone, is what let a pooled
     * package's second study join (USUBJID does not scope the study — SDTMIG 3.4 §4.2.3).
     */
    private static final class TableIndex
    {

        private final IDataTable table;

        private final int studyIdx;

        private final int usubjIdx;

        private final Map<String, Map<String, Map<String, List<Long>>>> byStudyCol = new HashMap<>();

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


        /** {@code keyCell(STUDYID) → subjectKey(keyCell(USUBJID), normKey(cell)) → rows}. */
        Map<String, Map<String, List<Long>>> byStudy(String column)
        {
            return byStudyCol.computeIfAbsent(column, this::build);
        }


        private Map<String, Map<String, List<Long>>> build(String column)
        {
            Map<String, Map<String, List<Long>>> index = new HashMap<>();
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
                // JKM R5: keyCell, not cell — this index is probed by subjectKey and by the
                // RELREC row's studyKey, so the two sides MUST share one encoding. With
                // cell()/nz() a row whose USUBJID is MISSING indexed under "" and conflated with
                // a genuinely empty one.
                index.computeIfAbsent(keyCell(table, studyIdx, r), _ -> new HashMap<>())
                        .computeIfAbsent(subjectKey(keyCell(table, usubjIdx, r), vn),
                                _ -> new ArrayList<>())
                        .add(r);
            }
            return index;
        }
    }

    /**
     * The in-study index key and its probe. ⚠ {@code usubj} arrives ALREADY key-encoded by
     * {@link #keyCell}, from a table row on the index side and from the RELREC row
     * ({@code LinkSpec.usubjKey}) on the probe side — as does the study bucket's key
     * ({@code LinkSpec.studyKey}). ⛔ The probe used to pass the raw {@code cell()} text instead, on
     * the claim that the two are "the same thing for a present value" — false for a numeric
     * {@code USUBJID}, whose key encoding renders through the double (E1): a LONG beyond
     * {@code 2^53} keys as its double's text, and until E7 every 13-digit LONG did. ⛔ Do not
     * re-introduce an {@code nz} collapse (since deleted) here: it is what made a MISSING key equal
     * an empty one ({@code JKM R5}). The normalised value is kept verbatim to match the scan.
     */
    private static String subjectKey(String usubj, String valueNorm)
    {
        return usubj + '\0' + valueNorm;
    }

    // ---- key encoding (JKM R5) -- the manager twin (cumba-datatable-manager-local's
    // RelrecRelationshipResolver) mirrors keyCell arm by arm but is NOT byte-identical to it ----
    // ⭐ `nz` is GONE (2026-09-21, JKM R5): it mapped a missing cell to "", which is exactly
    // the conflation the ruling forbids. Every key site now goes through keyCell instead. ⚠
    // Error Prone's UnusedMethod proved only that nz() had no caller left; it could not see a
    // probe that still compared RAW text against a keyCell index (E1) — that took a case table.


    /**
     * One join-key component, encoded so that a <b>missing</b> cell is not the empty string.
     *
     * <p>
     * ⭐⭐ <b>{@code JKM R5} (owner, 2026-09-21): <i>"a MIS will not join a record with an empty
     * string and a MIS_A will not join a record with a MIS or MIS_B."</i></b> Every key site here
     * used to read {@code nz(cell(...))}, and {@link #cell} answers {@code null} for a missing cell
     * while the {@code nz} helper it fed mapped {@code null} to {@code ""} — so a MISSING
     * {@code USUBJID} joined a genuinely <b>empty</b> one, and two different markers joined <b>each
     * other</b>. That was a live violation on the RELREC path, independent of the DROP/KEEP
     * question.
     * </p>
     *
     * <p>
     * ⚠ <b>{@code JKM R7}:</b> an <b>absent</b> column is present-but-empty, so it keeps answering
     * {@code ""} — the character default. That is the ruled answer, not the old collapse surviving.
     * </p>
     *
     * <p>
     * ⛔⛔ <b>This is the WEAKER of the two identity forms in this engine, deliberately and only
     * here.</b> {@link GroupKeyPolicy.KeyPart} is a sealed type that <b>cannot</b> collide;
     * {@code reportingForm()} is a <em>rendering</em>, and its own javadoc says it must never be
     * re-parsed to recover identity. This path is string-keyed by construction (the keys are
     * {@code \0}-joined, and a behaviourally identical twin lives in
     * {@code cumba-datatable-manager-local}), so the strong form is not available without changing
     * both repos. {@code Missing.reportingForm()} prefixes {@code \u0001}, which no clinical text
     * cell carries — <i>unlikely</i> to collide rather than <i>unable</i> to. ⇒ if this path is
     * ever unified with the expander's, take the {@code KeyPart} identity and delete this method;
     * {@code GroupedResult.buildKey} already makes the same trade for the same reason.
     * </p>
     */
    private static String keyCell(IDataTable t, int col, long row)
    {
        if (col < 0)
        {
            return ""; // JKM R7: absent column -> the character type default
        }
        return GroupKeyPolicy.KEEP_MISSING_KEYS.keyPart(t.getColumn(col).getDataValue(row))
                .reportingForm();
    }


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
     * Normalises a join-key value: a numeric value compares by its exact decimal canonical text
     * ({@link NumericKeyText}) so {@code "1"}, {@code "1.0"} and {@code 1} all match while
     * {@code 9007199254740993} and {@code 9007199254740992} do not ({@code RRK E2}); a non-numeric
     * value compares verbatim. Applied to both record-level {@code IDVAR == IDVARVAL} filters and
     * the dataset-level {@code IDVAR}-value equi-join so numeric link variables typed differently
     * across domains still join ({@code D4-R5}: a text join with its own coercion). ⛔ This went
     * through {@code Double.parseDouble} until 2026-09-26, which folded every integer above
     * {@code 2^53} onto its neighbour (PLAN-relrec-idvar-key-precision T1-1 (a)).
     */
    private static @Nullable String normKey(@Nullable String v)
    {
        if (v == null)
        {
            return null;
        }
        String canonical = NumericKeyText.canonicalOrNull(v.strip());
        return canonical != null ? canonical : v;
    }

}
