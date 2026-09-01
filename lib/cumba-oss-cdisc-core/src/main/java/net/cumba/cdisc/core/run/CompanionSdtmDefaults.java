package net.cumba.cdisc.core.run;

import java.util.List;
import net.cumba.cdisc.core.metadata.MetadataProductKeys;
import org.jspecify.annotations.Nullable;

/**
 * EC-14 layer (ii) — house convention mapping an ADaM-family run to its companion SDTM product for
 * the {@code standard_domains} enumeration. An ADaM submission carries no SDTM IG product, so
 * {@code SRCDOM is an SDTM domain name} rules (e.g. {@code CDISC-AD0180}) have nothing to validate
 * against until a companion SDTM version is resolved.
 *
 * <p>
 * <b>There is exactly ONE tier: a declared SDTM-family product</b> — {@code standards/sdtmig/…},
 * {@code standards/sendig/…} or a {@code standards/tig/<v>/sdtm} leg — the <em>first</em> such key
 * in the effective {@code --metadata-products} order (first-match-wins). None declared ⇒ no
 * companion, and {@code standard_domains} rules SKIP loudly.
 * </p>
 *
 * <p>
 * ⛔ <b>Plan 2 (R9/R10) deleted the other three tiers.</b> They were: a TIG-ADaM derivation, an
 * {@code ADAMIG_TO_SDTMIG} version table ({@code 1-0 → 3-1-2 … 1-3 → 3-4}) compiled into this
 * class, and a {@code DEFAULT_COMPANION_SDTMIG = "3-4"} guess for anything unmapped.
 * </p>
 * <ul>
 * <li>The table is now <b>packaging data</b> ({@code rules-src/package-standards.json}): a rules
 * package declares its companion, and R7 appends that declaration to the effective product list —
 * so it arrives here as a declared product and the single tier answers. Changing a mapping no
 * longer needs an engine release.</li>
 * <li>The TIG derivation is unnecessary: a TIG package declares all four legs as primaries, so its
 * {@code sdtm} leg is itself a declared SDTM-family product.</li>
 * <li>The {@code 3-4} fallback is gone (R10). Guessing the newest SDTMIG for an unmapped ADaM
 * product silently validated against metadata nobody chose.</li>
 * </ul>
 *
 * <p>
 * ⚠ This class no longer mirrors the Python
 * {@code cdisc_rules_engine.constants.companion_standards} module; the two have deliberately
 * diverged and there is nothing left to keep in sync.
 * </p>
 *
 * <p>
 * ⛔ <b>The surface stays NARROW</b> (plan §2.4). The result is used only to build the provider that
 * {@code CompanionDomainsProvider} consults for {@code getStandardDatasetNames()}. A declared SDTM
 * product is deliberately <em>not</em> injected into {@code MetadataLibraryProvider}:
 * {@code getRequiredVariables} / {@code getExpectedVariables} / {@code getColumnOrder} all branch
 * on {@code hasSdtmProduct()}, so doing so would change how <em>ADaM</em> variables resolve as a
 * side effect of naming an SDTM version.
 * </p>
 */
public final class CompanionSdtmDefaults
{

    private CompanionSdtmDefaults()
    {
    }

    /**
     * The resolved companion SDTM product for an ADaM-family run.
     *
     * @param loaderStandard
     *            the standard token to hand {@code PickleMetadataProviderFactory.forSdtm} — either
     *            {@code sdtmig} or {@code tig}.
     * @param loaderVersion
     *            the version token for the same call — a bare SDTMIG version (e.g. {@code 3-4}) or,
     *            for TIG, the compound {@code <version>/sdtm} that yields the cache key
     *            {@code standards/tig/<version>/sdtm}.
     * @param display
     *            a human-readable label for the run header / logs (e.g. {@code sdtmig 3-4}).
     * @param defaulted
     *            always {@code false} since R10 deleted the house fallback. Retained so the record
     *            shape (and the run header that reads it) is unchanged; there is no longer any path
     *            that guesses a companion, so nothing can set it.
     * @param declared
     *            {@code true} whenever a companion resolved at all — since R10 every companion
     *            comes from a declared product, either typed by the user or contributed by the
     *            selected rules package (R7). Log-only.
     */
    public record Companion(String loaderStandard, String loaderVersion, String display,
            boolean defaulted, boolean declared)
    {

        public Companion
        {
            if (defaulted && declared)
            {
                throw new IllegalArgumentException(
                        "a declared companion is never a defaulted one: " + display);
            }
        }
    }

    /**
     * The companion SDTM product for an ADaM-family run: the <em>first</em> SDTM-family key in the
     * effective metadata-product list, or {@code null} when none is declared.
     *
     * <p>
     * ⛔ <b>R9 / R10 — the house table is GONE.</b> This used to fall through three further tiers: a
     * TIG-ADaM derivation, an {@code ADAMIG_TO_SDTMIG} version table compiled into the engine, and
     * finally a {@code DEFAULT_COMPANION_SDTMIG = "3-4"} guess for anything unmapped. All three are
     * deleted.
     * </p>
     *
     * <ul>
     * <li>The <b>table</b> moved to packaging data ({@code rules-src/package-standards.json}); a
     * package now declares its companion, and R7 appends that declaration to the effective product
     * list — so it arrives here as a declared product and tier 1 answers.</li>
     * <li>The <b>TIG derivation</b> is unnecessary: a TIG package declares all four legs as
     * primaries, so its {@code sdtm} leg is itself a declared SDTM-family product.</li>
     * <li>The <b>{@code 3-4} fallback</b> is gone outright (R10): a package declaring no companion
     * <em>has</em> none, and its {@code standard_domains} rules SKIP loudly. Guessing the newest
     * SDTMIG for an unmapped ADaM product silently validated against metadata nobody chose.</li>
     * </ul>
     *
     * @param effectiveProducts
     *            the run's effective metadata products as resolved {@code standards/...} cache keys
     *            — the user's {@code --metadata-products} followed by the selected packages'
     *            declared standards (R7)
     * @return the resolved companion, or {@code null} when none is declared
     */
    public static @Nullable Companion resolve(List<String> effectiveProducts)
    {
        return declaredCompanion(effectiveProducts);
    }


    /**
     * <b>Ruling 6, tier 1</b> — the companion implied by the <em>first</em> SDTM-family key in
     * {@code declaredProducts}, or {@code null} when the user declared none (the house table then
     * applies unchanged).
     *
     * <p>
     * Three key shapes count as SDTM-family, and they are the three the plan names:
     * {@code standards/sdtmig/<v>}, {@code standards/sendig/<v>} and the
     * {@code standards/tig/<v>/sdtm} leg. A {@code standards/tig/<v>/adam} leg is <b>not</b> one —
     * it drives tier 2's derivation instead.
     * </p>
     *
     * @param declaredProducts
     *            resolved {@code standards/...} cache keys in the user's precedence order
     * @return the declared companion, or {@code null}
     */
    public static @Nullable Companion declaredCompanion(List<String> declaredProducts)
    {
        // Phase 7: family detection is delegated to MetadataProductKeys — the same classifier
        // both cache paths' library-layer selection uses (§7-0), so "which key counts as
        // SDTM-family" cannot drift between the companion tier and provider construction.
        MetadataProductKeys.SdtmLoader loader = MetadataProductKeys
                .firstSdtmLoader(declaredProducts);
        if (loader == null)
        {
            return null;
        }
        return new Companion(loader.standard(), loader.version(),
                loader.standard() + " " + loader.version(), false, true);
    }


    /**
     * Whether the declared products contain a TIG ADaM leg ({@code standards/tig/<v>/adam}) — the
     * declaration-based successor of the removed {@code -s tig -ss adam} detection.
     */
    public static boolean declaresTigAdam(List<String> declaredProducts)
    {
        return "adam".equals(tigLeg(declaredProducts));
    }


    /**
     * The leg of the first declared TIG product ({@code standards/tig/<v>/<leg>} → {@code <leg>}),
     * or {@code null} when none is declared. Feeds the report's {@code Sub_Standard} field, which
     * only TIG runs ever populated.
     */
    public static @Nullable String tigLeg(List<String> declaredProducts)
    {
        for (String key : declaredProducts)
        {
            if (key == null)
            {
                continue;
            }
            String leg = MetadataProductKeys.tigLegOf(key);
            if (leg != null)
            {
                return leg;
            }
        }
        return null;
    }

}
