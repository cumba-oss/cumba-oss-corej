package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import net.cumba.corej.core.expr.eval.DataValues;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.IDataValue;
import org.junit.jupiter.api.Test;

/**
 * Survivor pins for {@link ScalarSemantics}' equality and numeric-coercion helpers — the code that
 * decides whether two scalar values are "the same". A defect here silently mis-compares lab values,
 * doses and ages while the validation report still looks clean, so every branch is pinned with an
 * exact verdict and a negative twin.
 */
class ScalarSemanticsValueCoercionPinsTest
{

    private static IDataValue cell(IDataTable t, String column, long row)
    {
        return t.getColumn(t.getMetaData().getColumnIndex(column)).getDataValue(row);
    }

    // -------------------------------------------------------------------------
    // isNumericMember — the numeric IN-list anchor
    // -------------------------------------------------------------------------


    /**
     * Pins the null-probe guard (line 338): a null cell is never a member — replacing the return
     * with true would make every numeric not-in-list rule silent on absent cells.
     */
    @Test
    void isNumericMemberNullAndNonNumericProbesAreNeverMembers()
    {
        Set<Double> members = Set.of(1.0, 2.0);
        assertFalse(ScalarSemantics.isNumericMember(null, members));
        assertFalse(ScalarSemantics.isNumericMember(DataValues.of(""), members),
                "a blank parses to NaN and is never a member");
        assertFalse(ScalarSemantics.isNumericMember(DataValues.of("abc"), members));
        // Positive twins: "1.0" and "01" both parse to a member.
        assertTrue(ScalarSemantics.isNumericMember(DataValues.of("1.0"), members));
        assertTrue(ScalarSemantics.isNumericMember(DataValues.of("01"), members));
        assertFalse(ScalarSemantics.isNumericMember(DataValues.of("3"), members));
    }

    // -------------------------------------------------------------------------
    // tryNumericLhs / tryNumericRhs / comparisonLhsAsDouble — comparison coercions
    // -------------------------------------------------------------------------


    /**
     * Pins the declared-type gate (line 372): LONG and DOUBLE cells coerce, a STRING cell never
     * does — even when its content parses ({@code DataValues.of("5")} parses to 5.0 via
     * getValueAsDouble, so a negated type check would wrongly return 5.0 instead of null).
     */
    @Test
    void tryNumericLhsCoercesOnlyDeclaredNumericTypes()
    {
        IDataTable t = MockTable.of().colLong("L", 5L).colDouble("D", 2.5).build();
        assertEquals(5.0, ScalarSemantics.tryNumericLhs(cell(t, "L", 0)));
        assertEquals(2.5, ScalarSemantics.tryNumericLhs(cell(t, "D", 0)));
        assertNull(ScalarSemantics.tryNumericLhs(DataValues.of("5")),
                "a STRING cell is never coerced, even if its content parses");
    }


    /**
     * Pins tryNumericRhs's fallthrough (line 402): a value that is neither Number nor String
     * answers null, not 0 — a 0 would make every date comparison against it fire as if the RHS were
     * the epoch.
     */
    @Test
    void tryNumericRhsAnswersNullForNonNumberNonString()
    {
        assertNull(ScalarSemantics.tryNumericRhs(true));
        assertNull(ScalarSemantics.tryNumericRhs(null));
        assertNull(ScalarSemantics.tryNumericRhs("abc"));
        // Positive twins.
        assertEquals(5.0, ScalarSemantics.tryNumericRhs(5));
        assertEquals(5.5, ScalarSemantics.tryNumericRhs("5.5"));
    }


    /**
     * Pins the char-cell parse path of comparisonLhsAsDouble (lines 458/462): a Char cell holding
     * numeric text (the --ORNRHI reference-range shape) parses from its string form; a genuinely
     * textual cell answers null (no violation), NOT 0 — a 0 would make {@code AGE < 18}-style rules
     * fire on free-text cells.
     */
    @Test
    void comparisonLhsAsDoubleParsesCharCellsAndRejectsText()
    {
        IDataTable t = MockTable.of().col("X", "12.5", "abc", "").build();
        assertEquals(12.5, ScalarSemantics.comparisonLhsAsDouble(cell(t, "X", 0)),
                "a Char cell carrying numeric text parses (reference-range case)");
        assertNull(ScalarSemantics.comparisonLhsAsDouble(cell(t, "X", 1)),
                "textual data must yield null (no violation), never 0");
        assertNull(ScalarSemantics.comparisonLhsAsDouble(cell(t, "X", 2)),
                "a missing cell yields null");
    }
}
