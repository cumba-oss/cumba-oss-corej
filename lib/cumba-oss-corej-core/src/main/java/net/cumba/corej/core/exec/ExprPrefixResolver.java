package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.OperandClassifier;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.expr.eval.FunctionRegistry;
import net.cumba.corej.core.expr.eval.NameSlots;
import net.cumba.corej.core.expr.eval.Parameter;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the SDTM {@code --} domain-prefix wildcard inside a native {@link Expr} tree at bind
 * time — the expression-side sibling of {@link CheckConditionTransformer}, applying the
 * <em>same</em> two-prefix text policy ({@link CheckConditionTransformer#resolveTextWildcard}):
 * variable-name positions take the variable wildcard prefix (EC-36 — {@code ""} for SUPP/SQ, the
 * 2-character parent suffix for AP), the dataset half of a dot-qualified reference keeps the full
 * CDISC domain code, and {@code SUPP--} / {@code SQAP--} there is the supplemental family of that
 * code ({@code SUPP--.QVAL} on an {@code APMH} primary is {@code SQAPMH.QVAL}, on {@code LBCH}
 * {@code SUPPLB.QVAL} — {@code PLAN-split-supp-resolution} D6 / D10).
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
 * <li><b>{@code ${VAR[:fmt]}} operand substitution and the dot-qualified {@code **} form</b>
 * ({@code RELREC.**} itself a load error since W5 of {@code PLAN-merged-evaluation-table}) (D77d) —
 * both derive a column name from a <em>cell value</em>, so they are per-row column selection, not
 * templates. Any operand containing a {@code ${...}} placeholder is left verbatim; the {@code **}
 * column half of a dotted reference is preserved by the shared text policy.</li>
 * <li><b>The template of {@code variable_count("--LNKGRP")}</b> (D92a) — it deliberately folds
 * across the dataset inventory using the pre-resolution template ({@code --LNKGRP} re-resolves per
 * iterated dataset: AE&rarr;AELNKGRP, CM&rarr;CMLNKGRP, …). Expanding it here would leave
 * CDISC-CG0022 / CG0024 counting only the current domain's column — inert, with every gate green.
 * The function ({@code ScalarMetadataFunctions.variableCount}, a registry function since wave 4b)
 * resolves it per iterated dataset at run time.</li>
 * <li><b>{@code name_pattern=}</b> (D92b) — a string-literal variable-set selector matched by
 * regex, never a wildcard.</li>
 * <li><b>Every STRING literal that is not at a name slot</b>
 * ({@code PLAN-string-literal-prefix-rewrite} S3) — a comparison operand, a data argument, a
 * {@code regex=} value, a {@code keys=} member, the member of a data LIST. A STRING literal is
 * rewritten <b>only</b> at a name slot, and {@link NameSlots} is the only source that says which
 * slot is one (the registry's marked parameters, else the metadata accessors' name positions, else
 * none). The shared text policy rewrites dotted, {@code **} and {@code --}-leading spellings, so
 * before this rule a data literal such as {@code "DOSE NOT CHANGED--SEE CRF."} was rewritten on its
 * shape alone ({@code "DOSE NOT CHANGEDAESEE CRF."} on an AE rule); "untouched" had been a corpus
 * measurement, never a property of the code. At a name slot a LIST literal's STRING members are
 * names too (S4: {@code colref(["--SEQ", "--X"])}). A bare reference keeps the policy wherever it
 * stands (S5).</li>
 * <li><b>Non-STRING literals</b> (numbers, booleans, regexes).</li>
 * <li><b>Keyword-argument <em>keys</em></b> — a keyword names a parameter, never a column; a
 * {@code --} inside a keyword's <em>value</em> is resolved like any other operand of that
 * slot.</li>
 * </ul>
 */
public final class ExprPrefixResolver
{

    /** D92a: the operation that folds its name operand across the dataset inventory. */
    private static final String INVENTORY_FOLD_OPERATION = "variable_count";

    /** D92a: the parameter carrying that operation's fold template. */
    private static final String INVENTORY_FOLD_PARAMETER = "name";

    /** D92b: the regex variable-set selector, on any call. */
    private static final String NAME_PATTERN_PARAMETER = "name_pattern";

    private ExprPrefixResolver()
    {
    }


    /**
     * Whether the argument bound to {@code parameter} of a call to {@code function} keeps its
     * template, whatever it spells — D92a ({@code variable_count}'s fold template, positional or
     * {@code name=}) and D92b (every {@code name_pattern=}). The one statement of the exemptions:
     * {@link #resolve} reads it, and so does the name-slot roster, which holds the slots whose
     * literal behaves like a name to <em>marked ∪ kept</em>.
     *
     * @param function
     *            the call's name
     * @param parameter
     *            the bound parameter's name, or {@code null} when the call has no descriptor
     * @return whether the argument is left verbatim
     */
    public static boolean keepsTemplate(String function, @Nullable String parameter)
    {
        return NAME_PATTERN_PARAMETER.equals(parameter)
                || (INVENTORY_FOLD_OPERATION.equals(function)
                        && INVENTORY_FOLD_PARAMETER.equals(parameter))
                // runbook W1b VA (Q3): the key_variable candidates ("--SEQ") resolve against the
                // TARGET dataset at evaluation, never against the dataset in hand
                || (ReferencedRecordCount.KEY_VARIABLE_PARAMETER.equals(parameter)
                        && (ReferencedRecordCount.NAME.equals(function)
                                || ReferencedKeyUniqueness.NAME.equals(function)));
    }


    /**
     * Returns {@code e} with every resolvable {@code --} reference substituted, or the very same
     * instance when nothing needed resolving (identity is preserved bottom-up, so callers can use
     * {@code ==} as the change test).
     */
    public static Expr resolve(Expr e, String variablePrefix, String datasetPrefix)
    {
        return resolve(e, variablePrefix, datasetPrefix, NameSlots.REGISTRY);
    }


    /**
     * {@link #resolve(Expr, String, String)} against an explicit name-slot lookup — the seam a
     * planted control uses to prove the resolver asks {@link NameSlots} and nothing else.
     */
    static Expr resolve(Expr e, String variablePrefix, String datasetPrefix, NameSlots.Lookup slots)
    {
        return new Pass(variablePrefix, datasetPrefix, slots).resolve(e);
    }

    /** One resolution pass: the two prefixes and the name-slot lookup it consults. */
    private record Pass(String variablePrefix, String datasetPrefix, NameSlots.Lookup slots)
    {

        Expr resolve(Expr e)
        {
            return switch (e)
            {
            // S3: a STRING literal on its own is a value; a LIST's references still resolve (S5)
            case Expr.Lit lit -> resolveLit(lit, false);
            case Expr.Ref ref -> resolveRef(ref, variablePrefix, datasetPrefix);
            case Expr.Call c -> resolveCall(c);
            case Expr.Binary b ->
            {
                Expr left = resolve(b.left());
                Expr right = resolve(b.right());
                yield left == b.left() && right == b.right() ? b
                        : new Expr.Binary(b.op(), left, right);
            }
            case Expr.And a ->
            {
                List<Expr> parts = resolveParts(a.parts(), false);
                yield parts == null ? a : new Expr.And(parts);
            }
            case Expr.Or o ->
            {
                List<Expr> parts = resolveParts(o.parts(), false);
                yield parts == null ? o : new Expr.Or(parts);
            }
            case Expr.Not n ->
            {
                Expr inner = resolve(n.inner());
                yield inner == n.inner() ? n : new Expr.Not(inner);
            }
            };
        }


        /** An argument: a literal judged by its slot, anything else resolved as an expression. */
        private Expr resolveOperand(Expr e, boolean nameSlot)
        {
            return e instanceof Expr.Lit lit ? resolveLit(lit, nameSlot) : resolve(e);
        }


        /**
         * A literal: a STRING is rewritten only at a name slot; a LIST's members are resolved as
         * operands of the same slot (S4 — at a name slot its STRING members are names).
         */
        private Expr resolveLit(Expr.Lit lit, boolean nameSlot)
        {
            if (lit.kind() == Expr.LitKind.STRING && lit.value() instanceof String s)
            {
                if (!nameSlot)
                {
                    return lit;
                }
                String resolved = resolveName(s, variablePrefix, datasetPrefix);
                return resolved == null ? lit : new Expr.Lit(Expr.LitKind.STRING, resolved);
            }
            if (lit.kind() == Expr.LitKind.LIST && lit.value() instanceof List<?> members)
            {
                @SuppressWarnings("unchecked")
                List<Expr> exprs = (List<Expr>) members;
                List<Expr> resolved = resolveParts(exprs, nameSlot);
                return resolved == null ? lit : new Expr.Lit(Expr.LitKind.LIST, resolved);
            }
            return lit;
        }


        /** The rewritten parts, or {@code null} when every element came back identical. */
        private @Nullable List<Expr> resolveParts(List<Expr> parts, boolean nameSlot)
        {
            List<Expr> out = null;
            for (int i = 0; i < parts.size(); i++)
            {
                Expr resolved = resolveOperand(parts.get(i), nameSlot);
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


        private Expr resolveCall(Expr.Call c)
        {
            FunctionDescriptor descriptor = FunctionRegistry.descriptor(c.name());
            List<Expr> args = null;
            for (int i = 0; i < c.args().size(); i++)
            {
                Expr arg = c.args().get(i);
                Parameter bound = descriptor == null ? null : NameSlots.parameterAt(descriptor, i);
                // D92a / D92b keep their template (keepsTemplate). PLAN-dynamic-column-functions
                // §2.4 / SPEC §7.2 item 6: a find_vars /regex/ entry is a regex, not a name,
                // exactly like name_pattern= (D92b); its template, glob and literal entries ARE
                // specialised (D93c) — find_vars' entry is a name slot.
                Expr resolved = keepsTemplate(c.name(), bound == null ? null : bound.name())
                        || (i == 0 && isFindVarsRegex(c, arg)) ? arg
                                : resolveOperand(arg, slots.isNameSlot(c, i));
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
                Expr resolved = keepsTemplate(c.name(), kw.getKey()) ? value
                        : resolveOperand(value, slots.isNameSlot(c, kw.getKey()));
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


    /**
     * Whether {@code arg} is a {@code find_vars} string-literal regex entry (never specialised).
     */
    private static boolean isFindVarsRegex(Expr.Call c, Expr arg)
    {
        return FindVars.NAME.equals(c.name()) && arg instanceof Expr.Lit lit
                && lit.kind() == Expr.LitKind.STRING
                && FindVars.isRegexEntry(String.valueOf(lit.value()));
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
