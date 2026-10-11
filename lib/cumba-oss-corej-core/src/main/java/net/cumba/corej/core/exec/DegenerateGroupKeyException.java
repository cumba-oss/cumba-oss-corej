package net.cumba.corej.core.exec;

import java.io.Serial;
import java.util.List;

/**
 * A {@code domain=} grouped call ({@code max}, {@code min_date}, {@code record_count},
 * {@code distinct}, {@code read_value}, {@code sequence}, …) whose {@code group=} key has no member
 * left on both sides while at least one member was dropped for being present on one side only
 * ({@link GroupedAggregate.SidedKey}).
 *
 * <p>
 * ⭐⭐ <b>Owner, 2026-10-11</b> (QK16 of {@code PLAN-join-key-one-side-absent-drop}, register
 * {@code REG-Q4}): <i>"B the rule errors."</i> — the {@code group=} member keys two datasets like a
 * join key, and {@code REG-Q3} answers a join with no key column left by a rule ERROR. Grouping the
 * whole {@code domain=} dataset instead would hand every record the study-wide answer (its maximum,
 * its earliest date, its row count) under a WARNING only. A key whose every member is absent from
 * BOTH sides is not this error: it stays {@code EC-44}'s one group over the whole dataset, as
 * before.
 * </p>
 *
 * <p>
 * Carried on the same {@code "__error__"} sentinel channel as {@link DegenerateJoinKeyException},
 * caught beside it in {@code RuleRunner}.
 * </p>
 */
final class DegenerateGroupKeyException extends RuntimeException
{

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param aFunction
     *            the grouped function
     * @param aMembers
     *            the {@code group=} members as authored, prefixes resolved
     * @param aDataset
     *            the {@code domain=} dataset
     * @param aOneSided
     *            the members present on one side only, as the WARNING named them
     */
    DegenerateGroupKeyException(String aFunction, List<String> aMembers, String aDataset,
            List<String> aOneSided)
    {
        super(aFunction + " group= over '" + aDataset + "' has no usable key: no member of ("
                + String.join(", ", aMembers) + ") is present on both the validated dataset and '"
                + aDataset + "' (" + String.join(", ", aOneSided)
                + " present on one side only). Grouping the whole of " + aDataset
                + " instead would compare every record with the whole dataset. Declare the"
                + " member in Requirements.Variables so a study that lacks it skips instead.");
    }
}
