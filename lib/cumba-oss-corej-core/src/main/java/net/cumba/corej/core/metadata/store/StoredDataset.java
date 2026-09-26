package net.cumba.corej.core.metadata.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One dataset as the store holds it (audit §3: {@code name}/{@code label}/{@code ordinal}/
 * {@code datasetStructure}; since format 3 also {@code description} and {@code status},
 * PLAN-define-ct-evaluation T1-9) plus its variables in source order. Model datasets publish no
 * {@code datasetStructure}; it is then {@code null}, as is any scalar the source omits.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StoredDataset(@Nullable String name, @Nullable String label, @Nullable String ordinal,
        @Nullable String datasetStructure, List<StoredVariable> variables,
        @Nullable String description, @Nullable String status)
{

    /** Defensive copy; a {@code null} variable list is canonicalised to empty. */
    public StoredDataset
    {
        variables = variables == null ? List.of() : List.copyOf(variables);
    }
}
