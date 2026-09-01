package net.cumba.cdisc.core.expr.eval;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.cumba.cdisc.core.exec.ArithmeticSemantics;
import net.cumba.cdisc.core.exec.EvaluationContext;
import net.cumba.cdisc.core.exec.ExpressionResultCache;
import net.cumba.cdisc.core.exec.GroupKeyPolicy;
import net.cumba.cdisc.core.exec.GroupSemantics;
import net.cumba.cdisc.core.exec.GroupedResult;
import net.cumba.cdisc.core.exec.JoinLookup;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.core.exec.OperandSubstitutor;
import net.cumba.cdisc.core.exec.OperationExecutor;
import net.cumba.cdisc.core.exec.OperatorRegistry;
import net.cumba.cdisc.core.exec.ScalarSemantics;
import net.cumba.cdisc.core.exec.VariableMetadataResult;
import net.cumba.cdisc.core.expr.ExpressionException;
import net.cumba.cdisc.core.expr.ExpressionPrinter;
import net.cumba.cdisc.core.expr.OperandKind;
import net.cumba.cdisc.core.expr.RuleDefinitionException;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.expr.convert.OperationExpressionParser;
import net.cumba.cdisc.core.metadata.CdiscDomainResolver;
import net.cumba.cdisc.core.metadata.DefineMetadataListCodec;
import net.cumba.cdisc.core.metadata.VlmResolver;
import net.cumba.cdisc.core.model.NextRecordRelation;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.cdisc.core.model.OperationType;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * Compiles a boolean {@link Expr} into an executable {@link ExprProgram} of closures. Mirrors the
 * structural dispatch of {@link net.cumba.cdisc.core.expr.ExprLowering} (so the native backend
 * reproduces the lowerable subset bit-for-bit) but emits
 * {@link Primitives}/{@link FunctionRegistry} calls instead of operator-leaf
 * {@code CheckCondition}s.
 *
 * <p>
 * Operand kinds are resolved against the live table at evaluation time (via the {@link EvalRun}'s
 * {@link EvaluationContext}); the heavy structure — operator dispatch, compiled regex
 * {@link Pattern}s, bound {@link EvalFunction}s, folded literals, comparison families — is built
 * once here. Constructs the native backend does not implement (unqualified-cross-dataset names,
 * grouped-set membership, unknown functions, …) raise an {@link ExpressionException} at compile
 * time, so the caller can fall back to the lowered legacy path.
 * </p>
 */
public final class ExprCompiler
{

    /**
     * A compiled value operand: yields a {@link Vector}, or {@code null} for a missing/unresolvable
     * name-position column (which makes the enclosing predicate produce an empty {@link BitSet},
     * matching the legacy {@code forEachValue} missing-column behaviour).
     */
    @FunctionalInterface
    interface ValuePlan
    {

        @Nullable
        Vector eval(EvalRun run);
    }

    private static final System.Logger LOGGER = System.getLogger(ExprCompiler.class.getName());

    /** Comparison-mode tag markers that select an operator family (not value transforms). */
    private static final Set<String> TAGS = Set.of("date", "date_part", "time_part", "num");

    /**
     * <b>Fix #370</b> tier 3 — the {@code [domain name]} placeholder in the Library's
     * {@code SUPPQUAL} label template, matched <b>case-insensitively</b> and tolerating internal
     * whitespace.
     *
     * <p>
     * ⚠⚠ Case-insensitive is not defensiveness, it is measured necessity. Across the whole metadata
     * cache (34 product keys, 507 dataset + data-structure labels) exactly <b>seven</b> labels
     * contain a bracket, in <b>two</b> spellings: {@code "Supplemental Qualifiers for
     * [domain name]"} (4 products) and {@code "Supplemental Qualifiers [DOMAIN NAME]"} (3,
     * including {@code sdtmig 3-1-2}). A case-sensitive literal {@code replace} would ship working
     * on 4 products and <b>silently inert</b> on the other 3.
     * </p>
     */
    private static final Pattern DOMAIN_NAME_PLACEHOLDER = Pattern
            .compile("\\[\\s*domain\\s*name\\s*\\]", Pattern.CASE_INSENSITIVE);

    /**
     * Any bracketed token — the drift detector for {@link #DOMAIN_NAME_PLACEHOLDER}. A precise
     * pattern buys precision and costs drift-tolerance: were CDISC to reword the placeholder to,
     * say, {@code [parent domain]}, the substitution would silently stop applying and
     * {@code ds_label("LIBRARY")} would start returning a raw template as if it were a label. A
     * label carrying a bracket the placeholder did NOT match is the one observable signal that tier
     * 3 has gone inert, so it is logged.
     */
    private static final Pattern BRACKETED_TOKEN = Pattern.compile("\\[[^\\]]*\\]");

    /**
     * Labels already reported by {@link #substitutePlaceholder}. These are <b>per-dataset</b>
     * reads, so an unguarded warning would flood the log with one line per dataset per rule;
     * de-duplicating on the literal label collapses that to one line per distinct wording, which is
     * what the signal actually is.
     */
    private static final Set<String> WARNED_TEMPLATE_LABELS = ConcurrentHashMap.newKeySet();

    /**
     * Boolean predicates whose second argument is a literal text / regex (legacy {@code
      * resolveLiteral} semantics), not a per-row value operand.
     */
    private static final Set<String> LITERAL_ARG1 = Set.of("prefix_matches", "suffix_matches",
            "imatches");

    /**
     * Boolean predicates that fire on a wholly-absent operand column. An absent column carries no
     * data, so it is empty for every row — {@code empty}/{@code is_missing} therefore flag all rows
     * rather than taking the no-rows missing-column short-circuit. Follows the empty-operator
     * absent-column branch.
     */
    private static final Set<String> FIRES_ON_ABSENT_COLUMN = Set.of("empty", "is_missing");

    /**
     * EC-43: the all-missing vector an absent column folds to. Broadcast, type
     * {@link DataValueType#MISSING}, one cached cell for every row — so the fold costs nothing per
     * row. Hoisted to a constant because the fold sits inside a per-evaluation closure.
     */
    private static final ConstVector ALL_MISSING = ConstVector.of(null);

    /**
     * EC-43 test hook: when {@code false}, {@link #nameRefPlan} keeps returning {@code null} for an
     * absent column instead of folding it to {@link #ALL_MISSING}.
     *
     * <p>
     * This exists so the exclusion set stays <b>derivable</b> after the fix ships. The fork derives
     * its own set by monkeypatching {@code _absent_target_as_missing} off and re-classifying; Java
     * needs the same affordance, or {@code AbsentColumnContractTest} degrades into a frozen
     * hand-list — exactly what the design avoids. Package-private and flipped only by that test.
     * </p>
     */
    static boolean absentFoldEnabled = true;

    /**
     * Default for the {@code invalid_duration} {@code negative=} kwarg when the call omits it.
     * {@code true} accepts the signed (leading-minus) ISO&nbsp;8601 duration grammar, matching the
     * Python reference engine's absent-default (EC-20) and the aligned legacy operator (the
     * invalid-duration operator).
     */
    private static final boolean DEFAULT_NEGATIVE = true;

    /**
     * The boolean call names {@link #compileBoolCall} dispatches by name (the {@code *exists*}
     * family and the group operators), i.e. boolean callables that are <em>not</em> registered
     * {@link FunctionRegistry} functions. Used by {@link #isBooleanCall} to admit
     * {@code <call> == true/false} and {@code <call> == <call>}. Must mirror
     * {@code compileBoolCall}.
     *
     * <p>
     * Public because the expression-syntax documentation gate
     * ({@code net.cumba.cdisc.core.doc.ExpressionCheckSpecDriftTest} in {@code corej-cdisc-rules})
     * asserts every name here is documented in
     * {@code documentation/CORE-EXPRESSION-CHECK-SPECIFICATION.md} — the same single-sourcing
     * stance as {@link net.cumba.cdisc.core.expr.eval.BroadcastFold#WHOLE_COLUMN_VERDICT_OPERATORS}
     * (see {@code plans/PLAN-expression-docs-restructure.md} §4).
     * </p>
     */
    public static final Set<String> HARDCODED_BOOLEAN_CALLS = Set.of("ds_exists", "ds_not_exists",
            "var_exists", "var_not_exists", "does_not_equal_string_part", "has_not_equal_length",
            "has_equal_length", "has_multiple_values_for", "present_on_multiple_rows_within",
            "empty_within_except_last_row", "is_not_unique_relationship", "is_not_unique_set",
            "is_unique_set", "is_not_unique_value", "is_unique_value",
            "is_inconsistent_across_dataset", "inconsistent_enumerated_columns", "not_contains_all",
            "has_same_values", "shares_no_elements_with", "is_not_ordered_subset_of",
            "is_unique_relationship", "contains_all", "shares_elements_with",
            "is_ordered_subset_of", "var_is_null");

    private ExprCompiler()
    {
    }


    /** Compiles a boolean {@code Expr} to an executable program. */
    public static ExprProgram compile(Expr expr)
    {
        return new ExprProgram(compileBool(expr));
    }

    // ---------------------------------------------------------------------
    // Boolean nodes
    // ---------------------------------------------------------------------


    private static ExprProgram.BoolPlan compileBool(Expr e)
    {
        return compileBool(e, false);
    }


    /**
     * Compiles a boolean {@code Expr}, wrapping the result in the per-dataset cache decorator when
     * {@code e} is the <b>maximal</b> pure subtree on its path — pure and not nested inside an
     * already-pure (hence already-wrapped) ancestor (coarse, boolean-only v1; §3.5 / §3.6 of
     * {@code plans/PLAN-dataset-expression-cache.md}). {@code parentPure} carries the "an ancestor
     * will wrap me" signal down the boolean tree so only the topmost pure node is keyed.
     */
    private static ExprProgram.BoolPlan compileBool(Expr e, boolean parentPure)
    {
        boolean pure = DatasetExpressionCache.isPure(e);
        ExprProgram.BoolPlan plan = switch (e)
        {
        case Expr.And a -> compileAnd(a, pure);
        case Expr.Or o -> compileOr(o, pure);
        case Expr.Not n -> compileNot(n, pure);
        case Expr.Binary b -> compileBinary(b);
        case Expr.Call c -> compileBoolCall(c);
        case Expr.Ref r -> throw unsupported(
                "bare reference '" + r.name() + "' is not a boolean condition");
        case Expr.Lit _ -> throw unsupported("a literal is not a boolean condition");
        };
        return pure && !parentPure ? cachedBool(e, plan) : plan;
    }


    /**
     * Wraps a compiled pure boolean plan so its violation {@link BitSet} is computed once per
     * {@code (table-instance, canonical-expression, domain-prefix)} and reused across the dataset's
     * rules. The §3.6 gate ({@link DatasetExpressionCache#cacheableAt}) is applied at eval time — a
     * non-cacheable context (joins present, a metadata rule type, a non-local / variable-shadowed
     * ref) or a {@code null} cache falls straight through to {@code inner}, so behaviour is
     * identical to the uncached path. The stored result is <b>cloned on read</b>: the engine
     * mutates a child's {@link BitSet} in place ({@link #invert},
     * {@code CheckEvaluator.evaluateNot}), so a shared cache entry must never be handed out
     * directly.
     */
    private static ExprProgram.BoolPlan cachedBool(Expr e, ExprProgram.BoolPlan inner)
    {
        String canon = ExpressionPrinter.print(e);
        return run ->
        {
            EvaluationContext ctx = run.ctx();
            ExpressionResultCache cache = ctx.getExprCache();
            if (cache == null || !DatasetExpressionCache.cacheableAt(e, ctx))
            {
                return inner.eval(run);
            }
            Object stored = cache.computeIfAbsent(
                    DatasetExpressionCache.keyOf(ctx.getTable(), canon, ctx.getDomainPrefix()),
                    () -> inner.eval(run));
            if (!(stored instanceof BitSet bits))
            {
                // Invariant: a BoolPlan always yields a non-null BitSet, so computeIfAbsent always
                // stores one. Fail loud rather than silently re-evaluate if that ever changes.
                throw new IllegalStateException(
                        "cached boolean plan yielded a non-BitSet: " + canon);
            }
            return (BitSet) bits.clone();
        };
    }


    private static ExprProgram.BoolPlan compileAnd(Expr.And a, boolean parentPure)
    {
        List<ExprProgram.BoolPlan> parts = new ArrayList<>(a.parts().size());
        for (Expr p : a.parts())
        {
            parts.add(compileBool(p, parentPure));
        }
        return run ->
        {
            int rc = run.rowCount();
            BitSet result = allSet(rc); // vacuous truth: empty And -> all rows
            for (ExprProgram.BoolPlan part : parts)
            {
                result.and(part.eval(run));
                // Candidate-mask short-circuit (E2): the running intersection can only shrink, so
                // once it is empty no later part can add a violation — skip the rest. Verdict-
                // identical (a whole part is skipped, never given a partial row view, so group/
                // aggregate operators that need the full dataset are unaffected).
                if (result.isEmpty())
                {
                    break;
                }
            }
            return result;
        };
    }


    private static ExprProgram.BoolPlan compileOr(Expr.Or o, boolean parentPure)
    {
        List<ExprProgram.BoolPlan> parts = new ArrayList<>(o.parts().size());
        for (Expr p : o.parts())
        {
            parts.add(compileBool(p, parentPure));
        }
        return run ->
        {
            int rc = run.rowCount();
            BitSet result = new BitSet(rc);
            for (ExprProgram.BoolPlan part : parts)
            {
                result.or(part.eval(run));
                // Candidate-mask short-circuit (E2): the running union can only grow, so once every
                // row is already a violation no later part can change the result — skip the rest.
                // Verdict-identical and group-operator-safe (whole parts only, never a partial
                // view).
                if (result.nextClearBit(0) >= rc)
                {
                    break;
                }
            }
            return result;
        };
    }


    private static ExprProgram.BoolPlan compileNot(Expr.Not n, boolean parentPure)
    {
        // not equalsIgnoreCase(X, v) is the not_equal_to_case_insensitive surface, NOT a structural
        // negation: the legacy operator treats both-missing as "equal" (no violation), so a literal
        // [0,rowCount) flip would turn both-missing rows into spurious violations. Map it straight
        // to
        // the negated case-insensitive equality (parent-plan micro-decision (i) / Q1), mirroring
        // ExprLowering.lowerNot.
        if (isCaseInsensitiveEqualityCall(n.inner()))
        {
            return compileCaseInsensitiveEquality((Expr.Call) n.inner(), true);
        }
        // Q1 negation pairs: the converter spells the negative group operators as not
        // <positive>(…).
        // Map them straight to the negative group semantics — never a structural [0,rowCount) flip,
        // which would wrongly flag rows in no group (missing/invalid within-key, or the per-group
        // unflagged rows). Mirrors ExprLowering.lowerNot -> negative operator-leaf.
        if (n.inner() instanceof Expr.Call call)
        {
            if ("present_on_multiple_rows_within".equals(call.name()))
            {
                return compileMultipleRowsWithin(call, false);
            }
            if ("has_next_corresponding_record".equals(call.name()))
            {
                return compileNextCorrespondingRecord(call);
            }
            if ("is_sorted_by".equals(call.name()))
            {
                return compileTargetIsNotSortedBy(call);
            }
            // is_unique_set is the one positive group function that is NOT defined as
            // invert(<negative>) — compileUniqueSet(c, false) computes uniqueCandidates
            // independently of duplicates, so the double-inversion identity a structural flip
            // relies on holds only where the two actually partition the rows.
            //
            // ⚠ SINCE EC-53 / Fix #143 THIS BRANCH IS REDUNDANT, and the comment that used to
            // stand here was wrong about why it was not. It claimed the branch was still needed
            // because GroupSemantics.uniqueSetViolations returns an empty BitSet for both
            // polarities on an absent target. That was the pre-EC-53 behaviour: an absent target
            // is now dropped and the check regroups, so duplicates and uniqueCandidates partition
            // every row and the mapping computes exactly what a flip would. Nor does the ONE
            // surviving early-out rescue the claim: `rowCount <= 0` makes the flip a no-op
            // (flip(0, 0)) anyway. The `nameColName == null` early-out the older text also
            // invoked no longer exists at all — since the collapsed single-list grammar (Plan A,
            // 2026-08-23) uniqueSetViolations takes no name parameter, only a keyCols list, and
            // an absent or null member is dropped inside the key loop rather than short-circuited.
            //
            // It is kept anyway, deliberately: it costs nothing, it states the negative the
            // converter actually emitted instead of deriving it, it keeps this path identical to
            // ExprLowering's POSITIVE_TO_NEGATIVE mapping and the legacy leaf, and
            // uniqueSetViolations is a public method taking List<? extends @Nullable String> —
            // so the partition invariant this would otherwise depend on is a property of a
            // collaborator, not of a private contract. Removing it would trade a free branch for
            // a dependency on that invariant never regressing; a regression there reintroduces
            // the whole-table over-firing shape CORE-000213 / CORE-001034 were filed for.
            if ("is_unique_set".equals(call.name()))
            {
                return compileUniqueSet(call, true);
            }
        }
        return invert(compileBool(n.inner(), parentPure));
    }


    /**
     * Inverts a boolean plan over the run's row count. The four positive group functions (change
     * #1) — {@code is_unique_relationship}, {@code contains_all}, {@code shares_elements_with},
     * {@code is_ordered_subset_of} — are the logical complement of their existing negative operator
     * plans, so the {@code not <positive>(…)} the converter emits double-inverts back to the
     * negative bit-for-bit, while a bare {@code <positive>(…)} reads as the natural positive.
     */
    private static ExprProgram.BoolPlan invert(ExprProgram.BoolPlan inner)
    {
        return run ->
        {
            BitSet result = inner.eval(run);
            result.flip(0, run.rowCount());
            return result;
        };
    }


    /**
     * Whether an operation may join the unified boolean surface (boolean position and
     * {@code == true/false} via the BoolPlan path): it must be boolean-valued <em>and</em>
     * non-library-dependent. {@code domain_is_custom} is excluded: it is library-dependent, so
     * without a Library it resolves to the {@code LIBRARY_NOT_AVAILABLE} sentinel — which under a
     * {@code not}/{@code invert} would mis-fire every row — and its §9.C skip-gate only exists on
     * converted Form-B rules, not an inline Form-A use. It keeps its operand-position
     * {@code == true/false} behaviour unchanged.
     *
     * <p>
     * ⚠ This paragraph used to claim that {@code domain_is_custom} was the only boolean-valued
     * operation and that the predicate therefore admitted <b>none</b>. That was already untrue when
     * written: {@code variable_is_null} (T5a) is boolean-valued and library-independent, so it has
     * been admitted all along. The admitted set is now {@code variable_is_null} and
     * {@code variable_exists} — read it off {@link OperationExecutor#isBooleanValued} minus
     * {@link OperationExecutor#isLibraryDependent}, never off this prose.
     * </p>
     *
     * <p>
     * ⚑ {@code variable_exists} is admitted here only as a consequence of being boolean-valued; it
     * is <b>not</b> the intended way to test column existence. Existence is a check function
     * ({@code var_exists(X)} / {@code var_exists("D.X")}, see
     * {@code plans/PLAN-variable-exists-cross-dataset.md}) and the operation exists to carry the
     * answer into {@code Output_Variables} ({@code plans/PLAN-retired-operators-as-operations.md}).
     * The two agree by construction — {@code OperationExecutor.evalVariableExists} reads the same
     * facts — so an inline use here cannot disagree with the function; it is merely a longer way to
     * say the same thing.
     * </p>
     */
    private static boolean isUnifiableBooleanOperation(@Nullable OperationType type)
    {
        return OperationExecutor.isBooleanValued(type)
                && !OperationExecutor.isLibraryDependent(type);
    }


    /**
     * Bridges an inline boolean <em>operation</em> — compiled by {@link #operationCallPlan} to a
     * {@link ValuePlan} that broadcasts a {@link Boolean} — to a verdict {@link BitSet}: each row
     * fires where the operation resolved to {@link Boolean#TRUE}. Such an operation is a total
     * dataset-level broadcast (every row identical, always a {@code Boolean}), so under
     * {@link #invert} the structural complement is correct. Only operations admitted by
     * {@link #isUnifiableBooleanOperation} reach here (currently none — see that method); the
     * {@code null} guard is defensive (the operand plan broadcasts a {@code ConstVector}, never a
     * Java {@code null}). Retained as general scaffolding for a future non-library boolean
     * operation.
     */
    private static ExprProgram.BoolPlan valueAsBool(ValuePlan vp)
    {
        return run ->
        {
            Vector v = vp.eval(run);
            BitSet out = new BitSet(run.rowCount());
            if (v == null)
            {
                return out;
            }
            for (int r = 0; r < run.rowCount(); r++)
            {
                if (Boolean.TRUE.equals(v.resolvedObject(r)))
                {
                    out.set(r);
                }
            }
            return out;
        };
    }


    private static boolean isCaseInsensitiveEqualityCall(Expr e)
    {
        return e instanceof Expr.Call c && "equalsIgnoreCase".equals(c.name())
                && c.args().size() == 2 && c.kwargs().isEmpty();
    }


    /**
     * Native plan for {@code equalsIgnoreCase(X, value)} ({@code negate == false}, the
     * {@code equal_to_case_insensitive} surface) and its negation {@code not equalsIgnoreCase(X,
     * value)} ({@code negate == true}, {@code not_equal_to_case_insensitive}). Routes through the
     * shared {@link Primitives#equality} case-insensitive path so the both-missing / one-missing
     * verdicts match the legacy operator bit-for-bit.
     */
    private static ExprProgram.BoolPlan compileCaseInsensitiveEquality(Expr.Call c, boolean negate)
    {
        // EC-43: fold the target; the value operand keeps its own contract.
        ValuePlan nameP = operandPlan(c.args().get(0), true, true);
        ValuePlan valueP = valuePlan(c.args().get(1));
        return run ->
        {
            Vector v = nameP.eval(run);
            Vector valueV = valueP.eval(run);
            if (v == null || valueV == null)
            {
                return new BitSet();
            }
            return Primitives.equality(v, valueV, run.rowCount(), negate, true, false, false);
        };
    }


    private static ExprProgram.BoolPlan compileBinary(Expr.Binary b)
    {
        // X != <arithmetic> — the not_equal_to_{divide,subtract,pctchg} surfaces evaluate natively
        // through the shared ArithmeticSemantics (Phase 4b); other arithmetic shapes fall back to
        // the lowered legacy path.
        if (b.op() == Expr.BinOp.NEQ && b.right() instanceof Expr.Binary rb && isArith(rb.op()))
        {
            return compileArithmeticNotEqual(b, rb);
        }
        return switch (b.op())
        {
        case MATCH, NMATCH -> compileRegex(b);
        case IN, NOT_IN -> compileMembership(b);
        default -> compileComparison(b);
        };
    }


    private static boolean isArith(Expr.BinOp op)
    {
        return op == Expr.BinOp.ADD || op == Expr.BinOp.SUB || op == Expr.BinOp.MUL
                || op == Expr.BinOp.DIV;
    }


    private static boolean isHundred(Expr e)
    {
        return e instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.NUMBER
                && Double.compare((Double) lit.value(), 100.0) == 0;
    }


    /**
     * Native plan for {@code X != A / B} (divide), {@code X != A - B} (subtract) and
     * {@code X != ((A - B) / B) * 100} (pctchg). Per row it reads the three numeric operands and
     * applies {@link ArithmeticSemantics}, so it is bit-for-bit identical to the legacy
     * {@code not_equal_to_*} operators. Any other arithmetic shape is rejected (the rule then falls
     * back to the lowered legacy path).
     */
    private static ExprProgram.BoolPlan compileArithmeticNotEqual(Expr.Binary b, Expr.Binary rhs)
    {
        Expr aExpr;
        Expr bExpr;
        int mode; // 0 = divide, 1 = subtract, 2 = pctchg
        // pctchg (MUL by 100) is checked first: its structural identity test
        // (b1.name().equals(b2.name())) is ref-only and must keep its specific shape. The plain
        // divide/subtract branch (DIV/SUB op) is disjoint from pctchg (MUL op), so loosening its
        // operands to any value expression (column / $-var / numeric literal / nested value) cannot
        // shadow pctchg. Operands bind via operandPlan and are read per row through asDouble below,
        // preserving the existing missing/NaN semantics.
        if (rhs.op() == Expr.BinOp.MUL && isHundred(rhs.right())
                && rhs.left() instanceof Expr.Binary div && div.op() == Expr.BinOp.DIV
                && div.left() instanceof Expr.Binary sub && sub.op() == Expr.BinOp.SUB
                && sub.left() instanceof Expr.Ref && sub.right() instanceof Expr.Ref b1
                && div.right() instanceof Expr.Ref b2 && b1.name().equals(b2.name()))
        {
            aExpr = sub.left();
            bExpr = div.right();
            mode = 2;
        }
        else if (rhs.op() == Expr.BinOp.DIV || rhs.op() == Expr.BinOp.SUB)
        {
            aExpr = rhs.left();
            bExpr = rhs.right();
            mode = rhs.op() == Expr.BinOp.DIV ? 0 : 1;
        }
        else
        {
            throw unsupported("unsupported arithmetic comparison shape");
        }
        ValuePlan nameP = operandPlan(b.left(), true);
        ValuePlan aP = operandPlan(aExpr, true);
        ValuePlan bP = operandPlan(bExpr, true);
        int m = mode;
        return run ->
        {
            Vector nameV = nameP.eval(run);
            Vector aV = aP.eval(run);
            Vector bV = bP.eval(run);
            if (nameV == null || aV == null || bV == null)
            {
                return new BitSet();
            }
            int rc = run.rowCount();
            BitSet result = new BitSet(rc);
            for (int r = 0; r < rc; r++)
            {
                if (nameV.isMissing(r) || aV.isMissing(r) || bV.isMissing(r))
                {
                    continue;
                }
                double a = aV.asDouble(r);
                double bd = bV.asDouble(r);
                double expected = switch (m)
                {
                case 0 -> ArithmeticSemantics.divide(a, bd);
                case 1 -> ArithmeticSemantics.subtract(a, bd);
                default -> ArithmeticSemantics.percentChange(a, bd);
                };
                if (ArithmeticSemantics.differs(nameV.asDouble(r), expected))
                {
                    result.set(r);
                }
            }
            return result;
        };
    }


    private static ExprProgram.BoolPlan compileRegex(Expr.Binary b)
    {
        Pattern pattern = compilePattern(b.right());
        boolean negate = b.op() == Expr.BinOp.NMATCH;
        ValuePlan nameP = operandPlan(b.left(), true, true);
        return run ->
        {
            Vector v = nameP.eval(run);
            if (v == null)
            {
                // EC-43: unreachable for an absent column (folded to all-missing); still reached
                // by a `$`-name not in context and by an unresolved `--` wildcard.
                return new BitSet();
            }
            return Primitives.regexFind(v, pattern, run.rowCount(), negate);
        };
    }


    private static ExprProgram.BoolPlan compileMembership(Expr.Binary b)
    {
        boolean negate = b.op() == Expr.BinOp.NOT_IN;
        // T3 composite membership: `tuple(c1, c2, …) [not] in distinct([c1, c2, …], domain="D")`.
        // The left operand is the per-row composite key (the `tuple` value function's List cell)
        // and
        // the right operand a list-target `distinct` operation resolving to a Set<List<String>> of
        // the reference dataset's row-tuples. Detected from the `tuple(...)` LHS and handled as a
        // whole-tuple set membership (NOT the element-wise list-membership of listMembership).
        if (b.left() instanceof Expr.Call tupleCall && "tuple".equals(tupleCall.name()))
        {
            return compileTupleMembership(tupleCall, b.right(), negate);
        }
        // upper(X) in/not in […] is the (is_)(not_)contained_by_case_insensitive surface: read the
        // unwrapped column, build an upper-cased set, and fold the probe (Primitives.membership
        // upper-cases the cell), exactly as the legacy operator does.
        boolean caseInsensitive = isUpperCall(b.left());
        // Both the positive (is_contained_by_case_insensitive) and negative
        // (is_not_contained_by_case_insensitive) case-insensitive membership surfaces nativize:
        // Primitives.membership(v, set, rowCount, negate, caseInsensitive=true) upper-cases the
        // probe and returns `negate != set.contains(probe)`, so the negated form evaluates
        // correctly. The legacy `is_not_contained_by_case_insensitive` operator
        // the case-insensitive membership operator is implemented to mirror this
        // exactly — case-insensitive negative membership on both engines (was previously an
        // unimplemented legacy no-op; PLAN-regex-rule-optimization Phase 1).
        Expr nameExpr = caseInsensitive ? ((Expr.Call) b.left()).args().get(0) : b.left();
        // EC-43: fold the probe column. The list/accessor sources below keep their own guards.
        ValuePlan nameP = operandPlan(nameExpr, true, !isListAccessor(nameExpr));
        // List-LHS membership (D4 of PLAN-define-item-metadata-parity-929-1081): a list-valued
        // metadata accessor (var_codelist_coded_codes) compares element-wise, mirroring Python's
        // is_column_of_iterables(target) branch. Detected statically from the accessor function.
        boolean listLhs = isListAccessor(nameExpr);
        Expr right = b.right();
        // RHS is a list-valued metadata accessor (e.g. var_codelist_coded_values("LIBRARY")): under
        // the per-(variable, row) iteration it is a per-column constant list, so resolve it to the
        // membership set and do scalar-in-list membership — mirroring Python's
        // is_(not_)contained_by
        // is_column_of_iterables(comparator) branch (NRI-008, value-check-against-library). Must
        // precede the constant-set paths below, which only accept a list literal or a $-variable.
        // VLM (Value Check against Define XML VLM): a per-record value-level codelist accessor
        // (vlm_codelist_coded_values / _coded_codes). Unlike the library accessor, the codelist
        // varies PER ROW (a different value-level condition may apply to each record), so the set
        // is
        // rebuilt per row rather than read once from row 0. A row whose accessor yields null (no
        // condition matched, or no Define-XML) makes no membership decision — it never fires.
        if (!listLhs && isVlmListAccessor(right))
        {
            ValuePlan rightP = operandPlan(right, true);
            return run ->
            {
                Vector v = nameP.eval(run);
                if (v == null)
                {
                    return new BitSet();
                }
                Vector rv = rightP.eval(run);
                return vlmListMembership(v, rv, run.rowCount(), negate, caseInsensitive);
            };
        }
        if (!listLhs && isListAccessor(right))
        {
            ValuePlan rightP = operandPlan(right, true);
            return run ->
            {
                Vector v = nameP.eval(run);
                if (v == null)
                {
                    return new BitSet();
                }
                return Primitives.membership(v, listAccessorSet(rightP, run, caseInsensitive),
                        run.rowCount(), negate, caseInsensitive);
            };
        }
        // Phase 9b (decision D2): an ALL-NUMERIC list LITERAL runs membership numerically — the
        // probe is parsed and compared against the numeric members, so "10.0"/"01" both match the
        // member 10. A MIXED list literal (a NUMBER and a STRING member) is a load error. This
        // classification applies ONLY to a static list literal, never to a $-var list, a ${*}
        // wildcard, or a per-row GroupedResult (their contents are dynamic / not statically typed)
        // — those stay textual. The case-insensitive surface (upper(X) in [...]) is all-string and
        // never numeric (numbers have no case). The legacy OperatorRegistry membership path runs
        // the SAME classification on the JSON-array node types (isNumber() vs isTextual()), which
        // agree element-for-element with these Expr.Lit kinds, so native == legacy by construction.
        if (!caseInsensitive && !listLhs)
        {
            Set<Double> numericMembers = numericMemberSet(right);
            if (numericMembers != null)
            {
                return run ->
                {
                    Vector v = nameP.eval(run);
                    return v == null ? new BitSet()
                            : Primitives.numericMembership(v, numericMembers, run.rowCount(),
                                    negate);
                };
            }
        }
        // `${*}` wildcard list operand (Fix #37 / Epic B1): the membership set is row-dependent —
        // it enumerates foreign/local columns whose names match the anchored pattern and reads the
        // row's values, via ValueResolver.resolveWildcardValues. Build a per-row plan
        // when the RHS is a wildcard substitution; otherwise the constant-set path below applies.
        OperandSubstitutor.Wildcard wild = wildcardOperand(right);
        if (wild != null)
        {
            return wildcardMembershipPlan(nameP, wild, negate, caseInsensitive);
        }
        // §9.A: the RHS may be an inline list operation (Form A) — computed like a $-operation ref,
        // supporting both the broadcast-set and the per-row GroupedResult shapes (e.g. an inlined
        // `X in distinct(SV.VISITNUM, group=[USUBJID])`). Built once at compile time.
        Operation inlineSetOp = inlineSetOperation(right);
        return run ->
        {
            Vector v = nameP.eval(run);
            if (v == null)
            {
                return new BitSet();
            }
            // A $-reference may resolve to a per-row GroupedResult (e.g. CORE-000168's
            // $sv_visitnum, a distinct-per-USUBJID operation). The legacy engine resolves the
            // membership set PER ROW (
            // GroupedResult.getForRow); mirror that with a per-row loop instead of the broadcast
            // constant set.
            if (right instanceof Expr.Ref ref
                    && run.ctx().resolveVariable(ref.name()) instanceof GroupedResult grouped)
            {
                return groupedMembership(v, grouped, run, negate, caseInsensitive);
            }
            if (inlineSetOp != null)
            {
                Object result = inlineOperationResult(inlineSetOp, run.ctx());
                if (result instanceof GroupedResult grouped)
                {
                    return groupedMembership(v, grouped, run, negate, caseInsensitive);
                }
                Set<String> inlineSet = toSet(result, caseInsensitive);
                return listLhs
                        ? Primitives.listMembership(v, inlineSet, run.rowCount(), negate,
                                caseInsensitive)
                        : Primitives.membership(v, inlineSet, run.rowCount(), negate,
                                caseInsensitive);
            }
            Set<String> set = buildSet(run, right, caseInsensitive);
            return listLhs
                    ? Primitives.listMembership(v, set, run.rowCount(), negate, caseInsensitive)
                    : Primitives.membership(v, set, run.rowCount(), negate, caseInsensitive);
        };
    }


    /**
     * Native plan for the T3 composite membership {@code tuple(c1, …) [not] in distinct([c1, …],
     * domain="D")}. The left operand evaluates per row to a {@code List<String>} composite key (the
     * {@code tuple} value function); the right operand is a list-target {@code distinct} operation
     * (or a {@code $}-reference to its result) resolving to a {@code Set<List<String>>} of the
     * reference dataset's distinct row-tuples. A row fires when its tuple is (for {@code not in})
     * absent from / (for {@code in}) present in that set — exactly mirroring the single-column
     * {@code Primitives.membership} contract (an empty/absent reference set contains nothing, so
     * {@code not in} fires and {@code in} does not). A row whose tuple cell is not a list (never
     * the case for the {@code tuple} function) makes no membership decision and does not fire.
     */
    private static ExprProgram.BoolPlan compileTupleMembership(Expr.Call tupleCall, Expr right,
            boolean negate)
    {
        ValuePlan lhsPlan = operandPlan(tupleCall, true);
        Operation inlineSetOp = inlineSetOperation(right);
        return run ->
        {
            Object rhs;
            if (inlineSetOp != null)
            {
                rhs = inlineOperationResult(inlineSetOp, run.ctx());
            }
            else if (right instanceof Expr.Ref ref
                    && run.ctx().getVariables().containsKey(ref.name()))
            {
                rhs = run.ctx().resolveVariable(ref.name());
            }
            else
            {
                rhs = null;
            }
            Set<List<String>> tupleSet = toTupleSet(rhs);
            Vector lhs = lhsPlan.eval(run);
            BitSet result = new BitSet(run.rowCount());
            if (lhs == null)
            {
                return result;
            }
            for (int r = 0; r < run.rowCount(); r++)
            {
                List<String> rowTuple = toStringTuple(lhs.resolvedObject(r));
                if (rowTuple != null && negate != tupleSet.contains(rowTuple))
                {
                    result.set(r);
                }
            }
            return result;
        };
    }


    /**
     * Coerces an operation result into a {@code Set<List<String>>} of reference row-tuples (T3): a
     * {@link Collection} of {@link List} elements, each normalised to a {@code List<String>} (a
     * {@code null} element folds to {@code ""}). A {@code null} / non-collection / empty result is
     * the empty set (so {@code not in} fires and {@code in} does not — the single-column contract).
     */
    private static Set<List<String>> toTupleSet(@Nullable Object result)
    {
        if (!(result instanceof Collection<?> col))
        {
            return Set.of();
        }
        Set<List<String>> set = LinkedHashSet.newLinkedHashSet(col.size());
        for (Object element : col)
        {
            List<String> tuple = toStringTuple(element);
            if (tuple != null)
            {
                set.add(tuple);
            }
        }
        return set;
    }


    /**
     * Normalises a tuple cell (the {@code tuple} function's {@code List} cell, or a reference-set
     * {@code List} element) to a {@code List<String>} with {@code null} elements folded to
     * {@code ""}, or {@code null} when the value is not a {@link List}.
     */
    private static @Nullable List<String> toStringTuple(@Nullable Object value)
    {
        if (!(value instanceof List<?> list))
        {
            return null;
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list)
        {
            out.add(item == null ? "" : item.toString());
        }
        return out;
    }


    /**
     * Whether {@code e} is a list-valued metadata accessor call (e.g.
     * {@code var_codelist_coded_codes}) — its operand is a {@code List<String>} that membership
     * must compare element-wise (D4).
     */
    private static boolean isListAccessor(Expr e)
    {
        if (e instanceof Expr.Call call)
        {
            MetadataAttribute attr = MetadataAttribute.fromFunction(call.name());
            return attr != null && attr.isList();
        }
        return false;
    }


    /**
     * Whether {@code e} is a per-record VLM list-valued accessor ({@code vlm_codelist_coded_values}
     * / {@code vlm_codelist_coded_codes}) — its cell is a {@code List<String>} that varies per row,
     * so membership must rebuild the set for each row.
     */
    private static boolean isVlmListAccessor(Expr e)
    {
        return e instanceof Expr.Call call && ("vlm_codelist_coded_values".equals(call.name())
                || "vlm_codelist_coded_codes".equals(call.name()));
    }


    /**
     * Per-row membership against a VLM list accessor: for each row the RHS cell is the value-level
     * codelist {@code List<String>} that applies to that record. A row whose cell is not a list (no
     * matching value-level condition / no Define-XML) or whose probe value is missing makes no
     * decision and never fires — the native analog of Python's VLM builder emitting only rows that
     * have a matched value-level codelist and a populated value.
     */
    private static BitSet vlmListMembership(Vector probe, @Nullable Vector listVec, int rowCount,
            boolean negate, boolean caseInsensitive)
    {
        BitSet out = new BitSet();
        if (listVec == null)
        {
            return out;
        }
        for (int r = 0; r < rowCount; r++)
        {
            if (!(listVec.resolvedObject(r) instanceof List<?> list) || list.isEmpty())
            {
                // No matched value-level codelist (unmatched row, or a matched ItemDef that
                // declares
                // no codelist) ⇒ no codelist constraint applies ⇒ no decision.
                continue;
            }
            IDataValue dv = probe.dataValue(r);
            if (dv.isMissingOrInvalid())
            {
                continue;
            }
            Set<String> set = new LinkedHashSet<>();
            for (Object item : list)
            {
                if (item != null)
                {
                    set.add(fold(item.toString(), caseInsensitive));
                }
            }
            if (negate != set.contains(fold(dv.getValueAsString(), caseInsensitive)))
            {
                out.set(r);
            }
        }
        return out;
    }


    /**
     * The membership set for a list-valued metadata accessor on the RHS of
     * {@code in}/{@code not in}. The accessor materialises a {@code ConstVector} whose cell is the
     * variable's codelist {@code List<String>} (constant across the column under the per-(variable,
     * row) iteration), so the set is read from row 0. {@code null} / absent / non-list resolves to
     * the empty set — matching Python's {@code is_column_of_iterables} on an empty per-row list.
     */
    private static Set<String> listAccessorSet(ValuePlan rightP, EvalRun run,
            boolean caseInsensitive)
    {
        Set<String> set = new LinkedHashSet<>();
        Vector rv = rightP.eval(run);
        if (rv != null && run.rowCount() > 0 && rv.resolvedObject(0) instanceof List<?> list)
        {
            for (Object item : list)
            {
                if (item != null)
                {
                    set.add(fold(item.toString(), caseInsensitive));
                }
            }
        }
        return set;
    }


    /**
     * Per-row membership against a {@link GroupedResult}-valued {@code $}-reference — mirrors the
     * row-aware string-list resolution + {@code Primitives.membership} semantics: the row's group
     * value resolves via {@code getForRow} ({@code null} elements contribute the empty string, a
     * scalar is a singleton, an absent group is the empty set), a missing probe never fires, and
     * the probe and set fold case only on the case-insensitive surface.
     */
    private static BitSet groupedMembership(Vector v, GroupedResult grouped, EvalRun run,
            boolean negate, boolean caseInsensitive)
    {
        EvaluationContext ctx = run.ctx();
        // R-P7 review M3: candidate-aware via Primitives.scan so an unqualified foreign LHS gets
        // the legacy forEachJoinedValue ANY-MATCH semantics here too.
        return Primitives.scan(v, run.rowCount(), (dv, r) ->
        {
            // Empty-string literal fix: a missing LHS folds to "" and is probed literally.
            String cell = ScalarSemantics.isMissing(dv) ? "" : dv.getValueAsString();
            Object groupVal = grouped.getForRow(ctx, r);
            Set<String> set = new LinkedHashSet<>();
            if (groupVal instanceof Collection<?> col)
            {
                for (Object item : col)
                {
                    set.add(fold(item != null ? item.toString() : "", caseInsensitive));
                }
            }
            else if (groupVal != null)
            {
                set.add(fold(groupVal.toString(), caseInsensitive));
            }
            String probe = fold(cell, caseInsensitive);
            return negate != set.contains(probe);
        });
    }


    /**
     * Returns the parsed {@link OperandSubstitutor.Wildcard} when {@code right} is a {@code ${*}}
     * wildcard operand-substitution reference, else {@code null} (a scalar {@code ${VAR}}, a plain
     * reference, a literal list, or an unparseable placeholder all return {@code null} so the
     * constant-set membership path or the legacy fallback handles them).
     */
    private static OperandSubstitutor.@Nullable Wildcard wildcardOperand(Expr right)
    {
        if (!(right instanceof Expr.Ref ref) || ref.kind() != OperandKind.WILDCARD_COLUMN)
        {
            return null;
        }
        OperandSubstitutor.ParsedOperand parsed = parseScalarSubstitution(ref.name());
        return parsed instanceof OperandSubstitutor.Wildcard w ? w : null;
    }


    /**
     * Per-row {@code ${*}} membership plan. For each row the matching foreign/local column values
     * are collected (via {@code ValueResolver.resolveWildcardValues}, the same code the legacy
     * {@code is_(not_)contained_by} path runs) into a set, then the name-side cell is tested for
     * membership — per {@code evalIsContainedBy[CaseInsensitive]}: a missing name never fires, the
     * probe is upper-cased (and the set built upper-cased) only for the case-insensitive surface.
     *
     * <p>
     * A driver-free wildcard compiles its column-name pattern once (invariant across rows); a
     * driver-bearing wildcard re-derives the pattern per row from the row's driver cells. A
     * {@link OperandSubstitutor.SubstitutionException} on a driver-bearing pattern propagates,
     * matching the legacy {@code resolveStringList} path.
     * </p>
     */
    private static ExprProgram.BoolPlan wildcardMembershipPlan(ValuePlan nameP,
            OperandSubstitutor.Wildcard wild, boolean negate, boolean caseInsensitive)
    {
        Pattern compiledPattern = wild.hasDrivers() ? null : compileWildcardPattern(wild);
        return run ->
        {
            Vector v = nameP.eval(run);
            if (v == null)
            {
                return new BitSet();
            }
            EvaluationContext ctx = run.ctx();
            // R-P7 review M3: candidate-aware via Primitives.scan (foreign LHS → any-match OR).
            return Primitives.scan(v, run.rowCount(), (dv, r) ->
            {
                List<String> values = net.cumba.cdisc.core.exec.ValueResolver
                        .resolveWildcardValues(wild, compiledPattern, ctx, r);
                Set<String> set = LinkedHashSet.newLinkedHashSet(values.size());
                for (String s : values)
                {
                    set.add(caseInsensitive ? s.toUpperCase(Locale.ROOT) : s);
                }
                // Empty-string literal fix: a missing cell folds to "" and is probed literally.
                String cell = ScalarSemantics.isMissing(dv) ? "" : dv.getValueAsString();
                String probe = caseInsensitive ? cell.toUpperCase(Locale.ROOT) : cell;
                return negate != set.contains(probe);
            });
        };
    }


    /**
     * Compiles the anchored column-name {@link Pattern} for a driver-free wildcard once. Mirrors
     * wildcard-pattern caching: {@code toColumnPattern} only consults the context for driver
     * substitution, so a {@code null} context / row 0 is safe here.
     */
    private static Pattern compileWildcardPattern(OperandSubstitutor.Wildcard wild)
    {
        return OperandSubstitutor.toColumnPattern(wild, null, 0L);
    }


    private static boolean isUpperCall(Expr e)
    {
        return e instanceof Expr.Call c && "upper".equals(c.name()) && c.args().size() == 1
                && c.kwargs().isEmpty();
    }


    private static ExprProgram.BoolPlan compileComparison(Expr.Binary b)
    {
        // str(A) ==/!= str(B): a type-insensitive equality (the type_insensitive operator-leaf
        // surface). Both operands are coerced to strings before comparison — matching the legacy
        // CheckEvaluator path. Only the symmetric ==/!= form is supported (mirrors ExprLowering).
        if ((b.op() == Expr.BinOp.EQ || b.op() == Expr.BinOp.NEQ) && isStr(b.left())
                && isStr(b.right()))
        {
            return compileTypeInsensitiveEquality(b);
        }

        String lt = tagOf(b.left());
        String rt = tagOf(b.right());
        Expr li = untag(b.left());
        Expr ri = untag(b.right());

        // Unified boolean surface: a boolean callable compared to true/false (or to another boolean
        // callable) is a boolean condition, not a value comparison. `f() == true` behaves as `f()`,
        // `f() == false` as `not f()` (true = all-1 vector, false = all-0), realised at the
        // BoolPlan level so it works for EVERY boolean callable — registered functions, the
        // hard-coded *exists*/group operators, and any inline boolean operation admitted by the
        // unified-surface gate (currently none; domain_is_custom is library-dependent and
        // excluded).
        // A value operand (a plain column, a value function) is unaffected.
        if (b.op() == Expr.BinOp.EQ || b.op() == Expr.BinOp.NEQ)
        {
            if (isBoolLiteral(ri) && isBooleanCall(li))
            {
                return boolEqLiteral(compileBool(li), boolLiteralOf(ri), b.op());
            }
            if (isBoolLiteral(li) && isBooleanCall(ri))
            {
                return boolEqLiteral(compileBool(ri), boolLiteralOf(li), b.op());
            }
            if (isBooleanCall(li) && isBooleanCall(ri))
            {
                return boolEqBool(compileBool(li), compileBool(ri), b.op());
            }
        }

        String family = family(lt, rt);
        // Phase 8a: a num()-tagged operand on either side forces numeric-mode equality.
        boolean numTag = "num".equals(lt) || "num".equals(rt);
        Expr.BinOp op = b.op();

        // EC-43: one shared target plan for EQ/NEQ, all four ORDER ops and the date families.
        ValuePlan leftP = operandPlan(li, true, true);
        // Affix-compare RHS (Phase 5): both EQ and NEQ resolve the RHS through the generic
        // valuePlan (value position), so `prefix(X,2) == REF` reads REF as a per-row column /
        // $-var identical to the != form and to plain ==. A quoted-literal RHS (`== "FA"`) still
        // folds to a ConstVector via valuePlan, matching the converter's emitted form, so existing
        // converted-rule parity is preserved.
        ValuePlan rightP = valuePlan(ri);

        // EC-49 (Fix #148): an affix NEQ fires on an empty left operand, exactly like every other
        // negative leaf. The `Primitives.nonEmpty(lv)` mask that used to sit here was the engine's
        // last operator-level exception to the absent-column contract, and both justifications
        // written for it were false — see register §45. `non_empty(X)` is the author's opt-out, and
        // it now actually does something for this family.
        //
        // The interception itself SURVIVES the mask: family() falls back to the RIGHT operand's
        // type tag, so without this branch `prefix(X, 4) != date(D)` would route into compileDate.
        // That would be a type-dispatch change, which this is not.
        //
        // Fix #149: the forceNumeric argument is `numTag`, not the hard-coded `false` Fix #148
        // inherited from the masked branch. prefix/suffix are char-in, char-out — a number is
        // coerced to text BEFORE the cut — so `prefix(num(X), 2) != Y` is and stays a STRING
        // comparison (tagOf only matches a ONE-argument tag call, so an affix call carries no tag
        // and numTag is false there). The way an author asks for a numeric verdict is to tag the
        // comparison instead: `num(prefix(X, 2)) != Y` or `prefix(X, 2) != num(Y)`. untag() exposes
        // the inner affix call to isAffixCall, so those are exactly the shapes that reached this
        // branch and had their num() thrown away — the idiom the tag exists for was the one broken.
        // EQ never had the defect (it falls through to the switch below, which already passes
        // numTag), so this also makes the affix EQ/NEQ pair complementary again.
        if (op == Expr.BinOp.NEQ && isAffixCall(li))
        {
            return compilePlain(op, leftP, rightP, numTag);
        }

        return switch (family)
        {
        case "date" -> compileDate(op, leftP, rightP);
        case "date_part" -> compileDatePart(op, leftP, rightP, false);
        case "time_part" -> compileDatePart(op, leftP, rightP, true);
        default -> compilePlain(op, leftP, rightP, numTag);
        };
    }


    /** Whether {@code e} is a {@code true}/{@code false} literal operand. */
    private static boolean isBoolLiteral(Expr e)
    {
        return e instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.BOOL;
    }


    /**
     * The primitive value of a {@code true}/{@code false} literal (caller has checked it is one).
     */
    private static boolean boolLiteralOf(Expr e)
    {
        return (boolean) ((Expr.Lit) e).value();
    }


    /**
     * Whether {@code e} is a boolean-valued call usable as a condition: a registered
     * {@link FunctionKind#BOOLEAN} function, a {@link #HARDCODED_BOOLEAN_CALLS} dispatch name, or
     * an inline boolean operation admitted by {@link #isUnifiableBooleanOperation} (currently none
     * — {@code domain_is_custom} is library-dependent and excluded). A value operand (column ref,
     * value function) returns {@code false} so its {@code == true/false} comparison keeps the
     * ordinary value-equality semantics.
     */
    private static boolean isBooleanCall(Expr e)
    {
        if (!(e instanceof Expr.Call c))
        {
            return false;
        }
        if (isInlineOperation(c))
        {
            return isUnifiableBooleanOperation(OperationType.fromJson(c.name()));
        }
        FunctionDescriptor d = FunctionRegistry.descriptor(c.name(), c.args().size());
        if (d != null)
        {
            return d.kind() == FunctionKind.BOOLEAN;
        }
        return HARDCODED_BOOLEAN_CALLS.contains(c.name());
    }


    /**
     * {@code <bool> == true} / {@code <bool> != false} are the boolean plan itself;
     * {@code <bool> ==
     * false} / {@code <bool> != true} are its complement.
     */
    private static ExprProgram.BoolPlan boolEqLiteral(ExprProgram.BoolPlan bp, boolean literal,
            Expr.BinOp op)
    {
        boolean isEquals = op == Expr.BinOp.EQ;
        // identity for `== true` / `!= false`; complement for `== false` / `!= true`.
        return isEquals == literal ? bp : invert(bp);
    }


    /**
     * {@code <bool> == <bool>} is the per-row XNOR (rows where the two verdicts agree); {@code !=}
     * is the XOR (rows where they differ).
     */
    private static ExprProgram.BoolPlan boolEqBool(ExprProgram.BoolPlan left,
            ExprProgram.BoolPlan right, Expr.BinOp op)
    {
        boolean wantEqual = op == Expr.BinOp.EQ;
        return run ->
        {
            BitSet result = (BitSet) left.eval(run).clone();
            result.xor(right.eval(run));
            if (wantEqual)
            {
                result.flip(0, run.rowCount());
            }
            return result;
        };
    }


    /** A {@code prefix(x, n)} / {@code suffix(x, n)} affix-substring call (two args, no kwargs). */
    private static boolean isAffixCall(Expr e)
    {
        return e instanceof Expr.Call c && c.kwargs().isEmpty() && c.args().size() == 2
                && ("prefix".equals(c.name()) || "suffix".equals(c.name()));
    }


    /** A {@code str(X)} type-tag wrapper (one argument, no kwargs). */
    private static boolean isStr(Expr e)
    {
        return e instanceof Expr.Call c && c.kwargs().isEmpty() && c.args().size() == 1
                && "str".equals(c.name());
    }


    private static ExprProgram.BoolPlan compileTypeInsensitiveEquality(Expr.Binary b)
    {
        boolean negate = b.op() == Expr.BinOp.NEQ;
        ValuePlan leftP = operandPlan(((Expr.Call) b.left()).args().get(0), true, true);
        ValuePlan rightP = valuePlan(((Expr.Call) b.right()).args().get(0));
        return run ->
        {
            Vector lv = leftP.eval(run);
            Vector rv = rightP.eval(run);
            if (lv == null || rv == null)
            {
                return new BitSet();
            }
            return Primitives.equality(lv, rv, run.rowCount(), negate, false, true, false);
        };
    }


    private static ExprProgram.BoolPlan compileDate(Expr.BinOp op, ValuePlan leftP,
            ValuePlan rightP)
    {
        int direction = dateDirection(op);
        boolean orEqual = dateOrEqual(op);
        boolean negate = op == Expr.BinOp.NEQ;
        return run ->
        {
            Vector lv = leftP.eval(run);
            Vector rv = rightP.eval(run);
            if (lv == null || rv == null)
            {
                return new BitSet();
            }
            return Primitives.dateComparison(lv, rv, run.rowCount(), direction, orEqual, negate);
        };
    }


    private static ExprProgram.BoolPlan compileDatePart(Expr.BinOp op, ValuePlan leftP,
            ValuePlan rightP, boolean isTimePart)
    {
        if (op != Expr.BinOp.EQ && op != Expr.BinOp.NEQ)
        {
            throw unsupported("date_part/time_part comparisons support only == and !=");
        }
        boolean negate = op == Expr.BinOp.NEQ;
        return run ->
        {
            Vector lv = leftP.eval(run);
            Vector rv = rightP.eval(run);
            if (lv == null || rv == null)
            {
                return new BitSet();
            }
            return Primitives.datePartComparison(lv, rv, run.rowCount(), isTimePart, negate);
        };
    }


    private static ExprProgram.BoolPlan compilePlain(Expr.BinOp op, ValuePlan leftP,
            ValuePlan rightP, boolean forceNumeric)
    {
        if (op == Expr.BinOp.EQ || op == Expr.BinOp.NEQ)
        {
            boolean negate = op == Expr.BinOp.NEQ;
            return run ->
            {
                Vector lv = leftP.eval(run);
                Vector rv = rightP.eval(run);
                if (lv == null || rv == null)
                {
                    return new BitSet();
                }
                return Primitives.equality(lv, rv, run.rowCount(), negate, false, false,
                        forceNumeric);
            };
        }
        int direction = numDirection(op);
        boolean orEqual = op == Expr.BinOp.GE || op == Expr.BinOp.LE;
        return run ->
        {
            Vector lv = leftP.eval(run);
            Vector rv = rightP.eval(run);
            if (lv == null || rv == null)
            {
                return new BitSet();
            }
            return Primitives.comparison(lv, rv, run.rowCount(), direction, orEqual);
        };
    }


    private static ExprProgram.BoolPlan compileBoolCall(Expr.Call c)
    {
        String name = c.name();
        int arity = c.args().size();
        if (("exists".equals(name) || "not_exists".equals(name)) && arity == 1)
        {
            // Owner ruling 1 (PLAN-leaf-scope-domain-inference.md): the Rule_Type-dependent generic
            // presence operators are retired. The loader rejects them first
            // (RulePackageLoader.GENERIC_PRESENCE_OPERATORS); this arm is the same verdict for a
            // rule that reaches the compiler by another path (RuleGenerator, a direct API caller).
            throw new RuleDefinitionException("the generic presence operator '" + name
                    + "' was retired — spell the fact the rule means: var_exists(X) /"
                    + " var_not_exists(X) for column presence, ds_exists(X) / ds_not_exists(X)"
                    + " for dataset presence");
        }
        if (("ds_exists".equals(name) || "ds_not_exists".equals(name)) && arity == 1)
        {
            return compileExists(c, "ds_not_exists".equals(name), ExistsMode.DATASET);
        }
        if (("var_exists".equals(name) || "var_not_exists".equals(name)) && arity == 1)
        {
            return compileExists(c, "var_not_exists".equals(name), ExistsMode.VARIABLE);
        }
        if ("var_is_null".equals(name) && arity == 1)
        {
            return compileVarIsNull(c);
        }
        if ("invalid_duration".equals(name) && arity == 1)
        {
            // EC-22: the lowering emits invalid_duration(NAME, negative=<bool>) but the generic
            // boolean tail neither consumes nor rejects kwargs, so the kwarg was silently dropped
            // and the arity-1 builtin hardwired a default. Parse the negative= boolean-literal
            // kwarg here and route to Primitives.invalidDuration so the native lane matches the
            // legacy operator (and Python). Absent ⇒ DEFAULT_NEGATIVE (EC-20 alignment).
            boolean allowNegative = DEFAULT_NEGATIVE;
            Expr neg = c.kwargs().get("negative");
            if (neg != null)
            {
                if (!(neg instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.BOOL)
                {
                    throw unsupported("invalid_duration negative= must be a boolean literal");
                }
                allowNegative = (Boolean) lit.value();
            }
            ValuePlan operand = operandPlan(c.args().get(0), true, true);
            boolean allow = allowNegative;
            return run ->
            {
                Vector v = operand.eval(run);
                return v == null ? new BitSet()
                        : Primitives.invalidDuration(v, run.rowCount(), allow);
            };
        }
        if ("does_not_equal_string_part".equals(name))
        {
            return compileStringPart(c);
        }
        if ("has_not_equal_length".equals(name))
        {
            return compileLengthNotEqual(c);
        }
        if ("has_equal_length".equals(name))
        {
            return compileLengthEquality(c, false);
        }
        if ("has_multiple_values_for".equals(name))
        {
            return compileHasMultipleValuesFor(c);
        }
        if ("present_on_multiple_rows_within".equals(name))
        {
            return compileMultipleRowsWithin(c, true);
        }
        if ("empty_within_except_last_row".equals(name))
        {
            return compileEmptyWithinExceptLastRow(c);
        }
        if ("is_not_unique_relationship".equals(name))
        {
            return compileNotUniqueRelationship(c);
        }
        if ("is_not_unique_set".equals(name))
        {
            return compileUniqueSet(c, true);
        }
        if ("is_unique_set".equals(name))
        {
            return compileUniqueSet(c, false);
        }
        // Task ED: is_not_unique_value(X) is the single-column tuple-uniqueness check — exactly
        // is_not_unique_set(X) with no keys (flags rows whose X value is duplicated). Its positive
        // twin is_unique_value(X) is the logical complement (change #1, like the Task EA
        // positives).
        if ("is_not_unique_value".equals(name))
        {
            return compileUniqueSet(c, true);
        }
        if ("is_unique_value".equals(name))
        {
            return invert(compileUniqueSet(c, true));
        }
        if ("is_inconsistent_across_dataset".equals(name))
        {
            return compileInconsistentAcrossDataset(c);
        }
        if ("inconsistent_enumerated_columns".equals(name))
        {
            return compileInconsistentEnumeratedColumns(c);
        }
        if ("not_contains_all".equals(name))
        {
            return compileNotContainsAll(c);
        }
        if ("has_same_values".equals(name))
        {
            return compileHasSameValues(c);
        }
        if ("shares_no_elements_with".equals(name))
        {
            return compileSharesNoElementsWith(c);
        }
        if ("is_not_ordered_subset_of".equals(name))
        {
            return compileIsNotOrderedSubsetOf(c);
        }
        // The four positive group functions (change #1): each is the logical complement of the
        // existing negative operator plan above (operand resolution reused unchanged). The
        // converter emits not <positive>(…), which double-inverts back to the negative; a bare
        // <positive>(…) is the natural positive.
        if ("is_unique_relationship".equals(name))
        {
            return invert(compileNotUniqueRelationship(c));
        }
        if ("contains_all".equals(name))
        {
            return invert(compileNotContainsAll(c));
        }
        if ("shares_elements_with".equals(name))
        {
            return invert(compileSharesNoElementsWith(c));
        }
        if ("is_ordered_subset_of".equals(name))
        {
            return invert(compileIsNotOrderedSubsetOf(c));
        }
        if (isInlineOperation(c))
        {
            // §9.B / unified surface: a non-library boolean-valued inline operation compiles in
            // boolean position by bridging its broadcast result to a verdict BitSet. A non-boolean
            // operation (variable_count, …) is not a condition; a library-dependent boolean op
            // (domain_is_custom) is excluded — its LIBRARY_NOT_AVAILABLE sentinel under a
            // `not`/`invert` would mis-fire every row when no Library is configured (the §9.C
            // skip-gate exists only on converted Form-B rules, not an inline Form-A use), so it
            // stays on the operand-position `== true/false` path. ⚠ The set admitted here is
            // whatever isBooleanValued minus isLibraryDependent yields — today variable_is_null and
            // variable_exists; do not re-derive it from a comment (this one claimed "no operation"
            // while variable_is_null was already admitted).
            if (isUnifiableBooleanOperation(OperationType.fromJson(name)))
            {
                return valueAsBool(operationCallPlan(c));
            }
            throw unsupported("operation '" + name + "' is not a boolean condition");
        }
        FunctionDescriptor descriptor = FunctionRegistry.descriptor(name, arity);
        if (descriptor == null)
        {
            throw unsupported("no native function '" + name + "' with " + arity + " argument(s)");
        }
        if (descriptor.kind() != FunctionKind.BOOLEAN)
        {
            throw unsupported("value function '" + name + "' is not a boolean condition");
        }
        EvalFunction fn = descriptor.fn();
        if (arity == 0)
        {
            // Arity-0 boolean builtin (e.g. §9.C library_available()): no operand to evaluate.
            return run -> (BitSet) fn.apply(run, List.of(), c.kwargs());
        }
        // EC-43 (T1 reconciliation): every registered BOOLEAN predicate folds an absent operand to
        // all-missing and computes its own verdict over it — is_integer("") is false, contains("")
        // is false, matches_regex "^$" is true. `empty`/`is_missing` are the ONE exclusion: they
        // own
        // their absent-column contract via FIRES_ON_ABSENT_COLUMN below (class E, the plain-vs-dot-
        // qualified asymmetry Fix #126 built), and folding them would produce the identical verdict
        // by a different route — so the special case is kept rather than quietly replaced.
        boolean firesOnAbsentColumn = FIRES_ON_ABSENT_COLUMN.contains(name);
        ValuePlan arg0 = operandPlan(c.args().get(0), true, !firesOnAbsentColumn);
        List<ValuePlan> rest = new ArrayList<>(Math.max(0, arity - 1));
        boolean literalArg1 = LITERAL_ARG1.contains(name);
        for (int i = 1; i < arity; i++)
        {
            Expr arg = c.args().get(i);
            rest.add(literalArg1 && i == 1 ? regexLiteralPlan(name, arg) : valuePlan(arg));
        }
        return run ->
        {
            Vector v0 = arg0.eval(run);
            if (v0 == null)
            {
                if (firesOnAbsentColumn)
                {
                    BitSet all = new BitSet(run.rowCount());
                    all.set(0, run.rowCount());
                    return all;
                }
                return new BitSet();
            }
            List<Vector> args = new ArrayList<>(arity);
            args.add(v0);
            for (ValuePlan p : rest)
            {
                args.add(p.eval(run));
            }
            return (BitSet) fn.apply(run, args, c.kwargs());
        };
    }


    /**
     * Native plan for {@code does_not_equal_string_part(NAME, VALUE, regex="…")}: per row, extracts
     * capture group&nbsp;1 from the resolved {@code VALUE} via the {@code regex=} kwarg and fires
     * where {@code NAME} differs from it. Routes the verdict through
     * {@link net.cumba.cdisc.core.exec.ScalarSemantics#differsFromStringPart}, the same helper the
     * legacy operator now calls.
     */
    private static ExprProgram.BoolPlan compileStringPart(Expr.Call c)
    {
        Expr regexExpr = c.kwargs().get("regex");
        if (!(regexExpr instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.STRING)
        {
            throw unsupported("does_not_equal_string_part requires a regex= string literal");
        }
        if (c.args().size() < 2)
        {
            throw unsupported("does_not_equal_string_part requires a value operand");
        }
        Pattern pattern = Pattern.compile((String) lit.value());
        ValuePlan nameP = operandPlan(c.args().get(0), true, true);
        ValuePlan valueP = valuePlan(c.args().get(1));
        return run ->
        {
            Vector nameV = nameP.eval(run);
            Vector valueV = valueP.eval(run);
            if (nameV == null || valueV == null)
            {
                return new BitSet();
            }
            // R-P7 review M3: candidate-aware via Primitives.scan (foreign LHS → any-match OR).
            return Primitives.scan(nameV, run.rowCount(), (dv, r) ->
            {
                Object target = valueV.resolvedObject(r);
                if (target == null)
                {
                    return false;
                }
                // Literal LHS: a missing cell folds to "" (B.5). The both-empty corner is a
                // deliberate, whitelisted divergence from Python (no gate; see operator-examples.md
                // B.5).
                String lhs = ScalarSemantics.isMissing(dv) ? "" : dv.getValueAsString();
                // D5: a numeric-literal VALUE operand is auto-stringified canonically (100.0 ->
                // "100") before the regex extracts its part — a String operand is unchanged.
                String targetText = target instanceof Number n ? canonicalNumberText(n)
                        : target.toString();
                return ScalarSemantics.differsFromStringPart(lhs, targetText, pattern);
            });
        };
    }


    /**
     * Native plan for the {@code has_not_equal_length(NAME, len)} surface: fires where the cell's
     * string length differs from the integer literal {@code len} (legacy {@code resolveIntValue}
     * truncates, so does the cast). Missing ⇒ no violation.
     */
    private static ExprProgram.BoolPlan compileLengthNotEqual(Expr.Call c)
    {
        return compileLengthEquality(c, true);
    }


    /**
     * Shared plan for {@code has_equal_length} ({@code negate == false}) and
     * {@code has_not_equal_length} ({@code true}): string-length (in)equality against a numeric
     * literal; missing ⇒ no violation on BOTH polarities (the legacy operators' contract).
     */
    private static ExprProgram.BoolPlan compileLengthEquality(Expr.Call c, boolean negate)
    {
        if (c.args().size() < 2)
        {
            throw unsupported(c.name() + " requires a length operand");
        }
        // Phase 3: the length is a per-row operand read via the shared exact-integer integral —
        // a numeric/string literal ("5"), a numeric column, or a char column parsing to an int.
        // A missing / non-integral length folds to 0 (legacy asInt parity).
        ValuePlan nameP = operandPlan(c.args().get(0), true, true);
        ValuePlan lenP = valuePlan(c.args().get(1));
        return run ->
        {
            Vector v = nameP.eval(run);
            Vector lenV = lenP.eval(run);
            if (v == null || lenV == null)
            {
                return new BitSet();
            }
            return Primitives.lengthEquality(v, lenV, run.rowCount(), negate);
        };
    }


    /**
     * Native plan for {@code has_multiple_values_for(NAME, KEY, within=…, include_empty=…)}:
     * partitions rows by the {@code within} key tuple (or treats the whole dataset as one group
     * when absent) and fires every row whose {@code KEY} value maps to more than one distinct
     * {@code NAME} value within its group. Routes both the partitioning and the per-group
     * functional-dependency check through the shared {@link GroupSemantics}, so the result is
     * bit-for-bit identical to the legacy operator. {@code include_empty=true} (Fix #121,
     * Java-only, default {@code false}) disables the D.13 emptiness-exclusion so a blank key or
     * dependent participates as a real value. Operands that are not plain columns (wildcard /
     * {@code ${…}} / {@code $}-op) are declined (the deferred Phase-N operand-resolution residual).
     */
    private static ExprProgram.BoolPlan compileHasMultipleValuesFor(Expr.Call c)
    {
        if (c.args().size() < 2)
        {
            throw unsupported("has_multiple_values_for requires a name and a key operand");
        }
        String rawName = groupOperandName(c.args().get(0));
        String rawKey = groupOperandName(c.args().get(1));
        List<List<String>> rawWithin = withinComponents(c.kwargs().get("within"));
        boolean includeEmpty = includeEmptyKwarg(c);
        GroupKeyPolicy policy = groupKeyPolicy(c, GroupKeyPolicy.DROP_MISSING_KEYS);
        return run ->
        {
            EvaluationContext ctx = run.ctx();
            String name = resolveDomainPrefix(rawName, ctx);
            String key = resolveDomainPrefix(rawKey, ctx);
            List<List<String>> withinComps = resolveDomainPrefixComponents(rawWithin, ctx);
            IDataTable table = ctx.getTable();
            DataTableMeta meta = table.getMetaData();
            int nameIdx = meta.getColumnIndex(name);
            int keyIdx = meta.getColumnIndex(key);
            if (nameIdx < 0 || keyIdx < 0)
            {
                return new BitSet();
            }
            IDataTableColumn nameCol = table.getColumn(nameIdx);
            IDataTableColumn keyCol = table.getColumn(keyIdx);
            int rowCount = run.rowCount();
            if (withinComps.isEmpty())
            {
                return GroupSemantics.hasMultipleValuesForRows(nameCol, keyCol, i -> i, rowCount,
                        includeEmpty);
            }
            List<int[]> groups = GroupSemantics.partitionCoalesced(table, withinComps, policy);
            BitSet result = new BitSet(rowCount);
            for (int[] g : groups)
            {
                BitSet gr = GroupSemantics.hasMultipleValuesForRows(nameCol, keyCol, i -> g[i],
                        g.length, includeEmpty);
                for (int i = gr.nextSetBit(0); i >= 0; i = gr.nextSetBit(i + 1))
                {
                    result.set(g[i]);
                }
            }
            return result;
        };
    }


    /**
     * Native plan for {@code present_on_multiple_rows_within(NAME, within=COL)}
     * ({@code flagMultiple
     * == true}) and the Q1-spelled {@code not present_on_multiple_rows_within(…)}
     * ({@code flagMultiple == false}, the {@code not_present_on_multiple_rows_within} surface).
     * Partitions on the composite {@code (within, NAME)} key and flags rows by group size through
     * the shared {@link GroupSemantics}. Single-column {@code within} only (the legacy contract);
     * anything else is declined to the legacy no-op.
     */
    private static ExprProgram.BoolPlan compileMultipleRowsWithin(Expr.Call c, boolean flagMultiple)
    {
        if (c.args().isEmpty())
        {
            throw unsupported("present_on_multiple_rows_within requires a name operand");
        }
        String rawName = groupOperandName(c.args().get(0));
        List<String> within = withinColumns(c.kwargs().get("within"));
        if (within.size() != 1)
        {
            throw unsupported("present_on_multiple_rows_within requires a single within column");
        }
        String rawWithinCol = within.get(0);
        GroupKeyPolicy policy = groupKeyPolicy(c, GroupKeyPolicy.DROP_MISSING_KEYS);
        return run ->
        {
            String name = resolveDomainPrefix(rawName, run.ctx());
            String withinCol = resolveDomainPrefix(rawWithinCol, run.ctx());
            IDataTable table = run.ctx().getTable();
            DataTableMeta meta = table.getMetaData();
            // EC-44 (Fix #134): an absent `within` is dropped by partition() and the composite
            // key falls back to NAME alone — "this NAME value appears on more than one row",
            // which is what "within" means once nothing can tell the rows apart.
            //
            // NAME still bails, though it is a partition column too (flagGroupsBySize reads only
            // group SIZES, never a cell). The reason is not "nothing to read" but that NAME is the
            // SUBJECT of the assertion — the value whose repetition is being counted. With NAME
            // absent every row would share the same empty NAME, and the operator would report that
            // a variable the study never collected "is present on multiple rows", which is not a
            // coarser answer to the same question but a different and meaningless one.
            if (meta.getColumnIndex(name) < 0)
            {
                return new BitSet();
            }
            List<int[]> groups = GroupSemantics.group(table, List.of(withinCol, name), policy);
            BitSet result = new BitSet(run.rowCount());
            GroupSemantics.flagGroupsBySize(groups, flagMultiple, result);
            return result;
        };
    }


    /**
     * Native plan for {@code empty_within_except_last_row(NAME, GROUP, ordering=ORDER)}: partitions
     * by the {@code GROUP} column, orders each group by {@code ORDER}, and fires every row except
     * the last whose {@code NAME} is missing/empty — via the shared {@link GroupSemantics}.
     */
    private static ExprProgram.BoolPlan compileEmptyWithinExceptLastRow(Expr.Call c)
    {
        if (c.args().size() < 2)
        {
            throw unsupported("empty_within_except_last_row requires a name and a group operand");
        }
        String rawName = groupOperandName(c.args().get(0));
        String rawGroup = groupOperandName(c.args().get(1));
        String rawOrder = orderingColumn(c);
        GroupKeyPolicy policy = groupKeyPolicy(c, GroupKeyPolicy.DROP_MISSING_KEYS);
        return run ->
        {
            String name = resolveDomainPrefix(rawName, run.ctx());
            String group = resolveDomainPrefix(rawGroup, run.ctx());
            String order = resolveDomainPrefix(rawOrder, run.ctx());
            IDataTable table = run.ctx().getTable();
            DataTableMeta meta = table.getMetaData();
            int nameIdx = meta.getColumnIndex(name);
            int orderIdx = meta.getColumnIndex(order);
            // EC-44 (Fix #134): the subject (`name`) and ordering columns still bail when absent —
            // there is nothing to read or order by — but an absent `group` is dropped by
            // partition(), which then treats the whole dataset as one group.
            if (nameIdx < 0 || orderIdx < 0)
            {
                return new BitSet();
            }
            IDataTableColumn nameCol = table.getColumn(nameIdx);
            IDataTableColumn orderCol = table.getColumn(orderIdx);
            List<int[]> groups = GroupSemantics.group(table, List.of(group), policy);
            BitSet result = new BitSet(run.rowCount());
            for (int[] rows : groups)
            {
                GroupSemantics.flagEmptyExceptLastRow(nameCol, orderCol, rows, result);
            }
            return result;
        };
    }


    /**
     * Native plan for
     * {@code has_next_corresponding_record(NAME, VALUE, within=COL, ordering=ORDER[, relation=])} —
     * emitted only under {@code not} (the {@code does_not_have_next_corresponding_record} surface,
     * Q1). Partitions by {@code within}, orders each group by {@code ORDER}, and fires every row
     * (except the last) whose {@code NAME} does not <em>correspond</em> to the next ordered row's
     * {@code VALUE}, via the shared {@link GroupSemantics}. Correspondence is {@code KeyPart}
     * identity (since {@code W38-A1} / Fix #249 a genuine missing corresponds only to the same
     * missing marker, {@code ""} to {@code ""}), widened by the optional {@code relation=}
     * comparison — see {@link #neighbourRelation}. ⚠ {@code keep_missings=} governs only the
     * {@code within} partition, never the compared cells.
     */
    private static ExprProgram.BoolPlan compileNextCorrespondingRecord(Expr.Call c)
    {
        if (c.args().size() < 2)
        {
            throw unsupported("has_next_corresponding_record requires a name and a value operand");
        }
        String rawName = groupOperandName(c.args().get(0));
        String rawValue = groupOperandName(c.args().get(1));
        List<String> within = withinColumns(c.kwargs().get("within"));
        if (within.size() != 1)
        {
            throw unsupported("has_next_corresponding_record requires a single within column");
        }
        String rawWithinCol = within.get(0);
        String rawOrder = orderingColumn(c);
        GroupKeyPolicy policy = groupKeyPolicy(c, GroupKeyPolicy.DROP_MISSING_KEYS);
        GroupSemantics.NeighbourRelation relation = neighbourRelation(c);
        return run ->
        {
            String name = resolveDomainPrefix(rawName, run.ctx());
            String value = resolveDomainPrefix(rawValue, run.ctx());
            String withinCol = resolveDomainPrefix(rawWithinCol, run.ctx());
            String order = resolveDomainPrefix(rawOrder, run.ctx());
            IDataTable table = run.ctx().getTable();
            DataTableMeta meta = table.getMetaData();
            int nameIdx = meta.getColumnIndex(name);
            int valueIdx = meta.getColumnIndex(value);
            int orderIdx = meta.getColumnIndex(order);
            // EC-44 (Fix #134): subject / value / ordering columns still bail when absent; an
            // absent `within` is dropped by partition() and the whole dataset becomes one group.
            if (nameIdx < 0 || valueIdx < 0 || orderIdx < 0)
            {
                return new BitSet();
            }
            IDataTableColumn nameCol = table.getColumn(nameIdx);
            IDataTableColumn valueCol = table.getColumn(valueIdx);
            IDataTableColumn orderCol = table.getColumn(orderIdx);
            List<int[]> groups = GroupSemantics.group(table, List.of(withinCol), policy);
            BitSet result = new BitSet(run.rowCount());
            for (int[] rows : groups)
            {
                GroupSemantics.flagNoNextCorrespondingRecord(nameCol, valueCol, orderCol, rows,
                        result, relation);
            }
            return result;
        };
    }


    /**
     * EC-87 — builds the {@code relation=} kwarg's neighbour relation. Absent (or {@code "=="}) ⇒
     * the shipped identity relation, and nothing else changes (D-2). {@code "<="} / {@code ">="} ⇒
     * identity <b>in disjunction with</b> the {@code date_*}-family comparison
     * ({@link Primitives#compareCells}, direction {@code -1} / {@code 1}, or-equal): a relation may
     * only widen what "corresponds", never narrow it (D-1) — so two month-precision {@code 2020-01}
     * cells, which the ∀-hull rule would call not-{@code <=}, still correspond by identity, and a
     * missing or blank cell keeps exactly its identity disposition because the comparison arm
     * answers {@code false} on it (D-3).
     *
     * <p>
     * The next record's cell is handed to {@code compareCells} in the shape
     * {@code Vector.resolvedObject} produces for a plain column
     * ({@code ScalarSemantics.resolvedString}): its string form, or {@code null} when
     * missing/invalid — so a numeric-typed pair still takes the numeric branch, as a same-row
     * {@code date_*} comparison would. ⚠ D-5: a <em>mixed</em> numeric/ISO pair does NOT correspond
     * ({@code mixedVerdict=false}) — the malformed shape stays reported, as it is today under
     * identity; the {@code date_*} operators' "fires regardless of direction" is the same
     * disposition read from the other side.
     * </p>
     * <p>
     * ⚠ The spelling is validated on the inline surface by {@code RulePackageLoader} as a load
     * error; the throw here is the declared-surface backstop and must never be reached by a shipped
     * rule.
     * </p>
     */
    private static GroupSemantics.NeighbourRelation neighbourRelation(Expr.Call c)
    {
        Expr e = c.kwargs().get("relation");
        if (e == null)
        {
            return GroupSemantics::identityCorresponds;
        }
        if (!(e instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.STRING)
        {
            throw unsupported(c.name() + " relation= must be a string literal");
        }
        String spelling = (String) lit.value();
        NextRecordRelation rel = NextRecordRelation.fromSpelling(spelling);
        if (rel == null)
        {
            throw unsupported(c.name() + " unknown relation= `" + spelling + "` — expected one of "
                    + NextRecordRelation.SPELLINGS);
        }
        if (rel == NextRecordRelation.IDENTITY)
        {
            return GroupSemantics::identityCorresponds;
        }
        int direction = rel.direction();
        return (cur,
                next) -> GroupSemantics.identityCorresponds(cur, next) || Primitives.compareCells(
                        cur, next.isMissingOrInvalid() ? null : next.getValueAsString(), direction,
                        true, false, false);
    }


    /**
     * Native plan for {@code is_sorted_by(TARGET, by=[asc/desc("col")…], within=COL)} — emitted
     * only under {@code not} (the {@code target_is_not_sorted_by} surface, Q1). Extracts the
     * target, the ordered sort-key column names from the {@code by=} descriptors (the asc/desc
     * direction is ignored, matching the legacy operator) and the optional single {@code within}
     * column, then delegates to the shared {@link GroupSemantics#targetIsNotSortedByViolations}.
     */
    private static ExprProgram.BoolPlan compileTargetIsNotSortedBy(Expr.Call c)
    {
        if (c.args().isEmpty())
        {
            throw unsupported("is_sorted_by requires the target column");
        }
        String rawTarget = groupOperandName(c.args().get(0));
        Expr by = c.kwargs().get("by");
        if (!(by instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.LIST)
        {
            throw unsupported("is_sorted_by requires a by=[…] descriptor list");
        }
        @SuppressWarnings("unchecked")
        List<Expr> descs = (List<Expr>) lit.value();
        List<String> rawSortVars = new ArrayList<>(descs.size());
        for (Expr d : descs)
        {
            if (!(d instanceof Expr.Call dc) || dc.args().size() != 1
                    || !(dc.args().get(0) instanceof Expr.Lit nameLit)
                    || nameLit.kind() != Expr.LitKind.STRING)
            {
                throw unsupported("is_sorted_by descriptor must be asc/desc(\"col\")");
            }
            rawSortVars.add((String) nameLit.value());
        }
        List<String> within = withinColumns(c.kwargs().get("within"));
        String rawWithinCol = within.size() == 1 ? within.get(0) : null;
        // ⚠ FOLD_BLANK_KEYS is this operator's SHIPPED default, and it is the questionable one for
        // an ordering operator — keeping blank keys chains one subject's last row to another's
        // first within each blank kind ("" its own bucket, each missing marker its own since
        // W38-A1 / Fix #249). It is preserved here so that correcting it stays an authoring
        // decision per rule.
        GroupKeyPolicy policy = groupKeyPolicy(c, GroupKeyPolicy.FOLD_BLANK_KEYS);
        return run ->
        {
            EvaluationContext ctx = run.ctx();
            return GroupSemantics.targetIsNotSortedByViolations(ctx.getTable(), run.rowCount(),
                    resolveDomainPrefix(rawTarget, ctx), resolveDomainPrefixes(rawSortVars, ctx),
                    rawWithinCol == null ? null : resolveDomainPrefix(rawWithinCol, ctx), policy);
        };
    }


    /**
     * The {@code ordering=} column name, declining when absent (the legacy operators require it).
     */
    private static String orderingColumn(Expr.Call c)
    {
        Expr ord = c.kwargs().get("ordering");
        if (ord == null)
        {
            throw unsupported(c.name() + " requires an ordering= column");
        }
        return groupOperandName(ord);
    }


    /**
     * Native plan for {@code is_not_unique_relationship(NAME, VALUE)}: flags rows whose NAME/VALUE
     * pair is not a 1:1 mapping, via the shared {@link GroupSemantics}.
     */
    private static ExprProgram.BoolPlan compileNotUniqueRelationship(Expr.Call c)
    {
        if (c.args().isEmpty())
        {
            throw unsupported("is_not_unique_relationship requires a name operand");
        }
        String rawName = groupOperandName(c.args().get(0));
        // The comparator side is every operand after the name: extra positional args plus a
        // keys=[…] list. The single-column form is_(not_)unique_relationship(NAME, VALUE) yields
        // one entry (backward-compatible with the original two-positional plan); the multi-column
        // form is_(not_)unique_relationship(NAME, keys=[A, B, …]) yields the whole key list, which
        // GroupSemantics treats as a value tuple (mirroring Python's list-comparator form).
        List<String> rawValues = keyColumns(c);
        if (rawValues.isEmpty())
        {
            throw unsupported(
                    "is_not_unique_relationship requires a value operand (positional or keys=[…])");
        }
        return run ->
        {
            String name = resolveDomainPrefix(rawName, run.ctx());
            List<String> values = resolveDomainPrefixes(rawValues, run.ctx());
            return GroupSemantics.relationshipNotUniqueViolations(run.ctx().getTable(),
                    run.rowCount(), name, values);
        };
    }


    /**
     * Native plan for {@code is_not_unique_set} ({@code flagDuplicates == true}) / {@code
     * is_unique_set} ({@code false}) and the single-column {@code is_(not_)unique_value} pair: the
     * key tuple is the member list ({@link #uniqueSetMembers}), checked for tuple (non-)uniqueness
     * via the shared {@link GroupSemantics}. No member is privileged — an absent or unresolved
     * member is dropped uniformly, wherever it sits (D-4).
     */
    private static ExprProgram.BoolPlan compileUniqueSet(Expr.Call c, boolean flagDuplicates)
    {
        List<String> rawMembers = uniqueSetMembers(c);
        // Optional regex= kwarg: Python is_unique_set normalizes each matching key column to its
        // first regex match before grouping (e.g. a datetime key grouped at date granularity).
        String regex = optionalRegexLiteral(c);
        // ⚠ FOLD_BLANK_KEYS: is_(not_)unique_set's shipped contract keeps "" and a genuine
        // missing as real key components (operator-examples.md D.1's keep axis) — since W38-A1 /
        // Fix #249 each blank kind is its own KeyPart identity, so an "" tuple and a
        // missing-marker tuple are no longer duplicates of one another.
        GroupKeyPolicy policy = groupKeyPolicy(c, GroupKeyPolicy.FOLD_BLANK_KEYS);
        return run ->
        {
            // $-ref members (e.g. $TIMING_VARIABLES from get_dataset_filtered_variables) resolve
            // only against the run context, so splice them to their underlying column lists here —
            // mirroring how record_count's group= is expanded by OperationExecutor.expandGroupRefs.
            List<String> members = expandRefKeys(rawMembers, run.ctx());
            return GroupSemantics.uniqueSetViolations(run.ctx().getTable(), run.rowCount(),
                    resolveDomainPrefixes(members, run.ctx()), regex, flagDuplicates, policy);
        };
    }


    /**
     * The member list of a uniqueness call, in authored order.
     *
     * <p>
     * {@code is_(not_)unique_value(X)} — the single operand <em>is</em> the whole tuple
     * ({@code List.of(X)}; D-5: never flattened, a list of one is noise).
     * </p>
     *
     * <p>
     * {@code is_(not_)unique_set([A, B, …])} — the canonical form since 2026-08-23 (owner
     * requirement #1): ONE positional LIST literal, each member a {@link #keyMemberName} (a column,
     * a {@code --}-wildcard, a string literal, or a {@code $}-ref passed through raw for
     * {@link #expandRefKeys}). ⚠ This legalises a {@code $}-ref in position 0, which
     * {@link #groupOperandName} refused on the old first operand — intended, D-4 drops an
     * unresolved member uniformly.
     * </p>
     *
     * <p>
     * ⛔ The pre-2026-08-23 spelling {@code f(A, keys=[…])} / {@code f(A, B)} / {@code f(A)} is not
     * accepted here. It is refused by {@code ExprLowering.functionOperatorLeaf} and rejected at
     * LOAD ({@code RulePackageLoader.validateInlineUniqueSetShape}) so the author gets an error,
     * not a degraded rule — the {@code unsupported(...)} on this path only degrades.
     * </p>
     */
    private static List<String> uniqueSetMembers(Expr.Call c)
    {
        if (c.args().isEmpty())
        {
            throw unsupported(c.name() + " requires a member operand");
        }
        if ("is_unique_value".equals(c.name()) || "is_not_unique_value".equals(c.name()))
        {
            if (c.args().size() != 1 || c.kwargs().containsKey("keys"))
            {
                throw unsupported(c.name() + " takes exactly one column operand");
            }
            return List.of(groupOperandName(c.args().get(0)));
        }
        if (c.args().size() != 1 || c.kwargs().containsKey("keys"))
        {
            throw unsupported(c.name() + " takes one list operand: " + c.name() + "([A, B, …])");
        }
        if (!(c.args().get(0) instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.LIST)
        {
            throw unsupported(c.name() + "'s operand must be a list literal");
        }
        @SuppressWarnings("unchecked")
        List<Expr> items = (List<Expr>) lit.value();
        if (items.isEmpty())
        {
            throw unsupported(c.name() + "([]) has no members");
        }
        List<String> out = new ArrayList<>(items.size());
        for (Expr item : items)
        {
            out.add(keyMemberName(item));
        }
        return out;
    }


    /** The {@code regex=} kwarg as a string literal, or {@code null} when absent / non-literal. */
    private static @Nullable String optionalRegexLiteral(Expr.Call c)
    {
        Expr regexExpr = c.kwargs().get("regex");
        if (regexExpr instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.STRING)
        {
            return (String) lit.value();
        }
        return null;
    }


    /**
     * Splices any {@code $}-reference key member to the column list it resolves to in {@code ctx}
     * (an operation result such as {@code $TIMING_VARIABLES} from
     * {@code get_dataset_filtered_variables}); a non-{@code $} member, and a {@code $}-ref that
     * does not resolve to a collection, passes through unchanged so the downstream column lookup
     * reports it. Kept symmetric with {@code OperationExecutor.expandGroupRefs} so the two cannot
     * drift.
     */
    private static List<String> expandRefKeys(List<String> rawKeys, EvaluationContext ctx)
    {
        List<String> out = new ArrayList<>(rawKeys.size());
        for (String k : rawKeys)
        {
            if (k.startsWith("$"))
            {
                Object resolved = ctx.resolveVariable(k);
                if (resolved instanceof Collection<?> col)
                {
                    for (Object item : col)
                    {
                        if (item != null)
                        {
                            out.add(item.toString());
                        }
                    }
                    continue;
                }
            }
            out.add(k);
        }
        return out;
    }


    /**
     * Native plan for {@code is_inconsistent_across_dataset(NAME, keys=[…], include_empty=…)}:
     * flags every row whose group (by the {@code keys} columns) holds more than one distinct NAME
     * value, via the shared {@link GroupSemantics}. {@code include_empty=true} (Fix #121,
     * Java-only, default {@code false}) disables the D.2 emptiness-exception so a blank NAME value
     * participates (folded to the canonical {@code ""}).
     */
    private static ExprProgram.BoolPlan compileInconsistentAcrossDataset(Expr.Call c)
    {
        if (c.args().isEmpty())
        {
            throw unsupported("is_inconsistent_across_dataset requires a name operand");
        }
        String rawName = groupOperandName(c.args().get(0));
        List<String> rawKeys = keyColumns(c);
        boolean includeEmpty = includeEmptyKwarg(c);
        GroupKeyPolicy policy = groupKeyPolicy(c, GroupKeyPolicy.DROP_MISSING_KEYS);
        return run -> GroupSemantics.inconsistentAcrossDatasetViolations(run.ctx().getTable(),
                resolveDomainPrefix(rawName, run.ctx()), resolveDomainPrefixes(rawKeys, run.ctx()),
                run.rowCount(), includeEmpty, policy);
    }


    /**
     * The effective {@link GroupKeyPolicy} for a group-aware operator call: the operator family's
     * shipped default, with an authored {@code keep_missings=} kwarg overriding its
     * {@code keepMissings} disposition.
     *
     * <p>
     * ⚠ The {@code base} argument carries the family's <b>default</b>, and those defaults are not
     * uniform — {@code is_not_unique_set} and {@code target_is_not_sorted_by} fold a blank key
     * while the {@code within:}-keyed operators and {@code is_inconsistent_across_dataset} discard
     * it. Pass each call site's own default; do not standardise it here, or this phase would move
     * findings.
     * </p>
     *
     * <p>
     * ⚠⚠ This validates the <b>inline</b> (Form A) surface — the one shipped rules actually execute
     * through, since they inline their operations. {@code CheckToExpr} validates the declared
     * surface. A malformed value must be rejected on <em>both</em>; an earlier parameter's
     * validation was bypassed on exactly this path until its own review caught it.
     * </p>
     */
    private static GroupKeyPolicy groupKeyPolicy(Expr.Call c, GroupKeyPolicy base)
    {
        Expr e = c.kwargs().get("keep_missings");
        if (e == null)
        {
            return base;
        }
        if (!(e instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.BOOL)
        {
            throw unsupported(c.name() + " keep_missings= must be a boolean literal");
        }
        return base.withKeepMissings((Boolean) lit.value());
    }


    /**
     * Reads the optional {@code include_empty=} kwarg (Fix #121): absent → {@code false}; otherwise
     * it must be a boolean literal.
     */
    private static boolean includeEmptyKwarg(Expr.Call c)
    {
        Expr e = c.kwargs().get("include_empty");
        if (e == null)
        {
            return false;
        }
        if (!(e instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.BOOL)
        {
            throw unsupported(c.name() + " include_empty= must be a boolean literal");
        }
        return (Boolean) lit.value();
    }


    /**
     * Native plan for {@code inconsistent_enumerated_columns(NAME)}: flags rows with a gap in the
     * {@code NAME, NAME1, NAME2, …} enumerated-column sequence, via the shared
     * {@link GroupSemantics}.
     */
    private static ExprProgram.BoolPlan compileInconsistentEnumeratedColumns(Expr.Call c)
    {
        if (c.args().isEmpty())
        {
            throw unsupported("inconsistent_enumerated_columns requires a name operand");
        }
        String rawName = groupOperandName(c.args().get(0));
        return run -> GroupSemantics.inconsistentEnumeratedColumnsViolations(run.ctx().getTable(),
                resolveDomainPrefix(rawName, run.ctx()), run.rowCount());
    }


    /**
     * Native plan for {@code not_contains_all(NAME, keys=[…])} and the {@code $}-operation form
     * {@code not_contains_all($a, $b)}: flags every row when the distinct values of the source do
     * not contain all required values, via the shared {@link GroupSemantics}. The source is the
     * NAME column's distinct values (absent column ⇒ {@code null} ⇒ flagged) or — per the
     * distinct-source-value contract — a {@code $}-operation list (absent / non-collection ⇒ EMPTY
     * set). The required values come from the {@code keys=} list or a {@code $}-list value operand
     * (an absent {@code $}-list ⇒ empty ⇒ trivially contained ⇒ no violation).
     */
    private static ExprProgram.BoolPlan compileNotContainsAll(Expr.Call c)
    {
        // CT-004 (NRI-007, PLAN-value-check-against-library-codelist Phase 5b): a list-valued
        // metadata accessor as the SOURCE set — `not contains_all(var_codelist_coded_values(
        // "LIBRARY"), var_codelist_coded_values("DEFINE"))` fires when the required list carries a
        // token outside the source list. A null/no-list source yields the empty set, so any
        // required token fires — rules guard with var_codelist_extensible("LIBRARY") == false,
        // which is null-safe on variables without a library codelist.
        //
        // ⚠ TWO OPERAND SHAPES reach this branch, and only the SOURCE side is fixed. The source
        // is always the list accessor, resolved once for the current variable under iteration. The
        // REQUIRED side is whatever operandPlan yields: (a) a second list accessor — the original
        // CT-004 shape, broadcast, one synthetic row, mirroring the Python per-row contains_all
        // over two columns of iterables; or (b) a PER-ROW token producer, since Fix #329 —
        // `not contains_all(var_codelist_coded_values("LIBRARY"), split_by(value(), ";"))` in
        // CDISC-SEND-0049, where the vector carries a different List per row. Both are correct
        // because the verdict is delegated to Primitives.notContainsAllTokens, whose javadoc is the
        // authority on the per-row contract (a null / non-list / empty-list cell never fires).
        if (c.args().size() >= 2 && isListAccessor(c.args().get(0)))
        {
            ValuePlan sourceP = operandPlan(c.args().get(0), true);
            ValuePlan requiredP = operandPlan(c.args().get(1), true);
            return run ->
            {
                Vector tokens = requiredP.eval(run);
                if (tokens == null)
                {
                    return new BitSet();
                }
                return Primitives.notContainsAllTokens(tokens, listAccessorSet(sourceP, run, false),
                        run.rowCount());
            };
        }
        if (c.args().isEmpty() || !(c.args().get(0) instanceof Expr.Ref nameRef))
        {
            throw unsupported("not_contains_all requires a reference name operand");
        }
        String rawName = nameRef.name();
        boolean nameIsOperation = rawName.startsWith("$");
        // T9 per-row token branch: `$codelist not_contains_all split_by(--VAR, "…")`. When the
        // value operand is a per-row list producer (a call such as split_by(...)), the verdict is
        // PER ROW — the row fires when any of its tokens is not in the source set — rather than the
        // dataset-level distinct-values broadcast below. This mirrors the Python reference engine's
        // not_contains_all over two columns of iterables (proven per-row by CoreIssue890). The
        // source (name) side is the allowed set: a $-operation list (e.g. codelist_terms) or a
        // column's distinct values.
        if (c.args().size() >= 2 && c.args().get(1) instanceof Expr.Call)
        {
            ValuePlan tokensPlan = operandPlan(c.args().get(1), true);
            String sourceName = nameIsOperation ? rawName : groupOperandName(c.args().get(0));
            return run ->
            {
                EvaluationContext ctx = run.ctx();
                if (nameIsOperation && !ctx.getVariables().containsKey(sourceName))
                {
                    return new BitSet();
                }
                Set<String> allowed = nameIsOperation
                        ? GroupSemantics.distinctOperationValues(ctx.resolveVariable(sourceName))
                        : GroupSemantics.distinctColumnValues(ctx.getTable(),
                                resolveDomainPrefix(sourceName, ctx), run.rowCount());
                Vector tokens = tokensPlan.eval(run);
                if (tokens == null || allowed == null)
                {
                    return new BitSet();
                }
                return Primitives.notContainsAllTokens(tokens, allowed, run.rowCount());
            };
        }
        if (!nameIsOperation)
        {
            rawName = groupOperandName(c.args().get(0)); // plain column / -- shape check
        }
        String requiredRef = c.args().size() >= 2 && c.args().get(1) instanceof Expr.Ref vr
                && vr.name().startsWith("$") ? vr.name() : null;
        List<String> requiredCols = requiredRef != null ? List.of() : keyColumns(c);
        String name = rawName;
        return run ->
        {
            EvaluationContext ctx = run.ctx();
            // CheckEvaluator.evaluateLeaf guard: a $-NAME absent from the context short-circuits
            // to an empty result BEFORE the operator runs (present-but-non-collection values fall
            // through to the empty-set → flag-all contract below).
            if (nameIsOperation && !ctx.getVariables().containsKey(name))
            {
                return new BitSet();
            }
            Set<String> distinct = nameIsOperation
                    ? GroupSemantics.distinctOperationValues(ctx.resolveVariable(name))
                    : GroupSemantics.distinctColumnValues(ctx.getTable(),
                            resolveDomainPrefix(name, ctx), run.rowCount());
            List<String> required = requiredRef != null
                    ? GroupSemantics.operationStringList(ctx.resolveVariable(requiredRef))
                    : resolveDomainPrefixes(requiredCols, ctx);
            return GroupSemantics.notContainsAllVerdict(distinct, required, run.rowCount());
        };
    }


    /**
     * Native plan for {@code has_same_values(NAME)}: flags all rows when every non-missing NAME
     * value is identical, via the shared {@link GroupSemantics}.
     */
    private static ExprProgram.BoolPlan compileHasSameValues(Expr.Call c)
    {
        if (c.args().isEmpty())
        {
            throw unsupported("has_same_values requires a name operand");
        }
        String rawName = groupOperandName(c.args().get(0));
        return run -> GroupSemantics.hasSameValuesViolations(run.ctx().getTable(),
                resolveDomainPrefix(rawName, run.ctx()), run.rowCount());
    }


    /**
     * Native plan for {@code shares_no_elements_with(NAME[, VALUE | keys=[…]])} — a broadcast
     * set-intersection verdict over {@code $}-operation lists. Operand resolution mirrors the
     * set-intersection contract exactly: the NAME operand resolves only as a {@code $}-context
     * variable (a non-{@code $} name is unresolvable); the VALUE side is a {@code $}-variable
     * reference or the literal {@code keys=[…]} list (raised from a JSON array value); either side
     * unresolvable ⇒ every row flagged. The verdict itself is
     * {@link GroupSemantics#sharesNoElementsVerdict}.
     */
    private static ExprProgram.BoolPlan compileSharesNoElementsWith(Expr.Call c)
    {
        if (c.args().isEmpty() || !(c.args().get(0) instanceof Expr.Ref nameRef))
        {
            throw unsupported("shares_no_elements_with requires a reference name operand");
        }
        String name = nameRef.name();
        List<String> literalKeys = c.kwargs().containsKey("keys")
                ? keyLiterals(c.kwargs().get("keys"))
                : null;
        // The value side: either the literal keys= list, or a reference name (non-null exactly
        // when literalKeys is null — kept as the empty string otherwise so NullAway sees a
        // non-null dereference in the closure; the literalKeys branch never reads it).
        String valueName = "";
        if (literalKeys == null)
        {
            if (c.args().size() >= 2 && c.args().get(1) instanceof Expr.Ref valueRef)
            {
                valueName = valueRef.name();
            }
            else
            {
                throw unsupported("shares_no_elements_with requires a reference value operand"
                        + " or a keys= list");
            }
        }
        String valueRefName = valueName;
        return run ->
        {
            EvaluationContext ctx = run.ctx();
            // CheckEvaluator.evaluateLeaf guard: a $-NAME absent from the context short-circuits
            // to an empty result BEFORE the operator's flag-all null contract can apply.
            if (name.startsWith("$") && !ctx.getVariables().containsKey(name))
            {
                return new BitSet();
            }
            Object nameSet = name.startsWith("$") ? ctx.resolveVariable(name) : null;
            Object valueSet;
            if (literalKeys != null)
            {
                valueSet = literalKeys;
            }
            else
            {
                valueSet = valueRefName.startsWith("$") ? ctx.resolveVariable(valueRefName) : null;
            }
            return GroupSemantics.sharesNoElementsVerdict(nameSet, valueSet, run.rowCount());
        };
    }


    /**
     * Native plan for {@code is_not_ordered_subset_of(NAME, $VALUE)} — a broadcast order-preserving
     * subsequence verdict over {@code $}-operation lists. Operand resolution mirrors the legacy
     * ordered-subset contract exactly: the NAME operand resolves via {@code ctx.resolveVariable}
     * (any name; unresolvable ⇒ {@code null}); the VALUE operand resolves only as a
     * {@code $}-context variable; either side unresolvable ⇒ NO row flagged. The verdict itself is
     * {@link GroupSemantics#isNotOrderedSubsetVerdict}.
     */
    private static ExprProgram.BoolPlan compileIsNotOrderedSubsetOf(Expr.Call c)
    {
        if (c.args().isEmpty() || !(c.args().get(0) instanceof Expr.Ref nameRef))
        {
            throw unsupported("is_not_ordered_subset_of requires a reference name operand");
        }
        if (c.args().size() < 2 || !(c.args().get(1) instanceof Expr.Ref valueRef))
        {
            throw unsupported("is_not_ordered_subset_of requires a reference value operand");
        }
        String name = nameRef.name();
        String valueName = valueRef.name();
        return run ->
        {
            EvaluationContext ctx = run.ctx();
            // CheckEvaluator.evaluateLeaf guard (same outcome as the operator's null contract —
            // no rows — but mirrored explicitly so the present-but-null case stays faithful).
            if (name.startsWith("$") && !ctx.getVariables().containsKey(name))
            {
                return new BitSet();
            }
            Object nameVal = ctx.resolveVariable(name);
            Object valueVal = valueName.startsWith("$") ? ctx.resolveVariable(valueName) : null;
            return GroupSemantics.isNotOrderedSubsetVerdict(nameVal, valueVal, run.rowCount());
        };
    }


    /**
     * The literal string members of a {@code keys=[…]} list whose entries are value literals (not
     * column references) — the raised form of a JSON array value on a set operator
     * ({@code shares_no_elements_with}). Reference entries contribute their raw name (the raise
     * encodes textual array members as references); string literals contribute their text.
     */
    private static List<String> keyLiterals(Expr keys)
    {
        if (!(keys instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.LIST)
        {
            throw unsupported("keys= must be a list");
        }
        @SuppressWarnings("unchecked")
        List<Expr> items = (List<Expr>) lit.value();
        List<String> out = new ArrayList<>(items.size());
        for (Expr item : items)
        {
            switch (item)
            {
            case Expr.Ref r -> out.add(r.name());
            case Expr.Lit l when l.kind() == Expr.LitKind.STRING -> out.add((String) l.value());
            default -> throw unsupported("keys= member must be a reference or string literal");
            }
        }
        return out;
    }


    /**
     * The key columns of a set/aggregate function-operator call: any positional operands beyond the
     * first, followed by the {@code keys=} list. Each must be a plain column or {@code --}-prefix
     * reference — or a {@code $}-operation reference, which is passed through RAW: the legacy Array
     * members stay literal (no {@code $}-expansion), so the downstream column lookup misses and
     * {@code GroupSemantics} drops it exactly like an absent column (CORE-001034's
     * {@code $TIMING_VARIABLES} key). Mirroring that drop keeps the verdict bit-identical.
     */
    private static List<String> keyColumns(Expr.Call c)
    {
        List<String> cols = new ArrayList<>();
        for (int i = 1; i < c.args().size(); i++)
        {
            cols.add(keyMemberName(c.args().get(i)));
        }
        Expr keys = c.kwargs().get("keys");
        if (keys instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.LIST)
        {
            @SuppressWarnings("unchecked")
            List<Expr> items = (List<Expr>) lit.value();
            for (Expr item : items)
            {
                cols.add(keyMemberName(item));
            }
        }
        else if (keys != null)
        {
            throw unsupported("keys= must be a list of column references");
        }
        return cols;
    }


    /** A keys-list member: a group operand, or a {@code $}-reference passed through raw. */
    private static String keyMemberName(Expr e)
    {
        if (e instanceof Expr.Ref r && r.kind() == OperandKind.OPERATION_REF)
        {
            return r.name();
        }
        return groupOperandName(e);
    }


    /**
     * The raw name of a group-operator operand: a plain column reference, or a {@code --}-prefix
     * domain wildcard — returned RAW and resolved against the run's domain prefix inside the plan
     * closure (via {@link #resolveDomainPrefix}), so the compiled program stays dataset-agnostic
     * and cache-shareable across domains. Any other operand kind declines.
     */
    private static String groupOperandName(Expr e)
    {
        if (e instanceof Expr.Ref r && (r.kind() == OperandKind.COLUMN
                || (r.kind() == OperandKind.WILDCARD_COLUMN && isDomainPrefixWildcard(r.name()))))
        {
            return r.name();
        }
        // A name operand may also be written as a string literal (the generator's preferred form),
        // equivalent by definition to the bare reference; a leading --prefix is resolved downstream
        // by resolveDomainPrefixes exactly as for the bare-ref form.
        if (e instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.STRING)
        {
            return (String) lit.value();
        }
        throw unsupported("group operator operand must be a plain column or --prefix reference"
                + " (got " + e.getClass().getSimpleName() + ")");
    }


    /**
     * A dot-qualified {@code **} reference ({@code RELREC.**TERM}): the column part after the first
     * dot starts with {@code **}, resolved per row by the joined-dataset lookup itself
     * ({@code RelrecExpandedLookup}).
     */
    private static boolean isDottedStarStar(String name)
    {
        int dot = name.indexOf('.');
        return dot > 0 && name.startsWith("**", dot + 1);
    }


    /**
     * Resolves any {@code --}-prefix member of {@code rawNames} against the run's domain prefix.
     */
    private static List<String> resolveDomainPrefixes(List<String> rawNames, EvaluationContext ctx)
    {
        List<String> out = new ArrayList<>(rawNames.size());
        for (String n : rawNames)
        {
            out.add(resolveDomainPrefix(n, ctx));
        }
        return out;
    }


    /** Applies {@link #resolveDomainPrefix} to every column of every EC-24 within component. */
    private static List<List<String>> resolveDomainPrefixComponents(
            List<List<String>> rawComponents, EvaluationContext ctx)
    {
        List<List<String>> out = new ArrayList<>(rawComponents.size());
        for (List<String> comp : rawComponents)
        {
            out.add(resolveDomainPrefixes(comp, ctx));
        }
        return out;
    }


    /**
     * The {@code within=} partitioning columns: a single column reference, a list of column
     * references, or empty when absent. Declines a non-column member (Phase-N residual).
     */
    private static List<String> withinColumns(@Nullable Expr within)
    {
        if (within == null)
        {
            return List.of();
        }
        if (within instanceof Expr.Ref)
        {
            return List.of(groupOperandName(within));
        }
        if (within instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.LIST)
        {
            @SuppressWarnings("unchecked")
            List<Expr> items = (List<Expr>) lit.value();
            List<String> cols = new ArrayList<>(items.size());
            for (Expr item : items)
            {
                cols.add(groupOperandName(item));
            }
            return cols;
        }
        throw unsupported("within= must be a column reference or a list of column references");
    }


    /**
     * Normalises the {@code within=} kwarg to a list of key <b>components</b> (EC-24). Each
     * top-level entry is one component: a column reference / string literal is a singleton
     * component, while a nested {@code [C0, C1, …]} list is a coalesce-group (first-populated
     * column). Mirrors {@link net.cumba.cdisc.core.model.CheckConditionLeaf#getWithinComponents()}
     * so the native and legacy engines partition identically.
     */
    private static List<List<String>> withinComponents(@Nullable Expr within)
    {
        if (within == null)
        {
            return List.of();
        }
        if (within instanceof Expr.Ref)
        {
            return List.of(List.of(groupOperandName(within)));
        }
        if (within instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.LIST)
        {
            @SuppressWarnings("unchecked")
            List<Expr> items = (List<Expr>) lit.value();
            List<List<String>> components = new ArrayList<>(items.size());
            for (Expr item : items)
            {
                if (item instanceof Expr.Lit inner && inner.kind() == Expr.LitKind.LIST)
                {
                    @SuppressWarnings("unchecked")
                    List<Expr> group = (List<Expr>) inner.value();
                    List<String> comp = new ArrayList<>(group.size());
                    for (Expr g : group)
                    {
                        comp.add(groupOperandName(g));
                    }
                    components.add(comp);
                }
                else
                {
                    components.add(List.of(groupOperandName(item)));
                }
            }
            return components;
        }
        throw unsupported("within= must be a column reference or a list of column references");
    }

    /**
     * Which existence fact an exists-family call reads: each of the {@code ds_}/{@code var_} twins
     * is fixed to one fact — the retired generic {@code exists} was the only member whose fact
     * depended on the rule's type.
     */
    private enum ExistsMode
    {
        /** Dataset presence ({@code ds_exists}/{@code ds_not_exists}). */
        DATASET,
        /**
         * Column presence on the evaluated dataset ({@code var_exists} / {@code var_not_exists}).
         */
        VARIABLE
    }

    private static ExprProgram.BoolPlan compileExists(Expr.Call c, boolean negate, ExistsMode mode)
    {
        // §3.7 of PLAN-leaf-scope-domain-inference.md: var_exists(varname()) is the universe
        // discriminator — column presence of the CURSOR variable (absent from the data under the
        // Define universe, always present under Data). Resolved per evaluation from the cursor.
        if (mode == ExistsMode.VARIABLE && isCurrentVariableName(c.args().get(0)))
        {
            return run ->
            {
                Object cursor = run.ctx().resolveVariable("variable_name");
                boolean exists = cursor != null
                        && OperatorRegistry.existsAsVariable(run.ctx(), cursor.toString());
                boolean fire = negate != exists;
                BitSet result = new BitSet(run.rowCount());
                if (fire && run.rowCount() > 0)
                {
                    result.set(0, run.rowCount());
                }
                return result;
            };
        }
        // The whole exists family accepts a bareword/backtick reference or — equivalent by
        // definition — a string literal carrying the same name.
        String rawName = switch (c.args().get(0))
        {
        case Expr.Ref ref -> ref.name();
        case Expr.Lit lit when lit.kind() == Expr.LitKind.STRING -> (String) lit.value();
        default -> throw unsupported(c.name() + " expects a reference or a string literal");
        };
        if (mode == ExistsMode.DATASET && (rawName.indexOf('.') >= 0 || rawName.indexOf('=') >= 0
                || rawName.contains("${") || rawName.contains("--")))
        {
            // A dataset-presence test has no dotted/filter/substitution/--prefix surface: the
            // argument is a plain dataset name, checked at compile time so the rule fails loudly.
            throw unsupported(c.name() + " expects a plain dataset name");
        }
        if (OperandSubstitutor.hasPlaceholder(rawName))
        {
            // Review F3: a `${...}` operand-template name is a PER-ROW driver substitution on
            // the legacy engine (each row may resolve to a different concrete column — e.g.
            // var_exists("AP${APERIOD}SDT") with APERIOD = 1, 2). Broadcasting the
            // OperatorRegistry-level fact would freeze one row's verdict for the whole dataset,
            // so VARIABLE mode delegates to the shared per-row implementation
            // (OperatorRegistry.existsPerRowBits — the exact code the legacy
            // evalExists/evalVarExists run). resolveDomainPrefix still applies first, exactly as
            // on the broadcast path below; it rewrites only a leading `--` and can neither
            // introduce nor remove a placeholder. DATASET mode never reaches here (rejected
            // above).
            return run -> OperatorRegistry.existsPerRowBits(run.ctx(),
                    resolveDomainPrefix(rawName, run.ctx()), !negate);
        }
        return run ->
        {
            int rc = run.rowCount();
            // P3b: a --prefix wildcard resolves per run against the domain prefix (the legacy
            // phase-2c rewrite happens before legacy eval; natively it happens here, keeping the
            // compiled program dataset-agnostic) — VARIABLE mode only; a dataset name never
            // carries the prefix. existsAsDataset/existsAsVariable are the two rule-type-
            // independent entry points (the former handles dotted forms). ${...} names never
            // reach this broadcast closure — they took the per-row plan above (review F3).
            boolean exists = switch (mode)
            {
            case DATASET -> OperatorRegistry.existsAsDataset(run.ctx(), rawName);
            case VARIABLE -> OperatorRegistry.existsAsVariable(run.ctx(),
                    resolveDomainPrefix(rawName, run.ctx()));
            };
            boolean fire = negate != exists;
            BitSet result = new BitSet(rc);
            if (fire && rc > 0)
            {
                result.set(0, rc);
            }
            return result;
        };
    }


    /**
     * T5a — per-variable all-null cursor predicate {@code var_is_null(X)}. {@code X} is a plain
     * column reference / string literal, or the current-variable cursor ({@code varname()} /
     * {@code variable_name}) in a Variable Metadata Check. Broadcast-constant: the verdict is TRUE
     * when the named column is absent from the dataset, or present but empty ("" / missing) for
     * <em>every</em> record — mirroring the {@code variable_is_null} operation and the Python
     * reference engine's {@code variable_is_null}
     * ({@code (series.isnull() | (series == "")).all()}). The all-null scan reads the real dataset
     * rows via {@link OperatorRegistry#variableIsNull}, so the single verdict is well-defined even
     * under {@code evaluateBroadcast}'s 1-row range.
     */
    private static ExprProgram.BoolPlan compileVarIsNull(Expr.Call c)
    {
        Expr arg = c.args().get(0);
        boolean fromCursor = isCurrentVariableName(arg);
        String literalName = null;
        if (!fromCursor)
        {
            literalName = switch (arg)
            {
            case Expr.Ref ref -> ref.name();
            case Expr.Lit lit when lit.kind() == Expr.LitKind.STRING -> (String) lit.value();
            default -> throw unsupported(
                    "var_is_null expects a column reference, a string literal, or varname()");
            };
        }
        boolean cursor = fromCursor;
        String captured = literalName;
        return run ->
        {
            EvaluationContext ctx = run.ctx();
            String colName;
            if (cursor)
            {
                Object resolved = ctx.resolveVariable("variable_name");
                colName = resolved == null ? null : resolved.toString();
            }
            else
            {
                colName = captured == null ? null : resolveDomainPrefix(captured, ctx);
            }
            boolean isNull = OperatorRegistry.variableIsNull(ctx, colName);
            int rc = run.rowCount();
            BitSet result = new BitSet(rc);
            if (isNull && rc > 0)
            {
                result.set(0, rc);
            }
            return result;
        };
    }


    /**
     * T5b (SD1082) — per-variable max-actual-length cursor value {@code max_value_length(X)}.
     * {@code X} is a plain column reference / string literal, the current-variable cursor
     * ({@code varname()} / {@code variable_name}), or absent (arity-0, defaulting to the current
     * variable). Broadcast-constant: the max codepoint length over the named column's non-missing
     * values (0 when absent / all-missing) is computed once and broadcast to every row, mirroring
     * the {@code variable_max_size} metadata fact and the Python reference engine
     * ({@code dropna().astype(str).str.len().max()}). The scan reads the real dataset rows via
     * {@link OperatorRegistry#maxValueLength}, so the single value is well-defined even under
     * {@code evaluateBroadcast}'s 1-row range. The result is rendered as its numeric string so a
     * comparison against {@code var_length(X,"DATA")} (also a numeric string) is numeric-aware.
     */
    private static ValuePlan compileMaxValueLength(Expr.Call c)
    {
        Expr arg = c.args().isEmpty() ? null : c.args().get(0);
        boolean fromCursor = arg == null || isCurrentVariableName(arg);
        String literalName = null;
        if (!fromCursor)
        {
            // arg is non-null in this branch (fromCursor is TRUE when arg == null).
            literalName = switch (Objects.requireNonNull(arg))
            {
            case Expr.Ref ref -> ref.name();
            case Expr.Lit lit when lit.kind() == Expr.LitKind.STRING -> (String) lit.value();
            default -> throw unsupported(
                    "max_value_length expects a column reference, a string literal, or varname()");
            };
        }
        boolean cursor = fromCursor;
        String captured = literalName;
        return run ->
        {
            EvaluationContext ctx = run.ctx();
            String colName;
            if (cursor)
            {
                Object resolved = ctx.resolveVariable("variable_name");
                colName = resolved == null ? null : resolved.toString();
            }
            else
            {
                colName = captured == null ? null : resolveDomainPrefix(captured, ctx);
            }
            long max = OperatorRegistry.maxValueLength(ctx, colName);
            return ConstVector.of(Long.toString(max));
        };
    }


    /**
     * T2/VLM — a per-record Define-XML value-level metadata accessor {@code vlm_data_type(X)} /
     * {@code vlm_length(X)} / {@code vlm_mandatory(X)} / {@code vlm_codelist_coded_values(X)} /
     * {@code vlm_codelist_coded_codes(X)} ({@code Value Check against Define XML VLM} rule type).
     * {@code X} is {@code varname()} / {@code variable_name} / absent (the current variable) or a
     * string literal naming a column. For each row the applicable value-level {@code ItemDef} is
     * selected by evaluating the linked WhereClause predicate against the row
     * ({@link VlmResolver#resolve}); the requested attribute of the matched {@code ItemDef} is
     * returned, or {@code null} when no condition matches (⇒ the enclosing predicate does not fire,
     * the native analog of Python's inner-join-drops-unmatched). A {@code null} {@code vlmResolver}
     * (no Define-XML) yields {@code null} for every row and the rule SKIPs via the
     * {@link MetadataLevel#DEFINE} provider gate in {@code RuleRunner}.
     */
    private static ValuePlan compileVlmAccessor(Expr.Call c)
    {
        String fn = c.name();
        Expr arg = c.args().isEmpty() ? null : c.args().get(0);
        boolean fromCursor = arg == null || isCurrentVariableName(arg);
        String literalName = null;
        if (!fromCursor)
        {
            literalName = switch (Objects.requireNonNull(arg))
            {
            case Expr.Ref ref -> ref.name();
            case Expr.Lit lit when lit.kind() == Expr.LitKind.STRING -> (String) lit.value();
            default -> throw unsupported(
                    fn + " expects a column reference, a string literal, or varname()");
            };
        }
        boolean cursor = fromCursor;
        String captured = literalName;
        DataValueType type = switch (fn)
        {
        case "vlm_length", "vlm_value_length" -> DataValueType.LONG;
        case "vlm_type_conforms", "vlm_codelist_extensible", "vlm_has_codelist", "vlm_decode_matches" -> DataValueType.BOOLEAN;
        default -> DataValueType.STRING;
        };
        return run ->
        {
            EvaluationContext ctx = run.ctx();
            VlmResolver vlm = ctx.getVlmResolver();
            String domain = ctx.getDomainName();
            String var;
            if (cursor)
            {
                Object resolved = ctx.resolveVariable("variable_name");
                var = resolved == null ? null : resolved.toString();
            }
            else
            {
                var = captured == null ? null : resolveDomainPrefix(captured, ctx);
            }
            if (vlm == null || var == null || domain == null)
            {
                return ConstVector.of(null);
            }
            IDataTable table = ctx.getTable();
            DataTableMeta meta = table.getMetaData();
            String targetVar = var;
            // Fix #123: the decode partner depends only on the cursor variable and the column set,
            // both row-invariant, so it is resolved ONCE here. Resolving it per row would rescan
            // every column on each record (O(rows x columns)) for an unchanging answer.
            String vlmDecodeVar = "vlm_decode_matches".equals(fn)
                    ? resolvePartner(targetVar, meta, _ -> PartnerVerdict.UNKNOWN)
                    : null;
            return new ComputedVector(run.rowCount(), type, row ->
            {
                VlmResolver.VlmMatch m = vlm.resolve(domain, targetVar,
                        name -> cellString(table, meta, name, row));
                if (m == null)
                {
                    return null;
                }
                return switch (fn)
                {
                case "vlm_data_type" -> m.dataType();
                case "vlm_length" -> m.length() == null ? null
                        : Long.valueOf(m.length().longValue());
                case "vlm_mandatory" -> m.mandatory();
                case "vlm_codelist_coded_values" -> m.codedValues();
                case "vlm_codelist_coded_codes" -> m.codedCodes();
                case "vlm_type_conforms" -> vlmTypeConforms(m.dataType(),
                        cellString(table, meta, targetVar, row));
                case "vlm_codelist_extensible" -> vlmExtensible(ctx.getLibraryProvider(),
                        m.codelistCCode());
                case "vlm_value_length" -> vlmValueLength(m.dataType(),
                        cellString(table, meta, targetVar, row));
                case "vlm_has_codelist" -> Boolean.valueOf(!m.codedValues().isEmpty());
                case "vlm_decode_matches" -> vlmDecodeMatches(m, targetVar, vlmDecodeVar, table,
                        meta, row);
                default -> throw unsupported("unknown vlm accessor " + fn);
                };
            });
        };
    }


    /**
     * Reads the string form of {@code name}'s cell at {@code row}, or {@code null} when
     * absent/missing.
     */
    private static @Nullable String cellString(IDataTable table, DataTableMeta meta, String name,
            int row)
    {
        int idx = meta.getColumnIndex(name);
        if (idx < 0)
        {
            return null;
        }
        // Blank resolves by the column's declared type — see ScalarSemantics.resolvedString.
        return ScalarSemantics.resolvedString(table.getColumn(idx), meta.getColumn(idx).getType(),
                row);
    }


    /**
     * SD1230 — whether {@code value} conforms to the Define-XML value-level {@code dataType}. An
     * empty value or an unknown/text-family type conforms (populated-ness is SD1229's concern; the
     * check enforces {@code integer}/{@code float}/{@code date}/{@code datetime} lexical form).
     * Kept deliberately simple and mirrored byte-for-byte by the Python VLM builder's
     * {@code define_vlm_type_conforms} column so both engines agree.
     */
    private static @Nullable Boolean vlmTypeConforms(@Nullable String dataType,
            @Nullable String value)
    {
        if (value == null || value.isEmpty() || dataType == null)
        {
            return Boolean.TRUE;
        }
        return switch (dataType.trim().toLowerCase(Locale.ROOT))
        {
        case "integer" -> value.matches("[+-]?\\d+");
        case "float" -> parsesAsNumber(value);
        case "date" -> value.matches("\\d{4}-\\d{2}-\\d{2}");
        case "datetime" -> value.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2})?.*");
        default -> Boolean.TRUE;
        };
    }


    private static boolean parsesAsNumber(String s)
    {
        try
        {
            Double.parseDouble(s.trim());
            return true;
        }
        catch (NumberFormatException _)
        {
            return false;
        }
    }


    /**
     * SD1231 — the value's stored length for the value-level {@code dataType}, kept byte-for-byte
     * in step with the Python {@code ValuesDatasetBuilder.calculate_variable_value_length}: an
     * {@code integer} strips leading zeros, a {@code float} strips leading zeros and drops the
     * decimal point, {@code text} is the raw length, and every other type is {@code null}
     * (unmeasured ⇒ the length compare never fires). A missing value is {@code null}.
     */
    private static @Nullable Long vlmValueLength(@Nullable String dataType, @Nullable String value)
    {
        if (value == null || dataType == null)
        {
            return null;
        }
        return switch (dataType.trim().toLowerCase(Locale.ROOT))
        {
        case "integer" -> (long) stripLeadingZeros(value).length();
        case "float" -> (long) stripLeadingZeros(value).replace(".", "").length();
        case "text" -> (long) value.length();
        default -> null;
        };
    }


    private static String stripLeadingZeros(String s)
    {
        int i = 0;
        while (i < s.length() && s.charAt(i) == '0')
        {
            i++;
        }
        return s.substring(i);
    }


    /**
     * CT2003/CT2006 — whether the record's paired decode value matches the value-level codelist
     * decode of its code value. The code variable is the cursor ({@code codeVar}); its paired
     * decode variable follows the CDISC {@code --TESTCD}→{@code --TEST} convention (drop a trailing
     * {@code "CD"}). Returns {@code null} (no decision ⇒ no fire) when the code value is empty, the
     * decode variable cannot be derived / is absent, or the code value is not a term in the
     * value-level codelist (the latter is SD0037's concern), so the check only fires on a genuine
     * decode <em>mismatch</em>.
     */
    private static @Nullable Object vlmDecodeMatches(VlmResolver.VlmMatch m, String codeVar,
            @Nullable String decodeVar, IDataTable table, DataTableMeta meta, int row)
    {
        String codeVal = cellString(table, meta, codeVar, row);
        if (codeVal == null || codeVal.isEmpty())
        {
            return null;
        }
        if (decodeVar == null)
        {
            return null;
        }
        String expectedDecode = m.codeDecodeMap().get(codeVal);
        if (expectedDecode == null)
        {
            return null;
        }
        return Boolean.valueOf(expectedDecode.equals(cellString(table, meta, decodeVar, row)));
    }


    /**
     * Fix #123 — per-record {@code define_variable_decode_matches} accessor (rule type
     * {@code Value Check against Define XML Variable}). The cursor variable is treated as the
     * <b>code</b> variable: its Define-XML ItemDef codelist supplies a {@code CodedValue → Decode}
     * map, the decode partner is resolved by {@link #resolveDecodePartner}, and each record's
     * decode value is compared to the Decode of its coded value.
     *
     * <p>
     * Returns {@code null} (no decision ⇒ no fire) when the code value is empty, the variable binds
     * no decode-carrying codelist, no decode partner can be resolved/confirmed, or the code value
     * is not a term of the codelist (an out-of-codelist value is SD0037's concern) — so the check
     * fires only on a genuine decode <b>mismatch</b>. A {@code null} define provider yields
     * {@code null} for every row and the rule SKIPs via the {@link MetadataLevel#DEFINE} provider
     * gate in {@code RuleRunner}.
     * </p>
     *
     * <p>
     * Everything invariant across rows is resolved once, outside the row lambda:
     * {@code getVariableMetadata} performs a linear scan and rebuilds its key map on every call, so
     * calling it per record would be quadratic.
     * </p>
     */
    private static ValuePlan compileDefineVariableDecodeMatches(Expr.Call c)
    {
        Expr arg = c.args().isEmpty() ? null : c.args().get(0);
        if (arg != null && !isCurrentVariableName(arg))
        {
            throw unsupported(DEFINE_VARIABLE_DECODE_MATCHES + " expects varname() or no argument");
        }
        return run ->
        {
            EvaluationContext ctx = run.ctx();
            MetadataProvider define = ctx.getDefineProvider();
            String domain = ctx.getDomainName();
            Object resolved = ctx.resolveVariable("variable_name");
            String codeVar = resolved == null ? null : resolved.toString();
            if (define == null || domain == null || codeVar == null)
            {
                return ConstVector.of(null);
            }
            IDataTable table = ctx.getTable();
            DataTableMeta meta = table.getMetaData();
            Map<String, String> codeDecode = DefineMetadataListCodec.decodeStringMap(
                    define.getVariableMetadata(domain, codeVar).get("codelist_code_decode"));
            if (codeDecode.isEmpty())
            {
                return ConstVector.of(null);
            }
            String decodeVar = resolveDecodePartner(codeVar, codeDecode, define, domain, meta);
            if (decodeVar == null)
            {
                return ConstVector.of(null);
            }
            String codeColumn = codeVar;
            String decodeColumn = decodeVar;
            return new ComputedVector(run.rowCount(), DataValueType.BOOLEAN, row ->
            {
                String codeVal = cellString(table, meta, codeColumn, row);
                if (codeVal == null || codeVal.isEmpty())
                {
                    return null;
                }
                String expected = codeDecode.get(codeVal);
                if (expected == null)
                {
                    return null;
                }
                return Boolean.valueOf(expected.equals(cellString(table, meta, decodeColumn, row)));
            });
        };
    }


    /**
     * Fix #123 — resolves the decode partner of {@code codeVar}: <b>suffix proposes, metadata
     * confirms</b>.
     *
     * <ol>
     * <li><b>Suffix step.</b> Strip each of {@link #DECODE_PARTNER_SUFFIXES} (longest first). A
     * candidate must exist as a column <i>and</i> be confirmed. This resolves the collisions that
     * decode-set matching alone cannot — {@code TRTPN}→{@code TRTP}, not {@code TRTA}.</li>
     * <li><b>Unique-match fallback.</b> If no suffix candidate confirmed, scan the remaining
     * columns and accept only when <b>exactly one</b> confirms. This is what reaches the genuine
     * non-suffix pair {@code ETCD}→{@code ELEMENT}; ambiguity yields {@code null}.</li>
     * </ol>
     *
     * <p>
     * <b>Confirmation</b> = the candidate's own define codelist coded-value set equals the code
     * variable's decode set; when the candidate binds no codelist, every distinct observed value of
     * the candidate column must be in the decode set (a column with no populated value is not
     * confirmable and is rejected). The gate is what makes the wide suffix set safe: {@code N} is
     * collision-prone, and without confirmation a coincidental name match would pair.
     * </p>
     *
     * @param codeVar
     *            the code variable (the cursor)
     * @param codeDecode
     *            its {@code CodedValue → Decode} map (non-empty)
     * @param define
     *            the define provider
     * @param domain
     *            the domain name
     * @param meta
     *            the dataset metadata
     * @return the confirmed decode partner column, or {@code null}
     */
    private static @Nullable String resolveDecodePartner(String codeVar,
            Map<String, String> codeDecode, MetadataProvider define, String domain,
            DataTableMeta meta)
    {
        Set<String> decodes = new HashSet<>(codeDecode.values());
        // One snapshot of the domain's define variables — getVariableMetadata rescans per call.
        Map<String, String> codedValuesByVar = new HashMap<>();
        for (Map<String, String> v : define.getDomainVariables(domain))
        {
            String name = v.get("name");
            if (name != null)
            {
                codedValuesByVar.put(name, v.get("codelist_coded_values"));
            }
        }
        return resolvePartner(codeVar, meta,
                candidate -> defineVerdict(candidate, decodes, codedValuesByVar));
    }

    /**
     * Fix #123 — verdict of a candidate decode partner. Deliberately three-valued: metadata can say
     * yes, say no, or have nothing to say. Treating "nothing to say" as a rejection would silently
     * drop the many partners that bind no codelist of their own; treating it as an acceptance in
     * the fallback would pair on no evidence at all.
     */
    private enum PartnerVerdict
    {
        /** Metadata positively identifies this as the decode partner. */
        CONFIRMED,
        /** Metadata contradicts the pairing — reject even if the name matches. */
        REJECTED,
        /** No metadata to judge by; the naming convention is the only evidence. */
        UNKNOWN
    }

    /**
     * Fix #123 — the shared <b>suffix proposes, metadata confirms</b> partner resolution used by
     * all three paired code/decode accessors. Only the confirmation differs, so it is supplied as
     * {@code confirm}.
     *
     * <ol>
     * <li><b>Suffix step</b> — strip each of {@link #DECODE_PARTNER_SUFFIXES} (longest first). The
     * candidate must exist as a column and must not be {@link PartnerVerdict#REJECTED}: a
     * CDISC-conventional name is itself evidence, so an {@link PartnerVerdict#UNKNOWN} candidate is
     * accepted here. This resolves collisions no content check can ({@code TRTPN}→{@code TRTP}, not
     * {@code TRTA}).</li>
     * <li><b>Unique-match fallback</b> — otherwise accept the single
     * {@link PartnerVerdict#CONFIRMED} column, if there is exactly one. Positive metadata evidence
     * is required here because there is no naming evidence; this is what reaches genuine non-suffix
     * pairs such as {@code ETCD}→{@code ELEMENT}. Ambiguity yields {@code null}.</li>
     * </ol>
     *
     * <p>
     * <b>Confirmation never reads the record values being validated.</b> An earlier revision
     * confirmed a candidate by checking that its observed values were all valid decodes, which is
     * self-defeating: the very mismatch the rule exists to report would make the pair fail
     * confirmation and fire nothing. Confirmation is metadata-only.
     * </p>
     *
     * @param codeVar
     *            the code variable (the cursor)
     * @param meta
     *            the dataset metadata
     * @param confirm
     *            metadata verdict for a candidate column name
     * @return the resolved partner column, or {@code null}
     */
    private static @Nullable String resolvePartner(String codeVar, DataTableMeta meta,
            java.util.function.Function<String, PartnerVerdict> confirm)
    {
        for (String suffix : DECODE_PARTNER_SUFFIXES)
        {
            if (!codeVar.endsWith(suffix) || codeVar.length() <= suffix.length())
            {
                continue;
            }
            String candidate = codeVar.substring(0, codeVar.length() - suffix.length());
            if (meta.getColumnIndex(candidate) >= 0
                    && confirm.apply(candidate) != PartnerVerdict.REJECTED)
            {
                return candidate;
            }
        }
        String only = null;
        for (int i = 0; i < meta.getColumnCount(); i++)
        {
            String candidate = meta.getColumn(i).getName();
            if (candidate.equals(codeVar) || confirm.apply(candidate) != PartnerVerdict.CONFIRMED)
            {
                continue;
            }
            if (only != null)
            {
                return null; // ambiguous — give up rather than guess
            }
            only = candidate;
        }
        return only;
    }


    /**
     * Fix #123 — metadata verdict for a define-backed candidate: its own ItemDef codelist coded
     * values equal the code variable's decode set ({@link PartnerVerdict#CONFIRMED}), differ
     * ({@link PartnerVerdict#REJECTED}), or it binds no codelist at all
     * ({@link PartnerVerdict#UNKNOWN}).
     */
    private static PartnerVerdict defineVerdict(String candidate, Set<String> decodes,
            Map<String, String> codedValuesByVar)
    {
        List<String> coded = DefineMetadataListCodec.decode(codedValuesByVar.get(candidate));
        if (coded.isEmpty())
        {
            return PartnerVerdict.UNKNOWN;
        }
        return decodes.equals(new HashSet<>(coded)) ? PartnerVerdict.CONFIRMED
                : PartnerVerdict.REJECTED;
    }

    /**
     * E9 — the {@code library_variable_code_pair_matches} accessor name (operand and function share
     * the name; see {@code MetadataOperandMapping} / {@code BuiltinRegistry}).
     */
    static final String LIBRARY_CODE_PAIR_MATCHES = "library_variable_code_pair_matches";

    /**
     * Fix #123 — the {@code define_variable_decode_matches} accessor name (operand and function
     * share the name, like {@link #LIBRARY_CODE_PAIR_MATCHES}).
     */
    static final String DEFINE_VARIABLE_DECODE_MATCHES = "define_variable_decode_matches";

    /**
     * Fix #123 — candidate suffixes for the decode partner, <b>longest first</b> so
     * {@code VISITNUM} strips to {@code VISIT} and never to {@code VISITNU}. Derived empirically
     * from the shipped defines (see plans/PLAN-define-variable-decode-pairing.md §2.5): {@code N}
     * dominates (AVISITN/AVISIT, RACEN/RACE, TRTPN/TRTP), then {@code NUM} (VISITNUM/VISIT), then
     * {@code CD} (PARAMCD/PARAM).
     */
    private static final List<String> DECODE_PARTNER_SUFFIXES = List.of("NUM", "CD", "N");

    /**
     * CT2003 — per-record {@code library_variable_code_pair_matches} accessor (rule type
     * {@code Value Check against Library Metadata}). The current variable is the code variable
     * (e.g. {@code --TESTCD}); its paired decode variable follows the CDISC convention (drop a
     * trailing {@code "CD"}). For each row the code value is mapped to its CDISC-CT term concept id
     * (via {@link MetadataProvider#getCodelistCodeMap} for the code variable's bound codelist) and
     * the paired decode value is mapped to its term concept id (the decode variable's codelist
     * map); the accessor returns {@code true} when the two concept ids are equal — paired
     * {@code --TESTCD} / {@code --TEST} terms share their concept id in the CDISC CT. Returns
     * {@code null} (no decision ⇒ no fire) when the code value is empty, the decode variable cannot
     * be derived / is absent, or either value is not a term of its codelist — so the check fires
     * only on a genuine cross-codelist mismatch. A {@code null} library provider yields
     * {@code null} for every row and the rule SKIPs via the {@link MetadataLevel#LIBRARY} provider
     * gate in {@code RuleRunner}.
     */
    private static ValuePlan compileLibraryCodePairMatches(Expr.Call c)
    {
        Expr arg = c.args().isEmpty() ? null : c.args().get(0);
        if (arg != null && !isCurrentVariableName(arg))
        {
            throw unsupported(LIBRARY_CODE_PAIR_MATCHES + " expects varname() or no argument");
        }
        return run ->
        {
            EvaluationContext ctx = run.ctx();
            MetadataProvider library = ctx.getLibraryProvider();
            String domain = ctx.getDomainName();
            Object resolved = ctx.resolveVariable("variable_name");
            String codeVar = resolved == null ? null : resolved.toString();
            if (library == null || domain == null || codeVar == null)
            {
                return ConstVector.of(null);
            }
            IDataTable table = ctx.getTable();
            DataTableMeta meta = table.getMetaData();
            // Fix #373 — getCodelistCodeMap resolves through findColumn ALONE, so it misses a
            // split member exactly as getVariableMetadata's leg 1 does. Resolve the key ONCE and
            // reuse it for all three lookups below: re-probing per variable could pick a different
            // key for the code and decode halves and compare two unrelated codelists.
            // ⚠ The AP/SUPP shapes are NOT resolved here — getCodelistCodeMap now canonicalises
            // them itself (Fix #373, provider side), which is where the canonical name is known.
            String resolvedDomain = domain;
            Map<String, String> resolvedCodeMap = library.getCodelistCodeMap(domain, codeVar);
            if (resolvedCodeMap.isEmpty())
            {
                String byDomain = libraryVariableDomain(table, domain);
                if (!byDomain.equals(domain))
                {
                    resolvedCodeMap = library.getCodelistCodeMap(byDomain, codeVar);
                    resolvedDomain = byDomain;
                }
            }
            if (resolvedCodeMap.isEmpty())
            {
                return ConstVector.of(null);
            }
            // Assigned exactly once so both stay effectively final for the row lambda below.
            final Map<String, String> codeMap = resolvedCodeMap;
            final String libDomain = resolvedDomain;
            // Fix #123: widened from the CD-only convention to the shared resolver. Confirmation
            // here is CDISC-CT concept-id alignment — paired codelists (e.g. LBTESTCD / LBTEST)
            // publish the same concept id for every term, which is exactly this operand's premise.
            Set<String> codeConcepts = new HashSet<>(codeMap.values());
            String decodeVar = resolvePartner(codeVar, meta, candidate ->
            {
                Map<String, String> candidateMap = library.getCodelistCodeMap(libDomain, candidate);
                if (candidateMap.isEmpty())
                {
                    return PartnerVerdict.UNKNOWN; // no published codelist to judge by
                }
                return codeConcepts.equals(new HashSet<>(candidateMap.values()))
                        ? PartnerVerdict.CONFIRMED
                        : PartnerVerdict.REJECTED;
            });
            if (decodeVar == null)
            {
                return ConstVector.of(null);
            }
            Map<String, String> decodeMap = library.getCodelistCodeMap(libDomain, decodeVar);
            String codeColumn = codeVar;
            String decodeColumn = decodeVar;
            return new ComputedVector(run.rowCount(), DataValueType.BOOLEAN, row ->
            {
                String codeVal = cellString(table, meta, codeColumn, row);
                if (codeVal == null || codeVal.isEmpty())
                {
                    return null;
                }
                String codeCcode = codeMap.get(codeVal);
                if (codeCcode == null)
                {
                    return null;
                }
                String decodeVal = cellString(table, meta, decodeColumn, row);
                String decodeCcode = decodeVal == null ? null : decodeMap.get(decodeVal);
                if (decodeCcode == null)
                {
                    return null;
                }
                return Boolean.valueOf(codeCcode.equals(decodeCcode));
            });
        };
    }


    /**
     * CT2004/CT2005 — whether the matched value-level codelist (identified by its NCI C-code) is
     * extensible per the CDISC CT library. Defaults to {@code true} (extensible) when the library
     * or the codelist C-code is unavailable, matching
     * {@code MetadataProvider.isCodelistExtensible}'s unknown-codelist default and the Python VLM
     * builder, so a non-extensible check (CT2004) never false-fires without a library.
     */
    private static Boolean vlmExtensible(@Nullable MetadataProvider library, @Nullable String cCode)
    {
        if (library == null || cCode == null)
        {
            return Boolean.TRUE;
        }
        return Boolean.valueOf(library.isCodelistExtensible(cCode));
    }

    // ---------------------------------------------------------------------
    // Operand (value) plans
    // ---------------------------------------------------------------------


    private static ValuePlan valuePlan(Expr e)
    {
        return operandPlan(e, false);
    }


    private static ValuePlan operandPlan(Expr e, boolean namePosition)
    {
        return operandPlan(e, namePosition, false);
    }


    /**
     * EC-43: as {@link #operandPlan(Expr, boolean)}, but when {@code foldAbsentColumn} is set a
     * name-position column that resolves to nothing yields an all-missing vector instead of
     * {@code null}, so the enclosing predicate computes its own polarity over it.
     *
     * <p>
     * The flag is threaded into {@link #valueCallPlan}'s <em>argument</em> plans on purpose: the
     * fork materialises the COLUMN ({@code patched[target] = ""}) and lets every downstream
     * function see it, so {@code len(X)} over an absent {@code X} must be {@code 0}, not missing.
     * Wrapping the composed operand plan instead would yield {@code MISSING} and
     * {@code has_equal_length 0} would diverge.
     * </p>
     */
    private static ValuePlan operandPlan(Expr e, boolean namePosition, boolean foldAbsentColumn)
    {
        return switch (e)
        {
        case Expr.Lit lit ->
        {
            ConstVector cv = ConstVector.of(literalObject(lit));
            yield _ -> cv;
        }
        case Expr.Ref r ->
        {
            if (r.kind() == OperandKind.WILDCARD_COLUMN)
            {
                // `--`-prefix domain wildcard (e.g. `--SEQ`) is a 1-to-1 rewrite resolvable at eval
                // time from the context's domain prefix (mirrors
                // CheckConditionTransformer.resolvePrefixes), so the compiled program stays
                // domain-agnostic and serves every domain. Other wildcards — `*`/`**`/ADaM-capture
                // column enumeration (arity-changing, expanded to N rules by WildcardExpander),
                // `${...}` substitution, and dot-qualified RELREC.`**` per-row forms — need
                // downstream machinery the native backend lacks; decline so the rule falls back to
                // the lowered legacy path.
                if (isDomainPrefixWildcard(r.name()))
                {
                    yield namePosition ? nameRefPlan(r.name(), foldAbsentColumn)
                            : valueRefPlan(r.name());
                }
                // `${VAR[:fmt]}` scalar operand-substitution (Fix #37): the concrete column name is
                // a per-row function of the row's driver cells, so it cannot be resolved once at
                // compile time like `--SEQ`. It is, however, a single dynamic column read with no
                // arity change — so we yield a per-row ComputedVector that mirrors
                // substitution in value and name position. A `${*}`
                // wildcard (list-valued) or a parse failure still declines to legacy.
                OperandSubstitutor.ParsedOperand parsed = parseScalarSubstitution(r.name());
                if (parsed instanceof OperandSubstitutor.Scalar scalar && parsed.hasDrivers())
                {
                    yield substitutedScalarPlan(scalar, namePosition);
                }
                // P5b: a dot-qualified `**` reference (RELREC.**TERM) resolves per row through the
                // SAME engine-agnostic joined-dataset lookup the legacy ValueResolver consults —
                // RelrecExpandedLookup (registered under the dataset key by RelrecRowExpander)
                // itself resolves the `**` prefix against the bound target row's domain. The
                // dotted plan already routes through that lookup, so this is a scalar per-row
                // read with no arity change.
                if (isDottedStarStar(r.name()))
                {
                    yield namePosition ? nameRefPlan(r.name(), foldAbsentColumn)
                            : valueRefPlan(r.name());
                }
                throw unsupported("wildcard/prefix operand '" + r.name()
                        + "' is resolved downstream; not native-supported");
            }
            yield namePosition ? nameRefPlan(r.name(), foldAbsentColumn) : valueRefPlan(r.name());
        }
        case Expr.Call c ->
        {
            if (tagOf(c) != null)
            {
                yield operandPlan(untag(c), namePosition, foldAbsentColumn);
            }
            if (isInlineOperation(c))
            {
                yield operationCallPlan(c);
            }
            MetadataAttribute metaAttr = MetadataAttribute.fromFunction(c.name());
            if (metaAttr != null)
            {
                yield metadataPlan(metaAttr, c);
            }
            if ("max_value_length".equals(c.name()) && c.args().size() <= 1)
            {
                yield compileMaxValueLength(c);
            }
            if (c.name().startsWith("vlm_") && c.args().size() <= 1)
            {
                yield compileVlmAccessor(c);
            }
            if (LIBRARY_CODE_PAIR_MATCHES.equals(c.name()) && c.args().size() <= 1)
            {
                yield compileLibraryCodePairMatches(c);
            }
            if (DEFINE_VARIABLE_DECODE_MATCHES.equals(c.name()) && c.args().size() <= 1)
            {
                yield compileDefineVariableDecodeMatches(c);
            }
            yield valueCallPlan(c, foldAbsentColumn);
        }
        default -> throw unsupported("unsupported operand " + e.getClass().getSimpleName());
        };
    }


    /**
     * Whether {@code name} is a {@code --}-prefix domain wildcard (e.g. {@code --SEQ}) the native
     * backend resolves at eval time — as opposed to a {@code *}/{@code **}/ADaM enumeration
     * wildcard or a dot-qualified {@code RELREC.**} form, which it declines.
     */
    private static boolean isDomainPrefixWildcard(String name)
    {
        return name.startsWith("--") && name.indexOf('.') < 0;
    }


    /**
     * Parses a {@code ${...}} operand at compile time, returning the {@link OperandSubstitutor}
     * parsed form, or {@code null} when the name carries no placeholder or fails to parse. A parse
     * failure (malformed placeholder, illegal format spec) returns {@code null} so the caller
     * declines to the legacy path rather than nativising an invalid operand.
     */
    private static OperandSubstitutor.@Nullable ParsedOperand parseScalarSubstitution(String name)
    {
        if (!OperandSubstitutor.hasPlaceholder(name))
        {
            return null;
        }
        try
        {
            return OperandSubstitutor.parse(name);
        }
        catch (OperandSubstitutor.OperandParseException _)
        {
            return null;
        }
    }


    /**
     * A per-row plan for a {@code ${VAR[:fmt]}} scalar operand-substitution. The concrete column
     * name is rebuilt from the row's driver cells via
     * {@link OperandSubstitutor#substituteScalar(OperandSubstitutor.Scalar, EvaluationContext, long)}
     * and the named column's value read — exactly the legacy semantics.
     *
     * <p>
     * The two positions diverge in the legacy engine and so diverge here:
     * </p>
     * <ul>
     * <li><b>Value position</b> (Scalar) → {@code resolveColumnValue}: the resolved name is read as
     * dotted-join / primary-table string / first-non-null across unqualified joins / {@code null}.
     * There is no {@code resolveVariable} probe and no literal fallback (matching
     * {@code resolveColumnValue}).</li>
     * <li><b>Name position</b>: the resolved name is read as dotted-join / primary-table cell /
     * {@code null} — with <em>no</em> unqualified-join fallback.</li>
     * </ul>
     *
     * <p>
     * <b>Fix #269 / EC-84 — an unresolvable substitution is "no value", in BOTH positions.</b> A
     * driver the row cannot resolve (column absent from the dataset, or the row's driver cell
     * missing / rejected by the format spec) leaves the operand with no concrete column to name, so
     * the row resolves to a missing cell — the standing <i>absent column = all-missing column</i>
     * policy, and the same {@code null} an absent target column already produces one line below.
     * The value position used to let {@link OperandSubstitutor.SubstitutionException} propagate;
     * because {@code Primitives.scan} resolves the right-hand operand for <em>every</em> row rather
     * than only the rows still in the enclosing {@code all}'s candidate mask, a single blank driver
     * cell aborted the whole run — no {@code not empty(DRIVER)} conjunct could shield it. Per-row
     * "no value" (never a rule-level ERROR, never a skip) is the ruled disposition; whether the row
     * then reports is left, as always, to the rule's own guards.
     * </p>
     */
    private static ValuePlan substitutedScalarPlan(OperandSubstitutor.Scalar scalar,
            boolean namePosition)
    {
        return run ->
        {
            EvaluationContext ctx = run.ctx();
            int rc = run.rowCount();
            return new ComputedVector(rc, DataValueType.STRING,
                    row -> substitutedScalarValue(scalar, ctx, row, namePosition));
        };
    }


    private static @Nullable Object substitutedScalarValue(OperandSubstitutor.Scalar scalar,
            EvaluationContext ctx, long row, boolean namePosition)
    {
        String name;
        try
        {
            name = OperandSubstitutor.substituteScalar(scalar, ctx, row);
        }
        catch (OperandSubstitutor.SubstitutionException _)
        {
            // Fix #269 / EC-84: the row's drivers do not resolve, so there is no concrete column
            // to read. Both positions answer with a missing cell -- the disposition an absent
            // column has under the standing "absent column = all-missing column" policy, and the
            // one an unresolvable target column already produced below.
            return null;
        }
        int dot = name.indexOf('.');
        if (dot > 0)
        {
            String ds = name.substring(0, dot);
            String col = name.substring(dot + 1);
            JoinLookup lookup = ctx.getJoinedDatasets().get(ds);
            return lookup == null ? null : lookup.lookup(ctx.getTable(), row, col);
        }
        DataTableMeta meta = ctx.getTable().getMetaData();
        int colIdx = meta.getColumnIndex(name);
        if (colIdx >= 0)
        {
            // Blank resolves by the column's declared type — see ScalarSemantics.resolvedString.
            return ScalarSemantics.resolvedString(ctx.getTable(), colIdx, row);
        }
        // Unqualified name absent from the primary table. The value position falls back to the
        // first non-null value across all joins (resolveColumnValue → resolveFromJoinedDatasets);
        // the name position does not (forEachSubstitutedValue reads the local column only).
        return namePosition ? null : firstJoined(ctx, row, name);
    }


    /**
     * Resolves a {@code --}-prefix wildcard in a NAME position against the context's variable
     * wildcard prefix (Python's {@code wildcard_replacement}) — the 2-character parent suffix for
     * an AP dataset, {@code ""} for SUPP/SQ, otherwise the CDISC domain code. EC-36: substitution
     * is unconditional once a prefix exists; the previous {@code length() == 2} gate silently left
     * the name raw for AP and SUPP datasets. A {@code null} prefix returns the raw name.
     */
    static String resolveDomainPrefix(String name, EvaluationContext ctx)
    {
        if (!isDomainPrefixWildcard(name))
        {
            return name;
        }
        // EC-36: a `--` in a NAME position is a variable-name wildcard, so it takes Python's
        // wildcard_replacement — the 2-character AP parent suffix for an AP dataset (APMH holds
        // MHTERM), and "" for SUPP/SQ (--QNAM is QNAM). ctx.getDomainPrefix() is the CDISC domain
        // code and stays reserved for dataset-name wildcards (Operation `domain:`, Fix #59/#33).
        // Falls back to the domain prefix for synthetic contexts built without a table.
        String prefix = ctx.getVariableWildcardPrefix();
        if (prefix == null)
        {
            prefix = ctx.getDomainPrefix();
        }
        if (prefix == null)
        {
            return name;
        }
        // "" is a legitimate replacement (SUPP/SQ), so an is-empty test must not be mistaken for
        // "no prefix". The old `length() == 2` gate silently left --TERM unresolved for every AP
        // dataset and --QNAM unresolved for every SUPP dataset; Python substitutes unconditionally.
        return prefix + name.substring(2);
    }


    /**
     * Name-position reference: column fast path, dotted-join, or context variable. A
     * {@code --}-prefix raw name is resolved against the context's domain prefix first. When
     * {@code foldAbsentColumn} is set a name that resolves to no column in the primary table
     * <em>and</em> to no {@code Match_Datasets} join yields an all-missing {@link ConstVector}
     * instead of {@code null} — <b>an absent column is a column whose values are all missing</b>.
     * The enclosing predicate then computes its OWN polarity over it: {@code X != "A"} and
     * {@code X !~ /re/} fire (a blank is neither), while {@code X == "A"} does not, and a
     * column-vs-column negative with BOTH sides absent does not (both fold to {@code ""} and
     * compare equal — the legacy both-missing contract that {@link #compileNot}'s
     * case-insensitive-equality interception exists to protect).
     *
     * <p>
     * <b>Two of this method's three {@code null} exits are deliberately NOT folded</b>, because
     * they do not mean "absent column":
     * </p>
     * <ul>
     * <li>a {@code $}-name that is not a key of the context — the <em>"Variable not in
     * context"</em> contract (guard-residual D4), mirrored at {@link #compileNotContainsAll} and
     * friends. Folding it would make {@code $X != v} fire on every row when an Operation never
     * ran.</li>
     * <li>a {@code --} wildcard whose domain prefix could not be resolved (EC-36) — the name was
     * never resolved at all, so it is not a column that is absent.</li>
     * </ul>
     *
     * <p>
     * This is the Java mirror of the fork's {@code _absent_target_as_missing} (EC-38 / Fix #128),
     * including its central choice: materialise the column and let the operator decide, so no
     * per-operator polarity table has to be maintained.
     * </p>
     */
    private static ValuePlan nameRefPlan(String rawName, boolean foldAbsentColumn)
    {
        return run ->
        {
            EvaluationContext ctx = run.ctx();
            int rc = run.rowCount();
            String name = resolveDomainPrefix(rawName, ctx);
            if (name.indexOf('.') > 0)
            {
                return dottedVector(ctx, rc, name);
            }
            Object var = ctx.resolveVariable(name);
            if (var != null)
            {
                return variableVector(ctx, rc, var);
            }
            if (name.startsWith("$"))
            {
                // Present-but-null vs absent (guard-residual D4): a $-entry that EXISTS in the
                // context with a null value (e.g. a per-variable VariableMetadataResult projection
                // with no entry for the current column) is a MISSING VALUE — the legacy
                // evaluateLeafAgainstMetadata treats it as metaMissing (so `empty` fires) and the
                // legacy CheckEvaluator containsKey guard lets such leaves evaluate. Only a name
                // truly ABSENT from the context is unresolved (null ⇒ empty BitSet), mirroring
                // CheckEvaluator's "Variable not in context" contract.
                return ctx.getVariables().containsKey(name) ? ConstVector.of(null) : null;
            }
            DataTableMeta meta = ctx.getTable().getMetaData();
            int idx = meta.getColumnIndex(name);
            if (idx >= 0)
            {
                return new ColumnVector(ctx.getTable().getColumn(idx),
                        meta.getColumn(idx).getType());
            }
            // Unqualified name absent from the primary table: if a Match_Datasets join carries it,
            // resolve it the same way the legacy engine does
            // (the joined-dataset lookup) — the
            // FIRST NON-NULL value across all lookupAll matches, scanning every join. This matters
            // for 1-to-many joins where the first-wins matched row's cell is null/missing but a
            // later matched row is non-null: scalar lookup() would return null and diverge from
            // legacy. Genuinely-missing names (not in the primary table nor any join) stay null ⇒
            // empty BitSet (Appendix-C missing contract).
            Vector joined = joinedColumnVector(ctx, rc, name);
            if (joined != null)
            {
                return joined;
            }
            // EC-43: an unresolved `--` wildcard is not an absent column — resolveDomainPrefix
            // returned the raw name because neither a variable-wildcard nor a domain prefix was
            // available, so nothing was ever resolved. Keep the null.
            if (isDomainPrefixWildcard(rawName) && name.equals(rawName))
            {
                return null;
            }
            // EC-43: fold only what could actually BE a dataset column. The engine's other
            // absent-column decision point, BroadcastFold.isFoldableColumnReference, already
            // rejects engine-meta barewords (`variable_label`, `library_variable_core`, … — they
            // start lowercase) and every non-column shape; using the same predicate here keeps the
            // two layers from holding different ideas of what "an absent column" is. Without it a
            // metadata operand the loader's canonicalization pass did not rewrite would be
            // materialised as an absent COLUMN, and `len(variable_label) > 40` would fire on every
            // row of every dataset instead of yielding nothing.
            if (foldAbsentColumn && absentFoldEnabled
                    && BroadcastFold.isFoldableColumnReference(name))
            {
                ctx.noteAbsentColumnFold(name);
                return ALL_MISSING;
            }
            return null;
        };
    }


    /**
     * Scalar {@link Vector} over an unqualified column carried by a {@code Match_Datasets} join,
     * resolved per row as the <b>first non-null value across all {@link JoinLookup#lookupAll}
     * matches</b>, scanning every join — bit-for-bit the legacy semantics of the joined-dataset
     * lookup in value and name position (single-value case). Using {@code lookupAll} instead of the
     * scalar {@code lookup} fixes the 1-to-many divergence where the first-wins matched row's cell
     * is null/missing but a later matched row is non-null.
     * <p>
     * Returns {@code null} when the name is carried by no join, so the enclosing predicate yields
     * an empty {@link BitSet} (the missing-column contract). The probe uses each join's schema (via
     * the {@link DatasetResolver}) to decide whether the name is carried, matching the previous
     * behaviour and avoiding a needless {@code lookupAll} pass on joins that cannot contain it.
     */
    private static @Nullable Vector joinedColumnVector(EvaluationContext ctx, int rowCount,
            String name)
    {
        for (Map.Entry<String, JoinLookup> entry : ctx.getJoinedDatasets().entrySet())
        {
            // Fix #358 (review F1): exact name first, else the split-domain union, so an
            // unqualified joined-column reference is carried on a split submission too.
            IDataTable foreign = net.cumba.cdisc.core.exec.SplitDomainResolution
                    .resolveTableOrThrow(ctx.getDatasetResolver(), entry.getKey(), ctx.getRuleId());
            if (foreign != null && foreign.getMetaData().getColumnIndex(name) >= 0)
            {
                // B2 (PLAN-native-engine-residuals): the foreign vector carries BOTH legacy views —
                // the scalar first-non-null (value position) AND the per-row candidate list with
                // the forEachJoinedValue live-lookup latch (name position). Predicate consumers
                // apply ANY-MATCH over the candidates via Primitives.scan.
                return new JoinedCandidatesVector(ctx, rowCount, name);
            }
        }
        return null;
    }


    /**
     * Value-position reference, following the column/var/literal-fallback order; never returns
     * {@code null} (an unresolved operand broadcasts {@code null}).
     */
    private static ValuePlan valueRefPlan(String rawName)
    {
        return run ->
        {
            EvaluationContext ctx = run.ctx();
            int rc = run.rowCount();
            String name = resolveDomainPrefix(rawName, ctx);
            if (name.indexOf('.') > 0)
            {
                return dottedVector(ctx, rc, name);
            }
            Object var = ctx.resolveVariable(name);
            if (var != null)
            {
                return variableVector(ctx, rc, var);
            }
            if (name.startsWith("$"))
            {
                return ConstVector.of(null);
            }
            DataTableMeta meta = ctx.getTable().getMetaData();
            int idx = meta.getColumnIndex(name);
            if (idx >= 0)
            {
                return new ColumnVector(ctx.getTable().getColumn(idx),
                        meta.getColumn(idx).getType());
            }
            // An unresolvable value-position identifier yields null, never the bareword itself:
            // bareword = column reference, quoted = literal.
            return new ComputedVector(rc, DataValueType.STRING, row -> firstJoined(ctx, row, name));
        };
    }


    /**
     * Whether {@code c} is an inline operation-function call (Form A) — a call whose name is a
     * known {@link OperationType} that should be computed through the {@link OperationExecutor}
     * rather than the {@link FunctionRegistry}.
     *
     * <p>
     * Collision guard (decision D5): {@code record_count} exists both as the arity-0 row-count
     * VALUE builtin and as an operation that accepts {@code filter}/{@code group}. Arity counts
     * positional arguments only, so the two share {@code (name, arity=0)} and cannot be
     * distinguished by the registry. A call to an operation-named function routes to the operation
     * path only when it carries an operation keyword argument <em>or</em> has no builtin overload
     * at its arity; bare {@code record_count()} keeps the builtin (semantically identical to an
     * unfiltered/ungrouped operation {@code record_count}).
     * </p>
     */
    // Public so BroadcastFold can recognise an inline-operation call as the dataset-fact operand
    // equivalent of a $-operation reference (native broadcast-verdict retention parity), and so
    // RulePackageLoader.injectInlineOperationGates detects ungated library/define/dictionary-
    // dependent inline calls with the engine's own recognition (single authority).
    public static boolean isInlineOperation(Expr.Call c)
    {
        if (OperationType.fromJson(c.name()) == null)
        {
            return false;
        }
        boolean hasBuiltin = FunctionRegistry.descriptor(c.name(), c.args().size()) != null;
        return !hasBuiltin || !c.kwargs().isEmpty();
    }


    /**
     * Compiles an inline operation-function (Form A) to a {@link ValuePlan} that, per evaluation,
     * runs the operation through the shared {@link OperationExecutor} against the live context
     * (primary table, dataset resolver, library provider, prior {@code $}-variables) and broadcasts
     * its result with {@link #variableVector} — exactly as a {@code $}-operation reference does, so
     * a scalar broadcasts as a {@link ConstVector} and a {@link GroupedResult} resolves per row.
     *
     * <p>
     * The {@link Operation} is built once at compile time from the call (the
     * {@link OperationExpressionParser} mapping shared with the Form-B loader path).
     * Library-dependent operations and {@code cross_dataset_variable_metadata} are <em>not</em>
     * inlinable (decision D3): they carry SKIP / per-variable semantics the expression operand path
     * cannot express, so they must stay authored as {@code Operations} entries. An inline use of
     * one is a {@link RuleDefinitionException} — a definitional rule error (loud {@code loadError}
     * / ERROR) — rather than a decline, because an inline operation has no {@code $}-variable and
     * so cannot fall back to the legacy engine the way an ordinary unsupported construct can.
     * </p>
     *
     * <p>
     * The supported inline surface is the <b>operand position</b> of a comparison / arithmetic /
     * function argument (where {@link #operandPlan} runs). A list-returning operation used as a
     * membership right-hand side or a group operand is not reached here ({@code buildSet} /
     * {@code groupOperandName} handle those positions) and stays Form B.
     * </p>
     *
     * <p>
     * No per-occurrence memoisation: the result is recomputed on every {@code eval(run)} (so a
     * variable-level rule that re-evaluates per column recomputes per column). The shipped corpus
     * is Form B, so inline operations are a hand-authoring surface; keep an expensive
     * inventory-scanning operation (e.g. {@code variable_count}) as an {@code Operations} entry to
     * retain the {@code LazyValue} single-execution.
     * </p>
     */
    private static ValuePlan operationCallPlan(Expr.Call c)
    {
        OperationType type = OperationType.fromJson(c.name());
        // cross_dataset_variable_metadata resolves per-variable (VariableMetadataResult) and has no
        // inline operand surface — author it as a var_*(dataset=) accessor (§9.D) instead.
        // Library-dependent operations DO compile inline (§9.C); their SKIP-on-missing-Library
        // semantics are restored by the `library_available() and available(<op>)` Precondition the
        // converter adds — a library op produces LIBRARY_NOT_AVAILABLE here only when the gate has
        // already skipped the rule, so the check never reaches it.
        if (type == OperationType.CROSS_DATASET_VARIABLE_METADATA)
        {
            throw new RuleDefinitionException("operation '" + c.name()
                    + "' cannot be inlined; author it as a var_*(dataset=) accessor");
        }
        Operation op = OperationExpressionParser.fromCall(c, null);
        return run ->
        {
            EvaluationContext ctx = run.ctx();
            Object result = inlineOperationResult(op, ctx);
            // A skipped / unresolvable operation (null) broadcasts null — no row fires — mirroring
            // an absent $-operation reference (valueRefPlan / nameRefPlan).
            return result == null ? ConstVector.of(null)
                    : variableVector(ctx, run.rowCount(), result);
        };
    }


    /**
     * Runs an inline operation against the live context, returning its raw result (a scalar / list
     * / {@link GroupedResult}, or {@code null} when skipped / unresolvable). Shared by the operand
     * path ({@link #operationCallPlan}) and the §9.A membership-set path so both feed
     * {@link OperationExecutor#executeOne} identically (same {@link #forcedPriors} group-$-var
     * unwrap).
     */
    private static @Nullable Object inlineOperationResult(Operation op, EvaluationContext ctx)
    {
        // Resolve `--` domain wildcards in name/domain/group against the run's domain prefix before
        // executing — the legacy path does this at rule-prep time
        // (RuleRunner.resolveOperationPrefix)
        // but the compiled program is domain-agnostic, so an inline operation must resolve here.
        // Without it a `--`-prefixed group/name column (e.g. record_count(group=[…, --TESTCD, …]))
        // names a non-existent column and the operation silently resolves to null.
        Operation resolved = OperationExecutor.resolvePrefixes(op, ctx.getDomainPrefix(),
                ctx.getVariableWildcardPrefix());
        return OperationExecutor.executeOne(resolved, ctx.getTable(), ctx.getDatasetResolver(),
                ctx.getLibraryProvider(), forcedPriors(resolved, ctx), ctx.getRuleId(),
                ctx.getDictionaryProvider(), ctx.getDefineProvider());
    }


    /**
     * The {@link Operation} for an inline list operation used as a membership right-hand side
     * (§9.A), or {@code null} when {@code right} is not an inlinable operation call (a
     * {@code $}-ref, a list literal, a wildcard, or a library / cross-dataset operation that cannot
     * be inlined). A {@code null} return lets the caller fall through to the {@code $}-ref /
     * literal {@link #buildSet} path (or its legacy decline).
     */
    private static @Nullable Operation inlineSetOperation(Expr right)
    {
        if (!(right instanceof Expr.Call c) || !isInlineOperation(c))
        {
            return null;
        }
        OperationType type = OperationType.fromJson(c.name());
        if (OperationExecutor.isLibraryDependent(type)
                || type == OperationType.CROSS_DATASET_VARIABLE_METADATA)
        {
            return null;
        }
        return OperationExpressionParser.fromCall(c, null);
    }


    /**
     * Folds an operation result into a membership {@link Set} with the same contract as
     * {@link #buildSet}'s {@code $}-reference branch: a {@link java.util.Collection} contributes
     * its (case-folded) elements, a non-grouped scalar a singleton, and a {@code null} /
     * unresolvable / {@link GroupedResult} result the empty set (a {@link GroupedResult} is handled
     * per row before this is reached).
     */
    private static Set<String> toSet(@Nullable Object result, boolean caseInsensitive)
    {
        if (result instanceof Collection<?> col)
        {
            Set<String> set = LinkedHashSet.newLinkedHashSet(col.size());
            for (Object o : col)
            {
                set.add(fold(o != null ? o.toString() : "", caseInsensitive));
            }
            return set;
        }
        if (result != null && !(result instanceof GroupedResult))
        {
            return Set.of(fold(result.toString(), caseInsensitive));
        }
        return Set.of();
    }


    /**
     * The prior-{@code $}-variable map to feed {@link OperationExecutor#executeOne} for an inline
     * operation. {@code OperationExecutor.expandGroupRefs} reads {@code group} entries that name a
     * {@code $}-variable straight out of this map and would see an unforced {@link LazyValue}
     * wrapper (neither a String nor a Collection) and silently drop the reference. So when the
     * operation's {@code group} names any {@code $}-variable, return a small map with exactly those
     * entries forced via {@link EvaluationContext#resolveVariable} (which unwraps
     * {@code LazyValue}); other variables stay lazy. With no {@code $}-group reference the live map
     * is passed through unchanged.
     */
    private static Map<String, Object> forcedPriors(Operation op, EvaluationContext ctx)
    {
        List<String> group = op.getGroup();
        if (group == null || group.stream().noneMatch(g -> g != null && g.startsWith("$")))
        {
            return ctx.getVariables();
        }
        Map<String, Object> forced = new LinkedHashMap<>();
        for (String g : group)
        {
            if (g != null && g.startsWith("$") && ctx.getVariables().containsKey(g))
            {
                forced.put(g, ctx.resolveVariable(g));
            }
        }
        return forced;
    }


    // Package-private (not private) so NativeExprEvaluatorTest can assert the Phase 10 literal-only
    // fold returns a broadcast ConstVector (single compile-time computation) rather than a per-row
    // ComputedVector.
    static ValuePlan valueCallPlan(Expr.Call c)
    {
        return valueCallPlan(c, false);
    }


    /** EC-43 variant: {@code foldAbsentColumn} reaches this call's ARGUMENT plans. */
    static ValuePlan valueCallPlan(Expr.Call c, boolean foldAbsentColumn)
    {
        FunctionDescriptor descriptor = FunctionRegistry.descriptor(c.name(), c.args().size());
        if (descriptor == null)
        {
            throw unsupported("no native function '" + c.name() + "' with " + c.args().size()
                    + " argument(s)");
        }
        if (descriptor.kind() != FunctionKind.VALUE)
        {
            throw unsupported("function '" + c.name() + "' is not a value function");
        }
        EvalFunction fn = descriptor.fn();
        List<ValuePlan> argPlans = new ArrayList<>(c.args().size());
        for (Expr arg : c.args())
        {
            argPlans.add(operandPlan(arg, true, foldAbsentColumn));
        }
        // Constant-fold a pure value function whose arguments are ALL literals: evaluate it once at
        // compile time (against a 1-row context-free run — the allowlisted transforms read only
        // run.rowCount(), never run.ctx()) and broadcast the single result as a ConstVector. The
        // value is row-independent, so this is identical to what the per-row path would yield on
        // every row, with the same missing/empty handling. Context-dependent calls (value/varname/
        // record_count/colref/var_*/ds_*) are excluded by the allowlist. No JoinedCandidatesVector
        // can occur here (all args are literals), so the candidate-propagation branch is
        // orthogonal.
        if (isPureFoldable(c.name()) && c.kwargs().isEmpty()
                && c.args().stream().allMatch(a -> a instanceof Expr.Lit))
        {
            List<Vector> litArgs = new ArrayList<>(argPlans.size());
            EvalRun foldRun = EvalRun.ofRowCount(1);
            for (ValuePlan p : argPlans)
            {
                litArgs.add(p.eval(foldRun));
            }
            Vector folded = (Vector) fn.apply(foldRun, litArgs);
            ConstVector cv = ConstVector.of(folded == null ? null : folded.resolvedObject(0));
            return _ -> cv;
        }
        java.util.function.UnaryOperator<String> unary = pureUnaryTransform(c.name());
        ValuePlan inner = run ->
        {
            List<Vector> args = new ArrayList<>(argPlans.size());
            for (ValuePlan p : argPlans)
            {
                Vector v = p.eval(run);
                if (v == null)
                {
                    return null; // missing column propagates -> empty enclosing result
                }
                args.add(v);
            }
            // B2 candidate propagation: a pure unary string transform over an unqualified foreign
            // reference keeps the per-row candidate list (each candidate transformed), so the
            // enclosing predicate still applies the legacy any-match OR — e.g. `len(X) > 8`
            // (longer_than) and `upper(X) in […]` (case-insensitive membership) evaluate per
            // joined value exactly like the legacy forEachJoinedValue loop applying the operator's
            // value logic per match.
            if (unary != null && args.size() == 1
                    && args.get(0) instanceof JoinedCandidatesVector jc)
            {
                DataValueType type = "len".equals(c.name()) || "length".equals(c.name())
                        ? DataValueType.LONG
                        : DataValueType.STRING;
                return jc.mapped(unary, type);
            }
            return (Vector) fn.apply(run, args, c.kwargs());
        };
        return cachedValue(c, inner);
    }


    /**
     * §3.2 (plan unified-callable-surface): the value twin of {@link #cachedBool} — a pure value
     * call (per the {@link DatasetExpressionCache} VALUE allow-list) is computed once per
     * {@code (table-instance, canonical-expression, domain-prefix)} and reused across the dataset's
     * rules, replacing the legacy {@code $}-var {@code LazyValue} single-execution advantage. The
     * §3.6 {@code cacheableAt} gate applies at eval time exactly as for booleans. {@link Vector}s
     * are immutable, so unlike the boolean path no defensive clone is needed; a {@code null} result
     * (missing column) is never stored — it falls through uncached.
     */
    private static ValuePlan cachedValue(Expr e, ValuePlan inner)
    {
        if (!DatasetExpressionCache.isPure(e))
        {
            return inner;
        }
        String canon = ExpressionPrinter.print(e);
        return run ->
        {
            EvaluationContext ctx = run.ctx();
            ExpressionResultCache cache = ctx.getExprCache();
            if (cache == null || !DatasetExpressionCache.cacheableAt(e, ctx))
            {
                return inner.eval(run);
            }
            // Lazy: a null result (missing column) is not stored and falls through uncached by
            // contract. A ComputedVector is fully materialised BEFORE the store: its internal
            // memo arrays are not thread-safe, but forcing every row first means the cached
            // instance is never mutated after the map put — and the ConcurrentHashMap store is
            // the happens-before publication point for those array writes (review finding 2).
            Object stored = cache.computeIfAbsent(
                    DatasetExpressionCache.keyOf(ctx.getTable(), canon, ctx.getDomainPrefix()),
                    () ->
                    {
                        Vector v = inner.eval(run);
                        if (v instanceof ComputedVector)
                        {
                            for (int row = 0; row < run.rowCount(); row++)
                            {
                                v.resolvedObject(row);
                            }
                        }
                        return v;
                    });
            return (Vector) stored;
        };
    }


    /**
     * The per-value form of a pure unary string transform — the converter-emitted wrappers around
     * name operands ({@code longer_than}/{@code shorter_than} → {@code len(X)}, the
     * case-insensitive surfaces → {@code upper(X)}/{@code lower(X)}) — or {@code null} when the
     * function is not in the (deliberately small) allowlist. Each mirrors the registered
     * {@code BuiltinFunctions} implementation byte-for-byte for non-missing values; missing
     * candidates never reach the transform ({@code JoinedCandidatesVector} maps non-null values
     * only).
     */
    private static java.util.function.@Nullable UnaryOperator<String> pureUnaryTransform(
            String name)
    {
        return switch (name)
        {
        // len("")=0 (operator-examples.md A.5 / function-examples.md): an empty-string candidate
        // is length 0, so the length operators evaluate it literally — legacy
        // evalLongerThan/ShorterThan now also fold "" to length 0, keeping legacy↔native parity.
        case "len", "length" -> s -> String.valueOf(s.length());
        case "upper", "upcase" -> s -> s.toUpperCase(Locale.ROOT);
        case "lower", "lowcase" -> s -> s.toLowerCase(Locale.ROOT);
        case "trim" -> String::strip;
        default -> null;
        };
    }

    /**
     * Pure value functions that may be constant-folded when every argument is a literal: a
     * context-free transform whose result depends only on its (compile-time-known) arguments, never
     * on the current row / dataset / {@link net.cumba.cdisc.core.exec.EvaluationContext}. Excludes
     * {@code value}/{@code varname}/{@code record_count}/{@code colref}, the {@code var_*}/
     * {@code ds_*} metadata accessors, and inline operations — all of which read {@code run.ctx()}.
     */
    private static final Set<String> PURE_FOLDABLE = Set.of("lower", "lowcase", "upper", "upcase",
            "len", "length", "trim", "abs", "round", "floor", "ceil", "year", "month", "day",
            "concat", "coalesce", "substring", "prefix", "suffix");

    /** Whether {@code name} is a pure value function eligible for literal-only constant folding. */
    private static boolean isPureFoldable(String name)
    {
        return PURE_FOLDABLE.contains(name);
    }


    /** The pattern operand of a regex predicate: STRICTLY a /regex/ literal. */
    private static ValuePlan regexLiteralPlan(String fn, Expr e)
    {
        if (e instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.REGEX)
        {
            ConstVector cv = ConstVector.of((String) lit.value());
            return _ -> cv;
        }
        throw new RuleDefinitionException(fn + " requires a /regex/ literal pattern argument");
    }

    // ---------------------------------------------------------------------
    // Metadata accessor functions (var_* / ds_*) — core-handled value operands
    // ---------------------------------------------------------------------


    /**
     * Compiles a {@code var_<attr>(name, level)} / {@code ds_<attr>([name,] level)} accessor into a
     * broadcast value operand. The {@code (attribute, level)} pair is validated <em>at compile
     * time</em>: an unsupported cell (e.g. {@code var_role(X, "DATA")}), an unknown level literal,
     * or a name operand that is neither a string literal nor the {@code variable_name} operand
     * raise a {@link RuleDefinitionException} (the rule is wrong) — distinct from
     * {@link ExpressionException} so it is filed as a rule definition error rather than a legacy
     * fallback. At evaluation time the value is read from the DATA table / DEFINE / LIBRARY source,
     * normalized to its canonical vocabulary (decision D5), and broadcast. A name / dataset /
     * metadata entry that resolves to nothing at a supported level broadcasts {@code null} (missing
     * — decision D4).
     */
    private static ValuePlan metadataPlan(MetadataAttribute attr, Expr.Call c)
    {
        // §9.D: a variable-scope var_* accessor may carry a `dataset="D"` keyword — read the named
        // variable's DATA-level metadata from another dataset (join-free, via the DatasetResolver),
        // the inline form of cross_dataset_variable_metadata. No other keyword is accepted.
        String foreignDataset = null;
        for (Map.Entry<String, Expr> kw : c.kwargs().entrySet())
        {
            if ("dataset".equals(kw.getKey()) && attr.scope() == MetadataAttribute.Scope.VARIABLE
                    && kw.getValue() instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.STRING)
            {
                foreignDataset = (String) lit.value();
            }
            else
            {
                throw new RuleDefinitionException(attr.functionName()
                        + " does not take keyword argument '" + kw.getKey() + "'");
            }
        }
        List<Expr> args = c.args();
        Expr nameExpr;
        Expr levelExpr;
        if (attr.scope() == MetadataAttribute.Scope.VARIABLE)
        {
            if (args.size() == 1)
            {
                // var_<attr>(level): the name defaults to the current variable (change #6),
                // exactly like var_<attr>(variable_name, level) — see the name handling below.
                nameExpr = null;
                levelExpr = args.get(0);
            }
            else if (args.size() == 2)
            {
                nameExpr = args.get(0);
                levelExpr = args.get(1);
            }
            else
            {
                throw new RuleDefinitionException(
                        attr.functionName() + " requires (level) or (name, level)");
            }
        }
        else if (args.size() == 1)
        {
            nameExpr = null;
            levelExpr = args.get(0);
        }
        else if (args.size() == 2)
        {
            nameExpr = args.get(0);
            levelExpr = args.get(1);
        }
        else
        {
            throw new RuleDefinitionException(
                    attr.functionName() + " requires (level) or (dataset_name, level)");
        }

        MetadataLevel level = levelLiteral(attr, levelExpr);
        if (!attr.supports(level))
        {
            throw new RuleDefinitionException(attr.functionName() + " is not available at the "
                    + level + " level (rule is invalid)");
        }
        if (foreignDataset != null
                && (level != MetadataLevel.DATA || crossDatasetField(attr) == null))
        {
            throw new RuleDefinitionException(
                    attr.functionName() + " dataset= is only supported at the DATA level for "
                            + "var_label / var_type / var_length / var_format");
        }

        boolean variableNameRef = false;
        String literalName = null;
        if (nameExpr == null)
        {
            // VARIABLE arity-1 (level) defaults the name to the current variable, resolved
            // identically to the variable_name operand; DATASET arity-1 keeps name == null
            // (the current dataset, handled downstream).
            variableNameRef = attr.scope() == MetadataAttribute.Scope.VARIABLE;
        }
        else if (nameExpr instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.STRING)
        {
            literalName = (String) lit.value();
        }
        else if (attr.scope() == MetadataAttribute.Scope.VARIABLE
                && isCurrentVariableName(nameExpr))
        {
            // The variable_name operand or a varname() call — both name the current variable.
            variableNameRef = true;
        }
        else
        {
            throw new RuleDefinitionException(
                    attr.functionName() + " name must be a string" + " literal"
                            + (attr.scope() == MetadataAttribute.Scope.VARIABLE
                                    ? " or the variable_name operand"
                                    : ""));
        }

        String capturedName = literalName;
        boolean fromVariableName = variableNameRef;
        String foreign = foreignDataset;
        return run ->
        {
            EvaluationContext ctx = run.ctx();
            String name = capturedName;
            if (fromVariableName)
            {
                Object resolved = ctx.resolveVariable("variable_name");
                if (resolved == null)
                {
                    return ConstVector.of(null);
                }
                name = resolved.toString();
            }
            if (foreign != null)
            {
                // Reuse the operation's own builder so the inline var_*(dataset=) value is
                // byte-identical to cross_dataset_variable_metadata (label / data_type / length /
                // format, including the STRING->"Char" / else->"Num" data_type mapping).
                String field = crossDatasetField(attr);
                Object value = name == null ? null
                        : VariableMetadataResult.build(ctx.getDatasetResolver(), foreign, field,
                                ctx.getDomainName()).getForVariable(name);
                return ConstVector.of(value);
            }
            String raw = readMetadata(ctx, attr, level, name);
            if (attr.isList())
            {
                // A list-valued attribute (e.g. var_codelist_coded_codes) is carried JSON-encoded
                // through the string provider channel; materialise it as a List<String> operand so
                // membership compares it element-wise (mirrors Python's is_column_of_iterables).
                return ConstVector.of(DefineMetadataListCodec.decode(raw));
            }
            return ConstVector.of(attr.normalize(raw));
        };
    }


    /**
     * The {@code cross_dataset_variable_metadata} field string a variable-scope DATA-level
     * {@link MetadataAttribute} maps to ({@code var_label}&rarr;{@code "label"},
     * {@code var_type}&rarr;{@code "data_type"}, {@code var_length}&rarr;{@code "length"},
     * {@code var_format}&rarr;{@code "format"}), or {@code null} for an attribute with no foreign
     * read (so {@code dataset=} is rejected for it).
     */
    private static @Nullable String crossDatasetField(MetadataAttribute attr)
    {
        return switch (attr)
        {
        case VAR_LABEL -> "label";
        case VAR_TYPE -> "data_type";
        case VAR_LENGTH -> "length";
        case VAR_FORMAT -> "format";
        default -> null;
        };
    }


    /**
     * Whether {@code e} names the current variable in a {@code var_<attr>} name position: the bare
     * {@code variable_name} operand or a zero-arg {@code varname()} call. Both resolve to the
     * current variable's name (the {@code variable_name} context variable), so the level-only
     * overload {@code var_<attr>(level)} and these explicit forms share one resolution path.
     */
    public static boolean isCurrentVariableName(Expr e)
    {
        if (e instanceof Expr.Ref ref && "variable_name".equals(ref.name()))
        {
            return true;
        }
        return e instanceof Expr.Call c && "varname".equals(c.name()) && c.args().isEmpty()
                && c.kwargs().isEmpty();
    }


    private static MetadataLevel levelLiteral(MetadataAttribute attr, Expr levelExpr)
    {
        if (levelExpr instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.STRING)
        {
            return MetadataLevel.parse((String) lit.value());
        }
        throw new RuleDefinitionException(
                attr.functionName() + " level must be a string literal (DATA, DEFINE, or LIBRARY)");
    }


    private static @Nullable String readMetadata(EvaluationContext ctx, MetadataAttribute attr,
            MetadataLevel level, @Nullable String name)
    {
        return switch (level)
        {
        case DATA -> readDataLevel(ctx, attr, name);
        case DEFINE -> readProviderLevel(ctx.getDefineProvider(), ctx, attr, name,
                MetadataLevel.DEFINE);
        case LIBRARY -> readProviderLevel(ctx.getLibraryProvider(), ctx, attr, name,
                MetadataLevel.LIBRARY);
        };
    }


    /**
     * Resolves a <b>dataset-scope</b> metadata operand name ({@code dataset_*} /
     * {@code library_dataset_*} / {@code define_dataset_*}) for the current dataset — the
     * finding-projection twin of the {@code ds_*} accessor compilation ({@link #metadataPlan}),
     * reading the SAME sources ({@code readMetadata}) so a reported Output_Variables value always
     * equals the evaluated one (EC-37 Phase 3, {@code PLAN-auto-output-variables}). Returns
     * {@code null} when {@code operand} is not a dataset-scope operand name, names an
     * attribute/level cell that does not exist, or resolves to nothing at its level — callers omit
     * the entry rather than reporting a {@code null} value.
     */
    public static @Nullable String datasetScopeOperandValue(EvaluationContext ctx, String operand)
    {
        MetadataLevel level;
        String suffix;
        if (operand.startsWith("library_dataset_"))
        {
            level = MetadataLevel.LIBRARY;
            suffix = operand.substring("library_dataset_".length());
        }
        else if (operand.startsWith("define_dataset_"))
        {
            level = MetadataLevel.DEFINE;
            suffix = operand.substring("define_dataset_".length());
        }
        else if (operand.startsWith("dataset_"))
        {
            level = MetadataLevel.DATA;
            suffix = operand.substring("dataset_".length());
        }
        else
        {
            return null;
        }
        MetadataAttribute attr = MetadataAttribute
                .fromFunction("ds_" + ("data_type".equals(suffix) ? "type" : suffix));
        if (attr == null || attr.scope() != MetadataAttribute.Scope.DATASET
                || !attr.supports(level))
        {
            return null;
        }
        return attr.normalize(readMetadata(ctx, attr, level, null));
    }


    private static @Nullable String readDataLevel(EvaluationContext ctx, MetadataAttribute attr,
            @Nullable String name)
    {
        DataTableMeta meta = ctx.getTable().getMetaData();
        if (attr.scope() == MetadataAttribute.Scope.DATASET)
        {
            return switch (attr)
            {
            case DS_NAME -> meta.getName();
            case DS_LABEL -> meta.getLabel();
            case DS_CLASS -> ctx.getClassName();
            // ⚠ The one member of this family that is NOT a plain getter: a domain is not a field
            // on DataTableMeta, it is derived from the data. unsplitNameFromData is the SAME leg
            // Scope.Domains matches against, so `dataset_domain == "X"` and
            // `Scope.Domains.Include: [X]` agree by construction.
            case DS_DOMAIN -> OperationExecutor.unsplitNameFromData(ctx.getTable());
            default -> null;
            };
        }
        if (name == null)
        {
            return null;
        }
        int idx = meta.getColumnIndex(name);
        if (idx < 0)
        {
            return null;
        }
        var col = meta.getColumn(idx);
        return switch (attr)
        {
        case VAR_NAME -> col.getName();
        case VAR_LABEL -> col.getLabel();
        // var_type is the column's post-load data type (the authoritative DataValueType) mapped to
        // Char/Num. The Python parity harness mirrors this by giving each study variable the same
        // loaded type (see engine_adapter), so both engines agree without consulting nativeType —
        // a passive source-format record that must not drive rule logic.
        case VAR_TYPE -> charOrNum(col.getType());
        // A non-positive declared length is "unspecified" -> missing, matching the provider levels.
        case VAR_LENGTH -> col.getLength() > 0 ? Integer.toString(col.getLength()) : null;
        case VAR_FORMAT -> col.getDisplayFormat();
        case VAR_ORDINAL -> Integer.toString(idx);
        default -> null;
        };
    }


    private static @Nullable String readProviderLevel(@Nullable MetadataProvider provider,
            EvaluationContext ctx, MetadataAttribute attr, @Nullable String name,
            MetadataLevel level)
    {
        // Provider absent ⇒ missing, matching the *_variable_* / *_dataset_* operands this family
        // replaces (those resolve to null when their level's provider is not configured).
        if (provider == null)
        {
            return null;
        }
        Map<String, String> meta;
        if (attr.scope() == MetadataAttribute.Scope.DATASET)
        {
            String dataset = name != null ? name : ctx.getDomainName();
            if (dataset == null)
            {
                return null;
            }
            meta = provider.getDatasetMetadata(dataset); // tier 1 — the name
            // Fix #370 — the CDISC Library publishes DOMAINS, never the sponsor's split-member file
            // names, so "unknown here" means "that name is not a standard dataset" and never "this
            // dataset has no metadata": measured, 409 SDTM/SEND library datasets, 0 without a
            // label. Falling back therefore cannot mask a real "the Library says this dataset has
            // no label", because no such case exists.
            //
            // ⚠⚠ LIBRARY ONLY. A Define-XML declares one ItemGroupDef per dataset FILE, split
            // members included, so the member name is the CORRECT key at DEFINE; a fallback there
            // would answer a split member from its PARENT's declaration and make FDA-SD1325 /
            // PMDA-SD1325 (`ds_label("DATA") != ds_label("DEFINE")`) fire on conforming data.
            //
            // ⚠ The level is a PROXY for "this provider is domain-keyed", and the proxy is not
            // exact. `CdiscLibraryProviderBuilder.buildOrDegraded` returns a study-backed
            // `MetadataLibraryProvider(studyLib)` — member-name-keyed — when there is no access,
            // an UNKNOWN standard, or a non-impl access object. Those runs are NOT degraded, so
            // `Fix #369`'s `libraryAnswerable` gate (RuleRunner, "LIBRARY-level metadata may not
            // be answered from a non-library source") does not skip them, and tier 2 could answer
            // a split member from the define's parent declaration. It cannot move a finding today
            // (zero corpus rules read this leg), and the exact gate is a provider-provenance
            // capability that does not exist on MetadataProvider. FILED, not fixed here.
            //
            // ⚑ `name == null` guards it: an explicit ds_label("AE", "LIBRARY") names the dataset
            // the author wants, and second-guessing that would be wrong.
            if (unknownDataset(meta) && level == MetadataLevel.LIBRARY && name == null)
            {
                meta = libraryDomainFallback(provider, ctx.getTable(), dataset);
            }
        }
        else
        {
            // Fix #373 — the VARIABLE-scope twin of Fix #370's dataset leg. `ctx.getDomainName()`
            // is
            // the MEMBER name, and `MetadataLibraryProvider.getVariableMetadata` is keyed by it:
            // leg
            // 1 (`findColumn` → `library.getDataTable(member)`) misses for a split member because
            // the CDISC Library publishes DOMAINS, never the sponsor's split-member file names, and
            // leg 2 (`buildResolvedSdtm`) has no split-member tier, so `qsco` / `QS1` / `LB1` reach
            // its step 2 as themselves.
            //
            // ⭐ MEASURED, twice, and it settles what the previous comment here got wrong: the
            // result is NULL, not a plausible-but-wrong value. `CustomDomainClassDetector`
            // prefixes its topic-variable probes with the string it is passed, so for `qsco` it
            // looks for `QSCOTESTCD` while a real QS split member carries `QSTESTCD` — the sniffer
            // misses for the same reason the domain lookup missed. Confirmed on a PRODUCT-BACKED
            // provider (sdtmig 3-4 + sdtmct-2024-09-27), not just a product-less one:
            // `qsco`/`QS1`/`LB1`/`APMH1` return null for var_label/type/role/core.
            // ⇒ the fix is null → value: findings APPEAR. ⚠ They can also DISAPPEAR, because a null
            // read is not silence — it compares as "" (`Primitives.equality` ends
            // `negate != equal`, no null propagation), so an UNGUARDED `!=` against a LIBRARY read
            // fires today and goes quiet once the read resolves. `CDISC-SEND-0005` is exactly that
            // shape.
            //
            // ⚠⚠ LIBRARY ONLY, and enforced by the level check below rather than inside the
            // provider: a Define-XML declares one ItemGroupDef per dataset FILE, split members
            // included, so the member name is the CORRECT key at DEFINE.
            String domain = ctx.getDomainName();
            if (domain == null || name == null)
            {
                return null;
            }
            meta = level == MetadataLevel.LIBRARY
                    ? libraryVariableMetadata(provider, ctx.getTable(), domain, name)
                    : provider.getVariableMetadata(domain, name);
        }
        return meta != null ? meta.get(attr.providerKey()) : null;
    }


    /**
     * <b>Fix #373</b> — the VARIABLE-scope twin of {@link #libraryDomainFallback}: ask the CDISC
     * Library about {@code member}, falling back to the member's CDISC domain when it answers
     * nothing.
     *
     * <p>
     * ⚠⚠ <b>LIBRARY ONLY.</b> Call this from LIBRARY-level sites and nowhere else — the DEFINE
     * providers are member-keyed <em>by design</em> (one {@code ItemGroupDef} per dataset FILE), so
     * the same fallback there would answer a split member from its PARENT's declaration.
     * </p>
     *
     * <p>
     * ⭐⭐ <b>The retry predicate is the design, and "empty" is the deliberate choice.</b> A split
     * member returns an EMPTY map (measured: {@code qsco}, {@code QS1}, {@code LB1},
     * {@code APMH1}), so the retry fires for exactly those. A {@code SUPP--}/{@code SQ--}/
     * {@code AP--} member returns a NON-empty map from leg 2, so the retry does <b>not</b> fire for
     * them — which is correct, because their own defect (leg 2 carries no codelist family) is fixed
     * inside {@code MetadataLibraryProvider} instead, where the canonical name is known. Resolving
     * them here would be actively wrong for SUPP: a supplemental dataset has no {@code DOMAIN}
     * column, so {@code cdiscDomainOf} could not answer it correctly anyway.
     * </p>
     *
     * <p>
     * ⚑ A dataset-LEVEL predicate ({@code getDatasetMetadata(member).isEmpty()}) was tried first
     * and measurably fails: the Library knows only canonical names, so it reports {@code SUPPAE},
     * {@code APAE} and {@code qsco} alike as unknown and cannot tell them apart.
     * </p>
     *
     * <p>
     * ⭐⭐ <b>TIER 1 WINNING IS LOAD-BEARING, not an optimisation.</b> Two shapes depend on it: (a)
     * {@code CdiscLibraryProviderBuilder.buildOrDegraded}'s no-access branch returns a
     * <em>study-backed</em> {@code MetadataLibraryProvider}, which is member-keyed — tier 1 is the
     * only correct answer there; (b) a {@code SUPPAE} table that happens to carry a {@code DOMAIN}
     * cell would resolve to {@code AE} under tier 2 and lose {@code RDOMAIN} / {@code QEVAL}
     * entirely. Reversing the order compiles and passes most tests, so it is pinned deliberately by
     * {@code tierOneWins_evenWhenATableCouldResolveADomain}.
     * </p>
     *
     * <p>
     * ⚑ {@code cdiscDomainOf} reads the row-0 {@code DOMAIN} cell first, then
     * {@code SplitDatasetUtil.unsplitName}, then the raw name — so {@code APMH1} resolves whether
     * its {@code DOMAIN} cell says {@code MH} or the name has to be unsplit, and no AP strip is
     * involved. {@code APMH1} is the case a naive fix silently misses.
     * </p>
     */
    public static Map<String, String> libraryVariableMetadata(MetadataProvider provider,
            @Nullable IDataTable table, String member, String variable)
    {
        Map<String, String> byMember = provider.getVariableMetadata(member, variable);
        if (byMember != null && !byMember.isEmpty())
        {
            return byMember; // tier 1 — the member name, and it WINS (see the javadoc)
        }
        String domain = libraryVariableDomain(table, member);
        if (!domain.equals(member))
        {
            Map<String, String> byDomain = provider.getVariableMetadata(domain, variable);
            if (byDomain != null && !byDomain.isEmpty())
            {
                return byDomain; // tier 2 — the CDISC domain, AP prefix preserved
            }
            // ⚑ Tier 3 — the SAME domain with the AP prefix dropped. Tier 2 keeps the prefix so a
            // PRODUCT-BACKED provider can run its AP shim (see libraryVariableDomain), but a
            // product-LESS one — a degraded or Define-only run — has no algorithm-B leg at all, so
            // it can only answer the bare domain that its library actually publishes. Asking the
            // richer key first and the plainer key second serves both without a provider check.
            String bare = withoutApPrefix(domain);
            if (!bare.equals(domain))
            {
                Map<String, String> byBare = provider.getVariableMetadata(bare, variable);
                if (byBare != null && !byBare.isEmpty())
                {
                    return byBare;
                }
            }
        }
        // ⚑ Never null, although `getVariableMetadata` is contracted to return an empty map and a
        // null answer is tolerated on the way IN (a provider implemented outside this module, or a
        // test double). This method is declared non-null and public; returning the provider's null
        // straight through would break that contract for the next caller.
        return Map.of();
    }


    /** {@code APMH} → {@code MH}; anything not AP-prefixed is returned unchanged. */
    private static String withoutApPrefix(String name)
    {
        return isApPrefixed(name) ? name.substring(2) : name;
    }


    /**
     * <b>Fix #373</b> — the key tier 2 of {@link #libraryVariableMetadata} uses, exposed separately
     * for {@code library_variable_code_pair_matches}, which needs the SAME key for three
     * {@code getCodelistCodeMap} lookups and must not re-probe between them. Returns {@code member}
     * unchanged when nothing better resolves, so callers can compare by identity to detect "no
     * fallback available".
     */
    static String libraryVariableDomain(@Nullable IDataTable table, String member)
    {
        // ⚑ CdiscDomainResolver.cdiscDomainOf documents "may be null (returns empty string)" and
        // its body checks for it, but its parameter is not annotated @Nullable — so NullAway
        // rejects the call. Guarded here rather than widening that signature, which is outside this
        // fix's scope (drift, surfaced in the plan's fix ledger).
        if (table == null)
        {
            return member;
        }
        String domain = CdiscDomainResolver.cdiscDomainOf(table);
        if (domain.isEmpty())
        {
            return member;
        }
        // ⛔⛔ An AP-- member's Library key must KEEP its AP prefix. `cdiscDomainOf` prefers the
        // row-0 DOMAIN cell, which on a conforming AP dataset holds the RELATED domain code (MH for
        // APMH1) — but the AP shim lives on the AP-prefixed key: `buildResolvedSdtm` strips the
        // prefix ITSELF and sets `addAP`, which merges the ASSOCIATED PERSONS identifiers (APID,
        // RSUBJID, SREL). Handing the provider the bare domain code skips that merge, so those
        // three variables resolve to NOTHING on APMH1 while resolving fine on an unsplit APMH —
        // i.e. the better-populated dataset would get the worse answer.
        // ⚑ Re-prefixing is enough because tier 2 only runs after tier 1 missed, and the resolver
        // strips AP again on the way in. Found by the Fix #373 code review; the original fix
        // handled APMH1's parent-domain variables (MHTERM) and silently lost the AP-shim ones.
        if (isApPrefixed(member) && !isApPrefixed(domain))
        {
            return "AP" + domain;
        }
        return domain;
    }


    /** {@code AP} + at least one more character — the shape {@code buildResolvedSdtm} strips. */
    private static boolean isApPrefixed(String name)
    {
        return name.length() > 2 && name.regionMatches(true, 0, "AP", 0, 2);
    }


    /**
     * Whether a {@code getDatasetMetadata} answer means <em>"this provider does not know that
     * dataset"</em>.
     *
     * <p>
     * ⛔⛔ <b>The contract is an EMPTY MAP, not {@code null}</b> —
     * {@link MetadataProvider#getDatasetMetadata} says <em>"or empty map if unknown"</em>, and
     * every shipped implementation honours it: {@code MetadataLibraryProvider} returns
     * {@code Map.of()} for a domain the library has no data table for, as does the rule-test
     * {@code MapBackedLibraryMetadataProvider}. A {@code meta == null} test would therefore make
     * the whole {@code Fix #370} fallback <b>inert in production and in the .cdt suites</b>, while
     * a null-returning unit-test stub kept its tests green — the exact shape of the
     * {@code Fix #369} no-op. {@code null} is still accepted, because the interface is
     * implementable outside this module.
     * </p>
     */
    private static boolean unknownDataset(@Nullable Map<String, String> meta)
    {
        return meta == null || meta.isEmpty();
    }


    /**
     * <b>Fix #370</b> — the LIBRARY-only fallback for a dataset name the CDISC Library does not
     * publish, applied after the name leg (tier 1) has missed.
     *
     * <ol>
     * <li><b>Tier 2 — the CDISC domain</b>, resolved by {@link CdiscDomainResolver#cdiscDomainOf}:
     * <em>exactly</em> the resolution the retired {@code domain_label()} operation used. ⚠
     * Deliberately <b>not</b> {@code OperationExecutor.unsplitNameFromData} — the two differ for a
     * dataset with no {@code DOMAIN} column or no rows, and identical resolution is what made
     * retiring {@code domain_label()} onto this accessor (<b>Fix #371</b>) a refactor rather than a
     * behaviour change hiding inside a cleanup. ⚑ {@code domain_label()} and its
     * {@code OperationType.DOMAIN_LABEL} arm no longer exist; this accessor is the only
     * dataset-label read left.</li>
     * <li><b>Tier 3 — {@code SUPP--} / {@code SQ--} → {@code SUPPQUAL}</b>, with the label template
     * substituted from {@code RDOMAIN}; see {@link #suppQualMetadata}.</li>
     * </ol>
     *
     * @return the resolved metadata, or an empty map when no tier answers (a genuinely custom
     *         domain, or an {@code AP--} dataset — see the class-level note in the plan: Associated
     *         Persons datasets live in a separate product this rule never declares, so all three
     *         tiers miss by design and the accessor keeps returning {@code null}).
     */
    private static Map<String, String> libraryDomainFallback(MetadataProvider provider,
            IDataTable table, String dataset)
    {
        // ⚠⚠ A supplemental dataset is answered by SUPPQUAL or NOT AT ALL — tier 2 is not tried
        // for it, and not tried after tier 3 declines either.
        //
        // ⭐ The reason is the STANDARD's own variable list, not a preference. Measured against the
        // Library cache: SUPPQUAL publishes STUDYID, RDOMAIN, USUBJID, IDVAR, IDVARVAL, QNAM,
        // QLABEL, QVAL, QORIG, QEVAL (sdtmig 3-1-2; sendig 3-0 adds POOLID) — `RDOMAIN`, and
        // **no `DOMAIN` at all**. Owner-confirmed 2026-08-27: *"The SUPP--/SQ-- should not have a
        // DOMAIN variable, it should have RDOMAIN."*
        // ⇒ `cdiscDomainOf` reads the row-0 `DOMAIN` cell first, so ordering tier 2 ahead of tier 3
        // would resolve a supplemental dataset from a column the standard does not define for it —
        // deriving a label from a variable that should not be there. A `SUPPAE` carrying
        // `DOMAIN=AE` would answer "Adverse Events" instead of "Supplemental Qualifiers for AE".
        // The SUPP/SQ prefix and `RDOMAIN` are the two things that ARE part of a supplemental
        // dataset's published shape, so those — and only those — decide it.
        //
        // ⚠ This is the one point where this accessor and `domain_label()` disagree, so retiring
        // `domain_label()` onto it is a pure refactor everywhere EXCEPT that non-conforming shape.
        if (isSupplementalName(dataset))
        {
            return suppQualMetadata(provider, table);
        }
        String domain = CdiscDomainResolver.cdiscDomainOf(table);
        // ⚑ `!domain.equals(dataset)` is an OPTIMISATION, not a correctness guard: re-asking the
        // provider with the key that just missed returns the same empty map. It reads like a guard,
        // so it is worth saying that nothing depends on it.
        if (!domain.isEmpty() && !domain.equals(dataset))
        {
            Map<String, String> byDomain = provider.getDatasetMetadata(domain);
            if (!unknownDataset(byDomain))
            {
                return byDomain;
            }
        }
        return Map.of();
    }


    /**
     * Whether the dataset name is a supplemental-qualifiers one. The {@code length() > 2} bound is
     * {@code MetadataLibraryProvider.buildResolvedSdtm}'s own step-1 test, reused rather than
     * re-invented so a bare {@code SQ} or a two-character sponsor name is not swept in by the short
     * {@code SQ} prefix.
     */
    private static boolean isSupplementalName(String dataset)
    {
        String upper = dataset.toUpperCase(Locale.ROOT);
        return upper.length() > 2 && (upper.startsWith("SUPP") || upper.startsWith("SQ"));
    }


    /**
     * <b>Fix #370</b> tier 3 — the CDISC Library publishes exactly <b>one</b> supplemental dataset,
     * {@code SUPPQUAL} (SDTMIG 3-4: 63 datasets, one of them supplemental, class
     * {@code Relationship}); there is no {@code SUPPAE}, {@code SUPPLB} or {@code SQAPAE}. Its
     * label is a <b>template</b>, so a {@code SUPP--}/{@code SQ--} dataset is mapped onto it and
     * the placeholder substituted with the parent domain read from {@code RDOMAIN} — yielding
     * {@code "Supplemental Qualifiers for AE"} for {@code SUPPAE}, which is SDTMIG's own convention
     * for the real dataset label.
     *
     * <p>
     * ⚑ {@code SUPPAE} is <em>not</em> a "letter split": {@code SplitDatasetUtil.isSuppLetterSplit}
     * requires length 7–11, so {@code unsplitName("SUPPAE")} (6 chars) returns {@code SUPPAE}
     * unchanged while {@code SUPPLBHM} (8) becomes {@code SUPPLBH}. Neither is in the Library, so
     * this tier keys off the {@code SUPP}/{@code SQ} <b>prefix</b> and both reach it.
     * </p>
     *
     * @return the substituted {@code SUPPQUAL} metadata, or an <b>empty map</b> when the Library
     *         publishes no {@code SUPPQUAL} or nothing survives the template check below — which
     *         {@link #unknownDataset} and the caller's {@code meta.get(...)} both already read as
     *         "no answer", so there is deliberately no separate {@code null} signal to get wrong.
     */
    private static Map<String, String> suppQualMetadata(MetadataProvider provider, IDataTable table)
    {
        Map<String, String> supp = provider.getDatasetMetadata("SUPPQUAL");
        if (unknownDataset(supp))
        {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>(supp);
        // The contract builds this map with putIfPresent, so an in-repo provider never yields a
        // null value; an out-of-repo one could, and the map is walked wholesale.
        out.values().removeIf(Objects::isNull);
        String parent = OperationExecutor.firstRowValue(table, "RDOMAIN");
        if (parent != null)
        {
            out.replaceAll((_, v) -> substitutePlaceholder(v, parent));
        }
        // ⛔⛔ NEVER hand back a TEMPLATE as though it were a value. Without this, a 0-row or
        // RDOMAIN-less SUPPAE answers ds_label("LIBRARY") with the literal
        // "Supplemental Qualifiers for [domain name]", and the first rule to compare it against
        // ds_label("DATA") fires on CONFORMING data. Dropping the key leaves the accessor's
        // documented "null = could not resolve", and the non-templated keys (className,
        // datasetStructure) still answer. Same rule retires a CDISC reword the placeholder no
        // longer matches — which substitutePlaceholder has already WARNED about.
        out.values().removeIf(v -> BRACKETED_TOKEN.matcher(v).find());
        return Collections.unmodifiableMap(out);
    }


    /**
     * Test seam — clears the once-per-label ledger behind {@link #substitutePlaceholder}'s WARNING
     * so a second run in the same JVM observes the first warning again. Without it the "warns
     * exactly once" assertion depends on JVM-global state and turns into a confusing flake under
     * surefire reruns or a {@code @RepeatedTest}.
     */
    static void resetPlaceholderWarnings()
    {
        WARNED_TEMPLATE_LABELS.clear();
    }


    /**
     * Substitutes {@link #DOMAIN_NAME_PLACEHOLDER} in one {@code SUPPQUAL} metadata value with the
     * parent domain, and — when the value carries a bracketed token the placeholder did <b>not</b>
     * match — logs the one WARNING that makes a CDISC reword visible instead of silent.
     *
     * <p>
     * ⚑ This is the lesson {@code Fix #369} cost two cycles to learn, applied prophylactically: a
     * lookup that returns something plausible when it has actually stopped working is worse than
     * one that fails. The value is still returned and the run is not failed — the warning is the
     * signal, not the policy.
     * </p>
     */
    private static String substitutePlaceholder(String value, String parent)
    {
        Matcher matcher = DOMAIN_NAME_PLACEHOLDER.matcher(value);
        if (matcher.find())
        {
            return matcher.reset().replaceAll(Matcher.quoteReplacement(parent));
        }
        if (BRACKETED_TOKEN.matcher(value).find() && WARNED_TEMPLATE_LABELS.add(value))
        {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Fix #370: a CDISC Library SUPPQUAL metadata value carries a bracketed token "
                            + "that the [domain name] placeholder does not match: \"{0}\". The "
                            + "SUPP--/SQ-- label tier is INERT for it and ds_label(\"LIBRARY\") "
                            + "returns the raw template. Update DOMAIN_NAME_PLACEHOLDER in "
                            + "ExprCompiler.",
                    value);
        }
        return value;
    }


    /** DATA-level data type folded to {@code Char} / {@code Num} (mirrors the provider mapping). */
    private static @Nullable String charOrNum(@Nullable DataValueType type)
    {
        if (type == null)
        {
            return null;
        }
        return switch (type)
        {
        case STRING -> "Char";
        case LONG, DOUBLE, BOOLEAN -> "Num";
        default -> null;
        };
    }

    // ---------------------------------------------------------------------
    // Eval-time operand resolution helpers (mirror ValueResolver / forEachValue)
    // ---------------------------------------------------------------------


    private static Vector dottedVector(EvaluationContext ctx, int rowCount, String name)
    {
        int dot = name.indexOf('.');
        String ds = name.substring(0, dot);
        String col = name.substring(dot + 1);
        JoinLookup lookup = ctx.getJoinedDatasets().get(ds);
        if (lookup == null)
        {
            return ConstVector.of(null);
        }
        return new ComputedVector(rowCount, DataValueType.STRING,
                row -> lookup.lookup(ctx.getTable(), row, col));
    }


    private static Vector variableVector(EvaluationContext ctx, int rowCount, Object var)
    {
        if (var instanceof GroupedResult grouped)
        {
            // getForRowOrDefault (not getForRow): an absent group key resolves to the op-scoped
            // default (0L for record_count -- a subject with zero matching rows is a real 0, so
            // e.g. `$count <= 1` fires -- null otherwise), mirroring the legacy
            // the row-loop default. The same default
            // now flows to the comparison value side and the report output (RuleRunner), so a
            // record_count $var renders and compares as 0 on every path.
            return new ComputedVector(rowCount, DataValueType.STRING,
                    row -> grouped.getForRowOrDefault(ctx, row));
        }
        return ConstVector.of(var);
    }


    private static @Nullable String firstJoined(EvaluationContext ctx, long row, String name)
    {
        for (JoinLookup lookup : ctx.getJoinedDatasets().values())
        {
            for (String v : lookup.lookupAll(ctx.getTable(), row, name))
            {
                if (v != null)
                {
                    return v;
                }
            }
        }
        return null;
    }


    /**
     * Phase 9b (decision D2): classifies a membership <b>list literal</b> by member type. Returns
     * the parsed {@code Set<Double>} of members when EVERY member is a numeric literal (all-numeric
     * ⇒ numeric membership), {@code null} when no member is numeric (all-string ⇒ textual
     * membership, the existing {@link #buildSet} path), and throws a
     * {@link RuleDefinitionException} when the list MIXES at least one numeric and at least one
     * non-numeric member. Any non-list RHS (a scalar literal, a {@code $}-variable list, a
     * {@code ${*}} wildcard) returns {@code null} so the textual path runs — only a static list
     * literal is numeric-classified.
     *
     * <p>
     * "Numeric member" is exactly an {@code Expr.Lit} of kind {@link Expr.LitKind#NUMBER}; "non-
     * numeric" is any other member (a {@code STRING}/{@code BOOL} literal, or an {@code upper(...)}
     * wrapper from the case-insensitive surface — though that surface never reaches here). This
     * mirrors the legacy classification over the source JSON-array nodes
     * ({@code JsonNode.isNumber()} vs not), so both engines agree element-for-element on the
     * shipped corpus (15 all-integer lists).
     * </p>
     */
    private static @Nullable Set<Double> numericMemberSet(Expr right)
    {
        if (!(right instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.LIST)
        {
            return null;
        }
        @SuppressWarnings("unchecked")
        List<Expr> items = (List<Expr>) lit.value();
        boolean anyNumeric = false;
        boolean anyNonNumeric = false;
        for (Expr item : items)
        {
            if (item instanceof Expr.Lit m && m.kind() == Expr.LitKind.NUMBER)
            {
                anyNumeric = true;
            }
            else
            {
                anyNonNumeric = true;
            }
        }
        if (anyNumeric && anyNonNumeric)
        {
            throw new RuleDefinitionException("membership list mixes numeric and string members");
        }
        if (!anyNumeric)
        {
            return null; // all-string (or empty) — textual membership
        }
        Set<Double> members = LinkedHashSet.newLinkedHashSet(items.size());
        for (Expr item : items)
        {
            members.add((Double) ((Expr.Lit) item).value());
        }
        return members;
    }


    private static Set<String> buildSet(EvalRun run, Expr right, boolean caseInsensitive)
    {
        if (right instanceof Expr.Lit lit)
        {
            if (lit.kind() == Expr.LitKind.LIST)
            {
                @SuppressWarnings("unchecked")
                List<Expr> items = (List<Expr>) lit.value();
                Set<String> set = LinkedHashSet.newLinkedHashSet(items.size());
                for (Expr item : items)
                {
                    set.add(setTerm(item, caseInsensitive));
                }
                return set;
            }
            return Set.of(setTerm(lit, caseInsensitive));
        }
        if (right instanceof Expr.Ref ref)
        {
            Object var = run.ctx().resolveVariable(ref.name());
            // Follow the $-reference contract so the
            // membership set matches the legacy operator exactly: a Collection contributes its
            // elements, a non-grouped scalar a singleton, and a null / absent operation result the
            // EMPTY set — never a fatal "unsupported" throw. The empty-set case covers an
            // operation that produced no result, so the $-ref resolves to nothing — e.g.
            // CORE-000712's value_is_reference `distinct` ($rdomain_variables) when its "SUPP--"
            // target dataset does not resolve, or when the SUPP-- table has no RDOMAIN column.
            // Legacy then runs is_(not_)contained_by against an empty set, and so must the native
            // path. A GroupedResult never reaches here — compileMembership routes
            // GroupedResult-valued refs to groupedMembership before calling buildSet. The
            // Collection / scalar / null folding is shared with the §9.A inline-set path via toSet.
            return toSet(var, caseInsensitive);
        }
        throw unsupported("membership right-hand side must be a list literal or a $-variable list");
    }


    /**
     * A membership-set term: unwraps an {@code upper("…")} wrapper (the case-insensitive surface
     * preserves the original mixed-case literal under {@code upper(...)}) to its underlying text,
     * then upper-cases it when {@code caseInsensitive} so the set matches the upper-cased probe.
     */
    private static String setTerm(Expr item, boolean caseInsensitive)
    {
        Expr inner = isUpperCall(item) ? ((Expr.Call) item).args().get(0) : item;
        return fold(literalText(inner), caseInsensitive);
    }


    private static String fold(String s, boolean caseInsensitive)
    {
        return caseInsensitive ? s.toUpperCase(Locale.ROOT) : s;
    }

    // ---------------------------------------------------------------------
    // Static helpers
    // ---------------------------------------------------------------------


    private static @Nullable String tagOf(Expr e)
    {
        if (e instanceof Expr.Call c && c.kwargs().isEmpty() && c.args().size() == 1
                && TAGS.contains(c.name()))
        {
            return c.name();
        }
        return null;
    }


    private static Expr untag(Expr e)
    {
        return tagOf(e) != null ? ((Expr.Call) e).args().get(0) : e;
    }


    private static String family(@Nullable String lt, @Nullable String rt)
    {
        String w = lt != null ? lt : rt;
        if ("date".equals(w))
        {
            return "date";
        }
        if ("date_part".equals(w))
        {
            return "date_part";
        }
        if ("time_part".equals(w))
        {
            return "time_part";
        }
        return "plain"; // null / num
    }


    private static int dateDirection(Expr.BinOp op)
    {
        return switch (op)
        {
        case GT, GE -> 1;
        case LT, LE -> -1;
        default -> 0; // EQ, NEQ
        };
    }


    private static boolean dateOrEqual(Expr.BinOp op)
    {
        return op == Expr.BinOp.EQ || op == Expr.BinOp.NEQ || op == Expr.BinOp.GE
                || op == Expr.BinOp.LE;
    }


    private static int numDirection(Expr.BinOp op)
    {
        return (op == Expr.BinOp.GT || op == Expr.BinOp.GE) ? 1 : -1;
    }


    private static Pattern compilePattern(Expr e)
    {
        if (e instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.REGEX)
        {
            return Pattern.compile((String) lit.value());
        }
        throw new RuleDefinitionException(
                "the right-hand side of =~ / !~ must be a /regex/ literal");
    }


    private static Object literalObject(Expr.Lit lit)
    {
        return switch (lit.kind())
        {
        case STRING, REGEX -> lit.value();
        case NUMBER -> lit.value(); // Double
        case BOOL -> lit.value();
        case LIST -> throw unsupported("a list literal is not a scalar operand");
        };
    }


    private static String literalText(Expr e)
    {
        if (e instanceof Expr.Lit lit)
        {
            return switch (lit.kind())
            {
            case STRING, REGEX -> (String) lit.value();
            case NUMBER -> numberText((Double) lit.value());
            case BOOL -> lit.value().toString();
            case LIST -> throw unsupported("list literal has no scalar text");
            };
        }
        if (e instanceof Expr.Ref ref)
        {
            return ref.name();
        }
        throw unsupported("expected a literal or reference operand");
    }


    private static String numberText(Double d)
    {
        return canonicalNumberText(d);
    }


    /**
     * Canonical {@code Number} → {@code String} rendering — the single source of truth used
     * wherever a numeric value is rendered where a {@code String} is expected (a numeric-literal
     * operand in a string position, the {@code numberText} literal path, the D5 substring needle).
     * An integral finite value drops its trailing {@code .0} ({@code 100.0 → "100"},
     * {@code -2.0 → "-2"}); a fractional value renders via {@link Double#toString(double)}
     * ({@code 3.5 → "3.5"}); infinities render as {@code "Infinity"}/{@code "-Infinity"}. This is
     * intentionally NOT {@code DataValueDouble.getValueAsString()} — that cleans to 12 significant
     * digits and would change the rendering of high-precision fractional values, whereas this
     * preserves the exact legacy {@code numberText} output for every finite value.
     */
    public static String canonicalNumberText(Number n)
    {
        double d = n.doubleValue();
        if (!Double.isInfinite(d) && Double.compare(d, Math.rint(d)) == 0)
        {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }


    private static BitSet allSet(int rowCount)
    {
        BitSet bs = new BitSet(rowCount);
        if (rowCount > 0)
        {
            bs.set(0, rowCount);
        }
        return bs;
    }


    private static ExpressionException unsupported(String detail)
    {
        return new ExpressionException(
                "Expression construct not supported by the native evaluator: " + detail);
    }

}
