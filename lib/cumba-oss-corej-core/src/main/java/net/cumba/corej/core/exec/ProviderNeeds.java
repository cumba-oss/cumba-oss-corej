package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.ArgumentBinder;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.expr.eval.FunctionRegistry;
import net.cumba.corej.core.expr.eval.ProviderNeed;
import net.cumba.corej.core.model.CompiledBinding;
import net.cumba.corej.core.model.Rule;
import org.jspecify.annotations.Nullable;

/**
 * ⭐ <b>The one reader of provider needs</b> ({@code PLAN-binding-expressions} §5.2, "provider
 * capability"): which run-level providers a rule, a binding or a single call needs before it can
 * answer.
 *
 * <p>
 * It reads the <b>one key</b> a need is declared under — a registry function's
 * {@linkplain FunctionDescriptor#provider() provider capability} ({@link ProviderNeed}), for a
 * function call anywhere, in a compiled binding or inline. (The second key, an operation type, went
 * with the Operation carrier in runbook W8; it had needed nothing since wave 4b, when the last
 * provider-reading operation became a registry function.)
 * </p>
 * <p>
 * It walks every surface a call can sit on: compiled binding expressions and (for {@link #ofExpr})
 * any expression the caller hands it — the Check and the Precondition. So a call in a binding and
 * the same call inline in the Check are gated alike, and a ported callable kept its gates the
 * moment its operation type was deleted.
 * </p>
 *
 * <p>
 * Readers (wave 0): {@code RuleRunner}'s no-provider SKIP arms ({@link #ofBindings}),
 * {@code ProviderRequirements} surfaces 1 and 3, {@code RulePackageLoader.gateTermsForCall}
 * ({@link #ofCall}) and {@code StudyValidationService.requiredDictionaryTypes}. Wave 1 adds the
 * {@link ProviderNeed.Kind#DICTIONARY} capability on its ported dictionary functions and routes its
 * remaining dictionary sites here; any dictionary-only view it adds (its D-W1-3) may only ever be a
 * view of this class, never a sibling.
 * </p>
 *
 * @param library
 *            whether the CDISC Library is needed
 * @param define
 *            whether the sponsor Define-XML is needed
 * @param dictionary
 *            whether any external dictionary is needed (typed or not)
 * @param dictionaryTypes
 *            the statically-named dictionary types, in first-seen order (a typeless dictionary call
 *            contributes {@link #dictionary} only)
 */
public record ProviderNeeds(boolean library, boolean define, boolean dictionary,
        SequencedSet<String> dictionaryTypes)
{

    /** No provider needed. */
    public static final ProviderNeeds NONE = new ProviderNeeds(false, false, false,
            new LinkedHashSet<>());

    /** Defensive copy of the type set. */
    public ProviderNeeds
    {
        dictionaryTypes = java.util.Collections
                .unmodifiableSequencedSet(new LinkedHashSet<>(dictionaryTypes));
    }


    /**
     * Whether nothing is needed.
     *
     * @return whether nothing is needed
     */
    public boolean isEmpty()
    {
        return !library && !define && !dictionary;
    }


    /**
     * The union of two needs; dictionary types keep first-seen order.
     *
     * @param other
     *            the other needs
     * @return the union
     */
    public ProviderNeeds union(ProviderNeeds other)
    {
        if (other.isEmpty())
        {
            return this;
        }
        if (isEmpty())
        {
            return other;
        }
        SequencedSet<String> types = new LinkedHashSet<>(dictionaryTypes);
        types.addAll(other.dictionaryTypes);
        return new ProviderNeeds(library || other.library, define || other.define,
                dictionary || other.dictionary, types);
    }


    /**
     * The needs of a rule's <b>bindings</b> — every compiled binding expression, in authored order.
     * This is the surface {@code RuleRunner}'s eager SKIP arms gate: a {@code $}-bound call gets no
     * injected Precondition gate, so the runner itself must SKIP a rule whose binding needs an
     * absent provider.
     *
     * @param rule
     *            the rule
     * @return the needs
     */
    public static ProviderNeeds ofBindings(Rule rule)
    {
        ProviderNeeds needs = NONE;
        for (CompiledBinding compiled : rule.bindingOrder())
        {
            needs = needs.union(ofExpr(compiled.expression()));
        }
        return needs;
    }


    /**
     * The needs of every call in an expression (a compiled binding, a Check level, a Precondition),
     * nested calls included.
     *
     * @param expr
     *            the expression
     * @return the needs
     */
    public static ProviderNeeds ofExpr(Expr expr)
    {
        List<ProviderNeeds> found = new ArrayList<>();
        collect(expr, found);
        ProviderNeeds needs = NONE;
        for (ProviderNeeds n : found)
        {
            needs = needs.union(n);
        }
        return needs;
    }


    private static void collect(Expr e, List<ProviderNeeds> out)
    {
        switch (e)
        {
        case Expr.And a -> a.parts().forEach(p -> collect(p, out));
        case Expr.Or o -> o.parts().forEach(p -> collect(p, out));
        case Expr.Not n -> collect(n.inner(), out);
        case Expr.Binary b ->
        {
            collect(b.left(), out);
            collect(b.right(), out);
        }
        case Expr.Call c ->
        {
            ProviderNeeds n = ofCall(c);
            if (!n.isEmpty())
            {
                out.add(n);
            }
            c.args().forEach(a -> collect(a, out));
            c.kwargs().values().forEach(a -> collect(a, out));
        }
        case Expr.Lit lit ->
        {
            if (lit.kind() == Expr.LitKind.LIST)
            {
                @SuppressWarnings("unchecked")
                List<Expr> items = (List<Expr>) lit.value();
                items.forEach(item -> collect(item, out));
            }
        }
        case Expr.Ref _ ->
        {
            // A $-reference is a binding, judged on its own declaration (ofBindings); a column or
            // builtin reference needs no provider on THIS surface — the metadata-operand surface
            // (library_* / define_* operands, var_*("LIBRARY") accessors) is MetadataExprScan's.
        }
        }
    }


    /**
     * The needs of ONE call, not its arguments: a registry function is read by its
     * {@linkplain FunctionDescriptor#provider() provider capability}. For a
     * {@link ProviderNeed.Kind#DICTIONARY} capability the type is the static string literal bound
     * to the declared type parameter; a non-literal leaves the type unnamed (wave 1 makes that a
     * load error).
     *
     * @param call
     *            the call
     * @return the needs; {@link #NONE} for a call that needs nothing or is not well formed
     */
    public static ProviderNeeds ofCall(Expr.Call call)
    {
        FunctionDescriptor descriptor = FunctionRegistry.descriptor(call.name());
        if (descriptor == null)
        {
            return NONE;
        }
        ProviderNeed need = descriptor.provider();
        if (need == null)
        {
            return NONE;
        }
        return switch (need.kind())
        {
        case LIBRARY -> new ProviderNeeds(true, false, false, new LinkedHashSet<>());
        case DEFINE -> new ProviderNeeds(false, true, false, new LinkedHashSet<>());
        case DICTIONARY -> dictionaryNeed(call, descriptor, need);
        };
    }


    /**
     * ⭐ Wave 1 (D-W1-3 (iv)/(v)) — the <b>typeless dictionary calls</b> of an expression, a view of
     * this reader and never a sibling: every call, nested calls included, that needs a dictionary
     * ({@link #ofCall} answers {@link #dictionary()}) but names no static string-literal type — an
     * inline call with a blank {@code external_dictionary_type}, or a registry function whose type
     * argument is absent, unbindable or not a string literal. The loader makes each a load error,
     * because the gate is decided before any row is read and a type known only at lookup time could
     * not be gated at all.
     *
     * @param expr
     *            the expression to walk
     * @return the typeless dictionary calls, in walk order
     */
    public static List<Expr.Call> typelessDictionaryCalls(Expr expr)
    {
        List<Expr.Call> out = new ArrayList<>();
        collectTypeless(expr, out);
        return out;
    }


    private static void collectTypeless(Expr e, List<Expr.Call> out)
    {
        switch (e)
        {
        case Expr.And a -> a.parts().forEach(p -> collectTypeless(p, out));
        case Expr.Or o -> o.parts().forEach(p -> collectTypeless(p, out));
        case Expr.Not n -> collectTypeless(n.inner(), out);
        case Expr.Binary b ->
        {
            collectTypeless(b.left(), out);
            collectTypeless(b.right(), out);
        }
        case Expr.Call c ->
        {
            ProviderNeeds n = ofCall(c);
            // A registry call that does not BIND is the compiler's own load error (arity, an
            // unknown keyword) and is left to it, so the diagnosis names the real cause; a call
            // that binds but names no static type is this guard's finding.
            if (n.dictionary() && n.dictionaryTypes().isEmpty() && binds(c))
            {
                out.add(c);
            }
            c.args().forEach(a -> collectTypeless(a, out));
            c.kwargs().values().forEach(a -> collectTypeless(a, out));
        }
        case Expr.Lit lit ->
        {
            if (lit.kind() == Expr.LitKind.LIST)
            {
                @SuppressWarnings("unchecked")
                List<Expr> items = (List<Expr>) lit.value();
                items.forEach(item -> collectTypeless(item, out));
            }
        }
        case Expr.Ref _ ->
        {
            // a reference names no call
        }
        }
    }


    private static boolean binds(Expr.Call call)
    {
        FunctionDescriptor descriptor = FunctionRegistry.descriptor(call.name());
        if (descriptor == null)
        {
            // Unreachable from collectTypeless (a dictionary need comes only from a registered
            // descriptor); an unregistered name is the compiler's own "no native function" error.
            return true;
        }
        try
        {
            ArgumentBinder.bind(descriptor, call);
            return true;
        }
        catch (ExpressionException _)
        {
            return false;
        }
    }


    private static ProviderNeeds dictionaryNeed(Expr.Call call, FunctionDescriptor descriptor,
            ProviderNeed need)
    {
        SequencedSet<String> types = new LinkedHashSet<>();
        String literal = staticStringArgument(call, descriptor, need.typeParameter());
        if (literal != null && !literal.isBlank())
        {
            types.add(literal);
        }
        return new ProviderNeeds(false, false, true, types);
    }


    /**
     * The static string literal bound to {@code parameter} in {@code call}, or {@code null} when
     * the call does not bind, the parameter is absent, or the argument is not a string literal.
     */
    private static @Nullable String staticStringArgument(Expr.Call call,
            FunctionDescriptor descriptor, @Nullable String parameter)
    {
        if (parameter == null)
        {
            return null;
        }
        List<@Nullable Expr> bound;
        try
        {
            bound = ArgumentBinder.bind(descriptor, call);
        }
        catch (ExpressionException _)
        {
            return null;
        }
        for (int i = 0; i < descriptor.parameters().size() && i < bound.size(); i++)
        {
            if (parameter.equals(descriptor.parameters().get(i).name())
                    && bound.get(i) instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.STRING)
            {
                return (String) lit.value();
            }
        }
        return null;
    }

}
