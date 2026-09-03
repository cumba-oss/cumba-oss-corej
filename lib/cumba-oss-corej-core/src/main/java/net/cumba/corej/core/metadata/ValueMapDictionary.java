package net.cumba.corej.core.metadata;

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
import java.util.zip.GZIPInputStream;
import org.jspecify.annotations.Nullable;

/**
 * T1 — an external medical-dictionary modelled as a simple <b>value-map</b>, loaded from the house
 * JSON format under {@code lib/cumba-oss-corej-core/dictionaries/}<i>type</i>{@code .json}. A
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
 * Small dummy maps are checked into the repository under
 * {@code lib/cumba-oss-corej-core/dictionaries/} so the dictionary conformance rules execute and
 * the {@code .cdt} scenarios discriminate without any licensed dictionary data present; real
 * dictionary data is installed by the operator behind the same file format
 * ({@code plans/PLAN-dictionary-seeder.md}).
 * </p>
 *
 * <p>
 * <b>Readers of this format.</b> There are exactly two, both in this module: this class, and
 * {@code DictionaryValidationTest.caseContractViolations}, which walks the same four sections
 * independently to audit the preferred-case contract. Nothing outside {@code cumba-oss-corej-core}
 * reads it. (Before {@code 987ab2ba1} this javadoc claimed the files were also read by a Python
 * reference engine's {@code ValueMapValidator} shim; that module was renamed away and no such
 * reader exists — the claim wrongly implied a cross-engine constraint on the format.)
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

    /**
     * Where a dictionary came from and which release it is — written by the dictionary installer,
     * read back for the report's {@code *_Version} conformance fields.
     *
     * <p>
     * All three components are {@code null} for a file that declares none (every hand-authored
     * fixture, and every file predating the installer). Provenance is <b>reported, never
     * checked</b>: no conformance finding compares a declared define.xml
     * {@code ExternalCodeList/@Version} against it.
     * </p>
     *
     * @param version
     *            the vendor's own release identifier, verbatim ({@code "27.0"},
     *            {@code "2026.07.06"}, {@code "SEP_2020"}, {@code "4Aug2026"}) — never parsed,
     *            ordered or normalised, only compared for equality
     * @param source
     *            the URL or local path the installer read
     * @param retrieved
     *            the ISO-8601 date the installer fetched it
     */
    public record Provenance(@Nullable String version, @Nullable String source,
            @Nullable String retrieved)
    {

        /** The provenance of a file that declares none. */
        public static final Provenance NONE = new Provenance(null, null, null);
    }

    private final String type;

    private final Provenance provenance;

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

    private ValueMapDictionary(String type, Provenance provenance,
            Map<String, Map<String, String>> levels, Map<String, List<String>> hierarchy,
            Map<String, Map<String, String>> pairs, Map<String, Map<String, String>> attributes)
    {
        this.type = type;
        this.provenance = provenance;
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


    /** The dictionary's recorded provenance, or {@link Provenance#NONE} when it declares none. */
    public Provenance getProvenance()
    {
        return provenance;
    }


    /** The vendor release identifier this dictionary was built from, or {@code null}. */
    public @Nullable String getVersion()
    {
        return provenance.version();
    }


    /**
     * Whether this dictionary carries any usable content at all — at least one term, ancestor list,
     * pair or attribute.
     *
     * <p>
     * <b>Why this exists.</b> Every reader below degrades a missing, {@code null}, array-shaped or
     * misspelled section to an empty map <em>silently</em>, so a truncated download, a partly
     * written file or a converter bug parses cleanly and answers nothing. Such a file would
     * otherwise still make {@link RuntimeDictionaryProvider#isAvailable} return {@code true},
     * bypassing the engine's eager SKIP arm entirely: the membership rules would then find every
     * term invalid and fire on <em>every row</em>, while the pair and decode rules would report
     * {@code noViolation} vacuously. {@link RuntimeDictionaryProvider#loadDirectory} therefore
     * treats a dictionary that fails this test as <b>not loaded</b>, so the established
     * "unavailable ⇒ SKIP, never false-pass" contract catches it whatever its provenance.
     * </p>
     */
    public boolean hasContent()
    {
        // The hierarchy check mirrors anyEntry: a key mapping to an empty ancestor list answers
        // no hierarchy probe, so {"hierarchy":{"X":[]}} is no more content than {"levels":{"X":{}}}
        // — counting it would register the dictionary as available and bypass the SKIP arm.
        return anyEntry(levels) || hierarchy.values().stream().anyMatch(l -> !l.isEmpty())
                || anyEntry(pairs) || anyEntry(attributes);
    }


    /** Whether a nested section holds at least one inner entry (an empty inner map is not one). */
    private static boolean anyEntry(Map<String, Map<String, String>> section)
    {
        return section.values().stream().anyMatch(inner -> !inner.isEmpty());
    }


    /**
     * Loads a dictionary from a house-format JSON file on disk. A file whose name ends {@code .gz}
     * is decompressed on the way in, so a large bundle can ship as {@code <type>.json.gz} without a
     * separate unpack step.
     */
    public static ValueMapDictionary load(Path file) throws IOException
    {
        try (InputStream in = open(file))
        {
            return parse(MAPPER.readTree(in));
        }
    }


    /** Opens {@code file}, wrapping it in a GZIP stream when its name ends {@code .gz}. */
    private static InputStream open(Path file) throws IOException
    {
        Path name = file.getFileName();
        InputStream raw = Files.newInputStream(file);
        if (name == null || !name.toString().endsWith(".gz"))
        {
            return raw;
        }
        try
        {
            return new GZIPInputStream(raw);
        }
        catch (IOException e)
        {
            // The wrapper never took ownership, so this stream would otherwise leak on a file
            // named .gz that is not gzip.
            raw.close();
            throw e;
        }
    }


    /** Parses a dictionary from an already-read house-format JSON document. */
    public static ValueMapDictionary parse(JsonNode root)
    {
        String t = root.hasNonNull("type") ? root.get("type").asText() : "";
        return new ValueMapDictionary(t, readProvenance(root), readLevels(root.get("levels")),
                readNestedStringList(root.get("hierarchy")), readNestedStringMap(root.get("pairs")),
                readNestedStringMap(root.get("attributes")));
    }


    /** Reads the optional {@code version} / {@code source} / {@code retrieved} keys. */
    private static Provenance readProvenance(JsonNode root)
    {
        String version = text(root, "version");
        String source = text(root, "source");
        String retrieved = text(root, "retrieved");
        return version == null && source == null && retrieved == null ? Provenance.NONE
                : new Provenance(version, source, retrieved);
    }


    /** A non-blank string field, or {@code null}. */
    private static @Nullable String text(JsonNode root, String field)
    {
        if (!root.hasNonNull(field))
        {
            return null;
        }
        String value = root.get(field).asText();
        return value.isBlank() ? null : value;
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
