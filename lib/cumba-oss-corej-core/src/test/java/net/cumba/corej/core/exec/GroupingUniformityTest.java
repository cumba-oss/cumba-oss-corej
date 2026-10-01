package net.cumba.corej.core.exec;

import static net.cumba.corej.core.exec.QualifiedNameFixture.check;
import static net.cumba.corej.core.exec.QualifiedNameFixture.exactInventory;
import static net.cumba.corej.core.exec.QualifiedNameFixture.executed;
import static net.cumba.corej.core.exec.QualifiedNameFixture.joined;
import static net.cumba.corej.core.exec.QualifiedNameFixture.loadClean;
import static net.cumba.corej.core.exec.QualifiedNameFixture.outcome;
import static net.cumba.corej.core.exec.QualifiedNameFixture.primary;
import static net.cumba.corej.core.exec.QualifiedNameFixture.renamed;
import static net.cumba.corej.core.exec.QualifiedNameFixture.ruleJson;
import static net.cumba.corej.core.exec.QualifiedNameFixture.run;
import static net.cumba.corej.core.exec.QualifiedNameFixture.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-qualified-name-uniformity-review} phase 1 — the rule-level {@code Grouping} surface
 * (qualified keys since {@code PLAN-rprfdy-offset-tp-join} C3) and the harness row shared with
 * {@code PLAN-stage-a-parameter-type-arming}: a {@code find_vars} binding holding QUALIFIED names,
 * spliced into {@code record_count(group=[…, $b])} — the plan's premise was that it reads the
 * joined columns exactly as the literal {@code group=[…, J.X]} does; MEASURED, the splice is
 * refused as the rule's ERROR (N15), and only the literal spelling reads the join.
 *
 * <p>
 * Known red recorded, not fixed: <b>N6</b> (ruled, plan §2.3 D-ABSENT) — an absent qualified key
 * component ERRORs the rule while an absent bare key is dropped from the key (EC-44).
 * </p>
 */
class GroupingUniformityTest
{

    /** D-SRC (rprfdy): the source of a qualified key must be an INNER join. */
    private static final String INNER_J = "\"Match_Datasets\":[{\"Name\":\"J\",\"Keys\":[\"K\"],"
            + "\"Join_Type\":\"inner\"}]";

    private static Rule groupRule(String groupingVariables)
    {
        return groupRule(groupingVariables, "S", "G");
    }


    private static Rule groupRule(String groupingVariables, String... outputVariables)
    {
        return loadClean(
                ruleJson("Group", "\"Grouping\":{\"Variables\":[" + groupingVariables + "]},"
                        + INNER_J + "," + check("S != \"zz\"") + "," + outcome(outputVariables)),
                true);
    }


    /**
     * N18 (D72 / D94c): a group finding reports a bare output column as the block's DISTINCT SET
     * over the flagged rows; a dotted joined column is a column of the augmented primary like any
     * other, so it reports the same set. RED before: the dotted entry kept the anchor row's value
     * ({@code "a"} where the bare entry reports {@code "[a, b]"}).
     */
    @Test
    void aDottedGroupOutputReportsTheBlockDistinctSetLikeTheBareOne()
    {
        IDataTable p = primary();
        IDataTable j = joined();
        List<Map<String, String>> bare = QualifiedNameFixture
                .values(executed(groupRule("\"G\"", "S", "N"), p, exactInventory(p, j)));
        List<Map<String, String>> dotted = QualifiedNameFixture
                .values(executed(groupRule("\"G\"", "J.S", "J.N"), p, exactInventory(p, j)));
        assertEquals("[a, b]", bare.get(0).get("S"), "D94c: g1's flagged rows hold a and b");
        assertEquals(3, dotted.size());
        for (int i = 0; i < 3; i++)
        {
            assertEquals(bare.get(i).get("S"), dotted.get(i).get("J.S"), "group " + i);
            assertEquals(bare.get(i).get("N"), dotted.get(i).get("J.N"), "group " + i);
        }
    }


    private static List<Map<String, String>> groupKeys(RuleExecutionResult r)
    {
        List<Map<String, String>> out = new ArrayList<>();
        for (Violation v : r.getViolations())
        {
            out.add(v.getGroupKey());
        }
        return out;
    }


    @Test
    void aQualifiedGroupingKeyPartitionsLikeTheBareOne()
    {
        // J's G differs from P's (T2 r1 M5): h1 ×4 / h2 ×2 against P's g1 / g2 / g3 pairs. The
        // reference for Grouping [J.G] over P ⋈ J is Grouping [G] over P', the primary carrying
        // J's G itself.
        IDataTable p = primary();
        IDataTable j = RealTables.of("J").str("K", "k1", "k2", "k3", "k4", "k5", "k6")
                .str("S", "a", "b", "", "A", "b", "c").str("G", "h1", "h1", "h1", "h1", "h2", "h2")
                .build();
        IDataTable pLikeJ = RealTables.of("P").str("K", "k1", "k2", "k3", "k4", "k5", "k6")
                .str("S", "a", "b", "", "A", "b", "c").str("G", "h1", "h1", "h1", "h1", "h2", "h2")
                .build();
        RuleExecutionResult bareOnP = executed(groupRule("\"G\""), p, exactInventory(p, j));
        RuleExecutionResult bare = executed(groupRule("\"G\""), pLikeJ, exactInventory(pLikeJ, j));
        RuleExecutionResult dotted = executed(groupRule("\"J.G\"", "S", "J.G"), p,
                exactInventory(p, j));
        assertEquals(3, bareOnP.getViolations().size(), "P's own G: g1 / g2 / g3");
        assertEquals(2, bare.getViolations().size(), "J's G: h1 / h2");
        assertEquals(bare.getViolations().size(), dotted.getViolations().size(),
                "the qualified key partitions by J's values, not P's");
        assertEquals(QualifiedNameFixture.values(bare),
                renamed(QualifiedNameFixture.values(dotted), Map.of("J.G", "G")),
                "the same anchors, the same reported values — the key printed as written");
        List<String> bareKeys = new ArrayList<>();
        List<String> dottedKeys = new ArrayList<>();
        for (int i = 0; i < 2; i++)
        {
            bareKeys.add(String.valueOf(groupKeys(bare).get(i).values()));
            dottedKeys.add(String.valueOf(groupKeys(dotted).get(i).values()));
        }
        assertEquals(bareKeys, dottedKeys,
                "the group key VALUES agree (the key is named as written)");
    }


    /** ⚠ Known red N6 — ruled (plan §2.3, D-ABSENT), recorded, not a finding. */
    @Test
    void anAbsentQualifiedKeyErrorsWhereAnAbsentBareKeyIsDropped()
    {
        IDataTable p = table("P", "G");
        IDataTable j = table("J", "G");
        RuleExecutionResult bare = run(groupRule("\"G\""), p, exactInventory(p, j));
        RuleExecutionResult dotted = run(groupRule("\"J.G\""), p, exactInventory(p, j));
        assertEquals(RuleExecutionStatus.EXECUTED, bare.getStatus(), bare.getStatusMessage());
        assertEquals(1, bare.getViolations().size(),
                "EC-44: the absent bare member is dropped, one group remains");
        assertEquals(RuleExecutionStatus.ERROR, dotted.getStatus(),
                "D-ABSENT: UnresolvedQualifiedKeyException — " + dotted.getStatusMessage());
        assertTrue(String.valueOf(dotted.getStatusMessage()).contains("J.G"),
                dotted.getStatusMessage());
    }


    /**
     * The row the plan shares with {@code PLAN-stage-a-parameter-type-arming} (plan §3 phase 1),
     * MEASURED: a {@code find_vars} binding holding a QUALIFIED name cannot be spliced into
     * {@code record_count(group=[$b])} — {@code GroupSplice} refuses the splice by design ("a
     * qualified group= member is judged at load, so author it in the group= list itself": D-DOMAIN
     * / D-SRC / D-REGEX are load gates, and the join type D-SRC needs is not on the evaluation
     * context), while the literal {@code group=[J.G]} is admitted. ⭐ N15 (fixed): the refusal is
     * the rule's ERROR on {@code RuleRunner}'s sentinel channel — until then an
     * {@link IllegalStateException} escaped {@code RuleRunner}. The literal row is the uniform one;
     * the arming plan's splice premise must read this pin, not the plan text.
     */
    @Test
    void aFindVarsBindingOfQualifiedNamesSplicedIntoRecordCountErrorsTheRule()
    {
        IDataTable p = primary();
        IDataTable j = joined();
        String literal = ruleJson("Record", INNER_J + ","
                + "\"Bindings\":[{\"name\":\"$c\",\"expression\":\"record_count(domain=\\\"J\\\","
                + " group=[J.G])\"}]," + check("$c == 2") + "," + outcome("S", "$c"));
        String spliced = ruleJson("Record", INNER_J + ","
                + "\"Bindings\":[{\"name\":\"$b\",\"expression\":\"find_vars(\\\"J.G\\\")\"},"
                + "{\"name\":\"$c\",\"expression\":\"record_count(domain=\\\"J\\\", group=[$b])\"}],"
                + check("$c == 2") + "," + outcome("S", "$c"));
        RuleExecutionResult lit = executed(loadClean(literal, true), p, exactInventory(p, j));
        assertEquals(6, lit.getViolations().size(), "every record's group of J has two rows");
        assertEquals("2", QualifiedNameFixture.values(lit).get(0).get("$c"));
        RuleExecutionResult refused = run(loadClean(spliced, true), p, exactInventory(p, j));
        assertEquals(RuleExecutionStatus.ERROR, refused.getStatus(), refused.getStatusMessage());
        assertTrue(String.valueOf(refused.getStatusMessage())
                .contains("splices the qualified name J.G"), refused.getStatusMessage());
    }
}
