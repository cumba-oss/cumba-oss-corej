package net.cumba.corej.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.gen.WildcardExpander;
import net.cumba.corej.core.metadata.LibraryTemplates.Candidates;
import net.cumba.corej.core.metadata.LibraryTemplates.Entry;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-library-template-label-resolution} — the one template leg, tier by tier, on published
 * entries shaped as the store publishes them (ADaMIG 1.1–1.3 and OCCDS 1.0 spellings).
 */
class LibraryTemplatesTest
{

    private static final String LT = LibraryTemplates.LABEL_TEMPLATE_KEY;

    private static Entry e(String name, String label)
    {
        return new Entry(name, Map.of("label", label, "simpleDatatype", "Char"));
    }

    private static final List<Entry> BDS = List.of(e("PARAMCD", "Parameter Code"),
            e("ASTDT", "Analysis Start Date"), e("ANLzzFL", "Analysis Flag zz"),
            e("AVALCATy", "Analysis Category y"), e("AyLO", "Analysis Range y Lower Limit"),
            e("PxxSwSDT", "Period xx Subperiod w Start Date"),
            e("AOCCzzFL", "1st Occurrence of ..."));

    private static final List<Entry> ADSL = List.of(e("STUDYID", "Study Identifier"),
            e("ITTFL", "Intent-To-Treat Population Flag"),
            e("TRTSDT", "Date of First Exposure to Treatment"),
            e("TRTxxP", "Planned Treatment for Period xx"),
            e("TRTSDTF", "Date of First Exposure Imput. Flag"));

    private static final List<Entry> STEMS = List.of(e("*DT", "{Date}"), e("*SDT", "{Start Date}"),
            e("*DTF", "{Date Imputation Flag}"), e("*SDTF", "{Start Date Imputation Flag}"),
            e("*TM", "{Time}"), e("*DTM", "{Datetime}"));

    private static final Candidates BDS_DATASET = new Candidates(BDS, ADSL, STEMS);

    private static final Candidates ADSL_DATASET = new Candidates(ADSL, List.of(), STEMS);

    @Test
    void aLiteralOfTheOwnStructureWinsAndCarriesItsLabelAsTheTemplate()
    {
        Map<String, String> r = LibraryTemplates.resolve("ASTDT", BDS_DATASET);
        assertEquals("ASTDT", r.get("name"));
        assertEquals("Analysis Start Date", r.get("label"));
        assertEquals("Analysis Start Date", r.get(LT));
        // register CIT §1: the dataset's letter case does not matter
        assertEquals("ASTDT", LibraryTemplates.resolve("astdt", BDS_DATASET).get("name"));
    }


    @Test
    void anIndexTemplateSubstitutesTheMarkersAsWholeWordsAndKeepsTheTemplateName()
    {
        Map<String, String> r = LibraryTemplates.resolve("ANL01FL", BDS_DATASET);
        assertEquals("ANLzzFL", r.get("name"), "D2: the name is the template");
        assertEquals("Analysis Flag 01", r.get("label"));
        assertEquals("Analysis Flag 01 ...", r.get(LT), "D8: a zz name allows a descriptive tail");
        Map<String, String> two = LibraryTemplates.resolve("P02S1SDT", BDS_DATASET);
        assertEquals("PxxSwSDT", two.get("name"));
        assertEquals("Period 02 Subperiod 1 Start Date", two.get("label"));
        assertEquals("Period 02 Subperiod 1 Start Date", two.get(LT), "xx / w names: no tail");
        Map<String, String> y = LibraryTemplates.resolve("A1LO", BDS_DATASET);
        assertEquals("Analysis Range 1 Lower Limit", y.get("label"), "a lone y, whole word");
        assertEquals("Analysis Range 1 Lower Limit ...", y.get(LT));
    }


    @Test
    void aTemplateWhoseLabelAlreadyEndsInAnEllipsisGetsNoSecondTail()
    {
        Map<String, String> r = LibraryTemplates.resolve("AOCC01FL", BDS_DATASET);
        assertEquals("AOCCzzFL", r.get("name"));
        assertEquals("1st Occurrence of ...", r.get("label"));
        assertEquals("1st Occurrence of ...", r.get(LT));
    }


    /**
     * T2 review H1. {@code adam-occds-1-0} (and {@code -1-1}, and the TIG) publish {@code DECDORGw}
     * twice in ONE structure, with the two labels below — measured in our store
     * ({@code products/standards/adam/adam-occds-1-0.json}, variable sets "Original or Prior MedDRA
     * Coding Variables" and "Original or Prior WHO Drug Coding Variables"). Which one a
     * {@code DECDORG1} column must carry depends on the dictionary, which the name does not tell:
     * no answer. The fixture this replaced gave both rows ONE invented label, so it could only ever
     * confirm the merge.
     */
    @Test
    void oneTemplateNamePublishedWithTwoDifferentLabelsIsAnAmbiguity()
    {
        List<Entry> occds = List.of(e("STUDYID", "Study Identifier"),
                e("DECDORGw", "PT in Original Dictionary w"),
                e("DECDORGw", "Standardized Med Name in Orig Dict w"),
                e("AOCCzzFL", "1st Occurrence of ..."));
        Candidates occdsDataset = new Candidates(occds, ADSL, STEMS);
        assertEquals(Map.of(), LibraryTemplates.resolve("DECDORG1", occdsDataset),
                "MedDRA or WHO Drug: no answer, and no fall-through to ADSL or a stem");
        assertEquals("1st Occurrence of ...",
                LibraryTemplates.resolve("AOCC01FL", occdsDataset).get(LT),
                "control: the other template of the same structure still answers");
        // the order of the two rows does not matter
        assertEquals(Map.of(), LibraryTemplates.resolve("DECDORG1",
                new Candidates(List.of(occds.get(2), occds.get(1)), ADSL, STEMS)));
    }


    @Test
    void rowsOfOneNameWithEqualLabelsAreOneTemplateWhitespaceAside()
    {
        List<Entry> twice = List.of(e("AOCCzzFL", "1st Occurrence of ..."),
                e("AOCCzzFL", "1st Occurrence of ..."), e("ANLzzFL", "Analysis Flag zz"),
                e("ANLzzFL", "Analysis  Flag zz "));
        Candidates c = new Candidates(twice, ADSL, STEMS);
        assertEquals("AOCCzzFL", LibraryTemplates.resolve("AOCC01FL", c).get("name"),
                "the same label twice: one template");
        assertEquals("Analysis Flag 01", LibraryTemplates.resolve("ANL01FL", c).get("label"),
                "a label differing only in whitespace is no disagreement; the first row serves");
    }


    @Test
    void aLiteralPublishedTwiceWithDifferentLabelsIsAnAmbiguityToo()
    {
        Candidates clash = new Candidates(
                List.of(e("ADURN", "Analysis Duration (N)"), e("ADURN", "AE Duration (N)")),
                List.of(e("ADURN", "ADSL row")), STEMS);
        assertEquals(Map.of(), LibraryTemplates.resolve("ADURN", clash),
                "no answer, and no fall-through to the ADSL literal");
        Candidates same = new Candidates(
                List.of(e("SRCDOM", "Source Data"), e("SRCDOM", "Source Data")), ADSL, STEMS);
        assertEquals("Source Data", LibraryTemplates.resolve("SRCDOM", same).get("label"));
    }


    @Test
    void twoDifferentTemplatesMatchingOneColumnAreAnAmbiguity()
    {
        List<Entry> clashing = List.of(e("ANLzzFL", "Analysis Flag zz"), e("ANLxxFL", "Other zz"));
        assertEquals(Map.of(),
                LibraryTemplates.resolve("ANL01FL", new Candidates(clashing, ADSL, STEMS)),
                "two different templates match: no answer, and no fall-through to ADSL or a stem");
    }


    /**
     * The governing chain is tiered (subclass, then base): ambiguity is a property of ONE tier, and
     * the most specific tier with an answer governs. Labels as our store publishes them:
     * {@code adam-adae-1-0} ADAE ({@code AOCCzzFL} with four dots, {@code DECDORGy}) over the OCCDS
     * base ({@code AOCCzzFL} with three, {@code DECDORGw} twice).
     */
    @Test
    void theMostSpecificTierGovernsInsteadOfCollidingWithItsBase()
    {
        List<Entry> adae = List.of(e("AOCCzzFL", "1st Occurrence of ...."),
                e("DECDORGy", "PT in Original Dictionary y"));
        List<Entry> occds = List.of(e("AOCCzzFL", "1st Occurrence of ..."),
                e("DECDORGw", "PT in Original Dictionary w"),
                e("DECDORGw", "Standardized Med Name in Orig Dict w"),
                e("ANLzzFL", "Analysis Flag zz"));
        Candidates chain = Candidates.ofTiers(List.of(adae, occds), List.of(ADSL), STEMS);
        assertEquals("1st Occurrence of ....", LibraryTemplates.resolve("AOCC01FL", chain).get(LT),
                "the subclass's own label, not a collision with the base's");
        assertEquals("DECDORGy", LibraryTemplates.resolve("DECDORG1", chain).get("name"),
                "DECDORGy (subclass) governs DECDORGw (base), which alone would be ambiguous");
        assertEquals("ANLzzFL", LibraryTemplates.resolve("ANL01FL", chain).get("name"),
                "a name only the base publishes resolves from the base");
        assertEquals(Map.of(),
                LibraryTemplates.resolve("DECDORG1",
                        Candidates.ofTiers(List.of(occds), List.of(ADSL), STEMS)),
                "control: the base alone is ambiguous for the same column");
    }


    @Test
    void aResolutionIsMemoisedPerCandidatesAndColumn()
    {
        Candidates c = new Candidates(BDS, ADSL, STEMS);
        Map<String, String> first = LibraryTemplates.resolve("ANL01FL", c);
        assertSame(first, LibraryTemplates.resolve("ANL01FL", c),
                "the second read of the same column is the memoised answer");
        assertEquals(first, LibraryTemplates.resolve("ANL01FL", new Candidates(BDS, ADSL, STEMS)),
                "and equal to a fresh resolution");
    }


    @Test
    void anAdslVariableInABdsDatasetResolvesToItsAdslRowBeforeAnyStem()
    {
        Map<String, String> ittfl = LibraryTemplates.resolve("ITTFL", BDS_DATASET);
        assertEquals("ITTFL", ittfl.get("name"));
        assertEquals("Intent-To-Treat Population Flag", ittfl.get("label"));
        // D9 beats the stem: TRTSDT ends in SDT but is an ADSL literal
        Map<String, String> trtsdt = LibraryTemplates.resolve("TRTSDT", BDS_DATASET);
        assertEquals("TRTSDT", trtsdt.get("name"));
        assertEquals("Date of First Exposure to Treatment", trtsdt.get("label"));
        // and the ADSL template tier too
        Map<String, String> trt01p = LibraryTemplates.resolve("TRT01P", BDS_DATASET);
        assertEquals("TRTxxP", trt01p.get("name"));
        assertEquals("Planned Treatment for Period 01", trt01p.get("label"));
        assertEquals("Planned Treatment for Period 01", trt01p.get(LT));
        // the ADSL dataset itself has no ADSL tier, and does not need one
        assertEquals("ITTFL", LibraryTemplates.resolve("ITTFL", ADSL_DATASET).get("name"));
    }


    @Test
    void aColumnSpelledLikeTheTemplateHitsItLiterallyAndHoldsNoTemplate()
    {
        Map<String, String> r = LibraryTemplates.resolve("TRTXXP", ADSL_DATASET);
        assertEquals("TRTxxP", r.get("name"));
        assertEquals("Planned Treatment for Period xx", r.get("label"), "verbatim, as today");
        assertNull(r.get(LT), "a template names no concrete variable: nothing to hold it to");
    }


    @Test
    void theLongestStemSuffixGovernsAndServesOnlyNameAndTemplate()
    {
        Map<String, String> sdt = LibraryTemplates.resolve("RANDSDT", BDS_DATASET);
        assertEquals("*SDT", sdt.get("name"), "*SDT, never *DT");
        assertEquals("{Start Date}", sdt.get(LT), "the longest stem's phrase");
        assertEquals("*SDTF", LibraryTemplates.resolve("XXSDTF", BDS_DATASET).get("name"));
        assertEquals("*DTF", LibraryTemplates.resolve("XXDTF", BDS_DATASET).get("name"));
        assertEquals("*DT", LibraryTemplates.resolve("XXDT", BDS_DATASET).get("name"));
        assertEquals("*DTM", LibraryTemplates.resolve("XXDTM", BDS_DATASET).get("name"),
                "*DTM, never *TM");
        assertEquals("*SDT", LibraryTemplates.resolve("randsdt", ADSL_DATASET).get("name"),
                "a sponsor column of ADSL is governed by the stems too");
        assertFalse(sdt.containsKey("simpleDatatype"), "a stem serves no type");
        assertEquals(Map.of(), LibraryTemplates.resolve("DT", BDS_DATASET),
                "the root binds at least one character");
        assertEquals("*DT", LibraryTemplates.resolve("SDT", BDS_DATASET).get("name"),
                "SDT is *DT with the one-letter root S, not *SDT with an empty one");
        assertEquals(Map.of(), LibraryTemplates.resolve("NOTHING", BDS_DATASET));
    }


    /**
     * R2-3: a stem hit carries every published stem the name ends in under its own key, so the
     * stem-selecting accessor form serves each stem rule its own phrase — and a literal or index
     * template hit carries none, which is the stem rules' literal skip.
     */
    @Test
    void everyStemTheNameEndsInServesItsOwnTemplateUnderItsKey()
    {
        Map<String, String> sdt = LibraryTemplates.resolve("RANDSDT", BDS_DATASET);
        assertEquals("{Date}", sdt.get(LibraryTemplates.stemKey("*DT")), "*DT fits RANDSDT");
        assertEquals("{Start Date}", sdt.get(LibraryTemplates.stemKey("*SDT")));
        assertEquals("{Start Date}", sdt.get(LibraryTemplates.stemKey("*sdt")),
                "the key is case-folded");
        assertNull(sdt.get(LibraryTemplates.stemKey("*DTF")), "a stem the name does not end in");
        assertEquals("label_template:*DT", LibraryTemplates.stemKey("*dt"));
        Map<String, String> dt = LibraryTemplates.resolve("XXDT", BDS_DATASET);
        assertEquals("{Date}", dt.get(LibraryTemplates.stemKey("*DT")));
        assertNull(dt.get(LibraryTemplates.stemKey("*SDT")), "XXDT does not end in SDT");
        assertEquals(Map.of("name", "*DT", LT, "{Date}", "label_template:*DT", "{Date}"), dt,
                "a stem hit: the longest stem's name and phrase, plus one key per fitting stem");
        // a literal of the own structure (ASTDT ends in SDT), an ADSL literal copied into BDS
        // (TRTSDT) and an index template (PxxSwSDT) are governed by their own published label,
        // never by the suffix convention: no stem key, so the stem form answers a missing
        for (String literal : List.of("ASTDT", "TRTSDT", "P01S1SDT"))
        {
            Map<String, String> r = LibraryTemplates.resolve(literal, BDS_DATASET);
            assertFalse(r.isEmpty(), literal + " resolves");
            assertNull(r.get(LibraryTemplates.stemKey("*DT")), literal + " carries no *DT key");
            assertNull(r.get(LibraryTemplates.stemKey("*SDT")), literal + " carries no *SDT key");
        }
    }


    @Test
    void aBracePhraseIsNeverServedAsALabel()
    {
        List<Entry> own = List.of(new Entry("FOOy", Map.of("label", "{Phrase} y")));
        Map<String, String> r = LibraryTemplates.resolve("FOO1",
                new Candidates(own, List.of(), List.of()));
        assertEquals("FOOy", r.get("name"));
        assertNull(r.get("label"));
        assertEquals("{Phrase} 1 ...", r.get(LT));
        List<Entry> lit = List.of(new Entry("BAR", Map.of("label", "{Phrase}")));
        Map<String, String> l = LibraryTemplates.resolve("BAR",
                new Candidates(lit, List.of(), List.of()));
        assertEquals("{Phrase}", l.get("label"), "a literal's attributes are served as published");
        assertNull(l.get(LT), "but a braced label is no template of a literal");
    }


    @Test
    void theMarkerGrammarIsTheWildcardExpanders()
    {
        assertNull(WildcardExpander.indexTemplate("TRTSDT"));
        assertNull(WildcardExpander.indexTemplate("*SDT"));
        assertNull(WildcardExpander.indexTemplate("TRTyyP"), "yy is no marker");
        assertEquals(List.of("xx", "w"), WildcardExpander.indexTemplate("PxxSw").groupNames());
        assertEquals(Map.of("xx", "02", "w", "1"),
                WildcardExpander.indexTemplate("PxxSwSDT").capture("p02s1sdt"));
        assertNull(WildcardExpander.indexTemplate("TRTxxP").capture("TRT1P"), "xx is two digits");
        assertTrue(LibraryTemplates.allowsDescriptiveTail("AVALCATy"));
        assertTrue(LibraryTemplates.allowsDescriptiveTail("ANLzzFL"));
        assertFalse(LibraryTemplates.allowsDescriptiveTail("TRTxxP"));
        assertFalse(LibraryTemplates.allowsDescriptiveTail("PHwSDT"));
        assertEquals("Fixxed 01",
                LibraryTemplates.substituteMarkers("Fixxed xx", Map.of("xx", "01")),
                "a marker inside a word is left alone");
    }


    @Test
    void entriesComeFromMapRowsWithTheirPublishedSpelling()
    {
        Map<String, Map<String, String>> rows = Map.of("TRTXXP",
                Map.of("name", "TRTxxP", "label", "Planned Treatment for Period xx"), "AGE",
                Map.of("label", "Age"));
        List<Entry> entries = LibraryTemplates.entriesOf(rows);
        assertEquals(2, entries.size());
        assertTrue(entries.stream().anyMatch(en -> "TRTxxP".equals(en.name())),
                "the name= attribute is the published spelling");
        assertTrue(entries.stream().anyMatch(en -> "AGE".equals(en.name())),
                "without one, the map key");
        assertEquals(List.of("*SDT"), LibraryTemplates
                .stemsOf(List.of(e("ASTDT", "x"), e("*SDT", "{Start Date}"), e("*sdt", "dup")))
                .stream().map(Entry::name).toList());
    }
}
