package net.cumba.corej.core.exec;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import net.cumba.corej.core.expr.eval.ComputedVector;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.TypedValue;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;

/**
 * The registry function {@code date_diff_days(name, reference)} — the calendar days from a
 * reference date to a record's date, ported in wave W2b of {@code RUNBOOK-operations-to-functions}
 * ({@code PLAN-operation-replacements} §8) from the retired {@code DATE_DIFF_DAYS} operation.
 * <p>
 * Both parameters are read <b>per row</b>, at one fixed level (D6 / D7): {@code name} is the date
 * column of the evaluated record ({@code --DTC}); {@code reference} is any value expression — a
 * column of the same record, a column read through the rule's declared join ({@code DM.RFSTDTC}),
 * or a <em>named</em> aggregation such as
 * {@code min_date(SJSTDTC, domain=SJ, group=[USUBJID, RPHASE])}, which is how the operation's Mode
 * 2 (a grouped foreign extreme selected by {@code domain=} / {@code group=} /
 * {@code reference_extreme}) is written now. The operation decided per call site which level its
 * {@code reference} was read at — D7's only offender — and that switch is gone: the grouped read is
 * the aggregate's own contract. Its {@code offset} is plain arithmetic
 * ({@code date_diff_days(…) + 1}); its Mode 3 foreign minuend ({@code minuend_domain} /
 * {@code minuend_match}) is a declared join and a dotted {@code name}.
 * </p>
 * <p>
 * The algorithm is the retired evaluator's: with a complete {@code yyyy-MM-dd} prefix on both
 * sides, {@code DAYS.between(reference, date)} — <b>no</b> {@code +1}, unlike {@code dy}. A
 * <b>missing</b> cell on either side answers that missing — the input's own cell, identity kept,
 * two distinct identities collapsing to {@code MIS} (D85c / D86a,
 * {@link ArithmeticSemantics#combinedMissing} / {@link ArithmeticSemantics#carrierCell}) — decided
 * before any parse. A present but short or unparsable input answers the computed missing
 * ({@link ScalarSemantics#computedMissing()}); never {@code null}. So a reference with no answer —
 * the aggregate's absent dataset or column, a group with no candidate, an indeterminate extreme —
 * reaches the Check as a missing, and a populated derived day compared against it is reported
 * (EC-45: nothing skips; applicability is {@code Requirements}' job). The result is a {@code LONG}
 * day count, rendered as the operation's {@code Long} was.
 * </p>
 */
public final class DateDiffDays
{

    /** The function name as authored. */
    public static final String NAME = "date_diff_days";

    private static final int DATE_PREFIX_LENGTH = 10;

    private DateDiffDays()
    {
    }


    /**
     * The {@code EvalFunction} body: {@code args} are the bound {@code name} (date) and
     * {@code reference} vectors.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            the bound argument vectors
     * @return the per-row day count, missing where it cannot be computed
     */
    public static Vector evaluate(EvalRun run, List<Vector> args)
    {
        Vector date = args.get(0);
        Vector reference = args.get(1);
        return new ComputedVector(run.rowCount(), DataValueType.LONG, row ->
        {
            TypedValue td = date.value(row);
            TypedValue tr = reference.value(row);
            MissingValue missing = ArithmeticSemantics.combinedMissing(td, tr, null);
            if (missing != null)
            {
                return ArithmeticSemantics.carrierCell(missing, td, tr, null);
            }
            Long days = daysBetween(text(tr.cell()), text(td.cell()));
            return days != null ? days : ScalarSemantics.computedMissing();
        });
    }


    /**
     * The calendar days from {@code aReference} to {@code aDate}, both truncated to their leading
     * {@code yyyy-MM-dd}: {@code DAYS.between(reference, date)}, no {@code +1}. {@code null} when
     * either side lacks a complete date prefix or does not parse.
     *
     * @param aReference
     *            the reference (subtrahend) date text
     * @param aDate
     *            the record's (minuend) date text
     * @return the day count, or {@code null} when it cannot be computed
     */
    static @Nullable Long daysBetween(String aReference, String aDate)
    {
        if (aDate.length() < DATE_PREFIX_LENGTH || aReference.length() < DATE_PREFIX_LENGTH)
        {
            return null;
        }
        try
        {
            LocalDate ref = LocalDate.parse(aReference.substring(0, DATE_PREFIX_LENGTH));
            LocalDate date = LocalDate.parse(aDate.substring(0, DATE_PREFIX_LENGTH));
            return ChronoUnit.DAYS.between(ref, date);
        }
        catch (DateTimeParseException _)
        {
            return null;
        }
    }


    private static String text(IDataValue cell)
    {
        // The caller has already handed any missing cell through (D85c), so this one is present.
        return cell.getValueAsString();
    }

}
