package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.cumba.corej.core.expr.eval.ColumnTypeGate;
import net.cumba.corej.core.expr.eval.ColumnTypeMismatchException;
import net.cumba.corej.core.expr.eval.ComputedVector;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.TypedValue;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;

/**
 * The registry function {@code row_max(name_pattern=)} — the per-record horizontal maximum over the
 * columns whose names match a regular expression, ported in wave 3 of
 * {@code RUNBOOK-operations-to-functions} ({@code PLAN-per-row-functions}) from the retired
 * {@code ROW_MAX} operation (EC-8). Backs {@code CDISC-AD0084} / {@code PMDA-AD0084}
 * ({@code row_max(name_pattern="^TR(0[1-9]|[1-9][0-9])EDT$")}, the latest {@code TRxxEDT}).
 *
 * <p>
 * {@code name_pattern} is the function's only parameter (runbook R6: RETIRE-both — no {@code name}
 * target); it is a static string literal (a load error otherwise, and for a regex that does not
 * compile) matched against the evaluated table's column names in full and case-insensitively (owner
 * ruling 2026-09-28, CIT §1) — D92b's variable-set selector, resolved per dataset.
 * </p>
 *
 * <p>
 * <b>Per row</b> the candidates are the matched cells that are populated (EC-51's shared filter,
 * {@link Extremes#extremeCandidate}: not missing, not blank or whitespace-only). ⭐ They rank <b>by
 * the matched columns' declared type</b>, decided once per dataset, never per row ({@code D-W1-2a},
 * as the ruling audit applies it to W5's {@code max}): a Num column set takes the numeric maximum
 * ({@code ±0} one value, D84); a Char set the shared string branch ({@code genericStringExtreme},
 * EC-46: the date rule when every candidate is a positionable ISO date, plain lexicographic order
 * otherwise). A matched column with no candidate in <em>any</em> row takes no part in that decision
 * (round 2 H2: a CSV / XLSX provider types an all-blank column {@code STRING}, so an all-blank
 * {@code TR02EDT} beside a numeric {@code TR01EDT} is not a mixed set). A set in which Num
 * <em>and</em> Char columns both carry values is a {@link ColumnTypeMismatchException} — the rule
 * ERRORs (column-type doctrine), since the two have no common order and a per-row choice could hand
 * a numeric cell back under a declared {@code STRING} vector. A textual value is never parsed as a
 * number — the retired reducer did, so a row of CHAR {@code "9"} / {@code "10"} answered
 * {@code "10"} and now answers {@code "9"} (named movement, population 0: both corpus rules read
 * ADaM {@code TRxxEDT}, which is numeric). On a tie the first candidate in column order wins. The
 * winning <b>cell</b> is returned, identity and type kept.
 * </p>
 *
 * <p>
 * <b>No answer</b>: a row whose matched cells are all missing answers their combined missing
 * identity (the first cell carrying it, {@code MIS} when identities differ — the runbook's n-ary
 * hand-through, {@link ArithmeticSemantics#combineIdentities}); a row with populated-but-blank
 * cells only, an indeterminate date extreme (EC-46) or a table with no matching column answers the
 * computed missing ({@link ScalarSemantics#computedMissing()}). The retired operation omitted those
 * rows' keys (a {@code null}), and every consuming Check guards {@code not empty($trxx_max)}, so
 * both fold alike.
 * </p>
 */
public final class RowMax
{

    /** The function name as authored. */
    public static final String NAME = "row_max";

    /** Its one parameter. */
    public static final String PATTERN_PARAMETER = "name_pattern";

    private RowMax()
    {
    }


    /**
     * Compiles {@code name_pattern} the one way both the load-time check and the evaluation use.
     *
     * @param pattern
     *            the authored regular expression
     * @return the case-insensitive pattern
     * @throws java.util.regex.PatternSyntaxException
     *             when the expression does not compile
     */
    public static Pattern compile(String pattern)
    {
        return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
    }


    /**
     * The {@code EvalFunction} body: {@code args} holds the bound {@code name_pattern} vector.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            the bound argument vectors
     * @return the per-row maximum
     */
    public static Vector evaluate(EvalRun run, List<Vector> args)
    {
        Pattern pattern = compile(args.get(0).value(0).cell().getValueAsString());
        IDataTable table = run.ctx().getTable();
        DataTableMeta meta = table.getMetaData();
        List<IDataTableColumn> matched = new ArrayList<>();
        List<String> numericNames = new ArrayList<>();
        List<String> characterNames = new ArrayList<>();
        DataValueType numericType = null;
        for (int c = 0; c < meta.getColumnCount(); c++)
        {
            String name = meta.getColumn(c).getName();
            if (!pattern.matcher(name).matches())
            {
                continue;
            }
            matched.add(table.getColumn(c));
            DataValueType type = meta.getColumn(c).getType();
            ColumnTypeGate.Kind kind = ColumnTypeGate.kindOf(type);
            if (kind == ColumnTypeGate.Kind.NUMERIC)
            {
                numericNames.add(name);
                numericType = numericType == null || numericType == type ? type
                        : DataValueType.DOUBLE;
            }
            else if (kind == ColumnTypeGate.Kind.CHARACTER)
            {
                characterNames.add(name);
            }
        }
        boolean numeric = numericType != null;
        if (!numericNames.isEmpty() && !characterNames.isEmpty())
        {
            // Combined review of runbook W2–W8, round 2 H2: a column with no candidate in ANY
            // row answers nothing and takes no part in the type decision. A CSV / XLSX provider
            // types a column STRING unless a sampled cell is numeric evidence, so an all-blank
            // TR02EDT arrives as Char beside a numeric TR01EDT — that is not a mixed set. One
            // early-exit scan per column, once per dataset, only when both kinds matched.
            List<String> liveNumeric = candidateBearing(table, numericNames);
            List<String> liveCharacter = candidateBearing(table, characterNames);
            if (!liveNumeric.isEmpty() && !liveCharacter.isEmpty())
            {
                // Column-type doctrine (PLAN-column-type-conformance: a type mismatch ERRORs): a
                // Num and a Char column have no common order — ranking per row would compare a
                // number with a text on one row and not on the next, and could hand a numeric
                // cell back under a declared STRING vector (combined review W3/W4b L2).
                throw new ColumnTypeMismatchException("column-type mismatch: " + NAME + "("
                        + PATTERN_PARAMETER + "=\"" + pattern.pattern()
                        + "\") matches Num column(s) " + liveNumeric + " and Char column(s) "
                        + liveCharacter + " of dataset " + meta.getName()
                        + " that both carry values; a row maximum needs one column type");
            }
            // The kind that carries values decides; with none anywhere every row answers a
            // missing either way, and the Num kind is kept.
            numeric = !liveNumeric.isEmpty() || liveCharacter.isEmpty();
        }
        // The candidate-free columns stay in the set: they contribute no candidate on any row,
        // and their missing cells still combine into a row's missing identity.
        IDataTableColumn[] columns = matched.toArray(new IDataTableColumn[0]);
        // ⭐ The branch is decided ONCE, from the matched set's DECLARED types — never per row:
        // a Num set ranks numerically, anything else (a Char set, no column) by the string branch.
        DataValueType declared = numeric && numericType != null ? numericType
                : DataValueType.STRING;
        boolean numericBranch = numeric;
        return ComputedVector.typed(run.rowCount(), declared,
                row -> rowMax(columns, row, numericBranch));
    }


    /**
     * The members of {@code names} that carry a candidate ({@link Extremes#extremeCandidate}: not
     * missing, not blank) in at least one row of {@code table} — each column scanned until its
     * first candidate.
     */
    private static List<String> candidateBearing(IDataTable table, List<String> names)
    {
        DataTableMeta meta = table.getMetaData();
        long rowCount = table.getRowCount();
        List<String> live = new ArrayList<>(names.size());
        for (String name : names)
        {
            IDataTableColumn column = table.getColumn(meta.getColumnIndex(name));
            for (long r = 0; r < rowCount; r++)
            {
                IDataValue cell = column.getDataValue(r);
                if (TypedValue.missingIdentityOf(cell) == null
                        && Extremes.extremeCandidate(cell) != null)
                {
                    live.add(name);
                    break;
                }
            }
        }
        return live;
    }


    /**
     * The maximum of one row over {@code columns} (see the class comment).
     *
     * @param columns
     *            the matched columns
     * @param row
     *            the row
     * @param numeric
     *            whether the matched set is declared numeric (decided once per dataset by
     *            {@link #evaluate}); otherwise the string branch ranks the candidates
     * @return the winning cell, a carried missing, or the computed missing
     */
    static IDataValue rowMax(IDataTableColumn[] columns, long row, boolean numeric)
    {
        List<IDataValue> candidates = new ArrayList<>(columns.length);
        List<String> texts = new ArrayList<>(columns.length);
        boolean allMissing = columns.length > 0;
        MissingValue combined = null;
        for (IDataTableColumn column : columns)
        {
            IDataValue cell = column.getDataValue(row);
            MissingValue missing = TypedValue.missingIdentityOf(cell);
            if (missing != null)
            {
                combined = combined == null ? missing
                        : ArithmeticSemantics.combineIdentities(combined, missing);
                continue;
            }
            allMissing = false;
            String raw = Extremes.extremeCandidate(cell);
            if (raw == null)
            {
                continue; // a present blank / whitespace-only cell is no candidate (EC-51)
            }
            if (numeric && !(cell.getValue() instanceof Number))
            {
                // A declared-Num column handing back a non-numeric present cell breaks the
                // column's own type contract; a guess at an order would be a silent verdict.
                throw new ColumnTypeMismatchException("column-type mismatch: " + NAME
                        + " over a Num column set read the non-numeric value '" + raw + "'");
            }
            candidates.add(cell);
            texts.add(raw);
        }
        if (candidates.isEmpty())
        {
            return allMissing ? carrierOf(combined, columns, row)
                    : ScalarSemantics.computedMissing();
        }
        if (numeric)
        {
            return numericMax(candidates);
        }
        String winner = Extremes.genericStringExtreme(texts, true);
        if (winner == null)
        {
            return ScalarSemantics.computedMissing(); // EC-46: indeterminate
        }
        return candidates.get(texts.indexOf(winner));
    }


    private static IDataValue carrierOf(@Nullable MissingValue combined, IDataTableColumn[] columns,
            long row)
    {
        if (combined == null)
        {
            return ScalarSemantics.computedMissing();
        }
        // The first cell that carries the combined identity (identity kept, D85c); none when two
        // distinct identities collapsed to MIS.
        for (IDataTableColumn column : columns)
        {
            IDataValue cell = column.getDataValue(row);
            if (TypedValue.missingIdentityOf(cell) == combined)
            {
                return cell;
            }
        }
        return ScalarSemantics.computedMissing();
    }


    private static IDataValue numericMax(List<IDataValue> candidates)
    {
        IDataValue best = candidates.get(0);
        for (int i = 1; i < candidates.size(); i++)
        {
            IDataValue cell = candidates.get(i);
            if (compareNumeric((Number) cell.getValue(), (Number) best.getValue()) > 0)
            {
                best = cell;
            }
        }
        return best;
    }


    private static int compareNumeric(Number a, Number b)
    {
        if (a instanceof Long al && b instanceof Long bl)
        {
            return Long.compare(al, bl);
        }
        return Double.compare(normalizeZero(a.doubleValue()), normalizeZero(b.doubleValue()));
    }


    private static double normalizeZero(double d)
    {
        return d == 0.0 ? 0.0 : d;
    }

}
