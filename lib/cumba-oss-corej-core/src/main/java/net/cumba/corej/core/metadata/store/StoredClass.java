package net.cumba.corej.core.metadata.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One product class as the store holds it (audit §3: {@code name}/{@code label}/{@code ordinal};
 * since format 3 also {@code description}, PLAN-define-ct-evaluation T1-9). IG classes carry
 * datasets; model classes carry class-level variables; CDASH IG classes carry {@code domains} and
 * {@code scenarios}, CDASH model classes {@code cdashModelFields} — every slot exists and the
 * unused ones are empty.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StoredClass(@Nullable String name, @Nullable String label, @Nullable String ordinal,
        List<StoredVariable> classVariables, List<StoredDataset> datasets,
        @Nullable String description, List<StoredDomain> domains, List<StoredScenario> scenarios,
        List<StoredField> cdashModelFields)
{

    /** Defensive copies; {@code null} lists are canonicalised to empty. */
    public StoredClass
    {
        classVariables = classVariables == null ? List.of() : List.copyOf(classVariables);
        datasets = datasets == null ? List.of() : List.copyOf(datasets);
        domains = domains == null ? List.of() : List.copyOf(domains);
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        cdashModelFields = cdashModelFields == null ? List.of() : List.copyOf(cdashModelFields);
    }
}
