package net.cumba.corej.core.model;

import java.util.List;
import java.util.Objects;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.Domain;
import org.jspecify.annotations.Nullable;

/**
 * A {@code Bindings:} entry whose expression is <b>not</b> a single top-level {@link OperationType}
 * call — a registry-function call, a column or dotted reference, arithmetic, a boolean expression,
 * a nested operation call or a reference to an earlier binding ({@code PLAN-binding-expressions}
 * §2). It is compiled exactly like the {@code Check} and its value is visible everywhere a binding
 * is visible (SPEC §3.2).
 *
 * <p>
 * <b>Runtime-only.</b> Materialised from the authored {@link Binding} by
 * {@code RulePackageLoader.normalizeOperations}, never serialised: the authoring surface stays
 * {@code name:} + {@code expression:}.
 * </p>
 *
 * <p>
 * <b>Why {@link #predecessors} and not an index.</b> Operation bindings live in
 * {@code Rule.getOperations()}, which several passes rewrite after load — the two load-time
 * inliners <em>drop</em> entries, the specialiser and the expanders copy the list. An absolute
 * authored index would shift under a drop and put a compiled binding after an operation authored
 * later than it (which the forward-reference check would then misread). The names of the bindings
 * authored before this one survive every one of those rewrites, so {@link Rule#bindingOrder()}
 * places the binding directly after the last of them still present.
 * </p>
 *
 * @param name
 *            the {@code $}-name the binding defines
 * @param expression
 *            the parsed (and, once {@code installNativeExpr} ran, metadata-canonicalised)
 *            expression
 * @param predecessors
 *            the names of every binding authored before this one, in authored order
 * @param domain
 *            the binding's derived evaluation domain (SPEC §3.2 — a binding's level is derived,
 *            never declared), installed by {@code RulePackageLoader.installNativeExpr};
 *            {@code null} until then
 */
public record CompiledBinding(String name, Expr expression, List<String> predecessors,
        @Nullable Domain domain) implements BoundBinding
{

    /**
     * Defensive copy of {@link #predecessors}; {@code name} and {@code expression} are required.
     */
    public CompiledBinding
    {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(expression, "expression");
        predecessors = List.copyOf(predecessors);
    }


    /**
     * This binding with another expression — the specialiser's {@code --} resolution and the
     * expanders' token / name rewrites.
     *
     * @param aExpression
     *            the rewritten expression
     * @return the copy
     */
    public CompiledBinding withExpression(Expr aExpression)
    {
        return new CompiledBinding(name, aExpression, predecessors, domain);
    }


    /**
     * This binding with its derived domain installed.
     *
     * @param aDomain
     *            the derived evaluation domain
     * @return the copy
     */
    public CompiledBinding withDomain(Domain aDomain)
    {
        return new CompiledBinding(name, expression, predecessors, aDomain);
    }


    /**
     * Whether the binding's value varies per row or per variable — {@code true} when its derived
     * domain demands a cursor, and conservatively {@code true} while no domain is installed. An
     * operation (dataset-level by construction) may read only a binding for which this is
     * {@code false} (§5.0's hand-over contract).
     *
     * @return whether the value needs a cursor
     */
    public boolean needsCursor()
    {
        return domain == null || !domain.isBroadcast();
    }

}
