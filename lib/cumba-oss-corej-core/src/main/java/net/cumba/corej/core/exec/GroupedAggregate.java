package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.ExpressionPrinter;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.ArgumentBinder;
import net.cumba.corej.core.expr.eval.ComputedVector;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.DatasetExpressionCache;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.ExprCompiler;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.expr.eval.FunctionRegistry;
import net.cumba.corej.core.expr.eval.NativeExprEvaluator;
import net.cumba.corej.core.expr.eval.Parameter;
import net.cumba.corej.core.expr.eval.TypedValue;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.corej.core.expr.typed.TypeExpectations;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.GroupKeyPolicy;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;

/**
 * ⭐ The grouped, cross-dataset, name-based aggregate plan of runbook W5
 * ({@code PLAN-grouped-aggregate-functions} §2.2) — the registry functions {@code max},
 * {@code max_date} and {@code min_date}, and the mechanism {@code read_value}'s {@code group=}
 * (D14) and the later waves reuse ({@link RecordCount}, W6; {@link Distinct}, W7).
 *
 * <p>
 * A call is read at load by {@link #spec} — its column arguments are <b>names</b> (EC-44 is defined
 * over names), never vectors of the primary, because under {@code domain=} every one of them names
 * a column of the OTHER dataset (owner D13 Q4 / D14 / D10; inside a <em>nested</em> grouped call in
 * the target, that call's target and filter columns are its own {@code domain=}'s, its
 * {@code group=} columns are read on both its {@code domain=} and the target table — the broadcast
 * key — and a nested call without {@code domain=} reads the target table itself, see
 * {@link #readTarget}) — and evaluated per execution by {@link #broadcast}: the target table is
 * resolved ({@code domain=} through {@link SplitDomainResolution}, as {@code read_value}), the
 * target cells and the {@code filter=} mask are evaluated on a table-scoped context, the groups are
 * formed by the engine's one grouper ({@link IndexHelper#groupByPresent}: absent group columns
 * partition nothing, plan 16's {@code GroupKey} identity, the {@link IndexHelper.BlockResults}
 * tripwire, the {@code keep_missings} policies), each block's value is computed by the function's
 * {@link Aggregator}, and the values are returned <b>already broadcast per row of the primary</b>
 * (runbook R2): one identity key per primary row through the ONE derivation both sides use
 * ({@link GroupKeyIdentity#identityKey}), the cross-table key-type check run once per evaluation
 * (GKI Q2 — a Char/Num key pair between the two tables ERRORs the rule). No per-row result object
 * leaves a ported callable.
 * </p>
 *
 * <p>
 * <b>Answers</b> (the runbook's ⚑ on a VALUE function answering a missing, D85c / D86a, W3's
 * {@code RowMax} precedent): a block whose inputs are ALL missing answers their combined missing
 * identity (the first carrier cell, {@code MIS} when the identities differ); a block with present
 * input but no result (blank-only cells, an indeterminate date extreme — EC-46 — or a filter that
 * keeps no row) answers {@link ScalarSemantics#computedMissing()} for the extremes, and for
 * {@code read_value} {@code X}'s type default — D13's "no qualifying row", group by group; a
 * primary row whose key has no group answers the function's no-group default
 * ({@code computedMissing()} for the extremes — the retired operations' declared MISSING codomain —
 * and {@code X}'s type default for {@code read_value}, D13); never {@code null}. The declared type
 * of the vector is the target's.
 * </p>
 *
 * <p>
 * <b>{@code max} ranks candidates by their own type</b> (owner D-W1-2a, {@code D-W3-5}): a numeric
 * target numerically ({@code Long} exact, else {@code Double} with {@code ±0} one value), a textual
 * one through the shared string branch ({@link Extremes#genericStringExtreme}: plain text order —
 * owner K3, 2026-09-30, "text is text"; date-looking text gets no date rule, which belongs to
 * {@code max_date} / {@code min_date}); the winning <em>cell</em> is answered, the first in row
 * order on a tie. Measured at W5's go: the retired {@code evalMaxGrouped} already ranked every real
 * character column as text (its numeric branch needed a finite {@code getValueAsDouble()}, which
 * {@code DataValueString} never gives), so this is the shipped order, not a movement.
 * </p>
 *
 * <p>
 * The block map is memoised for the execution ({@code EvaluationContext.getAggregateMemo()}, keyed
 * by the primary table and the canonical call text), so a binding read by several contexts of one
 * execution groups once. The three aggregates admit no {@code $} reference in any argument, so the
 * call text is the whole key; a caller whose {@code group=} splices a {@code $}-list
 * ({@link RecordCount}, runbook W6 D-W6-8) keys on the call text PLUS the expanded names, because
 * the derived contexts of one execution share the memo while their variables maps may differ.
 * </p>
 */
public final class GroupedAggregate
{

    /** {@code max(name, domain=, filter=, group=, keep_missings=)}. */
    public static final String MAX = "max";

    /** {@code max_date(name, domain=, filter=, group=, keep_missings=, missing_values=)}. */
    public static final String MAX_DATE = "max_date";

    /** {@code min_date(name, domain=, filter=, group=, keep_missings=, missing_values=)}. */
    public static final String MIN_DATE = "min_date";

    /** The three grouped aggregate functions this class implements. */
    public static final Set<String> FUNCTION_NAMES = Set.of(MAX, MAX_DATE, MIN_DATE);

    /** The target parameter — the only one the three read (runbook R6: KEEP-name). */
    public static final String NAME_PARAMETER = "name";

    /** The dataset parameter (D10: a bare or a quoted dataset name). */
    public static final String DOMAIN_PARAMETER = "domain";

    /** The row filter — a boolean expression over the target table's own columns (D9). */
    public static final String FILTER_PARAMETER = "filter";

    /** The grouping key — a list of column references of the target table. */
    public static final String GROUP_PARAMETER = "group";

    /** The missing-key disposition (a boolean literal). */
    public static final String KEEP_MISSINGS_PARAMETER = "keep_missings";

    /** EC-51 Half B — the missing-candidate disposition of the two date extremes. */
    public static final String MISSING_VALUES_PARAMETER = "missing_values";

    /** {@link #MISSING_VALUES_PARAMETER}: a missing candidate is not a candidate (the default). */
    public static final String MISSING_VALUES_SKIP = "skip";

    /** {@link #MISSING_VALUES_PARAMETER}: a missing candidate makes the extreme undeterminable. */
    public static final String MISSING_VALUES_INDETERMINATE = "indeterminate";

    private GroupedAggregate()
    {
    }


    /**
     * Whether {@code name} is one of the three grouped aggregate functions.
     *
     * @param name
     *            a call name
     * @return {@code true} for {@code max}, {@code max_date} and {@code min_date}
     */
    public static boolean isFunction(String name)
    {
        return FUNCTION_NAMES.contains(name);
    }

    /**
     * What a function computes for one block: over the block's rows (those the filter keeps —
     * {@code keep} is {@code null} when there is no filter), reading the target cell by row.
     */
    @FunctionalInterface
    interface Aggregator
    {

        /**
         * Computes the block's value.
         *
         * @param cellAt
         *            the target cell of an absolute row of the target table
         * @param rows
         *            the block's absolute row indices
         * @param keep
         *            the filter mask over the target table, or {@code null} for every row
         * @param type
         *            the target's declared type (a function whose "no qualifying row" answer is the
         *            type default reads it — {@code read_value}, D13)
         * @return the block's value — a cell, a carried missing, the computed missing or the
         *         target's type default; never {@code null}
         */
        IDataValue aggregate(IntFunction<IDataValue> cellAt, int[] rows, @Nullable BitSet keep,
                DataValueType type);
    }


    /**
     * A grouped aggregate call as authored, read at load (D-W2-1's strict-reader shape).
     *
     * @param function
     *            the function name
     * @param target
     *            the target — a bare column reference (the fast path) or any VALUE expression over
     *            the target table (D14 / D119c); a nested grouped call inside it is evaluated on
     *            its own {@code domain=} and broadcast to the target table's rows
     * @param dataset
     *            the {@code domain=} dataset, or {@code null} for the primary
     * @param filter
     *            the boolean filter over the target table's own columns, or {@code null}
     * @param group
     *            the group column names (non-empty)
     * @param policy
     *            the missing-key policy
     * @param missingIsIndeterminate
     *            EC-51 Half B — {@code true} for {@code missing_values="indeterminate"}
     * @param canonical
     *            the canonical call text (the memo key)
     */
    public record Spec(String function, Expr target, @Nullable String dataset,
            @Nullable Expr filter, List<String> group, GroupKeyPolicy policy,
            boolean missingIsIndeterminate, String canonical)
    {

        public Spec
        {
            group = List.copyOf(group);
        }


        /**
         * Evaluates the call for this run: the per-group values broadcast to every row of the
         * evaluated dataset.
         *
         * @param run
         *            the evaluation run
         * @return the broadcast vector
         */
        public Vector evaluate(EvalRun run)
        {
            Aggregator aggregator = switch (function)
            {
            case MAX -> (cellAt, rows, keep, _) -> maxOf(cellAt, rows, keep);
            case MAX_DATE -> (cellAt, rows, keep, _) -> dateExtremeOf(cellAt, rows, keep, true,
                    missingIsIndeterminate);
            case MIN_DATE -> (cellAt, rows, keep, _) -> dateExtremeOf(cellAt, rows, keep, false,
                    missingIsIndeterminate);
            default -> throw new IllegalStateException("not a grouped aggregate: " + function);
            };
            return broadcast(run, function, target, dataset, filter, group, policy, canonical,
                    aggregator, _ -> ScalarSemantics.computedMissing());
        }
    }

    // -----------------------------------------------------------------------
    // The strict reader (load time)
    // -----------------------------------------------------------------------

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
        String fn = c.name();
        FunctionDescriptor descriptor = FunctionRegistry.descriptor(fn);
        if (descriptor == null || !isFunction(fn))
        {
            throw new ExpressionException("no grouped aggregate function '" + fn + "'");
        }
        rejectPositionalAfterTarget(fn, c);
        // D9: one binding for the keyword spellings; ArgumentBinder raises the unknown-keyword
        // errors (D19a) — `missing_values` on max is one of them.
        List<@Nullable Expr> bound = ArgumentBinder.bind(descriptor, c);
        List<Parameter> params = descriptor.parameters();
        Expr targetExpr = slot(bound, params, NAME_PARAMETER);
        String dataset = readDataset(fn, slot(bound, params, DOMAIN_PARAMETER));
        boolean foreign = dataset != null;
        Expr target = readTarget(fn, targetExpr, foreign);
        Expr filter = readFilter(fn, slot(bound, params, FILTER_PARAMETER), foreign);
        List<String> group = readGroup(fn, slot(bound, params, GROUP_PARAMETER), foreign, true);
        GroupKeyPolicy policy = readPolicy(fn, slot(bound, params, KEEP_MISSINGS_PARAMETER));
        boolean indeterminate = readMissingValues(fn,
                slot(bound, params, MISSING_VALUES_PARAMETER));
        return new Spec(fn, target, dataset, filter, group, policy, indeterminate,
                ExpressionPrinter.print(c));
    }


    private static void rejectPositionalAfterTarget(String fn, Expr.Call c)
    {
        rejectPositionalAfterTarget(fn, c, "domain=, filter=, group=, keep_missings="
                + (MAX.equals(fn) ? "" : " and missing_values="));
    }


    /**
     * The target is the only positional argument (combined review of runbook W2–W8, W5/W6 M1): the
     * binder would bind a second positional to {@code domain}, a third to {@code filter} and so on
     * (D9), but every load-time reader of the call — the absent-dataset skip, the classifiers, the
     * stage-A typer, the output-variable deriver — reads the keywords only, so
     * {@code max_date(DSSTDTC, DS, group=[USUBJID])} on a study with no DS ran instead of skipping
     * and passed silently. The reader refuses the shape and names the keyword spelling, as
     * {@code record_count} and {@code read_value} already do.
     *
     * @param fn
     *            the function name
     * @param c
     *            the authored call
     * @param keywords
     *            the keyword spellings the message names
     * @throws ExpressionException
     *             for a second positional argument — the rule's load error
     */
    static void rejectPositionalAfterTarget(String fn, Expr.Call c, String keywords)
    {
        if (c.args().size() > 1)
        {
            throw new ExpressionException(fn + " takes one positional argument, its target; got "
                    + c.args().size() + " — write the others as keywords: " + keywords);
        }
    }


    static @Nullable Expr slot(List<@Nullable Expr> bound, List<Parameter> params, String name)
    {
        for (int i = 0; i < params.size(); i++)
        {
            if (params.get(i).name().equals(name))
            {
                return i < bound.size() ? bound.get(i) : null;
            }
        }
        return null;
    }


    /**
     * The target: a bare column of the target table, a {@code --}-prefix column (primary only — the
     * prefix resolves against the primary's domain), or any VALUE expression over the target
     * table's own columns. A quoted name is a string, never a column (R1); a dotted or {@code $}
     * reference names another table or binding and is refused. ⚠ Every reference <em>outside</em> a
     * nested grouped call is a column of the target table. Inside a nested call
     * ({@code min_date(min_date(EXSTDTC, domain="EX", group=[POOLID], …), domain="POOLDEF",
     * group=[USUBJID])} — the pool's first dose per POOLDEF row, {@code PLAN-scalar-date-extremes})
     * the call's target and filter columns are its own {@code domain=}'s; its {@code group=}
     * columns are read on both its {@code domain=} and the target table (the broadcast key); a
     * nested call without {@code domain=} reads the target table itself. {@link #checkRefs} checks
     * only their shape.
     */
    static Expr readTarget(String fn, @Nullable Expr target, boolean foreign)
    {
        if (target == null)
        {
            throw new ExpressionException(fn + " requires its target, the column to aggregate");
        }
        if (target instanceof Expr.Lit lit)
        {
            throw new ExpressionException("argument '" + NAME_PARAMETER + "' of '" + fn
                    + "' takes a column reference, not the literal " + lit.value()
                    + " — a quoted name is a string, never a column");
        }
        if (target instanceof Expr.Ref ref)
        {
            columnName(fn, NAME_PARAMETER, ref, foreign);
            return target;
        }
        // D14 / D119c: an expression target — every reference outside a nested grouped call is a
        // column of the target table (the same rule as the filter). A nested call's target and
        // filter columns are its own domain='s; its group= columns are read on both its domain=
        // and the target table (the broadcast key); a nested call without domain= reads the
        // target table itself. Only their shape is checked here.
        checkRefs(fn, NAME_PARAMETER, target, foreign);
        return target;
    }


    /**
     * A bare column reference of the target table, as a name.
     *
     * @throws ExpressionException
     *             for anything else
     */
    static String columnName(String fn, String parameter, Expr e, boolean foreign)
    {
        if (e instanceof Expr.Ref ref)
        {
            return switch (ref.kind())
            {
            case COLUMN -> ref.name();
            case WILDCARD_COLUMN ->
            {
                // A `--` domain prefix resolves against the PRIMARY's domain (the per-dataset
                // specialisation, D77), so under domain= it would name the wrong dataset's
                // column: refused, as read_value refuses it. Any other wildcard (an ADaM
                // template such as AyIND, expanded per rule by WildcardExpander before the
                // expansion is compiled) is accepted as the name it will become.
                if (foreign && isDomainPrefixWildcard(ref.name()))
                {
                    throw new ExpressionException(fn + " does not resolve `--` (" + ref.name()
                            + ") under domain=: name the column of the dataset explicitly");
                }
                yield ref.name();
            }
            case DOTTED_REF -> throw new ExpressionException("argument '" + parameter + "' of '"
                    + fn + "' names a column of " + (foreign ? "domain" : "the dataset")
                    + ", so it is written bare, not dotted (" + ref.name() + ") — write it bare ("
                    + ref.name().substring(ref.name().indexOf('.') + 1) + ")");
            default -> throw new ExpressionException("argument '" + parameter + "' of '" + fn
                    + "' must be a plain column reference; " + ref.name() + " is not one");
            };
        }
        if (e instanceof Expr.Lit lit)
        {
            throw new ExpressionException("argument '" + parameter + "' of '" + fn
                    + "' takes a column reference, not the literal " + lit.value()
                    + " — a quoted name is a string, never a column");
        }
        throw new ExpressionException(
                "argument '" + parameter + "' of '" + fn + "' must be a plain column reference");
    }


    private static boolean isDomainPrefixWildcard(String name)
    {
        return name.startsWith("--") && name.indexOf('.') < 0;
    }


    static @Nullable String readDataset(String fn, @Nullable Expr domain)
    {
        if (domain == null)
        {
            return null;
        }
        return switch (domain)
        {
        case Expr.Ref ref when ref.kind() == OperandKind.COLUMN -> ref.name();
        case Expr.Lit lit when lit.kind() == Expr.LitKind.STRING -> (String) lit.value();
        default -> throw new ExpressionException(
                fn + "'s domain= takes a dataset reference — DS or \"DS\"");
        };
    }


    /**
     * {@code filter=(<boolean over the target table>)} (D9 / D12's sketch): the retired
     * {@code filter(K="v")} call form is refused with the spelling named, and every reference
     * inside is a bare column of the target table.
     */
    static @Nullable Expr readFilter(String fn, @Nullable Expr filter, boolean foreign)
    {
        if (filter == null)
        {
            return null;
        }
        if (filter instanceof Expr.Call call && FILTER_PARAMETER.equals(call.name()))
        {
            throw new ExpressionException(fn + "'s filter= is a boolean expression over the "
                    + (foreign ? "domain" : "dataset")
                    + "'s columns, written filter=(COLUMN == \"value\"), not filter(COLUMN=\"value\")");
        }
        if (filter instanceof Expr.Lit || filter instanceof Expr.Ref)
        {
            throw new ExpressionException(
                    fn + "'s filter= is a boolean expression, e.g. filter=(COLUMN == \"value\")");
        }
        checkRefs(fn, FILTER_PARAMETER, filter, foreign);
        return filter;
    }


    /**
     * Every reference inside {@code e} is a bare column — of the target table, or, inside a nested
     * grouped call, a target or filter column of that call's own {@code domain=} (a nested call's
     * {@code group=} columns are read on both its {@code domain=} and the target table, the
     * broadcast key; a nested call without {@code domain=} reads the target table itself). The
     * shape is what is checked; the column's home is resolved when the nested call is evaluated.
     */
    private static void checkRefs(String fn, String parameter, Expr e, boolean foreign)
    {
        switch (e)
        {
        case Expr.Ref ref -> columnName(fn, parameter, ref, foreign);
        case Expr.Call call ->
        {
            call.args().forEach(a -> checkRefs(fn, parameter, a, foreign));
            call.kwargs().values().forEach(a -> checkRefs(fn, parameter, a, foreign));
        }
        case Expr.Binary binary ->
        {
            checkRefs(fn, parameter, binary.left(), foreign);
            checkRefs(fn, parameter, binary.right(), foreign);
        }
        case Expr.And and -> and.parts().forEach(p -> checkRefs(fn, parameter, p, foreign));
        case Expr.Or or -> or.parts().forEach(p -> checkRefs(fn, parameter, p, foreign));
        case Expr.Not not -> checkRefs(fn, parameter, not.inner(), foreign);
        case Expr.Lit lit ->
        {
            if (lit.kind() == Expr.LitKind.LIST && lit.value() instanceof List<?> items)
            {
                for (Object item : items)
                {
                    if (item instanceof Expr inner)
                    {
                        checkRefs(fn, parameter, inner, foreign);
                    }
                }
            }
        }
        }
    }


    /**
     * {@code group=[…]} — the group column names of the target table; a single reference is a
     * one-column key. Required and non-empty when {@code required} (D-W5-1: the three extremes),
     * optional for {@code read_value} (an empty result means "ungrouped"). No {@code $} member.
     */
    static List<String> readGroup(String fn, @Nullable Expr group, boolean foreign,
            boolean required)
    {
        return readGroup(fn, group, foreign, required, false);
    }


    /**
     * {@link #readGroup(String, Expr, boolean, boolean)}, optionally admitting a {@code $}
     * reference as a member (runbook W6, {@link RecordCount}): the member is kept as its raw
     * {@code $name} and spliced at evaluation into the column names the binding holds
     * ({@code group=[USUBJID, --TESTCD, $TIMING_VARIABLES]}, the retired the retired executor's
     * {@code expandGroupRefs}).
     *
     * @param allowSplice
     *            whether a {@code $} reference is a legal member
     */
    static List<String> readGroup(String fn, @Nullable Expr group, boolean foreign,
            boolean required, boolean allowSplice)
    {
        if (group == null)
        {
            if (required)
            {
                throw new ExpressionException(fn
                        + " requires group=[…], the columns whose combined value defines a group");
            }
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (group instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.LIST)
        {
            @SuppressWarnings("unchecked")
            List<Expr> items = (List<Expr>) lit.value();
            for (Expr item : items)
            {
                out.add(groupMember(fn, item, foreign, allowSplice));
            }
        }
        else if (group instanceof Expr.Ref)
        {
            out.add(groupMember(fn, group, foreign, allowSplice));
        }
        else
        {
            throw new ExpressionException("argument '" + GROUP_PARAMETER + "' of '" + fn
                    + "' takes a list of column references");
        }
        if (out.isEmpty())
        {
            throw new ExpressionException(fn + "'s group= must name at least one column");
        }
        return List.copyOf(out);
    }


    private static String groupMember(String fn, Expr item, boolean foreign, boolean allowSplice)
    {
        if (allowSplice && item instanceof Expr.Ref ref && ref.kind() == OperandKind.OPERATION_REF)
        {
            return ref.name();
        }
        if (item instanceof Expr.Ref ref && ref.kind() == OperandKind.DOTTED_REF)
        {
            // ⭐ C2 of PLAN-rprfdy-offset-tp-join (owner 2026-09-29: "use qualified variables in
            // a group key … group=[DM.RPATHCD, RPHASE] as it is clear that DM.RPATHCD joins to
            // RPATHCD"): a qualified member names where the RECORD-side value comes from — the
            // rule's own Match_Datasets entry DM, read at the row's bound DM record — and the
            // unqualified RPATHCD is the same column of the grouped dataset. It needs an "other
            // side", so it is admitted under domain= only (D-DOMAIN): grouping the evaluated
            // dataset by a joined value is the rule-level Grouping's job (C3).
            if (!foreign)
            {
                throw new ExpressionException("argument '" + GROUP_PARAMETER + "' of '" + fn
                        + "' names the qualified " + ref.name() + ", which requires domain=: a"
                        + " qualified group member keys the record side through the join and the"
                        + " grouped dataset by the unqualified name, so there must be a grouped"
                        + " dataset — group the evaluated dataset by a joined value with the"
                        + " rule-level Grouping instead, or write it bare ("
                        + ref.name().substring(ref.name().indexOf('.') + 1)
                        + ") to group by the evaluated dataset's own column");
            }
            return ref.name();
        }
        if (item instanceof Expr.Ref ref && ref.kind() == OperandKind.WILDCARD_COLUMN
                && ref.name().indexOf('.') > 0)
        {
            // DM.--SEQ: a `--` inside a qualified member would resolve against different
            // datasets on the two sides — refused, as the join key refuses it.
            throw new ExpressionException("argument '" + GROUP_PARAMETER + "' of '" + fn
                    + "' names the qualified " + ref.name() + " with a `--` wildcard — the two"
                    + " sides of a qualified group member resolve against different datasets,"
                    + " so name the column explicitly");
        }
        return columnName(fn, GROUP_PARAMETER, item, foreign);
    }


    /** Whether any {@code group=} member is qualified ({@code DM.RPATHCD}, C2). */
    static boolean hasQualifiedMember(List<String> groupNames)
    {
        for (String name : groupNames)
        {
            if (MatchDataset.qualifierOf(name) != null)
            {
                return true;
            }
        }
        return false;
    }


    /**
     * The {@code group=} names as the GROUPED side reads them (C2): a qualified member's
     * unqualified name, every plain member as authored. A qualified member whose unqualified column
     * the grouped dataset lacks is a rule ERROR (D-ABSENT) — never EC-44's "an absent group column
     * partitions nothing", which stays the plain members' rule.
     *
     * @param fn
     *            the function name, for the message
     * @param table
     *            the grouped dataset
     * @param groupNames
     *            the members as authored, prefixes resolved
     * @return the names to group {@code table} by, position-wise
     */
    static List<String> groupedSideNames(String fn, IDataTable table, List<String> groupNames)
    {
        if (!hasQualifiedMember(groupNames))
        {
            return groupNames;
        }
        List<String> out = new ArrayList<>(groupNames.size());
        for (String name : groupNames)
        {
            String qualifier = MatchDataset.qualifierOf(name);
            if (qualifier == null)
            {
                out.add(name);
                continue;
            }
            String unqualified = name.substring(qualifier.length() + 1);
            if (table.getMetaData().getColumnIndex(unqualified) < 0)
            {
                throw new UnresolvedQualifiedKeyException(fn + " group=", name,
                        table.getMetaData().getName() + " has no column " + unqualified
                                + " (the grouped dataset needs the same variable, unqualified)");
            }
            out.add(unqualified);
        }
        return List.copyOf(out);
    }


    /**
     * The RECORD-side keyer of a {@code group=} with a qualified member (C2): a plain member is the
     * evaluated row's own cell, a qualified member {@code DM.RPATHCD} is the cell of the row's
     * bound DM record, read through the join lookup the context carries — the same identity
     * derivation as {@link GroupKeyIdentity#identityKey}, component for component, so the key meets
     * the block key the grouped side built over the unqualified names.
     *
     * <p>
     * ⛔ Built per call from the CONTEXT, never stored in the memoised {@link Grouped}: the memo is
     * keyed on the primary and the call text and must not carry a join-dependent reader. D-ABSENT:
     * no lookup for the qualifier (the source entry's dataset is absent from the study), or the
     * source column absent from it, is a rule ERROR before any row is read.
     * </p>
     *
     * @param ctx
     *            the evaluation context (its joined datasets)
     * @param fn
     *            the function name, for the message
     * @param groupNames
     *            the members as authored, prefixes resolved
     * @return the keyer
     */
    static RowKey recordKeyer(EvaluationContext ctx, String fn, List<String> groupNames)
    {
        int n = groupNames.size();
        IDataTable primary = ctx.getTable();
        int[] columns = new int[n];
        @Nullable
        JoinLookup[] lookups = new JoinLookup[n];
        String[] unqualified = new String[n];
        for (int i = 0; i < n; i++)
        {
            String name = groupNames.get(i);
            String qualifier = MatchDataset.qualifierOf(name);
            if (qualifier == null)
            {
                columns[i] = primary.getMetaData().getColumnIndex(name);
                continue;
            }
            columns[i] = -1;
            unqualified[i] = name.substring(qualifier.length() + 1);
            lookups[i] = requireSourceLookup(ctx, fn + " group=", name);
        }
        return (_, t, row) ->
        {
            Object[] parts = new Object[n];
            for (int i = 0; i < n; i++)
            {
                JoinLookup lookup = lookups[i];
                parts[i] = lookup == null ? GroupKeyIdentity.identityAt(t, columns[i], row)
                        : GroupKeyIdentity
                                .identityOf(lookup.lookupValue(t, row, unqualified[i], false));
            }
            return GroupKeyIdentity.keyOf(parts);
        };
    }


    /**
     * The join lookup a qualified member reads its record side through (C2 / C3): the context's
     * lookup for the qualifier, which must exist and carry the unqualified column — else D-ABSENT's
     * ERROR ({@link UnresolvedQualifiedKeyException}), never a {@code ""} key.
     *
     * @param ctx
     *            the evaluation context (its joined datasets)
     * @param where
     *            the surface, for the message ({@code read_value group=}, {@code Grouping})
     * @param qualifiedName
     *            the member as authored, e.g. {@code DM.RPATHCD}
     * @return the lookup
     */
    static JoinLookup requireSourceLookup(EvaluationContext ctx, String where, String qualifiedName)
    {
        String qualifier = java.util.Objects
                .requireNonNull(MatchDataset.qualifierOf(qualifiedName));
        String unqualified = qualifiedName.substring(qualifier.length() + 1);
        JoinLookup lookup = ctx.getJoinedDatasets().get(qualifier);
        if (lookup == null)
        {
            throw new UnresolvedQualifiedKeyException(where, qualifiedName,
                    "no " + qualifier + " record is bound to the evaluated rows (the study has no "
                            + qualifier + " dataset, or the entry built no join)");
        }
        if (!lookup.hasColumn(ctx.getTable(), 0, unqualified))
        {
            throw new UnresolvedQualifiedKeyException(where, qualifiedName,
                    qualifier + " has no column " + unqualified);
        }
        return lookup;
    }


    /**
     * GKI Q2 for a {@code group=} that may carry a qualified member (C2): a plain member compares
     * the grouped column's kind with the evaluated table's, a qualified member with the SOURCE
     * column's ({@code DM.RPATHCD}'s kind in DM, through the lookup's declared type) — the
     * evaluated table does not carry it, and judging that side would skip the check silently.
     *
     * @param ctx
     *            the evaluation context
     * @param table
     *            the grouped dataset
     * @param groupNames
     *            the members as authored, prefixes resolved
     */
    static void requireCompatibleKeys(EvaluationContext ctx, IDataTable table,
            List<String> groupNames)
    {
        if (!hasQualifiedMember(groupNames))
        {
            GroupKeyIdentity.requireCompatibleKeyColumns(table, groupNames, ctx.getTable(),
                    groupNames);
            return;
        }
        IDataTable primary = ctx.getTable();
        for (String name : groupNames)
        {
            String qualifier = MatchDataset.qualifierOf(name);
            if (qualifier == null)
            {
                GroupKeyIdentity.requireCompatibleKeyColumns(table, List.of(name), primary,
                        List.of(name));
                continue;
            }
            String unqualified = name.substring(qualifier.length() + 1);
            JoinLookup lookup = ctx.getJoinedDatasets().get(qualifier);
            if (lookup == null)
            {
                continue; // recordKeyer reports it
            }
            GroupKeyIdentity.requireCompatibleKeyColumn(table, unqualified, qualifier,
                    lookup.declaredTypeOf(unqualified));
        }
    }


    /**
     * {@code keep_missings=} from the bound slot (D9): the shipped default of every key-building
     * grouped evaluator is {@link GroupKeyPolicy#KEEP_MISSING_KEYS}; a boolean literal overrides
     * its disposition.
     */
    static GroupKeyPolicy readPolicy(String fn, @Nullable Expr keepMissings)
    {
        if (keepMissings == null)
        {
            return GroupKeyPolicy.KEEP_MISSING_KEYS;
        }
        if (!(keepMissings instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.BOOL)
        {
            throw new ExpressionException(
                    "`keep_missings` must be a boolean literal (true/false) on `" + fn + "`");
        }
        return GroupKeyPolicy.KEEP_MISSING_KEYS.withKeepMissings((Boolean) lit.value());
    }


    private static boolean readMissingValues(String fn, @Nullable Expr missingValues)
    {
        if (missingValues == null)
        {
            return false;
        }
        if (!(missingValues instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.STRING)
        {
            throw new ExpressionException(
                    "`missing_values` on `" + fn + "` must be `" + MISSING_VALUES_SKIP + "` or `"
                            + MISSING_VALUES_INDETERMINATE + "` (a string literal)");
        }
        String value = (String) lit.value();
        if (MISSING_VALUES_INDETERMINATE.equals(value))
        {
            return true;
        }
        if (MISSING_VALUES_SKIP.equals(value))
        {
            return false;
        }
        throw new ExpressionException("`missing_values` must be `" + MISSING_VALUES_SKIP + "` or `"
                + MISSING_VALUES_INDETERMINATE + "`, got `" + value + "`");
    }

    // -----------------------------------------------------------------------
    // The mechanism (evaluation time)
    // -----------------------------------------------------------------------

    /**
     * How a primary row derives the key it reads its group by —
     * {@link GroupKeyIdentity#identityKey} over the group names unless the grouping was formed over
     * a derived key (a {@code regex=} normalisation, {@link RecordCount}), in which case the
     * grouping supplies its own.
     */
    @FunctionalInterface
    interface RowKey
    {

        /**
         * The key of a primary row.
         *
         * @param meta
         *            the primary's metadata
         * @param primary
         *            the primary table
         * @param row
         *            the row
         * @return the key
         */
        Object of(DataTableMeta meta, IDataTable primary, int row);
    }


    /**
     * The answer of every row of the evaluated dataset, read straight by row — the
     * {@link Grouped#direct} channel of a grouping formed over that dataset itself.
     */
    @FunctionalInterface
    interface RowAnswers
    {

        /**
         * The answer of a row.
         *
         * @param row
         *            the absolute row of the evaluated dataset
         * @return its group's value, or the no-group answer; never {@code null}
         */
        IDataValue at(int row);
    }


    /**
     * The memoised per-execution grouping: one value per group key, the vector's declared type, —
     * when the grouping keys by something other than the group names' identity — the primary-side
     * key derivation, and — when the groups were formed over the evaluated dataset itself — the
     * primary row's answer read straight by row ({@code direct}: no key is derived per row, XCUT
     * PERF 3), else {@code null}.
     */
    record Grouped(Map<Object, IDataValue> byKey, DataValueType type, @Nullable RowKey keyer,
            @Nullable RowAnswers direct)
    {

        /**
         * The grouping takes <b>ownership</b> of {@code byKey} (every builder hands over a map it
         * built for this grouping and never touches again), so it is wrapped, not copied — a copy
         * per execution of every block map was the second of two (W5/W6 L2).
         */
        Grouped
        {
            byKey = java.util.Collections.unmodifiableMap(byKey);
        }


        Grouped(Map<Object, IDataValue> byKey, DataValueType type, @Nullable RowKey keyer)
        {
            this(byKey, type, keyer, null);
        }


        /**
         * The answer of every row of the primary, by row: one slot per row, pre-filled with
         * {@code absent} and overwritten with each block's value over the block's own rows — only
         * valid when the blocks were formed over the primary table itself.
         */
        static RowAnswers perRow(int rowCount, IDataValue absent,
                List<IndexHelper.GroupBlock> blocks, IntFunction<IDataValue> valueOfBlock)
        {
            IDataValue[] answers = new IDataValue[rowCount];
            java.util.Arrays.fill(answers, absent);
            for (int b = 0; b < blocks.size(); b++)
            {
                IDataValue value = valueOfBlock.apply(b);
                for (int r : blocks.get(b).rows())
                {
                    answers[r] = value;
                }
            }
            return row -> row < answers.length ? answers[row] : absent;
        }
    }

    /**
     * One answer for every row, as a {@link ConstVector} (combined review of runbook W2–W8, XCUT
     * PERF 2): the constant pays no per-row carrier and is what a dataset-level hand-over reads for
     * free ({@code BindingValue.handOver}). The carrier is exactly the one a typed computed vector
     * builds per row ({@link TypedValue#typedCell}, the cell's identity kept — D85c); the payload
     * is what a dataset-level hand-over has always answered for it: a numeric cell's number,
     * otherwise the cell's resolved text ({@code null} for a missing).
     *
     * @param type
     *            the declared type
     * @param cell
     *            the answer
     * @return the constant vector
     */
    static ConstVector constantCell(DataValueType type, IDataValue cell)
    {
        TypedValue typed = TypedValue.typedCell(type, cell);
        Object resolved = typed.resolved();
        Object payload = resolved instanceof String && cell.getValue() instanceof Number number
                ? number
                : resolved;
        return new ConstVector(payload, type, typed);
    }


    /**
     * Groups the target table and broadcasts the per-group values to every row of the evaluated
     * dataset.
     *
     * @param run
     *            the evaluation run (its context is the primary)
     * @param fn
     *            the function name (for the EC-44 log context and the error messages)
     * @param target
     *            the target expression
     * @param dataset
     *            the {@code domain=} dataset, or {@code null} for the primary
     * @param filter
     *            the filter, or {@code null}
     * @param group
     *            the group column names (non-empty)
     * @param policy
     *            the missing-key policy
     * @param canonical
     *            the canonical call text (the memo key)
     * @param aggregator
     *            the block aggregator
     * @param noGroup
     *            the value of a primary row whose key has no group, by the target's type
     * @return the broadcast vector
     */
    static Vector broadcast(EvalRun run, String fn, Expr target, @Nullable String dataset,
            @Nullable Expr filter, List<String> group, GroupKeyPolicy policy, String canonical,
            Aggregator aggregator, java.util.function.Function<DataValueType, IDataValue> noGroup)
    {
        EvaluationContext ctx = run.ctx();
        List<String> groupNames = resolvePrefixes(group, ctx);
        return broadcast(run, fn, canonical, groupNames, () -> groupTarget(ctx, fn, target, dataset,
                filter, groupNames, policy, aggregator, noGroup), noGroup);
    }


    /**
     * The broadcast half on its own: memoises {@code grouping} for the execution under
     * {@code memoKey} and hands every row of the evaluated dataset its group's value.
     *
     * @param run
     *            the evaluation run (its context is the primary)
     * @param fn
     *            the function name, for a D-ABSENT message (never the call text — review round 1 of
     *            {@code PLAN-rprfdy-offset-tp-join}, lane 2 L5)
     * @param memoKey
     *            the memo key text — the canonical call, plus whatever the grouping depends on
     *            beyond it (D-W6-8)
     * @param groupNames
     *            the group column names, prefixes resolved (the primary-side key when the grouping
     *            supplies no {@link RowKey})
     * @param grouping
     *            forms the groups (run once per execution)
     * @param noGroup
     *            the value of a primary row whose key has no group, by the vector's type
     * @return the broadcast vector
     */
    static Vector broadcast(EvalRun run, String fn, String memoKey, List<String> groupNames,
            java.util.function.Supplier<Grouped> grouping,
            java.util.function.Function<DataValueType, IDataValue> noGroup)
    {
        EvaluationContext ctx = run.ctx();
        IDataTable primary = ctx.getTable();
        Object memo = ctx.getAggregateMemo().computeIfAbsent(
                DatasetExpressionCache.keyOf(primary, memoKey, ctx.getDomainPrefix()),
                grouping::get);
        Grouped grouped = (Grouped) java.util.Objects.requireNonNull(memo,
                "the memo never stores null");
        IDataValue absent = noGroup.apply(grouped.type());
        Map<Object, IDataValue> byKey = grouped.byKey();
        // C2: a qualified member's record side is resolved BEFORE the no-group shortcut — D-ABSENT
        // is a fact of the study's columns, not of whether the grouped side formed a group (review
        // round 1, lane 2 L2: an empty TP used to EXECUTE where a populated one ERRORed).
        RowKey qualifiedKeyer = null;
        if (hasQualifiedMember(groupNames))
        {
            // The record side reads a qualified member through the join, from THIS context —
            // never a keyer the memoised grouping carries (a regex= keyer is refused with a
            // qualified member at load, D-REGEX; stated here rather than trusted).
            if (grouped.keyer() != null)
            {
                throw new IllegalStateException("a grouping with a qualified group= member must"
                        + " not carry its own keyer (D-REGEX): " + groupNames);
            }
            qualifiedKeyer = recordKeyer(ctx, fn, groupNames);
        }
        if (byKey.isEmpty())
        {
            // One answer for every row (XCUT PERF 2): a constant, not a per-row carrier.
            return constantCell(grouped.type(), absent);
        }
        RowAnswers direct = grouped.direct();
        if (direct != null)
        {
            // The groups were formed over the evaluated dataset itself: each row reads its own
            // block's answer by row, with no key derived per row (XCUT PERF 3).
            return ComputedVector.typed(run.rowCount(), grouped.type(), direct::at);
        }
        DataTableMeta meta = primary.getMetaData();
        // The primary's key columns are resolved once per broadcast, never per row (XCUT PERF 3).
        int[] keyColumns = GroupKeyIdentity.columnIndices(meta, groupNames);
        RowKey keyer;
        if (qualifiedKeyer != null)
        {
            keyer = qualifiedKeyer;
        }
        else
        {
            keyer = grouped.keyer() != null ? grouped.keyer()
                    : (_, t, row) -> GroupKeyIdentity.identityKey(t, keyColumns, row);
        }
        return ComputedVector.typed(run.rowCount(), grouped.type(), row ->
        {
            IDataValue v = byKey.get(keyer.of(meta, primary, row));
            return v != null ? v : absent;
        });
    }


    private static Grouped groupTarget(EvaluationContext ctx, String fn, Expr target,
            @Nullable String dataset, @Nullable Expr filter, List<String> groupNames,
            GroupKeyPolicy policy, Aggregator aggregator,
            java.util.function.Function<DataValueType, IDataValue> noGroup)
    {
        IDataTable primary = ctx.getTable();
        IDataTable table = resolveTarget(ctx, dataset);
        if (table == null)
        {
            // Q17-a: an absent `domain` dataset answers the function's own no-value — every
            // primary row reads the no-group default.
            return new Grouped(Map.of(), DataValueType.STRING, null);
        }
        DataTableMeta meta = table.getMetaData();
        IntFunction<IDataValue> cellAt;
        DataValueType type;
        if (target instanceof Expr.Ref ref)
        {
            // The fast path (every corpus site): a direct column read, no compile.
            int colIdx = meta.getColumnIndex(resolvePrefix(ref.name(), ctx));
            if (colIdx < 0)
            {
                return new Grouped(Map.of(), DataValueType.STRING, null);
            }
            IDataTableColumn column = table.getColumn(colIdx);
            cellAt = column::getDataValue;
            type = meta.getColumn(colIdx).getType();
        }
        else
        {
            // D14 / D119c: a computed target, evaluated over the target table on its own context
            // (the retired computed-target materialiser's shape — D76: its own type expectations
            // decide the
            // absent-column default).
            Vector v = ExprCompiler.evaluateValueExpression(target,
                    tableContext(ctx, table, List.of(target)));
            if (v == null)
            {
                return new Grouped(Map.of(), DataValueType.STRING, null);
            }
            cellAt = row -> v.value(row).cell();
            type = v.declaredType();
        }
        BitSet keep = filterMask(ctx, table, filter);
        // C2: a qualified member groups the target by its UNQUALIFIED name (absent ⇒ ERROR).
        boolean qualified = hasQualifiedMember(groupNames);
        IndexHelper.Grouping grouping = IndexHelper.groupByPresent(table,
                groupedSideNames(fn, table, groupNames), logContext(ctx, fn), policy);
        if (grouping == null)
        {
            // an unexpanded $-ref in the group list — the reader refuses those, so unreachable
            return new Grouped(Map.of(), type, null);
        }
        IndexHelper.BlockResults results = new IndexHelper.BlockResults(grouping);
        List<IndexHelper.GroupBlock> blocks = grouping.blocks();
        IDataValue[] blockValues = new IDataValue[blocks.size()];
        for (int b = 0; b < blockValues.length; b++)
        {
            // Every block claims its key with a value (never skip): a key is never shared silently
            // (the tripwire), and a row of the block always reads its own block's answer.
            IndexHelper.GroupBlock block = blocks.get(b);
            blockValues[b] = aggregator.aggregate(cellAt, block.rows(), keep, type);
            results.put(block, blockValues[b]);
        }
        if (table == primary && !qualified)
        {
            // The groups are the evaluated dataset's own: every row's answer is written straight
            // from its block (XCUT PERF 3); the key map stays for the empty-grouping test. Never
            // for a qualified member: its record side is the bound source record, not the row.
            Map<Object, IDataValue> byKey = new java.util.LinkedHashMap<>(
                    Math.max(16, blockValues.length * 2));
            results.results().forEach((key, value) -> byKey.put(key, (IDataValue) value));
            return new Grouped(byKey, type, null,
                    Grouped.perRow(Math.toIntExact(primary.getRowCount()), noGroup.apply(type),
                            blocks, b -> blockValues[b]));
        }
        // GKI Q2 (owner 2026-09-27): a Char/Num key pair between the grouped table and the
        // evaluated one ERRORs the rule instead of silently matching no row. Once per evaluation,
        // never per row. A qualified member is judged against its SOURCE column (C2).
        requireCompatibleKeys(ctx, table, groupNames);
        // Built for this grouping and handed over — Grouped wraps it, never copies (W5/W6 L2).
        Map<Object, IDataValue> byKey = new java.util.LinkedHashMap<>(
                Math.max(16, blockValues.length * 2));
        results.results().forEach((key, value) -> byKey.put(key, (IDataValue) value));
        return new Grouped(byKey, type, null);
    }


    /**
     * The target table: the primary for no {@code domain=}, else the named dataset through
     * {@link SplitDomainResolution} (a split family unions, an un-unionable split ERRORs — the
     * {@code read_value} route), or {@code null} when the study lacks it.
     */
    static @Nullable IDataTable resolveTarget(EvaluationContext ctx, @Nullable String dataset)
    {
        return dataset == null ? ctx.getTable()
                : SplitDomainResolution.resolveTableOrThrow(ctx.getDatasetResolver(),
                        datasetName(dataset, ctx), ctx.getRuleId());
    }


    /**
     * The {@code domain=} dataset as it names a table of the study: a {@code --} in the literal
     * ({@code domain="SUPP--"}) resolves against the evaluated dataset exactly as
     * {@code Match_Datasets} names and the cross-dataset metadata functions resolve it
     * ({@link DatasetIdentity#resolveWildcard} — Fix #33: {@code SUPP--} on {@code AE} is
     * {@code SUPPAE}, and on {@code SUPPAE} too). Combined review of runbook W2–W8, F-W8-2: the
     * grouped readers looked the literal {@code SUPP--} up verbatim and silently answered their
     * absent-dataset default, while {@code crossDatasetVariableMetadata} resolved it — one
     * spelling, two meanings.
     *
     * @param dataset
     *            the authored {@code domain=} dataset
     * @param ctx
     *            the evaluation context (its table is the evaluated dataset)
     * @return the dataset name to resolve
     */
    static String datasetName(String dataset, EvaluationContext ctx)
    {
        String resolved = DatasetIdentity.resolveWildcard(dataset, ctx.getTable());
        return resolved != null ? resolved : dataset;
    }


    /** The {@code filter=} mask over {@code table}, or {@code null} for no filter. */
    static @Nullable BitSet filterMask(EvaluationContext ctx, IDataTable table,
            @Nullable Expr filter)
    {
        return filter == null ? null
                : NativeExprEvaluator.evaluate(filter, tableContext(ctx, table, List.of(filter)));
    }


    /** The EC-44 log context of a grouping: the rule id and the function name. */
    static String logContext(EvaluationContext ctx, String fn)
    {
        return (ctx.getRuleId() != null ? ctx.getRuleId() : "?") + " " + fn;
    }


    /**
     * A context scoped to {@code table} for the filter / the computed target: its own type
     * expectations (D76), the run's resolver and rule id, no joins and no {@code $} variables —
     * right-side only, by design (the {@code MatchFilter} / {@code ReadValue} shape).
     */
    static EvaluationContext tableContext(EvaluationContext ctx, IDataTable table, List<Expr> roots)
    {
        TypeExpectations expectations = TypeExpectations.of(roots);
        // suppMerge(false): the declared SUPP merge serves the PRIMARY table only
        // (PLAN-operation-replacements §2.3 / §7) — a bare name in filter= or a computed target
        // is a column of the target table, never a qualifier of that table's own SUPP-- (combined
        // review of runbook W2–W8, W2 M3).
        return EvaluationContext.builder().table(table).ruleId(ctx.getRuleId()).suppMerge(false)
                .domainName(table.getMetaData().getName()).datasetResolver(ctx.getDatasetResolver())
                .numericExpectedColumns(expectations.numericDefaultColumns())
                .numericExpectedDynamicSites(expectations.numericDynamicSites()).build();
    }


    static List<String> resolvePrefixes(List<String> names, EvaluationContext ctx)
    {
        List<String> out = new ArrayList<>(names.size());
        for (String name : names)
        {
            out.add(resolvePrefix(name, ctx));
        }
        return out;
    }


    /**
     * A {@code --} still present at evaluation (the per-dataset specialisation normally resolves it
     * before the call is compiled, D77) resolves against the run's domain prefix, exactly as the
     * retired executor's {@code resolvePrefixes} did.
     */
    static String resolvePrefix(String name, EvaluationContext ctx)
    {
        String prefix = ctx.getDomainPrefix();
        return prefix != null && name.contains("--") ? name.replace("--", prefix) : name;
    }

    // -----------------------------------------------------------------------
    // The aggregators
    // -----------------------------------------------------------------------


    /**
     * {@code max}: the winning cell, ranked by its own type (see the class comment).
     *
     * @param cellAt
     *            the target cell by absolute row
     * @param rows
     *            the block's rows
     * @param keep
     *            the filter mask, or {@code null}
     * @return the block's value
     */
    static IDataValue maxOf(IntFunction<IDataValue> cellAt, int[] rows, @Nullable BitSet keep)
    {
        MissingScan scan = new MissingScan();
        IDataValue bestNumeric = null;
        // The winning cell's number, read once (W5/W6 L3: re-reading getValue() per comparison
        // re-boxed it).
        Number bestNumber = null;
        boolean numeric = true;
        // The text ranking's candidates, filled only once a textual candidate is seen (a numeric
        // target never allocates beyond the two empty lists).
        List<IDataValue> candidates = new ArrayList<>(0);
        List<String> texts = new ArrayList<>(0);
        for (int r : rows)
        {
            if (keep != null && !keep.get(r))
            {
                continue;
            }
            IDataValue cell = cellAt.apply(r);
            if (scan.absorb(cell))
            {
                continue;
            }
            // The numeric payload first (W5/W6 L3): a present, valid number is always a candidate
            // (its text is never blank), so it needs neither the text nor the code-point blank
            // scan that Extremes.extremeCandidate runs.
            if (numeric && cell.getValue() instanceof Number n && !cell.isMissingOrInvalid())
            {
                if (bestNumber == null || compareNumeric(n, bestNumber) > 0)
                {
                    bestNumeric = cell;
                    bestNumber = n;
                }
                continue;
            }
            String raw = Extremes.extremeCandidate(cell);
            if (raw == null)
            {
                continue; // a present blank / whitespace-only cell is no candidate (EC-51)
            }
            // A textual candidate: the block ranks as text. Candidates seen so far were numeric
            // (a computed target may mix); they join the text ranking by their own text.
            if (numeric)
            {
                numeric = false;
                if (bestNumeric != null)
                {
                    // Re-collect the numeric candidates as text, in row order, so the text
                    // ranking sees every candidate of the block.
                    for (int again : rows)
                    {
                        if (again == r)
                        {
                            break;
                        }
                        if (keep != null && !keep.get(again))
                        {
                            continue;
                        }
                        IDataValue earlier = cellAt.apply(again);
                        String earlierRaw = TypedValue.missingIdentityOf(earlier) == null
                                ? Extremes.extremeCandidate(earlier)
                                : null;
                        if (earlierRaw != null)
                        {
                            candidates.add(earlier);
                            texts.add(earlierRaw);
                        }
                    }
                }
            }
            candidates.add(cell);
            texts.add(raw);
        }
        if (numeric)
        {
            return bestNumeric != null ? bestNumeric : scan.noCandidate(cellAt, rows, keep);
        }
        // A block turns textual only by admitting a textual candidate, so the text ranking holds
        // at least that one, and plain text order always answers (K3: no indeterminate arm).
        String winner = java.util.Objects.requireNonNull(Extremes.genericStringExtreme(texts, true),
                "a textual block holds the candidate that made it textual");
        return candidates.get(texts.indexOf(winner));
    }


    /**
     * {@code max_date} / {@code min_date}: the EC-46 / EC-51 date accumulator over the block's
     * candidates; the winning cell.
     */
    static IDataValue dateExtremeOf(IntFunction<IDataValue> cellAt, int[] rows,
            @Nullable BitSet keep, boolean findMax, boolean missingIsIndeterminate)
    {
        MissingScan scan = new MissingScan();
        Extremes.DateExtreme extreme = new Extremes.DateExtreme(findMax, missingIsIndeterminate);
        for (int r : rows)
        {
            if (keep != null && !keep.get(r))
            {
                continue;
            }
            IDataValue cell = cellAt.apply(r);
            scan.absorb(cell);
            // A filtered-out row is not a candidate at all, so it is never "a missing candidate":
            // the disposition applies to the rows the function actually reads.
            extreme.addCell(cell);
        }
        String resolved = extreme.result();
        if (resolved == null)
        {
            return scan.noCandidate(cellAt, rows, keep);
        }
        for (int r : rows)
        {
            if (keep != null && !keep.get(r))
            {
                continue;
            }
            IDataValue cell = cellAt.apply(r);
            if (resolved.equals(Extremes.extremeCandidate(cell)))
            {
                return cell;
            }
        }
        return ScalarSemantics.computedMissing(); // unreachable: the winner is one of the cells
    }

    /**
     * The missing-input bookkeeping of a block (the runbook's ⚑, D85c / D86a): whether any present
     * cell was seen, and the combined identity of the missing ones.
     */
    static final class MissingScan
    {

        private boolean anyPresent;

        private boolean anyMissing;

        private @Nullable MissingValue combined;

        /**
         * Absorbs {@code cell}: a missing one into the combined identity, a present one as seen.
         *
         * @param cell
         *            a read cell
         * @return {@code true} when {@code cell} is missing (absorbed into the identity)
         */
        boolean absorb(IDataValue cell)
        {
            MissingValue missing = TypedValue.missingIdentityOf(cell);
            if (missing == null)
            {
                anyPresent = true;
                return false;
            }
            anyMissing = true;
            combined = combined == null ? missing
                    : ArithmeticSemantics.combineIdentities(combined, missing);
            return true;
        }


        /**
         * The block's answer when no candidate won: the carried identity when every read cell was
         * missing (the first cell carrying the combined identity — D85c; none when two identities
         * collapsed to {@code MIS}), else the computed missing.
         */
        IDataValue noCandidate(IntFunction<IDataValue> cellAt, int[] rows, @Nullable BitSet keep)
        {
            if (anyPresent || !anyMissing || combined == null)
            {
                return ScalarSemantics.computedMissing();
            }
            for (int r : rows)
            {
                if (keep != null && !keep.get(r))
                {
                    continue;
                }
                IDataValue cell = cellAt.apply(r);
                if (TypedValue.missingIdentityOf(cell) == combined)
                {
                    return cell;
                }
            }
            return ScalarSemantics.computedMissing();
        }
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
