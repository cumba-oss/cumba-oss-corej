package net.cumba.corej.core.metadata.dictionary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The installer's two contracts: <b>validate before writing</b>, and <b>never silently re-point an
 * existing selection</b>.
 *
 * <p>
 * Both are tested through a stub converter, because the point is the installer's behaviour, not any
 * vendor's format. The vendor converters get their own tests where the format knowledge lives.
 * </p>
 */
class DictionaryInstallerTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ------------------------------------------------------------------
    // Validate before writing
    // ------------------------------------------------------------------

    @Test
    void aValidDictionaryIsWrittenWithItsProvenanceAndLicence(@TempDir Path dir) throws IOException
    {
        DictionaryInstaller installer = new DictionaryInstaller(dir);

        String version = installer.install(source(dir),
                converter("medrt", "2026.07.06", medrtJson("A", "N1")),
                "MED-RT terms of use: none published.", false);

        assertEquals("2026.07.06", version);
        Path written = dir.resolve("medrt").resolve("2026.07.06").resolve("medrt.json");
        assertTrue(Files.isRegularFile(written));
        ObjectNode doc = (ObjectNode) MAPPER.readTree(Files.readAllBytes(written));
        assertEquals("medrt", doc.get("type").asText());
        assertEquals("2026.07.06", doc.get("version").asText());
        assertTrue(doc.hasNonNull("source"), "provenance records where it came from");
        assertTrue(doc.hasNonNull("retrieved"), "and when");
        assertTrue(Files.isRegularFile(dir.resolve("medrt").resolve("2026.07.06")
                .resolve("LICENSES").resolve("MEDRT.txt")), "the licence travels with the data");
    }


    /**
     * ⛔ The contract that matters: a dictionary failing validation is <b>not written at all</b>.
     * Writing first and checking after would hand the operator the exact artefact the design exists
     * to prevent — one that loads cleanly and answers wrongly.
     */
    @Test
    void anIncompleteDictionaryIsNeverWritten(@TempDir Path dir) throws IOException
    {
        DictionaryInstaller installer = new DictionaryInstaller(dir);

        // unii requires levels UNII + SRS and pairs.unii; this carries only one level.
        String version = installer.install(source(dir),
                converter("unii", "4Aug2026", "{\"levels\":{\"UNII\":{\"X\":\"X\"}}}"), "FDA",
                false);

        assertNull(version);
        assertFalse(Files.exists(dir.resolve("unii").resolve("4Aug2026").resolve("unii.json")),
                "nothing may be written when validation failed");
        assertFalse(Files.exists(dir.resolve(DictionaryStore.MANIFEST)),
                "and it must not be bound in the manifest either");
        assertEquals(1, installer.getReport().getSkipped().size());
        assertTrue(
                installer.getReport().getWarnings().stream()
                        .anyMatch(w -> w.contains("pairs[unii]")),
                installer.getReport().getWarnings().toString());
    }


    /** A case-contract breach is caught by the same gate, before any write. */
    @Test
    void aCaseContractBreachIsNeverWritten(@TempDir Path dir) throws IOException
    {
        DictionaryInstaller installer = new DictionaryInstaller(dir);

        String version = installer.install(source(dir), converter("medrt", "2026.07.06",
                // key is not the case-fold of its value
                "{\"levels\":{\"MEDRT\":{\"Aspirin\":\"Aspirin\"},\"MEDRTCD\":{\"N1\":\"N1\"}},"
                        + "\"pairs\":{\"medrt\":{\"N1\":\"Aspirin\"}}}"),
                null, false);

        assertNull(version);
        assertFalse(Files.exists(dir.resolve("medrt")), "no directory is even created");
    }


    @Test
    void aDistributionWithNoVersionCannotBeInstalled(@TempDir Path dir) throws IOException
    {
        DictionaryInstaller installer = new DictionaryInstaller(dir);

        assertNull(installer.install(source(dir), converter("medrt", "", "{}"), null, false));
        assertTrue(installer.getReport().getSkipped().get(0).contains("no version"));
    }

    // ------------------------------------------------------------------
    // A7: the version becomes a path segment and manifest key — police it
    // ------------------------------------------------------------------


    /**
     * A1 showed a real vendor file yielding a 44-character version with a BOM, spaces and parens.
     * Installing it creates a directory name and manifest key that cannot be typed back, so every
     * later {@code --medrt-version} lookup answers "not installed". The installer must refuse,
     * naming the offending value.
     */
    @Test
    void aVersionThatIsNotAConservativeTokenIsRefused(@TempDir Path dir) throws IOException
    {
        DictionaryInstaller installer = new DictionaryInstaller(dir);

        String version = installer.install(source(dir), converter("medrt",
                "\uFEFF" + "July 2026 MED-RT (version name 2026.07.06)", medrtJson("A", "N1")),
                null, false);

        assertNull(version);
        assertFalse(Files.exists(dir.resolve("medrt")), "nothing is written");
        assertTrue(
                installer.getReport().getSkipped().get(0)
                        .contains("July 2026 MED-RT (version name 2026.07.06)"),
                "the skip names the offending value: " + installer.getReport().getSkipped());
    }


    /** And a traversing version can never become a path. */
    @Test
    void aTraversingVersionIsRefused(@TempDir Path dir) throws IOException
    {
        // The store sits one level down, so the surrounding directory is owned by this test and
        // an escaping write (store/medrt/../..) would be visible in it.
        Path store = dir.resolve("store");
        DictionaryInstaller installer = new DictionaryInstaller(store);

        assertNull(installer.install(source(dir), converter("medrt", "..", medrtJson("A", "N1")),
                null, false));
        assertFalse(Files.exists(store.resolve("medrt")), "nothing is written in the store");
        assertFalse(Files.exists(dir.resolve("medrt")), "and nothing escapes it");
        assertEquals(1, installer.getReport().getSkipped().size());
    }


    /** The six real vendor version vocabularies all pass the token check. */
    @Test
    void everyRealVendorVersionShapeIsAccepted()
    {
        for (String version : new String[]
        {
                "27.0", "SEP_2020", "2026.07.06", "4Aug2026", "2.80", "20240901"
        })
        {
            assertTrue(DictionaryInstaller.isValidVersionToken(version), version);
        }
        assertFalse(DictionaryInstaller.isValidVersionToken("a/b"));
        assertFalse(DictionaryInstaller.isValidVersionToken("."));
    }


    /**
     * A5b — the cardinality floors are ADVISORY: a suspiciously small (likely truncated) dictionary
     * still installs, but the operator is warned with the actual entry count. A heuristic bound
     * must never hard-refuse a lawful release it was not calibrated on, and there is no override
     * flag it could hide behind.
     */
    @Test
    void aSuspiciouslySmallDictionaryInstallsWithALoudWarning(@TempDir Path dir) throws IOException
    {
        DictionaryInstaller installer = new DictionaryInstaller(dir);

        String version = installer
                .install(source(dir),
                        converter("medrt", "2026.07.06",
                                "{\"levels\":{\"MEDRT\":{\"A\":\"A\"},\"MEDRTCD\":{\"N1\":\"N1\"}},"
                                        + "\"pairs\":{\"medrt\":{\"N1\":\"A\"}}}"),
                        "licence", false);

        assertEquals("2026.07.06", version, "the install is not blocked");
        assertTrue(
                Files.isRegularFile(
                        dir.resolve("medrt").resolve("2026.07.06").resolve("medrt.json")),
                "the document is written");
        assertEquals(1, installer.getReport().getWarnings().size(),
                installer.getReport().getWarnings().toString());
        String warning = installer.getReport().getWarnings().get(0);
        assertTrue(warning.contains("levels.MEDRTCD: 1"), warning);
        assertTrue(warning.contains("at least 1000"), warning);
        assertTrue(warning.contains("installed anyway"), warning);
    }

    // ------------------------------------------------------------------
    // A8: dropped term spellings are surfaced, exactly like replaced bytes
    // ------------------------------------------------------------------


    /**
     * A converter that refuses case-colliding spellings (SNOMED, UNII, MED-RT) tallies each
     * refusal; the installer must surface the total — a concept whose only spelling was refused is
     * code-only, and nothing else would ever tell the operator.
     */
    @Test
    void droppedTermSpellingsAreSurfacedAsAWarning(@TempDir Path dir) throws IOException
    {
        DictionaryConverter dropping = new DictionaryConverter()
        {

            @Override
            public String type()
            {
                return "medrt";
            }


            @Override
            public ObjectNode convert(Path aRawDir) throws IOException
            {
                RawDictionaryFiles.countDroppedTerm();
                RawDictionaryFiles.countDroppedTerm();
                RawDictionaryFiles.countDroppedTerm();
                return (ObjectNode) MAPPER.readTree(medrtJson("A", "N1"));
            }


            @Override
            public String versionOf(Path aRawDir)
            {
                return "2026.07.06";
            }
        };

        DictionaryInstaller installer = new DictionaryInstaller(dir);
        assertEquals("2026.07.06", installer.install(source(dir), dropping, "licence", false));
        assertEquals(1, installer.getReport().getWarnings().size(),
                installer.getReport().getWarnings().toString());
        String warning = installer.getReport().getWarnings().get(0);
        assertTrue(warning.contains("3 term spelling(s)"), warning);
        assertTrue(warning.contains("medrt 2026.07.06"), warning);
    }

    // ------------------------------------------------------------------
    // Manifest discipline
    // ------------------------------------------------------------------


    @Test
    void theFirstInstallOfATypeBindsIt(@TempDir Path dir) throws IOException
    {
        install(dir, "medrt", "2026.07.06", false);

        assertEquals("2026.07.06", manifest(dir).get("medrt"));
    }


    /**
     * ⛔ Installing a second release must NOT re-point an existing selection. Otherwise adding a
     * newer MedDRA would silently change the findings for every study already validating against
     * the old one — the same hazard the store's no-fallback rule exists to prevent, arriving
     * through the installer instead.
     */
    @Test
    void asecondInstallLeavesTheExistingSelectionAlone(@TempDir Path dir) throws IOException
    {
        install(dir, "medrt", "2026.07.06", false);
        DictionaryInstaller second = install(dir, "medrt", "2026.08.03", false);

        assertEquals("2026.07.06", manifest(dir).get("medrt"), "the bound version is unchanged");
        assertTrue(
                Files.isRegularFile(
                        dir.resolve("medrt").resolve("2026.08.03").resolve("medrt.json")),
                "but the new release IS installed and selectable");
        assertTrue(
                second.getReport().getWarnings().stream()
                        .anyMatch(w -> w.contains("--set-default")),
                "and the operator is told how to bind it");
    }


    /**
     * Phase 7b / D11 — {@code --skip-installed} makes re-installs idempotent: an already-present
     * type/version is left byte-for-byte alone (a container entrypoint re-converting the same
     * mounted distribution on every boot must not rewrite the store, churn mtimes and thereby
     * invalidate the D8 cache for nothing).
     */
    @Test
    void skipInstalledLeavesAnExistingVersionUntouched(@TempDir Path dir) throws IOException
    {
        install(dir, "medrt", "2026.07.06", false);
        Path written = dir.resolve("medrt").resolve("2026.07.06").resolve("medrt.json");
        java.nio.file.attribute.FileTime before = Files.getLastModifiedTime(written);

        DictionaryInstaller again = new DictionaryInstaller(dir, false, true);
        String version = again.install(source(dir),
                converter("medrt", "2026.07.06", medrtJson("Changed", "N9")), "licence", false);

        assertNull(version, "a skipped type reports nothing installed");
        assertTrue(
                again.getReport().getSkipped().stream()
                        .anyMatch(m -> m.contains("already installed")),
                "the skip is reported, not silent: " + again.getReport().getSkipped());
        assertEquals(before, Files.getLastModifiedTime(written), "the file was not rewritten");
        assertTrue(Files.readString(written).contains("\"A\""),
                "the original content stands (the changed distribution was NOT converted)");
    }


    /** The other half: without the flag, the same re-install DOES refresh the version. */
    @Test
    void withoutSkipInstalledAReinstallRefreshes(@TempDir Path dir) throws IOException
    {
        install(dir, "medrt", "2026.07.06", false);
        Path written = dir.resolve("medrt").resolve("2026.07.06").resolve("medrt.json");

        DictionaryInstaller again = new DictionaryInstaller(dir, false, false);
        String version = again.install(source(dir),
                converter("medrt", "2026.07.06", medrtJson("Changed", "N9")), "licence", false);

        assertEquals("2026.07.06", version);
        assertTrue(Files.readString(written).contains("Changed"),
                "the refreshed content was written");
    }


    @Test
    void setDefaultRebindsDeliberately(@TempDir Path dir) throws IOException
    {
        install(dir, "medrt", "2026.07.06", false);
        install(dir, "medrt", "2026.08.03", true);

        assertEquals("2026.08.03", manifest(dir).get("medrt"));
    }


    /** End to end: what the installer writes is what the store binds. */
    @Test
    void whatIsInstalledIsWhatTheStoreLoads(@TempDir Path dir) throws IOException
    {
        install(dir, "medrt", "2026.07.06", false);

        assertEquals("2026.07.06", DictionaryStore.load(dir, Map.of()).versionOf("medrt"));
    }

    // ------------------------------------------------------------------
    // Dry run (Phase 6b): the full pipeline minus every write
    // ------------------------------------------------------------------


    /**
     * A dry run converts and validates exactly like a real install — so its report is trustworthy —
     * but writes <b>nothing</b>: no document, no licence, no manifest entry.
     */
    @Test
    void aDryRunReportsTheInstallButWritesNothing(@TempDir Path dir) throws IOException
    {
        DictionaryInstaller installer = new DictionaryInstaller(dir, true);

        String version = installer.install(source(dir),
                converter("medrt", "2026.07.06", medrtJson("A", "N1")), "licence", false);

        assertEquals("2026.07.06", version, "the report says what a real run would do");
        assertEquals(Map.of("medrt", "2026.07.06"), installer.getReport().getInstalled());
        assertFalse(Files.exists(dir.resolve("medrt")), "no document directory");
        assertFalse(Files.exists(dir.resolve(DictionaryStore.MANIFEST)), "no manifest");
    }


    /** A dry run still validates, so a broken conversion is reported as skipped, not installed. */
    @Test
    void aDryRunStillValidates(@TempDir Path dir) throws IOException
    {
        DictionaryInstaller installer = new DictionaryInstaller(dir, true);

        assertNull(installer.install(source(dir),
                converter("unii", "4Aug2026", "{\"levels\":{\"UNII\":{\"X\":\"X\"}}}"), null,
                false));
        assertEquals(1, installer.getReport().getSkipped().size());
    }

    // ------------------------------------------------------------------
    // SOURCES.md and extra licence documents (Phase 7a)
    // ------------------------------------------------------------------


    /** Extra licence documents — LOINC's verbatim notice — travel into {@code LICENSES/} too. */
    @Test
    void extraLicenceFilesTravelWithTheData(@TempDir Path dir) throws IOException
    {
        DictionaryInstaller installer = new DictionaryInstaller(dir);

        installer.install(source(dir), converter("medrt", "2026.07.06", medrtJson("A", "N1")),
                "main notice", Map.of("EXTRA_notice.txt", "verbatim extra text"), false);

        Path licences = dir.resolve("medrt").resolve("2026.07.06").resolve("LICENSES");
        assertEquals("verbatim extra text", Files.readString(licences.resolve("EXTRA_notice.txt")));
        assertEquals("main notice", Files.readString(licences.resolve("MEDRT.txt")),
                "the main notice is still written beside it");
    }


    /**
     * {@code SOURCES.md} records the raw artefact's SHA-256 per type, and an install of one type
     * never rewrites another type's section.
     */
    @Test
    void sourcesFileRecordsTheShaAndDoesNotClobberASibling(@TempDir Path dir) throws IOException
    {
        DictionaryInstaller installer = new DictionaryInstaller(dir);
        installer.install(
                sourceWithArtefacts(dir, "https://example.test/medrt",
                        new DictionarySource.Artefact("MEDRT.txt", "https://example.test/m.txt",
                                "aa11")),
                converter("medrt", "2026.07.06", medrtJson("A", "N1")), null, false);
        installer.install(
                sourceWithArtefacts(dir, "https://example.test/whodrug",
                        new DictionarySource.Artefact("WHODrug_B3.zip",
                                "https://example.test/w.zip", "bb22")),
                converter("whodrug", "SEP_2020",
                        "{\"levels\":{\"PT\":{\"ASPIRIN\":\"ASPIRIN\"},"
                                + "\"ATC\":{\"ANALGESICS\":\"Analgesics\"},"
                                + "\"ATCCD\":{\"N02\":\"N02\"}},"
                                + "\"pairs\":{\"whodrug\":{\"ASPIRIN\":\"ASPIRIN\"}}}"),
                null, false);

        String sources = Files.readString(dir.resolve(DictionaryInstaller.SOURCES_FILE));
        assertTrue(sources.contains("## medrt"), sources);
        assertTrue(sources.contains("SHA-256 aa11"), sources);
        assertTrue(sources.contains("## whodrug"), sources);
        assertTrue(sources.contains("SHA-256 bb22"), sources);
        assertTrue(sources.contains("- Version: 2026.07.06"), sources);
        assertTrue(sources.contains("- Entries: "), "A5b: the written entry counts are recorded");
        assertTrue(sources.contains("levels.MEDRTCD: 1101"),
                "per-section counts, so a truncation is visible at a glance: " + sources);

        // Reinstalling one type rewrites only its own section.
        installer.install(
                sourceWithArtefacts(dir, "https://example.test/medrt",
                        new DictionarySource.Artefact("MEDRT.txt", "https://example.test/m.txt",
                                "cc33")),
                converter("medrt", "2026.08.03", medrtJson("A", "N1")), null, false);
        String updated = Files.readString(dir.resolve(DictionaryInstaller.SOURCES_FILE));
        assertTrue(updated.contains("SHA-256 cc33"), updated);
        assertFalse(updated.contains("SHA-256 aa11"),
                "the type's old section is replaced, not accumulated: " + updated);
        assertTrue(updated.contains("SHA-256 bb22"),
                "the sibling type's section survives untouched: " + updated);
        assertEquals(1, updated.split("## medrt", -1).length - 1,
                "exactly one section per type: " + updated);
    }


    /** A dry run writes no {@code SOURCES.md} either. */
    @Test
    void aDryRunWritesNoSourcesFile(@TempDir Path dir) throws IOException
    {
        DictionaryInstaller installer = new DictionaryInstaller(dir, true);
        installer.install(source(dir), converter("medrt", "2026.07.06", medrtJson("A", "N1")),
                "licence", false);

        assertFalse(Files.exists(dir.resolve(DictionaryInstaller.SOURCES_FILE)));
    }


    /**
     * Phase 8 — a conversion that replaced undecodable bytes must <b>say so</b>. The decoder
     * substitutes U+FFFD rather than aborting (one bad byte costs one term, not the whole
     * dictionary), and the installer surfaces the tally as a warning, because a substituted
     * character yields a term that passes the case contract and matches no real data.
     */
    @Test
    void replacedBytesDuringConversionAreSurfacedAsAWarning(@TempDir Path dir) throws IOException
    {
        Path rawFile = dir.resolve("raw.txt");
        // 'A' 0xFF 'B' LF 0xC3 LF — two undecodable sequences.
        Files.write(rawFile, new byte[]
        {
                0x41, (byte) 0xFF, 0x42, 0x0A, (byte) 0xC3, 0x0A
        });
        String json = medrtJson("A", "N1");
        DictionaryConverter converter = new DictionaryConverter()
        {

            @Override
            public String type()
            {
                return "medrt";
            }


            @Override
            public ObjectNode convert(Path aRawDir) throws IOException
            {
                try (var in = RawDictionaryFiles.reader(rawFile))
                {
                    assertEquals(2, in.lines().count(),
                            "exhaust the file the way a vendor converter would");
                }
                return (ObjectNode) MAPPER.readTree(json);
            }


            @Override
            public String versionOf(Path aRawDir)
            {
                return "2026.07.06";
            }
        };

        DictionaryInstaller installer = new DictionaryInstaller(dir);
        assertEquals("2026.07.06", installer.install(source(dir), converter, "licence", false));
        assertEquals(1, installer.getReport().getWarnings().size(),
                "the replacements are surfaced, exactly once");
        String warning = installer.getReport().getWarnings().get(0);
        assertTrue(warning.contains("medrt 2026.07.06"), warning);
        assertTrue(warning.contains("2 undecodable byte sequence(s)"), warning);
        assertTrue(warning.contains("U+FFFD"), warning);
    }


    private static DictionarySource sourceWithArtefacts(Path aDir, String aProvenance,
            DictionarySource.Artefact... aArtefacts)
    {
        return new DictionarySource()
        {

            @Override
            public Path resolve()
            {
                return aDir;
            }


            @Override
            public String provenance()
            {
                return aProvenance;
            }


            @Override
            public java.util.List<DictionarySource.Artefact> artefacts()
            {
                return java.util.List.of(aArtefacts);
            }


            @Override
            public void close()
            {
                // Nothing created, nothing to release.
            }
        };
    }

    // ------------------------------------------------------------------


    private static DictionaryInstaller install(Path aDir, String aType, String aVersion,
            boolean aSetDefault)
        throws IOException
    {
        DictionaryInstaller installer = new DictionaryInstaller(aDir);
        installer.install(source(aDir), converter(aType, aVersion, medrtJson("A", "N1")), "licence",
                aSetDefault);
        return installer;
    }


    private static Map<String, String> manifest(Path aDir) throws IOException
    {
        return MAPPER.readValue(Files.readAllBytes(aDir.resolve(DictionaryStore.MANIFEST)),
                MAPPER.getTypeFactory().constructMapType(java.util.LinkedHashMap.class,
                        String.class, String.class));
    }


    private static DictionarySource source(Path aDir)
    {
        return new LocalDictionarySource(aDir);
    }


    /**
     * A valid, floor-clearing medrt document (A5b: medrt requires ≥ 1 000 MEDRTCD entries) whose
     * distinguishing entry is {@code upper(aName) -> aName} decoded from {@code aNui} — so the
     * tests keep asserting on {@code "A"} / {@code "Changed"} exactly as before.
     */
    private static String medrtJson(String aName, String aNui)
    {
        StringBuilder names = new StringBuilder();
        StringBuilder codes = new StringBuilder();
        StringBuilder pairs = new StringBuilder();
        names.append('"').append(aName.toUpperCase(java.util.Locale.ROOT)).append("\":\"")
                .append(aName).append('"');
        codes.append('"').append(aNui).append("\":\"").append(aNui).append('"');
        pairs.append('"').append(aNui).append("\":\"").append(aName).append('"');
        for (int i = 0; i < 1_100; i++)
        {
            String name = "Filler " + i + " [EPC]";
            String nui = "N" + String.format(java.util.Locale.ROOT, "%09d", i);
            names.append(",\"").append(name.toUpperCase(java.util.Locale.ROOT)).append("\":\"")
                    .append(name).append('"');
            codes.append(",\"").append(nui).append("\":\"").append(nui).append('"');
            pairs.append(",\"").append(nui).append("\":\"").append(name).append('"');
        }
        return "{\"levels\":{\"MEDRT\":{" + names + "},\"MEDRTCD\":{" + codes + "}},"
                + "\"pairs\":{\"medrt\":{" + pairs + "}}}";
    }


    /**
     * A converter that returns a fixed document — the installer's behaviour is what is under test.
     */
    private static DictionaryConverter converter(String aType, String aVersion, String aJson)
    {
        return new DictionaryConverter()
        {

            @Override
            public String type()
            {
                return aType;
            }


            @Override
            public ObjectNode convert(Path aRawDir) throws IOException
            {
                return (ObjectNode) MAPPER.readTree(aJson);
            }


            @Override
            public String versionOf(Path aRawDir)
            {
                return aVersion;
            }
        };
    }

}
