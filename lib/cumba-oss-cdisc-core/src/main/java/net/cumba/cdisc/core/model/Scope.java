package net.cumba.cdisc.core.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.SequencedSet;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Data
@NoArgsConstructor
public class Scope
{

    @JsonProperty("Classes")
    private @Nullable ClassScope classes;

    @JsonProperty("Domains")
    private @Nullable DomainScope domains;

    /*
     * ⛔ There is deliberately NO `Variables` property here. It retired to
     * `Requirements.Variables` (PLAN-scope-requirements-split.md phase 4/5): a variable
     * requirement says what a rule needs in order to answer at all, not which datasets it is
     * about. A surviving `Scope: {Variables: …}` now reaches `recordUnknownKey` below and is
     * rejected by loader gate R1 — re-adding the property here would disarm that gate silently.
     */

    /**
     * Selection by the <b>dataset name</b> (owner requirement #5) — {@code Scope.Domains} minus the
     * split-base re-test. See {@link DatasetScope}.
     */
    @JsonProperty("Datasets")
    private @Nullable DatasetScope datasets;

    @JsonProperty("Use_Case")
    private @Nullable String useCase;

    /**
     * ADaM data-structure scope. Canonical house spelling is {@code Data_Structures}; the alias
     * accepts the upstream CORE authoring spelling {@code "Data Structures"} (the Python engine
     * normalizes spaces to underscores before reading it).
     */
    @JsonProperty("Data_Structures")
    @JsonAlias("Data Structures")
    private @Nullable DataStructureScope dataStructures;

    /** ADaM subclass scope ({@code Scope.Subclasses}, Define-XML 2.1 subclass vocabulary). */
    @JsonProperty("Subclasses")
    private @Nullable SubclassScope subclasses;

    /**
     * JSON keys under {@code Scope} that bound to no modelled property. The mapper runs with
     * {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled, so without this collector a key here binds to
     * nothing and is <em>unrecorded</em> — which is exactly how a {@code Scope.Variables} surviving
     * the {@code Requirements} migration would become invisible to loader gate R1 rather than an
     * error. Read by gates R1 and R2.
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
     * The JSON keys of this scope that bound to no modelled property, in encounter order.
     *
     * @return an unmodifiable view of the collected unknown keys
     */
    public SequencedSet<String> getUnknownKeys()
    {
        return Collections.unmodifiableSequencedSet(unknownKeys);
    }

}
