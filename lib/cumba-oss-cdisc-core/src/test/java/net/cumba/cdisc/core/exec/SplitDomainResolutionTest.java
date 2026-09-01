package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.view.UnionDataTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SplitDomainResolution}: exact-first precedence, the split-domain union in
 * deterministic member order, the single-member and oddly-named-member shapes, the two-character
 * bound (no inventory walk for longer names), per-resolver memoisation (including {@code Absent}
 * and {@code Invalid}, and under concurrent access — the study-anchor pass has no
 * {@code JoinCache}, so the memo must not depend on one), and the type-clash {@code Invalid}
 * mapping (ruling 1).
 */
class SplitDomainResolutionTest
{

    private static IDataTable lbch()
    {
        return RealTables.of("lbch").str("DOMAIN", "LB").str("USUBJID", "U1").str("LBSEQ", "1")
                .str("LBORRES", "res-ch").build();
    }


    private static IDataTable lbhe()
    {
        return RealTables.of("lbhe").str("DOMAIN", "LB").str("USUBJID", "U2").str("LBSEQ", "9")
                .str("LBORRES", "res-he").build();
    }

    /** A WithInventory resolver that counts inventory walks ({@code availableDatasets} calls). */
    private static final class CountingInventory implements DatasetResolver.WithInventory
    {

        private final Map<String, IDataTable> byName = new LinkedHashMap<>();

        final AtomicInteger walks = new AtomicInteger();

        CountingInventory(IDataTable... tables)
        {
            for (IDataTable t : tables)
            {
                String n = t.getMetaData().getName();
                byName.put(n != null ? n.toUpperCase(Locale.ROOT) : "?", t);
            }
        }


        @Override
        public @Nullable IDataTable resolve(String name)
        {
            return name == null ? null : byName.get(name.toUpperCase(Locale.ROOT));
        }


        @Override
        public Set<String> availableDatasets()
        {
            walks.incrementAndGet();
            return byName.keySet();
        }
    }

    @Test
    void exactHitWinsOverTheDomainUnion()
    {
        IDataTable exactLb = RealTables.of("LB").str("DOMAIN", "LB").str("USUBJID", "U0").build();
        CountingInventory inv = new CountingInventory(exactLb, lbch(), lbhe());
        DomainResolution r = SplitDomainResolution.resolve(inv, "LB", "R");
        assertSame(exactLb, ((DomainResolution.Table) r).table(),
                "a file literally named LB wins; the union never engages");
        assertEquals(0, inv.walks.get(), "an exact hit must not walk the inventory");
    }


    @Test
    void splitDomain_returnsTheUnionInMemberNameOrder()
    {
        // Registered in reverse order — the union must still stack lbch before lbhe.
        CountingInventory inv = new CountingInventory(lbhe(), lbch());
        DomainResolution r = SplitDomainResolution.resolve(inv, "LB", "R");
        IDataTable t = ((DomainResolution.Table) r).table();
        UnionDataTable union = assertInstanceOf(UnionDataTable.class, t);
        assertEquals("LB", union.getMetaData().getName());
        assertEquals("lbch,lbhe", union.getMetaData().getMetaData(UnionDataTable.META_UNION_OF));
        assertEquals(2, union.getRowCount());
    }


    @Test
    void singleOddlyNamedMember_isReturnedAsThatMember()
    {
        IDataTable aeV2 = RealTables.of("ae_v2").str("DOMAIN", "AE").str("USUBJID", "U1").build();
        CountingInventory inv = new CountingInventory(aeV2);
        DomainResolution r = SplitDomainResolution.resolve(inv, "AE", "R");
        assertSame(aeV2, ((DomainResolution.Table) r).table(), "no wrapper for a single member");
    }


    @Test
    void noMatch_isAbsent_andCached()
    {
        CountingInventory inv = new CountingInventory(lbch(), lbhe());
        assertInstanceOf(DomainResolution.Absent.class,
                SplitDomainResolution.resolve(inv, "ZZ", "R"));
        assertEquals(1, inv.walks.get());
        assertInstanceOf(DomainResolution.Absent.class,
                SplitDomainResolution.resolve(inv, "ZZ", "R"));
        assertEquals(1, inv.walks.get(), "an Absent result is memoised — no second walk");
    }


    @Test
    void threeCharacterName_neverWalksTheInventory()
    {
        // "adsl" is in the inventory but not under the name ADSL; ADSL is 4 chars, so the R-4
        // bound answers Absent without touching the inventory.
        IDataTable adsl = RealTables.of("adsl2").str("DOMAIN", "ADSL").str("TRT01P", "A").build();
        CountingInventory inv = new CountingInventory(adsl);
        assertInstanceOf(DomainResolution.Absent.class,
                SplitDomainResolution.resolve(inv, "ADSL", "R"));
        assertInstanceOf(DomainResolution.Absent.class,
                SplitDomainResolution.resolve(inv, "SUPPLB", "R"));
        assertEquals(0, inv.walks.get(), "the two-character bound must prevent the walk");
    }


    @Test
    void nonInventoryResolver_isExactOnly()
    {
        Map<String, IDataTable> tables = Map.of("LBCH", lbch());
        DatasetResolver plain = tables::get;
        assertInstanceOf(DomainResolution.Absent.class,
                SplitDomainResolution.resolve(plain, "LB", "R"));
        assertInstanceOf(DomainResolution.Table.class,
                SplitDomainResolution.resolve(plain, "LBCH", "R"));
    }


    @Test
    void unionIsMemoised_sameInstanceOnTheSecondCall_withoutAnyJoinCache()
    {
        // The study-anchor pass has joinCache == null (LibraryValidator); memoisation lives on
        // the resolver, so the identity-keyed index caches still see ONE union instance.
        CountingInventory inv = new CountingInventory(lbch(), lbhe());
        IDataTable first = ((DomainResolution.Table) SplitDomainResolution.resolve(inv, "LB", "R"))
                .table();
        IDataTable second = ((DomainResolution.Table) SplitDomainResolution.resolve(inv, "LB", "R"))
                .table();
        assertSame(first, second);
        assertEquals(1, inv.walks.get(), "one inventory walk per (resolver, domain)");
        // A different resolver instance builds its own union — the memo is per resolver.
        CountingInventory other = new CountingInventory(lbch(), lbhe());
        IDataTable third = ((DomainResolution.Table) SplitDomainResolution.resolve(other, "LB",
                "R")).table();
        assertTrue(third != first, "the memo must be scoped to the resolver instance");
    }


    @Test
    void typeClash_isInvalid_namingColumnAndMembers_andCached()
    {
        IDataTable numeric = RealTables.of("lbch").str("DOMAIN", "LB").str("USUBJID", "U1")
                .lng("LBSTRESN", 1L).build();
        IDataTable text = RealTables.of("lbhe").str("DOMAIN", "LB").str("USUBJID", "U2")
                .str("LBSTRESN", "high").build();
        CountingInventory inv = new CountingInventory(numeric, text);
        DomainResolution r = SplitDomainResolution.resolve(inv, "LB", "R");
        DomainResolution.Invalid invalid = assertInstanceOf(DomainResolution.Invalid.class, r);
        assertEquals("LB", invalid.domain());
        assertTrue(invalid.message().contains("LBSTRESN"), invalid.message());
        assertTrue(invalid.message().contains("lbch"), invalid.message());
        assertTrue(invalid.message().contains("lbhe"), invalid.message());
        assertTrue(invalid.message().contains("cannot be joined"), invalid.message());
        // Cached: diagnosed once per run, not once per rule.
        assertSame(invalid, SplitDomainResolution.resolve(inv, "LB", "R"));
        assertEquals(1, inv.walks.get());
        // resolveTableOrThrow maps Invalid to the rule-ERROR exception (ruling 1).
        InvalidJoinedDomainException ex = assertThrows(InvalidJoinedDomainException.class,
                () -> SplitDomainResolution.resolveTableOrThrow(inv, "LB", "R"));
        assertEquals(invalid.message(), ex.getMessage());
        assertEquals("LB", ex.domain());
        // An un-unionable split still counts as PRESENT (its data ships with the submission).
        assertTrue(SplitDomainResolution.isPresentAsDomain(inv, "LB"));
    }


    @Test
    void resolveTableOrThrow_mapsTableAndAbsent()
    {
        CountingInventory inv = new CountingInventory(lbch(), lbhe());
        assertInstanceOf(UnionDataTable.class,
                SplitDomainResolution.resolveTableOrThrow(inv, "LB", "R"));
        assertNull(SplitDomainResolution.resolveTableOrThrow(inv, "ZZ", "R"));
    }


    @Test
    void isPresentAsDomain_widensToSplitDomains_boundedToDomainCodes()
    {
        IDataTable adsl = RealTables.of("adsl2").str("DOMAIN", "ADSL").str("TRT01P", "A").build();
        CountingInventory inv = new CountingInventory(lbch(), lbhe(), adsl);
        assertTrue(SplitDomainResolution.isPresentAsDomain(inv, "LB"), "split LB is present");
        assertFalse(SplitDomainResolution.isPresentAsDomain(inv, "ZZ"));
        assertFalse(SplitDomainResolution.isPresentAsDomain(inv, "ADSL"),
                "a 4-character name gets no domain fallback (the ScopeVariables hazard)");
    }


    @Test
    void concurrentResolves_buildTheUnionOnce() throws Exception
    {
        CountingInventory inv = new CountingInventory(lbch(), lbhe());
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> pool = new ArrayList<>();
        IDataTable[] results = new IDataTable[threads];
        for (int i = 0; i < threads; i++)
        {
            final int slot = i;
            Thread t = new Thread(() ->
            {
                try
                {
                    start.await();
                }
                catch (InterruptedException _)
                {
                    Thread.currentThread().interrupt();
                    return;
                }
                results[slot] = ((DomainResolution.Table) SplitDomainResolution.resolve(inv, "LB",
                        "R")).table();
            });
            t.start();
            pool.add(t);
        }
        start.countDown();
        for (Thread t : pool)
        {
            t.join(30_000);
        }
        assertEquals(1, inv.walks.get(), "computeIfAbsent must build the union exactly once");
        for (IDataTable r : results)
        {
            assertNotNull(r,
                    "a worker died before resolving — the identity check would be vacuous");
            assertSame(results[0], r, "every thread must see the same union instance");
        }
    }

}
