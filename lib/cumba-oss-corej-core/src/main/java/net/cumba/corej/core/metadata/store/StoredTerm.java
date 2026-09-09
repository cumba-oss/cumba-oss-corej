package net.cumba.corej.core.metadata.store;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One CT term as the store holds it — the five fields of the frozen CT field set
 * (AUDIT-metadata-reachable-fields.md §2) at term level.
 *
 * <p>
 * {@code definition} and {@code synonyms} are read by neither engine today and are stored anyway
 * (owner ruling 2026-09-08, plan §3.2 — deliberate future-proofing; do not optimise them out). A
 * field the source did not publish is {@code null}; {@code null} and an empty synonym list are
 * distinct and both round-trip.
 * </p>
 *
 * <p>
 * This is a STORAGE type: term identity for deduplication is full value equality of all five
 * components (the record's own {@code equals}), and the term ids the store assigns over it are
 * internal — they never appear in this API.
 * </p>
 */
public record StoredTerm(@Nullable String submissionValue, @Nullable String conceptId,
        @Nullable String preferredTerm, @Nullable String definition,
        @Nullable List<String> synonyms)
{

    /** Defensive copy; {@code null} (unpublished) stays {@code null}, distinct from empty. */
    public StoredTerm
    {
        synonyms = synonyms == null ? null : List.copyOf(synonyms);
    }
}
