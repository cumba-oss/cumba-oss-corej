package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.cumba.corej.core.exec.AdamSourceDomain.SourceDomains;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

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


    private static SourceDomains domainsOf(String name, String... columns)
    {
        return AdamSourceDomain.domainsOf(table(name, columns));
    }


    private static Set<String> domains(String name, String... columns)
    {
        return domainsOf(name, columns).domains();
    }


    @Test
    void oneDomainFromAnyOfItsOccurrenceVariables()
    {
        assertEquals(Set.of("AE"), domains("ADAE", "STUDYID", "AESEQ"), "--SEQ alone");
        assertEquals(Set.of("AE"), domains("ADAE", "AETERM", "AEDECOD", "AESER"),
                "--TERM without --SEQ (D2: AESEQ is then demanded)");
        assertEquals(Set.of("CM"), domains("ADCM", "CMTRT"), "--TRT");
        assertEquals("AE", AdamSourceDomain.singleDomainOf(domainsOf("ADAE", "AESEQ")));
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
        assertEquals(Set.of(), domains("ADAE", "STUDYID", "DCDECOD"),
                "a copied ADSL DCDECOD is not a DC domain");
        assertEquals(Set.of(), domains("ADAE", "AEDECOD", "AESER"),
                "AEDECOD without AESEQ / AETERM names nothing (recorded consequence: D4)");
        assertEquals(Set.of(), domains("ADCM", "cmdecod"),
                "letter case ignored, still no evidence");
        assertEquals(Set.of("AE"), domains("ADAE", "AESEQ", "AETERM", "DCDECOD", "DSDECOD"),
                "AE's own occurrence variables name AE; the copied DECODs add nothing");
        assertEquals(Set.of("AE"), domains("ADAE", "AESEQ", "AEDECOD"),
                "AESEQ names AE; the DECOD is moot beside it");
        assertEquals(Set.of("AE", "EX"), domains("ADAE", "AETERM", "EXTRT"),
                "two topic variables, no --SEQ: two domains (the named residual of BD2 (a))");
    }


    @Test
    void theDatasetNameAndAKeptDomainCellAreNotEvidence()
    {
        assertEquals(Set.of("AE"), domains("ADEVENT", "AESEQ", "AETERM"),
                "D1 (§4.2: ADAE is not a required dataset name)");
        assertEquals(Set.of(), domains("ADAE", "STUDYID", "DOMAIN"),
                "a kept SDTM DOMAIN cell is not read");
    }


    /**
     * {@code PLAN-bugfix-engine-define} BD2 (a) (owner 2026-10-15): one {@code --SEQ} prefix
     * outranks copied topic columns; multi-domain needs two {@code --SEQ} prefixes or
     * {@code SRCDOM} (ADaMIG-OCCDS v1.1 §3). The {@code SRCDOM} case is the §6 planted control:
     * without the {@code SRCDOM} clause it would read single-domain AE.
     */
    @Test
    void oneSeqOutranksCopiedTopicColumnsAndSrcdomMakesItMultiDomain()
    {
        SourceDomains copiedExtrt = domainsOf("ADAE", "AETERM", "AESEQ", "EXTRT");
        assertEquals(Set.of("AE"), copiedExtrt.domains(), "the one --SEQ names the one domain");
        assertFalse(copiedExtrt.multi());
        assertEquals("AE", AdamSourceDomain.singleDomainOf(copiedExtrt));

        SourceDomains twoSeq = domainsOf("ADAE", "AESEQ", "CMSEQ");
        assertEquals(Set.of("AE", "CM"), twoSeq.domains());
        assertTrue(twoSeq.multi(), "two --SEQ prefixes: multi-domain (D3)");
        assertNull(AdamSourceDomain.singleDomainOf(twoSeq));

        SourceDomains srcdom = domainsOf("ADAE", "AESEQ", "AETERM", "EXTRT", "SRCDOM");
        assertTrue(srcdom.multi(), "SRCDOM: multi-domain whatever the one --SEQ says");
        assertEquals(Set.of("AE", "EX"), srcdom.domains(), "every evidence prefix is a domain");
        assertNull(AdamSourceDomain.singleDomainOf(srcdom));

        SourceDomains srcdomAlone = domainsOf("ADAE", "AESEQ", "SRCDOM");
        assertTrue(srcdomAlone.multi(), "SRCDOM with one prefix is still multi-domain (MED-1)");
        assertEquals(Set.of("AE"), srcdomAlone.domains());
        assertNull(AdamSourceDomain.singleDomainOf(srcdomAlone));

        // the named residual: no --SEQ, two topic columns — nothing breaks the tie
        SourceDomains residual = domainsOf("ADAE", "AETERM", "EXTRT");
        assertTrue(residual.multi());
        assertNull(AdamSourceDomain.singleDomainOf(residual), "AESEQ is still not demanded here");

        // T16: the same set feeds predicate (ii) — CMSEQ beside a copied AETERM is {CM}
        SourceDomains cm = domainsOf("ADCM", "CMSEQ", "AETERM");
        assertEquals(Set.of("CM"), cm.domains());
        assertTrue(AdamSourceDomain.carriesAnotherDomain(cm, "AE"),
                "a published AESER is dropped: AE is a copied column's domain, not the dataset's");
        assertFalse(AdamSourceDomain.carriesAnotherDomain(cm, "CM"));
    }


    /**
     * {@code PLAN-bugfix-engine-define} BD4 (a) with BD8 (a) (owner 2026-10-16): the evidence
     * suffix names the source domain's observation class — {@code --TRT} Interventions,
     * {@code --TERM} Events, {@code --SEQ} alone no class — and the other class's topic template
     * does not apply.
     */
    @Test
    void theEvidenceSuffixNamesTheClassAndTheClassBoundTemplate()
    {
        SourceDomains cm = domainsOf("ADCM", "CMSEQ", "CMTRT");
        assertEquals(AdamSourceDomain.INTERVENTIONS, AdamSourceDomain.observationClassOf(cm, "CM"));
        assertFalse(AdamSourceDomain.topicTemplateApplies("--TERM", AdamSourceDomain.INTERVENTIONS),
                "ADCM is not asked for CMTERM, which SDTM CM does not define");
        assertTrue(AdamSourceDomain.topicTemplateApplies("--TRT", AdamSourceDomain.INTERVENTIONS));
        assertTrue(AdamSourceDomain.topicTemplateApplies("--SEQ", AdamSourceDomain.INTERVENTIONS));

        SourceDomains ae = domainsOf("ADAE", "AESEQ", "AETERM");
        assertEquals(AdamSourceDomain.EVENTS, AdamSourceDomain.observationClassOf(ae, "AE"));
        assertFalse(AdamSourceDomain.topicTemplateApplies("--TRT", AdamSourceDomain.EVENTS));
        assertTrue(AdamSourceDomain.topicTemplateApplies("--TERM", AdamSourceDomain.EVENTS));

        SourceDomains seqOnly = domainsOf("ADCM", "CMSEQ");
        assertNull(AdamSourceDomain.observationClassOf(seqOnly, "CM"),
                "--SEQ alone names no class: both templates are demanded as before (BD8 (a))");
        assertTrue(AdamSourceDomain.topicTemplateApplies("--TERM", null));
        assertTrue(AdamSourceDomain.topicTemplateApplies("--TRT", null));

        SourceDomains both = domainsOf("ADXX", "XXSEQ", "XXTERM", "XXTRT");
        assertNull(AdamSourceDomain.observationClassOf(both, "XX"),
                "both topic variables: no class (theoretical, kept out of both arms)");
        assertNull(AdamSourceDomain.observationClassOf(ae, "CM"), "a domain not in the set");
    }


    @Test
    void severalDomainsResolveNoTemplate()
    {
        SourceDomains domains = domainsOf("ADAE", "AESEQ", "MHSEQ", "MHTERM");
        assertEquals(List.of("AE", "MH"), List.copyOf(domains.domains()), "sorted");
        assertNull(AdamSourceDomain.singleDomainOf(domains), "D3: no -- name is demanded");
        assertNull(AdamSourceDomain.singleDomainOf(domainsOf("ADAE", "STUDYID")), "D4");
    }


    @Test
    void theEvidenceIsTheExactTwoLetterShapeLetterCaseIgnored()
    {
        assertEquals(Set.of("AE"), domains("adae", "aeseq", "aeTerm"),
                "the owner's case-insensitive names ruling; the prefix is upper-cased");
        assertEquals(Set.of(), domains("ADAE", "ASEQ", "SRCSEQ", "RECSEQ", "SRCDOM"),
                "ADaM's own sequence variables are not a domain's (review M1)");
        assertEquals(Set.of(),
                domains("ADAE", "AESTDTC", "RFSTDTC", "AEBODSYS", "AECAT", "TRTA", "TRTP"),
                "D5: no timing suffix, no BODSYS / CAT, and ADaM's TRTA / TRTP are not --TRT");
        assertEquals(Set.of(), domains("ADAE", "AEXTERM", "XTERM"),
                "a longer or shorter prefix is not a two-letter domain");
    }


    @Test
    void aCarriedNameIsDroppedOnlyOnEvidenceOfADifferentDomain()
    {
        SourceDomains ae = domainsOf("ADAE", "AESEQ");
        assertTrue(AdamSourceDomain.carriesAnotherDomain(ae, "CM"), "CMTRT on an AE set");
        assertFalse(AdamSourceDomain.carriesAnotherDomain(ae, "AE"));
        assertFalse(
                AdamSourceDomain.carriesAnotherDomain(domainsOf("ADAE", "AESEQ", "CMSEQ"), "CM"),
                "D3: each present domain's own names stay demanded");
        assertFalse(AdamSourceDomain.carriesAnotherDomain(domainsOf("ADAE", "STUDYID"), "AE"),
                "review H1: no evidence never drops a concrete name");
    }


    @Test
    void theCarriedDomainIsTheOpeningSourceTokenThatPrefixesTheName()
    {
        assertEquals("CM", AdamSourceDomain.carriedDomainOf("CMTRT", "CM.CMTRT"));
        assertEquals("AE", AdamSourceDomain.carriedDomainOf("aeser", "AE.AESER Serious event"));
        assertNull(AdamSourceDomain.carriedDomainOf("--SEQ", "XX.--SEQ"), "the placeholder");
        assertNull(AdamSourceDomain.carriedDomainOf("ARM", "DM.ARM"), "copies a value, no prefix");
        assertNull(AdamSourceDomain.carriedDomainOf("AESEVN", "Code AE.AESEV to numeric"),
                "a later token is a derivation input");
        assertNull(AdamSourceDomain.carriedDomainOf("VOLUME", "PC.PCSTRESN"));
        assertNull(AdamSourceDomain.carriedDomainOf("CMTRT", null));
    }
}
