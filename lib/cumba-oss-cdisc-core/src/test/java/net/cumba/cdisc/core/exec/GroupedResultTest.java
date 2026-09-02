package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupedResultTest
{

    @Test
    void testGetForRow_singleGroupColumn()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S01", "S02", "S01")
                .col("DTC", "2024-01-01", "2024-02-01", "2024-03-01").build();

        GroupedResult grouped = new GroupedResult(List.of("USUBJID"),
                Map.of("S01", "2024-01-01", "S02", "2024-02-01"));

        EvaluationContext ctx = EvaluationContext.builder().table(table).build();

        assertEquals("2024-01-01", grouped.getForRow(ctx, 0)); // S01
        assertEquals("2024-02-01", grouped.getForRow(ctx, 1)); // S02
        assertEquals("2024-01-01", grouped.getForRow(ctx, 2)); // S01 again
    }


    @Test
    void testGetForRow_multipleGroupColumns()
    {
        IDataTable table = MockTable.of().col("STUDYID", "STUDY1", "STUDY1", "STUDY2")
                .col("USUBJID", "S01", "S02", "S01").col("VALUE", "A", "B", "C").build();

        GroupedResult grouped = new GroupedResult(List.of("STUDYID", "USUBJID"), Map
                .of("STUDY1\0S01", "result1", "STUDY1\0S02", "result2", "STUDY2\0S01", "result3"));

        EvaluationContext ctx = EvaluationContext.builder().table(table).build();

        assertEquals("result1", grouped.getForRow(ctx, 0));
        assertEquals("result2", grouped.getForRow(ctx, 1));
        assertEquals("result3", grouped.getForRow(ctx, 2));
    }


    @Test
    void testGetForRow_missingGroupKey_returnsNull()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S99").build();

        GroupedResult grouped = new GroupedResult(List.of("USUBJID"), Map.of("S01", "value1"));

        EvaluationContext ctx = EvaluationContext.builder().table(table).build();

        assertNull(grouped.getForRow(ctx, 0));
    }


    @Test
    void testGetForRow_missingGroupColumn_usesEmptyString()
    {
        IDataTable table = MockTable.of().col("X", "1").build();

        GroupedResult grouped = new GroupedResult(List.of("NONEXISTENT"), Map.of("", "fallback"));

        EvaluationContext ctx = EvaluationContext.builder().table(table).build();

        // Missing column → empty string key
        assertEquals("fallback", grouped.getForRow(ctx, 0));
    }


    @Test
    void testGetForRow_missingValue_probesTheMarkerToken()
    {
        // ⚠ Re-pointed by W38-A1 (Fix #249): a genuinely missing group-key cell used to probe the
        // "" key (the fold); it now probes the MIS marker token, so a missing-keyed row finds the
        // missing-keyed group's value and can never read a literal-""-keyed group's.
        IDataTable table = MockTable.of().col("USUBJID", (String) null).build();

        GroupedResult grouped = new GroupedResult(List.of("USUBJID"),
                Map.of("\u0001MIS", "missing-group", "", "empty-group"));

        EvaluationContext ctx = EvaluationContext.builder().table(table).build();

        assertEquals("missing-group", grouped.getForRow(ctx, 0));
    }


    @Test
    void testRecordFields()
    {
        List<String> groupCols = List.of("A", "B");
        Map<String, Object> results = Map.of("key1", "val1");
        GroupedResult gr = new GroupedResult(groupCols, results);

        assertEquals(groupCols, gr.groupColumns());
        assertEquals(results, gr.results());
    }

}
