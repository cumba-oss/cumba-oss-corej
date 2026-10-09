package net.cumba.corej.core.expr.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.exec.GroupSplice;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import org.jspecify.annotations.Nullable;

/**
 * ⭐ The <b>name-list binding</b> — {@code $keys: [STUDYID, USUBJID]}, a list literal of bare column
 * references bound to a name ({@code PLAN-elementwise-list-functions} owner ruling D1-A, runbook
 * W1a E2). It is a list of column <b>names</b>, never of cells: the binding's value is the list of
 * the names as text (what a {@code ["STUDYID", "USUBJID"]} binding holds), its domain is
 * dataset-level ({@code DomainScan.inferBinding}), and its static type is
 * {@code list<column-reference>}.
 *
 * <p>
 * It is legal <b>only</b> where D1 names a read of column names (coordinator decision 9,
 * 2026-10-08): as the names in a splice slot — every {@code list<column-reference>} slot of the
 * roster ({@link GroupSplice#spliceSlots}: {@link GroupSplice#SPLICING_READERS}), by keyword or by
 * position — and so as the per-row values inside {@code values($keys)}. Under {@code domain=} a
 * name list holding a {@code --} member is refused ({@link #domainPrefixRefusal}). Anywhere else —
 * a value position ({@code X in $keys}, a comparison, a function's value argument, a binding of the
 * binding), {@code Output_Variables} — it is a load error carrying {@link #hint}: refusing keeps
 * the name/value distinction the rulings protect, the form is new (0 corpus carriers), and widening
 * later is cheap where a guessed meaning is not. Stage A files the finding where it walks the rule
 * ({@code StageAChecker}); {@link #firstValueRead} is the loader's twin for the paths stage A never
 * walks (a Check-less rule's bindings).
 * </p>
 *
 * <p>
 * The one shape predicate is {@link #isNameList}: every reader of the form — the binding compiler
 * ({@code ExprCompiler.compileBinding}), the domain scan, stage A, the bind-time specialiser's name
 * inlining ({@code RuleSpecialiser}) and the loader — decides by it, so the form cannot drift
 * between them. A list mixing a bare name with a literal is neither a name list nor a plain list
 * and compiles to neither (the binding compiler's refusal).
 * </p>
 */
public final class NameListBinding
{

    private NameListBinding()
    {
    }


    /**
     * Whether {@code e} is a name list: a non-empty list literal whose every member is a column
     * reference — bare ({@code USUBJID}), prefixed ({@code --SEQ}, resolved at bind time as every
     * {@code --} name is) or qualified ({@code DM.USUBJID}). A {@code $} member, a literal, a call
     * or a nested list makes it not one.
     *
     * @param e
     *            the expression
     * @return {@code true} for a name list
     */
    public static boolean isNameList(@Nullable Expr e)
    {
        if (!(e instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.LIST
                || !(lit.value() instanceof List<?> items) || items.isEmpty())
        {
            return false;
        }
        for (Object item : items)
        {
            if (!isColumnName(item))
            {
                return false;
            }
        }
        return true;
    }


    /**
     * Whether {@code item} is a column reference a name list may hold.
     *
     * @param item
     *            a list member
     * @return {@code true} for a bare, prefixed or qualified column reference
     */
    public static boolean isColumnName(@Nullable Object item)
    {
        return item instanceof Expr.Ref ref
                && (ref.kind() == OperandKind.COLUMN || ref.kind() == OperandKind.WILDCARD_COLUMN
                        || ref.kind() == OperandKind.DOTTED_REF);
    }


    /**
     * The names a name list holds, in authored order — the binding's value.
     *
     * @param lit
     *            a name list ({@link #isNameList})
     * @return the member names as text
     */
    public static List<String> names(Expr.Lit lit)
    {
        @SuppressWarnings("unchecked")
        List<Expr> items = (List<Expr>) lit.value();
        List<String> out = new ArrayList<>(items.size());
        for (Expr item : items)
        {
            out.add(((Expr.Ref) item).name());
        }
        return List.copyOf(out);
    }


    /**
     * Whether {@code e} is a <b>string list</b>: a non-empty list literal whose every member is a
     * string literal ({@code ["STUDYID", "USUBJID"]}) — the form D1-B retires as a list of column
     * names (runbook W4 E3); as values it stays legal (D1 (a)).
     *
     * @param e
     *            the expression
     * @return {@code true} for a string list
     */
    public static boolean isStringList(@Nullable Expr e)
    {
        if (!(e instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.LIST
                || !(lit.value() instanceof List<?> items) || items.isEmpty())
        {
            return false;
        }
        for (Object item : items)
        {
            if (!(item instanceof Expr.Lit member) || member.kind() != Expr.LitKind.STRING)
            {
                return false;
            }
        }
        return true;
    }


    /**
     * ⭐ Runbook W4 E3 ({@code PLAN-elementwise-list-functions} owner ruling D1-B, coordinator
     * W4-D6): the first splice-slot read ({@link GroupSplice#spliceSlots} — the bare operand, a
     * list member, a {@code within=} component, the member a sort-key descriptor wraps) of a
     * binding whose expression, followed through bindings of bindings, is a written
     * {@link #isStringList string list}. Its strings were turned into column names at bind time, so
     * {@code group=$k} answered as the names — while the same strings written in place
     * ({@code group=["STUDYID"]}) are refused (runbook W8 D-W8-6). Judged by PROVENANCE, never by
     * type: a computed list of names ({@code minus([…], subtract=[…])}, {@code get_*_variables})
     * still splices (D1 (b)); a single string binding ({@code $k: "--SEQ"}, PACK 5 S8) is not a
     * list (coordinator E3-D1); {@code colref} and {@code key_variable} are no splice slots. One
     * call, not the tree: the loader's seam pass walks every call of every Check level and binding
     * of every rule, before stage A.
     *
     * @param call
     *            the call
     * @param bound
     *            the rule's bindings visible here, name → expression
     * @return the refusal, or {@code null}
     */
    public static @Nullable String stringListSpliceRefusal(Expr.Call call, Map<String, Expr> bound)
    {
        if (bound.isEmpty())
        {
            return null;
        }
        for (GroupSplice.Slot slot : GroupSplice.spliceSlots(call))
        {
            for (Expr member : slot.operands())
            {
                if (!(member instanceof Expr.Ref ref) || ref.kind() != OperandKind.OPERATION_REF)
                {
                    continue;
                }
                Expr held = net.cumba.corej.core.expr.convert.BindingInliner.boundExpression(member,
                        bound);
                if (held != member && isStringList(held))
                {
                    return stringListRefusal(call.name(), slot.parameter(), ref.name(),
                            (Expr.Lit) held);
                }
            }
        }
        return null;
    }


    /**
     * The E3 sentence, D1's hint verbatim with the authored names: <i>"write the columns as names:
     * [STUDYID, USUBJID]"</i>.
     *
     * @param function
     *            the splicing function
     * @param parameter
     *            its splice slot
     * @param binding
     *            the binding read there, {@code $} included
     * @param strings
     *            the string list it holds
     * @return the sentence
     */
    public static String stringListRefusal(String function, String parameter, String binding,
            Expr.Lit strings)
    {
        @SuppressWarnings("unchecked")
        List<Expr> items = (List<Expr>) strings.value();
        List<String> names = new ArrayList<>(items.size());
        for (Expr item : items)
        {
            names.add(String.valueOf(((Expr.Lit) item).value()));
        }
        return "argument '" + parameter + "' of '" + function + "' reads the binding " + binding
                + " as column names, but it holds the strings "
                + net.cumba.corej.core.expr.ExpressionPrinter.print(strings)
                + " - a quoted name is a string, never a column: write the columns as names: ["
                + String.join(", ", names) + "]";
    }


    /**
     * The refusal of a name-list binding read anywhere but a splice slot (decision 9's hint).
     *
     * @param binding
     *            the binding's name, {@code $} included
     * @return the sentence
     */
    public static String hint(String binding)
    {
        return "the binding " + binding + " holds column names: use it in a name slot (group="
                + binding + ") or read its values with values(" + binding
                + "); to compare names as text, write a string list";
    }


    /**
     * Whether {@code call} reads its splice slots against ANOTHER dataset: it names {@code domain=}
     * ({@code read_value} always does — it requires it). Only the {@code GroupedAggregate} readers
     * take {@code domain=}; the compiler-dispatched splicing readers never name it.
     *
     * @param call
     *            the call
     * @return {@code true} for a foreign read
     */
    public static boolean isForeign(Expr.Call call)
    {
        return call.kwargs()
                .containsKey(net.cumba.corej.core.exec.GroupedAggregate.DOMAIN_PARAMETER)
                || net.cumba.corej.core.exec.ReadValue.NAME.equals(call.name());
    }


    /**
     * Runbook W1a fold (R5 review A-M2 / BS2b MED-3, coordinator decision: load-time refusal): a
     * name-list binding spliced at a slot of a {@link #isForeign foreign} call, holding a
     * {@code --} member. Under {@code domain=} a {@code --} name resolves against the EVALUATED
     * dataset's prefix, not the domain's, so it would be looked up in the other table and partition
     * nothing; the written member is refused at load ({@code GroupedAggregate.columnName} — this
     * sentence mirrors it), and so is the written list bound to a name. Since the T2 fold (review
     * L1-L1) so is a QUALIFIED member: the run time refuses every spliced qualified name under
     * {@code domain=} ({@code GroupSplice.splicedName}), so the load refuses it first. One sentence
     * for stage A and the loader's seam pass.
     *
     * @param call
     *            the splicing call
     * @param binding
     *            the spliced binding's name, {@code $} included
     * @param nameList
     *            its expression
     * @param where
     *            where the call is written, or {@code null} when the caller says so itself
     * @return the refusal, or {@code null} when the call is not foreign, the binding is not a name
     *         list, or it holds no {@code --} and no qualified member
     */
    public static @Nullable String domainPrefixRefusal(Expr.Call call, String binding,
            @Nullable Expr nameList, @Nullable String where)
    {
        if (!isForeign(call) || !(nameList instanceof Expr.Lit lit) || !isNameList(lit))
        {
            return null;
        }
        for (String name : names(lit))
        {
            if (name.startsWith("--") && name.indexOf('.') < 0)
            {
                return (where == null ? "" : where + ": ") + call.name()
                        + " does not resolve `--` (" + name + ", spliced from " + binding
                        + ") under domain=: name the column of the dataset explicitly";
            }
            if (net.cumba.corej.core.model.MatchDataset.qualifierOf(name) != null)
            {
                // T2 fold (review L1-L1): the run time refuses every spliced qualified name under
                // domain= (GroupSplice.splicedName - a qualified group= member is judged at load,
                // D-DOMAIN / D-SRC, which a spliced one never reaches); the load says so first
                return (where == null ? "" : where + ": ") + call.name() + " splices the qualified"
                        + " name " + name + " (from " + binding + ") under domain= — a qualified"
                        + " group= member is judged at load, so author it in the group= list"
                        + " itself";
            }
        }
        return null;
    }


    /**
     * The loader's twin of stage A's judgement, for a tree stage A never walks: the first
     * {@code $}-reference to a name-list binding outside a splice slot, as {@link #hint}, or
     * {@code null} when the tree reads none.
     *
     * @param tree
     *            the expression
     * @param nameLists
     *            the names of the rule's name-list bindings visible to {@code tree}
     * @param where
     *            what the tree is, for the message ("the binding $m")
     * @return the refusal, or {@code null}
     */
    public static @Nullable String firstValueRead(Expr tree, Set<String> nameLists, String where)
    {
        if (nameLists.isEmpty())
        {
            return null;
        }
        String read = firstRead(tree, nameLists);
        return read == null ? null : where + ": " + hint(read);
    }


    private static @Nullable String firstRead(Expr e, Set<String> nameLists)
    {
        switch (e)
        {
        case Expr.Ref ref ->
        {
            return ref.kind() == OperandKind.OPERATION_REF && nameLists.contains(ref.name())
                    ? ref.name()
                    : null;
        }
        case Expr.Lit lit ->
        {
            if (lit.kind() == Expr.LitKind.LIST && lit.value() instanceof List<?> items)
            {
                for (Object item : items)
                {
                    String read = item instanceof Expr inner ? firstRead(inner, nameLists) : null;
                    if (read != null)
                    {
                        return read;
                    }
                }
            }
            return null;
        }
        case Expr.Call call ->
        {
            Set<Expr> spliced = java.util.Collections
                    .newSetFromMap(new java.util.IdentityHashMap<>());
            for (GroupSplice.Slot slot : GroupSplice.spliceSlots(call))
            {
                spliced.addAll(slot.operands());
            }
            for (Expr arg : call.args())
            {
                String read = argumentRead(arg, spliced, nameLists);
                if (read != null)
                {
                    return read;
                }
            }
            for (Map.Entry<String, Expr> kw : call.kwargs().entrySet())
            {
                String read = argumentRead(kw.getValue(), spliced, nameLists);
                if (read != null)
                {
                    return read;
                }
            }
            return null;
        }
        case Expr.Binary b ->
        {
            String left = firstRead(b.left(), nameLists);
            return left != null ? left : firstRead(b.right(), nameLists);
        }
        case Expr.And a ->
        {
            return firstReadOf(a.parts(), nameLists);
        }
        case Expr.Or o ->
        {
            return firstReadOf(o.parts(), nameLists);
        }
        case Expr.Not n ->
        {
            return firstRead(n.inner(), nameLists);
        }
        }
    }


    /**
     * One argument: a spliced operand that IS the name-list reference is the admitted read; a list
     * literal at the splice slot admits its name-list members; anything else is walked.
     */
    private static @Nullable String argumentRead(Expr arg, Set<Expr> spliced, Set<String> nameLists)
    {
        if (spliced.contains(arg) && isNameListRef(arg, nameLists))
        {
            return null;
        }
        if (arg instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.LIST
                && lit.value() instanceof List<?> items && !spliced.isEmpty())
        {
            for (Object item : items)
            {
                if (!(item instanceof Expr inner)
                        || (spliced.contains(inner) && isNameListRef(inner, nameLists)))
                {
                    continue;
                }
                String read = firstRead(inner, nameLists);
                if (read != null)
                {
                    return read;
                }
            }
            return null;
        }
        return firstRead(arg, nameLists);
    }


    private static boolean isNameListRef(Expr e, Set<String> nameLists)
    {
        return e instanceof Expr.Ref ref && ref.kind() == OperandKind.OPERATION_REF
                && nameLists.contains(ref.name());
    }


    private static @Nullable String firstReadOf(List<Expr> parts, Set<String> nameLists)
    {
        for (Expr part : parts)
        {
            String read = firstRead(part, nameLists);
            if (read != null)
            {
                return read;
            }
        }
        return null;
    }
}
