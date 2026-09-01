package net.cumba.cdisc.core.metadata;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import lombok.CustomLog;
import net.cumba.cdisc.core.metadata.pickle.PickleProductSource;
import net.cumba.cdisc.library.api.model.adam.AdamDataStructure;
import net.cumba.cdisc.library.api.model.adam.AdamProduct;

/**
 * Phase 7 of {@code plans/PLAN-metadata-product-selection.md} — <b>the single construction site</b>
 * that turns the run's declared metadata-product keys ({@code --metadata-products}, resolved to
 * verbatim {@code standards/...} cache keys) into the ordered
 * {@link MetadataLibraryProvider.DeclaredAdamProduct} list the provider walks (ruling 1:
 * first-match-wins on the user's order).
 *
 * <p>
 * ⚠⚠⚠ <b>Both cache paths MUST funnel through {@link #assemble}.</b> The API path
 * ({@code CdiscLibraryProviderBuilder.buildAdam}) and the pickle path
 * ({@code PickleMetadataProviderFactory.forAdam}) differ only in the {@link AdamProductFetcher}
 * they supply. A path that built its own list — as the pickle path would have if
 * {@code tryPickleProvider} constructed its provider directly — would leave the multi-product
 * feature silently inert on that source while every test stayed green. Grep discipline: nothing
 * outside this class may turn a declared-key list into {@code DeclaredAdamProduct}s.
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
     * {@code standards/...} key. Implementations: the CDISC Library API
     * ({@code client.getAdamProduct(id, true)} — which must <b>refuse a TIG leg with a stated
     * reason</b>, the API has no TIG) and the pickle cache ({@code cache.get(key)}).
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
        AdamProduct fetch(String aCacheKey) throws IOException;
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
            String igKey = PickleProductSource.standardsKey(aStandardName, aStandardVersion);
            out = List.of(
                    new MetadataLibraryProvider.DeclaredAdamProduct(igKey, aFetcher.fetch(igKey)));
        }
        else
        {
            LOGGER.log(System.Logger.Level.INFO,
                    "ADaM metadata resolves against {0} declared product(s), in precedence order: "
                            + "{1}",
                    out.size(), out.stream()
                            .map(MetadataLibraryProvider.DeclaredAdamProduct::cacheKey).toList());
        }
        validateDeclaredStructures(out);
        return List.copyOf(out);
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
            for (AdamDataStructure ds : declared.product().dataStructures())
            {
                if (MetadataLibraryProvider.structureTokenOf(declared.cacheKey(), ds) != null)
                {
                    mapped++;
                }
                else
                {
                    unmapped.add(ds.name().orElse("<unnamed>"));
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
