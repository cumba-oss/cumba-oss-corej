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
 * {@code Scope.Datasets} — selection by the <b>dataset name</b>
 * ({@code plans/PLAN-scope-requirements-split.md} &#167;4.6, owner requirement #5). Deliberately
 * <em>not</em> {@link DomainScope}: the two axes differ in exactly the two things
 * {@code DomainScope} carries.
 *
 * <ul>
 * <li><b>No split-base re-test.</b> {@code Scope.Domains: ["LB"]} selects {@code LB1} / {@code LB2}
 * through the data-derived unsplit name; {@code Scope.Datasets: ["LB"]} selects the file named
 * {@code LB} and nothing else. That absence <em>is</em> the feature.</li>
 * <li><b>No {@code include_split_datasets}.</b> It is a statement about domain families; on a name
 * axis it has no meaning, so the field is not offered.</li>
 * </ul>
 *
 * <p>
 * Entry vocabulary is {@code Scope.Domains}': the {@code ALL} / {@code NONE} sentinels,
 * {@code /regex/} and glob patterns, the strict {@code --} token, and literal equality after
 * normalisation. ⚠ Glob, {@code /regex/} and {@code NONE} are coreJ-only — the upstream CORE JSON
 * Schema's {@code Datasets} axis is a name enum plus a name pattern, so an entry using them is
 * legal here and not upstream-portable.
 * </p>
 */
@Data
@NoArgsConstructor
public class DatasetScope
{

    @JsonProperty("Include")
    private @Nullable List<String> include;

    @JsonProperty("Exclude")
    private @Nullable List<String> exclude;

    /**
     * JSON keys under {@code Scope.Datasets} that bound to no modelled property — in particular an
     * {@code include_split_datasets} copied over from {@code Scope.Domains}, which this axis does
     * not implement. The mapper runs with {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled, so without
     * this collector such a key would be silently dropped. Read by loader gate R2.
     *
     * <p>
     * Populated only at parse time, never serialised, and excluded from {@code equals} /
     * {@code hashCode} / {@code toString} — mirroring {@link Rule#getUnknownKeys()}.
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
