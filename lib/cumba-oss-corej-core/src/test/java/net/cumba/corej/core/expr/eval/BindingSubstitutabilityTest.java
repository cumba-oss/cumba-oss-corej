package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleExecutionStatus;
import net.cumba.corej.core.exec.RuleRunnerCalls;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-binding-substitutability} §5 (runbook W1a BS1, C9–C11, ruled Q4): every static
 * parameter the compiler held to a literal because it is <b>read once per call</b> — and whose
 * value is read at evaluation, not at plan-build time — takes any <b>dataset-level</b> expression
 * of its declared type, bound ({@code $x}) or written inline, and the two spellings load alike and
 * evaluate alike (D3 / D16). A per-row or per-variable value is the LEVEL finding, one message for
 * both spellings; a wrong-typed literal stays a type finding.
 *
 * <p>
 * The widened parameters: {@code extract_metadata} / {@code variable_count} {@code name},
 * {@code get_dataset_filtered_variables} / {@code get_model_filtered_variables} {@code key_value}
 * (C9, the open-vocabulary strings); the five dictionary functions' {@code case_sensitive} (C10);
 * {@code codelist_terms} {@code codelists}, {@code valid_codelist_dates} {@code ct_package_types}
 * (C11). NOT widened: {@code keep_missings} (consumed at plan-build time — owner question pending,
 * coordinator decision 8), the closed vocabularies and the regex parameters (G2, C7 / C8), and the
 * name slots {@code column_series_metadata.name} / {@code cross_dataset_variable_metadata.domain}
 * (class X, coordinator decision 1).
 * </p>
 */
class BindingSubstitutabilityTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * (parameter, the call with {@code ‹arg›} at the widened parameter, a dataset-level expression
     * of its type) — the roster BS1 widens.
     */
    private static final List<String[]> WIDENED = List.of(new String[]
    {
            "name", "extract_metadata(name=‹arg›)", "lower(\"DATASET_NAME\")"
    }, new String[]
    {
            "name", "variable_count(name=‹arg›)", "upper(\"--lnkgrp\")"
    }, new String[]
    {
            "key_value", "get_dataset_filtered_variables(key_name=\"role\", key_value=‹arg›)",
            "upper(\"identifier\")"
    }, new String[]
    {
            "key_value", "get_model_filtered_variables(key_name=\"core\", key_value=‹arg›)",
            "upper(\"req\")"
    }, new String[]
    {
            "codelists", "codelist_terms(codelists=‹arg›, level=\"term\")",
            "union([\"DOMAIN\"], [\"ND\"])"
    }, new String[]
    {
            "ct_package_types", "valid_codelist_dates(ct_package_types=‹arg›)",
            "minus([\"SDTM\", \"ADAM\"], subtract=[\"ADAM\"])"
    }, new String[]
    {
            "case_sensitive",
            "dictionary_has_decode(CMTRT, external_dictionary_type=\"whodrug\", case_sensitive=‹arg›)",
            "(1 == 2)"
    }, new String[]
    {
            "case_sensitive",
            "valid_external_dictionary_value(AEDECOD, external_dictionary_type=\"meddra\","
                    + " dictionary_term_type=\"PT\", case_sensitive=‹arg›)",
            "(1 == 2)"
    }, new String[]
    {
            "case_sensitive",
            "valid_external_dictionary_code(AEDECOD, external_dictionary_type=\"meddra\","
                    + " dictionary_term_type=\"PT\", case_sensitive=‹arg›)",
            "(1 == 2)"
    }, new String[]
    {
            "case_sensitive", "valid_external_dictionary_code_term_pair(TSVALCD, TSVAL,"
                    + " external_dictionary_type=\"unii\", case_sensitive=‹arg›)",
            "(1 == 2)"
    }, new String[]
    {
            "case_sensitive", "valid_external_dictionary_hierarchy(AEHLT, AESOC,"
                    + " external_dictionary_type=\"meddra\", case_sensitive=‹arg›)",
            "(1 == 2)"
    });

    @Test
    void aDatasetLevelExpressionLoadsBoundAndInlineAlike() throws Exception
    {
        for (String[] row : WIDENED)
        {
            String call = row[1];
            String expr = row[2];
            Rule bound = loadCall(call.replace("‹arg›", "$x"), "$x", expr);
            assertNull(bound.getLoadError(), call + " bound → " + bound.getLoadError());
            Rule inline = loadCall(call.replace("‹arg›", expr));
            assertNull(inline.getLoadError(), call + " inline → " + inline.getLoadError());
        }
    }


    @Test
    void aPerRowValueIsTheSameLevelFindingBoundAndInline() throws Exception
    {
        for (String[] row : WIDENED)
        {
            String parameter = row[0];
            String call = row[1];
            String bound = String
                    .valueOf(loadCall(call.replace("‹arg›", "$x"), "$x", "AETERM").getLoadError());
            String inline = String
                    .valueOf(loadCall(call.replace("‹arg›", "AETERM")).getLoadError());
            assertTrue(bound.contains("OPERATION_READS_CURSOR_BINDING"),
                    call + " bound → " + bound);
            assertTrue(bound.contains("argument '" + parameter + "'"), call + " bound → " + bound);
            assertTrue(bound.contains("read once per call"), call + " bound → " + bound);
            assertEquals(bound.replace("`$x`", "`AETERM`"), inline,
                    call + ": one level finding for both spellings");
        }
    }


    @Test
    void aPerRowValueInACheckLessRuleIsTheLoadersFinding() throws Exception
    {
        // The paths stage A never walks (G3): a Check-less rule's bindings meet the same judgement
        // in the loader's seam pass, so a per-row value never silently reads row 0 there.
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("Core", Map.of("Id", "BS1-CHECKLESS"));
        rule.put("Bindings", List.of(Map.of("name", "$k", "expression", "AETERM"),
                Map.of("name", "$v", "expression", "extract_metadata(name=$k)")));
        rule.put("Match_Datasets", List.of(Map.of("Name", "AE")));
        rule.put("Outcome", Map.of("Message", "m", "Output_Variables", List.of("$v")));
        String json = MAPPER.writeValueAsString(Map.of("rules", Map.of("x", rule)));
        Rule loaded = RulePackageLoader.loadFromString(json).getRules().get("x");
        assertNotNull(loaded);
        String error = String.valueOf(loaded.getLoadError());
        assertTrue(error.contains("argument 'name' of 'extract_metadata'"), error);
        assertTrue(error.contains("read once per call"), error);
        assertTrue(error.contains("the binding $v"), error);
    }


    @Test
    void aWrongTypedLiteralStaysATypeFinding() throws Exception
    {
        // The type half of the retired seams stays where stage A does not walk (a binding compiles
        // before stage A runs): a string where a boolean is read, a string where a list is read.
        String flag = String.valueOf(load(
                "valid_external_dictionary_code_term_pair(TSVALCD, TSVAL,"
                        + " external_dictionary_type=\"unii\", case_sensitive=$x)",
                "$x", "\"false\"").getLoadError());
        // (a typed $x: "false" in a Check is stage A's PARAMETER_TYPE "takes boolean, not string";
        // the compile-site wording "takes a boolean (true or false)" is DictionaryFunctionsTest's)
        assertTrue(flag.contains("argument 'case_sensitive'") && flag.contains("boolean"), flag);
        String list = String.valueOf(
                load("not empty($v)", "$v", "codelist_terms(codelists=\"DOMAIN\", level=\"term\")")
                        .getLoadError());
        assertTrue(list.contains("argument 'codelists'") && list.contains("takes a list"), list);
    }


    @Test
    void theNameSlotsAndTheClosedVocabulariesStayLiteral() throws Exception
    {
        // Coordinator decision 1 (class X): column_series_metadata.name and
        // cross_dataset_variable_metadata.domain are name slots; the closed vocabularies and the
        // regex parameters are literal types (G2). The widening does not reach them.
        for (String call : List.of(
                "column_series_metadata(name=lower(\"COVAL\"), name_pattern=\"^COVAL\\\\d+$\")",
                "cross_dataset_variable_metadata(\"label\", domain=lower(\"ADSL\"))",
                "codelist_terms(codelists=[\"DOMAIN\"], level=lower(\"TERM\"))",
                "get_dataset_filtered_variables(key_name=lower(\"ROLE\"), key_value=\"x\")",
                "variable_count(name_pattern=lower(\"^.+FL$\"))"))
        {
            String error = String.valueOf(load("not empty($v)", "$v", call).getLoadError());
            assertTrue(error.contains("static string literal"), call + " → " + error);
        }
    }


    @Test
    void aComputedMetadataKeyEvaluatesAsTheLiteralDoes() throws Exception
    {
        IDataTable dm = MockTable.of().col("USUBJID", "S1", "S2").name("DM").build();
        Rule literal = load("$n == \"DM\"", "$n", "extract_metadata(name=\"dataset_name\")");
        Rule bound = load("$n == \"DM\"", "$k", "lower(\"DATASET_NAME\")", "$n",
                "extract_metadata(name=$k)");
        Rule inline = load("$n == \"DM\"", "$n", "extract_metadata(name=lower(\"DATASET_NAME\"))");
        int expected = -1;
        for (Rule rule : List.of(literal, bound, inline))
        {
            assertNull(rule.getLoadError(), rule.getLoadError());
            RuleExecutionResult result = RuleRunnerCalls.execute(rule, dm);
            assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                    result.getStatusMessage());
            assertTrue(result.getViolations().size() > 0, "the key resolves to the dataset name");
            expected = expected < 0 ? result.getViolations().size() : expected;
            assertEquals(expected, result.getViolations().size(),
                    "the three spellings answer alike");
        }
    }


    /**
     * A registry call as a rule: a BOOLEAN function is the Check itself, a value function is bound
     * to {@code $v} and the Check reads {@code not empty($v)}.
     */
    private static Rule loadCall(String call, String... bindings) throws Exception
    {
        String name = call.substring(0, call.indexOf('('));
        FunctionDescriptor d = FunctionRegistry.descriptor(name);
        assertNotNull(d, name);
        if (d.kind() == FunctionKind.BOOLEAN)
        {
            return load(call, bindings);
        }
        List<String> all = new ArrayList<>(List.of(bindings));
        all.add("$v");
        all.add(call);
        return load("not empty($v)", all.toArray(new String[0]));
    }


    private static Rule load(String check, String... bindings) throws Exception
    {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("Core", Map.of("Id", "BS1-PAIR"));
        List<Map<String, String>> declared = new ArrayList<>();
        for (int i = 0; i < bindings.length; i += 2)
        {
            declared.add(Map.of("name", bindings[i], "expression", bindings[i + 1]));
        }
        if (!declared.isEmpty())
        {
            rule.put("Bindings", declared);
        }
        rule.put("Check", Map.of("expression", check));
        rule.put("Outcome", Map.of("Message", "m"));
        String json = MAPPER.writeValueAsString(Map.of("rules", Map.of("x", rule)));
        Rule loaded = RulePackageLoader.loadFromString(json).getRules().get("x");
        assertNotNull(loaded, "the rule parses");
        return loaded;
    }
}
