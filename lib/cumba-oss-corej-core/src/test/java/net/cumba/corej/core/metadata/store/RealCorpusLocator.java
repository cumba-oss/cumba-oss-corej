package net.cumba.corej.core.metadata.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import net.cumba.corej.core.metadata.pickle.PickleCache;

/**
 * Single resolution point for the REAL Python-engine pickle corpus the integration tests seed their
 * stores from (final cross-plan review, F5). Before it existed the two real-corpus test classes in
 * this module looked for the same corpus <em>differently</em> —
 * {@code StoreMetadataProviderFactoryAdamTest} gated on the {@code CDISC_PICKLE_CACHE_DIR}
 * environment variable alone while {@code StoreSeederRealDataConformanceTest} hardcoded
 * {@code /data/cdisc.metadata.library-cache-pkl} — so on a machine carrying the corpus at the
 * well-known path, two of the nine "migrated wholesale" ADaM tests silently never ran.
 *
 * <p>
 * Precedence follows {@code cumba-corej-rules}' {@code PickleCacheLocator}: the
 * {@code cdisc.pickle.cache.dir} system property, then the {@code CDISC_PICKLE_CACHE_DIR}
 * environment variable — plus, unlike that repo, the well-known {@link #WELL_KNOWN} directory as a
 * last tier, because it is the location this module's real-corpus conformance gate has always run
 * against and dropping it would take that gate off the corpus on the machines that carry it.
 * </p>
 *
 * <p>
 * ⚠ The rules repo's disposition is kept: a corpus that is <b>named but broken</b> fails loudly
 * ({@link IllegalStateException} naming both keys and the value) and is never silently skipped — a
 * typo'd path and an absent corpus must not produce the same green run. Only a corpus that is
 * absent <em>everywhere</em> (nothing configured, well-known directory missing — a CI runner, a
 * fresh clone) resolves empty, and every caller states that skip as a named {@code assumeTrue}
 * message, never a bare filter.
 * </p>
 */
public final class RealCorpusLocator
{

    /** The well-known real-corpus location this module's conformance gate has always used. */
    public static final Path WELL_KNOWN = Path.of("/data/cdisc.metadata.library-cache-pkl");

    /** The skip message every caller should use, naming the keys and the well-known tier. */
    public static final String ABSENT_MESSAGE = "no real pickle corpus: neither "
            + PickleCache.CACHE_DIR_PROPERTY + " nor " + PickleCache.CACHE_DIR_ENV + " is set and "
            + WELL_KNOWN + " does not exist - skipping";

    private RealCorpusLocator()
    {
    }


    /**
     * Resolves the real pickle corpus directory.
     *
     * @return the corpus directory, absolutised, or empty when nothing is configured and the
     *         well-known directory does not exist. Callers skip with {@link #ABSENT_MESSAGE}.
     * @throws IllegalStateException
     *             when the property or the environment names a path that is not a directory — loud,
     *             never a silent skip.
     */
    public static Optional<Path> locate()
    {
        String configured = System.getProperty(PickleCache.CACHE_DIR_PROPERTY);
        if (configured == null || configured.isBlank())
        {
            configured = System.getenv(PickleCache.CACHE_DIR_ENV);
        }
        if (configured != null && !configured.isBlank())
        {
            Path explicit = Path.of(configured);
            if (!Files.isDirectory(explicit))
            {
                throw new IllegalStateException(PickleCache.CACHE_DIR_ENV + " / "
                        + PickleCache.CACHE_DIR_PROPERTY + " is set to '" + configured
                        + "', which is not a directory. Fix it or unset it - a wrong path and "
                        + "no path must not look identical in a test log.");
            }
            return Optional.of(explicit.toAbsolutePath());
        }
        return Files.isDirectory(WELL_KNOWN) ? Optional.of(WELL_KNOWN) : Optional.empty();
    }
}
