package net.cumba.corej.ruletest.cdt.ruletest;

import static net.cumba.corej.ruletest.cdt.ruletest.MapBackedLibraryMetadataProvider.var;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.MetadataProvider;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link MapBackedLibraryMetadataProvider} and its fluent
 * {@link MapBackedLibraryMetadataProvider.Builder}. Verifies the empty defaults of every
 * {@link MetadataProvider} method, case-folding of domain/codelist keys, round-trip through the
 * introspection getters, and the static {@code var(…)} helpers.
 */
class MapBackedLibraryMetadataProviderTest
{

    @Nested
    class EmptyDefaults
    {

        @Test
        void empty_returnsBlankProvider()
        {
            MapBackedLibraryMetadataProvider p = MapBackedLibraryMetadataProvider.empty();

            assertEquals("test", p.getStandard());
            assertEquals("1", p.getVersion());
            assertTrue(p.isEmpty());
        }


        @Test
        void allListGetters_returnEmptyListForUnknownDomain()
        {
            MapBackedLibraryMetadataProvider p = MapBackedLibraryMetadataProvider.empty();

            assertEquals(List.of(), p.getRequiredVariables("AE"));
            assertEquals(List.of(), p.getExpectedVariables("AE"));
            assertEquals(List.of(), p.getColumnOrder("AE"));
            assertEquals(List.of(), p.getModelColumnOrder("AE"));
            assertEquals(List.of(), p.getCodelistTerms("C66731"));
            assertEquals(List.of(), p.getDomainVariables("AE"));
            assertEquals(List.of(), p.getModelVariables("AE"));
            assertEquals(List.of(), p.getPublishedCtPackages());
        }


        @Test
        void allMapGetters_returnEmptyMapForUnknownDomain()
        {
            MapBackedLibraryMetadataProvider p = MapBackedLibraryMetadataProvider.empty();

            assertEquals(Map.of(), p.getVariableMetadata("AE", "AEOCCUR"));
            assertEquals(Map.of(), p.getDatasetMetadata("AE"));
            assertEquals(Map.of(), p.getCodelistTermMappings("NY"));
        }


        @Test
        void isDomainCustom_falseByDefault()
        {
            MapBackedLibraryMetadataProvider p = MapBackedLibraryMetadataProvider.empty();
            assertFalse(p.isDomainCustom("AE"));
        }


        @Test
        void isCodelistExtensible_trueByDefault()
        {
            MapBackedLibraryMetadataProvider p = MapBackedLibraryMetadataProvider.empty();
            assertTrue(p.isCodelistExtensible("UnknownCodelist"));
        }


        @Test
        void nullDomain_caseFoldsToEmptyKey_andReturnsEmptyDefaults()
        {
            // up("null") returns "" — the getter should still return the default.
            MapBackedLibraryMetadataProvider p = MapBackedLibraryMetadataProvider.empty();
            assertEquals(List.of(), p.getRequiredVariables(null));
            assertEquals(Map.of(), p.getDatasetMetadata(null));
            assertFalse(p.isDomainCustom(null));
        }
    }


    @Nested
    class BuilderScalarFields
    {

        @Test
        void standardAndVersion_areSet()
        {
            MapBackedLibraryMetadataProvider p = MapBackedLibraryMetadataProvider.builder()
                    .standard("sdtmig").version("3-4").build();

            assertEquals("sdtmig", p.getStandard());
            assertEquals("3-4", p.getVersion());
        }
    }


    @Nested
    class BuilderListMaps
    {

        @Test
        void requiredVariables_caseInsensitiveLookup()
        {
            MapBackedLibraryMetadataProvider p = MapBackedLibraryMetadataProvider.builder()
                    .requiredVariables("dm", "STUDYID", "USUBJID").build();

            assertEquals(List.of("STUDYID", "USUBJID"), p.getRequiredVariables("DM"));
            assertEquals(List.of("STUDYID", "USUBJID"), p.getRequiredVariables("dm"));
            assertEquals(List.of("STUDYID", "USUBJID"), p.getRequiredVariables("Dm"));
            // Introspection getter stores the upper-cased key.
            assertTrue(p.getRequiredVariablesMap().containsKey("DM"));
        }


        @Test
        void expectedVariables_storedAndRetrieved()
        {
            MapBackedLibraryMetadataProvider p = MapBackedLibraryMetadataProvider.builder()
                    .expectedVariables("AE", "AESEV", "AESER").build();

            assertEquals(List.of("AESEV", "AESER"), p.getExpectedVariables("AE"));
            assertEquals(Map.of("AE", List.of("AESEV", "AESER")), p.getExpectedVariablesMap());
        }


        @Test
        void columnOrder_storedAndRetrieved()
        {
            MapBackedLibraryMetadataProvider p = MapBackedLibraryMetadataProvider.builder()
                    .columnOrder("AE", "STUDYID", "USUBJID", "AESEQ").build();

            assertEquals(List.of("STUDYID", "USUBJID", "AESEQ"), p.getColumnOrder("AE"));
            assertEquals(Map.of("AE", List.of("STUDYID", "USUBJID", "AESEQ")),
                    p.getColumnOrderMap());
        }


        @Test
        void modelColumnOrder_storedAndRetrieved()
        {
            MapBackedLibraryMetadataProvider p = MapBackedLibraryMetadataProvider.builder()
                    .modelColumnOrder("AE", "STUDYID", "USUBJID").build();

            assertEquals(List.of("STUDYID", "USUBJID"), p.getModelColumnOrder("AE"));
            assertEquals(Map.of("AE", List.of("STUDYID", "USUBJID")), p.getModelColumnOrderMap());
        }


        @Test
        void codelistTerms_storedAndRetrieved()
        {
            MapBackedLibraryMetadataProvider p = MapBackedLibraryMetadataProvider.builder()
                    .codelistTerms("c66731", "Y", "N").build();

            // case-folded codelist key
            assertEquals(List.of("Y", "N"), p.getCodelistTerms("C66731"));
            assertTrue(p.getCodelistTermsMap().containsKey("C66731"));
        }


        /**
         * CT2003 — the code map keys on (domain, variable), both case-folded, while the TERM keys
         * stay verbatim: the accessor looks them up with the submitted cell value, and decodes are
         * mixed case ("Albumin").
         */
        @Test
        void codelistCodes_storedCaseFoldedWithVerbatimTerms()
        {
            MapBackedLibraryMetadataProvider p = MapBackedLibraryMetadataProvider.builder()
                    .codelistCodes("lb", "lbtestcd", Map.of("ALB", "C64431"))
                    .codelistCodes("LB", "LBTEST", Map.of("Albumin", "C64431")).build();

            assertEquals("C64431", p.getCodelistCodeMap("LB", "LBTESTCD").get("ALB"));
            assertEquals("C64431", p.getCodelistCodeMap("lb", "lbtestcd").get("ALB"));
            assertEquals("C64431", p.getCodelistCodeMap("LB", "LBTEST").get("Albumin"));
            // The term key is NOT folded — an upper-cased decode must not resolve.
            assertNull(p.getCodelistCodeMap("LB", "LBTEST").get("ALBUMIN"));
            assertFalse(p.isEmpty());
            assertTrue(p.getCodelistCodesMap().containsKey("LB"));
        }


        /** An undeclared domain or variable yields the interface default: an empty map. */
        @Test
        void codelistCodes_undeclared_isEmptyMap()
        {
            MapBackedLibraryMetadataProvider p = MapBackedLibraryMetadataProvider.builder()
                    .codelistCodes("LB", "LBTESTCD", Map.of("ALB", "C64431")).build();

            assertTrue(p.getCodelistCodeMap("LB", "LBTEST").isEmpty());
            assertTrue(p.getCodelistCodeMap("AE", "AETERM").isEmpty());
            assertTrue(MapBackedLibraryMetadataProvider.empty().getCodelistCodeMap("LB", "LBTESTCD")
                    .isEmpty());
        }


        /** Declaring the same (domain, variable) twice REPLACES the map, like variable-metadata. */
        @Test
        void codelistCodes_redeclared_replaces()
        {
            MapBackedLibraryMetadataProvider p = MapBackedLibraryMetadataProvider.builder()
                    .codelistCodes("LB", "LBTESTCD", Map.of("ALB", "C64431"))
                    .codelistCodes("LB", "LBTESTCD", Map.of("BILI", "C64433")).build();

            assertEquals(Map.of("BILI", "C64433"), p.getCodelistCodeMap("LB", "LBTESTCD"));
        }
    }


    @Nested
    class BuilderCustomDomains
    {

        @Test
        void customDomain_caseInsensitiveLookup()
        {
            MapBackedLibraryMetadataProvider p = MapBackedLibraryMetadataProvider.builder()
                    .customDomain("xx").build();

            assertTrue(p.isDomainCustom("XX"));
            assertTrue(p.isDomainCustom("xx"));
            assertFalse(p.isDomainCustom("AE"));
            assertEquals(java.util.Set.of("XX"), p.getCustomDomainsSet());
        }
    }


    @Nested
    class BuilderCodelistFlags
    {

        @Test
        void codelistExtensible_overridesDefault()
        {
            MapBackedLibraryMetadataProvider p = MapBackedLibraryMetadataProvider.builder()
                    .codelistExtensible("ny", false).build();

            assertFalse(p.isCodelistExtensible("NY"));
            // unknown codelists still default to true
            assertTrue(p.isCodelistExtensible("OTHER"));
        }


        @Test
        void codelistTermMappings_storedAndRetrieved()
        {
            Map<String, String> mappings = Map.of("Y", "Yes", "N", "No");
            MapBackedLibraryMetadataProvider p = MapBackedLibraryMetadataProvider.builder()
                    .codelistTermMappings("ny", mappings).build();

            assertEquals(mappings, p.getCodelistTermMappings("NY"));
            // unknown codelist returns empty
            assertEquals(Map.of(), p.getCodelistTermMappings("OTHER"));
        }
    }


    @Nested
    class BuilderVariableMetadata
    {

        @Test
        void variableMetadata_caseFoldedKeys()
        {
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("label", "Adverse Event Severity");
            meta.put("core", "Exp");
            MapBackedLibraryMetadataProvider p = MapBackedLibraryMetadataProvider.builder()
                    .variableMetadata("ae", "aesev", meta).build();

            Map<String, String> got = p.getVariableMetadata("AE", "AESEV");
            assertEquals(meta, got);
            assertEquals(meta, p.getVariableMetadata("ae", "Aesev"));
            // Other variable on the same (existing) domain returns empty.
            assertEquals(Map.of(), p.getVariableMetadata("AE", "UNKNOWN"));
            // Other domain entirely returns empty.
            assertEquals(Map.of(), p.getVariableMetadata("DM", "AESEV"));
        }
    }


    @Nested
    class BuilderDomainAndModelVariables
    {

        @Test
        void domainVariables_listForm_storedAndRetrieved()
        {
            List<Map<String, String>> vars = List.of(var("STUDYID", "Topic"),
                    var("USUBJID", "Topic", "label", "Unique Subject Identifier"));

            MapBackedLibraryMetadataProvider p = MapBackedLibraryMetadataProvider.builder()
                    .domainVariables("dm", vars).build();

            List<Map<String, String>> got = p.getDomainVariables("DM");
            assertEquals(2, got.size());
            assertEquals("STUDYID", got.get(0).get("name"));
            assertEquals("Unique Subject Identifier", got.get(1).get("label"));
            assertEquals(2, p.getDomainVariablesMap().get("DM").size());
        }


        @Test
        void domainVariable_appendForm_buildsList()
        {
            MapBackedLibraryMetadataProvider p = MapBackedLibraryMetadataProvider.builder()
                    .domainVariable("AE", "STUDYID", "Topic")
                    .domainVariable("AE", "USUBJID", "Topic").build();

            List<Map<String, String>> vars = p.getDomainVariables("AE");
            assertEquals(2, vars.size());
            assertEquals("STUDYID", vars.get(0).get("name"));
            assertEquals("USUBJID", vars.get(1).get("name"));
        }


        @Test
        void modelVariables_listForm_storedAndRetrieved()
        {
            List<Map<String, String>> vars = List.of(var("AETERM", "Topic"));
            MapBackedLibraryMetadataProvider p = MapBackedLibraryMetadataProvider.builder()
                    .modelVariables("ae", vars).build();

            assertEquals(1, p.getModelVariables("AE").size());
            assertEquals("AETERM", p.getModelVariables("AE").get(0).get("name"));
            assertEquals(1, p.getModelVariablesMap().get("AE").size());
        }


        @Test
        void modelVariable_appendForm_buildsList()
        {
            MapBackedLibraryMetadataProvider p = MapBackedLibraryMetadataProvider.builder()
                    .modelVariable("AE", "AETERM", "Topic").modelVariable("AE", "AESEV", "Qual")
                    .build();

            List<Map<String, String>> vars = p.getModelVariables("AE");
            assertEquals(2, vars.size());
            assertEquals("AETERM", vars.get(0).get("name"));
            assertEquals("AESEV", vars.get(1).get("name"));
        }


        /** EC-85 — the class-keyed sibling of the two forms above. */
        @Test
        void modelClassVariables_bothForms_classKeyedAndCaseInsensitive()
        {
            MapBackedLibraryMetadataProvider p = MapBackedLibraryMetadataProvider.builder()
                    .modelClassVariables("events", List.of(var("--TERM", "Topic")))
                    .modelClassVariable("Findings", "--TESTCD", "Topic")
                    .modelClassVariable("FINDINGS", "--ORRES", "Result Qualifier").build();

            assertEquals(1, p.getModelVariablesForClass("EVENTS").size());
            assertEquals("--TERM", p.getModelVariablesForClass("EVENTS").get(0).get("name"));
            List<Map<String, String>> findings = p.getModelVariablesForClass("findings");
            assertEquals(2, findings.size());
            assertEquals("--ORRES", findings.get(1).get("name"));
            assertTrue(p.getModelVariablesForClass("INTERVENTIONS").isEmpty());
            assertEquals(2, p.getModelClassVariablesMap().size());
            // Domain-keyed and class-keyed maps are independent.
            assertTrue(p.getModelVariables("EVENTS").isEmpty());
            assertTrue(p.getModelVariablesMap().isEmpty());
            // The production resolver entry point stays at the interface default (null) so the
            // executor takes the substituting fallback.
            assertNull(p.getStandardModelVariablesForClass(null, null, "EVENTS"));
        }
    }


    @Nested
    class BuilderDatasetMetadata
    {

        @Test
        void datasetClass_shorthand_setsClassName()
        {
            MapBackedLibraryMetadataProvider p = MapBackedLibraryMetadataProvider.builder()
                    .datasetClass("adlbc", "BDS").build();

            Map<String, String> md = p.getDatasetMetadata("ADLBC");
            assertEquals("BDS", md.get("className"));
            assertEquals(1, md.size());
            assertTrue(p.getDatasetMetadataMap().containsKey("ADLBC"));
        }


        @Test
        void datasetMetadata_fullMap_storedAndRetrieved()
        {
            MapBackedLibraryMetadataProvider p = MapBackedLibraryMetadataProvider.builder()
                    .datasetMetadata("ADAE",
                            Map.of("className", "OCCURRENCE", "label", "Adverse Events"))
                    .build();

            Map<String, String> md = p.getDatasetMetadata("ADAE");
            assertEquals("OCCURRENCE", md.get("className"));
            assertEquals("Adverse Events", md.get("label"));
        }
    }


    @Nested
    class BuilderPublishedCtPackages
    {

        @Test
        void publishedCtPackages_varargsForm()
        {
            MapBackedLibraryMetadataProvider p = MapBackedLibraryMetadataProvider.builder()
                    .publishedCtPackages("sdtmct-2023-10-26", "sdtmct-2024-03-29").build();

            assertEquals(List.of("sdtmct-2023-10-26", "sdtmct-2024-03-29"),
                    p.getPublishedCtPackages());
            assertEquals(2, p.getPublishedCtPackagesList().size());
        }


        @Test
        void publishedCtPackages_listForm_overwritesPrevious()
        {
            MapBackedLibraryMetadataProvider p = MapBackedLibraryMetadataProvider.builder()
                    .publishedCtPackages("OLD-1", "OLD-2").publishedCtPackages(List.of("NEW-1"))
                    .build();

            assertEquals(List.of("NEW-1"), p.getPublishedCtPackages());
        }
    }


    @Nested
    class VarHelpers
    {

        @Test
        void var_twoArg_buildsNameRoleMap()
        {
            Map<String, String> m = var("AETERM", "Topic");
            assertEquals(2, m.size());
            assertEquals("AETERM", m.get("name"));
            assertEquals("Topic", m.get("role"));
        }


        @Test
        void var_extras_appendKeyValuePairs()
        {
            Map<String, String> m = var("AETERM", "Topic", "label", "Reported Term", "core", "Req");
            assertEquals(4, m.size());
            assertEquals("Reported Term", m.get("label"));
            assertEquals("Req", m.get("core"));
        }


        @Test
        void var_oddExtras_throws()
        {
            assertThrows(IllegalArgumentException.class, () -> var("AETERM", "Topic", "label"));
        }
    }


    @Nested
    class IsEmpty
    {

        @Test
        void isEmpty_falseAfterAnyDeclaration()
        {
            assertFalse(MapBackedLibraryMetadataProvider.builder()
                    .requiredVariables("AE", "STUDYID").build().isEmpty());

            assertFalse(MapBackedLibraryMetadataProvider.builder().codelistExtensible("NY", false)
                    .build().isEmpty());

            assertFalse(MapBackedLibraryMetadataProvider.builder().publishedCtPackages("p").build()
                    .isEmpty());

            assertFalse(MapBackedLibraryMetadataProvider.builder().customDomain("XX").build()
                    .isEmpty());

            assertFalse(MapBackedLibraryMetadataProvider.builder().datasetClass("ADAE", "BDS")
                    .build().isEmpty());

            assertFalse(MapBackedLibraryMetadataProvider.builder()
                    .codelistTermMappings("NY", Map.of("Y", "Yes")).build().isEmpty());

            assertFalse(MapBackedLibraryMetadataProvider.builder()
                    .variableMetadata("AE", "X", Map.of("label", "v")).build().isEmpty());

            assertFalse(MapBackedLibraryMetadataProvider.builder()
                    .domainVariable("AE", "X", "Topic").build().isEmpty());

            assertFalse(MapBackedLibraryMetadataProvider.builder().modelVariable("AE", "X", "Topic")
                    .build().isEmpty());

            assertFalse(MapBackedLibraryMetadataProvider.builder()
                    .modelClassVariable("EVENTS", "--TERM", "Topic").build().isEmpty());
        }
    }
}
