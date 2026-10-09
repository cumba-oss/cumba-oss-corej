package net.cumba.corej.core.expr.eval;

import java.util.Collection;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * ⭐ The one refusal of a <b>list where one value is read</b> (runbook W4 pack P2): the sentences
 * stage A files as {@code PARAMETER_TYPE} when the list is visible at load, and the run-time guard
 * that makes the same sentence the rule's ERROR ({@code RuleDefinitionException}, D35 "written
 * wrong") when only the run knows the value is a list — so the two channels can never disagree.
 * <ul>
 * <li><b>E6</b> ({@code PLAN-elementwise-list-functions} Q4, owner 2026-10-05 <i>"agree to
 * error"</i>): every parameter of a lifted ({@link FunctionDescriptor#elementwise()}) function but
 * parameter 0 is scalar per row — a list there is never a zip or a cartesian product
 * ({@link #atScalarParameter}; the guard runs in {@code ElementwiseLift.Lifted}).</li>
 * <li><b>E7a</b> (Q7, <i>"agree one level only"</i>): a list element that is itself a list at the
 * lifted parameter ({@link #nested}; the guard is {@code ElementwiseLift.liftedElement} — the
 * membership and {@code contains_any} readers share {@code ElementwiseLift.element}, which renders
 * a nested list instead).</li>
 * <li><b>E6b</b> ({@code PLAN-engine-gap-list-elementwise} §6.1 Q1): the needle of {@code contains}
 * / {@code does_not_contain} / {@code starts_with} / {@code ends_with} is one value
 * ({@link #needle}; the guard runs in {@code Primitives.substring}) — the hint names
 * {@code contains_any} where a list form exists, and says there is none for the affix tests.</li>
 * <li><b>L2-L5a</b> (T2 fold, coordinator O11): {@code duration} is not element-wise - a list at
 * its operand is refused ({@link #notElementwise}; stage A types a visible list, the guard in
 * {@code ExprCompiler}'s conversion arm covers the rest).</li>
 * </ul>
 * <p>
 * The guard is an inline {@code instanceof Collection} at each reader (the needle in
 * {@code Primitives.substring}, the element in {@code ElementwiseLift.liftedElement}) — no
 * capturing lambda per row (W4 fold, R5 P2-L3), so the hot path pays nothing measurable; the
 * per-row scan of a vector ({@link #holdsList}) is only reached where the compiler could not prove
 * the argument scalar by shape.
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
     * A list at the one parameter of a function that is NOT element-wise (T2 fold, review L2-L5a:
     * {@code duration}, coordinator O11 - D6's "joins E5" was never built).
     *
     * @param function
     *            the function
     * @param parameter
     *            its parameter
     * @return the sentence
     */
    public static String notElementwise(String function, String parameter)
    {
        return "argument '" + parameter + "' of '" + function + "' takes one value per row, not a"
                + " list - " + function + " is not element-wise: read one element of the list";
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
