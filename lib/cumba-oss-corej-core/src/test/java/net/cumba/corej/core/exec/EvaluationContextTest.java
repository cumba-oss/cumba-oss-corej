package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EvaluationContextTest
{

    @Test
    void testRowCount()
    {
        IDataTable table = MockTable.of().col("X", "a", "b", "c").build();
        EvaluationContext ctx = EvaluationContext.builder().table(table).build();
        assertEquals(3, ctx.rowCount());
    }


    @Test
    void testDefaultVariables_empty()
    {
        IDataTable table = MockTable.of().col("X", "1").build();
        EvaluationContext ctx = EvaluationContext.builder().table(table).build();
        assertNotNull(ctx.getVariables());
        assertTrue(ctx.getVariables().isEmpty());
    }


    @Test
    void testDefaultDatasetResolver_returnsNull()
    {
        IDataTable table = MockTable.of().col("X", "1").build();
        EvaluationContext ctx = EvaluationContext.builder().table(table).build();
        assertNull(ctx.getDatasetResolver().resolve("anything"));
    }


    @Test
    void testCustomVariables()
    {
        IDataTable table = MockTable.of().col("X", "1").build();
        Map<String, Object> vars = Map.of("$var1", "hello", "$var2", 42L);
        EvaluationContext ctx = EvaluationContext.builder().table(table).variables(vars).build();
        assertEquals("hello", ctx.getVariables().get("$var1"));
        assertEquals(42L, ctx.getVariables().get("$var2"));
    }


    @Test
    void testCustomDatasetResolver()
    {
        IDataTable table = MockTable.of().col("X", "1").build();
        IDataTable dmTable = MockTable.of().col("Y", "2").build();

        EvaluationContext ctx = EvaluationContext.builder().table(table)
                .datasetResolver(name -> "DM".equals(name) ? dmTable : null).build();

        assertSame(dmTable, ctx.getDatasetResolver().resolve("DM"));
        assertNull(ctx.getDatasetResolver().resolve("AE"));
    }

    // ---- resolveVariable: null-id support ----------------------------------


    /**
     * Regression: {@link EvaluationContext#resolveVariable} must accept a {@code null} id on the
     * DEFAULT variables map. The default is {@code Map.of()}, and the immutable maps throw
     * {@link NullPointerException} on {@code get(null)} rather than returning {@code null} the way
     * a {@link java.util.HashMap} does — so before the guard, probing with an unresolved cursor
     * name crashed on any context built without an explicit {@code .variables(...)}.
     */
    @Test
    void resolveVariable_nullId_onDefaultVariablesMap()
    {
        IDataTable table = MockTable.of().col("X", "1").build();
        EvaluationContext ctx = EvaluationContext.builder().table(table).build();
        assertNull(ctx.resolveVariable(null), "a null id names no variable");
    }


    /** The same must hold for a NON-empty immutable map, which also rejects a null key. */
    @Test
    void resolveVariable_nullId_onPopulatedImmutableMap()
    {
        IDataTable table = MockTable.of().col("X", "1").build();
        EvaluationContext ctx = EvaluationContext.builder().table(table)
                .variables(Map.of("$a", "1", "$b", 2L)).build();
        assertNull(ctx.resolveVariable(null));
        assertEquals("1", ctx.resolveVariable("$a"), "real lookups are unaffected");
    }


    /** ...and for a null-tolerant map, where the guard must not change the existing answer. */
    @Test
    void resolveVariable_nullId_onNullTolerantMap()
    {
        IDataTable table = MockTable.of().col("X", "1").build();
        java.util.Map<String, Object> vars = new java.util.HashMap<>();
        vars.put("$a", "1");
        EvaluationContext ctx = EvaluationContext.builder().table(table).variables(vars).build();
        assertNull(ctx.resolveVariable(null), "unchanged for a HashMap, which tolerates get(null)");
        assertEquals("1", ctx.resolveVariable("$a"));
        assertNull(ctx.resolveVariable("$missing"), "an absent id is still a miss");
    }


    /** A null id must short-circuit BEFORE any LazyValue is forced. */
    @Test
    void resolveVariable_nullId_doesNotForceALazyValue()
    {
        IDataTable table = MockTable.of().col("X", "1").build();
        java.util.concurrent.atomic.AtomicInteger forced = new java.util.concurrent.atomic.AtomicInteger();
        java.util.Map<String, Object> vars = new java.util.HashMap<>();
        vars.put("$op", new LazyValue<Object>(() ->
        {
            forced.incrementAndGet();
            return "computed";
        }));
        EvaluationContext ctx = EvaluationContext.builder().table(table).variables(vars).build();

        assertNull(ctx.resolveVariable(null));
        assertEquals(0, forced.get(), "a null probe must not force any Operation");

        assertEquals("computed", ctx.resolveVariable("$op"), "a real read still unwraps LazyValue");
        assertEquals(1, forced.get());
    }

}
