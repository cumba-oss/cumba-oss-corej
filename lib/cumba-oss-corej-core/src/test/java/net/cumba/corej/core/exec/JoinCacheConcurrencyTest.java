package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Thread-safety regression gate for {@link RuleRunner#execute} driven from many worker threads
 * against one shared {@link JoinCache} and one shared primary table.
 *
 * <p>
 * ⚠⚠ <b>Two entry shapes, one cache.</b> A plain keyed {@code Match_Datasets} entry
 * ({@link #buildJoinRule}: {@code {ADSL, [USUBJID]}}) and a {@code SUPP}-prefixed one
 * ({@link #buildCachedJoinRule}, outside the key-type check) are both keyed parts of the merged
 * evaluation view, built once per merge signature on the dataset's {@link JoinCache} (P-3 of
 * {@code PLAN-merged-evaluation-table}) — the structure every worker races for. The cases gate the
 * determinism of the view under concurrency (every parallel result equals the sequential baseline),
 * that one view is built per signature ({@code mergedViewCount() == 1}), and that a warm cache
 * keeps serving the instance the cold-start wave built. ⚠ The build count is best-effort under a
 * race (a tiny ADSL builds in microseconds while each worker spends milliseconds in
 * {@code RuleRunner.execute} setup). (Until W6 the plain entry was served by the key-match
 * expander's shared index and the {@code SUPP} one by a cached {@code DatasetLookup}; both retired
 * with the legacy pipeline.)
 * </p>
 *
 * <p>
 * The fixtures are real {@code ColumnCachedDataTable}s over {@code CachedDataTableColumn}s
 * ({@link RealTables}), the shape every loaded dataset has, so the datatable's join-key reads take
 * their production fast path (the datatable's own differential covers every buffer class). Not
 * {@code MockTable} (PLAN-fast-gate-tests F3): its cells are Mockito mocks, and a shared mock
 * records every call from every worker under Mockito's own locking — measured 2026-09-28, ~95 % of
 * this class's run time (35–50 s → ~1 s), and that locking partly serialised the "concurrent"
 * workers, which can hide the very race this class exists to find.
 * </p>
 */
// Test awaits pool/executor termination explicitly; the per-task Future is intentionally ignored.
@SuppressWarnings("FutureReturnValueIgnored")
// ⚠ runParallel's try-with-resources close() awaits pool termination with no bound; a worker
// deadlocked on a monitor ignores shutdownNow()'s interrupt. This bound turns that hang into a red.
@Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class JoinCacheConcurrencyTest
{

    private static net.cumba.corej.core.model.CheckConditionExpression expr(String source)
    {
        return new net.cumba.corej.core.model.CheckConditionExpression(
                net.cumba.corej.core.expr.CheckExpressionParser.parse(source), source);
    }

    private static final int THREADS = 8;

    private static final int RULES_PER_THREAD = 16;

    @RepeatedTest(2)
    void parallelRuleExecution_keyMatchExpansion_matchesSequentialBaseline() throws Exception
    {
        // Primary: BDS-style table where TRTP is checked against ADSL.TRT01P (joined by USUBJID).
        // This entry is a keyed part of the merged view, cached per signature on the dataset's
        // JoinCache (see the class javadoc); this case gates the determinism of that path.
        IDataTable primary = makePrimary(1024);
        IDataTable adsl = makeAdsl();

        DatasetResolver resolver = name -> "ADSL".equals(name) ? adsl : null;

        Rule rule = buildJoinRule();

        // Sequential baseline.
        JoinCache.SharedIndexCache baselineShared = new JoinCache.SharedIndexCache();
        JoinCache baselineCache = new JoinCache(baselineShared);
        RuleExecutionResult baseline = RuleRunnerCalls.execute(rule, primary, resolver, "AD", null,
                baselineCache);
        // Guard against a vacuous comparison: the baseline must actually run and fire.
        assertEquals(1024 / 4, baseline.getViolationCount(), "the fixture fires once per SUBJ02"
                + " row: " + baseline.getStatus() + " " + baseline.getStatusMessage());

        // Concurrent: many threads, one shared JoinCache, one shared rule.
        JoinCache.SharedIndexCache shared = new JoinCache.SharedIndexCache();
        JoinCache cache = new JoinCache(shared);

        List<RuleExecutionResult> results = runParallel(THREADS, RULES_PER_THREAD,
                () -> RuleRunnerCalls.execute(rule, primary, resolver, "AD", null, cache));

        for (RuleExecutionResult r : results)
        {
            assertResultsEqual(baseline, r);
        }
    }


    @RepeatedTest(2)
    void parallelRuleExecution_keyMatchExpansion_concurrentColdStart() throws Exception
    {
        // N threads start simultaneously, all cold on the dataset's view cache — the moment of
        // maximum contention.
        IDataTable primary = makePrimary(2048);
        IDataTable adsl = makeAdsl();
        DatasetResolver resolver = name -> "ADSL".equals(name) ? adsl : null;
        Rule rule = buildJoinRule();

        JoinCache.SharedIndexCache shared = new JoinCache.SharedIndexCache();
        JoinCache cache = new JoinCache(shared);

        // First call from N threads concurrently — the moment of maximum contention.
        List<RuleExecutionResult> firstWave = runParallel(THREADS, 1,
                () -> RuleRunnerCalls.execute(rule, primary, resolver, "AD", null, cache));

        RuleExecutionResult reference = firstWave.get(0);
        for (RuleExecutionResult r : firstWave)
        {
            assertResultsEqual(reference, r);
        }
        // The counter, not identity observed after the race — but best-effort here (see the class
        // javadoc): the deterministic race check is in KeyMatchSharedIndexTest.
        // W3a of PLAN-merged-evaluation-table: the join is a part of the merged view, built once
        // per merge signature on the dataset's JoinCache (P-3) - the view cache is the structure
        // the race is on now (the legacy key-match index is not built on this arm).
        assertEquals(1, cache.mergedViewCount(),
                "the merged view must be built once, however many threads race for it");
    }


    @Test
    void keyMatchExpansion_twoRulesAcrossThreads_matchBaseline() throws Exception
    {
        // Two rules with the same Match_Datasets ({ADSL, USUBJID}) running in parallel must each
        // report the sequential baseline, and share one merged view (asserted at the end).
        IDataTable primary = makePrimary(64);
        IDataTable adsl = makeAdsl();
        DatasetResolver resolver = name -> "ADSL".equals(name) ? adsl : null;

        JoinCache.SharedIndexCache shared = new JoinCache.SharedIndexCache();
        JoinCache cache = new JoinCache(shared);

        Rule a = buildJoinRule();
        a.getCore().setId("CORE-A");
        Rule b = buildJoinRule();
        b.getCore().setId("CORE-B");

        RuleExecutionResult baseline = RuleRunnerCalls.execute(buildJoinRule(), primary, resolver,
                "AD", null, new JoinCache(new JoinCache.SharedIndexCache()));
        assertEquals(64 / 4, baseline.getViolationCount(), "the fixture fires once per SUBJ02 row");

        // Run both in parallel a few times so any cache-rebuild race surfaces.
        for (int i = 0; i < 16; i++)
        {
            List<List<RuleExecutionResult>> pairs = runParallel(2, 1,
                    () -> List.of(RuleRunnerCalls.execute(a, primary, resolver, "AD", null, cache),
                            RuleRunnerCalls.execute(b, primary, resolver, "AD", null, cache)));
            assertEquals(2, pairs.size(), "both workers must report");
            for (List<RuleExecutionResult> pair : pairs)
            {
                for (RuleExecutionResult r : pair)
                {
                    assertEquals(baseline.getStatus(), r.getStatus(), "status");
                    assertEquals(baseline.getViolationCount(), r.getViolationCount(),
                            "violation count");
                }
            }
        }
        assertEquals(1, cache.mergedViewCount(),
                "two rules with the same {ADSL, [USUBJID]} entry share one merged view");
    }


    @RepeatedTest(2)
    void parallelRuleExecution_cachedKeyJoin_concurrentColdStartMatchesBaseline() throws Exception
    {
        // The SUPP-named entry's keyed part (outside the key-type check, left), cold-started from N
        // threads on a fresh cache: the moment of maximum contention.
        IDataTable primary = makePrimary(2048);
        IDataTable adsl = makeAdsl();
        DatasetResolver resolver = name -> CACHED_JOIN_DATASET.equals(name) ? adsl : null;
        Rule rule = buildCachedJoinRule();

        JoinCache baselineCache = new JoinCache(new JoinCache.SharedIndexCache());
        RuleExecutionResult baseline = RuleRunnerCalls.execute(rule, primary, resolver, "AD", null,
                baselineCache);
        assertEquals(2048 / 4, baseline.getViolationCount(), "the fixture fires once per SUBJ02"
                + " row: " + baseline.getStatus() + " " + baseline.getStatusMessage());
        // Non-vacuity of the path itself: had the entry gone to the key-match expander instead,
        // this cache would be empty.
        // W3a: the SUPP-named keyed entry is a keyed part of the merged view (left: a first-wins
        // lookup never dropped a row), built once per signature on the JoinCache.
        assertEquals(1, baselineCache.mergedViewCount(),
                "the SUPP-named entry must be served by the merged view");

        JoinCache.SharedIndexCache shared = new JoinCache.SharedIndexCache();
        JoinCache cache = new JoinCache(shared);
        List<RuleExecutionResult> firstWave = runParallel(THREADS, 1,
                () -> RuleRunnerCalls.execute(rule, primary, resolver, "AD", null, cache));
        assertEquals(THREADS, firstWave.size(), "every worker must report");
        for (RuleExecutionResult r : firstWave)
        {
            assertResultsEqual(baseline, r);
        }
        assertEquals(1, cache.mergedViewCount(), "the cold-start wave must build the one view");
        MergedEvaluation.Built built = cache.mergedViews().iterator().next();

        List<RuleExecutionResult> secondWave = runParallel(THREADS, RULES_PER_THREAD,
                () -> RuleRunnerCalls.execute(rule, primary, resolver, "AD", null, cache));
        assertEquals(THREADS * RULES_PER_THREAD, secondWave.size(), "every iteration must report");
        for (RuleExecutionResult r : secondWave)
        {
            assertResultsEqual(baseline, r);
        }
        assertEquals(1, cache.mergedViewCount(),
                "the merged view must be built once across both waves");
        assertSame(built, cache.mergedViews().iterator().next(),
                "a warm JoinCache must keep serving the view the cold-start wave built");
    }


    @Test
    void cachedKeyJoin_twoRulesAcrossThreads_shareOneMergedView() throws Exception
    {
        // Two rules with the same Match_Datasets entry ({SUPPADSL, USUBJID}, outside the key-type
        // check) running in parallel must observe the same merged view instance after every wave
        // (the view is shared across rules and never rebuilt; see the class javadoc for what this
        // cannot see during the cold-start race itself).
        IDataTable primary = makePrimary(64);
        IDataTable adsl = makeAdsl();
        DatasetResolver resolver = name -> CACHED_JOIN_DATASET.equals(name) ? adsl : null;

        JoinCache.SharedIndexCache shared = new JoinCache.SharedIndexCache();
        JoinCache cache = new JoinCache(shared);

        Rule a = buildCachedJoinRule();
        a.getCore().setId("CORE-A");
        Rule b = buildCachedJoinRule();
        b.getCore().setId("CORE-B");

        RuleExecutionResult baseline = RuleRunnerCalls.execute(buildCachedJoinRule(), primary,
                resolver, "AD", null, new JoinCache(new JoinCache.SharedIndexCache()));
        assertEquals(64 / 4, baseline.getViolationCount(), "the fixture fires once per SUBJ02 row");

        MergedEvaluation.Built first = null;
        for (int i = 0; i < 16; i++)
        {
            List<List<RuleExecutionResult>> pairs = runParallel(2, 1,
                    () -> List.of(RuleRunnerCalls.execute(a, primary, resolver, "AD", null, cache),
                            RuleRunnerCalls.execute(b, primary, resolver, "AD", null, cache)));
            assertEquals(2, pairs.size(), "both workers must report");
            for (List<RuleExecutionResult> pair : pairs)
            {
                for (RuleExecutionResult r : pair)
                {
                    assertEquals(baseline.getStatus(), r.getStatus(), "status");
                    assertEquals(baseline.getViolationCount(), r.getViolationCount(),
                            "violation count");
                }
            }
            assertEquals(1, cache.mergedViewCount(), "wave " + i + " must leave the view cached");
            MergedEvaluation.Built current = cache.mergedViews().iterator().next();
            if (first == null)
            {
                first = current;
            }
            assertSame(first, current, "wave " + i + " must reuse the one cached merged view");
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * The joined-dataset name of {@link #buildCachedJoinRule}: refused by the expander BY NAME (a
     * {@code SUPP} prefix), which is what routes it to the cached key-join branch.
     */
    private static final String CACHED_JOIN_DATASET = "SUPPADSL";

    /** {@code JoinCache}'s lookup key for that dataset joined on {@code USUBJID}. */

    private static IDataTable makePrimary(int rows)
    {
        // 4 subjects, ~rows/4 records per subject. TRTP equals "PLACEBO" on every row → the
        // not_equal_to check vs ADSL.TRT01P fires for SUBJ02 (whose ADSL.TRT01P is "ACTIVE").
        String[] subjects =
        {
                "SUBJ01", "SUBJ02", "SUBJ03", "SUBJ04"
        };
        String[] usubjid = new String[rows];
        String[] trtp = new String[rows];
        Arrays.setAll(usubjid, i -> subjects[i % subjects.length]);
        Arrays.fill(trtp, "PLACEBO");
        return direct(RealTables.of("ADLB").str("USUBJID", usubjid).str("TRTP", trtp).build());
    }


    private static IDataTable makeAdsl()
    {
        return direct(RealTables.of("ADSL").str("USUBJID", "SUBJ01", "SUBJ02", "SUBJ03", "SUBJ04")
                .str("TRT01P", "PLACEBO", "ACTIVE", "PLACEBO", "PLACEBO").build());
    }


    /**
     * Guards the fixture's point: it must be a loaded dataset's table class (a real
     * {@code ColumnCachedDataTable}), so the join reads it the way it reads a study's.
     */
    private static IDataTable direct(IDataTable aTable)
    {
        assertTrue(aTable.getClass().getSimpleName().contains("Cached"),
                "the fixture must be a real cached table: " + aTable.getClass());
        return aTable;
    }


    private static Rule buildJoinRule()
    {
        // Check: TRTP must equal ADSL.TRT01P, joined by USUBJID — a keyed part of the merged view
        // under the key-type check; buildCachedJoinRule is the variant outside it.
        net.cumba.corej.core.model.CheckConditionExpression leaf = expr("TRTP != ADSL.TRT01P");

        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("CORE-JOIN");
        rule.setCore(core);
        // Record sensitivity, explicitly: unset, the run reports ONE finding per dataset, and the
        // "fires once per SUBJ02 row" assertions below would be measuring the fold, not the join.
        rule.setSensitivity(net.cumba.corej.core.model.Sensitivity.RECORD);
        Outcome outcome = new Outcome();
        outcome.setMessage("TRTP ≠ ADSL.TRT01P");
        outcome.setOutputVariables(List.of("USUBJID", "TRTP", "ADSL.TRT01P"));
        rule.setOutcome(outcome);
        rule.setCheck(new CheckConditionAll(List.of(leaf)));
        // The engine runs only the native expression form; without it every execution ERRORs
        // ("the Check has no native expression form") and each comparison below is vacuous.
        rule.setCheckExpr(
                net.cumba.corej.core.expr.CheckExpressionParser.parse("TRTP != ADSL.TRT01P"));

        MatchDataset md = new MatchDataset();
        md.setName("ADSL");
        md.setKeys(List.of("USUBJID"));
        // Explicit since U15 of PLAN-retire-dead-multi-match-lookup; `left` is what this fixture
        // always ran as.
        md.setJoinType("left");
        rule.setMatchDatasets(List.of(md));
        return rule;
    }


    /**
     * {@link #buildJoinRule} with its entry renamed to {@link #CACHED_JOIN_DATASET}. A
     * {@code SUPP}-prefixed name takes the entry out of the key-type check
     * ({@code JoinKeyTypes.textCarriedForeignKeyFamily}) while it is neither {@code Child: true}
     * nor a template: a keyed part the view keeps every row of ({@code left}). (Until W6 of
     * {@code PLAN-merged-evaluation-table} this routed the entry to the retired cached key-join
     * lookup.)
     */
    private static Rule buildCachedJoinRule()
    {
        Rule rule = buildJoinRule();
        rule.getMatchDatasets().get(0).setName(CACHED_JOIN_DATASET);
        String check = "TRTP != " + CACHED_JOIN_DATASET + ".TRT01P";
        rule.setCheck(new CheckConditionAll(List.of(expr(check))));
        rule.setCheckExpr(net.cumba.corej.core.expr.CheckExpressionParser.parse(check));
        rule.getOutcome()
                .setOutputVariables(List.of("USUBJID", "TRTP", CACHED_JOIN_DATASET + ".TRT01P"));
        return rule;
    }


    private static <T> List<T> runParallel(int threads, int iterationsPerThread,
            java.util.concurrent.Callable<T> work)
        throws Exception
    {
        // shutdownNow() first, so a stuck worker is interrupted before close() waits for it.
        try (ExecutorService pool = Executors.newFixedThreadPool(threads))
        {
            try
            {
                CountDownLatch ready = new CountDownLatch(threads);
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(threads);
                List<T> results = java.util.Collections.synchronizedList(new ArrayList<>());
                AtomicReference<Throwable> failure = new AtomicReference<>();
                for (int t = 0; t < threads; t++)
                {
                    pool.submit(() ->
                    {
                        ready.countDown();
                        try
                        {
                            start.await();
                            for (int i = 0; i < iterationsPerThread; i++)
                            {
                                T r = work.call();
                                if (r != null)
                                {
                                    results.add(r);
                                }
                            }
                        }
                        catch (Throwable e)
                        {
                            failure.compareAndSet(null, e);
                        }
                        finally
                        {
                            done.countDown();
                        }
                    });
                }
                assertTrue(ready.await(10, TimeUnit.SECONDS), "workers did not start");
                start.countDown();
                assertTrue(done.await(60, TimeUnit.SECONDS), "workers did not finish");
                if (failure.get() != null)
                {
                    fail("Worker threw: " + failure.get(), failure.get());
                }
                return results;
            }
            finally
            {
                pool.shutdownNow();
            }
        }
    }


    private static void assertResultsEqual(RuleExecutionResult expected, RuleExecutionResult actual)
    {
        assertEquals(expected.getRuleId(), actual.getRuleId(), "ruleId");
        assertEquals(expected.getStatus(), actual.getStatus(), "status");
        assertEquals(expected.getTotalRows(), actual.getTotalRows(), "totalRows");
        assertEquals(expected.getViolationCount(), actual.getViolationCount(), "violation count");
        // Violation rows should be identical and in the same order (the engine iterates rows
        // sequentially within a single rule execution; only the cache build is parallel).
        for (int i = 0; i < expected.getViolations().size(); i++)
        {
            assertEquals(expected.getViolations().get(i).getRow(),
                    actual.getViolations().get(i).getRow(), "violation row at index " + i);
        }
    }

}
