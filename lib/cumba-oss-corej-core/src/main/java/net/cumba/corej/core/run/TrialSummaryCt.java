package net.cumba.corej.core.run;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.cumba.corej.core.exec.CodelistAttributes;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.IDataValue;

/**
 * The CDISC CT versions a study's Trial Summary states — the run's second CT source
 * (PLAN-ct-packages-default-and-ts-source CTD-2, owner 2026-10-05), consulted only when the CT
 * Packages field is blank and the Define-XML declares no CT for a root the run consumes.
 *
 * <ul>
 * <li><b>{@code TSVCDREF} / {@code TSVCDVER}</b> (SDTMIG 3.1.3–3.4): every row whose
 * {@code TSVCDREF} names CDISC CT ({@link CodelistAttributes#isCdiscCt}, the predicate
 * {@code get_codelist_attributes} uses — CQ3 (a)) with a non-blank {@code TSVCDVER} yields
 * {@code <root>-<TSVCDVER>}.</li>
 * <li><b>{@code TSPARMCD = "SNDCTVER"}</b> (CQ1 (a)): a SEND Trial Summary has no {@code TSVCDREF}
 * / {@code TSVCDVER}; it states its CT version as that row's {@code TSVAL}. For a {@code sendct}
 * root the one distinct ISO date found in {@code TSVAL} — spelled bare ({@code 2023-12-15}) or with
 * a prefix ({@code SEND CT 2024-09-27}) — yields {@code sendct-<date>}.</li>
 * <li><b>Dates parse strictly</b> (CQ4 (a), fold F3): a value that is not exactly one valid ISO
 * date ({@code 2023-12}, {@code Dec 2023}, {@code 2023-02-30}) is never taken and never aborted on;
 * it is listed in {@link CtSelection.TrialSummaryEvidence#ignored()} for the run note — the data
 * defect itself is {@code CDISC-CG0289}'s finding.</li>
 * <li><b>Columns are found case-insensitively</b> (owner ruling 2026-09-28), whatever the table's
 * own {@code columnNameCaseSensitive}. A missing column means no row of that family is taken
 * (CQ6).</li>
 * <li><b>Merge:</b> every distinct package is kept (CTD-2, D1 "merge, never first-wins"), newest
 * first, like {@code StudyValidationService.ctIdsWithPrefix}.</li>
 * </ul>
 */
final class TrialSummaryCt
{

    /** The SEND CT version parameter (CT term C96370). */
    static final String SNDCTVER = "SNDCTVER";

    /** The SEND CT root, whose Trial Summary also carries {@link #SNDCTVER} rows. */
    static final String SENDCT_ROOT = "sendct";

    /** Exactly a {@code YYYY-MM-DD} shape; the calendar check is {@link #STRICT_DATE}'s. */
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /**
     * An ISO date token inside a longer {@code TSVAL}, not part of a longer digit run: only digit
     * adjacency (directly, or across one hyphen) disqualifies, so {@code sendct-2024-09-27} and
     * {@code SEND-CT-2024-09-27} still name their date (5e review LOW-1).
     */
    private static final Pattern ISO_DATE_TOKEN = Pattern
            .compile("(?<!\\d)(?<!\\d-)\\d{4}-\\d{2}-\\d{2}(?!-?\\d)");

    /** ISO_LOCAL_DATE with its STRICT resolver stated explicitly: 2023-02-30 does not parse. */
    private static final DateTimeFormatter STRICT_DATE = DateTimeFormatter.ISO_LOCAL_DATE
            .withResolverStyle(ResolverStyle.STRICT);

    private TrialSummaryCt()
    {
    }


    /**
     * Reads the CDISC CT versions {@code aTs} states for {@code aCtRoot}.
     *
     * @param aTs
     *            the study's Trial Summary
     * @param aCtRoot
     *            the run's TS root: {@code sdtmct} or {@code sendct}
     * @return the packages (distinct, newest first) and the values not taken
     */
    static CtSelection.TrialSummaryEvidence read(IDataTable aTs, String aCtRoot)
    {
        int vcdref = columnIndex(aTs, "TSVCDREF");
        int vcdver = columnIndex(aTs, "TSVCDVER");
        boolean send = SENDCT_ROOT.equals(aCtRoot);
        int parmcd = send ? columnIndex(aTs, "TSPARMCD") : -1;
        int tsval = send ? columnIndex(aTs, "TSVAL") : -1;
        boolean refFamily = vcdref >= 0 && vcdver >= 0;
        boolean sendFamily = parmcd >= 0 && tsval >= 0;
        if (!refFamily && !sendFamily)
        {
            return CtSelection.TrialSummaryEvidence.EMPTY;
        }
        Map<String, String> evidenceById = new LinkedHashMap<>();
        Set<String> ignored = new LinkedHashSet<>();
        long rows = aTs.getRowCount();
        for (long r = 0; r < rows; r++)
        {
            if (refFamily)
            {
                readRefRow(aTs, r, vcdref, vcdver, aCtRoot, evidenceById, ignored);
            }
            if (sendFamily)
            {
                readSndctverRow(aTs, r, parmcd, tsval, aCtRoot, evidenceById, ignored);
            }
        }
        List<CtSelection.TsRef> refs = new ArrayList<>(evidenceById.size());
        for (Map.Entry<String, String> e : evidenceById.entrySet())
        {
            refs.add(new CtSelection.TsRef(e.getKey(), e.getValue()));
        }
        // Newest first: the ids share one root and end in an ISO date, so the lexicographic
        // order is the date order (the store provider's ctIdsWithPrefix sorts the same way).
        refs.sort(Comparator.comparing(CtSelection.TsRef::packageId).reversed());
        return new CtSelection.TrialSummaryEvidence(refs, new ArrayList<>(ignored));
    }


    private static void readRefRow(IDataTable aTs, long aRow, int aRefCol, int aVerCol,
            String aCtRoot, Map<String, String> aEvidenceById, Set<String> aIgnored)
    {
        String ref = text(aTs, aRow, aRefCol);
        if (!CodelistAttributes.isCdiscCt(ref))
        {
            return;
        }
        String version = text(aTs, aRow, aVerCol).strip();
        if (version.isEmpty())
        {
            return;
        }
        String evidence = "TSVCDREF \"" + ref.strip() + "\", TSVCDVER \"" + version + "\"";
        if (ISO_DATE.matcher(version).matches() && isCalendarDate(version))
        {
            aEvidenceById.putIfAbsent(aCtRoot + "-" + version, evidence);
        }
        else
        {
            aIgnored.add(evidence);
        }
    }


    private static void readSndctverRow(IDataTable aTs, long aRow, int aParmcdCol, int aValCol,
            String aCtRoot, Map<String, String> aEvidenceById, Set<String> aIgnored)
    {
        if (!SNDCTVER.equals(text(aTs, aRow, aParmcdCol).strip()))
        {
            return;
        }
        String value = text(aTs, aRow, aValCol).strip();
        if (value.isEmpty())
        {
            return;
        }
        String evidence = "TSPARMCD \"" + SNDCTVER + "\", TSVAL \"" + value + "\"";
        // Exactly one DISTINCT date: the same date spelled twice is one statement (LOW-1).
        Set<String> dates = new LinkedHashSet<>(2);
        Matcher m = ISO_DATE_TOKEN.matcher(value);
        while (m.find())
        {
            dates.add(m.group());
        }
        String date = dates.size() == 1 ? dates.iterator().next() : null;
        if (date != null && isCalendarDate(date))
        {
            aEvidenceById.putIfAbsent(aCtRoot + "-" + date, evidence);
        }
        else
        {
            aIgnored.add(evidence);
        }
    }


    private static boolean isCalendarDate(String aDate)
    {
        try
        {
            LocalDate.parse(aDate, STRICT_DATE);
            return true;
        }
        catch (DateTimeException _)
        {
            return false;
        }
    }


    /** The column's index, matched case-insensitively, or {@code -1}. */
    private static int columnIndex(IDataTable aTs, String aName)
    {
        DataTableMeta meta = aTs.getMetaData();
        for (int c = 0; c < meta.getColumnCount(); c++)
        {
            if (aName.equalsIgnoreCase(meta.getColumn(c).getName().strip()))
            {
                return c;
            }
        }
        return -1;
    }


    private static String text(IDataTable aTs, long aRow, int aColumn)
    {
        IDataValue cell = aTs.getDataValue(aRow, aColumn);
        return cell.isMissingOrInvalid() ? "" : cell.getValueAsString();
    }
}
