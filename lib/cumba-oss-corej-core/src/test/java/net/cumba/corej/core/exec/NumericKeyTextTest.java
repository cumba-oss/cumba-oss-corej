package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.function.Function;
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

    private static final Function<String, String> CANONICAL = NumericKeyText::canonicalOrNull;

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
                Arguments.of("0.30000000000000001", "0.30000000000000001"),
                // M1 (review round 1): beyond |scale| 400 the text is scientific, never a 2^31-char
                // plain string; every one of these used to kill the run (OOM / negative array
                // size) and gave Infinity under the old double coercion
                Arguments.of("1E999999999", "1E+999999999"),
                Arguments.of("1E-999999999", "1E-999999999"),
                Arguments.of("1E2147483647", "1E+2147483647"),
                Arguments.of("1000E2147483000", "1E+2147483003"),
                Arguments.of("1E2147483648", "1E+2147483648"),
                Arguments.of("1000E2147483645", "1E+2147483648"),
                Arguments.of("1.5E-500", "1.5E-500"), Arguments.of("1E401", "1E+401"),
                Arguments.of("1E-401", "1E-401"),
                // still plain at the bound (401 / 402 characters)
                Arguments.of("1E400", "1" + "0".repeat(400)),
                Arguments.of("1E-400", "0." + "0".repeat(399) + "1"),
                // round 2 M1: a canonical integer longer than the bound takes the scientific arm
                // like every other spelling of its value (the unbounded fast path kept it plain,
                // so "1E401" and "1" + 401 zeros were equal values with unequal text)
                Arguments.of("1" + "0".repeat(401), "1E+401"),
                Arguments.of("-1" + "0".repeat(401), "-1E+401"),
                Arguments.of("1" + "0".repeat(400), "1" + "0".repeat(400)));
    }


    /**
     * Equal values have equal text, whichever arm renders them — several spellings of one value in
     * and around the scientific range all canonicalise to the first spelling's text. The
     * injectivity test above cannot see this: it only says different values differ.
     */
    static Stream<Arguments> spellingsOfOneValue()
    {
        return Stream.of(
                Arguments.of(List.of("1E401", "1" + "0".repeat(401), "10E400", "1.0E401", "0.1E402",
                        "+1E401", "1" + "0".repeat(401) + ".0", "1000E398")),
                Arguments.of(List.of("-1E401", "-1" + "0".repeat(401), "-10E400", "-1.0E401")),
                Arguments.of(List.of("1E-401", "0.1E-400", "10E-402", "0." + "0".repeat(400) + "1",
                        "1.0E-401")),
                Arguments.of(List.of("1.5E-500", "15E-501", "0.15E-499", "150E-502")),
                Arguments.of(List.of("1E999999999", "10E999999998", "0.1E1000000000")),
                // control at and below the bound: the plain arm agrees with itself
                Arguments.of(List.of("1E400", "1" + "0".repeat(400), "10E399", "1.0E400")),
                Arguments.of(List.of("1E-400", "0." + "0".repeat(399) + "1", "10E-401")),
                Arguments.of(List.of("12", "12.0", "012", "1.2E1", "120E-1", "+12")));
    }


    @ParameterizedTest(name = "{0}")
    @MethodSource("spellingsOfOneValue")
    void equalValuesHaveEqualText(List<String> spellings)
    {
        String expected = CANONICAL.apply(spellings.get(0));
        assertNotNull(expected, spellings.get(0) + " must be a number");
        for (String spelling : spellings)
        {
            assertEquals(expected, CANONICAL.apply(spelling), spelling);
        }
    }


    @Test
    void scientificArmIsInjectiveAndAFixedPoint()
    {
        // Two different stripped values never share a text, whichever arm renders them, and a
        // rendered text canonicalises to itself (a re-parse lands on the same arm).
        assertNotEquals(NumericKeyText.canonicalOrNull("1E999999999"),
                NumericKeyText.canonicalOrNull("2E999999999"));
        assertNotEquals(NumericKeyText.canonicalOrNull("1E999999999"),
                NumericKeyText.canonicalOrNull("1E999999998"));
        for (String t : new String[]
        {
                "1E999999999", "1E-999999999", "1E2147483647", "1.5E-500", "1E400", "1E-400"
        })
        {
            String once = NumericKeyText.canonicalOrNull(t);
            assertEquals(once, NumericKeyText.canonicalOrNull(once), t);
        }
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
            "L1", "AE-001",
            // an exponent BigDecimal cannot hold: while parsing (NumberFormatException) and
            // while stripping the trailing zeros (ArithmeticException) -- both are text
            "1E-2147483648", "10000E2147483645",
            // L3 (review round 1): BigDecimal accepts non-ASCII digits; a key does not
            "\uFF11\uFF12", "\u0661\u0662", "1\uFF10"
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
