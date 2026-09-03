package net.cumba.corej.core.expr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.expr.ast.Expr;

/**
 * The canonical spelling of a negated presence fact: {@code not ds_exists(X)} and
 * {@code not var_exists(X)}, never the {@code ds_not_exists(X)} / {@code var_not_exists(X)} twins.
 * Both spellings compile to the same fact; the shipped corpus carries only the {@code not …} form
 * (zero negative-spelling calls in {@code rules/}), and until phase 1 of
 * {@code PLAN-leaf-scope-domain-inference.md} that invariant was a by-product of the assembler's
 * lower/raise dance (the negative leaf lowered to the generic {@code not_exists} and re-raised as
 * {@code Not(ds_exists)}). The dance is gone; this rewrite keeps the invariant explicit, in the
 * writer's canonical rendering, where the corpus drift test polices it.
 */
public final class PresenceSpelling
{

    private PresenceSpelling()
    {
    }

    private static final Map<String, String> NEGATIVE_TO_POSITIVE = Map.of("ds_not_exists",
            "ds_exists", "var_not_exists", "var_exists");

    /**
     * Rewrites every {@code ds_not_exists(X)} / {@code var_not_exists(X)} call in {@code e} to
     * {@code not ds_exists(X)} / {@code not var_exists(X)}; every other node is rebuilt unchanged.
     */
    public static Expr canonical(Expr e)
    {
        return switch (e)
        {
        case Expr.Call c ->
        {
            String positive = NEGATIVE_TO_POSITIVE.get(c.name());
            List<Expr> args = mapAll(c.args());
            Map<String, Expr> kwargs = new LinkedHashMap<>();
            c.kwargs().forEach((k, v) -> kwargs.put(k, canonical(v)));
            Expr call = new Expr.Call(positive != null ? positive : c.name(), args, kwargs);
            yield positive != null ? new Expr.Not(call) : call;
        }
        case Expr.And a -> new Expr.And(mapAll(a.parts()));
        case Expr.Or o -> new Expr.Or(mapAll(o.parts()));
        case Expr.Not n -> new Expr.Not(canonical(n.inner()));
        case Expr.Binary b -> new Expr.Binary(b.op(), canonical(b.left()), canonical(b.right()));
        case Expr.Lit lit ->
        {
            if (lit.kind() == Expr.LitKind.LIST)
            {
                @SuppressWarnings("unchecked")
                List<Expr> items = (List<Expr>) lit.value();
                yield new Expr.Lit(Expr.LitKind.LIST, mapAll(items));
            }
            yield lit;
        }
        case Expr.Ref r -> r;
        };
    }


    private static List<Expr> mapAll(List<Expr> parts)
    {
        List<Expr> out = new ArrayList<>(parts.size());
        for (Expr p : parts)
        {
            out.add(canonical(p));
        }
        return out;
    }
}
