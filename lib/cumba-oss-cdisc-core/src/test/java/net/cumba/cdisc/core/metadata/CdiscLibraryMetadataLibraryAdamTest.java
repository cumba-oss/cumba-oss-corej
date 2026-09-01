package net.cumba.cdisc.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.library.api.model.adam.AdamProduct;
import net.cumba.cdisc.library.api.model.ct.CtPackage;
import net.cumba.datatable.metadata.ICodeList;
import net.cumba.datatable.metadata.IColumnMetadata;
import net.cumba.datatable.metadata.IDataTableMetadata;
import net.cumba.web.api.dev.MapResource;
import org.junit.jupiter.api.Test;

class CdiscLibraryMetadataLibraryAdamTest
{

    // ------------------------------------------------------------------
    // Fixture builders
    // ------------------------------------------------------------------

    private static Map<String, Object> mkVariable(String name, String label, String ordinal,
            String dtype, String core, String codelistConceptId)
    {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("name", name);
        v.put("label", label);
        v.put("ordinal", ordinal);
        v.put("simpleDatatype", dtype);
        v.put("core", core);
        if (codelistConceptId != null)
        {
            Map<String, Object> links = new LinkedHashMap<>();
            Map<String, Object> codelist = new LinkedHashMap<>();
            codelist.put("href", "/mdr/ct/packages/sdtmct-test/codelists/" + codelistConceptId);
            codelist.put("title", codelistConceptId);
            links.put("codelist", codelist);
            v.put("_links", links);
        }
        return v;
    }


    private static Map<String, Object> mkVariableSet(String name, String label,
            List<Map<String, Object>> variables)
    {
        Map<String, Object> vs = new LinkedHashMap<>();
        vs.put("name", name);
        vs.put("label", label);
        vs.put("analysisVariables", variables);
        return vs;
    }


    private static Map<String, Object> mkDataStructure(String name, String label, String className,
            List<Map<String, Object>> variableSets)
    {
        Map<String, Object> ds = new LinkedHashMap<>();
        ds.put("name", name);
        ds.put("label", label);
        ds.put("class", className);
        ds.put("analysisVariableSets", variableSets);
        return ds;
    }


    private static AdamProduct mkProduct(String name, String version,
            List<Map<String, Object>> dataStructures)
    {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("version", version);
        p.put("dataStructures", dataStructures);
        return MapResource.of(p, AdamProduct.class);
    }


    private static Map<String, Object> mkTerm(String submissionValue, String preferredTerm)
    {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("submissionValue", submissionValue);
        t.put("preferredTerm", preferredTerm);
        return t;
    }


    private static Map<String, Object> mkCodelist(String conceptId, String submissionValue,
            boolean extensible, List<Map<String, Object>> terms)
    {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("conceptId", conceptId);
        c.put("submissionValue", submissionValue);
        c.put("extensible", extensible);
        c.put("terms", terms);
        return c;
    }


    /**
     * Builds a CT package as the CDISC Library serialises it: {@code name} is the display
     * <em>label</em>, never the package id. The id travels separately via {@link CtPackageRef}.
     */
    private static CtPackageRef mkCtPackage(String id, String label,
            List<Map<String, Object>> codelists)
    {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", label);
        p.put("codelists", codelists);
        return new CtPackageRef(id, MapResource.of(p, CtPackage.class));
    }

    // ------------------------------------------------------------------
    // Fixtures — ADSL with a couple of variable sets, referencing SEX
    // (which is defined in SDTM CT, not ADaM CT)
    // ------------------------------------------------------------------


    private static AdamProduct adamFixture()
    {
        List<Map<String, Object>> idVars = new ArrayList<>();
        idVars.add(mkVariable("STUDYID", "Study Identifier", "1", "Char", "Req", null));
        idVars.add(mkVariable("USUBJID", "Unique Subject Identifier", "2", "Char", "Req", null));
        idVars.add(
                mkVariable("SUBJID", "Subject Identifier for the Study", "3", "Char", "Req", null));

        List<Map<String, Object>> demoVars = new ArrayList<>();
        demoVars.add(mkVariable("AGE", "Age", "10", "Num", "Perm", null));
        // SEX references the SDTM CT codelist C66731
        demoVars.add(mkVariable("SEX", "Sex", "11", "Char", "Req", "C66731"));
        demoVars.add(mkVariable("RACE", "Race", "12", "Char", "Perm", null));

        Map<String, Object> idSet = mkVariableSet("Identifiers", "Identifiers", idVars);
        Map<String, Object> demoSet = mkVariableSet("Demographics", "Demographics", demoVars);

        Map<String, Object> adsl = mkDataStructure("ADSL", "Subject Level Analysis Dataset",
                "SUBJECT LEVEL ANALYSIS DATASET", List.of(idSet, demoSet));

        return mkProduct("ADaMIG", "1-3", List.of(adsl));
    }


    private static CtPackageRef adamCtFixture()
    {
        // ADaM-specific codelists — e.g. ANLzzFL parameter categories
        List<Map<String, Object>> paramTerms = List.of(mkTerm("Y", "Yes"), mkTerm("N", "No"));
        Map<String, Object> yn = mkCodelist("C99999", "YN", false, paramTerms);
        return mkCtPackage("adamct-2024-03-29", "ADaM CT 2024-03-29", List.of(yn));
    }


    private static CtPackageRef sdtmCtFixture()
    {
        // SDTM CT contains SEX, referenced by ADSL.SEX
        List<Map<String, Object>> sexTerms = List.of(mkTerm("M", "Male"), mkTerm("F", "Female"),
                mkTerm("U", "Unknown"));
        Map<String, Object> sex = mkCodelist("C66731", "SEX", false, sexTerms);
        return mkCtPackage("sdtmct-2024-03-29", "SDTM CT 2024-03-29", List.of(sex));
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------


    /**
     * Fix A regression guard for the two-package ADaM path: {@code CtVersion} takes the ADaM
     * package id, and {@code PublishedCtPackages} lists the ADaM id followed by the SDTM fallback
     * id — ids throughout, never the API display labels the packages carry in {@code name()}.
     */
    @Test
    void ctVersionAndPublishedPackagesUseRequestedIdsAcrossBothPackages()
    {
        CtPackageRef adamCt = adamCtFixture();
        CtPackageRef sdtmCt = sdtmCtFixture();
        // Guard the premise: both fixtures carry labels in name(), as the API does.
        assertEquals("ADaM CT 2024-03-29", adamCt.pkg().name().orElse(null));
        assertEquals("SDTM CT 2024-03-29", sdtmCt.pkg().name().orElse(null));

        CdiscLibraryMetadataLibrary lib = CdiscLibraryMetadataLibrary.fromAdam("adamig", "1-3",
                adamFixture(), adamCt, sdtmCt);

        assertEquals("adamct-2024-03-29", lib.getMetaValue(MetadataKeys.CT_VERSION).orElse(null));
        assertEquals(List.of("adamct-2024-03-29", "sdtmct-2024-03-29"),
                lib.getMetaValue(MetadataKeys.PUBLISHED_CT_PACKAGES).orElse(null));
    }


    /** With no SDTM fallback, only the ADaM id is published. */
    @Test
    void publishedCtPackagesOmitsAbsentSdtmFallback()
    {
        CdiscLibraryMetadataLibrary lib = CdiscLibraryMetadataLibrary.fromAdam("adamig", "1-3",
                adamFixture(), adamCtFixture(), null);

        assertEquals(List.of("adamct-2024-03-29"),
                lib.getMetaValue(MetadataKeys.PUBLISHED_CT_PACKAGES).orElse(null));
    }


    @Test
    void adamLibraryNameAndVersion()
    {
        CdiscLibraryMetadataLibrary lib = CdiscLibraryMetadataLibrary.fromAdam("adamig", "1-3",
                adamFixture(), adamCtFixture(), null);

        assertEquals("adamig", lib.getName());
        assertEquals("1-3", lib.getVersion());
        assertEquals("adamig", lib.getMetaValue(MetadataKeys.STANDARD_NAME).orElse(null));
        assertEquals("1-3", lib.getMetaValue(MetadataKeys.STANDARD_VERSION).orElse(null));
        assertEquals("adamct-2024-03-29", lib.getMetaValue(MetadataKeys.CT_VERSION).orElse(null));
    }


    @Test
    void dataStructuresBecomeTablesWithClassName()
    {
        CdiscLibraryMetadataLibrary lib = CdiscLibraryMetadataLibrary.fromAdam("adamig", "1-3",
                adamFixture(), adamCtFixture(), null);

        IDataTableMetadata adsl = lib.getDataTable("ADSL").orElseThrow();
        assertEquals("ADSL", adsl.getName());
        assertEquals("Subject Level Analysis Dataset", adsl.getLabel());
        assertEquals("SUBJECT LEVEL ANALYSIS DATASET", adsl.getClassName());
    }


    @Test
    void variableSetsAreFlattenedAndOrdered()
    {
        CdiscLibraryMetadataLibrary lib = CdiscLibraryMetadataLibrary.fromAdam("adamig", "1-3",
                adamFixture(), adamCtFixture(), null);

        IDataTableMetadata adsl = lib.getDataTable("ADSL").orElseThrow();
        List<IColumnMetadata> cols = adsl.getColumns();
        assertEquals(6, cols.size());
        assertEquals("STUDYID", cols.get(0).getName());
        assertEquals("USUBJID", cols.get(1).getName());
        assertEquals("SUBJID", cols.get(2).getName());
        assertEquals("AGE", cols.get(3).getName());
        assertEquals("SEX", cols.get(4).getName());
        assertEquals("RACE", cols.get(5).getName());
    }


    @Test
    void modelColumnOrderIsPopulatedFromAllVariables()
    {
        CdiscLibraryMetadataLibrary lib = CdiscLibraryMetadataLibrary.fromAdam("adamig", "1-3",
                adamFixture(), adamCtFixture(), null);

        IDataTableMetadata adsl = lib.getDataTable("ADSL").orElseThrow();
        Object mco = adsl.getMetaValue(MetadataKeys.MODEL_COLUMN_ORDER).orElseThrow();
        @SuppressWarnings("unchecked")
        List<String> mcoList = (List<String>) mco;
        assertEquals(List.of("STUDYID", "USUBJID", "SUBJID", "AGE", "SEX", "RACE"), mcoList);
    }


    @Test
    void sdtmCtFallbackExposesSdtmCodelists()
    {
        CdiscLibraryMetadataLibrary lib = CdiscLibraryMetadataLibrary.fromAdam("adamig", "1-3",
                adamFixture(), adamCtFixture(), sdtmCtFixture());

        // Both ADaM codelist (YN) and SDTM codelist (SEX) should be visible
        assertTrue(lib.getCodelist("YN").isPresent());
        assertTrue(lib.getCodelist("SEX").isPresent());

        // The SEX codelist came from SDTM CT
        ICodeList sex = lib.getCodelist("SEX").orElseThrow();
        assertEquals("C66731", sex.getMetaValue(MetadataKeys.CODELIST_CONCEPT_ID).orElse(null));
        assertEquals(3, sex.getEntries().size());
    }


    @Test
    void withoutSdtmCtFallbackSdtmCodelistsAreUnavailable()
    {
        CdiscLibraryMetadataLibrary lib = CdiscLibraryMetadataLibrary.fromAdam("adamig", "1-3",
                adamFixture(), adamCtFixture(), null);

        assertTrue(lib.getCodelist("YN").isPresent());
        assertFalse(lib.getCodelist("SEX").isPresent());
    }


    @Test
    void adamCtHasPrecedenceOverSdtmCtOnCollision()
    {
        // Put SEX into ADaM CT as well (with different contents) and ensure ADaM wins
        List<Map<String, Object>> adamSexTerms = List.of(mkTerm("M", "ADAM-MALE"),
                mkTerm("F", "ADAM-FEMALE"));
        Map<String, Object> adamSex = mkCodelist("C88888", "SEX", false, adamSexTerms);
        CtPackageRef adamCt = mkCtPackage("adamct-2024-03-29", "ADaM CT 2024-03-29",
                List.of(adamSex));

        CdiscLibraryMetadataLibrary lib = CdiscLibraryMetadataLibrary.fromAdam("adamig", "1-3",
                adamFixture(), adamCt, sdtmCtFixture());

        ICodeList sex = lib.getCodelist("SEX").orElseThrow();
        // ADaM's version should have won (2 entries, not 3)
        assertEquals(2, sex.getEntries().size());
        assertEquals("ADAM-MALE", sex.getEntries().get(0).getDecodeValue());
    }


    @Test
    void sexColumnHasCodelistResolvedFromSdtmCt()
    {
        CdiscLibraryMetadataLibrary lib = CdiscLibraryMetadataLibrary.fromAdam("adamig", "1-3",
                adamFixture(), adamCtFixture(), sdtmCtFixture());

        IDataTableMetadata adsl = lib.getDataTable("ADSL").orElseThrow();
        IColumnMetadata sex = adsl.getColumn("SEX").orElseThrow();
        // Concept id C66731 resolved to SEX (via sdtm ct fallback)
        assertEquals("SEX", sex.getCodelist());
    }


    @Test
    void isCustomDomainIsFalseForAdamDataStructures()
    {
        CdiscLibraryMetadataLibrary lib = CdiscLibraryMetadataLibrary.fromAdam("adamig", "1-3",
                adamFixture(), adamCtFixture(), null);

        IDataTableMetadata adsl = lib.getDataTable("ADSL").orElseThrow();
        assertEquals(false, adsl.getMetaValue(MetadataKeys.IS_CUSTOM_DOMAIN).orElse(null));
    }


    @Test
    void nullArgumentsAreRejected()
    {
        assertThrows(NullPointerException.class, () -> CdiscLibraryMetadataLibrary.fromAdam(null,
                "1-3", adamFixture(), adamCtFixture(), null));
        assertThrows(NullPointerException.class, () -> CdiscLibraryMetadataLibrary
                .fromAdam("adamig", null, adamFixture(), adamCtFixture(), null));
        assertThrows(NullPointerException.class, () -> CdiscLibraryMetadataLibrary
                .fromAdam("adamig", "1-3", null, adamCtFixture(), null));
        assertThrows(NullPointerException.class, () -> CdiscLibraryMetadataLibrary
                .fromAdam("adamig", "1-3", adamFixture(), null, null));
        // sdtmCt is optional, so null is OK — already exercised by the main tests.
    }


    @Test
    void integratesWithMetadataLibraryProvider()
    {
        CdiscLibraryMetadataLibrary lib = CdiscLibraryMetadataLibrary.fromAdam("adamig", "1-3",
                adamFixture(), adamCtFixture(), sdtmCtFixture());
        MetadataLibraryProvider provider = new MetadataLibraryProvider(lib);

        assertEquals("adamig", provider.getStandard());
        assertEquals("1-3", provider.getVersion());

        List<String> required = provider.getRequiredVariables("ADSL");
        assertTrue(required.contains("STUDYID"));
        assertTrue(required.contains("USUBJID"));
        assertTrue(required.contains("SEX"));
        assertFalse(required.contains("AGE"));

        // Codelist lookup via SDTM CT fallback
        assertEquals(List.of("M", "F", "U"), provider.getCodelistTerms("SEX"));
        // Also via concept id
        assertEquals(List.of("M", "F", "U"), provider.getCodelistTerms("C66731"));

        // SEX column metadata round-trip
        Map<String, String> sexMeta = provider.getVariableMetadata("ADSL", "SEX");
        assertEquals("SEX", sexMeta.get("codelist"));
        assertEquals("Char", sexMeta.get("simpleDatatype"));
        assertEquals("Req", sexMeta.get("core"));
        assertNotNull(sexMeta.get("ordinal"));

        assertFalse(provider.isDomainCustom("ADSL"));
    }

}
