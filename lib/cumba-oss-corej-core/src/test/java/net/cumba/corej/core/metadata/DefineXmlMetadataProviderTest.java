package net.cumba.corej.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.gen.DefineXMLProvider;
import org.junit.jupiter.api.Test;

/**
 * The in-core adapter from a direct {@link DefineXMLProvider} to the engine's
 * {@code MetadataProvider} define level: verifies the provider-key mapping
 * ({@code dataType}&rarr;{@code simpleDatatype}, {@code orderNumber}&rarr;{@code ordinal}) and that
 * the list-valued {@code codelist_coded_codes} is carried through unchanged.
 */
class DefineXmlMetadataProviderTest
{

    /** A minimal {@link DefineXMLProvider} stub exposing one DM variable with a codelist. */
    private static DefineXMLProvider stub()
    {
        return new DefineXMLProvider()
        {

            @Override
            public Map<String, String> getDatasetMetadata(String datasetName)
            {
                return Map.of("name", datasetName, "label", "Demographics");
            }


            @Override
            public List<Map<String, String>> getVariables(String datasetName)
            {
                return List.of(Map.of("name", "DOMAIN", "label", "Domain Abbreviation", "dataType",
                        "Char", "role", "Identifier", "orderNumber", "1", "codelist", "CL.DOMAIN",
                        "ccode", "C66734", "codelist_coded_codes", "[\"DM\",\"AE\"]"));
            }


            @Override
            public List<Map<String, String>> getValueLevelMetadata(String d, String v)
            {
                return List.of();
            }


            @Override
            public List<Map<String, String>> getWhereClauseConditions(String whereClauseOID)
            {
                return List.of();
            }


            @Override
            public List<Map<String, String>> getCodelistTerms(String codelistOID)
            {
                return List.of(Map.of("codedValue", "DM", "decode", "Demographics"),
                        Map.of("codedValue", "AE", "decode", "Adverse Events"));
            }


            @Override
            public List<String> getDatasetNames()
            {
                return List.of("DM");
            }


            @Override
            public List<String> getKeyVariables(String datasetName)
            {
                return List.of();
            }
        };
    }


    @Test
    void variableMetadata_mapsKeysToProviderChannel()
    {
        DefineXmlMetadataProvider provider = new DefineXmlMetadataProvider(stub());
        Map<String, String> meta = provider.getVariableMetadata("DM", "DOMAIN");

        assertEquals("DOMAIN", meta.get("name"));
        assertEquals("Char", meta.get("simpleDatatype"), "dataType -> simpleDatatype");
        assertEquals("Identifier", meta.get("role"));
        assertEquals("1", meta.get("ordinal"), "orderNumber -> ordinal");
        assertEquals("C66734", meta.get("ccode"));
        assertEquals("[\"DM\",\"AE\"]", meta.get("codelist_coded_codes"),
                "coded codes carried JSON-encoded");
    }


    @Test
    void variableMetadata_forwardsE2DefineAccessors()
    {
        DefineXMLProvider e2Stub = new DefineXMLProvider()
        {

            @Override
            public Map<String, String> getDatasetMetadata(String datasetName)
            {
                return Map.of("name", datasetName);
            }


            @Override
            public List<Map<String, String>> getVariables(String datasetName)
            {
                Map<String, String> v = new java.util.LinkedHashMap<>();
                v.put("name", "SEX");
                v.put("origin_type", "Derived");
                v.put("has_comment", "true");
                v.put("has_method", "false");
                v.put("external_dictionary", "MEDDRA");
                v.put("external_dictionary_version", "25.0");
                return List.of(v);
            }


            @Override
            public List<Map<String, String>> getValueLevelMetadata(String d, String v)
            {
                return List.of();
            }


            @Override
            public List<Map<String, String>> getWhereClauseConditions(String whereClauseOID)
            {
                return List.of();
            }


            @Override
            public List<Map<String, String>> getCodelistTerms(String codelistOID)
            {
                return List.of();
            }


            @Override
            public List<String> getDatasetNames()
            {
                return List.of("DM");
            }


            @Override
            public List<String> getKeyVariables(String datasetName)
            {
                return List.of();
            }
        };
        Map<String, String> meta = new DefineXmlMetadataProvider(e2Stub).getVariableMetadata("DM",
                "SEX");
        assertEquals("Derived", meta.get("origin_type"));
        assertEquals("true", meta.get("has_comment"));
        assertEquals("false", meta.get("has_method"));
        assertEquals("MEDDRA", meta.get("external_dictionary"));
        assertEquals("25.0", meta.get("external_dictionary_version"));
    }


    /**
     * A {@link DefineXMLProvider} stub exposing exactly one DM variable, built from {@code var}.
     */
    private static DefineXMLProvider oneVariable(Map<String, String> var)
    {
        return new DefineXMLProvider()
        {

            @Override
            public Map<String, String> getDatasetMetadata(String datasetName)
            {
                return Map.of("name", datasetName);
            }


            @Override
            public List<Map<String, String>> getVariables(String datasetName)
            {
                return List.of(var);
            }


            @Override
            public List<Map<String, String>> getValueLevelMetadata(String d, String v)
            {
                return List.of();
            }


            @Override
            public List<Map<String, String>> getWhereClauseConditions(String whereClauseOID)
            {
                return List.of();
            }


            @Override
            public List<Map<String, String>> getCodelistTerms(String codelistOID)
            {
                return List.of();
            }


            @Override
            public List<String> getDatasetNames()
            {
                return List.of("DM");
            }


            @Override
            public List<String> getKeyVariables(String datasetName)
            {
                return List.of();
            }
        };
    }


    /**
     * `Fix #263`: the sponsor-extended terms {@code OdmDefineXMLProvider} emits from
     * {@code def:ExtendedValue="Yes"} must reach the provider-key channel, otherwise
     * {@code var_codelist_extended_values("DEFINE")} resolves empty on every real Define-XML and
     * the rules reading it can never fire. Pinned both ways: the value round-trips, and an ItemDef
     * without extended values reads as the empty list rather than absent.
     */
    @Test
    void variableMetadata_forwardsCodelistExtendedValues()
    {
        DefineXmlMetadataProvider provider = new DefineXmlMetadataProvider(oneVariable(
                Map.of("name", "VSPOS", "codelist_extended_values", "[\"BOGUS\",\"OTHER\"]")));
        Map<String, String> meta = provider.getVariableMetadata("DM", "VSPOS");

        assertEquals("[\"BOGUS\",\"OTHER\"]", meta.get("codelist_extended_values"),
                "extended values carried JSON-encoded");
        assertEquals("[\"BOGUS\",\"OTHER\"]",
                provider.getDomainVariables("DM").get(0).get("codelist_extended_values"),
                "same mapping on the domain-variable (define ItemDef) channel");
    }


    @Test
    void variableMetadata_extendedValuesDefaultToEmptyList()
    {
        DefineXmlMetadataProvider provider = new DefineXmlMetadataProvider(stub());
        assertEquals("[]",
                provider.getVariableMetadata("DM", "DOMAIN").get("codelist_extended_values"),
                "an ItemDef with no extended terms reads as the empty list, like coded values");
    }


    @Test
    void domainVariablesAndColumnOrder()
    {
        DefineXmlMetadataProvider provider = new DefineXmlMetadataProvider(stub());
        assertEquals(1, provider.getDomainVariables("DM").size());
        assertEquals(List.of("DOMAIN"), provider.getColumnOrder("DM"));
    }


    @Test
    void codelistTerms_returnsCodedValues()
    {
        DefineXmlMetadataProvider provider = new DefineXmlMetadataProvider(stub());
        assertEquals(List.of("DM", "AE"), provider.getCodelistTerms("CL.DOMAIN"));
    }


    @Test
    void unknownVariable_returnsEmpty()
    {
        DefineXmlMetadataProvider provider = new DefineXmlMetadataProvider(stub());
        assertTrue(provider.getVariableMetadata("DM", "NOPE").isEmpty());
    }


    /** A datatable-style define fallback exposing keys the ODM omits, plus dataset metadata. */
    private static net.cumba.corej.core.exec.MetadataProvider fallback()
    {
        return new net.cumba.corej.core.exec.StubMetadataProvider()
                .variable("DM",
                        Map.of("name", "DOMAIN", "role", "STALE", "length", "8", "format", "$2."))
                .variable("DM", Map.of("name", "STUDYID", "role", "Identifier", "length", "20"));
    }


    @Test
    void withFallback_mergesOdmOverDatatableBase()
    {
        DefineXmlMetadataProvider provider = new DefineXmlMetadataProvider(stub(), fallback());
        Map<String, String> meta = provider.getVariableMetadata("DM", "DOMAIN");

        // ODM wins for role + adds ccode/coded codes; datatable-only keys survive.
        assertEquals("Identifier", meta.get("role"), "ODM role overrides fallback's STALE");
        assertEquals("C66734", meta.get("ccode"));
        assertEquals("[\"DM\",\"AE\"]", meta.get("codelist_coded_codes"));
        assertEquals("8", meta.get("length"), "datatable-only length preserved");
        assertEquals("$2.", meta.get("format"), "datatable-only format preserved");
    }


    @Test
    void withFallback_defineOnlyVariableDefersToFallback()
    {
        DefineXmlMetadataProvider provider = new DefineXmlMetadataProvider(stub(), fallback());
        // STUDYID is in the fallback but not the ODM stub -> served entirely by the fallback.
        Map<String, String> meta = provider.getVariableMetadata("DM", "STUDYID");
        assertEquals("Identifier", meta.get("role"));
        assertEquals("20", meta.get("length"));
    }


    @Test
    void declaredStructureKeyedProducts_delegatesToFallbackAndDefaultsEmpty()
    {
        // Provenance (log-only) follows the structure-keyed fallback when one is wired...
        net.cumba.corej.core.exec.MetadataProvider fb = org.mockito.Mockito
                .mock(net.cumba.corej.core.exec.MetadataProvider.class);
        org.mockito.Mockito.when(fb.declaredStructureKeyedProducts())
                .thenReturn(java.util.List.of("standards/adam/adamig-1-3"));
        assertEquals(java.util.List.of("standards/adam/adamig-1-3"),
                new DefineXmlMetadataProvider(stub(), fb).declaredStructureKeyedProducts());
        // ...and is empty for a define-only provider (nothing product-backed to cite).
        assertEquals(java.util.List.of(),
                new DefineXmlMetadataProvider(stub()).declaredStructureKeyedProducts());
    }


    @Test
    void structureKeyedAccessors_forwardTheSubclassToTheFallback()
    {
        // ⚠⚠ Phase 3 of PLAN-metadata-product-selection. This decorator wraps the run provider on
        // every ADaM run that carries a define.xml. Forwarding only the one-arg convenience would
        // strip the dataset's subclass, so the governing structure would never be selected and
        // every list would come from the base — with no exception and no log line.
        net.cumba.corej.core.exec.MetadataProvider fb = org.mockito.Mockito
                .mock(net.cumba.corej.core.exec.MetadataProvider.class);
        java.util.List<String> subclasses = java.util.List.of("ADVERSE EVENT");
        org.mockito.Mockito
                .when(fb.getRequiredVariablesForStructure("OCCURRENCE DATA STRUCTURE", subclasses))
                .thenReturn(java.util.List.of("AEDECOD"));
        org.mockito.Mockito
                .when(fb.getExpectedVariablesForStructure("OCCURRENCE DATA STRUCTURE", subclasses))
                .thenReturn(java.util.List.of("AEDECOD", "AESEQ"));

        DefineXmlMetadataProvider wrapped = new DefineXmlMetadataProvider(stub(), fb);
        assertEquals(java.util.List.of("AEDECOD"),
                wrapped.getRequiredVariablesForStructure("OCCURRENCE DATA STRUCTURE", subclasses));
        assertEquals(java.util.List.of("AEDECOD", "AESEQ"),
                wrapped.getExpectedVariablesForStructure("OCCURRENCE DATA STRUCTURE", subclasses));

        // With no fallback there is no CDISC Library behind this provider: null, i.e. "cannot
        // answer" — never an empty list, which would read as "requires nothing".
        DefineXmlMetadataProvider defineOnly = new DefineXmlMetadataProvider(stub());
        org.junit.jupiter.api.Assertions.assertNull(defineOnly
                .getRequiredVariablesForStructure("OCCURRENCE DATA STRUCTURE", subclasses));
        org.junit.jupiter.api.Assertions.assertNull(defineOnly
                .getExpectedVariablesForStructure("OCCURRENCE DATA STRUCTURE", subclasses));
    }
}
