package net.cumba.cdisc.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.datatable.report.FindingKind;
import net.cumba.datatable.report.RowFindingSlab;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.report.SkippedRuleEntry;
import net.cumba.datatable.report.ValidationFinding;
import net.cumba.datatable.report.ValidationReport;
import net.cumba.datatable.report.ValidationReportMember;
import org.junit.jupiter.api.Test;

class ReportSectionsTest
{

    @Test
    void sectionsFromWriterCarryAllFiveSheets()
    {
        ReportSections s = sampleWriter().sections();
        assertEquals("SDTMIG", s.conformanceDetails().get("Standard"));
        assertEquals(1, s.datasetDetails().size());
        assertEquals(1, s.issueSummary().size());
        assertEquals(1, s.issueDetails().size());
        // Issue_Details rows are projected to maps with the JSON field names/order.
        Map<String, Object> detail = s.issueDetails().get(0);
        assertEquals("CORE-001", detail.get("core_id"));
        assertEquals(List.of("AESTDY"), detail.get("variables"));
        assertEquals(List.of("5"), detail.get("values"));
        // No rules were supplied, but the CORE-001 finding is an orphan → it still gets a
        // Rules_Report row (EXECUTION status derived from the finding). Python parity.
        assertEquals(1, s.rulesReport().size());
        assertEquals("CORE-001", s.rulesReport().get(0).get("core_id"));
        // No skipped entries on the sample report → empty Skipped_Rules section.
        assertTrue(s.skippedRules().isEmpty());
    }


    @Test
    void sectionsCarrySkippedRules()
    {
        ValidationReport report = ValidationReport.builder().members(List.of())
                .skippedRules(List.of(SkippedRuleEntry.builder().coreId("CORE-000351").dataset("EX")
                        .reason("domain EX not in Scope.Domains.Include [AE]").build()))
                .build();
        ReportSections s = new ReportAssembler().report(report).sections();
        assertEquals(1, s.skippedRules().size());
        assertEquals("CORE-000351", s.skippedRules().get(0).get("core_id"));
        assertEquals("EX", s.skippedRules().get(0).get("dataset"));
        assertEquals("domain EX not in Scope.Domains.Include [AE]",
                s.skippedRules().get(0).get("reason"));
    }


    @Test
    void fromExportDocumentRecoversSkippedRules()
    {
        ReportSections s = ReportSections.fromExportDocument(Map.of("Skipped_Rules",
                List.of(Map.of("core_id", "CORE-1", "dataset", "EX", "reason", "r"))));
        assertEquals(1, s.skippedRules().size());
        assertEquals("CORE-1", s.skippedRules().get(0).get("core_id"));
    }


    @Test
    void fromExportDocumentRoundTripsThroughToExport()
    {
        // toExport() is the document the REST layer persists/recovers; fromExportDocument must
        // reconstruct the same section shapes (Issue_Details serialised to maps en route).
        ReportAssembler writer = sampleWriter();
        Map<String, Object> document = writer.sections().toExportDocument();
        // Simulate the REST round-trip: IssueDetail records become maps once serialised. Replace
        // the typed list with the writer's own map projection (what JSON-on-disk would yield).
        document.put("Issue_Details", writer.sections().issueDetails());

        ReportSections s = ReportSections.fromExportDocument(document);
        assertEquals("SDTMIG", s.conformanceDetails().get("Standard"));
        assertEquals(1, s.datasetDetails().size());
        assertEquals("ae.xpt", s.datasetDetails().get(0).get("filename"));
        assertEquals(1, s.issueDetails().size());
        assertEquals("CORE-001", s.issueDetails().get(0).get("core_id"));
    }


    @Test
    void fromExportDocumentToleratesMissingOrWrongTypedSections()
    {
        ReportSections s = ReportSections.fromExportDocument(
                Map.of("Conformance_Details", "not-a-map", "Issue_Summary", "not-a-list"));
        assertTrue(s.conformanceDetails().isEmpty());
        assertTrue(s.datasetDetails().isEmpty());
        assertTrue(s.issueSummary().isEmpty());
        assertTrue(s.issueDetails().isEmpty());
        assertTrue(s.rulesReport().isEmpty());
        assertTrue(s.skippedRules().isEmpty());
    }


    @Test
    void fromExportDocumentHandlesNull()
    {
        ReportSections s = ReportSections.fromExportDocument(null);
        assertTrue(s.conformanceDetails().isEmpty());
        assertTrue(s.rulesReport().isEmpty());
    }


    private static ReportAssembler sampleWriter()
    {
        ValidationFinding finding = ValidationFinding.builder().source("cumba.core")
                .ruleId("CORE-001").severity(Severity.ERROR).kind(FindingKind.RULE_VIOLATION)
                .message("AE issue").variableNames(List.of("AESTDY"))
                .rows(RowFindingSlab.builder(1).addRow(0, new String[]
                {
                        "5"
                }).build()).build();
        ValidationReport report = ValidationReport.builder().members(List.of(ValidationReportMember
                .builder().domain("AE").fileName("ae.xpt").findings(List.of(finding)).build()))
                .build();
        return new ReportAssembler().report(report)
                .conformance(ReportAssembler.Conformance.builder().standard("sdtmig").version("3-4")
                        .totalRuntimeSeconds(1.0).coreEngineVersion("0.5.0.0").build())
                .datasets(List.of(new ReportAssembler.DatasetInfo("ae.xpt", "Adverse Events",
                        "/study/sdtm", "2026-04-01T00:00:00", 1.0, 10L, "AE", 30)));
    }
}
