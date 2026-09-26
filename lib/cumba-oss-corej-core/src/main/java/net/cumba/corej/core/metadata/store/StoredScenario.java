package net.cumba.corej.core.metadata.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One CDASH IG scenario as the store holds it (a class's {@code scenarios[]} entry: the scenario
 * name, the domain it specialises and that domain's name) with its fields in source order.
 *
 * <p>
 * A format-3 level (PLAN-define-ct-evaluation T1-9); nothing reads it yet ({@code reached-by:
 * none} in the field manifest). Empty, never {@code null}, on every non-CDASH product.
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StoredScenario(@Nullable String scenario, @Nullable String domain,
        @Nullable String domainName, @Nullable String ordinal, List<StoredField> fields)
{

    /** Defensive copy; a {@code null} field list is canonicalised to empty. */
    public StoredScenario
    {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
