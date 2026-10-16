package net.cumba.corej.core.expr.eval;

import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.values.DataValueSupport;
import net.cumba.datatable.values.DataValueType;
import org.jspecify.annotations.Nullable;

/**
 * Zero-copy {@link Vector} over a resolved {@link IDataTableColumn}. Reads go straight through
 * {@link IDataTableColumn#getDataValue(long)} — the datatable's own per-cell access, so there is no
 * extra boxing beyond what the datatable API already imposes (feasibility review Item 4: the public
 * datatable API exposes no primitive bulk accessor).
 *
 * <p>
 * The carrier keeps the cell verbatim and decodes its missing identity ({@link TypedValue#column});
 * the legacy blank contract — a blank cell resolves to {@code null}, with the {@code ""} of a
 * populated character cell staying a value — is now {@link TypedValue#resolved()}'s cell-sourced
 * rule, byte-identical to the retired {@code ScalarSemantics.resolvedString} route this class used
 * to take.
 * </p>
 *
 * @param name
 *            the resolved column name this vector was built for, or {@code null} when the read is
 *            not an authored column reference (the {@code value()} cursor of a per-variable rule).
 *            {@link ColumnTypeGate} gates only <em>named</em> column vectors (§10 F9), and uses the
 *            name in its mismatch message
 * @param column
 *            the resolved primary-table column
 * @param declaredType
 *            the column's declared value type (from {@code DataTableMeta}), used for the
 *            compile-time operand-homogeneity check, the polymorphic date dispatch and the
 *            column-type mismatch gate
 */
public record ColumnVector(@Nullable String name, IDataTableColumn column,
        DataValueType declaredType) implements Vector
{

    @Override
    public TypedValue value(int row)
    {
        return TypedValue.column(declaredType, column.getDataValue(row));
    }


    /** {@inheritDoc} J7: a resolved primary column is gated under its authored name. */
    @Override
    public @Nullable String gatedName()
    {
        return name;
    }


    /**
     * {@inheritDoc} One early-exit scan of the resolved column under the datatable's own blank test
     * ({@link DataValueSupport#isEmptyOrMissing}, the cell predicate {@code ColumnContentScan}
     * folds into {@code ColumnContent.isBlank()} — this vector holds the column, not its table), so
     * the answer agrees with every other reader of that status; a 0-row column is vacuously blank,
     * as {@code ColumnContent.NO_ROWS} is. Not memoised: a record cannot carry a cache, and the
     * gate asks only once per compiled site, after the kinds already disagree — the path that is a
     * rule ERROR without the exemption.
     */
    @Override
    public boolean isBlank()
    {
        long rows = column.getRowCount();
        for (long r = 0; r < rows; r++)
        {
            if (!DataValueSupport.isEmptyOrMissing(column.getDataValue(r)))
            {
                return false; // a value: FILLED, decided
            }
        }
        return true;
    }

}
