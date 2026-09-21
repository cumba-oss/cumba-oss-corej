package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.RuleDefinitionException;
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
            .col("D", "2020-01-01", "2020-01", "", "2020-01-01T09:15", "2020-02-29")
            .col("TM", "09:15", "09", "", "09:15:30", "23:59").build();

    private static final int ROWS = 5;

    private static Vector dates()
    {
        return VectorLayerTest.col(T, "D");
    }


    private static Vector times()
    {
        return VectorLayerTest.col(T, "TM");
    }


    /**
     * The construction pin: for every (cell, member) pair, temporal membership over the singleton
     * {@code {member}} answers exactly what {@link Primitives#dateComparison} answers for
     * {@code cell == member}. ⛔ This is the property that makes "membership IS a disjunction of
     * equality" true for the temporal family by construction rather than by assertion.
     *
     * <p>
     * ⚠⚠ <b>The invariant is ISO-SHAPED MEMBERS ONLY, and review round 1 was right to say the
     * original wording overclaimed.</b> {@link Primitives#dateComparison} passes
     * {@code mixedVerdict = true} to {@code compareCells} and {@link Primitives#isTemporalMember}
     * passes {@code false}, so on a malformed mixed numeric/ISO pair the two DELIBERATELY differ —
     * the operator answers "this row is a finding", membership answers "not a member". That
     * divergence is pinned by {@link #theMixedShapeVerdictDivergesFromTheOperatorOnPurpose()}
     * rather than hidden by a fixture that cannot reach it.
     * </p>
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
        return evaluate(expression, Map.of());
    }


    private static BitSet evaluate(String expression, Map<String, Object> variables)
    {
        return ExprCompiler.compile(CheckExpressionParser.parse(expression))
                .evaluate(EvalRun.fullRange(EvaluationContext.builder().table(T)
                        .variables(new java.util.LinkedHashMap<>(variables))
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


    /**
     * ⛔⛔ <b>The narrowing, pinned.</b> The temporal arm takes a <b>list literal</b> only. Its first
     * spelling took every right-hand side, which was a latent regression in two shapes
     * {@code buildSet} cannot serve: a {@code ${*}} wildcard set (it would have thrown
     * {@code unsupported} where {@code wildcardMembershipPlan} answered) and a {@code $}-ref
     * resolving to a per-row {@code GroupedResult} (it would have substituted an EMPTY set for a
     * per-row one).
     *
     * <p>
     * This pins the {@code $}-ref half: a temporal probe against a {@code $}-bound list keeps its
     * <b>textual</b> behaviour, so the datetime does <em>not</em> match the date. ⚠ That is a
     * stated GAP, not an accident — closing it needs a temporal counterpart to
     * {@code groupedMembership}, not a wider condition on this branch. The test exists so the gap
     * is visible and so widening the branch without that counterpart reds here.
     * </p>
     */
    @Test
    void aTemporalProbeAgainstADynamicSetStaysTextual()
    {
        BitSet fires = evaluate("date(D) in $dates", Map.of("$dates", List.of("2020-01-01T09:15")));
        assertFalse(fires.get(0),
                "row 0 (2020-01-01) must NOT match through a $-bound set — the temporal arm is"
                        + " deliberately restricted to a list literal");
        assertTrue(fires.get(3), "row 3 IS the literal text, so the textual arm matches it");
    }


    /**
     * ⛔⛔ <b>Review round 1, finding 2 — the counterexample the construction pin's fixture could not
     * reach.</b> A plainly non-ISO member drives {@code compareCells} into its mixed numeric/ISO
     * arm, where the verdict is the CALLER's: the operator passes {@code true} (a malformed row is
     * a finding regardless of direction), membership passes {@code false} (a malformed pair is
     * simply not a member, so {@code not in} still fires). Asserted here so the gap is stated
     * rather than avoided.
     */
    @Test
    void theMixedShapeVerdictDivergesFromTheOperatorOnPurpose()
    {
        BitSet operator = Primitives.dateComparison(dates(), ConstVector.of("17"), ROWS, 0, false,
                false);
        assertTrue(operator.get(0), "the date OPERATOR reports a malformed mixed pair");
        assertFalse(Primitives.isTemporalMember(dates().value(0).cell(), Set.of("17")),
                "MEMBERSHIP answers 'not a member' for the same pair — the deliberate divergence");
    }


    /**
     * ⛔⛔ <b>Review round 1, finding 1 — the HIGH.</b> A {@code time()}-marked probe was routed
     * through the DATE comparator, whose {@code isoComponents} length gate rejects {@code "09:15"}
     * outright, so {@code in} answered <b>false for an exact match</b> and {@code not in} fired on
     * every row — a dataset-wide flood, and a live divergence from {@code ==}.
     */
    @Test
    void aTimeProbeUsesTheTimeComparatorNotTheDateOne()
    {
        assertTrue(Primitives.isTimeMember(times().value(0).cell(), Set.of("09:15")),
                "09:15 is exactly 09:15 — this answered FALSE through the date comparator");
        assertFalse(Primitives.isTemporalMember(times().value(0).cell(), Set.of("09:15")),
                "⚠ the DATE arm must still reject it — otherwise this test is not measuring the"
                        + " time arm at all");
    }


    /** The time arm's own construction pin, against {@link Primitives#timeComparison}. */
    @Test
    void timeMembershipOverASingletonIsExactlyTimeEquality()
    {
        for (String member : List.of("09:15", "09", "09:15:30", "23:59", ""))
        {
            BitSet eq = Primitives.timeComparison(times(), ConstVector.of(member), ROWS, 0, false,
                    false);
            for (int row = 0; row < ROWS; row++)
            {
                boolean in = Primitives.isTimeMember(times().value(row).cell(), Set.of(member));
                assertEquals(eq.get(row), in, "TM[" + row + "] vs \"" + member + "\"");
            }
        }
    }


    /** End to end: the compiler routes a {@code time()} probe to the time arm. */
    @Test
    void theCompilerRoutesATimeProbeToTheTimeArm()
    {
        BitSet fires = evaluate("time(TM) in [time(\"09:15\")]");
        assertTrue(fires.get(0), "exact match must be a member");
        assertFalse(fires.get(2), "blank is a member of nothing");
    }


    /**
     * ⛔ <b>Review round 1, finding 5.</b> The temporal branch used to return before
     * {@code numericMemberSet} ran, which took Q3's mixed-list LOAD ERROR with it. Moving the
     * branch after the numeric classification restores it.
     */
    @Test
    void aMixedLiteralListIsStillRejectedUnderATemporalProbe()
    {
        RuleDefinitionException thrown = assertThrows(RuleDefinitionException.class,
                () -> evaluate("date(D) in [1, \"A\"]"));
        assertTrue(thrown.getMessage().contains("mixes numeric and string"),
                "Q3's rejection must survive the temporal branch: " + thrown.getMessage());
    }
}
