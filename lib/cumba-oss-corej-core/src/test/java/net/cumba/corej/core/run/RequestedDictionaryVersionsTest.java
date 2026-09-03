package net.cumba.corej.core.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.gen.DefineXMLProvider;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-dictionary-seeder} Phase 6b (D6) — the caller-side half of dictionary version
 * selection: {@link StudyValidationService#requestedDictionaryVersions} merges the CLI options over
 * the define.xml declarations into the {@code requested} map {@code DictionaryStore.load} binds
 * with. The manifest sits below both, inside the store, and "nothing requested" stays an empty map
 * — never a guess.
 */
class RequestedDictionaryVersionsTest
{

    /** A define provider declaring fixed dictionary versions and nothing else. */
    private static DefineXMLProvider declaring(Map<String, String> versions)
    {
        return new DefineXMLProvider()
        {

            @Override
            public Map<String, String> externalDictionaryVersions()
            {
                return versions;
            }


            @Override
            public Map<String, String> getDatasetMetadata(String datasetName)
            {
                return Map.of();
            }


            @Override
            public List<Map<String, String>> getVariables(String datasetName)
            {
                return List.of();
            }


            @Override
            public List<Map<String, String>> getValueLevelMetadata(String datasetName,
                    String variableName)
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
                return List.of();
            }


            @Override
            public List<String> getKeyVariables(String datasetName)
            {
                return List.of();
            }
        };
    }


    /** The ruled precedence: a CLI option always beats the define.xml declaration (D6 item 1). */
    @Test
    void aCliVersionBeatsTheDefineXmlDeclaration()
    {
        Map<String, String> merged = StudyValidationService.requestedDictionaryVersions(
                Map.of("meddra", "27.0"), declaring(Map.of("meddra", "26.1", "unii", "4Aug2026")));

        assertEquals("27.0", merged.get("meddra"), "the CLI option wins");
        assertEquals("4Aug2026", merged.get("unii"), "undisputed define declarations survive");
    }


    /** With no CLI option, the define.xml declaration is the selection (D6 item 2). */
    @Test
    void theDefineXmlDeclarationIsUsedWhenNoCliOptionNamesTheType()
    {
        Map<String, String> merged = StudyValidationService.requestedDictionaryVersions(Map.of(),
                declaring(Map.of("whodrug", "SEP_2020")));

        assertEquals(Map.of("whodrug", "SEP_2020"), merged);
    }


    /**
     * No define.xml and no CLI option means an EMPTY request — the store then consults its
     * manifest, and failing that skips. Nothing is ever inferred here.
     */
    @Test
    void noSourcesYieldsAnEmptyRequest()
    {
        assertTrue(StudyValidationService.requestedDictionaryVersions(Map.of(), null).isEmpty());
    }


    /** Defensive normalisation: CLI keys are lower-cased, blank CLI values contribute nothing. */
    @Test
    void cliKeysAreLowerCasedAndBlankValuesIgnored()
    {
        Map<String, String> merged = StudyValidationService
                .requestedDictionaryVersions(Map.of("MedDRA", "27.0", "unii", "  "), null);

        assertEquals(Map.of("meddra", "27.0"), merged);
    }

}
