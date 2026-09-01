package net.cumba.cdisc.core.exec;

import java.util.List;
import net.cumba.cdisc.core.expr.CheckToExpr;
import net.cumba.cdisc.core.expr.ExpressionException;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.expr.convert.OperationExpressionParser;
import net.cumba.cdisc.core.expr.eval.ExprCompiler;
import net.cumba.cdisc.core.model.CheckCondition;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.cdisc.core.model.OperationType;
import net.cumba.cdisc.core.model.Rule;
import org.jspecify.annotations.Nullable;

/**
 * The <b>derived</b> provider dependencies of a rule — the single source of truth behind
 * {@code Requirements.Library} / {@code .Define} / {@code .Dictionary}
 * ({@code plans/PLAN-scope-requirements-split.md} &#167;4.5, owner ruling Q4 option (c)).
 *
 * <p>
 * ⚠⚠ <b>Each provider has more than one surface, and a derivation that reads only one is wrong on
 * rules that are correct.</b> {@link #of(Rule)} unions all three:
 * </p>
 * <ol>
 * <li>a declared ({@code $}-ref) {@code Operations} entry whose {@code OperationType} is library /
 * define / dictionary dependent — {@link OperationExecutor#isLibraryDependent} and its two
 * siblings, the switches {@code RuleRunner}'s eager arms read;</li>
 * <li>a bare {@code library_*} / {@code define_*} <b>operand prefix</b> in the {@code Check}, with
 * no {@code Operations} entry at all ({@code CORE-001081} is the live instance) —
 * {@link RuleRunner#referencesOperandPrefix};</li>
 * <li>an <b>inlined</b> operation call in the {@code Check} expression, which the loader gates with
 * an injected {@code Precondition} term rather than an {@code Operations} entry
 * ({@code RulePackageLoader.injectInlineOperationGates}).</li>
 * </ol>
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
            // Surface 2 — bare operand prefixes in the Check.
            lib |= RuleRunner.referencesOperandPrefix(check, "library_");
            def |= RuleRunner.referencesOperandPrefix(check, "define_");
            // Surface 3 — inlined operation calls.
            Expr raised = tryRaise(check);
            if (raised != null)
            {
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
     * Walks the expression for inline operation calls, mirroring
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
        case Expr.Ref r ->
        {
            // ⭐ Surface 2 (`referencesOperandPrefix`) walks the TYPED CheckCondition, so it cannot
            // see an operand that lives inside an expression-authored Check. Without this the rule
            // derives Requirements.Library = false, the runner never builds the library provider
            // for it, the operand is never populated, and the Check evaluates false on every row —
            // a SILENT PASS indistinguishable from conformant data.
            //
            // ⚠ A `$`-ref is a DECLARED operation covered by surface 1; Expr.Ref here is the bare
            // builtin-operand form. See MetadataExprScan for why the prefix is safe: no shipped
            // rule's derived value changes.
            if (r.name().startsWith("library_"))
            {
                out.library = true;
            }
            else if (r.name().startsWith("define_"))
            {
                out.define = true;
            }
        }
        default ->
        {
            // Lit — nothing to classify.
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
