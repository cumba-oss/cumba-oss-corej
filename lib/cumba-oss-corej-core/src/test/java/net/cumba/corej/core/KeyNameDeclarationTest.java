package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import net.cumba.corej.core.metadata.LibraryVariableAttributes;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code key_name} vocabulary of the two filtered Library walks. Until wave 4 the operation
 * parser's {@code validateKeyName} owned it; since {@code PLAN-list-functions} (D-W4-3) the walks
 * are registry functions and the vocabulary is {@code ExprCompiler}'s compile seam, reached at load
 * for a binding and for an inline Check call alike. The claims are unchanged: every key the
 * resolver publishes loads on BOTH walks, a key no level publishes is a load error naming what the
 * level does serve, and a walk that does not declare the parameter refuses it.
 */
class KeyNameDeclarationTest
{

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


    private static Rule binding(String expression)
    {
        return load("{\"Core\":{\"Id\":\"X-1\"},\"Bindings\":[{\"name\": \"$t\", \"expression\": \""
                + expression.replace("\"", "\\\"")
                + "\"}],\"Check\":{\"expression\":\"varname() in $t\"}}");
    }


    private static String loadError(String expression)
    {
        String error = binding(expression).getLoadError();
        assertNotNull(error, expression + " must be a load error");
        return error;
    }


    @Test
    void theLibraryVariableVocabularyLoadsOnBothFilters()
    {
        assertNull(binding("get_model_filtered_variables(key_name=\"role\", key_value=\"Timing\")")
                .getLoadError());
        assertNull(binding("get_dataset_filtered_variables(key_name=\"core\", key_value=\"Perm\")")
                .getLoadError());
        // Every member of the vocabulary is authorable on both filters — no half-served key.
        for (String key : LibraryVariableAttributes.KEYS)
        {
            assertNull(binding("get_model_filtered_variables(key_name=\"" + key + "\")")
                    .getLoadError(), key);
            assertNull(binding("get_dataset_filtered_variables(key_name=\"" + key + "\")")
                    .getLoadError(), key);
        }
    }


    @Test
    @DisplayName("a key no metadata level publishes is a load error, not an empty set")
    void aKeyNoLevelPublishesIsALoadError()
    {
        String error = loadError(
                "get_model_filtered_variables(key_name=\"valueList\", key_value=\"DM\")");
        assertTrue(error.contains("`key_name` `valueList`"), error);
        assertTrue(error.contains("can never match"), error);
        // The message must name what the level DOES serve — that is the whole point of it.
        for (String key : LibraryVariableAttributes.KEYS)
        {
            assertTrue(error.contains(key), error + " // missing " + key);
        }
        // ⭐ P2's ruling, and the ONLY keys still rejected on the two library filters: the three
        // list-valued stored fields. The row is Map<String,String> and key_value is a scalar, so
        // a list-valued key could never match — a load error makes the no-op loud.
        for (String key : Set.of("valueList", "codelistSubmissionValues", "codelistIds"))
        {
            loadError("get_model_filtered_variables(key_name=\"" + key + "\")");
            loadError("get_dataset_filtered_variables(key_name=\"" + key + "\")");
        }
        // A typo is the other half of the complement, and the common case in practice.
        loadError("get_model_filtered_variables(key_name=\"Core\")");
        loadError("get_dataset_filtered_variables(key_name=\"simple_datatype\")");
    }


    @Test
    @DisplayName("⭐ P3: the SEND template's whole legal vocabulary loads, on BOTH filters")
    void theWidenedScalarVocabularyLoads()
    {
        for (String key : Set.of("definition", "examples", "label", "name", "notes", "ordinal",
                "role", "simpleDatatype", "variableCcode", "core", "description", "roleDescription",
                "usageRestrictions", "describedValueDomain"))
        {
            assertNull(binding("get_model_filtered_variables(key_name=\"" + key + "\")")
                    .getLoadError(), key);
            assertNull(binding("get_dataset_filtered_variables(key_name=\"" + key + "\")")
                    .getLoadError(), key);
        }
        Rule ok = binding(
                "get_model_filtered_variables(key_name=\"notes\", key_value=\"ISO 8601.\")");
        assertNull(ok.getLoadError());
        assertNotNull(ok.compiledBinding("$t"), "a compiled binding, not an operation");
    }


    @Test
    @DisplayName("a walk that never reads key_name refuses it")
    void aNonConsumingWalkRefusesTheParameter()
    {
        // ⚠⚠ Not merely "empty": the column-order walk answers the UNFILTERED order, so a silently
        // accepted key_name would make the function answer a different question than authored.
        // On the function surface the binder refuses an undeclared parameter by construction.
        String error = loadError(
                "get_column_order_from_library(key_name=\"role\", key_value=\"Timing\")");
        assertTrue(error.contains("key_name"), error);
        assertTrue(loadError("get_model_column_order(key_name=\"role\")").contains("key_name"));
        assertTrue(loadError("distinct(USUBJID, key_name=\"role\")").contains("key_name"));
    }


    @Test
    void theInlineFormIsValidatedAtLoadToo()
    {
        Rule bad = load("{\"Core\":{\"Id\":\"X-1\"},\"Check\":{\"expression\":"
                + "\"AETERM in get_model_filtered_variables(key_name=\\\"valueList\\\","
                + " key_value=\\\"x\\\")\"}}");
        assertNotNull(bad.getLoadError());
        assertTrue(bad.getLoadError().contains("`key_name` `valueList`"), bad.getLoadError());
        Rule ok = load("{\"Core\":{\"Id\":\"X-1\"},\"Check\":{\"expression\":"
                + "\"AETERM in get_model_filtered_variables(key_name=\\\"role\\\","
                + " key_value=\\\"Timing\\\")\"}}");
        assertNull(ok.getLoadError());
    }


    @Test
    @DisplayName("⛔ core on the Model walk is legal — SUPP/SQ and ADaM publish it")
    void coreOnTheModelWalkIsNotALoadError()
    {
        // The FDA-SD1078 shape itself. A blanket reject here would be WRONG: the Model walk
        // publishes `core` for SUPP--/SQ-- datasets and for every ADaM dataset. Servability is a
        // per-dataset runtime fact, so the diagnostic for it is a runtime WARNING — see
        // net.cumba.corej.core.exec.UnservedKeyNameDiagnosticTest.
        assertNull(binding("get_model_filtered_variables(key_name=\"core\", key_value=\"Perm\")")
                .getLoadError());
        // Symmetrically: `role` is absent from every ADaM row, so it must not be rejected on
        // either filter either.
        assertNull(binding("get_dataset_filtered_variables(key_name=\"role\")").getLoadError());
    }


    @Test
    void theParameterIsAStaticLiteral()
    {
        // A column bound there would be read at row 0 and silently stand for every row (D-W3-4's
        // reasoning, applied by D-W4-3).
        String error = loadError(
                "get_model_filtered_variables(key_name=DOMAIN, key_value=\"Perm\")");
        assertTrue(error.contains("static string literal"), error);
    }


    @Test
    void theVocabularyIsTheResolversOwn()
    {
        // The load-time allowlist and MetadataProvider's documented `variables_metadata` row shape
        // are ONE set. If a resolver starts publishing a fifteenth key, this is what reds.
        // ⚠ The pin that this set is exactly what the RESOLVER publishes — not merely what someone
        // typed here — is LibraryVariableRowBreadthTest, which derives it from a real walk.
        assertEquals(Set.of("name", "role", "core", "simpleDatatype", "label", "ordinal",
                "description", "roleDescription", "definition", "notes", "examples",
                "usageRestrictions", "variableCcode", "describedValueDomain"),
                LibraryVariableAttributes.KEYS);
    }
}
