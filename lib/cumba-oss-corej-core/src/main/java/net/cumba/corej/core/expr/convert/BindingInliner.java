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
 * the readers that used to resolve a {@code $}-reference against {@code getOperations()} alone and
 * degrade to their worst case on a miss (R19 {@code RuleClassifier}, R27
 * {@code StudyRuleClassifier}, the corpus test tree's C2–C5): a compiled binding IS a named
 * sub-expression, so reading it inline is exact, not an approximation. An operation binding's
 * {@code $}-reference is left alone — those readers keep their operation arm.
 * </p>
 *
 * <p>
 * ⚠ For <b>static</b> readers only. Evaluation never inlines: the runner evaluates a compiled
 * binding once per context ({@code BindingValue}), and the inlined tree would evaluate it once per
 * occurrence. A forward or cyclic reference is a stage-A load error, so the recursion terminates on
 * every loadable rule; a guard stops it on a hand-built one (the reference is then left in place).
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
