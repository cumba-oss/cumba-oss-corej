package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * ⭐ Phase 1a of {@code plans/PLAN-membership-as-equality.md} — <b>the temporal arm of D81's
 * {@code in}-is-a-disjunction-of-{@code ==}</b>.
 *
 * <p>
 * {@link Primitives#isMember} has a textual and a numeric arm and had no temporal one: {@code ==}
 * reaches the interval hull rule through a separate compile path ({@code ExprCompiler.compileDate})
 * that membership — selected on the {@code IN}/{@code NOT_IN} op before any temporal typing — could
 * never enter. {@link Primitives#temporalMembership} closes that, and this class pins it the way
 * {@link InLowersIntoEqualityTest} pins the scalar arms: <b>membership over a singleton answers
 * exactly what the temporal comparison answers for the same pair</b>, so a future change to the
 * hull rule that is not mirrored here reds this test instead of silently re-opening a second
 * comparison path.
 * </p>
 *
 * <p>
 * ⚠⚠ <b>This arm is LATENT on the shipped corpus and that is recorded deliberately.</b> Measured
 * 2026-09-21: <b>zero</b> of the corpus's 464 membership occurrences carry a temporally-marked
 * probe (verified twice, the second time as a superset over every expression containing both a
 * membership and a {@code date}/{@code time} marker — 12 hits, all with the marker in a different
 * conjunct). So no shipped rule's verdict moves, a corpus differential has nothing to measure, and
 * <b>these unit tests are the whole evidence</b>. A zero delta downstream is the expected result,
 * never a pass.
 * </p>
 */
class TemporalMembershipTest
{

    /** A date-declared column carrying the shapes the hull rule branches on. */
    private static final IDataTable T = MockTable.of()
            .col("D", "2020-01-01", "2020-01", "", "2020-01-01T09:15", "2020-02-29").build();

    private static final int ROWS = 5;

    private static Vector dates()
    {
        return VectorLayerTest.col(T, "D");
    }


    /**
     * The construction pin: for every (cell, member) pair, temporal membership over the singleton
     * {@code {member}} answers exactly what {@link Primitives#dateComparison} answers for
     * {@code cell == member}. ⛔ This is the property that makes "membership IS a disjunction of
     * equality" true for the temporal family by construction rather than by assertion.
     */
    @Test
    void temporalMembershipOverASingletonIsExactlyTemporalEquality()
    {
        for (String member : List.of("2020-01-01", "2020-01-01T09:15", "2020-01", "2020-01-15",
                "2020-02-29", ""))
        {
            BitSet eq = Primitives.dateComparison(dates(), ConstVector.of(member), ROWS, 0, false,
                    false);
            for (int row = 0; row < ROWS; row++)
            {
                boolean in = Primitives.isTemporalMember(dates().value(row).cell(), Set.of(member));
                assertEquals(eq.get(row), in, "D[" + row + "] vs \"" + member + "\"");
            }
        }
    }


    /**
     * ⭐ The headline fix, and its own sensitivity control. A complete date and a datetime of the
     * same day are compared at the coarser core precision, so they are the SAME point and the probe
     * is a member — while the textual arm {@link Primitives#isMember} runs answers {@code false}
     * for the identical pair. The second assertion is what proves this test would have failed
     * before the arm existed.
     */
    @Test
    void aDateMatchesADatetimeOfTheSameDayWhereTextDoesNot()
    {
        Vector col = dates();
        assertTrue(Primitives.isTemporalMember(col.value(0).cell(), Set.of("2020-01-01T09:15")),
                "2020-01-01 is the same point as 2020-01-01T09:15 at day precision");
        assertFalse(Primitives.isMember(col.value(0).cell(), Set.of("2020-01-01T09:15"), false),
                "⚠ the TEXTUAL arm must still answer false — otherwise this test is not measuring"
                        + " the temporal arm at all");
    }


    /**
     * A truncated partial is a span, not a point, so it is not equal to a day inside it —
     * {@code samePoint} over the clipped hulls, which is the rule {@code date(A) == date(B)}
     * already applies. ⛔ Not a bug: membership inherits the hull rule whole, including the parts
     * that answer false.
     */
    @Test
    void aPartialDateIsNotAMemberOfADayInsideIt()
    {
        assertFalse(Primitives.isTemporalMember(dates().value(1).cell(), Set.of("2020-01-15")),
                "2020-01 is a month-long hull, not the point 2020-01-15");
    }


    /** The ∃ definition: any one matching member suffices, and order does not matter. */
    @Test
    void anyMatchingMemberSuffices()
    {
        assertTrue(Primitives.isTemporalMember(dates().value(0).cell(),
                Set.of("1999-12-31", "2020-01-01T09:15", "2021-06-01")));
        assertFalse(Primitives.isTemporalMember(dates().value(0).cell(),
                Set.of("1999-12-31", "2021-06-01")));
    }


    /**
     * D13's membership limb reaches the temporal arm too — and it is not written twice: it falls
     * out of {@link Primitives#compareCells}'s own missing short-circuit.
     */
    @Test
    void aBlankCellIsAMemberOfNothing()
    {
        assertFalse(Primitives.isTemporalMember(dates().value(2).cell(), Set.of("2020-01-01")));
        assertFalse(Primitives.isTemporalMember(dates().value(2).cell(), Set.of("")));
    }


    /** The vectorised form, both polarities, over the whole column. */
    @Test
    void theVectorisedFormNegatesTheWholeVerdict()
    {
        Set<String> members = Set.of("2020-01-01");
        BitSet in = Primitives.temporalMembership(dates(), members, ROWS, false);
        BitSet notIn = Primitives.temporalMembership(dates(), members, ROWS, true);
        for (int row = 0; row < ROWS; row++)
        {
            assertEquals(!in.get(row), notIn.get(row), "row " + row + " must invert");
        }
        assertTrue(in.get(0), "2020-01-01 is a member of {2020-01-01}");
        assertTrue(in.get(3), "2020-01-01T09:15 is the same day-precision point");
    }


    private static BitSet evaluate(String expression)
    {
        return ExprCompiler.compile(CheckExpressionParser.parse(expression))
                .evaluate(EvalRun.fullRange(EvaluationContext.builder().table(T)
                        .joinedDatasets(Map.of()).evaluationDomain(Domain.ROW).build()));
    }


    /**
     * ⭐⭐ <b>END TO END — the primitive tests above prove the ARM works; this proves the COMPILER
     * REACHES IT.</b> Without this, every assertion above could pass while
     * {@code compileMembership} still routed a date probe to the textual path, which is exactly the
     * shape of "an instrument placed downstream of the thing it is measuring".
     *
     * <p>
     * It also covers the second half of phase 1a that is invisible from {@link Primitives}: a
     * member written {@code date("…")} must contribute its ISO text. Before {@code setTerm}
     * stripped the conversion, {@code literalText} reached a {@code Call} and threw <em>"expected a
     * literal or reference operand"</em> — so a date list literal did not merely compare as text,
     * it did not BUILD.
     * </p>
     */
    @Test
    void theCompilerRoutesADateProbeToTheTemporalArm()
    {
        BitSet fires = evaluate("date(D) in [date(\"2020-01-01T09:15\")]");
        assertTrue(fires.get(0),
                "row 0 (2020-01-01) must match the datetime member at day precision — if it does"
                        + " not, the compiler routed to the TEXTUAL arm");
        assertTrue(fires.get(3), "row 3 is the member itself");
        assertFalse(fires.get(1), "row 1 (2020-01, a month-long hull) is not that point");
        assertFalse(fires.get(2), "row 2 is blank — a member of nothing");
    }


    /**
     * The negative spelling inverts through the same route. ⚠⚠ <b>The member is the DATETIME on
     * purpose.</b> Written against {@code date("2020-01-01")} this test passed with the temporal
     * routing sabotaged — both arms agree on that pair, so it measured nothing. Measured, not
     * reasoned: the sabotage run redded 1 of 8, and this was one of the 7 that did not notice.
     * Against the datetime member the two arms DISAGREE on row 0, which is what makes the assertion
     * discriminating.
     */
    @Test
    void theCompilerRoutesNotInToo()
    {
        BitSet fires = evaluate("date(D) not in [date(\"2020-01-01T09:15\")]");
        assertFalse(fires.get(0),
                "2020-01-01 IS a day-precision member of {2020-01-01T09:15}, so `not in` must not"
                        + " fire — the TEXTUAL arm would fire here");
        assertTrue(fires.get(1), "2020-01 is not that point, so `not in` fires");
    }
}
