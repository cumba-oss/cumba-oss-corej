package net.cumba.cdisc.core.metadata;

import static net.cumba.cdisc.core.metadata.TestMetadataFixtures.codelist;
import static net.cumba.cdisc.core.metadata.TestMetadataFixtures.column;
import static net.cumba.cdisc.core.metadata.TestMetadataFixtures.lib;
import static net.cumba.cdisc.core.metadata.TestMetadataFixtures.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

class MetadataLibraryProviderTest
{

    // ------------------------------------------------------------------
    // Standard / version
    // ------------------------------------------------------------------

    @Test
    void standardAndVersionComeFromMetaKeys()
    {
        IMetadataLibrary library = lib("study").meta(MetadataKeys.STANDARD_NAME, "sdtmig")
                .meta(MetadataKeys.STANDARD_VERSION, "3-4").build();
        MetadataProvider provider = new MetadataLibraryProvider(library);

        assertEquals("sdtmig", provider.getStandard());
        assertEquals("3-4", provider.getVersion());
    }


    @Test
    void standardAndVersionReturnNullWhenAbsent()
    {
        MetadataProvider provider = new MetadataLibraryProvider(lib("study").build());
        assertNull(provider.getStandard());
        assertNull(provider.getVersion());
    }


    @Test
    void nullLibraryThrows()
    {
        assertThrows(NullPointerException.class, () -> new MetadataLibraryProvider(null));
    }

    // ------------------------------------------------------------------
    // Variable filtering (required / expected / column order)
    // ------------------------------------------------------------------


    @Test
    void requiredAndExpectedVariablesRespectCore()
    {
        IMetadataLibrary library = lib("study")
                .table(table("DM")
                        .column(column("STUDYID", 0, DataValueType.STRING).core("Req").build())
                        .column(column("USUBJID", 1, DataValueType.STRING).core("Req").build())
                        .column(column("SEX", 2, DataValueType.STRING).core("Exp").build())
                        .column(column("AGE", 3, DataValueType.LONG).core("Perm").build()).build())
                .build();
        MetadataProvider provider = new MetadataLibraryProvider(library);

        assertEquals(List.of("STUDYID", "USUBJID"), provider.getRequiredVariables("DM"));
        assertEquals(List.of("STUDYID", "USUBJID", "SEX"), provider.getExpectedVariables("DM"));
        assertEquals(List.of("STUDYID", "USUBJID", "SEX", "AGE"), provider.getColumnOrder("DM"));
    }


    @Test
    void coreFallsBackToMetaKeyWhenGetterNull()
    {
        IMetadataLibrary library = lib("study").table(table("DM").column(
                column("STUDYID", 0, DataValueType.STRING).meta(MetadataKeys.CORE, "Req").build())
                .build()).build();
        MetadataProvider provider = new MetadataLibraryProvider(library);
        assertEquals(List.of("STUDYID"), provider.getRequiredVariables("DM"));
    }


    @Test
    void unknownDomainReturnsEmptyLists()
    {
        MetadataProvider provider = new MetadataLibraryProvider(lib("study").build());
        assertEquals(List.of(), provider.getRequiredVariables("XX"));
        assertEquals(List.of(), provider.getExpectedVariables("XX"));
        assertEquals(List.of(), provider.getColumnOrder("XX"));
        assertEquals(List.of(), provider.getDomainVariables("XX"));
    }

    // ------------------------------------------------------------------
    // Model column order — no silent fallback to column order
    // ------------------------------------------------------------------


    @Test
    void modelColumnOrderFromMetaKey()
    {
        IMetadataLibrary library = lib("study").table(table("AE")
                .meta(MetadataKeys.MODEL_COLUMN_ORDER,
                        List.of("STUDYID", "USUBJID", "AESEQ", "AETERM"))
                .column(column("STUDYID", 0, DataValueType.STRING).build())
                .column(column("AETERM", 1, DataValueType.STRING).build()).build()).build();
        MetadataProvider provider = new MetadataLibraryProvider(library);
        assertEquals(List.of("STUDYID", "USUBJID", "AESEQ", "AETERM"),
                provider.getModelColumnOrder("AE"));
    }


    @Test
    void modelColumnOrderIsEmptyWhenKeyMissing()
    {
        IMetadataLibrary library = lib("study")
                .table(table("AE").column(column("STUDYID", 0, DataValueType.STRING).build())
                        .column(column("AETERM", 1, DataValueType.STRING).build()).build())
                .build();
        MetadataProvider provider = new MetadataLibraryProvider(library);
        // Honest empty — no silent fallback to getColumnOrder.
        assertEquals(List.of(), provider.getModelColumnOrder("AE"));
    }


    @Test
    void modelColumnOrderEmptyForUnknownDomain()
    {
        MetadataProvider provider = new MetadataLibraryProvider(lib("study").build());
        assertEquals(List.of(), provider.getModelColumnOrder("XX"));
    }

    // ------------------------------------------------------------------
    // isDomainCustom
    // ------------------------------------------------------------------


    @Test
    void isDomainCustomFromBooleanMetaKey()
    {
        IMetadataLibrary library = lib("study")
                .table(table("MYAE").meta(MetadataKeys.IS_CUSTOM_DOMAIN, true).build())
                .table(table("DM").build()).build();
        MetadataProvider provider = new MetadataLibraryProvider(library);

        assertTrue(provider.isDomainCustom("MYAE"));
        assertFalse(provider.isDomainCustom("DM"));
    }


    @Test
    void isDomainCustomAcceptsStringBoolean()
    {
        IMetadataLibrary library = lib("study")
                .table(table("MYAE").meta(MetadataKeys.IS_CUSTOM_DOMAIN, "true").build()).build();
        MetadataProvider provider = new MetadataLibraryProvider(library);
        assertTrue(provider.isDomainCustom("MYAE"));
    }


    @Test
    void isDomainCustomFalseForUnknownDomain()
    {
        MetadataProvider provider = new MetadataLibraryProvider(lib("study").build());
        assertFalse(provider.isDomainCustom("XX"));
    }

    // ------------------------------------------------------------------
    // Variable / dataset metadata maps
    // ------------------------------------------------------------------


    @Test
    void variableMetadataContainsExpectedKeys()
    {
        IMetadataLibrary library = lib("study").table(table("DM").column(
                column("USUBJID", 1, DataValueType.STRING).label("Unique Subject Identifier")
                        .core("Req").role("Identifier").codelist("$USUBJID").build())
                .build()).build();
        MetadataProvider provider = new MetadataLibraryProvider(library);

        Map<String, String> meta = provider.getVariableMetadata("DM", "USUBJID");
        assertEquals("USUBJID", meta.get("name"));
        assertEquals("Unique Subject Identifier", meta.get("label"));
        assertEquals("Char", meta.get("simpleDatatype"));
        assertEquals("1", meta.get("ordinal"));
        assertEquals("Req", meta.get("core"));
        assertEquals("Identifier", meta.get("role"));
        assertEquals("$USUBJID", meta.get("codelist"));
    }


    @Test
    void domainVariablesReturnsAllColumns()
    {
        IMetadataLibrary library = lib("study").table(table("DM")
                .column(column("STUDYID", 0, DataValueType.STRING).core("Req").build())
                .column(column("USUBJID", 1, DataValueType.STRING).core("Req").build()).build())
                .build();
        MetadataProvider provider = new MetadataLibraryProvider(library);

        List<Map<String, String>> vars = provider.getDomainVariables("DM");
        assertEquals(2, vars.size());
        assertEquals("STUDYID", vars.get(0).get("name"));
        assertEquals("USUBJID", vars.get(1).get("name"));
    }


    @Test
    void datasetMetadataContainsNameLabelStructureClass()
    {
        IMetadataLibrary library = lib("study").table(table("AE").label("Adverse Events")
                .className("EVENTS").structure("One record per adverse event").build()).build();
        MetadataProvider provider = new MetadataLibraryProvider(library);

        Map<String, String> meta = provider.getDatasetMetadata("AE");
        assertEquals("AE", meta.get("name"));
        assertEquals("Adverse Events", meta.get("label"));
        assertEquals("EVENTS", meta.get("className"));
        assertEquals("One record per adverse event", meta.get("datasetStructure"));
    }

    // ------------------------------------------------------------------
    // DataValueType → simpleDatatype mapping
    // ------------------------------------------------------------------


    @Test
    void simpleDatatypeCoversAllNumericTypes()
    {
        IMetadataLibrary library = lib("study")
                .table(table("X").column(column("A", 0, DataValueType.STRING).build())
                        .column(column("B", 1, DataValueType.LONG).build())
                        .column(column("C", 2, DataValueType.DOUBLE).build())
                        .column(column("D", 3, DataValueType.BOOLEAN).build()).build())
                .build();
        MetadataProvider provider = new MetadataLibraryProvider(library);
        List<Map<String, String>> vars = provider.getDomainVariables("X");

        assertEquals("Char", vars.get(0).get("simpleDatatype"));
        assertEquals("Num", vars.get(1).get("simpleDatatype"));
        assertEquals("Num", vars.get(2).get("simpleDatatype"));
        assertEquals("Num", vars.get(3).get("simpleDatatype"));
    }

    // ------------------------------------------------------------------
    // Codelist lookup — by name, concept id, submission value
    // ------------------------------------------------------------------


    @Test
    void codelistLookupByName()
    {
        IMetadataLibrary library = lib("study")
                .codelist(codelist("NY").entry("N", "No").entry("Y", "Yes").build()).build();
        MetadataProvider provider = new MetadataLibraryProvider(library);
        assertEquals(List.of("N", "Y"), provider.getCodelistTerms("NY"));
    }


    @Test
    void codelistLookupByConceptId()
    {
        IMetadataLibrary library = lib("study")
                .codelist(codelist("NY").meta(MetadataKeys.CODELIST_CONCEPT_ID, "C66742")
                        .entry("N", "No").entry("Y", "Yes").build())
                .build();
        MetadataProvider provider = new MetadataLibraryProvider(library);
        assertEquals(List.of("N", "Y"), provider.getCodelistTerms("C66742"));
    }


    @Test
    void codelistLookupBySubmissionValueMetaKey()
    {
        IMetadataLibrary library = lib("study").codelist(
                codelist("internal-name").meta(MetadataKeys.CODELIST_SUBMISSION_VALUE, "SEX")
                        .entry("M", "Male").entry("F", "Female").build())
                .build();
        MetadataProvider provider = new MetadataLibraryProvider(library);
        assertEquals(List.of("M", "F"), provider.getCodelistTerms("SEX"));
    }


    @Test
    void codelistTermMappingsPreserveOrder()
    {
        IMetadataLibrary library = lib("study").codelist(
                codelist("NY").entry("N", "No").entry("Y", "Yes").entry("U", "Unknown").build())
                .build();
        MetadataProvider provider = new MetadataLibraryProvider(library);

        Map<String, String> mappings = provider.getCodelistTermMappings("NY");
        assertEquals(3, mappings.size());
        assertEquals("No", mappings.get("N"));
        assertEquals("Yes", mappings.get("Y"));
        assertEquals("Unknown", mappings.get("U"));
        // LinkedHashMap preserves insertion order
        assertEquals(List.of("N", "Y", "U"), List.copyOf(mappings.keySet()));
    }


    @Test
    void variableMetadataCarriesCodelistCcodeAtLibraryLevel()
    {
        // A column bound to a codelist whose CODELIST_CONCEPT_ID is C66742 (the NY codelist C-code)
        // must surface that C-code under "ccode" — backing library_variable_ccode (CDISC-SEND-0055,
        // library_variable_ccode == "C66742"). Mirrors Python's library-level ccode
        // materialisation.
        IMetadataLibrary library = lib("study").table(table("AE")
                .column(column("AENY", 1, DataValueType.STRING).codelist("NY").build()).build())
                .codelist(codelist("NY").meta(MetadataKeys.CODELIST_CONCEPT_ID, "C66742")
                        .entry("N", "No").entry("Y", "Yes").build())
                .build();
        MetadataProvider provider = new MetadataLibraryProvider(library);

        Map<String, String> meta = provider.getVariableMetadata("AE", "AENY");
        assertEquals("C66742", meta.get("ccode"));
    }


    @Test
    void variableMetadataOmitsCcodeWhenNoCodelist()
    {
        // No codelist bound → ccode absent (ccodeOf returns null → putIfPresent omits the key).
        IMetadataLibrary library = lib("study").table(
                table("AE").column(column("AETERM", 1, DataValueType.STRING).build()).build())
                .build();
        MetadataProvider provider = new MetadataLibraryProvider(library);

        Map<String, String> meta = provider.getVariableMetadata("AE", "AETERM");
        assertNull(meta.get("ccode"));
    }


    @Test
    void variableMetadataOmitsCcodeWhenCodelistHasNoConceptId()
    {
        // Bound codelist resolves but exposes no CODELIST_CONCEPT_ID → ccode absent.
        IMetadataLibrary library = lib("study").table(table("AE")
                .column(column("AENY", 1, DataValueType.STRING).codelist("NY").build()).build())
                .codelist(codelist("NY").entry("N", "No").entry("Y", "Yes").build()).build();
        MetadataProvider provider = new MetadataLibraryProvider(library);

        Map<String, String> meta = provider.getVariableMetadata("AE", "AENY");
        assertNull(meta.get("ccode"));
    }


    @Test
    void unknownCodelistReturnsEmptyListAndMap()
    {
        MetadataProvider provider = new MetadataLibraryProvider(lib("study").build());
        assertEquals(List.of(), provider.getCodelistTerms("SEX"));
        assertEquals(Map.of(), provider.getCodelistTermMappings("SEX"));
    }


    @Test
    void isCodelistExtensibleReadsBooleanFlag()
    {
        IMetadataLibrary library = lib("study")
                .codelist(codelist("EXT").extensible(Boolean.TRUE).build())
                .codelist(codelist("NONEXT").extensible(Boolean.FALSE).build()).build();
        MetadataProvider provider = new MetadataLibraryProvider(library);
        assertTrue(provider.isCodelistExtensible("EXT"));
        assertFalse(provider.isCodelistExtensible("NONEXT"));
    }


    @Test
    void isCodelistExtensibleTrueForUnknown()
    {
        MetadataProvider provider = new MetadataLibraryProvider(lib("study").build());
        assertTrue(provider.isCodelistExtensible("UNKNOWN"));
    }


    @Test
    void isCodelistExtensibleTrueWhenFlagNull()
    {
        IMetadataLibrary library = lib("study").codelist(codelist("X").extensible(null).build())
                .build();
        MetadataProvider provider = new MetadataLibraryProvider(library);
        assertTrue(provider.isCodelistExtensible("X"));
    }

    // ------------------------------------------------------------------
    // General robustness
    // ------------------------------------------------------------------


    @Test
    void emptyCodelistOrCodeReturnsEmpty()
    {
        MetadataProvider provider = new MetadataLibraryProvider(lib("study").build());
        assertEquals(List.of(), provider.getCodelistTerms(""));
        assertNotNull(provider.getCodelistTerms(null));
        assertEquals(List.of(), provider.getCodelistTerms(null));
    }

}
