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
import net.cumba.corej.core.exec.ScopeMatcher;
import net.cumba.corej.core.model.ClassScope;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Scope;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.values.DataValueType;
import net.cumba.web.api.dev.MapResource;
import org.junit.jupiter.api.Test;

/**
 * Tests for the Fix #55 product-aware {@link MetadataLibraryProvider} constructors and
 * {@link MetadataLibraryProvider#degraded(IMetadataLibrary, Throwable)} factory.
 */
class MetadataLibraryProviderProductsTest
{

    // ------------------------------------------------------------------
    // Fixture helpers — minimal SDTM / ADaM products built via MapResource
    // ------------------------------------------------------------------

    private static Map<String, Object> sdtmVar(String name, String label, String ordinal,
            String dtype, String core)
    {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("name", name);
        v.put("label", label);
        v.put("ordinal", ordinal);
        v.put("simpleDatatype", dtype);
        v.put("core", core);
        return v;
    }


    private static Map<String, Object> sdtmDataset(String name, String label, String structure)
    {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", name);
        d.put("label", label);
        d.put("datasetStructure", structure);
        // Empty dataset variables — not exercised by these tests.
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


    private static SdtmProduct mkSdtmProduct()
    {
        // Findings class: STUDYID, USUBJID, --SEQ, --TESTCD (in ordinal order)
        List<Map<String, Object>> findingsVars = new ArrayList<>();
        findingsVars.add(sdtmVar("STUDYID", "Study Identifier", "1", "Char", "Req"));
        findingsVars.add(sdtmVar("USUBJID", "Unique Subject Identifier", "2", "Char", "Req"));
        findingsVars.add(sdtmVar("--SEQ", "Sequence Number", "3", "Num", "Req"));
        findingsVars.add(sdtmVar("--TESTCD", "Test Code", "4", "Char", "Req"));

        Map<String, Object> lb = sdtmDataset("LB", "Laboratory Test Results",
                "One record per measurement per visit per subject");

        List<Map<String, Object>> eventsVars = new ArrayList<>();
        eventsVars.add(sdtmVar("STUDYID", "Study Identifier", "1", "Char", "Req"));
        eventsVars.add(sdtmVar("USUBJID", "Unique Subject Identifier", "2", "Char", "Req"));
        eventsVars.add(sdtmVar("--TERM", "Reported Term", "3", "Char", "Req"));
        Map<String, Object> ae = sdtmDataset("AE", "Adverse Events",
                "One record per event per subject");

        Map<String, Object> findings = sdtmClass("Findings", findingsVars, List.of(lb));
        Map<String, Object> events = sdtmClass("Events", eventsVars, List.of(ae));

        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", "SDTMIG");
        product.put("version", "3-4");
        product.put("classes", List.of(findings, events));
        return MapResource.of(product, SdtmProduct.class);
    }


    private static AdamProduct mkAdamProduct()
    {
        List<Map<String, Object>> identifiers = new ArrayList<>();
        identifiers.add(sdtmVar("STUDYID", "Study Identifier", "1", "Char", "Req"));
        identifiers.add(sdtmVar("USUBJID", "Unique Subject Identifier", "2", "Char", "Req"));

        List<Map<String, Object>> timing = new ArrayList<>();
        timing.add(sdtmVar("ADT", "Analysis Date", "10", "Num", "Cond"));

        Map<String, Object> idSet = new LinkedHashMap<>();
        idSet.put("name", "Identifiers");
        idSet.put("ordinal", "1");
        idSet.put("analysisVariables", identifiers);

        Map<String, Object> timingSet = new LinkedHashMap<>();
        timingSet.put("name", "Timing");
        timingSet.put("ordinal", "2");
        timingSet.put("analysisVariables", timing);

        Map<String, Object> adsl = new LinkedHashMap<>();
        adsl.put("name", "ADSL");
        adsl.put("label", "Subject Level Analysis Dataset");
        adsl.put("class", "SUBJECT LEVEL ANALYSIS DATASET");
        adsl.put("analysisVariableSets", List.of(idSet, timingSet));

        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", "ADaMIG");
        product.put("version", "1-3");
        product.put("dataStructures", List.of(adsl));
        return MapResource.of(product, AdamProduct.class);
    }


    private static IMetadataLibrary studyWith(String tableName)
    {
        return lib("study")
                .table(table(tableName).column(column("STUDYID", 0, DataValueType.STRING).build())
                        .column(column("USUBJID", 1, DataValueType.STRING).build()).build())
                .build();
    }


    private static IDataTable mockTable(String name)
    {
        IDataTable table = mock(IDataTable.class);
        DataTableMeta meta = mock(DataTableMeta.class);
        lenient().when(meta.getName()).thenReturn(name);
        lenient().when(table.getMetaData()).thenReturn(meta);
        return table;
    }

    // ------------------------------------------------------------------
    // getModelColumnOrder — product-first
    // ------------------------------------------------------------------


    @Test
    void getModelColumnOrder_sdtm_walksClassVariables()
    {
        IMetadataLibrary study = studyWith("LB");
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkSdtmProduct(),
                "sdtmig", "3-4");
        // LB lives under the Findings class — its classVariables() returns 4 names by ordinal.
        assertEquals(List.of("STUDYID", "USUBJID", "--SEQ", "--TESTCD"),
                provider.getModelColumnOrder("LB"));
    }


    @Test
    void getModelColumnOrder_adam_walksDataStructureVariables()
    {
        IMetadataLibrary study = studyWith("ADSL");
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkAdamProduct(),
                "adamig", "1-3");
        // Flattened across analysisVariableSets, ordered by ordinal: identifiers (1, 2) then ADT
        // (10).
        assertEquals(List.of("STUDYID", "USUBJID", "ADT"), provider.getModelColumnOrder("ADSL"));
    }


    @Test
    void getModelColumnOrder_customDomain_returnsEmptyEvenWithProduct()
    {
        // Custom domain not in the SDTM product → product walk yields no class →
        // empty list. The OperationExecutor SKIP shim then surfaces this as
        // LIBRARY_NOT_AVAILABLE for the get_model_column_order operation.
        IMetadataLibrary study = lib("study").table(table("MYAE").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkSdtmProduct(),
                "sdtmig", "3-4");
        assertEquals(List.of(), provider.getModelColumnOrder("MYAE"));
    }


    @Test
    void getModelColumnOrder_noProduct_fallsBackToLegacyMetaKey()
    {
        // The legacy fallback path: with no product, the provider reads
        // MetadataKeys.MODEL_COLUMN_ORDER from the per-table meta. This is the path that the
        // follow-up retirement will eventually remove, but it must work today.
        IMetadataLibrary library = lib("study").table(table("AE")
                .meta(MetadataKeys.MODEL_COLUMN_ORDER, List.of("STUDYID", "USUBJID", "AESEQ"))
                .build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(library);
        assertEquals(List.of("STUDYID", "USUBJID", "AESEQ"), provider.getModelColumnOrder("AE"));
    }

    // ------------------------------------------------------------------
    // getStandardModelVariables (Fix #42 Phase 2 hook)
    // ------------------------------------------------------------------


    @Test
    void getStandardModelVariables_sdtm_returnsClassVariables()
    {
        IMetadataLibrary study = studyWith("LB");
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkSdtmProduct(),
                "sdtmig", "3-4");
        List<String> result = provider.getStandardModelVariables(mockTable("LB"), null);
        // Fix #42 Phase 2: -- wildcards are substituted with the original domain prefix.
        assertEquals(List.of("STUDYID", "USUBJID", "LBSEQ", "LBTESTCD"), result);
    }


    @Test
    void getStandardModelVariables_adam_returnsAnalysisVariables()
    {
        IMetadataLibrary study = studyWith("ADSL");
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkAdamProduct(),
                "adamig", "1-3");
        List<String> result = provider.getStandardModelVariables(mockTable("ADSL"), null);
        assertEquals(List.of("STUDYID", "USUBJID", "ADT"), result);
    }


    @Test
    void getStandardModelVariables_noProduct_returnsNullSignal()
    {
        // No product configured → caller treats null as library-not-available.
        MetadataLibraryProvider provider = new MetadataLibraryProvider(studyWith("LB"));
        assertNull(provider.getStandardModelVariables(mockTable("LB"), null));
    }


    @Test
    void getStandardModelVariables_customDomainWithProduct_returnsEmptyList()
    {
        // Custom domain (not in the product) → empty list, distinct from null:
        // "library is fine, just doesn't know this domain". Caller path can then
        // route to a Fix #41 sniffer or treat as custom.
        IMetadataLibrary study = lib("study").table(table("MYAE").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkSdtmProduct(),
                "sdtmig", "3-4");
        List<String> result = provider.getStandardModelVariables(mockTable("MYAE"), null);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ------------------------------------------------------------------
    // getDatasetClass — precedence
    // ------------------------------------------------------------------


    @Test
    void getDatasetClass_defineXmlClassWinsOverProduct()
    {
        // Define-XML / study marks LB as "MyCustomClass". Product would otherwise return Findings.
        // The Define-XML class wins per documented precedence.
        IMetadataLibrary study = lib("study").table(table("LB").className("MyCustomClass").build())
                .build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkSdtmProduct(),
                "sdtmig", "3-4");
        assertEquals("MyCustomClass", provider.getDatasetClass("LB"));
    }


    @Test
    void getDatasetClass_productReverseWalkFires_whenStudyClassIsNull()
    {
        // Study has no class → fall to product reverse-walk. Findings class owns LB.
        IMetadataLibrary study = lib("study").table(table("LB").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkSdtmProduct(),
                "sdtmig", "3-4");
        assertEquals("Findings", provider.getDatasetClass("LB"));
    }


    @Test
    void getDatasetClass_adamReverseWalkFires()
    {
        IMetadataLibrary study = lib("study").table(table("ADSL").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkAdamProduct(),
                "adamig", "1-3");
        assertEquals("SUBJECT LEVEL ANALYSIS DATASET", provider.getDatasetClass("ADSL"));
    }


    @Test
    void getDatasetClass_unknownDomain_returnsNullPendingFix41Sniffer()
    {
        IMetadataLibrary study = lib("study").build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkSdtmProduct(),
                "sdtmig", "3-4");
        assertNull(provider.getDatasetClass("MYXX"));
    }


    @Test
    void getDatasetClass_noProduct_studyClassStillWins()
    {
        IMetadataLibrary study = lib("study").table(table("LB").className("Findings").build())
                .build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study);
        assertEquals("Findings", provider.getDatasetClass("LB"));
    }


    @Test
    void getDatasetClass_noProductAndNoStudyClass_usesDomainClassMapFallback()
    {
        // No product and no study class: the curated DomainClassMap fallback (Tier 2.5) now
        // resolves known standard domains offline, so LB maps to FINDINGS. A domain absent from
        // the map still returns null (see
        // getDatasetClass_unknownDomain_returnsNullPendingFix41Sniffer).
        IMetadataLibrary study = lib("study").table(table("LB").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study);
        assertEquals("FINDINGS", provider.getDatasetClass("LB"));
    }

    // ------------------------------------------------------------------
    // FU-4: synthetic "ADAM OTHER" class token for structure-less ADaM datasets
    // ------------------------------------------------------------------


    private static Rule ruleWithClassInclude(String... classes)
    {
        Rule rule = new Rule();
        Scope scope = new Scope();
        ClassScope cs = new ClassScope();
        cs.setInclude(List.of(classes));
        scope.setClasses(cs);
        rule.setScope(scope);
        return rule;
    }


    @Test
    void getDatasetClass_adamStructureLessDomain_returnsAdamOtherSentinel()
    {
        // ADaM run, dataset with no Define-XML class, not a product structure (only ADSL is), no
        // curated map entry, and no sniffer signature (STUDYID/USUBJID only). FU-4: the last-resort
        // fallback returns the synthetic "ADAM OTHER" token instead of null so ["ADAM
        // OTHER"]-scoped
        // rules can reach it.
        IMetadataLibrary study = studyWith("ADEFF");
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkAdamProduct(),
                "adamig", "1-3");
        assertEquals("ADAM OTHER", provider.getDatasetClass("ADEFF"));
    }


    @Test
    void adamOtherSentinel_isReachedByAdamOtherScopedRule()
    {
        // The sentinel flows through ScopeMatcher as a normal class name: a rule scoping
        // Classes.Include:["ADAM OTHER"] matches (normalize collapses the space), so the rule
        // applies to the structure-less ADaM dataset.
        IMetadataLibrary study = studyWith("ADEFF");
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkAdamProduct(),
                "adamig", "1-3");
        String cls = provider.getDatasetClass("ADEFF");
        Rule rule = ruleWithClassInclude("BASIC DATA STRUCTURE", "ADAM OTHER");
        assertNull(ScopeMatcher.describeClassMismatch(rule, cls));
        assertTrue(ScopeMatcher.matchesClass(rule, cls));
    }


    @Test
    void getDatasetClass_adamRealBdsWithDefineClass_isNotMislabelledAdamOther()
    {
        // Gate check: a real BDS dataset that carries a Define-XML/study class must keep it — the
        // FU-4 fallback only fires when every tier falls through. A rule scoped to ["ADAM OTHER"]
        // must NOT apply to it.
        IMetadataLibrary study = lib("study")
                .table(table("ADLBC").className("BASIC DATA STRUCTURE").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkAdamProduct(),
                "adamig", "1-3");
        String cls = provider.getDatasetClass("ADLBC");
        assertEquals("BASIC DATA STRUCTURE", cls);
        assertFalse(ScopeMatcher.matchesClass(ruleWithClassInclude("ADAM OTHER"), cls));
    }


    @Test
    void getDatasetClass_nonAdamUnknownDomain_staysNullNotAdamOther()
    {
        // Family gate: an SDTM run with an undetectable domain must still return null — the
        // sentinel
        // is ADaM-only and must never leak into SDTM/SEND runs.
        IMetadataLibrary study = lib("study").build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkSdtmProduct(),
                "sdtmig", "3-4");
        assertNull(provider.getDatasetClass("MYXX"));
    }


    @Test
    void getDatasetClass_adamBdsColumnsButNoResolvedClass_isNotMislabelledAdamOther()
    {
        // FU-4 positive structure-absence gate: an ADaM dataset whose class no tier resolves but
        // which carries BDS indicator columns (PARAM/AVAL) is NOT structure-less, so the "ADAM
        // OTHER" sentinel must NOT fire — it stays null (unresolved) and a rule scoped to
        // ["ADAM OTHER"] does not reach it. Mirrors the Python hasNoAdamStructureIndicators gate.
        IMetadataLibrary study = lib("study")
                .table(table("ADBDS").column(column("STUDYID", 0, DataValueType.STRING).build())
                        .column(column("USUBJID", 1, DataValueType.STRING).build())
                        .column(column("PARAM", 2, DataValueType.STRING).build())
                        .column(column("AVAL", 3, DataValueType.STRING).build()).build())
                .build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkAdamProduct(),
                "adamig", "1-3");
        String cls = provider.getDatasetClass("ADBDS");
        assertNull(cls);
        assertFalse(ScopeMatcher.matchesClass(ruleWithClassInclude("ADAM OTHER"), cls));
    }


    @Test
    void getDatasetClass_adamCodedTermColumn_isNoLongerAdamOther_fix140()
    {
        // Fix #140 (EC-50): adding DECOD to the OCCDS suffixes widens
        // AdamDataStructureDetector.hasNoStructureIndicators, which is the FU-4 gate. A dataset
        // carrying a dictionary-coded term column is an OCCDS dataset, not a structure-less one,
        // so the "ADAM OTHER" sentinel must NOT fire for it any more and the shipped
        // Scope.Classes:["ADAM OTHER"] rules no longer reach it. This is the one consequence of
        // Fix #140 that lands outside the ADaM scope gates — it is intended, and pinned here.
        //
        // The population was 8 when this was written; it is 3 as of 2026-08-10 — PMDA-AD0376,
        // PMDA-AD1011, PMDA-AD1012A. The five PMDA-AD0252* rules dropped the token because the
        // PMDA workbook's DOMAINS cell for AD0252 does not name ADAMOTHER (AD0252A-D have no
        // sheet row at all), so it was coreJ-authored rather than sheet-faithful. The three that
        // remain are the ones whose sheet row does name it. See
        // plans/done/PLAN-adam-other-scope-b-g2.md.
        //
        // This assertion does not depend on the count — it pins the detector, not the corpus — so
        // the number above is context, not a ratchet. Do not turn it into one.
        IMetadataLibrary study = lib("study")
                .table(table("ADXAE").column(column("STUDYID", 0, DataValueType.STRING).build())
                        .column(column("USUBJID", 1, DataValueType.STRING).build())
                        .column(column("AEDECOD", 2, DataValueType.STRING).build()).build())
                .build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkAdamProduct(),
                "adamig", "1-3");
        String cls = provider.getDatasetClass("ADXAE");
        assertNull(cls);
        assertFalse(ScopeMatcher.matchesClass(ruleWithClassInclude("ADAM OTHER"), cls));
    }


    @Test
    void getDatasetClass_threeArg_adamActualColumns_gateReadsThem()
    {
        // The 3-arg (actual-columns) path also reaches the FU-4 gate when the sniffer misses: with
        // only non-indicator columns the dataset resolves to the sentinel; with a BDS indicator
        // column present it stays null.
        IMetadataLibrary study = lib("study").build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkAdamProduct(),
                "adamig", "1-3");
        assertEquals("ADAM OTHER",
                provider.getDatasetClass("ADXX", "ADXX", java.util.Set.of("STUDYID", "USUBJID")));
        assertNull(provider.getDatasetClass("ADYY", "ADYY", java.util.Set.of("STUDYID", "AVAL")));
    }

    // ------------------------------------------------------------------
    // degraded(...) factory
    // ------------------------------------------------------------------


    @Test
    void degraded_returnsLibraryUnavailableForClassHierarchy()
    {
        IMetadataLibrary study = lib("study").table(table("LB")
                .column(column("STUDYID", 0, DataValueType.STRING).core("Req").build())
                .column(column("USUBJID", 1, DataValueType.STRING).core("Req").build()).build())
                .build();
        MetadataLibraryProvider provider = MetadataLibraryProvider.degraded(study,
                new IOException("HTTP 503"));

        // Class-hierarchy queries: signal "library not available".
        assertEquals(List.of(), provider.getModelColumnOrder("LB"));
        assertNull(provider.getStandardModelVariables(mockTable("LB"), null));

        // Non-class-hierarchy queries continue to work via the underlying IMetadataLibrary.
        assertEquals(List.of("STUDYID", "USUBJID"), provider.getRequiredVariables("LB"));
        assertEquals(List.of("STUDYID", "USUBJID"), provider.getColumnOrder("LB"));
    }


    @Test
    void degraded_getDatasetClass_studyClassStillWins()
    {
        // In degraded mode tier-2 (product reverse-walk) is suppressed because the products are
        // null anyway. Tier-1 (study/Define-XML class) still works.
        IMetadataLibrary study = lib("study").table(table("LB").className("Findings").build())
                .build();
        MetadataLibraryProvider provider = MetadataLibraryProvider.degraded(study,
                new IOException("HTTP 500"));
        assertEquals("Findings", provider.getDatasetClass("LB"));
    }


    @Test
    void degraded_getDatasetClass_unknownDomainReturnsNull()
    {
        IMetadataLibrary study = lib("study").build();
        MetadataLibraryProvider provider = MetadataLibraryProvider.degraded(study,
                new IOException("boom"));
        // A domain absent from the curated DomainClassMap (and unresolvable by any tier) still
        // returns null even in degraded mode. (Known domains like LB now resolve via Tier 2.5.)
        assertNull(provider.getDatasetClass("ZZ"));
    }


    @Test
    void degraded_acceptsNullCauseWithoutCrashing()
    {
        IMetadataLibrary study = lib("study").build();
        MetadataLibraryProvider provider = MetadataLibraryProvider.degraded(study, null);
        assertEquals(List.of(), provider.getModelColumnOrder("LB"));
    }

    // ------------------------------------------------------------------
    // Standard / version override via product-aware constructor
    // ------------------------------------------------------------------


    @Test
    void productAwareConstructorPropagatesStandardAndVersion()
    {
        // Caller-supplied standard / version trump anything in the IMetadataLibrary's meta keys.
        IMetadataLibrary study = lib("study").meta(MetadataKeys.STANDARD_NAME, "OLD")
                .meta(MetadataKeys.STANDARD_VERSION, "0-0").build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkSdtmProduct(),
                "sdtmig", "3-4");
        assertEquals("sdtmig", provider.getStandard());
        assertEquals("3-4", provider.getVersion());
    }

    // ------------------------------------------------------------------
    // Fix #41 — getDatasetClass tier-3 custom-domain sniffer
    // ------------------------------------------------------------------


    @Test
    void getDatasetClass_tier3SnifferFiresWhenLibraryAndProductMiss()
    {
        // Custom domain MYAE with topic-variable AETERM-prefixed pattern. Library doesn't know
        // it (no className meta); product reverse-walk doesn't find it; tier-3 sniffer runs
        // and returns EVENTS.
        IMetadataLibrary study = lib("study")
                .table(table("MYAE").column(column("STUDYID", 0, DataValueType.STRING).build())
                        .column(column("USUBJID", 1, DataValueType.STRING).build())
                        .column(column("DOMAIN", 2, DataValueType.STRING).build())
                        .column(column("MYAETERM", 3, DataValueType.STRING).build()).build())
                .build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkSdtmProduct(),
                "sdtmig", "3-4");
        assertEquals("EVENTS", provider.getDatasetClass("MYAE"));
    }


    @Test
    void getDatasetClass_tier3SnifferReturnsNullWhenNoTopicMatches()
    {
        // Custom domain with neither DOMAIN nor RDOMAIN — sniffer can't help. Provider
        // returns null; ScopeMatcher's strict-on-null then skips class-scoped rules with
        // the per-dataset WARN.
        IMetadataLibrary study = lib("study")
                .table(table("MYDM").column(column("STUDYID", 0, DataValueType.STRING).build())
                        .column(column("USUBJID", 1, DataValueType.STRING).build()).build())
                .build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkSdtmProduct(),
                "sdtmig", "3-4");
        assertNull(provider.getDatasetClass("MYDM"));
    }


    @Test
    void getDatasetClass_tier1StillWinsOverSniffer()
    {
        // Define-XML / study class set explicitly → tier 1 wins, sniffer is never called.
        IMetadataLibrary study = lib("study")
                .table(table("MYAE").className("DefineXmlPicked")
                        .column(column("STUDYID", 0, DataValueType.STRING).build())
                        .column(column("DOMAIN", 1, DataValueType.STRING).build())
                        .column(column("MYAETERM", 2, DataValueType.STRING).build()).build())
                .build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study);
        assertEquals("DefineXmlPicked", provider.getDatasetClass("MYAE"));
    }


    @Test
    void getDatasetClass_tier3FiresOnNoProductBranch()
    {
        // No product configured → tier 2 inactive. Sniffer still runs as tier 3 against
        // study-only metadata. Confirms the sniffer doesn't depend on a product being loaded.
        IMetadataLibrary study = lib("study")
                .table(table("MYCM").column(column("STUDYID", 0, DataValueType.STRING).build())
                        .column(column("DOMAIN", 1, DataValueType.STRING).build())
                        .column(column("MYCMTRT", 2, DataValueType.STRING).build()).build())
                .build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study);
        assertEquals("INTERVENTIONS", provider.getDatasetClass("MYCM"));
    }


    @Test
    void getDatasetClass_threeArg_classifiesFromActualColumnsWhenLibraryLacksTable()
    {
        // SUPPAE is absent from the (product-derived / study) metadata library — so tier 1 & the
        // library-table sniff can't see it. The actual dataset columns (RDOMAIN + QNAM) classify it
        // as RELATIONSHIP, mirroring Python's handle_custom_domains on the loaded dataset (Part B).
        IMetadataLibrary study = lib("study").table(
                table("DM").column(column("STUDYID", 0, DataValueType.STRING).build()).build())
                .build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study);
        assertEquals("RELATIONSHIP", provider.getDatasetClass("SUPPAE", "SUPPAE",
                java.util.Set.of("RDOMAIN", "USUBJID", "QNAM", "QVAL")));
        // Without the actual columns (and no library table) it cannot resolve — the regression the
        // pickle wiring would otherwise cause if Part B were absent.
        assertNull(provider.getDatasetClass("SUPPAE", "SUPPAE"));
    }


    @Test
    void getDatasetClass_threeArg_tier1StillWinsOverActualColumns()
    {
        // A real tier-1 (Define-XML) class must not be overridden by the column sniffer.
        IMetadataLibrary study = lib("study").table(table("MYAE").className("DefineXmlPicked")
                .column(column("STUDYID", 0, DataValueType.STRING).build())
                .column(column("DOMAIN", 1, DataValueType.STRING).build()).build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study);
        assertEquals("DefineXmlPicked",
                provider.getDatasetClass("MYAE", "MYAE", java.util.Set.of("DOMAIN", "MYAETERM")));
    }


    @Test
    void getDatasetClass_degradedSuppressesTier2ButLeavesTier3()
    {
        // Degraded mode: product reverse-walk suppressed (libraryFailed = true), but the
        // sniffer (tier 3) is library-independent and still runs.
        IMetadataLibrary study = lib("study")
                .table(table("MYLB").column(column("STUDYID", 0, DataValueType.STRING).build())
                        .column(column("DOMAIN", 1, DataValueType.STRING).build())
                        .column(column("MYLBTESTCD", 2, DataValueType.STRING).build()).build())
                .build();
        MetadataLibraryProvider provider = MetadataLibraryProvider.degraded(study,
                new IOException("HTTP 500"));
        assertEquals("FINDINGS", provider.getDatasetClass("MYLB"));
    }

    // ------------------------------------------------------------------
    // Fix #60 — 2-arg getDatasetClass(memberName, cdiscDomain) for split datasets
    // ------------------------------------------------------------------


    @Test
    void getDatasetClass_twoArg_splitDataset_productReverseWalkUsesCdiscCode()
    {
        // LBHE-style split: IMetadataLibrary registers the table under the member name "LBHE";
        // the CDISC domain code "LB" is what the SDTM product knows. Tier 1 (study) has no
        // className metadata, so tier 2 fires — keyed by CDISC code "LB" and finds Findings.
        IMetadataLibrary study = lib("study").table(table("LBHE").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkSdtmProduct(),
                "sdtmig", "3-4");
        assertEquals("Findings", provider.getDatasetClass("LBHE", "LB"));
    }


    @Test
    void getDatasetClass_twoArg_splitDataset_singleArgWithCdiscCodeWouldMiss()
    {
        // Documents the bug Fix #60 closes: passing the custom CDISC code "MY" as a single
        // argument misses the IMetadataLibrary lookup (member is "MYHE") AND, because the study
        // has no table for "MY", the sniffer can't run either. A custom domain is used so the
        // curated DomainClassMap (Tier 2.5) does not resolve it — the resolver returns null. The
        // 2-arg form below resolves the same setup correctly via the tier-3 sniffer.
        IMetadataLibrary study = lib("study")
                .table(table("MYHE").column(column("STUDYID", 0, DataValueType.STRING).build())
                        .column(column("DOMAIN", 1, DataValueType.STRING).build())
                        .column(column("MYTESTCD", 2, DataValueType.STRING).build()).build())
                .build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study);
        // Single-arg with the CDISC code can't reach the MYHE-keyed table → null.
        assertNull(provider.getDatasetClass("MY"));
        // 2-arg form with member "MYHE" + CDISC "MY" finds the table (member key) and the
        // sniffer matches MYTESTCD against the CDISC prefix → FINDINGS.
        assertEquals("FINDINGS", provider.getDatasetClass("MYHE", "MY"));
    }


    @Test
    void getDatasetClass_twoArg_studyClassStillWinsTier1()
    {
        // Tier 1 (study className) wins regardless of which key carries the explicit metadata,
        // so long as the IMetadataLibrary lookup succeeds. The lookup goes by member name.
        IMetadataLibrary study = lib("study")
                .table(table("LBHE").className("OverriddenByDefine").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkSdtmProduct(),
                "sdtmig", "3-4");
        assertEquals("OverriddenByDefine", provider.getDatasetClass("LBHE", "LB"));
    }


    @Test
    void getDatasetClass_twoArg_singleArgFormUsesSameKeyForBothTiers()
    {
        // The single-arg form is a same-key delegation — preserves pre-Fix-#60 behaviour for
        // callers that only have one identifier (e.g. legacy tests). Same value is used as
        // both member name and CDISC code. The body matches the
        // productReverseWalk_whenStudyClassIsNull scenario; this test guards a different
        // contract (single-arg delegation) and is intentionally kept distinct.
        IMetadataLibrary study = lib("study").table(table("LB").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkSdtmProduct(),
                "sdtmig", "3-4");
        String resolved = provider.getDatasetClass("LB");
        assertEquals("Findings", resolved);
        // Sanity: the two-arg form with the same key resolves to the same class — proves the
        // single-arg form is the same-key shortcut.
        assertEquals(resolved, provider.getDatasetClass("LB", "LB"));
    }

    // ------------------------------------------------------------------
    // Fix #61 — SUPP/SQ class-resolution A→B→C cascade
    // ------------------------------------------------------------------


    private static SdtmProduct mkSdtmIgWithSuppQual(boolean withDatasetVariables)
    {
        // Minimal IG: Findings + Events + a Relationship Datasets class containing SUPPQUAL.
        // When `withDatasetVariables` is true, SUPPQUAL.datasetVariables is populated — tier A
        // fires. When false, SUPPQUAL exists but its datasetVariables list is empty — tier A
        // misses and the resolver falls through to the Model.
        List<Map<String, Object>> findingsVars = new ArrayList<>();
        findingsVars.add(sdtmVar("STUDYID", "Study Identifier", "1", "Char", "Req"));
        Map<String, Object> lb = sdtmDataset("LB", "Laboratory Test Results",
                "One record per measurement");
        Map<String, Object> findings = sdtmClass("Findings", findingsVars, List.of(lb));

        Map<String, Object> suppQual = new LinkedHashMap<>();
        suppQual.put("name", "SUPPQUAL");
        suppQual.put("label", "Supplemental Qualifiers");
        suppQual.put("datasetStructure", "One record per IDVAR per QNAM per parent record");
        if (withDatasetVariables)
        {
            List<Map<String, Object>> suppVars = new ArrayList<>();
            suppVars.add(sdtmVar("STUDYID", "Study Identifier", "1", "Char", "Req"));
            suppVars.add(sdtmVar("RDOMAIN", "Related Domain Abbreviation", "2", "Char", "Req"));
            suppVars.add(sdtmVar("USUBJID", "Unique Subject Identifier", "3", "Char", "Exp"));
            suppVars.add(sdtmVar("IDVAR", "Identifying Variable", "4", "Char", "Exp"));
            suppVars.add(sdtmVar("IDVARVAL", "Identifying Variable Value", "5", "Char", "Exp"));
            suppVars.add(sdtmVar("QNAM", "Qualifier Variable Name", "6", "Char", "Req"));
            suppVars.add(sdtmVar("QLABEL", "Qualifier Variable Label", "7", "Char", "Req"));
            suppVars.add(sdtmVar("QVAL", "Data Value", "8", "Char", "Req"));
            suppVars.add(sdtmVar("QORIG", "Origin", "9", "Char", "Req"));
            suppVars.add(sdtmVar("QEVAL", "Evaluator", "10", "Char", "Perm"));
            suppQual.put("datasetVariables", suppVars);
        }
        else
        {
            suppQual.put("datasetVariables", List.of());
        }
        Map<String, Object> relationship = sdtmClass("Relationship Datasets", List.of(),
                List.of(suppQual));

        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", "SDTMIG");
        product.put("version", "3-4");
        product.put("classes", List.of(findings, relationship));
        return MapResource.of(product, SdtmProduct.class);
    }


    private static SdtmProduct mkSdtmModelWithRelationship()
    {
        // Minimal SDTM Model: a RELATIONSHIP class with the canonical SUPPQUAL variable set.
        // Used to exercise tier B when the IG's SUPPQUAL has empty datasetVariables.
        List<Map<String, Object>> relVars = new ArrayList<>();
        relVars.add(sdtmVar("STUDYID", "Study Identifier", "1", "Char", "Req"));
        relVars.add(sdtmVar("RDOMAIN", "Related Domain Abbreviation", "2", "Char", "Req"));
        relVars.add(sdtmVar("USUBJID", "Unique Subject Identifier", "3", "Char", "Exp"));
        relVars.add(sdtmVar("IDVAR", "Identifying Variable", "4", "Char", "Exp"));
        relVars.add(sdtmVar("IDVARVAL", "Identifying Variable Value", "5", "Char", "Exp"));
        relVars.add(sdtmVar("QNAM", "Qualifier Variable Name", "6", "Char", "Req"));
        relVars.add(sdtmVar("QLABEL", "Qualifier Variable Label", "7", "Char", "Req"));
        relVars.add(sdtmVar("QVAL", "Data Value", "8", "Char", "Req"));
        relVars.add(sdtmVar("QORIG", "Origin", "9", "Char", "Req"));
        relVars.add(sdtmVar("QEVAL", "Evaluator", "10", "Char", "Perm"));
        Map<String, Object> rel = sdtmClass("Relationship", relVars, List.of());

        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", "SDTM");
        product.put("version", "2-0");
        product.put("classes", List.of(rel));
        return MapResource.of(product, SdtmProduct.class);
    }


    private static IDataTable mkSuppTable(String aMemberName)
    {
        IDataTable table = mock(IDataTable.class);
        DataTableMeta meta = mock(DataTableMeta.class);
        lenient().when(meta.getName()).thenReturn(aMemberName);
        // DOMAIN column present at index 0; row 0 carries the parent CDISC code or "SUPPQUAL".
        lenient().when(meta.getColumnIndex("DOMAIN")).thenReturn(-1);
        lenient().when(table.getMetaData()).thenReturn(meta);
        lenient().when(table.getRowCount()).thenReturn(0L);
        // CdiscDomainResolver will fall through to SplitDatasetUtil.unsplitName(memberName).
        // For "SUPPAE" that yields "SUPPAE"; the SUPP/SQ pivot inside buildResolvedSdtm rewrites
        // it to SUPPQUAL.
        return table;
    }


    @Test
    void supp_tierA_igDatasetVariablesUsedFirst()
    {
        IMetadataLibrary study = lib("study").table(table("SUPPAE").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study,
                mkSdtmIgWithSuppQual(true), mkSdtmModelWithRelationship(), "sdtmig", "3-4");
        List<String> vars = provider.getStandardModelVariables(mkSuppTable("SUPPAE"), null);
        assertNotNull(vars);
        // IG fixture only has 10 vars (no POOLID/SPDEVID); tier A wins so we get exactly those 10.
        assertEquals(List.of("STUDYID", "RDOMAIN", "USUBJID", "IDVAR", "IDVARVAL", "QNAM", "QLABEL",
                "QVAL", "QORIG", "QEVAL"), vars);
    }


    @Test
    void supp_tierB_modelClassFiresWhenIgDatasetVariablesEmpty()
    {
        IMetadataLibrary study = lib("study").table(table("SUPPAE").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study,
                mkSdtmIgWithSuppQual(false), mkSdtmModelWithRelationship(), "sdtmig", "3-4");
        List<String> vars = provider.getStandardModelVariables(mkSuppTable("SUPPAE"), null);
        assertNotNull(vars);
        // Tier B fires: same canonical 10 from the Model's RELATIONSHIP class.
        assertEquals(10, vars.size());
        assertTrue(vars.contains("RDOMAIN"));
        assertTrue(vars.contains("QNAM"));
        assertTrue(vars.contains("QVAL"));
    }


    @Test
    void supp_tierC_canonicalFallbackFiresWhenBothIgAndModelMiss()
    {
        // IG has SUPPQUAL with empty datasetVariables; no Model product configured.
        IMetadataLibrary study = lib("study").table(table("SUPPAE").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study,
                mkSdtmIgWithSuppQual(false), null, "sdtmig", "3-4");
        List<String> vars = provider.getStandardModelVariables(mkSuppTable("SUPPAE"), null);
        assertNotNull(vars);
        // Tier C: invariant 12-element list (includes POOLID + SPDEVID per SDTM Model 2.0).
        assertEquals(12, vars.size());
        assertEquals("STUDYID", vars.get(0));
        assertTrue(vars.contains("POOLID"));
        assertTrue(vars.contains("SPDEVID"));
        assertTrue(vars.contains("QEVAL"));
    }


    @Test
    void supp_tierC_firesWhenIgHasNoSuppQualDatasetAtAll()
    {
        // IG without a Relationship class entirely — common when the test fixture only models
        // Findings/Events. Tier A misses (no dataset), tier B misses (no Model), tier C fires.
        IMetadataLibrary study = lib("study").table(table("SUPPAE").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkSdtmProduct(), null,
                "sdtmig", "3-4");
        List<String> vars = provider.getStandardModelVariables(mkSuppTable("SUPPAE"), null);
        assertNotNull(vars);
        assertEquals(12, vars.size());
        assertTrue(vars.contains("RDOMAIN"));
        assertTrue(vars.contains("QNAM"));
    }


    @Test
    void supp_legacyTwoArgConstructorSkipsTierB()
    {
        // Pre-Fix-#61 callers using the IG-only constructor get tier A → tier C cascade. Tier B
        // (the Model) is unreachable because no Model product was passed.
        IMetadataLibrary study = lib("study").table(table("SUPPAE").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study,
                mkSdtmIgWithSuppQual(false), "sdtmig", "3-4");
        List<String> vars = provider.getStandardModelVariables(mkSuppTable("SUPPAE"), null);
        assertNotNull(vars);
        // IG SUPPQUAL has empty datasetVariables → tier A misses → no Model → tier C fires.
        assertEquals(12, vars.size());
    }


    @Test
    void sq_pivotsToSameSuppQualPath()
    {
        // SQ-prefixed dataset shares the SUPPQUAL pivot. Mirror Python's behaviour: SQ* domains
        // resolve via the same A→B→C cascade as SUPP*.
        IMetadataLibrary study = lib("study").table(table("SQAE").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study,
                mkSdtmIgWithSuppQual(true), mkSdtmModelWithRelationship(), "sdtmig", "3-4");
        List<String> vars = provider.getStandardModelVariables(mkSuppTable("SQAE"), null);
        assertNotNull(vars);
        // Tier A wins — same 10 IG-driven variables.
        assertEquals(10, vars.size());
        assertEquals("RDOMAIN", vars.get(1));
    }


    @Test
    void supp_detailedShape_tierC_carriesAttributeMaps()
    {
        // The detailed shape (Python variables_metadata) projects role/core/ordinal alongside
        // name. Tier C fixtures hard-code those, so the detailed call should round-trip them.
        IMetadataLibrary study = lib("study").table(table("SUPPAE").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkSdtmProduct(), null,
                "sdtmig", "3-4");
        List<Map<String, String>> detailed = provider
                .getStandardModelVariablesDetailed(mkSuppTable("SUPPAE"), null);
        assertNotNull(detailed);
        Map<String, Map<String, String>> byName = new LinkedHashMap<>();
        for (Map<String, String> v : detailed)
        {
            byName.put(v.get("name"), v);
        }
        assertEquals("Identifier", byName.get("STUDYID").get("role"));
        assertEquals("Req", byName.get("STUDYID").get("core"));
        assertEquals("Topic", byName.get("QNAM").get("role"));
        assertEquals("Result Qualifier", byName.get("QVAL").get("role"));
    }

    // ------------------------------------------------------------------
    // Algorithm A (buildResolvedSdtmModel) — non-detectable three-tier fallback
    // and getStandardVariablesDetailed (algorithm B) — coverage of the new branches.
    // ------------------------------------------------------------------


    /** IG with a non-detectable Special-Purpose DM dataset; classes carry no classVariables. */
    private static SdtmProduct mkSdtmIgWithDm()
    {
        List<Map<String, Object>> dmVars = new ArrayList<>();
        dmVars.add(sdtmVar("STUDYID", "Study Identifier", "1", "Char", "Req"));
        dmVars.add(sdtmVar("USUBJID", "Unique Subject Identifier", "2", "Char", "Req"));
        dmVars.add(sdtmVar("IGONLY", "IG Only Var", "3", "Char", "Perm"));
        Map<String, Object> dm = new LinkedHashMap<>();
        dm.put("name", "DM");
        dm.put("label", "Demographics");
        dm.put("datasetStructure", "One record per subject");
        dm.put("datasetVariables", dmVars);
        Map<String, Object> specialPurpose = sdtmClass("Special-Purpose", List.of(), List.of(dm));
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", "SDTMIG");
        product.put("version", "3-4");
        product.put("classes", List.of(specialPurpose));
        return MapResource.of(product, SdtmProduct.class);
    }


    /**
     * SDTM Model variant for the non-detectable tier-1/tier-2 fallback. When {@code withClassVars}
     * is true the Special-Purpose class carries classVariables (tier 1); otherwise the class is
     * empty and the Model exposes a top-level {@code datasets} array carrying DM (tier 2).
     */
    private static SdtmProduct mkSdtmModelWithDm(boolean withClassVars)
    {
        List<Map<String, Object>> spVars = new ArrayList<>();
        spVars.add(sdtmVar("STUDYID", "Study Identifier", "1", "Char", "Req"));
        spVars.add(sdtmVar("USUBJID", "Unique Subject Identifier", "2", "Char", "Req"));
        spVars.add(sdtmVar("MODELCLASSVAR", "Model Class Var", "3", "Char", "Perm"));

        List<Map<String, Object>> dmDatasetVars = new ArrayList<>();
        dmDatasetVars.add(sdtmVar("STUDYID", "Study Identifier", "1", "Char", "Req"));
        dmDatasetVars.add(sdtmVar("USUBJID", "Unique Subject Identifier", "2", "Char", "Req"));
        dmDatasetVars.add(sdtmVar("MODELDOMAINVAR", "Model Domain Var", "3", "Char", "Perm"));
        Map<String, Object> dmDataset = new LinkedHashMap<>();
        dmDataset.put("name", "DM");
        dmDataset.put("datasetVariables", dmDatasetVars);

        Map<String, Object> sp = sdtmClass("Special-Purpose", withClassVars ? spVars : List.of(),
                List.of());
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", "SDTM");
        product.put("version", "2-0");
        product.put("classes", List.of(sp));
        product.put("datasets", withClassVars ? List.of() : List.of(dmDataset));
        return MapResource.of(product, SdtmProduct.class);
    }


    @Test
    void algoA_nonDetectable_tier1_modelClassVariables()
    {
        // Model Special-Purpose class HAS classVariables → tier 1 wins (no IG overwrite under
        // algorithm A). The Model class var MODELCLASSVAR appears; the IG-only var does not.
        IMetadataLibrary study = lib("study").table(table("DM").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkSdtmIgWithDm(),
                mkSdtmModelWithDm(true), "sdtmig", "3-4");
        List<String> vars = provider.getStandardModelVariables(mockTable("DM"), null);
        assertNotNull(vars);
        assertTrue(vars.contains("MODELCLASSVAR"), "tier 1 uses Model class vars: " + vars);
        assertFalse(vars.contains("IGONLY"), "algorithm A does not pull IG-only vars: " + vars);
    }


    @Test
    void algoA_nonDetectable_tier2_modelDomainDatasetVariables()
    {
        // Model Special-Purpose class is empty → tier 2 reads the Model's top-level DM dataset.
        IMetadataLibrary study = lib("study").table(table("DM").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkSdtmIgWithDm(),
                mkSdtmModelWithDm(false), "sdtmig", "3-4");
        List<String> vars = provider.getStandardModelVariables(mockTable("DM"), null);
        assertNotNull(vars);
        assertTrue(vars.contains("MODELDOMAINVAR"), "tier 2 uses Model domain vars: " + vars);
        assertFalse(vars.contains("IGONLY"), "tier 2 precedes the IG tier: " + vars);
    }


    @Test
    void algoA_nonDetectable_tier3_igDatasetVariables()
    {
        // No Model product at all → tier 1 and tier 2 miss; tier 3 reads the IG DM dataset.
        IMetadataLibrary study = lib("study").table(table("DM").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkSdtmIgWithDm(),
                null, "sdtmig", "3-4");
        List<String> vars = provider.getStandardModelVariables(mockTable("DM"), null);
        assertNotNull(vars);
        assertTrue(vars.contains("IGONLY"), "tier 3 falls back to IG dataset vars: " + vars);
    }


    @Test
    void algoA_sqAp_nonDetectable_mergesAssociatedPersonsIdentifiers()
    {
        // SQDM pivots to SUPPQUAL — but to exercise the assembleNonDetectable AP-merge branch we
        // use an AP-prefixed non-detectable domain. APDM strips AP → DM (Special-Purpose,
        // non-detectable) and merges ASSOCIATED PERSONS identifiers (minus USUBJID).
        IMetadataLibrary study = lib("study").table(table("APDM").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkSdtmIgWithDmAndAp(),
                mkSdtmModelWithDmAndAp(), "sdtmig", "3-4");
        List<String> vars = provider.getStandardModelVariables(mockTable("APDM"), null);
        assertNotNull(vars);
        assertTrue(vars.contains("APID"), "AP identifier merged into non-detectable set: " + vars);
        // USUBJID is excluded from the AP-identifier merge but stays from the base DM vars.
        assertTrue(vars.contains("STUDYID"), "base DM vars retained: " + vars);
    }


    /** IG with DM (Special-Purpose) and an ASSOCIATED PERSONS class. */
    private static SdtmProduct mkSdtmIgWithDmAndAp()
    {
        List<Map<String, Object>> dmVars = new ArrayList<>();
        dmVars.add(sdtmVar("STUDYID", "Study Identifier", "1", "Char", "Req"));
        dmVars.add(sdtmVar("USUBJID", "Unique Subject Identifier", "2", "Char", "Req"));
        Map<String, Object> dm = new LinkedHashMap<>();
        dm.put("name", "DM");
        dm.put("datasetVariables", dmVars);
        Map<String, Object> sp = sdtmClass("Special-Purpose", List.of(), List.of(dm));
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", "SDTMIG");
        product.put("version", "3-4");
        product.put("classes", List.of(sp));
        return MapResource.of(product, SdtmProduct.class);
    }


    /** Model with empty Special-Purpose class, a top-level DM dataset, and an AP class. */
    private static SdtmProduct mkSdtmModelWithDmAndAp()
    {
        List<Map<String, Object>> dmVars = new ArrayList<>();
        dmVars.add(sdtmVar("STUDYID", "Study Identifier", "1", "Char", "Req"));
        dmVars.add(sdtmVar("USUBJID", "Unique Subject Identifier", "2", "Char", "Req"));
        Map<String, Object> dm = new LinkedHashMap<>();
        dm.put("name", "DM");
        dm.put("datasetVariables", dmVars);

        List<Map<String, Object>> apVars = new ArrayList<>();
        apVars.add(sdtmVar("USUBJID", "Unique Subject Identifier", "1", "Char", "Req"));
        apVars.add(sdtmVar("APID", "Associated Person Identifier", "2", "Char", "Req"));
        Map<String, Object> ap = sdtmClass("Associated Persons", apVars, List.of());
        Map<String, Object> sp = sdtmClass("Special-Purpose", List.of(), List.of());

        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", "SDTM");
        product.put("version", "2-0");
        product.put("classes", List.of(sp, ap));
        product.put("datasets", List.of(dm));
        return MapResource.of(product, SdtmProduct.class);
    }


    @Test
    void getStandardVariablesDetailed_algoB_returnsAttributeMaps()
    {
        IMetadataLibrary study = lib("study").table(table("DM").build()).build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study, mkSdtmIgWithDm(),
                mkSdtmModelWithDm(false), "sdtmig", "3-4");
        List<Map<String, String>> detailed = provider.getStandardVariablesDetailed(mockTable("DM"),
                null);
        assertNotNull(detailed);
        // Algorithm B for non-detectable DM returns the IG dataset variables (incl. IGONLY).
        assertTrue(detailed.stream().anyMatch(m -> "IGONLY".equals(m.get("name"))),
                "algorithm B exposes IG dataset vars: " + detailed);
    }


    @Test
    void getStandardVariablesDetailed_nullSignals()
    {
        // No product → null. Degraded → null. Null table → null.
        MetadataLibraryProvider noProduct = new MetadataLibraryProvider(studyWith("DM"));
        assertNull(noProduct.getStandardVariablesDetailed(mockTable("DM"), null));

        MetadataLibraryProvider degraded = MetadataLibraryProvider.degraded(studyWith("DM"),
                new IOException("HTTP 503"));
        assertNull(degraded.getStandardVariablesDetailed(mockTable("DM"), null));

        MetadataLibraryProvider withProduct = new MetadataLibraryProvider(studyWith("DM"),
                mkSdtmIgWithDm(), "sdtmig", "3-4");
        assertNull(withProduct.getStandardVariablesDetailed(null, null));
    }
}
