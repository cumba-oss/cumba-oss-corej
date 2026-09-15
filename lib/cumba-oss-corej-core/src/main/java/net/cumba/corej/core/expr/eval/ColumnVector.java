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

}
