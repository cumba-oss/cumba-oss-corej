package net.cumba.corej.core.metadata.dictionary;

import static net.cumba.corej.core.metadata.dictionary.RawDictionaryFiles.putAll;
import static net.cumba.corej.core.metadata.dictionary.RawDictionaryFiles.reader;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.PushbackReader;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Converts a LOINC table distribution into the house format.
 *
 * <h2>Input</h2>
 *
 * <p>
 * {@code Loinc.csv}, at the top of the distribution or under {@code LoincTable/}. Column indices
 * are resolved from the header row by name — {@code LOINC_NUM}, {@code LONG_COMMON_NAME},
 * {@code VersionLastChanged} — never assumed positional.
 * </p>
 *
 * <p>
 * ⛔ <b>This is a real CSV, not a line-per-row file.</b> Quoted fields carry embedded commas,
 * doubled quotes <i>and embedded newlines</i>, so the file is parsed as one character stream. A
 * line-by-line split — the upstream Python engine has exactly that bug — tears every such row in
 * two, corrupting it and the row after it.
 * </p>
 *
 * <h2>Why {@code pairs.loinc} exists — a licence requirement, not a functional one</h2>
 *
 * <p>
 * No shipped rule reads a LOINC pair registry; both LOINC rules are membership checks against
 * {@code levels.LOINC}. The display names are emitted because LOINC's licence §10.c requires any
 * identifier extracted from the licensed material to carry its corresponding display name — a
 * codes-only extract separates code from meaning and fails that condition. So {@code pairs.loinc}
 * maps each code to its {@code LONG_COMMON_NAME}, inert for the corpus but load-bearing for
 * compliance ({@code dictionaries/README.md} §5.1).
 * </p>
 *
 * <h2>Version</h2>
 *
 * <p>
 * The maximum {@code VersionLastChanged} value over all rows. When every value is a plain
 * <i>major</i>{@code .}<i>minor</i> number the maximum is taken numerically (so {@code 2.77}
 * outranks {@code 2.9}); any odd value demotes the whole comparison to lexicographic rather than
 * crashing the conversion.
 * </p>
 */
public final class LoincConverter implements DictionaryConverter
{

    private static final String FILE_NAME = "Loinc.csv";

    private static final String NESTED_DIR = "LoincTable";

    private static final String CODE_COLUMN = "LOINC_NUM";

    private static final String NAME_COLUMN = "LONG_COMMON_NAME";

    private static final String VERSION_COLUMN = "VersionLastChanged";

    private static final Pattern MAJOR_MINOR = Pattern.compile("\\d{1,9}\\.\\d{1,9}");

    @Override
    public String type()
    {
        return "loinc";
    }


    @Override
    public ObjectNode convert(Path aRawDir) throws IOException
    {
        Path file = tableFile(aRawDir);
        if (file == null)
        {
            throw new NoSuchFileException(aRawDir.resolve(FILE_NAME).toString(), null,
                    "a LOINC distribution must contain " + FILE_NAME + " or " + NESTED_DIR + "/"
                            + FILE_NAME);
        }

        Map<String, String> codesLevel = new LinkedHashMap<>();
        Map<String, String> pairs = new LinkedHashMap<>();

        try (PushbackReader in = new PushbackReader(reader(file)))
        {
            List<String> header = readRecord(in);
            if (header == null)
            {
                throw new IOException(file + " is empty; a LOINC table starts with a header row "
                        + "naming " + CODE_COLUMN + " and " + NAME_COLUMN);
            }
            int codeAt = requiredColumn(header, CODE_COLUMN, file);
            int nameAt = requiredColumn(header, NAME_COLUMN, file);
            for (List<String> row = readRecord(in); row != null; row = readRecord(in))
            {
                if (row.size() <= Math.max(codeAt, nameAt))
                {
                    continue;
                }
                String code = row.get(codeAt).trim();
                String name = row.get(nameAt).trim();
                if (code.isEmpty())
                {
                    continue;
                }
                codesLevel.putIfAbsent(upper(code), code);
                if (!name.isEmpty())
                {
                    pairs.putIfAbsent(code, name);
                }
            }
        }

        ObjectNode root = JsonNodeFactory.instance.objectNode();
        putAll(root.putObject("levels").putObject("LOINC"), codesLevel);
        putAll(root.putObject("pairs").putObject("loinc"), pairs);
        return root;
    }


    @Override
    public String versionOf(Path aRawDir) throws IOException
    {
        Path file = tableFile(aRawDir);
        if (file == null)
        {
            return "";
        }
        List<String> values = new ArrayList<>();
        try (PushbackReader in = new PushbackReader(reader(file)))
        {
            List<String> header = readRecord(in);
            if (header == null)
            {
                return "";
            }
            int versionAt = columnOf(header, VERSION_COLUMN);
            if (versionAt < 0)
            {
                return "";
            }
            for (List<String> row = readRecord(in); row != null; row = readRecord(in))
            {
                if (row.size() <= versionAt)
                {
                    continue;
                }
                String value = row.get(versionAt).trim();
                if (!value.isEmpty())
                {
                    values.add(value);
                }
            }
        }
        return newest(values);
    }


    /** The table file, at the top of the distribution or under {@code LoincTable/}. */
    private static @Nullable Path tableFile(Path aRawDir)
    {
        Path top = aRawDir.resolve(FILE_NAME);
        if (Files.isRegularFile(top))
        {
            return top;
        }
        Path nested = aRawDir.resolve(NESTED_DIR).resolve(FILE_NAME);
        return Files.isRegularFile(nested) ? nested : null;
    }


    /**
     * The greatest of the collected version values — numerically when every value is a plain
     * <i>major</i>{@code .}<i>minor</i> number, lexicographically otherwise, and the empty string
     * when the file carried none.
     */
    private static String newest(List<String> aValues)
    {
        if (aValues.isEmpty())
        {
            return "";
        }
        Comparator<String> order = aValues.stream().allMatch(v -> MAJOR_MINOR.matcher(v).matches())
                ? Comparator.comparingLong(LoincConverter::majorOf)
                        .thenComparingLong(LoincConverter::minorOf)
                : Comparator.naturalOrder();
        return aValues.stream().max(order).orElse("");
    }


    private static long majorOf(String aVersion)
    {
        return Long.parseLong(aVersion.substring(0, aVersion.indexOf('.')));
    }


    private static long minorOf(String aVersion)
    {
        return Long.parseLong(aVersion.substring(aVersion.indexOf('.') + 1));
    }


    private static int requiredColumn(List<String> aHeader, String aName, Path aFile)
        throws IOException
    {
        int at = columnOf(aHeader, aName);
        if (at < 0)
        {
            throw new IOException(
                    aFile + " has no " + aName + " column; found " + String.join(", ", aHeader));
        }
        return at;
    }


    /** The index of a named column in the header record, or {@code -1} when it is absent. */
    private static int columnOf(List<String> aHeader, String aName)
    {
        for (int i = 0; i < aHeader.size(); i++)
        {
            if (aHeader.get(i).trim().equalsIgnoreCase(aName))
            {
                return i;
            }
        }
        return -1;
    }


    /**
     * Reads one CSV record — possibly spanning several physical lines — or {@code null} at end of
     * input. RFC 4180 quoting: a field starting with {@code "} runs to the closing quote,
     * {@code ""} inside it is a literal quote, and separators and newlines inside it are data.
     */
    private static @Nullable List<String> readRecord(PushbackReader aIn) throws IOException
    {
        int c = aIn.read();
        if (c < 0)
        {
            return null;
        }
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        while (c >= 0)
        {
            if (quoted)
            {
                if (c == '"')
                {
                    int next = aIn.read();
                    if (next == '"')
                    {
                        field.append('"');
                    }
                    else
                    {
                        quoted = false;
                        if (next >= 0)
                        {
                            aIn.unread(next);
                        }
                    }
                }
                else
                {
                    field.append((char) c);
                }
            }
            else if (c == '"' && field.isEmpty())
            {
                quoted = true;
            }
            else if (c == ',')
            {
                fields.add(field.toString());
                field.setLength(0);
            }
            else if (c == '\n')
            {
                break;
            }
            else if (c == '\r')
            {
                int next = aIn.read();
                if (next >= 0 && next != '\n')
                {
                    aIn.unread(next);
                }
                break;
            }
            else
            {
                field.append((char) c);
            }
            c = aIn.read();
        }
        fields.add(field.toString());
        return fields;
    }


    private static String upper(String aText)
    {
        return aText.toUpperCase(Locale.ROOT);
    }

}
