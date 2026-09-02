package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import net.cumba.cdisc.core.expr.eval.DataValues;
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
    // valueEquals — the equal_to / not_equal_to scalar anchor
    // -------------------------------------------------------------------------


    /**
     * Pins the numeric branch: a DOUBLE cell 30.0 equals the Number target 30 numerically even
     * though the string forms ("30.0" vs "30") differ. Kills the line-253 instanceof negation and
     * the line-256 NaN-guard negation (both would fall through to the textual compare and answer
     * false) and the line-258 {@code ==}→{@code !=} negation.
     */
    @Test
    void valueEqualsComparesNumericallyWhenTargetIsANumber()
    {
        assertTrue(ScalarSemantics.valueEquals(DataValues.of(30.0), 30),
                "30.0 == 30 numerically; the textual fold would wrongly answer false");
        // Negative twin (kills the line-258 replaced-with-true mutant): a genuinely different
        // number must NOT be equal — this is the mis-compared-dose case.
        assertFalse(ScalarSemantics.valueEquals(DataValues.of(31.0), 30));
    }


    /**
     * A non-parseable cell against a Number target falls back to the textual compare — "abc" is not
     * "30". A parseable textual cell against the same target matches numerically.
     */
    @Test
    void valueEqualsFallsBackToTextWhenTheCellDoesNotParse()
    {
        assertFalse(ScalarSemantics.valueEquals(DataValues.of("abc"), 30));
        assertTrue(ScalarSemantics.valueEquals(DataValues.of("30"), 30));
    }


    /**
     * Pins the null-target guard (line 262/264): a null RHS compares equal ONLY to a missing cell.
     * Negating the guard would NPE on the present cell; replacing the return with a constant flips
     * one of the two verdicts.
     */
    @Test
    void valueEqualsWithNullTargetMatchesOnlyAMissingCell()
    {
        assertTrue(ScalarSemantics.valueEquals(DataValues.of(null), null),
                "missing == missing under a null target");
        assertFalse(ScalarSemantics.valueEquals(DataValues.of("A"), null),
                "a present value never equals a null target");
        // A missing cell against a PRESENT string target folds to "" and differs.
        assertFalse(ScalarSemantics.valueEquals(DataValues.of(null), "A"));
    }


    /** Pins the plain string branch (line 266) in both directions. */
    @Test
    void valueEqualsStringBranchBothVerdicts()
    {
        assertTrue(ScalarSemantics.valueEquals(DataValues.of("A"), "A"));
        assertFalse(ScalarSemantics.valueEquals(DataValues.of("A"), "B"));
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

    // -------------------------------------------------------------------------
    // isIntegerValue — is_integer anchor
    // -------------------------------------------------------------------------


    /** Pins both verdicts of the integer check (line 1202). */
    @Test
    void isIntegerValueBothVerdicts()
    {
        assertTrue(ScalarSemantics.isIntegerValue(DataValues.of("3")));
        assertTrue(ScalarSemantics.isIntegerValue(DataValues.of("3.0")),
                "a whole-valued decimal is an integer");
        assertFalse(ScalarSemantics.isIntegerValue(DataValues.of("3.5")));
        assertFalse(ScalarSemantics.isIntegerValue(DataValues.of(null)),
                "a missing cell folds to \"\", which is not an integer");
    }
}
