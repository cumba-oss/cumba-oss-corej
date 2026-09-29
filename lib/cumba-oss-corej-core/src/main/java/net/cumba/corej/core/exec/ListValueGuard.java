package net.cumba.corej.core.exec;

import java.util.Collection;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The one guard behind register {@code NNL §1}: an element of a <b>list value</b> is a real value
 * or a {@code MissingValue}, never {@code null}.
 *
 * <p>
 * ⭐ <b>Owner, 2026-09-28:</b> <i>"We only have MissingValue's or empty strings to represent absence
 * of data."</i> A source entry with no member (a nameless {@code ItemGroupDef}, a
 * {@code CodeListItem} without a {@code CodedValue}) is <b>skipped by its producer</b>; a
 * {@code null} element that still arrives is a producer defect, and this guard throws <b>where the
 * list is born</b>, naming the producer, so the rule ERRORs with that message
 * ({@code LibraryValidator.executeRule} turns the exception into a synthetic ERROR result) instead
 * of some consumer quietly folding the element to {@code ""} or dropping it.
 * </p>
 *
 * <p>
 * ⭐ <b>Two birth sites, never per row</b> ({@code PLAN-no-null-list-elements} §3):
 * {@link OperationExecutor#executeOne} — every operation result, once per operation per (rule ×
 * dataset), memoised by {@link LazyValue} — and
 * {@link net.cumba.corej.core.expr.eval.ConstVector#of} for a {@code Collection} value — literal
 * list bindings, {@code variableVector} over an operation result, the list accessors, the injected
 * {@code library_variable_*_values}, compile-time constant folds — once per construction. Per-row
 * list producers ({@code split_by}, {@code tuple}, the list form of {@code upper}/{@code lower},
 * the VLM lists) are null-free by construction — pinned by {@code PerRowListProducersNullFreeTest},
 * not by this guard — and are deliberately <b>not</b> guarded per row: a {@code GroupedResult}
 * becomes a per-row {@code ComputedVector}, and rescanning a ~50-name list on every row is the cost
 * §3 rejected.
 * </p>
 *
 * <p>
 * The scan covers a top-level {@link Collection}, <b>one</b> nesting level (the
 * {@code distinct([A, B])} reference tuples) and every {@code Collection} value of a
 * {@link GroupedResult}. A scalar {@code null} is <b>not</b> this guard's concern — that is the
 * untyped scalar channel {@code NF §1} still lists as {@code target}. An array is not a list value
 * and is not scanned: no producer emits one, and the consumers' dead {@code Object[]} arms were
 * deleted rather than guarded ({@code PLAN-no-null-list-elements} review round 1, LOW-2).
 * </p>
 *
 * <p>
 * ⚠ The scan is a loop, never {@code contains(null)}: {@code List.of(…).contains(null)} throws
 * {@code NullPointerException}, and the immutable lists most producers build are exactly the ones
 * that are already null-free.
 * </p>
 */
public final class ListValueGuard
{

    /** {@code outerIndex} of a top-level scan: no enclosing element. */
    private static final int TOP_LEVEL = -1;

    private ListValueGuard()
    {
    }


    /**
     * Rejects a list value that holds a {@code null} element.
     *
     * @param value
     *            the value born at the call site — anything; only a {@link Collection}, its nested
     *            {@link Collection} elements and a {@link GroupedResult}'s {@link Collection}
     *            values are scanned
     * @param producer
     *            names the producer for the message — evaluated only on a hit
     * @throws IllegalStateException
     *             {@code "<producer> produced a list with a null element at index <i> — nothing is
     *             ever null (register NNL §1)"}; for a nested list,
     *             {@code "… at index <i> of the nested list at index <o> …"}
     */
    public static void requireNoNullElement(@Nullable Object value, Supplier<String> producer)
    {
        if (value instanceof Collection<?> col)
        {
            requireNoNullElement(col, producer, TOP_LEVEL);
        }
        else if (value instanceof GroupedResult grouped)
        {
            for (Object v : grouped.results().values())
            {
                if (v instanceof Collection<?> col)
                {
                    requireNoNullElement(col, producer, TOP_LEVEL);
                }
            }
        }
    }


    /**
     * The elements of a list value that passed this guard, typed non-null for the consumer's loop:
     * NullAway reads an element of a {@code Collection<?>} as {@code @Nullable}, and the guard's
     * post-condition is exactly that no element is. One unchecked cast, no rescan — the guard ran
     * at the birth site; a consumer never re-checks per row.
     *
     * @param items
     *            a list value that passed {@link #requireNoNullElement} at its birth site (or was
     *            built null-free per row)
     * @return the same elements, typed non-null
     */
    @SuppressWarnings("unchecked")
    public static Iterable<Object> elements(Iterable<?> items)
    {
        return (Iterable<Object>) items;
    }


    /**
     * Scans {@code col}; {@code outerIndex} is {@link #TOP_LEVEL} for a top-level list (which then
     * descends one level) and the index of the enclosing element for a nested one (which does not).
     *
     * <p>
     * ⭐ A nested hit names <b>both</b> positions — <i>"at index 0 of the nested list at index
     * 1"</i> — so a {@code null} component of a {@code distinct([A, B])} tuple is located by its
     * tuple and its component (review round 1, L-1: the message used to name the inner index only,
     * which cannot tell the tuple apart).
     * </p>
     */
    private static void requireNoNullElement(Collection<?> col, Supplier<String> producer,
            int outerIndex)
    {
        int i = 0;
        for (Object element : col)
        {
            if (element == null)
            {
                String where = outerIndex == TOP_LEVEL ? "index " + i
                        : "index " + i + " of the nested list at index " + outerIndex;
                throw new IllegalStateException(
                        producer.get() + " produced a list with a null element at " + where
                                + " — nothing is ever null (register NNL §1)");
            }
            if (outerIndex == TOP_LEVEL && element instanceof Collection<?> nested)
            {
                requireNoNullElement(nested, producer, i);
            }
            i++;
        }
    }

}
