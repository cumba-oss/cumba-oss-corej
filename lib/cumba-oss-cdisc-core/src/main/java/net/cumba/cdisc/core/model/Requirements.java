package net.cumba.cdisc.core.model;

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
 * What a rule needs in order to be able to answer at all — as opposed to {@link Scope}, which says
 * which datasets the rule is <em>about</em> ({@code plans/PLAN-scope-requirements-split.md}, owner
 * requirement #6).
 *
 * <p>
 * An unmet requirement yields {@code RuleExecutionStatus.SKIPPED} with a reason naming it; it is
 * never a finding. All facets are ANDed with each other, and {@code Requirements} as a whole is
 * ANDed with {@code Scope}.
 * </p>
 *
 * <pre>{@code
 * Requirements:
 *   Variables:
 *     All:  ["USUBJID", "--DTC"]
 *     Any:  ["--STDTC", "--DTC"]
 *     None: ["POOLID"]
 *   Datasets: ["EX"]
 *   Library: true
 *   Define: false
 *   Dictionary: true
 * }</pre>
 *
 * <p>
 * ⛔ A requirement is for a thing whose absence means <b>nothing to check</b>, never for a thing
 * whose absence <b>is</b> the defect (owner ruling {@code M2-D24} / {@code M3-F.1}, 2026-08-21). A
 * presence rule must not require the thing it reports.
 * </p>
 *
 * <p>
 * The three provider booleans are <b>derived</b> facts, not declarations: the engine computes them
 * from the rule's {@code Operations} and {@code Check} and behaves on the derivation regardless of
 * what is authored. An authored value that disagrees is a load error (gate R5) — the field
 * documents the derivation and can never override it.
 * </p>
 */
@Data
@NoArgsConstructor
public class Requirements
{

    /** Column-presence requirements. Facets are ANDed. */
    @JsonProperty("Variables")
    private @Nullable VariableRequirement variables;

    /**
     * Datasets that must be present in the run for the rule to be answerable at all. Presence is
     * the <b>widened</b> fact {@code ds_exists} has tested since {@code Fix #358} — a split domain
     * counts as present — never exact-name resolution.
     */
    @JsonProperty("Datasets")
    private @Nullable List<String> datasets;

    /** Whether a CDISC Library {@code MetadataProvider} is required. Derived; see class javadoc. */
    @JsonProperty("Library")
    private @Nullable Boolean library;

    /** Whether a sponsor Define-XML overlay is required. Derived; see class javadoc. */
    @JsonProperty("Define")
    private @Nullable Boolean define;

    /**
     * Whether at least one external dictionary is required. Derived; see class javadoc. A boolean,
     * not a list of {@code external_dictionary_type}s — the types are already in {@code Operations}
     * and duplicating them here would give them a second chance to drift.
     */
    @JsonProperty("Dictionary")
    private @Nullable Boolean dictionary;

    /**
     * JSON keys under {@code Requirements} that bound to no modelled property. The mapper runs with
     * {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled, so without this collector a misspelled block
     * ({@code Dataset:}, {@code Libraries:}) would bind to nothing and the requirement would
     * silently not exist. Read by loader gate R2.
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
