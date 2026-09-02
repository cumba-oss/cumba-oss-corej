package net.cumba.cdisc.core.metadata.dictionary;

import static net.cumba.cdisc.core.metadata.dictionary.RawDictionaryFiles.putAll;
import static net.cumba.cdisc.core.metadata.dictionary.RawDictionaryFiles.reader;

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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
 * which is the defect this construction exists to prevent. No rule probes the hierarchy at PT or
 * LLT, so those keys are not emitted.
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
        putLevel(levels, "LLT", lltByCode);
        putLevel(levels, "PT", ptByCode);
        putLevel(levels, "HLT", hltByCode);
        putLevel(levels, "HLGT", hlgtByCode);
        putLevel(levels, "SOC", socByCode);

        ObjectNode hierarchy = root.putObject("hierarchy");
        for (Map.Entry<String, Set<String>> e : primarySocsByName(aRawDir, hltByCode, hlgtByCode,
                socByCode).entrySet())
        {
            ArrayNode ancestors = hierarchy.putArray(e.getKey());
            e.getValue().forEach(ancestors::add);
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
     * The distinct primary-path SOC names reached from each HLT and HLGT name, in file order. Rows
     * whose codes do not resolve against the level files carry nothing resolvable and are skipped.
     */
    private static Map<String, Set<String>> primarySocsByName(Path aRawDir,
            Map<String, String> aHltByCode, Map<String, String> aHlgtByCode,
            Map<String, String> aSocByCode)
        throws IOException
    {
        Map<String, Set<String>> out = new LinkedHashMap<>();
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
                String soc = aSocByCode.get(fields[SOC_CODE_AT].trim());
                if (soc == null)
                {
                    continue;
                }
                String hlt = aHltByCode.get(fields[HLT_CODE_AT].trim());
                if (hlt != null)
                {
                    out.computeIfAbsent(hlt, _ -> new LinkedHashSet<>()).add(soc);
                }
                String hlgt = aHlgtByCode.get(fields[HLGT_CODE_AT].trim());
                if (hlgt != null)
                {
                    out.computeIfAbsent(hlgt, _ -> new LinkedHashSet<>()).add(soc);
                }
            }
        }
        return out;
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
