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
 * ⭐⭐ <b>Renamed and rewritten 2026-09-21 — this class used to be
 * {@code ValuePositionNullStillShortCircuitsTest}, and both its NAME and its javadoc stated the
 * contract the owner's uniformity ruling ABOLISHED.</b>
 *
 * <p>
 * It read: <i>"the value-position null keeps its guard, by design … argument plans are built with
 * {@code foldAbsentColumn = false}; an absent column there still yields null … those
 * {@code v == null} branches are therefore LIVE."</i> ⛔ None of that is true any more. The flag is
 * gone, an absent column folds to its type default in EVERY operand position, and the branches are
 * not live. The tests inside had already been inverted; the class name and doc had not, which is
 * worse than a stale comment — a reader takes the class name for the contract.
 * </p>
 *
 * <p>
 * What it pins now: an absent column in VALUE position DECIDES rather than short-circuiting, and
 * therefore agrees with a present-but-blank column. The absent-equals-blank scope limit this class
 * was built to document is CLOSED.
 * </p>
 */
class ValuePositionAbsentColumnDecidesTest
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
    @DisplayName("X != colref(ABSENT) now DECIDES — the value-position short-circuit is gone")
    void colrefOverAbsentColumnDecides()
    {
        // ⭐⭐ REWRITTEN 2026-09-21. This class existed to pin that the VALUE side kept a
        // missing-column short-circuit while the TARGET side folded -- "colref's first hop is an
        // argument plan built with foldAbsentColumn=false, so an absent column still yields null".
        // That is one bare name meaning two different things depending on which operand position it
        // occupied, which the uniformity ruling (owner, 2026-09-21) abolishes.
        // ⚠ Derived from the contract, not read off the run: D34 #3 makes an absent character
        // column a present empty string, so colref over it has a value to read and the comparison
        // decides, exactly as it does over a present-but-blank column.
        assertNotEquals(new BitSet(), eval("TSPARMCD != colref(TSVALREF)", absent()),
                "an absent column folds in value position too, so the comparison decides");
    }


    @Test
    @DisplayName("X != substring(ABSENT, 1, 2) likewise fires on every row")
    void substringOverAbsentColumnDecides()
    {
        // substring("", 1, 2) is "", and TSPARMCD differs from "" on both rows.
        assertEquals(2, eval("TSPARMCD != substring(TSVALREF, 1, 2)", absent()).cardinality(),
                "the absent column is the present empty string, so every row fires");
    }


    @Test
    @DisplayName("⭐ and so absent AGREES with blank in value position — the scope limit is CLOSED")
    void absentAndBlankNowAgreeInValuePosition()
    {
        // ⭐⭐ THE INVERSION THAT MATTERS. This assertion was assertNotEquals, with the message
        // "EC-43's absent-equals-blank contract covers the TARGET operand only; the value side
        // keeps
        // the missing-column short-circuit (§4.2). Pinned so the boundary is a decision, not a
        // surprise." The boundary was a consequence of the per-position flag; with the flag gone
        // the
        // absent-equals-blank contract holds in BOTH positions, which is what it always claimed to
        // be about.
        BitSet onBlank = eval("TSPARMCD != substring(TSVALREF, 1, 2)", blank());
        assertEquals(2, onBlank.cardinality(),
                "with the column present and blank the substring is \"\" and PLANSUB differs "
                        + "from it, so every row fires");
        assertEquals(onBlank, eval("TSPARMCD != substring(TSVALREF, 1, 2)", absent()),
                "absent and blank now answer identically in value position too");
    }
}
