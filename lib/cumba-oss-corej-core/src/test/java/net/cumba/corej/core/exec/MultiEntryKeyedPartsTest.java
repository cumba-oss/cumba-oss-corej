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

/**
 * Two keyed {@code Match_Datasets} entries over one primary: a primary row fanned out by the first
 * entry binds the second entry's partner on every copy, in entry order (the first entry the major
 * factor), and each entry keys its own partner. Written for the retired key-match expander's probe
 * memo ({@code PLAN-identity-safe-join-caches} Phase 2, #5(a)); since W6 of
 * {@code PLAN-merged-evaluation-table} the merged view joins every entry against the primary row
 * itself, so there is no memo to leak — the bindings stay the pin.
 */
class MultiEntryKeyedPartsTest
{

    private static MatchDataset md(String aName)
    {
        MatchDataset m = new MatchDataset();
        m.setName(aName);
        m.setKeys(List.of("USUBJID"));
        m.setJoinType("left");
        return m;
    }


    private static List<String> render(MergedEvaluation.Built aExp)
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


    @Test
    void aFannedOutPrimaryRowBindsTheSecondEntryOnEveryCopy()
    {
        IDataTable primary = MockTable.of().name("ADAE").col("USUBJID", "P1", "P2").build();
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "P1", "P1", "P1", "P2")
                .col("ROWID", "A0", "A1", "A2", "A3").build();
        IDataTable dm = MockTable.of().name("DM").col("USUBJID", "P1", "P2")
                .col("ROWID", "D0", "D1").build();

        MergedEvaluation.Built exp = ExecCalls.expand(primary, List.of(md("AE"), md("DM")),
                Map.of("AE", ae, "DM", dm)::get, "R", new JoinCache.SharedIndexCache());
        assertNotNull(exp);
        assertEquals(List.of("0 AE=A0 DM=D0", "0 AE=A1 DM=D0", "0 AE=A2 DM=D0", "1 AE=A3 DM=D1"),
                render(exp), "AE fans primary row 0 out to 3 rows; each binds DM's D0");
    }


    /**
     * ⭐ Each entry keys its own partner: P1 is key id 0 in AE's index but key id 1 in DM's — a key
     * id carried from one entry into the next (the retired probe memo's hazard) would bind DM's D0.
     */
    @Test
    void eachEntryKeysItsOwnPartner()
    {
        IDataTable primary = MockTable.of().name("ADAE").col("USUBJID", "P1").build();
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "P1").col("ROWID", "A0").build();
        IDataTable dm = MockTable.of().name("DM").col("USUBJID", "P9", "P1")
                .col("ROWID", "D0", "D1").build();
        MergedEvaluation.Built exp = ExecCalls.expand(primary, List.of(md("AE"), md("DM")),
                Map.of("AE", ae, "DM", dm)::get, "R", null);
        assertNotNull(exp);
        assertEquals(List.of("0 AE=A0 DM=D1"), render(exp),
                "P1 is key id 0 in AE's index but key id 1 in DM's: DM must probe for itself");
    }
}
