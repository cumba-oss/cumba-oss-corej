package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * ⭐ Phase 7b of {@code PLAN-typed-expression-engine.md} (owner rulings 2026-09-17) — the
 * {@code Bindings:} authoring surface and its loud rejections. Three rulings, pinned together:
 *
 * <ol>
 * <li>{@code Operations:} became {@code Bindings:} and the entry key {@code id:} became
 * {@code name:} — the retired spellings fail LOUD at load, naming the replacement (the same
 * contract phase 7d gave the retired operator-leaf Check);</li>
 * <li>the FIELD FORM of a binding ({@code operator:} + parameter fields) is retired entirely as an
 * authoring surface — only {@code name:} + {@code expression:} bind;</li>
 * <li>no sibling field may shadow an expression parameter: measured pre-fix, a sibling
 * {@code level: "term"} beside an expression that did not name it was <b>silently discarded</b>
 * (the FDA-SD1078 under-report shape). Now every stray key on a binding is a load failure.</li>
 * </ol>
 */
class BindingAuthoringSurfaceTest
{

    private static RulePackage load(String ruleBody) throws IOException
    {
        return RulePackageLoader.loadFromString("{\"rules\":{\"x\":" + ruleBody + "}}");
    }


    private static Exception loadFails(String ruleBody)
    {
        return assertThrows(Exception.class, () -> load(ruleBody));
    }

    // -----------------------------------------------------------------------
    // Ruling 1 — the retired spellings fail loud, naming the replacement
    // -----------------------------------------------------------------------


    @Test
    void retiredOperationsBlockIsRejectedNamingBindings()
    {
        Exception ex = loadFails("""
                {"Core":{"Id":"T-BS1"},
                 "Check":{"expression":"$n > 5"},
                 "Operations":[{"name":"$n","expression":"record_count()"}]}""");
        assertTrue(ex.getMessage().contains("`Operations:` block is retired"), ex.getMessage());
        assertTrue(ex.getMessage().contains("`Bindings:`"),
                "the rejection must name the replacement: " + ex.getMessage());
    }


    @Test
    void retiredIdKeyIsRejectedNamingName()
    {
        Exception ex = loadFails("""
                {"Core":{"Id":"T-BS2"},
                 "Check":{"expression":"$n > 5"},
                 "Bindings":[{"id":"$n","expression":"record_count()"}]}""");
        assertTrue(ex.getMessage().contains("`id:` is retired"), ex.getMessage());
        assertTrue(ex.getMessage().contains("`name:`"),
                "the rejection must name the replacement: " + ex.getMessage());
    }

    // -----------------------------------------------------------------------
    // Ruling 2 — the field form is retired entirely
    // -----------------------------------------------------------------------


    @Test
    void fieldFormOperatorIsRejectedNamingTheExpressionForm()
    {
        Exception ex = loadFails("""
                {"Core":{"Id":"T-BS3"},
                 "Check":{"expression":"$n > 5"},
                 "Bindings":[{"name":"$n","operator":"record_count","group":["USUBJID"]}]}""");
        assertTrue(ex.getMessage().contains("field form of a binding"), ex.getMessage());
        assertTrue(ex.getMessage().contains("`expression:`"),
                "the rejection must name the replacement: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("record_count"),
                "the rejection must name the offending operator: " + ex.getMessage());
    }

    // -----------------------------------------------------------------------
    // Ruling 3 — no sibling field beside an expression, on either surface
    // -----------------------------------------------------------------------


    @Test
    void siblingParameterFieldBesideAnExpressionIsRejectedNamingTheKey()
    {
        // Pre-fix this was the measured silent drop: `level` bound, then normalise() replaced the
        // whole record with the parsed expression's fields and the declaration vanished — no
        // error, no warning, `level=null` at execution. Until runbook W8 an operation-parameter
        // key got its own "keyword argument" message; since W8 it gets the generic one.
        Exception ex = loadFails("""
                {"Core":{"Id":"T-BS4"},
                 "Check":{"expression":"\\"X\\" in $terms"},
                 "Bindings":[{"name":"$terms",
                    "expression":"distinct(USUBJID)",
                    "delimiter":","}]}""");
        assertTrue(ex.getMessage().contains("found `delimiter:`"), ex.getMessage());
        assertTrue(ex.getMessage().contains("only `name:` and `expression:`"),
                "the rejection must name the binding's only keys: " + ex.getMessage());
    }


    @Test
    void unknownBindingKeyIsRejected()
    {
        Exception ex = loadFails("""
                {"Core":{"Id":"T-BS5"},
                 "Check":{"expression":"$n > 5"},
                 "Bindings":[{"name":"$n","expression":"record_count()","banana":1}]}""");
        assertTrue(ex.getMessage().contains("`banana:`"), ex.getMessage());
        assertTrue(ex.getMessage().contains("only `name:` and `expression:`"), ex.getMessage());
    }

    // -----------------------------------------------------------------------
    // The replacement surface itself
    // -----------------------------------------------------------------------


    @Test
    void bindingWithoutExpressionIsALoadError() throws IOException
    {
        RulePackage pkg = load("""
                {"Core":{"Id":"T-BS6"},
                 "Check":{"expression":"$n > 5"},
                 "Bindings":[{"name":"$n"}]}""");
        Rule rule = pkg.getRules().values().iterator().next();
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().contains("declares no `expression:`"), rule.getLoadError());
    }


    @Test
    void theNewSurfaceLoadsAndMaterialises() throws IOException
    {
        RulePackage pkg = load("""
                {"Core":{"Id":"T-BS7"},
                 "Check":{"expression":"AESEQ not in $n"},
                 "Bindings":[{"name":"$n","expression":"distinct(AESEQ, group=[USUBJID])"}]}""");
        Rule rule = pkg.getRules().values().iterator().next();
        assertNull(rule.getLoadError());
        // (since runbook W8 every binding is a compiled one; bindingOrder() is that one list.)
        assertEquals(1, rule.bindingOrder().size());
        assertEquals(1, rule.getCompiledBindings().size());
        assertEquals("$n", rule.getCompiledBindings().get(0).name());
        assertEquals("distinct(AESEQ, group=[USUBJID])", net.cumba.corej.core.expr.ExpressionPrinter
                .print(rule.getCompiledBindings().get(0).expression()));
    }

}
