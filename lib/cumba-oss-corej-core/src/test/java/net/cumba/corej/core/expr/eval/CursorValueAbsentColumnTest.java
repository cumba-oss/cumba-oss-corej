package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.typed.TypeExpectations;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-column-typed-function-absent-type} A5 (the fold): <b>{@code value()} over a cursor
 * variable the evaluated table lacks takes its use-derived kind</b>, as the column read directly
 * would — {@code MissingValue.MIS} at a numeric-expected site, {@code ""} at a character or untyped
 * one (D34 #3/#4, D76a). The cursor names a column the data lacks only in the Define universe (an
 * ItemDef with no data column); a {@code null} cursor names no variable at all and stays
 * {@code MIS} in every position.
 *
 * <p>
 * Evaluated through the compiled plan over a context whose numeric-expected sites are the real
 * stage-A walk's ({@link TypeExpectations#numericDynamicSites()}), so the test covers the
 * recording, the marker the function answers and the compiler arm that resolves it. Positions as in
 * {@code ColumnTypedAbsentDefaultTest}: {@code value() != ""} fires iff the value is a missing
 * (D12), {@code len(value()) == 0} fires iff it is {@code ""} ({@code len} records no kind).
 * </p>
 */
class CursorValueAbsentColumnTest
{

    private static final IDataTable DM = MockTable.of().name("DM").col("USUBJID", "S1", "S2", "S3")
            .build();

    private static int fired(String check, @org.jspecify.annotations.Nullable String cursor)
    {
        Expr expr = CheckExpressionParser.parse(check);
        EvaluationContext.EvaluationContextBuilder b = EvaluationContext.builder().table(DM)
                .numericExpectedDynamicSites(
                        TypeExpectations.of(List.of(expr)).numericDynamicSites());
        if (cursor != null)
        {
            b.variables(Map.of("variable_name", cursor));
        }
        return NativeExprEvaluator.evaluate(expr, b.build()).cardinality();
    }


    @Test
    void anAbsentCursorColumnTakesItsSitesKind()
    {
        assertEquals(3, fired("value() < 3", "ZZ"), "a numeric position reads MIS (A1)");
        assertEquals(0, fired("value() != \"\"", "ZZ"), "a character position reads \"\"");
        assertEquals(3, fired("len(value()) == 0", "ZZ"), "no typed position reads \"\" (A4)");
        assertEquals(3, fired("value() < 3 and value() != \"\"", "ZZ"),
                "numeric wins over character (A3): MIS, so the character read is MIS too");
    }


    @Test
    void aNullCursorNamesNoVariableAndStaysMis()
    {
        assertEquals(3, fired("value() != \"\"", null), "no cursor: MIS in a character position");
        assertEquals(0, fired("len(value()) == 0", null), "no cursor: MIS with no position");
    }


    @Test
    void aPresentCursorColumnReadsItsCells()
    {
        assertEquals(3, fired("value() != \"\"", "USUBJID"));
        assertEquals(0, fired("len(value()) == 0", "USUBJID"));
    }
}
