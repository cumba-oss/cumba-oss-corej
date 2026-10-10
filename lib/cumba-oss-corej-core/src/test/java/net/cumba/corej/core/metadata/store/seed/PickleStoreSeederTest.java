package net.cumba.corej.core.metadata.store.seed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import net.cumba.corej.core.metadata.pickle.LocalPickleSource;
import net.cumba.corej.core.metadata.store.MetadataStore;
import net.cumba.corej.core.metadata.store.Presence;
import net.cumba.corej.core.metadata.store.StoredVariable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PickleStoreSeeder} against the miniature pickle cache: projection breadth, the id grammar
 * guard, and above all the re-seed semantics of plan §5.2 — presence-based skipping that really
 * skips (proven by corrupting the source under a present package), {@code refresh} that really
 * re-fetches, the source-union enumeration, and the atomic replace.
 */
class PickleStoreSeederTest
{

    @TempDir
    private Path temp;

    private Path pickleDir;

    private Path target;

    @BeforeEach
    void setUp() throws IOException
    {
        pickleDir = Files.createDirectories(temp.resolve("pickles"));
        target = temp.resolve("store").resolve("metadata-cache.zip");
        SeedFixtures.writePickleDir(pickleDir);
    }


    private PickleStoreSeeder seeder()
    {
        return new PickleStoreSeeder(new LocalPickleSource(pickleDir));
    }


    /**
     * Engine L1 (PLAN-define-ct-evaluation review round 1): a target holding a store of a NEWER
     * format is refused - a routine re-seed must not silently downgrade a store another
     * installation relies on. {@code refresh} (the CLI's {@code --seed-overwrite}) replaces it
     * deliberately; an OLDER store is re-seeded in full without a word of warning (D-17).
     */
    @Test
    void aNewerFormatTargetIsRefusedUnlessRefreshIsExplicit() throws IOException
    {
        seeder().seed(StoreSeedOptions.of(target));
        int current;
        try (MetadataStore store = MetadataStore.open(target))
        {
            current = store.manifest().formatVersion();
        }
        relabelFormatVersion(target, current + 1);

        IOException refused = assertThrows(IOException.class,
                () -> seeder().seed(StoreSeedOptions.of(target)));
        assertTrue(refused.getMessage().contains("newer build"), refused.getMessage());
        assertTrue(refused.getMessage().contains("Refusing to overwrite"), refused.getMessage());
        assertThrows(net.cumba.corej.core.metadata.store.StoreFormatException.class,
                () -> MetadataStore.open(target), "the newer store is left untouched");

        StoreSeedReport replaced = seeder().seed(StoreSeedOptions.of(target).withRefresh(true));
        assertEquals(List.of(), replaced.warnings());
        try (MetadataStore store = MetadataStore.open(target))
        {
            assertEquals(current, store.manifest().formatVersion());
        }

        relabelFormatVersion(target, 1);
        StoreSeedReport reseeded = seeder().seed(StoreSeedOptions.of(target));
        assertEquals(List.of(), reseeded.warnings(),
                "D-17: an OLDER store is re-acquired in full and that is a notice, not a warning");
        assertEquals(0, reseeded.ctPackagesCarried(), "nothing is carried from an old store");
        try (MetadataStore store = MetadataStore.open(target))
        {
            assertEquals(current, store.manifest().formatVersion());
        }
    }


    /** The relabel trick {@code MetadataStoreCorruptionTest} uses: rewrite the manifest version. */
    private static void relabelFormatVersion(Path aStore, int aVersion) throws IOException
    {
        java.util.Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(aStore.toFile()))
        {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> names = zip.entries();
            while (names.hasMoreElements())
            {
                java.util.zip.ZipEntry entry = names.nextElement();
                entries.put(entry.getName(), zip.getInputStream(entry).readAllBytes());
            }
        }
        String manifest = new String(entries.get("manifest.json"), StandardCharsets.UTF_8);
        String relabelled = manifest.replaceFirst("\"formatVersion\" : \\d+",
                "\"formatVersion\" : " + aVersion);
        assertTrue(!relabelled.equals(manifest), "the relabel must change the manifest");
        entries.put("manifest.json", relabelled.getBytes(StandardCharsets.UTF_8));
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(
                Files.newOutputStream(aStore)))
        {
            for (java.util.Map.Entry<String, byte[]> entry : entries.entrySet())
            {
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }


    @Test
    void projectsTheFullVariableFieldUnion() throws IOException
    {
        seeder().seed(StoreSeedOptions.of(target));
        try (MetadataStore store = MetadataStore.open(target))
        {
            StoredVariable dtc = store.product(SeedFixtures.SDTM_MODEL_KEY).orElseThrow().classes()
                    .get(0).classVariables().get(0);
            assertEquals("--DTC", dtc.name());
            assertEquals("Timing variable", dtc.roleDescription());
            assertEquals("Collection date and time.", dtc.definition());
            assertEquals("ISO 8601.", dtc.notes());
            assertEquals("2003-12-15; 2003-12-15T13:14", dtc.examples());
            assertEquals("None", dtc.usageRestrictions());
            assertEquals("C82515", dtc.variableCcode());

            StoredVariable lbtestcd = store.product(SeedFixtures.IG_KEY).orElseThrow().classes()
                    .get(0).datasets().get(0).variables().get(1);
            assertEquals(List.of("GLUC", "ALB"), lbtestcd.valueList());
            assertEquals("Lab test codes", lbtestcd.describedValueDomain());
            assertEquals("Req", lbtestcd.core());
        }
    }


    @Test
    void aStrayCtLookalikeFileIsWarnedAndExcluded() throws IOException
    {
        SeedFixtures.writePickle(pickleDir.resolve("oddct-notadate.pkl"),
                java.util.Map.of("package", "oddct-notadate"));

        StoreSeedReport report = seeder().seed(StoreSeedOptions.of(target));

        assertTrue(
                report.warnings().stream()
                        .anyMatch(w -> w.startsWith("oddct-notadate:") && w.contains("excluded")),
                "the malformed stem must be warned about: " + report.warnings());
        try (MetadataStore store = MetadataStore.open(target))
        {
            assertEquals(List.of(SeedFixtures.PKG_QS, SeedFixtures.PKG_1, SeedFixtures.PKG_2),
                    store.publishedCtPackages(),
                    "a malformed id must not poison the published enumeration");
        }
    }


    /**
     * ⭐ The incremental-acquisition proof: after the first seed, a present package's pickle is
     * CORRUPTED on disk. A default re-seed must not notice — presence in the existing store is
     * sufficient (CT packages are immutable once published) — while {@code refresh} must trip over
     * it, proving the skip is real in both directions.
     */
    @Test
    void reseedCarriesPresentPackagesWithoutTouchingTheirPickles() throws IOException
    {
        seeder().seed(StoreSeedOptions.of(target));
        Files.write(pickleDir.resolve(SeedFixtures.PKG_1 + ".pkl"),
                "no longer a pickle".getBytes(StandardCharsets.UTF_8));
        SeedFixtures.writePickle(pickleDir.resolve("sendct-2024-03-29.pkl"),
                SeedFixtures.qsPackage());

        StoreSeedReport report = seeder().seed(StoreSeedOptions.of(target));

        assertEquals(3, report.ctPackagesCarried());
        assertEquals(1, report.ctPackagesFetched(), "only the new package is acquired");
        assertEquals(List.of(), report.warnings(),
                "the corrupted pickle must never have been read");
        try (MetadataStore store = MetadataStore.open(target))
        {
            assertEquals(2, store.ctPackage(SeedFixtures.PKG_1).orElseThrow().codelists().size(),
                    "the carried package keeps its original content");
            assertEquals(Presence.PRESENT, store.presence("sendct-2024-03-29"));
        }
    }


    @Test
    void refreshReallyRefetchesEverything() throws IOException
    {
        seeder().seed(StoreSeedOptions.of(target));
        Files.write(pickleDir.resolve(SeedFixtures.PKG_1 + ".pkl"),
                "no longer a pickle".getBytes(StandardCharsets.UTF_8));

        StoreSeedReport report = seeder().seed(StoreSeedOptions.of(target).withRefresh(true));

        assertEquals(0, report.ctPackagesCarried());
        assertEquals(List.of(SeedFixtures.PKG_1), report.ctPackagesMissed(),
                "refresh re-reads the source, so the corruption must surface");
        try (MetadataStore store = MetadataStore.open(target))
        {
            assertEquals(Presence.ABSENT, store.presence(SeedFixtures.PKG_1));
            assertTrue(store.publishedCtPackages().contains(SeedFixtures.PKG_1),
                    "still enumerated - the directory still lists it");
        }
    }


    @Test
    void aPackageTheSourceLostStaysHeldAndEnumerated() throws IOException
    {
        seeder().seed(StoreSeedOptions.of(target));
        Files.delete(pickleDir.resolve(SeedFixtures.PKG_QS + ".pkl"));

        StoreSeedReport report = seeder().seed(StoreSeedOptions.of(target));

        assertEquals(3, report.ctPackagesCarried());
        try (MetadataStore store = MetadataStore.open(target))
        {
            assertEquals(Presence.PRESENT, store.presence(SeedFixtures.PKG_QS));
            assertTrue(store.publishedCtPackages().contains(SeedFixtures.PKG_QS),
                    "published-enumeration union keeps the id");
        }
    }


    /**
     * D-27 (review round 2, L1): a corrupt product entry in the BASELINE (embedded key mismatching
     * its file, so it binds lazily and fails on access) is dropped with a warning instead of
     * killing the seed — a plain re-seed repairs the store.
     */
    @Test
    void aCorruptBaselineProductIsDroppedWithAWarningNotCarried() throws IOException
    {
        seeder().seed(StoreSeedOptions.of(target));
        Path damaged = temp.resolve("damaged.zip");
        net.cumba.corej.core.metadata.store.CorruptStores.withMismatchingProductKey(target, damaged,
                SeedFixtures.IG_KEY);
        Files.move(damaged, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        // The source no longer offers any product, so every product would be carried forward.
        Files.delete(pickleDir.resolve("standards_details.pkl"));

        StoreSeedReport report = seeder().seed(StoreSeedOptions.of(target));

        assertTrue(
                report.warnings().stream()
                        .anyMatch(w -> w.startsWith(
                                SeedFixtures.IG_KEY + ": the existing store's copy is corrupt")),
                report.warnings().toString());
        try (MetadataStore store = MetadataStore.open(target))
        {
            assertTrue(store.product(SeedFixtures.IG_KEY).isEmpty(), "dropped, not carried");
            assertTrue(store.product(SeedFixtures.ADAM_KEY).isPresent(),
                    "the intact products are still carried forward");
        }
    }


    /** Re-seeding (all-carry) and fresh-seeding the same input give byte-identical stores. */
    @Test
    void carriedContentIsByteIdenticalToFreshProjection() throws IOException
    {
        seeder().seed(StoreSeedOptions.of(target));
        seeder().seed(StoreSeedOptions.of(target));
        Path fresh = temp.resolve("fresh.zip");
        seeder().seed(StoreSeedOptions.of(fresh));

        assertEquals(-1, Files.mismatch(target, fresh),
                "carry-forward must reproduce the projection bit for bit");
    }


    @Test
    void aFailedSeedLeavesTheExistingStoreAndNoDroppings() throws IOException
    {
        seeder().seed(StoreSeedOptions.of(target));
        byte[] before = Files.readAllBytes(target);

        PickleStoreSeeder broken = new PickleStoreSeeder(
                new LocalPickleSource(temp.resolve("does-not-exist")));
        assertThrows(IOException.class, () -> broken.seed(StoreSeedOptions.of(target)));

        assertArrayEquals(before, Files.readAllBytes(target),
                "the previous store must survive a failed seed byte for byte");
        try (Stream<Path> files = Files.list(target.getParent()))
        {
            assertEquals(List.of(target), files.toList(), "no temp files may be left behind");
        }
    }
}
