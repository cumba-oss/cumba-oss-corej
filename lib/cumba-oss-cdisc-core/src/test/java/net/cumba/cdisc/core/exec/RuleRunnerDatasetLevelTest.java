package net.cumba.cdisc.core.exec;

import static net.cumba.datatable.testkit.TestMetadataFixtures.column;
import static net.cumba.datatable.testkit.TestMetadataFixtures.lib;
import static net.cumba.datatable.testkit.TestMetadataFixtures.table;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import net.cumba.cdisc.core.metadata.MetadataLibraryProvider;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.Outcome;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RuleCore;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.DataValueType;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 of PLAN-coreJ-cdisc-provider — the dataset-level three-level operands
 * ({@code dataset_label} / {@code define_dataset_label} / {@code library_dataset_label}). Built by
 * hand because the consuming rule (compare a dataset label across the data / define / library
 * levels) is planned but not yet in any package.
 */
class RuleRunnerDatasetLevelTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A {@code DATASET_METADATA_CHECK}: {@code define_dataset_label != library_dataset_label}. */
    private static Rule defineVsLibraryLabelRule()
    {
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("define_dataset_label")
                .operator("not_equal_to").value(MAPPER.valueToTree("library_dataset_label"))
                .build();
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-DATASET-LABEL");
        rule.setCore(core);
        rule.setCheck(new CheckConditionAll(List.of(leaf)));
        Outcome outcome = new Outcome();
        outcome.setMessage("dataset label differs between define and library");
        rule.setOutcome(outcome);
        net.cumba.cdisc.core.RulePackageLoader.installNativeExpr(rule);
        return rule;
    }


    private static MetadataProvider providerWithDatasetLabel(String label)
    {
        IMetadataLibrary l = lib("x")
                .table(table("DM").label(label)
                        .column(column("STUDYID", 0, DataValueType.STRING).build()).build())
                .build();
        return MetadataLibraryProvider.forDefine(l);
    }


    private static IDataTable dmTable()
    {
        return MockTable.of().name("DM").col("STUDYID", "S001").build();
    }


    private static RuleExecutionResult run(@Nullable MetadataProvider library,
            @Nullable MetadataProvider define)
    {
        return RuleRunner.execute(defineVsLibraryLabelRule(), dmTable(), _ -> null, "DM", library,
                null, define);
    }


    @Test
    void defineLabelDiffersFromLibrary_violation()
    {
        assertTrue(
                run(providerWithDatasetLabel("Subject Demographics"),
                        providerWithDatasetLabel("Demographics")).hasViolations(),
                "define_dataset_label != library_dataset_label -> violation");
    }


    @Test
    void defineLabelMatchesLibrary_noViolation()
    {
        assertFalse(
                run(providerWithDatasetLabel("Demographics"),
                        providerWithDatasetLabel("Demographics")).hasViolations(),
                "define_dataset_label == library_dataset_label -> no violation");
    }


    @Test
    void noDefineProvider_skipped()
    {
        assertTrue(run(providerWithDatasetLabel("Demographics"), null).isSkipped(),
                "no define provider -> SKIPPED (define_dataset_label required)");
    }
}
