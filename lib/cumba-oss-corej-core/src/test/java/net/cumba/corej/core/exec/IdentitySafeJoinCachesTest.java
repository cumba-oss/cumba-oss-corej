package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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

    // ---------------------------------------------------------------- lookup indexes (getOrBuild)


    @Test
    void lookupIndexes_collidingTablesStayApartAndMatchTheUncachedBuild()
    {
        IDataTable a = MockTable.of().name("DM").col("USUBJID", "P1").col("VAL", "a0").build();
        IDataTable b = MockTable.of().name("DM").col("USUBJID", "P2", "P1").col("VAL", "b0", "b1")
                .build();
        IDataTable primary = MockTable.of().name("AE").col("USUBJID", "P1").build();
        JoinCache.SharedIndexCache cache = new JoinCache.SharedIndexCache(
                IdentitySafeJoinCachesTest::oneBucket);

        for (IDataTable first : List.of(a, b))
        {
            IDataTable second = first == a ? b : a;
            assertEquals(uncached(first, primary), viaCache(cache, first, primary));
            assertEquals(uncached(second, primary), viaCache(cache, second, primary),
                    "one bucket, same key columns: only identity may separate the two tables");
        }
        assertEquals("a0", viaCache(cache, a, primary));
        assertEquals("b1", viaCache(cache, b, primary));
        assertEquals(2, cache.lookupIndexBuildCount(), "one build per table, then hits");
    }


    @Test
    void lookupIndexes_keyColumnOrderIsPartOfTheKey()
    {
        IDataTable dm = MockTable.of().name("DM").col("A", "1", "2").col("B", "2", "1")
                .col("VAL", "r0", "r1").build();
        IDataTable primary = MockTable.of().name("AE").col("A", "2").col("B", "1").build();
        JoinCache.SharedIndexCache cache = new JoinCache.SharedIndexCache();
        List<String> ab = List.of("A", "B");
        List<String> ba = List.of("B", "A");
        String viaAb = java.util.Objects
                .requireNonNull(DatasetLookup.build("DM", dm, ab, cache.getOrBuild(dm, ab)))
                .lookup(primary, 0, "VAL");
        String viaBa = java.util.Objects
                .requireNonNull(DatasetLookup.build("DM", dm, ba, cache.getOrBuild(dm, ba)))
                .lookup(primary, 0, "VAL");
        assertEquals("r1", viaAb);
        assertEquals("r1", viaBa, "an index built for [A,B] must not serve [B,A]");
        assertEquals(2, cache.lookupIndexBuildCount());
    }


    private static @Nullable String viaCache(JoinCache.SharedIndexCache aCache, IDataTable aJoined,
            IDataTable aPrimary)
    {
        return java.util.Objects.requireNonNull(
                DatasetLookup.build("DM", aJoined, USUBJID, aCache.getOrBuild(aJoined, USUBJID)))
                .lookup(aPrimary, 0, "VAL");
    }


    private static @Nullable String uncached(IDataTable aJoined, IDataTable aPrimary)
    {
        return java.util.Objects.requireNonNull(DatasetLookup.build("DM", aJoined, USUBJID))
                .lookup(aPrimary, 0, "VAL");
    }

    // ----------------------------------------------------------------------- child-match indexes


    @Test
    void childMatch_collidingParentsStayApartAndANullBuildNeverLeaks()
    {
        IDataTable withIdvar = MockTable.of().name("AE").col("USUBJID", "P1").col("AESEQ", "1")
                .build();
        IDataTable withoutIdvar = MockTable.of().name("AE").col("USUBJID", "P1").build();
        for (boolean nullFirst : new boolean[]
        {
                false, true
        })
        {
            JoinCache.SharedIndexCache cache = new JoinCache.SharedIndexCache(
                    IdentitySafeJoinCachesTest::oneBucket);
            if (nullFirst)
            {
                assertNull(cache.getOrBuildChildMatchIndex(withoutIdvar, USUBJID, "AESEQ"));
            }
            assertNotNull(cache.getOrBuildChildMatchIndex(withIdvar, USUBJID, "AESEQ"),
                    "a buildable parent must not read another parent's cached null");
            assertNull(cache.getOrBuildChildMatchIndex(withoutIdvar, USUBJID, "AESEQ"),
                    "a parent lacking the IDVAR must not read another parent's index");
            assertEquals(2, cache.childMatchIndexBuildCount());
        }
    }


    @Test
    void childMatch_idvarAndStandardKeysArePartOfTheKey()
    {
        IDataTable parent = MockTable.of().name("AE").col("USUBJID", "P1").col("AESEQ", "1")
                .col("STUDYID", "S").build();
        JoinCache.SharedIndexCache cache = new JoinCache.SharedIndexCache();
        assertNull(cache.getOrBuildChildMatchIndex(parent, USUBJID, "AELNKID"),
                "no AELNKID column: the build fails and that null is cached");
        assertNotNull(cache.getOrBuildChildMatchIndex(parent, USUBJID, "AESEQ"),
                "a different IDVAR is a different index, not the cached null");
        assertNotNull(
                cache.getOrBuildChildMatchIndex(parent, List.of("USUBJID", "STUDYID"), "AESEQ"));
        assertNull(cache.getOrBuildChildMatchIndex(parent, List.of("USUBJID", "NOSUCH"), "AESEQ"),
                "different standard keys are a different index");
        assertEquals(4, cache.childMatchIndexBuildCount());
        assertEquals(4, cache.childMatchIndexCount());
    }

    // ------------------------------------------------------------------------- key-match indexes


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
        assertEquals(2, cache.keyMatchIndexBuildCount());
    }


    private static List<String> expansion(IDataTable aPrimary, MatchDataset aEntry,
            IDataTable aChild, JoinCache.@Nullable SharedIndexCache aCache)
    {
        KeyMatchRowExpander.KeyMatchExpansion exp = ExecCalls.expand(aPrimary, List.of(aEntry),
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
        while ((probe.get() != null || cache.lookupIndexedTableCount() != 0
                || cache.childMatchIndexedTableCount() != 0
                || cache.keyMatchIndexedTableCount() != 0
                || cache.wildcardColumns().entryCount() != 0) && System.nanoTime() < deadline)
        {
            System.gc();
            Thread.sleep(10);
        }
        assertNull(probe.get(), "no cache may keep the table reachable (D3: no parent field)");
        assertEquals(0, cache.lookupIndexedTableCount());
        assertEquals(0, cache.childMatchIndexedTableCount());
        assertEquals(0, cache.keyMatchIndexedTableCount());
        assertEquals(0, cache.wildcardColumns().entryCount());
    }


    /** Populates all four caches from one table that nothing else keeps. */
    private static WeakReference<IDataTable> populateAll(JoinCache.SharedIndexCache aCache)
    {
        IDataTable t = MockTable.of().name("AE").col("USUBJID", "P1").col("AESEQ", "1")
                .col("TRT01PN", "7").build();
        try
        {
            aCache.getOrBuild(t, USUBJID);
            assertNotNull(aCache.getOrBuildChildMatchIndex(t, USUBJID, "AESEQ"));
            // Through the REAL expander, so the index is built by reading t (review round 1, lane
            // A, LOW): an index or closure that kept its child would then show here.
            MatchDataset md = new MatchDataset();
            md.setName("AE");
            md.setKeys(USUBJID);
            md.setJoinType("left");
            IDataTable primary = MockTable.of().name("ADAE").col("USUBJID", "P1").build();
            assertNotNull(
                    ExecCalls.expand(primary, List.of(md), Map.of("AE", t)::get, "R", aCache));
            aCache.wildcardColumns().matchingColumns(t, Pattern.compile("TRT.*PN"));
            assertEquals(1, aCache.lookupIndexedTableCount());
            assertEquals(1, aCache.childMatchIndexedTableCount());
            assertEquals(1, aCache.keyMatchIndexedTableCount());
            assertEquals(1, aCache.wildcardColumns().entryCount());
            return new WeakReference<>(t);
        }
        finally
        {
            Reference.reachabilityFence(t);
        }
    }

    // --------------------------------------------------------------------- D5: lookupCache


    @Test
    void lookupCache_servesALookupOnlyForTheTableItWasBuiltOver()
    {
        IDataTable dm1 = MockTable.of().name("DM").col("USUBJID", "P1").col("VAL", "first").build();
        IDataTable dm2 = MockTable.of().name("DM").col("USUBJID", "P1").col("VAL", "second")
                .build();
        IDataTable primary = MockTable.of().name("AE").col("USUBJID", "P1").build();
        JoinCache jc = new JoinCache(new JoinCache.SharedIndexCache());

        DatasetLookup l1 = jc.getOrBuildLookup("DM", dm1, USUBJID);
        DatasetLookup l2 = jc.getOrBuildLookup("DM", dm2, USUBJID);
        assertNotNull(l1);
        assertNotNull(l2);
        assertNotSame(l1, l2, "same NAME, different table instance: never the old lookup");
        assertSame(dm2, l2.dataset());
        assertEquals("second", l2.lookup(primary, 0, "VAL"));
        assertSame(l2, jc.getOrBuildLookup("DM", dm2, USUBJID), "the same instance again is a hit");
    }

    // ------------------------------------------------------------------- D9: join-map race


    /**
     * ⭐ D9, deterministic: thread A validates the published map for primary P1 and is HELD by the
     * seam before reading it; meanwhile another primary table's map is published. A must still read
     * P1's map. The pre-fix code re-read the field after validation and read P2's map instead.
     */
    @Test
    void joinMap_aCallerReadsTheMapItValidatedEvenIfAnotherIsPublishedMeanwhile() throws Exception
    {
        IDataTable dm = MockTable.of().name("DM").col("USUBJID", "P1", "P2").col("VAL", "d1", "d2")
                .build();
        IDataTable p1 = MockTable.of().name("AE").col("USUBJID", "P1").build();
        IDataTable p2 = MockTable.of().name("CM").col("USUBJID", "P2").build();
        DatasetLookup lookup = DatasetLookup.build("DM", dm, USUBJID);
        assertNotNull(lookup);
        assertEquals("d1", lookup.lookup(p1, 0, "VAL"), "publishes P1's map");

        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newSingleThreadExecutor())
        {
            Thread[] worker = new Thread[1];
            lookup.setAfterJoinMapEnsuredForTest(holdOnce(worker, held, release));
            Future<@Nullable String> a = pool.submit(() ->
            {
                worker[0] = Thread.currentThread();
                return lookup.lookup(p1, 0, "VAL");
            });
            try
            {
                assertTrue(held.await(30, TimeUnit.SECONDS), "thread A never reached the seam");
                lookup.setAfterJoinMapEnsuredForTest(null);
                assertEquals("d2", lookup.lookup(p2, 0, "VAL"), "publishes P2's map meanwhile");
            }
            finally
            {
                release.countDown();
            }
            assertEquals("d1", a.get(30, TimeUnit.SECONDS),
                    "thread A validated P1's map and must read THAT one, not P2's");
        }
    }


    /** A seam that parks the worker thread once, the first time it passes. */
    private static Runnable holdOnce(Thread[] aWorker, CountDownLatch aHeld,
            CountDownLatch aRelease)
    {
        Consumer<CountDownLatch> await = latch ->
        {
            try
            {
                assertTrue(latch.await(30, TimeUnit.SECONDS), "the test never released A");
            }
            catch (InterruptedException ex)
            {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        };
        return () ->
        {
            if (Thread.currentThread() == aWorker[0] && aHeld.getCount() > 0)
            {
                aHeld.countDown();
                await.accept(aRelease);
            }
        };
    }
}
