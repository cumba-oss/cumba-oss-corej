package net.cumba.cdisc.core.metadata;

import static net.cumba.datatable.testkit.TestMetadataFixtures.column;
import static net.cumba.datatable.testkit.TestMetadataFixtures.lib;
import static net.cumba.datatable.testkit.TestMetadataFixtures.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.library.api.model.adam.AdamProduct;
import net.cumba.cdisc.library.api.model.ct.CtPackage;
import net.cumba.cdisc.library.api.model.sdtm.SdtmProduct;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.values.DataValueType;
import net.cumba.web.api.dev.MapResource;
import org.junit.jupiter.api.Test;

class LibraryMetadataEnhancerTest
{

    // ------------------------------------------------------------------
    // Fixture helpers
    // ------------------------------------------------------------------

    private static Map<String, Object> mkVar(String name, String label, String ordinal,
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


    private static Map<String, Object> mkDataset(String name, String label, String structure,
            List<Map<String, Object>> variables)
    {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", name);
        d.put("label", label);
        d.put("datasetStructure", structure);
        d.put("datasetVariables", variables);
        return d;
    }


    private static Map<String, Object> mkClass(String name, List<Map<String, Object>> classVars,
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
        List<Map<String, Object>> classVars = new ArrayList<>();
        classVars.add(mkVar("STUDYID", "Study Identifier", "1", "Char", "Req"));
        classVars.add(mkVar("USUBJID", "Unique Subject Identifier", "2", "Char", "Req"));

        List<Map<String, Object>> dmVars = new ArrayList<>();
        dmVars.add(mkVar("STUDYID", "Study Identifier", "1", "Char", "Req"));
        dmVars.add(mkVar("USUBJID", "Unique Subject Identifier", "2", "Char", "Req"));
        dmVars.add(mkVar("AGE", "Age", "3", "Num", "Exp"));

        Map<String, Object> dm = mkDataset("DM", "Demographics", "One record per subject", dmVars);
        Map<String, Object> specialPurpose = mkClass("Special-Purpose", classVars, List.of(dm));

        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", "SDTMIG");
        product.put("version", "3-4");
        product.put("classes", List.of(specialPurpose));
        return MapResource.of(product, SdtmProduct.class);
    }


    private static AdamProduct mkAdamProduct()
    {
        List<Map<String, Object>> adslVars = new ArrayList<>();
        adslVars.add(mkVar("STUDYID", "Study Identifier", "1", "Char", "Req"));
        adslVars.add(mkVar("USUBJID", "Unique Subject Identifier", "2", "Char", "Req"));

        Map<String, Object> varSet = new LinkedHashMap<>();
        varSet.put("name", "Identifiers");
        varSet.put("analysisVariables", adslVars);

        Map<String, Object> adsl = new LinkedHashMap<>();
        adsl.put("name", "ADSL");
        adsl.put("label", "Subject Level Analysis Dataset");
        adsl.put("class", "SUBJECT LEVEL ANALYSIS DATASET");
        adsl.put("analysisVariableSets", List.of(varSet));

        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", "ADaMIG");
        product.put("version", "1-3");
        product.put("dataStructures", List.of(adsl));
        return MapResource.of(product, AdamProduct.class);
    }


    /**
     * Builds a CT package reference from its <em>id</em>. The package body carries the display
     * label the CDISC Library actually serialises ({@code "SDTM CT 2024-03-29"}), so any code that
     * mistakenly reads {@code name()} as the id is caught rather than silently passing.
     */
    private static CtPackageRef mkCt(String aId)
    {
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("name", ctLabel(aId));
        pkg.put("codelists", List.of());
        return new CtPackageRef(aId, MapResource.of(pkg, CtPackage.class));
    }


    /** {@code sdtmct-2024-03-29} → {@code SDTM CT 2024-03-29}. */
    private static String ctLabel(String aId)
    {
        int cut = aId.indexOf("ct-");
        return aId.substring(0, cut).toUpperCase(java.util.Locale.ROOT) + " CT "
                + aId.substring(cut + 3);
    }


    private static IMetadataLibrary mkStudy()
    {
        return lib("Study").table(table("DM").label("Custom Demographics")
                .column(column("STUDYID", 0, DataValueType.STRING).build())
                .column(column("USUBJID", 1, DataValueType.STRING).build())
                .column(column("MYCOL", 2, DataValueType.STRING).build()).build()).build();
    }

    // ------------------------------------------------------------------
    // SDTM builder
    // ------------------------------------------------------------------


    @Test
    void sdtmBuilderProducesProvider()
    {
        MetadataProvider provider = LibraryMetadataEnhancer.forSdtm().study(mkStudy())
                .standardVersion("3-4").sdtm(mkSdtmProduct()).ct(mkCt("sdtmct-2024-03-29"))
                .buildProvider();

        assertEquals("sdtmig", provider.getStandard());
        assertEquals("3-4", provider.getVersion());
        // STUDYID and USUBJID are required per SDTM standards
        List<String> required = provider.getRequiredVariables("DM");
        assertTrue(required.contains("STUDYID"));
        assertTrue(required.contains("USUBJID"));
        // getColumnOrder is the algorithm-B (IG-base + Model-merge) standard column order, not the
        // study's per-table columns — so it returns the standard DM variables and does NOT carry
        // the study-only custom column MYCOL. (Mirrors Python get_column_order_from_library, which
        // is standard-driven.) The non-detectable Special-Purpose DM resolves to the IG dataset
        // variables [STUDYID, USUBJID, AGE].
        List<String> order = provider.getColumnOrder("DM");
        assertEquals(List.of("STUDYID", "USUBJID", "AGE"), order);
    }


    @Test
    void sdtmBuilderWithoutStudyExposesStandardsDirectly()
    {
        MetadataProvider provider = LibraryMetadataEnhancer.forSdtm().standardVersion("3-4")
                .sdtm(mkSdtmProduct()).ct(mkCt("sdtmct-2024-03-29")).buildProvider();

        assertEquals("sdtmig", provider.getStandard());
        // Standards-only: DM has the full 3-variable set from the standard
        assertEquals(List.of("STUDYID", "USUBJID", "AGE"), provider.getColumnOrder("DM"));
    }


    @Test
    void sdtmBuilderMissingVersionThrows()
    {
        assertThrows(NullPointerException.class,
                () -> LibraryMetadataEnhancer.forSdtm().study(mkStudy()).sdtm(mkSdtmProduct())
                        .ct(mkCt("sdtmct-2024-03-29")).buildProvider());
    }


    @Test
    void sdtmBuilderMissingProductThrows()
    {
        assertThrows(NullPointerException.class, () -> LibraryMetadataEnhancer.forSdtm()
                .standardVersion("3-4").ct(mkCt("sdtmct-2024-03-29")).buildProvider());
    }


    @Test
    void sdtmBuilderMissingCtThrows()
    {
        assertThrows(NullPointerException.class, () -> LibraryMetadataEnhancer.forSdtm()
                .standardVersion("3-4").sdtm(mkSdtmProduct()).buildProvider());
    }


    @Test
    void sdtmBuilderCustomStandardName()
    {
        MetadataProvider provider = LibraryMetadataEnhancer.forSdtm().standardName("sdtm")
                .standardVersion("2-0").sdtm(mkSdtmProduct()).ct(mkCt("sdtmct-2024-03-29"))
                .buildProvider();
        assertEquals("sdtm", provider.getStandard());
        assertEquals("2-0", provider.getVersion());
    }


    @Test
    void sdtmBuildMetadataReturnsEnriched()
    {
        IMetadataLibrary enriched = LibraryMetadataEnhancer.forSdtm().study(mkStudy())
                .standardVersion("3-4").sdtm(mkSdtmProduct()).ct(mkCt("sdtmct-2024-03-29"))
                .buildMetadata();
        // Instance should be an EnrichedMetadataLibrary
        assertTrue(enriched instanceof EnrichedMetadataLibrary);
        // Table DM should be present
        assertTrue(enriched.getDataTable("DM").isPresent());
        // Custom column present (from study)
        assertTrue(enriched.getDataTable("DM").orElseThrow().getColumn("MYCOL").isPresent());
    }

    // ------------------------------------------------------------------
    // ADaM builder
    // ------------------------------------------------------------------


    @Test
    void adamBuilderProducesProvider()
    {
        MetadataProvider provider = LibraryMetadataEnhancer.forAdam().study(null)
                .standardVersion("1-3").adam(mkAdamProduct()).ct(mkCt("adamct-2024-03-29"))
                .buildProvider();

        assertEquals("adamig", provider.getStandard());
        assertEquals("1-3", provider.getVersion());
        assertEquals(List.of("STUDYID", "USUBJID"), provider.getRequiredVariables("ADSL"));
    }


    @Test
    void adamBuilderAcceptsOptionalSdtmCtFallback()
    {
        MetadataProvider provider = LibraryMetadataEnhancer.forAdam().standardVersion("1-3")
                .adam(mkAdamProduct()).ct(mkCt("adamct-2024-03-29"))
                .sdtmCt(mkCt("sdtmct-2024-03-29")).buildProvider();

        // Should still return a working provider; no codelists defined here
        assertEquals("adamig", provider.getStandard());
        assertFalse(provider.isDomainCustom("ADSL"));
    }


    @Test
    void adamBuilderMissingProductThrows()
    {
        assertThrows(NullPointerException.class, () -> LibraryMetadataEnhancer.forAdam()
                .standardVersion("1-3").ct(mkCt("adamct-2024-03-29")).buildProvider());
    }


    @Test
    void adamBuilderMissingCtThrows()
    {
        assertThrows(NullPointerException.class, () -> LibraryMetadataEnhancer.forAdam()
                .standardVersion("1-3").adam(mkAdamProduct()).buildProvider());
    }


    @Test
    void adamBuilderMissingVersionThrows()
    {
        assertThrows(NullPointerException.class, () -> LibraryMetadataEnhancer.forAdam()
                .adam(mkAdamProduct()).ct(mkCt("adamct-2024-03-29")).buildProvider());
    }


    @Test
    void adamBuildMetadataReturnsEnrichedWhenStudyProvided()
    {
        IMetadataLibrary study = lib("Study")
                .table(table("ADSL").column(column("STUDYID", 0, DataValueType.STRING).build())
                        .column(column("USUBJID", 1, DataValueType.STRING).build()).build())
                .build();

        IMetadataLibrary enriched = LibraryMetadataEnhancer.forAdam().study(study)
                .standardVersion("1-3").adam(mkAdamProduct()).ct(mkCt("adamct-2024-03-29"))
                .buildMetadata();

        assertTrue(enriched instanceof EnrichedMetadataLibrary);
        assertNotNull(enriched.getDataTable("ADSL").orElseThrow());
    }


    @Test
    void adamBuildMetadataWithoutStudyReturnsRawStandards()
    {
        IMetadataLibrary metadata = LibraryMetadataEnhancer.forAdam().standardVersion("1-3")
                .adam(mkAdamProduct()).ct(mkCt("adamct-2024-03-29")).buildMetadata();

        // Not wrapped — just the CdiscLibraryMetadataLibrary
        assertTrue(metadata instanceof CdiscLibraryMetadataLibrary);
    }

    // ------------------------------------------------------------------
    // CT identity metadata — CtVersion / PublishedCtPackages
    // ------------------------------------------------------------------


    /**
     * The builder must carry the CT package <em>id</em> into {@code CtVersion} and
     * {@code PublishedCtPackages}.
     *
     * <p>
     * Every fixture in this class already passes a {@link CtPackageRef} whose {@code name()} is the
     * API's display label ({@code "SDTM CT 2024-03-29"}) rather than the id, precisely so that
     * reading the wrong one is detectable — but until now nothing here read either. The keys
     * matter: {@code valid_codelist_dates} prefix-matches {@code sdtmct} and parses a date out of
     * the value, and a label satisfies neither.
     * </p>
     */
    @Test
    void sdtmBuilderRecordsTheCtVersionAndPublishedPackages()
    {
        CtPackageRef ct = mkCt("sdtmct-2024-03-29");
        // Guard the premise: the fixture really does carry a label in name(), as the API does.
        assertEquals("SDTM CT 2024-03-29", ct.pkg().name().orElse(null));

        IMetadataLibrary metadata = LibraryMetadataEnhancer.forSdtm().standardVersion("3-4")
                .sdtm(mkSdtmProduct()).ct(ct).buildMetadata();

        assertEquals("sdtmct-2024-03-29",
                metadata.getMetaValue(MetadataKeys.CT_VERSION).orElse(null));
        assertEquals(List.of("sdtmct-2024-03-29"),
                metadata.getMetaValue(MetadataKeys.PUBLISHED_CT_PACKAGES).orElse(null));
    }


    /**
     * The study wrapper must not hide them. {@link EnrichedMetadataLibrary} resolves library
     * metadata primary-first, and the study library has no {@code CtVersion} — so the keys survive
     * only because the lookup falls through to the standards library.
     */
    @Test
    void ctIdentityMetadataSurvivesTheStudyWrapper()
    {
        IMetadataLibrary metadata = LibraryMetadataEnhancer.forSdtm().study(mkStudy())
                .standardVersion("3-4").sdtm(mkSdtmProduct()).ct(mkCt("sdtmct-2024-03-29"))
                .buildMetadata();

        assertTrue(metadata instanceof EnrichedMetadataLibrary);
        assertEquals("sdtmct-2024-03-29",
                metadata.getMetaValue(MetadataKeys.CT_VERSION).orElse(null));
        assertEquals(List.of("sdtmct-2024-03-29"),
                metadata.getMetaValue(MetadataKeys.PUBLISHED_CT_PACKAGES).orElse(null));
    }


    /**
     * {@code CtVersion} names the ADaM package; the SDTM fallback only joins the published list.
     */
    @Test
    void adamBuilderRecordsBothCtPackagesButVersionsOnTheAdamOne()
    {
        IMetadataLibrary metadata = LibraryMetadataEnhancer.forAdam().standardVersion("1-3")
                .adam(mkAdamProduct()).ct(mkCt("adamct-2024-03-29"))
                .sdtmCt(mkCt("sdtmct-2024-03-29")).buildMetadata();

        assertEquals("adamct-2024-03-29",
                metadata.getMetaValue(MetadataKeys.CT_VERSION).orElse(null));
        assertEquals(List.of("adamct-2024-03-29", "sdtmct-2024-03-29"),
                metadata.getMetaValue(MetadataKeys.PUBLISHED_CT_PACKAGES).orElse(null));
    }


    /** Without the SDTM fallback the ADaM package is the only published one. */
    @Test
    void adamBuilderWithoutTheSdtmFallbackPublishesOnlyTheAdamPackage()
    {
        IMetadataLibrary metadata = LibraryMetadataEnhancer.forAdam().standardVersion("1-3")
                .adam(mkAdamProduct()).ct(mkCt("adamct-2024-03-29")).buildMetadata();

        assertEquals(List.of("adamct-2024-03-29"),
                metadata.getMetaValue(MetadataKeys.PUBLISHED_CT_PACKAGES).orElse(null));
    }


    /**
     * An id-less package still contributes its codelists but must leave the identity keys unset —
     * inventing a version from the display label is what the {@link CtPackageRef} pairing exists to
     * prevent.
     */
    @Test
    void anAnonymousCtPackageLeavesTheIdentityKeysUnset()
    {
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("name", "SDTM CT 2024-03-29");
        pkg.put("codelists", List.of());

        IMetadataLibrary metadata = LibraryMetadataEnhancer.forSdtm().standardVersion("3-4")
                .sdtm(mkSdtmProduct())
                .ct(CtPackageRef.anonymous(MapResource.of(pkg, CtPackage.class))).buildMetadata();

        assertTrue(metadata.getMetaValue(MetadataKeys.CT_VERSION).isEmpty());
        assertTrue(metadata.getMetaValue(MetadataKeys.PUBLISHED_CT_PACKAGES).isEmpty());
    }

    // ------------------------------------------------------------------
    // Fix #55 — buildProvider() threads the product through
    // ------------------------------------------------------------------


    @Test
    void sdtmBuildProvider_producesProductAwareProvider()
    {
        // With a product set on the builder, buildProvider() should wire it through to the
        // MetadataLibraryProvider so getStandardModelVariables answers from the product directly
        // (returns a list, not the no-product null sentinel).
        MetadataProvider provider = LibraryMetadataEnhancer.forSdtm().study(mkStudy())
                .standardVersion("3-4").sdtm(mkSdtmProduct()).ct(mkCt("sdtmct-2024-03-29"))
                .buildProvider();

        // DM is in the Special-Purpose class with class-vars STUDYID, USUBJID
        List<String> model = provider.getModelColumnOrder("DM");
        assertEquals(List.of("STUDYID", "USUBJID"), model);
    }


    @Test
    void adamBuildProvider_producesProductAwareProvider()
    {
        MetadataProvider provider = LibraryMetadataEnhancer.forAdam().standardVersion("1-3")
                .adam(mkAdamProduct()).ct(mkCt("adamct-2024-03-29")).buildProvider();

        List<String> model = provider.getModelColumnOrder("ADSL");
        assertEquals(List.of("STUDYID", "USUBJID"), model);
    }


    @Test
    void noProductPath_produces_noProductAwareProvider()
    {
        // Direct construction without a product (the fallback path used by CoreEngineRunner /
        // CdiscValidate when no CT package or no Library client is configured). Class-hierarchy
        // queries fall through to the legacy MetadataKeys.MODEL_COLUMN_ORDER reader and return
        // empty if the key isn't populated.
        IMetadataLibrary study = lib("study").table(
                table("DM").column(column("STUDYID", 0, DataValueType.STRING).build()).build())
                .build();
        MetadataLibraryProvider provider = new MetadataLibraryProvider(study);
        assertEquals(List.of(), provider.getModelColumnOrder("DM"));
    }

}
