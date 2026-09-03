package net.cumba.corej.core.metadata.dictionary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import net.cumba.corej.core.metadata.RuntimeDictionaryProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The versioned dictionary store and the directory resolver.
 *
 * <p>
 * The assertions that carry weight are the <b>negative</b> ones: that a dictionary with no selected
 * version is not loaded <em>however many versions are installed</em>, and that a
 * selected-but-absent version is not silently substituted. Both protect the same property — that a
 * validation only ever uses a release a human named, so the same study cannot validate differently
 * on two machines because of what happens to sit on disk.
 * </p>
 */
class DictionaryStoreTest
{

    private static final String MEDDRA_27 = "{\"type\":\"meddra\",\"version\":\"27.0\",\"levels\":{\"PT\":{\"HEADACHE\":\"Headache\"}}}";

    private static final String MEDDRA_26 = "{\"type\":\"meddra\",\"version\":\"26.1\",\"levels\":{\"PT\":{\"MIGRAINE\":\"Migraine\"}}}";

    // ------------------------------------------------------------------
    // Selection
    // ------------------------------------------------------------------

    @Test
    void aRequestedVersionIsBoundAndItsSiblingIsNot(@TempDir Path dir) throws IOException
    {
        installed(dir, "meddra", "27.0", MEDDRA_27);
        installed(dir, "meddra", "26.1", MEDDRA_26);

        RuntimeDictionaryProvider p = DictionaryStore.load(dir, Map.of("meddra", "27.0"));

        assertTrue(p.isAvailable("meddra"));
        assertEquals("27.0", p.versionOf("meddra"));
        assertTrue(p.isValidTerm("meddra", "PT", "Headache"), "27.0's terms are the ones loaded");
        assertFalse(p.isValidTerm("meddra", "PT", "Migraine"), "26.1's terms are not");
    }


    /** ⛔ The core rule: no selection ⇒ not loaded, whatever is installed. */
    @Test
    void withNoSelectionNothingIsLoadedHoweverManyVersionsExist(@TempDir Path dir)
        throws IOException
    {
        installed(dir, "meddra", "27.0", MEDDRA_27);
        installed(dir, "meddra", "26.1", MEDDRA_26);

        assertFalse(DictionaryStore.load(dir, Map.of()).isAvailable("meddra"),
                "two installed, none selected");
    }


    /**
     * The same, with exactly one version installed. This is the case where a "use the only one"
     * fallback would look harmless — and it is precisely the case that would make findings depend
     * on incidental disk state.
     */
    @Test
    void aSingleInstalledVersionIsStillNotLoadedWithoutASelection(@TempDir Path dir)
        throws IOException
    {
        installed(dir, "meddra", "27.0", MEDDRA_27);

        assertFalse(DictionaryStore.load(dir, Map.of()).isAvailable("meddra"),
                "one installed and none selected behaves exactly like ten");
    }


    @Test
    void aSelectedButUninstalledVersionIsNeverSubstituted(@TempDir Path dir) throws IOException
    {
        installed(dir, "meddra", "27.0", MEDDRA_27);

        RuntimeDictionaryProvider p = DictionaryStore.load(dir, Map.of("meddra", "25.0"));

        assertFalse(p.isAvailable("meddra"),
                "25.0 was asked for and is not installed — 27.0 must not stand in for it");
    }


    /** Selection is per type: an unselected MedDRA does not disable a selected UNII. */
    @Test
    void selectionDegradesPerTypeNotGlobally(@TempDir Path dir) throws IOException
    {
        installed(dir, "meddra", "27.0", MEDDRA_27);
        installed(dir, "unii", "4Aug2026",
                "{\"type\":\"unii\",\"levels\":{\"UNII\":{\"X\":\"X\"}}}");

        RuntimeDictionaryProvider p = DictionaryStore.load(dir, Map.of("unii", "4Aug2026"));

        assertTrue(p.isAvailable("unii"));
        assertFalse(p.isAvailable("meddra"));
    }

    // ------------------------------------------------------------------
    // Manifest
    // ------------------------------------------------------------------


    @Test
    void theManifestSuppliesAVersionWhenTheCallerNamesNone(@TempDir Path dir) throws IOException
    {
        installed(dir, "meddra", "27.0", MEDDRA_27);
        Files.writeString(dir.resolve(DictionaryStore.MANIFEST), "{\"meddra\":\"27.0\"}");

        assertTrue(DictionaryStore.load(dir, Map.of()).isAvailable("meddra"));
    }


    /**
     * A caller-supplied version outranks the manifest — the CLI and define.xml both arrive here.
     */
    @Test
    void aRequestedVersionOutranksTheManifest(@TempDir Path dir) throws IOException
    {
        installed(dir, "meddra", "27.0", MEDDRA_27);
        installed(dir, "meddra", "26.1", MEDDRA_26);
        Files.writeString(dir.resolve(DictionaryStore.MANIFEST), "{\"meddra\":\"26.1\"}");

        assertEquals("27.0",
                DictionaryStore.load(dir, Map.of("meddra", "27.0")).versionOf("meddra"));
    }


    @Test
    void aMalformedManifestSkipsRatherThanFailing(@TempDir Path dir) throws IOException
    {
        installed(dir, "meddra", "27.0", MEDDRA_27);
        Files.writeString(dir.resolve(DictionaryStore.MANIFEST), "not json at all");

        assertFalse(DictionaryStore.load(dir, Map.of()).isAvailable("meddra"),
                "unreadable manifest can only ever cause a SKIP, never a wrong answer");
    }

    // ------------------------------------------------------------------
    // Layout compatibility
    // ------------------------------------------------------------------


    /** The shipped dummy fixture is a flat, unversioned set and must keep loading unchanged. */
    @Test
    void aFlatUnversionedDirectoryStillLoads(@TempDir Path dir) throws IOException
    {
        Files.writeString(dir.resolve("meddra.json"), MEDDRA_27);

        RuntimeDictionaryProvider p = DictionaryStore.load(dir, Map.of());

        assertTrue(p.isAvailable("meddra"), "a flat set has no versions to select between");
    }


    @Test
    void anAbsentDirectoryYieldsAnEmptyProvider() throws IOException
    {
        assertFalse(DictionaryStore.load(Path.of("/nonexistent-dictionary-store"), Map.of())
                .isAvailable("meddra"));
    }


    /** The content guard still applies inside a version directory. */
    @Test
    void anInstalledButEmptyVersionIsNotLoaded(@TempDir Path dir) throws IOException
    {
        installed(dir, "meddra", "27.0", "{\"type\":\"meddra\",\"levels\":{}}");

        assertFalse(DictionaryStore.load(dir, Map.of("meddra", "27.0")).isAvailable("meddra"));
    }


    /**
     * ⛔ A stray flat file at the root of a VERSIONED store must not silently disable the whole
     * tree: before this guard, one leftover hand-copied {@code meddra.json} switched the entire
     * store to the flat carve-out — every selection ignored, no diagnostic anywhere. The versioned
     * tree wins; the stray file is ignored (it must not even answer for its own type, or it would
     * answer with no version selected).
     */
    @Test
    void aStrayFlatFileDoesNotDisableTheVersionedStore(@TempDir Path dir) throws IOException
    {
        installed(dir, "meddra", "27.0", MEDDRA_27);
        Files.writeString(dir.resolve(DictionaryStore.MANIFEST), "{\"meddra\":\"27.0\"}");
        Files.writeString(dir.resolve("unii.json"),
                "{\"type\":\"unii\",\"levels\":{\"UNII\":{\"X\":\"X\"}}}");

        RuntimeDictionaryProvider p = DictionaryStore.load(dir, Map.of());

        assertTrue(p.isAvailable("meddra"),
                "the versioned tree must keep working with a stray flat file beside it");
        assertEquals("27.0", p.versionOf("meddra"));
        assertFalse(p.isAvailable("unii"),
                "the stray flat file must not answer — it has no version selected");
    }


    /**
     * Q12 through the flat carve-out: a version REQUEST against a flat store must not be silently
     * ignored — the one door through which a study could validate against a release nobody chose. A
     * mismatching (or version-less) flat file is dropped with the versioned path's diagnosis; a
     * matching one still answers.
     */
    @Test
    void aFlatStoreHonoursARequestedVersionInsteadOfIgnoringIt(@TempDir Path dir) throws IOException
    {
        Files.writeString(dir.resolve("meddra.json"), MEDDRA_27); // declares 27.0

        RuntimeDictionaryProvider mismatch = DictionaryStore.load(dir, Map.of("meddra", "25.0"));
        assertFalse(mismatch.isAvailable("meddra"),
                "25.0 was asked for; the flat 27.0 must not stand in for it");
        RuntimeDictionaryProvider.Unavailability u = mismatch.unavailabilityOf("meddra");
        assertNotNull(u, "the drop must be diagnosed, not silent");
        assertEquals(RuntimeDictionaryProvider.UnavailabilityReason.VERSION_NOT_INSTALLED,
                u.reason());
        assertTrue(u.detail().contains("25.0"), u.detail());
        assertTrue(u.detail().contains("27.0"), "names what the flat store holds: " + u.detail());

        assertTrue(DictionaryStore.load(dir, Map.of("meddra", "27.0")).isAvailable("meddra"),
                "a request MATCHING the flat file's declared version still answers");
        assertTrue(DictionaryStore.load(dir, Map.of("unii", "1.0")).isAvailable("meddra"),
                "a request for a DIFFERENT type leaves the flat file alone");
    }


    /** A flat file declaring NO version cannot satisfy any request. */
    @Test
    void aVersionlessFlatFileCannotSatisfyARequest(@TempDir Path dir) throws IOException
    {
        Files.writeString(dir.resolve("meddra.json"),
                "{\"type\":\"meddra\",\"levels\":{\"PT\":{\"HEADACHE\":\"Headache\"}}}");

        RuntimeDictionaryProvider p = DictionaryStore.load(dir, Map.of("meddra", "27.0"));

        assertFalse(p.isAvailable("meddra"));
        RuntimeDictionaryProvider.Unavailability u = p.unavailabilityOf("meddra");
        assertNotNull(u);
        assertTrue(u.detail().contains("no version"), u.detail());
    }


    /**
     * Batch B7 — the SELECT side applies the installer's version-token test before the requested
     * version (which can come from a define.xml, i.e. from study data) may become a path segment.
     * {@code ..} and separators must never reach {@code resolve()}.
     */
    @Test
    void aTraversingRequestedVersionIsRejectedNotResolved(@TempDir Path dir) throws IOException
    {
        Path store = dir.resolve("store");
        installed(store, "meddra", "27.0", MEDDRA_27);
        // A directory OUTSIDE the store that ".." could reach: were the token resolved, the
        // dictionary file planted there would load and the test below would fail on isAvailable.
        Path outside = Files.createDirectories(dir.resolve("outside").resolve("v"));
        Files.writeString(outside.resolve("meddra.json"), MEDDRA_27);

        for (String hostile : new String[]
        {
                "..", "../../outside/v", "27.0/../26.1", "a/b", "a\\b", "27 .0"
        })
        {
            RuntimeDictionaryProvider p = DictionaryStore.load(store, Map.of("meddra", hostile));
            assertFalse(p.isAvailable("meddra"), "hostile token must not bind: " + hostile);
            RuntimeDictionaryProvider.Unavailability u = p.unavailabilityOf("meddra");
            assertNotNull(u, hostile);
            assertTrue(u.detail().contains("not a usable store version token"),
                    hostile + " -> " + u.detail());
        }
    }


    /**
     * Batch B2, store level: one unreadable version directory degrades ONLY its own type. A
     * truncated {@code .json.gz} (not valid gzip) among healthy siblings must leave the siblings
     * bound and give the corrupt one the right diagnosis — not collapse the whole provider into "is
     * not installed" for every type.
     */
    @Test
    void aCorruptVersionFileDegradesOnlyItsOwnType(@TempDir Path dir) throws IOException
    {
        installed(dir, "meddra", "27.0", MEDDRA_27);
        Path uniiDir = Files.createDirectories(dir.resolve("unii").resolve("4Aug2026"));
        Files.write(uniiDir.resolve("unii.json.gz"), new byte[]
        {
                0x00, 0x01, 0x02, 0x03
        });

        RuntimeDictionaryProvider p = DictionaryStore.load(dir,
                Map.of("meddra", "27.0", "unii", "4Aug2026"));

        assertTrue(p.isAvailable("meddra"), "the healthy sibling must stay bound");
        assertFalse(p.isAvailable("unii"));
        RuntimeDictionaryProvider.Unavailability u = p.unavailabilityOf("unii");
        assertNotNull(u, "the corrupt file must be diagnosed, not folded into 'not installed'");
        assertEquals(RuntimeDictionaryProvider.UnavailabilityReason.NO_USABLE_CONTENT, u.reason());
        assertTrue(u.detail().contains("could not be read"), u.detail());
        assertTrue(u.detail().contains("reinstall"), u.detail());
    }

    // ------------------------------------------------------------------
    // D13 item 2 — the recorded WHY behind every declined binding
    // ------------------------------------------------------------------


    @Test
    void noSelectionRecordsWhichVersionsAreInstalledAndHowToChoose(@TempDir Path dir)
        throws IOException
    {
        installed(dir, "meddra", "27.0", MEDDRA_27);
        installed(dir, "meddra", "26.1", MEDDRA_26);

        RuntimeDictionaryProvider p = DictionaryStore.load(dir, Map.of());

        RuntimeDictionaryProvider.Unavailability u = p.unavailabilityOf("meddra");
        assertNotNull(u, "the store must say WHY meddra did not load");
        assertEquals(RuntimeDictionaryProvider.UnavailabilityReason.NO_VERSION_SELECTED,
                u.reason());
        assertTrue(u.detail().contains("26.1, 27.0"),
                "the operator must be told what IS installed: " + u.detail());
        assertTrue(u.detail().contains("--meddra-version"), "…and how to choose: " + u.detail());
        assertTrue(u.detail().contains(DictionaryStore.MANIFEST), u.detail());
        assertFalse(u.detail().contains("install it into"),
                "an installed dictionary must never be reported as not installed: " + u.detail());
    }


    /**
     * Phase 6b: the message must name the option that actually exists. SNOMED's selection option is
     * {@code --snomed-version-select}, because {@code --snomed-version} is an accepted-but-ignored
     * Python-compat option — pointing an operator at it would send them to a flag that does
     * nothing.
     */
    @Test
    void theSnomedAdviceNamesTheSelectOptionNotTheCompatSink(@TempDir Path dir) throws IOException
    {
        installed(dir, "snomed", "2024-09-01",
                "{\"type\":\"snomed\",\"version\":\"2024-09-01\",\"levels\":"
                        + "{\"PT\":{\"X\":\"X\"}}}");

        RuntimeDictionaryProvider.Unavailability u = DictionaryStore.load(dir, Map.of())
                .unavailabilityOf("snomed");

        assertNotNull(u);
        assertTrue(u.detail().contains("--snomed-version-select"), u.detail());
        assertFalse(u.detail().contains("--snomed-version,"),
                "must not point at the ignored Python-compat option: " + u.detail());
    }


    @Test
    void aSelectedButUninstalledVersionRecordsThatDiagnosis(@TempDir Path dir) throws IOException
    {
        installed(dir, "meddra", "27.0", MEDDRA_27);

        RuntimeDictionaryProvider p = DictionaryStore.load(dir, Map.of("meddra", "25.0"));

        RuntimeDictionaryProvider.Unavailability u = p.unavailabilityOf("meddra");
        assertNotNull(u);
        assertEquals(RuntimeDictionaryProvider.UnavailabilityReason.VERSION_NOT_INSTALLED,
                u.reason());
        assertTrue(u.detail().contains("25.0"), "names the version that was asked for");
        assertTrue(u.detail().contains("27.0"), "names what is installed instead: " + u.detail());
    }


    @Test
    void anUnusableInstalledVersionRecordsNoUsableContent(@TempDir Path dir) throws IOException
    {
        installed(dir, "meddra", "27.0", "{\"type\":\"meddra\",\"levels\":{}}");

        RuntimeDictionaryProvider p = DictionaryStore.load(dir, Map.of("meddra", "27.0"));

        RuntimeDictionaryProvider.Unavailability u = p.unavailabilityOf("meddra");
        assertNotNull(u);
        assertEquals(RuntimeDictionaryProvider.UnavailabilityReason.NO_USABLE_CONTENT, u.reason());
        assertTrue(u.detail().contains("reinstall"),
                "the action is a reinstall, not a fresh install: " + u.detail());
    }


    /** A type never installed at all records nothing — the reader defaults to NOT_INSTALLED. */
    @Test
    void aTypeWithNoInstallationRecordsNothingAndDefaultsToNotInstalled(@TempDir Path dir)
        throws IOException
    {
        installed(dir, "meddra", "27.0", MEDDRA_27);

        RuntimeDictionaryProvider p = DictionaryStore.load(dir, Map.of("meddra", "27.0"));

        assertNull(p.unavailabilityOf("unii"));
        assertEquals(RuntimeDictionaryProvider.notInstalledDetail(),
                p.unavailabilityDetail("unii"));
    }

    // ------------------------------------------------------------------
    // Directory resolution
    // ------------------------------------------------------------------


    @Test
    void resolverPrecedenceIsExplicitThenEnvThenSysprop(@TempDir Path dir) throws IOException
    {
        Path a = Files.createDirectory(dir.resolve("a"));
        Path b = Files.createDirectory(dir.resolve("b"));
        Path c = Files.createDirectory(dir.resolve("c"));

        assertEquals(a, DictionaryDirectoryResolver
                .resolve(a.toString(), b.toString(), c.toString()).orElseThrow());
        assertEquals(b, DictionaryDirectoryResolver.resolve(null, b.toString(), c.toString())
                .orElseThrow());
        assertEquals(c,
                DictionaryDirectoryResolver.resolve(null, null, c.toString()).orElseThrow());
    }


    /** A typo in a configured path must fail loudly, not degrade to a clean-looking run. */
    @Test
    void aConfiguredButMissingDirectoryIsAHardError()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DictionaryDirectoryResolver.resolve("/no/such/dictionaries", null, null));
        assertTrue(e.getMessage().contains("--dictionaries-dir"), e.getMessage());

        assertThrows(IllegalStateException.class,
                () -> DictionaryDirectoryResolver.resolve(null, "/no/such/dictionaries", null));
        assertThrows(IllegalStateException.class,
                () -> DictionaryDirectoryResolver.resolve(null, null, "/no/such/dictionaries"));
    }


    /** Nobody configured the conventional default, so its absence is not an error. */
    @Test
    void anAbsentConventionalDefaultIsNotAnError()
    {
        Optional<Path> resolved = DictionaryDirectoryResolver.resolve(null, null, null);
        assertTrue(resolved.isEmpty() || Files.isDirectory(resolved.orElseThrow()),
                "either nothing resolves, or what resolves exists");
    }

    // ------------------------------------------------------------------


    private static void installed(Path aStore, String aType, String aVersion, String aJson)
        throws IOException
    {
        Path versionDir = Files.createDirectories(aStore.resolve(aType).resolve(aVersion));
        Files.writeString(versionDir.resolve(aType + ".json"), aJson);
    }

}
