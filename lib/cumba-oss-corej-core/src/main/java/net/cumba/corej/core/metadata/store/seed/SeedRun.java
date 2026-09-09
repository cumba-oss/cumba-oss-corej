package net.cumba.corej.core.metadata.store.seed;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import net.cumba.corej.core.metadata.store.MetadataStore;
import net.cumba.corej.core.metadata.store.MetadataStoreWriter;
import net.cumba.corej.core.metadata.store.Presence;
import net.cumba.corej.core.metadata.store.StoreProvenance;
import net.cumba.corej.core.metadata.store.StoredCtPackage;
import net.cumba.corej.core.metadata.store.StoredProduct;
import org.jspecify.annotations.Nullable;

/**
 * The seed orchestration both seeders share — everything about a seed that is not source-specific:
 * the re-seed baseline, the incremental-acquisition/wholesale-rebuild split of plan §5.2, and the
 * atomic replace.
 *
 * <ul>
 * <li><b>Baseline:</b> an existing store at the target is opened as the acquisition cache (unless
 * {@code refresh}); one that fails to open is warned about and ignored, which degrades to a full
 * re-acquisition — the conservative direction.</li>
 * <li><b>CT packages</b> are immutable once published, so presence in the baseline is sufficient to
 * carry one forward without touching the source. The published enumeration and the held set are
 * each the UNION of baseline and source, so a source that has lost a package never shrinks the
 * store silently.</li>
 * <li><b>Products</b> can be revised upstream and are cheap (a few dozen documents), so the
 * source's offer is always re-acquired wholesale; baseline products whose key the source no longer
 * offers are carried, with a warning. There is deliberately no per-product freshness bookkeeping: a
 * fetch date in the manifest would break the byte-identity of plan §5.1, and the manifest's
 * per-part sha256 already identifies each product's content exactly.</li>
 * <li><b>Rebuild:</b> the writer rebuilds the whole file from the merged model and the target is
 * replaced via temp file + atomic move, so an interrupted seed never leaves a partial store and a
 * concurrent reader keeps its old file.</li>
 * </ul>
 */
final class SeedRun
{

    /** How a seeder obtains one CT package document from its source. */
    @FunctionalInterface
    interface CtFetcher
    {

        /**
         * Fetches one CT package document.
         *
         * @param aId
         *            the package id
         * @return the package document, or empty when the source does not have it
         * @throws IOException
         *             when the source fails delivering it — the run warns and continues
         */
        Optional<JsonNode> fetch(String aId) throws IOException;
    }

    private final StoreSeedOptions options;

    private final List<String> warnings = new ArrayList<>();

    private final List<String> missed = new ArrayList<>();

    private final StoreProjection projection = new StoreProjection();

    private final @Nullable MetadataStore existing;

    private int ctFetched;

    private int ctCarried;

    private int productsCarried;

    SeedRun(StoreSeedOptions aOptions)
    {
        options = aOptions;
        existing = openBaseline(aOptions);
    }


    List<String> warnings()
    {
        return warnings;
    }


    void warn(String aWarning)
    {
        warnings.add(aWarning);
    }


    /**
     * The published CT package enumeration for the manifest: the sorted union of what the source
     * offers and what the baseline already enumerated. First-class data (plan §4.3) — never derived
     * from which packages end up held.
     *
     * @param aSourceIds
     *            the ids the source enumerates (already grammar-checked by the seeder)
     * @return the sorted, distinct enumeration
     */
    List<String> publishedUnion(List<String> aSourceIds)
    {
        TreeSet<String> union = new TreeSet<>(aSourceIds);
        if (existing != null)
        {
            union.addAll(existing.publishedCtPackages());
        }
        return List.copyOf(union);
    }


    /**
     * Acquires the CT packages for the given enumeration: carried from the baseline when present
     * there (no source access), fetched otherwise. An id neither side can deliver is recorded as
     * missed — the store will enumerate it and answer ABSENT.
     *
     * @param aPublished
     *            the enumeration from {@link #publishedUnion(List)}
     * @param aFetcher
     *            the source access
     * @return the held packages by id
     */
    Map<String, StoredCtPackage> acquireCtPackages(List<String> aPublished, CtFetcher aFetcher)
    {
        Map<String, StoredCtPackage> held = new TreeMap<>();
        for (String id : aPublished)
        {
            if (existing != null && existing.presence(id) == Presence.PRESENT)
            {
                existing.ctPackage(id).ifPresent(pkg -> held.put(id, projection.intern(pkg)));
                ctCarried++;
                continue;
            }
            Optional<JsonNode> document;
            try
            {
                document = aFetcher.fetch(id);
            }
            catch (IOException | RuntimeException e)
            {
                warn(id + ": source failed delivering the CT package (" + e.getMessage()
                        + ") - the store will answer ABSENT for it");
                missed.add(id);
                continue;
            }
            if (document.isEmpty())
            {
                warn(id + ": published but not obtainable from this source"
                        + " - the store will answer ABSENT for it");
                missed.add(id);
                continue;
            }
            held.put(id, projection.ctPackage(id, document.get()));
            ctFetched++;
        }
        return held;
    }


    /**
     * Merges the source's freshly projected products with baseline products whose key the source no
     * longer offers (carried, with a warning — they cannot be refreshed from this source).
     *
     * @param aFromSource
     *            the products projected from the source this run, by key
     * @return the merged product set by key
     */
    Map<String, StoredProduct> mergeProducts(Map<String, StoredProduct> aFromSource)
    {
        Map<String, StoredProduct> merged = new TreeMap<>(aFromSource);
        if (existing == null)
        {
            return merged;
        }
        for (String key : storedProductKeys(existing))
        {
            if (!merged.containsKey(key))
            {
                existing.product(key).ifPresent(product ->
                {
                    merged.put(key, product);
                    productsCarried++;
                    warn(key + ": not offered by this source - carried forward unchanged"
                            + " from the existing store");
                });
            }
        }
        return merged;
    }


    /**
     * The product catalogue for the manifest: the sorted union of the source's catalogue and the
     * baseline's, mirroring {@link #mergeProducts(Map)}.
     *
     * @param aSourceCatalogue
     *            the declarable product keys the source derives
     * @return the sorted, distinct catalogue
     */
    List<String> catalogueUnion(List<String> aSourceCatalogue)
    {
        TreeSet<String> union = new TreeSet<>(aSourceCatalogue);
        if (existing != null)
        {
            union.addAll(existing.productCatalogue());
        }
        return List.copyOf(union);
    }


    /**
     * The manifest provenance: the options' override verbatim when set; otherwise the baseline's
     * lines with the line for this seeder's source replaced (or appended), so provenance stays one
     * line per source rather than growing per run.
     *
     * @param aOwn
     *            this run's provenance line
     * @return the provenance to record
     */
    List<StoreProvenance> provenance(StoreProvenance aOwn)
    {
        List<StoreProvenance> override = options.provenanceOverride();
        if (override != null)
        {
            return override;
        }
        List<StoreProvenance> merged = new ArrayList<>();
        boolean replaced = false;
        if (existing != null)
        {
            for (StoreProvenance line : existing.manifest().provenance())
            {
                if (line.source().equals(aOwn.source()))
                {
                    merged.add(aOwn);
                    replaced = true;
                }
                else
                {
                    merged.add(line);
                }
            }
        }
        if (!replaced)
        {
            merged.add(aOwn);
        }
        return merged;
    }


    /**
     * Rebuilds the store file from the writer and replaces the target atomically (temp file in the
     * target's directory, then move), closing the baseline first.
     *
     * @param aWriter
     *            the fully populated writer
     * @return the written store's size in bytes
     * @throws IOException
     *             when writing or replacing fails; the previous store, if any, is untouched
     */
    long writeAtomically(MetadataStoreWriter aWriter) throws IOException
    {
        close();
        Path target = options.target().toAbsolutePath();
        Path parent = target.getParent();
        if (parent == null)
        {
            throw new IOException("store target has no parent directory: " + target);
        }
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, target.getFileName().toString(), ".seeding");
        try
        {
            aWriter.write(temp);
            long size = Files.size(temp);
            try
            {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            catch (AtomicMoveNotSupportedException _)
            {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return size;
        }
        finally
        {
            Files.deleteIfExists(temp);
        }
    }


    /** Releases the baseline store, if one was opened. */
    void close()
    {
        if (existing != null)
        {
            existing.close();
        }
    }


    StoreSeedReport report(int aProductsSeeded, @Nullable String aSourceRef, long aStoreBytes)
    {
        return new StoreSeedReport(aProductsSeeded, productsCarried, ctFetched, ctCarried, missed,
                warnings, aSourceRef, options.target(), aStoreBytes);
    }


    /**
     * Opens the re-seed baseline: the existing store at the target, unless {@code refresh} asked to
     * ignore it. A file that exists but fails to open is warned about and treated as absent —
     * everything is then re-acquired from the source, and the atomic replace overwrites the
     * unreadable file.
     */
    private @Nullable MetadataStore openBaseline(StoreSeedOptions aOptions)
    {
        if (aOptions.refresh() || !Files.isRegularFile(aOptions.target()))
        {
            return null;
        }
        try
        {
            return MetadataStore.open(aOptions.target());
        }
        catch (IOException e)
        {
            warn(aOptions.target() + ": existing store is unreadable (" + e.getMessage()
                    + ") - re-acquiring everything from the source");
            return null;
        }
    }


    /** The product keys the baseline holds, recovered from its manifest's per-part hashes. */
    private static List<String> storedProductKeys(MetadataStore aStore)
    {
        List<String> keys = new ArrayList<>();
        for (String entry : aStore.manifest().partHashes().keySet())
        {
            if (entry.startsWith("products/") && entry.endsWith(".json"))
            {
                keys.add(entry.substring("products/".length(), entry.length() - ".json".length()));
            }
        }
        return keys;
    }
}
