package net.cumba.corej.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import net.cumba.corej.core.metadata.store.MetadataStoreWriter;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cache P4 — the product catalogue is the unified metadata store's declarable catalogue and nothing
 * else. These tests pin the three dispositions (configured, unconfigured, unreadable) and the
 * deliberate <b>absence of memoisation</b>: the pre-P4 catalogue was memoised process-wide with no
 * invalidation (plan §8), so a re-seeded store was invisible until a JVM restart. A test that
 * re-seeds between two {@code configured()} calls and sees the new content is the guard against
 * that wart returning.
 */
class MetadataProductCatalogueTest
{

    @AfterEach
    void clearStoreProperty()
    {
        System.clearProperty(StoreMetadataProviderFactory.STORE_PROPERTY);
    }


    private static Path writeStore(Path aTarget, List<String> aCatalogue) throws IOException
    {
        new MetadataStoreWriter().productCatalogue(aCatalogue).publishedCtPackages(List.of())
                .write(aTarget);
        return aTarget;
    }


    @Test
    void theConfiguredCatalogueReadsTheStore(@TempDir Path aTemp) throws IOException
    {
        Path store = writeStore(aTemp.resolve("store.zip"),
                List.of("standards/sdtmig/3-4", "standards/adam/adamig-1-3"));
        System.setProperty(StoreMetadataProviderFactory.STORE_PROPERTY, store.toString());

        MetadataProductCatalogue c = MetadataProductCatalogue.configured();

        assertEquals(Set.of("standards/sdtmig/3-4", "standards/adam/adamig-1-3"), c.keys());
        assertEquals(1, c.sources().size());
        assertTrue(c.sources().get(0).contains("metadata store"), () -> c.sources().toString());
    }


    @Test
    void aReseededStoreIsPickedUpWithoutARestart(@TempDir Path aTemp) throws IOException
    {
        // ⛔ Plan §8: the pre-P4 catalogue memoised per resolved configuration, so a store
        // re-seeded after first resolution was not re-read until JVM restart. P4 dropped the
        // memoisation with the union; this is the pin that keeps it dropped.
        Path store = writeStore(aTemp.resolve("store.zip"), List.of("standards/sdtmig/3-4"));
        System.setProperty(StoreMetadataProviderFactory.STORE_PROPERTY, store.toString());
        assertEquals(Set.of("standards/sdtmig/3-4"), MetadataProductCatalogue.configured().keys());

        writeStore(store, List.of("standards/sdtmig/3-4", "standards/tig/1-0/adam"));

        assertEquals(Set.of("standards/sdtmig/3-4", "standards/tig/1-0/adam"),
                MetadataProductCatalogue.configured().keys(),
                "a re-seeded store must be re-read on the next resolution");
    }


    @Test
    void noConfiguredStoreYieldsAnEmptyCatalogue()
    {
        // STORE_PROPERTY cleared by the fixture; CDISC_METADATA_STORE is not set in test runs.
        MetadataProductCatalogue c = MetadataProductCatalogue.configured();
        assertEquals(Set.of(), c.keys());
        assertEquals(List.of(), c.sources());
    }


    @Test
    void anUnreadableStoreContributesNothing(@TempDir Path aTemp) throws IOException
    {
        Path garbage = aTemp.resolve("store.zip");
        Files.writeString(garbage, "this is not a zip");
        System.setProperty(StoreMetadataProviderFactory.STORE_PROPERTY, garbage.toString());

        MetadataProductCatalogue c = MetadataProductCatalogue.configured();

        assertEquals(Set.of(), c.keys(), "an unreadable store must degrade to an empty catalogue");
        assertEquals(List.of(), c.sources());
    }


    @Test
    void ofExposesKeysAndSources()
    {
        MetadataProductCatalogue c = MetadataProductCatalogue.of(Set.of("standards/sdtmig/3-4"),
                List.of("metadata store /x"));
        assertEquals(Set.of("standards/sdtmig/3-4"), c.keys());
        assertEquals(List.of("metadata store /x"), c.sources());
    }


    @Test
    void theDeprecatedTwoArgOverloadIgnoresItsArguments(@TempDir Path aTemp) throws IOException
    {
        // The pre-P4 pickle/API overrides must not resurrect a second source: whatever a legacy
        // caller passes, only the configured store answers.
        Path store = writeStore(aTemp.resolve("store.zip"), List.of("standards/sendig/3-1-1"));
        System.setProperty(StoreMetadataProviderFactory.STORE_PROPERTY, store.toString());

        @SuppressWarnings("removal")
        MetadataProductCatalogue c = MetadataProductCatalogue.configured("/some/pickle/dir",
                "/some/api/cache");

        assertEquals(Set.of("standards/sendig/3-1-1"), c.keys());
    }
}
