package net.cumba.corej.core.metadata.store.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.cumba.corej.core.metadata.pickle.LocalPickleSource;
import net.cumba.corej.core.metadata.store.MetadataStore;
import net.cumba.corej.core.metadata.store.StoreProvenance;
import net.cumba.corej.core.metadata.store.StoredCodelist;
import net.cumba.corej.core.metadata.store.StoredCtPackage;
import net.cumba.corej.core.metadata.store.StoredProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ⭐ The conformance test of plan §5.1: {@link PickleStoreSeeder} and {@link WebApiStoreSeeder} must
 * produce a BYTE-IDENTICAL store from equivalent input. This is the single test that keeps the two
 * sources from drifting back into two caches — the defect the whole plan exists to remove — so its
 * assertion is deliberately total: every byte of the file, manifest included.
 *
 * <p>
 * "Equivalent input" is constructed, not asserted: the web-api cache the {@code WebApiStoreSeeder}
 * reads is generated FROM the pickle directory by the production {@code PickleCacheSeeder} (the
 * converter that produced every real pre-seeded cache), plus the {@code /mdr/products} document
 * that has no pickle source. Both seeds run with the same provenance override, since provenance
 * legitimately names the source and would otherwise be the one honest difference.
 * </p>
 */
class StoreSeederConformanceTest
{

    private static final List<StoreProvenance> FIXED_PROVENANCE = List
            .of(new StoreProvenance("conformance", "fixture", "2026-09-08T00:00:00Z"));

    @TempDir
    private Path temp;

    private Path pickleDir;

    private Path webCacheDir;

    @BeforeEach
    void setUp() throws IOException
    {
        pickleDir = Files.createDirectories(temp.resolve("pickles"));
        webCacheDir = Files.createDirectories(temp.resolve("web-cache"));
        SeedFixtures.writePickleDir(pickleDir);
        SeedFixtures.writeWebCache(pickleDir, webCacheDir);
    }


    @Test
    void bothSeedersProduceAByteIdenticalStore() throws IOException
    {
        Path fromPickles = temp.resolve("from-pickles.zip");
        Path fromWebApi = temp.resolve("from-web-api.zip");

        new PickleStoreSeeder(new LocalPickleSource(pickleDir))
                .seed(StoreSeedOptions.of(fromPickles).withProvenanceOverride(FIXED_PROVENANCE));
        new WebApiStoreSeeder(SeedFixtures.offlineAccess(webCacheDir))
                .seed(StoreSeedOptions.of(fromWebApi).withProvenanceOverride(FIXED_PROVENANCE));

        assertEquals(-1, Files.mismatch(fromPickles, fromWebApi),
                "the two seeders diverged - the store is on its way to being two caches again");
    }


    /**
     * The identity above must not hold vacuously: both stores carry the full fixture content, and
     * the wire-form differences the projection absorbs are actually present in the inputs.
     */
    @Test
    void theIdenticalStoresCarryTheFullProjection() throws IOException
    {
        Path target = temp.resolve("store.zip");
        StoreSeedReport report = new PickleStoreSeeder(new LocalPickleSource(pickleDir))
                .seed(StoreSeedOptions.of(target).withProvenanceOverride(FIXED_PROVENANCE));

        assertEquals(5, report.productsSeeded());
        assertEquals(3, report.ctPackagesFetched());
        assertEquals(List.of(), report.warnings());
        try (MetadataStore store = MetadataStore.open(target))
        {
            assertEquals(List.of(SeedFixtures.PKG_QS, SeedFixtures.PKG_1, SeedFixtures.PKG_2),
                    store.publishedCtPackages());
            assertEquals(List.of(SeedFixtures.ADAM_KEY, SeedFixtures.IG_KEY, SeedFixtures.TIG_KEY),
                    store.productCatalogue(), "the catalogue is IG-typed keys only, TIG included");

            StoredProduct ig = store.product(SeedFixtures.IG_KEY).orElseThrow();
            assertEquals("/mdr/sdtm/2-0", ig.modelHref());
            assertEquals(List.of("C66731", "C78735"),
                    ig.classes().get(0).datasets().get(0).variables().get(0).codelistIds(),
                    "multi-valued _links.codelist must survive as a list");

            StoredProduct model = store.product(SeedFixtures.SDTM_MODEL_KEY).orElseThrow();
            assertEquals("C82515", model.classes().get(0).classVariables().get(0).variableCcode());
            assertEquals(1, model.datasets().size(), "models keep their top-level datasets");

            StoredProduct adam = store.product(SeedFixtures.ADAM_KEY).orElseThrow();
            assertEquals("SUBJECT LEVEL ANALYSIS DATASET", adam.dataStructures().get(0).className(),
                    "the source's 'class' key lands in className");

            StoredCtPackage pkg = store.ctPackage(SeedFixtures.PKG_2).orElseThrow();
            StoredCodelist ny = codelist(pkg, "NY");
            assertEquals(Boolean.FALSE, ny.extensible(),
                    "extensible is a real Boolean regardless of the source's wire form");
            Optional<StoredCodelist> yesOnly = pkg.codelists().stream()
                    .filter(c -> "YESONLY".equals(c.submissionValue())).findFirst();
            assertEquals("The affirmative response (revised).",
                    yesOnly.orElseThrow().terms().get(0).definition());
        }
    }


    /**
     * Provenance is the one legitimate difference between the two seeders — pin that WITHOUT the
     * override the stores differ in the manifest and only there, so a future change cannot quietly
     * widen "differs in provenance" into "differs".
     */
    @Test
    void withoutTheOverrideOnlyTheManifestDiffers() throws IOException
    {
        Path fromPickles = temp.resolve("from-pickles.zip");
        Path fromWebApi = temp.resolve("from-web-api.zip");

        new PickleStoreSeeder(new LocalPickleSource(pickleDir))
                .seed(StoreSeedOptions.of(fromPickles));
        new WebApiStoreSeeder(SeedFixtures.offlineAccess(webCacheDir))
                .seed(StoreSeedOptions.of(fromWebApi));

        assertNotEquals(-1L, Files.mismatch(fromPickles, fromWebApi),
                "derived provenance names the source and must differ");
        try (MetadataStore pickles = MetadataStore.open(fromPickles);
                MetadataStore webApi = MetadataStore.open(fromWebApi))
        {
            assertEquals(pickles.manifest().partHashes(), webApi.manifest().partHashes(),
                    "every data part must still be byte-identical");
            assertEquals(pickles.manifest().publishedCtPackages(),
                    webApi.manifest().publishedCtPackages());
            assertEquals(pickles.manifest().productCatalogue(),
                    webApi.manifest().productCatalogue());
            assertEquals(PickleStoreSeeder.PROVENANCE_SOURCE,
                    pickles.manifest().provenance().get(0).source());
            assertEquals(WebApiStoreSeeder.PROVENANCE_SOURCE,
                    webApi.manifest().provenance().get(0).source());
        }
    }


    /** A store seeded by one seeder and re-seeded by the other merges rather than forks. */
    @Test
    void crossSeederReseedMergesOntoOneStore() throws IOException
    {
        Path target = temp.resolve("store.zip");
        new PickleStoreSeeder(new LocalPickleSource(pickleDir)).seed(StoreSeedOptions.of(target));
        StoreSeedReport second = new WebApiStoreSeeder(SeedFixtures.offlineAccess(webCacheDir))
                .seed(StoreSeedOptions.of(target));

        assertEquals(3, second.ctPackagesCarried(),
                "every CT package was already present - none may be re-fetched");
        assertEquals(0, second.ctPackagesFetched());
        try (MetadataStore store = MetadataStore.open(target))
        {
            List<String> sources = store.manifest().provenance().stream()
                    .map(StoreProvenance::source).toList();
            assertEquals(
                    List.of(PickleStoreSeeder.PROVENANCE_SOURCE,
                            WebApiStoreSeeder.PROVENANCE_SOURCE),
                    sources, "both sources appear in provenance, once each");
            assertTrue(store.product(SeedFixtures.IG_KEY).isPresent());
        }
    }


    private static StoredCodelist codelist(StoredCtPackage aPackage, String aSubmissionValue)
    {
        return aPackage.codelists().stream()
                .filter(c -> aSubmissionValue.equals(c.submissionValue())).findFirst()
                .orElseThrow();
    }
}
