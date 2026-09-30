package net.cumba.corej.core.exec;

import java.io.Serial;

/**
 * A <b>qualified</b> key component — {@code DM.RPATHCD} in a {@code Match_Datasets} key, in a
 * grouped function's {@code group=} or in the rule-level {@code Grouping} — cannot be resolved at
 * run time ({@code PLAN-rprfdy-offset-tp-join} D-ABSENT).
 *
 * <p>
 * A qualified variable names where the <b>record-side</b> value comes from: the rule's own
 * {@code Match_Datasets} entry {@code DM}, read at the row's bound {@code DM} record; the
 * unqualified {@code RPATHCD} is the same variable on the other side (the joined or the grouped
 * dataset). Three things can make that unanswerable, and each is a rule <b>ERROR</b> — never a
 * {@code ""} key, never JKM R7's absent-side default, never EC-44's "an absent group column
 * partitions nothing": the source entry's dataset is absent from the study; the source column is
 * absent from that dataset; the unqualified variable is absent from the other side. Owner (C1,
 * 2026-09-29): <i>"The joined data set needs the same variable without the domain
 * qualification"</i>; D35: a rule errors when it <i>"can't be executed at all against the
 * dataset"</i>.
 * </p>
 *
 * <p>
 * ⛔ <b>Not a load error.</b> Column inventories are data, invisible at load; the loader judges only
 * the declaration (the qualifier names an earlier, ordinary, {@code inner} entry). The authored way
 * to avoid this error is a requirement, not a flag: declare both spellings in
 * {@code Requirements.Variables.All} ({@code DM.RPATHCD} and {@code TP.RPATHCD}) and a study that
 * lacks either <b>SKIPS</b> instead (JKM R7's authoring gate, Q8). ⚠ An {@code All_Or_None} group
 * alone is not enough: a study lacking the column on <em>both</em> sides satisfies the group and
 * still reaches this error — JKM R7's both-absent drop is for one column both sides lack; here the
 * two sides are different datasets, and dropping the component would silently join on the remaining
 * ones. That is the one arm a rule declaring the group reaches, so the message names {@code All}
 * (review round 1, lane 1 L1).
 * </p>
 *
 * <p>
 * Caught in {@code RuleRunner}'s join-defect multi-catch beside {@link DegenerateJoinKeyException}
 * and {@link JoinKeyTypeMismatchException}, on the same {@code "__error__"} sentinel channel.
 * </p>
 */
final class UnresolvedQualifiedKeyException extends RuntimeException
{

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param aWhere
     *            the surface, e.g. {@code Match_Datasets TP}, {@code read_value group=} or
     *            {@code Grouping}
     * @param aQualifiedKey
     *            the component as authored, e.g. {@code DM.RPATHCD}
     * @param aReason
     *            what could not be resolved, e.g. {@code DM has no column RPATHCD}
     */
    UnresolvedQualifiedKeyException(String aWhere, String aQualifiedKey, String aReason)
    {
        super(aWhere + ": qualified key " + aQualifiedKey + " cannot be resolved — " + aReason
                + ". A qualified key names where the record-side value comes from (the rule's own"
                + " Match_Datasets entry " + qualifierOf(aQualifiedKey) + ", read at the row's"
                + " bound " + qualifierOf(aQualifiedKey) + " record) and the same variable"
                + " unqualified on the other side; declare both spellings in"
                + " Requirements.Variables.All so a study that lacks either SKIPS the rule instead"
                + " (an All_Or_None group alone lets a study that lacks the variable on BOTH sides"
                + " run, and ERROR).");
    }


    private static String qualifierOf(String aQualifiedKey)
    {
        int dot = aQualifiedKey.indexOf('.');
        return dot > 0 ? aQualifiedKey.substring(0, dot) : aQualifiedKey;
    }
}
