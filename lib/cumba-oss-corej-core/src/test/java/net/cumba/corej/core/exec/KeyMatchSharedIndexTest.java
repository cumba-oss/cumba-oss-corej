package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The key-match child index shared through {@link JoinCache.SharedIndexCache}
 * ({@code PLAN-keymatch-shared-join-index}).
 *
 * <p>
 * ⚠⚠ <b>Why every shape runs against ONE cache, in several orders.</b> A wrong cache hit needs two
 * joins of the same child table that key it differently while agreeing on every field the cache key
 * holds. A test with a fresh cache per shape can never produce one, so it cannot see a field
 * missing from {@link KeyMatchIndex.SpecKey} at all (pre-go review M2). The shapes below are paired
 * so that each D2 field is the ONLY difference between two of them:
 * </p>
 * <ul>
 * <li>{@code asString} — {@code S3} / {@code S4} ({@code Join_As_String});</li>
 * <li>{@code keepMissings} — {@code S1} / {@code S2};</li>
 * <li>the order of {@code childColIds} — {@code S3} / {@code S5};</li>
 * <li>{@code childAbsentParts} — {@code S6} / {@code S7}: the child lacks {@code VISITNUM}, and the
 * two primaries type it character and numeric, so it contributes {@code EMPTY} and
 * {@code MIS};</li>
 * <li>{@code active} — {@code S8a} / {@code S8b}: the child carries only the middle component, and
 * each primary carries a different one of the other two.</li>
 * </ul>
 * <p>
 * The negative controls of the plan's Phase 2 are run against this class: removing any one of those
 * fields from {@code KeySpec.cacheKey()} reds {@link #everyShapeMatchesItsUncachedRunInEveryOrder}.
 * </p>
 */
class KeyMatchSharedIndexTest
{

    private static final String AE = "AE";

    private static final String USUBJID = "USUBJID";

    private static final String AESEQ = "AESEQ";

    private static final String VISITNUM = "VISITNUM";

    private static final String ROWID = "ROWID";

    /** The one child every shape joins: sharing it is what makes a wrong hit possible. */
    private static IDataTable child()
    {
        return MockTable.of().col(USUBJID, "P1", "P1", "P2", "")
                .colDouble(AESEQ, 1.0, 2.0, 1.0, 1.0).col("AEOUT", "FATAL", "OK", "FATAL", "OK")
                .col(ROWID, "A0", "A1", "A2", "A3").name(AE).build();
    }


    /** Numeric-typed primary: {@code VISITNUM} is a numeric column of missings. */
    private static IDataTable primaryNum()
    {
        return MockTable.of().col(USUBJID, "P1", "P2", "P3", "")
                .colDouble(AESEQ, 1.0, 1.0, 2.0, 1.0).colDouble("VISN", 1.0, 1.0, 2.0, 1.0)
                .colDouble(VISITNUM, null, null, null, null).name("ADAE").build();
    }


    /** Character-typed primary: {@code VISITNUM} is a character column of blanks. */
    private static IDataTable primaryChar()
    {
        return MockTable.of().col(USUBJID, "P1", "P2").col(VISITNUM, "", "").name("ADCM").build();
    }


    /** A primary carrying {@code XVAR} and no {@code VISITNUM}. */
    private static IDataTable primaryXvar()
    {
        return MockTable.of().col(USUBJID, "P1", "P2").col("XVAR", "", "").name("ADEX").build();
    }


    private static MatchDataset bind(String aJson)
    {
        try
        {
            return new ObjectMapper().readValue(aJson, MatchDataset.class);
        }
        catch (Exception ex)
        {
            throw new IllegalStateException(ex);
        }
    }


    private static MatchDataset md(String aKeysJson, String aExtra)
    {
        return bind("{\"Name\":\"AE\",\"Keys\":" + aKeysJson + ",\"Join_Type\":\"left\"" + aExtra
                + "}");
    }

    /** One join to run: the primary it runs on, and its {@code Match_Datasets} entries. */
    private record Shape(String name, IDataTable primary, List<MatchDataset> entries)
    {
    }

    private static List<Shape> shapes(IDataTable aNum, IDataTable aChar, IDataTable aXvar)
    {
        return List.of(new Shape("S1 single key", aNum, List.of(md("[\"USUBJID\"]", ""))),
                new Shape("S2 keep_missings false", aNum,
                        List.of(md("[\"USUBJID\"]", ",\"keep_missings\":false"))),
                new Shape("S3 two keys", aNum, List.of(md("[\"USUBJID\",\"AESEQ\"]", ""))),
                new Shape("S4 Join_As_String", aNum,
                        List.of(md("[\"USUBJID\",\"AESEQ\"]", ",\"Join_As_String\":true"))),
                new Shape("S5 two keys reversed", aNum, List.of(md("[\"AESEQ\",\"USUBJID\"]", ""))),
                new Shape("S6 child-absent key, char primary", aChar,
                        List.of(md("[\"USUBJID\",\"VISITNUM\"]", ""))),
                new Shape("S7 child-absent key, numeric primary", aNum,
                        List.of(md("[\"USUBJID\",\"VISITNUM\"]", ""))),
                new Shape("S8a active first two", aChar,
                        List.of(md("[\"VISITNUM\",\"USUBJID\",\"XVAR\"]", ""))),
                new Shape("S8b active last two", aXvar,
                        List.of(md("[\"VISITNUM\",\"USUBJID\",\"XVAR\"]", ""))),
                new Shape("S9 sided keys", aNum,
                        List.of(md("[\"USUBJID\",{\"left\":\"VISN\",\"right\":\"AESEQ\"}]", ""))),
                new Shape("S10 filtered", aNum,
                        List.of(md("[\"USUBJID\"]", ",\"Filter\":\"AEOUT == \\\"FATAL\\\"\""))),
                new Shape("S11 inner", aNum, List.of(bind(
                        "{\"Name\":\"AE\",\"Keys\":[\"USUBJID\"]," + "\"Join_Type\":\"inner\"}"))));
    }


    /**
     * The expansion as text: per expanded row, the primary row and the bound child's {@code ROWID}
     * (or {@code null} when unbound). {@code ROWID} identifies a child row by VALUE, so the
     * rendering is the same whether a lookup reads the base table or a filtered view of it.
     */
    private static List<String> render(KeyMatchRowExpander.@Nullable KeyMatchExpansion aExp)
    {
        assertNotNull(aExp, "every shape here has an expandable entry");
        IDataTable t = aExp.table();
        List<String> out = new ArrayList<>();
        for (long i = 0; i < t.getRowCount(); i++)
        {
            StringBuilder sb = new StringBuilder().append(t.getRealRowIndex(i));
            for (Map.Entry<String, JoinLookup> e : aExp.lookups().entrySet())
            {
                sb.append(' ').append(e.getKey()).append('=')
                        .append(e.getValue().lookup(t, i, ROWID));
            }
            out.add(sb.toString());
        }
        return out;
    }


    private static List<String> run(Shape aShape, IDataTable aChild,
            JoinCache.@Nullable SharedIndexCache aCache)
    {
        return render(KeyMatchRowExpander.expand(aShape.primary(), aShape.entries(),
                Map.of(AE, aChild)::get, "R-" + aShape.name(), aCache));
    }


    @Test
    void everyShapeMatchesItsUncachedRunInEveryOrder()
    {
        IDataTable ae = child();
        List<Shape> shapes = shapes(primaryNum(), primaryChar(), primaryXvar());
        Map<String, List<String>> reference = new HashMap<>();
        for (Shape s : shapes)
        {
            reference.put(s.name(), run(s, ae, null));
        }

        List<Shape> reversed = new ArrayList<>(shapes);
        Collections.reverse(reversed);
        // ⚠ keep_missings is result-neutral in ONE direction only: a KEEP index served to a DROP
        // rule is harmless (the blank primary row never probes), a DROP index served to a KEEP rule
        // loses every blank-keyed child row. Neither order above builds S2's DROP index before a
        // KEEP rule on the same key, so the third order does (review round 1, lane A, LOW-1).
        List<Shape> dropFirst = new ArrayList<>(shapes);
        dropFirst.add(0, dropFirst.remove(1));
        Map<String, List<Shape>> orders = new LinkedHashMap<>();
        orders.put("forward", shapes);
        orders.put("reversed", reversed);
        orders.put("keep_missings:false first", dropFirst);
        // Every order's RESULTS are checked before any build count, so a wrong hit is reported as
        // the wrong answer it produces rather than masked by an earlier order's count.
        Map<String, Integer> builds = new LinkedHashMap<>();
        for (Map.Entry<String, List<Shape>> order : orders.entrySet())
        {
            JoinCache.SharedIndexCache cache = new JoinCache.SharedIndexCache();
            for (Shape s : order.getValue())
            {
                assertEquals(reference.get(s.name()), run(s, ae, cache),
                        s.name() + " (" + order.getKey() + " order)"
                                + ": a shared index must answer exactly what a private one does");
            }
            builds.put(order.getKey(), cache.keyMatchIndexBuildCount());
        }
        for (Map.Entry<String, Integer> b : builds.entrySet())
        {
            assertEquals(9, b.getValue(), b.getKey() + " order: 12 shapes, 9 distinct key shapes:"
                    + " S9 (sided, same resolved columns) shares S3's index, and S10 (filtered) and"
                    + " S11 (inner) share S1's");
        }
    }


    /**
     * ⭐ Non-vacuity: the pairs must genuinely key the child differently. If two shapes of a pair
     * rendered the same, a wrong hit between them would be invisible and the test above would prove
     * nothing about that field.
     */
    @Test
    void thePairedShapesReallyDiffer()
    {
        IDataTable ae = child();
        Map<String, List<String>> r = new HashMap<>();
        for (Shape s : shapes(primaryNum(), primaryChar(), primaryXvar()))
        {
            r.put(s.name(), run(s, ae, null));
        }
        assertEquals(List.of("0 AE=A0", "0 AE=A1", "1 AE=A2", "2 AE=null", "3 AE=A3"),
                r.get("S1 single key"), "KEEP: the blank key of row 3 joins the blank child row");
        assertEquals(List.of("0 AE=A0", "0 AE=A1", "1 AE=A2", "2 AE=null", "3 AE=null"),
                r.get("S2 keep_missings false"));
        assertEquals(List.of("0 AE=A0", "1 AE=A2", "2 AE=null", "3 AE=A3"), r.get("S3 two keys"));
        assertEquals(r.get("S3 two keys"), r.get("S4 Join_As_String"),
                "the same rows — but through a DIFFERENT index (Present(\"1\") keys, not 1.0)");
        assertEquals(r.get("S3 two keys"), r.get("S5 two keys reversed"));
        assertEquals(List.of("0 AE=A0", "0 AE=A1", "1 AE=A2"),
                r.get("S6 child-absent key, char primary"), "the char primary's \"\" meets EMPTY");
        assertEquals(List.of("0 AE=A0", "0 AE=A1", "1 AE=A2", "2 AE=null", "3 AE=A3"),
                r.get("S7 child-absent key, numeric primary"),
                "the numeric primary's MIS meets MIS");
        assertEquals(List.of("0 AE=A0", "0 AE=A1", "1 AE=A2"), r.get("S8a active first two"));
        assertEquals(List.of("0 AE=A0", "0 AE=A1", "1 AE=A2"), r.get("S8b active last two"));
        assertEquals(r.get("S3 two keys"), r.get("S9 sided keys"));
        assertEquals(List.of("0 AE=A0", "1 AE=A2", "2 AE=null", "3 AE=null"),
                r.get("S10 filtered"));
        assertEquals(List.of("0 AE=A0", "0 AE=A1", "1 AE=A2", "3 AE=A3"), r.get("S11 inner"));
    }


    /**
     * D3's byte-identity oracle, independent of the new code path: the filter applied as a MASK
     * over a shared unfiltered index must bind exactly the rows the old path bound — the unfiltered
     * entry joined against the filtered VIEW that {@link MatchFilter#apply} builds. Three filters:
     * one keeping some rows, one keeping all, one keeping none.
     */
    @Test
    void aFilterMaskBindsExactlyWhatTheFilteredViewBound()
    {
        IDataTable ae = child();
        IDataTable primary = primaryNum();
        JoinCache.SharedIndexCache cache = new JoinCache.SharedIndexCache();
        for (String filter : List.of("AEOUT == \\\"FATAL\\\"", "AEOUT != \\\"X\\\"",
                "AEOUT == \\\"NEVER\\\""))
        {
            for (String joinType : List.of("left", "inner"))
            {
                MatchDataset filtered = bind(
                        "{\"Name\":\"AE\",\"Keys\":[\"USUBJID\"],\"Join_Type\":\"" + joinType
                                + "\",\"Filter\":\"" + filter + "\"}");
                MatchDataset plain = bind("{\"Name\":\"AE\",\"Keys\":[\"USUBJID\"],\"Join_Type\":\""
                        + joinType + "\"}");
                IDataTable view = MatchFilter.apply(filtered, ae, "R");
                List<String> oldPath = render(KeyMatchRowExpander.expand(primary, List.of(plain),
                        Map.of(AE, Objects2.nonNull(view))::get, "R", null));
                List<String> newPath = render(KeyMatchRowExpander.expand(primary, List.of(filtered),
                        Map.of(AE, ae)::get, "R", cache));
                assertEquals(oldPath, newPath, filter + " / " + joinType);
            }
        }
        assertEquals(1, cache.keyMatchIndexBuildCount(),
                "six filtered joins of one table on one key share ONE unfiltered index");
    }


    @Test
    void twoRulesOnTheSameChildAndShapeShareOneBuild()
    {
        IDataTable ae = child();
        JoinCache.SharedIndexCache cache = new JoinCache.SharedIndexCache();
        Shape s1 = shapes(primaryNum(), primaryChar(), primaryXvar()).get(0);
        run(s1, ae, cache);
        run(s1, ae, cache);
        assertEquals(1, cache.keyMatchIndexBuildCount());

        IDataTable second = child();
        try
        {
            run(s1, second, cache);
            assertEquals(2, cache.keyMatchIndexBuildCount(),
                    "an equal but DIFFERENT table instance is a different child — identity, not"
                            + " equality");
            assertEquals(2, cache.keyMatchIndexedTableCount());
        }
        finally
        {
            // Both tables must still be reachable when the count is read, or a collection in
            // between would (correctly) purge one and red this assertion.
            Reference.reachabilityFence(ae);
            Reference.reachabilityFence(second);
        }
    }


    /**
     * Q3 (d): an index lives exactly as long as its table. Once nothing but the cache refers to a
     * child, the collector may take it, and its indexes go with it.
     */
    @Test
    void anIndexDoesNotOutliveItsTable() throws InterruptedException
    {
        JoinCache.SharedIndexCache cache = new JoinCache.SharedIndexCache();
        Shape s1 = shapes(primaryNum(), primaryChar(), primaryXvar()).get(0);
        WeakReference<IDataTable> probe = indexOnce(cache, s1);

        long deadline = System.nanoTime() + 20_000_000_000L;
        while ((probe.get() != null || cache.keyMatchIndexedTableCount() != 0)
                && System.nanoTime() < deadline)
        {
            System.gc();
            Thread.sleep(10);
        }
        assertEquals(null, probe.get(), "the cache must not keep the child table reachable");
        assertEquals(0, cache.keyMatchIndexedTableCount(),
                "a collected table's indexes must be purged, not held for the rest of the run");
    }


    /**
     * Indexes a child that nothing else keeps, checks the cache holds it while it is still
     * reachable, and returns a weak handle on it.
     */
    private static WeakReference<IDataTable> indexOnce(JoinCache.SharedIndexCache aCache,
            Shape aShape)
    {
        IDataTable ae = child();
        try
        {
            run(aShape, ae, aCache);
            assertEquals(1, aCache.keyMatchIndexedTableCount(),
                    "the index must exist to begin with");
            return new WeakReference<>(ae);
        }
        finally
        {
            // Read while the table is provably reachable: a collection before the count would
            // purge the entry legitimately and red the precondition (review r1, lane B, LOW-5).
            Reference.reachabilityFence(ae);
        }
    }


    /**
     * The cold-start race, made deterministic (review round 1, lane B, LOW-4). Driving it through
     * {@code RuleRunner.execute} races a microsecond build against milliseconds of setup, so a
     * get-then-put regression would still count one build on most runs. Here the build itself holds
     * the door: the first builder parks until every other thread has had time to reach the cache,
     * so an implementation that lets a second thread build is caught every time.
     */
    @Test
    void aColdStartRaceBuildsOnceAndHandsEveryThreadTheSameIndex() throws Exception
    {
        int threads = 8;
        IDataTable ae = child();
        KeyMatchIndex.SpecKey spec = new KeyMatchIndex.SpecKey(List.of(0), List.of(true), List.of(),
                true, false);
        JoinCache.SharedIndexCache cache = new JoinCache.SharedIndexCache();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch building = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger suppliers = new AtomicInteger();
        Supplier<KeyMatchIndex> build = () ->
        {
            suppliers.incrementAndGet();
            building.countDown();
            try
            {
                assertTrue(release.await(30, TimeUnit.SECONDS),
                        "the test never released the build");
            }
            catch (InterruptedException ex)
            {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
            return KeyMatchIndex.build(0, r -> null);
        };
        // close() awaits termination; the build's own 30 s await bounds it, and the inner finally
        // opens the door first so a failed assertion can never leave a thread parked.
        try (ExecutorService pool = Executors.newFixedThreadPool(threads))
        {
            try
            {
                List<Future<KeyMatchIndex>> results = new ArrayList<>();
                for (int t = 0; t < threads; t++)
                {
                    results.add(pool.submit(() ->
                    {
                        start.await();
                        return cache.getOrBuildKeyMatchIndex(ae, spec, build);
                    }));
                }
                start.countDown();
                assertTrue(building.await(30, TimeUnit.SECONDS),
                        "no thread ever started the build");
                // Every other thread is now either blocked on the build or about to be; give the
                // stragglers time to arrive before the door opens.
                Thread.sleep(300);
                release.countDown();
                Set<KeyMatchIndex> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
                for (Future<KeyMatchIndex> f : results)
                {
                    distinct.add(f.get(30, TimeUnit.SECONDS));
                }
                assertEquals(1, suppliers.get(), "only one thread may build");
                assertEquals(1, cache.keyMatchIndexBuildCount());
                assertEquals(1, distinct.size(), "every thread must receive the one shared index");
            }
            finally
            {
                start.countDown();
                release.countDown();
                pool.shutdownNow();
            }
        }
    }


    /**
     * P4: one primary row with more matches than the initial binding capacity, folded through two
     * entries — the second an inner join that drops the one row with no partner. Checks growth, the
     * copying of an earlier entry's bindings, and that expanded rows stay in nested-loop order.
     */
    @Test
    void manyMatchesAcrossTwoEntriesKeepNestedLoopOrder()
    {
        int n = 300;
        String[] ids = new String[n];
        String[] rowIds = new String[n];
        for (int i = 0; i < n; i++)
        {
            ids[i] = "P1";
            rowIds[i] = "A" + i;
        }
        IDataTable ae = MockTable.of().col(USUBJID, ids).col(ROWID, rowIds).name(AE).build();
        IDataTable dm = MockTable.of().col(USUBJID, "P1").col(ROWID, "D0").name("DM").build();
        IDataTable primary = MockTable.of().col(USUBJID, "P1", "P9").name("ADAE").build();
        MatchDataset aeJoin = md("[\"USUBJID\"]", "");
        MatchDataset dmJoin = bind(
                "{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"],\"Join_Type\":\"inner\"}");
        JoinCache.SharedIndexCache cache = new JoinCache.SharedIndexCache();

        List<String> rows = render(KeyMatchRowExpander.expand(primary, List.of(aeJoin, dmJoin),
                Map.of(AE, ae, "DM", dm)::get, "R", cache));

        assertEquals(n, rows.size(),
                "P9 matched no AE row (left keeps it) and no DM row (inner drops it)");
        for (int i = 0; i < n; i++)
        {
            assertEquals("0 AE=A" + i + " DM=D0", rows.get(i));
        }
        assertEquals(2, cache.keyMatchIndexBuildCount());
    }


    /** P5: a table past {@code int} range fails loudly instead of wrapping silently. */
    @Test
    void aPrimaryPastIntRangeFailsLoudly()
    {
        IDataTable huge = mock(IDataTable.class);
        when(huge.getRowCount()).thenReturn(1L << 31);
        List<MatchDataset> entries = List.of(md("[\"USUBJID\"]", ""));
        DatasetResolver resolver = Map.of(AE, child())::get;
        ArithmeticException ex = assertThrows(ArithmeticException.class,
                () -> KeyMatchRowExpander.expand(huge, entries, resolver, "R", null));
        assertTrue(ex.getMessage().contains("overflow"), ex.getMessage());
    }


    /**
     * P5, the child side. A real (MockTable) child that merely reports a huge row count: the guard
     * sits after {@code keySpec}, which reads the child's metadata first.
     */
    @Test
    void aChildPastIntRangeFailsLoudly()
    {
        IDataTable huge = child(); // MockTable builds a Mockito mock, so it can be re-stubbed
        doReturn(1L << 31).when(huge).getRowCount();
        List<MatchDataset> entries = List.of(md("[\"USUBJID\"]", ""));
        DatasetResolver resolver = Map.of(AE, huge)::get;
        IDataTable primary = primaryNum();
        assertThrows(ArithmeticException.class,
                () -> KeyMatchRowExpander.expand(primary, entries, resolver, "R", null));
    }


    /**
     * The guard's POSITION: on an oversized child whose key types also disagree, the rule must
     * still report the key-type error it reported before the guard existed — the row count was
     * first read after {@code keySpec} then, and still is (review round 2, LOW-3). Moving the guard
     * above {@code keySpec} turns this into an {@code ArithmeticException}.
     */
    @Test
    void aKeyTypeErrorStillWinsOverTheRowCountGuard()
    {
        IDataTable huge = child(); // AESEQ is numeric here
        doReturn(1L << 31).when(huge).getRowCount();
        IDataTable primary = MockTable.of().col(USUBJID, "P1").col(AESEQ, "1").name("ADAE").build();
        List<MatchDataset> entries = List.of(md("[\"USUBJID\",\"AESEQ\"]", ""));
        DatasetResolver resolver = Map.of(AE, huge)::get;
        assertThrows(JoinKeyTypeMismatchException.class,
                () -> KeyMatchRowExpander.expand(primary, entries, resolver, "R", null));
    }

    /** Local null check with a message, so a missing view fails as an assertion, not an NPE. */
    private static final class Objects2
    {

        private static IDataTable nonNull(@Nullable IDataTable aTable)
        {
            assertNotNull(aTable, "MatchFilter.apply returns a table for a non-null input");
            return aTable;
        }
    }
}
