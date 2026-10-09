package net.cumba.corej.core.exec;

import static net.cumba.corej.core.exec.SequenceFunctionTest.loadError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import org.junit.jupiter.api.Test;

/**
 * Runbook W4 KM (§3 table B; coordinator W4-D4): {@code keep_missings=} on
 * {@code has_multiple_values_for} without {@code within=} is the rule's load error — with no
 * grouping key the disposition has no effect (the ungrouped arm never reads it), and PACK 6's
 * doctrine refuses a silently ignored argument. With {@code within=} it loads as before.
 */
class KeepMissingsWithinLoadTest
{

    private static void assertKm(String error)
    {
        assertTrue(error.contains("`keep_missings` on has_multiple_values_for requires `within=`"),
                error);
        assertTrue(error.contains("it would have no effect"), error);
    }


    @Test
    void keepMissingsWithoutWithinIsALoadErrorOnEveryPath()
    {
        assertKm(loadError("has_multiple_values_for(AEDECOD, AETERM, keep_missings=true)"));
        assertKm(loadError("not has_multiple_values_for(AEDECOD, AETERM, keep_missings=false)"));
        assertKm(loadError("$b", "$b",
                "has_multiple_values_for(AEDECOD, AETERM, keep_missings=true)"));
        Rule checkless = SequenceFunctionTest.loadCheckless("$b",
                "has_multiple_values_for(AEDECOD, AETERM, keep_missings=true)");
        assertKm(String.valueOf(checkless.getLoadError()));
    }


    /**
     * A {@code Match_Datasets} Filter is compiled at run time and never meets the inline Check
     * seams; the KM refusal reaches it at load all the same.
     */
    @Test
    void keepMissingsWithoutWithinIsALoadErrorInAMatchDatasetsFilter() throws Exception
    {
        assertKm(String.valueOf(
                loadWithFilter("not has_multiple_values_for(ARM, ARMCD, keep_missings=true)")
                        .getLoadError()));
        assertEquals(null, loadWithFilter(
                "not has_multiple_values_for(ARM, ARMCD, within=USUBJID, keep_missings=true)")
                        .getLoadError());
    }


    private static Rule loadWithFilter(String filter) throws Exception
    {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("Core", Map.of("Id", "CFX-KM"));
        rule.put("Check", Map.of("expression", "not empty(ARM)"));
        rule.put("Outcome", Map.of("Message", "m"));
        rule.put("Requirements", Map.of("Variables", Map.of("All", List.of("USUBJID"),
                "All_Or_None", List.of(List.of("USUBJID", "DM.USUBJID")))));
        rule.put("Match_Datasets", List.of(Map.of("Name", "DM", "Keys", List.of("USUBJID"),
                "Join_Type", "left", "Filter", filter)));
        String json = new ObjectMapper().writeValueAsString(Map.of("rules", Map.of("x", rule)));
        Rule loaded = RulePackageLoader.loadFromString(json).getRules().get("x");
        return java.util.Objects.requireNonNull(loaded);
    }


    @Test
    void keepMissingsWithWithinLoadsAsBefore()
    {
        assertEquals("null", loadError(
                "has_multiple_values_for(AEDECOD, AETERM, within=USUBJID, keep_missings=true)"));
        assertEquals("null", loadError("has_multiple_values_for(AEDECOD, AETERM)"));
        assertEquals("null", loadError("has_multiple_values_for(AEDECOD, AETERM, within=USUBJID)"));
    }
}
