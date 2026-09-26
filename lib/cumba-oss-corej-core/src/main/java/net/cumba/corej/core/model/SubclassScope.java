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
 * ADaM subclass scope filter for rules ({@code Scope.Subclasses}). When present, restricts the rule
 * by the dataset's detected data-structure subclass (see
 * {@link net.cumba.corej.core.metadata.AdamSubclassDetector}). The token vocabulary is the
 * Define-XML 2.1 {@code ItemGroupSubClass} enumeration — {@code ADVERSE EVENT},
 * {@code MEDICAL DEVICE TIME-TO-EVENT}, {@code NON-COMPARTMENTAL ANALYSIS},
 * {@code POPULATION PHARMACOKINETIC ANALYSIS}, {@code TIME-TO-EVENT} — plus the {@code ALL}
 * sentinel in {@code Include}.
 *
 * <h2>Null-detection semantics (decided 2026-07-26)</h2>
 * <ul>
 * <li>{@code Include} requires a positively detected subclass that is in the list — a dataset with
 * no detectable subclass (the normal case for a plain BDS/OCCDS/ADSL dataset) is skipped;</li>
 * <li>{@code Exclude} rejects only on a positive match — a dataset with no detectable subclass
 * passes an Exclude-only scope.</li>
 * </ul>
 *
 * <p>
 * Unlike {@code Data_Structures} this field has no Python-engine runtime counterpart upstream (the
 * CORE rule schema defines it, nothing consumes it).
 * </p>
 */
@Data
@NoArgsConstructor
public class SubclassScope
{

    @JsonProperty("Include")
    private @Nullable List<String> include;

    @JsonProperty("Exclude")
    private @Nullable List<String> exclude;

    /**
     * JSON keys under a {@code Scope.Subclasses} block that bound to no modelled property. The
     * mapper runs with {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled, so without this collector a
     * misspelt key ({@code Includ}) is dropped and the rule runs against every subclass. Read by
     * {@code RulePackageLoader.validateUnknownKeys}, which makes every collected key a per-rule
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
