package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.cumba.cdisc.core.expr.eval.CalendarDates;
import net.cumba.cdisc.core.expr.eval.IsoDateBounds;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-is-partial-date-masked-forms.md} <b>Phase 1</b> — the mask-aware structural validator
 * {@link ScalarSemantics#isMaskedDate} — <b>and Phase 3</b> ({@code Fix #215}), which widened
 * {@link ScalarSemantics#isPartialDate} to accept the same shapes.
 *
 * <p>
 * &#9873; Phase 1 was deliberately additive: {@code isMaskedDate} was a second predicate and no
 * existing consumer moved. Phase 3 is the flip — {@code isMaskedDate(s)} now <b>implies</b>
 * {@code isPartialDate(s)} (&#9888; for an <em>untrimmed</em> value — {@code isMaskedDate} trims
 * and {@code isPartialDate} does not, a pre-existing asymmetry nothing routes on), so the two are
 * no longer opposites, and what {@code isMaskedDate} answers alone is <em>which</em> convention a
 * value uses (the question {@code IsoDateBounds.bound} dispatches on).
 * </p>
 *
 * <p>
 * &#9873; The plan's Phase 1 also names a second predicate, {@code isPositionable}. It was not
 * added: <b>it already exists</b> as {@link IsoDateBounds#canPosition}, shipped with EC-46 /
 * Fix&nbsp;#142, with exactly the specified truth table. Its contract is pinned here rather than
 * duplicated — two predicates that can disagree would be a worse defect than the one the plan is
 * fixing.
 * </p>
 */
class ScalarSemanticsMaskedDateTest
{

    /** P1 — the three legal masked forms, in both their long and short spellings. */
    @Test
    void isMaskedDateAcceptsEveryLegalMaskedForm()
    {
        for (String masked : new String[]
        {
                "2012-06--", "2012-06-", // day masked
                "2012---15", "2012--15", // month masked, day 15 KNOWN
                "----06-15", "--06-15", // year masked
        })
        {
            assertTrue(ScalarSemantics.isMaskedDate(masked), masked + " is a legal masked date");
        }
    }


    /** P1 — a hyphen is legal only as a WHOLE component. */
    @Test
    void isMaskedDateRejectsAPartiallyMaskedComponentAndEveryNonMaskedShape()
    {
        for (String rejected : new String[]
        {
                "2012-0--15", // ⚠ half a month masked — the plan's named counter-example
                "2012-06-1-", "20-2---15", "UNKNOWN", "NA", "", "   ", "-", "----------",
                // Not masked: unmasked values are isPartialDate's business, not this predicate's.
                "2026", "2026-01", "2026-01-17", "2026-01-17T10:30", "2026-02-30",
        })
        {
            assertFalse(ScalarSemantics.isMaskedDate(rejected),
                    "'" + rejected + "' is not a masked date");
        }
        assertFalse(ScalarSemantics.isMaskedDate(null));
    }


    /**
     * &#9873;&#9873; <b>The Phase-3 flip ({@code Fix #215}).</b> This test previously pinned the
     * opposite — <i>"isPartialDate models ISO truncation and still rejects every masked form"</i> —
     * because Phase 1 was deliberately additive. Phase 3 (owner ruling 2026-08-09, option (a):
     * <i>widen in place</i>) makes a legal masked date a <b>valid partial date</b>, so
     * {@code invalid_date} no longer fires on one. The flip is pinned here, in the same test that
     * pinned its predecessor, so the change is visible rather than merely absent.
     *
     * <p>
     * &#9888; {@code isCompleteDate} is the half that must <b>not</b> move — every masked form is
     * length &le; 9 and so fails the structural completeness gate regardless. That is what makes
     * {@code PLAN-partial-date-predicate-impact.md} &#167;R3.3's invariance claim for the 49
     * {@code is_complete_date} rules and the 19 {@code is_not_complete_date_part} rules hold.
     * </p>
     */
    @Test
    void theStructuralValidatorNowAcceptsEveryMaskedForm()
    {
        for (String masked : new String[]
        {
                "2012-06--", "2012-06-", "2012---15", "2012--15", "----06-15", "--06-15"
        })
        {
            assertTrue(ScalarSemantics.isPartialDate(masked),
                    "a masked date IS a partial date since Fix #215: " + masked);
            assertTrue(CalendarDates.isValidDate(masked), "…and so is every gate above it");
            assertTrue(CalendarDates.isPartialDate(masked), "valid, and not complete");
            assertFalse(CalendarDates.isCompleteDate(masked),
                    "⚠ INVARIANT: a masked date is never COMPLETE — " + masked);
            assertFalse(CalendarDates.isCompleteDatePart(masked),
                    "⚠ INVARIANT: nor is its date part — " + masked);
        }
        // ⚠ Widening the structural gate did not collapse the calendar check: a masked value whose
        // KNOWN components are impossible is still invalid, in both directions of the mask.
        assertFalse(CalendarDates.isValidDate("2012-13--"), "month 13, day masked");
        assertFalse(CalendarDates.isValidDate("2012---32"), "day 32, month masked");
        assertFalse(CalendarDates.isValidDate("----02-30"), "30 February, year masked");
        assertTrue(CalendarDates.isValidDate("----02-29"),
                "⚑ 29 February IS possible when the year is unknown — some year is a leap year");
        assertTrue(CalendarDates.isValidDate("2012---31"),
                "⚑ day 31 IS possible when the month is unknown — some month has 31 days");
        // ⚠ Positioning is a separate question and did not move: the year-masked form is still
        // unbounded, so it is valid and yet cannot be placed on the calendar.
        assertTrue(IsoDateBounds.canPosition("2012-06--"));
        assertTrue(IsoDateBounds.canPosition("2012---15"));
        assertFalse(IsoDateBounds.canPosition("----06-15"));
    }


    /**
     * {@code Fix #215} — {@link ScalarSemantics#isMaskedDate} strips a trailing timezone /
     * fractional-seconds tail before matching, exactly as {@code isPartialDate} does.
     *
     * <p>
     * &#9888;&#9888; This is load-bearing, not cosmetic. {@code IsoDateBounds.bound} dispatches on
     * this predicate and routes everything it rejects to {@code truncatedBound}, which reads fixed
     * substrings of the string it was handed; its own {@code core()} strips only <b>one</b>
     * decoration. Without the strip here, a doubly-decorated masked value arrived at
     * {@code truncatedBound} as {@code "2012-06--Z"}, was waved through by the widened
     * {@code CalendarDates.isValidDate} (which strips again), and produced a garbage bound — for
     * four of them, an uncaught {@code NumberFormatException} / {@code DateTimeException} out of
     * the evaluator. Accepting them here sends them to {@code maskedBound}, whose anchored patterns
     * reject a decorated value, so they answer {@code null}: the pre-{@code Fix #215} verdict.
     * </p>
     */
    @Test
    void isMaskedDateToleratesTheSameDecorationsIsPartialDateDoes()
    {
        for (String decorated : new String[]
        {
                "2012-06--Z", "2012-06--+01:00", "2012-06--.000", "2012---15Z", "----06-15Z",
                "2012--15+0100"
        })
        {
            assertTrue(ScalarSemantics.isMaskedDate(decorated), decorated);
            assertTrue(ScalarSemantics.isPartialDate(decorated),
                    "isMaskedDate ⇒ isPartialDate: " + decorated);
        }
        // A value carrying TWO decorations is not masked-shaped even here (one strip still leaves
        // a residual tail), and it does not need to be: bound()'s own core() has already removed
        // the outer one, so what this predicate is asked is the single-decoration form above. The
        // property that matters is the outcome — truncatedBound never answers for any of them.
        assertNull(IsoDateBounds.lower("2012-06--ZZ"), "no garbage bound for a stacked decoration");
        assertNull(IsoDateBounds.upper("2012--15ZZ"), "…and no uncaught exception either");
        assertNull(IsoDateBounds.upper("--06-15Z+01:00"));
    }


    /**
     * P2 — {@code isPositionable}'s specified truth table, pinned on the predicate that already
     * implements it: true for complete, truncated-partial and <b>bounded</b> masked; false for
     * year-masked, blank and invalid.
     */
    @Test
    void canPositionIsTheSpecifiedPositioningPredicate()
    {
        for (String positionable : new String[]
        {
                "2026-01-17", "2026-01-17T10:30", "2026-01-17T10:30:00", // complete
                "2026", "2026-01", "2026-01-17T10", // truncated partial
                "2012-06--", "2012---15", // masked, bounded
        })
        {
            assertTrue(IsoDateBounds.canPosition(positionable), positionable + " is positionable");
        }
        for (String unpositionable : new String[]
        {
                "----06-15", "--06-15", // masked, UNBOUNDED — nothing anchors it
                "", "   ", "UNKNOWN", "NA", "2026-02-30", "2026-13-01",
        })
        {
            assertFalse(IsoDateBounds.canPosition(unpositionable),
                    "'" + unpositionable + "' cannot be positioned");
        }
    }


    /**
     * P5 — the structural-vs-calendar split. {@code 2026-02-30} is correctly <i>shaped</i> and
     * calendar-impossible; the structural validator accepts it while the calendar one rejects it.
     * That divergence is deliberate and parity-whitelisted, and nothing here may collapse it.
     */
    @Test
    void theStructuralVersusCalendarSplitIsPreserved()
    {
        assertTrue(ScalarSemantics.isPartialDate("2026-02-30"), "structurally well-formed");
        assertFalse(CalendarDates.isValidDate("2026-02-30"), "calendar-impossible");
        assertFalse(ScalarSemantics.isMaskedDate("2026-02-30"), "and it carries no mask");
        assertFalse(IsoDateBounds.canPosition("2026-02-30"), "⇒ it has no hull");

        // The control: the same day in a month that has one is accepted by both.
        assertTrue(ScalarSemantics.isPartialDate("2026-01-30"));
        assertTrue(CalendarDates.isValidDate("2026-01-30"));
        assertEquals("2026-01-30T00:00:00", IsoDateBounds.lower("2026-01-30"));
    }

}
