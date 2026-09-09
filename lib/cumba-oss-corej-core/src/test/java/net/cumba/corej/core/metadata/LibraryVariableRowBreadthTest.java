package net.cumba.corej.core.metadata;

import static net.cumba.datatable.testkit.TestMetadataFixtures.lib;
import static net.cumba.datatable.testkit.TestMetadataFixtures.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.metadata.store.StoredClass;
import net.cumba.corej.core.metadata.store.StoredDataStructure;
import net.cumba.corej.core.metadata.store.StoredDataset;
import net.cumba.corej.core.metadata.store.StoredProduct;
import net.cumba.corej.core.metadata.store.StoredVariable;
import net.cumba.corej.core.metadata.store.StoredVariableSet;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⭐ P3 of PLAN-library-variable-key-name-breadth.md — the resolver row carries every <b>scalar</b>
 * field the source product published, not the six it used to.
 *
 * <p>
 * <b>Why this test exists.</b> Both engines filter library variables by a caller-supplied
 * {@code key_name}. Python compares against the raw variable dict; coreJ compared against a six-key
 * map. A rule written to the SEND authoring template's own legal vocabulary —
 * {@code definition | examples | label | name | notes | ordinal | role | simpleDatatype |
 * variableCcode} — therefore matched nothing for four of those nine values, on every dataset, with
 * no diagnostic. This test walks the real resolver and pins that the fields are there.
 * </p>
 *
 * <h2>⛔ …and pins that there is ONE vocabulary, not one per level</h2>
 *
 * <p>
 * The plan asked for a per-level split: model-product keys legal only on
 * {@code get_model_filtered_variables}, IG-product keys only on
 * {@code get_dataset_filtered_variables}. Measured, that split does not exist — neither walk is
 * confined to one product — and encoding it would refuse rules the engine demonstrably serves, the
 * same mistake P1 declined to make with {@code core}.
 * {@link #theIgWalkPublishesTheModelOnlyScalars} and {@link #theModelWalkPublishesIgOnlyCore} are
 * the two directions, so a later "tightening" reds here instead of silently killing rules.
 * </p>
 */
class LibraryVariableRowBreadthTest
{

    /** The three stored fields that are lists, and can therefore never be row values. */
    private static final Set<String> LIST_VALUED = Set.of("valueList", "codelistSubmissionValues",
            "codelistIds");

    // ------------------------------------------------------------------
    // Fixtures — shaped after the real payloads (audit §3, re-measured
    // 2026-09-08 over /data/cdisc.metadata.library-cache-pkl):
    // * model variables carry roleDescription/definition/notes/examples/
    // usageRestrictions/variableCcode and NO core;
    // * IG dataset variables carry core/codelistSubmissionValues and no
    // notes/examples;
    // * ADaM analysis variables carry core and no role.
    // ------------------------------------------------------------------

    /** A model variable populated with every scalar AND every list field the store can hold. */
    private static StoredVariable fullyPopulatedModelVar(String aName)
    {
        return StoredVariable.builder().name(aName).label("Reported Term").ordinal("5")
                .role("Topic").simpleDatatype("Char").description("The term as reported.")
                .roleDescription("Topic variable").definition("A textual description of an event.")
                .notes("ISO 8601 not applicable.").examples("HEADACHE; NAUSEA")
                .usageRestrictions("None").variableCcode("C49487")
                .describedValueDomain("Sponsor-defined")
                // The three list-valued fields — present in the SOURCE, and deliberately not
                // reachable as row keys.
                .valueList(List.of("AE", "CM")).codelistSubmissionValues(List.of("MILD", "SEVERE"))
                .codelistIds(List.of("C66731")).build();
    }


    private static StoredVariable modelVar(String aName, String aRole, String aNotes)
    {
        return StoredVariable.builder().name(aName).label(aName + " label").ordinal("9").role(aRole)
                .simpleDatatype("Char").notes(aNotes).definition("def of " + aName).build();
    }


    private static StoredVariable igVar(String aName, String aRole, String aCore)
    {
        return StoredVariable.builder().name(aName).label(aName + " IG label").ordinal("9")
                .role(aRole).simpleDatatype("Char").core(aCore).description("IG description")
                .codelistSubmissionValues(List.of("MILD")).build();
    }


    /** SDTM Model product: GENERAL OBSERVATIONS identifiers + an EVENTS class. */
    private static StoredProduct modelProduct()
    {
        List<StoredVariable> genObs = List.of(modelVar("STUDYID", "Identifier", "Model note."),
                modelVar("USUBJID", "Identifier", "Model note."));
        List<StoredVariable> events = List.of(fullyPopulatedModelVar("--TERM"),
                modelVar("--DECOD", "Synonym Qualifier", "Model-only: the IG omits this one."));
        return StoredProduct.builder().key("models/sdtm/2-0").name("SDTM").version("2-0")
                .classes(List.of(
                        new StoredClass("General Observations", "General Observations", "1", genObs,
                                List.of()),
                        new StoredClass("Events", "Events", "2", events, List.of())))
                .build();
    }


    /** SDTMIG product: the AE dataset under EVENTS, carrying AETERM but NOT AEDECOD. */
    private static StoredProduct igProduct()
    {
        StoredDataset ae = new StoredDataset("AE", "Adverse Events", "1", "",
                List.of(igVar("AETERM", "Topic", "Req")));
        // A non-detectable class with an EMPTY model class and an IG dataset — the Model walk's
        // tier-3 fallback (buildResolvedSdtmModel) answers this one from the IG dataset.
        StoredDataset ta = new StoredDataset("TA", "Trial Arms", "2", "",
                List.of(igVar("ARMCD", "Topic", "Req")));
        return StoredProduct.builder().key("standards/sdtmig/3-4").name("SDTMIG").version("3-4")
                .classes(List.of(new StoredClass("Events", "Events", "1", List.of(), List.of(ae)),
                        new StoredClass("Trial Design", "Trial Design", "2", List.of(),
                                List.of(ta))))
                .build();
    }


    /** ADaM product: analysis variables carry {@code core} and no {@code role}. */
    private static StoredProduct adamProduct()
    {
        StoredVariable trtp = StoredVariable.builder().name("TRTP").label("Planned Treatment")
                .ordinal("1").simpleDatatype("Char").core("Req").description("ADaM description")
                .describedValueDomain("Sponsor-defined").build();
        return StoredProduct
                .builder().key("standards/adam/adamig-1-3").name(
                        "ADaMIG")
                .version("1-3")
                .dataStructures(List.of(new StoredDataStructure("ADSL", "Subject Level", "1",
                        "ADSL", null, List.of(new StoredVariableSet("Identifiers", "Identifiers",
                                "1", List.of(trtp))))))
                .build();
    }


    private static IDataTable mock(String aDomain)
    {
        return MockTable.of().name(aDomain).col("DOMAIN", aDomain).build();
    }


    private static MetadataLibraryProvider sdtm(String... aDomains)
    {
        var builder = lib("study");
        for (String d : aDomains)
        {
            builder = builder.table(table(d).build());
        }
        IMetadataLibrary study = builder.build();
        return MetadataLibraryProvider.forStoredSdtm(study, igProduct(), modelProduct(), "sdtmig",
                "3-4");
    }


    private static Map<String, String> row(List<Map<String, String>> aRows, String aName)
    {
        for (Map<String, String> r : aRows)
        {
            if (aName.equals(r.get("name")))
            {
                return r;
            }
        }
        throw new AssertionError("no row named " + aName + " in " + aRows);
    }

    // ------------------------------------------------------------------
    // P3 — the widened Model row
    // ------------------------------------------------------------------


    @Test
    @DisplayName("⭐ the Model walk publishes every scalar the model product carried")
    void theModelWalkPublishesEveryScalar()
    {
        List<Map<String, String>> rows = sdtm("AE").getStandardModelVariablesDetailed(mock("AE"),
                null);
        assertNotNull(rows);
        Map<String, String> term = row(rows, "AETERM");
        // The historical six.
        assertEquals("AETERM", term.get("name"));
        assertEquals("Topic", term.get("role"));
        assertEquals("Char", term.get("simpleDatatype"));
        assertEquals("Reported Term", term.get("label"));
        assertEquals("5", term.get("ordinal"));
        // ⭐ The eight P3 added. Every one of these used to be absent, so a key_name naming it
        // matched nothing on every dataset — silently.
        assertEquals("The term as reported.", term.get("description"));
        assertEquals("Topic variable", term.get("roleDescription"));
        assertEquals("A textual description of an event.", term.get("definition"));
        assertEquals("ISO 8601 not applicable.", term.get("notes"));
        assertEquals("HEADACHE; NAUSEA", term.get("examples"));
        assertEquals("None", term.get("usageRestrictions"));
        assertEquals("C49487", term.get("variableCcode"));
        assertEquals("Sponsor-defined", term.get("describedValueDomain"));
    }


    @Test
    @DisplayName("⛔ the list-valued source fields are never row keys, on any walk")
    void listValuedFieldsAreNeverRowKeys()
    {
        // The fixture variable carries all three in the SOURCE. A row is Map<String,String>, and
        // Python's `var.get("valueList") == "<scalar>"` compares a list to a string — always
        // false. So they are excluded here and rejected at rule load (KeyNameDeclarationTest),
        // rather than encoded into some invented scalar form.
        List<Map<String, String>> model = sdtm("AE").getStandardModelVariablesDetailed(mock("AE"),
                null);
        List<Map<String, String>> ig = sdtm("AE").getStandardVariablesDetailed(mock("AE"), null);
        assertNotNull(model);
        assertNotNull(ig);
        for (List<Map<String, String>> rows : List.of(model, ig))
        {
            for (Map<String, String> r : rows)
            {
                for (String listField : LIST_VALUED)
                {
                    assertFalse(r.containsKey(listField), listField + " in " + r);
                }
            }
        }
        // …and the IG row really did carry codelistSubmissionValues in its source.
        assertEquals("Req", row(ig, "AETERM").get("core"));
    }

    // ------------------------------------------------------------------
    // ⛔ One vocabulary, not one per level — both directions
    // ------------------------------------------------------------------


    @Test
    @DisplayName("⛔ a model-only key such as `notes` IS served by the IG-level walk")
    void theIgWalkPublishesTheModelOnlyScalars()
    {
        // buildResolvedSdtm builds its identifier / class / timing buckets from the MODEL
        // product's classVariables and merely overrides them by name with the IG dataset's. The
        // IG's AE dataset has no AEDECOD, so the model row survives — with its model attributes.
        // A per-level vocabulary that rejected `notes` on get_dataset_filtered_variables would
        // therefore forbid a filter the engine answers.
        List<Map<String, String>> rows = sdtm("AE").getStandardVariablesDetailed(mock("AE"), null);
        assertNotNull(rows);
        Map<String, String> decod = row(rows, "AEDECOD");
        assertEquals("Model-only: the IG omits this one.", decod.get("notes"));
        assertEquals("def of --DECOD", decod.get("definition"));
        // The overridden one carries the IG's attributes instead — the merge is by whole row.
        assertEquals("Req", row(rows, "AETERM").get("core"));
        assertFalse(row(rows, "AETERM").containsKey("notes"));
    }


    @Test
    @DisplayName("⛔ an IG/ADaM key such as `core` IS served by the Model-level walk")
    void theModelWalkPublishesIgOnlyCore()
    {
        // Tier 3 of buildResolvedSdtmModel: a non-detectable domain whose model class and model
        // dataset are both empty falls through to the IG dataset's variables. This is one of the
        // three routes (with the SUPP--/SQ-- cascade and ADaM) by which `core` reaches the Model
        // walk — the reason P1 refused to make get_model_filtered_variables(key_name="core") a
        // load error, and it must stay true after the widening.
        List<Map<String, String>> rows = sdtm("TA").getStandardModelVariablesDetailed(mock("TA"),
                null);
        assertNotNull(rows);
        assertEquals("Req", row(rows, "ARMCD").get("core"));
        assertEquals("IG description", row(rows, "ARMCD").get("description"));

        // ADaM is the other route, and symmetrically carries no `role` at all.
        IMetadataLibrary study = lib("study").table(table("ADSL").build()).build();
        MetadataLibraryProvider adam = MetadataLibraryProvider.forStoredAdam(study, adamProduct(),
                "adamig", "1-3");
        List<Map<String, String>> adamRows = adam.getStandardModelVariablesDetailed(mock("ADSL"),
                null);
        assertNotNull(adamRows);
        Map<String, String> trtp = row(adamRows, "TRTP");
        assertEquals("Req", trtp.get("core"));
        assertEquals("ADaM description", trtp.get("description"));
        assertEquals("Sponsor-defined", trtp.get("describedValueDomain"));
        assertFalse(trtp.containsKey("role"), trtp.toString());
    }

    // ------------------------------------------------------------------
    // The vocabulary is derived, not typed
    // ------------------------------------------------------------------


    @Test
    @DisplayName("⭐ LibraryVariableAttributes.KEYS is exactly what the resolver publishes")
    void theVocabularyIsExactlyWhatTheResolverPublishes()
    {
        // Derived from real walks — NOT from a list typed into the test. If the resolver starts
        // publishing a key, or stops, this reds without anyone having to remember to update a
        // constant.
        //
        // ⚠ It takes TWO walks, and that is the finding rather than an inconvenience: no single
        // row can carry the whole vocabulary, because no single product publishes it. The model
        // variable holds all thirteen model-side scalars and NOT `core`; the IG-sourced row holds
        // `core`. Their union is the vocabulary — which is precisely why the servable set is a
        // per-dataset runtime fact and the load-time set is their union.
        List<Map<String, String>> modelRows = sdtm("AE")
                .getStandardModelVariablesDetailed(mock("AE"), null);
        List<Map<String, String>> igSourced = sdtm("TA")
                .getStandardModelVariablesDetailed(mock("TA"), null);
        assertNotNull(modelRows);
        assertNotNull(igSourced);
        assertEquals(13, row(modelRows, "AETERM").size(), row(modelRows, "AETERM").toString());
        Set<String> published = new LinkedHashSet<>(row(modelRows, "AETERM").keySet());
        published.addAll(row(igSourced, "ARMCD").keySet());
        assertEquals(new TreeSet<>(LibraryVariableAttributes.KEYS), new TreeSet<>(published));

        // And the closed set is exactly the store's scalar fields: every StoredVariable component
        // is either a row key or one of the three list-valued exclusions. A field added to the
        // record without a decision here reds.
        Set<String> components = new LinkedHashSet<>();
        for (var component : StoredVariable.class.getRecordComponents())
        {
            components.add(component.getName());
        }
        Set<String> expected = new TreeSet<>(LibraryVariableAttributes.KEYS);
        expected.addAll(LIST_VALUED);
        assertEquals(expected, new TreeSet<>(components));
    }


    @Test
    @DisplayName("the runtime diagnostic stays silent for a key this level now serves")
    void aNewlyServedKeyIsNotWarnedAbout()
    {
        // The complement of UnservedKeyNameDiagnosticTest: before P3 every one of these keys was
        // absent from every row, so warnUnservedKeyName would have fired on all of them.
        List<Map<String, String>> rows = sdtm("AE").getStandardModelVariablesDetailed(mock("AE"),
                null);
        assertNotNull(rows);
        List<String> unserved = new ArrayList<>();
        for (String key : List.of("description", "roleDescription", "definition", "notes",
                "examples", "usageRestrictions", "variableCcode", "describedValueDomain"))
        {
            if (!LibraryVariableAttributes.carriedByAny(rows, key))
            {
                unserved.add(key);
            }
        }
        assertEquals(List.of(), unserved);
        assertTrue(
                LibraryVariableAttributes.publishedKeys(rows)
                        .containsAll(List.of("notes", "examples", "definition", "variableCcode")),
                LibraryVariableAttributes.publishedKeys(rows).toString());
    }
}
