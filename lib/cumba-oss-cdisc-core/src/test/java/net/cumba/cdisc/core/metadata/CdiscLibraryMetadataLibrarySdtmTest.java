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
import net.cumba.cdisc.library.api.model.ct.CtPackage;
import net.cumba.cdisc.library.api.model.sdtm.SdtmProduct;
import net.cumba.datatable.metadata.ICodeList;
import net.cumba.datatable.metadata.ICodelistEntry;
import net.cumba.datatable.metadata.IColumnMetadata;
import net.cumba.datatable.metadata.IDataTableMetadata;
import net.cumba.datatable.values.DataValueType;
import net.cumba.web.api.dev.MapResource;
import org.junit.jupiter.api.Test;

class CdiscLibraryMetadataLibrarySdtmTest
{

    // ------------------------------------------------------------------
    // Test fixture builders — mimic what the CDISC Library JSON API returns
    // ------------------------------------------------------------------

    private static Map<String, Object> mkVariable(String name, String label, String ordinal,
            String dtype, String core, String role, String codelistConceptId)
    {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("name", name);
        v.put("label", label);
        v.put("ordinal", ordinal);
        v.put("simpleDatatype", dtype);
        v.put("core", core);
        v.put("role", role);
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


    private static Map<String, Object> mkClass(String name, String label,
            List<Map<String, Object>> classVariables, List<Map<String, Object>> datasets)
    {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", name);
        c.put("label", label);
        c.put("classVariables", classVariables);
        c.put("datasets", datasets);
        return c;
    }


    private static SdtmProduct mkProduct(String name, String version,
            List<Map<String, Object>> classes)
    {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("version", version);
        p.put("classes", classes);
        return MapResource.of(p, SdtmProduct.class);
    }


    private static Map<String, Object> mkTerm(String submissionValue, String preferredTerm)
    {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("submissionValue", submissionValue);
        t.put("preferredTerm", preferredTerm);
        return t;
    }


    private static Map<String, Object> mkTerm(String submissionValue, String preferredTerm,
            String conceptId)
    {
        Map<String, Object> t = mkTerm(submissionValue, preferredTerm);
        t.put("conceptId", conceptId);
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
     * Builds a CT package as the CDISC Library actually serialises it: {@code name} is the display
     * <em>label</em> ({@code "SDTM CT 2024-03-29"}), never the package id. The id is supplied
     * separately via {@link CtPackageRef}, mirroring how the runtime learns it (from the request).
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
    // Fixture — a minimal SDTMIG 3-4 with Events class and AE dataset
    // ------------------------------------------------------------------


    private static SdtmProduct sdtmFixture()
    {
        List<Map<String, Object>> classVars = new ArrayList<>();
        classVars.add(
                mkVariable("STUDYID", "Study Identifier", "1", "Char", "Req", "Identifier", null));
        classVars.add(mkVariable("DOMAIN", "Domain Abbreviation", "2", "Char", "Req", "Identifier",
                null));
        classVars.add(mkVariable("USUBJID", "Unique Subject Identifier", "3", "Char", "Req",
                "Identifier", null));

        List<Map<String, Object>> aeVars = new ArrayList<>();
        aeVars.add(
                mkVariable("STUDYID", "Study Identifier", "1", "Char", "Req", "Identifier", null));
        aeVars.add(mkVariable("DOMAIN", "Domain Abbreviation", "2", "Char", "Req", "Identifier",
                null));
        aeVars.add(mkVariable("USUBJID", "Unique Subject Identifier", "3", "Char", "Req",
                "Identifier", null));
        aeVars.add(mkVariable("AESEQ", "Sequence Number", "4", "Num", "Req", "Identifier", null));
        aeVars.add(mkVariable("AETERM", "Reported Term for the Adverse Event", "5", "Char", "Req",
                "Topic", null));
        aeVars.add(mkVariable("AESEV", "Severity/Intensity", "6", "Char", "Perm",
                "Record Qualifier", "C66769"));

        Map<String, Object> aeDataset = mkDataset("AE", "Adverse Events",
                "One record per adverse event per subject", aeVars);

        Map<String, Object> dmVars = mkDataset("DM", "Demographics", "One record per subject",
                new ArrayList<>(classVars));

        Map<String, Object> eventsClass = mkClass("Events", "Events", classVars,
                List.of(aeDataset));
        Map<String, Object> specialPurposeClass = mkClass("Special-Purpose", "Special Purpose",
                List.of(), List.of(dmVars));

        return mkProduct("SDTMIG", "3-4", List.of(eventsClass, specialPurposeClass));
    }


    private static CtPackageRef sdtmCtFixture()
    {
        List<Map<String, Object>> severityTerms = List.of(mkTerm("MILD", "Mild"),
                mkTerm("MODERATE", "Moderate"), mkTerm("SEVERE", "Severe"));
        Map<String, Object> severity = mkCodelist("C66769", "AESEV", false, severityTerms);

        List<Map<String, Object>> nyTerms = List.of(mkTerm("N", "No"), mkTerm("Y", "Yes"));
        Map<String, Object> ny = mkCodelist("C66742", "NY", false, nyTerms);

        return mkCtPackage("sdtmct-2024-03-29", "SDTM CT 2024-03-29", List.of(severity, ny));
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------


    /**
     * Fix A regression guard. {@code CtVersion} and {@code PublishedCtPackages} must carry the
     * package <em>id</em> that was requested, not {@link CtPackage#name()} — which the CDISC
     * Library populates with a display label. Before the fix these keys held
     * {@code "SDTM CT 2024-03-29"}, which {@code valid_codelist_dates} can neither prefix-match
     * against {@code sdtmct} nor parse a date from.
     */
    @Test
    void ctVersionAndPublishedPackagesUseTheRequestedIdNotTheApiLabel()
    {
        CtPackageRef ct = sdtmCtFixture();
        // Guard the premise: the fixture really does carry a label in name(), as the API does.
        assertEquals("SDTM CT 2024-03-29", ct.pkg().name().orElse(null));

        CdiscLibraryMetadataLibrary lib = CdiscLibraryMetadataLibrary.fromSdtm("sdtmig", "3-4",
                sdtmFixture(), ct);

        assertEquals("sdtmct-2024-03-29", lib.getMetaValue(MetadataKeys.CT_VERSION).orElse(null));
        assertEquals(List.of("sdtmct-2024-03-29"),
                lib.getMetaValue(MetadataKeys.PUBLISHED_CT_PACKAGES).orElse(null));
    }


    /**
     * A {@link CtPackageRef} with no id still contributes its codelists, but leaves
     * {@code CtVersion} / {@code PublishedCtPackages} unset rather than inventing a value from the
     * label.
     */
    @Test
    void anonymousCtPackageLeavesCtVersionUnset()
    {
        CtPackageRef anonymous = CtPackageRef.anonymous(sdtmCtFixture().pkg());

        CdiscLibraryMetadataLibrary lib = CdiscLibraryMetadataLibrary.fromSdtm("sdtmig", "3-4",
                sdtmFixture(), anonymous);

        assertTrue(lib.getMetaValue(MetadataKeys.CT_VERSION).isEmpty());
        assertTrue(lib.getMetaValue(MetadataKeys.PUBLISHED_CT_PACKAGES).isEmpty());
        // Codelists still resolve — only the identity metadata is withheld.
        assertNotNull(lib.getCodelist("AESEV").orElse(null));
    }


    /** The explicit published-ids overload wins over the single-package default. */
    @Test
    void explicitPublishedCtPackagesOverrideTheSingleRequestedId()
    {
        List<String> published = List.of("sdtmct-2024-03-29", "sdtmct-2023-12-15");

        CdiscLibraryMetadataLibrary lib = CdiscLibraryMetadataLibrary.fromSdtm("sdtmig", "3-4",
                sdtmFixture(), sdtmCtFixture(), published);

        assertEquals("sdtmct-2024-03-29", lib.getMetaValue(MetadataKeys.CT_VERSION).orElse(null));
        assertEquals(published, lib.getMetaValue(MetadataKeys.PUBLISHED_CT_PACKAGES).orElse(null));
    }


    @Test
    void libraryNameAndVersionFromExplicitArguments()
    {
        CdiscLibraryMetadataLibrary lib = CdiscLibraryMetadataLibrary.fromSdtm("sdtmig", "3-4",
                sdtmFixture(), sdtmCtFixture());

        assertEquals("sdtmig", lib.getName());
        assertEquals("3-4", lib.getVersion());
        assertEquals("sdtmig", lib.getMetaValue(MetadataKeys.STANDARD_NAME).orElse(null));
        assertEquals("3-4", lib.getMetaValue(MetadataKeys.STANDARD_VERSION).orElse(null));
        assertEquals("sdtmct-2024-03-29", lib.getMetaValue(MetadataKeys.CT_VERSION).orElse(null));
    }


    @Test
    void datasetsAreBuiltFromAllClasses()
    {
        CdiscLibraryMetadataLibrary lib = CdiscLibraryMetadataLibrary.fromSdtm("sdtmig", "3-4",
                sdtmFixture(), sdtmCtFixture());

        List<IDataTableMetadata> tables = lib.getDataTables();
        assertEquals(2, tables.size());

        IDataTableMetadata ae = lib.getDataTable("AE").orElseThrow();
        assertEquals("AE", ae.getName());
        assertEquals("Adverse Events", ae.getLabel());
        assertEquals("Events", ae.getClassName());
        assertEquals("One record per adverse event per subject", ae.getStructure());

        IDataTableMetadata dm = lib.getDataTable("DM").orElseThrow();
        assertEquals("DM", dm.getName());
        assertEquals("Special-Purpose", dm.getClassName());
    }


    @Test
    void datasetLookupIsCaseInsensitive()
    {
        CdiscLibraryMetadataLibrary lib = CdiscLibraryMetadataLibrary.fromSdtm("sdtmig", "3-4",
                sdtmFixture(), sdtmCtFixture());
        assertTrue(lib.getDataTable("ae").isPresent());
        assertTrue(lib.getDataTable("Ae").isPresent());
        assertTrue(lib.getDataTable("AE").isPresent());
    }


    @Test
    void columnsAreOrderedByOrdinalAndHaveCorrectAttributes()
    {
        CdiscLibraryMetadataLibrary lib = CdiscLibraryMetadataLibrary.fromSdtm("sdtmig", "3-4",
                sdtmFixture(), sdtmCtFixture());

        IDataTableMetadata ae = lib.getDataTable("AE").orElseThrow();
        List<IColumnMetadata> cols = ae.getColumns();
        assertEquals(6, cols.size());
        assertEquals("STUDYID", cols.get(0).getName());
        assertEquals("AESEV", cols.get(5).getName());

        IColumnMetadata aeterm = ae.getColumn("AETERM").orElseThrow();
        assertEquals("Reported Term for the Adverse Event", aeterm.getLabel());
        assertEquals(DataValueType.STRING, aeterm.getType());
        assertEquals("Req", aeterm.getCore());
        assertEquals("Topic", aeterm.getRole());

        IColumnMetadata aeseq = ae.getColumn("AESEQ").orElseThrow();
        assertEquals(DataValueType.DOUBLE, aeseq.getType());
    }


    @Test
    void modelColumnOrderIsPopulatedFromClassVariables()
    {
        CdiscLibraryMetadataLibrary lib = CdiscLibraryMetadataLibrary.fromSdtm("sdtmig", "3-4",
                sdtmFixture(), sdtmCtFixture());

        IDataTableMetadata ae = lib.getDataTable("AE").orElseThrow();
        Object mco = ae.getMetaValue(MetadataKeys.MODEL_COLUMN_ORDER).orElseThrow();
        assertTrue(mco instanceof List<?>);
        @SuppressWarnings("unchecked")
        List<String> mcoList = (List<String>) mco;
        assertEquals(List.of("STUDYID", "DOMAIN", "USUBJID"), mcoList);
    }


    @Test
    void isCustomDomainIsFalseForStandardDatasets()
    {
        CdiscLibraryMetadataLibrary lib = CdiscLibraryMetadataLibrary.fromSdtm("sdtmig", "3-4",
                sdtmFixture(), sdtmCtFixture());

        IDataTableMetadata ae = lib.getDataTable("AE").orElseThrow();
        assertEquals(false, ae.getMetaValue(MetadataKeys.IS_CUSTOM_DOMAIN).orElseThrow());
    }


    @Test
    void codelistReferenceIsResolvedToSubmissionValue()
    {
        CdiscLibraryMetadataLibrary lib = CdiscLibraryMetadataLibrary.fromSdtm("sdtmig", "3-4",
                sdtmFixture(), sdtmCtFixture());

        IDataTableMetadata ae = lib.getDataTable("AE").orElseThrow();
        IColumnMetadata aesev = ae.getColumn("AESEV").orElseThrow();
        // Concept id C66769 should have been resolved to its submission value "AESEV"
        assertEquals("AESEV", aesev.getCodelist());
    }


    @Test
    void codelistsAreBuiltWithConceptIdMetaKey()
    {
        CdiscLibraryMetadataLibrary lib = CdiscLibraryMetadataLibrary.fromSdtm("sdtmig", "3-4",
                sdtmFixture(), sdtmCtFixture());

        List<ICodeList> codelists = lib.getCodelists();
        assertEquals(2, codelists.size());

        ICodeList severity = lib.getCodelist("AESEV").orElseThrow();
        assertEquals("C66769",
                severity.getMetaValue(MetadataKeys.CODELIST_CONCEPT_ID).orElseThrow());
        assertEquals("AESEV",
                severity.getMetaValue(MetadataKeys.CODELIST_SUBMISSION_VALUE).orElseThrow());
        assertFalse(severity.isExtensible());

        List<ICodelistEntry> entries = severity.getEntries();
        assertEquals(3, entries.size());
        assertEquals("MILD", entries.get(0).getCodeValue());
        assertEquals("Mild", entries.get(0).getDecodeValue());
    }


    @Test
    void nullArgumentsAreRejected()
    {
        assertThrows(NullPointerException.class, () -> CdiscLibraryMetadataLibrary.fromSdtm(null,
                "3-4", sdtmFixture(), sdtmCtFixture()));
        assertThrows(NullPointerException.class, () -> CdiscLibraryMetadataLibrary
                .fromSdtm("sdtmig", null, sdtmFixture(), sdtmCtFixture()));
        assertThrows(NullPointerException.class,
                () -> CdiscLibraryMetadataLibrary.fromSdtm("sdtmig", "3-4", null, sdtmCtFixture()));
        assertThrows(NullPointerException.class,
                () -> CdiscLibraryMetadataLibrary.fromSdtm("sdtmig", "3-4", sdtmFixture(), null));
    }

    // ------------------------------------------------------------------
    // Integration: feed the result through MetadataLibraryProvider
    // ------------------------------------------------------------------


    @Test
    void integratesWithMetadataLibraryProvider()
    {
        CdiscLibraryMetadataLibrary lib = CdiscLibraryMetadataLibrary.fromSdtm("sdtmig", "3-4",
                sdtmFixture(), sdtmCtFixture());
        MetadataLibraryProvider provider = new MetadataLibraryProvider(lib);

        assertEquals("sdtmig", provider.getStandard());
        assertEquals("3-4", provider.getVersion());

        // Required variables for AE
        List<String> required = provider.getRequiredVariables("AE");
        assertTrue(required.contains("STUDYID"));
        assertTrue(required.contains("USUBJID"));
        assertTrue(required.contains("AESEQ"));
        assertTrue(required.contains("AETERM"));
        assertFalse(required.contains("AESEV"));

        // Model column order from class variables
        assertEquals(List.of("STUDYID", "DOMAIN", "USUBJID"), provider.getModelColumnOrder("AE"));

        // Codelist lookup by name
        assertEquals(List.of("MILD", "MODERATE", "SEVERE"), provider.getCodelistTerms("AESEV"));
        // Codelist lookup by concept id
        assertEquals(List.of("MILD", "MODERATE", "SEVERE"), provider.getCodelistTerms("C66769"));

        // Not custom
        assertFalse(provider.isDomainCustom("AE"));

        // Codelist extensibility
        assertFalse(provider.isCodelistExtensible("AESEV"));
        // Unknown codelist defaults to extensible
        assertTrue(provider.isCodelistExtensible("UNKNOWN"));

        // Dataset-level metadata
        Map<String, String> dsMeta = provider.getDatasetMetadata("AE");
        assertEquals("AE", dsMeta.get("name"));
        assertEquals("Adverse Events", dsMeta.get("label"));
        assertEquals("Events", dsMeta.get("className"));
        assertNotNull(dsMeta.get("datasetStructure"));
    }


    /**
     * The codelist accessors backing the {@code Value Check against Library Metadata} rule type
     * (NRI-008): {@code codelist_coded_values} (term submission values),
     * {@code codelist_coded_codes} (term concept ids — the ADaM code accessor), and
     * {@code codelist_extensible}. AESEV is bound to the non-extensible codelist C66769; a
     * non-codelist variable carries none of the keys.
     */
    @Test
    void codelistAccessorsArePopulatedForBoundVariable()
    {
        List<Map<String, Object>> terms = List.of(mkTerm("MILD", "Mild", "C100001"),
                mkTerm("MODERATE", "Moderate", "C100002"), mkTerm("SEVERE", "Severe", "C100003"));
        CtPackageRef ct = mkCtPackage("sdtmct-2024-03-29", "SDTM CT 2024-03-29",
                List.of(mkCodelist("C66769", "AESEV", false, terms)));
        CdiscLibraryMetadataLibrary lib = CdiscLibraryMetadataLibrary.fromSdtm("sdtmig", "3-4",
                sdtmFixture(), ct);
        MetadataLibraryProvider provider = new MetadataLibraryProvider(lib);

        Map<String, String> aesev = provider.getVariableMetadata("AE", "AESEV");
        assertEquals(DefineMetadataListCodec.encode(List.of("MILD", "MODERATE", "SEVERE")),
                aesev.get("codelist_coded_values"));
        assertEquals(DefineMetadataListCodec.encode(List.of("C100001", "C100002", "C100003")),
                aesev.get("codelist_coded_codes"));
        assertEquals("false", aesev.get("codelist_extensible"));

        // A variable with no library codelist carries none of the accessor keys (rule no-fires).
        Map<String, String> aeterm = provider.getVariableMetadata("AE", "AETERM");
        assertFalse(aeterm.containsKey("codelist_coded_values"));
        assertFalse(aeterm.containsKey("codelist_coded_codes"));
        assertFalse(aeterm.containsKey("codelist_extensible"));
    }

}
