package net.cumba.corej.core.metadata.store;

/**
 * One provenance line in the {@linkplain StoreManifest manifest}: which source contributed to the
 * store, the ref (tag, URL, directory) it was read from, and when. All three are caller-supplied
 * display strings — the store itself never invents a timestamp, which is what keeps a rebuild from
 * identical input byte-identical (PLAN-metadata-cache-unification.md §5.1).
 */
public record StoreProvenance(String source, String ref, String fetchedAt)
{

    /** Null-safe canonicalisation: a missing component is stored as the empty string. */
    public StoreProvenance
    {
        source = source == null ? "" : source;
        ref = ref == null ? "" : ref;
        fetchedAt = fetchedAt == null ? "" : fetchedAt;
    }
}
