package net.cumba.corej.core.exec;

/**
 * A read of a merged column the data cannot supply — one carrying a {@code MERGE_ERROR}
 * ({@code PLAN-merged-evaluation-table} OD-7, MET #15): a rule that reads such a column
 * <b>errors</b>, never evaluates a default in its place. {@code RuleRunner} maps it to the rule's
 * {@code ERROR} status quoting the flag; it is the read-time backstop behind the static gate on the
 * rule's referenced names, reached by a dynamic read ({@code colref(…)}, a {@code ${…}} target)
 * that no static walk can see.
 */
public final class MergeErrorReadException extends RuntimeException
{

    private static final long serialVersionUID = 1L;

    /**
     * @param aColumn
     *            the merged column's name.
     * @param aFlag
     *            the column's {@code MERGE_ERROR} message.
     */
    public MergeErrorReadException(String aColumn, String aFlag)
    {
        super(MergedEvaluation.flagMessage(aColumn, aFlag));
    }
}
