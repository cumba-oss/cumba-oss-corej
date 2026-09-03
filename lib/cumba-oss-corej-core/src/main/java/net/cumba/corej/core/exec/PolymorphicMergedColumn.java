package net.cumba.corej.core.exec;

import net.cumba.datatable.AbstractDataTableColumn;
import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.impl.databuffer.IDataBufferNumeric;
import net.cumba.datatable.values.DataValueSupport;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * Column view used by {@link ChildMatchPreMerger}'s synthetic augmented table. For each primary row
 * it dispatches to the matched parent row via two per-call buffers and returns the parent column's
 * value through that mapping — values resolve on the fly, no per-cell storage.
 * <p>
 * Polymorphic in the sense that different rows of this column may resolve through different parent
 * tables (CO and RELREC primaries reference variable parents per row). The {@link #perParentColumn}
 * array carries, per parent index, the corresponding column on that parent ({@code null} when the
 * parent does not expose the named column — E14).
 * </p>
 * <p>
 * Augmented cells are exposed as {@link DataValueType#STRING} regardless of the parent column's
 * native type, so rule operators that compare these cells against string literals see uniform
 * typing across joined-in columns. The augmented column meta therefore declares {@code STRING}, and
 * the read path stringifies the parent cell on every access.
 * </p>
 * <p>
 * {@link DataValueType#MISSING} is returned when:
 * </p>
 * <ul>
 * <li>no parent was resolved for the primary row,</li>
 * <li>no parent row matched on the join key,</li>
 * <li>the matched parent lacks this column, or</li>
 * <li>the parent cell itself is missing/invalid.</li>
 * </ul>
 */
final class PolymorphicMergedColumn extends AbstractDataTableColumn
{

    /** Shared MISSING sentinel returned when {@link #readParentDataValue} resolves to no value. */
    private static final IDataValue MISSING_VALUE = DataValueSupport.getAsDataValue(null,
            DataValueType.MISSING);

    private final byte[] perRowParentIdx;

    private final IDataBufferNumeric perRowParentRow;

    /**
     * Per-parent column array. Entry at parent index {@code p} is the column instance on
     * {@code parentTables[p]} that exposes this name, or {@code null} when that parent lacks the
     * column (E14).
     */
    private final @Nullable IDataTableColumn[] perParentColumn;

    PolymorphicMergedColumn(int aIndex, byte[] aPerRowParentIdx,
            IDataBufferNumeric aPerRowParentRow, @Nullable IDataTableColumn[] aPerParentColumn)
    {
        super(aIndex);
        perRowParentIdx = aPerRowParentIdx;
        perRowParentRow = aPerRowParentRow;
        perParentColumn = aPerParentColumn;
    }


    /**
     * Required because {@link IDataTableColumn#getRowCount()} is abstract and
     * {@code ColumnCachedDataTable.getRowCount()} delegates to {@code columns[0].getRowCount()} (it
     * does not read the row count from {@code DataTableMeta}).
     */
    @Override
    public long getRowCount()
    {
        return perRowParentIdx.length;
    }


    @Override
    public @Nullable Object getValue(long aRow)
    {
        IDataValue dv = readParentDataValue(aRow);
        // IDataTableColumn#getValue is @Nullable in this module's datatable, so a miss returns a
        // bare null (the pre-existing behaviour) rather than the MISSING sentinel.
        return dv == null || dv.isMissingOrInvalid() ? null : dv.getValueAsString();
    }


    @Override
    public IDataValue getDataValue(long aRow)
    {
        IDataValue dv = readParentDataValue(aRow);
        if (dv == null || dv.isMissingOrInvalid())
        {
            return MISSING_VALUE;
        }
        // String-coerce so the augmented column reports a uniform STRING type — rule operators
        // that compare augmented cells against String literals must see strings here even when
        // the parent column is numeric (LONG --SEQ etc., E17).
        return DataValueSupport.getAsDataValue(dv.getValueAsString(), DataValueType.STRING);
    }


    /**
     * Resolve the matched parent cell for {@code aRow}, or {@code null} if the dispatch indicates a
     * miss (parent index -1, parent row -1, or no column on the resolved parent — E14).
     */
    private @Nullable IDataValue readParentDataValue(long aRow)
    {
        int idx = (int) aRow;
        int pIdx = perRowParentIdx[idx];
        if (pIdx < 0)
        {
            return null;
        }
        long pRow = perRowParentRow.getValueAsLong(idx);
        if (pRow < 0)
        {
            return null;
        }
        @Nullable
        IDataTableColumn col = perParentColumn[pIdx];
        return col == null ? null : col.getDataValue(pRow);
    }

}
