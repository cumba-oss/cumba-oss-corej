package net.cumba.cdisc.core.expr.eval;

import static net.cumba.cdisc.core.expr.eval.VectorLayerTest.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import net.cumba.cdisc.core.exec.MockTable;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Q16 — the {@code date_*} family compares over the <b>range</b> a possibly-incomplete ISO value
 * denotes: {@code A op B} means "A op <i>every</i> candidate of B", uniformly for all six
 * operators. See {@link IsoDateComparison}.
 *
 * <h2>&#9888;&#9888; Why this class exists in this shape</h2>
 * <p>
 * EC-46 / Fix #142 measured <b>zero</b> partial, masked, offset-bearing or unpositionable values in
 * the whole scenario corpus and called its own exposure <i>latent</i>. Re-measured for Q16 across
 * all <b>4,718</b> {@code .cdt} fixtures: of the 110 rules carrying a {@code date_*} comparison, 65
 * have fixtures at all, and their <b>538</b> date-column cells are 484 complete dates and 54 blanks
 * — <b>0</b> year-only, <b>0</b> year-month, <b>0</b> masked. Meanwhile {@code /data/testdata}
 * holds <b>136,324</b> year-only and <b>59,949</b> year-month {@code --DTC} cells. &#8658; <b>Not
 * one existing fixture can exercise any of this</b>, so the fixtures below are a deliverable, not a
 * convenience: without them every assertion here would pass vacuously.
 * </p>
 * <p>
 * &#9873; The whole {@link #MATRIX} was <b>measured against the pre-Q16 engine first</b>, and the
 * "before" column is recorded per row. Fourteen of the twenty-one rows differ from that baseline,
 * so those rows cannot be vacuous — they would have failed before the change. The seven that agree
 * are the deliberate pins (complete-vs-complete, the two &sect;2.1 worked examples, the two masked
 * rows that were already right, and the absent-column short-circuit).
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class DateComparisonPartialSemanticsTest
{

    // ---- the operator table --------------------------------------------------

    /** {@code direction, orEqual, negate} for the six {@code date_*} operators, in table order. */
    private static final int[][] OPS =
    {
            {
                    0, 1, 0
            }, // ==
            {
                    0, 1, 1
            }, // !=
            {
                    1, 0, 0
            }, // >
            {
                    1, 1, 0
            }, // >=
            {
                    -1, 0, 0
            }, // <
            {
                    -1, 1, 0
            }, // <=
    };

    private static final String[] OP_NAMES =
    {
            "==", "!=", ">", ">=", "<", "<="
    };

    /**
     * One row per operand pair: {@code id}, {@code A}, {@code B}, then the six expected verdicts as
     * {@code T}/{@code F} in {@link #OP_NAMES} order, then the pre-Q16 baseline in the same form.
     *
     * <p>
     * The baseline column is not asserted — it is the measurement that proves the row is not
     * vacuous, kept next to the expectation so a future reader can see which rows moved and which
     * are pins.
     * </p>
     */
    private static final String[][] MATRIX =
    {
            // -- complete vs complete: MUST be bit-identical to the pre-Q16 engine.
            // (Scoped by Fix #250: shapes with no fractional tail, no leading whitespace and no
            // stacked tail, like these five rows — a fractional-second tail or a leading space
            // now compares through its CORE and deliberately differs from the pre-Q16 raw
            // reading; a single offset like C5's was always normalized identically by both.
            // See IsoDateComparisonReadableCoreTest.) ------------------------------------------
            {
                    "C1 equal days", "2026-01-17", "2026-01-17", "TFFTFT", "TFFTFT"
            },
            {
                    "C2 later day", "2026-01-18", "2026-01-17", "FTTTFF", "FTTTFF"
            },
            {
                    "C3 earlier day", "2026-01-16", "2026-01-17", "FTFFTT", "FTFFTT"
            },
            {
                    // Mixed precision, but BOTH complete: still compared at the coarser precision.
                    "C4 day vs second", "2024-01-15", "2024-01-15T13:30:00", "TFFTFT", "TFFTFT"
            },
            {
                    // The offset shifts the value across midnight before the comparison.
                    "C5 offset midnight shift", "2024-03-16T01:30+02:00", "2024-03-15", "TFFTFT",
                    "TFFTFT"
            },

            // -- truncated partials: the range reading ----------------------------------------
            {
                    // §2.1 verbatim: "a 2026-01-17 is not gt, lt or eq to it".
                    "P1 day vs month", "2026-01-17", "2026-01", "FFFFFF", "TFFTFT"
            },
            {
                    // §2.1 verbatim: "but a 2026-02-01 is gt".
                    "P2 after the month", "2026-02-01", "2026-01", "FTTTFF", "FTTTFF"
            },
            {
                    // §2.1 verbatim: "and a 2025-12-31 is lt".
                    "P3 before the month", "2025-12-31", "2026-01", "FTFFTT", "FTFFTT"
            },
            {
                    // ⚠⚠ Baseline all-T: a YEAR parses as a number, so the pre-Q16 engine took the
                    // "mixed numeric/ISO shape ⇒ violation" branch and fired == and != at once.
                    "P4 day vs year", "2026-01-17", "2026", "FFFFFF", "TTTTTT"
            },
            {
                    "P5 month vs same month", "2026-01", "2026-01", "FFFFFF", "TFFTFT"
            },
            {
                    "P6 month vs day", "2026-01", "2026-01-17", "FFFFFF", "TFFTFT"
            },

            // -- masked components: legal data, EC-46's mask-aware hulls ----------------------
            {
                    // T4: 2012---15 is [2012-01-15, 2012-12-15]; 2013-01-01 is past all of it.
                    "M1 after a month-masked hull", "2013-01-01", "2012---15", "FTTTFF", "FTTTFF"
            },
            {
                    // T4: inside the hull ⇒ nothing can be established. Baseline said "greater",
                    // by comparing the digit '0' against the placeholder '-'.
                    "M2 inside a month-masked hull", "2012-06-20", "2012---15", "FFFFFF", "FTTTFF"
            },
            {
                    "M3 after a day-masked hull", "2012-07-01", "2012-06--", "FTTTFF", "FTTTFF"
            },
            {
                    "M4 inside a day-masked hull", "2012-06-15", "2012-06--", "FFFFFF", "TFFTFT"
            },

            // -- unpositionable operands: every comparison FALSE ------------------------------
            {
                    // ⚠⚠ THE BLOCKING DEFECT: the pre-Q16 engine answered "equal" to a blank, so
                    // every date_*_or_equal_to fired on every row.
                    "B1 blank right operand", "2026-01-17", "", "FFFFFF", "TFFTFT"
            },
            {
                    "B2 junk right operand", "2026-01-17", "UNKNOWN", "FFFFFF", "FTFFTT"
            },
            {
                    "B3 year-masked right operand", "2026-01-17", "----06-15", "FFFFFF", "FTTTFF"
            },
            {
                    "B4 calendar-impossible right operand", "2026-01-17", "2026-02-30", "FFFFFF",
                    "FTFFTT"
            },
            {
                    // ⚑ NOT saturation: the absent-column short-circuit runs first and is
                    // deliberately untouched, so a negative leaf still fires on a blank LEFT cell.
                    "B5 blank left operand", "", "2026-01-17", "FTFFFF", "FTFFFF"
            },
            {
                    "B6 junk left operand", "UNKNOWN", "2026-01-17", "FFFFFF", "FTTTFF"
            },
    };

    static List<String[]> matrix()
    {
        return List.of(MATRIX);
    }


    private static boolean fires(String a, String b, int op)
    {
        IDataTable t = MockTable.of().col("A", a).col("B", b).build();
        BitSet r = Primitives.dateComparison(col(t, "A"), col(t, "B"), 1, OPS[op][0],
                OPS[op][1] == 1, OPS[op][2] == 1);
        return r.get(0);
    }


    /** The hull bound, asserted present — the test-side companion of {@code latest_possible}. */
    private static String boundOf(String value, boolean high)
    {
        String b = IsoDateComparison.bound(value, high);
        assertNotNull(b, "expected a bounded hull for '" + value + "'");
        return b;
    }


    @ParameterizedTest(name = "{0}")
    @MethodSource("matrix")
    @DisplayName("the §2.2/§2.3 table, one operand pair per case, all six operators")
    void theTable(String id, String a, String b, String expected, String baseline)
    {
        StringBuilder actual = new StringBuilder();
        for (int op = 0; op < OPS.length; op++)
        {
            actual.append(fires(a, b, op) ? 'T' : 'F');
        }
        assertEquals(expected, actual.toString(), () -> id + ": '" + a + "' vs '" + b + "' over "
                + String.join(",", OP_NAMES) + " — pre-Q16 baseline was " + baseline);
    }

    // ---- T3: complete-vs-complete is unchanged, and structurally so ------------


    /**
     * T3. Every complete-vs-complete row is asserted above against its measured pre-Q16 value; this
     * pins the <i>mechanism</i> that guarantees it, so a future refactor cannot quietly route the
     * complete case through the hulls and rediscover the {@code T00:00:00 >= T23:59:59} trap.
     */
    @Test
    void twoCompleteDatesTakeThePreQ16PathAndAreNotHulled()
    {
        // The hulls of a complete day-precision value are NOT a point — they span the day.
        assertEquals("2026-01-17T00:00:00", IsoDateBounds.lower("2026-01-17"));
        assertEquals("2026-01-17T23:59:59", IsoDateBounds.upper("2026-01-17"));

        // ⇒ a naive lower(A) >= upper(B) would answer FALSE for two identical dates. It does not,
        // because the complete/complete case never reaches the hull rule.
        assertTrue(fires("2026-01-17", "2026-01-17", 3), "'>=' on two equal complete dates");
        assertTrue(fires("2026-01-17", "2026-01-17", 5), "'<=' on two equal complete dates");
        assertTrue(fires("2026-01-17", "2026-01-17", 0), "'==' on two equal complete dates");
    }


    /** T1's complementarity arm — it holds exactly where both operands are positionable points. */
    @Test
    void complementarityHoldsForCompleteOperandsAndIsDeliberatelyBrokenForUnpositionableOnes()
    {
        for (String[] pair : new String[][]
        {
                {
                        "2026-01-17", "2026-01-17"
                },
                {
                        "2026-01-18", "2026-01-17"
                },
                {
                        "2026-01-16", "2026-01-17"
                },
        })
        {
            String a = pair[0];
            String b = pair[1];
            assertEquals(fires(a, b, 0), !fires(a, b, 1), "== / != complement for " + a + "," + b);
            assertEquals(fires(a, b, 2), !fires(a, b, 5), "> / <= complement for " + a + "," + b);
            assertEquals(fires(a, b, 4), !fires(a, b, 3), "< / >= complement for " + a + "," + b);
        }

        // ⚠ Intended: you cannot compare against a value you do not have, so BOTH halves of every
        // pair are false. Documented in §2.3 as a deliberate consequence of saturation.
        for (int op = 0; op < OPS.length; op++)
        {
            assertFalse(fires("2026-01-17", "", op), "blank comparand, operator " + OP_NAMES[op]);
        }
    }

    // ---- T5: the saturation guard is load-bearing ------------------------------


    /**
     * T5's positive control. Each unpositionable operand below is <b>only</b> rejected by the
     * unbounded-hull branch: it is a non-blank, non-null string that passes the absent-column
     * short-circuit and reaches {@link IsoDateComparison}, and its counterpart is a perfectly good
     * date. So a single filter decides the verdict, and neutering that filter reddens these rows —
     * which is what makes them a pin rather than a coincidence.
     */
    @Test
    void everyUnpositionableOperandReachesTheComparisonRatherThanBeingFilteredEarlier()
    {
        for (String junk : new String[]
        {
                "", "UNKNOWN", "----06-15", "2026-02-30"
        })
        {
            assertNull(IsoDateBounds.lower(junk), "unbounded hull: " + junk);
            assertNull(IsoDateBounds.upper(junk), "unbounded hull: " + junk);
            for (int op = 0; op < OPS.length; op++)
            {
                assertFalse(fires("2026-01-17", junk, op),
                        "'" + junk + "' as comparand, operator " + OP_NAMES[op]);
            }
        }

        // The control: the SAME left operand against a positionable partial is not uniformly
        // false, so the assertions above are about unpositionability and not about the left
        // operand or the harness.
        assertTrue(fires("2026-02-01", "2026-01", 2), "a positionable partial still orders");
    }

    // ---- T4 / EC-46 boundary: two hull models that must not merge --------------


    /**
     * ⚠⚠ The Fix&nbsp;#142 split, pinned. Saturating bounds belong to the <b>comparison</b> layer
     * only. {@link IsoDateBounds} must keep answering {@code null} — "the extreme cannot be
     * determined" — because {@code min_date} / {@code max_date} rely on it; under saturation
     * {@code max{2012-06-15, ----06-15}} would answer {@code 9999-06-15}, a confident-looking
     * answer that is false.
     */
    @Test
    void saturationLivesInTheComparisonLayerOnlyAndNeverLeaksIntoTheExtremeSelectors()
    {
        // The extreme selectors' view: unpositionable, full stop. No saturated sentinel anywhere.
        assertFalse(IsoDateBounds.canPosition("----06-15"));
        assertNull(IsoDateBounds.lower("----06-15"));
        assertNull(IsoDateBounds.upper("----06-15"));
        assertNull(IsoDateComparison.bound("----06-15", true));
        assertNull(IsoDateComparison.bound("", false));

        // A bounded masked value is positionable in BOTH layers and keeps its known components —
        // so the split above is about unboundedness, not about masking.
        assertTrue(IsoDateBounds.canPosition("2012---15"));
        assertEquals("2012-01-15", IsoDateComparison.bound("2012---15", false));
        assertEquals("2012-12-15", IsoDateComparison.bound("2012---15", true));

        // ⚠ 9999 must never be manufactured — it is a real clinical "ongoing" value, and
        // IsoDateBoundsTest already pins it as a positionable year.
        assertEquals("9999-01-01", IsoDateComparison.bound("9999", false));
        assertTrue(fires("9999-12-31", "9998", 2),
                "a real 9999 date still orders normally against a partial");
        assertFalse(fires("9999-12-31", "", 3),
                "…but a blank comparand does not become 9999-12-31 and make '>=' fire");
    }

    // ---- §2.2b: the escape hatch makes all four quantifiers expressible --------


    /**
     * &sect;2.2b's worked example — {@code CDISC-CG0079}, <i>"raise an error when MHSTDTC is
     * populated and is the same as or later than RFSTDTC"</i>, with a month-precision history date.
     * The operator's own reading is "definitely"; the author can write the "possibly" reading.
     */
    @Test
    void theBoundBuiltinsExposeBothReadingsOfTheSameComparison()
    {
        String mhstdtc = "2026-01";
        String rfstdtc = "2026-01-17";

        // The default: not definitely on-or-after ⇒ no error. The history may have started 01-05.
        assertFalse(fires(mhstdtc, rfstdtc, 3), "the operator's own 'definitely' reading");

        // Spelt out, it agrees.
        assertFalse(fires(boundOf(mhstdtc, false), boundOf(rfstdtc, true), 3),
                "earliest_possible >= latest_possible");

        // The looser reading, which the author must now ask for explicitly.
        assertTrue(fires(boundOf(mhstdtc, true), boundOf(rfstdtc, false), 3),
                "latest_possible >= earliest_possible");

        // ⚑ And the spelt-out "definitely" form must not disagree with the default on COMPLETE
        // operands — which is why the bounds render at the value's own precision, not at seconds.
        assertTrue(fires(boundOf(rfstdtc, false), boundOf(rfstdtc, true), 3),
                "earliest_possible(X) >= latest_possible(X) for a complete X");
        assertEquals("2026-01-17", boundOf(rfstdtc, false));
        assertEquals("2026-01-17", boundOf(rfstdtc, true));
    }

    // ---- the year-precision numeric hijack ------------------------------------


    /**
     * &#9888;&#9888; A year-precision comparand used to be swallowed by the numeric branch:
     * {@code Double.parseDouble("2026")} succeeds while the character left operand stays
     * non-numeric, so the "mixed shape ⇒ violation" fallthrough fired <b>every operator on every
     * row</b> — measured, all six TRUE including {@code ==} and {@code !=} at once. The re-route is
     * deliberately narrow: only a calendar-valid ISO string wins back the date reading.
     */
    @Test
    void aYearPrecisionComparandIsReadAsADateAndNotAsANumber()
    {
        for (int op = 0; op < OPS.length; op++)
        {
            assertFalse(fires("2026-01-17", "2026", op),
                    "a day inside a year: " + OP_NAMES[op] + " cannot be established");
        }
        assertTrue(fires("2027-01-01", "2026", 2), "…but a later year is definitely greater");
        assertTrue(fires("2025-12-31", "2026", 4), "…and an earlier year is definitely less");

        // The control: a value that is NOT a calendar-valid ISO date keeps the old mixed-shape
        // verdict, so the re-route did not swallow the malformed-data signal wholesale.
        assertTrue(fires("2026-01-17", "20260117", 0), "a non-ISO numeric comparand still flags");
        assertTrue(fires("2026-01-17", "20260117", 4), "…for every direction");
    }

    // ---- Fix #212 / D4 — an interval's precision comes from its HALVES ----------


    /**
     * &#9888;&#9888; The hazard D4 removes, pinned step by step rather than folded into a comment —
     * the shape {@code IsoDateBoundsDispatchTest.theHazardIsReal} uses.
     *
     * <p>
     * {@code precisionOf} used to hand the <b>whole</b> {@code a/b} string to
     * {@code detectIsoPrecision}, which buckets purely on <b>length</b>. A month-precision interval
     * is 15 characters, so it read as tier 13 and the hull was clipped to the <i>hour</i>.
     * </p>
     */
    @Test
    void anIntervalsLengthIsNotItsPrecision()
    {
        // 1: a month-precision interval is 15 characters long.
        assertEquals(15, "2003-12/2003-12".length());
        // 2: detectIsoPrecision reads length 15 as HOUR precision (tier 13).
        assertEquals(13,
                net.cumba.cdisc.core.exec.ScalarSemantics.detectIsoPrecision("2003-12/2003-12"));
        // 3: but both halves are month precision (tier 7)…
        assertEquals(7, net.cumba.cdisc.core.exec.ScalarSemantics.detectIsoPrecision("2003-12"));
        // 4: …so the hull is rendered at DAY precision, not clipped to an hour.
        assertEquals("2003-12-01", boundOf("2003-12/2003-12", false));
        assertEquals("2003-12-31", boundOf("2003-12/2003-12", true));
    }


    /** The {@code earliest_possible} / {@code latest_possible} hull, per precision tier. */
    @ParameterizedTest(name = "{0} -> [{1}, {2}]")
    @CsvSource(
    {
            "2003-12/2003-12,                     2003-12-01,          2003-12-31",
            "2003-01/2003-06,                     2003-01-01,          2003-06-30",
            "2012/2013,                           2012-01-01,          2013-12-31",
            "2003-01-01/2003-06-30,               2003-01-01,          2003-06-30",
            "2003-12-15T10:00/2003-12-15T10:30,   2003-12-15T10:00,    2003-12-15T10:30",
            // Asymmetric halves take the COARSER of the two (D4). ⚑ The lower is Dec 15 — D1's
            // reading; the D3 min/max widening applies only to a backwards pair, so this VALUE
            // builtin answers the true earliest. See IsoDateBoundsTest.Intervals.
            "2003-12-15/2003-12,                  2003-12-15,          2003-12-31"
    })
    void anIntervalHullIsRenderedAtItsHalvesPrecision(String input, String lower, String upper)
    {
        assertEquals(lower, boundOf(input, false));
        assertEquals(upper, boundOf(input, true));
    }


    /**
     * The consequence for the six {@code date_*} operators — the reason D4 is not cosmetic.
     * {@code Primitives.java} routes all six into {@code IsoDateComparison.fires}, which takes the
     * hull path whenever either operand is not a complete date, and an interval never is. So the
     * hull <b>is</b> the comparison semantics for these values, and a wrong precision silently
     * clips it.
     */
    @Test
    void theClippedPrecisionUsedToSilenceAWholeDayComparison()
    {
        // The interval spans exactly December 2003; Dec 31 is its last possible day.
        assertTrue(fires("2003-12/2003-12", "2003-12-31", 5),
                "<= : every candidate of the interval is on or before Dec 31");
        assertTrue(fires("2003-12/2003-12", "2003-12-01", 3),
                ">= : every candidate is on or after Dec 1");
        // …and the interval is definitely after / before dates outside its span.
        assertTrue(fires("2003-12/2003-12", "2003-11-30", 2), "> : December is after Nov 30");
        assertTrue(fires("2003-12/2003-12", "2004-01-01", 4), "< : December is before Jan 1");
        // Inside the span nothing can be established, in either direction.
        assertFalse(fires("2003-12/2003-12", "2003-12-15", 2));
        assertFalse(fires("2003-12/2003-12", "2003-12-15", 4));
        // Symmetrically, with the interval on the right.
        assertTrue(fires("2003-11-30", "2003-12/2003-12", 4), "Nov 30 is before all of December");
        assertTrue(fires("2004-01-01", "2003-12/2003-12", 2), "Jan 1 is after all of December");
    }


    /** An unpositionable interval saturates exactly like any other unbounded value. */
    @Test
    void anUnpositionableIntervalMakesEverySixOperatorFalse()
    {
        for (int op = 0; op < OPS.length; op++)
        {
            assertFalse(fires("2003-12-15", "2003/2004/2005", op),
                    OP_NAMES[op] + " on a multi-solidus operand");
            assertFalse(fires("2003-12-15", "2003-12-15T10:00/P1D", op),
                    OP_NAMES[op] + " on a <datetime>/<duration> operand");
        }
        assertNull(IsoDateComparison.bound("2003/2004/2005", false));
        assertNull(IsoDateComparison.bound("2003-12-15T10:00/P1D", true));
    }

}
