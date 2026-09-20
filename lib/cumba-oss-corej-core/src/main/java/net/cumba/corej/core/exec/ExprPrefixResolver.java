package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.OperandClassifier;
import net.cumba.corej.core.expr.ast.Expr;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the SDTM {@code --} domain-prefix wildcard inside a native {@link Expr} tree at bind
 * time — the expression-side sibling of {@link CheckConditionTransformer}, applying the
 * <em>same</em> two-prefix text policy ({@link CheckConditionTransformer#resolveTextWildcard}):
 * variable-name positions take the variable wildcard prefix (EC-36 — {@code ""} for SUPP/SQ, the
 * 2-character parent suffix for AP), the dataset half of a dot-qualified reference keeps the full
 * CDISC domain code ({@code SUPP--.QVAL} on an {@code APMH} primary is {@code SUPPAPMH.QVAL}).
 *
 * <p>
 * Called from {@link RuleSpecialiser} (D77); public so tests and embedders can specialise a raw
 * expression directly. After this pass no {@code --} reference remains in the expression, so the
 * compiled program is concrete for its domain and {@code ExprCompiler.resolveDomainPrefix} can
 * <em>assert</em> resolvedness instead of resolving (D77b). Sharing across domains is intentionally
 * given up — see D77g for why the blast radius is small.
 * </p>
 *
 * <p>
 * <b>What is deliberately NOT rewritten:</b>
 * </p>
 * <ul>
 * <li><b>{@code ${VAR[:fmt]}} operand substitution and the dot-qualified {@code RELREC.**} form</b>
 * (D77d) — both derive a column name from a <em>cell value</em>, so they are per-row column
 * selection, not templates. Any operand containing a {@code ${...}} placeholder is left verbatim;
 * the {@code **} column half of a dotted reference is preserved by the shared text policy.</li>
 * <li><b>The name operand of {@code variable_count} / {@code variable_value_count}</b> (D92a) —
 * both deliberately fold across the dataset inventory using the pre-resolution template ({@code
 * --LNKGRP} re-resolves per iterated dataset: AE&rarr;AELNKGRP, CM&rarr;CMLNKGRP, …). Expanding it
 * here would leave CDISC-CG0022 / CG0024 counting only the current domain's column — inert, with
 * every gate green. The inline evaluation path ({@code
 * ExprCompiler.inlineOperationResult}) resolves the operand per iterated dataset at run time,
 * exactly as the Operations-block path does via {@code Operation.originalName}.</li>
 * <li><b>{@code name_pattern=}</b> (D92b) — a string-literal variable-set selector matched by
 * regex, never a wildcard.</li>
 * <li><b>Non-STRING literals</b> (numbers, booleans, regexes) and any string literal that does not
 * match the shared text policy's name shapes — a data literal such as {@code "DOSE NOT CHANGED--SEE
 * CRF"} has no leading {@code --} and is untouched.</li>
 * <li><b>Keyword-argument <em>keys</em></b> (an inline operation's {@code filter=} column keys) —
 * they are carried into the {@link net.cumba.corej.core.model.Operation}'s filter map and resolved
 * by {@code OperationExecutor.resolvePrefixes} on the operation path.</li>
 * </ul>
 */
public final class ExprPrefixResolver
{

    /** D92a: operations that fold their name operand across the dataset inventory. */
    private static final java.util.Set<String> INVENTORY_FOLD_OPERATIONS = java.util.Set
            .of("variable_count", "variable_value_count");

    private ExprPrefixResolver()
    {
    }


    /**
     * Returns {@code e} with every resolvable {@code --} reference substituted, or the very same
     * instance when nothing needed resolving (identity is preserved bottom-up, so callers can use
     * {@code ==} as the change test).
     */
    public static Expr resolve(Expr e, String variablePrefix, String datasetPrefix)
    {
        return switch (e)
        {
        case Expr.Lit lit -> resolveLit(lit, variablePrefix, datasetPrefix);
        case Expr.Ref ref -> resolveRef(ref, variablePrefix, datasetPrefix);
        case Expr.Call c -> resolveCall(c, variablePrefix, datasetPrefix);
        case Expr.Binary b ->
        {
            Expr left = resolve(b.left(), variablePrefix, datasetPrefix);
            Expr right = resolve(b.right(), variablePrefix, datasetPrefix);
            yield left == b.left() && right == b.right() ? b : new Expr.Binary(b.op(), left, right);
        }
        case Expr.And a ->
        {
            List<Expr> parts = resolveParts(a.parts(), variablePrefix, datasetPrefix);
            yield parts == null ? a : new Expr.And(parts);
        }
        case Expr.Or o ->
        {
            List<Expr> parts = resolveParts(o.parts(), variablePrefix, datasetPrefix);
            yield parts == null ? o : new Expr.Or(parts);
        }
        case Expr.Not n ->
        {
            Expr inner = resolve(n.inner(), variablePrefix, datasetPrefix);
            yield inner == n.inner() ? n : new Expr.Not(inner);
        }
        };
    }


    /** The rewritten parts, or {@code null} when every element came back identical. */
    private static @Nullable List<Expr> resolveParts(List<Expr> parts, String variablePrefix,
            String datasetPrefix)
    {
        List<Expr> out = null;
        for (int i = 0; i < parts.size(); i++)
        {
            Expr resolved = resolve(parts.get(i), variablePrefix, datasetPrefix);
            if (out == null && resolved != parts.get(i))
            {
                out = new ArrayList<>(parts.subList(0, i));
            }
            if (out != null)
            {
                out.add(resolved);
            }
        }
        return out;
    }


    private static Expr resolveRef(Expr.Ref ref, String variablePrefix, String datasetPrefix)
    {
        String resolved = resolveName(ref.name(), variablePrefix, datasetPrefix);
        if (resolved == null)
        {
            return ref;
        }
        return new Expr.Ref(resolved, classifyOrKeep(resolved, ref));
    }


    /**
     * Re-classifies the resolved name so a substituted {@code --STDTC} becomes a plain
     * {@code COLUMN} ref (and {@code SUPP--.QVAL} a {@code DOTTED_REF}) rather than keeping the
     * stale {@code WILDCARD_COLUMN} kind. Falls back to the source ref's kind when the resolved
     * spelling does not classify (defensive; resolved names are ordinary upper-case columns).
     */
    private static net.cumba.corej.core.expr.OperandKind classifyOrKeep(String name, Expr.Ref ref)
    {
        try
        {
            return OperandClassifier.classify(name, -1);
        }
        catch (ExpressionException _)
        {
            return ref.kind();
        }
    }


    private static Expr resolveLit(Expr.Lit lit, String variablePrefix, String datasetPrefix)
    {
        if (lit.kind() == Expr.LitKind.STRING && lit.value() instanceof String s)
        {
            String resolved = resolveName(s, variablePrefix, datasetPrefix);
            return resolved == null ? lit : new Expr.Lit(Expr.LitKind.STRING, resolved);
        }
        if (lit.kind() == Expr.LitKind.LIST && lit.value() instanceof List<?> members)
        {
            @SuppressWarnings("unchecked")
            List<Expr> exprs = (List<Expr>) members;
            List<Expr> resolved = resolveParts(exprs, variablePrefix, datasetPrefix);
            return resolved == null ? lit : new Expr.Lit(Expr.LitKind.LIST, resolved);
        }
        return lit;
    }


    private static Expr resolveCall(Expr.Call c, String variablePrefix, String datasetPrefix)
    {
        boolean inventoryFold = INVENTORY_FOLD_OPERATIONS.contains(c.name());
        List<Expr> args = null;
        for (int i = 0; i < c.args().size(); i++)
        {
            Expr arg = c.args().get(i);
            // D92a: the name operand (first positional argument) keeps its template.
            Expr resolved = inventoryFold && i == 0 ? arg
                    : resolve(arg, variablePrefix, datasetPrefix);
            if (args == null && resolved != arg)
            {
                args = new ArrayList<>(c.args().subList(0, i));
            }
            if (args != null)
            {
                args.add(resolved);
            }
        }
        Map<String, Expr> kwargs = null;
        for (Map.Entry<String, Expr> kw : c.kwargs().entrySet())
        {
            Expr value = kw.getValue();
            Expr resolved;
            if ("name_pattern".equals(kw.getKey()) || (inventoryFold && "name".equals(kw.getKey())))
            {
                // D92b: name_pattern= is a regex variable-set selector, never a wildcard.
                // D92a: the keyword spelling of the fold template keeps it too.
                resolved = value;
            }
            else
            {
                resolved = resolve(value, variablePrefix, datasetPrefix);
            }
            if (kwargs == null && resolved != value)
            {
                kwargs = new LinkedHashMap<>();
                for (Map.Entry<String, Expr> prior : c.kwargs().entrySet())
                {
                    if (prior.getKey().equals(kw.getKey()))
                    {
                        break;
                    }
                    kwargs.put(prior.getKey(), prior.getValue());
                }
            }
            if (kwargs != null)
            {
                kwargs.put(kw.getKey(), resolved);
            }
        }
        if (args == null && kwargs == null)
        {
            return c;
        }
        return new Expr.Call(c.name(), args != null ? args : c.args(),
                kwargs != null ? kwargs : c.kwargs());
    }


    /**
     * The shared text policy applied to one operand spelling: the resolved name, or {@code null}
     * when nothing changes. {@code ${...}} operands are per-row column selection (D77d) and are
     * never rewritten, whatever else the spelling contains.
     */
    private static @Nullable String resolveName(String name, String variablePrefix,
            String datasetPrefix)
    {
        if (name.contains("${"))
        {
            return null;
        }
        return CheckConditionTransformer.resolveTextWildcard(name, variablePrefix, datasetPrefix);
    }

}
