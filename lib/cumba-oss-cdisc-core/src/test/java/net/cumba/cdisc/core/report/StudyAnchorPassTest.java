package net.cumba.cdisc.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.core.exec.MockTable;
import net.cumba.cdisc.core.metadata.MetadataKeys;
import net.cumba.cdisc.core.metadata.MetadataLibraryProvider;
import net.cumba.cdisc.core.metadata.TestMetadataFixtures;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.cdisc.core.run.DatasetExecutionSummary;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.report.ValidationReport;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * The study-anchor execution path: an anchor-eligible {@code Sensitivity: Study} rule runs
 * <em>once</em> against a synthetic study anchor rather than once per dataset.
 *
 * <p>
 * Two properties matter and are tested separately. <b>Equivalence</b> — the anchor pass must reach
 * the same verdict as the per-dataset path it replaces, which is what makes default-on safe; this
 * is asserted by running the same rules under both paths via the {@code corej.studyAnchorPass}
 * kill-switch. <b>Reach</b> — the anchor pass sits outside the per-dataset loop, so it still fires
 * when the study has no analysable datasets at all, which the per-dataset path structurally cannot.
 * </p>
 */
class StudyAnchorPassTest
{

    private static final String SWITCH = "corej.studyAnchorPass";

    /** The migration's real shapes, as the shipped packages express them. */
    private static List<Rule> studyRules() throws Exception
    {
        String json = """
                {
                  "rules": {
                    "STUDY-DM": {
                      "Core": { "Id": "STUDY-DM" },
                      "Sensitivity": "Study",
                      "Scope": { "Domains": { "Include": ["ALL"] } },
                      "Check": { "expression": "not ds_exists(\\"DM\\")" },
                      "Outcome": { "Message": "DM missing" }
                    },
                    "STUDY-TS": {
                      "Core": { "Id": "STUDY-TS" },
                      "Sensitivity": "Study",
                      "Scope": { "Domains": { "Include": ["ALL"] } },
                      "Check": { "expression": "not ds_exists(\\"TS\\")" },
                      "Outcome": { "Message": "TS missing" }
                    },
                    "STUDY-QUIET": {
                      "Core": { "Id": "STUDY-QUIET" },
                      "Sensitivity": "Study",
                      "Scope": { "Domains": { "Include": ["ALL"] } },
                      "Check": { "expression": "not ds_exists(\\"AE\\")" },
                      "Outcome": { "Message": "AE missing" }
                    }
                  }
                }
                """;
        RulePackage pkg = RulePackageLoader.loadFromString(json);
        return List.copyOf(pkg.getRules().values());
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


    /** Messages of every STUDY-labelled finding, sorted — the run's study-level verdict. */
    private static List<String> studyFindings(ValidationReport report)
    {
        return report.getMembers().stream().filter(m -> "STUDY".equals(m.getDomain()))
                .flatMap(m -> m.getFindings().stream()).map(f -> f.getMessage()).sorted().toList();
    }


    private static ValidationReport validateFullStudy() throws Exception
    {
        return LibraryValidator.builder().provider(provider()).rules(studyRules())
                .libraryUri("file:///study").targetDataset("AE", "ae.json", table("AE"))
                .targetDataset("LB", "lb.json", table("LB")).validate();
    }


    /**
     * Runs {@code body} with the anchor pass forced off, restoring the previous property value
     * afterwards so test ordering cannot leak the switch.
     */
    private static <T> T withAnchorPassDisabled(java.util.concurrent.Callable<T> body)
        throws Exception
    {
        String previous = System.getProperty(SWITCH);
        System.setProperty(SWITCH, "false");
        try
        {
            return body.call();
        }
        finally
        {
            if (previous == null)
            {
                System.clearProperty(SWITCH);
            }
            else
            {
                System.setProperty(SWITCH, previous);
            }
        }
    }

    // ---- 3.e equivalence ------------------------------------------------------------------


    /**
     * The load-bearing test: the anchor pass and the per-dataset collapse must produce the same
     * study-level findings. This is the evidence that defaulting the fast path on is safe.
     */
    @Test
    void anchorPassAndCollapsePathAgreeOnTheStudyVerdict() throws Exception
    {
        List<String> viaAnchor = studyFindings(validateFullStudy());
        List<String> viaCollapse = withAnchorPassDisabled(() -> studyFindings(validateFullStudy()));

        assertEquals(viaCollapse, viaAnchor, "the fast path must not change any verdict; collapse="
                + viaCollapse + " anchor=" + viaAnchor);
        assertEquals(List.of("DM missing", "TS missing"), viaAnchor,
                "both paths report exactly the two absent datasets, once each");
    }


    /** Each study rule is still accounted for exactly once, under STUDY, on the anchor path. */
    @Test
    void anchorPassAccountsForEachStudyRuleExactlyOnce() throws Exception
    {
        LibraryValidator validator = LibraryValidator.builder().provider(provider())
                .rules(studyRules()).libraryUri("file:///study")
                .targetDataset("AE", "ae.json", table("AE"))
                .targetDataset("LB", "lb.json", table("LB")).build();
        validator.validate();

        List<DatasetExecutionSummary> summaries = validator.getExecutionSummaries();
        for (String id : List.of("STUDY-DM", "STUDY-TS", "STUDY-QUIET"))
        {
            assertEquals(1,
                    summaries.stream().flatMap(s -> s.ruleExecutions().stream())
                            .filter(rx -> id.equals(rx.coreId())).count(),
                    id + " is accounted for exactly once across all execution summaries");
        }
        assertTrue(
                summaries.stream().filter(s -> !"STUDY".equals(s.domain()))
                        .flatMap(s -> s.ruleExecutions().stream())
                        .noneMatch(rx -> String.valueOf(rx.coreId()).startsWith("STUDY-")),
                "no study rule appears on a per-dataset execution summary");
    }

    // ---- 3.c zero-dataset reach -----------------------------------------------------------


    /**
     * Q4: a study fact must be reported even when the submission contains no analysable datasets.
     * The per-dataset path structurally cannot do this — with no datasets, nothing runs.
     */
    @Test
    void studyRulesFireWhenNoDatasetIsValidated() throws Exception
    {
        ValidationReport report = LibraryValidator.builder().provider(provider())
                .rules(studyRules()).libraryUri("file:///study").validate();

        assertEquals(List.of("AE missing", "DM missing", "TS missing"), studyFindings(report),
                "with an empty study every absence assertion fires");
    }


    /** And the old path genuinely cannot — the contrast that motivates the anchor pass. */
    @Test
    void theCollapsePathReportsNothingOnAnEmptyStudy() throws Exception
    {
        List<String> viaCollapse = withAnchorPassDisabled(
                () -> studyFindings(LibraryValidator.builder().provider(provider())
                        .rules(studyRules()).libraryUri("file:///study").validate()));

        assertEquals(List.of(), viaCollapse,
                "with no datasets the per-dataset path runs no rules at all");
    }


    /** Every dataset failing to load is the same situation as having none. */
    @Test
    void studyRulesFireWhenEveryDatasetFailsToLoad() throws Exception
    {
        ValidationReport report = LibraryValidator.builder().provider(provider())
                .rules(studyRules()).libraryUri("file:///study")
                .targetDataset("AE", "ae.json", () ->
                {
                    throw new IllegalStateException("ae.json is corrupt");
                }).validate();

        assertTrue(studyFindings(report).contains("DM missing"),
                "a study fact survives a study whose datasets all failed to open");
    }

    // ---- 3.d kill-switch -------------------------------------------------------------------


    /** With the switch off, study rules go back through the per-dataset path. */
    @Test
    void killSwitchRoutesStudyRulesBackThroughThePerDatasetPath() throws Exception
    {
        List<String> viaCollapse = withAnchorPassDisabled(() -> studyFindings(validateFullStudy()));

        assertEquals(List.of("DM missing", "TS missing"), viaCollapse,
                "the collapse path still yields one finding per study rule");
    }

    // ---- duplicate Core.Ids: the two paths must pick the same representative -----------------


    /**
     * Two rule objects sharing a {@code Core.Id} (two supplied packages, or a locally-patched copy
     * of a shipped rule) must report the study fact once — and the surviving copy must be the one
     * the collapse would have chosen. Picking simply "the first" would let a SKIPPED copy mask a
     * firing one, losing a finding the fallback path still reports.
     */
    @Test
    void aSkippedDuplicateDoesNotMaskAFiringOne() throws Exception
    {
        // Copy A is skipped (a define-dependent operation with no Define provider); copy B fires.
        String skipping = """
                {
                  "rules": {
                    "STUDY-DUP": {
                      "Core": { "Id": "STUDY-DUP" },
                      "Sensitivity": "Study",
                      "Scope": { "Domains": { "Include": ["ALL"] } },
                      "Operations": [
                        { "id": "$defined", "operator": "define_dataset_names" }
                      ],
                      "Check": { "expression": "\\"DM\\" not in $defined" },
                      "Outcome": { "Message": "DM missing" }
                    }
                  }
                }
                """;
        String firing = """
                {
                  "rules": {
                    "STUDY-DUP": {
                      "Core": { "Id": "STUDY-DUP" },
                      "Sensitivity": "Study",
                      "Scope": { "Domains": { "Include": ["ALL"] } },
                      "Check": { "expression": "not ds_exists(\\"DM\\")" },
                      "Outcome": { "Message": "DM missing" }
                    }
                  }
                }
                """;
        List<Rule> rules = new ArrayList<>();
        rules.add(RulePackageLoader.loadFromString(skipping).getRules().get("STUDY-DUP"));
        rules.add(RulePackageLoader.loadFromString(firing).getRules().get("STUDY-DUP"));

        ValidationReport report = LibraryValidator.builder().provider(provider()).rules(rules)
                .libraryUri("file:///study").targetDataset("AE", "ae.json", table("AE"))
                .targetDataset("LB", "lb.json", table("LB")).validate();

        assertEquals(List.of("DM missing"), studyFindings(report),
                "the firing copy represents the group — once");
    }

    // ---- non-eligible study rules keep the fallback -----------------------------------------


    /**
     * A study rule whose check reads the dataset under evaluation is not anchor-eligible; it must
     * still work, via the per-dataset path and the collapse.
     */
    @Test
    void nonEligibleStudyRuleStillCollapsesToOneFinding() throws Exception
    {
        String json = """
                {
                  "rules": {
                    "STUDY-LOCAL": {
                      "Core": { "Id": "STUDY-LOCAL" },
                      "Sensitivity": "Study",
                      "Scope": { "Domains": { "Include": ["ALL"] } },
                      "Check": { "expression": "STUDYID == \\"S1\\"" },
                      "Outcome": { "Message": "local study rule", "Output_Variables": ["STUDYID"] }
                    }
                  }
                }
                """;
        List<Rule> rules = new ArrayList<>(
                RulePackageLoader.loadFromString(json).getRules().values());

        ValidationReport report = LibraryValidator.builder().provider(provider()).rules(rules)
                .libraryUri("file:///study").targetDataset("AE", "ae.json", table("AE"))
                .targetDataset("LB", "lb.json", table("LB")).validate();

        assertEquals(1, studyFindings(report).stream().filter("local study rule"::equals).count(),
                "the fallback path still collapses a non-eligible study rule to one finding");
    }
}
