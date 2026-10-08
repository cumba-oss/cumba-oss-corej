package net.cumba.corej.core.expr.eval;

import java.util.ArrayList;
import java.util.List;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * A per-row <b>list</b> held as K column-wide element vectors — the fast form of
 * {@code values([X, Y])} ({@code PLAN-elementwise-list-functions} §3.1 / §3.7, owner Q10): K
 * column-wide reads, a row's {@link List} materialised <b>lazily</b>, only for a consumer that asks
 * for the row ({@code RuleRunner.renderCollection}, a list-LHS membership, {@code count}) and
 * memoised for the rest of the evaluation, as a {@link ComputedVector} memoises its rows.
 *
 * <p>
 * The row carrier is exactly the one {@code colref(list)} produces today
 * ({@code DynamicColumnRead}): a {@link TypedValue#resolved} payload holding the K cells as
 * {@link IDataValue}s in input order — a missing cell stays at its position as its own missing
 * ({@code DCF Q4}, D85c), an absent column's element vector contributes its type default — so the
 * member-set builders, {@code renderCollection} and {@code count} see what they see for
 * {@code colref(list)} (DCF §2.7 point 2: <i>"the list holds {@code IDataValue} cells, never
 * unwrapped Java values"</i>). Declared {@link DataValueType#STRING}, as {@code colref(list)} is.
 * </p>
 *
 * <p>
 * {@link #elements()} hands the K vectors to a consumer that reads them directly without
 * materialising any row — the lifter's K-kernel-calls case (E4) and {@code count}'s fast path (E9).
 * Not thread-safe: per evaluation, like every memoising vector.
 * </p>
 */
public final class ListVector implements Vector
{

    private final List<Vector> elements;

    private final int rowCount;

    /** The rendered rows, allocated on the first {@link #value} read (most readers never ask). */
    private @Nullable TypedValue @Nullable [] rowCache;

    /**
     * @param rowCount
     *            the number of rows the vector spans
     * @param elements
     *            the K element vectors, in the list's input order (copied)
     */
    public ListVector(int rowCount, List<Vector> elements)
    {
        this.elements = List.copyOf(elements);
        this.rowCount = rowCount;
    }


    /**
     * The K element vectors, in input order — read directly by the consumers that never need a
     * row's list.
     *
     * @return the element vectors (unmodifiable)
     */
    public List<Vector> elements()
    {
        return elements;
    }


    /**
     * The list's cardinality — K, the same on every row (a missing member is a member).
     *
     * @return the number of element vectors
     */
    public int size()
    {
        return elements.size();
    }


    @Override
    public TypedValue value(int row)
    {
        @Nullable
        TypedValue[] cache = rowCache;
        if (cache == null)
        {
            // W1a fold (R5 review B-LOW-2): the K element vectors are what count / values' readers
            // read; a row's rendered list is built only when a row is asked for
            cache = new TypedValue[rowCount];
            rowCache = cache;
        }
        TypedValue tv = cache[row];
        if (tv == null)
        {
            List<IDataValue> cells = new ArrayList<>(elements.size());
            for (Vector element : elements)
            {
                cells.add(memberCell(element.value(row)));
            }
            tv = TypedValue.resolved(DataValueType.STRING, List.copyOf(cells));
            cache[row] = tv;
        }
        return tv;
    }


    /**
     * One member's cell, in the representation {@code colref(list)} carries
     * ({@code DynamicColumnRead.Primary.read}): a missing cell as itself (identity kept — D85c), a
     * numeric cell typed, a present character value as its member-safe text ({@link DataValues#of}:
     * a member set keys it by its text, the report renders it unquoted).
     */
    private static IDataValue memberCell(TypedValue tv)
    {
        IDataValue cell = tv.cell();
        if (tv.missing() == null && cell.getType() == DataValueType.STRING)
        {
            return DataValues.of(cell.getValueAsString());
        }
        return cell;
    }


    @Override
    public DataValueType declaredType()
    {
        return DataValueType.STRING;
    }

}
