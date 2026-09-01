package net.cumba.cdisc.core.exec;

/**
 * Shared scalar arithmetic-comparison semantics for the {@code not_equal_to_divide},
 * {@code not_equal_to_subtract} and {@code not_equal_to_pctchg} operators. Both the legacy operator
 * engine ({@link OperatorRegistry}) and the native expression evaluator
 * ({@code net.cumba.cdisc.core.expr.eval.ExprCompiler}) compute the expected value and the
 * not-equal verdict through this one helper, so the two backends agree bit-for-bit (Phase 4b /
 * native-first principle — the algorithm lives here once; when the legacy engine is retired the
 * operator wrappers drop away and this stays).
 *
 * <p>
 * Missing/invalid operands are surfaced as {@link Double#NaN} (division/percent-change by zero, or
 * a non-numeric cell), and {@link #differs(double, double)} treats any {@code NaN} as "no
 * violation", matching the legacy skip-on-missing/NaN/zero-denominator behaviour.
 * </p>
 */
public final class ArithmeticSemantics
{

    /**
     * Tolerance for the arithmetic not-equal comparison. Distinct from the {@code 1e-9}
     * {@link ScalarSemantics#DATE_EPSILON} date tolerance.
     */
    public static final double EPSILON = 1e-10;

    private ArithmeticSemantics()
    {
    }


    /**
     * {@code numerator / denominator}, or {@link Double#NaN} when the denominator is zero (no
     * violation).
     *
     * @param numerator
     *            the dividend
     * @param denominator
     *            the divisor
     * @return the quotient, or {@code NaN} for a zero denominator
     */
    public static double divide(double numerator, double denominator)
    {
        return denominator == 0.0 ? Double.NaN : numerator / denominator;
    }


    /**
     * {@code a - b}.
     *
     * @param a
     *            the minuend
     * @param b
     *            the subtrahend
     * @return the difference
     */
    public static double subtract(double a, double b)
    {
        return a - b;
    }


    /**
     * {@code ((a - b) / b) * 100}, or {@link Double#NaN} when {@code b} is zero (no violation).
     *
     * @param a
     *            the current value
     * @param b
     *            the baseline value
     * @return the percent change, or {@code NaN} for a zero baseline
     */
    public static double percentChange(double a, double b)
    {
        return b == 0.0 ? Double.NaN : (a - b) / b * 100.0;
    }


    /**
     * Whether {@code actual} differs from {@code expected} by more than {@link #EPSILON} — the
     * not_equal_to_* violation condition. A {@code NaN} on either side (missing/invalid operand or
     * a zero denominator) is not a violation.
     *
     * @param actual
     *            the observed value
     * @param expected
     *            the computed expected value
     * @return {@code true} when the two differ beyond the tolerance
     */
    public static boolean differs(double actual, double expected)
    {
        if (Double.isNaN(actual) || Double.isNaN(expected))
        {
            return false;
        }
        return Math.abs(actual - expected) > EPSILON;
    }

}
