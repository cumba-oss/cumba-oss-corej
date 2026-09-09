package net.cumba.corej.core;

import java.nio.file.Path;
import java.util.Optional;
import lombok.CustomLog;
import net.cumba.cdisc.library.api.client.CdiscLibraryClient;
import net.cumba.web.api.cache.ApiCache;
import net.cumba.web.api.cache.GzipFileApiCache;
import org.jspecify.annotations.Nullable;

/**
 * Default {@link CoreLibraryAccess} implementation, wrapping a {@link CdiscLibraryClient}.
 *
 * <p>
 * {@code public final} so the type is visible (which lets
 * {@code net.cumba.corej.core.metadata.store.seed.WebApiStoreSeeder} cast a
 * {@code CoreLibraryAccess} to the impl to reach {@link #client()}), but the constructor is
 * package-private — downstream modules cannot instantiate it. The cross-package helpers
 * ({@link #client()}, {@link #forTesting(CdiscLibraryClient)}) are all {@code public} because Java
 * package-private cannot bridge {@code cdisc.core} and {@code cdisc.core.metadata}; they are scoped
 * <em>by convention</em>, not by language: any non-test downstream caller has to import a
 * {@code cdisc.library.*} type to use them, which re-introduces the very coupling this API removes
 * — self-defeating.
 * </p>
 */
@CustomLog
public final class CoreLibraryAccessImpl implements CoreLibraryAccess
{

    private final CdiscLibraryClient client;

    /**
     * Package-private: only same-package factories on {@link CoreLibraryAccess} and the
     * {@link #forTesting(CdiscLibraryClient)} seam can construct.
     */
    CoreLibraryAccessImpl(CdiscLibraryClient aClient)
    {
        client = aClient;
    }


    /**
     * Visible-for-testing factory. Wraps the supplied client without consulting environment
     * variables or system properties. Non-test callers must already import
     * {@link CdiscLibraryClient}, which re-introduces the cdisc.library coupling this API removes —
     * so production use is self-defeating by construction.
     */
    public static CoreLibraryAccess forTesting(CdiscLibraryClient aMockClient)
    {
        return new CoreLibraryAccessImpl(aMockClient);
    }


    /**
     * Public for cross-package use by {@code WebApiStoreSeeder}. Returns a cdisc.library type
     * intentionally; the same self-defeating-import argument keeps non-test downstream consumers
     * out.
     */
    public CdiscLibraryClient client()
    {
        return client;
    }


    /**
     * Open access using the env/sysprop-resolved API key. Returns {@link Optional#empty()} when no
     * key is configured (manager.local pattern — degrade silently rather than substituting a
     * dummy). Package-private so the {@link CoreLibraryAccess} static factories on the interface
     * stay the only entry point.
     */
    static Optional<CoreLibraryAccess> openIfConfigured(@Nullable Path aCacheDir)
    {
        String apiKey = CdiscLibraryClient.getApiKey();
        if (apiKey == null || apiKey.isBlank())
        {
            return Optional.empty();
        }
        String apiUrl = CdiscLibraryClient.getApiUrl();
        ApiCache cache = resolveCache(aCacheDir);
        CdiscLibraryClient builtClient = CdiscLibraryClient.builder()//
                .apiKey(apiKey)//
                .cache(cache)//
                .baseUrl(apiUrl)//
                .build();
        return Optional.of(new CoreLibraryAccessImpl(builtClient));
    }


    /**
     * Open access with full configuration. Null/blank {@code aApiKey} substitutes {@code "dummy"}
     * (CLI compatibility — Library rule endpoints are reachable anonymously). Null {@code aBaseUrl}
     * falls back to {@link CdiscLibraryClient#DEFAULT_BASE_URL}. Null {@code aCacheDir} falls back
     * to {@link CdiscLibraryClient#getCache()}.
     */
    static CoreLibraryAccess open(String aApiKey, @Nullable String aBaseUrl,
            @Nullable Path aCacheDir)
    {
        String apiKey = (aApiKey == null || aApiKey.isBlank()) ? "dummy" : aApiKey;
        String baseUrl = aBaseUrl != null ? aBaseUrl : CdiscLibraryClient.DEFAULT_BASE_URL;
        ApiCache cache = resolveCache(aCacheDir);
        CdiscLibraryClient builtClient = CdiscLibraryClient.builder()//
                .apiKey(apiKey)//
                .cache(cache)//
                .baseUrl(baseUrl)//
                .build();
        return new CoreLibraryAccessImpl(builtClient);
    }


    private static ApiCache resolveCache(@Nullable Path aCacheDir)
    {
        if (aCacheDir == null)
        {
            return CdiscLibraryClient.getCache();
        }
        return new GzipFileApiCache(aCacheDir.toAbsolutePath(), ".json");
    }

}
