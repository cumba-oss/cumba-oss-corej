package net.cumba.corej.core.expr.eval;

import java.util.List;
import java.util.Set;

import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.ExpressionPrinter;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;

/**
 * Static support for the per-dataset cross-rule expression-result cache
 * ({@code PLAN-dataset-expression-cache.md}): the compile-time <b>purity classifier</b> (§3.4) that
 * decides whether an {@link Expr} subtree is a cache <em>candidate</em>, and the <b>cache key</b>
 * (§3.2) derived from the live table instance, the canonical expression text, and the domain
 * prefix.
 *
 * <p>
 * A leaf is a candidate iff its subtree reads <b>only the table's own column data</b> — column
 * references, literals, and the {@link #PURE_FUNCTIONS} allow-list of {@code BuiltinFunctions} that
 * read only their argument {@code Vector}s, combined by comparison / arithmetic / regex /
 * membership-against-a-literal-list / boolean combinators. Everything else (the current-variable
 * cursor {@code value()}/{@code varname()}, {@code $}-operation refs, dotted cross-dataset refs,
 * {@code var_*}/{@code ds_*}/{@code library_*}/{@code define_*} metadata accessors, engine
 * builtins, {@code exists}, {@code record_count}, the type-tag forms
 * {@code str}/{@code num}/{@code date}…) is non-cacheable by construction: it is simply absent from
 * the allow-list.
 * </p>
 *
 * <p>
 * <b>Purity is necessary, not sufficient.</b> This static pass only flags candidates; whether a
 * candidate is actually cached is decided at evaluation time by the §3.6 decline gate (a
 * {@code COLUMN} ref can resolve to a joined dataset or be shadowed by a context variable — runtime
 * properties this pass cannot see). That gate, the cache object, and the compiler wiring arrive in
 * later phases; this class is the pure (side-effect-free) classifier and key.
 * </p>
 */
public final class DatasetExpressionCache
{

    /**
     * The exact allow-list of native functions that read <b>only their argument {@code Vector}s</b>
     * (verified by auditing every registered {@code BuiltinFunctions} {@code EvalFunction} and the
     * {@code Primitives} helpers it delegates to). Every arity overload of each name is pure, so a
     * name-keyed set is sound. 19 VALUE + 25 BOOLEAN. This is an <b>allow-list</b>: any function
     * not listed — a future impure builtin, or a structural non-registry form such as {@code str} /
     * {@code num} / {@code date_part} / {@code exists} / a metadata accessor — is non-cacheable by
     * default.
     */
    static final Set<String> PURE_FUNCTIONS = Set.of(
            // VALUE (19)
            "lower", "lowcase", "upper", "upcase", "len", "length", "abs", "round", "floor", "ceil",
            "trim", "year", "month", "day", "concat", "coalesce", "substring", "prefix", "suffix",
            // BOOLEAN (23)
            "between", "empty", "is_missing", "non_empty", "present", "is_present", "contains",
            "does_not_contain", "starts_with", "ends_with", "equalsIgnoreCase", "prefix_matches",
            "suffix_matches", "imatches", "is_integer", "is_not_integer", "invalid_duration",
            "is_valid_duration", "is_valid_date", "is_complete_date", "is_partial_date",
            "is_incomplete_date", "invalid_date", "is_complete_date_part",
            "is_not_complete_date_part");

    private DatasetExpressionCache()
    {
    }


    /**
     * Whether {@code e} is a cache candidate — its whole subtree reads only the table's own columns
     * (§3.4). See the class javadoc for the allow-list and the "necessary, not sufficient" caveat.
     *
     * @param e
     *            the expression subtree
     * @return {@code true} iff every leaf of {@code e} is a column ref, a literal, or a pure
     *         allow-listed call, combined only by pure operators
     */
    public static boolean isPure(Expr e)
    {
        return switch (e)
        {
        case Expr.Ref r -> r.kind() == OperandKind.COLUMN
                || r.kind() == OperandKind.WILDCARD_COLUMN;
        case Expr.Lit lit -> isPureLiteral(lit);
        case Expr.Call c -> PURE_FUNCTIONS.contains(c.name()) && c.kwargs().isEmpty()
                && c.args().stream().allMatch(DatasetExpressionCache::isPure);
        // IN / NOT_IN are pure only against a literal-list RHS — a $-op set or ${*} wildcard RHS is
        // a non-pure operand resolved per row (read beyond the table's own columns).
        case Expr.Binary b when b.op() == Expr.BinOp.IN
                || b.op() == Expr.BinOp.NOT_IN -> isPure(b.left())
                        && b.right() instanceof Expr.Lit l && l.kind() == Expr.LitKind.LIST
                        && isPure(b.right());
        case Expr.Binary b -> isPure(b.left()) && isPure(b.right());
        case Expr.And a -> a.parts().stream().allMatch(DatasetExpressionCache::isPure);
        case Expr.Or o -> o.parts().stream().allMatch(DatasetExpressionCache::isPure);
        case Expr.Not n -> isPure(n.inner());
        };
    }


    /** A scalar literal is pure; a list literal is pure iff every element is pure. */
    private static boolean isPureLiteral(Expr.Lit lit)
    {
        if (lit.kind() != Expr.LitKind.LIST)
        {
            return true;
        }
        @SuppressWarnings("unchecked")
        List<Expr> items = (List<Expr>) lit.value();
        return items.stream().allMatch(DatasetExpressionCache::isPure);
    }


    /**
     * Derives the cache key (§3.2) for a candidate leaf: the live table <b>instance identity</b>,
     * the canonical expression text ({@link ExpressionPrinter#print(Expr)}), and the domain prefix.
     * Identity keying is safe by construction — a non-merging rule sees the shared base table, a
     * merging / RELREC-expanded rule sees its own instance, so distinct merge states never collide.
     *
     * @param table
     *            the evaluation table instance ({@code run.ctx().getTable()})
     * @param leaf
     *            the candidate expression subtree
     * @param domainPrefix
     *            the 2-character domain prefix for {@code --} substitution, or {@code null}
     * @return the cache key
     */
    public static Key keyOf(IDataTable table, Expr leaf, @Nullable String domainPrefix)
    {
        return new Key(new IdentityKey(table), ExpressionPrinter.print(leaf), domainPrefix);
    }


    /**
     * Key from an already-computed canonical string (the compiler prints the leaf once at compile
     * time and reuses the string on every evaluation, avoiding a per-eval re-print).
     *
     * @param table
     *            the evaluation table instance
     * @param exprCanonical
     *            the canonical expression text ({@link ExpressionPrinter#print(Expr)})
     * @param domainPrefix
     *            the domain prefix, or {@code null}
     * @return the cache key
     */
    public static Key keyOf(IDataTable table, String exprCanonical, @Nullable String domainPrefix)
    {
        return new Key(new IdentityKey(table), exprCanonical, domainPrefix);
    }


    /**
     * The eval-time decline gate (§3.6): whether a statically-pure candidate (see {@link #isPure})
     * may actually be cached against {@code ctx}. Purity is necessary but not sufficient — a
     * {@code COLUMN} ref can resolve to a joined dataset or be shadowed by a context variable, both
     * runtime properties the static classifier cannot see. Cache only when <b>all</b> hold:
     * <ol>
     * <li><b>No joins</b> — {@code ctx.getJoinedDatasets().isEmpty()}; otherwise an absent-locally
     * column ref resolves through the join fallback.</li>
     * <li><b>The {@code {ROW}} evaluation domain</b> — not one with a VAR cursor, whose
     * per-variable loop binds the variable name into the context and re-evaluates per variable (so
     * a leaf's result need not even be constant within one rule), and not the broadcast {@code {}}
     * domain. The test: decline a {@code null} domain or any other than {@code Domain.ROW}.</li>
     * <li><b>Every column ref resolves to a local, unshadowed column</b> — each {@code COLUMN} /
     * {@code --}-prefix {@code WILDCARD_COLUMN} ref (after {@code --} substitution) is present in
     * {@code ctx.getTable()} and is not a key in {@code ctx.getVariables()}. This also makes the
     * leaf independent of the per-expression ident-fallback flag (which only affects an
     * unresolvable value-position identifier), so that flag need not enter the key.</li>
     * <li><b>Only {@code --}-prefix wildcards</b> — a {@code *} / {@code **} / {@code ${…}}
     * wildcard reads driver cells / joins and is declined.</li>
     * </ol>
     * Intended to be called only on a leaf that already passed {@link #isPure}.
     *
     * @param leaf
     *            the statically-pure candidate
     * @param ctx
     *            the evaluation context
     * @return {@code true} iff the leaf is safe to cache against {@code ctx}
     */
    public static boolean cacheableAt(Expr leaf, EvaluationContext ctx)
    {
        if (!ctx.getJoinedDatasets().isEmpty())
        {
            return false;
        }
        // Allow-list of one domain (leaf-scope phase 7 re-key of the former RECORD_DATA
        // allow-list): only a pure {ROW} evaluation neither binds a variable cursor nor
        // injects per-variable keys. Every domain with a VAR cursor runs the per-variable loop,
        // where caching a leaf could collapse per-variable passes or shadow a column.
        Domain domain = ctx.getEvaluationDomain();
        if (domain == null || !domain.equals(Domain.ROW))
        {
            return false;
        }
        return refsResolveLocally(leaf, ctx);
    }


    /**
     * Whether every column ref in {@code e} resolves to a local, unshadowed column (§3.6 #2-#4).
     */
    private static boolean refsResolveLocally(Expr e, EvaluationContext ctx)
    {
        return switch (e)
        {
        case Expr.Ref r -> refResolvesLocally(r, ctx);
        case Expr.Lit lit -> literalRefsResolveLocally(lit, ctx);
        case Expr.Call c -> c.args().stream().allMatch(a -> refsResolveLocally(a, ctx));
        case Expr.Binary b -> refsResolveLocally(b.left(), ctx)
                && refsResolveLocally(b.right(), ctx);
        case Expr.And a -> a.parts().stream().allMatch(p -> refsResolveLocally(p, ctx));
        case Expr.Or o -> o.parts().stream().allMatch(p -> refsResolveLocally(p, ctx));
        case Expr.Not n -> refsResolveLocally(n.inner(), ctx);
        };
    }


    private static boolean refResolvesLocally(Expr.Ref r, EvaluationContext ctx)
    {
        OperandKind kind = r.kind();
        if (kind != OperandKind.COLUMN && kind != OperandKind.WILDCARD_COLUMN)
        {
            return false;
        }
        String name = r.name();
        if (kind == OperandKind.WILDCARD_COLUMN)
        {
            // §3.6 #4: only a `--`-prefix wildcard is cacheable (others read driver cells / joins).
            if (!name.startsWith("--"))
            {
                return false;
            }
            name = resolveDomainPrefix(name,
                    ctx.getVariableWildcardPrefix() != null ? ctx.getVariableWildcardPrefix()
                            : ctx.getDomainPrefix());
        }
        // §3.6 #3 (fine): a name shadowed by a context variable is not a pure table read.
        if (ctx.getVariables().containsKey(name))
        {
            return false;
        }
        // §3.6 #2: must resolve to a column of the local table.
        return ctx.getTable().getMetaData().getColumnIndex(name) >= 0;
    }


    private static boolean literalRefsResolveLocally(Expr.Lit lit, EvaluationContext ctx)
    {
        if (lit.kind() != Expr.LitKind.LIST)
        {
            return true;
        }
        @SuppressWarnings("unchecked")
        List<Expr> items = (List<Expr>) lit.value();
        return items.stream().allMatch(i -> refsResolveLocally(i, ctx));
    }


    /**
     * Resolves a {@code --}-prefix wildcard column name against the <em>variable</em> wildcard
     * prefix, mirroring {@code ExprCompiler.resolveDomainPrefix}. EC-36: substitution is
     * unconditional once a prefix exists — the old {@code length() == 2} gate left the name raw for
     * every AP dataset (4-character code) and every SUPP dataset ({@code ""}), so this purity probe
     * asked about a different column than the evaluator would read and declined to cache it.
     */
    private static String resolveDomainPrefix(String name, @Nullable String variablePrefix)
    {
        if (name.startsWith("--") && variablePrefix != null)
        {
            return variablePrefix + name.substring(2);
        }
        return name;
    }

    /**
     * The cache key: table instance identity + canonical expression text + domain prefix. Two rules
     * that authored the same pure leaf against the same table instance and prefix collide on one
     * key (the intended cross-rule sharing).
     */
    public record Key(IdentityKey table, String exprCanonical, @Nullable String domainPrefix)
    {
    }


    /**
     * Wraps an {@link IDataTable} so the cache key compares it by <b>reference identity</b>, not by
     * the table's own (content-based) {@code equals}/{@code hashCode}. Identity is the correct and
     * cheap notion here: the same object reference is the same data, and a merge produces a
     * distinct instance (§2).
     */
    public static final class IdentityKey
    {

        private final IDataTable table;

        IdentityKey(IDataTable table)
        {
            this.table = table;
        }


        /** The wrapped table instance. */
        public IDataTable table()
        {
            return table;
        }


        @Override
        public boolean equals(@Nullable Object o)
        {
            return o instanceof IdentityKey other && other.table == this.table;
        }


        @Override
        public int hashCode()
        {
            return System.identityHashCode(table);
        }


        @Override
        public String toString()
        {
            return "IdentityKey@" + Integer.toHexString(System.identityHashCode(table));
        }
    }

}
