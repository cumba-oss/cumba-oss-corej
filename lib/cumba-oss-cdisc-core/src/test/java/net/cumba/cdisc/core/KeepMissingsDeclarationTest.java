package net.cumba.cdisc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.cdisc.core.exec.GroupKeyPolicy;
import net.cumba.cdisc.core.expr.CheckToExpr;
import net.cumba.cdisc.core.expr.ExprLowering;
import net.cumba.cdisc.core.expr.ExpressionPrinter;
import net.cumba.cdisc.core.expr.RuleDefinitionException;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.expr.convert.OperationExpressionParser;
import net.cumba.cdisc.core.model.CheckCondition;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
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
                + "\"Operations\":[{\"id\":\"o1\",\"operator\":\"variable_count\","
                + "\"name\":\"AVAL\",\"keep_missings\":true}],"
                + "\"Check\":{\"expression\":\"AVAL > 1\"}}");
        assertNotNull(rule.getLoadError(),
                "the field form must reach the same rejection as the call form");
        assertTrue(rule.getLoadError().contains("keep_missings"),
                "expected a keep_missings rejection, got: " + rule.getLoadError());
    }

    // ------------------------------------------------------------------
    // Surfaces 3 and 4 — the Check leaf (within: and the array value:)
    // ------------------------------------------------------------------


    @Test
    void theCheckLeafRoundTripsTheDisposition()
    {
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("AVAL")
                .operator("has_multiple_values_for").value(textNode("PARAMCD"))
                .within(textNode("USUBJID")).keepMissings(Boolean.FALSE).build();

        Expr raised = CheckToExpr.toExpr(leaf);
        String printed = ExpressionPrinter.print(raised);
        assertTrue(printed.contains("keep_missings=false"),
                "the declared surface must emit the kwarg, got: " + printed);

        CheckCondition lowered = ExprLowering.toCheckCondition(raised);
        assertTrue(lowered instanceof CheckConditionLeaf, "expected a leaf, got " + lowered);
        assertEquals(false, ((CheckConditionLeaf) lowered).getKeepMissings(),
                "the round-trip must not lose the disposition");
    }


    @Test
    void theSortedByLeafRoundTripsTheDisposition()
    {
        // target_is_not_sorted_by travels its own raise/lower path, so it needs its own pin.
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("LBSEQ")
                .operator("target_is_not_sorted_by").value(sortDescriptors())
                .within(textNode("USUBJID")).keepMissings(Boolean.FALSE).build();

        Expr raised = CheckToExpr.toExpr(leaf);
        assertTrue(ExpressionPrinter.print(raised).contains("keep_missings=false"),
                "the ordering operator must emit the kwarg too");
        CheckCondition lowered = ExprLowering.toCheckCondition(raised);
        assertEquals(false, ((CheckConditionLeaf) lowered).getKeepMissings());
    }


    @Test
    void theNegatedTwinLeafRoundTripsTheDisposition()
    {
        // ⚠⚠ The Q1 negation pairs raise through their POSITIVE twin's name —
        // does_not_have_next_corresponding_record calls
        // functionLeaf("has_next_corresponding_record", …) — and functionLeaf's allowlist guard
        // tests the name it was CALLED WITH. Listing only the negative name made this valid
        // declaration throw "operator 'has_next_corresponding_record' does not support
        // keep_missings", naming an operator the rule author never wrote, and left the parameter
        // unauthorable on the six shipped rules that use this operator.
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("SEENDTC")
                .operator("does_not_have_next_corresponding_record").value(textNode("SESTDTC"))
                .within(textNode("USUBJID")).ordering("SESEQ").keepMissings(Boolean.FALSE).build();

        Expr raised = CheckToExpr.toExpr(leaf);
        assertTrue(ExpressionPrinter.print(raised).contains("keep_missings=false"),
                "the negated twin must emit the kwarg, got: " + ExpressionPrinter.print(raised));

        CheckCondition lowered = ExprLowering.toCheckCondition(raised);
        assertTrue(lowered instanceof CheckConditionLeaf, "expected a leaf, got " + lowered);
        assertEquals("does_not_have_next_corresponding_record",
                ((CheckConditionLeaf) lowered).getOperator(),
                "the round-trip must restore the NEGATIVE operator, not the positive twin");
        assertEquals(false, ((CheckConditionLeaf) lowered).getKeepMissings(),
                "the round-trip must not lose the disposition");
    }


    @Test
    void aDispositionOnANonGroupingOperatorIsRejectedOnTheDeclaredSurface()
    {
        // ⚠ `equal_to` is a row-level operator, so it is rejected by rejectGroupFields rather than
        // by the functionLeaf allowlist — either way it must not load silently.
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("AVAL").operator("equal_to")
                .value(textNode("1")).keepMissings(Boolean.TRUE).build();
        RuntimeException ex = assertThrows(RuntimeException.class, () -> CheckToExpr.toExpr(leaf));
        assertTrue(ex.getMessage().contains("keep_missings"),
                "expected a keep_missings rejection, got: " + ex.getMessage());
    }


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
        // ⚠ The load-error assertion alone is WEAK here and was measured to be so: a valid
        // expression is lowered to a leaf before the inline walk runs, so the misrouting branch is
        // never reached on this input and the assertion passes either way. The misrouting itself is
        // pinned by the two rejection tests above (they would report "unknown operation function"
        // instead of a keep_missings message). What this test adds is the end-to-end survival of
        // the declaration through the real loader:
        assertTrue(rule.getCheck() instanceof CheckConditionLeaf,
                "the valid form must lower to a leaf, got: " + rule.getCheck());
        assertEquals(false, ((CheckConditionLeaf) rule.getCheck()).getKeepMissings(),
                "the disposition must survive the loader's expression lowering");
    }


    @Test
    void aValidInlineOperationDispositionStillLoads()
    {
        Rule rule = load("{\"Core\":{\"Id\":\"X-1\"},\"Check\":{\"expression\":"
                + "\"AVAL > record_count(group=[USUBJID], keep_missings=true)\"}}");
        assertNull(rule.getLoadError(),
                "a valid inline operation keep_missings must load: " + rule.getLoadError());
    }


    /**
     * ⚠⚠ The declaration must survive a leaf <b>rebuild</b>. {@code CheckConditionTransformer}
     * reconstructs a leaf field-by-field whenever a {@code --} wildcard in the name or value needs
     * resolving, and a field it forgets is erased silently — no error, no log, just a rule running
     * under a disposition its author did not choose.
     *
     * <p>
     * ⚠ The fixture forces the rebuild: the name carries {@code --} so the early "no change" return
     * cannot be taken. Without that this test would pass on the identity path and prove nothing.
     * </p>
     */
    @Test
    void theDispositionSurvivesWildcardResolution()
    {
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("--TESTCD")
                .operator("has_multiple_values_for").value(textNode("PARAMCD"))
                .within(textNode("USUBJID")).keepMissings(Boolean.FALSE).build();

        CheckCondition resolved = net.cumba.cdisc.core.exec.CheckConditionTransformer
                .resolvePrefixes(leaf, "LB");

        assertTrue(resolved instanceof CheckConditionLeaf, "expected a leaf, got " + resolved);
        CheckConditionLeaf out = (CheckConditionLeaf) resolved;
        assertEquals("LBTESTCD", out.getName(), "the fixture must actually trigger the rebuild");
        assertEquals(false, out.getKeepMissings(),
                "the grouping-key disposition must survive the field-by-field rebuild");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------


    private static Expr parse(String expression)
    {
        return net.cumba.cdisc.core.expr.CheckExpressionParser.parse(expression);
    }


    private static com.fasterxml.jackson.databind.JsonNode textNode(String v)
    {
        return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.textNode(v);
    }


    private static com.fasterxml.jackson.databind.JsonNode sortDescriptors()
    {
        com.fasterxml.jackson.databind.node.ArrayNode arr = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                .arrayNode();
        com.fasterxml.jackson.databind.node.ObjectNode o = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                .objectNode();
        o.put("name", "LBSEQ");
        o.put("sort_order", "asc");
        o.put("null_position", "last");
        arr.add(o);
        return arr;
    }

}
