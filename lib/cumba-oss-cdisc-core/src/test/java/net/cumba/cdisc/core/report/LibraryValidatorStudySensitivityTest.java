package net.cumba.cdisc.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.TextNode;
import java.util.List;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.core.exec.MockTable;
import net.cumba.cdisc.core.metadata.MetadataLibraryProvider;
import net.cumba.cdisc.core.metadata.TestMetadataFixtures;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.cdisc.core.model.Outcome;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RuleCore;
import net.cumba.cdisc.core.model.Sensitivity;
import net.cumba.cdisc.core.run.DatasetExecutionSummary;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.report.SkippedRuleEntry;
import net.cumba.datatable.report.ValidationReport;
import net.cumba.datatable.report.ValidationReportMember;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * Study-sensitivity collapse in {@link LibraryValidator} — the Java mirror of the Python reference
 * engine's {@code _collapse_to_study_result} ({@code rules_engine.py}). A {@code Sensitivity=Study}
 * rule executes per dataset (so dataset-scoped operands resolve) but yields exactly ONE
 * representative finding for the whole study, relabelled to the synthetic {@code STUDY} dataset.
 */
class LibraryValidatorStudySensitivityTest
{

    private static final String STUDY_MESSAGE = "study-wide non-conformance";

    /** Provider that knows both DM (Special-Purpose) and AE (Events) so both datasets execute. */
    private static MetadataProvider providerWithDmAndAe()
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
                        .build())
                .table(TestMetadataFixtures.table("AE").label("Adverse Events").className("Events")
                        .structure("One record per event")
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


    private static IDataTable dmFiring()
    {
        return MockTable.of().name("DM").col("STUDYID", "S1", "S1").col("USUBJID", "U1", "U2")
                .col("VAL", "BAD", "BAD").build();
    }


    private static IDataTable aeFiring()
    {
        return MockTable.of().name("AE").col("STUDYID", "S1", "S1").col("USUBJID", "U1", "U2")
                .col("AETERM", "X", "Y").col("VAL", "BAD", "BAD").build();
    }


    /** A Sensitivity=Study rule with a plain per-row check that fires on any VAL=="BAD" row. */
    private static Rule studyRuleFiring()
    {
        Rule r = new Rule();
        r.setId("uuid-CORE-STUDY-FIRE");
        RuleCore core = new RuleCore();
        core.setId("CORE-STUDY-FIRE");
        r.setCore(core);
        r.setSensitivity(Sensitivity.STUDY);
        Outcome outcome = new Outcome();
        outcome.setMessage(STUDY_MESSAGE);
        outcome.setOutputVariables(List.of("USUBJID"));
        r.setOutcome(outcome);
        r.setCheck(new CheckConditionAll(
                List.of(CheckConditionLeaf.builder().name("VAL").operator("equal_to")
                        .value(TextNode.valueOf("BAD")).valueIsLiteral(true).build())));
        return r;
    }


    @Test
    void studyRuleFiringOnMultipleDatasetsCollapsesToOneStudyFinding()
    {
        ValidationReport report = LibraryValidator.builder().provider(providerWithDmAndAe())
                .rules(List.of(studyRuleFiring())).libraryUri("file:///study/dm.xpt")
                .targetDataset("DM", "dm.xpt", dmFiring()).targetDataset("AE", "ae.xpt", aeFiring())
                .validate();

        // Exactly one report member is labelled STUDY, and it carries the collapsed finding.
        List<ValidationReportMember> studyMembers = report.getMembers().stream()
                .filter(m -> "STUDY".equals(m.getDomain())).toList();
        assertEquals(1, studyMembers.size(), "study rule collapses to a single STUDY member");
        assertTrue(
                studyMembers.get(0).getFindings().stream()
                        .anyMatch(f -> STUDY_MESSAGE.equals(f.getMessage())),
                "the STUDY member carries the representative finding");

        // The per-dataset DM / AE members must NOT carry the study rule's finding — it was
        // collapsed away, not duplicated per dataset.
        assertFalse(
                report.getMembers().stream()
                        .filter(m -> "DM".equals(m.getDomain()) || "AE".equals(m.getDomain()))
                        .flatMap(m -> m.getFindings().stream())
                        .anyMatch(f -> STUDY_MESSAGE.equals(f.getMessage())),
                "no per-dataset copy of the study finding survives the collapse");
    }


    @Test
    void studyRuleAppearsExactlyOnceInExecutionSummariesUnderStudy()
    {
        LibraryValidator validator = LibraryValidator.builder().provider(providerWithDmAndAe())
                .rules(List.of(studyRuleFiring())).libraryUri("file:///study/dm.xpt")
                .targetDataset("DM", "dm.xpt", dmFiring()).targetDataset("AE", "ae.xpt", aeFiring())
                .build();
        validator.validate();

        List<DatasetExecutionSummary> summaries = validator.getExecutionSummaries();

        // Exactly one synthetic STUDY summary row, carrying the study rule's single execution.
        List<DatasetExecutionSummary> studyRows = summaries.stream()
                .filter(s -> "STUDY".equals(s.domain())).toList();
        assertEquals(1, studyRows.size(), "one synthetic STUDY execution summary row");
        DatasetExecutionSummary studyRow = studyRows.get(0);

        List<DatasetExecutionSummary.RuleExecution> studyRuleRows = studyRow.ruleExecutions()
                .stream().filter(rx -> "CORE-STUDY-FIRE".equals(rx.coreId())).toList();
        assertEquals(1, studyRuleRows.size(), "the study rule is accounted for once, under STUDY");
        assertEquals("EXECUTED", studyRuleRows.get(0).status());

        // The study rule appears in NO per-dataset (DM / AE) summary — it was collapsed out.
        assertFalse(
                summaries.stream().filter(s -> !"STUDY".equals(s.domain()))
                        .flatMap(s -> s.ruleExecutions().stream())
                        .anyMatch(rx -> "CORE-STUDY-FIRE".equals(rx.coreId())),
                "study rule must not appear on any per-dataset execution summary");

        // rulesTotal reconciles: every summary row (including STUDY) reports the same run-wide
        // selected-rule total, and the study rule is accounted for exactly once across all rows.
        long studyRuleAccountings = summaries.stream().flatMap(s -> s.ruleExecutions().stream())
                .filter(rx -> "CORE-STUDY-FIRE".equals(rx.coreId())).count();
        assertEquals(1, studyRuleAccountings,
                "study rule is accounted for exactly once across all execution summaries");
    }


    @Test
    void studyRuleSkippedOnEveryDatasetYieldsOneSkippedStudyResult()
    {
        // A define-dependent operation with no Define provider SKIPs the rule on every dataset.
        Operation defineOp = new Operation();
        defineOp.setId("$define_dataset_names");
        defineOp.setOperator("define_dataset_names");

        Rule r = new Rule();
        r.setId("uuid-CORE-STUDY-SKIP");
        RuleCore core = new RuleCore();
        core.setId("CORE-STUDY-SKIP");
        r.setCore(core);
        r.setSensitivity(Sensitivity.STUDY);
        Outcome outcome = new Outcome();
        outcome.setMessage("never fires (skipped)");
        outcome.setOutputVariables(List.of("STUDYID"));
        r.setOutcome(outcome);
        r.setOperations(List.of(defineOp));
        r.setCheck(new CheckConditionAll(
                List.of(CheckConditionLeaf.builder().name("STUDYID").operator("is_contained_by")
                        .value(TextNode.valueOf("$define_dataset_names")).build())));

        // No .defineProvider(...) → the define-dependent rule is SKIPPED on both datasets.
        ValidationReport report = LibraryValidator.builder().provider(providerWithDmAndAe())
                .rules(List.of(r)).libraryUri("file:///study/dm.xpt")
                .targetDataset("DM", "dm.xpt", dmFiring()).targetDataset("AE", "ae.xpt", aeFiring())
                .validate();

        // Exactly one collapsed SKIPPED result, labelled STUDY — not one per dataset.
        List<SkippedRuleEntry> studySkips = report.getSkippedRules().stream()
                .filter(s -> "CORE-STUDY-SKIP".equals(s.getCoreId())).toList();
        assertEquals(1, studySkips.size(),
                "an all-skipped study rule yields exactly one skipped study result");
        assertEquals("STUDY", studySkips.get(0).getDataset(),
                "the collapsed skipped result is labelled STUDY");

        // And no STUDY finding member is emitted for a skipped rule.
        assertTrue(
                report.getMembers().stream().filter(m -> "STUDY".equals(m.getDomain()))
                        .allMatch(m -> m.getFindings().isEmpty()),
                "a skipped study rule produces no finding");
    }
}
