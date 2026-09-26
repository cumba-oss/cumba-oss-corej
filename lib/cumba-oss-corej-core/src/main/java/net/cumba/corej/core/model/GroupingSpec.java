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
 * A rule's {@code Grouping:} block — the rule-level grouping key plus its missing-key disposition.
 *
 * <pre>
 * Grouping:
 *   Variables: ["USUBJID", "PARAMCD"]
 *   keep_missings: true          # optional; default is the engine's per-site default
 * </pre>
 *
 * <p>
 * <b>Why a block rather than two flat keys.</b> A flat {@code Grouping_Variables:} beside a flat
 * {@code Grouping_Keep_Missings:} lets a rule carry the parameter with no variables at all —
 * meaningless, and silently so. Nesting makes that state unrepresentable, and it matches the
 * existing {@code Scope.Domains.Include} house shape.
 * </p>
 *
 * <p>
 * ⚠ <b>The mixed casing is deliberate</b> and follows the corpus's own split: structural rule-level
 * keys are PascalCase ({@code Scope}, {@code Domains}, {@code Include}, {@code Variables}) while
 * parameters are snake_case ({@code value_is_literal}, {@code missing_values},
 * {@code keep_missings}). It is not a typo and must not be "corrected".
 * </p>
 *
 * <p>
 * The flat {@code Grouping_Variables:} form remains accepted — see
 * {@link Rule#effectiveGroupingVariables()} — so the corpus is never ahead of the engine. Declaring
 * both shapes on one rule is a load error.
 * </p>
 */
@Data
@NoArgsConstructor
public class GroupingSpec
{

    /**
     * The grouping key columns, in declared order. The {@code --} domain-prefix wildcard is
     * resolved at execution time exactly as it is for the flat {@code Grouping_Variables:} form.
     */
    @JsonProperty("Variables")
    private @Nullable List<String> variables;

    /**
     * Whether a row whose grouping key carries a missing value stays in its group (folded under the
     * blank key) or is dropped along with its whole group.
     *
     * <p>
     * {@code null} — the shipped state of every rule — means "engine default", which for the
     * rule-level grouping surface is <b>drop</b>. A missing value is a valid value of a variable,
     * so the intended direction is {@code true}; the default is not flipped here so that this phase
     * moves no findings.
     * </p>
     *
     * <p>
     * ⚠ Not to be confused with {@code missing_values} on an {@link Operation}, which governs a
     * different axis: how a missing <em>input</em> affects an <em>operation's result</em>. This one
     * governs whether a row <em>participates in a group</em>.
     * </p>
     */
    @JsonProperty("keep_missings")
    private @Nullable Boolean keepMissings;

    /**
     * JSON keys under a {@code Grouping} block that bound to no modelled property. The mapper runs
     * with {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled, so without this collector a misspelt key
     * ({@code Variable}, {@code keep_missing}) is dropped and the rule groups on nothing. Read by
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
