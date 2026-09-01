package net.cumba.cdisc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * Phase-2 wiring of {@code plans/PLAN-auto-output-variables.md} (EC-37): the loader installs the
 * effective Output_Variables after {@code retainNativeExpr}, records the delta in the
 * {@code derivationRationale}, and honours the {@code corej.autoOutputVariables} kill-switch.
 */
class RulePackageLoaderDeriveOutputVariablesTest
{

    private static final String PKG = """
            {"rules": {"TEST-OV": {
              "Core": {"Id": "TEST-OV"},
              "Sensitivity": "Record",
              "Check": {"all": [
                {"name": "AESTDTC", "operator": "non_empty"},
                {"name": "AEENDTC", "operator": "non_empty"}
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


    @Test
    void killSwitchDisablesDerivation() throws Exception
    {
        System.setProperty("corej.autoOutputVariables", "false");
        try
        {
            RulePackage pkg = RulePackageLoader.loadFromString(PKG);
            Rule rule = pkg.getRules().get("TEST-OV");
            assertNotNull(rule);
            assertNull(rule.getEffectiveOutputVariables());
            // Consumers fall back to the authored list.
            assertEquals(List.of("AESTDTC"), rule.effectiveOutputVariablesOrAuthored());
        }
        finally
        {
            System.clearProperty("corej.autoOutputVariables");
        }
    }


    @Test
    void noDeltaMeansNoRationaleEntry() throws Exception
    {
        String pkg = """
                {"rules": {"TEST-NODELTA": {
                  "Core": {"Id": "TEST-NODELTA"},
                  "Sensitivity": "Record",
                  "Check": {"all": [{"name": "AETERM", "operator": "empty"}]},
                  "Outcome": {"Message": "m", "Output_Variables": ["AETERM"]}
                }}}""";
        Rule rule = RulePackageLoader.loadFromString(pkg).getRules().get("TEST-NODELTA");
        assertNotNull(rule);
        assertEquals(List.of("AETERM"), rule.getEffectiveOutputVariables());
        assertTrue(rule.getDerivationRationale() == null
                || !rule.getDerivationRationale().containsKey("Output_Variables"));
    }
}
