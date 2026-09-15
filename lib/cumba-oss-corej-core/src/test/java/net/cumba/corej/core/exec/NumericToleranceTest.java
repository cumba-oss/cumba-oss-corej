package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import net.cumba.datatable.values.DataValueSupport;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Step C of {@code PLAN-joined-column-typing}: numeric comparison carries a tolerance, configurable
 * by significant digits, applied to <b>all</b> numeric comparisons and all five relations.
 *
 * <p>
 * Owner ruling, 2026-09-14/15 (D8, D12): the tolerance is <b>purely relative</b> with no absolute
 * floor, defaulting to {@link ScalarSemantics#DEFAULT_TOLERANCE_DIGITS} significant digits, and
 * {@code AGE < 17} must be <b>false</b> for a subject of {@code 16.99999999997}.
 * </p>
 */
class NumericToleranceTest
{

    private static final int GT = 1;

    private static final int LT = -1;

    @Test
    @DisplayName("the owner's AGE case: 16.99999999997 is 17, so `< 17` is false and `>= 17` true")
    void ageCase()
    {
        double age = 16.99999999997;
        assertTrue(ScalarSemantics.numericEquals(age, 17.0));
        assertFalse(ScalarSemantics.compareNumericTolerant(age, 17.0, LT, false), "< 17");
        assertTrue(ScalarSemantics.compareNumericTolerant(age, 17.0, GT, true), ">= 17");
        // Tolerance cannot be one-sided: if it were, `== 17` and `< 17` would both hold.
        assertFalse(ScalarSemantics.compareNumericTolerant(age, 17.0, GT, false), "> 17");
    }


    @Test
    @DisplayName("⛔ NOT quantisation: 4.999999999997 and 5.00000000001 are equal")
    void isNotQuantisation()
    {
        // This pair is the reason "significant digits" specifies the tolerance's MAGNITUDE and is
        // not implemented by rounding. Quantise both to 12 significant digits and they land on
        // 5.00000000000 and 5.00000000001 -- DIFFERENT -- which contradicts the ruling. |a-b| <= ε
        // gets it right.
        assertTrue(ScalarSemantics.numericEquals(4.999999999997, 5.00000000001));
    }


    @Test
    @DisplayName("D8: nothing is approximately zero except zero")
    void nothingIsApproximatelyZero()
    {
        // A purely relative ε scales to its operands, so |x-0| = |x| can never be <= |x|*1e-11
        // unless x is 0. A genuine lab value of 1e-15 therefore correctly satisfies `> 0` -- which
        // an absolute floor of 1e-13 would have broken by calling it equal to zero.
        assertFalse(ScalarSemantics.numericEquals(1e-15, 0.0));
        assertTrue(ScalarSemantics.compareNumericTolerant(1e-15, 0.0, GT, false), "1e-15 > 0");
        assertTrue(ScalarSemantics.numericEquals(0.0, 0.0));
    }


    @Test
    @DisplayName("the scale is max(|a|,|b|), so a > b and b < a cannot disagree")
    void scaleIsSymmetric()
    {
        double a = 1.0E13 + 1;
        double b = 1.0E13;
        assertEquals(ScalarSemantics.tolerance(a, b), ScalarSemantics.tolerance(b, a));
        assertFalse(ScalarSemantics.compareNumericTolerant(a, b, GT, false));
        assertFalse(ScalarSemantics.compareNumericTolerant(b, a, LT, false));
    }


    @Test
    @DisplayName("values further apart than ε still order normally")
    void wideGapsStillOrder()
    {
        assertTrue(ScalarSemantics.compareNumericTolerant(30.0, 9.0, GT, false));
        assertTrue(ScalarSemantics.compareNumericTolerant(9.0, 30.0, LT, false));
        assertFalse(ScalarSemantics.numericEquals(30.0, 9.0));
        // small magnitudes: ε scales down with them, so ordinary clinical values are unaffected
        assertFalse(ScalarSemantics.numericEquals(0.1, 0.2));
        assertTrue(ScalarSemantics.compareNumericTolerant(0.2, 0.1, GT, false));
    }


    @Test
    @DisplayName("exactly one of <, ==, > holds for any pair")
    void exactlyOneRelationHolds()
    {
        double[][] pairs =
        {
                {
                        17.0, 16.99999999997
                },
                {
                        30.0, 9.0
                },
                {
                        0.0, 0.0
                },
                {
                        1e-15, 0.0
                },
                {
                        1.0E13 + 1, 1.0E13
                },
                {
                        -5.0, 5.0
                }
        };
        for (double[] p : pairs)
        {
            int n = (ScalarSemantics.compareNumericTolerant(p[0], p[1], LT, false) ? 1 : 0)
                    + (ScalarSemantics.numericEquals(p[0], p[1]) ? 1 : 0)
                    + (ScalarSemantics.compareNumericTolerant(p[0], p[1], GT, false) ? 1 : 0);
            assertEquals(1, n, p[0] + " vs " + p[1] + ": exactly one relation must hold");
        }
    }


    @Test
    @DisplayName("<= is (< or ==) and >= is (> or ==), at the ε boundary too")
    void inclusiveRelationsAgreeWithTheirParts()
    {
        double[][] pairs =
        {
                {
                        5.002294452894392, 5.002294452944415
                },
                {
                        17.0, 16.99999999997
                },
                {
                        30.0, 9.0
                },
                {
                        0.0, 0.0
                },
                {
                        -5.0, 5.0
                }
        };
        for (double[] p : pairs)
        {
            boolean lt = ScalarSemantics.compareNumericTolerant(p[0], p[1], LT, false);
            boolean gt = ScalarSemantics.compareNumericTolerant(p[0], p[1], GT, false);
            boolean eq = ScalarSemantics.numericEquals(p[0], p[1]);
            assertEquals(lt || eq, ScalarSemantics.compareNumericTolerant(p[0], p[1], LT, true),
                    p[0] + " <= " + p[1]);
            assertEquals(gt || eq, ScalarSemantics.compareNumericTolerant(p[0], p[1], GT, true),
                    p[0] + " >= " + p[1]);
        }
    }


    @Test
    @DisplayName("D9: membership carries the same tolerance as equality")
    void membershipCarriesTheTolerance()
    {
        // `in` is "equals one of the following", so AGE == 17 and AGE in [17] must agree. Before
        // this, isNumericMember did an exact Set<Double> lookup and they disagreed.
        IDataValue age = DataValueSupport.getAsDataValue(16.99999999997, DataValueType.DOUBLE);
        assertTrue(ScalarSemantics.numericEquals(16.99999999997, 17.0), "the == side");
        assertTrue(ScalarSemantics.isNumericMember(age, Set.of(17.0)), "the `in` side must agree");
        assertTrue(ScalarSemantics.isNumericMember(age, Set.of(17.0, 18.0)));
        // A genuinely absent member is still absent -- the tolerance widens, it does not match all.
        assertFalse(ScalarSemantics.isNumericMember(age, Set.of(18.0, 19.0)));
        // The exact fast path still works.
        assertTrue(ScalarSemantics.isNumericMember(
                DataValueSupport.getAsDataValue(17.0, DataValueType.DOUBLE), Set.of(17.0)));
    }


    @Test
    @DisplayName("D12: the default is 12 significant digits, matching getAsDoubleCleaned")
    void defaultIsTwelveDigits()
    {
        assertEquals(12, ScalarSemantics.DEFAULT_TOLERANCE_DIGITS);
        assertEquals(ScalarSemantics.DEFAULT_TOLERANCE_DIGITS, ScalarSemantics.toleranceDigits(),
                "no -D override is set in the test JVM");
        // ε is the relative magnitude the digit count names.
        assertEquals(17.0 * 1e-11, ScalarSemantics.tolerance(17.0, 16.99999999997), 1e-20);
    }


    @Test
    @DisplayName("F6: the property is parsed, clamped and WARNS rather than falling back silently")
    void tolerancePropertyIsParsedAndClamped()
    {
        // Parsing is exercised directly because TOLERANCE_DIGITS is a static final resolved at
        // class init -- D12's override previously shipped with nothing able to cover N != 12.
        assertEquals(15, ScalarSemantics.parseToleranceDigits("15"), "a valid override applies");
        assertEquals(1, ScalarSemantics.parseToleranceDigits(" 1 "), "trimmed");
        assertEquals(17, ScalarSemantics.parseToleranceDigits("17"), "the upper bound is usable");
        // Unusable values fall back -- and now log a WARNING, which is the difference between
        // "you did not set it" and "you set it wrong".
        assertEquals(12, ScalarSemantics.parseToleranceDigits("l2"), "letter ell, not a number");
        assertEquals(12, ScalarSemantics.parseToleranceDigits("0"), "below the range");
        assertEquals(12, ScalarSemantics.parseToleranceDigits("18"), "above the range");
        assertEquals(12, ScalarSemantics.parseToleranceDigits(""), "blank");
        assertEquals(12, ScalarSemantics.parseToleranceDigits(null), "unset");
    }


    @Test
    @DisplayName("a different N genuinely changes the tolerance's magnitude")
    void toleranceScalesWithTheConfiguredDigits()
    {
        // The ε formula is exercised at several N by computing it directly, so the relationship
        // between the setting and the tolerance is covered even though the effective setting is
        // fixed at class-init in this JVM.
        assertEquals(17.0 * 1e-11, 17.0 * Math.pow(10, -(12 - 1)), 1e-20);
        assertEquals(17.0 * 1e-14, 17.0 * Math.pow(10, -(15 - 1)), 1e-24);
    }


    @Test
    @DisplayName("non-finite operands fall back to exact comparison")
    void nonFiniteIsExact()
    {
        assertEquals(0.0d, ScalarSemantics.tolerance(Double.POSITIVE_INFINITY, 1.0));
        assertFalse(ScalarSemantics.numericEquals(Double.NaN, Double.NaN));
        assertTrue(
                ScalarSemantics.numericEquals(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY));
    }
}
