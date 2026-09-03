package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import net.cumba.corej.core.model.Executability;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.corej.core.model.Sensitivity;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 of {@code plans/PLAN-extend-expression-engine.md} — serialization round-trip for the
 * setter-based {@code Sensitivity} / {@code Executability} binding on {@link Rule}: valid values
 * serialize canonically, invalid raw strings round-trip verbatim, and the JSON carries exactly one
 * capitalized key per field (guarding the Lombok/Jackson duplicate-property hazard — without
 * {@code @JsonIgnore} on the typed and raw fields Jackson would also emit
 * {@code rawSensitivity}/{@code rawExecutability} properties). Since the leaf-scope plan retired
 * {@code Rule_Type}, the JSON must carry no such key at all.
 */
class RuleEnumRoundTripTest
{

    private static String packageOf(String ruleJson)
    {
        return "{\"rules\":{\"rule-1\":" + ruleJson + "}}";
    }


    private static Rule onlyRule(RulePackage pkg)
    {
        return pkg.getRules().values().iterator().next();
    }


    private static int countOccurrences(String haystack, String needle)
    {
        int count = 0;
        int idx = haystack.indexOf(needle);
        while (idx >= 0)
        {
            count++;
            idx = haystack.indexOf(needle, idx + needle.length());
        }
        return count;
    }


    @Test
    void validEnums_roundTripCanonically_withoutDuplicateProperties() throws IOException
    {
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-301"},
                  "Sensitivity": "Record",
                  "Executability": "Fully Executable",
                  "Check": {"name": "AESTDY", "operator": "non_empty"}
                }
                """;
        Rule loaded = onlyRule(RulePackageLoader.loadFromString(packageOf(ruleJson)));
        String json = RulePackageLoader.toJson(loaded);

        assertEquals(0, countOccurrences(json, "\"Rule_Type\""), "no Rule_Type key: " + json);
        assertEquals(1, countOccurrences(json, "\"Sensitivity\""),
                "exactly one Sensitivity key: " + json);
        assertEquals(1, countOccurrences(json, "\"Executability\""),
                "exactly one Executability key: " + json);
        // Guard the Lombok/Jackson hazard: the @JsonIgnore'd typed and raw fields must not leak
        // as camelCase duplicate properties.
        assertFalse(json.contains("\"rejectedRuleType\""), "no rejectedRuleType key: " + json);
        assertFalse(json.contains("\"rawSensitivity\""), "no rawSensitivity key: " + json);
        assertFalse(json.contains("\"rawExecutability\""), "no rawExecutability key: " + json);

        Rule reloaded = onlyRule(RulePackageLoader.loadFromString(packageOf(json)));
        assertNull(reloaded.getLoadError());
        assertEquals(Sensitivity.RECORD, reloaded.getSensitivity());
        assertEquals(Executability.FULLY_EXECUTABLE, reloaded.getExecutability());
    }


    @Test
    void invalidEnums_rawStringsRoundTripVerbatim() throws IOException
    {
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-302"},
                  "Sensitivity": "Variable",
                  "Executability": "Mostly Executable",
                  "Check": {"name": "AESTDY", "operator": "non_empty"}
                }
                """;
        Rule loaded = onlyRule(RulePackageLoader.loadFromString(packageOf(ruleJson)));
        assertNotNull(loaded.getLoadError(), "invalid values tag a loadError");

        String json = RulePackageLoader.toJson(loaded);
        assertTrue(json.contains("\"Sensitivity\":\"Variable\""),
                "invalid Sensitivity kept verbatim: " + json);
        assertTrue(json.contains("\"Executability\":\"Mostly Executable\""),
                "invalid Executability kept verbatim: " + json);
        assertFalse(json.contains("\"rawSensitivity\""), "no raw field leak: " + json);

        Rule reloaded = onlyRule(RulePackageLoader.loadFromString(packageOf(json)));
        assertEquals("Variable", reloaded.getRawSensitivity());
        assertEquals("Mostly Executable", reloaded.getRawExecutability());
        assertNull(reloaded.getSensitivity());
        assertNull(reloaded.getExecutability());
        assertNotNull(reloaded.getLoadError(), "reload re-tags the loadError");
    }


    @Test
    void absentEnums_areDerivedAndSurviveTheRoundTrip() throws IOException
    {
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-303"},
                  "Check": {"name": "AESTDY", "operator": "non_empty"}
                }
                """;
        Rule loaded = onlyRule(RulePackageLoader.loadFromString(packageOf(ruleJson)));
        String json = RulePackageLoader.toJson(loaded);
        Rule reloaded = onlyRule(RulePackageLoader.loadFromString(packageOf(json)));
        assertNull(reloaded.getLoadError());
        // PLAN-derive-rule-type-sensitivity phase 6: the loader derives Sensitivity, and the typed
        // setter keeps the raw JSON in sync, so serializing a loaded rule writes it out
        // explicitly.
        assertEquals(Sensitivity.RECORD, reloaded.getSensitivity());
        // Executability is not derivable, so it stays absent through the round trip.
        assertNull(reloaded.getExecutability());
    }


    @Test
    void typedSetters_syncRawField_noStaleRawAfterClearing() throws IOException
    {
        // Review F5: the typed setters are hand-written to keep the raw JSON string in sync.
        // Loading an invalid raw and then programmatically clearing the typed field must NOT
        // leave the stale raw string resurfacing on serialization.
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-304"},
                  "Sensitivity": "Variable",
                  "Executability": "Mostly Executable",
                  "Check": {"name": "AESTDY", "operator": "non_empty"}
                }
                """;
        Rule loaded = onlyRule(RulePackageLoader.loadFromString(packageOf(ruleJson)));
        assertEquals("Variable", loaded.getRawSensitivity());

        loaded.setSensitivity(null);
        loaded.setExecutability(null);
        assertNull(loaded.getRawSensitivity(), "typed null clears the raw");
        assertNull(loaded.getRawExecutability());
        assertNull(loaded.getSensitivityJson(), "no stale raw on the JSON getter");

        String json = RulePackageLoader.toJson(loaded);
        assertFalse(json.contains("\"Sensitivity\":\"Variable\""), json);
        assertFalse(json.contains("Mostly Executable"), json);

        // Setting a valid typed value writes the canonical raw string.
        loaded.setSensitivity(Sensitivity.RECORD);
        assertEquals("Record", loaded.getRawSensitivity(),
                "typed non-null syncs the canonical raw");
        assertEquals("Record", loaded.getSensitivityJson());
        json = RulePackageLoader.toJson(loaded);
        assertTrue(json.contains("\"Sensitivity\":\"Record\""), json);

        // And the reloaded rule is consistent (no loadError from a stale invalid raw).
        Rule reloaded = onlyRule(RulePackageLoader.loadFromString(packageOf(json)));
        assertEquals(Sensitivity.RECORD, reloaded.getSensitivity());
        assertNull(reloaded.getLoadError());
    }


    @Test
    void programmaticTypedSetter_serializesCanonicalValue()
    {
        // The Lombok typed setters stay the programmatic construction path (LibraryRuleMapper,
        // WildcardExpander, RuleGenerator); the @JsonGetter must emit the canonical value even
        // though no raw string was ever bound.
        Rule rule = new Rule();
        rule.setSensitivity(Sensitivity.DATASET);
        rule.setExecutability(Executability.NOT_EXECUTABLE);
        String json = RulePackageLoader.toJson(rule);
        assertTrue(json.contains("\"Sensitivity\":\"Dataset\""), json);
        assertTrue(json.contains("\"Executability\":\"Not Executable\""), json);
    }
}
