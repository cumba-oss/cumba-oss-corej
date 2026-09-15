package net.cumba.corej.core.gen;

/**
 * How a materialised rule reached the executed set.
 *
 * <p>
 * ⚠⚠ <b>Neither value is a generator, and this enum gates nothing.</b> It is a provenance tag
 * recorded on {@link GeneratedRuleInfo} so the report writer can tell a wildcard child from a
 * {@code --}-prefix substitution — {@code LibraryValidator} branches on
 * {@link #SDTM_PREFIX_EXPANSION} to bundle the per-domain rows of one source rule back into a
 * single {@code Issue_Summary} row.
 * </p>
 *
 * <p>
 * It used to carry 29 values, 27 of which switched on an in-Java rule <i>generator</i>. Those
 * generators minted rules carrying no {@code Standards} block, so they belonged to no rule package
 * and fired regardless of what the user had selected. Fix #366 disabled them and
 * {@code plans/PLAN-remove-rule-generator.md} deleted them, along with the {@code EnumSet} plumbing
 * — the constructor parameter, {@code isEnabled} and {@code corpusDeliveryOnly()} — that existed
 * only to keep them switched off. <b>Rules now come only from rule files</b>, so there is nothing
 * left to gate.
 * </p>
 */
public enum RuleCategory
{

    /**
     * Wildcard expansion of a selected package's own template rule ({@code *FL}, {@code *DTF}, …)
     * into one concrete rule per matching column. Performed by {@link WildcardExpander}.
     */
    WILDCARD_EXPANSION,

    /**
     * SDTM {@code --}-prefix delivery: the domain prefix is substituted into each scoped rule of
     * the selected packages, and <b>every</b> one of them is passed through to the executed set,
     * expanded or unchanged. ⚠ This is the corpus delivery path — if it stops running, no rule
     * executes at all.
     */
    SDTM_PREFIX_EXPANSION

}
