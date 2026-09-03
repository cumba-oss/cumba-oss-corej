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
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The MedDRA converter, against a synthetic {@code MedAscii} fixture (no licensed data is present
 * in this repository). The properties that matter: the hierarchy is built from primary-path rows
 * only, keyed at HLT and HLGT, with names from the level files — a genuine <em>within-level</em>
 * case conflict aborts the conversion instead of silently picking a spelling, while a cross-level
 * divergence is preserved (A4, owner ruling).
 */
class MedDraConverterTest
{

    private final MedDraConverter converter = new MedDraConverter();

    @TempDir
    private Path raw;

    // ------------------------------------------------------------------
    // Happy path
    // ------------------------------------------------------------------

    @Test
    void convertsAllTenLevels() throws IOException
    {
        writeDistribution();

        ObjectNode doc = converter.convert(raw);

        assertEquals("Headache", doc.path("levels").path("PT").path("HEADACHE").asText());
        assertEquals("10019211", doc.path("levels").path("PTCD").path("10019211").asText());
        assertEquals("Cephalgia", doc.path("levels").path("LLT").path("CEPHALGIA").asText());
        assertEquals("Headaches NEC",
                doc.path("levels").path("HLT").path("HEADACHES NEC").asText());
        assertEquals("Nervous system disorders",
                doc.path("levels").path("SOC").path("NERVOUS SYSTEM DISORDERS").asText());
    }

    // ------------------------------------------------------------------
    // ⛔ The hazard: a full (non-primary) hierarchy closure
    // ------------------------------------------------------------------


    @Test
    void hierarchyIsBuiltFromPrimaryPathRowsOnly() throws IOException
    {
        writeDistribution();

        JsonNode hierarchy = converter.convert(raw).path("hierarchy");

        assertEquals(List.of("Nervous system disorders"), ancestors(hierarchy, "Headaches NEC"),
                "the primary row contributes its SOC — and only its SOC");
        assertEquals(List.of("Nervous system disorders"), ancestors(hierarchy, "Headaches"));
        assertFalse(hierarchy.has("Gastrointestinal and abdominal pains"),
                "an HLT reached only on a non-primary row is not an ancestor path");
        assertFalse(hierarchy.has("Gastrointestinal signs and symptoms"),
                "nor is its HLGT — admitting secondary SOCs would leave CG0460/CG0461 unable "
                        + "to fire");
    }


    @Test
    void hierarchyIsKeyedAtHltAndHlgtAndNotAtPt() throws IOException
    {
        writeDistribution();

        JsonNode hierarchy = converter.convert(raw).path("hierarchy");

        assertTrue(hierarchy.has("Headaches NEC"), hierarchy.toString());
        assertTrue(hierarchy.has("Headaches"));
        assertFalse(hierarchy.has("Headache"), "no rule probes the hierarchy at PT");
        assertFalse(hierarchy.has("Cephalgia"), "nor at LLT");
    }


    @Test
    void hierarchyNamesComeFromTheLevelFilesNotFromMdhier() throws IOException
    {
        writeDistribution();

        JsonNode hierarchy = converter.convert(raw).path("hierarchy");

        // mdhier.asc's denormalised HLT, HLGT and SOC name columns ALL deliberately carry the
        // wrong case; keys and ancestors alike must come from the level files' spellings. A
        // converter taking the ancestor verbatim from mdhier's SOC-name column (field 7) would
        // emit "NERVOUS SYSTEM DISORDERS" here and pass a keys-only assertion.
        assertTrue(hierarchy.has("Headaches NEC"), hierarchy.toString());
        assertFalse(hierarchy.has("HEADACHES NEC"));
        assertEquals(List.of("Nervous system disorders"), ancestors(hierarchy, "Headaches NEC"),
                "the ancestor is soc.asc's spelling, not mdhier field 7's");
        assertEquals(List.of("Nervous system disorders"), ancestors(hierarchy, "Headaches"),
                "and the HLGT key's ancestor likewise");
        assertTrue(hierarchy.has("Headaches"), "the HLGT key is hlgt.asc's spelling");
        assertFalse(hierarchy.has("HEADACHES"), "not mdhier field 6's");
    }

    // ------------------------------------------------------------------
    // ⛔ The hazard: silently picking one of two vendor spellings — WITHIN a level
    // ------------------------------------------------------------------


    /**
     * A4 / owner ruling — a case divergence <b>across</b> levels is the vendor's prerogative, not a
     * conflict: the engine consults only the level a rule's {@code dictionary_term_type} names, so
     * each level answers with its own spelling. Aborting here (the pre-A4 behaviour) would refuse a
     * legitimate distribution.
     */
    @Test
    void aCrossLevelCaseDivergenceIsPreservedNotAborted() throws IOException
    {
        writeDistribution();
        // An LLT spelled in a different case than the PT of the same term.
        Files.writeString(raw.resolve("llt.asc"), "10019210$HEADACHE$10019211$\n",
                StandardCharsets.UTF_8);

        ObjectNode doc = converter.convert(raw);

        assertEquals("HEADACHE", doc.path("levels").path("LLT").path("HEADACHE").asText(),
                "the LLT level keeps llt.asc's spelling");
        assertEquals("Headache", doc.path("levels").path("PT").path("HEADACHE").asText(),
                "and the PT level keeps pt.asc's — both levels answer correctly");
        assertEquals(List.of(), HouseFormatValidator.caseContractViolations("meddra", stamped(doc)),
                "the per-level case contract accepts the divergence");
    }


    /** Two spellings of one term within ONE file are a genuine conflict and still abort. */
    @Test
    void aWithinLevelCaseConflictAbortsTheConversion() throws IOException
    {
        writeDistribution();
        Files.writeString(raw.resolve("llt.asc"),
                "10019210$Cephalgia$10019211$\n10019299$CEPHALGIA$10019211$\n",
                StandardCharsets.UTF_8);

        IOException thrown = assertThrows(IOException.class, () -> converter.convert(raw));

        assertTrue(thrown.getMessage().contains("'Cephalgia'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("'CEPHALGIA'"), "both spellings are named");
        assertTrue(thrown.getMessage().contains("llt.asc"), "and the file");
    }

    // ------------------------------------------------------------------
    // A2: a BOM'd level file must not corrupt its first row's code
    // ------------------------------------------------------------------


    @Test
    void aLeadingBomInALevelFileDoesNotCorruptTheFirstCode() throws IOException
    {
        writeDistribution();
        Files.writeString(raw.resolve("llt.asc"), "\uFEFF" + "10019210$Cephalgia$10019211$\n",
                StandardCharsets.UTF_8);

        ObjectNode doc = converter.convert(raw);

        assertEquals("10019210", doc.path("levels").path("LLTCD").path("10019210").asText(),
                "with the BOM left in, the first code would be \\uFEFF10019210 and silently "
                        + "drop out of every join");
    }

    // ------------------------------------------------------------------
    // The document the installer will actually write
    // ------------------------------------------------------------------


    @Test
    void theConvertedDocumentPassesHouseFormatValidation() throws IOException
    {
        writeDistribution();

        ObjectNode doc = converter.convert(raw);
        doc.put("type", "meddra"); // the installer stamps this; the converter must not

        assertEquals(List.of(), HouseFormatValidator.validate("meddra", doc));
    }


    @Test
    void refusesADistributionMissingALevelFile() throws IOException
    {
        writeDistribution();
        Files.delete(raw.resolve("hlgt.asc"));

        assertThrows(NoSuchFileException.class, () -> converter.convert(raw));
    }

    // ------------------------------------------------------------------
    // Version
    // ------------------------------------------------------------------


    @Test
    void versionIsFieldZeroOfTheReleaseFile() throws IOException
    {
        writeDistribution();

        assertEquals("27.0", converter.versionOf(raw));
    }


    @Test
    void reportsNoVersionWhenTheReleaseFileIsAbsent() throws IOException
    {
        assertEquals("", converter.versionOf(raw));
    }


    /** The type stamp the installer would add before validating. */
    private static ObjectNode stamped(ObjectNode aDoc)
    {
        return aDoc.put("type", "meddra");
    }


    private static List<String> ancestors(JsonNode aHierarchy, String aTerm)
    {
        List<String> out = new java.util.ArrayList<>();
        aHierarchy.path(aTerm).forEach(ancestor -> out.add(ancestor.asText()));
        return out;
    }


    /**
     * A miniature {@code MedAscii} distribution. The PT {@code Headache} sits primarily under
     * {@code Headaches NEC} &rarr; {@code Headaches} &rarr; {@code Nervous system disorders} and
     * secondarily (a {@code primary_soc_fg} of {@code N}) under a gastrointestinal path;
     * {@code mdhier.asc}'s denormalised HLT, HLGT <b>and SOC</b> name columns all deliberately
     * carry the wrong case, so a converter reading any name from {@code mdhier} — key or ancestor —
     * is caught.
     */
    private void writeDistribution() throws IOException
    {
        write("llt.asc", "10019210$Cephalgia$10019211$");
        write("pt.asc", "10019211$Headache$$10029205$");
        write("hlt.asc", "10019231$Headaches NEC$",
                "10017977$Gastrointestinal and abdominal " + "pains$");
        write("hlgt.asc", "10019233$Headaches$", "10018012$Gastrointestinal signs and symptoms$");
        write("soc.asc", "10029205$Nervous system disorders$N$",
                "10017947$Gastrointestinal " + "disorders$G$");
        write("mdhier.asc",
                "10019211$10019231$10019233$10029205$Headache$HEADACHES NEC$HEADACHES"
                        + "$NERVOUS SYSTEM DISORDERS$Nerv$$10029205$Y$",
                "10019211$10017977$10018012$10017947$Headache$Gastrointestinal and abdominal "
                        + "pains$Gastrointestinal signs and symptoms$Gastrointestinal disorders"
                        + "$Gast$$10029205$N$");
        write("meddra_release.asc", "27.0$English$$");
    }


    private void write(String aFileName, String... aLines) throws IOException
    {
        Files.writeString(raw.resolve(aFileName), String.join("\n", aLines) + "\n",
                StandardCharsets.UTF_8);
    }

}
