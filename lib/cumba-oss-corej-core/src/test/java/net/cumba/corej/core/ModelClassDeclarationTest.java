package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.cumba.corej.core.metadata.SdtmObservationClasses;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * EC-85's {@code model_class} vocabulary on {@code get_model_filtered_variables}. Until wave 4 the
 * operation parser's {@code validateModelClass} owned it; since {@code PLAN-list-functions}
 * (D-W4-3) the walk is a registry function, the vocabulary is {@code ExprCompiler}'s compile seam
 * (matched after the same normalisation the function applies), and a function that does not declare
 * the parameter refuses it by construction.
 */
class ModelClassDeclarationTest
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
        return load(
                "{\"Core\":{\"Id\":\"X-1\"},\"Bindings\":[{\"name\": \"$ev\", \"expression\": \""
                        + expression.replace("\"", "\\\"")
                        + "\"}],\"Check\":{\"expression\":\"varname() in $ev\"}}");
    }


    @Test
    void aKnownClassLoadsInAnySpellingTheNormaliserAccepts()
    {
        assertNull(binding("get_model_filtered_variables(model_class=\"events\")").getLoadError());
        assertNull(binding("get_model_filtered_variables(model_class=\" Findings About \")")
                .getLoadError());
        // Composes with the role filter — the filter tail is shared.
        Rule composed = binding("get_model_filtered_variables(model_class=\"EVENTS\","
                + " key_name=\"role\", key_value=\"Topic\")");
        assertNull(composed.getLoadError());
        assertNotNull(composed.compiledBinding("$ev"), "a compiled binding, not an operation");
    }


    @Test
    void anUnknownClassSpellingIsALoadError()
    {
        String error = binding("get_model_filtered_variables(model_class=\"EVENT\")")
                .getLoadError();
        assertNotNull(error);
        assertTrue(error.contains("unknown `model_class` value `EVENT`"), error);
        assertTrue(error.contains("EVENTS"), error);
        String twice = binding(
                "get_model_filtered_variables(model_class=\"special-purpose datasets\")")
                        .getLoadError();
        assertNotNull(twice, "normalised once, never twice");
        assertTrue(twice.contains("unknown `model_class`"), twice);
    }


    @Test
    void aFunctionThatDoesNotDeclareTheParameterRefusesIt()
    {
        String error = binding("get_model_column_order(model_class=\"EVENTS\")").getLoadError();
        assertNotNull(error);
        assertTrue(error.contains("model_class"), error);
    }


    @Test
    void theInlineFormIsValidatedAtLoadToo()
    {
        Rule bad = load("{\"Core\":{\"Id\":\"X-1\"},\"Check\":{\"expression\":"
                + "\"AETERM in get_model_filtered_variables(model_class=\\\"EVENT\\\")\"}}");
        assertNotNull(bad.getLoadError());
        assertTrue(bad.getLoadError().contains("unknown `model_class` value"), bad.getLoadError());
        Rule ok = load("{\"Core\":{\"Id\":\"X-1\"},\"Check\":{\"expression\":"
                + "\"AETERM in get_model_filtered_variables(model_class=\\\"EVENTS\\\")\"}}");
        assertNull(ok.getLoadError());
    }


    @Test
    void theVocabularyIsTheResolversOwn()
    {
        // The seam and MetadataLibraryProvider read ONE set of names, normalised ONE way.
        for (String name : SdtmObservationClasses.DETECTABLE)
        {
            assertTrue(SdtmObservationClasses.isDetectable(name), name);
            assertTrue(SdtmObservationClasses.MODEL_CLASS_NAMES.contains(name), name);
        }
        assertEquals("SPECIAL PURPOSE", SdtmObservationClasses.normalise("Special-Purpose"));
        assertEquals("SPECIAL-PURPOSE",
                SdtmObservationClasses.normalise("special-purpose datasets"));
        assertEquals("FINDINGS ABOUT", SdtmObservationClasses.normalise("findings about"));
        assertNull(SdtmObservationClasses.normalise(null));
        // Every allowed name is a fixed point of normalise(), so the load-time validation and the
        // resolver's in-walk normalisation cannot disagree.
        for (String name : SdtmObservationClasses.MODEL_CLASS_NAMES)
        {
            assertEquals(name, SdtmObservationClasses.normalise(name), name);
        }
        assertFalse(SdtmObservationClasses.isDetectable(null));
        assertFalse(SdtmObservationClasses.isDetectable("SPECIAL PURPOSE"));
    }
}
