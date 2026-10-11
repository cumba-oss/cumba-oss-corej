package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.merge.KeyDropWarning;
import org.junit.jupiter.api.Test;

/**
 * ⭐ {@code REG-Q3} — every key column a {@code Match_Datasets} join drops because it is present on
 * one side only is <b>logged as a WARNING naming the rule</b>, in the one wording the SUPP merge
 * already uses ({@link KeyDropWarning}), so a fan-out is always traceable (owner, 2026-10-09:
 * <i>"move to the drop as proposed with your warning"</i>; QK1 (a): the log WARNING, emitted by the
 * engine, which holds the rule id).
 *
 * <p>
 * Emitted by {@code MergedEvaluation.keyedSpecs} for <b>every</b> served keyed part — also a
 * {@code SUPP--} named one the key gates do not govern (review M1) — once per dropped column; a
 * column absent from both sides leaves the key silently (QK5 (a)).
 * </p>
 */
class JoinKeyDropWarningTest
{

    private static final String AE = "AE";

    private static final String USUBJID = "USUBJID";

    /** The WARNING lines {@code MergedEvaluation} logs while {@code body} runs. */
    static List<String> warnings(Runnable body)
    {
        List<String> out = new ArrayList<>();
        Handler handler = new Handler()
        {

            @Override
            public void publish(LogRecord aRecord)
            {
                if (aRecord.getLevel().intValue() >= Level.WARNING.intValue())
                {
                    synchronized (out)
                    {
                        out.add(aRecord.getMessage());
                    }
                }
            }


            @Override
            public void flush()
            {
                // nothing buffered
            }


            @Override
            public void close()
            {
                // nothing to release
            }
        };
        Logger juli = Logger.getLogger(MergedEvaluation.class.getName());
        juli.addHandler(handler);
        try
        {
            body.run();
        }
        finally
        {
            juli.removeHandler(handler);
        }
        synchronized (out)
        {
            return List.copyOf(out);
        }
    }


    private static MatchDataset entry(String name, List<String> keys)
    {
        MatchDataset m = new MatchDataset();
        m.setName(name);
        m.setKeys(keys);
        m.setJoinType("left");
        return m;
    }


    private static MergedEvaluation.Built expand(IDataTable primary, List<MatchDataset> entries,
            Map<String, IDataTable> datasets)
    {
        MergedEvaluation.Built built = ExecCalls.expand(primary, entries, datasets::get, "CG-TEST");
        assertNotNull(built);
        return built;
    }


    @Test
    void aColumnOnThePartnerOnlyIsOneWarningNamingTheRuleTheEntryAndTheCarrier()
    {
        IDataTable ae = RealTables.of(AE).str(USUBJID, "P1").str("AETERM", "HEADACHE").build();
        IDataTable sv = RealTables.of("SV").str(USUBJID, "P1", "P1").lng("VISITNUM", 1L, 2L)
                .build();
        List<String> lines = warnings(() -> assertEquals(2L,
                expand(ae, List.of(entry("SV", List.of(USUBJID, "VISITNUM"))), Map.of("SV", sv))
                        .table().getRowCount(),
                "control: VISITNUM left the key, the AE record pairs both SV rows"));
        assertEquals(List.of(
                "[CG-TEST] Match_Datasets SV: " + KeyDropWarning.format("SV", "VISITNUM", "SV")),
                lines);
    }


    @Test
    void aColumnOnTheRecordSideOnlyNamesTheValidatedDatasetAsTheCarrier()
    {
        IDataTable ae = RealTables.of(AE).str(USUBJID, "P1").lng("VISITNUM", 1L).build();
        IDataTable sv = RealTables.of("SV").str(USUBJID, "P1").str("SVSTDTC", "2024").build();
        List<String> lines = warnings(() -> expand(ae,
                List.of(entry("SV", List.of(USUBJID, "VISITNUM"))), Map.of("SV", sv)));
        assertEquals(List.of("[CG-TEST] Match_Datasets SV: SV: key column VISITNUM is present on"
                + " one side only (AE) and is ignored"), lines);
    }


    @Test
    void aSidedKeyIsNamedLeftSlashRight()
    {
        IDataTable ae = RealTables.of(AE).str(USUBJID, "P1").build();
        IDataTable fa = RealTables.of("FA").str(USUBJID, "P1").str("FASEQ", "1").build();
        MatchDataset sided;
        try
        {
            sided = new ObjectMapper()
                    .readValue(
                            "{\"Name\":\"FA\",\"Join_Type\":\"left\",\"Keys\":[\"USUBJID\","
                                    + "{\"left\":\"AESEQ\",\"right\":\"FASEQ\"}]}",
                            MatchDataset.class);
        }
        catch (JsonProcessingException e)
        {
            throw new IllegalStateException(e);
        }
        List<String> lines = warnings(() -> expand(ae, List.of(sided), Map.of("FA", fa)));
        assertEquals(List.of(
                "[CG-TEST] Match_Datasets FA: " + KeyDropWarning.format("FA", "AESEQ/FASEQ", "FA")),
                lines);
    }


    @Test
    void aColumnAbsentFromBothSidesLeavesTheKeySilently()
    {
        IDataTable ae = RealTables.of(AE).str(USUBJID, "P1").build();
        IDataTable sv = RealTables.of("SV").str(USUBJID, "P1").build();
        assertEquals(
                List.of(), warnings(() -> expand(ae,
                        List.of(entry("SV", List.of(USUBJID, "VISITNUM"))), Map.of("SV", sv))),
                "QK5 (a): both sides absent is not a one-side drop");
    }


    @Test
    void twoDroppedColumnsAreTwoWarnings()
    {
        IDataTable ae = RealTables.of(AE).str(USUBJID, "P1").str("AESPID", "A1").build();
        IDataTable sv = RealTables.of("SV").str(USUBJID, "P1").lng("VISITNUM", 1L).build();
        List<String> lines = warnings(() -> expand(ae,
                List.of(entry("SV", List.of(USUBJID, "VISITNUM", "AESPID"))), Map.of("SV", sv)));
        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("key column VISITNUM")
                && lines.get(1).contains("key column AESPID"), lines.toString());
    }


    /**
     * Review M1: a keyed {@code SUPP--} entry is a keyed part the key gates do not govern
     * ({@code JoinKeyTypes.governedByKeyTypeCheck}); its one-side drop is WARNed all the same.
     */
    @Test
    void aKeyedSuppEntryOutsideTheGatesWarnsToo()
    {
        IDataTable ae = RealTables.of(AE).str(USUBJID, "P1").str("AESEQ", "1").build();
        IDataTable suppae = RealTables.of("SUPPAE").str(USUBJID, "P1").str("QNAM", "X")
                .str("QVAL", "Y").build();
        MatchDataset supp = entry("SUPP--", List.of(USUBJID, "AESEQ"));
        assertTrue(!JoinKeyTypes.governedByKeyTypeCheck(supp),
                "control: the entry is outside the gates, the path review M1 named");
        List<String> lines = warnings(() -> expand(ae, List.of(supp), Map.of("SUPPAE", suppae)));
        assertEquals(List.of(
                "[CG-TEST] Match_Datasets SUPP--: " + KeyDropWarning.format("SUPPAE", "AESEQ", AE)),
                lines);
    }


    /** One rule over AE keyed to SV on {@code [USUBJID, VISITNUM]}, firing when no SV row binds. */
    private static Rule svRule()
    {
        String json = "{\"rules\":{\"T\":{\"Core\":{\"Id\":\"T-JK\"},\"Sensitivity\":\"Record\","
                + "\"Requirements\":{\"Variables\":{\"All\":[\"USUBJID\"],\"All_Or_None\":"
                + "[[\"USUBJID\",\"SV.USUBJID\"],[\"VISITNUM\",\"SV.VISITNUM\"]]}},"
                + "\"Match_Datasets\":[{\"Name\":\"SV\",\"Keys\":[\"USUBJID\",\"VISITNUM\"],"
                + "\"Join_Type\":\"left\"}],\"Check\":{\"expression\":\"empty(SV.SVSTDTC)\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}}}";
        try
        {
            Rule rule = RulePackageLoader.loadFromString(json).getRules().get("T");
            assertNull(rule.getLoadError(), rule.getLoadError());
            return rule;
        }
        catch (java.io.IOException e)
        {
            throw new IllegalArgumentException(e);
        }
    }


    /**
     * The plan's planted control (a), rule level: an UNGATED one-side key (the declaration removed
     * after load — the authoring gate would refuse such a rule) fans out with exactly one WARNING
     * where the retired default flooded; the same rule WITH its {@code All_Or_None} group SKIPS
     * (the gate {@code REG-Q3} keeps).
     */
    @Test
    void anUngatedOneSideKeyFansOutWithOneWarningWhileTheDeclaredRuleSkips()
    {
        IDataTable ae = RealTables.of(AE).str(USUBJID, "P1", "P2").str("AETERM", "A", "B").build();
        IDataTable sv = RealTables.of("SV").str(USUBJID, "P1", "P1").lng("VISITNUM", 1L, 2L)
                .str("SVSTDTC", "2024-01-01", "2024-02-01").build();
        Rule declared = svRule();
        RuleExecutionResult skipped = RuleRunnerCalls.execute(declared, ae,
                RealTables.inventoryOf(ae, sv));
        assertEquals(RuleExecutionStatus.SKIPPED, skipped.getStatus(),
                "the declaration turns AE's missing VISITNUM into a SKIP: "
                        + skipped.getStatusMessage());
        Rule ungated = svRule();
        ungated.setRequirements(null);
        List<RuleExecutionResult> res = new ArrayList<>();
        List<String> lines = warnings(() -> res
                .add(RuleRunnerCalls.execute(ungated, ae, RealTables.inventoryOf(ae, sv))));
        assertEquals(RuleExecutionStatus.EXECUTED, res.get(0).getStatus(),
                res.get(0).getStatusMessage());
        assertEquals(List.of("P2"),
                res.get(0).getViolations().stream()
                        .map(v -> String.valueOf(v.getValues().get(USUBJID))).toList(),
                "P1 binds both SV rows (VISITNUM left the key), only P2 has none. [P1, P2] is the"
                        + " retired default's flood (\"\" / MIS matched no SV row)");
        assertEquals(List
                .of("[T-JK] Match_Datasets SV: " + KeyDropWarning.format("SV", "VISITNUM", "SV")),
                lines);
    }
}
