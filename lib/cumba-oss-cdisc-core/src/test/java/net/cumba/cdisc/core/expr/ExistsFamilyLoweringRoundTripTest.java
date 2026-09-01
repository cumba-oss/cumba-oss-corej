package net.cumba.cdisc.core.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.model.CheckCondition;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.CheckConditionNot;
import org.junit.jupiter.api.Test;

/**
 * Lowering and round-trip tests for the exists family (Phase 1 of
 * {@code plans/PLAN-extend-expression-engine.md}): the four new operators lower to like-named
 * operator-leaves, string-literal arguments lower exactly like the bareword form, and the
 * {@code Check → Expr → text → Expr → Check} round-trip preserves names and operators.
 * <p>
 * Note on canonicalization: a string-literal argument and the bareword reference are equivalent by
 * definition, so the leaf carries only the plain name — raising it back emits the <b>bareword</b>
 * form ({@code var_exists("AESTDTC")} → leaf → {@code var_exists(AESTDTC)}). The round-trip is
 * semantically lossless; only the literal spelling canonicalizes.
 * </p>
 */
class ExistsFamilyLoweringRoundTripTest
{

    private static CheckCondition lower(String source)
    {
        return ExprLowering.toCheckCondition(CheckExpressionParser.parse(source));
    }

    // ---- lowering: bareword and string-literal arguments ----------------------


    @Test
    void newPredicates_lowerToLikeNamedLeaves()
    {
        for (String op : List.of("ds_exists", "ds_not_exists"))
        {
            CheckConditionLeaf l = assertInstanceOf(CheckConditionLeaf.class, lower(op + "(DM)"));
            assertEquals("DM", l.getName(), op + " argument becomes the leaf name");
            assertEquals(op, l.getOperator(), "operator is name-preserving");
            assertNull(l.getValue(), "a unary predicate leaf carries no value");
        }
        for (String op : List.of("var_exists", "var_not_exists"))
        {
            CheckConditionLeaf l = assertInstanceOf(CheckConditionLeaf.class,
                    lower(op + "(AESTDTC)"));
            assertEquals("AESTDTC", l.getName(), op + " argument becomes the leaf name");
            assertEquals(op, l.getOperator(), "operator is name-preserving");
        }
    }


    @Test
    void stringLiteralArgument_lowersIdenticallyToBareword_wholeFamily()
    {
        for (String op : List.of("var_exists", "var_not_exists", "var_exists", "var_not_exists"))
        {
            assertEquals(lower(op + "(AESTDTC)"), lower(op + "(\"AESTDTC\")"),
                    op + ": string literal and bareword must lower to the same leaf");
        }
        for (String op : List.of("ds_exists", "ds_not_exists"))
        {
            assertEquals(lower(op + "(DM)"), lower(op + "(\"DM\")"),
                    op + ": string literal and bareword must lower to the same leaf");
        }
    }


    @Test
    void varExists_keepsFullExistsArgumentSurface()
    {
        // Dotted, filter form and ${...} substitution all lower like legacy exists.
        assertEquals("AE.AESTDY",
                assertInstanceOf(CheckConditionLeaf.class, lower("var_exists(AE.AESTDY)"))
                        .getName());
        assertEquals("DS.DSDECOD=DEATH", assertInstanceOf(CheckConditionLeaf.class,
                lower("var_exists(\"DS.DSDECOD=DEATH\")")).getName());
        assertEquals("AP${APERIOD}SDT",
                assertInstanceOf(CheckConditionLeaf.class, lower("var_exists(\"AP${APERIOD}SDT\")"))
                        .getName());
        assertEquals("--SEQ",
                assertInstanceOf(CheckConditionLeaf.class, lower("var_exists(--SEQ)")).getName(),
                "the -- prefix survives lowering untouched (resolved at eval time)");
    }


    @Test
    void dsExists_rejectsNonPlainDatasetNames_atLoweringTime()
    {
        // Mirror of the native compile-time restriction — lowering fails as loudly.
        assertThrows(ExpressionException.class, () -> lower("ds_exists(AE.AESTDY)"),
                "dotted name must be rejected");
        assertThrows(ExpressionException.class, () -> lower("ds_exists(\"DS.DSDECOD=DEATH\")"),
                "filter form must be rejected");
        assertThrows(ExpressionException.class, () -> lower("ds_exists(\"AP${APERIOD}SDT\")"),
                "${...} substitution must be rejected");
        assertThrows(ExpressionException.class, () -> lower("ds_not_exists(--DM)"),
                "-- prefix must be rejected");
    }


    @Test
    void nonStringLiteralArgument_isRejected()
    {
        assertThrows(ExpressionException.class, () -> lower("var_exists(5)"),
                "a number literal is not a name");
        assertThrows(ExpressionException.class, () -> lower("ds_exists(/DM/)"),
                "a regex literal is not a name");
    }


    @Test
    void structuralNot_staysStructural()
    {
        CheckConditionNot not = assertInstanceOf(CheckConditionNot.class,
                lower("not ds_exists(DM)"));
        CheckConditionLeaf inner = assertInstanceOf(CheckConditionLeaf.class, not.getCondition());
        assertEquals("ds_exists", inner.getOperator());
        assertEquals("DM", inner.getName());
    }

    // ---- round-trip: Check → Expr → text → Expr → Check -----------------------


    @Test
    void leafRoundTrip_preservesNameAndOperator()
    {
        for (String op : List.of("ds_exists", "ds_not_exists"))
        {
            assertLeafRoundTrips(CheckConditionLeaf.builder().name("DM").operator(op).build());
        }
        for (String op : List.of("var_exists", "var_not_exists"))
        {
            assertLeafRoundTrips(CheckConditionLeaf.builder().name("AESTDTC").operator(op).build());
        }
    }


    @Test
    void stringLiteralSource_canonicalizesToQuotedLiteralOnRaise()
    {
        // Phase 5 (plan unified-callable-surface) flipped the canonical raise form: the generator
        // prefers the quoted STRING literal for a plain name operand; parser, engine and lowering
        // accept both spellings, so semantics are identical (documented contract).
        CheckCondition lowered = lower("var_exists(\"AESTDTC\")");
        String printed = ExpressionPrinter.print(CheckToExpr.toExpr(lowered));
        assertEquals("var_exists(\"AESTDTC\")", printed,
                "raise emits the quoted-literal form for a plain name");
        assertEquals(lowered, ExprLowering.toCheckCondition(CheckExpressionParser.parse(printed)),
                "re-lowering the printed form reproduces the same leaf — semantically lossless");
    }


    private static void assertLeafRoundTrips(CheckConditionLeaf orig)
    {
        Expr raised = CheckToExpr.toExpr(orig);
        String printed = ExpressionPrinter.print(raised);
        assertTrue(printed.startsWith(orig.getOperator() + "("),
                "raised call keeps the operator name: " + printed);
        CheckConditionLeaf rt = assertInstanceOf(CheckConditionLeaf.class,
                ExprLowering.toCheckCondition(CheckExpressionParser.parse(printed)));
        assertEquals(orig.getName(), rt.getName(), "name");
        assertEquals(orig.getOperator(), rt.getOperator(), "operator");
        assertEquals(orig.getValue(), rt.getValue(), "value");
    }
}
