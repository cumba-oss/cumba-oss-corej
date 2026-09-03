package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.cumba.corej.core.exec.IsoDateCorpus;
import net.cumba.corej.core.exec.ScalarSemantics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Fix #209, Phase 2b prerequisite A — {@link CalendarDates#isValidDate(String)} no longer reads
 * fixed character offsets, and that change moved nothing except the inputs on which it used to
 * <b>throw</b>.
 *
 * <h2>What was wrong</h2>
 *
 * <p>
 * The old body gated on {@code ScalarSemantics.isPartialDate(core)} and then read components with
 * {@code charAt} at offsets 5/8/11/14/17, under the comment <i>"the structural check guarantees
 * ASCII digits at these positions, so no parse failure"</i>. It did not: {@code isPartialDate}
 * normalises its <em>own</em> argument before validating, so the gate judged
 * {@code strip(strip(s))} while the reads indexed {@code strip(s)}. Any value carrying <b>two</b>
 * timezone decorations therefore passed the gate at one precision tier and was then read at the
 * next tier's offsets, off the end of the string. The handler caught {@link DateTimeException}
 * only, so the {@link StringIndexOutOfBoundsException} propagated out of the evaluator — and
 * {@code Primitives} calls {@code isValidDate} on <b>raw cell text</b>, so the input is data.
 * </p>
 *
 * <p>
 * &#9873; This is the same invariant {@code PLAN-is-partial-date-masked-forms.md} Phase 3 would
 * break deliberately (every masked form has a non-canonical length), which is why the fix is to
 * remove the fixed-offset reads rather than to widen the {@code catch}: a broadened catch would
 * turn the crash into a silent {@code false}, and a wrong answer is worse than a loud one.
 * </p>
 */
class CalendarDatesPositionalReadTest
{

    /**
     * {@code stripFractionalSeconds(stripTimezone(·))} — the normalisation {@code core} applies.
     */
    private static String strip(String s)
    {
        return ScalarSemantics.stripFractionalSeconds(ScalarSemantics.stripTimezone(s));
    }

    @Nested
    @DisplayName("the crash was live before this fix, and is gone")
    class Crash
    {

        /**
         * Every one of these threw {@link StringIndexOutOfBoundsException} out of
         * {@code CalendarDates.isValidDate} at {@code b2ea8a714}. They are stacked-decoration
         * values: {@code strip} once leaves a trailing {@code Z}, and the gate strips a second time
         * and accepts the shorter string.
         */
        @ParameterizedTest
        @ValueSource(strings =
        {
                "2012ZZ", "2012Z+01:00", "2012-06ZZ", "2012-06-15ZZ", "2012-06-15Z+01:00",
                "2012-06-15T10ZZ", "2012-06-15T10:30ZZ"
        })
        void formerlyThrowingInputsNowAnswer(String input)
        {
            // The precondition that made them crash: one strip is not a fixed point.
            assertNotEquals(strip(input), strip(strip(input)), "not a stacked-decoration input");
            assertThrows(StringIndexOutOfBoundsException.class, () -> legacyIsValidDate(input),
                    "the pinned legacy body must still demonstrate the crash");
            assertFalse(CalendarDates.isValidDate(input),
                    "a value carrying two offsets is not a valid date");
        }


        @Test
        void noInputInTheCorpusThrows()
        {
            List<String> thrown = new ArrayList<>();
            for (String s : IsoDateCorpus.all())
            {
                try
                {
                    CalendarDates.isValidDate(s);
                    CalendarDates.isCompleteDate(s);
                    CalendarDates.isPartialDate(s);
                    CalendarDates.isCompleteDatePart(s);
                }
                catch (RuntimeException e)
                {
                    thrown.add(s + " -> " + e);
                }
            }
            assertEquals(List.of(), thrown, thrown.size() + " inputs still throw");
        }


        /**
         * &#9873;&#9873; {@code PLAN-partial-date-predicate-impact.md} &#167;R3.2's <b>W1</b>
         * prediction, made executable: <i>"option (a) as written crashes the engine"</i> — widen
         * the gate and leave the fixed-offset reader, and a day-masked value reaches
         * {@code parse2(core, 8)} and indexes past the end of an 8-character string. It was a paper
         * argument written against a transcription; here it runs.
         *
         * <p>
         * &#9888; This is why {@code Fix #209} had to land first, and why {@code Fix #215}'s
         * {@code isValidDate} validates a decoded layout instead of reading offsets. The
         * {@code assertThrows} below is the failure mode the shipped code does <b>not</b> have —
         * {@link #formerlyThrowingInputsNowAnswer} covers the same value on the shipped path.
         * </p>
         */
        @Test
        void theWidenedGateWouldHaveCrashedTheOldBody()
        {
            for (String masked : new String[]
            {
                    "2012-06-", "2012-06--"
            })
            {
                assertTrue(ScalarSemantics.isPartialDate(masked),
                        "precondition: Fix #215 widened the gate to admit " + masked);
                assertThrows(StringIndexOutOfBoundsException.class,
                        () -> widenedGateLegacyBody(masked),
                        "W1 must still demonstrate the crash for " + masked);
                assertTrue(CalendarDates.isValidDate(masked),
                        "…and the shipped body answers instead of throwing");
            }
        }


        /** The pre-{@code Fix #209} body with only its gate widened — {@code §R3.2}'s W1. */
        private boolean widenedGateLegacyBody(String s)
        {
            String core = strip(s);
            if (!ScalarSemantics.isPartialDate(core))
            {
                return false;
            }
            int len = core.length();
            if (len == 4)
            {
                return true;
            }
            int month = parse2(core, 5);
            if (month < 1 || month > 12)
            {
                return false;
            }
            if (len == 7)
            {
                return true;
            }
            parse2(core, 8); // ⛔ index 8 of an 8-character "2012-06-"
            return true;
        }


        @Test
        void theLegacyBodyThrewOnManyCorpusInputs()
        {
            // Control for the test above: it is only meaningful because the old body did throw.
            long crashes = IsoDateCorpus.all().stream().filter(s ->
            {
                try
                {
                    legacyIsValidDate(s);
                    return false;
                }
                catch (StringIndexOutOfBoundsException _)
                {
                    return true;
                }
            }).count();
            assertTrue(crashes > 0, "the pinned legacy body no longer demonstrates the defect");
        }
    }


    @Nested
    @DisplayName("everything that did not throw is bit-for-bit unchanged")
    class Unchanged
    {

        /**
         * The acceptance that matters: on every input where {@code strip} is already a fixed point
         * — i.e. every value the old body could actually read correctly — the new
         * {@code isValidDate} returns exactly what the old one did.
         */
        @Test
        void agreesWithTheLegacyBodyWhereverTheLegacyBodyWasWellDefined()
        {
            List<String> disagreements = new ArrayList<>();
            List<String> maskedMovers = new ArrayList<>();
            int compared = 0;
            for (String s : IsoDateCorpus.all())
            {
                if (!isNormalisationFixedPoint(s))
                {
                    continue;
                }
                compared++;
                if (legacyIsValidDate(s) == CalendarDates.isValidDate(s))
                {
                    continue;
                }
                // ⚑ Fix #215 — the ONE class this comparison is now allowed to move: a legal SDTM
                // masked date, which the legacy body rejected and which is now valid. Every such
                // move is false → true; a move in the other direction is a regression.
                if (ScalarSemantics.isMaskedDate(s) && CalendarDates.isValidDate(s))
                {
                    maskedMovers.add(s);
                    continue;
                }
                disagreements.add(s);
            }
            assertEquals(List.of(), disagreements, "isValidDate moved outside the masked class");
            assertTrue(compared > 60_000, "expected the bulk of the corpus, compared " + compared);
            // Measured 2026-08-11: 65 rows. Neuter-and-watch — without this the test above passes
            // against a change that moves nothing at all.
            assertEquals(65, maskedMovers.size(), "the masked class moved: " + maskedMovers.size());
        }


        /**
         * &#9873; The half of {@code Fix #215} that must <b>not</b> move: widening the partial gate
         * cannot change {@code isCompleteDate} or {@code isCompleteDatePart} on any input, because
         * every newly-valid value is length &le; 9. This is
         * {@code PLAN-partial-date-predicate-impact.md} &#167;R3.3's invariance for the 49
         * {@code is_complete_date} rules and the 19 {@code is_not_complete_date_part} rules, proved
         * by execution rather than argument.
         */
        @Test
        void isCompleteDateAndIsCompleteDatePartAreBitForBitUnchanged()
        {
            List<String> moved = new ArrayList<>();
            for (String s : IsoDateCorpus.all())
            {
                if (!isNormalisationFixedPoint(s))
                {
                    continue;
                }
                boolean wasComplete = legacyIsValidDate(s) && ScalarSemantics.isCompleteDate(s);
                if (wasComplete != CalendarDates.isCompleteDate(s))
                {
                    moved.add(s);
                }
            }
            assertEquals(List.of(), moved, "isCompleteDate moved");
        }


        /**
         * &#9873; The complement, stated rather than hidden: the ONLY inputs whose verdict moves
         * are stacked-decoration values, and every one of them now answers {@code false}. The old
         * verdict there was a crash or a garbage read — e.g. {@code 2012-06-15+01:00+01:00} was
         * {@code true} because the reads landed on the offset's own digits.
         */
        @Test
        void theOnlyMoversAreStackedDecorations()
        {
            List<String> movers = new ArrayList<>();
            for (String s : IsoDateCorpus.all())
            {
                if (isNormalisationFixedPoint(s))
                {
                    continue;
                }
                assertFalse(CalendarDates.isValidDate(s), "stacked decoration accepted: " + s);
                movers.add(s);
            }
            assertTrue(movers.size() > 50, "expected the class to be exercised, got " + movers);
            assertTrue(legacyIsValidDate("2012-06-15+01:00+01:00"),
                    "the legacy body read the offset's digits as an hour and a minute");
        }


        /** Every value in the corpus with no {@code /}, whose strip is a fixed point. */
        private boolean isNormalisationFixedPoint(String s)
        {
            if (s.indexOf('/') >= 0)
            {
                // An interval recurses on halves; apply the test to each half instead.
                for (String half : s.split("/", -1))
                {
                    if (!isNormalisationFixedPoint(half))
                    {
                        return false;
                    }
                }
                return true;
            }
            return strip(s).equals(strip(strip(s)));
        }
    }


    @Nested
    @DisplayName("the surviving catch is minimal")
    class CatchScope
    {

        @Test
        void onlyCalendarImpossibilityIsCaught()
        {
            // LocalDate.of is the single guarded statement; these are the values it rejects.
            assertFalse(CalendarDates.isValidDate("2023-02-29"), "not a leap year");
            assertFalse(CalendarDates.isValidDate("2024-04-31"), "April has 30 days");
            assertFalse(CalendarDates.isValidDate("2024-01-00"), "day 0");
            assertTrue(CalendarDates.isValidDate("2024-02-29"), "leap year");
            assertThrows(DateTimeException.class, () -> LocalDate.of(2023, 2, 29),
                    "the guarded call must still be the thing that throws");
        }


        @Test
        void rangeChecksRejectWithoutThrowing()
        {
            assertFalse(CalendarDates.isValidDate("2024-13-01"), "month 13");
            assertFalse(CalendarDates.isValidDate("2024-00-01"), "month 0");
            assertFalse(CalendarDates.isValidDate("2024-01-01T24"), "hour 24");
            assertFalse(CalendarDates.isValidDate("2024-01-01T23:60"), "minute 60");
            assertFalse(CalendarDates.isValidDate("2024-01-01T23:59:60"), "second 60");
            assertTrue(CalendarDates.isValidDate("2024-01-01T23:59:59"));
        }
    }


    /**
     * {@code Fix #215} — the calendar checks apply to every component a <b>masked</b> value
     * carries, not just to the ones before its first gap.
     *
     * <p>
     * &#9888;&#9888; This is the trap Phase 3 had to clear. The pre-{@code Fix #215} body returned
     * {@code true} at the first {@link net.cumba.corej.core.exec.IsoDateComponents#ABSENT}
     * component, which was sound only while absence was a trailing run. Had it survived the
     * widening, {@code 2012---32} and {@code ----13-01} would both have been accepted as valid
     * dates — a silent hole rather than a loud one.
     * </p>
     */
    @Nested
    @DisplayName("a masked component is UNKNOWN, not unchecked")
    class MaskedCalendarValidation
    {

        @ParameterizedTest
        @ValueSource(strings =
        {
                "2012-06--", "2012-06-", "2012---15", "2012--15", "----06-15", "--06-15",
                // ⚑ possible under SOME completion of the unknown component, so valid:
                "2012---31", "----02-29", "2012-12--"
        })
        void possibleMaskedValuesAreValid(String input)
        {
            assertTrue(CalendarDates.isValidDate(input), input);
            assertTrue(CalendarDates.isPartialDate(input), input + " is partial, never complete");
            assertFalse(CalendarDates.isCompleteDate(input), input);
        }


        @ParameterizedTest
        @ValueSource(strings =
        {
                // month known and impossible
                "2012-13--", "2012-00--", "----13-15", "----00-15",
                // day known and impossible under EVERY completion
                "2012---32", "2012---00", "----02-30", "----04-31", "----06-31"
        })
        void impossibleMaskedValuesAreStillInvalid(String input)
        {
            assertFalse(CalendarDates.isValidDate(input), input);
        }


        @Test
        void theUnknownComponentWidensTheRangeExactlyAsFarAsItShould()
        {
            // Month unknown ⇒ the day is judged against the longest month, not against January.
            assertTrue(CalendarDates.isValidDate("2012---30"), "some month has 30 days");
            assertTrue(CalendarDates.isValidDate("2012---31"), "some month has 31 days");
            assertFalse(CalendarDates.isValidDate("2012---32"), "no month has 32");
            // Year unknown ⇒ February gets its leap length, and only February.
            assertTrue(CalendarDates.isValidDate("----02-29"), "some year is a leap year");
            assertFalse(CalendarDates.isValidDate("----02-30"), "no February has 30 days");
            // Both known ⇒ unchanged, leap-aware behaviour.
            assertFalse(CalendarDates.isValidDate("2023-02-29"), "2023 is not a leap year");
            assertTrue(CalendarDates.isValidDate("2024-02-29"), "2024 is");
        }
    }

    // ---- the pre-Fix-#209 body, kept as the oracle ---------------------------

    /**
     * {@code CalendarDates.isValidDate} exactly as it stood at {@code b2ea8a714}, including its
     * fixed-offset {@code parse2} reads and its {@code DateTimeException}-only handler. Retained so
     * the differential comparison — and the crash itself — are executable rather than asserted.
     */
    private static boolean legacyIsValidDate(String s)
    {
        if (s == null)
        {
            return false;
        }
        int slash = s.indexOf('/');
        if (slash >= 0)
        {
            return legacyIsValidDate(s.substring(0, slash))
                    && legacyIsValidDate(s.substring(slash + 1));
        }
        String core = strip(s);
        // ⚠⚠ Fix #215 — this MUST be the pinned pre-#215 gate, not the live
        // ScalarSemantics.isPartialDate. Calling the live one made the oracle track the code it is
        // supposed to be an oracle for: once the gate was widened, the old body sailed past it on
        // "2012-06-" and crashed at parse2(core, 8), so the differential comparison below died
        // instead of reporting. See theWidenedGateWouldHaveCrashedTheOldBody.
        if (!legacyIsPartialDate(core))
        {
            return false;
        }
        int len = core.length();
        try
        {
            if (len == 4)
            {
                return true;
            }
            int month = parse2(core, 5);
            if (month < 1 || month > 12)
            {
                return false;
            }
            if (len == 7)
            {
                return true;
            }
            int year = Integer.parseInt(core.substring(0, 4));
            int day = parse2(core, 8);
            LocalDate.of(year, month, day);
            if (len == 10)
            {
                return true;
            }
            int hour = parse2(core, 11);
            if (hour > 23)
            {
                return false;
            }
            if (len == 13)
            {
                return true;
            }
            int minute = parse2(core, 14);
            if (minute > 59)
            {
                return false;
            }
            if (len == 16)
            {
                return true;
            }
            int second = parse2(core, 17);
            return second <= 59;
        }
        catch (DateTimeException _)
        {
            return false;
        }
    }


    private static int parse2(String s, int offset)
    {
        return (s.charAt(offset) - '0') * 10 + s.charAt(offset + 1) - '0';
    }


    /**
     * {@code ScalarSemantics.isPartialDate} as it stood <b>before</b> {@code Fix #215} — the
     * truncation-only length gate. Kept locally so {@link #legacyIsValidDate(String)} is a real
     * oracle rather than a wrapper around whatever the shipped gate currently does.
     */
    private static boolean legacyIsPartialDate(String s)
    {
        int slash = s.indexOf('/');
        if (slash >= 0)
        {
            return legacyIsPartialDate(s.substring(0, slash))
                    && legacyIsPartialDate(s.substring(slash + 1));
        }
        String v = strip(s);
        int len = v.length();
        if (len != 4 && len != 7 && len != 10 && len != 13 && len != 16 && len != 19)
        {
            return false;
        }
        for (int i = 0; i < len; i++)
        {
            char c = v.charAt(i);
            char separator = switch (i)
            {
            case 4, 7 -> '-';
            case 10 -> 'T';
            case 13, 16 -> ':';
            // Not a separator position: the character must be an ASCII digit.
            default -> 0;
            };
            boolean ok = separator == 0 ? c >= '0' && c <= '9' : c == separator;
            if (!ok)
            {
                return false;
            }
        }
        return true;
    }
}
