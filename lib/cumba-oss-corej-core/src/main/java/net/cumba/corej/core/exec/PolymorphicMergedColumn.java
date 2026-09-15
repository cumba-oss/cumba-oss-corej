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
 * <b>Typing (J8, {@code PLAN-joined-column-typing}).</b> The column reports the <em>parent column's
 * own declared type</em> when every parent that exposes the name agrees on it, and
 * {@link DataValueType#STRING} when they disagree. {@code ChildMatchPreMerger} computes that once
 * and passes it here <b>and</b> to the augmented meta, so the declared type and the cells can never
 * diverge — {@code ScalarSemantics.equalsNumericAware} branches on the <em>cell's</em> type rather
 * than the declared one, so a mismatch would silently flip {@code AESEQ == "3"} between textual and
 * numeric equality.
 * </p>
 * <p>
 * When the type is {@code STRING} the read path keeps the historical stringify-and-retype exactly,
 * which is also the disagreement fallback. Otherwise it forwards the parent's own
 * {@link IDataValue} unchanged (J2 Option A): one allocation per access instead of three, and — the
 * point of the change — the value never passes through
 * {@code DataValueSupport.getAsDoubleCleaned}'s 12-significant-digit rounding.
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

    /**
     * The J8 declared type this column reports, as computed by
     * {@code ChildMatchPreMerger.agreedParentType} and mirrored into the augmented meta.
     * {@code STRING} means either a genuinely character parent column or parents that disagree;
     * both take the historical stringifying read path.
     */
    private final DataValueType declaredType;

    PolymorphicMergedColumn(int aIndex, byte[] aPerRowParentIdx,
            IDataBufferNumeric aPerRowParentRow, @Nullable IDataTableColumn[] aPerParentColumn,
            DataValueType aDeclaredType)
    {
        super(aIndex);
        perRowParentIdx = aPerRowParentIdx;
        perRowParentRow = aPerRowParentRow;
        perParentColumn = aPerParentColumn;
        declaredType = aDeclaredType;
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
        if (dv == null || dv.isMissingOrInvalid())
        {
            return null;
        }
        // J8: a STRING column (character parent, or parents that disagree) keeps the historical
        // text form. Otherwise hand back the parent's own value object, so ScalarSemantics
        // .resolvedString's `raw instanceof String` fast path misses and its fallback reads the
        // parent IDataValue directly -- identical text, no cleaning on the numeric path.
        return declaredType == DataValueType.STRING ? dv.getValueAsString() : dv.getValue();
    }


    @Override
    public IDataValue getDataValue(long aRow)
    {
        IDataValue dv = readParentDataValue(aRow);
        if (dv == null || dv.isMissingOrInvalid())
        {
            return MISSING_VALUE;
        }
        // J8: STRING means a character parent or parents that disagree -- keep the historical
        // stringify-and-retype so that case is byte-identical to the pre-change engine. Otherwise
        // forward the parent's own IDataValue (J2 Option A): the cell keeps its real type, so
        // equalsNumericAware takes its numeric branch on a real value instead of on text that
        // getAsDoubleCleaned has already rounded to 12 significant digits.
        //
        // NB the E17 citation that used to justify the unconditional STRING coercion was a
        // misattribution: E17 is the numeric-IDVAR *join key* coercion (ChildMatchPreMerger
        // .wrapWithCoercedIdvarval), not the merged *value*. No ruling ever covered this.
        return declaredType == DataValueType.STRING
                ? DataValueSupport.getAsDataValue(dv.getValueAsString(), DataValueType.STRING)
                : dv;
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
