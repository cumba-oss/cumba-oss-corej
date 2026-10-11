package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * C3 of {@code PLAN-rprfdy-offset-tp-join}: a <b>qualified</b> member of the rule-level
 * {@code Grouping} — owner 2026-09-29: <i>"This requires support for qualified variables at the
 * global rule group keys (a third point)"</i>.
 *
 * <p>
 * {@code Grouping: {Variables: [DM.RPATHCD, RPHASE]}} partitions the evaluated (expanded) rows by
 * the {@code RPATHCD} of each row's bound DM record and its own {@code RPHASE}. ⛔ The silent-drop
 * trap (§2.1, phase 3): today's {@code presentGroupVars} filter keeps only names the evaluated
 * table carries, so {@code DM.RPATHCD} was dropped without a word and the dataset collapsed into
 * one group per {@code RPHASE}. Every fixture carries two paths sharing one phase, so that arm reds
 * as ONE finding where two are owed.
 * </p>
 */
class RuleRunnerQualifiedGroupingTest
{

    /** BW rows {@code USUBJID/RPHASE/VAL}. */
    private static IDataTable bw(String... rows)
    {
        String[] usubjid = new String[rows.length];
        String[] phase = new String[rows.length];
        String[] val = new String[rows.length];
        String[] seq = new String[rows.length];
        for (int i = 0; i < rows.length; i++)
        {
            String[] parts = rows[i].split("/", -1);
            usubjid[i] = parts[0];
            phase[i] = parts[1];
            val[i] = parts[2];
            seq[i] = String.valueOf(i + 1);
        }
        return RealTables.of("BW").str("USUBJID", usubjid).str("BWSEQ", seq).str("RPHASE", phase)
                .str("VAL", val).build();
    }


    private static IDataTable dm(String... usubjidPath)
    {
        String[] usubjid = new String[usubjidPath.length];
        String[] path = new String[usubjidPath.length];
        for (int i = 0; i < usubjidPath.length; i++)
        {
            String[] parts = usubjidPath[i].split("/", -1);
            usubjid[i] = parts[0];
            path[i] = parts[1];
        }
        return RealTables.of("DM").str("USUBJID", usubjid).str("RPATHCD", path).build();
    }

    private static final String BLOCK_GROUPING = "\"Grouping\":{\"Variables\":[\"DM.RPATHCD\","
            + "\"RPHASE\"]}";

    private static final String FLAT_GROUPING = "\"Grouping_Variables\":[\"DM.RPATHCD\","
            + "\"RPHASE\"]";

    private static final String DM_ENTRY = "[{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"]}]";

    private static final String DM_REQUIREMENTS = "{\"All\":[\"USUBJID\"],"
            + "\"All_Or_None\":[[\"USUBJID\",\"DM.USUBJID\"]]}";

    private static String ruleJson(String grouping, String matchDatasets, String requirements,
            String check)
    {
        return "{\"rules\":{\"T\":{\"Core\":{\"Id\":\"T-QG3\"},\"Sensitivity\":\"Group\","
                + grouping + "," + "\"Requirements\":{\"Variables\":" + requirements + "},"
                + "\"Match_Datasets\":" + matchDatasets + "," + "\"Check\":" + check + ","
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\",\"VAL\"]}}}}";
    }

    private static final String BAD = "{\"expression\":\"VAL == \\\"BAD\\\"\"}";

    private static Rule load(String json)
    {
        try
        {
            return RulePackageLoader.loadFromString(json).getRules().get("T");
        }
        catch (java.io.IOException e)
        {
            throw new IllegalArgumentException(e);
        }
    }


    private static Rule loadClean(String grouping)
    {
        Rule rule = load(ruleJson(grouping, DM_ENTRY, DM_REQUIREMENTS, BAD));
        assertNull(rule.getLoadError(), rule.getLoadError());
        return rule;
    }


    private static RuleExecutionResult run(Rule rule, IDataTable primary, IDataTable... others)
    {
        IDataTable[] all = new IDataTable[others.length + 1];
        System.arraycopy(others, 0, all, 0, others.length);
        all[others.length] = primary;
        return RuleRunnerCalls.execute(rule, primary, RealTables.inventoryOf(all));
    }


    /** The anchoring subjects of the group findings, in report order. */
    private static List<String> anchors(RuleExecutionResult res)
    {
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus(), res.getStatusMessage());
        List<String> out = new ArrayList<>();
        for (Violation v : res.getViolations())
        {
            // The anchor row's own subject — the USUBJID OUTPUT renders the group's distinct set
            // (D94c), which is the group, not the anchor.
            out.add(v.getUsubjid());
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // The grouping, the report key and the level unit
    // -----------------------------------------------------------------------


    /** ⛔ The silent-drop trap: two paths in one phase are TWO groups, two findings. */
    @Test
    void groupsByTheJoinedValue()
    {
        RuleExecutionResult res = run(loadClean(BLOCK_GROUPING),
                bw("S1/P1/BAD", "S2/P1/BAD", "S3/P1/BAD"), dm("S1/A", "S2/B", "S3/A"));
        // Group (A, P1) = S1 + S3, anchored at S1; group (B, P1) = S2.
        assertEquals(List.of("S1", "S2"), anchors(res));
    }


    @Test
    void theReportKeyCarriesTheQualifiedValue()
    {
        RuleExecutionResult res = run(loadClean(BLOCK_GROUPING), bw("S1/P1/BAD", "S2/P1/BAD"),
                dm("S1/A", "S2/B"));
        assertEquals(List.of("S1", "S2"), anchors(res));
        assertEquals(Map.of("DM.RPATHCD", "A", "RPHASE", "P1"),
                res.getViolations().get(0).getGroupKey());
        assertEquals(Map.of("DM.RPATHCD", "B", "RPHASE", "P1"),
                res.getViolations().get(1).getGroupKey());
    }


    /** The level plan's unit (multi-level Check) carries the qualified value: two units. */
    @Test
    void theLevelUnitCarriesTheQualifiedValue()
    {
        String levels = "{\"ERROR\":{\"expression\":\"VAL == \\\"BAD\\\"\"},"
                + "\"INFO\":{\"expression\":\"VAL == \\\"MEH\\\" or VAL == \\\"BAD\\\"\","
                + "\"Message\":\"weaker\"}}";
        Rule rule = load(ruleJson(BLOCK_GROUPING, DM_ENTRY, DM_REQUIREMENTS, levels));
        assertNull(rule.getLoadError(), rule.getLoadError());
        RuleExecutionResult res = run(rule, bw("S1/P1/BAD", "S2/P1/BAD"), dm("S1/A", "S2/B"));
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus(), res.getStatusMessage());
        assertEquals(2, res.getViolations().size(), res.getViolations().toString());
        Violation.Unit first = res.getViolations().get(0).getUnit();
        Violation.Unit second = res.getViolations().get(1).getUnit();
        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first, second, "two path groups are two units");
        assertTrue(first.toString().contains("A") && second.toString().contains("B"),
                first + " / " + second);
    }


    /** A plain member beside the qualified one still keys: one phase apart is another group. */
    @Test
    void thePlainMemberStillKeysBesideTheQualifiedOne()
    {
        RuleExecutionResult res = run(loadClean(BLOCK_GROUPING),
                bw("S1/P1/BAD", "S1/P2/BAD", "S2/P1/BAD"), dm("S1/A", "S2/A"));
        // (A, P1) = rows 0 + 2 anchored at S1; (A, P2) = row 1.
        assertEquals(2, res.getViolations().size(), res.getViolations().toString());
        assertEquals(Map.of("DM.RPATHCD", "A", "RPHASE", "P2"),
                res.getViolations().get(1).getGroupKey());
    }


    /** EC-44 stays the PLAIN members' rule: an absent plain column partitions nothing. */
    @Test
    void anAbsentPlainMemberIsStillDropped()
    {
        Rule rule = loadClean("\"Grouping\":{\"Variables\":[\"DM.RPATHCD\",\"NOSUCH\"]}");
        RuleExecutionResult res = run(rule, bw("S1/P1/BAD", "S2/P2/BAD"), dm("S1/A", "S2/B"));
        assertEquals(List.of("S1", "S2"), anchors(res));
        assertEquals(Map.of("DM.RPATHCD", "A"), res.getViolations().get(0).getGroupKey());
    }


    /**
     * D-COPIES under C3 (review round 1, lane 2 L4): DM carries S1 TWICE, on paths A and B, so the
     * DM join makes two copies of the one BW record, and each copy is grouped by ITS bound DM
     * record — two groups, two findings, each anchored on its own copy of the same primary row.
     */
    @Test
    void eachCopyOfARecordGroupsByItsOwnBoundSourceRecord()
    {
        RuleExecutionResult res = run(loadClean(BLOCK_GROUPING), bw("S1/P1/BAD"),
                dm("S1/A", "S1/B"));
        assertEquals(List.of("S1", "S1"), anchors(res));
        assertEquals(Map.of("DM.RPATHCD", "A", "RPHASE", "P1"),
                res.getViolations().get(0).getGroupKey());
        assertEquals(Map.of("DM.RPATHCD", "B", "RPHASE", "P1"),
                res.getViolations().get(1).getGroupKey());
        assertEquals(1, res.getViolations().get(0).getRowNumber(), "the A copy is BW row 1");
        assertEquals(1, res.getViolations().get(1).getRowNumber(), "and so is the B copy");
    }


    @Test
    void theFlatAndTheBlockFormAgree()
    {
        IDataTable table = bw("S1/P1/BAD", "S2/P1/BAD", "S3/P1/BAD");
        IDataTable dmTable = dm("S1/A", "S2/B", "S3/A");
        assertEquals(anchors(run(loadClean(BLOCK_GROUPING), table, dmTable)),
                anchors(run(loadClean(FLAT_GROUPING), table, dmTable)));
        assertEquals(2, run(loadClean(FLAT_GROUPING), table, dmTable).getViolations().size());
    }

    // -----------------------------------------------------------------------
    // D-C3-POLICY — the qualified component is a key component like any other
    // -----------------------------------------------------------------------


    @Test
    void aBlankQualifiedKeyDropsTheGroupByDefault()
    {
        RuleExecutionResult res = run(loadClean(BLOCK_GROUPING), bw("S1/P1/BAD", "S2/P1/BAD"),
                dm("S1/A", "S2/"));
        assertEquals(List.of("S1"), anchors(res));
    }


    @Test
    void keepMissingsKeepsTheBlankAndTheMissingQualifiedKeysAsDistinctGroups()
    {
        Rule rule = loadClean("\"Grouping\":{\"Variables\":[\"DM.RPATHCD\",\"RPHASE\"],"
                + "\"keep_missings\":true}");
        // A real STRING buffer reads a null cell as the character MIS (DataBufferString, the
        // 2026-09-18 owner ruling), so the fixture is a real table: the merged view (W3a) copies
        // column metadata, which the Mockito-backed MockTable.colSasMissing could not give.
        IDataTable dmWithMissing = RealTables.of("DM").str("USUBJID", "S1", "S2", "S3")
                .str("RPATHCD", "A", "", null).build();
        RuleExecutionResult res = run(rule, bw("S1/P1/BAD", "S2/P1/BAD", "S3/P1/BAD"),
                dmWithMissing);
        // "" and MIS are distinct groups (D11), each its own finding.
        assertEquals(List.of("S1", "S2", "S3"), anchors(res));
        assertEquals("", res.getViolations().get(1).getGroupKey().get("DM.RPATHCD"));
        assertEquals(".", res.getViolations().get(2).getGroupKey().get("DM.RPATHCD"));
    }

    // -----------------------------------------------------------------------
    // An absent member (EC-44, QK4), the declaration, the loader
    // -----------------------------------------------------------------------


    /**
     * {@code REG-Q4} QK4 (owner 2026-10-09): a qualified {@code Grouping} member whose source
     * column is absent is dropped exactly as an absent bare member ({@code EC-44}) — the records
     * group by RPHASE alone, one group, one finding. It used to ERROR (D-ABSENT) on a path of its
     * own; the blocks are now visited in the index order the plain path uses (plan §5 Q9).
     */
    @Test
    void theSourceColumnAbsentFromTheSourceDatasetDropsTheMember()
    {
        IDataTable dmWithoutPath = RealTables.of("DM").str("USUBJID", "S1", "S2").build();
        RuleExecutionResult res = run(loadClean(BLOCK_GROUPING), bw("S1/P1/BAD", "S2/P1/BAD"),
                dmWithoutPath);
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus(), res.getStatusMessage());
        assertEquals(List.of("S1"), anchors(res),
                "one group (RPHASE P1), anchored at its first flagged record");
    }


    /** The source dataset absent: the join-key declaration SKIPS first (JKM R7's gate, Q8). */
    @Test
    void theSourceDatasetAbsentSkipsThroughTheDeclaration()
    {
        RuleExecutionResult res = run(loadClean(BLOCK_GROUPING), bw("S1/P1/BAD"));
        assertEquals(RuleExecutionStatus.SKIPPED, res.getStatus(), res.getStatusMessage());
    }


    private static String loadError(String grouping, String matchDatasets, String requirements)
    {
        String error = load(ruleJson(grouping, matchDatasets, requirements, BAD)).getLoadError();
        assertNotNull(error, "expected a load error");
        return error;
    }


    /**
     * The qualifier must name a DECLARED entry (an undeclared one is a name that resolves nowhere);
     * since {@code REG-Q4} QK10 any declared entry is a source — a {@code left} one loads (it was
     * refused: D-SRC's ordinary / {@code inner} half).
     */
    @Test
    void theQualifierMustNameADeclaredEntryOfAnyKind()
    {
        String undeclared = loadError(BLOCK_GROUPING, "[]", "{\"All\":[\"USUBJID\"]}");
        assertTrue(
                undeclared.contains(
                        "Grouping member DM.RPATHCD names no Match_Datasets entry" + " DM"),
                undeclared);
        assertNull(load(ruleJson(BLOCK_GROUPING,
                "[{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"],\"Join_Type\":\"left\"}]",
                DM_REQUIREMENTS, BAD)).getLoadError());
    }


    @Test
    void aDomainWildcardInsideAQualifiedMemberIsRefused()
    {
        String error = loadError("\"Grouping\":{\"Variables\":[\"DM.--SEQ\",\"RPHASE\"]}", DM_ENTRY,
                DM_REQUIREMENTS);
        assertTrue(error.contains("Grouping member DM.--SEQ carries a -- / & wildcard"), error);
    }

}
