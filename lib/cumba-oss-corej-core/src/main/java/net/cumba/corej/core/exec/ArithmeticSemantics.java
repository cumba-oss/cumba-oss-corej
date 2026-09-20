package net.cumba.corej.core.exec;

import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;

/**
 * Scalar semantics of the first-class arithmetic operators {@code + - * /} (phase 3d of
 * {@code PLAN-typed-expression-engine}, D83): {@code number × number → number}, at every level and
 * in every position. The evaluator ({@code ExprCompiler}'s arithmetic value plan) computes each row
 * through these methods; the three fused {@code not_equal_to_divide} / {@code _subtract} /
 * {@code _pctchg} shapes they replaced are now ordinary {@code X != <arithmetic>} comparisons with
 * no shape test and no special-cased verdict.
 *
 * <p>
 * ⭐ <b>Calculations are EXACT (D83b)</b> — no tolerance and no rounding inside arithmetic. Exact
 * means <em>no deliberate rounding</em>, <b>not</b> <em>no floating-point representation
 * error</em>: {@code num} is an IEEE double, so {@code ((AVAL - BASE) / BASE) * 100} still carries
 * representation error — which is precisely why the comparison tolerance
 * ({@link ScalarSemantics#tolerance}) exists at the comparison boundary. Calculation creates the
 * drift, comparison absorbs it, once, at the boundary.
 * </p>
 *
 * <p>
 * ⭐ <b>The arithmetic epsilon is retired (D83c).</b> Until phase 3d this class carried
 * {@code EPSILON = 1e-10}, an <em>absolute</em> tolerance applied to the fused arithmetic
 * {@code !=} only — D64c's half-migration failure mode (a tolerant {@code !=} beside an exact
 * {@code <}), hidden one layer down. The {@code !=} that consumes an arithmetic result now takes
 * the same relative significant-digit tolerance every other comparison takes
 * ({@link ScalarSemantics#numericEquals}); nothing arithmetic-specific remains at the comparison.
 * {@link ScalarSemantics#DATE_EPSILON} is unaffected — dates are special (D79a).
 * </p>
 *
 * <p>
 * ⭐⭐ <b>Missing semantics (D85 / D85c / D86 / D86a)</b> — the engine has no {@code NaN} results;
 * every non-value is a {@link MissingValue}:
 * </p>
 * <ul>
 * <li><b>Operand missing — propagate the identity</b> (D85c/D86/D86a): collect the
 * {@code MissingValue}s of all missing operands; <b>one distinct identity ⇒ that identity; two or
 * more ⇒ {@link MissingValue#MIS}; none ⇒ the ordinary result</b>. So {@code .A + 5} is {@code .A},
 * {@code .A + .A} is {@code .A}, {@code .A + .B} is {@code MIS}. <b>Commutativity is the governing
 * property</b> — {@code a + b} and {@code b + a} must agree, which any "left operand wins" rule
 * breaks; {@link #combineIdentities} is symmetric by construction, and associative, so nested
 * binary operators realise the n-ary rule exactly. ⚠ This deliberately preserves <em>more</em> than
 * SAS, which collapses any special missing to plain {@code .} in arithmetic (D85c records the
 * divergence; its SAS confirmation is an owner action).</li>
 * <li><b>Zero divisor — {@link MissingValue#MIS} directly</b> (D85/D83e): a <em>no result</em>,
 * with no operand identity to propagate. The caller answers {@code MIS} <em>before</em> calling
 * {@link #divide}, whose zero-denominator branch is a loud contract assertion. Note the two paths
 * reach {@code MIS} for different reasons: propagation carries an identity, a zero divisor creates
 * one.</li>
 * <li><b>A computed {@code NaN} is a computed missing</b> (D36 #8): an unreadable numeric read of a
 * present cell, or a {@code NaN} produced by the operation itself (e.g. {@code ∞ - ∞} over infinite
 * operands), yields {@code MIS} — identities are only preserved when they are <em>carried</em>,
 * never when they are <em>created</em>. No {@code NaN} ever denotes a result (D85); {@code NaN}
 * remains only the datatable's on-the-wire encoding of a numeric {@code MissingValue}, decoded at
 * the carrier boundary (D85a).</li>
 * </ul>
 *
 * <p>
 * The verdict of a comparison that consumes a missing arithmetic result follows the comparison's
 * own missing semantics — for the shipped {@code X != <arithmetic>} rules that means a present
 * {@code X} never equals a missing result, so the row <b>fires</b> (D34 #5-2's total comparison),
 * where the fused shapes' caller used to skip any row with a missing operand. That flip is phase
 * 3d's ruled change, not an accident; the fused skip existed only to freeze verdicts until this
 * phase.
 * </p>
 */
public final class ArithmeticSemantics
{

    private ArithmeticSemantics()
    {
    }


    /**
     * D86/D86a's identity combine for one binary operation over the two operands' missing
     * identities: none missing ⇒ {@code null} (compute the ordinary result); one distinct identity
     * ⇒ that identity; two distinct ⇒ {@link MissingValue#MIS}.
     *
     * <p>
     * Symmetric ({@code combineIdentities(a, b) == combineIdentities(b, a)}) and associative, so
     * folding it pairwise over any operand list implements the n-ary rule of D86a verbatim —
     * commutativity of {@code +} and {@code *} holds by construction rather than by convention.
     * </p>
     *
     * @param left
     *            the left operand's missing identity, or {@code null} when it holds a value
     * @param right
     *            the right operand's missing identity, or {@code null} when it holds a value
     * @return the result's missing identity, or {@code null} when both operands hold values
     */
    public static @Nullable MissingValue combineIdentities(@Nullable MissingValue left,
            @Nullable MissingValue right)
    {
        if (left == null)
        {
            return right;
        }
        if (right == null)
        {
            return left;
        }
        return left == right ? left : MissingValue.MIS;
    }


    /**
     * {@code a + b}.
     *
     * @param a
     *            the left addend
     * @param b
     *            the right addend
     * @return the sum
     */
    public static double add(double a, double b)
    {
        return a + b;
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
     * {@code a * b}.
     *
     * @param a
     *            the left factor
     * @param b
     *            the right factor
     * @return the product
     */
    public static double multiply(double a, double b)
    {
        return a * b;
    }


    /**
     * {@code numerator / denominator}. ⛔ A zero denominator never reaches this method: it is a
     * <em>no result</em> — {@code MissingValue.MIS} — decided by the caller (D85/D83e), so hitting
     * the guard means an engine defect, not a data condition (loud beats a silent {@code NaN}).
     *
     * @param numerator
     *            the dividend
     * @param denominator
     *            the divisor; never {@code 0.0}
     * @return the quotient
     */
    public static double divide(double numerator, double denominator)
    {
        if (denominator == 0.0)
        {
            throw new IllegalArgumentException(
                    "zero denominator: the caller answers MissingValue.MIS (D85), "
                            + "arithmetic never runs");
        }
        return numerator / denominator;
    }

}
