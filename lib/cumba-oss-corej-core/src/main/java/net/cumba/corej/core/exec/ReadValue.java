package net.cumba.corej.core.exec;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.NativeExprEvaluator;
import net.cumba.corej.core.expr.eval.ReadValueAmbiguityException;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.corej.core.expr.typed.TypeExpectations;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.DataValueSupport;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.GroupKeyPolicy;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * The registry function {@code read_value(X, domain=D, filter=(…), mode="FIRST")} — a generic
 * filtered cross-dataset read, owner rulings D12 / D13 / D10 (2026-09-22), built in runbook W2a
 * ({@code PLAN-operation-replacements} §2.2) as the replacement of the retired
 * {@code ts_parameter_value} operation.
 *
 * <p>
 * It is a <b>compiler-dispatched</b> value call ({@code CompilerDispatchedCalls}, the
 * {@code max_value_length} shape): its arguments name columns of {@code D}, never of the dataset
 * under evaluation, so they are read from the call by {@link #spec} at load time rather than
 * compiled as vectors of the primary. {@code X} is a bare column reference of {@code D} (a quoted
 * name is a string, never a column — R1; a dotted or {@code $} reference and a {@code --} name are
 * load errors, {@code domain=} already names the dataset); {@code domain=} is the bare dataset
 * reference {@code TS} or the quoted {@code "TS"} (D10, both spellings); {@code filter=} is a
 * boolean expression over {@code D}'s own columns (right-side only, spec §3.3's choice — a dotted,
 * {@code $} or {@code --} reference inside it is a load error), evaluated against a
 * {@code D}-scoped context exactly as {@code Match_Datasets.Filter} is ({@code MatchFilter});
 * {@code mode=} is a required string literal, because the row-selection policy is authored, never
 * inferred (D13).
 * </p>
 *
 * <p>
 * Modes: {@code FIRST} — the first qualifying row in dataset order (what {@code ts_parameter_value}
 * did); {@code ONLY} — asserts exactly one qualifying row, two or more <b>ERROR</b> the rule
 * ({@link ReadValueAmbiguityException}, {@code RuleRunner}'s ninth ERROR site); {@code MIN} /
 * {@code MAX} — the smallest / largest {@code X} over the qualifying rows, ranked by the engine's
 * one ordering ({@link GroupedPredicates#compareOrdering}: a missing below every value, missings by
 * byte, numbers numerically with ±0 folded, text as text — D34 #5, D-W1-2a), no declared-type gate
 * (Q3). The answer is one dataset-level value broadcast to every row (R2): the selected row's own
 * cell, identity kept (a missing {@code X} answers that missing, D85c); <b>no qualifying row
 * answers {@code X}'s type default</b> in {@code D} (char {@code ""}, numeric
 * {@code MissingValue.MIS} — D13, D34 #3/#4) and is never an error; an absent {@code D} or an
 * {@code X} that is not a column of {@code D} answers the absent-joined-column default
 * ({@link JoinLookup#absentJoinedColumnValue}, {@code ""} — a binding carries no column-level type
 * expectation to consult, plan §2.2 D-W2-9).
 * </p>
 */
public final class ReadValue
{

    /** The function name as authored. */
    public static final String NAME = "read_value";

    private static final Set<String> KEYWORDS = Set.of("domain", "filter", "mode", "group",
            "keep_missings");

    private ReadValue()
    {
    }

    /** The authored row-selection policy (D13). */
    public enum Mode
    {
        /** The first qualifying row in dataset order. */
        FIRST,
        /** Exactly one qualifying row; more is a rule ERROR. */
        ONLY,
        /** The smallest {@code X} over the qualifying rows. */
        MIN,
        /** The largest {@code X} over the qualifying rows. */
        MAX
    }


    /**
     * The read as authored: the column of {@code D} to read, the dataset, the optional filter over
     * {@code D}'s rows and the mode.
     *
     * @param column
     *            {@code X}
     * @param dataset
     *            {@code D}
     * @param filter
     *            the boolean filter over {@code D}'s own columns, or {@code null} for every row
     * @param mode
     *            the row-selection policy
     */
    public record Spec(String column, String dataset, @Nullable Expr filter, Mode mode,
            List<String> group, GroupKeyPolicy policy, String canonical)
    {

        public Spec
        {
            group = List.copyOf(group);
        }


        /**
         * The ungrouped read (W2a's shape), for callers that build a spec by hand.
         *
         * @param column
         *            {@code X}
         * @param dataset
         *            {@code D}
         * @param filter
         *            the filter, or {@code null}
         * @param mode
         *            the row-selection policy
         */
        public Spec(String column, String dataset, @Nullable Expr filter, Mode mode)
        {
            this(column, dataset, filter, mode, List.of(), GroupKeyPolicy.KEEP_MISSING_KEYS,
                    NAME + "(" + column + ", domain=" + dataset + ", mode=\"" + mode + "\")");
        }


        /**
         * Reads the value for this run: one dataset-level cell, broadcast to every row of the
         * evaluated dataset.
         *
         * @param run
         *            the evaluation run (its context resolves {@code D})
         * @return the broadcast value
         */
        public Vector evaluate(EvalRun run)
        {
            if (!group.isEmpty())
            {
                // W5 (PLAN-grouped-aggregate-functions D-W5-9, owner D14): the per-group read —
                // the mode's selection within each group of D, broadcast to the primary by key
                // (GroupedAggregate); a primary row with no group answers X's type default
                // (D13's no-match answer), never an error.
                return GroupedAggregate.broadcast(run, NAME,
                        new Expr.Ref(column, OperandKind.COLUMN), dataset, filter, group, policy,
                        canonical, this::selectWithin, DataValueSupport::defaultForType);
            }
            EvaluationContext ctx = run.ctx();
            // F-W8-2: a `--` in the domain= literal resolves as every other dataset-name position
            // does (DatasetIdentity.resolveWildcard, Fix #33).
            IDataTable table = SplitDomainResolution.resolveTableOrThrow(ctx.getDatasetResolver(),
                    GroupedAggregate.datasetName(dataset, ctx), ctx.getRuleId());
            if (table == null)
            {
                return broadcast(DataValueType.STRING, JoinLookup.absentJoinedColumnValue(false));
            }
            int colIdx = table.getMetaData().getColumnIndex(column);
            if (colIdx < 0)
            {
                return broadcast(DataValueType.STRING, JoinLookup.absentJoinedColumnValue(false));
            }
            DataValueType type = table.getMetaData().getColumn(colIdx).getType();
            int rowCount = Math.toIntExact(table.getRowCount());
            BitSet keep;
            if (filter == null)
            {
                keep = new BitSet(rowCount);
                if (rowCount > 0)
                {
                    keep.set(0, rowCount);
                }
            }
            else
            {
                // D76: the filter is its own expression, so its own type expectations decide the
                // absent-column default — the MatchFilter shape, verbatim. suppMerge(false): the
                // declared SUPP merge serves the PRIMARY table only (PLAN-operation-replacements
                // §2.3 / §7) — a bare name in filter= is a column of D, never a qualifier of D's
                // own SUPP-- (combined review of runbook W2–W8, W2 M3).
                TypeExpectations expectations = TypeExpectations.of(List.of(filter));
                EvaluationContext filterCtx = EvaluationContext.builder().table(table)
                        .ruleId(ctx.getRuleId()).suppMerge(false)
                        .domainName(table.getMetaData().getName())
                        .datasetResolver(ctx.getDatasetResolver())
                        .numericExpectedColumns(expectations.numericDefaultColumns())
                        .numericExpectedDynamicSites(expectations.numericDynamicSites()).build();
                keep = NativeExprEvaluator.evaluate(filter, filterCtx);
            }
            int selected = select(table, colIdx, keep);
            IDataValue cell = selected < 0 ? DataValueSupport.defaultForType(type)
                    : table.getDataValue(selected, colIdx);
            return broadcast(type, cell);
        }


        private int select(IDataTable table, int colIdx, BitSet keep)
        {
            return switch (mode)
            {
            case FIRST -> keep.nextSetBit(0);
            case ONLY ->
            {
                int matches = keep.cardinality();
                if (matches > 1)
                {
                    throw new ReadValueAmbiguityException(column, dataset, matches);
                }
                yield keep.nextSetBit(0);
            }
            case MIN, MAX -> extreme(table, colIdx, keep);
            };
        }


        private int extreme(IDataTable table, int colIdx, BitSet keep)
        {
            int best = -1;
            for (int r = keep.nextSetBit(0); r >= 0; r = keep.nextSetBit(r + 1))
            {
                if (best < 0)
                {
                    best = r;
                    continue;
                }
                int cmp = GroupedPredicates.compareOrdering(table.getDataValue(r, colIdx),
                        table.getDataValue(best, colIdx));
                if (mode == Mode.MIN ? cmp < 0 : cmp > 0)
                {
                    best = r;
                }
            }
            return best;
        }


        /**
         * The one dataset-level answer, broadcast as a constant (combined review of runbook W2–W8,
         * XCUT PERF 2): a {@code ConstVector} pays no per-row carrier and is what the dataset-level
         * hand-over reads for free.
         */
        private static Vector broadcast(DataValueType type, IDataValue cell)
        {
            return GroupedAggregate.constantCell(type, cell);
        }


        /**
         * The grouped selection: the mode applied to the block's kept rows. {@code ONLY} asserts
         * exactly one qualifying row <em>per group</em> and reports how many the group kept. A
         * group whose rows the filter all drops answers {@code X}'s type default — D13's "no
         * qualifying row ⇒ {@code X}'s type default in {@code D}" (char {@code ""}, numeric
         * {@code MissingValue.MIS}; {@code PLAN-operation-replacements} §2.2, D-W5-9), the same
         * answer a primary row with no group at all reads. Until the combined review of runbook
         * W2–W8 (W2 M1) it answered the computed missing, so a subject whose rows were all filtered
         * out read {@code MIS} while a subject with no row read {@code ""}.
         */
        private IDataValue selectWithin(java.util.function.IntFunction<IDataValue> cellAt,
                int[] rows, @Nullable BitSet keep, DataValueType type)
        {
            int best = -1;
            int matches = 0;
            for (int i = 0; i < rows.length; i++)
            {
                int r = rows[i];
                if (keep != null && !keep.get(r))
                {
                    continue;
                }
                matches++;
                if (best < 0)
                {
                    best = r;
                    if (mode == Mode.FIRST)
                    {
                        break;
                    }
                    continue;
                }
                if (mode == Mode.ONLY)
                {
                    // The true cardinality, as the ungrouped read reports it (W2 L1): the rows
                    // after the second match are counted before the rule ERRORs.
                    throw new ReadValueAmbiguityException(column, dataset,
                            matches + keptAfter(rows, i + 1, keep));
                }
                int cmp = GroupedPredicates.compareOrdering(cellAt.apply(r), cellAt.apply(best));
                if (mode == Mode.MIN ? cmp < 0 : cmp > 0)
                {
                    best = r;
                }
            }
            return best < 0 ? DataValueSupport.defaultForType(type) : cellAt.apply(best);
        }


        private static int keptAfter(int[] rows, int from, @Nullable BitSet keep)
        {
            if (keep == null)
            {
                return rows.length - from;
            }
            int kept = 0;
            for (int i = from; i < rows.length; i++)
            {
                if (keep.get(rows[i]))
                {
                    kept++;
                }
            }
            return kept;
        }
    }

    /**
     * Reads the call at load time, refusing every shape the plan cannot mean (the messages name the
     * spelling the author must use).
     *
     * @param c
     *            the authored call
     * @return the read
     * @throws ExpressionException
     *             when the call is malformed — the rule's load error, with the reader's own message
     */
    public static Spec spec(Expr.Call c)
    {
        if (c.args().size() != 1)
        {
            throw new ExpressionException(NAME
                    + " takes exactly one positional argument, the column of domain to read; got "
                    + c.args().size());
        }
        String column = columnName(c.args().get(0));
        Map<String, Expr> kwargs = c.kwargs();
        for (String key : kwargs.keySet())
        {
            if (!KEYWORDS.contains(key))
            {
                throw new ExpressionException(NAME + " has no parameter '" + key
                        + "' — its keywords are domain=, filter=, mode=, group= and keep_missings=");
            }
        }
        Expr domainExpr = kwargs.get("domain");
        if (domainExpr == null)
        {
            throw new ExpressionException(
                    NAME + " requires domain=, the dataset the column is read from (TS or \"TS\")");
        }
        String dataset = datasetName(domainExpr);
        Expr modeExpr = kwargs.get("mode");
        if (modeExpr == null)
        {
            throw new ExpressionException(NAME
                    + " requires mode= — \"FIRST\", \"ONLY\", \"MIN\" or \"MAX\" — the row-selection"
                    + " policy is authored, never inferred");
        }
        Mode mode = mode(modeExpr);
        Expr filter = kwargs.get("filter");
        if (filter != null)
        {
            checkFilterRefs(filter);
        }
        // W5: group= is the grouped aggregates' reader (bare columns of D, at least one when
        // given); keep_missings= from the same reader.
        List<String> group = GroupedAggregate.readGroup(NAME, kwargs.get("group"), true, false);
        GroupKeyPolicy policy = GroupedAggregate.readPolicy(NAME, kwargs.get("keep_missings"));
        if (group.isEmpty() && kwargs.containsKey("keep_missings"))
        {
            throw new ExpressionException("`keep_missings` on " + NAME
                    + " requires a non-empty `group`; with no grouping key it would have no effect");
        }
        return new Spec(column, dataset, filter, mode, group, policy,
                net.cumba.corej.core.expr.ExpressionPrinter.print(c));
    }


    private static String columnName(Expr arg)
    {
        return switch (arg)
        {
        case Expr.Ref ref when ref.kind() == OperandKind.COLUMN -> ref.name();
        case Expr.Ref ref when ref
                .kind() == OperandKind.WILDCARD_COLUMN -> throw new ExpressionException(
                        NAME + " does not resolve `--` (" + ref.name()
                                + "): name the column of domain explicitly");
        case Expr.Ref ref when ref
                .kind() == OperandKind.DOTTED_REF -> throw new ExpressionException(NAME
                        + " reads a column of domain, so its column is written bare, not dotted ("
                        + ref.name() + ") — write it bare ("
                        + ref.name().substring(ref.name().indexOf('.') + 1) + ")");
        case Expr.Ref ref -> throw new ExpressionException(
                NAME + " reads a column of domain; " + ref.name() + " is not one");
        case Expr.Lit lit -> throw new ExpressionException(
                "argument 'name' of '" + NAME + "' takes a column reference, not the literal "
                        + lit.value() + " — a quoted name is a string, never a column");
        default -> throw new ExpressionException(
                NAME + " reads a column of domain; give its name as the first argument");
        };
    }


    private static String datasetName(Expr domain)
    {
        return switch (domain)
        {
        case Expr.Ref ref when ref.kind() == OperandKind.COLUMN -> ref.name();
        case Expr.Lit lit when lit.kind() == Expr.LitKind.STRING -> (String) lit.value();
        default -> throw new ExpressionException(
                NAME + "'s domain= takes a dataset reference — TS or \"TS\"");
        };
    }


    private static Mode mode(Expr modeExpr)
    {
        if (!(modeExpr instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.STRING)
        {
            throw new ExpressionException(NAME
                    + "'s mode= takes a string literal: \"FIRST\", \"ONLY\", \"MIN\" or \"MAX\"");
        }
        try
        {
            return Mode.valueOf((String) lit.value());
        }
        catch (IllegalArgumentException _)
        {
            throw new ExpressionException(NAME + "'s mode= is one of \"FIRST\", \"ONLY\","
                    + " \"MIN\" or \"MAX\", not \"" + lit.value() + "\"");
        }
    }


    /** Right-side columns only (spec §3.3, Q4): a reference outside {@code D} is a load error. */
    private static void checkFilterRefs(Expr e)
    {
        switch (e)
        {
        case Expr.Ref ref ->
        {
            if (ref.kind() != OperandKind.COLUMN)
            {
                throw new ExpressionException(
                        NAME + "'s filter= reads the columns of domain only; " + ref.name()
                                + " is not a bare column of it (no dotted, $ or -- reference)"
                                + (ref.kind() == OperandKind.DOTTED_REF ? " — write it bare ("
                                        + ref.name().substring(ref.name().indexOf('.') + 1) + ")"
                                        : ""));
            }
        }
        case Expr.Call call ->
        {
            call.args().forEach(ReadValue::checkFilterRefs);
            call.kwargs().values().forEach(ReadValue::checkFilterRefs);
        }
        case Expr.Binary binary ->
        {
            checkFilterRefs(binary.left());
            checkFilterRefs(binary.right());
        }
        case Expr.And and -> and.parts().forEach(ReadValue::checkFilterRefs);
        case Expr.Or or -> or.parts().forEach(ReadValue::checkFilterRefs);
        case Expr.Not not -> checkFilterRefs(not.inner());
        case Expr.Lit lit ->
        {
            if (lit.kind() == Expr.LitKind.LIST && lit.value() instanceof List<?> items)
            {
                for (Object item : items)
                {
                    if (item instanceof Expr inner)
                    {
                        checkFilterRefs(inner);
                    }
                }
            }
        }
        default ->
        {
            // nothing else carries a reference
        }
        }
    }
}
