package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import net.cumba.corej.core.exec.IsoDateCorpus;
import net.cumba.corej.core.exec.ScalarSemantics;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Fix #209, Phase 2b prerequisite B — {@link IsoDateBounds} routes on the value's <b>shape</b>, not
 * on {@code CalendarDates.isValidDate} happening to reject masked forms.
 *
 * <h2>What was fragile</h2>
 *
 * <p>
 * {@code bound()} used to try {@code truncatedBound} first and treat its {@code null} as the signal
 * to try the masks — a signal that existed only because {@code isValidDate} rejects every masked
 * form, which the class said in so many words. {@code PLAN-is-partial-date-masked-forms.md} Phase 3
 * makes {@code isValidDate} <b>accept</b> masked forms; the signal then disappears, the masked
 * branch stops being reached, and {@code 2012-06--} is bounded as if it were right-truncated:
 * {@code detectIsoPrecision} reads its length 9 as month precision, so the hull would come out as
 * {@code "2012-06---01T00:00:00"} — a string that is not a date at all.
 * {@code Disjoint.theHazardIsReal()} pins each step of that.
 * </p>
 *
 * <p>
 * Under the shape-first dispatch the two branches are disjoint by construction, so no change to a
 * validator can reroute anything. The dispatch is a strict either/or with <b>no fallback</b>, which
 * is what makes it testable: neuter {@code ScalarSemantics.isMaskedDate} and the masked hulls below
 * go {@code null} instead of quietly still working via the second branch.
 * </p>
 *
 * <h2>&#9888; Re-scoped by Fix #212</h2>
 * <p>
 * The whole-corpus agreement claim is no longer true, <b>on purpose</b>: Fix #212 gives the ISO
 * interval of uncertainty its own arm, which moves 121 corpus inputs. They are not excluded — they
 * are enumerated in {@link MovedByFix212}, together with the reason each move is a fix rather than
 * a regression. Everything solidus-free is still byte-identical to the pre-Fix-#209 dispatch.
 * </p>
 *
 * <h2>&#9888; Re-scoped again by Fix #220</h2>
 * <p>
 * {@code Fix #220} hoists {@code Fix #212}'s readability guard from the interval arm into
 * {@code IsoDateBounds.halfBound}, so "solidus-free is byte-identical" now holds <b>except</b> for
 * the 30 stacked-decoration inputs enumerated in {@link MovedByFix220}. Those 30 subsume the whole
 * garbage-bound class (25) and the whole residual crash class (1); both classes are now empty, and
 * {@code MovedByFix220} asserts that they are rather than deleting the pin.
 * </p>
 */
class IsoDateBoundsDispatchTest
{

    /** The value, or the exception type — so a crash counts as behaviour to be compared. */
    private static String outcomeOf(Supplier<@Nullable String> call)
    {
        try
        {
            return "= " + call.get();
        }
        catch (RuntimeException e)
        {
            return "throws " + e.getClass().getSimpleName();
        }
    }

    @Nested
    @DisplayName("the dispatch moved nothing")
    class Unchanged
    {

        /**
         * &#9873;&#9873; <b>{@code Fix #215} inverted this test, and that inversion IS the proof
         * prerequisite&nbsp;B existed for.</b>
         *
         * <p>
         * At {@code Fix #209} the shape-first dispatch and the legacy try-then-fall-back dispatch
         * agreed on all 73&nbsp;056 corpus inputs, because {@code CalendarDates.isValidDate}
         * rejected every masked form and so {@code truncatedBound} always returned {@code null} for
         * one. Phase 3 makes {@code isValidDate} <b>accept</b> masked forms. The legacy dispatch
         * therefore now answers from {@code truncatedBound} — with the nonsense
         * {@code Disjoint.theHazardIsReal()} predicts — while the shipped dispatch still routes by
         * shape and produces the correct hull.
         * </p>
         *
         * <p>
         * So the assertion is no longer "they agree". It is: <b>they disagree on exactly the masked
         * values, the legacy answer is the wrong one, and the shipped answer is unchanged from what
         * it was before the widening</b> (pinned separately by
         * {@link #theShippedDispatchIsBitForBitUnchangedByTheWidening(String, String, String)}).
         * </p>
         * <p>
         * &#9888; <b>Intervals are excluded, by {@code Fix #212}.</b> That fix moves interval
         * outcomes deliberately; every moved input is listed in
         * {@link MovedByFix212#THE_MOVED_SET}, so the interval class is <i>recorded there</i>
         * rather than re-asserted here. What remains below is the NON-interval population, and on
         * it the break must be exactly the masked class.
         * </p>
         *
         */
        @Test
        void theLegacyFallbackDispatchNowBreaksOnExactlyTheMaskedValues()
        {
            List<String> unexpected = new ArrayList<>();
            List<String> maskedDisagreements = new ArrayList<>();
            for (String s : IsoDateCorpus.all())
            {
                if (s.indexOf('/') >= 0)
                {
                    continue; // Fix #212's population — see MovedByFix212
                }
                if (MovedByFix220.THE_MOVED_SET.contains(s))
                {
                    // Fix #220's population — see MovedByFix220. These 30 solidus-free stacked
                    // decorations used to agree with the legacy dispatch because BOTH produced the
                    // same wrong answer; the readability guard now answers null and the legacy body
                    // still does not. Partitioned, not excluded: the ids are enumerated there.
                    continue;
                }
                for (boolean high : new boolean[]
                {
                        false, true
                })
                {
                    // Compare the OUTCOME, not just the value: a thrown exception is behaviour
                    // too, and this class used to carry a residual one (now retired — see
                    // MovedByFix220).
                    String was = outcome(() -> legacyBound(s, high));
                    String is = outcome(
                            () -> high ? IsoDateBounds.upper(s) : IsoDateBounds.lower(s));
                    if (was.equals(is))
                    {
                        continue;
                    }
                    String core = IsoDateBounds.core(s);
                    if (core != null && ScalarSemantics.isMaskedDate(core))
                    {
                        maskedDisagreements.add(s);
                    }
                    else
                    {
                        unexpected.add(
                                s + " " + (high ? "upper" : "lower") + ": " + was + " -> " + is);
                    }
                }
            }
            assertEquals(List.of(), unexpected, "IsoDateBounds moved outside the masked class");
            assertTrue(maskedDisagreements.size() > 20,
                    "the widening should have broken the legacy fallback dispatch — it did not, "
                            + "so this test is no longer proving anything: "
                            + maskedDisagreements.size());
            // The concrete case the class javadoc predicts, spelled out.
            assertEquals("2012-06---01T00:00:00", legacyBound("2012-06--", false),
                    "the legacy dispatch now emits the predicted nonsense");
            assertEquals("2012-06-01T00:00:00", IsoDateBounds.lower("2012-06--"),
                    "…and the shape-first dispatch does not");
        }


        /**
         * The "protect, do not fix" acceptance for {@code Fix #215}:
         * {@code PLAN-partial-date-predicate-impact.md} &#167;R3.4 counts <b>114 rules / 521
         * entries</b> reaching {@link IsoDateBounds}, and none of them may move. Measured over the
         * whole corpus against a baseline captured before the widening: <b>0 differences</b>,
         * including thrown-exception type.
         *
         * <p>
         * &#9888; The values here are the ones the widening actually put at risk — the masked
         * shapes, each with a single and a stacked decoration. A stacked one is the case that
         * reached {@code truncatedBound} as {@code "2012-06--Z"} while {@code isValidDate} judged
         * {@code "2012-06--"}; without the decoration strip in {@link ScalarSemantics#isMaskedDate}
         * they produced garbage bounds, and four of them threw out of the evaluator.
         * </p>
         */
        @ParameterizedTest(name = "{0} -> [{1}, {2}]")
        @CsvSource(value =
        {
                "2012-06--   | 2012-06-01T00:00:00 | 2012-06-30T23:59:59",
                "2012-06--Z  | 2012-06-01T00:00:00 | 2012-06-30T23:59:59",
                "2012-06--ZZ | null                | null",
                "2012---15   | 2012-01-15T00:00:00 | 2012-12-15T23:59:59",
                "2012---15Z  | 2012-01-15T00:00:00 | 2012-12-15T23:59:59",
                "2012--15ZZ  | null                | null",
                "2012--15Z+01:00 | null            | null",
                "----06-15   | null                | null",
                "--06-15ZZ   | null                | null",
                "--06-15Z+01:00  | null            | null"
        }, delimiter = '|')
        void theShippedDispatchIsBitForBitUnchangedByTheWidening(String input, String lower,
                String upper)
        {
            assertEquals("null".equals(lower.trim()) ? null : lower.trim(),
                    IsoDateBounds.lower(input.trim()), input);
            assertEquals("null".equals(upper.trim()) ? null : upper.trim(),
                    IsoDateBounds.upper(input.trim()), input);
        }


        @Test
        void theCorpusExercisesBothBranches()
        {
            long positioned = IsoDateCorpus.all().stream().filter(IsoDateBounds::canPosition)
                    .count();
            long masked = IsoDateCorpus.all().stream().filter(ScalarSemantics::isMaskedDate)
                    .count();
            assertTrue(positioned > 100, "expected many positionable values, got " + positioned);
            assertTrue(masked > 5, "expected masked values in the corpus, got " + masked);
        }

    }


    /**
     * Fix #212 — the inputs whose bounds the interval arm <b>moved</b>, listed rather than
     * excluded.
     *
     * <p>
     * Every one is an {@code IsoDateCorpus} <i>decoration</i> interval — {@code base[tail]/base} or
     * {@code base/base[tail]} for a canonical {@code base} — and every one previously produced
     * either a {@code DateTimeException} out of the evaluator or a string that is not a date
     * ({@code 2012/2012-01T00:00:00}, {@code 2012-06Z/2012-06:59}). <b>No random-noise input and no
     * solidus-free input moved.</b> Measured 2026-08-11 on the 60&nbsp;463 distinct corpus values.
     * </p>
     */
    @Nested
    @DisplayName("Fix #212 — the moved set, recorded")
    class MovedByFix212
    {

        /**
         * &#9888; <b>The explicit moved set.</b> If this list needs editing, say why in the plan's
         * {@code ## Results} — a silent edit here is how a behaviour change stops being reviewable.
         * {@code PLAN-is-partial-date-masked-forms.md} Phase 3 is the change most likely to move
         * it, since it widens {@code ScalarSemantics.isoComponents}.
         */
        private static final List<String> THE_MOVED_SET = List.of("2012+0100/2012",
                "2012+01:00/2012", "2012-05:00/2012", "2012-06+0100/2012-06",
                "2012-06+01:00/2012-06", "2012-06-05:00/2012-06", "2012-06-15+0100/2012-06-15",
                "2012-06-15+01:00/2012-06-15", "2012-06-15-05:00/2012-06-15",
                "2012-06-15-99:99/2012-06-15", "2012-06-15.000/2012-06-15",
                "2012-06-15.000Z/2012-06-15", "2012-06-15.5/2012-06-15", "2012-06-15/2012-06-15",
                "2012-06-15/2012-06-15+0100", "2012-06-15/2012-06-15+01:00",
                "2012-06-15/2012-06-15+01:00+01:00", "2012-06-15/2012-06-15-05:00",
                "2012-06-15/2012-06-15-99:99", "2012-06-15/2012-06-15-99:99+01:00",
                "2012-06-15/2012-06-15.000", "2012-06-15/2012-06-15.000Z",
                "2012-06-15/2012-06-15.000ZZ", "2012-06-15/2012-06-15.5", "2012-06-15/2012-06-15Z",
                "2012-06-15/2012-06-15Z+01:00", "2012-06-15/2012-06-15ZZ",
                "2012-06-15T10+0100/2012-06-15T10", "2012-06-15T10+01:00/2012-06-15T10",
                "2012-06-15T10-05:00/2012-06-15T10", "2012-06-15T10-99:99/2012-06-15T10",
                "2012-06-15T10.000/2012-06-15T10", "2012-06-15T10.000Z/2012-06-15T10",
                "2012-06-15T10.5/2012-06-15T10", "2012-06-15T10/2012-06-15T10",
                "2012-06-15T10/2012-06-15T10+0100", "2012-06-15T10/2012-06-15T10+01:00",
                "2012-06-15T10/2012-06-15T10+01:00+01:00", "2012-06-15T10/2012-06-15T10-05:00",
                "2012-06-15T10/2012-06-15T10-99:99", "2012-06-15T10/2012-06-15T10-99:99+01:00",
                "2012-06-15T10/2012-06-15T10.000", "2012-06-15T10/2012-06-15T10.000Z",
                "2012-06-15T10/2012-06-15T10.000ZZ", "2012-06-15T10/2012-06-15T10.5",
                "2012-06-15T10/2012-06-15T10Z", "2012-06-15T10/2012-06-15T10Z+01:00",
                "2012-06-15T10/2012-06-15T10ZZ", "2012-06-15T10:30+0100/2012-06-15T10:30",
                "2012-06-15T10:30+01:00/2012-06-15T10:30",
                "2012-06-15T10:30-05:00/2012-06-15T10:30",
                "2012-06-15T10:30-99:99/2012-06-15T10:30", "2012-06-15T10:30.000/2012-06-15T10:30",
                "2012-06-15T10:30.000Z/2012-06-15T10:30", "2012-06-15T10:30.5/2012-06-15T10:30",
                "2012-06-15T10:30/2012-06-15T10:30", "2012-06-15T10:30/2012-06-15T10:30+0100",
                "2012-06-15T10:30/2012-06-15T10:30+01:00",
                "2012-06-15T10:30/2012-06-15T10:30+01:00+01:00",
                "2012-06-15T10:30/2012-06-15T10:30-05:00",
                "2012-06-15T10:30/2012-06-15T10:30-99:99",
                "2012-06-15T10:30/2012-06-15T10:30-99:99+01:00",
                "2012-06-15T10:30/2012-06-15T10:30.000", "2012-06-15T10:30/2012-06-15T10:30.000Z",
                "2012-06-15T10:30/2012-06-15T10:30.000ZZ", "2012-06-15T10:30/2012-06-15T10:30.5",
                "2012-06-15T10:30/2012-06-15T10:30Z", "2012-06-15T10:30/2012-06-15T10:30Z+01:00",
                "2012-06-15T10:30/2012-06-15T10:30ZZ",
                "2012-06-15T10:30:45+0100/2012-06-15T10:30:45",
                "2012-06-15T10:30:45+01:00/2012-06-15T10:30:45",
                "2012-06-15T10:30:45-05:00/2012-06-15T10:30:45",
                "2012-06-15T10:30:45/2012-06-15T10:30:45+0100",
                "2012-06-15T10:30:45/2012-06-15T10:30:45+01:00",
                "2012-06-15T10:30:45/2012-06-15T10:30:45+01:00+01:00",
                "2012-06-15T10:30:45/2012-06-15T10:30:45-05:00",
                "2012-06-15T10:30:45/2012-06-15T10:30:45-99:99+01:00",
                "2012-06-15T10:30:45/2012-06-15T10:30:45.000ZZ",
                "2012-06-15T10:30:45/2012-06-15T10:30:45Z+01:00",
                "2012-06-15T10:30:45/2012-06-15T10:30:45ZZ", "2012-06-15T10:30Z/2012-06-15T10:30",
                "2012-06-15T10Z/2012-06-15T10", "2012-06-15Z/2012-06-15", "2012-06-99:99/2012-06",
                "2012-06.000/2012-06", "2012-06.000Z/2012-06", "2012-06.5/2012-06",
                "2012-06/2012-06", "2012-06/2012-06+0100", "2012-06/2012-06+01:00",
                "2012-06/2012-06+01:00+01:00", "2012-06/2012-06-05:00", "2012-06/2012-06-99:99",
                "2012-06/2012-06-99:99+01:00", "2012-06/2012-06.000", "2012-06/2012-06.000Z",
                "2012-06/2012-06.000ZZ", "2012-06/2012-06.5", "2012-06/2012-06Z",
                "2012-06/2012-06Z+01:00", "2012-06/2012-06ZZ", "2012-06Z/2012-06",
                "2012-99:99/2012", "2012.000/2012", "2012.000Z/2012", "2012.5/2012", "2012/2012",
                "2012/2012+0100", "2012/2012+01:00", "2012/2012+01:00+01:00", "2012/2012-05:00",
                "2012/2012-99:99", "2012/2012-99:99+01:00", "2012/2012.000", "2012/2012.000Z",
                "2012/2012.000ZZ", "2012/2012.5", "2012/2012Z", "2012/2012Z+01:00", "2012/2012ZZ",
                "2012Z/2012");

        @Test
        void theMovedSetIsExactlyTheListedInputs()
        {
            Set<String> moved = new TreeSet<>();
            for (String s : IsoDateCorpus.all())
            {
                // ⚠⚠ Fix #215 (lane D, same wave) widened isValidDate to accept the SDTM MASKED
                // forms, which moves 86 further corpus inputs — none of them an interval, none
                // carrying a solidus. They are NOT this fix's population: they are asserted by
                // theLegacyFallbackDispatchNowBreaksOnExactlyTheMaskedValues and by the
                // "protect, do not fix" CsvSource above. Partitioning here keeps each fix's
                // moved set attributable to the fix that caused it.
                // ⚑ Measured at the wave-30 merge: of the 121 recorded below, ZERO stopped
                // moving — Fix #215 adds to the picture without disturbing Fix #212's population.
                String core = IsoDateBounds.core(s);
                if (core != null && ScalarSemantics.isMaskedDate(core))
                {
                    continue;
                }
                if (MovedByFix220.THE_MOVED_SET.contains(s))
                {
                    // ⚠⚠ Fix #220 (wave 31 lane B) hoisted the interval arm's readability guard
                    // into halfBound, which moves 30 further NON-masked, NON-interval inputs from
                    // a garbage bound, a crash, or a silently-truncated instant to null. Same
                    // partitioning principle as the masked class above: each fix's moved set stays
                    // attributable to its own fix.
                    continue;
                }
                for (boolean high : new boolean[]
                {
                        false, true
                })
                {
                    if (!outcome(() -> legacyBound(s, high)).equals(
                            outcome(() -> high ? IsoDateBounds.upper(s) : IsoDateBounds.lower(s))))
                    {
                        moved.add(s);
                    }
                }
            }
            assertEquals(new TreeSet<>(THE_MOVED_SET), moved,
                    "Fix #212 moved a different set of NON-MASKED corpus inputs than the one "
                            + "recorded");
        }


        /**
         * &#9873; <b>The justification for moving them, as a census rather than a claim.</b> Not
         * one of the 118 had a defensible previous answer, and there are exactly three ways they
         * were wrong:
         *
         * <ul>
         * <li><b>threw</b> — a {@code DateTimeException} out of the evaluator (the &sect;3.2 row
         * this plan started from);</li>
         * <li><b>not-an-instant</b> — a string that is not a date at all, e.g.
         * {@code 2012/2012-01T00:00:00} or {@code 2012-06Z/2012-06:59};</li>
         * <li><b>collapsed-to-a-point</b> — {@code truncatedBound}'s {@code default} arm returned
         * {@code core.substring(0, 19)}, i.e. the <i>left half only</i>, with the right half and
         * any offset silently discarded. It <em>looks</em> well-formed, which is exactly why it
         * needs naming: {@code lower} and {@code upper} came out <b>equal</b>, so a 44-character
         * interval was reported as a single instant.</li>
         * </ul>
         */
        @Test
        void everyMovedInputWasPreviouslyWrong_censusByFailureMode()
        {
            int threw = 0;
            int notAnInstant = 0;
            int collapsedToAPoint = 0;
            List<String> unexplained = new ArrayList<>();
            for (String s : THE_MOVED_SET)
            {
                String lo = outcome(() -> legacyBound(s, false));
                String hi = outcome(() -> legacyBound(s, true));
                if (lo.startsWith("throws") || hi.startsWith("throws"))
                {
                    threw++;
                }
                else if (!isInstant(lo) || !isInstant(hi))
                {
                    notAnInstant++;
                }
                else if (lo.equals(hi))
                {
                    collapsedToAPoint++;
                }
                else
                {
                    unexplained.add(s + " lower " + lo + " upper " + hi);
                }
            }
            assertEquals(List.of(), unexplained,
                    "a moved input had a previous answer this test cannot explain away — check it "
                            + "is really a fix and not a regression");
            assertEquals(THE_MOVED_SET.size(), threw + notAnInstant + collapsedToAPoint,
                    "the census must account for every moved input");
            // Measured 2026-08-11. The numbers are pinned so a later change to the interval arm
            // cannot quietly shift inputs between "was crashing" and "was silently wrong".
            assertEquals(9, threw, "inputs whose bounds used to throw");
            assertEquals(101, notAnInstant, "inputs whose bounds used to be a non-date string");
            assertEquals(11, collapsedToAPoint,
                    "inputs the 19-character truncation used to report as a single instant");
        }


        private static boolean isInstant(String outcome)
        {
            return outcome.startsWith("= ") && INSTANT.matcher(outcome.substring(2)).matches();
        }
    }

    /** {@code yyyy-MM-ddTHH:mm:ss} — what a well-formed bound looks like. */
    private static final Pattern INSTANT = Pattern
            .compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");

    /** {@code bound()} exactly as it stood at {@code b2ea8a714}: try, then fall back. */
    private static @Nullable String legacyBound(@Nullable String s, boolean high)
    {
        String core = IsoDateBounds.core(s);
        if (core == null)
        {
            return null;
        }
        String truncated = IsoDateBounds.truncatedBound(core, high);
        return truncated != null ? truncated : IsoDateBounds.maskedBound(core, high);
    }


    /**
     * {@code bound()} exactly as it stood before {@code Fix #220} — the shape-first dispatch with
     * the readability guard on the <b>interval arm only</b>. Reconstructed from the same
     * package-private primitives {@link #legacyBound} uses, so the fix's effect is a
     * <b>differential</b> over the whole corpus rather than a claim.
     */
    private static @Nullable String preFix220Bound(@Nullable String s, boolean high)
    {
        if (s != null && s.indexOf('/') >= 0)
        {
            String @Nullable [] halves = IsoDateBounds.intervalHalves(s);
            if (halves == null)
            {
                return null;
            }
            String loLeft = preFix220IntervalHalf(halves[0], false);
            String hiLeft = preFix220IntervalHalf(halves[0], true);
            String loRight = preFix220IntervalHalf(halves[1], false);
            String hiRight = preFix220IntervalHalf(halves[1], true);
            if (loLeft == null || hiLeft == null || loRight == null || hiRight == null)
            {
                return null;
            }
            if (loLeft.compareTo(hiRight) <= 0)
            {
                return high ? hiRight : loLeft;
            }
            if (high)
            {
                return hiLeft.compareTo(hiRight) >= 0 ? hiLeft : hiRight;
            }
            return loLeft.compareTo(loRight) <= 0 ? loLeft : loRight;
        }
        return unguardedHalfBound(s, high);
    }


    /** {@code intervalHalfBound} as it stood before {@code Fix #220} deleted it. */
    private static @Nullable String preFix220IntervalHalf(String half, boolean high)
    {
        String core = IsoDateBounds.core(half);
        if (core == null || (!ScalarSemantics.isMaskedDate(core)
                && ScalarSemantics.isoComponents(core) == null))
        {
            return null;
        }
        return unguardedHalfBound(half, high);
    }


    /** {@code halfBound} without {@code Fix #220}'s readability guard. */
    private static @Nullable String unguardedHalfBound(@Nullable String s, boolean high)
    {
        String core = IsoDateBounds.core(s);
        if (core == null)
        {
            return null;
        }
        return ScalarSemantics.isMaskedDate(core) ? IsoDateBounds.maskedBound(core, high)
                : IsoDateBounds.truncatedBound(core, high);
    }


    /** The value, or the exception type — so a crash counts as behaviour to be preserved. */
    private static String outcome(Supplier<@Nullable String> call)
    {
        try
        {
            return "= " + call.get();
        }
        catch (RuntimeException e)
        {
            return "throws " + e.getClass().getSimpleName();
        }
    }

    /**
     * {@code Fix #220} — the inputs the hoisted <b>readability guard</b> moved, listed rather than
     * summarised, and the two whole-corpus classes it emptied.
     *
     * <p>
     * &#9888;&#9888; <b>This nest replaces {@code KnownDefects}, which pinned the defect as
     * permanent.</b> {@code truncatedBound} had the same shape {@code Fix #209} removed from
     * {@code CalendarDates}: it gates on {@code CalendarDates.isValidDate}, which normalises its
     * own argument, then reads {@code core} itself with {@code detectIsoPrecision} and fixed
     * substrings. {@code IsoDateBounds.core} strips only ONE decoration, so on a <b>stacked</b>
     * tail the two disagree — {@code 2012.000ZZ} has core {@code 2012.000Z}, the "month" read at
     * {@code core[5..7]} is {@code "00"}, and {@code YearMonth.of(2012, 0)} threw out of the
     * evaluator. {@code Fix #212} had already written the cure as {@code intervalHalfBound}, on the
     * interval arm alone; {@code Fix #220} hoisted it into {@code halfBound}, so both paths are now
     * immune by construction and {@code intervalHalfBound} is gone.
     * </p>
     */
    @Nested
    @DisplayName("Fix #220 — the residual crash and garbage-bound classes, EMPTIED and pinned")
    class MovedByFix220
    {

        /**
         * &#9888; <b>The explicit moved set — measured over {@code IsoDateCorpus} at
         * {@code 43531d6bf}, immediately before the hoist.</b> All <b>30</b> are solidus-free
         * <i>stacked</i>-decoration inputs, and all 30 now answer {@code null} on both bounds. If
         * this list needs editing, say why in
         * {@code plans/done/PLAN-truncated-bound-double-strip.md} &sect; Results — a silent edit
         * here is how a behaviour change stops being reviewable.
         *
         * <p>
         * &#9873;&#9873; <b>Two failure modes, and the second is invisible to the census the plan
         * prescribed.</b> 25 of them rendered a string that is not a date at all
         * ({@code 2012Z-12-31T23:59:59}), one of those ({@code 2012.000ZZ}) additionally throwing
         * {@code DateTimeException} on its upper bound. The other <b>five</b> — the
         * second-precision family {@link #TRUNCATED_AWAY_A_RESIDUE} — came out as the perfectly
         * well-formed {@code 2012-06-15T10:30:45}, because {@code truncatedBound}'s {@code default}
         * arm is {@code core.substring(0, 19)} and simply <b>discarded</b> the residue it could not
         * read. A census of "neither {@code null} nor a well-formed instant" therefore measures 25
         * and misses these; they are the same defect wearing a plausible answer.
         * </p>
         */
        private static final List<String> THE_MOVED_SET = List.of("2012+01:00+01:00",
                "2012-06+01:00+01:00", "2012-06-15+01:00+01:00", "2012-06-15-99:99+01:00",
                "2012-06-15.000ZZ", "2012-06-15T10+01:00+01:00", "2012-06-15T10-99:99+01:00",
                "2012-06-15T10.000ZZ", "2012-06-15T10:30+01:00+01:00",
                "2012-06-15T10:30-99:99+01:00", "2012-06-15T10:30.000ZZ",
                "2012-06-15T10:30:45+01:00+01:00", "2012-06-15T10:30:45-99:99+01:00",
                "2012-06-15T10:30:45.000ZZ", "2012-06-15T10:30:45Z+01:00", "2012-06-15T10:30:45ZZ",
                "2012-06-15T10:30Z+01:00", "2012-06-15T10:30ZZ", "2012-06-15T10Z+01:00",
                "2012-06-15T10ZZ", "2012-06-15Z+01:00", "2012-06-15ZZ", "2012-06-99:99+01:00",
                "2012-06.000ZZ", "2012-06Z+01:00", "2012-06ZZ", "2012-99:99+01:00", "2012.000ZZ",
                "2012Z+01:00", "2012ZZ");

        /**
         * The five whose previous answer <i>looked</i> right: the 19-character truncation returned
         * a valid instant and threw the unread tail away. Pinned separately because they are the
         * half of the class an "is the output well-formed" census cannot find.
         */
        private static final List<String> TRUNCATED_AWAY_A_RESIDUE = List.of(
                "2012-06-15T10:30:45+01:00+01:00", "2012-06-15T10:30:45-99:99+01:00",
                "2012-06-15T10:30:45.000ZZ", "2012-06-15T10:30:45Z+01:00", "2012-06-15T10:30:45ZZ");

        /** The single input that used to throw {@code DateTimeException} out of the evaluator. */
        private static final String THE_INPUT_THAT_THREW = "2012.000ZZ";

        /**
         * The 15 inputs for which {@code Fix #220} broke {@code isDetermined ⇒ canPosition}, closed
         * by {@code Fix #226}. Re-derived over {@code IsoDateCorpus} at {@code 7e1c8934d}: 15,
         * identical to the set {@code Fix #220} recorded.
         *
         * <p>
         * &#9873; The arithmetic explains itself. {@link #THE_MOVED_SET} is the six canonical tiers
         * &times; the five <b>stacked</b> tails that {@link IsoDateBounds#core}'s single strip
         * cannot clear (30). Of the six tiers only three — {@code 2012-06-15}, {@code …T10:30},
         * {@code …T10:30:45} — reach a length {@code ScalarSemantics.isCompleteDate} accepts
         * (10/16/19), so only 3 &times; 5 = <b>15</b> could ever have been {@code isDetermined}.
         * {@code 2012ZZ}, {@code 2012-06ZZ} and {@code 2012-06-15T10ZZ} were never determined and
         * so were never in the broken set.
         * </p>
         */
        private static final List<String> THE_DETERMINED_BUT_UNPOSITIONABLE_SET = List.of(
                "2012-06-15+01:00+01:00", "2012-06-15-99:99+01:00", "2012-06-15.000ZZ",
                "2012-06-15T10:30+01:00+01:00", "2012-06-15T10:30-99:99+01:00",
                "2012-06-15T10:30.000ZZ", "2012-06-15T10:30:45+01:00+01:00",
                "2012-06-15T10:30:45-99:99+01:00", "2012-06-15T10:30:45.000ZZ",
                "2012-06-15T10:30:45Z+01:00", "2012-06-15T10:30:45ZZ", "2012-06-15T10:30Z+01:00",
                "2012-06-15T10:30ZZ", "2012-06-15Z+01:00", "2012-06-15ZZ");

        @Test
        void theMovedSetIsExactlyTheListedInputs()
        {
            Set<String> moved = new TreeSet<>();
            for (String s : IsoDateCorpus.all())
            {
                for (boolean high : new boolean[]
                {
                        false, true
                })
                {
                    if (!outcome(() -> preFix220Bound(s, high)).equals(
                            outcome(() -> high ? IsoDateBounds.upper(s) : IsoDateBounds.lower(s))))
                    {
                        moved.add(s);
                    }
                }
            }
            assertEquals(new TreeSet<>(THE_MOVED_SET), moved,
                    "Fix #220 moved a different set of corpus inputs than the one recorded");
        }


        /**
         * &#9873; <b>T4 — the interval arm did not move.</b> The interval arm applied this exact
         * guard already ({@code intervalHalfBound}), so hoisting it one level down composes a guard
         * with itself and is a no-op there.
         *
         * <p>
         * &#9888;&#9888; <b>Be honest about what this can catch.</b> {@code preFix220IntervalHalf}
         * is "the literal guard, then the unguarded body" and production {@code halfBound} is now
         * "the same guard, then the same body" — so the two sides are the <i>same function</i> on
         * every half, and this test could not detect a <b>mis-hoisted</b> guard (even deleting the
         * {@code isMaskedDate} disjunct from production would move nothing here). It is a
         * <i>regression</i> pin, not evidence about the hoist: it fails if some later change makes
         * the interval arm diverge from the scalar one.
         * </p>
         *
         * <p>
         * &#8658; The falsifiable evidence that the interval arm still behaves lives in the
         * concrete pins, and both were observed <b>red</b> under Phase 3's neuter:
         * {@link #theIntervalsThatUsedToThrowNowHaveAHull(String, String, String)} (four hulls that
         * must survive) and {@code IsoDateBoundsTest.unpositionableIntervalsYieldNullOnBOTHBounds}
         * (six stacked-tail intervals that must stay {@code null}).
         * </p>
         */
        @Test
        void noIntervalInputMoved()
        {
            List<String> movedIntervals = new ArrayList<>();
            for (String s : IsoDateCorpus.all())
            {
                if (s.indexOf('/') < 0)
                {
                    continue;
                }
                for (boolean high : new boolean[]
                {
                        false, true
                })
                {
                    String was = outcome(() -> preFix220Bound(s, high));
                    String is = outcome(
                            () -> high ? IsoDateBounds.upper(s) : IsoDateBounds.lower(s));
                    if (!was.equals(is))
                    {
                        movedIntervals.add(
                                s + " " + (high ? "upper" : "lower") + ": " + was + " -> " + is);
                    }
                }
            }
            assertEquals(List.of(), movedIntervals,
                    "the interval arm was already immune — nothing there may move");
            // A lint on the constant above, not on the engine: an interval must never be recorded
            // as part of THIS fix's population, whatever a future editor believes.
            assertTrue(THE_MOVED_SET.stream().noneMatch(s -> s.indexOf('/') >= 0),
                    "the recorded moved set must contain no interval: " + THE_MOVED_SET);
        }


        /**
         * &#9873; <b>The justification for moving them, as a census rather than a claim.</b> Not
         * one of the 30 had a previous answer <b>this class</b> could defend, and there are exactly
         * three ways they were wrong — the counts are pinned so a later change cannot quietly shift
         * an input between them:
         *
         * <ul>
         * <li><b>threw</b> — one bound ({@code 2012.000ZZ} upper) raised {@code DateTimeException}
         * out of the evaluator;</li>
         * <li><b>not-an-instant</b> — 49 bounds rendered a string that is not a date
         * ({@code 2012Z-12-31T23:59:59}, {@code 2012-06Z-01T00:00:00});</li>
         * <li><b>truncated-away-a-residue</b> — 10 bounds (the 5 inputs of
         * {@link #TRUNCATED_AWAY_A_RESIDUE}, both bounds each) came back as a well-formed instant
         * that {@code truncatedBound}'s {@code default} arm cut at 19 characters, silently dropping
         * a core longer than that. &#9888; This is the mode a "well-formed output" census cannot
         * see, which is why it is checked positively: the core it answered from was <b>longer than
         * the answer</b>, i.e. the value carried something the class never read.</li>
         * </ul>
         *
         * <p>
         * &#9888;&#9888; <b>"Indefensible here" is not "indefensible everywhere", and the review
         * caught the overclaim.</b> {@code IsoDateComparison.bound} <i>clips</i> a hull to the
         * operand's own precision, and for <b>11</b> of the 30 the clip cut the unread residue off
         * and the consumer-visible answer came out well-formed — {@code 2012-06-15ZZ} yielded
         * {@code 2012-06-15}, the five {@link #TRUNCATED_AWAY_A_RESIDUE} yielded
         * {@code 2012-06-15T10:30:45}. Those 11 now answer {@code null} there too, and leaf
         * verdicts move with them. That is still the right outcome —
         * {@code CalendarDates.isValidDate} is {@code false} for every one, so the engine already
         * calls them invalid dates, and the old answer was read out of a string the gate never
         * inspected — but it is a <b>behaviour change with a visible consumer</b>, not the removal
         * of pure junk. See the plan's {@code ## Results}.
         * </p>
         */
        @Test
        void everyMovedInputWasPreviouslyGarbageOrACrash()
        {
            List<String> unexplained = new ArrayList<>();
            List<String> stillAnswering = new ArrayList<>();
            List<String> threwFor = new ArrayList<>();
            int notAnInstant = 0;
            int truncatedAwayAResidue = 0;
            for (String s : THE_MOVED_SET)
            {
                String core = IsoDateBounds.core(s);
                for (boolean high : new boolean[]
                {
                        false, true
                })
                {
                    String was = outcome(() -> preFix220Bound(s, high));
                    if (was.startsWith("throws"))
                    {
                        threwFor.add(s);
                    }
                    else if (!isInstant(was))
                    {
                        notAnInstant++;
                    }
                    else if (core != null && core.length() > 19
                            && was.equals("= " + core.substring(0, 19)))
                    {
                        truncatedAwayAResidue++;
                        assertTrue(TRUNCATED_AWAY_A_RESIDUE.contains(s),
                                "an unrecorded input was answered by the 19-char truncation: " + s);
                    }
                    else
                    {
                        unexplained.add(s + " " + (high ? "upper" : "lower") + " was " + was);
                    }
                    String is = outcome(
                            () -> high ? IsoDateBounds.upper(s) : IsoDateBounds.lower(s));
                    if (!"= null".equals(is))
                    {
                        stillAnswering.add(s + " " + (high ? "upper" : "lower") + " -> " + is);
                    }
                }
            }
            assertEquals(List.of(), unexplained,
                    "a moved input had a previous answer this census cannot explain away");
            assertEquals(List.of(), stillAnswering, "every moved input must now answer null");
            // ⚠ WHICH input threw, not merely how many: a count would still pass if the crash
            // migrated to a different value.
            assertEquals(List.of(THE_INPUT_THAT_THREW), threwFor, "the crash class, by id");
            assertEquals(49, notAnInstant, "bounds that used to be a non-date string");
            assertEquals(2 * TRUNCATED_AWAY_A_RESIDUE.size(), truncatedAwayAResidue,
                    "bounds the 19-character truncation used to answer by discarding a residue");
        }


        /**
         * &#9873; <b>T1, half one — the residual crash class is EMPTY.</b> Descended from
         * {@code KnownDefects.theResidualClassIsExactlyOneCorpusInput}, which asserted the count
         * was 1. &#9888; <b>The test is kept, not deleted</b>: an empty residual class is exactly
         * what must stay pinned, or the defect returns silently. Measured 32 throwing corpus inputs
         * before {@code Fix #209}, 10 after it, 1 after {@code Fix #212}, and 0 after
         * {@code Fix #220}.
         */
        @Test
        void theResidualCrashClassIsEmpty()
        {
            List<String> throwing = new ArrayList<>();
            for (String s : IsoDateCorpus.all())
            {
                try
                {
                    IsoDateBounds.lower(s);
                    IsoDateBounds.upper(s);
                }
                catch (RuntimeException _)
                {
                    throwing.add(s);
                }
            }
            assertEquals(List.of(), throwing.stream().distinct().toList(),
                    "IsoDateBounds must not throw out of the evaluator for any corpus input");
        }


        /**
         * &#9873; <b>T1, half two — the garbage-bound class is EMPTY.</b> This class was never
         * measured in main before {@code Fix #220}: the crash test above counted only throws, and
         * the 22 the board cited came from wave-30 lane D's worktree, which did not carry lane B's
         * guard. Measured here at {@code 43531d6bf}: <b>25</b> — and note that this class is
         * <i>smaller</i> than the moved set, because 5 more inputs were wrong while still looking
         * well-formed (see {@link #TRUNCATED_AWAY_A_RESIDUE}). Now none — every bound this class
         * hands out is either {@code null} or a well-formed {@code yyyy-MM-ddTHH:mm:ss}.
         */
        @Test
        void noCorpusInputYieldsAMalformedBound()
        {
            List<String> malformed = new ArrayList<>();
            for (String s : IsoDateCorpus.all())
            {
                for (boolean high : new boolean[]
                {
                        false, true
                })
                {
                    String o = outcome(
                            () -> high ? IsoDateBounds.upper(s) : IsoDateBounds.lower(s));
                    if (!"= null".equals(o) && !isInstant(o))
                    {
                        malformed.add(s + " " + (high ? "upper" : "lower") + " -> " + o);
                    }
                }
            }
            assertEquals(List.of(), malformed.stream().distinct().toList(),
                    "a bound must be null or an instant, never a string that is not a date");
        }


        /**
         * &#9873; The four inputs {@code Fix #212} took out of the crash class, kept here so the
         * lineage of the class is not lost when its own test became an emptiness assertion.
         */
        @ParameterizedTest(name = "{0} no longer throws")
        @CsvSource(
        {
                "2012/2012,  2012-01-01T00:00:00, 2012-12-31T23:59:59",
                "2012/2013,  2012-01-01T00:00:00, 2013-12-31T23:59:59",
                "2020/2021,  2020-01-01T00:00:00, 2021-12-31T23:59:59",
                "2012/2012Z, 2012-01-01T00:00:00, 2012-12-31T23:59:59"
        })
        void theIntervalsThatUsedToThrowNowHaveAHull(String input, String lower, String upper)
        {
            assertEquals(lower, IsoDateBounds.lower(input));
            assertEquals(upper, IsoDateBounds.upper(input));
        }


        /** The stacked tails that used to throw or render nonsense, pinned one by one. */
        @ParameterizedTest(name = "{0} -> null")
        @ValueSource(strings =
        {
                "2012.000ZZ", "2012ZZ", "2012Z+01:00", "2012-06ZZ", "2012-06Z+01:00",
                "2012-06-15ZZ", "2012-06-15Z+01:00", "2012-06-15T10:30ZZ", "2012+01:00+01:00",
                "2012-99:99+01:00", "2012-06-15T10:30:45ZZ", "2012-06-15T10:30:45Z+01:00",
                "2012-06-15T10:30:45.000ZZ"
        })
        void stackedTailsNowYieldNullInsteadOfThrowingOrRenderingNonsense(String input)
        {
            assertNull(IsoDateBounds.lower(input), input);
            assertNull(IsoDateBounds.upper(input), input);
            assertFalse(IsoDateBounds.canPosition(input), input);
        }


        /**
         * &#9888;&#9888; <b>The invariant {@code Fix #220} broke and {@code Fix #226} closed — the
         * <i>regression record</i>, not the gate.</b> {@link IsoDateBounds#isDetermined} gates on
         * {@code CalendarDates.isCompleteDate}, which strips a second time — the very double-strip
         * {@code Fix #220} removed from the bound path — and under {@code Fix #220} it had not yet
         * received the guard, so these <b>15</b> inputs answered "yes, a determinate calendar day"
         * while both their bounds were {@code null}. {@code Fix #226} gave {@code isDetermined} the
         * same guard, by extraction rather than by copy, and the set is now <b>empty</b>.
         *
         * <p>
         * &#9873;&#9873; <b>This test is deliberately NOT the acceptance gate for the
         * invariant.</b> An id list can always be made green by editing the id list. The gate is
         * the property
         * {@code IsoDateBoundsTest.Determinacy.isDeterminedImpliesCanPositionEverywhereInTheCorpus}
         * asserts over the same corpus, which no edit to a constant can satisfy. What lives here is
         * the <b>differential</b>: {@link #preFix226IsDetermined} reconstructs the guard-less body,
         * and the first assertion below re-measures — executably — that it was exactly these 15 and
         * not some other set. Delete the reconstruction and the historical claim stops being
         * checkable; that is why it is a literal copy of the old body rather than a call into
         * production.
         * </p>
         *
         * <p>
         * &#9888; All 15 are a subset of {@link #THE_MOVED_SET}: the break had no cause of its own,
         * it was {@code Fix #220}'s bound-path change seen from the other predicate. That
         * containment is asserted, because a member from outside the moved set would mean a second,
         * unexplained mechanism.
         * </p>
         */
        @Test
        void theDeterminedButUnpositionableSetIsClosed()
        {
            Set<String> wasBroken = new TreeSet<>();
            Set<String> stillBroken = new TreeSet<>();
            for (String s : IsoDateCorpus.all())
            {
                if (!IsoDateBounds.canPosition(s))
                {
                    if (preFix226IsDetermined(s))
                    {
                        wasBroken.add(s);
                    }
                    if (IsoDateBounds.isDetermined(s))
                    {
                        stillBroken.add(s);
                    }
                }
            }
            assertEquals(new TreeSet<>(THE_DETERMINED_BUT_UNPOSITIONABLE_SET), wasBroken,
                    "Fix #220 broke the invariant for a different set than the one recorded");
            assertEquals(new TreeSet<String>(), stillBroken,
                    "Fix #226 must leave isDetermined && !canPosition empty — see "
                            + "IsoDateBoundsTest.Determinacy");
            assertTrue(
                    THE_DETERMINED_BUT_UNPOSITIONABLE_SET.stream()
                            .allMatch(THE_MOVED_SET::contains),
                    "every one must be an input Fix #220 moved, or it has another cause: "
                            + THE_DETERMINED_BUT_UNPOSITIONABLE_SET);
        }


        /**
         * {@code isDetermined} exactly as it stood between {@code Fix #220} and {@code Fix #226} —
         * the D5 solidus short-circuit and the completeness test, <b>without</b> the readability
         * guard. The readability conjunct is written out (by being <i>absent</i>) rather than
         * delegated, so <b>removing the guard from production cannot silently remove it from the
         * "before" side</b> and quietly empty the differential.
         *
         * <p>
         * &#9888; <b>It is not fully frozen, and the javadoc must not claim it is.</b> It still
         * calls production {@link IsoDateBounds#core} and {@code CalendarDates.isCompleteDate}; if
         * either of those moves, this reconstruction moves with it and the historical "15" must be
         * re-derived rather than trusted. Only the guard's absence is pinned here.
         * </p>
         */
        private static boolean preFix226IsDetermined(@Nullable String s)
        {
            if (s != null && s.indexOf('/') >= 0)
            {
                return false;
            }
            String core = IsoDateBounds.core(s);
            return core != null && CalendarDates.isCompleteDate(core);
        }


        private static boolean isInstant(String outcome)
        {
            return outcome.startsWith("= ") && INSTANT.matcher(outcome.substring(2)).matches();
        }
    }


    @Nested
    @DisplayName("the branches are disjoint by construction")
    class Disjoint
    {

        @Test
        void maskedBoundOnlyEverAnswersForAMaskedShape()
        {
            // The containment that makes the shape-first dispatch behaviour-preserving:
            // DAY_MASKED ∪ MONTH_MASKED ⊆ isMaskedDate. If a regex there were widened without
            // widening isMaskedDate, the dispatch would stop reaching it — and this fails.
            for (String s : IsoDateCorpus.all())
            {
                String core = IsoDateBounds.core(s);
                if (core != null && IsoDateBounds.maskedBound(core, false) != null)
                {
                    assertTrue(ScalarSemantics.isMaskedDate(core),
                            "maskedBound answered for a shape isMaskedDate rejects: " + core);
                }
            }
        }


        /**
         * &#9873;&#9873; <b>{@code Fix #215} turned this test from a tautology into the real
         * thing.</b> It used to assert that {@code truncatedBound} <em>refuses</em> a masked shape
         * — which was true only because {@code CalendarDates.isValidDate} rejected one, i.e. the
         * very fallback signal prerequisite&nbsp;B removed. Now that the validator accepts masked
         * forms, {@code truncatedBound} <b>would happily answer, with nonsense</b>. What protects
         * the 114 rules / 521 entries is therefore the dispatch and nothing else, so that is what
         * is asserted: {@code bound()} returns the <em>masked</em> branch's answer, which differs
         * from what the truncated branch would have produced.
         */
        @Test
        void truncatedBoundIsNeverConsultedForAMaskedShape()
        {
            int demonstrated = 0;
            for (String masked : IsoDateCorpus.MASKED_SHAPES)
            {
                if (!ScalarSemantics.isMaskedDate(masked))
                {
                    continue; // a near-miss, e.g. 2012-0--15 — not this branch's business
                }
                for (boolean high : new boolean[]
                {
                        false, true
                })
                {
                    String shipped = high ? IsoDateBounds.upper(masked)
                            : IsoDateBounds.lower(masked);
                    assertEquals(IsoDateBounds.maskedBound(masked, high), shipped,
                            "bound() must answer from the masked branch for " + masked);
                    // The hazard, live: since Fix #215 the truncated branch no longer refuses a
                    // masked shape — it returns nonsense, or throws out of lastDayOfMonth when the
                    // "month" it reads is a hyphen. Either way it is not the shipped answer, and
                    // only the shape-first dispatch keeps it out.
                    String wrong = outcomeOf(() -> IsoDateBounds.truncatedBound(masked, high));
                    if (!"= null".equals(wrong))
                    {
                        assertNotEquals(wrong, "= " + shipped,
                                "the truncated branch's answer leaked through for " + masked);
                        demonstrated++;
                    }
                }
            }
            assertTrue(demonstrated > 0,
                    "⚠ truncatedBound no longer answers for any masked shape — this test has "
                            + "stopped demonstrating the hazard it exists for");
        }


        @Test
        void theHazardIsReal()
        {
            // Each step of the failure the dispatch prevents, pinned so the reasoning is not
            // folded into a comment. 1: the masked form is length 9.
            assertEquals(9, "2012-06--".length());
            // 2: detectIsoPrecision reads length 9 as MONTH precision (tier 7).
            assertEquals(7, ScalarSemantics.detectIsoPrecision("2012-06--"));
            // 3: the month-precision arm of truncatedBound appends "-01" + the low time, so a
            // widened validator would have produced this — nonsense, silently.
            assertEquals("2012-06---01T00:00:00", "2012-06--" + "-01" + "T00:00:00");
            // 4: what the masked branch produces instead, and what bound() actually returns.
            assertEquals("2012-06-01T00:00:00", IsoDateBounds.lower("2012-06--"));
        }
    }


    @Nested
    @DisplayName("the masked hulls, reached through the shape branch")
    class MaskedHulls
    {

        @ParameterizedTest(name = "{0} -> [{1}, {2}]")
        @CsvSource(
        {
                "2012-06--,  2012-06-01T00:00:00, 2012-06-30T23:59:59",
                "2012-06-,   2012-06-01T00:00:00, 2012-06-30T23:59:59",
                "2012-02--,  2012-02-01T00:00:00, 2012-02-29T23:59:59",
                "2013-02--,  2013-02-01T00:00:00, 2013-02-28T23:59:59",
                "2012---15,  2012-01-15T00:00:00, 2012-12-15T23:59:59",
                "2012--15,   2012-01-15T00:00:00, 2012-12-15T23:59:59",
                "2012---31,  2012-01-31T00:00:00, 2012-12-31T23:59:59"
        })
        void boundedMaskedFormsKeepTheirHull(String input, String lower, String upper)
        {
            assertTrue(ScalarSemantics.isMaskedDate(input), "precondition: routed by shape");
            assertEquals(lower, IsoDateBounds.lower(input));
            assertEquals(upper, IsoDateBounds.upper(input));
            assertTrue(IsoDateBounds.canPosition(input));
        }


        @ParameterizedTest
        @ValueSource(strings =
        {
                "----06-15", "--06-15", "2012-13--", "2012---32", "2012-0--15"
        })
        void unpositionableShapesStayUnpositionable(String input)
        {
            assertNull(IsoDateBounds.lower(input), input);
            assertNull(IsoDateBounds.upper(input), input);
        }


        @Test
        void truncatedFormsStillGoThroughTheTruncatedBranch()
        {
            for (String canonical : IsoDateCorpus.CANONICAL)
            {
                assertNotNull(IsoDateBounds.lower(canonical), canonical);
                assertEquals(IsoDateBounds.lower(canonical),
                        IsoDateBounds.truncatedBound(canonical, false), canonical);
            }
        }
    }

}
