package net.cumba.corej.core.metadata.dictionary;

import static net.cumba.corej.core.metadata.dictionary.RawDictionaryFiles.putAll;
import static net.cumba.corej.core.metadata.dictionary.RawDictionaryFiles.reader;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.cumba.corej.core.metadata.ValueMapDictionary;
import org.jspecify.annotations.Nullable;

/**
 * Converts a MedDRA {@code MedAscii} distribution into the house format.
 *
 * <h2>Input</h2>
 *
 * <p>
 * The {@code $}-delimited ASCII files: {@code llt.asc}, {@code pt.asc}, {@code hlt.asc},
 * {@code hlgt.asc}, {@code soc.asc} (field 0 the code, field 1 the name), {@code mdhier.asc} (the
 * PT&rarr;HLT&rarr;HLGT&rarr;SOC paths), and {@code meddra_release.asc} (field 0 the release, e.g.
 * {@code 27.0}). Each line is stripped of its leading and trailing {@code $} delimiters and then
 * split on {@code $}.
 * </p>
 *
 * <h2>One preferred form per term <em>within a level</em>, enforced loudly</h2>
 *
 * <p>
 * Every name is published verbatim — 51 MedDRA rules read these levels and most compare
 * case-sensitively — and registered in a preferred-form table <b>per level file</b>. Two spellings
 * of one term <em>within one file</em> (a {@code pt.asc} carrying both {@code Headache} and
 * {@code HEADACHE}) cannot be represented — the house format admits one preferred form per term
 * within a level, and silently picking either spelling would make the rules reading that level fire
 * on conformant data — so such a conflict aborts the conversion with an exception naming both
 * spellings. A divergence <em>across</em> files (an LLT {@code HEADACHE} against a PT
 * {@code Headache}) is legitimate and preserved: the engine consults only the level a rule's
 * {@code dictionary_term_type} names, so each level answers with its own vendor spelling (owner
 * ruling — the cross-level clause had no engine backing).
 * </p>
 *
 * <h2>⛔ The hierarchy is primary-path only, keyed at HLT and HLGT</h2>
 *
 * <p>
 * {@code hierarchy} is built exclusively from {@code mdhier.asc} rows whose {@code primary_soc_fg}
 * (field 11) is {@code Y}, keyed at the <b>HLT name</b> and the <b>HLGT name</b>, each mapping to
 * the distinct SOC names reached on those primary rows. The two rules that read it —
 * {@code CDISC-CG0460} and {@code CDISC-CG0461} — check {@code --SOC}, which SDTM defines as the
 * <i>primary</i> system organ class: a full closure would additionally admit every secondary SOC,
 * so a row coded against a secondary SOC would resolve and the rules could never fire — silently,
 * which is the defect this construction exists to prevent. This bare-name map is the <b>legacy</b>
 * shape and stays exactly as it was (HLT and HLGT names only, the levels merged under one key), so
 * every reader of a file written before DI1 answers as before.
 * </p>
 *
 * <h2>The level-scoped hierarchy — {@code hierarchy_by_level} (runbook W2 DI1)</h2>
 *
 * <p>
 * Beside it, from the same primary rows, the converter writes the primary path keyed <b>per
 * level</b> ({@code PLAN-engine-gap-dict-unsupported} §4.1, §5.1 Q1/Q2): a PT name &rarr; its
 * primary HLT, HLGT and SOC names, a PT code ({@code PTCD}, field 0 of {@code mdhier.asc}) &rarr;
 * their codes; an HLT &rarr; the HLGTs and SOCs, an HLGT &rarr; the SOCs it reaches on primary rows
 * (a set — an HLT can sit under several HLGTs). A PT has exactly one primary row, so its lists hold
 * one term each. A name may recur at several levels — a PT can also be an HLT and an LLT (owner
 * fact, 2026-10-08) — and each level keeps its own entry; the engine reads the level of the
 * variable ({@code ValueMapDictionary.onHierarchyPath} with levels). Codes are code-like
 * identifiers, never words, and sit at their own {@code *CD} levels.
 * </p>
 *
 * <p>
 * ⚑ <b>No LLT level</b> (runbook W2 fold, R5 PB-M1 — O11 "build only what is needed now", the
 * owner's memory-first priority): an LLT &rarr; PT level was about three quarters of the section's
 * entries (tens of MB on a release) and no shipped rule reads {@code --LLT}, so it is not written;
 * a hierarchy rule reading {@code --LLT} reports SKIPPED with the reason (there is no bare-name
 * fallback for MedDRA, {@code ValueMapDictionary.onHierarchyPath}). ⚠ When a rule comes to read
 * {@code --LLT} the level must be added back, keyed by LLT name and LLT code ({@code llt.asc} field
 * 2 names the PT), because a PT name can also be an LLT name (owner fact) — the bare-name map
 * cannot tell them apart.
 * </p>
 *
 * <p>
 * The names in the hierarchy come from the level files' own code&rarr;name maps (and therefore
 * carry each level's preferred form), never from {@code mdhier.asc}'s denormalised name columns, so
 * a case divergence between the files cannot produce an unresolvable ancestor.
 * </p>
 *
 * <h2>Version</h2>
 *
 * <p>
 * Field 0 of the first non-blank line of {@code meddra_release.asc}, or the empty string when the
 * file is absent — which the installer treats as "not installable", never as a guess.
 * </p>
 */
public final class MedDraConverter implements DictionaryConverter
{

    private static final String RELEASE_FILE = "meddra_release.asc";

    private static final String HIERARCHY_FILE = "mdhier.asc";

    private static final String PRIMARY_FLAG = "Y";

    private static final int HLT_CODE_AT = 1;

    private static final int HLGT_CODE_AT = 2;

    private static final int SOC_CODE_AT = 3;

    private static final int PRIMARY_FLAG_AT = 11;

    /**
     * The level names, as the {@code levels} and {@code hierarchy_by_level} sections spell them.
     */
    private static final String LLT = "LLT";

    private static final String PT = "PT";

    private static final String HLT = "HLT";

    private static final String HLGT = "HLGT";

    private static final String SOC = "SOC";

    private static final String CODE = "CD";

    @Override
    public String type()
    {
        return "meddra";
    }


    @Override
    public ObjectNode convert(Path aRawDir) throws IOException
    {
        Map<String, String> lltByCode = readTerms(aRawDir, "llt.asc");
        Map<String, String> ptByCode = readTerms(aRawDir, "pt.asc");
        Map<String, String> hltByCode = readTerms(aRawDir, "hlt.asc");
        Map<String, String> hlgtByCode = readTerms(aRawDir, "hlgt.asc");
        Map<String, String> socByCode = readTerms(aRawDir, "soc.asc");

        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode levels = root.putObject("levels");
        putLevel(levels, LLT, lltByCode);
        putLevel(levels, PT, ptByCode);
        putLevel(levels, HLT, hltByCode);
        putLevel(levels, HLGT, hlgtByCode);
        putLevel(levels, SOC, socByCode);

        PrimaryPaths paths = primaryPaths(aRawDir, ptByCode, hltByCode, hlgtByCode, socByCode);
        ObjectNode hierarchy = root.putObject("hierarchy");
        for (Map.Entry<String, Set<String>> e : paths.socsByName().entrySet())
        {
            ArrayNode ancestors = hierarchy.putArray(e.getKey());
            e.getValue().forEach(ancestors::add);
        }
        Map<String, Map<String, Map<String, Set<String>>>> byLevel = paths.byLevel();
        ObjectNode scoped = root.putObject(ValueMapDictionary.HIERARCHY_BY_LEVEL);
        for (Map.Entry<String, Map<String, Map<String, Set<String>>>> level : byLevel.entrySet())
        {
            if (level.getValue().isEmpty())
            {
                continue;
            }
            ObjectNode levelNode = scoped.putObject(level.getKey());
            for (Map.Entry<String, Map<String, Set<String>>> entry : level.getValue().entrySet())
            {
                ObjectNode entryNode = levelNode.putObject(entry.getKey());
                entry.getValue().forEach((parentLevel, terms) ->
                {
                    ArrayNode list = entryNode.putArray(parentLevel);
                    terms.forEach(list::add);
                });
            }
        }
        return root;
    }


    @Override
    public String versionOf(Path aRawDir) throws IOException
    {
        Path file = aRawDir.resolve(RELEASE_FILE);
        if (!Files.isRegularFile(file))
        {
            return "";
        }
        try (BufferedReader in = reader(file))
        {
            for (String line = in.readLine(); line != null; line = in.readLine())
            {
                String[] fields = fieldsOf(line);
                if (fields.length > 0 && !fields[0].isBlank())
                {
                    return fields[0].trim();
                }
            }
        }
        return "";
    }

    /**
     * What the primary rows of {@code mdhier.asc} yield.
     *
     * @param socsByName
     *            the legacy map: the distinct primary SOC names of each HLT and HLGT name, the two
     *            levels merged under one key
     * @param byLevel
     *            the level-scoped map: level &rarr; key &rarr; ancestor level &rarr; ancestor terms
     *            (PT, HLT, HLGT and their codes — no LLT level, R5 PB-M1)
     */
    private record PrimaryPaths(Map<String, Set<String>> socsByName,
            Map<String, Map<String, Map<String, Set<String>>>> byLevel)
    {
    }

    /**
     * The primary-path ancestors, in file order — the legacy HLT/HLGT &rarr; SOC map and the
     * level-scoped one (PT, HLT and HLGT names and codes). Rows whose SOC does not resolve against
     * the level files carry nothing resolvable and are skipped; an unresolvable HLT, HLGT or PT
     * code only drops that term.
     */
    private static PrimaryPaths primaryPaths(Path aRawDir, Map<String, String> aPtByCode,
            Map<String, String> aHltByCode, Map<String, String> aHlgtByCode,
            Map<String, String> aSocByCode)
        throws IOException
    {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        Map<String, Map<String, Map<String, Set<String>>>> byLevel = new LinkedHashMap<>();
        for (String level : List.of(PT, PT + CODE, HLT, HLT + CODE, HLGT, HLGT + CODE))
        {
            byLevel.put(level, new LinkedHashMap<>());
        }
        try (BufferedReader in = reader(requiredFile(aRawDir, HIERARCHY_FILE)))
        {
            for (String line = in.readLine(); line != null; line = in.readLine())
            {
                String[] fields = fieldsOf(line);
                if (fields.length <= PRIMARY_FLAG_AT
                        || !PRIMARY_FLAG.equals(fields[PRIMARY_FLAG_AT].trim()))
                {
                    continue;
                }
                String socCode = fields[SOC_CODE_AT].trim();
                String soc = aSocByCode.get(socCode);
                if (soc == null)
                {
                    continue;
                }
                String hltCode = fields[HLT_CODE_AT].trim();
                String hlt = aHltByCode.get(hltCode);
                if (hlt != null)
                {
                    out.computeIfAbsent(hlt, _ -> new LinkedHashSet<>()).add(soc);
                }
                String hlgtCode = fields[HLGT_CODE_AT].trim();
                String hlgt = aHlgtByCode.get(hlgtCode);
                if (hlgt != null)
                {
                    out.computeIfAbsent(hlgt, _ -> new LinkedHashSet<>()).add(soc);
                }
                String ptCode = fields[0].trim();
                addPath(byLevel, PT, aPtByCode.get(ptCode), ptCode, hlt, hltCode, hlgt, hlgtCode,
                        soc, socCode);
                addPath(byLevel, HLT, hlt, hltCode, null, null, hlgt, hlgtCode, soc, socCode);
                addPath(byLevel, HLGT, hlgt, hlgtCode, null, null, null, null, soc, socCode);
            }
        }
        return new PrimaryPaths(out, byLevel);
    }


    /**
     * Files one primary row's ancestors under {@code aLevel}'s name and code (both, or neither when
     * the name does not resolve); a {@code null} ancestor is not on the row.
     */
    private static void addPath(Map<String, Map<String, Map<String, Set<String>>>> aByLevel,
            String aLevel, @Nullable String aName, String aCode, @Nullable String aHlt,
            @Nullable String aHltCode, @Nullable String aHlgt, @Nullable String aHlgtCode,
            String aSoc, String aSocCode)
    {
        if (aName == null)
        {
            return;
        }
        if (aHlt != null)
        {
            add(aByLevel, aLevel, aName, HLT, aHlt);
            add(aByLevel, aLevel + CODE, aCode, HLT + CODE, Objects.requireNonNull(aHltCode));
        }
        if (aHlgt != null)
        {
            add(aByLevel, aLevel, aName, HLGT, aHlgt);
            add(aByLevel, aLevel + CODE, aCode, HLGT + CODE, Objects.requireNonNull(aHlgtCode));
        }
        add(aByLevel, aLevel, aName, SOC, aSoc);
        add(aByLevel, aLevel + CODE, aCode, SOC + CODE, aSocCode);
    }


    private static void add(Map<String, Map<String, Map<String, Set<String>>>> aByLevel,
            String aLevel, @Nullable String aKey, String aParentLevel, String aTerm)
    {
        if (aKey == null)
        {
            return;
        }
        aByLevel.computeIfAbsent(aLevel, _ -> new LinkedHashMap<>())
                .computeIfAbsent(aKey, _ -> new LinkedHashMap<>())
                .computeIfAbsent(aParentLevel, _ -> new LinkedHashSet<>()).add(aTerm);
    }


    /**
     * Reads one level file into a code&rarr;name map, registering every name in that level's own
     * preferred-form table.
     *
     * @throws IOException
     *             when two names in this file fold to the same term but disagree on case
     */
    private static Map<String, String> readTerms(Path aRawDir, String aFileName) throws IOException
    {
        Map<String, String> preferred = new LinkedHashMap<>();
        Map<String, String> byCode = new LinkedHashMap<>();
        try (BufferedReader in = reader(requiredFile(aRawDir, aFileName)))
        {
            for (String line = in.readLine(); line != null; line = in.readLine())
            {
                String[] fields = fieldsOf(line);
                if (fields.length < 2)
                {
                    continue;
                }
                String code = fields[0].trim();
                String name = fields[1].trim();
                if (code.isEmpty() || name.isEmpty())
                {
                    continue;
                }
                register(preferred, name, aFileName);
                byCode.putIfAbsent(code, name);
            }
        }
        return byCode;
    }


    /** A name level ({@code upper(name) -> name}) and its {@code *CD} twin, from one code map. */
    private static void putLevel(ObjectNode aLevels, String aName, Map<String, String> aByCode)
    {
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, String> codes = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : aByCode.entrySet())
        {
            codes.put(upper(e.getKey()), e.getKey());
            names.putIfAbsent(upper(e.getValue()), e.getValue());
        }
        putAll(aLevels.putObject(aName), names);
        putAll(aLevels.putObject(aName + "CD"), codes);
    }


    /**
     * Registers a term's preferred form in one level's table. Unlike the free dictionaries'
     * first-spelling-wins acceptance, a genuine within-level case conflict here aborts the
     * conversion: both spellings are the vendor's, both are read by case-sensitive rules, and
     * silently dropping either would make those rules fire on conformant data. A cross-level
     * divergence is deliberately not checked (see the class comment).
     */
    private static void register(Map<String, String> aPreferred, String aName, String aFileName)
        throws IOException
    {
        String prior = aPreferred.putIfAbsent(upper(aName), aName);
        if (prior != null && !prior.equals(aName))
        {
            throw new IOException("case conflict in the MedDRA distribution: '" + prior + "' and '"
                    + aName + "' (" + aFileName + ") fold to the same term but disagree on case; "
                    + "the house format admits one preferred form per term within a level");
        }
    }


    private static Path requiredFile(Path aRawDir, String aFileName) throws IOException
    {
        Path file = aRawDir.resolve(aFileName);
        if (!Files.isRegularFile(file))
        {
            throw new NoSuchFileException(file.toString(), null,
                    "a MedDRA MedAscii distribution must contain " + aFileName);
        }
        return file;
    }


    /**
     * The fields of one {@code $}-delimited line: leading and trailing {@code $} delimiters (and a
     * stray {@code \r}) are stripped, then the remainder is split on {@code $}, keeping interior
     * empty fields.
     */
    private static String[] fieldsOf(String aLine)
    {
        int start = 0;
        int end = aLine.length();
        while (end > start && (aLine.charAt(end - 1) == '$' || aLine.charAt(end - 1) == '\r'))
        {
            end--;
        }
        while (start < end && aLine.charAt(start) == '$')
        {
            start++;
        }
        return aLine.substring(start, end).split("\\$", -1);
    }


    private static String upper(String aText)
    {
        return aText.toUpperCase(Locale.ROOT);
    }

}
