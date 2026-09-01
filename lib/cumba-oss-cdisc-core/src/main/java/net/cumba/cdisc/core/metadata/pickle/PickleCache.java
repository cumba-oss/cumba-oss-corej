package net.cumba.cdisc.core.metadata.pickle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.razorvine.pickle.PickleException;
import net.razorvine.pickle.Unpickler;
import org.jspecify.annotations.Nullable;

/**
 * Read-only reader over the Python {@code cdisc-rules-engine} pickle metadata cache
 * ({@code resources/cache/*.pkl}). The pickles hold the raw CDISC Library JSON responses (as nested
 * {@code Map}/{@code List}/{@code String}, protocol 4, no custom classes) keyed by the Python
 * engine's cache keys, so this reader lets the Java engine consume <i>exactly the same</i> metadata
 * the Python oracle uses — with no API key and no network.
 *
 * <p>
 * The cache directory is <b>explicit</b> (decision: no default). {@link #openIfConfigured()} reads
 * the {@code cdisc.pickle.cache.dir} system property or {@code CDISC_PICKLE_CACHE_DIR} environment
 * variable and returns {@link Optional#empty()} when neither is set, so callers degrade gracefully
 * exactly as they do today when no Library access is configured.
 * </p>
 *
 * <p>
 * Key → file routing mirrors the Python {@code CachePopulator} layout:
 * </p>
 * <ul>
 * <li>{@code models/…} → {@code standards_models.pkl}</li>
 * <li>{@code standards/…} → {@code standards_details.pkl}</li>
 * <li>{@code library_variables_metadata/…} → {@code variables_metadata.pkl}</li>
 * <li>a CT package id (e.g. {@code sdtmct-2024-09-27}) → {@code <id>.pkl} (the whole file is the
 * package object) — use {@link #getCtPackage(String)}</li>
 * </ul>
 *
 * <p>
 * Each {@code .pkl} is decoded once and memoised. Thread-safe.
 * </p>
 */
public final class PickleCache
{

    /** System property naming the pickle cache directory. */
    public static final String CACHE_DIR_PROPERTY = "cdisc.pickle.cache.dir";

    /** Environment variable naming the pickle cache directory. */
    public static final String CACHE_DIR_ENV = "CDISC_PICKLE_CACHE_DIR";

    private static final String MODELS_FILE = "standards_models";

    private static final String STANDARDS_FILE = "standards_details";

    private static final String VARIABLES_FILE = "variables_metadata";

    private final Path dir;

    private final Map<String, Map<String, Object>> files = new ConcurrentHashMap<>();

    private PickleCache(Path aDir)
    {
        dir = aDir;
    }


    /**
     * Opens a reader over an explicit cache directory.
     *
     * @param aDir
     *            the directory containing the {@code *.pkl} files (typically the Python engine's
     *            {@code resources/cache}).
     * @return a reader bound to {@code aDir} (the directory is not required to exist until a file
     *         is actually read).
     */
    public static PickleCache open(Path aDir)
    {
        return new PickleCache(aDir);
    }


    /**
     * Opens a reader from the {@code cdisc.pickle.cache.dir} system property or
     * {@code CDISC_PICKLE_CACHE_DIR} environment variable.
     *
     * @return the reader, or empty when neither is configured (no default — callers degrade).
     */
    public static Optional<PickleCache> openIfConfigured()
    {
        String configured = System.getProperty(CACHE_DIR_PROPERTY);
        if (configured == null || configured.isBlank())
        {
            configured = System.getenv(CACHE_DIR_ENV);
        }
        if (configured == null || configured.isBlank())
        {
            return Optional.empty();
        }
        return Optional.of(open(Path.of(configured)));
    }


    /** The configured cache directory. */
    public Path directory()
    {
        return dir;
    }


    /**
     * Enumerates the published controlled-terminology package names from the cache directory,
     * mirroring the Python engine's offline enumeration ({@code script_utils.py}): every cache file
     * whose name contains {@code "ct-"} (e.g. {@code sdtmct-2020-03-27.pkl}) contributes its stem
     * ({@code sdtmct-2020-03-27}). The {@code "ct-"} substring spans
     * {@code sdtmct}/{@code sendct}/{@code adamct}/{@code cdashct}; the rule's
     * {@code applicableCtPackageTypes} filter narrows it per standard. Drives J9 — without this the
     * pickle path leaves {@code PUBLISHED_CT_PACKAGES} empty, so {@code valid_codelist_dates}
     * over-fires.
     *
     * @return the published CT package stems (empty when the directory is unreadable / has none)
     */
    public java.util.List<String> publishedCtPackages()
    {
        String[] files = Optional.ofNullable(dir.toFile().list()).orElse(new String[0]);
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String f : files)
        {
            if (f.contains("ct-"))
            {
                // Strip the file extension: the package stem is the name up to the first dot
                // (e.g. sdtmct-2020-03-27.pkl -> sdtmct-2020-03-27).
                int dot = f.indexOf('.');
                out.add(dot >= 0 ? f.substring(0, dot) : f);
            }
        }
        return out;
    }


    /**
     * The keys of {@code standards_details.pkl} — e.g. {@code standards/sdtmig/3-4},
     * {@code standards/adam/adamig-1-3}, {@code standards/tig/1-0/sdtm}.
     *
     * @return the key set in file order; empty when the file is absent or unreadable.
     */
    public Set<String> standardKeys()
    {
        return Collections.unmodifiableSet(new LinkedHashSet<>(load(STANDARDS_FILE).keySet()));
    }


    /**
     * The keys of {@code standards_models.pkl} — e.g. {@code models/sdtm/2-0},
     * {@code models/adam/2-1}.
     *
     * @return the key set in file order; empty when the file is absent or unreadable.
     */
    public Set<String> modelKeys()
    {
        return Collections.unmodifiableSet(new LinkedHashSet<>(load(MODELS_FILE).keySet()));
    }


    /** Whether the cache directory exists on disk (useful for test {@code assumeTrue} guards). */
    public boolean isAvailable()
    {
        return Files.isDirectory(dir);
    }


    /**
     * Resolves a keyed cache entry ({@code models/…}, {@code standards/…} or
     * {@code library_variables_metadata/…}).
     *
     * @param aCacheKey
     *            the Python cache key.
     * @return the raw JSON object as a {@code Map}, or empty when the file/key is absent.
     */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> get(String aCacheKey)
    {
        String file = fileFor(aCacheKey);
        if (file == null)
        {
            return Optional.empty();
        }
        Object value = load(file).get(aCacheKey);
        return value instanceof Map<?, ?> m ? Optional.of((Map<String, Object>) m)
                : Optional.empty();
    }


    /**
     * Resolves a CT package, whose entire {@code <id>.pkl} file is the package object.
     *
     * @param aPackageId
     *            the CT package id (e.g. {@code sdtmct-2024-09-27}).
     * @return the package object as a {@code Map}, or empty when the file is absent.
     */
    public Optional<Map<String, Object>> getCtPackage(String aPackageId)
    {
        Map<String, Object> contents = load(aPackageId);
        return contents.isEmpty() ? Optional.empty() : Optional.of(contents);
    }


    private static @Nullable String fileFor(String aCacheKey)
    {
        if (aCacheKey.startsWith("models/"))
        {
            return MODELS_FILE;
        }
        if (aCacheKey.startsWith("standards/"))
        {
            return STANDARDS_FILE;
        }
        if (aCacheKey.startsWith("library_variables_metadata/"))
        {
            return VARIABLES_FILE;
        }
        return null;
    }


    @SuppressWarnings("unchecked")
    private Map<String, Object> load(String aFileStem)
    {
        return files.computeIfAbsent(aFileStem, stem ->
        {
            Path file = dir.resolve(stem + ".pkl");
            if (!Files.isRegularFile(file))
            {
                return Map.of();
            }
            try
            {
                byte[] bytes = Files.readAllBytes(file);
                Object decoded = new Unpickler().loads(bytes);
                if (decoded instanceof Map<?, ?> m)
                {
                    return (Map<String, Object>) m;
                }
                return Map.of();
            }
            catch (IOException e)
            {
                throw new UncheckedIOException("Failed reading pickle cache file " + file, e);
            }
            catch (PickleException e)
            {
                throw new IllegalStateException("Failed decoding pickle cache file " + file, e);
            }
        });
    }
}
