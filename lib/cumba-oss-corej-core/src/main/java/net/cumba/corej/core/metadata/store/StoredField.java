package net.cumba.corej.core.metadata.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * One CDASH field as the store holds it — the union of every scalar the source publishes on a
 * domain field, a scenario field or a CDASH model class field ({@code cdashModelFields}), measured
 * over the whole pickle corpus (PLAN-define-ct-evaluation phase 0b). A key the source omits on a
 * given field is {@code null}.
 *
 * <p>
 * A format-3 level (T1-9); nothing reads it yet ({@code reached-by: none}). ⚠ A field's
 * {@code _links.codelist} is deliberately NOT projected into a codelist-id list here: that would be
 * a new {@code _links}-derived value, and T1-9 keeps {@code _links} out (only
 * {@link StoredVariable#codelistIds()} keeps its pre-existing projection).
 * </p>
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StoredField(@Nullable String name, @Nullable String label, @Nullable String ordinal,
        @Nullable String core, @Nullable String definition, @Nullable String simpleDatatype,
        @Nullable List<String> codelistSubmissionValues, @Nullable String completionInstructions,
        @Nullable String implementationNotes, @Nullable String mappingInstructions,
        @Nullable String prompt, @Nullable String questionText, @Nullable String domainSpecific)
{

    /** Defensive copy; {@code null} (unpublished) stays {@code null}, distinct from empty. */
    public StoredField
    {
        codelistSubmissionValues = codelistSubmissionValues == null ? null
                : List.copyOf(codelistSubmissionValues);
    }
}
