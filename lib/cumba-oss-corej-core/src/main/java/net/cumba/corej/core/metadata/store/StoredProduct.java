package net.cumba.corej.core.metadata.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * One projected metadata product as the store holds it — an IG ({@code standards/…}) or a
 * foundational model ({@code models/…}), keyed exactly as the catalogue keys it.
 *
 * <p>
 * Structure per AUDIT-metadata-reachable-fields.md §3: product {@code name}/{@code label}/
 * {@code version} plus the one surviving product link, {@code _links.model.href} ({@code null} on
 * models and on products without one). The three product shapes each use their slots: SDTM-family
 * IGs fill {@code classes} (with nested datasets), foundational models fill {@code classes} (with
 * class variables) AND top-level {@code datasets}, ADaM products fill {@code dataStructures}.
 * Unused slots are empty, never {@code null}.
 * </p>
 *
 * <p>
 * This is a STORAGE type — a plain record over the store's content. It is NOT the engine-facing
 * type: the engine keeps consuming {@code ICodeList} and friends, built from these in a later phase
 * (plan §4.3.1). No {@code ApiResource}, no Jackson proxy, no {@code Link}.
 * </p>
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StoredProduct(String key, @Nullable String name, @Nullable String label,
        @Nullable String version, @Nullable String modelHref, List<StoredClass> classes,
        List<StoredDataset> datasets, List<StoredDataStructure> dataStructures)
{

    /** Defensive copies; {@code null} structure lists are canonicalised to empty. */
    public StoredProduct
    {
        classes = classes == null ? List.of() : List.copyOf(classes);
        datasets = datasets == null ? List.of() : List.copyOf(datasets);
        dataStructures = dataStructures == null ? List.of() : List.copyOf(dataStructures);
    }
}
