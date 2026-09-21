package net.cumba.corej.core.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Column-presence requirements — {@code Requirements.Variables}
 * ({@code plans/done/PLAN-scope-requirements-split.md} &#167;4.3). Successor of the retired
 * {@code Scope.Variables} block, which said the same thing in a field named for selection.
 *
 * <h2>Semantics</h2>
 * <ul>
 * <li>{@code All} — every entry must be present. Byte-for-byte the former
 * {@code Scope.Variables.Include}.</li>
 * <li>{@code Any} — one or more <b>groups</b>, ANDed with each other; a group is satisfied when at
 * least one of its entries is present (an AND of ORs). The flat authored spelling
 * ({@code Any: ["A","B"]}) is ONE group; the nested spelling ({@code Any: [["A","B"],["C","D"]]})
 * declares several. A group is unmet only when <em>every</em> entry in it is absent, so the
 * mismatch reason names the group — no single entry is at fault.</li>
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
 * <h2>The type suffix ({@code plans/PLAN-variable-type-requirements.md})</h2>
 * <p>
 * An {@code All} or {@code Any} entry may end in <b>{@code :N}</b> / <b>{@code :C}</b> (or the
 * equivalent {@code :Num} / {@code :Char}, case-insensitive — ruling D7), demanding that the column
 * is numeric or character as well as present. An unmet type is a {@code SKIPPED} naming both types,
 * never a finding.
 * </p>
 * <ul>
 * <li>The type is the <b>dataset's</b> own declared type — {@code DataTableColumnMeta.getType()},
 * classified by the one shipped mapping {@code ColumnTypeGate.kindOf}. ⛔ Never the CDISC Library's
 * or the Define-XML's (ruling D6); the rule language names those apart as
 * {@code var_type("LIBRARY")} / {@code var_type("DEFINE")}.</li>
 * <li>A type the mapping does not classify does <b>not</b> block (ruling D2) — the entry fails only
 * on a positively contradicted type.</li>
 * <li>A qualified entry takes the qualifier's dataset; for a split domain, the type every member
 * carrying the column agrees on, and none when they disagree (ruling D8).</li>
 * <li>A variable delivered by the SUPP-QNAM pivot takes that {@code SUPPxx} table's {@code QVAL}
 * type, since that is how its values arrive (ruling D9).</li>
 * <li>⛔ A suffix in {@code None} is a <b>load error</b> (gate R9, ruling D1): it would mean "no
 * variable of that type may be present", which a variable of the OTHER type also satisfies.</li>
 * <li>⭐ An entry with no suffix means exactly what it always did, byte-for-byte, message text
 * included (ruling D5).</li>
 * </ul>
 *
 * <p>
 * ⚠⚠ <b>Corrected 2026-09-21.</b> This javadoc said {@code None} ships with <b>zero</b> corpus
 * carriers, "so every test of it is a hand-authored gate test". That is <b>false</b>, and the claim
 * propagated into a plan and nearly into the authoritative specification before it was measured: at
 * HEAD <b>43</b> {@code rules-src} rules carry a {@code None} facet and <b>221</b> shipped rule
 * instances across <b>25</b> packages do ({@code FDA-SD0089-A} →
 * {@code {"None":["TEDUR"],"All":["TEENRL"]}}). What genuinely has zero carriers is the <em>type
 * suffix</em>, which is why rejecting it in {@code None} costs nothing — and that, not the facet,
 * is the thing whose tests are all hand-authored.
 * </p>
 */
@Data
@NoArgsConstructor
public class VariableRequirement
{

    /** Every entry must be present — the former {@code Scope.Variables.Include}. */
    @JsonProperty("All")
    private @Nullable List<String> all;

    /**
     * The canonical {@code Any} groups — an AND of ORs. A flat authored {@code Any} is ONE group;
     * each group needs two or more distinct entries (loader gate R4, rulings D2/D3). {@code null}
     * when the facet is absent or authored {@code ~}; an authored {@code []} is an <b>empty</b>
     * list here — zero groups, which R4 rejects as unsatisfiable.
     *
     * <p>
     * ⚠ JSON binding goes through {@link #readAny}/{@link #writeAny} (see {@link AnyGroupsJson}),
     * not through this field: the parse must also set {@link #anyMixedShape}, which a field-level
     * {@code JsonDeserializer} cannot reach.
     * </p>
     */
    @JsonIgnore
    private @Nullable List<List<String>> anyGroups;

    /**
     * Whether the authored {@code Any} mixed flat entries with groups
     * ({@code Any: ["A", ["B","C"]]}). Ruling D2 makes that a load error, and it is a <b>shape</b>
     * error: loader gate R4 reads this flag to report <i>"mixed shape"</i> rather than blaming a
     * one-entry group the author never typed (which is D3's message about a different mistake).
     *
     * <p>
     * A parse-time diagnostic like {@link #unknownKeys}: never serialised, excluded from
     * {@code equals} / {@code hashCode} / {@code toString}, and not carried by wildcard expansion —
     * R4 runs at load time, before any expansion.
     * </p>
     */
    @JsonIgnore
    @lombok.Setter(lombok.AccessLevel.NONE)
    @lombok.EqualsAndHashCode.Exclude
    @lombok.ToString.Exclude
    private boolean anyMixedShape;

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
     * The JSON read half of the {@code Any} facet — flat or nested, per element (see
     * {@link AnyGroupsJson}). Explicitly annotated so the key {@code "Any"} stays a <em>known</em>
     * property and never reaches {@link #recordUnknownKey}.
     *
     * @param node
     *            the authored {@code Any} value, or {@code null} / a JSON null for {@code Any: ~}
     */
    @JsonProperty("Any")
    void readAny(@Nullable JsonNode node)
    {
        AnyGroupsJson.Parsed parsed = AnyGroupsJson.parse(node);
        this.anyGroups = parsed.groups();
        this.anyMixedShape = parsed.mixedShape();
    }


    /**
     * The JSON write half of the {@code Any} facet: one group serialises <b>flat</b>, several
     * serialise nested — see {@link AnyGroupsJson#write}.
     *
     * @return the JSON value for {@code "Any"}, or {@code null} when there are no groups
     */
    @JsonProperty("Any")
    @Nullable
    Object writeAny()
    {
        return AnyGroupsJson.write(anyGroups);
    }


    /**
     * Every entry of every group, flattened, in group order then entry order. For lint, census and
     * presence checks ONLY — anything that reasons about the <em>disjunction</em> must iterate
     * {@code getAnyGroups()} instead ({@code ScopeMatcher.describeAnyLeg},
     * {@code WildcardExpander}'s substitution, loader gate R4).
     *
     * <p>
     * ⚠⚠ Preserves EVERY entry verbatim — {@code null}s and blanks included. Loader gate R3 exists
     * to find empty entries and reads this; a filtering {@code anyUnion()} would make R3 silently
     * stop checking.
     * </p>
     *
     * @return an unmodifiable flat view of the groups; never {@code null}, empty when there are no
     *         groups
     */
    public List<String> anyUnion()
    {
        List<List<String>> groups = anyGroups;
        if (groups == null || groups.isEmpty())
        {
            return List.of();
        }
        List<String> union = new ArrayList<>();
        for (List<String> group : groups)
        {
            union.addAll(group);
        }
        return Collections.unmodifiableList(union);
    }


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
