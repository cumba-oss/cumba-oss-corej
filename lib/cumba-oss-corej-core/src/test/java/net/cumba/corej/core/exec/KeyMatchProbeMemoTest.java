package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * {@code PLAN-identity-safe-join-caches} Phase 2, #5(a): a later {@code Match_Datasets} entry
 * probes each PRIMARY row once, however many expanded rows an earlier entry fanned it out to — and
 * the memo never leaks from one entry into the next.
 */
class KeyMatchProbeMemoTest
{

    private static MatchDataset md(String aName)
    {
        MatchDataset m = new MatchDataset();
        m.setName(aName);
        m.setKeys(List.of("USUBJID"));
        m.setJoinType("left");
        return m;
    }


    private static List<String> render(KeyMatchRowExpander.KeyMatchExpansion aExp)
    {
        IDataTable t = aExp.table();
        List<String> out = new ArrayList<>();
        for (long i = 0; i < t.getRowCount(); i++)
        {
            StringBuilder sb = new StringBuilder().append(t.getRealRowIndex(i));
            for (Map.Entry<String, JoinLookup> e : aExp.lookups().entrySet())
            {
                sb.append(' ').append(e.getKey()).append('=')
                        .append(e.getValue().lookup(t, i, "ROWID"));
            }
            out.add(sb.toString());
        }
        return out;
    }


    /** How many times the key-match path read a key cell of {@code aPrimary}. */
    private static long keyReads(IDataTable aPrimary)
    {
        return Mockito.mockingDetails(aPrimary).getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("getColumn")).count();
    }


    @Test
    void aFannedOutPrimaryRowIsProbedOncePerEntry()
    {
        // MockTable builds a Mockito mock, so its invocations are the probe instrument.
        IDataTable primary = MockTable.of().name("ADAE").col("USUBJID", "P1", "P2").build();
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "P1", "P1", "P1", "P2")
                .col("ROWID", "A0", "A1", "A2", "A3").build();
        IDataTable dm = MockTable.of().name("DM").col("USUBJID", "P1", "P2")
                .col("ROWID", "D0", "D1").build();
        Mockito.clearInvocations(primary);

        KeyMatchRowExpander.KeyMatchExpansion exp = ExecCalls.expand(primary,
                List.of(md("AE"), md("DM")), Map.of("AE", ae, "DM", dm)::get, "R",
                new JoinCache.SharedIndexCache());
        assertNotNull(exp);
        long reads = keyReads(primary);

        assertEquals(List.of("0 AE=A0 DM=D0", "0 AE=A1 DM=D0", "0 AE=A2 DM=D0", "1 AE=A3 DM=D1"),
                render(exp), "the expansion itself is unchanged by the memo");
        assertEquals(4, reads,
                "AE probes the 2 primary rows, DM probes them again — 2 + 2, not 2 + 4: primary"
                        + " row 0 is expanded to 3 rows by AE and must be probed once for DM");
    }


    /**
     * ⭐ The memo is per ENTRY. With one primary row, the first entry's last probe and the second
     * entry's first probe are for the same primary row — a memo carried across entries would hand
     * DM the key id AE's index gave, which in DM's index names a DIFFERENT row.
     */
    @Test
    void theMemoIsResetBetweenEntries()
    {
        IDataTable primary = MockTable.of().name("ADAE").col("USUBJID", "P1").build();
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "P1").col("ROWID", "A0").build();
        IDataTable dm = MockTable.of().name("DM").col("USUBJID", "P9", "P1")
                .col("ROWID", "D0", "D1").build();
        KeyMatchRowExpander.KeyMatchExpansion exp = ExecCalls.expand(primary,
                List.of(md("AE"), md("DM")), Map.of("AE", ae, "DM", dm)::get, "R", null);
        assertNotNull(exp);
        assertEquals(List.of("0 AE=A0 DM=D1"), render(exp),
                "P1 is key id 0 in AE's index but key id 1 in DM's: DM must probe for itself");
    }
}
