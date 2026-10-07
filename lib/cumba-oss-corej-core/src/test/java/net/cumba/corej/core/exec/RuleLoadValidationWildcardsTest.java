package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
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
                      {"expression": "var_exists(\\"TRTxxP\\")"}
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
                      {"expression": "var_exists(\\"TRTxxP\\")"}
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
                        {"expression": "not empty(*)"},
                        {"expression": "empty(*N)"}
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
                      {"expression": "not empty(*)"},
                      {"expression": "empty(*N)"}
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
                        {"expression": "var_exists(*N)"},
                        {"expression": "var_not_exists(*)"}
                      ]},
                      {"all": [
                        {"expression": "var_exists(*C)"},
                        {"expression": "var_not_exists(*)"}
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
                      {"expression": "var_exists(*N)"}
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
                      {"expression": "not empty(*GRy)"}
                    ]
                  }
                }
                """;
        RulePackage pkg = RulePackageLoader.loadFromString(packageOf(ruleJson));
        Rule rule = onlyRule(pkg);
        assertNull(rule.getLoadError(),
                "filter targeting y group on *GRy wildcard should not tag loadError");
    }

    // ---- PLAN-silently-ignored-authoring-inputs E2 / E3: the template-only directives


    private static Rule load(String ruleJson) throws IOException
    {
        return onlyRule(RulePackageLoader.loadFromString(packageOf(ruleJson)));
    }


    private static String rule(String id, String expression, String directive)
    {
        return """
                {
                  "Core": {"Id": "%s"},
                  %s,
                  "Check": {"all": [{"expression": "%s"}]}
                }
                """.formatted(id, directive, expression.replace("\"", "\\\""));
    }

    /** The eleven released cursor rules' shape: a {@code stem=}-like literal is not a marker. */
    private static final String CURSOR = "ends_with(upper(varname()), \"DT\")"
            + " and var_type(\"DATA\") != \"Num\"";

    /** An {@code xx} template that is NOT a bare-{@code *} pairing template (review R1-1). */
    private static final String XX_TEMPLATE = "not empty(TRTxxP)";

    /**
     * E2: {@code wildcardExclude} drops an excluded secondary of ANY template
     * ({@code WildcardExpander.expand}, every non-bare-{@code *} wildcard); on a rule that is not a
     * template nothing is expanded and the list is read by nothing — a load error. Red-first: the
     * refusal loaded clean before E2.
     */
    @Test
    void wildcardExcludeOnANonTemplateIsALoadError() throws IOException
    {
        String error = load(
                rule("TEST-E2-A", "not empty(AETERM)", "\"wildcardExclude\": [\"^AE\"]"))
                        .getLoadError();
        assertNotNull(error, "a wildcardExclude nothing reads must be refused");
        assertTrue(error.contains("[TEST-E2-A] wildcardExclude has no effect on a rule that is not"
                + " a wildcard template"), error);
        String cursor = load(rule("TEST-E2-B", CURSOR, "\"wildcardExclude\": [\"^AE\"]"))
                .getLoadError();
        assertNotNull(cursor, "a varname() cursor rule is not a template");
        // the planted control: any template honours it, not only a pairing template (R1-1)
        assertNull(load(rule("TEST-E2-C", XX_TEMPLATE, "\"wildcardExclude\": [\"^TRT02\"]"))
                .getLoadError());
    }


    /**
     * E3: {@code skipIfLibraryDefined: true} is read only by {@code applyTemplatePostFilters}, on a
     * template's expansions; on a non-template — the eleven released {@code varname()} cursor
     * rules' shape — it is read by nothing, a load error. {@code false} states the default and
     * loads. Red-first: the refusals loaded clean before E3.
     */
    @Test
    void skipIfLibraryDefinedOnANonTemplateIsALoadError() throws IOException
    {
        String error = load(rule("TEST-E3-A", CURSOR, "\"skipIfLibraryDefined\": true"))
                .getLoadError();
        assertNotNull(error, "a skipIfLibraryDefined nothing reads must be refused");
        assertTrue(error.contains("[TEST-E3-A] skipIfLibraryDefined has no effect on a rule that is"
                + " not a wildcard template"), error);
        assertNotNull(load(rule("TEST-E3-B", "not empty(AETERM)", "\"skipIfLibraryDefined\": true"))
                .getLoadError());
        // the planted controls
        assertNull(load(rule("TEST-E3-C", XX_TEMPLATE, "\"skipIfLibraryDefined\": true"))
                .getLoadError());
        assertNull(
                load(rule("TEST-E3-D", CURSOR, "\"skipIfLibraryDefined\": false")).getLoadError());
    }
}
