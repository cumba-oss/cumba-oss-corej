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
 * A format-3 level (T1-9); nothing reads it yet ({@code reached-by: none}).
 * </p>
 *
 * <p>
 * {@link #codelistIds()} (format 4): the C-codes of the field's {@code _links.codelist} refs, each
 * reduced to its trailing id segment, exactly as {@link StoredVariable#codelistIds()} — T1-9's one
 * {@code _links}-derived exception, extended from SDTM/SEND variables to CDASH fields by the owner
 * on 2026-09-28 (PLAN-store-cdash-codelist-ids). The rest of {@code _links} stays out. In cdashig
 * 1-1-1 / 2-0 / 2-1 / 2-2 and the CDASH models 1-0 / 1-1 / 1-2 the link is the field's ONLY
 * codelist information: those products publish no {@code codelistSubmissionValues}.
 * </p>
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StoredField(@Nullable String name, @Nullable String label, @Nullable String ordinal,
        @Nullable String core, @Nullable String definition, @Nullable String simpleDatatype,
        @Nullable List<String> codelistSubmissionValues, @Nullable String completionInstructions,
        @Nullable String implementationNotes, @Nullable String mappingInstructions,
        @Nullable String prompt, @Nullable String questionText, @Nullable String domainSpecific,
        @Nullable List<String> codelistIds)
{

    /** Defensive copies; {@code null} (unpublished) stays {@code null}, distinct from empty. */
    public StoredField
    {
        codelistSubmissionValues = codelistSubmissionValues == null ? null
                : List.copyOf(codelistSubmissionValues);
        codelistIds = codelistIds == null ? null : List.copyOf(codelistIds);
    }
}
