package net.cumba.cdisc.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.Map;
import net.cumba.cdisc.core.exec.EvaluationContext;
import net.cumba.cdisc.core.expr.CheckExpressionParser;
import net.cumba.cdisc.core.expr.ExpressionException;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * T5b (SD1082) — pins the {@code max_value_length()} compiler plan
 * ({@code ExprCompiler.compileMaxValueLength}).
 *
 * <p>
 * {@code OperatorRegistry.maxValueLength} — the probe the plan delegates to — was already covered
 * by {@code ExistenceProbeTest}, but the <b>plan around it</b> was not: mutation testing reported
 * all 6 of its mutants surviving. The plan is what decides <em>which column</em> the probe is asked
 * about, and it accepts four argument shapes (a bare reference, a string literal, an explicit
 * {@code variable_name} cursor and no argument at all) plus a rejection path. Nothing held any of
 * them, so the plan could have measured the wrong column — or always the cursor — in silence.
 * </p>
 *
 * <p>
 * Each case asserts both the matching length and a non-matching one: a plan that answered a
 * constant would satisfy the positive half alone.
 * </p>
 */
class ExprCompilerMaxValueLengthTest
{

    /** AETERM's longest value is "HEADACHE" (8); AEDECOD's is "PAIN"/"ACHE" (4). */
    private static IDataTable ae()
    {
        return MockTable.of().name("AE").col("AETERM", "RASH", "HEADACHE")
                .col("AEDECOD", "PAIN", "ACHE").build();
    }


    private static EvaluationContext ctx(Map<String, Object> variables)
    {
        return EvaluationContext.builder().table(ae()).variables(variables).build();
    }


    /** True when the expression fires on at least one row. */
    private static boolean fires(String source, Map<String, Object> variables)
    {
        BitSet b = NativeExprEvaluator.evaluate(CheckExpressionParser.parse(source),
                ctx(variables));
        return !b.isEmpty();
    }


    @Test
    void aBareColumnReferenceMeasuresThatColumn()
    {
        assertTrue(fires("max_value_length(AETERM) == \"8\"", Map.of()));
        assertFalse(fires("max_value_length(AETERM) == \"4\"", Map.of()));
        assertTrue(fires("max_value_length(AEDECOD) == \"4\"", Map.of()));
    }


    @Test
    void aStringLiteralNamesTheSameColumnAsTheBareReference()
    {
        assertTrue(fires("max_value_length(\"AETERM\") == \"8\"", Map.of()));
        assertFalse(fires("max_value_length(\"AETERM\") == \"4\"", Map.of()));
        assertTrue(fires("max_value_length(\"AEDECOD\") == \"4\"", Map.of()));
    }


    @Test
    void noArgumentMeasuresTheVariableUnderTheCursor()
    {
        assertTrue(fires("max_value_length() == \"4\"", Map.of("variable_name", "AEDECOD")));
        assertFalse(fires("max_value_length() == \"8\"", Map.of("variable_name", "AEDECOD")));
        assertTrue(fires("max_value_length() == \"8\"", Map.of("variable_name", "AETERM")));
    }


    @Test
    void anExplicitVariableNameArgumentIsTheCursorNotAColumnName()
    {
        assertTrue(fires("max_value_length(variable_name) == \"4\"",
                Map.of("variable_name", "AEDECOD")));
        assertTrue(fires("max_value_length(variable_name) == \"8\"",
                Map.of("variable_name", "AETERM")));
    }


    @Test
    void anUnresolvedCursorMeasuresNothing()
    {
        // resolveVariable misses → the probe is asked about a null column, which reads as 0.
        assertTrue(fires("max_value_length() == \"0\"", Map.of()));
    }


    @Test
    void anArgumentThatNamesNoColumnIsRejected()
    {
        assertThrows(ExpressionException.class,
                () -> fires("max_value_length(1) == \"0\"", Map.of()));
    }

}
