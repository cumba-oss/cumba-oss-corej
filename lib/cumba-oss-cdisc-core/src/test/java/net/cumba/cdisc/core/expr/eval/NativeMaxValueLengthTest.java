package net.cumba.cdisc.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import net.cumba.cdisc.core.exec.EvaluationContext;
import net.cumba.cdisc.core.exec.MockTable;
import net.cumba.cdisc.core.exec.OperatorRegistry;
import net.cumba.cdisc.core.expr.CheckExpressionParser;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Native-compiler tests for the {@code max_value_length(X)} cursor value function (T5b — SD1082).
 * The value is the maximum codepoint length over the named column's non-missing values (0 when the
 * column is absent or every value is missing), broadcast to every row — the native mirror of the
 * Python reference engine's {@code variable_max_size}
 * ({@code df[var].dropna().astype(str).str.len().max()}). Paired with {@code var_length(varname(),
 * "DATA")} (the declared length) it drives the SD1082 length-vs-max check: declared == max → no
 * fire; declared != max → fire.
 */
class NativeMaxValueLengthTest
{

    private static Expr parse(String source)
    {
        return CheckExpressionParser.parse(source);
    }


    private static EvaluationContext ctx(IDataTable t, Map<String, Object> variables)
    {
        return EvaluationContext.builder().table(t).datasetResolver(_ -> null).variables(variables)
                .build();
    }

    // ---- OperatorRegistry.maxValueLength (the underlying scan) ---------------


    @Test
    void maxOverNonMissingValues()
    {
        IDataTable t = MockTable.of().col("USUBJID", "S01", "S02", "S03")
                .col("AECOD", "AB", "ABCDE", "").build();
        assertEquals(5L, OperatorRegistry.maxValueLength(ctx(t, Map.of()), "AECOD"),
                "max codepoint length over non-missing values is 5");
        assertEquals(0L, OperatorRegistry.maxValueLength(ctx(t, Map.of()), "NOSUCHVAR"),
                "absent column reads as 0");
        assertEquals(0L, OperatorRegistry.maxValueLength(ctx(t, Map.of()), null),
                "a null (unresolved cursor) name reads as 0");
    }

    // ---- native compiled form -----------------------------------------------


    @Test
    void maxValueLengthEqualsLongestStoredValue()
    {
        IDataTable t = MockTable.of().col("USUBJID", "S01", "S02", "S03")
                .col("AECOD", "AB", "ABCDE", "").build();
        assertTrue(NativeExprEvaluator.evaluateBroadcast(parse("max_value_length(AECOD) == 5"),
                ctx(t, Map.of())), "the max stored value length is 5 (\"ABCDE\")");
        assertFalse(NativeExprEvaluator.evaluateBroadcast(parse("max_value_length(AECOD) == 2"),
                ctx(t, Map.of())), "2 is the length of the first value, not the max");
    }


    @Test
    void absentColumnIsZero()
    {
        IDataTable t = MockTable.of().col("USUBJID", "S01").col("AECOD", "AB").build();
        assertTrue(
                NativeExprEvaluator.evaluateBroadcast(parse("max_value_length(NOSUCHVAR) == 0"),
                        ctx(t, Map.of())),
                "an absent column has max value length 0 (Python variable_max_size parity)");
    }


    @Test
    void allEmptyColumnIsZero()
    {
        IDataTable t = MockTable.of().col("USUBJID", "S01", "S02").col("EMPTYVAR", "", "").build();
        assertTrue(NativeExprEvaluator.evaluateBroadcast(parse("max_value_length(EMPTYVAR) == 0"),
                ctx(t, Map.of())), "an all-empty column has max value length 0");
    }


    @Test
    void codepointCountingNotUtf16Units()
    {
        // A supplementary-plane character (😀, U+1F600) is one codepoint but two UTF-16 units;
        // the max value length counts codepoints, matching pandas str.len().
        IDataTable t = MockTable.of().col("USUBJID", "S01").col("EMOJI", "😀").build();
        assertTrue(NativeExprEvaluator.evaluateBroadcast(parse("max_value_length(EMOJI) == 1"),
                ctx(t, Map.of())), "one codepoint counts as length 1, not 2");
    }

    // ---- cursor form + SD1082 declared-vs-max shape -------------------------


    @Test
    void cursorFormResolvesTheCurrentVariable()
    {
        IDataTable t = MockTable.of().col("USUBJID", "S01", "S02").col("AECOD", "AB", "ABCDE")
                .build();
        assertTrue(
                NativeExprEvaluator.evaluateBroadcast(parse("max_value_length(varname()) == 5"),
                        ctx(t, Map.of("variable_name", "AECOD"))),
                "the cursor resolves AECOD, whose max value length is 5");
    }


    @Test
    void declaredEqualsMaxDoesNotFire()
    {
        // colMeta declares length 5; the longest stored value ("ABCDE") is also 5 → no mismatch.
        IDataTable t = MockTable.of().col("USUBJID", "S01", "S02").col("AECOD", "AB", "ABCDE")
                .colMeta("AECOD", "AE code", 5, null).build();
        assertFalse(
                NativeExprEvaluator.evaluateBroadcast(
                        parse("var_length(\"DATA\") != max_value_length(varname())"),
                        ctx(t, Map.of("variable_name", "AECOD"))),
                "declared length (5) equals max stored length (5) → SD1082 does not fire");
    }


    @Test
    void declaredExceedsMaxFires()
    {
        // colMeta over-declares length 40; the longest stored value is only 5 → mismatch.
        IDataTable t = MockTable.of().col("USUBJID", "S01", "S02").col("AECOD", "AB", "ABCDE")
                .colMeta("AECOD", "AE code", 40, null).build();
        assertTrue(
                NativeExprEvaluator.evaluateBroadcast(
                        parse("var_length(\"DATA\") != max_value_length(varname())"),
                        ctx(t, Map.of("variable_name", "AECOD"))),
                "declared length (40) differs from max stored length (5) → SD1082 fires");
    }
}
