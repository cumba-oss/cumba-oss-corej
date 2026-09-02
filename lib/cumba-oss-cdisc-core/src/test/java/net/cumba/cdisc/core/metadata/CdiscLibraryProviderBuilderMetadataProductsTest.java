package net.cumba.cdisc.core.metadata;

import static net.cumba.datatable.testkit.TestMetadataFixtures.lib;
import static net.cumba.datatable.testkit.TestMetadataFixtures.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.CoreLibraryAccess;
import net.cumba.cdisc.core.CoreLibraryAccessImpl;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.library.api.client.CdiscLibraryClient;
import net.cumba.cdisc.library.api.model.adam.AdamProduct;
import net.cumba.datatable.metadata.IMetadataLibrary;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Phase 3 of {@code plans/PLAN-metadata-product-selection.md} — <b>the wiring</b>.
 *
 * <h2>Why this test is the load-bearing one</h2>
 *
 * <p>
 * Phase 2 gave {@link MetadataLibraryProvider} an ordered list of declared ADaM products, and Phase
 * 3 gave it a subclass-aware precedence chain — but nothing constructed a provider with more than
 * one product, so {@code StudyValidationParams.metadataProducts()} reached nothing and the whole
 * mechanism was <b>inert on more than one product</b>. A green build proves nothing about that; the
 * only thing that does is a two-product build resolving <em>differently</em> from a one-product
 * build, through the real {@link CdiscLibraryProviderBuilder} path a run takes.
 * </p>
 */
class CdiscLibraryProviderBuilderMetadataProductsTest
{

    private static final String ADAMIG_KEY = "standards/adam/adamig-1-3";

    private static final String OCCDS_KEY = "standards/adam/adam-occds-1-1";

    private static final String NCA_KEY = "standards/adam/adam-nca-1-0";

    private static final String OCCDS_TOKEN = AdamDataStructureDetector.OCCDS;

    private static final String ADVERSE_EVENT = AdamSubclassDetector.ADVERSE_EVENT;

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static Map<String, Object> adamVar(String name, String ordinal, String core)
    {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("name", name);
        v.put("ordinal", ordinal);
        v.put("simpleDatatype", "Char");
        v.put("core", core);
        return v;
    }


    private static Map<String, Object> structure(String name, @Nullable String className,
            @Nullable String subClass, List<Map<String, Object>> vars)
    {
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("name", "Variables");
        set.put("ordinal", "1");
        set.put("analysisVariables", vars);

        Map<String, Object> ds = new LinkedHashMap<>();
        ds.put("name", name);
        if (className != null)
        {
            ds.put("class", className);
        }
        if (subClass != null)
        {
            ds.put("subClass", subClass);
        }
        ds.put("analysisVariableSets", List.of(set));
        return ds;
    }


    @SafeVarargs

    @SuppressWarnings("varargs") // passes its own varargs array to List.of
    private static AdamProduct product(String name, Map<String, Object>... structures)
    {
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", name);
        product.put("version", "1-0");
        product.put("dataStructures", List.of(structures));
        return net.cumba.web.api.dev.MapResource.of(product, AdamProduct.class);
    }


    /** {@code adamig-1-3}: ADSL and BDS only — it publishes no occurrence structure at all. */
    private static AdamProduct adamig13()
    {
        return product("adamig", structure("ADSL", "SUBJECT LEVEL ANALYSIS DATASET", null,
                List.of(adamVar("USUBJID", "1", "Req"))));
    }


    /** {@code adam-occds-1-1}: a base OCCDS and an AE specialisation that disagree on --SEQ. */
    private static AdamProduct occds11()
    {
        return product("adam-occds",
                structure("OCCDS", "OCCURRENCE DATA STRUCTURE", null,
                        List.of(adamVar("USUBJID", "1", "Req"), adamVar("--SEQ", "2", "Cond"),
                                adamVar("CMTRT", "3", "Req"))),
                structure("AE", "OCCURRENCE DATA STRUCTURE", ADVERSE_EVENT,
                        List.of(adamVar("USUBJID", "1", "Req"), adamVar("--SEQ", "2", "Req"))));
    }


    private static IMetadataLibrary studyOnly()
    {
        return lib("study").table(table("ADAE").build()).build();
    }


    private static CoreLibraryAccess wrap(CdiscLibraryClient aClient)
    {
        return CoreLibraryAccessImpl.forTesting(aClient);
    }


    private static CdiscLibraryClient client() throws IOException
    {
        CdiscLibraryClient client = mock(CdiscLibraryClient.class);
        when(client.getAdamProduct("adamig-1-3", true)).thenReturn(adamig13());
        when(client.getAdamProduct("adam-occds-1-1", true)).thenReturn(occds11());
        return client;
    }


    private static MetadataProvider build(CdiscLibraryClient aClient, List<String> aProducts)
    {
        return CdiscLibraryProviderBuilder.from(wrap(aClient)).study(studyOnly()).standard("adamig")
                .version("1-3").metadataProducts(aProducts).ctPackageIds(List.of())
                .buildOrDegraded();
    }

    // ------------------------------------------------------------------
    // ⭐ The demonstration
    // ------------------------------------------------------------------


    @Test
    void twoProductsResolveDifferentlyFromOne() throws IOException
    {
        CdiscLibraryClient client = client();

        // (a) One product — today's run. adamig-1-3 publishes no occurrence structure, so an
        // occurrence dataset cannot resolve at all: null, i.e. the loud SKIP.
        MetadataProvider one = build(client, List.of(ADAMIG_KEY));
        assertNull(one.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of(ADVERSE_EVENT)));
        assertEquals(List.of(ADAMIG_KEY), one.declaredStructureKeyedProducts());

        // (b) Two products, the supplement first. The token now resolves — from the declared
        // supplement, and inside it the AE structure governs --SEQ (Req), while CMTRT, published
        // only by the base OCCDS behind it, still resolves.
        MetadataProvider two = build(client, List.of(OCCDS_KEY, ADAMIG_KEY));
        assertEquals(List.of("USUBJID", "--SEQ", "CMTRT"),
                two.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of(ADVERSE_EVENT)));

        // (c) …and the same two products with NO detected subclass answer from the base alone.
        // Under the union this returned --SEQ too, for every occurrence dataset in the study.
        assertEquals(List.of("USUBJID", "CMTRT"),
                two.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of()));

        assertNotEquals(one.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of(ADVERSE_EVENT)),
                two.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of(ADVERSE_EVENT)),
                "if these agree, the declared product list never reached the provider");
        assertEquals(List.of(OCCDS_KEY, ADAMIG_KEY), two.declaredStructureKeyedProducts());
        verify(client).getAdamProduct("adam-occds-1-1", true);
    }


    @Test
    void precedenceIsTheUsersOrder() throws IOException
    {
        // Reversing the declaration reverses which product answers the token — ruling 1, through
        // the real build path.
        CdiscLibraryClient client = client();
        MetadataProvider supplementFirst = build(client, List.of(OCCDS_KEY, ADAMIG_KEY));
        MetadataProvider igFirst = build(client, List.of(ADAMIG_KEY, OCCDS_KEY));

        assertEquals(List.of(ADAMIG_KEY, OCCDS_KEY), igFirst.declaredStructureKeyedProducts());
        // adamig-1-3 has no OCCURRENCE structure, so only the supplement can answer this token in
        // either order. ⚠ Since Phase 8 that is not what makes the two agree — candidates are
        // pooled across all declared products and specificity decides; the user's order is the
        // tie-break among equal specificity only (see
        // theSupplementsSubclassGovernsThroughTheBuildPathWhenItIsDeclaredSecond below, and
        // MetadataLibraryProviderCrossProductSpecificityTest).
        assertEquals(
                supplementFirst.getRequiredVariablesForStructure(OCCDS_TOKEN,
                        List.of(ADVERSE_EVENT)),
                igFirst.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of(ADVERSE_EVENT)));
        // …while the ADSL token only adamig publishes resolves from it in both orders.
        assertEquals(List.of("USUBJID"),
                igFirst.getRequiredVariablesForStructure(AdamDataStructureDetector.ADSL));
    }


    @Test
    void theSupplementsSubclassGovernsThroughTheBuildPathWhenItIsDeclaredSecond() throws IOException
    {
        // ⭐ Phase 8, through the REAL build path. Both products publish BASIC DATA STRUCTURE —
        // the IG as a base, the supplement only as NON-COMPARTMENTAL ANALYSIS — so before pooling
        // the IG declared first matched the token, adamStructuresForToken returned, and ADNCA was
        // never consulted: an NCA dataset silently got the base BDS's Cond values.
        CdiscLibraryClient client = mock(CdiscLibraryClient.class);
        when(client.getAdamProduct("adamig-1-3", true)).thenReturn(product("adamig",
                structure("ADSL", "SUBJECT LEVEL ANALYSIS DATASET", null,
                        List.of(adamVar("USUBJID", "1", "Req"))),
                structure("BDS", "BASIC DATA STRUCTURE", null,
                        List.of(adamVar("USUBJID", "1", "Req"), adamVar("AFRLT", "2", "Cond")))));
        when(client.getAdamProduct("adam-nca-1-0", true)).thenReturn(product("adam-nca",
                structure("ADNCA", "BASIC DATA STRUCTURE",
                        AdamSubclassDetector.NON_COMPARTMENTAL_ANALYSIS,
                        List.of(adamVar("AFRLT", "1", "Req")))));

        List<String> nca = List.of(AdamSubclassDetector.NON_COMPARTMENTAL_ANALYSIS);
        MetadataProvider igFirst = build(client, List.of(ADAMIG_KEY, NCA_KEY));
        MetadataProvider supplementFirst = build(client, List.of(NCA_KEY, ADAMIG_KEY));

        assertEquals(List.of("AFRLT", "USUBJID"),
                igFirst.getRequiredVariablesForStructure(AdamDataStructureDetector.BDS, nca),
                "ADNCA governs AFRLT even though its product is declared SECOND");
        assertEquals(
                supplementFirst.getRequiredVariablesForStructure(AdamDataStructureDetector.BDS,
                        nca),
                igFirst.getRequiredVariablesForStructure(AdamDataStructureDetector.BDS, nca),
                "the answer must not depend on declaration order");
        // …and a plain BDS dataset is unaffected in both orders: the ADNCA tier is not in its
        // chain at all.
        assertEquals(List.of("USUBJID"),
                igFirst.getRequiredVariablesForStructure(AdamDataStructureDetector.BDS));
        assertEquals(List.of("USUBJID"),
                supplementFirst.getRequiredVariablesForStructure(AdamDataStructureDetector.BDS));
    }

    // ------------------------------------------------------------------
    // Backwards compatibility (§1b′)
    // ------------------------------------------------------------------


    @Test
    void noDeclaredProducts_behavesExactlyAsBefore() throws IOException
    {
        // The .cdt harness and every caller that never sets -mp. One fetch, one product, the
        // derived key.
        CdiscLibraryClient client = client();
        MetadataProvider p = build(client, List.of());

        assertNotNull(p);
        assertEquals(List.of(ADAMIG_KEY), p.declaredStructureKeyedProducts());
        verify(client, times(1)).getAdamProduct(anyString(), anyBoolean());
    }


    @Test
    void theIgProductIsReusedRatherThanRefetched() throws IOException
    {
        // The -s/-v product is already in hand; declaring it must not cost a second round trip.
        CdiscLibraryClient client = client();
        MetadataProvider p = build(client, List.of(ADAMIG_KEY, OCCDS_KEY));

        assertNotNull(p);
        verify(client, times(1)).getAdamProduct("adamig-1-3", true);
        verify(client, times(1)).getAdamProduct("adam-occds-1-1", true);
    }


    @Test
    void aNonAdamKeyIsSkippedRatherThanGuessedAt() throws IOException
    {
        // §2.4: a declared SDTM product on an ADaM run stays NARROW — injecting it into this
        // provider would change how ADaM variables resolve as a side effect of declaring an SDTM
        // version. With nothing ADaM-shaped left, the -s/-v product answers alone.
        CdiscLibraryClient client = client();
        MetadataProvider p = build(client, List.of("standards/sdtmig/3-4"));

        assertEquals(List.of(ADAMIG_KEY), p.declaredStructureKeyedProducts());
        verify(client, never()).getSdtmVersion(anyString(), anyString(), anyBoolean());
    }


    @Test
    void aDuplicateKeyIsFetchedOnce() throws IOException
    {
        CdiscLibraryClient client = client();
        MetadataProvider p = build(client, List.of(OCCDS_KEY, OCCDS_KEY, ADAMIG_KEY));

        assertEquals(List.of(OCCDS_KEY, ADAMIG_KEY), p.declaredStructureKeyedProducts());
        verify(client, times(1)).getAdamProduct("adam-occds-1-1", true);
    }


    @Test
    void theCtEnrichedBranchCarriesTheDeclaredListToo() throws IOException
    {
        // ⚠⚠ The branch a real run with a CT package takes. It builds the provider through
        // LibraryMetadataEnhancer rather than the direct constructor, so it needs its OWN wiring —
        // and a test that only ever exercises the no-CT path would leave the enhanced branch
        // silently single-product while everything looked green.
        CdiscLibraryClient client = client();
        when(client.getCtPackage("adamct-2024-03-29", true))
                .thenReturn(mock(net.cumba.cdisc.library.api.model.ct.CtPackage.class));

        MetadataProvider p = CdiscLibraryProviderBuilder.from(wrap(client)).study(studyOnly())
                .standard("adamig").version("1-3").metadataProducts(List.of(OCCDS_KEY, ADAMIG_KEY))
                .ctPackageIds(List.of("adamct-2024-03-29")).buildOrDegraded();

        assertEquals(List.of(OCCDS_KEY, ADAMIG_KEY), p.declaredStructureKeyedProducts());
        assertEquals(List.of("USUBJID", "--SEQ", "CMTRT"),
                p.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of(ADVERSE_EVENT)));
        assertEquals(List.of("USUBJID", "CMTRT"),
                p.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of()));
        verify(client).getCtPackage("adamct-2024-03-29", true);
    }

    // ------------------------------------------------------------------
    // Phase 5 §2.4 — a declared SDTM product is NARROW
    // ------------------------------------------------------------------


    @Test
    void aDeclaredSdtmProductDoesNotChangeAdamVariableResolution() throws IOException
    {
        // ⛔ §2.4's regression guard, on the provider side: adding an SDTM key to -mp must leave
        // every ADaM answer byte-identical. (The companion side — that the declaration still
        // reaches getStandardDatasetNames() — is StudyValidationServiceCompanionSeamTest's.)
        CdiscLibraryClient client = client();
        MetadataProvider without = build(client, List.of(OCCDS_KEY, ADAMIG_KEY));
        MetadataProvider with = build(client,
                List.of("standards/sdtmig/3-1-1", OCCDS_KEY, ADAMIG_KEY));

        assertEquals(without.declaredStructureKeyedProducts(),
                with.declaredStructureKeyedProducts());
        assertEquals(without.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of(ADVERSE_EVENT)),
                with.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of(ADVERSE_EVENT)));
        assertEquals(without.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of()),
                with.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of()));
        assertEquals(without.getRequiredVariables("ADAE"), with.getRequiredVariables("ADAE"));
        assertEquals(without.getExpectedVariables("ADAE"), with.getExpectedVariables("ADAE"));
        assertEquals(without.getColumnOrder("ADAE"), with.getColumnOrder("ADAE"));
    }

    // ------------------------------------------------------------------
    // Phase 6b — fail-early granularity, at declaration time
    // ------------------------------------------------------------------


    @Test
    void aProductWhoseStructuresAllMapToNoTokenFailsLoudly() throws IOException
    {
        // A declared product that can never answer is a bad declaration, not an availability
        // problem: it must NOT be swallowed into the degraded provider.
        CdiscLibraryClient client = client();
        when(client.getAdamProduct("adam-future-1-0", true)).thenReturn(product("adam-future",
                structure("WHATEVER", "SOMETHING NEW", null, List.of(adamVar("X", "1", "Req"))),
                structure("ALSO", "ANOTHER NEW THING", null, List.of(adamVar("Y", "1", "Req")))));

        UnmappedMetadataProductException e = assertThrows(UnmappedMetadataProductException.class,
                () -> build(client, List.of("standards/adam/adam-future-1-0", ADAMIG_KEY)));

        assertEquals("standards/adam/adam-future-1-0", e.cacheKey());
        assertEquals(List.of("WHATEVER", "ALSO"), e.structureNames());
        assertTrue(e.getMessage().contains("WHATEVER"), e.getMessage());
    }


    @Test
    void aPartiallyMappedProductOnlyWarns() throws IOException
    {
        // ⚠⚠ The asymmetry that keeps tig/1-0/adam working: it publishes ADSL/BDS/OCCDS plus
        // REFERENDS, and a blanket "fail if ANY structure is unmapped" would reject it outright.
        CdiscLibraryClient client = client();
        when(client.getAdamProduct("adam-half-1-0", true)).thenReturn(product("adam-half",
                structure("OCCDS", "OCCURRENCE DATA STRUCTURE", null,
                        List.of(adamVar("USUBJID", "1", "Req"))),
                structure("MYSTERY", "NOT A STRUCTURE", null, List.of(adamVar("Z", "1", "Req")))));

        MetadataProvider p = build(client, List.of("standards/adam/adam-half-1-0", ADAMIG_KEY));

        assertNotNull(p);
        assertFalse(p.isLibraryUnavailable(), "a partial map must not degrade the run");
        assertEquals(List.of("USUBJID"), p.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of()),
                "the mapped half still answers");
    }


    @Test
    void theRealTigAdamShapeIsAccepted() throws IOException
    {
        // The concrete case the granularity rule protects — with Phase 6a's new token it is not
        // even a partial map any more, so this must be silent.
        CdiscLibraryClient client = client();
        when(client.getAdamProduct("adam-tigshape-1-0", true)).thenReturn(product("tig-adam",
                structure("ADSL", "SUBJECT LEVEL ANALYSIS DATASET", null,
                        List.of(adamVar("USUBJID", "1", "Req"))),
                structure("REFERENDS", AdamDataStructureDetector.REFERENCE_DATA_STRUCTURE, null,
                        List.of(adamVar("SRCVAR", "1", "Req")))));

        MetadataProvider p = build(client, List.of("standards/adam/adam-tigshape-1-0", ADAMIG_KEY));

        assertEquals(List.of("SRCVAR"), p.getRequiredVariablesForStructure(
                AdamDataStructureDetector.REFERENCE_DATA_STRUCTURE, List.of()));
    }


    @Test
    void aProductWithANullClassStructureIsJudgedThroughTheOverrides() throws IOException
    {
        // adam-tte-1-0's `BDS for TTE` publishes NO class. Without the (cacheKey, name) class
        // override it maps to nothing, so this declaration would hard-fail — which is precisely
        // how §6b tells "unmapped" from "mapped by an override".
        CdiscLibraryClient client = client();
        when(client.getAdamProduct("adam-tte-1-0", true)).thenReturn(product("adam-tte",
                structure("BDS for TTE", null, null, List.of(adamVar("AVAL", "1", "Req")))));

        MetadataProvider p = build(client, List.of("standards/adam/adam-tte-1-0", ADAMIG_KEY));

        assertEquals(List.of("AVAL"), p.getRequiredVariablesForStructure(
                AdamDataStructureDetector.BDS, List.of(AdamSubclassDetector.TIME_TO_EVENT)));
    }


    @Test
    void aDeclaredProductThatCannotBeFetchedDegradesTheRun() throws IOException
    {
        // ⚠ Deliberately the same disposition a failed single-product fetch has always had.
        // Silently dropping a declared product would leave the run resolving against a list the
        // user did not declare — precisely what ruling 1 forbids.
        CdiscLibraryClient client = client();
        when(client.getAdamProduct("adam-occds-1-1", true)).thenThrow(new IOException("HTTP 401"));

        MetadataProvider p = build(client, List.of(OCCDS_KEY, ADAMIG_KEY));

        assertTrue(p.isLibraryUnavailable(),
                "an undeliverable declared product must degrade the run, not vanish");
        assertFalse(p.supportsStructureKeyedVariables());
    }

    // ------------------------------------------------------------------
    // Phase 7 §7-0 — the LIBRARY layer follows the first declared product of the run's family
    // ------------------------------------------------------------------


    private MetadataProvider buildWithCt(CdiscLibraryClient aClient, List<String> aProducts)
        throws IOException
    {
        when(aClient.getCtPackage("adamct-2024-03-29", true))
                .thenReturn(mock(net.cumba.cdisc.library.api.model.ct.CtPackage.class));
        // libraryAsStudy: the provider's IMetadataLibrary is the PURE product-derived library, so
        // which product built it is directly observable through per-table queries.
        return CdiscLibraryProviderBuilder.from(wrap(aClient)).study(studyOnly()).libraryAsStudy()
                .standard("adamig").version("1-3").metadataProducts(aProducts)
                .ctPackageIds(List.of("adamct-2024-03-29")).buildOrDegraded();
    }


    @Test
    void theAdamLibraryLayerFollowsTheFirstDeclaredProduct() throws IOException
    {
        // §7-0 (owner ruling 2026-08-28): the dataset/variable universe the study is enriched
        // against comes from the FIRST declared product of the run's own family — not from the
        // -s/-v product regardless of declaration.
        CdiscLibraryClient client = client();
        MetadataProvider occdsFirst = buildWithCt(client, List.of(OCCDS_KEY, ADAMIG_KEY));
        assertFalse(occdsFirst.getVariableMetadata("OCCDS", "CMTRT").isEmpty(),
                "the supplement declared first is the library layer");
        assertTrue(occdsFirst.getVariableMetadata("ADSL", "USUBJID").isEmpty(),
                "adamig's universe must NOT be the library layer when it is declared second");

        CdiscLibraryClient client2 = client();
        MetadataProvider igFirst = buildWithCt(client2, List.of(ADAMIG_KEY, OCCDS_KEY));
        assertFalse(igFirst.getVariableMetadata("ADSL", "USUBJID").isEmpty());
        assertTrue(igFirst.getVariableMetadata("OCCDS", "CMTRT").isEmpty());
    }


    @Test
    void theDefaultDeclarationKeepsTheSvLibraryLayer() throws IOException
    {
        // §7-0's no-op proof for existing runs: the §1b′ default list (the single -s/-v key) and
        // the no-products caller (the .cdt harness) both put the -s/-v product's universe in the
        // library layer — byte-for-byte today's behaviour.
        CdiscLibraryClient client = client();
        MetadataProvider defaulted = buildWithCt(client, List.of(ADAMIG_KEY));
        CdiscLibraryClient client2 = client();
        MetadataProvider legacy = buildWithCt(client2, List.of());

        assertFalse(defaulted.getVariableMetadata("ADSL", "USUBJID").isEmpty());
        assertEquals(defaulted.getVariableMetadata("ADSL", "USUBJID"),
                legacy.getVariableMetadata("ADSL", "USUBJID"));
        assertEquals(defaulted.declaredStructureKeyedProducts(),
                legacy.declaredStructureKeyedProducts());
    }


    @Test
    void theSdtmLibraryLayerFollowsTheFirstDeclaredSdtmProduct() throws IOException
    {
        // §7-0 on the SDTM branch: the first SDTM-family key decides which IG is fetched as the
        // library product; the -s/-v pair is only the fallback when none is declared.
        CdiscLibraryClient client = client();
        when(client.getSdtmVersion("sendig", "3-1-1", true))
                .thenReturn(mock(net.cumba.cdisc.library.api.model.sdtm.SdtmProduct.class));

        MetadataProvider p = CdiscLibraryProviderBuilder.from(wrap(client)).study(studyOnly())
                .standard("sdtmig").version("3-4")
                .metadataProducts(List.of("standards/sendig/3-1-1", ADAMIG_KEY))
                .ctPackageIds(List.of()).buildOrDegraded();

        assertFalse(p.isLibraryUnavailable());
        verify(client).getSdtmVersion("sendig", "3-1-1", true);
        verify(client, never()).getSdtmVersion("sdtmig", "3-4", true);
    }


    @Test
    void theDefaultSdtmDeclarationIsANoOp() throws IOException
    {
        // The §1b′ default (standards/sdtmig/3-4 on a -s sdtmig -v 3-4 run) and a declaration
        // with no SDTM-family key at all both fetch exactly the -s/-v product — every existing
        // invocation is unchanged by §7-0.
        CdiscLibraryClient client = client();
        when(client.getSdtmVersion("sdtmig", "3-4", true))
                .thenReturn(mock(net.cumba.cdisc.library.api.model.sdtm.SdtmProduct.class));

        MetadataProvider defaulted = CdiscLibraryProviderBuilder.from(wrap(client))
                .study(studyOnly()).standard("sdtmig").version("3-4")
                .metadataProducts(List.of("standards/sdtmig/3-4")).ctPackageIds(List.of())
                .buildOrDegraded();
        MetadataProvider adamOnlyDeclared = CdiscLibraryProviderBuilder.from(wrap(client))
                .study(studyOnly()).standard("sdtmig").version("3-4")
                .metadataProducts(List.of(ADAMIG_KEY)).ctPackageIds(List.of()).buildOrDegraded();

        assertFalse(defaulted.isLibraryUnavailable());
        assertFalse(adamOnlyDeclared.isLibraryUnavailable());
        verify(client, times(2)).getSdtmVersion("sdtmig", "3-4", true);
    }

    // ------------------------------------------------------------------
    // Phase 7 §7-1/§7-2 — TIG is pickle-only; the API path says so
    // ------------------------------------------------------------------


    @Test
    void aDeclaredTigAdamLegOnTheApiPathDegradesWithTheStatedReason() throws IOException
    {
        // §7-2 made the TIG ADaM leg ADaM-family, so it now ENTERS the list — and because the
        // API serves no TIG, the API path must fail with the stated pickle-only reason rather
        // than skip it silently (the silent no-op ruling 1 forbids) or 404 mysteriously.
        CdiscLibraryClient client = client();
        java.util.logging.Logger logger = java.util.logging.Logger
                .getLogger(MetadataLibraryProvider.class.getName());
        CapturingWarnings handler = new CapturingWarnings();
        logger.addHandler(handler);
        MetadataProvider p;
        try
        {
            p = build(client, List.of("standards/tig/1-0/adam", ADAMIG_KEY));
        }
        finally
        {
            logger.removeHandler(handler);
        }

        assertTrue(p.isLibraryUnavailable());
        verify(client, never()).getAdamProduct(anyString(), anyBoolean());
        assertTrue(
                handler.formatted().stream()
                        .anyMatch(m -> m.contains("pickle metadata cache")
                                && m.contains("standards/tig/1-0/adam")),
                () -> "the degradation cause must state the pickle-only reason; got: "
                        + handler.formatted());
    }


    @Test
    void aTigSdtmFirstDeclarationOnTheApiPathDegradesWithTheStatedReason() throws IOException
    {
        CdiscLibraryClient client = client();
        MetadataProvider p = CdiscLibraryProviderBuilder.from(wrap(client)).study(studyOnly())
                .standard("sdtmig").version("3-4")
                .metadataProducts(List.of("standards/tig/1-0/sdtm")).ctPackageIds(List.of())
                .buildOrDegraded();

        assertTrue(p.isLibraryUnavailable());
        verify(client, never()).getSdtmVersion(anyString(), anyString(), anyBoolean());
    }

    /** Collects WARNING records the provider's {@link System.Logger} routes through JUL. */
    private static final class CapturingWarnings extends java.util.logging.Handler
    {

        private final List<java.util.logging.LogRecord> records = new java.util.ArrayList<>();

        List<String> formatted()
        {
            return records.stream()
                    .map(r -> r.getParameters() == null ? r.getMessage()
                            : java.text.MessageFormat.format(r.getMessage(), r.getParameters()))
                    .toList();
        }


        @Override
        public void publish(java.util.logging.LogRecord aRecord)
        {
            records.add(aRecord);
        }


        @Override
        public void flush()
        {
            // nothing buffered
        }


        @Override
        public void close()
        {
            // nothing to release
        }
    }
}
