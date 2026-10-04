package net.cumba.corej.core.metadata;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.CustomLog;
import net.cumba.corej.core.metadata.store.StoredDataStructure;
import net.cumba.corej.core.metadata.store.StoredProduct;

/**
 * Phase 7 of {@code plans/done/PLAN-metadata-product-selection.md} — <b>the single construction
 * site</b> that turns the run's declared metadata-product keys ({@code --metadata-products},
 * resolved to verbatim {@code standards/...} cache keys) into the ordered
 * {@link MetadataLibraryProvider.DeclaredAdamProduct} list the provider walks (ruling 1:
 * first-match-wins on the user's order).
 *
 * <p>
 * ⚠⚠⚠ <b>Both cache paths MUST funnel through {@link #assemble}.</b> The API path
 * ({@code StoreMetadataProviderFactory.forAdam}) and the pickle path (historically
 * {@code PickleMetadataProviderFactory.forAdam}, deleted by cache 8g; today a test seeding path)
 * differ only in the {@link AdamProductFetcher} they supply. A path that built its own list — as
 * the pickle path would have if the deleted {@code tryPickleProvider} constructed its provider
 * directly — would leave the multi-product feature silently inert on that source while every test
 * stayed green. Grep discipline: nothing outside this class may turn a declared-key list into
 * {@code DeclaredAdamProduct}s.
 * </p>
 *
 * <p>
 * What one call does, in order:
 * </p>
 * <ol>
 * <li>walks the declared keys in the user's precedence order, deduplicating (first wins);</li>
 * <li>fetches every <b>ADaM-family</b> key ({@code standards/adam/<id>} or the
 * {@code standards/tig/<v>/adam} leg — §7-2) through the supplied fetcher; keys of other families
 * are skipped with an INFO line (they are not this provider's to answer — an SDTM key declared on
 * an ADaM run stays narrow, plan §2.4);</li>
 * <li>with nothing ADaM-shaped declared, falls back to the single product implied by
 * {@code -s}/{@code -v} under its derived key — byte-for-byte the pre-{@code -mp} behaviour;</li>
 * <li>runs the plan-§6b declaration check ({@link #validateDeclaredStructures}) so a wholly
 * unaddressable product fails loudly on <b>both</b> cache paths.</li>
 * </ol>
 *
 * <p>
 * ⚠⚠ A declared product the fetcher cannot deliver <b>throws</b> — deliberately the same
 * disposition a failed single-product fetch has always had (the API caller degrades the provider;
 * the pickle caller falls back to the API path). Silently dropping a declared product would leave
 * the run resolving against a list the user did not declare, which is exactly what ruling 1
 * forbids.
 * </p>
 */
@CustomLog
public final class DeclaredAdamProducts
{

    private DeclaredAdamProducts()
    {
    }

    /**
     * One cache source's way of materialising an ADaM-family product for a declared
     * {@code standards/...} key, as the engine's own {@link StoredProduct} (cache plan §4.3.1, P3 —
     * api-model sources project through {@code ApiModelProjection} at this boundary).
     * Implementations: the CDISC Library API ({@code client.getAdamProduct(id, true)} — which must
     * <b>refuse a TIG leg with a stated reason</b>, the API has no TIG), the pickle cache
     * ({@code cache.get(key)}), and the unified metadata store
     * ({@code StoreMetadataProviderFactory}).
     */
    @FunctionalInterface
    public interface AdamProductFetcher
    {

        /**
         * Materialises the product for {@code aCacheKey}.
         *
         * @param aCacheKey
         *            a canonical ADaM-family {@code standards/...} cache key
         * @return the product (never {@code null})
         * @throws IOException
         *             when this source cannot deliver the product — the caller disposes of the
         *             whole run (degrade, or fall back to the other source); it never drops the
         *             product from the list
         */
        StoredProduct fetch(String aCacheKey) throws IOException;
    }

    /**
     * Assembles (and §6b-validates) the ordered declared-product list. See the class javadoc for
     * the exact steps.
     *
     * @param aStandardName
     *            the run's {@code -s} standard (e.g. {@code adamig}) — the fallback product when
     *            nothing ADaM-shaped is declared
     * @param aStandardVersion
     *            the run's {@code -v} version (e.g. {@code 1-3})
     * @param aDeclaredKeys
     *            resolved {@code standards/...} cache keys, highest precedence first (never
     *            {@code null}; an omitted {@code -mp} arrives as the single {@code -s}/{@code -v}
     *            key per §1b′)
     * @param aFetcher
     *            the cache source
     * @return the ordered list, never empty
     * @throws IOException
     *             when a declared (or the fallback) product cannot be fetched
     * @throws UnmappedMetadataProductException
     *             when a declared product's structures map to no token at all (§6b)
     */
    public static List<MetadataLibraryProvider.DeclaredAdamProduct> assemble(String aStandardName,
            String aStandardVersion, List<String> aDeclaredKeys, AdamProductFetcher aFetcher)
        throws IOException
    {
        List<MetadataLibraryProvider.DeclaredAdamProduct> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String key : aDeclaredKeys)
        {
            if (key == null || !seen.add(key))
            {
                continue;
            }
            if (!MetadataProductKeys.isAdamFamily(key))
            {
                LOGGER.log(System.Logger.Level.INFO,
                        "Declared metadata product {0} is not an ADaM-family key "
                                + "(standards/adam/<id> or standards/tig/<v>/adam); it does not "
                                + "contribute to this run''s ADaM variable resolution.",
                        key);
                continue;
            }
            out.add(new MetadataLibraryProvider.DeclaredAdamProduct(key, aFetcher.fetch(key)));
        }
        if (out.isEmpty())
        {
            String igKey = MetadataProductKeys.standardsKey(aStandardName, aStandardVersion);
            out.add(new MetadataLibraryProvider.DeclaredAdamProduct(igKey, aFetcher.fetch(igKey)));
        }
        else
        {
            LOGGER.log(System.Logger.Level.INFO,
                    "ADaM metadata resolves against {0} declared product(s), in precedence order: "
                            + "{1}",
                    out.size(), out.stream()
                            .map(MetadataLibraryProvider.DeclaredAdamProduct::cacheKey).toList());
        }
        inferOccdsProduct(out, aFetcher);
        validateDeclaredStructures(out);
        return List.copyOf(out);
    }

    /**
     * {@code PLAN-library-template-label-resolution} D10 — the OCCDS product of an ADaMIG's own
     * generation: OCCDS 1.0 belongs to ADaMIG 1.1, OCCDS 1.1 to ADaMIG 1.2 and 1.3. ADaMIG 1.0 has
     * no OCCDS generation, and a TIG ADaM leg carries its own occurrence structure.
     */
    static final Map<String, String> OCCDS_BY_ADAMIG_VERSION = Map.of(//
            "1-1", "standards/adam/adam-occds-1-0", //
            "1-2", "standards/adam/adam-occds-1-1", //
            "1-3", "standards/adam/adam-occds-1-1");

    private static final String ADAMIG_PREFIX = "adamig-";

    /**
     * <b>D10 (owner, 2026-10-03): infer the OCCDS product from the ADaMIG version; an explicit
     * declaration wins.</b> Both pilot ADaM studies declare only ADaMIG 1.1, which publishes no
     * occurrence structure, so {@code ADAE} resolved against nothing — no required variables, no
     * labels, no types — although that IG's generation has OCCDS 1.0. When no declared product
     * publishes an {@code OCCURRENCE DATA STRUCTURE} (a declared {@code adam-occds-*} or
     * {@code adam-adae-1-0} is the user's explicit choice and is left alone), the first declared
     * ADaMIG's generation names the OCCDS product, which is appended at the <b>lowest</b>
     * precedence and announced at INFO — so report provenance
     * ({@link MetadataLibraryProvider#declaredStructureKeyedProducts()}) names it and a user who
     * wants another one declares it with {@code -mp}. Nothing is inferred for ADaMIG 1.0 or for a
     * TIG leg, and that is said at INFO too. An inferred product the source cannot deliver is a
     * WARNING and no inference — never a degraded run: the user declared nothing that failed.
     */
    private static void inferOccdsProduct(
            List<MetadataLibraryProvider.DeclaredAdamProduct> aDeclared,
            AdamProductFetcher aFetcher)
    {
        for (MetadataLibraryProvider.DeclaredAdamProduct declared : aDeclared)
        {
            for (StoredDataStructure ds : declared.product().dataStructures())
            {
                if (AdamDataStructureDetector.OCCDS
                        .equals(MetadataLibraryProvider.structureTokenOf(declared.cacheKey(), ds)))
                {
                    return; // an explicit OCCDS-publishing product wins
                }
            }
        }
        Optional<String> tigLeg = aDeclared.stream()
                .map(MetadataLibraryProvider.DeclaredAdamProduct::cacheKey)
                .filter(MetadataProductKeys::isTigAdamLeg).findFirst();
        if (tigLeg.isPresent())
        {
            LOGGER.log(System.Logger.Level.INFO,
                    "No OCCDS product inferred for {0}: a TIG ADaM leg carries its own "
                            + "occurrence structure (D10).",
                    tigLeg.get());
            return;
        }
        Optional<String> adamig = aDeclared.stream()
                .map(MetadataLibraryProvider.DeclaredAdamProduct::cacheKey).filter(key ->
                {
                    String id = MetadataProductKeys.adamProductIdOf(key);
                    return id != null && id.startsWith(ADAMIG_PREFIX);
                }).findFirst();
        if (adamig.isEmpty())
        {
            return; // no ADaMIG declared (an OCCDS-less supplement alone): nothing to infer from
        }
        String source = adamig.get();
        String version = Objects.requireNonNull(MetadataProductKeys.adamProductIdOf(source))
                .substring(ADAMIG_PREFIX.length());
        String occdsKey = OCCDS_BY_ADAMIG_VERSION.get(version);
        if (occdsKey == null)
        {
            LOGGER.log(System.Logger.Level.INFO,
                    "No OCCDS product inferred for {0}: ADaMIG {1} has no OCCDS generation (D10); "
                            + "declare one with -mp to resolve occurrence datasets.",
                    source, version);
            return;
        }
        try
        {
            StoredProduct occds = aFetcher.fetch(occdsKey);
            aDeclared.add(new MetadataLibraryProvider.DeclaredAdamProduct(occdsKey, occds));
            LOGGER.log(System.Logger.Level.INFO,
                    "OCCDS metadata product inferred from {0}: {1} appended at lowest precedence "
                            + "(D10: the OCCDS of the ADaMIG''s own generation); declare an OCCDS "
                            + "product with -mp to override.",
                    source, occdsKey);
        }
        catch (IOException e)
        {
            LOGGER.log(System.Logger.Level.WARNING,
                    "OCCDS metadata product {0}, inferred from {1}, is not available ({2}); "
                            + "occurrence datasets resolve against no structure.",
                    occdsKey, source, e.getMessage());
        }
    }


    /**
     * <b>Plan §6b — fail-early granularity.</b> For each declared product: hard-fail when
     * <em>every</em> published structure maps to no structure token
     * ({@link MetadataLibraryProvider#structureTokenOf}), WARN when only some do, stay silent when
     * all map.
     *
     * <p>
     * ⚠⚠ The asymmetry is the point. A blanket "fail if any structure is unmapped" would reject
     * {@code tig/1-0/adam} — a currently-working run — over {@code REFERENDS} alone. A blanket
     * "warn only" would let a wholly unaddressable product through as the silent no-op ruling 1
     * forbids. A product publishing <em>no</em> structures at all is neither: there is nothing
     * unmapped, so it is left to the loud SKIP the token chain already emits.
     * </p>
     *
     * @param aDeclared
     *            the assembled list
     * @throws UnmappedMetadataProductException
     *             when a declared product's structures map to no token at all
     */
    private static void validateDeclaredStructures(
            List<MetadataLibraryProvider.DeclaredAdamProduct> aDeclared)
    {
        for (MetadataLibraryProvider.DeclaredAdamProduct declared : aDeclared)
        {
            List<String> unmapped = new ArrayList<>();
            int mapped = 0;
            for (StoredDataStructure ds : declared.product().dataStructures())
            {
                if (MetadataLibraryProvider.structureTokenOf(declared.cacheKey(), ds) != null)
                {
                    mapped++;
                }
                else
                {
                    unmapped.add(ds.name() == null ? "<unnamed>" : ds.name());
                }
            }
            if (unmapped.isEmpty())
            {
                continue;
            }
            if (mapped == 0)
            {
                throw new UnmappedMetadataProductException(declared.cacheKey(), unmapped);
            }
            LOGGER.log(System.Logger.Level.WARNING,
                    "Declared metadata product {0} publishes {1} data structure(s) that map to no "
                            + "ADaM structure token and are therefore unreachable by any rule: "
                            + "{2}. Its other {3} structure(s) resolve normally.",
                    declared.cacheKey(), unmapped.size(), unmapped, mapped);
        }
    }
}
