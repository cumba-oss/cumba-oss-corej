package net.cumba.corej.core.expr.eval;

import java.io.Serial;

/**
 * {@code read_value(X, domain=D, mode="ONLY")} matched more than one row of {@code D} — the rule
 * asserted there would be exactly one and the data says otherwise (owner ruling D13, 2026-09-22:
 * <i>"ONLY on a second match fails with an error"</i>; {@code PLAN-operation-replacements} §2.2).
 * Caught by {@code RuleRunner} as its own ERROR site, so the rules repository's
 * {@code ViolationNormaliser} classifies it by the fixed message tail rather than folding it into
 * another reason. An EMPTY match is not an error — it answers the type default (D13, D34 #3/#4).
 */
public final class ReadValueAmbiguityException extends RuntimeException
{

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception for a {@code mode="ONLY"} read that matched several rows.
     *
     * @param column
     *            the read column ({@code X})
     * @param dataset
     *            the dataset ({@code D})
     * @param matches
     *            how many rows qualified
     */
    public ReadValueAmbiguityException(String column, String dataset, long matches)
    {
        super("read_value(" + column + ", domain=" + dataset + ", mode=\"ONLY\") matched " + matches
                + " rows of " + dataset + " — ONLY asserts exactly one qualifying row");
    }
}
