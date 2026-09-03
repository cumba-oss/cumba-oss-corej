package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for rule execution against a 0-row dataset — the unit-level pin of the zero-row
 * policy ({@code PLAN-zero-row-routing-rekey.md}, owner rulings Q1 / Q5 / 6c-1, shipped as
 * {@code Fix #349} / {@code EC-89}; the policy text is {@code the zero-row policy note}).
 *
 * <p>
 * The policy in one line: a zero-row dataset is an error case reported <b>once</b>, by the
 * package's own "domain table has no records" rule; a rule that must <b>read a row</b> to decide
 * has nothing to check and returns {@code EXECUTED} with zero findings (never {@code SKIPPED}); a
 * rule that can still decide <b>correctly</b> without reading a row — metadata, Define,
 * library-level — fires exactly as on a populated dataset. The engine realises the split
 * structurally, not by a predicate: the non-row-reading rules return from {@code BroadcastFold} or
 * the leaf-scope dispatch upstream of the row path, and the row-reading ones evaluate the
 * <em>real</em> 0-row table, which yields no bits. The synthetic 1-row zero-column table that
 * {@code RuleRunner} once substituted for a non-row-based rule on a 0-row dataset is retired.
 * </p>
 *
 * <p>
 * Two discriminators are asserted throughout. {@code RuleExecutionResult.totalViolationCount} is
 * {@code -1} when the verdict was decided upstream of the row path and {@code >= 0} when rows were
 * evaluated — so a pin that asserts it fails the moment a rule changes route. And the dataset-level
 * violation of a metadata rule keeps the row=0 convention even when the dataset is empty
 * (historically {@code getRealRowIndex(0)} on the empty table threw; mirrors the corpus scenarios
 * {@code CDISC-AD0061} / {@code CDISC-AD0365}).
 * </p>
 */
class RuleRunnerEmptyDatasetTest
{

    /** A dataset-level metadata rule: fire when {@code TRTSDT} is not a column on the dataset. */
    private static Rule trtsdtNotExistsRule(String coreId, List<String> outputVars)
    {
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("TRTSDT")
                .operator("var_not_exists").build();
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId(coreId);
        rule.setCore(core);
        rule.setCheck(leaf);
        rule.setSensitivity(Sensitivity.DATASET);
        Outcome outcome = new Outcome();
        outcome.setMessage("TRTSDT is not present");
        outcome.setOutputVariables(outputVars);
        rule.setOutcome(outcome);
        net.cumba.corej.core.RulePackageLoader.installNativeExpr(rule);
        return rule;
    }


    @Test
    void datasetLevelRule_emptyPrimary_firesWithoutCrash()
    {
        // 0-row ADSL with a STUDYID column but no TRTSDT.
        IDataTable empty = MockTable.of().name("ADSL").col("STUDYID").build();
        assertEquals(0, empty.getRowCount(), "precondition: table must be empty");

        Rule rule = trtsdtNotExistsRule("CORE-EMPTY-FIRE", List.of("TRTSDT"));

        RuleExecutionResult result = assertDoesNotThrow(() -> RuleRunner.execute(rule, empty));

        assertTrue(result.hasViolations(),
                "dataset-level metadata check must fire on empty dataset");
        assertEquals(1, result.getViolationCount());
        // Dataset-level violation uses the row=0 convention even when the dataset is empty.
        assertEquals(0L, result.getViolations().getFirst().getRow());
    }


    @Test
    void datasetLevelRule_emptyPrimary_outputVarIsRealColumn_emptyValue()
    {
        // STUDYID is a real (present) column; on an empty dataset there is no value to read.
        IDataTable empty = MockTable.of().name("ADSL").col("STUDYID").build();

        Rule rule = trtsdtNotExistsRule("CORE-EMPTY-OUTVAR", List.of("STUDYID"));

        RuleExecutionResult result = assertDoesNotThrow(() -> RuleRunner.execute(rule, empty));

        assertTrue(result.hasViolations());
        assertEquals("", result.getViolations().getFirst().getValues().get("STUDYID"),
                "present column on empty dataset resolves to empty, not a crash");
    }


    @Test
    void datasetLevelRule_nonEmptyPrimary_unchanged()
    {
        // Regression: a populated dataset still records the dataset-level violation at row 0
        // and reads the real cell value for an output variable.
        IDataTable populated = MockTable.of().name("ADSL").col("STUDYID", "S1", "S2").build();

        Rule rule = trtsdtNotExistsRule("CORE-NONEMPTY", List.of("STUDYID"));

        RuleExecutionResult result = RuleRunner.execute(rule, populated);

        assertTrue(result.hasViolations());
        assertEquals(1, result.getViolationCount());
        assertEquals(0L, result.getViolations().getFirst().getRow());
        assertEquals("S1", result.getViolations().getFirst().getValues().get("STUDYID"));
    }


    /**
     * EC-43 / <b>R14</b>, re-adjudicated under {@code PLAN-zero-row-routing-rekey.md} (owner
     * rulings Q1 / 6c-1, {@code Fix #349}). Until that fix the engine substituted a synthetic 1-row
     * table carrying <b>zero columns</b> for a non-row-based rule on a 0-row dataset; after the
     * absent-column fold <em>every</em> referenced column resolved to all-missing there, whether or
     * not the real dataset carried it, so a Path-B negative such as {@code is_not_integer(TSVAL)}
     * fired once at the dataset level. This test used to pin that firing as a decision.
     *
     * <p>
     * The fold reasoning is still correct — an absent column <em>is</em> all-missing and a table
     * with no columns genuinely has none — but the table it applied to is gone: the rule must read
     * a row to decide, it now evaluates the <b>real</b> 0-row table, and there is no row to fire
     * on. The measured consequence corpus-wide was 13 shipped {@code DOMAIN}-column rules reporting
     * a spurious violation on every empty dataset ({@code GROUNDING-zero-row-rekey-2026-08-23}).
     * The fork has no counterpart — an empty dataframe yields no rows at all — so the two engines
     * now agree here.
     * </p>
     *
     * <p>
     * ⚠ <b>The leaf is {@code is_not_integer}, and the choice is still load-bearing (triage finding
     * S5).</b> A column-vs-<em>literal</em> negative carries an injected {@code var_exists(TSVAL)}
     * guard (pinned below); {@code is_not_integer} lowers to a <em>structural</em> negation
     * ({@code not is_integer(X)}) that the injector contributes no guard for, so this shape reaches
     * the row path with nothing between it and the data. It is therefore the shape that
     * <em>would</em> fire again if a zero-column substitute ever came back — which is the pin.
     * </p>
     */
    @Test
    void nonRowBasedRule_emptyPrimary_pathBNegativeHasNoRowToFireOn()
    {
        // TSVAL is PRESENT on the real dataset; there are simply no rows.
        IDataTable empty = MockTable.of().name("TS").col("TSPARMCD").col("TSVAL").build();
        assertEquals(0, empty.getRowCount(), "precondition: table must be empty");

        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("CORE-EMPTY-PATHB");
        rule.setCore(core);
        rule.setCheck(
                CheckConditionLeaf.builder().name("TSVAL").operator("is_not_integer").build());
        // DATASET sensitivity makes the rule non-row-based: one finding at most, collapsed from the
        // row evaluation — the shape the retired synthetic table used to be built for.
        rule.setSensitivity(Sensitivity.DATASET);
        Outcome outcome = new Outcome();
        outcome.setMessage("TSVAL is not X");
        rule.setOutcome(outcome);
        net.cumba.corej.core.RulePackageLoader.installNativeExpr(rule);

        RuleExecutionResult result = assertDoesNotThrow(() -> RuleRunner.execute(rule, empty));

        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                "nothing to check is an execution with no findings, never SKIPPED");
        assertFalse(result.hasViolations(),
                "the rule must read a row to decide and the real table has none — the zero-column "
                        + "substitute that made TSVAL fold absent here is retired (Fix #349)");
        assertEquals(0L, result.getTotalViolationCount(),
                "the verdict came from the row path (>= 0), not from an upstream fold (-1)");
        assertEquals(0L, result.getTotalRows());

        // The same rule at RECORD sensitivity is row-based and always evaluated the real 0-row
        // table. The two answers now AGREE, and that agreement is the pin: on an empty dataset
        // row-basedness decides the finding multiplicity only, never whether a fold can fire.
        Rule rowBased = new Rule();
        RuleCore rowCore = new RuleCore();
        rowCore.setId("CORE-EMPTY-PATHB-ROW");
        rowBased.setCore(rowCore);
        rowBased.setCheck(
                CheckConditionLeaf.builder().name("TSVAL").operator("is_not_integer").build());
        rowBased.setSensitivity(Sensitivity.RECORD);
        rowBased.setOutcome(outcome);
        net.cumba.corej.core.RulePackageLoader.installNativeExpr(rowBased);
        assertFalse(RuleRunner.execute(rowBased, empty).hasViolations(),
                "a row-based rule on a 0-row dataset has no rows to fire on");
    }


    /**
     * Triage finding S5, the other half of the pin above: a column-vs-<b>literal</b> negative
     * carries an injected {@code var_exists(TSVAL)} guard, and on a 0-row dataset it does
     * <em>not</em> fire.
     *
     * <p>
     * ⚠ The reason changed with {@code Fix #349}, and the old reason must not be re-derived. Until
     * then the guard was <em>false</em> — not because the real dataset lacked {@code TSVAL} (it
     * declares it) but because the synthetic 1-row table the engine substituted for a non-row-based
     * rule had no columns at all. That table is retired. Now the guard is <b>true</b> — the real
     * metadata declares {@code TSVAL} — the conjunction is not decidable without a row, the rule
     * takes the row path, and the real table has no row to evaluate. Same verdict, opposite
     * mechanism; the {@code totalViolationCount} assertion below is what tells the two apart.
     * </p>
     */
    @Test
    void nonRowBasedRule_emptyPrimary_guardedColumnVsLiteralNegativeDoesNotFire()
    {
        IDataTable empty = MockTable.of().name("TS").col("TSPARMCD").col("TSVAL").build();

        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("CORE-EMPTY-PATHB-S5");
        rule.setCore(core);
        // value_is_literal: true, or the converter reads "X" as a column reference and the
        // column-vs-COLUMN arm would guard it for a different reason — the two must not mask.
        // Guard AUTHORED (NonEmptyGuardInliner, which injected it, was deleted 2026-08-26).
        // The subject here is the engine's row-path verdict on a guarded negative, not the
        // provenance of the guard.
        rule.setCheck(new net.cumba.corej.core.model.CheckConditionAll(java.util.List.of(
                CheckConditionLeaf.builder().name("TSVAL").operator("var_exists").build(),
                CheckConditionLeaf.builder().name("TSVAL").operator("not_equal_to")
                        .value(new com.fasterxml.jackson.databind.node.TextNode("X"))
                        .valueIsLiteral(true).build())));
        rule.setSensitivity(Sensitivity.DATASET);
        Outcome outcome = new Outcome();
        outcome.setMessage("TSVAL is not X");
        rule.setOutcome(outcome);
        net.cumba.corej.core.RulePackageLoader.installNativeExpr(rule);

        assertTrue(String.valueOf(rule.getCheckExpr()).contains("var_exists"),
                () -> "precondition: the guard must be present — " + rule.getCheckExpr());
        RuleExecutionResult result = RuleRunner.execute(rule, empty);
        assertFalse(result.hasViolations(),
                "var_exists(TSVAL) is TRUE on the real metadata, so the guarded negative is not "
                        + "decidable without a row — and the 0-row table has none");
        assertEquals(0L, result.getTotalViolationCount(),
                "the verdict came from the row path, not from the guard folding false");
    }


    /**
     * Row-based rules on an empty dataset yield no violations and must not crash. ⚠ Until the
     * 2026-08-23 review this case never installed the native expression, so the rule fell to the
     * "no native form" {@code ERROR} gate and both assertions passed without the row path running —
     * the vacuous-pin failure mode. It now installs the Check and asserts the status and the return
     * site.
     */
    @Test
    void rowBasedRule_emptyPrimary_noViolation()
    {
        IDataTable empty = MockTable.of().name("AE").col("AESEV").build();

        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("AESEV").operator("non_empty")
                .build();
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("CORE-EMPTY-ROWBASED");
        rule.setCore(core);
        rule.setCheck(leaf);
        rule.setSensitivity(Sensitivity.RECORD);
        Outcome outcome = new Outcome();
        outcome.setMessage("AESEV is populated");
        rule.setOutcome(outcome);
        net.cumba.corej.core.RulePackageLoader.installNativeExpr(rule);
        assertNotNull(rule.getCheckExpr(), "precondition: the leaf compiled natively");

        RuleExecutionResult result = assertDoesNotThrow(() -> RuleRunner.execute(rule, empty));

        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                "a row-based rule on an empty dataset EXECUTES with nothing to report");
        assertFalse(result.hasViolations());
        assertEquals(0L, result.getTotalViolationCount(), "decided on the row path");
        assertEquals(0, result.getTotalRows());
    }


    /**
     * {@code PLAN-leaf-scope-domain-inference.md} §5 phase-4 precondition, owner ruling Q12 (b)
     * (2026-08-22): the {@code Fix #330} × empty-dataset interaction, pinned as a <b>synthetic</b>
     * case because no shipped rule reaches it with this shape (the only positive bare
     * {@code empty(COL)} carriers, {@code CDISC-SEND-0105.1} / {@code -0106.1}, are {@code Group}
     * rules and return into {@code executeGrouped}; their grouped-path zero-row pins live in the
     * {@code .cdt} suites).
     *
     * <p>
     * <b>Re-adjudicated under {@code PLAN-zero-row-routing-rekey.md}</b> (owner rulings Q1 / 6c-1,
     * {@code Fix #349}) — the re-adjudication the previous javadoc of this test asked for. The
     * observed 2026-08-22 run had the {@code DATASET}-sensitivity rule firing once, because since
     * {@code Fix #330} a bare {@code empty(X)} reads <em>absent = all-missing</em> and the
     * synthetic table substituted for a non-row-based rule on a 0-row dataset carried zero columns
     * — so {@code empty(SPECIES)} was TRUE there although the real dataset declares
     * {@code SPECIES}. That substitution is retired: the Check is a column read, so it must read a
     * row to decide, and the real 0-row table has none. {@code DATASET} and {@code RECORD}
     * sensitivity now agree.
     * </p>
     *
     * <p>
     * The Check is built as a {@code CheckConditionExpression}, not a leaf, because the leaf form
     * routes through {@code BroadcastFold}'s {@code empty(absent)} short-circuit (a different
     * mechanism, the {@code Fix #330} ledger's own trap note) — this pin is about the row path.
     * </p>
     */
    @Test
    void nonRowBasedRule_emptyPrimary_positiveBareEmptyExpressionHasNoRowToFireOn()
    {
        IDataTable empty = MockTable.of().name("DM").col("STUDYID").col("SPECIES").build();
        assertEquals(0, empty.getRowCount(), "precondition: table must be empty");
        assertTrue(empty.getMetaData().getColumnIndex("SPECIES") >= 0,
                "precondition: the REAL dataset declares SPECIES");

        Rule dataset = bareEmptyExpressionRule("CORE-EMPTY-FIX330-DATASET", Sensitivity.DATASET);
        assertTrue(
                dataset.getCheck() instanceof net.cumba.corej.core.model.CheckConditionExpression,
                "precondition: expression form, so the leaf-form fold short-circuit is not in play");
        RuleExecutionResult result = assertDoesNotThrow(() -> RuleRunner.execute(dataset, empty));
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus());
        assertEquals(0, result.getViolationCount(),
                "a positive bare empty(SPECIES) must read a row to decide; the zero-column table "
                        + "that folded it TRUE on an empty dataset is retired (Fix #349)");
        assertEquals(0L, result.getTotalViolationCount(), "decided on the row path, not by a fold");

        Rule record = bareEmptyExpressionRule("CORE-EMPTY-FIX330-RECORD", Sensitivity.RECORD);
        assertFalse(RuleRunner.execute(record, empty).hasViolations(),
                "the same Check at RECORD sensitivity is row-based and has nothing to fire on");
    }

    // ------------------------------------------------------------------------------------------
    // The policy's three halves, pinned directly (PLAN-zero-row-routing-rekey.md phase 1)
    // ------------------------------------------------------------------------------------------


    /**
     * The unit-level pin of the 13 (ruling 6c-1): a {@code Dataset} × {@code {ROW}}
     * {@code DOMAIN}-column rule — {@code len(DOMAIN) != 2}, the shape of {@code CORE-000180} and
     * its twelve siblings — on a 0-row table that <em>declares</em> {@code DOMAIN}. Before
     * {@code Fix #349} this fired once on every empty dataset: the synthetic substitute had no
     * columns, {@code DOMAIN} folded absent and {@code len(absent) != 2} was true.
     */
    @Test
    void rowReadingRule_emptyDataset_executedWithNoFindings()
    {
        IDataTable empty = MockTable.of().name("DM").col("STUDYID").col("DOMAIN").build();
        assertEquals(0, empty.getRowCount(), "precondition: table must be empty");
        assertTrue(empty.getMetaData().getColumnIndex("DOMAIN") >= 0,
                "precondition: the dataset declares DOMAIN — the column is present, not absent");

        Rule rule = expressionRule("CORE-EMPTY-DOMAIN-LEN", "len(DOMAIN) != 2",
                Sensitivity.DATASET);

        RuleExecutionResult result = assertDoesNotThrow(() -> RuleRunner.execute(rule, empty));

        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                "nothing to check is EXECUTED with no findings, never SKIPPED (ruling Q5)");
        assertEquals(0, result.getViolationCount(),
                "a DOMAIN-column rule must read a row; an empty dataset has none to be wrong");
        assertEquals(0L, result.getTotalViolationCount(), "decided on the row path");
        assertEquals(0L, result.getTotalRows());

        // The same rule on one populated row with a bad DOMAIN still fires — the fix removed a
        // spurious finding, not the rule's teeth.
        IDataTable bad = MockTable.of().name("DM").col("STUDYID", "S1").col("DOMAIN", "DMX")
                .build();
        assertEquals(1, RuleRunner.execute(rule, bad).getViolationCount());
    }


    /**
     * Family B of the 29 pinned zero-row firers ({@code var_exists(A) and not var_exists(B)} — the
     * shape of {@code PMDA-AD0007} and its siblings): a metadata rule decides <em>correctly</em>
     * without reading a row, so it keeps firing on an empty dataset (ruling Q1). The return site is
     * asserted by {@code totalViolationCount == -1}: this pin fails the moment the rule starts
     * taking the row path, where a 0-row table would silence it.
     */
    @Test
    void metadataRule_emptyDataset_stillFires()
    {
        // STUDYID present, TRTSDT absent — the rule's condition holds on the metadata alone.
        IDataTable empty = MockTable.of().name("ADSL").col("STUDYID").build();
        assertEquals(0, empty.getRowCount(), "precondition: table must be empty");

        Rule rule = expressionRule("CORE-EMPTY-METADATA",
                "var_exists(\"STUDYID\") and not var_exists(\"TRTSDT\")", Sensitivity.DATASET);

        RuleExecutionResult result = assertDoesNotThrow(() -> RuleRunner.execute(rule, empty));

        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus());
        assertEquals(1, result.getViolationCount(),
                "a rule that can decide without a row fires on an empty dataset exactly as on a "
                        + "populated one");
        assertEquals(-1L, result.getTotalViolationCount(),
                "decided UPSTREAM of the row path (BroadcastFold) — the structural proof that the "
                        + "zero-row policy cannot silence it");
        assertEquals(0L, result.getViolations().getFirst().getRow(),
                "dataset-level finding on an empty dataset keeps the row=0 convention");
    }


    /**
     * The twins' unit-level pin: {@code $records_in_dataset == 0} over a {@code record_count}
     * operation — the shape of {@code FDA-SD0001}, {@code PMDA-SD0001}, {@code CDISC-CG0408},
     * {@code CDISC-SEND-0278}, {@code CORE-000579} and the minted {@code CDISC-AD9701} /
     * {@code CORE-009706}. This is the one rule the whole policy routes the zero-row story through:
     * it must fire exactly once on an empty dataset and not at all on a populated one. A failure
     * here would mean the policy silenced the rule it reports empty datasets with.
     */
    @Test
    void noRecordsTwin_emptyDataset_firesOnce() throws java.io.IOException
    {
        Rule rule = noRecordsTwin();
        IDataTable empty = MockTable.of().name("LB").col("STUDYID").col("DOMAIN").build();
        assertEquals(0, empty.getRowCount(), "precondition: table must be empty");

        RuleExecutionResult result = assertDoesNotThrow(() -> RuleRunner.execute(rule, empty));

        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus());
        assertEquals(1, result.getViolationCount(), "an empty dataset is reported ONCE, here");
        assertEquals(-1L, result.getTotalViolationCount(),
                "decided by BroadcastFold — $records_in_dataset == 0 is a dataset fact on both "
                        + "sides, so the twin never sees the row path and cannot be silenced by it");
        assertEquals("0", result.getViolations().getFirst().getValues().get("$records_in_dataset"),
                "the output variable carries the count that fired");

        IDataTable populated = MockTable.of().name("LB").col("STUDYID", "S1", "S1")
                .col("DOMAIN", "LB", "LB").build();
        assertFalse(RuleRunner.execute(rule, populated).hasViolations(),
                "two records: $records_in_dataset == 2, no finding");
    }


    /** A {@code CheckConditionExpression} rule from its source text, installed natively. */
    private static Rule expressionRule(String coreId, String source, Sensitivity sensitivity)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId(coreId);
        rule.setCore(core);
        rule.setCheck(new net.cumba.corej.core.model.CheckConditionExpression(
                net.cumba.corej.core.expr.CheckExpressionParser.parse(source), source));
        rule.setSensitivity(sensitivity);
        Outcome outcome = new Outcome();
        outcome.setMessage(source);
        rule.setOutcome(outcome);
        net.cumba.corej.core.RulePackageLoader.installNativeExpr(rule);
        assertNotNull(rule.getCheckExpr(), "precondition: the expression compiled natively");
        return rule;
    }


    /**
     * The shipped "no records" shape, loaded through the package loader in the shipped Form-B
     * spelling.
     */
    private static Rule noRecordsTwin() throws java.io.IOException
    {
        net.cumba.corej.core.model.RulePackage pkg = net.cumba.corej.core.RulePackageLoader
                .loadFromString(
                        """
                                {"rules": {"TEST-NO-RECORDS": {
                                  "Core": {"Id": "TEST-NO-RECORDS", "Status": "Published", "Version": "1"},
                                  "Executability": "Fully Executable",
                                  "Scope": {"Domains": {"Include": ["ALL"]}},
                                  "Operations": [{"id": "$records_in_dataset", "expression": "record_count()"}],
                                  "Check": {"expression": "$records_in_dataset == 0"},
                                  "Outcome": {"Message": "Dataset has no records.",
                                              "Output_Variables": ["$records_in_dataset"]}
                                }}}
                                """);
        Rule rule = pkg.getRules().get("TEST-NO-RECORDS");
        assertNotNull(rule, "precondition: the twin loaded");
        assertNull(rule.getLoadError(), "precondition: the twin loaded without error");
        return rule;
    }


    private static Rule bareEmptyExpressionRule(String coreId, Sensitivity sensitivity)
    {
        String source = "empty(SPECIES)";
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId(coreId);
        rule.setCore(core);
        rule.setCheck(new net.cumba.corej.core.model.CheckConditionExpression(
                net.cumba.corej.core.expr.CheckExpressionParser.parse(source), source));
        rule.setSensitivity(sensitivity);
        Outcome outcome = new Outcome();
        outcome.setMessage("SPECIES is empty");
        rule.setOutcome(outcome);
        net.cumba.corej.core.RulePackageLoader.installNativeExpr(rule);
        assertTrue(rule.getCheckExpr() != null, "precondition: the expression compiled natively");
        return rule;
    }

}
