package net.cumba.corej.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import lombok.CustomLog;
import net.cumba.cdisc.library.api.client.CdiscLibraryClient;
import net.cumba.cdisc.library.api.model.ct.CtPackage;
import net.cumba.cdisc.library.api.model.ct.CtPackageList;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.web.api.Link;
import net.cumba.web.api.cache.ApiCache;
import net.cumba.web.api.cache.GzipFileApiCache;
import org.jspecify.annotations.Nullable;

/**
 * Default {@link CoreLibraryAccess} implementation, wrapping a {@link CdiscLibraryClient}.
 *
 * <p>
 * {@code public final} so the type is visible (which lets
 * {@link net.cumba.corej.core.metadata.CdiscLibraryProviderBuilder} cast a
 * {@code CoreLibraryAccess} to the impl to reach {@link #client()} or
 * {@link #fetchCtPackage(String)}), but the constructor is package-private — downstream modules
 * cannot instantiate it. The cross-package helpers ({@link #client()},
 * {@link #forTesting(CdiscLibraryClient)}, {@link #fetchCtPackage(String)}) are all {@code public}
 * because Java package-private cannot bridge {@code cdisc.core} and {@code cdisc.core.metadata};
 * they are scoped <em>by convention</em>, not by language: any non-test downstream caller has to
 * import a {@code cdisc.library.*} type to use them, which re-introduces the very coupling this API
 * removes — self-defeating.
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
     * Public for cross-package use by {@code CdiscLibraryProviderBuilder}. Returns a cdisc.library
     * type intentionally; the same self-defeating-import argument keeps non-test downstream
     * consumers out.
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


    @Override
    public List<Rule> loadRules(String aStandard, String aVersion) throws IOException
    {
        // Moved from CoreEngineRunner.loadBaseFromApi. The IOException wrap names the
        // standard + version so the end user sees the same error text after the
        // migration; CoreLibraryAccessTest pins the message format as a regression
        // guard.
        try
        {
            net.cumba.cdisc.library.api.model.rules.RulePackage apiPkg = client.getRules(aStandard,
                    aVersion);
            RulePackage engine = LibraryRuleMapper.mapRulePackage(apiPkg);
            if (engine == null || engine.getRules() == null || engine.getRules().isEmpty())
            {
                return List.of();
            }
            return new ArrayList<>(engine.getRules().values());
        }
        catch (IOException e)
        {
            throw new IOException("Failed to fetch rules for " + aStandard + " " + aVersion
                    + " from the CDISC Library API: " + e.getMessage(), e);
        }
    }


    @Override
    public List<String> listCtPackageIds()
    {
        // Moved verbatim from CoreCheckProperties.fetchCtPackageIds.
        try
        {
            CtPackageList list = client.getCtPackages();
            if (list == null)
            {
                return List.of();
            }
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (Link link : list.packageLinks())
            {
                link.id().ifPresent(id ->
                {
                    if (!id.isBlank())
                    {
                        ids.add(id);
                    }
                });
            }
            return List.copyOf(ids);
        }
        catch (IOException _)
        {
            return List.of();
        }
    }


    /**
     * Best-effort CT package fetch used by {@code CdiscLibraryProviderBuilder}'s internal
     * resolveCtPackage loop. No-throw: returns {@code null} on {@link IOException} so the loop can
     * fall through to the next candidate id. Preserves the fall-through semantics of the legacy
     * {@code fetchCt} loops in {@code CoreEngineRunner} and {@code CdiscValidate} (Fix #65),
     * including the CLI's prior {@code WARNING} per failed fetch — the legacy CLI loop emitted
     * {@code "Failed to fetch CT package {id}: {cause}"}; we log the same here so the user-visible
     * diagnostic survives the migration.
     */
    public @Nullable CtPackage fetchCtPackage(String aId)
    {
        try
        {
            return client.getCtPackage(aId, true);
        }
        catch (IOException e)
        {
            LOGGER.log(System.Logger.Level.WARNING, "Failed to fetch CT package {0}: {1}", aId,
                    e.getMessage());
            return null;
        }
    }
}
