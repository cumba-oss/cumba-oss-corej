package net.cumba.corej.core.metadata;

import static net.cumba.datatable.testkit.TestMetadataFixtures.column;
import static net.cumba.datatable.testkit.TestMetadataFixtures.lib;
import static net.cumba.datatable.testkit.TestMetadataFixtures.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.library.api.model.adam.AdamProduct;
import net.cumba.cdisc.library.api.model.sdtm.SdtmProduct;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.values.DataValueType;
import net.cumba.web.api.dev.MapResource;
import org.junit.jupiter.api.Test;

/**
 * Fix #42 Phase 2 — coverage for {@link MetadataLibraryProvider#getStandardModelVariables}.
 *
 * <p>
 * The Phase 2 resolver mirrors Python's {@code get_variables_metadata_from_standard_model}:
 * effective-domain computation (SUPP/SQ → SUPPQUAL, AP-prefix stripping), class lookup via product
 * reverse-walk or Fix #41 sniffer, GENERAL OBSERVATIONS merge for detectable classes, AP-identifier
 * merge, and {@code --} wildcard substitution. Each test below covers one branch.
 * </p>
 */
class StandardModelVariablesResolverTest
{

    // ------------------------------------------------------------------
    // SDTM product fixture — covers all branches the tests exercise:
    // * GENERAL OBSERVATIONS class with Identifier + Timing variables
    // * EVENTS / INTERVENTIONS / FINDINGS / FINDINGS ABOUT classes
    // * SUPPQUAL class
    // * ASSOCIATED PERSONS class
    // ------------------------------------------------------------------

    private static Map<String, Object> sdtmVar(String name, String label, String ordinal,
            String dtype, String core, String role)
    {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("name", name);
        v.put("label", label);
        v.put("ordinal", ordinal);
        v.put("simpleDatatype", dtype);
        v.put("core", core);
        if (role != null)
        {
            v.put("role", role);
        }
        return v;
    }


    private static Map<String, Object> sdtmVar(String name, String label, String ordinal,
            String dtype, String core)
    {
        return sdtmVar(name, label, ordinal, dtype, core, null);
    }


    private static Map<String, Object> sdtmDataset(String name, String label, String structure)
    {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", name);
        d.put("label", label);
        d.put("datasetStructure", structure);
        d.put("datasetVariables", List.of());
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
     * Builds a richer SDTM product with GENERAL OBSERVATIONS shared variables, EVENTS, FINDINGS,
     * FINDINGS ABOUT, INTERVENTIONS, SUPPQUAL and ASSOCIATED PERSONS classes — enough surface to
     * exercise all five Phase 2 pipeline steps.
     */
    private static SdtmProduct mkRichSdtmProduct()
    {
        // GENERAL OBSERVATIONS class — provides Identifier (STUDYID, USUBJID) + Timing (--DTC).
        List<Map<String, Object>> genObsVars = new ArrayList<>();
        genObsVars.add(sdtmVar("STUDYID", "Study Identifier", "1", "Char", "Req", "Identifier"));
        genObsVars.add(
                sdtmVar("USUBJID", "Unique Subject Identifier", "2", "Char", "Req", "Identifier"));
        genObsVars.add(sdtmVar("--SEQ", "Sequence Number", "3", "Num", "Req", "Identifier"));
        genObsVars.add(sdtmVar("--DTC", "Date/Time", "10", "Char", "Perm", "Timing"));
        Map<String, Object> genObs = sdtmClass("General Observations", genObsVars, List.of());

        // EVENTS class — class-specific variables.
        List<Map<String, Object>> eventsVars = new ArrayList<>();
        eventsVars.add(sdtmVar("--TERM", "Reported Term", "5", "Char", "Req"));
        eventsVars.add(sdtmVar("--DECOD", "Standardized Term", "6", "Char", "Perm"));
        Map<String, Object> ae = sdtmDataset("AE", "Adverse Events", "");
        Map<String, Object> events = sdtmClass("Events", eventsVars, List.of(ae));

        // INTERVENTIONS class.
        List<Map<String, Object>> interventionsVars = new ArrayList<>();
        interventionsVars
                .add(sdtmVar("--TRT", "Reported Name of Intervention", "5", "Char", "Req"));
        interventionsVars.add(sdtmVar("--DOSE", "Dose per Administration", "6", "Num", "Perm"));
        Map<String, Object> cm = sdtmDataset("CM", "Concomitant Medication", "");
        Map<String, Object> interventions = sdtmClass("Interventions", interventionsVars,
                List.of(cm));

        // FINDINGS class — class-specific variables. --TEST exists for FINDINGS ABOUT splice.
        List<Map<String, Object>> findingsVars = new ArrayList<>();
        findingsVars.add(sdtmVar("--TESTCD", "Test Code", "5", "Char", "Req"));
        findingsVars.add(sdtmVar("--TEST", "Test Name", "6", "Char", "Req"));
        findingsVars.add(sdtmVar("--ORRES", "Result or Finding", "7", "Char", "Req"));
        Map<String, Object> lb = sdtmDataset("LB", "Laboratory Test Results", "");
        Map<String, Object> findings = sdtmClass("Findings", findingsVars, List.of(lb));

        // FINDINGS ABOUT class — adds --OBJ between TEST and ORRES.
        List<Map<String, Object>> faVars = new ArrayList<>();
        faVars.add(sdtmVar("--OBJ", "Object of the Observation", "8", "Char", "Req"));
        Map<String, Object> fa = sdtmDataset("FA", "Findings About", "");
        Map<String, Object> findingsAbout = sdtmClass("Findings About", faVars, List.of(fa));

        // SUPPQUAL class — supplemental qualifiers. Fix #61's three-tier cascade resolves SUPP/SQ
        // domains via the IG's SUPPQUAL dataset (tier A) before falling back to the SDTM Model's
        // RELATIONSHIP class (tier B) and the hardcoded canonical list (tier C). Modern IG payloads
        // embed the variables on the SUPPQUAL dataset, so we model the same shape here.
        List<Map<String, Object>> suppqualVars = new ArrayList<>();
        suppqualVars.add(sdtmVar("STUDYID", "Study Identifier", "1", "Char", "Req", "Identifier"));
        suppqualVars.add(sdtmVar("RDOMAIN", "Related Domain", "2", "Char", "Req", "Identifier"));
        suppqualVars.add(
                sdtmVar("USUBJID", "Unique Subject Identifier", "3", "Char", "Req", "Identifier"));
        suppqualVars.add(sdtmVar("IDVAR", "Identifying Variable", "4", "Char", "Req"));
        suppqualVars.add(sdtmVar("QNAM", "Qualifier Name", "5", "Char", "Req"));
        suppqualVars.add(sdtmVar("QVAL", "Data Value", "6", "Char", "Req"));
        Map<String, Object> suppqualDataset = sdtmDataset("SUPPQUAL", "Supplemental Qualifiers",
                "");
        suppqualDataset.put("datasetVariables", suppqualVars);
        Map<String, Object> suppqual = sdtmClass("SUPPQUAL", suppqualVars,
                List.of(suppqualDataset));

        // ASSOCIATED PERSONS class — its identifiers (excluding USUBJID) are merged when add_AP.
        List<Map<String, Object>> apVars = new ArrayList<>();
        apVars.add(
                sdtmVar("APID", "Associated Person Identifier", "11", "Char", "Req", "Identifier"));
        apVars.add(
                sdtmVar("USUBJID", "Unique Subject Identifier", "12", "Char", "Req", "Identifier"));
        Map<String, Object> apClass = sdtmClass("Associated Persons", apVars, List.of());

        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", "SDTMIG");
        product.put("version", "3-4");
        product.put("classes",
                List.of(genObs, events, interventions, findings, findingsAbout, suppqual, apClass));
        return MapResource.of(product, SdtmProduct.class);
    }


    /**
     * ADaM product: ADSL (Subject Level Analysis Dataset) and one BDS-class data structure named
     * BDS used to exercise the className-fallback path for ADAE.
     */
    private static AdamProduct mkAdamProduct()
    {
        List<Map<String, Object>> adslIds = new ArrayList<>();
        adslIds.add(sdtmVar("STUDYID", "Study Identifier", "1", "Char", "Req"));
        adslIds.add(sdtmVar("USUBJID", "Unique Subject Identifier", "2", "Char", "Req"));
        adslIds.add(sdtmVar("AGE", "Age", "3", "Num", "Req"));

        Map<String, Object> adslIdSet = new LinkedHashMap<>();
        adslIdSet.put("name", "Identifiers");
        adslIdSet.put("ordinal", "1");
        adslIdSet.put("analysisVariables", adslIds);

        Map<String, Object> adsl = new LinkedHashMap<>();
        adsl.put("name", "ADSL");
        adsl.put("label", "Subject Level Analysis Dataset");
        adsl.put("class", "SUBJECT LEVEL ANALYSIS DATASET");
        adsl.put("analysisVariableSets", List.of(adslIdSet));

        // BDS variable set
        List<Map<String, Object>> bdsVars = new ArrayList<>();
        bdsVars.add(sdtmVar("STUDYID", "Study Identifier", "1", "Char", "Req"));
        bdsVars.add(sdtmVar("USUBJID", "Unique Subject Identifier", "2", "Char", "Req"));
        bdsVars.add(sdtmVar("PARAMCD", "Parameter Code", "10", "Char", "Req"));
        bdsVars.add(sdtmVar("AVAL", "Analysis Value", "20", "Num", "Cond"));

        Map<String, Object> bdsSet = new LinkedHashMap<>();
        bdsSet.put("name", "Identifiers and Analysis");
        bdsSet.put("ordinal", "1");
        bdsSet.put("analysisVariables", bdsVars);

        Map<String, Object> bds = new LinkedHashMap<>();
        bds.put("name", "BDS");
        bds.put("label", "Basic Data Structure");
        bds.put("class", "BASIC DATA STRUCTURE");
        bds.put("analysisVariableSets", List.of(bdsSet));

        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", "ADaMIG");
        product.put("version", "1-3");
        product.put("dataStructures", List.of(adsl, bds));
        return MapResource.of(product, AdamProduct.class);
    }


    private static IDataTable mockTable(String name)
    {
        IDataTable table = mock(IDataTable.class);
        DataTableMeta meta = mock(DataTableMeta.class);
        lenient().when(meta.getName()).thenReturn(name);
        lenient().when(meta.getColumnIndex("DOMAIN")).thenReturn(-1);
        lenient().when(table.getMetaData()).thenReturn(meta);
        lenient().when(table.getRowCount()).thenReturn(0L);
        return table;
    }


    /**
     * Builds a mock {@link IDataTable} whose member name and CDISC domain code differ — the Fix #59
     * split-dataset shape (e.g. {@code LBHE} member with {@code DOMAIN}=`LB`).
     */
    private static IDataTable mockSplitTable(String memberName, String domainColumnValue)
    {
        IDataTable table = mock(IDataTable.class);
        DataTableMeta meta = mock(DataTableMeta.class);
        lenient().when(meta.getName()).thenReturn(memberName);
        lenient().when(meta.getColumnIndex("DOMAIN")).thenReturn(3);
        lenient().when(table.getMetaData()).thenReturn(meta);
        lenient().when(table.getRowCount()).thenReturn(1L);
        net.cumba.datatable.values.IDataValue dv = mock(
                net.cumba.datatable.values.IDataValue.class);
        lenient().when(dv.isMissingOrInvalid()).thenReturn(false);
        lenient().when(dv.getValueAsString()).thenReturn(domainColumnValue);
        net.cumba.datatable.IDataTableColumn col = mock(net.cumba.datatable.IDataTableColumn.class);
        lenient().when(col.getDataValue(org.mockito.ArgumentMatchers.anyLong())).thenReturn(dv);
        lenient().when(table.getColumn(3)).thenReturn(col);
        return table;
    }

    // ------------------------------------------------------------------
    // SDTM — standard non-custom domain
    // ------------------------------------------------------------------


    @Test
    void standardSdtmFindingsDomain_returnsIdentifiersClassVarsAndTiming_wildcardSubstituted()
    {
        // LB ⇒ Findings class. Identifiers (STUDYID, USUBJID, --SEQ) + class vars
        // (--TESTCD, --TEST, --ORRES) + timing (--DTC). All `--` wildcards substituted to LB.
        IMetadataLibrary study = lib("study").table(table("LB").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkRichSdtmProduct(),
                "sdtmig", "3-4");

        List<String> result = provider.getStandardModelVariables(mockTable("LB"), null);

        assertNotNull(result);
        assertEquals(
                List.of("STUDYID", "USUBJID", "LBSEQ", "LBTESTCD", "LBTEST", "LBORRES", "LBDTC"),
                result);
    }


    @Test
    void standardSdtmEventsDomain_returnsIdentifiersClassVarsAndTiming_wildcardSubstituted()
    {
        IMetadataLibrary study = lib("study").table(table("AE").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkRichSdtmProduct(),
                "sdtmig", "3-4");

        List<String> result = provider.getStandardModelVariables(mockTable("AE"), null);

        // EVENTS-only class vars: --TERM, --DECOD; identifiers from GenObs; timing --DTC.
        assertEquals(List.of("STUDYID", "USUBJID", "AESEQ", "AETERM", "AEDECOD", "AEDTC"), result);
    }

    // ------------------------------------------------------------------
    // SDTM — custom domain (Fix #41 sniffer drives class)
    // ------------------------------------------------------------------


    @Test
    void customEventsShapeDomain_sniffsToEvents_returnsEventsClassVariables()
    {
        // MYAE has DOMAIN + MYAETERM → sniffer picks EVENTS. Resolver returns EVENTS class vars
        // with `--` substituted to MYAE.
        IMetadataLibrary study = lib("study")
                .table(table("MYAE").column(column("STUDYID", 0, DataValueType.STRING).build())
                        .column(column("USUBJID", 1, DataValueType.STRING).build())
                        .column(column("DOMAIN", 2, DataValueType.STRING).build())
                        .column(column("MYAETERM", 3, DataValueType.STRING).build()).build())
                .build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkRichSdtmProduct(),
                "sdtmig", "3-4");

        List<String> result = provider.getStandardModelVariables(mockTable("MYAE"), null);

        // Identifiers (STUDYID, USUBJID, MYAESEQ from GenObs) + class vars (MYAETERM, MYAEDECOD)
        // + timing (MYAEDTC).
        assertNotNull(result);
        assertEquals(List.of("STUDYID", "USUBJID", "MYAESEQ", "MYAETERM", "MYAEDECOD", "MYAEDTC"),
                result);
    }


    @Test
    void customFindingsAboutShapeDomain_sniffsToFindingsAbout_returnsMergedClassVariables()
    {
        // MYFA has DOMAIN + MYFATESTCD + MYFAOBJ → sniffer picks FINDINGS ABOUT. Resolver merges
        // FINDINGS class vars around --TEST and substitutes -- to MYFA.
        IMetadataLibrary study = lib("study")
                .table(table("MYFA").column(column("STUDYID", 0, DataValueType.STRING).build())
                        .column(column("USUBJID", 1, DataValueType.STRING).build())
                        .column(column("DOMAIN", 2, DataValueType.STRING).build())
                        .column(column("MYFATESTCD", 3, DataValueType.STRING).build())
                        .column(column("MYFAOBJ", 4, DataValueType.STRING).build()).build())
                .build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkRichSdtmProduct(),
                "sdtmig", "3-4");

        List<String> result = provider.getStandardModelVariables(mockTable("MYFA"), null);

        // FINDINGS class vars: --TESTCD, --TEST, --ORRES. FINDINGS ABOUT adds --OBJ between TEST
        // and ORRES per the merge rule. Identifiers + timing from GenObs around the class vars.
        assertNotNull(result);
        assertEquals(List.of("STUDYID", "USUBJID", "MYFASEQ", "MYFATESTCD", "MYFATEST", "MYFAOBJ",
                "MYFAORRES", "MYFADTC"), result);
    }

    // ------------------------------------------------------------------
    // SUPP / SQ shimming
    // ------------------------------------------------------------------


    @Test
    void suppDomain_routesToSuppqualClass()
    {
        // SUPPDM ⇒ effective domain SUPPQUAL. No `--` wildcards in SUPPQUAL class vars; the
        // wildcardDomain (SUPPDM) is irrelevant. No add_AP since parent (DM) doesn't start AP.
        IMetadataLibrary study = lib("study").table(table("SUPPDM").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkRichSdtmProduct(),
                "sdtmig", "3-4");

        List<String> result = provider.getStandardModelVariables(mockTable("SUPPDM"), null);

        // Note: SUPPQUAL class is "Relationship" in the fixture (it's a non-detectable class),
        // so identifiers/timing are NOT added from GenObs — only the class's own vars.
        assertEquals(List.of("STUDYID", "RDOMAIN", "USUBJID", "IDVAR", "QNAM", "QVAL"), result);
    }

    // ------------------------------------------------------------------
    // AP shimming
    // ------------------------------------------------------------------


    @Test
    void apDomain_stripsApPrefixAndMergesAssociatedPersonsIdentifiers()
    {
        // APAE ⇒ stripped to AE (Events class) + AP identifier merge (excluding USUBJID).
        // wildcardDomain is the stripped form (AE) per Python parity (original_domain = domain
        // after strip).
        IMetadataLibrary study = lib("study").table(table("APAE").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkRichSdtmProduct(),
                "sdtmig", "3-4");

        List<String> result = provider.getStandardModelVariables(mockTable("APAE"), null);

        // Identifiers: STUDYID (1), USUBJID (2), --SEQ→AESEQ (3) from GenObs + APID (11) from
        // ASSOCIATED PERSONS (USUBJID excluded). Class vars: AETERM, AEDECOD. Timing: AEDTC.
        assertNotNull(result);
        assertEquals(List.of("STUDYID", "USUBJID", "AESEQ", "APID", "AETERM", "AEDECOD", "AEDTC"),
                result);
    }

    // ------------------------------------------------------------------
    // ADaM
    // ------------------------------------------------------------------


    @Test
    void adamAdsl_returnsSubjectLevelAnalysisDatasetVariables()
    {
        IMetadataLibrary study = lib("study").table(table("ADSL").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkAdamProduct(),
                "adamig", "1-3");

        List<String> result = provider.getStandardModelVariables(mockTable("ADSL"), null);

        assertEquals(List.of("STUDYID", "USUBJID", "AGE"), result);
    }


    @Test
    void adamAdaeBdsClass_fallsBackToBasicDataStructure()
    {
        // ADAE isn't a directly-named ADaM data structure; it's a BDS-class dataset. The
        // resolver's class-name fallback (AD-prefixed and not ADSL) routes it to BDS.
        IMetadataLibrary study = lib("study").table(table("ADAE").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkAdamProduct(),
                "adamig", "1-3");

        List<String> result = provider.getStandardModelVariables(mockTable("ADAE"), null);

        assertEquals(List.of("STUDYID", "USUBJID", "PARAMCD", "AVAL"), result);
    }


    @Test
    void adamUnknownDomain_returnsEmptyList()
    {
        // A non-AD-prefixed unknown domain doesn't match any structure or className fallback.
        IMetadataLibrary study = lib("study").table(table("XYZZY").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkAdamProduct(),
                "adamig", "1-3");

        List<String> result = provider.getStandardModelVariables(mockTable("XYZZY"), null);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ------------------------------------------------------------------
    // No products / degraded mode
    // ------------------------------------------------------------------


    @Test
    void noProductConfigured_returnsNull()
    {
        // Library-not-available signal — caller treats as SKIPPED.
        MetadataLibraryProvider provider = new MetadataLibraryProvider(
                lib("study").table(table("LB").build()).build());
        assertNull(provider.getStandardModelVariables(mockTable("LB"), null));
    }


    @Test
    void degradedMode_returnsNull()
    {
        IMetadataLibrary study = lib("study").table(table("LB").build()).build();
        MetadataLibraryProvider provider = MetadataLibraryProvider.degraded(study,
                new IOException("HTTP 503"));
        assertNull(provider.getStandardModelVariables(mockTable("LB"), null));
    }

    // ------------------------------------------------------------------
    // Edge cases
    // ------------------------------------------------------------------


    @Test
    void nullTable_returnsNull()
    {
        MetadataLibraryProvider provider = new MetadataLibraryProvider(lib("study").build(),
                mkRichSdtmProduct(), "sdtmig", "3-4");
        assertNull(provider.getStandardModelVariables(null, null));
    }


    @Test
    void unknownCustomDomainNoSniffer_returnsEmptyList()
    {
        // No DOMAIN column → sniffer can't help. Expect empty list.
        IMetadataLibrary study = lib("study")
                .table(table("ZZZZ").column(column("STUDYID", 0, DataValueType.STRING).build())
                        .column(column("USUBJID", 1, DataValueType.STRING).build()).build())
                .build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkRichSdtmProduct(),
                "sdtmig", "3-4");

        List<String> result = provider.getStandardModelVariables(mockTable("ZZZZ"), null);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ------------------------------------------------------------------
    // Phase 2 step 3 — IG-override merge
    // (Closes the gap noted in the subagent's end-of-run report.)
    // ------------------------------------------------------------------


    /**
     * Builds an SDTM product where datasets carry their own IG-level {@code datasetVariables}. Used
     * to exercise the IG-override merge that mirrors Python's {@code sdtm_utilities.py:178-212}.
     */
    private static SdtmProduct mkSdtmProductWithIgVars()
    {
        // GENERAL OBSERVATIONS — same as mkRichSdtmProduct (provides Identifier + Timing).
        List<Map<String, Object>> genObsVars = new ArrayList<>();
        genObsVars.add(sdtmVar("STUDYID", "Study Identifier", "1", "Char", "Req", "Identifier"));
        genObsVars.add(
                sdtmVar("USUBJID", "Unique Subject Identifier", "2", "Char", "Req", "Identifier"));
        genObsVars.add(sdtmVar("--SEQ", "Sequence Number", "3", "Num", "Req", "Identifier"));
        genObsVars.add(sdtmVar("--DTC", "Date/Time", "10", "Char", "Perm", "Timing"));
        Map<String, Object> genObs = sdtmClass("General Observations", genObsVars, List.of());

        // FINDINGS class variables (model-level).
        List<Map<String, Object>> findingsClassVars = new ArrayList<>();
        findingsClassVars.add(sdtmVar("--TESTCD", "Test Code", "5", "Char", "Req"));
        findingsClassVars.add(sdtmVar("--TEST", "Test Name", "6", "Char", "Req"));
        findingsClassVars.add(sdtmVar("--ORRES", "Result or Finding", "7", "Char", "Req"));

        // LB IG dataset — carries override and IG-only variables.
        // Override: --TESTCD already in model (Char, Req) — overridden in place.
        // IG-only (no role / Qualifier): --STAT inserted before Timing section.
        // IG-only (role=Identifier): SPDEVID inserted after Identifiers section (after --SEQ).
        // IG-only (role=Timing): --ENDY inserted at end.
        List<Map<String, Object>> lbIgVars = new ArrayList<>();
        lbIgVars.add(sdtmVar("--TESTCD", "Test Code (IG override)", "5", "Char", "Req"));
        lbIgVars.add(sdtmVar("SPDEVID", "Sponsor Device Identifier", "100", "Char", "Perm",
                "Identifier"));
        lbIgVars.add(
                sdtmVar("--STAT", "Completion Status", "200", "Char", "Perm", "Result Qualifier"));
        lbIgVars.add(sdtmVar("--ENDY", "End Study Day", "300", "Num", "Perm", "Timing"));
        Map<String, Object> lb = new LinkedHashMap<>();
        lb.put("name", "LB");
        lb.put("label", "Laboratory Test Results");
        lb.put("datasetStructure", "");
        lb.put("datasetVariables", lbIgVars);
        Map<String, Object> findings = sdtmClass("Findings", findingsClassVars, List.of(lb));

        // SPECIAL PURPOSE — non-detectable. DM dataset has its own datasetVariables that should
        // replace (not merge with) the class-level vars, since SPECIAL PURPOSE isn't a
        // detectable class.
        List<Map<String, Object>> spClassVars = new ArrayList<>();
        spClassVars.add(sdtmVar("STUDYID", "Study Identifier", "1", "Char", "Req"));
        spClassVars.add(sdtmVar("DOMAIN", "Domain Abbreviation", "2", "Char", "Req"));

        List<Map<String, Object>> dmIgVars = new ArrayList<>();
        dmIgVars.add(sdtmVar("STUDYID", "Study Identifier", "1", "Char", "Req"));
        dmIgVars.add(sdtmVar("DOMAIN", "Domain Abbreviation", "2", "Char", "Req"));
        dmIgVars.add(sdtmVar("USUBJID", "Unique Subject Identifier", "3", "Char", "Req"));
        dmIgVars.add(sdtmVar("RFSTDTC", "Subject Reference Start Date", "10", "Char", "Perm"));
        dmIgVars.add(sdtmVar("AGE", "Age", "20", "Num", "Perm"));
        dmIgVars.add(sdtmVar("ARM", "Description of Planned Arm", "30", "Char", "Req"));
        Map<String, Object> dm = new LinkedHashMap<>();
        dm.put("name", "DM");
        dm.put("label", "Demographics");
        dm.put("datasetStructure", "");
        dm.put("datasetVariables", dmIgVars);
        Map<String, Object> specialPurpose = sdtmClass("Special-Purpose", spClassVars, List.of(dm));

        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", "SDTMIG");
        product.put("version", "3-4");
        product.put("classes", List.of(genObs, findings, specialPurpose));
        return MapResource.of(product, SdtmProduct.class);
    }


    @Test
    void igOverride_detectableClass_replacesModelVarByName_preservesPosition()
    {
        // LB.--TESTCD exists in model FINDINGS class AND in LB's datasetVariables. Python's
        // override-by-name swaps the IG entry into the existing position; the dedupe of
        // substituted names produces a single LBTESTCD entry, not two.
        IMetadataLibrary study = lib("study").table(table("LB").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study,
                mkSdtmProductWithIgVars(), "sdtmig", "3-4");

        // Algorithm-B IG-override merge via getColumnOrder (see sibling test note).
        List<String> result = provider.getColumnOrder("LB");

        // Single LBTESTCD, not duplicated.
        long testcdCount = result.stream().filter("LBTESTCD"::equals).count();
        assertEquals(1, testcdCount);
    }


    @Test
    void igOverride_detectableClass_insertsIgOnlyIdentifierAfterIdentifiersSection()
    {
        // The IG-override merge is now algorithm B (getColumnOrder /
        // getStandardVariablesDetailed); getStandardModelVariables is the pure-Model algorithm A
        // (no IG override). This test exercises the algorithm-B merge via getColumnOrder.
        IMetadataLibrary study = lib("study").table(table("LB").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study,
                mkSdtmProductWithIgVars(), "sdtmig", "3-4");

        List<String> result = provider.getColumnOrder("LB");

        // SPDEVID has role=Identifier and isn't in the model — should land in the identifiers
        // section, after STUDYID/USUBJID/LBSEQ.
        int spdevidIdx = result.indexOf("SPDEVID");
        int testcdIdx = result.indexOf("LBTESTCD");
        assertTrue(spdevidIdx > 0,
                "SPDEVID should be present and not at the very start: " + result);
        assertTrue(spdevidIdx < testcdIdx,
                "SPDEVID (Identifier) should appear before LBTESTCD (class var): " + result);
    }


    @Test
    void igOverride_detectableClass_insertsIgOnlyTimingAtEnd()
    {
        IMetadataLibrary study = lib("study").table(table("LB").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study,
                mkSdtmProductWithIgVars(), "sdtmig", "3-4");

        // Algorithm-B IG-override merge via getColumnOrder (see sibling test note).
        List<String> result = provider.getColumnOrder("LB");

        // LBENDY has role=Timing — should be at the very end (after the existing LBDTC timing
        // section).
        assertEquals("LBENDY", result.get(result.size() - 1),
                "LBENDY (Timing IG-only) should be at the end of the merged list: " + result);
    }


    @Test
    void igOverride_detectableClass_insertsIgOnlyClassVarBeforeTimingSection()
    {
        IMetadataLibrary study = lib("study").table(table("LB").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study,
                mkSdtmProductWithIgVars(), "sdtmig", "3-4");

        // Algorithm-B IG-override merge via getColumnOrder (see sibling test note).
        List<String> result = provider.getColumnOrder("LB");

        // LBSTAT has role="Result Qualifier" (non-Identifier, non-Timing) — should land
        // between class vars and the timing section. Specifically: after LBORRES (last class
        // var), before LBDTC and LBENDY (timing).
        int statIdx = result.indexOf("LBSTAT");
        int dtcIdx = result.indexOf("LBDTC");
        int endyIdx = result.indexOf("LBENDY");
        assertTrue(statIdx > 0, "LBSTAT should be present: " + result);
        assertTrue(statIdx < dtcIdx, "LBSTAT should be before LBDTC: " + result);
        assertTrue(statIdx < endyIdx, "LBSTAT should be before LBENDY: " + result);
    }


    @Test
    void igOverride_nonDetectableNonCustom_replacesClassVarsWithIgVars()
    {
        // DM is SPECIAL PURPOSE (non-detectable). The model class vars (STUDYID, DOMAIN) are
        // not the relevant source — Python uses IG datasetVariables instead. The IG list adds
        // USUBJID, RFSTDTC, AGE, ARM that the class-level model doesn't carry.
        IMetadataLibrary study = lib("study").table(table("DM").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study,
                mkSdtmProductWithIgVars(), "sdtmig", "3-4");

        // Non-detectable, non-custom: algorithm B returns the IG datasetVariables. This is the
        // getColumnOrder (algorithm B) path; algorithm A would three-tier-fallback to the Model
        // domain instead.
        List<String> result = provider.getColumnOrder("DM");

        assertNotNull(result);
        assertTrue(result.contains("RFSTDTC"),
                "DM IG dataset variables must be exposed (RFSTDTC): " + result);
        assertTrue(result.contains("AGE"),
                "DM IG dataset variables must be exposed (AGE): " + result);
        assertTrue(result.contains("ARM"),
                "DM IG dataset variables must be exposed (ARM): " + result);
        assertTrue(result.contains("USUBJID"),
                "DM IG dataset variables must include USUBJID: " + result);
    }


    @Test
    void igOverride_customDomain_skipsIgMergeUsesModelOnly()
    {
        // Custom domain (not in product) — sniffer returns the inferred class, but Python's
        // is_custom branch returns model_variables without the IG-override merge. Java mirrors
        // that: no IG dataset for the custom domain, so the model-derived list is final.
        IMetadataLibrary study = lib("study")
                .table(table("MYAE").column(column("STUDYID", 0, DataValueType.STRING).build())
                        .column(column("USUBJID", 1, DataValueType.STRING).build())
                        .column(column("DOMAIN", 2, DataValueType.STRING).build())
                        .column(column("MYAETERM", 3, DataValueType.STRING).build()).build())
                .build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study,
                mkSdtmProductWithIgVars(), "sdtmig", "3-4");

        // mkSdtmProductWithIgVars has no EVENTS class with --TERM, but the sniffer still
        // returns EVENTS for MYAE (--TERM topic). With no EVENTS class in this product the
        // resolver returns empty — the test confirms the custom-domain branch doesn't crash
        // and doesn't trigger IG merge.
        List<String> result = provider.getStandardModelVariables(mockTable("MYAE"), null);
        assertNotNull(result);
    }

    // ------------------------------------------------------------------
    // Phase 2 follow-up #1 — getStandardModelVariablesDetailed
    // (Closes the second open item from Phase 2: filtered-variable Operations
    // route through the class-aware resolver.)
    // ------------------------------------------------------------------


    @Test
    void detailed_sdtmFindings_returnsAttributeMaps()
    {
        IMetadataLibrary study = lib("study").table(table("LB").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkRichSdtmProduct(),
                "sdtmig", "3-4");

        List<Map<String, String>> result = provider
                .getStandardModelVariablesDetailed(mockTable("LB"), null);

        assertNotNull(result);
        // Each entry must carry the substituted name PLUS attributes.
        Map<String, Map<String, String>> byName = new java.util.HashMap<>();
        for (Map<String, String> v : result)
        {
            byName.put(v.get("name"), v);
        }
        // STUDYID is an Identifier-role var from GENERAL OBSERVATIONS.
        Map<String, String> studyid = byName.get("STUDYID");
        assertNotNull(studyid, "STUDYID must be in detailed output: " + result);
        assertEquals("Identifier", studyid.get("role"));
        assertEquals("Req", studyid.get("core"));
        // LBSEQ is a substituted Identifier — name should be the substituted form, role
        // should propagate from the GENERAL OBSERVATIONS source row.
        Map<String, String> lbseq = byName.get("LBSEQ");
        assertNotNull(lbseq, "LBSEQ must be in detailed output: " + result);
        assertEquals("Identifier", lbseq.get("role"));
    }


    @Test
    void detailed_filterByRoleTiming_isolatesTimingVariables()
    {
        // Simulates what evalGetModelFilteredVariables does: filter the detailed output by
        // role=Timing → just the timing variables.
        IMetadataLibrary study = lib("study").table(table("LB").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkRichSdtmProduct(),
                "sdtmig", "3-4");

        List<Map<String, String>> all = provider.getStandardModelVariablesDetailed(mockTable("LB"),
                null);
        List<String> timingNames = all.stream().filter(v -> "Timing".equals(v.get("role")))
                .map(v -> v.get("name")).toList();
        // GENERAL OBSERVATIONS has --DTC role=Timing → substituted to LBDTC.
        assertTrue(timingNames.contains("LBDTC"),
                "Timing-role variables must include LBDTC: " + timingNames);
        // Class-vars without an explicit role shouldn't appear.
        assertFalse(timingNames.contains("LBTESTCD"),
                "LBTESTCD has no Timing role: " + timingNames);
    }


    @Test
    void detailed_adamAdsl_returnsAttributeMapsWithoutRole()
    {
        // ADaM analysisVariables don't carry "role"; the detailed output reflects that.
        IMetadataLibrary study = lib("study").table(table("ADSL").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkAdamProduct(),
                "adamig", "1-3");

        List<Map<String, String>> result = provider
                .getStandardModelVariablesDetailed(mockTable("ADSL"), null);

        assertNotNull(result);
        assertTrue(result.size() >= 2,
                "ADSL detailed output should have at least 2 entries: " + result);
        for (Map<String, String> v : result)
        {
            // ADaM lane intentionally doesn't populate "role" (not part of the API model).
            assertFalse(v.containsKey("role"),
                    "ADaM detailed output must not synthesise role: " + v);
            assertNotNull(v.get("name"), "Every entry must have a name: " + v);
        }
    }


    @Test
    void detailed_degradedProvider_returnsNull()
    {
        IMetadataLibrary study = lib("study").table(table("LB").build()).build();
        MetadataLibraryProvider provider = MetadataLibraryProvider.degraded(study,
                new java.io.IOException("HTTP 503"));

        assertNull(provider.getStandardModelVariablesDetailed(mockTable("LB"), null));
    }


    @Test
    void detailed_noProductConfigured_returnsNull()
    {
        // No-product constructor → detailed accessor signals library-not-available the same
        // way the names-only accessor does.
        IMetadataLibrary study = lib("study").table(table("LB").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study);

        assertNull(provider.getStandardModelVariablesDetailed(mockTable("LB"), null));
    }

    // ------------------------------------------------------------------
    // Fix #59 — DOMAIN-column-driven CDISC domain resolution
    // (Regression for the LBHE bug: member name LBHE, DOMAIN column = LB.)
    // ------------------------------------------------------------------


    @Test
    void lbheStyleSplit_resolvesViaDomainColumn_returnsLbVariables()
    {
        // Member name LBHE (a multi-letter LB-split SplitDatasetUtil doesn't recognise),
        // but DOMAIN column on row 0 = LB. Pre-Fix-#59, the resolver looked up class "LBHE"
        // and returned empty (the runtime then SKIPped the rule). Post-Fix-#59, the lookup
        // routes through the LB Findings class and returns the LB allowed variables.
        IMetadataLibrary study = lib("study").table(table("LBHE").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkRichSdtmProduct(),
                "sdtmig", "3-4");

        List<String> result = provider.getStandardModelVariables(mockSplitTable("LBHE", "LB"),
                null);

        assertNotNull(result);
        assertFalse(result.isEmpty(),
                "LBHE with DOMAIN=LB must resolve to the LB Findings allowed variables: " + result);
        // STUDYID is in the GENERAL OBSERVATIONS Identifier set; --SEQ is substituted to
        // LBSEQ using the wildcard domain (= original domain after AP strip; = "LBHE" here
        // since LBHE doesn't trigger SUPP/AP rewriting).
        assertTrue(result.contains("STUDYID"),
                "STUDYID must be present (GenObs Identifier): " + result);
        // The wildcard substitution honours the DOMAIN column (Fix #59) — `--SEQ` becomes
        // `LBSEQ`, not `LBHESEQ`.
        assertTrue(result.contains("LBSEQ"),
                "--SEQ must be substituted to LBSEQ (DOMAIN column wins): " + result);
        assertFalse(result.contains("LBHESEQ"),
                "--SEQ must NOT be substituted to LBHESEQ (Fix #59 regression): " + result);
    }


    @Test
    void lbheStyleSplit_detailedAccessorAlsoUsesDomainColumn()
    {
        IMetadataLibrary study = lib("study").table(table("LBHE").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkRichSdtmProduct(),
                "sdtmig", "3-4");

        List<Map<String, String>> result = provider
                .getStandardModelVariablesDetailed(mockSplitTable("LBHE", "LB"), null);

        assertNotNull(result);
        assertFalse(result.isEmpty(), "Detailed accessor must also resolve LBHE→LB: " + result);
        boolean foundLbseq = result.stream().anyMatch(v -> "LBSEQ".equals(v.get("name")));
        assertTrue(foundLbseq, "LBSEQ must appear in the detailed output: " + result);
    }

    // ------------------------------------------------------------------
    // EC-85 — getStandardModelVariablesForClass: the forced-class walk
    // ------------------------------------------------------------------


    private static List<String> names(List<Map<String, String>> aRows)
    {
        List<String> out = new ArrayList<>();
        for (Map<String, String> row : aRows)
        {
            out.add(row.get("name"));
        }
        return out;
    }


    @Test
    void forcedEventsClassOnFindingsDomain_returnsEventsTableUnderTheFindingsPrefix()
    {
        // D-1: LB is FINDINGS; asking for EVENTS must walk the EVENTS class (identifiers + --TERM,
        // --DECOD + timing) and substitute `--` with LB — the dataset's OWN prefix — so the
        // answer is "the EVENTS variables as they would be spelled inside LB".
        IMetadataLibrary study = lib("study").table(table("LB").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkRichSdtmProduct(),
                "sdtmig", "3-4");

        List<Map<String, String>> result = provider
                .getStandardModelVariablesForClass(mockTable("LB"), null, "EVENTS");

        assertNotNull(result);
        assertEquals(List.of("STUDYID", "USUBJID", "LBSEQ", "LBTERM", "LBDECOD", "LBDTC"),
                names(result));
        // And the own-class walk is untouched by the new entry point.
        assertEquals(
                List.of("STUDYID", "USUBJID", "LBSEQ", "LBTESTCD", "LBTEST", "LBORRES", "LBDTC"),
                provider.getStandardModelVariables(mockTable("LB"), null));
    }


    @Test
    void forcedClassIsNormalisedLikeTheResolversOwnClassNames()
    {
        IMetadataLibrary study = lib("study").table(table("LB").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkRichSdtmProduct(),
                "sdtmig", "3-4");

        List<Map<String, String>> lower = provider
                .getStandardModelVariablesForClass(mockTable("LB"), null, "interventions");
        List<Map<String, String>> fa = provider.getStandardModelVariablesForClass(mockTable("LB"),
                null, "Findings About");

        assertNotNull(lower);
        assertEquals(List.of("STUDYID", "USUBJID", "LBSEQ", "LBTRT", "LBDOSE", "LBDTC"),
                names(lower));
        assertNotNull(fa);
        // FINDINGS ABOUT splices FINDINGS around --TEST, exactly as the own-class walk does.
        assertEquals(List.of("STUDYID", "USUBJID", "LBSEQ", "LBTESTCD", "LBTEST", "LBOBJ",
                "LBORRES", "LBDTC"), names(fa));
    }


    @Test
    void probe3_ownClassNamesAndDetailedProjectionAgree_forEveryDetectableDomain()
    {
        // §3.3 / §4.1 premise of PLAN-cross-class-model-variable-lookup: `$model_order`
        // (get_model_column_order → getStandardModelVariables) and the unfiltered
        // get_model_filtered_variables source (getStandardModelVariablesDetailed → name) are the
        // SAME list, so "not in $model_order" is exactly "not in my own class's table".
        IMetadataLibrary study = lib("study").table(table("LB").build()).table(table("AE").build())
                .table(table("CM").build()).table(table("FA").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkRichSdtmProduct(),
                "sdtmig", "3-4");
        for (String domain : List.of("LB", "AE", "CM", "FA"))
        {
            List<Map<String, String>> detailed = provider
                    .getStandardModelVariablesDetailed(mockTable(domain), null);
            assertNotNull(detailed);
            assertEquals(provider.getStandardModelVariables(mockTable(domain), null),
                    names(detailed), domain);
        }
    }


    @Test
    void forcedClassOnSuppTable_shortCircuitsToSuppqual_ignoringTheClass()
    {
        // D-3: a SUPP-- table has no general observation class; SUPPQUAL wins over the forced
        // EVENTS and the answer is the SUPPQUAL cascade's, not EVENTS under a SUPPDM prefix.
        IMetadataLibrary study = lib("study").table(table("SUPPDM").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkRichSdtmProduct(),
                "sdtmig", "3-4");

        List<Map<String, String>> result = provider
                .getStandardModelVariablesForClass(mockTable("SUPPDM"), null, "EVENTS");

        assertNotNull(result);
        assertEquals(List.of("STUDYID", "RDOMAIN", "USUBJID", "IDVAR", "QNAM", "QVAL"),
                names(result));
    }


    @Test
    void forcedClassOnAdamOnlyProvider_returnsNull()
    {
        // D-4: ADaM has no observation class to select — library-not-available, never a walk.
        IMetadataLibrary study = lib("study").table(table("ADSL").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkAdamProduct(),
                "adamig", "1-3");

        assertNull(provider.getStandardModelVariablesForClass(mockTable("ADSL"), null, "EVENTS"));
        // Degraded / product-less providers answer the same way.
        assertNull(MetadataLibraryProvider.degraded(study, new IOException("HTTP 503"))
                .getStandardModelVariablesForClass(mockTable("ADSL"), null, "EVENTS"));
        assertNull(new MetadataLibraryProvider(study)
                .getStandardModelVariablesForClass(mockTable("ADSL"), null, "EVENTS"));
    }


    @Test
    void forcedNonDetectableClass_answersFromItsOwnClassVariables_neverTheDomainTiers()
    {
        // D-2: ASSOCIATED PERSONS is non-detectable and carries class variables → those, under
        // the non-detectable assembly (no GenObs merge). A class the model does NOT carry must
        // come back null — NOT LB's own variables via the domain-keyed tiers 2/3.
        IMetadataLibrary study = lib("study").table(table("LB").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkRichSdtmProduct(),
                "sdtmig", "3-4");

        List<Map<String, String>> ap = provider.getStandardModelVariablesForClass(mockTable("LB"),
                null, "ASSOCIATED PERSONS");
        assertNotNull(ap);
        assertEquals(List.of("APID", "USUBJID"), names(ap));

        List<Map<String, String>> absent = provider
                .getStandardModelVariablesForClass(mockTable("LB"), null, "TRIAL DESIGN");
        assertNull(absent,
                "a class the model does not carry is library-not-available, got " + absent);
    }


    @Test
    void forcedClassWithNoResolvableDomain_returnsEmptyList()
    {
        IMetadataLibrary study = lib("study").build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkRichSdtmProduct(),
                "sdtmig", "3-4");
        List<Map<String, String>> result = provider.getStandardModelVariablesForClass(mockTable(""),
                null, "EVENTS");
        assertNotNull(result);
        assertTrue(result.isEmpty());
        assertNull(provider.getStandardModelVariablesForClass(null, null, "EVENTS"));
        assertNull(provider.getStandardModelVariablesForClass(mockTable("LB"), null, null));
    }
}
