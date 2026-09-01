package net.cumba.cdisc.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import net.cumba.cdisc.core.model.Rule;
import org.jspecify.annotations.Nullable;

/**
 * Front-door to the CDISC Library used by the rule engine. Hides
 * {@link net.cumba.cdisc.library.api.client.CdiscLibraryClient} and every
 * {@code net.cumba.cdisc.library.api.model.*} type from downstream modules so that neither
 * {@code manager.local} nor the {@code dataviewer.cli} sidecar needs an
 * {@code import net.cumba.cdisc.library.*} line in either production or test code.
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


    /**
     * Fetch the rule package for {@code aStandard}/{@code aVersion} from the Library API and map it
     * to the engine's domain. Returns an empty list when the API yields no rules. Throws
     * {@link IOException} on transport / parse failure with a message that names the standard and
     * version, preserving the user-visible error text that the manager-local code emitted before
     * this migration.
     */
    List<Rule> loadRules(String aStandard, String aVersion) throws IOException;


    /**
     * List CT-package IDs (e.g. {@code "sdtmct-2024-09-27"}, {@code "adamct-2024-03-29"}). Returns
     * an empty list on any failure — callers degrade to "no CT picker." Matches the existing
     * tolerance in the manager's prior {@code fetchCtPackageIds} helper.
     */
    List<String> listCtPackageIds();
}
