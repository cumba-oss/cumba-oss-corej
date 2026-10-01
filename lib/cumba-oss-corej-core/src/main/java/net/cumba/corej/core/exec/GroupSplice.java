package net.cumba.corej.core.exec;

import java.io.Serial;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.cumba.corej.core.model.MatchDataset;

/**
 * The {@code $}-list splice of a column-name list — ONE implementation for {@code record_count}'s
 * {@code group=} (D-W6-7, the retired executor's {@code expandGroupRefs}) and the member list of
 * {@code is_unique_set} / {@code is_not_unique_set} ({@code PLAN-qualified-name-uniformity-review}
 * N15 / N29).
 *
 * <p>
 * A {@code $} member resolves at evaluation to what a dataset-level binding holds: a list is
 * spliced element by element as column names ({@link ListValueGuard#elements}: the list was born at
 * a guard site, register NNL §1), a {@code String} is one name. Anything else — a scalar, a per-row
 * binding, an unresolvable name — is a rule {@code ERROR}, never a silent answer. So is a spliced
 * <b>qualified</b> name: a qualified member is judged at load (D-DOMAIN / D-SRC / D-REGEX for
 * {@code group=}; "written bare" for a uniqueness member, N5), and a spliced one never reaches
 * those gates. Serving it would need those gates at run time; the join type D-SRC requires is not
 * on the evaluation context, so it is refused instead.
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


    /**
     * Splices every {@code $} member of {@code members}; a plain member passes through.
     *
     * @param fn
     *            the function name, for the message
     * @param where
     *            the slot, for the message ({@code "group= member"}, {@code "member"})
     * @param members
     *            the members as authored
     * @param ctx
     *            the evaluation context the bindings resolve in
     * @param foreign
     *            whether the call names {@code domain=} — a spliced {@code --} name would then
     *            resolve against the evaluated dataset's prefix and be looked up in the OTHER
     *            table, silently partitioning nothing (combined review of runbook W2–W8, W5/W6 L6)
     * @param qualifiedAdvice
     *            what the author should write instead of a spliced qualified name
     * @return the spliced names
     * @throws GroupSpliceException
     *             for a {@code $} member that does not hold a list of names or a name, that splices
     *             a qualified name, or a {@code --} name under {@code domain=}
     */
    public static List<String> splice(String fn, String where, List<String> members,
            EvaluationContext ctx, boolean foreign, String qualifiedAdvice)
    {
        List<String> out = new ArrayList<>(members.size());
        for (String member : members)
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
                    + " — only a dataset-level list binding can be spliced into " + fn);
            }
        }
        return out;
    }


    private static String splicedName(String fn, String where, String member, String name,
            EvaluationContext ctx, boolean foreign, String qualifiedAdvice)
    {
        if (MatchDataset.qualifierOf(name) != null)
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
     * A {@code $}-list splice the engine refuses — a rule {@code ERROR} on {@code RuleRunner}'s
     * sentinel channel (D35: the rule is written in a way that cannot be executed). An
     * {@link IllegalStateException} so the callers that already expected one keep their contract.
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
