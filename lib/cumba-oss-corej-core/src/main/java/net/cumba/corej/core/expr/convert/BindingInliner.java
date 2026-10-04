package net.cumba.corej.core.expr.convert;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.CompiledBinding;
import net.cumba.corej.core.model.Rule;
import org.jspecify.annotations.Nullable;

/**
 * Reads <b>through</b> a rule's compiled bindings ({@code PLAN-binding-expressions}, wave 0): every
 * {@code $}-reference that names a compiled binding is replaced by that binding's expression,
 * recursively, so a static reader — a classifier, a dataset-reference walk, a corpus lint — sees
 * the rule exactly as if the binding had been written inline.
 *
 * <p>
 * ⭐ This is what "resolve a compiled binding by walking its expression like a Check leaf" means for
 * the static readers ({@code RuleClassifier}, {@code StudyRuleClassifier}, the corpus test tree's
 * C2–C5): a compiled binding IS a named sub-expression, so reading it inline is exact, not an
 * approximation. Since runbook W8 ({@code PLAN-retire-operation-surface}) every binding is a
 * compiled binding ({@code Rule.bindingOrder()} is the one view), so every {@code $}-reference to a
 * binding of the rule is inlined; a {@code $}-reference naming no binding of the rule is left in
 * place.
 * </p>
 *
 * <p>
 * ⚠ For <b>static</b> readers only. Evaluation never inlines: the runner evaluates a compiled
 * binding once per context ({@code BindingValue}), and the inlined tree would evaluate it once per
 * occurrence. A forward or cyclic reference is a stage-A load error, so the recursion terminates on
 * every loadable rule; a guard stops it on a hand-built one (the reference is then left in place).
 * The one rewrite evaluation shares is {@link #inlinePresenceNames}: it moves a NAME into a
 * presence probe, never a value, so nothing is evaluated twice.
 * </p>
 */
public final class BindingInliner
{

    private BindingInliner()
    {
    }


    /**
     * The rule's compiled bindings by name, in authored order.
     *
     * @param rule
     *            the rule
     * @return name → expression; empty when the rule has none
     */
    public static Map<String, Expr> compiledExpressions(Rule rule)
    {
        List<CompiledBinding> compiled = rule.getCompiledBindings();
        if (compiled == null || compiled.isEmpty())
        {
            return Map.of();
        }
        Map<String, Expr> byName = new LinkedHashMap<>();
        for (CompiledBinding binding : compiled)
        {
            byName.put(binding.name(), binding.expression());
        }
        return byName;
    }


    /**
     * {@code expr} with every reference to one of the rule's compiled bindings replaced by that
     * binding's (recursively inlined) expression; the very same instance when nothing is replaced.
     *
     * @param expr
     *            the expression to read through, may be {@code null}
     * @param rule
     *            the rule the references resolve against
     * @return the inlined expression, or {@code null} for a {@code null} input
     */
    public static @Nullable Expr inline(@Nullable Expr expr, Rule rule)
    {
        if (expr == null)
        {
            return null;
        }
        Map<String, Expr> compiled = compiledExpressions(rule);
        return compiled.isEmpty() ? expr : inline(expr, compiled, new HashSet<>());
    }


    /**
     * {@link #inline(Expr, Rule)} against an explicit name → expression map — for a caller that
     * holds the bindings without a {@link Rule} (the corpus test tree reads raw YAML).
     *
     * @param expr
     *            the expression to read through
     * @param compiled
     *            compiled binding name → expression
     * @return the inlined expression
     */
    public static Expr inline(Expr expr, Map<String, Expr> compiled)
    {
        return compiled.isEmpty() ? expr : inline(expr, compiled, new HashSet<>());
    }


    private static Expr inline(Expr e, Map<String, Expr> compiled, Set<String> expanding)
    {
        return switch (e)
        {
        case Expr.Ref ref ->
        {
            Expr body = ref.kind() == OperandKind.OPERATION_REF ? compiled.get(ref.name()) : null;
            if (body == null || !expanding.add(ref.name()))
            {
                yield ref;
            }
            Expr inlined = inline(body, compiled, expanding);
            expanding.remove(ref.name());
            yield inlined;
        }
        case Expr.Lit lit ->
        {
            if (lit.kind() != Expr.LitKind.LIST)
            {
                yield lit;
            }
            @SuppressWarnings("unchecked")
            List<Expr> items = (List<Expr>) lit.value();
            List<Expr> out = inlineAll(items, compiled, expanding);
            yield out == items ? lit : new Expr.Lit(Expr.LitKind.LIST, out);
        }
        case Expr.Call call ->
        {
            List<Expr> args = inlineAll(call.args(), compiled, expanding);
            Map<String, Expr> kwargs = new LinkedHashMap<>();
            boolean changed = args != call.args();
            for (Map.Entry<String, Expr> kw : call.kwargs().entrySet())
            {
                Expr value = inline(kw.getValue(), compiled, expanding);
                changed |= value != kw.getValue();
                kwargs.put(kw.getKey(), value);
            }
            yield changed ? new Expr.Call(call.name(), args, kwargs) : call;
        }
        case Expr.Binary binary ->
        {
            Expr condition = conditionAgainstLiteral(binary, compiled, expanding);
            if (condition != null)
            {
                yield condition;
            }
            Expr left = inline(binary.left(), compiled, expanding);
            Expr right = inline(binary.right(), compiled, expanding);
            yield left == binary.left() && right == binary.right() ? binary
                    : new Expr.Binary(binary.op(), left, right);
        }
        case Expr.And and ->
        {
            List<Expr> parts = inlineAll(and.parts(), compiled, expanding);
            yield parts == and.parts() ? and : new Expr.And(parts);
        }
        case Expr.Or or ->
        {
            List<Expr> parts = inlineAll(or.parts(), compiled, expanding);
            yield parts == or.parts() ? or : new Expr.Or(parts);
        }
        case Expr.Not not ->
        {
            Expr inner = inline(not.inner(), compiled, expanding);
            yield inner == not.inner() ? not : new Expr.Not(inner);
        }
        };
    }


    /**
     * {@code $b == true} / {@code $b != false} over a <b>condition</b> binding reads as the
     * condition itself, {@code $b == false} / {@code $b != true} as its negation — the identity
     * {@code ExprCompiler}'s unified boolean surface applies to a boolean call compared to a
     * literal ({@code boolEqLiteral}). Without it the reader would see a comparison whose operand
     * is a whole condition, a shape no Check ever authors, and classify it as having no operand at
     * all. {@code null} when the node is not that shape.
     */
    private static @Nullable Expr conditionAgainstLiteral(Expr.Binary binary,
            Map<String, Expr> compiled, Set<String> expanding)
    {
        if (binary.op() != Expr.BinOp.EQ && binary.op() != Expr.BinOp.NEQ)
        {
            return null;
        }
        Expr ref = binary.left() instanceof Expr.Lit ? binary.right() : binary.left();
        Expr literal = ref == binary.left() ? binary.right() : binary.left();
        if (!(ref instanceof Expr.Ref r) || r.kind() != OperandKind.OPERATION_REF
                || !(literal instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.BOOL)
        {
            return null;
        }
        Expr body = compiled.get(r.name());
        if (body == null || !net.cumba.corej.core.expr.eval.ExprCompiler.isConditionExpr(body))
        {
            return null;
        }
        Expr condition = inline(ref, compiled, expanding);
        boolean isEquality = binary.op() == Expr.BinOp.EQ;
        boolean literalTrue = Boolean.TRUE.equals(lit.value());
        boolean positive = isEquality == literalTrue;
        return positive ? condition : new Expr.Not(condition);
    }

    /** The presence probes whose argument names a column ({@code ds_exists} is not one). */
    private static final Set<String> VARIABLE_PRESENCE = Set.of("var_exists", "var_not_exists");

    /**
     * ⭐ {@code expr} with every {@code var_exists($x)} / {@code var_not_exists($x)} whose binding
     * holds a <b>name form</b> ({@link #isNameForm}) rewritten to the inline spelling
     * {@code var_exists(<that name>)}; the very same instance when nothing is rewritten.
     *
     * <p>
     * D3 / D16 ({@code PLAN-binding-substitutability} G1): a {@code $}-binding answers as its
     * expression written in place. A presence probe reads its argument as a NAME when it is one — a
     * bare column, a dotted {@code DS.X}, a {@code ${…}} template, a string literal, the
     * {@code varname()} cursor — and as a per-row VALUE otherwise ({@code ExprCompiler}'s computed
     * path). So {@code $c: TRT01P} then {@code var_exists($c)} is {@code var_exists(TRT01P)} — the
     * column's presence, one dataset fact, the {@code --} prefix specialised as for the literal —
     * never TRT01P's cells read as names; {@code $n: concat(…)} stays a binding reference and is
     * evaluated per row ({@code PLAN-dynamic-column-corpus-respelling} §6.2, T2 review MEDIUM-2).
     * </p>
     *
     * <p>
     * Unlike {@link #inline(Expr, Rule)} this rewrite is <b>for evaluation as well</b>: the loader
     * applies it before stage A, the compiler, the domain scan and the specialiser see the tree
     * ({@code RulePackageLoader.installNativeExpr}), and stage A applies it to what it walks, so no
     * reader can tell the two spellings apart. It moves a name, never a value: nothing is evaluated
     * twice. The binding itself stays declared (an {@code Output_Variables} entry may read it).
     * </p>
     *
     * @param expr
     *            the expression to rewrite
     * @param bound
     *            the bindings the expression may read, name → expression
     * @return the rewritten expression
     */
    public static Expr inlinePresenceNames(Expr expr, Map<String, Expr> bound)
    {
        return bound.isEmpty() ? expr : presenceNames(expr, bound);
    }


    /**
     * {@link #inlinePresenceNames} for ONE call, not its arguments: {@code call} rewritten to
     * {@code var_exists(<name>)} when it is a presence probe over a name-form binding, else the
     * very same instance — for a walker that visits every call itself (stage A).
     *
     * @param call
     *            the call
     * @param bound
     *            binding name → expression
     * @return the inline spelling, or {@code call}
     */
    public static Expr.Call inlinePresenceName(Expr.Call call, Map<String, Expr> bound)
    {
        if (VARIABLE_PRESENCE.contains(call.name()) && call.args().size() == 1
                && call.kwargs().isEmpty() && isBindingReference(call.args().get(0)))
        {
            Expr name = boundExpression(call.args().get(0), bound);
            return isNameForm(name) ? new Expr.Call(call.name(), List.of(name), Map.of()) : call;
        }
        return call;
    }


    /**
     * {@code arg}, or — when it is a {@code $}-binding reference — the expression that binding
     * holds, followed through a binding of a binding. Bindings reference only earlier ones (a
     * forward or cyclic reference is an armed stage-A error); the walk is bounded by the binding
     * count all the same, so a cycle that reached here could not loop. A reference naming no
     * binding of {@code bound} is returned as it is.
     *
     * @param arg
     *            the argument
     * @param bound
     *            binding name → expression
     * @return the expression {@code arg} stands for
     */
    public static Expr boundExpression(Expr arg, Map<String, Expr> bound)
    {
        Expr e = arg;
        for (int hops = 0; hops <= bound.size() && isBindingReference(e); hops++)
        {
            Expr next = bound.get(((Expr.Ref) e).name());
            if (next == null)
            {
                break;
            }
            e = next;
        }
        return e;
    }


    /**
     * Whether {@code e} is an argument a presence probe reads as a NAME — the forms
     * {@code ExprCompiler.compileExists}' literal path handles: a reference that is not a
     * {@code $}-binding (a bare column, a dotted {@code DS.X}, a {@code ${…}} template,
     * {@code variable_name}), a string literal, or the {@code varname()} cursor. Anything else — a
     * call, a number, a list — is a computed value.
     *
     * @param e
     *            the expression
     * @return whether it is a name form
     */
    public static boolean isNameForm(Expr e)
    {
        return (e instanceof Expr.Ref && !isBindingReference(e))
                || (e instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.STRING)
                || net.cumba.corej.core.expr.eval.ExprCompiler.isCurrentVariableName(e);
    }


    private static boolean isBindingReference(Expr e)
    {
        return e instanceof Expr.Ref r && r.kind() == OperandKind.OPERATION_REF;
    }


    private static Expr presenceNames(Expr e, Map<String, Expr> bound)
    {
        return switch (e)
        {
        case Expr.Ref ref -> ref;
        case Expr.Lit lit ->
        {
            if (lit.kind() != Expr.LitKind.LIST)
            {
                yield lit;
            }
            @SuppressWarnings("unchecked")
            List<Expr> items = (List<Expr>) lit.value();
            List<Expr> out = presenceNamesAll(items, bound);
            yield out == items ? lit : new Expr.Lit(Expr.LitKind.LIST, out);
        }
        case Expr.Call call ->
        {
            Expr.Call probe = inlinePresenceName(call, bound);
            if (probe != call)
            {
                yield probe;
            }
            List<Expr> args = presenceNamesAll(call.args(), bound);
            Map<String, Expr> kwargs = new LinkedHashMap<>();
            boolean changed = args != call.args();
            for (Map.Entry<String, Expr> kw : call.kwargs().entrySet())
            {
                Expr value = presenceNames(kw.getValue(), bound);
                changed |= value != kw.getValue();
                kwargs.put(kw.getKey(), value);
            }
            yield changed ? new Expr.Call(call.name(), args, kwargs) : call;
        }
        case Expr.Binary binary ->
        {
            Expr left = presenceNames(binary.left(), bound);
            Expr right = presenceNames(binary.right(), bound);
            yield left == binary.left() && right == binary.right() ? binary
                    : new Expr.Binary(binary.op(), left, right);
        }
        case Expr.And and ->
        {
            List<Expr> parts = presenceNamesAll(and.parts(), bound);
            yield parts == and.parts() ? and : new Expr.And(parts);
        }
        case Expr.Or or ->
        {
            List<Expr> parts = presenceNamesAll(or.parts(), bound);
            yield parts == or.parts() ? or : new Expr.Or(parts);
        }
        case Expr.Not not ->
        {
            Expr inner = presenceNames(not.inner(), bound);
            yield inner == not.inner() ? not : new Expr.Not(inner);
        }
        };
    }


    /** {@link #presenceNames} element-wise; the same instance when no element changed. */
    private static List<Expr> presenceNamesAll(List<Expr> items, Map<String, Expr> bound)
    {
        List<Expr> out = null;
        for (int i = 0; i < items.size(); i++)
        {
            Expr item = items.get(i);
            Expr rewritten = presenceNames(item, bound);
            if (rewritten != item && out == null)
            {
                out = new ArrayList<>(items.subList(0, i));
            }
            if (out != null)
            {
                out.add(rewritten);
            }
        }
        return out == null ? items : out;
    }


    /** The list inlined element-wise; the same instance when no element changed. */
    private static List<Expr> inlineAll(List<Expr> items, Map<String, Expr> compiled,
            Set<String> expanding)
    {
        List<Expr> out = null;
        for (int i = 0; i < items.size(); i++)
        {
            Expr item = items.get(i);
            Expr inlined = inline(item, compiled, expanding);
            if (inlined != item && out == null)
            {
                out = new ArrayList<>(items.subList(0, i));
            }
            if (out != null)
            {
                out.add(inlined);
            }
        }
        return out == null ? items : out;
    }

}
