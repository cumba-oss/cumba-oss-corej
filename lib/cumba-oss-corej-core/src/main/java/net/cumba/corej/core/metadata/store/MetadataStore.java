package net.cumba.corej.core.metadata.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The unified CDISC metadata store, read side: one zip file (PLAN-metadata-cache-unification.md §4)
 * holding the projected IG/model products, every published CT package deduplicated through a shared
 * term table, and a manifest. {@link #open(Path)} inflates the whole store eagerly (~15 MB, tens of
 * milliseconds) and every accessor then serves from memory; {@link #close()} is a no-op today and
 * exists so a later format revision may load lazily without an API change.
 *
 * <p>
 * The store's internal term ids never appear here — {@link StoredCtPackage} hands out fully
 * materialised terms. That is what keeps a wholesale rebuild on every re-seed safe (plan §5.2).
 * </p>
 *
 * <p>
 * This phase (P1) ships the format only: nothing is wired to it yet, and the
 * {@code openIfConfigured()} cascade of plan §4.3 belongs to the wiring phases.
 * </p>
 */
public interface MetadataStore extends AutoCloseable
{

    /**
     * Opens the store at {@code aStore}, verifying the manifest's per-part sha256 hashes and
     * inflating everything into memory.
     *
     * @throws IOException
     *             if the file is missing, not a store, of an unknown format version, missing a
     *             part, carrying an undeclared part, or failing a hash or structural check — a
     *             partial or corrupted store never opens quietly.
     */
    static MetadataStore open(Path aStore) throws IOException
    {
        return ZipMetadataStore.open(aStore);
    }


    /** The projected product under {@code aKey} ({@code standards/…} or {@code models/…}). */
    Optional<StoredProduct> product(String aKey);


    /**
     * The CT package under {@code aId}.
     *
     * @throws IllegalArgumentException
     *             if {@code aId} is malformed — a malformed id is rejected before lookup, never
     *             conflated with an absent package. Ask {@link #presence(String)} first when the id
     *             is untrusted.
     */
    Optional<StoredCtPackage> ctPackage(String aId);


    /**
     * The published CT package enumeration, verbatim from the manifest. First-class data — NEVER
     * derived from what a run loaded; that derivation is the live defect this store fixes (plan
     * §1.1-1). May legitimately differ from what {@link #ctPackage(String)} can serve.
     */
    List<String> publishedCtPackages();


    /** Total presence answer for {@code aCtPackageId}; see {@link Presence}. Never throws. */
    Presence presence(String aCtPackageId);


    /** The declarable product catalogue, verbatim from the manifest. */
    List<String> productCatalogue();


    /** The store's manifest. */
    StoreManifest manifest();


    /** Releases nothing today (the store is fully in memory); never throws. */
    @Override
    void close();
}
