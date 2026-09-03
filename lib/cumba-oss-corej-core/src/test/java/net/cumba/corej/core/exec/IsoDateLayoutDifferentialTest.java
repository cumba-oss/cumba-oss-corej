package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Fix #209, Phase 2b prerequisite A — {@link ScalarSemantics#isoComponents(String)} is the single
 * structural walk behind {@link ScalarSemantics#isPartialDate(String)}, and replacing the old
 * hand-inlined walk moved <b>nothing</b>.
 *
 * <p>
 * The proof is differential: {@link #legacyIsPartialDate(String)} below is the pre-Fix-#209
 * implementation, copied verbatim from {@code ScalarSemantics.isPartialDate} at {@code b2ea8a714},
 * and {@link IsoDateCorpus} generates every shape the boundary logic can distinguish — exhaustive
 * short strings, single-character mutations of every canonical form at every position, and seeded
 * random noise. A disagreement anywhere fails the test.
 * </p>
 *
 * <p>
 * &#9873; This is the guard the widening phase has to keep green while it deliberately changes one
 * side: the point is that a future edit to {@code isoComponents} can never move
 * {@code isPartialDate} <em>by accident</em>.
 * </p>
 */
class IsoDateLayoutDifferentialTest
{

    private static final int ABSENT = IsoDateComponents.ABSENT;

    // ---- the pre-Fix-#209 implementation, kept as the oracle -----------------

    /** {@code ScalarSemantics.isPartialDate} exactly as it stood at {@code b2ea8a714}. */
    private static boolean legacyIsPartialDate(String s)
    {
        if (s == null)
        {
            return false;
        }
        int slash = s.indexOf('/');
        if (slash >= 0)
        {
            return legacyIsPartialDate(s.substring(0, slash))
                    && legacyIsPartialDate(s.substring(slash + 1));
        }
        s = ScalarSemantics.stripFractionalSeconds(ScalarSemantics.stripTimezone(s));
        int len = s.length();
        if (len != 4 && len != 7 && len != 10 && len != 13 && len != 16 && len != 19)
        {
            return false;
        }
        if (!digit(s, 0) || !digit(s, 1) || !digit(s, 2) || !digit(s, 3))
        {
            return false;
        }
        if (len == 4)
        {
            return true;
        }
        if (s.charAt(4) != '-')
        {
            return false;
        }
        if (!digit(s, 5) || !digit(s, 6))
        {
            return false;
        }
        if (len == 7)
        {
            return true;
        }
        if (s.charAt(7) != '-')
        {
            return false;
        }
        if (!digit(s, 8) || !digit(s, 9))
        {
            return false;
        }
        if (len == 10)
        {
            return true;
        }
        if (s.charAt(10) != 'T')
        {
            return false;
        }
        if (!digit(s, 11) || !digit(s, 12))
        {
            return false;
        }
        if (len == 13)
        {
            return true;
        }
        if (s.charAt(13) != ':')
        {
            return false;
        }
        if (!digit(s, 14) || !digit(s, 15))
        {
            return false;
        }
        if (len == 16)
        {
            return true;
        }
        if (s.charAt(16) != ':')
        {
            return false;
        }
        return digit(s, 17) && digit(s, 18);
    }


    private static boolean digit(String s, int i)
    {
        char c = s.charAt(i);
        return c >= '0' && c <= '9';
    }

    @Nested
    @DisplayName("isPartialDate moved on EXACTLY the masked class, and nowhere else")
    class WidenedByExactlyTheMasks
    {

        /**
         * &#9873;&#9873; {@code Fix #215} — this test used to assert <b>zero</b> disagreement with
         * the pre-{@code Fix #209} body. Phase 3 deliberately moves one side, so the assertion is
         * now the <em>shape</em> of the movement rather than its absence: every disagreement is a
         * value that {@link ScalarSemantics#isMaskedDate} accepts, and every one of them moved
         * {@code false} &rarr; {@code true}. A widening can only <b>add</b> acceptances; a
         * disagreement in the other direction would be a regression, and this catches it.
         */
        @Test
        void everyDisagreementWithTheLegacyBodyIsAMaskedValueThatBecameTrue()
        {
            List<String> wrongDirection = new ArrayList<>();
            List<String> notMasked = new ArrayList<>();
            List<String> movers = new ArrayList<>();
            for (String s : IsoDateCorpus.all())
            {
                if (legacyIsPartialDate(s) == ScalarSemantics.isPartialDate(s))
                {
                    continue;
                }
                movers.add(s);
                if (!ScalarSemantics.isPartialDate(s))
                {
                    wrongDirection.add(s);
                }
                if (!ScalarSemantics.isMaskedDate(s))
                {
                    notMasked.add(s);
                }
            }
            assertEquals(List.of(), wrongDirection, "a widening cannot REMOVE an acceptance");
            assertEquals(List.of(), notMasked, "a non-masked value moved");
            // Measured 2026-08-11: 75 corpus rows, 65 distinct strings — the six canonical masked
            // shapes plus 0000-00--, 2012--06 and every single-decoration variant of them.
            assertEquals(75, movers.size(), "the moving set moved");
            assertEquals(65, movers.stream().distinct().count(), "distinct movers");
        }


        @Test
        void theCorpusIsNotVacuous()
        {
            // Neuter-and-watch control: the corpus must contain both verdicts, or the loop above
            // would pass against any implementation that answers a constant.
            long accepted = IsoDateCorpus.all().stream().filter(ScalarSemantics::isPartialDate)
                    .count();
            // Measured 2026-08-11: 413 of 73 056 (338 before Fix #215, +75 masked). Both verdicts
            // must be well represented, or the differential loop above would pass against an
            // implementation answering a constant.
            assertEquals(413, accepted, "the corpus's accepted count moved");
            assertEquals(73_056, IsoDateCorpus.all().size(), "the corpus itself moved");
        }


        /**
         * The invariance {@code PLAN-partial-date-predicate-impact.md} &#167;R3.3 proves on paper
         * for the 49 {@code is_complete_date} rules and the 19 {@code is_not_complete_date_part}
         * rules — here by execution over the whole corpus. Widening the <em>partial</em> gate
         * cannot move the <em>complete</em> predicate, because every value it newly admits is
         * length &le; 9 and so fails the completeness length gate outright.
         */
        @Test
        void isCompleteDateIsUntouchedByTheWidening()
        {
            for (String s : IsoDateCorpus.all())
            {
                if (legacyIsPartialDate(s) == ScalarSemantics.isPartialDate(s))
                {
                    continue;
                }
                assertFalse(ScalarSemantics.isCompleteDate(s),
                        "a newly-accepted partial date must never be COMPLETE: " + s);
            }
        }


        @Test
        void nullIsStillFalse()
        {
            assertFalse(ScalarSemantics.isPartialDate(null));
        }
    }


    @Nested
    @DisplayName("the decoder and the gate cannot disagree")
    class GateAndLayoutAgree
    {

        @Test
        void layoutIsNonNullForExactlyTheNormalisedStringsTheGateAccepts()
        {
            for (String s : IsoDateCorpus.all())
            {
                if (s.indexOf('/') >= 0)
                {
                    continue; // the gate's interval recursion has no single layout
                }
                String core = ScalarSemantics
                        .stripFractionalSeconds(ScalarSemantics.stripTimezone(s));
                assertEquals(ScalarSemantics.isPartialDate(s),
                        ScalarSemantics.isoComponents(core) != null,
                        () -> "gate/layout disagree on \"" + s + "\" (core \"" + core + "\")");
            }
        }


        @Test
        void theDecoderDoesNotRenormalise()
        {
            // The contract that removes the crash class: the decoder reads the string it is
            // handed. A value still carrying an offset is NOT a layout, even though stripping it
            // would produce one.
            assertNull(ScalarSemantics.isoComponents("2012-06-15Z"));
            assertNull(ScalarSemantics.isoComponents("2012-06-15+01:00"));
            assertNotNull(ScalarSemantics.isoComponents("2012-06-15"));
        }


        @Test
        void nullDecodesToNull()
        {
            assertNull(ScalarSemantics.isoComponents(null));
        }


        /**
         * &#9873;&#9873; <b>The containment {@code Fix #215} rests on.</b> {@code isoComponents}
         * accepts exactly the pre-{@code Fix #215} truncation layouts <b>plus</b> exactly
         * {@link ScalarSemantics#isMaskedDate}'s shapes — no more.
         *
         * <p>
         * This is not tidiness. {@code IsoDateBounds.bound} dispatches on {@code isMaskedDate} and
         * sends everything else to {@code truncatedBound}, which reads fixed substrings. A shape
         * this decoder accepted but {@code isMaskedDate} rejected would reach that reader as a
         * value it cannot decode — measured, that produced garbage bounds and uncaught exceptions.
         * Referenced by name from {@code ScalarSemantics.isoComponents}' javadoc.
         * </p>
         */
        @Test
        void maskedAcceptanceIsExactlyIsMaskedDate()
        {
            for (String s : IsoDateCorpus.all())
            {
                // (a) Nothing outside the two families is decoded. ⚠ isMaskedDate normalises and
                // this decoder does not, so the implication is one-way on a decorated value.
                if (ScalarSemantics.isoComponents(s) != null)
                {
                    assertTrue(legacyIsoLayout(s) || ScalarSemantics.isMaskedDate(s),
                            () -> "isoComponents accepted a shape outside truncation ∪ masked: \""
                                    + s + "\"");
                }
                // (b) …and every masked value decodes once it is normalised, which is exactly the
                // string isPartialDate and CalendarDates.isValidDate hand it.
                if (ScalarSemantics.isMaskedDate(s))
                {
                    String core = ScalarSemantics
                            .stripFractionalSeconds(ScalarSemantics.stripTimezone(s.trim()));
                    assertNotNull(ScalarSemantics.isoComponents(core),
                            () -> "a masked value did not decode: \"" + s + "\" core \"" + core
                                    + "\"");
                }
            }
        }
    }

    /**
     * {@code isoComponents != null} exactly as it stood before {@code Fix #215}: the truncation
     * layouts only, on the string as given (this decoder never normalises).
     */
    private static boolean legacyIsoLayout(String s)
    {
        int len = s.length();
        if (len != 4 && len != 7 && len != 10 && len != 13 && len != 16 && len != 19)
        {
            return false;
        }
        if (!digit(s, 0) || !digit(s, 1) || !digit(s, 2) || !digit(s, 3))
        {
            return false;
        }
        if (len == 4)
        {
            return true;
        }
        if (s.charAt(4) != '-' || !digit(s, 5) || !digit(s, 6))
        {
            return false;
        }
        if (len == 7)
        {
            return true;
        }
        if (s.charAt(7) != '-' || !digit(s, 8) || !digit(s, 9))
        {
            return false;
        }
        if (len == 10)
        {
            return true;
        }
        if (s.charAt(10) != 'T' || !digit(s, 11) || !digit(s, 12))
        {
            return false;
        }
        if (len == 13)
        {
            return true;
        }
        if (s.charAt(13) != ':' || !digit(s, 14) || !digit(s, 15))
        {
            return false;
        }
        if (len == 16)
        {
            return true;
        }
        return s.charAt(16) == ':' && digit(s, 17) && digit(s, 18);
    }

    @Nested
    @DisplayName("the decoded components")
    class Components
    {

        @ParameterizedTest(name = "{0}")
        @CsvSource(
        {
                "2012,          2012, -1, -1, -1, -1, -1",
                "2012-06,       2012,  6, -1, -1, -1, -1",
                "2012-06-15,    2012,  6, 15, -1, -1, -1",
                "2012-06-15T10, 2012,  6, 15, 10, -1, -1",
                "2012-06-15T10:30,    2012, 6, 15, 10, 30, -1",
                "2012-06-15T10:30:45, 2012, 6, 15, 10, 30, 45",
                // no calendar validation here — the split CalendarDates owns is preserved
                "2026-02-30,    2026,  2, 30, -1, -1, -1",
                "0000-00-00,       0,  0,  0, -1, -1, -1",
                "9999-99-99T99:99:99, 9999, 99, 99, 99, 99, 99",
                // ⚑ Fix #215 — the SDTM masked shapes, in both spellings. Note the INTERIOR
                // absences: the month is unknown in 2012---15 and the day is still 15.
                "2012-06--,     2012,  6, -1, -1, -1, -1",
                "2012-06-,      2012,  6, -1, -1, -1, -1",
                "2012---15,     2012, -1, 15, -1, -1, -1",
                "2012--15,      2012, -1, 15, -1, -1, -1",
                "----06-15,       -1,  6, 15, -1, -1, -1",
                "--06-15,         -1,  6, 15, -1, -1, -1",
                // …and still no calendar validation on a masked value either
                "2012---32,     2012, -1, 32, -1, -1, -1", "----13-15,       -1, 13, 15, -1, -1, -1"
        })
        void decodeEveryTier(String input, int year, int month, int day, int hour, int minute,
                int second)
        {
            IsoDateComponents c = ScalarSemantics.isoComponents(input);
            assertNotNull(c, input);
            assertEquals(new IsoDateComponents(year, month, day, hour, minute, second), c);
        }


        @ParameterizedTest
        @ValueSource(strings =
        {
                // ⚠ The masked shapes moved OUT of this list at Fix #215 — see decodeEveryTier.
                // What remains here are their near-misses: a hyphen is legal only as a WHOLE
                // component, so a half-masked month or a one-digit day is still not a layout.
                "", "2012-", "201", "20126", "2012-6", "2012-06-1", "2012-06-15X10",
                "2012-06-15T10-30", "2012-06-15T10:30-45", "2012_06_15", "banana", "2012-0--15",
                "2012----15", "2012---5", "---06-15", "2012-06---", "2012-06-15Z", "2012-06--Z"
        })
        void nonPrefixesDecodeToNull(String input)
        {
            assertNull(ScalarSemantics.isoComponents(input), input);
        }


        @Test
        void absentIsNegativeSoItCannotCollideWithADecodedField()
        {
            // Every two-digit field decodes to 0..99, so a negative sentinel is unambiguous.
            assertTrue(ABSENT < 0);
            for (String s : IsoDateCorpus.all())
            {
                IsoDateComponents c = ScalarSemantics.isoComponents(s);
                if (c != null)
                {
                    // ⚠ Fix #215 — the year is ABSENT for a year-masked value; it was previously
                    // asserted always present.
                    assertTrue(c.year() == ABSENT || (c.year() >= 0 && c.year() <= 9999), s);
                    for (int f : new int[]
                    {
                            c.month(), c.day(), c.hour(), c.minute(), c.second()
                    })
                    {
                        assertTrue(f == ABSENT || (f >= 0 && f <= 99), s + " field " + f);
                    }
                }
            }
        }


        /**
         * &#9873;&#9873; {@code Fix #215} inverted this test. It used to assert that an
         * {@link IsoDateComponents#ABSENT} component is always a <b>trailing run</b> — the contract
         * {@code CalendarDates} relied on when it returned at the first absent component. A masked
         * value breaks that by design ({@code 2012---15} has an absent month and a present day), so
         * what is pinned now is <em>which</em> values may carry an interior absence: exactly the
         * masked ones. A truncation prefix that grew an interior hole would be a decoder bug.
         */
        @Test
        void anInteriorAbsenceMeansAMaskedValueAndNothingElse()
        {
            int interior = 0;
            for (String s : IsoDateCorpus.all())
            {
                IsoDateComponents c = ScalarSemantics.isoComponents(s);
                if (c == null)
                {
                    continue;
                }
                int[] fields =
                {
                        c.year(), c.month(), c.day(), c.hour(), c.minute(), c.second()
                };
                boolean seenAbsent = false;
                boolean hasInterior = false;
                for (int f : fields)
                {
                    if (f == ABSENT)
                    {
                        seenAbsent = true;
                    }
                    else if (seenAbsent)
                    {
                        hasInterior = true;
                    }
                }
                if (hasInterior)
                {
                    interior++;
                    assertTrue(ScalarSemantics.isMaskedDate(s),
                            "interior ABSENT in a value that is not masked: \"" + s + "\"");
                }
            }
            // Neuter-and-watch control: without this the loop passes vacuously on a decoder that
            // never produces an interior absence at all — i.e. on the pre-Fix-#215 body.
            assertTrue(interior > 0, "no interior absence in the corpus — the widening is inert");
        }


        /** A day-masked value is a trailing absence, so it is NOT the interior class. */
        @ParameterizedTest
        @CsvSource(
        {
                "2012-06--, false", "2012-06-, false", "2012---15, true", "2012--15, true",
                "----06-15, true", "--06-15, true", "2012-06, false", "2012, false"
        })
        void whichMaskedShapesCarryAnInteriorAbsence(String input, boolean interior)
        {
            IsoDateComponents c = ScalarSemantics.isoComponents(input);
            assertNotNull(c, input);
            boolean seenAbsent = false;
            boolean found = false;
            for (int f : new int[]
            {
                    c.year(), c.month(), c.day(), c.hour(), c.minute(), c.second()
            })
            {
                if (f == ABSENT)
                {
                    seenAbsent = true;
                }
                else if (seenAbsent)
                {
                    found = true;
                }
            }
            assertEquals(interior, found, input);
        }
    }

}
