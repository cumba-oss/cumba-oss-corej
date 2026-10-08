package net.cumba.corej.core.expr.eval;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.cumba.corej.core.exec.ListValueGuard;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;

/**
 * The element-wise <b>lift</b> of a scalar value function over a list at its parameter 0
 * ({@code PLAN-elementwise-list-functions} §3.5 / §3.6, owner Q2–Q6, Q10, Q15; runbook W1a E4). A
 * descriptor flagged {@link FunctionDescriptor#elementwise()} carries its scalar <em>kernel</em>
 * wrapped in a {@link Lifted}, so every caller of {@link FunctionDescriptor#fn()} — the compiler, a
 * direct registry call — sees one function that answers a scalar as the kernel does and a list
 * element by element. The shape is decided <b>on the vector, at evaluation</b> (§3.5: the compiled
 * plan is memoised structurally across rules, so a rule's binding may not decide at compile time),
 * in four cases:
 *
 * <ol>
 * <li><b>scalar</b> — a {@link ColumnVector}, a scalar {@link ConstVector}, a per-row vector no row
 * of which holds a list: the unchanged kernel call.</li>
 * <li><b>{@link ListVector}</b> ({@code values([X, Y])} and every lift over it) — K kernel calls
 * over the full row range, element vector <i>k</i> in slot 0, every other argument the same vector
 * for every <i>k</i>; a {@link ListVector} of the K results (Q10, no per-row allocation).</li>
 * <li><b>dataset-level list</b> — a {@link ConstVector} holding a {@link Collection} while every
 * other argument is broadcast: one kernel call over the elements, answered as one
 * {@link ConstVector} (Q15: computed once, the constant hand-over form a {@code {}} binding
 * promises, correct on a zero-row table).</li>
 * <li><b>per-row list</b> — a {@link ComputedVector} some row of which holds a {@link Collection}
 * ({@code split_by}, {@code colref(list)}, a {@code $} binding of one): every row's elements are
 * addressed as one range by an {@code int[]} offset array, the other arguments row-expanded, the
 * kernel called <b>once</b> over the range, the results regrouped per row (Q10). A row holding a
 * scalar in such a vector is a one-element group answered as the kernel answers it.</li>
 * </ol>
 *
 * <p>
 * <b>Elements.</b> One adapter ({@link #element(Object, DataValueType)}) turns a list element into
 * the {@link TypedValue} the kernel reads — the representations a list carries today: a
 * {@link String} / {@link Number} (a literal list, {@code split_by}, the library and define lists),
 * a {@link MissingValue} or {@link Primitives.MissingMember} ({@code tuple}, {@code distinct}), an
 * {@link IDataValue} cell ({@code colref(list)}, {@code values}). A missing element reaches the
 * kernel as a missing with its identity and comes back <b>as the original element</b> when the
 * kernel answers a missing (Q5: the function's own rule per element — for the string functions
 * identity, D36 / D85c); a present result is carried as its payload (the kernel's text) or its
 * typed cell. ⚠ An element that is itself a list keeps <b>today's rendering</b>
 * ({@code item.toString()} is what the kernel folds) until runbook W4's E7a makes it the rule ERROR
 * Q7 rules — a lift never recurses. A {@code null} element is the {@link ListValueGuard}'s error,
 * as it was in the fold it replaces (register {@code NNL §1}).
 * </p>
 *
 * <p>
 * <b>Purity.</b> A flagged kernel reads {@code run.rowCount()} and its argument vectors only — the
 * lifted cases hand it a run over the element range with the caller's context, never a row of the
 * evaluated table ({@code ElementwiseLiftRosterDriftTest} pins it).
 * </p>
 */
final class ElementwiseLift
{

    private ElementwiseLift()
    {
    }


    /**
     * The lifted function of {@code kernel} — idempotent: a lifted function is returned as it is.
     *
     * @param name
     *            the function name, for messages
     * @param kernel
     *            the scalar implementation
     * @return the function {@link FunctionDescriptor#fn()} publishes for a flagged descriptor
     */
    static EvalFunction lift(String name, EvalFunction kernel)
    {
        return kernel instanceof Lifted ? kernel : new Lifted(name, kernel);
    }


    /** Whether {@code fn} is a lifted function. */
    static boolean isLifted(@Nullable EvalFunction fn)
    {
        return fn instanceof Lifted;
    }


    /**
     * The scalar kernel under a lifted function, or {@code fn} itself — what the compiler calls
     * when the argument at parameter 0 is <b>statically</b> scalar (a column, a scalar-typed call,
     * a literal): no shape to decide, so the scan of case 4 is spared on the hot path.
     */
    static EvalFunction kernelOf(EvalFunction fn)
    {
        return fn instanceof Lifted lifted ? lifted.kernel() : fn;
    }

    /**
     * The lifted function: {@link #kernel()} applied per element of a list at parameter 0 (the four
     * cases of the class javadoc), the kernel itself otherwise.
     *
     * @param name
     *            the function name, for messages
     * @param kernel
     *            the scalar implementation
     */
    record Lifted(String name, EvalFunction kernel) implements EvalFunction
    {

        @Override
        public Object apply(EvalRun run, List<Vector> args)
        {
            return apply(run, args, Map.of());
        }


        @Override
        public Object apply(EvalRun run, List<Vector> args, Map<String, Expr> kwargs)
        {
            Vector x = args.isEmpty() ? null : args.get(0);
            if (x instanceof ListVector list)
            {
                return overElementVectors(run, list, args, kwargs);
            }
            if (x instanceof ConstVector constant
                    && constant.value() instanceof Collection<?> items)
            {
                return othersBroadcast(args) ? overConstantList(run, constant, items, args, kwargs)
                        : overRows(run, constant, args, kwargs);
            }
            if (x instanceof ComputedVector)
            {
                return overRows(run, x, args, kwargs);
            }
            return kernel.apply(run, args, kwargs);
        }


        /** Case 2: K kernel calls, one per element vector, in the list's input order. */
        private ListVector overElementVectors(EvalRun run, ListVector list, List<Vector> args,
                Map<String, Expr> kwargs)
        {
            List<Vector> out = new ArrayList<>(list.size());
            for (Vector element : list.elements())
            {
                out.add(result(kernel.apply(run, withFirst(args, element), kwargs)));
            }
            return new ListVector(run.rowCount(), out);
        }


        /** Case 3: one kernel call over the K elements of a broadcast list; one constant. */
        private ConstVector overConstantList(EvalRun run, ConstVector constant, Collection<?> items,
                List<Vector> args, Map<String, Expr> kwargs)
        {
            ListValueGuard.requireNoNullElement(items, () -> "the list argument of " + name);
            List<Object> elements = new ArrayList<>(items.size());
            for (Object item : items)
            {
                elements.add(item);
            }
            int k = elements.size();
            DataValueType type = constant.declaredType();
            Vector view = ComputedVector.carried(k, type, i -> element(elements.get(i), type));
            Vector folded = result(
                    kernel.apply(new EvalRun(run.ctx(), 0, k), withFirst(args, view), kwargs));
            List<Object> out = new ArrayList<>(k);
            for (int i = 0; i < k; i++)
            {
                out.add(result(folded.value(i), elements.get(i)));
            }
            return ConstVector.of(out, () -> name);
        }


        /**
         * Case 4 (or case 1 when no row holds a list): the rows' elements as one range, the kernel
         * called once, the results regrouped per row.
         */
        private Vector overRows(EvalRun run, Vector x, List<Vector> args, Map<String, Expr> kwargs)
        {
            int rows = run.rowCount();
            Flat flat = Flat.of(x, rows, name);
            if (flat == null)
            {
                return result(kernel.apply(run, args, kwargs));
            }
            DataValueType type = x.declaredType();
            Vector view = ComputedVector.carried(flat.total(), type, i ->
            {
                int row = flat.rowOf(i);
                return flat.listRows.get(row) ? element(flat.item(x, row, i), type) : x.value(row);
            });
            List<Vector> slot = new ArrayList<>(args.size());
            slot.add(view);
            for (int a = 1; a < args.size(); a++)
            {
                Vector other = args.get(a);
                slot.add(other == null || other instanceof ConstVector ? other
                        : ComputedVector.remapped(other, flat.total(), flat::rowOf));
            }
            Vector folded = result(
                    kernel.apply(new EvalRun(run.ctx(), 0, flat.total()), slot, kwargs));
            DataValueType resultType = folded.declaredType();
            return ComputedVector.carried(rows, resultType, row ->
            {
                int from = flat.offsets[row];
                if (!flat.listRows.get(row))
                {
                    // the scalar row: the kernel's own carrier, exactly as the scalar call answers
                    return folded.value(from);
                }
                int to = flat.offsets[row + 1];
                List<Object> out = new ArrayList<>(to - from);
                int i = from;
                for (Object item : flat.items(x, row))
                {
                    out.add(result(folded.value(i++), Objects.requireNonNull(item)));
                }
                return TypedValue.resolved(resultType, out);
            });
        }
    }


    /**
     * The row layout of a per-row vector some row of which holds a list: {@code offsets[r]} is the
     * range index of row {@code r}'s first element, a scalar (or missing) row is one element wide.
     * Reads of consecutive range indices resolve their row in O(1) through {@link #cursor}; a jump
     * falls back to a binary search.
     */
    private static final class Flat
    {

        final int[] offsets;

        final BitSet listRows;

        private int cursor;

        private Flat(int[] offsets, BitSet listRows)
        {
            this.offsets = offsets;
            this.listRows = listRows;
        }


        /**
         * The layout of {@code x} over {@code rows} rows, or {@code null} when no row holds a list.
         */
        static @Nullable Flat of(Vector x, int rows, String name)
        {
            BitSet listRows = new BitSet(rows);
            int[] offsets = null;
            for (int row = 0; row < rows; row++)
            {
                Object value = x.value(row).resolved();
                if (value instanceof Collection<?> items)
                {
                    if (offsets == null)
                    {
                        // the first list row: every earlier row is one element wide
                        offsets = new int[rows + 1];
                        for (int r = 0; r <= row; r++)
                        {
                            offsets[r] = r;
                        }
                    }
                    ListValueGuard.requireNoNullElement(items,
                            () -> "the list argument of " + name);
                    listRows.set(row);
                    offsets[row + 1] = offsets[row] + items.size();
                }
                else if (offsets != null)
                {
                    offsets[row + 1] = offsets[row] + 1;
                }
            }
            return offsets == null ? null : new Flat(offsets, listRows);
        }


        int total()
        {
            return offsets[offsets.length - 1];
        }


        int rowOf(int i)
        {
            int row = cursor;
            if (i >= offsets[row] && i < offsets[row + 1])
            {
                return row;
            }
            if (row + 1 < offsets.length - 1 && i >= offsets[row + 1] && i < offsets[row + 2])
            {
                cursor = row + 1;
                return cursor;
            }
            int lo = 0;
            int hi = offsets.length - 2;
            while (lo < hi)
            {
                int mid = (lo + hi + 1) >>> 1;
                if (offsets[mid] <= i)
                {
                    lo = mid;
                }
                else
                {
                    hi = mid - 1;
                }
            }
            cursor = lo;
            return lo;
        }


        /** Row {@code row}'s list (a row {@link #listRows} marks). */
        Collection<?> items(Vector x, int row)
        {
            return (Collection<?>) Objects.requireNonNull(x.value(row).resolved());
        }


        /** The element at range index {@code i} of list row {@code row}. */
        Object item(Vector x, int row, int i)
        {
            Collection<?> items = items(x, row);
            int index = i - offsets[row];
            if (items instanceof List<?> list)
            {
                return Objects.requireNonNull(list.get(index));
            }
            int j = 0;
            for (Object item : items)
            {
                if (j++ == index)
                {
                    return Objects.requireNonNull(item);
                }
            }
            throw new IndexOutOfBoundsException(index);
        }
    }

    /** The element adapter: a list element as the {@link TypedValue} the kernel reads. */
    static TypedValue element(Object item, DataValueType declaredType)
    {
        if (item instanceof IDataValue cell)
        {
            return TypedValue.typedCell(declaredType, cell);
        }
        MissingValue missing = Primitives.MemberSet.missingIdentityOfMember(item);
        if (missing != null)
        {
            return TypedValue.typedCell(declaredType, DataValues.of(missing));
        }
        if (item instanceof Collection<?>)
        {
            // a nested list: today's rendering (E7a, runbook W4, makes it the Q7 rule ERROR)
            return TypedValue.resolved(declaredType, item.toString());
        }
        return TypedValue.resolved(declaredType, item);
    }


    /**
     * The kernel's answer for one element, as the list carries it: a missing answer to a missing
     * element is the element itself (identity kept); any other missing is the kernel's cell; a
     * present answer is its typed cell when the kernel produced one, else its payload.
     */
    private static Object result(TypedValue folded, Object original)
    {
        if (folded.missing() != null)
        {
            return Primitives.MemberSet.missingIdentityOfMember(original) != null ? original
                    : folded.cell();
        }
        IDataValue source = folded.sourceCell();
        return source != null ? source : Objects.requireNonNull(folded.resolved());
    }


    private static Vector result(Object kernelAnswer)
    {
        if (kernelAnswer instanceof Vector v)
        {
            return v;
        }
        throw new IllegalStateException(
                "a lifted kernel must answer a Vector, not " + kernelAnswer);
    }


    private static List<Vector> withFirst(List<Vector> args, Vector first)
    {
        List<Vector> slot = new ArrayList<>(args);
        slot.set(0, first);
        return slot;
    }


    private static boolean othersBroadcast(List<Vector> args)
    {
        for (int a = 1; a < args.size(); a++)
        {
            Vector other = args.get(a);
            if (other != null && !(other instanceof ConstVector))
            {
                return false;
            }
        }
        return true;
    }
}
