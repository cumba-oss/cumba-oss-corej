package net.cumba.corej.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import net.cumba.datatable.report.FindingKind;
import net.cumba.datatable.report.RowFindingSlab;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.report.ValidationFinding;
import net.cumba.datatable.report.ValidationFindingLocation;
import net.cumba.datatable.report.ValidationReport;
import net.cumba.datatable.report.ValidationReportMember;
import org.junit.jupiter.api.Test;

class ReportDocumentV2Test
{

    private static final String CORE_252 = "CORE-000252";

    private static final String CORE_ERR = "CORE-000999";

    private static final String CORE_LIB = "CORE-000500";

    @Test
    void multiRowFinding_emitsOneCombinedEntryWithRowsAndLocation()
    {
        Map<String, Object> export = new ReportAssembler().report(sampleReport())
                .conformance(ReportAssembler.Conformance.builder().standard("sdtmig").version("3-4")
                        .reportGeneration("2026-04-01T00:00:00").build())
                .sections().toCombinedExportDocument();

        List<Map<String, Object>> findings = combinedFindings(export);
        Map<String, Object> ae = findings.stream().filter(f -> CORE_252.equals(f.get("core_id")))
                .findFirst().orElseThrow();

        assertEquals(CORE_252, ae.get("core_id"));
        assertEquals("AETERM must not be null", ae.get("message"));
        assertEquals("fully executable", ae.get("executability"));
        assertEquals("ae.xpt", ae.get("dataset"));
        assertEquals("AE", ae.get("domain"));
        assertEquals(List.of("AETERM"), ae.get("variables"));

        @SuppressWarnings("unchecked")
        Map<String, Object> location = (Map<String, Object>) ae.get("location");
        assertEquals("AE", location.get("dataset"));
        assertEquals(List.of("AETERM"), location.get("variables"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ae.get("rows");
        assertEquals(2, rows.size(), "one combined finding holds its multiple rows");
        assertEquals(3, rows.get(0).get("row"));
        assertEquals("SUBJ-001", rows.get(0).get("USUBJID"));
        assertEquals("2", rows.get(0).get("SEQ"));
        // values align positionally with variables (["AETERM"]).
        assertEquals(List.of("HEADACHE"), rows.get(0).get("values"));
        assertEquals(17, rows.get(1).get("row"));
        assertEquals("SUBJ-004", rows.get(1).get("USUBJID"));
        assertEquals(List.of("NAUSEA"), rows.get(1).get("values"));
    }


    @Test
    void datasetScopedEngineError_emittedAsVirtualFindingWithEmptyRows()
    {
        ValidationFinding error = ValidationFinding.builder().source("cumba.core").ruleId(CORE_ERR)
                .kind(FindingKind.ENGINE_ERROR).severity(Severity.ERROR).message("Boom")
                .location(ValidationFindingLocation.builder().dataset("DM").variableNames(List.of())
                        .build())
                .build();
        ValidationReportMember member = ValidationReportMember.builder().domain("DM")
                .fileName("dm.xpt").findings(List.of(error)).build();
        ValidationReport report = ValidationReport.builder().members(List.of(member)).build();

        Map<String, Object> export = new ReportAssembler().report(report).sections()
                .toCombinedExportDocument();
        List<Map<String, Object>> findings = combinedFindings(export);

        assertEquals(1, findings.size(), "dataset-scoped engine error is a virtual finding in v2");
        Map<String, Object> f = findings.get(0);
        assertEquals(CORE_ERR, f.get("core_id"));
        assertEquals("Boom", f.get("message"));
        assertEquals("dm.xpt", f.get("dataset"));
        assertEquals("DM", f.get("domain"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) f.get("rows");
        assertTrue(rows.isEmpty(), "virtual findings carry an empty rows[] array");
        @SuppressWarnings("unchecked")
        Map<String, Object> location = (Map<String, Object>) f.get("location");
        assertEquals("DM", location.get("dataset"));
    }


    @Test
    void globalLibraryFinding_excludedFromFindings()
    {
        // Member with an empty domain (library-/global-level) — excluded from Findings.
        ValidationFinding libFinding = ValidationFinding.builder().source("cumba.core")
                .ruleId(CORE_LIB).kind(FindingKind.RULE_VIOLATION).severity(Severity.WARNING)
                .message("Library warning").build();
        ValidationReportMember member = ValidationReportMember.builder().domain("")
                .findings(List.of(libFinding)).build();
        ValidationReport report = ValidationReport.builder().members(List.of(member)).build();

        Map<String, Object> export = new ReportAssembler().report(report).sections()
                .toCombinedExportDocument();
        assertTrue(combinedFindings(export).isEmpty(),
                "global/library-level findings (empty domain) are not in Findings");
    }


    @Test
    void metadataSections_identicalToV1()
    {
        // The four metadata sections must be byte-equal to v1 (same private builders).
        ReportAssembler writer = new ReportAssembler().report(sampleReport())
                .conformance(ReportAssembler.Conformance.builder().standard("sdtmig").version("3-4")
                        .totalRuntimeSeconds(1.5).coreEngineVersion("0.5.0.0")
                        .reportGeneration("2026-04-01T00:00:00").build())
                .datasets(List.of(new ReportAssembler.DatasetInfo("ae.xpt", "Adverse Events",
                        "/study/sdtm", "2026-04-01T00:00:00", 12.345, 100L, "AE", 30)));

        Map<String, Object> v1 = writer.sections().toExportDocument();
        Map<String, Object> v2 = writer.sections().toCombinedExportDocument();

        assertEquals(v1.get("Conformance_Details"), v2.get("Conformance_Details"));
        assertEquals(v1.get("Dataset_Details"), v2.get("Dataset_Details"));
        assertEquals(v1.get("Issue_Summary"), v2.get("Issue_Summary"));
        assertEquals(v1.get("Rules_Report"), v2.get("Rules_Report"));
        // v2 replaces the flat per-row Issue_Details with the combined Findings array.
        assertFalse(v2.containsKey("Issue_Details"));
        assertTrue(v2.containsKey("Findings"));
    }


    @Test
    void reportVersionIsTwoAndFindingsSortedByCoreIdThenDataset()
    {
        // Two findings on different datasets with out-of-order core ids → sorted (core_id,
        // dataset).
        ValidationFinding f2 = finding("CORE-000300", "DM", List.of("AGE"), "120");
        ValidationFinding f1 = finding("CORE-000100", "AE", List.of("AETERM"), "HEADACHE");
        ValidationReport report = ValidationReport.builder()
                .members(List.of(
                        ValidationReportMember.builder().domain("DM").fileName("dm.xpt")
                                .findings(List.of(f2)).build(),
                        ValidationReportMember.builder().domain("AE").fileName("ae.xpt")
                                .findings(List.of(f1)).build()))
                .build();

        Map<String, Object> export = new ReportAssembler().report(report).sections()
                .toCombinedExportDocument();
        assertEquals("2.0", export.get("Report_Version"));
        List<Map<String, Object>> findings = combinedFindings(export);
        assertEquals(2, findings.size());
        assertEquals("CORE-000100", findings.get(0).get("core_id"));
        assertEquals("CORE-000300", findings.get(1).get("core_id"));
    }


    @Test
    void combinedDocumentRoundTripsThroughJackson() throws Exception
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new ObjectMapper().writeValue(baos,
                new ReportAssembler().report(sampleReport())
                        .conformance(ReportAssembler.Conformance.builder().standard("sdtmig")
                                .version("3-4").reportGeneration("2026-04-01T00:00:00").build())
                        .sections().toCombinedExportDocument());

        Map<String, Object> parsed = new ObjectMapper().readValue(baos.toByteArray(),
                new TypeReference<Map<String, Object>>()
                {
                });
        assertEquals("2.0", parsed.get("Report_Version"));
        assertNotNull(parsed.get("Conformance_Details"));
        assertNotNull(parsed.get("Findings"));
    }

    // ------------------------------------------------------------------
    // Skipped_Rules (additive Java extension)
    // ------------------------------------------------------------------


    @Test
    void v2Export_emitsSkippedRulesAfterRulesReport()
    {
        ValidationReport report = sampleReport().toBuilder()
                .skippedRules(List.of(
                        net.cumba.datatable.report.SkippedRuleEntry.builder().coreId("CORE-000351")
                                .dataset("EX").reason("Rule skipped — no Library access").build()))
                .build();

        Map<String, Object> export = new ReportAssembler().report(report).sections()
                .toCombinedExportDocument();

        List<String> keys = List.copyOf(export.keySet());
        assertEquals(List.of("Report_Version", "Conformance_Details", "Dataset_Details",
                "Issue_Summary", "Findings", "Rules_Report", "Skipped_Rules"), keys);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> skipped = (List<Map<String, Object>>) export.get("Skipped_Rules");
        assertEquals(1, skipped.size());
        assertEquals("CORE-000351", skipped.get(0).get("core_id"));
        assertEquals("EX", skipped.get(0).get("dataset"));
        assertEquals("Rule skipped — no Library access", skipped.get(0).get("reason"));
    }


    @Test
    void v2Export_skippedRulesEmptyWhenReportHasNone()
    {
        Map<String, Object> export = new ReportAssembler().report(sampleReport()).sections()
                .toCombinedExportDocument();
        assertTrue(export.containsKey("Skipped_Rules"));
        assertEquals(List.of(), export.get("Skipped_Rules"));
    }

    // ------------------------------------------------------------------
    // Test fixtures
    // ------------------------------------------------------------------


    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> combinedFindings(Map<String, Object> export)
    {
        return (List<Map<String, Object>>) export.get("Findings");
    }


    private static ValidationReport sampleReport()
    {
        List<String> aeNames = List.of("USUBJID", "SEQ", "AETERM");
        RowFindingSlab slab = RowFindingSlab.builder(aeNames.size()).addRow(2, new String[]
        {
                "SUBJ-001", "2", "HEADACHE"
        }).addRow(16, new String[]
        {
                "SUBJ-004", "1", "NAUSEA"
        }).build();
        ValidationFinding ae = ValidationFinding.builder().source("cumba.core").ruleId(CORE_252)
                .severity(Severity.ERROR).kind(FindingKind.RULE_VIOLATION)
                .executability("fully executable").message("AETERM must not be null")
                .variableNames(aeNames).location(ValidationFindingLocation.builder().dataset("AE")
                        .variableNames(List.of("AETERM")).build())
                .rows(slab).build();
        ValidationReportMember member = ValidationReportMember.builder().domain("AE")
                .fileName("ae.xpt").findings(List.of(ae)).build();
        return ValidationReport.builder().members(List.of(member)).build();
    }


    private static ValidationFinding finding(String coreId, String domain, List<String> vars,
            String value)
    {
        List<String> names = new java.util.ArrayList<>(List.of("USUBJID", "SEQ"));
        names.addAll(vars);
        String[] cells = new String[names.size()];
        cells[0] = "SUBJ-001";
        cells[1] = "1";
        for (int i = 0; i < vars.size(); i++)
        {
            cells[2 + i] = value;
        }
        RowFindingSlab slab = RowFindingSlab.builder(names.size()).addRow(0, cells).build();
        return ValidationFinding.builder().source("cumba.core").ruleId(coreId)
                .severity(Severity.ERROR).kind(FindingKind.RULE_VIOLATION).message("msg")
                .variableNames(names).location(ValidationFindingLocation.builder().dataset(domain)
                        .variableNames(vars).build())
                .rows(slab).build();
    }
}
