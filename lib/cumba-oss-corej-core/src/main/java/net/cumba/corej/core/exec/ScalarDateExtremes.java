package net.cumba.corej.core.exec;

import java.util.List;
import java.util.Objects;
import net.cumba.corej.core.expr.eval.ColumnTypeGate;
import net.cumba.corej.core.expr.eval.ComputedVector;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.TypedValue;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;

/**
 * The registry functions {@code earliest_date(a, b)} / {@code latest_date(a, b)} — the earlier /
 * later of two dates, per row ({@code PLAN-scalar-date-extremes}, SDE D1: the names; S1–S7: the
 * contract). {@code earliest_date(a, b)} answers exactly what {@code min_date} answers over a group
 * holding the two cells {@code a} and {@code b}, and {@code latest_date} what {@code max_date}
 * answers: the pair is a two-row block of the one EC-46 / EC-51 accumulator every date extreme
 * shares ({@link Extremes.DateExtreme}), so the two can never drift. Backs the four SEND rules that
 * combine a subject's own first / last dose with its pools' ({@code CDISC-SEND-0204} /
 * {@code -0205} / {@code -0305} / {@code -0306}: {@code earliest_date($own, $pool)}).
 *
 * <p>
 * <b>Arity</b> (S1): exactly two arguments, {@code a} and {@code b}, positional or by name
 * ({@code earliest_date(X, b=Y)} binds, D19a; a name already bound by position is refused, an
 * unknown keyword is a binder load error). No other parameter — in particular no
 * {@code missing_values=} (S8): a per-part {@code indeterminate} stays on the grouped call that
 * feeds it. More than two dates nest, {@code earliest_date(a, earliest_date(b, c))}, which is exact
 * for determinate operands; for partial operands nesting is not the three-way extreme (EC-46 is not
 * associative once an inner pair is undeterminable).
 * </p>
 *
 * <p>
 * <b>Types</b> (S3): both parameters are declared {@code DATE} and the result is typed {@code DATE}
 * ({@code ElementTable}, beside {@code min_date} / {@code max_date}), which is what makes an
 * untagged {@code RFXSTDTC != earliest_date(…)} visible to the mixed-type check; a string literal
 * argument is a {@code PARAMETER_TYPE} finding — write {@code date("…")}. The declared result type
 * is derived from the two inputs: their common declared type, else {@code STRING} (the type a
 * grouped date extreme falls back to). Unequal types are <b>not</b> refused: a numeric cell can
 * never win — {@link Extremes.DateExtreme} takes a winner only when
 * {@code IsoDateBounds.isDetermined} reads a calendar-complete date core, which no number's text is
 * — so the cell handed back under a {@code STRING} declaration is always a character date or a
 * missing, and the {@link RowMax} trap (a numeric cell under a {@code STRING} declaration) cannot
 * arise. A present number beside a date makes the pair undeterminable ({@code MIS}), exactly as
 * {@code min_date} over the same two cells. That is the reachable case: an XLSX / CSV EX whose
 * {@code EXSTDTC} arrives as an Excel serial types the own extreme {@code DOUBLE}, beside the
 * {@code STRING} pool part of a study without POOLDEF — refusing it would ERROR all four SEND rules
 * for a provider artefact. A numeric <b>column</b> read as a date argument is instead
 * <b>observed</b> ({@link ColumnTypeGate#observeIsoConversionRead}, D55, observe-only), as
 * {@code date(NUM)} is.
 * </p>
 *
 * <p>
 * <b>Candidates</b> (S4, D36 / EC-51 Half A): a genuine {@link MissingValue} (any identity) and a
 * blank or whitespace-only cell are not candidates — the other argument decides alone. The
 * extremes' candidate rule, <em>not</em> propagation: {@code earliest_date(.A, 2020-01-01)} is
 * {@code 2020-01-01}, where {@code date_diff_days(.A, …)} would be {@code .A}.
 * </p>
 *
 * <p>
 * <b>The answer</b> (S5, EC-46): the determined candidate that reaches every possible completion of
 * the other wins, and its <b>cell</b> is handed back, identity and type kept; on equal text
 * {@code a} wins. {@code earliest_date("2012-06", "2012-06-01")} is {@code 2012-06-01};
 * {@code earliest_date("2012-06", "2012-06-02")} has no answer (the partial could be the 1st); two
 * partials have no answer; a present unpositionable value ({@code UNK}, {@code 2020-02-30}, a
 * masked day) makes the answer undeterminable — unlike a missing, which is skipped.
 * </p>
 *
 * <p>
 * <b>No answer</b> (S6, SPEC §4(10) / §4(13)): both arguments missing ⇒ their carried identity (one
 * identity ⇒ that cell, two distinct ⇒ {@code MIS}, D85c / D86a —
 * {@link ArithmeticSemantics#combinedMissing} / {@link ArithmeticSemantics#carrierCell}); any other
 * no-answer (blank only, a missing beside a blank, an undeterminable pair) ⇒ the computed
 * {@code MIS} ({@link ScalarSemantics#computedMissing()}); never {@code null}.
 * </p>
 *
 * <p>
 * <b>An indeterminate operand</b> (S7, SDE D2 ruled (a)): there is no indeterminate <em>value</em>
 * — an undeterminable grouped extreme reaches this function as the computed {@code MIS} and is
 * skipped like any missing, so the pair may name the determinate part's date where the exact union
 * would have no answer. For the consumers that ship ({@code !=}, EC-45) that is under-report only:
 * every row the pair reports, the union reports.
 * </p>
 *
 * <p>
 * <b>Per row</b> (S10, P1 (i)): two cell reads, at most four {@code IsoDateBounds} strings inside
 * the accumulator, one small accumulator object; the answer is an input cell — no copy, no boxing,
 * no per-row collection.
 * </p>
 */
public final class ScalarDateExtremes
{

    /** {@code earliest_date(a, b)} — the earlier of two dates. */
    public static final String EARLIEST = "earliest_date";

    /** {@code latest_date(a, b)} — the later of two dates. */
    public static final String LATEST = "latest_date";

    private ScalarDateExtremes()
    {
    }


    /**
     * The {@code EvalFunction} body of {@code earliest_date}: {@code args} are the bound {@code a}
     * and {@code b} vectors.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            the bound argument vectors
     * @return the per-row earlier date, missing where there is no answer
     */
    public static Vector earliest(EvalRun run, List<Vector> args)
    {
        return evaluate(run, args, false);
    }


    /**
     * The {@code EvalFunction} body of {@code latest_date}: {@code args} are the bound {@code a}
     * and {@code b} vectors.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            the bound argument vectors
     * @return the per-row later date, missing where there is no answer
     */
    public static Vector latest(EvalRun run, List<Vector> args)
    {
        return evaluate(run, args, true);
    }


    private static Vector evaluate(EvalRun run, List<Vector> args, boolean findMax)
    {
        Vector a = args.get(0);
        Vector b = args.get(1);
        String context = (findMax ? LATEST : EARLIEST) + "()";
        // D55, observe-only (the class comment): a numeric column read as a date is observed,
        // never refused — it cannot win, so the answer stays a date or a missing.
        ColumnTypeGate.observeIsoConversionRead(a, context);
        ColumnTypeGate.observeIsoConversionRead(b, context);
        DataValueType declared = declaredType(a, b);
        return ComputedVector.typed(run.rowCount(), declared,
                row -> pairExtreme(a.value(row), b.value(row), findMax));
    }


    /**
     * The declared result type: the arguments' common declared type, else {@code STRING} — never a
     * refusal, because a numeric cell can never be the winning cell (the class comment). Decided
     * once per evaluation, never per row.
     *
     * @param a
     *            the first argument
     * @param b
     *            the second argument
     * @return the declared type of the result vector
     */
    static DataValueType declaredType(Vector a, Vector b)
    {
        DataValueType ta = a.declaredType();
        return ta == b.declaredType() ? ta : DataValueType.STRING;
    }


    /**
     * The pair's extreme for one row — a two-row {@link Extremes.DateExtreme} block under the
     * default {@code skip} disposition, the winning cell handed back (see the class comment).
     *
     * @param ta
     *            the first argument's carrier
     * @param tb
     *            the second argument's carrier
     * @param findMax
     *            {@code true} for the later date, {@code false} for the earlier
     * @return the winning input cell, the carried missing when both are missing, or the computed
     *         missing when there is no other answer; never {@code null}
     */
    static IDataValue pairExtreme(TypedValue ta, TypedValue tb, boolean findMax)
    {
        if (ta.missing() != null && tb.missing() != null)
        {
            // S6: both missing — their carried identity (D85c / D86a), the cell that carries it.
            MissingValue combined = Objects
                    .requireNonNull(ArithmeticSemantics.combinedMissing(ta, tb, null));
            return ArithmeticSemantics.carrierCell(combined, ta, tb, null);
        }
        IDataValue ca = ta.cell();
        IDataValue cb = tb.cell();
        // S4 / S5: the shared candidate filter (a missing or blank cell is no candidate) and the
        // EC-46 determinability rule, in the one accumulator min_date / max_date use.
        Extremes.DateExtreme block = new Extremes.DateExtreme(findMax, false);
        block.addCell(ca);
        block.addCell(cb);
        @Nullable
        String best = block.result();
        if (best == null)
        {
            return ScalarSemantics.computedMissing();
        }
        // The winning CELL, identity and type kept; on equal text `a` wins.
        return best.equals(Extremes.extremeCandidate(ca)) ? ca : cb;
    }

}
