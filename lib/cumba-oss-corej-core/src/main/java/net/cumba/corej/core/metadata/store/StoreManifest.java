package net.cumba.corej.core.metadata.store;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The store's {@code manifest.json}: format version, per-source provenance, the sha256 of every
 * other zip entry, and the two first-class enumerations.
 *
 * <p>
 * {@link #publishedCtPackages()} is the complete published CT package universe as the seeder saw it
 * — it is data of its own, NOT derived from which packages the store happens to hold. Deriving it
 * from loaded content is the live defect the unified store exists to fix
 * (PLAN-metadata-cache-unification.md §1.1-1), so the writer requires it explicitly and the reader
 * serves it verbatim.
 * </p>
 *
 * @param formatVersion
 *            the on-disk format version; readers refuse a version they do not know
 * @param provenance
 *            one entry per contributing source
 * @param partHashes
 *            zip entry name → lowercase sha256 hex of the entry's uncompressed bytes, for every
 *            entry except the manifest itself. Iteration order is preserved as written (sorted by
 *            entry name).
 * @param publishedCtPackages
 *            the published CT package enumeration, first-class (see above)
 * @param productCatalogue
 *            the declarable product catalogue (IG-typed keys)
 */
public record StoreManifest(int formatVersion, List<StoreProvenance> provenance,
        Map<String, String> partHashes, List<String> publishedCtPackages,
        List<String> productCatalogue)
{

    /** Null-safe canonicalisation to immutable, order-preserving collections. */
    public StoreManifest
    {
        provenance = provenance == null ? List.of() : List.copyOf(provenance);
        partHashes = partHashes == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(partHashes));
        publishedCtPackages = publishedCtPackages == null ? List.of()
                : List.copyOf(publishedCtPackages);
        productCatalogue = productCatalogue == null ? List.of() : List.copyOf(productCatalogue);
    }
}
