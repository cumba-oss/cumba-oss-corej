package net.cumba.corej.core.metadata;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Phase 7 of {@code plans/PLAN-metadata-product-selection.md} — the <b>one</b> place a declared
 * metadata-product cache key ({@code standards/...}, the verbatim {@code PickleCache} key form that
 * {@code ProductKeyResolver} outputs) is classified by family.
 *
 * <p>
 * Three key shapes exist, and they are deliberately <b>not</b> parsed beyond family recognition
 * (the version/id segments are unsplittable — {@code adam-occds-1-1} could be {@code adam} +
 * {@code occds-1-1} or {@code adam-occds} + {@code 1-1}):
 * </p>
 *
 * <ul>
 * <li>{@code standards/adam/<productId>} — an ADaM-family product;</li>
 * <li>{@code standards/sdtmig/<v>} / {@code standards/sendig/<v>} — SDTM-family (the version
 * segment stays unparsed: {@code 3-4}, {@code ap-1-0}, {@code dart-1-1} are all real);</li>
 * <li>{@code standards/tig/<v>/<leg>} — a TIG leg, whose family is the leg's ({@code adam} leg ⇒
 * ADaM family, {@code sdtm} leg ⇒ SDTM family).</li>
 * </ul>
 *
 * <p>
 * ⭐ <b>§7-0 (owner ruling 2026-08-28): the library layer follows the FIRST declared product of the
 * run's own family.</b> {@link #firstSdtmLoader(List)} and {@link #firstAdamFamilyKey(List)} are
 * that selection, shared by the API path ({@code CdiscLibraryProviderBuilder}) and the pickle path
 * ({@code PickleMetadataProviderFactory} / {@code StudyValidationService.tryPickleProvider}) so the
 * two cache sources cannot drift. {@code CompanionSdtmDefaults} delegates its SDTM-family detection
 * here for the same reason.
 * </p>
 */
public final class MetadataProductKeys
{

    /** A TIG leg product key: {@code standards/tig/<version>/<leg>}. */
    private static final Pattern TIG_LEG_KEY = Pattern.compile("standards/tig/([^/]+)/([^/]+)");

    /**
     * A non-TIG SDTM-family product key: {@code standards/sdtmig/<version>} or
     * {@code standards/sendig/<version>}. The version segment is deliberately unparsed — the
     * shipped cache carries {@code 3-4} as well as {@code ap-1-0}, {@code md-1-1}, {@code dart-1-1}
     * and {@code genetox-1-0}, and every one of them is a legitimate key that
     * {@code PickleMetadataProviderFactory.forSdtm(family, version)} reassembles verbatim.
     */
    private static final Pattern SDTM_FAMILY_KEY = Pattern
            .compile("standards/(sdtmig|sendig)/([^/]+)");

    /** Prefix of a plain ADaM product key. */
    private static final String ADAM_PREFIX = "standards/adam/";

    /** The TIG leg that is itself an SDTM product. */
    private static final String TIG_SDTM_LEG = "sdtm";

    /** The TIG leg that is itself an ADaM product. */
    private static final String TIG_ADAM_LEG = "adam";

    private MetadataProductKeys()
    {
    }

    /**
     * The {@code (standard, version)} pair to hand
     * {@code PickleMetadataProviderFactory.forSdtm(standard, version)} /
     * {@code CdiscLibraryClient.getSdtmVersion(standard, version, …)} for an SDTM-family product
     * key. For a TIG SDTM leg the version is the compound {@code <version>/sdtm}, which
     * {@code PickleProductSource.standardsKey} reassembles into the verbatim cache key
     * {@code standards/tig/<version>/sdtm} (the API path cannot load TIG at all and must say so —
     * see {@code CdiscLibraryProviderBuilder}).
     */
    public record SdtmLoader(String standard, String version)
    {
    }

    /**
     * The CDISC Library product id inside a plain {@code standards/adam/<productId>} key (e.g.
     * {@code adam-occds-1-1}), or {@code null} when the key has another shape — including a TIG
     * ADaM leg, which is ADaM-family but has no {@code /mdr/adam/...} id (§7-2).
     *
     * @param aKey
     *            a {@code standards/...} cache key
     * @return the product id, or {@code null}
     */
    public static @Nullable String adamProductIdOf(String aKey)
    {
        String key = canonical(aKey);
        if (!key.startsWith(ADAM_PREFIX))
        {
            return null;
        }
        String id = key.substring(ADAM_PREFIX.length());
        return id.isEmpty() || id.indexOf('/') >= 0 ? null : id;
    }


    /**
     * The leg of a TIG product key ({@code standards/tig/<v>/<leg>} → {@code <leg>}), or
     * {@code null} for any other shape.
     *
     * @param aKey
     *            a {@code standards/...} cache key
     * @return the leg, or {@code null}
     */
    public static @Nullable String tigLegOf(String aKey)
    {
        Matcher m = TIG_LEG_KEY.matcher(canonical(aKey));
        return m.matches() ? m.group(2) : null;
    }


    /**
     * Whether {@code aKey} is the TIG ADaM leg ({@code standards/tig/<v>/adam}) — ADaM-family, but
     * loadable only from the pickle cache (§7-1: the API has no TIG).
     *
     * @param aKey
     *            a {@code standards/...} cache key
     * @return {@code true} for a TIG ADaM leg
     */
    public static boolean isTigAdamLeg(String aKey)
    {
        return TIG_ADAM_LEG.equals(tigLegOf(aKey));
    }


    /**
     * Whether {@code aKey} names an ADaM-family product: a plain {@code standards/adam/<id>} key
     * <b>or</b> a TIG ADaM leg. §7-2 — the leg counting as ADaM-family is what lets a declared
     * {@code tig/<v>/adam} enter the ordered ADaM product list at all.
     *
     * @param aKey
     *            a {@code standards/...} cache key
     * @return {@code true} for an ADaM-family key
     */
    public static boolean isAdamFamily(String aKey)
    {
        return adamProductIdOf(aKey) != null || isTigAdamLeg(aKey);
    }


    /**
     * Whether {@code aKey} names a TIG product ({@code standards/tig/...}, any leg). TIG is
     * pickle-only — the CDISC Library API path can neither enumerate nor fetch it — so callers use
     * this to state that reason instead of failing mysteriously.
     *
     * @param aKey
     *            a {@code standards/...} cache key (or a bare {@code tig/...} token)
     * @return {@code true} for a TIG key
     */
    public static boolean isTig(String aKey)
    {
        String key = canonical(aKey);
        return key.startsWith("standards/tig/") || key.startsWith("tig/");
    }


    /**
     * The loader pair for an SDTM-family key ({@code standards/sdtmig/<v>},
     * {@code standards/sendig/<v>} or the {@code standards/tig/<v>/sdtm} leg), or {@code null} for
     * any other family — a {@code standards/tig/<v>/adam} leg is deliberately <b>not</b> one.
     *
     * @param aKey
     *            a {@code standards/...} cache key
     * @return the loader pair, or {@code null}
     */
    public static @Nullable SdtmLoader sdtmLoaderOf(String aKey)
    {
        String key = canonical(aKey);
        Matcher tig = TIG_LEG_KEY.matcher(key);
        if (tig.matches() && TIG_SDTM_LEG.equals(tig.group(2)))
        {
            return new SdtmLoader("tig", tig.group(1) + "/" + TIG_SDTM_LEG);
        }
        Matcher sdtm = SDTM_FAMILY_KEY.matcher(key);
        if (sdtm.matches())
        {
            return new SdtmLoader(sdtm.group(1), sdtm.group(2));
        }
        return null;
    }


    /**
     * §7-0 — the loader pair of the <b>first</b> SDTM-family key in the user's precedence order, or
     * {@code null} when none is declared (callers then fall back to the {@code -s}/{@code -v} pair,
     * which is also what the omitted-{@code -mp} default resolves to, so the fallback is
     * behaviour-preserving).
     *
     * @param aKeys
     *            resolved {@code standards/...} cache keys in the user's precedence order
     * @return the first SDTM-family loader, or {@code null}
     */
    public static @Nullable SdtmLoader firstSdtmLoader(List<String> aKeys)
    {
        for (String key : aKeys)
        {
            if (key == null)
            {
                continue;
            }
            SdtmLoader loader = sdtmLoaderOf(key);
            if (loader != null)
            {
                return loader;
            }
        }
        return null;
    }


    /**
     * §7-0 — the <b>first</b> ADaM-family key in the user's precedence order, or {@code null} when
     * none is declared.
     *
     * @param aKeys
     *            resolved {@code standards/...} cache keys in the user's precedence order
     * @return the first ADaM-family key (canonicalised), or {@code null}
     */
    public static @Nullable String firstAdamFamilyKey(List<String> aKeys)
    {
        for (String key : aKeys)
        {
            if (key != null && isAdamFamily(key))
            {
                return canonical(key);
            }
        }
        return null;
    }


    /** Trim + lower-case; the resolver emits canonical keys but callers may hand-build them. */
    private static String canonical(String aKey)
    {
        return aKey.trim().toLowerCase(Locale.ROOT);
    }
}
