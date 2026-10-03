package net.cumba.corej.core.metadata.store.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.metadata.store.StoredClass;
import net.cumba.corej.core.metadata.store.StoredDataStructure;
import net.cumba.corej.core.metadata.store.StoredDataset;
import net.cumba.corej.core.metadata.store.StoredDomain;
import net.cumba.corej.core.metadata.store.StoredField;
import net.cumba.corej.core.metadata.store.StoredProduct;
import net.cumba.corej.core.metadata.store.StoredScenario;
import net.cumba.corej.core.metadata.store.StoredVariable;
import net.cumba.corej.core.metadata.store.StoredVariableSet;
import org.junit.jupiter.api.Test;

/**
 * {@link StoreErrata} over synthetic products: one application per entry kind, the stale verdict
 * (D4: expected value gone → reported, NOT applied) and the unreached verdict (product, container
 * or variable absent → reported, no warning), the model-dataset double copy, and the register
 * guards (duplicate id, unknown kind). The shipped register itself is proven on the real corpus by
 * {@link StoreErrataRealCorpusTest}.
 */
class StoreErrataTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static StoreErrata register(String aEntriesJson)
    {
        try
        {
            return StoreErrata.parse(MAPPER.readTree("{\"entries\": [" + aEntriesJson + "]}"));
        }
        catch (java.io.IOException e)
        {
            throw new AssertionError(e);
        }
    }


    private static StoredVariable var(String aName, String aOrdinal, String aLabel)
    {
        return StoredVariable.builder().name(aName).ordinal(aOrdinal).label(aLabel).build();
    }


    /** An SDTM-model-shaped product: a class with variables, the DM dataset twice (class + top). */
    private static StoredProduct model()
    {
        StoredDataset dm = new StoredDataset("DM", "Demographics", "1", null,
                List.of(var("DMDTC", "1", "Date/Time of Collection")), null, null);
        StoredClass events = new StoredClass("Events", "Events", "1",
                List.of(var("--TERM", "1", "Reported Term"),
                        var("--DECOD", "2", "Dictionary-Derived Term"),
                        var("--CAT", "3", "Category")),
                List.of(), null, List.of(), List.of(), List.of());
        StoredClass special = new StoredClass("Special-Purpose", "Special-Purpose", "2", List.of(),
                List.of(dm), null, List.of(), List.of(), List.of());
        return StoredProduct.builder().key("models/sdtm/1-7").classes(List.of(events, special))
                .datasets(List.of(dm)).build();
    }


    private static StoredProduct adam()
    {
        StoredVariableSet ids = new StoredVariableSet("Identifier", "Identifier Variables", "1",
                List.of(var("STUDYID", "1", "Study Identifier")), null);
        StoredVariableSet flags = new StoredVariableSet("Flag", "Flag Variables", "2",
                List.of(var("ABLFL", "1", "Baseline Record Flag")), null);
        StoredVariableSet pop = new StoredVariableSet("Population Indicators", "Population", "3",
                List.of(var("ITTRFL", "1", "ITT Record Flag*"),
                        var("SAFRFL", "2", "Safety Record Flag*")),
                null);
        StoredDataStructure bds = new StoredDataStructure("BDS", "Basic Data Structure", "2", "BDS",
                null, List.of(flags, pop, ids), "censored and for what");
        return StoredProduct.builder().key("standards/adam/adamig-1-3").dataStructures(List.of(bds))
                .build();
    }


    private static StoredField field(String aName, String aOrdinal, String aLabel)
    {
        return StoredField.builder().name(aName).ordinal(aOrdinal).label(aLabel).build();
    }


    private static StoredProduct cdashIg()
    {
        StoredDomain ae = new StoredDomain("AE", "Adverse Events", "1", null, List.of(
                field("AETERM", "1", "Adverse Event Term"), field("VISDAT", "303", "Visit Date")));
        StoredScenario local = new StoredScenario("Local Reading", "Findings", "EG", "1",
                List.of(field("EGCLSIG", "1", "ECG Clinical Significance")));
        StoredClass events = new StoredClass("Events", "Events", "1", List.of(), List.of(), null,
                List.of(ae), List.of(local), List.of());
        return StoredProduct.builder().key("standards/cdashig/1-1-1").classes(List.of(events))
                .build();
    }


    private static StoredProduct cdashModel()
    {
        StoredClass findings = new StoredClass("Findings", "Findings", "1", List.of(), List.of(),
                null, List.of(), List.of(),
                List.of(field("--OBJ", "1", "Object of the Observation"),
                        field("--ORRES", "2", "Result")));
        StoredDomain dm = new StoredDomain("DM", "Demographics", "1", null,
                List.of(field("SITEID", "1", "Site")));
        return StoredProduct.builder().key("models/cdash/1-0").classes(List.of(findings))
                .domains(List.of(dm)).build();
    }


    private static Map<String, StoredProduct> all()
    {
        return Map.of("models/sdtm/1-7", model(), "standards/adam/adamig-1-3", adam(),
                "standards/cdashig/1-1-1", cdashIg(), "models/cdash/1-0", cdashModel());
    }


    private static StoredClass classOf(StoredProduct aProduct, String aName)
    {
        return aProduct.classes().stream().filter(c -> aName.equals(c.name())).findFirst()
                .orElseThrow();
    }


    @Test
    void setAttributeRewritesTheVariableAndEveryCopyOfAModelDataset()
    {
        StoreErrata errata = register("""
                {"id":"t.1","issue":"#283.17","product":"models/sdtm/1-7","kind":"set-attribute",
                 "where":{"dataset":"DM","variable":"DMDTC"},"attribute":"label",
                 "expected":"Date/Time of Collection","corrected":"Datetime of Collection"},
                {"id":"t.2","issue":"#282.1","product":"models/sdtm/1-7","kind":"set-attribute",
                 "where":{"class":"Events","variable":"--TERM"},"attribute":"codelistIds",
                 "expected":null,"corrected":["C66786"]}
                """);
        StoreErrata.Outcome out = errata.apply(all(), Set.of());
        assertEquals(List.of("t.1", "t.2"), out.applied());
        assertEquals(List.of(), out.stale());
        assertEquals(List.of(), out.unreached());
        StoredProduct m = out.products().get("models/sdtm/1-7");
        assertEquals("Datetime of Collection", m.datasets().get(0).variables().get(0).label(),
                "the top-level copy");
        assertEquals("Datetime of Collection",
                classOf(m, "Special-Purpose").datasets().get(0).variables().get(0).label(),
                "the class copy");
        assertEquals(List.of("C66786"), classOf(m, "Events").classVariables().get(0).codelistIds());
        assertEquals("Reported Term", classOf(m, "Events").classVariables().get(0).label(),
                "the other attributes survive the rebuild");
    }


    @Test
    void aStaleEntryIsReportedAndNotApplied()
    {
        StoreErrata errata = register("""
                {"id":"s.1","issue":"#282.1","product":"models/sdtm/1-7","kind":"set-attribute",
                 "where":{"class":"Events","variable":"--TERM"},"attribute":"label",
                 "expected":"Reported Term e","corrected":"Reported Term"}
                """);
        StoreErrata.Outcome out = errata.apply(all(), Set.of());
        assertEquals(List.of(), out.applied());
        assertEquals(1, out.stale().size(), out.stale().toString());
        assertTrue(out.stale().get(0).startsWith("s.1: label expected 'Reported Term e' but the"
                + " source now holds 'Reported Term'"), out.stale().get(0));
        assertEquals("Reported Term", classOf(out.products().get("models/sdtm/1-7"), "Events")
                .classVariables().get(0).label(), "D4: never silently re-applied");
    }


    @Test
    void anAbsentProductContainerOrVariableIsUnreachedNotStale()
    {
        StoreErrata errata = register(
                """
                        {"id":"u.1","issue":"#282.1","product":"standards/sdtmig/3-2","kind":"set-attribute",
                         "where":{"dataset":"TI","variable":"IETESTCD"},"attribute":"label",
                         "expected":"x","corrected":"y"},
                        {"id":"u.2","issue":"#282.1","product":"models/sdtm/1-7","kind":"set-attribute",
                         "where":{"class":"Findings","variable":"--TEST"},"attribute":"label",
                         "expected":"x","corrected":"y"},
                        {"id":"u.3","issue":"#282.1","product":"models/sdtm/1-7","kind":"set-attribute",
                         "where":{"class":"Events","variable":"--LLT"},"attribute":"label",
                         "expected":"x","corrected":"y"}
                        """);
        StoreErrata.Outcome out = errata.apply(all(), Set.of());
        assertEquals(List.of(), out.stale());
        assertEquals(3, out.unreached().size(), out.unreached().toString());
        assertTrue(
                out.unreached().get(0).contains("product standards/sdtmig/3-2 not in this source"));
        assertTrue(out.unreached().get(1).contains("class Findings not in models/sdtm/1-7"));
        assertTrue(out.unreached().get(2).contains("variable --LLT not in class Events"));
    }


    @Test
    void normalisedEntriesAssertTheSiteWasNormalisedThisRun()
    {
        StoreErrata errata = register("""
                {"id":"n.1","issue":"#283.34","product":"models/sdtm/1-7","kind":"normalised",
                 "where":{"class":"Events","variable":"--CAT"},"attribute":"label",
                 "expected":"Category \\\\n","corrected":"Category"}
                """);
        String site = "models/sdtm/1-7/classes[Events]/classVariables[--CAT].label";
        StoreErrata.Outcome applied = errata.apply(all(), Set.of(site));
        assertEquals(List.of("n.1"), applied.applied());
        StoreErrata.Outcome stale = errata.apply(all(), Set.of());
        assertEquals(1, stale.stale().size());
        assertTrue(stale.stale().get(0).contains("normalisation no longer changes " + site),
                stale.stale().get(0));
        StoreErrata.Outcome dataset = register("""
                {"id":"n.2","issue":"#283.35","product":"models/sdtm/1-7","kind":"normalised",
                 "where":{"dataset":"DM","variable":"DMDTC"},"attribute":"label",
                 "expected":"x","corrected":"Date/Time of Collection"}
                """).apply(all(), Set.of(
                "models/sdtm/1-7/classes[Special-Purpose]/datasets[DM]/variables[DMDTC].label"));
        assertEquals(1, dataset.stale().size(),
                "both copies of a model dataset must have been normalised: " + dataset.stale());
    }


    @Test
    void addVariableInsertsAfterItsAnchorAndRenumbersTheClass()
    {
        StoreErrata errata = register("""
                {"id":"a.1","issue":"#283.3","product":"models/sdtm/1-7","kind":"add-variable",
                 "where":{"class":"Events"},"after":"--DECOD",
                 "variable":{"name":"--EVDTYP","label":"Medical History Event Date Type",
                   "simpleDatatype":"Char","role":"Variable Qualifier",
                   "roleDescription":"Variable Qualifier of MHSTDTC and/or MHENDTC",
                   "description":"Specifies the aspect.","usageRestrictions":"MH domain only"}}
                """);
        StoreErrata.Outcome out = errata.apply(all(), Set.of());
        assertEquals(List.of("a.1"), out.applied());
        List<StoredVariable> vars = classOf(out.products().get("models/sdtm/1-7"), "Events")
                .classVariables();
        assertEquals(List.of("--TERM", "--DECOD", "--EVDTYP", "--CAT"),
                vars.stream().map(StoredVariable::name).toList());
        assertEquals(List.of("1", "2", "3", "4"),
                vars.stream().map(StoredVariable::ordinal).toList());
        assertEquals("MH domain only", vars.get(2).usageRestrictions());
        assertNull(vars.get(2).variableCcode(), "not printed, not invented");

        StoreErrata.Outcome again = errata.apply(out.products(), Set.of());
        assertEquals(1, again.stale().size(), "a second application finds the root present");
        assertTrue(again.stale().get(0).contains("already in class Events"));
    }


    @Test
    void addFieldAppendsToTheDomain()
    {
        StoreErrata errata = register(
                """
                        {"id":"f.1","issue":"#283.11","product":"standards/cdashig/1-1-1","kind":"add-field",
                         "where":{"class":"Events","domain":"AE"},
                         "field":{"name":"VISENDAT","ordinal":"304","core":"O","prompt":"Visit End Date",
                           "questionText":"What is the visit end date?"}}
                        """);
        StoreErrata.Outcome out = errata.apply(all(), Set.of());
        assertEquals(List.of("f.1"), out.applied());
        List<StoredField> fields = classOf(out.products().get("standards/cdashig/1-1-1"), "Events")
                .domains().get(0).fields();
        assertEquals(List.of("AETERM", "VISDAT", "VISENDAT"),
                fields.stream().map(StoredField::name).toList());
        assertEquals("304", fields.get(2).ordinal());
        assertEquals("O", fields.get(2).core());
        assertEquals(1, errata.apply(out.products(), Set.of()).stale().size(), "present → stale");
    }


    @Test
    void setOrderRemoveSetAndStructureAttributes()
    {
        StoreErrata errata = register(
                """
                        {"id":"o.1","issue":"#283.31","product":"standards/adam/adamig-1-3","kind":"remove-set",
                         "where":{"structure":"BDS","set":"Population Indicators"},"expected":["ITTRFL","SAFRFL"]},
                        {"id":"o.2","issue":"#283.28","product":"standards/adam/adamig-1-3","kind":"set-order",
                         "where":{"structure":"BDS"},"expected":["Flag","Identifier"],"corrected":["Identifier","Flag"]},
                        {"id":"o.3","issue":"#283.29","product":"standards/adam/adamig-1-3","kind":"set-attribute",
                         "where":{"structure":"BDS"},"attribute":"description",
                         "expected":"censored and for what","corrected":"censored and for what reason."},
                        {"id":"o.4","issue":"#283.47","product":"standards/adam/adamig-1-3","kind":"set-attribute",
                         "where":{"structure":"BDS","set":"Flag"},"attribute":"name",
                         "expected":"Flag","corrected":"Flags"}
                        """);
        StoreErrata.Outcome out = errata.apply(all(), Set.of());
        assertEquals(List.of("o.1", "o.2", "o.3", "o.4"), out.applied(), out.stale().toString());
        StoredDataStructure bds = out.products().get("standards/adam/adamig-1-3").dataStructures()
                .get(0);
        assertEquals(List.of("Identifier", "Flags"),
                bds.variableSets().stream().map(StoredVariableSet::name).toList());
        assertEquals(List.of("1", "2"),
                bds.variableSets().stream().map(StoredVariableSet::ordinal).toList(),
                "the existing ordinals are reassigned in the new order");
        assertEquals("censored and for what reason.", bds.description());
    }


    @Test
    void setOrderIsStaleWhenTheCurrentOrderMovedOrTheCorrectionIsNoPermutation()
    {
        StoreErrata.Outcome moved = register(
                """
                        {"id":"o.5","issue":"#283.28","product":"standards/adam/adamig-1-3","kind":"set-order",
                         "where":{"structure":"BDS"},"expected":["Identifier","Flag","Population Indicators"],
                         "corrected":["Identifier","Population Indicators","Flag"]}
                        """)
                        .apply(all(), Set.of());
        assertEquals(1, moved.stale().size());
        assertTrue(moved.stale().get(0).contains("set order expected"), moved.stale().get(0));
        StoreErrata.Outcome bad = register(
                """
                        {"id":"o.6","issue":"#283.28","product":"standards/adam/adamig-1-3","kind":"set-order",
                         "where":{"structure":"BDS"},"expected":["Flag","Population Indicators","Identifier"],
                         "corrected":["Identifier","Flag"]}
                        """)
                        .apply(all(), Set.of());
        assertTrue(bad.stale().get(0).contains("not a permutation"), bad.stale().get(0));
    }


    @Test
    void moveFieldMovesACdashModelFieldIntoADomainCreatingItOnRequest()
    {
        StoreErrata errata = register(
                """
                        {"id":"m.1","issue":"#283.24","product":"models/cdash/1-0","kind":"move-field",
                         "field":"--OBJ","from":{"class":"Findings"},"to":{"domain":"FA"},"createDomain":true},
                        {"id":"m.2","issue":"#283.25","product":"models/cdash/1-0","kind":"move-field",
                         "field":"--ORRES","from":{"class":"Findings"},"to":{"domain":"XX"},"createDomain":false}
                        """);
        StoreErrata.Outcome out = errata.apply(all(), Set.of());
        assertEquals(List.of("m.1"), out.applied());
        assertEquals(1, out.stale().size(), "a missing domain without createDomain is stale");
        StoredProduct m = out.products().get("models/cdash/1-0");
        assertEquals(List.of("--ORRES"),
                classOf(m, "Findings").cdashModelFields().stream().map(StoredField::name).toList());
        assertEquals(List.of("DM", "FA"), m.domains().stream().map(StoredDomain::name).toList());
        assertEquals(List.of("--OBJ"),
                m.domains().get(1).fields().stream().map(StoredField::name).toList());
        assertNull(m.domains().get(1).label(), "a created domain carries the printed name only");
    }


    @Test
    void scenarioAndClassFieldsAreLocatedToo()
    {
        StoreErrata errata = register(
                """
                        {"id":"c.1","issue":"#283.39","product":"standards/cdashig/1-1-1","kind":"set-attribute",
                         "where":{"class":"Events","scenario":"Local Reading","field":"EGCLSIG"},"attribute":"label",
                         "expected":"ECG Clinical Significance","corrected":"ECG Clin. Sig."},
                        {"id":"c.2","issue":"#282.25","product":"models/cdash/1-0","kind":"set-attribute",
                         "where":{"class":"Findings","field":"--ORRES"},"attribute":"label",
                         "expected":"Result","corrected":"Result in Original Units"},
                        {"id":"c.3","issue":"#283.22","product":"models/cdash/1-0","kind":"set-attribute",
                         "where":{"domain":"DM","field":"SITEID"},"attribute":"prompt",
                         "expected":null,"corrected":"Site"}
                        """);
        StoreErrata.Outcome out = errata.apply(all(), Set.of());
        assertEquals(List.of("c.1", "c.2", "c.3"), out.applied(), out.unreached().toString());
        assertEquals("ECG Clin. Sig.",
                classOf(out.products().get("standards/cdashig/1-1-1"), "Events").scenarios().get(0)
                        .fields().get(0).label());
        StoredProduct m = out.products().get("models/cdash/1-0");
        assertEquals("Result in Original Units",
                classOf(m, "Findings").cdashModelFields().get(1).label());
        assertEquals("Site", m.domains().get(0).fields().get(0).prompt());
    }


    @Test
    void theRegisterGuardsRejectDuplicateIdsUnknownKindsAndUncitedEntries()
    {
        assertThrows(IllegalStateException.class, () -> register("""
                {"id":"d","issue":"#1","product":"p","kind":"set-attribute"},
                {"id":"d","issue":"#1","product":"p","kind":"set-attribute"}
                """));
        assertThrows(IllegalStateException.class, () -> register(
                "{\"id\":\"k\",\"issue\":\"#1\",\"product\":\"p\",\"kind\":\"rename\"}"));
        assertThrows(IllegalStateException.class, () -> register(
                "{\"id\":\"i\",\"issue\":\"\",\"product\":\"p\",\"kind\":\"set-attribute\"}"));
    }


    @Test
    void theShippedRegisterLoadsAndCitesOnlyTheThreeAppliedIssues()
    {
        List<StoreErrata.Entry> entries = StoreErrata.load().entries();
        assertTrue(entries.size() > 500, "entries: " + entries.size());
        for (StoreErrata.Entry e : entries)
        {
            assertTrue(e.issue().matches("#28[234]\\.\\d+"), e.id() + " cites " + e.issue());
            assertTrue(e.authority().length() > 10, e.id() + " names its authority");
        }
    }
}
