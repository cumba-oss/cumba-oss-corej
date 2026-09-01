package net.cumba.cdisc.core.exec;

import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.values.DataValueSupport;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * Shared, parity-critical scalar semantics for per-row operators.
 *
 * <p>
 * These are the pure, side-effect-free helpers that encode the subtle null/missing handling, the
 * ISO-vs-SAS date comparison rules (timezone normalization, precision truncation, {@code 1e-9}
 * epsilon), the structural ISO-8601 date-prefix validators, the integer check and the ISO-8601
 * duration grammar. They were extracted from {@link OperatorRegistry} so that <b>both</b> the
 * legacy operator engine and the native expression evaluator (package
 * {@code net.cumba.cdisc.core.expr.eval}) compute over the <i>same</i> code. Parity between the two
 * backends is therefore by construction, and the existing {@code OperatorRegistry*Test} suites
 * (which exercise the legacy engine that now delegates here) double as the parity proof for these
 * primitives.
 * </p>
 *
 * <p>
 * <b>Timezone contract (intentional, parity-whitelisted divergence from the Python CORE engine,
 * like {@code expr.eval.CalendarDates}).</b> Where Python plainly strips a trailing offset before
 * comparing, {@link #normalizeToUtc(String)} applies the offset instant-preserving and renders the
 * value in UTC at the input's precision: {@code 2024-03-15T13:30+02:00} equals
 * {@code 2024-03-15T11:30Z} — and does <i>not</i> equal {@code 2024-03-15T13:30Z}. Values without
 * an offset are assumed to already be UTC and compare byte-identically to the legacy behaviour;
 * unparseable offset-bearing values fall back to the legacy strip.
 * </p>
 *
 * <p>
 * This class is stateless and thread-safe; every method is a pure function of its arguments.
 * </p>
 */
public final class ScalarSemantics
{

    /**
     * Floating-point tolerance for numeric date/datetime comparisons. {@code 1e-9} is well below
     * millisecond precision (1ms = 1e-3 in *DTM seconds), and well above the resolution of
     * {@code double} for typical SAS reference dates.
     */
    public static final double DATE_EPSILON = 1e-9;

    /** Trailing ISO-8601 timezone offset to strip before precision detection. */
    private static final Pattern ISO_TZ_OFFSET = Pattern.compile("(Z|[+-]\\d{2}:?\\d{2})$");

    /**
     * UTC renderer at millisecond precision (the finest tier of {@link #detectIsoPrecision});
     * {@link #normalizeToUtc} substring-truncates its output back to the input's tier.
     */
    private static final DateTimeFormatter UTC_RENDER = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS", Locale.ROOT);

    /**
     * Bare time-of-day shape after offset stripping (review F7): optional leading {@code 'T'}, then
     * {@code HH:MM}, {@code HH:MM:SS} or {@code HH:MM:SS.fff} — no date part. Matched anchored
     * ({@code matches()}).
     */
    private static final Pattern BARE_TIME_SHAPE = Pattern
            .compile("T?\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d{1,3})?)?");

    /**
     * UTC renderer for bare times at millisecond precision; {@link #normalizeBareTimeToUtc}
     * substring-truncates its output back to the input's precision.
     */
    private static final DateTimeFormatter UTC_TIME_RENDER = DateTimeFormatter
            .ofPattern("HH:mm:ss.SSS", Locale.ROOT);

    // Ported verbatim from Python's is_valid_duration (helpers.py:78-113) — two branches that
    // differ only in the leading `[-]?`. Keep byte-for-byte parity with the Python oracle.
    private static final Pattern ISO_8601_DURATION_POSITIVE = Pattern
            .compile("^P(?!$)(?:(?:(\\d+(?:[.,]\\d*)?Y)?[,]?(\\d+(?:[.,]\\d*)?M)?[,]?"
                    + "(\\d+(?:[.,]\\d*)?D)?[,]?(T(?=\\d)(?:(\\d+(?:[.,]\\d*)?H)?[,]?"
                    + "(\\d+(?:[.,]\\d*)?M)?[,]?(\\d+(?:[.,]\\d*)?S)?)?)?)|(\\d+(?:[.,]\\d*)?W))$");

    private static final Pattern ISO_8601_DURATION_WITH_NEGATIVE = Pattern
            .compile("^[-]?P(?!$)(?:(?:(\\d+(?:[.,]\\d*)?Y)?[,]?(\\d+(?:[.,]\\d*)?M)?[,]?"
                    + "(\\d+(?:[.,]\\d*)?D)?[,]?(T(?=\\d)(?:(\\d+(?:[.,]\\d*)?H)?[,]?"
                    + "(\\d+(?:[.,]\\d*)?M)?[,]?(\\d+(?:[.,]\\d*)?S)?)?)?)|(\\d+(?:[.,]\\d*)?W))$");

    private ScalarSemantics()
    {
    }

    // -------------------------------------------------------------------------
    // Missing / empty
    // -------------------------------------------------------------------------


    /**
     * Returns {@code true} if the data value is missing, invalid, null, or an empty string. In
     * clinical data (especially SAS), character variables cannot be null — they are represented as
     * empty strings instead; a Dataset-JSON or Parquet character column, by contrast, <em>can</em>
     * carry a source {@code null}, which the loaders represent as a {@code MissingValue}. Both are
     * blank here, so a rule cannot tell them apart.
     * <p>
     * The predicate itself now lives on the datatable layer as
     * {@link IDataValue#isEmptyOrMissing()} (so {@code DataValueSupport} and the column
     * implementations can reach it, which they cannot do for a {@code corej-cdisc-core} class);
     * this method is the null-safe wrapper its callers already depend on.
     * </p>
     * <p>
     * ⚠ When you have the column and the row rather than an {@link IDataValue}, call
     * {@link net.cumba.datatable.IDataTableColumn#isEmptyOrMissing(long)} instead — it answers from
     * the raw stored value and allocates nothing.
     * </p>
     */
    public static boolean isMissing(IDataValue dv)
    {
        // ⚠ Deliberately DataValueSupport.isEmptyOrMissing(dv) and not dv.isEmptyOrMissing():
        // the latter is a default method on an interface the test suite mocks widely, and a
        // Mockito mock answers false to an unstubbed default instead of running it. See that
        // method's javadoc — routing at the default reddened several suites that have nothing to
        // do with blank cells.
        return DataValueSupport.isEmptyOrMissing(dv);
    }


    /**
     * <b>The engine-facing string form of a cell — the one place that decides how a blank cell
     * resolves.</b>
     *
     * <p>
     * A populated cell yields its string form; a blank cell yields {@code null}. This is exactly
     * the semantics every call site had inline before ({@code isMissingOrInvalid() ? null :
     * getValueAsString()}), gathered into one named, greppable place and given an allocation-free
     * fast path for the overwhelmingly common case of a populated character cell.
     * </p>
     *
     * <h4>⚠⚠ Why a blank character cell does NOT yet resolve to {@code ""}</h4>
     * <p>
     * The settled design wants the engine <em>blind</em> to the difference between a source
     * {@code null} and an empty string in a character column, which means a blank character cell
     * should resolve to {@code ""} — the direction the owner specified ("treat a
     * {@code MissingValue} in a char column like an empty string"). <b>That step is a separate
     * decision (Q5) with its own acceptance criterion; the {@code date_*} defect that used to block
     * it is FIXED (Q16).</b>
     * </p>
     * <p>
     * ⚠ History, kept because the stale form of this comment misled a ruling once. It used to claim
     * that {@code Primitives.dateComparison} runs the ISO comparison on a {@code ""} comparand and
     * that {@link #compareIso}'s min-precision truncation then makes <em>every</em>
     * {@code date_*_or_equal_to} against a blank fire. Neither half is true today.
     * {@code Primitives.dateComparison} short-circuits its <em>left</em> operand on
     * {@link #isMissing}, which treats {@code ""} as missing
     * ({@code DataValueSupport.isEmptyOrMissing}); and against a <em>non-numeric</em> left operand
     * a {@code ""} <em>comparand</em> reaches
     * {@link net.cumba.cdisc.core.expr.eval.IsoDateComparison#fires}, where
     * {@code IsoDateBounds.core("")} is {@code null}, the hull is unbounded, and all six operators
     * answer {@code false}. (Against a numeric-typed left operand a {@code ""} comparand parses to
     * no number and takes the mixed-numeric/ISO branch instead — <b>{@code true} for all six
     * operators</b>, the malformed-shape verdict.) {@code compareIso} no longer sees a raw operand
     * at all — since {@code Fix #250} the complete/complete fast path hands it the <b>cores</b>,
     * and nothing else reaches it.
     * </p>
     * <p>
     * ⚠ The Q5 flip is still not a no-op for {@code date_*}, for a narrower reason: a {@code null}
     * comparand returns {@code negate} (the absent-column contract — {@code date_not_equal_to}
     * FIRES on it), while a {@code ""} comparand saturates to {@code false} on character leaves —
     * and flips to <b>all six operators {@code true}</b> on numeric-typed leaves (the mixed-shape
     * branch above). Resolving blanks to {@code ""} would therefore still move
     * {@code date_not_equal_to} verdicts on character leaves and every operator on numeric ones, in
     * merged/joined columns too (see {@code PolymorphicMergedColumn.getDataValue}, which maps a
     * blank parent cell to the missing sentinel whatever the column's type) — which is why the step
     * still needs an owner decision rather than being a free rename.
     * </p>
     * <p>
     * ⚠ When that decision lands, the blank branch below becomes
     * {@code aDeclaredType == DataValueType.STRING ? "" : null} and nothing else here changes —
     * which is why {@code aDeclaredType} is already a parameter.
     * </p>
     *
     * <p>
     * ⚠ This is <b>not</b> for grouping-key components. There a {@code MissingValue} and a
     * {@code ""} are <em>distinct keys</em> with a shared disposition, so folding them would be
     * wrong — see {@link GroupKeyPolicy}.
     * </p>
     *
     * @param aColumn
     *            the column to read.
     * @param aDeclaredType
     *            the column's declared type, from its {@code DataTableColumnMeta}. Not consulted
     *            yet; see the blocked step above.
     * @param aRow
     *            the 0-based row index.
     * @return the cell's string form, or {@code null} when the cell is missing/invalid.
     */
    public static @Nullable String resolvedString(IDataTableColumn aColumn,
            DataValueType aDeclaredType, long aRow)
    {
        Object raw = aColumn.getValue(aRow);
        if (raw instanceof String s)
        {
            // A present character value — the common case, and the reason this reads the raw
            // stored object first: no IDataValue is allocated and no toString() runs.
            return s;
        }
        IDataValue dv = aColumn.getDataValue(aRow);
        if (dv.isMissingOrInvalid())
        {
            return null;
        }
        return dv.getValueAsString();
    }


    /**
     * {@link #resolvedString(IDataTableColumn, DataValueType, long)} for a column addressed by
     * index on a table, taking the declared type from the table's metadata.
     *
     * @param aTable
     *            the table to read.
     * @param aColumnIndex
     *            the 0-based column index; must be valid.
     * @param aRow
     *            the 0-based row index.
     * @return the cell's string form, or {@code null} for a blank cell in a non-character column.
     */
    public static @Nullable String resolvedString(IDataTable aTable, int aColumnIndex, long aRow)
    {
        return resolvedString(aTable.getColumn(aColumnIndex),
                aTable.getMetaData().getColumn(aColumnIndex).getType(), aRow);
    }

    // -------------------------------------------------------------------------
    // Equality
    // -------------------------------------------------------------------------


    /**
     * Value-equality used by {@code equal_to} / {@code not_equal_to}: numeric comparison when the
     * resolved {@code target} is a {@link Number} and the cell parses as a number, otherwise a
     * string comparison.
     */
    public static boolean valueEquals(IDataValue dv, @Nullable Object target)
    {
        if (target instanceof Number targetNum)
        {
            double dvDouble = dv.getValueAsDouble();
            if (!Double.isNaN(dvDouble))
            {
                return dvDouble == targetNum.doubleValue();
            }
        }
        // Latent-NPE guard: a null RHS compares equal only to a missing cell.
        if (target == null)
        {
            return dv.isMissingOrInvalid();
        }
        return dv.getValueAsString().equals(target.toString());
    }


    /**
     * Equality verdict with numeric mode (Phase 8). The comparison runs in <b>numeric mode</b> when
     * the cell is not missing, the comparison is neither case- nor type-insensitive, and at least
     * one operand is declared/known numeric — i.e. {@code forceNumeric} (the native-only
     * {@code num()} marker), the LHS is a numeric-typed cell ({@link DataValueType#LONG} /
     * {@link DataValueType#DOUBLE}), or the resolved {@code target} is a {@link Number}. In numeric
     * mode both sides are parsed ({@link IDataValue#getValueAsDouble()} for the LHS,
     * {@link #comparisonTargetAsDouble(Object)} for the RHS) and compared numerically <i>only when
     * both parse</i>; otherwise the verdict falls back to the literal textual fold (a missing /
     * empty / non-parseable side folds to {@code ""}), preserving the {@code equal_to} empty-string
     * semantics ({@code missing == missing} matches, {@code present != missing} fires,
     * {@code AGE != "abc"} fires textually).
     *
     * <p>
     * This is the single parity anchor for {@code ==}/{@code !=}: both the native evaluator
     * ({@code Primitives.equality}) routes through it, so the engines cannot drift. The trigger is
     * computable identically in both engines (each has the cell {@code dv} and the resolved
     * {@code target}); it deliberately does <b>not</b> consult the RHS vector's declared type,
     * which legacy cannot see.
     * </p>
     *
     * @return {@code true} when the cell equals the target under this verdict
     */
    public static boolean equalsNumericAware(IDataValue dv, @Nullable Object target,
            boolean dvMissing, boolean caseInsensitive, boolean typeInsensitive,
            boolean forceNumeric)
    {
        if (!caseInsensitive && !typeInsensitive && !dvMissing
                && (forceNumeric || isNumericType(dv) || target instanceof Number))
        {
            double lhsD = dv.getValueAsDouble();
            Double rhsD = comparisonTargetAsDouble(target);
            if (!Double.isNaN(lhsD) && rhsD != null)
            {
                return lhsD == rhsD;
            }
        }
        String a = dvMissing ? "" : dv.getValueAsString();
        String b = target == null ? "" : target.toString();
        return caseInsensitive ? a.equalsIgnoreCase(b) : a.equals(b);
    }


    /**
     * Numeric-membership verdict (Phase 9b, decision D2). The probe {@code dv} is a member of an
     * <b>all-numeric</b> membership list iff its content parses to a finite number ({@code ==})
     * equal to one of the {@code numericMembers}. A missing / empty / non-numeric probe parses to
     * {@code NaN} and is therefore <b>never</b> a member — so a numeric {@code not in} list fires
     * on a blank exactly as the textual path fires on a blank not present in the set (a numeric set
     * can never contain {@code ""}). The members were parsed once via the same
     * {@link IDataValue#getValueAsDouble()} / {@code Double.parseDouble} path, and the comparison
     * is exact {@code ==} on the parsed doubles — consistent with {@link #equalsNumericAware} — so
     * {@code "1.0"}, {@code "01"} and {@code "1"} all match the member {@code 1}.
     *
     * <p>
     * This is the single anchor for numeric membership: the native evaluator
     * ({@code Primitives.numericMembership}) and the membership operators both route through it, so
     * they cannot drift. {@code numericMembers} is a {@code Set<Double>}; {@code Double.equals}
     * coincides with {@code ==} for the finite, non-{@code -0.0} members that arise from numeric
     * literals, and the probe is excluded up front when it is {@code NaN}.
     * </p>
     *
     * @return {@code true} when the probe parses to a number equal to a member
     */
    public static boolean isNumericMember(IDataValue dv, java.util.Set<Double> numericMembers)
    {
        if (dv == null)
        {
            return false;
        }
        double probe = dv.getValueAsDouble();
        if (Double.isNaN(probe))
        {
            return false;
        }
        return numericMembers.contains(probe);
    }


    /**
     * {@code true} when the cell's declared type is {@link DataValueType#LONG} / {@code DOUBLE}.
     */
    private static boolean isNumericType(IDataValue dv)
    {
        DataValueType t = dv.getType();
        return t == DataValueType.LONG || t == DataValueType.DOUBLE;
    }

    // -------------------------------------------------------------------------
    // Numeric coercion for the polymorphic date comparison (type-gated LHS)
    // -------------------------------------------------------------------------


    /**
     * Returns the LHS as a {@code Double} only when the value's declared type is
     * {@link DataValueType#LONG} or {@link DataValueType#DOUBLE}. A {@code STRING} cell is never
     * coerced — even if its content parses as a number — because the column's declared type is the
     * authoritative signal that the rule is operating on numeric SAS dates rather than ISO strings.
     */
    public static @Nullable Double tryNumericLhs(IDataValue dv)
    {
        DataValueType t = dv.getType();
        if (t == DataValueType.LONG || t == DataValueType.DOUBLE)
        {
            double d = dv.getValueAsDouble();
            return Double.isNaN(d) ? null : d;
        }
        return null;
    }


    /**
     * Returns the resolved RHS as a {@code Double}: directly for {@link Number}, parsed for
     * {@link String}s that parse cleanly, {@code null} otherwise.
     */
    public static @Nullable Double tryNumericRhs(@Nullable Object value)
    {
        if (value instanceof Number n)
        {
            return n.doubleValue();
        }
        if (value instanceof String s)
        {
            try
            {
                return Double.parseDouble(s);
            }
            catch (NumberFormatException _)
            {
                return null;
            }
        }
        return null;
    }


    /**
     * Coerces a resolved comparison target to a {@code Double}: directly for {@link Number}, parsed
     * via {@code Double.parseDouble(target.toString())} otherwise, {@code null} when missing or
     * non-parseable. Used by the numeric {@code less_than}/{@code greater_than} family (distinct
     * from {@link #tryNumericRhs} only in that it parses the {@code toString()} of arbitrary
     * non-string objects).
     */
    public static @Nullable Double comparisonTargetAsDouble(@Nullable Object target)
    {
        if (target == null)
        {
            return null;
        }
        if (target instanceof Number n)
        {
            return n.doubleValue();
        }
        try
        {
            return Double.parseDouble(target.toString());
        }
        catch (NumberFormatException _)
        {
            return null;
        }
    }


    /**
     * Coerces the <b>LHS cell</b> of a numeric comparison ({@code less_than}/{@code greater_than}
     * family) to a {@code Double}, mirroring {@link #comparisonTargetAsDouble(Object)} on the RHS
     * so the two operands are treated symmetrically. A missing cell ⇒ {@code null} (no violation).
     * A numeric-typed cell returns its double directly; a Character cell holding numeric text (the
     * {@code --ORNRHI}/{@code --ORNRLO} reference-range case, where the SDTM variable is
     * {@code Char} but carries a number and the rule guards with {@code is_numeric}) is parsed from
     * its string form — matching the Python oracle, which parses both sides. A non-numeric cell ⇒
     * {@code null} (no violation), so genuinely textual data is unaffected. Used by both the native
     * ({@code Primitives.comparison}) paths so the two engines cannot drift.
     */
    public static @Nullable Double comparisonLhsAsDouble(IDataValue dv)
    {
        if (isMissing(dv))
        {
            return null;
        }
        double d = dv.getValueAsDouble();
        if (!Double.isNaN(d))
        {
            return d;
        }
        try
        {
            return Double.parseDouble(dv.getValueAsString());
        }
        catch (NumberFormatException _)
        {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // ISO date string comparison
    // -------------------------------------------------------------------------


    /** Strips a trailing {@code Z} / {@code ±HH:MM} / {@code ±HHMM} offset, if present. */
    public static String stripTimezone(String s)
    {
        if (s == null || s.isEmpty())
        {
            return s;
        }
        return ISO_TZ_OFFSET.matcher(s).replaceFirst("");
    }


    /**
     * Strips a trailing fractional-seconds group ({@code .<digits>}) from an ISO-8601 value, after
     * any timezone has already been removed. A {@code '.'} not followed exclusively by digits is
     * left intact (not a fractional-seconds tail). Mirrors the optional {@code microsecond} group
     * in the Python {@code date_regex} oracle.
     */
    public static String stripFractionalSeconds(String s)
    {
        if (s == null || s.isEmpty())
        {
            return s;
        }
        int dot = s.indexOf('.');
        if (dot < 0)
        {
            return s;
        }
        for (int i = dot + 1; i < s.length(); i++)
        {
            if (!isAsciiDigit(s.charAt(i)))
            {
                return s;
            }
        }
        return s.substring(0, dot);
    }


    /**
     * Renders an ISO-8601 value in UTC for comparison: a trailing offset ({@code Z} /
     * {@code ±HH:MM} / {@code ±HHMM}) is applied <i>instant-preserving</i> and removed, keeping the
     * input's precision tier ({@code 2024-03-15T13+02:00} → {@code 2024-03-15T11}); offset-less
     * input is returned unchanged — the value is assumed to already be UTC, byte-identical to the
     * legacy strip-only behaviour. A BARE TIME with an offset ({@code 13:30:00+02:00}, optionally
     * with a leading {@code 'T'}) is likewise normalized to UTC at its precision (review F7), so
     * {@code time_part} comparisons stay consistent with the datetime path. Any other value that
     * carries an offset but is not a parseable ISO date-time (garbage, or a bare date with an
     * offset glued on) falls back to the plain offset strip — the legacy behaviour — so malformed
     * data can never regress or throw.
     */
    public static String normalizeToUtc(String s)
    {
        if (s == null || s.isEmpty())
        {
            return s;
        }
        Matcher m = ISO_TZ_OFFSET.matcher(s);
        if (!m.find())
        {
            return s; // no offset: assume UTC
        }
        String stripped = s.substring(0, m.start());
        int tier = detectIsoPrecision(stripped);
        if (tier < 13)
        {
            // Review F7: a BARE TIME with an offset ("13:30:00+02:00", "T13:30+0200") denotes
            // an instant-of-day just like a full datetime's time part — apply the offset and
            // render in UTC so a time_part comparison against a UTC-normalized datetime LHS
            // stays consistent. Every other sub-hour-precision shape (bare date, garbage) is
            // not a parseable ISO date-time; keep the legacy strip-only verdict.
            String time = normalizeBareTimeToUtc(stripped, m.group());
            return time != null ? time : stripped;
        }
        String offset = m.group();
        if (offset.length() == 5)
        {
            // ±HHMM — ISO_OFFSET_DATE_TIME only accepts the colon form ±HH:MM.
            offset = offset.substring(0, 3) + ":" + offset.substring(3);
        }
        // ISO_OFFSET_DATE_TIME needs at least minute precision — pad an hour-only value.
        String dateTime = tier == 13 ? stripped + ":00" : stripped;
        try
        {
            OffsetDateTime odt = OffsetDateTime.parse(dateTime + offset,
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            OffsetDateTime utc = odt.withOffsetSameInstant(ZoneOffset.UTC);
            return UTC_RENDER.format(utc).substring(0, tier);
        }
        catch (DateTimeParseException _)
        {
            return stripped; // legacy fallback
        }
    }


    /**
     * Review F7 — applies {@code offset} to a bare time-of-day value (optional leading {@code 'T'},
     * then {@code HH:MM}, {@code HH:MM:SS} or {@code HH:MM:SS.fff} — no date part) and re-renders
     * it in UTC at the input's precision, preserving the leading {@code 'T'}. Returns {@code null}
     * when {@code stripped} is not a bare time shape or does not parse (out-of-range fields), so
     * the caller falls back to the legacy offset strip.
     */
    private static @Nullable String normalizeBareTimeToUtc(String stripped, String offset)
    {
        if (!BARE_TIME_SHAPE.matcher(stripped).matches())
        {
            return null;
        }
        boolean leadingT = stripped.charAt(0) == 'T';
        String time = leadingT ? stripped.substring(1) : stripped;
        // ±HHMM — OffsetTime's ISO parser only accepts Z / the colon form ±HH:MM.
        String colonOffset = offset.length() == 5
                ? offset.substring(0, 3) + ":" + offset.substring(3)
                : offset;
        // Pad a minute-only value for parsing, mirroring the datetime path's :00 pad.
        String padded = time.length() == 5 ? time + ":00" : time;
        try
        {
            OffsetTime utc = OffsetTime.parse(padded + colonOffset)
                    .withOffsetSameInstant(ZoneOffset.UTC);
            String rendered = UTC_TIME_RENDER.format(utc).substring(0, time.length());
            return leadingT ? "T" + rendered : rendered;
        }
        catch (DateTimeParseException _)
        {
            return null;
        }
    }


    /**
     * Detects the precision tier of an ISO-8601 string by length, after timezone stripping. Tiers:
     * 4 (year), 7 (month), 10 (day), 13 (hour), 16 (minute), 19 (second), 23 (millisecond).
     */
    public static int detectIsoPrecision(String s)
    {
        if (s == null)
        {
            return 0;
        }
        int n = s.length();
        if (n >= 23)
        {
            return 23;
        }
        if (n >= 19)
        {
            return 19;
        }
        if (n >= 16)
        {
            return 16;
        }
        if (n >= 13)
        {
            return 13;
        }
        if (n >= 10)
        {
            return 10;
        }
        if (n >= 7)
        {
            return 7;
        }
        if (n >= 4)
        {
            return 4;
        }
        return n;
    }


    /**
     * Compares two ISO-8601 strings with precision-auto truncation. Both sides are normalized to
     * UTC ({@link #normalizeToUtc}: a trailing offset is applied instant-preserving, an offset-less
     * value is assumed UTC), then truncated to {@code min(precA, precB)} before a plain
     * lexicographic {@code compareTo}. The truncation runs on the UTC-rendered strings, so an
     * offset that shifts the value across midnight correctly changes the date part.
     */
    public static int compareIso(String a, String b)
    {
        String aStripped = normalizeToUtc(a);
        String bStripped = normalizeToUtc(b);
        if (aStripped == null)
        {
            aStripped = "";
        }
        if (bStripped == null)
        {
            bStripped = "";
        }
        int precA = detectIsoPrecision(aStripped);
        int precB = detectIsoPrecision(bStripped);
        int prec = Math.min(precA, precB);
        String aTrunc = aStripped.length() > prec ? aStripped.substring(0, prec) : aStripped;
        String bTrunc = bStripped.length() > prec ? bStripped.substring(0, prec) : bStripped;
        return aTrunc.compareTo(bTrunc);
    }


    /** Numeric date comparator with {@link #DATE_EPSILON} tolerance. */
    public static boolean matchNumeric(double lhs, double rhs, int direction, boolean orEqual)
    {
        if (direction == 0)
        {
            return Math.abs(lhs - rhs) < DATE_EPSILON;
        }
        if (direction > 0)
        {
            return orEqual ? lhs + DATE_EPSILON >= rhs : lhs - DATE_EPSILON > rhs;
        }
        return orEqual ? lhs <= rhs + DATE_EPSILON : lhs + DATE_EPSILON < rhs;
    }


    /** Lexicographic comparator for the ISO path. */
    public static boolean matchCmp(int cmp, int direction, boolean orEqual)
    {
        if (direction == 0)
        {
            return cmp == 0;
        }
        if (direction > 0)
        {
            return orEqual ? cmp >= 0 : cmp > 0;
        }
        return orEqual ? cmp <= 0 : cmp < 0;
    }

    // -------------------------------------------------------------------------
    // Structural ISO date-prefix validators (legacy: no calendar validation)
    // -------------------------------------------------------------------------


    /**
     * Hand-rolled validator for complete ISO-8601 date prefixes (lengths 10, 16, 19). No calendar
     * validation — matches the legacy structural-only contract.
     */
    public static boolean isCompleteDate(String s)
    {
        if (s == null)
        {
            return false;
        }
        // Match the Python is_complete_date oracle (datetime.fromisoformat, with a Z->+00:00
        // fallback): a day-precision-or-finer value carrying a timezone or fractional seconds is
        // still "complete". Strip those decorations before the structural length gate. An interval
        // is never complete, so — unlike isPartialDate — there is no "/" handling here.
        s = stripFractionalSeconds(stripTimezone(s));
        int len = s.length();
        if (len != 10 && len != 16 && len != 19)
        {
            return false;
        }
        if (!isAsciiDigit(s.charAt(0)) || !isAsciiDigit(s.charAt(1)) || !isAsciiDigit(s.charAt(2))
                || !isAsciiDigit(s.charAt(3)))
        {
            return false;
        }
        if (s.charAt(4) != '-')
        {
            return false;
        }
        if (!isAsciiDigit(s.charAt(5)) || !isAsciiDigit(s.charAt(6)))
        {
            return false;
        }
        if (s.charAt(7) != '-')
        {
            return false;
        }
        if (!isAsciiDigit(s.charAt(8)) || !isAsciiDigit(s.charAt(9)))
        {
            return false;
        }
        if (len == 10)
        {
            return true;
        }
        if (s.charAt(10) != 'T')
        {
            return false;
        }
        if (!isAsciiDigit(s.charAt(11)) || !isAsciiDigit(s.charAt(12)))
        {
            return false;
        }
        if (s.charAt(13) != ':')
        {
            return false;
        }
        if (!isAsciiDigit(s.charAt(14)) || !isAsciiDigit(s.charAt(15)))
        {
            return false;
        }
        if (len == 16)
        {
            return true;
        }
        if (s.charAt(16) != ':')
        {
            return false;
        }
        return isAsciiDigit(s.charAt(17)) && isAsciiDigit(s.charAt(18));
    }


    /**
     * Hand-rolled validator for <b>every valid partial date form</b> — the ISO-8601
     * right-truncation prefixes (lengths 4, 7, 10, 13, 16, 19) <b>and</b> the SDTM masked forms of
     * {@link #isMaskedDate} ({@code 2012-06--}, {@code 2012---15}, {@code ----06-15}). No calendar
     * validation: {@code 2026-02-30} and {@code 2012---32} are both structurally fine here and are
     * {@code CalendarDates}' business to reject.
     *
     * <p>
     * &#9873; <b>The masked forms were admitted by {@code Fix #215}</b>
     * ({@code PLAN-is-partial-date-masked-forms.md} Phase 3, owner ruling 2026-08-09 option (a):
     * <i>widen in place</i>). Before it, this predicate modelled ISO-8601 <b>truncation</b> only —
     * dropping <em>trailing</em> components — and rejected a hyphen placeholder for a
     * <em>middle</em> unknown, so a legitimately partial {@code --DTC} was reported as an invalid
     * date. {@code plans/PLAN-partial-date-extreme-selection.md} (and the retired
     * {@code CORE-RULES-JAVA-EXTENSIONS.md} &#167;21, indexed in
     * {@code corej-cdisc-rules/documentation/expression-docs-disposition.md} &#167;A) state that
     * {@code --DTC} variables <b>legally</b> carry masked components, so that rejection was a false
     * positive. &#9888; The name is now the contract: <em>this answers "is {@code s} a partial
     * date", not "is {@code s} a truncation prefix"</em>. When you need the narrower question, ask
     * {@link #isoComponents(String)} whether the layout it returns has an <em>interior</em>
     * {@link IsoDateComponents#ABSENT}, or ask {@link #isMaskedDate(String)} directly.
     * </p>
     *
     * <p>
     * The structural walk itself lives in {@link #isoComponents(String)}: this predicate is that
     * decoder's {@code != null}, plus the interval and normalisation handling below. They are
     * deliberately <b>one</b> implementation — a consumer that needs the components must not be
     * able to disagree with the gate about which strings are well-formed, which is exactly the
     * split that produced the crash documented on {@link IsoDateComponents}.
     * </p>
     */
    public static boolean isPartialDate(String s)
    {
        if (s == null)
        {
            return false;
        }
        // ISO interval "a/b": valid iff both halves are valid partial dates (Python date_regex
        // parity — an interval of uncertainty is accepted by the Python is_valid_date oracle).
        int slash = s.indexOf('/');
        if (slash >= 0)
        {
            return isPartialDate(s.substring(0, slash)) && isPartialDate(s.substring(slash + 1));
        }
        // Strip an optional trailing timezone (Z / ±HH:MM) then fractional seconds (.<digits>)
        // before the structural length gate, so a --DTC carrying a legitimate offset / fractional
        // second is not flagged invalid (Python accepts these; Java previously over-reported).
        return isoComponents(stripFractionalSeconds(stripTimezone(s))) != null;
    }


    /**
     * Decodes {@code s} as a partial ISO-8601 date and returns the components it carries, or
     * {@code null} when it is not one. The structural contract is {@link #isPartialDate}'s: either
     * a <b>right-truncation prefix</b> — length 4, 7, 10, 13, 16 or 19, ASCII digits in the
     * component positions and {@code -} / {@code T} / {@code :} separators between them — or one of
     * the SDTM <b>masked</b> shapes {@link #isMaskedDate} accepts. No calendar validation:
     * {@code 2026-02-30} and {@code 2012---32} both decode happily and are {@code CalendarDates}'
     * business to reject.
     *
     * <p>
     * &#9888; <b>{@code s} must already be normalised</b>: no {@code a/b} interval, and any
     * timezone offset and fractional-seconds tail already stripped. This method does <b>not</b>
     * re-normalise, so the components it returns are always positions of the string it was handed —
     * a caller can never read a component out of a string the gate did not actually inspect.
     * {@link #isPartialDate} performs that normalisation before delegating here.
     * </p>
     *
     * <p>
     * Components the value does not carry are {@link IsoDateComponents#ABSENT}. &#9873;&#9873;
     * <b>Since {@code Fix #215} that is no longer always a trailing run.</b> A right-truncation
     * prefix drops components from the right, so its absences are a suffix; a masked value replaces
     * a <em>middle</em> component and keeps the ones after it, so {@code 2012---15} decodes to an
     * absent month with a <b>present</b> day, and {@code ----06-15} to an absent <b>year</b> with a
     * present month and day. &#9888;&#9888; <b>A consumer must therefore validate every component
     * the layout carries and skip the absent ones — never stop at the first {@code ABSENT}</b>,
     * which was the pre-{@code Fix #215} shape and would have silently accepted {@code 2012---32}.
     * </p>
     *
     * <p>
     * &#9873; <b>The masked set decoded here is exactly {@link #isMaskedDate}'s</b>, and that
     * containment is load-bearing rather than incidental: {@code IsoDateBounds.bound} dispatches on
     * {@code isMaskedDate} and routes everything else to {@code truncatedBound}, which reads fixed
     * substrings. A shape accepted here but rejected by {@code isMaskedDate} would reach that
     * reader as a value it cannot decode. Pinned by
     * {@code IsoDateLayoutDifferentialTest.maskedAcceptanceIsExactlyIsMaskedDate}.
     * </p>
     *
     * @param s
     *            a normalised candidate, or {@code null}
     * @return the decoded components, or {@code null} when {@code s} is not a partial date
     */
    public static @Nullable IsoDateComponents isoComponents(@Nullable String s)
    {
        if (s == null)
        {
            return null;
        }
        int len = s.length();
        // The masked shapes have lengths 7/8/9. Only 7 collides with a truncation length, and a
        // year-masked value fails the four-leading-digits test below, so the two families are
        // disjoint however this is ordered — see maskedComponents.
        if (len >= 7 && len <= 9)
        {
            IsoDateComponents masked = maskedComponents(s, len);
            if (masked != null)
            {
                return masked;
            }
        }
        if (len != 4 && len != 7 && len != 10 && len != 13 && len != 16 && len != 19)
        {
            return null;
        }
        if (!allDigits(s, 0, 4))
        {
            return null;
        }
        int year = decimal(s, 0, 4);
        int absent = IsoDateComponents.ABSENT;
        if (len == 4)
        {
            return new IsoDateComponents(year, absent, absent, absent, absent, absent);
        }
        if (s.charAt(4) != '-' || !allDigits(s, 5, 7))
        {
            return null;
        }
        int month = decimal(s, 5, 7);
        if (len == 7)
        {
            return new IsoDateComponents(year, month, absent, absent, absent, absent);
        }
        if (s.charAt(7) != '-' || !allDigits(s, 8, 10))
        {
            return null;
        }
        int day = decimal(s, 8, 10);
        if (len == 10)
        {
            return new IsoDateComponents(year, month, day, absent, absent, absent);
        }
        if (s.charAt(10) != 'T' || !allDigits(s, 11, 13))
        {
            return null;
        }
        int hour = decimal(s, 11, 13);
        if (len == 13)
        {
            return new IsoDateComponents(year, month, day, hour, absent, absent);
        }
        if (s.charAt(13) != ':' || !allDigits(s, 14, 16))
        {
            return null;
        }
        int minute = decimal(s, 14, 16);
        if (len == 16)
        {
            return new IsoDateComponents(year, month, day, hour, minute, absent);
        }
        if (s.charAt(16) != ':' || !allDigits(s, 17, 19))
        {
            return null;
        }
        return new IsoDateComponents(year, month, day, hour, minute, decimal(s, 17, 19));
    }


    /**
     * {@code Fix #215} — decodes the SDTM masked shapes for {@link #isoComponents(String)}, or
     * {@code null} when {@code s} is not one. The three shape tests are
     * {@link #isMaskedDate(String)}'s own, so the set decoded here is <b>exactly</b> the set that
     * predicate accepts; only the component extraction is new.
     *
     * <p>
     * &#9888; The shapes are mutually exclusive, so the order of the three tests does not matter: a
     * day-masked value has digits at 5–6 where a month-masked one has hyphens, and a year-masked
     * one has no leading digits at all. &#9888; Unlike {@link #isMaskedDate(String)} this does
     * <b>not</b> trim — {@link #isoComponents(String)} reads the string it was handed, which is the
     * contract that keeps the gate and the reader from drifting apart.
     * </p>
     *
     * @param s
     *            the candidate, already known to be 7–9 characters long
     * @param len
     *            {@code s.length()}, since every branch needs it
     */
    private static @Nullable IsoDateComponents maskedComponents(String s, int len)
    {
        int absent = IsoDateComponents.ABSENT;
        if (isDayMasked(s))
        {
            // 2012-06- / 2012-06-- — year and month known, day (and everything finer) unknown.
            return new IsoDateComponents(decimal(s, 0, 4), decimal(s, 5, 7), absent, absent, absent,
                    absent);
        }
        if (isMonthMasked(s))
        {
            // 2012--15 / 2012---15 — an INTERIOR absence: the day is known, the month is not.
            return new IsoDateComponents(decimal(s, 0, 4), absent, decimal(s, len - 2, len), absent,
                    absent, absent);
        }
        if (isYearMasked(s))
        {
            // --06-15 / ----06-15 — the year is unknown, so the value is unpositionable; the month
            // and day are still known and still have to be calendar-checkable.
            return new IsoDateComponents(absent, decimal(s, len - 5, len - 3),
                    decimal(s, len - 2, len), absent, absent, absent);
        }
        return null;
    }


    /**
     * The decimal value of {@code [from, to)}, which the caller has already checked is all ASCII
     * digits. Hand-rolled rather than {@code Integer.parseInt(substring(…))} to avoid allocating on
     * the per-cell path.
     */
    private static int decimal(String s, int from, int to)
    {
        int value = 0;
        for (int i = from; i < to; i++)
        {
            value = value * 10 + s.charAt(i) - '0';
        }
        return value;
    }


    /**
     * {@code true} iff {@code s} is an SDTM <b>masked</b> date — a date-shaped value in which one
     * whole component has been replaced by hyphen placeholders so the components after it keep
     * their position.
     *
     * <table border="1">
     * <caption>The three masked forms</caption>
     * <tr>
     * <th>form</th>
     * <th>example</th>
     * <th>meaning</th>
     * </tr>
     * <tr>
     * <td>day masked</td>
     * <td>{@code 2012-06--}, {@code 2012-06-}</td>
     * <td>some day in June 2012</td>
     * </tr>
     * <tr>
     * <td>month masked</td>
     * <td>{@code 2012---15}, {@code 2012--15}</td>
     * <td>the 15th of some month in 2012</td>
     * </tr>
     * <tr>
     * <td>year masked</td>
     * <td>{@code ----06-15}, {@code --06-15}</td>
     * <td>15 June of some year</td>
     * </tr>
     * </table>
     *
     * <p>
     * &#9873; <b>Why this is still a separate predicate from {@link #isPartialDate}.</b> It used to
     * be the <em>only</em> one that accepted these shapes: {@code isPartialDate} modelled ISO-8601
     * <b>truncation</b> — dropping <em>trailing</em> components ({@code 2012}, {@code 2012-06}) —
     * and rejected a hyphen placeholder for a <em>middle</em> unknown as a different convention.
     * {@code Fix #215} widened it, so <b>{@code isMaskedDate(s)} now implies
     * {@code isPartialDate(s)}</b> and the two are no longer opposites. &#9888; With one asymmetry
     * that predates both and is deliberately not changed here: <b>this predicate trims and
     * {@code isPartialDate} does not</b>, so a value padded with whitespace is masked here and not
     * a partial date there. Nothing routes on that — {@code IsoDateBounds.core} trims before it
     * asks — but do not read the implication as unconditional. What this one still answers alone is
     * <em>which</em> of the two conventions a value uses — the question {@code IsoDateBounds.bound}
     * dispatches on, because a masked value's hull is a non-contiguous set rather than a right-open
     * interval. {@code plans/PLAN-partial-date-extreme-selection.md} records that {@code --DTC}
     * variables <b>legally</b> carry masked components, which is why widening was the right call.
     * </p>
     * <p>
     * &#9888; A hyphen is legal only as a <b>whole</b> component: {@code 2012---15} yes,
     * {@code 2012-0--15} no. Structural only — no calendar validation, exactly like
     * {@link #isPartialDate}, so {@code 2012---32} is masked-shaped here and is separately rejected
     * by {@code CalendarDates} / {@code IsoDateBounds}.
     * </p>
     * <p>
     * &#9873; <b>This predicate does not answer "can it be positioned".</b> A year-masked value is
     * masked but <em>unbounded</em> — nothing anchors it on the calendar. That question already has
     * an answer: {@code IsoDateBounds.canPosition}, which is true for complete, truncated-partial
     * and bounded-masked values and false for year-masked, blank and invalid ones.
     * </p>
     */
    public static boolean isMaskedDate(String s)
    {
        if (s == null)
        {
            return false;
        }
        // ⚠⚠ Fix #215 — strip a trailing timezone / fractional-seconds tail before matching the
        // shape, exactly as isPartialDate and isCompleteDate do. This is NOT cosmetic symmetry:
        // IsoDateBounds.bound dispatches on this predicate and sends everything it rejects to
        // truncatedBound, which reads fixed substrings of the string it was handed. Its own core()
        // strips only ONE decoration, so a doubly-decorated masked value ("2012-06--ZZ") arrived
        // as "2012-06--Z" — shape-rejected here, yet accepted by CalendarDates.isValidDate, which
        // strips again. Measured: without this strip, 22 such inputs produced a garbage bound and
        // 4 of them ("2012--15ZZ", "2012--15Z+01:00", "--06-15ZZ", "--06-15Z+01:00") threw an
        // UNCAUGHT NumberFormatException / DateTimeException out of the evaluator. Accepting them
        // here routes them to maskedBound, whose anchored patterns do not match a decorated value,
        // so they answer null — the pre-Fix-#215 verdict, restored exactly.
        String t = s.trim();
        if (t.length() < 7)
        {
            // The shortest masked form is --06-15 (7), and stripping only shortens. Answering here
            // keeps the regex in stripTimezone off the per-cell path for blank and short cells.
            return false;
        }
        String v = stripFractionalSeconds(stripTimezone(t));
        return isDayMasked(v) || isMonthMasked(v) || isYearMasked(v);
    }


    /** {@code 2012-06--} (9) / {@code 2012-06-} (8) — {@code YYYY-MM} then one or two hyphens. */
    private static boolean isDayMasked(String v)
    {
        int len = v.length();
        return (len == 8 || len == 9) && allDigits(v, 0, 4) && v.charAt(4) == '-'
                && allDigits(v, 5, 7) && allHyphens(v, 7, len);
    }


    /** {@code 2012---15} (9) / {@code 2012--15} (8) — {@code YYYY}, hyphens, then {@code DD}. */
    private static boolean isMonthMasked(String v)
    {
        int len = v.length();
        int hyphens = len - 6;
        return (len == 8 || len == 9) && allDigits(v, 0, 4) && allHyphens(v, 4, 4 + hyphens)
                && allDigits(v, 4 + hyphens, len);
    }


    /** {@code ----06-15} / {@code --06-15} — leading hyphens, then {@code MM-DD}. */
    private static boolean isYearMasked(String v)
    {
        int len = v.length();
        int lead = len - 5;
        return (len == 7 || len == 9) && allHyphens(v, 0, lead) && allDigits(v, lead, lead + 2)
                && v.charAt(lead + 2) == '-' && allDigits(v, lead + 3, len);
    }


    /** Whether every character in {@code [from, to)} is an ASCII digit. */
    private static boolean allDigits(String v, int from, int to)
    {
        for (int i = from; i < to; i++)
        {
            if (!isAsciiDigit(v.charAt(i)))
            {
                return false;
            }
        }
        return from < to;
    }


    /** Whether every character in {@code [from, to)} is a hyphen. */
    private static boolean allHyphens(String v, int from, int to)
    {
        for (int i = from; i < to; i++)
        {
            if (v.charAt(i) != '-')
            {
                return false;
            }
        }
        return from < to;
    }


    private static boolean isAsciiDigit(char c)
    {
        return c >= '0' && c <= '9';
    }

    // -------------------------------------------------------------------------
    // String-part extraction (does_not_equal_string_part)
    // -------------------------------------------------------------------------


    /**
     * The {@code does_not_equal_string_part} per-row verdict for a present {@code value} / present
     * {@code target} pair: matches {@code pattern} against the whole {@code target}, extracts
     * capture group&nbsp;1, and reports whether {@code value} does <b>not</b> equal that extracted
     * part. A {@code target} that does not fully match, or a {@code pattern} without a
     * group&nbsp;1, is not a violation. An optional group&nbsp;1 that did not participate in the
     * match (a {@code null} from {@link Matcher#group(int)}) is treated as the empty string, so
     * {@code (\d*)?} and {@code (\d*)} yield the same verdict. Mirrors the legacy
     * {@code does_not_equal_string_part} operator; the missing-operand gate (missing value or
     * {@code null} target) is the caller's responsibility, exactly as in the legacy
     * {@code forEachValue} body.
     *
     * @param value
     *            the column cell's string form (LHS)
     * @param target
     *            the resolved value operand's string form (RHS)
     * @param pattern
     *            the capture-group regex applied to {@code target}
     * @return {@code true} when {@code value} differs from the extracted group&nbsp;1
     */
    public static boolean differsFromStringPart(String value, String target, Pattern pattern)
    {
        Matcher m = pattern.matcher(target);
        if (!m.matches() || m.groupCount() < 1)
        {
            return false;
        }
        String g = m.group(1);
        return !value.equals(g == null ? "" : g);
    }

    // -------------------------------------------------------------------------
    // Integer check
    // -------------------------------------------------------------------------


    /** {@code true} iff the value's string form parses as a finite whole number. */
    public static boolean isIntegerValue(IDataValue dv)
    {
        return isIntegerString(dv.getValueAsString());
    }


    /** {@code true} iff {@code s} parses as a finite whole number. */
    public static boolean isIntegerString(String s)
    {
        if (s == null || s.isEmpty())
        {
            return false;
        }
        try
        {
            double d = Double.parseDouble(s);
            return !Double.isInfinite(d) && !Double.isNaN(d) && d == Math.floor(d);
        }
        catch (NumberFormatException _)
        {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // ISO-8601 duration
    // -------------------------------------------------------------------------


    /**
     * Returns {@code true} when {@code s} is <b>not</b> a valid ISO-8601 duration (e.g.
     * {@code P1Y}, {@code P2M3D}, {@code PT24H}). When {@code allowNegative} is {@code true}, a
     * leading minus-sign variant (e.g. {@code -P1Y}) is also accepted as valid. Mirrors the legacy
     * {@code invalid_duration} operator, which is the complement of the Python oracle's
     * {@code is_valid_duration}.
     */
    public static boolean isInvalidDuration(String s, boolean allowNegative)
    {
        if (s == null || s.isEmpty() || "P".equals(s) || "PT".equals(s))
        {
            return true;
        }
        Pattern pattern = allowNegative ? ISO_8601_DURATION_WITH_NEGATIVE
                : ISO_8601_DURATION_POSITIVE;
        Matcher m = pattern.matcher(s);
        if (!m.matches())
        {
            return true;
        }
        return !isValidDurationPostMatch(m);
    }


    /**
     * Post-match validation mirroring Python's {@code is_valid_duration} tail (helpers.py:96-113).
     * Enforces two additional rules beyond the regex: (a) when the {@code T} designator is present,
     * at least one of H/M/S must be populated; (b) a decimal point or comma is allowed only in the
     * smallest populated component.
     */
    private static boolean isValidDurationPostMatch(Matcher m)
    {
        String years = m.group(1);
        String months = m.group(2);
        String days = m.group(3);
        String timeDesignator = m.group(4);
        String hours = m.group(5);
        String minutes = m.group(6);
        String seconds = m.group(7);
        String weeks = m.group(8);

        if (timeDesignator != null && hours == null && minutes == null && seconds == null)
        {
            return false;
        }

        // Python order: years, months, weeks, days, hours, minutes, seconds.
        List<String> components = new ArrayList<>(7);
        if (years != null)
        {
            components.add(years);
        }
        if (months != null)
        {
            components.add(months);
        }
        if (weeks != null)
        {
            components.add(weeks);
        }
        if (days != null)
        {
            components.add(days);
        }
        if (hours != null)
        {
            components.add(hours);
        }
        if (minutes != null)
        {
            components.add(minutes);
        }
        if (seconds != null)
        {
            components.add(seconds);
        }

        boolean decimalFound = false;
        for (int i = 0; i < components.size(); i++)
        {
            String component = components.get(i);
            if (component.indexOf('.') >= 0 || component.indexOf(',') >= 0)
            {
                if (decimalFound || i != components.size() - 1)
                {
                    return false;
                }
                decimalFound = true;
            }
        }
        return true;
    }

}
