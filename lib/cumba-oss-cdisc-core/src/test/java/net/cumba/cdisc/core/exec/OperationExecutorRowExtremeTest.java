package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * EC-8 — {@code row_max} / {@code row_min} horizontal reducer over a {@code name_pattern} column
 * set, exercised through {@link OperationExecutor#executeOne} with the raw {@link GroupedResult}
 * inspected. The result map is keyed by the matched columns themselves (NUL-separated cell values,
 * in the table's column order).
 */
class OperationExecutorRowExtremeTest
{

    private static final String NUL = "\0";

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    private static Operation rowOp(String operator, String pattern)
    {
        Operation op = new Operation();
        op.setId("$ext");
        op.setOperator(operator);
        op.setNamePattern(pattern);
        return op;
    }


    private static GroupedResult run(Operation op, IDataTable table)
    {
        return (GroupedResult) OperationExecutor.executeOne(op, table, NO_RESOLVER, null,
                new HashMap<>());
    }


    @Test
    void numericMode_max_picksNumericExtremeReturnsOriginalString()
    {
        // Two matched numeric columns; numeric mode ⇒ 12 > 9 (not lexicographic "9" > "12").
        IDataTable ds = MockTable.of().col("TR01N", "9", "3").col("TR02N", "12", "20")
                .col("OTHER", "x", "y").name("TR").build();
        GroupedResult gr = run(rowOp("row_max", "^TR\\d+N$"), ds);
        assertEquals(List.of("TR01N", "TR02N"), gr.groupColumns());
        assertEquals("12", gr.results().get("9" + NUL + "12"));
        assertEquals("20", gr.results().get("3" + NUL + "20"));
    }


    @Test
    void numericMode_min_picksNumericMinimum()
    {
        IDataTable ds = MockTable.of().col("TR01N", "9", "3").col("TR02N", "12", "20").name("TR")
                .build();
        GroupedResult gr = run(rowOp("row_min", "^TR\\d+N$"), ds);
        assertEquals("9", gr.results().get("9" + NUL + "12"));
        assertEquals("3", gr.results().get("3" + NUL + "20"));
    }


    @Test
    void stringMode_max_lexicographicOnSamePrecisionIso()
    {
        // ISO-8601 same-precision dates: plain lexicographic max = latest date (raw string
        // returned).
        IDataTable ds = MockTable.of().col("TR01EDT", "2020-01-10", "2019-12-31")
                .col("TR02EDT", "2020-03-01", "2020-01-01").name("TR").build();
        GroupedResult gr = run(rowOp("row_max", "^TR\\d+EDT$"), ds);
        assertEquals("2020-03-01", gr.results().get("2020-01-10" + NUL + "2020-03-01"));
        assertEquals("2020-01-01", gr.results().get("2019-12-31" + NUL + "2020-01-01"));
    }


    @Test
    void stringMode_min_lexicographicOnIso()
    {
        IDataTable ds = MockTable.of().col("TR01EDT", "2020-01-10", "2019-12-31")
                .col("TR02EDT", "2020-03-01", "2020-01-01").name("TR").build();
        GroupedResult gr = run(rowOp("row_min", "^TR\\d+EDT$"), ds);
        assertEquals("2020-01-10", gr.results().get("2020-01-10" + NUL + "2020-03-01"));
        assertEquals("2019-12-31", gr.results().get("2019-12-31" + NUL + "2020-01-01"));
    }


    @Test
    void mixedPopulated_onlyPopulatedCellsContribute()
    {
        // Row 1: TR01EDT populated, TR02EDT empty ⇒ result is the single populated cell.
        // Row 2: TR01EDT missing (null), TR02EDT populated ⇒ result is the populated cell.
        // ⚠ Key spelling re-pointed by W38-A1 (Fix #249): row 2's genuinely missing TR01EDT
        // component renders the MIS marker token in the group key, no longer "" — a missing and
        // an empty cell are distinct key identities (row 1's empty TR02EDT still renders "").
        IDataTable ds = MockTable.of().col("TR01EDT", "2020-01-10", null)
                .col("TR02EDT", "", "2021-05-05").name("TR").build();
        GroupedResult gr = run(rowOp("row_max", "^TR\\d+EDT$"), ds);
        assertEquals("2020-01-10", gr.results().get("2020-01-10" + NUL + ""));
        assertEquals("2021-05-05", gr.results().get("\u0001MIS" + NUL + "2021-05-05"));
    }


    /**
     * EC-51 — the whitespace change reaches {@code row_max}/{@code row_min} in a wider way than "a
     * blank stops winning the min": {@code rowExtreme}'s mode gate needs <b>every</b> value to
     * match {@code ROW_EXTREME_NUMERIC}, so a whitespace cell's mere presence used to force the
     * whole row lexicographic. Dropping it can flip the row into numeric mode and move the
     * <b>max</b>, which is the case this pins.
     */
    @Test
    void whitespaceCell_noLongerForcesLexicographicMode()
    {
        // Lexicographically "9" > "12"; numerically 12 > 9. Before EC-51 the " " cell forced
        // string mode and row_max was "9"; now it is dropped and numeric mode gives "12".
        IDataTable ds = MockTable.of().col("TR01EDT", " ").col("TR02EDT", "9").col("TR03EDT", "12")
                .name("TR").build();

        GroupedResult max = run(rowOp("row_max", "^TR\\d+EDT$"), ds);
        assertEquals("12", max.results().values().iterator().next(),
                "dropping the whitespace cell re-enables numeric mode, so 12 beats 9");

        GroupedResult min = run(rowOp("row_min", "^TR\\d+EDT$"), ds);
        assertEquals("9", min.results().values().iterator().next(),
                "the whitespace cell used to win the min outright");
    }


    @Test
    void allEmptyRow_isOmittedFromResults()
    {
        // Every matched cell empty/missing on the row ⇒ no entry (absent key ⇒ null default).
        IDataTable ds = MockTable.of().col("TR01EDT", "2020-01-10", "")
                .col("TR02EDT", "2020-02-01", null).name("TR").build();
        GroupedResult gr = run(rowOp("row_max", "^TR\\d+EDT$"), ds);
        assertTrue(gr.results().containsKey("2020-01-10" + NUL + "2020-02-01"));
        // Row 2 (both empty/missing) produced no result entry.
        assertNull(gr.results().get("" + NUL + ""));
        assertEquals(1, gr.results().size());
    }


    @Test
    void noMatchingColumns_returnsNull()
    {
        IDataTable ds = MockTable.of().col("AGE", "40").col("SEX", "M").name("DM").build();
        assertNull(run(rowOp("row_max", "^TR\\d+EDT$"), ds));
    }


    @Test
    void emptyOrInvalidPattern_returnsNull()
    {
        IDataTable ds = MockTable.of().col("TR01EDT", "2020-01-10").name("TR").build();
        assertNull(run(rowOp("row_max", ""), ds));
        assertNull(run(rowOp("row_max", "[unclosed"), ds));
    }


    @Test
    void singleMatchedColumn_returnsThatCell()
    {
        IDataTable ds = MockTable.of().col("TR01EDT", "2020-01-10", "2019-01-01").name("TR")
                .build();
        GroupedResult gr = run(rowOp("row_min", "^TR\\d+EDT$"), ds);
        assertEquals(List.of("TR01EDT"), gr.groupColumns());
        assertEquals("2020-01-10", gr.results().get("2020-01-10"));
        assertEquals("2019-01-01", gr.results().get("2019-01-01"));
    }
}
