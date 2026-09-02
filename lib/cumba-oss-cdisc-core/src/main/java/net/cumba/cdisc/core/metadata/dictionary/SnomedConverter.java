package net.cumba.cdisc.core.metadata.dictionary;

import static net.cumba.cdisc.core.metadata.dictionary.RawDictionaryFiles.cells;
import static net.cumba.cdisc.core.metadata.dictionary.RawDictionaryFiles.fileNameOf;
import static net.cumba.cdisc.core.metadata.dictionary.RawDictionaryFiles.putAll;
import static net.cumba.cdisc.core.metadata.dictionary.RawDictionaryFiles.reader;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Converts an unpacked SNOMED CT release — an RF2 edition or the Global Patient Set — into the
 * house format.
 *
 * <h2>Input</h2>
 *
 * <p>
 * One {@code sct2_Description*.txt} file found under the distribution directory (RF2 releases nest
 * them under {@code Snapshot/Terminology/} and the like, so the search is recursive). Tab-separated
 * with a header row; the columns are resolved by name — {@code active}, {@code conceptId},
 * {@code typeId}, {@code term}. Only rows with {@code active == "1"} are read.
 * </p>
 *
 * <h2>⛔ One release view, chosen deliberately</h2>
 *
 * <p>
 * A real RF2 release unpacks to <b>three sibling views</b> — {@code Delta/}, {@code Full/},
 * {@code Snapshot/} — each with its own description file. Merging them is not harmless duplication:
 * term selection is first-come, {@code "Delta" < "Full" < "Snapshot"} in path order, and a Full
 * file carries every <em>historical</em> row still flagged active in its own era — so a concept
 * whose term was ever re-cased would publish the obsolete spelling and refuse the current one,
 * making the case-sensitive term rules fire on every conformant row. The converter therefore uses
 * only the {@code Snapshot} files when any path carries that token, else only {@code Full}, else
 * whatever matched — and <b>refuses</b> when more than one file remains after that choice (a
 * language extension, or two editions unpacked together), because silently merging languages or
 * editions has the same first-wins failure mode.
 * </p>
 *
 * <h2>⛔ The synonym, never the Fully Specified Name</h2>
 *
 * <p>
 * A description row's {@code typeId} distinguishes the Synonym ({@code 900000000000013009}) from
 * the Fully Specified Name ({@code 900000000000003001}). An FSN reads {@code Headache (finding)} —
 * the semantic tag is part of the string — while the data the 8 SNOMED rules check carries the
 * plain term. Emitting FSNs would therefore make every one of those rules answer false on every
 * conformant row, so each concept's term is its <b>first active synonym</b> in file order (the
 * language refsets' preferred/acceptable ranking is not consulted). A synonym whose spelling
 * case-conflicts with an already-published term is passed over in favour of the concept's next
 * active synonym — never rewritten, which would assert a spelling the release does not carry for
 * that concept.
 * </p>
 *
 * <p>
 * ⚠ Intended for Snapshot-style files (the GPS is one). A Full-history file — read only when the
 * distribution offers no Snapshot — is read as-is: rows are not collapsed by {@code effectiveTime},
 * so a description inactivated by a later row still contributes its earlier {@code active == "1"}
 * states.
 * </p>
 *
 * <h2>Version</h2>
 *
 * <p>
 * The 8-digit date token in the description file's name —
 * {@code sct2_Description_Full-en_INT_20240901.txt} &rarr; {@code 20240901}.
 * </p>
 */
public final class SnomedConverter implements DictionaryConverter
{

    private static final String FILE_PREFIX = "sct2_Description";

    private static final String FILE_SUFFIX = ".txt";

    private static final String SYNONYM_TYPE = "900000000000013009";

    private static final String ACTIVE_COLUMN = "active";

    private static final String CONCEPT_COLUMN = "conceptId";

    private static final String TYPE_COLUMN = "typeId";

    private static final String TERM_COLUMN = "term";

    private static final Pattern RELEASE_DATE = Pattern.compile("\\d{8}");

    @Override
    public String type()
    {
        return "snomed";
    }


    @Override
    public ObjectNode convert(Path aRawDir) throws IOException
    {
        List<Path> files = descriptionFiles(aRawDir);
        if (files.isEmpty())
        {
            throw new NoSuchFileException(aRawDir.toString(), null,
                    "a SNOMED CT distribution must contain a " + FILE_PREFIX + "*" + FILE_SUFFIX
                            + " file");
        }

        Map<String, String> preferred = new LinkedHashMap<>();
        Map<String, String> termsLevel = new LinkedHashMap<>();
        Map<String, String> codesLevel = new LinkedHashMap<>();
        Map<String, String> pairs = new LinkedHashMap<>();

        for (Path file : files)
        {
            readDescriptions(file, preferred, termsLevel, codesLevel, pairs);
        }

        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode levels = root.putObject("levels");
        putAll(levels.putObject("SNOMED"), termsLevel);
        putAll(levels.putObject("SNOMEDCD"), codesLevel);
        putAll(root.putObject("pairs").putObject("snomed"), pairs);
        return root;
    }


    @Override
    public String versionOf(Path aRawDir) throws IOException
    {
        List<Path> files = descriptionFiles(aRawDir);
        if (files.isEmpty())
        {
            return "";
        }
        Matcher m = RELEASE_DATE.matcher(fileNameOf(files.get(0)));
        String date = "";
        while (m.find())
        {
            // The last 8-digit token: RF2 names put the release date at the end, after the
            // module and namespace tokens.
            date = m.group();
        }
        return date;
    }


    private static void readDescriptions(Path aFile, Map<String, String> aPreferred,
            Map<String, String> aTermsLevel, Map<String, String> aCodesLevel,
            Map<String, String> aPairs)
        throws IOException
    {
        try (BufferedReader in = reader(aFile))
        {
            String header = in.readLine();
            if (header == null)
            {
                return;
            }
            int activeAt = columnOf(header, ACTIVE_COLUMN, aFile);
            int conceptAt = columnOf(header, CONCEPT_COLUMN, aFile);
            int typeAt = columnOf(header, TYPE_COLUMN, aFile);
            int termAt = columnOf(header, TERM_COLUMN, aFile);
            int width = Math.max(Math.max(activeAt, conceptAt), Math.max(typeAt, termAt));
            for (String line = in.readLine(); line != null; line = in.readLine())
            {
                String[] parts = cells(line);
                if (parts.length <= width || !"1".equals(parts[activeAt].trim()))
                {
                    continue;
                }
                String concept = parts[conceptAt].trim();
                String term = parts[termAt].trim();
                if (concept.isEmpty() || term.isEmpty())
                {
                    continue;
                }
                aCodesLevel.putIfAbsent(upper(concept), concept);
                if (!SYNONYM_TYPE.equals(parts[typeAt].trim()) || aPairs.containsKey(concept))
                {
                    continue;
                }
                if (!accept(aPreferred, term))
                {
                    continue;
                }
                aTermsLevel.putIfAbsent(upper(term), term);
                aPairs.put(concept, term);
            }
        }
    }


    /**
     * The description file(s) of the release view this conversion reads — see the class comment. At
     * most one file is ever returned; several survivors are a refusal, not a merge.
     */
    private static List<Path> descriptionFiles(Path aRawDir) throws IOException
    {
        if (!Files.isDirectory(aRawDir))
        {
            return List.of();
        }
        List<Path> all;
        try (Stream<Path> walk = Files.walk(aRawDir))
        {
            all = walk.filter(Files::isRegularFile).filter(SnomedConverter::isDescriptionFile)
                    .sorted().toList();
        }
        return selectReleaseView(aRawDir, all);
    }


    /**
     * Restricts the matched description files to one release view — {@code Snapshot} when any path
     * carries that token, else {@code Full}, else the whole flat set — and refuses when more than
     * one file still remains (several languages, editions or extensions unpacked together).
     */
    private static List<Path> selectReleaseView(Path aRawDir, List<Path> aAll) throws IOException
    {
        List<Path> selected = filterByViewToken(aRawDir, aAll, "Snapshot");
        String view = "Snapshot";
        if (selected.isEmpty())
        {
            selected = filterByViewToken(aRawDir, aAll, "Full");
            view = "Full";
        }
        if (selected.isEmpty())
        {
            selected = aAll;
            view = "flat";
        }
        if (selected.size() > 1)
        {
            throw new IOException("several SNOMED description files remain after selecting the "
                    + view + " view: "
                    + selected.stream().map(aRawDir::relativize).map(Path::toString).toList()
                    + " — a conversion reads exactly one release view in one language; point the "
                    + "raw directory at a single edition, or remove the extra files");
        }
        return selected;
    }


    /** The files whose path below the distribution root carries the given RF2 view token. */
    private static List<Path> filterByViewToken(Path aRawDir, List<Path> aAll, String aToken)
    {
        return aAll.stream().filter(p -> aRawDir.relativize(p).toString().contains(aToken))
                .toList();
    }


    private static boolean isDescriptionFile(Path aFile)
    {
        String name = fileNameOf(aFile);
        return name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX);
    }


    /** The index of a named column in the header row. */
    private static int columnOf(String aHeader, String aName, Path aFile) throws IOException
    {
        String[] parts = cells(aHeader);
        for (int i = 0; i < parts.length; i++)
        {
            if (parts[i].trim().equalsIgnoreCase(aName))
            {
                return i;
            }
        }
        throw new IOException(aFile + " has no " + aName + " column; found " + aHeader);
    }


    /**
     * Registers a term's preferred form, reporting whether this spelling may be published. The
     * first spelling of a term wins; a later, differently-cased one is refused rather than
     * normalised, because normalising is what breaks the case-sensitive term rules. Every refusal
     * is tallied via {@link RawDictionaryFiles#countDroppedTerm()} for the installer to surface.
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
