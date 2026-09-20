package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.BitSet;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * ⭐⭐ The D96c boundary, pinned (phase 3b of {@code PLAN-typed-expression-engine}) — 41 of Review
 * 1b's verdicts rest on which side of it a valueless date operand falls:
 *
 * <ul>
 * <li><b>A positionally-unreadable STRING takes SPEC §5.2(4)</b> — unbounded hull, all six
 * <b>predicates</b> answer {@code false}. That covers junk ({@code UNKNOWN}), a calendar-impossible
 * date ({@code 2026-02-30}), a masked form ({@code ----06-15}), and a <b>right</b> operand that
 * resolves to {@code ""} through a channel that keeps it a string. ⭐ <b>H1b (2026-09-17):
 * {@code negate} then flips it</b>, so {@code date_not_equal_to} <b>fires</b> on every one of those
 * and only the five un-negated operators stay false.</li>
 * <li><b>A genuine {@code MissingValue} takes the missing short-circuit</b> (§4(3)'s stand-in for
 * equality: {@code !=} fires, {@code ==} is silent) — a missing numeric cell, a {@code null}
 * operation result (Review 0 C4).</li>
 * <li>⚠ <b>A blank <b>left</b> cell of a primary column folds to missing</b> (the F3 fold — the
 * absent-column contract, {@code Primitives.dateComparison}'s documented short-circuit), so it
 * takes the negate path rather than the unbounded hull. ⭐ This was filed as "the engine's one
 * deviation from §5.2(4), frozen deliberately"; H1b UNFROZE it by moving everything else to meet
 * it, so the two paths now agree on every operator instead of only on five.</li>
 * <li>⚠⚠ <b>The conversion that crosses the boundary</b> (D96b): {@code earliest_possible} /
 * {@code latest_possible} answer a genuine <b>missing</b> for an unpositionable input
 * ({@code BuiltinFunctions.hullBound} → {@code null}), which is why the same blank cell is safe in
 * a rule's ERROR check ({@code date(A) != B}: all-false / negate) and <b>fires</b> in its INFO
 * check once wrapped ({@code != earliest_possible(A)}: null operand → negate). Deliberate, and
 * invisible until it fires — hence pinned.</li>
 * </ul>
 */
class DateMissingBoundaryD96cTest
{

    private static BitSet eval(String expr, EvaluationContext ctx)
    {
        return NativeExprEvaluator.evaluate(CheckExpressionParser.parse(expr), ctx);
    }


    private static BitSet bits(int... set)
    {
        BitSet b = new BitSet();
        for (int i : set)
        {
            b.set(i);
        }
        return b;
    }


    private static EvaluationContext ctxOf(IDataTable t)
    {
        return EvaluationContext.builder().table(t).build();
    }


    /**
     * ⭐ <b>H1b re-expectation (owner, 2026-09-17).</b> §5.2(4)'s "false for all six" is the verdict
     * of the six <b>predicates</b>; {@code !=} is the negated operator and now <b>fires</b> on an
     * unpositionable operand, in <b>either</b> position. Only the five un-negated operators still
     * answer {@code false}. This test used to assert {@code bits()} for all six, which is the
     * behaviour the ruling replaced.
     */
    @Test
    void unreadableStringRightOperand_fivePredicatesFalseAndNotEqualFires()
    {
        // The LEFT cell holds a real date; the RIGHT operand is positionally unreadable.
        IDataTable t = MockTable.of().name("AE").col("A", "2012-06-15", "2012-06-15", "2012-06-15")
                .col("B", "UNKNOWN", "2026-02-30", "----06-15").build();
        EvaluationContext c = ctxOf(t);
        for (String op : new String[]
        {
                "==", "<", "<=", ">", ">="
        })
        {
            assertEquals(bits(), eval("date(A) " + op + " date(B)", c), op);
        }
        assertEquals(bits(0, 1, 2), eval("date(A) != date(B)", c),
                "H1b: a right-hand unpositionable operand now answers like a left-hand one");
    }


    @Test
    void unreadableStringLeftOperand_fivePredicatesFalseAndNotEqualFires()
    {
        // The mirror, and the half the H1b filing did not know about: a NON-BLANK unreadable LEFT
        // cell never reached compareCells' isMissing short-circuit either (isMissing is
        // missing-OR-EMPTY), so it fell to the hull and was silent under != while a BLANK left
        // cell fired. Both asymmetries close at the same line.
        IDataTable t = MockTable.of().name("AE").col("A", "UNKNOWN", "2026-02-30", "----06-15")
                .col("B", "2012-06-15", "2012-06-15", "2012-06-15").build();
        EvaluationContext c = ctxOf(t);
        for (String op : new String[]
        {
                "==", "<", "<=", ">", ">="
        })
        {
            assertEquals(bits(), eval("date(A) " + op + " date(B)", c), op);
        }
        assertEquals(bits(0, 1, 2), eval("date(A) != date(B)", c),
                "…and a masked/junk LEFT operand now fires exactly as a BLANK one always did");
    }


    /**
     * ⛔ <b>The control that keeps H1b from overshooting</b>: a <em>bounded</em> partial is NOT
     * unpositionable. {@code 2020} has the hull {@code [2020-01-01, 2020-12-31]}, which is not
     * disjoint from {@code 2020-06-15}, so {@code !=} stays <b>silent</b> — while the operand one
     * row down, masked to unreadability, fires. If the arm were keyed on "not a complete date"
     * rather than on "no hull bound", this row would move.
     */
    @Test
    void aBoundedPartialIsNotUnpositionableAndDoesNotFire()
    {
        IDataTable t = MockTable.of().name("AE").col("A", "2020", "2020-06", "----06-15")
                .col("B", "2020-06-15", "2020-06-15", "2020-06-15").build();
        EvaluationContext c = ctxOf(t);
        assertEquals(bits(2), eval("date(A) != date(B)", c),
                "only the unbounded row fires; the two bounded partials overlap B and stay silent");
        assertEquals(bits(), eval("date(A) == date(B)", c),
                "== is unmoved for all three: a partial is not a point, junk has no hull");
    }


    @Test
    void genuineMissingTakesTheMissingShortCircuit()
    {
        // A missing NUMERIC cell is a genuine MissingValue (MockTable.colLong null): the
        // operator's missing short-circuit answers negate — != fires, == is silent. This is
        // §4(3)'s equality face (missing is not equal to any present value), and it is exactly
        // today's shipped behaviour (Review 0 C4).
        IDataTable t = MockTable.of().name("AE").colLong("A", null, 19000L)
                .colLong("B", 19000L, 19000L).build();
        EvaluationContext c = ctxOf(t);
        assertEquals(bits(0), eval("date(A) != date(B)", c));
        assertEquals(bits(1), eval("date(A) == date(B)", c));
    }


    @Test
    void blankLeftCellFoldsToMissingNotToTheHull()
    {
        // ⚠ The frozen deviation from §5.2(4) as written: a blank LEFT cell short-circuits to
        // negate (the F3 fold / absent-column contract), so date(A) != B FIRES where the
        // unbounded-hull reading would stay silent. Review 1b measured its populations against
        // this; the true MissingValue-vs-"" runtime split is the phase-3d rewrite.
        IDataTable t = MockTable.of().name("AE").col("A", "", "2012-06-15")
                .col("B", "2012-06-15", "2012-06-15").build();
        EvaluationContext c = ctxOf(t);
        assertEquals(bits(0), eval("date(A) != date(B)", c));
        assertEquals(bits(1), eval("date(A) == date(B)", c));
    }


    @Test
    void hullBoundConvertsUnpositionableToGenuineMissing()
    {
        // ⚠⚠ D96b's boundary crossing: earliest_possible/latest_possible answer MISSING for an
        // unpositionable input — junk, masked, blank and missing all land on the same side once
        // wrapped, while a positionable value (complete or partial) keeps a bound.
        IDataTable t = MockTable.of().name("AE")
                .col("A", "UNKNOWN", "----06-15", "", "2012-06-15", "2012-06").build();
        EvaluationContext c = ctxOf(t);
        assertEquals(bits(0, 1, 2), eval("empty(earliest_possible(A))", c));
        assertEquals(bits(0, 1, 2), eval("empty(latest_possible(A))", c));
    }


    /**
     * ⭐⭐ <b>H1b CLOSES D96b's split for {@code !=}</b>, and that is the point of keeping this test
     * rather than deleting it. It used to pin <i>"same cell, opposite verdicts, by design"</i>: a
     * direct comparison against a junk/blank right operand was silent while the same operand
     * wrapped in {@code earliest_possible} fired, because the wrapper converts an unpositionable
     * value into a genuine {@link net.cumba.datatable.values.MissingValue}. The direct half now
     * fires too, so the two agree for {@code !=}.
     *
     * <p>
     * ⚠ The crossing itself is NOT closed — it is unobservable <em>through this operator</em> only.
     * The four order operators still read the two sides differently (D34 #5 sorts a
     * {@code MissingValue} low; an unbounded hull answers false), which is what the second pair of
     * assertions pins, and {@code empty()} still separates them
     * ({@link #hullBoundConvertsUnpositionableToGenuineMissing}).
     * </p>
     */
    @Test
    void theDirectAndWrappedFormsNowAgreeForNotEqualButNotForOrder()
    {
        IDataTable t = MockTable.of().name("AE").col("A", "2012-06-15", "2012-06-15")
                .col("B", "UNKNOWN", "").build();
        EvaluationContext c = ctxOf(t);
        assertEquals(bits(0, 1), eval("date(A) != date(B)", c),
                "direct: H1b — an unpositionable right operand answers negate, so != fires");
        assertEquals(bits(0, 1), eval("date(A) != earliest_possible(B)", c),
                "wrapped: the bound of an unpositionable value is MISSING, and != fires on it");
        assertEquals(bits(), eval("date(A) < date(B)", c),
                "order, direct: an unbounded hull is still false for all four order operators");
        assertEquals(bits(), eval("date(A) < earliest_possible(B)", c),
                "order, wrapped: a MissingValue sorts LOW (D34 #5), so A < MISSING is false too");
    }

}
