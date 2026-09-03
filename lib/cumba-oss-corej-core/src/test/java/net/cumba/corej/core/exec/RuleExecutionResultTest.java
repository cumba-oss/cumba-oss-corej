package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleExecutionResultTest
{

    @Test
    void testHasViolations_true()
    {
        RuleExecutionResult result = RuleExecutionResult.builder().ruleId("CORE-001")
                .message("test").violations(List.of(new Violation(0, Map.of()))).totalRows(10)
                .build();

        assertTrue(result.hasViolations());
        assertEquals(1, result.getViolationCount());
    }


    @Test
    void testHasViolations_false_emptyList()
    {
        RuleExecutionResult result = RuleExecutionResult.builder().ruleId("CORE-001")
                .message("test").violations(List.of()).totalRows(10).build();

        assertFalse(result.hasViolations());
        assertEquals(0, result.getViolationCount());
    }


    @Test
    void testHasViolations_false_nullList()
    {
        RuleExecutionResult result = RuleExecutionResult.builder().ruleId("CORE-001")
                .message("test").violations(null).totalRows(10).build();

        assertFalse(result.hasViolations());
        assertEquals(0, result.getViolationCount());
    }


    @Test
    void testFields()
    {
        RuleExecutionResult result = RuleExecutionResult.builder().ruleId("CORE-123")
                .message("Error message").violations(List.of()).totalRows(42).build();

        assertEquals("CORE-123", result.getRuleId());
        assertEquals("Error message", result.getMessage());
        assertEquals(42, result.getTotalRows());
    }


    @Test
    void testNotTruncated_byDefault_usesListSize()
    {
        RuleExecutionResult result = RuleExecutionResult.builder().ruleId("CORE-001")
                .violations(List.of(new Violation(0, Map.of()), new Violation(1, Map.of())))
                .totalRows(10).build();

        // totalViolationCount defaults to -1 ⇒ getViolationCount() falls back to the list size.
        assertEquals(2, result.getViolationCount());
        assertFalse(result.isTruncated());
    }


    @Test
    void testTruncated_reportsTrueTotal()
    {
        RuleExecutionResult result = RuleExecutionResult.builder().ruleId("CORE-001")
                .violations(List.of(new Violation(0, Map.of()), new Violation(1, Map.of())))
                .totalViolationCount(2_000_000L).totalRows(5_000_000).build();

        assertEquals(2_000_000, result.getViolationCount());
        assertTrue(result.isTruncated());
        assertTrue(result.hasViolations());
    }


    @Test
    void testNotTruncated_whenTotalEqualsListSize()
    {
        RuleExecutionResult result = RuleExecutionResult.builder().ruleId("CORE-001")
                .violations(List.of(new Violation(0, Map.of()))).totalViolationCount(1L)
                .totalRows(10).build();

        assertEquals(1, result.getViolationCount());
        assertFalse(result.isTruncated());
    }


    @Test
    void testRuntimeMillis_defaultsToMinusOne()
    {
        RuleExecutionResult result = RuleExecutionResult.builder().ruleId("CORE-001")
                .violations(List.of()).totalRows(10).build();

        assertEquals(-1, result.getRuntimeMillis());
    }


    @Test
    void testRuntimeMillis_builderSetsIt_zeroIsValid()
    {
        RuleExecutionResult result = RuleExecutionResult.builder().ruleId("CORE-001")
                .violations(List.of()).totalRows(10).runtimeMillis(0).build();

        assertEquals(0, result.getRuntimeMillis());
    }


    @Test
    void testToBuilder_stampsRuntime_preservesOtherFields()
    {
        RuleExecutionResult base = RuleExecutionResult.builder().ruleId("CORE-001")
                .violations(List.of(new Violation(0, Map.of()))).totalViolationCount(2_000_000L)
                .totalRows(5_000_000).build();

        RuleExecutionResult stamped = base.toBuilder().runtimeMillis(123).build();

        assertEquals(123, stamped.getRuntimeMillis());
        // toBuilder must carry every other field through unchanged (esp. the findings-cap total).
        assertEquals("CORE-001", stamped.getRuleId());
        assertEquals(2_000_000, stamped.getViolationCount());
        assertTrue(stamped.isTruncated());
        assertEquals(5_000_000, stamped.getTotalRows());
    }

}
