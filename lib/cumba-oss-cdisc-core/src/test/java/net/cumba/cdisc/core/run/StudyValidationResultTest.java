package net.cumba.cdisc.core.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.report.ReportAssembler;
import net.cumba.cdisc.core.report.ReportSections;
import net.cumba.datatable.report.ValidationReport;
import org.junit.jupiter.api.Test;

class StudyValidationResultTest
{

    private static StudyValidationResult sample()
    {
        ValidationReport report = new net.cumba.cdisc.core.report.ValidationReportBuilder()
                .libraryUri("file:///study").build();
        ReportAssembler.Conformance conformance = ReportAssembler.Conformance.builder()
                .standard("custom").version("1-0").totalRuntimeSeconds(1.5)
                .coreEngineVersion("0.0.0-test").build();
        return new StudyValidationResult(report, conformance, List.of(), List.of(), 0, 1.5,
                List.of());
    }


    @Test
    void rejectsNullReportAndConformance()
    {
        ReportAssembler.Conformance c = ReportAssembler.Conformance.builder().build();
        ValidationReport r = new net.cumba.cdisc.core.report.ValidationReportBuilder().build();
        assertThrows(NullPointerException.class,
                () -> new StudyValidationResult(null, c, List.of(), List.of(), 0, 0.0, List.of()));
        assertThrows(NullPointerException.class,
                () -> new StudyValidationResult(r, null, List.of(), List.of(), 0, 0.0, List.of()));
    }


    @Test
    void sectionsAreAssembled()
    {
        ReportSections s = sample().sections();
        assertNotNull(s);
        assertTrue(s.toExportDocument().containsKey("Conformance_Details"));
    }


    @Test
    @SuppressWarnings("unchecked")
    void sections_threadRuntimeFromExecutionSummariesIntoTheDocument()
    {
        ValidationReport report = new net.cumba.cdisc.core.report.ValidationReportBuilder()
                .libraryUri("file:///study").build();
        ReportAssembler.Conformance conformance = ReportAssembler.Conformance.builder()
                .standard("custom").version("1-0").coreEngineVersion("0.0.0-test").build();
        net.cumba.cdisc.core.model.Rule rule = new net.cumba.cdisc.core.model.Rule();
        net.cumba.cdisc.core.model.RuleCore core = new net.cumba.cdisc.core.model.RuleCore();
        core.setId("CORE-1");
        rule.setCore(core);
        // CORE-1 ran on two datasets (10 ms + 20 ms ⇒ summed 30); each dataset carries its own
        // wall-clock time.
        DatasetExecutionSummary dm = new DatasetExecutionSummary("DM", "dm.xpt", 1, 1, 0, 100,
                List.of(), List.of(new DatasetExecutionSummary.RuleExecution("CORE-1", "g1",
                        "EXECUTED", 0, 10, null, null, null, null)));
        DatasetExecutionSummary ae = new DatasetExecutionSummary("AE", "ae.xpt", 1, 1, 0, 200,
                List.of(), List.of(new DatasetExecutionSummary.RuleExecution("CORE-1", "g2",
                        "EXECUTED", 0, 20, null, null, null, null)));
        List<ReportAssembler.DatasetInfo> datasets = List.of(
                new ReportAssembler.DatasetInfo("dm.xpt", "Demographics", "/p", "2026-01-01", 1.0,
                        10L, "DM", 5),
                new ReportAssembler.DatasetInfo("ae.xpt", "Adverse Events", "/p", "2026-01-01", 1.0,
                        10L, "AE", 5));
        StudyValidationResult r = new StudyValidationResult(report, conformance, datasets,
                List.of(rule), 0, 1.5, List.of(dm, ae));

        Map<String, Object> export = r.sections().toExportDocument();

        List<Map<String, Object>> rules = (List<Map<String, Object>>) export.get("Rules_Report");
        assertEquals(30L, rules.get(0).get("runtime_ms"), "per-rule runtime sums across datasets");

        List<Map<String, Object>> details = (List<Map<String, Object>>) export
                .get("Dataset_Details");
        Map<String, Long> byDomain = new java.util.HashMap<>();
        for (Map<String, Object> d : details)
        {
            byDomain.put((String) d.get("domain"), (Long) d.get("runtime_ms"));
        }
        assertEquals(100L, byDomain.get("DM"));
        assertEquals(200L, byDomain.get("AE"));
    }


    /**
     * The v1/v2 <em>serialisation</em> this record used to perform itself now belongs to the
     * report-writer modules (Fix #224); what this record still owes its callers is a complete
     * document in both shapes, which is what these two assert.
     */
    @Test
    void theV1DocumentCarriesEverySection()
    {
        Map<String, Object> v1 = sample().sections().toExportDocument();
        assertTrue(v1.containsKey("Conformance_Details"));
        assertTrue(v1.containsKey("Rules_Report"));
    }


    @Test
    void theV2DocumentCarriesFindingsAndTheVersionDiscriminator()
    {
        Map<String, Object> v2 = sample().sections().toCombinedExportDocument();
        assertEquals("2.0", v2.get("Report_Version"));
        assertTrue(v2.containsKey("Findings"));
        assertTrue(v2.containsKey("Conformance_Details"));
    }


    @Test
    void accessorsExposeValues()
    {
        StudyValidationResult r = sample();
        assertEquals(0, r.findingCount());
        assertEquals(1.5, r.totalRuntimeSeconds());
        assertTrue(r.datasets().isEmpty());
        assertTrue(r.rules().isEmpty());
    }


    @Test
    void convenienceConstructorDefaultsGeneratedRulesToEmpty()
    {
        assertTrue(sample().generatedRules().isEmpty());
    }


    @Test
    void generatedRulesCopiedAndExposed()
    {
        ValidationReport report = new net.cumba.cdisc.core.report.ValidationReportBuilder()
                .libraryUri("file:///study").build();
        ReportAssembler.Conformance conformance = ReportAssembler.Conformance.builder()
                .standard("custom").version("1-0").totalRuntimeSeconds(1.5)
                .coreEngineVersion("0.0.0-test").build();
        Rule gen = new Rule();
        gen.setId("CG0001-AGE");
        StudyValidationResult r = new StudyValidationResult(report, conformance, List.of(),
                List.of(), 0, 1.5, List.of(), Map.of("CG0001-AGE", gen));
        assertEquals(1, r.generatedRules().size());
        assertEquals("CG0001-AGE", r.generatedRules().get("CG0001-AGE").getId());
    }


    @Test
    void nullGeneratedRulesBecomesEmpty()
    {
        ValidationReport report = new net.cumba.cdisc.core.report.ValidationReportBuilder()
                .libraryUri("file:///study").build();
        ReportAssembler.Conformance conformance = ReportAssembler.Conformance.builder()
                .standard("custom").version("1-0").totalRuntimeSeconds(1.5)
                .coreEngineVersion("0.0.0-test").build();
        StudyValidationResult r = new StudyValidationResult(report, conformance, List.of(),
                List.of(), 0, 1.5, List.of(), null);
        assertTrue(r.generatedRules().isEmpty());
    }
}
