package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.ExpressionPrinter;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.ArgumentBinder;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.DatasetExpressionCache;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.expr.eval.FunctionRegistry;
import net.cumba.corej.core.expr.eval.Parameter;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.DataValueLong;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.GroupKey;
import net.cumba.datatable.values.GroupKeyPolicy;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * ⭐ Runbook W6 ({@code PLAN-record-count-function}) — the registry function
 * {@code record_count(domain=, filter=, group=, keep_missings=, regex=)}: how many rows of the
 * target table the filter keeps, per group and broadcast per row of the evaluated dataset (runbook
 * R2) when {@code group=} names columns, one dataset-level count otherwise. The retired
 * {@code RECORD_COUNT} operation and the registry's bare {@code record_count()} fast path are ONE
 * function now (P-Q2): the bare call is the ungrouped, unfiltered branch.
 *
 * <p>
 * The plan is W5's ({@link GroupedAggregate}): the call is read at load by {@link #spec} — its
 * column arguments are <b>names</b>, of the OTHER dataset under {@code domain=} (D10 / D13 Q4 /
 * D14) — and evaluated per execution. It declares <b>no target</b> (runbook R6, RETIRE-both: the
 * operation never read the one 40 corpus sites authored). A count is never missing: a group with no
 * kept row, a row of the evaluated dataset whose key has no group, and an absent {@code domain=}
 * dataset all answer {@code 0} — the retired operation's declared COUNT codomain (Q17-a:
 * <i>"record_count over a dataset that is not in the study counted ZERO"</i>).
 * </p>
 *
 * <p>
 * <b>The ungrouped path forms no groups</b> (D-W6-2, owner: performance first — 247 corpus sites
 * count whole tables): the answer is the row count, or the filter mask's cardinality, as a
 * dataset-level {@link ConstVector} — exactly EC-45 §1.3(3)'s one whole-table block, without the
 * per-row key.
 * </p>
 *
 * <p>
 * <b>The {@code $}-list splice</b> (D-W6-7, the retired executor's {@code expandGroupRefs}): a
 * {@code group=} member such as {@code $TIMING_VARIABLES} resolves at evaluation to the list a
 * dataset-level binding holds and is spliced element by element as column names
 * ({@link ListValueGuard#elements}: the list was born at a guard site, register NNL §1; a blank or
 * absent name partitions nothing, EC-44); a {@code String} is one name; anything else — a scalar, a
 * per-row binding, an unresolvable name — ERRORs the rule instead of silently answering nothing.
 * Because the derived contexts of one execution share the aggregate memo while their variables maps
 * may differ, a spliced call is memoised under the call text PLUS the expanded names (D-W6-8).
 * </p>
 *
 * <p>
 * <b>{@code regex=}</b> (D1, owner 2026-09-28 — declared by the operation and never read):
 * {@code is_unique_set}'s normalisation, verbatim ({@link GroupSemantics#regexColumnPatterns}): a
 * group column whose FIRST non-blank value matches the pattern is grouped by each present value's
 * first match, a non-matching present value by the empty key (the bucket a blank cell occupies), a
 * missing value by its own identity; a column whose sample does not match is left verbatim. The
 * blocks of {@link IndexHelper#groupByPresent} are merged by their normalised key, and the
 * evaluated dataset's rows derive their key the same way, so the two sides agree by construction
 * (CG0562: the binding and the Check's {@code is_unique_set} group the timing window at one
 * granularity).
 * </p>
 */
public final class RecordCount
{

    /** The function name. */
    public static final String NAME = "record_count";

    /** {@code regex=} — the key normalisation pattern (a string literal). */
    public static final String REGEX_PARAMETER = "regex";

    private static final GroupKeyPolicy KEY_POLICY = GroupKeyPolicy.KEEP_MISSING_KEYS;

    private static final IDataValue ZERO = new DataValueLong(0L);

    /**
     * The key a row of the evaluated dataset reads under a dropping {@code keep_missings=false}
     * policy when one of its key components is blank: no block ever carries it (the grouping drops
     * every block with a blank component), so the row finds no group and counts {@code 0}.
     */
    private static final Object NO_GROUP = new Object();

    private RecordCount()
    {
    }


    /**
     * Whether {@code c} is a grouped {@code record_count} call — one that authors {@code group=}
     * (an ungrouped call answers one dataset-level value). An empty {@code group=[]} is no third
     * case: the reader refuses it at load ({@code GroupedAggregate.readGroup}, "must name at least
     * one column"), so a rule carrying one never runs.
     *
     * @param c
     *            a call
     * @return {@code true} for a grouped call
     */
    public static boolean isGrouped(Expr.Call c)
    {
        return c.kwargs().get(GroupedAggregate.GROUP_PARAMETER) != null;
    }

    /**
     * A {@code record_count} call as authored, read at load.
     *
     * @param dataset
     *            the {@code domain=} dataset, or {@code null} for the evaluated dataset
     * @param filter
     *            the boolean filter over the target table's own columns, or {@code null}
     * @param group
     *            the group members — column names, and {@code $} names to splice
     * @param spliced
     *            whether {@code group} holds a {@code $} member
     * @param policy
     *            the missing-key policy
     * @param regex
     *            the compiled {@code regex=}, or {@code null}
     * @param canonical
     *            the canonical call text (the memo key, plus the expanded names when spliced)
     */
    public record Spec(@Nullable String dataset, @Nullable Expr filter, List<String> group,
            boolean spliced, GroupKeyPolicy policy, @Nullable Pattern regex, String canonical)
    {

        public Spec
        {
            group = List.copyOf(group);
        }


        /**
         * Evaluates the call for this run.
         *
         * @param run
         *            the evaluation run
         * @return the count vector — a {@link ConstVector} when ungrouped, a broadcast per row when
         *         grouped
         */
        public Vector evaluate(EvalRun run)
        {
            return RecordCount.evaluate(this, run);
        }
    }

    // -----------------------------------------------------------------------
    // The strict reader (load time)
    // -----------------------------------------------------------------------

    /**
     * Reads the call at load time, refusing every shape the plan cannot mean (the messages name the
     * spelling the author must use): a positional argument (the binder — the function declares no
     * target), a {@code --} reference under {@code domain=}, a dotted or {@code $} reference in
     * {@code filter=}, the retired {@code filter(K="v")} call form, a {@code group=} that is not a
     * list of column / {@code $} references, a non-boolean {@code keep_missings}, a {@code regex=}
     * that is not a string literal or does not compile, an unknown keyword (D19a).
     *
     * @param c
     *            the authored call
     * @return the read
     * @throws ExpressionException
     *             when the call is malformed — the rule's load error, with the reader's own message
     */
    public static Spec spec(Expr.Call c)
    {
        if (!NAME.equals(c.name()))
        {
            throw new ExpressionException("not " + NAME + ": '" + c.name() + "'");
        }
        FunctionDescriptor descriptor = FunctionRegistry.descriptor(NAME);
        if (descriptor == null)
        {
            throw new ExpressionException("no registry function '" + NAME + "'");
        }
        if (!c.args().isEmpty())
        {
            throw new ExpressionException(NAME + " takes no positional argument — it counts rows,"
                    + " never a column: name the column in filter=(…) or group=[…] instead");
        }
        List<@Nullable Expr> bound = ArgumentBinder.bind(descriptor, c);
        List<Parameter> params = descriptor.parameters();
        String dataset = GroupedAggregate.readDataset(NAME,
                GroupedAggregate.slot(bound, params, GroupedAggregate.DOMAIN_PARAMETER));
        boolean foreign = dataset != null;
        Expr filter = GroupedAggregate.readFilter(NAME,
                GroupedAggregate.slot(bound, params, GroupedAggregate.FILTER_PARAMETER), foreign);
        List<String> group = GroupedAggregate.readGroup(NAME,
                GroupedAggregate.slot(bound, params, GroupedAggregate.GROUP_PARAMETER), foreign,
                false, true);
        GroupKeyPolicy policy = GroupedAggregate.readPolicy(NAME,
                GroupedAggregate.slot(bound, params, GroupedAggregate.KEEP_MISSINGS_PARAMETER));
        Pattern regex = readRegex(GroupedAggregate.slot(bound, params, REGEX_PARAMETER));
        boolean spliced = group.stream().anyMatch(g -> g.startsWith("$"));
        return new Spec(dataset, filter, group, spliced, policy, regex, ExpressionPrinter.print(c));
    }


    private static @Nullable Pattern readRegex(@Nullable Expr regex)
    {
        if (regex == null)
        {
            return null;
        }
        if (!(regex instanceof Expr.Lit lit)
                || (lit.kind() != Expr.LitKind.STRING && lit.kind() != Expr.LitKind.REGEX))
        {
            throw new ExpressionException(
                    "`" + REGEX_PARAMETER + "` on `" + NAME + "` must be a string literal");
        }
        String text = String.valueOf(lit.value());
        if (text.isEmpty())
        {
            return null;
        }
        try
        {
            return Pattern.compile(text);
        }
        catch (PatternSyntaxException ex)
        {
            ExpressionException invalid = new ExpressionException("`" + REGEX_PARAMETER + "` on `"
                    + NAME + "` is not a valid regular expression: " + ex.getDescription());
            invalid.initCause(ex);
            throw invalid;
        }
    }

    // -----------------------------------------------------------------------
    // Evaluation
    // -----------------------------------------------------------------------


    private static Vector evaluate(Spec spec, EvalRun run)
    {
        EvaluationContext ctx = run.ctx();
        List<String> names = GroupedAggregate.resolvePrefixes(
                spec.spliced() ? splice(spec.group(), ctx, spec.dataset() != null) : spec.group(),
                ctx);
        // D-W6-8: a spliced call keys its memo on the expanded names too — the derived contexts of
        // one execution share the memo, and their variables maps may resolve the $-list
        // differently.
        String memoKey = spec.spliced() ? spec.canonical() + " group=" + names : spec.canonical();
        if (names.isEmpty())
        {
            Object memo = ctx.getAggregateMemo().computeIfAbsent(
                    DatasetExpressionCache.keyOf(ctx.getTable(), memoKey, ctx.getDomainPrefix()),
                    () -> countAll(ctx, spec));
            return (Vector) java.util.Objects.requireNonNull(memo, "the memo never stores null");
        }
        return GroupedAggregate.broadcast(run, memoKey, names,
                () -> countPerGroup(ctx, spec, names), _ -> ZERO);
    }


    /**
     * The splice: every {@code $} member replaced by the column names its binding holds.
     *
     * @param foreign
     *            whether the call names {@code domain=} — a spliced {@code --} name would then
     *            resolve against the evaluated dataset's prefix and be looked up in the OTHER
     *            table, silently partitioning nothing (combined review of runbook W2–W8, W5/W6 L6),
     *            so it ERRORs the rule as the reader refuses an authored one at load
     * @throws IllegalStateException
     *             for a {@code $} member that does not hold a list of names, or that splices a
     *             {@code --} name under {@code domain=} — the rule ERRORs (D-W6-7)
     */
    private static List<String> splice(List<String> group, EvaluationContext ctx, boolean foreign)
    {
        List<String> out = new ArrayList<>(group.size());
        for (String member : group)
        {
            if (!member.startsWith("$"))
            {
                out.add(member);
                continue;
            }
            Object value = ctx.resolveVariable(member);
            switch (value)
            {
            case Collection<?> items ->
            {
                // Born at a ListValueGuard site (a compiled list binding: ConstVector.of) — no
                // element is null (register NNL §1).
                for (Object item : ListValueGuard.elements(items))
                {
                    out.add(splicedName(member, item.toString(), ctx, foreign));
                }
            }
            case String name -> out.add(splicedName(member, name, ctx, foreign));
            case null, default -> throw new IllegalStateException(
                    "[" + ctx.getRuleId() + "] " + NAME + ": the group= member " + member
                            + " must hold a list of column names, but "
                            + (value == null ? "it resolves to nothing"
                                    : "it holds " + value.getClass().getSimpleName())
                            + " — only a dataset-level list binding can be spliced into group=");
            }
        }
        return out;
    }


    private static String splicedName(String member, String name, EvaluationContext ctx,
            boolean foreign)
    {
        if (foreign && name.startsWith("--") && name.indexOf('.') < 0)
        {
            throw new IllegalStateException("[" + ctx.getRuleId() + "] " + NAME
                    + ": the group= member " + member + " splices " + name
                    + " under domain= — a `--` name resolves against the evaluated dataset, not"
                    + " the domain; the list must name the domain's columns explicitly");
        }
        return name;
    }


    /** The ungrouped count: the whole target table, or the rows the filter keeps. */
    private static Vector countAll(EvaluationContext ctx, Spec spec)
    {
        IDataTable table = GroupedAggregate.resolveTarget(ctx, spec.dataset());
        if (table == null)
        {
            return ConstVector.of(0L); // Q17-a: an absent dataset counted zero records
        }
        BitSet keep = GroupedAggregate.filterMask(ctx, table, spec.filter());
        long count = keep == null ? table.getRowCount() : keep.cardinality();
        return ConstVector.of(count);
    }


    /** The grouped count over the target table: one value per group key. */
    private static GroupedAggregate.Grouped countPerGroup(EvaluationContext ctx, Spec spec,
            List<String> names)
    {
        IDataTable primary = ctx.getTable();
        IDataTable table = GroupedAggregate.resolveTarget(ctx, spec.dataset());
        if (table == null)
        {
            return new GroupedAggregate.Grouped(Map.of(), DataValueType.LONG, null);
        }
        BitSet keep = GroupedAggregate.filterMask(ctx, table, spec.filter());
        IndexHelper.Grouping grouping = IndexHelper.groupByPresent(table, names,
                GroupedAggregate.logContext(ctx, NAME), spec.policy());
        if (grouping == null)
        {
            // an unexpanded $-ref in the group list — the splice above resolved or threw, so
            // unreachable
            return new GroupedAggregate.Grouped(Map.of(), DataValueType.LONG, null);
        }
        Pattern regex = spec.regex();
        boolean normalise = regex != null;
        @Nullable
        Pattern[] patterns = regex != null ? columnPatterns(table, names, regex) : new Pattern[0];
        List<IndexHelper.GroupBlock> blocks = grouping.blocks();
        Map<Object, IDataValue> byKey = new LinkedHashMap<>(Math.max(16, blocks.size() * 2));
        // The raw grouping's tripwire (two blocks with one identity key) guards the index, regex
        // or not; the regex merge below is the one place two raw keys legitimately meet.
        IndexHelper.BlockResults claims = new IndexHelper.BlockResults(grouping);
        GroupKeyPolicy policy = spec.policy();
        // The key columns resolved once per side, never per row (XCUT PERF 3).
        int[] targetColumns = GroupKeyIdentity.columnIndices(table.getMetaData(), names);
        Map<Object, long[]> merged = new LinkedHashMap<>();
        IDataValue[] blockCounts = new IDataValue[blocks.size()];
        for (int b = 0; b < blockCounts.length; b++)
        {
            IndexHelper.GroupBlock block = blocks.get(b);
            int[] rows = block.rows();
            long count = keep == null ? rows.length : kept(rows, keep);
            // One cell per block, claimed and stored alike — no boxed Long beside it (W5/W6 L2).
            blockCounts[b] = new DataValueLong(count);
            claims.put(block, blockCounts[b]);
            if (!normalise)
            {
                byKey.put(block.key(), blockCounts[b]);
            }
            else if (rows.length > 0)
            {
                Object key = normalisedKey(table, targetColumns, patterns, policy, rows[0]);
                merged.computeIfAbsent(key, _ -> new long[1])[0] += count;
            }
        }
        GroupedAggregate.RowKey keyer = null;
        if (normalise)
        {
            merged.forEach((key, count) -> byKey.put(key, new DataValueLong(count[0])));
            int[] primaryColumns = GroupKeyIdentity.columnIndices(primary.getMetaData(), names);
            keyer = (_, t, row) -> normalisedKey(t, primaryColumns, patterns, policy, row);
        }
        else if (table == primary)
        {
            // The groups are the evaluated dataset's own: every row's count is written straight
            // from its block, with no key derived per row (XCUT PERF 3).
            return new GroupedAggregate.Grouped(byKey, DataValueType.LONG, null,
                    GroupedAggregate.Grouped.perRow(Math.toIntExact(primary.getRowCount()), ZERO,
                            blocks, b -> blockCounts[b]));
        }
        if (table != primary)
        {
            // GKI Q2: a Char/Num key pair between the counted table and the evaluated one ERRORs
            // the rule instead of silently matching no row. Once per evaluation, never per row.
            GroupKeyIdentity.requireCompatibleKeyColumns(table, names, primary, names);
        }
        return new GroupedAggregate.Grouped(byKey, DataValueType.LONG, keyer);
    }


    private static long kept(int[] rows, BitSet keep)
    {
        long count = 0;
        for (int r : rows)
        {
            if (keep.get(r))
            {
                count++;
            }
        }
        return count;
    }


    /**
     * The per-column normalisation patterns, parallel to {@code names}: {@code is_unique_set}'s
     * gate ({@link GroupSemantics#regexColumnPatterns}) over the columns the table carries, an
     * absent column left {@code null}.
     */
    private static @Nullable Pattern[] columnPatterns(IDataTable table, List<String> names,
            Pattern regex)
    {
        DataTableMeta meta = table.getMetaData();
        int[] presentIds = new int[names.size()];
        int[] presentAt = new int[names.size()];
        int n = 0;
        for (int i = 0; i < names.size(); i++)
        {
            int idx = meta.getColumnIndex(names.get(i));
            if (idx >= 0)
            {
                presentIds[n] = idx;
                presentAt[n] = i;
                n++;
            }
        }
        int[] ids = java.util.Arrays.copyOf(presentIds, n);
        @Nullable
        Pattern[] gated = GroupSemantics.regexColumnPatterns(table, ids,
                (int) Math.min(Integer.MAX_VALUE, table.getRowCount()), regex.pattern());
        @Nullable
        Pattern[] out = new Pattern[names.size()];
        for (int i = 0; i < n; i++)
        {
            out[presentAt[i]] = gated[i];
        }
        return out;
    }


    /**
     * A row's key over {@code names} with the per-column normalisation applied: the identity of
     * each component ({@link GroupKeyIdentity#identityKey}'s derivation — an absent column is
     * {@code ""}), except that a component of a normalised column is its first regex match, or the
     * empty key when the present value does not match. A blank or missing component is never
     * normalised (the question is about a value) and keeps its identity — except under a dropping
     * policy ({@code keep_missings=false}), where a blank component makes the whole key
     * {@link #NO_GROUP}: the grouping dropped every block with a blank component, and the empty key
     * a blank cell would take is the bucket a NON-matching present value occupies, so the blank row
     * read that bucket's count (combined review of runbook W2–W8, W5/W6 L1).
     */
    private static Object normalisedKey(IDataTable table, int[] columns,
            @Nullable Pattern[] patterns, GroupKeyPolicy policy, long row)
    {
        int n = columns.length;
        Object[] parts = new Object[n];
        for (int i = 0; i < n; i++)
        {
            int idx = columns[i];
            if (idx < 0)
            {
                parts[i] = "";
                continue;
            }
            IDataValue cell = table.getColumn(idx).getDataValue(row);
            if (!policy.keepMissings() && policy.isBlankKeyComponent(cell))
            {
                return NO_GROUP;
            }
            Pattern p = patterns[i];
            if (p == null || KEY_POLICY.isBlankKeyComponent(cell))
            {
                parts[i] = KEY_POLICY.keyIdentity(cell);
                continue;
            }
            // A regex-extracted key is a DERIVED text identity, for numeric components too (the
            // is_unique_set contract, D64g).
            Matcher m = p.matcher(KEY_POLICY.keyPart(cell).reportingForm());
            parts[i] = m.find() ? m.group() : "";
        }
        return n == 1 ? parts[0] : GroupKey.of(parts);
    }
}
