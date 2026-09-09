package net.cumba.corej.core.metadata.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Write → read → every field comes back. Products round-trip exactly (order preserved); CT packages
 * round-trip up to term order within a codelist, which the format deliberately does not preserve
 * (see {@link StoredCodelist}) — both sides are canonicalised with the test-local order.
 */
class MetadataStoreRoundTripTest
{

    @TempDir
    Path tempDir;

    private MetadataStore write() throws IOException
    {
        Path file = tempDir.resolve("metadata-cache.zip");
        MetadataStoreFixtures.populatedWriter().write(file);
        return MetadataStore.open(file);
    }


    @Test
    void productsRoundTripFieldForField() throws IOException
    {
        try (MetadataStore store = write())
        {
            assertEquals(Optional.of(MetadataStoreFixtures.igProduct()),
                    store.product(MetadataStoreFixtures.IG_KEY));
            assertEquals(Optional.of(MetadataStoreFixtures.modelProduct()),
                    store.product(MetadataStoreFixtures.MODEL_KEY));
            assertEquals(Optional.of(MetadataStoreFixtures.adamProduct()),
                    store.product(MetadataStoreFixtures.ADAM_KEY));
            assertEquals(Optional.empty(), store.product("standards/sdtmig/9-9"));
            assertEquals(Optional.empty(), store.product(null));
        }
    }


    @Test
    void ctPackagesRoundTripFieldForField() throws IOException
    {
        try (MetadataStore store = write())
        {
            for (StoredCtPackage expected : List.of(MetadataStoreFixtures.sdtmPackage1(),
                    MetadataStoreFixtures.sdtmPackage2(), MetadataStoreFixtures.qsftPackage()))
            {
                Optional<StoredCtPackage> actual = store.ctPackage(expected.id());
                assertTrue(actual.isPresent(), expected.id());
                assertEquals(MetadataStoreFixtures.canonical(expected),
                        MetadataStoreFixtures.canonical(actual.get()), expected.id());
            }
        }
    }


    @Test
    void nullVersusEmptyCollectionsSurvive() throws IOException
    {
        try (MetadataStore store = write())
        {
            StoredCtPackage qs = store.ctPackage(MetadataStoreFixtures.PKG_QSFT).orElseThrow();
            StoredCodelist codelist = qs.codelists().get(0);
            assertEquals(List.of(), codelist.synonyms(), "empty synonyms must stay empty");
            assertEquals(null, codelist.extensible(), "unpublished extensible must stay null");
            StoredTerm unknown = codelist.terms().stream()
                    .filter(t -> "U".equals(t.submissionValue())).findFirst().orElseThrow();
            assertEquals(null, unknown.synonyms(), "null synonyms must stay null");
            StoredCtPackage sdtm = store.ctPackage(MetadataStoreFixtures.PKG_SDTM_1).orElseThrow();
            StoredTerm no = sdtm.codelists().get(0).terms().stream()
                    .filter(t -> "N".equals(t.submissionValue())).findFirst().orElseThrow();
            assertEquals(List.of(), no.synonyms(), "empty synonyms must stay empty");
            assertEquals(null, no.definition(), "unpublished definition must stay null");
        }
    }


    @Test
    void publishedEnumerationIsFirstClassNotDerived() throws IOException
    {
        try (MetadataStore store = write())
        {
            assertEquals(MetadataStoreFixtures.publishedCtPackages(), store.publishedCtPackages());
            // The proof: an id the store does NOT hold is still enumerated…
            assertTrue(
                    store.publishedCtPackages().contains(MetadataStoreFixtures.PKG_PUBLISHED_ONLY));
            assertEquals(Presence.ABSENT, store.presence(MetadataStoreFixtures.PKG_PUBLISHED_ONLY));
        }
    }


    @Test
    void manifestRoundTrips() throws IOException
    {
        try (MetadataStore store = write())
        {
            StoreManifest manifest = store.manifest();
            // ⚠ A literal on purpose: reading StoreFormat.FORMAT_VERSION here would make an
            // accidental bump invisible. 1 → 2 on 2026-09-08 (StoredVariable.examples became a
            // scalar; see that constant's javadoc).
            assertEquals(2, manifest.formatVersion());
            assertEquals(
                    List.of(new StoreProvenance("pickle", "v0.17.1", "2026-09-08T00:00:00Z"),
                            new StoreProvenance("web-api", "https://library.cdisc.org", "")),
                    manifest.provenance());
            assertEquals(MetadataStoreFixtures.productCatalogue(), store.productCatalogue());
            assertEquals(7, manifest.partHashes().size(), "4 CT parts + 3 products, each hashed");
        }
    }


    @Test
    void termTableIsSharedAcrossPackages() throws IOException
    {
        try (MetadataStore store = write())
        {
            // The identical NY codelist in both sdtm packages must materialise ONCE.
            StoredCodelist ny1 = store.ctPackage(MetadataStoreFixtures.PKG_SDTM_1).orElseThrow()
                    .codelists().stream().filter(c -> "NY".equals(c.submissionValue())).findFirst()
                    .orElseThrow();
            StoredCodelist ny2 = store.ctPackage(MetadataStoreFixtures.PKG_SDTM_2).orElseThrow()
                    .codelists().stream().filter(c -> "NY".equals(c.submissionValue())).findFirst()
                    .orElseThrow();
            assertTrue(ny1 == ny2, "identical codelist versions must share one instance");
        }
    }


    @Test
    void emptyStoreRoundTrips() throws IOException
    {
        Path file = tempDir.resolve("empty.zip");
        new MetadataStoreWriter().publishedCtPackages(List.of()).write(file);
        try (MetadataStore store = MetadataStore.open(file))
        {
            assertEquals(List.of(), store.publishedCtPackages());
            assertEquals(List.of(), store.productCatalogue());
            assertEquals(Optional.empty(), store.product("standards/sdtmig/3-4"));
            assertEquals(Presence.ABSENT, store.presence("sdtmct-2023-06-30"));
            assertEquals(4, store.manifest().partHashes().size(), "the 4 fixed CT parts");
        }
    }
}
