package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Phase 5 — a NAME operand may be written as a string literal ({@code has_multiple_values_for("X",
 * "K")}) for the group operators (resolved via {@code groupOperandName}), equivalent by definition
 * to the bare-reference form. The exists family and {@code var_*}/{@code ds_*} metadata accessors
 * already accepted string-literal names; this extends the same to the ~12 group operators.
 */
class NameLiteralOperandTest
{

    private static IDataTable table()
    {
        return MockTable.of().name("AE").col("USUBJID", "S1", "S1").col("AEDECOD", "A", "B")
                .colLong("AESEQ", 1L, 2L).build();
    }


    private static EvaluationContext ctx()
    {
        return EvaluationContext.builder().table(table()).domainName("AE").build();
    }


    private static boolean supported(String expr)
    {
        return NativeExprEvaluator.isSupported(CheckExpressionParser.parse(expr));
    }


    private static BitSet eval(String expr)
    {
        return NativeExprEvaluator.evaluate(CheckExpressionParser.parse(expr), ctx());
    }


    @Test
    void groupOperatorsAcceptStringLiteralNames()
    {
        assertTrue(supported("has_multiple_values_for(\"AEDECOD\", \"USUBJID\")"));
        assertTrue(supported("is_not_unique_relationship(\"AEDECOD\", \"USUBJID\")"));
        assertTrue(supported("has_same_values(\"AEDECOD\", within=[\"USUBJID\"])"));
    }


    @Test
    void stringLiteralNameEqualsBareRef()
    {
        assertEquals(eval("has_multiple_values_for(AEDECOD, USUBJID)"),
                eval("has_multiple_values_for(\"AEDECOD\", \"USUBJID\")"));
        assertEquals(eval("not is_not_unique_relationship(AEDECOD, USUBJID)"),
                eval("not is_not_unique_relationship(\"AEDECOD\", \"USUBJID\")"));
    }
}
