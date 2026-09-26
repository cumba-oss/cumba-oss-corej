package net.cumba.corej.core;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.model.KeyHint;

/**
 * Adds the join-key declaration the loader demands
 * ({@code RulePackageLoader.validateJoinKeyDeclarations}, {@code PLAN-rule-unknown-keys-gate} §5.7)
 * to a test fixture that keys a {@code Match_Datasets} entry — so a test whose subject is the join
 * mechanism (a filter, a flag, an absent joined dataset, a {@code Join_Type}) keeps that subject
 * and does not have to hand-author the declaration the corpus lint enforces on every real rule.
 *
 * <p>
 * The declaration is the lint's shape: an ordinary entry gets its first key bare in
 * {@code Requirements.Variables.All} and one {@code All_Or_None} group per key column holding the
 * bare key and {@code NAME.RIGHT} (one group per bare key across the rule's ordinary entries); a
 * {@code Child} entry gets every key bare in {@code All}; an expansion template its first bare key.
 * Only what is missing is added; nothing the fixture authored is removed — and nothing is
 * overwritten or repaired: a {@code Requirements} / {@code Variables} that is not an object, an
 * {@code All} / {@code All_Or_None} that is not an array, a duplicate key, or a sibling key in
 * {@code Variables} that is a near miss of a facet this helper would add ({@code Al}, {@code
 * All_or_None} — adding the facet would make the loader's hint for the typo disappear) each fail
 * loudly with an {@link IllegalArgumentException}, so the fixture says what the test thinks it
 * says.
 * </p>
 *
 * <p>
 * ⚠ The declaration is NOT inert at run level. A qualified entry ({@code DM.USUBJID}) is decided
 * against the foreign dataset by {@code ScopeMatcher} during study classification AND by
 * {@code RuleRunner.execute} itself (its qualified-scope gate, Fix #124): with the joined dataset
 * absent the rule SKIPs by requirement — ruled class P8 of {@code PLAN-join-key-authoring-gate} —
 * and with a plain-lambda resolver it SKIPs as undecidable ("the dataset resolver cannot enumerate
 * other datasets"). A test that must reach the join mechanism therefore supplies the joined dataset
 * through an inventory-aware resolver ({@code RealTables.inventoryOf}); a test whose subject is the
 * absent-dataset behaviour past that gate uses a dotted-only read with no {@code Match_Datasets}.
 * </p>
 */
public final class KeyedJoinFixtures
{

    /** Strict about duplicate keys, as the loader's mapper is — a fixture is not repaired here. */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private static final String ALL = "All";

    private static final String ALL_OR_NONE = "All_Or_None";

    private KeyedJoinFixtures()
    {
    }


    /**
     * The fixture with the declaration added: a package ({@code {"rules": {...}}}) or a single rule
     * object.
     */
    public static String declared(String json)
    {
        try
        {
            JsonNode root = MAPPER.readTree(json);
            if (root.isObject() && root.has("rules") && root.get("rules").isObject())
            {
                root.get("rules").forEach(rule -> declare((ObjectNode) rule));
            }
            else if (root.isObject())
            {
                declare((ObjectNode) root);
            }
            return MAPPER.writeValueAsString(root);
        }
        catch (java.io.IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }


    /** Adds the missing declaration in place. */
    public static void declare(ObjectNode rule)
    {
        JsonNode joins = rule.get("Match_Datasets");
        if (joins == null || !joins.isArray())
        {
            return;
        }
        List<String> all = new ArrayList<>();
        Map<String, Set<String>> groups = new LinkedHashMap<>(); // bare key -> members
        for (JsonNode md : joins)
        {
            if (!md.isObject() || !md.has("Keys") || !md.get("Keys").isArray()
                    || md.get("Keys").isEmpty() || !md.hasNonNull("Name"))
            {
                continue;
            }
            String name = md.get("Name").asText();
            List<String> left = new ArrayList<>();
            List<String> right = new ArrayList<>();
            for (JsonNode k : md.get("Keys"))
            {
                if (k.isTextual())
                {
                    left.add(k.asText());
                    right.add(k.asText());
                }
                else if (k.isObject() && k.hasNonNull("left") && k.hasNonNull("right"))
                {
                    left.add(k.get("left").asText());
                    right.add(k.get("right").asText());
                }
            }
            if (left.isEmpty())
            {
                continue;
            }
            boolean template = name.startsWith("&")
                    || left.stream().anyMatch(k -> k.startsWith("&"))
                    || right.stream().anyMatch(k -> k.startsWith("&"));
            if (template)
            {
                if (!left.get(0).startsWith("&"))
                {
                    all.add(left.get(0));
                }
                continue;
            }
            if (md.path("Child").asBoolean(false))
            {
                all.addAll(left);
                continue;
            }
            all.add(left.get(0));
            for (int i = 0; i < left.size(); i++)
            {
                String bare = left.get(i);
                groups.computeIfAbsent(fold(bare), k -> new LinkedHashSet<>(List.of(bare)))
                        .add(name + "." + right.get(i));
            }
        }
        if (all.isEmpty() && groups.isEmpty())
        {
            return;
        }
        ObjectNode requirements = objectAt(rule, "Requirements");
        ObjectNode vars = objectAt(requirements, "Variables");
        refuseNearMissSiblings(vars, all.isEmpty() ? null : ALL,
                groups.isEmpty() ? null : ALL_OR_NONE);
        ArrayNode allNode = arrayAt(vars, ALL);
        Set<String> have = new LinkedHashSet<>();
        allNode.forEach(n -> have.add(fold(n.asText())));
        for (String key : all)
        {
            if (have.add(fold(key)))
            {
                allNode.add(key);
            }
        }
        if (!groups.isEmpty())
        {
            ArrayNode aon = arrayAt(vars, ALL_OR_NONE);
            for (Set<String> members : groups.values())
            {
                Set<String> folded = new LinkedHashSet<>();
                members.forEach(m -> folded.add(fold(m)));
                boolean present = false;
                for (JsonNode g : aon)
                {
                    Set<String> existing = new LinkedHashSet<>();
                    g.forEach(m -> existing.add(fold(m.asText())));
                    if (existing.containsAll(folded))
                    {
                        present = true;
                    }
                }
                if (!present)
                {
                    ArrayNode g = aon.addArray();
                    members.forEach(g::add);
                }
            }
        }
    }


    /** The object under {@code key}, created when absent; anything else present fails loudly. */
    private static ObjectNode objectAt(ObjectNode parent, String key)
    {
        JsonNode present = parent.get(key);
        if (present == null || present.isNull())
        {
            return parent.putObject(key);
        }
        if (!present.isObject())
        {
            throw new IllegalArgumentException("fixture's " + key + " is " + present.getNodeType()
                    + ", not an object — not overwriting it: " + present);
        }
        return (ObjectNode) present;
    }


    /** The array under {@code key}, created when absent; anything else present fails loudly. */
    private static ArrayNode arrayAt(ObjectNode parent, String key)
    {
        JsonNode present = parent.get(key);
        if (present == null || present.isNull())
        {
            return parent.putArray(key);
        }
        if (!present.isArray())
        {
            throw new IllegalArgumentException("fixture's " + key + " is " + present.getNodeType()
                    + ", not an array — not overwriting it: " + present);
        }
        return (ArrayNode) present;
    }


    /**
     * Refuses to add a facet beside a sibling key that is a near miss of it — the loader hints the
     * typo only while the object does not already carry the candidate, so adding {@code All} beside
     * {@code Al} would silence the hint the test may be asserting.
     */
    private static void refuseNearMissSiblings(ObjectNode vars, String... facetsToAdd)
    {
        vars.fieldNames().forEachRemaining(sibling ->
        {
            for (String facet : facetsToAdd)
            {
                if (facet != null && !sibling.equals(facet) && (sibling.equalsIgnoreCase(facet)
                        || KeyHint.editDistance(sibling, facet) <= 1))
                {
                    throw new IllegalArgumentException("fixture's Requirements.Variables carries '"
                            + sibling + "', a near miss of '" + facet + "' — adding the facet"
                            + " would mask the loader's hint; declare the fixture by hand");
                }
            }
        });
    }


    private static String fold(String entry)
    {
        return entry.trim().replaceAll("(?i):(N|C|NUM|CHAR)$", "").toUpperCase(Locale.ROOT);
    }
}
