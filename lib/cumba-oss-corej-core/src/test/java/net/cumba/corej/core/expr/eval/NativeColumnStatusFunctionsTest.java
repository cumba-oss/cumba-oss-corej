package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-present-empty-column-fold} §4.4 — the column-status functions {@code var_is_empty}
 * (the rename of {@code var_is_null}), {@code var_is_single_value} and {@code var_is_all_missing}:
 * every content cell, the three argument shapes, and the six ABSENT cells (register
 * {@code D34 #3/#4}, {@code D43}, the type from the rule's expectation per {@code D76}: no numeric
 * expectation ⇒ char ⇒ {@code var_is_all_missing} FALSE). The dotted (joined) and SUPP-qualifier
 * scan arms are paired with the table arm on the same cells so they cannot drift.
 */
class NativeColumnStatusFunctionsTest
{

    private static BitSet eval(String source, EvaluationContext ctx)
    {
        return NativeExprEvaluator.evaluate(CheckExpressionParser.parse(source), ctx);
    }


    private static BitSet all(int n)
    {
        BitSet b = new BitSet(n);
        b.set(0, n);
        return b;
    }

    private static final BitSet NONE = new BitSet();

    /**
     * FILLED+MULTI (F), FILLED+SINGLE (S), all-"" (E), "" mixed with missing (X), all-missing (M).
     */
    private static IDataTable cells()
    {
        // MockTable: a null entry of colSasMissing is the missing marker (MissingValue.MIS).
        return MockTable.of().name("AE").col("F", "a", "b").col("S", "a", "a").col("E", "", "")
                .colSasMissing("X", "", null).colSasMissing("M", null, null).build();
    }


    private static EvaluationContext ctx(IDataTable t)
    {
        return EvaluationContext.builder().table(t).build();
    }


    @Test
    void varIsEmptyIsTrueOnBlankColumnsOnly()
    {
        EvaluationContext c = ctx(cells());
        assertEquals(NONE, eval("var_is_empty(F)", c));
        assertEquals(NONE, eval("var_is_empty(S)", c));
        assertEquals(all(2), eval("var_is_empty(E)", c));
        assertEquals(all(2), eval("var_is_empty(X)", c));
        assertEquals(all(2), eval("var_is_empty(M)", c));
        assertEquals(all(2), eval("var_is_empty(MM)", ctx(distinctMarkers())));
    }


    @Test
    void varIsSingleValueFollowsTheUniformity()
    {
        EvaluationContext c = ctx(cells());
        assertEquals(NONE, eval("var_is_single_value(F)", c));
        assertEquals(all(2), eval("var_is_single_value(S)", c));
        assertEquals(all(2), eval("var_is_single_value(E)", c), "all \"\" is one value");
        assertEquals(NONE, eval("var_is_single_value(X)", c), "\"\" is not a missing value");
        assertEquals(all(2), eval("var_is_single_value(M)", c));
        assertEquals(NONE, eval("var_is_single_value(MM)", ctx(distinctMarkers())), ".A is not ._");
    }


    @Test
    void varIsAllMissingIsStrictlyMissing()
    {
        EvaluationContext c = ctx(cells());
        assertEquals(NONE, eval("var_is_all_missing(F)", c));
        assertEquals(NONE, eval("var_is_all_missing(E)", c), "D50: \"\" is not missing");
        assertEquals(NONE, eval("var_is_all_missing(X)", c));
        assertEquals(all(2), eval("var_is_all_missing(M)", c));
        assertEquals(all(2), eval("var_is_all_missing(MM)", ctx(distinctMarkers())),
                "any uniformity");
    }


    /** A real column-backed table whose MM holds two DISTINCT missing markers (.A and ._). */
    private static IDataTable distinctMarkers()
    {
        net.cumba.datatable.DataTableColumnMeta cm = net.cumba.datatable.DataTableColumnMeta
                .builder().index(0).name("MM").type(net.cumba.datatable.values.DataValueType.DOUBLE)
                .build();
        net.cumba.datatable.DataTableMeta meta = net.cumba.datatable.DataTableMeta.builder()
                .name("AE").setColumns(cm).rowCount(2).totalRowCount(2).build();
        net.cumba.datatable.impl.CachedDataTableColumn col = new net.cumba.datatable.impl.CachedDataTableColumn(
                0, net.cumba.datatable.values.DataValueType.DOUBLE);
        col.addElement(net.cumba.datatable.values.MissingValue.MIS_A);
        col.addElement(net.cumba.datatable.values.MissingValue.MIS__);
        return new net.cumba.datatable.impl.ColumnCachedDataTable(meta, col);
    }


    @Test
    void theThreeArgumentShapes()
    {
        EvaluationContext cursor = EvaluationContext.builder().table(cells())
                .variables(Map.of("variable_name", "E")).build();
        assertEquals(all(2), eval("var_is_empty(varname())", cursor));
        assertEquals(all(2), eval("var_is_single_value(variable_name)", cursor));
        assertEquals(NONE, eval("var_is_all_missing(varname())", cursor));
        EvaluationContext c = ctx(cells());
        assertEquals(all(2), eval("var_is_empty(\"E\")", c), "a string literal names the column");
        assertEquals(all(2), eval("var_is_single_value(\"S\")", c));
        assertEquals(all(2), eval("var_is_all_missing(\"M\")", c));
    }


    @Test
    void theSixAbsentCells()
    {
        // ZZ is absent. D34 #3/#4: a present column holding its type's default on every row —
        // "" for char, MIS for numeric; the type is the RULE's expectation (D76).
        EvaluationContext charExpected = ctx(cells());
        EvaluationContext numericExpected = EvaluationContext.builder().table(cells())
                .numericExpectedColumns(Set.of("ZZ")).build();
        assertEquals(all(2), eval("var_is_empty(ZZ)", charExpected), "absent char: all \"\"");
        assertEquals(all(2), eval("var_is_empty(ZZ)", numericExpected), "absent numeric: all MIS");
        assertEquals(all(2), eval("var_is_single_value(ZZ)", charExpected));
        assertEquals(all(2), eval("var_is_single_value(ZZ)", numericExpected));
        assertEquals(NONE, eval("var_is_all_missing(ZZ)", charExpected),
                "no numeric expectation ⇒ char ⇒ \"\" on every row ⇒ FALSE (D76a)");
        assertEquals(all(2), eval("var_is_all_missing(ZZ)", numericExpected),
                "numeric-expected ⇒ MIS on every row ⇒ TRUE");
    }


    @Test
    void zeroRows()
    {
        IDataTable empty = MockTable.of().name("AE").col("E").build();
        EvaluationContext c = ctx(empty);
        assertEquals(NONE, eval("var_is_empty(E)", c), "no row to paint (the verdict is TRUE)");
        assertEquals(NONE, eval("var_is_single_value(E)", c));
        assertEquals(NONE, eval("var_is_single_value(ZZ)", c), "rows > 0 is part of the fact");
    }


    @Test
    void theRetiredNameIsNotAnAlias()
    {
        EvaluationContext c = ctx(cells());
        try
        {
            eval("var_is_null(E)", c);
        }
        catch (RuntimeException e)
        {
            String message = String.valueOf(e.getMessage());
            assertTrue(message.contains("renamed to var_is_empty"), message);
            return;
        }
        throw new AssertionError(
                "var_is_null must be a load error with a hint (D10), not an alias");
    }
}
