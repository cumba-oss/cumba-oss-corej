package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * The parsed content of one {@code SUPP--} dataset, built once per table per run and shared by
 * every rule the {@link SuppPivot} answers for ({@code PLAN-operation-replacements} §2.3, the
 * declared SUPP merge {@code Supp_Merge}).
 *
 * <p>
 * One pass over the supplemental rows records, per non-blank {@code QNAM} in first-seen order, the
 * rows that carry it: the subject key (the {@code USUBJID} <em>and</em> {@code POOLID} texts, each
 * {@code ""} when its column is absent or the cell missing — see {@link SubjectKey}), the
 * {@code IDVAR} name ({@code ""} for a subject-level qualifier — SDTMIG 8.4: a blank {@code IDVAR}
 * qualifies every record of the subject), the {@code IDVARVAL} text and the {@code QVAL} cell
 * itself (identity kept: a missing {@code QVAL} stays that missing). Rows whose {@code QNAM} is
 * blank, whose {@code IDVAR} is set while {@code IDVARVAL} is missing, or that are subject-level
 * with a blank subject key (naming neither a subject, a pool nor a record) can qualify no record
 * and are dropped. The {@code IDVARVAL} text is stored raw (stripped only at match time,
 * {@link ChildMatchIndex#normalizeJoinToken}) because the coercion depends on the parent column's
 * type, which is known only when a parent is merged.
 * </p>
 *
 * <p>
 * ⭐ <b>Pooled records</b> (SENDIG: <i>"either USUBJID or POOLID must be populated"</i> — a pooled
 * record carries a blank {@code USUBJID} and a populated {@code POOLID}, and its {@code SUPP--}
 * rows carry the same {@code POOLID}). The subject key is therefore the <em>pair</em>: keyed on
 * {@code USUBJID} alone every pooled record read {@code ""} and a qualifier of one pool qualified
 * every pool, and a subject-level row with a blank {@code USUBJID} qualified every pooled record
 * (combined review of runbook W2–W8, W2 H2). A table without a {@code POOLID} column simply
 * contributes {@code ""} for that half, so a subject domain without pools keys exactly as before.
 * The pair is sound because SENDIG makes the halves exclusive (combined review round 2, engine L5 /
 * corpus L5, kept deliberately): a parent row carrying <em>both</em> — which SENDIG disallows —
 * matches only a {@code SUPP--} row carrying the same pair, and a record-level row whose halves are
 * both blank reaches a parent record whose halves are both blank (a trial-level parent).
 * </p>
 *
 * <p>
 * A {@code QNAM} cell is a value and is recorded exactly, case-sensitively (owner 2026-09-28,
 * register CIT §3: <i>"a String that contains a column name is still a string in the engine"</i>) —
 * the same reading {@code OperatorRegistry.existsInSuppQnam} applies on the existence path, so the
 * merged column set and the existence pivot agree by construction.
 * </p>
 */
final class SuppQnamIndex
{

    /**
     * The identity of the subject (or pool) a row belongs to: the {@code USUBJID} text and the
     * {@code POOLID} text, each {@code ""} when the column is absent or the cell missing. Shared by
     * the supplemental side (an {@link Entry}) and the parent side ({@code SuppPivot} derives one
     * per parent row through {@link #of(IDataTable, int, int, long)}), so the two cannot key
     * differently.
     */
    record SubjectKey(String usubjid, String poolid)
    {

        /** The key of a row naming neither a subject nor a pool. */
        static final SubjectKey BLANK = new SubjectKey("", "");

        /**
         * The key of row {@code r} of {@code table}, read through the two column indices (either
         * {@code -1} when the table lacks that column).
         */
        static SubjectKey of(IDataTable table, int usubjidIdx, int poolidIdx, long r)
        {
            String usubjid = usubjidIdx < 0 ? null : text(table, usubjidIdx, r);
            String poolid = poolidIdx < 0 ? null : text(table, poolidIdx, r);
            if (usubjid == null && poolid == null)
            {
                return BLANK;
            }
            return new SubjectKey(usubjid == null ? "" : usubjid, poolid == null ? "" : poolid);
        }


        boolean isBlank()
        {
            return usubjid.isEmpty() && poolid.isEmpty();
        }
    }


    /** One supplemental row that qualifies a record ({@code idvar} set) or a subject (blank). */
    record Entry(SubjectKey subject, String idvar, String idvarval, IDataValue qval)
    {

        boolean subjectLevel()
        {
            return idvar.isEmpty();
        }
    }

    private final Map<String, List<Entry>> byQnam;

    private SuppQnamIndex(Map<String, List<Entry>> aByQnam)
    {
        byQnam = aByQnam;
    }


    /**
     * Parses {@code supp}, or answers {@code null} when the table has no {@code QNAM} column at
     * all. A missing {@code IDVAR} / {@code IDVARVAL} / {@code USUBJID} / {@code POOLID} column
     * reads as blank for every row (combined review of runbook W2–W8, W2 L2: the existence path and
     * the pivot read ONE parse — {@link #qnams()} is every {@code QNAM} the table carries,
     * {@link #entries(String)} the rows that can qualify a record).
     *
     * @param supp
     *            the supplemental dataset
     * @return the index, or {@code null} when the table has no {@code QNAM} column
     */
    static @Nullable SuppQnamIndex of(IDataTable supp)
    {
        DataTableMeta meta = supp.getMetaData();
        int qnamIdx = meta.getColumnIndex("QNAM");
        if (qnamIdx < 0)
        {
            return null;
        }
        int idvarIdx = meta.getColumnIndex("IDVAR");
        int idvarvalIdx = meta.getColumnIndex("IDVARVAL");
        int usubjidIdx = meta.getColumnIndex("USUBJID");
        int poolidIdx = meta.getColumnIndex("POOLID");
        int qvalIdx = meta.getColumnIndex("QVAL");
        Map<String, List<Entry>> byQnam = new LinkedHashMap<>();
        long rowCount = supp.getRowCount();
        for (long r = 0; r < rowCount; r++)
        {
            String qnam = text(supp, qnamIdx, r);
            if (qnam == null || qnam.isEmpty())
            {
                continue;
            }
            List<Entry> entries = byQnam.computeIfAbsent(qnam, _ -> new ArrayList<>());
            String idvar = idvarIdx < 0 ? null : text(supp, idvarIdx, r);
            String idvarval = idvarvalIdx < 0 ? null : text(supp, idvarvalIdx, r);
            if (idvar == null)
            {
                idvar = "";
            }
            if (!idvar.isEmpty() && idvarval == null)
            {
                continue; // a record-level row without a record value qualifies nothing
            }
            SubjectKey subject = SubjectKey.of(supp, usubjidIdx, poolidIdx, r);
            if (idvar.isEmpty() && subject.isBlank())
            {
                continue; // a subject-level row naming neither subject nor pool qualifies nothing
            }
            IDataValue qval = qvalIdx < 0 ? SuppPivot.BLANK : supp.getDataValue(r, qvalIdx);
            entries.add(new Entry(subject, idvar, idvarval == null ? "" : idvarval, qval));
        }
        return new SuppQnamIndex(byQnam);
    }


    /** Every {@code QNAM} the table carries (the existence half), in first-seen order. */
    Set<String> qnams()
    {
        return Collections.unmodifiableSet(byQnam.keySet());
    }


    /**
     * The rows carrying {@code qnam} that can qualify a record, in dataset order (empty for an
     * unknown name, and for a name whose rows can all qualify nothing).
     */
    List<Entry> entries(String qnam)
    {
        List<Entry> entries = byQnam.get(qnam);
        return entries == null ? List.of() : Collections.unmodifiableList(entries);
    }


    private static @Nullable String text(IDataTable table, int colIdx, long row)
    {
        IDataValue dv = table.getDataValue(row, colIdx);
        return dv.isMissingOrInvalid() ? null : dv.getValueAsString();
    }
}
