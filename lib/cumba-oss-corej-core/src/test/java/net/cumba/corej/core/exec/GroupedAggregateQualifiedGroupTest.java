package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * C2 of {@code PLAN-rprfdy-offset-tp-join}: a <b>qualified</b> member of a grouped function's
 * {@code group=}.
 *
 * <p>
 * Owner, 2026-09-29: <i>"I would like to have the possibility to use qualified variables in a group
 * key. I don't want this group=[{left: DM.RPATHCD, right: RPATHCD}, RPHASE] but only a single
 * group=[DM.RPATHCD, RPHASE] as it is clear that DM.RPATHCD joins to RPATHCD."</i>
 * </p>
 *
 * <p>
 * {@code read_value(RPRFDY, domain="TP", group=[DM.RPATHCD, RPHASE], mode="MAX")} groups TP by its
 * own {@code [RPATHCD, RPHASE]} and keys each record of the evaluated dataset by the
 * {@code RPATHCD} of its bound DM record and its own {@code RPHASE}. Every fixture carries two
 * paths sharing one {@code RPHASE} value, so the EC-44 trap — the grouped side dropping the absent
 * {@code DM.RPATHCD} and grouping TP by {@code RPHASE} alone, a MAX over ALL paths — reds
 * distinguishably (§2.1, phase 2).
 * </p>
 */
class GroupedAggregateQualifiedGroupTest
{

    private static IDataTable bw(String... usubjidPhase)
    {
        String[] usubjid = new String[usubjidPhase.length];
        String[] phase = new String[usubjidPhase.length];
        String[] seq = new String[usubjidPhase.length];
        for (int i = 0; i < usubjidPhase.length; i++)
        {
            String[] parts = usubjidPhase[i].split("/", -1);
            usubjid[i] = parts[0];
            phase[i] = parts[1];
            seq[i] = String.valueOf(i + 1);
        }
        return MockTable.of().name("BW").col("USUBJID", usubjid).col("BWSEQ", seq)
                .col("RPHASE", phase).build();
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
        return MockTable.of().name("DM").col("USUBJID", usubjid).col("RPATHCD", path).build();
    }


    /** TP rows {@code RPATHCD/TPSTGORD/RPHASE/RPRFDY/TPDTC} — numeric {@code RPRFDY}. */
    private static IDataTable tp(String... rows)
    {
        String[] path = new String[rows.length];
        String[] ord = new String[rows.length];
        String[] phase = new String[rows.length];
        Long[] rprfdy = new Long[rows.length];
        String[] dtc = new String[rows.length];
        for (int i = 0; i < rows.length; i++)
        {
            String[] parts = rows[i].split("/", -1);
            path[i] = parts[0];
            ord[i] = parts[1];
            phase[i] = parts[2];
            rprfdy[i] = parts[3].isEmpty() ? null : Long.valueOf(parts[3]);
            dtc[i] = parts.length > 4 ? parts[4] : "";
        }
        return MockTable.of().name("TP").col("RPATHCD", path).col("TPSTGORD", ord)
                .col("RPHASE", phase).colLong("RPRFDY", rprfdy).col("TPDTC", dtc).build();
    }

    private static final String DM_ENTRY = "[{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"]}]";

    private static final String DM_REQUIREMENTS = "{\"All\":[\"USUBJID\"],"
            + "\"All_Or_None\":[[\"USUBJID\",\"DM.USUBJID\"]]}";

    private static String ruleJson(String matchDatasets, String requirements, String binding,
            String check)
    {
        return "{\"rules\":{\"T\":{\"Core\":{\"Id\":\"T-QG\"},\"Sensitivity\":\"Record\","
                + "\"Requirements\":{\"Variables\":" + requirements + "}," + "\"Match_Datasets\":"
                + matchDatasets + "," + "\"Bindings\":[{\"name\":\"$v\",\"expression\":\""
                + binding.replace("\"", "\\\"") + "\"}]," + "\"Check\":{\"expression\":\""
                + check.replace("\"", "\\\"") + "\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\",\"$v\"]}}}}";
    }


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


    private static Rule loadClean(String binding, String check)
    {
        Rule rule = load(ruleJson(DM_ENTRY, DM_REQUIREMENTS, binding, check));
        assertNull(rule.getLoadError(), rule.getLoadError());
        return rule;
    }


    private static String loadError(String matchDatasets, String requirements, String binding)
    {
        String error = load(ruleJson(matchDatasets, requirements, binding, "not empty($v)"))
                .getLoadError();
        assertNotNull(error, "expected a load error");
        return error;
    }


    private static RuleExecutionResult run(Rule rule, IDataTable primary, IDataTable... others)
    {
        IDataTable[] all = new IDataTable[others.length + 1];
        System.arraycopy(others, 0, all, 0, others.length);
        all[others.length] = primary;
        return RuleRunnerCalls.execute(rule, primary, RealTables.inventoryOf(all));
    }


    /** The firing subjects, sorted, with the reported {@code $v}. */
    private static List<String> fired(RuleExecutionResult res)
    {
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus(), res.getStatusMessage());
        List<String> out = new ArrayList<>();
        for (Violation v : res.getViolations())
        {
            out.add(v.getValues().get("USUBJID") + "=" + v.getValues().get("$v"));
        }
        out.sort(null);
        return out;
    }

    private static final String READ_MAX = "read_value(RPRFDY, domain=\"TP\","
            + " group=[DM.RPATHCD, RPHASE], mode=\"MAX\")";

    // -----------------------------------------------------------------------
    // Each grouped function keys the record side through the join
    // -----------------------------------------------------------------------

    /**
     * ⛔ The EC-44 trap: paths A and B share phase P1. Each record reads ITS path's phase value — S1
     * (A) 0, S2 (B) 1. Grouping TP by RPHASE alone would answer the MAX over both paths, 1, for
     * every record.
     */
    @Test
    void readValueKeysEachRecordByItsOwnPath()
    {
        Rule rule = loadClean(READ_MAX, "$v == 1");
        RuleExecutionResult res = run(rule, bw("S1/P1", "S2/P1"), dm("S1/A", "S2/B"),
                tp("A/1/P1/0", "A/2/P1/0", "B/1/P1/1"));
        assertEquals(List.of("S2=1"), fired(res));
    }


    @Test
    void maxKeysEachRecordByItsOwnPath()
    {
        Rule rule = loadClean("max(RPRFDY, domain=\"TP\", group=[DM.RPATHCD, RPHASE])", "$v == 1");
        RuleExecutionResult res = run(rule, bw("S1/P1", "S2/P1"), dm("S1/A", "S2/B"),
                tp("A/1/P1/0", "A/2/P1/0", "B/1/P1/1"));
        assertEquals(List.of("S2=1"), fired(res));
    }


    @Test
    void minDateKeysEachRecordByItsOwnPath()
    {
        Rule rule = loadClean("min_date(TPDTC, domain=\"TP\", group=[DM.RPATHCD, RPHASE])",
                "$v == \"2020-02-01\"");
        RuleExecutionResult res = run(rule, bw("S1/P1", "S2/P1"), dm("S1/A", "S2/B"),
                tp("A/1/P1/0/2020-01-05", "A/2/P1/0/2020-01-01", "B/1/P1/1/2020-02-01"));
        assertEquals(List.of("S2=2020-02-01"), fired(res));
    }


    @Test
    void recordCountKeysEachRecordByItsOwnPath()
    {
        Rule rule = loadClean("record_count(domain=\"TP\", group=[DM.RPATHCD, RPHASE])", "$v == 2");
        RuleExecutionResult res = run(rule, bw("S1/P1", "S2/P1"), dm("S1/A", "S2/B"),
                tp("A/1/P1/0", "A/2/P1/0", "B/1/P1/1"));
        // Path A has two stages in P1, path B one; grouped by RPHASE alone both would read 3.
        assertEquals(List.of("S1=2"), fired(res));
    }


    @Test
    void distinctKeysEachRecordByItsOwnPath()
    {
        Rule rule = loadClean("distinct(TPSTGORD, domain=\"TP\", group=[DM.RPATHCD, RPHASE])",
                "\"2\" in $v");
        RuleExecutionResult res = run(rule, bw("S1/P1", "S2/P1"), dm("S1/A", "S2/B"),
                tp("A/1/P1/0", "A/2/P1/0", "B/1/P1/1"));
        // Only path A has a stage 2; grouped by RPHASE alone both records would see it.
        assertEquals(1, res.getViolations().size(), res.getViolations().toString());
        assertEquals("S1", res.getViolations().get(0).getValues().get("USUBJID"));
    }


    /** A plain member beside the qualified one still keys: a stage in another phase is ignored. */
    @Test
    void thePlainMemberStillKeysBesideTheQualifiedOne()
    {
        Rule rule = loadClean(READ_MAX, "$v == 1");
        RuleExecutionResult res = run(rule, bw("S1/P1", "S1/P2"), dm("S1/A"),
                tp("A/1/P1/0", "A/2/P2/1"));
        assertEquals(List.of("S1=1"), fired(res));
    }

    // -----------------------------------------------------------------------
    // D-COPIES, a blank source value, the no-group answer
    // -----------------------------------------------------------------------


    /** D-COPIES: DM duplicating a USUBJID on two paths makes two copies, each reading its path. */
    @Test
    void eachCopyOfARecordReadsItsOwnPathsGroup()
    {
        Rule rule = loadClean(READ_MAX, "not empty($v)");
        RuleExecutionResult res = run(rule, bw("S1/P1"), dm("S1/A", "S1/B"),
                tp("A/1/P1/0", "B/1/P1/1"));
        assertEquals(List.of("S1=0", "S1=1"), fired(res));
    }


    /** §6.2 case 5: a blank DM.RPATHCD keys "" — no TP stage carries it, so the read is missing. */
    @Test
    void aBlankSourceValueReadsTheNoGroupAnswerWhenTpHasNoBlankPath()
    {
        Rule rule = loadClean(READ_MAX, "empty($v)");
        RuleExecutionResult res = run(rule, bw("S1/P1", "S2/P1"), dm("S1/", "S2/B"),
                tp("A/1/P1/0", "B/1/P1/1"));
        assertEquals(List.of("S1=."), fired(res)); // the missing reports its marker
    }


    /** §6.2 case 5, the nonconformant-TP arm: a blank-RPATHCD stage IS the blank key's group. */
    @Test
    void aBlankSourceValueReadsABlankPathsStageWhenTpCarriesOne()
    {
        Rule rule = loadClean(READ_MAX, "$v == 0");
        RuleExecutionResult res = run(rule, bw("S1/P1", "S2/P1"), dm("S1/", "S2/B"),
                tp("/1/P1/0", "B/1/P1/1"));
        assertEquals(List.of("S1=0"), fired(res));
    }


    /** A record whose (path, phase) has no TP row reads the no-group answer, not an error. */
    @Test
    void aRecordWithoutAGroupReadsTheNoGroupAnswer()
    {
        Rule rule = loadClean(READ_MAX, "empty($v)");
        RuleExecutionResult res = run(rule, bw("S1/P1", "S2/P1"), dm("S1/A", "S2/B"),
                tp("A/1/P1/0"));
        assertEquals(List.of("S2=."), fired(res)); // the missing reports its marker
    }

    // -----------------------------------------------------------------------
    // GKI Q2 against the SOURCE column, D-ABSENT
    // -----------------------------------------------------------------------


    /** GKI Q2: the kind compared with TP's is DM.RPATHCD's, not the evaluated table's. */
    @Test
    void aKindMismatchBetweenTheSourceAndTheGroupedColumnErrors()
    {
        Rule rule = loadClean(READ_MAX, "$v == 1");
        IDataTable numericTp = MockTable.of().name("TP").colLong("RPATHCD", 1L).col("RPHASE", "P1")
                .colLong("RPRFDY", 0L).build();
        RuleExecutionResult res = run(rule, bw("S1/P1"), dm("S1/1"), numericTp);
        assertEquals(RuleExecutionStatus.ERROR, res.getStatus(), res.getStatusMessage());
        String msg = String.valueOf(res.getStatusMessage());
        assertTrue(msg.contains("RPATHCD is Numeric in TP"), msg);
        assertTrue(msg.contains("Character in DM"), msg);
    }


    private static String errorMessage(RuleExecutionResult res)
    {
        assertEquals(RuleExecutionStatus.ERROR, res.getStatus(), res.getStatusMessage());
        String msg = String.valueOf(res.getStatusMessage());
        assertTrue(msg.contains("qualified key DM.RPATHCD"), msg);
        assertEquals(msg, res.getViolations().get(0).getValues().get("__error__"));
        return msg;
    }


    /**
     * The source entry's dataset absent from the study: through a loaded rule the join-key
     * declaration ({@code [USUBJID, DM.USUBJID]}) SKIPS it first (JKM R7's authoring gate, Q8), so
     * the ERROR is pinned at the keyer — a context carrying no DM lookup.
     */
    @Test
    void theSourceDatasetAbsentFromTheStudyErrors()
    {
        Rule rule = loadClean(READ_MAX, "$v == 1");
        RuleExecutionResult skipped = run(rule, bw("S1/P1"), tp("A/1/P1/0"));
        assertEquals(RuleExecutionStatus.SKIPPED, skipped.getStatus(), skipped.getStatusMessage());

        EvaluationContext noJoin = EvaluationContext.builder().table(bw("S1/P1")).build();
        UnresolvedQualifiedKeyException ex = org.junit.jupiter.api.Assertions
                .assertThrows(UnresolvedQualifiedKeyException.class, () -> GroupedAggregate
                        .recordKeyer(noJoin, "read_value", List.of("DM.RPATHCD", "RPHASE")));
        assertTrue(ex.getMessage().contains("no DM record is bound"), ex.getMessage());
    }


    @Test
    void theSourceColumnAbsentFromTheSourceDatasetErrors()
    {
        Rule rule = loadClean(READ_MAX, "$v == 1");
        IDataTable dmWithoutPath = MockTable.of().name("DM").col("USUBJID", "S1").build();
        String msg = errorMessage(run(rule, bw("S1/P1"), dmWithoutPath, tp("A/1/P1/0")));
        assertTrue(msg.contains("DM has no column RPATHCD"), msg);
    }


    /** The unqualified variable absent from the grouped dataset is an ERROR — not EC-44's drop. */
    @Test
    void theUnqualifiedVariableAbsentFromTheGroupedDatasetErrors()
    {
        Rule rule = loadClean(READ_MAX, "$v == 1");
        IDataTable tpWithoutPath = MockTable.of().name("TP").col("RPHASE", "P1")
                .colLong("RPRFDY", 1L).build();
        String msg = errorMessage(run(rule, bw("S1/P1"), dm("S1/A"), tpWithoutPath));
        assertTrue(msg.contains("TP has no column RPATHCD"), msg);
    }

    // -----------------------------------------------------------------------
    // The loader — D-DOMAIN, D-REGEX, `--`, an undeclared qualifier, D-SRC
    // -----------------------------------------------------------------------


    @Test
    void aQualifiedMemberRequiresDomain()
    {
        String error = loadError(DM_ENTRY, DM_REQUIREMENTS,
                "max(BWSEQ, group=[DM.RPATHCD, RPHASE])");
        assertTrue(error.contains("DM.RPATHCD") && error.contains("domain="), error);
    }


    @Test
    void aQualifiedMemberWithRegexIsRefused()
    {
        String error = loadError(DM_ENTRY, DM_REQUIREMENTS,
                "record_count(domain=\"TP\", group=[DM.RPATHCD, RPHASE], regex=\"^(.*)$\")");
        assertTrue(error.contains("DM.RPATHCD") && error.contains("regex"), error);
    }


    @Test
    void aDomainWildcardInsideAQualifiedMemberIsRefused()
    {
        String error = loadError(DM_ENTRY, DM_REQUIREMENTS,
                "read_value(RPRFDY, domain=\"TP\", group=[DM.--SEQ, RPHASE], mode=\"MAX\")");
        assertTrue(error.contains("DM.--SEQ"), error);
    }


    /**
     * An undeclared qualifier is a load error of the loader's own gate: Stage A's
     * {@code DOTTED_REF_UNDECLARED} reaches a {@code group=} list member but is an UNARMED kind
     * (reported, never parking — measured 2026-09-30, phase 2 red-before: no load error at all).
     */
    @Test
    void anUndeclaredQualifierIsALoadError()
    {
        String error = loadError("[]", "{\"All\":[\"USUBJID\"]}", READ_MAX);
        assertTrue(error.contains("group= member DM.RPATHCD names no Match_Datasets entry DM"),
                error);
    }


    @Test
    void theSourceEntryMustBeAnOrdinaryInnerJoin()
    {
        String left = loadError("[{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"],\"Join_Type\":\"left\"}]",
                DM_REQUIREMENTS, READ_MAX);
        assertTrue(left.contains("names entry DM with Join_Type left"), left);
        String child = loadError(
                "[{\"Name\":\"SUPPBW\",\"Child\":true,\"Keys\":[\"USUBJID\",\"IDVAR\","
                        + "\"IDVARVAL\"]}]",
                "{\"All\":[\"USUBJID\",\"IDVAR\",\"IDVARVAL\"]}",
                "read_value(RPRFDY, domain=\"TP\", group=[SUPPBW.RPATHCD, RPHASE], mode=\"MAX\")");
        assertTrue(child.contains("names entry SUPPBW, which is not an ordinary keyed join"),
                child);
    }


    /**
     * A compiler-dispatched group operator has no other side: a dotted {@code within=} is refused.
     */
    @Test
    void aDottedWithinMemberOfAGroupOperatorIsRefused()
    {
        String error = loadError(DM_ENTRY, DM_REQUIREMENTS,
                "has_multiple_values_for(BWSEQ, RPHASE, within=[DM.RPATHCD])");
        assertTrue(error.contains("group operator operand must be a plain column"), error);
    }

}
