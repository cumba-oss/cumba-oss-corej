package net.cumba.corej.core.exec;

import static net.cumba.corej.core.exec.QualifiedNameFixture.check;
import static net.cumba.corej.core.exec.QualifiedNameFixture.exactInventory;
import static net.cumba.corej.core.exec.QualifiedNameFixture.executed;
import static net.cumba.corej.core.exec.QualifiedNameFixture.loadClean;
import static net.cumba.corej.core.exec.QualifiedNameFixture.matchJ;
import static net.cumba.corej.core.exec.QualifiedNameFixture.outcome;
import static net.cumba.corej.core.exec.QualifiedNameFixture.rows;
import static net.cumba.corej.core.exec.QualifiedNameFixture.ruleJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import net.cumba.corej.core.gen.TokenExpander;
import net.cumba.corej.core.gen.WildcardExpander;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-qualified-name-uniformity-review} phase 1 — the expansion-token channels: the scalar
 * {@code ${VAR[:fmt]}} operand, the list-valued {@code ${*}} wildcard, and the declared
 * {@code &NAME&} token. Each resolves a column name OUTSIDE the function-parameter model, so each
 * gets its own differential: the qualified spelling against the same table joined 1:1.
 */
class ExpansionTokenUniformityTest
{

    /** N drives the name: rows 0..3 name P1V, P2V, P1V, P3V (absent); row 4's driver is missing. */
    private static IDataTable driven(String name)
    {
        return RealTables.of(name).str("K", "k1", "k2", "k3", "k4", "k5")
                .lng("N", 1L, 2L, 1L, 3L, null).dbl("P1V", 1.0, 2.0, 3.0, 4.0, 5.0)
                .dbl("P2V", 9.0, 9.0, 9.0, 9.0, 9.0).str("S", "x", "y", "x", "z", "q")
                .str("Q1V", "x", "x", "x", "x", "x").str("Q2V", "y", "y", "y", "y", "y").build();
    }


    private static List<Long> fired(String expression, IDataTable p, IDataTable j)
    {
        Rule r = loadClean(
                ruleJson("Record", matchJ("") + "," + check(expression) + "," + outcome("K")),
                true);
        return rows(executed(r, p, exactInventory(p, j)));
    }


    @Test
    void theScalarTokenResolvesTheQualifiedNameLikeTheBareOne()
    {
        IDataTable p = driven("P");
        IDataTable j = driven("J");
        assertEquals(List.of(2L, 3L), fired("P${N:%d}V > 1.5", p, j),
                "row 2 reads P2V = 9, row 3 P1V = 3; row 4 names the absent P3V (\"\", Q8); the"
                        + " missing driver names no column (MIS)");
        assertEquals(fired("P${N:%d}V > 1.5", p, j), fired("J.P${N:%d}V > 1.5", p, j));
        assertEquals(fired("empty(P${N:%d}V)", p, j), fired("empty(J.P${N:%d}V)", p, j));
        assertEquals(fired("P${N:%d}V == 1", p, j), fired("J.P${N:%d}V == 1", p, j));
    }


    @Test
    void theListWildcardResolvesTheQualifiedNameLikeTheBareOne()
    {
        IDataTable p = driven("P");
        IDataTable j = driven("J");
        assertEquals(List.of(1L, 2L, 3L), fired("S in Q${*}V", p, j),
                "S is x / y / x on rows 1-3, in {Q1V = x, Q2V = y}; z and q are not");
        assertEquals(fired("S in Q${*}V", p, j), fired("S in J.Q${*}V", p, j));
        assertEquals(fired("S not in Q${*}V", p, j), fired("S not in J.Q${*}V", p, j));
    }


    @Test
    void aDeclaredTokenExpandsToTheSameConcreteRuleAsTheHandWrittenSpelling()
    {
        IDataTable p = RealTables.of("P").str("K", "k1", "k2", "k3").str("S", "a", "b", "c")
                .str("X", "x", "x", "x").build();
        IDataTable j = RealTables.of("J").str("K", "k1", "k2", "k3").str("S", "a", "zz", "c")
                .build();
        String template = ruleJson("Record",
                matchJ("") + ","
                        + "\"Expansion\":[{\"token\":\"&VAR&\",\"over\":\"shared_variables\","
                        + "\"with\":\"J\"}]," + check("&VAR& != J.&VAR&") + ","
                        + outcome("&VAR&", "J.&VAR&"));
        Rule tpl = loadClean(template, true);
        ScopeVariableSource source = ScopeVariableSource.of(exactInventory(p, j), p);
        assertNotNull(source);
        WildcardExpander.ExpansionResult result = TokenExpander.tryExpand(tpl, p.getMetaData(),
                new TokenExpander.Context(source, null, "P"));
        List<Rule> concrete = assertInstanceOf(WildcardExpander.ExpansionResult.Expanded.class,
                result).rules();
        assertEquals(List.of("T-QNU-S"), concrete.stream().map(Rule::effectiveId).toList(),
                "shared variables minus the join key: exactly S");
        RuleExecutionResult expanded = executed(concrete.get(0), p, exactInventory(p, j));
        Rule hand = loadClean(ruleJson("Record",
                matchJ("") + "," + check("S != J.S") + "," + outcome("S", "J.S")), true);
        RuleExecutionResult written = executed(hand, p, exactInventory(p, j));
        assertEquals(List.of(2L), rows(written));
        assertEquals(rows(written), rows(expanded),
                "&VAR& became the COLUMN S and J.&VAR& the DOTTED_REF J.S");
        assertEquals(QualifiedNameFixture.values(written), QualifiedNameFixture.values(expanded));
    }
}
