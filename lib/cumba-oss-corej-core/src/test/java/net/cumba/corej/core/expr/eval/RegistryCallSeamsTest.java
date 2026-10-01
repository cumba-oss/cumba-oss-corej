package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.typed.ExprType.Unknown;
import net.cumba.corej.core.model.Rule;
import org.junit.jupiter.api.Test;

/**
 * The load-time argument seams of a registry call written <b>inline</b> in a Check (combined review
 * of runbook W2–W8, W4 M4 = W3/W4b L1): the seam used to be an allowlist of the wave-4 / wave-4b
 * names, so every other registry function inline — row_max, the dictionary functions,
 * referenced_dataset_variables — loaded clean and met the compiler's seams only at its first
 * evaluation, a run-time ERROR instead of a load error. Inverted: every registry name meets the
 * generic seam except the compiler-dispatched calls, whose exemption set is self-checked. Also the
 * (function, parameter) keying of the static-string seam (W3/W4b L3). Real registry, no Mockito.
 */
class RegistryCallSeamsTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ============================================================ inline load errors (red on HEAD)

    @Test
    void anInlineRowMaxWithANonLiteralPatternIsALoadError() throws Exception
    {
        assertInlineLoadError("row_max(name_pattern=TRTEDT) == \"\"", "name_pattern", "row_max",
                "static string literal", "TRTEDT");
    }


    @Test
    void anInlineDictionaryCallWithANonLiteralLevelOrFlagIsALoadError() throws Exception
    {
        assertInlineLoadError("valid_external_dictionary_value(AEDECOD,"
                + " external_dictionary_type=\"meddra\", dictionary_term_type=AELLT) == false",
                "dictionary_term_type", "static string literal", "AELLT");
        assertInlineLoadError(
                "valid_external_dictionary_code_term_pair(TSVALCD, TSVAL,"
                        + " external_dictionary_type=\"unii\", case_sensitive=TSPARMCD) == false",
                "case_sensitive", "boolean literal");
    }


    @Test
    void anInlineColumnReferenceParameterGivenALiteralIsALoadError() throws Exception
    {
        // PLAN-stage-a-parameter-type-arming Q3 / phase 4: the R1 seam stands down in the loader's
        // seam pass, and the ARMED stage A answers — the prefix pins WHICH guard refused. ⚠ Without
        // stage A the fixture would NOT be a load error at all (review r1 seams L1): the compile
        // site's refusal is an ExpressionException, which NativeExprEvaluator.isSupported swallows,
        // so the rule would load with no native form and ERROR at its first evaluation.
        assertInlineLoadError("IDVAR not in referenced_dataset_variables(\"RDOMAIN\")",
                "stage A: PARAMETER_TYPE", "takes a column reference, not the literal RDOMAIN");
    }


    /**
     * The two seam halves stage A covers in the pass, each on an inline call: a wrong-typed LITERAL
     * {@code case_sensitive} (S2) and a wrong-typed literal static string (S3) are the armed
     * {@code PARAMETER_TYPE}; the staticness halves (a column at either) stay the compile seams'
     * (the test above).
     */
    @Test
    void theWrongTypedLiteralHalvesAreStageAsInThePass() throws Exception
    {
        assertInlineLoadError(
                "valid_external_dictionary_code_term_pair(TSVALCD, TSVAL,"
                        + " external_dictionary_type=\"unii\", case_sensitive=\"false\") == false",
                "stage A: PARAMETER_TYPE", "'case_sensitive'", "takes boolean, not string");
        assertInlineLoadError(
                "valid_external_dictionary_code_term_pair(TSVALCD, TSVAL,"
                        + " external_dictionary_type=\"unii\", case_sensitive=0) == false",
                "stage A: PARAMETER_TYPE", "takes boolean, not number");
        assertInlineLoadError("row_max(name_pattern=5) == \"\"", "stage A: PARAMETER_TYPE",
                "'name_pattern' of 'row_max' takes string, not number");
        assertInlineLoadError(
                "valid_external_dictionary_value(AEDECOD,"
                        + " external_dictionary_type=\"meddra\", dictionary_term_type=5) == false",
                "stage A: PARAMETER_TYPE", "'dictionary_term_type'", "takes string, not number");
    }


    /**
     * Review r1 seams M1: the seam pass runs BEFORE stage A and stage A runs only on a rule with a
     * Check; a Check-less rule's binding never meets stage A, so the pass must keep every seam for
     * it ({@code stageAJudged} reflects reality).
     */
    @Test
    void aCheckLessRuleKeepsTheSeamsInThePass() throws Exception
    {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("Core", Map.of("Id", "CFX-NOCHECK"));
        rule.put("Bindings",
                List.of(Map.of("name", "$dy", "expression", "dy(\"AESTDTC\", RFSTDTC)")));
        rule.put("Outcome", Map.of("Message", "m"));
        String json = MAPPER.writeValueAsString(Map.of("rules", Map.of("x", rule)));
        Rule loaded = RulePackageLoader.loadFromString(json).getRules().get("x");
        assertNotNull(loaded);
        assertNotNull(loaded.getLoadError(), "a quoted name in a Check-less rule's binding");
        assertTrue(
                loaded.getLoadError().contains("takes a column reference, not the literal AESTDTC"),
                loaded.getLoadError());
    }


    /**
     * Review r1 seams M2: the S2 / S3 literal halves stand down only where stage A can see the
     * conflict — a {@code case_sensitive} declared UNKNOWN (a stub) keeps the seam's refusal.
     */
    @Test
    void theLiteralHalvesStandDownOnlyForATypedParameter() throws Exception
    {
        FunctionDescriptor probe = new FunctionDescriptor("__r1_m2_probe__",
                List.of(Parameter.required("external_dictionary_type",
                        net.cumba.corej.core.expr.typed.ExprType.Primitive.STRING),
                        Parameter.optional("case_sensitive", Unknown.UNKNOWN)),
                FunctionKind.BOOLEAN, (run, args) -> new java.util.BitSet())
                        .withProvider(ProviderNeed.dictionary("external_dictionary_type"));
        try (var _ = RegistryTestSeam.register(probe))
        {
            Rule rule = inline("__r1_m2_probe__(external_dictionary_type=\"meddra\","
                    + " case_sensitive=\"false\")");
            assertNotNull(rule.getLoadError(), "the seam must still refuse the literal");
            assertTrue(rule.getLoadError().contains("boolean literal"), rule.getLoadError());
        }
    }

    // ============================================================ controls (green on HEAD too)


    @Test
    void theValidInlineCallsOfTheNewlyCoveredFunctionsStillLoad() throws Exception
    {
        for (String check : List.of("row_max(name_pattern=\"^TR(0[1-9]|[1-9][0-9])EDT$\") == \"\"",
                "valid_external_dictionary_value(AEDECOD, external_dictionary_type=\"meddra\","
                        + " dictionary_term_type=\"PT\") == false",
                "IDVAR not in referenced_dataset_variables(RDOMAIN)",
                "has_multiple_values_for(COHORTN, COHORT, within=USUBJID, keep_missings=true)"))
        {
            Rule rule = inline(check);
            assertNull(rule.getLoadError(), check + " → " + rule.getLoadError());
        }
    }

    // ============================================================ (function, parameter) keying


    @Test
    void aSameNamedParameterOfAnotherFunctionIsNotHeldToALiteral() throws Exception
    {
        // A per-row operand that merely shares row_max's `name_pattern` name: the name-keyed seam
        // refused the column there ("takes a static string literal"), a false load error.
        FunctionDescriptor probe = new FunctionDescriptor("__w3w4b_l3_probe__",
                List.of(Parameter.required("name_pattern", Unknown.UNKNOWN)), FunctionKind.VALUE,
                (run, args) -> Objects.requireNonNull(args.get(0)));
        try (var _ = RegistryTestSeam.register(probe))
        {
            Rule rule = bound("__w3w4b_l3_probe__(name_pattern=AETERM)");
            assertNull(rule.getLoadError(), String.valueOf(rule.getLoadError()));
        }
        // …while row_max's own name_pattern is still held to a literal (and variable_count's).
        for (String call : List.of("row_max(name_pattern=AETERM)",
                "variable_count(name_pattern=AETERM)"))
        {
            String error = bound(call).getLoadError();
            assertNotNull(error, call + " must be a load error");
            assertTrue(error.contains("static string literal"), call + " → " + error);
        }
    }

    // ============================================================ helpers


    private static void assertInlineLoadError(String check, String... fragments) throws Exception
    {
        String error = inline(check).getLoadError();
        assertNotNull(error, check + " must be a LOAD error, not a first-evaluation one");
        for (String fragment : fragments)
        {
            assertTrue(error.contains(fragment), check + " → " + error + " // missing " + fragment);
        }
    }


    private static Rule inline(String check) throws Exception
    {
        return load(check, List.of());
    }


    private static Rule bound(String expression) throws Exception
    {
        return load("not empty($v)", List.of(Map.of("name", "$v", "expression", expression)));
    }


    private static Rule load(String check, List<Map<String, String>> bindings) throws Exception
    {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("Core", Map.of("Id", "CFX-SEAMS"));
        if (!bindings.isEmpty())
        {
            rule.put("Bindings", new ArrayList<>(bindings));
        }
        rule.put("Check", Map.of("expression", check));
        rule.put("Outcome", Map.of("Message", "m"));
        String json = MAPPER.writeValueAsString(Map.of("rules", Map.of("x", rule)));
        Rule loaded = RulePackageLoader.loadFromString(json).getRules().get("x");
        assertNotNull(loaded, "the rule parses");
        return loaded;
    }
}
