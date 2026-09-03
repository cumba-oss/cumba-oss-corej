package net.cumba.corej.core.exec;

import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;

/**
 * Per-{@code (table, Pattern)} cache of column indices whose names match a regex.
 *
 * <p>
 * Used by {@link ValueResolver#resolveWildcardValues} for {@code ${*}} wildcard operand resolution.
 * Without this cache, {@code collectWildcardValues} would regex-match every column of the foreign
 * table on every row evaluated against the wildcard — for ADSL with ~80 columns × millions of
 * primary rows, that's tens of millions of redundant regex matches.
 * </p>
 *
 * <p>
 * Cached pre-computed list of matching column indices is invariant for the lifetime of an
 * {@link IDataTable} (its column set doesn't change after load). Pattern equality is handled by
 * identity — {@code ExprCompiler} compiles one {@link java.util.regex.Pattern} per wildcard plan
 * and reuses it across rows, so the same instance reaches us for a given rule.
 * </p>
 *
 * <p>
 * Static cache; entries persist for the JVM lifetime, but bounded by
 * {@code (rule package size) × (foreign table count)} which is small.
 * </p>
 */
final class WildcardForeignColumnCache
{

    private record Key(int tableIdentity, int patternIdentity)
    {
    }

    private static final ConcurrentHashMap<Key, int[]> CACHE = new ConcurrentHashMap<>();

    private WildcardForeignColumnCache()
    {
    }


    /**
     * Returns the column indices in {@code table} whose names match {@code pattern}, computed once
     * per {@code (table identity, pattern identity)} and cached.
     */
    static int[] matchingColumns(IDataTable table, Pattern pattern)
    {
        Key key = new Key(System.identityHashCode(table), System.identityHashCode(pattern));
        return CACHE.computeIfAbsent(key, _ -> compute(table, pattern));
    }


    private static int[] compute(IDataTable table, Pattern pattern)
    {
        DataTableMeta meta = table.getMetaData();
        int colCount = meta.getColumnCount();
        int[] tmp = new int[colCount];
        int n = 0;
        for (int c = 0; c < colCount; c++)
        {
            String colName = meta.getColumn(c).getName();
            if (pattern.matcher(colName).matches())
            {
                tmp[n++] = c;
            }
        }
        if (n == colCount)
        {
            return tmp;
        }
        int[] out = new int[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }


    /** Test-only — clears the cache between unit-test runs. */
    static void clearForTesting()
    {
        CACHE.clear();
    }
}
