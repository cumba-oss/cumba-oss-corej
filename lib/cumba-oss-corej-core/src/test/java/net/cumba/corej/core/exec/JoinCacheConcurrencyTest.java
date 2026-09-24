package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Thread-safety regression gate for {@link RuleRunner#execute} driven from many worker threads
 * against one shared {@link JoinCache} and one shared primary table.
 *
 * <p>
 * ⚠⚠ <b>Two join paths, and only one of them touches the cache.</b> A plain keyed
 * {@code Match_Datasets} entry ({@link #buildJoinRule}: {@code {ADSL, [USUBJID]}}) is served by
 * {@link KeyMatchRowExpander}'s row expansion — {@code KeyMatchRowExpander.expandableEntries}
 * accepts it ({@code JoinKeyTypes.governedByKeyTypeCheck}) and {@code RuleRunner} removes it from
 * the key-join build — so it <b>never reaches</b> {@code JoinCache.getOrBuildLookup},
 * {@code SharedIndexCache} or {@code DatasetLookup.ensureJoinMap}. The {@code *_keyMatchExpansion*}
 * cases therefore gate only the <b>determinism of the key-match expansion path</b> under
 * concurrency (every parallel result equals the sequential baseline).
 * </p>
 *
 * <p>
 * The cache itself is gated by the {@code *_cachedKeyJoin*} cases, whose entry
 * ({@link #buildCachedJoinRule}) carries {@code Child: true} with non-IDVAR keys: the expander
 * excludes it, {@code ChildMatchPreMerger} leaves a non-IDVAR child untouched, and
 * {@code RuleRunner.buildJoinedDatasets} takes its cached key-join branch. Those cases assert the
 * rule fires, parallel equals baseline, and {@code JoinCache.get("ADSL|USUBJID")} holds one
 * {@link DatasetLookup} that is the SAME instance across waves — i.e. {@code computeIfAbsent} in
 * {@code getOrBuildLookup} really shares the entry, and the lazily built join map inside it
 * ({@code ensureJoinMap}) is read concurrently by every worker.
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
        // ⚠ This entry is served by KeyMatchRowExpander, not the JoinCache (see the class
        // javadoc): this case gates the determinism of the expansion path only.
        IDataTable primary = makePrimary(1024);
        IDataTable adsl = makeAdsl();

        DatasetResolver resolver = name -> "ADSL".equals(name) ? adsl : null;

        Rule rule = buildJoinRule();

        // Sequential baseline.
        JoinCache.SharedIndexCache baselineShared = new JoinCache.SharedIndexCache();
        JoinCache baselineCache = new JoinCache(baselineShared);
        RuleExecutionResult baseline = RuleRunner.execute(rule, primary, resolver, "AD", null,
                baselineCache);
        // Guard against a vacuous comparison: the baseline must actually run and fire.
        assertTrue(baseline.getViolationCount() > 0, "the fixture must fire for SUBJ02: "
                + baseline.getStatus() + " " + baseline.getStatusMessage());

        // Concurrent: many threads, one shared JoinCache, one shared rule.
        JoinCache.SharedIndexCache shared = new JoinCache.SharedIndexCache();
        JoinCache cache = new JoinCache(shared);

        List<RuleExecutionResult> results = runParallel(THREADS, RULES_PER_THREAD,
                () -> RuleRunner.execute(rule, primary, resolver, "AD", null, cache));

        for (RuleExecutionResult r : results)
        {
            assertResultsEqual(baseline, r);
        }
    }


    @RepeatedTest(2)
    void parallelRuleExecution_keyMatchExpansion_concurrentColdStart() throws Exception
    {
        // N threads start simultaneously on the key-match expansion path. ⚠ That path does not
        // touch the JoinCache (see the class javadoc); the cold-start race on
        // JoinCache.getOrBuildLookup / ensureJoinMap is gated by the *_cachedKeyJoin* cases.
        IDataTable primary = makePrimary(2048);
        IDataTable adsl = makeAdsl();
        DatasetResolver resolver = name -> "ADSL".equals(name) ? adsl : null;
        Rule rule = buildJoinRule();

        JoinCache.SharedIndexCache shared = new JoinCache.SharedIndexCache();
        JoinCache cache = new JoinCache(shared);

        // First call from N threads concurrently — the moment of maximum contention.
        List<RuleExecutionResult> firstWave = runParallel(THREADS, 1,
                () -> RuleRunner.execute(rule, primary, resolver, "AD", null, cache));

        RuleExecutionResult reference = firstWave.get(0);
        for (RuleExecutionResult r : firstWave)
        {
            assertResultsEqual(reference, r);
        }
    }


    @Test
    void keyMatchExpansion_twoRulesAcrossThreads_matchBaseline() throws Exception
    {
        // Two rules with the same Match_Datasets ({ADSL, USUBJID}) running in parallel must each
        // report the sequential baseline. The shared-instance claim is asserted by
        // cachedKeyJoin_twoRulesAcrossThreads_shareOneDatasetLookup, whose entry reaches the
        // JoinCache; this one does not (see the comment at the end).
        IDataTable primary = makePrimary(64);
        IDataTable adsl = makeAdsl();
        DatasetResolver resolver = name -> "ADSL".equals(name) ? adsl : null;

        JoinCache.SharedIndexCache shared = new JoinCache.SharedIndexCache();
        JoinCache cache = new JoinCache(shared);

        Rule a = buildJoinRule();
        a.getCore().setId("CORE-A");
        Rule b = buildJoinRule();
        b.getCore().setId("CORE-B");

        RuleExecutionResult baseline = RuleRunner.execute(buildJoinRule(), primary, resolver, "AD",
                null, new JoinCache(new JoinCache.SharedIndexCache()));
        assertTrue(baseline.getViolationCount() > 0, "the fixture must fire for SUBJ02");

        // Run both in parallel a few times so any cache-rebuild race surfaces.
        for (int i = 0; i < 16; i++)
        {
            List<List<RuleExecutionResult>> pairs = runParallel(2, 1,
                    () -> List.of(RuleRunner.execute(a, primary, resolver, "AD", null, cache),
                            RuleRunner.execute(b, primary, resolver, "AD", null, cache)));
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
        // ⚠ No assertion on JoinCache.lookupCache: a key-based Match_Datasets entry is served by
        // KeyMatchRowExpander's row expansion, which removes it from the lookup build, so this
        // rule shape never populates that cache (measured 2026-09-24: empty after 16 waves).
    }


    @RepeatedTest(2)
    void parallelRuleExecution_cachedKeyJoin_concurrentColdStartMatchesBaseline() throws Exception
    {
        // The cached key-join branch (RuleRunner.buildJoinedDatasets → JoinCache.getOrBuildLookup
        // → SharedIndexCache.getOrBuild, then DatasetLookup.ensureJoinMap per row lookup), cold-
        // started from N threads on a fresh cache: the moment of maximum contention.
        IDataTable primary = makePrimary(2048);
        IDataTable adsl = makeAdsl();
        DatasetResolver resolver = name -> "ADSL".equals(name) ? adsl : null;
        Rule rule = buildCachedJoinRule();

        JoinCache baselineCache = new JoinCache(new JoinCache.SharedIndexCache());
        RuleExecutionResult baseline = RuleRunner.execute(rule, primary, resolver, "AD", null,
                baselineCache);
        assertTrue(baseline.getViolationCount() > 0, "the fixture must fire for SUBJ02: "
                + baseline.getStatus() + " " + baseline.getStatusMessage());
        // Non-vacuity of the path itself: had the entry gone to the key-match expander instead,
        // this cache would be empty.
        assertNotNull(baselineCache.get(ADSL_LOOKUP_KEY),
                "the Child: true entry must be served by the JoinCache's key-join branch");

        JoinCache cache = new JoinCache(new JoinCache.SharedIndexCache());
        List<RuleExecutionResult> firstWave = runParallel(THREADS, 1,
                () -> RuleRunner.execute(rule, primary, resolver, "AD", null, cache));
        assertEquals(THREADS, firstWave.size(), "every worker must report");
        for (RuleExecutionResult r : firstWave)
        {
            assertResultsEqual(baseline, r);
        }
        JoinLookup built = cache.get(ADSL_LOOKUP_KEY);
        assertNotNull(built, "the cold-start wave must populate the JoinCache");

        List<RuleExecutionResult> secondWave = runParallel(THREADS, RULES_PER_THREAD,
                () -> RuleRunner.execute(rule, primary, resolver, "AD", null, cache));
        assertEquals(THREADS * RULES_PER_THREAD, secondWave.size(), "every iteration must report");
        for (RuleExecutionResult r : secondWave)
        {
            assertResultsEqual(baseline, r);
        }
        assertSame(built, cache.get(ADSL_LOOKUP_KEY),
                "a warm JoinCache must keep serving the lookup the cold-start wave built");
    }


    @Test
    void cachedKeyJoin_twoRulesAcrossThreads_shareOneDatasetLookup() throws Exception
    {
        // Two rules with the same cached Match_Datasets entry ({ADSL, USUBJID}, Child: true)
        // running in parallel must observe the same DatasetLookup instance — confirming
        // computeIfAbsent in JoinCache.getOrBuildLookup really shares the entry across rules and
        // threads.
        IDataTable primary = makePrimary(64);
        IDataTable adsl = makeAdsl();
        DatasetResolver resolver = name -> "ADSL".equals(name) ? adsl : null;

        JoinCache cache = new JoinCache(new JoinCache.SharedIndexCache());

        Rule a = buildCachedJoinRule();
        a.getCore().setId("CORE-A");
        Rule b = buildCachedJoinRule();
        b.getCore().setId("CORE-B");

        RuleExecutionResult baseline = RuleRunner.execute(buildCachedJoinRule(), primary, resolver,
                "AD", null, new JoinCache(new JoinCache.SharedIndexCache()));
        assertTrue(baseline.getViolationCount() > 0, "the fixture must fire for SUBJ02");

        JoinLookup first = null;
        for (int i = 0; i < 16; i++)
        {
            List<List<RuleExecutionResult>> pairs = runParallel(2, 1,
                    () -> List.of(RuleRunner.execute(a, primary, resolver, "AD", null, cache),
                            RuleRunner.execute(b, primary, resolver, "AD", null, cache)));
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
            JoinLookup current = cache.get(ADSL_LOOKUP_KEY);
            assertNotNull(current, "wave " + i + " must leave the lookup cached");
            if (first == null)
            {
                first = current;
            }
            assertSame(first, current, "wave " + i + " must reuse the one cached DatasetLookup");
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** {@code JoinCache}'s lookup key for {@code ADSL} joined on {@code USUBJID}. */
    private static final String ADSL_LOOKUP_KEY = "ADSL|USUBJID";

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
        return MockTable.of().col("USUBJID", usubjid).col("TRTP", trtp).name("ADLB").build();
    }


    private static IDataTable makeAdsl()
    {
        return MockTable.of().col("USUBJID", "SUBJ01", "SUBJ02", "SUBJ03", "SUBJ04")
                .col("TRT01P", "PLACEBO", "ACTIVE", "PLACEBO", "PLACEBO").name("ADSL").build();
    }


    private static Rule buildJoinRule()
    {
        // Check: TRTP must equal ADSL.TRT01P, joined by USUBJID. ⚠ As built here the entry is
        // served by KeyMatchRowExpander and never registers with the JoinCache;
        // buildCachedJoinRule is the variant that does.
        net.cumba.corej.core.model.CheckConditionExpression leaf = expr("TRTP != ADSL.TRT01P");

        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("CORE-JOIN");
        rule.setCore(core);
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
        rule.setMatchDatasets(List.of(md));
        return rule;
    }


    /**
     * {@link #buildJoinRule} with {@code Child: true} on its entry. The flag takes it out of
     * {@code KeyMatchRowExpander.expandableEntries}; with non-IDVAR keys
     * {@code ChildMatchPreMerger} returns the primary unchanged; so the entry reaches the cached
     * key-join branch of {@code RuleRunner.buildJoinedDatasets}.
     */
    private static Rule buildCachedJoinRule()
    {
        Rule rule = buildJoinRule();
        rule.getMatchDatasets().get(0).setChild(true);
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
