package net.cumba.corej.core.metadata.store.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import net.cumba.corej.core.metadata.pickle.HttpArchivePickleSource;
import net.cumba.corej.core.metadata.pickle.LocalPickleSource;
import net.cumba.corej.core.metadata.pickle.PickleCache;
import net.cumba.corej.core.metadata.pickle.PickleSource;
import net.cumba.corej.core.metadata.store.MetadataStoreWriter;
import net.cumba.corej.core.metadata.store.StoreProvenance;
import net.cumba.corej.core.metadata.store.StoredCtPackage;
import net.cumba.corej.core.metadata.store.StoredProduct;

/**
 * Seeds the unified metadata store (plan §5.1) from the Python engine's pickle cache — a local
 * {@code resources/cache} directory ({@link LocalPickleSource}) or the pinned upstream archive
 * ({@link HttpArchivePickleSource}).
 *
 * <p>
 * This is the store-writing successor to {@code PickleCacheSeeder} (which converts pickles into
 * web-api cache entries and is deleted in P4). Unlike that seeder it owes the wire format nothing:
 * the pickles are projected straight onto the store's canonical model through
 * {@link StoreProjection}, the same projection {@link WebApiStoreSeeder} uses — which is what the
 * byte-identity conformance test of plan §5.1 pins.
 * </p>
 *
 * <p>
 * Re-seeding follows plan §5.2: an existing store at the target is the baseline, CT packages
 * already present are carried forward without re-reading their (potentially large, or
 * re-downloaded) pickles, the two standards files are always re-projected (they are single files
 * the source materialises anyway), and the target is rebuilt wholesale and replaced atomically.
 * </p>
 */
public final class PickleStoreSeeder
{

    /** Provenance source name for this seeder's manifest line. */
    public static final String PROVENANCE_SOURCE = "pickle";

    /**
     * The store's CT package id grammar, re-checked here so a stray {@code *ct-*} file in the
     * source directory is warned about and excluded instead of aborting the writer.
     */
    private static final Pattern CT_PACKAGE_ID = Pattern
            .compile("[a-z][a-z0-9-]*ct-\\d{4}-\\d{2}-\\d{2}");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PickleSource source;

    /**
     * Creates a seeder over the given pickle source. The source is materialised per
     * {@link #seed(StoreSeedOptions)} call and belongs to the caller — it is not closed here.
     *
     * @param aSource
     *            supplies the {@code *.pkl} directory
     */
    public PickleStoreSeeder(PickleSource aSource)
    {
        source = aSource;
    }


    /**
     * Seeds the target store from the pickle source.
     *
     * @param aOptions
     *            the run configuration
     * @return what was fetched, carried, missed and warned about
     * @throws IOException
     *             when the source cannot be materialised or the store cannot be written; an
     *             existing target is left untouched on failure
     */
    public StoreSeedReport seed(StoreSeedOptions aOptions) throws IOException
    {
        SeedRun run = new SeedRun(aOptions);
        try
        {
            Path dir = source.resolve();
            String sourceRef = source.provenance().orElse(null);
            PickleCache cache = PickleCache.open(dir);

            List<String> sourceIds = enumerateCtPackages(cache, run);
            List<String> published = run.publishedUnion(sourceIds);
            Map<String, StoredCtPackage> held = run.acquireCtPackages(published,
                    id -> fetchCtPackage(dir, id));

            Map<String, StoredProduct> fromSource = projectProducts(cache, run);
            Map<String, StoredProduct> products = run.mergeProducts(fromSource);
            List<String> catalogue = run
                    .catalogueUnion(List.copyOf(new TreeSet<>(cache.standardKeys())));

            MetadataStoreWriter writer = new MetadataStoreWriter();
            products.values().forEach(writer::addProduct);
            held.values().forEach(writer::addCtPackage);
            writer.publishedCtPackages(published).productCatalogue(catalogue);
            run.provenance(new StoreProvenance(PROVENANCE_SOURCE,
                    sourceRef != null ? sourceRef : dir.toString(), aOptions.fetchedAt()))
                    .forEach(writer::addProvenance);

            long size = run.writeAtomically(writer);
            return run.report(fromSource.size(), sourceRef, size);
        }
        finally
        {
            run.close();
        }
    }


    /**
     * The source's CT package enumeration: the directory listing ({@code *ct-*} stems), with any
     * stem the store's id grammar rejects warned about and excluded — the writer would refuse it,
     * and enumerating an id the store could never hold poisons the published list.
     */
    private static List<String> enumerateCtPackages(PickleCache aCache, SeedRun aRun)
    {
        List<String> ids = new ArrayList<>();
        for (String id : aCache.publishedCtPackages())
        {
            if (CT_PACKAGE_ID.matcher(id).matches())
            {
                ids.add(id);
            }
            else
            {
                aRun.warn(id + ": not a valid CT package id - excluded from the store"
                        + " and its published enumeration");
            }
        }
        return ids;
    }


    /**
     * Reads and converts one CT package pickle. Deliberately through a FRESH {@link PickleCache}:
     * the shared instance memoises every file it decodes, and holding all 206 decoded packages
     * (~420 MB of pickles) in one map for the duration of a seed would dwarf the store being built.
     * A throwaway instance lets each package be collected once projected.
     */
    private static Optional<JsonNode> fetchCtPackage(Path aDir, String aId)
    {
        return PickleCache.open(aDir).getCtPackage(aId).map(MAPPER::valueToTree);
    }


    /** Projects the two standards files (IG products and foundational models). */
    private static Map<String, StoredProduct> projectProducts(PickleCache aCache, SeedRun aRun)
    {
        StoreProjection projection = new StoreProjection();
        Map<String, StoredProduct> products = new TreeMap<>();
        for (String key : aCache.standardKeys())
        {
            projectProduct(aCache, key, projection, products, aRun);
        }
        for (String key : aCache.modelKeys())
        {
            projectProduct(aCache, key, projection, products, aRun);
        }
        return products;
    }


    private static void projectProduct(PickleCache aCache, String aKey, StoreProjection aProjection,
            Map<String, StoredProduct> aProducts, SeedRun aRun)
    {
        Optional<Map<String, Object>> entry;
        try
        {
            entry = aCache.get(aKey);
        }
        catch (RuntimeException e)
        {
            aRun.warn(aKey + ": unreadable pickle entry (" + e.getMessage() + ") - skipped");
            return;
        }
        if (entry.isEmpty())
        {
            aRun.warn(aKey + ": key enumerated but not resolvable - skipped");
            return;
        }
        aProducts.put(aKey, aProjection.product(aKey, MAPPER.valueToTree(entry.get())));
    }
}
