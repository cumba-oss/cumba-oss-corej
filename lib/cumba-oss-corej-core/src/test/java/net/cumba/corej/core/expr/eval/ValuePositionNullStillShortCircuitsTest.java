package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.BitSet;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * EC-43 §4.2 — <b>the value-position null keeps its guard, by design</b>, and this test states the
 * consequence out loud so nobody has to rediscover it.
 *
 * <p>
 * The fold is threaded into the <em>target</em> operand only. A comparison's right-hand side goes
 * through {@code valuePlan} &rarr; the two-argument {@code valueCallPlan}, whose argument plans are
 * built with {@code foldAbsentColumn = false}; an absent column there still yields {@code null},
 * {@code valueCallPlan} propagates it outward ("missing column propagates"), and the enclosing
 * comparison short-circuits to an empty {@link BitSet}. Those {@code v == null} branches are
 * therefore <b>live</b>, not dead code left behind by the fix — which is exactly why §4.2 kept all
 * of them.
 * </p>
 *
 * <p>
 * <b>The scope limit:</b> in VALUE position, absent still does <em>not</em> equal blank. With
 * {@code TSVALREF} present and blank, {@code substring(TSVALREF, 1, 2)} is {@code ""} and
 * {@code TSPARMCD != ""} fires; with {@code TSVALREF} absent the whole leaf yields nothing. That is
 * a documented boundary of EC-43, not a bug: extending the fold to the value side would make an
 * unresolved {@code $}-operation and a never-merged join column indistinguishable from an absent
 * data column, which is the D4 contract the fold deliberately preserves.
 * </p>
 */
class ValuePositionNullStillShortCircuitsTest
{

    /** TS carrying TSPARMCD and an IDVAR-style pointer column, but no TSVALREF. */
    private static IDataTable absent()
    {
        return MockTable.of().name("TS").col("TSPARMCD", "PLANSUB", "PLANSUB").build();
    }


    /** The same table with TSVALREF present and blank on every row. */
    private static IDataTable blank()
    {
        return MockTable.of().name("TS").col("TSPARMCD", "PLANSUB", "PLANSUB")
                .col("TSVALREF", "", "").build();
    }


    private static BitSet eval(String expression, IDataTable table)
    {
        return NativeExprEvaluator.evaluate(CheckExpressionParser.parse(expression),
                EvaluationContext.builder().table(table).build());
    }


    @Test
    @DisplayName("X != colref(ABSENT) short-circuits — the value-position null survives the fold")
    void colrefOverAbsentColumnShortCircuits()
    {
        assertEquals(new BitSet(), eval("TSPARMCD != colref(TSVALREF)", absent()),
                "colref's first hop is an argument plan built with foldAbsentColumn=false, so an "
                        + "absent column still yields null and the comparison yields no rows");
    }


    @Test
    @DisplayName("X != substring(ABSENT, 1, 2) short-circuits for the same reason")
    void substringOverAbsentColumnShortCircuits()
    {
        assertEquals(new BitSet(), eval("TSPARMCD != substring(TSVALREF, 1, 2)", absent()));
    }


    @Test
    @DisplayName("and so absent != blank in VALUE position — the documented scope limit")
    void absentAndBlankDisagreeInValuePosition()
    {
        BitSet onBlank = eval("TSPARMCD != substring(TSVALREF, 1, 2)", blank());
        assertEquals(2, onBlank.cardinality(),
                "with the column present and blank the substring is \"\" and PLANSUB differs "
                        + "from it, so every row fires");
        assertNotEquals(onBlank, eval("TSPARMCD != substring(TSVALREF, 1, 2)", absent()),
                "EC-43's absent-equals-blank contract covers the TARGET operand only; the value "
                        + "side keeps the missing-column short-circuit (§4.2). Pinned so the "
                        + "boundary is a decision, not a surprise");
    }
}
