package net.cumba.corej.core.expr.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.cumba.corej.core.expr.ExpressionPrinter;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.CompiledBinding;
import net.cumba.corej.core.model.Rule;
import org.jspecify.annotations.Nullable;

/**
 * The ONE level derivation for an operand that is read once per call as a dataset-level value
 * ({@code PLAN-union-function} §3.3, {@code PLAN-binding-substitutability} G3 — ruled by D3 / D16:
 * where {@code $x} is accepted, the expression {@code $x} holds is accepted too, and the only
 * refusals are a type or a level mismatch, reported alike for both spellings). A {@code $}-binding
 * is judged by its installed domain ({@link CompiledBinding#needsCursor()}); any other expression —
 * and a {@code $} whose domain is not installed yet, in the loader's seam pass — by
 * {@link DomainScan#infer} over {@link BindingDomains#forRule}, the very derivation
 * {@code RulePackageLoader.installNativeExpr} installs on a binding, so
 * {@code $p: split_by(X, ",")} read as {@code $p} and {@code split_by(X, ",")} written inline get
 * the same answer.
 *
 * <p>
 * Two callers: stage A ({@code StageAChecker.checkListFunctionCall}, the armed kind
 * {@code OPERATION_READS_CURSOR_BINDING}) where it walks the rule, and the loader's seam pass
 * ({@link #firstRefusal}) where it does not — a Check-less rule's bindings, a
 * {@code Match_Datasets} Filter, the re-run after a {@code CHECKER_FAILURE} — so a per-row operand
 * never silently reads row 0 on either path.
 */
public final class ListOperandLevel
{

    /** The set functions whose operands are dataset-level lists ({@code minus}, {@code union}). */
    public static final Set<String> LIST_SET_FUNCTIONS = Set
            .of(net.cumba.corej.core.exec.Minus.NAME, net.cumba.corej.core.exec.Union.NAME);

    private ListOperandLevel()
    {
    }


    /**
     * Whether the operand's value varies per row or per variable, so a reader that takes one value
     * per call has no row to pick it at.
     *
     * @param operand
     *            the operand, bound ({@code $x}) or inline
     * @param rule
     *            the rule it is written in (its bindings supply the {@code $} domains)
     * @return {@code true} for a per-row / per-variable operand
     */
    public static boolean needsCursor(Expr operand, Rule rule)
    {
        if (operand instanceof Expr.Ref ref && ref.kind() == OperandKind.OPERATION_REF)
        {
            CompiledBinding read = rule.compiledBinding(ref.name());
            if (read != null && read.domain() != null)
            {
                return read.needsCursor();
            }
        }
        return !DomainScan.infer(operand, BindingDomains.forRule(rule)).isBroadcast();
    }


    /**
     * The refusal's text — one sentence for both spellings and both set functions.
     *
     * @param function
     *            the reading function ({@code minus} / {@code union})
     * @param where
     *            where the call is written ("the Check (ERROR)", "the binding $v", …)
     * @param operand
     *            the refused operand, printed as written
     * @return the message
     */
    public static String refusal(String function, String where, Expr operand)
    {
        return "the list function " + function + "(…) in " + where
                + " cannot read the per-row or per-variable operand `"
                + ExpressionPrinter.print(operand) + "` — " + setOperation(function)
                + " reads only dataset-level lists";
    }


    /** {@code "a set union"} / {@code "a set difference"}, for the message. */
    public static String setOperation(String function)
    {
        return net.cumba.corej.core.exec.Union.NAME.equals(function) ? "a set union"
                : "a set difference";
    }


    /**
     * The loader's twin of stage A's walk: the first per-row / per-variable operand of a set
     * function anywhere in the expression, or {@code null} when every operand is dataset-level.
     *
     * @param expr
     *            the expression to walk
     * @param rule
     *            the rule it belongs to
     * @param where
     *            where it is written, for the message
     * @return the refusal, or {@code null}
     */
    public static @Nullable String firstRefusal(Expr expr, Rule rule, String where)
    {
        switch (expr)
        {
        case Expr.And a ->
        {
            return firstRefusal(a.parts(), rule, where);
        }
        case Expr.Or o ->
        {
            return firstRefusal(o.parts(), rule, where);
        }
        case Expr.Not n ->
        {
            return firstRefusal(n.inner(), rule, where);
        }
        case Expr.Binary b ->
        {
            return firstRefusal(List.of(b.left(), b.right()), rule, where);
        }
        case Expr.Call c ->
        {
            List<Expr> operands = new ArrayList<>(c.args());
            operands.addAll(c.kwargs().values());
            if (LIST_SET_FUNCTIONS.contains(c.name()))
            {
                for (Expr operand : operands)
                {
                    if (needsCursor(operand, rule))
                    {
                        return refusal(c.name(), where, operand);
                    }
                }
            }
            return firstRefusal(operands, rule, where);
        }
        case Expr.Lit lit ->
        {
            if (lit.kind() == Expr.LitKind.LIST && lit.value() instanceof List<?> items)
            {
                for (Object item : items)
                {
                    if (item instanceof Expr e)
                    {
                        String refusal = firstRefusal(e, rule, where);
                        if (refusal != null)
                        {
                            return refusal;
                        }
                    }
                }
            }
            return null;
        }
        case Expr.Ref _ ->
        {
            return null;
        }
        }
    }


    private static @Nullable String firstRefusal(List<Expr> parts, Rule rule, String where)
    {
        for (Expr part : parts)
        {
            String refusal = firstRefusal(part, rule, where);
            if (refusal != null)
            {
                return refusal;
            }
        }
        return null;
    }
}
