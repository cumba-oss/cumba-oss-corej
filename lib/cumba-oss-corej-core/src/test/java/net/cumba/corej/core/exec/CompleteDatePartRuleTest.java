package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Fix #157 — the shipped end-to-end contract of the {@code is_complete_date_part} /
 * {@code is_not_complete_date_part} operator pair, measured through {@link RuleRunner} rather than
 * at the {@code Primitives} layer (a probe below {@code RuleRunner} can reach verdicts the shipped
 * pipeline never produces).
 *
 * <p>
 * The value set is the truth table confirmed for {@code PMDA-SD2247A} in
 * {@code plans/done/PLAN-incomplete-date-rule-review.md} §3b. The second test is the point of the
 * effort: it runs the SAME eight values through today's {@code is_incomplete_date} and shows
 * exactly which rows move — the empty and malformed values it silently passes, and the
 * date-complete/time-truncated value it wrongly reports.
 * </p>
 *
 * <p>
 * ⚠ This is an <b>engine-only</b> lane. No shipped rule uses the new operator yet; the corpus
 * migration is a separate, later effort.
 * </p>
 */
class CompleteDatePartRuleTest
{

    /** The confirmed truth table's values, in row order. */
    private static final List<String> VALUES = List.of("", "banana", "2020-13-45", "2020",
            "2020-01", "2020-01-15", "2020-01-15T10", "2020-01-15T10:30:00");

    private static Rule oneLeafRule(String operator)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("CORE-FIX157-" + operator);
        rule.setCore(core);
        rule.setCheck(CheckConditionLeaf.builder().name("TSVAL").operator(operator).build());
        rule.setSensitivity(Sensitivity.RECORD);
        Outcome outcome = new Outcome();
        outcome.setMessage("date part is not complete");
        outcome.setOutputVariables(List.of("TSVAL"));
        rule.setOutcome(outcome);
        RulePackageLoader.installNativeExpr(rule);
        return rule;
    }


    private static IDataTable ts()
    {
        return MockTable.of().name("TS").col("TSVAL", VALUES.toArray(new String[0])).build();
    }


    /** The 0-based row indices the rule reports, in ascending order. */
    private static List<Long> firedRows(String operator, IDataTable table)
    {
        return RuleRunner.execute(oneLeafRule(operator), table).getViolations().stream()
                .map(Violation::getRow).sorted().toList();
    }


    @Test
    void notCompleteDatePartFiresOnEveryValueWhoseDatePortionIsNotComplete()
    {
        // Rows 0-4: blank, malformed-structural, malformed-calendar, year-only, year-month.
        // Rows 5-7 all carry a complete YYYY-MM-DD date portion and must NOT fire — including
        // row 6, "2020-01-15T10", whose TIME is truncated. That row is the fix.
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L), firedRows("is_not_complete_date_part", ts()));
    }


    @Test
    void positiveFormFiresOnExactlyTheComplement()
    {
        assertEquals(List.of(5L, 6L, 7L), firedRows("is_complete_date_part", ts()));
    }


    /**
     * The reason the operator exists. {@code is_incomplete_date} means <i>valid but truncated</i>,
     * so it misses the blank and both malformed values (under-report) and fires on the
     * date-complete/time-truncated one (over-report). Four of the eight rows disagree.
     */
    @Test
    void differsFromIsIncompleteDateOnExactlyTheFourContestedRows()
    {
        assertEquals(List.of(3L, 4L, 6L), firedRows("is_incomplete_date", ts()));
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L), firedRows("is_not_complete_date_part", ts()));
        // and is_complete_date is not the answer either — it also rejects row 6.
        assertEquals(List.of(5L, 7L), firedRows("is_complete_date", ts()));
    }


    /**
     * EC-43 — an absent column is an all-missing column, so the negative form fires on every row
     * and the positive form on none. That is why {@code is_not_complete_date_part} is a
     * guard-matrix class-V <em>negative</em> leaf despite its positive-looking spelling.
     */
    @Test
    void absentColumnBehavesExactlyLikeAnAllBlankColumn()
    {
        IDataTable absent = MockTable.of().name("TS").col("TSPARMCD", "A", "B", "C").build();
        IDataTable blank = MockTable.of().name("TS").col("TSPARMCD", "A", "B", "C")
                .col("TSVAL", "", "", "").build();

        assertEquals(List.of(0L, 1L, 2L), firedRows("is_not_complete_date_part", blank));
        assertEquals(firedRows("is_not_complete_date_part", blank),
                firedRows("is_not_complete_date_part", absent));
        assertEquals(List.of(), firedRows("is_complete_date_part", absent));
    }

}
