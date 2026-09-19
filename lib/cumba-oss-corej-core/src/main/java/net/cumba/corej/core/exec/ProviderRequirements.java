package net.cumba.corej.core.exec;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.expr.CheckToExpr;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.convert.OperationExpressionParser;
import net.cumba.corej.core.expr.eval.ExprCompiler;
import net.cumba.corej.core.expr.eval.MetadataExprScan;
import net.cumba.corej.core.expr.eval.MetadataLevel;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.Operation;
import net.cumba.corej.core.model.OperationType;
import net.cumba.corej.core.model.Rule;
import org.jspecify.annotations.Nullable;

/**
 * The <b>derived</b> provider dependencies of a rule — the single source of truth behind
 * {@code Requirements.Library} / {@code .Define} / {@code .Dictionary}
 * ({@code plans/done/PLAN-scope-requirements-split.md} &#167;4.5, owner ruling Q4 option (c)).
 *
 * <p>
 * ⚠⚠ <b>Each provider has more than one surface, and a derivation that reads only one is wrong on
 * rules that are correct.</b> {@link #of(Rule)} unions all three:
 * </p>
 * <ol>
 * <li>a declared ({@code $}-ref) {@code Operations} entry whose {@code OperationType} is library /
 * define / dictionary dependent — {@link OperationExecutor#isLibraryDependent} and its two
 * siblings, the switches {@code RuleRunner}'s eager arms read;</li>
 * <li>a metadata <b>operand</b> in the {@code Check} that resolves at a provider-backed level, with
 * no {@code Operations} entry at all. Two spellings, and <b>both</b> must be read: the bare
 * {@code library_*} / {@code define_*} operand prefix on the typed condition tree
 * ({@link RuleRunner#referencesOperandPrefix}; {@code CDISC-CG0010} is the live instance), and the
 * level-naming accessor <b>call</b> {@code var_*(…, "LIBRARY")} / {@code vlm_*(…)}, which is the
 * corpus's dominant form and is read by delegating to
 * {@link MetadataExprScan#providerLevelsUsed};</li>
 * <li>an <b>inlined</b> operation call in the {@code Check} expression, which the loader gates with
 * an injected {@code Precondition} term rather than an {@code Operations} entry
 * ({@code RulePackageLoader.injectInlineOperationGates}).</li>
 * </ol>
 *
 * <p>
 * ⚠⚠ <b>Why surface 2 delegates rather than re-implementing.</b> Until
 * {@code plans/PLAN-metadata-cache-unification.md} P0b this type read only the operand
 * <em>prefix</em>: an {@code Expr.Ref} whose name starts {@code library_}. But
 * {@code var_codelist_extensible("LIBRARY")} is an {@code Expr.Call}, and {@link #classifyCall}
 * returns early on a call that is not a declared operation — so the accessor form was invisible
 * here while {@code RuleRunner}'s own runtime gates (and {@code BroadcastFold}) read it through
 * {@link MetadataExprScan#providerLevelsUsed}. Two components disagreeing about what a rule needs
 * is the defect, so there is now one implementation of the operand vocabulary and this type calls
 * it. Measured over the shipped corpus on 2026-09-08 (3 816 distinct check rules): 153 rules are
 * library-dependent — 104 by operation, 49 by operand, the two sets disjoint — of which this
 * derivation saw 147; the delegation recovers the remaining <b>6</b> ({@code CDISC-SEND-0014},
 * {@code CDISC-SEND-0049}, {@code CDISC-SEND-0340}, {@code FDA-CT2003}, {@code PMDA-CT2003},
 * {@code FDA-CT2002}) and <b>18</b> more on the {@code define} side (the {@code vlm_*} and
 * {@code var_*("DEFINE")} carriers).
 * </p>
 *
 * <p>
 * ⚠ Surface 3 has <b>zero carriers in the shipped corpus</b> — {@code CrossCorpusDerivationTest}
 * asserts zero injected gates corpus-wide — so it is exercised only by hand-authored tests. It is
 * implemented anyway: gate R5 compares an authored declaration against this derivation, and a rule
 * that inlines a dictionary call and honestly declares {@code Dictionary: true} must not be
 * rejected.
 * </p>
 *
 * <p>
 * ⚠ {@link OperationType#DICTIONARY_AVAILABLE} is excluded: it is the availability <em>gate</em>,
 * not a dependency — it returns a well-defined {@code false} with no dictionary loaded, which is
 * exactly why {@code RuleRunner}'s eager arm excludes it too.
 * </p>
 *
 * <p>
 * This type reads the <b>authored</b> {@code Check}, never {@code Rule.getCheckExpr()}: the native
 * form is retained after the load gates run, so at gate time it is still {@code null}.
 * </p>
 *
 * @param library
 *            whether the rule needs a CDISC Library metadata provider
 * @param define
 *            whether the rule needs a sponsor Define-XML overlay
 * @param dictionary
 *            whether the rule needs at least one external dictionary
 */
public record ProviderRequirements(boolean library, boolean define, boolean dictionary)
{

    /**
     * Derives the rule's provider dependencies by unioning all three surfaces.
     *
     * @param rule
     *            the rule to inspect
     * @return the derived dependencies; all {@code false} for a rule that needs no provider
     */
    public static ProviderRequirements of(Rule rule)
    {
        boolean lib = false;
        boolean def = false;
        boolean dict = false;
        // Surface 1 — declared Operations.
        List<Operation> ops = rule.getOperations();
        if (ops != null)
        {
            for (Operation op : ops)
            {
                if (op == null)
                {
                    continue;
                }
                OperationType type = op.getOperationType();
                lib |= OperationExecutor.isLibraryDependent(type);
                def |= OperationExecutor.isDefineDependent(type);
                dict |= isDictionaryDependency(type);
            }
        }
        // ⚑ Plan C §3.3: EVERY declared check level. A define_* / library_* operand sitting in a
        // weaker level needs its provider just as much as one in the strictest — and the runtime
        // arms (RuleRunner's operand gate, gate R5) read this derivation, so a level this scan did
        // not see would run against a missing provider and silently PASS. One level, and it IS
        // getCheck(), for every rule that authors a plain Check:.
        for (CheckCondition check : rule.checkConditions())
        {
            // Surface 2a — bare operand prefixes on the TYPED condition tree. Kept beside the
            // delegation below, not replaced by it: an old-style Check that CheckToExpr cannot
            // raise (an operator leaf carrying within/regex/ordering) has no expression surface at
            // all, and its operand names are reachable only here.
            lib |= RuleRunner.referencesOperandPrefix(check, "library_");
            def |= RuleRunner.referencesOperandPrefix(check, "define_");
            Expr raised = tryRaise(check);
            if (raised != null)
            {
                // Surface 2b — every operand the expression reads at a provider-backed level,
                // accessor CALLS included. One implementation of that vocabulary, shared with the
                // runtime gates that act on it.
                Set<MetadataLevel> levels = MetadataExprScan.providerLevelsUsed(raised);
                lib |= levels.contains(MetadataLevel.LIBRARY);
                def |= levels.contains(MetadataLevel.DEFINE);
                // Surface 3 — inlined operation calls.
                Inlined inlined = new Inlined();
                scanInlineCalls(raised, inlined);
                lib |= inlined.library;
                def |= inlined.define;
                dict |= inlined.dictionary;
            }
        }
        return new ProviderRequirements(lib, def, dict);
    }

    /**
     * The rules of a run that <b>will</b> SKIP for want of a metadata provider, decided
     * <em>before</em> evaluation starts ({@code plans/PLAN-metadata-cache-unification.md} &#167;6.1
     * gap 2).
     *
     * <p>
     * ⭐ <b>Why up front matters.</b> The same fact is already discoverable at the far end of a run
     * — {@code RuleRunner}'s operand and metadata-native gates raise SKIPPED per rule, per dataset,
     * deep inside evaluation — but by then it is a scatter of per-rule statuses, not an answer to
     * <em>"what will this run be unable to check, and why?"</em>. A keyless run is the normal case
     * for the population R2 targets, so the run must be able to say <em>"N rules skipped: no
     * metadata cache"</em> before it starts, not leave the user to count SKIPPED rows.
     * </p>
     *
     * <p>
     * ⚠ This forecasts the <b>provider-absence</b> skip only. A rule can still SKIP for a reason
     * this cannot know in advance — an unresolvable codelist, a cross-standard dataset, a missing
     * secondary — so {@code library} is a lower bound on the run's SKIPPED count, never a
     * prediction of it.
     * </p>
     *
     * @param libraryDependent
     *            how many of the inspected rules need the CDISC Library at all
     * @param library
     *            the ids of the library-dependent rules that will skip, sorted; empty when the
     *            library is answerable
     * @param defineDependent
     *            how many of the inspected rules need a sponsor Define-XML at all
     * @param define
     *            the ids of the define-dependent rules that will skip, sorted; empty when a
     *            Define-XML was supplied
     */
    public record SkipForecast(int libraryDependent, List<String> library, int defineDependent,
            List<String> define)
    {

        /**
         * Defensive immutable copies: the record is handed across the run boundary, and callers
         * (and SpotBugs) must not be able to mutate the forecast after construction.
         */
        public SkipForecast
        {
            library = List.copyOf(library);
            define = List.copyOf(define);
        }


        /** The number of DISTINCT rules that will skip — a rule needing both is counted once. */
        public int skippedRuleCount()
        {
            Set<String> distinct = new TreeSet<>(library);
            distinct.addAll(define);
            return distinct.size();
        }
    }

    /**
     * Forecasts which of {@code rules} will SKIP because a provider they depend on is absent.
     *
     * <p>
     * ⚠ {@code libraryAnswerable} is the caller's, because "answerable" is not "non-null":
     * {@code OperationExecutor.libraryAnswerable} treats a DEGRADED provider as unable to serve
     * LIBRARY-level reads (Fix #369), and passing a bare {@code provider != null} here would
     * forecast zero skips for exactly the degraded run that produces the most.
     * </p>
     *
     * @param rules
     *            the rules selected for the run, after filtering
     * @param libraryAnswerable
     *            whether a CDISC Library provider can answer LIBRARY-level reads
     * @param defineAvailable
     *            whether a sponsor Define-XML provider was supplied
     * @return the forecast; a rule with no id contributes to the counts but not to the id lists
     */
    public static SkipForecast forecast(Collection<Rule> rules, boolean libraryAnswerable,
            boolean defineAvailable)
    {
        int libDependent = 0;
        int defDependent = 0;
        Set<String> libSkipped = new TreeSet<>();
        Set<String> defSkipped = new TreeSet<>();
        for (Rule rule : rules)
        {
            ProviderRequirements needs = of(rule);
            String id = rule.effectiveId();
            if (needs.library())
            {
                libDependent++;
                if (!libraryAnswerable && id != null)
                {
                    libSkipped.add(id);
                }
            }
            if (needs.define())
            {
                defDependent++;
                if (!defineAvailable && id != null)
                {
                    defSkipped.add(id);
                }
            }
        }
        return new SkipForecast(libDependent, List.copyOf(libSkipped), defDependent,
                List.copyOf(defSkipped));
    }


    /**
     * Whether the operation type is a dictionary <em>dependency</em>, i.e. dictionary-backed and
     * not the {@code dictionary_available} gate itself.
     */
    private static boolean isDictionaryDependency(@Nullable OperationType type)
    {
        return type != OperationType.DICTIONARY_AVAILABLE
                && OperationExecutor.isDictionaryDependent(type);
    }

    /**
     * Mutable accumulator for {@link #scanInlineCalls} — a record cannot be built incrementally.
     */
    private static final class Inlined
    {

        private boolean library;

        private boolean define;

        private boolean dictionary;
    }

    /**
     * Raises the authored Check to the expression IR, or {@code null} when it has no faithful
     * expression surface (an old-style / mixed Check, which by construction inlines nothing).
     */
    private static @Nullable Expr tryRaise(CheckCondition check)
    {
        try
        {
            return CheckToExpr.toExpr(check);
        }
        catch (ExpressionException _)
        {
            return null;
        }
    }


    /**
     * Walks the expression for <b>surface 3</b> only — inline operation calls, mirroring
     * {@code RulePackageLoader.collectGateTerms} — the pass that decides which availability gate an
     * inlined call needs. Reading the same predicate ({@link ExprCompiler#isInlineOperation}) and
     * the same parser is what keeps the derivation and the injection from disagreeing.
     */
    private static void scanInlineCalls(Expr node, Inlined out)
    {
        switch (node)
        {
        case Expr.And a -> a.parts().forEach(part -> scanInlineCalls(part, out));
        case Expr.Or o -> o.parts().forEach(part -> scanInlineCalls(part, out));
        case Expr.Not n -> scanInlineCalls(n.inner(), out);
        case Expr.Binary b ->
        {
            scanInlineCalls(b.left(), out);
            scanInlineCalls(b.right(), out);
        }
        case Expr.Call c ->
        {
            classifyCall(c, out);
            c.args().forEach(arg -> scanInlineCalls(arg, out));
            c.kwargs().values().forEach(v -> scanInlineCalls(v, out));
        }
        default ->
        {
            // Ref / Lit — nothing to classify HERE.
            //
            // ⭐ An expression-authored Check's bare `library_*` / `define_*` operand is an
            // Expr.Ref that `referencesOperandPrefix` cannot see (it walks the TYPED
            // CheckCondition). It is not dropped: MetadataExprScan.providerLevelsUsed carries the
            // identical prefix rule and is called on the same raised expression by of(). Deriving
            // it in two places is how the two drifted apart in the first place — the accessor-call
            // form was added there and never here — so the vocabulary lives in exactly one class.
            //
            // ⚠ A `$`-ref is a DECLARED operation, covered by surface 1.
        }
        }
    }


    private static void classifyCall(Expr.Call call, Inlined out)
    {
        if (!ExprCompiler.isInlineOperation(call))
        {
            return;
        }
        Operation op;
        try
        {
            op = OperationExpressionParser.fromCall(call, null);
        }
        catch (RuntimeException _)
        {
            // Not a well-formed operation call — the compiler rejects it on its own, and a rule
            // that cannot compile has no provider dependency worth deriving.
            return;
        }
        OperationType type = op.getOperationType();
        out.library |= OperationExecutor.isLibraryDependent(type);
        out.define |= OperationExecutor.isDefineDependent(type);
        out.dictionary |= isDictionaryDependency(type);
    }

}
