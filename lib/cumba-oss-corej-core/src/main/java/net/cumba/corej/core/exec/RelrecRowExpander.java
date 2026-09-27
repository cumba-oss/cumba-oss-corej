package net.cumba.corej.core.exec;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntFunction;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.databuffer.DataBufferFactory;
import net.cumba.datatable.impl.databuffer.IDataBufferNumeric;
import net.cumba.datatable.values.GroupKeyPolicy;
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
 * rules in either class must be mirrored in the other.</b> What is no longer copied is the key
 * identity: both classes (and the data browser's RELREC dialog) classify a key cell through the one
 * shared type, {@code cumba-datatable}'s {@link GroupKeyPolicy#textKeyIdentity(IDataValue)}
 * ({@code PLAN-shared-key-identity}).
 * </p>
 *
 * <p>
 * ⭐ <b>The indexes are compact</b> ({@code PLAN-shared-key-identity} P-R1, owner 2026-09-27:
 * <i>"agree to int[] and take in plan 15"</i>): a {@link KeyRows} maps each distinct
 * {@link RelrecKey} to a dense id through an open-addressing table and holds the rows as
 * {@code int[]} CSR postings — the {@code KeyMatchIndex} P1/P5 shape. The
 * {@code Map<String, Map<String, List<Long>>>} it replaces paid an {@code ArrayList}, its array and
 * a boxed {@code Long} per row: measured in phase 0 on a 1M-row target, the postings were half the
 * index. Row ids are {@code int} ({@link Math#toIntExact} on the row count, as in
 * {@code KeyMatchIndex}).
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
     * cells' key identities ({@link #keyCell}), which is what every probe compares against the
     * keyCell-built indexes (E1). The related domain is the {@code byDomain} map key, not carried
     * here.
     */
    private record LinkSpec(boolean recordLevel, @Nullable String study, Object studyKey,
            @Nullable String usubj, Object usubjKey, String srcIdvar, @Nullable String srcIdvarval,
            String tgtIdvar, @Nullable String tgtIdvarval)
    {
    }


    /**
     * One RELREC key: the {@code STUDYID} and {@code USUBJID} key identities ({@link #keyCell} — a
     * {@code String} or a {@code MissingValue} constant) and one text component — the normalised
     * link value ({@link #normKey}) in a {@link KeyRows} index, the {@code RELID} in RELREC's own
     * grouping, {@code ""} in a record-level scan (whose rows all carry the same value).
     *
     * <p>
     * Equality is component-wise, so the key cannot collide where the {@code \0}-joined string it
     * replaces only was unlikely to: a {@code MissingValue} never equals a text cell, and no cell
     * text can straddle two components.
     * </p>
     */
    record RelrecKey(Object study, Object subject, String value)
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

        // Group RELREC rows by (STUDYID, USUBJID, RELID), each component its key identity.
        Map<RelrecKey, List<Long>> groups = new LinkedHashMap<>();
        long rrCount = relrec.getRowCount();
        for (long rr = 0; rr < rrCount; rr++)
        {
            String relid = cell(relrec, cRelid, rr);
            if (relid == null)
            {
                continue;
            }
            RelrecKey key = new RelrecKey(keyCell(relrec, cStudy, rr), keyCell(relrec, cUsubj, rr),
                    relid);
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
        // no study), while the key identity is what every probe compares against the
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
     * {@code (STUDYID, USUBJID, normKey(IDVARVAL))}, key-identified exactly like the index it looks
     * up (JKM R5 / keyCell), in the RELREC row's study when a study is named (c) and in each study
     * the primary index holds otherwise (b alone, T1-3 (i)). ⛔ Until 2026-09-26 this path keyed on
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
        String pValue = Objects.requireNonNull(normKey(spec.srcIdvarval()));
        String tValue = Objects.requireNonNull(normKey(spec.tgtIdvarval()));
        KeyRows pIndex = primaryIndex.byColumn(spec.srcIdvar());
        KeyRows tIndex = targetIndex.byColumn(spec.tgtIdvar());
        if (isNonBlank(spec.study()))
        {
            emitCross(pIndex, pIndex.find(new RelrecKey(spec.studyKey(), spec.usubjKey(), pValue)),
                    tIndex, tIndex.find(new RelrecKey(spec.studyKey(), spec.usubjKey(), tValue)),
                    ordinal, out, seen);
            return;
        }
        for (Object study : pIndex.studies())
        {
            emitCross(pIndex, pIndex.find(new RelrecKey(study, spec.usubjKey(), pValue)), tIndex,
                    tIndex.find(new RelrecKey(study, spec.usubjKey(), tValue)), ordinal, out, seen);
        }
    }


    /**
     * Dataset-level link (either IDVARVAL blank): equi-join on
     * {@code (STUDYID, USUBJID, normKey(IDVAR-cell))} — every primary key in the RELREC row's study
     * when a study is named (c), every primary key otherwise (b: the key carries its own study, so
     * it finds only the same study on the other side). A (rare) subject-scoped dataset-level link
     * takes the full scan.
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
        KeyRows pIndex = primaryIndex.byColumn(spec.srcIdvar());
        KeyRows tIndex = targetIndex.byColumn(spec.tgtIdvar());
        boolean studyScoped = isNonBlank(spec.study());
        for (int id = 0; id < pIndex.keyCount(); id++)
        {
            RelrecKey key = pIndex.key(id);
            if (studyScoped && !key.study().equals(spec.studyKey()))
            {
                continue;
            }
            emitCross(pIndex, id, tIndex, tIndex.find(key), ordinal, out, seen);
        }
    }


    /** Every pairing of key {@code primaryId}'s rows with key {@code targetId}'s rows. */
    private static void emitCross(KeyRows primary, int primaryId, KeyRows target, int targetId,
            int ordinal, List<long[]> out, Set<String> seen)
    {
        if (primaryId == KeyRows.NOT_FOUND || targetId == KeyRows.NOT_FOUND)
        {
            return;
        }
        int primaryEnd = primary.end(primaryId);
        int targetEnd = target.end(targetId);
        for (int i = primary.start(primaryId); i < primaryEnd; i++)
        {
            long pr = primary.row(i);
            for (int j = target.start(targetId); j < targetEnd; j++)
            {
                long tr = target.row(j);
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
     * sides are filtered to it (c); the key gives (b) either way.
     */
    private static void joinByScan(LinkSpec spec, int ordinal, IDataTable primary,
            IDataTable target, List<long[]> out, Set<String> seen)
    {
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
        KeyRows targetIndex = KeyRows.build(Math.toIntExact(target.getRowCount()),
                r -> scanKey(spec, target, tStudyIdx, tUsubjIdx, tIdvarIdx, r, tgtNorm));

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
            RelrecKey key = scanKey(spec, primary, pStudyIdx, pUsubjIdx, pIdvarIdx, r, srcNorm);
            int id = key == null ? KeyRows.NOT_FOUND : targetIndex.find(key);
            if (id == KeyRows.NOT_FOUND)
            {
                continue;
            }
            int end = targetIndex.end(id);
            for (int pos = targetIndex.start(id); pos < end; pos++)
            {
                long tr = targetIndex.row(pos);
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


    /**
     * One scanned row's key, or {@code null} when the row does not take part: classified on the RAW
     * RELREC values (a blank RELREC {@code USUBJID} means "not subject-scoped", a blank
     * {@code STUDYID} "no study named"), then filtered on the key identities, which is how both
     * scanned tables' {@code STUDYID} and {@code USUBJID} are keyed. A record-level link keys on
     * {@code (STUDYID, USUBJID)} alone — its value is the {@code IDVAR == IDVARVAL} filter, so the
     * key's value component is {@code ""} for every row — and a dataset-level link on
     * {@code (STUDYID, USUBJID, normKey(IDVAR-cell))}.
     *
     * @param idvarFilter
     *            the normalised {@code IDVARVAL} a record-level row must carry; unused
     *            dataset-level.
     */
    private static @Nullable RelrecKey scanKey(LinkSpec spec, IDataTable t, int studyIdx,
            int usubjIdx, int idvarIdx, long row, @Nullable String idvarFilter)
    {
        Object study = keyCell(t, studyIdx, row);
        if (isNonBlank(spec.study()) && !study.equals(spec.studyKey()))
        {
            return null;
        }
        Object usubj = keyCell(t, usubjIdx, row);
        if (isNonBlank(spec.usubj()) && !usubj.equals(spec.usubjKey()))
        {
            return null;
        }
        String idv = cell(t, idvarIdx, row);
        if (idv == null)
        {
            return null;
        }
        String idvNorm = Objects.requireNonNull(normKey(idv));
        if (spec.recordLevel())
        {
            return idvNorm.equals(idvarFilter) ? new RelrecKey(study, usubj, "") : null;
        }
        return new RelrecKey(study, usubj, idvNorm);
    }

    // ---- table index ----

    /**
     * Lazily builds, per {@code IDVAR} column, one {@link KeyRows} index for one table (rows with a
     * {@code null} value cell are skipped), built once and reused across every link of the related
     * domain. One keying serves both join modes: the key is
     * {@code (keyCell(STUDYID), keyCell(USUBJID), normKey(cell))}, so a link that names a study
     * probes that study's keys (c) and a link that names none probes each study the index holds,
     * every key finding only its own study on the other side (b). ⛔ There is no study-less keying
     * any more: the former {@code bySubject} index, keyed by {@code (USUBJID, normKey)} alone, is
     * what let a pooled package's second study join (USUBJID does not scope the study — SDTMIG 3.4
     * §4.2.3).
     */
    private static final class TableIndex
    {

        private final IDataTable table;

        private final int studyIdx;

        private final int usubjIdx;

        private final Map<String, KeyRows> byColumn = new HashMap<>();

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


        /** {@code (keyCell(STUDYID), keyCell(USUBJID), normKey(cell)) → rows}. */
        KeyRows byColumn(String column)
        {
            return byColumn.computeIfAbsent(column, this::build);
        }


        private KeyRows build(String column)
        {
            int colIdx = table.getMetaData().getColumnIndex(column);
            if (colIdx < 0 || usubjIdx < 0)
            {
                return KeyRows.build(0, _ -> null);
            }
            // JKM R5: keyCell, not cell — this index is probed with the RELREC row's studyKey and
            // usubjKey, so the two sides MUST share one identity. With cell()/nz() a row whose
            // USUBJID is MISSING indexed under "" and conflated with a genuinely empty one.
            return KeyRows.build(Math.toIntExact(table.getRowCount()), r -> indexKey(colIdx, r));
        }


        private @Nullable RelrecKey indexKey(int colIdx, long row)
        {
            String v = cell(table, colIdx, row);
            if (v == null)
            {
                return null;
            }
            // v is non-null, so normKey is non-null.
            return new RelrecKey(keyCell(table, studyIdx, row), keyCell(table, usubjIdx, row),
                    Objects.requireNonNull(normKey(v)));
        }
    }


    /**
     * A table's {@link RelrecKey}s and, per key, the rows carrying it, ascending — the
     * {@code KeyMatchIndex} P1/P5 shape ({@code PLAN-shared-key-identity} P-R1):
     * <ul>
     * <li>an <b>open-addressing</b> table maps a key to a dense key id — no entry objects, no
     * boxing;</li>
     * <li>the rows are <b>CSR</b>: {@code rows} lists every participating row grouped by key id,
     * {@code offsets[id] .. offsets[id + 1]} is that key's slice, ascending within it (the order
     * the {@code List<Long>} postings had);</li>
     * <li>the keys are also kept <b>by id</b>, i.e. in first-appearance order, so a dataset-level
     * join walks them deterministically, and the distinct {@code STUDYID} identities are kept in
     * first-appearance order for a record-level link that names no study.</li>
     * </ul>
     * Key identity is exactly {@link RelrecKey#equals}. Immutable once built; confined to the
     * expansion that built it.
     */
    static final class KeyRows
    {

        /** Returned by {@link #find} for a key the table does not carry. */
        static final int NOT_FOUND = -1;

        private static final int MIN_CAPACITY = 16;

        private final @Nullable RelrecKey[] slotKeys;

        private final int[] slotIds;

        private final int slotMask;

        private final RelrecKey[] keys;

        private final int[] offsets;

        private final int[] rows;

        private final List<Object> studies;

        private KeyRows(@Nullable RelrecKey[] aSlotKeys, int[] aSlotIds, RelrecKey[] aKeys,
                int[] aOffsets, int[] aRows, List<Object> aStudies)
        {
            slotKeys = aSlotKeys;
            slotIds = aSlotIds;
            slotMask = aSlotKeys.length - 1;
            keys = aKeys;
            offsets = aOffsets;
            rows = aRows;
            studies = aStudies;
        }


        /**
         * Builds the index in two passes: the first computes each row's key <b>once</b>, assigns
         * key ids and counts the rows per key; the second lays the rows out by key. The only
         * temporary is one {@code int} per row.
         *
         * @param aRowCount
         *            the table's row count.
         * @param aKeyOfRow
         *            the key of a row, or {@code null} when the row does not take part; called
         *            exactly once per row, in ascending row order.
         * @return the index, never {@code null}.
         */
        static KeyRows build(int aRowCount, IntFunction<@Nullable RelrecKey> aKeyOfRow)
        {
            @Nullable
            RelrecKey[] table = new RelrecKey[MIN_CAPACITY];
            int[] ids = new int[MIN_CAPACITY];
            RelrecKey[] byId = new RelrecKey[MIN_CAPACITY];
            int[] counts = new int[MIN_CAPACITY];
            Set<Object> studies = new LinkedHashSet<>();
            int size = 0;
            int[] rowKey = new int[aRowCount];
            for (int r = 0; r < aRowCount; r++)
            {
                RelrecKey key = aKeyOfRow.apply(r);
                if (key == null)
                {
                    rowKey[r] = NOT_FOUND;
                    continue;
                }
                int mask = table.length - 1;
                int slot = spread(key.hashCode()) & mask;
                RelrecKey stored = table[slot];
                while (stored != null && !stored.equals(key))
                {
                    slot = (slot + 1) & mask;
                    stored = table[slot];
                }
                if (stored != null)
                {
                    int id = ids[slot];
                    counts[id]++;
                    rowKey[r] = id;
                    continue;
                }
                int id = size++;
                table[slot] = key;
                ids[slot] = id;
                if (id == byId.length)
                {
                    byId = Arrays.copyOf(byId, id * 2);
                    counts = Arrays.copyOf(counts, id * 2);
                }
                byId[id] = key;
                counts[id] = 1;
                studies.add(key.study());
                rowKey[r] = id;
                if (size * 2 > table.length)
                {
                    int[] grownIds = new int[table.length * 2];
                    table = grow(table, ids, grownIds);
                    ids = grownIds;
                }
            }
            int[] offsets = new int[size + 1];
            for (int id = 0; id < size; id++)
            {
                offsets[id + 1] = offsets[id] + counts[id];
            }
            int[] rows = new int[offsets[size]];
            int[] cursor = Arrays.copyOf(offsets, size);
            for (int r = 0; r < aRowCount; r++)
            {
                int id = rowKey[r];
                if (id != NOT_FOUND)
                {
                    rows[cursor[id]++] = r;
                }
            }
            return new KeyRows(table, ids, Arrays.copyOf(byId, size), offsets, rows,
                    List.copyOf(studies));
        }


        /**
         * Re-hashes {@code aTable} into one twice its size, writing the ids into {@code aNewIds}.
         */
        private static @Nullable RelrecKey[] grow(@Nullable RelrecKey[] aTable, int[] aIds,
                int[] aNewIds)
        {
            @Nullable
            RelrecKey[] grown = new RelrecKey[aTable.length * 2];
            int mask = grown.length - 1;
            for (int i = 0; i < aTable.length; i++)
            {
                RelrecKey key = aTable[i];
                if (key == null)
                {
                    continue;
                }
                int slot = spread(key.hashCode()) & mask;
                while (grown[slot] != null)
                {
                    slot = (slot + 1) & mask;
                }
                grown[slot] = key;
                aNewIds[slot] = aIds[i];
            }
            return grown;
        }


        /**
         * The id of {@code aKey}.
         *
         * @param aKey
         *            the key.
         * @return its key id, or {@link #NOT_FOUND}.
         */
        int find(RelrecKey aKey)
        {
            for (int slot = spread(aKey.hashCode()) & slotMask;; slot = (slot + 1) & slotMask)
            {
                RelrecKey stored = slotKeys[slot];
                if (stored == null)
                {
                    return NOT_FOUND;
                }
                if (stored.equals(aKey))
                {
                    return slotIds[slot];
                }
            }
        }


        /** Returns the number of distinct keys; ids run {@code 0 .. keyCount() - 1}. */
        int keyCount()
        {
            return keys.length;
        }


        /** Returns key {@code aId}. */
        RelrecKey key(int aId)
        {
            return keys[aId];
        }


        /** Returns the first position of key {@code aId}'s slice, for {@link #row}. */
        int start(int aId)
        {
            return offsets[aId];
        }


        /** Returns one past the last position of key {@code aId}'s slice. */
        int end(int aId)
        {
            return offsets[aId + 1];
        }


        /** Returns the row at slice position {@code aPos}. */
        int row(int aPos)
        {
            return rows[aPos];
        }


        /** Returns the distinct {@code STUDYID} key identities, in first-appearance order. */
        List<Object> studies()
        {
            return studies;
        }


        private static int spread(int aHash)
        {
            int h = aHash * 0x9E3779B9;
            return h ^ (h >>> 16);
        }
    }

    // ---- key identity (JKM R5) — shared with the manager twin and the data browser's RELREC
    // dialog through cumba-datatable's GroupKeyPolicy.textKeyIdentity (PLAN-shared-key-identity):
    // the three sites no longer carry copies of the encoding, and a change to it is one change ----
    // ⭐ `nz` is GONE (2026-09-21, JKM R5): it mapped a missing cell to "", which is exactly
    // the conflation the ruling forbids. Every key site goes through keyCell instead. ⚠
    // Error Prone's UnusedMethod proved only that nz() had no caller left; it could not see a
    // probe that still compared RAW text against a keyCell index (E1) — that took a case table.

    /**
     * One join-key component's <b>identity</b>, so that a <b>missing</b> cell is not the empty
     * string.
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
     * ⭐ <b>The identity is the shared type's</b> ({@code PLAN-shared-key-identity}, owner ruling D2
     * (c)): {@link GroupKeyPolicy#textKeyIdentity(IDataValue)} under {@code KEEP_MISSING_KEYS} —
     * the cell's text (a present number's cleaned text: the RELREC link is a text join,
     * {@code D4-R5}), {@code ""}, or the {@code MissingValue} constant. Until then this method
     * rendered {@code KeyPart.reportingForm()} and keyed {@code \0}-joined strings on it, the
     * weaker of the two forms: a {@code "\\u0001" + marker} token is only <i>unlikely</i> to
     * collide with a text cell, where an enum constant <b>cannot</b>. The manager twin and the data
     * browser's dialog classify through the same method, so the three sites cannot drift apart.
     * </p>
     */
    private static Object keyCell(IDataTable t, int col, long row)
    {
        if (col < 0)
        {
            return ""; // JKM R7: absent column -> the character type default
        }
        return GroupKeyPolicy.KEEP_MISSING_KEYS.textKeyIdentity(t.getColumn(col).getDataValue(row));
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
