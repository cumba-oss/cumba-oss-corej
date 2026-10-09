package net.cumba.corej.core.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
 * read through the levels it does name — a file listing a child under its immediate parent only (an
 * LLT under its PT, whose own entry carries the one primary path) chains exactly. The MedDRA
 * converter writes every level's ancestors directly (an HLT / HLGT keeps its SET of primary SOCs)
 * and, since R5 PB-M1, no LLT level at all. A level that names the requested parent level answers
 * from that list alone — a key missing there is off-path, never chained. A chain is bounded
 * ({@link #MAX_CHAIN}) and visits each (level, key) once per lookup (R5 PB-L4), so a malformed,
 * cyclic or fanning file cannot loop or multiply.
 * </p>
 *
 * <p>
 * <b>A level the section lacks</b> (R5 PB-L1; runbook W4 DI2): {@link #hasLevel} for the CHILD
 * level is the capability the rule runner reads
 * ({@code RuntimeDictionaryProvider.answersHierarchyAt}) — a hierarchy rule whose child level the
 * section lacks (a {@code --LLT} read; any read on a file without the section) SKIPs with the
 * reason. The bare-name map is no fallback any more.
 * </p>
 *
 * <p>
 * <b>Memory.</b> Held as one map per (child level, parent level) pair, keys to immutable lists,
 * every string canonicalised on load through the ONE intern map the dictionary's reader shares with
 * its {@code levels} section (R5 PB-M1: a SOC name recurs on every PT row, and a PT name is also a
 * {@code levels} value); the case-folded twin {@code case_sensitive=false} reads is built on first
 * use only, once (never a duplicate per racing thread), each distinct string upper-cased once.
 * </p>
 */
final class LevelScopedHierarchy
{

    /** The section of a file that carries none — such a file answers no hierarchy (W4 DI2). */
    static final LevelScopedHierarchy EMPTY = new LevelScopedHierarchy(Map.of());

    /** The longest chain followed: LLT → PT → HLT → HLGT → SOC is four steps. */
    private static final int MAX_CHAIN = 8;

    /** child level → parent level → child key → parent terms, as authored. */
    private final Map<String, Map<String, Map<String, List<String>>>> exact;

    /** The folded twin (keys and terms upper-cased), built once, on the first folded lookup. */
    private volatile @Nullable Map<String, Map<String, Map<String, List<String>>>> folded;

    /** Guards the one build of {@link #folded}. */
    private final Object foldLock = new Object();

    private LevelScopedHierarchy(Map<String, Map<String, Map<String, List<String>>>> aExact)
    {
        exact = aExact;
    }


    /**
     * Reads the section; a missing or non-object section, or one with no non-empty entry, is
     * {@link #EMPTY}. Non-object entries and non-array term lists are skipped, like the other
     * section readers' degradation.
     *
     * @param aNode
     *            the section, or {@code null}
     * @param aCanonical
     *            the intern map shared with the dictionary's other section readers (R5 PB-M1)
     * @return the section
     */
    static LevelScopedHierarchy read(@Nullable JsonNode aNode, Map<String, String> aCanonical)
    {
        if (aNode == null || !aNode.isObject())
        {
            return EMPTY;
        }
        Map<String, Map<String, Map<String, List<String>>>> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> level : aNode.properties())
        {
            Map<String, Map<String, List<String>>> byParent = readLevel(level.getValue(),
                    aCanonical);
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


    /** The one instance of {@code aText} in {@code aCanonical} (it becomes that instance). */
    static String canonical(Map<String, String> aCanonical, String aText)
    {
        String prior = aCanonical.putIfAbsent(aText, aText);
        return prior == null ? aText : prior;
    }


    /** Whether the file carries the section at all — the stale-file discriminator (W4 DI2). */
    boolean isEmpty()
    {
        return exact.isEmpty();
    }


    /**
     * Whether the section carries {@code aLevel} as a child level (R5 PB-L1) — a lookup whose child
     * level it lacks cannot be answered (runbook W4 DI2: the rule SKIPs with the reason).
     */
    boolean hasLevel(String aLevel)
    {
        return exact.containsKey(aLevel);
    }


    /**
     * Whether {@code aParent}, read at {@code aParentLevel}, lies on the hierarchy path of
     * {@code aTerm} read at {@code aTermLevel}. Verbatim when {@code aCaseSensitive}, else both
     * sides fold.
     */
    boolean onPath(String aTermLevel, String aTerm, String aParentLevel, String aParent,
            boolean aCaseSensitive)
    {
        return aCaseSensitive ? onPath(exact, aTermLevel, aTerm, aParentLevel, aParent, 0, null)
                : onPath(folded(), aTermLevel, upper(aTerm), aParentLevel, upper(aParent), 0, null);
    }


    /**
     * One step of the lookup; {@code aVisited} is allocated on the first chained step only (a
     * direct answer — every MedDRA-converted lookup — allocates nothing) and holds the
     * {@code level + '\u0000' + key} pairs already followed, so each is walked once (R5 PB-L4).
     */
    private static boolean onPath(Map<String, Map<String, Map<String, List<String>>>> aIndex,
            String aLevel, String aKey, String aParentLevel, String aParent, int aDepth,
            @Nullable Set<String> aVisited)
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
        Set<String> visited = aVisited != null ? aVisited : new HashSet<>();
        if (!visited.add(aLevel + '\u0000' + aKey))
        {
            return false;
        }
        for (Map.Entry<String, Map<String, List<String>>> via : byParent.entrySet())
        {
            for (String ancestor : via.getValue().getOrDefault(aKey, List.of()))
            {
                if (onPath(aIndex, via.getKey(), ancestor, aParentLevel, aParent, aDepth + 1,
                        visited))
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
            synchronized (foldLock)
            {
                f = folded;
                if (f == null)
                {
                    f = fold(exact);
                    folded = f;
                }
            }
        }
        return f;
    }


    /**
     * Keys and terms upper-cased; two keys folding together keep the union of their terms. Each
     * distinct string is upper-cased once and every folded occurrence shares that one instance (a
     * SOC name recurs on every PT row) — R5 PB-M1.
     */
    private static Map<String, Map<String, Map<String, List<String>>>> fold(
            Map<String, Map<String, Map<String, List<String>>>> aExact)
    {
        Map<String, String> upper = new HashMap<>();
        Map<String, Map<String, Map<String, List<String>>>> out = new HashMap<>();
        for (Map.Entry<String, Map<String, Map<String, List<String>>>> level : aExact.entrySet())
        {
            Map<String, Map<String, List<String>>> byParent = new HashMap<>();
            for (Map.Entry<String, Map<String, List<String>>> parent : level.getValue().entrySet())
            {
                Map<String, Set<String>> merged = new HashMap<>();
                for (Map.Entry<String, List<String>> e : parent.getValue().entrySet())
                {
                    Set<String> terms = merged.computeIfAbsent(upper(upper, e.getKey()),
                            _ -> new LinkedHashSet<>());
                    e.getValue().forEach(t -> terms.add(upper(upper, t)));
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


    /**
     * {@code aText} upper-cased once per fold, and one instance per folded text: the memo maps
     * every text it has seen — authored or folded — to its folded instance.
     */
    private static String upper(Map<String, String> aMemo, String aText)
    {
        String known = aMemo.get(aText);
        if (known != null)
        {
            return known;
        }
        String folded = upper(aText);
        String instance = aMemo.get(folded);
        if (instance == null)
        {
            instance = folded;
            aMemo.put(folded, folded);
        }
        aMemo.put(aText, instance);
        return instance;
    }

}
