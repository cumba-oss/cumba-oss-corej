package net.cumba.corej.core.exec;

import java.util.BitSet;
import java.util.List;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * The registry function {@code interval_uncertainty_precision_mismatch(name, delimiter=)} — the
 * ISO-8601 interval-of-uncertainty precision comparator, ported in wave 3 of
 * {@code RUNBOOK-operations-to-functions} ({@code PLAN-per-row-functions}) from the retired
 * {@code INTERVAL_UNCERTAINTY_PRECISION_MISMATCH} operation. Backs {@code CDISC-SEND-0070} and its
 * {@code -A} / {@code -B} splits.
 *
 * <p>
 * Per row it answers {@code true} (fires) when the {@code name} value holds the delimiter
 * ({@code /} unless {@code delimiter=} says otherwise) and its two halves carry a
 * <em>different</em> ISO-8601 precision tier, each half measured after its timezone offset and
 * fractional seconds are stripped — the comparison is of the <em>representation</em>, so a UTC
 * offset on one side only is not a precision difference. No delimiter, or a blank half, answers
 * {@code false}. A missing or blank value answers {@code false} too, which is also the whole answer
 * for an absent column (D4: an absent column is a constant of its type's default,
 * {@code D34 #3/#4}) — the retired operation answered {@code null} there, and the rule executed
 * with no finding either way.
 * </p>
 *
 * <p>
 * {@code name} is a column reference, so the {@code --} of {@code --DTC} resolves through the
 * shared typed-parameter path and a quoted name is a string literal that fails to load (R1). The
 * delimiter is read per row, so any expression of the right type may supply it; a missing or empty
 * delimiter is {@code /}, the retired default.
 * </p>
 */
public final class IntervalPrecision
{

    /** The function name as authored. */
    public static final String NAME = "interval_uncertainty_precision_mismatch";

    private static final String DEFAULT_DELIMITER = "/";

    private IntervalPrecision()
    {
    }


    /**
     * The {@code EvalFunction} body: {@code args} are the bound {@code name} vector and the
     * (optional, may be {@code null}) {@code delimiter} vector.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            the bound argument vectors
     * @return the rows whose interval halves differ in precision
     */
    public static BitSet evaluate(EvalRun run, List<Vector> args)
    {
        Vector value = args.get(0);
        // The compiler hands an absent optional parameter through as a null slot.
        @Nullable
        Vector delimiter = args.get(1);
        int rowCount = run.rowCount();
        BitSet out = new BitSet(rowCount);
        for (int row = 0; row < rowCount; row++)
        {
            if (mismatch(text(value.value(row).cell()), delimiterAt(delimiter, row)))
            {
                out.set(row);
            }
        }
        return out;
    }


    private static String delimiterAt(@Nullable Vector delimiter, int row)
    {
        if (delimiter == null)
        {
            return DEFAULT_DELIMITER;
        }
        String d = text(delimiter.value(row).cell());
        return d.isEmpty() ? DEFAULT_DELIMITER : d;
    }


    /**
     * Whether an ISO-8601 interval-of-uncertainty value's two halves (split on the first
     * {@code delimiter}) carry different precision tiers. No delimiter, or either half blank, ⇒
     * {@code false} (no fire).
     *
     * @param value
     *            the value text, {@code ""} for a blank or missing cell
     * @param delimiter
     *            the non-empty delimiter
     * @return whether the halves' precision tiers differ
     */
    static boolean mismatch(String value, String delimiter)
    {
        int cut = value.indexOf(delimiter);
        if (cut < 0)
        {
            return false;
        }
        String head = value.substring(0, cut);
        String tail = value.substring(cut + delimiter.length());
        if (head.isEmpty() || tail.isEmpty())
        {
            return false;
        }
        return halfPrecision(head) != halfPrecision(tail);
    }


    /**
     * Precision tier of one half of an interval of uncertainty.
     * {@link ScalarSemantics#detectIsoPrecision} buckets purely on string length and is documented
     * as operating on an already-timezone-stripped value, so a UTC offset or a fractional-seconds
     * tail carried on one half only would otherwise read as a precision difference
     * ({@code 2003-12-15T10:00+02:00} is length 22 ⇒ tier 19, against length 16 ⇒ tier 16 for
     * {@code 2003-12-15T10:30}, though both are minute precision). Normalises exactly as
     * {@code CalendarDates.isValidDate} does.
     *
     * <p>
     * ⚠ Deliberately <em>not</em> {@code IsoDateBounds.core}: that normalisation applies the offset
     * instant-preserving, which can re-render the value into a different tier. SEND70 asks about
     * the <em>representation</em> of the two halves ("the completeness of the representation … must
     * be the same on both sides of the solidus"), not about the instant they denote.
     * </p>
     */
    private static int halfPrecision(String half)
    {
        return ScalarSemantics.detectIsoPrecision(
                ScalarSemantics.stripFractionalSeconds(ScalarSemantics.stripTimezone(half)));
    }


    private static String text(IDataValue cell)
    {
        return cell.isMissingOrInvalid() ? "" : cell.getValueAsString();
    }

}
