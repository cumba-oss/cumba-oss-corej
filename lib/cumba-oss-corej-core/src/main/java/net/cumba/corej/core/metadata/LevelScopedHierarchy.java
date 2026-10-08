package net.cumba.corej.core.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Runbook W2 DI1 ({@code PLAN-engine-gap-dict-unsupported} §4.1, §5.1 Q1/Q2) — the house format's
 * <b>level-scoped</b> hierarchy section, {@code hierarchy_by_level}:
 *
 * <pre>
 * "hierarchy_by_level": {
 *   "PT":   { "Headache": { "HLT": ["Headaches NEC"], "HLGT": ["Headaches"], "SOC": ["Nervous system disorders"] } },
 *   "PTCD": { "10019211": { "HLTCD": ["10019231"], "HLGTCD": ["10019233"], "SOCCD": ["10029205"] } },
 *   "LLT":  { "Cephalgia": { "PT": ["Headache"] } },
 *   "HLT":  { "Headaches NEC": { "HLGT": ["Headaches"], "SOC": ["Nervous system disorders"] } },
 *   …
 * }
 * </pre>
 *
 * <p>
 * A lookup names the level of the child <em>and</em> of the parent — the levels of the two
 * variables read, never a bare name: a MedDRA PT name can also be an HLT and an LLT name (owner
 * fact, 2026-10-08), so the bare-name {@code hierarchy} map, which merges the levels under one key,
 * would admit a wrong SOC. The parent level is scoped too, so an HLT name on a PT's path does not
 * satisfy a SOC column. Codes sit at their own levels ({@code PTCD}, …) and are code-like
 * identifiers, never words, so they never collide with a name.
 * </p>
 *
 * <p>
 * <b>Chaining.</b> A child level that does not name the requested parent level <em>at all</em> is
 * read through the levels it does name: the MedDRA converter files an LLT under its PT only, and
 * the PT's own entry carries its one primary path, so the chain is exact; every other level carries
 * its ancestors directly (an HLT / HLGT keeps its SET of primary SOCs). A level that names the
 * requested parent level answers from that list alone — a key missing there is off-path, never
 * chained. A chain is bounded ({@link #MAX_CHAIN}) so a malformed, cyclic file cannot loop.
 * </p>
 *
 * <p>
 * <b>Memory.</b> Held as one map per (child level, parent level) pair, keys to immutable lists,
 * every string canonicalised on load (a SOC name recurs on every PT and LLT row); the case-folded
 * twin {@code case_sensitive=false} reads is built on first use only.
 * </p>
 */
final class LevelScopedHierarchy
{

    /** The section of a file that carries none — the legacy arm answers instead. */
    static final LevelScopedHierarchy EMPTY = new LevelScopedHierarchy(Map.of());

    /** The longest chain followed: LLT → PT → HLT → HLGT → SOC is four steps. */
    private static final int MAX_CHAIN = 8;

    /** child level → parent level → child key → parent terms, as authored. */
    private final Map<String, Map<String, Map<String, List<String>>>> exact;

    /** The folded twin (keys and terms upper-cased), built on the first folded lookup. */
    private volatile @Nullable Map<String, Map<String, Map<String, List<String>>>> folded;

    private LevelScopedHierarchy(Map<String, Map<String, Map<String, List<String>>>> aExact)
    {
        exact = aExact;
    }


    /**
     * Reads the section; a missing or non-object section, or one with no non-empty entry, is
     * {@link #EMPTY}. Non-object entries and non-array term lists are skipped, like the other
     * section readers' degradation.
     */
    static LevelScopedHierarchy read(@Nullable JsonNode aNode)
    {
        if (aNode == null || !aNode.isObject())
        {
            return EMPTY;
        }
        Map<String, String> canonical = new HashMap<>();
        Map<String, Map<String, Map<String, List<String>>>> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> level : aNode.properties())
        {
            Map<String, Map<String, List<String>>> byParent = readLevel(level.getValue(),
                    canonical);
            if (!byParent.isEmpty())
            {
                out.put(level.getKey(), byParent);
            }
        }
        return out.isEmpty() ? EMPTY : new LevelScopedHierarchy(out);
    }


    private static Map<String, Map<String, List<String>>> readLevel(JsonNode aLevel,
            Map<String, String> aCanonical)
    {
        Map<String, Map<String, List<String>>> byParent = new LinkedHashMap<>();
        if (!aLevel.isObject())
        {
            return byParent;
        }
        for (Map.Entry<String, JsonNode> entry : aLevel.properties())
        {
            if (!entry.getValue().isObject())
            {
                continue;
            }
            String key = canonical(aCanonical, entry.getKey());
            for (Map.Entry<String, JsonNode> parent : entry.getValue().properties())
            {
                List<String> terms = new ArrayList<>();
                if (parent.getValue().isArray())
                {
                    for (JsonNode term : parent.getValue())
                    {
                        terms.add(canonical(aCanonical, term.asText()));
                    }
                }
                if (!terms.isEmpty())
                {
                    byParent.computeIfAbsent(parent.getKey(), _ -> new HashMap<>()).put(key,
                            List.copyOf(terms));
                }
            }
        }
        return byParent;
    }


    private static String canonical(Map<String, String> aCanonical, String aText)
    {
        String prior = aCanonical.putIfAbsent(aText, aText);
        return prior == null ? aText : prior;
    }


    /** Whether the file carries the section at all — the discriminator of the legacy arm. */
    boolean isEmpty()
    {
        return exact.isEmpty();
    }


    /**
     * Whether {@code aParent}, read at {@code aParentLevel}, lies on the hierarchy path of
     * {@code aTerm} read at {@code aTermLevel}. Verbatim when {@code aCaseSensitive}, else both
     * sides fold.
     */
    boolean onPath(String aTermLevel, String aTerm, String aParentLevel, String aParent,
            boolean aCaseSensitive)
    {
        return aCaseSensitive ? onPath(exact, aTermLevel, aTerm, aParentLevel, aParent, 0)
                : onPath(folded(), aTermLevel, upper(aTerm), aParentLevel, upper(aParent), 0);
    }


    private static boolean onPath(Map<String, Map<String, Map<String, List<String>>>> aIndex,
            String aLevel, String aKey, String aParentLevel, String aParent, int aDepth)
    {
        Map<String, Map<String, List<String>>> byParent = aIndex.get(aLevel);
        if (byParent == null || aDepth > MAX_CHAIN)
        {
            return false;
        }
        Map<String, List<String>> direct = byParent.get(aParentLevel);
        if (direct != null)
        {
            List<String> terms = direct.get(aKey);
            return terms != null && terms.contains(aParent);
        }
        for (Map.Entry<String, Map<String, List<String>>> via : byParent.entrySet())
        {
            for (String ancestor : via.getValue().getOrDefault(aKey, List.of()))
            {
                if (onPath(aIndex, via.getKey(), ancestor, aParentLevel, aParent, aDepth + 1))
                {
                    return true;
                }
            }
        }
        return false;
    }


    private Map<String, Map<String, Map<String, List<String>>>> folded()
    {
        Map<String, Map<String, Map<String, List<String>>>> f = folded;
        if (f == null)
        {
            f = fold(exact);
            folded = f;
        }
        return f;
    }


    /** Keys and terms upper-cased; two keys folding together keep the union of their terms. */
    private static Map<String, Map<String, Map<String, List<String>>>> fold(
            Map<String, Map<String, Map<String, List<String>>>> aExact)
    {
        Map<String, Map<String, Map<String, List<String>>>> out = new HashMap<>();
        for (Map.Entry<String, Map<String, Map<String, List<String>>>> level : aExact.entrySet())
        {
            Map<String, Map<String, List<String>>> byParent = new HashMap<>();
            for (Map.Entry<String, Map<String, List<String>>> parent : level.getValue().entrySet())
            {
                Map<String, Set<String>> merged = new HashMap<>();
                for (Map.Entry<String, List<String>> e : parent.getValue().entrySet())
                {
                    Set<String> terms = merged.computeIfAbsent(upper(e.getKey()),
                            _ -> new LinkedHashSet<>());
                    e.getValue().forEach(t -> terms.add(upper(t)));
                }
                Map<String, List<String>> lists = new HashMap<>();
                merged.forEach((k, v) -> lists.put(k, List.copyOf(v)));
                byParent.put(parent.getKey(), lists);
            }
            out.put(level.getKey(), byParent);
        }
        return out;
    }


    private static String upper(String aText)
    {
        return aText.toUpperCase(Locale.ROOT);
    }

}
