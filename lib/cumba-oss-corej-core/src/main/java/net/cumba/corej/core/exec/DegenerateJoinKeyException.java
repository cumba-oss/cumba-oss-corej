package net.cumba.corej.core.exec;

import java.io.Serial;

/**
 * No component of a {@code Match_Datasets} key is present on <b>both</b> sides, so there is no key
 * left to join on (register {@code REG-Q3}, which kept {@code JKM R7}'s all-absent half).
 *
 * <p>
 * ⭐⭐ <b>Owner, 2026-10-09</b> ({@code REG-Q3}, changing {@code JKM R7}): a key column present on
 * one side only leaves the key with a WARNING; <i>"no key column left ⇒ rule ERROR; a rule listing
 * its key columns under Requirements.Variables SKIPS instead."</i> Before, 2026-09-21: <i>"Absent
 * in both sides can be dropped from the join. if all columns are absent, then the rule should fail
 * with an error."</i>
 * </p>
 *
 * <p>
 * ⛔ <b>Why an error rather than a match-everything or a skip.</b> Dropping a component is
 * survivable while another one keys the join. Dropping <em>every</em> component leaves an <b>empty
 * key</b>, and an empty key is a cartesian product, not a join: every key would hash to one
 * constant and the matcher would be all-true, so {@code _matched_} would read <b>true for every
 * row</b>. ⇒ the error says <i>"there is no join here"</i>; it is not a complaint about absent
 * columns.
 * </p>
 *
 * <p>
 * ⭐ <b>The authored way to avoid it is a requirement, not a flag:</b> declare one join column per
 * side in {@code Requirements.Variables.All} — the same column once unqualified and once qualified,
 * e.g. {@code [USUBJID, DM.USUBJID]} — and the rule <b>SKIPS</b> cleanly instead
 * (<i>non-executability is not missingness</i>, {@code Q8}). That gate is an authoring MUST for new
 * and edited rules as of 2026-09-21; the corpus-wide roll-out is a separate update, so this error
 * is the live backstop until then.
 * </p>
 *
 * <p>
 * Carried on the same {@code "__error__"} sentinel channel as {@link InvalidJoinedDomainException}
 * and caught beside it in {@code RuleRunner}.
 * </p>
 */
final class DegenerateJoinKeyException extends RuntimeException
{

    @Serial
    private static final long serialVersionUID = 1L;

    DegenerateJoinKeyException(String aDataset, java.util.List<String> aKeys)
    {
        super("Match_Datasets join on '" + aDataset + "' has no usable key: no key column of ("
                + String.join(", ", aKeys) + ") is present on both the validated dataset and '"
                + aDataset + "'. An empty key would match every row against every row. Declare one "
                + "join column per side in Requirements.Variables.All (e.g. the key unqualified and "
                + "qualified) so the rule skips instead.");
    }
}
