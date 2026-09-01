package net.cumba.cdisc.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.library.api.model.adam.AdamProduct;
import net.cumba.datatable.impl.metadata.DataTableLibraryMetadataAdapter;
import net.cumba.web.api.dev.MapResource;
import org.junit.jupiter.api.Test;

/**
 * Fix #368 — structure-keyed resolution of ADaM Required/Expected variables.
 *
 * <p>
 * The shapes asserted here are the ones <b>measured</b> from
 * {@code /data/cdisc.metadata.library-cache-pkl/standards_details.pkl} on 2026-08-27, not invented:
 * {@code adamig-1-3} publishes {@code ADSL} classed {@code SUBJECT LEVEL ANALYSIS DATASET} and
 * <em>two</em> structures ({@code BDS}, {@code TTE}) classed {@code BASIC DATA STRUCTURE}, of which
 * {@code TTE} requires nothing; {@code adamig-1-0}…{@code 1-2} spell the same two classes
 * {@code ADSL} and {@code BDS}; and every ADaMIG version's ADSL Required list ends with the naming
 * template {@code TRTxxP}.
 * </p>
 */
class MetadataLibraryProviderAdamStructureTest
{

    // ------------------------------------------------------------------
    // Fixture builders — the CDISC Library's own JSON shape
    // ------------------------------------------------------------------

    private static Map<String, Object> var(String name, String ordinal, String core)
    {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("name", name);
        v.put("ordinal", ordinal);
        v.put("core", core);
        return v;
    }


    private static Map<String, Object> varSet(String name, List<Map<String, Object>> variables)
    {
        Map<String, Object> vs = new LinkedHashMap<>();
        vs.put("name", name);
        vs.put("analysisVariables", variables);
        return vs;
    }


    private static Map<String, Object> structure(String name, String className,
            List<Map<String, Object>> variableSets)
    {
        Map<String, Object> ds = new LinkedHashMap<>();
        ds.put("name", name);
        ds.put("class", className);
        ds.put("analysisVariableSets", variableSets);
        return ds;
    }


    private static AdamProduct product(List<Map<String, Object>> structures)
    {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", "ADaMIG");
        p.put("dataStructures", structures);
        return MapResource.of(p, AdamProduct.class);
    }


    /** {@code adamig-1-3}: ADSL + BDS + TTE, with the 1-3 class spellings. */
    private static AdamProduct adamig13()
    {
        List<Map<String, Object>> adslIds = new ArrayList<>();
        adslIds.add(var("STUDYID", "1", "Req"));
        adslIds.add(var("USUBJID", "2", "Req"));
        adslIds.add(var("SITEID", "4", "Req"));
        adslIds.add(var("AGE", "5", "Perm"));

        List<Map<String, Object>> adslTrt = new ArrayList<>();
        adslTrt.add(var("TRTxxP", "9", "Req"));
        adslTrt.add(var("TRTxxA", "10", "Perm"));

        List<Map<String, Object>> bdsVars = new ArrayList<>();
        bdsVars.add(var("PARAM", "1", "Req"));
        bdsVars.add(var("PARAMCD", "2", "Req"));
        bdsVars.add(var("AVAL", "3", "Cond"));

        // TTE shares BDS's class and publishes NO Required variable — the union case.
        List<Map<String, Object>> tteVars = new ArrayList<>();
        tteVars.add(var("CNSR", "1", "Perm"));

        List<Map<String, Object>> structures = new ArrayList<>();
        structures.add(structure("ADSL", AdamDataStructureDetector.ADSL,
                List.of(varSet("Identifier", adslIds), varSet("Treatment", adslTrt))));
        structures.add(
                structure("BDS", AdamDataStructureDetector.BDS, List.of(varSet("Basic", bdsVars))));
        structures.add(
                structure("TTE", AdamDataStructureDetector.BDS, List.of(varSet("Tte", tteVars))));
        return product(structures);
    }


    /** {@code adamig-1-1}: the same two structures under the pre-1-3 class spellings. */
    private static AdamProduct adamig11()
    {
        List<Map<String, Object>> adslVars = new ArrayList<>();
        adslVars.add(var("STUDYID", "1", "Req"));
        adslVars.add(var("SUBJID", "2", "Req"));

        List<Map<String, Object>> bdsVars = new ArrayList<>();
        bdsVars.add(var("PARAM", "1", "Req"));

        List<Map<String, Object>> structures = new ArrayList<>();
        structures.add(structure("ADSL", "ADSL", List.of(varSet("Identifier", adslVars))));
        structures.add(structure("BDS", "BDS", List.of(varSet("Basic", bdsVars))));
        return product(structures);
    }


    private static MetadataLibraryProvider providerFor(AdamProduct aProduct)
    {
        return new MetadataLibraryProvider(DataTableLibraryMetadataAdapter.empty(), aProduct,
                "adamig", "1-3");
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------


    @Test
    void adslResolvesByStructureAndKeepsTheNamingTemplateVerbatim()
    {
        MetadataLibraryProvider p = providerFor(adamig13());

        // TRTxxP is returned AS PUBLISHED. Substituting it is the caller's job — doing it here
        // would hard-code the concrete form (TRT01P) the standard never publishes, which is
        // precisely what the three PMDA-AD0047 fixtures did to hide the defect.
        assertEquals(List.of("STUDYID", "USUBJID", "SITEID", "TRTxxP"),
                p.getRequiredVariablesForStructure(AdamDataStructureDetector.ADSL));
    }


    @Test
    void bdsUnionsEveryStructureSharingItsClass()
    {
        MetadataLibraryProvider p = providerFor(adamig13());

        // BDS and TTE both class as BASIC DATA STRUCTURE; TTE requires nothing, so the union is
        // exactly BDS's list. An INTERSECTION here would be empty — silently vacuous.
        assertEquals(List.of("PARAM", "PARAMCD"),
                p.getRequiredVariablesForStructure(AdamDataStructureDetector.BDS));
    }


    @Test
    void preOneThreeClassSpellingsResolveThroughTheAliasMap()
    {
        MetadataLibraryProvider p = providerFor(adamig11());

        // ⚠ Without the alias map both of these return null and every rule on adamig 1-0/1-1/1-2
        // silently skips — the same failure the lint-rules.py version-key bug produced.
        assertEquals(List.of("STUDYID", "SUBJID"),
                p.getRequiredVariablesForStructure(AdamDataStructureDetector.ADSL));
        assertEquals(List.of("PARAM"),
                p.getRequiredVariablesForStructure(AdamDataStructureDetector.BDS));
    }


    @Test
    void anAbsentStructureIsNullAndAnEmptyOneIsAnEmptyList()
    {
        // The distinction the whole fix turns on. null => "no such structure, SKIP the rule";
        // empty => "the structure exists and requires nothing", a legitimate green pass.
        MetadataLibraryProvider p = providerFor(adamig13());
        assertNull(p.getRequiredVariablesForStructure(AdamDataStructureDetector.OCCDS));
        assertNull(
                p.getRequiredVariablesForStructure(AdamDataStructureDetector.MEDICAL_DEVICE_BDS));

        List<Map<String, Object>> onlyPermissible = List
                .of(structure("TTE", AdamDataStructureDetector.BDS,
                        List.of(varSet("Tte", List.of(var("CNSR", "1", "Perm"))))));
        MetadataLibraryProvider empty = providerFor(product(onlyPermissible));
        assertEquals(List.of(),
                empty.getRequiredVariablesForStructure(AdamDataStructureDetector.BDS));
    }


    @Test
    void structureKeyingIsAdvertisedOnlyWhenAnAdamProductIsBound()
    {
        assertTrue(providerFor(adamig13()).supportsStructureKeyedVariables());

        // No product: the study-backed provider. This is the no-CT ADaM shape before Fix #368 —
        // it answered getRequiredVariables("ADSL") from the STUDY's columns, which carry no core
        // attribute, so the list was empty for every dataset including ADSL.
        MetadataLibraryProvider studyOnly = new MetadataLibraryProvider(
                DataTableLibraryMetadataAdapter.empty());
        assertFalse(studyOnly.supportsStructureKeyedVariables());
        assertNull(studyOnly.getRequiredVariablesForStructure(AdamDataStructureDetector.ADSL));
    }


    @Test
    void expectedMirrorsRequiredBecauseAdamHasNoExpCoreValue()
    {
        MetadataLibraryProvider p = providerFor(adamig13());
        assertEquals(p.getRequiredVariablesForStructure(AdamDataStructureDetector.ADSL),
                p.getExpectedVariablesForStructure(AdamDataStructureDetector.ADSL));
    }


    @Test
    void namesAreOrderedByOrdinalAcrossFlattenedVariableSets()
    {
        // Mirrors CdiscLibraryMetadataLibrary.fromAdam: variable sets flattened, then sorted by
        // ordinal — so a reported list reads in the standard's own order, not declaration order.
        List<Map<String, Object>> setB = List.of(var("BBB", "1", "Req"));
        List<Map<String, Object>> setA = List.of(var("AAA", "2", "Req"));
        MetadataLibraryProvider p = providerFor(
                product(List.of(structure("ADSL", AdamDataStructureDetector.ADSL,
                        List.of(varSet("second", setA), varSet("first", setB))))));
        assertEquals(List.of("BBB", "AAA"),
                p.getRequiredVariablesForStructure(AdamDataStructureDetector.ADSL));
    }
}
