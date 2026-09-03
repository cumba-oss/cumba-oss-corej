package net.cumba.corej.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Load-time guards on the dictionary bundle: gzip support, the {@code .gz} file-stem fix, the
 * provenance fields, and — the one that carries real weight — the <b>content guard</b> that keeps a
 * structurally empty dictionary from registering as available.
 *
 * <p>
 * <b>Why the content guard matters.</b> {@link ValueMapDictionary} degrades every mis-shaped
 * section to an empty map silently, so a truncated download or a converter bug parses cleanly. Were
 * such a file registered, {@link RuntimeDictionaryProvider#isAvailable} would answer {@code true}
 * and the engine's eager SKIP arm would never fire: the 84 membership rules would find every term
 * invalid and fire on <em>every row</em>, and the 12 pair/decode rules would report
 * {@code noViolation} vacuously. Neither failure announces itself.
 * </p>
 *
 * <p>
 * <b>Every guard assertion here is paired with a positive control</b> — the same shape carrying one
 * real entry, asserted to load. A test that only proved "empty things are dropped" would still pass
 * if the loader dropped everything, which is the failure mode a guard test most easily hides.
 * </p>
 */
class DictionaryLoadGuardTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ------------------------------------------------------------------
    // The content guard
    // ------------------------------------------------------------------

    /** The four shapes a silently-degrading reader turns into an empty section. */
    @Test
    void structurallyEmptyDictionariesCarryNoContent() throws IOException
    {
        assertFalse(parse("{\"type\":\"unii\"}").hasContent(), "no sections at all");
        assertFalse(parse("{\"type\":\"unii\",\"levels\":{}}").hasContent(), "empty levels");
        assertFalse(parse("{\"type\":\"unii\",\"levels\":{\"UNII\":{}}}").hasContent(),
                "a level with no terms is still no content");
        assertFalse(parse("{\"type\":\"unii\",\"levels\":null}").hasContent(), "null section");
        assertFalse(parse("{\"type\":\"unii\",\"levels\":[]}").hasContent(),
                "array-shaped section");
        assertFalse(parse("{\"type\":\"unii\",\"levelz\":{\"UNII\":{\"X\":\"X\"}}}").hasContent(),
                "a misspelled section key is silently ignored, so this is empty too");
        // A6 — hierarchy was the one section judged by ITS OWN emptiness rather than its
        // entries': a key mapping to an empty ancestor list answered no probe yet registered the
        // dictionary as available, so every membership rule fired on every row.
        assertFalse(parse("{\"type\":\"meddra\",\"hierarchy\":{\"X\":[]}}").hasContent(),
                "a hierarchy of empty ancestor lists is no more content than an empty level");
    }


    /** Positive control: one entry in any of the four sections is content. */
    @Test
    void oneEntryInAnySectionIsContent() throws IOException
    {
        assertTrue(parse("{\"type\":\"unii\",\"levels\":{\"UNII\":{\"X\":\"X\"}}}").hasContent(),
                "levels");
        assertTrue(parse("{\"type\":\"m\",\"hierarchy\":{\"A\":[\"B\"]}}").hasContent(),
                "hierarchy");
        assertTrue(parse("{\"type\":\"m\",\"pairs\":{\"m\":{\"C\":\"D\"}}}").hasContent(), "pairs");
        // neoplasm legitimately emits no levels at all — attributes alone must count as content.
        assertTrue(parse("{\"type\":\"neoplasm\",\"attributes\":{\"neoplasm\":{\"A\":\"BENIGN\"}}}")
                .hasContent(), "attributes alone");
    }


    /**
     * The guard in the loader: a contentless file is NOT registered, so its rules SKIP rather than
     * false-answering — while a content-bearing sibling in the same directory still loads.
     */
    @Test
    void aContentlessDictionaryIsNotAvailableButItsSiblingStillIs(@TempDir Path dir)
        throws IOException
    {
        Files.writeString(dir.resolve("unii.json"), "{\"type\":\"unii\",\"levels\":{\"UNII\":{}}}");
        Files.writeString(dir.resolve("medrt.json"),
                "{\"type\":\"medrt\",\"levels\":{\"MEDRT\":{\"A\":\"A\"}}}");

        RuntimeDictionaryProvider p = RuntimeDictionaryProvider.loadDirectory(dir);

        assertFalse(p.isAvailable("unii"),
                "an empty unii.json must not report as available — that would bypass the SKIP arm");
        assertTrue(p.isAvailable("medrt"), "the content-bearing sibling still loads");
        assertEquals(List.of("medrt"), List.copyOf(p.loadedTypes()));
    }


    /**
     * Batch B2 — an UNREADABLE file (a truncated {@code .json.gz} that is not valid gzip, a JSON
     * parse error) degrades exactly like a contentless one: per file, with the right diagnosis, and
     * never by discarding the healthy siblings. Before this guard the {@link IOException}
     * propagated out of {@code loadDirectory}, the caller nulled the whole provider, and every type
     * reported "is not installed" while the store sat populated.
     */
    @Test
    void anUnreadableFileDegradesOnlyItselfAndItsSiblingsStayLoaded(@TempDir Path dir)
        throws IOException
    {
        Files.write(dir.resolve("unii.json.gz"), new byte[]
        {
                0x1f, 0x00, 0x00, 0x00
        }); // wrong gzip magic — a truncated/corrupt download
        Files.writeString(dir.resolve("garbage.json"), "this is not json {{{");
        Files.writeString(dir.resolve("medrt.json"),
                "{\"type\":\"medrt\",\"levels\":{\"MEDRT\":{\"A\":\"A\"}}}");

        RuntimeDictionaryProvider p = RuntimeDictionaryProvider.loadDirectory(dir);

        assertTrue(p.isAvailable("medrt"),
                "one corrupt file must not discard the sibling that loads fine");
        assertFalse(p.isAvailable("unii"));
        RuntimeDictionaryProvider.Unavailability u = p.unavailabilityOf("unii");
        assertNotNull(u,
                "the corrupt file must carry a diagnosis, not the catch-all " + "'not installed'");
        assertEquals(RuntimeDictionaryProvider.UnavailabilityReason.NO_USABLE_CONTENT, u.reason());
        assertTrue(u.detail().contains("could not be read"), u.detail());
        assertTrue(u.detail().contains("reinstall"), u.detail());
        assertNotNull(p.unavailabilityOf("garbage"),
                "the unparseable sibling is diagnosed independently");
    }


    /**
     * The consequence the guard exists to prevent, stated as an assertion: with the empty
     * dictionary dropped, a membership probe answers {@code false} because the type is
     * <em>unavailable</em>, not because the term was rejected by an empty index.
     */
    @Test
    void aDroppedDictionaryAnswersNothingRatherThanRejectingEverything(@TempDir Path dir)
        throws IOException
    {
        Files.writeString(dir.resolve("meddra.json"), "{\"type\":\"meddra\",\"levels\":{}}");
        RuntimeDictionaryProvider p = RuntimeDictionaryProvider.loadDirectory(dir);

        assertFalse(p.isAvailable("meddra"));
        assertFalse(p.isValidTerm("meddra", "PT", "Headache"));
        assertNull(p.versionOf("meddra"));
    }


    /**
     * D13 item 2 — the drop records its diagnosis, so the per-rule SKIP can say "installed but
     * unusable — reinstall" instead of the misleading "not installed" to an operator who DID
     * install the file.
     */
    @Test
    void aDroppedDictionaryRecordsWhyItWasDropped(@TempDir Path dir) throws IOException
    {
        Files.writeString(dir.resolve("meddra.json"), "{\"type\":\"meddra\",\"levels\":{}}");
        RuntimeDictionaryProvider p = RuntimeDictionaryProvider.loadDirectory(dir);

        RuntimeDictionaryProvider.Unavailability u = p.unavailabilityOf("meddra");
        assertNotNull(u, "the content-guard drop must leave a diagnosis behind");
        assertEquals(RuntimeDictionaryProvider.UnavailabilityReason.NO_USABLE_CONTENT, u.reason());
        assertTrue(u.detail().contains("reinstall"), u.detail());
        assertEquals(u.detail(), p.unavailabilityDetail("meddra"));
        assertNull(p.unavailabilityOf("unii"),
                "a type that simply is not there records nothing — NOT_INSTALLED is the default");
    }

    // ------------------------------------------------------------------
    // gzip
    // ------------------------------------------------------------------


    @Test
    void aGzippedDictionaryLoadsAndKeysUnderItsOwnType(@TempDir Path dir) throws IOException
    {
        writeGzip(dir.resolve("unii.json.gz"),
                "{\"type\":\"unii\",\"levels\":{\"SRS\":{\"ASPIRIN\":\"ASPIRIN\"}}}");

        RuntimeDictionaryProvider p = RuntimeDictionaryProvider.loadDirectory(dir);

        assertTrue(p.isAvailable("unii"));
        assertTrue(p.isValidTerm("unii", "SRS", "aspirin"), "membership folds case");
    }


    /**
     * The {@code fileStem} fix. Without stripping {@code .gz} first, a gzipped file that declares
     * no {@code type} would key the provider under {@code "unii.json"} — a type no rule names — and
     * all 24 UNII rules would SKIP with nothing to explain why.
     */
    @Test
    void aGzippedFileWithNoTypeFieldFallsBackToTheStemWithoutTheGzSuffix(@TempDir Path dir)
        throws IOException
    {
        writeGzip(dir.resolve("unii.json.gz"), "{\"levels\":{\"UNII\":{\"X\":\"X\"}}}");

        RuntimeDictionaryProvider p = RuntimeDictionaryProvider.loadDirectory(dir);

        assertTrue(p.isAvailable("unii"), "stem must be 'unii', not 'unii.json'");
        assertFalse(p.isAvailable("unii.json"));
    }


    @Test
    void plainJsonStillLoadsAlongsideGzip(@TempDir Path dir) throws IOException
    {
        Files.writeString(dir.resolve("medrt.json"),
                "{\"type\":\"medrt\",\"levels\":{\"MEDRT\":{\"A\":\"A\"}}}");
        writeGzip(dir.resolve("unii.json.gz"),
                "{\"type\":\"unii\",\"levels\":{\"UNII\":{\"X\":\"X\"}}}");

        RuntimeDictionaryProvider p = RuntimeDictionaryProvider.loadDirectory(dir);

        assertEquals(2, p.loadedTypes().size(), "both encodings load from one directory");
    }

    // ------------------------------------------------------------------
    // Provenance
    // ------------------------------------------------------------------


    @Test
    void provenanceIsReadWhenDeclared() throws IOException
    {
        ValueMapDictionary d = parse("{\"type\":\"medrt\",\"version\":\"2026.07.06\","
                + "\"source\":\"https://evs.nci.nih.gov/ftp1/MED-RT/MEDRT.txt\","
                + "\"retrieved\":\"2026-08-30\",\"levels\":{\"MEDRT\":{\"A\":\"A\"}}}");

        assertEquals("2026.07.06", d.getVersion());
        assertEquals("2026-08-30", d.getProvenance().retrieved());
        assertEquals("https://evs.nci.nih.gov/ftp1/MED-RT/MEDRT.txt", d.getProvenance().source());
    }


    /** A file predating the installer declares none — and must still load. */
    @Test
    void provenanceIsNoneWhenAbsentOrBlank() throws IOException
    {
        ValueMapDictionary absent = parse("{\"type\":\"medrt\",\"levels\":{\"M\":{\"A\":\"A\"}}}");
        assertEquals(ValueMapDictionary.Provenance.NONE, absent.getProvenance());
        assertNull(absent.getVersion());

        ValueMapDictionary blank = parse(
                "{\"type\":\"medrt\",\"version\":\"  \",\"levels\":{\"M\":{\"A\":\"A\"}}}");
        assertNull(blank.getVersion(), "a blank version is absent, not the empty string");
    }


    @Test
    void versionOfReportsTheLoadedRelease(@TempDir Path dir) throws IOException
    {
        Files.writeString(dir.resolve("medrt.json"),
                "{\"type\":\"medrt\",\"version\":\"2026.07.06\","
                        + "\"levels\":{\"MEDRT\":{\"A\":\"A\"}}}");

        RuntimeDictionaryProvider p = RuntimeDictionaryProvider.loadDirectory(dir);

        assertEquals("2026.07.06", p.versionOf("medrt"));
        assertNull(p.versionOf("meddra"), "a type that is not loaded has no version");
    }

    // ------------------------------------------------------------------


    private static ValueMapDictionary parse(String json) throws IOException
    {
        return ValueMapDictionary.parse(MAPPER.readTree(json));
    }


    private static void writeGzip(Path file, String json) throws IOException
    {
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file)))
        {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
    }

}
