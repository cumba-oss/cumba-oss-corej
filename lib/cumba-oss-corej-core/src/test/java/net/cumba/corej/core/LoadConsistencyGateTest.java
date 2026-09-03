package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.cumba.corej.core.model.Rule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the load-time consistency gates of {@code PLAN-derive-rule-type-sensitivity}
 * phase 3 that survive: {@code Grouping_Variables} ⟺ {@code Sensitivity: Group} (3b) and the
 * grouped-operation rule type (3c). Gate 3a (the Python one-frame-per-rule compatibility warning)
 * was deleted by phase 2 of {@code PLAN-leaf-scope-domain-inference.md}. Both are wired into
 * {@code RulePackageLoader.validateEnumFields(Rule)} so {@code LibraryRuleMapper} gets them too.
 *
 * <p>
 * Rules are bound from JSON through the production mapper, so the tests exercise the same
 * {@code Check} binding the loader uses.
 * </p>
 */
class LoadConsistencyGateTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Binds a rule and runs the gates, returning the resulting load finding — the error, or a
     * warning — or {@code null} when the rule is clean on both channels.
     */
    private static String validate(String json)
    {
        Rule rule = gated(json);
        return rule.getLoadError() != null ? rule.getLoadError() : rule.getLoadWarning();
    }


    /** Binds a rule and runs the gates, returning the rule for channel-specific assertions. */
    private static Rule gated(String json)
    {
        Rule rule;
        try
        {
            rule = MAPPER.readValue(json, Rule.class);
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("bad test fixture: " + json, e);
        }
        RulePackageLoader.validateEnumFields(rule);
        return rule;
    }


    /** Binds a rule without running any gate — for tests that drive derivation directly. */
    private static Rule bind(String json)
    {
        try
        {
            return MAPPER.readValue(json, Rule.class);
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("bad test fixture: " + json, e);
        }
    }


    private static String rule(String tail)
    {
        return "{\"Core\":{\"Id\":\"T-1\"}," + tail + "}";
    }

    @Nested
    @DisplayName("Phase 7 (leaf-scope plan) — Rule_Type is rejected at load")
    class RuleTypeRejected
    {

        private static final String CHECK = "\"Check\":{\"all\":[{\"name\":\"AESEV\",\"operator\":\"var_exists\"}]}";

        @Test
        @DisplayName("a rule without Rule_Type loads and derives only Sensitivity")
        void noRuleTypeDerivesSensitivityOnly()
        {
            Rule rule = bind(rule(CHECK));
            RulePackageLoader.validateEnumFields(rule);
            RulePackageLoader.deriveOmittedFields(rule);
            assertNull(rule.getLoadError());
            assertNotNull(rule.getDerivationRationale());
            assertTrue(rule.getDerivationRationale().containsKey("Sensitivity"));
            assertFalse(rule.getDerivationRationale().containsKey("Rule_Type"));
        }


        @Test
        @DisplayName("an authored Rule_Type — even a formerly valid one — is a load error")
        void authoredTypeIsRejected()
        {
            Rule rule = bind("{\"Core\":{\"Id\":\"T-1\"},\"Rule_Type\":\"Domain Presence"
                    + " Check\"," + CHECK + "}");
            RulePackageLoader.validateEnumFields(rule);
            assertEquals("Domain Presence Check", rule.getRejectedRuleType());
            assertNotNull(rule.getLoadError());
            assertTrue(
                    rule.getLoadError().contains(
                            "Rule_Type 'Domain Presence Check' is no" + " longer a rule field"),
                    rule.getLoadError());
            assertTrue(rule.getLoadError().contains("Variable_Universe"), rule.getLoadError());
        }
    }


    @Nested
    @DisplayName("3b — Grouping_Variables ⟺ Sensitivity: Group")
    class GroupConsistency
    {

        private static final String CHECK = "\"Check\":{\"all\":[{\"name\":\"AESEV\",\"operator\":\"empty\"}]}";

        @Test
        @DisplayName("Group with grouping variables is the conforming shape")
        void groupWithGroupingVariables()
        {
            assertNull(validate(rule("\"Sensitivity\":\"Group\","
                    + "\"Grouping_Variables\":[\"USUBJID\"]," + CHECK)));
        }


        @Test
        @DisplayName("Group without grouping variables is rejected")
        void groupWithoutGroupingVariables()
        {
            String error = validate(rule("\"Sensitivity\":\"Group\"," + CHECK));
            assertNotNull(error);
            assertTrue(error.contains("requires a non-empty Grouping_Variables"), error);
        }


        @Test
        @DisplayName("grouping variables under a non-Group Sensitivity are rejected")
        void groupingVariablesWithoutGroupSensitivity()
        {
            String error = validate(rule("\"Sensitivity\":\"Record\","
                    + "\"Grouping_Variables\":[\"USUBJID\"]," + CHECK));
            assertNotNull(error);
            assertTrue(error.contains("requires Sensitivity `Group`"), error);
        }


        @Test
        @DisplayName("grouping variables with no Sensitivity pass — the post-strip normal case")
        void groupingVariablesWithNoSensitivity()
        {
            assertNull(validate(rule("\"Grouping_Variables\":[\"USUBJID\"]," + CHECK)));
        }
    }


    @Nested
    @DisplayName("3c is gone — a grouped operation is a row cursor, whatever the type says")
    class GroupedOperationOnAnyType
    {

        private static final String GROUPED_OP = "\"Operations\":[{\"id\":\"$n\",\"operator\":\"record_count\","
                + "\"group\":[\"USUBJID\"]}],"
                + "\"Check\":{\"all\":[{\"name\":\"$n\",\"operator\":\"equal_to\","
                + "\"value\":1}]}";

        @Test
        @DisplayName("a grouped operation loads without any type gate (phase 6, leaf-scope plan)")
        void groupedOperationLoadsWithoutATypeGate()
        {
            assertNull(validate(rule(GROUPED_OP)));
        }
    }

}
