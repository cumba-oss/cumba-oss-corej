package net.cumba.corej.core.exec;

import java.io.Serial;
import net.cumba.corej.core.expr.eval.ColumnTypeGate;

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

    JoinKeyTypeMismatchException(String aDataset, String aKeyColumn, String aJoinedColumn,
            String aPrimaryKind, String aJoinedKind)
    {
        this("Match_Datasets " + aDataset + ": join key " + aKeyColumn + " is " + aPrimaryKind
                + " in the primary dataset and "
                + (aKeyColumn.equals(aJoinedColumn) ? ""
                        : "its joined column " + aJoinedColumn + " is ")
                + aJoinedKind + " in " + aDataset
                + ". Declare the type this rule REQUIRES on BOTH sides in"
                + " Requirements.Variables.All — e.g. \"" + aKeyColumn + ":N\" (or \":C\") for the"
                + " primary and \"" + aDataset + "." + aJoinedColumn + ":N\" for the joined"
                + " dataset — so a"
                + " study that does not meet it SKIPS instead. Declare the type the rule needs, not"
                + " the one this study has: a tag that matches the divergent column is satisfied"
                + " and does not skip. Or set Join_As_String: true on the entry to compare the keys"
                + " as text.");
    }


    private JoinKeyTypeMismatchException(String aMessage)
    {
        super(aMessage);
    }


    /**
     * The grouped-lookup arm ({@code PLAN-grouping-key-identity}, owner 2026-09-27, Q2: <i>"Beside
     * this, I agree to error out."</i>): a grouped operation's result, grouped on one dataset, is
     * read against another whose key column has the other kind. The lookup keys on the typed
     * identity, so the two sides would silently never meet — the <i>"silent mismatch"</i>
     * {@code D4-R2} refuses. Thrown from {@link GroupedResult#requireCompatibleKeys} and caught
     * where every other {@code JoinKeyTypeMismatchException} is, so the rule ERRORs.
     *
     * @param aGroupedColumn
     *            the key column on the grouped side
     * @param aGroupedDataset
     *            the dataset the operation grouped
     * @param aGroupedKind
     *            the column's kind there
     * @param aEvaluatedColumn
     *            the key column on the side the result is read against (the same name unless the
     *            keys are sided)
     * @param aEvaluatedDataset
     *            the dataset the result is read against
     * @param aEvaluatedKind
     *            the column's kind there
     * @return the exception to throw
     */
    static JoinKeyTypeMismatchException forGroupedLookup(String aGroupedColumn,
            String aGroupedDataset, ColumnTypeGate.Kind aGroupedKind, String aEvaluatedColumn,
            String aEvaluatedDataset, ColumnTypeGate.Kind aEvaluatedKind)
    {
        return new JoinKeyTypeMismatchException("Grouped lookup: key column " + aGroupedColumn
                + " is " + describe(aGroupedKind) + " in " + aGroupedDataset
                + ", where the operation grouped, and "
                + (aGroupedColumn.equals(aEvaluatedColumn) ? ""
                        : "its counterpart " + aEvaluatedColumn + " is ")
                + describe(aEvaluatedKind) + " in " + aEvaluatedDataset
                + ", where the result is read, so no record could find its group. Declare the type"
                + " this rule REQUIRES on BOTH sides in Requirements.Variables.All so a study that"
                + " does not meet it SKIPS instead.");
    }


    private static String describe(ColumnTypeGate.Kind aKind)
    {
        return aKind == ColumnTypeGate.Kind.NUMERIC ? "Numeric" : "Character";
    }

}
