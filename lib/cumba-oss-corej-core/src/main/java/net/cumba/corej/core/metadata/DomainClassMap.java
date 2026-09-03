package net.cumba.corej.core.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.CustomLog;
import org.jspecify.annotations.Nullable;

/**
 * Internal, extensible domain (CDISC code) &rarr; observation-class mapping used as a fallback when
 * the CDISC Library API cannot resolve a dataset's class (no/invalid API key, network failure, or a
 * domain the loaded product does not cover). Consulted by
 * {@link MetadataLibraryProvider#getDatasetClass(String, String)} between the product reverse-walk
 * and the heuristic custom-domain sniffer, so the curated mapping wins over the heuristic for known
 * standard domains while truly custom domains still fall through to the sniffer.
 *
 * <h2>Source</h2> A bundled JSON resource ({@value #RESOURCE}) namespaced by standard family
 * ({@code sdtm} / {@code send} / {@code adam}), each a flat {@code domain -> class} object. The map
 * is extended/overridden at runtime by a JSON file of the same shape pointed at by the
 * {@value #ENV_OVERRIDE} environment variable or the {@value #SP_OVERRIDE} system property
 * (env-first, mirroring the {@code COREJ_RULES_DIR} / {@code CDISC_API_CACHE} convention). External
 * entries merge over the bundled defaults per key.
 *
 * <h2>Lookup</h2> {@link #classFor(String, String)} uses the run's own standard family when it is
 * known (that family is authoritative — a domain absent there returns {@code null} so the caller's
 * sniffer can handle a custom domain). Only when no/unknown family is supplied does it scan every
 * family and return the first match (insertion order). Domain codes are matched case-insensitively.
 *
 * <p>
 * Immutable after construction; the process-wide instance is loaded lazily and cached.
 * </p>
 */
@CustomLog
public final class DomainClassMap
{

    /** Environment variable pointing at an external override/extension JSON file. */
    public static final String ENV_OVERRIDE = "COREJ_DOMAIN_CLASS_MAP";

    /** System property counterpart to {@link #ENV_OVERRIDE} (lower precedence than the env var). */
    public static final String SP_OVERRIDE = "corej.domain.class.map";

    private static final String RESOURCE = "/metadata/domain-class-map.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static volatile @Nullable DomainClassMap instance;

    /** family (lower-case) -&gt; (domain upper-case -&gt; class name). */
    private final Map<String, Map<String, String>> byFamily;

    DomainClassMap(Map<String, Map<String, String>> aByFamily)
    {
        byFamily = aByFamily;
    }


    /** Lazily-loaded, cached process-wide instance. */
    public static DomainClassMap getInstance()
    {
        DomainClassMap local = instance;
        if (local == null)
        {
            synchronized (DomainClassMap.class)
            {
                local = instance;
                if (local == null)
                {
                    local = load();
                    instance = local;
                }
            }
        }
        return local;
    }


    /** Builds an instance from the bundled resource plus any configured external override. */
    static DomainClassMap load()
    {
        String override = System.getenv(ENV_OVERRIDE);
        if (override == null || override.isBlank())
        {
            override = System.getProperty(SP_OVERRIDE);
        }
        return loadFrom(override == null || override.isBlank() ? null : Path.of(override));
    }


    /**
     * Builds an instance from the bundled resource, then merges the given external override file
     * when non-null and readable. Pure (no env/system-property/singleton access) so tests stay
     * hermetic.
     */
    static DomainClassMap loadFrom(@Nullable Path overrideFile)
    {
        Map<String, Map<String, String>> data = new LinkedHashMap<>();
        try (InputStream in = DomainClassMap.class.getResourceAsStream(RESOURCE))
        {
            if (in != null)
            {
                merge(data, MAPPER.readTree(in));
            }
            else
            {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Bundled domain-class map not found on classpath: {0}", RESOURCE);
            }
        }
        catch (IOException e)
        {
            LOGGER.log(System.Logger.Level.WARNING, "Failed to read bundled domain-class map", e);
        }

        if (overrideFile != null)
        {
            if (Files.isReadable(overrideFile))
            {
                try (InputStream in = Files.newInputStream(overrideFile))
                {
                    merge(data, MAPPER.readTree(in));
                    LOGGER.log(System.Logger.Level.INFO, "Merged external domain-class map: {0}",
                            overrideFile);
                }
                catch (IOException e)
                {
                    LOGGER.log(System.Logger.Level.WARNING,
                            "Failed to read external domain-class map {0}; using defaults",
                            overrideFile, e);
                }
            }
            else
            {
                LOGGER.log(System.Logger.Level.WARNING,
                        "External domain-class map not readable: {0}; using defaults",
                        overrideFile);
            }
        }
        return new DomainClassMap(data);
    }


    private static void merge(Map<String, Map<String, String>> target, JsonNode root)
    {
        if (root == null || !root.isObject())
        {
            return;
        }
        for (Map.Entry<String, JsonNode> family : root.properties())
        {
            JsonNode domains = family.getValue();
            if (!domains.isObject())
            {
                // Skips non-object members such as the "_comment" documentation string.
                continue;
            }
            Map<String, String> domainMap = target.computeIfAbsent(
                    family.getKey().toLowerCase(Locale.ROOT), _ -> new LinkedHashMap<>());
            for (Map.Entry<String, JsonNode> entry : domains.properties())
            {
                if (entry.getValue().isTextual())
                {
                    domainMap.put(entry.getKey().toUpperCase(Locale.ROOT),
                            entry.getValue().asText());
                }
            }
        }
    }


    /**
     * Resolves the observation class for a domain code.
     *
     * @param standardFamily
     *            the run's standard family ({@code sdtm} / {@code send} / {@code adam}). When it
     *            names a known family that family is authoritative — a domain absent there yields
     *            {@code null} (so the caller's sniffer can handle a genuinely custom domain) rather
     *            than matching a same-code entry in an unrelated family. When {@code null} / blank
     *            / an unknown family, every family is scanned (first match wins).
     * @param domainCode
     *            the CDISC domain code (e.g. {@code DM}, {@code LB}); matched case-insensitively
     * @return the mapped class name, or {@code null} when not resolved
     */
    public @Nullable String classFor(@Nullable String standardFamily, @Nullable String domainCode)
    {
        if (domainCode == null || domainCode.isEmpty())
        {
            return null;
        }
        String dom = domainCode.toUpperCase(Locale.ROOT);
        if (standardFamily != null && !standardFamily.isBlank())
        {
            Map<String, String> domainMap = byFamily.get(standardFamily.toLowerCase(Locale.ROOT));
            if (domainMap != null)
            {
                // Known family is authoritative: return its lookup (possibly null) without
                // falling through to other families (Q3: scan-all applies only when no namespace
                // is provided). This keeps a custom domain absent here on the sniffer path.
                return domainMap.get(dom);
            }
        }
        // No known family: scan all namespaces, first match wins (Q3).
        for (Map<String, String> domainMap : byFamily.values())
        {
            String mapped = domainMap.get(dom);
            if (mapped != null)
            {
                return mapped;
            }
        }
        return null;
    }
}
