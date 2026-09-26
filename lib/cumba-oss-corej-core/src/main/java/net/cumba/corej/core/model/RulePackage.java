package net.cumba.corej.core.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Data
@NoArgsConstructor
public class RulePackage
{

    private @Nullable Map<String, Rule> rules;

    /**
     * The CDISC Library standards this package declares it runs against (R6), or {@code null} when
     * the package declares none.
     *
     * <p>
     * ⛔ <b>This must stay a MODELLED property.</b> The loader's mapper runs with
     * {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled, so an unmodelled {@code standards} key would be
     * swallowed by {@link #recordUnknownKey} and the declaration would vanish without trace — the
     * silent-degradation shape this programme keeps finding.
     * </p>
     *
     * <p>
     * ⚑ Declarations are resolved <b>file first, then the {@code packages.json} cache</b>; the
     * manifest copy exists for fast lookup without opening every package, not as an authority.
     * </p>
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    private @Nullable List<StandardRef> standards;

    /** The keys a {@code standards[]} entry binds — the {@link StandardRef} record's components. */
    public static final java.util.Set<String> STANDARD_REF_KEYS = java.util.Set.of("id", "role");

    /**
     * The record's own binding, applied per entry by {@link #setStandardsJson}. Lenient like the
     * loader's mapper: the extras are already collected, and the record must bind exactly as it did
     * when Jackson bound the list directly (an unknown {@code role} still throws through
     * {@link StandardRef.Role#fromWire}).
     */
    private static final com.fasterxml.jackson.databind.ObjectMapper STANDARD_REF_READER = new com.fasterxml.jackson.databind.ObjectMapper()
            .configure(
                    com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    false);

    /**
     * Every key of a {@code standards[i]} entry that is neither {@code id} nor {@code role}, as
     * {@code standards[i]: 'key'}, in encounter order ({@code PLAN-rule-unknown-keys-gate}, T1-6).
     *
     * <p>
     * ⛔ Collected <b>here</b>, not by a collector on {@link StandardRef}: the record is serialised
     * into every generated package and into {@code packages.json}, is built at a dozen
     * {@code new StandardRef(…)} sites and compared by value — a collector field would change its
     * constructor and {@code equals} and write an {@code unknownKeys} member into every package,
     * which this very gate would then reject (review H1).
     * </p>
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @lombok.EqualsAndHashCode.Exclude
    @lombok.ToString.Exclude
    private final List<UnknownStandardKey> unknownStandardKeys = new java.util.ArrayList<>();

    /**
     * One key of a {@code standards[index]} entry that is neither {@code id} nor {@code role}, with
     * the keys that entry does carry (so the hint never proposes one of them).
     */
    public record UnknownStandardKey(int index, String key, java.util.Set<String> present)
    {

        /** Defensive, unmodifiable copy — the pattern {@link RuleCheck} uses for its map. */
        public UnknownStandardKey
        {
            present = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(present));
        }
    }

    /**
     * Jackson's binding of {@code standards} — the explicitly named setter wins over the Lombok one
     * for JSON — reading each entry as a raw object so its unbound keys can be recorded before the
     * {@link StandardRef} record binds it exactly as before. Programmatic callers keep
     * {@code setStandards(List)}; serialisation keeps {@code getStandards()} and is unchanged.
     *
     * @param raw
     *            the JSON array's elements, or {@code null} for a JSON null
     * @throws java.io.IOException
     *             if an entry does not bind as a {@link StandardRef} (an unknown role, a non-object
     *             element) — the package load fails, as it did before
     */
    // Package-private, not private: invoked reflectively by Jackson, and both PMD and SpotBugs
    // flag an uncalled private method.
    @com.fasterxml.jackson.annotation.JsonSetter("standards")
    void setStandardsJson(@Nullable List<com.fasterxml.jackson.databind.node.ObjectNode> raw)
        throws java.io.IOException
    {
        if (raw == null)
        {
            this.standards = null;
            return;
        }
        List<StandardRef> bound = new java.util.ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++)
        {
            com.fasterxml.jackson.databind.node.ObjectNode entry = raw.get(i);
            if (entry == null)
            {
                // Review E4: a JSON null element used to surface as a bare NullPointerException.
                throw new java.io.IOException("rule package: standards[" + i
                        + "] is null — a standards entry is an object {id, role}");
            }
            int index = i;
            java.util.Set<String> present = new LinkedHashSet<>();
            entry.fieldNames().forEachRemaining(present::add);
            for (String key : present)
            {
                if (!STANDARD_REF_KEYS.contains(key))
                {
                    unknownStandardKeys.add(new UnknownStandardKey(index, key, present));
                }
            }
            bound.add(STANDARD_REF_READER.treeToValue(entry, StandardRef.class));
        }
        this.standards = bound;
    }


    /**
     * The {@code standards[i]} entry keys that bound to nothing, in encounter order.
     *
     * @return an unmodifiable view, empty for every shipped package
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public List<UnknownStandardKey> getUnknownStandardKeys()
    {
        return java.util.Collections.unmodifiableList(unknownStandardKeys);
    }

    /**
     * Every top-level JSON key of this package that bound to no modelled property, in encounter
     * order.
     *
     * <p>
     * The loader's mapper runs with {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled, so an unknown
     * package key would otherwise vanish without trace. This set only <em>records</em> them; what
     * rejects is {@code RulePackageLoader.validateNoPackageSeverityThreshold}, which fails the
     * package load on a package-level <b>run severity threshold</b> (named) and, since
     * {@code PLAN-rule-unknown-keys-gate} (T1-6), on <b>any</b> other key — a package carries only
     * {@code rules} and {@code standards}.
     * </p>
     *
     * <p>
     * &#9873; <b>Why that key in particular is rejected rather than ignored</b> (Plan C &#167;3.4,
     * ruling 4): the threshold is a <em>run</em> option and nothing else. A per-package threshold
     * would let one rule behave differently in two packages, which contradicts the {@code rules/}
     * findings-diff invariant — a rule's behaviour is a property of the rule. Silently dropping the
     * key would leave an author believing a threshold was in force when it was not, which is worse
     * than either accepting or rejecting it.
     * </p>
     *
     * <p>
     * Shipped packages carry only modelled keys ({@code rules}, and {@code standards} since Plan 2
     * Phase 2), so this set is empty for all 56 of them. ⚠ It stays empty only while every key a
     * package uses is modelled above — that is the point of the property, not a coincidence.
     * </p>
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @lombok.EqualsAndHashCode.Exclude
    @lombok.ToString.Exclude
    private final SequencedSet<String> unknownKeys = new LinkedHashSet<>();

    /**
     * Jackson's catch-all for unbound top-level package keys; records the key name and drops the
     * value.
     *
     * @param name
     *            the unbound JSON key
     * @param value
     *            its value — deliberately unread; only the key's presence is diagnostic
     */
    @com.fasterxml.jackson.annotation.JsonAnySetter
    void recordUnknownKey(String name, @Nullable Object value)
    {
        unknownKeys.add(name);
    }


    /**
     * The top-level JSON keys of this package that bound to no modelled property.
     *
     * @return an unmodifiable view of the collected unknown keys (empty for every shipped package)
     */
    public SequencedSet<String> getUnknownKeys()
    {
        return java.util.Collections.unmodifiableSequencedSet(unknownKeys);
    }

}
