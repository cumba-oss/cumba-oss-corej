package net.cumba.corej.core.expr.eval;

import java.util.List;
import java.util.Map;
import java.util.Set;

import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.GroupedResult;
import net.cumba.corej.core.exec.JoinLookup;
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
 * undecided. Additionally the fold mirrors the legacy missing-column fold (Fix #40,
 * {@code CheckConditionOptimizer.tryFoldOnMissingColumn}): a non-{@code exists} leaf whose
 * name-side column is absent from the primary table and from every joined dataset is uniformly
 * {@code FALSE}.
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
        if (foldsOnMissingColumn(leaf, ctx) && firesEmptyOnAbsentColumn(leaf))
        {
            // EC-43 (T2 reconciliation). `empty`/`is_missing` keep their broadcast TRUE — an absent
            // column is empty for every row, that is their whole contract (FIRES_ON_ABSENT_COLUMN),
            // and existing specs pin the single dataset-level finding it produces.
            //
            // EVERY OTHER operator must fall through to the row path. The verdict over an absent
            // column is no longer a per-operator constant — the column folds to all-missing and the
            // operator computes its own polarity — but more importantly the two paths REPORT
            // differently: a broadcast TRUE yields one dataset-level finding, while the row path
            // yields one finding per row. Short-circuiting here would therefore break the very
            // contract EC-43 establishes, because a PRESENT-but-all-blank column (which never
            // reaches this fold) would report per row while an ABSENT one reported once.
            // Measured: spec EC43-not-equal-absent emitted 1 violation against the control's 2.
            //
            // Falling through costs the O(1) short-circuit for absent columns (see R12); that is
            // the price of absent == blank, and correctness wins.
            return Verdict.TRUE;
        }
        return Verdict.UNKNOWN;
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
        // context variables (e.g. the Fix #10 DOMAIN injection — CORE-000598's
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
        case Expr.Call c -> isEvaluableExistsCall(c)
                || (isDatasetFactBoolCall(c) && FOLD_EQUIVALENT_BOOL_CALLS.contains(c.name()));
        case Expr.Ref r -> r.kind() == OperandKind.OPERATION_REF;
        case Expr.Lit lit -> lit.kind() == Expr.LitKind.BOOL;
        default -> false; // combinators are handled by fold(), never here
        };
        return shape && providersAvailable(e, ctx)
                && operationRefsSafe(e, ctx, allowVariableMetadata);
    }

    /**
     * BOOL calls whose legacy operators the Step-1 fold evaluates — the raised-form mirror of
     * {@code CheckConditionOptimizer.SUPPORTED_METADATA_OPERATORS} (P6 review finding A1). Other
     * BOOLEAN registrations ({@code is_integer}, the date predicates, …) are NOT legacy-foldable:
     * the legacy leaf survives to the row path, so the fold must stay UNKNOWN to preserve the
     * verdict multiplicity (per-row findings on a row-based rule, not one dataset violation).
     */
    private static final Set<String> FOLD_EQUIVALENT_BOOL_CALLS = Set.of("empty", "non_empty",
            "contains", "does_not_contain", "starts_with", "ends_with", "prefix_matches",
            "suffix_matches", "matches");

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
     * Both polarities are listed because the name reaching a caller depends on which side of
     * {@code CheckToExpr.NEGATED_TO_POSITIVE} it is read from: the operator-leaf corpora spell
     * {@code not_contains_all}, while the raised {@link Expr} spells the same thing
     * {@code Not(contains_all(…))}.
     * </p>
     *
     * <p>
     * <b>Single source.</b> {@code RuleClassifier.BROADCAST_OPERATORS} is this set — the derivation
     * (operator-leaf view) and the {@link Expr}-level consumers must not drift apart about which
     * operators broadcast. Adding an operator here is the only place it needs adding.
     * </p>
     */
    public static final Set<String> WHOLE_COLUMN_VERDICT_OPERATORS = Set.of("has_same_values",
            "has_different_values", "shares_no_elements_with", "shares_elements_with",
            "is_ordered_subset_of", "is_not_ordered_subset_of", "contains_all", "not_contains_all");

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
     * Invariant (R-P7 review): every registered BOOLEAN function is a pure per-value predicate of
     * its arguments. A future BOOLEAN registration that reads the table or context directly (the
     * way the VALUE functions {@code value()}/{@code varname()}/{@code colref} do) must be excluded
     * here, or it would be silently broadcast-flagged.
     * </p>
     */
    public static boolean isDatasetFactBoolCall(Expr.Call c)
    {
        if (!c.kwargs().isEmpty() || c.args().isEmpty())
        {
            return false;
        }
        FunctionDescriptor d = FunctionRegistry.descriptor(c.name(), c.args().size());
        return d != null && d.kind() == FunctionKind.BOOLEAN
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


    /**
     * Whether any {@code $}-operation reference of {@code e} resolves to a per-row
     * {@link GroupedResult} at runtime.
     */
    public static boolean hasGroupedOperationRef(Expr e, EvaluationContext ctx)
    {
        return hasOperationRefOfType(e, ctx, GroupedResult.class);
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
        case COLUMN, WILDCARD_COLUMN, DOTTED_REF -> true;
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
    // Missing-column fold — mirror of CheckConditionOptimizer.tryFoldOnMissingColumn
    // (Fix #40). The two implementations share these helpers so they can never drift.
    // ------------------------------------------------------------------


    /**
     * Whether the legacy missing-column fold (Fix #40) decides this leaf: its name-side column is a
     * plain authored column reference that is absent from the primary table AND from every joined
     * dataset. {@code --}-prefix names are resolved against the context's domain prefix first (the
     * legacy fold runs after the phase-2c rewrite, so it always sees concrete names).
     */
    private static boolean foldsOnMissingColumn(Expr leaf, EvaluationContext ctx)
    {
        String raw = leafNameColumn(leaf);
        if (raw == null)
        {
            return false;
        }
        String name = ExprCompiler.resolveDomainPrefix(raw, ctx);
        if (!isFoldableColumnReference(name))
        {
            return false;
        }
        if (ctx.getTable().getMetaData().getColumnIndex(name) >= 0)
        {
            return false;
        }
        return !anyJoinedDatasetHasColumn(name, ctx);
    }


    /**
     * Whether {@code leaf} is an {@code empty}/{@code is_missing} call — the two predicates that
     * keep a <b>broadcast</b> TRUE on a wholly-absent column (it is empty for every row).
     *
     * <p>
     * ⚠ <b>This is a statement about reporting granularity, not about polarity.</b> Since EC-43 /
     * Fix #139 an absent column folds to <b>all-missing</b> and <b>every</b> operator computes its
     * own polarity against it — so {@code X == ""}, {@code matches_regex(X, "^$")} and any other
     * predicate that is true of an empty value is true for every row here too. What sets these two
     * apart is only that they short-circuit to a broadcast verdict (one dataset-level finding)
     * instead of falling through to the row path (one finding per row); see the call site in
     * {@code foldLeaf} for why the short-circuit was narrowed to exactly these two.
     * </p>
     *
     * <p>
     * ⚠ The narrowing leaves a known asymmetry <b>for these two operators only</b>: {@code empty}
     * over an <em>absent</em> column reports <b>once</b>, while {@code empty} over a
     * <em>present-but-all-blank</em> column reports <b>per row</b> — identical semantics, different
     * granularity.
     * </p>
     *
     * <p>
     * <b>Why that asymmetry is acceptable and not merely tolerated</b> (user, 2026-08-04): a
     * present-but-all-blank column is <em>itself</em> a conformance finding, so the state it
     * reports differently on is one that should not persist in conformant data. It is covered for
     * every core designation:
     * </p>
     * <ul>
     * <li><b>Required</b> — {@code CORE-000356} (<i>a Required variable is empty</i>)</li>
     * <li><b>Expected</b> — {@code FDA-SD1149} (<i>empty for all records in the dataset</i>)</li>
     * <li><b>Permissible</b> — {@code FDA-SD1078} / {@code PMDA-SD1078} (<i>present in the dataset
     * but empty for all records</i>) — ⚠ the PMDA twin is only <i>Partially Executable</i>, the one
     * gap in that coverage</li>
     * </ul>
     * <p>
     * So do not "fix" the asymmetry by widening the short-circuit again: that would re-introduce
     * the defect Fix #139 removed, in exchange for aligning the reporting of a state that another
     * rule already flags.
     * </p>
     *
     * <p>
     * <em>Corrected 2026-08-04.</em> This javadoc previously read "as opposed to every other
     * operator which yields no rows there", which described pre-EC-43 behaviour and directly
     * contradicted the implementation comment at its own call site.
     * </p>
     */
    private static boolean firesEmptyOnAbsentColumn(Expr leaf)
    {
        return leaf instanceof Expr.Call c
                && ("empty".equals(c.name()) || "is_missing".equals(c.name()));
    }


    /**
     * The leaf's NAME-side column, mirroring how the legacy leaf carries its name: the left operand
     * of a comparison (descending through value-function wrappers and arithmetic), or the first
     * positional argument of a non-{@code exists} operator call. {@code null} when the name side is
     * not a plain/wildcard column reference.
     */
    private static @Nullable String leafNameColumn(Expr leaf)
    {
        return switch (leaf)
        {
        case Expr.Binary b -> nameSideColumn(b.left());
        case Expr.Call c -> !isExistsCall(c) && !c.args().isEmpty()
                ? nameSideColumn(c.args().get(0))
                : null;
        default -> null;
        };
    }


    private static @Nullable String nameSideColumn(Expr e)
    {
        return switch (e)
        {
        case Expr.Ref r -> r.kind() == OperandKind.COLUMN || r.kind() == OperandKind.WILDCARD_COLUMN
                ? r.name()
                : null;
        case Expr.Call c -> c.args().isEmpty() ? null : nameSideColumn(c.args().get(0));
        case Expr.Binary b -> nameSideColumn(b.left()); // arithmetic keeps the name on the left
        default -> null;
        };
    }


    /**
     * Returns {@code true} if {@code name} is a regular CDISC variable reference eligible for
     * column-presence folding. Conservative — only folds names that look like authored dataset
     * columns. Single source for the legacy {@code tryFoldOnMissingColumn} eligibility (the
     * optimizer delegates here).
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
     * Whether {@code columnName} is surfaceable from any {@code Match_Datasets} joined dataset.
     * Single source for the legacy {@code tryFoldOnMissingColumn} reachability check (the optimizer
     * delegates here).
     */
    public static boolean anyJoinedDatasetHasColumn(String columnName, EvaluationContext ctx)
    {
        Map<String, JoinLookup> joins = ctx.getJoinedDatasets();
        if (joins == null || joins.isEmpty())
        {
            return false;
        }
        DatasetResolver resolver = ctx.getDatasetResolver();
        if (resolver == null)
        {
            return false;
        }
        for (JoinLookup lookup : joins.values())
        {
            String dsName = lookup.getDatasetName();
            if (dsName == null)
            {
                continue;
            }
            // Fix #358 (review F1): exact name first, else the split-domain union — without this
            // an UNQUALIFIED reference to a joined column (`empty(LBORRES)` behind a
            // `Name: LB` join) folds to ALL_MISSING on a split submission even though the join
            // itself resolved the union.
            net.cumba.datatable.IDataTable joined = net.cumba.corej.core.exec.SplitDomainResolution
                    .resolveTableOrThrow(resolver, dsName, ctx.getRuleId());
            if (joined == null)
            {
                continue;
            }
            if (joined.getMetaData().getColumnIndex(columnName) >= 0)
            {
                return true;
            }
        }
        return false;
    }

}
