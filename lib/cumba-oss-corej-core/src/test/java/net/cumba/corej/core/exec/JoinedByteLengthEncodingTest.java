package net.cumba.corej.core.exec;

import static net.cumba.corej.core.exec.QualifiedNameFixture.check;
import static net.cumba.corej.core.exec.QualifiedNameFixture.exactInventory;
import static net.cumba.corej.core.exec.QualifiedNameFixture.executed;
import static net.cumba.corej.core.exec.QualifiedNameFixture.loadClean;
import static net.cumba.corej.core.exec.QualifiedNameFixture.outcome;
import static net.cumba.corej.core.exec.QualifiedNameFixture.ruleJson;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-bugfix-engine-define} JB (T14, by precedent D72: <i>a merged column behaves in every
 * respect like a primary column</i>): a byte count over a qualified joined operand {@code J.X} with
 * no written {@code encoding=} defaults to <b>J's</b> table {@code Encoding}, the file the bytes
 * come from — not the evaluated table's. Before 2026-10-16 both {@code max_value_length} and
 * {@code len} / {@code length} read the primary's {@code Encoding} for a joined column (B2's
 * review, 2026-10-15; 0 corpus carriers).
 */
class JoinedByteLengthEncodingTest
{

    /** {@code é}: 1 windows-1252 byte, 2 UTF-8 bytes. */
    private static final String E_ACUTE = "é";

    private static final String MATCH = "\"Match_Datasets\":[{\"Name\":\"J\",\"Keys\":[\"K\"],"
            + "\"Join_Type\":\"left\"}]";

    private static IDataTable primary(String encoding)
    {
        return MockTable.of().col("K", "k1").col("S", E_ACUTE).metaValue("Encoding", encoding)
                .name("P").build();
    }


    private static IDataTable joined(String encoding)
    {
        return MockTable.of().col("K", "k1").col("S", E_ACUTE).metaValue("Encoding", encoding)
                .name("J").build();
    }


    /** Rows fired by {@code expression} on a windows-1252 primary joined to a UTF-8 J. */
    private static List<Long> fired(String expression)
    {
        Rule rule = loadClean(
                ruleJson("Record", MATCH + "," + check(expression) + "," + outcome("K")), true);
        IDataTable p = primary("windows-1252");
        return QualifiedNameFixture.rows(executed(rule, p, exactInventory(p, joined("UTF-8"))));
    }


    @Test
    void aJoinedColumnsByteCountUsesTheJoinedTablesEncoding()
    {
        assertEquals(List.of(1L), fired("max_value_length(J.S, unit=\"bytes\") == 2"),
                "J declares UTF-8: é is 2 bytes there (the primary's windows-1252 would say 1)");
        assertEquals(List.of(1L), fired("len(J.S, unit=\"bytes\") == 2"));
        assertEquals(List.of(1L), fired("length(J.S, unit=\"bytes\") == 2"));
    }


    @Test
    void theControls()
    {
        // a written encoding= always wins
        assertEquals(List.of(1L),
                fired("max_value_length(J.S, unit=\"bytes\", encoding=\"windows-1252\") == 1"));
        assertEquals(List.of(1L),
                fired("len(J.S, unit=\"bytes\", encoding=\"windows-1252\") == 1"));
        // a primary column keeps the primary's Encoding
        assertEquals(List.of(1L), fired("max_value_length(S, unit=\"bytes\") == 1"));
        assertEquals(List.of(1L), fired("len(S, unit=\"bytes\") == 1"));
        // a compound argument keeps the evaluated table's Encoding (the bytes are no longer a
        // column's cells)
        assertEquals(List.of(1L), fired("len(upper(J.S), unit=\"bytes\") == 1"));
    }
}
