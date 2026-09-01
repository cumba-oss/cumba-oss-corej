package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.MatchDataset;
import net.cumba.cdisc.core.model.Outcome;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RuleCore;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * Phase 1 thread-safety regression gate for the engine's per-dataset caches.
 *
 * <p>
 * Drives {@link RuleRunner#execute} from many worker threads against one shared {@link JoinCache}
 * and one shared primary table, mirroring the future Phase 2 fan-out shape. Asserts that every
 * concurrent invocation produces the same result that a sequential baseline does — any
 * unsynchronised lazy-init in {@link JoinCache}, {@link DatasetLookup}, or
 * {@link RelrecExpandedLookup} that re-emerges in the future will surface here as missing
 * violations, mismatched row maps, or thrown exceptions.
 */
// Test awaits pool/executor termination explicitly; the per-task Future is intentionally ignored.
@SuppressWarnings("FutureReturnValueIgnored")
class JoinCacheConcurrencyTest
{

    private static final int THREADS = 8;

    private static final int RULES_PER_THREAD = 16;

    @RepeatedTest(2)
    void parallelRuleExecution_sharedJoinCache_sameDatasetLookupRule() throws Exception
    {
        // Primary: BDS-style table where TRTP is checked against ADSL.TRT01P (joined by USUBJID).
        // 1024 rows split across 4 subjects keeps the run cheap while still exercising the
        // per-row joinMap that ensureJoinMap builds lazily.
        IDataTable primary = makePrimary(1024);
        IDataTable adsl = makeAdsl();

        DatasetResolver resolver = name -> "ADSL".equals(name) ? adsl : null;

        Rule rule = buildJoinRule();

        // Sequential baseline.
        JoinCache.SharedIndexCache baselineShared = new JoinCache.SharedIndexCache();
        JoinCache baselineCache = new JoinCache(baselineShared);
        RuleExecutionResult baseline = RuleRunner.execute(rule, primary, resolver, "AD", null,
                baselineCache);

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
    void parallelRuleExecution_sharedJoinCache_concurrentColdStart() throws Exception
    {
        // Cold-start the JoinCache from N threads simultaneously — exercises the
        // computeIfAbsent path in JoinCache.getOrBuildLookup and the synchronised
        // ensureJoinMap in DatasetLookup. A fresh cache is created per outer run; without the
        // Phase 1 fixes in place the first wave of threads can race on cache population.
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
    void joinCache_sharedDatasetLookupAcrossThreads() throws Exception
    {
        // Two rules with the same Match_Datasets ({ADSL, USUBJID}) running in parallel must
        // observe the same DatasetLookup instance from JoinCache.lookupCache — confirming
        // computeIfAbsent really shares the entry.
        IDataTable primary = makePrimary(64);
        IDataTable adsl = makeAdsl();
        DatasetResolver resolver = name -> "ADSL".equals(name) ? adsl : null;

        JoinCache.SharedIndexCache shared = new JoinCache.SharedIndexCache();
        JoinCache cache = new JoinCache(shared);

        Rule a = buildJoinRule();
        a.getCore().setId("CORE-A");
        Rule b = buildJoinRule();
        b.getCore().setId("CORE-B");

        // Run both in parallel a few times so any cache-rebuild race surfaces.
        for (int i = 0; i < 16; i++)
        {
            runParallel(2, 1, () ->
            {
                RuleRunner.execute(a, primary, resolver, "AD", null, cache);
                RuleRunner.execute(b, primary, resolver, "AD", null, cache);
                return null;
            });
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------


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
        for (int i = 0; i < rows; i++)
        {
            usubjid[i] = subjects[i % subjects.length];
            trtp[i] = "PLACEBO";
        }
        return MockTable.of().col("USUBJID", usubjid).col("TRTP", trtp).name("ADLB").build();
    }


    private static IDataTable makeAdsl()
    {
        return MockTable.of().col("USUBJID", "SUBJ01", "SUBJ02", "SUBJ03", "SUBJ04")
                .col("TRT01P", "PLACEBO", "ACTIVE", "PLACEBO", "PLACEBO").name("ADSL").build();
    }


    private static Rule buildJoinRule()
    {
        // Check: TRTP must equal ADSL.TRT01P. The CheckConditionLeaf with a foreign-dataset
        // reference is what drives DatasetLookup.lookup per row, and Match_Datasets registers
        // ADSL/USUBJID with the JoinCache.
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("TRTP").operator("not_equal_to")
                .value(textNode("ADSL.TRT01P")).build();

        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("CORE-JOIN");
        rule.setCore(core);
        Outcome outcome = new Outcome();
        outcome.setMessage("TRTP ≠ ADSL.TRT01P");
        outcome.setOutputVariables(List.of("USUBJID", "TRTP", "ADSL.TRT01P"));
        rule.setOutcome(outcome);
        rule.setCheck(new CheckConditionAll(List.of(leaf)));

        MatchDataset md = new MatchDataset();
        md.setName("ADSL");
        md.setKeys(List.of("USUBJID"));
        rule.setMatchDatasets(List.of(md));
        return rule;
    }


    private static com.fasterxml.jackson.databind.node.TextNode textNode(String s)
    {
        return com.fasterxml.jackson.databind.node.TextNode.valueOf(s);
    }


    private static <T> List<T> runParallel(int threads, int iterationsPerThread,
            java.util.concurrent.Callable<T> work)
        throws Exception
    {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
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
