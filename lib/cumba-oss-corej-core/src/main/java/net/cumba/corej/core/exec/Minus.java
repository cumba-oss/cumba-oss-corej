package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.cumba.corej.core.expr.RuleDefinitionException;
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
 * An empty minuend answers {@code []}. <b>Both operands are lists only</b>
 * ({@code PLAN-minus-lists-only}, owner #3 2026-10-01; runbook W4 M1 / M2 — {@code union}'s
 * contract): a scalar-typed operand is stage A's {@code PARAMETER_TYPE} at load (the descriptor
 * types both {@code list<unknown>}), and an operand that is a single value at run time is a
 * {@link RuleDefinitionException} — never its own singleton; an absent operand is {@code []}. The
 * function is dataset-level (an aggregate): a per-row list operand has no one difference to answer
 * and is refused loudly, never read at row 0.
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
        // both operands are read before the empty-minuend answer, so a single-value subtrahend is
        // refused whatever the data (W4 M2)
        List<Object> minuend = listOnly(args.get(0), run.rowCount(), NAME,
                "`" + VALUE_PARAMETER + "`", "set difference");
        List<Object> subtrahendList = listOnly(args.get(1), run.rowCount(), NAME,
                "`" + SUBTRACT_PARAMETER + "`", "set difference");
        if (minuend.isEmpty())
        {
            return ListFunctionSupport.broadcast(NAME, List.of());
        }
        Set<Object> subtrahend = new HashSet<>(subtrahendList);
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
     * The list-only operand reader of {@code minus} and {@code union} (runbook W4 M2 — one reader,
     * one behaviour): the <b>dataset-level</b> value of the operand ({@link #rawOperand}: a
     * constant list's elements, any other vector its first row's value — a list binding computed
     * per row over a dataset-level list, {@code upper($list)}, holds the same list on every row),
     * each element keyed by {@link #setElement}; an absent operand, or a zero-row non-constant one,
     * is {@code []}. A value that is not a {@link Collection} is a {@link RuleDefinitionException}
     * naming the operand — a set operation reads lists only, and the authored form of a single
     * value is its singleton list literal. A genuinely per-row binding is refused at load
     * ({@code StageAChecker}, {@code OPERATION_READS_CURSOR_BINDING}).
     *
     * <p>
     * ⚠ Over a zero-row table a non-constant vector has no row 0 ({@code ComputedVector.value(0)}
     * throws {@code ArrayIndexOutOfBoundsException}), so it reads as no value — {@code []} — the
     * answer {@code BindingValue}'s dataset-level hand-over gives the same operand over the same
     * table (combined review W4 M2). ⚠ An array is not a list value and fails loud (NNL §1). The
     * elements are never {@code null} — a bound list was born at a {@link ListValueGuard} site.
     * </p>
     *
     * @param operand
     *            the bound operand vector, or {@code null} when absent
     * @param rowCount
     *            the run's row count
     * @param function
     *            the function, for the message
     * @param operandLabel
     *            the operand as the message names it ({@code `value`}, {@code 2})
     * @param setOperation
     *            what the function computes, for the message ({@code set difference})
     * @return the operand's set elements
     * @throws RuleDefinitionException
     *             for a single value
     */
    static List<Object> listOnly(@Nullable Vector operand, int rowCount, String function,
            String operandLabel, String setOperation)
    {
        Object value = rawOperand(operand, rowCount);
        if (value == null)
        {
            return List.of();
        }
        if (value.getClass().isArray())
        {
            throw new IllegalStateException("an array is not a list value (NNL \u00a71)");
        }
        if (!(value instanceof Collection<?> c))
        {
            throw new RuleDefinitionException(function + "(\u2026) operand " + operandLabel
                    + " is not a list (" + value + "): a " + setOperation
                    + " reads lists only \u2014 a single value is written as its singleton list"
                    + " literal [\"" + value + "\"]");
        }
        List<Object> out = new ArrayList<>(c.size());
        for (Object item : ListValueGuard.elements(c))
        {
            out.add(setElement(item));
        }
        return out;
    }


    /**
     * The value a dataset-level list operand carries — a {@code ConstVector}'s member value,
     * otherwise row 0's resolved value; {@code null} for an absent operand or a zero-row
     * non-constant vector. Read by {@link #listOnly}, the reader {@link Union} shares.
     */
    static @Nullable Object rawOperand(@Nullable Vector operand, int rowCount)
    {
        if (operand == null)
        {
            return null;
        }
        if (operand instanceof ConstVector constant)
        {
            return constant.memberValue();
        }
        if (rowCount <= 0)
        {
            return null;
        }
        return operand.value(0).resolved();
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
