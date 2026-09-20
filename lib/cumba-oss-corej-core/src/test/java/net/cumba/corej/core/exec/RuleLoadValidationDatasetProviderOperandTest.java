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
                      {"expression": "ds_class(\\"LIBRARY\\") == \\"EVENTS\\""}
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
                      {"expression": "not empty(var_label(\\"DATA\\"))"},
                      {"expression": "not empty(ds_label(\\"DEFINE\\"))"}
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
                      {"expression": "library_dataset_structure_version == \\"EVENTS\\""}
                    ]
                  },
                  "Check": {
                    "all": [
                      {"expression": "not empty(define_dataset_purpose)"},
                      {"expression": "empty(AETERM)"}
                    ]
                  }
                }
                """;
        // Phase 7d (D121): an operand no accessor serves is not even CLASSIFIABLE in the
        // expression grammar — the parse itself rejects the unknown lowercase operand, one stage
        // earlier and louder than the retired leaf walker's per-rule loadError.
        Exception ex = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> RulePackageLoader.loadFromString(packageOf(ruleJson)));
        assertTrue(ex.getMessage().contains("Unknown built-in reference"), ex.getMessage());
    }


    @Test
    void datasetMetadataCheckUsage_staysValid() throws IOException
    {
        // The supported home of these operands: DATASET_METADATA_CHECK, where RuleRunner phase
        // 2a2 injects the provider-backed values (e.g. CDISC-CG0010-style three-level compares).
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-GR-C4"},
                  "Sensitivity": "Dataset",
                  "Check": {
                    "all": [
                      {"expression": "ds_label(\\"DEFINE\\") != ds_label(\\"LIBRARY\\")"}
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
                      {"expression": "AETERM == ds_class(\\"LIBRARY\\")"}
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
