package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntBinaryOperator;
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
 * {@code empty_within_except_last_row} (their {@code ordering=} renamed in place, O17; the old
 * spelling a load error with the hint "write by=" since runbook W4 OR2).
 *
 * <p>
 * A sort-key list is {@code [asc(COL), desc(COL), COL, ...]} — or the one bare operand, read as the
 * one-member list (C5): {@code asc(X)} / {@code desc(X)} are descriptors (not callables —
 * {@code CompilerDispatchedCalls}), a <b>bare column means {@code asc}</b> (the SQL default, O16
 * #3), and a descriptor's column — or a bare member — is a {@link GroupSplice.Member}: a written
 * name, or a {@code $} / computed member spliced once per run ({@code asc($c)} with a dataset-level
 * name, {@code by=$keys} with a name list, each key ascending — O16 #2 / #3), judged at load for
 * its level by the splice roster ({@code GroupSplice.SPLICE_SLOTS}: stage A's G3 arm and the
 * loader's {@code ListOperandLevel.spliceRefusals} unwrap the descriptor). A descriptor's
 * {@code nulls="first"} / {@code nulls="last"} is read into the key ({@link Nulls}); since runbook
 * W4 SB3 any other {@code nulls=} value (a case variant, another string, a non-literal) and any
 * other descriptor keyword is a load error — before it parsed and was silently ignored. A quoted
 * name is a string, never a column (R1).
 * </p>
 *
 * <p>
 * Both order by {@link CellOrder}, {@code desc} reversing a key's cell order, and both place a
 * blank or missing key cell by the key's {@code nulls=} — first unless {@code nulls="last"}, under
 * {@code desc} too ({@link #sortRowsPlacingNulls}; runbook W2 SB1, W2-D3 for {@code is_sorted_by},
 * the W2 fold's R5 PC-1 for {@code sequence}, O16: "nulls= and direction honoured" in both).
 * </p>
 */
public final class SortKeys
{

    /** The parameter name, as every reader declares it (O17). */
    public static final String PARAMETER = "by";

    /**
     * The pre-O17 spelling of the three single-key readers, retired in runbook W4 OR2: written, it
     * is the load error {@link #rejectRetiredOrdering} with the hint "write by=".
     */
    public static final String RETIRED_PARAMETER = "ordering";

    /** The two sort-key descriptors. */
    private static final List<String> DESCRIPTORS = List.of("asc", "desc");

    /** A descriptor's placement keyword ({@code asc(X, nulls="last")}). */
    public static final String NULLS_PARAMETER = "nulls";

    /** The run length below which {@link #stableSort} sorts by insertion. */
    private static final int INSERTION_THRESHOLD = 16;

    private SortKeys()
    {
    }

    /**
     * Where a sort key's blank and missing cells go ({@code nulls=} on its descriptor) —
     * independent of the direction (W2-D3).
     */
    public enum Nulls
    {
        /** Before every value — the placement when {@code nulls=} is omitted. */
        FIRST,
        /** After every value. */
        LAST
    }


    /**
     * One sort key as authored: its column member, its direction and its {@code nulls=} placement.
     *
     * @param member
     *            the column — a written name, or a spliced member
     * @param descending
     *            {@code true} for {@code desc(...)}
     * @param nulls
     *            the written {@code nulls=} placement; absent when omitted
     */
    public record Key(GroupSplice.Member member, boolean descending, @Nullable Nulls nulls)
    {

    }


    /**
     * One sort key resolved for a run: the column name (prefix resolved by the caller), its
     * direction and its {@code nulls=} placement.
     *
     * @param column
     *            the column name
     * @param descending
     *            {@code true} for {@code desc(...)}
     * @param nulls
     *            the written {@code nulls=} placement; absent when omitted
     */
    public record ResolvedKey(String column, boolean descending, @Nullable Nulls nulls)
    {

        /**
         * A key with no written {@code nulls=}.
         *
         * @param column
         *            the column name
         * @param descending
         *            {@code true} for {@code desc(...)}
         */
        public ResolvedKey(String column, boolean descending)
        {
            this(column, descending, null);
        }


        /**
         * Whether this key's blank and missing cells go last ({@code nulls="last"}); first when
         * omitted, whatever the direction.
         *
         * @return {@code true} for {@code nulls="last"}
         */
        public boolean nullsLast()
        {
            return nulls == Nulls.LAST;
        }
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
     * Whether {@code parameter} is a sort-key slot — {@code by=} — the only slot a descriptor may
     * stand in.
     *
     * @param parameter
     *            the parameter name
     * @return {@code true} for a sort-key slot
     */
    public static boolean isSortKeyParameter(String parameter)
    {
        return PARAMETER.equals(parameter);
    }


    /**
     * The load refusal of a sort-key descriptor written outside a sort-key slot (runbook W1b fold,
     * R5 A-L4) — one sentence for stage A and the loader's seam pass: {@code within=asc($w)} used
     * to load (the seams judged the member it wraps) and then ERROR at run time.
     *
     * @param fn
     *            the function
     * @param parameter
     *            the slot it is written in
     * @param where
     *            where it is written (the loader's binding name), or {@code null}
     * @param descriptor
     *            the descriptor as authored
     * @return the message
     */
    public static String descriptorOutsideSortKey(String fn, String parameter,
            @Nullable String where, Expr descriptor)
    {
        return "argument '" + parameter + "' of '" + fn + "'"
                + (where == null ? "" : " in " + where) + " takes column names, not the sort-key"
                + " descriptor " + ExpressionPrinter.print(descriptor)
                + " — asc() / desc() order a " + PARAMETER + "= list only; write the column bare";
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
            Nulls nulls = nullsOf(fn, member);
            Expr column = column(member);
            GroupSplice.Member spliced = GroupSplice.spliced(column);
            if (spliced != null)
            {
                out.add(new Key(spliced, descending, nulls));
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
            // R5 A-L1: a wildcard key is the dotless `--X` domain prefix the pre-SK readers took
            // (GroupedAggregate.isDomainPrefixWildcard); `DM.--SEQ` / `SUPP--.QVAL` order nothing
            if (!(column instanceof Expr.Ref ref) || !(ref.kind() == OperandKind.COLUMN
                    || (ref.kind() == OperandKind.WILDCARD_COLUMN
                            && GroupedAggregate.isDomainPrefixWildcard(ref.name()))
                    || ref.kind() == OperandKind.DOTTED_REF))
            {
                throw new ExpressionException(fn + " sort key " + ExpressionPrinter.print(member)
                        + " must be a column, asc(COL) or desc(COL)");
            }
            out.add(new Key(new GroupSplice.Name(ref.name()), descending, nulls));
        }
        if (out.isEmpty())
        {
            throw new ExpressionException(fn + " " + PARAMETER + "=[] names no sort key");
        }
        return List.copyOf(out);
    }


    /**
     * The {@code nulls=} placement a descriptor writes: {@code "first"} / {@code "last"}, or
     * {@code null} when omitted (or {@code member} is no descriptor). Runbook W4 SB3
     * ({@code PLAN-is-sorted-by-direction} N2, coordinator W4-D12): only the two lower-case string
     * literals are a placement — a case variant ({@code "Last"} is refused, not folded), another
     * string or a non-literal is a load error, and so is a descriptor keyword other than
     * {@code nulls=} ({@code asc(X, null="last")}); before SB3 each parsed and silently kept the
     * default placement.
     *
     * @throws ExpressionException
     *             for an unknown descriptor keyword or {@code nulls=} value
     */
    private static @Nullable Nulls nullsOf(String fn, Expr member)
    {
        if (!(member instanceof Expr.Call c) || !isDescriptor(c))
        {
            return null;
        }
        for (String keyword : c.kwargs().keySet())
        {
            if (!NULLS_PARAMETER.equals(keyword))
            {
                throw new ExpressionException(fn + " sort key " + ExpressionPrinter.print(member)
                        + ": a sort-key descriptor takes only " + NULLS_PARAMETER + "=, not "
                        + keyword + "=");
            }
        }
        Expr written = c.kwargs().get(NULLS_PARAMETER);
        if (written == null)
        {
            return null;
        }
        Object value = written instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.STRING
                ? lit.value()
                : null;
        if ("first".equals(value))
        {
            return Nulls.FIRST;
        }
        if ("last".equals(value))
        {
            return Nulls.LAST;
        }
        throw new ExpressionException(fn + " sort key " + ExpressionPrinter.print(member) + ": "
                + NULLS_PARAMETER + "= takes \"first\" or \"last\" (lower case), not "
                + ExpressionPrinter.print(written));
    }


    /**
     * O17's pure rename for the three single-key readers: {@code by=} holding exactly one ascending
     * key ({@code by=AESEQ}, {@code by=[asc(AESEQ)]}, {@code by=$one}); more keys, {@code desc} or
     * a {@code nulls=} placement is the load error <i>"not supported for &lt;f&gt;"</i> — no new
     * behaviour (W4 SB3 / O17).
     *
     * @param fn
     *            the function
     * @param by
     *            the {@code by=} argument, or {@code null}
     * @return the one key's member
     * @throws ExpressionException
     *             when it is absent, or the key is not one ascending column
     */
    public static GroupSplice.Member single(String fn, @Nullable Expr by)
    {
        if (by == null)
        {
            throw new ExpressionException(
                    fn + " requires " + PARAMETER + "=, the column that orders the group");
        }
        List<Key> keys = read(fn, by);
        if (keys.size() != 1 || keys.get(0).descending() || keys.get(0).nulls() != null)
        {
            throw new ExpressionException("more than one sort key, desc or " + NULLS_PARAMETER
                    + "= is not supported for " + fn + ": write " + PARAMETER + "=COL or "
                    + PARAMETER + "=[asc(COL)] (" + ExpressionPrinter.print(by) + ")");
        }
        return keys.get(0).member();
    }


    /**
     * Runbook W4 OR2 (owner O17, coordinator W4-D2): the retired {@code ordering=} keyword of the
     * three single-key readers, read from the WRITTEN keywords — the load error names the rename
     * ("write by="), where the binder would only say the parameter does not exist. The loader's
     * seam pass ({@link #checkSingleKeyCall}) runs it before stage A on every path; the compiler's
     * arms run it too, for a call compiled outside the seam.
     *
     * @param call
     *            the authored call
     * @throws ExpressionException
     *             when {@code call} writes {@code ordering=}
     */
    public static void rejectRetiredOrdering(Expr.Call call)
    {
        Expr ordering = call.kwargs().get(RETIRED_PARAMETER);
        if (ordering != null)
        {
            throw new ExpressionException(call.name() + ": " + RETIRED_PARAMETER
                    + "= is retired \u2014 write " + PARAMETER + "="
                    + ExpressionPrinter.print(ordering) + " (the same one ascending key, O17)");
        }
    }

    /**
     * The single-key readers whose compiler arm reads {@code by=} from the KEYWORDS only
     * ({@code ExprCompiler.orderingMember}); {@code is_last_in_group} reads its bound slot, so its
     * positional key keeps its meaning (O17's rename in place).
     */
    private static final java.util.Set<String> KEYWORD_ONLY_READERS = java.util.Set
            .of("empty_within_except_last_row", "has_next_corresponding_record");

    /**
     * The load-time seam of the three single-key readers ({@code RulePackageLoader}'s tailored
     * readers, O17): a retired {@code ordering=} refused first ({@link #rejectRetiredOrdering}),
     * then the call bound against its descriptor and its {@code by=} judged by {@link #single} — so
     * {@code desc}, two keys, {@code nulls=} or no key are load errors on the inline Check surface
     * as on a binding. A POSITIONAL key of {@code empty_within_except_last_row} /
     * {@code has_next_corresponding_record} is refused naming the keyword (W4 fold, R5 P1-M1):
     * their arms read {@code by=} by keyword, so it bound here and was then lost there — the rule
     * degraded; reading it would be a new feature, and O17 is "only a rename".
     *
     * @param call
     *            the authored call
     * @throws ExpressionException
     *             the reader's refusal
     */
    public static void checkSingleKeyCall(Expr.Call call)
    {
        rejectRetiredOrdering(call);
        Expr by = boundBy(call);
        if (by != null && KEYWORD_ONLY_READERS.contains(call.name())
                && !call.kwargs().containsKey(PARAMETER))
        {
            throw new ExpressionException(
                    call.name() + " takes its sort key by keyword \u2014 write " + PARAMETER + "="
                            + ExpressionPrinter.print(by));
        }
        single(call.name(), by);
    }


    /**
     * Runbook W4 SB3: the load-time seam of {@code is_sorted_by} ({@code RulePackageLoader}'s
     * tailored readers) — its {@code by=} read by {@link #read}, so an unknown {@code nulls=} value
     * or descriptor keyword is the rule's load error on every path. (The compiler's own refusal
     * only degrades an inline Check, and stage A never walks a Check-less rule.)
     *
     * @param call
     *            the authored call
     * @throws ExpressionException
     *             the reader's refusal
     */
    public static void checkSortKeyCall(Expr.Call call)
    {
        read(call.name(), boundBy(call));
    }


    /** The {@code by=} slot of {@code call} bound against its descriptor, or {@code null}. */
    private static @Nullable Expr boundBy(Expr.Call call)
    {
        net.cumba.corej.core.expr.eval.FunctionDescriptor descriptor = net.cumba.corej.core.expr.eval.FunctionRegistry
                .descriptor(call.name());
        if (descriptor == null)
        {
            return null;
        }
        List<@Nullable Expr> bound = net.cumba.corej.core.expr.eval.ArgumentBinder.bind(descriptor,
                call);
        List<net.cumba.corej.core.expr.eval.Parameter> params = descriptor.parameters();
        for (int i = 0; i < params.size() && i < bound.size(); i++)
        {
            if (PARAMETER.equals(params.get(i).name()))
            {
                return bound.get(i);
            }
        }
        return null;
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
                out.add(new ResolvedKey(name.name(), key.descending(), key.nulls()));
                continue;
            }
            for (String column : GroupSplice.splice(fn, PARAMETER + "= sort key",
                    List.of(key.member()), ctx, foreign,
                    "a sort key is a column of the ordered table, written bare"))
            {
                out.add(new ResolvedKey(column, key.descending(), key.nulls()));
            }
        }
        return List.copyOf(out);
    }


    /**
     * The column names of resolved keys, in order.
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
     * The rows of {@code table} in the order of {@code keys} — {@link CellOrder} per key, each key
     * classified over its cells in these rows (owner O22), {@code desc} reversing it, an absent key
     * column ordering nothing (EC-44), ties kept in the order given (a stable sort: file order) —
     * with the blank and missing cells of each key placed by its {@code nulls=}: first unless
     * {@code nulls="last"}, whatever the key's direction (W2-D3), two of them tied (file order).
     * The order of {@code is_sorted_by} (runbook W2 SB1 / SB2) and of {@code sequence} (W2 fold, R5
     * PC-1). ⭐ Each key is decorated ONCE ({@link CellOrder#key}) and an {@code int[]} of positions
     * is merge-sorted over the decorated arrays, so no comparison re-reads a cell (R5 A-M2).
     *
     * @param table
     *            the table whose rows are ordered
     * @param keys
     *            the resolved keys
     * @param rows
     *            the row indices to order (not modified)
     * @return the row indices in key order
     */
    public static int[] sortRowsPlacingNulls(IDataTable table, List<ResolvedKey> keys, int[] rows)
    {
        return sort(table, keys, rows);
    }


    private static int[] sort(IDataTable table, List<ResolvedKey> keys, int[] rows)
    {
        DataTableMeta meta = table.getMetaData();
        List<CellOrder.Key> decorated = new ArrayList<>(keys.size());
        List<ResolvedKey> present = new ArrayList<>(keys.size());
        for (ResolvedKey key : keys)
        {
            int idx = meta.getColumnIndex(key.column());
            if (idx >= 0)
            {
                IDataTableColumn column = table.getColumn(idx);
                List<Object> values = new ArrayList<>(rows.length);
                for (int row : rows)
                {
                    values.add(CellOrder.valueOf(column.getDataValue(row)));
                }
                decorated.add(CellOrder.key(values));
                present.add(key);
            }
        }
        int n = rows.length;
        int[] positions = new int[n];
        for (int i = 0; i < n; i++)
        {
            positions[i] = i;
        }
        if (!decorated.isEmpty())
        {
            int width = decorated.size();
            CellOrder.Key[] byKey = decorated.toArray(CellOrder.Key[]::new);
            boolean[] reversed = new boolean[width];
            boolean[] nullsLast = new boolean[width];
            for (int k = 0; k < width; k++)
            {
                reversed[k] = present.get(k).descending();
                nullsLast[k] = present.get(k).nullsLast();
            }
            stableSort(positions, (a, b) ->
            {
                for (int k = 0; k < width; k++)
                {
                    boolean na = byKey[k].blankOrMissing(a);
                    boolean nb = byKey[k].blankOrMissing(b);
                    if (na || nb)
                    {
                        if (na && nb)
                        {
                            continue;
                        }
                        // the placement is the key's, never the direction's (W2-D3)
                        return na == nullsLast[k] ? 1 : -1;
                    }
                    // desc reverses the cell order by swapping the operands (never by negation)
                    int c = reversed[k] ? byKey[k].compare(b, a) : byKey[k].compare(a, b);
                    if (c != 0)
                    {
                        return c;
                    }
                }
                return 0;
            });
        }
        int[] out = new int[n];
        for (int i = 0; i < n; i++)
        {
            out[i] = rows[positions[i]];
        }
        return out;
    }


    /** A stable merge sort of {@code a} under {@code order} (insertion sort below 16 elements). */
    static void stableSort(int[] a, IntBinaryOperator order)
    {
        if (a.length > 1)
        {
            mergeSort(a, new int[a.length], 0, a.length, order);
        }
    }


    private static void mergeSort(int[] a, int[] scratch, int from, int to, IntBinaryOperator order)
    {
        if (to - from <= INSERTION_THRESHOLD)
        {
            for (int i = from + 1; i < to; i++)
            {
                int value = a[i];
                int j = i;
                // > 0, never >= 0: an equal element stays before the one inserted (stable)
                while (j > from && order.applyAsInt(a[j - 1], value) > 0)
                {
                    j--;
                }
                if (j < i)
                {
                    System.arraycopy(a, j, a, j + 1, i - j);
                    a[j] = value;
                }
            }
            return;
        }
        int middle = (from + to) >>> 1;
        mergeSort(a, scratch, from, middle, order);
        mergeSort(a, scratch, middle, to, order);
        if (order.applyAsInt(a[middle - 1], a[middle]) <= 0)
        {
            return; // already in order
        }
        System.arraycopy(a, from, scratch, from, to - from);
        int left = from;
        int right = middle;
        for (int k = from; k < to; k++)
        {
            // <= keeps the left (earlier) element first on a tie: stable
            if (right >= to
                    || (left < middle && order.applyAsInt(scratch[left], scratch[right]) <= 0))
            {
                a[k] = scratch[left++];
            }
            else
            {
                a[k] = scratch[right++];
            }
        }
    }
}
