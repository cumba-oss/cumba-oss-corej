package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.cdisc.core.expr.CheckToExpr;
import net.cumba.cdisc.core.expr.ExpressionException;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.Outcome;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RuleCore;
import net.cumba.cdisc.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fix #212 / EC-73 — the inverted ISO interval of uncertainty reaches the shipped
 * {@code invalid_date} operator, measured <b>through {@link RuleRunner}</b>.
 *
 * <h2>Why through the runner</h2>
 * <p>
 * &#9888; Per {@code EC-45}: a probe that calls {@code CalendarDates.isValidDate} directly proves
 * something about a helper, not about the operator a rule actually fires. FDA-SD0003 is
 * {@code variable_name matches_regex "DTC$"} &and; {@code variable_value non_empty} &and;
 * {@code variable_value invalid_date} over Domains ALL, so this fixture is that leaf's shape over a
 * {@code --DTC} column.
 * </p>
 *
 * <h2>What the design ruled, and what it costs</h2>
 * <p>
 * D9 routes the inversion through {@code invalid_date} rather than through a new rule: there is no
 * conformance-sheet row to author one from (searched every sheet of
 * {@code SDTM_and_SDTMIG_Conformance_Rules_v2.0.xlsx} and {@code SEND_Conformance_Rules_v5.0.xlsx}
 * for {@code solidus|uncertainty} — the SDTM workbook returns zero rows, the SEND workbook only
 * CDISC-SEND-0070 and CDISC-SEND-0317), and a dedicated rule would double-report a cell
 * {@code invalid_date} already flags. &#9888; <b>The accepted cost</b>: the message is the generic
 * <i>"Invalid ISO 8601 value…"</i>, so an ordering error is reported as a format error.
 * </p>
 */
class IsoIntervalInvalidDateRuleTest
{

    /** The forward spellings that must stay silent — see PLAN &sect;7. */
    private static final String FORWARD = "2003-01-01/2003-06-30";

    /** SDTMIG &sect;4.4.2's worked interval, written backwards. */
    private static final String BACKWARDS = "2003-06-30/2003-01-01";

    /** Imprecise but satisfiable: Dec 15 lies inside December, so it is NOT inverted. */
    private static final String SATISFIABLE = "2003-12-15/2003-12";

    @Test
    @DisplayName("invalid_date fires on an inverted interval and only on it")
    void invalidDateFiresForAnInvertedIntervalThroughTheRunner()
    {
        IDataTable table = MockTable.of().name("AE")
                .col("AESTDTC", FORWARD, BACKWARDS, SATISFIABLE, "2003-01-01", "").build();

        RuleExecutionResult result = run(table);

        assertTrue(result.hasViolations(), "the backwards interval must be flagged");
        assertEquals(1, result.getViolationCount(),
                "only the backwards row: " + result.getViolations());
        assertEquals(BACKWARDS, result.getViolations().get(0).getValues().get("AESTDTC"));
    }


    @Test
    @DisplayName("⚠ the legitimate uncertainty interval must NOT be flagged")
    void everySatisfiableIntervalStaysSilent()
    {
        // FDA-SD0003 is *DTC × Domains ALL. If isValidDate ever stopped accepting a forward
        // interval, this rule would false-positive on every submission that records an
        // uncertainty interval — which SDTMIG §4.4.2 RECOMMENDS. That is the failure mode this
        // test exists to make loud.
        IDataTable table = MockTable.of().name("AE")
                .col("AESTDTC", "2003-12-15T10:00/2003-12-15T10:30", "2003-01-01/2003-02-15",
                        "2003-12-01/2003-12-10", FORWARD, SATISFIABLE, "2003-12-15/2003-12-15")
                .build();

        assertFalse(run(table).hasViolations(),
                "a legitimate interval of uncertainty was flagged invalid_date");
    }


    /** The leaf, raised and executed exactly as the shipped rule is. */
    private static RuleExecutionResult run(IDataTable table)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("FIX212-INVALID-DATE");
        rule.setCore(core);
        // FDA-SD0003's own shape: non_empty ∧ invalid_date. ⚠ The non_empty conjunct is
        // load-bearing here — without it the blank cell in the fixture fires too, because
        // isValidDate("") is false. That is the shipped rule, not a quirk of this test.
        rule.setCheck(new CheckConditionAll(List.of(
                CheckConditionLeaf.builder().name("AESTDTC").operator("non_empty").build(),
                CheckConditionLeaf.builder().name("AESTDTC").operator("invalid_date").build())));
        rule.setSensitivity(Sensitivity.RECORD);
        Outcome outcome = new Outcome();
        outcome.setMessage("Invalid ISO 8601 value for a Date/Time (DTC) variable.");
        outcome.setOutputVariables(List.of("AESTDTC"));
        rule.setOutcome(outcome);
        try
        {
            rule.setCheckExpr(CheckToExpr.toExpr(rule.getCheck()));
        }
        catch (ExpressionException e)
        {
            throw new AssertionError("invalid_date must raise to the native IR", e);
        }
        return RuleRunner.execute(rule, table);
    }

}
