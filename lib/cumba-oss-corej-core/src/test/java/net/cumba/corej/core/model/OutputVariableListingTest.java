package net.cumba.corej.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import org.junit.jupiter.api.Test;

/**
 * {@link OutputVariableListing} — the one definition of effective / authored / excluded
 * ({@code PLAN-rule-catalogue-effective-output-variables} C2, created by
 * {@code PLAN-rule-authoring-api} phase 2 per the hand-over).
 */
class OutputVariableListingTest
{

    private static final String PACKAGE_HEAD = "{\"rules\":{\"CDISC-XX0001\":{\"Core\":{\"Id\":"
            + "\"CDISC-XX0001\",\"Status\":\"Draft\",\"Version\":\"1\"},"
            + "\"Executability\":\"Fully Executable\",\"Scope\":{\"Domains\":{\"Include\":"
            + "[\"AE\"]}},\"Check\":{\"expression\":\"AESEV == \\\"MILD\\\" and AESER == \\\"Y\\\"\"},"
            + "\"Outcome\":{\"Message\":\"m\"";

    private static Rule load(String aOutcomeTail) throws IOException
    {
        Map<String, Rule> rules = RulePackageLoader
                .loadFromString(PACKAGE_HEAD + aOutcomeTail + "}}}}").getRules();
        return rules.get("CDISC-XX0001");
    }


    @Test
    void aRuleAuthoringNothingDerivesItsEffectiveList() throws IOException
    {
        Rule rule = load("");
        OutputVariableListing l = OutputVariableListing.of(rule);
        assertEquals(List.of(), l.authored());
        assertEquals(List.of(), l.excluded());
        assertEquals(rule.effectiveOutputVariablesOrAuthored(), l.effective());
        assertTrue(l.derived());
        assertFalse(l.effective().isEmpty(), "the deriver names the compared columns");
        assertFalse(l.isAuthored(l.effective().getFirst()));
    }


    @Test
    void anAuthoredNameIsAuthoredAndEffective() throws IOException
    {
        Rule rule = load(",\"Output_Variables\":[\"AESEV\"]");
        OutputVariableListing l = OutputVariableListing.of(rule);
        assertEquals(List.of("AESEV"), l.authored());
        assertTrue(l.isAuthored("AESEV"));
        assertFalse(l.isAuthored("aesev"), "exact string, P3");
        assertTrue(l.effective().contains("AESEV"));
    }


    @Test
    void anExclusionIsAbsentFromEffectiveAndPresentInExcluded() throws IOException
    {
        Rule rule = load(",\"Output_Variables\":[\"!AESER\"]");
        OutputVariableListing l = OutputVariableListing.of(rule);
        assertEquals(List.of("AESER"), l.excluded());
        assertEquals(List.of(), l.authored());
        assertFalse(l.effective().contains("AESER"));
        assertTrue(l.derived());
    }


    @Test
    void aRuleThatFailedToLoadIsNotDerived() throws IOException
    {
        // An unknown function is a per-rule load error; no derivation ran.
        Map<String, Rule> rules = RulePackageLoader.loadFromString(
                PACKAGE_HEAD.replace("AESEV == \\\"MILD\\\"", "no_such_function(AESEV)")
                        + ",\"Output_Variables\":[\"AESEV\",\"!AESER\"]}}}}")
                .getRules();
        Rule rule = rules.get("CDISC-XX0001");
        assertTrue(rule.getLoadError() != null, "premise: the rule failed to load");
        OutputVariableListing l = OutputVariableListing.of(rule);
        assertFalse(l.derived());
        assertEquals(List.of("AESEV"), l.authored());
        assertEquals(List.of("AESER"), l.excluded());
        assertEquals(List.of("AESEV"), l.effective(), "authored with !X applied, no derivation");
    }


    @Test
    void anAuthoredNameThatIsAlsoExcludedShowsOnlyAsExcluded() throws IOException
    {
        // P3: X and !X together is a load error (the loader refuses "both authored and
        // excluded"), so no derivation ran; the effective list applies the exclusion, and a
        // surface that marks the effective list and strikes the excluded one shows X only as !X.
        Rule rule = load(",\"Output_Variables\":[\"AESEV\",\"!AESEV\"]");
        assertTrue(rule.getLoadError() != null, "premise: X with !X is refused at load");
        OutputVariableListing l = OutputVariableListing.of(rule);
        assertFalse(l.derived());
        assertEquals(List.of("AESEV"), l.excluded());
        assertEquals(List.of("AESEV"), l.authored(),
                "the author wrote the include: authored is not a subset of effective here");
        assertFalse(l.effective().contains("AESEV"), "excluded wins over the authored include");
    }


    @Test
    void aHandBuiltRuleDerivesOnFirstRead()
    {
        Rule rule = new Rule();
        Outcome outcome = new Outcome();
        outcome.setOutputVariables(List.of("USUBJID", "!AESER"));
        rule.setOutcome(outcome);
        OutputVariableListing l = OutputVariableListing.of(rule);
        assertTrue(l.derived(), "no load error: the derivation runs on first read");
        assertEquals(List.of("USUBJID"), l.effective());
        assertEquals(List.of("USUBJID"), l.authored());
        assertEquals(List.of("AESER"), l.excluded());
        assertEquals(l, OutputVariableListing.of(rule), "a second read is equal");
        assertSame(l.effective(), OutputVariableListing.of(rule).effective(),
                "memoised: a second read returns the stored list itself, not a re-derivation");
    }


    @Test
    void thePerVariableHoistLeadsButIsNotAuthored() throws IOException
    {
        // D7 / R-9.7: on the per-variable path variable_name is hoisted to index 0 by the
        // deriver; it is derived, so it is not marked as authored.
        Map<String, Rule> rules = RulePackageLoader
                .loadFromString(PACKAGE_HEAD.replace("AESEV == \\\"MILD\\\" and AESER == \\\"Y\\\"",
                        "varname() == \\\"AETERM\\\"") + ",\"Output_Variables\":[\"AETERM\"]}}}}")
                .getRules();
        Rule rule = rules.get("CDISC-XX0001");
        assertEquals(null, rule.getLoadError());
        OutputVariableListing l = OutputVariableListing.of(rule);
        assertEquals("variable_name", l.effective().getFirst(), "hoisted lead");
        assertFalse(l.isAuthored("variable_name"));
        assertTrue(l.isAuthored("AETERM"));
        assertTrue(l.effective().contains("AETERM"));
    }


    @Test
    void theListsAreImmutableCopies()
    {
        java.util.ArrayList<String> src = new java.util.ArrayList<>(List.of("A"));
        OutputVariableListing l = new OutputVariableListing(src, src, List.of(), true);
        src.add("B");
        assertEquals(List.of("A"), l.effective());
    }
}
