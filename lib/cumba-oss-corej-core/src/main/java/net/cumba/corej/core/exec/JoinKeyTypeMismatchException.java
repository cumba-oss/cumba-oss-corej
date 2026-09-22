package net.cumba.corej.core.exec;

import java.io.Serial;

/**
 * Thrown when a {@code Match_Datasets} join key is <b>character</b> on one side and <b>numeric</b>
 * on the other, and the entry does not declare {@code Join_As_String: true}
 * ({@code PLAN-join-key-type-identity}, ruling {@code D4-R2}).
 *
 * <p>
 * ⭐⭐ <b>Why an ERROR and not a silent non-match.</b> Owner, 2026-09-22: <i>"B as I read it means a
 * rule errors out if the types do not match. The author should add
 * {@code Requirements.Variables.All} and express the typed requirements to avoid the error. I do
 * not want a silent mismatch."</i> Typed identity alone would make such a join simply match nothing
 * — which on a {@code Join_Type: left} entry is not silence but a <b>flood</b> of false "no
 * matching record" findings over data whose real defect another rule already reports
 * ({@code CDISC-AD0199}). Erroring reports the condition once, loudly, and cannot be mistaken for a
 * clean run.
 * </p>
 *
 * <p>
 * Caught at the top of rule execution ({@code RuleRunner.execute}) and turned into a
 * {@code RuleExecutionStatus.ERROR} result with an {@code __error__} sentinel violation — the same
 * "virtual finding" shape as {@link InvalidJoinedDomainException} and
 * {@link DegenerateJoinKeyException}.
 * </p>
 *
 * <p>
 * ⛔ It is never thrown for a {@code Child:true} / {@code RELREC} / {@code SUPP--} entry: those join
 * a text-carried foreign key against a typed column by design and are excluded by
 * {@link JoinKeyTypes#excludedFromKeyTypeCheck} ({@code D4-R5}).
 * </p>
 */
final class JoinKeyTypeMismatchException extends RuntimeException
{

    @Serial
    private static final long serialVersionUID = 1L;

    JoinKeyTypeMismatchException(String aDataset, String aKeyColumn, String aPrimaryKind,
            String aJoinedKind)
    {
        super("Match_Datasets " + aDataset + ": join key " + aKeyColumn + " is " + aPrimaryKind
                + " in the primary dataset and " + aJoinedKind + " in " + aDataset
                + ". Declare the type this rule REQUIRES on BOTH sides in Requirements.Variables"
                + " — e.g. \"" + aKeyColumn + ":N\" for the primary and \"" + aDataset + "."
                + aKeyColumn + ":N\" for the joined dataset — so a study that does not meet it"
                + " SKIPS instead. Declare the type the rule needs, not the one this study has: a"
                + " tag that matches the divergent column is satisfied and does not skip. Or set"
                + " Join_As_String: true on the entry to compare the keys as text.");
    }

}
