package net.cumba.corej.core.exec;

import java.util.List;
import java.util.Objects;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.view.HashLookup;
import net.cumba.datatable.values.DataValueType;

/**
 * Shared zero-allocation key-hashing primitives for the CDISC engine. Used by both
 * {@link DatasetLookup} (cross-dataset joins) and the set-uniqueness operators in
 * {@code OperatorRegistry}.
 *
 * <h2>Equality semantics</h2>
 * <p>
 * Key equality is the ruled value identity of {@link GroupKeyPolicy.KeyPart}, never
 * {@link Objects#equals} on the raw column values. ⭐ Corrected 2026-09-21 ({@code JKM R5}): this
 * javadoc used to document the raw comparison, and the raw channel is {@code @Nullable} by
 * contract, cannot distinguish {@code STRING "5"} from {@code LONG 5}, and carries no
 * {@code MissingValue} — so it could not express the ruled identity at all. LONG column holding
 * {@code 5L} do not compare equal — in contrast to a String-coerced implementation. CDISC
 * join/uniqueness keys are always STRING in practice, so this change has no effect on real clinical
 * data.
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
     * ⚠ <b>{@code JKM R7}:</b> when this side lacks the column the component is <em>present but
     * empty</em> — the type default of the side that <em>does</em> carry it, since an absent column
     * has no type of its own. {@code otherTable}/{@code otherCol} are passed for exactly that.
     * </p>
     */
    private static GroupKeyPolicy.KeyPart part(IDataTable table, int row, int col,
            IDataTable otherTable, int otherCol)
    {
        if (col < 0)
        {
            return isNumericColumn(otherTable, otherCol)
                    ? GroupKeyPolicy.KeyPart.missing(net.cumba.datatable.values.MissingValue.MIS)
                    : GroupKeyPolicy.KeyPart.EMPTY;
        }
        return GroupKeyPolicy.KEEP_MISSING_KEYS.keyPart(table.getColumn(col).getDataValue(row));
    }


    private static boolean isNumericColumn(IDataTable table, int col)
    {
        if (col < 0)
        {
            // Unreachable: the caller only asks about the OTHER side, and the both-absent case left
            // the key before this point. Defensive, and character is the safer default.
            return false;
        }
        DataValueType type = table.getMetaData().getColumn(col).getType();
        return type == DataValueType.DOUBLE || type == DataValueType.LONG;
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
                    // would carry the same constant, so it cannot discriminate. ⚠ This site was
                    // already right when the other builder was wrong — and it is still wrong on the
                    // degenerate case: if EVERY component is skipped the hash is a constant and
                    // this
                    // matcher is all-true, so `_matched_` would read true for every row where R7
                    // rules a rule ERROR. That case is caught by the expander's KeySpec, which runs
                    // first for every entry the expander accepts; the entries reaching HERE are the
                    // Child:true ones, whose key always carries IDVAR/IDVARVAL.
                    continue;
                }
                if (!Objects.equals(part(table1, row1, c1, table2, c2),
                        part(table2, row2, c2, table1, c1)))
                {
                    return false;
                }
            }
            return true;
        }
    }
}
