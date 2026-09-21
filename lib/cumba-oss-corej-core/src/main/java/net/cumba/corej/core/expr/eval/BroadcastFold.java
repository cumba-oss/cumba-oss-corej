package net.cumba.corej.core.expr.eval;

import java.util.List;
import java.util.Set;

import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.GroupedResult;
import net.cumba.corej.core.exec.VariableMetadataResult;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import org.jspecify.annotations.Nullable;

/**
 * Three-valued (Kleene) native fold of a boolean {@link Expr} at dataset level — the native
 * equivalent of the legacy {@code CheckConditionOptimizer.partialEvaluateDataset} +
 * {@code simplify} pass (see {@code plans/done/PLAN-native-runtime-guard-residual.md}).
 *
 * <p>
 * A leaf is evaluated once via {@link NativeExprEvaluator#evaluateBroadcast} when it is
 * <b>dataset-constant</b> — an {@code exists}/{@code not_exists} presence fact, a comparison of
 * dataset facts ({@code ds_*} accessors, {@code record_count()}, literals, {@code $}-operation
 * results that are runtime-scalar), or a bare {@code $}-boolean verdict. A leaf carrying a runtime
 * {@link GroupedResult} (per-row values) or — unless {@code allowVariableMetadata} — a
 * {@link VariableMetadataResult} (per-variable values) is {@link Verdict#UNKNOWN}, exactly where
 * the removed legacy leaf classifier classified the leaf ROW / VARIABLE and the legacy fold left it
 * undecided.
 * </p>
 *
 * <p>
 * &#9888;&#9888; <b>The absent-column fold is a DATASET-LEVEL fold for every operator — D111,
 * 2026-09-17.</b> A leaf all of whose column reads bind at dataset level, at least one by
 * <b>absence</b> ({@link #bindColumnLevel}: an absent column is a dataset-level constant, D34
 * #3/#4), evaluates once and folds — {@code foldLeaf}'s {@code absentColumnLeafLevel} arm. The
 * verdict is not per-operator: the compiled program folds the absent read to all-missing (EC-43)
 * and the operator computes its own polarity, so a negative leaf such as {@code --OCCUR != "N"}
 * folds {@link Verdict#TRUE} (one dataset-level finding) while {@code X == "A"} folds
 * {@link Verdict#FALSE}. Two superseded shapes of this arm, kept for archaeology: pre-EC-43 the
 * fold made every such leaf uniformly FALSE (Fix #40); from EC-43 until D111 only
 * {@code empty}/{@code is_missing} short-circuited (the D38 allowlist) and every other leaf fell
 * through to the row path, so an absent column reported <b>per row</b> exactly like a
 * present-but-all-blank one — that reporting equivalence was EC-43's contract, and <b>D111
 * knowingly retires it</b>: absence is a schema fact decidable before a row is read, blankness a
 * data fact that needs the rows (the epistemic split of D111), and the per-dataset report is the
 * corpus' gate-plus-sister-rule idiom collapsed into the engine (D111a). A present-but-all-blank
 * column still reports per row; the specs {@code EC43-not-equal-absent-operator} /
 * {@code EC43-absent-equals-blank-control} pin the pair (1 dataset finding vs 2 row findings).
 * </p>
 *
 * <p>
 * The combinators are exact Kleene logic, mirroring the legacy {@code simplify} collapse rules:
 * {@code all[…, FALSE, …] → FALSE}, all-TRUE → TRUE, {@code any[…, TRUE, …] → TRUE}, all-FALSE →
 * FALSE, {@code not} flips, anything containing an undecided part stays UNKNOWN. The fold therefore
 * decides exactly when the legacy fold decides, with the same verdict.
 * {@code library_dataset_*}/{@code define_dataset_*} operands outside Dataset Metadata Check —
 * formerly the one documented exception (the legacy fold compared them against the empty string) —
 * are now a LOAD ERROR ({@code RulePackageLoader.validateDatasetProviderOperands}, user decision
 * 2026-06-12); such refs staying UNKNOWN here is defense-in-depth for synthetic expressions only.
 * </p>
 *
 * <p>
 * This class is also the single home of the broadcast <b>shape</b> predicates shared with
 * {@code RulePackageLoader.isBroadcastVerdictExpr} (load-time flagging) and of the runtime
 * {@code $}-operand safety walk previously private to {@code RuleRunner}, so the load-time flag,
 * the runtime guard, and the fold can never drift apart.
 * </p>
 *
 * <p>
 * ⭐ <b>Phase 5 (typed-expression plan) measured this fold against the STATIC level of the typed
 * tree</b> ({@code exec.LevelInstrument} compares them at both {@code RuleRunner} fold sites) and
 * the runtime probes here are <b>load-bearing, not redundant with the static level</b> — each
 * decides cases the typed level cannot: {@code providersAvailable} (provider presence is a runtime
 * fact the D7 SKIPPED contract depends on), {@code operationRefsSafe} and its mirror image (a
 * {@code $}-binding's materialised kind is dataset-dependent — a cross-dataset operation over an
 * absent domain degenerates to a scalar the static binding level calls per-row/group), and the
 * Kleene combinators (a decided operand's <em>value</em> short-circuits around row-level operands,
 * which no static level predicts — measured: every such decision was {@code FALSE}, where the fold
 * and the row path are observationally identical). The one deliberate fold-vs-level disagreement
 * phase 5 measured — EC-43's absent-column fall-through — was <b>closed by D111</b> (phase 7): the
 * absent-column leaf now folds through the same bind-time level classification the typed walk reads
 * ({@link #bindColumnLevel}), so that population reports {@code AGREE_DECIDED}.
 * </p>
 */
public final class BroadcastFold
{

    /** Three-valued fold verdict. */
    public enum Verdict
    {
        /** Decided: the condition holds for the dataset (one dataset-level violation). */
        TRUE,
        /** Decided: the condition does not hold (no violations). */
        FALSE,
        /** Not dataset-decidable — continue with the regular (row / per-variable) dispatch. */
        UNKNOWN
    }

    private BroadcastFold()
    {
    }


    /**
     * Folds {@code expr} against the runtime context. {@code allowVariableMetadata} permits
     * {@link VariableMetadataResult}-valued {@code $}-refs to count as constants — only valid on
     * the per-variable loop, which projects them onto the column cursor.
     */
    public static Verdict fold(Expr expr, EvaluationContext ctx, boolean allowVariableMetadata)
    {
        return switch (expr)
        {
        case Expr.And a -> foldAnd(a.parts(), ctx, allowVariableMetadata);
        case Expr.Or o -> foldOr(o.parts(), ctx, allowVariableMetadata);
        case Expr.Not n -> negate(fold(n.inner(), ctx, allowVariableMetadata));
        default -> foldLeaf(expr, ctx, allowVariableMetadata);
        };
    }


    private static Verdict foldAnd(List<Expr> parts, EvaluationContext ctx,
            boolean allowVariableMetadata)
    {
        boolean unknown = false;
        for (Expr p : parts)
        {
            Verdict v = fold(p, ctx, allowVariableMetadata);
            if (v == Verdict.FALSE)
            {
                return Verdict.FALSE; // all[…, FALSE, …] → FALSE, regardless of undecided parts
            }
            unknown |= v == Verdict.UNKNOWN;
        }
        return unknown ? Verdict.UNKNOWN : Verdict.TRUE; // empty all[] is vacuously TRUE
    }


    private static Verdict foldOr(List<Expr> parts, EvaluationContext ctx,
            boolean allowVariableMetadata)
    {
        boolean unknown = false;
        for (Expr p : parts)
        {
            Verdict v = fold(p, ctx, allowVariableMetadata);
            if (v == Verdict.TRUE)
            {
                return Verdict.TRUE; // any[…, TRUE, …] → TRUE, regardless of undecided parts
            }
            unknown |= v == Verdict.UNKNOWN;
        }
        return unknown ? Verdict.UNKNOWN : Verdict.FALSE; // empty any[] is FALSE
    }


    private static Verdict negate(Verdict v)
    {
        return switch (v)
        {
        case TRUE -> Verdict.FALSE;
        case FALSE -> Verdict.TRUE;
        case UNKNOWN -> Verdict.UNKNOWN;
        };
    }


    private static Verdict foldLeaf(Expr leaf, EvaluationContext ctx, boolean allowVariableMetadata)
    {
        if (isDatasetConstantLeaf(leaf, ctx, allowVariableMetadata)
                && NativeExprEvaluator.isSupported(leaf))
        {
            return NativeExprEvaluator.evaluateBroadcast(leaf, ctx) ? Verdict.TRUE : Verdict.FALSE;
        }
        if (absentColumnLeafLevel(leaf, ctx) == AbsentLeafLevel.DATASET_WITH_ABSENT
                && NativeExprEvaluator.isSupported(leaf))
        {
            // D111 / D39 / D39a — the absent-column dataset-level fold, by the LEVEL CALCULUS.
            // Every column this leaf reads binds at dataset level (bindColumnLevel: an absent
            // column is a dataset-level constant per D34 #3/#4; a context scalar is a dataset
            // fact), at least one of them by ABSENCE, and every other operand is a literal or a
            // runtime-scalar $-ref — so the leaf's verdict is row-independent and evaluates once.
            // The compiled program folds the absent read to ALL_MISSING (EC-43) and the operator
            // computes its own polarity, exactly as the row path would on every row; what changes
            // is REPORTING: one dataset-level finding instead of one per row.
            //
            // This deliberately breaks EC-43's absent == present-but-all-blank REPORTING
            // equivalence (the verdicts still agree): D111 rules the difference epistemic, not
            // semantic — absence is a schema fact decidable before a row is read, blankness is a
            // data fact that needs the rows. A present-but-all-blank column never reaches this
            // arm (bindColumnLevel says ROW) and keeps reporting per row. The former shape of
            // this arm — a D38 allowlist short-circuiting only `empty`/`is_missing` — is retired:
            // the level classification decides for every operator, which is D39a's "the mechanism
            // is the level calculus, not a special fold rule".
            return NativeExprEvaluator.evaluateBroadcast(leaf, ctx) ? Verdict.TRUE : Verdict.FALSE;
        }
        return Verdict.UNKNOWN;
    }

    // ------------------------------------------------------------------
    // D111 / D39a — the absent-column dataset-level leaf, classified by the
    // same bind-time level calculus the typed walk uses (bindColumnLevel).
    // ------------------------------------------------------------------

    /** The D39a level class of one leaf's operand tree; see {@link #absentColumnLeafLevel}. */
    private enum AbsentLeafLevel
    {
        /** Some operand is row/variable-level (or an unclassifiable shape) — not foldable here. */
        ROW,
        /** All operands are dataset-level facts, but none by absence — left to the other arms. */
        DATASET,
        /**
         * All operands are dataset-level facts and at least one is an ABSENT column
         * ({@link BindColumnLevel#DATASET_ABSENT}) — the D39 population; the leaf folds.
         */
        DATASET_WITH_ABSENT
    }

    /**
     * Classifies {@code e}'s operand tree for the D39a absent-column fold: dataset-level iff every
     * column reference binds at dataset level ({@link #bindColumnLevel} — absent, or a context
     * scalar), every {@code $}-reference materialised as a runtime scalar, and every call is a pure
     * value function / predicate of such operands.
     *
     * <p>
     * The call cascade mirrors {@link DomainScan}'s: the families that read the table or the cursor
     * rather than their operands — the exists family, broadcast column predicates, whole-column
     * verdicts, library gates, metadata accessors, {@code vlm_*}, the varname-anchored calls,
     * {@code value()}/{@code varname()}, and inline operations — decline
     * ({@link AbsentLeafLevel#ROW}), leaving them to the arms that own them. Everything else is,
     * per {@code DomainScan.call}'s fall-through, the join of its operands.
     * </p>
     *
     * <p>
     * ⚠ {@code DATASET} (dataset-level with <b>no</b> absence) deliberately does NOT fold in
     * {@code foldLeaf}: D111 rules exactly the absent-column population, and the shapes the fold
     * historically declines (the {@code UNSUPPORTED_SHAPE} instrument class) stay declined.
     * </p>
     */
    private static AbsentLeafLevel absentColumnLeafLevel(Expr e, EvaluationContext ctx)
    {
        return switch (e)
        {
        case Expr.Lit lit -> lit.kind() == Expr.LitKind.LIST ? listLevel(lit, ctx)
                : AbsentLeafLevel.DATASET;
        case Expr.Ref r -> refLevel(r, ctx);
        case Expr.Binary b -> joinLevels(absentColumnLeafLevel(b.left(), ctx),
                absentColumnLeafLevel(b.right(), ctx));
        case Expr.Call c -> callLevel(c, ctx);
        // combinators are fold()'s business at leaf top level; nested in operand position
        // they are not a shape this classification claims to understand.
        default -> AbsentLeafLevel.ROW;
        };
    }


    private static AbsentLeafLevel listLevel(Expr.Lit lit, EvaluationContext ctx)
    {
        // Parser-produced LIST literals hold Exprs; a synthetic literal may hold anything else —
        // classify only what is provably understood and decline the rest (ROW = "not foldable
        // here"), never cast blind: this walk runs on arbitrary runtime expressions.
        if (!(lit.value() instanceof List<?> items))
        {
            return AbsentLeafLevel.ROW;
        }
        AbsentLeafLevel level = AbsentLeafLevel.DATASET;
        for (Object item : items)
        {
            if (!(item instanceof Expr itemExpr))
            {
                return AbsentLeafLevel.ROW;
            }
            level = joinLevels(level, absentColumnLeafLevel(itemExpr, ctx));
        }
        return level;
    }


    private static AbsentLeafLevel refLevel(Expr.Ref r, EvaluationContext ctx)
    {
        return switch (r.kind())
        {
        case COLUMN -> switch (bindColumnLevel(r.name(), ctx))
        {
        case ROW -> AbsentLeafLevel.ROW;
        case DATASET_CONTEXT_SCALAR -> AbsentLeafLevel.DATASET;
        case DATASET_ABSENT -> AbsentLeafLevel.DATASET_WITH_ABSENT;
        };
        case WILDCARD_COLUMN ->
        {
            // D77b: an unresolved `--` name reaching the evaluator is a specialisation failure and
            // errors loudly (the retired missing-column fold asserted the same); a `*` capture
            // passes through and stays row/variable-level.
            ExprCompiler.resolveDomainPrefix(r.name(), ctx);
            yield AbsentLeafLevel.ROW;
        }
        // Mirrors operationRefsSafe's materialised-kind probe: only a runtime scalar is a
        // dataset fact; GroupedResult / VariableMetadataResult refs decline.
        case OPERATION_REF ->
        {
            Object v = ctx.resolveVariable(r.name());
            yield v instanceof String || v instanceof Number || v instanceof Boolean
                    ? AbsentLeafLevel.DATASET
                    : AbsentLeafLevel.ROW;
        }
        default -> AbsentLeafLevel.ROW;
        };
    }


    private static AbsentLeafLevel callLevel(Expr.Call c, EvaluationContext ctx)
    {
        String name = c.name();
        boolean cursorNullary = ("value".equals(name) || "varname".equals(name))
                && c.args().isEmpty() && c.kwargs().isEmpty();
        if (cursorNullary || isExistsCall(c) || isBroadcastColumnPredicate(c)
                || isWholeColumnVerdictCall(c) || isLibraryGateCall(c)
                || MetadataAttribute.fromFunction(name) != null || name.startsWith("vlm_")
                || DomainScan.VARNAME_ANCHORED_CALLS.contains(name)
                || ExprCompiler.isInlineOperation(c))
        {
            return AbsentLeafLevel.ROW;
        }
        AbsentLeafLevel level = AbsentLeafLevel.DATASET;
        for (Expr arg : c.args())
        {
            level = joinLevels(level, absentColumnLeafLevel(arg, ctx));
        }
        for (Expr kwarg : c.kwargs().values())
        {
            level = joinLevels(level, absentColumnLeafLevel(kwarg, ctx));
        }
        return level;
    }


    private static AbsentLeafLevel joinLevels(AbsentLeafLevel a, AbsentLeafLevel b)
    {
        if (a == AbsentLeafLevel.ROW || b == AbsentLeafLevel.ROW)
        {
            return AbsentLeafLevel.ROW;
        }
        return a == AbsentLeafLevel.DATASET_WITH_ABSENT || b == AbsentLeafLevel.DATASET_WITH_ABSENT
                ? AbsentLeafLevel.DATASET_WITH_ABSENT
                : AbsentLeafLevel.DATASET;
    }


    /**
     * Whether a non-combinator {@code leaf} is dataset-constant against the RUNTIME context: its
     * shape reads no per-row data AND every {@code $}-operation reference resolves to a
     * row-independent scalar (no {@link GroupedResult}; {@link VariableMetadataResult} only when
     * {@code allowVariableMetadata}).
     */
    static boolean isDatasetConstantLeaf(Expr e, EvaluationContext ctx,
            boolean allowVariableMetadata)
    {
        // §9.C: library_available() / available(<op>) are dataset-constant by design and must fold
        // even when the Library provider is absent (that is exactly what they report) — so they
        // bypass the providersAvailable veto that would otherwise leave them UNKNOWN.
        if (e instanceof Expr.Call gate && isLibraryGateCall(gate))
        {
            return true;
        }
        boolean shape = switch (e)
        {
        // The VALUE side may additionally be a bare reference resolved from the dataset-level
        // context variables (e.g. the Fix #10 DOMAIN injection — CDISC-CG0413's
        // `dataset_name prefix_not_equal_to 2 value "DOMAIN"`): the legacy fold resolves textual
        // values via metadata.containsKey BEFORE the literal fallback, and the compiled native
        // operand plans resolve variables before columns — both engines read the VARIABLE, so the
        // leaf is dataset-constant when the resolved value is a scalar. The NAME side stays
        // strict: the legacy classifier folds only DATASET-classified names.
        case Expr.Binary b ->
        {
            boolean factPair = isDatasetFactOperand(b.left())
                    && (isDatasetFactOperand(b.right()) || isScalarContextVarRef(b.right(), ctx));
            // A broadcast-verdict predicate compared to a BOOL literal (e.g.
            // `var_exists(X) == true`) is itself dataset-level: `== true` / `!= false` is the
            // identity of the verdict, `== false` / `!= true` its negation. The compiled plan
            // already reduces `boolExpr == lit` to the (possibly inverted) verdict, so the
            // comparison folds exactly as the bare predicate does. Mirror: the load-time
            // RulePackageLoader.isBroadcastVerdictExpr Binary case.
            boolean eq = b.op() == Expr.BinOp.EQ || b.op() == Expr.BinOp.NEQ;
            boolean boolEqVerdict = eq && ((isBoolLiteral(b.left())
                    && isDatasetConstantLeaf(b.right(), ctx, allowVariableMetadata))
                    || (isBoolLiteral(b.right())
                            && isDatasetConstantLeaf(b.left(), ctx, allowVariableMetadata)));
            yield factPair || boolEqVerdict;
        }
        // ⭐ D121/D121a (terminal review L1): the FOLD_EQUIVALENT_BOOL_CALLS roster is gone. It
        // mirrored CheckConditionOptimizer.SUPPORTED_METADATA_OPERATORS so the fold would agree
        // with the retired engine's Step-1; 7d deleted that reference copy, so the roster was a
        // hand-written list with no authority and no drift gate — the shape D121a rules out. A
        // BOOLEAN call whose every argument is a dataset fact HAS one value for the whole dataset,
        // whichever function it is, so isDatasetFactBoolCall is the whole condition. ⚑ Measured
        // free: no non-roster BOOLEAN call over dataset-fact-only arguments exists in rules-src.
        case Expr.Call c -> isEvaluableExistsCall(c) || isDatasetFactBoolCall(c);
        case Expr.Ref r -> r.kind() == OperandKind.OPERATION_REF;
        case Expr.Lit lit -> lit.kind() == Expr.LitKind.BOOL;
        default -> false; // combinators are handled by fold(), never here
        };
        return shape && providersAvailable(e, ctx)
                && operationRefsSafe(e, ctx, allowVariableMetadata);
    }


    /**
     * Whether every DEFINE / LIBRARY metadata level read by {@code e} has its provider configured
     * (P6 review finding B1): a {@code ds_*} accessor over an absent provider must stay UNKNOWN so
     * the dispatch's documented SKIPPED contract (D7) applies — never a fold verdict computed over
     * a {@code null} provider read.
     */
    private static boolean providersAvailable(Expr e, EvaluationContext ctx)
    {
        var levels = MetadataExprScan.providerLevelsUsed(e);
        if (levels.contains(MetadataLevel.DEFINE) && ctx.getDefineProvider() == null)
        {
            return false;
        }
        return !(levels.contains(MetadataLevel.LIBRARY) && ctx.getLibraryProvider() == null);
    }


    /**
     * A bare COLUMN-kind reference whose name resolves to a SCALAR dataset-level context variable
     * at runtime. Lists and per-row/per-variable results decline (stay UNKNOWN — the literal /
     * column fallbacks of the two engines are not provably aligned for those).
     */
    private static boolean isScalarContextVarRef(Expr e, EvaluationContext ctx)
    {
        if (!(e instanceof Expr.Ref r) || r.kind() != OperandKind.COLUMN)
        {
            return false;
        }
        Object v = ctx.resolveVariable(r.name());
        return v instanceof String || v instanceof Number || v instanceof Boolean;
    }


    /**
     * An {@code exists} call the fold may evaluate: the legacy classifier marks the exists family
     * DATASET <em>except</em> for {@code ${...}} operand-template names, which are per-row driver
     * substitutions (Fix #37) and classify ROW.
     */
    private static boolean isEvaluableExistsCall(Expr.Call c)
    {
        return isExistsCall(c) && !existsArgName(c).contains("${");
    }

    // ------------------------------------------------------------------
    // Broadcast SHAPE predicates — single source shared with
    // RulePackageLoader.isBroadcastVerdictExpr (load-time flag).
    // ------------------------------------------------------------------

    /**
     * The exists-family call names — the {@code ds_}/{@code var_} twins. All four are dataset-level
     * presence facts broadcast to every row, so they participate in the dataset-level folds
     * identically. (The generic {@code exists}/{@code not_exists} pair is retired and rejected at
     * load.)
     */
    private static final Set<String> EXISTS_CALLS = Set.of("ds_exists", "ds_not_exists",
            "var_exists", "var_not_exists");

    /**
     * An exists-family call on a single bare reference or string-literal name (the two argument
     * forms are equivalent by definition).
     */
    public static boolean isExistsCall(Expr.Call c)
    {
        return EXISTS_CALLS.contains(c.name()) && c.args().size() == 1 && c.kwargs().isEmpty()
                && (c.args().get(0) instanceof Expr.Ref || (c.args().get(0) instanceof Expr.Lit lit
                        && lit.kind() == Expr.LitKind.STRING));
    }

    /**
     * Operators whose verdict is one <b>whole-column</b> fact broadcast to every row, even though
     * their operands are spelled as ordinary column references. Both engines evaluate them over the
     * source column's <em>distinct values</em> and broadcast a single boolean.
     *
     * <p>
     * Both polarities are listed because the corpus historically spelled both: the retired
     * operator-leaf form said {@code not_contains_all}, while the expression form spells the same
     * thing {@code Not(contains_all(…))}.
     * </p>
     *
     * <p>
     * <b>Single source.</b> {@code RuleClassifier.BROADCAST_OPERATORS} is this set — the derivation
     * (operator-leaf view) and the {@link Expr}-level consumers must not drift apart about which
     * operators broadcast. Adding an operator here is the only place it needs adding.
     * </p>
     */
    public static final Set<String> WHOLE_COLUMN_VERDICT_OPERATORS = Set.of("has_same_values",
            "shares_no_elements_with", "shares_elements_with", "is_ordered_subset_of",
            "is_not_ordered_subset_of", "contains_all", "not_contains_all");

    /**
     * Whether {@code c} is a {@linkplain #WHOLE_COLUMN_VERDICT_OPERATORS whole-column} verdict
     * call. Unlike {@link #isDatasetFactCall} this does <em>not</em> require dataset-fact operands:
     * the whole point of these operators is that they consume a per-row column and still yield one
     * broadcast verdict.
     */
    public static boolean isWholeColumnVerdictCall(Expr.Call c)
    {
        return WHOLE_COLUMN_VERDICT_OPERATORS.contains(c.name());
    }

    /**
     * Predicates whose verdict is one dataset-wide fact about a <b>named column</b>, even though
     * the operand is spelled as an ordinary column reference.
     *
     * <p>
     * Deliberately <em>distinct</em> from {@link #WHOLE_COLUMN_VERDICT_OPERATORS}: those reduce
     * over the column's distinct <em>values</em> and that is what their javadoc promises, while
     * these answer a question about the column <em>itself</em>. Widening that set instead would
     * have made its own documentation false, and a set whose documentation has stopped describing
     * its members is how the next omission happens.
     * </p>
     *
     * <p>
     * Grounded in {@code ExprCompiler.compileVarIsNull}, which is documented <em>and
     * implemented</em> as broadcast-constant: it computes one boolean and paints it over the whole
     * row range.
     * </p>
     *
     * <p>
     * <b>Single source</b> with the operator-leaf view in {@code RuleClassifier.nonDatasetReason}
     * and the raised-expression views in {@link DomainScan} and the corpus mixed-granularity lint,
     * exactly as {@link #WHOLE_COLUMN_VERDICT_OPERATORS} is: adding an operator here is the only
     * place it needs adding. {@code var_is_null} being registered in <em>none</em> of these sets is
     * what routed {@code FDA-SD9714} / {@code PMDA-SD9714} per record — one finding per row from a
     * rule minted to report a dataset-wide absence once.
     * </p>
     * <p>
     * ⛔ <b>Every member must accept exactly the three argument shapes
     * {@link #isBroadcastColumnPredicate} tests for</b> — the {@code varname()} cursor, an
     * {@code Expr.Ref}, or a string {@code Expr.Lit}. That guard is written for {@code var_is_null}
     * specifically, including its arity of 1. A member with a different surface (an arity-0 form,
     * say) would be rejected by the guard in {@link DomainScan} and the corpus lint while still
     * short-circuiting {@code RuleClassifier.nonDatasetReason}, which keys on the NAME alone — i.e.
     * half-registered and split-brained, the exact failure this set exists to prevent. Such a
     * member needs its own guard, not an entry here.
     * </p>
     */
    public static final Set<String> BROADCAST_COLUMN_PREDICATES = Set.of("var_is_null");

    /**
     * Whether {@code c} is a {@linkplain #BROADCAST_COLUMN_PREDICATES broadcast column predicate}
     * in one of the argument shapes that actually compiles.
     *
     * <p>
     * &#9888; The shape check is <b>not</b> decoration. Callers hand an accepted call to
     * {@code DomainScan.existsCall}, which ends in {@code (String) ((Expr.Lit) arg).value()} and
     * casts blind; a name-and-arity-only guard would therefore hand it
     * {@code var_is_null(upper(X))} or {@code var_is_null(3)} and throw {@link ClassCastException}
     * on a path {@code DomainScan.infer} runs at <em>load</em>, for every rule. The accepted set is
     * exactly {@code ExprCompiler.compileVarIsNull}'s: the current-variable cursor, a plain column
     * reference, or a string literal. Anything else keeps falling through to the generic operand
     * scan, which answers without throwing.
     * </p>
     *
     * <p>
     * {@link #isExistsCall}'s guard cannot be reused here: it rejects the {@code varname()}
     * {@link Expr.Call} form, which {@code compileVarIsNull} accepts and {@code FDA-SD1078} uses.
     * </p>
     *
     * @param c
     *            the call to test
     * @return whether it is a broadcast column predicate in a compilable argument shape
     */
    public static boolean isBroadcastColumnPredicate(Expr.Call c)
    {
        if (!BROADCAST_COLUMN_PREDICATES.contains(c.name()) || c.args().size() != 1
                || !c.kwargs().isEmpty())
        {
            return false;
        }
        Expr arg = c.args().get(0);
        return ExprCompiler.isCurrentVariableName(arg) || arg instanceof Expr.Ref
                || (arg instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.STRING);
    }


    /**
     * Whether {@code c} is a §9.C library skip-gate call — {@code library_available()} (arity 0) or
     * {@code available(<op>)} (arity 1). Such a call is a dataset-constant broadcast verdict that
     * remains valid with no Library provider, so both the load-time broadcast flag
     * ({@code RulePackageLoader.isBroadcastVerdictExpr}) and the runtime fold treat it as one.
     */
    public static boolean isLibraryGateCall(Expr.Call c)
    {
        return ("library_available".equals(c.name()) && c.args().isEmpty())
                || ("available".equals(c.name()) && c.args().size() == 1)
                // T1: dictionary_available(<type>) is the external-dictionary skip-gate — a
                // dataset-constant broadcast verdict that folds valid even with no dictionary
                // provider (that absence is exactly what it reports), so both the load-time
                // broadcast flag and the runtime fold treat it as a gate call.
                || ("dictionary_available".equals(c.name()) && c.args().size() == 1);
    }


    /** The checked name of an exists-family call (only valid when {@link #isExistsCall}). */
    private static String existsArgName(Expr.Call c)
    {
        return switch (c.args().get(0))
        {
        case Expr.Ref r -> r.name();
        case Expr.Lit lit -> (String) lit.value();
        default -> throw new IllegalArgumentException("not an exists-family call: " + c);
        };
    }

    /** Pure value-function wrappers that preserve broadcast-constancy of their fact operands. */
    private static final Set<String> PURE_FACT_WRAPPERS = Set.of("str", "len", "length", "upper",
            "upcase", "lower", "lowcase", "trim", "prefix", "suffix");

    /**
     * A broadcast-safe <b>dataset-fact</b> operand by shape: a literal, a {@code $}-operation
     * reference, or a dataset-fact call. A bare data-column / dotted / wildcard reference reads
     * per-row data and declines.
     */
    public static boolean isDatasetFactOperand(Expr e)
    {
        return switch (e)
        {
        case Expr.Lit _ -> true;
        case Expr.Ref r -> r.kind() == OperandKind.OPERATION_REF;
        case Expr.Call c -> isDatasetFactCall(c) || isRowIndependentOperation(c);
        default -> false;
        };
    }


    /**
     * Whether {@code c} is a <b>row-independent</b> inline operation call (Form A) — the
     * dataset-fact operand equivalent of a {@code $}-operation reference, so an inlined
     * {@code op(...) == lit} comparison stays a broadcast-verdict exactly as the pre-inline
     * {@code $op == lit} form did. Grouped operations (a {@code group} keyword, or the
     * always-grouped {@code dy} / {@code has_mixed_emptiness_within_group}) resolve per row and are
     * excluded.
     */
    private static boolean isRowIndependentOperation(Expr.Call c)
    {
        return ExprCompiler.isInlineOperation(c) && !c.kwargs().containsKey("group")
                && !"dy".equals(c.name()) && !"has_mixed_emptiness_within_group".equals(c.name())
                // The valid_external_dictionary_* operations (T1) validate each record's own value
                // against the dictionary, so they resolve to a per-row GroupedResult despite
                // carrying no `group` keyword — they must NOT fold to a single dataset verdict.
                && !PER_ROW_INLINE_OPERATIONS.contains(c.name())
                // distinct(VAR, value_is_reference=true) also yields a per-row GroupedResult
                // (evalDistinctVariableNames) despite carrying no `group` keyword.
                && !("distinct".equals(c.name())
                        && isTrueLiteral(c.kwargs().get("value_is_reference")));
    }

    /**
     * Inline operations (Form A) that resolve to a per-row {@link GroupedResult} despite carrying
     * no {@code group} keyword, so they are NOT row-independent dataset facts.
     */
    private static final Set<String> PER_ROW_INLINE_OPERATIONS = Set.of(
            "valid_external_dictionary_value", "valid_external_dictionary_code",
            "valid_external_dictionary_code_term_pair", "valid_external_dictionary_hierarchy",
            // E8: dictionary_has_decode keys its GroupedResult by the code column exactly like its
            // four siblings — omitted here since Fix #92; surfaced by the D-TA-3 / Fix #266 flag
            // tests (the shipped corpus was unaffected: CG0096 keeps its $-operation form).
            "dictionary_has_decode");

    /**
     * Whether {@code e} is the boolean literal {@code true} (a {@code value_is_reference=true}
     * kwarg).
     */
    private static boolean isTrueLiteral(@Nullable Expr e)
    {
        return e instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.BOOL
                && Boolean.TRUE.equals(lit.value());
    }


    /** Whether {@code e} is a BOOL literal ({@code true} or {@code false}). */
    private static boolean isBoolLiteral(@Nullable Expr e)
    {
        return e instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.BOOL;
    }


    /**
     * A broadcast-constant dataset-fact call: a DATASET-scope {@code ds_*} accessor with literal
     * arguments (level / name), {@code record_count()}, or a pure wrapper over fact operands.
     */
    public static boolean isDatasetFactCall(Expr.Call c)
    {
        if (!c.kwargs().isEmpty())
        {
            return false;
        }
        if ("record_count".equals(c.name()) && c.args().isEmpty())
        {
            return true;
        }
        MetadataAttribute attr = MetadataAttribute.fromFunction(c.name());
        if (attr != null)
        {
            return attr.scope() == MetadataAttribute.Scope.DATASET
                    && c.args().stream().allMatch(a -> a instanceof Expr.Lit);
        }
        return PURE_FACT_WRAPPERS.contains(c.name()) && !c.args().isEmpty()
                && c.args().stream().allMatch(BroadcastFold::isDatasetFactOperand);
    }


    /**
     * A boolean predicate call over dataset facts only (e.g. {@code matches(ds_name("DATA"), …)}):
     * every argument is broadcast-constant, so the verdict is fold-equivalent regardless of which
     * registered BOOL function it is.
     *
     * <p>
     * Invariant (R-P7 review; <b>restated</b> round 3): every registered BOOLEAN function <em>with
     * an implementation</em> is <b>row-independent given broadcast-constant arguments</b> — its
     * verdict is a function of the argument values alone and cannot vary from row to row. A future
     * BOOLEAN registration that reads the table <em>per row</em> (the way the VALUE functions
     * {@code value()}/{@code varname()}/{@code colref} do) must be excluded here, or it would be
     * silently broadcast-flagged.
     * </p>
     *
     * <p>
     * ⚠⚠ The invariant was written as "a pure per-value predicate … reads neither the table nor the
     * evaluation context", and <b>that spelling is already false</b> for two shipped builtins:
     * {@code library_available} and {@code dictionary_available} both carry an {@code fn()} and
     * both reach {@code run.ctx().getLibraryProvider()} / {@code getDictionaryProvider()}. They are
     * admitted by the test below and folding them is <b>correct</b> — a provider is a property of
     * the run, not of the row. The property that licenses the fold is row-independence, not
     * context-freedom. ⛔ Do not un-fold them on the strength of the old wording. ⛔ The
     * {@code fn() == null} check below is that exclusion for the phase-7 compiler-dispatched
     * boolean calls ({@code CompilerDispatchedCalls}): the group and presence operators are
     * registered for their <em>signatures</em>, but every one of them reads the table or context,
     * so admitting them here would silently broadcast-flag e.g. {@code contains_all($a, $b)} —
     * exactly the hazard this invariant names.
     * </p>
     *
     * <p>
     * ⛔ <b>That invariant is a GATE, not prose</b> (R2-8, review round 2). {@code L1} replaced a
     * hand-written roster of admissible names with the descriptor test above, which closed a real
     * mirror mismatch — {@code RulePackageLoader.isBroadcastVerdictExpr} already asked this
     * question with no roster, so the load-time flag and the runtime fold disagreed for every
     * non-roster BOOL call — but it also made the default <b>fail-open</b>: an unknown BOOLEAN
     * registration is now admitted where the roster left it {@code UNKNOWN}.
     * {@code BroadcastBoolFunctionInvariantTest} narrows that: it enumerates every BOOLEAN
     * descriptor <em>with</em> an implementation contributed by any SPI {@link FunctionProvider} on
     * <b>this module's test classpath</b>, asserts every one of them comes from
     * {@code BuiltinFunctions}, and re-asserts that every compiler-dispatched boolean has
     * {@code fn() == null} — the exclusion the paragraph above relies on.
     * </p>
     *
     * <p>
     * ⚠⚠ <b>Be exact about what that gate does</b> (round 3): it gates <b>provenance</b>, not
     * row-independence. No assertion in it inspects an {@code fn()}. A new BOOLEAN added to
     * {@code BuiltinFunctions} whose implementation reads per-row table state passes it untouched —
     * the only thing that reds is {@code BuiltinFunctionsRegistrationTest}'s name/arity roster,
     * which the same edit updates <em>by construction</em>. This paragraph used to say "adding one
     * is a reviewed edit that asserts purity"; the first half is true and the second half has no
     * mechanism behind it. Row-independence is upheld by <b>review</b>, at the two doors the
     * provenance gate names: a new {@code BuiltinFunctions} entry, and a foreign provider reaching
     * this classpath.
     * </p>
     *
     * <p>
     * ⚑ <b>Residual, the classpath one</b>: the enumeration runs in this module, so it sees that
     * module's test classpath only. A {@link FunctionProvider} shipped by a <em>downstream</em>
     * module (the define-conformance module, or an embedder's jar) contributing an implemented
     * BOOLEAN that reads the table per row would be picked up by {@link FunctionRegistry} at
     * runtime and admitted here, with the gate green one module upstream. No such provider exists
     * anywhere in the stack today (verified round 3); the hazard, when one appears, is the same
     * multiplicity-not-value one as the embedder residual below.
     * </p>
     *
     * <p>
     * ⚑ <b>The one residual, deliberately accepted</b>: an embedder calling
     * {@link FunctionRegistry#register(FunctionDescriptor)} at runtime with a table-reading BOOLEAN
     * function is reachable by no test, because it exists only in that embedder's process. It is
     * accepted rather than gated because the residual hazard is <b>multiplicity, not value</b>: a
     * leaf that folds {@code TRUE} yields one dataset-level finding where the row path yielded N,
     * and the {@code FALSE} direction is observationally identical either way.
     * </p>
     */
    public static boolean isDatasetFactBoolCall(Expr.Call c)
    {
        if (!c.kwargs().isEmpty() || c.args().isEmpty())
        {
            return false;
        }
        FunctionDescriptor d = FunctionRegistry.descriptorAccepting(c.name(), c.args().size());
        return d != null && d.kind() == FunctionKind.BOOLEAN && d.fn() != null
                && c.args().stream().allMatch(BroadcastFold::isDatasetFactOperand);
    }

    // ------------------------------------------------------------------
    // Runtime $-operand safety — moved verbatim from RuleRunner (P7-era
    // broadcastSafeAtRuntime/operationRefsSafe), so the fold, the metadata
    // dispatch gate, and the per-variable loop share ONE definition.
    // ------------------------------------------------------------------


    /**
     * Walks every {@code $}-operation reference of {@code expr} and checks its RUNTIME value type:
     * a {@link GroupedResult} (per-row values) is never broadcast-safe; a
     * {@link VariableMetadataResult} (per-variable values) is safe only when
     * {@code allowVariableMetadata} (the per-variable loop projects it onto the column cursor).
     * LazyValue entries are unwrapped by {@link EvaluationContext#resolveVariable} (Fix #36),
     * exactly like the removed legacy leaf classifier's type check.
     */
    public static boolean operationRefsSafe(Expr expr, EvaluationContext ctx,
            boolean allowVariableMetadata)
    {
        return switch (expr)
        {
        case Expr.And a -> a.parts().stream()
                .allMatch(p -> operationRefsSafe(p, ctx, allowVariableMetadata));
        case Expr.Or o -> o.parts().stream()
                .allMatch(p -> operationRefsSafe(p, ctx, allowVariableMetadata));
        case Expr.Not n -> operationRefsSafe(n.inner(), ctx, allowVariableMetadata);
        case Expr.Binary b -> operationRefsSafe(b.left(), ctx, allowVariableMetadata)
                && operationRefsSafe(b.right(), ctx, allowVariableMetadata);
        case Expr.Call c -> c.args().stream()
                .allMatch(p -> operationRefsSafe(p, ctx, allowVariableMetadata))
                && c.kwargs().values().stream()
                        .allMatch(p -> operationRefsSafe(p, ctx, allowVariableMetadata));
        case Expr.Ref r ->
        {
            if (r.kind() != OperandKind.OPERATION_REF)
            {
                yield true;
            }
            Object val = ctx.resolveVariable(r.name());
            if (val instanceof GroupedResult)
            {
                yield false;
            }
            yield allowVariableMetadata || !(val instanceof VariableMetadataResult);
        }
        case Expr.Lit _ -> true;
        };
    }


    /**
     * Whether any {@code $}-operation reference of {@code e} resolves to a per-variable
     * {@link VariableMetadataResult} at runtime — the trigger for per-variable native routing.
     */
    public static boolean hasVariableMetadataRef(Expr e, EvaluationContext ctx)
    {
        return hasOperationRefOfType(e, ctx, VariableMetadataResult.class);
    }


    private static boolean hasOperationRefOfType(Expr e, EvaluationContext ctx, Class<?> type)
    {
        return switch (e)
        {
        case Expr.And a -> a.parts().stream().anyMatch(p -> hasOperationRefOfType(p, ctx, type));
        case Expr.Or o -> o.parts().stream().anyMatch(p -> hasOperationRefOfType(p, ctx, type));
        case Expr.Not n -> hasOperationRefOfType(n.inner(), ctx, type);
        case Expr.Binary b -> hasOperationRefOfType(b.left(), ctx, type)
                || hasOperationRefOfType(b.right(), ctx, type);
        case Expr.Call c -> c.args().stream().anyMatch(p -> hasOperationRefOfType(p, ctx, type))
                || c.kwargs().values().stream().anyMatch(p -> hasOperationRefOfType(p, ctx, type));
        case Expr.Ref r -> r.kind() == OperandKind.OPERATION_REF
                && type.isInstance(ctx.resolveVariable(r.name()));
        case Expr.Lit _ -> false;
        };
    }


    /**
     * Whether {@code e} reads per-row DATA: a bare column / wildcard / dotted reference outside an
     * {@code exists} presence fact, the {@code value()} current-variable cells, or a
     * {@code $}-reference resolving to a per-row {@link GroupedResult}. Metadata accessors,
     * {@code varname()}, the {@code variable_name} anchor, literals, and scalar {@code $}-results
     * are row-independent. Decides per-variable routing granularity: no row reads ⇒ one broadcast
     * verdict per variable (the legacy Step-3 fold); row reads ⇒ per-(variable, row) evaluation
     * (the legacy Step-4 residue).
     */
    public static boolean readsRowData(Expr e, EvaluationContext ctx)
    {
        return switch (e)
        {
        case Expr.And a -> a.parts().stream().anyMatch(p -> readsRowData(p, ctx));
        case Expr.Or o -> o.parts().stream().anyMatch(p -> readsRowData(p, ctx));
        case Expr.Not n -> readsRowData(n.inner(), ctx);
        case Expr.Binary b -> readsRowData(b.left(), ctx) || readsRowData(b.right(), ctx);
        case Expr.Call c ->
        {
            if (isEvaluableExistsCall(c))
            {
                // Column/dataset presence is a dataset fact, not a row read.
                yield false;
            }
            if (isExistsCall(c))
            {
                // An exists over a ${...} operand template IS a row read (per-row driver
                // substitution, Fix #37) — uniformly for the reference and the string-literal
                // argument spelling (the argument walk would only catch the placeholder ref).
                yield true;
            }
            if ("value".equals(c.name()) && c.args().isEmpty())
            {
                yield true; // current-variable per-row cells
            }
            yield c.args().stream().anyMatch(p -> readsRowData(p, ctx))
                    || c.kwargs().values().stream().anyMatch(p -> readsRowData(p, ctx));
        }
        case Expr.Ref r -> switch (r.kind())
        {
        // MATCHED_FLAG is a per-row verdict (spec §3.3: a boolean at level record) — a row read.
        case COLUMN, WILDCARD_COLUMN, DOTTED_REF, MATCHED_FLAG -> true;
        case OPERATION_REF -> ctx.resolveVariable(r.name()) instanceof GroupedResult;
        case BUILTIN -> false;
        };
        case Expr.Lit _ -> false;
        };
    }


    /**
     * Whether every {@link VariableMetadataResult}-valued {@code $}-reference of {@code e} sits in
     * GUARD position (anywhere except the right-hand side of a comparison). Legacy is
     * position-dependent: a {@code $}-NAME-side VMR leaf is folded at Step 3 against the per-column
     * projection, while a textual {@code "$vmr"} in a row-leaf VALUE position reaches Step 4's
     * {@code ValueResolver}, which has no VMR branch and yields the raw object. The per-(variable,
     * row) native path projects VMR entries per column only when this holds.
     */
    public static boolean vmrRefsOnlyInGuardPosition(Expr e, EvaluationContext ctx)
    {
        return noVmrInValuePosition(e, ctx, false);
    }


    private static boolean noVmrInValuePosition(Expr e, EvaluationContext ctx,
            boolean valuePosition)
    {
        return switch (e)
        {
        case Expr.And a -> a.parts().stream()
                .allMatch(p -> noVmrInValuePosition(p, ctx, valuePosition));
        case Expr.Or o -> o.parts().stream()
                .allMatch(p -> noVmrInValuePosition(p, ctx, valuePosition));
        case Expr.Not n -> noVmrInValuePosition(n.inner(), ctx, valuePosition);
        case Expr.Binary b -> noVmrInValuePosition(b.left(), ctx, valuePosition)
                && noVmrInValuePosition(b.right(), ctx, true);
        case Expr.Call c -> c.args().stream()
                .allMatch(p -> noVmrInValuePosition(p, ctx, valuePosition))
                && c.kwargs().values().stream()
                        .allMatch(p -> noVmrInValuePosition(p, ctx, valuePosition));
        case Expr.Ref r -> !(valuePosition && r.kind() == OperandKind.OPERATION_REF
                && ctx.resolveVariable(r.name()) instanceof VariableMetadataResult);
        case Expr.Lit _ -> true;
        };
    }

    // ------------------------------------------------------------------
    // Column-name eligibility and bind-time level classification — the
    // level-calculus primitives shared with the typed walk (LevelInstrument's
    // resolver delegates to bindColumnLevel) and with absentColumnLeafLevel.
    // ------------------------------------------------------------------


    /**
     * Returns {@code true} if {@code name} is a regular CDISC variable reference eligible for
     * column-presence folding. Conservative — only folds names that look like authored dataset
     * columns. Single source for column-presence eligibility ({@link #bindColumnLevel} and the
     * typed walk's {@code TypeExpectations} delegate here).
     * <p>
     * Excludes: null / empty; names starting with anything but {@code A–Z} (engine meta such as
     * {@code variable_name} / {@code library_variable_*} starts lowercase); {@code $}-prefixed
     * operation refs; dotted cross-dataset names; {@code ${...}} substitution templates; and any
     * wildcard / ADaM-capture name (contains {@code *} or a lowercase letter beyond the first
     * character).
     */
    public static boolean isFoldableColumnReference(@Nullable String name)
    {
        if (name == null || name.isEmpty())
        {
            return false;
        }
        if (name.indexOf('$') >= 0 || name.indexOf('.') >= 0 || name.indexOf('*') >= 0)
        {
            return false;
        }
        char first = name.charAt(0);
        if (first < 'A' || first > 'Z')
        {
            return false;
        }
        for (int i = 1; i < name.length(); i++)
        {
            char c = name.charAt(i);
            boolean upper = c >= 'A' && c <= 'Z';
            boolean digit = c >= '0' && c <= '9';
            boolean underscore = c == '_';
            if (!(upper || digit || underscore))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * The bind-time level class of a bare column-reference NAME (phase 5 of
     * {@code PLAN-typed-expression-engine.md}) — the static counterpart of what this fold's leaf
     * probes read off materialised values at run time.
     */
    public enum BindColumnLevel
    {
        /** An ordinary per-row column read. */
        ROW,
        /**
         * The name resolves to a scalar CONTEXT VARIABLE (the Fix #10 {@code DOMAIN} injection):
         * both engines resolve variables before columns, so the read is a dataset-level fact — the
         * name-based sibling of {@code isScalarContextVarRef}.
         */
        DATASET_CONTEXT_SCALAR,
        /**
         * The name is a foldable column reference absent from the primary table AND every joined
         * dataset — a dataset-level constant by D39a (D34 #3/#4: an absent column is a present
         * column holding its type's default).
         */
        DATASET_ABSENT
    }

    /**
     * Classifies a bare column-reference name against the RUNTIME context — the bind-time
     * column-level primitive of the level calculus, read by the typed walk
     * ({@code LevelInstrument}'s resolver) and by the D111 absent-column fold arm alike: context
     * variables first (resolution order), then {@link #isFoldableColumnReference} eligibility, then
     * primary-table and joined-dataset presence. A {@code --}-template or otherwise non-foldable
     * name stays {@link BindColumnLevel#ROW} — never resolved here (D77b makes an unresolved
     * {@code --} the evaluator's assertion, not this probe's).
     */
    public static BindColumnLevel bindColumnLevel(String name, EvaluationContext ctx)
    {
        Object v = ctx.resolveVariable(name);
        if (v instanceof String || v instanceof Number || v instanceof Boolean)
        {
            return BindColumnLevel.DATASET_CONTEXT_SCALAR;
        }
        if (!isFoldableColumnReference(name))
        {
            return BindColumnLevel.ROW;
        }
        if (ctx.getTable().getMetaData().getColumnIndex(name) >= 0)
        {
            return BindColumnLevel.ROW;
        }
        // ⭐⭐ UVC unqualified + the UNIFORMITY ruling (owner, 2026-09-21). This used to answer
        // `anyJoinedDatasetHasColumn(name, ctx) ? ROW : DATASET_ABSENT` -- i.e. a bare name absent
        // from the primary was ROW-level purely because some JOIN carried a column of that name.
        // That is the name's meaning varying with its surroundings, in the one place that decides
        // BINDING SCOPE rather than a value: it changes how many findings a rule emits (via
        // absentColumnLeafLevel -> foldLeaf -> DATASET_WITH_ABSENT), not just what a leaf reads.
        // ⇒ A bare name the primary lacks is a DATASET-LEVEL ABSENT column, full stop.
        return BindColumnLevel.DATASET_ABSENT;
    }

}
