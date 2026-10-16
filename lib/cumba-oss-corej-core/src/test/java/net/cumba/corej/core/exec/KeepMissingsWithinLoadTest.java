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
 * {@code PLAN-w4-parked-engine-refusals} KM-S (C4): the same refusal on {@code is_sorted_by}, whose
 * ordering verdict reads the disposition only through its {@code within=} key.
 */
class KeepMissingsWithinLoadTest
{

    private static void assertKm(String error)
    {
        assertKm("has_multiple_values_for", error);
    }


    private static void assertKm(String function, String error)
    {
        assertTrue(error.contains("`keep_missings` on " + function + " requires `within=`"), error);
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


    /**
     * W4 fold (R5 P1-L2): an empty {@code within=[]} groups by nothing — the ungrouped arm runs and
     * never reads the disposition — so it meets the same refusal as no {@code within=} (the
     * non-empty check {@code is_last_in_group}'s {@code group=} has). Without
     * {@code keep_missings=} it loads as before.
     */
    @Test
    void keepMissingsWithAnEmptyWithinIsTheSameLoadError()
    {
        assertKm(loadError(
                "has_multiple_values_for(AEDECOD, AETERM, within=[], keep_missings=true)"));
        Rule checkless = SequenceFunctionTest.loadCheckless("$b",
                "has_multiple_values_for(AEDECOD, AETERM, within=[], keep_missings=false)");
        assertKm(String.valueOf(checkless.getLoadError()));
        assertEquals("null", loadError("has_multiple_values_for(AEDECOD, AETERM, within=[])"));
    }


    @Test
    void keepMissingsWithWithinLoadsAsBefore()
    {
        assertEquals("null", loadError(
                "has_multiple_values_for(AEDECOD, AETERM, within=USUBJID, keep_missings=true)"));
        assertEquals("null", loadError("has_multiple_values_for(AEDECOD, AETERM)"));
        assertEquals("null", loadError("has_multiple_values_for(AEDECOD, AETERM, within=USUBJID)"));
    }


    /**
     * {@code PLAN-w4-parked-engine-refusals} KM-S (C4): {@code is_sorted_by} reads the
     * {@code keep_missings=} disposition only through its {@code within=} key — without one every
     * row is in one group and the argument has no effect, so it is the rule's load error in a
     * Check, a binding and a {@code Match_Datasets} Filter.
     */
    @Test
    void keepMissingsOnIsSortedByWithoutWithinIsALoadError() throws Exception
    {
        assertKm("is_sorted_by",
                loadError("not is_sorted_by(AESEQ, by=[asc(AESTDTC)], keep_missings=false)"));
        assertKm("is_sorted_by",
                loadError("is_sorted_by(AESEQ, by=[asc(AESTDTC)], keep_missings=true)"));
        assertKm("is_sorted_by", loadError("$b", "$b",
                "not is_sorted_by(AESEQ, by=[asc(AESTDTC)], keep_missings=false)"));
        assertKm("is_sorted_by", String.valueOf(
                loadWithFilter("not is_sorted_by(ARMCD, by=[asc(ARM)], keep_missings=false)")
                        .getLoadError()));
    }


    /**
     * KM-S (T1): {@code within=[]} alone is already refused ({@code StrictColumnOperandTest}); with
     * {@code keep_missings=} the loader's shape seam runs before the compile probe, so the KM-S
     * message — the one naming the argument to fix — wins, in a Check and in a Filter.
     */
    @Test
    void keepMissingsOnIsSortedByWithAnEmptyWithinNamesKeepMissings() throws Exception
    {
        assertKm("is_sorted_by", loadError(
                "not is_sorted_by(AESEQ, by=[asc(AESTDTC)], within=[], keep_missings=false)"));
        assertKm("is_sorted_by",
                String.valueOf(loadWithFilter(
                        "not is_sorted_by(ARMCD, by=[asc(ARM)], within=[], keep_missings=false)")
                                .getLoadError()));
    }


    /** KM-S control: with {@code within=} the call loads, in a Check and in a Filter. */
    @Test
    void keepMissingsOnIsSortedByWithWithinLoads() throws Exception
    {
        assertEquals("null", loadError(
                "not is_sorted_by(AESEQ, by=[asc(AESTDTC)], within=USUBJID, keep_missings=false)"));
        assertEquals(null, loadWithFilter(
                "not is_sorted_by(ARMCD, by=[asc(ARM)], within=USUBJID, keep_missings=false)")
                        .getLoadError());
    }
}
