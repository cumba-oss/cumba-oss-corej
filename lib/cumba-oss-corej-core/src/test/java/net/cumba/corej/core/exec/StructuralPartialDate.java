package net.cumba.corej.core.exec;

import org.jspecify.annotations.Nullable;

/**
 * The structural (shape-only, no calendar validation) ISO-8601 partial-date gate, composed from the
 * live primitives exactly as the engine composes it: {@code CalendarDates.isValidDate} normalises a
 * value with {@link ScalarSemantics#stripTimezone} then
 * {@link ScalarSemantics#stripFractionalSeconds} and asks {@link ScalarSemantics#isoComponents}
 * whether the core is a layout.
 *
 * <p>
 * ⚑ <b>Why this lives in test sources.</b> It is what {@code ScalarSemantics.isPartialDate(String)}
 * answered. That method had no production caller — only its own interval recursion reached it in
 * {@code src/main}; the production gate is {@code CalendarDates.isValidDate}, which performs the
 * same normalisation itself — and it was removed by the fixpoint pass of
 * {@code PLAN-retire-dead-multi-match-lookup}. The tests that pinned the structural shape (Fix #209
 * / Fix #215: the masked widening, the decoder/gate agreement, the structural-vs-calendar split)
 * now pin it through this composition. ⚠ The {@code a/b} interval split is kept only so the corpus
 * figures those tests measured against the removed method stay comparable; no production code
 * performs <em>this</em> split (the calendar gate has its own, stricter one: exactly two
 * components, forward-running).
 * </p>
 */
public final class StructuralPartialDate
{

    private StructuralPartialDate()
    {
    }


    /**
     * Returns whether {@code s} is a structurally well-formed partial date: a truncation prefix or
     * SDTM masked shape, optionally decorated with a timezone / fractional seconds, or an
     * {@code a/b} interval of two such values.
     *
     * @param s
     *            the candidate, or {@code null}
     * @return whether {@code s} is structurally well-formed
     */
    public static boolean accepts(@Nullable String s)
    {
        if (s == null)
        {
            return false;
        }
        int slash = s.indexOf('/');
        if (slash >= 0)
        {
            return accepts(s.substring(0, slash)) && accepts(s.substring(slash + 1));
        }
        return ScalarSemantics.isoComponents(
                ScalarSemantics.stripFractionalSeconds(ScalarSemantics.stripTimezone(s))) != null;
    }

}
