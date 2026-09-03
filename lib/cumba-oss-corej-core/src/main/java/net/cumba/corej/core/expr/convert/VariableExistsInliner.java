package net.cumba.corej.core.expr.convert;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.Operation;
import org.jspecify.annotations.Nullable;

/**
 * Shared inline mapping that lowers a {@code variable_exists} <em>operation</em> consumed in the
 * Check as {@code $X == true} / {@code $X == false} into the Check as the {@code var_exists(<col>)}
 * / {@code not var_exists(<col>)} check-position <em>function</em>, dropping the operation.
 *
 * <p>
 * The two engines implement variable-existence on opposite sides of the operation/operator line:
 * Python keeps a {@code variable_exists} operation (consumed as {@code $X == true}), while Java
 * retired that operation in favour of the {@code var_exists(X)} check function (see
 * {@code plans/done/PLAN-variable-exists-cross-dataset.md}). So the shipped {@code rules/} corpus
 * (production Java) and the parity Java lane's native fixture-compile path must rewrite the
 * operation form into the function form that Java already runs correctly. This class is that one
 * mapping, invoked from both:
 * </p>
 * <ul>
 * <li>the offline converter ({@code OperationInliner}) — fixes shipped {@code rules/}; and</li>
 * <li>Java's native fixture-compile path
 * ({@link net.cumba.corej.core.RulePackageLoader#installNativeExpr}) — so the parity Java lane
 * evaluates the fixture's {@code variable_exists} operation as {@code var_exists()}.</li>
 * </ul>
 *
 * <p>
 * The result is exactly the expression the retired {@code var_exists} check-leaf already lowered to
 * ({@code var_exists(EXVAMT)} / {@code var_exists(EX.EXVAMTU)}), which Java compiles natively today
 * (see {@code ExprCompiler.compileExists} {@code ExistsMode.VARIABLE}) — a proven-good target, not
 * a new evaluation surface.
 * </p>
 *
 * <p>
 * Mapping rules (mirroring the plan): a {@code variable_exists} operation {@code {id:$X,
 * name:<col>, domain:<D>?}} is inlined only when <em>every</em> reference to {@code $X} across the
 * supplied expressions is the operand of a boolean equality ({@code $X == true} or
 * {@code $X == false}). If {@code $X} is referenced anywhere else, it is left untouched (field form
 * / residual). A present {@code domain} qualifies the column as {@code <D>.<col>} (the
 * cross-dataset {@code var_exists} surface).
 * </p>
 */
public final class VariableExistsInliner
{

    private VariableExistsInliner()
    {
    }


    /**
     * Builds the {@code $}-id &rarr; {@code var_exists} column map for every
     * {@code variable_exists} operation in {@code ops}. The column is the operation's {@code name},
     * qualified {@code <domain>.<name>} when the operation carries a {@code domain} (the
     * cross-dataset {@code var_exists("D.X")} surface). Operations without an {@code id} or
     * {@code name} are skipped.
     *
     * @param ops
     *            the rule's operations (may be {@code null})
     * @return id &rarr; column for each {@code variable_exists} operation (empty if none)
     */
    public static Map<String, String> candidateColumns(@Nullable List<Operation> ops)
    {
        Map<String, String> out = new LinkedHashMap<>();
        if (ops == null)
        {
            return out;
        }
        for (Operation op : ops)
        {
            if (!"variable_exists".equals(op.getOperator()) || op.getId() == null
                    || op.getName() == null)
            {
                continue;
            }
            String col = op.getDomain() == null || op.getDomain().isEmpty() ? op.getName()
                    : op.getDomain() + "." + op.getName();
            out.put(op.getId(), col);
        }
        return out;
    }


    /**
     * Returns the subset of {@code candidates} whose every reference across {@code exprs} is a
     * boolean-equality operand ({@code $X == true|false}). An id referenced in any other position
     * is excluded (left field form), so a partial / unsafe rewrite never happens.
     *
     * @param exprs
     *            every Check / Precondition expression of the rule
     * @param candidates
     *            id &rarr; column from {@link #candidateColumns}
     * @return the eligible id &rarr; column subset
     */
    public static Map<String, String> eligible(List<Expr> exprs, Map<String, String> candidates)
    {
        if (candidates.isEmpty())
        {
            return Map.of();
        }
        Map<String, Integer> total = new HashMap<>();
        Map<String, Integer> boolEq = new HashMap<>();
        for (Expr e : exprs)
        {
            countTotalRefs(e, candidates.keySet(), total);
            countBoolEq(e, candidates.keySet(), boolEq);
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : candidates.entrySet())
        {
            int t = total.getOrDefault(e.getKey(), 0);
            int b = boolEq.getOrDefault(e.getKey(), 0);
            if (t > 0 && t == b)
            {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }


    /**
     * The subset of {@code eligible} ids whose operation must be <b>kept</b> even though its
     * {@code $X == true} consumption has been rewritten to {@code var_exists(<col>)}: those the
     * rule declares in {@code Outcome.Output_Variables}.
     *
     * <p>
     * ⚠⚠ This is the whole of the {@code Fix #181} reporting warrant applied to this operator, and
     * it is deliberately <em>not</em> "stop lowering". The verdict stays on the proven
     * {@code var_exists} function — nothing about
     * {@code plans/done/PLAN-variable-exists-cross-dataset.md}'s retirement of the operation as an
     * evaluation surface is reversed — while the operation survives purely so
     * {@link net.cumba.corej.core.exec.RuleRunner} materialises its {@code $}-result and the
     * declared output variable has a value to report. An id the rule does not report is still
     * dropped: keeping it would cost a lookup per (rule, dataset) and buy nothing.
     * </p>
     *
     * <p>
     * Requires {@link net.cumba.corej.core.model.OperationType#VARIABLE_EXISTS} to exist — without
     * a native execution surface the kept operation would resolve to null (or, in Form B, fail to
     * load at all). Retaining and executing are one decision; do not separate them.
     * </p>
     *
     * @param eligible
     *            the ids about to be rewritten out of the Check (from {@link #eligible})
     * @param outputVariables
     *            the rule's declared {@code Outcome.Output_Variables} (may be {@code null})
     * @return the ids whose operation and {@code Output_Variables} entry must be preserved
     */
    public static java.util.Set<String> reported(java.util.Set<String> eligible,
            @Nullable List<String> outputVariables)
    {
        if (eligible.isEmpty() || outputVariables == null || outputVariables.isEmpty())
        {
            return java.util.Set.of();
        }
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String ov : outputVariables)
        {
            // An exclusion token `!$X` is the opposite of a report: the author is suppressing
            // the $-result, so the operation is NOT retained on its account (E-2).
            if (!net.cumba.corej.core.model.OutputVariableToken.isExclusion(ov)
                    && eligible.contains(ov))
            {
                out.add(ov);
            }
        }
        return out;
    }


    /**
     * Rewrites {@code expr}, replacing each {@code $X == true} with {@code var_exists(<col>)} and
     * each {@code $X == false} with {@code not var_exists(<col>)} for every id in
     * {@code eligibleColumns}. Returns {@code expr} unchanged when the map is empty.
     *
     * @param expr
     *            the expression to rewrite
     * @param eligibleColumns
     *            id &rarr; column subset from {@link #eligible}
     * @return the rewritten expression
     */
    public static Expr rewrite(Expr expr, Map<String, String> eligibleColumns)
    {
        if (eligibleColumns.isEmpty())
        {
            return expr;
        }
        Map<String, Expr> calls = new HashMap<>();
        // Phase 5 (plan unified-callable-surface): a plain name is emitted in the generator's
        // preferred quoted form, matching CheckToExpr.nameOperand; structured names (dotted
        // cross-dataset refs) keep the bare spelling whose OperandKind encodes the structure.
        eligibleColumns
                .forEach((id, col) -> calls.put(id,
                        CheckExpressionParser.parse(col.matches("(--)?[A-Za-z_][A-Za-z0-9_]*")
                                ? "var_exists(\"" + col + "\")"
                                : "var_exists(" + col + ")")));
        return replace(expr, calls);
    }


    private static Expr replace(Expr e, Map<String, Expr> calls)
    {
        return switch (e)
        {
        case Expr.Binary b ->
        {
            Expr hit = asVarExists(b, calls);
            yield hit != null ? hit
                    : new Expr.Binary(b.op(), replace(b.left(), calls), replace(b.right(), calls));
        }
        case Expr.And a -> new Expr.And(a.parts().stream().map(p -> replace(p, calls)).toList());
        case Expr.Or o -> new Expr.Or(o.parts().stream().map(p -> replace(p, calls)).toList());
        case Expr.Not n -> new Expr.Not(replace(n.inner(), calls));
        case Expr.Call c -> new Expr.Call(c.name(),
                c.args().stream().map(a -> replace(a, calls)).toList(), replaceKwargs(c, calls));
        default -> e;
        };
    }


    private static Map<String, Expr> replaceKwargs(Expr.Call c, Map<String, Expr> calls)
    {
        Map<String, Expr> out = new LinkedHashMap<>();
        c.kwargs().forEach((k, v) -> out.put(k, replace(v, calls)));
        return out;
    }


    /**
     * Returns the {@code var_exists} replacement for a {@code $X == true|false} binary (negated for
     * {@code == false}), or {@code null} when {@code b} is not such a binary for an eligible id.
     */
    private static @Nullable Expr asVarExists(Expr.Binary b, Map<String, Expr> calls)
    {
        if (b.op() != Expr.BinOp.EQ)
        {
            return null;
        }
        // Identify the (eligible-$-ref, boolean-literal) pair on either side of the `==`.
        Expr.Ref ref;
        Expr.Lit lit;
        if (b.left() instanceof Expr.Ref l && isBoolLit(b.right()))
        {
            ref = l;
            lit = (Expr.Lit) b.right();
        }
        else if (b.right() instanceof Expr.Ref rr && isBoolLit(b.left()))
        {
            ref = rr;
            lit = (Expr.Lit) b.left();
        }
        else
        {
            return null;
        }
        Expr call = calls.get(ref.name());
        if (call == null)
        {
            return null;
        }
        return (Boolean) lit.value() ? call : new Expr.Not(call);
    }


    /** The eligible {@code $}-id compared against a boolean literal in this binary, or null. */
    private static @Nullable String boolComparedRef(Expr left, Expr right,
            java.util.Set<String> ids)
    {
        if (left instanceof Expr.Ref r && ids.contains(r.name()) && isBoolLit(right))
        {
            return r.name();
        }
        if (right instanceof Expr.Ref r && ids.contains(r.name()) && isBoolLit(left))
        {
            return r.name();
        }
        return null;
    }


    private static boolean isBoolLit(Expr e)
    {
        return e instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.BOOL;
    }


    private static void countTotalRefs(Expr e, java.util.Set<String> ids, Map<String, Integer> out)
    {
        switch (e)
        {
        case Expr.Ref r ->
        {
            if (ids.contains(r.name()))
            {
                out.merge(r.name(), 1, Integer::sum);
            }
        }
        case Expr.Binary b ->
        {
            countTotalRefs(b.left(), ids, out);
            countTotalRefs(b.right(), ids, out);
        }
        case Expr.And a -> a.parts().forEach(p -> countTotalRefs(p, ids, out));
        case Expr.Or o -> o.parts().forEach(p -> countTotalRefs(p, ids, out));
        case Expr.Not n -> countTotalRefs(n.inner(), ids, out);
        case Expr.Call c ->
        {
            c.args().forEach(a -> countTotalRefs(a, ids, out));
            c.kwargs().values().forEach(v -> countTotalRefs(v, ids, out));
        }
        default ->
        {
            // Lit — no nested refs.
        }
        }
    }


    private static void countBoolEq(Expr e, java.util.Set<String> ids, Map<String, Integer> out)
    {
        if (e instanceof Expr.Binary b && b.op() == Expr.BinOp.EQ)
        {
            String id = boolComparedRef(b.left(), b.right(), ids);
            if (id != null)
            {
                out.merge(id, 1, Integer::sum);
                return; // do not descend — the matched ref is accounted for here
            }
        }
        switch (e)
        {
        case Expr.Binary b ->
        {
            countBoolEq(b.left(), ids, out);
            countBoolEq(b.right(), ids, out);
        }
        case Expr.And a -> a.parts().forEach(p -> countBoolEq(p, ids, out));
        case Expr.Or o -> o.parts().forEach(p -> countBoolEq(p, ids, out));
        case Expr.Not n -> countBoolEq(n.inner(), ids, out);
        case Expr.Call c ->
        {
            c.args().forEach(a -> countBoolEq(a, ids, out));
            c.kwargs().values().forEach(v -> countBoolEq(v, ids, out));
        }
        default ->
        {
            // Ref / Lit — nothing to descend.
        }
        }
    }

}
