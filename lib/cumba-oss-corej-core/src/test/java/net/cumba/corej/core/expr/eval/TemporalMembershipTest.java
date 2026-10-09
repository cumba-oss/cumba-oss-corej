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
            .col("TM", "09:15", "09", "", "09:15:30", "23:59")
            .col("S", "2020-01-01T09:15,1999-01-01", "2020-01-15", "2020-01-01", "",
                    "2020-02-29T23:00")
            .build();

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
                        .evaluationDomain(Domain.ROW).build()));
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
     * ⭐ <b>The pin, flipped</b> ({@code PLAN-engine-gap-temporal-membership} §5, owner Q1
     * 2026-10-07 "yes fix"; runbook W2 TM1). It pinned a stated gap: a temporal probe against a
     * {@code $}-bound set compared TEXT, so the datetime did not match the date of the same day
     * while the list literal three lines up said it did - a silent D81 divergence. Membership now
     * follows {@code ==} for every set shape: the {@code $} set is read on its value (BS4's
     * routing) and each member compared by the hull rule.
     */
    @Test
    void aTemporalProbeAgainstADynamicSetFollowsEquality()
    {
        BitSet fires = evaluate("date(D) in $dates", Map.of("$dates", List.of("2020-01-01T09:15")));
        assertTrue(fires.get(0),
                "row 0 (2020-01-01) matches the datetime member at day precision through a"
                        + " $-bound set, as it does against the list literal");
        assertTrue(fires.get(3), "row 3 is the member itself");
        assertFalse(fires.get(1), "row 1 (2020-01, a month-long hull) is not that point");
        assertFalse(fires.get(2), "row 2 is blank - a member of nothing");
        assertEquals(evaluate("date(D) in [date(\"2020-01-01T09:15\")]"), fires,
                "the $ set answers what the literal answers");
    }


    /**
     * {@code not in} against a {@code $} set inverts through the same route, the blank included.
     */
    @Test
    void notInAgainstADynamicSetFollowsTheLiteral()
    {
        Map<String, Object> vars = Map.of("$dates", List.of("2020-01-01T09:15"));
        BitSet fires = evaluate("date(D) not in $dates", vars);
        assertFalse(fires.get(0), "2020-01-01 IS a day-precision member - `not in` must not fire");
        assertTrue(fires.get(1), "2020-01 is not that point");
        assertTrue(fires.get(2), "a blank probe is a member of nothing, so `not in` fires");
        assertEquals(evaluate("date(D) not in [date(\"2020-01-01T09:15\")]"), fires);
    }


    /**
     * A {@code time()} probe against a {@code $} set takes the TIME comparator, as the literal
     * does.
     */
    @Test
    void aTimeProbeAgainstADynamicSetUsesTheTimeComparator()
    {
        Map<String, Object> vars = Map.of("$times", List.of("09:15", "23:59:00"));
        BitSet fires = evaluate("time(TM) in $times", vars);
        assertEquals(evaluate("time(TM) in [time(\"09:15\"), time(\"23:59:00\")]"), fires);
        assertTrue(fires.get(0), "09:15 is exactly 09:15");
        assertFalse(fires.get(2), "a blank probe is a member of nothing");
    }


    /**
     * A non-ISO member is "not a member" (the deliberate divergence from the operator,
     * {@link #theMixedShapeVerdictDivergesFromTheOperatorOnPurpose()}) - so {@code not in} fires.
     */
    @Test
    void aNonIsoMemberOfADynamicSetIsNotAMember()
    {
        Map<String, Object> vars = Map.of("$s", List.of("17"));
        assertFalse(evaluate("date(D) in $s", vars).get(0));
        assertTrue(evaluate("date(D) not in $s", vars).get(0));
    }


    /**
     * BS4's routing carries the temporal arm to every value-read right-hand side: a lifted call
     * over the {@code $} set (one constant set) and a per-row list call (the row's own set) answer
     * as the bound set does.
     */
    @Test
    void aCallOnTheRightIsTemporalToo()
    {
        Map<String, Object> vars = Map.of("$dates", List.of("2020-01-01T09:15"));
        assertEquals(evaluate("date(D) in $dates", vars),
                evaluate("date(D) in upper($dates)", vars),
                "date(X) in upper($s) answers as date(X) in $s");
        // S per row: {2020-01-01T09:15, 1999-01-01} / {2020-01-15} / {2020-01-01} / blank /
        // {2020-02-29T23:00}
        BitSet perRow = evaluate("date(D) in split_by(S, \",\")");
        assertTrue(perRow.get(0), "2020-01-01 matches its row's datetime member");
        assertFalse(perRow.get(1), "2020-01 is a hull, not the day 2020-01-15");
        assertFalse(perRow.get(2), "a blank probe is a member of nothing");
        assertFalse(perRow.get(3), "a blank set holds nothing");
        assertTrue(perRow.get(4), "2020-02-29 matches 2020-02-29T23:00 at day precision");
    }


    /**
     * ⭐ The differential (§5.1): over a generated matrix of ISO-shaped member sets,
     * {@code date(D) in $s} answers exactly {@code date(D) == m1 or date(D) == m2 ...} on every
     * row, and {@code not in} its negation - D81 by construction for the dynamic set.
     */
    @Test
    void membershipInADynamicSetIsTheDisjunctionOfEquality()
    {
        List<String> pool = List.of("2020-01-01", "2020-01-01T09:15", "2020-01", "2020-01-15",
                "2020-02-29", "2020-02-29T00:00:00", "2019");
        int checked = 0;
        for (int i = 0; i < pool.size(); i++)
        {
            for (int j = i; j < pool.size(); j++)
            {
                List<String> members = i == j ? List.of(pool.get(i))
                        : List.of(pool.get(i), pool.get(j));
                StringBuilder any = new StringBuilder();
                for (String m : members)
                {
                    any.append(any.isEmpty() ? "" : " or ").append("date(D) == date(\"").append(m)
                            .append("\")");
                }
                Map<String, Object> vars = Map.of("$s", members);
                BitSet expected = evaluate(any.toString());
                assertEquals(expected, evaluate("date(D) in $s", vars), "in " + members);
                BitSet negated = (BitSet) expected.clone();
                negated.flip(0, ROWS);
                assertEquals(negated, evaluate("date(D) not in $s", vars), "not in " + members);
                checked++;
            }
        }
        assertEquals(28, checked);
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


    /**
     * ⛔⛔ <b>Review round 2, finding 1 — the same D81 divergence as round 1's HIGH, one operand
     * shape over.</b> {@code compileComparison} reads BOTH markers and {@code family(lt, rt)} falls
     * back to the RIGHT one, so {@code X == date("…")} is temporal even when the left side carries
     * no marker. Membership keyed on the probe alone, so the identical operands went textual —
     * <b>false where the comparison answers true</b> — and it loaded clean, because
     * {@code compatible(DATE, DATE)} holds. The members' shared conversion now routes too.
     */
    @Test
    void anUnmarkedProbeAgainstConvertedMembersRoutesTemporally()
    {
        BitSet fires = evaluate("D in [date(\"2020-01-01T09:15\")]");
        assertTrue(fires.get(0),
                "2020-01-01 must match the datetime member at day precision — the members' own"
                        + " date() conversion states the family, exactly as it does for `==`");
        assertFalse(fires.get(1), "2020-01 is a month-long hull, not that point");
    }


    /**
     * The fallback is ORDERED like {@code family()}'s: a probe marker WINS. A {@code num()} probe
     * must not be pulled into the temporal family by its members. ⚠ Without this, the fix for
     * finding 1 would have been a wider regression than the defect it closed.
     *
     * <p>
     * ⚠⚠ Asserted by BEHAVIOUR, not by an exception. The first version of this test expected a
     * throw and got none. ⛔ <b>And the first explanation of why was also wrong</b>, so it is
     * written out here: {@code num()} is a <b>conversion function</b>, not a tag — {@code TAGS} is
     * {@code date_part}/{@code time_part} alone, and a conversion compiles to a real value plan
     * rather than being erased. The gate stays silent for a different reason:
     * {@code ColumnTypeGate.gatedColumn} returns {@code null} unless the vector has a
     * {@code gatedName()}, which only a plain column read has. {@code num(D)} is a COMPUTED vector,
     * so {@code requireCharacterRead} is a no-op on it — nothing to do with tagging. The
     * discriminating fact is simply that the temporal arm was not taken: row 0 would match under it
     * and must not here.
     * </p>
     */
    @Test
    void aProbeMarkerOutranksTheMembers()
    {
        BitSet fires = evaluate("num(D) in [date(\"2020-01-01T09:15\")]");
        assertFalse(fires.get(0),
                "a num() probe states NUMERIC and keeps it — under the temporal arm row 0 would"
                        + " match at day precision, which is exactly what must not happen");
    }


    /** A list that does not agree on one conversion states nothing, and stays textual. */
    @Test
    void membersMustAgreeOnOneConversionToStateAFamily()
    {
        BitSet fires = evaluate("D in [date(\"2020-01-01T09:15\"), \"2020-02-29\"]");
        assertFalse(fires.get(0),
                "a mixed list states no family — textual, so no day-precision" + " match");
        assertTrue(fires.get(4), "…and the literal text still matches textually");
    }


    /**
     * ⭐ <b>Review round 2, finding 4.</b> The TIME arm's {@code aMixedVerdict = false} was pinned
     * by nothing — flipping it to {@code true} broke no test, so the divergence the design turns on
     * was asserted only for {@code date}. This is its time counterpart: a numeric-shaped member
     * against an ISO time cell reaches {@code compareTimeCells}' mixed arm, where the OPERATOR
     * reports a finding and MEMBERSHIP answers "not a member".
     */
    @Test
    void theTimeArmsMixedShapeVerdictIsPinnedToo()
    {
        BitSet operator = Primitives.timeComparison(times(), ConstVector.of("34200"), ROWS, 0,
                false, false);
        assertTrue(operator.get(0), "the time OPERATOR reports the malformed mixed pair");
        assertFalse(Primitives.isTimeMember(times().value(0).cell(), Set.of("34200")),
                "MEMBERSHIP answers 'not a member' — flipping aMixedVerdict must red HERE");
    }


    /**
     * The time arm's negation pin. ⚠ {@code not in} firing on every row was the exact shape of
     * round 1's HIGH, and the vectorised time form had no negative-polarity assertion at all.
     */
    @Test
    void theTimeVectorisedFormNegatesTheWholeVerdict()
    {
        Set<String> members = Set.of("09:15");
        BitSet in = Primitives.timeMembership(times(), members, ROWS, false);
        BitSet notIn = Primitives.timeMembership(times(), members, ROWS, true);
        for (int row = 0; row < ROWS; row++)
        {
            assertEquals(!in.get(row), notIn.get(row), "row " + row + " must invert");
        }
        assertTrue(in.get(0), "09:15 is a member of {09:15}");
        assertFalse(notIn.get(0), "⛔ and `not in` must NOT fire on it — the HIGH's own shape");
    }


    /**
     * Runbook W2 fold (R5 A-LOW-2): a member set is read ONCE ({@link Primitives.TemporalMembers})
     * instead of once per probe, and that read is the pairwise comparator's decision tree written
     * over pre-read halves — so it must answer exactly what the pairwise
     * {@link Primitives#compareCells} / {@code compareTimeCells} loop answers, for every probe and
     * every set: singletons over a grid of ISO dates and datetimes, partials, intervals, numbers,
     * blanks, junk and mixed shapes, and every two-member set of them.
     */
    @Test
    void aSetReadOnceAnswersWhatThePairwiseComparatorAnswers()
    {
        List<String> dates = List.of("2020-01-15", "2020-01", "2020", "2020-01-15T09:15",
                "2020-01-15T09:15:30", "2020-01-15T09:15:30.123", "2020-01-15Z", " 2020-01-15",
                "2003-12/2003-12", "----06-15", "2020-02-30", "", "junk", "20200115", "5", "5.0",
                "2020-01-15ZZ");
        List<String> times = List.of("09:15", "09:15:00", "09:15:30", "10", "09", "23:59:59",
                "09:15:30.5", "", "junk", "5", "540", "T09:15");
        assertSetEqualsPairwise(dates, false);
        assertSetEqualsPairwise(times, true);
    }


    private static void assertSetEqualsPairwise(List<String> values, boolean time)
    {
        for (String probe : values)
        {
            net.cumba.datatable.values.IDataValue cell = DataValues.of(probe);
            for (String a : values)
            {
                for (String b : values)
                {
                    Set<String> members = new java.util.LinkedHashSet<>(List.of(a, b));
                    boolean pairwise = false;
                    for (String m : members)
                    {
                        pairwise |= time
                                ? Primitives.compareTimeCells(cell, m, 0, false, false, false)
                                : Primitives.compareCells(cell, m, 0, false, false, false);
                    }
                    assertEquals(pairwise,
                            Primitives.TemporalMembers.of(members, time).contains(cell),
                            (time ? "time " : "date ") + "'" + probe + "' in " + members);
                }
            }
        }
        // a numeric probe cell (a typed DOUBLE) against the same sets
        net.cumba.datatable.values.IDataValue number = DataValues.of(5.0);
        for (String a : values)
        {
            boolean pairwise = time ? Primitives.compareTimeCells(number, a, 0, false, false, false)
                    : Primitives.compareCells(number, a, 0, false, false, false);
            assertEquals(pairwise, Primitives.TemporalMembers.of(Set.of(a), time).contains(number),
                    "5.0 in {" + a + "}");
        }
    }
}
