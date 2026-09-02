package net.cumba.cdisc.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.BitSet;
import net.cumba.cdisc.core.exec.EvaluationContext;
import net.cumba.cdisc.core.expr.CheckExpressionParser;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Verdict pins for {@link ExprCompiler#compileComparison}'s family routing: the {@code str()}
 * type-insensitive surface, the {@code num()} numeric-equality tag, the {@code date}/
 * {@code date_part}/{@code time_part} families, the affix-NEQ interception and the plain ordering
 * comparisons. The routing IS the verdict here — each case uses data where the two candidate
 * families disagree (e.g. {@code "5"} vs {@code "05"}: numerically equal, textually different), so
 * a mutant that reroutes a family ({@code family}, {@code tagOf}, {@code isStr},
 * {@code isAffixCall}, {@code dateOrEqual}, {@code numDirection}, {@code compilePlain}'s EQ/order
 * split) flips an asserted verdict rather than surviving as an internal detail.
 */
class ExprCompilerComparisonTagFamiliesTest
{

    private static BitSet eval(String expr, EvaluationContext ctx)
    {
        return NativeExprEvaluator.evaluate(CheckExpressionParser.parse(expr), ctx);
    }


    private static BitSet bits(int... set)
    {
        BitSet b = new BitSet();
        for (int i : set)
        {
            b.set(i);
        }
        return b;
    }


    private static EvaluationContext ctxOf(IDataTable t)
    {
        return EvaluationContext.builder().table(t).build();
    }

    // ---- str() == str(): type-insensitive (string) equality -------------------


    @Test
    void strTagForcesStringEqualityWhereNumericWouldMatch()
    {
        // CNUM is a NUMERIC column, SVAL a character one. Plain == is numeric-aware on a numeric
        // LHS ("02" parses to 2), the str()==str() surface is a pure string compare.
        IDataTable t = MockTable.of().name("AE").colLong("CNUM", 1L, 2L, 10L)
                .col("SVAL", "1", "02", "10").build();
        EvaluationContext c = ctxOf(t);
        assertEquals(bits(0, 1, 2), eval("CNUM == SVAL", c),
                "plain equality on a numeric LHS must be numeric-aware (02 matches 2)");
        assertEquals(bits(0, 2), eval("str(CNUM) == str(SVAL)", c),
                "str()==str() must compare string forms only (02 does not match 2)");
        assertEquals(bits(1), eval("str(CNUM) != str(SVAL)", c),
                "str()!=str() is the exact complement of str()==str()");
    }

    // ---- num() tag forces numeric equality on character data -------------------


    @Test
    void numTagForcesNumericEqualityOnCharacterData()
    {
        IDataTable t = MockTable.of().name("AE").col("NUMC", "5", "6", "x").build();
        EvaluationContext c = ctxOf(t);
        assertEquals(bits(), eval("NUMC == \"5.0\"", c),
                "an untagged character comparison is literal — \"5\" is not \"5.0\"");
        assertEquals(bits(0), eval("num(NUMC) == \"5.0\"", c),
                "num() must force the numeric parse — 5 equals 5.0, 6 and x do not");
        assertEquals(bits(1, 2), eval("num(NUMC) != \"5.0\"", c),
                "num() != must fire the numeric complement (unparseable falls back literal)");
    }

    // ---- ordering: direction and or-equal boundaries ---------------------------


    @Test
    void orderingComparisonsPinDirectionAndBoundary()
    {
        IDataTable t = MockTable.of().name("AE").colDouble("VAL", 4.0, 5.0, 6.0).build();
        EvaluationContext c = ctxOf(t);
        assertEquals(bits(2), eval("VAL > 5", c), "strict greater must exclude the equal row");
        assertEquals(bits(1, 2), eval("VAL >= 5", c), "or-equal greater must include it");
        assertEquals(bits(0), eval("VAL < 5", c), "strict less must exclude the equal row");
        assertEquals(bits(0, 1), eval("VAL <= 5", c), "or-equal less must include it");
        assertEquals(bits(1), eval("VAL == 5", c),
                "== must stay an equality, never a directional comparison");
    }

    // ---- date family: routed by the date() tag ---------------------------------


    @Test
    void dateComparisonDirectionAndOrEqual()
    {
        IDataTable t = MockTable.of().name("AE").col("DT", "2024-01-15", "2024-01-16", "2024-01-17")
                .build();
        EvaluationContext c = ctxOf(t);
        assertEquals(bits(0), eval("DT < date(\"2024-01-16\")", c),
                "date < must exclude the equal day");
        assertEquals(bits(0, 1), eval("DT <= date(\"2024-01-16\")", c),
                "date <= must include the equal day");
        assertEquals(bits(2), eval("DT > date(\"2024-01-16\")", c),
                "date > must exclude the equal day");
        assertEquals(bits(1, 2), eval("DT >= date(\"2024-01-16\")", c),
                "date >= must include the equal day");
        assertEquals(bits(1), eval("DT == date(\"2024-01-16\")", c), "date == fires the equal day");
        assertEquals(bits(0, 2), eval("DT != date(\"2024-01-16\")", c),
                "date != fires the complement");
    }


    @Test
    void missingLeftOperandFiresOnlyTheNegatedDateComparison()
    {
        IDataTable t = MockTable.of().name("AE").col("DT", "2024-01-16", "").build();
        EvaluationContext c = ctxOf(t);
        assertEquals(bits(1), eval("DT != date(\"2024-01-16\")", c),
                "a blank LHS fires the negated date comparison (absent-column contract)");
        assertEquals(bits(0), eval("DT == date(\"2024-01-16\")", c),
                "a blank LHS must never fire the positive date comparison");
    }

    // ---- date_part / time_part families ----------------------------------------


    @Test
    void datePartAndTimePartCompareOnlyThatPart()
    {
        IDataTable t = MockTable.of().name("AE").col("TS", "2024-01-15T10:00", "2024-01-16T10:00")
                .build();
        EvaluationContext c = ctxOf(t);
        // Same date part on row 0 despite different rendering precision; plain equality would
        // never fire either row.
        assertEquals(bits(0), eval("date_part(TS) == \"2024-01-15\"", c),
                "date_part == must compare the date part only");
        assertEquals(bits(1), eval("date_part(TS) != \"2024-01-15\"", c),
                "date_part != is the complement");
        assertEquals(bits(0, 1), eval("time_part(TS) == \"10:00\"", c),
                "time_part == must compare the time part only");
        assertEquals(bits(), eval("time_part(TS) != \"10:00\"", c),
                "time_part != must not fire when every time part matches");
    }

    // ---- affix NEQ interception --------------------------------------------------


    @Test
    void affixNotEqualsIsAPlainStringComparisonAndFiresOnEmpty()
    {
        IDataTable t = MockTable.of().name("AE").col("AFX", "ABCD", "AB", "", "ZZZZ").build();
        EvaluationContext c = ctxOf(t);
        assertEquals(bits(2, 3), eval("prefix(AFX, 2) != \"AB\"", c),
                "affix != must fire the differing rows INCLUDING the empty one (EC-49)");
        assertEquals(bits(0, 1), eval("prefix(AFX, 2) == \"AB\"", c),
                "affix == is the complement on this data");
    }


    @Test
    void affixNotEqualsAgainstADateTagStaysAPlainComparison()
    {
        // prefix("05x", 2) = "05" vs date("5"): the affix interception keeps this a STRING
        // comparison ("05" != "5" fires); the date family's numeric branch would call them equal.
        IDataTable t = MockTable.of().name("AE").col("NUM2", "05x", "5x").build();
        EvaluationContext c = ctxOf(t);
        assertEquals(bits(0), eval("prefix(NUM2, 1) != date(\"5\")", c),
                "the affix-NEQ interception must keep the comparison textual");
    }


    @Test
    void nonAffixNotEqualsAgainstADateTagUsesTheDateFamily()
    {
        // A PLAIN reference LHS must route by the right operand's date tag. A character cell
        // against a numeric-parsed comparand is the date family's MALFORMED MIXED SHAPE — a
        // violation regardless of direction — so BOTH rows fire, where the plain family would
        // fire only the textually-different row 1. The routing itself is the asserted verdict.
        IDataTable t = MockTable.of().name("AE").col("NN", "05", "6").build();
        EvaluationContext c = ctxOf(t);
        assertEquals(bits(0, 1), eval("NN != date(\"05\")", c),
                "a non-affix LHS must take the date family's mixed-shape contract on both rows");
    }

}
