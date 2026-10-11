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
 * D-SRC on the group operators (register {@code RPR C2} / {@code C3}): a qualified operand of a
 * group operator — a {@code members} list, a {@code name=} / {@code within=} / {@code key}, the
 * extremes' {@code name=} — must name a declared {@code Match_Datasets} entry of the same rule,
 * else a load error naming the rule, through the one gate the {@code group=} members and the
 * rule-level {@code Grouping} pass ({@code RulePackageLoader.qualifiedSourceError}). ⭐ Since
 * {@code REG-Q4} QK12 (owner 2026-10-09, {@code MET D-SRC-ops} withdrawn) ANY declared entry is a
 * source — a {@code left} one, a {@code Child} parent: the operand reads the merged column like a
 * bare name (an unmatched {@code left} row its type default, {@code D72a-1}). Until then the entry
 * had to be an ordinary, expandable, keyed {@code inner} one (owner 2026-10-05, W3d ride-along).
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
            "empty_within_except_last_row(J.S, group=[K], by=K)",
            "contains_all(source=J.S, required=[\"a\"])", "is_unique_value(J.S)",
            "is_unique_relationship(J.S, K)");

    /** QK12: over a {@code left} join as over an {@code inner} one, every shape loads. */
    @Test
    void aQualifiedOperandLoadsOverALeftJoinAsOverAnInnerJoin()
    {
        for (String shape : SHAPES)
        {
            assertNull(rule(match("J", "left"), shape).getLoadError(), shape
                    + " loads over a left join: " + rule(match("J", "left"), shape).getLoadError());
            assertNull(rule(match("J", "inner"), shape).getLoadError(),
                    shape + " loads over an inner join: "
                            + rule(match("J", "inner"), shape).getLoadError());
        }
    }


    /**
     * An UNDECLARED qualifier stays a load error (a name that resolves nowhere — NAME, not a
     * policy); a {@code Child} parent is a declared entry and loads (QK12).
     */
    @Test
    void anUndeclaredQualifierIsALoadErrorAndAChildParentIsASource()
    {
        String undeclared = rule(match("J", "inner"), "is_unique_set([Q.S, K])").getLoadError();
        assertNotNull(undeclared);
        assertTrue(undeclared.contains("names no Match_Datasets entry Q"), undeclared);
        assertNull(rule("\"Match_Datasets\":[{\"Name\":\"J\",\"Child\":true}]",
                "has_multiple_values_for(name=J.S, key=K)").getLoadError());
        // controls: bare operands are not qualified names; a lower-case qualifier names the
        // entry (P-19); a domain= call reads the named dataset itself (W3c) and is not judged
        assertNull(rule(match("J", "left"), "is_unique_set([S, K])").getLoadError());
        assertNull(rule(match("J", "inner"), "is_unique_set([j.S, K])").getLoadError());
        assertNull(
                rule(match("J", "left"), "max(J.N, domain=\"J\", group=[K]) == N").getLoadError());
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
        assertEquals(25, GroupReadTable.GROUP_OPERATORS.size(),
                "+1 is_not_unique_member_set (runbook W1b SS1)");
    }
}
