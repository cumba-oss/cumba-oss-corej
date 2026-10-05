package net.cumba.corej.core.exec;

import java.util.Arrays;
import java.util.function.IntToLongFunction;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.databuffer.DataBufferFactory;
import net.cumba.datatable.impl.databuffer.IDataBufferNumeric;
import org.jspecify.annotations.Nullable;

/**
 * The <b>real rows</b> of a row-mapped evaluation table ({@code PLAN-merged-evaluation-table} §3.8,
 * W3e): which merged rows are copies of one primary record. A one-to-many merge gives a primary
 * record one evaluation row per related record; the findings sink (MET #22, P-18) and the
 * aggregates over primary columns (OD-6) need to tell those copies apart from distinct records.
 *
 * <ul>
 * <li>{@link #count(IDataTable)} — the number of distinct real rows the table evaluates: the
 * primary records an inner merge kept, each once. On a table that is not row-mapped it is the row
 * count.</li>
 * <li>{@link #ofRepeating(IDataTable)} — the projection onto one row per real row, or {@code null}
 * when no real row repeats (no expansion: the table's rows already are distinct records, so nothing
 * is de-duplicated and nothing is re-counted — a non-expanded table is provably unchanged).</li>
 * </ul>
 *
 * <p>
 * Computed once per row-mapped table and kept on it ({@link RelrecExpandedTable}): the merged
 * evaluation view's table is cached per (dataset, merge signature) (P-3), so every rule sharing the
 * signature shares this too. Memory: two compact int buffers sized by the merged rows (the
 * representative of every merged row) and the distinct real rows (the merged row standing for
 * each); a temporary {@code int[]} over the base rows while computing. No per-row object.
 * </p>
 */
public final class RealRows
{

    /** The representative's table: row {@code i} is the {@code i}-th distinct real row. */
    private final IDataTable table;

    /** Merged row → index of its representative among the distinct real rows. */
    private final IDataBufferNumeric representativeOf;

    private final int count;

    /** Whether some real row repeats (else no buffer is kept and {@link #ofRepeating} is null). */
    private final boolean repeats;

    private RealRows(IDataTable aTable, IDataBufferNumeric aRepresentativeOf, int aCount,
            boolean aRepeats)
    {
        table = aTable;
        representativeOf = aRepresentativeOf;
        count = aCount;
        repeats = aRepeats;
    }


    /**
     * The real-row projection of {@code table}, or {@code null} when no real row repeats in it.
     *
     * @param table
     *            an evaluation table
     * @return the projection, or {@code null} for a table whose rows are distinct records
     */
    public static @Nullable RealRows ofRepeating(IDataTable table)
    {
        return table instanceof RelrecExpandedTable expanded ? expanded.realRows().repeating()
                : null;
    }


    /**
     * The number of distinct real rows (primary records) {@code table} evaluates — what
     * {@code record_count}, {@code RuleExecutionResult.totalRows} and every count over primary
     * columns read (OD-6).
     *
     * @param table
     *            an evaluation table
     * @return the distinct real rows; the row count for a table that is not row-mapped
     */
    public static long count(IDataTable table)
    {
        return table instanceof RelrecExpandedTable expanded ? expanded.realRows().count
                : table.getRowCount();
    }


    /**
     * The table of the distinct real rows, in first-appearance order: row {@code i} reads the
     * merged row that first carried the {@code i}-th real row (every primary column of it reads
     * that record's cell; {@code getRealRowIndex} answers the record).
     *
     * @return the projection's table
     */
    public IDataTable table()
    {
        return table;
    }


    /**
     * The number of distinct real rows.
     *
     * @return the number of distinct real rows
     */
    public int count()
    {
        return count;
    }


    /**
     * The row of {@link #table()} standing for merged row {@code row}.
     *
     * @param row
     *            a merged row
     * @return the representative's row in {@link #table()}
     */
    public int representative(int row)
    {
        return (int) representativeOf.getValueAsLong(row);
    }


    /** {@code this}, or {@code null} when no real row repeats (no buffers are kept then). */
    private @Nullable RealRows repeating()
    {
        return repeats ? this : null;
    }


    /**
     * Computes the projection of a row-mapped table.
     *
     * @param expanded
     *            the row-mapped table
     * @param baseRowOf
     *            merged row → base row
     * @param baseRows
     *            the base table's row count (base rows are {@code [0, baseRows)})
     * @return the projection; for a table with no repeated real row one that keeps no buffer
     */
    static RealRows compute(RelrecExpandedTable expanded, IntToLongFunction baseRowOf,
            long baseRows)
    {
        int rows = Math.toIntExact(expanded.getRowCount());
        int[] firstSeen = new int[Math.toIntExact(baseRows)];
        Arrays.fill(firstSeen, -1);
        IDataBufferNumeric representativeOf = DataBufferFactory.get().createForRange(0,
                Math.max(0, rows - 1L));
        representativeOf.setExpectedSize(rows);
        IDataBufferNumeric representatives = DataBufferFactory.get().createForRange(0,
                Math.max(0, rows - 1L));
        int distinct = 0;
        for (int r = 0; r < rows; r++)
        {
            int base = Math.toIntExact(baseRowOf.applyAsLong(r));
            int seen = firstSeen[base];
            if (seen < 0)
            {
                seen = distinct++;
                firstSeen[base] = seen;
                representatives.addValue(r);
            }
            representativeOf.addValue(seen);
        }
        if (distinct == rows)
        {
            // No real row repeats: the rows already are distinct records. Keep nothing but the
            // count (an inner merge that only dropped rows lands here).
            return new RealRows(expanded, DataBufferFactory.get().createForRange(0, 0), distinct,
                    false);
        }
        return new RealRows(new RelrecExpandedTable(expanded, representatives), representativeOf,
                distinct, true);
    }
}
