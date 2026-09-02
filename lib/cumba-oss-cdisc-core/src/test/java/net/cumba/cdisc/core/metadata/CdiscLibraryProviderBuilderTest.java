package net.cumba.cdisc.core.metadata;

import static net.cumba.datatable.testkit.TestMetadataFixtures.lib;
import static net.cumba.datatable.testkit.TestMetadataFixtures.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import net.cumba.cdisc.core.CoreLibraryAccess;
import net.cumba.cdisc.core.CoreLibraryAccessImpl;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.library.api.client.CdiscLibraryClient;
import net.cumba.cdisc.library.api.model.adam.AdamProduct;
import net.cumba.cdisc.library.api.model.ct.CtPackage;
import net.cumba.cdisc.library.api.model.sdtm.SdtmProduct;
import net.cumba.datatable.metadata.IMetadataLibrary;
import org.junit.jupiter.api.Test;

/**
 * Tests for the Fix #57 {@link CdiscLibraryProviderBuilder} facade, rewritten for the
 * {@link CoreLibraryAccess} migration.
 *
 * <p>
 * The {@link CdiscLibraryClient} mocks are wrapped behind
 * {@link CoreLibraryAccessImpl#forTesting(CdiscLibraryClient)} — this test sits inside cdisc.core
 * (the only module allowed to know about both types) so the cdisc.library imports are legitimate
 * here.
 * </p>
 */
class CdiscLibraryProviderBuilderTest
{

    private static final String SDTM_CT_ID = "sdtmct-2024-09-27";

    private static final String ADAM_CT_ID = "adamct-2024-03-29";

    private static IMetadataLibrary studyOnly()
    {
        return lib("study").table(table("AE").build()).build();
    }


    private static CoreLibraryAccess wrap(CdiscLibraryClient aClient)
    {
        return CoreLibraryAccessImpl.forTesting(aClient);
    }


    @Test
    void from_nullAccess_returnsStudyOnlyProvider()
    {
        IMetadataLibrary study = studyOnly();
        MetadataProvider provider = CdiscLibraryProviderBuilder.from(null).study(study)
                .standard("sdtmig").version("3-4").buildOrDegraded();
        assertNotNull(provider);
        // Study-only provider has no products, so getModelColumnOrder falls back to legacy
        // MODEL_COLUMN_ORDER key — empty here because the study has no such key set.
        assertEquals(java.util.List.of(), provider.getModelColumnOrder("AE"));
    }


    @Test
    void unknownStandard_returnsStudyOnlyProvider() throws IOException
    {
        CdiscLibraryClient client = mock(CdiscLibraryClient.class);
        MetadataProvider provider = CdiscLibraryProviderBuilder.from(wrap(client))
                .study(studyOnly()).standard("unknown-standard").version("1-0")
                .ctPackageIds(List.of(SDTM_CT_ID)).buildOrDegraded();
        assertNotNull(provider);
        // Client never consulted because the standard isn't recognised — short-circuits
        // before resolveCtPackage runs, so getCtPackage is never called either.
        verify(client, never()).getSdtmVersion(anyString(), anyString(), anyBoolean());
        verify(client, never()).getAdamProduct(anyString(), anyBoolean());
        verify(client, never()).getCtPackage(anyString(), anyBoolean());
    }


    @Test
    void emptyCtPackageIds_sdtm_stillFetchesProduct() throws IOException
    {
        // Fix #65: with no CT id to resolve, the SDTM branch still fetches the IG product;
        // only the CT-codelist enrichment layer is skipped. (Pre-Fix-#65 a missing CT
        // short-circuited to study-only.)
        CdiscLibraryClient client = mock(CdiscLibraryClient.class);
        SdtmProduct product = mock(SdtmProduct.class);
        when(product.classes()).thenReturn(java.util.List.of());
        when(product.modelLink()).thenReturn(java.util.Optional.empty());
        when(client.getSdtmVersion("sdtmig", "3-4", true)).thenReturn(product);

        MetadataProvider provider = CdiscLibraryProviderBuilder.from(wrap(client))
                .study(studyOnly()).standard("sdtmig").version("3-4").ctPackageIds(List.of())
                .buildOrDegraded();
        assertNotNull(provider);
        verify(client).getSdtmVersion("sdtmig", "3-4", true);
        verify(client, never()).getCtPackage(anyString(), anyBoolean());
    }


    @Test
    void emptyCtPackageIds_adam_stillFetchesProduct() throws IOException
    {
        // Fix #65 sister case for ADaM.
        CdiscLibraryClient client = mock(CdiscLibraryClient.class);
        AdamProduct product = mock(AdamProduct.class);
        when(product.dataStructures()).thenReturn(java.util.List.of());
        when(client.getAdamProduct("adamig-1-3", true)).thenReturn(product);

        MetadataProvider provider = CdiscLibraryProviderBuilder.from(wrap(client))
                .study(studyOnly()).standard("adamig").version("1-3").ctPackageIds(List.of())
                .buildOrDegraded();
        assertNotNull(provider);
        verify(client).getAdamProduct("adamig-1-3", true);
    }


    @Test
    void emptyCtPackageIds_sdtmFetchFailure_stillDegrades() throws IOException
    {
        // Fix #65: even on the no-CT path, an IG fetch failure must produce the degraded
        // provider (not a NullPointerException further down).
        CdiscLibraryClient client = mock(CdiscLibraryClient.class);
        when(client.getSdtmVersion("sdtmig", "3-4", true)).thenThrow(new IOException("HTTP 503"));

        MetadataProvider provider = CdiscLibraryProviderBuilder.from(wrap(client))
                .study(studyOnly()).standard("sdtmig").version("3-4").ctPackageIds(List.of())
                .buildOrDegraded();
        assertNotNull(provider);
        assertEquals(null, provider.getStandardModelVariables(null, null));
    }


    @Test
    void sdtmHappyPath_resolvesCtIdAndFetchesProduct() throws IOException
    {
        CdiscLibraryClient client = mock(CdiscLibraryClient.class);
        SdtmProduct product = mock(SdtmProduct.class);
        CtPackage ct = mock(CtPackage.class);
        when(client.getSdtmVersion("sdtmig", "3-4", true)).thenReturn(product);
        when(client.getCtPackage(SDTM_CT_ID, true)).thenReturn(ct);

        MetadataProvider provider = CdiscLibraryProviderBuilder.from(wrap(client))
                .study(studyOnly()).standard("SDTMIG").version("3-4")
                .ctPackageIds(List.of(SDTM_CT_ID)).buildOrDegraded();
        assertNotNull(provider);
        // Standard name was lower-cased before the API call (matches Python convention + the
        // pre-Fix-#57 runtime behaviour).
        verify(client).getSdtmVersion("sdtmig", "3-4", true);
        // CT-package fetch happened via the builder's resolveCtPackage helper, not via the
        // caller — the legacy fetchCt loop is gone.
        verify(client).getCtPackage(SDTM_CT_ID, true);
    }


    @Test
    void adamHappyPath_resolvesCtIdAndFetchesProduct() throws IOException
    {
        CdiscLibraryClient client = mock(CdiscLibraryClient.class);
        AdamProduct product = mock(AdamProduct.class);
        CtPackage ct = mock(CtPackage.class);
        when(client.getAdamProduct("adamig-1-3", true)).thenReturn(product);
        when(client.getCtPackage(ADAM_CT_ID, true)).thenReturn(ct);

        MetadataProvider provider = CdiscLibraryProviderBuilder.from(wrap(client))
                .study(studyOnly()).standard("ADAMIG").version("1-3")
                .ctPackageIds(List.of(ADAM_CT_ID)).buildOrDegraded();
        assertNotNull(provider);
        // Product ID is composed as <stdname>-<version> per the legacy runtime convention.
        verify(client).getAdamProduct("adamig-1-3", true);
        verify(client).getCtPackage(ADAM_CT_ID, true);
    }


    @Test
    void wrongPrefixCtPackageIsIgnored_andProductStillFetched() throws IOException
    {
        // SDTM standard with only an ADaM CT id supplied → the prefix filter drops it,
        // builder degrades to the no-CT path but still fetches the SDTM product (Fix #65).
        CdiscLibraryClient client = mock(CdiscLibraryClient.class);
        SdtmProduct product = mock(SdtmProduct.class);
        when(product.classes()).thenReturn(java.util.List.of());
        when(product.modelLink()).thenReturn(java.util.Optional.empty());
        when(client.getSdtmVersion("sdtmig", "3-4", true)).thenReturn(product);

        MetadataProvider provider = CdiscLibraryProviderBuilder.from(wrap(client))
                .study(studyOnly()).standard("sdtmig").version("3-4")
                .ctPackageIds(List.of(ADAM_CT_ID)).buildOrDegraded();
        assertNotNull(provider);
        verify(client).getSdtmVersion("sdtmig", "3-4", true);
        verify(client, never()).getCtPackage(anyString(), anyBoolean());
    }


    @Test
    void ctPackageFetchFailureIsSwallowed_andBuilderFallsBackToNoCt() throws IOException
    {
        // The legacy fetchCt loop in CoreEngineRunner/CdiscValidate caught IOException and
        // returned null silently. CoreLibraryAccessImpl.fetchCtPackage preserves that
        // contract — verify that the builder treats a failed CT fetch as "no CT" rather
        // than degrading the whole provider.
        CdiscLibraryClient client = mock(CdiscLibraryClient.class);
        SdtmProduct product = mock(SdtmProduct.class);
        when(product.classes()).thenReturn(java.util.List.of());
        when(product.modelLink()).thenReturn(java.util.Optional.empty());
        when(client.getSdtmVersion("sdtmig", "3-4", true)).thenReturn(product);
        when(client.getCtPackage(SDTM_CT_ID, true)).thenThrow(new IOException("CT HTTP 500"));

        MetadataProvider provider = CdiscLibraryProviderBuilder.from(wrap(client))
                .study(studyOnly()).standard("sdtmig").version("3-4")
                .ctPackageIds(List.of(SDTM_CT_ID)).buildOrDegraded();
        assertNotNull(provider);
        verify(client).getCtPackage(SDTM_CT_ID, true);
        // Product still fetched — provider is product-aware, just without CT enrichment.
        verify(client).getSdtmVersion("sdtmig", "3-4", true);
    }


    @Test
    void onCtFetchHookFiresOncePerCandidate() throws IOException
    {
        // Hook should fire exactly once per id that matches the prefix and is attempted;
        // first successful fetch ends the loop.
        CdiscLibraryClient client = mock(CdiscLibraryClient.class);
        SdtmProduct product = mock(SdtmProduct.class);
        CtPackage ct = mock(CtPackage.class);
        when(client.getSdtmVersion("sdtmig", "3-4", true)).thenReturn(product);
        when(client.getCtPackage(SDTM_CT_ID, true)).thenReturn(ct);

        java.util.List<String> fired = new java.util.ArrayList<>();
        CdiscLibraryProviderBuilder.from(wrap(client)).study(studyOnly()).standard("sdtmig")
                .version("3-4").ctPackageIds(List.of(SDTM_CT_ID)).onCtFetch(fired::add)
                .buildOrDegraded();
        assertEquals(List.of(SDTM_CT_ID), fired);
    }


    @Test
    void onCtFetchHook_nullResetsToNoOp()
    {
        // Setting null on the hook reverts to the silent default — no NPE when the loop
        // accepts() it during resolveCtPackage.
        CdiscLibraryClient client = mock(CdiscLibraryClient.class);
        MetadataProvider provider = CdiscLibraryProviderBuilder.from(wrap(client))
                .study(studyOnly()).standard("unknown").version("1-0").onCtFetch(null)
                .buildOrDegraded();
        assertNotNull(provider);
    }


    @Test
    void sdtmFetchFailure_withCt_returnsDegradedProvider() throws IOException
    {
        CdiscLibraryClient client = mock(CdiscLibraryClient.class);
        CtPackage ct = mock(CtPackage.class);
        when(client.getCtPackage(SDTM_CT_ID, true)).thenReturn(ct);
        when(client.getSdtmVersion("sdtmig", "3-4", true)).thenThrow(new IOException("HTTP 500"));

        IMetadataLibrary study = studyOnly();
        MetadataProvider provider = CdiscLibraryProviderBuilder.from(wrap(client)).study(study)
                .standard("sdtmig").version("3-4").ctPackageIds(List.of(SDTM_CT_ID))
                .buildOrDegraded();
        assertNotNull(provider);
        // Degraded provider: getStandardModelVariables returns null (the unavailable signal).
        assertEquals(null, provider.getStandardModelVariables(null, null));
        // And getModelColumnOrder returns empty list under degraded mode.
        assertEquals(java.util.List.of(), provider.getModelColumnOrder("AE"));
    }


    @Test
    void adamFetchFailure_withCt_returnsDegradedProvider() throws IOException
    {
        CdiscLibraryClient client = mock(CdiscLibraryClient.class);
        CtPackage ct = mock(CtPackage.class);
        when(client.getCtPackage(ADAM_CT_ID, true)).thenReturn(ct);
        when(client.getAdamProduct("adamig-1-3", true))
                .thenThrow(new RuntimeException("malformed product"));

        MetadataProvider provider = CdiscLibraryProviderBuilder.from(wrap(client))
                .study(studyOnly()).standard("adamig").version("1-3")
                .ctPackageIds(List.of(ADAM_CT_ID)).buildOrDegraded();
        assertNotNull(provider);
        assertEquals(null, provider.getStandardModelVariables(null, null));
    }


    @Test
    void sendigStandardKindIsTreatedAsSdtm() throws IOException
    {
        // SEND/SENDIG live under the SDTM family per StandardKind.fromName — confirm the
        // facade routes them to the SDTM branch rather than UNKNOWN.
        CdiscLibraryClient client = mock(CdiscLibraryClient.class);
        SdtmProduct product = mock(SdtmProduct.class);
        CtPackage ct = mock(CtPackage.class);
        when(client.getSdtmVersion("sendig", "3-1-1", true)).thenReturn(product);
        // SEND uses the sdtmct prefix per StandardKind.SDTM mapping.
        when(client.getCtPackage("sdtmct-2024-09-27", true)).thenReturn(ct);

        MetadataProvider provider = CdiscLibraryProviderBuilder.from(wrap(client))
                .study(studyOnly()).standard("sendig").version("3-1-1")
                .ctPackageIds(List.of("sdtmct-2024-09-27")).buildOrDegraded();
        assertNotNull(provider);
        verify(client).getSdtmVersion("sendig", "3-1-1", true);
    }


    @Test
    void nonImplAccess_degradesToStudyOnly()
    {
        // Defensive: a future caller (typically a test) that supplies a CoreLibraryAccess
        // that is NOT a CoreLibraryAccessImpl (e.g. mock(CoreLibraryAccess.class)) must
        // degrade to the study-only provider rather than throwing ClassCastException
        // when the builder casts to reach the underlying CdiscLibraryClient.
        CoreLibraryAccess fake = mock(CoreLibraryAccess.class);
        MetadataProvider provider = CdiscLibraryProviderBuilder.from(fake).study(studyOnly())
                .standard("sdtmig").version("3-4").ctPackageIds(List.of(SDTM_CT_ID))
                .buildOrDegraded();
        assertNotNull(provider);
        // Study-only — no products, no enrichment.
        assertEquals(java.util.List.of(), provider.getModelColumnOrder("AE"));
    }


    @Test
    void buildOrDegraded_isIdempotentReturnsSameClass()
    {
        // Sanity: two invocations with the same inputs return distinct provider instances
        // of the same class (builder is single-use; callers create one per provider). No
        // leakage between calls.
        IMetadataLibrary study = studyOnly();
        MetadataProvider p1 = CdiscLibraryProviderBuilder.from(null).study(study).standard("sdtmig")
                .version("3-4").buildOrDegraded();
        MetadataProvider p2 = CdiscLibraryProviderBuilder.from(null).study(study).standard("sdtmig")
                .version("3-4").buildOrDegraded();
        assertNotNull(p1);
        assertNotNull(p2);
        assertSame(p1.getClass(), p2.getClass());
    }


    @Test
    void nullCtPackageIdsParam_isTreatedAsEmpty() throws IOException
    {
        // Defensive — the builder accepts null and treats it as an empty list (no CT match,
        // builder takes the Fix #65 product-without-CT path).
        CdiscLibraryClient client = mock(CdiscLibraryClient.class);
        SdtmProduct product = mock(SdtmProduct.class);
        when(product.classes()).thenReturn(java.util.List.of());
        when(product.modelLink()).thenReturn(java.util.Optional.empty());
        when(client.getSdtmVersion("sdtmig", "3-4", true)).thenReturn(product);

        MetadataProvider provider = CdiscLibraryProviderBuilder.from(wrap(client))
                .study(studyOnly()).standard("sdtmig").version("3-4").ctPackageIds(null)
                .buildOrDegraded();
        assertNotNull(provider);
        verify(client).getSdtmVersion("sdtmig", "3-4", true);
    }


    @Test
    void libraryAsStudy_sdtm_ignoresStudyTables() throws IOException
    {
        // With a CT package resolved, libraryAsStudy() builds over the product-derived library
        // only,
        // so the study's AE table is NOT visible (dataset metadata comes from the empty product).
        CdiscLibraryClient client = mock(CdiscLibraryClient.class);
        SdtmProduct product = mock(SdtmProduct.class);
        CtPackage ct = mock(CtPackage.class);
        when(product.classes()).thenReturn(java.util.List.of());
        when(product.modelLink()).thenReturn(java.util.Optional.empty());
        when(client.getSdtmVersion("sdtmig", "3-4", true)).thenReturn(product);
        when(client.getCtPackage(SDTM_CT_ID, true)).thenReturn(ct);

        MetadataProvider provider = CdiscLibraryProviderBuilder.from(wrap(client))
                .study(studyOnly()).libraryAsStudy().standard("sdtmig").version("3-4")
                .ctPackageIds(List.of(SDTM_CT_ID)).buildOrDegraded();
        assertNotNull(provider);
        assertTrue(provider.getDatasetMetadata("AE").isEmpty(),
                "library-as-study must ignore the study's AE table");
    }


    @Test
    void studyBacked_sdtm_keepsStudyTables() throws IOException
    {
        // Contrast with libraryAsStudy_sdtm_ignoresStudyTables: the default (study-backed) build
        // overlays the study, so the study's AE table IS visible.
        CdiscLibraryClient client = mock(CdiscLibraryClient.class);
        SdtmProduct product = mock(SdtmProduct.class);
        CtPackage ct = mock(CtPackage.class);
        when(product.classes()).thenReturn(java.util.List.of());
        when(product.modelLink()).thenReturn(java.util.Optional.empty());
        when(client.getSdtmVersion("sdtmig", "3-4", true)).thenReturn(product);
        when(client.getCtPackage(SDTM_CT_ID, true)).thenReturn(ct);

        MetadataProvider provider = CdiscLibraryProviderBuilder.from(wrap(client))
                .study(studyOnly()).standard("sdtmig").version("3-4")
                .ctPackageIds(List.of(SDTM_CT_ID)).buildOrDegraded();
        assertNotNull(provider);
        assertEquals("AE", provider.getDatasetMetadata("AE").get("name"));
    }


    @Test
    void libraryAsStudy_noCt_fallsBackToStudyBacked() throws IOException
    {
        // libraryAsStudy() requires CT (the product-derived library needs a CtPackage). With no CT
        // resolved it degrades to the study-backed no-CT path, so the study's AE table stays
        // visible.
        CdiscLibraryClient client = mock(CdiscLibraryClient.class);
        SdtmProduct product = mock(SdtmProduct.class);
        when(product.classes()).thenReturn(java.util.List.of());
        when(product.modelLink()).thenReturn(java.util.Optional.empty());
        when(client.getSdtmVersion("sdtmig", "3-4", true)).thenReturn(product);

        MetadataProvider provider = CdiscLibraryProviderBuilder.from(wrap(client))
                .study(studyOnly()).libraryAsStudy().standard("sdtmig").version("3-4")
                .ctPackageIds(List.of()).buildOrDegraded();
        assertNotNull(provider);
        assertEquals("AE", provider.getDatasetMetadata("AE").get("name"));
        verify(client, never()).getCtPackage(anyString(), anyBoolean());
    }


    @Test
    void libraryAsStudy_adam_ignoresStudyTables() throws IOException
    {
        // ADaM sister case to libraryAsStudy_sdtm_ignoresStudyTables.
        CdiscLibraryClient client = mock(CdiscLibraryClient.class);
        AdamProduct product = mock(AdamProduct.class);
        CtPackage ct = mock(CtPackage.class);
        when(product.dataStructures()).thenReturn(java.util.List.of());
        when(client.getAdamProduct("adamig-1-3", true)).thenReturn(product);
        when(client.getCtPackage(ADAM_CT_ID, true)).thenReturn(ct);

        MetadataProvider provider = CdiscLibraryProviderBuilder.from(wrap(client))
                .study(studyOnly()).libraryAsStudy().standard("adamig").version("1-3")
                .ctPackageIds(List.of(ADAM_CT_ID)).buildOrDegraded();
        assertNotNull(provider);
        assertTrue(provider.getDatasetMetadata("AE").isEmpty(),
                "library-as-study must ignore the study's AE table");
    }
}
