package net.cumba.corej.core.exec;

import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.TypedValue;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.datatable.values.DataValueSupport;
import net.cumba.datatable.values.DataValueType;
import org.jspecify.annotations.Nullable;

/**
 * {@code PLAN-column-typed-function-absent-type} A1 — <b>a column-typed function whose column (or
 * dataset) is absent answers that column's use-derived default</b>, as the column would if it were
 * read directly (owner, 2026-10-05: <i>"a function result that is untyped because it's type is
 * related to an absent variable, can be handled in the same way as the variable would be handled if
 * it would be used directly"</i>).
 *
 * <p>
 * The functions are {@code read_value} (absent {@code domain=} dataset or column), {@code max} over
 * a column (absent dataset or column), {@code row_max} (no column matches its pattern) and, since
 * the plan's fold (A5), {@code value()} (the cursor names a Define-universe variable the data
 * lacks; a {@code null} cursor names no variable and stays {@code MIS}). The default is the one a
 * directly read absent column folds to (D34 #3/#4, D76, D76a): the all- {@code MissingValue.MIS}
 * constant where the call site stands in a <b>numeric-expected</b> position of the rule — through a
 * binding, the binding's uses (A2) — and the all-{@code ""} constant everywhere else: a character
 * position, conflicting positions resolve to numeric (D76a: numeric when any position expects
 * numeric), no typed position at all to {@code ""}.
 * </p>
 *
 * <p>
 * ⛔⛔ <b>The kind is read once per evaluation, from the run's context — never at compile time, never
 * per row, never in a cache key.</b> A compiled plan is memoised by structural {@code Expr} across
 * rules ({@code NativeExprEvaluator.CACHE}), so a kind bound at compile time would carry one rule's
 * expectation into another; the grouped functions' per-execution memo ({@code GroupedAggregate})
 * holds the grouping, which knows only that the column is absent, and the kind is applied after it.
 * The site is the call expression itself ({@code TypeExpectations.numericDynamicSites()}), as
 * {@code colref}'s is (DCF Q14 (b)).
 * </p>
 */
public final class ColumnTypedAbsence
{

    /**
     * D34 #4: the constant a directly read absent <b>numeric</b> column folds to — the all-MIS
     * broadcast ({@code ExprCompiler}'s {@code ALL_MISSING}, declared {@code MISSING}).
     */
    private static final ConstVector NUMERIC = ConstVector.of(null);

    /**
     * D34 #3: the constant a directly read absent <b>character</b> (or untyped) column folds to —
     * the present empty string, the carrier {@code read_value} has answered for an absent column
     * since runbook W2a.
     */
    private static final ConstVector CHARACTER = GroupedAggregate.constantCell(DataValueType.STRING,
            DataValueSupport.defaultForType(DataValueType.STRING));

    /**
     * {@code value()}'s answer when the variable cursor names a column the evaluated table lacks,
     * before its call site resolves it ({@link #cursorValueAtSite}): one shared instance, so the
     * resolution is an identity test, distinct from the {@code null}-cursor answer (which names no
     * variable and stays {@code MIS}). Its own value is the all-{@code MIS} broadcast
     * ({@code ConstVector.of(null)}) the function answered before the fold — what a caller that
     * never resolves it reads.
     */
    public static final ConstVector ABSENT_CURSOR_COLUMN = new ConstVector(null,
            DataValueType.MISSING, TypedValue.resolved(DataValueType.MISSING, null));

    private ColumnTypedAbsence()
    {
    }


    /**
     * The compiler's {@code value()} arm: {@code evaluated} as the registry function answered it,
     * with {@link #ABSENT_CURSOR_COLUMN} resolved to the call site's use-derived default —
     * {@code MIS} at a numeric-expected site, {@code ""} otherwise (A1 / A5). One identity test
     * and, only for an absent cursor column, one set lookup per evaluation; never per row.
     *
     * @param evaluated
     *            what the registry function answered ({@code null} propagates)
     * @param run
     *            the evaluation run
     * @param site
     *            the authored {@code value()} call
     * @return the answer
     */
    // Identity is the point: one shared instance marks "the cursor names an absent column", and an
    // equal value computed any other way (the null cursor's all-MIS) must not be mistaken for it.
    @SuppressWarnings("ReferenceEquality")
    public static @Nullable Vector cursorValueAtSite(@Nullable Vector evaluated, EvalRun run,
            Expr.Call site)
    {
        return evaluated == ABSENT_CURSOR_COLUMN ? answer(run, site) : evaluated;
    }


    /**
     * Whether the call site stands in a numeric-expected position of the evaluated rule — one set
     * lookup, made once per evaluation.
     *
     * @param run
     *            the evaluation run (its context carries the rule's numeric-expected sites)
     * @param site
     *            the column-typed call, or {@code null} for a caller that has none (a hand-built
     *            spec), which takes the untyped default
     * @return {@code true} when an absent column read at this site defaults to {@code MIS}
     */
    static boolean numericSite(EvalRun run, Expr.@Nullable Call site)
    {
        return site != null && run.ctx().getNumericExpectedDynamicSites().contains(site);
    }


    /**
     * The answer of the column-typed call {@code site} whose column (or dataset) is absent: the
     * all-MIS constant at a numeric-expected site, the all-{@code ""} constant otherwise.
     *
     * @param run
     *            the evaluation run
     * @param site
     *            the column-typed call, or {@code null} (untyped)
     * @return the constant answer for every row
     */
    public static ConstVector answer(EvalRun run, Expr.@Nullable Call site)
    {
        return numericSite(run, site) ? NUMERIC : CHARACTER;
    }
}
