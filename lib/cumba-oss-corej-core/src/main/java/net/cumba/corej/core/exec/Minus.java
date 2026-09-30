package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.Primitives;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;

/**
 * {@code minus(value, subtract=)} — the set difference {@code value \ subtract}, order-preserving:
 * the elements of the minuend that equal no element of the subtrahend, in the minuend's order
 * (runbook wave 4, {@code PLAN-list-functions} D-W4-4). Both operands are list expressions: a
 * {@code $}-binding holding a list, or a static list literal
 * ({@code minus(["AGEU", "SDESIGN"], subtract=$present)} reports which expected members are
 * absent). The result <em>is</em> the finding of every rule authoring it (the missing variables /
 * datasets), which is why the output deriver treats it as a value, not a bulk list.
 *
 * <p>
 * An element is removed iff it EQUALS a subtrahend element ({@code D81} / {@code D34 #5-2}): a
 * missing element keeps its identity on both sides and meets only the same missing, never a present
 * {@code "."}. ⭐ A <b>tuple</b> element (a row tuple from a {@code distinct} over several columns)
 * compares by its components, never by its {@code toString} rendering — the I6 residual of
 * {@code PLAN-no-null-list-elements}, fixed here (population 0 in the corpus).
 * </p>
 *
 * <p>
 * An empty minuend answers {@code []}; a scalar operand is its singleton. The function is
 * dataset-level (an aggregate): a per-row list operand has no one difference to answer and is
 * refused loudly, never read at row 0.
 * </p>
 */
public final class Minus
{

    /** The function name as authored. */
    public static final String NAME = "minus";

    /** The minuend parameter (positional, or the {@code value=} keyword). */
    public static final String VALUE_PARAMETER = "value";

    /** The subtrahend parameter. */
    public static final String SUBTRACT_PARAMETER = "subtract";

    private Minus()
    {
    }


    /**
     * The {@code EvalFunction} body: {@code args} holds the bound minuend and subtrahend.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            the bound {@code value} and {@code subtract} vectors
     * @return the broadcast difference
     */
    public static Vector evaluate(EvalRun run, List<Vector> args)
    {
        List<Object> minuend = listOperand(args.get(0), run.rowCount());
        if (minuend.isEmpty())
        {
            return ListFunctionSupport.broadcast(NAME, List.of());
        }
        Set<Object> subtrahend = new HashSet<>(listOperand(args.get(1), run.rowCount()));
        List<Object> difference = new ArrayList<>(minuend.size());
        for (Object element : minuend)
        {
            if (!subtrahend.contains(element))
            {
                difference.add(element);
            }
        }
        return ListFunctionSupport.broadcast(NAME, difference);
    }


    /**
     * A list operand as set elements — the <b>dataset-level</b> value of the operand, exactly the
     * hand-over form the retired MINUS operation read through {@code BindingValue.forOperation}: a
     * constant list contributes its elements, any other vector its first row's value (a list
     * binding computed per row over a dataset-level list, {@code upper($list)}, holds the same list
     * on every row), a scalar its singleton, an absent argument nothing. A genuinely per-row
     * binding is refused at load ({@code StageAChecker}, {@code OPERATION_READS_CURSOR_BINDING}) —
     * the operation's runtime guard, kept where it costs nothing.
     *
     * <p>
     * ⚠ Over a zero-row table a non-constant vector has no row 0 ({@code ComputedVector.value(0)}
     * throws {@code ArrayIndexOutOfBoundsException}), so it reads as no value — {@code []} — the
     * answer {@code BindingValue}'s dataset-level hand-over gives the same operand over the same
     * table (combined review W4 M2).
     * </p>
     */
    private static List<Object> listOperand(@Nullable Vector operand, int rowCount)
    {
        if (operand == null)
        {
            return List.of();
        }
        if (operand instanceof ConstVector constant)
        {
            return normalizeToList(constant.memberValue());
        }
        if (rowCount <= 0)
        {
            return List.of();
        }
        return normalizeToList(operand.value(0).resolved());
    }


    /**
     * Coerces a bound value to a list of set elements: an absent ({@code null}) value → {@code []};
     * any {@link Collection} → its elements; a scalar → its singleton. The elements are never
     * {@code null} — a bound list was born at a {@link ListValueGuard} site (register NNL §1). ⚠ An
     * array is not a list value and fails loud rather than fall through as one opaque scalar.
     */
    static List<Object> normalizeToList(@Nullable Object value)
    {
        if (value == null)
        {
            return List.of();
        }
        if (value.getClass().isArray())
        {
            throw new IllegalStateException("an array is not a list value (NNL §1)");
        }
        if (value instanceof Collection<?> c)
        {
            List<Object> out = new ArrayList<>(c.size());
            for (Object item : ListValueGuard.elements(c))
            {
                out.add(setElement(item));
            }
            return out;
        }
        return List.of(setElement(value));
    }


    /**
     * A set-operation element: a missing member as its {@link MissingValue} identity, a tuple as
     * the list of its components (each a set element itself — structural equality, the I6 fix),
     * anything else as its text.
     */
    static Object setElement(Object item)
    {
        MissingValue missing = Primitives.MemberSet.missingIdentityOfMember(item);
        if (missing != null)
        {
            return missing;
        }
        if (item instanceof List<?> tuple)
        {
            List<Object> components = new ArrayList<>(tuple.size());
            for (Object component : ListValueGuard.elements(tuple))
            {
                components.add(setElement(component));
            }
            return List.copyOf(components);
        }
        return item.toString();
    }

}
