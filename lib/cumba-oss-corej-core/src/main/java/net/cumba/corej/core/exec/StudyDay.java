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
 * The registry function {@code dy(name, reference)} — the SDTM study day of a record's date
 * relative to a reference date, ported in wave 1 of {@code RUNBOOK-operations-to-functions}
 * ({@code PLAN-function-surface-wave1} phase 2) from the retired {@code DY} operation.
 * <p>
 * Both parameters are column references, per row: the date column of the evaluated record
 * ({@code --DTC}) and the reference date, which the rule reads through its declared join
 * ({@code DM.RFSTDTC}, or {@code DM.RFXSTDTC} / {@code DM.RFCSTDTC}). The reference is
 * <b>required</b>: the retired operation defaulted it to {@code RFSTDTC} and read {@code DM} itself
 * through a hard-coded {@code resolver.resolve("DM")} keyed by {@code USUBJID}, which is the
 * implicit read this port deletes — with a duplicated {@code DM.USUBJID} the operation's
 * last-non-missing-wins subject map and the Check's per-row {@code DM.RFSTDTC} disagreed on which
 * DM record they read ({@code FINDING-dy-binding-vs-check-disagree}). Now the binding and the Check
 * read the same matched record on every row copy the join makes (D32).
 * </p>
 * <p>
 * The algorithm is the retired {@code calculateStudyDay}, unchanged: with a complete
 * {@code yyyy-MM-dd} prefix on both sides, {@code date − reference + 1} when the date is on or
 * after the reference, else {@code date − reference} — there is no day 0. A <b>missing</b> cell on
 * either side answers that missing — the input's own cell, identity kept, two distinct identities
 * collapsing to {@code MIS} (D85c / D86a, {@link ArithmeticSemantics#combinedMissing} /
 * {@link ArithmeticSemantics#carrierCell}, {@code PLAN-missing-identity-nonstring-functions}) —
 * decided <b>before</b> the parse: with {@code X = .A}, {@code dy(X, R)} is {@code .A} whatever
 * present value {@code R} holds (even an incomplete date); with {@code X = .A, R = .B} it is the
 * plain missing, {@code MIS}. Only an all-present but short or unparsable input answers the
 * computed missing ({@link ScalarSemantics#computedMissing()}, the {@code IDataValue} hand-through
 * of {@code PLAN-case-fold-missing-d36}); never a raw {@code MissingValue} payload and never
 * {@code null}. The result renders as the retired operation's {@code Long} did
 * ({@code $value_dy_algorithm=1}, not {@code 1.0}).
 * </p>
 */
public final class StudyDay
{

    /** The function name as authored. */
    public static final String NAME = "dy";

    private static final int DATE_PREFIX_LENGTH = 10;

    private StudyDay()
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
     * @return the per-row study day, missing where it cannot be computed
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
            Long day = studyDay(text(td.cell()), text(tr.cell()));
            return day != null ? day : ScalarSemantics.computedMissing();
        });
    }


    /**
     * SDTM study day: {@code daysBetween(reference, date) + 1} when {@code date >= reference}, else
     * {@code daysBetween(reference, date)} (negative, no day 0). {@code null} when either side
     * lacks a complete {@code yyyy-MM-dd} prefix or does not parse.
     *
     * @param aDate
     *            the record's date text
     * @param aReference
     *            the reference date text
     * @return the study day, or {@code null} when it cannot be computed
     */
    static @Nullable Long studyDay(String aDate, String aReference)
    {
        if (aDate.length() < DATE_PREFIX_LENGTH || aReference.length() < DATE_PREFIX_LENGTH)
        {
            return null;
        }
        try
        {
            LocalDate date = LocalDate.parse(aDate.substring(0, DATE_PREFIX_LENGTH));
            LocalDate ref = LocalDate.parse(aReference.substring(0, DATE_PREFIX_LENGTH));
            long days = ChronoUnit.DAYS.between(ref, date);
            return days >= 0 ? days + 1 : days;
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
