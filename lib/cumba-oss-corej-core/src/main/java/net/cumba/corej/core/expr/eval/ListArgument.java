package net.cumba.corej.core.expr.eval;

import java.util.Collection;
import java.util.Map;
import java.util.function.Supplier;
import net.cumba.corej.core.expr.RuleDefinitionException;
import org.jspecify.annotations.Nullable;

/**
 * ⭐ The one refusal of a <b>list where one value is read</b> (runbook W4 pack P2): the sentences
 * stage A files as {@code PARAMETER_TYPE} when the list is visible at load, and the run-time guard
 * that makes the same sentence the rule's ERROR ({@link RuleDefinitionException}, D35 "written
 * wrong") when only the run knows the value is a list — so the two channels can never disagree.
 * <ul>
 * <li><b>E6</b> ({@code PLAN-elementwise-list-functions} Q4, owner 2026-10-05 <i>"agree to
 * error"</i>): every parameter of a lifted ({@link FunctionDescriptor#elementwise()}) function but
 * parameter 0 is scalar per row — a list there is never a zip or a cartesian product
 * ({@link #atScalarParameter}; the guard runs in {@code ElementwiseLift.Lifted}).</li>
 * <li><b>E7a</b> (Q7, <i>"agree one level only"</i>): a list element that is itself a list at the
 * lifted parameter ({@link #nested}; the guard is {@code ElementwiseLift.element}).</li>
 * <li><b>E6b</b> ({@code PLAN-engine-gap-list-elementwise} §6.1 Q1): the needle of {@code contains}
 * / {@code does_not_contain} / {@code starts_with} / {@code ends_with} is one value
 * ({@link #needle}; the guard runs in {@code Primitives.substring}) — the hint names
 * {@code contains_any} where a list form exists, and says there is none for the affix tests.</li>
 * </ul>
 * <p>
 * The guard is {@link #requireScalar}: one {@code instanceof} per value read, so the hot path pays
 * nothing measurable; the per-row scan of a vector ({@link #holdsList}) is only reached where the
 * compiler could not prove the argument scalar by shape.
 * </p>
 */
public final class ListArgument
{

    /** The needle functions with their remedy (E6b, coordinator W4-D7). */
    private static final Map<String, String> NEEDLE_REMEDIES = Map.of("contains",
            "use contains_any(x, list)", "does_not_contain", "use not contains_any(x, list)",
            "starts_with", "starts_with has no list form", "ends_with",
            "ends_with has no list form");

    /** The needle's parameter (slot 1) of the four substring predicates. */
    public static final String NEEDLE_PARAMETER = "value";

    private ListArgument()
    {
    }


    /**
     * Whether {@code function} is one of the four substring predicates whose needle is one value.
     *
     * @param function
     *            the function name
     * @return {@code true} for {@code contains}, {@code does_not_contain}, {@code starts_with},
     *         {@code ends_with}
     */
    public static boolean isNeedleFunction(String function)
    {
        return NEEDLE_REMEDIES.containsKey(function);
    }


    /**
     * E6b: a list at the needle of a substring predicate.
     *
     * @param function
     *            one of the four ({@link #isNeedleFunction})
     * @param described
     *            what was found, e.g. {@code list<string>} or {@code a list}
     * @return the sentence
     */
    public static String needle(String function, String described)
    {
        return "argument '" + NEEDLE_PARAMETER + "' of '" + function + "' takes one value, not "
                + described + " - " + NEEDLE_REMEDIES.getOrDefault(function, "it has no list form");
    }


    /**
     * E6 (Q4): a list at a parameter of a lifted function other than parameter 0.
     *
     * @param function
     *            the lifted function
     * @param parameter
     *            the parameter holding the list
     * @param lifted
     *            the lifted parameter (parameter 0)
     * @param described
     *            what was found, e.g. {@code list<number>} or {@code a list}
     * @return the sentence
     */
    public static String atScalarParameter(String function, String parameter, String lifted,
            String described)
    {
        return "argument '" + parameter + "' of '" + function + "' takes one value per row, not "
                + described + " - a list is lifted only at '" + lifted
                + "' (parameter 0, element by element)";
    }


    /**
     * E7a (Q7): a list of lists at the lifted parameter.
     *
     * @param function
     *            the lifted function
     * @param parameter
     *            the lifted parameter
     * @param described
     *            what was found, e.g. {@code list<list<string>>} or {@code a list holding a list}
     * @return the sentence
     */
    public static String nested(String function, String parameter, String described)
    {
        return "argument '" + parameter + "' of '" + function + "' lifts over one list level only,"
                + " not " + described + " - read one component of the nested list";
    }


    /**
     * The run-time guard: {@code value} is one value, never a list.
     *
     * @param value
     *            the value read (a row's resolved payload, a constant's value)
     * @param message
     *            the refusal, evaluated only on a hit
     * @throws RuleDefinitionException
     *             when {@code value} is a {@link Collection} — the rule's ERROR
     */
    public static void requireScalar(@Nullable Object value, Supplier<String> message)
    {
        if (value instanceof Collection<?>)
        {
            throw new RuleDefinitionException(message.get());
        }
    }


    /**
     * Whether {@code v} carries a list in any of its first {@code rows} rows — a constant holding a
     * {@link Collection}, a {@link ListVector}, a {@link BoolListVector}, or a per-row vector some
     * row of which resolves to one. A {@link ColumnVector} never does (a cell is a scalar).
     *
     * @param v
     *            the argument vector, or {@code null} for an absent optional
     * @param rows
     *            the rows of the run
     * @return {@code true} when a list is carried
     */
    public static boolean holdsList(@Nullable Vector v, int rows)
    {
        if (v == null || v instanceof ColumnVector)
        {
            return false;
        }
        if (v instanceof ListVector || v instanceof BoolListVector)
        {
            return true;
        }
        if (v instanceof ConstVector constant)
        {
            return constant.value() instanceof Collection<?>;
        }
        for (int row = 0; row < rows; row++)
        {
            if (v.value(row).resolved() instanceof Collection<?>)
            {
                return true;
            }
        }
        return false;
    }
}
