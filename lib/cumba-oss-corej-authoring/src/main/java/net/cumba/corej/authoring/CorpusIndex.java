package net.cumba.corej.authoring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What {@link RuleInsight#related} looks rules up in: the authority references of every authored
 * check (built from the corpus itself, so it works in both twins), plus — where the repository
 * carries them — the equivalence register ({@code registers/rule-inventory/rule-equivalence.yaml},
 * {@code EQ-} / {@code REL-} classes) and the minted-rule parentage
 * ({@code registers/derivation/minted-rule-parentage.tsv}). Immutable once built; the two registers
 * are added through {@link #withEquivalenceRegister} / {@link #withParentage}, which return a new
 * index.
 */
public final class CorpusIndex
{

    /**
     * An authority reference: {@code (Origin, Rule_Identifier.Id)} of a {@code Standards} block.
     */
    public record ReferenceKey(String origin, String ruleId)
    {

        @Override
        public String toString()
        {
            return origin + " " + ruleId;
        }
    }


    /** One class of the equivalence register. */
    public record RegisterClass(String id, @Nullable String kind, List<String> members,
            boolean equivalence)
    {

        public RegisterClass
        {
            members = List.copyOf(members);
        }
    }


    /** One row of the minted-rule parentage register. */
    public record Parentage(String child, String parent, String kind, String reason)
    {
    }

    private final Map<ReferenceKey, List<String>> byReference;

    private final Map<String, List<ReferenceKey>> referencesByRule;

    private final Map<String, List<RegisterClass>> classesByRule;

    private final Map<String, List<Parentage>> parentageByRule;

    private CorpusIndex(Map<ReferenceKey, List<String>> aByReference,
            Map<String, List<ReferenceKey>> aReferencesByRule,
            Map<String, List<RegisterClass>> aClassesByRule,
            Map<String, List<Parentage>> aParentageByRule)
    {
        // Frozen on the way in — maps AND their lists (pack-2 review 8a-A F6: a shallow copy let
        // the next with…() append to this index's lists).
        byReference = frozen(aByReference);
        referencesByRule = frozen(aReferencesByRule);
        classesByRule = frozen(aClassesByRule);
        parentageByRule = frozen(aParentageByRule);
    }


    private static <K, V> Map<K, List<V>> frozen(Map<K, List<V>> aMap)
    {
        Map<K, List<V>> out = new LinkedHashMap<>();
        aMap.forEach((k, v) -> out.put(k, List.copyOf(v)));
        return Collections.unmodifiableMap(out);
    }


    /** A mutable copy of a frozen map — new lists, so adding to them leaves the source alone. */
    private static <K, V> Map<K, List<V>> thawed(Map<K, List<V>> aMap)
    {
        Map<K, List<V>> out = new LinkedHashMap<>();
        aMap.forEach((k, v) -> out.put(k, new ArrayList<>(v)));
        return out;
    }


    /**
     * Index the authored checks by their authority references.
     *
     * @param aChecks
     *            {@code Core.Id} to check node (the loaded {@code rules-src/checks} tree)
     * @return the index
     */
    public static CorpusIndex of(Map<String, JsonNode> aChecks)
    {
        Map<ReferenceKey, List<String>> byRef = new HashMap<>();
        Map<String, List<ReferenceKey>> refsByRule = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : aChecks.entrySet())
        {
            List<ReferenceKey> refs = referencesOf(e.getValue());
            refsByRule.put(e.getKey(), refs);
            for (ReferenceKey ref : refs)
            {
                byRef.computeIfAbsent(ref, _ -> new ArrayList<>()).add(e.getKey());
            }
        }
        return new CorpusIndex(byRef, refsByRule, Map.of(), Map.of());
    }


    /**
     * The references a check's {@code Standards} block names, distinct, in authored order.
     *
     * @param aCheck
     *            the check node
     * @return the references
     */
    public static List<ReferenceKey> referencesOf(JsonNode aCheck)
    {
        // Read off the references themselves, walked in AuthoredRuleAssembler.walkReferences'
        // order — not cut back out of its locator key, whose '|' fields an id may contain and
        // whose '#n' tiebreak sits on the LAST field (pack-2 review 8a-A F6).
        List<ReferenceKey> out = new ArrayList<>();
        JsonNode standards = aCheck.get("Standards");
        if (standards == null || !standards.isArray())
        {
            return out;
        }
        for (JsonNode authority : standards)
        {
            JsonNode stds = authority.get("Standards");
            if (stds == null)
            {
                continue;
            }
            for (JsonNode std : stds)
            {
                JsonNode refs = std.get("References");
                if (refs == null)
                {
                    continue;
                }
                for (JsonNode ref : refs)
                {
                    JsonNode rid = ref.get("Rule_Identifier");
                    String ruleId = rid == null ? "" : text(rid.get("Id"));
                    if (!ruleId.isEmpty())
                    {
                        ReferenceKey key = new ReferenceKey(text(ref.get("Origin")), ruleId);
                        if (!out.contains(key))
                        {
                            out.add(key);
                        }
                    }
                }
            }
        }
        return out;
    }


    private static String text(@Nullable JsonNode aNode)
    {
        return aNode == null || aNode.isNull() ? "" : aNode.asText();
    }


    /**
     * Add the equivalence register.
     *
     * @param aYaml
     *            the register text ({@code classes:} with {@code EQ-} entries, {@code relations:}
     *            with {@code REL-} entries; each has {@code id}, {@code members}, and a
     *            {@code kind} or {@code relation})
     * @return a new index carrying the classes
     * @throws JsonProcessingException
     *             when the register is not valid YAML
     */
    public CorpusIndex withEquivalenceRegister(String aYaml) throws JsonProcessingException
    {
        JsonNode root = AuthoredRuleAssembler.readYamlTree(aYaml);
        Map<String, List<RegisterClass>> byRule = thawed(classesByRule);
        addClasses(root.get("classes"), true, byRule);
        addClasses(root.get("relations"), false, byRule);
        return new CorpusIndex(byReference, referencesByRule, byRule, parentageByRule);
    }


    private static void addClasses(@Nullable JsonNode aList, boolean aEquivalence,
            Map<String, List<RegisterClass>> aByRule)
    {
        if (aList == null || !aList.isArray())
        {
            return;
        }
        for (JsonNode entry : aList)
        {
            String id = entry.path("id").asText("");
            List<String> members = new ArrayList<>();
            for (JsonNode m : entry.path("members"))
            {
                members.add(m.asText());
            }
            JsonNode kindNode = entry.has("kind") ? entry.get("kind") : entry.get("relation");
            String kind = kindNode == null || kindNode.isNull() ? null : kindNode.asText();
            RegisterClass cls = new RegisterClass(id, kind, members, aEquivalence);
            for (String member : members)
            {
                aByRule.computeIfAbsent(member, _ -> new ArrayList<>()).add(cls);
            }
        }
    }


    /**
     * Add the minted-rule parentage register.
     *
     * @param aTsv
     *            the register text: {@code #} comment lines, then
     *            {@code child<TAB>parent<TAB>kind<TAB>…<TAB>reason} rows (the reason is the last
     *            column)
     * @return a new index carrying the parentage
     */
    public CorpusIndex withParentage(String aTsv)
    {
        Map<String, List<Parentage>> byRule = thawed(parentageByRule);
        for (String line : aTsv.split("\\R", -1))
        {
            if (line.isBlank() || line.startsWith("#"))
            {
                continue;
            }
            String[] cols = line.split("\t", -1);
            if (cols.length < 3)
            {
                continue;
            }
            Parentage p = new Parentage(cols[0].strip(), cols[1].strip(), cols[2].strip(),
                    cols[cols.length - 1].strip());
            byRule.computeIfAbsent(p.child(), _ -> new ArrayList<>()).add(p);
            byRule.computeIfAbsent(p.parent(), _ -> new ArrayList<>()).add(p);
        }
        return new CorpusIndex(byReference, referencesByRule, classesByRule, byRule);
    }


    /** The rules whose {@code Standards} name a reference. */
    public List<String> rulesReferencing(ReferenceKey aReference)
    {
        return List.copyOf(byReference.getOrDefault(aReference, List.of()));
    }


    /** The references a rule names (empty for a rule the index does not hold). */
    public List<ReferenceKey> referencesOf(String aCoreId)
    {
        return List.copyOf(referencesByRule.getOrDefault(aCoreId, List.of()));
    }


    /** The register classes a rule is a member of. */
    public Collection<RegisterClass> classesOf(String aCoreId)
    {
        return List.copyOf(classesByRule.getOrDefault(aCoreId, List.of()));
    }


    /** The parentage rows naming a rule, as child or parent. */
    public Collection<Parentage> parentageOf(String aCoreId)
    {
        return List.copyOf(parentageByRule.getOrDefault(aCoreId, List.of()));
    }


    /** The number of indexed rules. */
    public int size()
    {
        return referencesByRule.size();
    }
}
