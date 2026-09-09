package net.cumba.corej.core.metadata.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One ADaM analysis variable set as the store holds it, with its analysis variables in source
 * order.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StoredVariableSet(@Nullable String name, @Nullable String label,
        @Nullable String ordinal, List<StoredVariable> variables)
{

    /** Defensive copy; a {@code null} variable list is canonicalised to empty. */
    public StoredVariableSet
    {
        variables = variables == null ? List.of() : List.copyOf(variables);
    }
}
