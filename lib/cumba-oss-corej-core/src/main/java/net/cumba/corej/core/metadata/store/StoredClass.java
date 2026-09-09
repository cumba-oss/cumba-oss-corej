package net.cumba.corej.core.metadata.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One product class as the store holds it (audit §3: {@code name}/{@code label}/{@code ordinal}).
 * IG classes carry datasets; model classes carry class-level variables — both slots exist and the
 * unused one is empty.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StoredClass(@Nullable String name, @Nullable String label, @Nullable String ordinal,
        List<StoredVariable> classVariables, List<StoredDataset> datasets)
{

    /** Defensive copies; {@code null} lists are canonicalised to empty. */
    public StoredClass
    {
        classVariables = classVariables == null ? List.of() : List.copyOf(classVariables);
        datasets = datasets == null ? List.of() : List.copyOf(datasets);
    }
}
