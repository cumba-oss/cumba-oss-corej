package net.cumba.cdisc.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.cdisc.core.exec.IsoDateCorpus;
import net.cumba.cdisc.core.exec.ScalarSemantics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The fast path's two guarded normalisation fixes, pinned differentially against the raw pre-Q16
 * formula:
 *
 * <ul>
 * <li>{@code Fix #229} — {@link IsoDateComparison#fires}'s fast path requires a <b>readable</b>
 * core on both sides, sharing {@link IsoDateBounds#isReadableCore} with
 * {@code IsoDateBounds.halfBound} ({@code Fix #220}) and {@link IsoDateBounds#isDetermined}
 * ({@code Fix #226}) rather than carrying a third copy of the predicate;</li>
 * <li>{@code Fix #250} — the fast path compares the <b>cores</b>, not the raw operands, so the
 * classification and the comparison read the same string.</li>
 * </ul>
 *
 * <h2>The two defects</h2>
 * <p>
 * The fast path gated on {@code IsoDateBounds.core} (which strips <b>once</b>) followed by
 * {@link CalendarDates#isCompleteDate} (which strips <b>again</b>), and then compared the
 * <b>raw</b> operands through {@link ScalarSemantics#compareIso}. On a <i>stacked</i> tail the two
 * strips disagree, so {@code fires("2012-06-15ZZ", "2012-06-16ZZ", <)} answered {@code true} for
 * two values whose hulls are both {@code null} and which {@link CalendarDates#isValidDate} calls
 * invalid dates ({@code Fix #229}). And a <i>readable</i> pair still compared raw text whose
 * normalisation differs from the cores' — no trim, no fractional-second strip, a length-bucket
 * precision tier — so {@code fires("2012-06-15.000", "2012-06-15T10:30:45", <)} answered
 * {@code true} because {@code detectIsoPrecision} called the first hour-precision and
 * {@code '.' < 'T'} ({@code Fix #250}, the owner's 2026-08-13 ruling: two complete dates compare on
 * the fast path at the minimal precision of both <b>cores</b>; only a definitive partial takes the
 * interval).
 * </p>
 *
 * <h2>&#9873;&#9873; Why the population here is the PAIR space</h2>
 * <p>
 * {@code Fix #220}'s census measured the <b>mixed</b> case ({@code "2012-06-15ZZ"} against
 * {@code "2012-05"}), which <b>cannot reach the fast path at all</b> — the right operand is not
 * complete. A census over <i>one</i> operand is structurally blind to a defect gated on
 * <b>both</b>. Measured exhaustively here at the wave-33 lane-D merge: for a fixed unreadable left
 * operand, only <b>93</b> of the {@code 60 463} distinct corpus values are counterparts that can
 * move the verdict — <b>0.154%</b>. A single-operand census picking one arbitrary counterpart
 * therefore sees nothing 99.85% of the time, which is exactly what happened.
 * </p>
 *
 * <h2>&#9888;&#9888; Which tests catch what — MEASURED, not assumed</h2>
 * <p>
 * &#9888; The neuter matrix below is the {@code Fix #229}-era measurement, kept as the record of
 * why the class is shaped this way; the class was <b>redesigned as a two-family differential</b> at
 * {@code Fix #250} (the "before" side of every differential test is
 * {@link Operator#rawFastPathFormula}, computed live, so a zero-sized moved family fails its count
 * assertion rather than passing vacuously). Three of the {@code Fix #229}-era eight derived their
 * expectations <i>from</i> {@link IsoDateBounds#isReadableCore}, so neutering that predicate made
 * their "unreadable" arm <b>vacuous</b> rather than red. Run at the lane-D merge, three neuters
 * &times; the whole class (8 tests):
 * </p>
 * <table border="1">
 * <caption>Failures per neuter</caption>
 * <tr>
 * <th>neuter</th>
 * <th>fails</th>
 * <th>survives (i.e. does NOT catch it)</th>
 * </tr>
 * <tr>
 * <td><b>N1</b> — drop the two conjuncts from {@code fires}</td>
 * <td>4 / 8</td>
 * <td>{@code theMixedCaseCannotMove}, both of {@link Safety}, {@code theUnreadable…Fifteen} (all
 * four are statements about the <i>predicate</i>, which N1 leaves intact)</td>
 * </tr>
 * <tr>
 * <td><b>N2</b> — {@code isReadableCore} returns {@code true} unconditionally</td>
 * <td>5 / 8</td>
 * <td>{@code fastPathIsTakenExactly…} and both of {@link Safety} — vacuous, as above</td>
 * </tr>
 * <tr>
 * <td><b>N3</b> — <b>both</b> at once</td>
 * <td>5 / 8</td>
 * <td>the same three</td>
 * </tr>
 * </table>
 * <p>
 * &#9873; So the class as a whole does <b>not</b> go green under the double neuter that
 * {@code Fix #226}'s property test went green under — but three of its tests individually do, and
 * that is the limit to state. What carries N3 is
 * {@link TheMovedSet#theFifteenNeverTakeTheFastPath_literalAnchored}: it names the fifteen operands
 * and their expected verdicts as <b>literals</b> and consults no predicate at all, so no change to
 * {@code isReadableCore} can make it vacuous. {@link TheMovedSet#theWorkedExampleFromThePlan} does
 * the same with a pair neither of whose operands is in the corpus.
 * </p>
 * <p>
 * Outside this class, neutering {@code isReadableCore} is additionally caught by
 * {@code IsoDateBoundsDispatchTest.MovedByFix220.theMovedSetIsExactlyTheListedInputs} and
 * {@code IsoDateBoundsTest.Determinacy.isDeterminedImpliesCanPositionEverywhereInTheCorpus}, which
 * read the same predicate through the bound and determinacy paths.
 * </p>
 */
@DisplayName("Fix #229 + #250 — the fast path requires readable cores and compares them")
class IsoDateComparisonReadableCoreTest
{

    /**
     * The six operators {@code ExprCompiler.compileDate} emits, in
     * {@code (direction, orEqual, negate)} form — see {@code ExprCompiler.dateDirection} /
     * {@code dateOrEqual}. There is no seventh: only {@code date_not_equal_to} sets {@code negate},
     * and it always arrives with {@code direction == 0}.
     */
    private static final List<Operator> OPERATORS = List.of(new Operator("==", 0, true, false),
            new Operator("!=", 0, true, true), new Operator(">", 1, false, false),
            new Operator(">=", 1, true, false), new Operator("<", -1, false, false),
            new Operator("<=", -1, true, false));

    /**
     * &#9888; <b>The moved set's operands, as literals.</b> Measured over {@link IsoDateCorpus} at
     * the wave-33 lane-D merge: exactly these fifteen distinct values are calendar-complete after
     * <i>two</i> strips yet undecodable after <i>one</i>, so exactly these fifteen used to reach
     * the fast path with no readable core. They are the same fifteen {@code Fix #226} recorded as
     * {@code IsoDateBoundsDispatchTest.MovedByFix220.THE_DETERMINED_BUT_UNPOSITIONABLE_SET} —
     * necessarily so, because both gates are {@code core != null && isCompleteDate(core)}.
     *
     * <p>
     * &#9873; The arithmetic: {@code Fix #220}'s thirty stacked-decoration inputs are six canonical
     * tiers &times; five stacked tails; only the three tiers whose stripped length is 10/16/19 can
     * satisfy {@code isCompleteDate}, so 3 &times; 5 = 15.
     * </p>
     *
     * <p>
     * If this list needs editing, say why in {@code plans/PLAN-isodatecomparison-readable-core.md}
     * &sect; Results.
     * </p>
     */
    private static final List<String> UNREADABLE_FAST_PATH_OPERANDS = List.of(
            "2012-06-15+01:00+01:00", "2012-06-15-99:99+01:00", "2012-06-15.000ZZ",
            "2012-06-15T10:30+01:00+01:00", "2012-06-15T10:30-99:99+01:00",
            "2012-06-15T10:30.000ZZ", "2012-06-15T10:30:45+01:00+01:00",
            "2012-06-15T10:30:45-99:99+01:00", "2012-06-15T10:30:45.000ZZ",
            "2012-06-15T10:30:45Z+01:00", "2012-06-15T10:30:45ZZ", "2012-06-15T10:30Z+01:00",
            "2012-06-15T10:30ZZ", "2012-06-15Z+01:00", "2012-06-15ZZ");

    /**
     * The number of distinct corpus values that reach the fast-path gate at all — the grid the
     * whole differential lives inside. Recorded so a corpus change surfaces as a red here rather
     * than as a silently different census.
     */
    private static final int FAST_PATH_ELIGIBLE_OPERANDS = 93;

    /**
     * |U&times;F &cup; F&times;U| = 15 &times; 93 &times; 2 &minus; 15&sup2; — the ordered pairs
     * {@code Fix #229} moved (readability family), every verdict {@code true} &rarr; {@code false}.
     */
    private static final int READABILITY_MOVED_PAIRS = 2565;

    /** The readability family's moved verdicts — a strict narrowing, findings only removed. */
    private static final int READABILITY_MOVED_VERDICTS = 7695;

    /**
     * The raw/core family {@code Fix #250} moved: readable <i>valid/valid</i> pairs whose raw
     * reading disagrees with their cores' reading through {@link ScalarSemantics#compareIso}. Every
     * pair carries a {@code '.'}-tail on at least one side and flips to <i>equal</i> under the core
     * reading, so each moves exactly <b>4</b> verdicts — two up, two down.
     */
    private static final int RAW_CORE_MOVED_PAIRS = 260;

    /** The raw/core family's moved verdicts: 520 {@code false→true} + 520 {@code true→false}. */
    private static final int RAW_CORE_MOVED_VERDICTS = 1040;

    /**
     * The complete differential against the raw pre-Q16 formula — the union of the two disjoint
     * families (2 565 + 260, 7 695 + 1 040).
     */
    private static final int MOVED_PAIRS = 2825;

    /** Union verdicts: 8 215 {@code true→false} + 520 {@code false→true}. */
    private static final int MOVED_VERDICTS = 8735;

    private record Operator(String symbol, int direction, boolean orEqual, boolean negate)
    {

        boolean fires(String a, String b)
        {
            return IsoDateComparison.fires(a, b, direction, orEqual, negate);
        }


        /**
         * The raw pre-Q16 fast-path answer — {@code negate != matchCmp(compareIso(a, b), …)} over
         * the <b>raw</b> operands, verbatim from the branch as it stood before {@code Fix #229}
         * guarded it and {@code Fix #250} re-read it through the cores. The differential's "before"
         * side for BOTH families, computed live so the assertions do not depend on an archived
         * build.
         */
        boolean rawFastPathFormula(String a, String b)
        {
            return negate != ScalarSemantics.matchCmp(ScalarSemantics.compareIso(a, b), direction,
                    orEqual);
        }


        /**
         * The {@code Fix #250} fast-path answer — the same formula over the operands' <b>cores</b>.
         * What the shipped branch now computes when both cores are readable and complete.
         */
        boolean coreFastPathFormula(String a, String b)
        {
            String ca = IsoDateBounds.core(a);
            String cb = IsoDateBounds.core(b);
            return negate != ScalarSemantics.matchCmp(
                    ScalarSemantics.compareIso(ca == null ? "" : ca, cb == null ? "" : cb),
                    direction, orEqual);
        }
    }

    /** Distinct corpus values, in corpus order. */
    private static List<String> corpus()
    {
        return List.copyOf(new LinkedHashSet<>(IsoDateCorpus.all()));
    }


    /**
     * {@code true} iff the operand clears the fast-path gate as it stood before {@code Fix #229}.
     */
    private static boolean fastPathEligible(String s)
    {
        String core = IsoDateBounds.core(s);
        return core != null && CalendarDates.isCompleteDate(core);
    }


    private static boolean readableCore(String s)
    {
        String core = IsoDateBounds.core(s);
        return core != null && IsoDateBounds.isReadableCore(core);
    }


    private static List<String> fastPathEligibleOperands()
    {
        List<String> out = new ArrayList<>();
        for (String s : corpus())
        {
            if (fastPathEligible(s))
            {
                out.add(s);
            }
        }
        return out;
    }

    @Nested
    @DisplayName("the invariant — the fast path is taken exactly when both cores are readable")
    class TheInvariant
    {

        /**
         * &#9873;&#9873; <b>The invariant, asserted rather than the id list.</b> "Took the fast
         * path" is not directly observable, so it is pinned by a <b>decisive witness</b>: on the
         * fast path {@code ==} and {@code !=} are exact complements ({@code negate != matchCmp(…)}
         * of the same comparison), so <i>exactly one</i> of them is true; on the hull path with an
         * unbounded operand <b>both</b> are false, which the fast path can never produce. So:
         *
         * <ul>
         * <li>both cores readable &rArr; every one of the six verdicts equals the {@code Fix #250}
         * <b>core</b> formula, i.e. the fast path <i>was</i> taken — and it read the cores, not the
         * raw text ({@code Fix #250} changed the formula asserted here from
         * {@link Operator#rawFastPathFormula} to {@link Operator#coreFastPathFormula}; the raw
         * formula disagrees with the shipped branch on exactly the {@code RAW_CORE_MOVED_PAIRS},
         * which {@link TheMovedSet} pins);</li>
         * <li>either core unreadable &rArr; {@code ==} and {@code !=} are both false, i.e. the fast
         * path was <i>not</i> taken.</li>
         * </ul>
         *
         * <p>
         * Exhaustive over the whole F &times; F grid — and that grid is the whole story: a pair can
         * only move if the old code took the fast path (both operands eligible) and the new code
         * does not (at least one unreadable) or reads the cores where the old code read raw text,
         * so every moving pair lies inside it. The grid is 93 &times; 93 = 8 649 pairs &times; 6
         * operators.
         * </p>
         */
        @Test
        void fastPathIsTakenExactlyWhenBothCoresAreReadable()
        {
            List<String> eligible = fastPathEligibleOperands();
            assertEquals(FAST_PATH_ELIGIBLE_OPERANDS, eligible.size(),
                    "the fast-path-eligible operand set changed size; re-run the census");
            for (String a : eligible)
            {
                for (String b : eligible)
                {
                    boolean bothReadable = readableCore(a) && readableCore(b);
                    for (Operator op : OPERATORS)
                    {
                        if (bothReadable)
                        {
                            assertEquals(op.coreFastPathFormula(a, b), op.fires(a, b),
                                    () -> "both cores readable, so the fast path must be taken "
                                            + "and must read the CORES: " + a + " " + op.symbol()
                                            + " " + b);
                        }
                    }
                    if (!bothReadable)
                    {
                        assertFalse(IsoDateComparison.fires(a, b, 0, true, false),
                                () -> "an unreadable core must not reach the fast path: " + a
                                        + " == " + b);
                        assertFalse(IsoDateComparison.fires(a, b, 0, true, true),
                                () -> "an unreadable core must not reach the fast path: " + a
                                        + " != " + b);
                    }
                }
            }
        }


        /**
         * &#9873; <b>&sect;1.1 made checkable — the mixed case cannot move, and that is why a
         * single-operand census was blind.</b> Pair each of the fifteen unreadable operands with a
         * counterpart that is <i>not</i> fast-path-eligible and neither the old nor the new code
         * takes the fast path; both answer through the hull, which is unbounded on the unreadable
         * side, so all six verdicts are false in both directions.
         *
         * <p>
         * <b>Sampling rule.</b> The exhaustive form is 15 &times; 60 463 &times; 2 = 1 813 890
         * pairs and runs for ~53 s, too slow for the suite; it was run in full at the lane-D merge
         * and its result is recorded in {@code plans/PLAN-isodatecomparison-readable-core.md}
         * &sect; Results (1 395 movers per direction, <b>all</b> with a fast-path-eligible
         * counterpart, none in the mixed class). What runs here is a <b>seeded uniform sample of 3
         * 000 distinct corpus values</b> ({@code Random(20260811)}, sampled without replacement by
         * index), both operand orders — 90 000 pairs.
         * </p>
         */
        @Test
        void theMixedCaseCannotMove_theBlindSpotOfASingleOperandCensus()
        {
            List<String> all = corpus();
            Random rnd = new Random(20_260_811L);
            Set<Integer> picked = new LinkedHashSet<>();
            while (picked.size() < 3_000)
            {
                picked.add(rnd.nextInt(all.size()));
            }
            int mixedPairs = 0;
            for (int i : picked)
            {
                String other = all.get(i);
                if (fastPathEligible(other))
                {
                    continue;
                }
                mixedPairs++;
                for (String u : UNREADABLE_FAST_PATH_OPERANDS)
                {
                    for (Operator op : OPERATORS)
                    {
                        assertFalse(op.fires(u, other),
                                () -> "mixed pair must answer through the hull: " + u + " "
                                        + op.symbol() + " " + other);
                        assertFalse(op.fires(other, u),
                                () -> "mixed pair must answer through the hull: " + other + " "
                                        + op.symbol() + " " + u);
                    }
                }
            }
            assertTrue(mixedPairs > 2_900,
                    "the sample should be almost entirely mixed — fast-path-eligible values are "
                            + "0.154% of the corpus; got " + mixedPairs);
        }
    }


    @Nested
    @DisplayName("Fix #229 — the moved set, recorded")
    class TheMovedSet
    {

        /**
         * The operand half of the census: the unreadable-but-complete values are exactly the
         * fifteen recorded, no more and no fewer.
         */
        @Test
        void theUnreadableFastPathOperandsAreExactlyTheListedFifteen()
        {
            Set<String> found = new TreeSet<>();
            for (String s : corpus())
            {
                if (fastPathEligible(s) && !readableCore(s))
                {
                    found.add(s);
                }
            }
            assertEquals(new TreeSet<>(UNREADABLE_FAST_PATH_OPERANDS), found,
                    "Fix #229 moves a different operand set than the one recorded");
        }


        /**
         * The pair half, measured differentially against {@link Operator#rawFastPathFormula}: which
         * ordered pairs move, in which direction, and to which of the two disjoint families each
         * belongs.
         *
         * <p>
         * &#9873; <b>The readability family ({@code Fix #229}) narrows; the raw/core family
         * ({@code Fix #250}) flips to <i>equal</i>.</b> A pair with an unreadable core falls
         * through to the hull path, whose bounds are {@code null}, whose answer is {@code false}
         * for all six operators — every such verdict moves {@code true} &rarr; {@code false}. A
         * readable pair moves exactly when its raw reading disagrees with its cores' reading; both
         * operands are then {@link CalendarDates#isValidDate}-valid, at least one carries a
         * {@code '.'}-tail, the core comparison answers <i>equal</i>, and exactly <b>4</b> verdicts
         * move — {@code ==}/{@code >=}/{@code <=} gain two, {@code !=} and one strict inequality
         * lose two. <i>(Before {@code Fix #250} this test asserted every moved verdict was
         * {@code true → false}; the 260-pair raw/core family moves both directions, which is the
         * fix working — 520 up, 520 down.)</i>
         * </p>
         */
        @Test
        void theMovedSetSplitsIntoTheTwoFamilies()
        {
            List<String> eligible = fastPathEligibleOperands();
            int readabilityPairs = 0;
            int readabilityVerdicts = 0;
            int rawCorePairs = 0;
            int rawCoreVerdicts = 0;
            int rawCoreUp = 0;
            int rawCoreDown = 0;
            for (String a : eligible)
            {
                for (String b : eligible)
                {
                    boolean bothReadable = readableCore(a) && readableCore(b);
                    int moved = 0;
                    int up = 0;
                    for (Operator op : OPERATORS)
                    {
                        boolean before = op.rawFastPathFormula(a, b);
                        boolean after = op.fires(a, b);
                        if (before != after)
                        {
                            moved++;
                            if (after)
                            {
                                up++;
                            }
                            if (!bothReadable)
                            {
                                assertTrue(before,
                                        () -> "the readability family must narrow "
                                                + "(true -> false), never add a finding: " + a + " "
                                                + op.symbol() + " " + b);
                            }
                        }
                    }
                    if (moved == 0)
                    {
                        continue;
                    }
                    if (!bothReadable)
                    {
                        readabilityPairs++;
                        readabilityVerdicts += moved;
                        assertEquals(0, up,
                                () -> "readability family is a strict narrowing: " + a + " / " + b);
                    }
                    else
                    {
                        rawCorePairs++;
                        rawCoreVerdicts += moved;
                        rawCoreUp += up;
                        rawCoreDown += moved - up;
                        assertTrue(CalendarDates.isValidDate(a) && CalendarDates.isValidDate(b),
                                () -> "every raw/core mover is a valid/valid pair: " + a + " / "
                                        + b);
                        assertTrue(a.contains(".") || b.contains("."),
                                () -> "every raw/core mover carries a '.'-tail: " + a + " / " + b);
                        assertEquals(4, moved, () -> "a raw/core mover moves exactly 4 verdicts: "
                                + a + " / " + b);
                        String ca = IsoDateBounds.core(a);
                        String cb = IsoDateBounds.core(b);
                        assertEquals(0,
                                ScalarSemantics.compareIso(ca == null ? "" : ca,
                                        cb == null ? "" : cb),
                                () -> "a raw/core mover flips to EQUAL under the core reading: " + a
                                        + " / " + b);
                    }
                }
            }
            assertEquals(READABILITY_MOVED_PAIRS, readabilityPairs,
                    "the readability family's pair count changed");
            assertEquals(READABILITY_MOVED_VERDICTS, readabilityVerdicts,
                    "the readability family's verdict count changed");
            assertEquals(RAW_CORE_MOVED_PAIRS, rawCorePairs,
                    "the raw/core family's pair count changed");
            assertEquals(RAW_CORE_MOVED_VERDICTS, rawCoreVerdicts,
                    "the raw/core family's verdict count changed");
            assertEquals(RAW_CORE_MOVED_VERDICTS / 2, rawCoreUp,
                    "the raw/core family moves symmetrically: 520 false->true");
            assertEquals(RAW_CORE_MOVED_VERDICTS / 2, rawCoreDown,
                    "the raw/core family moves symmetrically: 520 true->false");
            assertEquals(MOVED_PAIRS, readabilityPairs + rawCorePairs,
                    "the union pair count changed");
            assertEquals(MOVED_VERDICTS, readabilityVerdicts + rawCoreVerdicts,
                    "the union verdict count changed");
        }


        /**
         * &#9873;&#9873; <b>The double-neuter catcher.</b> Literals only — no call to
         * {@link IsoDateBounds#isReadableCore}, no corpus derivation. Removing the guard from
         * {@code fires} reds it; neutering {@code isReadableCore} to a constant {@code true} reds
         * it too, because the fifteen would then take the fast path again and answer {@code true}
         * for {@code ==} against themselves.
         */
        @Test
        void theFifteenNeverTakeTheFastPath_literalAnchored()
        {
            for (String u : UNREADABLE_FAST_PATH_OPERANDS)
            {
                for (Operator op : OPERATORS)
                {
                    assertFalse(op.fires(u, u), () -> u + " " + op.symbol() + " " + u
                            + " must answer through the hull");
                    assertFalse(op.fires(u, "2012-06-15"), () -> u + " " + op.symbol()
                            + " 2012-06-15 must answer through the hull");
                    assertFalse(op.fires("2012-06-15", u), () -> "2012-06-15 " + op.symbol() + " "
                            + u + " must answer through the hull");
                }
            }
        }


        /**
         * D2 — the plan's worked example, with a right operand that appears nowhere in
         * {@link IsoDateCorpus}, so the fix cannot be corpus-shaped. {@code true} before
         * {@code Fix #229}, {@code false} after.
         */
        @Test
        void theWorkedExampleFromThePlan()
        {
            assertFalse(IsoDateComparison.fires("2012-06-15ZZ", "2012-06-16ZZ", -1, false, false),
                    "two values with null hulls, both invalid dates, must not compare as dates");
            assertFalse(CalendarDates.isValidDate("2012-06-15ZZ"));
            assertFalse(CalendarDates.isValidDate("2012-06-16ZZ"));
        }
    }


    @Nested
    @DisplayName("Fix #250 — the fast path compares the CORES, literal-anchored")
    class TheCoreComparison
    {

        /**
         * The plan's worked example, as literals — no predicate, no corpus derivation, so no neuter
         * can make it vacuous. Raw, {@code detectIsoPrecision("2012-06-15.000")} is 13 (hour) and
         * {@code '.' < 'T'}, so {@code <} answered {@code true} and {@code ==} answered
         * {@code false}; through the cores both operands reduce to day precision and compare
         * <i>equal</i>. The raw formula is computed live alongside, so the "before" side is
         * asserted rather than remembered.
         */
        @Test
        void theFractionalTailWorkedExample()
        {
            Operator eq = OPERATORS.get(0);
            Operator lt = OPERATORS.get(4);
            assertFalse(eq.rawFastPathFormula("2012-06-15.000", "2012-06-15T10:30:45"),
                    "raw: the pair reads unequal — half of what makes this test non-vacuous");
            assertTrue(lt.rawFastPathFormula("2012-06-15.000", "2012-06-15T10:30:45"),
                    "raw: '.' < 'T' really does order the pair — the other half");
            assertFalse(lt.fires("2012-06-15.000", "2012-06-15T10:30:45"),
                    "'<' no longer fires: the cores are the same day");
            assertTrue(eq.fires("2012-06-15.000", "2012-06-15T10:30:45"),
                    "'==' fires: a day-precision core equals the same day at any finer precision");
        }


        /**
         * The both-directions pin: {@code "2012-06-15.10"} vs {@code "2012-06-15.000"} — raw, both
         * read as hour tier and {@code '1' > '0'} says <i>not equal</i>; the cores are both
         * {@code 2012-06-15}. {@code ==} gains a finding ({@code false → true}) and {@code !=}
         * loses one ({@code true → false}) — the &sect;4 symmetry, one concrete pair.
         */
        @Test
        void theMovedFamilyMovesBothDirections()
        {
            Operator eq = OPERATORS.get(0);
            Operator ne = OPERATORS.get(1);
            assertFalse(eq.rawFastPathFormula("2012-06-15.10", "2012-06-15.000"));
            assertTrue(eq.fires("2012-06-15.10", "2012-06-15.000"), "== gains: false -> true");
            assertTrue(ne.rawFastPathFormula("2012-06-15.10", "2012-06-15.000"));
            assertFalse(ne.fires("2012-06-15.10", "2012-06-15.000"), "!= loses: true -> false");
        }


        /**
         * &#9888; The family {@code IsoDateCorpus} cannot see (&sect;2 (ii)): {@code compareIso}
         * does not trim, and {@code Primitives.dateComparison} passes the resolved string through
         * untrimmed, so a <b>leading-space</b> operand never compared equal to its trimmed twin —
         * the corpus alphabet has no space, so this is pinned here as literals. Trailing space was
         * already eaten by the min-precision truncation (raw tier of {@code "2012-06-15 "} is still
         * 10... it is 11 characters, tier 10, truncated back to the date), asserted as the control
         * so the leading-space assertion is about the LEADING side specifically.
         */
        @Test
        void aLeadingSpaceOperandNowComparesThroughItsCore()
        {
            Operator eq = OPERATORS.get(0);
            assertFalse(eq.rawFastPathFormula(" 2012-06-15", "2012-06-15"),
                    "raw: the leading space keeps the pair unequal — the family is real");
            assertTrue(eq.fires(" 2012-06-15", "2012-06-15"),
                    "the core is trimmed, so the pair now compares equal");
            assertTrue(eq.rawFastPathFormula("2012-06-15 ", "2012-06-15"),
                    "control: TRAILING space was already equal raw (truncation eats it)");
            assertTrue(eq.fires("2012-06-15 ", "2012-06-15"), "and still is");
        }


        /**
         * &sect;3's central claim, spot-pinned: a pair with a definitive partial still takes the
         * interval path — {@code Fix #250} moves nothing there. (The exhaustive statement is
         * {@code DateComparisonPartialSemanticsTest}'s matrix; this pin just keeps the boundary
         * visible next to the fast-path tests.)
         */
        @Test
        void partialOperandsStillTakeTheIntervalPath()
        {
            for (Operator op : OPERATORS)
            {
                assertFalse(op.fires("2012-06", "2012-06-15"),
                        () -> "a month-precision operand cannot be ordered against a day inside "
                                + "it: " + op.symbol());
            }
            assertTrue(OPERATORS.get(2).fires("2012-07-01", "2012-06"),
                    "> : after the whole month — the interval path still orders what it can");
        }
    }


    @Nested
    @DisplayName("D5 — the guard rejects only isValidDate-rejects; valid/valid moves only raw/core")
    class Safety
    {

        /**
         * &#11088; <b>The safety property, asserted over the corpus rather than spot-checked.</b> A
         * singly-decorated <i>valid</i> value must never be pushed off the fast path: if the new
         * guard rejects an operand, {@link CalendarDates#isValidDate} must already reject it too.
         * Measured: all fifteen rejected operands are {@code isValidDate}-invalid, so the fix
         * removes findings only from rows a conformance run already reports as bad dates.
         */
        @Test
        void theGuardRejectsOnlyWhatIsValidDateAlreadyRejects()
        {
            for (String s : corpus())
            {
                if (fastPathEligible(s) && !readableCore(s))
                {
                    assertFalse(CalendarDates.isValidDate(s),
                            () -> "the guard rejected a value isValidDate accepts: " + s);
                }
            }
        }


        /**
         * The pair form, inverted by {@code Fix #250} into the positive control it used to forbid:
         * a valid/valid comparison moves away from the raw formula <b>exactly</b> when its raw
         * reading disagrees with its cores' reading — the 260-pair raw/core family — and nowhere
         * else. <i>(Before {@code Fix #250} this test asserted NO valid/valid comparison moved;
         * those 260 pairs moving is the fix working, so the assertion now pins the moved set's
         * boundary instead of its emptiness.)</i> Wherever raw and core readings agree, the shipped
         * verdict still equals the raw formula — nothing outside the family moved.
         */
        @Test
        void exactlyTheRawCoreFamilyMovedAmongValidValidPairs()
        {
            List<String> eligible = fastPathEligibleOperands();
            int movedPairs = 0;
            for (String a : eligible)
            {
                for (String b : eligible)
                {
                    if (!CalendarDates.isValidDate(a) || !CalendarDates.isValidDate(b))
                    {
                        continue;
                    }
                    if (!readableCore(a) || !readableCore(b))
                    {
                        continue;
                    }
                    boolean pairMoved = false;
                    for (Operator op : OPERATORS)
                    {
                        boolean raw = op.rawFastPathFormula(a, b);
                        boolean core = op.coreFastPathFormula(a, b);
                        assertEquals(core, op.fires(a, b),
                                () -> "a valid/valid readable pair must answer the CORE reading: "
                                        + a + " " + op.symbol() + " " + b);
                        if (raw != core)
                        {
                            pairMoved = true;
                        }
                        else
                        {
                            assertEquals(raw, op.fires(a, b),
                                    () -> "outside the raw/core family nothing may move: " + a + " "
                                            + op.symbol() + " " + b);
                        }
                    }
                    if (pairMoved)
                    {
                        movedPairs++;
                    }
                }
            }
            assertEquals(RAW_CORE_MOVED_PAIRS, movedPairs,
                    "the valid/valid moved set must be exactly the raw/core family");
        }
    }
}
