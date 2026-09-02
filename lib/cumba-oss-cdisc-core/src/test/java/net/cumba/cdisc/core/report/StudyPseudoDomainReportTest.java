package net.cumba.cdisc.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.core.metadata.MetadataKeys;
import net.cumba.cdisc.core.metadata.MetadataLibraryProvider;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.cdisc.core.run.DatasetExecutionSummary;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.report.ValidationReport;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.testkit.TestMetadataFixtures;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * The {@code STUDY} pseudo-domain as seen by the <em>report surface</em>.
 *
 * <p>
 * A collapsed study finding is attached to a domain that is not a real dataset: it has no source
 * file, no {@code Dataset_Details} entry and no rows to point at. Each writer is exercised here
 * rather than assumed to cope. The {@code dataset} field in particular must read {@code STUDY} and
 * not the file name of whichever dataset produced the representative result — that is both wrong on
 * its face and a divergence from the Python reference engine, which emits {@code dataset='STUDY'}.
 * </p>
 */
class StudyPseudoDomainReportTest
{

    private static Rule studyRule() throws Exception
    {
        String json = """
                {
                  "rules": {
                    "CORE-STUDY-REPORT": {
                      "Core": { "Id": "CORE-STUDY-REPORT", "Status": "Published" },
                      "Sensitivity": "Study",
                      "Description": "DM must be present in the study",
                      "Check": { "expression": "not ds_exists(\\"DM\\")" },
                      "Outcome": { "Message": "DM dataset is missing" }
                    }
                  }
                }
                """;
        RulePackage pkg = RulePackageLoader.loadFromString(json);
        return pkg.getRules().get("CORE-STUDY-REPORT");
    }


    private static MetadataProvider provider()
    {
        IMetadataLibrary lib = TestMetadataFixtures.lib("study")
                .meta(MetadataKeys.STANDARD_NAME, "sdtmig")
                .meta(MetadataKeys.STANDARD_VERSION, "3-4")
                .table(TestMetadataFixtures.table("AE").label("Adverse Events").className("Events")
                        .column(TestMetadataFixtures.column("STUDYID", 0, DataValueType.STRING)
                                .label("Study Identifier").core("Req").role("Identifier").build())
                        .build())
                .table(TestMetadataFixtures.table("LB").label("Laboratory Test Results")
                        .className("Findings")
                        .column(TestMetadataFixtures.column("STUDYID", 0, DataValueType.STRING)
                                .label("Study Identifier").core("Req").role("Identifier").build())
                        .build())
                .build();
        return new MetadataLibraryProvider(lib);
    }


    private static IDataTable table(String name)
    {
        return MockTable.of().name(name).col("STUDYID", "S1", "S1").build();
    }


    private static LibraryValidator validator() throws Exception
    {
        return LibraryValidator.builder().provider(provider()).rules(List.of(studyRule()))
                .libraryUri("file:///study").targetDataset("AE", "ae.json", table("AE"))
                .targetDataset("LB", "lb.json", table("LB")).build();
    }


    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> section(Map<String, Object> export, String name)
    {
        return (List<Map<String, Object>>) export.get(name);
    }


    private static List<Map<String, Object>> issueDetails(Map<String, Object> export)
    {
        return section(export, "Issue_Details");
    }


    /** Q13: {@code dataset} must be {@code STUDY}, matching the Python reference engine. */
    @Test
    void issueSummaryAndDetailsLabelBothDatasetAndDomainAsStudy() throws Exception
    {
        ValidationReport report = validator().validate();
        Map<String, Object> export = new ReportAssembler().report(report).sections()
                .toExportDocument();

        List<Map<String, Object>> ours = issueDetails(export).stream()
                .filter(d -> "CORE-STUDY-REPORT".equals(d.get("core_id"))).toList();
        assertEquals(1, ours.size(), "one Issue_Details row for the study rule");
        assertEquals("STUDY", ours.get(0).get("dataset"),
                "dataset must be STUDY, not the representative dataset's file name");
        assertEquals("STUDY", ours.get(0).get("domain"), "domain is the STUDY pseudo-domain");

        List<Map<String, Object>> summary = section(export, "Issue_Summary");
        assertTrue(
                summary.stream().filter(r -> "CORE-STUDY-REPORT".equals(r.get("core_id")))
                        .allMatch(r -> "STUDY".equals(r.get("dataset"))),
                "Issue_Summary agrees with Issue_Details");
    }


    /** No dataset file name may leak into a study finding. */
    @Test
    void noDatasetFileNameLeaksIntoTheStudyFinding() throws Exception
    {
        Map<String, Object> export = new ReportAssembler().report(validator().validate()).sections()
                .toExportDocument();

        assertFalse(
                issueDetails(export).stream()
                        .filter(d -> "CORE-STUDY-REPORT".equals(d.get("core_id")))
                        .anyMatch(d -> String.valueOf(d.get("dataset")).endsWith(".json")),
                "the representative dataset's file name must not surface");
    }


    /** The writer must tolerate a domain with no {@code Dataset_Details} entry. */
    @Test
    void jsonWriterHandlesAStudyDomainWithNoDatasetDetailsEntry() throws Exception
    {
        Map<String, Object> export = new ReportAssembler().report(validator().validate()).sections()
                .toExportDocument();

        assertFalse(
                section(export, "Dataset_Details").stream()
                        .anyMatch(d -> "STUDY".equals(d.get("dataset"))),
                "STUDY is not a real dataset, so it has no Dataset_Details row");
        assertEquals(1,
                issueDetails(export).stream()
                        .filter(d -> "CORE-STUDY-REPORT".equals(d.get("core_id"))).count(),
                "yet the study finding still renders");
    }


    /** Full serialization must not throw on the synthetic domain. */
    @Test
    void theStudyDomainDocumentSerialisesWithoutError() throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(out,
                new ReportAssembler().report(validator().validate()).sections().toExportDocument());

        String json = out.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(json.contains("CORE-STUDY-REPORT"), "the study rule is present in the output");
        assertTrue(json.contains("STUDY"), "the STUDY label is present");
    }

    // The XLSX rendering of the same rows is pinned in the corej-cdisc-report-xlsx module
    // (XlsxReportWriterTest.rendersASyntheticStudyDomainRow): the writer left the engine in
    // Fix #224 and an engine test cannot reach it without recreating the POI dependency.


    /**
     * The synthetic {@code STUDY} execution summary must carry a real runtime — the {@code -1} "not
     * measured" sentinel used to leak straight into the runtime CSV as {@code STUDY,,-1,…}.
     */
    @Test
    void studyExecutionSummaryReportsANonNegativeRuntime() throws Exception
    {
        LibraryValidator validator = validator();
        validator.validate();

        List<DatasetExecutionSummary> studyRows = validator.getExecutionSummaries().stream()
                .filter(s -> "STUDY".equals(s.domain())).toList();
        assertEquals(1, studyRows.size(), "one synthetic STUDY summary row");
        assertTrue(studyRows.get(0).runtimeMillis() >= 0,
                "a row that executed must not report the -1 unset sentinel, was "
                        + studyRows.get(0).runtimeMillis());
        assertEquals(1, studyRows.get(0).findings(), "the collapsed finding is counted once");
    }
}
