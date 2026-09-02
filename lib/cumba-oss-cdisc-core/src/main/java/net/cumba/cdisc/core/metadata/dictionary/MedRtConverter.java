package net.cumba.cdisc.core.metadata.dictionary;

import static net.cumba.cdisc.core.metadata.dictionary.RawDictionaryFiles.cells;
import static net.cumba.cdisc.core.metadata.dictionary.RawDictionaryFiles.firstMatching;
import static net.cumba.cdisc.core.metadata.dictionary.RawDictionaryFiles.putAll;
import static net.cumba.cdisc.core.metadata.dictionary.RawDictionaryFiles.reader;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts a MED-RT distribution into the house format.
 *
 * <h2>Input</h2>
 *
 * <p>
 * {@code MEDRT.txt}: tab-separated, three columns — concept name, NUI, and the literal source tag
 * {@code MED-RT}. ~3 695 rows.
 * </p>
 *
 * <h2>The type tag, and why both spellings are emitted</h2>
 *
 * <p>
 * 3 691 of the 3 695 concept names carry a bracketed type tag — {@code Cyclooxygenase Inhibitors
 * [MoA]}, {@code 1-Compartment [PK]}. All eight MED-RT rules compare {@code TS.TSVAL} for
 * {@code TSPARMCD == "PCLAS"} byte-exactly against {@code levels.MEDRT}, and both spellings occur
 * in real submissions: FDA's SPL convention writes the established pharmacologic class with its
 * tag, while many sponsors submit the bare class name. Committing to one therefore false-fires on
 * the other, so the tagged name is emitted as the canonical form and the bare base name as a second
 * {@code levels.MEDRT} entry — a distinct key, so the preferred-case contract's one-form-per-term
 * clause is untouched.
 * </p>
 *
 * <p>
 * ⛔ <b>The bare alias is omitted wherever a base name maps to more than one NUI</b> (eight of 3 695
 * in the 2026 release). An alias exists to answer a code/decode pair; where the base name cannot
 * name a single concept there is no pairing to assert, and the tagged forms still answer.
 * </p>
 *
 * <p>
 * The aliases' decodes live in their own registry, {@code pairs["medrt-base"]}, because a
 * {@code pairs} registry is a code &rarr; decode map and one NUI cannot carry two decodes in one
 * map. This works because {@code ValueMapDictionary.codeDecodePair} falls through to scan every
 * registry of the dictionary once the named one misses.
 * </p>
 *
 * <h2>Version</h2>
 *
 * <p>
 * The {@code version} element of {@code Core_MEDRT_*_DTS.xml} — a machine-written field carrying
 * exactly the release token, e.g. {@code 2026.07.06}. When no DTS file is present, the token is
 * <em>extracted</em> from {@code MEDRT_Release_Notes*.txt}: first a {@code version name
 * <token>} phrase, else a bare {@code yyyy.mm.dd} date. Else the empty string — which the installer
 * treats as "not installable", never as a guess.
 * </p>
 *
 * <p>
 * ⛔ <b>The notes are prose, never taken whole.</b> The real 2026 file's first line is
 * {@code July 2026 MED-RT (version name 2026.07.06)} — behind a byte-order mark, with CR CR line
 * ends. Installing that line verbatim yields a directory name and manifest key that starts with an
 * invisible character and cannot be retyped, so {@code --medrt-version 2026.07.06} — the value the
 * file itself declares — would answer "not installed" and every MED-RT rule would SKIP.
 * </p>
 */
public final class MedRtConverter implements DictionaryConverter
{

    /** The trailing bracketed concept-type tag: {@code [PE]}, {@code [EPC]}, {@code [MoA]}, … */
    private static final Pattern TYPE_TAG = Pattern.compile("\\s+\\[[A-Za-z]+\\]\\s*$");

    private static final Pattern VERSION_ELEMENT = Pattern
            .compile("<(?:[A-Za-z0-9_]+:)?version>([^<]*)</");

    /** The release token as the notes' prose declares it: {@code (version name 2026.07.06)}. */
    private static final Pattern NOTES_VERSION_NAME = Pattern.compile("version name ([0-9.]+)");

    /** The bare release-date shape, for notes that carry only the token itself. */
    private static final Pattern NOTES_DATE = Pattern.compile("\\d{4}\\.\\d{2}\\.\\d{2}");

    private static final String SOURCE_FILE = "MEDRT.txt";

    @Override
    public String type()
    {
        return "medrt";
    }


    @Override
    public ObjectNode convert(Path aRawDir) throws IOException
    {
        Path file = aRawDir.resolve(SOURCE_FILE);
        if (!Files.isRegularFile(file))
        {
            throw new NoSuchFileException(file.toString(), null,
                    "the MED-RT distribution must contain " + SOURCE_FILE);
        }

        List<String[]> rows = readRows(file);

        // How many distinct concepts each bare base name reaches. An untagged name is its own
        // base, so a root colliding with some concept's stripped name is counted here too.
        Map<String, Set<String>> nuisByBase = new LinkedHashMap<>();
        for (String[] row : rows)
        {
            nuisByBase.computeIfAbsent(upper(baseNameOf(row[0])), _ -> new LinkedHashSet<>())
                    .add(row[1]);
        }

        Map<String, String> preferred = new LinkedHashMap<>();
        Map<String, String> namesLevel = new LinkedHashMap<>();
        Map<String, String> codesLevel = new LinkedHashMap<>();
        Map<String, String> namePairs = new LinkedHashMap<>();
        Map<String, String> basePairs = new LinkedHashMap<>();

        for (String[] row : rows)
        {
            String name = row[0];
            String nui = row[1];
            codesLevel.put(upper(nui), nui);
            if (!accept(preferred, name))
            {
                // Another row already published this name in a different case. Emitting the
                // second spelling would breach the one-preferred-form clause, and rewriting it
                // to the first would assert a decode the vendor never wrote.
                continue;
            }
            namesLevel.put(upper(name), name);
            namePairs.putIfAbsent(nui, name);

            String base = baseNameOf(name);
            if (base.equals(name) || base.isEmpty())
            {
                continue;
            }
            Set<String> reached = nuisByBase.get(upper(base));
            if (reached != null && reached.size() == 1 && accept(preferred, base))
            {
                namesLevel.put(upper(base), base);
                basePairs.putIfAbsent(nui, base);
            }
        }

        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode levels = root.putObject("levels");
        putAll(levels.putObject("MEDRT"), namesLevel);
        putAll(levels.putObject("MEDRTCD"), codesLevel);
        ObjectNode pairs = root.putObject("pairs");
        putAll(pairs.putObject("medrt"), namePairs);
        if (!basePairs.isEmpty())
        {
            putAll(pairs.putObject("medrt-base"), basePairs);
        }
        return root;
    }


    @Override
    public String versionOf(Path aRawDir) throws IOException
    {
        Path dts = firstMatching(aRawDir, "Core_MEDRT_*_DTS.xml");
        if (dts != null)
        {
            try (BufferedReader in = reader(dts))
            {
                for (String line = in.readLine(); line != null; line = in.readLine())
                {
                    Matcher m = VERSION_ELEMENT.matcher(line);
                    if (m.find() && !m.group(1).isBlank())
                    {
                        return m.group(1).trim();
                    }
                }
            }
        }
        Path notes = firstMatching(aRawDir, "MEDRT_Release_Notes*.txt");
        if (notes != null)
        {
            return versionFromNotes(notes);
        }
        return "";
    }


    /**
     * The release token extracted from the prose of the release notes — never a whole line, whose
     * real shape is a BOM-prefixed sentence (see the class comment). A {@code version name} phrase
     * wins over a bare date; a file declaring neither yields {@code ""}.
     */
    private static String versionFromNotes(Path aNotes) throws IOException
    {
        String date = "";
        try (BufferedReader in = reader(aNotes))
        {
            for (String line = in.readLine(); line != null; line = in.readLine())
            {
                Matcher named = NOTES_VERSION_NAME.matcher(line);
                if (named.find())
                {
                    return named.group(1);
                }
                Matcher bare = NOTES_DATE.matcher(line);
                if (date.isEmpty() && bare.find())
                {
                    date = bare.group();
                }
            }
        }
        return date;
    }


    /** The concept name with its trailing type tag removed; unchanged when it carries none. */
    private static String baseNameOf(String aName)
    {
        return TYPE_TAG.matcher(aName).replaceFirst("").trim();
    }


    /**
     * Registers a term's preferred form, reporting whether this spelling may be published: the
     * first spelling of a term wins, and a later, differently-cased one is refused rather than
     * silently normalised. Every refusal is tallied via
     * {@link RawDictionaryFiles#countDroppedTerm()} for the installer to surface.
     */
    private static boolean accept(Map<String, String> aPreferred, String aTerm)
    {
        String prior = aPreferred.putIfAbsent(upper(aTerm), aTerm);
        if (prior == null || prior.equals(aTerm))
        {
            return true;
        }
        RawDictionaryFiles.countDroppedTerm();
        return false;
    }


    private static List<String[]> readRows(Path aFile) throws IOException
    {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader in = reader(aFile))
        {
            for (String line = in.readLine(); line != null; line = in.readLine())
            {
                String[] parts = cells(line);
                if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank())
                {
                    continue;
                }
                rows.add(new String[]
                {
                        parts[0].trim(), parts[1].trim()
                });
            }
        }
        return rows;
    }


    private static String upper(String aText)
    {
        return aText.toUpperCase(Locale.ROOT);
    }

}
