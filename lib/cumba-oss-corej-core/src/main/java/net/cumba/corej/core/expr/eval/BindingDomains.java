package net.cumba.corej.core.expr.eval;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.model.CompiledBinding;
import net.cumba.corej.core.model.Rule;

/**
 * The evaluation domain each {@code $}-binding of a rule contributes to {@link DomainScan} — a
 * compiled binding's <b>derived</b> domain ({@code CompiledBinding.domain()}), or, before
 * {@code installNativeExpr} installed it, the domain inferred from its expression with the domains
 * of the bindings authored before it ({@code PLAN-binding-expressions} R9), so a record-level
 * binding is never classified dataset-constant (which would fold the rule's level wrongly).
 *
 * <p>
 * Runbook W8 ({@code PLAN-retire-operation-surface}): the renamed {@code BindingDomains}, which
 * classified a declared operation's runtime result kind (per row / per variable / scalar) beside
 * the compiled bindings' domains. No operation exists, so the kind is gone and only the domain is
 * left.
 * </p>
 */
@FunctionalInterface
public interface BindingDomains
{

    /** Every reference is dataset-constant — for expressions that carry no {@code $}-reference. */
    BindingDomains NONE = _ -> Domain.DATASET;

    /**
     * The evaluation domain a {@code $}-reference contributes.
     *
     * @param ref
     *            the reference as it appears in the expression (with its leading {@code $})
     * @return the domain; {@link Domain#DATASET} for a reference no binding defines (the dangling
     *         operand load guard rejects that rule separately)
     */
    Domain domainOf(String ref);


    /**
     * The domains of {@code rule}'s compiled bindings, in authored order.
     *
     * @param rule
     *            the rule
     * @return the domains
     */
    static BindingDomains forRule(Rule rule)
    {
        List<CompiledBinding> order = rule.bindingOrder();
        if (order.isEmpty())
        {
            return NONE;
        }
        Map<String, Domain> domains = new HashMap<>();
        BindingDomains sofar = ref -> domains.getOrDefault(ref, Domain.DATASET);
        for (CompiledBinding compiled : order)
        {
            Domain domain = compiled.domain() != null ? compiled.domain()
                    : DomainScan.infer(compiled.expression(), sofar);
            domains.put(compiled.name(), domain);
        }
        return sofar;
    }
}
