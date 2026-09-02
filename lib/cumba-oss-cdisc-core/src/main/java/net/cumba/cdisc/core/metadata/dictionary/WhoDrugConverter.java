package net.cumba.cdisc.core.metadata.dictionary;

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
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converts a WHODrug <b>B3</b> distribution — fixed-width, column-positional — into the house
 * format.
 *
 * <h2>Input</h2>
 *
 * <ul>
 * <li>{@code DD.txt} — drug names. Per line: {@code [0:6]} the drug record number, {@code [6:8]}
 * sequence 1, {@code [8:11]} sequence 2, {@code [30:]} the drug name. The record's <b>preferred
 * name</b> is the row with sequence 1 {@code 01} <em>and</em> sequence 2 {@code 001} — B3
 * identifies a product by DrugRecNo + Seq1 + Seq2, and trade names conventionally share sequence 1
 * {@code 01} at sequence 2 {@code 002} and up, so filtering on sequence 1 alone would let every
 * such trade name into the preferred-name level.</li>
 * <li>{@code INA.txt} — the full ATC index. Per line: {@code [0:7]} the ATC code, {@code [7]} the
 * level digit, {@code [8:]} the ATC text.</li>
 * <li>{@code version.txt} — the release, encoded in the first line's tail:
 * {@code upper(v[-5:-2]) + "_20" + v[-2:]}, so a line ending {@code GLOBALB3Sep20} names release
 * {@code SEP_2020}.</li>
 * </ul>
 *
 * <h2>⛔ {@code ATCCD} comes from {@code INA.txt}, never {@code DDA.txt}</h2>
 *
 * <p>
 * {@code DDA.txt} holds only the ATC codes the licensee's drug records happen to use — a subset of
 * the classification. Building {@code levels.ATCCD} from it would make {@code FDA-SD1346} fire on
 * lawful ATC values that no local drug carries, so the level is built from the full index in
 * {@code INA.txt} and {@code DDA.txt} is not read at all.
 * </p>
 *
 * <h2>{@code levels.PT} is preferred names only</h2>
 *
 * <p>
 * {@code FDA-SD1344} checks {@code --DECOD} against {@code levels.PT}. Emitting every
 * {@code DD.txt} row there would let any trade name pass as a preferred name, so only the
 * sequence-1 {@code 01} / sequence-2 {@code 001} rows are published at that level.
 * </p>
 *
 * <h2>⛔ {@code pairs.whodrug} is keyed by the reported drug name</h2>
 *
 * <p>
 * {@code CDISC-CG0096} is {@code dictionary_has_decode(CMTRT, …)}, and {@code CMTRT} is the
 * sponsor's verbatim reported term — the engine's lookup is a {@code containsKey} on that value. So
 * the registry maps <b>every</b> {@code DD.txt} name, preferred and non-preferred alike, to the
 * preferred name of its drug record. Keying it by drug record number instead would answer for no
 * reported term at all, leaving {@code CDISC-CG0096} exactly as vacuous as the empty {@code pairs}
 * it replaces. A record with no sequence-1 {@code 01} row has no preferred name to decode to and
 * contributes no pair.
 * </p>
 *
 * <h2>One preferred form per term <em>within a level</em>, enforced loudly</h2>
 *
 * <p>
 * Each published level — preferred drug names, ATC texts, ATC codes — keeps its own preferred-form
 * table, and a genuine <em>within-level</em> case conflict (two of the vendor's own spellings in
 * one level folding to the same term) aborts the conversion with an exception naming both spellings
 * rather than silently picking one. Levels are <b>not</b> checked against each other: WHO writes B3
 * drug names upper-case but level-5 ATC substance texts lower/mixed case, so a drug named
 * {@code IBUPROFEN} coexisting with an ATC text {@code Ibuprofen} is the normal case, not a defect
 * — the engine consults only the level a rule's {@code dictionary_term_type} names, so both rules
 * answer correctly (owner ruling; a global table made every real WHODrug distribution
 * uninstallable). Pair keys are sourced from the drug-name table, so a reported name that is — up
 * to case — a published preferred name is written in that name's preferred form (an identity
 * mapping on real B3 data, whose names are uniformly upper-case).
 * </p>
 */
public final class WhoDrugConverter implements DictionaryConverter
{

    private static final String DRUG_FILE = "DD.txt";

    private static final String ATC_FILE = "INA.txt";

    private static final String VERSION_FILE = "version.txt";

    private static final String PREFERRED_SEQUENCE_1 = "01";

    private static final String PREFERRED_SEQUENCE_2 = "001";

    private static final int RECORD_END = 6;

    private static final int SEQUENCE_1_END = 8;

    private static final int SEQUENCE_2_END = 11;

    private static final int NAME_AT = 30;

    private static final int ATC_CODE_END = 7;

    private static final int ATC_TEXT_AT = 8;

    /** One {@code DD.txt} row: the drug record it belongs to, its sequences 1 and 2, its name. */
    private record DrugName(String record, String sequence1, String sequence2, String name)
    {
    }

    @Override
    public String type()
    {
        return "whodrug";
    }


    @Override
    public ObjectNode convert(Path aRawDir) throws IOException
    {
        List<DrugName> drugNames = readDrugNames(requiredFile(aRawDir, DRUG_FILE));
        Map<String, String> preferredNameByRecord = new LinkedHashMap<>();
        for (DrugName row : drugNames)
        {
            if (PREFERRED_SEQUENCE_1.equals(row.sequence1())
                    && PREFERRED_SEQUENCE_2.equals(row.sequence2()))
            {
                preferredNameByRecord.putIfAbsent(row.record(), row.name());
            }
        }

        // One preferred-form table PER LEVEL — the engine never compares across levels, and WHO's
        // own casing conventions differ between them (see the class comment).
        Map<String, String> namesPreferred = new LinkedHashMap<>();
        Map<String, String> namesLevel = new LinkedHashMap<>();
        for (String name : preferredNameByRecord.values())
        {
            register(namesPreferred, name, DRUG_FILE + " drug names");
            namesLevel.putIfAbsent(upper(name), name);
        }

        Map<String, String> atcCodesLevel = new LinkedHashMap<>();
        Map<String, String> atcTextsLevel = new LinkedHashMap<>();
        readAtcIndex(requiredFile(aRawDir, ATC_FILE), atcCodesLevel, atcTextsLevel);

        Map<String, String> pairs = new LinkedHashMap<>();
        for (DrugName row : drugNames)
        {
            String decode = preferredNameByRecord.get(row.record());
            if (decode != null)
            {
                pairs.putIfAbsent(namesPreferred.getOrDefault(upper(row.name()), row.name()),
                        decode);
            }
        }

        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode levels = root.putObject("levels");
        putAll(levels.putObject("PT"), namesLevel);
        putAll(levels.putObject("ATC"), atcTextsLevel);
        putAll(levels.putObject("ATCCD"), atcCodesLevel);
        putAll(root.putObject("pairs").putObject("whodrug"), pairs);
        return root;
    }


    @Override
    public String versionOf(Path aRawDir) throws IOException
    {
        Path file = aRawDir.resolve(VERSION_FILE);
        if (!Files.isRegularFile(file))
        {
            return "";
        }
        String v = "";
        try (BufferedReader in = reader(file))
        {
            // The first non-empty line names the release.
            for (String line = in.readLine(); line != null && v.isEmpty(); line = in.readLine())
            {
                v = line.trim();
            }
        }
        if (v.length() < 5)
        {
            return "";
        }
        return upper(v.substring(v.length() - 5, v.length() - 2)) + "_20"
                + v.substring(v.length() - 2);
    }


    private static List<DrugName> readDrugNames(Path aFile) throws IOException
    {
        List<DrugName> out = new ArrayList<>();
        try (BufferedReader in = reader(aFile))
        {
            for (String line = in.readLine(); line != null; line = in.readLine())
            {
                if (line.length() <= NAME_AT)
                {
                    continue;
                }
                String record = line.substring(0, RECORD_END).trim();
                String name = line.substring(NAME_AT).trim();
                if (record.isEmpty() || name.isEmpty())
                {
                    continue;
                }
                out.add(new DrugName(record, line.substring(RECORD_END, SEQUENCE_1_END),
                        line.substring(SEQUENCE_1_END, SEQUENCE_2_END), name));
            }
        }
        return out;
    }


    private static void readAtcIndex(Path aFile, Map<String, String> aCodesLevel,
            Map<String, String> aTextsLevel)
        throws IOException
    {
        Map<String, String> codesPreferred = new LinkedHashMap<>();
        Map<String, String> textsPreferred = new LinkedHashMap<>();
        try (BufferedReader in = reader(aFile))
        {
            for (String line = in.readLine(); line != null; line = in.readLine())
            {
                String code = line.substring(0, Math.min(ATC_CODE_END, line.length())).trim();
                if (code.isEmpty())
                {
                    continue;
                }
                register(codesPreferred, code, ATC_FILE + " ATC codes");
                aCodesLevel.putIfAbsent(upper(code), code);
                String text = line.length() > ATC_TEXT_AT ? line.substring(ATC_TEXT_AT).trim() : "";
                if (!text.isEmpty())
                {
                    register(textsPreferred, text, ATC_FILE + " ATC texts");
                    aTextsLevel.putIfAbsent(upper(text), text);
                }
            }
        }
    }


    /**
     * Registers a term's preferred form in one level's table. A genuine <em>within-level</em> case
     * conflict — two of the vendor's own spellings in the same level folding to the same term —
     * aborts the conversion rather than silently picking one, because whichever spelling lost would
     * have its case-sensitive rules fire on conformant data. Cross-level divergence is deliberately
     * not checked here (see the class comment).
     */
    private static void register(Map<String, String> aPreferred, String aTerm, String aWhere)
        throws IOException
    {
        String prior = aPreferred.putIfAbsent(upper(aTerm), aTerm);
        if (prior != null && !prior.equals(aTerm))
        {
            throw new IOException("case conflict in the WHODrug distribution: '" + prior + "' and '"
                    + aTerm + "' (" + aWhere + ") fold to the same term but "
                    + "disagree on case; the house format admits one preferred form per term "
                    + "within a level");
        }
    }


    private static Path requiredFile(Path aRawDir, String aFileName) throws IOException
    {
        Path file = aRawDir.resolve(aFileName);
        if (!Files.isRegularFile(file))
        {
            throw new NoSuchFileException(file.toString(), null,
                    "a WHODrug B3 distribution must contain " + aFileName);
        }
        return file;
    }


    private static String upper(String aText)
    {
        return aText.toUpperCase(Locale.ROOT);
    }

}
