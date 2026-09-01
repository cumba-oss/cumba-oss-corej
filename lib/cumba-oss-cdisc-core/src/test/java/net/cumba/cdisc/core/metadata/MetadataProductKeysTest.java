package net.cumba.cdisc.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.cdisc.core.metadata.MetadataProductKeys.SdtmLoader;
import org.junit.jupiter.api.Test;

/**
 * Phase 7 of {@code plans/PLAN-metadata-product-selection.md} — the shared key-family classifier.
 * Every shape asserted here is a <b>real cache key</b> (the pickle's {@code standards_details.pkl}
 * holds exactly the three shapes; §2.5 of the plan).
 */
class MetadataProductKeysTest
{

    @Test
    void adamProductIdOfAcceptsOnlyPlainAdamKeys()
    {
        assertEquals("adamig-1-3",
                MetadataProductKeys.adamProductIdOf("standards/adam/adamig-1-3"));
        assertEquals("adam-occds-1-1",
                MetadataProductKeys.adamProductIdOf("standards/adam/adam-occds-1-1"));
        assertNull(MetadataProductKeys.adamProductIdOf("standards/sdtmig/3-4"));
        assertNull(MetadataProductKeys.adamProductIdOf("standards/tig/1-0/adam"),
                "the TIG ADaM leg has no /mdr/adam id — it is ADaM-family but not id-addressable");
        assertNull(MetadataProductKeys.adamProductIdOf("standards/adam/"));
        assertNull(MetadataProductKeys.adamProductIdOf("standards/adam/x/y"));
    }


    @Test
    void tigLegClassification()
    {
        assertEquals("adam", MetadataProductKeys.tigLegOf("standards/tig/1-0/adam"));
        assertEquals("sdtm", MetadataProductKeys.tigLegOf("standards/tig/1-0/sdtm"));
        assertNull(MetadataProductKeys.tigLegOf("standards/adam/adamig-1-3"));
        assertNull(MetadataProductKeys.tigLegOf("standards/tig/1-0"));
        assertTrue(MetadataProductKeys.isTigAdamLeg("standards/tig/1-0/adam"));
        assertFalse(MetadataProductKeys.isTigAdamLeg("standards/tig/1-0/sdtm"));
    }


    @Test
    void isAdamFamilyCoversThePlainKeyAndTheTigLeg()
    {
        // §7-2 — the TIG ADaM leg counting as ADaM-family is what lets a declared tig/<v>/adam
        // enter the ordered product list at all.
        assertTrue(MetadataProductKeys.isAdamFamily("standards/adam/adamig-1-3"));
        assertTrue(MetadataProductKeys.isAdamFamily("standards/tig/1-0/adam"));
        assertFalse(MetadataProductKeys.isAdamFamily("standards/tig/1-0/sdtm"));
        assertFalse(MetadataProductKeys.isAdamFamily("standards/sdtmig/3-4"));
        assertFalse(MetadataProductKeys.isAdamFamily("standards/sendig/dart-1-1"));
    }


    @Test
    void sdtmLoaderOfCoversAllThreeSdtmFamilyShapes()
    {
        assertEquals(new SdtmLoader("sdtmig", "3-4"),
                MetadataProductKeys.sdtmLoaderOf("standards/sdtmig/3-4"));
        // The version segment stays unparsed — supplement/vertical versions are real keys.
        assertEquals(new SdtmLoader("sendig", "dart-1-1"),
                MetadataProductKeys.sdtmLoaderOf("standards/sendig/dart-1-1"));
        // The TIG SDTM leg reassembles through the compound version, verbatim key form.
        assertEquals(new SdtmLoader("tig", "1-0/sdtm"),
                MetadataProductKeys.sdtmLoaderOf("standards/tig/1-0/sdtm"));
        assertNull(MetadataProductKeys.sdtmLoaderOf("standards/tig/1-0/adam"));
        assertNull(MetadataProductKeys.sdtmLoaderOf("standards/adam/adamig-1-3"));
        // "sdtm" is the MODEL family, not an IG — deliberately not SDTM-family here, so a
        // -s sdtm run keeps its -s/-v pair (behaviour-preserving fallback).
        assertNull(MetadataProductKeys.sdtmLoaderOf("standards/sdtm/2-0"));
    }


    @Test
    void firstOfFamilySelectionIsOrderSensitive()
    {
        List<String> keys = List.of("standards/adam/adam-occds-1-1", "standards/sendig/3-1-1",
                "standards/sdtmig/3-4", "standards/tig/1-0/adam");
        assertEquals(new SdtmLoader("sendig", "3-1-1"), MetadataProductKeys.firstSdtmLoader(keys));
        assertEquals("standards/adam/adam-occds-1-1", MetadataProductKeys.firstAdamFamilyKey(keys));
        assertNull(MetadataProductKeys.firstSdtmLoader(List.of("standards/adam/adamig-1-3")));
        assertNull(MetadataProductKeys.firstAdamFamilyKey(List.of("standards/sdtmig/3-4")));
    }


    @Test
    void keysAreCanonicalisedBeforeClassification()
    {
        assertEquals("adamig-1-3",
                MetadataProductKeys.adamProductIdOf(" Standards/Adam/ADAMIG-1-3 "));
        assertEquals("standards/tig/1-0/adam",
                MetadataProductKeys.firstAdamFamilyKey(List.of(" STANDARDS/TIG/1-0/ADAM ")));
    }


    @Test
    void isTigAcceptsBothKeyAndBareTokenForms()
    {
        // Used by the resolver's failure wording — a NotFound token may be bare or full-form.
        assertTrue(MetadataProductKeys.isTig("standards/tig/1-0/adam"));
        assertTrue(MetadataProductKeys.isTig("tig/1-0/adam"));
        assertFalse(MetadataProductKeys.isTig("standards/sdtmig/3-4"));
        assertFalse(MetadataProductKeys.isTig("adamig-1-3"));
    }
}
