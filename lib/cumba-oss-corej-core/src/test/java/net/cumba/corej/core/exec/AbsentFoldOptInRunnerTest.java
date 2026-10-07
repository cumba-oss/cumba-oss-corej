package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-message-folded-corpus-alignment} row 5 — <b>Q6</b> (owner, 2026-10-05: <i>"I would
 * expect the absent fold does not fire anymore if no Message_Folded is available. Like the all
 * empty fold."</i>): the absent-column fold (D111) is opt-in through
 * {@code Outcome.Message_Folded}, exactly as the present-but-empty collapse is. A record-level rule
 * <b>without</b> the key is folded absent-blind (P4): a leaf over an absent column answers UNKNOWN,
 * dataset-constant leaves still decide, and the per-row path reads the absent column as its type
 * default. With the key, a TRUE the absence decided reports once under {@code Message_Folded}; a
 * TRUE a dataset-constant leaf decided reports once under {@code Message} (P4's discriminator: TRUE
 * under the full fold and not under the absent-blind one).
 *
 * <p>
 * Red first (recorded in the plan's status file): against the phase-2 engine the first and third
 * tests fail — one folded finding instead of three per-row ones, and {@code Message_Folded} on a
 * dataset-constant verdict.
 * </p>
 */
class AbsentFoldOptInRunnerTest
{

    private static final String FOLDED = "AEBDSYCD not present in the dataset: per-row message";

    private static Rule rule(String id, String check, @Nullable String messageFolded)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId(id);
        rule.setCore(core);
        rule.setCheck(new CheckConditionExpression(CheckExpressionParser.parse(check), check));
        rule.setSensitivity(Sensitivity.RECORD);
        Outcome outcome = new Outcome();
        outcome.setMessage("per-row message");
        outcome.setMessageFolded(messageFolded);
        outcome.setOutputVariables(List.of("AEBDSYCD"));
        rule.setOutcome(outcome);
        RulePackageLoader.installNativeExpr(rule);
        assertNull(rule.getLoadError(), rule.getLoadError());
        return rule;
    }


    /** AE, three records, no {@code AEBDSYCD} column at all. */
    private static IDataTable aeWithoutBdsycd()
    {
        return MockTable.of().name("AE").col("USUBJID", "01", "02", "03")
                .col("AEBODSYS", "Cardiac", "", "Renal").build();
    }


    /** AE, three records, {@code AEBDSYCD} present and populated on every one. */
    private static IDataTable aeWithBdsycd()
    {
        return MockTable.of().name("AE").col("USUBJID", "01", "02", "03")
                .col("AEBODSYS", "Cardiac", "", "Renal").col("AEBDSYCD", "1", "2", "3").build();
    }


    private static RuleExecutionResult run(Rule rule, IDataTable table)
    {
        return RuleRunnerCalls.execute(rule, table, _ -> null, "AE", null, null, null,
                Integer.MAX_VALUE, null, null, null, Set.of(), Set.of());
    }


    private static void assertOneDatasetFinding(RuleExecutionResult result, String message)
    {
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(1, result.getViolationCount(), "one dataset finding: " + result);
        Violation v = result.getViolations().getFirst();
        assertFalse(v.getUnit() instanceof Violation.Unit.Row, "never a row unit: " + v.getUnit());
        assertEquals(message, result.getMessage());
    }


    @Test
    @DisplayName("Q6 — without Message_Folded a rule reports PER ROW on an absent column")
    void withoutTheKeyTheAbsentColumnIsEvaluatedPerRow()
    {
        RuleExecutionResult r = run(rule("Q6-OUT", "empty(AEBDSYCD)", null), aeWithoutBdsycd());
        assertEquals(RuleExecutionStatus.EXECUTED, r.getStatus(), r.getStatusMessage());
        assertEquals(3, r.getViolationCount(),
                "the absent column reads as \"\" on every record, so every record fires: " + r);
        for (Violation v : r.getViolations())
        {
            assertFalse(v.getUnit() instanceof Violation.Unit.Dataset,
                    "a row finding, never the dataset unit: " + v);
        }
        assertEquals("per-row message", r.getMessage());
    }


    @Test
    @DisplayName("with Message_Folded an absent-decided TRUE is ONE finding under Message_Folded")
    void withTheKeyTheAbsentColumnFoldsToOneFinding()
    {
        assertOneDatasetFinding(run(rule("Q6-IN", "empty(AEBDSYCD)", FOLDED), aeWithoutBdsycd()),
                FOLDED);
    }


    @Test
    @DisplayName("P4 — a dataset-constant TRUE reports under Message even when the key is declared")
    void aDatasetConstantVerdictKeepsMessage()
    {
        // record_count() > 0 decides the Or on its own; AEBDSYCD is present and populated, so
        // nothing about the absence or blankness of a column took part.
        assertOneDatasetFinding(
                run(rule("Q6-CONST", "empty(AEBDSYCD) or record_count() > 0", FOLDED),
                        aeWithBdsycd()),
                "per-row message");
    }


    @Test
    @DisplayName("P4 — TRUE under the full fold but not absent-blind: the absence decided it")
    void anAbsentLeafConjoinedWithADatasetConstantIsAbsentDecided()
    {
        // And(absent ⇒ TRUE, record_count() > 0 ⇒ TRUE) is TRUE under the full fold; absent-blind
        // the first part is UNKNOWN, so the verdict is the absence's and Message_Folded prints.
        assertOneDatasetFinding(
                run(rule("Q6-MIXED", "empty(AEBDSYCD) and record_count() > 0", FOLDED),
                        aeWithoutBdsycd()),
                FOLDED);
    }


    @Test
    @DisplayName("without the key a dataset-constant leaf still decides (the blind fold keeps arms 1 and 3)")
    void withoutTheKeyADatasetConstantLeafStillDecides()
    {
        assertOneDatasetFinding(
                run(rule("Q6-OUT-CONST", "record_count() > 0", null), aeWithoutBdsycd()),
                "per-row message");
        RuleExecutionResult none = run(rule("Q6-OUT-FALSE", "record_count() > 10", null),
                aeWithoutBdsycd());
        assertEquals(RuleExecutionStatus.EXECUTED, none.getStatus(), none.getStatusMessage());
        assertEquals(0, none.getViolationCount(), none.toString());
    }


    @Test
    @DisplayName("without the key a leaf FALSE on the absent column fires nowhere, per row as it did folded")
    void withoutTheKeyAnAbsentFalseLeafMovesNothing()
    {
        RuleExecutionResult r = run(rule("Q6-OUT-NEG", "not empty(AEBDSYCD)", null),
                aeWithoutBdsycd());
        assertEquals(RuleExecutionStatus.EXECUTED, r.getStatus(), r.getStatusMessage());
        assertTrue(r.getViolations().isEmpty(), "\"\" is empty on every record: " + r);
    }
}
