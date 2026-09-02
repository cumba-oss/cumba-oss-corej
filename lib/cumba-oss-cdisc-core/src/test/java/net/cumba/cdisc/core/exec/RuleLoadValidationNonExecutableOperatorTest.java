package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * P1b of {@code plans/done/PLAN-native-engine-full-coverage.md} — a rule using an operator that NO
 * engine implements must surface as a <b>load error</b> instead of silently passing at runtime
 * (user decision: option (b), 2026-06-11). The remaining non-executable operators are the
 * affix-compare variants ({@code prefix_is_contained_by}, {@code suffix_equal_to}, …) and the
 * {@code dataset_metadata} operand.
 *
 * <p>
 * Note: {@code is_not_contained_by_case_insensitive} is NO LONGER non-executable — it is now
 * implemented on both engines as case-insensitive negative membership (PLAN-regex-rule-optimization
 * Phase 1: native {@code Primitives.membership(…, negate=true,
 * caseInsensitive=true)}). The tests below assert it now loads clean.
 * </p>
 */
class RuleLoadValidationNonExecutableOperatorTest
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
    void nonExecutableOperator_tagsLoadError() throws IOException
    {
        // prefix_is_contained_by remains non-executable (no engine implements it) — it must still
        // surface as a load error.
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-P1B-A"},
                  "Sensitivity": "Record",
                  "Check": {
                    "all": [
                      {"name": "DSDECOD", "operator": "prefix_is_contained_by",
                       "value": ["COMPLETED"]}
                    ]
                  }
                }
                """;
        RulePackage pkg = RulePackageLoader.loadFromString(packageOf(ruleJson));
        Rule rule = onlyRule(pkg);
        assertNotNull(rule.getLoadError(), "non-executable operator must tag a loadError");
        assertTrue(rule.getLoadError().contains("prefix_is_contained_by"),
                "loadError must name the operator: " + rule.getLoadError());
        assertNull(rule.getCheckExpr(), "a loadError rule never retains a native checkExpr");
    }


    @Test
    void caseInsensitiveNegativeMembership_staysClean() throws IOException
    {
        // is_not_contained_by_case_insensitive is now implemented on both engines (case-insensitive
        // negative membership) — it must NOT be tagged as a load error.
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-P1B-CI"},
                  "Sensitivity": "Record",
                  "Check": {
                    "all": [
                      {"name": "DSDECOD", "operator": "is_not_contained_by_case_insensitive",
                       "value": ["COMPLETED"]}
                    ]
                  }
                }
                """;
        RulePackage pkg = RulePackageLoader.loadFromString(packageOf(ruleJson));
        assertNull(onlyRule(pkg).getLoadError(),
                "is_not_contained_by_case_insensitive is implemented and must load clean");
    }


    @Test
    void implementedCaseInsensitiveOperator_staysClean() throws IOException
    {
        // The POSITIVE is_contained_by_case_insensitive IS implemented (legacy + native) — it must
        // not be affected by the non-executable tagging.
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-P1B-B"},
                  "Sensitivity": "Record",
                  "Check": {
                    "all": [
                      {"name": "DSDECOD", "operator": "is_contained_by_case_insensitive",
                       "value": ["COMPLETED"]}
                    ]
                  }
                }
                """;
        RulePackage pkg = RulePackageLoader.loadFromString(packageOf(ruleJson));
        assertNull(onlyRule(pkg).getLoadError());
    }


    @Test
    void nonExecutableOperand_datasetMetadata_tagsLoadError() throws IOException
    {
        // R-P4 (PLAN-native-engine-residuals): dataset_metadata has no resolution on either
        // engine — a leaf naming it (ADAM-ADD-100019's shape) has never fired anywhere. It must
        // fail loudly at load (user decision, 2026-06-11).
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-RP4-A"},
                  "Sensitivity": "Dataset",
                  "Check": {
                    "all": [
                      {"name": "dataset_metadata", "operator": "empty"}
                    ]
                  }
                }
                """;
        RulePackage pkg = RulePackageLoader.loadFromString(packageOf(ruleJson));
        Rule rule = onlyRule(pkg);
        assertNotNull(rule.getLoadError(), "dataset_metadata operand must tag a loadError");
        assertTrue(rule.getLoadError().contains("dataset_metadata"),
                "loadError must name the operand: " + rule.getLoadError());
        assertNull(rule.getCheckExpr());
    }


    @Test
    @Disabled("rules-adamig-1-3-additions.json temporarily moved. "
            + "Re-enable when the additions corpus is restored.")
    void shippedAdam100019IsTagged() throws IOException
    {
        RulePackage pkg = RulePackageLoader.loadCombined(Path.of(
                System.getProperty("projectBasedir"), "src/test/resources/fixtures/rules/packages",
                "rules-adamig-1-3-additions.json"));
        Rule rule = pkg.getRules().values().stream().filter(r -> r != null && r.getCore() != null
                && "ADAM-ADD-100019".equals(r.getCore().getId())).findFirst().orElseThrow();
        assertNotNull(rule.getLoadError(), "ADAM-ADD-100019 must carry the operand loadError");
        assertTrue(rule.getLoadError().contains("dataset_metadata"));
    }


    @Test
    @Disabled("rules-adamig-1-3-additions.json temporarily moved. "
            + "Re-enable when the additions corpus is restored.")
    void expressionFormDatasetMetadataIsTaggedToo() throws IOException
    {
        // R-P7 review MINOR: the expression corpus ships `"expression": "empty(dataset_metadata)"`
        // — the deserializer lowers it to the operand leaf, so the SAME loadError must apply when
        // loading the expression-syntax package.
        RulePackage pkg = RulePackageLoader.loadCombined(Path.of(
                System.getProperty("projectBasedir"), "src/test/resources/fixtures/rules/packages",
                "rules-adamig-1-3-additions.json"));
        Rule rule = pkg.getRules().values().stream().filter(r -> r != null && r.getCore() != null
                && "ADAM-ADD-100019".equals(r.getCore().getId())).findFirst().orElseThrow();
        assertNotNull(rule.getLoadError(),
                "the expression-form rule must carry the operand loadError too");
        assertTrue(rule.getLoadError().contains("dataset_metadata"));
    }


    @Test
    void shippedCorpusRulesUsingOperatorLoadClean() throws IOException
    {
        // The shipped sdtmig-3-2 rules that use is_not_contained_by_case_insensitive must NO LONGER
        // be tagged with a load error: the operator is implemented on both engines, so those rules
        // now load clean and fire (previously they were silent no-ops tagged as non-executable).
        RulePackage pkg = RulePackageLoader
                .loadCombined(Path.of(System.getProperty("projectBasedir"),
                        "src/test/resources/fixtures/rules/packages", "rules-sdtmig-3-2.json"));
        for (Rule rule : pkg.getRules().values())
        {
            if (rule != null && rule.getLoadError() != null)
            {
                assertTrue(!rule.getLoadError().contains("is_not_contained_by_case_insensitive"),
                        "is_not_contained_by_case_insensitive must not tag a loadError: "
                                + rule.getLoadError());
            }
        }
    }

}
