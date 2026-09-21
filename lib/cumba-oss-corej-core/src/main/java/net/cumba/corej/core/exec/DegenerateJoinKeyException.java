package net.cumba.corej.core.exec;

import java.io.Serial;

/**
 * Every component of a {@code Match_Datasets} key is absent from <b>both</b> sides, so there is no
 * key left to join on ({@code PLAN-join-key-missing-semantics}; register {@code JKM R7}).
 *
 * <p>
 * ⭐⭐ <b>Owner, 2026-09-21:</b> <i>"Absent columns are treated like present, but empty (with default
 * value). So if a string column is absent on one side, this is treated as empty and stays empty in
 * the join. Absent in both sides can be dropped from the join. if all columns are absent, then the
 * rule should fail with an error."</i>
 * </p>
 *
 * <p>
 * ⛔ <b>Why an error rather than a match-everything or a skip.</b> Dropping a both-sides-absent
 * component is behaviour-preserving — both sides carry the same constant, so the component cannot
 * discriminate. Dropping <em>every</em> component leaves an <b>empty key</b>, and an empty key is a
 * cartesian product, not a join: {@code KeyHashing.computeKeyHashSafe} would return its constant
 * and the matcher would be all-true, so {@code _matched_} would read <b>true for every row</b>. ⇒
 * the error says <i>"there is no join here"</i>; it is not a complaint about absent columns.
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
        super("Match_Datasets join on '" + aDataset + "' has no usable key: every key column ("
                + String.join(", ", aKeys) + ") is absent from both the validated dataset and '"
                + aDataset + "'. An empty key would match every row against every row. Declare one "
                + "join column per side in Requirements.Variables.All (e.g. the key unqualified and "
                + "qualified) so the rule skips instead.");
    }
}
