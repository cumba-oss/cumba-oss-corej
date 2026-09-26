package net.cumba.corej.core.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.SequencedSet;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Project-specific annotation that records, for a rule which is <em>not directly executable</em>,
 * what the engine must do to run it — or, for a genuinely non-executable rule, why it cannot be
 * run.
 *
 * <p>
 * The upstream CDISC {@link Executability} flag is coarse (and the engine does not consult it for
 * execution decisions). A rule whose {@link Executability} is
 * {@link Executability#FULLY_EXECUTABLE} may still require wildcard expansion or template
 * generation before it can run; this hint carries that "how". It is present only on rules that are
 * not directly executable:
 * </p>
 * <ul>
 * <li>{@code expanded} — the rule's {@code --} domain-prefix wildcards must be expanded to the
 * target dataset (handled by {@code WildcardExpander} / {@code DatasetRuleResolver}).</li>
 * <li>{@code generated} — concrete rules must be materialised from a wildcard/root-name template
 * (e.g. {@code *FL}) by {@code DatasetRuleResolver} using dataset and CDISC Library variable
 * metadata. ⚑ The template is the corpus rule's own; the hint's name predates
 * {@code plans/PLAN-remove-rule-generator.md}, which removed the in-Java generators — nothing is
 * minted from metadata any more, only expanded from a rule the user selected.</li>
 * <li>{@code not executable} — the rule cannot be run by the current engine (e.g. it needs dynamic
 * value-indexed variable resolution or multi-axis index expansion); {@link Executability} stays
 * {@link Executability#NOT_EXECUTABLE} and {@link #detail} explains the blocker.</li>
 * </ul>
 *
 * <p>
 * Directly-executable rules carry no hint.
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExecutabilityHint
{

    /**
     * Category of handling required: {@code expanded}, {@code generated}, or
     * {@code not executable}.
     */
    @JsonProperty("Category")
    private @Nullable String category;

    /**
     * Human-readable description of what the engine must do to execute the rule, or — for a
     * {@code not executable} rule — why it cannot be run.
     */
    @JsonProperty("Detail")
    private @Nullable String detail;

    /**
     * JSON keys under a {@code ExecutabilityHint} block that bound to no modelled property. The
     * mapper runs with {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled, so without this collector a
     * misspelt key ({@code Categroy}) is dropped and the hint is empty. Read by
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
