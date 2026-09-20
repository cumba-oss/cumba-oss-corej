package net.cumba.corej.core.metadata.store.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.cumba.corej.core.metadata.store.MetadataStore;
import net.cumba.corej.core.metadata.store.Presence;
import net.cumba.web.api.cache.ApiCache;
import net.cumba.web.api.cache.GzipFileApiCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link WebApiStoreSeeder} over a recorded {@code GzipFileApiCache} — the offline exercise mode
 * the real caches use too (no API key exists here; the base URL cannot leave the host, so a cache
 * miss fails immediately instead of reaching a network).
 */
class WebApiStoreSeederTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    private Path temp;

    private Path cacheDir;

    private Path target;

    @BeforeEach
    void setUp() throws IOException
    {
        Path pickleDir = Files.createDirectories(temp.resolve("pickles"));
        cacheDir = Files.createDirectories(temp.resolve("web-cache"));
        target = temp.resolve("store").resolve("metadata-cache.zip");
        SeedFixtures.writePickleDir(pickleDir);
        SeedFixtures.writeWebCache(pickleDir, cacheDir);
    }


    private WebApiStoreSeeder seeder()
    {
        return new WebApiStoreSeeder(SeedFixtures.offlineAccess(cacheDir));
    }


    /**
     * ⛔ Both spellings must resolve. A trailing slash is an ordinary URL variation a proxy, mirror
     * or endpoint revision can introduce, and rejecting it seeds the store <b>without the
     * foundational SDTM model</b> — {@code projectProducts} warns and continues, so every
     * model-tier resolution downstream runs IG-only. Neither direction was pinned before (review
     * N1, 2026-09-17), which is how a {@code split("/", -1)} tightening filed as hygiene changed
     * what the seeder accepts.
     */
    @Test
    void modelKeyForAcceptsAHrefWithOrWithoutATrailingSlash()
    {
        assertEquals("models/sdtm/2-0", WebApiStoreSeeder.modelKeyFor("/mdr/sdtm/2-0"));
        assertEquals("models/sdtm/2-0", WebApiStoreSeeder.modelKeyFor("/mdr/sdtm/2-0/"),
                "a trailing slash must not cost the foundational model");
        assertEquals("models/sdtm/2-0", WebApiStoreSeeder.modelKeyFor("/mdr/sdtm/sdtm-2-0"),
                "the product-prefixed spelling still resolves");

        // Genuinely malformed shapes stay rejected — the tightening's real intent.
        assertNull(WebApiStoreSeeder.modelKeyFor("/mdr/sdtm"), "one segment is not the shape");
        assertNull(WebApiStoreSeeder.modelKeyFor("/mdr/sdtm/2-0/extra"), "three segments are not");
        assertNull(WebApiStoreSeeder.modelKeyFor("/mdr//2-0"), "an empty first segment is not");
        assertNull(WebApiStoreSeeder.modelKeyFor("/other/sdtm/2-0"), "a foreign prefix is not");
    }


    @Test
    void walksAllFiveFamilies() throws IOException
    {
        StoreSeedReport report = seeder().seed(StoreSeedOptions.of(target));

        assertEquals(5, report.productsSeeded(), "3 IGs + 2 distinct linked models");
        assertEquals(3, report.ctPackagesFetched());
        assertEquals(List.of(), report.warnings());
        try (MetadataStore store = MetadataStore.open(target))
        {
            assertEquals(List.of(SeedFixtures.ADAM_KEY, SeedFixtures.IG_KEY, SeedFixtures.TIG_KEY),
                    store.productCatalogue(),
                    "only Implementation-Guide-typed entries enter the catalogue");
            assertTrue(store.product(SeedFixtures.ADAM_MODEL_KEY).isPresent(),
                    "/mdr/adam/adam-2-1 must land under models/adam/2-1");
            assertTrue(store.product(SeedFixtures.TIG_KEY).isPresent(),
                    "the TIG substandard is fetched bare and keyed under standards/tig/…");
            assertEquals(true, store.ctPackage(SeedFixtures.PKG_2).orElseThrow().codelists().get(1)
                    .extensible(), "the API's string \"true\" must land as a real Boolean");
            assertEquals(WebApiStoreSeeder.PROVENANCE_SOURCE,
                    store.manifest().provenance().get(0).source());
        }
    }


    @Test
    void aPublishedButUndeliverablePackageIsEnumeratedAndAbsent() throws IOException
    {
        writeIndex(SeedFixtures.PKG_1, SeedFixtures.PKG_2, SeedFixtures.PKG_QS,
                "sendct-2024-03-29");

        StoreSeedReport report = seeder().seed(StoreSeedOptions.of(target));

        assertEquals(List.of("sendct-2024-03-29"), report.ctPackagesMissed());
        assertEquals(3, report.ctPackagesFetched());
        try (MetadataStore store = MetadataStore.open(target))
        {
            assertTrue(store.publishedCtPackages().contains("sendct-2024-03-29"),
                    "published is first-class data, independent of what could be loaded");
            assertEquals(Presence.ABSENT, store.presence("sendct-2024-03-29"));
        }
    }


    @Test
    void aMalformedPackageLinkIsWarnedAndExcluded() throws IOException
    {
        writeIndex(SeedFixtures.PKG_1, SeedFixtures.PKG_2, SeedFixtures.PKG_QS, "not-a-package");

        StoreSeedReport report = seeder().seed(StoreSeedOptions.of(target));

        assertTrue(report.warnings().stream().anyMatch(w -> w.contains("not-a-package")),
                "the malformed link must be warned about: " + report.warnings());
        try (MetadataStore store = MetadataStore.open(target))
        {
            assertFalse(store.publishedCtPackages().contains("not-a-package"));
        }
    }


    @Test
    void anUnreachableEnumerationFailsTheSeedLoudly() throws IOException
    {
        Path emptyCache = Files.createDirectories(temp.resolve("empty-cache"));
        WebApiStoreSeeder seeder = new WebApiStoreSeeder(SeedFixtures.offlineAccess(emptyCache));

        assertThrows(IOException.class, () -> seeder.seed(StoreSeedOptions.of(target)),
                "a dead or keyless API must not produce a hollow store");
        assertFalse(Files.exists(target));
    }


    /**
     * ⭐ The incremental-acquisition proof on the API side: after the first seed, every recorded CT
     * package response is DELETED. A default re-seed still succeeds — presence in the existing
     * store replaces the ~206 package calls — while the products and enumerations are re-fetched as
     * always.
     */
    @Test
    void reseedCarriesPresentPackagesWithoutTouchingTheApi() throws IOException
    {
        seeder().seed(StoreSeedOptions.of(target));
        // The prefix is asked of the encoder, not spelled out. It used to read
        // "api_mdr_ct_packages_", which matched nothing once Q14 re-encoded cache file names —
        // so the deletion below removed NOTHING and every assertion still passed, because the
        // packages are carried from the store rather than the cache. The count assertion is what
        // makes that impossible to repeat.
        String packagePrefix = ApiCache.encodeKeyForFileName("/api/mdr/ct/packages/");
        List<Path> recorded;
        try (Stream<Path> entries = Files.list(cacheDir))
        {
            recorded = entries.filter(p -> p.getFileName().toString().startsWith(packagePrefix))
                    .toList();
        }
        assertEquals(3,
                recorded.stream().filter(p -> p.getFileName().toString().endsWith(".json.gz"))
                        .count(),
                "the three recorded CT package responses must be the ones being deleted");
        for (Path entry : recorded)
        {
            Files.delete(entry);
        }

        StoreSeedReport report = seeder().seed(StoreSeedOptions.of(target));

        assertEquals(3, report.ctPackagesCarried());
        assertEquals(0, report.ctPackagesFetched());
        assertEquals(List.of(), report.warnings(),
                "no package request may have been issued: " + report.warnings());
        try (MetadataStore store = MetadataStore.open(target))
        {
            assertEquals(2, store.ctPackage(SeedFixtures.PKG_1).orElseThrow().codelists().size());
        }
    }


    /** Rewrites the recorded {@code /mdr/ct/packages} enumeration. */
    private void writeIndex(String... aIds) throws IOException
    {
        List<Map<String, Object>> links = Stream.of(aIds).map(id -> Map.<String, Object> of("href",
                "/mdr/ct/packages/" + id, "title", id, "type", "Terminology")).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("_links", Map.of("packages", links));
        new GzipFileApiCache(cacheDir.toAbsolutePath(), ".json").write("/api/mdr/ct/packages",
                MAPPER.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
    }
}
