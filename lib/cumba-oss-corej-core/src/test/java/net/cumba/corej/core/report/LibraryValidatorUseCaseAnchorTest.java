package net.cumba.corej.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.metadata.MetadataKeys;
import net.cumba.corej.core.metadata.MetadataLibraryProvider;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.report.SkippedRuleEntry;
import net.cumba.datatable.report.ValidationReport;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.testkit.TestMetadataFixtures;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * The study-anchor arm of the {@code Scope.Use_Case} filter (owner ruling X1,
 * {@code plans/PLAN-use-case-scope-filter.md}), driven through {@link LibraryValidator} directly.
 *
 * <p>
 * An anchor-eligible study rule is executed once against the synthetic {@code STUDY} anchor and is
 * removed from the per-dataset path, so it never reaches {@code DatasetRuleResolver}'s scope gate.
 * A filter living only in that gate would therefore miss exactly these rules. The validator keeps
 * an out-of-use-case rule out of the anchor pass, and the per-dataset gate then reports it
 * {@code SKIPPED} on every target dataset.
 * </p>
 */
class LibraryValidatorUseCaseAnchorTest
{

    private static final String MESSAGE = "DM dataset is missing";

    private static final String ID = "CORE-STUDY-UC";

    /** A study-level absence check scoped to the INDH use case. */
    private static Rule studyRule() throws Exception
    {
        String json = """
                {
                  "rules": {
                    "CORE-STUDY-UC": {
                      "Core": { "Id": "CORE-STUDY-UC", "Status": "Published" },
                      "Sensitivity": "Study",
                      "Scope": { "Domains": { "Include": ["ALL"] }, "Use_Case": "INDH" },
                      "Check": { "expression": "not ds_exists(\\"DM\\")" },
                      "Outcome": { "Message": "DM dataset is missing" }
                    }
                  }
                }
                """;
        return RulePackageLoader.loadFromString(json).getRules().get(ID);
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


    private static ValidationReport validate(String useCase) throws Exception
    {
        return LibraryValidator.builder().provider(provider()).rules(List.of(studyRule()))
                .useCase(useCase).targetDataset("AE", "ae.json", table("AE"))
                .targetDataset("LB", "lb.json", table("LB")).validate();
    }


    private static long studyFindings(ValidationReport report)
    {
        return report.getMembers().stream().filter(m -> "STUDY".equals(m.getDomain()))
                .flatMap(m -> m.getFindings().stream()).filter(f -> MESSAGE.equals(f.getMessage()))
                .count();
    }


    @Test
    void outsideTheUseCase_theAnchorRuleIsNotExecuted_andIsSkippedOnEveryDataset() throws Exception
    {
        ValidationReport report = validate("NONCLIN");

        assertEquals(0, studyFindings(report), "the out-of-use-case anchor rule must not fire");
        List<SkippedRuleEntry> skipped = report.getSkippedRules().stream()
                .filter(e -> ID.equals(e.getCoreId())).toList();
        assertEquals(2, skipped.size(), () -> "one SKIPPED row per target dataset: " + skipped);
        for (SkippedRuleEntry e : skipped)
        {
            assertEquals("use case NONCLIN not in Scope.Use_Case [INDH]", e.getReason());
        }
        assertEquals(List.of("AE", "LB"),
                skipped.stream().map(SkippedRuleEntry::getDataset).sorted().toList());
    }


    @Test
    void inTheUseCase_orWithNone_theAnchorRuleFiresOnce() throws Exception
    {
        for (String useCase : new String[]
        {
                "indh", " INDH ", null, "   "
        })
        {
            ValidationReport report = validate(useCase);
            assertEquals(1, studyFindings(report), "use case '" + useCase + "'");
            assertTrue(report.getSkippedRules().isEmpty(),
                    () -> "use case '" + useCase + "': " + report.getSkippedRules());
        }
    }
}
