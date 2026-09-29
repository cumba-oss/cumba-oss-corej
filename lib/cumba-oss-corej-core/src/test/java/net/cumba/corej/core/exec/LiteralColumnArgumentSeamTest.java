package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import org.junit.jupiter.api.Test;

/**
 * The R1 compile seam of wave 1 ({@code ExprCompiler.rejectLiteralColumnArguments}) beyond the five
 * ported callables: it reaches every registry call that binds a {@code COLUMN_REFERENCE} parameter,
 * and since review round 1 of {@code PLAN-function-surface-wave1} (lane 1 M1) the elements of a
 * trailing column-reference <b>collector</b> too. {@code tuple(A, B, …)} is the one such
 * descriptor: before the tightening {@code tuple("A", B)} failed to load (slot {@code c1} is a
 * plain column parameter) while {@code tuple(A, B, "C")} loaded and computed against the constant
 * string {@code C} — the same silence R1 retires. Zero corpus sites quote a tuple element (both
 * corpora, measured 2026-09-29), so the tightening moves no verdict.
 */
class LiteralColumnArgumentSeamTest
{

    @Test
    void aQuotedLeadingTupleColumnFailsToLoad() throws IOException
    {
        String error = loadError("tuple(\\\"A\\\", B)");
        assertTrue(error.contains("column reference") && error.contains("c1"), error);
    }


    @Test
    void aQuotedCollectorTupleColumnFailsToLoadTheSameWay() throws IOException
    {
        // The collector slot: before the tightening this loaded.
        String error = loadError("tuple(A, B, \\\"C\\\")");
        assertTrue(error.contains("column reference") && error.contains("columns"), error);
        assertTrue(loadError("tuple(A, B, C, \\\"D\\\")").contains("column reference"));
    }


    @Test
    void plainTupleColumnsLoad() throws IOException
    {
        for (String spelling : List.of("tuple(A, B)", "tuple(A, B, C)", "tuple(A, B, C, D)"))
        {
            assertNull(load(spelling).getLoadError(), spelling);
        }
    }


    private static Rule load(String binding) throws IOException
    {
        String pkg = "{\"rules\":{\"x\":{\"Core\":{\"Id\":\"W1-TUPLE\"},"
                + "\"Bindings\":[{\"name\":\"$t\",\"expression\":\"" + binding + "\"}],"
                + "\"Check\":{\"expression\":\"\\\"x\\\" in $t\"}}}}";
        Rule rule = RulePackageLoader.loadFromString(pkg).getRules().get("x");
        assertNotNull(rule, "the rule loads");
        return rule;
    }


    private static String loadError(String binding) throws IOException
    {
        Rule rule = load(binding);
        assertNotNull(rule.getLoadError(), "expected a load error for " + binding);
        return rule.getLoadError();
    }

}
