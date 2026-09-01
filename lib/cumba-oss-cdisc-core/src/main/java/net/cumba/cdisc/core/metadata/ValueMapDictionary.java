package net.cumba.cdisc.core.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * T1 — an external medical-dictionary modelled as a simple <b>value-map</b>, loaded from the house
 * JSON format under {@code lib/corej-cdisc-core/dictionaries/}<i>type</i>{@code .json}. A
 * dictionary reduces every dictionary conformance check to a lookup:
 *
 * <ul>
 * <li><b>membership</b> — the term (upper-cased) is a key of {@code levels[termType]};</li>
 * <li><b>case</b> — {@code levels[termType][upper(term)]} equals the term verbatim (the value holds
 * the preferred case);</li>
 * <li><b>hierarchy-path</b> — the parent is in {@code hierarchy[term]};</li>
 * <li><b>code&harr;decode pair</b> — some registry / attribute map carries {@code map[code] ==
 * decode};</li>
 * <li><b>per-term attribute</b> — {@code attributes[attr][upper(term)]}.</li>
 * </ul>
 *
 * <p>
 * Small dummy maps are checked into the repository so the dictionary conformance rules execute and
 * parity-test now without any licensed dictionary data; real dictionary data drops in behind the
 * same file format later. The identical files are read by the Python reference engine's
 * {@code ValueMapValidator} shim so both engines validate against the same data.
 * </p>
 *
 * <p>
 * Instances are immutable. Since D-TA-3 / Fix #266 the hierarchy, pair and decode lookups are
 * <b>flag-aware</b>: each keeps a case-sensitive primary path (comparing the dictionary's
 * as-authored entries verbatim) and a case-folded fallback path serving
 * {@code case_sensitive: false}, backed by dual pre-folded indexes so both stay O(1) per probe.
 * Term membership keeps its two established primitives: {@link #isValidTerm} (folded probe over the
 * folded {@code levels} index) and {@link #caseMatches} (folded probe, verdict compared against the
 * stored preferred-case value verbatim — the sensitive path).
 * </p>
 */
public final class ValueMapDictionary
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String type;

    private final Map<String, Map<String, String>> levels;

    /** Hierarchy exactly as authored in the dictionary file: term &rarr; ancestor terms. */
    private final Map<String, List<String>> hierarchy;

    /** Case-folded hierarchy index (keys and ancestor values upper-cased). */
    private final Map<String, List<String>> hierarchyFolded;

    /** Pair registries exactly as authored: registry &rarr; code &rarr; decode. */
    private final Map<String, Map<String, String>> pairs;

    /** Pair registries with the code keys upper-cased (decodes verbatim). */
    private final Map<String, Map<String, String>> pairsFolded;

    /** Attribute maps exactly as authored: attribute &rarr; term/code &rarr; value. */
    private final Map<String, Map<String, String>> attributes;

    /** Attribute maps with the term/code keys upper-cased (values verbatim). */
    private final Map<String, Map<String, String>> attributesFolded;

    private ValueMapDictionary(String type, Map<String, Map<String, String>> levels,
            Map<String, List<String>> hierarchy, Map<String, Map<String, String>> pairs,
            Map<String, Map<String, String>> attributes)
    {
        this.type = type;
        this.levels = levels;
        this.hierarchy = hierarchy;
        this.hierarchyFolded = foldHierarchy(hierarchy);
        this.pairs = pairs;
        this.pairsFolded = foldKeys(pairs);
        this.attributes = attributes;
        this.attributesFolded = foldKeys(attributes);
    }


    /** The dictionary type (e.g. {@code "meddra"}). */
    public String getType()
    {
        return type;
    }


    /** Loads a dictionary from a house-format JSON file on disk. */
    public static ValueMapDictionary load(Path file) throws IOException
    {
        try (InputStream in = Files.newInputStream(file))
        {
            return parse(MAPPER.readTree(in));
        }
    }


    /** Parses a dictionary from an already-read house-format JSON document. */
    public static ValueMapDictionary parse(JsonNode root)
    {
        String t = root.hasNonNull("type") ? root.get("type").asText() : "";
        return new ValueMapDictionary(t, readLevels(root.get("levels")),
                readNestedStringList(root.get("hierarchy")), readNestedStringMap(root.get("pairs")),
                readNestedStringMap(root.get("attributes")));
    }


    /**
     * The {@code levels} index: term keys upper-cased on load (the folded membership index), the
     * values kept verbatim (each value holds the term's preferred case).
     */
    private static Map<String, Map<String, String>> readLevels(@Nullable JsonNode node)
    {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        if (node == null || !node.isObject())
        {
            return out;
        }
        for (Map.Entry<String, JsonNode> outer : node.properties())
        {
            Map<String, String> inner = new LinkedHashMap<>();
            JsonNode innerNode = outer.getValue();
            if (innerNode.isObject())
            {
                for (Map.Entry<String, JsonNode> e : innerNode.properties())
                {
                    inner.put(e.getKey().toUpperCase(Locale.ROOT), e.getValue().asText());
                }
            }
            out.put(outer.getKey(), inner);
        }
        return out;
    }


    /** Nested string map read verbatim — keys and values exactly as authored in the file. */
    private static Map<String, Map<String, String>> readNestedStringMap(@Nullable JsonNode node)
    {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        if (node == null || !node.isObject())
        {
            return out;
        }
        for (Map.Entry<String, JsonNode> outer : node.properties())
        {
            Map<String, String> inner = new LinkedHashMap<>();
            JsonNode innerNode = outer.getValue();
            if (innerNode.isObject())
            {
                for (Map.Entry<String, JsonNode> e : innerNode.properties())
                {
                    inner.put(e.getKey(), e.getValue().asText());
                }
            }
            out.put(outer.getKey(), inner);
        }
        return out;
    }


    /** Hierarchy read verbatim — term keys and ancestor values exactly as authored. */
    private static Map<String, List<String>> readNestedStringList(@Nullable JsonNode node)
    {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (node == null || !node.isObject())
        {
            return out;
        }
        for (Map.Entry<String, JsonNode> outer : node.properties())
        {
            List<String> values = new java.util.ArrayList<>();
            if (outer.getValue().isArray())
            {
                for (JsonNode item : outer.getValue())
                {
                    values.add(item.asText());
                }
            }
            out.put(outer.getKey(), values);
        }
        return out;
    }


    /**
     * The folded twin of a pair/attribute structure: inner keys upper-cased, values verbatim. On a
     * folded-key collision the last entry wins — the same disposition the pre-#266 load-time
     * folding produced.
     */
    private static Map<String, Map<String, String>> foldKeys(Map<String, Map<String, String>> exact)
    {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> outer : exact.entrySet())
        {
            Map<String, String> inner = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : outer.getValue().entrySet())
            {
                inner.put(upper(e.getKey()), e.getValue());
            }
            out.put(outer.getKey(), inner);
        }
        return out;
    }


    /** The folded twin of the hierarchy: term keys and ancestor values upper-cased. */
    private static Map<String, List<String>> foldHierarchy(Map<String, List<String>> exact)
    {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : exact.entrySet())
        {
            out.put(upper(e.getKey()),
                    e.getValue().stream().map(ValueMapDictionary::upper).toList());
        }
        return out;
    }


    private static String upper(String s)
    {
        return s.toUpperCase(Locale.ROOT);
    }


    /** Membership: the term (case-folded) is present in the given level. */
    public boolean isValidTerm(@Nullable String level, @Nullable String term)
    {
        if (term == null)
        {
            return false;
        }
        if (level != null && !level.isEmpty())
        {
            Map<String, String> m = levels.get(level);
            return m != null && m.containsKey(upper(term));
        }
        // No level specified: valid if present in any level.
        for (Map<String, String> m : levels.values())
        {
            if (m.containsKey(upper(term)))
            {
                return true;
            }
        }
        return false;
    }


    /**
     * Case check: the term exactly matches the stored preferred case for its level — the
     * case-SENSITIVE membership primitive (the D-TA-3 default for the membership operations).
     */
    public boolean caseMatches(@Nullable String level, @Nullable String term)
    {
        if (term == null)
        {
            return false;
        }
        Map<String, String> m = level != null && !level.isEmpty() ? levels.get(level) : anyLevel();
        if (m == null)
        {
            return false;
        }
        String preferred = m.get(upper(term));
        return preferred != null && preferred.equals(term);
    }


    private @Nullable Map<String, String> anyLevel()
    {
        return levels.values().stream().findFirst().orElse(null);
    }


    /**
     * Hierarchy-path: {@code parent} is an ancestor of {@code term} on the dictionary's primary
     * hierarchy path (the stored list holds all such ancestors). When {@code caseSensitive} (the
     * D-TA-3 default) both operands are compared verbatim against the as-authored hierarchy;
     * {@code case_sensitive: false} folds both sides.
     */
    public boolean onHierarchyPath(@Nullable String term, @Nullable String parent,
            boolean caseSensitive)
    {
        if (term == null || parent == null)
        {
            return false;
        }
        List<String> parents = caseSensitive ? hierarchy.get(term)
                : hierarchyFolded.get(upper(term));
        return parents != null && parents.contains(caseSensitive ? parent : upper(parent));
    }


    /**
     * code&harr;decode pairing: whether some registry (in {@code pairs}) or attribute map (in
     * {@code attributes}) maps {@code code} to {@code decode}. When {@code reg} names a registry it
     * is consulted first; otherwise every pair / attribute map is searched. When
     * {@code caseSensitive} (the D-TA-3 default) code and decode both compare verbatim; with
     * {@code case_sensitive: false} both compare case-folded (pre-#266 the code folded while the
     * decode compared verbatim — an asymmetry no rule authored and no flag could express).
     */
    public boolean codeDecodePair(@Nullable String reg, @Nullable String code,
            @Nullable String decode, boolean caseSensitive)
    {
        if (code == null || decode == null)
        {
            return false;
        }
        Map<String, Map<String, String>> p = caseSensitive ? pairs : pairsFolded;
        Map<String, Map<String, String>> a = caseSensitive ? attributes : attributesFolded;
        String key = caseSensitive ? code : upper(code);
        // The named registry is a *preference*: consult it first, but on a miss fall through to
        // scanning every other pairs registry AND the attributes maps (M1). Mirrors Python
        // is_valid_code_term_pair, which always iterates `pairs` + `attributes`.
        if (reg != null && p.containsKey(reg)
                && decodeMatches(decode, p.get(reg).get(key), caseSensitive))
        {
            return true;
        }
        for (Map<String, String> m : p.values())
        {
            if (decodeMatches(decode, m.get(key), caseSensitive))
            {
                return true;
            }
        }
        for (Map<String, String> m : a.values())
        {
            if (decodeMatches(decode, m.get(key), caseSensitive))
            {
                return true;
            }
        }
        return false;
    }


    private static boolean decodeMatches(String decode, @Nullable String stored,
            boolean caseSensitive)
    {
        return stored != null
                && (caseSensitive ? stored.equals(decode) : stored.equalsIgnoreCase(decode));
    }


    /**
     * E8 — decode-presence: whether some registry (in {@code pairs}) or attribute map (in
     * {@code attributes}) holds <em>any</em> decode for {@code code} — the {@code containsKey}
     * counterpart of {@link #codeDecodePair} minus the decode-equality. When {@code reg} names a
     * {@code pairs} registry it is consulted first; otherwise every {@code pairs} / {@code
     * attributes} map is searched. When {@code caseSensitive} (the D-TA-3 default) the code
     * compares verbatim; {@code case_sensitive: false} folds it. A dummy dictionary with no
     * {@code pairs}/{@code attributes} always returns {@code false} (parity holds; real data drops
     * in behind the same format).
     */
    public boolean hasDecode(@Nullable String reg, @Nullable String code, boolean caseSensitive)
    {
        if (code == null)
        {
            return false;
        }
        Map<String, Map<String, String>> p = caseSensitive ? pairs : pairsFolded;
        Map<String, Map<String, String>> a = caseSensitive ? attributes : attributesFolded;
        String key = caseSensitive ? code : upper(code);
        if (reg != null && p.containsKey(reg) && p.get(reg).containsKey(key))
        {
            return true;
        }
        for (Map<String, String> m : p.values())
        {
            if (m.containsKey(key))
            {
                return true;
            }
        }
        for (Map<String, String> m : a.values())
        {
            if (m.containsKey(key))
            {
                return true;
            }
        }
        return false;
    }


    /**
     * Per-term attribute lookup (e.g. NEOPLASM benign/malignant class), or {@code null}. The probe
     * stays case-folded — no operation exposes this lookup today, and its established contract is
     * the folded index.
     */
    public @Nullable String termAttribute(@Nullable String attr, @Nullable String term)
    {
        if (attr == null || term == null)
        {
            return null;
        }
        Map<String, String> m = attributesFolded.get(attr);
        return m != null ? m.get(upper(term)) : null;
    }

}
