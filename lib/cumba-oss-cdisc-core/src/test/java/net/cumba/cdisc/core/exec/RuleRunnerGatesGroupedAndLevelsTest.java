package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Outcome pins for {@code RuleRunner}'s skip gates, severity stamping, load-error sentinel, the
 * grouped execution path and the multi-level (Check_Levels) machinery. Each of these decides
 * whether — and as WHAT — a rule's verdict reaches the report: a mutated gate turns a stated SKIP
 * into a silent false PASS, a mutated group anchor points the finding at the wrong records, and a
 * mutated level merge double-reports or under-counts. Every test asserts the exact statuses,
 * messages, rows and counts, with a negative twin per gate.
 */
class RuleRunnerGatesGroupedAndLevelsTest
{

    private static Rule load(String ruleJson) throws Exception
    {
        RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"R1\":" + ruleJson + "}}");
        Rule rule = pkg.getRules().get("R1");
        assertNull(rule.getLoadError(), "rule must load cleanly: " + rule.getLoadError());
        return rule;
    }


    private static RuleExecutionResult runAtInfo(Rule rule, IDataTable table)
    {
        return RuleRunner.execute(rule, table, _ -> null, "AE", null, null, null, Integer.MAX_VALUE,
                null, null, null, Set.of(), Set.of(), Severity.INFO);
    }

    // -----------------------------------------------------------------------
    // library_* operand gate (value side, `any` branch, literal carve-out)
    // -----------------------------------------------------------------------


    /**
     * A {@code library_*} operand on the VALUE side of a leaf inside an {@code any} branch trips
     * the not-available gate: without a library the rule reports SKIPPED naming the missing
     * provider; with a DEGRADED library it reports the could-not-be-consulted sentence instead —
     * two different facts the sponsor reads. A literal that merely looks like the operand
     * ({@code value_is_literal: true}) must NOT trip the gate and evaluates as data.
     */
    @Test
    void libraryOperandGateSkipsWithExactMessageAndSparesLiterals() throws Exception
    {
        Rule operand = load("{\"Core\":{\"Id\":\"R1\"},\"Sensitivity\":\"Record\","
                + "\"Check\":{\"any\":[{\"name\":\"AESEV\",\"operator\":\"equal_to\","
                + "\"value\":\"library_variable_role\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AESEV\"]}}");
        IDataTable ae = MockTable.of().name("AE").col("AESEV", "library_variable_role", "OK")
                .build();

        RuleExecutionResult noLibrary = RuleRunner.execute(operand, ae, _ -> null, "AE", null, null,
                null);
        assertTrue(noLibrary.isSkipped());
        assertEquals("Rule skipped — no Library metadata (rule requires library_* operands)",
                noLibrary.getStatusMessage());

        MetadataProvider degraded = org.mockito.Mockito.mock(MetadataProvider.class);
        org.mockito.Mockito.when(degraded.isLibraryUnavailable()).thenReturn(true);
        RuleExecutionResult degradedRun = RuleRunner.execute(operand, ae, _ -> null, "AE", degraded,
                null, null);
        assertTrue(degradedRun.isSkipped());
        assertEquals(
                "Rule skipped — the CDISC Library could not be consulted for this run, and "
                        + "library_* operands may not be answered from a non-library source",
                degradedRun.getStatusMessage());

        Rule literal = load("{\"Core\":{\"Id\":\"R1\"},\"Sensitivity\":\"Record\","
                + "\"Check\":{\"any\":[{\"name\":\"AESEV\",\"operator\":\"equal_to\","
                + "\"value\":\"library_variable_role\",\"value_is_literal\":true}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AESEV\"]}}");
        RuleExecutionResult literalRun = RuleRunner.execute(literal, ae, _ -> null, "AE", null,
                null, null);
        assertFalse(literalRun.isSkipped(), "a literal must not trip the operand gate");
        assertEquals(1, literalRun.getViolations().size());
        assertEquals(0L, literalRun.getViolations().get(0).getRow());
    }


    /**
     * The metadata-broadcast path's own library gate (reached only by accessor expressions the
     * loader cannot lower to operands): a missing library reads "no Library metadata available", a
     * DEGRADED one names the failed consultation — swapping the two misinforms the sponsor about
     * whether supplying a library key would change the run.
     */
    @Test
    void metadataPathLibraryGateDistinguishesMissingFromDegraded() throws Exception
    {
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Check":{"expression":"upper(var_label(\\"DATA\\")) != \
                upper(var_role(\\"LIBRARY\\"))"},
                 "Outcome":{"Message":"m","Output_Variables":["variable_name"]}}""");
        IDataTable ae = MockTable.of().name("AE").col("AESEV", "x")
                .colMeta("AESEV", "Severity", 0, null).build();

        RuleExecutionResult missing = RuleRunner.execute(rule, ae, _ -> null, "AE", null, null,
                null);
        assertTrue(missing.isSkipped());
        assertEquals("Rule skipped — no Library metadata available", missing.getStatusMessage());

        MetadataProvider degraded = org.mockito.Mockito.mock(MetadataProvider.class);
        org.mockito.Mockito.when(degraded.isLibraryUnavailable()).thenReturn(true);
        RuleExecutionResult degradedRun = RuleRunner.execute(rule, ae, _ -> null, "AE", degraded,
                null, null);
        assertTrue(degradedRun.isSkipped());
        assertEquals(
                "Rule skipped — the CDISC Library could not be consulted for this run, and "
                        + "LIBRARY-level metadata may not be answered from a non-library source",
                degradedRun.getStatusMessage());
    }

    // -----------------------------------------------------------------------
    // severity stamp + load-error sentinel
    // -----------------------------------------------------------------------


    /**
     * Every result leaving {@code execute} carries the rule's effective severity — executed,
     * skipped and error results alike. A finding without a severity cannot be triaged.
     */
    @Test
    void everyResultCarriesTheRulesEffectiveSeverity() throws Exception
    {
        Rule rule = load("{\"Core\":{\"Id\":\"R1\"},\"Sensitivity\":\"Record\","
                + "\"Check\":{\"all\":[{\"name\":\"AESEV\",\"operator\":\"equal_to\","
                + "\"value\":\"BAD\",\"value_is_literal\":true}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AESEV\"]}}");
        IDataTable ae = MockTable.of().name("AE").col("AESEV", "BAD").build();

        RuleExecutionResult r = RuleRunner.execute(rule, ae, _ -> null, "AE", null, null, null);
        assertNotNull(r.getSeverity(), "the severity stamp must never be dropped");
        assertEquals(rule.effectiveSeverity(), r.getSeverity());
    }


    /**
     * The load-error sentinel still reports the dataset's true row count — an ERROR result whose
     * {@code totalRows} reads 0 misstates how much data the broken rule failed to cover.
     */
    @Test
    void loadErrorSentinelReportsTrueRowCount()
    {
        Rule rule = new Rule();
        net.cumba.cdisc.core.model.RuleCore core = new net.cumba.cdisc.core.model.RuleCore();
        core.setId("TEST-INVALID");
        rule.setCore(core);
        rule.setCheck(net.cumba.cdisc.core.model.CheckConditionLeaf.builder().name("AESEV")
                .operator("non_empty").build());
        rule.setLoadError("boom");
        IDataTable ae = MockTable.of().name("AE").col("AESEV", "a", "b", "c").build();

        RuleExecutionResult r = RuleRunner.execute(rule, ae);
        assertTrue(r.isError());
        assertEquals("boom", r.getViolations().get(0).getValues().get("__error__"));
        assertEquals(3L, r.getTotalRows(), "the sentinel keeps the dataset's true row count");
    }

    // -----------------------------------------------------------------------
    // grouped execution
    // -----------------------------------------------------------------------


    /**
     * A Group-sensitivity rule reports ONE finding per failing group, anchored at the group's FIRST
     * FLAGGED row — with the grouping column at index 0, which a {@code >= 0} boundary mutant
     * silently drops (collapsing the dataset into one group and halving the findings). A group
     * whose key is missing is dropped by the shipped default. The per-rule cap truncates the stored
     * findings but never the true count.
     */
    @Test
    void groupedFindingsAnchorAtFirstFlaggedRowPerGroup() throws Exception
    {
        Rule rule = load("{\"Core\":{\"Id\":\"R1\"},\"Sensitivity\":\"Group\","
                + "\"Grouping_Variables\":[\"GRP\"],"
                + "\"Check\":{\"all\":[{\"name\":\"VAL\",\"operator\":\"equal_to\","
                + "\"value\":\"BAD\",\"value_is_literal\":true}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"GRP\",\"VAL\"]}}");
        // GRP at column index 0. Groups: A rows 0-1 (row 1 fires), B rows 2-3 (none),
        // C row 4 (fires), "" row 5 (fires but the missing key drops the group).
        IDataTable t = MockTable.of().name("AE").col("GRP", "A", "A", "B", "B", "C", "")
                .col("VAL", "ok", "BAD", "ok", "ok", "BAD", "BAD").build();

        RuleExecutionResult r = RuleRunner.execute(rule, t, _ -> null, "AE", null, null, null);
        assertEquals(2, r.getViolations().size(), "one finding per failing NON-missing-key group");
        assertEquals(1L, r.getViolations().get(0).getRow(), "group A anchors at its flagged row");
        assertEquals("A", r.getViolations().get(0).getValues().get("GRP"));
        assertEquals(4L, r.getViolations().get(1).getRow());
        assertEquals("C", r.getViolations().get(1).getValues().get("GRP"));

        RuleExecutionResult capped = RuleRunner.execute(rule, t, _ -> null, "AE", null, null, null,
                1, null, null, null, Set.of(), Set.of(), Severity.WARNING);
        assertEquals(1, capped.getViolations().size(), "the cap materialises one group finding");
        assertEquals(2, capped.getViolationCount(), "the TRUE group count survives the cap");
    }


    /**
     * {@code Grouping.keep_missings: true} keeps the missing-key group in the population — the
     * negative twin of the shipped default above. Dropping a declared keep silently deletes real
     * findings from groups keyed by a legitimately blank variable.
     */
    @Test
    void keepMissingsDeclarationKeepsTheMissingKeyGroup() throws Exception
    {
        Rule rule = load("{\"Core\":{\"Id\":\"R1\"},\"Sensitivity\":\"Group\","
                + "\"Grouping\":{\"Variables\":[\"GRP\"],\"keep_missings\":true},"
                + "\"Check\":{\"all\":[{\"name\":\"VAL\",\"operator\":\"equal_to\","
                + "\"value\":\"BAD\",\"value_is_literal\":true}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"VAL\"]}}");
        IDataTable t = MockTable.of().name("AE").col("GRP", "A", "").col("VAL", "BAD", "BAD")
                .build();

        RuleExecutionResult r = RuleRunner.execute(rule, t, _ -> null, "AE", null, null, null);
        assertEquals(2, r.getViolations().size(),
                "the declared keep_missings keeps the blank-key group");
    }

    // -----------------------------------------------------------------------
    // multi-level (Check_Levels) execution
    // -----------------------------------------------------------------------


    /**
     * Cross-level first claim on GROUP units: the two rungs anchor the SAME group at DIFFERENT rows
     * (the level's own first flagged row), so only the group-key stamp can tell the weaker rung it
     * is re-reporting. G1 is claimed by ERROR (anchored at its BAD row) and must NOT be re-reported
     * by INFO (whose first flagged row differs); G2 is INFO's alone. A mutant degrading the group
     * stamp either double-reports G1 or collapses G1 and G2 into one claim and silently drops G2.
     */
    @Test
    void multiLevelGroupedFirstClaimIsKeyedOnTheGroupNotTheAnchorRow() throws Exception
    {
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Group",
                 "Grouping_Variables":["GRP"],
                 "Check":{
                   "ERROR":{"expression":"VAL == \\"BAD\\""},
                   "INFO":{"expression":"VAL == \\"MEH\\" or VAL == \\"BAD\\"",
                           "Message":"weaker"}},
                 "Outcome":{"Message":"m","Output_Variables":["GRP","VAL"]}}""");
        // G1: row 0 MEH (INFO-only), row 1 BAD (both) → ERROR anchors at 1, INFO would anchor
        // at 0 — different rows, same group. G2: row 2 MEH → INFO only.
        IDataTable t = MockTable.of().name("AE").col("GRP", "G1", "G1", "G2")
                .col("VAL", "MEH", "BAD", "MEH").build();

        RuleExecutionResult r = runAtInfo(rule, t);

        assertEquals(2, r.getViolations().size(),
                "G1 once (claimed by ERROR), G2 once (INFO) — no double report, no lost group");
        Violation g1 = r.getViolations().get(0);
        assertEquals(Severity.ERROR, g1.getLevel());
        assertEquals(1L, g1.getRow(), "ERROR anchors G1 at ITS first flagged row");
        assertEquals("G1", g1.getValues().get("GRP"));
        Violation g2 = r.getViolations().get(1);
        assertEquals(Severity.INFO, g2.getLevel());
        assertEquals(2L, g2.getRow());
        assertEquals("G2", g2.getValues().get("GRP"));
    }


    /**
     * The single-group (no grouping column present) variant of the same first claim: both rungs
     * flag different rows of the ONE group, and the empty group-key stamp makes the weaker rung add
     * nothing. Without the stamp the two anchors differ and the same group ships twice.
     */
    @Test
    void multiLevelSingleGroupIsClaimedOnceAcrossLevels() throws Exception
    {
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Group",
                 "Grouping_Variables":["ABSENT"],
                 "Check":{
                   "ERROR":{"expression":"VAL == \\"BAD\\""},
                   "INFO":{"expression":"VAL == \\"MEH\\" or VAL == \\"BAD\\"",
                           "Message":"weaker"}},
                 "Outcome":{"Message":"m","Output_Variables":["VAL"]}}""");
        IDataTable t = MockTable.of().name("AE").col("VAL", "MEH", "BAD").build();

        RuleExecutionResult r = runAtInfo(rule, t);
        assertEquals(1, r.getViolations().size(),
                "one group in the dataset — one finding across both rungs");
        assertEquals(Severity.ERROR, r.getViolations().get(0).getLevel());
        assertEquals(1L, r.getViolations().get(0).getRow());
    }


    /**
     * The merged multi-level count is the sum of the levels' TRUE counts minus the cross-level
     * re-claims — and it survives the per-rule cap. ERROR truly fires 1 row; INFO truly fires 2 of
     * which 1 is re-claimed; total 2, stored 1 under a cap of 1. A flipped accumulator or a dropped
     * skip-count under-reports the dataset's defect burden.
     */
    @Test
    void multiLevelTotalCountSurvivesTheCapAndSubtractsOnlyReclaims() throws Exception
    {
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Check":{
                   "ERROR":{"expression":"A == \\"a\\""},
                   "INFO":{"expression":"A == \\"a\\" or A == \\"b\\"",
                           "Message":"weaker"}},
                 "Outcome":{"Message":"m","Output_Variables":["A"]}}""");
        IDataTable t = MockTable.of().name("AE").col("A", "a", "b", "c").build();

        RuleExecutionResult r = RuleRunner.execute(rule, t, _ -> null, "AE", null, null, null, 1,
                null, null, null, Set.of(), Set.of(), Severity.INFO);

        assertEquals(1, r.getViolations().size(), "the cap materialises one");
        assertEquals(2, r.getViolationCount(),
                "ERROR's row plus INFO's un-reclaimed row — the true burden");
    }


    /**
     * When EVERY declared level skips (both rungs need a Define-XML and none is supplied), the rule
     * reports SKIPPED with the first level's stated reason — never EXECUTED-with-zero, which would
     * read as a clean pass of a rule that was never asked.
     */
    @Test
    void allLevelsSkippedReportsTheFirstLevelsReason() throws Exception
    {
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Check":{
                   "ERROR":{"expression":"upper(var_label(\\"DATA\\")) != \
                upper(var_role(\\"DEFINE\\"))"},
                   "INFO":{"expression":"upper(var_label(\\"DATA\\")) == \
                upper(var_role(\\"DEFINE\\"))",
                           "Message":"weaker"}},
                 "Outcome":{"Message":"m","Output_Variables":["variable_name"]}}""");
        IDataTable t = MockTable.of().name("AE").col("AESEV", "x")
                .colMeta("AESEV", "Severity", 0, null).build();

        RuleExecutionResult r = runAtInfo(rule, t);
        assertTrue(r.isSkipped(), () -> String.valueOf(r.getStatus()));
        assertEquals("Rule skipped — no Define-XML metadata available", r.getStatusMessage());
        assertTrue(r.getViolations().isEmpty());
    }
}
