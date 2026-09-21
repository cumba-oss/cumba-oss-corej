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
 * On a {@code -1} binding (a {@code left}-only row with no matching child) the <b>text</b>
 * {@link #lookup} still resolves every column to {@code null} (matching Python's {@code left_only}
 * columns set to {@code None}), while the <b>typed</b> {@link #lookupValue} answers the column's
 * type default per D72/D72a-1 — {@code ""} char, {@code MissingValue.MIS} numeric — because a
 * merged column behaves like a primary column at the value layer; {@link #hasColumn} still reports
 * the child's schema.
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
    public IDataValue lookupValue(IDataTable table, long row, String columnName,
            boolean numericExpected)
    {
        int colIdx = child.getMetaData().getColumnIndex(columnName);
        if (colIdx < 0)
        {
            // ⭐⭐ §9c DOTTED PARITY (owner ruling, 2026-09-18): the column is absent from the child
            // ENTIRELY, so it takes the RULE's expected default exactly as an absent primary column
            // does (D72/D76/§1b): numeric -> MissingValue.MIS, otherwise the CONSTANT "" (D34 #3) —
            // a present empty string, never a computed MIS for a char read (the D96a order-arm
            // regression). Identical to DatasetLookup.lookupValue's arm, deliberately.
            //
            // ⚠ THIS SITE WAS MISSED TWICE: it is the THIRD production implementation of this arm,
            // and the plan's §9c names only two (DatasetLookup and RelrecExpandedLookup). A fix
            // applied to the two named ones would have left this one — the corpus path for plain
            // named Match_Dataset entries — answering the old unconditional "". ⛔ When this arm
            // changes again, change all three or none.
            //
            // ⚑ The previous rationale — "the expectation is UNREADABLE FROM HERE, lookupValue is
            // handed no EvaluationContext" — was correct about the mechanism and wrong about the
            // conclusion: §9c makes it a reason to change the channel, which the numericExpected
            // parameter is.
            return JoinLookup.absentJoinedColumnValue(numericExpected);
        }
        long cr = boundChildRow(row);
        if (cr < 0)
        {
            // ⭐ D72/D72a-1: a left-join row with no match yields the column's TYPE default —
            // char -> "", numeric -> MissingValue.MIS (D34 #3/#4) — exactly as a primary column
            // with no value would read.
            return net.cumba.datatable.values.DataValueSupport
                    .defaultForType(child.getMetaData().getColumn(colIdx).getType());
        }
        // ⛔ D75a case 4: a bound child cell that is a genuine MissingValue is a SUPPLIED value,
        // distinct from "" (D11/D12), and passes through unchanged. (Until D72 a missing char
        // cell was rewritten to "" and a missing numeric one to null.)
        return child.getColumn(colIdx).getDataValue(cr);
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
        // A blank resolves per ScalarSemantics.resolvedString — type-INDEPENDENT: a missing cell
        // reads null whatever the column type (owner ruling 2026-09-18), a stored "" reads "".
        return ScalarSemantics.resolvedString(child, colIdx, cr);
    }


    @Override
    public boolean hasColumn(IDataTable table, long row, String columnName)
    {
        return columnName != null && child.getMetaData().getColumnIndex(columnName) >= 0;
    }


    /**
     * {@inheritDoc}
     *
     * <p>
     * Answered from the bound-row array the expansion already built (D88 §3.3): an expanded row
     * bound to {@code -1} is a {@code left}-join row that found no matching child.
     * </p>
     */
    @Override
    public boolean matchedRow(IDataTable table, long row)
    {
        return boundChildRow(row) >= 0;
    }


    @Override
    public String getDatasetName()
    {
        return datasetName;
    }
}
