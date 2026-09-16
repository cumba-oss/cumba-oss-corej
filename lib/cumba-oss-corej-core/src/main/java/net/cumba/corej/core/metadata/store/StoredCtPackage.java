package net.cumba.corej.core.metadata.store;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One published CT package as the store holds it: its id and its codelists, in the package's own
 * codelist order.
 *
 * <p>
 * Every other package-level field the API publishes ({@code name}, {@code label},
 * {@code effectiveDate}, …) is excluded — zero call sites in either engine
 * (AUDIT-metadata-reachable-fields.md §2) — but the id is first-class because four independent
 * run-time selection paths resolve packages by it (audit §6).
 * </p>
 *
 * <p>
 * ⚠ {@code id} is {@link Nullable} on purpose, and was annotated as such once NullAway was armed
 * (it had always been constructed null-bearing). {@code null} is the <b>anonymous empty package</b>
 * a provider carries when the run selected no CT at all — see
 * {@code StoreMetadataProviderFactory.forSdtm}/{@code forAdam}, whose
 * {@code MetadataLibraryProvider} sink already takes a {@code @Nullable} configured package id. A
 * store never holds one: {@code MetadataStoreWriter.addCtPackage} rejects a null (or malformed) id
 * outright, which is the guard that keeps the two dispositions apart.
 * </p>
 */
public record StoredCtPackage(@Nullable String id, List<StoredCodelist> codelists)
{

    /** Defensive copy; a {@code null} codelist list is canonicalised to empty. */
    public StoredCtPackage
    {
        codelists = codelists == null ? List.of() : List.copyOf(codelists);
    }
}
