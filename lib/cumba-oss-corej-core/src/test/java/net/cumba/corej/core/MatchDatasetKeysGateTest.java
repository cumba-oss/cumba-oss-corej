package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Rule;
import org.junit.jupiter.api.Test;

/**
 * The {@code Match_Datasets} unknown-key gate ({@code PLAN-match-datasets-wildcard}, T1-1 A, T1-2
 * (b), T1-3 yes): a key under a {@code Match_Datasets} entry that the model does not bind is a
 * <b>load error</b>, and the retired {@code Wildcard} is named as retired.
 *
 * <p>
 * ⭐ Why it exists: the loader's mapper runs with {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled, so
 * before this gate {@code Join_type: "left"} (one letter off) was dropped at parse and the join ran
 * as the default <b>inner</b>, silently removing the rows the author meant to keep.
 * {@code Wildcard} was the same silence in a different costume: bound, stored, and read by nothing.
 * </p>
 *
 * <p>
 * ⚑ Red-first: the three refusal cases below were run against the loader <b>before</b> the gate
 * existed and failed (recorded in the plan's status file); {@link #everyModelledKeyStillLoads()} is
 * the control without which a gate that errors on everything would pass them.
 * </p>
 */
class MatchDatasetKeysGateTest
{

    private static Rule load(String entriesJson) throws IOException
    {
        String json = """
                {"rules":{"x":{"Core":{"Id":"T-MDK"},"Sensitivity":"Record",\
                "Match_Datasets":[%s],\
                "Outcome":{"Message":"m","Output_Variables":["USUBJID"]},\
                "Check":{"all":[{"expression": "not empty(USUBJID)"}]}}}}""".formatted(entriesJson);
        Rule rule = RulePackageLoader.loadFromString(json).getRules().get("x");
        assertNotNull(rule, "the fixture must bind, or nothing below is measuring anything");
        return rule;
    }


    private static String errorOf(String entriesJson) throws IOException
    {
        String error = load(entriesJson).getLoadError();
        assertNotNull(error, "expected a load error for " + entriesJson);
        return error;
    }


    @Test
    void aMisspelledJoinTypeIsALoadError() throws IOException
    {
        // Before the gate: Join_type dropped at parse, normalizeJoinTypes stamped `inner`, and the
        // rule ran an inner join its author had written as `left`.
        String error = errorOf("{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"],\"Join_type\":\"left\"}");
        assertTrue(error.contains("[T-MDK] unknown key 'Join_type' under 'Match_Datasets[0]'"),
                error);
        assertTrue(error.contains("(entry 'DM')"), "the entry is named: " + error);
        assertTrue(error.contains("Join_Type"),
                "the message lists the bound spellings, so the fix is visible: " + error);
    }


    @Test
    void aLowerCaseWildcardIsAnOrdinaryUnknownKey() throws IOException
    {
        // Case-sensitive by design, like every other key: no special case for a near-miss of the
        // retired spelling.
        String error = errorOf("{\"Name\":\"RELREC\",\"wildcard\":\"PC\"}");
        assertTrue(error.contains("unknown key 'wildcard' under 'Match_Datasets[0]'"), error);
        assertFalse(error.contains("retired"), error);
    }


    @Test
    void anAuthoredWildcardIsARetiredKeyLoadError() throws IOException
    {
        // T1-2 RULED (b): an ERROR from the start, never a warning. The engine release carrying it
        // is held (on the PUSH) until the corpus re-released without the key is pinned in all
        // five bundles.
        Rule rule = load("{\"Name\":\"RELREC\",\"Wildcard\":\"PC\"}");
        String error = rule.getLoadError();
        assertNotNull(error, "a loadError is what makes RuleRunner report the rule as ERROR");
        assertTrue(error.contains(
                "[T-MDK] retired key 'Wildcard' under 'Match_Datasets[0]'" + " (entry 'RELREC')"),
                error);
        assertTrue(error.contains("delete the key"), error);
        assertFalse(error.contains("unknown key"),
                "the retired key is reported once, as retired: " + error);
        assertNull(rule.getLoadWarning(), "no warning phase: (a) was not ruled");
    }


    @Test
    void theIndexNamesTheOffendingEntryNotTheFirst() throws IOException
    {
        String error = errorOf("{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"]},"
                + "{\"Name\":\"AE\",\"Keys\":[\"USUBJID\"],\"filter\":\"AESER == 'Y'\"}");
        assertTrue(error.contains("unknown key 'filter' under 'Match_Datasets[1]' (entry 'AE')"),
                error);
        assertFalse(error.contains("Match_Datasets[0]"), "the good entry is not blamed: " + error);
    }


    @Test
    void everyUnknownKeyOfAnEntryIsReportedInAuthoredOrder() throws IOException
    {
        String error = errorOf("{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"],\"Keep_Missings\":false,"
                + "\"Wildcard\":\"DM\",\"JoinType\":\"left\"}");
        int keep = error.indexOf("unknown key 'Keep_Missings'");
        int wild = error.indexOf("retired key 'Wildcard'");
        int join = error.indexOf("unknown key 'JoinType'");
        assertTrue(keep >= 0 && wild > keep && join > wild, error);
    }


    @Test
    void aNamelessEntryIsNamedByItsIndexAlone() throws IOException
    {
        String error = errorOf("{\"Keys\":[\"USUBJID\"],\"Nmae\":\"DM\"}");
        assertTrue(error.contains("unknown key 'Nmae' under 'Match_Datasets[0]':"), error);
        assertFalse(error.contains("(entry "), error);
    }


    @Test
    void everyModelledKeyStillLoads() throws IOException
    {
        // ⛔ The control: all seven bound keys, well formed, on the two entry shapes that can carry
        // them together (Join_As_String / keep_missings:false are refused on a Child entry by
        // their own gates).
        Rule rule = load("{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"],\"Join_Type\":\"left\","
                + "\"Join_As_String\":true,\"keep_missings\":false,"
                + "\"Filter\":\"not empty(USUBJID)\"},"
                + "{\"Name\":\"AE\",\"Child\":true,\"Keys\":[\"USUBJID\",\"IDVAR\",\"IDVARVAL\"]}");
        assertNull(rule.getLoadError());
        List<MatchDataset> matches = rule.getMatchDatasets();
        assertNotNull(matches);
        assertEquals(2, matches.size());
        assertTrue(matches.get(0).getUnknownKeys().isEmpty());
        assertTrue(matches.get(1).getUnknownKeys().isEmpty());
    }


    @Test
    void aBareRelrecEntryStillLoads() throws IOException
    {
        // The shape the three former Wildcard carriers now author.
        assertNull(load("{\"Name\":\"RELREC\"}").getLoadError());
    }


    @Test
    void theGateAppendsToAnEarlierLoadErrorRatherThanReplacingIt() throws IOException
    {
        // Two gates of validateEnumFields on one rule: the bad Join_Type value AND the unknown key.
        String error = errorOf(
                "{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"],\"Join_Type\":\"outer\",\"Wildcard\":\"X\"}");
        assertTrue(error.contains("Join_Type"), error);
        assertTrue(error.contains("retired key 'Wildcard'"), error);
    }


    @Test
    void theUnknownKeyViewIsReadOnly()
    {
        MatchDataset md = new MatchDataset();
        assertTrue(md.getUnknownKeys().isEmpty());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> md.getUnknownKeys().add("x"));
    }
}
