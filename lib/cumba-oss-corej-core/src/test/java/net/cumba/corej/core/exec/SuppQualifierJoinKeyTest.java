package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * W4 ride-along of {@code PLAN-merged-evaluation-table} (W3f's named gap, decided by the
 * coordinator — never silently wrong): a left key of a {@code Match_Datasets.Keys} entry that the
 * primary lacks but its {@code SUPP<domain>} supplies as a qualifier ({@code QNAM}) is not served —
 * the datatable keys the raw primary before the SUPP parts — and fell to JKM R7's absent default, a
 * silent mis-join. It is the rule's ERROR under the merged table's classified prefix
 * ({@link MergedEvaluation#MERGE_REFUSED_MESSAGE_PREFIX}), naming the key and saying the qualifier
 * cannot key a join yet. Corpus exposure 0 (W3f).
 */
class SuppQualifierJoinKeyTest
{

    private static Rule rule(boolean aSuppMerge)
    {
        String json = "{\"rules\":{\"X-1\":{\"Core\":{\"Id\":\"X-1\"},"
                + (aSuppMerge ? "" : "\"Supp_Merge\":false,")
                + "\"Requirements\":{\"Variables\":{\"All\":[\"USUBJID\"],"
                + "\"All_Or_None\":[[\"USUBJID\",\"XX.USUBJID\"],[\"AETRTEM\",\"XX.AETRTEM\"]]}},"
                + "\"Match_Datasets\":[{\"Name\":\"XX\",\"Keys\":[\"USUBJID\",\"AETRTEM\"],"
                + "\"Join_Type\":\"left\"}],"
                + "\"Check\":{\"expression\":\"XX.VAL == \\\"hit\\\"\"},"
                + "\"Outcome\":{\"Message\":\"m\"}}}}";
        try
        {
            Rule rule = RulePackageLoader.loadFromString(json).getRules().get("X-1");
            assertNull(rule.getLoadError(), "fixture must load: " + rule.getLoadError());
            return rule;
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException(json, e);
        }
    }


    /** An inventory-capable resolver, so the qualified key declarations can be decided. */
    private static DatasetResolver resolver(IDataTable... tables)
    {
        return RealTables.inventoryOf(tables);
    }


    private static IDataTable ae()
    {
        return RealTableFixture.of("AE").str("STUDYID", "S1", "S1").str("DOMAIN", "AE", "AE")
                .str("USUBJID", "01", "01").lng("AESEQ", 1L, 2L).build();
    }


    private static IDataTable suppae(String aQnam)
    {
        return RealTableFixture.of("SUPPAE").str("STUDYID", "S1", "S1").str("RDOMAIN", "AE", "AE")
                .str("USUBJID", "01", "01").str("IDVAR", "AESEQ", "AESEQ").str("IDVARVAL", "1", "2")
                .str("QNAM", aQnam, aQnam).str("QVAL", "Y", "N").build();
    }


    private static IDataTable xx()
    {
        return RealTableFixture.of("XX").str("USUBJID", "01", "01").str("AETRTEM", "Y", "N")
                .str("VAL", "hit", "miss").build();
    }


    @Test
    void aSuppQualifierAsAJoinKeyIsTheRulesError()
    {
        IDataTable ae = ae();
        RuleExecutionResult r = RuleRunnerCalls.execute(rule(true), ae,
                resolver(ae, suppae("AETRTEM"), xx()));
        assertEquals(RuleExecutionStatus.ERROR, r.getStatus(), String.valueOf(r));
        String msg = String.valueOf(r.getStatusMessage());
        assertTrue(msg.startsWith(MergedEvaluation.MERGE_REFUSED_MESSAGE_PREFIX), msg);
        assertTrue(msg.contains("the key AETRTEM of the Match_Datasets entry XX"), msg);
        assertTrue(msg.contains("supplemental qualifier (QNAM) of SUPPAE"), msg);
        assertTrue(msg.contains("cannot key a join yet"), msg);
    }


    @Test
    void controlsThePrimarysOwnColumnAnotherQualifierAndNoSuppMergeAreNotRefused()
    {
        // The planted controls, each one fact away from the refused shape:
        // (1) the primary carries AETRTEM itself (the SUPP qualifier of the same name is shadowed,
        // P-10): the key is served from the primary and the rule runs — row 0 joins XX's "hit";
        IDataTable aeWithKey = RealTableFixture.of("AE").str("STUDYID", "S1", "S1")
                .str("DOMAIN", "AE", "AE").str("USUBJID", "01", "01").lng("AESEQ", 1L, 2L)
                .str("AETRTEM", "Y", "N").build();
        RuleExecutionResult own = RuleRunnerCalls.execute(rule(true), aeWithKey,
                resolver(aeWithKey, suppae("AETRTEM"), xx()));
        assertEquals(RuleExecutionStatus.EXECUTED, own.getStatus(), String.valueOf(own));
        assertEquals(1, own.getViolations().size(), String.valueOf(own));
        // (2) SUPPAE qualifies another QNAM, and (3) the rule merges no SUPP: AETRTEM is absent
        // everywhere the rule looks, so the declared key SKIPS the rule (every keyed join declares
        // its key) — never the refusal.
        IDataTable ae = ae();
        for (RuleExecutionResult r : java.util.List.of(
                RuleRunnerCalls.execute(rule(true), ae, resolver(ae, suppae("AEOTHER"), xx())),
                RuleRunnerCalls.execute(rule(false), ae, resolver(ae, suppae("AETRTEM"), xx()))))
        {
            assertEquals(RuleExecutionStatus.SKIPPED, r.getStatus(), String.valueOf(r));
            assertTrue(String.valueOf(r.getStatusMessage()).contains("is only partly present"),
                    String.valueOf(r));
        }
    }
}
