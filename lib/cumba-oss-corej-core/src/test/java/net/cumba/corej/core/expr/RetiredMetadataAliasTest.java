package net.cumba.corej.core.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * Runbook W4 MA6 ({@code PLAN-metadata-accessor-default-level} table 2 rows 13-16, D4 / D6, V13;
 * coordinator W4-D10): the 44 metadata aliases of §4.3 and {@code library_variable_label_template}
 * (the 45th, V13) are refused in expressions — a Check level, a binding, a {@code Match_Datasets}
 * Filter — as the RULE's load error with a hint naming the accessor spelling, in the loader's
 * per-rule seam beside the retired call names ({@code var_is_null}): one rule is parked, its
 * package and its siblings load. They stay legal {@code Output_Variables} report columns.
 */
class RetiredMetadataAliasTest
{

    /** §4.3's 44 + {@code library_variable_label_template} (V13), written out — not derived. */
    private static final Set<String> ROSTER = Set.of("dataset_class", "dataset_domain",
            "dataset_label", "dataset_name", "define_dataset_class", "define_dataset_label",
            "define_dataset_name", "library_dataset_class", "library_dataset_label",
            "library_dataset_name", "variable_data_type", "variable_format", "variable_label",
            "variable_length", "define_variable_ccode", "define_variable_codelist",
            "define_variable_codelist_coded_codes", "define_variable_codelist_coded_values",
            "define_variable_codelist_extended_values", "define_variable_core",
            "define_variable_data_type", "define_variable_external_dictionary",
            "define_variable_external_dictionary_version", "define_variable_has_codelist",
            "define_variable_has_comment", "define_variable_has_method", "define_variable_label",
            "define_variable_length", "define_variable_name", "define_variable_ordinal",
            "define_variable_origin_type", "define_variable_role", "library_variable_ccode",
            "library_variable_codelist", "library_variable_codelist_coded_codes",
            "library_variable_codelist_coded_values", "library_variable_codelist_extensible",
            "library_variable_core", "library_variable_data_type", "library_variable_label",
            "library_variable_length", "library_variable_name", "library_variable_ordinal",
            "library_variable_role", "library_variable_label_template");

    /** §4.3's other 20 operands: never refused. */
    private static final List<String> KEPT = List.of("dataset_metadata", "record_count",
            "variable_name", "variable_value", "variable_value_length", "variable_size",
            "variable_max_size", "define_variable_decode_matches",
            "library_variable_code_pair_matches", "library_variable_label_values",
            "library_variable_data_type_values", "define_vlm_data_type", "define_vlm_length",
            "define_vlm_mandatory", "define_vlm_codelist_coded_values",
            "define_vlm_codelist_coded_codes", "define_vlm_type_conforms",
            "define_vlm_codelist_extensible", "define_vlm_has_codelist",
            "define_vlm_decode_matches");

    @Test
    void theRosterIsExactlyTheFortyFourAliasesAndTheLabelTemplate()
    {
        assertEquals(45, ROSTER.size());
        assertEquals(new TreeSet<>(ROSTER),
                new TreeSet<>(MetadataOperandMapping.retiredOperands()));
        for (String kept : KEPT)
        {
            assertTrue(BuiltinRegistry.isBuiltin(kept), kept);
            assertFalse(MetadataOperandMapping.retiredOperands().contains(kept), kept);
        }
        // every registered operand is either retired or kept: the 65 of V13
        Set<String> all = new TreeSet<>(ROSTER);
        all.addAll(KEPT);
        assertEquals(new TreeSet<>(BuiltinRegistry.names()), all);
    }


    @Test
    void theHintIsTheAccessorSpellingWithTheAnchorAndTheDataLevelElided()
    {
        assertEquals("ds_name()", MetadataOperandMapping.retiredOperandHint("dataset_name"));
        assertEquals("ds_domain()", MetadataOperandMapping.retiredOperandHint("dataset_domain"));
        assertEquals("var_label()", MetadataOperandMapping.retiredOperandHint("variable_label"));
        assertEquals("var_type()", MetadataOperandMapping.retiredOperandHint("variable_data_type"));
        assertEquals("ds_class(\"LIBRARY\")",
                MetadataOperandMapping.retiredOperandHint("library_dataset_class"));
        assertEquals("var_codelist_coded_codes(\"DEFINE\")",
                MetadataOperandMapping.retiredOperandHint("define_variable_codelist_coded_codes"));
        assertEquals("var_codelist_coded_values(\"LIBRARY\")", MetadataOperandMapping
                .retiredOperandHint("library_variable_codelist_coded_values"));
        assertEquals("label_template(\"LIBRARY\")",
                MetadataOperandMapping.retiredOperandHint("library_variable_label_template"));
        for (String alias : ROSTER)
        {
            String hint = MetadataOperandMapping.retiredOperandHint(alias);
            assertNotNull(hint, alias);
            assertFalse(hint.contains("variable_name") || hint.contains("\"DATA\""),
                    alias + " -> " + hint);
            // the hint is itself a legal expression
            assertNotNull(CheckExpressionParser.parse(hint), hint);
        }
        assertNull(MetadataOperandMapping.retiredOperandHint("variable_size"), "kept operand");
    }


    @Test
    void eachAliasInACheckIsTheRulesLoadErrorWithItsHint() throws IOException
    {
        for (String alias : ROSTER)
        {
            RulePackage pkg = loadPair(rule(alias + " == \"X\"", null, null));
            assertRetired(alias, pkg.getRules().get("x").getLoadError());
            assertNull(pkg.getRules().get("ok").getLoadError(),
                    "a sibling rule of the same package loads clean: the per-rule channel");
        }
    }


    @Test
    void eachAliasInABindingIsTheRulesLoadErrorWithItsHint() throws IOException
    {
        for (String alias : ROSTER)
        {
            // a Check-ed rule reading the binding, and a Check-less rule
            assertRetired(alias, loadPair(rule("$b == \"X\"", "len(" + alias + ")", null))
                    .getRules().get("x").getLoadError());
            Map<String, Object> checkless = rule(null, alias, null);
            assertRetired(alias, loadPair(checkless).getRules().get("x").getLoadError());
        }
    }


    @Test
    void eachAliasInAMatchDatasetsFilterIsTheRulesLoadErrorWithItsHint() throws IOException
    {
        for (String alias : ROSTER)
        {
            assertRetired(alias, loadPair(rule("not empty(ARM)", null, alias + " == \"DM\""))
                    .getRules().get("x").getLoadError());
        }
        assertNull(loadPair(rule("not empty(ARM)", null, "ds_name() == \"DM\"")).getRules().get("x")
                .getLoadError(), "the accessor spelling in a Filter loads");
    }


    @Test
    void anAliasNestedDeepInACheckIsRefusedToo() throws IOException
    {
        assertRetired("variable_label",
                loadPair(rule("not empty(AETERM) and (len(upper(variable_label)) > 40"
                        + " or AETERM in [variable_label])", null, null)).getRules().get("x")
                                .getLoadError());
    }


    @Test
    void theAccessorSpellingsLoad() throws IOException
    {
        for (String alias : ROSTER)
        {
            String hint = MetadataOperandMapping.retiredOperandHint(alias);
            String error = loadPair(rule("len(" + hint + ") > 0", null, null)).getRules().get("x")
                    .getLoadError();
            assertFalse(error != null && error.contains("retired operand"), hint + " -> " + error);
        }
    }


    @Test
    void theKeptOperandsAreNeverTheRetiredOperandError() throws IOException
    {
        for (String kept : KEPT)
        {
            String error = loadPair(rule("len(" + kept + ") > 0", null, null)).getRules().get("x")
                    .getLoadError();
            assertFalse(error != null && error.contains("retired operand"), kept + " -> " + error);
        }
    }


    @Test
    void theAliasesStayLegalOutputVariables() throws IOException
    {
        for (String alias : ROSTER)
        {
            assertTrue(BuiltinRegistry.isBuiltin(alias), alias);
            assertNotNull(MetadataOperandMapping.forwardOperand(alias), alias);
        }
        Map<String, Object> rule = rule("not empty(AETERM)", null, null);
        rule.put("Outcome",
                Map.of("Message", "m", "Output_Variables",
                        List.of("AETERM", "dataset_name", "variable_label",
                                "library_variable_label_template",
                                "define_variable_codelist_coded_codes")));
        Rule loaded = loadPair(rule).getRules().get("x");
        assertNull(loaded.getLoadError(), loaded.getLoadError());
    }

    // ------------------------------------------------------------------ helpers


    private static void assertRetired(String alias, String error)
    {
        assertNotNull(error, alias + ": no load error");
        assertTrue(error.contains("retired operand '" + alias + "'"), error);
        assertTrue(error.contains(
                "write " + MetadataOperandMapping.retiredOperandHint(alias) + " (same value)"),
                error);
        assertTrue(error.contains("stays valid as an Output_Variables report column"), error);
    }


    /** A rule {@code x}; a null Check makes it Check-less, a binding is {@code $b}. */
    private static Map<String, Object> rule(String check, String binding, String filter)
    {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("Core", Map.of("Id", "T-MA6"));
        if (binding != null)
        {
            rule.put("Bindings", List.of(Map.of("name", "$b", "expression", binding)));
        }
        if (check != null)
        {
            rule.put("Check", Map.of("expression", check));
        }
        rule.put("Outcome", Map.of("Message", "m"));
        if (filter != null)
        {
            rule.put("Requirements", Map.of("Variables", Map.of("All", List.of("USUBJID"),
                    "All_Or_None", List.of(List.of("USUBJID", "DM.USUBJID")))));
            rule.put("Match_Datasets", List.of(Map.of("Name", "DM", "Keys", List.of("USUBJID"),
                    "Join_Type", "left", "Filter", filter)));
        }
        return rule;
    }


    /** {@code x} beside a clean sibling {@code ok}. */
    private static RulePackage loadPair(Map<String, Object> x) throws IOException
    {
        Map<String, Object> ok = rule("not empty(AETERM)", null, null);
        ok.put("Core", Map.of("Id", "T-MA6-OK"));
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("x", x);
        rules.put("ok", ok);
        return RulePackageLoader
                .loadFromString(new ObjectMapper().writeValueAsString(Map.of("rules", rules)));
    }
}
