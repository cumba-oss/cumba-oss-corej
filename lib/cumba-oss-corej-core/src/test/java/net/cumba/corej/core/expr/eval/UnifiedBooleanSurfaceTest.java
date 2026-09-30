package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.BitSet;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Phases 1–2 of the unified callable surface: every boolean callable — registered functions and the
 * hard-coded {@code *exists*} family — is usable in <b>boolean position</b> (bare and under
 * {@code not}) and supports {@code == true/false} (and {@code <bool> == <bool>}), evaluating
 * identically to the bare / {@code not} form.
 * <p>
 * ⚠ The inline-<em>operation</em> boolean-position path admitted every boolean-valued operation
 * that was not library-dependent — none since runbook W2a retired {@code variable_is_null} and
 * {@code variable_exists} (their sites are the {@code var_is_null(X)} / {@code var_exists(X)}
 * functions) — and went with the operation surface in runbook W8. {@code domain_is_custom} stays
 * excluded because it IS library-dependent (a bare/{@code not} use would mis-fire under invert
 * without a Library).
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
        // (plans/done/PLAN-retired-operators-as-operations.md), so this is still the form rules
        // use.
        assertTrue(supported("var_exists(\"AETERM\")"));
        assertTrue(supported("not var_exists(\"AETERM\")"));
    }


    @Test
    void nonBooleanFunctionIsNotABooleanCondition()
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
        // ⭐ Wave 0 (PLAN-binding-expressions §5.2 (c)): the sentinel no longer broadcasts at all.
        // A provider-dependent inline operation that answered it raises the provider capability's
        // "answered but unusable" signal — loud (the rule would report ERROR), never a verdict,
        // neither the old silent no-fire nor the all-fire this guard exists for. In a loaded rule
        // the injected `library_available() and available(domain_is_custom())` Precondition SKIPs
        // first (available() reads the same signal as "not available"), so the throw is reached
        // only by a Check evaluated without its gate, as here.
        assertThrows(UnusableProviderAnswerException.class,
                () -> eval("domain_is_custom() == false", ctx()));
        assertFalse(eval("available(domain_is_custom())", ctx()).get(0),
                "the availability gate reads the signal as not available");
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
