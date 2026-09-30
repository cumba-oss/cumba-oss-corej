package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.Map;
import net.cumba.corej.core.model.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RulePackageLoaderTest
{

    /**
     * Dedicated, self-contained fixture rather than the shipped
     * {@code rules/rules-sdtmig-3-4.json}. It carries verbatim copies of only the rules these tests
     * assert on, so edits to the production rule packages (e.g. trimming redundant scope) can no
     * longer break loader tests. Each rule body is preserved exactly as published, less the retired
     * {@code _links} block (PLAN-underscore-field-retirement).
     */
    private static final String FIXTURE = "/rules/rulepackageloader-fixture.json";

    private static RulePackage rulePackage;

    @BeforeAll
    static void loadPackage() throws Exception
    {
        try (InputStream is = RulePackageLoaderTest.class.getResourceAsStream(FIXTURE))
        {
            assertNotNull(is, "Test fixture not found on classpath: " + FIXTURE);
            rulePackage = RulePackageLoader.load(is);
        }
    }


    @Test
    void testPackageLoaded()
    {
        assertNotNull(rulePackage);
    }


    @Test
    void testRuleCount()
    {
        // The fixture carries exactly the rules referenced by the tests below.
        assertEquals(7, rulePackage.getRules().size());
    }

    private static final String CDISC_CG0151_ID = "0066ab1c-982c-42e6-96f8-e7cbec162a91";

    private static Rule cdiscCg0151()
    {
        Rule rule = rulePackage.getRules().get(CDISC_CG0151_ID);
        assertNotNull(rule, "Rule CDISC-CG0151 (" + CDISC_CG0151_ID + ") should exist");
        return rule;
    }


    private static CheckConditionExpression cdiscCg0151Check()
    {
        Rule rule = cdiscCg0151();
        return assertInstanceOf(CheckConditionExpression.class, rule.getCheck());
    }


    @Test
    void testKnownRule_CORE000351_coreFields()
    {
        Rule rule = cdiscCg0151();
        assertEquals("CDISC-CG0151", rule.getCore().getId());
        assertEquals("Published", rule.getCore().getStatus());
        assertEquals("1", rule.getCore().getVersion());
        assertEquals(CDISC_CG0151_ID, rule.getId());
    }


    @Test
    void testKnownRule_CORE000351_typeSensitivityExecutability()
    {
        Rule rule = cdiscCg0151();
        assertEquals(Sensitivity.RECORD, rule.getSensitivity());
        assertEquals(Executability.PARTIALLY_EXECUTABLE, rule.getExecutability());
    }


    @Test
    void testKnownRule_CORE000351_outcome()
    {
        Rule rule = cdiscCg0151();
        assertEquals("USUBJID is not unique within study", rule.getOutcome().getMessage());
        assertEquals(1, rule.getOutcome().getOutputVariables().size());
        assertEquals("USUBJID", rule.getOutcome().getOutputVariables().get(0));
    }


    @Test
    void testKnownRule_CORE000351_scope()
    {
        Rule rule = cdiscCg0151();
        assertNotNull(rule.getScope());
        assertNotNull(rule.getScope().getClasses());
        assertEquals(1, rule.getScope().getClasses().getInclude().size());
        assertEquals("SPECIAL PURPOSE", rule.getScope().getClasses().getInclude().get(0));
        assertNotNull(rule.getScope().getDomains());
        assertEquals(1, rule.getScope().getDomains().getInclude().size());
        assertEquals("DM", rule.getScope().getDomains().getInclude().get(0));
    }


    @Test
    void testKnownRule_CORE000351_checkLeaf()
    {
        CheckConditionExpression check = cdiscCg0151Check();
        assertEquals("not is_unique_set([USUBJID, DOMAIN])", check.source());
        assertNotNull(check.expr());
    }


    @Test
    void testKnownRule_CORE000351_authorities()
    {
        Rule rule = cdiscCg0151();
        assertNotNull(rule.getAuthorities());
        assertFalse(rule.getAuthorities().isEmpty());
        assertEquals("CDISC", rule.getAuthorities().get(0).getOrganization());
        assertFalse(rule.getAuthorities().get(0).getStandards().isEmpty());
        assertEquals("SDTMIG", rule.getAuthorities().get(0).getStandards().get(0).getName());
    }


    @Test
    void testAllRulesHaveRequiredFields()
    {
        for (Map.Entry<String, Rule> entry : rulePackage.getRules().entrySet())
        {
            Rule rule = entry.getValue();
            assertNotNull(rule.getCore(), "Core is null for rule " + entry.getKey());
            assertNotNull(rule.getCore().getId(), "Core.Id is null for rule " + entry.getKey());
            assertNotNull(rule.getCheck(), "Check is null for rule " + entry.getKey());
            assertNotNull(rule.getSensitivity(), "Sensitivity is null for rule " + entry.getKey());
        }
    }


    @Test
    void testRuleWithAFunctionBinding()
    {
        // CDISC-CG0022: an operation binding until wave 4b (PLAN-scalar-metadata-functions) made
        // variable_count a registry function — the binding is compiled, its template a string.
        Rule rule = rulePackage.getRules().get("062da4b3-0c48-4ed3-a97b-c1e92d7bcf95");
        assertNotNull(rule, "the rule should exist");
        assertNull(rule.getLoadError(), rule.getLoadError());
        assertEquals(rule.getCompiledBindings(), rule.bindingOrder(),
                "every binding is a compiled one");
        CompiledBinding binding = rule.getCompiledBindings().get(0);
        assertEquals("$VARIABLE_COUNT", binding.name());
        net.cumba.corej.core.expr.ast.Expr.Call call = (net.cumba.corej.core.expr.ast.Expr.Call) binding
                .expression();
        assertEquals("variable_count", call.name());
        assertEquals(
                new net.cumba.corej.core.expr.ast.Expr.Lit(
                        net.cumba.corej.core.expr.ast.Expr.LitKind.STRING, "--LNKGRP"),
                call.args().get(0));
    }


    @Test
    void testRuleWithBindingDomain()
    {
        // CDISC-CG0148 has a binding with domain "EX"
        Rule rule = rulePackage.getRules().get("4162b46f-9e19-41ff-ab42-9a26ee5b37f9");
        assertNotNull(rule, "CDISC-CG0148 should exist");
        assertEquals("CDISC-CG0148", rule.getCore().getId());
        // (distinct is a registry function since runbook W7: the binding is a COMPILED one, and
        // its domain= is a keyword of the call.)
        assertEquals(rule.getCompiledBindings(), rule.bindingOrder());
        CompiledBinding binding = rule.getCompiledBindings().stream()
                .filter(b -> "$usubjids_in_ex".equals(b.name())).findFirst().orElseThrow();
        net.cumba.corej.core.expr.ast.Expr.Call call = (net.cumba.corej.core.expr.ast.Expr.Call) binding
                .expression();
        assertEquals("distinct", call.name());
        assertEquals("USUBJID",
                ((net.cumba.corej.core.expr.ast.Expr.Ref) call.args().get(0)).name());
        assertEquals("EX",
                ((net.cumba.corej.core.expr.ast.Expr.Lit) call.kwargs().get("domain")).value());
    }


    @Test
    void testRuleWithMatchDatasets()
    {
        Rule rule = rulePackage.getRules().get("029342ac-3023-43f2-8d13-444142f50383");
        assertNotNull(rule, "Rule with match datasets should exist");
        assertNotNull(rule.getMatchDatasets());
        assertFalse(rule.getMatchDatasets().isEmpty());

        MatchDataset md = rule.getMatchDatasets().get(0);
        assertEquals("DS", md.getName());
        assertNotNull(md.getKeys());
        assertTrue(md.getKeys().contains("USUBJID"));
    }


    @Test
    void testMatchDatasetWithChild()
    {
        Rule rule = rulePackage.getRules().get("206f189a-42aa-4bce-b2cf-e9d3a6d6651f");
        assertNotNull(rule);
        assertNotNull(rule.getMatchDatasets());

        boolean hasChild = rule.getMatchDatasets().stream()
                .anyMatch(md -> Boolean.TRUE.equals(md.getChild()));
        assertTrue(hasChild, "Should have a match dataset with Child=true");
    }


    @Test
    void testNestedCheckConditions()
    {
        // Rule 18eac328 has any nested inside all
        Rule rule = rulePackage.getRules().get("18eac328-00a4-419d-a128-764867659acf");
        assertNotNull(rule);
        assertInstanceOf(CheckConditionAll.class, rule.getCheck());
        CheckConditionAll all = (CheckConditionAll) rule.getCheck();

        boolean hasNestedAny = all.getConditions().stream()
                .anyMatch(CheckConditionAny.class::isInstance);
        assertTrue(hasNestedAny, "Should have nested any condition");
    }


    @Test
    void testRuleWithGroupingVariables()
    {
        Rule rule = rulePackage.getRules().get("72fc8a45-adcc-423b-b782-ffaabf9535e8");
        assertNotNull(rule);
        assertNotNull(rule.getGroupingVariables());
        assertFalse(rule.getGroupingVariables().isEmpty());
    }


    @Test
    void testAuthorityCitations()
    {
        Rule rule = rulePackage.getRules().get("0066ab1c-982c-42e6-96f8-e7cbec162a91");
        AuthorityStandard std = rule.getAuthorities().get(0).getStandards().get(0);
        assertNotNull(std.getReferences());
        assertFalse(std.getReferences().isEmpty());

        Reference ref = std.getReferences().get(0);
        assertNotNull(ref.getRuleIdentifier());
        assertEquals("CG0151", ref.getRuleIdentifier().getId());
        assertNotNull(ref.getCitations());
        assertFalse(ref.getCitations().isEmpty());
        assertNotNull(ref.getCitations().get(0).getCitedGuidance());
        assertNotNull(ref.getCitations().get(0).getDocument());
    }


    /** A JSON-null rule value (e.g. the editor wrapping a bare {@code null}) must not NPE. */
    @Test
    void testNullRuleValueDoesNotThrow() throws Exception
    {
        RulePackage pkg = assertDoesNotThrow(
                () -> RulePackageLoader.loadFromString("{\"rules\":{\"x\":null}}"));
        assertNull(pkg.getRules().get("x"));
    }


    /**
     * A JSON-null element inside a Check {@code all}/{@code any} array is dropped by the
     * deserializer (not retained as a null condition), so no downstream walker ever sees one —
     * pinned here on the sibling case, where a real condition survives beside the dropped null.
     *
     * <p>
     * ⚠ <b>RE-EXPECTED under D121 (H3, 2026-09-17) — this test used to pin {@code all:[null]}
     * loading CLEAN into an EMPTY composite.</b> That empty {@code all} compiles to the vacuous
     * truth and fires on every row, which is exactly the silent over-reporting the composite
     * grammar now rejects at load ({@code CheckConditionDeserializer.compositeList}). The null-DROP
     * half of the old pin survives above; the empty-RESULT half was the defect.
     * </p>
     */
    @Test
    void testNullCheckConditionElementIsDroppedBesideItsSiblings() throws Exception
    {
        RulePackage pkg = assertDoesNotThrow(() -> RulePackageLoader.loadFromString(
                "{\"rules\":{\"x\":{\"Check\":{\"all\":[null,{\"expression\":\"AGE > 18\"}]}}}}"));
        Rule rule = pkg.getRules().get("x");
        assertInstanceOf(CheckConditionAll.class, rule.getCheck());
        assertEquals(1, ((CheckConditionAll) rule.getCheck()).getConditions().size(),
                "the null element is dropped, the real condition survives");
    }


    /** The all-null composite itself is a LOUD load error now, never an empty vacuous truth. */
    @Test
    void testAllNullCheckCompositeIsRejectedLoudly()
    {
        Exception ex = assertThrows(Exception.class, () -> RulePackageLoader
                .loadFromString("{\"rules\":{\"x\":{\"Check\":{\"all\":[null]}}}}"));
        assertTrue(ex.getMessage().contains("holds no conditions"),
                "an all-null `all:` empties to the vacuous truth and must be rejected: "
                        + ex.getMessage());

        Exception nested = assertThrows(Exception.class, () -> RulePackageLoader.loadFromString(
                "{\"rules\":{\"x\":{\"Check\":{\"any\":[null,{\"all\":[null]}]}}}}"));
        assertTrue(nested.getMessage().contains("holds no conditions"),
                "a nested empty composite is rejected the same way: " + nested.getMessage());
    }


    /**
     * Neither empty input nor the JSON literal {@code null} may yield a {@code null} package — both
     * are an {@link java.io.IOException}. Relocated here when the per-expression
     * {@code identFallback} opt-in (and its dedicated test class) was removed; the contract is
     * independent of that feature and must stay pinned. Empty input is raised by Jackson itself
     * (end-of-input); the JSON {@code null} literal is caught by the loader's own guard.
     */
    @Test
    void emptyInputAndJsonNullThrowIoException()
    {
        assertThrows(java.io.IOException.class, () -> RulePackageLoader.loadFromString(""));
        assertThrows(java.io.IOException.class, () -> RulePackageLoader.loadFromString("null"));
    }


    /**
     * Combined review of runbook W2–W8 (W8, filed pre-existing): a broadcast-shaped Precondition
     * gate the compiler REFUSES used to leave {@code getPreconditionExpr()} null — "not fully
     * resolvable ⇒ continue" — so the gate was silently ignored and the rule ran ungated. It is a
     * load error now, naming the compiler's reason. Pre-fix: loadError null. A supported gate is
     * the control.
     */
    @Test
    void aRefusedBroadcastPreconditionGateIsALoadErrorNotSilentlyIgnored() throws Exception
    {
        Rule refused = loadOne("PRE-REFUSED");
        RulePackageLoader.installEngineInternalPrecondition(refused,
                precondition("available(__no_such_function__())"));
        assertNull(refused.getPreconditionExpr(), "the refused gate is not installed");
        String error = refused.getLoadError();
        assertNotNull(error, "a refused gate must be loud, never silently ignored");
        assertTrue(error.contains("Precondition gate") && error.contains("no native function"),
                error);

        Rule supported = loadOne("PRE-OK");
        RulePackageLoader.installEngineInternalPrecondition(supported,
                precondition("var_exists(\"DOMAIN\")"));
        assertNotNull(supported.getPreconditionExpr(), "a supported broadcast gate is installed");
        assertNull(supported.getLoadError(), supported.getLoadError());
    }


    private static Rule loadOne(String id) throws java.io.IOException
    {
        Rule rule = RulePackageLoader.loadFromString("{\"rules\":{\"x\":{\"Core\":{\"Id\":\"" + id
                + "\"},\"Check\":{\"expression\":\"AETERM == \\\"X\\\"\"},"
                + "\"Outcome\":{\"Message\":\"m\"}}}}").getRules().get("x");
        assertNotNull(rule, "the rule parses");
        assertNull(rule.getLoadError(), rule.getLoadError());
        return rule;
    }


    private static CheckCondition precondition(String expression) throws java.io.IOException
    {
        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                "{\"expression\":\"" + expression.replace("\"", "\\\"") + "\"}",
                CheckCondition.class);
    }

}
