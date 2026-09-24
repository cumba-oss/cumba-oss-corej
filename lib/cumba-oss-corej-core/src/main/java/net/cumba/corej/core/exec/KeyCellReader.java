package net.cumba.corej.core.exec;

import net.cumba.corej.core.exec.GroupKeyPolicy.KeyPart;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.impl.CachedDataTableColumn;
import net.cumba.datatable.impl.ColumnCachedDataTable;
import net.cumba.datatable.impl.view.UnionDataTable;
import net.cumba.datatable.values.DataValueType;
import org.jspecify.annotations.Nullable;

/**
 * Reads one key column's cells as {@link KeyPart}s — exactly what
 * {@code GroupKeyPolicy.KEEP_MISSING_KEYS.keyPart(table.getColumn(c).getDataValue(row))} answers,
 * without allocating the {@code IDataValue} (and, on views, the {@code DefaultDataTableColumn})
 * that path allocates per cell ({@code PLAN-identity-safe-join-caches} D6/D8).
 *
 * <p>
 * ⭐⭐ <b>Fast for PRESENT cells only; every other cell takes today's path.</b> The fast read is the
 * COLUMN's: {@link CachedDataTableColumn#presentStringValue} and
 * {@link CachedDataTableColumn#presentNumericValue} answer a present value without building a data
 * value, or say "ask {@code getDataValue}". Each datatable twin derives them from its OWN value
 * creation — the twins' buffer sets and {@code createDataValue} arms differ (the OSS twin has no
 * factor or bit-packed buffers, and turns a NaN in a LONG column into a present 0 where the
 * internal twin answers a missing value), so no reproduction of them could live here and be right
 * in both ({@code PLAN-identity-safe-join-caches} D6, as rebuilt at the OSS phase). The engine
 * names no buffer class, and so stays byte-identical across the twins. What the column answers maps
 * onto {@code keyPart} exactly: a non-empty {@code DataValueString} is {@code Present(s)}; a
 * non-missing {@code DataValueLong}/{@code DataValueDouble} is {@code PresentNumber(value)}.
 * </p>
 *
 * <p>
 * Dispatch is on the EXACT table and column classes, so a class this reader does not know — a view,
 * a mock — always takes the old path: safe, never wrong. A {@link UnionDataTable} (a split domain)
 * reads each row through its member ({@link UnionDataTable#memberOf}); a column the member lacks is
 * read through the union itself, exactly as before.
 * </p>
 */
@FunctionalInterface
interface KeyCellReader
{

    /**
     * The row's key part.
     *
     * @param aRow
     *            the row in the table this reader was built for.
     * @return exactly {@code KEEP_MISSING_KEYS.keyPart(table.getColumn(c).getDataValue(aRow))}.
     */
    KeyPart read(long aRow);


    /**
     * A reader for column {@code aColumn} of {@code aTable}.
     *
     * @param aTable
     *            the table.
     * @param aColumn
     *            a valid column index of {@code aTable}.
     * @return the reader; the old path for any table or column class other than the two named
     *         above.
     */
    static KeyCellReader of(IDataTable aTable, int aColumn)
    {
        if (!DirectReads.enabled())
        {
            return slow(aTable, aColumn);
        }
        if (aTable.getClass() == ColumnCachedDataTable.class)
        {
            IDataTableColumn column;
            try
            {
                column = aTable.getColumn(aColumn);
            }
            catch (IndexOutOfBoundsException ex)
            {
                // The old path resolved the column per CELL, so a key column the table's metadata
                // names but its held columns lack threw only when a row was read — never for a
                // zero-row table. Resolving it here must not move that throw to build time
                // (review round 1, lane B): such a column reads the old way.
                return slow(aTable, aColumn);
            }
            if (column.getClass() == CachedDataTableColumn.class
                    && column instanceof CachedDataTableColumn cached)
            {
                return new Direct(cached);
            }
        }
        if (aTable.getClass() == UnionDataTable.class && aTable instanceof UnionDataTable union)
        {
            return new Union(union, aColumn);
        }
        return slow(aTable, aColumn);
    }


    /**
     * One reader per key column; {@code null} where the column is absent ({@code -1}).
     *
     * @param aTable
     *            the table.
     * @param aColumns
     *            the key column indices, {@code -1} where absent.
     * @return the readers, index-aligned with {@code aColumns}.
     */
    static @Nullable KeyCellReader[] of(IDataTable aTable, int[] aColumns)
    {
        @Nullable
        KeyCellReader[] readers = new KeyCellReader[aColumns.length];
        for (int i = 0; i < aColumns.length; i++)
        {
            readers[i] = aColumns[i] < 0 ? null : of(aTable, aColumns[i]);
        }
        return readers;
    }


    /** Today's path: a data value per cell, classified by {@code keyPart}. */
    private static KeyCellReader slow(IDataTable aTable, int aColumn)
    {
        return row -> GroupKeyPolicy.KEEP_MISSING_KEYS
                .keyPart(aTable.getColumn(aColumn).getDataValue(row));
    }

    /**
     * A {@link CachedDataTableColumn} of a {@link ColumnCachedDataTable}: present cells straight
     * from the buffer, everything else the old way. A named class (not a lambda) so a test can
     * prove the dispatcher chose it, and it counts its fall-backs so a test can prove the fast arm
     * is what answered (review round 1, lane B, MEDIUM). A reader is confined to the call that
     * built it, so the plain counter is not shared between threads.
     */
    final class Direct implements KeyCellReader
    {

        private final CachedDataTableColumn column;

        private final DataValueType type;

        private long fallbacks;

        Direct(CachedDataTableColumn aColumn)
        {
            column = aColumn;
            type = aColumn.getType();
        }


        @Override
        public KeyPart read(long aRow)
        {
            // The column's present-value reads call ensureValidRow first — the very check
            // getDataValue makes — so a bad row throws the same exception, message included.
            switch (type)
            {
            case STRING ->
            {
                String s = column.presentStringValue(aRow);
                if (s != null)
                {
                    return new KeyPart.Present(s);
                }
            }
            case LONG, DOUBLE ->
            {
                double v = column.presentNumericValue(aRow);
                if (!Double.isNaN(v))
                {
                    return new KeyPart.PresentNumber(v);
                }
            }
            default ->
            {
                // no fast read for any other declared type
            }
            }
            fallbacks++;
            return GroupKeyPolicy.KEEP_MISSING_KEYS.keyPart(column.getDataValue(aRow));
        }


        /** Returns how many cells this reader answered the old way — the test instrument. */
        long fallbacks()
        {
            return fallbacks;
        }
    }


    /**
     * A split-domain union: each row is read through the member that holds it, with that member's
     * own reader (built lazily, once per member); a column the member lacks is read through the
     * union, exactly as before. Confined to the call that built it, like every reader.
     */
    final class Union implements KeyCellReader
    {

        private final UnionDataTable union;

        private final int column;

        private final @Nullable KeyCellReader[] perMember;

        private final KeyCellReader viaUnion;

        Union(UnionDataTable aUnion, int aColumn)
        {
            union = aUnion;
            column = aColumn;
            perMember = new KeyCellReader[aUnion.memberCount()];
            viaUnion = slow(aUnion, aColumn);
        }


        @Override
        public KeyPart read(long aRow)
        {
            int m = union.memberOf(aRow); // throws exactly as getDataValue does for a bad row
            int memberColumn = union.memberColumnOf(m, column);
            if (memberColumn < 0)
            {
                return viaUnion.read(aRow);
            }
            KeyCellReader r = perMember[m];
            if (r == null)
            {
                r = of(union.member(m), memberColumn);
                perMember[m] = r;
            }
            return r.read(aRow - union.memberRowStart(m));
        }


        /** Returns the reader built for member {@code aMember}, or {@code null} — for tests. */
        @Nullable
        KeyCellReader memberReader(int aMember)
        {
            return perMember[aMember];
        }
    }


    /** The test switch that forces every reader onto the old path. */
    final class DirectReads
    {

        /** {@code false} forces the old path everywhere — for the on/off equivalence tests. */
        private static volatile boolean enabled = true;

        private DirectReads()
        {
        }


        /** Returns whether direct reads are on (always, outside the on/off tests). */
        static boolean enabled()
        {
            return enabled;
        }


        /** Turns direct reads on or off — test use only, restored by the test afterwards. */
        static void setEnabledForTest(boolean aEnabled)
        {
            enabled = aEnabled;
        }
    }
}
