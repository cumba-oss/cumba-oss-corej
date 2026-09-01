package net.cumba.cdisc.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.core.exec.MockTable;
import net.cumba.cdisc.core.metadata.MetadataKeys;
import net.cumba.cdisc.core.metadata.MetadataLibraryProvider;
import net.cumba.cdisc.core.metadata.TestMetadataFixtures;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.cdisc.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.report.ValidationReport;
import net.cumba.datatable.report.ValidationReportMember;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * Study-sensitivity collapse for rules loaded the way <em>production</em> loads them — through
 * {@link RulePackageLoader}, so they carry no raw {@code id} at all.
 *
 * <p>
 * The pre-existing {@code LibraryValidatorStudySensitivityTest} builds its rules programmatically
 * and calls {@code setId(...)}, a condition no shipped rule meets: since the rule packages became
 * {@code Core.Id}-keyed, the {@code id} member was dropped entirely. The collapse grouped on that
 * {@code null}, so every {@code Sensitivity: Study} rule silently fell through and reported once
 * per dataset. These tests pin the loader-backed path that the programmatic fixture could not
 * reach.
 * </p>
 */
class LibraryValidatorLoadedStudyRuleTest
{

    private static final String MESSAGE = "DM dataset is missing";

    /** A study-level absence check, exactly as the shipped packages express it. */
    private static Rule loadedStudyRule() throws Exception
    {
        String json = """
                {
                  "rules": {
                    "CORE-STUDY-LOADED": {
                      "Core": { "Id": "CORE-STUDY-LOADED", "Status": "Published" },
                      "Sensitivity": "Study",
                      "Description": "DM must be present in the study",
                      "Scope": { "Domains": { "Include": ["ALL"] } },
                      "Check": { "expression": "not ds_exists(\\"DM\\")" },
                      "Outcome": { "Message": "DM dataset is missing" }
                    }
                  }
                }
                """;
        RulePackage pkg = RulePackageLoader.loadFromString(json);
        return pkg.getRules().get("CORE-STUDY-LOADED");
    }


    private static MetadataProvider providerWithAeAndLb()
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


    /**
     * The headline regression: a loaded study rule fires ONCE for the study, not once per dataset.
     */
    @Test
    void loadedStudyRuleCollapsesToASingleStudyFinding() throws Exception
    {
        Rule rule = loadedStudyRule();
        assertNull(rule.getId(), "precondition: a loaded rule carries no raw id");
        assertEquals(Sensitivity.STUDY, rule.getSensitivity(), "precondition: Sensitivity=Study");

        ValidationReport report = LibraryValidator.builder().provider(providerWithAeAndLb())
                .rules(List.of(rule)).libraryUri("file:///study")
                .targetDataset("AE", "ae.json", table("AE"))
                .targetDataset("LB", "lb.json", table("LB")).validate();

        List<ValidationReportMember> studyMembers = report.getMembers().stream()
                .filter(m -> "STUDY".equals(m.getDomain())).toList();
        assertEquals(1, studyMembers.size(), "exactly one STUDY-labelled member");

        long findings = report.getMembers().stream().flatMap(m -> m.getFindings().stream())
                .filter(f -> MESSAGE.equals(f.getMessage())).count();
        assertEquals(1, findings, "one finding for the study — not one per dataset");
    }


    /**
     * Two distinct loaded study rules must collapse independently — the grouping key has to
     * separate them, which a shared {@code null} id could not.
     */
    @Test
    void twoLoadedStudyRulesCollapseIndependently() throws Exception
    {
        String json = """
                {
                  "rules": {
                    "CORE-STUDY-A": {
                      "Core": { "Id": "CORE-STUDY-A" },
                      "Sensitivity": "Study",
                      "Check": { "expression": "not ds_exists(\\"DM\\")" },
                      "Outcome": { "Message": "DM missing" }
                    },
                    "CORE-STUDY-B": {
                      "Core": { "Id": "CORE-STUDY-B" },
                      "Sensitivity": "Study",
                      "Check": { "expression": "not ds_exists(\\"TS\\")" },
                      "Outcome": { "Message": "TS missing" }
                    }
                  }
                }
                """;
        RulePackage pkg = RulePackageLoader.loadFromString(json);

        ValidationReport report = LibraryValidator.builder().provider(providerWithAeAndLb())
                .rules(List.copyOf(pkg.getRules().values())).libraryUri("file:///study")
                .targetDataset("AE", "ae.json", table("AE"))
                .targetDataset("LB", "lb.json", table("LB")).validate();

        List<String> messages = report.getMembers().stream()
                .filter(m -> "STUDY".equals(m.getDomain())).flatMap(m -> m.getFindings().stream())
                .map(f -> f.getMessage()).sorted().toList();
        assertEquals(List.of("DM missing", "TS missing"), messages,
                "each study rule collapses to its own single finding");
    }


    /**
     * Two rule packages supplied together (e.g. {@code --rules-file A --rules-file B} for two
     * SDTMIG versions of the same family) can carry the same {@code Core.Id} twice. Grouping on
     * {@link Rule#effectiveId()} deliberately merges them into a single study finding rather than
     * reporting the same study fact twice.
     */
    @Test
    void twoRuleObjectsSharingACoreIdCollapseTogether() throws Exception
    {
        String json = """
                {
                  "rules": {
                    "CORE-STUDY-DUP": {
                      "Core": { "Id": "CORE-STUDY-DUP" },
                      "Sensitivity": "Study",
                      "Check": { "expression": "not ds_exists(\\"DM\\")" },
                      "Outcome": { "Message": "DM missing" }
                    }
                  }
                }
                """;
        Rule fromPackageA = RulePackageLoader.loadFromString(json).getRules().get("CORE-STUDY-DUP");
        Rule fromPackageB = RulePackageLoader.loadFromString(json).getRules().get("CORE-STUDY-DUP");
        assertNotSame(fromPackageA, fromPackageB, "precondition: two distinct Rule objects");

        ValidationReport report = LibraryValidator.builder().provider(providerWithAeAndLb())
                .rules(List.of(fromPackageA, fromPackageB)).libraryUri("file:///study")
                .targetDataset("AE", "ae.json", table("AE"))
                .targetDataset("LB", "lb.json", table("LB")).validate();

        long findings = report.getMembers().stream().filter(m -> "STUDY".equals(m.getDomain()))
                .flatMap(m -> m.getFindings().stream())
                .filter(f -> "DM missing".equals(f.getMessage())).count();
        assertEquals(1, findings,
                "duplicate Core.Ids across packages report the study fact once, not twice");
    }


    /** A study rule that does not fire produces no STUDY finding at all. */
    @Test
    void loadedStudyRuleThatNeverFiresProducesNoStudyFinding() throws Exception
    {
        String json = """
                {
                  "rules": {
                    "CORE-STUDY-QUIET": {
                      "Core": { "Id": "CORE-STUDY-QUIET" },
                      "Sensitivity": "Study",
                      "Check": { "expression": "not ds_exists(\\"AE\\")" },
                      "Outcome": { "Message": "AE missing" }
                    }
                  }
                }
                """;
        RulePackage pkg = RulePackageLoader.loadFromString(json);

        ValidationReport report = LibraryValidator.builder().provider(providerWithAeAndLb())
                .rules(List.copyOf(pkg.getRules().values())).libraryUri("file:///study")
                .targetDataset("AE", "ae.json", table("AE"))
                .targetDataset("LB", "lb.json", table("LB")).validate();

        assertTrue(report.getMembers().stream().filter(m -> "STUDY".equals(m.getDomain()))
                .allMatch(m -> m.getFindings().isEmpty()), "AE is present, so the rule is silent");
    }
}
