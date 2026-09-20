package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import net.cumba.corej.core.expr.RuleDefinitionException;
import net.cumba.corej.core.expr.convert.OperationExpressionParser;
import net.cumba.corej.core.model.Operation;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * ⭐ Phase 6b (D3/D14) — <b>R11 is closed by construction</b>: an operation's target parameter
 * accepts any expression of the right type. {@code max(num(WEIGHT))} — the exact spelling R11
 * documented as impossible ("excluded from today's gate only because
 * {@code OperationExpressionParser.stringOf} throws on any {@code Call}") — now loads, and the
 * executor materialises the computed target into a synthetic column
 * ({@link TargetExpressionMaterializer}) over which the untouched {@code max} domain logic runs.
 */
class ComputedTargetOperationTest
{

    private static Operation formB(String expression)
    {
        Operation op = new Operation();
        op.setId("$x");
        op.setExpression(expression);
        return OperationExpressionParser.normalize(op);
    }


    @Test
    void maxOverAComputedNumericTargetEvaluates()
    {
        // R11's named case: a Char-declared column whose content is numeric, read through num().
        IDataTable t = MockTable.of().col("WT", "70", "80", "75.5").build();
        Operation op = formB("max(num(WT))");
        assertNotNull(op.getNameExpr(), "the computed target survives normalisation");
        assertNull(op.getName());

        Object result = OperationExecutor.executeOne(op, t, name -> null, null, Map.of());
        assertEquals(80.0, ((Number) result).doubleValue(), 1e-9);
    }


    @Test
    void distinctOverAComputedStringTargetEvaluates()
    {
        IDataTable t = MockTable.of().col("ARM", "a", "A", "b").build();
        Operation op = formB("distinct(upper(ARM))");
        Object result = OperationExecutor.executeOne(op, t, name -> null, null, Map.of());
        assertTrue(result instanceof java.util.Collection<?> c && c.size() == 2 && c.contains("A")
                && c.contains("B"), String.valueOf(result));
    }


    @Test
    void groupedMaxOverAComputedTargetEvaluatesPerGroup()
    {
        IDataTable t = MockTable.of().col("USUBJID", "S1", "S1", "S2").col("WT", "70", "80", "75.5")
                .build();
        Operation op = formB("max(num(WT), group=[USUBJID])");
        Object result = OperationExecutor.executeOne(op, t, name -> null, null, Map.of());
        assertTrue(result instanceof GroupedResult, String.valueOf(result));
    }


    /** An unresolvable column inside the expression answers the operation's own EmptyResult. */
    @Test
    void unresolvableColumnInsideTheTargetAnswersTheEmptyResult()
    {
        IDataTable t = MockTable.of().col("WT", "70").build();
        Operation op = formB("max(num(NOSUCH))");
        assertNull(OperationExecutor.executeOne(op, t, name -> null, null, Map.of()),
                "max declares EmptyResult.MISSING");
    }


    @Test
    void computedTargetIsRefusedOutsideTheRowReadingOperations()
    {
        RuleDefinitionException ex = assertThrows(RuleDefinitionException.class,
                () -> formB("extract_metadata(upper(X))"));
        assertTrue(ex.getMessage().contains("does not support a computed target"), ex.getMessage());
    }


    @Test
    void domainPrefixInsideAComputedTargetIsRefusedAtLoad()
    {
        RuleDefinitionException ex = assertThrows(RuleDefinitionException.class,
                () -> formB("max(num(--DTC))"));
        assertTrue(ex.getMessage().contains("--"), ex.getMessage());
    }


    /** The synthetic name never collides with an authored column. */
    @Test
    void syntheticNameIsMadeUniqueAgainstTheTargetTable()
    {
        IDataTable t = MockTable.of().col(TargetExpressionMaterializer.SYNTHETIC_NAME, "9", "9")
                .col("WT", "70", "80").build();
        Operation op = formB("max(num(WT))");
        Object result = OperationExecutor.executeOne(op, t, name -> null, null, Map.of());
        assertEquals(80.0, ((Number) result).doubleValue(), 1e-9);
    }


    /** The materialised view serves both value channels of the synthetic column. */
    @Test
    void materializedColumnServesBothValueChannels()
    {
        IDataTable t = MockTable.of().col("WT", "70", (String) null).build();
        Operation op = formB("max(num(WT))");
        TargetExpressionMaterializer.Materialized m = TargetExpressionMaterializer.materialize(op,
                t, Map.of());
        assertNotNull(m);
        int idx = m.table().getMetaData()
                .getColumnIndex(TargetExpressionMaterializer.SYNTHETIC_NAME);
        assertTrue(idx >= 0);
        assertEquals(70.0, ((Number) m.table().getValue(0, idx)).doubleValue(), 1e-9,
                "the resolved channel");
        assertTrue(m.table().getDataValue(1, idx).isMissingOrInvalid(), "the typed cell channel");
        assertEquals(TargetExpressionMaterializer.SYNTHETIC_NAME, m.op().getName());
        assertNull(m.op().getNameExpr());
    }


    /** D19a still holds around the computed target: at most one positional. */
    @Test
    void computedTargetKeepsThePositionalDiscipline()
    {
        Operation op = new Operation();
        op.setId("$x");
        op.setExpression("max(num(WT), num(HT))");
        RuleDefinitionException ex = assertThrows(RuleDefinitionException.class,
                () -> OperationExpressionParser.normalize(op));
        assertTrue(ex.getMessage().contains("at most one positional"), ex.getMessage());
    }

}
