package net.cumba.corej.core.exec;

import java.io.Serial;

/**
 * Thrown when a {@code Match_Datasets} entry's {@code Keys} list carries an element neither side
 * can read in full ({@code PLAN-join-key-type-identity}, review round 2).
 *
 * <p>
 * ⛔⛔ <b>Why this exists rather than reusing {@link DegenerateJoinKeyException}.</b> That one says
 * <i>"every key column … is absent from both the validated dataset and 'AE' … declare one join
 * column per side in Requirements.Variables.All so the rule skips instead"</i> — and for a
 * half-declared sided key <b>none of that is true</b>: the columns are present, and no
 * {@code Requirements} entry can help. The author has to write the missing {@code "right"}. An
 * error whose remedy sends the author to the data when the defect is in the rule is worse than no
 * error, which is the same lesson {@code D4-R2}'s own message learned in round 1.
 * </p>
 *
 * <p>
 * Normally unreachable: {@code RulePackageLoader.checkSidedKeys} rejects the shape at load. This is
 * the backstop for a rule built programmatically that never passed through the loader, so it fails
 * with a diagnosis rather than an {@code ArrayIndexOutOfBoundsException}.
 * </p>
 */
final class MalformedSidedKeyException extends RuntimeException
{

    @Serial
    private static final long serialVersionUID = 1L;

    MalformedSidedKeyException(String aDataset, String aDetail)
    {
        super("Match_Datasets " + aDataset + ": malformed Keys element — " + aDetail
                + ". Every element must be a column name, or an object declaring BOTH \"left\" and"
                + " \"right\" as strings; the two sides are indexed in lockstep.");
    }

}
