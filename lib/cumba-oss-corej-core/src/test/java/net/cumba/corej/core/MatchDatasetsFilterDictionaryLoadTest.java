package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.model.Rule;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-bugfix-engine-define} SF, BD6′ (β) (owner 2026-10-16: <i>"agree to β"</i>): an
 * external-dictionary call inside a {@code Match_Datasets} Filter is refused at load. The Filter is
 * evaluated while the merged view is built, in a context with no dictionary provider and before
 * every dictionary skip arm, so the call ERRORed at run time in every dictionary state — healthy,
 * stale or not loaded (confirmation look HIGH-1). The W4 precedent (RF1 (a)): a spelling that
 * cannot do what it says is refused at load. 0 corpus carriers.
 */
class MatchDatasetsFilterDictionaryLoadTest
{

    private static Rule load(String filter, String check) throws Exception
    {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("Core", Map.of("Id", "CFX-DICT"));
        rule.put("Check", Map.of("expression", check));
        rule.put("Outcome", Map.of("Message", "m"));
        rule.put("Requirements", Map.of("Variables", Map.of("All", List.of("USUBJID"),
                "All_Or_None", List.of(List.of("USUBJID", "AE.USUBJID")))));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("Name", "AE");
        entry.put("Keys", List.of("USUBJID"));
        entry.put("Join_Type", "left");
        if (filter != null)
        {
            entry.put("Filter", filter);
        }
        rule.put("Match_Datasets", List.of(entry));
        String json = new ObjectMapper().writeValueAsString(Map.of("rules", Map.of("x", rule)));
        Rule loaded = RulePackageLoader.loadFromString(json).getRules().get("x");
        assertNotNull(loaded);
        return loaded;
    }


    @Test
    void aHierarchyCallInAFilterIsALoadErrorNamingTheCallAndTheEntry() throws Exception
    {
        Rule rule = load(
                "valid_external_dictionary_hierarchy(AEHLT, AESOC, external_dictionary_type=\"meddra\")",
                "not empty(AE.AETERM)");
        String error = String.valueOf(rule.getLoadError());
        assertTrue(error.contains("valid_external_dictionary_hierarchy"), error);
        assertTrue(error.contains("Match_Datasets"), error);
        assertTrue(error.contains("'AE'"), error);
        assertTrue(error.contains("Filter"), error);
    }


    @Test
    void aDecodeCallInAFilterIsALoadError() throws Exception
    {
        Rule rule = load("dictionary_has_decode(AEDECOD, external_dictionary_type=\"meddra\")",
                "not empty(AE.AETERM)");
        String error = String.valueOf(rule.getLoadError());
        assertTrue(error.contains("dictionary_has_decode"), error);
        assertTrue(error.contains("Filter"), error);
    }


    /** The control: the same call in a Check level still loads (the Check has every skip arm). */
    @Test
    void theSameCallInTheCheckStillLoads() throws Exception
    {
        Rule rule = load("not empty(AETERM)",
                "not valid_external_dictionary_hierarchy(AE.AEHLT, AE.AESOC,"
                        + " external_dictionary_type=\"meddra\")");
        assertNull(rule.getLoadError(), rule.getLoadError());
        Rule plain = load(null,
                "not dictionary_has_decode(AEDECOD," + " external_dictionary_type=\"meddra\")");
        assertNull(plain.getLoadError(), plain.getLoadError());
    }
}
