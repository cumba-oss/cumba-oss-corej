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
 * (D-W6-7, the retired executor's {@code expandGroupRefs}) and the member list of
 * {@code is_unique_set} / {@code is_not_unique_set} ({@code PLAN-qualified-name-uniformity-review}
 * N15 / N29).
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

    /** The uniqueness-set operators whose argument 0 is a splice list (N15). */
    private static final java.util.Set<String> UNIQUE_SET_CALLS = java.util.Set.of("is_unique_set",
            "is_not_unique_set");

    /** The parameter name of the uniqueness operators' splice list, as the registry declares it. */
    public static final String MEMBERS_PARAMETER = "members";

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
     *            the parameter name ({@code members} / {@code group})
     * @param operands
     *            the authored operands
     */
    public record Slot(String parameter, List<Expr> operands)
    {
    }

    /**
     * The splice slot {@code call} carries, or {@code null} for a call that splices nothing: the
     * member list of {@code is_(not_)unique_set} and the {@code group=} of {@code record_count}.
     * The loader's level seam asks it ({@code ListOperandLevel.firstRefusal}).
     *
     * @param call
     *            the call
     * @return the slot, or {@code null}
     */
    public static @Nullable Slot spliceSlot(Expr.Call call)
    {
        Expr operand;
        String parameter;
        if (UNIQUE_SET_CALLS.contains(call.name()) && !call.args().isEmpty())
        {
            operand = call.args().get(0);
            parameter = MEMBERS_PARAMETER;
        }
        else if (RecordCount.NAME.equals(call.name()))
        {
            operand = call.kwargs().get(GroupedAggregate.GROUP_PARAMETER);
            parameter = GroupedAggregate.GROUP_PARAMETER;
        }
        else
        {
            return null;
        }
        if (operand == null)
        {
            return null;
        }
        if (operand instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.LIST)
        {
            @SuppressWarnings("unchecked")
            List<Expr> items = (List<Expr>) lit.value();
            return new Slot(parameter, items);
        }
        return new Slot(parameter, List.of(operand));
    }


    /**
     * The splice lists {@code call} carries: the member list of {@code is_(not_)unique_set} and the
     * {@code group=} list of {@code record_count} — the two lists whose {@code $} members
     * {@link #splice} reads as column NAMES. The bind-time specialiser asks it
     * ({@code RuleSpecialiser.inlineSplicedNames}, {@code PLAN-string-literal-prefix-rewrite}
     * review MED-1): a {@code $} member whose binding holds a written name is that name written in
     * the list (G1), inlined at the splice site only — never under {@code domain=}.
     *
     * @param call
     *            the call
     * @return the list literals that are spliced (empty when none)
     */
    public static List<Expr> spliceLists(Expr.Call call)
    {
        Expr list = null;
        if (UNIQUE_SET_CALLS.contains(call.name()) && !call.args().isEmpty())
        {
            list = call.args().get(0);
        }
        else if (RecordCount.NAME.equals(call.name()))
        {
            list = call.kwargs().get(GroupedAggregate.GROUP_PARAMETER);
        }
        return list instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.LIST ? List.of(lit)
                : List.of();
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
