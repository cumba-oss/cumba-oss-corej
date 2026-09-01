package net.cumba.cdisc.core.model;

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

    /**
     * Every top-level JSON key of this package that bound to no modelled property, in encounter
     * order.
     *
     * <p>
     * The loader's mapper runs with {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled, so an unknown
     * package key would otherwise vanish without trace. This set only <em>records</em> them; what
     * rejects is {@code RulePackageLoader.validateNoPackageSeverityThreshold}, which turns a
     * package-level <b>run severity threshold</b> into a load failure.
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
