package net.cumba.corej.core.metadata.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One ADaM data structure as the store holds it (audit §3: {@code className}/{@code subClass} plus
 * {@code analysisVariableSets}; the source's key for the former is {@code class} — the seeder maps
 * it). {@code subClass} is published on only a few structures and is otherwise {@code null}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StoredDataStructure(@Nullable String name, @Nullable String label,
        @Nullable String ordinal, @Nullable String className, @Nullable String subClass,
        List<StoredVariableSet> variableSets)
{

    /** Defensive copy; a {@code null} set list is canonicalised to empty. */
    public StoredDataStructure
    {
        variableSets = variableSets == null ? List.of() : List.copyOf(variableSets);
    }
}
