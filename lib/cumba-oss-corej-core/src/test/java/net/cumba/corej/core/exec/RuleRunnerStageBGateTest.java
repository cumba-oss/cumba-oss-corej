package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.typed.StageBChecker;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The stage-B bind gate in {@code RuleRunner} (phase 4 of {@code PLAN-typed-expression-engine.md}):
 * an armed finding maps to a bind {@code ERROR} on the {@code "__error__"} sentinel channel naming
 * the binding (D41), a clean report proceeds, and the checker runs inside the production execution
 * path. The armed path is exercised through {@link RuleRunner#stageBGate} directly, because through
 * {@code execute} the specialiser resolves every surface the checker asserts — by design, that path
 * only fires on a specialiser defect.
 */
class RuleRunnerStageBGateTest
{

    private static net.cumba.corej.core.model.CheckConditionExpression expr(String source)
    {
        return new net.cumba.corej.core.model.CheckConditionExpression(
                net.cumba.corej.core.expr.CheckExpressionParser.parse(source), source);
    }


    @AfterEach
    void clearObserver()
    {
        StageBChecker.setObserver(null);
    }


    private static Rule exprRule(String id, String expression)
    {
        Rule rule = new Rule();
        rule.setId(id);
        rule.setCheckExpr(CheckExpressionParser.parse(expression));
        return rule;
    }


    @Test
    void anArmedFindingMapsToABindErrorNamingTheBinding()
    {
        IDataTable table = MockTable.of().col("AEOCCUR", "N").build();
        Rule rule = exprRule("TEST-0001", "--OCCUR != \"N\"");

        RuleExecutionResult result = RuleRunner.stageBGate(rule, table, true, "TEST-0001", null);

        assertNotNull(result);
        assertEquals(RuleExecutionStatus.ERROR, result.getStatus());
        assertTrue(result.getStatusMessage().contains("--OCCUR"));
        assertTrue(result.getStatusMessage().contains("specialiser defect"));
        assertEquals(1, result.getViolations().size());
        assertEquals("stage B: " + "UNRESOLVED_WILDCARD [--OCCUR]: '--OCCUR' still carries a "
                + "`--` wildcard after specialisation — a specialiser defect (D77b), not an "
                + "authoring one", result.getViolations().get(0).getValues().get("__error__"));
    }


    @Test
    void aCleanReportProceeds()
    {
        IDataTable table = MockTable.of().col("AEOCCUR", "N").build();
        Rule rule = exprRule("TEST-0002", "AEOCCUR != \"N\"");

        assertNull(RuleRunner.stageBGate(rule, table, true, "TEST-0002", null));
    }


    @Test
    void observeOnlyFindingsNeverGate()
    {
        // A column-type mismatch is the ColumnTypeGate's to error (D15) — the checker's shadow
        // finding must not produce a stage-B outcome.
        IDataTable table = MockTable.of().col("AESEQ", "1").build();
        Rule rule = exprRule("TEST-0003", "AESEQ > 5");

        assertNull(RuleRunner.stageBGate(rule, table, true, "TEST-0003", null));
    }


    @Test
    void theCheckerRunsInsideTheProductionExecutionPath()
    {
        // Anti-vacuity for the wiring itself: a plain RuleRunner.execute must reach the stage-B
        // checker (observer called once per (rule × dataset) execution).
        AtomicInteger seen = new AtomicInteger();
        StageBChecker.setObserver((rule, report) -> seen.incrementAndGet());
        IDataTable table = MockTable.of().name("AE").col("AEOCCUR", "N", "Y").build();
        Rule rule = exprRule("TEST-0004", "AEOCCUR == \"N\"");
        rule.setCheck(expr("AEOCCUR == N"));

        RuleExecutionResult result = RuleRunner.execute(rule, table);

        assertNotNull(result);
        assertEquals(1, seen.get());
    }


    @Test
    void aDeclaredSkipMapsToSkippedWithTheReason()
    {
        // The D89a skip channel is structurally unreachable through production today (no Filter
        // field until 5b-J), so the mapping is exercised on a synthetic report — provably
        // correct rather than dead.
        IDataTable table = MockTable.of().col("AEOCCUR", "N").build();
        net.cumba.corej.core.expr.typed.StageBReport report = new net.cumba.corej.core.expr.typed.StageBReport(
                List.of(), List.of("Rule skipped — filter column AE.AEOUT is not present and is "
                        + "declared in Requirements.Variables (D89a)"));

        RuleExecutionResult result = RuleRunner.mapStageBReport(report, table, "TEST-0005", null);

        assertNotNull(result);
        assertEquals(RuleExecutionStatus.SKIPPED, result.getStatus());
        assertTrue(result.getStatusMessage().contains("AE.AEOUT"));
        assertEquals(List.of(), result.getViolations());
    }

}
