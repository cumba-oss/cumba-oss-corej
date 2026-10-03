package net.cumba.corej.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link UsageRestrictions}: every text form the store carries (measured over
 * {@code models/sdtm/1-2 … 2-1} on 2026-10-03, after the errata register added the 1.5–1.8
 * domain-specific roots) parses into the right clause, and the domain verdicts follow.
 */
class UsageRestrictionsTest
{

    @Test
    void domainOnlyFormsInEverySpellingTheStoreUses()
    {
        for (String text : List.of("MH domain only", "EG Domain only", "PT Domain Only",
                "GF domain only"))
        {
            UsageRestrictions r = UsageRestrictions.parse(text);
            assertEquals(List.of(UsageRestrictions.Form.DOMAINS_ONLY), r.forms(), text);
            assertEquals(1, r.onlyDomains().size(), text);
            assertFalse(r.isUnparseable(), text);
        }
        UsageRestrictions mh = UsageRestrictions.parse("MH domain only");
        assertTrue(mh.allowsDomain("MH", "EVENTS"));
        assertTrue(mh.allowsDomain("mh", null), "case-insensitive domain");
        assertFalse(mh.allowsDomain("AE", "EVENTS"), "AEEVDTYP is not a model variable");
        assertFalse(mh.allowsDomain("CE", null));
    }


    @Test
    void domainListForms()
    {
        UsageRestrictions three = UsageRestrictions.parse("CP, IS, and LB domains only");
        assertEquals(Set.of("CP", "IS", "LB"), three.onlyDomains());
        assertTrue(three.allowsDomain("LB", "FINDINGS"));
        assertFalse(three.allowsDomain("MB", "FINDINGS"));

        UsageRestrictions specimen = UsageRestrictions.parse(
                "Only in Findings class specimen-based domains: BS, CP, GF, IS, LB, MB, MS, MI, PC, PP");
        assertEquals(List.of(UsageRestrictions.Form.DOMAINS_ONLY), specimen.forms());
        assertEquals(10, specimen.onlyDomains().size());
        assertTrue(specimen.allowsDomain("PC", "FINDINGS"));
        assertFalse(specimen.allowsDomain("VS", "FINDINGS"));
    }


    @Test
    void notInDomainForms()
    {
        UsageRestrictions notAe = UsageRestrictions.parse("Not in AE domain");
        assertEquals(Set.of("AE"), notAe.notDomains());
        assertFalse(notAe.allowsDomain("AE", "EVENTS"));
        assertTrue(notAe.allowsDomain("MH", "EVENTS"));

        UsageRestrictions useCase = UsageRestrictions
                .parse("Not in QS, FT, and clinical classifications use case of RS");
        assertEquals(List.of(UsageRestrictions.Form.NOT_IN_DOMAINS), useCase.forms());
        assertEquals(Set.of("QS", "FT"), useCase.notDomains());
        assertFalse(useCase.allowsDomain("QS", "FINDINGS"));
        assertTrue(useCase.allowsDomain("RS", "FINDINGS"),
                "the RS clause is a use-case condition the domain walk cannot decide: RS stays allowed");
    }


    @Test
    void notInClassForm()
    {
        UsageRestrictions r = UsageRestrictions.parse("Not in Findings class domains");
        assertEquals(List.of(UsageRestrictions.Form.NOT_IN_CLASS), r.forms());
        assertEquals(Set.of("FINDINGS"), r.notClasses());
        assertFalse(r.allowsDomain("LB", "FINDINGS"));
        assertFalse(r.allowsDomain("FA", "FINDINGS ABOUT"), "a Findings-class domain too");
        assertTrue(r.allowsDomain("AE", "EVENTS"));
        assertTrue(r.allowsDomain("LB", null), "without a class the class clause cannot apply");
    }


    @Test
    void trialTypeAndIgClausesAreRecognisedAndNotAppliedByDomain()
    {
        UsageRestrictions human = UsageRestrictions.parse("Not in human clinical trials");
        assertEquals(List.of(UsageRestrictions.Form.TRIAL_TYPE), human.forms());
        assertTrue(human.allowsDomain("BW", "FINDINGS"));
        assertEquals(List.of(UsageRestrictions.Form.TRIAL_TYPE),
                UsageRestrictions.parse("Not in nonclinical trials").forms());
        UsageRestrictions tobacco = UsageRestrictions.parse("Tobacco IG only");
        assertEquals(List.of(UsageRestrictions.Form.IG_ONLY), tobacco.forms());
        assertTrue(tobacco.allowsDomain("DM", null));
    }


    @Test
    void compoundClausesCombine()
    {
        UsageRestrictions r = UsageRestrictions
                .parse("Not in human clinical trials; EX domain only");
        assertEquals(
                List.of(UsageRestrictions.Form.TRIAL_TYPE, UsageRestrictions.Form.DOMAINS_ONLY),
                r.forms());
        assertTrue(r.allowsDomain("EX", "INTERVENTIONS"));
        assertFalse(r.allowsDomain("CM", "INTERVENTIONS"));
        assertFalse(r.isUnparseable());
    }


    @Test
    void absentNoneAndUnparseableRestrictNothing()
    {
        assertEquals(List.of(UsageRestrictions.Form.NONE), UsageRestrictions.parse(null).forms());
        assertEquals(List.of(UsageRestrictions.Form.NONE), UsageRestrictions.parse("None").forms());
        assertEquals(List.of(UsageRestrictions.Form.NONE), UsageRestrictions.parse("  ").forms());
        UsageRestrictions odd = UsageRestrictions.parse("Only when the moon is full");
        assertTrue(odd.isUnparseable(), "reported, never silently a restriction");
        assertEquals(List.of(UsageRestrictions.Form.UNPARSEABLE), odd.forms());
        assertTrue(odd.allowsDomain("AE", "EVENTS"));
        assertTrue(UsageRestrictions.parse("MH domain only; whatever else").isUnparseable(),
                "one unknown clause makes the text unparseable even beside a known one");
    }


    /**
     * Review F5: the model walk parses every model variable's restriction on every walk, so a text
     * is parsed once and the immutable result shared; the cache is bounded, and a text past the
     * bound still parses correctly.
     */
    @Test
    void aRepeatedTextIsParsedOnceAndShared()
    {
        UsageRestrictions first = UsageRestrictions.parse("EX domain only");
        assertSame(first, UsageRestrictions.parse("EX domain only"));
        assertSame(first, UsageRestrictions.parse(String.join(" ", "EX", "domain", "only")),
                "keyed by the text, not the instance");
        assertFalse(first.allowsDomain("FA", "FINDINGS ABOUT"));
        assertTrue(first.allowsDomain("EX", "INTERVENTIONS"));
        for (int i = 0; i < UsageRestrictions.CACHE_LIMIT + 10; i++)
        {
            String domain = String.valueOf(new char[]
            {
                    (char) ('A' + i / 26), (char) ('A' + i % 26)
            });
            assertEquals(Set.of(domain),
                    UsageRestrictions.parse(domain + " domain only").onlyDomains());
        }
        UsageRestrictions late = UsageRestrictions.parse("ZZ domain only");
        assertEquals(Set.of("ZZ"), late.onlyDomains(), "past the bound a text still parses");
        assertSame(first, UsageRestrictions.parse("EX domain only"), "cached entries stay");
    }
}
