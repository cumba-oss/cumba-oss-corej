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
import java.util.Set;
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
 * <li><b>Errata and normalisation (format 5, PLAN-store-library-content-errata):</b> the products
 * projected from THIS run's source are text-normalised by the projection and then corrected by the
 * {@link StoreErrata errata register} (the entries scoped to this run's source) before the merge.
 * Stale entries are REPORTED, not applied and not warned about (D4: a production seed reads the
 * source's default branch, which may be ahead of the register, and must not fail for it — the
 * pinned real-corpus test is what fails on one); labels still ending in a footnote marker are
 * reported and warned about ({@link StoreSeedReport}).</li>
 * <li><b>Register digest:</b> the register's sha256 is recorded as a provenance line
 * ({@link StoreErrata#PROVENANCE_SOURCE}). A baseline recorded under a different digest — or none —
 * holds products corrected by another register, so its products are NOT carried (as for an older
 * format); its CT packages, which the register never touches, still are.</li>
 * <li><b>Seed supplements (PLAN-seed-sendig-dart-1-2, owner approval E14):</b> the products of the
 * {@link StoreSupplements} register are projected through the same projection and added to this
 * run's source products BEFORE the errata are applied, unless the source projects the key itself
 * (D3 (i)) or the carried baseline holds a copy its {@value StoreSupplements#PROVENANCE_SOURCE}
 * provenance line does not list as supplemented — a source-served product (D3 (ii), F10) — that the
 * carry really carries (a copy corrected under another register or corrupt is replaced, and the
 * replacement reported). A skip is reported, not warned about; the supplements' digest and applied
 * keys are always recorded as a provenance line (F7), before the register digest line.
 * {@link StoreSeedReport#productsSeeded()} keeps counting source products only (D9).</li>
 * <li><b>Rebuild:</b> the writer rebuilds the whole file from the merged model and the target is
 * replaced via temp file + atomic move, so an interrupted seed never leaves a partial store and a
 * concurrent reader keeps its old file.</li>
 * </ul>
 */
@lombok.CustomLog
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

    private static final String MODELS_PREFIX = "models/";

    private final StoreSeedOptions options;

    private final List<String> warnings = new ArrayList<>();

    private final List<String> missed = new ArrayList<>();

    private final StoreProjection projection = new StoreProjection();

    private final StoreErrata errata = StoreErrata.load();

    private final StoreSupplements supplements;

    private final String errataSource;

    private final @Nullable MetadataStore existing;

    /**
     * Whether the baseline's products were corrected under this build's register (its provenance
     * records the same digest); when not, none of them is carried.
     */
    private final boolean baselineProductsCurrent;

    private StoreErrata.@Nullable Outcome errataOutcome;

    private List<String> footnoteLabels = List.of();

    private final List<String> supplemented = new ArrayList<>();

    private final List<String> supplementSkipped = new ArrayList<>();

    private final List<String> supplementReplaced = new ArrayList<>();

    /**
     * Set by {@link #mergeProducts(Map)}; {@link #catalogueUnion(List)} reads its outcome (F12).
     */
    private boolean productsMerged;

    private int ctFetched;

    private int ctCarried;

    private int productsCarried;

    /**
     * Opens a run.
     *
     * @param aOptions
     *            the run configuration
     * @param aErrataSource
     *            the seed source the errata register is scoped by
     *            ({@link StoreErrata#SOURCE_PICKLE} or {@link StoreErrata#SOURCE_WEBAPI})
     * @throws IOException
     *             when the target holds a store of a newer format and {@code refresh} is off
     */
    SeedRun(StoreSeedOptions aOptions, String aErrataSource) throws IOException
    {
        this(aOptions, aErrataSource, StoreSupplements.load());
    }


    /**
     * Opens a run with the given supplements register (tests).
     *
     * @param aOptions
     *            the run configuration
     * @param aErrataSource
     *            the seed source the errata register is scoped by
     * @param aSupplements
     *            the seed supplements to apply
     * @throws IOException
     *             when the target holds a store of a newer format and {@code refresh} is off
     */
    SeedRun(StoreSeedOptions aOptions, String aErrataSource, StoreSupplements aSupplements)
        throws IOException
    {
        options = aOptions;
        errataSource = aErrataSource;
        supplements = aSupplements;
        existing = openBaseline(aOptions);
        baselineProductsCurrent = existing != null && errataDigestCurrent(existing);
    }


    void warn(String aWarning)
    {
        warnings.add(aWarning);
    }


    /**
     * The ONE projection for this run's products and CT packages — the seeders project through it
     * so its text-normalisation sites and counters reach the errata application and the report.
     *
     * @return the run's projection
     */
    StoreProjection projection()
    {
        return projection;
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
     * Applies the errata register (PLAN-store-library-content-errata D1/D4) to the products
     * projected from the source THIS run — never to carried baseline products, which were corrected
     * under the same register when they were seeded (see {@link #errataDigestCurrent}) — then scans
     * every label and prompt for a footnote marker (owner rule 2026-10-03). A stale entry is listed
     * in the report (its summary names it) but is NOT a warning: a seed from a source that is ahead
     * of the register must not fail on the CLI's exit code. A footnote hit is a warning.
     */
    private Map<String, StoredProduct> applyErrata(Map<String, StoredProduct> aFromSource)
    {
        StoreErrata.Outcome outcome = errata.apply(aFromSource, projection.normalisedSites(),
                errataSource);
        errataOutcome = outcome;
        for (String stale : outcome.stale())
        {
            LOGGER.log(System.Logger.Level.INFO,
                    "errata entry stale, reported and NOT applied: {0}", stale);
        }
        footnoteLabels = LabelFootnoteScan.scan(outcome.products());
        for (String hit : footnoteLabels)
        {
            warn("label or prompt ends in a footnote marker (owner rule 2026-10-03): " + hit);
        }
        return outcome.products();
    }


    /**
     * Merges the source's freshly projected products, plus the seed supplements this run applies
     * (D3), with baseline products whose key neither offers (carried, with a warning — they cannot
     * be refreshed from this source). Must precede {@link #catalogueUnion(List)} (F12).
     *
     * @param aFromSource
     *            the products projected from the source this run, by key
     * @return the merged product set by key
     */
    Map<String, StoredProduct> mergeProducts(Map<String, StoredProduct> aFromSource)
    {
        Map<String, StoredProduct> merged = new TreeMap<>(applyErrata(supplement(aFromSource)));
        carryBaseline(merged);
        warnOnMissingSupplementModels(aFromSource, merged);
        productsMerged = true;
        return merged;
    }


    /**
     * D3: this run's source products plus every supplement that is neither served by the source (i)
     * nor held by the baseline as a source-served copy the carry really carries (ii), each
     * projected through the run's {@link #projection()} — so the errata register and the footnote
     * scan, which run on the returned map next, apply to it as to a source product. The caller's
     * map is not modified.
     */
    private Map<String, StoredProduct> supplement(Map<String, StoredProduct> aFromSource)
    {
        Map<String, StoredProduct> out = new TreeMap<>(aFromSource);
        Set<String> baselineSupplemented = baselineSupplementedKeys();
        List<String> baselineKeys = existing == null ? List.of() : storedProductKeys(existing);
        for (StoreSupplements.Supplement entry : supplements.supplements())
        {
            String key = entry.key();
            if (aFromSource.containsKey(key))
            {
                supplementSkipped.add(key + ": the source now serves it - the " + entry.approval()
                        + " seed supplement was IGNORED; retire it");
                continue;
            }
            if (baselineKeys.contains(key) && !baselineSupplemented.contains(key))
            {
                String notCarried = uncarriedSourceServedCopy(key, entry.approval());
                if (notCarried == null)
                {
                    // F10: a real source product carried after a source dropped it (or failed to
                    // deliver it) is never replaced by the extract.
                    supplementSkipped.add(key + ": the existing store holds a source-served copy,"
                            + " carried forward - the " + entry.approval()
                            + " seed supplement was IGNORED; retire it");
                    continue;
                }
                supplementReplaced.add(notCarried);
            }
            out.put(key, projection.product(key, entry.product()));
            supplemented.add(key);
        }
        return out;
    }


    /**
     * D3 (ii) decided on what {@link #carryBaseline(Map)} really carries (review L2): {@code null}
     * when the baseline's source-served copy of {@code aKey} will be carried; otherwise why not —
     * the baseline was corrected under another errata register, so none of its products is carried
     * (F3; the F10 deviation, review L4 b), or the copy is corrupt (D-27: it fails on first access,
     * which is a warning as in the carry) — and the supplement then replaces it, reported.
     */
    private @Nullable String uncarriedSourceServedCopy(String aKey, String aApproval)
    {
        String replaces = " - the " + aApproval + " seed supplement replaces it";
        if (!baselineProductsCurrent || existing == null)
        {
            return aKey + ": its source-served copy was corrected under another errata register"
                    + " and is not carried" + replaces;
        }
        try
        {
            if (existing.product(aKey).isPresent())
            {
                return null;
            }
            return aKey + ": its source-served copy is unreadable" + replaces;
        }
        catch (java.io.UncheckedIOException e)
        {
            warn(aKey + ": the existing store's copy is corrupt (" + e.getMessage()
                    + ") - replaced by the " + aApproval + " seed supplement");
            return aKey + ": its source-served copy is corrupt" + replaces;
        }
    }


    /**
     * D7 / F11, narrowed by D10 and D13 (owner 2026-10-10): an applied supplement whose model is
     * absent from the merged products would answer nothing on the model walk — a warning, but only
     * when the source offers models AND serves an IG of the supplement's own family (its key up to
     * the last {@code /}: {@code standards/sendig/} for SENDIG-DART 1.2, the one case where it
     * needs {@code models/sdtm/1-6}). A source without any model is a partial source by design (a
     * one-IG cache); a source without an IG of that family is not that family's seed (an SDTM-only
     * cache, the hermetic fixtures, for a SEND supplement). The family is derived per key, not
     * fixed, so a supplement of another family is gated by its own (confirmation look, optional).
     */
    private void warnOnMissingSupplementModels(Map<String, StoredProduct> aFromSource,
            Map<String, StoredProduct> aMerged)
    {
        boolean sourceOffersModels = aFromSource.keySet().stream()
                .anyMatch(key -> key.startsWith(MODELS_PREFIX));
        if (!sourceOffersModels)
        {
            return;
        }
        for (String key : supplemented)
        {
            String family = key.substring(0, key.lastIndexOf('/') + 1);
            if (aFromSource.keySet().stream().noneMatch(served -> served.startsWith(family)))
            {
                continue;
            }
            StoredProduct product = aMerged.get(key);
            String href = product == null ? null : product.modelHref();
            String modelKey = href == null ? null : WebApiStoreSeeder.modelKeyFor(href);
            if (modelKey == null || !aMerged.containsKey(modelKey))
            {
                warn(key + ": the seed supplement links the model " + href
                        + ", which this seed does not hold - its model walk answers nothing");
            }
        }
    }


    /** The keys the baseline's supplement provenance line lists as applied, under any digest. */
    private Set<String> baselineSupplementedKeys()
    {
        if (existing == null)
        {
            return Set.of();
        }
        return existing.manifest().provenance().stream()
                .filter(line -> StoreSupplements.PROVENANCE_SOURCE.equals(line.source()))
                .findFirst().map(line -> StoreSupplements.appliedKeys(line.ref())).orElse(Set.of());
    }


    /**
     * Adds the baseline products whose key {@code aMerged} lacks, when the baseline's products were
     * corrected under this build's register.
     */
    private void carryBaseline(Map<String, StoredProduct> aMerged)
    {
        if (existing == null)
        {
            return;
        }
        if (!baselineProductsCurrent)
        {
            List<String> dropped = storedProductKeys(existing).stream()
                    .filter(key -> !aMerged.containsKey(key)).toList();
            if (!dropped.isEmpty())
            {
                // F3 (review): those products were corrected under another register, and the
                // carried copy cannot be re-corrected (an entry already applied reads as stale).
                // Like an older-format baseline: a notice, not a warning.
                LOGGER.log(System.Logger.Level.INFO,
                        "{0}: the existing store was corrected under another errata register -"
                                + " not carrying the products this source does not offer: {1}",
                        options.target(), dropped);
            }
            return;
        }
        for (String key : storedProductKeys(existing))
        {
            if (!aMerged.containsKey(key))
            {
                Optional<StoredProduct> carried;
                try
                {
                    carried = existing.product(key);
                }
                catch (java.io.UncheckedIOException e)
                {
                    // D-27 (review round 2, L1): a product entry whose embedded key mismatches
                    // binds lazily and fails on this first access. Dropped with a warning, so a
                    // plain re-seed repairs the store instead of dying on the baseline.
                    warn(key + ": the existing store's copy is corrupt (" + e.getMessage()
                            + ") - dropped; the next seed from a source that offers it"
                            + " re-acquires it");
                    continue;
                }
                carried.ifPresent(product ->
                {
                    aMerged.put(key, product);
                    productsCarried++;
                    warn(key + ": not offered by this source - carried forward unchanged"
                            + " from the existing store");
                });
            }
        }
    }


    /**
     * The product catalogue for the manifest: the sorted union of the source's catalogue, the keys
     * of the supplements this run applied, and the baseline's, mirroring
     * {@link #mergeProducts(Map)}.
     *
     * @param aSourceCatalogue
     *            the declarable product keys the source derives
     * @return the sorted, distinct catalogue
     * @throws IllegalStateException
     *             when called before {@link #mergeProducts(Map)} (F12)
     */
    List<String> catalogueUnion(List<String> aSourceCatalogue)
    {
        if (!productsMerged)
        {
            // F12: the applied supplements are known only once the products are merged.
            throw new IllegalStateException(
                    "catalogueUnion before mergeProducts: the applied supplements are not known");
        }
        TreeSet<String> union = new TreeSet<>(aSourceCatalogue);
        union.addAll(supplemented);
        if (existing != null)
        {
            union.addAll(existing.productCatalogue());
        }
        return List.copyOf(union);
    }


    /**
     * The manifest provenance: the options' override verbatim when set; otherwise the baseline's
     * lines with the line for this seeder's source replaced (or appended), so provenance stays one
     * line per source rather than growing per run. Either way the supplements line
     * ({@link StoreSupplements#PROVENANCE_SOURCE}, ref {@code sha256:<hex>} plus the applied keys,
     * F7) and then the register digest line ({@link StoreErrata#PROVENANCE_SOURCE}, ref
     * {@code sha256:<hex>}) are replaced by this build's and come last (D10: the errata line stays
     * the very last) — they describe how the products were supplemented and corrected, not where
     * they came from, so an override cannot drop them.
     *
     * @param aOwn
     *            this run's provenance line
     * @return the provenance to record
     */
    List<StoreProvenance> provenance(StoreProvenance aOwn)
    {
        List<StoreProvenance> merged = new ArrayList<>();
        List<StoreProvenance> override = options.provenanceOverride();
        if (override != null)
        {
            merged.addAll(override);
        }
        else
        {
            merged.addAll(sourceProvenance(aOwn));
        }
        merged.removeIf(line -> StoreErrata.PROVENANCE_SOURCE.equals(line.source())
                || StoreSupplements.PROVENANCE_SOURCE.equals(line.source()));
        merged.add(new StoreProvenance(StoreSupplements.PROVENANCE_SOURCE,
                supplements.provenanceRef(supplemented), ""));
        merged.add(new StoreProvenance(StoreErrata.PROVENANCE_SOURCE,
                StoreErrata.DIGEST_PREFIX + errata.digest(), ""));
        return merged;
    }


    private List<StoreProvenance> sourceProvenance(StoreProvenance aOwn)
    {
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
        Map<StoreTextNormaliser.Kind, Integer> counts = projection.normalisationCounts();
        StoreSeedReport.Normalisations normalisations = new StoreSeedReport.Normalisations(
                counts.getOrDefault(StoreTextNormaliser.Kind.LITERAL_NEWLINE, 0),
                counts.getOrDefault(StoreTextNormaliser.Kind.SHORT_FIELD_NEWLINE, 0),
                counts.getOrDefault(StoreTextNormaliser.Kind.HTML_ENTITY, 0));
        StoreErrata.Outcome outcome = errataOutcome;
        return new StoreSeedReport(aProductsSeeded, productsCarried, ctFetched, ctCarried, missed,
                warnings, aSourceRef, options.target(), aStoreBytes, normalisations,
                outcome == null ? List.of() : outcome.applied(),
                outcome == null ? List.of() : outcome.stale(),
                outcome == null ? List.of() : outcome.unreached(),
                outcome == null ? List.of() : outcome.notApplicable(), footnoteLabels, supplemented,
                supplementSkipped, supplementReplaced);
    }


    /**
     * Opens the re-seed baseline: the existing store at the target, unless {@code refresh} asked to
     * ignore it. A file that exists but fails to open is warned about and treated as absent —
     * everything is then re-acquired from the source, and the atomic replace overwrites the
     * unreadable file. Two format cases differ: an OLDER store is re-acquired in full and only
     * logged (D-17 — expected after a format bump, not a warning that should fail a seed); a NEWER
     * store (written by a newer build) is REFUSED unless {@code refresh} asked to replace it —
     * silently downgrading a store another installation relies on is the one thing a seed must not
     * do by accident.
     *
     * @throws IOException
     *             when the target holds a store of a newer format and {@code refresh} is off
     */
    private @Nullable MetadataStore openBaseline(StoreSeedOptions aOptions) throws IOException
    {
        if (aOptions.refresh() || !Files.isRegularFile(aOptions.target()))
        {
            return null;
        }
        try
        {
            return MetadataStore.open(aOptions.target());
        }
        catch (net.cumba.corej.core.metadata.store.StoreFormatException e)
        {
            if (e.writtenByNewerBuild())
            {
                // Review round 2 (M-NEWER d): the refusal is NEUTRAL - each surface appends its
                // own explicit-refresh switch (CLI --seed-overwrite, GUI "Refresh everything",
                // REST corej.cache-seed.refresh).
                throw new IOException(
                        e.getMessage() + ". Refusing to overwrite it without an explicit refresh.",
                        e);
            }
            // D-17: an old-format baseline is not carried forward at all — every CT package is
            // re-fetched, so no name-less codelist can be smuggled into the new store. Expected
            // once after every format bump, so it is a notice, never a warning (a warning fails
            // the CLI's --seed-cache exit code).
            LOGGER.log(System.Logger.Level.INFO,
                    "{0}: existing store is format {1} (this build writes format {2}): full"
                            + " re-seed, re-acquiring everything from the source",
                    aOptions.target(), e.foundVersion(), e.knownVersion());
            return null;
        }
        catch (IOException e)
        {
            warn(aOptions.target() + ": existing store is unreadable (" + e.getMessage()
                    + ") - re-acquiring everything from the source");
            return null;
        }
    }


    /**
     * Whether the baseline's provenance records this build's register digest. A store seeded before
     * the digest was recorded, or under another register, answers {@code false}.
     */
    private boolean errataDigestCurrent(MetadataStore aStore)
    {
        String current = StoreErrata.DIGEST_PREFIX + errata.digest();
        boolean same = aStore.manifest().provenance().stream()
                .anyMatch(line -> StoreErrata.PROVENANCE_SOURCE.equals(line.source())
                        && current.equals(line.ref()));
        if (!same)
        {
            LOGGER.log(System.Logger.Level.INFO,
                    "{0}: existing store was not corrected under this build''s errata register"
                            + " ({1}): its products are re-acquired, never carried",
                    options.target(), current);
        }
        return same;
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
