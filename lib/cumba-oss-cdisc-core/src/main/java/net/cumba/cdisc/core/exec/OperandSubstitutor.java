package net.cumba.cdisc.core.exec;

import static java.lang.System.Logger.Level.TRACE;

import java.util.ArrayList;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.CustomLog;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * Fix #37 — operand-template substitution for row-driven column references.
 *
 * <p>
 * Two placeholder forms are recognised inside a Check leaf's {@code name} or {@code value} operand:
 * </p>
 * <ul>
 * <li><b>{@code ${VAR[:fmt]}}</b> — driver substitution. {@code VAR} is an unquoted identifier
 * naming a column on the current row; the optional {@code :fmt} is a {@link String#format} format
 * spec (e.g. {@code %02d}, {@code %s}). Resolves to a single string at evaluation time.</li>
 * <li><b>{@code ${*}}</b> — numeric wildcard. Matches {@code \d+} against foreign-dataset column
 * names with the surrounding literal text. No format spec is allowed.</li>
 * </ul>
 *
 * <p>
 * Composes with Fix #18's dotted form ({@code <DOMAIN>.<column-pattern>}) for cross-dataset
 * references.
 * </p>
 *
 * <p>
 * This class is a pure helper (no engine state). Its three public phases are:
 * </p>
 * <ol>
 * <li>{@link #parse(String)} — split an operand into a {@link Scalar} or {@link Wildcard} parsed
 * form. Pure, called at rule-load time and again at evaluation time.</li>
 * <li>{@link #validate(ParsedOperand, String, Position)} — enforce the operator-compatibility
 * matrix. Called at rule-load time.</li>
 * <li>{@link #substituteScalar(Scalar, EvaluationContext, long)} /
 * {@link #toColumnPattern(Wildcard, EvaluationContext, long)} — resolve placeholders against the
 * row's driver values. Called per row at evaluation time.</li>
 * </ol>
 */
@CustomLog
public final class OperandSubstitutor
{

    /** Foreign-dataset prefix (Fix #18 shape). */
    private static final Pattern FOREIGN_DATASET_PREFIX = Pattern
            .compile("^([A-Z][A-Z0-9]*)\\.(.*)$");

    /** Driver placeholder: {@code ${VAR[:fmt]}}. */
    private static final Pattern DRIVER_PATTERN = Pattern
            .compile("\\$\\{([A-Z][A-Z0-9_]*)(?::([^}]+))?}");

    /** Wildcard placeholder: {@code ${*}}. Literal — no captured groups. */
    private static final String WILDCARD_TOKEN = "${*}";

    /**
     * Position of an operand inside a Check leaf. Affects which operators are allowed for
     * {@code ${*}}.
     */
    public enum Position
    {
        NAME, VALUE
    }


    /**
     * Result of parsing — either a scalar form (single column ref) or a wildcard form (list
     * source).
     */
    public sealed interface ParsedOperand permits Scalar, Wildcard
    {

        /** The "{@code <DOMAIN>.}" prefix, or {@code null} when absent. */
        @Nullable
        String foreignDataset();


        /** {@code true} if any {@code ${VAR}} placeholder appears. */
        boolean hasDrivers();
    }


    public record Scalar(@Nullable String foreignDataset,
            List<Segment> segments) implements ParsedOperand
    {

        @Override
        public boolean hasDrivers()
        {
            return segments.stream().anyMatch(Driver.class::isInstance);
        }
    }


    /**
     * A wildcard operand template with one or more {@code ${*}} placeholders. The literal/driver
     * text between (and around) the stars is captured as a list of {@code fragments}: for
     * <em>n</em> stars there are <em>n+1</em> fragments (fragment <em>i</em> precedes star
     * <em>i</em>, the final fragment follows the last star). A single {@code ${*}} yields two
     * fragments — the classic before/after split. Adjacent stars ({@code ${*}${*}} with no literal
     * between) are rejected at {@link #parse(String)} time, so no interior fragment is empty.
     */
    public record Wildcard(@Nullable String foreignDataset,
            List<List<Segment>> fragments) implements ParsedOperand
    {

        @Override
        public boolean hasDrivers()
        {
            return fragments.stream().flatMap(List::stream).anyMatch(Driver.class::isInstance);
        }
    }


    public sealed interface Segment permits Literal, Driver
    {
    }


    public record Literal(String text) implements Segment
    {
    }


    public record Driver(String varName, String formatSpec) implements Segment
    {
    }

    private OperandSubstitutor()
    {
    }


    /**
     * Fast probe — returns {@code true} if the operand contains any <code>"${"</code> substring,
     * meaning it may be worth parsing. This keeps the parse path off the hot non-substituted code
     * paths.
     */
    public static boolean hasPlaceholder(@Nullable String operand)
    {
        return operand != null && operand.contains("${");
    }


    /**
     * The distinct driver column names ({@code ${VAR}} placeholders) referenced by {@code parsed},
     * in first-seen order; empty when the operand is driver-free (e.g. a bare {@code ${*}}
     * wildcard). Lets a caller separate the two failure modes {@link #substituteScalar} /
     * {@link #toColumnPattern} otherwise conflate: a driver column <em>absent from the schema</em>
     * (a rule-authoring error, to be raised loudly) versus a driver column that is present but
     * <em>missing on a given row</em> (normal data, the per-row reference is simply unresolvable).
     */
    public static List<String> driverColumns(ParsedOperand parsed)
    {
        Stream<Segment> segments = switch (parsed)
        {
        case Scalar s -> s.segments().stream();
        case Wildcard w -> w.fragments().stream().flatMap(List::stream);
        };
        return segments.filter(Driver.class::isInstance).map(seg -> ((Driver) seg).varName())
                .distinct().toList();
    }


    /**
     * Parses an operand string into a {@link ParsedOperand}.
     *
     * <p>
     * Recognised forms:
     * </p>
     *
     * <pre>
     *   "&lt;DOMAIN&gt;.PREFIX${VAR}MIDDLE${VAR2:%02d}SUFFIX"
     *   "&lt;DOMAIN&gt;.PREFIX${*}SUFFIX"
     *   "PREFIX${VAR}SUFFIX"
     *   "PREFIX${*}SUFFIX"
     * </pre>
     *
     * <p>
     * Two or more {@code ${*}} placeholders are allowed (EC-16) — each contributes an independent
     * {@code \d+} run to the column pattern — provided no two are adjacent (a literal or driver
     * fragment must separate them, otherwise the digit split is ambiguous).
     * </p>
     *
     * @throws OperandParseException
     *             on malformed syntax, adjacent {@code ${*}} placeholders, empty driver name, empty
     *             format, or invalid format spec.
     */
    public static ParsedOperand parse(@Nullable String operand)
    {
        if (operand == null)
        {
            throw new OperandParseException("operand is null");
        }

        // Strip foreign-dataset prefix first so the body is parsed independently.
        String foreign = null;
        String body = operand;
        Matcher fm = FOREIGN_DATASET_PREFIX.matcher(operand);
        if (fm.matches())
        {
            // Only treat the dot as a foreign-dataset prefix if the body contains a placeholder
            // — otherwise the parser would consume e.g. "ADSL.AESTDY" as foreign+body even when
            // the caller never asked for substitution. Callers gate this method behind
            // hasPlaceholder() anyway, but be conservative.
            String after = fm.group(2);
            if (hasPlaceholder(after))
            {
                foreign = fm.group(1);
                body = after;
            }
        }

        // Reject any malformed "${" without a closing "}".
        validateNoUnterminatedPlaceholder(body);

        // Reject `${*:fmt}`.
        if (Pattern.compile("\\$\\{\\*:[^}]*}").matcher(body).find())
        {
            throw new OperandParseException(
                    "`${*}` does not accept a format spec in operand `" + operand + "`");
        }

        // Count wildcard occurrences. One or more → Wildcard with n+1 fragments (EC-16).
        int wildcardCount = countOccurrences(body, WILDCARD_TOKEN);
        if (wildcardCount >= 1)
        {
            List<String> rawFragments = splitOnWildcard(body);
            // Reject adjacent runs (`${*}${*}` with no literal between): an empty interior
            // fragment would join two `\d+` runs with no separator, making the digit split
            // ambiguous (Q-13b). The first and last fragments may legitimately be empty
            // (e.g. `${*}SDT` or `PH${*}`).
            for (int i = 1; i < rawFragments.size() - 1; i++)
            {
                if (rawFragments.get(i).isEmpty())
                {
                    throw new OperandParseException("adjacent `${*}` placeholders in operand `"
                            + operand + "` are not allowed (ambiguous digit split)");
                }
            }
            List<List<Segment>> fragments = new ArrayList<>(rawFragments.size());
            for (String frag : rawFragments)
            {
                fragments.add(parseSegments(frag, operand));
            }
            return new Wildcard(foreign, fragments);
        }

        List<Segment> segments = parseSegments(body, operand);
        return new Scalar(foreign, segments);
    }


    private static void validateNoUnterminatedPlaceholder(String body)
    {
        int idx = 0;
        while ((idx = body.indexOf("${", idx)) >= 0)
        {
            int close = body.indexOf('}', idx + 2);
            if (close < 0)
            {
                throw new OperandParseException(
                        "unterminated `${` placeholder in operand `" + body + "`");
            }
            // Empty braces are caught by parseSegments; format empty-body too.
            idx = close + 1;
        }
    }


    /**
     * Parses a body fragment (between possible wildcards) into literal + driver segments.
     */
    private static List<Segment> parseSegments(String body, String wholeOperand)
    {
        List<Segment> segments = new ArrayList<>();
        if (body.isEmpty())
        {
            return segments;
        }
        Matcher m = DRIVER_PATTERN.matcher(body);
        int last = 0;
        while (m.find())
        {
            int start = m.start();
            // Anything before the match must not itself contain a stray `${`. The wildcard
            // token is already excised at this point; everything else with `${` not matching
            // DRIVER_PATTERN is malformed (e.g. `${}`, `${VAR:}`).
            String literalRun = body.substring(last, start);
            checkLiteralRun(literalRun, wholeOperand);
            if (!literalRun.isEmpty())
            {
                segments.add(new Literal(literalRun));
            }
            String varName = m.group(1);
            String fmt = m.group(2);
            if (fmt != null && fmt.isEmpty())
            {
                throw new OperandParseException("empty format spec in `${" + varName
                        + ":}` (operand `" + wholeOperand + "`)");
            }
            if (fmt != null)
            {
                validateFormatSpec(fmt, wholeOperand);
            }
            segments.add(new Driver(varName, fmt));
            last = m.end();
        }
        String tail = body.substring(last);
        checkLiteralRun(tail, wholeOperand);
        if (!tail.isEmpty())
        {
            segments.add(new Literal(tail));
        }
        return segments;
    }


    /**
     * Rejects literal runs that still contain <code>"${"</code> — those are malformed placeholders
     * (e.g. <code>${}</code> or <code>${VAR:}</code> that fell through DRIVER_PATTERN).
     */
    private static void checkLiteralRun(String run, String wholeOperand)
    {
        if (run.contains("${"))
        {
            throw new OperandParseException(
                    "malformed placeholder in operand `" + wholeOperand + "` near `" + run + "`");
        }
    }


    @SuppressWarnings(
    {
            "PMD.UselessPureMethodCall", "ReturnValueIgnored"
    })
    private static void validateFormatSpec(String fmt, String wholeOperand)
    {
        // Reject specs that don't contain a `%` conversion — `String.format("zz", x)` would
        // silently return the literal "zz" and consume no argument, which is never what
        // a CDISC rule author wants.
        if (!fmt.contains("%"))
        {
            throw new OperandParseException("invalid format spec `" + fmt + "` in operand `"
                    + wholeOperand + "` (missing `%` conversion)");
        }
        // Probe with a numeric and string sample. If neither works, reject.
        boolean ok = false;
        try
        {
            // test if the format is able to format a numeric value
            String.format(fmt, 1L);
            ok = true;
        }
        catch (IllegalFormatException e)
        {
            LOGGER.log(TRACE, "format spec rejected numeric sample; try string", e);
        }
        if (!ok)
        {
            try
            {
                // test if the format is able to format a numeric value
                String.format(fmt, "x");
                ok = true;
            }
            catch (IllegalFormatException e)
            {
                LOGGER.log(TRACE, "format spec rejected string sample; fall through", e);
            }
        }
        if (!ok)
        {
            throw new OperandParseException(
                    "invalid format spec `" + fmt + "` in operand `" + wholeOperand + "`");
        }
    }


    private static int countOccurrences(String s, String token)
    {
        int count = 0;
        int idx = 0;
        while ((idx = s.indexOf(token, idx)) >= 0)
        {
            count++;
            idx += token.length();
        }
        return count;
    }


    /**
     * Splits {@code body} on every {@code ${*}} token, keeping empty edges. For <em>n</em> tokens
     * the result has <em>n+1</em> elements; the token itself is discarded.
     */
    private static List<String> splitOnWildcard(String body)
    {
        List<String> parts = new ArrayList<>();
        int idx = 0;
        int found;
        while ((found = body.indexOf(WILDCARD_TOKEN, idx)) >= 0)
        {
            parts.add(body.substring(idx, found));
            idx = found + WILDCARD_TOKEN.length();
        }
        parts.add(body.substring(idx));
        return parts;
    }

    // ---------------------------------------------------------------------
    // Operator compatibility
    // ---------------------------------------------------------------------


    /**
     * Validates that an operator is allowed for this operand at the given position.
     *
     * <p>
     * Compatibility matrix (per Fix #37 plan):
     * </p>
     * <ul>
     * <li>{@link Scalar} in either position — any operator.</li>
     * <li>{@link Wildcard} in {@link Position#NAME} — only {@code exists} / {@code not_exists} /
     * {@code var_exists} / {@code var_not_exists}.</li>
     * <li>{@link Wildcard} in {@link Position#VALUE} — only list-aware operators
     * ({@code is_contained_by}, {@code is_not_contained_by},
     * {@code is_contained_by_case_insensitive}, {@code is_not_contained_by_case_insensitive}).</li>
     * </ul>
     *
     * @throws OperatorMismatchException
     *             when the combination is off-diagonal.
     */
    public static void validate(ParsedOperand parsed, @Nullable String operator, Position position)
    {
        if (parsed instanceof Scalar)
        {
            return;
        }
        // Wildcard
        if (position == Position.NAME)
        {
            // var_exists/var_not_exists inherit the column-form exists surface incl. the
            // wildcard name position; ds_exists/ds_not_exists take a plain dataset name only.
            if (!"exists".equals(operator) && !"not_exists".equals(operator)
                    && !"var_exists".equals(operator) && !"var_not_exists".equals(operator))
            {
                throw new OperatorMismatchException(
                        "operator `" + operator + "` is not allowed for `${*}` in name position;"
                                + " only `exists` / `not_exists` / `var_exists` / `var_not_exists`"
                                + " are list-aware in this position");
            }
            return;
        }
        // Position.VALUE
        if (!isListAwareValueOperator(operator))
        {
            throw new OperatorMismatchException(
                    "operator `" + operator + "` is not allowed for `${*}` in value position;"
                            + " only list-aware operators (is_contained_by, is_not_contained_by,"
                            + " is_contained_by_case_insensitive,"
                            + " is_not_contained_by_case_insensitive) accept a wildcard list");
        }
    }


    private static boolean isListAwareValueOperator(@Nullable String operator)
    {
        if (operator == null)
        {
            return false;
        }
        return switch (operator)
        {
        case "is_contained_by", "is_not_contained_by", "is_contained_by_case_insensitive", "is_not_contained_by_case_insensitive" -> true;
        default -> false;
        };
    }

    // ---------------------------------------------------------------------
    // Per-row substitution
    // ---------------------------------------------------------------------


    /**
     * Substitutes {@code ${VAR}} placeholders in a {@link Scalar} operand using the row's driver
     * values. Returns the concrete column-name string with the foreign-dataset prefix preserved.
     *
     * @throws SubstitutionException
     *             if a driver column is absent, the row's value is missing or non-numeric (when the
     *             format spec requires numeric), or the format spec rejects the value.
     */
    public static String substituteScalar(Scalar parsed, EvaluationContext ctx, long row)
    {
        StringBuilder sb = new StringBuilder();
        if (parsed.foreignDataset() != null)
        {
            sb.append(parsed.foreignDataset()).append('.');
        }
        for (Segment seg : parsed.segments())
        {
            appendSegment(sb, seg, ctx, row);
        }
        return sb.toString();
    }


    /**
     * For a {@link Wildcard} operand: substitutes {@code ${VAR}} drivers, then converts each
     * {@code ${*}} to an independent {@code \d+} run, returning a compiled, anchored
     * {@link Pattern} for matching foreign-dataset column names. With <em>n</em> stars the pattern
     * joins the <em>n+1</em> fragment regexes with <em>n</em> {@code \d+} separators; the digit
     * runs are matched independently (no same-index correlation), which is sufficient because
     * callers only test {@link Matcher#matches()}.
     *
     * @throws SubstitutionException
     *             if any driver substitution fails.
     */
    public static Pattern toColumnPattern(Wildcard parsed, @Nullable EvaluationContext ctx,
            long row)
    {
        StringBuilder sb = new StringBuilder("^");
        List<List<Segment>> fragments = parsed.fragments();
        for (int i = 0; i < fragments.size(); i++)
        {
            if (i > 0)
            {
                sb.append("\\d+");
            }
            for (Segment seg : fragments.get(i))
            {
                appendSegmentRegex(sb, seg, ctx, row);
            }
        }
        sb.append('$');
        return Pattern.compile(sb.toString());
    }


    private static void appendSegment(StringBuilder sb, Segment seg, EvaluationContext ctx,
            long row)
    {
        if (seg instanceof Literal(String text))
        {
            sb.append(text);
            return;
        }
        Driver driver = (Driver) seg;
        sb.append(formatDriver(driver, ctx, row));
    }


    private static void appendSegmentRegex(StringBuilder sb, Segment seg,
            @Nullable EvaluationContext ctx, long row)
    {
        if (seg instanceof Literal(String text))
        {
            sb.append(Pattern.quote(text));
            return;
        }
        Driver driver = (Driver) seg;
        // A Driver segment only occurs for operands that have drivers, and those are never
        // compiled with a null ctx when the operand has no drivers.
        sb.append(Pattern
                .quote(formatDriver(driver, java.util.Objects.requireNonNull(ctx, "ctx"), row)));
    }


    /**
     * Reads the driver column on the primary table at the given row and applies the format spec.
     * Mirrors Fix #19's tryNumeric convention: only LONG / DOUBLE typed cells are considered
     * numeric. STRING cells are passed through {@code String.format} as-is.
     */
    private static String formatDriver(Driver driver, EvaluationContext ctx, long row)
    {
        IDataTable table = ctx.getTable();
        DataTableMeta meta = table.getMetaData();
        int colIdx = meta.getColumnIndex(driver.varName());
        if (colIdx < 0)
        {
            throw new SubstitutionException("driver column `" + driver.varName()
                    + "` is not present in dataset `" + meta.getName() + "`");
        }
        IDataValue dv = table.getColumn(colIdx).getDataValue(row);
        if (dv == null || dv.isMissingOrInvalid())
        {
            throw new SubstitutionException(
                    "driver column `" + driver.varName() + "` is missing on row " + row);
        }
        String fmt = driver.formatSpec();
        if (fmt == null)
        {
            // No format → use the value's raw string form. For numeric, strip a trailing ".0"
            // produced by Double.toString on integral doubles.
            return rawString(dv);
        }
        DataValueType t = dv.getType();
        // Numeric path: format with the long value when LONG / DOUBLE.
        if (t == DataValueType.LONG || t == DataValueType.DOUBLE)
        {
            double d = dv.getValueAsDouble();
            if (Double.isNaN(d))
            {
                throw new SubstitutionException("driver column `" + driver.varName()
                        + "` has a non-numeric value on row " + row);
            }
            try
            {
                return String.format(fmt, (long) d);
            }
            catch (IllegalFormatException ife)
            {
                // Fall back to %s style (e.g. fmt is "%s")
                try
                {
                    return String.format(fmt, dv.getValueAsString());
                }
                catch (IllegalFormatException _)
                {
                    throw new SubstitutionException("format spec `" + fmt + "` rejected value " + d
                            + " for driver `" + driver.varName() + "`: " + ife.getMessage());
                }
            }
        }
        // STRING path: pass through to String.format. Reject if the format expects numeric.
        try
        {
            return String.format(fmt, dv.getValueAsString());
        }
        catch (IllegalFormatException ife)
        {
            throw new SubstitutionException(
                    "format spec `" + fmt + "` rejected string value `" + dv.getValueAsString()
                            + "` for driver `" + driver.varName() + "`: " + ife.getMessage());
        }
    }


    private static String rawString(IDataValue dv)
    {
        DataValueType t = dv.getType();
        if (t == DataValueType.LONG || t == DataValueType.DOUBLE)
        {
            double d = dv.getValueAsDouble();
            if (d == Math.floor(d) && !Double.isInfinite(d))
            {
                return Long.toString((long) d);
            }
            return Double.toString(d);
        }
        return dv.getValueAsString();
    }

    // ---------------------------------------------------------------------
    // Exceptions
    // ---------------------------------------------------------------------

    /** Thrown by {@link #parse} for malformed substitution syntax. */
    public static class OperandParseException extends RuntimeException
    {

        private static final long serialVersionUID = 1L;

        public OperandParseException(String message)
        {
            super(message);
        }
    }


    /** Thrown by per-row substitution helpers when the row's drivers cannot resolve. */
    public static class SubstitutionException extends RuntimeException
    {

        private static final long serialVersionUID = 1L;

        public SubstitutionException(String message)
        {
            super(message);
        }
    }


    /** Thrown by {@link #validate(ParsedOperand, String, Position)}. */
    public static class OperatorMismatchException extends RuntimeException
    {

        private static final long serialVersionUID = 1L;

        public OperatorMismatchException(String message)
        {
            super(message);
        }
    }

}
