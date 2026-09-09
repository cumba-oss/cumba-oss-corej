package net.cumba.corej.core.metadata.store;

import java.util.List;

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
 */
public record StoredCtPackage(String id, List<StoredCodelist> codelists)
{

    /** Defensive copy; a {@code null} codelist list is canonicalised to empty. */
    public StoredCtPackage
    {
        codelists = codelists == null ? List.of() : List.copyOf(codelists);
    }
}
