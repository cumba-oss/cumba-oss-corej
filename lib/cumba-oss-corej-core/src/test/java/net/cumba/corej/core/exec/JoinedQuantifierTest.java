package net.cumba.corej.core.exec;

import static net.cumba.corej.core.exec.QualifiedNameFixture.check;
import static net.cumba.corej.core.exec.QualifiedNameFixture.exactInventory;
import static net.cumba.corej.core.exec.QualifiedNameFixture.load;
import static net.cumba.corej.core.exec.QualifiedNameFixture.loadClean;
import static net.cumba.corej.core.exec.QualifiedNameFixture.outcome;
import static net.cumba.corej.core.exec.QualifiedNameFixture.ruleJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.ExprCompiler;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.databuffer.DataBufferFactory;
import net.cumba.datatable.impl.databuffer.IDataBufferNumeric;
import net.cumba.datatable.impl.view.DataTableViewBuffer;
import net.cumba.datatable.impl.view.ViewDataTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-cg0218-transition-day-precision} C7: the quantifier family over joined rows —
 * {@code any} / {@code all} / {@code none} over {@code joined(c)} — evaluated per primary record on
 * B5's merged evaluation table ({@link JoinedRowFold}, P2 / P3 / P8). Findings are compared by
 * <b>real row</b>: a quantifier answers the same on every expanded row of a record, so the rows of
 * one record are one record here, whatever the sink prints per merged row (P6 is the rule's
 * business — it anchors each level on a row-level conjunct).
 */
class JoinedQuantifierTest
{

    /** {@code Match_Datasets} entry {@code name} keyed on {@code key}. */
    private static String match(String name, String key, String joinType)
    {
        return "{\"Name\":\"" + name + "\",\"Keys\":[\"" + key + "\"],\"Join_Type\":\"" + joinType
                + "\"}";
    }


    private static Rule rule(String id, String matches, String expression, String... out)
    {
        return loadClean(ruleJson("Record",
                "\"Match_Datasets\":[" + matches + "]," + check(expression) + "," + outcome(out))
                        .replace("T-QNU", id),
                true);
    }


    private static RuleExecutionResult run(String matches, String expression, IDataTable primary,
            IDataTable... partners)
    {
        IDataTable[] all = new IDataTable[partners.length + 1];
        all[0] = primary;
        System.arraycopy(partners, 0, all, 1, partners.length);
        Rule r = rule("T-JQ", matches, expression, "K");
        return QualifiedNameFixture.executed(r, primary, exactInventory(all));
    }


    /** The distinct real rows (0-based) the result reports. */
    private static Set<Long> realRows(RuleExecutionResult r)
    {
        assertEquals(RuleExecutionStatus.EXECUTED, r.getStatus(), r.getStatusMessage());
        Set<Long> out = new TreeSet<>();
        for (Violation v : r.getViolations())
        {
            out.add(v.getRowNumber() - 1);
        }
        return out;
    }


    /** P: k1, k2, k3. */
    private static IDataTable p()
    {
        return RealTables.of("P").str("K", "k1", "k2", "k3").str("G", "g", "g", "h").build();
    }


    /** J: k1 → a, b; k2 → c; nothing for k3. */
    private static IDataTable j()
    {
        return RealTables.of("J").str("K", "k1", "k1", "k2").str("S", "a", "b", "c").build();
    }

    // ------------------------------------------------------------------ the three quantifiers


    @Test
    @DisplayName("any / all / none over a 1:n inner join answer per record, not per row")
    void quantifiersOverAnInnerOneToManyJoin()
    {
        String inner = match("J", "K", "inner");
        // k1's rows: a, b; k2's: c; k3 is dropped by the inner join
        assertEquals(Set.of(0L), realRows(run(inner, "any(joined(J.S == \"b\"))", p(), j())),
                "some row of k1 is b");
        assertEquals(Set.of(1L), realRows(run(inner, "none(joined(J.S == \"b\"))", p(), j())),
                "no row of k2 is b (k1 has one)");
        assertEquals(Set.of(), realRows(run(inner, "all(joined(J.S == \"b\"))", p(), j())),
                "k1 has a beside b; k2 has c");
        assertEquals(Set.of(0L), realRows(run(inner, "all(joined(J.S != \"c\"))", p(), j())),
                "every row of k1 is not c");
    }


    @Test
    @DisplayName("sabotage control: the bare per-row condition answers per row — the fold moves it")
    void theBareConditionAnswersPerRow()
    {
        String inner = match("J", "K", "inner");
        // the same condition without the quantifier fires on k1 too (its row a is not b): the
        // quantifier is what makes k1 silent, so a vacuous fold would be seen here
        assertEquals(Set.of(0L, 1L), realRows(run(inner, "J.S != \"b\"", p(), j())));
        assertEquals(Set.of(1L), realRows(run(inner, "none(joined(J.S == \"b\"))", p(), j())));
    }


    @Test
    @DisplayName("not any(joined(c)) is none(joined(c))")
    void notAnyIsNone()
    {
        String inner = match("J", "K", "inner");
        assertEquals(realRows(run(inner, "none(joined(J.S == \"b\"))", p(), j())),
                realRows(run(inner, "not any(joined(J.S == \"b\"))", p(), j())));
        assertEquals(realRows(run(inner, "any(joined(J.S == \"b\"))", p(), j())),
                realRows(run(inner, "not none(joined(J.S == \"b\"))", p(), j())));
    }

    // ------------------------------------------------------------------ the domain (P2)


    @Test
    @DisplayName("a left join's unmatched record keeps its placeholder row in the domain")
    void anUnmatchedPlaceholderRowIsInTheDomain()
    {
        String left = match("J", "K", "left");
        // k3 has no partner: its one placeholder row is the record's domain
        assertEquals(Set.of(1L, 2L), realRows(run(left, "none(joined(J.S == \"b\"))", p(), j())));
        assertEquals(Set.of(2L), realRows(run(left, "all(joined(not J._matched_))", p(), j())),
                "the author guards with the match flag, as everywhere else (D88)");
        assertEquals(Set.of(0L, 1L),
                realRows(run(left, "any(joined(J._matched_ and J.S != \"zz\"))", p(), j())));
    }


    @Test
    @DisplayName("a table no record repeats on: any / all answer the condition, none its negation")
    void aNonExpandedTableIsTheConditionItself()
    {
        // one J row per key: the merge drops nothing and expands nothing
        IDataTable one = RealTables.of("J").str("K", "k1", "k2", "k3").str("S", "a", "b", "c")
                .build();
        String inner = match("J", "K", "inner");
        assertEquals(realRows(run(inner, "J.S == \"b\"", p(), one)),
                realRows(run(inner, "any(joined(J.S == \"b\"))", p(), one)));
        assertEquals(realRows(run(inner, "J.S == \"b\"", p(), one)),
                realRows(run(inner, "all(joined(J.S == \"b\"))", p(), one)));
        assertEquals(Set.of(0L, 2L), realRows(run(inner, "none(joined(J.S == \"b\"))", p(), one)));
    }


    @Test
    @DisplayName("two expanding entries: the domain is their combinations, duplication is harmless")
    void twoExpandingEntriesCompose()
    {
        // J by K (k1 → a, b) and J2 by G (g → p, q): k1 is 2 × 2 = 4 combinations, k2 is 1 × 2
        IDataTable j2 = RealTables.of("J2").str("G", "g", "g", "h").str("T", "p", "q", "r").build();
        String both = match("J", "K", "inner") + "," + match("J2", "G", "inner");
        assertEquals(Set.of(0L),
                realRows(run(both, "any(joined(J.S == \"b\" and J2.T == \"q\"))", p(), j(), j2)),
                "some combination of k1 is (b, q)");
        assertEquals(Set.of(0L, 1L),
                realRows(run(both, "all(joined(J.S != \"zz\"))", p(), j(), j2)),
                "every combination of every kept record satisfies it: the copies do not count");
        assertEquals(Set.of(1L), realRows(run(both, "none(joined(J.S == \"b\"))", p(), j(), j2)));
    }


    @Test
    @DisplayName("a RELREC expansion: the related records of one AE record are its domain")
    void aRelrecExpansionGroupsByRecord()
    {
        // AE seq 1 → CM seq 1 (ASPIRIN) and seq 2 (IBUPROFEN); AE seq 2 → CM seq 3 (OMEPRAZOLE)
        IDataTable ae = RealTables.of("AE").str("STUDYID", "S1", "S1").str("DOMAIN", "AE", "AE")
                .str("USUBJID", "01", "01").lng("AESEQ", 1L, 2L).str("K", "A", "B").build();
        IDataTable cm = RealTables.of("CM").str("STUDYID", "S1", "S1", "S1")
                .str("DOMAIN", "CM", "CM", "CM").str("USUBJID", "01", "01", "01")
                .lng("CMSEQ", 1L, 2L, 3L).str("CMTRT", "ASPIRIN", "IBUPROFEN", "OMEPRAZOLE")
                .build();
        IDataTable relrec = RealTables.of("RELREC").str("STUDYID", "S1", "S1", "S1", "S1", "S1")
                .str("RDOMAIN", "AE", "CM", "CM", "AE", "CM")
                .str("USUBJID", "01", "01", "01", "01", "01")
                .str("IDVAR", "AESEQ", "CMSEQ", "CMSEQ", "AESEQ", "CMSEQ")
                .str("IDVARVAL", "1", "1", "2", "2", "3").str("RELID", "R1", "R1", "R1", "R2", "R2")
                .build();
        Rule r = rule("T-JQ", "{\"Name\":\"RELREC\"}", "any(joined(CM.CMTRT == \"IBUPROFEN\"))",
                "K");
        RuleExecutionResult result = QualifiedNameFixture.executed(r, ae,
                exactInventory(ae, cm, relrec));
        assertEquals(Set.of(0L), realRows(result), "only AE seq 1 relates to IBUPROFEN");
        Rule none = rule("T-JQ", "{\"Name\":\"RELREC\"}", "none(joined(CM.CMTRT == \"IBUPROFEN\"))",
                "K");
        assertEquals(Set.of(1L),
                realRows(QualifiedNameFixture.executed(none, ae, exactInventory(ae, cm, relrec))));
    }


    @Test
    @DisplayName("§4.1: the quantifier groups by visit, never by subject")
    void groupsByRecordNeverBySubject()
    {
        // two visits of one subject against three elements: visit 1's FOLLOW-UP match must not
        // silence visit 0 (EPOCH X matches nothing), and visit 0's rows must not leak into visit 1
        IDataTable sv = RealTables.of("SV").str("USUBJID", "S1", "S1").str("K", "v0", "v1")
                .str("EPOCH", "X", "FOLLOW-UP").build();
        IDataTable se = RealTables.of("SE").str("USUBJID", "S1", "S1", "S1")
                .str("EPOCH", "SCREENING", "TREATMENT", "FOLLOW-UP").build();
        String inner = match("SE", "USUBJID", "inner");
        assertEquals(Set.of(0L), realRows(run(inner, "none(joined(EPOCH == SE.EPOCH))", sv, se)));
        assertEquals(Set.of(1L), realRows(run(inner, "any(joined(EPOCH == SE.EPOCH))", sv, se)));
    }

    // ------------------------------------------------------------------ keyed on RealRows (P8)


    /** {@code base} without its first row, in reverse order: a filtered and sorted view. */
    private static IDataTable filteredAndReversed(IDataTable base)
    {
        int n = Math.toIntExact(base.getRowCount());
        IDataBufferNumeric map = DataBufferFactory.get().createForRange(0, Math.max(0, n - 1L));
        map.setExpectedSize(n - 1);
        for (int i = n - 1; i >= 1; i--)
        {
            map.addValue(i);
        }
        return new ViewDataTable(base, new DataTableViewBuffer(map), null);
    }


    @Test
    @DisplayName("a filtered and sorted primary: grouped by real row, reported on the right record")
    void aFilteredAndSortedPrimaryGroupsByRealRow()
    {
        // P4: k1, k2, k3, k4; the view drops k1 and reverses: k4, k3, k2. J: k2 → a, b; k3 → c;
        // k4 → d, e. Real rows are the BASE rows (k2 = 1, k3 = 2, k4 = 3).
        IDataTable base = RealTables.of("P").str("K", "k1", "k2", "k3", "k4").build();
        IDataTable view = filteredAndReversed(base);
        IDataTable jj = RealTables.of("J").str("K", "k2", "k2", "k3", "k4", "k4")
                .str("S", "a", "b", "c", "d", "e").build();
        String inner = match("J", "K", "inner");
        assertEquals(Set.of(1L), realRows(run(inner, "any(joined(J.S == \"b\"))", view, jj)),
                "k2 (base row 1) has b");
        assertEquals(Set.of(2L, 3L), realRows(run(inner, "none(joined(J.S == \"b\"))", view, jj)));
        assertEquals(Set.of(3L),
                realRows(run(inner, "all(joined(J.S in [\"d\", \"e\"]))", view, jj)),
                "k4 (base row 3): every row is d or e");
        // the control: the unmerged view reports the same real rows for a per-row condition
        assertEquals(Set.of(1L), realRows(run(inner, "J.S == \"b\"", view, jj)));
    }


    @Test
    @DisplayName("a shared cached condition is read, never mutated, by the fold")
    void aSharedCachedConditionIsLeftUnchanged()
    {
        // The pure leaf J.S == "b" is the maximal pure subtree inside the quantifier and is the
        // whole Check of the second rule: one cache entry, read by both — this pins the SHARING
        // (one entry, two readers, the same answers on a re-run). It cannot see a fold writing
        // into the set: cachedBool hands every reader its own clone (review C8 L1). The
        // never-mutated guarantee is theFoldWritesANewSet's, over a repeating table.
        JoinCache joins = new JoinCache(new JoinCache.SharedIndexCache());
        ExpressionResultCache cache = new ExpressionResultCache();
        Rule none = rule("T-A", match("J", "K", "inner"), "none(joined(J.S == \"b\"))", "K");
        Rule bare = rule("T-B", match("J", "K", "inner"), "J.S == \"b\"", "K");
        IDataTable primary = p();
        DatasetResolver inventory = exactInventory(primary, j());
        RuleExecutionResult first = RuleRunnerCalls.execute(none, primary, inventory, null, null,
                joins, null, Integer.MAX_VALUE, cache);
        assertEquals(Set.of(1L), realRows(first));
        assertEquals(1, cache.size(), "the inner condition is the cached leaf");
        RuleExecutionResult second = RuleRunnerCalls.execute(bare, primary, inventory, null, null,
                joins, null, Integer.MAX_VALUE, cache);
        assertEquals(1, cache.size(), "the bare rule hits the same entry");
        assertEquals(Set.of(0L), realRows(second), "k1's row b, read from the shared entry");
        RuleExecutionResult again = RuleRunnerCalls.execute(none, primary, inventory, null, null,
                joins, null, Integer.MAX_VALUE, cache);
        assertEquals(Set.of(1L), realRows(again), "the fold reads the entry a second time");
    }

    // ------------------------------------------------------------------ the fold itself


    @Test
    @DisplayName("JoinedRowFold never writes into the condition it folds")
    void theFoldWritesANewSet()
    {
        // Over a REPEATING table from a real 1:n run (k1 twice, k2 once; k3 dropped by the inner
        // join), so the fold takes its record branch — a non-row-mapped table only reaches the
        // "every record is its one row" branch (review C8 L1). Sabotage JoinedRowFold's record
        // branch to write in place and both assertions go red.
        MergedEvaluation.Built built = MergedEvaluation.build(
                rule("T-F", match("J", "K", "inner"), "J.S == \"b\"", "K"), p(), p(),
                exactInventory(p(), j()), null, "R", null);
        assertNotNull(built);
        IDataTable table = built.table();
        assertEquals(3, table.getRowCount(), "k1 twice, k2 once");
        assertNotNull(RealRows.ofRepeating(table), "the premise: a record repeats");
        EvaluationContext ctx = EvaluationContext.builder().table(table).build();
        BitSet condition = new BitSet();
        condition.set(1); // k1's second row (J.S == "b")
        BitSet copy = (BitSet) condition.clone();
        for (JoinedRowFold.Quantifier q : JoinedRowFold.Quantifier.values())
        {
            BitSet out = JoinedRowFold.fold(q, condition, ctx, 3);
            assertTrue(out != condition, q + " answers a new set");
            assertEquals(copy, condition, q + " left the condition unchanged");
        }
        assertEquals(bits(0, 1),
                JoinedRowFold.fold(JoinedRowFold.Quantifier.ANY, condition, ctx, 3),
                "k1's two rows");
        assertEquals(bits(), JoinedRowFold.fold(JoinedRowFold.Quantifier.ALL, condition, ctx, 3),
                "k1 has a false row, k2's only row is false");
        assertEquals(bits(2), JoinedRowFold.fold(JoinedRowFold.Quantifier.NONE, condition, ctx, 3),
                "k2 alone has no true row");
    }


    @Test
    @DisplayName("a quantifier nested inside joined(…) is refused by the compiler, as at stage A")
    void aNestedQuantifierIsRefusedByTheCompilerToo()
    {
        // review C8 L2: compileJoinedQuantifier's javadoc promised the refusal stage A makes
        // (P4) — the compiler now makes it too, so a rule reaching it without stage A (an
        // embedder's direct compile) cannot fold a quantifier over a quantifier
        Expr nested = CheckExpressionParser.parse("any(joined(none(joined(J.S == \"b\"))))");
        ExpressionException e = assertThrows(ExpressionException.class,
                () -> ExprCompiler.compile(nested));
        assertTrue(e.getMessage().contains("nest"), e.getMessage());
        assertTrue(e.getMessage().contains("none"), e.getMessage());
        // the control: one quantifier compiles
        assertNotNull(
                ExprCompiler.compile(CheckExpressionParser.parse("any(joined(J.S == \"b\"))")));
    }


    @Test
    @DisplayName("joined(…) without an entry to range over is a load error (P5)")
    void joinedWithoutAJoinIsALoadError()
    {
        Rule r = load(ruleJson("Record", check("any(joined(K == \"k1\"))") + "," + outcome("K")),
                false);
        assertNotNull(r.getLoadError(), "no Match_Datasets entry: the rule must not load");
        assertTrue(r.getLoadError().contains("joined("), r.getLoadError());
    }


    private static BitSet bits(int... set)
    {
        BitSet out = new BitSet();
        for (int i : set)
        {
            out.set(i);
        }
        return out;
    }
}
