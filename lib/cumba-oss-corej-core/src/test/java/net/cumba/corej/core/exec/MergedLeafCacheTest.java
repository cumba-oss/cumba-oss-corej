package net.cumba.corej.core.exec;

import static net.cumba.corej.core.exec.QualifiedNameFixture.check;
import static net.cumba.corej.core.exec.QualifiedNameFixture.exactInventory;
import static net.cumba.corej.core.exec.QualifiedNameFixture.loadClean;
import static net.cumba.corej.core.exec.QualifiedNameFixture.outcome;
import static net.cumba.corej.core.exec.QualifiedNameFixture.ruleJson;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The carried W2 row of {@code PLAN-merged-evaluation-table} (P-3), landed in W3e:
 * {@code DatasetExpressionCache.cacheableAt} admits a context on the dataset's <b>shared</b> merged
 * view, and keys a leaf that reads a merged column on the <b>view</b> — one instance per merge
 * signature — never on the evaluation table, which two signatures can share (a merge that neither
 * drops nor expands a row keeps the primary itself as the evaluation table).
 */
class MergedLeafCacheTest
{

    /** P: row 0 joins J's first record by K and its second by K2; row 1 the other way round. */
    private static final IDataTable P = RealTables.of("P").str("K", "k1", "k2")
            .str("K2", "k2", "k1").build();

    private static final IDataTable J = RealTables.of("J").str("K", "k1", "k2")
            .str("K2", "k1", "k2").str("S", "x", "y").build();

    private static Rule rule(String id, String key)
    {
        String json = ruleJson("Record", "\"Match_Datasets\":[{\"Name\":\"J\",\"Keys\":[\"" + key
                + "\"],\"Join_Type\":\"left\"}]," + check("J.S == \"x\"") + "," + outcome("K"))
                        .replace("T-QNU", id);
        return loadClean(json, true);
    }


    private static RuleExecutionResult run(Rule rule, @Nullable JoinCache joins,
            ExpressionResultCache cache)
    {
        return RuleRunnerCalls.execute(rule, P, exactInventory(P, J), null, null, joins, null,
                Integer.MAX_VALUE, cache);
    }


    @Test
    @DisplayName("two merge signatures over one evaluation table never share a merged leaf")
    void aMergedLeafIsKeyedOnItsView()
    {
        JoinCache joins = new JoinCache(new JoinCache.SharedIndexCache());
        ExpressionResultCache cache = new ExpressionResultCache();
        RuleExecutionResult byK = run(rule("T-K", "K"), joins, cache);
        RuleExecutionResult byK2 = run(rule("T-K2", "K2"), joins, cache);
        assertEquals(List.of(1L), QualifiedNameFixture.rows(byK), "by K, row 1 reads S = x");
        assertEquals(List.of(2L), QualifiedNameFixture.rows(byK2),
                "by K2, row 2 reads S = x — a key on the shared evaluation table would hand it"
                        + " the K signature's answer");
    }


    @Test
    @DisplayName("one merge signature: the second rule reads the first rule's cached leaf")
    void oneSignatureSharesTheLeaf()
    {
        JoinCache joins = new JoinCache(new JoinCache.SharedIndexCache());
        ExpressionResultCache cache = new ExpressionResultCache();
        run(rule("T-A", "K"), joins, cache);
        int afterFirst = cache.size();
        RuleExecutionResult second = run(rule("T-B", "K"), joins, cache);
        assertEquals(1, afterFirst, "the merged leaf J.S == \"x\" is cached on the shared view");
        assertEquals(afterFirst, cache.size(), "the second rule hits it");
        assertEquals(List.of(1L), QualifiedNameFixture.rows(second));
    }


    @Test
    @DisplayName("a view built per execution (no dataset cache) is never a cache key")
    void aViewBuiltPerExecutionIsNotCached()
    {
        // W6 retired the legacy arm this test used to drive; the one remaining way to a join off
        // the shared view is an execution without a JoinCache — a fresh view per rule
        ExpressionResultCache cache = new ExpressionResultCache();
        RuleExecutionResult r = run(rule("T-L", "K"), null, cache);
        assertEquals(List.of(1L), QualifiedNameFixture.rows(r));
        assertEquals(0, cache.size(), "a per-rule view and its table are never a shared key");
    }
}
