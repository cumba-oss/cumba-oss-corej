package net.cumba.cdisc.core.gen;

import java.util.EnumSet;

/**
 * Categories of rules that the {@link RuleGenerator} can produce. Each category can be
 * independently enabled or disabled.
 *
 * <p>
 * ⚠ Two of these values are <b>not</b> generators — see {@link #corpusDeliveryOnly()}.
 * </p>
 */
public enum RuleCategory
{

    /** Variable label must match Library definition. */
    VARIABLE_LABEL,

    /** Variable type (Char/Num) must match Library definition. */
    VARIABLE_TYPE,

    /** Required variables (core=Req) must be present. */
    REQUIRED_VARIABLE,

    /** Expected variables (core=Exp) should be present. */
    EXPECTED_VARIABLE,

    /** Variable order must match Library-defined ordinals. */
    VARIABLE_ORDER,

    /** Non-extensible codelist values must be from published terms. */
    CODELIST_VALUE,

    /** TESTCD/TEST pairs must be consistent per codelist. */
    TESTCD_TEST_CONSISTENCY,

    /** TSPARMCD/TSPARM pairs must be consistent per codelist. */
    TSPARMCD_TSPARM_CONSISTENCY,

    /** FL/FN flag pairs must map Y=1, N=0. */
    FLAG_NUMERIC_CONSISTENCY,

    /** Character/numeric variable pairs must have one-to-one relationship. */
    PAIR_ONE_TO_ONE,

    /** Dataset label must match Library definition. */
    DATASET_LABEL,

    /** Variables not in the Library are flagged as unexpected. */
    DISALLOWED_VARIABLE,

    /** MedDRA dictionary validation (optional). */
    MEDDRA_VALIDATION,

    /** WHO Drug dictionary validation (optional). */
    WHODD_VALIDATION,

    /**
     * Indexed variable rules (TRTxxP, ANLzzFL, CRITy, etc.) — legacy hardcoded template expansion.
     */
    INDEXED_VARIABLE_RULES,

    /**
     * ⛔ <b>Retired (Fix #366): this value gates nothing.</b> Its only reader was the
     * {@code SUFFIX_LABEL} / {@code SUFFIX_TYPE} family gate in
     * {@code RuleGenerator.applyTemplatePostFilters}, deleted with the built-in templates. Enabling
     * it — as every {@code EnumSet.allOf} test does — produces nothing. Kept only until the
     * generator-code deletion of {@code plans/done/PLAN-retire-engine-generated-rules.md} §10 is
     * reviewed.
     *
     * <p>
     * It was: suffix-based label and type checks (e.g. variables ending in {@code DT} must be Num,
     * labels ending in {@code SDT} must contain "Start Date") — heuristics complementing the
     * Library-driven checks, with Library-defined variables automatically skipped.
     * </p>
     */
    SUFFIX_LABEL_TYPE,

    /**
     * ⚠ <b>NOT a generator</b> — see {@link #corpusDeliveryOnly()}. Wildcard expansion of the
     * SELECTED packages' own rules ({@code *FL}, {@code *DTF}, …). Disabling it silences the
     * corpus, not a generator.
     */
    WILDCARD_EXPANSION,

    /** Cross-dataset metadata checks (e.g., variable label/type vs ADSL). */
    CROSS_DATASET_METADATA,

    /**
     * ⚠ <b>NOT a generator</b> — see {@link #corpusDeliveryOnly()}. SDTM {@code --}-prefix
     * expansion: it substitutes the domain prefix in the scoped static rules and passes
     * <b>every</b> one of them through to the executed set, expanded or unchanged. Disabling it
     * means no corpus rule executes at all.
     */
    SDTM_PREFIX_EXPANSION,

    // Define-XML based categories (optional, requires DefineXMLProvider)

    /** All variables declared in Define-XML must exist in the dataset. */
    DEFINE_VARIABLE_PRESENCE,

    /** Variables in the dataset must be declared in Define-XML. */
    DEFINE_NO_EXTRA_VARIABLES,

    /** Variable labels must match Define-XML declarations. */
    DEFINE_VARIABLE_LABEL,

    /** Variable types must match Define-XML declarations. */
    DEFINE_VARIABLE_TYPE,

    /** Character variable values must not exceed Define-XML declared length. */
    DEFINE_VARIABLE_LENGTH,

    /** Values must be in the codelist defined in Define-XML. */
    DEFINE_CODELIST_VALUES,

    /** Records must be unique by Define-XML declared key variables. */
    DEFINE_KEY_UNIQUENESS,

    /** Record structure must match Define-XML declared structure. */
    DEFINE_DATASET_STRUCTURE,

    /** Variable attributes must match value-level metadata per where-clause. */
    DEFINE_VALUE_LEVEL_METADATA;

    /**
     * The backing set of {@link #corpusDeliveryOnly()}. ⚠ Private, and never handed out: an
     * {@code EnumSet} is mutable and {@code final} protects only the reference, so a public
     * constant could be edited in place by any caller in the JVM — including a test class running
     * earlier in the same Surefire fork — silently re-admitting a generator for the rest of the
     * process. That would also be a data race: {@code LibraryValidator} reads it per dataset from
     * the virtual-thread fan-out.
     */
    private static final EnumSet<RuleCategory> CORPUS_DELIVERY = EnumSet.of(WILDCARD_EXPANSION,
            SDTM_PREFIX_EXPANSION);

    /**
     * The two categories that are NOT generators: they are how rules from the SELECTED packages
     * reach the executed set. {@link #SDTM_PREFIX_EXPANSION} expands {@code --} prefixes and passes
     * every scoped static rule through; {@link #WILDCARD_EXPANSION} expands corpus wildcard rules.
     * Dropping either silences the corpus itself, not a generator.
     *
     * <p>
     * Everything else mints rules in Java at run time with no {@code Standards} block, so it
     * belongs to no package and fires regardless of what the user selected — the defect
     * {@code plans/done/PLAN-retire-engine-generated-rules.md} closes (Fix #366).
     * </p>
     *
     * <p>
     * ⚠⚠ <b>This is a named accessor on purpose.</b> The next reader's instinct is to "simplify"
     * the one production call site — {@code LibraryValidator} — back to {@code EnumSet.allOf}. That
     * would silently re-admit every generator, and nothing in the build would say so: the .cdt
     * suites and the rulespec specs never see a generated id, and the only instrument that does is
     * the findings snapshot, which is regenerated by hand. This javadoc is the guard.
     * </p>
     *
     * @return a fresh, caller-owned set of the two delivery categories
     */
    public static EnumSet<RuleCategory> corpusDeliveryOnly()
    {
        return EnumSet.copyOf(CORPUS_DELIVERY);
    }

}
