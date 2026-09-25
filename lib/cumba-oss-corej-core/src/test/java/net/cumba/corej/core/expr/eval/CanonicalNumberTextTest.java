package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import net.cumba.datatable.values.DataValueDouble;
import net.cumba.datatable.values.DataValueSupport;
import org.junit.jupiter.api.Test;

/**
 * {@link ExprCompiler#canonicalNumberText(Number)}, the single source of truth for native
 * number&rarr;string rendering. Since {@code PLAN-numeric-cleaning-and-key-text} (owner ruling D2,
 * 2026-09-25) it is {@link DataValueSupport#toPlainNumberText(double)}: an integral finite value
 * drops its trailing {@code .0}; a fractional value renders its shortest round-trip digits in
 * <b>plain</b> notation; nothing saturates. ⚠ Re-pinned here from the legacy {@code numberText}
 * oracle, which rendered {@code 12345678.9} as {@code "1.23456789E7"} and {@code Double.MAX_VALUE}
 * as {@code "9223372036854775807"}: a cell renders plain, so a literal must too, or the two never
 * match (review HIGH-1).
 */
class CanonicalNumberTextTest
{

    @Test
    void integralValuesDropTrailingZero()
    {
        assertEquals("3", ExprCompiler.canonicalNumberText(3.0));
        assertEquals("3.5", ExprCompiler.canonicalNumberText(3.5));
        assertEquals("-2", ExprCompiler.canonicalNumberText(-2.0));
        assertEquals("100", ExprCompiler.canonicalNumberText(100.0));
        assertEquals("0", ExprCompiler.canonicalNumberText(0.0));
        assertEquals("0", ExprCompiler.canonicalNumberText(-0.0));
        assertEquals("1000000000000000", ExprCompiler.canonicalNumberText(1e15));
        assertEquals("9007199254740992", ExprCompiler.canonicalNumberText(9007199254740992.0));
        assertEquals("42", ExprCompiler.canonicalNumberText(42));
        assertEquals("42", ExprCompiler.canonicalNumberText(42L));
    }


    @Test
    void fractionalValuesRenderPlainNeverScientific()
    {
        assertEquals("0.1", ExprCompiler.canonicalNumberText(0.1));
        assertEquals("12345.6789", ExprCompiler.canonicalNumberText(12345.6789));
        assertEquals("1.2345678901234567", ExprCompiler.canonicalNumberText(1.2345678901234567));
        assertEquals("123456789012.5", ExprCompiler.canonicalNumberText(123456789012.5));
        // the three that the legacy oracle rendered in scientific notation
        assertEquals("12345678.9", ExprCompiler.canonicalNumberText(12345678.9));
        assertEquals("1234567890123.5", ExprCompiler.canonicalNumberText(1234567890123.5));
        assertEquals("0.00000000000025", ExprCompiler.canonicalNumberText(2.5e-13));
    }


    @Test
    void aLiteralRendersLikeTheCellOfTheSameValue()
    {
        // HIGH-1: a numeric literal and a DOUBLE cell of the same (noise-free) value must spell
        // alike, or a match between them is lost to notation
        for (double v : new double[]
        {
                12345678.9, 0.0001, 3.5, 100.0, 1e20, 1234567890123.4
        })
        {
            assertEquals(new DataValueDouble(v).getValueAsString(),
                    ExprCompiler.canonicalNumberText(v), "cell vs literal text for " + v);
        }
    }


    @Test
    void nothingSaturatesAndTheExtremesAreExact()
    {
        assertEquals("100000000000000000000", ExprCompiler.canonicalNumberText(1e20));
        String max = ExprCompiler.canonicalNumberText(Double.MAX_VALUE);
        assertNotEquals("9223372036854775807", max, "the legacy (long) cast saturated here");
        assertEquals(309, max.length());
        assertFalse(max.contains("E"), max);
        // 4.9E-324 written out: "0." then 323 zeros then "49" -- a literal, not the method
        assertEquals("0." + "0".repeat(323) + "49",
                ExprCompiler.canonicalNumberText(Double.MIN_VALUE));
    }


    @Test
    void infinitiesRenderAsTheirNames()
    {
        assertEquals("Infinity", ExprCompiler.canonicalNumberText(Double.POSITIVE_INFINITY));
        assertEquals("-Infinity", ExprCompiler.canonicalNumberText(Double.NEGATIVE_INFINITY));
        assertEquals("NaN", ExprCompiler.canonicalNumberText(Double.NaN));
    }
}
