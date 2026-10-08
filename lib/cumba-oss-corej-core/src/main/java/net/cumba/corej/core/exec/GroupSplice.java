package net.cumba.corej.core.exec;

import java.io.Serial;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.cumba.corej.core.expr.ExpressionPrinter;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
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

    /**
     * The splice slots — function name to the names of its parameters whose list of column names
     * splices a {@code $} binding or a computed member (W1a BS2b, C6 / Q2: every
     * {@code list<column-reference>} parameter whose members are plain names). Not in the roster,
     * on purpose: {@code is_sorted_by}'s {@code by=}, whose members are {@code asc(X)} /
     * {@code desc(X)} sort-key descriptors rather than names, and {@code tuple}'s trailing
     * collector, whose elements are per-row column operands rather than a list.
     */
    private static final java.util.Map<String, List<String>> SPLICE_SLOTS = java.util.Map.ofEntries(
            java.util.Map.entry("is_unique_set", List.of(MEMBERS_PARAMETER)),
            java.util.Map.entry("is_not_unique_set", List.of(MEMBERS_PARAMETER)),
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
            java.util.Map.entry("is_last_in_group", List.of(GroupedAggregate.GROUP_PARAMETER)),
            java.util.Map.entry("has_mixed_emptiness_within_group",
                    List.of(GroupedAggregate.GROUP_PARAMETER, "qualifying_any_populated")),
            java.util.Map.entry("is_unique_relationship", List.of(KEYS_PARAMETER)),
            java.util.Map.entry("is_not_unique_relationship", List.of(KEYS_PARAMETER)),
            java.util.Map.entry("is_inconsistent_across_dataset", List.of(KEYS_PARAMETER)));

    /**
     * The slots whose list is the call's FIRST positional operand ({@code is_unique_set([…])},
     * {@code values([…])}); every other slot is a keyword ({@code group=}).
     */
    private static final java.util.Set<String> POSITIONAL_SLOT_CALLS = java.util.Set
            .of("is_unique_set", "is_not_unique_set", VALUES_CALL);

    /**
     * The readers that splice, as a message names them: every {@code list<column-reference>}
     * parameter except the two that take written names only.
     */
    public static final String SPLICING_READERS = "every list<column-reference> parameter"
            + " (the group= of record_count, max, max_date, min_date, read_value, distinct,"
            + " is_last_in_group and has_mixed_emptiness_within_group — and its"
            + " qualifying_any_populated=; the keys= of is_(not_)unique_relationship and"
            + " is_inconsistent_across_dataset; is_(not_)unique_set's members; values' columns)"
            + " — not is_sorted_by's by= (sort-key descriptors) and not tuple's columns";

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
    public sealed interface Member permits Name, Bound, Computed
    {

        /** The member as authored, for messages. */
        String printed();
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
            if (!(member instanceof Name))
            {
                return true;
            }
        }
        return false;
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
        }
        return List.copyOf(out);
    }

    /**
     * The splice slot of a call: the parameter and the authored operands that are spliced — the
     * elements of the list literal, or the one bare operand ({@code f($keys)} reads as
     * {@code f([$keys])}, C5).
     *
     * @param parameter
     *            the parameter name ({@code members} / {@code group} / {@code columns})
     * @param operands
     *            the authored operands
     */
    public record Slot(String parameter, List<Expr> operands)
    {
    }

    /**
     * The splice slots {@code call} carries, each with its authored operands — the member list of
     * {@code is_(not_)unique_set}, the {@code group=} of the grouped readers, the {@code columns}
     * of {@code values} ({@link #SPLICE_SLOTS}); empty for a call that splices nothing or whose
     * slots are absent. The loader's level seam asks it ({@code ListOperandLevel.firstRefusal}).
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
        List<Slot> out = new ArrayList<>(parameters.size());
        for (String parameter : parameters)
        {
            Expr operand = POSITIONAL_SLOT_CALLS.contains(call.name())
                    ? (call.args().isEmpty() ? null : call.args().get(0))
                    : call.kwargs().get(parameter);
            if (operand == null)
            {
                continue;
            }
            if (operand instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.LIST)
            {
                @SuppressWarnings("unchecked")
                List<Expr> items = (List<Expr>) lit.value();
                out.add(new Slot(parameter, items));
            }
            else
            {
                out.add(new Slot(parameter, List.of(operand)));
            }
        }
        return out;
    }


    /**
     * The splice lists {@code call} carries: the list literals at its {@link #SPLICE_SLOTS splice
     * slots} — the lists whose {@code $} members {@link #splice} reads as column NAMES. The
     * bind-time specialiser asks it ({@code RuleSpecialiser.inlineSplicedNames},
     * {@code PLAN-string-literal-prefix-rewrite} review MED-1): a {@code $} member whose binding
     * holds a written name is that name written in the list (G1), inlined at the splice site only —
     * never under {@code domain=}.
     *
     * @param call
     *            the call
     * @return the list literals that are spliced (empty when none)
     */
    public static List<Expr> spliceLists(Expr.Call call)
    {
        List<Expr> out = List.of();
        for (Slot slot : spliceSlots(call))
        {
            Expr list = POSITIONAL_SLOT_CALLS.contains(call.name()) ? call.args().get(0)
                    : call.kwargs().get(slot.parameter());
            if (list instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.LIST)
            {
                out = out.isEmpty() ? List.of(lit) : concat(out, lit);
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
            case Bound bound -> spliceValue(fn, where, bound.printed(),
                    ctx.resolveVariable(bound.ref()), ctx, foreign, qualifiedAdvice, out);
            case Computed computed -> spliceValue(fn, where, computed.printed(),
                    evaluate(computed, ctx), ctx, foreign, qualifiedAdvice, out);
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
        Vector v = NativeExprEvaluator.bindingProgram(computed.expr())
                .evaluate(EvalRun.fullRange(ctx));
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
    static final class GroupSpliceException extends IllegalStateException
    {

        @Serial
        private static final long serialVersionUID = 1L;

        GroupSpliceException(String message)
        {
            super(message);
        }
    }
}
