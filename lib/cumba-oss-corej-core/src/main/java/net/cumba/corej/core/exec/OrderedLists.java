package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import net.cumba.corej.core.expr.RuleDefinitionException;
import net.cumba.corej.core.expr.eval.ComputedVector;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.datatable.values.DataValueType;
import org.jspecify.annotations.Nullable;

/**
 * Runbook W1b RO ({@code PLAN-engine-gap-row-ordinal} §5 R2 / R4, §6.2 R6; coordinator D11) — the
 * kernels of the ordered-list registry functions, over vectors so a per-row list
 * ({@code sequence}'s output) and a dataset-level list are read alike: {@code is_prefix_of(a, b)} /
 * {@code is_not_prefix_of(a, b)} ({@code a}'s elements equal {@code b}'s first {@code |a|} elements
 * — element identity is the set operation's, {@link Minus#setElement}: text exact, a missing by its
 * marker), {@code is_increasing(list)} (every element strictly greater than its predecessor —
 * numerically iff every element is a number, else as text; an empty or one-element list is
 * increasing; a list holding a missing element answers {@code true} — vacuously, as the empty list
 * does: the owner ruled a missing element <i>"answers missing, so no finding"</i>
 * ({@code PLAN-engine-gap-row-ordinal} §6.2), the two-valued BOOLEAN kind has no missing, and the
 * authored {@code not is_increasing(…)} must then give no finding — coordinator D22, runbook W1b
 * P5a) and {@code collapse_runs(list)} (adjacent duplicates collapsed, {@code [S, T, T, F, T]} to
 * {@code [S, T, F, T]}).
 *
 * <p>
 * A scalar where a list is read is the rule's ERROR ({@link RuleDefinitionException}), never a
 * silent singleton; a missing list ({@code null} / a missing row value) answers {@code false} for
 * the predicates and the empty list for the transform.
 * </p>
 */
public final class OrderedLists
{

    private OrderedLists()
    {
    }


    /**
     * {@code is_prefix_of(a, b)} per row, negated for {@code is_not_prefix_of}.
     *
     * @param a
     *            the candidate prefix
     * @param b
     *            the list it is tested against
     * @param rowCount
     *            the run's row count
     * @param negate
     *            {@code true} for {@code is_not_prefix_of}
     * @return the rows on which the verdict holds
     */
    public static BitSet isPrefixOf(Vector a, Vector b, int rowCount, boolean negate)
    {
        BitSet out = new BitSet(rowCount);
        for (int row = 0; row < rowCount; row++)
        {
            List<Object> prefix = listAt("is_prefix_of", a, row);
            List<Object> whole = listAt("is_prefix_of", b, row);
            if (prefix == null || whole == null)
            {
                continue; // a missing list: the predicate is false, as contains_any answers
            }
            boolean verdict = prefix.size() <= whole.size()
                    && prefix.equals(whole.subList(0, prefix.size()));
            if (verdict != negate)
            {
                out.set(row);
            }
        }
        return out;
    }


    /**
     * {@code is_increasing(list)} per row: strict, numeric iff every element is numeric.
     *
     * @param list
     *            the list
     * @param rowCount
     *            the run's row count
     * @return the rows on which the list is strictly increasing
     */
    public static BitSet isIncreasing(Vector list, int rowCount)
    {
        BitSet out = new BitSet(rowCount);
        for (int row = 0; row < rowCount; row++)
        {
            List<Object> items = listAt("is_increasing", list, row);
            if (items != null && isIncreasing(items))
            {
                out.set(row);
            }
        }
        return out;
    }


    /**
     * Whether {@code items} is strictly increasing: numerically when every element is a number
     * ({@link CellOrder#isNumeric}), else as text. A missing element answers {@code true} (D22: the
     * ruled "missing, so no finding" of a two-valued predicate — the negated authoring
     * {@code not is_increasing(…)} then fires on no such list).
     *
     * @param items
     *            the elements
     * @return the verdict
     */
    public static boolean isIncreasing(List<?> items)
    {
        boolean numeric = true;
        for (Object item : items)
        {
            if (CellOrder.isMissing(item))
            {
                return true;
            }
            numeric &= CellOrder.isNumeric(item);
        }
        for (int i = 1; i < items.size(); i++)
        {
            Object previous = items.get(i - 1);
            Object current = items.get(i);
            int c = numeric ? CellOrder.compareNumeric(previous, current)
                    : CellOrder.compareText(previous, current);
            if (c >= 0)
            {
                return false;
            }
        }
        return true;
    }


    /**
     * {@code collapse_runs(list)}: the list with adjacent duplicates collapsed, per row — one
     * constant for a dataset-level list.
     *
     * @param list
     *            the list
     * @param rowCount
     *            the run's row count
     * @return the collapsed list vector
     */
    public static Vector collapseRuns(Vector list, int rowCount)
    {
        if (list instanceof ConstVector constant)
        {
            return ConstVector.of(collapse(normalize("collapse_runs", constant.memberValue())),
                    () -> "collapse_runs");
        }
        return new ComputedVector(rowCount, DataValueType.STRING,
                row -> collapse(listAt("collapse_runs", list, row)));
    }


    /**
     * The list with adjacent duplicates collapsed; the empty list for a missing one.
     *
     * @param items
     *            the elements, or {@code null}
     * @return the collapsed list
     */
    public static List<Object> collapse(@Nullable List<Object> items)
    {
        if (items == null || items.isEmpty())
        {
            return List.of();
        }
        List<Object> out = new ArrayList<>(items.size());
        Object previous = null;
        boolean first = true;
        for (Object item : items)
        {
            if (first || !item.equals(previous))
            {
                out.add(item);
            }
            previous = item;
            first = false;
        }
        return List.copyOf(out);
    }


    /**
     * The list a vector holds on {@code row} as set elements, or {@code null} for a missing one.
     */
    private static @Nullable List<Object> listAt(String function, Vector vector, int row)
    {
        if (vector.isMissing(row))
        {
            return null;
        }
        return normalize(function, vector.value(row).resolved());
    }


    private static @Nullable List<Object> normalize(String function, @Nullable Object value)
    {
        if (value == null)
        {
            return null;
        }
        if (!(value instanceof Collection<?> items))
        {
            throw new RuleDefinitionException(function + " takes a list, not the value " + value
                    + " — read a list binding, sequence(...), distinct(...) or values([...])");
        }
        List<Object> out = new ArrayList<>(items.size());
        for (Object item : ListValueGuard.elements(items))
        {
            out.add(Minus.setElement(item));
        }
        return out;
    }
}
