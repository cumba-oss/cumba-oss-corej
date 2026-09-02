package net.cumba.cdisc.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.Map;
import net.cumba.cdisc.core.model.*;
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

    private static final String CORE_000351_ID = "0066ab1c-982c-42e6-96f8-e7cbec162a91";

    private static Rule core000351()
    {
        Rule rule = rulePackage.getRules().get(CORE_000351_ID);
        assertNotNull(rule, "Rule CORE-000351 (" + CORE_000351_ID + ") should exist");
        return rule;
    }


    private static CheckConditionLeaf core000351Leaf()
    {
        Rule rule = core000351();
        assertInstanceOf(CheckConditionAll.class, rule.getCheck());
        CheckConditionAll all = (CheckConditionAll) rule.getCheck();
        assertEquals(1, all.getConditions().size());
        assertInstanceOf(CheckConditionLeaf.class, all.getConditions().get(0));
        return (CheckConditionLeaf) all.getConditions().get(0);
    }


    @Test
    void testKnownRule_CORE000351_coreFields()
    {
        Rule rule = core000351();
        assertEquals("CORE-000351", rule.getCore().getId());
        assertEquals("Published", rule.getCore().getStatus());
        assertEquals("1", rule.getCore().getVersion());
        assertEquals(CORE_000351_ID, rule.getId());
    }


    @Test
    void testKnownRule_CORE000351_typeSensitivityExecutability()
    {
        Rule rule = core000351();
        assertEquals(Sensitivity.RECORD, rule.getSensitivity());
        assertEquals(Executability.PARTIALLY_EXECUTABLE, rule.getExecutability());
    }


    @Test
    void testKnownRule_CORE000351_outcome()
    {
        Rule rule = core000351();
        assertEquals("USUBJID is not unique within study", rule.getOutcome().getMessage());
        assertEquals(1, rule.getOutcome().getOutputVariables().size());
        assertEquals("USUBJID", rule.getOutcome().getOutputVariables().get(0));
    }


    @Test
    void testKnownRule_CORE000351_scope()
    {
        Rule rule = core000351();
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
        CheckConditionLeaf leaf = core000351Leaf();
        assertEquals("USUBJID", leaf.getName());
        assertEquals("is_not_unique_set", leaf.getOperator());
        assertEquals(CheckOperator.IS_NOT_UNIQUE_SET, leaf.getCheckOperator());
    }


    @Test
    void testKnownRule_CORE000351_authorities()
    {
        Rule rule = core000351();
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
    void testRuleWithOperations()
    {
        Rule rule = rulePackage.getRules().get("062da4b3-0c48-4ed3-a97b-c1e92d7bcf95");
        assertNotNull(rule, "Rule with operations should exist");
        assertNotNull(rule.getOperations());
        assertFalse(rule.getOperations().isEmpty());

        Operation op = rule.getOperations().get(0);
        assertEquals("$VARIABLE_COUNT", op.getId());
        assertEquals("--LNKGRP", op.getName());
        assertEquals("variable_count", op.getOperator());
        assertEquals(OperationType.VARIABLE_COUNT, op.getOperationType());
    }


    @Test
    void testRuleWithOperationDomain()
    {
        // CORE-000885 has an operation with domain "EX"
        Rule rule = rulePackage.getRules().get("4162b46f-9e19-41ff-ab42-9a26ee5b37f9");
        assertNotNull(rule, "CORE-000885 should exist");
        assertEquals("CORE-000885", rule.getCore().getId());
        assertNotNull(rule.getOperations());
        assertEquals(1, rule.getOperations().size());

        Operation op = rule.getOperations().get(0);
        assertEquals("$usubjids_in_ex", op.getId());
        assertEquals("USUBJID", op.getName());
        assertEquals("distinct", op.getOperator());
        assertEquals("EX", op.getDomain());
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
     * A JSON-null element inside a Check {@code all}/{@code any} array must be dropped by the
     * deserializer (not retained as a null condition), so no downstream walker ever sees one.
     */
    @Test
    void testNullCheckConditionElementIsDropped() throws Exception
    {
        RulePackage pkg = assertDoesNotThrow(() -> RulePackageLoader
                .loadFromString("{\"rules\":{\"x\":{\"Check\":{\"all\":[null]}}}}"));
        Rule rule = pkg.getRules().get("x");
        assertInstanceOf(CheckConditionAll.class, rule.getCheck());
        assertTrue(((CheckConditionAll) rule.getCheck()).getConditions().isEmpty());
    }


    /** Nested null elements (a null beside a nested {@code all:[null]}) must also be dropped. */
    @Test
    void testNestedNullCheckConditionElementsAreDropped() throws Exception
    {
        RulePackage pkg = assertDoesNotThrow(() -> RulePackageLoader.loadFromString(
                "{\"rules\":{\"x\":{\"Check\":{\"any\":[null,{\"all\":[null]}]}}}}"));
        Rule rule = pkg.getRules().get("x");
        assertInstanceOf(CheckConditionAny.class, rule.getCheck());
        CheckConditionAny any = (CheckConditionAny) rule.getCheck();
        assertEquals(1, any.getConditions().size());
        assertInstanceOf(CheckConditionAll.class, any.getConditions().get(0));
        assertTrue(((CheckConditionAll) any.getConditions().get(0)).getConditions().isEmpty());
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

}
