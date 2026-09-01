package net.cumba.cdisc.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.core.exec.MockTable;
import net.cumba.cdisc.core.metadata.MetadataKeys;
import net.cumba.cdisc.core.metadata.MetadataLibraryProvider;
import net.cumba.cdisc.core.metadata.TestMetadataFixtures;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.report.ValidationReport;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * {@code Fix #225} end-to-end through a real {@link LibraryValidator} run.
 *
 * <p>
 * Two independent channels feed the report's skipped-rules section, and a fix that reads only one
 * of them looks right on a hand-built fixture and is wrong on a real run:
 * </p>
 * <ol>
 * <li><b>execution-time</b> — the runner declined to evaluate the rule (here: {@code Fix #222}'s
 * absent-and-reported dataset), recorded through {@code ValidationReportBuilder.add};</li>
 * <li><b>generation-time</b> — the rule's scope did not match the dataset, so it never reached the
 * runner at all, recorded through {@code ValidationReportBuilder.skippedRule}.</li>
 * </ol>
 *
 * <p>
 * The unit-level state table lives in {@link RulesReportSkippedStatusTest}; this class exists to
 * prove the two channels really do reach it.
 * </p>
 */
class RulesReportSkippedStatusChannelsTest
{

    private static final String STATUS = "status";

    /** {@code CDISC-CG0368}: the bare DM presence rule — {@code Fix #222}'s whole precondition. */
    private static Rule dmPresenceRule() throws Exception
    {
        return one("""
                {"rules": {"CDISC-CG0368": {
                  "Core": { "Id": "CDISC-CG0368", "Status": "Published" },
                  "Sensitivity": "Study",
                  "Description": "DM must be present in the study",
                  "Check": { "expression": "not ds_exists(\\"DM\\")" },
                  "Outcome": { "Message": "The DM dataset is not present in the study." }
                }}}
                """, "CDISC-CG0368");
    }


    /** Reads DM on every dataset it runs on, so an absent DM skips it everywhere. */
    private static Rule dmDependentRule() throws Exception
    {
        return one("""
                {"rules": {"DEP-1": {
                  "Core": { "Id": "DEP-1", "Status": "Published" },
                  "Sensitivity": "Record",
                  "Description": "AGE must match DM.AGE",
                  "Match_Datasets": [ { "Name": "DM", "Keys": ["STUDYID"] } ],
                  "Check": { "expression": "not empty(AGE) and AGE != DM.AGE" },
                  "Outcome": { "Message": "AGE does not match DM.AGE",
                               "Output_Variables": ["AGE"] }
                }}}
                """, "DEP-1");
    }


    /**
     * Scoped to a domain that is not in the run at all — so it is filtered out at generation time
     * on every dataset and never reaches the runner. Channel 2 in isolation.
     */
    private static Rule lbOnlyRule() throws Exception
    {
        return one("""
                {"rules": {"SCOPE-LB": {
                  "Core": { "Id": "SCOPE-LB", "Status": "Published" },
                  "Sensitivity": "Record",
                  "Description": "LBSTRESC must be populated",
                  "Scope": { "Domains": { "Include": ["LB"] } },
                  "Check": { "expression": "empty(LBSTRESC)" },
                  "Outcome": { "Message": "LBSTRESC is empty",
                               "Output_Variables": ["LBSTRESC"] }
                }}}
                """, "SCOPE-LB");
    }


    /**
     * Scoped to AE, in a run carrying AE <em>and</em> VS: generation-time skipped on VS, executed
     * (and clean) on AE. The partial case — the one that must NOT roll up to {@code SKIPPED}.
     */
    private static Rule aeOnlyRule() throws Exception
    {
        return one("""
                {"rules": {"SCOPE-AE": {
                  "Core": { "Id": "SCOPE-AE", "Status": "Published" },
                  "Sensitivity": "Record",
                  "Description": "STUDYID must be empty (never true here)",
                  "Scope": { "Domains": { "Include": ["AE"] } },
                  "Check": { "expression": "empty(STUDYID)" },
                  "Outcome": { "Message": "STUDYID is empty",
                               "Output_Variables": ["STUDYID"] }
                }}}
                """, "SCOPE-AE");
    }


    /** Scoped to AE and guaranteed to fire there — pins that a finding still beats a skip. */
    private static Rule aeFiringRule() throws Exception
    {
        return one("""
                {"rules": {"FIRE-AE": {
                  "Core": { "Id": "FIRE-AE", "Status": "Published" },
                  "Sensitivity": "Record",
                  "Description": "STUDYID must not be populated",
                  "Scope": { "Domains": { "Include": ["AE"] } },
                  "Check": { "expression": "not empty(STUDYID)" },
                  "Outcome": { "Message": "STUDYID is populated",
                               "Output_Variables": ["STUDYID"] }
                }}}
                """, "FIRE-AE");
    }


    private static Rule one(String json, String id) throws Exception
    {
        RulePackage pkg = RulePackageLoader.loadFromString(json);
        return pkg.getRules().get(id);
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
                .table(TestMetadataFixtures.table("VS").label("Vital Signs").className("Findings")
                        .column(TestMetadataFixtures.column("STUDYID", 0, DataValueType.STRING)
                                .label("Study Identifier").core("Req").role("Identifier").build())
                        .build())
                .build();
        return new MetadataLibraryProvider(lib);
    }


    private static IDataTable ae()
    {
        return MockTable.of().name("AE").col("STUDYID", "S1", "S1").col("AGE", "31", "32").build();
    }


    private static IDataTable vs()
    {
        return MockTable.of().name("VS").col("STUDYID", "S1").col("VSTESTCD", "HR").build();
    }


    private static String statusOf(ValidationReport report, List<Rule> rules, String coreId)
    {
        Map<String, Object> export = new ReportAssembler().report(report).rules(rules).sections()
                .toExportDocument();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) export.get("Rules_Report");
        for (Map<String, Object> row : rows)
        {
            if (coreId.equals(row.get("core_id")))
            {
                return String.valueOf(row.get(STATUS));
            }
        }
        return "<no row for " + coreId + ">";
    }


    @Test
    void executionTimeSkipOnEveryDatasetReportsSkipped() throws Exception
    {
        // Channel 1. DM is absent and its absence is reported by the presence rule, so DEP-1's
        // whole Check collapses on the only dataset it reaches.
        List<Rule> rules = List.of(dmPresenceRule(), dmDependentRule());
        ValidationReport report = LibraryValidator.builder().provider(provider()).rules(rules)
                .libraryUri("file:///study").targetDataset("AE", "ae.json", ae()).build()
                .validate();

        assertTrue(report.getSkippedRules().stream().anyMatch(e -> "DEP-1".equals(e.getCoreId())),
                "precondition: DEP-1 must actually be skipped");
        assertEquals(List.of(),
                report.getExecutedCoreIds().stream().filter("DEP-1"::equals).toList(),
                "precondition: DEP-1 must not be recorded as executed anywhere");

        assertEquals("SKIPPED", statusOf(report, rules, "DEP-1"));
    }


    @Test
    void generationTimeOnlySkipReportsSkipped() throws Exception
    {
        // Channel 2 in isolation: SCOPE-LB never reaches the runner on any dataset of this run.
        List<Rule> rules = List.of(lbOnlyRule());
        ValidationReport report = LibraryValidator.builder().provider(provider()).rules(rules)
                .libraryUri("file:///study").targetDataset("AE", "ae.json", ae())
                .targetDataset("VS", "vs.json", vs()).build().validate();

        assertEquals(2, report.getSkippedRules().stream()
                .filter(e -> "SCOPE-LB".equals(e.getCoreId())).count(),
                "one generation-time skip per dataset");
        assertEquals("SKIPPED", statusOf(report, rules, "SCOPE-LB"));
    }


    @Test
    void partialSkipReportsSuccessAndKeepsTheSkipRow() throws Exception
    {
        // SCOPE-AE runs cleanly on AE and is generation-time skipped on VS ⇒ it DID run.
        List<Rule> rules = List.of(aeOnlyRule());
        ValidationReport report = LibraryValidator.builder().provider(provider()).rules(rules)
                .libraryUri("file:///study").targetDataset("AE", "ae.json", ae())
                .targetDataset("VS", "vs.json", vs()).build().validate();

        assertTrue(report.getExecutedCoreIds().contains("SCOPE-AE"),
                "precondition: SCOPE-AE ran on AE");
        assertEquals(List.of("VS"),
                report.getSkippedRules().stream().filter(e -> "SCOPE-AE".equals(e.getCoreId()))
                        .map(e -> e.getDataset()).toList(),
                "precondition: SCOPE-AE was skipped on VS only");

        assertEquals("SUCCESS", statusOf(report, rules, "SCOPE-AE"),
                "a rule that ran somewhere is never SKIPPED");
    }


    @Test
    void aFindingStillBeatsASkipOnARealRun() throws Exception
    {
        List<Rule> rules = List.of(aeFiringRule());
        ValidationReport report = LibraryValidator.builder().provider(provider()).rules(rules)
                .libraryUri("file:///study").targetDataset("AE", "ae.json", ae())
                .targetDataset("VS", "vs.json", vs()).build().validate();

        assertEquals("ISSUE_REPORTED", statusOf(report, rules, "FIRE-AE"));
    }

}
