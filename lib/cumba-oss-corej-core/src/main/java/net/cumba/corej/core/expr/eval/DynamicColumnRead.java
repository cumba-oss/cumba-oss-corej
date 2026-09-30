package net.cumba.corej.core.expr.eval;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.JoinLookup;
import net.cumba.corej.core.exec.ScalarSemantics;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.values.DataValueSupport;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;

/**
 * ⭐ The one resolver of a <b>dynamic column name</b> — {@code colref(<string>)} and
 * {@code colref(<list<string>>)} ({@code PLAN-dynamic-column-functions} §2.3, owner Q14 (b), Q7,
 * Q4). A name handed to {@code colref} as a string resolves <b>exactly as the same name written
 * literally</b> ({@code D72}, {@code NF §3a}, {@code UVC unqualified} UNIFORMITY):
 * <ul>
 * <li>a bare {@code X} reads the <b>evaluation table</b> — the primary after the
 * {@code Child: true} pre-merge ({@code D62b}: a {@code colref(IDVAR)} reaches a pre-merged parent
 * column) — and never a regular join;</li>
 * <li>a dotted {@code DS.X} reads the joined dataset through {@link JoinLookup#lookupValue} exactly
 * as the authored {@code DS.X} does ({@code ExprCompiler.dottedVector}); a dataset that is not
 * supplied takes {@code ExprCompiler.dottedNotSuppliedDefault};</li>
 * <li>the cell keeps its column's own type (G3, PLAN-joined-column-typing's Shape 4) — an ADaM
 * numeric date stays a number;</li>
 * <li>an <b>absent</b> column takes the default the authored name takes at that position: MIS when
 * {@code numericDefault} (the call site stands in a numeric-expected position,
 * {@code TypeExpectations.numericDynamicSites()}) or the resolved name is numeric-expected
 * elsewhere in the rule, {@code ""} otherwise (D76, D34 #3/#4).</li>
 * </ul>
 *
 * <p>
 * ⛔ The {@code ${…}} path ({@code ExprCompiler.substitutedScalarCell}) is <b>not</b> routed here
 * (owner Q8: no behaviour change). The two are built on the same arms and answer cell-identically
 * except at one site shape, pinned as an expected divergence: a numeric position over an absent
 * column whose resolved name is not otherwise numeric-expected — {@code colref} MIS (Q14),
 * {@code ${…}} {@code ""} (Q8).
 * </p>
 *
 * <p>
 * ⚠ A SUPP-QNAM-pivoted qualifier ({@code exec/SuppPivot}) is <b>not</b> read here, as it is not by
 * {@code ${…}}: the pivot is not a column of the evaluation table, and {@code find_vars} does not
 * return pivoted names either (owner Q12). The authored bare name does read the pivot — an
 * asymmetry handed to {@code PLAN-qualified-name-uniformity-review}.
 * </p>
 */
public final class DynamicColumnRead
{

    private DynamicColumnRead()
    {
    }


    /**
     * The per-row dereference of {@code names} — one typed cell per row for a scalar first hop, one
     * list of typed cells per row for a list first hop (Q4: every element kept, a missing member
     * included; the cells stay {@link IDataValue}s so a member set builds the same text members the
     * {@code ${*}} collector does, §2.7).
     *
     * @param run
     *            the evaluation run
     * @param names
     *            the first hop: per row a column name, a list of names, or a missing value
     * @param numericDefault
     *            whether the call site stands in a numeric-expected position — decided ONCE per
     *            evaluation by the caller, never per row
     * @return the dereferenced vector (declared STRING — rows may resolve to different columns)
     */
    public static Vector vector(EvalRun run, Vector names, boolean numericDefault)
    {
        EvaluationContext ctx = run.ctx();
        return new ComputedVector(run.rowCount(), DataValueType.STRING,
                row -> resolve(ctx, names.value(row), row, numericDefault));
    }


    /** One row: a scalar name ⇒ its cell; a list of names ⇒ the list of their cells. */
    private static Object resolve(EvaluationContext ctx, TypedValue first, long row,
            boolean numericDefault)
    {
        if (first.missing() != null)
        {
            // A missing first hop names no column: the answer is that missing, its own cell
            // (D85c — colref(.A) is .A).
            return first.cell();
        }
        Object payload = first.resolved();
        if (payload instanceof Collection<?> items)
        {
            List<IDataValue> cells = new ArrayList<>(items.size());
            // NNL §1: a list value holds no null element (the ListValueGuard at its birth site).
            for (Object item : net.cumba.corej.core.exec.ListValueGuard.elements(items))
            {
                cells.add(member(ctx, item, row, numericDefault));
            }
            return cells;
        }
        return nameOf(ctx, first, row, numericDefault);
    }


    /** A scalar first hop: a present string names a column; anything else names none (Q7). */
    private static IDataValue nameOf(EvaluationContext ctx, TypedValue first, long row,
            boolean numericDefault)
    {
        IDataValue source = first.sourceCell();
        boolean text = source != null ? source.getType() == DataValueType.STRING
                : first.resolved() instanceof String;
        if (!text)
        {
            // Q7: a present non-string VALUE (a numeric cell, a number) names no column — a data
            // problem, not a rule defect (D35), so a computed missing, never an error.
            return ScalarSemantics.computedMissing();
        }
        return cell(ctx, String.valueOf(first.resolved()), row, numericDefault);
    }


    /** One element of a list first hop: a string is a name; a missing element is kept (Q4). */
    private static IDataValue member(EvaluationContext ctx, Object item, long row,
            boolean numericDefault)
    {
        MissingValue marker = Primitives.MemberSet.missingIdentityOfMember(item);
        if (marker != null)
        {
            // Q4: a missing NAME in the list stays in the list as that missing (D85c).
            return item instanceof IDataValue dv ? dv
                    : DataValueSupport.getAsDataValue(marker, DataValueType.MISSING);
        }
        if (item instanceof IDataValue dv)
        {
            return dv.getType() == DataValueType.STRING
                    ? memberCell(cell(ctx, dv.getValueAsString(), row, numericDefault))
                    : ScalarSemantics.computedMissing();
        }
        return item instanceof String s ? memberCell(cell(ctx, s, row, numericDefault))
                : ScalarSemantics.computedMissing();
    }


    /**
     * A list member's cell, as a member set reads it (§2.7 point 2): a member set keys a present
     * member by its {@code toString()}, which for a numeric cell and for {@link DataValues#of} is
     * the value's text, but for a joined {@code DataValueString} is the QUOTED text — so a present
     * character cell is re-carried as its text ({@code DataValues.of}), exactly the member the
     * {@code ${*}} collector's {@code getValueAsString()} builds. A numeric cell keeps its type; a
     * missing cell keeps its identity (Q4).
     */
    private static IDataValue memberCell(IDataValue cell)
    {
        return cell.getType() == DataValueType.STRING && TypedValue.missingIdentityOf(cell) == null
                ? DataValues.of(cell.getValueAsString())
                : cell;
    }


    /**
     * The cell {@code name} names on {@code row} — the arms of §2.3, the default of step 4.
     *
     * @param ctx
     *            the evaluation context
     * @param name
     *            the column name, bare or {@code DS.X}; {@code ""} names no column and takes the
     *            absent default
     * @param row
     *            the row
     * @param numericDefault
     *            whether the call site is numeric-expected (the first term of step 4)
     * @return the cell, never {@code null}
     */
    static IDataValue cell(EvaluationContext ctx, String name, long row, boolean numericDefault)
    {
        if (name.isEmpty())
        {
            return numericDefault ? ScalarSemantics.computedMissing()
                    : DataValueSupport.defaultForType(DataValueType.STRING);
        }
        // D77b: a `--` name reaching evaluation is a specialiser defect — assert, as for every
        // other name (a literal colref("--SEQ") is specialised at bind time, D93c).
        String resolved = ExprCompiler.resolveDomainPrefix(name, ctx);
        // Step 4: the site's own kind OR the resolved name's rule-wide kind (today's term).
        boolean numeric = numericDefault || ctx.getNumericExpectedColumns().contains(resolved);
        int dot = resolved.indexOf('.');
        if (dot > 0)
        {
            JoinLookup lookup = ctx.getJoinedDatasets().get(resolved.substring(0, dot));
            return lookup == null ? ExprCompiler.dottedNotSuppliedDefault(numeric)
                    : lookup.lookupValue(ctx.getTable(), row, resolved.substring(dot + 1), numeric);
        }
        DataTableMeta meta = ctx.getTable().getMetaData();
        int colIdx = meta.getColumnIndex(resolved);
        if (colIdx >= 0)
        {
            // The primary arm of substitutedScalarCell, verbatim in effect: resolvedString's
            // blank contract (any missing cell ⇒ that cell, identity kept), the numeric cell
            // handed through typed, a present character value as its text.
            String text = ScalarSemantics.resolvedString(ctx.getTable(), colIdx, row);
            if (text == null)
            {
                return ctx.getTable().getColumn(colIdx).getDataValue(row);
            }
            DataValueType t = meta.getColumn(colIdx).getType();
            return t == DataValueType.LONG || t == DataValueType.DOUBLE
                    ? ctx.getTable().getColumn(colIdx).getDataValue(row)
                    : DataValues.of(text);
        }
        // Absent from the evaluation table: the authored name's D76 default, under the same
        // eligibility nameRefPlan / valueRefPlan / substitutedScalarCell apply.
        if (ExprCompiler.absentFoldEnabled && BroadcastFold.isFoldableColumnReference(resolved))
        {
            ctx.noteAbsentColumnFold(resolved);
            return numeric ? ScalarSemantics.computedMissing()
                    : DataValueSupport.defaultForType(DataValueType.STRING);
        }
        return ScalarSemantics.computedMissing();
    }
}
