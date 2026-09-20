package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D111 (phase 7 of {@code PLAN-typed-expression-engine.md}) — an absent-column-only check folds to
 * a <b>dataset-level</b> evaluation and reports <b>one</b> finding, for <b>every</b> operator, not
 * only {@code empty}/{@code is_missing} (the retired D38 allowlist).
 *
 * <p>
 * ⭐ <b>This class pins the ruled loss of EC-43's reporting equivalence.</b> EC-43 established that
 * an absent column and a present-but-all-blank column report identically (both per row); D111
 * retires that pairing knowingly: the difference is <i>epistemic, not semantic</i> — absence is a
 * schema fact decidable before a row is read, blankness a data fact that needs the rows — so the
 * verdicts still agree while the granularity deliberately differs. The rulespec twins
 * {@code EC43-not-equal-absent-operator} / {@code EC43-absent-equals-blank-control} pin the same
 * pair corpus-side.
 * </p>
 *
 * <p>
 * D111a is the reason the fold is a feature: the corpus already stopped absent-column flooding by
 * hand (a presence gate plus a fires-once sister rule); the fold is that two-rule idiom implemented
 * once, in the level calculus.
 * </p>
 */
class AbsentColumnDatasetFoldTest
{

    /** TS with TSPARMCD populated over 4 rows; TSVAL absent. */
    private static IDataTable absent()
    {
        return MockTable.of().name("TS").col("TSPARMCD", "A", "B", "C", "D").build();
    }


    /** The same table with TSVAL present and blank on every row. */
    private static IDataTable allBlank()
    {
        return MockTable.of().name("TS").col("TSPARMCD", "A", "B", "C", "D")
                .col("TSVAL", "", "", "", "").build();
    }


    private static CheckCondition expression(String text)
    {
        return new CheckConditionExpression(CheckExpressionParser.parse(text), text);
    }


    private static Rule rule(String id, CheckCondition check)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId(id);
        rule.setCore(core);
        rule.setCheck(check);
        rule.setSensitivity(Sensitivity.RECORD);
        Outcome outcome = new Outcome();
        outcome.setMessage("m");
        outcome.setOutputVariables(List.of());
        rule.setOutcome(outcome);
        net.cumba.corej.core.RulePackageLoader.installNativeExpr(rule);
        return rule;
    }


    @Test
    @DisplayName("a firing negative over an ABSENT column reports ONE dataset-level finding")
    void absentNegativeFoldsToOneDatasetFinding()
    {
        Rule negative = rule("D111-NEG", expression("TSVAL != \"PLANNED\""));

        RuleExecutionResult result = RuleRunner.execute(negative, absent());
        assertTrue(result.hasViolations(), "missing != populated literal fires");
        assertEquals(1, result.getViolationCount(),
                "the absent-column fact is a dataset fact and reports once (D111/D39)");
    }


    @Test
    @DisplayName("the SAME leaf over a present-but-all-blank column keeps its per-row findings")
    void allBlankPresentColumnStaysPerRow()
    {
        Rule negative = rule("D111-BLANK", expression("TSVAL != \"PLANNED\""));

        RuleExecutionResult result = RuleRunner.execute(negative, allBlank());
        assertEquals(4, result.getViolationCount(),
                "blankness is a data fact: the row path answers, one finding per row — the ruled"
                        + " asymmetry (D111: epistemic, not semantic)");
    }


    @Test
    @DisplayName("a non-firing positive over an ABSENT column folds FALSE — no findings, no rows read")
    void absentPositiveFoldsFalse()
    {
        Rule positive = rule("D111-POS", expression("len(TSVAL) > 8"));

        RuleExecutionResult result = RuleRunner.execute(positive, absent());
        assertEquals(0, result.getViolationCount(),
                "len(missing) is 0, the CDISC-CG0149 shape: dataset-decided FALSE");
    }


    @Test
    @DisplayName("a row-level sibling keeps the check on the row path — the fold takes only all-absent checks")
    void mixedConjunctionStaysOnTheRowPath()
    {
        // and(row-leaf, absent-TRUE-leaf) is record-level (finest of the operands): the absent
        // conjunct folds TRUE but the Kleene AND stays UNKNOWN, so the row path selects exactly
        // the rows the sibling selects — no flooding and no lost selectivity.
        Rule mixed = rule("D111-MIXED", expression("TSPARMCD == \"B\" and TSVAL != \"PLANNED\""));

        RuleExecutionResult result = RuleRunner.execute(mixed, absent());
        assertEquals(1, result.getViolationCount(), "exactly the TSPARMCD == B row");
        assertEquals(1L, result.getViolations().getFirst().getRow());
    }
}
