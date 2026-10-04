package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * Phase-2 wiring of {@code plans/done/PLAN-auto-output-variables.md} (EC-37): the loader installs
 * the effective Output_Variables after {@code retainNativeExpr}, records the delta in the
 * {@code derivationRationale}, derives nothing for a rule that failed to load, and derives on first
 * read when a rule is projected without the loader's derivation (the kill switch is retired,
 * 2026-10-04).
 */
class RulePackageLoaderDeriveOutputVariablesTest
{

    private static final String PKG = """
            {"rules": {"TEST-OV": {
              "Core": {"Id": "TEST-OV"},
              "Sensitivity": "Record",
              "Check": {"all": [
                {"expression": "not empty(AESTDTC)"},
                {"expression": "not empty(AEENDTC)"}
              ]},
              "Outcome": {"Message": "m", "Output_Variables": ["AESTDTC"]}
            }}}""";

    @Test
    void loaderInstallsEffectiveListAndRationale() throws Exception
    {
        RulePackage pkg = RulePackageLoader.loadFromString(PKG);
        Rule rule = pkg.getRules().get("TEST-OV");
        assertNotNull(rule);
        assertNull(rule.getLoadError());
        assertEquals(List.of("AESTDTC", "AEENDTC"), rule.getEffectiveOutputVariables());
        assertEquals(List.of("AESTDTC", "AEENDTC"), rule.effectiveOutputVariablesOrAuthored());
        // The authored list is untouched — source shape is never mutated.
        assertEquals(List.of("AESTDTC"), rule.getOutcome().getOutputVariables());
        assertNotNull(rule.getDerivationRationale());
        assertEquals("derived: AEENDTC", rule.getDerivationRationale().get("Output_Variables"));
    }


    /**
     * The one path left without a derived list: a rule the loader parked with a load error. The
     * consumers' accessor then answers the authored list (exclusions applied) — the ERROR sentinel
     * still projects something, and nothing better exists for a rule that never compiled.
     */
    @Test
    void aLoadErrorRuleDerivesNothingAndFallsBackToTheAuthoredList() throws Exception
    {
        // AESTDTC authored AND excluded: E-3.2, a contradiction the loader parks.
        RulePackage pkg = RulePackageLoader
                .loadFromString(PKG.replace("[\"AESTDTC\"]", "[\"AESTDTC\", \"!AESTDTC\"]"));
        Rule rule = pkg.getRules().get("TEST-OV");
        assertNotNull(rule);
        assertNotNull(rule.getLoadError(), "the contradiction must park the rule");
        assertNull(rule.getEffectiveOutputVariables(), "a parked rule derives nothing");
        assertEquals(List.of(), rule.effectiveOutputVariablesOrAuthored(),
                "the fallback is the authored list with the exclusion applied");
    }


    /**
     * Q6 / M1 of {@code PLAN-redundant-output-variables-sweep}: with the kill switch retired, a
     * rule whose derivation never ran has no honest authored fallback — the authored list is lossy
     * since the sweep — so the accessor derives on first read, exactly the loader's list, and
     * memoises it.
     */
    @Test
    void aRuleProjectedWithoutTheDerivationDerivesOnFirstRead() throws Exception
    {
        RulePackage pkg = RulePackageLoader.loadFromString(PKG);
        Rule rule = pkg.getRules().get("TEST-OV");
        assertNotNull(rule);
        List<String> loaderList = rule.getEffectiveOutputVariables();
        rule.setEffectiveOutputVariables(null); // the state a caller bypassing the loader leaves
        rule.setExcludedOutputVariables(null);
        assertEquals(loaderList, rule.effectiveOutputVariablesOrAuthored(),
                "the first read derives exactly what the loader installed");
        assertEquals(List.of("AESTDTC", "AEENDTC"), rule.getEffectiveOutputVariables(),
                "and memoises it on the rule");
        assertNotNull(rule.getExcludedOutputVariables(), "the exclusion set rides along");
    }


    @Test
    void noDeltaMeansNoRationaleEntry() throws Exception
    {
        String pkg = """
                {"rules": {"TEST-NODELTA": {
                  "Core": {"Id": "TEST-NODELTA"},
                  "Sensitivity": "Record",
                  "Check": {"all": [{"expression": "empty(AETERM)"}]},
                  "Outcome": {"Message": "m", "Output_Variables": ["AETERM"]}
                }}}""";
        Rule rule = RulePackageLoader.loadFromString(pkg).getRules().get("TEST-NODELTA");
        assertNotNull(rule);
        assertEquals(List.of("AETERM"), rule.getEffectiveOutputVariables());
        assertTrue(rule.getDerivationRationale() == null
                || !rule.getDerivationRationale().containsKey("Output_Variables"));
    }
}
