package net.cumba.cdisc.core.metadata.dictionary;

import static net.cumba.cdisc.core.metadata.dictionary.RawDictionaryFiles.cells;
import static net.cumba.cdisc.core.metadata.dictionary.RawDictionaryFiles.firstMatching;
import static net.cumba.cdisc.core.metadata.dictionary.RawDictionaryFiles.putAll;
import static net.cumba.cdisc.core.metadata.dictionary.RawDictionaryFiles.reader;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts an FDA UNII distribution into the house format.
 *
 * <h2>Input</h2>
 *
 * <p>
 * {@code UNII_Records_}<i>date</i>{@code .txt}: a 25-column tab-separated file with a header row,
 * of which this converter reads exactly two — {@code UNII} and {@code DISPLAY_NAME} (171 912 data
 * rows in the 4 Aug 2026 release). Every third-party identifier column is deliberately dropped.
 * </p>
 *
 * <h2>⛔ Case is never normalised</h2>
 *
 * <p>
 * Nine of the 24 UNII rules compare a submitted substance name against {@code levels.SRS}
 * case-sensitively. FDA writes {@code DISPLAY_NAME} in upper case; title-casing it — or any other
 * "tidying" — makes those nine rules fire on every conformant row. The {@code levels} key is the
 * case-fold; the value is the vendor's bytes.
 * </p>
 *
 * <p>
 * Where two UNIIs carry display names that differ only in case, the house format cannot represent
 * both: {@code levels} admits one preferred form per term. The first spelling encountered wins and
 * the later one is dropped from {@code levels.SRS} and from {@code pairs.unii} — never rewritten,
 * which would assert a decode the FDA did not publish. The second UNII itself is still published in
 * {@code levels.UNII}, so a code check on it keeps answering.
 * </p>
 *
 * <h2>Version</h2>
 *
 * <p>
 * The date token in the file name — {@code UNII_Records_4Aug2026.txt} &rarr; {@code 4Aug2026}. It
 * is recorded verbatim and only ever compared for equality; nothing parses or orders it.
 * </p>
 */
public final class UniiConverter implements DictionaryConverter
{

    private static final String FILE_GLOB = "UNII_Records_*.txt";

    private static final Pattern FILE_VERSION = Pattern.compile("^UNII_Records_(.+)\\.txt$",
            Pattern.CASE_INSENSITIVE);

    private static final String CODE_COLUMN = "UNII";

    private static final String NAME_COLUMN = "DISPLAY_NAME";

    @Override
    public String type()
    {
        return "unii";
    }


    @Override
    public ObjectNode convert(Path aRawDir) throws IOException
    {
        Path file = recordsFile(aRawDir);

        Map<String, String> preferred = new LinkedHashMap<>();
        Map<String, String> codesLevel = new LinkedHashMap<>();
        Map<String, String> namesLevel = new LinkedHashMap<>();
        Map<String, String> pairs = new LinkedHashMap<>();

        try (BufferedReader in = reader(file))
        {
            String header = in.readLine();
            if (header == null)
            {
                throw new IOException(file + " is empty; a UNII record file starts with a header "
                        + "row naming " + CODE_COLUMN + " and " + NAME_COLUMN);
            }
            int codeAt = columnOf(header, CODE_COLUMN, 0);
            int nameAt = columnOf(header, NAME_COLUMN, 1);
            for (String line = in.readLine(); line != null; line = in.readLine())
            {
                String[] parts = cells(line);
                if (parts.length <= Math.max(codeAt, nameAt))
                {
                    continue;
                }
                String code = parts[codeAt].trim();
                String name = parts[nameAt].trim();
                if (code.isEmpty() || name.isEmpty())
                {
                    // A record with no UNII or no display name answers nothing; emitting it would
                    // put an empty term into a membership level.
                    continue;
                }
                if (!accept(preferred, code))
                {
                    continue;
                }
                codesLevel.put(upper(code), code);
                if (!accept(preferred, name))
                {
                    continue;
                }
                namesLevel.put(upper(name), name);
                pairs.putIfAbsent(code, name);
            }
        }

        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode levels = root.putObject("levels");
        putAll(levels.putObject("UNII"), codesLevel);
        putAll(levels.putObject("SRS"), namesLevel);
        putAll(root.putObject("pairs").putObject("unii"), pairs);
        return root;
    }


    @Override
    public String versionOf(Path aRawDir) throws IOException
    {
        Path file = firstMatching(aRawDir, FILE_GLOB);
        if (file == null)
        {
            return "";
        }
        Matcher m = FILE_VERSION.matcher(RawDictionaryFiles.fileNameOf(file));
        return m.matches() ? m.group(1) : "";
    }


    private static Path recordsFile(Path aRawDir) throws IOException
    {
        Path file = firstMatching(aRawDir, FILE_GLOB);
        if (file == null)
        {
            throw new NoSuchFileException(aRawDir.toString(), null,
                    "the UNII distribution must contain a " + FILE_GLOB + " file");
        }
        return file;
    }


    /** The index of a named column in the header row, or {@code aFallback} when it is absent. */
    private static int columnOf(String aHeader, String aName, int aFallback)
    {
        String[] parts = cells(aHeader);
        for (int i = 0; i < parts.length; i++)
        {
            if (parts[i].trim().equalsIgnoreCase(aName))
            {
                return i;
            }
        }
        return aFallback;
    }


    /**
     * Registers a term's preferred form, reporting whether this spelling may be published. The
     * first spelling of a term wins; a later, differently-cased one is refused rather than
     * normalised, because normalising is exactly what breaks the nine case-sensitive name rules.
     * Every refusal is tallied via {@link RawDictionaryFiles#countDroppedTerm()} for the installer
     * to surface.
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


    private static String upper(String aText)
    {
        return aText.toUpperCase(Locale.ROOT);
    }

}
