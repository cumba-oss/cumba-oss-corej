package net.cumba.corej.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.model.Authority;
import net.cumba.corej.core.model.AuthorityStandard;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Reference;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.RuleIdentifier;
import net.cumba.datatable.report.FindingKind;
import net.cumba.datatable.report.RowFindingSlab;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.report.ValidationFinding;
import net.cumba.datatable.report.ValidationReport;
import net.cumba.datatable.report.ValidationReportMember;
import org.junit.jupiter.api.Test;

class ReportDocumentV1Test
{

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> section(Map<String, Object> export, String name)
    {
        return (List<Map<String, Object>>) export.get(name);
    }

    private static final String CORE_001 = "CORE-001";

    private static final String CORE_002 = "CORE-002";

    private static final String CORE_003_NO_ISSUES = "CORE-003";

    @Test
    void emptyReport_emitsAllSectionsAndPlaceholders()
    {
        Map<String, Object> export = new ReportAssembler()
                .report(ValidationReport.builder().members(List.of()).build())
                .conformance(ReportAssembler.Conformance.builder().standard("sdtmig").version("3-4")
                        .totalRuntimeSeconds(10.1).coreEngineVersion("0.5.0.0")
                        .ctVersion("sdtmct-2024-09-27").defineXmlVersion("2.1").build())
                .sections().toExportDocument();

        assertTrue(export.containsKey("Conformance_Details"));
        assertTrue(export.containsKey("Dataset_Details"));
        assertTrue(export.containsKey("Issue_Summary"));
        assertTrue(export.containsKey("Issue_Details"));
        assertTrue(export.containsKey("Rules_Report"));

        @SuppressWarnings("unchecked")
        Map<String, Object> conformance = (Map<String, Object>) export.get("Conformance_Details");
        assertEquals("SDTMIG", conformance.get("Standard"));
        assertEquals("V3.4", conformance.get("Version"));
        assertEquals("0.5.0.0", conformance.get("CORE_Engine_Version"));
        assertEquals("10.10 seconds", conformance.get("Total_Runtime"));
        assertEquals("None", conformance.get("Issue_Limit_Per_Rule"));
        assertEquals("None", conformance.get("Issue_Limit_Per_Dataset"));
        assertTrue(conformance.containsKey("Issue_Limit_Per_Sheet"));
        assertNull(conformance.get("Issue_Limit_Per_Sheet"));
        assertEquals("sdtmct-2024-09-27", conformance.get("CT_Version"));
        assertEquals("2.1", conformance.get("Define_XML_Version"));

        assertEquals(List.of(), export.get("Dataset_Details"));
        assertEquals(List.of(), export.get("Issue_Summary"));
        assertEquals(List.of(), export.get("Issue_Details"));
        assertEquals(List.of(), export.get("Rules_Report"));
    }


    /**
     * Shared setup for the populated-report tests below: builds an export containing two datasets
     * (AE with two findings, DM with one) and three rules (CORE-001 / CORE-002 both with findings,
     * CORE-003 with none).
     */
    private static Map<String, Object> populatedReportExport()
    {
        ValidationReport report = sampleReport();
        List<Rule> rules = List.of(rule(CORE_001), rule(CORE_002), rule(CORE_003_NO_ISSUES));
        return new ReportAssembler().report(report)
                .conformance(ReportAssembler.Conformance.builder().standard("sdtmig").version("3-4")
                        .totalRuntimeSeconds(1.5).coreEngineVersion("0.5.0.0").build())
                .datasets(List.of(new ReportAssembler.DatasetInfo("ae.xpt", "Adverse Events",
                        "/study/sdtm", "2026-04-01T00:00:00", 12.345, 100L, "AE", 30)))
                .rules(rules).sections().toExportDocument();
    }


    @Test
    void populatedReport_datasetDetails_carriesSuppliedMetadata()
    {
        Map<String, Object> export = populatedReportExport();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = (List<Map<String, Object>>) export
                .get("Dataset_Details");

        assertEquals(1, details.size());
        Map<String, Object> ae = details.get(0);
        assertEquals("ae.xpt", ae.get("filename"));
        assertEquals("Adverse Events", ae.get("label"));
        assertEquals(12.345, ae.get("size_kb"));
        assertEquals(100L, ae.get("length"));
        // Additive Java fields: domain + column count, so the datasets of a multi-dataset file
        // (sharing a filename) can be told apart.
        assertEquals("AE", ae.get("domain"));
        assertEquals(30, ae.get("columns"));
    }


    @Test
    void populatedReport_issueSummary_isSortedByDatasetAndCoreId()
    {
        // Python-parity: dataset uses the source file name; rows sort by (dataset, core_id).
        Map<String, Object> export = populatedReportExport();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> summary = (List<Map<String, Object>>) export.get("Issue_Summary");

        assertEquals(2, summary.size());
        assertEquals("ae.xpt", summary.get(0).get("dataset"));
        assertEquals(CORE_001, summary.get(0).get("core_id"));
        assertEquals(2, summary.get(0).get("issues"));
        assertEquals("AE", summary.get(0).get("domain"));
        assertEquals("dm.xpt", summary.get(1).get("dataset"));
        assertEquals(CORE_002, summary.get(1).get("core_id"));
        assertEquals(1, summary.get(1).get("issues"));
        assertEquals("DM", summary.get(1).get("domain"));
    }


    @Test
    void populatedReport_issueDetails_firstEntryCarriesRowFields()
    {
        // Three row findings total, sorted by (core_id, dataset). The first entry must carry
        // the row index, USUBJID, SEQ and the output-variable slice (output vars minus
        // USUBJID and SEQ — here: AESTDY, DOMAIN).
        Map<String, Object> export = populatedReportExport();
        List<Map<String, Object>> issues = section(export, "Issue_Details");

        assertEquals(3, issues.size());
        Map<String, Object> first = issues.get(0);
        assertEquals(CORE_001, first.get("core_id"));
        assertEquals("ae.xpt", first.get("dataset"));
        assertEquals("AE", first.get("domain"));
        assertEquals("CDISC001", first.get("USUBJID"));
        assertEquals(2, first.get("row"));
        assertEquals("1", first.get("SEQ"));
        assertEquals(List.of("AESTDY", "DOMAIN"), first.get("variables"));
        assertEquals(List.of("5", "AE"), first.get("values"));
    }


    @Test
    void populatedReport_rulesReport_statusesAndCitations()
    {
        // Three rules: CORE-001 / CORE-002 ISSUE_REPORTED, CORE-003 SUCCESS (no findings).
        // CORE-001 carries CDISC + FDA authority citations.
        Map<String, Object> export = populatedReportExport();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rulesReport = (List<Map<String, Object>>) export
                .get("Rules_Report");

        assertEquals(3, rulesReport.size());
        assertEquals(CORE_001, rulesReport.get(0).get("core_id"));
        assertEquals("ISSUE_REPORTED", rulesReport.get(0).get("status"));
        assertEquals("CDISC-1, CDISC-2", rulesReport.get(0).get("cdisc_rule_id"));
        assertEquals("FDA-1", rulesReport.get(0).get("fda_rule_id"));
        assertEquals(CORE_002, rulesReport.get(1).get("core_id"));
        assertEquals("ISSUE_REPORTED", rulesReport.get(1).get("status"));
        assertEquals(CORE_003_NO_ISSUES, rulesReport.get(2).get("core_id"));
        assertEquals("SUCCESS", rulesReport.get(2).get("status"));
    }


    @Test
    void runtimeMaps_surfaceRuntimeMsInRulesReportAndDatasetDetails()
    {
        Map<String, Object> export = new ReportAssembler().report(sampleReport())
                .conformance(ReportAssembler.Conformance.builder().standard("sdtmig").version("3-4")
                        .build())
                .datasets(List.of(new ReportAssembler.DatasetInfo("ae.xpt", "Adverse Events",
                        "/study/sdtm", "2026-04-01T00:00:00", 1.0, 100L, "AE", 30)))
                .rules(List.of(rule(CORE_001), rule(CORE_002), rule(CORE_003_NO_ISSUES)))
                .ruleRuntimesMillis(Map.of(CORE_001, 30L))
                .datasetRuntimesMillis(
                        Map.of(ReportAssembler.datasetRuntimeKey("ae.xpt", "AE"), 250L))
                .sections().toExportDocument();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rulesReport = (List<Map<String, Object>>) export
                .get("Rules_Report");
        // Rows sort by core_id: CORE_001 carries its summed runtime; an unmapped rule yields -1.
        assertEquals(CORE_001, rulesReport.get(0).get("core_id"));
        assertEquals(30L, rulesReport.get(0).get("runtime_ms"));
        assertEquals(-1L, rulesReport.get(1).get("runtime_ms"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = (List<Map<String, Object>>) export
                .get("Dataset_Details");
        assertEquals(250L, details.get(0).get("runtime_ms"));
    }


    @Test
    void runtimeMaps_absent_yieldMinusOne()
    {
        Map<String, Object> export = populatedReportExport();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rulesReport = (List<Map<String, Object>>) export
                .get("Rules_Report");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = (List<Map<String, Object>>) export
                .get("Dataset_Details");

        assertEquals(-1L, rulesReport.get(0).get("runtime_ms"));
        assertEquals(-1L, details.get(0).get("runtime_ms"));
    }


    @Test
    void engineErrorFinding_mappedToExecutionErrorAndExcludedFromSummary()
    {
        ValidationFinding error = ValidationFinding.builder().source("cumba.core").ruleId(CORE_001)
                .kind(FindingKind.ENGINE_ERROR).severity(Severity.ERROR).message("Boom").build();
        ValidationReportMember member = ValidationReportMember.builder().domain("AE")
                .fileName("ae.xpt").findings(List.of(error)).build();
        ValidationReport report = ValidationReport.builder().members(List.of(member)).build();

        Map<String, Object> export = new ReportAssembler().report(report)
                .rules(List.of(rule(CORE_001))).sections().toExportDocument();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> summary = (List<Map<String, Object>>) export.get("Issue_Summary");
        assertEquals(0, summary.size(), "Engine errors must not appear in Issue_Summary");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rulesReport = (List<Map<String, Object>>) export
                .get("Rules_Report");
        assertEquals(1, rulesReport.size());
        assertEquals("EXECUTION_ERROR", rulesReport.get(0).get("status"));
    }


    @Test
    void issueSummaryAndDetails_useFileNameWhenAvailable()
    {
        // Member without fileName — writer falls back to the upper-cased domain.
        ValidationFinding finding = ValidationFinding.builder().source("cumba.core")
                .ruleId(CORE_001).severity(Severity.ERROR).kind(FindingKind.RULE_VIOLATION)
                .message("AE issue").variableNames(List.of("AESTDY"))
                .rows(RowFindingSlab.builder(1).addRow(0, new String[]
                {
                        "5"
                }).build()).build();
        ValidationReportMember memberNoFile = ValidationReportMember.builder().domain("AE")
                .findings(List.of(finding)).build();
        ValidationReport report = ValidationReport.builder().members(List.of(memberNoFile)).build();

        Map<String, Object> export = new ReportAssembler().report(report)
                .rules(List.of(rule(CORE_001))).sections().toExportDocument();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> summary = (List<Map<String, Object>>) export.get("Issue_Summary");
        assertEquals("AE", summary.get(0).get("dataset"),
                "When fileName is null the writer must fall back to the domain name");
    }


    @Test
    void executability_emittedAsLowerCasePythonForm()
    {
        // Build a finding that already carries the Python-form executability (Python-parity:
        // ValidationReportBuilder is responsible for converting Executability → pythonValue).
        ValidationFinding finding = ValidationFinding.builder().source("cumba.core")
                .ruleId(CORE_001).severity(Severity.ERROR).kind(FindingKind.RULE_VIOLATION)
                .executability("partially executable - possible overreporting").message("AE issue")
                .variableNames(List.of("AESTDY"))
                .rows(RowFindingSlab.builder(1).addRow(0, new String[]
                {
                        "5"
                }).build()).build();
        ValidationReportMember member = ValidationReportMember.builder().domain("AE")
                .fileName("ae.xpt").findings(List.of(finding)).build();
        ValidationReport report = ValidationReport.builder().members(List.of(member)).build();

        Map<String, Object> export = new ReportAssembler().report(report)
                .rules(List.of(rule(CORE_001))).sections().toExportDocument();
        List<Map<String, Object>> issues = section(export, "Issue_Details");
        assertEquals("partially executable - possible overreporting",
                issues.get(0).get("executability"));
    }


    @Test
    void successRule_emitsDeclaredOutcomeMessage()
    {
        // Fix #51 — Python-parity: Rules_Report.message is the rule's declared Outcome.Message
        // even when the rule produced no findings (status SUCCESS).
        Rule rule = rule(CORE_001);
        Outcome outcome = new Outcome();
        outcome.setMessage("AESEV is required.");
        rule.setOutcome(outcome);

        Map<String, Object> export = new ReportAssembler()
                .report(ValidationReport.builder().members(List.of()).build()).rules(List.of(rule))
                .sections().toExportDocument();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rulesReport = (List<Map<String, Object>>) export
                .get("Rules_Report");
        assertEquals(1, rulesReport.size());
        assertEquals(CORE_001, rulesReport.get(0).get("core_id"));
        assertEquals("SUCCESS", rulesReport.get(0).get("status"));
        assertEquals("AESEV is required.", rulesReport.get(0).get("message"));
    }


    @Test
    void successRuleWithoutOutcome_emitsNullMessage()
    {
        // Defensive: rules without an Outcome (some synthetic GEN-* rules) emit message=null,
        // not a sentinel string.
        Rule rule = rule(CORE_001);
        // no outcome attached

        Map<String, Object> export = new ReportAssembler()
                .report(ValidationReport.builder().members(List.of()).build()).rules(List.of(rule))
                .sections().toExportDocument();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rulesReport = (List<Map<String, Object>>) export
                .get("Rules_Report");
        assertEquals("SUCCESS", rulesReport.get(0).get("status"));
        assertNull(rulesReport.get(0).get("message"));
    }


    @Test
    void issueReportedRule_keepsFindingMessageNotDeclared()
    {
        // Fix #51 — when the rule has findings, the finding's message wins over the declared
        // Outcome.Message. Behaviour for ISSUE_REPORTED rows is unchanged.
        Rule rule = rule(CORE_001);
        Outcome outcome = new Outcome();
        outcome.setMessage("Declared text");
        rule.setOutcome(outcome);

        ValidationFinding finding = ValidationFinding.builder().source("cumba.core")
                .ruleId(CORE_001).severity(Severity.ERROR).kind(FindingKind.RULE_VIOLATION)
                .message("Finding text").variableNames(List.of("AESTDY"))
                .rows(RowFindingSlab.builder(1).addRow(0, new String[]
                {
                        "5"
                }).build()).build();
        ValidationReportMember member = ValidationReportMember.builder().domain("AE")
                .fileName("ae.xpt").findings(List.of(finding)).build();
        ValidationReport report = ValidationReport.builder().members(List.of(member)).build();

        Map<String, Object> export = new ReportAssembler().report(report).rules(List.of(rule))
                .sections().toExportDocument();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rulesReport = (List<Map<String, Object>>) export
                .get("Rules_Report");
        assertEquals("ISSUE_REPORTED", rulesReport.get(0).get("status"));
        assertEquals("Finding text", rulesReport.get(0).get("message"));
    }


    @Test
    void summaryBundlesGenExpRulesByOriginalCoreId()
    {
        // Fix #53 — the AE and CM `--` expansions of CORE-000767 now share the bare base id
        // `CORE-000767` (matching Python). Listing that id in bundledCoreIds collapses their
        // per-domain rows into one summary row, with both dataset names joined alphabetically and
        // issue counts summed.
        ValidationFinding aeFinding = bundleFinding("CORE-000767",
                "Parent --DECOD differs from FAOBJ.", 100);
        ValidationFinding cmFinding = bundleFinding("CORE-000767",
                "Parent --DECOD differs from FAOBJ.", 250);
        ValidationReport report = ValidationReport.builder()
                .members(List.of(
                        ValidationReportMember.builder().domain("AE").fileName("ae.xpt")
                                .findings(List.of(aeFinding)).build(),
                        ValidationReportMember.builder().domain("CM").fileName("cm.xpt")
                                .findings(List.of(cmFinding)).build()))
                .build();

        Map<String, Object> export = new ReportAssembler().report(report).rules(List.of())
                .bundledCoreIds(Set.of("CORE-000767")).sections().toExportDocument();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> summary = (List<Map<String, Object>>) export.get("Issue_Summary");
        assertEquals(1, summary.size(), "two per-domain expansions collapse into one summary row");
        assertEquals("CORE-000767", summary.get(0).get("core_id"));
        assertEquals("ae.xpt, cm.xpt", summary.get(0).get("dataset"));
        assertEquals(350, summary.get(0).get("issues"));

        // Issue_Details is per-row by design (Python parity: one entry per actual violation row);
        // bundling only collapses the Issue_Summary view. With 100 + 250 slab rows we expect 350
        // detail entries, every one carrying the shared bare id CORE-000767.
        List<Map<String, Object>> details = section(export, "Issue_Details");
        assertEquals(350, details.size(), "Issue_Details emits one entry per slab row");
        assertTrue(details.stream().allMatch(d -> "CORE-000767".equals(d.get("core_id"))));
    }


    @Test
    void summaryBundlesAlphabetically()
    {
        // Members fed in non-alphabetical order (MH, AE, CM) — the joined dataset string must
        // still come out alphabetical: ae.xpt, cm.xpt, mh.xpt. Deterministic regardless of run
        // order.
        ValidationReport report = ValidationReport.builder()
                .members(List.of(
                        ValidationReportMember.builder().domain("MH").fileName("mh.xpt")
                                .findings(List.of(bundleFinding("CORE-000767", "msg", 1))).build(),
                        ValidationReportMember.builder().domain("AE").fileName("ae.xpt")
                                .findings(List.of(bundleFinding("CORE-000767", "msg", 1))).build(),
                        ValidationReportMember.builder().domain("CM").fileName("cm.xpt")
                                .findings(List.of(bundleFinding("CORE-000767", "msg", 1))).build()))
                .build();
        Map<String, Object> export = new ReportAssembler().report(report).rules(List.of())
                .bundledCoreIds(Set.of("CORE-000767")).sections().toExportDocument();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> summary = (List<Map<String, Object>>) export.get("Issue_Summary");
        assertEquals(1, summary.size());
        assertEquals("ae.xpt, cm.xpt, mh.xpt", summary.get(0).get("dataset"));
    }


    @Test
    void summaryDoesNotBundleNonExpRules()
    {
        // Synthetic IDs not in bundledCoreIds (GEN-VMCALM-LBL, GEN-ANLFNFL, …) have no Python
        // counterpart to bundle against — they remain as separate per-dataset rows.
        ValidationFinding aeFinding = bundleFinding("GEN-VMCALM-LBL", "label issue", 1);
        ValidationFinding cmFinding = bundleFinding("GEN-VMCALM-LBL", "label issue", 1);
        ValidationReport report = ValidationReport.builder()
                .members(List.of(
                        ValidationReportMember.builder().domain("AE").fileName("ae.xpt")
                                .findings(List.of(aeFinding)).build(),
                        ValidationReportMember.builder().domain("CM").fileName("cm.xpt")
                                .findings(List.of(cmFinding)).build()))
                .build();
        Map<String, Object> export = new ReportAssembler().report(report).rules(List.of())
                .sections().toExportDocument();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> summary = (List<Map<String, Object>>) export.get("Issue_Summary");
        assertEquals(2, summary.size(),
                "GEN-VMCALM-LBL is not in bundledCoreIds; rows must not collapse");
        assertEquals("ae.xpt", summary.get(0).get("dataset"));
        assertEquals("GEN-VMCALM-LBL", summary.get(0).get("core_id"));
        assertEquals("cm.xpt", summary.get(1).get("dataset"));
        assertEquals("GEN-VMCALM-LBL", summary.get(1).get("core_id"));
    }


    @Test
    void summaryBundlesOnSameMessageOnly()
    {
        // Same bundled core_id, different messages (the message is part of the bundling key) → two
        // summary rows, not one — even though both share CORE-000767 and it is in bundledCoreIds.
        ValidationFinding ae = bundleFinding("CORE-000767", "AE-flavoured message", 5);
        ValidationFinding cm = bundleFinding("CORE-000767", "CM-flavoured message", 7);
        ValidationReport report = ValidationReport.builder()
                .members(List.of(
                        ValidationReportMember.builder().domain("AE").fileName("ae.xpt")
                                .findings(List.of(ae)).build(),
                        ValidationReportMember.builder().domain("CM").fileName("cm.xpt")
                                .findings(List.of(cm)).build()))
                .build();
        Map<String, Object> export = new ReportAssembler().report(report).rules(List.of())
                .bundledCoreIds(Set.of("CORE-000767")).sections().toExportDocument();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> summary = (List<Map<String, Object>>) export.get("Issue_Summary");
        assertEquals(2, summary.size(), "different messages → bundling key differs → two rows");
    }


    @Test
    void summaryDoesNotBundleOrdinaryCoreIdAbsentFromBundledSet()
    {
        // An ordinary (non-expanded) CORE rule firing in two datasets is NOT in bundledCoreIds — so
        // its per-dataset rows survive even with an identical message. Bundling is reserved for the
        // SDTM `--` expansions named in the set, not every shared CORE id.
        ValidationFinding ae = bundleFinding("CORE-000050", "same message", 3);
        ValidationFinding cm = bundleFinding("CORE-000050", "same message", 4);
        ValidationReport report = ValidationReport.builder()
                .members(List.of(
                        ValidationReportMember.builder().domain("AE").fileName("ae.xpt")
                                .findings(List.of(ae)).build(),
                        ValidationReportMember.builder().domain("CM").fileName("cm.xpt")
                                .findings(List.of(cm)).build()))
                .build();
        Map<String, Object> export = new ReportAssembler().report(report).rules(List.of())
                .bundledCoreIds(Set.of("CORE-000767")).sections().toExportDocument();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> summary = (List<Map<String, Object>>) export.get("Issue_Summary");
        assertEquals(2, summary.size(),
                "ordinary CORE id absent from bundledCoreIds keeps per-dataset rows");
        assertEquals("ae.xpt", summary.get(0).get("dataset"));
        assertEquals("cm.xpt", summary.get(1).get("dataset"));
    }


    @Test
    void documentSerialisesToValidJson() throws Exception
    {
        ValidationReport report = sampleReport();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new ObjectMapper().writeValue(baos,
                new ReportAssembler().report(report)
                        .conformance(ReportAssembler.Conformance.builder().standard("sdtmig")
                                .version("3-4").totalRuntimeSeconds(0.0).build())
                        .sections().toExportDocument());

        Map<String, Object> parsed = new ObjectMapper().readValue(baos.toByteArray(),
                new TypeReference<Map<String, Object>>()
                {
                });
        assertNotNull(parsed.get("Conformance_Details"));
        assertNotNull(parsed.get("Issue_Summary"));
    }

    // ------------------------------------------------------------------
    // Test fixtures
    // ------------------------------------------------------------------


    /**
     * Builds a single rule-violation finding whose schema is the literal {@code AESTDY} column (the
     * body content doesn't matter for summary-bundling tests; only the rule id, message, and row
     * count are asserted).
     */
    private static ValidationFinding bundleFinding(String coreId, String message, int rowCount)
    {
        RowFindingSlab.Builder slab = RowFindingSlab.builder(1);
        for (int i = 0; i < rowCount; i++)
        {
            slab.addRow(i, new String[]
            {
                    Integer.toString(i)
            });
        }
        return ValidationFinding.builder().source("cumba.core").ruleId(coreId)
                .severity(Severity.ERROR).kind(FindingKind.RULE_VIOLATION).message(message)
                .variableNames(List.of("AESTDY")).rows(slab.build()).build();
    }

    // ------------------------------------------------------------------
    // Skipped_Rules (additive Java extension)
    // ------------------------------------------------------------------


    @Test
    void emptyReport_emitsEmptySkippedRules()
    {
        Map<String, Object> export = new ReportAssembler()
                .report(ValidationReport.builder().members(List.of()).build()).sections()
                .toExportDocument();
        assertTrue(export.containsKey("Skipped_Rules"));
        assertEquals(List.of(), export.get("Skipped_Rules"));
    }


    @Test
    void skippedRules_emittedAfterRulesReportWithCoreIdDatasetReason()
    {
        ValidationReport report = ValidationReport.builder().members(List.of())
                .skippedRules(List.of(
                        net.cumba.datatable.report.SkippedRuleEntry.builder().coreId("CORE-000351")
                                .dataset("EX").reason("domain EX not in Scope.Domains.Include [AE]")
                                .build(),
                        net.cumba.datatable.report.SkippedRuleEntry.builder().coreId("CORE-000351")
                                .dataset("SUPPEX")
                                .reason("domain SUPPEX not in Scope.Domains.Include [AE]").build()))
                .build();

        Map<String, Object> export = new ReportAssembler().report(report).sections()
                .toExportDocument();

        // The pre-existing five keys are untouched and Skipped_Rules comes after Rules_Report.
        List<String> keys = List.copyOf(export.keySet());
        assertEquals(List.of("Conformance_Details", "Dataset_Details", "Issue_Summary",
                "Issue_Details", "Rules_Report", "Skipped_Rules"), keys);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> skipped = (List<Map<String, Object>>) export.get("Skipped_Rules");
        assertEquals(2, skipped.size());
        assertEquals("CORE-000351", skipped.get(0).get("core_id"));
        assertEquals("EX", skipped.get(0).get("dataset"));
        assertEquals("domain EX not in Scope.Domains.Include [AE]", skipped.get(0).get("reason"));
        assertEquals("SUPPEX", skipped.get(1).get("dataset"));
        // Each row carries exactly the three fields, in order.
        assertEquals(List.of("core_id", "dataset", "reason"), List.copyOf(skipped.get(0).keySet()));
    }


    private static ValidationReport sampleReport()
    {
        // Schema: USUBJID, SEQ, AESTDY (per AE rule); the DM rule uses USUBJID, SEQ, AGE.
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

        ValidationReportMember ae = ValidationReportMember.builder().domain("AE").fileName("ae.xpt")
                .findings(List.of(ae001)).build();
        ValidationReportMember dm = ValidationReportMember.builder().domain("DM").fileName("dm.xpt")
                .findings(List.of(dm002)).build();

        return ValidationReport.builder().members(List.of(ae, dm)).build();
    }


    private static Rule rule(String coreId)
    {
        Rule r = new Rule();
        RuleCore core = new RuleCore();
        core.setId(coreId);
        r.setCore(core);

        // CDISC authority with two reference IDs
        Authority cdisc = new Authority();
        cdisc.setOrganization("CDISC");
        AuthorityStandard cdiscStd = new AuthorityStandard();
        cdiscStd.setReferences(List.of(reference("CDISC-2"), reference("CDISC-1")));
        cdisc.setStandards(List.of(cdiscStd));

        // FDA authority with one reference id
        Authority fda = new Authority();
        fda.setOrganization("FDA");
        AuthorityStandard fdaStd = new AuthorityStandard();
        fdaStd.setReferences(List.of(reference("FDA-1")));
        fda.setStandards(List.of(fdaStd));

        r.setAuthorities(List.of(cdisc, fda));
        return r;
    }


    private static Reference reference(String id)
    {
        Reference ref = new Reference();
        RuleIdentifier ri = new RuleIdentifier();
        ri.setId(id);
        ref.setRuleIdentifier(ri);
        return ref;
    }
}
