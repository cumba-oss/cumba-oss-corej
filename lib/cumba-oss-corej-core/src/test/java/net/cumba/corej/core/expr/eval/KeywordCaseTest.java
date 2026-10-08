package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.BitSet;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpressionPrinter;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Runbook W1b KW (owner O21, 2026-10-08): the keywords {@code and or not in true false} are read in
 * any letter case — {@code X AND Y}, {@code X Not In [...]}, {@code TRUE} — as the same tree the
 * lower-case spelling builds, printed in the canonical lower case, and evaluated alike.
 */
class KeywordCaseTest
{

    private static IDataTable table()
    {
        return MockTable.of().name("AE").col("A", "a", "", "c").col("B", "x", "x", "y").build();
    }


    private static BitSet eval(String text)
    {
        return NativeExprEvaluator.evaluate(CheckExpressionParser.parse(text),
                EvaluationContext.builder().table(table()).build());
    }


    @Test
    void anyCaseParsesToTheLowerCaseTreeAndPrintsCanonically()
    {
        assertEquals(CheckExpressionParser.parse("X and Y or not Z"),
                CheckExpressionParser.parse("X AND Y Or NOT Z"));
        assertEquals("X and Y or not Z",
                ExpressionPrinter.print(CheckExpressionParser.parse("X AND Y OR Not Z")));
        assertEquals(CheckExpressionParser.parse("X not in [\"a\"] and Y == true"),
                CheckExpressionParser.parse("X NOT IN [\"a\"] AND Y == TRUE"));
        assertEquals("X not in [\"a\"] and Y == true and Z == false", ExpressionPrinter.print(
                CheckExpressionParser.parse("X Not In [\"a\"] And Y == True AND Z == False")));
    }


    @Test
    void anyCaseEvaluatesAsTheLowerCaseForm()
    {
        BitSet lower = eval("not empty(A) and B == \"x\"");
        assertEquals(1, lower.cardinality(), "premise: exactly one row satisfies the condition");
        assertEquals(lower, eval("NOT empty(A) AND B == \"x\""));
        assertEquals(lower, eval("Not empty(A) And B == \"x\""));
        assertEquals(eval("A in [\"a\", \"c\"] or B == \"y\""),
                eval("A IN [\"a\", \"c\"] OR B == \"y\""));
        assertEquals(eval("A not in [\"a\"] and not empty(B)"),
                eval("A NOT IN [\"a\"] AND NOT empty(B)"));
    }
}
