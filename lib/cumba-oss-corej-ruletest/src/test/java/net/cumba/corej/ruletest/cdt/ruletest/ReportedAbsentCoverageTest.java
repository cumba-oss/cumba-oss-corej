package net.cumba.corej.ruletest.cdt.ruletest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import org.junit.jupiter.api.Test;

/**
 * {@link ReportedAbsentCoverage} — the {@code #reported-absent} coverage check the corpus factories
 * and the rule editor share — and {@link ScenarioRuleSelector#packageKey}, the package selection
 * both make.
 */
class ReportedAbsentCoverageTest
{

    /** A bare presence rule (TS), a scoped one (TA: not a study-wide guarantee) and a reader. */
    private static final String PACKAGE = """
            {"rules":{
            "X-0001":{"Core":{"Id":"X-0001"},"Scope":{"Domains":{"Include":["ALL"]}},
            "Check":{"expression":"not ds_exists(\\"TS\\")"},"Outcome":{"Message":"no TS"}},
            "X-0002":{"Core":{"Id":"X-0002"},"Scope":{"Domains":{"Include":["DM"]}},
            "Check":{"expression":"not ds_exists(\\"TA\\")"},"Outcome":{"Message":"no TA"}},
            "X-0003":{"Core":{"Id":"X-0003"},"Scope":{"Domains":{"Include":["EX"]}},
            "Check":{"expression":"empty(EXSTDTC)"},"Outcome":{"Message":"m"}}}}
            """;

    private static List<Rule> rules() throws IOException
    {
        return List.copyOf(RulePackageLoader.loadFromString(PACKAGE).getRules().values());
    }


    private static RuleTestScenario scenario(String aPrelude)
    {
        return RuleTestCdt.parse(
                "#!RuleTest\n#test X-0003 expect=skipped domain=EX\n" + aPrelude
                        + "dataset EX\ncol EXSTDTC type=Char\n---\n2020-01-01\n---\n",
                "inline.cdt", null);
    }


    @Test
    void onlyAnUnscopedBarePresenceRuleReportsADataset() throws IOException
    {
        assertEquals(Set.of("TS"), ReportedAbsentCoverage.reportedDatasets(rules()));
    }


    @Test
    void aCoveredNameIsAcceptedAndAnUncoveredOneRefusedByName() throws IOException
    {
        Set<String> reported = ReportedAbsentCoverage.reportedDatasets(rules());
        String label = ReportedAbsentCoverage.packageLabel("fda", "sendig-3-1-1");
        assertNull(
                ReportedAbsentCoverage.refusal(scenario("#reported-absent TS\n"), reported, label));
        assertNull(ReportedAbsentCoverage.refusal(scenario(""), Set.of(), label),
                "no directive, nothing to check");
        String refusal = ReportedAbsentCoverage
                .refusal(scenario("#reported-absent TS TA POOLDEF\n"), reported, label);
        assertNotNull(refusal);
        assertTrue(refusal.startsWith("Scenario X-0003 (inline.cdt) declares #reported-absent"
                + " [TA, POOLDEF], but no rule of the fda family's 'sendig-3-1-1' package reports"
                + " that absence"), refusal);
        assertTrue(refusal.contains("the package reports [TS]."), refusal);
    }


    @Test
    void thePackageKeyIsTheDeclaredOneElseTheCanonicalFallback()
    {
        List<String> holding = List.of("sdtmig-3-3", "sendig-3-1-1", "sdtmig-3-4");
        assertEquals(
                "sendig-3-0", ScenarioRuleSelector
                        .packageKey(scenario("#library standard=sendig version=3-0\n"), holding),
                "declared wins, member or not");
        assertEquals("sdtmig-3-4", ScenarioRuleSelector.packageKey(scenario(""), holding),
                "the first preferred package holding the rule");
        assertEquals("sdtmig-3-3",
                ScenarioRuleSelector.packageKey(scenario(""),
                        List.of("sdtmig-3-1-2", "sdtmig-3-3")),
                "else the last sorted holding package");
        assertNull(ScenarioRuleSelector.packageKey(scenario(""), List.of()));
    }
}
