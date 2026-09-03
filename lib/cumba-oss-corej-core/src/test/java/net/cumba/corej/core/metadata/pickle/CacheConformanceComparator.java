package net.cumba.corej.core.metadata.pickle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import net.cumba.web.api.cache.GzipFileApiCache;

/**
 * Compares two web-api cache directories for <b>engine conformance</b>.
 *
 * <p>
 * Byte equality is the wrong bar. The pickle-seeded cache can never be byte-identical to one filled
 * from the live API, because {@code get_codelist_terms_map} permanently drops the CT package
 * {@code label} (it embeds a package number), {@code description}, {@code source},
 * {@code registrationStatus} and all codelist/term {@code _links} before pickling. What must match
 * is everything the rule engine actually reads.
 * </p>
 *
 * <p>
 * So this comparator asserts two things and reports a third:
 * </p>
 * <ol>
 * <li>the two directories expose the same set of endpoints;</li>
 * <li>for every shared endpoint, the <em>engine-read projection</em> is identical — the
 * class/dataset/variable walk for products, and submission values, concept ids, extensibility and
 * terms for CT packages;</li>
 * <li>differences outside that projection are collected as informational, not failures.</li>
 * </ol>
 *
 * <p>
 * Test-scope for now. It is written to be reusable as the acceptance test for the planned
 * API-key-based cache filler: point it at a seeded cache and a live-filled one and it states
 * whether they agree where it counts.
 * </p>
 */
final class CacheConformanceComparator
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CacheConformanceComparator()
    {
    }

    /**
     * The outcome of a comparison.
     *
     * @param onlyInA
     *            endpoints present only in the first directory.
     * @param onlyInB
     *            endpoints present only in the second.
     * @param projectionDifferences
     *            shared endpoints whose engine-read projection differs — these are failures.
     * @param ignoredDifferences
     *            shared endpoints that differ only outside the projection — informational.
     */
    record Result(Set<String> onlyInA, Set<String> onlyInB, List<String> projectionDifferences,
            List<String> ignoredDifferences)
    {

        /**
         * Whether the two caches agree everywhere the engine reads.
         *
         * @return whether the two caches conform.
         */
        boolean conforms()
        {
            return onlyInA.isEmpty() && onlyInB.isEmpty() && projectionDifferences.isEmpty();
        }


        /**
         * Renders the difference sets for an assertion message.
         *
         * @return a human-readable description.
         */
        String describe()
        {
            return "onlyInA=%s onlyInB=%s projectionDifferences=%s (ignored=%d)".formatted(onlyInA,
                    onlyInB, projectionDifferences, ignoredDifferences.size());
        }
    }

    /**
     * Compares two cache directories.
     *
     * @param aLeft
     *            the first cache directory.
     * @param aRight
     *            the second cache directory.
     * @param aBasePathPrefix
     *            the cache-key prefix both were written with (e.g. {@code /api}).
     * @return the comparison result.
     * @throws IOException
     *             when a directory or entry cannot be read.
     */
    static Result compare(Path aLeft, Path aRight, String aBasePathPrefix) throws IOException
    {
        Set<String> left = endpoints(aLeft, aBasePathPrefix);
        Set<String> right = endpoints(aRight, aBasePathPrefix);

        Set<String> onlyLeft = new TreeSet<>(left);
        onlyLeft.removeAll(right);
        Set<String> onlyRight = new TreeSet<>(right);
        onlyRight.removeAll(left);

        Set<String> shared = new LinkedHashSet<>(left);
        shared.retainAll(right);

        List<String> projection = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        for (String endpoint : new TreeSet<>(shared))
        {
            JsonNode a = read(aLeft, endpoint);
            JsonNode b = read(aRight, endpoint);
            if (!project(a).equals(project(b)))
            {
                projection.add(endpoint);
            }
            else if (!a.equals(b))
            {
                ignored.add(endpoint);
            }
        }
        return new Result(onlyLeft, onlyRight, projection, ignored);
    }


    /** Recovers endpoint paths from the gzip file names written by {@code FileApiCache}. */
    private static Set<String> endpoints(Path aDir, String aBasePathPrefix) throws IOException
    {
        Set<String> out = new TreeSet<>();
        if (!Files.isDirectory(aDir))
        {
            return out;
        }
        try (Stream<Path> files = Files.list(aDir))
        {
            files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json.gz"))
                    .map(n -> n.substring(0, n.length() - ".json.gz".length()))
                    // FileApiCache.toCacheFileName maps '/' to '_' and *then* URL-encodes, so the
                    // inverse has to decode first. Since keys carry the query string, a name like
                    // "api_mdr_ct_packages_sdtmct-2024-09-27%3Fexpand%3Dtrue" would otherwise be
                    // handed back to the cache still encoded — which re-encodes it and misses.
                    .map(n -> URLDecoder.decode(n, StandardCharsets.UTF_8))
                    .map(n -> "/" + n.replace('_', '/'))
                    .filter(p -> aBasePathPrefix.isEmpty() || p.startsWith(aBasePathPrefix))
                    .forEach(out::add);
        }
        return out;
    }


    private static JsonNode read(Path aDir, String aEndpoint) throws IOException
    {
        String json = new GzipFileApiCache(aDir.toAbsolutePath(), ".json").read(aEndpoint)
                .orElseThrow(() -> new IOException("missing entry " + aEndpoint + " in " + aDir));
        return MAPPER.readTree(json);
    }


    /**
     * Reduces an entry to exactly what the engine reads, so representation differences the engine
     * cannot observe (a display label, a dropped description, boolean-vs-string extensibility) do
     * not register as divergence.
     */
    private static JsonNode project(JsonNode aBody)
    {
        ObjectNode out = MAPPER.createObjectNode();

        if (aBody.has("codelists"))
        {
            ArrayNode codelists = MAPPER.createArrayNode();
            for (JsonNode cl : aBody.path("codelists"))
            {
                ObjectNode c = MAPPER.createObjectNode();
                c.put("submissionValue", cl.path("submissionValue").asText(null));
                c.put("conceptId", cl.path("conceptId").asText(null));
                c.put("extensible", asBoolean(cl.path("extensible")));
                ArrayNode terms = MAPPER.createArrayNode();
                for (JsonNode t : cl.path("terms"))
                {
                    ObjectNode term = MAPPER.createObjectNode();
                    term.put("submissionValue", t.path("submissionValue").asText(null));
                    term.put("preferredTerm", t.path("preferredTerm").asText(null));
                    term.put("conceptId", t.path("conceptId").asText(null));
                    terms.add(term);
                }
                c.set("terms", terms);
                codelists.add(c);
            }
            out.set("codelists", codelists);
        }

        if (aBody.has("classes"))
        {
            ArrayNode classes = MAPPER.createArrayNode();
            for (JsonNode k : aBody.path("classes"))
            {
                ObjectNode c = MAPPER.createObjectNode();
                c.put("name", k.path("name").asText(null));
                c.set("classVariables", variableNames(k.path("classVariables")));
                ArrayNode datasets = MAPPER.createArrayNode();
                for (JsonNode d : k.path("datasets"))
                {
                    ObjectNode ds = MAPPER.createObjectNode();
                    ds.put("name", d.path("name").asText(null));
                    ds.set("datasetVariables", variableNames(d.path("datasetVariables")));
                    datasets.add(ds);
                }
                c.set("datasets", datasets);
                classes.add(c);
            }
            out.set("classes", classes);
        }

        if (aBody.has("_links") && aBody.path("_links").has("packages"))
        {
            ArrayNode packages = MAPPER.createArrayNode();
            for (JsonNode p : aBody.path("_links").path("packages"))
            {
                packages.add(p.path("href").asText(null));
            }
            out.set("packages", packages);
        }
        return out;
    }


    /** Nulls are meaningful here, so {@code null} is preserved rather than coerced to "". */
    private static ArrayNode variableNames(JsonNode aVariables)
    {
        ArrayNode names = MAPPER.createArrayNode();
        for (JsonNode v : aVariables)
        {
            names.add(v.path("name").asText(null));
        }
        return names;
    }


    /** Normalises the two wire forms of {@code extensible} so they compare equal. */
    private static boolean asBoolean(JsonNode aNode)
    {
        return aNode.isBoolean() ? aNode.booleanValue() : Boolean.parseBoolean(aNode.asText());
    }
}
