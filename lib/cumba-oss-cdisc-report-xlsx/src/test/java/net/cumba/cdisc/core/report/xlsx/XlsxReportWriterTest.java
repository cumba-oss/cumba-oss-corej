package net.cumba.cdisc.core.report.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RuleCore;
import net.cumba.cdisc.core.report.ReportAssembler;
import net.cumba.cdisc.core.report.ReportSections;
import net.cumba.datatable.report.FindingKind;
import net.cumba.datatable.report.RowFindingSlab;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.report.ValidationFinding;
import net.cumba.datatable.report.ValidationReport;
import net.cumba.datatable.report.ValidationReportMember;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class XlsxReportWriterTest
{

    private static final String CORE_001 = "CORE-001";

    private static final String CORE_002 = "CORE-002";

    // ------------------------------------------------------------------
    // Structure: sheet names/order + template header rows survive
    // ------------------------------------------------------------------

    @Test
    void sheetNamesAndOrderMatchTemplate() throws Exception
    {
        // Five template sheets plus the programmatically appended Skipped Rules sheet.
        try (XSSFWorkbook wb = render(sampleSections(), 10_000))
        {
            assertEquals(6, wb.getNumberOfSheets());
            assertEquals("Conformance Details", wb.getSheetName(0));
            assertEquals("Dataset Details", wb.getSheetName(1));
            assertEquals("Issue Summary", wb.getSheetName(2));
            assertEquals("Issue Details", wb.getSheetName(3));
            assertEquals("Rules Report", wb.getSheetName(4));
            assertEquals("Skipped Rules", wb.getSheetName(5));
        }
    }


    @Test
    void templateHeaderRowsArePreserved() throws Exception
    {
        try (XSSFWorkbook wb = render(sampleSections(), 10_000))
        {
            assertEquals("Conformance Details", string(wb.getSheet("Conformance Details"), 0, 0));
            // Dataset Details header (row 1).
            Sheet ds = wb.getSheet("Dataset Details");
            assertEquals("Dataset", string(ds, 0, 0));
            assertEquals("Label", string(ds, 0, 1));
            assertEquals("Location", string(ds, 0, 2));
            assertEquals("Number of Records", string(ds, 0, 5));
            // Issue Details header.
            Sheet det = wb.getSheet("Issue Details");
            assertEquals("CORE-ID", string(det, 0, 0));
            assertEquals("Value(s)", string(det, 0, 8));
        }
    }

    // ------------------------------------------------------------------
    // Conformance Details
    // ------------------------------------------------------------------


    @Test
    void conformanceValuesLandInColumnBAtMappedRows() throws Exception
    {
        try (XSSFWorkbook wb = render(sampleSections(), 10_000))
        {
            Sheet c = wb.getSheet("Conformance Details");
            assertEquals("1.50 seconds", string(c, 2, 1)); // row 3 Total Runtime
            assertEquals("0.5.0.0", string(c, 3, 1)); // row 4 CORE Engine Version
            assertEquals("None", string(c, 4, 1)); // row 5 Issue Limit per Rule
            assertEquals("None", string(c, 5, 1)); // row 6 Issue Limit per Dataset
            assertEquals("SDTMIG", string(c, 8, 1)); // row 9 Standard
            assertEquals("V3.4", string(c, 10, 1)); // row 11 Version
            assertEquals("sdtmct-2024-09-27", string(c, 12, 1)); // row 13 CT Version
        }
    }


    @Test
    void issueLimitPerSheetShowsEffectiveLimit() throws Exception
    {
        try (XSSFWorkbook wb = render(sampleSections(), 10_000))
        {
            // Row 7 (0-based 6), column B — the XLSX shows the effective limit (JSON emits null).
            assertEquals("10000", string(wb.getSheet("Conformance Details"), 6, 1));
        }
    }


    @Test
    void unlimitedLimitShowsNone() throws Exception
    {
        try (XSSFWorkbook wb = render(sampleSections(), null))
        {
            assertEquals("None", string(wb.getSheet("Conformance Details"), 6, 1));
        }
    }


    @Test
    void absentOptionalConformanceRowsKeepTemplateDefaults() throws Exception
    {
        // No Sub-Standard / TIG Use Case / dictionary versions supplied → template defaults
        // survive.
        try (XSSFWorkbook wb = render(sampleSections(), 10_000))
        {
            Sheet c = wb.getSheet("Conformance Details");
            assertEquals("Sub-Standard", string(c, 9, 0)); // label preserved (col A)
            assertEquals("NAP", string(c, 9, 1)); // template default preserved (col B)
            assertEquals("not configured", string(c, 14, 1)); // UNII Version default
        }
    }


    /**
     * PLAN-dictionary-seeder Phase 6a — the run-level {@code Dictionary_Basis} line reaches the
     * XLSX (template row 21), alongside {@code Neoplasm_Version} (row 22 — seven types, seven
     * fields now) and {@code Library_Metadata_Basis} (row 23 — the {@code Fix #369} field that
     * previously reached the JSON and nothing else).
     */
    @Test
    void dictionaryBasisNeoplasmVersionAndLibraryBasisLandAtRows21To23() throws Exception
    {
        ReportSections degraded = new ReportAssembler()
                .report(ValidationReport.builder().members(List.of()).build())
                .conformance(ReportAssembler.Conformance.builder().standard("sdtmig")
                        .dictionaryBasis("external dictionaries degraded: 0 of 98 …")
                        .neoplasmVersion("2026-03-27")
                        .libraryMetadataBasis("unavailable — the CDISC Library …").build())
                .sections();
        try (XSSFWorkbook wb = render(degraded, 10_000))
        {
            Sheet c = wb.getSheet("Conformance Details");
            assertEquals("Dictionary Basis", string(c, 20, 0));
            assertEquals("external dictionaries degraded: 0 of 98 …", string(c, 20, 1));
            assertEquals("Neoplasm Version", string(c, 21, 0));
            assertEquals("2026-03-27", string(c, 21, 1));
            assertEquals("Library Metadata Basis", string(c, 22, 0));
            assertEquals("unavailable — the CDISC Library …", string(c, 22, 1));
        }
    }


    /**
     * On a healthy run the two basis keys are absent by design, and their template cells are
     * deliberately blank — "not configured" would misread absence (= healthy) as a problem — while
     * {@code Neoplasm Version} defaults like its six version siblings.
     */
    @Test
    void healthyRunLeavesTheBasisRowsBlank() throws Exception
    {
        try (XSSFWorkbook wb = render(sampleSections(), 10_000))
        {
            Sheet c = wb.getSheet("Conformance Details");
            assertNull(string(c, 20, 1), "Dictionary Basis stays blank on a healthy run");
            assertEquals("not configured", string(c, 21, 1)); // Neoplasm Version default
            assertNull(string(c, 22, 1), "Library Metadata Basis stays blank on a healthy run");
        }
    }

    // ------------------------------------------------------------------
    // Dataset Details — numeric cells
    // ------------------------------------------------------------------


    @Test
    void datasetDetailsWritesValuesAndNumericCells() throws Exception
    {
        try (XSSFWorkbook wb = render(sampleSections(), 10_000))
        {
            Sheet ds = wb.getSheet("Dataset Details");
            Row r = ds.getRow(1);
            assertEquals("ae.xpt", r.getCell(0).getStringCellValue());
            assertEquals("Adverse Events", r.getCell(1).getStringCellValue());
            assertEquals("/study/sdtm", r.getCell(2).getStringCellValue());
            assertEquals(CellType.NUMERIC, r.getCell(4).getCellType());
            assertEquals(12.345, r.getCell(4).getNumericCellValue(), 1e-9);
            assertEquals(CellType.NUMERIC, r.getCell(5).getCellType());
            assertEquals(100.0, r.getCell(5).getNumericCellValue(), 1e-9);
        }
    }

    // ------------------------------------------------------------------
    // Issue Summary
    // ------------------------------------------------------------------


    @Test
    void issueSummaryRowsWritten() throws Exception
    {
        try (XSSFWorkbook wb = render(sampleSections(), 10_000))
        {
            Sheet s = wb.getSheet("Issue Summary");
            Row r0 = s.getRow(1);
            assertEquals("ae.xpt", r0.getCell(0).getStringCellValue());
            assertEquals(CORE_001, r0.getCell(1).getStringCellValue());
            assertEquals(CellType.NUMERIC, r0.getCell(3).getCellType());
            assertEquals(2.0, r0.getCell(3).getNumericCellValue(), 1e-9);
        }
    }

    // ------------------------------------------------------------------
    // Issue Details — list joining, numeric row
    // ------------------------------------------------------------------


    @Test
    void issueDetailsJoinsListsAndWritesNumericRow() throws Exception
    {
        try (XSSFWorkbook wb = render(sampleSections(), 10_000))
        {
            Sheet det = wb.getSheet("Issue Details");
            Row r = det.getRow(1); // first detail, sorted by (core_id, dataset)
            assertEquals(CORE_001, r.getCell(0).getStringCellValue());
            assertEquals("ae.xpt", r.getCell(3).getStringCellValue());
            assertEquals("CDISC001", r.getCell(4).getStringCellValue()); // USUBJID
            assertEquals(CellType.NUMERIC, r.getCell(5).getCellType()); // Record (row)
            assertEquals(2.0, r.getCell(5).getNumericCellValue(), 1e-9);
            assertEquals("1", r.getCell(6).getStringCellValue()); // SEQ
            assertEquals("AESTDY, DOMAIN", r.getCell(7).getStringCellValue()); // Variable(s)
            assertEquals("5, AE", r.getCell(8).getStringCellValue()); // Value(s)
        }
    }

    // ------------------------------------------------------------------
    // Rules Report
    // ------------------------------------------------------------------


    @Test
    void rulesReportRowsWritten() throws Exception
    {
        try (XSSFWorkbook wb = render(sampleSections(), 10_000))
        {
            Sheet rr = wb.getSheet("Rules Report");
            Row r = rr.getRow(1);
            assertEquals(CORE_001, r.getCell(0).getStringCellValue());
            assertEquals("1", r.getCell(1).getStringCellValue());
            assertEquals("ISSUE_REPORTED", r.getCell(5).getStringCellValue());
        }
    }

    // ------------------------------------------------------------------
    // Skipped Rules (programmatically created sheet)
    // ------------------------------------------------------------------


    @Test
    void skippedRulesSheetCreatedWithHeaderWhenEmpty() throws Exception
    {
        // The template carries no Skipped Rules sheet — the writer appends it with its header
        // even when there are no skipped entries.
        try (XSSFWorkbook wb = render(sampleSections(), 10_000))
        {
            Sheet sk = wb.getSheet("Skipped Rules");
            assertEquals("Core ID", string(sk, 0, 0));
            assertEquals("Dataset", string(sk, 0, 1));
            assertEquals("Reason", string(sk, 0, 2));
            assertNull(string(sk, 1, 0), "no data rows without skipped entries");
        }
    }


    @Test
    void skippedRulesRowsWritten() throws Exception
    {
        try (XSSFWorkbook wb = render(sectionsWithSkippedRules(), 10_000))
        {
            Sheet sk = wb.getSheet("Skipped Rules");
            Row r1 = sk.getRow(1);
            assertEquals("CORE-000351", r1.getCell(0).getStringCellValue());
            assertEquals("EX", r1.getCell(1).getStringCellValue());
            assertEquals("domain EX not in Scope.Domains.Include [AE]",
                    r1.getCell(2).getStringCellValue());
            Row r2 = sk.getRow(2);
            assertEquals(CORE_002, r2.getCell(0).getStringCellValue());
            assertEquals("DM", r2.getCell(1).getStringCellValue());
            assertEquals("Rule skipped — no Library access", r2.getCell(2).getStringCellValue());
        }
    }

    // ------------------------------------------------------------------
    // Truncation
    // ------------------------------------------------------------------


    @Test
    void listSheetsTruncatedToMaxRows() throws Exception
    {
        // The template ships pre-formatted empty placeholder rows below the header (matching
        // openpyxl), so truncation is asserted on the data written, not on row existence: with a
        // cap of 1, exactly one data row carries a core_id and rows beyond it stay blank.
        try (XSSFWorkbook wb = render(sampleSections(), 1))
        {
            Sheet det = wb.getSheet("Issue Details");
            assertEquals(CORE_001, string(det, 1, 0), "first (only) data row present");
            assertNull(string(det, 2, 0), "no data written beyond the cap");
            assertNull(string(det, 3, 0), "no data written beyond the cap");
        }
    }

    // ------------------------------------------------------------------
    // resolveMaxRows
    // ------------------------------------------------------------------


    @Test
    void resolveMaxRowsDefaultsTo10000WhenNothingSet()
    {
        assertEquals(10_000, XlsxReportWriter.resolveMaxRows(null));
    }


    @Test
    void resolveMaxRowsUsesArgWhenSet()
    {
        assertEquals(250, XlsxReportWriter.resolveMaxRows(250));
    }


    @Test
    void resolveMaxRowsZeroMeansUnlimited()
    {
        assertNull(XlsxReportWriter.resolveMaxRows(0));
    }


    @Test
    void resolveMaxRowsNegativeFallsBackToDefault()
    {
        assertEquals(10_000, XlsxReportWriter.resolveMaxRows(-5));
    }

    // ------------------------------------------------------------------
    // Relocated with the writer (Fix #224): these lived in corej-cdisc-core, where they can no
    // longer reach the XLSX writer at all.
    // ------------------------------------------------------------------


    /**
     * From {@code RulesReportSkippedStatusTest} (C2): a rule that was skipped on every dataset must
     * surface as {@code SKIPPED} in the Rules Report sheet, and one that ran somewhere must not.
     * The status is derived in the engine's assembler; what is proved here is that the workbook
     * shows whatever the shared sections say.
     */
    @Test
    void rulesReportSheetShowsTheSkippedStatusFromTheSections() throws Exception
    {
        ValidationReport skippedEverywhere = ValidationReport.builder().members(List.of())
                .skippedRules(List.of(net.cumba.datatable.report.SkippedRuleEntry.builder()
                        .coreId(CORE_001).dataset("AE").reason("out of scope").build()))
                .executedCoreIds(List.of()).build();
        try (XSSFWorkbook wb = render(new ReportAssembler().report(skippedEverywhere)
                .rules(List.of(rule(CORE_001))).sections(), 10_000))
        {
            Row row = wb.getSheet("Rules Report").getRow(1);
            assertEquals(CORE_001, row.getCell(0).getStringCellValue());
            // RULES_COLUMNS = core_id, version, cdisc_rule_id, fda_rule_id, message, status
            assertEquals("SKIPPED", row.getCell(5).getStringCellValue());
        }

        ValidationReport partiallySkipped = ValidationReport.builder().members(List.of())
                .skippedRules(List.of(net.cumba.datatable.report.SkippedRuleEntry.builder()
                        .coreId(CORE_001).dataset("VS").reason("out of scope").build()))
                .executedCoreIds(List.of(CORE_001)).build();
        try (XSSFWorkbook wb = render(new ReportAssembler().report(partiallySkipped)
                .rules(List.of(rule(CORE_001))).sections(), 10_000))
        {
            assertEquals("SUCCESS",
                    wb.getSheet("Rules Report").getRow(1).getCell(5).getStringCellValue());
        }
    }


    /**
     * From {@code StudyPseudoDomainReportTest}: {@code STUDY} is a synthetic domain with no dataset
     * behind it, and the workbook must render its finding rows without tripping over the absent
     * {@code Dataset_Details} row.
     */
    @Test
    void rendersASyntheticStudyDomainRow() throws Exception
    {
        ReportSections sections = new ReportSections(java.util.Map.of("Standard", "SDTMIG"),
                List.of(),
                List.of(java.util.Map.of("dataset", "STUDY", "core_id", "CORE-STUDY-REPORT",
                        "message", "study-level finding", "issues", 1)),
                List.of(java.util.Map.of("core_id", "CORE-STUDY-REPORT", "message",
                        "study-level finding", "dataset", "STUDY", "domain", "STUDY")),
                List.of(java.util.Map.of("core_id", "CORE-STUDY-REPORT", "status", "SUCCESS")),
                List.of());
        try (XSSFWorkbook wb = render(sections, null))
        {
            assertEquals("STUDY",
                    wb.getSheet("Issue Summary").getRow(1).getCell(0).getStringCellValue());
            assertEquals("CORE-STUDY-REPORT",
                    wb.getSheet("Issue Details").getRow(1).getCell(0).getStringCellValue());
            // Unlimited cap renders as the literal "None" in Conformance row 7.
            assertEquals("None",
                    wb.getSheet("Conformance Details").getRow(6).getCell(1).getStringCellValue());
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------


    private static XSSFWorkbook render(ReportSections sections, Integer maxRows) throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new XlsxReportWriter(maxRows).write(sections, out);
        return new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()));
    }


    private static ReportSections sampleSections()
    {
        List<String> aeNames = List.of("USUBJID", "SEQ", "AESTDY", "DOMAIN");
        RowFindingSlab aeSlab = RowFindingSlab.builder(aeNames.size()).addRow(1, new String[]
        {
                "CDISC001", "1", "5", "AE"
        }).addRow(4, new String[]
        {
                "CDISC002", "3", "9", "AE"
        }).build();
        ValidationFinding ae001 = ValidationFinding.builder().source("cumba.core").ruleId(CORE_001)
                .severity(Severity.ERROR).kind(FindingKind.RULE_VIOLATION).message("AE issue")
                .variableNames(aeNames).rows(aeSlab).build();

        List<String> dmNames = List.of("USUBJID", "SEQ", "AGE");
        RowFindingSlab dmSlab = RowFindingSlab.builder(dmNames.size()).addRow(0, new String[]
        {
                "CDISC003", "1", "120"
        }).build();
        ValidationFinding dm002 = ValidationFinding.builder().source("cumba.core").ruleId(CORE_002)
                .severity(Severity.ERROR).kind(FindingKind.RULE_VIOLATION).message("DM issue")
                .variableNames(dmNames).rows(dmSlab).build();

        ValidationReport report = ValidationReport.builder()
                .members(List.of(
                        ValidationReportMember.builder().domain("AE").fileName("ae.xpt")
                                .findings(List.of(ae001)).build(),
                        ValidationReportMember.builder().domain("DM").fileName("dm.xpt")
                                .findings(List.of(dm002)).build()))
                .build();

        return new ReportAssembler().report(report)
                .conformance(ReportAssembler.Conformance.builder().standard("sdtmig").version("3-4")
                        .totalRuntimeSeconds(1.5).coreEngineVersion("0.5.0.0")
                        .ctVersion("sdtmct-2024-09-27").build())
                .datasets(List.of(new ReportAssembler.DatasetInfo("ae.xpt", "Adverse Events",
                        "/study/sdtm", "2026-04-01T00:00:00", 12.345, 100L, "AE", 30)))
                .rules(List.of(rule(CORE_001), rule(CORE_002))).sections();
    }


    private static ReportSections sectionsWithSkippedRules()
    {
        ValidationReport report = ValidationReport.builder().members(List.of())
                .skippedRules(List.of(
                        net.cumba.datatable.report.SkippedRuleEntry.builder().coreId("CORE-000351")
                                .dataset("EX").reason("domain EX not in Scope.Domains.Include [AE]")
                                .build(),
                        net.cumba.datatable.report.SkippedRuleEntry.builder().coreId(CORE_002)
                                .dataset("DM").reason("Rule skipped — no Library access").build()))
                .build();
        return new ReportAssembler().report(report).sections();
    }


    private static Rule rule(String coreId)
    {
        Rule r = new Rule();
        RuleCore core = new RuleCore();
        core.setId(coreId);
        r.setCore(core);
        return r;
    }


    /**
     * Returns the string value at the cell, treating a missing row, missing cell, or blank/empty
     * cell uniformly as {@code null}. The template ships blank-but-styled placeholder cells, so "no
     * value" and "empty value" must read the same for the assertions above.
     */
    private static String string(Sheet sheet, int rowIdx, int colIdx)
    {
        Row row = sheet.getRow(rowIdx);
        if (row == null)
        {
            return null;
        }
        Cell cell = row.getCell(colIdx);
        if (cell == null || cell.getCellType() == CellType.BLANK)
        {
            return null;
        }
        String value = cell.getStringCellValue();
        return value == null || value.isEmpty() ? null : value;
    }
}
