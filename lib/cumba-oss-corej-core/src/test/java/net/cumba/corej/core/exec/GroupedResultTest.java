package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.GroupKey;
import net.cumba.datatable.values.MissingValue;
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

        // IDENTITY keys (PLAN-grouping-key-identity): one GroupKey of the columns' identities
        GroupedResult grouped = new GroupedResult(List.of("STUDYID", "USUBJID"),
                Map.of(GroupKey.of("STUDY1", "S01"), "result1", GroupKey.of("STUDY1", "S02"),
                        "result2", GroupKey.of("STUDY2", "S01"), "result3"));

        EvaluationContext ctx = EvaluationContext.builder().table(table).build();

        assertEquals("result1", grouped.getForRow(ctx, 0));
        assertEquals("result2", grouped.getForRow(ctx, 1));
        assertEquals("result3", grouped.getForRow(ctx, 2));

        // TEXT keys, the text-carried family's encoding, probe the "\0"-joined rendering
        GroupedResult text = new GroupedResult(List.of("STUDYID", "USUBJID"),
                Map.of("STUDY1\0S01", "t1", "STUDY2\0S01", "t3"), null, GroupedResult.KeyMode.TEXT,
                null);
        assertEquals("t1", text.getForRow(ctx, 0));
        assertNull(text.getForRow(ctx, 1));
        assertEquals("t3", text.getForRow(ctx, 2));
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
    void testGetForRow_missingValue_probesTheMissingIdentity()
    {
        // ⚠ Re-pointed by W38-A1 (Fix #249): a genuinely missing group-key cell used to probe the
        // "" key (the fold); it probes its missing identity, so a missing-keyed row finds the
        // missing-keyed group's value and can never read a literal-""-keyed group's. Since
        // PLAN-grouping-key-identity that identity is the MissingValue constant itself (IDENTITY
        // mode), and still the SOH marker token in TEXT mode.
        IDataTable table = MockTable.of().col("USUBJID", (String) null).build();

        GroupedResult grouped = new GroupedResult(List.of("USUBJID"),
                Map.of(MissingValue.MIS, "missing-group", "", "empty-group"));

        EvaluationContext ctx = EvaluationContext.builder().table(table).build();

        assertEquals("missing-group", grouped.getForRow(ctx, 0));

        GroupedResult text = new GroupedResult(List.of("USUBJID"),
                Map.of("\u0001MIS", "missing-group", "", "empty-group"), null,
                GroupedResult.KeyMode.TEXT, null);
        assertEquals("missing-group", text.getForRow(ctx, 0));
        // a text cell spelling the token is not the missing identity
        assertNull(grouped.getForRow(EvaluationContext.builder()
                .table(MockTable.of().col("USUBJID", "\u0001MIS").build()).build(), 0));
    }


    @Test
    void testRecordFields()
    {
        List<String> groupCols = List.of("A", "B");
        Map<String, Object> results = Map.of("key1", "val1");
        GroupedResult gr = new GroupedResult(groupCols, results);

        assertEquals(groupCols, gr.groupColumns());
        assertEquals(results, gr.results());
        assertEquals(GroupedResult.KeyMode.IDENTITY, gr.keyMode());
        assertNull(gr.keyTypes());
        assertNull(gr.missingKeyDefault());
    }

}
