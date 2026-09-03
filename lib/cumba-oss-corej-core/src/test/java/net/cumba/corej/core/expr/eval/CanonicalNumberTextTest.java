package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Phase 9a — verifies {@link ExprCompiler#canonicalNumberText(Number)}, the single source of truth
 * for native number&rarr;string rendering. An integral finite value drops its trailing {@code .0};
 * a fractional value renders via {@link Double#toString(double)}; the output is byte-for-byte
 * identical to the legacy {@code numberText} for every finite value (this is a refactor to a shared
 * helper, not a behaviour change).
 */
class CanonicalNumberTextTest
{

    /** The legacy {@code numberText} body, kept here as the parity oracle. */
    private static String legacy(double dd)
    {
        Double d = dd;
        if (!d.isInfinite() && Double.compare(d, Math.rint(d)) == 0)
        {
            return Long.toString(d.longValue());
        }
        return d.toString();
    }


    @Test
    void integralValuesDropTrailingZero()
    {
        assertEquals("3", ExprCompiler.canonicalNumberText(3.0));
        assertEquals("3.5", ExprCompiler.canonicalNumberText(3.5));
        assertEquals("-2", ExprCompiler.canonicalNumberText(-2.0));
        assertEquals("100", ExprCompiler.canonicalNumberText(100.0));
        assertEquals("0", ExprCompiler.canonicalNumberText(0.0));
        assertEquals("0", ExprCompiler.canonicalNumberText(-0.0));
    }


    @Test
    void renderingIsIdenticalToLegacyForFiniteValues()
    {
        double[] sample =
        {
                3.0, 3.5, -2.0, 100.0, 0.0, -0.0, 1e15, 1e16, 0.1, 12345.6789, -7.0, 2.5e-13,
                1.2345678901234567, 123456789012.5, 1234567890123.5, 9007199254740992.0,
                Double.MAX_VALUE, Double.MIN_VALUE
        };
        for (double v : sample)
        {
            assertEquals(legacy(v), ExprCompiler.canonicalNumberText(v),
                    "canonicalNumberText must match legacy numberText for " + v);
        }
    }


    @Test
    void infinitiesRenderLikeLegacy()
    {
        assertEquals(legacy(Double.POSITIVE_INFINITY),
                ExprCompiler.canonicalNumberText(Double.POSITIVE_INFINITY));
        assertEquals(legacy(Double.NEGATIVE_INFINITY),
                ExprCompiler.canonicalNumberText(Double.NEGATIVE_INFINITY));
    }
}
