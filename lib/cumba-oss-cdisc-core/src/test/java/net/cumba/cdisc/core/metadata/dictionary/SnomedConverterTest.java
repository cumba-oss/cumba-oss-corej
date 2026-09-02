package net.cumba.cdisc.core.metadata.dictionary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The SNOMED converter, and above all the property all 8 SNOMED rules depend on: the term is the
 * synonym, never the Fully Specified Name with its semantic tag.
 */
class SnomedConverterTest
{

    private static final String FILE_NAME = "sct2_Description_Snapshot-en_INT_20240901.txt";

    private final SnomedConverter converter = new SnomedConverter();

    @TempDir
    private Path raw;

    // ------------------------------------------------------------------
    // Happy path
    // ------------------------------------------------------------------

    @Test
    void convertsTermsCodesAndPairs() throws IOException
    {
        writeDistribution(raw);

        ObjectNode doc = converter.convert(raw);

        assertEquals("Headache", doc.path("levels").path("SNOMED").path("HEADACHE").asText());
        assertEquals("25064002", doc.path("levels").path("SNOMEDCD").path("25064002").asText());
        assertEquals("Headache", doc.path("pairs").path("snomed").path("25064002").asText());
    }


    @Test
    void findsDescriptionFilesInNestedRf2Directories() throws IOException
    {
        writeDistribution(Files.createDirectories(raw.resolve("Snapshot").resolve("Terminology")));

        assertEquals(2, converter.convert(raw).path("pairs").path("snomed").size());
    }

    // ------------------------------------------------------------------
    // ⛔ The hazard: emitting Fully Specified Names
    // ------------------------------------------------------------------


    @Test
    void usesTheSynonymAndNeverTheFullySpecifiedName() throws IOException
    {
        writeDistribution(raw);

        JsonNode terms = converter.convert(raw).path("levels").path("SNOMED");

        assertTrue(terms.has("HEADACHE"), terms.toString());
        assertFalse(terms.has("HEADACHE (FINDING)"),
                "the FSN carries the semantic tag no submitted term ever does");
    }


    @Test
    void skipsInactiveRowsAndTakesTheFirstActiveSynonym() throws IOException
    {
        writeDistribution(raw);

        ObjectNode doc = converter.convert(raw);

        assertEquals("Nausea", doc.path("pairs").path("snomed").path("422587007").asText(),
                "the inactive earlier synonym is not the concept's term");
        assertFalse(doc.path("levels").path("SNOMED").has("SICKNESS FEELING"),
                "an inactive synonym publishes no term");
        assertFalse(doc.path("levels").path("SNOMED").has("CEPHALODYNIA"),
                "later synonyms of an already-served concept are not published");
    }

    // ------------------------------------------------------------------
    // ⛔ The hazard (A3): merging Delta/Full/Snapshot, with Full winning
    // ------------------------------------------------------------------


    /**
     * A real RF2 release unpacks to all three sibling views. Term selection is first-wins and
     * {@code "Full" < "Snapshot"} in path order, so merging publishes the Full view's
     * <em>historical</em> spelling and refuses the current one — the 3 case-sensitive
     * {@code _value} rules then fire on every conformant row. The Snapshot view must win, alone.
     */
    @Test
    void snapshotOutranksFullAndDeltaWhenSeveralViewsArePresent() throws IOException
    {
        String synonym = "900000000000013009";
        write(Files.createDirectories(raw.resolve("Delta").resolve("Terminology")),
                "sct2_Description_Delta-en_INT_20240901.txt",
                row("301", "1", "25064002", synonym, "Cephalodynia"));
        write(Files.createDirectories(raw.resolve("Full").resolve("Terminology")),
                "sct2_Description_Full-en_INT_20240901.txt",
                // The historical, since re-cased spelling — still active in its own era.
                row("302", "1", "25064002", synonym, "HEADACHE"));
        write(Files.createDirectories(raw.resolve("Snapshot").resolve("Terminology")),
                "sct2_Description_Snapshot-en_INT_20240901.txt",
                row("303", "1", "25064002", synonym, "Headache"));

        ObjectNode doc = converter.convert(raw);

        assertEquals("Headache", doc.path("pairs").path("snomed").path("25064002").asText(),
                "the Snapshot term wins — not the Full view's obsolete casing, not the Delta");
        assertEquals("Headache", doc.path("levels").path("SNOMED").path("HEADACHE").asText());
        assertFalse(doc.path("levels").path("SNOMED").has("CEPHALODYNIA"),
                "the Delta view's rows are not merged in");
    }


    /** With no Snapshot anywhere, the Full view is the deliberate second choice. */
    @Test
    void fullIsUsedWhenNoSnapshotExists() throws IOException
    {
        String synonym = "900000000000013009";
        write(Files.createDirectories(raw.resolve("Full").resolve("Terminology")),
                "sct2_Description_Full-en_INT_20240901.txt",
                row("302", "1", "25064002", synonym, "Headache"));

        assertEquals("Headache",
                converter.convert(raw).path("pairs").path("snomed").path("25064002").asText());
    }


    /**
     * Two description files surviving the view choice — a language extension beside the
     * international edition — are refused loudly: first-wins merging across languages has exactly
     * the same obsolete-spelling failure mode as merging views.
     */
    @Test
    void refusesTwoDescriptionFilesWithinOneView() throws IOException
    {
        Path terminology = Files.createDirectories(raw.resolve("Snapshot").resolve("Terminology"));
        writeDistribution(terminology);
        write(terminology, "sct2_Description_SpanishExtensionSnapshot-es_INT_20240930.txt",
                row("401", "1", "25064002", "900000000000013009", "Cefalea"));

        IOException thrown = assertThrows(IOException.class, () -> converter.convert(raw));

        assertTrue(thrown.getMessage().contains("one release view in one language"),
                thrown.getMessage());
        assertTrue(thrown.getMessage().contains("SpanishExtensionSnapshot"),
                "the refusal names the files: " + thrown.getMessage());
    }


    /** One header line plus the given data rows, as a description file named {@code aFileName}. */
    private static void write(Path aDir, String aFileName, String... aRows) throws IOException
    {
        String header = "id\teffectiveTime\tactive\tmoduleId\tconceptId\tlanguageCode\ttypeId"
                + "\tterm\tcaseSignificanceId";
        Files.writeString(aDir.resolve(aFileName), header + "\n" + String.join("\n", aRows) + "\n",
                StandardCharsets.UTF_8);
    }


    private static String row(String aId, String aActive, String aConcept, String aType,
            String aTerm)
    {
        return row(aId, aActive, aConcept, aType, aTerm, "900000000000207008",
                "900000000000448009");
    }

    // ------------------------------------------------------------------
    // The document the installer will actually write
    // ------------------------------------------------------------------


    @Test
    void theConvertedDocumentPassesHouseFormatValidation() throws IOException
    {
        writeDistribution(raw);

        ObjectNode doc = converter.convert(raw);
        doc.put("type", "snomed"); // the installer stamps this; the converter must not

        assertEquals(List.of(), HouseFormatValidator.validate("snomed", doc));
    }


    @Test
    void refusesADirectoryWithoutDescriptionFiles()
    {
        assertThrows(NoSuchFileException.class, () -> converter.convert(raw));
    }

    // ------------------------------------------------------------------
    // Version
    // ------------------------------------------------------------------


    @Test
    void versionIsTheDateTokenInTheFileName() throws IOException
    {
        writeDistribution(raw);

        assertEquals("20240901", converter.versionOf(raw));
    }


    @Test
    void reportsNoVersionWhenNoDescriptionFileIsPresent() throws IOException
    {
        assertEquals("", converter.versionOf(raw));
    }


    /**
     * A miniature RF2 description snapshot: for each concept an FSN plus synonyms, with the FSN
     * listed first so taking "the first row" would be caught.
     */
    private static void writeDistribution(Path aDir) throws IOException
    {
        String header = "id\teffectiveTime\tactive\tmoduleId\tconceptId\tlanguageCode\ttypeId"
                + "\tterm\tcaseSignificanceId";
        String module = "900000000000207008";
        String fsn = "900000000000003001";
        String synonym = "900000000000013009";
        String caseId = "900000000000448009";
        Files.writeString(aDir.resolve(FILE_NAME),
                String.join("\n", header,
                        row("101", "1", "25064002", fsn, "Headache (finding)", module, caseId),
                        row("102", "1", "25064002", synonym, "Headache", module, caseId),
                        row("103", "1", "25064002", synonym, "Cephalodynia", module, caseId),
                        row("201", "0", "422587007", synonym, "Sickness feeling", module, caseId),
                        row("202", "1", "422587007", fsn, "Nausea (finding)", module, caseId),
                        row("203", "1", "422587007", synonym, "Nausea", module, caseId), "") + "\n",
                StandardCharsets.UTF_8);
    }


    private static String row(String aId, String aActive, String aConcept, String aType,
            String aTerm, String aModule, String aCaseId)
    {
        return String.join("\t", aId, "20240901", aActive, aModule, aConcept, "en", aType, aTerm,
                aCaseId);
    }

}
