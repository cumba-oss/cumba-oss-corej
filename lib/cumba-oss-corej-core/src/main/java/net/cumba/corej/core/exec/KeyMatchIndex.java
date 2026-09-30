package net.cumba.corej.core.exec;

import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;

import net.cumba.datatable.values.GroupKeyPolicy.KeyPart;
import org.jspecify.annotations.Nullable;

/**
 * The joined (child) side of a key-based {@code Match_Datasets} join, as used by
 * {@link KeyMatchRowExpander}: every distinct join key, and for each key the child rows carrying
 * it, in ascending row order.
 *
 * <p>
 * ⭐ <b>Shaped for memory, because it is shared.</b> One index serves every rule that joins the same
 * child table on the same key shape ({@link JoinCache.SharedIndexCache}), across rules running in
 * parallel, so it is built for a small resident footprint rather than for convenience
 * ({@code PLAN-keymatch-shared-join-index} D7, proposals P1–P3 in
 * {@code plans/findings/FINDINGS-keymatch-data-structures.md}):
 * </p>
 * <ul>
 * <li>an <b>open-addressing</b> table maps a key to a dense key id — no entry objects, no boxing;
 * </li>
 * <li>the rows are held <b>CSR</b>-style: {@code rows} lists every participating child row grouped
 * by key id, and {@code offsets[id] .. offsets[id + 1]} is that key's slice. Within a slice the
 * rows are ascending, which is the order the previous {@code Map<List<KeyPart>, List<Long>>}
 * yielded;</li>
 * <li>a key is the {@link KeyPart} itself for a one-component join, and a {@link CompositeKey} for
 * a multi-component one — never a {@code List};</li>
 * <li>a lookup allocates nothing: {@link #find(KeyPart[])} compares a caller-owned scratch array
 * against the stored composite keys without building one.</li>
 * </ul>
 *
 * <p>
 * Key identity is exactly {@code KeyPart.equals}, component-wise — the identity the
 * {@code List<KeyPart>} key had. Row ids are {@code int}: the key-match path assumes a table fits
 * in an {@code int} row count and guards that with {@link Math#toIntExact}.
 * </p>
 *
 * <p>
 * Immutable once built, and no array is ever exposed, so an index is safe to share between threads
 * once safely published (the cache publishes it through a {@code ConcurrentHashMap}).
 * </p>
 */
final class KeyMatchIndex
{

    /** Returned by the {@code find} methods for a key the child does not carry. */
    static final int NOT_FOUND = -1;

    private static final int MIN_CAPACITY = 16;

    /** Slot table: the key stored in each slot, {@code null} for an empty slot. */
    private final @Nullable Object[] slotKeys;

    /** Slot table: the dense key id of the key in the same slot. */
    private final int[] slotIds;

    private final int slotMask;

    /** {@code offsets[id] .. offsets[id + 1]} is key {@code id}'s slice of {@link #rows}. */
    private final int[] offsets;

    /** Every participating child row, grouped by key id, ascending within a key. */
    private final int[] rows;

    private KeyMatchIndex(@Nullable Object[] aSlotKeys, int[] aSlotIds, int[] aOffsets, int[] aRows)
    {
        slotKeys = aSlotKeys;
        slotIds = aSlotIds;
        slotMask = aSlotKeys.length - 1;
        offsets = aOffsets;
        rows = aRows;
    }


    /**
     * Builds the index in two passes. The first computes each row's key <b>once</b>, assigns key
     * ids and counts the rows per key; the second lays the rows out by key. The only temporary is
     * one {@code int} per row.
     *
     * @param aRowCount
     *            the child's row count.
     * @param aKeyOfRow
     *            the key of a row — a {@link KeyPart} or a {@link CompositeKey}, the same kind for
     *            every row — or {@code null} when the row does not take part in the join. Called
     *            exactly once per row, in ascending row order.
     * @return the index, never {@code null}.
     */
    static KeyMatchIndex build(int aRowCount, IntFunction<@Nullable Object> aKeyOfRow)
    {
        KeyTable table = new KeyTable();
        int[] rowKey = new int[aRowCount];
        for (int r = 0; r < aRowCount; r++)
        {
            Object key = aKeyOfRow.apply(r);
            rowKey[r] = key == null ? NOT_FOUND : table.idOf(key);
        }
        int keyCount = table.size;
        int[] offsets = new int[keyCount + 1];
        for (int id = 0; id < keyCount; id++)
        {
            offsets[id + 1] = offsets[id] + table.counts[id];
        }
        int[] rows = new int[offsets[keyCount]];
        int[] cursor = Arrays.copyOf(offsets, keyCount);
        for (int r = 0; r < aRowCount; r++)
        {
            int id = rowKey[r];
            if (id != NOT_FOUND)
            {
                rows[cursor[id]++] = r;
            }
        }
        return new KeyMatchIndex(table.keys, table.ids, offsets, rows);
    }


    /**
     * The id of a one-component key.
     *
     * @param aKey
     *            the key part.
     * @return its key id, or {@link #NOT_FOUND}.
     */
    int find(KeyPart aKey)
    {
        for (int slot = spread(aKey.hashCode()) & slotMask;; slot = (slot + 1) & slotMask)
        {
            Object stored = slotKeys[slot];
            if (stored == null)
            {
                return NOT_FOUND;
            }
            if (stored.equals(aKey))
            {
                return slotIds[slot];
            }
        }
    }


    /**
     * The id of a multi-component key, given as a scratch array the caller owns and may reuse — no
     * {@link CompositeKey} is built for the lookup.
     *
     * @param aParts
     *            the key's components, in key order.
     * @return its key id, or {@link #NOT_FOUND}.
     */
    int find(KeyPart[] aParts)
    {
        int hash = Arrays.hashCode(aParts);
        for (int slot = spread(hash) & slotMask;; slot = (slot + 1) & slotMask)
        {
            Object stored = slotKeys[slot];
            if (stored == null)
            {
                return NOT_FOUND;
            }
            if (stored instanceof CompositeKey composite && composite.matches(hash, aParts))
            {
                return slotIds[slot];
            }
        }
    }


    /** Returns the first position of key {@code aId}'s slice, for {@link #row}. */
    int start(int aId)
    {
        return offsets[aId];
    }


    /** Returns one past the last position of key {@code aId}'s slice. */
    int end(int aId)
    {
        return offsets[aId + 1];
    }


    /** Returns the child row at slice position {@code aPos}. */
    int row(int aPos)
    {
        return rows[aPos];
    }


    /** Returns the number of distinct keys. */
    int keyCount()
    {
        return offsets.length - 1;
    }


    /**
     * Returns the number of participating child rows (a row dropped by {@code keep_missings: false}
     * is not one).
     */
    int rowCount()
    {
        return rows.length;
    }


    private static int spread(int aHash)
    {
        int h = aHash * 0x9E3779B9;
        return h ^ (h >>> 16);
    }

    /**
     * A multi-component join key: the components in key order and their hash, computed once. The
     * hash is {@link Arrays#hashCode(Object[])} of the components, so {@link #find(KeyPart[])} can
     * compute it from a scratch array. Equality is component-wise {@code KeyPart.equals}.
     *
     * <p>
     * ⚠ A class and not a record: a record's generated {@code equals} would compare the array by
     * identity (Error Prone {@code ArrayRecordComponent}).
     * </p>
     */
    static final class CompositeKey
    {

        private final KeyPart[] parts;

        private final int hash;

        /**
         * @param aParts
         *            the components; the array is owned by the key from here on and must not be
         *            modified.
         */
        CompositeKey(KeyPart[] aParts)
        {
            parts = aParts;
            hash = Arrays.hashCode(aParts);
        }


        private boolean matches(int aHash, KeyPart[] aParts)
        {
            return hash == aHash && Arrays.equals(parts, aParts);
        }


        @Override
        public boolean equals(@Nullable Object aOther)
        {
            return aOther instanceof CompositeKey other && other.matches(hash, parts);
        }


        @Override
        public int hashCode()
        {
            return hash;
        }


        @Override
        public String toString()
        {
            return Arrays.toString(parts);
        }
    }


    /**
     * What the cached index depends on besides the child table itself — the child-side half of a
     * resolved key shape ({@code PLAN-keymatch-shared-join-index} D2). Two joins of the same table
     * with equal {@code SpecKey}s build identical indexes, so they share one.
     *
     * <p>
     * ⛔ Every field is load-bearing; leaving one out lets two joins that key the child differently
     * share an index, which reads as a silent zero-match:
     * </p>
     * <ul>
     * <li>{@code childColIds} — the child key columns in key order, {@code -1} where absent;</li>
     * <li>{@code active} — per component, whether it takes part at all; derived from <b>both</b>
     * tables (JKM R7: absent on both sides drops the component);</li>
     * <li>{@code childAbsentParts} — what an absent child column contributes, one entry per active
     * component whose child column is {@code -1}, in key order; its type comes from the
     * <b>primary</b>'s column;</li>
     * <li>{@code keepMissings} — JKM R4: whether blank-keyed rows take part;</li>
     * <li>{@code asString} — {@code Join_As_String}: present parts are compared as rendered text,
     * so {@code 5.0} keys as {@code Present("5")} rather than {@code PresentNumber(5.0)}.</li>
     * </ul>
     *
     * <p>
     * Lists, not arrays, so the record's generated {@code equals} compares contents.
     * </p>
     *
     * <p>
     * ⭐ Deliberately the CHILD side only, and that is what lets a chained join share an index
     * ({@code PLAN-rprfdy-offset-tp-join} C1): a rule joining TP on {@code [DM.RPATHCD, RPHASE]}
     * and a rule joining TP on a primary {@code [RPATHCD, RPHASE]} probe the same TP columns, so
     * they build the identical index once — the record side (where the probe reads from) is no part
     * of the key. A qualified component can never reach {@code childAbsentParts}: an absent
     * unqualified column on the joined side is an ERROR before the index is built.
     * </p>
     */
    record SpecKey(List<Integer> childColIds, List<Boolean> active, List<KeyPart> childAbsentParts,
            boolean keepMissings, boolean asString)
    {

        SpecKey
        {
            childColIds = List.copyOf(childColIds);
            active = List.copyOf(active);
            childAbsentParts = List.copyOf(childAbsentParts);
        }
    }


    /** The growing key table used while building; frozen into the index afterwards. */
    private static final class KeyTable
    {

        private @Nullable Object[] keys = new Object[MIN_CAPACITY];

        private int[] ids = new int[MIN_CAPACITY];

        private int[] counts = new int[MIN_CAPACITY];

        private int size;

        /** The key's id, assigning the next one on first sight, and counts one row for it. */
        private int idOf(Object aKey)
        {
            int mask = keys.length - 1;
            int slot = spread(aKey.hashCode()) & mask;
            while (true)
            {
                Object stored = keys[slot];
                if (stored == null)
                {
                    break;
                }
                if (stored.equals(aKey))
                {
                    counts[ids[slot]]++;
                    return ids[slot];
                }
                slot = (slot + 1) & mask;
            }
            int id = size++;
            keys[slot] = aKey;
            ids[slot] = id;
            if (id == counts.length)
            {
                counts = Arrays.copyOf(counts, counts.length * 2);
            }
            counts[id] = 1;
            if (size * 2 > keys.length)
            {
                grow();
            }
            return id;
        }


        /** Doubles the slot table, keeping the load factor at or below one half. */
        private void grow()
        {
            @Nullable
            Object[] oldKeys = keys;
            int[] oldIds = ids;
            keys = new Object[oldKeys.length * 2];
            ids = new int[oldKeys.length * 2];
            int mask = keys.length - 1;
            for (int i = 0; i < oldKeys.length; i++)
            {
                Object key = oldKeys[i];
                if (key == null)
                {
                    continue;
                }
                int slot = spread(key.hashCode()) & mask;
                while (keys[slot] != null)
                {
                    slot = (slot + 1) & mask;
                }
                keys[slot] = key;
                ids[slot] = oldIds[i];
            }
        }
    }
}
