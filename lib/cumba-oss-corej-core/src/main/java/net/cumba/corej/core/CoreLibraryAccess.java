package net.cumba.corej.core;

import java.nio.file.Path;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * Front-door to the CDISC Library. Since cache P4 this is a <b>seeding-time</b> API only — the
 * validation run path never opens one (ruling R2: no network during a run), and since the P4b
 * cross-repo lane re-pointed the manager's pickers at the unified metadata store its one remaining
 * production consumer is {@code WebApiStoreSeeder} (where an API key legitimately belongs). Hides
 * {@link net.cumba.cdisc.library.api.client.CdiscLibraryClient} from downstream modules so that
 * neither {@code manager.local} nor the {@code dataviewer.cli} sidecar needs an
 * {@code import net.cumba.cdisc.library.*} line in either production or test code; since the final
 * review's F4 cut {@code listCtPackageIds} (zero callers) it declares no instance methods at all —
 * it is the factory seam over the client, nothing more.
 *
 * <p>
 * Instances come from the static factories below. The default implementation
 * ({@link CoreLibraryAccessImpl}) wraps a
 * {@link net.cumba.cdisc.library.api.client.CdiscLibraryClient}; cdisc.core tests use
 * {@link CoreLibraryAccessImpl#forTesting(net.cumba.cdisc.library.api.client.CdiscLibraryClient)}
 * to wrap a Mockito mock, and downstream tests mock this interface directly.
 * </p>
 */
public interface CoreLibraryAccess
{

    /**
     * Open access using the {@code CDISC_API_KEY} environment variable or
     * {@code cdisc.library.api.key} system property and the default cache directory. Returns
     * {@link Optional#empty()} when no key is configured — callers degrade gracefully
     * (manager.local pattern).
     */
    static Optional<CoreLibraryAccess> openIfConfigured()
    {
        return CoreLibraryAccessImpl.openIfConfigured(null);
    }


    /**
     * As {@link #openIfConfigured()} but with an explicit cache directory. {@code null} falls back
     * to {@link net.cumba.cdisc.library.api.client.CdiscLibraryClient#getCache()} (default
     * {@code ~/.cdiscApiCache}). Useful when an embedding context (UI config, tests) wants a
     * non-default cache location.
     */
    static Optional<CoreLibraryAccess> openIfConfigured(Path aCacheDir)
    {
        return CoreLibraryAccessImpl.openIfConfigured(aCacheDir);
    }


    /**
     * Open access with an explicit API key, substituting {@code "dummy"} when {@code aApiKey} is
     * null/blank. Used by the CLI, which assumes Library rules are reachable anonymously and so
     * always wants a client even when no key is set.
     */
    static CoreLibraryAccess openWithApiKey(String aApiKey)
    {
        return CoreLibraryAccessImpl.open(aApiKey, null, null);
    }


    /**
     * Open access with full configuration. Any {@code null} arg picks the same default as the
     * underlying client: null/blank {@code aApiKey} → {@code "dummy"}, null {@code aBaseUrl} →
     * {@link net.cumba.cdisc.library.api.client.CdiscLibraryClient#DEFAULT_BASE_URL}, null
     * {@code aCacheDir} → {@link net.cumba.cdisc.library.api.client.CdiscLibraryClient#getCache()}.
     */
    static CoreLibraryAccess open(String aApiKey, @Nullable String aBaseUrl,
            @Nullable Path aCacheDir)
    {
        return CoreLibraryAccessImpl.open(aApiKey, aBaseUrl, aCacheDir);
    }
}
