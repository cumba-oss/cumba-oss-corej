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

@Data
@NoArgsConstructor
public class Reference
{

    @JsonProperty("Origin")
    private @Nullable String origin;

    @JsonProperty("Version")
    private @Nullable String version;

    @JsonProperty("Rule_Identifier")
    private @Nullable RuleIdentifier ruleIdentifier;

    @JsonProperty("Citations")
    private @Nullable List<Citation> citations;

    /**
     * JSON keys under a {@code Authorities[].Standards[].References[]} block that bound to no
     * modelled property. The mapper runs with {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled, so
     * without this collector a misspelt key ({@code Rule_Identifer}) is dropped and the reference
     * loses its identifier. Read by {@code RulePackageLoader.validateUnknownKeys}, which makes
     * every collected key a per-rule load error naming the key and its path
     * ({@code PLAN-rule-unknown-keys-gate}). Mirrors {@link DatasetScope#getUnknownKeys()}.
     *
     * <p>
     * Populated only at parse time, never serialised, and excluded from {@code equals} /
     * {@code hashCode} / {@code toString}, so two rules that differ only in dropped keys still
     * compare equal (round-trip and fixture-comparison tests rely on that).
     * </p>
     */
    @JsonIgnore
    @lombok.EqualsAndHashCode.Exclude
    @lombok.ToString.Exclude
    private final SequencedSet<String> unknownKeys = new LinkedHashSet<>();

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
        unknownKeys.add(name);
    }


    /**
     * The JSON keys of this block that bound to no modelled property, in encounter order.
     *
     * @return an unmodifiable view of the collected unknown keys
     */
    public SequencedSet<String> getUnknownKeys()
    {
        return Collections.unmodifiableSequencedSet(unknownKeys);
    }

}
