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
