package net.cumba.corej.core.expr.typed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpressionPrinter;
import net.cumba.corej.core.expr.ast.Expr;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-column-typed-function-absent-type} A1 / A2 at the stage-A walk: the column-typed call
 * sites ({@code colref}, {@code read_value}, {@code max} over a column, {@code row_max}) record
 * their position's kind under the call, a {@code $}-reference hands its position's kind to its
 * binding's root (through {@code $}-chains), and {@code coalesce} passes its position to its
 * operands. The engine-side answers are pinned end to end by
 * {@code net.cumba.corej.core.exec.ColumnTypedAbsentDefaultTest}; this class pins the recording,
 * including the shapes that must record nothing.
 */
class ColumnTypedSiteExpectationTest
{

    private static Expr parse(String expression)
    {
        Expr parsed = CheckExpressionParser.parse(expression);
        assertTrue(parsed != null, "the parser must yield an expression for: " + expression);
        return parsed;
    }


    /** The expectations of {@code check} under bindings given as {@code name, expression} pairs. */
    private static TypeExpectations of(String check, String... bindings)
    {
        Map<String, Expr> map = new LinkedHashMap<>();
        List<Expr> roots = new java.util.ArrayList<>();
        roots.add(parse(check));
        for (int i = 0; i < bindings.length; i += 2)
        {
            Expr root = parse(bindings[i + 1]);
            map.put(bindings[i], root);
            roots.add(root);
        }
        return TypeExpectations.of(roots, map);
    }


    private static Set<String> sites(TypeExpectations te)
    {
        return te.numericDynamicSites().stream().map(ExpressionPrinter::print)
                .collect(Collectors.toSet());
    }

    // ------------------------------------------------------------------ A1: the sites


    @Test
    void everyColumnTypedCallRecordsItsNumericPositionUnderTheCall()
    {
        assertEquals(Set.of("read_value(X, domain=D, mode=\"FIRST\")"),
                sites(of("read_value(X, domain=D, mode=\"FIRST\") < 3")));
        assertEquals(Set.of("max(X, domain=D, group=[USUBJID])"),
                sites(of("Y > max(X, domain=D, group=[USUBJID])")));
        assertEquals(Set.of("row_max(name_pattern=\"^TR\")"),
                sites(of("row_max(name_pattern=\"^TR\") - 1 > 0")));
        assertEquals(Set.of("colref(\"ZZ\")"), sites(of("colref(\"ZZ\") < 3")));
        assertEquals(Set.of("read_value(X, domain=D, mode=\"FIRST\")"),
                sites(of("read_value(X, domain=D, mode=\"FIRST\") in [1, 2]")),
                "a numeric-literal membership probe is a numeric position");
    }


    @Test
    void aCharacterOrUntypedPositionRecordsNoNumericSite()
    {
        assertEquals(Set.of(), sites(of("read_value(X, domain=D, mode=\"FIRST\") == \"Y\"")));
        assertEquals(Set.of(), sites(of("empty(read_value(X, domain=D, mode=\"FIRST\"))")));
        assertEquals(Set.of(), sites(of("A == max(X, domain=D, group=[USUBJID])")),
                "a column-vs-call equality records no kind (D76a: char)");
    }


    @Test
    void aMaxOverAComputedTargetAndTheDateExtremesAreNoSites()
    {
        assertEquals(Set.of(), sites(of("max(X + 1, domain=D, group=[USUBJID]) > 3")),
                "the type of a computed target is the expression's, not a column's");
        assertEquals(Set.of(), sites(of("max_date(DSSTDTC, domain=D, group=[USUBJID]) > 3")));
    }

    // ------------------------------------------------------------------ A2: bindings


    @Test
    void aBindingsNumericUseRecordsOnItsRoot()
    {
        TypeExpectations column = of("$x < 3", "$x", "ZZ");
        assertTrue(column.numericExpected("ZZ"), "$x: ZZ; $x < 3 is ZZ < 3");
        assertEquals(Set.of("$x"), column.numericBindings());
        TypeExpectations dotted = of("$x + 1 > 3", "$x", "DM.AGE");
        assertTrue(dotted.numericExpected("DM.AGE"));
        assertEquals(Set.of("read_value(X, domain=D, mode=\"FIRST\")"),
                sites(of("$v > 3", "$v", "read_value(X, domain=D, mode=\"FIRST\")")));
    }


    @Test
    void aDollarChainIsFollowedToItsRoot()
    {
        TypeExpectations te = of("$c < 3", "$a", "max(X, domain=D, group=[USUBJID])", "$b", "$a",
                "$c", "$b");
        assertEquals(Set.of("max(X, domain=D, group=[USUBJID])"), sites(te));
        assertEquals(Set.of("$a", "$b", "$c"), te.numericBindings());
        TypeExpectations inBinding = of("not empty($r) and Y != $d", "$r",
                "read_value(RPRFDY, domain=TP, mode=\"MAX\")", "$d", "Z + $r");
        assertEquals(Set.of("read_value(RPRFDY, domain=TP, mode=\"MAX\")"), sites(inBinding),
                "a use inside another binding's arithmetic is a numeric use (SEND-0401's shape)");
    }


    @Test
    void aCharacterUseLeavesTheRootCharacter()
    {
        TypeExpectations te = of("$x == \"Y\"", "$x", "ZZ");
        assertEquals(Set.of(TypeExpectations.Expectation.CHARACTER), te.expectationsOf("ZZ"));
        assertEquals(Set.of(), te.numericBindings());
    }


    @Test
    void aBindingWithNoMapCarriesNothing()
    {
        // A context with no $ variables (a filter, a computed target) supplies no map: as before.
        TypeExpectations te = TypeExpectations.of(List.of(parse("$x < 3")));
        assertEquals(Set.of(), te.numericBindings());
        assertEquals(Set.of(), te.numericDefaultColumns());
    }


    @Test
    void aCyclicMapTerminates()
    {
        // Stage A refuses a cyclic binding; the walk still terminates on one (hop guard).
        TypeExpectations te = of("$a < 3", "$a", "$b", "$b", "$a");
        assertEquals(Set.of("$a", "$b"), te.numericBindings());
    }


    @Test
    void aListFormColrefBindingInAMembershipProbeRecordsNothing()
    {
        // D81d: colref over a list is an element-wise list membership, never a scalar read — as
        // written in place, so bound.
        assertEquals(Set.of(), sites(of("colref([\"A\", \"B\"]) in [1, 2]")));
        assertEquals(Set.of(), sites(of("$l in [1, 2]", "$l", "colref([\"A\", \"B\"])")));
    }

    // ------------------------------------------------------------------ coalesce


    @Test
    void coalescePassesItsPositionToEveryOperand()
    {
        TypeExpectations te = of("coalesce(A, B, 0) > 3");
        assertTrue(te.numericExpected("A"));
        assertTrue(te.numericExpected("B"));
        TypeExpectations bound = of("$c < 3", "$c",
                "coalesce(read_value(X, domain=D," + " mode=\"FIRST\"), ZZ)");
        assertTrue(bound.numericExpected("ZZ"));
        assertEquals(Set.of("read_value(X, domain=D, mode=\"FIRST\")"), sites(bound));
        assertEquals(Set.of(TypeExpectations.Expectation.CHARACTER),
                of("coalesce(A, \"x\") == \"Y\"").expectationsOf("A"));
    }
}
