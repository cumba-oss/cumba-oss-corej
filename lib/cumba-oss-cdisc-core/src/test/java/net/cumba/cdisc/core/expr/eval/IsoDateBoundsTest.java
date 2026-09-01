package net.cumba.cdisc.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.cumba.cdisc.core.exec.IsoDateCorpus;
import net.cumba.cdisc.core.exec.ScalarSemantics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * EC-46 Phase 1 — {@link IsoDateBounds} in isolation, before any selector consumes it.
 *
 * <p>
 * The plan's acceptance criteria: every precision tier of §3.2 covered; leap years correct; the
 * three masked shapes bounded per §4.1; {@code canPosition} false for a year-masked value, an
 * unparseable token and a structurally-invalid date alike; and <b>normalisation precedes
 * bounding</b>.
 * </p>
 */
class IsoDateBoundsTest
{

    @Nested
    @DisplayName("right-truncated values — the partial-date hull")
    class Truncated
    {

        @ParameterizedTest(name = "{0} -> [{1}, {2}]")
        @CsvSource(
        {
                // complete day — spans the day, which is what makes the benign MAX tie work
                "2012-06-15, 2012-06-15T00:00:00, 2012-06-15T23:59:59",
                // year only
                "2012, 2012-01-01T00:00:00, 2012-12-31T23:59:59",
                // month precision, 30-day month
                "2012-06, 2012-06-01T00:00:00, 2012-06-30T23:59:59",
                // month precision, 31-day month
                "2012-07, 2012-07-01T00:00:00, 2012-07-31T23:59:59",
                // hour / minute / second tiers
                "2012-06-15T10, 2012-06-15T10:00:00, 2012-06-15T10:59:59",
                "2012-06-15T10:30, 2012-06-15T10:30:00, 2012-06-15T10:30:59",
                "2012-06-15T10:30:45, 2012-06-15T10:30:45, 2012-06-15T10:30:45"
        })
        void boundsPerTier(String input, String lower, String upper)
        {
            assertEquals(lower, IsoDateBounds.lower(input));
            assertEquals(upper, IsoDateBounds.upper(input));
        }


        @ParameterizedTest(name = "{0} upper day = {1}")
        @CsvSource(
        {
                "2012-02, 2012-02-29T23:59:59", // leap
                "2013-02, 2013-02-28T23:59:59", // non-leap
                "2000-02, 2000-02-29T23:59:59", // century leap
                "1900-02, 1900-02-28T23:59:59"
        }) // century non-leap
        void leapAwareMonthEnd(String input, String upper)
        {
            assertEquals(upper, IsoDateBounds.upper(input));
        }


        @Test
        void secondPrecisionIsAPoint()
        {
            assertEquals(IsoDateBounds.lower("2012-06-15T10:30:45"),
                    IsoDateBounds.upper("2012-06-15T10:30:45"));
        }


        @Test
        void fractionalSecondsAreStrippedNotWidened()
        {
            assertEquals("2012-06-15T10:30:45", IsoDateBounds.lower("2012-06-15T10:30:45.123"));
            assertEquals("2012-06-15T10:30:45", IsoDateBounds.upper("2012-06-15T10:30:45.123"));
        }

    }


    @Nested
    @DisplayName("invariant 1 — the all-complete path keeps its raw-text ordering")
    class CompleteOrdering
    {

        /**
         * The reason the no-partials path is byte-identical: both bounds append the <i>same</i>
         * suffix to every value, so it cancels out of any comparison between same-tier values.
         */
        @Test
        void bothBoundsOrderCompleteDatesLikeTheRawText()
        {
            String[] raw =
            {
                    "2011-12-31", "2012-01-01", "2012-06-15", "2012-06-16", "2013-01-01"
            };
            for (int i = 0; i + 1 < raw.length; i++)
            {
                assertTrue(raw[i].compareTo(raw[i + 1]) < 0, "fixture order");
                assertTrue(lowerOf(raw[i]).compareTo(lowerOf(raw[i + 1])) < 0,
                        "lower disagrees with raw order at " + raw[i]);
                assertTrue(upperOf(raw[i]).compareTo(upperOf(raw[i + 1])) < 0,
                        "upper disagrees with raw order at " + raw[i]);
            }
        }


        @Test
        void aCompleteDateIsDetermined()
        {
            assertTrue(IsoDateBounds.isDetermined("2012-06-15"));
            assertTrue(IsoDateBounds.isDetermined("2012-06-15T10:30:45"));
        }


        @ParameterizedTest
        @ValueSource(strings =
        {
                "2012", "2012-06", "2012-06--", "2012---15", "----06-15", "UNK", "2012-13-45", "",
                "   "
        })
        void nothingIncompleteOrUnusableIsDetermined(String s)
        {
            assertFalse(IsoDateBounds.isDetermined(s), s + " must not be eligible to win");
        }


        @Test
        void nullIsNotDetermined()
        {
            assertFalse(IsoDateBounds.isDetermined(null));
        }

    }


    @Nested
    @DisplayName("masked components — Defect D")
    class Masked
    {

        @ParameterizedTest(name = "{0} -> [{1}, {2}]")
        @CsvSource(
        {
                // day masked — same hull as the month-precision partial
                "2012-06--, 2012-06-01T00:00:00, 2012-06-30T23:59:59",
                "2012-06-, 2012-06-01T00:00:00, 2012-06-30T23:59:59",
                // month masked — hull of a NON-contiguous set (twelve dates)
                "2012---15, 2012-01-15T00:00:00, 2012-12-15T23:59:59",
                "2012--15, 2012-01-15T00:00:00, 2012-12-15T23:59:59"
        })
        void maskedHulls(String input, String lower, String upper)
        {
            assertEquals(lower, IsoDateBounds.lower(input));
            assertEquals(upper, IsoDateBounds.upper(input));
        }


        @Test
        void aMaskedDayHullMatchesTheEquivalentTruncatedPartial()
        {
            assertEquals(IsoDateBounds.lower("2012-06"), IsoDateBounds.lower("2012-06--"));
            assertEquals(IsoDateBounds.upper("2012-06"), IsoDateBounds.upper("2012-06--"));
        }


        /**
         * A month-masked day 31 cannot land in a 30-day month, so the hull's high end is the latest
         * month that can actually hold it — December here — rather than an invalid 2012-12-31 read
         * off a naive "month = 12" fill.
         */
        @Test
        void maskedMonthWithADayNotEveryMonthHolds()
        {
            assertEquals("2012-01-31T00:00:00", IsoDateBounds.lower("2012---31"));
            assertEquals("2012-12-31T23:59:59", IsoDateBounds.upper("2012---31"));
        }


        @Test
        void maskedMonthWithDay30SkipsFebruaryOnTheLowEndOnly()
        {
            // January holds 30, so the low end is unaffected; December is still the high end.
            assertEquals("2012-01-30T00:00:00", IsoDateBounds.lower("2012---30"));
            assertEquals("2012-12-30T23:59:59", IsoDateBounds.upper("2012---30"));
        }


        @Test
        void aMaskedDayInFebruaryIsLeapAware()
        {
            assertEquals("2012-02-29T23:59:59", IsoDateBounds.upper("2012-02--"));
            assertEquals("2013-02-28T23:59:59", IsoDateBounds.upper("2013-02--"));
        }

    }


    @Nested
    @DisplayName("canPosition — one predicate, no taxonomy (OQ6 + OQ7)")
    class Unpositionable
    {

        @ParameterizedTest
        @ValueSource(strings =
        {
                // year masked — nothing anchors it on the calendar
                "----06-15", "--06-15",
                // unparseable tokens sponsors really do write into Char date columns
                "UNK", "UNKNOWN", "NOT DONE", "N/A", "NA", "9999-99-99",
                // structurally invalid dates
                "2012-13-45", "2012-02-30", "2012-00-10", "2013-02-29"
        })
        void cannotBePositioned(String s)
        {
            assertFalse(IsoDateBounds.canPosition(s), s + " must not be positionable");
            assertNull(IsoDateBounds.lower(s));
            assertNull(IsoDateBounds.upper(s));
        }


        @ParameterizedTest
        @ValueSource(strings =
        {
                "2012", "2012-06", "2012-06-15", "2012-06--", "2012---15", "2012-06-15T10:30:45",
                "2012-06-15T10:30:45+02:00"
        })
        void canBePositioned(String s)
        {
            assertTrue(IsoDateBounds.canPosition(s), s + " must be positionable");
            assertNotNull(IsoDateBounds.lower(s));
            assertNotNull(IsoDateBounds.upper(s));
        }


        @Test
        void aFourDigitYearIsPositionableEvenIfImplausible()
        {
            // "9999" is a structurally real ISO year, so it IS positionable -- pinned so the junk
            // list above is never read as "any four digits". Implausibility is not this class's
            // job; a conformance rule flags a bad year, the extreme selector only positions it.
            assertTrue(IsoDateBounds.canPosition("9999"));
            assertEquals("9999-01-01T00:00:00", IsoDateBounds.lower("9999"));
        }


        @Test
        void nullAndBlankAreNotPositionable()
        {
            assertFalse(IsoDateBounds.canPosition(null));
            assertFalse(IsoDateBounds.canPosition(""));
            assertFalse(IsoDateBounds.canPosition("   "));
        }

    }


    /**
     * {@code Fix #226} — <b>{@code isDetermined(x) ⇒ canPosition(x)}, as a property over the whole
     * differential corpus.</b>
     *
     * <p>
     * &#9873; <b>Why a property and not a list.</b> {@code Fix #220} left this invariant broken for
     * 15 inputs and pinned them <i>by id</i>. An id list is a fine record of what moved, but it is
     * not a gate: a later change that breaks the invariant for a <b>different</b> input reddens the
     * list, and the cheapest way to make the list green again is to edit it. The assertion below
     * cannot be satisfied that way — the only way to make it pass is for the invariant to hold. The
     * ids stay, as a regression record, in
     * {@code IsoDateBoundsDispatchTest.MovedByFix220.theDeterminedButUnpositionableSetIsClosed}.
     * </p>
     *
     * <p>
     * &#9888; The invariant is <b>one-directional</b>. {@code canPosition ⇒ isDetermined} is false
     * by design and must stay false: {@code 2012} has a hull and is not a determinate day. That
     * direction is pinned too, so the property above can never be "strengthened" into an
     * equivalence by a reader who mistakes one for the other.
     * </p>
     *
     * <p>
     * &#9888;&#9888; <b>Be honest about what the property can and cannot catch.</b> It is sensitive
     * to the two predicates <b>diverging</b>, not to the guard being deleted from both. Traced
     * during the {@code Fix #226} review: remove {@code isReadableCore} from {@code isDetermined}
     * <i>and</i> from {@code halfBound}, and {@code 2012-06-15ZZ} answers {@code true} to both (the
     * bound path renders the nonsense {@code 2012-06-15ZT00:00:00}), so this test goes <b>green on
     * a fully-neutered tree</b> — the "two filters rejecting the same input make each other
     * untestable" shape. &#8658; The double neuter is caught elsewhere, and observably:
     * {@code IsoDateBoundsDispatchTest.MovedByFix220}'s {@code noCorpusInputYieldsAMalformedBound},
     * {@code theMovedSetIsExactlyTheListedInputs} and
     * {@code theDeterminedButUnpositionableSetIsClosed} all red. The suite is the gate; no single
     * test is.
     * </p>
     */
    @Nested
    @DisplayName("Fix #226 — isDetermined implies canPosition")
    class Determinacy
    {

        /**
         * &#9873; <b>The gate.</b> Before {@code Fix #226} this failed for 15 corpus inputs, every
         * one of them a stacked-decoration value whose <i>twice</i>-stripped form is a complete
         * date while the once-stripped core {@code IsoDateBounds} actually reads is undecodable —
         * see {@code IsoDateBounds.isReadableCore} in the source.
         */
        @Test
        void isDeterminedImpliesCanPositionEverywhereInTheCorpus()
        {
            List<String> violations = new ArrayList<>();
            for (String s : IsoDateCorpus.all())
            {
                if (IsoDateBounds.isDetermined(s) && !IsoDateBounds.canPosition(s))
                {
                    violations.add(s);
                }
            }
            assertEquals(List.of(), violations.stream().distinct().sorted().toList(),
                    "isDetermined(x) must imply canPosition(x): a value cannot be a determinate "
                            + "calendar day and have no hull");
        }


        /**
         * The corpus does exercise the antecedent — without this, the property above would be
         * vacuously true if {@code isDetermined} ever started answering {@code false} everywhere.
         * &#9888; Measured 2026-08-11: <b>78</b> distinct determined inputs after {@code Fix #226},
         * 93 before it. The assertion is a floor rather than the exact count, so that widening
         * {@code isoComponents} (which can only admit more) does not red this test; the exact
         * before/after pair is recorded in {@code plans/PLAN-isdetermined-guard-parity.md}.
         */
        @Test
        void theCorpusContainsDeterminedInputsSoThePropertyIsNotVacuous()
        {
            long determined = IsoDateCorpus.all().stream().distinct()
                    .filter(IsoDateBounds::isDetermined).count();
            assertTrue(determined >= 78,
                    "the corpus must keep exercising isDetermined; measured 78, got " + determined);
        }


        /**
         * The converse is <b>not</b> an invariant and must not become one: a partial value has a
         * hull without denoting a single day.
         *
         * <p>
         * &#9888; {@code 2012-06-15T10:30} does <b>not</b> belong here — length 16 is one of
         * {@code ScalarSemantics.isCompleteDate}'s three tiers (10/16/19), so a minute-precision
         * value <i>is</i> determined. Only the hour tier (13) is partial-with-a-hull among the
         * time-bearing shapes. It is pinned as determined by
         * {@link #singlyDecoratedCompleteDatesStayDetermined(String)} instead.
         * </p>
         */
        @ParameterizedTest
        @ValueSource(strings =
        {
                "2012", "2012-06", "2012-06--", "2012---15", "2012-06-15T10"
        })
        void canPositionDoesNotImplyIsDetermined(String s)
        {
            assertTrue(IsoDateBounds.canPosition(s), s + " must have a hull");
            assertFalse(IsoDateBounds.isDetermined(s), s + " must not be determined");
        }


        /**
         * &#9888; The guard rejects a core it cannot read — it must not reject a <b>legitimately
         * decorated</b> complete date, which is the regression a too-eager guard would cause. Each
         * of these carries exactly ONE decoration, so {@link IsoDateBounds#core}'s single strip
         * leaves a decodable core.
         */
        @ParameterizedTest
        @ValueSource(strings =
        {
                "2012-06-15", "2012-06-15Z", "2012-06-15T10:30:45", "2012-06-15T10:30:45Z",
                "2012-06-15T10:30:45+01:00", "2012-06-15T10:30:45-05:00",
                "2012-06-15T10:30:45+0100", "2012-06-15T10:30:45.000", "2012-06-15T10:30",
                "  2012-06-15  "
        })
        void singlyDecoratedCompleteDatesStayDetermined(String s)
        {
            assertTrue(IsoDateBounds.isDetermined(s), s + " must stay determined");
            assertTrue(IsoDateBounds.canPosition(s), s + " must stay positionable");
        }

    }


    @Nested
    @DisplayName("invariant 2 — normalisation precedes bounding (Defect C)")
    class OffsetsFirst
    {

        @Test
        void aCompleteOffsetValueIsShiftedToUtcBeforeBounding()
        {
            // 10:00+02:00 is 08:00Z -- the bound must be built around the UTC instant.
            assertEquals("2012-06-15T08:00:00", IsoDateBounds.lower("2012-06-15T10:00:00+02:00"));
            assertEquals("2012-06-15T08:00:00", IsoDateBounds.upper("2012-06-15T10:00:00+02:00"));
        }


        @Test
        void anHourPrecisionOffsetValueBoundsAroundTheUtcHour()
        {
            // 2012-06-15T10+02:00 -> 08:00Z, hour precision -> [08:00:00, 08:59:59]
            assertEquals("2012-06-15T08:00:00", IsoDateBounds.lower("2012-06-15T10+02:00"));
            assertEquals("2012-06-15T08:59:59", IsoDateBounds.upper("2012-06-15T10+02:00"));
        }


        @Test
        void theOffsetCanMoveTheValueAcrossMidnight()
        {
            assertEquals("2012-06-14T23:00:00", IsoDateBounds.lower("2012-06-15T01:00:00+02:00"));
        }


        /**
         * The measured defect: lexically {@code …T00:00:00Z} sorts before {@code …T01:00:00+02:00},
         * but the offset value is the <i>earlier</i> instant. Bounds must expose that.
         */
        @Test
        void boundsExposeTheInstantOrderTheRawTextHides()
        {
            String zulu = "2012-06-15T00:00:00Z";
            String offset = "2012-06-15T01:00:00+02:00";
            assertTrue(zulu.compareTo(offset) < 0, "raw text puts the Z value first");
            assertTrue(lowerOf(offset).compareTo(lowerOf(zulu)) < 0,
                    "by instant the +02:00 value is earlier");
        }


        @Test
        void zuluIsAlreadyUtc()
        {
            assertEquals("2012-06-15T10:30:45", IsoDateBounds.lower("2012-06-15T10:30:45Z"));
        }

    }


    /**
     * Fix #212 — the ISO-8601 <b>interval of uncertainty</b>, SDTMIG v3.4 &sect;4.4.2 / SENDIG
     * v3.1.1 &sect;4.4.2.
     *
     * <p>
     * The IG <i>recommends</i> {@code a/b} for an imprecise {@code --DTC}; it is not a parity
     * concession. The hull is the union of the halves' hulls (D1 + D3), each half normalised
     * independently (D2).
     * </p>
     */
    @Nested
    @DisplayName("Fix #212 — the interval of uncertainty (SDTMIG §4.4.2)")
    class Intervals
    {

        /** The four worked examples of SDTMIG v3.4 §4.4.2, table on pp. 39–40. */
        @ParameterizedTest(name = "{0} -> [{1}, {2}]")
        @CsvSource(
        {
                "2003-12-15T10:00/2003-12-15T10:30, 2003-12-15T10:00:00, 2003-12-15T10:30:59",
                "2003-01-01/2003-02-15,             2003-01-01T00:00:00, 2003-02-15T23:59:59",
                "2003-12-01/2003-12-10,             2003-12-01T00:00:00, 2003-12-10T23:59:59",
                "2003-01-01/2003-06-30,             2003-01-01T00:00:00, 2003-06-30T23:59:59"
        })
        void theIgWorkedExamples(String input, String lower, String upper)
        {
            assertEquals(lower, IsoDateBounds.lower(input));
            assertEquals(upper, IsoDateBounds.upper(input));
            assertTrue(IsoDateBounds.canPosition(input), input + " must be positionable");
        }


        /**
         * D1 — a partial half keeps its own semantics. Nothing in SEND70's authoritative text
         * requires the halves to be complete; it regulates only that they be <b>symmetric</b>.
         */
        @ParameterizedTest(name = "{0} -> [{1}, {2}]")
        @CsvSource(
        {
                "2003-01/2003-06,       2003-01-01T00:00:00, 2003-06-30T23:59:59",
                "2012/2013,             2012-01-01T00:00:00, 2013-12-31T23:59:59",
                "2012/2012,             2012-01-01T00:00:00, 2012-12-31T23:59:59",
                // Asymmetric precision — legal here (CDISC-SEND-0070's business to flag, not
                // this class's). ⚑ The lower is Dec 15, D1's "earliest the START could be":
                // D3's min/max widening applies ONLY when the pair runs backwards, because an
                // unconditional min would make earliest_possible() answer Dec 1 — wrong for a
                // value builtin, though invisible to a date_* comparison.
                "2003-12-15/2003-12,    2003-12-15T00:00:00, 2003-12-31T23:59:59",
                // masked halves route to maskedBound, and compose (plan §8)
                "2012-06--/2012-07--,   2012-06-01T00:00:00, 2012-07-31T23:59:59"
        })
        void partialAndMaskedHalvesKeepTheirOwnHulls(String input, String lower, String upper)
        {
            assertEquals(lower, IsoDateBounds.lower(input));
            assertEquals(upper, IsoDateBounds.upper(input));
        }


        /** D2 — each half is normalised on its own, so a LEFT-hand offset is applied too. */
        @Test
        void eachHalfIsNormalisedIndependently()
        {
            // Both halves offset: 10:00+02:00 = 08:00Z, 11:00+02:00 = 09:00Z.
            assertEquals("2003-12-15T08:00:00",
                    IsoDateBounds.lower("2003-12-15T10:00+02:00/2003-12-15T11:00+02:00"));
            assertEquals("2003-12-15T09:00:59",
                    IsoDateBounds.upper("2003-12-15T10:00+02:00/2003-12-15T11:00+02:00"));
            // LEFT half only — this is the one core() on the whole string cannot see, because
            // ScalarSemantics.ISO_TZ_OFFSET is $-anchored.
            assertEquals("2003-12-15T08:00:00",
                    IsoDateBounds.lower("2003-12-15T10:00+02:00/2003-12-15T11:00"));
            assertEquals("2003-12-15T11:00:59",
                    IsoDateBounds.upper("2003-12-15T10:00+02:00/2003-12-15T11:00"));
            // RIGHT half only.
            assertEquals("2003-12-15T09:00:00",
                    IsoDateBounds.lower("2003-12-15T10:00/2003-12-15T11:00+02:00"));
            assertEquals("2003-12-15T10:00:59",
                    IsoDateBounds.upper("2003-12-15T10:00/2003-12-15T11:00+02:00"));
        }


        /**
         * D6 (a third component), D8 (an empty half) and D7 (an unpositionable half) all answer
         * {@code null} on <b>both</b> bounds. Never a lower without an upper: {@code
         * IsoDateComparison.fires} discards the pair on a single null anyway, so half an answer
         * buys nothing and misleads.
         */
        @ParameterizedTest
        @ValueSource(strings =
        {
                // D6 — SDTMIG defines exactly two components
                "2003/2004/2005", "2003-01-01/2003-02-01/2003-03-01",
                // D8 — an empty half anchors nothing
                "2003/", "/2003", "/",
                // D7 — <datetime>/<duration> (SDTMIG §4.4.3.2): the upper needs duration
                // arithmetic, which is out of scope
                "2003-12-15T10:00/P1D",
                // D7 — a year-masked half cannot be positioned at all
                "----06-15/2003-12-01", "2003-12-01/----06-15",
                // D7 — a calendar-impossible half
                "2003-02-30/2003-03-01", "2003-01-01/2003-13-01",
                // D7 — junk
                "UNK/2003-01-01", "2003-01-01/UNK",
                // ⚠ measured 2026-08-11: a STACKED tail is a shape this class cannot read. The
                // readability guard that refuses it lived on the interval arm alone until
                // Fix #220 hoisted it into IsoDateBounds.halfBound, which is why these stayed
                // null while their solidus-free counterparts did not. Without the guard these
                // produced garbage hulls such as "2012Z-12-31T23:59:59" — or threw.
                "2012/2012ZZ", "2012ZZ/2012", "2012/2012.000ZZ", "2012.000ZZ/2012",
                "2012/2012Z+01:00", "2012/2012+01:00+01:00"
        })
        void unpositionableIntervalsYieldNullOnBOTHBounds(String input)
        {
            assertNull(IsoDateBounds.lower(input), "lower(" + input + ")");
            assertNull(IsoDateBounds.upper(input), "upper(" + input + ")");
            assertFalse(IsoDateBounds.canPosition(input), input);
        }


        /**
         * D5 — an interval is uncertainty by definition, so it can never <i>win</i> an extreme.
         *
         * <p>
         * &#9888; <b>Honest caveat: this test would pass without the D5 short-circuit.</b> See
         * {@link #theD5ShortCircuitIsDefensiveAndChangesNothingMeasurableToday()} — no string
         * containing a solidus can satisfy {@code ScalarSemantics.isCompleteDate}, so the guard is
         * unobservable through the public API. It is kept for the reason recorded on
         * {@link IsoDateBounds#isDetermined}, not because a test can catch its removal.
         * </p>
         */
        @ParameterizedTest
        @ValueSource(strings =
        {
                "2003-01-01/2003-02-15", "2003-12-15T10:30:45/2003-12-15T10:30:46",
                "2012-06--/2012-07--", "2003/2004/2005", "2003-12-15T10:00/P1D", "2003/"
        })
        void noIntervalIsEverDetermined(String input)
        {
            assertFalse(IsoDateBounds.isDetermined(input), input + " must not be eligible to win");
        }


        /**
         * D3 &mdash; the public bounds take the min/max <b>across both halves</b>, so
         * {@code lower <= upper} holds by construction even when the data runs backwards.
         *
         * <p>
         * &#9888;&#9888; <b>This is exactly why {@link IsoDateBounds#intervalInverted} may not read
         * the public bounds.</b> The clamp makes an inverted interval look perfectly well-formed
         * from outside: the forward and the backwards spelling of the same pair produce the
         * <i>identical</i> hull. A predicate written against {@code lower}/{@code upper} could
         * therefore never fire — the vacuity shape recorded in
         * {@code masking-filters-make-tests-vacuous}. See PLAN &sect;3.
         * </p>
         */
        @Test
        void theClampHidesTheInversionFromThePublicBounds()
        {
            String forward = "2003-12-01/2003-12-10";
            String backwards = "2003-12-10/2003-12-01";
            assertEquals(IsoDateBounds.lower(forward), IsoDateBounds.lower(backwards),
                    "the clamp makes the two spellings indistinguishable through lower()");
            assertEquals(IsoDateBounds.upper(forward), IsoDateBounds.upper(backwards),
                    "…and through upper()");
            assertTrue(
                    IsoDateBounds.lower(backwards).compareTo(IsoDateBounds.upper(backwards)) <= 0,
                    "the D3 invariant");
            // …and yet the predicate, which reads the RAW halves, sees the difference.
            assertFalse(IsoDateBounds.intervalInverted(forward));
            assertTrue(IsoDateBounds.intervalInverted(backwards),
                    "if this is false the predicate has been wired to the clamped bounds");
        }


        /**
         * &#9873; The measurement behind D5's caveat, kept executable so the claim cannot rot.
         *
         * <p>
         * Two statements: (1) over the whole corpus, nothing changes if the short-circuit is
         * bypassed; (2) the reason is structural, not arithmetic — the separator positions
         * {@code ScalarSemantics.isCompleteDate} insists on cannot hold a solidus at <i>any</i> of
         * the three complete lengths. If (2) ever stops holding, D5 starts earning its keep, and
         * this test is where a reader finds out.
         * </p>
         */
        @Test
        void theD5ShortCircuitIsDefensiveAndChangesNothingMeasurableToday()
        {
            List<String> wouldDiffer = new ArrayList<>();
            for (String s : IsoDateCorpus.all())
            {
                if (s.indexOf('/') < 0)
                {
                    continue;
                }
                // isDetermined's body WITHOUT the D5 guard. ⚠ Fix #226 added a readability
                // conjunct (isReadableCore) to that body which is deliberately NOT modelled here
                // (Fix #229 widened it to package-private, so it is now reachable — the omission
                // stays on purpose), so this predicate is strictly WEAKER: it accepts everything
                // the real guard-less body would, and more. That is the safe direction here — an
                // empty result under the weaker model implies an empty result under the real one,
                // so the conclusion "D5 changes nothing" still follows. It would NOT be safe to
                // read a non-empty result as proof that D5 has become load-bearing.
                String core = IsoDateBounds.core(s);
                if (core != null && CalendarDates.isCompleteDate(core))
                {
                    wouldDiffer.add(s);
                }
            }
            assertEquals(List.of(), wouldDiffer,
                    "D5 has become observable — good; give it a real neuter test");
            // The structural reason, spelled out rather than left to the reader.
            for (String s : new String[]
            {
                    "2012-06-15/2012-06-15", "2012-06-15T10:30:45/2012-06-15T10:30:45",
                    "2012-06--/2012-07--", "2012/2012"
            })
            {
                assertFalse(ScalarSemantics.isCompleteDate(s),
                        "a solidus cannot occupy a separator position: " + s);
            }
        }


        /**
         * &#9888; <b>Composition with {@code PLAN-is-partial-date-masked-forms.md} Phase 3.</b>
         * That lane makes {@code CalendarDates.isValidDate} accept the SDTM masked forms, so
         * {@code 2012-06--/2012-07--} becomes a <i>valid</i> interval of two masked halves. The
         * bounds and the inversion test must already compose for that — verified here rather than
         * assumed, since neither lane can run the other's code.
         */
        @ParameterizedTest(name = "{0} inverted = {1}")
        @CsvSource(
        {
                "2012-06--/2012-07--,  false", "2012-07--/2012-06--,  true",
                "2012---15/2012---16,  false", "2012-06--/2012-06--,  false",
                // a masked half against a truncated one still composes
                "2012-06--/2012-08,    false", "2012-08/2012-06--,    true",
                // an unpositionable (year-masked) half is never DEFINITELY anything
                "----06-15/2012-07--,  false"
        })
        void maskedHalvesComposeWithTheInversionTest(String input, boolean inverted)
        {
            assertEquals(inverted, IsoDateBounds.intervalInverted(input), input);
            assertEquals(inverted, IsoDateBounds.intervalDefinitelyInverted(input),
                    "no offsets involved, so the two readings must agree: " + input);
        }


        /** Phase 3 — the invariant D3 buys, over the whole differential corpus. */
        @Test
        void lowerNeverExceedsUpperAnywhereInTheCorpus()
        {
            List<String> violations = new ArrayList<>();
            for (String s : IsoDateCorpus.all())
            {
                String lo;
                String hi;
                try
                {
                    lo = IsoDateBounds.lower(s);
                    hi = IsoDateBounds.upper(s);
                }
                catch (RuntimeException _)
                {
                    continue; // the residual throwing class, pinned by IsoDateBoundsDispatchTest
                }
                if (lo != null && hi != null && lo.compareTo(hi) > 0)
                {
                    violations.add(s + ": " + lo + " > " + hi);
                }
            }
            assertEquals(List.of(), violations, "lower(s) <= upper(s) must hold for every input");
        }


        /**
         * Phase 2 — the inversion predicate, on the RAW per-half bounds.
         *
         * <p>
         * Conservative: the test is {@code lower(start) > upper(end)}, so it fires only when no
         * completion of either half is consistent with the other.
         * </p>
         */
        @ParameterizedTest(name = "intervalInverted({0}) = {1}")
        @CsvSource(
        {
                // satisfiable — Dec 15 lies inside December
                "2003-12-15/2003-12,                 false",
                // earliest start Dec 1 is after the latest end Nov 15
                "2003-12/2003-11-15,                 true",
                // a degenerate point is legal, not an error
                "2003-12-15/2003-12-15,              false",
                // the IG's own example
                "2003-12-01/2003-12-10,              false",
                "2003-12-10/2003-12-01,              true",
                // inversion within a single day
                "2003-12-15T10:30/2003-12-15T10:00,  true",
                // left half unpositionable ⇒ not DEFINITELY inverted
                "----06-15/2003-12-01,               false",
                // not an interval at all
                "2003-12-15,                         false",
                "2003/2004/2005,                     false",
                "2003/,                              false"
        })
        void theInversionPredicate(String input, boolean inverted)
        {
            assertEquals(inverted, IsoDateBounds.intervalInverted(input), input);
        }


        @Test
        void nullAndNonIntervalsAreNeverInverted()
        {
            assertFalse(IsoDateBounds.intervalInverted(null));
            assertFalse(IsoDateBounds.intervalInverted(""));
            assertFalse(IsoDateBounds.intervalInverted("2012-06--"));
        }


        /**
         * The offset is applied before the ordering test, so a pair that reads forward as text can
         * still be backwards as an instant — and is.
         *
         * <p>
         * &#9888;&#9888; <b>But that is NOT on its own enough to report a violation.</b>
         * {@code intervalInverted} is the <i>instant</i> reading, and it rests on
         * {@code normalizeToUtc}'s convention that an offset-less value is already UTC. Where only
         * one half carries an offset, the convention rather than the data is what makes the pair
         * backwards, so {@link IsoDateBounds#intervalDefinitelyInverted} — the predicate
         * {@code invalid_date} actually reports on — requires the pair to run backwards <b>as
         * written</b> too. This test pins both halves of that distinction.
         * </p>
         */
        @Test
        void theInversionIsJudgedOnInstantsButReportedOnlyWhenTheTextAgrees()
        {
            String oneSided = "2003-12-15T10:00/2003-12-15T11:00+02:00";
            assertTrue(oneSided.substring(0, 16).compareTo(oneSided.substring(17, 33)) < 0,
                    "raw text reads forward");
            assertTrue(IsoDateBounds.intervalInverted(oneSided),
                    "11:00+02:00 is 09:00Z, which is BEFORE 10:00Z");
            assertFalse(IsoDateBounds.intervalDefinitelyInverted(oneSided),
                    "…but only under the offset-less-means-UTC convention, so it is not REPORTED");

            // Symmetric offsets shift both halves equally: the two readings agree, and a genuine
            // inversion is reported.
            String bothSided = "2003-12-15T11:00+02:00/2003-12-15T10:00+02:00";
            assertTrue(IsoDateBounds.intervalInverted(bothSided));
            assertTrue(IsoDateBounds.intervalDefinitelyInverted(bothSided));
            // And with no offsets at all, the two are the same predicate.
            assertTrue(IsoDateBounds.intervalDefinitelyInverted("2003-12-10/2003-12-01"));
            assertFalse(IsoDateBounds.intervalDefinitelyInverted("2003-12-01/2003-12-10"));
        }


        /**
         * D1 is the hull an interval DENOTES; D3's min/max widening applies only when the pair runs
         * backwards. Pinned because taking min/max unconditionally is the obvious implementation
         * and is wrong for {@code earliest_possible} — see {@code IsoDateBounds.intervalBound}.
         */
        @Test
        void aForwardIntervalKeepsD1sHullRatherThanTheWidenedOne()
        {
            // The start is Dec 15; the end is somewhere in December. The earliest it can denote is
            // Dec 15, NOT Dec 1.
            assertEquals("2003-12-15T00:00:00", IsoDateBounds.lower("2003-12-15/2003-12"));
            assertEquals("2003-12-31T23:59:59", IsoDateBounds.upper("2003-12-15/2003-12"));
            // The widened reading only appears once the pair is genuinely backwards.
            assertEquals("2003-12-01T00:00:00", IsoDateBounds.lower("2003-12-10/2003-12-01"));
            assertEquals("2003-12-10T23:59:59", IsoDateBounds.upper("2003-12-10/2003-12-01"));
        }

    }


    @Nested
    @DisplayName("the pairs the selection rule turns on")
    class SelectionPairs
    {

        /** OQ1 — the partial cannot start before the complete date, so the complete date wins. */
        @Test
        void oq1TheNonStrictTie()
        {
            assertEquals(lowerOf("2012-06-01"), lowerOf("2012-06"));
        }


        /** OQ1's exclusion — the partial CAN start earlier, so nothing is determined. */
        @Test
        void oq1Exclusion()
        {
            assertTrue(lowerOf("2012-06").compareTo(lowerOf("2012-06-02")) < 0);
        }


        /** The benign MAX tie: the partial cannot end after the last day of its own month. */
        @Test
        void benignMaxTie()
        {
            assertEquals(upperOf("2012-06-30"), upperOf("2012-06"));
        }


        /** Defect A's headline pair — the partial CAN end later, so the max is indeterminate. */
        @Test
        void defectAHeadlinePair()
        {
            assertTrue(upperOf("2012-06-15").compareTo(upperOf("2012-06")) < 0);
        }


        /** A non-prefix pair still resolves — the partial's whole hull is earlier. */
        @Test
        void nonPrefixPairIsDeterminate()
        {
            assertTrue(upperOf("2012-05").compareTo(upperOf("2012-06-15")) < 0);
        }

    }

    private static String lowerOf(String s)
    {
        String v = IsoDateBounds.lower(s);
        assertNotNull(v, "expected " + s + " to be positionable");
        return v;
    }


    private static String upperOf(String s)
    {
        String v = IsoDateBounds.upper(s);
        assertNotNull(v, "expected " + s + " to be positionable");
        return v;
    }

}
