package net.cumba.corej.core.exec;

import java.util.List;
import net.cumba.corej.core.expr.eval.IsoDateBounds;
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
 * <li>any other key — <b>ISO, then text</b>: a cell {@link IsoDateBounds} can position — a
 * four-digit cell read as a year ({@code "2021"} after {@code "2020-06-15"}) — by (lower bound,
 * upper bound, text), so a partial precedes the complete values it contains, a date precedes its
 * datetimes, an interval follows its start and an offset is applied instant-preserving; then every
 * other text ({@code UNK}, an invalid date, a number in a mixed key) by text;</li>
 * <li>then the blank {@code ""}, then a <b>missing</b> — the marker's own order
 * ({@link MissingValue}), so two missings of one kind are equal and two kinds are ordered.</li>
 * </ul>
 * <p>
 * Judged cell by cell, {@code "2021"} was a number and sorted before every date of its key.
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
 * ⭐ <b>Decorated once</b> (R5 A-M2; owner priority: performance first): {@link #key} reads each
 * cell's class, number or bounds ONCE into arrays, so a comparison during the sort is array reads —
 * never the half-dozen bound parses a per-comparison reading cost. {@code is_increasing}'s
 * list-level rule (numeric iff every element is numeric, else text) is {@link #compareNumeric} /
 * {@link #compareText} over the whole list ({@link OrderedLists}).
 * </p>
 */
public final class CellOrder
{

    /** The class of a number in a numeric key. */
    private static final byte NUMBER = 0;

    /** The class of an ISO-positionable cell in a non-numeric key. */
    private static final byte ISO = 1;

    /** The class of any other present text. */
    private static final byte TEXT = 2;

    /** The class of the blank {@code ""}. */
    private static final byte BLANK = 3;

    /** The class of a missing. */
    private static final byte MISSING = 4;

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
        int n = values.size();
        boolean numeric = true;
        for (int i = 0; i < n && numeric; i++)
        {
            Object value = values.get(i);
            if (!isMissing(value) && !text(value).isEmpty())
            {
                numeric = value instanceof Number num ? Double.isFinite(num.doubleValue())
                        : !Double.isNaN(parse(text(value)));
            }
        }
        byte[] rank = new byte[n];
        double[] number = new double[n];
        @Nullable
        String[] lower = new String[n];
        @Nullable
        String[] upper = new String[n];
        String[] text = new String[n];
        @Nullable
        MissingValue[] marker = new MissingValue[n];
        for (int i = 0; i < n; i++)
        {
            Object value = values.get(i);
            if (isMissing(value))
            {
                rank[i] = MISSING;
                marker[i] = markerOf(value);
                text[i] = "";
                continue;
            }
            String t = text(value);
            text[i] = t;
            if (t.isEmpty())
            {
                rank[i] = BLANK;
            }
            else if (numeric)
            {
                rank[i] = NUMBER;
                number[i] = value instanceof Number num ? num.doubleValue() : parse(t);
            }
            else
            {
                String low = IsoDateBounds.lower(t);
                if (low != null)
                {
                    rank[i] = ISO;
                    lower[i] = low;
                    upper[i] = IsoDateBounds.upper(t);
                }
                else
                {
                    rank[i] = TEXT;
                }
            }
        }
        return new Key(numeric, rank, number, lower, upper, text, marker);
    }

    /**
     * A sort key decorated once ({@link #key}): its classification and, per position, the class,
     * the number or the ISO bounds, the text and the missing marker — compared by array reads.
     */
    public static final class Key
    {

        private final boolean numeric;

        private final byte[] rank;

        private final double[] number;

        private final @Nullable String[] lower;

        private final @Nullable String[] upper;

        private final String[] text;

        private final @Nullable MissingValue[] marker;

        private Key(boolean aNumeric, byte[] aRank, double[] aNumber, @Nullable String[] aLower,
                @Nullable String[] aUpper, String[] aText, @Nullable MissingValue[] aMarker)
        {
            numeric = aNumeric;
            rank = aRank;
            number = aNumber;
            lower = aLower;
            upper = aUpper;
            text = aText;
            marker = aMarker;
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
            return rank.length;
        }


        /**
         * Whether the cell at position {@code i} is blank ({@code ""}) or missing — what a
         * {@code nulls=} placement places ({@link SortKeys#sortRowsPlacingNulls}).
         *
         * @param i
         *            a position
         * @return {@code true} for a blank or missing cell
         */
        public boolean blankOrMissing(int i)
        {
            return rank[i] >= BLANK;
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
            int c = Byte.compare(rank[i], rank[j]);
            if (c != 0)
            {
                return c;
            }
            if (rank[i] == MISSING)
            {
                return compareMarkers(marker[i], marker[j]);
            }
            if (rank[i] == NUMBER)
            {
                c = Double.compare(number[i], number[j]);
            }
            else if (rank[i] == ISO)
            {
                c = String.valueOf(lower[i]).compareTo(String.valueOf(lower[j]));
                if (c == 0)
                {
                    c = String.valueOf(upper[i]).compareTo(String.valueOf(upper[j]));
                }
            }
            return c != 0 ? c : text[i].compareTo(text[j]);
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
