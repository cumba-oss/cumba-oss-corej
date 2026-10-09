package net.cumba.corej.core.exec;

import java.io.Serial;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.cumba.corej.core.expr.ExpressionPrinter;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.BindingDomains;
import net.cumba.corej.core.expr.eval.Domain;
import net.cumba.corej.core.expr.eval.DomainScan;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.NativeExprEvaluator;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.corej.core.model.MatchDataset;
import org.jspecify.annotations.Nullable;

/**
 * The splice of a column-name list — ONE implementation for {@code record_count}'s {@code group=}
 * (D-W6-7, the retired executor's {@code expandGroupRefs}), the member list of
 * {@code is_unique_set} / {@code is_not_unique_set} ({@code PLAN-qualified-name-uniformity-review}
 * N15 / N29), since runbook W1a E1 the {@code columns} list of {@code values}
 * ({@code ExprCompiler.valuesPlan}, {@code PLAN-elementwise-list-functions} §3.2) and, since W1a
 * BS2b ({@code PLAN-binding-substitutability} C6, ruled Q2: <i>"every list&lt;column-reference&gt;
 * slot splices"</i>), the {@code group=} of the grouped readers {@code max} / {@code max_date} /
 * {@code min_date} ({@code GroupedAggregate}), {@code read_value} and {@code distinct}, and the
 * name lists of the compiler-dispatched readers — {@code is_last_in_group}'s and
 * {@code has_mixed_emptiness_within_group}'s {@code group=} / {@code qualifying_any_populated=},
 * the {@code keys=} of {@code is_(not_)unique_relationship} and
 * {@code is_inconsistent_across_dataset} ({@code ExprCompiler.strictColumnMembers} /
 * {@code keyMembers}). The roster is {@link #SPLICE_SLOTS}, read by every seam that has to agree
 * with the readers: stage A's G3 arm ({@code StageAChecker.splices}), the loader's level seam
 * ({@code ListOperandLevel.spliceRefusals} through {@link #spliceSlots}) and the bind-time name
 * inlining ({@link #spliceLists}).
 *
 * <p>
 * A member is a {@link Member}: a plain {@link Name} passes through; a {@link Bound} {@code $}
 * member resolves at evaluation to what a dataset-level binding holds; a {@link Computed} member —
 * the expression such a binding would hold, written in the list
 * ({@code PLAN-binding-substitutability} C4 / C5, ruled Q1 / Q2 and D3 / D16: where {@code $x} is
 * accepted, the expression {@code $x} holds is accepted too, W1a BS2) — is evaluated at the same
 * place, on the same context. Either way a list is spliced element by element as column names
 * ({@link ListValueGuard#elements}: the list was born at a guard site, register NNL §1), a
 * {@code String} is one name. Anything else — a scalar, a per-row binding, an unresolvable name —
 * is a rule {@code ERROR}, never a silent answer. So is a spliced <b>qualified</b> name under
 * {@code domain=}: there a qualified {@code group=} member keys the record side through the join
 * (C2) and is judged at load (D-DOMAIN / D-SRC / D-REGEX), and a spliced one never reaches those
 * gates — the join type D-SRC requires is not on the evaluation context, so it is refused. Without
 * {@code domain=} a qualified name is a column of the rule's one evaluation table like any other
 * ({@code PLAN-merged-evaluation-table} W3c, N5 lifted per MQ8): it splices through and is read by
 * {@link GroupReadTable}.
 * </p>
 *
 * <p>
 * The LEVEL of a spliced member is judged at load, for both spellings: stage A's G3 arm where it
 * walks the rule ({@code StageAChecker.findPerRowSplice}, {@code PARAMETER_TYPE}), the loader's
 * seam pass where it does not ({@code ListOperandLevel.firstRefusal}: a Check-less rule's bindings,
 * a {@code Match_Datasets} Filter). A {@link Bound} per-row member that still reaches evaluation
 * hands over its vector and is refused here; a {@link Computed} one is read as its first value, so
 * the load-time judgement is what keeps it from reading row 0 — the reason the loader twin exists.
 * </p>
 *
 * <p>
 * ⚠ The two call sites had drifted before this class (N29): the uniqueness splice passed a
 * qualified name, a {@code String} binding and a non-list binding through RAW, and the downstream
 * column lookup then dropped each silently as an absent member. The refusals are a
 * {@link GroupSpliceException}, caught by {@code RuleRunner} on the {@code "__error__"} sentinel
 * channel — until N15 the {@code IllegalStateException} escaped {@code RuleRunner} and only the
 * validator's catch-all turned it into an anonymous ERROR.
 * </p>
 */
public final class GroupSplice
{

    private GroupSplice()
    {
    }

    /** The parameter name of the uniqueness operators' splice list, as the registry declares it. */
    public static final String MEMBERS_PARAMETER = "members";

    /**
     * The parameter name of the relationship / consistency operators' key list
     * ({@code is_(not_)unique_relationship}, {@code is_inconsistent_across_dataset}), as the
     * registry declares it.
     */
    public static final String KEYS_PARAMETER = "keys";

    /**
     * The third caller of the one splice ({@code PLAN-elementwise-list-functions} §3.2, runbook W1a
     * E1): {@code values(list<column-reference>)} reads the named columns' cells per row, and its
     * {@link #VALUES_PARAMETER columns} list splices a {@code $} binding or a computed expression
     * of names exactly as the two readers above do — {@code values($keys)} reads as
     * {@code values([$keys])} (C5).
     */
    public static final String VALUES_CALL = "values";

    /** The parameter name of {@code values}' splice list, as the registry declares it. */
    public static final String VALUES_PARAMETER = "columns";

    /** The parameter name of the partitioning slots ({@code within=}). */
    public static final String WITHIN_PARAMETER = "within";

    /** {@code is_not_unique_member_set} (runbook W1b SS1). */
    public static final String NOT_UNIQUE_MEMBER_SET = "is_not_unique_member_set";

    /**
     * The slots whose members are sort keys — {@code asc(X)} / {@code desc(X)} descriptors around
     * the member the seams judge ({@link SortKeys#column}), and a bare column meaning {@code asc}.
     */
    private static final java.util.Set<String> SORT_KEY_PARAMETERS = java.util.Set
            .of(SortKeys.PARAMETER);

    /**
     * The splice slots — function name to the names of its parameters whose list of column names
     * splices a {@code $} binding or a computed member (W1a BS2b, C6 / Q2: every
     * {@code list<column-reference>} parameter whose members are plain names; since W1b BS2c —
     * coordinator D4 — the {@code unknown}-typed single-reference and {@code within=} slots too: a
     * single-reference slot takes a string or a one-element list, a nested EC-24 {@code within=}
     * component splices inside as at top level; and since W1b SK — owner O16 — the sort-key slots
     * {@code by=} of {@code is_sorted_by} and {@code sequence}, whose {@code asc(X)} /
     * {@code desc(X)} descriptors wrap the member {@link SortKeys#column} judges). Not in the
     * roster, on purpose: {@code tuple}'s trailing collector, whose elements are per-row column
     * operands rather than a list.
     */
    private static final java.util.Map<String, List<String>> SPLICE_SLOTS = java.util.Map.ofEntries(
            java.util.Map.entry("is_unique_set", List.of(MEMBERS_PARAMETER)),
            java.util.Map.entry("is_not_unique_set", List.of(MEMBERS_PARAMETER)),
            // W1b SS1 (PLAN-engine-gap-set-of-sets): the member and group lists of the
            // group-against-group verdict
            java.util.Map.entry(NOT_UNIQUE_MEMBER_SET,
                    List.of(MEMBERS_PARAMETER, GroupedAggregate.GROUP_PARAMETER)),
            java.util.Map.entry(RecordCount.NAME, List.of(GroupedAggregate.GROUP_PARAMETER)),
            java.util.Map.entry(VALUES_CALL, List.of(VALUES_PARAMETER)),
            java.util.Map.entry(GroupedAggregate.MAX, List.of(GroupedAggregate.GROUP_PARAMETER)),
            java.util.Map.entry(GroupedAggregate.MAX_DATE,
                    List.of(GroupedAggregate.GROUP_PARAMETER)),
            java.util.Map.entry(GroupedAggregate.MIN_DATE,
                    List.of(GroupedAggregate.GROUP_PARAMETER)),
            java.util.Map.entry(ReadValue.NAME, List.of(GroupedAggregate.GROUP_PARAMETER)),
            java.util.Map.entry(Distinct.NAME, List.of(GroupedAggregate.GROUP_PARAMETER)),
            // BS2b (keys=): the compiler-dispatched readers' name lists
            // (ExprCompiler.strictColumnMembers / keyMembers)
            java.util.Map.entry("is_last_in_group",
                    List.of(GroupedAggregate.GROUP_PARAMETER, SortKeys.PARAMETER)),
            java.util.Map.entry("has_mixed_emptiness_within_group",
                    List.of(GroupedAggregate.GROUP_PARAMETER, "qualifying_any_populated")),
            java.util.Map.entry("is_unique_relationship", List.of(KEYS_PARAMETER)),
            java.util.Map.entry("is_not_unique_relationship", List.of(KEYS_PARAMETER)),
            java.util.Map.entry("is_inconsistent_across_dataset", List.of(KEYS_PARAMETER)),
            // W1b BS2c (D4): the within= / by= (ordering= until W4 OR2) / single group slots,
            // unknown-typed
            java.util.Map.entry("has_multiple_values_for", List.of(WITHIN_PARAMETER)),
            java.util.Map.entry("present_on_multiple_rows_within", List.of(WITHIN_PARAMETER)),
            java.util.Map.entry("has_next_corresponding_record",
                    List.of(WITHIN_PARAMETER, SortKeys.PARAMETER)),
            java.util.Map.entry("empty_within_except_last_row",
                    List.of(GroupedAggregate.GROUP_PARAMETER, SortKeys.PARAMETER)),
            // W1b SK (O16 / O17): the sort-key slots, and is_last_in_group's by=
            java.util.Map.entry("is_sorted_by", List.of(SortKeys.PARAMETER, WITHIN_PARAMETER)),
            java.util.Map.entry(Sequence.NAME,
                    List.of(GroupedAggregate.GROUP_PARAMETER, SortKeys.PARAMETER)),
            // W1b VA (PLAN-engine-gap-value-addressed V1 / V4): the same-named keys of the
            // value-addressed readers
            java.util.Map.entry(ReferencedRecordCount.NAME, List.of(KEYS_PARAMETER)),
            java.util.Map.entry(ReferencedKeyUniqueness.NAME, List.of(KEYS_PARAMETER)));

    /**
     * The readers that splice, as a message names them: every {@code list<column-reference>}
     * parameter except the two that take written names only.
     */
    public static final String SPLICING_READERS = "every list<column-reference> parameter"
            + " (the group= of record_count, max, max_date, min_date, read_value, distinct,"
            + " sequence, is_last_in_group and has_mixed_emptiness_within_group — and its"
            + " qualifying_any_populated=; the keys= of is_(not_)unique_relationship and"
            + " is_inconsistent_across_dataset, referenced_record_count and"
            + " referenced_key_is_unique; is_(not_)unique_set's members;"
            + " is_not_unique_member_set's members and group; values' columns),"
            + " every within= / by= slot and empty_within_except_last_row's group"
            + " — not tuple's columns";

    /**
     * The splice slots whose per-row member is a VALUE key component instead of a refused splice
     * (runbook W1b UC1, {@code PLAN-engine-gap-unique-computed} Q2 / Q3, coordinator D2): the
     * member list of {@code is_(not_)unique_set} only — every other slot keeps refusing a per-row
     * member at load. The level decides, never the value's content: a dataset-level {@code $} or
     * expression splices names (as everywhere), a row-cursor one is read per row as a key column
     * ({@link Value}).
     */
    private static final java.util.Map<String, List<String>> VALUE_MEMBER_SLOTS = java.util.Map.of(
            "is_unique_set", List.of(MEMBERS_PARAMETER), "is_not_unique_set",
            List.of(MEMBERS_PARAMETER));

    /**
     * Whether a per-row member at {@code (function, parameter)} is a value key component (W1b UC1)
     * rather than a refused splice.
     *
     * @param function
     *            the call name
     * @param parameter
     *            the parameter name
     * @return {@code true} for {@code is_(not_)unique_set}'s members
     */
    public static boolean admitsValueMember(String function, String parameter)
    {
        List<String> slots = VALUE_MEMBER_SLOTS.get(function);
        return slots != null && slots.contains(parameter);
    }


    /**
     * Whether the reader behind {@code (function, parameter)} splices a {@code $} binding or a
     * computed member holding a list of column names at evaluation ({@link #SPLICE_SLOTS}).
     *
     * @param function
     *            the call name
     * @param parameter
     *            the parameter name, as the registry declares it
     * @return {@code true} for a splice slot
     */
    public static boolean splices(String function, String parameter)
    {
        List<String> slots = SPLICE_SLOTS.get(function);
        return slots != null && slots.contains(parameter);
    }

    /**
     * One member of a splice list, as the reader keeps it from load to evaluation.
     */
    public sealed interface Member permits Name, Bound, Computed, Sided, Value
    {

        /** The member as authored, for messages. */
        String printed();
    }


    /**
     * The sided {@code group=} member {@code {left: X, right: Y}} of a {@code domain=} grouped call
     * (runbook W1b RO, R3; coordinator D9): this row's {@code left} column keys the other table's
     * {@code right} groups. Never spliced; carried through the group-name lists as its
     * {@link #encoded() encoded name}, which {@code GroupedAggregate}'s record-side helpers read
     * back ({@link #isEncoded}, {@link #leftOf}, {@link #rightOf}) exactly as they read a qualified
     * {@code DS.VAR} member.
     */
    public record Sided(String left, String right) implements Member
    {

        /** The separator of the encoded name — no column name can carry it. */
        private static final String SEPARATOR = "->";

        @Override
        public String printed()
        {
            return "{left: " + left + ", right: " + right + "}";
        }


        /** The member as one group name: {@code left->right}. */
        public String encoded()
        {
            return left + SEPARATOR + right;
        }


        /** Whether a group name is an encoded sided member. */
        public static boolean isEncoded(String name)
        {
            return name.contains(SEPARATOR);
        }


        /** The record-side column of an encoded sided member. */
        public static String leftOf(String encoded)
        {
            return encoded.substring(0, encoded.indexOf(SEPARATOR));
        }


        /** The grouped-side column of an encoded sided member. */
        public static String rightOf(String encoded)
        {
            return encoded.substring(encoded.indexOf(SEPARATOR) + SEPARATOR.length());
        }
    }


    /** A plain column name (bare, {@code --}-wildcard or qualified) — passes through. */
    public record Name(String name) implements Member
    {

        @Override
        public String printed()
        {
            return name;
        }
    }


    /** A {@code $} reference, resolved at evaluation to what its binding holds. */
    public record Bound(String ref) implements Member
    {

        @Override
        public String printed()
        {
            return ref;
        }
    }


    /**
     * A computed member — the expression a {@link Bound} binding would hold, written in the list —
     * evaluated at evaluation on the same context (W1a BS2, C4). Its compiled program is the
     * binding program cache's ({@link NativeExprEvaluator#bindingProgram}), compiled once per
     * {@link Expr}.
     */
    public record Computed(Expr expr, String printed) implements Member
    {

        /**
         * The computed member for {@code expr}, printed once for the messages.
         *
         * @param expr
         *            the member expression
         * @return the member
         */
        public static Computed of(Expr expr)
        {
            return new Computed(expr, ExpressionPrinter.print(expr));
        }
    }


    /**
     * A member of a {@linkplain #admitsValueMember value-admitting} slot —
     * {@code is_(not_)unique_set}'s members (runbook W1b UC1, coordinator D1–D3) — that is not a
     * plain name: what {@link #spliced} answers ({@link Bound} / {@link Computed}), read at
     * evaluation by its LEVEL over the rule's installed binding domains ({@link #readsRowCursor}):
     * a dataset-level one splices as names exactly as the other slots' members do, a row-cursor one
     * is a per-row VALUE key component ({@link #valueVector}) — bound and inline alike (G1). A
     * member written with the {@code duration(…)} marker keys by its hull ({@link #hullKeyed},
     * dur-order Q5 (a)).
     *
     * @param expr
     *            the member expression — the {@code $} reference, or the computed expression
     * @param printed
     *            the member as authored, for messages
     * @param durationMarked
     *            whether the member is the written {@code duration(…)} marker
     */
    public record Value(Expr expr, String printed, boolean durationMarked) implements Member
    {

        /**
         * The value member for {@code item}, or {@code null} for a name or a literal (as
         * {@link #spliced}).
         *
         * @param item
         *            the authored member
         * @return the member, or {@code null}
         */
        public static @Nullable Value of(Expr item)
        {
            Member spliced = spliced(item);
            return spliced == null ? null
                    : new Value(item, spliced.printed(), isDurationMarker(item));
        }
    }

    /**
     * The spliced form of a list member that is not a plain name: a {@code $} reference is
     * {@link Bound}, any other non-literal, non-reference expression (a call, an operator) is
     * {@link Computed}; a literal or a column / wildcard / qualified reference is {@code null} —
     * the reader's own strict name reading judges those (a quoted name stays a string, never a
     * column — runbook W8 D-W8-6).
     *
     * @param item
     *            the authored member
     * @return the spliced member, or {@code null} for a name or a literal
     */
    public static @Nullable Member spliced(Expr item)
    {
        if (item instanceof Expr.Ref ref)
        {
            return ref.kind() == OperandKind.OPERATION_REF ? new Bound(ref.name()) : null;
        }
        if (item instanceof Expr.Lit)
        {
            return null;
        }
        return Computed.of(item);
    }


    /**
     * Whether any member is spliced at evaluation (a {@link Bound} or {@link Computed} one).
     *
     * @param members
     *            the members
     * @return {@code true} when evaluation has to splice
     */
    public static boolean splices(List<Member> members)
    {
        for (Member member : members)
        {
            if (member instanceof Bound || member instanceof Computed || member instanceof Value)
            {
                return true;
            }
        }
        return false;
    }


    /**
     * Whether {@code member}'s value varies per row on {@code ctx} — the level judgement of W1b UC1
     * (coordinator D2), derived over the rule's <b>installed</b> binding domains exactly as stage A
     * and the loader derive it ({@code DomainScan.infer}; a {@code $} member contributes its
     * installed domain, {@code BindingValue.handOver}'s own line), never from the value's content.
     *
     * @param member
     *            the member
     * @param ctx
     *            the rule's context
     * @return {@code true} when the member is a per-row value key component
     */
    public static boolean readsRowCursor(Value member, EvaluationContext ctx)
    {
        return DomainScan.infer(member.expr(), installedDomains(ctx)).rowCursor();
    }


    /** The installed domains of the context's compiled bindings; dataset for any other value. */
    private static BindingDomains installedDomains(EvaluationContext ctx)
    {
        return ref ->
        {
            Object raw = ctx.getVariables().get(ref);
            Domain domain = raw instanceof BindingValue bound ? bound.binding().domain() : null;
            return domain == null ? Domain.DATASET : domain;
        };
    }


    /**
     * The per-row vector of a row-cursor {@link Value} member on {@code ctx}: the binding program's
     * vector over the whole table — a {@code $} member reads its binding's vector through the
     * context, a computed one is evaluated on it, so the two spellings answer alike (G1).
     *
     * @param member
     *            the member ({@link #readsRowCursor} answered {@code true})
     * @param ctx
     *            the rule's context
     * @return the vector, one value per row of the evaluated table
     */
    public static Vector valueVector(Value member, EvaluationContext ctx)
    {
        return NativeExprEvaluator.bindingProgram(member.expr()).evaluate(EvalRun.fullRange(ctx));
    }


    /**
     * Whether {@code member} keys by the duration hull (dur-order Q5 (a), coordinator D1): the
     * member is the written {@code duration(…)} marker, or a {@code $} reference whose binding
     * holds it — bound and inline alike.
     *
     * @param member
     *            the member
     * @param ctx
     *            the rule's context (its compiled bindings)
     * @return {@code true} for a hull-keyed member
     */
    public static boolean hullKeyed(Value member, EvaluationContext ctx)
    {
        if (member.durationMarked())
        {
            return true;
        }
        if (member.expr() instanceof Expr.Ref ref && ref.kind() == OperandKind.OPERATION_REF)
        {
            Object raw = ctx.getVariables().get(ref.name());
            return raw instanceof BindingValue bound
                    && isDurationMarker(bound.binding().expression());
        }
        return false;
    }


    /**
     * Whether {@code e} is the written one-argument {@code duration(…)} marker — the mirror of
     * {@code ExprCompiler.isDurationMarker} / {@code RulePackageLoader.isDurationMarker}.
     */
    private static boolean isDurationMarker(Expr e)
    {
        return e instanceof Expr.Call c && "duration".equals(c.name()) && c.args().size() == 1
                && c.kwargs().isEmpty();
    }


    /**
     * The plain names among {@code members}, in order — what the load-time checks on authored names
     * (a qualified member, {@code regex=}) read; a spliced member is judged at evaluation.
     *
     * @param members
     *            the members
     * @return the {@link Name} members' names
     */
    public static List<String> names(List<Member> members)
    {
        List<String> out = new ArrayList<>(members.size());
        for (Member member : members)
        {
            if (member instanceof Name name)
            {
                out.add(name.name());
            }
            else if (member instanceof Sided sided)
            {
                out.add(sided.encoded());
            }
        }
        return List.copyOf(out);
    }

    /**
     * The splice slot of a call: the parameter, the authored operands that are spliced — the
     * elements of the list literal, or the one bare operand ({@code f($keys)} reads as
     * {@code f([$keys])}, C5) — and the authored argument itself.
     *
     * @param parameter
     *            the parameter name ({@code members} / {@code group} / {@code columns})
     * @param operands
     *            the authored operands
     * @param argument
     *            the authored argument at the slot (the list literal, or the bare operand)
     */
    public record Slot(String parameter, List<Expr> operands, Expr argument)
    {

        /** The operands, copied — the authored list literal's own list is never handed out. */
        public Slot
        {
            operands = List.copyOf(operands);
        }
    }

    /**
     * The splice slots {@code call} carries, each with its authored operands — the member list of
     * {@code is_(not_)unique_set}, the {@code group=} of the grouped readers, the {@code columns}
     * of {@code values} ({@link #SPLICE_SLOTS}); empty for a call that splices nothing or whose
     * slots are absent. The loader's level seam asks it ({@code ListOperandLevel.firstRefusal}). A
     * slot is read by its parameter's INDEX in the registry descriptor, as the readers bind it
     * ({@code ArgumentBinder}, D9) — positionally or by keyword alike (R5 review BS2b MED-1:
     * {@code is_last_in_group(AESEQ, $keys)} binds {@code group} by position); a call the binder
     * refuses is read by keyword only (its binding error is filed by the reader).
     *
     * @param call
     *            the call
     * @return the slots present on the call, in roster order
     */
    public static List<Slot> spliceSlots(Expr.Call call)
    {
        List<String> parameters = SPLICE_SLOTS.get(call.name());
        if (parameters == null)
        {
            return List.of();
        }
        List<@Nullable Expr> bound = bound(call);
        List<net.cumba.corej.core.expr.eval.Parameter> declared = bound == null ? List.of()
                : java.util.Objects.requireNonNull(
                        net.cumba.corej.core.expr.eval.FunctionRegistry.descriptor(call.name()))
                        .parameters();
        List<Slot> out = new ArrayList<>(parameters.size());
        for (String parameter : parameters)
        {
            Expr operand = call.kwargs().get(parameter);
            for (int i = 0; bound != null && i < declared.size(); i++)
            {
                if (declared.get(i).name().equals(parameter))
                {
                    operand = bound.get(i);
                    break;
                }
            }
            if (operand == null)
            {
                continue;
            }
            List<Expr> items = new ArrayList<>();
            if (operand instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.LIST)
            {
                @SuppressWarnings("unchecked")
                List<Expr> authored = (List<Expr>) lit.value();
                for (Expr item : authored)
                {
                    if (WITHIN_PARAMETER.equals(parameter) && item instanceof Expr.Lit inner
                            && inner.kind() == Expr.LitKind.LIST)
                    {
                        // BS2c (D4): an EC-24 coalesce component splices inside as at top level
                        @SuppressWarnings("unchecked")
                        List<Expr> component = (List<Expr>) inner.value();
                        items.addAll(component);
                    }
                    else
                    {
                        items.add(item);
                    }
                }
            }
            else
            {
                items.add(operand);
            }
            if (SORT_KEY_PARAMETERS.contains(parameter))
            {
                // SK (O16 #2): the seams judge the member a descriptor wraps
                items.replaceAll(SortKeys::column);
            }
            out.add(new Slot(parameter, items, operand));
        }
        return out;
    }


    /** The call bound to its descriptor, or {@code null} when it has none or the binder refuses. */
    private static @Nullable List<@Nullable Expr> bound(Expr.Call call)
    {
        net.cumba.corej.core.expr.eval.FunctionDescriptor descriptor = net.cumba.corej.core.expr.eval.FunctionRegistry
                .descriptor(call.name());
        if (descriptor == null)
        {
            return null;
        }
        try
        {
            return net.cumba.corej.core.expr.eval.ArgumentBinder.bind(descriptor, call);
        }
        catch (net.cumba.corej.core.expr.ExpressionException refused)
        {
            return null;
        }
    }


    /**
     * The splice arguments {@code call} carries: the list literals at its {@link #SPLICE_SLOTS
     * splice slots} — the lists whose {@code $} members {@link #splice} reads as column NAMES —
     * and, since the W1a fold (R5 review BS2b LOW-1), a bare {@code $} operand there, the
     * one-member list it reads as (C5). The bind-time specialiser asks it
     * ({@code RuleSpecialiser.inlineSplicedNames}, {@code PLAN-string-literal-prefix-rewrite}
     * review MED-1): a {@code $} member whose binding holds a written name is that name written in
     * the list (G1), inlined at the splice site only — never under {@code domain=}. Compared by
     * IDENTITY: an equal operand elsewhere in the call is not the slot's.
     *
     * @param call
     *            the call
     * @return the authored splice arguments (empty when none)
     */
    public static List<Expr> spliceLists(Expr.Call call)
    {
        List<Expr> out = List.of();
        for (Slot slot : spliceSlots(call))
        {
            Expr argument = slot.argument();
            if ((argument instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.LIST)
                    || (argument instanceof Expr.Ref ref
                            && ref.kind() == OperandKind.OPERATION_REF))
            {
                out = out.isEmpty() ? List.of(argument) : concat(out, argument);
            }
        }
        return out;
    }


    private static List<Expr> concat(List<Expr> lists, Expr more)
    {
        List<Expr> out = new ArrayList<>(lists.size() + 1);
        out.addAll(lists);
        out.add(more);
        return List.copyOf(out);
    }


    /**
     * Splices every {@link Bound} and {@link Computed} member of {@code members}; a {@link Name}
     * passes through.
     *
     * @param fn
     *            the function name, for the message
     * @param where
     *            the slot, for the message ({@code "group= member"}, {@code "member"})
     * @param members
     *            the members as authored
     * @param ctx
     *            the evaluation context the bindings resolve in — and the computed members are
     *            evaluated on
     * @param foreign
     *            whether the call names {@code domain=} — a spliced {@code --} name would then
     *            resolve against the evaluated dataset's prefix and be looked up in the OTHER
     *            table, silently partitioning nothing (combined review of runbook W2–W8, W5/W6 L6)
     * @param qualifiedAdvice
     *            what the author should write instead of a spliced qualified name
     * @return the spliced names
     * @throws GroupSpliceException
     *             for a spliced member that does not hold a list of names or a name, that splices a
     *             qualified name, or a {@code --} name under {@code domain=}
     */
    public static List<String> splice(String fn, String where, List<Member> members,
            EvaluationContext ctx, boolean foreign, String qualifiedAdvice)
    {
        List<String> out = new ArrayList<>(members.size());
        for (Member member : members)
        {
            switch (member)
            {
            case Name name -> out.add(name.name());
            case Sided sided -> out.add(sided.encoded());
            case Bound bound -> spliceValue(fn, where, bound.printed(),
                    ctx.resolveVariable(bound.ref()), ctx, foreign, qualifiedAdvice, out);
            case Computed computed -> spliceValue(fn, where, computed.printed(),
                    evaluate(computed, ctx), ctx, foreign, qualifiedAdvice, out);
            // a dataset-level value member splices as the $ / computed member it wraps (UC1);
            // a row-cursor one is read by valueVector before the splice — here its vector is
            // the refusal spliceValue files, never a silent first-value read
            case Value value -> spliceValue(fn, where, value.printed(),
                    value.expr() instanceof Expr.Ref ref ? ctx.resolveVariable(ref.name())
                            : datasetLevelValue(value.expr(), ctx),
                    ctx, foreign, qualifiedAdvice, out);
            }
        }
        return out;
    }


    /**
     * The value a computed member holds on {@code ctx}: the binding program's vector over the whole
     * table, read as a dataset-level binding is handed over ({@link BindingValue#firstValue}).
     */
    private static @Nullable Object evaluate(Computed computed, EvaluationContext ctx)
    {
        return datasetLevelValue(computed.expr(), ctx);
    }


    /**
     * The dataset-level value of {@code expr} on {@code ctx} — the binding program's vector over
     * the whole table, read as a dataset-level binding is handed over
     * ({@link BindingValue#firstValue}): what a {@code $}-binding holding {@code expr} would
     * answer, so an operand written in place answers as the binding does (G1). The set operands of
     * {@code shares_(no_)elements_with} read a non-{@code $} operand this way (runbook W1b SH); a
     * per-row expression is refused at load ({@code ListOperandLevel}), never read here.
     *
     * @param expr
     *            the expression
     * @param ctx
     *            the rule's context
     * @return the value, {@code null} for a missing one
     */
    public static @Nullable Object datasetLevelValue(Expr expr, EvaluationContext ctx)
    {
        Vector v = NativeExprEvaluator.bindingProgram(expr).evaluate(EvalRun.fullRange(ctx));
        return BindingValue.firstValue(v, ctx.rowCount());
    }


    private static void spliceValue(String fn, String where, String member, @Nullable Object value,
            EvaluationContext ctx, boolean foreign, String qualifiedAdvice, List<String> out)
    {
        switch (value)
        {
        case Collection<?> items ->
        {
            for (Object item : ListValueGuard.elements(items))
            {
                out.add(splicedName(fn, where, member, item.toString(), ctx, foreign,
                        qualifiedAdvice));
            }
        }
        case String name -> out
                .add(splicedName(fn, where, member, name, ctx, foreign, qualifiedAdvice));
        case null, default -> throw new GroupSpliceException("[" + ctx.getRuleId() + "] " + fn
                + ": the " + where + " " + member + " must hold a list of column names, but "
                + (value == null ? "it resolves to nothing"
                        : "it holds " + value.getClass().getSimpleName())
                + " — only a dataset-level list of column names (a binding or the expression it"
                + " holds) can be spliced into " + fn);
        }
    }


    private static String splicedName(String fn, String where, String member, String name,
            EvaluationContext ctx, boolean foreign, String qualifiedAdvice)
    {
        if (foreign && MatchDataset.qualifierOf(name) != null)
        {
            throw new GroupSpliceException(
                    "[" + ctx.getRuleId() + "] " + fn + ": the " + where + " " + member
                            + " splices the qualified name " + name + " — " + qualifiedAdvice);
        }
        if (foreign && name.startsWith("--") && name.indexOf('.') < 0)
        {
            throw new GroupSpliceException("[" + ctx.getRuleId() + "] " + fn + ": the " + where
                    + " " + member + " splices " + name
                    + " under domain= — a `--` name resolves against the evaluated dataset, not"
                    + " the domain; the list must name the domain's columns explicitly");
        }
        return name;
    }

    /**
     * A splice the engine refuses — a rule {@code ERROR} on {@code RuleRunner}'s sentinel channel
     * (D35: the rule is written in a way that cannot be executed). An {@link IllegalStateException}
     * so the callers that already expected one keep their contract.
     */
    public static final class GroupSpliceException extends IllegalStateException
    {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * @param message
         *            the rule's ERROR message
         */
        public GroupSpliceException(String message)
        {
            super(message);
        }
    }
}
