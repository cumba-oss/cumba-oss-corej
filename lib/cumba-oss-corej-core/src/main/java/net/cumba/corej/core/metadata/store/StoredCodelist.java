package net.cumba.corej.core.metadata.store;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One codelist version as the store holds it: the codelist-level half of the frozen CT field set
 * (AUDIT-metadata-reachable-fields.md §2) plus its terms.
 *
 * <p>
 * The source's display {@code name} ({@code "Sex"} for {@code C66731}) is stored since format 3
 * (PLAN-define-ct-evaluation T1-3 b, owner 2026-09-25: <i>"add name to StoredCodelist. We are the
 * store owner and if this is needed for a rule, we should support this"</i>) — PMDA-DD0031 finds a
 * CT codelist by its name ({@code StoreCtProvider.codelistByName}). It used to be the one
 * codelist-level field the source publishes that the store dropped. {@code definition} and
 * {@code synonyms} are stored though unread by either engine (owner ruling 2026-09-08).
 * {@code extensible} is a real {@link Boolean}, not the API's string — the store's canonical form
 * is the engine's own model, not the wire format (plan §5.1); {@code null} means the source did not
 * publish it.
 * </p>
 *
 * <p>
 * ⚠ Term order is NOT source order: the store keeps each codelist's terms as a deduplicated set,
 * returned in the store's internal content order (deterministic, but meaningless). CT term order
 * carries no semantics in either engine, and the varint-delta encoding that makes 206 cumulative
 * packages fit in ~2.7 MB requires sorted id lists (plan §3.4, §4.2).
 * </p>
 */
public record StoredCodelist(@Nullable String submissionValue, @Nullable String conceptId,
        @Nullable String name, @Nullable String preferredTerm, @Nullable String definition,
        @Nullable List<String> synonyms, @Nullable Boolean extensible, List<StoredTerm> terms)
{

    /** Defensive copies; a {@code null} term list is canonicalised to empty. */
    public StoredCodelist
    {
        synonyms = synonyms == null ? null : List.copyOf(synonyms);
        terms = terms == null ? List.of() : List.copyOf(terms);
    }
}
