package net.cumba.corej.core.metadata.store.seed;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import net.cumba.cdisc.library.api.client.CdiscLibraryClient;
import net.cumba.corej.core.CoreLibraryAccess;
import net.cumba.corej.core.CoreLibraryAccessImpl;
import net.cumba.corej.core.metadata.store.MetadataStoreWriter;
import net.cumba.corej.core.metadata.store.StoreProvenance;
import net.cumba.corej.core.metadata.store.StoredCtPackage;
import net.cumba.corej.core.metadata.store.StoredProduct;
import org.jspecify.annotations.Nullable;

/**
 * Seeds the unified metadata store (plan §5.1) from the CDISC Library API, walking the five
 * reachable families of plan §3.1: the product catalogue ({@code /mdr/products}), the IG products
 * it lists, the foundational models those IGs link ({@code _links.model.href}), the published CT
 * package enumeration ({@code /mdr/ct/packages}) and every CT package in it.
 *
 * <p>
 * This is where {@link CoreLibraryAccess} legitimately survives (plan §6): seeding is the one
 * operation for which a CDISC Library API key genuinely belongs, and after P4 nothing on the run
 * path reaches the network. Everything is fetched as RAW JSON and projected through
 * {@link StoreProjection} — the same projection {@link PickleStoreSeeder} uses — never through the
 * typed {@code api.model} views, which carry only the fields today's engine reads and would
 * silently narrow the store (audit §1: the variable field set must stay the full published union).
 * </p>
 *
 * <p>
 * Endpoints are requested exactly as the engine requests them ({@code ?expand=true} on products,
 * models and CT packages; bare on the two enumerations and on {@code /mdr/integrated/…}), so a
 * pre-populated {@code GzipFileApiCache} — including one written by {@code PickleCacheSeeder} —
 * answers every request without a network round trip. That is also how the conformance tests
 * exercise this seeder without an API key.
 * </p>
 *
 * <p>
 * Re-seeding follows plan §5.2: CT packages present in the existing store are carried forward
 * without an API call (they are immutable once published), so a routine re-seed costs ~50 product
 * requests plus one request per genuinely new package, not the ~860 of a full walk; products are
 * always re-fetched, which is what picks up an upstream revision. {@code refresh} re-fetches
 * everything.
 * </p>
 */
public final class WebApiStoreSeeder
{

    /** Provenance source name for this seeder's manifest line. */
    public static final String PROVENANCE_SOURCE = "web-api";

    /** The store's CT package id grammar; enumerated ids that fail it are warned and excluded. */
    private static final Pattern CT_PACKAGE_ID = Pattern
            .compile("[a-z][a-z0-9-]*ct-\\d{4}-\\d{2}-\\d{2}");

    /** The product-list entry type marking a declarable product ({@code /mdr/products}). */
    private static final String IMPLEMENTATION_GUIDE = "Implementation Guide";

    private static final String MDR_PREFIX = "/mdr/";

    private static final String INTEGRATED_PREFIX = "/mdr/integrated/";

    private static final String CT_PACKAGES_ENDPOINT = "/mdr/ct/packages";

    /**
     * The query the engine's own expanded requests carry. Kept identical to
     * {@code CdiscLibraryClient.expand} so the cache keys this seeder produces are the ones a
     * pre-populated cache already holds.
     */
    private static final String EXPAND_QUERY = "?expand=true";

    private final CdiscLibraryClient client;

    /**
     * Creates a seeder over the given library access.
     *
     * @param aAccess
     *            the access to seed through — from {@link CoreLibraryAccess#openIfConfigured()} for
     *            a live seed, or {@code CoreLibraryAccess.open(…)} pointed at a recorded cache for
     *            an offline one
     * @throws IllegalArgumentException
     *             when the access is not the default implementation (a mock of the interface cannot
     *             serve raw JSON)
     */
    public WebApiStoreSeeder(CoreLibraryAccess aAccess)
    {
        if (!(aAccess instanceof CoreLibraryAccessImpl impl))
        {
            throw new IllegalArgumentException(
                    "WebApiStoreSeeder needs the default CoreLibraryAccess implementation, not "
                            + aAccess.getClass().getName());
        }
        client = impl.client();
    }


    /**
     * Seeds the target store from the CDISC Library API (or its recorded cache).
     *
     * @param aOptions
     *            the run configuration
     * @return what was fetched, carried, missed and warned about
     * @throws IOException
     *             when either enumeration ({@code /mdr/products}, {@code /mdr/ct/packages}) cannot
     *             be fetched — without them the walk has nothing trustworthy to offer, and a seed
     *             against a dead or keyless API must fail loudly rather than write a hollow store.
     *             Individual product or package failures are warnings instead. An existing target
     *             is left untouched on failure.
     */
    public StoreSeedReport seed(StoreSeedOptions aOptions) throws IOException
    {
        SeedRun run = new SeedRun(aOptions);
        try
        {
            List<String> igHrefs = igProductHrefs();
            List<String> sourceIds = enumerateCtPackages(run);
            List<String> published = run.publishedUnion(sourceIds);
            Map<String, StoredCtPackage> held = run.acquireCtPackages(published, id -> Optional
                    .of(client.getRawJson(CT_PACKAGES_ENDPOINT + "/" + id + EXPAND_QUERY)));

            Map<String, StoredProduct> fromSource = projectProducts(igHrefs, run);
            Map<String, StoredProduct> products = run.mergeProducts(fromSource);
            List<String> catalogue = run.catalogueUnion(
                    igHrefs.stream().map(WebApiStoreSeeder::productKeyFor).sorted().toList());

            MetadataStoreWriter writer = new MetadataStoreWriter();
            products.values().forEach(writer::addProduct);
            held.values().forEach(writer::addCtPackage);
            writer.publishedCtPackages(published).productCatalogue(catalogue);
            run.provenance(
                    new StoreProvenance(PROVENANCE_SOURCE, client.baseUrl(), aOptions.fetchedAt()))
                    .forEach(writer::addProvenance);

            long size = run.writeAtomically(writer);
            return run.report(fromSource.size(), client.baseUrl(), size);
        }
        finally
        {
            run.close();
        }
    }


    /**
     * The Implementation-Guide-typed hrefs of {@code /mdr/products}, sorted — TIG's
     * {@code /mdr/integrated/…} entries included, since unlike the run-path catalogue (a pre-store
     * workaround) this seeder can and does load them.
     */
    private List<String> igProductHrefs() throws IOException
    {
        JsonNode products = client.getRawJson("/mdr/products");
        TreeSet<String> hrefs = new TreeSet<>();
        collectIgHrefs(products, hrefs);
        return List.copyOf(hrefs);
    }


    private static void collectIgHrefs(JsonNode aNode, Set<String> aHrefs)
    {
        if (aNode.isObject())
        {
            JsonNode type = aNode.get("type");
            JsonNode href = aNode.get("href");
            if (type != null && IMPLEMENTATION_GUIDE.equals(type.textValue()) && href != null
                    && href.isTextual())
            {
                aHrefs.add(href.textValue());
            }
        }
        for (JsonNode child : aNode)
        {
            collectIgHrefs(child, aHrefs);
        }
    }


    /** The published CT package ids from {@code /mdr/ct/packages}, grammar-checked and sorted. */
    private List<String> enumerateCtPackages(SeedRun aRun) throws IOException
    {
        JsonNode index = client.getRawJson(CT_PACKAGES_ENDPOINT);
        TreeSet<String> ids = new TreeSet<>();
        for (JsonNode link : index.path("_links").path("packages"))
        {
            String href = link.path("href").asText("");
            String id = href.substring(href.lastIndexOf('/') + 1);
            if (CT_PACKAGE_ID.matcher(id).matches())
            {
                ids.add(id);
            }
            else
            {
                aRun.warn(href + ": not a valid CT package link - excluded from the store"
                        + " and its published enumeration");
            }
        }
        return List.copyOf(ids);
    }


    /**
     * Fetches and projects the IG products, then the distinct foundational models their
     * {@code _links.model.href} entries name. A product that fails to fetch or projects empty is
     * warned about; the models an unfetchable IG would have named are simply not discovered — the
     * warning makes that visible.
     */
    private Map<String, StoredProduct> projectProducts(List<String> aIgHrefs, SeedRun aRun)
    {
        StoreProjection projection = new StoreProjection();
        Map<String, StoredProduct> products = new TreeMap<>();
        TreeSet<String> modelHrefs = new TreeSet<>();
        for (String href : aIgHrefs)
        {
            StoredProduct product = fetchProduct(href, productKeyFor(href), projection, aRun);
            if (product != null)
            {
                products.put(product.key(), product);
                if (product.modelHref() != null)
                {
                    modelHrefs.add(product.modelHref());
                }
            }
        }
        for (String href : modelHrefs)
        {
            String key = modelKeyFor(href);
            if (key == null)
            {
                aRun.warn(href + ": model link does not look like /mdr/<standard>/<version>"
                        + " - skipped");
                continue;
            }
            StoredProduct model = fetchProduct(href, key, projection, aRun);
            if (model != null)
            {
                products.put(model.key(), model);
            }
        }
        return products;
    }


    private @Nullable StoredProduct fetchProduct(String aHref, String aKey,
            StoreProjection aProjection, SeedRun aRun)
    {
        JsonNode document;
        try
        {
            document = client.getRawJson(aHref + queryFor(aHref));
        }
        catch (IOException | RuntimeException e)
        {
            aRun.warn(aKey + ": fetching " + aHref + " failed (" + e.getMessage() + ") - skipped");
            return null;
        }
        StoredProduct product = aProjection.product(aKey, document);
        if (product.classes().isEmpty() && product.datasets().isEmpty()
                && product.dataStructures().isEmpty())
        {
            aRun.warn(aKey + ": " + aHref + " projected with no classes, datasets or data"
                    + " structures - the source may not have served the expanded document");
        }
        return product;
    }


    /**
     * The query an endpoint is fetched with. {@code /mdr/integrated/…} is fetched BARE — the
     * client's integrated methods carry no expand flag, and the recorded caches hold those
     * documents (fully populated) under the bare key. ⚠ Whether the LIVE API expands a bare
     * integrated request the same way is unverified here; the empty-projection warning in
     * {@link #fetchProduct} is what would surface it.
     */
    private static String queryFor(String aHref)
    {
        return aHref.startsWith(INTEGRATED_PREFIX) ? "" : EXPAND_QUERY;
    }


    /**
     * The store key for an IG product href: {@code /mdr/<group>/<product>} →
     * {@code standards/<group>/<product>}, with {@code /mdr/integrated/tig/1-0/<sub>} →
     * {@code standards/tig/1-0/<sub>} — exactly the keys the Python populator files the same
     * documents under, so the two seeders' stores key identically.
     */
    private static String productKeyFor(String aHref)
    {
        String rest = aHref.startsWith(INTEGRATED_PREFIX)
                ? aHref.substring(INTEGRATED_PREFIX.length())
                : aHref.substring(MDR_PREFIX.length());
        return "standards/" + rest;
    }


    /**
     * The store key for a model href: {@code /mdr/sdtm/2-0} → {@code models/sdtm/2-0}, and the one
     * irregular family {@code /mdr/adam/adam-2-1} → {@code models/adam/2-1} (the version segment
     * repeats the group and the pickle keys strip it). {@code null} when the href is not the
     * two-segment shape every foundational model uses.
     */
    private static @Nullable String modelKeyFor(String aHref)
    {
        if (!aHref.startsWith(MDR_PREFIX))
        {
            return null;
        }
        // ⚠ split(…, -1), not split(…): the one-argument form DROPS trailing empty fields, so
        // "/mdr/sdtm/2-0/" split to exactly ["sdtm", "2-0"] and was accepted as the two-segment
        // shape this method documents. The limit keeps the trailing empty, the length check then
        // rejects it, and a genuine "/mdr/sdtm/2-0" is unaffected. (Error Prone [StringSplitter].)
        String[] segments = aHref.substring(MDR_PREFIX.length()).split("/", -1);
        if (segments.length != 2 || segments[0].isEmpty() || segments[1].isEmpty())
        {
            return null;
        }
        String version = segments[1].startsWith(segments[0] + "-")
                ? segments[1].substring(segments[0].length() + 1)
                : segments[1];
        return "models/" + segments[0] + "/" + version;
    }
}
