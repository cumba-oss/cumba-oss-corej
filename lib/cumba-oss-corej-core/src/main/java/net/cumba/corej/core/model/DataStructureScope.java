package net.cumba.corej.core.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * ADaM data-structure scope filter for rules ({@code Scope.Data_Structures}). When present,
 * restricts the rule to datasets whose detected data structure (see
 * {@link net.cumba.corej.core.metadata.AdamDataStructureDetector}) is in {@code Include} (when
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

    /**
     * JSON keys under a {@code Scope.Data_Structures} block that bound to no modelled property. The
     * mapper runs with {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled, so without this collector a
     * misspelt key ({@code Includ}) is dropped and the rule runs against every data structure. Read
     * by {@code RulePackageLoader.validateUnknownKeys}, which makes every collected key a per-rule
     * load error naming the key and its path ({@code PLAN-rule-unknown-keys-gate}). Mirrors
     * {@link DatasetScope#getUnknownKeys()}.
     *
     * <p>
     * Populated only at parse time, never serialised, and excluded from {@code equals} /
     * {@code hashCode} / {@code toString}, so two rules that differ only in dropped keys still
     * compare equal (round-trip and fixture-comparison tests rely on that).
     * </p>
     */
    @JsonIgnore
    @lombok.Setter(lombok.AccessLevel.NONE)
    @lombok.EqualsAndHashCode.Exclude
    @lombok.ToString.Exclude
    private @Nullable SequencedSet<String> unknownKeys;

    /** The shared empty view every clean instance answers with (review E8: no per-instance set). */
    private static final SequencedSet<String> NO_UNKNOWN_KEYS = Collections
            .unmodifiableSequencedSet(new LinkedHashSet<>());

    /**
     * Jackson's catch-all for unbound JSON keys; records the key name and drops the value.
     *
     * @param name
     *            the unbound JSON key
     * @param value
     *            its value — deliberately unread; only the key's presence is diagnostic
     */
    @JsonAnySetter
    void recordUnknownKey(String name, @Nullable Object value)
    {
        // Allocated on the first unknown key only: a full corpus load builds tens of thousands
        // of these blocks and almost none carries one (review E8).
        if (unknownKeys == null)
        {
            unknownKeys = new LinkedHashSet<>();
        }
        unknownKeys.add(name);
    }


    /**
     * The JSON keys of this block that bound to no modelled property, in encounter order.
     *
     * @return an unmodifiable view of the collected unknown keys
     */
    public SequencedSet<String> getUnknownKeys()
    {
        return unknownKeys == null ? NO_UNKNOWN_KEYS
                : Collections.unmodifiableSequencedSet(unknownKeys);
    }

}
