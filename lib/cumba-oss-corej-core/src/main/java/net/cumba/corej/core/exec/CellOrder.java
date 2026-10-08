package net.cumba.corej.core.exec;

import net.cumba.corej.core.expr.eval.IsoDateBounds;
import net.cumba.corej.core.expr.eval.Primitives;
import net.cumba.corej.core.expr.eval.TypedValue;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;

/**
 * Runbook W1b RO ({@code PLAN-engine-gap-row-ordinal} §5 R1, ruled Q3; coordinator D11 / D19) — the
 * ONE reusable ordering comparator over cell values: {@code sequence}'s sort keys and
 * {@code is_increasing} read it now, {@code is_sorted_by} adopts it in W2 (SB2).
 *
 * <p>
 * ⚠ It is a <b>total order</b>, by construction — which the engine's ISO relations are not:
 * {@code ScalarSemantics.compareIso} truncates to the shorter precision ({@code 2020-01-15} equals
 * both {@code 2020-01-15T08:00} and {@code T10:00}, not transitive), and the hull comparison of
 * {@code IsoDateComparison} leaves a partial against a complete value unordered. Under such a
 * comparator {@code TimSort} throws <i>"Comparison method violates its general contract"</i> on a
 * group of 32 rows or more. Here every cell falls in exactly one class, the classes are ordered,
 * and inside a class the order is lexicographic over a tuple that ends in the cell's own text — so
 * two cells compare equal only when they are the same text:
 * </p>
 * <ol>
 * <li><b>numbers</b> — a numeric cell, or a text that parses as a finite number ({@code "10"},
 * {@code "-0.5"}): by value, then by text ({@code "1"} before {@code "1.0"});</li>
 * <li><b>ISO dates and datetimes</b> — a text {@link IsoDateBounds} can position: by (lower bound,
 * upper bound, text), so a partial precedes the complete values it contains, a date precedes its
 * datetimes, an interval follows its start, and an offset is applied instant-preserving;</li>
 * <li><b>other text</b> — {@code UNK}, {@code NOT DONE}, an invalid date, a code: by text;</li>
 * <li><b>the blank {@code ""}</b>;</li>
 * <li><b>a missing</b> — the marker's own order ({@link MissingValue}), so two missings of one kind
 * are equal and two kinds are ordered.</li>
 * </ol>
 * <p>
 * The classes are judged per cell, never per column: a column mixing numbers and text orders its
 * numbers first — the {@code is_sorted_by} comparator's "numeric first, text fallback" made total.
 * {@code is_increasing}'s list-level rule (numeric iff every element is numeric, else text) is
 * {@link #compareNumeric} / {@link #compareText} over the whole list ({@link OrderedLists}).
 * </p>
 */
public final class CellOrder
{

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
     * The total order over two list elements / cell values ({@link #valueOf}): a {@link Number}, a
     * {@link String}, a {@link Primitives.MissingMember}; {@code null} is a missing.
     *
     * @param a
     *            one value
     * @param b
     *            the other
     * @return negative, zero or positive as {@code a} precedes, equals or follows {@code b}
     */
    public static int compare(@Nullable Object a, @Nullable Object b)
    {
        int ra = rank(a);
        int rb = rank(b);
        if (ra != rb)
        {
            return Integer.compare(ra, rb);
        }
        return switch (ra)
        {
        case 0 -> compareNumeric(a, b);
        case 1 -> compareIso(text(a), text(b));
        case 4 -> compareMissing(a, b);
        default -> text(a).compareTo(text(b));
        };
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
        return rank(value) == 0;
    }


    /** Whether {@code value} is a missing (a marker or {@code null}). */
    public static boolean isMissing(@Nullable Object value)
    {
        return value == null || value instanceof Primitives.MissingMember
                || Primitives.MemberSet.missingIdentityOfMember(value) != null;
    }


    private static int rank(@Nullable Object value)
    {
        if (isMissing(value))
        {
            return 4;
        }
        if (value instanceof Number n)
        {
            return Double.isFinite(n.doubleValue()) ? 0 : 2;
        }
        String s = text(value);
        if (s.isEmpty())
        {
            return 3;
        }
        if (!Double.isNaN(parse(s)))
        {
            return 0;
        }
        return IsoDateBounds.lower(s) != null ? 1 : 2;
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


    private static int compareIso(String a, String b)
    {
        int c = String.valueOf(IsoDateBounds.lower(a))
                .compareTo(String.valueOf(IsoDateBounds.lower(b)));
        if (c != 0)
        {
            return c;
        }
        c = String.valueOf(IsoDateBounds.upper(a))
                .compareTo(String.valueOf(IsoDateBounds.upper(b)));
        return c != 0 ? c : a.compareTo(b);
    }


    /** Two missings: by their marker's declared order (a {@code null} before every marker). */
    private static int compareMissing(@Nullable Object a, @Nullable Object b)
    {
        MissingValue ma = markerOf(a);
        MissingValue mb = markerOf(b);
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
