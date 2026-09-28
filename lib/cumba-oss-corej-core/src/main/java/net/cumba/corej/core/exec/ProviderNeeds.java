package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.convert.OperationExpressionParser;
import net.cumba.corej.core.expr.eval.ArgumentBinder;
import net.cumba.corej.core.expr.eval.ExprCompiler;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.expr.eval.FunctionRegistry;
import net.cumba.corej.core.expr.eval.ProviderNeed;
import net.cumba.corej.core.model.BoundBinding;
import net.cumba.corej.core.model.CompiledBinding;
import net.cumba.corej.core.model.Operation;
import net.cumba.corej.core.model.OperationType;
import net.cumba.corej.core.model.Rule;
import org.jspecify.annotations.Nullable;

/**
 * ⭐ <b>The one reader of provider needs</b> ({@code PLAN-binding-expressions} §5.2, "provider
 * capability"): which run-level providers a rule, a binding or a single call needs before it can
 * answer.
 *
 * <p>
 * It reads <b>both keys</b> a need can be declared under:
 * </p>
 * <ul>
 * <li>an {@link OperationType}'s predicates ({@link OperationExecutor#isLibraryDependent},
 * {@link OperationExecutor#isDefineDependent}, {@link OperationExecutor#isDictionaryDependent}
 * minus the {@code dictionary_available} gate itself, which is never a dependency) — a declared
 * operation binding or an inline operation call;</li>
 * <li>a registry function's {@linkplain FunctionDescriptor#provider() provider capability}
 * ({@link ProviderNeed}) — a function call anywhere, in a compiled binding or inline.</li>
 * </ul>
 * <p>
 * and it walks every surface a call can sit on: declared operations, compiled binding expressions
 * and (for {@link #ofExpr}) any expression the caller hands it — the Check and the Precondition. So
 * a call in a binding and the same call inline in the Check are gated alike, and a ported callable
 * keeps its gates the moment its {@code OperationType} is deleted.
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
     * The needs of a rule's <b>bindings</b> — every declared operation binding and every compiled
     * binding expression, in authored order. This is the surface {@code RuleRunner}'s eager SKIP
     * arms gate: a {@code $}-bound call gets no injected Precondition gate, so the runner itself
     * must SKIP a rule whose binding needs an absent provider.
     *
     * @param rule
     *            the rule
     * @return the needs
     */
    public static ProviderNeeds ofBindings(Rule rule)
    {
        ProviderNeeds needs = NONE;
        for (BoundBinding binding : rule.bindingOrder())
        {
            needs = needs.union(switch (binding)
            {
            case BoundBinding.OfOperation op -> ofOperation(op.operation());
            case CompiledBinding compiled -> ofExpr(compiled.expression());
            });
        }
        return needs;
    }


    /**
     * The needs of one declared operation, read off its {@link OperationType}.
     *
     * @param op
     *            the operation
     * @return the needs
     */
    public static ProviderNeeds ofOperation(Operation op)
    {
        OperationType type = op.getOperationType();
        boolean dictionary = type != OperationType.DICTIONARY_AVAILABLE
                && OperationExecutor.isDictionaryDependent(type);
        SequencedSet<String> types = new LinkedHashSet<>();
        String dictionaryType = op.getExternalDictionaryType();
        if (dictionary && dictionaryType != null && !dictionaryType.isBlank())
        {
            types.add(dictionaryType);
        }
        boolean library = OperationExecutor.isLibraryDependent(type);
        boolean define = OperationExecutor.isDefineDependent(type);
        return library || define || dictionary
                ? new ProviderNeeds(library, define, dictionary, types)
                : NONE;
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
     * The needs of ONE call, not its arguments: an inline {@link OperationType} call by its
     * predicates, a registry function by its {@linkplain FunctionDescriptor#provider() provider
     * capability}. For a {@link ProviderNeed.Kind#DICTIONARY} capability the type is the static
     * string literal bound to the declared type parameter; a non-literal leaves the type unnamed
     * (wave 1 makes that a load error).
     *
     * @param call
     *            the call
     * @return the needs; {@link #NONE} for a call that needs nothing or is not well formed
     */
    public static ProviderNeeds ofCall(Expr.Call call)
    {
        if (ExprCompiler.isInlineOperation(call))
        {
            try
            {
                return ofOperation(OperationExpressionParser.fromCall(call, null));
            }
            catch (RuntimeException _)
            {
                return NONE; // not a well-formed operation call — the compiler rejects it itself
            }
        }
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
