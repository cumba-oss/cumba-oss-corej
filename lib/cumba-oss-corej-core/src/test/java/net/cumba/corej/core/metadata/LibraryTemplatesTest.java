package net.cumba.corej.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
            e("PxxSwSDT", "Period xx Subperiod w Start Date"), e("DECDORGw", "Dict Decode Org w"),
            e("DECDORGw", "Dict Decode Org w"), e("AOCCzzFL", "1st Occurrence of ..."));

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


    @Test
    void twoRowsOfOneTemplateAreOneTemplateButTwoTemplatesAreAnAmbiguity()
    {
        assertEquals("DECDORGw", LibraryTemplates.resolve("DECDORG1", BDS_DATASET).get("name"),
                "adam-occds-1-0 publishes DECDORGw twice: one template");
        List<Entry> clashing = List.of(e("ANLzzFL", "Analysis Flag zz"), e("ANLxxFL", "Other zz"));
        assertEquals(Map.of(),
                LibraryTemplates.resolve("ANL01FL", new Candidates(clashing, ADSL, STEMS)),
                "two different templates match: no answer, and no fall-through to ADSL or a stem");
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
        assertEquals(Map.of("name", "*SDT", LT, "{Start Date}"), sdt, "*SDT, never *DT");
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
