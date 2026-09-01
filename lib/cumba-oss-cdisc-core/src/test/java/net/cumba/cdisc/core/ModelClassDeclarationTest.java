package net.cumba.cdisc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.cumba.cdisc.core.expr.RuleDefinitionException;
import net.cumba.cdisc.core.expr.convert.OperationExpressionParser;
import net.cumba.cdisc.core.metadata.SdtmObservationClasses;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * EC-85 — the {@code model_class} declaration on {@code get_model_filtered_variables}, on all three
 * authoring surfaces (field form, expression form, inline call). The two rejections — a
 * non-consuming operator and an unknown class spelling — land on the rule's {@code loadError}
 * channel, and an accepted value is normalised to the resolver's spelling (D-5).
 */
class ModelClassDeclarationTest
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


    private static Operation normalize(String expression)
    {
        Operation op = new Operation();
        op.setId("$x");
        op.setExpression(expression);
        return OperationExpressionParser.normalize(op);
    }


    @Test
    void expressionForm_acceptsAndNormalisesAKnownClass()
    {
        Operation op = normalize("get_model_filtered_variables(model_class=\"events\")");
        assertEquals("get_model_filtered_variables", op.getOperator());
        assertEquals("EVENTS", op.getModelClass());
        assertEquals("FINDINGS ABOUT",
                normalize("get_model_filtered_variables(model_class=\" Findings About \")")
                        .getModelClass());
        // Composes with the role filter — the filter tail is shared.
        Operation composed = normalize(
                "get_model_filtered_variables(model_class=\"EVENTS\", key_name=\"role\","
                        + " key_value=\"Topic\")");
        assertEquals("EVENTS", composed.getModelClass());
        assertEquals("role", composed.getKeyName());
        assertEquals("Topic", composed.getKeyValue());
    }


    @Test
    void expressionForm_rejectsAnUnknownClassSpelling()
    {
        RuleDefinitionException ex = assertThrows(RuleDefinitionException.class,
                () -> normalize("get_model_filtered_variables(model_class=\"EVENT\")"));
        assertTrue(ex.getMessage().contains("unknown `model_class` value `EVENT`"),
                ex.getMessage());
        assertTrue(ex.getMessage().contains("EVENTS"), ex.getMessage());
    }


    @Test
    void expressionForm_rejectsANonConsumingOperator()
    {
        RuleDefinitionException ex = assertThrows(RuleDefinitionException.class,
                () -> normalize("get_model_column_order(model_class=\"EVENTS\")"));
        assertTrue(
                ex.getMessage()
                        .contains("`model_class` is only valid on operation"
                                + " `get_model_filtered_variables`, not `get_model_column_order`"),
                ex.getMessage());
    }


    @Test
    void fieldForm_isValidatedAndNormalisedByTheLoader()
    {
        Rule ok = load("{\"Core\":{\"Id\":\"X-1\"},\"Operations\":[{\"id\":\"$ev\","
                + "\"operator\":\"get_model_filtered_variables\",\"model_class\":\"events\"}],"
                + "\"Check\":{\"expression\":\"varname() in $ev\"}}");
        assertNull(ok.getLoadError());
        assertNotNull(ok.getOperations());
        assertEquals("EVENTS", ok.getOperations().get(0).getModelClass());

        Rule badClass = load("{\"Core\":{\"Id\":\"X-1\"},\"Operations\":[{\"id\":\"$ev\","
                + "\"operator\":\"get_model_filtered_variables\",\"model_class\":\"EVENT\"}],"
                + "\"Check\":{\"expression\":\"varname() in $ev\"}}");
        assertNotNull(badClass.getLoadError());
        assertTrue(badClass.getLoadError().contains("unknown `model_class` value"),
                badClass.getLoadError());

        Rule badOperator = load("{\"Core\":{\"Id\":\"X-1\"},\"Operations\":[{\"id\":\"$ev\","
                + "\"operator\":\"get_model_column_order\",\"model_class\":\"EVENTS\"}],"
                + "\"Check\":{\"expression\":\"varname() in $ev\"}}");
        assertNotNull(badOperator.getLoadError());
        assertTrue(badOperator.getLoadError().contains("only valid on operation"),
                badOperator.getLoadError());
    }


    @Test
    void inlineForm_isValidatedByTheLoader()
    {
        Rule bad = load("{\"Core\":{\"Id\":\"X-1\"},\"Check\":{\"expression\":"
                + "\"varname() in get_model_filtered_variables(model_class=\\\"EVENT\\\")\"}}");
        assertNotNull(bad.getLoadError());
        assertTrue(bad.getLoadError().contains("unknown `model_class` value"), bad.getLoadError());

        Rule ok = load("{\"Core\":{\"Id\":\"X-1\"},\"Check\":{\"expression\":"
                + "\"varname() in get_model_filtered_variables(model_class=\\\"EVENTS\\\")\"}}");
        assertNull(ok.getLoadError());
    }


    @Test
    void theVocabularyIsTheResolversOwn()
    {
        // The parser and MetadataLibraryProvider read ONE set of names, normalised ONE way.
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
        // resolver's in-walk normalisation cannot disagree (review finding: "special-purpose
        // datasets" normalises to SPECIAL-PURPOSE and then AGAIN to SPECIAL PURPOSE).
        for (String name : SdtmObservationClasses.MODEL_CLASS_NAMES)
        {
            assertEquals(name, SdtmObservationClasses.normalise(name), name);
        }
        assertThrows(RuleDefinitionException.class, () -> normalize(
                "get_model_filtered_variables(model_class=\"special-purpose datasets\")"));
        assertTrue(!SdtmObservationClasses.isDetectable(null));
        assertTrue(!SdtmObservationClasses.isDetectable("SPECIAL PURPOSE"));
    }
}
