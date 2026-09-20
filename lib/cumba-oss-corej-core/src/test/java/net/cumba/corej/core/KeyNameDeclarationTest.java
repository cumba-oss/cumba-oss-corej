package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import net.cumba.corej.core.expr.RuleDefinitionException;
import net.cumba.corej.core.expr.convert.OperationExpressionParser;
import net.cumba.corej.core.metadata.LibraryVariableAttributes;
import net.cumba.corej.core.model.Operation;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⭐ The {@code key_name} declaration is validated at <b>rule load</b>, on all three authoring
 * surfaces (field form, expression form, inline call).
 *
 * <p>
 * <b>Why this test exists.</b> {@code FDA-SD1078} shipped as
 * {@code get_model_filtered_variables(key_name="core", key_value="Perm")} — a Model-level walk with
 * a key that walk does not publish for a standard domain. The filter matched nothing,
 * {@code is_contained_by []} was always false, and the rule could never fire: a 100 % under-report
 * with no SKIP, no error and no log line. A human reading metadata caught it (corpus review R2,
 * {@code OPS-MISC-03}); nothing in the engine or the suite could.
 * </p>
 *
 * <p>
 * ⛔ <b>And this test is equally the pin on what is deliberately NOT rejected.</b> {@code core} on
 * {@code get_model_filtered_variables} stays a legal declaration, because the Model walk publishes
 * {@code core} for SUPP--/SQ-- datasets and for every ADaM dataset — see
 * {@link #coreOnTheModelWalkIsNotALoadError}. That half is a runtime diagnostic
 * ({@code UnservedKeyNameDiagnosticTest}), not a load error.
 * </p>
 */
class KeyNameDeclarationTest
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
    void expressionForm_acceptsTheLibraryVariableVocabularyOnBothFilters()
    {
        assertEquals("role",
                normalize("get_model_filtered_variables(key_name=\"role\", key_value=\"Timing\")")
                        .getKeyName());
        assertEquals("core",
                normalize("get_dataset_filtered_variables(key_name=\"core\", key_value=\"Perm\")")
                        .getKeyName());
        // Every member of the vocabulary is authorable on both filters — no half-served key.
        for (String key : LibraryVariableAttributes.KEYS)
        {
            assertEquals(key, normalize("get_model_filtered_variables(key_name=\"" + key + "\")")
                    .getKeyName(), key);
            assertEquals(key, normalize("get_dataset_filtered_variables(key_name=\"" + key + "\")")
                    .getKeyName(), key);
        }
    }


    @Test
    @DisplayName("T7's key_name is a dataset column, so the library vocabulary does not apply")
    void expressionForm_leavesTsParameterValueAlone()
    {
        Operation op = normalize("ts_parameter_value(TSVAL, domain=\"TS\", key_name=\"TSPARMCD\","
                + " key_value=\"EXPSTDTC\")");
        assertEquals("TSPARMCD", op.getKeyName());
        assertEquals("TSVAL", op.getName());
        // TXPARMCD likewise — neither is a library attribute, and neither may be rejected.
        assertEquals("TXPARMCD",
                normalize("ts_parameter_value(TXVAL, domain=\"TX\", key_name=\"TXPARMCD\","
                        + " key_value=\"SPECIES\")").getKeyName());
    }


    @Test
    @DisplayName("a key no metadata level publishes is a load error, not an empty set")
    void expressionForm_rejectsAKeyNoLevelPublishes()
    {
        RuleDefinitionException ex = assertThrows(RuleDefinitionException.class, () -> normalize(
                "get_model_filtered_variables(key_name=\"valueList\"," + " key_value=\"DM\")"));
        assertTrue(ex.getMessage().contains("`key_name` `valueList`"), ex.getMessage());
        assertTrue(ex.getMessage().contains("can never match"), ex.getMessage());
        // The message must name what the level DOES serve — that is the whole point of it.
        for (String key : LibraryVariableAttributes.KEYS)
        {
            assertTrue(ex.getMessage().contains(key), ex.getMessage() + " // missing " + key);
        }
        // ⭐ P2's ruling, and the ONLY keys still rejected on the two library filters: the three
        // list-valued stored fields. The row is Map<String,String> and key_value is a scalar, and
        // Python compares the raw payload object — `var.get("valueList") == "DM"` compares a list
        // to a string and is always false. Supporting them would be supporting a no-op; a load
        // error makes the no-op loud. Encoding them (DefineMetadataListCodec or otherwise) would
        // invent comparison semantics Python does not have.
        for (String key : Set.of("valueList", "codelistSubmissionValues", "codelistIds"))
        {
            assertThrows(RuleDefinitionException.class,
                    () -> normalize("get_model_filtered_variables(key_name=\"" + key + "\")"), key);
            assertThrows(RuleDefinitionException.class,
                    () -> normalize("get_dataset_filtered_variables(key_name=\"" + key + "\")"),
                    key);
        }
        // A typo is the other half of the complement, and the common case in practice.
        assertThrows(RuleDefinitionException.class,
                () -> normalize("get_model_filtered_variables(key_name=\"Core\")"));
        assertThrows(RuleDefinitionException.class,
                () -> normalize("get_dataset_filtered_variables(key_name=\"simple_datatype\")"));
    }


    @Test
    @DisplayName("⭐ P3: the SEND template's whole legal vocabulary now loads, on BOTH filters")
    void expressionForm_acceptsTheWidenedScalarVocabulary()
    {
        // The SEND authoring template's legal key_name values. Before P3 four of them
        // (definition / examples / notes / variableCcode) were load errors: the resolver row
        // carried six keys and the template's contract was simply unmet.
        for (String key : Set.of("definition", "examples", "label", "name", "notes", "ordinal",
                "role", "simpleDatatype", "variableCcode"))
        {
            assertEquals(key, normalize("get_model_filtered_variables(key_name=\"" + key + "\")")
                    .getKeyName(), key);
            assertEquals(key, normalize("get_dataset_filtered_variables(key_name=\"" + key + "\")")
                    .getKeyName(), key);
        }
        // …and the remaining scalars the products publish, which the template does not name.
        for (String key : Set.of("core", "description", "roleDescription", "usageRestrictions",
                "describedValueDomain"))
        {
            assertEquals(key, normalize("get_model_filtered_variables(key_name=\"" + key + "\")")
                    .getKeyName(), key);
            assertEquals(key, normalize("get_dataset_filtered_variables(key_name=\"" + key + "\")")
                    .getKeyName(), key);
        }
        // ⛔ Both filters, deliberately. There is ONE vocabulary, not one per level — see
        // LibraryVariableRowBreadthTest, which measures both walks against the real resolver.
        Rule ok = load(
                "{\"Core\":{\"Id\":\"X-1\"},\"Bindings\":[{\"name\": \"$t\", \"expression\": \"get_model_filtered_variables(key_name=\\\"notes\\\", key_value=\\\"ISO 8601.\\\")\"}],\"Check\":{\"expression\":\"varname() in $t\"}}");
        assertNull(ok.getLoadError());
    }


    @Test
    @DisplayName("get_column_order_from_library documents key_name but coreJ never reads it")
    void expressionForm_rejectsANonConsumingOperator()
    {
        // ⚠⚠ Not merely "empty": coreJ's arm returns the UNFILTERED column order, so a silently
        // accepted key_name here makes the operation answer a different question than authored.
        RuleDefinitionException ex = assertThrows(RuleDefinitionException.class, () -> normalize(
                "get_column_order_from_library(key_name=\"role\"," + " key_value=\"Timing\")"));
        assertTrue(ex.getMessage().contains(
                "`key_name` is not consumed by operation" + " `get_column_order_from_library`"),
                ex.getMessage());
        assertTrue(ex.getMessage().contains("silently dropped"), ex.getMessage());
        // Any other operation is the same story — the field is simply dead there.
        assertThrows(RuleDefinitionException.class,
                () -> normalize("get_model_column_order(key_name=\"role\")"));
        assertThrows(RuleDefinitionException.class,
                () -> normalize("distinct(USUBJID, key_name=\"role\")"));
    }


    @Test
    void fieldForm_isValidatedByTheLoader()
    {
        Rule ok = load(
                "{\"Core\":{\"Id\":\"X-1\"},\"Bindings\":[{\"name\": \"$t\", \"expression\": \"get_model_filtered_variables(key_name=\\\"role\\\", key_value=\\\"Timing\\\")\"}],\"Check\":{\"expression\":\"varname() in $t\"}}");
        assertNull(ok.getLoadError());
        assertNotNull(ok.getOperations());
        assertEquals("role", ok.getOperations().get(0).getKeyName());

        Rule badKey = load(
                "{\"Core\":{\"Id\":\"X-1\"},\"Bindings\":[{\"name\": \"$t\", \"expression\": \"get_model_filtered_variables(key_name=\\\"valueList\\\", key_value=\\\"x\\\")\"}],\"Check\":{\"expression\":\"varname() in $t\"}}");
        assertNotNull(badKey.getLoadError());
        assertTrue(badKey.getLoadError().contains("`key_name` `valueList`"), badKey.getLoadError());

        Rule badOperator = load(
                "{\"Core\":{\"Id\":\"X-1\"},\"Bindings\":[{\"name\": \"$t\", \"expression\": \"get_column_order_from_library(key_name=\\\"role\\\")\"}],"
                        + "\"Check\":{\"expression\":\"varname() in $t\"}}");
        assertNotNull(badOperator.getLoadError());
        assertTrue(badOperator.getLoadError().contains("is not consumed by operation"),
                badOperator.getLoadError());
    }


    @Test
    void inlineForm_isValidatedByTheLoader()
    {
        Rule bad = load("{\"Core\":{\"Id\":\"X-1\"},\"Check\":{\"expression\":"
                + "\"varname() in get_model_filtered_variables(key_name=\\\"valueList\\\","
                + " key_value=\\\"x\\\")\"}}");
        assertNotNull(bad.getLoadError());
        assertTrue(bad.getLoadError().contains("`key_name` `valueList`"), bad.getLoadError());

        Rule ok = load("{\"Core\":{\"Id\":\"X-1\"},\"Check\":{\"expression\":"
                + "\"varname() in get_model_filtered_variables(key_name=\\\"role\\\","
                + " key_value=\\\"Timing\\\")\"}}");
        assertNull(ok.getLoadError());
    }


    @Test
    @DisplayName("⛔ core on the Model walk is legal — SUPP/SQ and ADaM publish it")
    void coreOnTheModelWalkIsNotALoadError()
    {
        // The FDA-SD1078 shape itself. A blanket reject here would be WRONG: measured in
        // MetadataLibraryProvider, the Model walk publishes `core` for SUPP--/SQ-- datasets (the
        // cascade's tier A projects IG SUPPQUAL dataset variables through substituteAndResolve,
        // its tier C is the hard-coded RELATIONSHIP fallback, which sets `core` literally) and for
        // every ADaM dataset (buildResolvedAdam copies AdamVariable.core()). Servability is a
        // per-dataset runtime fact, so the diagnostic for it is a runtime WARNING — see
        // net.cumba.corej.core.exec.UnservedKeyNameDiagnosticTest.
        Operation op = normalize(
                "get_model_filtered_variables(key_name=\"core\", key_value=\"Perm\")");
        assertEquals("core", op.getKeyName());
        assertEquals("Perm", op.getKeyValue());
        Rule ok = load(
                "{\"Core\":{\"Id\":\"X-1\"},\"Bindings\":[{\"name\": \"$t\", \"expression\": \"get_model_filtered_variables(key_name=\\\"core\\\", key_value=\\\"Perm\\\")\"}],\"Check\":{\"expression\":\"varname() in $t\"}}");
        assertNull(ok.getLoadError());
        // Symmetrically: `role` is absent from every ADaM row (buildResolvedAdam leaves it out),
        // so it must not be rejected on either filter either.
        assertEquals("role",
                normalize("get_dataset_filtered_variables(key_name=\"role\")").getKeyName());
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
