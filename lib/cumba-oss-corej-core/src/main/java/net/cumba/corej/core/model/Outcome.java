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
public class Outcome
{

    @JsonProperty("Message")
    private @Nullable String message;

    @JsonProperty("Output_Variables")
    private @Nullable List<String> outputVariables;

    /**
     * D7 ({@code PLAN-present-empty-column-fold}): the message of a <b>record-level</b> rule when
     * its per-row findings are folded or collapsed to ONE dataset-level finding. Declaring it is
     * the opt-in (D6) for the present-but-empty collapse ({@code EmptyColumnCollapse}): an
     * {@code empty}-family leaf over a present column whose content is blank on every row makes the
     * rule report once, under this message. A rule declaring it reports every broadcast TRUE of the
     * dataset-level fold under it too (the fold's verdict does not say what decided it). A rule
     * that can never collapse refuses the key at load (R2: no such leaf, a non-Record Sensitivity,
     * or a variable-cursor Check). {@code null} — the shipped default — leaves every rule as it
     * was.
     */
    @JsonProperty("Message_Folded")
    private @Nullable String messageFolded;

    /**
     * D9: the columns whose {@code empty}-family leaves never trigger the collapse — a Type 1 rule
     * keeps its per-row findings on them. {@code --} resolves like any variable reference; a name
     * the Check does not read is a load error. Inert on a rule without {@link #messageFolded}.
     */
    @JsonProperty("Keep_Row_Findings")
    private @Nullable List<String> keepRowFindings;

    /**
     * JSON keys under a {@code Outcome} block that bound to no modelled property. The mapper runs
     * with {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled, so without this collector a misspelt key
     * ({@code Mesage}, {@code Output_Variable}) is dropped and the rule reports with no message /
     * no output variables. Read by {@code RulePackageLoader.validateUnknownKeys}, which makes every
     * collected key a per-rule load error naming the key and its path
     * ({@code PLAN-rule-unknown-keys-gate}). Mirrors {@link DatasetScope#getUnknownKeys()}.
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
