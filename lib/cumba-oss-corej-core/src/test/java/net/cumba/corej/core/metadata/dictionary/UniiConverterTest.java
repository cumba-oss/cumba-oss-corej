package net.cumba.corej.core.metadata.dictionary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * The UNII converter, and above all the property nine case-sensitive rules depend on: the display
 * name is copied, never tidied.
 */
class UniiConverterTest
{

    private final UniiConverter converter = new UniiConverter();

    @TempDir
    private Path raw;

    // ------------------------------------------------------------------
    // Happy path
    // ------------------------------------------------------------------

    @Test
    void convertsCodesNamesAndPairs() throws IOException
    {
        writeDistribution();

        ObjectNode doc = converter.convert(raw);

        assertEquals("R16CO5Y76E", doc.path("levels").path("UNII").path("R16CO5Y76E").asText());
        assertEquals("ASPIRIN", doc.path("levels").path("SRS").path("ASPIRIN").asText());
        assertEquals("ASPIRIN", doc.path("pairs").path("unii").path("R16CO5Y76E").asText());
    }

    // ------------------------------------------------------------------
    // ⛔ The hazard: normalising case
    // ------------------------------------------------------------------


    @Test
    void keepsTheDisplayNameVerbatimAndFoldsOnlyTheKey() throws IOException
    {
        writeDistribution();

        JsonNode names = converter.convert(raw).path("levels").path("SRS");

        assertEquals("von Willebrand Factor", names.path("VON WILLEBRAND FACTOR").asText(),
                "the FDA's own spelling, not a title-cased or upper-cased rewrite");
        assertFalse(names.has("von Willebrand Factor"), "the key is the case-fold");
        assertEquals("von Willebrand Factor",
                converter.convert(raw).path("pairs").path("unii").path("K9M7X2Q1P4").asText(),
                "and the decode is the same bytes");
    }


    /**
     * Two UNIIs whose display names differ only in case cannot both be published: {@code levels}
     * admits one preferred form per term. The first wins; the second is dropped rather than
     * rewritten, and its code stays available.
     */
    @Test
    void dropsALaterDifferentlyCasedDisplayNameWithoutRewritingIt() throws IOException
    {
        writeDistribution();
        RawDictionaryFiles.resetDroppedTermCount();

        ObjectNode doc = converter.convert(raw);

        assertEquals("ASPIRIN", doc.path("levels").path("SRS").path("ASPIRIN").asText(),
                "the first spelling stands");
        assertFalse(doc.path("pairs").path("unii").has("Q8M3T5R7W9"),
                "the conflicting record asserts no decode");
        assertTrue(doc.path("levels").path("UNII").has("Q8M3T5R7W9"),
                "but the UNII itself is still a valid code and keeps answering");
        assertEquals(1, RawDictionaryFiles.droppedTermCount(),
                "A8 — the refusal is tallied for the installer to surface, never silent");
    }


    @Test
    void skipsRecordsWithNoCodeOrNoDisplayName() throws IOException
    {
        writeDistribution();

        ObjectNode doc = converter.convert(raw);

        assertEquals(4, doc.path("levels").path("UNII").size(),
                doc.path("levels").path("UNII").toString());
        assertFalse(doc.path("levels").path("UNII").has("N0N4M3D0O0"),
                "a record with no display name carries no term to check");
        assertFalse(doc.path("levels").path("SRS").has("NO CODE SUBSTANCE"),
                "and a name with no UNII cannot be paired with anything");
    }

    // ------------------------------------------------------------------
    // The document the installer will actually write
    // ------------------------------------------------------------------


    @Test
    void theConvertedDocumentPassesHouseFormatValidation() throws IOException
    {
        writeDistribution();

        ObjectNode doc = converter.convert(raw);
        doc.put("type", "unii"); // the installer stamps this; the converter must not

        assertEquals(List.of(), HouseFormatValidator.validate("unii", doc));
    }

    // ------------------------------------------------------------------
    // Version
    // ------------------------------------------------------------------


    @Test
    void readsTheVersionFromTheFileName() throws IOException
    {
        writeDistribution();

        assertEquals("4Aug2026", converter.versionOf(raw));
    }


    @Test
    void reportsNoVersionWhenNoRecordFileIsPresent() throws IOException
    {
        assertEquals("", converter.versionOf(raw));
    }


    private void writeDistribution() throws IOException
    {
        Files.writeString(raw.resolve("UNII_Records_4Aug2026.txt"),
                String.join("\n", "UNII\tDISPLAY_NAME\tMF\tINCHIKEY",
                        "R16CO5Y76E\tASPIRIN\tC9H8O4\tBSYNRYMUTXBXSQ",
                        "K9M7X2Q1P4\tvon Willebrand Factor\t\t", "Q8M3T5R7W9\tAspirin\tC9H8O4\t",
                        "N0N4M3D0O0\t\t\t", "\tNO CODE SUBSTANCE\t\t",
                        "H4L5F6D7S8\tSODIUM CHLORIDE\tClNa\t", "") + "\n",
                StandardCharsets.UTF_8);
    }

}
