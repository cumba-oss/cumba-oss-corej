package net.cumba.corej.core.exec;

import java.util.List;
import java.util.Objects;
import net.cumba.corej.core.exec.GroupKeyPolicy.KeyPart;
import net.cumba.corej.core.expr.eval.ColumnTypeGate;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.view.HashLookup;
import net.cumba.datatable.values.DataValueType;
import org.jspecify.annotations.Nullable;

/**
 * Shared key-hashing primitives for the CDISC engine — used by {@link DatasetLookup} (the hashed
 * join arm, and the {@code DegenerateJoinKeyException} it raises). ⚠ Corrected 2026-09-25: this
 * used to name {@code KeyMatchRowExpander} as a consumer; it has its own key path and calls nothing
 * here.
 *
 * <p>
 * ⚠ This heading said <b>"zero-allocation"</b> and named <i>"the set-uniqueness operators in
 * {@code OperatorRegistry}"</i> as a consumer. Both were corrected 2026-09-21: since {@code JKM R5}
 * moved the hash onto the typed channel, {@link #computeKeyHashSafe} allocates one
 * {@code IDataValue} and one {@code KeyPart} per component per row (its own javadoc prices that),
 * and a grep of {@code src/main} finds no {@code OperatorRegistry} consumer — that half was already
 * stale before this change.
 * </p>
 *
 * <h2>Equality semantics</h2>
 * <p>
 * Key equality is the ruled value identity of {@link GroupKeyPolicy.KeyPart}, never
 * {@link Objects#equals} on the raw column values. ⭐ Corrected 2026-09-21 ({@code JKM R5}): this
 * javadoc used to document the raw comparison, and the raw channel is {@code @Nullable} by
 * contract, cannot distinguish a {@code STRING "5"} from a {@code LONG 5}, and carries no
 * {@code MissingValue} — so it could not express the ruled identity at all.
 *
 * <p>
 * ⛔⛔ <b>This paragraph previously ended "CDISC join/uniqueness keys are always STRING in practice,
 * so this change has no effect on real clinical data."</b> That sentence, and the ungrammatical
 * fragment before it, were left behind by a botched edit of the pre-ruling text — and as spliced it
 * asserted NON-HARM for the very change it sat on. It is deleted, not corrected: the claim is false
 * (a numeric join key typed differently on the two sides is routine) and it is exactly the sentence
 * a later reader would have cited to skip measuring. ⚠ Found by the plan's own non-harm review
 * pass, which is what that pass is for.
 * </p>
 * </p>
 */
final class KeyHashing
{

    private KeyHashing()
    {
    }


    /**
     * Resolves key column names to indices in the given table, returning {@code -1} for any column
     * that is not present.
     */
    static int[] resolveColIds(DataTableMeta meta, List<String> keyColumns)
    {
        int[] ids = new int[keyColumns.size()];
        for (int i = 0; i < keyColumns.size(); i++)
        {
            ids[i] = meta.getColumnIndex(keyColumns.get(i));
        }
        return ids;
    }


    /**
     * Computes a 32-bit hash of the key column values at the given row, tolerating columns that are
     * missing from the table (encoded as {@code -1} in {@code colIds}).
     *
     * <p>
     * ⭐⭐ <b>The hash had to move to the typed channel WITH the matcher, and finding that out is
     * what this method's comment exists for.</b> Routing only {@link KeyMatcher} through
     * {@link GroupKeyPolicy.KeyPart} left the hash on {@code table.hashCodeAt}, i.e. on the raw
     * value — so a {@code LONG 2} and a {@code DOUBLE 2.0} landed in <b>different buckets</b> and
     * the typed matcher was <b>never consulted</b>. The change read as a no-op: 5 475 tests stayed
     * green and the one case that proves it works still failed. A matcher that is never reached
     * cannot decide anything.
     * </p>
     *
     * <p>
     * ⚠ <b>Every BLANK component contributes the same sentinel</b> — {@code ""}, any
     * {@link net.cumba.datatable.values.MissingValue}, and an absent column alike. That is
     * deliberate and it is what makes {@code JKM R7} work without the hash knowing the other side's
     * type: an absent column contributes the other side's type default ({@code EMPTY} or a numeric
     * missing), and the hash cannot see which, so the two must share a bucket and let the matcher —
     * which <em>can</em> see both sides — apply the ruled identity. ⇒ blanks collide by design;
     * {@code Empty} vs {@code Missing(MIS)} vs {@code Missing(MIS_A)} are separated by
     * {@link KeyMatcher#matches}, never by the bucket.
     * </p>
     *
     * <p>
     * ⚠ Cost: one {@link net.cumba.datatable.values.IDataValue} and one {@code KeyPart} per
     * component per row, where the raw form allocated neither. The population is the keyed entries
     * the expander refuses by NAME ({@code RELREC}, {@code --}, {@code SUPP*} / {@code SQ*} — none
     * shipped, reachable by a user package); since 2026-09-25 a {@code Child: true} entry builds no
     * lookup and never comes through here either. Correctness on a join key is not tradeable for an
     * allocation.
     * </p>
     */
    static int computeKeyHashSafe(IDataTable table, long row, int[] colIds)
    {
        int h = 0;
        for (int colId : colIds)
        {
            if (colId < 0)
            {
                h = 31 * h; // absent column: the blank sentinel, same as any blank cell
                continue;
            }
            KeyPart part = GroupKeyPolicy.KEEP_MISSING_KEYS
                    .keyPart(table.getColumn(colId).getDataValue(row));
            h = 31 * h + (part.present() ? part.hashCode() : 0);
        }
        return h != 0 ? h : 1;
    }


    /**
     * {@link #computeKeyHashSafe(IDataTable, long, int[])} over pre-built readers — the same hash,
     * for the per-row loops that build a join index or map ({@code PLAN-identity-safe-join-caches}
     * Phase 3b). A {@code null} reader is an absent column ({@code -1}).
     *
     * @param readers
     *            one reader per key column, from {@link KeyCellReader#of(IDataTable, int[])}.
     * @param row
     *            the row.
     * @return the same hash the table-based overload answers.
     */
    static int computeKeyHashSafe(@Nullable KeyCellReader[] readers, long row)
    {
        int h = 0;
        for (KeyCellReader reader : readers)
        {
            if (reader == null)
            {
                h = 31 * h; // absent column: the blank sentinel, same as any blank cell
                continue;
            }
            KeyPart part = reader.read(row);
            h = 31 * h + (part.present() ? part.hashCode() : 0);
        }
        return h != 0 ? h : 1;
    }

    /**
     * {@link HashLookup.BiRowMatcher} that compares key column values across two tables, tolerating
     * missing columns ({@code -1} in either {@code colIds}) under {@code JKM R7}: a column absent
     * on <b>both</b> sides leaves the key at that position; a column absent on <b>one</b> side
     * contributes the other side's <b>type default</b> ({@code ""} for a character column, the
     * numeric missing {@code MIS} for a numeric one) and is compared like any other component.
     * <p>
     * A single instance can be reused across all probes in a loop — the matcher itself holds no
     * per-probe state.
     */
    static final class KeyMatcher implements HashLookup.BiRowMatcher
    {

        /** One reader per key column of each side, built once — {@code null} where absent. */
        private final @Nullable KeyCellReader[] readers1;

        private final @Nullable KeyCellReader[] readers2;

        /**
         * Per component, what an absent side contributes when the OTHER side has the column
         * ({@code JKM R7}'s one-side rule) — {@code null} where both sides have it (the values
         * compare) or neither does (the component leaves the key).
         */
        private final @Nullable KeyPart[] absentPart;

        KeyMatcher(IDataTable table1, int[] colIds1, IDataTable table2, int[] colIds2)
        {
            // Phase 3b: the readers are built once per matcher (a matcher serves a whole loop),
            // never per row. They answer exactly the keyPart the per-cell path did.
            readers1 = KeyCellReader.of(table1, colIds1);
            readers2 = KeyCellReader.of(table2, colIds2);
            absentPart = absentParts(table1, colIds1, table2, colIds2);
        }


        /**
         * {@code JKM R7}, the one-side rule, decided once per matcher: the present side's declared
         * type decides what the absent side contributes — the same classification
         * {@code KeyMatchRowExpander.keySpec} makes for the expander arm, through the one home of
         * the numeric question ({@link ColumnTypeGate#kindOf}). A character column's default is the
         * empty string (a PRESENT value, D34 #1); a numeric column's is a {@code MissingValue},
         * since a numeric cell cannot hold {@code ""}. Under {@code JKM R5} that constant pairs
         * exactly the rows whose present side holds it — {@code ""} joins {@code ""}, {@code MIS}
         * joins {@code MIS} and no other marker.
         *
         * <p>
         * ⭐ <b>Why the hash needs no change for this.</b> {@link #computeKeyHashSafe} folds an
         * absent column as {@code 31 * h} and a non-{@code present()} part — {@code Empty} and
         * every {@code Missing} — as {@code 31 * h + 0}: the same contribution. So the probe from
         * the absent side already lands in the bucket of the rows this arm now matches; before it,
         * the matcher refused them there.
         * </p>
         *
         * <p>
         * ⚑ <b>History, kept because the omission was deliberate.</b> This arm was implemented on
         * 2026-09-21, measured, and REVERTED by {@code PLAN-join-key-missing-semantics}' non-harm
         * review: a {@code Child: true} entry then ALSO reached {@link DatasetLookup} with a direct
         * keyed lookup on {@code [USUBJID, IDVAR, IDVARVAL]}, and on the two entries whose joined
         * side lacked {@code IDVAR}/{@code IDVARVAL} this arm would have paired subject-level rows
         * nobody asked to pair. {@code PLAN-hashed-join-arm-absent-columns} (owner, 2026-09-25,
         * option A) removed that lookup — a Child entry is joined only through its pointer, by
         * {@code ChildMatchPreMerger} — and landed this arm in the same change. ⚠ The comment that
         * stood here in between over-claimed: <i>"matched NOTHING, ever"</i> was true of 2 of the
         * 11 entries, and <i>"{@code _matched_} would flip on 5 shipped rules"</i> of 0 — the flag
         * on a Child entry has been a stage-A load error all along (phase 1 findings, §6).
         * </p>
         */
        private static @Nullable KeyPart[] absentParts(IDataTable table1, int[] colIds1,
                IDataTable table2, int[] colIds2)
        {
            @Nullable
            KeyPart[] parts = new KeyPart[colIds1.length];
            for (int i = 0; i < colIds1.length; i++)
            {
                boolean in1 = colIds1[i] >= 0;
                boolean in2 = colIds2[i] >= 0;
                if (in1 == in2)
                {
                    continue; // both present: the values compare; both absent: the component leaves
                }
                DataValueType type = in1 ? table1.getMetaData().getColumn(colIds1[i]).getType()
                        : table2.getMetaData().getColumn(colIds2[i]).getType();
                parts[i] = ColumnTypeGate.kindOf(type) == ColumnTypeGate.Kind.NUMERIC
                        ? KeyPart.MISSING_MIS
                        : KeyPart.EMPTY;
            }
            return parts;
        }


        /**
         * Whether two rows' keys are equal, component by component, as
         * {@link GroupKeyPolicy.KeyPart}s read through {@link KeyCellReader} (exactly
         * {@code KEEP_MISSING_KEYS.keyPart} of each cell), an absent side reading its
         * {@link #absentParts type default}.
         *
         * <p>
         * ⭐⭐ <b>{@code JKM R5} — why this is not {@code Objects.equals} on the raw value.</b> This
         * matcher used to read {@link IDataTable#getValue(long, int)}, which is declared
         * {@code @Nullable}, and compare the boxed raw objects. Three defects in one line: a raw
         * {@code null} compared equal to another raw {@code null} (reachable today — an
         * {@code .rds} null factor level, {@code RdataTableProvider.copyFactorData}), which is a
         * {@code null} in a value comparison and so a null-free-channel violation; a
         * {@code STRING "5"} could never equal a {@code LONG 5}; and the ruled missing-value
         * identity was not applied at all, because the raw channel carries no
         * {@link net.cumba.datatable.values.MissingValue}. Routing through
         * {@link GroupKeyPolicy#keyPart} makes the comparison the ruled one, by construction.
         * </p>
         */
        @Override
        public boolean matches(int row1, int row2)
        {
            for (int i = 0; i < readers1.length; i++)
            {
                KeyCellReader r1 = readers1[i];
                KeyCellReader r2 = readers2[i];
                if (r1 == null && r2 == null)
                {
                    // JKM R7: absent on BOTH sides -> the component leaves the key. Both sides
                    // would carry the same constant, so it cannot discriminate. ⭐ This site already
                    // implemented that when the other builder did not.
                    continue;
                }
                // JKM R7: absent on ONE side -> that side contributes the other side's type
                // default (absentPart, non-null exactly here) and the component is compared.
                KeyPart p1 = r1 == null ? absentPart[i] : r1.read(row1);
                KeyPart p2 = r2 == null ? absentPart[i] : r2.read(row2);
                if (!Objects.equals(p1, p2))
                {
                    return false;
                }
            }
            return true;
        }
    }
}
