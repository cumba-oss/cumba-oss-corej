package net.cumba.corej.core.expr.eval;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.JoinLookup;
import net.cumba.corej.core.exec.ScalarSemantics;
import net.cumba.corej.core.expr.RuleDefinitionException;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.values.DataValueSupport;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;

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
 *
 * <p>
 * ⭐ <b>A name is resolved ONCE per evaluation</b> (review round 1, lane A M3): the column index,
 * the join lookup or the absent default a name stands for is decided on its first row and memoised
 * for the evaluation ({@link Target}), so a literal name, a literal list and a {@code find_vars}
 * list cost one {@code getColumnIndex} per name — not one linear, case-insensitive column scan per
 * row per member — and an absent fold is noted once. A per-row computed name is memoised the same
 * way, keyed by its text.
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
     * @param literal
     *            whether the first hop is a LITERAL (a string or a list of strings written in the
     *            rule): only then is a {@code --} name a specialiser defect (D77b); a DATA-derived
     *            {@code --} name names no column (review round 1, lane A M1)
     * @return the dereferenced vector (declared STRING — rows may resolve to different columns)
     */
    public static Vector vector(EvalRun run, Vector names, boolean numericDefault, boolean literal)
    {
        Resolver resolver = new Resolver(run.ctx(), numericDefault, literal);
        return new ComputedVector(run.rowCount(), DataValueType.STRING,
                row -> resolver.resolve(names.value(row), row));
    }

    /**
     * What a name stands for in this evaluation — decided once ({@link Resolver#target}), read per
     * row ({@link #read}): a constant (absent, not supplied), a column of the evaluation table, or
     * a joined column. Never a {@code null} value (NNL: every value a rule reads is a real value or
     * a {@code MissingValue}).
     */
    private sealed interface Target permits Constant, Primary, Joined
    {

        /** The cell on {@code row}. */
        IDataValue read(EvaluationContext ctx, long row);
    }


    /** A name that reads no cell: its absent / not-supplied default, or the computed missing. */
    private record Constant(IDataValue value) implements Target
    {

        @Override
        public IDataValue read(EvaluationContext ctx, long row)
        {
            return value;
        }
    }


    /**
     * A column of the evaluation table — the primary arm of {@code substitutedScalarCell}, verbatim
     * in effect: {@code resolvedString}'s blank contract (any missing cell ⇒ that cell, identity
     * kept), a numeric cell handed through typed, a present character value as its text (already a
     * member-safe {@code DataValues.of} — no second wrap).
     */
    private record Primary(int colIdx, boolean numericColumn) implements Target
    {

        @Override
        public IDataValue read(EvaluationContext ctx, long row)
        {
            String text = ScalarSemantics.resolvedString(ctx.getTable(), colIdx, row);
            if (text == null || numericColumn)
            {
                return ctx.getTable().getColumn(colIdx).getDataValue(row);
            }
            return DataValues.of(text);
        }
    }


    /** A joined dataset's column, read through {@link JoinLookup#lookupValue}. */
    private record Joined(JoinLookup lookup, String column, boolean numeric) implements Target
    {

        @Override
        public IDataValue read(EvaluationContext ctx, long row)
        {
            return lookup.lookupValue(ctx.getTable(), row, column, numeric);
        }
    }

    /** The most names not reading an evaluation-table column that one evaluation memoises. */
    static final int MAX_UNBOUNDED_NAMES = 1024;

    /** One evaluation's name resolution, memoised by name. */
    static final class Resolver
    {

        private final EvaluationContext ctx;

        private final boolean numericDefault;

        private final boolean literal;

        private final Map<String, Target> targets = new HashMap<>();

        /** The non-{@link Primary} targets memoised so far ({@link #MAX_UNBOUNDED_NAMES}). */
        private int unbounded;

        /** The memo's size — package-private for the cap test. */
        int memoSize()
        {
            return targets.size();
        }


        Resolver(EvaluationContext ctx, boolean numericDefault, boolean literal)
        {
            this.ctx = ctx;
            this.numericDefault = numericDefault;
            this.literal = literal;
        }


        /** One row: a scalar name ⇒ its cell; a list of names ⇒ the list of their cells. */
        Object resolve(TypedValue first, long row)
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
                // NNL §1: a list value holds no null element (the ListValueGuard at its birth
                // site).
                for (Object item : net.cumba.corej.core.exec.ListValueGuard.elements(items))
                {
                    cells.add(member(item, row));
                }
                return cells;
            }
            IDataValue source = first.sourceCell();
            boolean text = source != null ? source.getType() == DataValueType.STRING
                    : payload instanceof String;
            if (!text)
            {
                // Q7: a present non-string VALUE (a numeric cell, a number) names no column — a
                // data problem, not a rule defect (D35), so a computed missing, never an error.
                return ScalarSemantics.computedMissing();
            }
            return target(String.valueOf(payload)).read(ctx, row);
        }


        /** One element of a list first hop: a string is a name; a missing element is kept (Q4). */
        private IDataValue member(Object item, long row)
        {
            MissingValue marker = Primitives.MemberSet.missingIdentityOfMember(item);
            if (marker != null)
            {
                // Q4: a missing NAME in the list stays in the list as that missing (D85c).
                return item instanceof IDataValue dv ? dv
                        : DataValueSupport.getAsDataValue(marker, DataValueType.MISSING);
            }
            String name = item instanceof IDataValue dv
                    ? dv.getType() == DataValueType.STRING ? dv.getValueAsString() : null
                    : item instanceof String s ? s : null;
            return name == null ? ScalarSemantics.computedMissing()
                    : memberCell(target(name).read(ctx, row));
        }


        /**
         * A list member's cell as a member set reads it (§2.7 point 2): a set keys a present member
         * by {@code toString()}, which for a {@code DataValueString} — a joined character cell, and
         * the present {@code ""} an ABSENT or not-supplied name answers — is the QUOTED text, so
         * every present character cell is re-carried as its text ({@code DataValues.of}); a numeric
         * cell keeps its type, a missing one its identity. Review round 2 (engine 1): the re-carry
         * sat in the joined reader only, so an absent member keyed as {@code "\"\""} and
         * {@code TRTA not in colref([…, absent])} fired on a blank probe.
         */
        private static IDataValue memberCell(IDataValue cell)
        {
            return cell.getType() == DataValueType.STRING
                    && TypedValue.missingIdentityOf(cell) == null
                            ? DataValues.of(cell.getValueAsString())
                            : cell;
        }


        private Target target(String name)
        {
            Target known = targets.get(name);
            if (known == null)
            {
                known = DynamicColumnRead.target(ctx, name, numericDefault, literal);
                // Review rounds 2-3 (engine 4): a name that reads a column of the EVALUATION TABLE
                // is memoised always — their number is bounded by its columns. Every other name
                // (absent, not supplied, data-derived `--`, and a dotted name, whose column part
                // is only checked per row by the join) only up to MAX_UNBOUNDED_NAMES, so per-row
                // unique data values cannot grow the map per row.
                if (known instanceof Primary || unbounded++ < MAX_UNBOUNDED_NAMES)
                {
                    targets.put(name, known);
                }
            }
            return known;
        }
    }

    /**
     * The cell {@code name} names on {@code row} — the arms of §2.3, the default of step 4 (for a
     * single read; {@link #vector} memoises the resolution per evaluation).
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
        return target(ctx, name, numericDefault, true).read(ctx, row);
    }

    /** The join-match flag's column spelling ({@code DS._matched_}). */
    public static final String MATCHED_FLAG = "_matched_";

    /**
     * The join a dotted name's qualifier names, matched IGNORING letter case (owner 2026-09-28,
     * register CIT §1: every name match ignores case — review round 2): {@code colref("adsl.X")}
     * reads the {@code ADSL} join, and {@code find_vars("adsl.TRTxxA")} enumerates it.
     *
     * @param ctx
     *            the evaluation context
     * @param qualifier
     *            the qualifier as written
     * @return the join, or {@code null} when the rule declares no such dataset
     */
    public static @Nullable JoinLookup joinedDataset(EvaluationContext ctx, String qualifier)
    {
        Map<String, JoinLookup> joins = ctx.getJoinedDatasets();
        JoinLookup exact = joins.get(qualifier);
        if (exact != null)
        {
            return exact;
        }
        for (Map.Entry<String, JoinLookup> e : joins.entrySet())
        {
            if (e.getKey().equalsIgnoreCase(qualifier))
            {
                return e.getValue();
            }
        }
        return null;
    }


    /** What {@code name} stands for in this evaluation (§2.3 step 4). */
    private static Target target(EvaluationContext ctx, String name, boolean numericDefault,
            boolean literal)
    {
        IDataValue absent = numericDefault ? ScalarSemantics.computedMissing()
                : DataValueSupport.defaultForType(DataValueType.STRING);
        if (name.isEmpty())
        {
            return new Constant(absent);
        }
        String resolved;
        if (literal)
        {
            // D77b: a `--` name in a LITERAL argument reaching evaluation is a specialiser defect
            // — assert, as for every other authored name (colref("--SEQ") is specialised at bind
            // time, D93c).
            resolved = ExprCompiler.resolveDomainPrefix(name, ctx);
        }
        else if (name.startsWith("--"))
        {
            // Review round 1, lane A M1: a DATA-derived `--` name (IDVAR = "--SEQ") is no
            // specialiser defect — no column is spelled that way, so it names no column and takes
            // the site's absent default, as `${IDVAR}` never errored on it either.
            return new Constant(absent);
        }
        else
        {
            resolved = name;
        }
        String upper = resolved.toUpperCase(Locale.ROOT);
        // Step 4: the site's own kind OR the resolved name's rule-wide kind (today's term), the
        // name matched case-insensitively (owner 2026-09-28, CIT §1).
        Set<String> numericColumns = ctx.getNumericExpectedColumns();
        boolean numeric = numericDefault || numericColumns.contains(resolved)
                || numericColumns.contains(upper);
        int dot = resolved.indexOf('.');
        if (dot > 0)
        {
            String column = resolved.substring(dot + 1);
            if (MATCHED_FLAG.equalsIgnoreCase(column))
            {
                if (literal)
                {
                    // Review round 1, lane A L6 — dottedVector's guard, mirrored (a backstop:
                    // ExprCompiler.colrefPlan refuses a literal flag statically): the join-match
                    // flag is a boolean CONDITION (spec §3.3, D88b), never a value.
                    throw new RuleDefinitionException(resolved + " is a boolean condition, not a"
                            + " value — write it bare (e.g. `not " + resolved + "`), never through"
                            + " colref");
                }
                // Review round 2 (engine 3): a DATA-derived name spelling the flag is data, not a
                // rule defect — no column is named `_matched_`, so it takes the absent default.
                return new Constant(absent);
            }
            JoinLookup lookup = joinedDataset(ctx, resolved.substring(0, dot));
            return lookup == null ? new Constant(ExprCompiler.dottedNotSuppliedDefault(numeric))
                    : new Joined(lookup, column, numeric);
        }
        DataTableMeta meta = ctx.getTable().getMetaData();
        int colIdx = meta.getColumnIndex(resolved);
        if (colIdx >= 0)
        {
            DataValueType t = meta.getColumn(colIdx).getType();
            return new Primary(colIdx, t == DataValueType.LONG || t == DataValueType.DOUBLE);
        }
        // Absent from the evaluation table: the authored name's D76 default, under the same
        // eligibility nameRefPlan / valueRefPlan / substitutedScalarCell apply — judged on the
        // upper-cased name, since a column name matches case-insensitively (lane A L5: `zz` was
        // refused as an engine name and read MIS where `ZZ` reads "").
        if (ExprCompiler.absentFoldEnabled && BroadcastFold.isFoldableColumnReference(upper))
        {
            ctx.noteAbsentColumnFold(resolved);
            return new Constant(numeric ? ScalarSemantics.computedMissing()
                    : DataValueSupport.defaultForType(DataValueType.STRING));
        }
        return new Constant(ScalarSemantics.computedMissing());
    }
}
