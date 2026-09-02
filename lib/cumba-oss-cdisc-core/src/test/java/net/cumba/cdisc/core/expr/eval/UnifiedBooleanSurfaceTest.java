package net.cumba.cdisc.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.BitSet;
import net.cumba.cdisc.core.exec.EvaluationContext;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.core.expr.CheckExpressionParser;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Phases 1–2 of the unified callable surface: every boolean callable — registered functions and the
 * hard-coded {@code *exists*} family — is usable in <b>boolean position</b> (bare and under
 * {@code not}) and supports {@code == true/false} (and {@code <bool> == <bool>}), evaluating
 * identically to the bare / {@code not} form.
 * <p>
 * ⚠ The inline-<em>operation</em> boolean-position path admits every boolean-valued operation that
 * is not library-dependent — today {@code variable_is_null} and {@code variable_exists}.
 * {@code domain_is_custom} stays excluded because it IS library-dependent (a bare/{@code not} use
 * would mis-fire under invert without a Library). ⚠⚠ This paragraph previously said the path
 * "admits no operation"; that was wrong even then ({@code variable_is_null} qualified), so read the
 * admitted set off {@code OperationExecutor.isBooleanValued}, not off prose.
 */
class UnifiedBooleanSurfaceTest
{

    private static IDataTable aeTable()
    {
        return MockTable.of().name("AE").colLong("AESEQ", 1L).col("AETERM", "HEADACHE").build();
    }


    private static EvaluationContext ctx()
    {
        return EvaluationContext.builder().table(aeTable()).domainName("AE").build();
    }


    private static EvaluationContext ctxWithLibrary(boolean domainCustom)
    {
        MetadataProvider library = mock(MetadataProvider.class);
        lenient().when(library.isDomainCustom("AE")).thenReturn(domainCustom);
        return EvaluationContext.builder().table(aeTable()).domainName("AE")
                .libraryProvider(library).build();
    }


    private static boolean supported(String expr)
    {
        return NativeExprEvaluator.isSupported(CheckExpressionParser.parse(expr));
    }


    private static BitSet eval(String expr, EvaluationContext ctx)
    {
        return NativeExprEvaluator.evaluate(CheckExpressionParser.parse(expr), ctx);
    }

    // ---- Phase 1: boolean operations in boolean position ----


    @Test
    void varExistsFunctionCompilesInBooleanPosition()
    {
        // The var_exists FUNCTION is a boolean callable usable in boolean position (bare and under
        // not). It is the surface that DECIDES column existence; the variable_exists operation was
        // retired from that role and has come back only as the reporting carriage of this function
        // (plans/PLAN-retired-operators-as-operations.md), so this is still the form rules use.
        assertTrue(supported("var_exists(\"AETERM\")"));
        assertTrue(supported("not var_exists(\"AETERM\")"));
    }


    /**
     * The inline-operation arm of the unified surface, exercised by {@code variable_exists} — the
     * one non-library boolean operation added since T5a's {@code variable_is_null}. ⚠ It is here to
     * pin {@code OperationExecutor.isBooleanValued}, whose only consumer is
     * {@code ExprCompiler.isUnifiableBooleanOperation}: without this case, dropping
     * {@code VARIABLE_EXISTS} from that method leaves the whole build green.
     *
     * <p>
     * ⚑ The equality assertion is the real point: an inline {@code variable_exists(X)} must
     * evaluate <em>identically</em> to {@code var_exists(X)}, because the operation exists only to
     * report the answer the function decides. A divergence here is a defect even though no shipped
     * rule authors the inline form.
     * </p>
     */
    @Test
    void inlineVariableExistsOperationCompilesAndAgreesWithTheFunction()
    {
        assertTrue(supported("variable_exists(\"AETERM\")"));
        assertTrue(supported("not variable_exists(\"AETERM\")"));
        EvaluationContext ctx = ctx();
        assertEquals(eval("var_exists(\"AETERM\")", ctx), eval("variable_exists(\"AETERM\")", ctx));
        assertEquals(eval("var_exists(\"NOSUCH\")", ctx), eval("variable_exists(\"NOSUCH\")", ctx));
        assertEquals(eval("not var_exists(\"NOSUCH\")", ctx),
                eval("not variable_exists(\"NOSUCH\")", ctx));
    }


    @Test
    void nonBooleanOperationIsNotABooleanCondition()
    {
        // variable_count returns a number — it is not a boolean condition in boolean position.
        assertFalse(supported("variable_count(AETERM)"));
    }


    @Test
    void libraryDependentBooleanOpExcludedFromBooleanPosition()
    {
        // domain_is_custom is library-dependent: a bare / not use in boolean position would
        // mis-fire under invert when no Library is configured, so it is NOT admitted to the unified
        // boolean surface — it stays on the operand-position == true/false path. (It declines to
        // native here, falling back to legacy as before Phase 1.)
        assertFalse(supported("not domain_is_custom()"));
        assertFalse(supported("domain_is_custom()"));
    }

    // ---- Phase 2: == true/false for every boolean callable ----


    @Test
    void existsEqualsTrueEqualsBare()
    {
        EvaluationContext ctx = ctx();
        assertEquals(eval("var_exists(\"AETERM\")", ctx),
                eval("var_exists(\"AETERM\") == true", ctx));
    }


    @Test
    void existsEqualsFalseEqualsNot()
    {
        EvaluationContext ctx = ctx();
        assertEquals(eval("not var_exists(\"NOSUCH\")", ctx),
                eval("var_exists(\"NOSUCH\") == false", ctx));
        assertEquals(eval("not var_exists(\"NOSUCH\")", ctx),
                eval("var_exists(\"NOSUCH\") != true", ctx));
    }


    @Test
    void registeredBooleanFunctionEqualsLiteral()
    {
        assertTrue(supported("contains(AETERM, \"X\") == false"));
        EvaluationContext ctx = ctx();
        assertEquals(eval("not contains(AETERM, \"X\")", ctx),
                eval("contains(AETERM, \"X\") == false", ctx));
        assertEquals(eval("contains(AETERM, \"HEAD\")", ctx),
                eval("contains(AETERM, \"HEAD\") == true", ctx));
    }


    @Test
    void domainIsCustomEqualsFalseUsesOperandPath()
    {
        // With a Library, `domain_is_custom() == false` fires on the (non-custom) row via the
        // operand-position path (domain_is_custom is excluded from the BoolPlan reroute).
        assertEquals(1, eval("domain_is_custom() == false", ctxWithLibrary(false)).cardinality());
        assertEquals(0, eval("domain_is_custom() == false", ctxWithLibrary(true)).cardinality());
    }


    @Test
    void domainIsCustomEqualsFalseDoesNotMisfireWithoutLibrary()
    {
        // Regression guard (review finding 1): without a Library, the operation resolves to the
        // LIBRARY_NOT_AVAILABLE sentinel; `== false` must NOT fire (the BoolPlan/invert reroute
        // would have flipped a no-fire into an all-fire false positive — domain_is_custom is
        // excluded precisely to avoid that).
        assertEquals(0, eval("domain_is_custom() == false", ctx()).cardinality());
    }


    @Test
    void boolEqualsBoolIsXnor()
    {
        EvaluationContext ctx = ctx();
        // both columns exist → both true → XNOR fires.
        assertEquals(1, eval("var_exists(\"AETERM\") == var_exists(\"AESEQ\")", ctx).cardinality());
        // one exists, one not → differ → XNOR does not fire; XOR (!=) fires.
        assertEquals(0,
                eval("var_exists(\"AETERM\") == var_exists(\"NOSUCH\")", ctx).cardinality());
        assertEquals(1,
                eval("var_exists(\"AETERM\") != var_exists(\"NOSUCH\")", ctx).cardinality());
    }
}
