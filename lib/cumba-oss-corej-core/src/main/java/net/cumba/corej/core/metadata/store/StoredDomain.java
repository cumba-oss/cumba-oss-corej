package net.cumba.corej.core.metadata.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One CDASH domain as the store holds it — a CDASH IG class's {@code domains[]} entry or a CDASH
 * model product's top-level {@code domains[]} entry — with its fields in source order.
 *
 * <p>
 * A format-3 level (PLAN-define-ct-evaluation T1-9: <i>the pickle cache is the universe</i>): the
 * source publishes it, so the store keeps it. Nothing reads it yet; the field manifest says so
 * ({@code reached-by: none}). Empty, never {@code null}, on every non-CDASH product.
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StoredDomain(@Nullable String name, @Nullable String label, @Nullable String ordinal,
        @Nullable String description, List<StoredField> fields)
{

    /** Defensive copy; a {@code null} field list is canonicalised to empty. */
    public StoredDomain
    {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
