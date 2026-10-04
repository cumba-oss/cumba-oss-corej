package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-occds-source-domain-by-ig} D1–D5: an OCCDS dataset's source domains are the
 * two-letter prefixes of the occurrence variables it carries — its {@code --SEQ} or its topic
 * variable ({@code --TERM}, {@code --TRT}; a {@code --DECOD} alone is no evidence, owner ruling
 * MED-1 (a)) — as ADaMIG-OCCDS §3 says, never its name; and a carried-prefix name is dropped only
 * on evidence of a different domain.
 */
class AdamSourceDomainTest
{

    private static IDataTable table(String name, String... columns)
    {
        MockTable b = MockTable.of();
        for (String column : columns)
        {
            b.col(column, "x");
        }
        return b.name(name).build();
    }


    @Test
    void oneDomainFromAnyOfItsOccurrenceVariables()
    {
        assertEquals(Set.of("AE"), AdamSourceDomain.domainsOf(table("ADAE", "STUDYID", "AESEQ")),
                "--SEQ alone");
        assertEquals(Set.of("AE"),
                AdamSourceDomain.domainsOf(table("ADAE", "AETERM", "AEDECOD", "AESER")),
                "--TERM without --SEQ (D2: AESEQ is then demanded)");
        assertEquals(Set.of("CM"), AdamSourceDomain.domainsOf(table("ADCM", "CMTRT")), "--TRT");
        assertEquals("AE", AdamSourceDomain.singleDomainOf(Set.of("AE")));
    }


    /**
     * Owner ruling MED-1 (a), 2026-10-04: a domain is evidenced only by its {@code --SEQ} or its
     * topic variable; a {@code --DECOD} column alone adds no domain. ADSL's {@code DCDECOD} /
     * {@code DSDECOD}, routinely copied onto an occurrence dataset, read as a second domain before
     * and disarmed every {@code --} requirement (D3).
     */
    @Test
    void aDecodColumnAloneIsNoEvidence()
    {
        assertEquals(Set.of(), AdamSourceDomain.domainsOf(table("ADAE", "STUDYID", "DCDECOD")),
                "ADSL's DCDECOD gives no domain");
        assertEquals(Set.of(), AdamSourceDomain.domainsOf(table("ADAE", "AEDECOD", "AESER")),
                "AEDECOD alone gives no domain either");
        assertEquals(Set.of(), AdamSourceDomain.domainsOf(table("ADCM", "cmdecod")),
                "letter case does not make it evidence");
        assertEquals(Set.of("AE"),
                AdamSourceDomain.domainsOf(
                        table("ADAE", "AETERM", "AEDECOD", "AESER", "DCDECOD", "DSDECOD")),
                "copied ADSL DECOD columns do not make an AE dataset multi-domain");
        assertEquals(Set.of("AE"), AdamSourceDomain.domainsOf(table("ADAE", "AESEQ", "AEDECOD")),
                "--SEQ evidences AE; AEDECOD beside it adds nothing either way");
        assertEquals(Set.of("AE", "EX"),
                AdamSourceDomain.domainsOf(table("ADAE", "AETERM", "EXTRT")),
                "a --TRT topic variable is still evidence (the ruling names --TRT)");
    }


    @Test
    void theDatasetNameAndAKeptDomainCellAreNotEvidence()
    {
        assertEquals(Set.of("AE"), AdamSourceDomain.domainsOf(table("ADEVENT", "AESEQ", "AETERM")),
                "ADEVENT is not AD<dd>-named for AE, and need not be");
        assertEquals(Set.of(), AdamSourceDomain.domainsOf(table("ADAE", "STUDYID", "DOMAIN")),
                "an ADAE name with no occurrence variable gives no domain (D4)");
    }


    @Test
    void severalDomainsResolveNoTemplate()
    {
        Set<String> domains = AdamSourceDomain.domainsOf(table("ADAE", "AESEQ", "MHSEQ", "MHTERM"));
        assertEquals(List.of("AE", "MH"), List.copyOf(domains), "sorted");
        assertNull(AdamSourceDomain.singleDomainOf(domains), "D3: no -- name is demanded");
        assertNull(AdamSourceDomain.singleDomainOf(Set.of()), "D4");
    }


    @Test
    void theEvidenceIsTheExactTwoLetterShapeLetterCaseIgnored()
    {
        assertEquals(Set.of("AE"), AdamSourceDomain.domainsOf(table("adae", "aeseq", "aeTerm")),
                "the owner's case-insensitive names ruling; the prefix is upper-cased");
        assertEquals(Set.of(),
                AdamSourceDomain.domainsOf(table("ADAE", "ASEQ", "SRCSEQ", "RECSEQ", "SRCDOM")),
                "ADaM's own sequence variables are not a domain's (review M1)");
        assertEquals(Set.of(),
                AdamSourceDomain.domainsOf(
                        table("ADAE", "AESTDTC", "RFSTDTC", "AEBODSYS", "AECAT", "TRTA", "TRTP")),
                "D5: no timing suffix, no BODSYS / CAT, and ADaM's TRTA / TRTP are not --TRT");
        assertEquals(Set.of(), AdamSourceDomain.domainsOf(table("ADAE", "AEXTERM", "XTERM")),
                "a longer or shorter prefix is not a two-letter domain");
    }


    @Test
    void aCarriedNameIsDroppedOnlyOnEvidenceOfADifferentDomain()
    {
        assertTrue(AdamSourceDomain.carriesAnotherDomain(Set.of("AE"), "CM"), "CMTRT on an AE set");
        assertFalse(AdamSourceDomain.carriesAnotherDomain(Set.of("AE"), "AE"));
        assertFalse(AdamSourceDomain.carriesAnotherDomain(Set.of("AE", "CM"), "CM"),
                "D3: each present domain's own names stay demanded");
        assertFalse(AdamSourceDomain.carriesAnotherDomain(Set.of(), "AE"),
                "review H1: no evidence never drops a concrete name");
    }


    @Test
    void theCarriedDomainIsTheOpeningSourceTokenThatPrefixesTheName()
    {
        assertEquals("CM", AdamSourceDomain.carriedDomainOf("CMTRT", "CM.CMTRT"));
        assertEquals("AE", AdamSourceDomain.carriedDomainOf("aeser", " AE.AESER"));
        assertNull(AdamSourceDomain.carriedDomainOf("--SEQ", "XX.--SEQ"), "the placeholder");
        assertNull(AdamSourceDomain.carriedDomainOf("ARM", "DM.ARM"), "a copied value");
        assertNull(AdamSourceDomain.carriedDomainOf("AESEVN", "Code AE.AESEV to numeric"),
                "a later token is a derivation input");
        assertNull(AdamSourceDomain.carriedDomainOf("CMTRT", null));
    }
}
