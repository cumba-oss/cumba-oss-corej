package net.cumba.corej.core.metadata.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * One library variable as the store holds it — the union of every scalar variable field either
 * product family publishes (AUDIT-metadata-reachable-fields.md §3), plus the variable's codelist
 * refs from {@code _links.codelist}.
 *
 * <p>
 * The field set is deliberately the FULL published union, not the six keys coreJ reads today: both
 * engines filter library variables by an arbitrary caller-supplied {@code key_name}, so a dropped
 * field silently empties any rule that names it (audit §1). ⛔ Do not narrow this record —
 * PLAN-library-variable-key-name-breadth.md depends on the breadth being here.
 * </p>
 *
 * <p>
 * IG variables publish {@code core} and {@code codelistSubmissionValues}; model variables publish
 * {@code roleDescription}, {@code definition}, {@code notes}, {@code examples},
 * {@code usageRestrictions} and {@code variableCcode}; the rest are common. A field the source did
 * not publish is {@code null}. {@code ordinal} is kept as the source's string.
 * </p>
 *
 * <p>
 * &#9888;&#9888; <b>{@code examples} is a SCALAR, corrected 2026-09-08 by the key-name P3 lane.</b>
 * Audit &sect;3 and PLAN-library-variable-key-name-breadth.md &sect;3.3 both list it among the
 * list-valued fields; it is not. Measured over the whole real cache
 * ({@code /data/cdisc.metadata.library-cache-pkl/standards_models.pkl}): 213 model class variables
 * and 47 model dataset variables publish {@code examples}, and <em>every one of them</em> is a JSON
 * string ({@code NHOID} &rarr; {@code "A/California/7/2009 (H1N1)"}). The independently written
 * api-model view agrees &mdash; {@code SdtmVariable.examples()} returns {@code Optional<String>}.
 * While it was typed {@code List<String>} here, {@code StoreProjection}'s array-only reader
 * returned {@code null} for every real occurrence, so the field was <b>silently dropped from every
 * seeded store</b> and only the synthetic seeder fixture (which invented a two-element list) ever
 * exercised it.
 * </p>
 *
 * <p>
 * ⚠ {@code codelistIds} is a LIST, though plan §3.3 says "the variable's
 * {@code _links.codelist.id}" in the singular: measured on the real caches 2026-09-08,
 * {@code _links.codelist} is an array and 31 variables carry between two and five refs. A
 * single-valued slot would silently drop refs.
 * </p>
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StoredVariable(@Nullable String name, @Nullable String label,
        @Nullable String ordinal, @Nullable String core, @Nullable String role,
        @Nullable String simpleDatatype, @Nullable String description,
        @Nullable List<String> valueList, @Nullable String describedValueDomain,
        @Nullable List<String> codelistSubmissionValues, @Nullable String roleDescription,
        @Nullable String definition, @Nullable String notes, @Nullable String examples,
        @Nullable String usageRestrictions, @Nullable String variableCcode,
        @Nullable List<String> codelistIds)
{

    /** Defensive copies; {@code null} (unpublished) stays {@code null}, distinct from empty. */
    public StoredVariable
    {
        valueList = valueList == null ? null : List.copyOf(valueList);
        codelistSubmissionValues = codelistSubmissionValues == null ? null
                : List.copyOf(codelistSubmissionValues);
        codelistIds = codelistIds == null ? null : List.copyOf(codelistIds);
    }
}
