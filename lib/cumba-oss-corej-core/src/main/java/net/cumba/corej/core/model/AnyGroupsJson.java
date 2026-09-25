package net.cumba.corej.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The JSON shape of {@code Requirements.Variables.Any} — flat ({@code ["A","B"]}, ONE group) or
 * nested ({@code [["A","B"],["C","D"]]}, one group per inner array) — mapped to and from the
 * canonical {@code List<List<String>>} of {@link VariableRequirement}'s {@code getAnyGroups()}. ⭐
 * Since {@code PLAN-join-key-pairing} the same shape, parse table and write rule serve
 * {@code Requirements.Variables.All_Or_None} ({@code getAllOrNoneGroups()}) — the two facets are
 * spelled alike by ruling D1b, so one parser keeps them from drifting apart; only the facet name in
 * the two exception messages differs.
 *
 * <p>
 * ⚠ This is deliberately <b>not</b> a Jackson {@code JsonDeserializer}: the parse must also decide
 * {@link Parsed#mixedShape()}, a sibling flag on the model, and a field-level deserializer cannot
 * reach the bean it is populating. {@link VariableRequirement}'s {@code @JsonProperty("Any")}
 * accessor pair delegates here instead.
 * </p>
 *
 * <h2>Parse table (per element — never "first element decides")</h2>
 * <ul>
 * <li>every element a scalar ⇒ the flat spelling: <b>one</b> group holding all entries;</li>
 * <li>every element an array ⇒ the nested spelling: one group per array;</li>
 * <li>a mix ⇒ scalars become singleton groups, arrays become groups, and
 * {@link Parsed#mixedShape()} is set so loader gate R4 can report <i>"mixed shape"</i> (ruling D2)
 * instead of blaming a one-entry group the author never typed (D3's message);</li>
 * <li>{@code []} ⇒ <b>zero</b> groups (an empty list, not one empty group) — R4's unsatisfiable
 * arm, see {@code RequirementsLoadGateTest.emptyAny};</li>
 * <li>{@code null} ⇒ {@code null}, distinct from {@code []};</li>
 * <li>a bare scalar ({@code Any: AESEV}) <b>keeps throwing</b>, as the pre-groups
 * {@code List<String>} binding did — no quiet widening.</li>
 * </ul>
 *
 * <p>
 * Entries are preserved <b>verbatim</b>: a {@code null} element stays a {@code null} entry and a
 * blank stays blank, because loader gate R3 exists to find them — see
 * {@link VariableRequirement#anyUnion()}.
 * </p>
 */
final class AnyGroupsJson
{

    private AnyGroupsJson()
    {
    }

    /**
     * One parse result: the canonical groups plus the shape diagnostic.
     *
     * @param groups
     *            the canonical groups, or {@code null} when the facet was authored {@code ~} /
     *            {@code null}
     * @param mixedShape
     *            whether the authored array mixed scalar entries with group arrays
     */
    record Parsed(@Nullable List<List<String>> groups, boolean mixedShape)
    {
    }

    /** The parse half for {@code Any} — see the class javadoc's table. */
    static Parsed parse(@Nullable JsonNode node)
    {
        return parse(node, "Any");
    }


    /**
     * The parse half for either grouped facet.
     *
     * @param node
     *            the authored value
     * @param facet
     *            the facet's key ({@code Any} / {@code All_Or_None}), for the two messages only
     */
    static Parsed parse(@Nullable JsonNode node, String facet)
    {
        if (node == null || node.isNull())
        {
            return new Parsed(null, false);
        }
        if (!node.isArray())
        {
            // The pre-groups List<String> binding threw on a bare scalar too; keep throwing.
            throw new IllegalArgumentException("Requirements.Variables." + facet
                    + " must be an array — flat entries or nested groups of entries — but got a "
                    + node.getNodeType());
        }
        if (node.isEmpty())
        {
            return new Parsed(List.of(), false); // zero groups, NOT one empty group
        }
        boolean hasScalar = false;
        boolean hasArray = false;
        for (JsonNode element : node)
        {
            if (element.isArray())
            {
                hasArray = true;
            }
            else
            {
                hasScalar = true;
            }
        }
        if (!hasArray)
        {
            return new Parsed(List.of(entriesOf(node, facet)), false); // flat: ONE group
        }
        List<List<String>> groups = new ArrayList<>();
        for (JsonNode element : node)
        {
            groups.add(element.isArray() ? entriesOf(element, facet)
                    : Collections
                            .unmodifiableList(Collections.singletonList(entryOf(element, facet))));
        }
        return new Parsed(Collections.unmodifiableList(groups), hasScalar);
    }


    /** One group's entries, verbatim — nulls and blanks included (gate R3 reads them). */
    private static List<String> entriesOf(JsonNode array, String facet)
    {
        List<String> entries = new ArrayList<>();
        for (JsonNode element : array)
        {
            entries.add(entryOf(element, facet));
        }
        return Collections.unmodifiableList(entries);
    }


    /**
     * One entry. Scalars coerce through {@code asText()} exactly as the former {@code List<String>}
     * binding coerced them; a nested array or object inside a group throws, as it always did.
     */
    private static @Nullable String entryOf(JsonNode element, String facet)
    {
        if (element.isNull())
        {
            return null;
        }
        if (element.isValueNode())
        {
            return element.asText();
        }
        throw new IllegalArgumentException("Requirements.Variables." + facet
                + " group entries must be strings, but got a " + element.getNodeType());
    }


    /**
     * The write half, shared by both grouped facets: {@code null} ⇒ {@code null}; one group ⇒ the
     * <b>flat</b> spelling (so a display/debug serialisation — {@code RulePackageLoader.toJson} —
     * never shows a spuriously nested {@code Any} for the overwhelmingly common one-group case);
     * anything else — zero groups included — round-trips structurally.
     */
    static @Nullable Object write(@Nullable List<List<String>> groups)
    {
        if (groups == null)
        {
            return null;
        }
        if (groups.size() == 1)
        {
            return groups.get(0);
        }
        return groups;
    }

}
