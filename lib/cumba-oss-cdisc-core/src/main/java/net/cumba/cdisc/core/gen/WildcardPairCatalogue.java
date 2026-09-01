package net.cumba.cdisc.core.gen;

import java.util.Set;

/**
 * Curated catalogue of CDISC-standard character/numeric analysis-variable pairs, keyed by the
 * <em>secondary</em> variable name (the {@code *N} numeric or {@code *C} character counterpart of a
 * primary analysis variable).
 * <p>
 * Used by {@link WildcardExpander} when a rule sets {@code wildcardPairCatalogue: true} (Group B /
 * B4, rule PMDA-AD1012A): an empty-suffix pairing template (bare {@code *} primary co-anchored by a
 * {@code *N} / {@code *C} secondary) only emits an expansion when the concrete secondary column is
 * a catalogued pair. This realises the AD1012A requirement to look "explicitly at variable pairs
 * defined in the CDISC standard documents" — a whitelist, in contrast to the
 * {@code wildcardExclude} blacklist used by AD0376 / AD1011.
 * <p>
 * <strong>Provenance &amp; scope of the starter set.</strong> The entries below are the common,
 * high-certainty analysis pairs from the ADaMIG 1.0–1.3 variable tables (treatment, parameter,
 * category, visit, timepoint, phase, period, analysis-value, baseline). It is deliberately a
 * curated <em>starter</em> set, not exhaustive: indexed families ({@code TRT01P}…,
 * {@code PARCAT1}…) are seeded with the low-index representatives that appear in practice; extend
 * as needed. Modelled on the static-pattern precedent in
 * {@code RuleGenerator.generatePairOneToOneRules}.
 */
public final class WildcardPairCatalogue
{

    private WildcardPairCatalogue()
    {
    }

    /**
     * Catalogued secondary variable names. A primary {@code *} is required to be present (AD1012A)
     * only when its secondary here is present in the dataset.
     * <ul>
     * <li>{@code *N} numeric secondaries (primary is the character variable): treatment
     * ({@code TRTPN}, {@code TRTAN}, {@code TRT0nPN}, {@code TRT0nAN}), parameter ({@code PARAMN},
     * {@code PARCATnN}), visit ({@code AVISITN}), timepoint ({@code ATPTN}), phase
     * ({@code APHASEN}).</li>
     * <li>{@code *C} character secondaries (primary is the numeric variable): analysis value
     * ({@code AVALC}), baseline ({@code BASEC}), period ({@code APERIODC}).</li>
     * </ul>
     */
    private static final Set<String> SECONDARIES = Set.of(
            // --- numeric secondaries (*N): primary is the character analysis variable ---
            "TRTPN", "TRTAN", "TRT01PN", "TRT02PN", "TRT03PN", "TRT04PN", "TRT01AN", "TRT02AN",
            "TRT03AN", "TRT04AN", "PARAMN", "PARCAT1N", "PARCAT2N", "PARCAT3N", "AVISITN", "ATPTN",
            "APHASEN",
            // --- character secondaries (*C): primary is the numeric analysis variable ---
            "AVALC", "BASEC", "APERIODC");

    /**
     * Returns {@code true} when {@code secondaryColumn} is a catalogued CDISC-standard secondary
     * ({@code *N} / {@code *C}) analysis variable.
     *
     * @param secondaryColumn
     *            the concrete secondary column name (e.g. {@code TRTPN}, {@code AVALC})
     * @return {@code true} if the column is a catalogued pair secondary
     */
    public static boolean isCataloguedSecondary(String secondaryColumn)
    {
        return SECONDARIES.contains(secondaryColumn);
    }
}
