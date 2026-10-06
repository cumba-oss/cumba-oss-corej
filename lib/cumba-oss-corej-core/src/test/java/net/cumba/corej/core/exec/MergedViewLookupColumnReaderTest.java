package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.LongFunction;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.IDataValue;
import org.junit.jupiter.api.Test;

/**
 * {@link JoinLookup#columnReader} — the merged column resolved once per reader
 * ({@code PLAN-merged-evaluation-table} T1, the W3c perf candidate) — answers exactly what
 * {@link JoinLookup#lookupValue} answers, row by row: a matched cell, an unmatched row's
 * not-supplied default, a column the partner lacks (character and numeric expectation).
 */
class MergedViewLookupColumnReaderTest
{

    private static JoinLookup lookup(IDataTable lb)
    {
        IDataTable dm = RealTables.of("DM").str("USUBJID", "P1", "P3").str("ARM", "A", "")
                .lng("AGE", 34L, null).build();
        MatchDataset md = new MatchDataset();
        md.setName("DM");
        md.setKeys(List.of("USUBJID"));
        md.setJoinType("left");
        MergedEvaluation.Built built = ExecCalls.expand(lb, List.of(md),
                name -> "DM".equals(name) ? dm : null, "R");
        assertNotNull(built);
        return built.lookups().get("DM");
    }


    @Test
    void theReaderAnswersWhatLookupValueAnswersOnEveryRow()
    {
        IDataTable lb = RealTables.of("LB").str("USUBJID", "P1", "P2", "P3", "P1").build();
        JoinLookup lk = lookup(lb);
        int compared = 0;
        int notSupplied = 0;
        for (String column : List.of("ARM", "AGE", "NOPE"))
        {
            for (boolean numeric : new boolean[]
            {
                    false, true
            })
            {
                LongFunction<IDataValue> reader = lk.columnReader(lb, column, numeric);
                for (long r = 0; r < lb.getRowCount(); r++)
                {
                    IDataValue expected = lk.lookupValue(lb, r, column, numeric);
                    IDataValue actual = reader.apply(r);
                    assertEquals(expected.getType(), actual.getType(), column + "@" + r);
                    assertEquals(expected.getValue(), actual.getValue(), column + "@" + r);
                    assertEquals(expected.isMissingOrInvalid(), actual.isMissingOrInvalid(),
                            column + "@" + r);
                    compared++;
                    if (!lk.matchedRow(lb, r))
                    {
                        notSupplied++;
                    }
                }
            }
        }
        assertEquals(24, compared);
        assertTrue(notSupplied > 0, "the unmatched row P2 (not supplied) is among the rows");
        assertEquals("A", lk.columnReader(lb, "ARM", false).apply(3).getValue(),
                "a matched cell is the partner's (P1 again on row 3)");
    }
}
