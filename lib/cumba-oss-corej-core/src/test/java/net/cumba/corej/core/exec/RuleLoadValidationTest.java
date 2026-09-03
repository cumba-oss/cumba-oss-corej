package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * Fix #37 — load-time validation tests for the operand-substitution syntax.
 *
 * <p>
 * Each test builds a minimal rule package as a JSON string and loads it via
 * {@link RulePackageLoader#loadFromString(String)}. The validation pass walks the Check tree and
 * tags malformed substitution syntax / off-diagonal operators on the {@code Rule.loadError} field;
 * the rule is not removed from the package.
 * </p>
 */
class RuleLoadValidationTest
{

    private static String packageOf(String ruleJson)
    {
        return "{\"rules\":{\"rule-1\":" + ruleJson + "}}";
    }


    private static Rule onlyRule(RulePackage pkg)
    {
        return pkg.getRules().values().iterator().next();
    }


    @Test
    void offDiagonalOperator_wildcardInNameWithEqualTo_tagsLoadError() throws IOException
    {
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-001"},
                  "Check": {
                    "name": "PH${*}SDT",
                    "operator": "equal_to",
                    "value": "X"
                  }
                }
                """;
        RulePackage pkg = RulePackageLoader.loadFromString(packageOf(ruleJson));
        Rule rule = onlyRule(pkg);
        assertNotNull(rule.getLoadError(), "off-diagonal operator should tag loadError");
        assertTrue(rule.getLoadError().contains("TEST-001"),
                "loadError mentions the Core ID: " + rule.getLoadError());
        assertTrue(rule.getLoadError().contains("PH${*}SDT"),
                "loadError mentions the operand: " + rule.getLoadError());
        assertTrue(rule.getLoadError().toLowerCase(Locale.ROOT).contains("operator mismatch"),
                "loadError mentions operator mismatch: " + rule.getLoadError());
    }


    @Test
    void parseError_multipleWildcards_tagsLoadError() throws IOException
    {
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-002"},
                  "Check": {
                    "name": "${*}${*}SDT",
                    "operator": "var_exists"
                  }
                }
                """;
        RulePackage pkg = RulePackageLoader.loadFromString(packageOf(ruleJson));
        Rule rule = onlyRule(pkg);
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().toLowerCase(Locale.ROOT).contains("parse error"),
                "loadError mentions parse error: " + rule.getLoadError());
        assertTrue(rule.getLoadError().contains("TEST-002"));
    }


    @Test
    void validSubstitution_noLoadError() throws IOException
    {
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-003"},
                  "Check": {
                    "name": "AP${APERIOD:%02d}SDT",
                    "operator": "var_exists"
                  }
                }
                """;
        RulePackage pkg = RulePackageLoader.loadFromString(packageOf(ruleJson));
        Rule rule = onlyRule(pkg);
        assertNull(rule.getLoadError(),
                "valid substitution should leave loadError null but was: " + rule.getLoadError());
    }


    @Test
    void multipleErrorsInOneRule_joinedWithSemicolons() throws IOException
    {
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-004"},
                  "Check": {
                    "all": [
                      {"name": "PH${*}SDT", "operator": "equal_to", "value": "X"},
                      {"name": "${*}${*}SDT", "operator": "var_exists"}
                    ]
                  }
                }
                """;
        RulePackage pkg = RulePackageLoader.loadFromString(packageOf(ruleJson));
        Rule rule = onlyRule(pkg);
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().contains("; "),
                "multiple errors joined with `; `: " + rule.getLoadError());
        assertTrue(rule.getLoadError().contains("PH${*}SDT"),
                "first leaf error included: " + rule.getLoadError());
        assertTrue(rule.getLoadError().contains("${*}${*}SDT"),
                "second leaf error included: " + rule.getLoadError());
    }


    @Test
    void rulesWithoutSubstitution_unaffected() throws IOException
    {
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-005"},
                  "Check": {
                    "name": "AESTDY",
                    "operator": "non_empty"
                  }
                }
                """;
        RulePackage pkg = RulePackageLoader.loadFromString(packageOf(ruleJson));
        assertNull(onlyRule(pkg).getLoadError());
    }


    @Test
    void valuePosition_invalidWildcardOperator_tagsLoadError() throws IOException
    {
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-006"},
                  "Check": {
                    "name": "PHSDT",
                    "operator": "equal_to",
                    "value": "ADSL.PH${*}SDT"
                  }
                }
                """;
        RulePackage pkg = RulePackageLoader.loadFromString(packageOf(ruleJson));
        Rule rule = onlyRule(pkg);
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().toLowerCase(Locale.ROOT).contains("operator mismatch"),
                "loadError mentions operator mismatch: " + rule.getLoadError());
    }

    // ---- Fix #117/#118: closed-vocabulary Data_Structures / Subclasses tokens ----


    @Test
    void unknownDataStructureToken_tagsLoadError() throws IOException
    {
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-DS1"},
                  "Scope": {"Data_Structures": {"Include": ["BASIC DATA STRUCTUR"]}},
                  "Check": {"name": "STUDYID", "operator": "empty"}
                }
                """;
        Rule rule = onlyRule(RulePackageLoader.loadFromString(packageOf(ruleJson)));
        assertNotNull(rule.getLoadError(), "unknown structure token should tag loadError");
        assertTrue(rule.getLoadError().contains("BASIC DATA STRUCTUR"),
                "loadError names the bad token: " + rule.getLoadError());
    }


    @Test
    void unknownSubclassToken_tagsLoadError() throws IOException
    {
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-SC1"},
                  "Scope": {"Subclasses": {"Exclude": ["TIME TO EVENTS"]}},
                  "Check": {"name": "STUDYID", "operator": "empty"}
                }
                """;
        Rule rule = onlyRule(RulePackageLoader.loadFromString(packageOf(ruleJson)));
        assertNotNull(rule.getLoadError(), "unknown subclass token should tag loadError");
        assertTrue(rule.getLoadError().contains("TIME TO EVENTS"),
                "loadError names the bad token: " + rule.getLoadError());
    }


    @Test
    void knownTokensAndAllSentinel_loadClean() throws IOException
    {
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-DS2"},
                  "Scope": {
                    "Data_Structures": {"Include": ["ALL"], "Exclude": ["ADAM OTHER"]},
                    "Subclasses": {"Include": ["TIME-TO-EVENT", "ADVERSE EVENT"]}
                  },
                  "Check": {"name": "STUDYID", "operator": "empty"}
                }
                """;
        Rule rule = onlyRule(RulePackageLoader.loadFromString(packageOf(ruleJson)));
        assertTrue(rule.getLoadError() == null,
                "known tokens must load clean: " + rule.getLoadError());
    }


    /**
     * <b>Fix #179: the three medical-device structures are authorable.</b> Before it they folded
     * onto their bases inside the detector and were therefore <em>not</em> in
     * {@code AdamDataStructureDetector.STRUCTURE_TOKENS} — so authoring one tagged the rule with a
     * loadError and the semantically correct field was unusable for 345 of the corpus's 1394 ADaM
     * scope entries.
     */
    @Test
    void deviceStructureTokens_loadClean_fix179() throws IOException
    {
        for (String token : List.of("MEDICAL DEVICE BASIC DATA STRUCTURE",
                "MEDICAL DEVICE OCCURRENCE DATA STRUCTURE", "DEVICE LEVEL ANALYSIS DATASET"))
        {
            String ruleJson = """
                    {
                      "Core": {"Id": "TEST-DS3"},
                      "Scope": {"Data_Structures": {"Include": ["%s"]}},
                      "Check": {"name": "STUDYID", "operator": "empty"}
                    }
                    """.formatted(token);
            Rule rule = onlyRule(RulePackageLoader.loadFromString(packageOf(ruleJson)));
            assertTrue(rule.getLoadError() == null,
                    token + " must load clean: " + rule.getLoadError());
        }
        // Byte-exactness is unchanged — a case variant of a device token is still a loadError.
        String badJson = """
                {
                  "Core": {"Id": "TEST-DS4"},
                  "Scope": {"Data_Structures": {"Include": ["Medical Device Basic Data Structure"]}},
                  "Check": {"name": "STUDYID", "operator": "empty"}
                }
                """;
        Rule bad = onlyRule(RulePackageLoader.loadFromString(packageOf(badJson)));
        assertNotNull(bad.getLoadError(), "a case variant of a device token must still fail");
    }
}
