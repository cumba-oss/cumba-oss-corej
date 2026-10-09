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
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionAny;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.CheckConditionNot;
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
 * @param hierarchyReads
 *            the hierarchy capability needs (runbook W4 DI2): one per
 *            {@code valid_external_dictionary_hierarchy} call with a static type, the levels its
 *            columns read, in first-seen order
 */
public record ProviderNeeds(boolean library, boolean define, boolean dictionary,
        SequencedSet<String> dictionaryTypes, SequencedSet<HierarchyRead> hierarchyReads)
{

    /**
     * Runbook W4 DI2 ({@code PLAN-engine-gap-dict-unsupported} Q3): a CAPABILITY need beyond the
     * type — a {@code valid_external_dictionary_hierarchy} call reads its dictionary at the level
     * its child column names, and a loaded dictionary may still be unable to answer there (a house
     * file converted before DI1 has no {@code hierarchy_by_level}; no file carries LLT). The runner
     * SKIPs such a rule with the reason ({@code RuntimeDictionaryProvider.answersHierarchyAt}).
     *
     * @param type
     *            the dictionary type, as written
     * @param childLevel
     *            the level the child column reads
     * @param parentLevel
     *            the level the parent column reads, or {@code null} (a load error, F2)
     */
    public record HierarchyRead(String type, String childLevel, @Nullable String parentLevel)
    {
    }

    /** No provider needed. */
    public static final ProviderNeeds NONE = new ProviderNeeds(false, false, false,
            new LinkedHashSet<>());

    /** Defensive copies of the sets. */
    public ProviderNeeds
    {
        dictionaryTypes = java.util.Collections
                .unmodifiableSequencedSet(new LinkedHashSet<>(dictionaryTypes));
        hierarchyReads = java.util.Collections
                .unmodifiableSequencedSet(new LinkedHashSet<>(hierarchyReads));
    }


    /**
     * Needs without a hierarchy capability.
     *
     * @param library
     *            whether the CDISC Library is needed
     * @param define
     *            whether the sponsor Define-XML is needed
     * @param dictionary
     *            whether any external dictionary is needed
     * @param dictionaryTypes
     *            the statically-named dictionary types
     */
    public ProviderNeeds(boolean library, boolean define, boolean dictionary,
            SequencedSet<String> dictionaryTypes)
    {
        this(library, define, dictionary, dictionaryTypes, new LinkedHashSet<>());
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
        SequencedSet<HierarchyRead> reads = new LinkedHashSet<>(hierarchyReads);
        reads.addAll(other.hierarchyReads);
        return new ProviderNeeds(library || other.library, define || other.define,
                dictionary || other.dictionary, types, reads);
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


    /**
     * The needs of a Check level (or a Precondition) — every expression of an {@code all} /
     * {@code any} / {@code not} tree (runbook W4 DI2: the hierarchy capability arm reads the Check
     * levels too, so an inline call is gated like a bound one).
     *
     * @param condition
     *            the condition, may be {@code null}
     * @return the needs
     */
    public static ProviderNeeds ofCondition(@Nullable CheckCondition condition)
    {
        if (condition == null)
        {
            return NONE;
        }
        return switch (condition)
        {
        case CheckConditionExpression expression -> ofExpr(expression.expr());
        case CheckConditionAll all -> ofConditions(all.getConditions());
        case CheckConditionAny any -> ofConditions(any.getConditions());
        case CheckConditionNot not -> ofCondition(not.getCondition());
        };
    }


    private static ProviderNeeds ofConditions(@Nullable List<CheckCondition> conditions)
    {
        ProviderNeeds needs = NONE;
        if (conditions != null)
        {
            for (CheckCondition c : conditions)
            {
                needs = needs.union(ofCondition(c));
            }
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
        SequencedSet<HierarchyRead> reads = new LinkedHashSet<>();
        List<@Nullable Expr> bound;
        try
        {
            bound = ArgumentBinder.bind(descriptor, call);
        }
        catch (ExpressionException _)
        {
            return new ProviderNeeds(false, false, true, types);
        }
        String literal = staticStringArgument(bound, descriptor, need.typeParameter());
        if (literal != null && !literal.isBlank())
        {
            types.add(literal);
            // runbook W4 DI2: the hierarchy call's capability need — the level its child reads
            // (MedDRA only — W4 fold, R5 P3-L3: every other type answers from its bare map)
            String childLevel = DictionaryFunctions.HIERARCHY.equals(call.name())
                    && !bound.isEmpty()
                    && net.cumba.corej.core.metadata.RuntimeDictionaryProvider
                            .levelScopedHierarchy(literal)
                                    ? DictionaryFunctions.hierarchyLevelOfArgument(bound.get(0))
                                    : null;
            if (childLevel != null)
            {
                reads.add(new HierarchyRead(literal, childLevel,
                        bound.size() > 1
                                ? DictionaryFunctions.hierarchyLevelOfArgument(bound.get(1))
                                : null));
            }
        }
        return new ProviderNeeds(false, false, true, types, reads);
    }


    /**
     * The static string literal bound to {@code parameter}, or {@code null} when the parameter is
     * absent or the argument is not a string literal.
     */
    private static @Nullable String staticStringArgument(List<@Nullable Expr> bound,
            FunctionDescriptor descriptor, @Nullable String parameter)
    {
        if (parameter == null)
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
