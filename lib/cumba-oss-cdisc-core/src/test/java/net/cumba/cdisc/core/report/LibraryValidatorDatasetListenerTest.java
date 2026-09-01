package net.cumba.cdisc.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.core.exec.MockTable;
import net.cumba.cdisc.core.metadata.MetadataKeys;
import net.cumba.cdisc.core.metadata.MetadataLibraryProvider;
import net.cumba.cdisc.core.metadata.TestMetadataFixtures;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.report.ValidationFinding;
import net.cumba.datatable.report.ValidationReport;
import net.cumba.datatable.report.ValidationReportMember;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * Verifies the live {@link LibraryValidator.DatasetListener} hook: it fires once per target dataset
 * as that dataset finishes (sequential mode in target order; parallel mode in completion order),
 * and the sum of the per-dataset finding counts it reports converges to the report's total finding
 * count ({@code countFindings} semantics).
 */
class LibraryValidatorDatasetListenerTest
{

    private record Completion(int processed, int total, String domain, int findings)
    {
    }

    @Test
    void sequential_firesOncePerDatasetWithMonotonicCount()
    {
        List<Completion> seen = new ArrayList<>();
        ValidationReport report = buildValidator(true,
                (p, t, d, f) -> seen.add(new Completion(p, t, d, f))).validate();

        assertEquals(2, seen.size(), "one completion per target dataset");
        // Sequential mode increments the completion counter on the calling thread, so it is a
        // strict 1, 2, … (the domain order follows the validator's dataset iteration order).
        assertEquals(List.of(1, 2), seen.stream().map(Completion::processed).toList());
        assertEquals(Set.of("DM", "AE"),
                seen.stream().map(Completion::domain).collect(java.util.stream.Collectors.toSet()));
        assertTrue(seen.stream().allMatch(c -> c.total() == 2));
        assertEquals(ruleViolationRows(report), seen.stream().mapToInt(Completion::findings).sum(),
                "running per-dataset tally equals the report's rule-violation rows");
    }


    @RepeatedTest(3)
    void parallel_firesOncePerDatasetWithMonotonicCount()
    {
        List<Completion> seen = new CopyOnWriteArrayList<>();
        ValidationReport report = buildValidator(false,
                (p, t, d, f) -> seen.add(new Completion(p, t, d, f))).validate();

        assertEquals(2, seen.size(), "one completion per target dataset");
        assertEquals(Set.of("DM", "AE"),
                seen.stream().map(Completion::domain).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(1, 2),
                seen.stream().map(Completion::processed)
                        .collect(java.util.stream.Collectors.toSet()),
                "completion counter covers 1..N in completion order");
        assertTrue(seen.stream().allMatch(c -> c.total() == 2));
        assertEquals(ruleViolationRows(report), seen.stream().mapToInt(Completion::findings).sum());
    }


    private static LibraryValidator.Builder buildValidator(boolean sequential,
            LibraryValidator.DatasetListener listener)
    {
        return LibraryValidator.builder().provider(provider()).rules(emptyRulePackage())
                .libraryUri("file:///study/define.xml").targetDataset("DM", "dm.xpt", dmTable())
                .targetDataset("AE", "ae.xpt", aeTable()).sequential(sequential)
                .datasetListener(listener);
    }


    private static MetadataProvider provider()
    {
        IMetadataLibrary lib = TestMetadataFixtures.lib("study")
                .meta(MetadataKeys.STANDARD_NAME, "sdtmig")
                .meta(MetadataKeys.STANDARD_VERSION, "3-4")
                .table(TestMetadataFixtures.table("DM").label("Demographics")
                        .className("Special-Purpose").structure("One record per subject")
                        .column(TestMetadataFixtures.column("STUDYID", 0, DataValueType.STRING)
                                .label("Study Identifier").core("Req").role("Identifier").build())
                        .column(TestMetadataFixtures.column("USUBJID", 1, DataValueType.STRING)
                                .label("Unique Subject Identifier").core("Req").role("Identifier")
                                .build())
                        .column(TestMetadataFixtures.column("SEX", 2, DataValueType.STRING)
                                .label("Sex").core("Req").role("Record Qualifier").build())
                        .build())
                .table(TestMetadataFixtures.table("AE").label("Adverse Events").className("Events")
                        .structure("One record per event per subject")
                        .column(TestMetadataFixtures.column("STUDYID", 0, DataValueType.STRING)
                                .label("Study Identifier").core("Req").role("Identifier").build())
                        .column(TestMetadataFixtures.column("USUBJID", 1, DataValueType.STRING)
                                .label("Unique Subject Identifier").core("Req").role("Identifier")
                                .build())
                        .column(TestMetadataFixtures.column("AETERM", 2, DataValueType.STRING)
                                .label("Reported Term").core("Req").role("Topic").build())
                        .build())
                .build();
        return new MetadataLibraryProvider(lib);
    }


    private static RulePackage emptyRulePackage()
    {
        RulePackage pkg = new RulePackage();
        pkg.setRules(new java.util.HashMap<>());
        return pkg;
    }


    private static IDataTable dmTable()
    {
        return MockTable.of().name("DM").col("STUDYID", "STUDY1", "STUDY1", "STUDY1")
                .col("USUBJID", "SUBJ-001", "SUBJ-002", "SUBJ-003").col("SEX", "M", "F", "U")
                .build();
    }


    private static IDataTable aeTable()
    {
        return MockTable.of().name("AE").col("STUDYID", "STUDY1", "STUDY1")
                .col("USUBJID", "SUBJ-001", "SUBJ-002").col("AETERM", "HEADACHE", "NAUSEA").build();
    }


    /**
     * Total rule-violation rows in the report — the exact quantity the per-dataset listener sums
     * (engine-error / library-warning pseudo-findings are excluded, as they carry no violations).
     */
    private static int ruleViolationRows(ValidationReport report)
    {
        int total = 0;
        for (ValidationReportMember m : report.getMembers())
        {
            for (ValidationFinding f : m.getFindings())
            {
                if (f.getKind() == net.cumba.datatable.report.FindingKind.RULE_VIOLATION)
                {
                    total += f.getRowCount();
                }
            }
        }
        return total;
    }
}
