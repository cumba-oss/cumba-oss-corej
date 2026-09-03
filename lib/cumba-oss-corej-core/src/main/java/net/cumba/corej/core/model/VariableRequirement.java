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
 * Column-presence requirements — {@code Requirements.Variables}
 * ({@code plans/PLAN-scope-requirements-split.md} &#167;4.3). Successor of the retired
 * {@code Scope.Variables} block, which said the same thing in a field named for selection.
 *
 * <h2>Semantics</h2>
 * <ul>
 * <li>{@code All} — every entry must be present. Byte-for-byte the former
 * {@code Scope.Variables.Include}.</li>
 * <li>{@code Any} — <b>new</b>: at least one entry must be present. Unmet only when <em>every</em>
 * entry is absent, so the mismatch reason names the whole list — no single entry is at fault.</li>
 * <li>{@code None} — no entry may be present. Byte-for-byte the former
 * {@code Scope.Variables.Exclude}.</li>
 * </ul>
 *
 * <p>
 * The three facets are ANDed with each other and with {@link Scope}. Entry vocabulary is unchanged
 * from {@code Scope.Variables}: literal, {@code --} domain-prefix placeholder, qualified
 * {@code DATASET.VARIABLE}, glob and {@code /regex/}.
 * </p>
 *
 * <p>
 * ⛔ A requirement is for a thing whose absence means <b>nothing to check</b>, never for a thing
 * whose absence <b>is</b> the defect (owner ruling {@code M2-D24} / {@code M3-F.1}, 2026-08-21). A
 * presence rule must never list the variable it reports in {@code All} or {@code Any} — that skips
 * the rule on exactly the case it exists for.
 * </p>
 *
 * <p>
 * ⚠ {@code None} ships with <b>zero</b> corpus carriers (as did {@code Scope.Variables.Exclude}
 * before it), so every test of it is a hand-authored gate test: it proves the engine works and
 * never that a shipped rule carries it.
 * </p>
 */
@Data
@NoArgsConstructor
public class VariableRequirement
{

    /** Every entry must be present — the former {@code Scope.Variables.Include}. */
    @JsonProperty("All")
    private @Nullable List<String> all;

    /** At least one entry must be present. Two or more entries required (loader gate R4). */
    @JsonProperty("Any")
    private @Nullable List<String> any;

    /** No entry may be present — the former {@code Scope.Variables.Exclude}. */
    @JsonProperty("None")
    private @Nullable List<String> none;

    /**
     * JSON keys under {@code Requirements.Variables} that bound to no modelled property. The mapper
     * runs with {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled, so without this collector a misspelled
     * facet ({@code Al:}, {@code AnyOf:}) would bind to nothing and the requirement would silently
     * not exist. Read by loader gate R2.
     *
     * <p>
     * Populated only at parse time, never serialised, and excluded from {@code equals} /
     * {@code hashCode} / {@code toString} — mirroring {@link Rule#getUnknownKeys()}, whose contract
     * this is a copy of.
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
