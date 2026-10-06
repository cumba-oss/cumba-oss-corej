package net.cumba.corej.ruletest.cdt.ruletest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.report.ReportSections;
import net.cumba.corej.ruletest.cdt.ruletest.RunEnvironment.StoreAvailability;
import net.cumba.corej.ruletest.cdt.ruletest.ScenarioRunner.ScenarioRunResult;
import org.junit.jupiter.api.Test;

/** {@link ScenarioReports}: a run's results become the sections every report writer takes. */
class ScenarioReportsTest
{

    private static final String PACKAGE = """
            {"rules":{"CDISC-XX0001":{"Core":{"Id":"CDISC-XX0001","Status":"Published",
            "Version":"1"},"Executability":"Fully Executable",
            "Scope":{"Domains":{"Include":["AE"]}},
            "Check":{"expression":"AESEV == \\"MILD\\" and AESER == \\"Y\\""},
            "Outcome":{"Message":"a mild serious event"}}}}
            """;

    private static final String SCENARIO = """
            #!RuleTest
            #test CDISC-XX0001 expect=violation domain=AE
            #library standard=sdtmig version=3-4
            dataset AE label="Adverse Events"
            col STUDYID type=Char
            col USUBJID type=Char
            col AESEV type=Char
            col AESER type=Char
            ---
            S1 | 01 | MILD | Y
            S1 | 02 | SEVERE | N
            ---
            """;

    @Test
    void aRunBecomesReportSections() throws IOException
    {
        Rule rule = RulePackageLoader.loadFromString(PACKAGE).getRules().get("CDISC-XX0001");
        assertNotNull(rule);
        RuleTestScenario scenario = RuleTestCdt.parse(SCENARIO, "inline.cdt", null);
        ScenarioRunResult run = ScenarioRunner.run(rule, scenario, null, new RunEnvironment(
                StoreAvailability.unavailable("none", "no store", false), null, null), null);
        assertEquals(ScenarioRunner.Outcome.PASS, run.outcome(), run.detail());
        ReportSections sections = ScenarioReports.sections(rule, scenario, run.results(), "test");
        Map<String, Object> document = sections.toExportDocument();
        assertFalse(document.isEmpty());
        String rendered = document.toString();
        assertTrue(rendered.contains("CDISC-XX0001"), rendered);
        assertTrue(rendered.contains("a mild serious event") || rendered.contains("AESEV"),
                rendered);
        assertEquals(1, run.results().size());
    }
}
