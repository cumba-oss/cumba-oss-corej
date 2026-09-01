package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * Fix #24 — load-time validation of {@code wildcards} group keys against the capture-group tokens
 * present in the rule's Check leaves.
 *
 * <p>
 * A typo'd or otherwise-invalid group key (e.g. {@code "xy"} when only {@code xx} appears in any
 * leaf wildcard) silently does nothing during expansion, masking the author's intent. Surfacing it
 * as a load error catches it at boot rather than at validation runtime.
 * </p>
 */
class RuleLoadValidationWildcardsTest
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
    void validGroupKey_noLoadError() throws IOException
    {
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-024-A"},
                  "wildcards": {"xx": {"min": 2}},
                  "Check": {
                    "all": [
                      {"name": "TRTxxP", "operator": "var_exists"}
                    ]
                  }
                }
                """;
        RulePackage pkg = RulePackageLoader.loadFromString(packageOf(ruleJson));
        Rule rule = onlyRule(pkg);
        assertNull(rule.getLoadError(),
                "valid xx filter on a TRTxxP wildcard should not tag loadError");
        assertNotNull(rule.getWildcards());
        assertNotNull(rule.getWildcards().get("xx"));
    }


    @Test
    void unknownGroupKey_tagsLoadError() throws IOException
    {
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-024-B"},
                  "wildcards": {"xy": {"min": 2}},
                  "Check": {
                    "all": [
                      {"name": "TRTxxP", "operator": "var_exists"}
                    ]
                  }
                }
                """;
        RulePackage pkg = RulePackageLoader.loadFromString(packageOf(ruleJson));
        Rule rule = onlyRule(pkg);
        assertNotNull(rule.getLoadError(), "unknown group key 'xy' should tag loadError");
        assertTrue(rule.getLoadError().contains("xy"),
                "loadError should mention the offending key");
    }


    @Test
    void wildcardsOnRuleWithoutCheck_tagsLoadError() throws IOException
    {
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-024-C"},
                  "wildcards": {"xx": {"min": 2}}
                }
                """;
        RulePackage pkg = RulePackageLoader.loadFromString(packageOf(ruleJson));
        Rule rule = onlyRule(pkg);
        assertNotNull(rule.getLoadError(), "wildcards without a Check tree should tag loadError");
    }

    // ---- Fix #84 (Group B / B4): wildcardExclude / wildcardPairCatalogue validation ----------


    @Test
    void wildcardExcludeOnValidPairingTemplate_noLoadError() throws IOException
    {
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-080-A"},
                  "wildcardExclude": ["TRTPN", "*FN"],
                  "Check": {
                    "any": [
                      {"all": [
                        {"name": "*", "operator": "non_empty"},
                        {"name": "*N", "operator": "empty"}
                      ]}
                    ]
                  }
                }
                """;
        RulePackage pkg = RulePackageLoader.loadFromString(packageOf(ruleJson));
        Rule rule = onlyRule(pkg);
        assertNull(rule.getLoadError(), "valid wildcardExclude should not tag loadError");
        assertNotNull(rule.getWildcardExclude());
    }


    @Test
    void wildcardExcludeWithBlankEntry_tagsLoadError() throws IOException
    {
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-080-B"},
                  "wildcardExclude": ["TRTPN", "  "],
                  "Check": {
                    "all": [
                      {"name": "*", "operator": "non_empty"},
                      {"name": "*N", "operator": "empty"}
                    ]
                  }
                }
                """;
        RulePackage pkg = RulePackageLoader.loadFromString(packageOf(ruleJson));
        Rule rule = onlyRule(pkg);
        assertNotNull(rule.getLoadError(), "blank wildcardExclude entry should tag loadError");
        assertTrue(rule.getLoadError().contains("wildcardExclude"));
    }


    @Test
    void pairCatalogueWithBareStarAndAnchor_noLoadError() throws IOException
    {
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-080-C"},
                  "wildcardPairCatalogue": true,
                  "Check": {
                    "any": [
                      {"all": [
                        {"name": "*N", "operator": "var_exists"},
                        {"name": "*", "operator": "var_not_exists"}
                      ]},
                      {"all": [
                        {"name": "*C", "operator": "var_exists"},
                        {"name": "*", "operator": "var_not_exists"}
                      ]}
                    ]
                  }
                }
                """;
        RulePackage pkg = RulePackageLoader.loadFromString(packageOf(ruleJson));
        Rule rule = onlyRule(pkg);
        assertNull(rule.getLoadError(),
                "catalogue with bare-* primary + *N/*C anchor should not tag loadError");
    }


    @Test
    void pairCatalogueWithoutBareStar_tagsLoadError() throws IOException
    {
        // No bare "*" primary leaf — the catalogue directive is meaningless.
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-080-D"},
                  "wildcardPairCatalogue": true,
                  "Check": {
                    "all": [
                      {"name": "*N", "operator": "var_exists"}
                    ]
                  }
                }
                """;
        RulePackage pkg = RulePackageLoader.loadFromString(packageOf(ruleJson));
        Rule rule = onlyRule(pkg);
        assertNotNull(rule.getLoadError(),
                "wildcardPairCatalogue without a bare-* primary should tag loadError");
        assertTrue(rule.getLoadError().contains("wildcardPairCatalogue"));
    }


    @Test
    void wildcardsAcrossMultipleLeafGroups_validKeyResolves() throws IOException
    {
        // *GRy uses both * and y groups; the filter targets y.
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-024-D"},
                  "wildcards": {"y": {"min": 2}},
                  "Check": {
                    "all": [
                      {"name": "*GRy", "operator": "non_empty"}
                    ]
                  }
                }
                """;
        RulePackage pkg = RulePackageLoader.loadFromString(packageOf(ruleJson));
        Rule rule = onlyRule(pkg);
        assertNull(rule.getLoadError(),
                "filter targeting y group on *GRy wildcard should not tag loadError");
    }
}
