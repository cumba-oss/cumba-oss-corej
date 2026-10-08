package net.cumba.corej.core.expr.eval;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import net.cumba.corej.core.exec.JoinedRowFold;
import net.cumba.datatable.values.DataValueType;
import org.jspecify.annotations.Nullable;

/**
 * A per-row <b>{@code list<boolean>}</b> held as bits, never boxed ({@code
 * PLAN-elementwise-boolean-predicates} §3.3, P3; runbook W1a EB1): the answer of an element-wise
 * BOOLEAN predicate ({@link FunctionDescriptor#elementwise()}, {@link ElementwiseLift}) over a list
 * at its parameter 0 — one two-valued answer per element, the predicate's own (owner Q2: there are
 * no missing booleans). One of three forms, each the shape the lifter's case produces:
 *
 * <ul>
 * <li><b>columns</b> — {@code is_valid_date(values([X, Y]))}: K {@link BitSet}s over the rows, one
 * kernel call each ({@link ElementwiseLift} case 2); the quantifiers are column-wide bit
 * operations.</li>
 * <li><b>flattened</b> — a per-row list ({@code split_by}, a {@code $} binding of one; case 4): one
 * bit set over the element range and the lifter's {@code int[] offsets} ({@code offsets[r]} is row
 * {@code r}'s first element, {@code offsets[r + 1]} its end). A row holding a scalar or a missing
 * instead of a list is one element wide (P6: a scalar answer is a singleton); an empty list row
 * answers the empty conventions.</li>
 * <li><b>broadcast</b> — a dataset-level list ({@code $l: ["2020-01-01", "x"]}; case 3): one bit
 * set over the K elements, the same list on every row, so a quantifier over it is a dataset-level
 * constant (P8).</li>
 * </ul>
 *
 * <p>
 * <b>The reductions</b> ({@link #reduce}) are the quantifiers' element arm — two-valued OR / AND /
 * NOR, {@code any([])} false, {@code all([])} true, {@code none([])} true (Q2, the same conventions
 * as {@link JoinedRowFold} over a record's rows) — answering a <b>new</b> row bit set, never
 * materialising a row's list. {@link #value(int)} exists for the {@link Vector} contract and
 * diagnostics only ({@code [true, false]} as a {@code List<Boolean>}): no legal rule reads a
 * {@code list<boolean>} as a value (Q3 — stage A and the loader refuse every such position, the
 * compiler ERRORs the one shape only visible at evaluation).
 * </p>
 */
public final class BoolListVector implements Vector
{

    private final int rowCount;

    /** The K column bit sets (columns form), or {@code null}. */
    private final @Nullable List<BitSet> columns;

    /** The element-range bits (flattened and broadcast forms), or {@code null}. */
    private final @Nullable BitSet elements;

    /** The flattened form's row offsets ({@code rowCount + 1} entries), or {@code null}. */
    private final int @Nullable [] offsets;

    /** The broadcast form's element count, or {@code -1}. */
    private final int broadcastSize;

    private @Nullable TypedValue broadcastValue;

    private BoolListVector(int rowCount, @Nullable List<BitSet> columns, @Nullable BitSet elements,
            int @Nullable [] offsets, int broadcastSize)
    {
        this.rowCount = rowCount;
        this.columns = columns;
        this.elements = elements;
        this.offsets = offsets;
        this.broadcastSize = broadcastSize;
    }


    /**
     * The columns form: element {@code k} of every row is bit {@code row} of {@code columns[k]}.
     *
     * @param rowCount
     *            the rows the vector spans
     * @param columns
     *            the K answers, one bit set over the rows per element vector (copied as a list; the
     *            sets themselves are read only)
     * @return the vector
     */
    public static BoolListVector ofColumns(int rowCount, List<BitSet> columns)
    {
        return new BoolListVector(rowCount, List.copyOf(columns), null, null, -1);
    }


    /**
     * The flattened form: row {@code r}'s elements are bits {@code [offsets[r], offsets[r + 1])} of
     * {@code elements}.
     *
     * @param rowCount
     *            the rows the vector spans
     * @param offsets
     *            the row offsets, {@code rowCount + 1} entries, non-decreasing (read only)
     * @param elements
     *            the answers over the element range (read only)
     * @return the vector
     */
    public static BoolListVector flattened(int rowCount, int[] offsets, BitSet elements)
    {
        if (offsets.length != rowCount + 1)
        {
            throw new IllegalArgumentException("offsets must have rowCount + 1 entries, not "
                    + offsets.length + " for " + rowCount + " rows");
        }
        return new BoolListVector(rowCount, null, elements, offsets, -1);
    }


    /**
     * The broadcast form: the same K answers on every row.
     *
     * @param rowCount
     *            the rows the vector spans
     * @param size
     *            K, the list's cardinality
     * @param elements
     *            the K answers (read only)
     * @return the vector
     */
    public static BoolListVector broadcast(int rowCount, int size, BitSet elements)
    {
        return new BoolListVector(rowCount, null, elements, null, size);
    }


    /** The rows the vector spans. */
    public int rowCount()
    {
        return rowCount;
    }


    /** Whether this is the broadcast form — one list for the dataset (P8). */
    public boolean isBroadcast()
    {
        return broadcastSize >= 0;
    }


    /**
     * The quantifier over every row's list: {@code any} — some element true; {@code all} — every
     * element true (an empty list: true); {@code none} — no element true (an empty list: true).
     *
     * @param quantifier
     *            the quantifier
     * @return a new set over {@code [0, rowCount)}: the rows whose list satisfies it
     */
    public BitSet reduce(JoinedRowFold.Quantifier quantifier)
    {
        return reduce(quantifier, rowCount);
    }


    /**
     * {@link #reduce(JoinedRowFold.Quantifier)} over the caller's run: the broadcast form answers
     * any run (one list for the dataset, however the reader chunks its rows — a dataset-level
     * binding is evaluated over the whole table while its reader may run a range); the per-row
     * forms span exactly their own rows.
     *
     * @param quantifier
     *            the quantifier
     * @param rows
     *            the rows of the reading run
     * @return a new set over {@code [0, rows)}
     */
    public BitSet reduce(JoinedRowFold.Quantifier quantifier, int rows)
    {
        if (!isBroadcast() && rows != rowCount)
        {
            // W1a fold (R5 review B-LOW-1): the rule's ERROR (D35), never an engine crash
            throw new net.cumba.corej.core.expr.RuleDefinitionException(
                    "a per-row list<boolean> over " + rowCount + " rows cannot answer a run of "
                            + rows
                            + " rows — quantify it where it is computed, or bind a dataset-level list");
        }
        BitSet out = new BitSet(rows);
        if (columns != null)
        {
            reduceColumns(quantifier, columns, out);
        }
        else if (offsets != null)
        {
            reduceFlattened(quantifier, java.util.Objects.requireNonNull(elements), offsets, out);
        }
        else
        {
            BitSet bits = java.util.Objects.requireNonNull(elements);
            boolean holds = switch (quantifier)
            {
            case ANY -> !bits.isEmpty();
            case ALL -> bits.cardinality() == broadcastSize;
            case NONE -> bits.isEmpty();
            };
            if (holds)
            {
                out.set(0, rows);
            }
        }
        return out;
    }


    private void reduceColumns(JoinedRowFold.Quantifier quantifier, List<BitSet> sets, BitSet out)
    {
        switch (quantifier)
        {
        case ANY ->
        {
            for (BitSet column : sets)
            {
                out.or(column);
            }
        }
        case ALL ->
        {
            // all([]) is true: K = 0 sets every row
            out.set(0, rowCount);
            for (BitSet column : sets)
            {
                out.and(column);
            }
        }
        case NONE ->
        {
            for (BitSet column : sets)
            {
                out.or(column);
            }
            out.flip(0, rowCount);
        }
        }
    }


    private void reduceFlattened(JoinedRowFold.Quantifier quantifier, BitSet bits, int[] off,
            BitSet out)
    {
        for (int row = 0; row < rowCount; row++)
        {
            int from = off[row];
            int to = off[row + 1];
            boolean holds = switch (quantifier)
            {
            case ANY -> anyIn(bits, from, to);
            case ALL -> bits.nextClearBit(from) >= to;
            case NONE -> !anyIn(bits, from, to);
            };
            if (holds)
            {
                out.set(row);
            }
        }
    }


    private static boolean anyIn(BitSet bits, int from, int to)
    {
        int next = bits.nextSetBit(from);
        return next >= 0 && next < to;
    }


    /**
     * The P4 refusal of an element-wise predicate over a list in a <b>condition</b> position, or
     * anywhere a {@code list<boolean>} is not a quantifier's argument (Q3): stage A's finding, the
     * compiler's rule ERROR for the shape only an evaluation shows.
     *
     * @param function
     *            the predicate's name
     * @return the sentence
     */
    public static String quantifyHint(String function)
    {
        return function + "(…) over a list answers one boolean per element (a list<boolean>), which"
                + " is not a condition: quantify it with any(…), all(…) or none(…), or bind it"
                + " ($b: " + function + "(…)) and quantify the binding";
    }


    /**
     * The Q3 refusal of a {@code list<boolean>} binding read anywhere but as a quantifier's
     * argument — compared, a membership operand, a function argument, reported.
     *
     * @param binding
     *            the {@code $}-name
     * @return the sentence
     */
    public static String bindingHint(String binding)
    {
        return binding + " holds a list<boolean> (one answer per element), which is read only by a"
                + " quantifier: any(" + binding + "), all(" + binding + ") or none(" + binding
                + ")";
    }


    /**
     * The P5 refusal of a quantifier argument that is not a predicate's answer — the cells of
     * several columns, a string list, a value function's list, a comparison.
     *
     * @param quantifier
     *            the quantifier's name
     * @return the sentence
     */
    public static String quantifierArgumentHint(String quantifier)
    {
        return quantifier + "(…) takes one argument: joined(<condition>), an element-wise predicate"
                + " over a list (is_valid_date(values([X, Y]))) or a $-binding holding such a"
                + " predicate's answer";
    }


    /**
     * The P5 rule ERROR for a {@code $} binding whose evaluated vector is neither a
     * {@code list<boolean>} nor a condition's per-row answer (D35: written wrong ⇒ ERROR, never a
     * guess over cells).
     *
     * @param quantifier
     *            the quantifier's name
     * @param binding
     *            the {@code $}-name
     * @param what
     *            what the binding held instead
     * @return the sentence
     */
    public static String boundArgumentError(String quantifier, String binding, String what)
    {
        return quantifier + "(" + binding + "): the binding " + binding + " holds " + what
                + ", not a predicate's answer (a list<boolean>, or a condition's true / false per"
                + " row) — a quantifier folds two-valued answers only";
    }


    @Override
    public TypedValue value(int row)
    {
        if (broadcastSize >= 0)
        {
            TypedValue cached = broadcastValue;
            if (cached == null)
            {
                cached = TypedValue.resolved(DataValueType.BOOLEAN,
                        booleans(java.util.Objects.requireNonNull(elements), 0, broadcastSize));
                broadcastValue = cached;
            }
            return cached;
        }
        if (columns != null)
        {
            List<Boolean> out = new ArrayList<>(columns.size());
            for (BitSet column : columns)
            {
                out.add(column.get(row));
            }
            return TypedValue.resolved(DataValueType.BOOLEAN, List.copyOf(out));
        }
        int[] off = java.util.Objects.requireNonNull(offsets);
        return TypedValue.resolved(DataValueType.BOOLEAN,
                booleans(java.util.Objects.requireNonNull(elements), off[row], off[row + 1]));
    }


    private static List<Boolean> booleans(BitSet bits, int from, int to)
    {
        List<Boolean> out = new ArrayList<>(to - from);
        for (int i = from; i < to; i++)
        {
            out.add(bits.get(i));
        }
        return List.copyOf(out);
    }


    @Override
    public DataValueType declaredType()
    {
        return DataValueType.BOOLEAN;
    }

}
