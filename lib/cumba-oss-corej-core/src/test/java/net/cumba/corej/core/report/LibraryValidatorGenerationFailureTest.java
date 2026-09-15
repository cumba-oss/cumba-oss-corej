package net.cumba.corej.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.metadata.MetadataKeys;
import net.cumba.corej.core.metadata.MetadataLibraryProvider;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.run.DatasetExecutionSummary;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.report.FindingKind;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.report.ValidationFinding;
import net.cumba.datatable.report.ValidationReport;
import net.cumba.datatable.report.ValidationReportMember;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.testkit.TestMetadataFixtures;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * The rule-generation-failure path of {@code LibraryValidator.validateDataset} (F-corej-L2-03,
 * work-order rows L-03 / L-04 / L-05).
 *
 * <p>
 * When {@code DatasetRuleResolver.generate} throws, no rule can run against the dataset. That state
 * must travel the same per-dataset error channel as a table-open failure — a visible ERROR finding
 * on the dataset's own domain plus a non-empty {@code errors} list in its
 * {@link DatasetExecutionSummary} — never a run-level warning that leaves the dataset's row reading
 * {@code executed=0, errors=[]}: in a conformance report a clean row for a dataset on which nothing
 * executed is indistinguishable from a genuine "no findings".
 * </p>
 */
class LibraryValidatorGenerationFailureTest
{

    private static final String BOOM = "generation exploded on purpose";

    private static Rule simpleRule() throws Exception
    {
        return RulePackageLoader.loadFromString("""
                {"rules": {"GEN-1": {
                  "Core": { "Id": "GEN-1", "Status": "Published" },
                  "Sensitivity": "Record",
                  "Description": "STUDYID must exist",
                  "Check": { "expression": "not var_exists(\\"STUDYID\\")" },
                  "Outcome": { "Message": "STUDYID is missing." }
                }}}
                """).getRules().get("GEN-1");
    }


    /**
     * A provider whose {@code getDeclaredDatasetClass} throws. In the LibraryValidator flow that
     * method is consulted only inside {@code DatasetRuleResolver.generate}, so the throw surfaces
     * exactly on the generation-failure path under test; everything else delegates to a real
     * {@link MetadataLibraryProvider}.
     */
    private static MetadataProvider throwingProvider()
    {
        IMetadataLibrary lib = TestMetadataFixtures.lib("study")
                .meta(MetadataKeys.STANDARD_NAME, "sdtmig")
                .meta(MetadataKeys.STANDARD_VERSION, "3-4")
                .table(TestMetadataFixtures.table("AE").label("Adverse Events").className("Events")
                        .column(TestMetadataFixtures.column("STUDYID", 0, DataValueType.STRING)
                                .label("Study Identifier").core("Req").role("Identifier").build())
                        .build())
                .build();
        MetadataProvider real = new MetadataLibraryProvider(lib);
        return (MetadataProvider) Proxy.newProxyInstance(
                LibraryValidatorGenerationFailureTest.class.getClassLoader(), new Class<?>[]
                {
                        MetadataProvider.class
                }, (proxy, method, args) ->
                {
                    if ("getDeclaredDatasetClass".equals(method.getName()))
                    {
                        throw new IllegalStateException(BOOM);
                    }
                    try
                    {
                        return method.invoke(real, args);
                    }
                    catch (java.lang.reflect.InvocationTargetException e)
                    {
                        throw e.getCause();
                    }
                });
    }


    private static IDataTable ae()
    {
        return MockTable.of().name("AE").col("STUDYID", "S1", "S1").build();
    }


    @Test
    void generationFailure_isAPerDatasetError_notACleanRow() throws Exception
    {
        LibraryValidator validator = LibraryValidator.builder().provider(throwingProvider())
                .rules(List.of(simpleRule())).libraryUri("file:///study")
                .targetDataset("AE", "ae.json", ae()).build();
        ValidationReport report = validator.validate();

        // 1 — the execution summary carries the failure as an ERROR entry, not a clean row.
        List<DatasetExecutionSummary> summaries = validator.getExecutionSummaries();
        assertEquals(1, summaries.size());
        DatasetExecutionSummary summary = summaries.get(0);
        assertEquals("AE", summary.domain());
        assertEquals(0, summary.rulesExecuted());
        assertEquals(1, summary.errors().size(), "the dataset must NOT read errors=[]");
        DatasetExecutionSummary.RuleError error = summary.errors().get(0);
        assertEquals(ValidationReportBuilder.DATASET_LOAD_ERROR_RULE_ID, error.ruleId());
        assertTrue(error.message().contains("Rule generation failed for AE"), error.message());
        assertTrue(error.message().contains(BOOM), error.message());
        // L-03: this failure path measures its runtime like the table-open failure path does,
        // instead of reporting the -1 "not measured" sentinel.
        assertTrue(summary.runtimeMillis() >= 0,
                "generation failure must record elapsed time, not -1: " + summary.runtimeMillis());

        // 2 — the report attaches an ERROR finding to the dataset's own domain.
        ValidationReportMember ae = report.getMembers().stream()
                .filter(m -> "AE".equals(m.getDomain())).findFirst()
                .orElseThrow(() -> new AssertionError("no AE member in the report"));
        ValidationFinding finding = ae.getFindings().stream().filter(
                f -> ValidationReportBuilder.DATASET_LOAD_ERROR_RULE_ID.equals(f.getRuleId()))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "no dataset-error finding on AE: " + ae.getFindings()));
        assertEquals(Severity.ERROR, finding.getSeverity());
        assertEquals(FindingKind.ENGINE_ERROR, finding.getKind());
        assertTrue(finding.getMessage().contains("Rule generation failed for AE"),
                finding.getMessage());
        assertTrue(finding.getMessage().contains(BOOM), finding.getMessage());

        // 3 — it is an error now, not a run-level warning: the message appears nowhere as a
        // WARNING-severity library finding.
        for (ValidationReportMember member : report.getMembers())
        {
            for (ValidationFinding f : member.getFindings())
            {
                if (f.getSeverity() == Severity.WARNING)
                {
                    assertTrue(!f.getMessage().contains("Rule generation failed"),
                            "generation failure demoted to a warning again: " + f.getMessage());
                }
            }
        }
    }

}
