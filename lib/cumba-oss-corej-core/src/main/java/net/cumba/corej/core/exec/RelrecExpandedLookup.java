package net.cumba.corej.core.exec;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;

/**
 * {@link JoinLookup} for a row-EXPANDED forward RELREC join. Unlike a one-lookup-per-primary-row
 * (first-wins) join, this lookup is keyed by the row index of the <em>expanded</em> evaluation
 * table produced by {@link RelrecRowExpander}: every expanded row binds to exactly one related row,
 * so a scalar lookup is exact.
 *
 * <p>
 * The {@code table} argument is ignored — the expanded row index alone selects the bound (target
 * table, target row). {@code **} prefixes resolve per-row against the bound target's domain prefix.
 * </p>
 */
final class RelrecExpandedLookup implements JoinLookup
{

    /** Distinct related (target) tables, indexed by ordinal. */
    private final List<IDataTable> targetTables;

    /** Per expanded row: ordinal into {@link #targetTables} (>= 0; expansion is inner-join). */
    private final int[] targetOrdinal;

    /** Per expanded row: the bound target row index. */
    private final long[] targetRow;

    /** Resolved column index cache per target ordinal (caches -1 for absent columns). */
    private final ConcurrentHashMap<Short, ConcurrentHashMap<String, Integer>> colCache = new ConcurrentHashMap<>();

    RelrecExpandedLookup(List<IDataTable> targetTables, int[] targetOrdinal, long[] targetRow)
    {
        this.targetTables = targetTables;
        this.targetOrdinal = targetOrdinal;
        this.targetRow = targetRow;
    }


    @Override
    public @Nullable String lookup(IDataTable table, long row, String columnName)
    {
        if (columnName == null)
        {
            return null;
        }
        int r = (int) row;
        if (r < 0 || r >= targetRow.length)
        {
            return null;
        }
        int ord = targetOrdinal[r];
        if (ord < 0 || ord >= targetTables.size())
        {
            return null;
        }
        IDataTable target = targetTables.get(ord);

        String resolvedCol = columnName;
        if (columnName.startsWith("**"))
        {
            resolvedCol = java.util.Objects.requireNonNullElse(OperationExecutor
                    .variableWildcardPrefix(target, OperationExecutor.domainPrefix(target)), "")
                    + columnName.substring(2);
        }

        ConcurrentHashMap<String, Integer> cache = colCache.computeIfAbsent((short) ord,
                _ -> new ConcurrentHashMap<>());
        IDataTable t = target;
        int colIdx = cache.computeIfAbsent(resolvedCol, c -> t.getMetaData().getColumnIndex(c));
        if (colIdx < 0)
        {
            return null;
        }
        // Blank resolves by the column's declared type — see ScalarSemantics.resolvedString.
        return ScalarSemantics.resolvedString(target, colIdx, targetRow[r]);
    }


    @Override
    public boolean hasColumn(IDataTable table, long row, String columnName)
    {
        if (columnName == null)
        {
            return false;
        }
        int r = (int) row;
        if (r < 0 || r >= targetRow.length)
        {
            return false;
        }
        int ord = targetOrdinal[r];
        if (ord < 0 || ord >= targetTables.size())
        {
            return false;
        }
        IDataTable target = targetTables.get(ord);
        String resolvedCol = columnName.startsWith("**")
                ? java.util.Objects.requireNonNullElse(OperationExecutor
                        .variableWildcardPrefix(target, OperationExecutor.domainPrefix(target)), "")
                        + columnName.substring(2)
                : columnName;
        return target.getMetaData().getColumnIndex(resolvedCol) >= 0;
    }


    @Override
    public String getDatasetName()
    {
        return "RELREC";
    }
}
