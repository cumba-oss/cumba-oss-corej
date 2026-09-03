package net.cumba.corej.ruletest.cdt.ruletest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleRunner;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.impl.support.OverlayDataTable;
import org.junit.jupiter.api.Test;

/**
 * {@code Fix #354}, plan A §4.6 hazard 1 — documented in {@code README-CDT.md} ("Asserting where a
 * rule fires") and, since this class, tested: a {@code COL=value} pin on a column the rule
 * <b>excludes</b> from its projection ({@code Output_Variables: ["!COL"]}) still passes, because
 * {@link ViolationLocationCheck} falls back to the primary table's {@code COL} cell once the
 * projection has no such key. So a green {@code .cdt} scenario is not evidence an exclusion took
 * effect — the absence must be asserted on the projection directly, which the second half of each
 * test does. Both arms are pinned: the excluded column is absent from the projection AND the pin
 * passes through the fallback; the retained sibling is present AND its pin passes through the
 * projection.
 */
class ExcludedOutputVariablePinFallbackTest
{

    private static final String SCENARIO = "net/cumba/corej/ruletest/exclusion_fixtures/excluded-primary-column-pin.cdt";

    private static Rule rule(String outputVariables) throws Exception
    {
        String json = """
                {"rules":{"TEST-OV-EXCL":{
                  "Core":{"Id":"TEST-OV-EXCL"},"Sensitivity":"Record",
                  "Scope":{"Domains":{"Include":["AE"]}},
                  "Check":{"expression":"AESEV == \\"SEVERE\\" and not empty(AETERM) and not empty(AEDECOD)"},
                  "Outcome":{"Message":"m","Output_Variables":[%s]}}}}"""
                .formatted(outputVariables);
        Rule rule = RulePackageLoader.loadFromString(json).getRules().get("TEST-OV-EXCL");
        assertNotNull(rule);
        assertNull(rule.getLoadError(), rule.getLoadError());
        return rule;
    }


    private static OverlayDataTable primary(RuleTestScenario aScenario)
    {
        OverlayDataTable t = aScenario.primaryTable();
        assertNotNull(t, "scenario has a primary table");
        return t;
    }


    @Test
    void pinOnAnExcludedPrimaryColumnPassesThroughTheTableFallback() throws Exception
    {
        RuleTestScenario scenario = RuleTestCdt.loadResource(SCENARIO);
        Rule rule = rule("\"!AEDECOD\"");
        assertEquals(List.of("AESEV", "AETERM"), rule.getEffectiveOutputVariables());
        OverlayDataTable ae = primary(scenario);

        RuleExecutionResult result = RuleRunner.execute(rule, ae, _ -> null, "AE", null, null,
                null);

        // Arm 1 — the projection really lacks the excluded column and keeps its siblings.
        assertEquals(1, result.getViolationCount(), "status=" + result.getStatus());
        Map<String, String> values = result.getViolations().get(0).getValues();
        assertFalse(values.containsKey("AEDECOD"), values.toString());
        assertEquals("Rash", values.get("AETERM"));
        assertEquals("SEVERE", values.get("AESEV"));
        // Arm 2 — and the AEDECOD=RASH pin STILL passes: the documented table fallback read the
        // primary table's cell. This is the hazard, now pinned rather than merely described.
        ViolationLocationCheck.Result check = ViolationLocationCheck.verify(scenario, result, ae);
        assertTrue(check.pass(), check.detail());
    }


    @Test
    void theSamePinPassesThroughTheProjectionWhenNothingIsExcluded() throws Exception
    {
        // The control: without the exclusion the pin is served by the projection itself.
        RuleTestScenario scenario = RuleTestCdt.loadResource(SCENARIO);
        Rule rule = rule("");
        assertEquals(List.of("AESEV", "AETERM", "AEDECOD"), rule.getEffectiveOutputVariables());
        OverlayDataTable ae = primary(scenario);

        RuleExecutionResult result = RuleRunner.execute(rule, ae, _ -> null, "AE", null, null,
                null);

        Map<String, String> values = result.getViolations().get(0).getValues();
        assertEquals("RASH", values.get("AEDECOD"));
        ViolationLocationCheck.Result check = ViolationLocationCheck.verify(scenario, result, ae);
        assertTrue(check.pass(), check.detail());
    }


    private static Rule opRule(String outputVariables) throws Exception
    {
        String json = """
                {"rules":{"TEST-OV-EXCL":{
                  "Core":{"Id":"TEST-OV-EXCL"},"Sensitivity":"Record",
                  "Scope":{"Domains":{"Include":["AE"]}},
                  "Operations":[{"id":"$n","operator":"record_count"}],
                  "Check":{"expression":"AESEV == \\"SEVERE\\" and $n > 1"},
                  "Outcome":{"Message":"m","Output_Variables":[%s]}}}}"""
                .formatted(outputVariables);
        Rule rule = RulePackageLoader.loadFromString(json).getRules().get("TEST-OV-EXCL");
        assertNotNull(rule);
        assertNull(rule.getLoadError(), rule.getLoadError());
        return rule;
    }


    @Test
    void aPinOnAnExcludedNameWithNoTableColumnFailsCleanly() throws Exception
    {
        // An excluded name with no physical column (here a `$`-operation result) has nothing to
        // fall back to — the pin fails loudly. Scenario text built inline so the fixture above
        // stays the documented fallback case.
        RuleTestScenario scenario = RuleTestCdt.parse("""
                #!RuleTest
                #test TEST-OV-EXCL expect=violation domain=AE
                #expectViolationAt row=3 "$n"=3
                dataset AE
                col USUBJID type=Char
                col AESEQ   type=Num
                col AETERM  type=Char
                col AEDECOD type=Char
                col AESEV   type=Char
                ---
                001 | 1 | Headache | HEADACHE | MILD
                001 | 2 | Cough    | COUGH    | MODERATE
                002 | 1 | Rash     | RASH     | SEVERE
                """, "inline");
        Rule served = opRule("\"AESEV\"");
        Rule excluded = opRule("\"AESEV\", \"!$n\"");
        assertEquals(List.of("AESEV", "$n"), served.getEffectiveOutputVariables());
        assertEquals(List.of("AESEV"), excluded.getEffectiveOutputVariables());
        OverlayDataTable ae = primary(scenario);

        RuleExecutionResult servedResult = RuleRunner.execute(served, ae, _ -> null, "AE", null,
                null, null);
        RuleExecutionResult excludedResult = RuleRunner.execute(excluded, ae, _ -> null, "AE", null,
                null, null);

        assertEquals("3", servedResult.getViolations().get(0).getValues().get("$n"));
        ViolationLocationCheck.Result servedCheck = ViolationLocationCheck.verify(scenario,
                servedResult, ae);
        assertTrue(servedCheck.pass(), servedCheck.detail());
        assertFalse(excludedResult.getViolations().get(0).getValues().containsKey("$n"));
        ViolationLocationCheck.Result check = ViolationLocationCheck.verify(scenario,
                excludedResult, ae);
        assertFalse(check.pass(), "no table column to fall back to: " + check.detail());
    }
}
