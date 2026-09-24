package net.cumba.corej.core.exec;

import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;

/**
 * A cache of values built from a table, keyed by the table's <b>identity</b> and a sub-key that
 * names every other input of the build ({@code PLAN-identity-safe-join-caches} D1/D2).
 *
 * <p>
 * ⭐⭐ <b>A hit is exact, never a hash guess.</b> The table is held by a {@link TableRef}: a weak
 * reference whose {@code equals} is reference identity ({@code ==}) on the live referent. The
 * table's {@code identityHashCode} only picks the bucket. That is the difference from the caches
 * this class replaced, which keyed on {@code identityHashCode} alone (at most 31 bits, not unique)
 * and so could hand one table another table's index on a collision, with nothing validating the
 * hit.
 * </p>
 *
 * <p>
 * ⭐ <b>An entry lives exactly as long as its table</b> ({@code PLAN-keymatch-shared-join-index} Q3
 * (d)). Tables are soft-memoised ({@code StudyValidationService.softMemoised}), so under heap
 * pressure a table can be dropped and reloaded as a new instance mid-run: the old instance's
 * entries are swept (on the next miss or count) once the collector clears its reference, and the
 * new instance gets fresh ones, never a stale hit. ⚠ A value must therefore never hold its own
 * table strongly: that would pin the key's referent for the whole run (the classic
 * {@code WeakHashMap} leak). The sweep runs on a MISS and on every count, never on a hit (a hit is
 * the per-row path); a {@code ReferenceQueue} was measured to lag more than 14 s behind a large
 * heap. ⚠ The values therefore outlive their table until the next miss or count: the collection
 * that takes a table only clears the key. A reloaded table's first access IS a miss, so it sweeps
 * the old instance's entries at exactly the moment they could matter.
 * </p>
 *
 * <p>
 * Thread-safe. A value is built at most once per (table instance, sub-key), under
 * {@code computeIfAbsent}, and must be immutable once built. The builder must not touch this cache.
 * </p>
 *
 * @param <K>
 *            the sub-key: an immutable value naming every input of the build besides the table.
 * @param <V>
 *            the cached value.
 */
final class IdentityWeakCache<K, V>
{

    private final ConcurrentHashMap<TableRef, ConcurrentHashMap<K, V>> map = new ConcurrentHashMap<>();

    /** How the table's bucket is chosen; {@code System::identityHashCode} outside tests. */
    private final ToIntFunction<Object> identityHash;

    /** How many values this cache has built — the sharing tests' instrument. */
    private final AtomicInteger builds = new AtomicInteger();

    /** A cache bucketing tables by {@code System.identityHashCode}. */
    IdentityWeakCache()
    {
        this(System::identityHashCode);
    }


    /**
     * A cache with an injected bucket hash. ⭐ The test seam that proves a hit is exact: a test
     * passes {@code _ -> 42}, forcing EVERY table into one bucket, so only identity can tell them
     * apart.
     *
     * @param aIdentityHash
     *            the bucket hash of a table.
     */
    IdentityWeakCache(ToIntFunction<Object> aIdentityHash)
    {
        identityHash = aIdentityHash;
    }


    /**
     * The value for {@code aTable} under {@code aSubKey}, built with {@code aBuild} on first use.
     *
     * @param aTable
     *            the table the value is built from; compared by identity.
     * @param aSubKey
     *            every other input of the build.
     * @param aBuild
     *            builds the value from the sub-key; must not touch this cache.
     * @return the cached or freshly built value.
     */
    V getOrBuild(IDataTable aTable, K aSubKey, Function<? super K, ? extends V> aBuild)
    {
        ConcurrentHashMap<K, V> perTable = map.get(new TableRef(aTable, identityHash));
        if (perTable != null)
        {
            V hit = perTable.get(aSubKey);
            if (hit != null)
            {
                return hit; // the per-row fast path: no sweep, no build
            }
        }
        // A miss: sweep here — on the build path, not per hit. A per-row caller (the wildcard
        // column cache runs once per evaluated row) would otherwise walk the table map every row
        // (review round 1, lane A, LOW-1). Stale entries of collected tables are dropped on the
        // next miss or count, which is when they can matter.
        sweep();
        if (perTable == null)
        {
            perTable = map.computeIfAbsent(new TableRef(aTable, identityHash),
                    _ -> new ConcurrentHashMap<>());
        }
        return perTable.computeIfAbsent(aSubKey, k ->
        {
            builds.incrementAndGet();
            return aBuild.apply(k);
        });
    }


    /** Returns how many values this cache has built so far. */
    int buildCount()
    {
        return builds.get();
    }


    /** Returns how many live tables currently hold values. */
    int tableCount()
    {
        sweep();
        return map.size();
    }


    /** Returns how many values are currently cached, over all live tables. */
    int valueCount()
    {
        sweep();
        int n = 0;
        for (ConcurrentHashMap<K, V> perTable : map.values())
        {
            n += perTable.size();
        }
        return n;
    }


    /** Drops the values of every table the collector has taken. */
    private void sweep()
    {
        // refersTo, not get(): under G1/ZGC concurrent marking, get() runs the keep-alive barrier
        // and would mark a dead table live again on every sweep (review round 1, lane C, MEDIUM).
        map.keySet().removeIf(ref -> ref.refersTo(null));
    }

    /**
     * A weak, identity-compared reference to a table, usable as a map key. Two refs are equal when
     * they refer to the <b>same</b> live table; a cleared ref equals only itself, and is removed by
     * the sweep.
     */
    private static final class TableRef extends WeakReference<IDataTable>
    {

        private final int hash;

        TableRef(IDataTable aTable, ToIntFunction<Object> aIdentityHash)
        {
            super(aTable);
            hash = aIdentityHash.applyAsInt(aTable);
        }


        @Override
        public boolean equals(@Nullable Object aOther)
        {
            if (this == aOther)
            {
                return true;
            }
            if (!(aOther instanceof TableRef other))
            {
                return false;
            }
            IDataTable table = get();
            return table != null && table == other.get();
        }


        @Override
        public int hashCode()
        {
            return hash;
        }
    }
}
