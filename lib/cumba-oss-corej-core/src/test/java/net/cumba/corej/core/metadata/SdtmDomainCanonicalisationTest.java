package net.cumba.corej.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.cumba.corej.core.metadata.MetadataLibraryProvider.SdtmDomainCanonicalisation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * F-corej-L2-06 — the table-driven pin of the (now single) SDTM domain-canonicalisation ladder.
 * Three call sites used to carry byte-identical copies of this ladder held in agreement only by a
 * comment; they now all call {@link MetadataLibraryProvider#canonicaliseSdtmDomain}, and this test
 * pins the ladder's behaviour over the fixed domain corpus the finding named, so any future edit to
 * the shared copy is a deliberate, visible change.
 */
class SdtmDomainCanonicalisationTest
{

    @ParameterizedTest(name = "{0} -> effective={1} wildcard={2} addAP={3}")
    @CsvSource(
    {
            // plain domains are untouched
            "DM,      DM,       DM,      false", "AE,      AE,       AE,      false",
            "XX,      XX,       XX,      false", "ZZTEST,  ZZTEST,   ZZTEST,  false",
            // SUPP--/SQ-- route to SUPPQUAL; the wildcard prefix stays the original name
            "SUPPAE,  SUPPQUAL, SUPPAE,  false", "SQAPDM,  SUPPQUAL, SQAPDM,  true",
            // AP-- strips to the parent domain and takes the AP additions
            "APDM,    DM,       DM,      true",
            // two-letter names sit BELOW the `length() > 2` boundary and are untouched --
            // the boundary the surviving `> 2` -> `>= 2` mutants flip on both build* copies
            "AP,      AP,       AP,      false", "SQ,      SQ,       SQ,      false",
            // case-insensitive detection, case-preserving output
            "suppae,  SUPPQUAL, suppae,  false", "apdm,    dm,       dm,      true"
    })
    void ladder(String original, String effective, String wildcard, boolean addAP)
    {
        SdtmDomainCanonicalisation c = MetadataLibraryProvider.canonicaliseSdtmDomain(original);
        assertEquals(effective, c.effectiveDomain(), "effectiveDomain(" + original + ")");
        assertEquals(wildcard, c.wildcardDomain(), "wildcardDomain(" + original + ")");
        assertEquals(addAP, c.addAP(), "addAP(" + original + ")");
    }
}
