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
 * Phase 6 of {@code PLAN-leaf-scope-domain-inference.md} re-grounded the guard-residual gate of
 * 2026-06-12: a {@code library_dataset_*} / {@code define_dataset_*} operand is <b>legal on every
 * rule</b> — it canonicalises to its {@code ds_*("LIBRARY" / "DEFINE")} accessor for every rule,
 * not only the former dataset-metadata family — and the only thing that still fails at load is an
 * operand <em>no accessor serves</em> (which would otherwise fold against the empty string).
 */
class RuleLoadValidationDatasetProviderOperandTest
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
    void libraryDatasetOperandOnAnyRule_canonicalisesToTheAccessor() throws IOException
    {
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-GR-C1"},
                  "Sensitivity": "Record",
                  "Check": {
                    "all": [
                      {"name": "library_dataset_class", "operator": "equal_to",
                       "value": "EVENTS", "value_is_literal": true}
                    ]
                  }
                }
                """;
        Rule rule = onlyRule(RulePackageLoader.loadFromString(packageOf(ruleJson)));
        assertNull(rule.getLoadError(), rule.getLoadError());
        assertNotNull(rule.getCheckExpr());
        assertTrue(String.valueOf(rule.getCheckExpr()).contains("ds_class"),
                "the operand reads the accessor: " + rule.getCheckExpr());
    }


    @Test
    void defineDatasetOperandBesideVariableMetadata_canonicalisesToo() throws IOException
    {
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-GR-C2"},
                  "Sensitivity": "Dataset",
                  "Check": {
                    "any": [
                      {"name": "variable_label", "operator": "non_empty"},
                      {"name": "define_dataset_label", "operator": "non_empty"}
                    ]
                  }
                }
                """;
        Rule rule = onlyRule(RulePackageLoader.loadFromString(packageOf(ruleJson)));
        assertNull(rule.getLoadError(), rule.getLoadError());
        assertTrue(String.valueOf(rule.getCheckExpr()).contains("ds_label"),
                rule.getCheckExpr().toString());
    }


    @Test
    void anOperandNoAccessorServes_tagsLoadError_inCheckAndPrecondition() throws IOException
    {
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-GR-C3"},
                  "Sensitivity": "Record",
                  "Precondition": {
                    "all": [
                      {"name": "library_dataset_structure_version", "operator": "equal_to",
                       "value": "EVENTS", "value_is_literal": true}
                    ]
                  },
                  "Check": {
                    "all": [
                      {"name": "define_dataset_purpose", "operator": "non_empty"},
                      {"name": "AETERM", "operator": "empty"}
                    ]
                  }
                }
                """;
        Rule rule = onlyRule(RulePackageLoader.loadFromString(packageOf(ruleJson)));
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().contains("define_dataset_purpose"), rule.getLoadError());
        assertTrue(rule.getLoadError().contains("Check.all[0]"), rule.getLoadError());
        assertTrue(rule.getLoadError().contains("Precondition"), rule.getLoadError());
        assertTrue(rule.getLoadError().contains("no ds_* accessor serves"), rule.getLoadError());
    }


    @Test
    void datasetMetadataCheckUsage_staysValid() throws IOException
    {
        // The supported home of these operands: DATASET_METADATA_CHECK, where RuleRunner phase
        // 2a2 injects the provider-backed values (e.g. CORE-001081-style three-level compares).
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-GR-C4"},
                  "Sensitivity": "Dataset",
                  "Check": {
                    "all": [
                      {"name": "define_dataset_label", "operator": "not_equal_to",
                       "value": "library_dataset_label"}
                    ]
                  }
                }
                """;
        Rule rule = onlyRule(RulePackageLoader.loadFromString(packageOf(ruleJson)));
        assertNull(rule.getLoadError(), "DMC rules keep the operands: " + rule.getLoadError());
    }


    @Test
    void textualValueSideOnOtherTypes_staysValid() throws IOException
    {
        // VALUE-side textual references follow the universal var-or-literal resolution contract
        // (here: the literal fallback) — only the NAME side resolved from the injected variables
        // is tagged.
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-GR-C5"},
                  "Sensitivity": "Record",
                  "Check": {
                    "all": [
                      {"name": "AETERM", "operator": "equal_to",
                       "value": "library_dataset_class"}
                    ]
                  }
                }
                """;
        Rule rule = onlyRule(RulePackageLoader.loadFromString(packageOf(ruleJson)));
        assertNull(rule.getLoadError(),
                "value-side textual use is the literal-fallback contract, not the folded operand: "
                        + rule.getLoadError());
    }

}
