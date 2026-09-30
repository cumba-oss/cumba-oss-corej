package net.cumba.corej.core.expr.eval;

import java.util.ArrayList;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Locale;
import net.cumba.corej.core.exec.ScalarSemantics;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.datatable.values.DataValueSupport;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;

/**
 * The text conversions of {@code PLAN-dynamic-column-functions} §2.1 / §2.2 — {@code str(x)},
 * {@code printf(format, args…)} and {@code lpad(x, width, fill)} — as ordinary registered
 * functions, each with one evaluation.
 *
 * <p>
 * ⭐ <b>{@code str}'s rendering is the one the engine already compares with</b> — a cell's own TEXT
 * ({@link IDataValue#getValueAsString()}: a DOUBLE cell's cleaned plain text, NCL D2 /
 * {@code DataValueSupport.toCleanText}; a LONG's exact digits; a character value verbatim) and a
 * computed number's plain notation ({@link ExprCompiler#canonicalNumberText}: no {@code .0} on an
 * integral value, never scientific, a LONG above 2^53 exact). That is exactly what the retired
 * {@code str(A) == str(B)} comparison marker compared ({@code Primitives.equality}'s
 * type-insensitive fold, {@code targetString}), so {@code str(A) == str(B)} as an ordinary string
 * equality answers as the marker did (owner Q8, Q10). A missing value in gives that missing value
 * out (D36, D85c).
 * </p>
 *
 * <p>
 * ⭐ <b>{@code printf} brings no locale and no second format language</b> (§2.2): it runs under
 * {@link Locale#ROOT} always, accepts only {@code %s %d %f %e %x %%} with the flags
 * {@code 0 - + space}, width and precision, and its format is a string literal checked at load
 * ({@link #validateCall}). {@code %d} / {@code %x} take an <b>integral</b> number — a non-integral
 * value or a present non-number yields a computed missing, never the {@code (long) d} truncation
 * {@code ${VAR:%02d}} keeps (Q1 applies when the respelling plan moves those tokens). A DOUBLE is
 * read through the numeric cleaning, as its text is (NCL D2), so SAS noise on an integral period
 * does not read as non-integral.
 * </p>
 */
public final class TextFunctions
{

    /** The function names. */
    public static final String STR = "str";

    /** {@code printf}. */
    public static final String PRINTF = "printf";

    /** {@code lpad}. */
    public static final String LPAD = "lpad";

    private TextFunctions()
    {
    }

    // ------------------------------------------------------------------
    // str
    // ------------------------------------------------------------------


    /**
     * {@code str(x)} over a vector.
     *
     * @param rowCount
     *            the run's row count
     * @param x
     *            the operand
     * @return the per-row text; a missing row is that missing
     */
    public static Vector str(int rowCount, Vector x)
    {
        return new ComputedVector(rowCount, DataValueType.STRING, row ->
        {
            TypedValue tv = x.value(row);
            if (tv.missing() != null)
            {
                return tv.cell();
            }
            String text = text(tv);
            return text != null ? text : ScalarSemantics.computedMissing();
        });
    }


    /**
     * The text of a present value — the rendering {@code str} publishes, or {@code null} when the
     * value has no scalar text (a list).
     *
     * @param tv
     *            a present value
     * @return its text, or {@code null}
     */
    static @Nullable String text(TypedValue tv)
    {
        IDataValue cell = tv.sourceCell();
        if (cell != null)
        {
            return cell.getValueAsString();
        }
        Object payload = tv.resolved();
        if (payload instanceof Number n)
        {
            return ExprCompiler.canonicalNumberText(n);
        }
        if (payload instanceof java.util.Collection<?>)
        {
            return null;
        }
        return String.valueOf(payload);
    }

    // ------------------------------------------------------------------
    // lpad
    // ------------------------------------------------------------------


    /**
     * {@code lpad(x, width, fill)}: {@code x} left-padded with {@code fill} to {@code width}
     * characters; a longer {@code x} is returned unchanged, never truncated; a missing {@code x} is
     * that missing. {@code width} and {@code fill} are read per row; a missing, negative or
     * non-integral {@code width}, or a {@code fill} that is not exactly one character, answers the
     * computed missing (a data problem, never an error — D35).
     *
     * @param rowCount
     *            the run's row count
     * @param x
     *            the text
     * @param width
     *            the target width
     * @param fill
     *            the one-character fill, or {@code null} for the default {@code " "}
     * @return the padded text
     */
    public static Vector lpad(int rowCount, Vector x, Vector width, @Nullable Vector fill)
    {
        return new ComputedVector(rowCount, DataValueType.STRING, row ->
        {
            TypedValue tx = x.value(row);
            if (tx.missing() != null)
            {
                return tx.cell();
            }
            String s = text(tx);
            Long w = integral(width.value(row));
            String f = fill == null ? " " : oneChar(fill.value(row));
            if (s == null || w == null || w < 0 || f == null)
            {
                return ScalarSemantics.computedMissing();
            }
            int pad = (int) Math.min(Integer.MAX_VALUE, w) - s.codePointCount(0, s.length());
            return pad <= 0 ? s : f.repeat(pad) + s;
        });
    }


    private static @Nullable String oneChar(TypedValue tv)
    {
        if (tv.missing() != null)
        {
            return null;
        }
        String s = text(tv);
        return s != null && s.codePointCount(0, s.length()) == 1 ? s : null;
    }

    // ------------------------------------------------------------------
    // printf
    // ------------------------------------------------------------------

    /** One conversion of a parsed format: its Java spec (e.g. {@code %02d}) and its letter. */
    record Conversion(String spec, char letter)
    {

        boolean numeric()
        {
            return letter != 's';
        }


        boolean integral()
        {
            return letter == 'd' || letter == 'x';
        }
    }


    /** A parsed format: the literal text pieces around the conversions. */
    record Format(List<String> literals, List<Conversion> conversions)
    {
    }

    /**
     * Parses and validates a {@code printf} format (the load-time half of §2.2): only
     * {@code %s %d %f %e %x %%}, the flags {@code 0 - + space}, a width and a precision; no
     * argument index ({@code %1$s}), no {@code %n}, no {@code %t…}. Each conversion is also
     * formatted once with a sample value under {@link Locale#ROOT}, so a flag combination Java
     * refuses ({@code %-05d}, a precision on {@code %d}) is refused here rather than per row.
     *
     * @param format
     *            the format literal
     * @return the parsed format
     * @throws IllegalArgumentException
     *             naming the offending conversion
     */
    static Format parse(String format)
    {
        List<String> literals = new ArrayList<>();
        List<Conversion> conversions = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int i = 0;
        while (i < format.length())
        {
            char ch = format.charAt(i);
            if (ch != '%')
            {
                text.append(ch);
                i++;
                continue;
            }
            if (i + 1 < format.length() && format.charAt(i + 1) == '%')
            {
                text.append('%');
                i += 2;
                continue;
            }
            int start = i++;
            while (i < format.length() && "0-+ ".indexOf(format.charAt(i)) >= 0)
            {
                i++;
            }
            while (i < format.length() && Character.isDigit(format.charAt(i)))
            {
                i++;
            }
            if (i < format.length() && format.charAt(i) == '.')
            {
                i++;
                int digits = i;
                while (i < format.length() && Character.isDigit(format.charAt(i)))
                {
                    i++;
                }
                if (i == digits)
                {
                    throw new IllegalArgumentException("printf format \"" + format
                            + "\": a precision needs digits after the '.' in "
                            + format.substring(start, Math.min(i + 1, format.length())));
                }
            }
            if (i >= format.length())
            {
                throw new IllegalArgumentException("printf format \"" + format
                        + "\" ends inside the conversion " + format.substring(start));
            }
            char letter = format.charAt(i++);
            String spec = format.substring(start, i);
            if ("sdfex".indexOf(letter) < 0)
            {
                throw new IllegalArgumentException("printf format \"" + format
                        + "\": unsupported conversion " + spec + " — printf accepts %s %d %f %e"
                        + " %x %% with the flags 0 - + space, a width and a precision (no"
                        + " argument index, no %n, no %t)");
            }
            Conversion conversion = new Conversion(spec, letter);
            try
            {
                try (java.util.Formatter probe = new java.util.Formatter(Locale.ROOT))
                {
                    probe.format(spec, sample(conversion));
                }
            }
            catch (IllegalFormatException bad)
            {
                IllegalArgumentException error = new IllegalArgumentException(
                        "printf format \"" + format + "\": the conversion " + spec
                                + " is not valid (" + bad.getMessage() + ")");
                error.initCause(bad);
                throw error;
            }
            literals.add(text.toString());
            text.setLength(0);
            conversions.add(conversion);
        }
        literals.add(text.toString());
        return new Format(List.copyOf(literals), List.copyOf(conversions));
    }


    private static Object sample(Conversion c)
    {
        return switch (c.letter())
        {
        case 'd', 'x' -> 0L;
        case 'f', 'e' -> 0.0d;
        default -> "";
        };
    }


    /**
     * The load-time check of a {@code printf} call's bound arguments (§3.1: a compiler load error
     * through the static-string seam, armed today): the format is a string literal, its conversions
     * are the supported subset, the argument count equals the conversion count, and a string
     * literal never stands at a numeric conversion.
     *
     * @param bound
     *            the bound arguments — the format first, then the values (collector form)
     * @throws IllegalArgumentException
     *             naming the defect
     */
    static void validateCall(List<? extends @Nullable Expr> bound)
    {
        Expr formatArg = bound.isEmpty() ? null : bound.get(0);
        if (!(formatArg instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.STRING))
        {
            throw new IllegalArgumentException("argument 'format' of 'printf' takes a string"
                    + " literal, not a computed value — a format error must fail at load, not per"
                    + " row");
        }
        Format format = parse(String.valueOf(lit.value()));
        int args = bound.size() - 1;
        if (args != format.conversions().size())
        {
            throw new IllegalArgumentException(
                    "printf format \"" + lit.value() + "\" has " + format.conversions().size()
                            + " conversion(s) but the call passes " + args + " argument(s)");
        }
        for (int k = 0; k < args; k++)
        {
            Conversion c = format.conversions().get(k);
            if (c.numeric() && bound.get(k + 1) instanceof Expr.Lit arg
                    && arg.kind() == Expr.LitKind.STRING)
            {
                throw new IllegalArgumentException("printf conversion " + c.spec()
                        + " takes a number, not the string literal \"" + arg.value() + "\"");
            }
        }
    }


    /**
     * {@code printf(format, args…)} over vectors.
     *
     * @param rowCount
     *            the run's row count
     * @param format
     *            the format (a string literal, validated at load; re-parsed here once per call)
     * @param args
     *            the values, one per conversion
     * @return the per-row text; a missing argument makes the row that missing (two distinct
     *         missings: MIS, D86a); a value a numeric conversion cannot take, the computed missing
     */
    public static Vector printf(int rowCount, Vector format, List<Vector> args)
    {
        TypedValue ft = format.value(0);
        String formatText = ft.missing() != null ? null : text(ft);
        if (formatText == null)
        {
            return new ComputedVector(rowCount, DataValueType.STRING,
                    _ -> ScalarSemantics.computedMissing());
        }
        Format parsed = parse(formatText);
        if (parsed.conversions().size() != args.size())
        {
            throw new IllegalArgumentException(
                    "printf format \"" + formatText + "\" has " + parsed.conversions().size()
                            + " conversion(s) but the call passes " + args.size() + " argument(s)");
        }
        return new ComputedVector(rowCount, DataValueType.STRING,
                row -> formatRow(parsed, args, row));
    }


    private static Object formatRow(Format parsed, List<Vector> args, int row)
    {
        TypedValue[] values = new TypedValue[args.size()];
        MissingValue missing = null;
        IDataValue missingCell = null;
        boolean several = false;
        for (int k = 0; k < values.length; k++)
        {
            values[k] = args.get(k).value(row);
            MissingValue m = values[k].missing();
            if (m != null)
            {
                if (missing == null)
                {
                    missing = m;
                    missingCell = values[k].cell();
                }
                else if (missing != m)
                {
                    several = true;
                }
            }
        }
        if (missing != null)
        {
            // D36 / D85c: a missing argument makes the result that missing; D86a: two or more
            // distinct identities collapse to MIS.
            return several || missingCell == null ? ScalarSemantics.computedMissing() : missingCell;
        }
        StringBuilder out = new StringBuilder(parsed.literals().get(0));
        for (int k = 0; k < values.length; k++)
        {
            Conversion c = parsed.conversions().get(k);
            Object argument = argument(c, values[k]);
            if (argument == null)
            {
                return ScalarSemantics.computedMissing();
            }
            out.append(String.format(Locale.ROOT, c.spec(), argument));
            out.append(parsed.literals().get(k + 1));
        }
        return out.toString();
    }


    /** The Java argument for one conversion, or {@code null} when the value cannot take it. */
    private static @Nullable Object argument(Conversion c, TypedValue tv)
    {
        if (!c.numeric())
        {
            return text(tv);
        }
        // A present non-number (a character cell "3", a boolean) or, at %d / %x, a non-integral
        // number: no load check could see a column argument's value, so a data problem — the
        // computed missing (§2.2), never the (long) d truncation.
        if (c.integral())
        {
            return integral(tv);
        }
        return number(tv);
    }


    /** A present number's value, cleaned as its text is (NCL D2), or {@code null}. */
    private static @Nullable Double number(TypedValue tv)
    {
        IDataValue cell = tv.sourceCell();
        if (cell != null)
        {
            DataValueType t = cell.getType();
            if (t != DataValueType.LONG && t != DataValueType.DOUBLE)
            {
                return null;
            }
            double d = cell.getValueAsDouble();
            return Double.isNaN(d) ? null : DataValueSupport.getAsDoubleCleaned(d);
        }
        return tv.resolved() instanceof Number n
                ? DataValueSupport.getAsDoubleCleaned(n.doubleValue())
                : null;
    }


    /**
     * A present integral number as a {@code long} (a LONG exactly, beyond 2^53 included; a DOUBLE
     * through the cleaning), else {@code null}.
     */
    static @Nullable Long integral(TypedValue tv)
    {
        if (tv.missing() != null)
        {
            return null;
        }
        IDataValue cell = tv.sourceCell();
        Object raw = cell != null ? cell.getValue() : tv.resolved();
        if (cell != null && cell.getType() == DataValueType.LONG && raw instanceof Number n)
        {
            return n.longValue();
        }
        if (raw instanceof Long || raw instanceof Integer)
        {
            return ((Number) raw).longValue();
        }
        Double d = number(tv);
        if (d == null || Double.compare(d, Math.rint(d)) != 0 || d < -0x1p63 || d >= 0x1p63)
        {
            return null;
        }
        return d.longValue();
    }
}
