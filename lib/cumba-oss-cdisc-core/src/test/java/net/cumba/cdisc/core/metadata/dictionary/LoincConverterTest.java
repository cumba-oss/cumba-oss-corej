package net.cumba.cdisc.core.metadata.dictionary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * The LOINC converter, and above all the property the upstream Python engine gets wrong: a quoted
 * field containing an embedded newline must not tear its row — or the row after it — apart.
 */
class LoincConverterTest
{

    private final LoincConverter converter = new LoincConverter();

    @TempDir
    private Path raw;

    // ------------------------------------------------------------------
    // Happy path
    // ------------------------------------------------------------------

    @Test
    void convertsCodesAndDisplayNames() throws IOException
    {
        writeDistribution(raw);

        ObjectNode doc = converter.convert(raw);

        assertEquals("1558-6", doc.path("levels").path("LOINC").path("1558-6").asText());
        assertEquals("Fasting glucose [Mass/volume] in Serum or Plasma",
                doc.path("pairs").path("loinc").path("1558-6").asText(),
                "the licence's incorporation clause requires every extracted code to carry "
                        + "its display name");
    }


    @Test
    void findsTheTableUnderTheLoincTableDirectory() throws IOException
    {
        writeDistribution(Files.createDirectories(raw.resolve("LoincTable")));

        assertEquals(3, converter.convert(raw).path("levels").path("LOINC").size());
    }

    // ------------------------------------------------------------------
    // ⛔ The hazard: line-by-line CSV reading
    // ------------------------------------------------------------------


    @Test
    void aQuotedFieldWithAnEmbeddedNewlineDoesNotCorruptItsRowOrTheNext() throws IOException
    {
        writeDistribution(raw);

        ObjectNode doc = converter.convert(raw);

        assertEquals(3, doc.path("levels").path("LOINC").size(),
                doc.path("levels").path("LOINC").toString());
        assertEquals("Glucose [Mass/volume] in Serum or Plasma",
                doc.path("pairs").path("loinc").path("2345-7").asText(),
                "the row carrying the embedded newline still parses whole");
        assertTrue(doc.path("levels").path("LOINC").has("718-7"),
                "and the row after it is not swallowed");
    }

    // ------------------------------------------------------------------
    // The document the installer will actually write
    // ------------------------------------------------------------------


    @Test
    void theConvertedDocumentPassesHouseFormatValidation() throws IOException
    {
        writeDistribution(raw);

        ObjectNode doc = converter.convert(raw);
        doc.put("type", "loinc"); // the installer stamps this; the converter must not

        assertEquals(List.of(), HouseFormatValidator.validate("loinc", doc));
    }


    @Test
    void refusesADirectoryWithoutTheTable()
    {
        assertThrows(NoSuchFileException.class, () -> converter.convert(raw));
    }


    @Test
    void refusesATableWithoutTheCodeColumn() throws IOException
    {
        Files.writeString(raw.resolve("Loinc.csv"), "\"FOO\",\"LONG_COMMON_NAME\"\n\"x\",\"y\"\n",
                StandardCharsets.UTF_8);

        IOException thrown = assertThrows(IOException.class, () -> converter.convert(raw));

        assertTrue(thrown.getMessage().contains("LOINC_NUM"), thrown.getMessage());
    }


    @Test
    void refusesAnEmptyTable() throws IOException
    {
        Files.writeString(raw.resolve("Loinc.csv"), "", StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> converter.convert(raw));
    }


    /**
     * A2 — a {@code Loinc.csv} re-saved with a UTF-8 BOM must still convert: without the reader's
     * BOM skip, the header lookup sees {@code U+FEFF LOINC_NUM}, matches nothing, and throws a
     * message blaming a column that is in fact present.
     */
    @Test
    void aLeadingBomDoesNotBreakTheHeaderColumnLookup() throws IOException
    {
        Files.writeString(raw.resolve("Loinc.csv"),
                "\uFEFF" + "\"LOINC_NUM\",\"LONG_COMMON_NAME\",\"VersionLastChanged\"\n"
                        + "\"1558-6\",\"Fasting glucose\",\"2.77\"\n",
                StandardCharsets.UTF_8);

        assertEquals("1558-6",
                converter.convert(raw).path("levels").path("LOINC").path("1558-6").asText());
    }


    @Test
    void parsesCrlfLineEndings() throws IOException
    {
        Files.writeString(raw.resolve("Loinc.csv"),
                "\"LOINC_NUM\",\"LONG_COMMON_NAME\",\"VersionLastChanged\"\r\n"
                        + "\"1558-6\",\"Fasting glucose\",\"2.77\"\r\n",
                StandardCharsets.UTF_8);

        assertEquals(1, converter.convert(raw).path("levels").path("LOINC").size());
        assertEquals("2.77", converter.versionOf(raw));
    }

    // ------------------------------------------------------------------
    // Version
    // ------------------------------------------------------------------


    @Test
    void versionIsTheNumericallyGreatestVersionLastChanged() throws IOException
    {
        writeDistribution(raw);

        assertEquals("2.77", converter.versionOf(raw),
                "2.77 outranks 2.9 numerically, though not lexicographically");
    }


    @Test
    void anUnparsableVersionValueDemotesTheComparisonInsteadOfCrashing() throws IOException
    {
        Files.writeString(raw.resolve("Loinc.csv"),
                "\"LOINC_NUM\",\"LONG_COMMON_NAME\",\"VersionLastChanged\"\n"
                        + "\"1558-6\",\"Fasting glucose\",\"2.77\"\n"
                        + "\"2345-7\",\"Glucose\",\"Beta-2.9\"\n",
                StandardCharsets.UTF_8);

        assertEquals("Beta-2.9", converter.versionOf(raw), "lexicographic fallback");
    }


    @Test
    void reportsNoVersionWhenNoTableIsPresent() throws IOException
    {
        assertEquals("", converter.versionOf(raw));
    }


    @Test
    void reportsNoVersionWhenTheTableHasNoVersionColumn() throws IOException
    {
        Files.writeString(raw.resolve("Loinc.csv"),
                "\"LOINC_NUM\",\"LONG_COMMON_NAME\"\n\"1558-6\",\"Fasting glucose\"\n",
                StandardCharsets.UTF_8);

        assertEquals("", converter.versionOf(raw));
    }


    /**
     * A miniature {@code Loinc.csv}: quoted header, an embedded newline plus doubled quotes and a
     * comma inside the second row's COMPONENT field, and a third row behind it.
     */
    private static void writeDistribution(Path aDir) throws IOException
    {
        Files.writeString(aDir.resolve("Loinc.csv"),
                "\"LOINC_NUM\",\"COMPONENT\",\"LONG_COMMON_NAME\",\"VersionLastChanged\"\n"
                        + "\"1558-6\",\"Glucose^post CFst\","
                        + "\"Fasting glucose [Mass/volume] in Serum or Plasma\",\"2.77\"\n"
                        + "\"2345-7\",\"Glucose\nsecond \"\"line\"\", with comma\","
                        + "\"Glucose [Mass/volume] in Serum or Plasma\",\"2.9\"\n"
                        + "\"718-7\",\"Hemoglobin\",\"Hemoglobin [Mass/volume] in Blood\","
                        + "\"2.50\"\n",
                StandardCharsets.UTF_8);
    }

}
