package net.cumba.corej.core.metadata.dictionary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The neoplasm converter: one codelist out of the whole SEND CT distribution, keyed on the full
 * submission value because the base names are not unique across the two classes.
 */
class NeoplasmConverterTest
{

    private final NeoplasmConverter converter = new NeoplasmConverter();

    @TempDir
    private Path raw;

    // ------------------------------------------------------------------
    // Happy path and row selection
    // ------------------------------------------------------------------

    @Test
    void takesOnlyTheNeoplasmTypeCodelist() throws IOException
    {
        writeDistribution();

        JsonNode classes = converter.convert(raw).path("attributes").path("neoplasm");

        assertEquals("BENIGN", classes.path("ADENOMA, BENIGN").asText());
        assertEquals("MALIGNANT", classes.path("LYMPHOMA MALIGNANT, MALIGNANT").asText());
        assertFalse(classes.has("DERMATITIS"), "a term from another codelist is not a neoplasm");
        assertFalse(classes.has("Neoplasm Type"),
                "the codelist's own header row has an empty codelist code and is excluded");
        assertFalse(classes.has("NORMAL"),
                "a submission value declaring neither class is skipped, never guessed at");
        assertEquals(3, classes.size(), classes.toString());
    }

    // ------------------------------------------------------------------
    // ⛔ The hazard: 44 base names carry both classes
    // ------------------------------------------------------------------


    @Test
    void aBaseNameUnderBothClassesYieldsTwoEntries() throws IOException
    {
        writeDistribution();

        JsonNode classes = converter.convert(raw).path("attributes").path("neoplasm");

        assertEquals("BENIGN", classes.path("ADENOMA, BENIGN").asText());
        assertEquals("MALIGNANT", classes.path("ADENOMA, MALIGNANT").asText());
        assertFalse(classes.has("ADENOMA"),
                "keying on the stripped base name would let one class overwrite the other");
    }


    /** The corpus names no neoplasm level; emitting one would publish a term type nothing reads. */
    @Test
    void emitsNoLevelsSection() throws IOException
    {
        writeDistribution();

        ObjectNode doc = converter.convert(raw);

        assertTrue(doc.path("levels").isMissingNode(), doc.toString());
        assertTrue(doc.path("pairs").isMissingNode(), doc.toString());
        assertTrue(doc.path("hierarchy").isMissingNode(), doc.toString());
    }


    /** The NCI EVS mirror publishes the file with an underscore; both spellings must be read. */
    @Test
    void acceptsTheUnderscoreSpellingOfTheFileName() throws IOException
    {
        writeDistribution();
        Files.move(raw.resolve("SEND Terminology.txt"), raw.resolve("SEND_Terminology.txt"));

        assertEquals(3, converter.convert(raw).path("attributes").path("neoplasm").size());
    }

    // ------------------------------------------------------------------
    // ⛔ A9: data and version come from files matched by DIFFERENT globs
    // ------------------------------------------------------------------


    /**
     * With two releases unpacked into one directory, "first in name order" would pair one release's
     * terminology with the other's date stamp — 2023 data stamped {@code 2026-03-27}, silently.
     * Both lookups must refuse instead.
     */
    @Test
    void refusesTwoTerminologyFilesInOneDirectory() throws IOException
    {
        writeDistribution();
        Files.copy(raw.resolve("SEND Terminology.txt"), raw.resolve("SEND Terminology 2023.txt"));

        IOException thrown = assertThrows(IOException.class, () -> converter.convert(raw));

        assertTrue(thrown.getMessage().contains("SEND Terminology 2023.txt"), thrown.getMessage());
    }


    @Test
    void refusesTwoDateStampsInOneDirectory() throws IOException
    {
        writeDistribution();
        Files.writeString(raw.resolve("SEND Publication Date Stamp.txt"),
                "Package\tSchedule\tDate\nQ1 2026\t\t2026-03-27\t\tSEND Terminology\n",
                StandardCharsets.UTF_8);
        Files.writeString(raw.resolve("SEND_Publication_Date_Stamp_2023.txt"),
                "Package\tSchedule\tDate\nQ2 2023\t\t2023-06-30\t\tSEND Terminology\n",
                StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> converter.versionOf(raw),
                "a version picked from one of two stamps could contradict the converted data");
    }

    // ------------------------------------------------------------------
    // The document the installer will actually write
    // ------------------------------------------------------------------


    @Test
    void theConvertedDocumentPassesHouseFormatValidation() throws IOException
    {
        writeDistribution();

        ObjectNode doc = converter.convert(raw);
        doc.put("type", "neoplasm"); // the installer stamps this; the converter must not

        assertEquals(List.of(), HouseFormatValidator.validate("neoplasm", doc));
    }

    // ------------------------------------------------------------------
    // Version
    // ------------------------------------------------------------------


    @Test
    void readsTheReleaseDateFromThePublicationStamp() throws IOException
    {
        writeDistribution();
        Files.writeString(raw.resolve("SEND Publication Date Stamp.txt"),
                "Package\tSchedule\tDate\n" + "Q1 2026\t\t2026-03-27\t\tSEND Terminology\n",
                StandardCharsets.UTF_8);

        assertEquals("2026-03-27", converter.versionOf(raw));
    }


    @Test
    void reportsNoVersionWhenTheStampIsAbsent() throws IOException
    {
        writeDistribution();

        assertEquals("", converter.versionOf(raw),
                "an empty version is what stops the installer writing an unversioned store entry");
    }


    private void writeDistribution() throws IOException
    {
        Files.writeString(raw.resolve("SEND Terminology.txt"), String.join("\n",
                "Code\tCodelist Code\tCodelist Extensible (Yes/No)\tCodelist Name\t"
                        + "CDISC Submission Value\tCDISC Synonym(s)\tCDISC Definition",
                "C88025\t\tYes\tNeoplasm Type\tNeoplasm Type\t\tA classification of neoplasms.",
                "C2853\tC88025\tYes\tNeoplasm Type\tADENOMA, BENIGN\t\t",
                "C3768\tC88025\tYes\tNeoplasm Type\tADENOMA, MALIGNANT\t\t",
                "C3208\tC88025\tYes\tNeoplasm Type\tLYMPHOMA MALIGNANT, MALIGNANT\t\t",
                "C9999\tC88025\tYes\tNeoplasm Type\tNORMAL\t\t",
                "C0001\tC66768\tYes\tSEND Test Name\tDERMATITIS\t\t", "") + "\n",
                StandardCharsets.UTF_8);
    }

}
