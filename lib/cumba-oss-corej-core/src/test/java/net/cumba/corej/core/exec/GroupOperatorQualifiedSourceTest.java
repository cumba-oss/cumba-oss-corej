package net.cumba.corej.core.exec;

import static net.cumba.corej.core.exec.QualifiedNameFixture.check;
import static net.cumba.corej.core.exec.QualifiedNameFixture.load;
import static net.cumba.corej.core.exec.QualifiedNameFixture.outcome;
import static net.cumba.corej.core.exec.QualifiedNameFixture.ruleJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.expr.eval.FunctionRegistry;
import net.cumba.corej.core.model.Rule;
import org.junit.jupiter.api.Test;

/**
 * D-SRC extended to the group operators (owner 2026-10-05, {@code PLAN-merged-evaluation-table} W3d
 * ride-along; register {@code RPR C2} / {@code C3}): a qualified operand of a group operator — a
 * {@code members} list, a {@code name=} / {@code within=} / {@code key}, the extremes'
 * {@code name=} — must name an ordinary, expandable, keyed {@code inner} {@code Match_Datasets}
 * entry of the same rule, else a load error naming the rule, through the one gate the
 * {@code group=} members and the rule-level {@code Grouping} already pass
 * ({@code RulePackageLoader.qualifiedSourceError}). RED before: every shape below loaded clean over
 * a {@code left} join and read the type default as a key on its unmatched rows.
 */
class GroupOperatorQualifiedSourceTest
{

    private static String match(String name, String joinType)
    {
        return "\"Match_Datasets\":[{\"Name\":\"" + name + "\",\"Keys\":[\"K\"],\"Join_Type\":\""
                + joinType + "\"}]";
    }


    private static Rule rule(String match, String expression)
    {
        return load(ruleJson("Record", match + "," + check(expression) + "," + outcome("K")), true);
    }

    private static final List<String> SHAPES = List.of("is_unique_set([J.S, K])",
            "is_not_unique_set([K, J.S])", "has_multiple_values_for(name=J.S, key=K)",
            "present_on_multiple_rows_within(name=J.S, within=[K])",
            "is_inconsistent_across_dataset(name=J.S, keys=[K])", "has_same_values(J.S)",
            "max(J.N, group=[K]) == N", "record_count(group=[J.S]) > 1",
            "empty_within_except_last_row(J.S, group=[K], ordering=K)",
            "contains_all(source=J.S, required=[\"a\"])");

    @Test
    void aQualifiedOperandOverALeftJoinIsALoadErrorAndOverAnInnerJoinLoads()
    {
        for (String shape : SHAPES)
        {
            String error = rule(match("J", "left"), shape).getLoadError();
            assertNotNull(error, shape + " must not load over a left join");
            assertTrue(error.contains("J.S") || error.contains("J.N"), shape + ": " + error);
            assertTrue(error.contains("Join_Type left"), shape + ": " + error);
            assertTrue(error.contains("operand of ") || error.contains("group= member"),
                    shape + ": " + error);
            assertNull(rule(match("J", "inner"), shape).getLoadError(),
                    shape + " loads over an inner join: "
                            + rule(match("J", "inner"), shape).getLoadError());
        }
    }


    @Test
    void anUndeclaredOrNonOrdinaryQualifierIsALoadErrorAndTheBareOperandIsNot()
    {
        String undeclared = rule(match("J", "inner"), "is_unique_set([Q.S, K])").getLoadError();
        assertNotNull(undeclared);
        assertTrue(undeclared.contains("names no Match_Datasets entry Q"), undeclared);
        String child = rule("\"Match_Datasets\":[{\"Name\":\"J\",\"Child\":true}]",
                "has_multiple_values_for(name=J.S, key=K)").getLoadError();
        assertNotNull(child);
        assertTrue(child.contains("not an ordinary keyed join") || child.contains("Child"), child);
        // controls: bare operands are not qualified names; a lower-case qualifier names the
        // entry (P-19); a domain= call reads the named dataset itself (W3c) and is not judged
        assertNull(rule(match("J", "left"), "is_unique_set([S, K])").getLoadError());
        assertNull(rule(match("J", "inner"), "is_unique_set([j.S, K])").getLoadError());
        assertNull(
                rule(match("J", "left"), "max(J.N, domain=\"J\", group=[K]) == N").getLoadError());
    }


    /**
     * T2 review (lane 2, M2): the positive twins {@code is_unique_value} and
     * {@code is_unique_relationship} read their operands through the same group reader as their
     * negatives, so D-SRC judges them too. RED before: both loaded clean over a {@code left} join.
     */
    @Test
    void theUniquePositivesAreJudgedLikeTheirNegatives()
    {
        for (String shape : List.of("is_unique_value(J.S)", "is_unique_relationship(J.S, K)"))
        {
            String error = rule(match("J", "left"), shape).getLoadError();
            assertNotNull(error, shape + " must not load over a left join");
            assertTrue(error.contains("J.S") && error.contains("Join_Type left"),
                    shape + ": " + error);
            assertNull(rule(match("J", "inner"), shape).getLoadError(), shape + " over inner");
            // control: the bare operand is no qualified name
            assertNull(rule(match("J", "left"), shape.replace("J.S", "S")).getLoadError(),
                    shape + " with a bare operand");
        }
    }


    /** The roster the gate judges names registered functions only — it cannot rot. */
    @Test
    void theGroupOperatorRosterNamesRegisteredFunctions()
    {
        List<String> registered = FunctionRegistry.all().stream().map(FunctionDescriptor::name)
                .toList();
        for (String name : GroupReadTable.GROUP_OPERATORS)
        {
            assertTrue(registered.contains(name), name + " is not a registered function");
        }
        assertEquals(24, GroupReadTable.GROUP_OPERATORS.size());
    }
}
