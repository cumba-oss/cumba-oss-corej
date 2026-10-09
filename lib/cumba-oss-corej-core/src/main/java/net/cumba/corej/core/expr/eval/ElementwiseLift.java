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
 * identity, D36 / D85c); a present result is carried as its payload (the kernel's text), its typed
 * cell, or — a computed number — its canonical typed cell ({@link DataValues#number}, so every lift
 * case renders and keys a number alike, runbook W2 fold R5 B-MED-2). ⭐ An element that is itself a
 * list is the rule's ERROR (Q7, owner <i>"agree one level only"</i>; runbook W4 E7a,
 * {@link ListArgument#nested}) — a lift never recurses, and before E7a it folded the element's
 * rendering ({@code "[A, B]"}). A {@code null} element is the {@link ListValueGuard}'s error, as it
 * was in the fold it replaces (register {@code NNL §1}).
 * </p>
 *
 * <p>
 * <b>Predicates</b> ({@code PLAN-elementwise-boolean-predicates} §3.3, P2 / P6; runbook W1a EB1): a
 * {@link FunctionKind#BOOLEAN} kernel answers a {@link BitSet} over its run, so its lift keeps the
 * bits — the same four cases, each building the {@link BoolListVector} form of its shape (case 2
 * the K column sets, case 3 one broadcast set over the K elements, case 4 one set over the
 * flattened range with the row offsets); case 1 and a per-row vector no row of which holds a list
 * answer the kernel's bit set unchanged. A row holding a scalar or a missing inside a per-row list
 * vector is one element wide, which is exactly P6's singleton (its quantifier reads the kernel's
 * own answer on that value).
 * </p>
 *
 * <p>
 * <b>Other parameters</b> (Q4, owner <i>"agree to error"</i>; runbook W4 E6): every parameter but 0
 * is scalar per row. A list there — visible only at run time, since stage A refuses the typed one
 * at load — is the rule's ERROR ({@link ListArgument#atScalarParameter}), never a zip, a cartesian
 * product or the kernel's reading of the list (before E6 {@code substring}'s {@code start} read it
 * as a non-number and answered missing). The compiler hands the bare kernel only to a call whose
 * every argument is scalar by shape ({@code ExprCompiler.allStaticallyScalar} decides), so the
 * check runs exactly where an argument could hold a list.
 * </p>
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
        return lift(name, kernel, false);
    }


    /**
     * The lifted function of {@code kernel}, a VALUE kernel ({@code predicate} false, a
     * {@link Vector} per call) or a BOOLEAN one ({@code predicate} true, a {@link BitSet} per call,
     * lifted into a {@link BoolListVector}) — idempotent.
     *
     * @param name
     *            the function name, for messages
     * @param kernel
     *            the scalar implementation
     * @param predicate
     *            whether the kernel is a BOOLEAN predicate
     * @return the function {@link FunctionDescriptor#fn()} publishes for a flagged descriptor
     */
    static EvalFunction lift(String name, EvalFunction kernel, boolean predicate)
    {
        return kernel instanceof Lifted ? kernel : new Lifted(name, kernel, predicate);
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
     * @param predicate
     *            whether the kernel is a BOOLEAN predicate (a {@link BitSet} per call, lifted into
     *            a {@link BoolListVector}) rather than a VALUE function (a {@link Vector} per call)
     */
    record Lifted(String name, EvalFunction kernel, boolean predicate) implements EvalFunction
    {

        /** A VALUE kernel's lift. */
        Lifted(String name, EvalFunction kernel)
        {
            this(name, kernel, false);
        }


        @Override
        public Object apply(EvalRun run, List<Vector> args)
        {
            return apply(run, args, Map.of());
        }


        @Override
        public Object apply(EvalRun run, List<Vector> args, Map<String, Expr> kwargs)
        {
            requireScalarOthers(run, args);
            Vector x = args.isEmpty() ? null : args.get(0);
            if (x instanceof ListVector list)
            {
                return predicate ? bitsOverElementVectors(run, list, args, kwargs)
                        : overElementVectors(run, list, args, kwargs);
            }
            if (x instanceof ConstVector constant
                    && constant.value() instanceof Collection<?> items)
            {
                if (othersBroadcast(args))
                {
                    return predicate ? bitsOverConstantList(run, constant, items, args, kwargs)
                            : overConstantList(run, constant, items, args, kwargs);
                }
                return predicate ? bitsOverRows(run, constant, args, kwargs)
                        : overRows(run, constant, args, kwargs);
            }
            if (x instanceof ComputedVector)
            {
                return predicate ? bitsOverRows(run, x, args, kwargs)
                        : overRows(run, x, args, kwargs);
            }
            return kernel.apply(run, args, kwargs);
        }


        /**
         * Q4 (runbook W4 E6): every argument but parameter 0 holds one value per row — a list there
         * is the rule's ERROR, named after the parameter.
         */
        private void requireScalarOthers(EvalRun run, List<Vector> args)
        {
            for (int a = 1; a < args.size(); a++)
            {
                if (ListArgument.holdsList(args.get(a), run.rowCount()))
                {
                    throw new net.cumba.corej.core.expr.RuleDefinitionException(
                            ListArgument.atScalarParameter(name, parameterName(name, a),
                                    parameterName(name, 0), "a list"));
                }
            }
        }


        /** Case 2 for a predicate: K kernel calls, the K bit sets as the columns form. */
        private BoolListVector bitsOverElementVectors(EvalRun run, ListVector list,
                List<Vector> args, Map<String, Expr> kwargs)
        {
            List<BitSet> columns = new ArrayList<>(list.size());
            for (Vector element : list.elements())
            {
                columns.add(bits(kernel.apply(run, withFirst(args, element), kwargs)));
            }
            return BoolListVector.ofColumns(run.rowCount(), columns);
        }


        /** Case 3 for a predicate: one kernel call over the K elements, broadcast. */
        private BoolListVector bitsOverConstantList(EvalRun run, ConstVector constant,
                Collection<?> items, List<Vector> args, Map<String, Expr> kwargs)
        {
            ListValueGuard.requireNoNullElement(items, () -> "the list argument of " + name);
            List<Object> elements = new ArrayList<>(items);
            int k = elements.size();
            DataValueType type = constant.declaredType();
            Vector view = ComputedVector.carried(k, type,
                    i -> liftedElement(elements.get(i), type, name));
            BitSet folded = bits(
                    kernel.apply(new EvalRun(run.ctx(), 0, k), withFirst(args, view), kwargs));
            return BoolListVector.broadcast(run.rowCount(), k, folded);
        }


        /**
         * Case 4 for a predicate (or case 1 when no row holds a list): the rows' elements as one
         * range, the kernel called once, the bits kept with the row offsets.
         */
        private Object bitsOverRows(EvalRun run, Vector x, List<Vector> args,
                Map<String, Expr> kwargs)
        {
            int rows = run.rowCount();
            Flat flat = Flat.of(x, rows, name);
            if (flat == null)
            {
                return kernel.apply(run, args, kwargs);
            }
            BitSet folded = bits(kernel.apply(new EvalRun(run.ctx(), 0, flat.total()),
                    flattenedSlot(x, flat, args, name), kwargs));
            return BoolListVector.flattened(rows, flat.offsets, folded);
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
            Vector view = ComputedVector.carried(k, type,
                    i -> liftedElement(elements.get(i), type, name));
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
            Vector folded = result(kernel.apply(new EvalRun(run.ctx(), 0, flat.total()),
                    flattenedSlot(x, flat, args, name), kwargs));
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
     * The argument slots of the one kernel call over a flattened range (case 4): the element view
     * at parameter 0 — a list row's element, a scalar row's own value — and every other per-row
     * argument row-expanded through the layout (a broadcast constant as it is).
     */
    private static List<Vector> flattenedSlot(Vector x, Flat flat, List<Vector> args, String name)
    {
        DataValueType type = x.declaredType();
        Vector view = ComputedVector.carried(flat.total(), type, i ->
        {
            int row = flat.rowOf(i);
            return flat.listRows.get(row) ? liftedElement(flat.item(x, row, i), type, name)
                    : x.value(row);
        });
        List<Vector> slot = new ArrayList<>(args.size());
        slot.add(view);
        for (int a = 1; a < args.size(); a++)
        {
            Vector other = args.get(a);
            slot.add(other == null || other instanceof ConstVector ? other
                    : ComputedVector.remapped(other, flat.total(), flat::rowOf));
        }
        return slot;
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

    /**
     * The name of parameter {@code slot} of the lifted function {@code name}, for a message — the
     * registered descriptor's, else a positional label (a synthetic lift).
     */
    private static String parameterName(String name, int slot)
    {
        FunctionDescriptor d = FunctionRegistry.descriptor(name);
        return d != null && slot < d.parameters().size() ? d.parameters().get(slot).name()
                : slot == 0 ? "x" : "#" + (slot + 1);
    }


    /**
     * The lift's element adapter: {@link #element}, except that an element which is itself a list
     * is the rule's ERROR — a lift reaches one level only (Q7, runbook W4 E7a).
     */
    private static TypedValue liftedElement(Object item, DataValueType declaredType, String name)
    {
        // (an inline instanceof, no capturing lambda per element - W4 fold R5 P2-L3)
        if (item instanceof Collection<?>)
        {
            throw new net.cumba.corej.core.expr.RuleDefinitionException(
                    ListArgument.nested(name, parameterName(name, 0), "a list holding a list"));
        }
        return element(item, declaredType);
    }


    /**
     * The element adapter: a list element as the {@link TypedValue} the kernel reads (the lift —
     * through {@link #liftedElement} — the list-probe membership and {@code contains_any}).
     */
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
            // a nested list: its rendering — reached by the membership and contains_any readers
            // only; the lift refuses it first (liftedElement, Q7 / E7a)
            return TypedValue.resolved(declaredType, item.toString());
        }
        return TypedValue.resolved(declaredType, item);
    }


    /**
     * The kernel's answer for one element, as the list carries it: a missing answer to a missing
     * element is the element itself (identity kept); any other missing is the kernel's cell; a
     * present answer is its typed cell when the kernel produced one, a computed number its
     * canonical typed cell ({@link DataValues#number} — runbook W2 fold, R5 B-MED-2: a lifted
     * numeric list renders {@code [1, 2.5]} as a dataset-level number does, never {@code [1.0,
     * 2.5]}, and case 2's {@link ListVector} carries the same cell), any other answer its payload
     * (a string as its text).
     */
    private static Object result(TypedValue folded, Object original)
    {
        if (folded.missing() != null)
        {
            return Primitives.MemberSet.missingIdentityOfMember(original) != null ? original
                    : folded.cell();
        }
        IDataValue source = folded.sourceCell();
        if (source != null)
        {
            return source;
        }
        Object payload = Objects.requireNonNull(folded.resolved());
        return payload instanceof Number n ? DataValues.number(n) : payload;
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


    private static BitSet bits(Object kernelAnswer)
    {
        if (kernelAnswer instanceof BitSet b)
        {
            return b;
        }
        throw new IllegalStateException(
                "a lifted predicate kernel must answer a BitSet, not " + kernelAnswer);
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
