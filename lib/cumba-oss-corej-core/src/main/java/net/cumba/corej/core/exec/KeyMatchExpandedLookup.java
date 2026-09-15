package net.cumba.corej.core.exec;

import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * {@link JoinLookup} for a row-EXPANDED key-based {@code Match_Datasets} join produced by
 * {@link KeyMatchRowExpander}. Keyed by the row index of the <em>expanded</em> evaluation table:
 * every expanded row binds to exactly one child row (or to none, for a {@code left}-join row with
 * no match), so a scalar {@link #lookup} is exact — it returns the matched child's value, never a
 * first-wins guess. Mirrors {@link RelrecExpandedLookup} for plain key joins.
 *
 * <p>
 * The {@code table} argument is ignored — the expanded row index alone selects the bound child row.
 * A {@code -1} binding (a {@code left}-only row with no matching child) resolves every column to
 * {@code null}, while {@link #hasColumn} still reports the child's schema (present-but-null,
 * matching Python's {@code left_only} columns set to {@code None}).
 * </p>
 */
final class KeyMatchExpandedLookup implements JoinLookup
{

    private final String datasetName;

    private final IDataTable child;

    /** Per expanded row: bound child row index, or {@code -1} for a left-join row with no match. */
    private final long[] boundRow;

    KeyMatchExpandedLookup(String datasetName, IDataTable child, long[] boundRow)
    {
        this.datasetName = datasetName;
        this.child = child;
        this.boundRow = boundRow;
    }


    /**
     * {@inheritDoc}
     *
     * <p>
     * ⚠⚠ This override is <b>required</b>, not optional, because this class overrides
     * {@link #declaredTypeOf}. Reporting a real {@code LONG}/{@code DOUBLE} type while leaving the
     * values on {@code JoinLookup}'s default — which wraps {@link #lookup}'s cleaned text as
     * {@code STRING} — publishes a vector whose meta and cells disagree. That is the same
     * meta-vs-cell divergence J8 was built to avoid for the merged path, and it would have
     * reintroduced the precision defect on this one: the value would still round-trip through
     * {@code getAsDoubleCleaned}'s 12 significant digits.
     * </p>
     */
    @Override
    public @Nullable IDataValue lookupValue(IDataTable table, long row, String columnName)
    {
        long cr = boundChildRow(row);
        if (cr < 0)
        {
            return null;
        }
        int colIdx = child.getMetaData().getColumnIndex(columnName);
        if (colIdx < 0)
        {
            return null;
        }
        IDataValue dv = child.getColumn(colIdx).getDataValue(cr);
        // Mirrors lookup()'s blank contract via resolvedString: a blank CHARACTER cell reads "",
        // a blank NUMERIC cell reads null.
        if (dv.isMissingOrInvalid())
        {
            return child.getMetaData().getColumn(colIdx)
                    .getType() == net.cumba.datatable.values.DataValueType.STRING
                            ? net.cumba.datatable.values.DataValueSupport.getAsDataValue("",
                                    net.cumba.datatable.values.DataValueType.STRING)
                            : null;
        }
        return dv;
    }


    /** The bound child row for {@code row}, or {@code -1} when there is none. */
    private long boundChildRow(long row)
    {
        int r = (int) row;
        if (r < 0 || r >= boundRow.length)
        {
            return -1;
        }
        return boundRow[r];
    }


    /** {@inheritDoc} Answered from the child table this lookup already holds. */
    @Override
    public net.cumba.datatable.values.DataValueType declaredTypeOf(String columnName)
    {
        int colIdx = child.getMetaData().getColumnIndex(columnName);
        return colIdx < 0 ? net.cumba.datatable.values.DataValueType.MISSING
                : child.getMetaData().getColumn(colIdx).getType();
    }


    @Override
    public @Nullable String lookup(IDataTable table, long row, String columnName)
    {
        if (columnName == null)
        {
            return null;
        }
        int r = (int) row;
        if (r < 0 || r >= boundRow.length)
        {
            return null;
        }
        long cr = boundRow[r];
        if (cr < 0)
        {
            return null; // left-only row: no child bound
        }
        int colIdx = child.getMetaData().getColumnIndex(columnName);
        if (colIdx < 0)
        {
            return null;
        }
        // Blank resolves by the column's declared type — see ScalarSemantics.resolvedString.
        return ScalarSemantics.resolvedString(child, colIdx, cr);
    }


    @Override
    public boolean hasColumn(IDataTable table, long row, String columnName)
    {
        return columnName != null && child.getMetaData().getColumnIndex(columnName) >= 0;
    }


    @Override
    public String getDatasetName()
    {
        return datasetName;
    }
}
