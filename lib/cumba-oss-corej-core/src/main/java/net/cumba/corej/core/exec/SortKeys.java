package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.ExpressionPrinter;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import org.jspecify.annotations.Nullable;

/**
 * Runbook W1b RO / SK (owner O16 / O17, 2026-10-08) — the ONE sort-key reader behind every
 * {@code by=}: {@code is_sorted_by(by=)}, {@code sequence(by=)} and, as a single ascending key,
 * {@code is_last_in_group}, {@code has_next_corresponding_record} and
 * {@code empty_within_except_last_row} (their {@code ordering=} renamed, O17).
 *
 * <p>
 * A sort-key list is {@code [asc(COL), desc(COL), COL, ...]} — or the one bare operand, read as the
 * one-member list (C5): {@code asc(X)} / {@code desc(X)} are descriptors (not callables —
 * {@code CompilerDispatchedCalls}), a <b>bare column means {@code asc}</b> (the SQL default, O16
 * #3), and a descriptor's column — or a bare member — is a {@link GroupSplice.Member}: a written
 * name, or a {@code $} / computed member spliced once per run ({@code asc($c)} with a dataset-level
 * name, {@code by=$keys} with a name list, each key ascending — O16 #2 / #3), judged at load for
 * its level by the splice roster ({@code GroupSplice.SPLICE_SLOTS}: stage A's G3 arm and the
 * loader's {@code ListOperandLevel.spliceRefusals} unwrap the descriptor). A descriptor's extra
 * keywords ({@code nulls=}) parse and are ignored, as before. A quoted name is a string, never a
 * column (R1).
 * </p>
 *
 * <p>
 * {@code is_sorted_by} reads the resolved NAMES only and ignores the direction (coordinator D20; W2
 * SB1 honours it); {@code sequence} orders by {@link CellOrder} with {@code desc} reversing the
 * cell order ({@link #rowComparator}).
 * </p>
 */
public final class SortKeys
{

    /** The parameter name, as every reader declares it (O17). */
    public static final String PARAMETER = "by";

    /** The pre-O17 spelling the three single-key readers still accept until W4 (OR2). */
    public static final String LEGACY_PARAMETER = "ordering";

    /** The two sort-key descriptors. */
    private static final List<String> DESCRIPTORS = List.of("asc", "desc");

    private SortKeys()
    {
    }

    /**
     * One sort key as authored: its column member and its direction.
     *
     * @param member
     *            the column — a written name, or a spliced member
     * @param descending
     *            {@code true} for {@code desc(...)}
     */
    public record Key(GroupSplice.Member member, boolean descending)
    {

    }


    /**
     * One sort key resolved for a run: the column name (prefix resolved by the caller) and its
     * direction.
     *
     * @param column
     *            the column name
     * @param descending
     *            {@code true} for {@code desc(...)}
     */
    public record ResolvedKey(String column, boolean descending)
    {

    }

    /**
     * Whether {@code e} is a sort-key descriptor call ({@code asc(X)} / {@code desc(X)}, one
     * positional).
     *
     * @param e
     *            an expression
     * @return {@code true} for a descriptor
     */
    public static boolean isDescriptor(@Nullable Expr e)
    {
        return e instanceof Expr.Call c && DESCRIPTORS.contains(c.name()) && c.args().size() == 1;
    }


    /**
     * The column operand a sort-key member names: a descriptor's one argument, else the member
     * itself — what the splice seams judge for its level.
     *
     * @param member
     *            an authored member
     * @return the column operand
     */
    public static Expr column(Expr member)
    {
        return member instanceof Expr.Call c && isDescriptor(c) ? c.args().get(0) : member;
    }


    /**
     * The authored members of a {@code by=} argument: the list literal's items, or the one bare
     * operand (a descriptor, a column, a {@code $} or computed expression — C5).
     *
     * @param by
     *            the argument
     * @return the members
     */
    public static List<Expr> members(Expr by)
    {
        if (by instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.LIST)
        {
            @SuppressWarnings("unchecked")
            List<Expr> items = (List<Expr>) lit.value();
            return items;
        }
        return List.of(by);
    }


    /**
     * Reads a {@code by=} argument at load.
     *
     * @param fn
     *            the function, for the messages
     * @param by
     *            the argument, or {@code null} when absent
     * @return the keys, empty when absent
     * @throws ExpressionException
     *             for a member that is neither a column, a descriptor of one nor a spliceable
     *             member, or a quoted name (R1)
     */
    public static List<Key> read(String fn, @Nullable Expr by)
    {
        if (by == null)
        {
            return List.of();
        }
        List<Key> out = new ArrayList<>();
        for (Expr member : members(by))
        {
            boolean descending = member instanceof Expr.Call c && isDescriptor(c)
                    && "desc".equals(c.name());
            Expr column = column(member);
            GroupSplice.Member spliced = GroupSplice.spliced(column);
            if (spliced != null)
            {
                out.add(new Key(spliced, descending));
                continue;
            }
            if (column instanceof Expr.Lit lit)
            {
                String bare = member instanceof Expr.Call c && isDescriptor(c)
                        ? c.name() + "(" + lit.value() + ")"
                        : String.valueOf(lit.value());
                throw new ExpressionException(fn + " sort key " + ExpressionPrinter.print(member)
                        + " takes a column reference, not a string literal — write " + bare
                        + "; a quoted name is a string, never a column");
            }
            if (!(column instanceof Expr.Ref ref) || !(ref.kind() == OperandKind.COLUMN
                    || ref.kind() == OperandKind.WILDCARD_COLUMN
                    || ref.kind() == OperandKind.DOTTED_REF))
            {
                throw new ExpressionException(fn + " sort key " + ExpressionPrinter.print(member)
                        + " must be a column, asc(COL) or desc(COL)");
            }
            out.add(new Key(new GroupSplice.Name(ref.name()), descending));
        }
        if (out.isEmpty())
        {
            throw new ExpressionException(fn + " " + PARAMETER + "=[] names no sort key");
        }
        return List.copyOf(out);
    }


    /**
     * O17's pure rename for the three single-key readers: {@code by=} beside {@code ordering=},
     * exactly one of them, holding exactly one ascending key ({@code by=AESEQ},
     * {@code by=[asc(AESEQ)]}, {@code by=$one}); more keys or {@code desc} is the load error
     * <i>"not supported for &lt;f&gt;"</i> — no new behaviour.
     *
     * @param fn
     *            the function
     * @param by
     *            the {@code by=} argument, or {@code null}
     * @param ordering
     *            the {@code ordering=} argument, or {@code null}
     * @return the one key's member
     * @throws ExpressionException
     *             when both or neither are written, or the key is not one ascending column
     */
    public static GroupSplice.Member single(String fn, @Nullable Expr by, @Nullable Expr ordering)
    {
        if (by != null && ordering != null)
        {
            throw new ExpressionException(
                    fn + " takes " + PARAMETER + "= or " + LEGACY_PARAMETER + "=, not both ("
                            + LEGACY_PARAMETER + "= is the older spelling of " + PARAMETER + "=)");
        }
        Expr key = by != null ? by : ordering;
        if (key == null)
        {
            throw new ExpressionException(
                    fn + " requires " + PARAMETER + "=, the column that orders" + " the group ("
                            + LEGACY_PARAMETER + "= is the older spelling)");
        }
        List<Key> keys = read(fn, key);
        if (keys.size() != 1 || keys.get(0).descending())
        {
            throw new ExpressionException("more than one sort key, or desc, is not supported for "
                    + fn + ": write " + PARAMETER + "=COL or " + PARAMETER + "=[asc(COL)] ("
                    + ExpressionPrinter.print(key) + ")");
        }
        return keys.get(0).member();
    }


    /**
     * The load-time seam of the three single-key readers ({@code RulePackageLoader}'s tailored
     * readers, O17): the call bound against its descriptor, its {@code by=} / {@code ordering=}
     * judged by {@link #single} — so {@code desc}, two keys, both or neither are load errors on the
     * inline Check surface as on a binding.
     *
     * @param call
     *            the authored call
     * @throws ExpressionException
     *             the reader's refusal
     */
    public static void checkSingleKeyCall(Expr.Call call)
    {
        net.cumba.corej.core.expr.eval.FunctionDescriptor descriptor = net.cumba.corej.core.expr.eval.FunctionRegistry
                .descriptor(call.name());
        if (descriptor == null)
        {
            return;
        }
        List<@Nullable Expr> bound = net.cumba.corej.core.expr.eval.ArgumentBinder.bind(descriptor,
                call);
        Expr by = null;
        Expr ordering = null;
        List<net.cumba.corej.core.expr.eval.Parameter> params = descriptor.parameters();
        for (int i = 0; i < params.size() && i < bound.size(); i++)
        {
            if (PARAMETER.equals(params.get(i).name()))
            {
                by = bound.get(i);
            }
            else if (LEGACY_PARAMETER.equals(params.get(i).name()))
            {
                ordering = bound.get(i);
            }
        }
        single(call.name(), by, ordering);
    }


    /**
     * The keys of a run: every spliced member resolved to the column names it holds (each name one
     * key, the member's direction), a written name as authored. Prefixes are the caller's
     * ({@code resolveDomainPrefix}).
     *
     * @param fn
     *            the function, for the messages
     * @param keys
     *            the keys as authored
     * @param ctx
     *            the evaluation context
     * @param foreign
     *            whether the call names {@code domain=} ({@link GroupSplice#splice})
     * @return the resolved keys
     */
    public static List<ResolvedKey> resolve(String fn, List<Key> keys, EvaluationContext ctx,
            boolean foreign)
    {
        List<ResolvedKey> out = new ArrayList<>(keys.size());
        for (Key key : keys)
        {
            if (key.member() instanceof GroupSplice.Name name)
            {
                out.add(new ResolvedKey(name.name(), key.descending()));
                continue;
            }
            for (String column : GroupSplice.splice(fn, PARAMETER + "= sort key",
                    List.of(key.member()), ctx, foreign,
                    "a sort key is a column of the ordered table, written bare"))
            {
                out.add(new ResolvedKey(column, key.descending()));
            }
        }
        return List.copyOf(out);
    }


    /**
     * The column names of resolved keys, in order (what {@code is_sorted_by} reads, D20).
     *
     * @param keys
     *            resolved keys
     * @return the names
     */
    public static List<String> columns(List<ResolvedKey> keys)
    {
        List<String> out = new ArrayList<>(keys.size());
        for (ResolvedKey key : keys)
        {
            out.add(key.column());
        }
        return out;
    }


    /**
     * The row order of {@code table} under {@code keys} — {@link CellOrder} per key, {@code desc}
     * reversing it, an absent key column ordering nothing (EC-44), ties left to the stable sort
     * that applies it (file order).
     *
     * @param table
     *            the table whose rows are ordered
     * @param keys
     *            the resolved keys
     * @return the comparator over row indices
     */
    public static Comparator<Integer> rowComparator(IDataTable table, List<ResolvedKey> keys)
    {
        DataTableMeta meta = table.getMetaData();
        List<IDataTableColumn> columns = new ArrayList<>(keys.size());
        List<Boolean> descending = new ArrayList<>(keys.size());
        for (ResolvedKey key : keys)
        {
            int idx = meta.getColumnIndex(key.column());
            if (idx >= 0)
            {
                columns.add(table.getColumn(idx));
                descending.add(key.descending());
            }
        }
        return (a, b) ->
        {
            for (int i = 0; i < columns.size(); i++)
            {
                IDataTableColumn column = columns.get(i);
                Object va = CellOrder.valueOf(column.getDataValue(a));
                Object vb = CellOrder.valueOf(column.getDataValue(b));
                // desc reverses the cell order by swapping the operands (never by negation)
                int c = descending.get(i) ? CellOrder.compare(vb, va) : CellOrder.compare(va, vb);
                if (c != 0)
                {
                    return c;
                }
            }
            return 0;
        };
    }
}
