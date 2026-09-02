package net.cumba.cdisc.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.core.metadata.MetadataLibraryProvider;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.report.ValidationFinding;
import net.cumba.datatable.report.ValidationReport;
import net.cumba.datatable.report.ValidationReportMember;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.testkit.TestMetadataFixtures;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 verification gate: rule-level parallelism within a single dataset must produce a
 * byte-for-byte identical {@link ValidationReport} regardless of {@code ruleThreads} setting.
 *
 * <p>
 * Also covers the builder contract for {@code ruleThreads(int)}: strict {@code n >= 1}, clamp at
 * {@link Runtime#availableProcessors()}.
 */
class LibraryValidatorParallelismTest
{

    @RepeatedTest(3)
    void parallelRules_produceIdenticalReportToSequential()
    {
        // Sequential baseline.
        ValidationReport seq = buildValidator().ruleThreads(1).validate();

        // Parallel: 4 worker threads (or fewer if the test runner has <4 carriers — clamped
        // automatically by ruleThreads(int)). The same DM dataset, same rules, same provider —
        // any difference between seq and par is a parallelism bug.
        int n = Math.min(4, Runtime.getRuntime().availableProcessors());
        ValidationReport par = buildValidator().ruleThreads(n).validate();

        assertReportsEqual(seq, par);
    }


    @Test
    void ruleThreads_zero_throws()
    {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> LibraryValidator.builder().ruleThreads(0));
        assertTrue(ex.getMessage().contains("ruleThreads"));
    }


    @Test
    void ruleThreads_negative_throws()
    {
        assertThrows(IllegalArgumentException.class,
                () -> LibraryValidator.builder().ruleThreads(-1));
    }


    @Test
    void ruleThreads_aboveCpuCount_isClampedNotRejected()
    {
        // Builder must silently clamp values above availableProcessors() — no exception.
        // The validator itself is well-behaved at any clamped value, so a smoke validate()
        // confirms wiring is correct.
        int over = Runtime.getRuntime().availableProcessors() + 100;
        ValidationReport report = buildValidator().ruleThreads(over).validate();
        assertNotNull(report);
    }

    // ------------------------------------------------------------------
    // Fixture
    // ------------------------------------------------------------------


    private static LibraryValidator.Builder buildValidator()
    {
        return LibraryValidator.builder().provider(providerWithDm()).rules(emptyRulePackage())
                .libraryUri("file:///study/dm.xpt")
                // Multiple rows so per-row evaluation happens; the rule package is empty but the
                // RuleGenerator produces several rules from provider metadata (label / type /
                // required_variables / etc.) — enough to exercise the fan-out path.
                .targetDataset("DM", "dm.xpt", dmTable()).sequential(true);
    }


    private static MetadataProvider providerWithDm()
    {
        IMetadataLibrary lib = TestMetadataFixtures.lib("study")
                .meta(net.cumba.cdisc.core.metadata.MetadataKeys.STANDARD_NAME, "sdtmig")
                .meta(net.cumba.cdisc.core.metadata.MetadataKeys.STANDARD_VERSION, "3-4")
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
        return MockTable.of().name("DM").col("STUDYID", "STUDY1", "STUDY1", "STUDY1", "STUDY1")
                .col("USUBJID", "SUBJ-001", "SUBJ-002", "SUBJ-003", "SUBJ-004")
                .col("SEX", "M", "F", "U", "M").build();
    }


    private static void assertReportsEqual(ValidationReport a, ValidationReport b)
    {
        assertEquals(a.getMembers().size(), b.getMembers().size(), "member count");
        for (int i = 0; i < a.getMembers().size(); i++)
        {
            ValidationReportMember ma = a.getMembers().get(i);
            ValidationReportMember mb = b.getMembers().get(i);
            assertEquals(ma.getDomain(), mb.getDomain(), "member[" + i + "].domain");
            assertEquals(ma.getFileName(), mb.getFileName(), "member[" + i + "].fileName");

            List<ValidationFinding> fa = ma.getFindings();
            List<ValidationFinding> fb = mb.getFindings();
            assertEquals(fa.size(), fb.size(), "finding count for domain " + ma.getDomain());
            for (int j = 0; j < fa.size(); j++)
            {
                ValidationFinding ffa = fa.get(j);
                ValidationFinding ffb = fb.get(j);
                assertEquals(ffa.getRuleId(), ffb.getRuleId(),
                        "finding[" + j + "].ruleId in " + ma.getDomain());
                assertEquals(ffa.getMessage(), ffb.getMessage(),
                        "finding[" + j + "].message in " + ma.getDomain());
                assertEquals(ffa.getRowCount(), ffb.getRowCount(),
                        "finding[" + j + "].rowCount in " + ma.getDomain());
                assertEquals(ffa.getSeverity(), ffb.getSeverity(),
                        "finding[" + j + "].severity in " + ma.getDomain());
            }
        }
    }
}
