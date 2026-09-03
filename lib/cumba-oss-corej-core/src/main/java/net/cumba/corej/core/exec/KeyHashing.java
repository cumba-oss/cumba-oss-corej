package net.cumba.corej.core.exec;

import java.util.List;
import java.util.Objects;

import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.view.HashLookup;

/**
 * Shared zero-allocation key-hashing primitives for the CDISC engine. Used by both
 * {@link DatasetLookup} (cross-dataset joins) and the set-uniqueness operators in
 * {@code OperatorRegistry}.
 *
 * <h2>Equality semantics</h2>
 * <p>
 * Key equality is determined by {@link Objects#equals} on the raw column values (via
 * {@link IDataTable#getValue(long, int)}). This means a STRING column holding {@code "5"} and a
 * LONG column holding {@code 5L} do not compare equal — in contrast to a String-coerced
 * implementation. CDISC join/uniqueness keys are always STRING in practice, so this change has no
 * effect on real clinical data.
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
     * missing from the table (encoded as {@code -1} in {@code colIds}). A missing column
     * contributes a fixed sentinel to the hash so that two sides which both lack the same column
     * hash equally, while one side missing and the other present hash differently.
     */
    static int computeKeyHashSafe(IDataTable table, long row, int[] colIds)
    {
        int h = 0;
        for (int colId : colIds)
        {
            if (colId >= 0)
            {
                h = 31 * h + table.hashCodeAt(row, colId);
            }
            else
            {
                h = 31 * h;
            }
        }
        return h != 0 ? h : 1;
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
                    continue;
                }
                if (c1 < 0 || c2 < 0)
                {
                    return false;
                }
                Object v1 = table1.getValue(row1, c1);
                Object v2 = table2.getValue(row2, c2);
                if (!Objects.equals(v1, v2))
                {
                    return false;
                }
            }
            return true;
        }
    }
}
