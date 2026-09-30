package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * C1 of {@code PLAN-rprfdy-offset-tp-join}: a <b>qualified</b> {@code Match_Datasets} key.
 *
 * <p>
 * Owner, 2026-09-29: <i>"I would like to be able to give a qualified variable like DM.RPATHCD in
 * the join key to define that this is to be used as a key. The joined data set needs the same
 * variable without the domain qualification, so DM.RPATHCD joins on RPATHCD. This is independend of
 * the usability in the rule. I also agree that this should expand the row numbers like any other
 * join that is n-to-m."</i>
 * </p>
 *
 * <p>
 * The fixture family is the plan's own: a record dataset ({@code BW}) reaches {@code TP}'s
 * {@code RPRFDY} through {@code DM.RPATHCD} — DM is joined on {@code USUBJID} first, TP is joined
 * on {@code [DM.RPATHCD, RPHASE]}, the record side of the first component being the row's bound DM
 * record and the TP side its own {@code RPATHCD}. Every fixture carries two paths that share an
 * {@code RPHASE} value, so each wrong arm reds <b>distinguishably</b> (§5.1, round-2 M3): the JKM
 * R7 both-absent drop joins TP on {@code RPHASE} alone (every path's stages bind — MORE rows); a
 * child side left at {@code DM.RPATHCD} makes the component one-side-absent (no TP row matches —
 * ZERO rows).
 * </p>
 */
class QualifiedMatchKeyTest
{

    private static final ObjectMapper JSON = new ObjectMapper();

    private static IDataTable bw(String... usubjidPhase)
    {
        MockTable mt = MockTable.of().name("BW");
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
        return mt.col("USUBJID", usubjid).col("BWSEQ", seq).col("RPHASE", phase).build();
    }


    /** DM with a character {@code RPATHCD}; pairs are {@code USUBJID/RPATHCD}. */
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


    /** TP rows {@code RPATHCD/TPSTGORD/RPHASE/RPRFDY} — character {@code RPATHCD}. */
    private static IDataTable tp(String... rows)
    {
        String[] path = new String[rows.length];
        String[] ord = new String[rows.length];
        String[] phase = new String[rows.length];
        String[] rprfdy = new String[rows.length];
        for (int i = 0; i < rows.length; i++)
        {
            String[] parts = rows[i].split("/", -1);
            path[i] = parts[0];
            ord[i] = parts[1];
            phase[i] = parts[2];
            rprfdy[i] = parts[3];
        }
        return MockTable.of().name("TP").col("RPATHCD", path).col("TPSTGORD", ord)
                .col("RPHASE", phase).col("RPRFDY", rprfdy).build();
    }


    private static MatchDataset md(String json)
    {
        try
        {
            return JSON.readValue(json, MatchDataset.class);
        }
        catch (java.io.IOException e)
        {
            throw new IllegalArgumentException(e);
        }
    }


    private static MatchDataset dmEntry()
    {
        return md("{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"],\"Join_Type\":\"inner\"}");
    }


    private static MatchDataset tpEntry(String joinType)
    {
        return md("{\"Name\":\"TP\",\"Keys\":[\"DM.RPATHCD\",\"RPHASE\"],\"Join_Type\":\""
                + joinType + "\"}");
    }


    private static DatasetResolver resolver(IDataTable... tables)
    {
        Map<String, IDataTable> byName = new TreeMap<>();
        for (IDataTable t : tables)
        {
            byName.put(String.valueOf(t.getMetaData().getName()), t);
        }
        return byName::get;
    }


    /**
     * The expansion, described one line per expanded row as
     * {@code USUBJID:DM.RPATHCD->TP.RPATHCD/TP.TPSTGORD} — sorted, so the assertion reads as a set.
     */
    private static List<String> bindings(KeyMatchRowExpander.@Nullable KeyMatchExpansion exp)
    {
        assertNotNull(exp);
        IDataTable t = exp.table();
        JoinLookup dm = exp.lookups().get("DM");
        JoinLookup tp = exp.lookups().get("TP");
        List<String> out = new ArrayList<>();
        for (long i = 0; i < t.getRowCount(); i++)
        {
            String subject = t.getColumn(t.getMetaData().getColumnIndex("USUBJID")).getDataValue(i)
                    .getValueAsString();
            out.add(subject + ":" + dm.lookup(t, i, "RPATHCD") + "->" + (tp == null ? "-"
                    : tp.lookup(t, i, "RPATHCD") + "/" + tp.lookup(t, i, "TPSTGORD")));
        }
        out.sort(null);
        return out;
    }

    // -----------------------------------------------------------------------
    // The join itself
    // -----------------------------------------------------------------------


    /**
     * ⛔ The R7-drop trap (§2.1): both paths carry the phase {@code P1}. The chained key binds each
     * record to ITS path's stages only — S1 (path A) to A's two stages, S2 (path B) to B's one. The
     * both-absent drop would bind every record to all three (6 rows); a child side resolving
     * {@code DM.RPATHCD} would bind none (0 rows).
     */
    @Test
    void theChainedKeyBindsEachRecordToItsOwnPathsStages()
    {
        KeyMatchRowExpander.KeyMatchExpansion exp = ExecCalls.expand(bw("S1/P1", "S2/P1"),
                List.of(dmEntry(), tpEntry("inner")),
                resolver(dm("S1/A", "S2/B"), tp("A/1/P1/0", "A/2/P1/0", "B/1/P1/1")), "R");
        assertEquals(List.of("S1:A->A/1", "S1:A->A/2", "S2:B->B/1"), bindings(exp));
    }


    /** A path whose stage sits in ANOTHER phase does not bind: the plain component still keys. */
    @Test
    void thePlainComponentStillKeysBesideTheQualifiedOne()
    {
        KeyMatchRowExpander.KeyMatchExpansion exp = ExecCalls.expand(bw("S1/P1", "S1/P2"),
                List.of(dmEntry(), tpEntry("inner")),
                resolver(dm("S1/A"), tp("A/1/P1/0", "A/2/P2/1", "B/1/P1/1")), "R");
        assertEquals(List.of("S1:A->A/1", "S1:A->A/2"), bindings(exp));
    }


    /**
     * ⛔ Probe purity (§2.1): DM duplicates S1 on two paths, so the DM join makes two copies of the
     * one BW record; each copy must probe TP with ITS OWN bound DM row. A probe memoised on the
     * primary row alone would hand the second copy the first copy's TP rows.
     */
    @Test
    void eachCopyOfARecordProbesWithItsOwnSourceRow()
    {
        KeyMatchRowExpander.KeyMatchExpansion exp = ExecCalls.expand(bw("S1/P1"),
                List.of(dmEntry(), tpEntry("inner")),
                resolver(dm("S1/A", "S1/B"), tp("A/1/P1/0", "B/1/P1/1")), "R");
        assertEquals(List.of("S1:A->A/1", "S1:B->B/1"), bindings(exp));
    }


    /**
     * The memo may still short-cut a REPEATED source row: a record fanned out by an entry between
     * DM and TP probes TP once per distinct (primary row, DM row), and every copy binds right.
     */
    @Test
    void aRecordFannedOutBetweenTheSourceAndTheChainedEntryStillBindsRight()
    {
        IDataTable sj = MockTable.of().name("SJ").col("USUBJID", "S1", "S1").col("SJSEQ", "1", "2")
                .build();
        MatchDataset sjEntry = md(
                "{\"Name\":\"SJ\",\"Keys\":[\"USUBJID\"],\"Join_Type\":\"inner\"}");
        KeyMatchRowExpander.KeyMatchExpansion exp = ExecCalls.expand(bw("S1/P1"),
                List.of(dmEntry(), sjEntry, tpEntry("inner")),
                resolver(dm("S1/A", "S1/B"), sj, tp("A/1/P1/0", "B/1/P1/1")), "R");
        assertEquals(List.of("S1:A->A/1", "S1:A->A/1", "S1:B->B/1", "S1:B->B/1"), bindings(exp));
    }


    /** A {@code left} chained entry keeps the unmatched copy, unbound, and its flag reads false. */
    @Test
    void aLeftChainedEntryKeepsAnUnmatchedRecordUnbound()
    {
        KeyMatchRowExpander.KeyMatchExpansion exp = ExecCalls.expand(bw("S1/P1", "S2/P1"),
                List.of(dmEntry(), tpEntry("left")), resolver(dm("S1/A", "S2/B"), tp("A/1/P1/0")),
                "R");
        assertEquals(List.of("S1:A->A/1", "S2:B->null/null"), bindings(exp));
        JoinLookup tp = exp.lookups().get("TP");
        IDataTable t = exp.table();
        int matched = 0;
        for (long i = 0; i < t.getRowCount(); i++)
        {
            matched += tp.matchedRow(t, i) ? 1 : 0;
        }
        assertEquals(1, matched, "exactly one expanded row found a TP partner");
    }


    /** D4: the SOURCE column's kind is compared with the joined column's, and both are named. */
    @Test
    void aKindMismatchBetweenSourceAndJoinedColumnErrorsNamingBoth()
    {
        IDataTable numericTp = MockTable.of().name("TP").col("RPHASE", "P1").colLong("RPATHCD", 1L)
                .col("RPRFDY", "0").build();
        DatasetResolver inv = resolver(dm("S1/1"), numericTp);
        List<MatchDataset> entries = List.of(dmEntry(), tpEntry("inner"));
        JoinKeyTypeMismatchException ex = assertThrows(JoinKeyTypeMismatchException.class,
                () -> ExecCalls.expand(bw("S1/P1"), entries, inv, "R"));
        String msg = ex.getMessage();
        assertTrue(msg.contains("DM.RPATHCD"), msg);
        assertTrue(msg.contains("Character in DM"), msg);
        assertTrue(msg.contains("RPATHCD is Numeric in TP"), msg);
    }


    /** {@code Join_As_String} on the chained entry compares the two sides as text (D4-R3). */
    @Test
    void joinAsStringOnTheChainedEntryComparesAsText()
    {
        // Two paths in P1, so the R7 drop (TP on RPHASE alone) would bind both — the pre-change
        // engine passed this test with one TP row, vacuously.
        IDataTable numericTp = MockTable.of().name("TP").col("RPHASE", "P1", "P1")
                .colLong("RPATHCD", 1L, 2L).col("TPSTGORD", "1", "1").build();
        MatchDataset asText = md("{\"Name\":\"TP\",\"Keys\":[\"DM.RPATHCD\",\"RPHASE\"],"
                + "\"Join_Type\":\"inner\",\"Join_As_String\":true}");
        KeyMatchRowExpander.KeyMatchExpansion exp = ExecCalls.expand(bw("S1/P1"),
                List.of(dmEntry(), asText), resolver(dm("S1/1"), numericTp), "R");
        assertEquals(List.of("S1:1->1/1"), bindings(exp));
    }


    /**
     * JKM R4 on the chained entry: KEEP (the default) lets a blank source value pair with a blank
     * joined value; an authored {@code keep_missings: false} drops that record before any key is
     * built.
     */
    @Test
    void keepMissingsGovernsABlankSourceValue()
    {
        IDataTable blankPathDm = dm("S1/", "S2/B");
        IDataTable tpWithBlankPath = tp("/1/P1/0", "B/1/P1/1");
        KeyMatchRowExpander.KeyMatchExpansion keep = ExecCalls.expand(bw("S1/P1", "S2/P1"),
                List.of(dmEntry(), tpEntry("inner")), resolver(blankPathDm, tpWithBlankPath), "R");
        assertEquals(List.of("S1:->/1", "S2:B->B/1"), bindings(keep));

        MatchDataset drop = md("{\"Name\":\"TP\",\"Keys\":[\"DM.RPATHCD\",\"RPHASE\"],"
                + "\"Join_Type\":\"inner\",\"keep_missings\":false}");
        KeyMatchRowExpander.KeyMatchExpansion dropped = ExecCalls.expand(bw("S1/P1", "S2/P1"),
                List.of(dmEntry(), drop), resolver(blankPathDm, tpWithBlankPath), "R");
        assertEquals(List.of("S2:B->B/1"), bindings(dropped));
    }


    /** JKM R5: a missing source value is its own key — it never pairs with {@code ""}. */
    @Test
    void aMissingSourceValueDoesNotPairWithAnEmptyJoinedValue()
    {
        IDataTable dmWithMissing = MockTable.of().name("DM").col("USUBJID", "S1", "S2")
                .colSasMissing("RPATHCD", ".", "B").build();
        KeyMatchRowExpander.KeyMatchExpansion exp = ExecCalls.expand(bw("S1/P1", "S2/P1"),
                List.of(dmEntry(), tpEntry("inner")),
                resolver(dmWithMissing, tp("/1/P1/0", "B/1/P1/1")), "R");
        assertEquals(List.of("S2:B->B/1"), bindings(exp));
    }


    /**
     * {@code KeyMatchIndex.SpecKey} is the child-side half only, so a chained join and a plain join
     * over the same TP columns share ONE index (§2.1, §5.1): the plain rule builds it, the chained
     * rule reuses it, and both answer right.
     */
    @Test
    void aChainedAndAPlainJoinOverTheSameColumnsShareOneIndex()
    {
        JoinCache.SharedIndexCache shared = new JoinCache.SharedIndexCache();
        IDataTable tpTable = tp("A/1/P1/0", "B/1/P1/1");
        IDataTable dmTable = dm("S1/A", "S2/B");
        // The plain rule: the primary carries RPATHCD itself.
        IDataTable bwWithPath = MockTable.of().name("BW").col("USUBJID", "S1", "S2")
                .col("RPATHCD", "B", "A").col("RPHASE", "P1", "P1").build();
        MatchDataset plain = md(
                "{\"Name\":\"TP\",\"Keys\":[\"RPATHCD\",\"RPHASE\"]," + "\"Join_Type\":\"inner\"}");
        KeyMatchRowExpander.KeyMatchExpansion plainExp = ExecCalls.expand(bwWithPath,
                List.of(plain), resolver(dmTable, tpTable), "PLAIN", shared);
        assertNotNull(plainExp);
        assertEquals(1, shared.keyMatchIndexBuildCount(), "the plain join built TP's index");
        JoinLookup plainTp = plainExp.lookups().get("TP");
        assertEquals("B", plainTp.lookup(plainExp.table(), 0, "RPATHCD"));
        assertEquals("A", plainTp.lookup(plainExp.table(), 1, "RPATHCD"));

        KeyMatchRowExpander.KeyMatchExpansion chained = ExecCalls.expand(bw("S1/P1", "S2/P1"),
                List.of(dmEntry(), tpEntry("inner")), resolver(dmTable, tpTable), "CHAINED",
                shared);
        assertEquals(List.of("S1:A->A/1", "S2:B->B/1"), bindings(chained));
        assertEquals(2, shared.keyMatchIndexBuildCount(),
                "the chained join built DM's index and REUSED TP's — one build, not two");
    }

    // -----------------------------------------------------------------------
    // D-ABSENT — an unresolvable source is a rule ERROR, never a "" key, never a drop
    // -----------------------------------------------------------------------


    private static String unresolvedMessage(List<MatchDataset> entries, DatasetResolver inv)
    {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> ExecCalls.expand(bw("S1/P1"), entries, inv, "R"));
        assertFalse(ex instanceof IllegalStateException,
                "a diagnosis, not a bypass refusal: " + ex);
        String msg = String.valueOf(ex.getMessage());
        assertTrue(msg.contains("DM.RPATHCD"), msg);
        return msg;
    }


    @Test
    void theSourceDatasetAbsentFromTheStudyErrors()
    {
        String msg = unresolvedMessage(List.of(dmEntry(), tpEntry("inner")),
                resolver(tp("A/1/P1/0")));
        assertTrue(msg.contains("DM"), msg);
    }


    @Test
    void theSourceColumnAbsentFromTheSourceDatasetErrors()
    {
        IDataTable dmWithoutPath = MockTable.of().name("DM").col("USUBJID", "S1").build();
        String msg = unresolvedMessage(List.of(dmEntry(), tpEntry("inner")),
                resolver(dmWithoutPath, tp("A/1/P1/0")));
        assertTrue(msg.contains("DM has no column RPATHCD"), msg);
    }


    @Test
    void theUnqualifiedVariableAbsentFromTheJoinedDatasetErrors()
    {
        IDataTable tpWithoutPath = MockTable.of().name("TP").col("RPHASE", "P1").col("RPRFDY", "0")
                .build();
        String msg = unresolvedMessage(List.of(dmEntry(), tpEntry("inner")),
                resolver(dm("S1/A"), tpWithoutPath));
        assertTrue(msg.contains("TP has no column RPATHCD"), msg);
    }


    /** A qualifier naming no earlier entry never came through the loader: refused as such. */
    @Test
    void aQualifierNamingNoEarlierEntryIsALoaderBypass()
    {
        List<MatchDataset> entries = List.of(tpEntry("inner"));
        DatasetResolver inv = resolver(dm("S1/A"), tp("A/1/P1/0"));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ExecCalls.expand(bw("S1/P1"), entries, inv, "R"));
        assertTrue(ex.getMessage().contains("DM.RPATHCD"), ex.getMessage());
    }

    // -----------------------------------------------------------------------
    // Through RuleRunner — the loaded rule, its declarations and the ERROR channel
    // -----------------------------------------------------------------------


    /**
     * A loadable rule of the plan's shape: DM inner on {@code USUBJID}, TP inner on
     * {@code [DM.RPATHCD, RPHASE]}, every key declared (JKM R7's authoring gate).
     */
    private static String ruleJson(String matchDatasets, String requirements, String check)
    {
        return "{\"rules\":{\"T\":{\"Core\":{\"Id\":\"T-QK\"},\"Sensitivity\":\"Record\","
                + "\"Requirements\":{\"Variables\":" + requirements + "}," + "\"Match_Datasets\":"
                + matchDatasets + "," + "\"Check\":{\"expression\":\"" + check.replace("\"", "\\\"")
                + "\"}," + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}}}";
    }

    private static final String CHAINED_ENTRIES = "[{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"]},"
            + "{\"Name\":\"TP\",\"Keys\":[\"DM.RPATHCD\",\"RPHASE\"]}]";

    private static final String CHAINED_REQUIREMENTS = "{\"All\":[\"USUBJID\",\"DM.RPATHCD\","
            + "\"RPHASE\"],\"All_Or_None\":[[\"USUBJID\",\"DM.USUBJID\"],"
            + "[\"DM.RPATHCD\",\"TP.RPATHCD\"],[\"RPHASE\",\"TP.RPHASE\"]]}";

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


    private static Rule loadClean(String json)
    {
        Rule rule = load(json);
        assertNull(rule.getLoadError(), rule.getLoadError());
        return rule;
    }


    private static String loadError(String json)
    {
        String error = load(json).getLoadError();
        assertNotNull(error, "expected a load error");
        return error;
    }


    @Test
    void theLoadedRuleJudgesEachRecordAgainstItsOwnPath()
    {
        Rule rule = loadClean(
                ruleJson(CHAINED_ENTRIES, CHAINED_REQUIREMENTS, "TP.RPRFDY == \"1\""));
        IDataTable bwTable = bw("S1/P1", "S2/P1");
        RuleExecutionResult res = RuleRunnerCalls.execute(rule, bwTable, RealTables
                .inventoryOf(bwTable, dm("S1/A", "S2/B"), tp("A/1/P1/0", "A/2/P1/0", "B/1/P1/1")));
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus(), res.getStatusMessage());
        // S2 (path B, RPRFDY 1) fires once; S1's two path-A stages (RPRFDY 0) are silent. The R7
        // drop would join S1 to B's stage too and fire twice.
        assertEquals(1, res.getViolations().size(), res.getViolations().toString());
        assertEquals("S2", res.getViolations().get(0).getValues().get("USUBJID"));
    }


    /**
     * The one D-ABSENT case the authoring gate cannot turn into a SKIP: {@code RPATHCD} absent on
     * BOTH sides satisfies the {@code All_Or_None} group, the rule runs, and the qualified
     * component — unlike a plain one — may not be dropped (JKM R7's both-absent drop is for a
     * component both sides lack; here the sides are different datasets, and the join has no
     * answer). The rule ERRORs on the sentinel channel.
     */
    @Test
    void bothSidesAbsentIsAnErrorNotADrop()
    {
        // The qualified key SECOND: a first key is declared bare in All (the gate), which would
        // SKIP the study before the join; a later key is declared by its All_Or_None group only.
        Rule rule = loadClean(ruleJson(
                "[{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"]},"
                        + "{\"Name\":\"TP\",\"Keys\":[\"RPHASE\",\"DM.RPATHCD\"]}]",
                "{\"All\":[\"USUBJID\",\"RPHASE\"],\"All_Or_None\":[[\"USUBJID\",\"DM.USUBJID\"],"
                        + "[\"RPHASE\",\"TP.RPHASE\"],[\"DM.RPATHCD\",\"TP.RPATHCD\"]]}",
                "TP.RPRFDY == \"1\""));
        IDataTable bwTable = bw("S1/P1", "S2/P1");
        IDataTable dmWithoutPath = MockTable.of().name("DM").col("USUBJID", "S1", "S2").build();
        IDataTable tpWithoutPath = MockTable.of().name("TP").col("RPHASE", "P1", "P1")
                .col("RPRFDY", "0", "1").build();
        RuleExecutionResult res = RuleRunnerCalls.execute(rule, bwTable,
                RealTables.inventoryOf(bwTable, dmWithoutPath, tpWithoutPath));
        assertEquals(RuleExecutionStatus.ERROR, res.getStatus(), res.getStatusMessage());
        String msg = String.valueOf(res.getStatusMessage());
        assertTrue(msg.contains("DM.RPATHCD"), msg);
        assertEquals(msg, res.getViolations().get(0).getValues().get("__error__"));
    }


    /** The authoring gate: a study lacking the source column on one side SKIPS (JKM R7, Q8). */
    @Test
    void aOneSidedAbsenceSkipsThroughTheDeclaration()
    {
        Rule rule = loadClean(
                ruleJson(CHAINED_ENTRIES, CHAINED_REQUIREMENTS, "TP.RPRFDY == \"1\""));
        IDataTable bwTable = bw("S1/P1");
        IDataTable tpWithoutPath = MockTable.of().name("TP").col("RPHASE", "P1").col("RPRFDY", "0")
                .build();
        RuleExecutionResult res = RuleRunnerCalls.execute(rule, bwTable,
                RealTables.inventoryOf(bwTable, dm("S1/A"), tpWithoutPath));
        assertEquals(RuleExecutionStatus.SKIPPED, res.getStatus(), res.getStatusMessage());
    }

    // -----------------------------------------------------------------------
    // The loader — D-SRC, D-SIDED, the declaration shape
    // -----------------------------------------------------------------------


    @Test
    void theDeclarationGroupHoldsTheQualifiedKeyAndTheJoinedName()
    {
        String withoutGroup = "{\"All\":[\"USUBJID\",\"DM.RPATHCD\",\"RPHASE\"],"
                + "\"All_Or_None\":[[\"USUBJID\",\"DM.USUBJID\"],[\"RPHASE\",\"TP.RPHASE\"]]}";
        String error = loadError(ruleJson(CHAINED_ENTRIES, withoutGroup, "TP.RPRFDY == \"1\""));
        assertTrue(error.contains("DM.RPATHCD") && error.contains("TP.RPATHCD"), error);

        String notInAll = "{\"All\":[\"USUBJID\",\"RPHASE\"],"
                + "\"All_Or_None\":[[\"USUBJID\",\"DM.USUBJID\"],[\"DM.RPATHCD\",\"TP.RPATHCD\"],"
                + "[\"RPHASE\",\"TP.RPHASE\"]]}";
        String firstKey = loadError(ruleJson(CHAINED_ENTRIES, notInAll, "TP.RPRFDY == \"1\""));
        assertTrue(firstKey.contains("first key DM.RPATHCD"), firstKey);
    }


    private static String sourceError(String entries)
    {
        return loadError(ruleJson(entries, CHAINED_REQUIREMENTS, "TP.RPRFDY == \"1\""));
    }


    @Test
    void aQualifierMustNameAnEarlierOrdinaryInnerEntry()
    {
        // later
        String later = sourceError("[{\"Name\":\"TP\",\"Keys\":[\"DM.RPATHCD\",\"RPHASE\"]},"
                + "{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"]}]");
        assertTrue(
                later.contains("qualified key DM.RPATHCD names entry DM, which is declared LATER"),
                later);
        // itself
        String self = loadError(ruleJson(
                "[{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"]},"
                        + "{\"Name\":\"TP\",\"Keys\":[\"TP.RPATHCD\",\"RPHASE\"]}]",
                "{\"All\":[\"USUBJID\",\"TP.RPATHCD\",\"RPHASE\"],\"All_Or_None\":"
                        + "[[\"USUBJID\",\"DM.USUBJID\"],[\"RPHASE\",\"TP.RPHASE\"]]}",
                "TP.RPRFDY == \"1\""));
        assertTrue(self.contains("qualified key TP.RPATHCD names its own entry"), self);
        // undeclared
        String undeclared = loadError(
                ruleJson("[{\"Name\":\"TP\",\"Keys\":[\"DM.RPATHCD\"," + "\"RPHASE\"]}]",
                        CHAINED_REQUIREMENTS, "TP.RPRFDY == \"1\""));
        assertTrue(undeclared.contains("qualified key DM.RPATHCD names no Match_Datasets entry DM"),
                undeclared);
        // a left source
        String left = sourceError("[{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"],"
                + "\"Join_Type\":\"left\"},{\"Name\":\"TP\",\"Keys\":[\"DM.RPATHCD\",\"RPHASE\"]}]");
        assertTrue(left.contains("names entry DM with Join_Type left"), left);
        // a Child source
        String child = loadError(ruleJson(
                "[{\"Name\":\"SUPPBW\",\"Child\":true,"
                        + "\"Keys\":[\"USUBJID\",\"IDVAR\",\"IDVARVAL\"]},"
                        + "{\"Name\":\"TP\",\"Keys\":[\"SUPPBW.RPATHCD\",\"RPHASE\"]}]",
                "{\"All\":[\"USUBJID\",\"IDVAR\",\"IDVARVAL\",\"SUPPBW.RPATHCD\",\"RPHASE\"],"
                        + "\"All_Or_None\":[[\"SUPPBW.RPATHCD\",\"TP.RPATHCD\"],"
                        + "[\"RPHASE\",\"TP.RPHASE\"]]}",
                "TP.RPRFDY == \"1\""));
        assertTrue(child.contains("qualified key SUPPBW.RPATHCD names entry SUPPBW, which is not an"
                + " ordinary keyed join"), child);
        // a RELREC source
        String relrec = loadError(ruleJson(
                "[{\"Name\":\"RELREC\"},"
                        + "{\"Name\":\"TP\",\"Keys\":[\"RELREC.RPATHCD\",\"RPHASE\"]}]",
                "{\"All\":[\"USUBJID\",\"RELREC.RPATHCD\",\"RPHASE\"],"
                        + "\"All_Or_None\":[[\"RELREC.RPATHCD\",\"TP.RPATHCD\"],"
                        + "[\"RPHASE\",\"TP.RPHASE\"]]}",
                "TP.RPRFDY == \"1\""));
        assertTrue(relrec.contains("qualified key RELREC.RPATHCD names entry RELREC, which is not"
                + " an ordinary keyed join"), relrec);
    }


    @Test
    void aQualifiedKeyOnANonExpandableEntryIsRefused()
    {
        String onChild = loadError(ruleJson(
                "[{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"]},"
                        + "{\"Name\":\"SUPPBW\",\"Child\":true,\"Keys\":[\"DM.USUBJID\",\"IDVAR\","
                        + "\"IDVARVAL\"]}]",
                "{\"All\":[\"USUBJID\",\"DM.USUBJID\",\"IDVAR\",\"IDVARVAL\"],"
                        + "\"All_Or_None\":[[\"USUBJID\",\"DM.USUBJID\"]]}",
                "not empty(QVAL)"));
        assertTrue(onChild.contains(
                "qualified key DM.USUBJID on an entry the ordinary keyed join" + " does not read"),
                onChild);
    }


    @Test
    void aDottedSideInsideASidedElementIsRefusedNamingTheBareSpelling()
    {
        String sided = loadError(ruleJson("[{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"]},"
                + "{\"Name\":\"TP\",\"Keys\":[{\"left\":\"DM.RPATHCD\",\"right\":\"RPATHCD\"},"
                + "\"RPHASE\"]}]", CHAINED_REQUIREMENTS, "TP.RPRFDY == \"1\""));
        assertTrue(sided.contains("a sided Keys element carries the dotted name DM.RPATHCD"),
                sided);
        assertTrue(sided.contains("write \"DM.RPATHCD\" as the key element"), sided);
    }


    @Test
    void aDomainWildcardInsideAQualifiedKeyIsRefused()
    {
        String wildcard = loadError(ruleJson(
                "[{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"]},"
                        + "{\"Name\":\"TP\",\"Keys\":[\"DM.--SEQ\",\"RPHASE\"]}]",
                "{\"All\":[\"USUBJID\",\"DM.--SEQ\",\"RPHASE\"],\"All_Or_None\":"
                        + "[[\"USUBJID\",\"DM.USUBJID\"],[\"DM.--SEQ\",\"TP.--SEQ\"],"
                        + "[\"RPHASE\",\"TP.RPHASE\"]]}",
                "TP.RPRFDY == \"1\""));
        assertTrue(wildcard.contains("qualified key DM.--SEQ carries a -- / & wildcard"), wildcard);
    }

    // -----------------------------------------------------------------------
    // The model
    // -----------------------------------------------------------------------


    @Test
    void theRightSideOfAQualifiedKeyIsTheUnqualifiedName()
    {
        MatchDataset entry = tpEntry("inner");
        assertEquals(List.of("DM.RPATHCD", "RPHASE"), entry.getKeys());
        assertEquals(List.of("RPATHCD", "RPHASE"), entry.getRightKeys());
        assertFalse(entry.hasSidedKeys(), "a bare qualified string is not a sided element");
        MatchDataset plain = md("{\"Name\":\"TP\",\"Keys\":[\"RPATHCD\",\"RPHASE\"]}");
        assertEquals(plain.getKeys(), plain.getRightKeys());
    }

}
