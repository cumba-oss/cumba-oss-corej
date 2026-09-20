package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RuleRunner#collectCheckLeafColumns} — the Output_Variables inference used
 * when a rule declares no {@code Outcome.Output_Variables}, mirroring Python's
 * {@code RuleProcessor._extract_targets_from_conditions}. Covers the three parity rules:
 * negated-existence targets contribute nothing; ordinary targets contribute their column;
 * {@code additional_columns_*} expand to dataset columns matching {@code ^<name>\d+$}.
 *
 * <p>
 * Phase 7d (D121): exercised through {@link CheckConditionExpression} — the only Check shape left —
 * so these are also the vacuity control for the {@code Expr}-side walker: if the expression arm of
 * the inference ever silently contributes nothing (the phase-7a defect shape), every assertion here
 * goes red instead of green-by-emptiness.
 * </p>
 */
class CollectCheckLeafColumnsTest
{

    private static CheckConditionExpression expr(String source)
    {
        return new CheckConditionExpression(CheckExpressionParser.parse(source), source);
    }


    private static DataTableMeta meta(String... cols)
    {
        MockTable t = MockTable.of();
        for (String c : cols)
        {
            t.col(c, "x");
        }
        return t.name("LB").build().getMetaData();
    }


    @Test
    void skipsNotExistsTargets()
    {
        // MS exists AND MB not_exists -> only MS (Python skips not_exists targets).
        var check = new CheckConditionAll(
                List.of(expr("var_exists(MS)"), expr("var_not_exists(MB)")));
        assertEquals(List.of("MS"),
                List.copyOf(RuleRunner.collectCheckLeafColumns(check, meta("MS"))));
    }


    @Test
    void ordinaryTargetsContributeNameDeduped()
    {
        var check = new CheckConditionAll(List.of(expr("not empty(TRTP)"), expr("TRTP != \"X\""),
                expr("var_exists(USUBJID)")));
        assertEquals(List.of("TRTP", "USUBJID"),
                List.copyOf(RuleRunner.collectCheckLeafColumns(check, meta("TRTP", "USUBJID"))));
    }


    @Test
    void additionalColumnsExpandToNumberedSiblings()
    {
        // additional_columns_empty(TSVAL) -> TSVAL1, TSVAL2 (matching ^TSVAL\d+$), NOT bare TSVAL.
        var check = new CheckConditionAll(List.of(expr("additional_columns_empty(TSVAL)")));
        assertEquals(List.of("TSVAL1", "TSVAL2"), List.copyOf(RuleRunner
                .collectCheckLeafColumns(check, meta("TSVAL", "TSVAL1", "TSVAL2", "TSVALCD"))));
    }


    @Test
    void comparisonRightOperandContributesNothing()
    {
        // Only the LEFT operand of a comparison is the projection target (the leaf's name slot).
        var check = new CheckConditionAll(List.of(expr("AGE > MAXAGE")));
        assertEquals(List.of("AGE"),
                List.copyOf(RuleRunner.collectCheckLeafColumns(check, meta("AGE", "MAXAGE"))));
    }
}
