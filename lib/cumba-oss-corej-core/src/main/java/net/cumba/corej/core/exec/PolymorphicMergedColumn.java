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
 * diverge — {@code Primitives.equalsTypedAware} (D121 retired
 * {@code ScalarSemantics.equalsNumericAware}) branches on the <em>cell's</em> type rather than the
 * declared one, so a mismatch would silently flip {@code AESEQ == "3"} between textual and numeric
 * equality.
 * </p>
 * <p>
 * When the type is {@code STRING} the read path keeps the historical stringify-and-retype exactly,
 * which is also the disagreement fallback. Otherwise it forwards the parent's own
 * {@link IDataValue} unchanged (J2 Option A): one allocation per access instead of three, and — the
 * point of the change — the value never passes through
 * {@code DataValueSupport.getAsDoubleCleaned}'s 12-significant-digit rounding.
 * </p>
 * <p>
 * <b>Unsupplied values take the column's TYPE DEFAULT (D72/D72a-1/D75a):</b> a merged column
 * behaves in every respect like a primary column, so where no value was supplied — no parent
 * resolved for the primary row, no parent row matched on the join key, or the matched parent lacks
 * this column (D75a cases 1–3) — the cell is {@code ""} for a {@code STRING} column and
 * {@code MissingValue.MIS} for a numeric one (D34 #3/#4). ⛔ The fourth case is different: a matched
 * parent cell that is itself a genuine {@code MissingValue} (a Dataset-JSON / Parquet null, D46)
 * passes through <b>unchanged</b>, identity and all — D11/D12 make it a value distinct from
 * {@code ""}, and collapsing it into the default would be wrong invisibly (D80c). Until D72 all
 * four cases shared one {@code MISSING} sentinel.
 * </p>
 */
final class PolymorphicMergedColumn extends AbstractDataTableColumn
{

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


    /**
     * ⚑ <b>TARGET-INVARIANT(null-free-value-channel) — this method is the intended per-component
     * shape, and it is one of only two places in the stack that answer null-free
     * <em>unprompted</em>.</b>
     *
     * <p>
     * The declared return type is {@code @Nullable} because
     * {@link net.cumba.datatable.IDataTableColumn#getValue(long)} is {@code @Nullable} <b>by
     * contract</b> — the raw {@code getValue} family across the data-table layer may answer
     * {@code null}, and that is not a defect to be swept here. This override nevertheless
     * <b>never</b> answers {@code null}: all three of its arms end in a real value or a
     * {@link net.cumba.datatable.values.MissingValue}.
     * </p>
     * <ol>
     * <li>no parent value supplied ⇒ {@code DataValueSupport.defaultForType(declaredType)} —
     * {@code ""} for {@code STRING}, {@code MissingValue.MIS} otherwise;</li>
     * <li>a genuine missing ⇒ the parent's own {@code MissingValue}, passed through;</li>
     * <li>a populated cell ⇒ the parent's value (text form on {@code STRING}).</li>
     * </ol>
     * <p>
     * Arms 2 and 3 are null-free because {@link net.cumba.datatable.values.IDataValue#getValue()}
     * is itself contractually non-null (<i>"This is NOT allowed to be null. Null values must be
     * mapped to {@code MissingValue} …"</i>), and {@code getValueAsString()} answers a string in
     * every case.
     * </p>
     * <p>
     * ⭐ <b>Why the shape is here and not central.</b> The owner's standing preference is
     * <b>explicit per-component implementations, never a central choke point, overlay or manager
     * hook</b>. Making the raw {@code getValue} family null-free at <em>buffer</em> level was
     * additionally <b>rejected on measurement</b>: a buffer carries no declared type, so
     * {@code getValue(int)} cannot apply the type-dependent default at all. ⇒ Each component that
     * knows its type answers null-free itself, as this one does. The other such component is
     * {@code ReportTable.nullToMissing} in the datatable repository, noted there.
     * </p>
     * <p>
     * ⛔ Stated as the <b>target</b> invariant, not as a property of the whole channel: null is
     * still reachable elsewhere in the value path, which is what the invariant is being driven
     * towards eliminating. This method is a component that already satisfies it.
     * </p>
     */
    @Override
    public @Nullable Object getValue(long aRow)
    {
        IDataValue dv = readParentDataValue(aRow);
        if (dv == null)
        {
            // D72a-1 cases 1-3: no value was supplied, so the cell IS the column's type default.
            return DataValueSupport.defaultForType(declaredType).getValue();
        }
        if (dv.isMissingOrInvalid())
        {
            // D75a case 4: the parent supplied a genuine MissingValue — pass it through
            // unchanged (D11/D12 make it a value distinct from "").
            return dv.getValue();
        }
        // J8: a STRING column (character parent, or parents that disagree) keeps the historical
        // text form. Otherwise hand back the parent's own value object, so the carrier reads the
        // parent IDataValue directly -- identical text, no cleaning on the numeric path.
        return declaredType == DataValueType.STRING ? dv.getValueAsString() : dv.getValue();
    }


    @Override
    public IDataValue getDataValue(long aRow)
    {
        IDataValue dv = readParentDataValue(aRow);
        if (dv == null)
        {
            // ⭐ D72/D72a-1 (with D73/D80): a merged column behaves in EVERY respect like a
            // primary column, and the default is a property of the column's TYPE, applied
            // wherever a value is not supplied — no parent resolved, no parent row matched, or
            // the matched parent lacks the column (D75a cases 1-3). Char -> "", numeric ->
            // MissingValue.MIS (D34 #3/#4). WHY the value is missing does not change the value.
            return DataValueSupport.defaultForType(declaredType);
        }
        if (dv.isMissingOrInvalid())
        {
            // ⛔ D75a case 4 — THREE cases into one default, NOT four: here a value WAS supplied
            // and is a genuine MissingValue (a Dataset-JSON / Parquet null per D46), which
            // D11/D12 make a value distinct from "". It passes through unchanged, identity and
            // all — rewriting it to the type default would be wrong invisibly (D80c: both
            // readings render as "the merged column is blank").
            return dv;
        }
        // J8: STRING means a character parent or parents that disagree -- keep the historical
        // stringify-and-retype so that case is byte-identical to the pre-change engine. Otherwise
        // forward the parent's own IDataValue (J2 Option A): the cell keeps its real type, so
        // Primitives.equalsTypedAware takes its numeric branch on a real value instead of on
        // text that getAsDoubleCleaned has already rounded to 12 significant digits.
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
