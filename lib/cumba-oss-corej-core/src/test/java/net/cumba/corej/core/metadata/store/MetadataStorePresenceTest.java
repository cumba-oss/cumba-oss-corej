package net.cumba.corej.core.metadata.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link MetadataStore#presence(String)} is TOTAL — present, absent and malformed are three
 * different answers because a later phase aborts differently on each — and a malformed id is
 * rejected before lookup, never conflated with an absent package.
 */
class MetadataStorePresenceTest
{

    @TempDir
    static Path tempDir;

    private static MetadataStore store;

    @BeforeAll
    static void openStore() throws IOException
    {
        Path file = tempDir.resolve("metadata-cache.zip");
        MetadataStoreFixtures.populatedWriter().write(file);
        store = MetadataStore.open(file);
    }


    @Test
    void heldPackagesArePresent()
    {
        assertEquals(Presence.PRESENT, store.presence(MetadataStoreFixtures.PKG_SDTM_1));
        assertEquals(Presence.PRESENT, store.presence(MetadataStoreFixtures.PKG_SDTM_2));
        assertEquals(Presence.PRESENT, store.presence(MetadataStoreFixtures.PKG_QSFT));
    }


    @Test
    void wellFormedButUnheldIsAbsent()
    {
        // Published upstream, not seeded — and a plausible id nobody ever published.
        assertEquals(Presence.ABSENT, store.presence(MetadataStoreFixtures.PKG_PUBLISHED_ONLY));
        assertEquals(Presence.ABSENT, store.presence("adamct-2099-01-01"));
        assertEquals(Presence.ABSENT, store.presence("define-xmlct-2024-03-29"));
    }


    @ParameterizedTest
    @ValueSource(strings =
    {
            "", " ", "sdtmct", "sdtmct-2023", "sdtmct-2023-6-30", "SDTMCT-2023-06-30",
            "sdtm-2023-06-30", "sdtmct-2023-06-30 ", " sdtmct-2023-06-30", "sdtmct-2023-06-30/x",
            "../sdtmct-2023-06-30", "ct-2023-06-30", "sdtmct_2023_06_30"
    })
    void malformedIdsAreMalformedNotAbsent(String aId)
    {
        assertEquals(Presence.MALFORMED, store.presence(aId), aId);
    }


    @Test
    void nullIsMalformed()
    {
        assertEquals(Presence.MALFORMED, store.presence(null));
    }


    @Test
    void ctPackageRejectsMalformedIdBeforeLookup()
    {
        assertThrows(IllegalArgumentException.class, () -> store.ctPackage("SDTMCT-2023-06-30"));
        assertThrows(IllegalArgumentException.class, () -> store.ctPackage(null));
    }
}
