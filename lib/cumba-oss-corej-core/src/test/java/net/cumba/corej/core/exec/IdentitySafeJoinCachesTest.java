package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Scope;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-identity-safe-join-caches} Phase 1 — every table-keyed engine cache hits only for the
 * very table instance it was built from.
 *
 * <p>
 * ⭐⭐ <b>How a hit is proven exact.</b> In production two live tables share an
 * {@code identityHashCode} rarely, so no ordinary test can see whether a hit is validated. Every
 * collision test here therefore builds its cache with the seam {@code _ -> 42}: EVERY table lands
 * in ONE bucket, so only reference identity can tell them apart. ⚠ And the two tables of each test
 * carry IDENTICAL sub-keys (key names, IDVAR, regex) — otherwise the sub-key alone would separate
 * them and the test would prove nothing about identity (pre-go review, LOW).
 * </p>
 */
class IdentitySafeJoinCachesTest
{

    private static final List<String> USUBJID = List.of("USUBJID");

    /**
     * The bucket hash that forces every table into one bucket. ⚠ It deliberately ignores its
     * argument — that is the seam's whole point (every table collides) — hence the suppression.
     */
    @SuppressWarnings("PMD.UnusedFormalParameter")
    private static int oneBucket(Object aTable)
    {
        return 42;
    }

    // ----------------------------------------------------------- keyed parts of the merged view


    /**
     * The key-match index this test was written for retired in W6 of
     * {@code PLAN-merged-evaluation-table} (the datatable's shared matching indexes a view's
     * partners); its fixture still pins what the view joins, with and without the run's cache, for
     * two children colliding in one identity bucket.
     */
    @Test
    void keyMatch_collidingChildrenStayApartAndMatchTheUncachedExpansion()
    {
        IDataTable primary = MockTable.of().name("ADAE").col("USUBJID", "P1", "P2").build();
        IDataTable ae1 = MockTable.of().name("AE").col("USUBJID", "P1").col("ROWID", "A0").build();
        IDataTable ae2 = MockTable.of().name("AE").col("USUBJID", "P2", "P2")
                .col("ROWID", "B0", "B1").build();
        MatchDataset md = new MatchDataset();
        md.setName("AE");
        md.setKeys(USUBJID);
        md.setJoinType("left");
        JoinCache.SharedIndexCache cache = new JoinCache.SharedIndexCache(
                IdentitySafeJoinCachesTest::oneBucket);
        for (IDataTable child : List.of(ae1, ae2, ae1, ae2))
        {
            assertEquals(expansion(primary, md, child, null), expansion(primary, md, child, cache),
                    "one bucket, one SpecKey: only identity may separate the two children");
        }
        assertEquals(List.of("0:A0", "1:null"), expansion(primary, md, ae1, cache));
        assertEquals(List.of("0:null", "1:B0", "1:B1"), expansion(primary, md, ae2, cache));
    }


    private static List<String> expansion(IDataTable aPrimary, MatchDataset aEntry,
            IDataTable aChild, JoinCache.@Nullable SharedIndexCache aCache)
    {
        MergedEvaluation.Built exp = ExecCalls.expand(aPrimary, List.of(aEntry),
                Map.of("AE", aChild)::get, "R", aCache);
        assertNotNull(exp);
        IDataTable t = exp.table();
        JoinLookup lk = exp.lookups().get("AE");
        List<String> out = new java.util.ArrayList<>();
        for (long i = 0; i < t.getRowCount(); i++)
        {
            out.add(t.getRealRowIndex(i) + ":" + lk.lookup(t, i, "ROWID"));
        }
        return out;
    }

    // ---------------------------------------------------------------------- wildcard column sets


    @Test
    void wildcard_collidingTablesStayApart()
    {
        IDataTable t1 = MockTable.of().col("TRT01PN", "1").col("X", "x").build();
        IDataTable t2 = MockTable.of().col("Y", "y").col("TRT02PN", "2").col("TRT03PN", "3")
                .build();
        WildcardForeignColumnCache cache = new WildcardForeignColumnCache(
                new IdentityWeakCache<>(IdentitySafeJoinCachesTest::oneBucket));
        Pattern p = Pattern.compile("TRT.*PN");
        assertArrayEquals(new int[]
        {
                0
        }, cache.matchingColumns(t1, p));
        assertArrayEquals(new int[]
        {
                1, 2
        }, cache.matchingColumns(t2, p), "one bucket, one regex: only identity separates them");
        assertArrayEquals(new int[]
        {
                0
        }, cache.matchingColumns(t1, p));
        assertEquals(2, cache.computeCount());
    }


    @Test
    void wildcard_regexTextAndFlagsAreTheKeyNotThePatternInstance()
    {
        IDataTable t = MockTable.of().col("trt01pn", "1").col("TRT02PN", "2").col("Z", "z").build();
        WildcardForeignColumnCache cache = new WildcardForeignColumnCache();
        // A driver-bearing wildcard compiles a NEW Pattern per row: same text must HIT.
        for (int row = 0; row < 100; row++)
        {
            assertArrayEquals(new int[]
            {
                    1
            }, cache.matchingColumns(t, Pattern.compile("TRT.*PN")));
        }
        assertEquals(1, cache.computeCount(), "a recompiled pattern with the same text is a hit");
        assertArrayEquals(new int[]
        {
                0, 1
        }, cache.matchingColumns(t, Pattern.compile("TRT.*PN", Pattern.CASE_INSENSITIVE)),
                "flags are part of the key: case-insensitive matches the lower-case column too");
        // Varying driver values: one entry per DISTINCT regex, bounded by the run.
        for (String driver : List.of("01", "02", "01", "02", "03"))
        {
            cache.matchingColumns(t, Pattern.compile("TRT" + driver + "PN"));
        }
        assertEquals(2 + 3, cache.entryCount(),
                "2 earlier keys + 3 distinct driver regexes — not one entry per row");
    }


    /**
     * ⭐ Root wiring (pre-go review M2): the run's cache must be the one {@code RuleRunner.execute}
     * uses. An entry-count test on a hand-built cache cannot see a context root that forgot to pass
     * it, so this goes through {@code execute} and asserts the RUN cache computed — and then HIT on
     * a second rule.
     */
    @Test
    void wildcard_ruleRunnerUsesTheRunsCacheAndHitsAcrossRules()
    {
        IDataTable primary = RealTables.of("ADLB").str("USUBJID", "U1").str("LBSEQ", "1").build();
        IDataTable lb = RealTables.of("LB").str("USUBJID", "U1").str("LBSEQ", "1")
                .str("TRT01PN", "d1").build();
        DatasetResolver resolver = RealTables.inventoryOf(primary, lb);
        JoinCache.SharedIndexCache shared = new JoinCache.SharedIndexCache();

        RuleExecutionResult first = RuleRunnerCalls.execute(wildcardRule("R-1"), primary, resolver,
                (String) null, null, new JoinCache(shared));
        assertEquals("d1", first.getViolations().get(0).getValues().get("LB.TRT01PN"),
                "the fixture must actually expand the wildcard");
        assertEquals(1, shared.wildcardColumns().computeCount(),
                "the rule must have used the RUN's wildcard cache, not a private one");

        RuleRunnerCalls.execute(wildcardRule("R-2"), primary, resolver, (String) null, null,
                new JoinCache(shared));
        assertEquals(1, shared.wildcardColumns().computeCount(),
                "a second rule of the same run must HIT the shared entry");
    }


    private static Rule wildcardRule(String aId)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId(aId);
        rule.setCore(core);
        rule.setScope(new Scope());
        rule.setSensitivity(Sensitivity.RECORD);
        Outcome outcome = new Outcome();
        outcome.setMessage("fires everywhere");
        outcome.setOutputVariables(List.of("USUBJID", "LB.TRT${*}PN"));
        rule.setOutcome(outcome);
        MatchDataset md = new MatchDataset();
        md.setName("LB");
        md.setKeys(List.of("USUBJID", "LBSEQ"));
        md.setJoinType("left");
        rule.setMatchDatasets(List.of(md));
        rule.setCheck(new CheckConditionAll(
                List.of(new net.cumba.corej.core.model.CheckConditionExpression(
                        net.cumba.corej.core.expr.CheckExpressionParser.parse("not empty(USUBJID)"),
                        "not empty(USUBJID)"))));
        RulePackageLoader.installNativeExpr(rule);
        return rule;
    }

    // ------------------------------------------------------------------------------- lifetime


    /** Q3 (d) for every cache: once nothing but the cache refers to a table, its entries go. */
    @Test
    void everyCacheDropsACollectedTablesEntries() throws InterruptedException
    {
        JoinCache.SharedIndexCache cache = new JoinCache.SharedIndexCache();
        WeakReference<IDataTable> probe = populateAll(cache);
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (probe.get() != null && System.nanoTime() < deadline)
        {
            System.gc();
            Thread.sleep(10);
        }
        assertNull(probe.get(), "no cache may keep the table reachable (D3: no parent field)");
        // The collector clears every weak reference to the table at once, so the cache's own
        // references are cleared by now: one count, which sweeps, must find nothing left. (Waiting
        // for the count as well would let a sweep that never removes anything spin to the
        // deadline.)
        assertEquals(0, cache.wildcardColumns().entryCount());
    }


    /**
     * Populates the run's caches from one table that nothing else keeps — the wildcard column sets,
     * and the merged schema's QNAM set of it as a SUPP table — and builds a merged view over it
     * through the run's cache, so a view or closure that kept it would show here. (The lookup and
     * key-match index caches it also populated retired in W6 of
     * {@code PLAN-merged-evaluation-table}.)
     */
    private static WeakReference<IDataTable> populateAll(JoinCache.SharedIndexCache aCache)
    {
        // ⚠ A REAL table, not a MockTable: Mockito keeps the last mock invoked on a thread in its
        // thread-local stubbing progress, so a mock read last (here getMetaData, since the
        // testkit's metas are real — T1 of PLAN-merged-evaluation-table) stays reachable from
        // the test thread and this lifetime check could never see it go.
        IDataTable t = RealTables.of("AE").str("USUBJID", "P1").str("AESEQ", "1")
                .str("TRT01PN", "7").build();
        try
        {
            MatchDataset md = new MatchDataset();
            md.setName("AE");
            md.setKeys(USUBJID);
            md.setJoinType("left");
            IDataTable primary = MockTable.of().name("ADAE").col("USUBJID", "P1").build();
            assertNotNull(
                    ExecCalls.expand(primary, List.of(md), Map.of("AE", t)::get, "R", aCache));
            aCache.getOrBuildSuppQnams(t, _ -> java.util.Set.of("X"));
            aCache.wildcardColumns().matchingColumns(t, Pattern.compile("TRT.*PN"));
            assertEquals(1, aCache.suppQnamsBuildCount());
            assertEquals(1, aCache.wildcardColumns().entryCount());
            return new WeakReference<>(t);
        }
        finally
        {
            Reference.reachabilityFence(t);
        }
    }
}
