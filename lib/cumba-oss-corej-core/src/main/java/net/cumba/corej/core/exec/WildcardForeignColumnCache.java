package net.cumba.corej.core.exec;

import java.util.regex.Pattern;

import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;

/**
 * Per-{@code (table, regex)} cache of column indices whose names match a wildcard's regex.
 *
 * <p>
 * Used by {@link ValueResolver#resolveWildcardValues} for {@code ${*}} wildcard operand resolution
 * and by the output-variable wildcard expansion in {@link RuleRunner}. Without it,
 * {@code collectWildcardValues} would regex-match every column of the table on every row evaluated
 * against the wildcard — for ADSL with ~80 columns × millions of primary rows, tens of millions of
 * redundant regex matches.
 * </p>
 *
 * <p>
 * ⭐⭐ <b>Identity-exact and run-scoped</b> ({@code PLAN-identity-safe-join-caches} D1/D4). This used
 * to be a STATIC map keyed on {@code (identityHashCode(table), identityHashCode(pattern))} with no
 * reference check at all: a collision handed one table another table's (or another regex's) column
 * set — wrong values or an index out of bounds — and, because driver-bearing wildcards compile a
 * new {@link Pattern} per row, every row added an entry that was never freed, for the whole JVM
 * lifetime. Now:
 * </p>
 * <ul>
 * <li>the table is compared by identity ({@link IdentityWeakCache}), and the regex by its TEXT and
 * flags ({@link PatternKey}) — two patterns with the same text match the same columns, so a per-row
 * recompile hits instead of growing the map; the map is bounded by the distinct regexes of a
 * run;</li>
 * <li>an instance lives on the {@link EvaluationContext} (never {@code null}: each built context
 * gets its own unless one is passed in). {@link RuleRunner} passes the run's instance from
 * {@link JoinCache.SharedIndexCache}, so one validation run shares it and it dies with the
 * run.</li>
 * </ul>
 *
 * <p>
 * {@link #compute} reads only the table's column names and the regex, so a table's entry is
 * invariant while the table lives (a table's column set does not change after load). The returned
 * array is shared and must not be modified.
 * </p>
 */
final class WildcardForeignColumnCache
{

    /**
     * Every input of {@link #compute} besides the table: the regex text and its flags. (Every
     * pattern the engine builds today uses flags 0 — {@code OperandSubstitutor.toColumnPattern} —
     * but a different flag set matches different columns, so it is part of the key.)
     */
    record PatternKey(String regex, int flags)
    {
    }

    private final IdentityWeakCache<PatternKey, int[]> cache;

    /** A cache bucketing tables by {@code System.identityHashCode}. */
    WildcardForeignColumnCache()
    {
        this(new IdentityWeakCache<>());
    }


    /** A cache over the given backing cache — the test seam. */
    WildcardForeignColumnCache(IdentityWeakCache<PatternKey, int[]> aCache)
    {
        cache = aCache;
    }


    /**
     * Returns the column indices in {@code table} whose names match {@code pattern}, computed once
     * per {@code (table instance, regex, flags)} and cached.
     */
    int[] matchingColumns(IDataTable table, Pattern pattern)
    {
        return cache.getOrBuild(table, new PatternKey(pattern.pattern(), pattern.flags()),
                _ -> compute(table, pattern));
    }


    /** Returns how many column sets this cache has computed so far. */
    int computeCount()
    {
        return cache.buildCount();
    }


    /** Returns how many column sets are currently cached. */
    int entryCount()
    {
        return cache.valueCount();
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
}
