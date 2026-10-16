package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import net.cumba.corej.core.exec.OutputVariableDeriver;
import net.cumba.corej.core.model.Rule;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-bugfix-engine-define} OV (BD1 (a), owner 2026-10-15; T13): an <b>authored</b>
 * {@code Output_Variables} entry carrying a {@code ${…}} driver ({@code ADSL.PH${APHASEN:%d}SDT},
 * or a {@code ${*}} wildcard with a driver) is a load error naming the entry — the driver is
 * resolved per row and cannot name a report column; until 2026-10-16 the runner kept it verbatim
 * and the projection dropped it silently (F2). A Check or a binding may still read a driver
 * template (a language restriction is out of scope, T12); the deriver simply never derives one.
 */
class OutputVariablesDriverTemplateLoadTest
{

    private static final String JOIN = """
            "Match_Datasets":[{"Name":"ADSL","Keys":["USUBJID"],"Join_Type":"left"}],
            "Requirements":{"Variables":{"All":["USUBJID"],
              "All_Or_None":[["USUBJID","ADSL.USUBJID"]]}},""";

    private static Rule load(String check, String bindings, String outputVariables)
        throws IOException
    {
        String json = "{\"rules\":{\"R1\":{\"Core\":{\"Id\":\"R1\"},\"Sensitivity\":\"Record\","
                + JOIN + (bindings.isEmpty() ? "" : "\"Bindings\":" + bindings + ",")
                + "\"Check\":{\"expression\":\"" + check.replace("\"", "\\\"") + "\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":" + outputVariables + "}}}}";
        Rule rule = RulePackageLoader.loadFromString(json).getRules().get("R1");
        assertNotNull(rule);
        return rule;
    }


    @Test
    void anAuthoredScalarDriverEntryIsALoadErrorNamingIt() throws IOException
    {
        Rule rule = load("not empty(APHASEN)", "", "[\"APHASEN\",\"ADSL.PH${APHASEN:%d}SDT\"]");
        String error = String.valueOf(rule.getLoadError());
        assertTrue(error.contains("Outcome.Output_Variables"), error);
        assertTrue(error.contains("ADSL.PH${APHASEN:%d}SDT"), error);
        assertTrue(error.contains("driver"), error);
    }


    @Test
    void anAuthoredWildcardEntryWithADriverIsALoadError() throws IOException
    {
        Rule rule = load("not empty(APHASEN)", "", "[\"ADSL.P${APHASEN}${*}SDT\"]");
        String error = String.valueOf(rule.getLoadError());
        assertTrue(error.contains("ADSL.P${APHASEN}${*}SDT"), error);
        assertTrue(error.contains("driver"), error);
    }


    @Test
    void aDriverFreeWildcardEntryStillLoads() throws IOException
    {
        Rule rule = load("not empty(APHASEN)", "", "[\"ADSL.TRT0${*}A\"]");
        assertNull(rule.getLoadError(), rule.getLoadError());
        assertTrue(rule.effectiveOutputVariablesOrAuthored().contains("ADSL.TRT0${*}A"),
                rule.effectiveOutputVariablesOrAuthored().toString());
    }


    /** The control T13 guards: a Check-side driver template stays legal and derives no entry. */
    @Test
    void aCheckReadingADriverTemplateStillLoadsAndDerivesNoDriverEntry() throws IOException
    {
        Rule rule = load("not empty(APHASEN) and PHSDT != ADSL.PH${APHASEN:%d}SDT", "",
                "[\"APHASEN\",\"PHSDT\"]");
        assertNull(rule.getLoadError(), rule.getLoadError());
        List<String> effective = rule.effectiveOutputVariablesOrAuthored();
        assertFalse(effective.stream().anyMatch(OutputVariableDeriver::isDriverTemplate),
                "a driver template has never been reportable: " + effective);
        assertEquals(List.of("APHASEN", "PHSDT"), effective);
    }


    /** Confirmation look LOW-1: the binding route derives no driver entry either. */
    @Test
    void aBindingTargetingADriverTemplateDerivesNoDriverEntry() throws IOException
    {
        Rule rule = load("not empty(APHASEN) and PHSDT != $p",
                "[{\"name\":\"$p\",\"expression\":\"ADSL.PH${APHASEN:%d}SDT\"}]", "[\"PHSDT\"]");
        assertNull(rule.getLoadError(), rule.getLoadError());
        List<String> effective = rule.effectiveOutputVariablesOrAuthored();
        assertFalse(effective.stream().anyMatch(OutputVariableDeriver::isDriverTemplate),
                "the binding's target is a driver template: " + effective);
        assertTrue(effective.contains("$p"),
                "the binding id itself is derived (D4a): " + effective);
    }


    @Test
    void anExclusionOfADriverTemplateIsALoadErrorSayingItIsNeverDerived() throws IOException
    {
        Rule rule = load("not empty(APHASEN) and PHSDT != ADSL.PH${APHASEN:%d}SDT", "",
                "[\"PHSDT\",\"!ADSL.PH${APHASEN:%d}SDT\"]");
        String error = String.valueOf(rule.getLoadError());
        assertTrue(error.contains("!ADSL.PH${APHASEN:%d}SDT"), error);
        assertTrue(error.contains("never derived"), error);
    }
}
