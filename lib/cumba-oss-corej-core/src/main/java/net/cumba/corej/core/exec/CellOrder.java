package net.cumba.corej.core.exec;

import java.util.List;
import net.cumba.corej.core.expr.eval.Primitives;
import net.cumba.corej.core.expr.eval.TypedValue;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;

/**
 * Runbook W1b RO ({@code PLAN-engine-gap-row-ordinal} §5 R1, ruled Q3; coordinator D11 / D19; owner
 * O22) — the ONE reusable ordering over cell values: {@code sequence}'s sort keys,
 * {@code is_increasing} and, since runbook W2 SB2, {@code is_sorted_by}'s sort keys read it.
 *
 * <p>
 * ⭐ <b>A sort key is classified over its cells</b> (owner O22 (a), 2026-10-08; runbook W1b fold, R5
 * A-M1) with D11's rule, once per key ({@link #key}):
 * </p>
 * <ul>
 * <li>a <b>numeric key</b> — every present cell a number (a numeric cell, or a text that parses as
 * a finite number: {@code "10"}, {@code "-0.5"}): by value, then by text ({@code "1"} before
 * {@code "1.0"});</li>
 * <li>any other key — <b>ISO, then text</b>: a cell this order can place on the calendar — a
 * complete, partial or masked ISO 8601 date / datetime ({@code 2012---15}, {@code 2012-06--},
 * {@code ----06-15}), an interval by its start, a four-digit cell read as a year ({@code "2021"}
 * after {@code "2020-06-15"}) — ⭐ <b>part by part</b> (owner O24, 2026-10-09, superseding O23's
 * (lower bound, text)): year, month, day, hour, minute, second, millisecond, a <b>missing part
 * below every present value of that part</b> — {@code 2012} &lt; {@code 2012---15} &lt;
 * {@code 2012-01-01}; {@code 2015} &lt; {@code 2015-01} &lt; {@code 2015-01-01} &lt;
 * {@code 2015-01-01T08}; {@code ----06-15} before every dated value. A UTC offset is applied FIRST,
 * where the value has a time to shift ({@code T10:00+02:00} before {@code T09:00Z}); a date without
 * a time ignores it. Equal parts fall back to the cell's text. Then every other text — an invalid
 * date ({@code 2020-02-30}), {@code 2015-UN-UN}, {@code "2020-01-15 08:00"}, {@code UNK}, a number
 * in a mixed key — by text, after every dated cell (O23 #2);</li>
 * <li>then the blank — {@code ""} and a whitespace-only cell (O24 #2), placed by {@code nulls=}
 * where a sort key is read ({@link SortKeys#sortRowsPlacingNulls}) — then a <b>missing</b> — the
 * marker's own order ({@link MissingValue}), so two missings of one kind are equal and two kinds
 * are ordered.</li>
 * </ul>
 * <p>
 * Judged cell by cell, {@code "2021"} was a number and sorted before every date of its key. Until
 * O23 the ISO tuple was (lower bound, upper bound, text), which put {@code 2015-01-01} before
 * {@code 2015}; under O23 it was (lower bound, text), which put {@code 2012---15} after
 * {@code 2012-01-01} and left a year-masked value unplaced.
 * </p>
 *
 * <p>
 * ⚠ It is a <b>total order</b>, by construction — which the engine's ISO relations are not:
 * {@code ScalarSemantics.compareIso} truncates to the shorter precision ({@code 2020-01-15} equals
 * both {@code 2020-01-15T08:00} and {@code T10:00}, not transitive), and the hull comparison of
 * {@code IsoDateComparison} leaves a partial against a complete value unordered. Under such a
 * comparator {@code TimSort} throws <i>"Comparison method violates its general contract"</i> on a
 * group of 32 rows or more. Here every cell of a key falls in exactly one class, the classes are
 * ordered, and inside a class the order is lexicographic over a tuple that ends in the cell's own
 * text — so two cells compare equal only when they are the same text.
 * </p>
 *
 * <p>
 * ⭐ <b>Decorated once, one packed {@code long} per cell</b> (R5 A-M2; owner priority: performance
 * and memory first — a 10 M-row datetime column with offsets): {@link #key} reads each cell ONCE
 * into {@code packed[i]} — the class in the top bits, then for an ISO cell its UTC-normalised parts
 * as (value + 1, 0 = missing): year 15 bits, month 4, day 5, hour 5, minute 6, second 6,
 * millisecond 10 — so a plain {@code long} comparison is the part-by-part order; a numeric key
 * keeps one {@code double} per cell beside it. A comparison is array reads; only a tie re-reads the
 * cell's text from its source (no retained string copy). Memory: 8 B per row (16 B for a numeric
 * key), plus the caller's 4 B row index. {@code is_increasing}'s list-level rule (numeric iff every
 * element is numeric, else text) is {@link #compareNumeric} / {@link #compareText} over the whole
 * list ({@link OrderedLists}).
 * </p>
 */
public final class CellOrder
{

    /** The class of a number in a numeric key. */
    private static final long NUMBER = 0;

    /** The class of an ISO-placeable cell in a non-numeric key. */
    private static final long ISO = 1;

    /** The class of any other present text. */
    private static final long TEXT = 2;

    /** The class of a blank cell: {@code ""} or whitespace only. */
    private static final long BLANK = 3;

    /** The class of a missing. */
    private static final long MISSING = 4;

    /** Where the class sits in a packed key: above the 51 bits of ISO parts. */
    private static final int CLASS_SHIFT = 56;

    private CellOrder()
    {
    }


    /**
     * The value of a table cell as this order compares it: a numeric cell its number, a missing
     * cell its {@link Primitives.MissingMember}, else its text.
     *
     * @param cell
     *            the cell
     * @return the comparable value
     */
    public static Object valueOf(IDataValue cell)
    {
        MissingValue missing = TypedValue.missingIdentityOf(cell);
        if (missing != null)
        {
            return new Primitives.MissingMember(missing);
        }
        if (cell.isMissingOrInvalid())
        {
            return new Primitives.MissingMember(MissingValue.MIS);
        }
        net.cumba.datatable.values.DataValueType type = cell.getType();
        if (type == net.cumba.datatable.values.DataValueType.LONG
                || type == net.cumba.datatable.values.DataValueType.DOUBLE)
        {
            Object raw = cell.getValue();
            if (raw instanceof Number n)
            {
                return n;
            }
        }
        String text = cell.getValueAsString();
        return text == null ? "" : text;
    }


    /**
     * One sort key's cells classified over the key and decorated once ({@link CellOrder}'s rule):
     * positions {@code 0..n-1} are the values in the order given.
     *
     * @param values
     *            the key's values ({@link #valueOf}: a {@link Number}, a {@link String}, a
     *            {@link Primitives.MissingMember}; {@code null} is a missing)
     * @return the decorated key
     */
    public static Key key(List<?> values)
    {
        return decorate(values.size(), values::get);
    }


    /**
     * One sort key read straight from a table column at {@code rows} — each cell read once to
     * decorate it, and again only to break a tie on its text (no list of values, no string copy
     * retained).
     *
     * @param column
     *            the key's column
     * @param rows
     *            the rows, position {@code i} is row {@code rows[i]} (not copied, not modified)
     * @return the decorated key
     */
    public static Key key(net.cumba.datatable.IDataTableColumn column, int[] rows)
    {
        return decorate(rows.length, i -> valueOf(column.getDataValue(rows[i])));
    }


    private static Key decorate(int n, java.util.function.IntFunction<@Nullable Object> valueAt)
    {
        long[] packed = new long[n];
        double[] number = new double[n];
        boolean numeric = true;
        for (int i = 0; i < n; i++)
        {
            Object value = valueAt.apply(i);
            if (isMissing(value))
            {
                packed[i] = MISSING << CLASS_SHIFT; // the marker is read on a tie
                continue;
            }
            String t = text(value);
            if (t.isBlank())
            {
                packed[i] = BLANK << CLASS_SHIFT;
                continue;
            }
            double d = value instanceof Number num
                    ? Double.isFinite(num.doubleValue()) ? num.doubleValue() : Double.NaN
                    : parse(t);
            number[i] = d;
            numeric &= !Double.isNaN(d);
            long parts = IsoParts.pack(t);
            packed[i] = parts >= 0 ? (ISO << CLASS_SHIFT) | parts : TEXT << CLASS_SHIFT;
        }
        if (!numeric)
        {
            return new Key(false, packed, null, valueAt);
        }
        for (int i = 0; i < n; i++)
        {
            if (packed[i] >>> CLASS_SHIFT < BLANK)
            {
                packed[i] = NUMBER << CLASS_SHIFT;
            }
        }
        return new Key(true, packed, number, valueAt);
    }

    /**
     * A sort key decorated once ({@link #key}): its classification and, per position, the packed
     * class and ISO parts, the number of a numeric key — compared by array reads; a tie reads the
     * two cells' text from the key's source.
     */
    public static final class Key
    {

        private final boolean numeric;

        private final long[] packed;

        private final double @Nullable [] number;

        private final java.util.function.IntFunction<@Nullable Object> valueAt;

        private Key(boolean aNumeric, long[] aPacked, double @Nullable [] aNumber,
                java.util.function.IntFunction<@Nullable Object> aValueAt)
        {
            numeric = aNumeric;
            packed = aPacked;
            number = aNumber;
            valueAt = aValueAt;
        }


        /**
         * Whether the key is numeric (every present cell a number).
         *
         * @return {@code true} for a numeric key
         */
        public boolean numeric()
        {
            return numeric;
        }


        /**
         * The number of positions.
         *
         * @return the size
         */
        public int size()
        {
            return packed.length;
        }


        /**
         * Whether the cell at position {@code i} is blank ({@code ""}, whitespace only) or missing
         * — what a {@code nulls=} placement places ({@link SortKeys#sortRowsPlacingNulls}).
         *
         * @param i
         *            a position
         * @return {@code true} for a blank or missing cell
         */
        public boolean blankOrMissing(int i)
        {
            return packed[i] >>> CLASS_SHIFT >= BLANK;
        }


        /**
         * The total order over two positions of this key.
         *
         * @param i
         *            one position
         * @param j
         *            the other
         * @return negative, zero or positive as position {@code i} precedes, equals or follows
         *         {@code j}
         */
        public int compare(int i, int j)
        {
            int c = Long.compare(packed[i], packed[j]);
            if (c != 0)
            {
                return c;
            }
            long rank = packed[i] >>> CLASS_SHIFT;
            if (rank == MISSING)
            {
                // two missings: by their marker's declared order, re-read from the source
                return compareMarkers(markerOf(valueAt.apply(i)), markerOf(valueAt.apply(j)));
            }
            if (rank == NUMBER && number != null)
            {
                c = Double.compare(number[i], number[j]);
                if (c != 0)
                {
                    return c;
                }
            }
            // O24: equal parts (or class) fall back to the text, so the order stays total
            return text(valueAt.apply(i)).compareTo(text(valueAt.apply(j)));
        }
    }

    /**
     * Two values as numbers (both {@link #isNumeric}): by value, then by text — total.
     *
     * @param a
     *            one numeric value
     * @param b
     *            the other
     * @return the order
     */
    public static int compareNumeric(@Nullable Object a, @Nullable Object b)
    {
        int c = Double.compare(number(a), number(b));
        return c != 0 ? c : text(a).compareTo(text(b));
    }


    /**
     * Two present values as text (a number its canonical text), a missing after every text.
     *
     * @param a
     *            one value
     * @param b
     *            the other
     * @return the order
     */
    public static int compareText(@Nullable Object a, @Nullable Object b)
    {
        boolean ma = isMissing(a);
        boolean mb = isMissing(b);
        if (ma || mb)
        {
            return ma && mb ? compareMissing(a, b) : ma ? 1 : -1;
        }
        return text(a).compareTo(text(b));
    }


    /**
     * Whether {@code value} is a number for this order: a {@link Number}, or a text that parses as
     * a finite number.
     *
     * @param value
     *            the value
     * @return {@code true} for a number
     */
    public static boolean isNumeric(@Nullable Object value)
    {
        if (isMissing(value))
        {
            return false;
        }
        if (value instanceof Number n)
        {
            return Double.isFinite(n.doubleValue());
        }
        return !Double.isNaN(parse(text(value)));
    }


    /** Whether {@code value} is a missing (a marker or {@code null}). */
    public static boolean isMissing(@Nullable Object value)
    {
        return value == null || value instanceof Primitives.MissingMember
                || Primitives.MemberSet.missingIdentityOfMember(value) != null;
    }


    private static double number(@Nullable Object value)
    {
        return value instanceof Number n ? n.doubleValue() : parse(text(value));
    }


    /** The finite number {@code s} spells, or {@code NaN} when it spells none. */
    private static double parse(String s)
    {
        if (s.isEmpty())
        {
            return Double.NaN;
        }
        char first = s.charAt(0);
        char last = s.charAt(s.length() - 1);
        // Double.parseDouble trims whitespace and admits type suffixes ("1d", "0x1p3", "NaN",
        // "Infinity"): a number here is digits, a sign, a point and an exponent, nothing else
        if (!(Character.isDigit(first) || first == '-' || first == '+' || first == '.')
                || !(Character.isDigit(last) || last == '.'))
        {
            return Double.NaN;
        }
        try
        {
            double d = Double.parseDouble(s);
            return Double.isFinite(d) ? d : Double.NaN;
        }
        catch (NumberFormatException _)
        {
            return Double.NaN;
        }
    }


    /** Two missings: by their marker's declared order (a {@code null} before every marker). */
    private static int compareMissing(@Nullable Object a, @Nullable Object b)
    {
        return compareMarkers(markerOf(a), markerOf(b));
    }


    private static int compareMarkers(@Nullable MissingValue ma, @Nullable MissingValue mb)
    {
        if (ma == null || mb == null)
        {
            return ma == mb ? 0 : ma == null ? -1 : 1;
        }
        return ma.compareTo(mb);
    }


    private static @Nullable MissingValue markerOf(@Nullable Object value)
    {
        return value instanceof Primitives.MissingMember m ? m.marker()
                : Primitives.MemberSet.missingIdentityOfMember(value);
    }


    private static String text(@Nullable Object value)
    {
        if (value instanceof Number n)
        {
            return net.cumba.corej.core.expr.eval.ExprCompiler.canonicalNumberText(n);
        }
        return value == null ? "" : value.toString();
    }
}
