package net.cumba.cdisc.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * ADaM data-structure scope filter for rules ({@code Scope.Data_Structures}). When present,
 * restricts the rule to datasets whose detected data structure (see
 * {@link net.cumba.cdisc.core.metadata.AdamDataStructureDetector}) is in {@code Include} (when
 * given) and not in {@code Exclude}. The token vocabulary is the ADaM structure set:
 * {@code SUBJECT LEVEL ANALYSIS DATASET}, {@code BASIC DATA STRUCTURE},
 * {@code OCCURRENCE DATA STRUCTURE}, {@code ADAM OTHER}, plus the {@code ALL} sentinel in
 * {@code Include}.
 *
 * <p>
 * Mirrors the Python engine's {@code rule_applies_to_data_structure} gate
 * ({@code cdisc_rules_engine/utilities/rule_processor.py}); the upstream authoring spelling is
 * {@code "Data Structures"} (accepted via {@code @JsonAlias} on {@link Scope}), the house canonical
 * spelling is {@code Data_Structures}.
 * </p>
 */
@Data
@NoArgsConstructor
public class DataStructureScope
{

    @JsonProperty("Include")
    private @Nullable List<String> include;

    @JsonProperty("Exclude")
    private @Nullable List<String> exclude;

}
