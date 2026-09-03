package net.cumba.corej.core.metadata.dictionary;

import static net.cumba.corej.core.metadata.dictionary.RawDictionaryFiles.cells;
import static net.cumba.corej.core.metadata.dictionary.RawDictionaryFiles.putAll;
import static net.cumba.corej.core.metadata.dictionary.RawDictionaryFiles.reader;
import static net.cumba.corej.core.metadata.dictionary.RawDictionaryFiles.soleMatching;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Converts the CDISC SEND controlled-terminology distribution into the house format's
 * {@code neoplasm} dictionary.
 *
 * <h2>Input</h2>
 *
 * <p>
 * {@code SEND Terminology.txt} (the NCI EVS mirror publishes it as {@code SEND_Terminology.txt};
 * both spellings are accepted): tab-separated with a header row. Column 0 is the concept code,
 * column 1 the <i>codelist</i> code, column 4 the CDISC submission value. The rows wanted are those
 * whose codelist code is {@code C88025} — the {@code Neoplasm Type} codelist, 310 terms in the
 * 2026-03-27 release (140 {@code , BENIGN} + 170 {@code , MALIGNANT}). A row with an <b>empty</b>
 * codelist code is the codelist's own header row, not a member, and is excluded by the same
 * equality test.
 * </p>
 *
 * <h2>No {@code levels}, and no base names</h2>
 *
 * <p>
 * The single rule that reads this dictionary asks for a term's neoplasm <i>class</i>, not its
 * membership, so the document carries {@code attributes.neoplasm} and nothing else — emitting a
 * level would publish a term type no rule names.
 * </p>
 *
 * <p>
 * ⛔ <b>The key is the full submission value, verbatim.</b> 44 base names occur under <em>both</em>
 * classes — {@code ADENOMA, BENIGN} and {@code ADENOMA, MALIGNANT} — so keying on a stripped base
 * name would let one class silently overwrite the other for a quarter of the codelist. Rows ending
 * in neither suffix carry no class and are skipped rather than guessed at.
 * </p>
 *
 * <h2>Version</h2>
 *
 * <p>
 * The release date from the sibling {@code SEND Publication Date Stamp.txt}, e.g.
 * {@code 2026-03-27}. Absent that file, the empty string — which the installer treats as "not
 * installable", never as a guess.
 * </p>
 *
 * <p>
 * ⛔ The data and the version come from files matched by <b>different</b> globs, so both lookups
 * refuse a directory in which their glob matches more than one file: with two releases unpacked
 * side by side, "first in name order" would pair one release's terminology with the other's date
 * stamp — 2023 data stamped {@code 2026-03-27}, silently.
 * </p>
 */
public final class NeoplasmConverter implements DictionaryConverter
{

    /** The {@code Neoplasm Type} codelist. */
    private static final String CODELIST = "C88025";

    private static final String TERMINOLOGY_GLOB = "SEND[ _]Terminology*.txt";

    private static final String DATE_STAMP_GLOB = "SEND[ _]Publication[ _]Date[ _]Stamp*.txt";

    private static final Pattern RELEASE_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private static final int CODELIST_CODE_AT = 1;

    private static final int SUBMISSION_VALUE_AT = 4;

    private static final Map<String, String> CLASS_SUFFIXES = Map.of(", BENIGN", "BENIGN",
            ", MALIGNANT", "MALIGNANT");

    @Override
    public String type()
    {
        return "neoplasm";
    }


    @Override
    public ObjectNode convert(Path aRawDir) throws IOException
    {
        Path file = soleMatching(aRawDir, TERMINOLOGY_GLOB);
        if (file == null)
        {
            throw new NoSuchFileException(aRawDir.toString(), null,
                    "the SEND CT distribution must contain a " + TERMINOLOGY_GLOB + " file");
        }

        Map<String, String> classes = new LinkedHashMap<>();
        try (BufferedReader in = reader(file))
        {
            in.readLine(); // the header row
            for (String line = in.readLine(); line != null; line = in.readLine())
            {
                String[] parts = cells(line);
                if (parts.length <= SUBMISSION_VALUE_AT
                        || !CODELIST.equals(parts[CODELIST_CODE_AT].trim()))
                {
                    continue;
                }
                String value = parts[SUBMISSION_VALUE_AT].trim();
                String neoplasmClass = classOf(value);
                if (neoplasmClass != null)
                {
                    classes.putIfAbsent(value, neoplasmClass);
                }
            }
        }

        ObjectNode root = JsonNodeFactory.instance.objectNode();
        putAll(root.putObject("attributes").putObject("neoplasm"), classes);
        return root;
    }


    @Override
    public String versionOf(Path aRawDir) throws IOException
    {
        Path stamp = soleMatching(aRawDir, DATE_STAMP_GLOB);
        if (stamp == null)
        {
            return "";
        }
        try (BufferedReader in = reader(stamp))
        {
            for (String line = in.readLine(); line != null; line = in.readLine())
            {
                Matcher m = RELEASE_DATE.matcher(line);
                if (m.find())
                {
                    return m.group();
                }
            }
        }
        return "";
    }


    /**
     * The class a submission value declares in its own suffix, or {@code null} when it declares
     * none.
     */
    private static @Nullable String classOf(String aSubmissionValue)
    {
        for (Map.Entry<String, String> e : CLASS_SUFFIXES.entrySet())
        {
            if (aSubmissionValue.endsWith(e.getKey()))
            {
                return e.getValue();
            }
        }
        return null;
    }

}
