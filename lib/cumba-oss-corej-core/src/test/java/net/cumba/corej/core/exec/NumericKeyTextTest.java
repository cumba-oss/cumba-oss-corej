package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.stream.Stream;
import net.cumba.datatable.values.DataValueSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link NumericKeyText} — the one canonical form behind {@code RelrecRowExpander.normKey} and
 * {@code ChildMatchIndex.normalizeJoinToken} (PLAN-relrec-idvar-key-precision T1-1 (a)).
 */
class NumericKeyTextTest
{

    static Stream<Arguments> canonical()
    {
        return Stream.of(
                // the coercion every existing join relies on (D4-R5): "1" = "1.0" = 1
                Arguments.of("1", "1"), Arguments.of("1.0", "1"), Arguments.of("1.00", "1"),
                Arguments.of("01", "1"), Arguments.of("+1", "1"), Arguments.of("1.50", "1.5"),
                Arguments.of("0", "0"), Arguments.of("-0", "0"), Arguments.of("0.0", "0"),
                Arguments.of("00", "0"), Arguments.of("-12", "-12"), Arguments.of("-012.0", "-12"),
                // plain notation, the parent cell's own spelling (never scientific)
                Arguments.of("5.0E-4", "0.0005"), Arguments.of("0.0005", "0.0005"),
                Arguments.of("1e20", "100000000000000000000"), Arguments.of("1.0e-3", "0.001"),
                Arguments.of("12345678.5", "12345678.5"),
                // every digit kept: the E2 class the double coercion folded
                Arguments.of("9007199254740993", "9007199254740993"),
                Arguments.of("9007199254740993.0", "9007199254740993"),
                Arguments.of("100000000000000000001", "100000000000000000001"),
                Arguments.of("0.30000000000000001", "0.30000000000000001"));
    }


    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("canonical")
    void aDecimalTokenCanonicalises(String token, String expected)
    {
        assertEquals(expected, NumericKeyText.canonicalOrNull(token));
    }


    @Test
    void neighboursBeyond2p53AreTwoKeys()
    {
        assertNotEquals(NumericKeyText.canonicalOrNull("9007199254740992"),
                NumericKeyText.canonicalOrNull("9007199254740993"),
                "one double, two keys (RRK E2)");
        assertNotEquals(NumericKeyText.canonicalOrNull("0.3"),
                NumericKeyText.canonicalOrNull("0.30000000000000001"),
                "text beyond double precision no longer folds onto its double");
    }


    @ParameterizedTest
    @ValueSource(strings =
    {
            "", "ABC", "1d", "1f", "0x1p3", "NaN", "Infinity", "1,5", "1 2", "-", "+", ".", "1e",
            "L1", "AE-001"
    })
    void aNonDecimalTokenIsNotANumber(String token)
    {
        assertNull(NumericKeyText.canonicalOrNull(token));
    }


    /**
     * The fast integer path and the {@link java.math.BigDecimal} path agree wherever both apply:
     * the canonical form of a canonical form is itself.
     */
    @ParameterizedTest
    @ValueSource(strings =
    {
            "1", "0", "-5", "12345678901234567890", "1.5", "0.0005", "100000000000000000000"
    })
    void canonicalTextIsAFixedPoint(String canonical)
    {
        assertEquals(canonical, NumericKeyText.canonicalOrNull(canonical));
    }


    /**
     * A DOUBLE cell's own text ({@link DataValueSupport#toCleanText}) is already canonical, so a
     * cell and an authored {@code IDVARVAL} meet in one form without a second rendering.
     */
    @ParameterizedTest
    @ValueSource(doubles =
    {
            1.0d, 1.5d, 0.0005d, 12345678.5d, 1e20d, 9007199254740992d, -0.0d, 4.9999999999994d,
            123456789012.4d
    })
    void aCleanCellTextIsItsOwnCanonicalForm(double value)
    {
        String text = DataValueSupport.toCleanText(value);
        assertEquals(text, NumericKeyText.canonicalOrNull(text), "cell text " + text);
    }
}
