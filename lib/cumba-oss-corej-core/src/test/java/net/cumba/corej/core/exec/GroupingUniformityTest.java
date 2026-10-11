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
 * joined columns exactly as the literal {@code group=[…, J.X]} does. Measured at the time, the
 * splice was refused as the rule's ERROR (N15).
 *
 * <p>
 * Both known reds of that review are resolved by {@code REG-Q4} ({@code
 * PLAN-join-key-one-side-absent-drop}): <b>N6</b> — an absent qualified key component is dropped
 * like an absent bare one ({@code EC-44}, QK4) instead of ERRORing the rule; <b>N15</b> — the
 * spliced qualified name reads exactly as the literal (plan §5 Q7). The methods below assert the
 * resolved behaviour.
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


    /**
     * N6, resolved by {@code REG-Q4} QK4 (owner 2026-10-09): an absent qualified {@code Grouping}
     * member is dropped exactly as an absent bare one ({@code EC-44}) — it used to ERROR (D-ABSENT,
     * plan §2.3, recorded as a known red).
     */
    @Test
    void anAbsentQualifiedKeyIsDroppedLikeAnAbsentBareKey()
    {
        IDataTable p = table("P", "G");
        IDataTable j = table("J", "G");
        RuleExecutionResult bare = run(groupRule("\"G\""), p, exactInventory(p, j));
        RuleExecutionResult dotted = run(groupRule("\"J.G\""), p, exactInventory(p, j));
        assertEquals(RuleExecutionStatus.EXECUTED, bare.getStatus(), bare.getStatusMessage());
        assertEquals(1, bare.getViolations().size(),
                "EC-44: the absent bare member is dropped, one group remains");
        assertEquals(RuleExecutionStatus.EXECUTED, dotted.getStatus(), dotted.getStatusMessage());
        assertEquals(bare.getViolations().size(), dotted.getViolations().size(),
                "the absent qualified member is dropped the same way");
    }


    /**
     * ⭐ N16 (W3c of {@code PLAN-merged-evaluation-table}): a bare {@code Grouping} member the
     * primary lacks and its {@code SUPP--} delivers is a key column of the one evaluation table —
     * it partitions exactly as the same column carried by the record itself. RED before W3c: the
     * member was dropped as absent (EC-44) and AE's two records were one group. The full reader
     * population, both arms and the SUPP plant: {@code MergedColumnKeyReadersTest}.
     */
    @Test
    void aSuppDeliveredBareKeyPartitionsLikeTheColumnTheRecordCarries()
    {
        Rule rule = loadClean(
                ruleJson("Group", "\"Grouping\":{\"Variables\":[\"USUBJID\",\"AEXCAT\"]},"
                        + check("AESEQ > 0") + "," + outcome("AESEQ")),
                false);
        RealTables base = RealTables.of("AE").str("STUDYID", "S", "S").str("DOMAIN", "AE", "AE")
                .str("USUBJID", "S1", "S1").lng("AESEQ", 1L, 2L);
        IDataTable carrying = base.str("AEXCAT", "A", "B").build();
        IDataTable ae = RealTables.of("AE").str("STUDYID", "S", "S").str("DOMAIN", "AE", "AE")
                .str("USUBJID", "S1", "S1").lng("AESEQ", 1L, 2L).build();
        IDataTable suppAe = RealTables.of("SUPPAE").str("STUDYID", "S", "S")
                .str("RDOMAIN", "AE", "AE").str("USUBJID", "S1", "S1")
                .str("IDVAR", "AESEQ", "AESEQ").str("IDVARVAL", "1", "2")
                .str("QNAM", "AEXCAT", "AEXCAT").str("QLABEL", "x", "x").str("QVAL", "A", "B")
                .build();
        RuleExecutionResult reference = executed(rule, carrying, exactInventory(carrying));
        RuleExecutionResult merged = executed(rule, ae, exactInventory(ae, suppAe));
        assertEquals(2, reference.getViolations().size(), "two AEXCATs, two groups");
        assertEquals(QualifiedNameFixture.values(reference), QualifiedNameFixture.values(merged));
        assertEquals(groupKeys(reference), groupKeys(merged), "the key slab names the qualifier");
    }


    /**
     * W3c (N5 lifted per MQ8): WITHOUT {@code domain=} a qualified {@code group=} member is a
     * column of the one evaluation table, so a {@code find_vars} binding holding it splices into
     * {@code record_count(group=[$b])} and answers exactly as the literal {@code group=[J.G]} — as
     * it does under {@code domain=} since {@code REG-Q4} (the test below).
     */
    @Test
    void aFindVarsBindingOfQualifiedNamesSplicedWithoutDomainReadsLikeTheLiteral()
    {
        IDataTable p = primary();
        IDataTable j = RealTables.of("J").str("K", "k1", "k2", "k3", "k4", "k5", "k6")
                .str("S", "a", "b", "", "A", "b", "c").str("G", "h1", "h1", "h1", "h1", "h2", "h2")
                .build();
        String literal = ruleJson("Record", INNER_J + ","
                + "\"Bindings\":[{\"name\":\"$c\",\"expression\":\"record_count(group=[J.G])\"}],"
                + check("$c == 4") + "," + outcome("K", "$c"));
        String spliced = ruleJson("Record", INNER_J + ","
                + "\"Bindings\":[{\"name\":\"$b\",\"expression\":\"find_vars(\\\"J.G\\\")\"},"
                + "{\"name\":\"$c\",\"expression\":\"record_count(group=[$b])\"}],"
                + check("$c == 4") + "," + outcome("K", "$c"));
        RuleExecutionResult lit = executed(loadClean(literal, true), p, exactInventory(p, j));
        assertEquals(4, lit.getViolations().size(), "J's h1 holds four of P's records");
        assertEquals(QualifiedNameFixture.values(lit), QualifiedNameFixture
                .values(executed(loadClean(spliced, true), p, exactInventory(p, j))));
    }


    /**
     * The row the plan shares with {@code PLAN-stage-a-parameter-type-arming} (plan §3 phase 1),
     * re-measured after {@code REG-Q4}: a {@code find_vars} binding holding a QUALIFIED name
     * splices into {@code record_count(domain=…, group=[$b])} and answers exactly as the literal
     * {@code group=[J.G]} — no load gate is left to judge the spliced name (D-SRC's kind /
     * join-type refusal withdrawn for group members, D-REGEX aligned; plan §5 Q7). Until then
     * {@code GroupSplice} refused the splice (N15: the rule's ERROR).
     */
    @Test
    void aFindVarsBindingOfQualifiedNamesSplicedIntoRecordCountReadsLikeTheLiteral()
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
        RuleExecutionResult viaSplice = executed(loadClean(spliced, true), p, exactInventory(p, j));
        assertEquals(QualifiedNameFixture.values(lit), QualifiedNameFixture.values(viaSplice));
    }
}
