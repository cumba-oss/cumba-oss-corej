package net.cumba.corej.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.library.api.model.sdtm.SdtmProduct;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.testkit.TestMetadataFixtures;
import net.cumba.web.api.dev.MapResource;
import org.junit.jupiter.api.Test;

/**
 * The IG-override merge of {@code buildResolvedSdtm} (F-corej-L2-01, work-order rows M-02 / M-03 /
 * M-09 / B-14).
 *
 * <p>
 * On the production SDTMIG path the model buckets contain <b>duplicate</b> names: the
 * General-Observations identifiers (STUDYID, USUBJID, …) reappear in a self-contained IG class's
 * own classVariables list. The IG override must land on the occurrence that survives the keep-first
 * dedupe — with a last-wins name index it landed on the LAST duplicate, which the dedupe then
 * discarded, so the IG's attributes (core, label, datatype) were silently replaced by the SDTM
 * Model's. These tests observe the merge through the public accessors that every metadata rule is
 * answered from: {@code getRequiredVariables}, {@code getColumnOrder}, {@code getVariableMetadata}.
 * </p>
 */
class MetadataLibraryProviderIgOverrideMergeTest
{

    private static Map<String, Object> var(String name, String label, String ordinal, String role,
            String core)
    {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("name", name);
        v.put("label", label);
        v.put("ordinal", ordinal);
        v.put("simpleDatatype", "Char");
        v.put("role", role);
        if (core != null)
        {
            v.put("core", core);
        }
        return v;
    }


    private static Map<String, Object> dataset(String name, List<Map<String, Object>> vars)
    {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", name);
        d.put("label", name + " label");
        d.put("datasetStructure", "One record per something");
        d.put("datasetVariables", vars);
        return d;
    }


    private static Map<String, Object> sdtmClass(String name, List<Map<String, Object>> classVars,
            List<Map<String, Object>> datasets)
    {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", name);
        c.put("classVariables", classVars);
        c.put("datasets", datasets);
        return c;
    }


    /**
     * The distinguishing fixture from the finding: the General-Observations identifiers STUDYID /
     * USUBJID (no {@code core} — the Model does not publish one) reappear in the Events class's own
     * classVariables, and the AE IG dataset overrides them with {@code core="Req"}.
     */
    private static SdtmProduct product(List<Map<String, Object>> aeIgVars)
    {
        List<Map<String, Object>> genObsVars = new ArrayList<>();
        genObsVars.add(var("STUDYID", "Study Identifier", "1", "Identifier", null));
        genObsVars.add(var("USUBJID", "Unique Subject Identifier", "2", "Identifier", null));

        // Self-contained IG class: repeats the GenObs identifiers, then its own topic variable.
        List<Map<String, Object>> eventsVars = new ArrayList<>();
        eventsVars.add(var("STUDYID", "Study Identifier", "1", "Identifier", null));
        eventsVars.add(var("USUBJID", "Unique Subject Identifier", "2", "Identifier", null));
        eventsVars.add(var("--TERM", "Reported Term", "3", "Topic", null));

        Map<String, Object> genObs = sdtmClass("General Observations", genObsVars, List.of());
        Map<String, Object> events = sdtmClass("Events", eventsVars,
                List.of(dataset("AE", aeIgVars)));

        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", "SDTMIG");
        product.put("version", "3-4");
        product.put("classes", List.of(genObs, events));
        return MapResource.of(product, SdtmProduct.class);
    }


    private static MetadataLibraryProvider provider(SdtmProduct aProduct)
    {
        IMetadataLibrary study = TestMetadataFixtures.lib("study").build();
        return new MetadataLibraryProvider(study, aProduct, "sdtmig", "3-4");
    }


    /**
     * M-02 / M-03: the IG row for a DUPLICATED name must be the one that survives the keep-first
     * dedupe. Observable through {@code core}: only the IG rows carry {@code core="Req"}, so a
     * merge that lets the Model copy win empties {@code getRequiredVariables} of the identifiers.
     */
    @Test
    void igOverrideOnDuplicatedIdentifier_survivesTheKeepFirstDedupe()
    {
        MetadataLibraryProvider provider = provider(
                product(List.of(var("STUDYID", "Study Identifier", "1", "Identifier", "Req"),
                        var("USUBJID", "Unique Subject Identifier", "2", "Identifier", "Req"),
                        var("AETERM", "Reported Term", "3", "Topic", "Req"))));

        // All three IG rows say Req; before the fix STUDYID/USUBJID reported the Model's
        // core-less copy and vanished from the required list.
        assertEquals(List.of("STUDYID", "USUBJID", "AETERM"), provider.getRequiredVariables("AE"));
        // Merge order is unchanged by the fix: identifiers first, then class variables.
        assertEquals(List.of("STUDYID", "USUBJID", "AETERM"), provider.getColumnOrder("AE"));
        // Attribute CONTENT: the surviving STUDYID is the IG row, not the Model row.
        Map<String, String> studyid = provider.getVariableMetadata("AE", "STUDYID");
        assertEquals("Req", studyid.get("core"),
                "the IG-declared core must survive the merge; the Model copy carries none");
        assertEquals("Study Identifier", studyid.get("label"));
    }


    /**
     * M-09: an IG-only insert shifts every later index; the rebuilt index must be rebuilt at all.
     * With a stale index the following override lands on the wrong row and silently clobbers the
     * just-inserted IG-only variable. Duplicate-free buckets on purpose: a duplicate would absorb
     * the stale write and mask the defect.
     */
    @Test
    void igOnlyInsertThenOverride_keepsBothRows()
    {
        List<Map<String, Object>> genObsVars = List
                .of(var("STUDYID", "Study Identifier", "1", "Identifier", null));
        Map<String, Object> genObs = sdtmClass("General Observations", genObsVars, List.of());
        Map<String, Object> events = sdtmClass("Events",
                List.of(var("--TERM", "Reported Term", "3", "Topic", null)),
                List.of(dataset("AE", List.of(
                        // IG-only Identifier — in no model bucket, inserted after the identifiers.
                        var("AENEWID", "Sponsor Identifier", "1", "Identifier", "Req"),
                        // Override of the model "--TERM" (substituted to AETERM), processed AFTER
                        // the insert — with a stale index it overwrites AENEWID's slot instead.
                        var("AETERM", "Reported Term", "3", "Topic", "Req")))));
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", "SDTMIG");
        product.put("version", "3-4");
        product.put("classes", List.of(genObs, events));

        MetadataLibraryProvider provider = provider(MapResource.of(product, SdtmProduct.class));

        assertEquals(List.of("STUDYID", "AENEWID", "AETERM"), provider.getColumnOrder("AE"));
        assertEquals(List.of("AENEWID", "AETERM"), provider.getRequiredVariables("AE"));
        assertEquals("Req", provider.getVariableMetadata("AE", "AETERM").get("core"));
    }


    /**
     * B-11: the FINDINGS-ABOUT merge inserts the FA class variables between the FINDINGS
     * {@code --TEST} and {@code --ORRES}, and that mixed-ordinal order must survive both the
     * IG-override merge and the keep-first dedupe un-re-sorted.
     */
    @Test
    void findingsAboutMerge_ordersFaVariablesAfterTest_andIgOverrideSurvives()
    {
        Map<String, Object> genObs = sdtmClass("General Observations",
                List.of(var("STUDYID", "Study Identifier", "1", "Identifier", null)), List.of());
        Map<String, Object> findings = sdtmClass("Findings",
                List.of(var("--TEST", "Name of Measurement", "1", "Topic", null),
                        var("--ORRES", "Result or Finding", "2", "Result Qualifier", null)),
                List.of());
        Map<String, Object> findingsAbout = sdtmClass("Findings About",
                List.of(var("--OBJ", "Object of the Observation", "1", "Record Qualifier", null)),
                List.of(dataset("FA", List.of(var("FAOBJ", "Object of the Observation", "1",
                        "Record Qualifier", "Req")))));
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", "SDTMIG");
        product.put("version", "3-4");
        product.put("classes", List.of(genObs, findings, findingsAbout));

        MetadataLibraryProvider provider = provider(MapResource.of(product, SdtmProduct.class));

        // FAOBJ sits BETWEEN FATEST and FAORRES — the FA merge's order, not ordinal order.
        assertEquals(List.of("STUDYID", "FATEST", "FAOBJ", "FAORRES"),
                provider.getColumnOrder("FA"));
        // And the IG override of FAOBJ survives the dedupe: only the IG row carries core=Req.
        assertEquals(List.of("FAOBJ"), provider.getRequiredVariables("FA"));
        assertEquals("Req", provider.getVariableMetadata("FA", "FAOBJ").get("core"));
    }


    /**
     * B-12: an AP-prefixed domain merges the ASSOCIATED PERSONS identifiers into the identifier
     * bucket — except USUBJID, whose General-Observations row must keep winning (position AND
     * attributes) over the AP class's own copy.
     */
    @Test
    void apDomain_mergesApIdentifiersExceptUsubjid()
    {
        Map<String, Object> genObs = sdtmClass("General Observations",
                List.of(var("STUDYID", "Study Identifier", "1", "Identifier", null),
                        var("USUBJID", "Unique Subject Identifier", "2", "Identifier", null)),
                List.of());
        Map<String, Object> events = sdtmClass("Events",
                List.of(var("--TERM", "Reported Term", "3", "Topic", null)), List.of(dataset("AE",
                        List.of(var("AETERM", "Reported Term", "3", "Topic", "Req")))));
        Map<String, Object> ap = sdtmClass("Associated Persons",
                List.of(var("USUBJID", "AP Unique Subject Identifier", "0", "Identifier", null),
                        var("APID", "Associated Persons Identifier", "3", "Identifier", null)),
                List.of());
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", "SDTMIG");
        product.put("version", "3-4");
        product.put("classes", List.of(genObs, events, ap));

        MetadataLibraryProvider provider = provider(MapResource.of(product, SdtmProduct.class));

        // APID is merged in; the AP USUBJID (ordinal 0 — it would sort FIRST and win the
        // keep-first dedupe) is excluded, so the GenObs USUBJID keeps position 2 and its label.
        assertEquals(List.of("STUDYID", "USUBJID", "APID", "AETERM"),
                provider.getColumnOrder("APAE"));
        assertEquals("Unique Subject Identifier",
                provider.getVariableMetadata("APAE", "USUBJID").get("label"));
        assertEquals("Req", provider.getVariableMetadata("APAE", "AETERM").get("core"));
    }


    /**
     * B-14: the non-detectable, non-custom branch bypasses the merge entirely — the IG dataset's
     * own variables ARE the answer; class variables do not leak in.
     */
    @Test
    void nonDetectableClass_answersWithIgVariablesOnly()
    {
        List<Map<String, Object>> spVars = new ArrayList<>();
        spVars.add(var("MODELONLY", "Model Only Variable", "1", "Identifier", null));
        Map<String, Object> specialPurpose = sdtmClass("Special Purpose", spVars,
                List.of(dataset("DM", List.of(
                        var("STUDYID", "Study Identifier", "1", "Identifier", "Req"),
                        var("SUBJID", "Subject Identifier for the Study", "2", "Topic", "Req")))));
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", "SDTMIG");
        product.put("version", "3-4");
        product.put("classes", List.of(specialPurpose));

        MetadataLibraryProvider provider = provider(MapResource.of(product, SdtmProduct.class));

        assertEquals(List.of("STUDYID", "SUBJID"), provider.getColumnOrder("DM"));
        assertTrue(provider.getRequiredVariables("DM").contains("SUBJID"));
    }


    /**
     * The rebuild loop's own pin (M-09 × M-02). An IG-only variable ({@code AENEWID}) forces the
     * index REBUILD, and the IG row processed right after it overrides a <b>duplicated</b> name:
     * {@code STUDYID} appears both as a General-Observations identifier and again in the Events
     * class's own classVariables. With a last-wins rebuild the override lands on the second
     * {@code STUDYID} copy, which {@code dedupeByNameKeepFirst} then discards, so the surviving row
     * is the core-less Model copy and {@code STUDYID} drops out of the required list. No other case
     * in this class has BOTH a duplicated name and a preceding IG-only insert, so reverting only
     * the rebuild loop leaves all of them green — this is the one that catches it.
     */
    @Test
    void igOnlyInsertThenOverrideOfDuplicatedName_survivesTheRebuild()
    {
        MetadataLibraryProvider provider = provider(
                product(List.of(var("AENEWID", "Sponsor Identifier", "1", "Identifier", "Req"),
                        var("STUDYID", "Study Identifier", "1", "Identifier", "Req"))));

        List<String> required = provider.getRequiredVariables("AE");
        assertTrue(required.contains("STUDYID"),
                "the IG override of the duplicated STUDYID must land on the copy that survives "
                        + "the keep-first dedupe; required was " + required);
        assertTrue(required.contains("AENEWID"), required.toString());
        assertEquals("Req", provider.getVariableMetadata("AE", "STUDYID").get("core"),
                "the surviving STUDYID must be the IG row, not the Model copy");
    }

}
