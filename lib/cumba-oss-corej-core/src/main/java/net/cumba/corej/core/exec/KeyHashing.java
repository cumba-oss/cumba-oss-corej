package net.cumba.corej.core.exec;

import java.util.List;
import java.util.Objects;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.view.HashLookup;

/**
 * Shared key-hashing primitives for the CDISC engine — used by {@link DatasetLookup} (cross-dataset
 * joins) and {@code KeyMatchRowExpander}.
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
     * component per row, where the raw form allocated neither. The population is the
     * {@code Child:true} entries — the expander's own key path does not come through here — and
     * correctness on a join key is not tradeable for an allocation.
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
            GroupKeyPolicy.KeyPart part = GroupKeyPolicy.KEEP_MISSING_KEYS
                    .keyPart(table.getColumn(colId).getDataValue(row));
            h = 31 * h + (part.present() ? part.hashCode() : 0);
        }
        return h != 0 ? h : 1;
    }


    /**
     * One key component's identity, as a {@link GroupKeyPolicy.KeyPart}.
     *
     * <p>
     * ⭐⭐ <b>{@code JKM R5} — why this is not {@code Objects.equals} on the raw value.</b> This
     * matcher used to read {@link IDataTable#getValue(long, int)}, which is declared
     * {@code @Nullable}, and compare the boxed raw objects. Three defects in one line: a raw
     * {@code null} compared equal to another raw {@code null} (reachable today — an {@code .rds}
     * null factor level, {@code RdataTableProvider.copyFactorData}), which is a {@code null} in a
     * value comparison and so a null-free-channel violation; a {@code STRING "5"} could never equal
     * a {@code LONG 5}; and the ruled missing-value identity was not applied at all, because the
     * raw channel carries no {@link net.cumba.datatable.values.MissingValue}. Routing through
     * {@link GroupKeyPolicy#keyPart} makes the comparison the ruled one, by construction.
     * </p>
     *
     * <p>
     * ⚠ <b>Only reached for a component present on BOTH sides.</b> The caller settles both-absent
     * (the component leaves the key, {@code JKM R7}) and one-side-absent (no match, pending the
     * plan's D7) before getting here, which is why this needs no knowledge of the other side. An
     * earlier version took the other side's table/column to derive an absent side's type default;
     * that arm went with R7's one-side rule when the non-harm review reverted it, and its
     * {@code DOUBLE || LONG} predicate went too — leaving that classification in ONE place
     * ({@code KeyMatchRowExpander.isNumeric}) rather than two copies to keep in step.
     * </p>
     */
    private static GroupKeyPolicy.KeyPart part(IDataTable table, int row, int col)
    {
        return GroupKeyPolicy.KEEP_MISSING_KEYS.keyPart(table.getColumn(col).getDataValue(row));
    }


    /**
     * Returns {@code true} if any of the given key columns has a missing or invalid value in the
     * given row. A column listed as {@code -1} (not present in the table) also counts as missing.
     * <p>
     * Routes through {@link IDataTable#isMissingOrNull(long, int)} so buffer-backed tables can hit
     * the typed buffer's missing sentinel directly — no
     * {@link net.cumba.datatable.values.IDataValue} wrapper allocation, no autoboxing on numeric
     * columns.
     */
    static boolean anyKeyMissing(IDataTable table, int[] colIds, long row)
    {
        for (int colId : colIds)
        {
            if (colId < 0)
            {
                return true;
            }
            if (table.isMissingOrNull(row, colId))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * {@link HashLookup.BiRowMatcher} that compares key column values across two tables, tolerating
     * missing columns ({@code -1} in either {@code colIds}). A column missing on both sides is
     * considered equal at that position; a column missing on only one side is unequal.
     * <p>
     * A single instance can be reused across all probes in a loop — the matcher itself holds no
     * per-probe state.
     */
    static final class KeyMatcher implements HashLookup.BiRowMatcher
    {

        private final IDataTable table1;

        private final int[] colIds1;

        private final IDataTable table2;

        private final int[] colIds2;

        KeyMatcher(IDataTable table1, int[] colIds1, IDataTable table2, int[] colIds2)
        {
            this.table1 = table1;
            this.colIds1 = colIds1;
            this.table2 = table2;
            this.colIds2 = colIds2;
        }


        @Override
        public boolean matches(int row1, int row2)
        {
            for (int i = 0; i < colIds1.length; i++)
            {
                int c1 = colIds1[i];
                int c2 = colIds2[i];
                if (c1 < 0 && c2 < 0)
                {
                    // JKM R7: absent on BOTH sides -> the component leaves the key. Both sides
                    // would
                    // carry the same constant, so it cannot discriminate. ⭐ This site already
                    // implemented that when the other builder did not.
                    continue;
                }
                if (c1 < 0 || c2 < 0)
                {
                    // ⛔⛔ R7's ONE-SIDE-absent rule is deliberately NOT implemented here, and this
                    // is not an oversight — it was implemented, measured, and REVERTED by the
                    // plan's
                    // own non-harm review (2026-09-21).
                    //
                    // R7 says an absent column contributes the other side's type default and
                    // participates. Doing that HERE has a consequence R7 does not authorise: per
                    // the
                    // plan's D7, a `Child: true` entry ALSO gets a DatasetLookup built on
                    // [USUBJID, IDVAR, IDVARVAL] against a primary (AE, DM…) that has neither IDVAR
                    // nor IDVARVAL. That parasitic lookup matched NOTHING, ever, precisely because
                    // of
                    // this guard. With R7 applied, the primary's absent side contributes EMPTY and
                    // a
                    // subject-level CO comment or SUPPDM record — whose IDVAR/IDVARVAL are "" by
                    // the
                    // SDTM shape — also yields EMPTY, so it MATCHES: `_matched_` would flip false
                    // ->
                    // true on 5 shipped rules, and a dotted read would start answering the CO/SUPP
                    // row's values instead of the absent-column default.
                    //
                    // D7 is bucket (4) of the plan's scope — "STILL UNDECIDED, deliberately".
                    // Trading
                    // one unruled wrong answer for a different unruled wrong answer is not this
                    // plan's authorisation. ⇒ the conservative answer stays until D7 is ruled, and
                    // R7's one-side rule lives on the expander arm (196 entries) where no parasite
                    // rides along.
                    return false;
                }
                if (!Objects.equals(part(table1, row1, c1), part(table2, row2, c2)))
                {
                    return false;
                }
            }
            return true;
        }
    }
}
