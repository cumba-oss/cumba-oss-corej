package net.cumba.corej.core.expr.convert;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.expr.OperandClassifier;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.Operation;
import org.jspecify.annotations.Nullable;

/**
 * Shared inline mapping that lowers a {@code split_by} <em>operation</em> into the per-row native
 * value function {@code split_by(<col>, "<delimiter>")}, replacing every {@code $}-id reference and
 * dropping the operation (T9 — delimiter-split-then-per-token membership).
 *
 * <p>
 * The two engines implement the split on opposite sides of the operation/function line. Python
 * keeps a {@code split_by} operation ({@code operations/split_by.py}: {@code Series.str.split})
 * whose {@code $}-result — a Series of per-row token lists — is consumed by
 * {@code not_contains_all}. coreJ has <b>no</b> {@code SPLIT_BY} {@link Operation}: an operation
 * result is <em>broadcast</em> to every row ({@code ExprCompiler.operationCallPlan} →
 * {@code ConstVector}), so it cannot carry a per-row-<em>varying</em> list. Instead coreJ evaluates
 * the split per row as a value function ({@code split_by(--VAR, "/")}, like {@code upper}/
 * {@code substring}), and this class rewrites the authored operation form into that function so the
 * shipped {@code rules/} corpus (offline {@code OperationInliner}) and the parity Java native
 * fixture-compile path ({@code RulePackageLoader.installNativeExpr}) both evaluate it natively — a
 * single mapping the two paths share so they cannot drift (exactly the
 * {@link VariableExistsInliner} pattern).
 * </p>
 *
 * <p>
 * The resulting expression — e.g. {@code not contains_all($codelist, split_by(--SPEC, "/"))} —
 * compiles to the per-row {@code not_contains_all} token verdict
 * ({@code ExprCompiler.compileNotContainsAll}): the row fires when any delimited token is not a
 * member of the source set. The token-producing {@code split_by} operation carries no library/skip
 * semantics, so — unlike a library operation — it is always fully inlined; the companion
 * {@code codelist_terms} operation stays a field-form {@code Operations} entry and keeps the
 * library skip-gate.
 * </p>
 */
public final class SplitByInliner
{

    private SplitByInliner()
    {
    }


    /**
     * Builds the {@code $}-id &rarr; {@code split_by(<col>, "<delim>")} call map for every
     * {@code split_by} operation in {@code ops}. The column is the operation's {@code name} (a
     * {@code --} domain-prefix wildcard is preserved and resolved at eval time). Operations without
     * an {@code id}, {@code name}, or {@code delimiter} are skipped.
     *
     * @param ops
     *            the rule's operations (may be {@code null})
     * @return id &rarr; split_by call for each {@code split_by} operation (empty if none)
     */
    public static Map<String, Expr> candidateCalls(@Nullable List<Operation> ops)
    {
        Map<String, Expr> out = new LinkedHashMap<>();
        if (ops == null)
        {
            return out;
        }
        for (Operation op : ops)
        {
            if (!"split_by".equals(op.getOperator()) || op.getId() == null || op.getName() == null
                    || op.getDelimiter() == null)
            {
                continue;
            }
            Expr col = new Expr.Ref(op.getName(), OperandClassifier.classify(op.getName(), -1));
            Expr delim = new Expr.Lit(Expr.LitKind.STRING, op.getDelimiter());
            out.put(op.getId(), new Expr.Call("split_by", List.of(col, delim), Map.of()));
        }
        return out;
    }


    /**
     * The subset of {@code candidates} whose {@code $}-id is referenced anywhere in {@code exprs}.
     * An unreferenced {@code split_by} operation is dead and left untouched (it is not dropped by
     * the caller). Every reference is safely replaceable regardless of position — a rewrite that
     * lands in a non-compiling position is caught by the caller's {@code loadsNative} revert gate.
     *
     * @param exprs
     *            every Check / Precondition expression of the rule
     * @param candidates
     *            id &rarr; call from {@link #candidateCalls}
     * @return the referenced id &rarr; call subset
     */
    public static Map<String, Expr> referenced(List<Expr> exprs, Map<String, Expr> candidates)
    {
        if (candidates.isEmpty())
        {
            return Map.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Expr e : exprs)
        {
            collectRefs(e, candidates.keySet(), seen);
        }
        Map<String, Expr> out = new LinkedHashMap<>();
        for (Map.Entry<String, Expr> e : candidates.entrySet())
        {
            if (seen.contains(e.getKey()))
            {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }


    /**
     * Rewrites {@code expr}, replacing each {@code $}-id reference in {@code calls} with its
     * {@code split_by(<col>, "<delim>")} call. Returns {@code expr} unchanged when the map is
     * empty.
     *
     * @param expr
     *            the expression to rewrite
     * @param calls
     *            id &rarr; call subset (typically from {@link #referenced})
     * @return the rewritten expression
     */
    public static Expr rewrite(Expr expr, Map<String, Expr> calls)
    {
        if (calls.isEmpty())
        {
            return expr;
        }
        return replace(expr, calls);
    }


    private static Expr replace(Expr e, Map<String, Expr> calls)
    {
        return switch (e)
        {
        case Expr.Ref r -> calls.getOrDefault(r.name(), r);
        case Expr.Binary b -> new Expr.Binary(b.op(), replace(b.left(), calls),
                replace(b.right(), calls));
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


    private static void collectRefs(Expr e, Set<String> ids, Set<String> out)
    {
        switch (e)
        {
        case Expr.Ref r ->
        {
            if (ids.contains(r.name()))
            {
                out.add(r.name());
            }
        }
        case Expr.Binary b ->
        {
            collectRefs(b.left(), ids, out);
            collectRefs(b.right(), ids, out);
        }
        case Expr.And a -> a.parts().forEach(p -> collectRefs(p, ids, out));
        case Expr.Or o -> o.parts().forEach(p -> collectRefs(p, ids, out));
        case Expr.Not n -> collectRefs(n.inner(), ids, out);
        case Expr.Call c ->
        {
            c.args().forEach(a -> collectRefs(a, ids, out));
            c.kwargs().values().forEach(v -> collectRefs(v, ids, out));
        }
        default ->
        {
            // Lit — no nested refs.
        }
        }
    }

}
