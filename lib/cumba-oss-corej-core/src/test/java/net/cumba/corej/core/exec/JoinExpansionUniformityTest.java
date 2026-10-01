package net.cumba.corej.core.exec;

import static net.cumba.corej.core.exec.QualifiedNameFixture.check;
import static net.cumba.corej.core.exec.QualifiedNameFixture.exactInventory;
import static net.cumba.corej.core.exec.QualifiedNameFixture.executed;
import static net.cumba.corej.core.exec.QualifiedNameFixture.loadClean;
import static net.cumba.corej.core.exec.QualifiedNameFixture.matchJ;
import static net.cumba.corej.core.exec.QualifiedNameFixture.outcome;
import static net.cumba.corej.core.exec.QualifiedNameFixture.renamed;
import static net.cumba.corej.core.exec.QualifiedNameFixture.ruleJson;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-qualified-name-uniformity-review} phase 1 — criterion C10 (D32): a one-to-many join
 * expands the iteration domain, and a Check over {@code J.X} on the expanded primary answers
 * exactly as the same Check over {@code X} on a primary that carries those rows itself; a grouped
 * function counts an expanded row the same way.
 */
class JoinExpansionUniformityTest
{

    /** J carries k1 twice (S = a / a2) and k2 once; k3 has no match. */
    private static IDataTable manyJ()
    {
        return RealTables.of("J").str("K", "k1", "k1", "k2").str("S", "a", "a2", "b")
                .str("G", "g1", "g1", "g2").build();
    }


    private static IDataTable primaryP()
    {
        return RealTables.of("P").str("K", "k1", "k2", "k3").str("S", "a", "b", "c")
                .str("G", "g1", "g2", "g3").build();
    }


    /** What P looks like once the join has expanded it: k1 twice, k3's joined cells defaulted. */
    private static IDataTable expandedByHand()
    {
        return RealTables.of("P").str("K", "k1", "k1", "k2", "k3").str("S", "a", "a2", "b", "")
                .str("G", "g1", "g1", "g2", "").build();
    }


    private static RuleExecutionResult run(String expression, IDataTable p, IDataTable j,
            String... out)
    {
        Rule r = loadClean(
                ruleJson("Record", matchJ("") + "," + check(expression) + "," + outcome(out)),
                true);
        return executed(r, p, exactInventory(p, j));
    }


    private static RuleExecutionResult runBare(String expression, IDataTable p, String... out)
    {
        Rule r = loadClean(ruleJson("Record", check(expression) + "," + outcome(out)), false);
        return executed(r, p, exactInventory(p));
    }


    @Test
    void aOneToManyJoinExpandsTheRowsAndReadsEachMatchLikeAPrimaryRow()
    {
        RuleExecutionResult viaJoin = run("J.S != \"zz\"", primaryP(), manyJ(), "K", "J.S");
        RuleExecutionResult byHand = runBare("S != \"zz\"", expandedByHand(), "K", "S");
        assertEquals(4, byHand.getViolations().size());
        assertEquals(byHand.getViolations().size(), viaJoin.getViolations().size(),
                "k1 is judged twice, k3 once with the default");
        assertEquals(QualifiedNameFixture.values(byHand),
                renamed(QualifiedNameFixture.values(viaJoin), Map.of("J.S", "S")));
    }


    @Test
    void aGroupedFunctionCountsAnExpandedRowLikeAPrimaryRow()
    {
        // record_count(group=[G]) over the EXPANDED primary vs over the hand-expanded one: the
        // two k1 copies both carry J.G = g1 (dotted) / G = g1 (bare) and count as two.
        RuleExecutionResult viaJoin = run("record_count(group=[G]) == 2", primaryP(), manyJ(), "K");
        RuleExecutionResult byHand = runBare("record_count(group=[G]) == 2", expandedByHand(), "K");
        assertEquals(2, byHand.getViolations().size(), "the two k1 copies");
        assertEquals("k1", QualifiedNameFixture.values(byHand).get(1).get("K"));
        assertEquals(QualifiedNameFixture.values(byHand), QualifiedNameFixture.values(viaJoin));
    }


    /**
     * The grouped half with the QUALIFIED key (T2 r1 M6): Grouping [J.G] over the expanded join.
     */
    @Test
    void aDottedGroupingKeyOverAnExpandedJoinPartitionsLikeTheBareOne()
    {
        // D-SRC: a qualified key's source is an INNER join — k3 (no J row) is dropped on both
        // sides: the hand-expanded primary omits it too.
        IDataTable p = primaryP();
        IDataTable j = manyJ();
        // The join DUPLICATES the primary row: both k1 copies carry P's own S = a.
        IDataTable byHandInner = RealTables.of("P").str("K", "k1", "k1", "k2")
                .str("S", "a", "a", "b").str("G", "g1", "g1", "g2").build();
        String inner = "\"Match_Datasets\":[{\"Name\":\"J\",\"Keys\":[\"K\"],"
                + "\"Join_Type\":\"inner\"}]";
        Rule dotted = loadClean(ruleJson("Group", "\"Grouping\":{\"Variables\":[\"J.G\"]}," + inner
                + "," + check("S != \"zz\"") + "," + outcome("K")), true);
        Rule bare = loadClean(ruleJson("Group", "\"Grouping\":{\"Variables\":[\"G\"]},"
                + check("S != \"zz\"") + "," + outcome("K")), false);
        RuleExecutionResult viaJoin = executed(dotted, p, exactInventory(p, j));
        RuleExecutionResult byHand = executed(bare, byHandInner, exactInventory(byHandInner));
        assertEquals(2, byHand.getViolations().size(), "g1 (the two k1 copies) and g2");
        assertEquals(byHand.getViolations().size(), viaJoin.getViolations().size());
        assertEquals(QualifiedNameFixture.values(byHand), QualifiedNameFixture.values(viaJoin));
    }
}
