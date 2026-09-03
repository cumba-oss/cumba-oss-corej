package net.cumba.corej.core.metadata;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.CustomLog;
import net.cumba.cdisc.library.api.client.CdiscLibraryClient;
import net.cumba.cdisc.library.api.model.products.Products;
import net.cumba.corej.core.CoreLibraryAccess;
import net.cumba.corej.core.CoreLibraryAccessImpl;
import net.cumba.corej.core.metadata.pickle.PickleCache;
import net.cumba.corej.core.metadata.pickle.PickleMetadataProviderFactory;
import net.cumba.web.api.Link;
import org.jspecify.annotations.Nullable;

/**
 * Phase 7b of {@code plans/PLAN-metadata-product-selection.md} — the <b>source-agnostic product
 * catalogue</b> a {@code --metadata-products} token is resolved against. Two sources exist,
 * selected by what is configured and <b>unioned when both are</b>:
 *
 * <ul>
 * <li><b>pickle</b> — {@link PickleCache#standardKeys()} verbatim ({@code standards/sdtmig/3-4},
 * {@code standards/adam/adamig-1-3}, {@code standards/tig/1-0/adam}, …);</li>
 * <li><b>CDISC Library API</b> — {@code GET /mdr/products} (served from the configured
 * {@code CDISC_API_CACHE} when present, the network otherwise), filtered to entries typed
 * {@code "Implementation Guide"} and mapped {@code /mdr/<group>/<product>} →
 * {@code standards/<group>/<product>}.</li>
 * </ul>
 *
 * <p>
 * §7-1 (measured 2026-08-28): all 30 real IG products appear <b>identically</b> in both sources
 * under {@code <group>/<product>}, so no translation layer exists here — the mapping above is a
 * prefix swap. The type filter is what keeps the catalogue honest: a naive enumeration of the API
 * cache offers 391 paths of which 361 are sub-resources ({@code sdtmig/3-4/datasets/AE}, …), and
 * {@code /mdr/products} itself lists the <b>models</b> ({@code adam/adam-2-1},
 * {@code sdtm/1-2}…{@code 2-1}, {@code cdash/1-x}) as {@code "Foundational Model"} — none of which
 * is a declarable metadata product.
 * </p>
 *
 * <p>
 * ⚠⚠ <b>TIG is pickle-only.</b> The API's product list carries TIG under
 * {@code /mdr/integrated/tig/...}, but the API cache holds no TIG payloads and
 * {@code CdiscLibraryClient} has no TIG endpoints, so offering a TIG key from the API side would
 * resolve a token the run can never load. The API contribution therefore excludes TIG, and
 * {@code ProductKeyResolver} uses {@link #pickleConfigured()} to say <i>why</i> a TIG token failed
 * in an API-only deployment instead of failing mysteriously.
 * </p>
 *
 * <p>
 * A source that is configured but unreadable (an offline run with an empty API cache, a missing
 * pickle file) contributes nothing, with a WARN — resolution then proceeds against the other
 * source, and with no source at all only full-key tokens resolve (see {@code ProductKeyResolver}).
 * </p>
 */
@CustomLog
public final class MetadataProductCatalogue
{

    /** The API's product-list entry type that marks a declarable product. */
    private static final String IMPLEMENTATION_GUIDE = "Implementation Guide";

    /**
     * The base URL the API client is pointed at when <b>no CDISC Library API key is configured</b>.
     *
     * <p>
     * ⛔⛔ <b>Phase 11 finding F7.</b> {@code CoreLibraryAccessImpl.open} substitutes a
     * {@code "dummy"} API key when none is set, so a cache <em>miss</em> here issued a real request
     * to {@code api.library.cdisc.org} that could only ever 401 — once per token resolution, from
     * every unit test that resolves a {@code --metadata-products} token, in a build that must never
     * touch the network. The cache is consulted <b>before</b> the HTTP call, so pointing a keyless
     * client at an address that cannot leave the machine keeps every cache hit working exactly as
     * before and turns every cache miss into an immediate local failure — which the catch in
     * {@link #build} already handles as "this source contributes nothing, with a WARN".
     * </p>
     *
     * <p>
     * ⚠ Port 1 is reserved and unbindable without privileges, so the connection is refused rather
     * than hanging. This is deliberately <em>not</em> a "skip the API side without a key" rule:
     * that would lose the 30 cached products in exactly the offline, key-less deployment this
     * feature targets.
     * </p>
     */
    static final String OFFLINE_BASE_URL = "http://127.0.0.1:1/";

    /**
     * Memoised catalogues, keyed by everything {@link #build} reads. A catalogue is an enumeration
     * of published products — it does not change within a process — but building one costs a
     * pickle-cache scan and a {@code GET /mdr/products}.
     *
     * <p>
     * ⛔ <b>Phase 11 finding F7.</b> {@link #configured} was called once per
     * {@code --metadata-products} resolution and rebuilt everything each time. In the REST server
     * that is one product-list fetch per submitted run; under the CLI test suite it was one per
     * test. The key is the <b>resolved</b> configuration, not the caller's arguments, so two
     * callers that resolve to the same pickle directory share one entry and a caller that changes
     * {@code CDISC_PICKLE_CACHE_DIR} between calls does not read a stale one.
     * </p>
     */
    private static final ConcurrentMap<CatalogueKey, MetadataProductCatalogue> MEMO = new ConcurrentHashMap<>();

    /**
     * The resolved configuration a memoised catalogue was built from.
     *
     * @param pickleDir
     *            the resolved pickle-cache directory, or {@code null} when none is configured
     * @param apiCacheDir
     *            the resolved API-cache directory, or {@code null}
     * @param apiKeyConfigured
     *            whether a real CDISC Library API key is available — it decides whether the client
     *            may leave the machine at all (see {@link #OFFLINE_BASE_URL})
     */
    private record CatalogueKey(@Nullable String pickleDir, @Nullable String apiCacheDir,
            boolean apiKeyConfigured)
    {
    }

    private final Set<String> keys;

    private final boolean pickleConfigured;

    private final List<String> sources;

    private MetadataProductCatalogue(Set<String> aKeys, boolean aPickleConfigured,
            List<String> aSources)
    {
        keys = Collections.unmodifiableSet(new LinkedHashSet<>(aKeys));
        pickleConfigured = aPickleConfigured;
        sources = List.copyOf(aSources);
    }


    /**
     * The catalogue for the current configuration: the pickle cache when one is configured
     * ({@code --pickle-cache} / {@code CDISC_PICKLE_CACHE_DIR} / {@code cdisc.pickle.cache.dir}),
     * unioned with the CDISC Library API's product list ({@code /mdr/products}, served from the
     * {@code --cache} / {@code CDISC_API_CACHE} directory when cached there).
     *
     * @param aExplicitPickleDir
     *            an explicit pickle-cache directory (CLI {@code --pickle-cache}); may be
     *            {@code null}
     * @param aExplicitApiCacheDir
     *            an explicit API-cache directory (CLI {@code --cache}); may be {@code null} — the
     *            client then falls back to {@code CDISC_API_CACHE} and its own default
     * @return the configured catalogue (possibly empty)
     */
    public static MetadataProductCatalogue configured(@Nullable String aExplicitPickleDir,
            @Nullable String aExplicitApiCacheDir)
    {
        Path pickleDir = PickleMetadataProviderFactory.resolveConfiguredDir(aExplicitPickleDir);
        Path apiCacheDir = aExplicitApiCacheDir == null || aExplicitApiCacheDir.isBlank() ? null
                : Path.of(aExplicitApiCacheDir).toAbsolutePath();
        String apiKey = configuredApiKey();
        CatalogueKey memoKey = new CatalogueKey(pickleDir == null ? null : pickleDir.toString(),
                apiCacheDir == null ? null : apiCacheDir.toString(),
                apiKey != null && !apiKey.isBlank());
        return MEMO.computeIfAbsent(memoKey, _ -> build(pickleDir, apiCacheDir, apiKey));
    }


    /** The configured CDISC Library API key, from the environment or the system property. */
    private static @Nullable String configuredApiKey()
    {
        String apiKey = System.getenv(CdiscLibraryClient.ENV_CDISC_API_KEY);
        if (apiKey == null || apiKey.isBlank())
        {
            apiKey = System.getProperty(CdiscLibraryClient.SP_CDISC_API_KEY);
        }
        return apiKey;
    }


    /**
     * The base URL a catalogue's API client is given: the client's own default when a real API key
     * is configured, {@link #OFFLINE_BASE_URL} when none is — see {@link #OFFLINE_BASE_URL} for why
     * a keyless client must not be able to leave the machine.
     *
     * @param aApiKey
     *            the configured API key, or {@code null}/blank when none is
     * @return the base URL to build the client with, or {@code null} for the client's default
     */
    static @Nullable String apiBaseUrl(@Nullable String aApiKey)
    {
        return aApiKey == null || aApiKey.isBlank() ? OFFLINE_BASE_URL : null;
    }


    /**
     * Opens the API access the catalogue reads its product list through — the single place a client
     * is constructed here, and the seam a test observes to prove a keyless client is pointed at
     * {@link #OFFLINE_BASE_URL}.
     */
    static CoreLibraryAccess openApiAccess(@Nullable String aApiKey, @Nullable Path aApiCacheDir)
    {
        // CoreLibraryAccessImpl.open substitutes its own "dummy" key for a blank one; the empty
        // string keeps that behaviour while satisfying the non-null parameter.
        String key = aApiKey == null ? "" : aApiKey;
        return CoreLibraryAccess.open(key, apiBaseUrl(key), aApiCacheDir);
    }


    /** Builds one catalogue. Unmemoised — {@link #configured} is the memoised entry point. */
    private static MetadataProductCatalogue build(@Nullable Path aPickleDir,
            @Nullable Path aApiCacheDir, @Nullable String aApiKey)
    {
        Set<String> keys = new LinkedHashSet<>();
        List<String> sources = new java.util.ArrayList<>();
        boolean pickle = false;
        if (aPickleDir != null)
        {
            pickle = true;
            try
            {
                keys.addAll(PickleCache.open(aPickleDir).standardKeys());
                sources.add("pickle cache " + aPickleDir);
            }
            catch (RuntimeException e)
            {
                // ⛔ Review finding R-16 — this read used to sit OUTSIDE any catch, while this
                // class's own javadoc promises "a source that is configured but unreadable
                // contributes nothing, with a WARN". PickleCache.load returns Map.of() for an
                // ABSENT file but rethrows UncheckedIOException / IllegalStateException for one
                // that is unreadable or truncated, so a half-written or mode-000 cache propagated
                // out of a catalogue build. Since Plan 2 Phase 7 that build also backs
                // /api/meta/run-options, so it took the SPA's whole run form down with a 500 —
                // packages and defineVersions included — instead of just offering no products.
                LOGGER.log(System.Logger.Level.WARNING,
                        "Pickle metadata cache unavailable for --metadata-products resolution "
                                + "({0}): {1}. Continuing without it.",
                        aPickleDir, String.valueOf(e));
            }
        }
        try
        {
            CoreLibraryAccess access = openApiAccess(aApiKey, aApiCacheDir);
            if (access instanceof CoreLibraryAccessImpl impl)
            {
                keys.addAll(apiProductKeys(impl.client()));
                sources.add("CDISC Library API product list");
            }
        }
        catch (IOException | RuntimeException e)
        {
            // An unreadable API side contributes nothing; the pickle side (or the full-key
            // fallback) still resolves. Never fatal — cataloguing is a convenience, the run's
            // own fetches fail loudly on their own.
            LOGGER.log(System.Logger.Level.WARNING,
                    "CDISC Library product list unavailable for --metadata-products resolution "
                            + "({0}); resolving against {1}.",
                    String.valueOf(e), sources.isEmpty() ? "full-form keys only" : sources);
        }
        return new MetadataProductCatalogue(keys, pickle, sources);
    }


    /**
     * A catalogue over an explicit key set — the seam the pickle contribution uses and tests
     * construct directly.
     *
     * @param aKeys
     *            {@code standards/...} keys
     * @param aPickleConfigured
     *            whether a pickle source contributed (drives the TIG failure wording)
     * @param aSources
     *            human-readable source names, for messages
     * @return the catalogue
     */
    public static MetadataProductCatalogue of(Set<String> aKeys, boolean aPickleConfigured,
            List<String> aSources)
    {
        return new MetadataProductCatalogue(aKeys, aPickleConfigured, aSources);
    }


    /**
     * The API contribution: {@code GET /mdr/products}, filtered to {@value #IMPLEMENTATION_GUIDE}
     * entries with a two-segment {@code /mdr/<group>/<product>} href, mapped onto
     * {@code standards/<group>/<product>}. TIG ({@code /mdr/integrated/...}) is excluded — see the
     * class javadoc.
     *
     * @param aClient
     *            the library client (cache-through)
     * @return the product keys the API side offers
     * @throws IOException
     *             when the product list cannot be read (offline with no cached copy)
     */
    static Set<String> apiProductKeys(CdiscLibraryClient aClient) throws IOException
    {
        Products products = aClient.getProducts();
        Set<String> keys = new LinkedHashSet<>();
        for (Link link : products.allLinks())
        {
            if (!IMPLEMENTATION_GUIDE.equals(link.type().orElse(null)))
            {
                continue;
            }
            String href = link.href().orElse(null);
            if (href == null || !href.startsWith("/mdr/"))
            {
                continue;
            }
            String tail = href.substring("/mdr/".length());
            // Exactly <group>/<product>: sub-resources, QRS instruments and the integrated TIG
            // tree all carry more segments and are not addressable products here.
            if (tail.chars().filter(c -> c == '/').count() == 1)
            {
                keys.add("standards/" + tail);
            }
        }
        return keys;
    }


    /** The unioned {@code standards/...} product keys, in source order. */
    public Set<String> keys()
    {
        return keys;
    }


    /** Whether a pickle source contributed — the only source that can offer TIG. */
    public boolean pickleConfigured()
    {
        return pickleConfigured;
    }


    /** Human-readable source names, for failure messages ("resolved against …"). */
    public List<String> sources()
    {
        return sources;
    }
}
