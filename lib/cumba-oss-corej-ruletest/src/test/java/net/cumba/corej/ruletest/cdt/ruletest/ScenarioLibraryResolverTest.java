package net.cumba.corej.ruletest.cdt.ruletest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.metadata.store.MetadataStoreWriter;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.cumba.corej.core.metadata.store.StoredDataStructure;
import net.cumba.corej.core.metadata.store.StoredProduct;
import net.cumba.corej.core.metadata.store.StoredVariable;
import net.cumba.corej.core.metadata.store.StoredVariableSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the {@link ScenarioLibraryResolver} availability gate — since cache P4 that gate is the
 * unified metadata store ({@code CDISC_METADATA_STORE} / {@code cdisc.metadata.store}), not a CDISC
 * Library API key. Hermetic in both directions: the "configured" path runs against a synthetic
 * fixture store instead of self-skipping on credential-less CI.
 */
class ScenarioLibraryResolverTest
{

    @AfterEach
    void clearConfiguredStore()
    {
        System.clearProperty(StoreMetadataProviderFactory.STORE_PROPERTY);
    }


    private static LibraryRef adamRef()
    {
        return LibraryRef.builder().standard("adamig").version("1-3").build();
    }


    @Test
    void resolve_noStoreConfigured_returnsEmpty()
    {
        Assumptions.assumeTrue(System.getenv(StoreMetadataProviderFactory.STORE_ENV) == null,
                "a metadata store is configured in this environment");

        assertTrue(ScenarioLibraryResolver.resolve(adamRef()).isEmpty(),
                "no store configured => resolver must signal unavailable (scenario skips)");
    }


    @Test
    void resolve_storeConfigured_returnsProvider(@TempDir Path aTemp) throws IOException
    {
        Path store = aTemp.resolve("store.zip");
        StoredProduct adam = StoredProduct.builder().key("standards/adam/adamig-1-3").version("1-3")
                .dataStructures(
                        List.of(new StoredDataStructure("ADSL", null, "1",
                                "SUBJECT LEVEL ANALYSIS DATASET", null,
                                List.of(new StoredVariableSet("Identifier", null, "1",
                                        List.of(StoredVariable.builder().name("USUBJID")
                                                .ordinal("1").core("Req").build()),
                                        null)),
                                null)))
                .build();
        new MetadataStoreWriter().addProduct(adam).publishedCtPackages(List.of())
                .productCatalogue(List.of("standards/adam/adamig-1-3")).write(store);
        System.setProperty(StoreMetadataProviderFactory.STORE_PROPERTY, store.toString());

        Optional<MetadataProvider> provider = ScenarioLibraryResolver.resolve(adamRef());

        assertTrue(provider.isPresent(),
                "store configured and product held => resolver must return a provider");
        assertTrue(provider.orElseThrow().supportsStructureKeyedVariables(),
                "the ADaM ref must resolve to the structure-keyed ADaM provider");
    }


    @Test
    void resolve_storeLacksTheProduct_returnsEmpty(@TempDir Path aTemp) throws IOException
    {
        Path store = aTemp.resolve("store.zip");
        new MetadataStoreWriter().publishedCtPackages(List.of()).productCatalogue(List.of())
                .write(store);
        System.setProperty(StoreMetadataProviderFactory.STORE_PROPERTY, store.toString());

        assertTrue(ScenarioLibraryResolver.resolve(adamRef()).isEmpty(),
                "a store without the referenced product => unavailable (scenario skips)");
    }


    /**
     * T1-10 (a) / S17, PLAN-define-ct-evaluation: an OLD-FORMAT store must NOT degrade to "empty" —
     * {@code ScenarioLibrarySupport.resolveOrSkip} turns empty into a skipped scenario, so under a
     * stale store every {@code #library-ref} scenario would skip and the corpus gate stay green.
     * The resolver throws instead, naming the store and the re-seed. A store that is not a store at
     * all keeps the no-throw contract (below): that is a per-machine accident, not a format bump.
     */
    @Test
    void resolve_oldFormatStore_throwsInsteadOfSkipping(@TempDir Path aTemp) throws IOException
    {
        Path store = aTemp.resolve("store.zip");
        new MetadataStoreWriter().publishedCtPackages(List.of()).productCatalogue(List.of())
                .write(store);
        // The relabel trick MetadataStoreCorruptionTest uses: the current writer's store with its
        // manifest's formatVersion rewritten to 1, which no reader has known since 2026-09-08.
        java.util.Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(store.toFile()))
        {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> names = zip.entries();
            while (names.hasMoreElements())
            {
                java.util.zip.ZipEntry entry = names.nextElement();
                entries.put(entry.getName(), zip.getInputStream(entry).readAllBytes());
            }
        }
        String manifest = new String(entries.get("manifest.json"),
                java.nio.charset.StandardCharsets.UTF_8);
        String relabelled = manifest.replaceFirst("\"formatVersion\" : \\d+",
                "\"formatVersion\" : 1");
        assertTrue(!relabelled.equals(manifest), "the relabel must have changed the manifest");
        entries.put("manifest.json", relabelled.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path old = aTemp.resolve("format-1.zip");
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(
                Files.newOutputStream(old)))
        {
            for (java.util.Map.Entry<String, byte[]> entry : entries.entrySet())
            {
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        System.setProperty(StoreMetadataProviderFactory.STORE_PROPERTY, old.toString());

        RuntimeException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class, () -> ScenarioLibraryResolver.resolve(adamRef()),
                "an old-format store must FAIL the resolver, never return empty (= a skipped"
                        + " scenario and a green gate over a stale store)");
        assertTrue(thrown.getMessage().contains(old.toString()), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("re-seed"), thrown.getMessage());
    }


    @Test
    void resolve_unreadableStore_returnsEmptyInsteadOfThrowing(@TempDir Path aTemp)
        throws IOException
    {
        Path bogus = aTemp.resolve("store.zip");
        Files.writeString(bogus, "not a store");
        System.setProperty(StoreMetadataProviderFactory.STORE_PROPERTY, bogus.toString());

        assertTrue(ScenarioLibraryResolver.resolve(adamRef()).isEmpty(),
                "an unreadable store must degrade to unavailable, preserving no-throw");
    }
}
