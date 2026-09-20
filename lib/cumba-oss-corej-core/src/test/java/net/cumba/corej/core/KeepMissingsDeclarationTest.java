package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.exec.GroupKeyPolicy;
import net.cumba.corej.core.expr.RuleDefinitionException;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.convert.OperationExpressionParser;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.Operation;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * Phase 3 of {@code PLAN-grouping-missing-key-semantics}: the engine learns to <b>read</b>
 * {@code keep_missings} on all four authoring surfaces, and the default stays today's per-site
 * behaviour so no finding moves.
 *
 * <p>
 * ⚠⚠ Every rejection test asserts on the <b>message</b> as well as the throw, because both surfaces
 * can reject the same fixture for unrelated reasons and a bare {@code assertThrows} would pass on
 * the wrong cause. Each was neutered and watched to fail.
 * </p>
 */
class KeepMissingsDeclarationTest
{

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

    // ------------------------------------------------------------------
    // Surface 1 — the rule-level Grouping: block
    // ------------------------------------------------------------------


    @Test
    void bothRuleShapesResolveToTheSameGroupingKey()
    {
        Rule flat = load("{\"Core\":{\"Id\":\"X-1\"},\"Sensitivity\":\"Group\","
                + "\"Grouping_Variables\":[\"USUBJID\",\"PARAMCD\"],"
                + "\"Check\":{\"expression\":\"AVAL > 1\"}}");
        Rule block = load("{\"Core\":{\"Id\":\"X-1\"},\"Sensitivity\":\"Group\","
                + "\"Grouping\":{\"Variables\":[\"USUBJID\",\"PARAMCD\"]},"
                + "\"Check\":{\"expression\":\"AVAL > 1\"}}");

        assertNull(flat.getLoadError());
        assertNull(block.getLoadError());
        assertEquals(List.of("USUBJID", "PARAMCD"), flat.effectiveGroupingVariables());
        assertEquals(flat.effectiveGroupingVariables(), block.effectiveGroupingVariables(),
                "the block and the flat form must resolve to one grouping key");
        // Both are silent on the disposition, so both take the engine default.
        assertNull(flat.groupingKeepMissings());
        assertNull(block.groupingKeepMissings());
    }


    @Test
    void theBlockCarriesTheDeclaredDisposition()
    {
        Rule kept = load("{\"Core\":{\"Id\":\"X-1\"},\"Sensitivity\":\"Group\","
                + "\"Grouping\":{\"Variables\":[\"USUBJID\"],\"keep_missings\":true},"
                + "\"Check\":{\"expression\":\"AVAL > 1\"}}");
        assertNull(kept.getLoadError());
        assertEquals(true, kept.groupingKeepMissings());

        Rule dropped = load("{\"Core\":{\"Id\":\"X-1\"},\"Sensitivity\":\"Group\","
                + "\"Grouping\":{\"Variables\":[\"USUBJID\"],\"keep_missings\":false},"
                + "\"Check\":{\"expression\":\"AVAL > 1\"}}");
        assertNull(dropped.getLoadError());
        assertEquals(false, dropped.groupingKeepMissings());
    }


    @Test
    void theRuleLevelDefaultIsStillDiscard()
    {
        // The whole "no findings move" claim rests on this: a silent rule keeps the shipped
        // disposition, which for the rule-level grouping surface is DROP.
        Rule silent = load("{\"Core\":{\"Id\":\"X-1\"},\"Sensitivity\":\"Group\","
                + "\"Grouping\":{\"Variables\":[\"USUBJID\"]},"
                + "\"Check\":{\"expression\":\"AVAL > 1\"}}");
        GroupKeyPolicy effective = GroupKeyPolicy.DROP_MISSING_KEYS
                .withDeclared(silent.groupingKeepMissings());
        assertEquals(GroupKeyPolicy.DROP_MISSING_KEYS, effective);
        assertFalse(effective.keepMissings(), "the rule-level default must still discard");
    }


    @Test
    void declaringBothGroupingShapesIsALoadError()
    {
        Rule rule = load("{\"Core\":{\"Id\":\"X-1\"},\"Sensitivity\":\"Group\","
                + "\"Grouping_Variables\":[\"USUBJID\"],"
                + "\"Grouping\":{\"Variables\":[\"PARAMCD\"]},"
                + "\"Check\":{\"expression\":\"AVAL > 1\"}}");
        assertNotNull(rule.getLoadError(), "two disagreeing grouping keys must not load silently");
        assertTrue(rule.getLoadError().contains("both `Grouping:`"),
                "expected the both-shapes rejection, got: " + rule.getLoadError());
    }


    @Test
    void keepMissingsWithNoVariablesIsALoadError()
    {
        Rule rule = load("{\"Core\":{\"Id\":\"X-1\"}," + "\"Grouping\":{\"keep_missings\":true},"
                + "\"Check\":{\"expression\":\"AVAL > 1\"}}");
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().contains("`Grouping.keep_missings` requires"),
                "expected the orphan-parameter rejection, got: " + rule.getLoadError());
    }

    // ------------------------------------------------------------------
    // Surface 2 — Operations[].group:
    // ------------------------------------------------------------------


    @Test
    void aGroupedOperationAcceptsTheDeclaredDisposition()
    {
        Operation op = OperationExpressionParser.fromCall(
                (Expr.Call) parse("record_count(group=[USUBJID], keep_missings=true)"), "op1");
        assertEquals(true, op.getKeepMissings());
    }


    @Test
    void theInlineOperationSurfaceRejectsANonBooleanDisposition()
    {
        RuleDefinitionException ex = assertThrows(RuleDefinitionException.class,
                () -> OperationExpressionParser.fromCall(
                        (Expr.Call) parse("record_count(group=[USUBJID], keep_missings=\"yes\")"),
                        "op1"));
        assertTrue(ex.getMessage().contains("keep_missings must be a boolean literal"),
                "expected the boolean-literal rejection, got: " + ex.getMessage());
    }


    @Test
    void aDispositionOnANonGroupingOperationIsRejected()
    {
        Operation op = new Operation();
        op.setOperator("variable_count");
        op.setKeepMissings(Boolean.TRUE);
        RuleDefinitionException ex = assertThrows(RuleDefinitionException.class,
                () -> OperationExpressionParser.validateKeepMissings(op));
        assertTrue(ex.getMessage().contains("not supported by operation"),
                "expected the operator rejection, got: " + ex.getMessage());
    }


    @Test
    void aDispositionWithNoGroupKeyIsRejected()
    {
        // ⚠ Fixture shaped so ONLY the empty-group check can reject it: record_count IS a consuming
        // operator, so the operator allowlist above cannot be what fires here.
        Operation op = new Operation();
        op.setOperator("record_count");
        op.setKeepMissings(Boolean.TRUE);
        RuleDefinitionException ex = assertThrows(RuleDefinitionException.class,
                () -> OperationExpressionParser.validateKeepMissings(op));
        assertTrue(ex.getMessage().contains("requires a non-empty"),
                "expected the empty-group rejection, got: " + ex.getMessage());
    }


    @Test
    void aSilentOperationValidatesAndKeepsTheEngineDefault()
    {
        Operation op = new Operation();
        op.setOperator("variable_count");
        OperationExpressionParser.validateKeepMissings(op);
        assertNull(op.getKeepMissings());
    }


    @Test
    void theFieldFormOperationSurfaceIsGatedToo()
    {
        // A FIELD-FORM operation never passes through fromCall, so Jackson would bind the parameter
        // on a non-consuming operator without complaint if the loader did not re-run the guard.
        Rule rule = load("{\"Core\":{\"Id\":\"X-1\"},"
                + "\"Bindings\":[{\"name\": \"o1\", \"expression\": \"variable_count(AVAL, keep_missings=true)\"}],"
                + "\"Check\":{\"expression\":\"AVAL > 1\"}}");
        assertNotNull(rule.getLoadError(),
                "the field form must reach the same rejection as the call form");
        assertTrue(rule.getLoadError().contains("keep_missings"),
                "expected a keep_missings rejection, got: " + rule.getLoadError());
    }

    // ------------------------------------------------------------------
    // The inline Check surface (the one shipped rules execute through) —
    // the retired declared-leaf surface's tests went with the leaf model
    // (phase 7d, D121).
    // ------------------------------------------------------------------


    @Test
    void theInlineCheckSurfaceRejectsANonBooleanDisposition()
    {
        // ⚠⚠ This is the surface shipped rules actually execute through. ExprCompiler's own throw
        // would only DEGRADE the rule; the loader must turn it into a load error.
        Rule rule = load("{\"Core\":{\"Id\":\"X-1\"},\"Check\":{\"expression\":"
                + "\"has_multiple_values_for(AVAL, PARAMCD, within=USUBJID,"
                + " keep_missings=\\\"yes\\\")\"}}");
        assertNotNull(rule.getLoadError(),
                "a non-boolean inline disposition must be a load error, not a degradation");
        assertTrue(rule.getLoadError().contains("must be a boolean literal"),
                "expected the boolean-literal rejection, got: " + rule.getLoadError());
        assertFalse(rule.getLoadError().contains("unknown operation function"),
                "⚠⚠ MISROUTING: a Check operator was handed to the OPERATION parser. See"
                        + " aValidInlineCheckOperatorDispositionIsNotMistakenForAnOperation.");
    }


    @Test
    void theInlineCheckSurfaceRejectsANonGroupingOperator()
    {
        Rule rule = load("{\"Core\":{\"Id\":\"X-1\"},\"Check\":{\"expression\":"
                + "\"length(AVAL, keep_missings=true)\"}}");
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().contains("not supported by `length`"),
                "expected the group-aware-operator rejection, got: " + rule.getLoadError());
        assertFalse(rule.getLoadError().contains("unknown operation function"),
                "⚠⚠ MISROUTING: a Check operator was handed to the OPERATION parser.");
    }


    /**
     * ⚠⚠ The regression this parameter's shape made possible. {@code keep_missings} is the first
     * parameter to appear on <b>both</b> the operation surface and the Check-operator surface, so
     * it cannot be routed through {@code OperationExpressionParser.fromCall} the way
     * {@code missing_values} is — {@code fromCall} rejects any name that is not an
     * {@code OperationType}, and an inline {@code has_multiple_values_for(...)} would become a
     * bogus "unknown operation function" load error on a perfectly valid rule.
     */
    @Test
    void aValidInlineCheckOperatorDispositionIsNotMistakenForAnOperation()
    {
        Rule rule = load("{\"Core\":{\"Id\":\"X-1\"},\"Check\":{\"expression\":"
                + "\"has_multiple_values_for(AVAL, PARAMCD, within=USUBJID,"
                + " keep_missings=false)\"}}");
        assertNull(rule.getLoadError(),
                "a valid Check-operator keep_missings must load cleanly, not be routed through the"
                        + " operation parser: " + rule.getLoadError());
        // ⚠ The load-error assertion alone is WEAK here and was measured to be so: on this input
        // the misrouting branch is never reached, so the assertion passes either way. The
        // misrouting itself is pinned by the two rejection tests above (they would report "unknown
        // operation function" instead of a keep_missings message). What this test adds is the
        // end-to-end survival of the declaration through the real loader:
        //
        // ⭐ Phase 7 (PLAN-typed-expression-engine): this used to assert the Check had been LOWERED
        // to a CheckConditionLeaf and read the disposition off the leaf's getKeepMissings(). The
        // lowering is gone, so the assertion is re-pointed at the property rather than the
        // representation (D109a): the Check keeps its expression, and the disposition rides in the
        // call's kwargs — which is where the compiler reads it from, and always was.
        CheckConditionExpression check = assertInstanceOf(CheckConditionExpression.class,
                rule.getCheck(), "the valid form keeps its expression");
        Expr.Call call = assertInstanceOf(Expr.Call.class, check.expr(),
                "the whole Check is a call");
        assertEquals(new Expr.Lit(Expr.LitKind.BOOL, false), call.kwargs().get("keep_missings"),
                "the disposition must survive the loader");
    }


    @Test
    void aValidInlineOperationDispositionStillLoads()
    {
        Rule rule = load("{\"Core\":{\"Id\":\"X-1\"},\"Check\":{\"expression\":"
                + "\"AVAL > record_count(group=[USUBJID], keep_missings=true)\"}}");
        assertNull(rule.getLoadError(),
                "a valid inline operation keep_missings must load: " + rule.getLoadError());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------


    private static Expr parse(String expression)
    {
        return net.cumba.corej.core.expr.CheckExpressionParser.parse(expression);
    }

}
