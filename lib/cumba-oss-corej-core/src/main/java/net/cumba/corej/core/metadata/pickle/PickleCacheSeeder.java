package net.cumba.corej.core.metadata.pickle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.cumba.web.api.cache.ApiCache;
import net.cumba.web.api.cache.CacheEntry;
import net.cumba.web.api.cache.GzipFileApiCache;
import net.cumba.web.api.http.HttpRequest;
import org.jspecify.annotations.Nullable;

/**
 * Converts the Python engine's pickle metadata cache into CDISC Library <b>web-api cache</b>
 * entries, so a user without an API key can populate the ordinary {@link GzipFileApiCache} that
 * {@code CdiscLibraryClient} already reads.
 *
 * <p>
 * Nothing downstream changes: after seeding, the engine takes its normal API-client path and finds
 * the answers already cached. The conversion is a <em>re-serialisation</em>, not a
 * re-interpretation — {@link PickleProductSource} already proves the pickle maps and the live API
 * responses produce identical typed views.
 * </p>
 *
 * <h2>Canonical form: API-faithful</h2>
 *
 * <p>
 * The same directory is written by three parties — this seeder, a future API-key-based filler, and
 * ordinary online operation (every live response is cached by {@code AbstractApiClient}). Entries
 * are therefore written as the <b>API</b> would serialise them, never in a normalised form that
 * online traffic would immediately contradict:
 * </p>
 *
 * <ul>
 * <li>CT {@code name} carries the display label ({@code "SDTM CT 2024-09-27"}), not the package
 * id;</li>
 * <li>{@code extensible} is written back as a JSON <em>string</em>, as the API sends it.</li>
 * </ul>
 *
 * <p>
 * Both are safe only because the engine no longer depends on those representations: the CT package
 * id is threaded from the request via {@code CtPackageRef}, and {@code CtCodelist.extensible()}
 * parses either wire form.
 * </p>
 *
 * <h2>Keys follow the CONSUMER, not the producer</h2>
 *
 * <p>
 * The Python populator fetches metadata endpoints bare ({@code GET /mdr/sdtmig/3-4}); CoreJ fetches
 * them expanded ({@code GET /mdr/sdtmig/3-4?expand=true}). Since the query string participates in
 * {@link ApiCache#toCacheKey(HttpRequest)}, those are two different cache entries. This seeder
 * exists to answer <em>CoreJ's</em> lookups, so it writes the key CoreJ derives — the pickle is
 * only the source of the body, never of the key. Getting this backwards leaves the seeded cache
 * inert for exactly the entries it exists to provide, and it looks like a cache-miss bug rather
 * than a keying bug.
 * </p>
 *
 * <p>
 * The key itself is never assembled by hand: {@code Run.cacheKeyFor} builds the request CoreJ would
 * issue and asks the target cache to derive the key, so normalisation, parameter sorting and
 * over-long-key shortening can never drift out of step. {@code SeederEngineKeyAgreementTest} pins
 * the agreement against the real {@code CdiscLibraryClient}.
 * </p>
 *
 * <h2>What is not seeded</h2>
 *
 * <p>
 * Rules endpoints ({@code /mdr/rules/...}) are not reconstructible — the Python populator stores
 * converted rule objects, not raw API JSON. {@code /mdr/products}, {@code /mdr/about} and friends
 * have no pickle source. CoreJ ships its own rules corpus, so neither matters here.
 * </p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>
 * The seeder itself holds <b>no</b> mutable state: every counter, list and derived setting lives in
 * a {@link Run} created by {@link #seed(SeedOptions)} and discarded when it returns. One instance
 * may therefore be reused sequentially or shared across threads. (It previously kept the run state
 * in instance fields cleared by a {@code reset()}, which made two concurrent {@code seed} calls
 * interleave their reports silently — a shape worth not leaving in place merely because every
 * current caller happens to be single-threaded.)
 * </p>
 *
 * <p>
 * That is a statement about the <em>seeder</em>, not about the cache directory. Two runs pointed at
 * the same {@link SeedOptions#targetCacheDir()} still race on the filesystem: writes are atomic per
 * entry ({@code FileApiCache} moves a temp file into place), but skip-existing decisions are read
 * before that write, so overlapping runs can each decide to write the same entry. Give concurrent
 * runs distinct target directories.
 * </p>
 */
public final class PickleCacheSeeder
{

    /** Keys the Python populator adds that no API response carries. */
    private static final Set<String> PYTHON_ONLY_KEYS = Set.of("dataset_names", "standard_type");

    /** CT package id → the prefix the API uses in the package {@code name}. */
    private static final Map<String, String> CT_NAME_PREFIXES = Map.ofEntries(
            Map.entry("sdtmct", "SDTM"), Map.entry("sendct", "SEND"), Map.entry("adamct", "ADaM"),
            Map.entry("cdashct", "CDASH"), Map.entry("coact", "COA"), Map.entry("ddfct", "DDF"),
            Map.entry("mrctct", "MRCT"), Map.entry("qrsct", "QRS"), Map.entry("qs-ftct", "QS-FT"),
            Map.entry("tmfct", "TMF"), Map.entry("define-xmlct", "Define-XML"),
            Map.entry("glossaryct", "Glossary"), Map.entry("protocolct", "Protocol"));

    /** {@code sdtmct-2024-09-27} → prefix {@code sdtmct}, date {@code 2024-09-27}. */
    private static final Pattern CT_ID = Pattern.compile("^([a-z0-9-]+ct)-(\\d{4}-\\d{2}-\\d{2})$");

    private static final String CT_PACKAGES_ENDPOINT = "/mdr/ct/packages";

    /**
     * The query {@code CdiscLibraryClient.expand(endpoint, true)} appends.
     *
     * <p>
     * Every runtime call site for a product-style seeded endpoint passes {@code true}:
     * {@code CdiscLibraryProviderBuilder:249} (SDTM-IG / SEND-IG), {@code :374} (the SDTM model),
     * {@code :300} (ADaM), {@code CoreLibraryAccessImpl:201} (a CT package) and
     * {@code CdiscLibraryBackedLibraryProvider:95}.
     * </p>
     */
    private static final String EXPAND_QUERY = "expand=true";

    /** The query for the endpoints {@code Run.queryFor} rules out of expansion. */
    private static final String NO_QUERY = "";

    /** Endpoint prefix of the integrated-standards family (TIG and friends). */
    private static final String INTEGRATED_PREFIX = "/mdr/integrated/";

    private static final Map<String, List<String>> SEEDED_HEADERS = Map.of("content-type",
            List.of("application/json; charset=utf-8"), "x-cache-source",
            List.of("seeded-from-pickle"));

    /**
     * Seeds the target cache from the configured pickle source.
     *
     * @param aOptions
     *            the run configuration.
     * @return what was written, skipped and warned about.
     * @throws IOException
     *             when the source cannot be materialised or the cache cannot be written.
     */
    public SeedReport seed(SeedOptions aOptions) throws IOException
    {
        return new Run(aOptions).execute();
    }


    /**
     * The cache-key prefix implied by a base URL — {@code https://api.library.cdisc.org/api/} →
     * {@code /api}. Mirrors {@code AbstractApiClient}, which strips the trailing slash before
     * appending the endpoint, so the resulting key matches what a live request would produce.
     *
     * @param aBaseUrl
     *            the base URL.
     * @return the path prefix, or {@code ""} when the base URL has no path.
     */
    static String basePathOf(String aBaseUrl)
    {
        String path;
        try
        {
            path = new URI(aBaseUrl).getPath();
        }
        catch (URISyntaxException _)
        {
            return "";
        }
        if (path == null || path.isBlank() || "/".equals(path))
        {
            return "";
        }
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    /**
     * The state of a single {@link #seed(SeedOptions)} call.
     *
     * <p>
     * Everything that used to be an instance field of the seeder lives here: the tallies, the
     * accumulating lists, the derived key prefix and the target cache. A {@code Run} is created,
     * used once and dropped, so no two runs can see each other's state and no {@code reset()} is
     * needed to keep them apart.
     * </p>
     */
    private static final class Run
    {

        private final SeedOptions options;

        private final String prefix;

        private final ApiCache target;

        private final ObjectMapper mapper = new ObjectMapper();

        private final List<String> written = new ArrayList<>();

        private final List<String> skipped = new ArrayList<>();

        private final List<String> warnings = new ArrayList<>();

        private int standards;

        private int models;

        private int ctPackages;

        private boolean ctIndex;

        Run(SeedOptions aOptions)
        {
            options = aOptions;
            prefix = basePathOf(aOptions.apiBaseUrl());
            target = new GzipFileApiCache(aOptions.targetCacheDir().toAbsolutePath(), ".json");
        }


        SeedReport execute() throws IOException
        {
            PickleCache cache = PickleCache.open(options.source().resolve());
            // Read after resolve(): a download source only knows its ref once it has fetched.
            String sourceRef = options.source().provenance().orElse(null);

            seedKeyed(cache, cache.standardKeys(), true);
            seedKeyed(cache, cache.modelKeys(), false);
            seedCtPackages(cache);

            return new SeedReport(written, skipped, warnings, standards, models, ctPackages,
                    ctIndex, sourceRef);
        }


        private void seedKeyed(PickleCache aCache, Set<String> aKeys, boolean aIsStandard)
            throws IOException
        {
            for (String key : aKeys)
            {
                Optional<Map<String, Object>> entry;
                try
                {
                    entry = aCache.get(key);
                }
                catch (RuntimeException e)
                {
                    // A truncated or corrupt pickle must not abort the other ~250 entries.
                    warnings.add(
                            key + ": unreadable pickle entry (" + e.getMessage() + ") — skipped");
                    continue;
                }
                if (entry.isEmpty())
                {
                    warnings.add(key + ": key enumerated but not resolvable — skipped");
                    continue;
                }
                Map<String, Object> pickle = entry.get();
                String endpoint = endpointFor(key, pickle);
                if (endpoint == null)
                {
                    warnings.add(key + ": no _links.self.href and no derivable endpoint — skipped");
                    continue;
                }
                if (!write(endpoint, toApiBody(pickle)))
                {
                    continue;
                }
                if (aIsStandard)
                {
                    standards++;
                }
                else
                {
                    models++;
                }
            }
        }


        /**
         * The endpoint a pickled document belongs at. {@code _links.self.href} is authoritative —
         * it is the only source that gets {@code models/adam/2-1} → {@code /mdr/adam/adam-2-1} and
         * {@code standards/tig/1-0/sdtm} → {@code /mdr/integrated/tig/1-0/sdtm} right.
         * Key-derivation is a last resort and is warned about by the caller.
         */
        private @Nullable String endpointFor(String aKey, Map<String, Object> aPickle)
        {
            Object links = aPickle.get("_links");
            if (links instanceof Map<?, ?> linkMap && linkMap.get("self") instanceof Map<?, ?> self
                    && self.get("href") instanceof String href && !href.isBlank())
            {
                if (href.startsWith("/"))
                {
                    return href;
                }
                // An absolute or relative href would concatenate into a key no live request ever
                // produces (e.g. "/api" + "https://…"), silently writing a dead entry while the
                // real
                // endpoint stays unseeded. Refuse it and fall through to key-derivation.
                warnings.add(aKey + ": _links.self.href is not an absolute path (" + href
                        + ") — falling back to key-derivation");
            }
            // Key-derivation is a last resort: it is WRONG for models/adam/2-1 (correct:
            // /mdr/adam/adam-2-1) and for standards/tig/1-0/<sub> (correct:
            // /mdr/integrated/tig/1-0/<sub>), so always warn when it is used.
            int slash = aKey.indexOf('/');
            if (slash < 0)
            {
                return null;
            }
            String derived = "/mdr/" + aKey.substring(slash + 1);
            warnings.add(aKey + ": no usable _links.self.href — endpoint derived from the key as "
                    + derived + ", which may be wrong (ADaM models and TIG substandards differ)");
            return derived;
        }


        /**
         * Strips the Python-only keys; everything else (including {@code _links}) passes through.
         */
        private ObjectNode toApiBody(Map<String, Object> aPickle)
        {
            ObjectNode node = mapper.valueToTree(aPickle);
            PYTHON_ONLY_KEYS.forEach(node::remove);
            return node;
        }


        private void seedCtPackages(PickleCache aCache) throws IOException
        {
            List<String> ids = new ArrayList<>(aCache.publishedCtPackages());
            ids.sort(String::compareTo);

            for (String id : ids)
            {
                Optional<Map<String, Object>> pkg;
                try
                {
                    pkg = aCache.getCtPackage(id);
                }
                catch (RuntimeException e)
                {
                    warnings.add(id + ": corrupt CT pickle (" + e.getMessage() + ") — skipped");
                    continue;
                }
                if (pkg.isEmpty())
                {
                    warnings.add(id + ": CT package file unreadable — skipped");
                    continue;
                }
                if (write(CT_PACKAGES_ENDPOINT + "/" + id, toCtBody(id, pkg.get())))
                {
                    ctPackages++;
                }
            }

            if (!ids.isEmpty() && write(CT_PACKAGES_ENDPOINT, ctIndexBody(ids)))
            {
                ctIndex = true;
            }
        }


        /**
         * Rebuilds the API's package envelope around the pickle's reprojected {@code codelists}.
         *
         * <p>
         * The pickle is not verbatim: {@code get_codelist_terms_map} keeps only {@code {package,
         * codelists}} and converts {@code extensible} to a real boolean. Fields it dropped that
         * cannot be recovered — {@code label} (embeds a package number), {@code description},
         * {@code source}, {@code registrationStatus}, {@code _links.priorVersion} — are <b>omitted
         * rather than invented</b>. Nothing in the engine reads them.
         * </p>
         */
        private ObjectNode toCtBody(String aId, Map<String, Object> aPickle)
        {
            ObjectNode body = mapper.createObjectNode();

            ObjectNode self = mapper.createObjectNode();
            self.put("href", CT_PACKAGES_ENDPOINT + "/" + aId);
            self.put("type", "Terminology");
            body.set("_links", mapper.createObjectNode().set("self", self));

            Matcher m = CT_ID.matcher(aId);
            if (m.matches())
            {
                String label = CT_NAME_PREFIXES.get(m.group(1));
                if (label == null)
                {
                    label = m.group(1).toUpperCase(Locale.ROOT);
                    warnings.add(aId + ": unknown CT prefix '" + m.group(1)
                            + "' — name synthesised from the raw prefix");
                }
                body.put("name", label + " CT " + m.group(2));
                body.put("version", m.group(2));
                body.put("effectiveDate", m.group(2));
            }
            else
            {
                warnings.add(aId + ": id does not match <prefix>ct-<yyyy-mm-dd> — "
                        + "name/version/effectiveDate omitted");
            }

            body.set("codelists", ctCodelists(aPickle.get("codelists")));
            return body;
        }


        /**
         * Copies the codelists through, restoring {@code extensible} to the API's string form so a
         * seeded entry is indistinguishable from a cached live response.
         */
        private ArrayNode ctCodelists(@Nullable Object aCodelists)
        {
            ArrayNode out = mapper.createArrayNode();
            if (!(aCodelists instanceof List<?> list))
            {
                return out;
            }
            for (Object element : list)
            {
                if (!(element instanceof Map<?, ?> codelist))
                {
                    continue;
                }
                ObjectNode node = mapper.valueToTree(codelist);
                JsonNode extensible = node.get("extensible");
                if (extensible != null && extensible.isBoolean())
                {
                    node.put("extensible", Boolean.toString(extensible.booleanValue()));
                }
                out.add(node);
            }
            return out;
        }


        /**
         * The {@code /mdr/ct/packages} index, in the shape {@code CtPackageList.packageLinks()}
         * reads. Without it {@code PUBLISHED_CT_PACKAGES} stays empty and
         * {@code valid_codelist_dates} misbehaves.
         */
        private ObjectNode ctIndexBody(List<String> aIds)
        {
            ArrayNode packages = mapper.createArrayNode();
            for (String id : aIds)
            {
                ObjectNode link = mapper.createObjectNode();
                link.put("href", CT_PACKAGES_ENDPOINT + "/" + id);
                link.put("title", id);
                link.put("type", "Terminology");
                packages.add(link);
            }
            ObjectNode links = mapper.createObjectNode();
            links.set("packages", packages);
            return (ObjectNode) mapper.createObjectNode().set("_links", links);
        }


        /**
         * The query string CoreJ's request for this endpoint carries.
         *
         * <p>
         * Two families are fetched <b>bare</b>, and both would otherwise be seeded under a key no
         * request can produce:
         * </p>
         * <ul>
         * <li>the CT package <em>index</em> — {@code CoreLibraryAccessImpl:163} calls
         * {@code getCtPackages()}, which has no expand overload;</li>
         * <li>everything under {@code /mdr/integrated/} — the four TIG substandards reach the cache
         * via {@code CdiscLibraryClient.getIntegratedVersion} / {@code getIntegratedSdtm} and
         * friends, and <b>none</b> of the integrated methods takes an {@code expand} flag.</li>
         * </ul>
         *
         * @param aEndpoint
         *            the endpoint path, e.g. {@code /mdr/sdtmig/3-4}.
         * @return the query string, or {@code ""} when the endpoint is fetched bare.
         */
        private static String queryFor(String aEndpoint)
        {
            if (CT_PACKAGES_ENDPOINT.equals(aEndpoint) || aEndpoint.startsWith(INTEGRATED_PREFIX))
            {
                return NO_QUERY;
            }
            return EXPAND_QUERY;
        }


        /**
         * The cache key the target cache would derive for the request CoreJ issues against this
         * endpoint.
         *
         * <p>
         * Deliberately delegated to {@link ApiCache#toCacheKey(HttpRequest)} rather than assembled
         * here: the cache owns normalisation, query-parameter sorting and the over-long-key digest,
         * and a second hand-written copy of that logic is precisely how the seeder and the engine
         * drifted apart in the first place.
         * </p>
         *
         * @return the cache key, or {@code null} when the endpoint cannot form a URI — in which
         *         case no live request could reach it either.
         */
        private @Nullable String cacheKeyFor(String aEndpoint)
        {
            String query = queryFor(aEndpoint);
            String requestPath = query.isEmpty() ? prefix + aEndpoint
                    : prefix + aEndpoint + "?" + query;
            URI uri;
            try
            {
                uri = new URI(requestPath);
            }
            catch (URISyntaxException _)
            {
                return null;
            }
            return target.toCacheKey(HttpRequest.get(uri).build());
        }


        /**
         * Writes one entry, honouring skip-existing and dry-run.
         *
         * @return {@code true} when the entry was written (or would be, on a dry run).
         */
        private boolean write(String aEndpoint, JsonNode aBody) throws IOException
        {
            String path = cacheKeyFor(aEndpoint);
            if (path == null)
            {
                warnings.add(prefix + aEndpoint + ": endpoint is not a usable URI — skipped");
                return false;
            }
            // cacheTimestamp is a plain existence check; ApiCache.read would decompress the whole
            // entry, and a corrupt .json.gz already in the target would then abort the run —
            // exactly
            // the case a repair run exists to fix.
            if (!options.overwriteExisting() && target.cacheTimestamp(path).isPresent())
            {
                skipped.add(path);
                return false;
            }
            if (options.dryRun())
            {
                written.add(path);
                return true;
            }
            String json = mapper.writeValueAsString(aBody);
            if (options.writeMeta())
            {
                target.writeEntry(path, new CacheEntry(200, SEEDED_HEADERS, json));
            }
            else
            {
                target.write(path, json);
            }
            // FileApiCache.write / writeMetaFile deliberately swallow IOException ("cache write
            // failures are non-fatal"), so a read-only or full target directory would otherwise
            // produce a report claiming every entry was seeded. Confirm the entry actually landed.
            if (target.cacheTimestamp(path).isEmpty())
            {
                warnings.add(path + ": write silently failed — is " + options.targetCacheDir()
                        + " writable, and is there free space?");
                return false;
            }
            written.add(path);
            return true;
        }
    }
}
