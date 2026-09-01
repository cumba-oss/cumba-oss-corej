package net.cumba.cdisc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import net.cumba.cdisc.core.expr.CheckExpressionParser;
import net.cumba.cdisc.core.expr.CheckToExpr;
import net.cumba.cdisc.core.expr.ExprLowering;
import net.cumba.cdisc.core.expr.ExpressionPrinter;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.model.CheckCondition;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.NextRecordRelation;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * EC-87 — the {@code relation=} kwarg of {@code has_next_corresponding_record} on its two authoring
 * surfaces (declared leaf, inline Check text) and through the Check ⇄ Expr ⇄ text round-trip.
 *
 * <p>
 * ⚠⚠ Every rejection test asserts on the <b>message</b> as well as the throw ({@code
 * KeepMissingsDeclarationTest}'s discipline): both surfaces can reject a fixture for unrelated
 * reasons and a bare {@code assertThrows} would pass on the wrong cause. The inline-surface tests
 * are the ones that matter — shipped rules inline their Checks, and {@code ExprCompiler}'s own
 * throw would only <em>degrade</em> the rule, never error it.
 * </p>
 */
class NextRecordRelationDeclarationTest
{

    private static final String SHIPPED = "not has_next_corresponding_record(SJENDTC, SJSTDTC, "
            + "keep_missings=false, ordering=SJSEQ, relation=\"<=\", within=USUBJID)";

    private static JsonNode textNode(String s)
    {
        return JsonNodeFactory.instance.textNode(s);
    }


    /** Loads a one-rule package through the production loader and returns the rule. */
    private static Rule load(String ruleJson)
    {
        try
        {
            RulePackage pkg = RulePackageLoader
                    .loadFromString("{\"rules\":{\"X-1\":" + ruleJson + "}}");
            return pkg.getRules().get("X-1");
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("bad test fixture: " + ruleJson, e);
        }
    }


    private static Rule loadCheck(String expression)
    {
        return load("{\"Core\":{\"Id\":\"X-1\"},\"Check\":{\"expression\":\""
                + expression.replace("\"", "\\\"") + "\"}}");
    }

    // ------------------------------------------------------------------ the shared holder


    @Test
    void theHolderAdmitsExactlyThreeSpellings()
    {
        assertEquals(List.of("==", "<=", ">="), NextRecordRelation.SPELLINGS);
        assertEquals(NextRecordRelation.IDENTITY, NextRecordRelation.fromSpelling("=="));
        assertEquals(-1, NextRecordRelation.fromSpelling("<=").direction());
        assertEquals(1, NextRecordRelation.fromSpelling(">=").direction());
        assertEquals(0, NextRecordRelation.IDENTITY.direction());
        // D-4: the strict and the negated spellings are deliberately NOT admitted.
        assertNull(NextRecordRelation.fromSpelling("<"));
        assertNull(NextRecordRelation.fromSpelling(">"));
        assertNull(NextRecordRelation.fromSpelling("!="));
        assertNull(NextRecordRelation.fromSpelling("=<"));
        assertNull(NextRecordRelation.fromSpelling(null));
        assertTrue(NextRecordRelation.OPERATORS.contains("has_next_corresponding_record"));
        assertTrue(
                NextRecordRelation.OPERATORS.contains("does_not_have_next_corresponding_record"));
    }

    // ------------------------------------------------------------------ the declared surface


    @Test
    void theNegatedTwinLeafRoundTripsTheRelation()
    {
        // ⚠⚠ The Q1 negation pair raises through the POSITIVE twin's name, so the allowlist must
        // carry both names (the keep_missings lesson) — this is the declared form of every
        // shipped carrier.
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("SJENDTC")
                .operator("does_not_have_next_corresponding_record").value(textNode("SJSTDTC"))
                .within(textNode("USUBJID")).ordering("SJSEQ").keepMissings(Boolean.FALSE)
                .relation("<=").build();

        Expr raised = CheckToExpr.toExpr(leaf);
        String printed = ExpressionPrinter.print(raised);
        assertEquals(SHIPPED, printed,
                "the canonical text, kwargs in TreeMap order: keep_missings, ordering, relation, within");

        CheckCondition lowered = ExprLowering.toCheckCondition(raised);
        assertTrue(lowered instanceof CheckConditionLeaf, "expected a leaf, got " + lowered);
        CheckConditionLeaf back = (CheckConditionLeaf) lowered;
        assertEquals("does_not_have_next_corresponding_record", back.getOperator());
        assertEquals("<=", back.getRelation(), "the round-trip must not lose the relation");
        assertEquals(false, back.getKeepMissings());
        assertEquals("SJSEQ", back.getOrdering());
    }


    @Test
    void textParsesLowersAndPrintsBackToItself()
    {
        // text → Expr → leaf → Expr → text: the idempotence the corpus-drift guard relies on.
        Expr parsed = CheckExpressionParser.parse(SHIPPED);
        CheckCondition lowered = ExprLowering.toCheckCondition(parsed);
        assertEquals("<=", ((CheckConditionLeaf) lowered).getRelation());
        assertEquals(SHIPPED, ExpressionPrinter.print(CheckToExpr.toExpr(lowered)));
    }


    @Test
    void aRelationOnANonNextRecordOperatorIsRejectedOnTheDeclaredSurface()
    {
        CheckConditionLeaf grouped = CheckConditionLeaf.builder().name("AVAL")
                .operator("has_multiple_values_for").value(textNode("PARAMCD"))
                .within(textNode("USUBJID")).relation("<=").build();
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> CheckToExpr.toExpr(grouped));
        assertTrue(ex.getMessage().contains("does not support relation"),
                "expected the relation rejection, got: " + ex.getMessage());

        // A row-level operator is rejected by rejectGroupFields instead — either way, loudly.
        CheckConditionLeaf rowLevel = CheckConditionLeaf.builder().name("AVAL").operator("equal_to")
                .value(textNode("1")).relation("<=").build();
        RuntimeException ex2 = assertThrows(RuntimeException.class,
                () -> CheckToExpr.toExpr(rowLevel));
        assertTrue(ex2.getMessage().contains("relation"),
                "expected a relation rejection, got: " + ex2.getMessage());
    }


    @Test
    void loweringRejectsANonStringRelation()
    {
        Expr parsed = CheckExpressionParser.parse("not has_next_corresponding_record(SJENDTC, "
                + "SJSTDTC, ordering=SJSEQ, relation=1, within=USUBJID)");
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> ExprLowering.toCheckCondition(parsed));
        assertTrue(ex.getMessage().contains("relation= must be a string literal"),
                "got: " + ex.getMessage());
    }

    // ------------------------------------------------------------------ the inline surface


    @Test
    void aValidInlineRelationLoadsAndIsNotMistakenForAnOperation()
    {
        Rule rule = loadCheck(SHIPPED);
        assertNull(rule.getLoadError(), "a valid relation must load: " + rule.getLoadError());
        assertNotNull(rule.getCheckExpr());
        assertTrue(ExpressionPrinter.print(rule.getCheckExpr()).contains("relation=\"<=\""),
                "the installed native expr must carry the kwarg");
        assertNull(loadCheck(SHIPPED.replace("\"<=\"", "\">=\"")).getLoadError());
        assertNull(loadCheck(SHIPPED.replace("\"<=\"", "\"==\"")).getLoadError());
    }


    @Test
    void theInlineSurfaceRejectsAnUnknownSpellingAsALoadError()
    {
        // ⚠⚠ The typo'd spelling that, silently degraded to identity, would keep the rule quietly
        // over-reporting with no fixture able to catch it.
        Rule rule = loadCheck(SHIPPED.replace("\"<=\"", "\"=<\""));
        assertNotNull(rule.getLoadError(), "an unknown relation must be a LOAD error");
        assertTrue(rule.getLoadError().contains("unknown `relation` `=<`"),
                "expected the unknown-spelling rejection, got: " + rule.getLoadError());
        assertTrue(rule.getLoadError().contains("[==, <=, >=]"), "names the admitted spellings");
        assertFalse(rule.getLoadError().contains("unknown operation function"),
                "⚠⚠ MISROUTING: a Check operator was handed to the OPERATION parser");
        // D-4: the strict spellings are unknown too, not silently widened.
        assertNotNull(loadCheck(SHIPPED.replace("\"<=\"", "\"<\"")).getLoadError());
        assertNotNull(loadCheck(SHIPPED.replace("\"<=\"", "\"!=\"")).getLoadError());
    }


    @Test
    void theInlineSurfaceRejectsANonStringRelation()
    {
        Rule rule = loadCheck(SHIPPED.replace("\"<=\"", "1"));
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().contains("must be a string literal"),
                "got: " + rule.getLoadError());
    }


    @Test
    void theInlineSurfaceRejectsARelationOnAnotherOperator()
    {
        Rule rule = loadCheck(
                "has_multiple_values_for(AVAL, PARAMCD, relation=\"<=\", " + "within=USUBJID)");
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().contains("not supported by `has_multiple_values_for`"),
                "got: " + rule.getLoadError());
        assertFalse(rule.getLoadError().contains("unknown operation function"),
                "⚠⚠ MISROUTING: a Check operator was handed to the OPERATION parser");
    }
    // ------------------------------------------------------------------ the inline surface,
    // unlowered


    /**
     * ⚠⚠ The deserializer lowers an authored expression to a {@code CheckConditionLeaf} whenever it
     * can, so the tests above exercise the <b>leaf</b> arm of the load-time validation. A Check
     * that cannot be lowered (here: a {@code length()} conjunct, which has no legacy leaf) stays a
     * {@code CheckConditionExpression} and reaches the <b>inline</b> arm — which must reject the
     * same three shapes with the same messages.
     */
    @Test
    void theUnloweredInlineSurfaceRejectsTheSameThreeShapes()
    {
        String unknown = loadCheck(SHIPPED.replace("\"<=\"", "\"=<\"") + " and length(SJENDTC) > 3")
                .getLoadError();
        assertNotNull(unknown, "an unknown relation on the unlowered surface must be a load error");
        assertTrue(unknown.contains("unknown `relation` `=<`"), "got: " + unknown);

        String wrongOp = loadCheck("has_multiple_values_for(AVAL, PARAMCD, relation=\"<=\", "
                + "within=USUBJID) and length(AVAL) > 3").getLoadError();
        assertNotNull(wrongOp);
        assertTrue(wrongOp.contains("not supported by `has_multiple_values_for`"),
                "got: " + wrongOp);

        String nonString = loadCheck(SHIPPED.replace("\"<=\"", "1") + " and length(SJENDTC) > 3")
                .getLoadError();
        assertNotNull(nonString);
        assertTrue(nonString.contains("must be a string literal"), "got: " + nonString);

        // And the valid spelling still loads through the same arm.
        Rule ok = loadCheck(SHIPPED + " and length(SJENDTC) > 3");
        assertNull(ok.getLoadError(), "got: " + ok.getLoadError());
        assertTrue(ok.getCheck() instanceof net.cumba.cdisc.core.model.CheckConditionExpression,
                "the fixture must have stayed UNLOWERED, or this test exercises the wrong arm: "
                        + ok.getCheck());
    }


    /**
     * The compiler's own backstop — unreachable from a loaded rule (the loader rejects first), but
     * the declared contract of {@code ExprCompiler.neighbourRelation} for any caller that bypasses
     * the loader.
     */
    @Test
    void theCompilerBackstopRejectsWhatTheLoaderRejects()
    {
        Expr unknown = CheckExpressionParser.parse(SHIPPED.replace("\"<=\"", "\"=<\""));
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> net.cumba.cdisc.core.expr.eval.ExprCompiler.compile(unknown));
        assertTrue(ex.getMessage().contains("unknown relation= `=<`"), "got: " + ex.getMessage());

        Expr nonString = CheckExpressionParser.parse(SHIPPED.replace("\"<=\"", "1"));
        RuntimeException ex2 = assertThrows(RuntimeException.class,
                () -> net.cumba.cdisc.core.expr.eval.ExprCompiler.compile(nonString));
        assertTrue(ex2.getMessage().contains("relation= must be a string literal"),
                "got: " + ex2.getMessage());
    }
}
