package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * ⭐ Why the merged views of a {@link JoinCache} are keyed on the merge SIGNATURE and not on the
 * partner tables' identity ({@code PLAN-merged-evaluation-table} T1, the W6 note: the retired
 * {@code DatasetLookup} cache validated identity, D5 of {@code PLAN-identity-safe-join-caches}).
 *
 * <p>
 * A partner reloaded as a NEW instance while its signature's view is cached is unreachable: the
 * built view holds every partner strongly (its parts are views over them), so the run's soft
 * dataset memo cannot clear and reload a partner while the view lives, and the cache dies with its
 * dataset. This class pins that mechanism — a resolver that holds the partner only weakly (the
 * strictest memo) still answers the very instance the cached view reads — and shows the instrument
 * can see the opposite: with no view holding it, the same partner IS reclaimed. If a view ever
 * stopped pinning its partners (a weak part, a lazily re-resolved partner), the first test reds and
 * the cache key must then carry, or validate, the partners' identity.
 * </p>
 */
class JoinCacheViewIdentityTest
{

    private static final int GC_ATTEMPTS = 50;

    /** A resolver that holds the partner ONLY weakly — a memo the JVM may clear at will. */
    private static final class WeakPartnerResolver implements DatasetResolver
    {

        private final IDataTable primary;

        private final WeakReference<IDataTable> partner;

        WeakPartnerResolver(IDataTable aPrimary, IDataTable aPartner)
        {
            primary = aPrimary;
            partner = new WeakReference<>(aPartner);
        }


        @Override
        public @Nullable IDataTable resolve(String aName)
        {
            return "AE".equalsIgnoreCase(aName) ? partner.get()
                    : "DM".equalsIgnoreCase(aName) ? primary : null;
        }
    }

    private static IDataTable primary()
    {
        return RealTables.of("DM").str("USUBJID", "P1", "P2").str("AGE", "34", "51").build();
    }


    private static IDataTable partner()
    {
        return RealTables.of("AE").str("USUBJID", "P1", "P2").str("AETERM", "HEADACHE", "NAUSEA")
                .build();
    }


    private static Rule rule()
    {
        MatchDataset md = new MatchDataset();
        md.setName("AE");
        md.setKeys(List.of("USUBJID"));
        md.setJoinType("left");
        Rule rule = new Rule();
        rule.setMatchDatasets(List.of(md));
        rule.setSuppMerge(Boolean.FALSE);
        return rule;
    }


    /** Runs the collector until {@code ref} clears or the attempts run out; whether it cleared. */
    private static boolean collected(WeakReference<?> ref)
    {
        List<byte[]> pressure = new ArrayList<>();
        for (int i = 0; i < GC_ATTEMPTS && ref.get() != null; i++)
        {
            pressure.add(new byte[1 << 20]);
            System.gc();
            if (pressure.size() > 8)
            {
                pressure.clear();
            }
        }
        return ref.get() == null;
    }


    @Test
    void aCachedViewPinsItsPartnerSoTheSignatureNeverMeetsAReloadedInstance()
    {
        IDataTable dm = primary();
        WeakPartnerResolver resolver = new WeakPartnerResolver(dm, partner());
        JoinCache cache = new JoinCache(new JoinCache.SharedIndexCache());
        MergedEvaluation.Built first = MergedEvaluation.build(rule(), dm, dm, resolver, cache, "R1",
                "DM");
        assertNotNull(first);
        WeakReference<IDataTable> seen = new WeakReference<>(resolver.resolve("AE"));
        assertNotNull(seen.get(), "the partner resolves while the view is cached");

        // the test holds no strong reference to the partner: only the cached view does
        assertEquals(false, collected(seen),
                "the cached view holds its partner strongly — the memo cannot clear it, so no"
                        + " reload can hand a later rule of this dataset a NEW instance");
        MergedEvaluation.Built second = MergedEvaluation.build(rule(), dm, dm, resolver, cache,
                "R2", "DM");
        assertSame(first, second, "one signature, one view (P-3)");
        assertEquals(1, cache.mergedViewCount());
        assertSame(seen.get(), resolver.resolve("AE"),
                "a resolve during the view's life answers the very instance the view reads");
        assertEquals("NAUSEA", second.lookups().get("AE").lookup(second.table(), 1L, "AETERM"),
                "and the view reads that instance's data");
    }


    /**
     * ⭐ The planted control: the same partner, the same weak resolver, NO cached view — the
     * collector reclaims it. Without this the first test's "not collected" could only mean the
     * collector never ran.
     */
    @Test
    void withoutAViewTheSamePartnerIsReclaimed()
    {
        IDataTable dm = primary();
        WeakPartnerResolver resolver = new WeakPartnerResolver(dm, partner());
        WeakReference<IDataTable> seen = new WeakReference<>(resolver.resolve("AE"));
        assertNotNull(seen.get());
        assertEquals(true, collected(seen), "nothing pins the partner: the memo loses it");
        assertNull(resolver.resolve("AE"), "the next resolve would have to reload it");
    }
}
