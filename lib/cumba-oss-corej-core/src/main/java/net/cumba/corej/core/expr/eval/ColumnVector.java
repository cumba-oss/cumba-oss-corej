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
        DataValueType declaredType, BlankMemo blankMemo) implements Vector
{

    public ColumnVector(@Nullable String name, IDataTableColumn column, DataValueType declaredType)
    {
        this(name, column, declaredType, new BlankMemo());
    }

    /**
     * The memoised answer of {@link #isBlank()} — a record cannot carry mutable state, so the one
     * mutable cell is a component created per vector (B5 review MED-1: every gate site of one
     * compiled vector asks once, and the column is walked once).
     */
    public static final class BlankMemo
    {

        private static final byte UNKNOWN = 0;

        private static final byte BLANK = 1;

        private static final byte FILLED = 2;

        private byte state = UNKNOWN;

        @Override
        public String toString()
        {
            return state == UNKNOWN ? "unscanned" : state == BLANK ? "blank" : "filled";
        }
    }

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
     * ({@link IDataTableColumn#isEmptyOrMissing(long)}, the allocation-free form of
     * {@link DataValueSupport#isEmptyOrMissing} — the cell predicate {@code ColumnContentScan}
     * folds into {@code ColumnContent.isBlank()}; this vector holds the column, not its table), so
     * the answer agrees with every other reader of that status; a 0-row column is vacuously blank,
     * as {@code ColumnContent.NO_ROWS} is. Memoised in {@link #blankMemo} (B5 review MED-1): the
     * gate asks once per gate site of a compiled vector, and a vector may meet several, so the
     * column is walked once however often it is asked.
     */
    @Override
    public boolean isBlank()
    {
        BlankMemo memo = blankMemo;
        if (memo.state == BlankMemo.UNKNOWN)
        {
            memo.state = scanBlank() ? BlankMemo.BLANK : BlankMemo.FILLED;
        }
        return memo.state == BlankMemo.BLANK;
    }


    private boolean scanBlank()
    {
        long rows = column.getRowCount();
        for (long r = 0; r < rows; r++)
        {
            if (!column.isEmptyOrMissing(r))
            {
                return false; // a value: FILLED, decided
            }
        }
        return true;
    }

}
