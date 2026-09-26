package net.cumba.corej.core.metadata.store.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import net.cumba.corej.core.metadata.pickle.LocalPickleSource;
import net.cumba.corej.core.metadata.pickle.PickleCacheSeeder;
import net.cumba.corej.core.metadata.pickle.SeedOptions;
import net.cumba.corej.core.metadata.store.MetadataStore;
import net.cumba.corej.core.metadata.store.RealCorpusLocator;
import net.cumba.corej.core.metadata.store.StoreProvenance;
import net.cumba.web.api.cache.GzipFileApiCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The plan §5.1 byte-identity conformance over the REAL corpora: the full 463 MB pickle cache (206
 * CT packages, 34 IGs, 14 models) seeded by {@link PickleStoreSeeder}, against
 * {@link WebApiStoreSeeder} reading a web-api cache generated from those same pickles by the
 * production {@code PickleCacheSeeder} — plus the one document that has no pickle source,
 * {@code /mdr/products}, copied from the recorded real cache (measured 2026-09-08: its 34
 * Implementation-Guide entries match the pickle's standards keys one for one).
 *
 * <p>
 * Skipped when the machine does not carry the corpora (the pickle corpus via
 * {@link RealCorpusLocator}, the recorded web cache at {@code /data/cdisc.metadata.library-cache});
 * the synthetic {@link StoreSeederConformanceTest} pins the same property everywhere. This run is
 * heavyweight (it unpickles ~420 MB twice and gzip-writes an intermediate cache) and exists to
 * prove the property, the measured sizes and the audit's dedup numbers on the real data.
 * </p>
 */
class StoreSeederRealDataConformanceTest
{

    private static final Path REAL_WEB_CACHE = Path.of("/data/cdisc.metadata.library-cache");

    /** The one document in that cache this test needs; no pickle carries it. */
    private static final String PRODUCTS_KEY = "/api/mdr/products";

    /**
     * The name the recorded cache holds {@link #PRODUCTS_KEY} under.
     *
     * <p>
     * The recording predates Q14 (2026-09-11), which replaced the cache file-name encoding because
     * the old one mapped {@code /a/b} and {@code /a_b} onto one file. The ruling waived backward
     * compatibility for <em>caches</em> — they get refilled — but this directory is a frozen
     * <em>fixture</em>: there is no API key here to re-record it with. So the historical name is
     * named as history, and the copy is staged under whatever
     * {@link GzipFileApiCache#toCacheFileName(String)} calls it today. Nothing here re-implements
     * either encoding.
     * </p>
     */
    private static final String LEGACY_PRODUCTS_ENTRY = "api_mdr_products.json.gz";

    private static final List<StoreProvenance> FIXED_PROVENANCE = List
            .of(new StoreProvenance("conformance", "real-corpus", "2026-09-08T00:00:00Z"));

    @TempDir
    private Path temp;

    @Test
    void bothSeedersProduceAByteIdenticalStoreFromTheRealCorpus() throws IOException
    {
        // F5 (final cross-plan review): the pickle corpus resolves through RealCorpusLocator —
        // the SAME resolution the factory's real-corpus tests use — so the two real-corpus test
        // classes can no longer look for the same corpus differently. A named-but-broken corpus
        // fails loudly in the locator; only absent-everywhere skips, with the named message.
        assumeTrue(RealCorpusLocator.locate().isPresent(), RealCorpusLocator.ABSENT_MESSAGE);
        Path realPickles = RealCorpusLocator.locate().orElseThrow();
        Path recordedProducts = recordedProductsEntry();
        assumeTrue(recordedProducts != null,
                "real web cache (for /mdr/products) not present - skipping");

        Path fromPickles = temp.resolve("from-pickles.zip");
        StoreSeedReport pickleReport = new PickleStoreSeeder(new LocalPickleSource(realPickles))
                .seed(StoreSeedOptions.of(fromPickles).withProvenanceOverride(FIXED_PROVENANCE));

        Path webCache = Files.createDirectories(temp.resolve("web-cache"));
        new PickleCacheSeeder().seed(SeedOptions
                .builder(new LocalPickleSource(realPickles), webCache, SeedFixtures.BASE_URL)
                .writeMeta(false).build());
        // Staged under the name the cache reads it under today, whatever that is — the gzip bytes
        // are copied as they are, so nothing is re-compressed.
        Files.copy(recordedProducts,
                webCache.resolve(new GzipFileApiCache(webCache.toAbsolutePath(), ".json")
                        .toCacheFileName(PRODUCTS_KEY)),
                StandardCopyOption.REPLACE_EXISTING);

        Path fromWebApi = temp.resolve("from-web-api.zip");
        StoreSeedReport webReport = new WebApiStoreSeeder(SeedFixtures.offlineAccess(webCache))
                .seed(StoreSeedOptions.of(fromWebApi).withProvenanceOverride(FIXED_PROVENANCE));

        assertEquals(-1, Files.mismatch(fromPickles, fromWebApi),
                "the two seeders diverged on the real corpus; pickle report: "
                        + pickleReport.summary() + "; web report: " + webReport.summary());

        assertEquals(206, pickleReport.ctPackagesFetched());
        assertEquals(48, pickleReport.productsSeeded(), "34 IGs + 14 models");
        assertEquals(206, webReport.ctPackagesFetched());
        assertEquals(48, webReport.productsSeeded());

        try (MetadataStore store = MetadataStore.open(fromPickles))
        {
            assertEquals(206, store.publishedCtPackages().size());
            assertEquals(34, store.productCatalogue().size());
            assertTrue(store.product("standards/tig/1-0/sdtm").isPresent());
            assertTrue(store.product("models/adam/2-1").isPresent());
        }

        long size = Files.size(fromPickles);
        assertTrue(size > 2_000_000 && size < 6_000_000,
                "store size " + size + " bytes is wildly off the ~3.4 MB the audit measured");
        // Surfaced in the test log for the phase report; the assertion above is the gate.
        System.out.printf("real-corpus store: %,d bytes; pickle seed: %s%n", size,
                pickleReport.summary());
    }


    /**
     * NS3 (PLAN-define-ct-evaluation, T1-3 b / T1-9): the fields the format-3 bump admitted really
     * arrive from the REAL pickle corpus — asserted on the zip's own JSON, never through the
     * records, so this compiles against any format and reds by assertion, not by compilation. The
     * codelist {@code name} was the one field the source publishes at codelist level that the store
     * dropped (41 852 / 41 852 codelists carry it); the product scalars and the CDASH
     * domain/scenario/field levels are the T1-9 additions.
     */
    @Test
    void theRealSeedCarriesEveryFieldTheFormatBumpAdmitted() throws IOException
    {
        assumeTrue(RealCorpusLocator.locate().isPresent(), RealCorpusLocator.ABSENT_MESSAGE);
        Path realPickles = RealCorpusLocator.locate().orElseThrow();
        Path store = temp.resolve("pickles-only.zip");
        new PickleStoreSeeder(new LocalPickleSource(realPickles))
                .seed(StoreSeedOptions.of(store).withProvenanceOverride(FIXED_PROVENANCE));

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(store.toFile()))
        {
            com.fasterxml.jackson.databind.JsonNode headers = mapper
                    .readTree(zip.getInputStream(zip.getEntry("ct/codelists.json")))
                    .get("codelists");
            int nullName = 0;
            int sexVersions = 0;
            for (com.fasterxml.jackson.databind.JsonNode header : headers)
            {
                if (!header.hasNonNull("name"))
                {
                    nullName++;
                }
                if ("C66731".equals(header.path("conceptId").asText()))
                {
                    sexVersions++;
                    assertEquals("Sex", header.path("name").asText(),
                            "the SEX codelist's display name, as sdtmct-2024-03-29.pkl publishes it");
                }
            }
            assertTrue(sexVersions >= 1, "C66731 must be among the codelist versions");
            assertEquals(0, nullName, "every codelist header carries its name (T1-3 b)");

            com.fasterxml.jackson.databind.JsonNode sdtmig = mapper.readTree(
                    zip.getInputStream(zip.getEntry("products/standards/sdtmig/3-4.json")));
            assertTrue(sdtmig.hasNonNull("description") && sdtmig.hasNonNull("effectiveDate"),
                    "the product scalars the source publishes are stored (T1-9): " + sdtmig);

            com.fasterxml.jackson.databind.JsonNode cdashig = mapper.readTree(
                    zip.getInputStream(zip.getEntry("products/standards/cdashig/2-3.json")));
            int domains = 0;
            int scenarios = 0;
            int fields = 0;
            for (com.fasterxml.jackson.databind.JsonNode clazz : cdashig.path("classes"))
            {
                for (com.fasterxml.jackson.databind.JsonNode domain : clazz.path("domains"))
                {
                    domains++;
                    fields += domain.path("fields").size();
                }
                for (com.fasterxml.jackson.databind.JsonNode scenario : clazz.path("scenarios"))
                {
                    scenarios++;
                    fields += scenario.path("fields").size();
                }
            }
            assertTrue(domains > 0 && scenarios > 0 && fields > 0,
                    "CDASHIG 2.3 domains/scenarios/fields are stored as levels (T1-9, S18): "
                            + domains + "/" + scenarios + "/" + fields);
        }
    }


    /**
     * Locates {@code /mdr/products} in the recorded cache, under either the name the cache uses now
     * or the pre-Q14 name the directory was recorded with.
     *
     * <p>
     * Both are accepted on purpose. Looking only for the legacy name would turn a future
     * re-recording into a silent skip — the test would stop running and say nothing — and looking
     * only for the current name would skip on the directory that exists today.
     * </p>
     *
     * @return the entry, or {@code null} when the recorded cache is not on this machine.
     */
    private static Path recordedProductsEntry()
    {
        Path current = REAL_WEB_CACHE.resolve(
                new GzipFileApiCache(REAL_WEB_CACHE, ".json").toCacheFileName(PRODUCTS_KEY));
        if (Files.isRegularFile(current))
        {
            return current;
        }
        Path legacy = REAL_WEB_CACHE.resolve(LEGACY_PRODUCTS_ENTRY);
        return Files.isRegularFile(legacy) ? legacy : null;
    }
}
