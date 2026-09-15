package net.cumba.corej.core.expr.eval;

import net.cumba.corej.core.exec.ScalarSemantics;
import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * Zero-copy {@link Vector} over a resolved {@link IDataTableColumn}. Reads go straight through
 * {@link IDataTableColumn#getDataValue(long)} — the same per-cell access the legacy engine uses, so
 * there is no extra boxing beyond what the datatable API already imposes (feasibility review Item
 * 4: the public datatable API exposes no primitive bulk accessor).
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
    public IDataValue dataValue(int row)
    {
        return column.getDataValue(row);
    }


    /**
     * Plain primary-table column resolution, by the column's declared type: a blank cell in a
     * CHARACTER column resolves to {@code ""} and a blank cell in any other column to {@code null}.
     *
     * <p>
     * ⚑ For a character column that means a source {@code null} (a {@code MissingValue} since the
     * Dataset-JSON / Parquet loaders stopped flattening one at ingestion) resolves exactly as an
     * empty string does — the engine cannot tell them apart, which is the point. It is <b>not</b>
     * the same as folding {@code ""} onto the missing branch: {@code Primitives.dateComparison}
     * short-circuits on a {@code null} target and does not on {@code ""}, so that fold would move
     * every {@code date_*} rule with a blank character operand. See
     * {@link ScalarSemantics#resolvedString}.
     * </p>
     */
    @Override
    public @Nullable Object resolvedObject(int row)
    {
        return ScalarSemantics.resolvedString(column, declaredType, row);
    }


    /** {@inheritDoc} J7: a resolved primary column is gated under its authored name. */
    @Override
    public @Nullable String gatedName()
    {
        return name;
    }


    /**
     * Step A of {@code PLAN-joined-column-typing}: for a <b>numeric</b> column hand the comparison
     * the cell's own {@link IDataValue}, so the operand never passes through
     * {@code getAsDoubleCleaned}'s 12-significant-digit rounding on its way to being text and back.
     * Character columns, and any missing cell, resolve exactly as {@link #resolvedObject(int)}
     * does.
     *
     * <p>
     * ⚑ A missing cell falls through on purpose: {@code equalsNumericAware} and
     * {@code comparisonTargetAsDouble} both treat a missing target as "no value", and
     * {@link ScalarSemantics#resolvedString} already encodes the blank contract (a blank character
     * cell reads {@code ""}, a blank numeric cell {@code null}). Routing missing cells through the
     * typed branch would duplicate that contract in a second place.
     * </p>
     */
    @Override
    public @Nullable Object comparisonOperand(int row)
    {
        if (declaredType == DataValueType.LONG || declaredType == DataValueType.DOUBLE)
        {
            IDataValue dv = column.getDataValue(row);
            if (!dv.isMissingOrInvalid())
            {
                return dv;
            }
        }
        return resolvedObject(row);
    }

}
