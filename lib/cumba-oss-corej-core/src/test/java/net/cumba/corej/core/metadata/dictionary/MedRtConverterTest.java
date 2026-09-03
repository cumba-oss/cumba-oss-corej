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
 * The MED-RT converter, and in particular the one decision that cannot be read off the file: which
 * concepts get a bare-name alias beside their tagged name.
 */
class MedRtConverterTest
{

    private final MedRtConverter converter = new MedRtConverter();

    @TempDir
    private Path raw;

    // ------------------------------------------------------------------
    // Happy path
    // ------------------------------------------------------------------

    @Test
    void convertsNamesCodesAndPairs() throws IOException
    {
        writeDistribution();

        ObjectNode doc = converter.convert(raw);

        JsonNode names = doc.path("levels").path("MEDRT");
        assertEquals("Cyclooxygenase Inhibitors [MoA]",
                names.path("CYCLOOXYGENASE INHIBITORS [MOA]").asText(),
                "the tagged name is the canonical form, verbatim");
        assertEquals("Chemical/Ingredient", names.path("CHEMICAL/INGREDIENT").asText(),
                "an untagged root is published as it stands");
        assertEquals("N0000000160",
                doc.path("levels").path("MEDRTCD").path("N0000000160").asText());
        assertEquals("Cyclooxygenase Inhibitors [MoA]",
                doc.path("pairs").path("medrt").path("N0000000160").asText());
    }


    /** ⛔ The tag is part of the name; nothing may re-case it. */
    @Test
    void keepsTheVendorsMixedCaseTagVerbatim() throws IOException
    {
        writeDistribution();

        JsonNode names = converter.convert(raw).path("levels").path("MEDRT");

        assertTrue(names.has("CYCLOOXYGENASE INHIBITORS [MOA]"), "the key is the case-fold");
        assertFalse(names.has("Cyclooxygenase Inhibitors [MoA]"), "and only the key is folded");
        assertTrue(
                names.properties().stream().anyMatch(
                        e -> "Cyclooxygenase Inhibitors [MoA]".equals(e.getValue().asText())),
                "the value keeps the vendor's [MoA], not [MOA] or [moa]");
    }

    // ------------------------------------------------------------------
    // The hazard: a base name that names more than one concept
    // ------------------------------------------------------------------


    @Test
    void aliasesAUniqueBaseNameButNeverACollidingOne() throws IOException
    {
        writeDistribution();

        ObjectNode doc = converter.convert(raw);
        JsonNode names = doc.path("levels").path("MEDRT");
        JsonNode basePairs = doc.path("pairs").path("medrt-base");

        assertEquals("Cyclooxygenase Inhibitors", names.path("CYCLOOXYGENASE INHIBITORS").asText(),
                "a base name reaching exactly one NUI is aliased");
        assertEquals("Cyclooxygenase Inhibitors", basePairs.path("N0000000160").asText(),
                "and its decode goes to the second registry, so the tagged pair survives");

        assertFalse(names.has("SEROTONIN UPTAKE INHIBITORS"),
                "'Serotonin Uptake Inhibitors' reaches two NUIs — aliasing it would assert a "
                        + "pairing that cannot be justified");
        assertFalse(basePairs.has("N0000000001"), basePairs.toString());
        assertFalse(basePairs.has("N0000000002"), basePairs.toString());
        assertTrue(names.has("SEROTONIN UPTAKE INHIBITORS [MOA]"),
                "both tagged forms still answer");
        assertTrue(names.has("SEROTONIN UPTAKE INHIBITORS [EPC]"));
    }


    /** A stripped name colliding with an untagged root is a collision too. */
    @Test
    void doesNotAliasOverAnUntaggedRoot() throws IOException
    {
        writeDistribution();

        ObjectNode doc = converter.convert(raw);

        assertEquals("Chemical/Ingredient",
                doc.path("levels").path("MEDRT").path("CHEMICAL/INGREDIENT").asText());
        assertFalse(doc.path("pairs").path("medrt-base").has("N0000000004"),
                "the [EXT] concept's base name is the root's name, reaching two NUIs");
    }

    // ------------------------------------------------------------------
    // A2: a BOM'd MEDRT.txt must not corrupt its first row
    // ------------------------------------------------------------------


    /** The real 2026 {@code MEDRT.txt} starts with a BOM; the first concept must still publish. */
    @Test
    void aLeadingBomInTheSourceFileDoesNotCorruptTheFirstRow() throws IOException
    {
        Files.writeString(raw.resolve("MEDRT.txt"),
                "\uFEFF" + "Cyclooxygenase Inhibitors [MoA]\tN0000000160\tMED-RT\n",
                StandardCharsets.UTF_8);

        ObjectNode doc = converter.convert(raw);

        assertTrue(doc.path("levels").path("MEDRT").has("CYCLOOXYGENASE INHIBITORS [MOA]"),
                "with the BOM left in, the first name would carry an invisible prefix and match "
                        + "no submitted TSVAL: " + doc.path("levels").path("MEDRT"));
    }

    // ------------------------------------------------------------------
    // The document the installer will actually write
    // ------------------------------------------------------------------


    @Test
    void theConvertedDocumentPassesHouseFormatValidation() throws IOException
    {
        writeDistribution();

        ObjectNode doc = converter.convert(raw);
        doc.put("type", "medrt"); // the installer stamps this; the converter must not

        assertEquals(List.of(), HouseFormatValidator.validate("medrt", doc));
    }

    // ------------------------------------------------------------------
    // Version
    // ------------------------------------------------------------------


    /**
     * A1 — the REAL 2026 release notes: a UTF-8 BOM, a prose sentence, CR CR line ends. The BOM
     * survives {@code trim()}/{@code isBlank()}, so "first non-blank line" installed the whole
     * sentence — an invisible-prefixed directory name and manifest key that
     * {@code --medrt-version 2026.07.06} could never select, SKIPping all 8 MED-RT rules. The token
     * must be extracted, never the line taken whole.
     */
    @Test
    void extractsTheVersionTokenFromTheRealProseNotesLine() throws IOException
    {
        writeDistribution();
        Files.writeString(raw.resolve("MEDRT_Release_Notes.txt"),
                "\uFEFF" + "July 2026 MED-RT (version name 2026.07.06)\r\r\n"
                        + "More release prose follows here.\r\r\n",
                StandardCharsets.UTF_8);

        assertEquals("2026.07.06", converter.versionOf(raw),
                "the declared token, not the BOM'd prose line");
    }


    /** Notes that carry only the bare date token still yield it. */
    @Test
    void aBareDateLineInTheNotesStillYieldsTheToken() throws IOException
    {
        writeDistribution();
        Files.writeString(raw.resolve("MEDRT_Release_Notes_2026_07.txt"),
                "\n2026.07.06\nMED-RT release notes\n", StandardCharsets.UTF_8);

        assertEquals("2026.07.06", converter.versionOf(raw));
    }


    /** Notes with no recognisable token yield no version — never a guess, never prose. */
    @Test
    void notesWithoutARecognisableTokenYieldNoVersion() throws IOException
    {
        writeDistribution();
        Files.writeString(raw.resolve("MEDRT_Release_Notes.txt"),
                "July 2026 MED-RT release\nProse only, no token.\n", StandardCharsets.UTF_8);

        assertEquals("", converter.versionOf(raw));
    }


    @Test
    void readsTheVersionFromTheDtsVersionElement() throws IOException
    {
        writeDistribution();
        Files.writeString(raw.resolve("Core_MEDRT_2026.07.06_DTS.xml"),
                "<terminology>\n  <namespace>\n    <version>2026.07.06</version>\n"
                        + "  </namespace>\n</terminology>\n",
                StandardCharsets.UTF_8);

        assertEquals("2026.07.06", converter.versionOf(raw));
    }


    /** The DTS is machine-written and outranks the prose notes when both are present. */
    @Test
    void theDtsVersionElementOutranksTheNotes() throws IOException
    {
        writeDistribution();
        Files.writeString(raw.resolve("Core_MEDRT_2026.08.01_DTS.xml"),
                "<terminology><namespace><version>2026.08.01</version></namespace></terminology>\n",
                StandardCharsets.UTF_8);
        Files.writeString(raw.resolve("MEDRT_Release_Notes.txt"),
                "July 2026 MED-RT (version name 2026.07.06)\n", StandardCharsets.UTF_8);

        assertEquals("2026.08.01", converter.versionOf(raw));
    }


    @Test
    void reportsNoVersionWhenTheDistributionDeclaresNone() throws IOException
    {
        writeDistribution();

        assertEquals("", converter.versionOf(raw),
                "an empty version is what stops the installer writing an unversioned store entry");
    }


    private void writeDistribution() throws IOException
    {
        Files.writeString(raw.resolve("MEDRT.txt"),
                String.join("\n", "Cyclooxygenase Inhibitors [MoA]\tN0000000160\tMED-RT",
                        "Serotonin Uptake Inhibitors [MoA]\tN0000000001\tMED-RT",
                        "Serotonin Uptake Inhibitors [EPC]\tN0000000002\tMED-RT",
                        "Chemical/Ingredient\tN0000000003\tMED-RT",
                        "Chemical/Ingredient [EXT]\tN0000000004\tMED-RT", "\t\t", "") + "\n",
                StandardCharsets.UTF_8);
    }

}
