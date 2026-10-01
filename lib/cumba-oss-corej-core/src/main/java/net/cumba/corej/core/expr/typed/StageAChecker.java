package net.cumba.corej.core.expr.typed;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.ArgumentBinder;
import net.cumba.corej.core.expr.eval.BroadcastFold;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.expr.eval.FunctionKind;
import net.cumba.corej.core.expr.eval.FunctionRegistry;
import net.cumba.corej.core.expr.eval.MetadataAttribute;
import net.cumba.corej.core.expr.eval.MetadataLevel;
import net.cumba.corej.core.expr.eval.MetadataNormalizer.Normalization;
import net.cumba.corej.core.expr.eval.Parameter;
import net.cumba.corej.core.expr.typed.ExprType.ListOf;
import net.cumba.corej.core.expr.typed.ExprType.Primitive;
import net.cumba.corej.core.expr.typed.ExprType.Unknown;
import net.cumba.corej.core.model.CompiledBinding;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.OutputVariableToken;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.report.Severity;
import org.jspecify.annotations.Nullable;

/**
 * The stage-A checker of the typed-expression specification
 * ({@code .claude/docs/specs/SPEC-typed-expression-engine.md} §2): runs <b>once, at load, knowing
 * the expression only</b>, builds the {@link TypedExpr typed AST} — every node carrying
 * {@code (type, granularity, cursor)} — and checks what is decidable without a dataset: arity,
 * statically-known parameter types, the level algebra (§1.3 join / §1.4 raising, with the excluded
 * {@code group × cursor} cell, D67), list-literal homogeneity, name-position rules, binding order,
 * and {@code (attribute, metadata-level)} legality. Column types, absent columns and everything
 * else dataset-dependent are stage B's (D10) and are deliberately <b>not</b> judged here — a
 * dereferenced column types as {@link Unknown#UNKNOWN}, and unknown conflicts with nothing.
 *
 * <p>
 * <b>Phase 2 contract: no evaluation change.</b> Findings of an {@link StageAErrorKind#armed()}
 * kind append to the rule's {@code loadError} (the existing park path — the rule reports
 * {@code ERROR} once); every armed kind is measured at zero newly parked rules over the shipped
 * corpora, so production verdicts are untouched. Observe-only kinds are recorded on the report and
 * logged, never parked.
 * </p>
 *
 * <p>
 * The level classification deliberately mirrors {@code DomainScan}'s (the load-time inference the
 * runner dispatches on), refined onto the §1.3 product: {@code DomainScan}'s row cursor is the
 * {@code dataset < record} granularity axis, its variable cursor is {@link Cursor#PRESENT}, and
 * grouped operations — which the runtime still materialises per row ({@code PER_ROW_WHEN_GROUPED},
 * D91b's "group is invisible today") — are typed at their true {@code group(K)} granularity here,
 * because the typed tree is a report in phase 2, not a routing input.
 * </p>
 */
public final class StageAChecker
{

    private static final System.Logger LOGGER = System.getLogger(StageAChecker.class.getName());

    /**
     * Measurement hook: when set, every checked rule's report is offered to the observer. Used by
     * the corpus measurement runs that gate arming (see {@link StageAErrorKind}); never set in
     * production.
     */
    private static final AtomicReference<@Nullable BiConsumer<Rule, StageAReport>> OBSERVER = new AtomicReference<>();

    /**
     * Names whose call form anchors on the variable cursor.
     *
     * <p>
     * ⚠ <b>Corrected 2026-09-21.</b> This said <i>"unless given an explicit name"</i>, which is
     * true of {@code max_value_length} alone. The other two —
     * {@code library_variable_code_pair_matches} and {@code define_variable_decode_matches} —
     * <b>reject</b> an explicit name outright ({@code ExprCompiler}: <i>"expects varname() or no
     * argument"</i>), so for them the cursor anchoring is unconditional and they are unusable in a
     * {@code plans/PLAN-expansion-over-all-variables.md} expansion.
     * </p>
     *
     * <p>
     * ⚑ The same sentence is mirrored on {@code DomainScan.VARNAME_ANCHORED_CALLS}; both were
     * corrected together, because a shared census with one stale half is how the two cascades
     * drift.
     * </p>
     */
    private static final Set<String> VARNAME_ANCHORED_CALLS = Set.of("max_value_length",
            "library_variable_code_pair_matches", "define_variable_decode_matches");

    /** The date-family predicates of SPEC §5.3 (D27), each taking two same-base operands. */
    private static final Set<String> DATE_PREDICATES = Set.of("date_contains", "date_overlaps");

    /** The time-family predicates of SPEC §5.3 (D27). */
    private static final Set<String> TIME_PREDICATES = Set.of("time_contains", "time_overlaps");

    /** The temporal conversions, part accessors and hull bounds {@link #temporalCall} types. */
    private static final Set<String> TEMPORAL_CALLS = Set.of("date", "time", "date_part",
            "time_part", "earliest_possible", "latest_possible");

    /** The {@code by=[asc(COL), desc(COL)]} sort-key descriptor calls of is_sorted_by. */
    private static final Set<String> ORDERING_DESCRIPTORS = Set.of("asc", "desc");

    /** Bare-operand builtin names that read the (record × cursor) cell. */
    private static final Set<String> CELL_BUILTINS = Set.of("variable_value",
            "variable_value_length");

    /** Builtin operand names whose value is numeric. */
    private static final Set<String> NUMERIC_BUILTINS = Set.of("variable_length", "variable_size",
            "variable_max_size", "variable_value_length", "record_count",
            "library_variable_ordinal", "library_variable_length", "define_variable_ordinal",
            "define_variable_length", "define_vlm_length");

    /** The recovery level used to continue after the excluded {@code group × cursor} cell. */
    private static final Level EXCLUDED_CELL_RECOVERY = Level.VARIABLE_VALUE;

    private final Rule rule;

    /**
     * Phase 5: the bind-time column-level refinement hook (D39a — an absent column is a
     * dataset-level constant). {@link ColumnLevelResolver#STAGE_A} at load, where no dataset is
     * known.
     */
    private final ColumnLevelResolver columnLevels;

    private final Map<String, Level> bindingLevels;

    /**
     * The statically-known result type of each {@code $}-binding — the type its compiled expression
     * walks to with this very checker ({@code min_date}/{@code max_date} type {@code date} through
     * {@code ElementTable}, a column read stays a column reference, …); a binding that does not
     * walk stays {@link Unknown#UNKNOWN}.
     */
    private final Map<String, ExprType> bindingTypes;

    private final List<StageAFinding> findings = new ArrayList<>();

    /**
     * The root being walked, for the locating prefix of a {@link StageAErrorKind#PARAMETER_TYPE}
     * finding (review r1 tests M2: the finding is the rule's user-visible ERROR message, so it
     * names the Check level, the Precondition or the binding it was found in):
     * {@code Check (ERROR)}, {@code Precondition}, {@code binding $x}.
     */
    private String where = "the expression";

    /**
     * Test seam (review r1 seams M1): the effective id of a rule on which {@link #check} throws
     * inside its try — the only way to reach the {@link StageAErrorKind#CHECKER_FAILURE} path from
     * a loadable rule, which the loader's re-run of the seams depends on. Never set in production.
     */
    private static final AtomicReference<@Nullable String> FAIL_ON = new AtomicReference<>();

    /** Every reference name seen while walking, for the match-dataset checks. */
    private final List<String> referencedNames = new ArrayList<>();

    private StageAChecker(Rule rule)
    {
        this(rule, ColumnLevelResolver.STAGE_A);
    }


    /**
     * ⚠ Constructs without walking anything: {@link #scanBindings} is the first step of every entry
     * point, INSIDE its try ({@code PLAN-stage-a-parameter-type-arming} C4 — a binding walk that
     * threw from the constructor escaped {@link #check}'s "never throws" contract).
     */
    private StageAChecker(Rule rule, ColumnLevelResolver columnLevels)
    {
        this.rule = rule;
        this.columnLevels = columnLevels;
        this.bindingLevels = new HashMap<>();
        this.bindingTypes = new HashMap<>();
    }


    /**
     * Sets (or clears) the measurement observer. Test / measurement use only.
     *
     * @return the observer that was installed before, so a caller restores it in its
     *         {@code finally} instead of clearing to {@code null} — a reset blinds every later
     *         observer in the same JVM (review r1 tests 5: a corpus census test did exactly that to
     *         the plan's probe)
     */
    public static @Nullable BiConsumer<Rule, StageAReport> setObserver(
            @Nullable BiConsumer<Rule, StageAReport> observer)
    {
        return OBSERVER.getAndSet(observer);
    }


    /**
     * Test seam: makes {@link #check} fail (a {@link StageAErrorKind#CHECKER_FAILURE} finding) on
     * the rule whose effective id is {@code ruleId}; {@code null} clears it.
     *
     * @return the previous setting, to restore in a {@code finally}
     */
    public static @Nullable String setCheckerFailureInjection(@Nullable String ruleId)
    {
        return FAIL_ON.getAndSet(ruleId);
    }


    /**
     * Checks one rule's raised, canonicalised level expressions and applies the outcome: armed
     * findings append to the rule's {@code loadError} (spec §9 — load error, rule parked,
     * {@code ERROR} once), observe-only findings are logged. Never throws: a checker failure is
     * itself a finding ({@link StageAErrorKind#CHECKER_FAILURE}) and never parks the rule.
     *
     * @param rule
     *            the rule under load
     * @param levels
     *            the per-level expressions, as {@code installNativeExpr} raised them
     * @return the report
     */
    public static StageAReport runAndApply(Rule rule, SequencedMap<Severity, Expr> levels)
    {
        return runAndApply(rule, levels, null);
    }


    /**
     * {@link #runAndApply(Rule, SequencedMap)} with the rule's raised, canonicalised
     * {@code Precondition} as one more root ({@code PLAN-stage-a-parameter-type-arming} Q2 / C2:
     * every Precondition is checked, the engine-injected ones included — an engine-written root is
     * no less a root, and UNIFORMITY forbids a location-dependent answer).
     *
     * @param rule
     *            the rule under load
     * @param levels
     *            the per-level expressions, as {@code installNativeExpr} raised them
     * @param precondition
     *            the raised, canonicalised Precondition, or {@code null} when the rule has none
     * @return the report
     */
    public static StageAReport runAndApply(Rule rule, SequencedMap<Severity, Expr> levels,
            @Nullable Expr precondition)
    {
        StageAReport report = check(rule, levels, precondition);
        apply(rule, report);
        BiConsumer<Rule, StageAReport> observer = OBSERVER.get();
        if (observer != null)
        {
            observer.accept(rule, report);
        }
        return report;
    }


    /**
     * The <b>precondition-only</b> entry for a Precondition installed on an already-loaded rule
     * ({@code RulePackageLoader.installEngineInternalPrecondition}): the bindings are scanned for
     * their types only — their own findings and the binding-order checks were filed when the rule
     * loaded and are not re-reported — then the Precondition is walked as a root exactly as
     * {@link #check(Rule, SequencedMap, Expr)} walks one, armed findings park the rule, and the
     * measurement observer is <b>not</b> fired a second time for the rule. Never throws (a checker
     * failure is the {@link StageAErrorKind#CHECKER_FAILURE} finding).
     *
     * <p>
     * The rule-level {@code Match_Datasets} checks run over this one root plus the bindings' dotted
     * references, as at load. Only an observe-only kind can be re-reported by that: an armed one
     * would have parked the rule at load, and the caller skips a parked rule.
     * </p>
     *
     * @param rule
     *            the loaded rule, not parked
     * @param precondition
     *            the raised, canonicalised Precondition
     * @return the report (levels empty)
     */
    public static StageAReport runAndApplyPrecondition(Rule rule, Expr precondition)
    {
        StageAChecker checker = new StageAChecker(rule);
        TypedExpr typed = null;
        try
        {
            checker.scanBindings(rule.bindingOrder());
            checker.findings.clear();
            typed = checker.walkPrecondition(precondition);
            checker.listFunctionReads(precondition, "the Precondition");
            checker.checkMatchDatasets(List.of(precondition));
        }
        catch (RuntimeException ex)
        {
            checker.findings.add(new StageAFinding(StageAErrorKind.CHECKER_FAILURE,
                    "stage-A checker failed on rule " + rule.getId() + ": " + ex));
        }
        StageAReport report = new StageAReport(new LinkedHashMap<>(), typed, checker.findings);
        apply(rule, report);
        return report;
    }


    /** Files the report's armed findings as the rule's load error and logs the observed ones. */
    private static void apply(Rule rule, StageAReport report)
    {
        List<StageAFinding> armed = report.armedFindings();
        if (!armed.isEmpty())
        {
            String joined = String.join("; ", armed.stream().map(StageAFinding::toString).toList());
            String error = "stage A: " + joined;
            rule.setLoadError(
                    rule.getLoadError() == null ? error : rule.getLoadError() + "; " + error);
        }
        for (StageAFinding failure : report.findings())
        {
            if (failure.kind() == StageAErrorKind.CHECKER_FAILURE)
            {
                // review r1 sem L2: a checker defect is never armed, but it is never silent either
                LOGGER.log(System.Logger.Level.WARNING, "stage A could not judge rule {0}: {1}",
                        rule.effectiveId(), failure.message());
            }
        }
        if (!report.observedFindings().isEmpty() && LOGGER.isLoggable(System.Logger.Level.DEBUG))
        {
            LOGGER.log(System.Logger.Level.DEBUG, "stage A observations for {0}: {1}", rule.getId(),
                    report.observedFindings());
        }
    }


    /**
     * Checks one rule's raised level expressions without applying anything to the rule.
     */
    public static StageAReport check(Rule rule, SequencedMap<Severity, Expr> levels)
    {
        return check(rule, levels, null);
    }


    /**
     * Checks one rule's raised level expressions and its raised Precondition (when it carries one)
     * without applying anything to the rule. The Precondition is held to the same boolean-root rule
     * as a Check level and takes part in the list-function and {@code Match_Datasets} checks.
     */
    public static StageAReport check(Rule rule, SequencedMap<Severity, Expr> levels,
            @Nullable Expr precondition)
    {
        StageAChecker checker = new StageAChecker(rule);
        SequencedMap<Severity, TypedExpr> typed = new LinkedHashMap<>();
        TypedExpr typedPrecondition = null;
        try
        {
            String injected = FAIL_ON.get();
            if (injected != null && injected.equals(rule.effectiveId()))
            {
                throw new IllegalStateException("injected checker failure (test seam)");
            }
            checker.scanBindings(rule.bindingOrder());
            List<Root> roots = new ArrayList<>();
            for (Map.Entry<Severity, Expr> level : levels.entrySet())
            {
                checker.where = "Check (" + level.getKey() + ")";
                typed.put(level.getKey(), checker.walkRoot(level.getValue(), "the Check root"));
                roots.add(new Root(level.getValue(), "the Check (" + level.getKey() + ")"));
            }
            if (precondition != null)
            {
                typedPrecondition = checker.walkPrecondition(precondition);
                roots.add(new Root(precondition, "the Precondition"));
            }
            checker.where = "the rule";
            checker.checkBindingOrder();
            checker.checkListFunctionReads(roots);
            checker.checkMatchDatasets(roots.stream().map(Root::expr).toList());
        }
        catch (RuntimeException ex)
        {
            // A checker defect must never park a rule — that would be an evaluation change
            // caused by the instrument (phase 2's headline constraint).
            checker.findings.add(new StageAFinding(StageAErrorKind.CHECKER_FAILURE,
                    "stage-A checker failed on rule " + rule.getId() + ": " + ex));
        }
        return new StageAReport(typed, typedPrecondition, checker.findings);
    }


    /** Walks one root and holds it to the boolean-root rule. */
    private TypedExpr walkRoot(Expr expr, String position)
    {
        TypedExpr root = walk(expr);
        if (root.type().dereference() != Unknown.UNKNOWN
                && root.type().dereference() != Primitive.BOOLEAN)
        {
            find(StageAErrorKind.PARAMETER_TYPE,
                    position + " must be boolean, not " + root.type().describe());
        }
        return root;
    }


    private TypedExpr walkPrecondition(Expr precondition)
    {
        where = "Precondition";
        return walkRoot(precondition, "the Precondition root");
    }

    /** A root of the rule — a Check level or the Precondition — with its label for messages. */
    private record Root(Expr expr, String label)
    {
    }

    private void find(StageAErrorKind kind, String message)
    {
        // review r1 tests M2: a PARAMETER_TYPE finding is the rule's ERROR message, so it names
        // the root it was found in — Check (<level>), Precondition, or binding $x
        findings.add(new StageAFinding(kind,
                kind == StageAErrorKind.PARAMETER_TYPE ? where + ": " + message : message));
    }


    /**
     * The operand text of a finding, abbreviated: {@code  (len(AEOUT) == "5")} — the second half of
     * the locating message (review r1 tests M2).
     */
    private static String at(Expr node)
    {
        String text;
        try
        {
            text = net.cumba.corej.core.expr.ExpressionPrinter.print(node);
        }
        catch (RuntimeException ex)
        {
            return "";
        }
        return " (" + (text.length() > 120 ? text.substring(0, 117) + "..." : text) + ")";
    }


    /**
     * A caught throwable's message, never {@code null}. Every {@link ExpressionException} and
     * {@link ExcludedLevelCellException} constructor requires one, so the fallback is unreachable
     * today; it exists so a future message-less throwable becomes a named finding rather than a
     * finding whose whole diagnosis is the word {@code null}. (NullAway: {@code getMessage()} is
     * {@code @Nullable} on {@code Throwable}, and a finding's message is not.)
     *
     * @param aThrown
     *            the caught throwable
     * @return its message, or its {@code toString()} when it carries none
     */
    private static String messageOf(Throwable aThrown)
    {
        String message = aThrown.getMessage();
        return message != null ? message : aThrown.toString();
    }

    // ------------------------------------------------------------------
    // The walker
    // ------------------------------------------------------------------


    private TypedExpr walk(Expr e)
    {
        return switch (e)
        {
        case Expr.And a -> logical(e, a.parts(), "and");
        case Expr.Or o -> logical(e, o.parts(), "or");
        case Expr.Not n -> not(n);
        case Expr.Binary b -> binary(b);
        case Expr.Lit lit -> literal(lit);
        case Expr.Ref r -> ref(r);
        case Expr.Call c -> call(c);
        };
    }


    private TypedExpr logical(Expr node, List<Expr> parts, String op)
    {
        List<TypedExpr> children = new ArrayList<>(parts.size());
        Level level = Level.STUDY;
        for (Expr part : parts)
        {
            TypedExpr child = walk(part);
            requireBoolean(child, "an operand of '" + op + "'", node);
            level = join(level, child.level());
            children.add(child);
        }
        return new TypedExpr(node, Primitive.BOOLEAN, level, children);
    }


    private TypedExpr not(Expr.Not n)
    {
        TypedExpr inner = walk(n.inner());
        requireBoolean(inner, "the operand of 'not'", n);
        return new TypedExpr(n, Primitive.BOOLEAN, inner.level(), List.of(inner));
    }


    private void requireBoolean(TypedExpr operand, String position, Expr context)
    {
        ExprType t = operand.type().dereference();
        if (t != Unknown.UNKNOWN && t != Primitive.BOOLEAN)
        {
            find(StageAErrorKind.PARAMETER_TYPE,
                    position + " must be boolean, not " + t.describe() + at(context));
        }
    }


    private TypedExpr binary(Expr.Binary b)
    {
        TypedExpr left = walk(b.left());
        TypedExpr right = walk(b.right());
        Level level = join(left.level(), right.level());
        List<TypedExpr> children = List.of(left, right);
        switch (b.op())
        {
        case EQ, NEQ, LT, GT, LE, GE -> comparison(b, left, right);
        case MATCH, NMATCH -> regexMatch(b, left, right);
        case IN, NOT_IN -> membership(b, left, right);
        case ADD, SUB, MUL, DIV ->
        {
            requireNumber(left, "the left operand of arithmetic", b);
            requireNumber(right, "the right operand of arithmetic", b);
            return new TypedExpr(b, Primitive.NUMBER, level, children);
        }
        }
        return new TypedExpr(b, Primitive.BOOLEAN, level, children);
    }


    private void comparison(Expr.Binary b, TypedExpr left, TypedExpr right)
    {
        listBindingComparison(b, b.left());
        listBindingComparison(b, b.right());
        ExprType lt = left.type().dereference();
        ExprType rt = right.type().dereference();
        if (mixedTemporalString(lt, rt) || mixedTemporalString(rt, lt))
        {
            // §5.2: the operator applies only when BOTH sides are date (or both time); a mixed
            // temporal/string pair is the type error that forces the phase-3c corpus rewrite
            // (D71b) — never a reinterpretation of the untyped side (the plan's §1(4) defect).
            // Phase 3b types the temporal producers, so this is no longer vacuous; the kind is
            // OBSERVE-ONLY (its javadoc records the one rulespec that keeps it so).
            find(StageAErrorKind.MIXED_DATE_STRING_COMPARISON,
                    "a comparison must not mix date/time and string operands (" + lt.describe()
                            + " " + b.op() + " " + rt.describe()
                            + ") — convert the string side with date(...) / time(...)");
        }
        else if (!ExprType.compatible(lt, rt))
        {
            find(StageAErrorKind.PARAMETER_TYPE, "comparison operands disagree: " + lt.describe()
                    + " " + b.op() + " " + rt.describe() + at(b));
        }
    }


    /**
     * Review round 2, LOW-3: a scalar comparison ({@code == != < > <= >=}) against a
     * <b>compiled</b> binding whose static type is a list — a list-literal binding or a list-valued
     * function's — is the load error of the same comparison written against the inline list (which
     * never compiles). {@code X != $l} otherwise ran and flagged every row, {@code X == $l} none. ⚠
     * An <b>operation</b> binding's list is deliberately NOT judged here: the shipped corpus
     * compares against operation lists and the executor answers them (probe F1); changing that
     * would move shipped verdicts, which wave 0 may not do.
     */
    private void listBindingComparison(Expr.Binary b, Expr operand)
    {
        if (!(operand instanceof Expr.Ref ref) || rule.compiledBinding(ref.name()) == null)
        {
            return;
        }
        ExprType type = bindingTypes.get(ref.name());
        if (type != null && type.dereference() instanceof ListOf)
        {
            find(StageAErrorKind.COMPARISON_WITH_LIST_BINDING,
                    "the comparison " + b.op() + " reads the list-valued binding " + ref.name()
                            + " as a scalar — test membership with `in` / `not in`, or compare"
                            + " one element");
        }
    }


    /** §5.2's mixed pair, one direction: a temporal side against a statically-known string. */
    private static boolean mixedTemporalString(ExprType a, ExprType b)
    {
        return (a == Primitive.DATE || a == Primitive.TIME) && b == Primitive.STRING;
    }


    private void regexMatch(Expr.Binary b, TypedExpr left, TypedExpr right)
    {
        requireStringish(left, "the left operand of " + b.op(), b);
        // §1.1: regex is literal-only, never computed — the right-hand side must be a /…/ or
        // string literal.
        if (!(right.node() instanceof Expr.Lit lit)
                || (lit.kind() != Expr.LitKind.REGEX && lit.kind() != Expr.LitKind.STRING))
        {
            find(StageAErrorKind.PARAMETER_TYPE,
                    "the right-hand side of " + b.op() + " must be a regex literal" + at(b));
        }
    }


    private void membership(Expr.Binary b, TypedExpr left, TypedExpr right)
    {
        ExprType lt = left.type().dereference();
        ExprType rt = right.type().dereference();
        if (rt != Unknown.UNKNOWN && !rt.isCollection())
        {
            find(StageAErrorKind.PARAMETER_TYPE,
                    "the right operand of 'in' must be a list or set, not " + rt.describe()
                            + at(b));
            return;
        }
        // A list element is read in VALUE position (§1.2): a list<column-reference> — tuple(A, B),
        // a column-reference list binding — holds cells, so its element type dereferences to
        // unknown exactly as a bare column does (PLAN-stage-a-parameter-type-arming phase 3:
        // `"x" in $t` over `$t: tuple(A, B)` used to disagree string-vs-column-reference).
        if (lt.isCollection())
        {
            // D81d: list-LHS membership is a COLLECTION shape (each left element probes the
            // right set — the corpus has 10 rules of it, e.g. [A, B] in $set), legal but never
            // absorbed by the scalar `==`-disjunction lowering. Element-wise compatibility is
            // the check.
            ExprType leftElement = ExprType.elementOf(lt).dereference();
            if (!ExprType.compatible(leftElement, ExprType.elementOf(rt).dereference()))
            {
                find(StageAErrorKind.PARAMETER_TYPE, "membership element type "
                        + leftElement.describe() + " disagrees with " + rt.describe() + at(b));
            }
            return;
        }
        ExprType element = ExprType.elementOf(rt).dereference();
        // ⭐ Phase 1a of PLAN-membership-as-equality: a temporal probe against string members is
        // the SAME defect `comparison` names, so it gets the same actionable message instead of
        // the generic disagreement. Q2 (owner, 2026-09-21): membership inherits whatever `==`
        // does, and `==` makes this a type error rather than reinterpreting the untyped side ⇒
        // the authoring is `date(--DTC) in [date("2020-01-01")]`.
        // ⚠ The REJECTION itself is not new — `compatible(DATE, STRING)` was already false, so
        // this shape already failed Stage A. What was missing is the instruction on how to fix it.
        if (mixedTemporalString(lt, element) || mixedTemporalString(element, lt))
        {
            find(StageAErrorKind.MIXED_DATE_STRING_COMPARISON,
                    "membership must not mix date/time and string operands (" + lt.describe()
                            + " in " + rt.describe()
                            + ") — convert the members with date(...) / time(...)");
            return;
        }
        if (!ExprType.compatible(lt, element))
        {
            find(StageAErrorKind.PARAMETER_TYPE, "membership element type " + lt.describe()
                    + " disagrees with " + rt.describe() + at(b));
        }
    }


    private void requireNumber(TypedExpr operand, String position, Expr context)
    {
        ExprType t = operand.type().dereference();
        if (t != Unknown.UNKNOWN && t != Primitive.NUMBER)
        {
            find(StageAErrorKind.PARAMETER_TYPE,
                    position + " must be a number, not " + t.describe() + at(context));
        }
    }


    private void requireStringish(TypedExpr operand, String position, Expr context)
    {
        ExprType t = operand.type().dereference();
        if (t != Unknown.UNKNOWN && t != Primitive.STRING)
        {
            find(StageAErrorKind.PARAMETER_TYPE,
                    position + " must be a string, not " + t.describe() + at(context));
        }
    }


    private TypedExpr literal(Expr.Lit lit)
    {
        return switch (lit.kind())
        {
        case STRING -> new TypedExpr(lit, Primitive.STRING, Level.STUDY, List.of());
        case NUMBER -> new TypedExpr(lit, Primitive.NUMBER, Level.STUDY, List.of());
        case BOOL -> new TypedExpr(lit, Primitive.BOOLEAN, Level.STUDY, List.of());
        case REGEX -> new TypedExpr(lit, Primitive.REGEX, Level.STUDY, List.of());
        case LIST -> listLiteral(lit);
        };
    }


    @SuppressWarnings("unchecked")
    private TypedExpr listLiteral(Expr.Lit lit)
    {
        List<Expr> items = (List<Expr>) lit.value();
        List<TypedExpr> children = new ArrayList<>(items.size());
        Level level = Level.STUDY;
        ExprType element = Unknown.UNKNOWN;
        boolean heterogeneous = false;
        for (Expr item : items)
        {
            TypedExpr child = walk(item);
            level = join(level, child.level());
            // §1.5: a literal must be homogeneous. Element-wise the same value/name duality
            // applies (§1.2): a column reference dereferences to unknown, so [A, B] and
            // ["A", "B"] are both homogeneous and only known-vs-known conflicts ([1, "a"]) fire.
            ExprType t = child.type().dereference();
            if (isSpliceShape(child.node(), child.type()) && t instanceof ListOf spliced)
            {
                // review r1 sem M1: a $ binding holding a LIST OF NAMES is spliced element by
                // element (GroupSplice), so beside a name it is not a mixed list — it
                // contributes its ELEMENT type to the literal's homogeneity
                t = spliced.element().dereference();
            }
            if (t != Unknown.UNKNOWN)
            {
                if (element == Unknown.UNKNOWN)
                {
                    element = t;
                }
                else if (!ExprType.compatible(element, t))
                {
                    heterogeneous = true;
                }
            }
            children.add(child);
        }
        if (heterogeneous)
        {
            find(StageAErrorKind.HETEROGENEOUS_LIST,
                    "a list literal must be homogeneous (spec §1.5); found mixed element types");
            element = Unknown.UNKNOWN;
        }
        return new TypedExpr(lit, new ListOf(element), level, children);
    }


    private TypedExpr ref(Expr.Ref r)
    {
        referencedNames.add(r.name());
        return switch (r.kind())
        {
        case COLUMN, WILDCARD_COLUMN, DOTTED_REF -> new TypedExpr(r, Primitive.COLUMN_REFERENCE,
                columnRefLevel(r), List.of());
        // The join-match flag (spec §3.3, D88): statically BOOLEAN — the one column whose type is
        // a stage-A fact, because the engine computes it — at level record.
        case MATCHED_FLAG -> new TypedExpr(r, Primitive.BOOLEAN, Level.RECORD, List.of());
        case OPERATION_REF -> new TypedExpr(r, bindingTypes.getOrDefault(r.name(), Unknown.UNKNOWN),
                bindingLevels.getOrDefault(r.name(), Level.DATASET), List.of());
        case BUILTIN -> builtinRef(r);
        };
    }


    /**
     * The level of a bare column / wildcard / dotted reference: the {@link ColumnLevelResolver
     * bind-time refinement} when one is installed and answers, else the stage-A default
     * {@link Level#RECORD}. A resolver failure falls back to the default — refinement is an
     * instrument input in phase 5 and must never change what the checker reports.
     */
    private Level columnRefLevel(Expr.Ref r)
    {
        try
        {
            Level refined = columnLevels.resolve(r);
            return refined != null ? refined : Level.RECORD;
        }
        catch (RuntimeException ex)
        {
            LOGGER.log(System.Logger.Level.TRACE, "column-level resolver failed for {0}: {1}",
                    r.name(), ex.toString());
            return Level.RECORD;
        }
    }


    /**
     * Phase 5 entry point: the typed tree of one expression of {@code rule}, with the bind-time
     * {@code columnLevels} refinement applied (D39a — an absent column is a dataset-level
     * constant). (Phase 6's D106d refinement of a {@code $}-binding's level from the run's dataset
     * inventory — a cross-dataset <em>operation</em> over an absent foreign domain degenerating to
     * a scalar — went with the operation surface in runbook W8: it had no subject since W6, and an
     * absent {@code domain=} dataset SKIPs the rule before any level is read.)
     *
     * @param rule
     *            the (specialised) rule the expression belongs to
     * @param expr
     *            the expression to type
     * @param columnLevels
     *            the bind-time column-level refinement (D39a)
     * @return the typed root, or {@code null} when the walk failed
     */
    public static @Nullable TypedExpr deriveTyped(Rule rule, Expr expr,
            ColumnLevelResolver columnLevels)
    {
        try
        {
            StageAChecker checker = new StageAChecker(rule, columnLevels);
            checker.scanBindings(rule.bindingOrder());
            return checker.walk(expr);
        }
        catch (RuntimeException ex)
        {
            LOGGER.log(System.Logger.Level.TRACE, "level derivation failed for {0}: {1}",
                    rule.getId(), ex.toString());
            return null;
        }
    }


    private TypedExpr builtinRef(Expr.Ref r)
    {
        String name = r.name();
        Level level;
        if ("variable_name".equals(name))
        {
            level = Level.VARIABLE_METADATA;
        }
        else if (CELL_BUILTINS.contains(name) || name.startsWith("define_vlm_"))
        {
            level = Level.VARIABLE_VALUE;
        }
        else if (name.startsWith("variable_") || name.startsWith("library_variable_")
                || name.startsWith("define_variable_"))
        {
            level = Level.VARIABLE_METADATA;
        }
        else
        {
            level = Level.DATASET;
        }
        ExprType type;
        if ("variable_name".equals(name))
        {
            type = Primitive.COLUMN_REFERENCE;
        }
        else if ("variable_value".equals(name))
        {
            type = Unknown.UNKNOWN;
        }
        else if (NUMERIC_BUILTINS.contains(name))
        {
            type = Primitive.NUMBER;
        }
        else if (name.endsWith("_values") || name.endsWith("_codes"))
        {
            type = new ListOf(Primitive.STRING);
        }
        else if (name.endsWith("_extensible") || name.contains("_has_") || name.endsWith("_matches")
                || name.endsWith("_conforms"))
        {
            type = Primitive.BOOLEAN;
        }
        else
        {
            type = Primitive.STRING;
        }
        return new TypedExpr(r, type, level, List.of());
    }

    // ------------------------------------------------------------------
    // Calls — classification mirrors DomainScan.call
    // ------------------------------------------------------------------


    private TypedExpr call(Expr.Call c)
    {
        String name = c.name();
        List<TypedExpr> children = walkArguments(c);
        if ("value".equals(name) && c.args().isEmpty() && c.kwargs().isEmpty())
        {
            return new TypedExpr(c, Unknown.UNKNOWN, Level.VARIABLE_VALUE, children);
        }
        if ("varname".equals(name) && c.args().isEmpty() && c.kwargs().isEmpty())
        {
            return new TypedExpr(c, Primitive.COLUMN_REFERENCE, Level.VARIABLE_METADATA, children);
        }
        if ("colref".equals(name))
        {
            return colref(c, children);
        }
        TypedExpr temporal = temporalCall(c, children);
        if (temporal != null)
        {
            return temporal;
        }
        if ("num".equals(name) && c.args().size() == 1 && c.kwargs().isEmpty())
        {
            // The one conversion that is already a value function (R10 / D91f: num() stays in
            // the value plan).
            return new TypedExpr(c, Primitive.NUMBER, children.get(0).level(), children);
        }
        if (BroadcastFold.isExistsCall(c) || BroadcastFold.isBroadcastColumnPredicate(c))
        {
            return new TypedExpr(c, Primitive.BOOLEAN, presenceLevel(c), children);
        }
        if (BroadcastFold.isWholeColumnVerdictCall(c))
        {
            // One dataset fact per column: the operands' record granularity is absorbed, the
            // cursor survives (a per-variable operand keeps the verdict per variable).
            Cursor cursor = joinChildren(children).cursor();
            return new TypedExpr(c, Primitive.BOOLEAN,
                    new Level(Granularity.Simple.DATASET, cursor), children);
        }
        if (BroadcastFold.isLibraryGateCall(c))
        {
            // The availability gates the engine writes into a Precondition (library_available(),
            // dictionary_available(<type>), available(<call>)): a dataset fact — and since the
            // Precondition is a stage-A root (PLAN-stage-a-parameter-type-arming Q2) their
            // arguments meet the descriptor's parameter types like any registered call's.
            FunctionDescriptor gate = FunctionRegistry.descriptor(name);
            if (gate != null)
            {
                checkParameterBinding(c, gate, children);
            }
            return new TypedExpr(c, Primitive.BOOLEAN, Level.DATASET, children);
        }
        MetadataAttribute attr = MetadataAttribute.fromFunction(name);
        if (attr != null)
        {
            return accessor(c, attr, children);
        }
        if (name.startsWith("vlm_"))
        {
            return new TypedExpr(c, Unknown.UNKNOWN, Level.VARIABLE_VALUE, children);
        }
        if (VARNAME_ANCHORED_CALLS.contains(name))
        {
            Level level = c.args().isEmpty() || isCurrentVariableName(c.args().get(0))
                    ? Level.VARIABLE_METADATA
                    : Level.DATASET;
            return new TypedExpr(c, ElementTable.resultType(name), level, children);
        }
        FunctionDescriptor descriptor = FunctionRegistry.descriptor(name);
        if (descriptor != null)
        {
            TypedExpr typed = registered(c, descriptor, children);
            if (net.cumba.corej.core.exec.FindVars.NAME.equals(name))
            {
                // PLAN-dynamic-column-functions §2.4: find_vars reads the dataset's column
                // inventory, so even a literal entry is at least DATASET level (a literal's own
                // level is STUDY); a computed entry keeps its operands' RECORD level.
                return new TypedExpr(c, typed.type(), join(typed.level(), Level.DATASET), children);
            }
            if (net.cumba.corej.core.exec.RecordCount.NAME.equals(name))
            {
                // Runbook W6 (PLAN-record-count-function D-W6-9): the retired inline
                // RECORD_COUNT operation's level, carried over — group(K) when grouped (the
                // excluded group x cursor cell, D67, is found exactly as before), DATASET
                // otherwise (`registered` above already folds the row axis for the name).
                return net.cumba.corej.core.exec.RecordCount.isGrouped(c)
                        ? new TypedExpr(c, typed.type(),
                                groupedLevel(groupKeys(c.kwargs().get("group"))), children)
                        : typed;
            }
            if (net.cumba.corej.core.exec.Distinct.NAME.equals(name))
            {
                // Runbook W7 (PLAN-distinct-function D-W7-12): the retired inline DISTINCT
                // operation's level, carried over — group(K) when grouped (the excluded group x
                // cursor cell, D67, is found exactly as before), DATASET otherwise.
                return new TypedExpr(c, typed.type(),
                        net.cumba.corej.core.exec.Distinct.isGrouped(c)
                                ? groupedLevel(groupKeys(c.kwargs().get("group")))
                                : Level.DATASET,
                        children);
            }
            if (net.cumba.corej.core.exec.GroupedAggregate.isFunction(name)
                    || (net.cumba.corej.core.exec.ReadValue.NAME.equals(name)
                            && c.kwargs().containsKey("group")))
            {
                // Runbook W5 (PLAN-grouped-aggregate-functions D-W5-7): a grouped aggregate
                // answers one value per primary row, keyed by its group= — the retired inline
                // operation's group(K) granularity, carried over (the excluded group x cursor
                // cell, D67, is found exactly as before). The descriptor binding above keeps the
                // arity / keyword findings.
                return new TypedExpr(c, typed.type(),
                        groupedLevel(groupKeys(c.kwargs().get("group"))), children);
            }
            if (c.kwargs().containsKey("domain"))
            {
                // DomainScan's `domain=` arm, mirrored (combined review of runbook W2–W8, XCUT
                // L3): an ungrouped registry call carrying `domain=` reads ANOTHER dataset —
                // read_value(TSVAL, domain=TS, filter=(…), mode="FIRST") — and answers one value
                // for the run; its column arguments are that dataset's, not reads of the primary.
                // After the grouped arm above, as in DomainScan, so a grouped foreign read keeps
                // its group(K) granularity.
                return new TypedExpr(c, typed.type(), Level.DATASET, children);
            }
            return typed;
        }
        return unregistered(c, children);
    }


    /**
     * Whether a call of {@code name} reaches the descriptor-driven parameter check —
     * {@link #registered}, or the library-gate arm, which binds its descriptor since the
     * Precondition became a root — i.e. whether stage A judges its arguments against the
     * descriptor's parameter types. Decided on a one-column-argument call, the shape every
     * short-circuit of {@link #call} is keyed on ({@code value()} / {@code varname()} short-circuit
     * only nullary, and neither declares a parameter). ⭐ The loader's seam pass keeps the R1
     * literal seam exactly for the column-reference-bearing registry-evaluated descriptors this
     * answers {@code false} for ({@code ExprCompiler.STAGE_A_SHORT_CIRCUITED_COLUMN_CALLS});
     * {@code StageAResidueDriftTest} re-derives that set from the providers and this predicate, so
     * the two cannot drift apart ({@code PLAN-stage-a-parameter-type-arming} §3.3, phase 4).
     *
     * @param name
     *            a registered function name
     * @return whether stage A binds the call's arguments to the descriptor's parameters
     */
    public static boolean judgesParameters(String name)
    {
        Expr.Call probe = new Expr.Call(name,
                List.of(new Expr.Ref("X", net.cumba.corej.core.expr.OperandKind.COLUMN)), Map.of());
        if ("colref".equals(name) || "num".equals(name) || TEMPORAL_CALLS.contains(name))
        {
            return false;
        }
        if (BroadcastFold.isExistsCall(probe) || BroadcastFold.isBroadcastColumnPredicate(probe)
                || BroadcastFold.isWholeColumnVerdictCall(probe))
        {
            return false;
        }
        // the library-gate arm binds its descriptor since the Precondition became a root
        return BroadcastFold.isLibraryGateCall(probe)
                || (MetadataAttribute.fromFunction(name) == null && !name.startsWith("vlm_")
                        && !VARNAME_ANCHORED_CALLS.contains(name));
    }


    /**
     * {@code colref(x)} — §1.2's dynamic dereference ({@code PLAN-dynamic-column-functions} §2.3):
     * a string names one column ({@code column-reference} at record level, as before); a list of
     * strings names one column per element ({@code list<unknown>} — its elements are the named
     * columns' cells, never names, so never {@code list<string>}). ⚠ Both result types live ONLY
     * here: a name-keyed {@code ElementTable} row cannot tell the scalar form from the list form. A
     * statically known argument of any other type is {@link StageAErrorKind#PARAMETER_TYPE} (owner
     * Q7; armed since {@code PLAN-stage-a-parameter-type-arming}, so the rule fails to load); at
     * run time a present non-string value names no column and is a computed missing.
     */
    private TypedExpr colref(Expr.Call c, List<TypedExpr> children)
    {
        if (c.args().size() != 1 || !c.kwargs().isEmpty())
        {
            find(StageAErrorKind.ARITY, "colref takes exactly one argument");
            return new TypedExpr(c, Primitive.COLUMN_REFERENCE, Level.RECORD, children);
        }
        ExprType arg = children.get(0).type();
        if (arg instanceof ListOf list)
        {
            if (!isNameType(list.element()))
            {
                find(StageAErrorKind.PARAMETER_TYPE, "colref takes a column name (a string) or a"
                        + " list of column names, not " + arg.describe() + at(c));
            }
            return new TypedExpr(c, new ListOf(Unknown.UNKNOWN), Level.RECORD, children);
        }
        if (!isNameType(arg))
        {
            find(StageAErrorKind.PARAMETER_TYPE, "colref takes a column name (a string) or a list"
                    + " of column names, not " + arg.describe() + at(c));
        }
        return new TypedExpr(c, Primitive.COLUMN_REFERENCE, Level.RECORD, children);
    }


    /**
     * Whether a statically known type can carry a column name: a string, or a column reference /
     * unknown whose value (read per row) is one.
     */
    private static boolean isNameType(ExprType t)
    {
        return t == Primitive.STRING || t == Primitive.COLUMN_REFERENCE || t == Unknown.UNKNOWN;
    }


    /**
     * The temporal value surface of SPEC §5 (phase 3b), typed for real: the conversions
     * {@code date(x)}/{@code time(x)} (§1.2 — {@code time} is Review 0's E1), the part accessors
     * {@code date_part}/{@code time_part} (D20 — ordinary functions taking a date; ⚠ still erased
     * mode tags in the compiler until the 3c rewrite, D98b(ii)), and the bounds
     * {@code earliest_possible}/{@code latest_possible} (D22 — overloaded on both temporal bases,
     * the result base following the argument). Returns {@code null} for any other name.
     *
     * <p>
     * Parameter findings here are {@link StageAErrorKind#PARAMETER_TYPE} (armed since
     * {@code PLAN-stage-a-parameter-type-arming}): a statically NUMBER argument to {@code date()}
     * is D55's {@code date(NUM)} shape — refused at load with the {@code date_from_sas_days} /
     * {@code date_from_sas_datetime} rewrite named in the message; a numeric COLUMN is stage B's
     * bind gate (a column types as unknown here).
     * </p>
     */
    private @Nullable TypedExpr temporalCall(Expr.Call c, List<TypedExpr> children)
    {
        String name = c.name();
        if (!TEMPORAL_CALLS.contains(name))
        {
            return null;
        }
        boolean conversion = "date".equals(name) || "time".equals(name);
        boolean part = "date_part".equals(name) || "time_part".equals(name);
        if (c.args().size() != 1 || !c.kwargs().isEmpty())
        {
            find(StageAErrorKind.ARITY, name + " takes exactly one argument");
            return new TypedExpr(c, Unknown.UNKNOWN, joinChildren(children), children);
        }
        TypedExpr arg = children.get(0);
        ExprType at = arg.type().dereference();
        ExprType result;
        if (conversion)
        {
            if (at == Primitive.NUMBER)
            {
                find(StageAErrorKind.PARAMETER_TYPE, name + "() over a number is the D55 shape "
                        + "(a SAS numeric is not an ISO-8601 text) — author date_from_sas_days"
                        + "(...) or date_from_sas_datetime(...) for a numeric date column" + at(c));
            }
            else if (at != Unknown.UNKNOWN && at != Primitive.STRING && at != resultOf(name))
            {
                // The conversion is idempotent on its own base; any other known type is not a
                // temporal text.
                find(StageAErrorKind.PARAMETER_TYPE,
                        name + "() converts a string, not " + at.describe() + at(c));
            }
            result = resultOf(name);
        }
        else if (part)
        {
            if (at != Unknown.UNKNOWN && at != Primitive.DATE)
            {
                find(StageAErrorKind.PARAMETER_TYPE, name + " takes a date (D20), not "
                        + at.describe() + " — convert with date(...) first (SPEC §1.2)" + at(c));
            }
            result = "date_part".equals(name) ? Primitive.DATE : Primitive.TIME;
        }
        else
        {
            if (at != Unknown.UNKNOWN && at != Primitive.DATE && at != Primitive.TIME)
            {
                find(StageAErrorKind.PARAMETER_TYPE, name + " takes a date or time (D22), not "
                        + at.describe() + " — convert with date(...) / time(...) first" + at(c));
            }
            // D22's overload: the result base follows the argument; date is the default base.
            result = at == Primitive.TIME ? Primitive.TIME : Primitive.DATE;
        }
        return new TypedExpr(c, result, arg.level(), children);
    }


    /**
     * The result type of a temporal conversion by name: {@code date} → date, {@code time} → time.
     */
    private static ExprType resultOf(String conversionName)
    {
        return "time".equals(conversionName) ? Primitive.TIME : Primitive.DATE;
    }


    /** Walks positional then keyword arguments, in order. */
    private List<TypedExpr> walkArguments(Expr.Call c)
    {
        List<TypedExpr> children = new ArrayList<>(c.args().size() + c.kwargs().size());
        for (Expr arg : c.args())
        {
            children.add(walk(arg));
        }
        for (Expr kwarg : c.kwargs().values())
        {
            children.add(walk(kwarg));
        }
        return children;
    }


    /**
     * The level of a presence fact ({@code var_exists} family, {@code var_is_null}): a dataset
     * fact, unless it names the cursor variable (then per-variable) or carries a {@code ${...}}
     * per-row driver template (then per-row) — {@code DomainScan.existsCall}'s three answers.
     */
    private Level presenceLevel(Expr.Call c)
    {
        Expr arg = c.args().get(0);
        if (isCurrentVariableName(arg))
        {
            return Level.VARIABLE_METADATA;
        }
        String argName;
        if (arg instanceof Expr.Ref r)
        {
            argName = r.name();
        }
        else if (arg instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.STRING)
        {
            argName = (String) lit.value();
        }
        else
        {
            return Level.DATASET;
        }
        return argName.contains("${") ? Level.RECORD : Level.DATASET;
    }


    private TypedExpr accessor(Expr.Call c, MetadataAttribute attr, List<TypedExpr> children)
    {
        List<Expr> args = c.args();
        if (args.isEmpty() || args.size() > 2)
        {
            find(StageAErrorKind.ARITY, attr.functionName() + " takes a level argument and "
                    + "optionally a name argument, not " + args.size() + " argument(s)");
        }
        else
        {
            metadataLevel(attr, args.get(args.size() - 1));
            if (args.size() == 2)
            {
                accessorName(attr, args.get(0));
            }
        }
        Level level;
        if (attr.scope() == MetadataAttribute.Scope.DATASET
                || (args.size() == 2 && !isCurrentVariableName(args.get(0))))
        {
            level = Level.DATASET;
        }
        else
        {
            level = Level.VARIABLE_METADATA;
        }
        return new TypedExpr(c, accessorType(attr), level, children);
    }


    /** §1.1: the {@code (attribute, metadata-level)} legality table — 78 cells, 50 legal. */
    private void metadataLevel(MetadataAttribute attr, Expr levelArg)
    {
        if (!(levelArg instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.STRING)
        {
            find(StageAErrorKind.METADATA_LEVEL_ILLEGAL, attr.functionName()
                    + " requires a literal metadata level (DATA, DEFINE, or LIBRARY)");
            return;
        }
        MetadataLevel level = MetadataLevel.tryParse((String) lit.value());
        if (level == null)
        {
            find(StageAErrorKind.METADATA_LEVEL_ILLEGAL,
                    attr.functionName() + ": unknown metadata level '" + lit.value()
                            + "' (expected DATA, DEFINE, or LIBRARY)");
        }
        else if (!attr.supports(level))
        {
            find(StageAErrorKind.METADATA_LEVEL_ILLEGAL, "(" + attr.functionName() + ", " + level
                    + ") is not a legal (attribute, metadata-level) pair");
        }
    }


    /**
     * §1.2: a name position requires a statically known name — a string literal (the {@code ${...}}
     * template form included) or the current-variable cursor. The typed mirror of
     * {@code ExprCompiler:4583}.
     */
    private void accessorName(MetadataAttribute attr, Expr nameArg)
    {
        boolean literal = nameArg instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.STRING;
        if (!literal && !isCurrentVariableName(nameArg))
        {
            find(StageAErrorKind.NON_STATIC_NAME,
                    attr.functionName() + " name must be a string literal"
                            + (attr.scope() == MetadataAttribute.Scope.VARIABLE
                                    ? " or the variable_name operand"
                                    : ""));
        }
    }


    private ExprType accessorType(MetadataAttribute attr)
    {
        if (attr.isList())
        {
            return new ListOf(Primitive.STRING);
        }
        if (attr.normalization() == Normalization.NUMERIC)
        {
            return Primitive.NUMBER;
        }
        if (attr.normalization() == Normalization.BOOLEAN)
        {
            return Primitive.BOOLEAN;
        }
        return Primitive.STRING;
    }


    private TypedExpr registered(Expr.Call c, FunctionDescriptor descriptor,
            List<TypedExpr> children)
    {
        if (DATE_PREDICATES.contains(c.name()) || TIME_PREDICATES.contains(c.name()))
        {
            // SPEC §5.3 / D27a: both operands carry the base the NAME carries (a point widens to
            // a degenerate interval, so no instant variant exists); a statically-known operand
            // of any other type needs an explicit conversion (§1.2 — no implicit widening).
            ExprType base = DATE_PREDICATES.contains(c.name()) ? Primitive.DATE : Primitive.TIME;
            for (TypedExpr child : children)
            {
                ExprType t = child.type().dereference();
                if (t != Unknown.UNKNOWN && t != base)
                {
                    find(StageAErrorKind.PARAMETER_TYPE,
                            c.name() + " takes " + base.describe() + " operands, not "
                                    + t.describe() + " — convert with "
                                    + (base == Primitive.DATE ? "date(...)" : "time(...)") + at(c));
                }
            }
        }
        checkParameterBinding(c, descriptor, children);
        ExprType type = descriptor.kind() == FunctionKind.BOOLEAN ? Primitive.BOOLEAN
                : resultType(c, children);
        // record_count() folds the row axis (§1.4) — by name, historically — and so does every
        // descriptor declared an aggregate (PLAN-binding-expressions §4.1: a ported list-valued
        // callable such as get_codelist_attributes); an aggregate keeps its operands' cursor.
        Level level;
        if ("record_count".equals(c.name()))
        {
            level = Level.DATASET;
        }
        else if (descriptor.aggregate())
        {
            level = new Level(Granularity.Simple.DATASET, joinChildren(children).cursor());
        }
        else if (descriptor.perRow())
        {
            // A declared row reader (FunctionDescriptor.readingRows: row_max, whose only operand
            // is a static pattern) is at least RECORD level whatever its operands say — the same
            // flag DomainScan reads, so the two calculi cannot drift (combined review XCUT H1).
            level = join(joinChildren(children), Level.RECORD);
        }
        else
        {
            level = joinChildren(children);
        }
        return new TypedExpr(c, type, level, children);
    }

    /** The case-folds, which fold a list argument element-wise (G2). */
    private static final Set<String> CASE_FOLDS = Set.of("upper", "upcase", "lower", "lowcase");

    /**
     * The result type of a registered value call: the {@link ElementTable} row, except that a
     * case-fold over a list-typed argument answers {@code list<string>} — {@code upper} /
     * {@code lower} fold element-wise ({@code BuiltinFunctions.foldValue}; owner 2026-09-28:
     * <i>"upper allows a list of strings as parameter and returns a list"</i>), so
     * {@code IDVAR not in upper($names)} is a list membership, not a scalar right operand
     * ({@code PLAN-stage-a-parameter-type-arming} G2). A zero-argument spelling keeps its
     * {@link StageAErrorKind#ARITY} finding and the scalar row.
     */
    private static ExprType resultType(Expr.Call c, List<TypedExpr> children)
    {
        if (CASE_FOLDS.contains(c.name()) && !children.isEmpty())
        {
            ExprType argument = children.get(0).type();
            if (argument.dereference() instanceof ListOf)
            {
                return new ListOf(Primitive.STRING);
            }
            if (argument == Unknown.UNKNOWN)
            {
                // review r1 sem L1: a fold over a binding of unknown static type (a list or a
                // scalar — nobody knows at load) is unknown, so `X in upper($u)` is not refused;
                // a column reference keeps the string row (a cell folds to a string)
                return Unknown.UNKNOWN;
            }
        }
        return ElementTable.resultType(c.name());
    }


    /**
     * Phase 6b (D16/D19a): binds the call against the one descriptor's parameter list and checks
     * each statically-known argument type against its parameter's declared type. A binding
     * violation — wrong argument count, an unknown argument name, D19a's rebinding of a
     * positionally-bound parameter — is the {@link StageAErrorKind#ARITY} kind (armed: the pre-6b
     * {@code (name, arity)} registry expressed the same contract, so the corpus measures 0 newly
     * parked); a known-vs-known parameter type conflict is {@link StageAErrorKind#PARAMETER_TYPE}.
     *
     * <p>
     * {@code PLAN-stage-a-parameter-type-arming} §3.1 — the parameter-only types are typed by the
     * spelling the reader accepts, nothing wider: a quoted {@code "DM"} or bare {@code DM} at a
     * {@code DATASET_REFERENCE} parameter IS a dataset reference (SPEC §1.1 / D10,
     * {@code GroupedAggregate.readDataset} — G1); a string literal at {@code record_count}'s
     * {@code regex=} IS a regex ({@code RecordCount.readRegex} — G4, C1: the affix / imatches
     * patterns compile from a {@code /…/} literal alone and keep refusing a string); and a
     * {@code list<column-reference>} parameter is typed element by element, with a {@code $}
     * binding holding a list of names accepted as a <b>splice</b> exactly where the reader splices
     * ({@link #splices} — G3). Every other expression keeps its own type, so a computed string or a
     * number at any of these slots is still a finding.
     * </p>
     */
    private void checkParameterBinding(Expr.Call c, FunctionDescriptor descriptor,
            List<TypedExpr> children)
    {
        // ArgumentBinder.bind answers one slot per DECLARED parameter, null where an optional is
        // absent — so the list element type is @Nullable, and saying so is what lets the loop
        // below skip the absent slots under NullAway instead of pretending they cannot occur.
        List<@Nullable Expr> bound;
        try
        {
            bound = ArgumentBinder.bind(descriptor, c);
        }
        catch (ExpressionException ex)
        {
            find(StageAErrorKind.ARITY, messageOf(ex));
            return;
        }
        // Argument order in `children` is walkArguments' — positionals then kwargs — so map each
        // bound slot back to its walked type by expression identity within that order.
        // ⚠ Declared as IdentityHashMap, not Map: reference identity IS the lookup key here
        // (the same `--`-resolved sub-expression can appear twice and must map to its OWN walked
        // type), and Error Prone's [IdentityHashMapUsage] exists because an IdentityHashMap behind
        // a Map-typed reference silently violates the Map contract for any reader who does not
        // know. Naming the concrete type is the statement that the violation is the point.
        IdentityHashMap<Expr, TypedExpr> walkedByIdentity = new IdentityHashMap<>();
        int i = 0;
        for (Expr arg : c.args())
        {
            walkedByIdentity.put(arg, children.get(i++));
        }
        for (Expr kwarg : c.kwargs().values())
        {
            walkedByIdentity.put(kwarg, children.get(i++));
        }
        List<Parameter> params = descriptor.parameters();
        boolean collector = !params.isEmpty() && params.get(params.size() - 1).collector();
        for (int slot = 0; slot < bound.size(); slot++)
        {
            Expr arg = bound.get(slot);
            if (arg == null)
            {
                continue;
            }
            // For a collector descriptor every slot beyond the fixed leads belongs to the
            // trailing collector parameter (its ELEMENT type is what each argument must carry).
            Parameter param = !collector || slot < params.size() - 1 ? params.get(slot)
                    : params.get(params.size() - 1);
            ExprType declared = param.collector() ? ExprType.elementOf(param.type()) : param.type();
            if (declared == Unknown.UNKNOWN)
            {
                continue;
            }
            TypedExpr walked = walkedByIdentity.get(arg);
            if (walked == null)
            {
                continue;
            }
            if (isColumnReferenceList(declared) && arg instanceof Expr.Lit lit
                    && lit.kind() == Expr.LitKind.LIST)
            {
                checkColumnReferenceList(c, param, walked);
                continue;
            }
            if (declared == Primitive.COLUMN_REFERENCE)
            {
                checkColumnReferenceArgument(c, descriptor, param, arg, walked.type());
                continue;
            }
            if (isColumnReferenceList(declared) && splices(c, param)
                    && isSpliceShape(arg, walked.type()) && isPerRow(walked))
            {
                findPerRowSplice(c, param, arg);
                continue;
            }
            ExprType actual = actualType(c, param, declared, arg, walked.type());
            if (actual != Unknown.UNKNOWN && !ExprType.compatible(actual, declared))
            {
                find(StageAErrorKind.PARAMETER_TYPE,
                        "argument '" + param.name() + "' of '" + c.name() + "' takes "
                                + declared.describe() + ", not " + actual.describe() + at(c));
            }
        }
    }


    /**
     * The type a bound argument contributes against its declared parameter type: a column reference
     * dereferences implicitly in value position (§1.2), so only a column-reference-typed parameter
     * sees the reference itself; the two parameter-only literal spellings of G1 / G4 and the bare
     * {@code $}-list splice of G3 (at {@code record_count}'s {@code group=}, the one splicing
     * reader that also takes a bare reference) are typed as what the reader reads.
     */
    private static ExprType actualType(Expr.Call c, Parameter param, ExprType declared, Expr arg,
            ExprType argType)
    {
        if (declared == Primitive.DATASET_REFERENCE && isDatasetSpelling(arg))
        {
            return Primitive.DATASET_REFERENCE;
        }
        if (declared == Primitive.REGEX
                && net.cumba.corej.core.exec.RecordCount.NAME.equals(c.name())
                && net.cumba.corej.core.exec.RecordCount.REGEX_PARAMETER.equals(param.name())
                && isStringLiteral(arg))
        {
            return Primitive.REGEX;
        }
        if (isColumnReferenceList(declared) && splices(c, param) && isSpliceShape(arg, argType)
                && net.cumba.corej.core.exec.RecordCount.NAME.equals(c.name()))
        {
            return declared;
        }
        return argType.dereference();
    }


    /**
     * A scalar {@code COLUMN_REFERENCE} parameter (or a collector element of them —
     * {@code tuple(A, B, …)}) is R1's seam, typed as the runtime reads it: a <b>literal</b> of any
     * kind is refused (a quoted name is a string, never a column — the retired {@code stringOf}
     * spelling; {@code ExprCompiler.rejectLiteralColumnArguments} refuses the same literal at the
     * compile sites), a statically list-typed argument is refused (a list of names is not a
     * column), and a reference or a <b>computed</b> scalar passes — a registry function receives
     * its arguments as per-row value vectors, so {@code date_diff_days(upper(TFDTC), …)} and
     * {@code tuple(upper(ARMCD), ARM)} read the computed value exactly as a column's cells
     * (measured at arming: both are pinned engine behaviour, and the earlier known-vs-known test
     * refused them). The cell type is stage B's (D10).
     */
    private void checkColumnReferenceArgument(Expr.Call c, FunctionDescriptor descriptor,
            Parameter param, Expr arg, ExprType type)
    {
        if (arg instanceof Expr.Lit lit)
        {
            String literal = lit.kind() == Expr.LitKind.LIST ? "a list literal"
                    : String.valueOf(lit.value());
            find(StageAErrorKind.PARAMETER_TYPE,
                    "argument '" + param.name() + "' of '" + c.name()
                            + "' takes a column reference, not the literal " + literal
                            + " — a quoted name is a string, never a column" + at(c));
        }
        else if (type.dereference() instanceof ListOf)
        {
            find(StageAErrorKind.PARAMETER_TYPE, "argument '" + param.name() + "' of '" + c.name()
                    + "' takes a column reference, not " + type.describe() + at(c));
        }
        else if (descriptor.fn() == null && type != Primitive.COLUMN_REFERENCE
                && type.dereference() != Unknown.UNKNOWN)
        {
            // review r1 sem L6: a compiler-dispatched call reads its column parameters with a
            // STRICT reader (ExprCompiler.groupOperandName: a column, a wildcard, nothing else),
            // so a known non-reference is refused here with stage A's message rather than at
            // compile time; a registry-evaluated function reads value vectors and keeps
            // accepting a computed scalar (the branch above this one)
            find(StageAErrorKind.PARAMETER_TYPE, "argument '" + param.name() + "' of '" + c.name()
                    + "' takes a column reference, not " + type.describe() + at(c));
        }
    }


    /**
     * Review r1 sem L7: a {@code $} binding whose derived level is per row (a row granularity or a
     * variable cursor) hands over a per-row vector at evaluation, which {@code GroupSplice} refuses
     * as the rule's ERROR — so the splice is refused at load, where stage A's derived level is
     * certain ({@code colref(list)}, {@code upper(AETERM)}, …). A dataset-level binding of names
     * splices.
     */
    private static boolean isPerRow(TypedExpr e)
    {
        return e.level().granularity() == Granularity.Simple.RECORD
                || e.level().cursor() == Cursor.PRESENT;
    }


    private void findPerRowSplice(Expr.Call c, Parameter param, Expr node)
    {
        String name = node instanceof Expr.Ref ref ? ref.name() : "the binding";
        find(StageAErrorKind.PARAMETER_TYPE, "argument '" + param.name() + "' of '" + c.name()
                + "' cannot splice the per-row binding " + name
                + " — only a dataset-level binding of column names can be spliced (at evaluation"
                + " a per-row binding hands over its vector, which the splice refuses)" + at(c));
    }


    /**
     * G3: a {@code list<column-reference>} parameter given a list literal is typed element by
     * element — a column reference (bare, wildcard, dotted, {@code asc(X)}) or an unknown element
     * passes, a {@code $} binding holding a list of names passes only where the reader
     * {@link #splices}, and any other statically-known element is the finding, named.
     */
    private void checkColumnReferenceList(Expr.Call c, Parameter param, TypedExpr list)
    {
        boolean splicing = splices(c, param);
        int position = 0;
        for (TypedExpr element : list.children())
        {
            position++;
            ExprType type = element.type();
            if (type == Primitive.COLUMN_REFERENCE || type == Unknown.UNKNOWN)
            {
                continue;
            }
            boolean splice = isSpliceShape(element.node(), type);
            if (splice && splicing)
            {
                if (isPerRow(element))
                {
                    findPerRowSplice(c, param, element.node());
                }
                continue;
            }
            String spelled = element.node() instanceof Expr.Ref ref ? " (" + ref.name() + ")"
                    : element.node() instanceof Expr.Lit lit ? " (" + lit.value() + ")" : "";
            find(StageAErrorKind.PARAMETER_TYPE, "argument '" + param.name() + "' of '" + c.name()
                    + "' takes list<column-reference>, not " + type.describe() + " at element "
                    + position + spelled
                    + (splice
                            ? " — a spliced $ binding of names is read only by record_count's"
                                    + " group= and is_(not_)unique_set's members"
                            : "")
                    + at(c));
        }
    }


    /**
     * Whether the reader behind {@code (call, parameter)} splices a {@code $} binding holding a
     * list of column names at evaluation — {@code record_count}'s {@code group=}
     * ({@code RecordCount.spec}: {@code allowSplice = true}) and the member list of
     * {@code is_unique_set} / {@code is_not_unique_set} ({@code ExprCompiler.compileUniqueSet}),
     * both through the one {@code GroupSplice}. {@code GroupedAggregate} ({@code max},
     * {@code max_date}, {@code min_date}), {@code ReadValue}, {@code Distinct} and the
     * {@code keys=} readers pass {@code allowSplice = false}, so a splice there stays a finding.
     */
    private static boolean splices(Expr.Call c, Parameter param)
    {
        if (net.cumba.corej.core.exec.RecordCount.NAME.equals(c.name()))
        {
            return net.cumba.corej.core.exec.GroupedAggregate.GROUP_PARAMETER.equals(param.name());
        }
        return ("is_unique_set".equals(c.name()) || "is_not_unique_set".equals(c.name()))
                && "members".equals(param.name());
    }


    /**
     * A {@code $} reference whose static type is what {@code GroupSplice} splices: a list of names
     * ({@code list<string>}), a list of unknowns ({@code colref(list)}), or one name (a
     * {@code string} binding is one member — N29).
     */
    private static boolean isSpliceShape(Expr node, ExprType type)
    {
        return node instanceof Expr.Ref ref
                && ref.kind() == net.cumba.corej.core.expr.OperandKind.OPERATION_REF
                && (type == Primitive.STRING
                        || (type instanceof ListOf list && (list.element() == Primitive.STRING
                                || list.element() == Unknown.UNKNOWN)));
    }


    private static boolean isColumnReferenceList(ExprType declared)
    {
        return declared instanceof ListOf list && list.element() == Primitive.COLUMN_REFERENCE;
    }


    /** The two dataset-reference spellings {@code GroupedAggregate.readDataset} accepts. */
    private static boolean isDatasetSpelling(Expr arg)
    {
        return isStringLiteral(arg) || (arg instanceof Expr.Ref ref
                && ref.kind() == net.cumba.corej.core.expr.OperandKind.COLUMN);
    }


    private static boolean isStringLiteral(Expr arg)
    {
        return arg instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.STRING;
    }


    private TypedExpr unregistered(Expr.Call c, List<TypedExpr> children)
    {
        // Phase 7 (D119h): the former blanket admission of the hardcoded boolean calls and the
        // two negation-dispatched group operators is gone — every compiler-dispatched call now
        // carries a registered descriptor (CompilerDispatchedCalls), so those names take the
        // registered() path above and finally get D91f (ii)'s arity/keyword contract checked.
        String name = c.name();
        if (ORDERING_DESCRIPTORS.contains(name) && c.args().size() == 1)
        {
            // asc(COL) / desc(COL) inside an is_sorted_by by=[…] list: a sort-key
            // descriptor naming a column.
            return new TypedExpr(c, Primitive.COLUMN_REFERENCE, joinChildren(children), children);
        }
        if (registeredAtAnyArity(name))
        {
            find(StageAErrorKind.ARITY, "'" + name + "' does not accept " + c.args().size()
                    + " positional argument(s)");
        }
        else
        {
            find(StageAErrorKind.UNKNOWN_ELEMENT, "unknown element '" + name + "'");
        }
        return new TypedExpr(c, Unknown.UNKNOWN, joinChildren(children), children);
    }


    private static boolean registeredAtAnyArity(String name)
    {
        return FunctionRegistry.all().stream().anyMatch(d -> d.name().equals(name));
    }

    // ------------------------------------------------------------------
    // Levels
    // ------------------------------------------------------------------


    /** D5's join, converting the excluded {@code group × cursor} cell into the D67 finding. */
    private Level join(Level a, Level b)
    {
        try
        {
            return a.join(b);
        }
        catch (ExcludedLevelCellException ex)
        {
            find(StageAErrorKind.LEVEL_EXCLUDED_GROUP_CURSOR, messageOf(ex));
            return EXCLUDED_CELL_RECOVERY;
        }
    }


    private Level joinChildren(List<TypedExpr> children)
    {
        Level level = Level.STUDY;
        for (TypedExpr child : children)
        {
            level = join(level, child.level());
        }
        return level;
    }


    /**
     * The level of a grouped registry call (W5–W7's {@code group=} readers): {@code group(K)} over
     * its keys — the excluded {@code group(K) × cursor} cell (D67) is found exactly as the retired
     * inline operations' was — or {@link Level#RECORD} when the keys are empty.
     */
    private Level groupedLevel(Set<String> groupKeys)
    {
        if (!groupKeys.isEmpty())
        {
            try
            {
                return new Level(new Granularity.Group(groupKeys), Cursor.ABSENT);
            }
            catch (ExcludedLevelCellException ex)
            {
                find(StageAErrorKind.LEVEL_EXCLUDED_GROUP_CURSOR, messageOf(ex));
                return EXCLUDED_CELL_RECOVERY;
            }
        }
        return Level.RECORD;
    }


    /** Best-effort extraction of {@code group=} key column names from a call keyword. */
    private static Set<String> groupKeys(@Nullable Expr groupArg)
    {
        Set<String> keys = new LinkedHashSet<>();
        if (groupArg instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.LIST)
        {
            @SuppressWarnings("unchecked")
            List<Expr> items = (List<Expr>) lit.value();
            for (Expr item : items)
            {
                if (item instanceof Expr.Ref r)
                {
                    keys.add(r.name());
                }
                else if (item instanceof Expr.Lit l && l.kind() == Expr.LitKind.STRING)
                {
                    keys.add((String) l.value());
                }
            }
        }
        else if (groupArg instanceof Expr.Ref r)
        {
            keys.add(r.name());
        }
        return keys;
    }

    // ------------------------------------------------------------------
    // Bindings and match datasets
    // ------------------------------------------------------------------


    /**
     * The level and result type of each {@code $}-binding, derived from its expression (spec §3.2 —
     * a binding's level is derived, never a source), in authored order
     * ({@code PLAN-binding-expressions} R24). A binding is walked with this very checker — the one
     * the Check uses — so its level is the derived level of its expression and its type the
     * expression's type, and a type error inside it is a stage-A finding like one in the Check. The
     * walk sees every EARLIER binding's level and type, which is all a binding may read.
     */
    private void scanBindings(List<CompiledBinding> order)
    {
        for (CompiledBinding compiled : order)
        {
            where = "binding " + compiled.name();
            TypedExpr typed = walk(compiled.expression());
            bindingLevels.put(compiled.name(), typed.level());
            bindingTypes.put(compiled.name(), typed.type());
        }
    }


    /**
     * Spec §3.2: a binding is visible to every <b>later</b> binding; a forward or self reference is
     * a stage-A error (and over the declaration order a cycle implies a forward edge, so one check
     * covers both).
     */
    private void checkBindingOrder()
    {
        // PLAN-binding-expressions R25: over the AUTHORED order of BOTH binding kinds — a compiled
        // binding reading a later operation binding, or the reverse, is the same error. The
        // loader rejects a duplicate name (R1), so the putIfAbsent below never has to choose.
        List<CompiledBinding> order = rule.bindingOrder();
        if (order.isEmpty())
        {
            return;
        }
        Map<String, Integer> declaredAt = new HashMap<>();
        for (int i = 0; i < order.size(); i++)
        {
            declaredAt.putIfAbsent(order.get(i).name(), i);
        }
        for (int i = 0; i < order.size(); i++)
        {
            CompiledBinding binding = order.get(i);
            for (String ref : compiledBindingRefs(binding))
            {
                Integer target = declaredAt.get(ref);
                if (target != null && target >= i)
                {
                    find(StageAErrorKind.FORWARD_OR_CYCLIC_BINDING,
                            "binding " + binding.name() + " references " + ref
                                    + (target == i ? " (itself)" : ", which is declared later"));
                }
            }
        }
    }


    /**
     * §5.0's hand-over contract, row 3, for the one reader left that takes a {@code $}-binding as a
     * <b>dataset-level list</b>: {@code minus} ({@code not empty(minus($a, subtract=$p))}), written
     * in the Check or nested in a compiled binding. A per-row or per-variable compiled binding
     * among its operands is a load error ({@link StageAErrorKind#OPERATION_READS_CURSOR_BINDING}),
     * never a run-time backstop throw. (Until runbook W8 an inline operation was held to the same
     * rule through the fields it read.)
     */
    private void checkListFunctionReads(Iterable<Root> roots)
    {
        for (Root root : roots)
        {
            listFunctionReads(root.expr(), root.label());
        }
        List<CompiledBinding> compiled = rule.getCompiledBindings();
        if (compiled != null)
        {
            for (CompiledBinding binding : compiled)
            {
                listFunctionReads(binding.expression(), "the binding " + binding.name());
            }
        }
    }


    private void listFunctionReads(Expr e, String where)
    {
        switch (e)
        {
        case Expr.And a -> a.parts().forEach(p -> listFunctionReads(p, where));
        case Expr.Or o -> o.parts().forEach(p -> listFunctionReads(p, where));
        case Expr.Not n -> listFunctionReads(n.inner(), where);
        case Expr.Binary b ->
        {
            listFunctionReads(b.left(), where);
            listFunctionReads(b.right(), where);
        }
        case Expr.Call c ->
        {
            if (net.cumba.corej.core.exec.Minus.NAME.equals(c.name()))
            {
                checkListFunctionCall(c, where);
            }
            c.args().forEach(a -> listFunctionReads(a, where));
            c.kwargs().values().forEach(a -> listFunctionReads(a, where));
        }
        case Expr.Lit lit ->
        {
            if (lit.kind() == Expr.LitKind.LIST)
            {
                @SuppressWarnings("unchecked")
                List<Expr> items = (List<Expr>) lit.value();
                items.forEach(item -> listFunctionReads(item, where));
            }
        }
        case Expr.Ref _ ->
        {
            // a reference is not a call
        }
        }
    }


    /**
     * Wave 4 ({@code PLAN-list-functions} D-W4-4): {@code minus} reads its two operands as
     * dataset-level lists — exactly the hand-over form the retired MINUS operation read through
     * {@code BindingValue.forOperation}, whose runtime guard refused a cursor binding. The guard is
     * kept, at load: a {@code $}-reference to a per-row or per-variable binding bound to
     * {@code minus} is the same finding as an operation reading one.
     */
    private void checkListFunctionCall(Expr.Call c, String where)
    {
        List<Expr> operands = new ArrayList<>(c.args());
        operands.addAll(c.kwargs().values());
        for (Expr operand : operands)
        {
            if (operand instanceof Expr.Ref ref
                    && ref.kind() == net.cumba.corej.core.expr.OperandKind.OPERATION_REF)
            {
                CompiledBinding read = rule.compiledBinding(ref.name());
                if (read != null && read.needsCursor())
                {
                    find(StageAErrorKind.OPERATION_READS_CURSOR_BINDING,
                            "the list function " + c.name() + "(…) in " + where
                                    + " cannot read the per-row or per-variable binding "
                                    + ref.name() + " — a set difference reads only dataset-level"
                                    + " lists");
                }
            }
        }
    }


    /** The {@code $}-references a compiled binding's expression makes. */
    private static Set<String> compiledBindingRefs(CompiledBinding compiled)
    {
        Set<String> refs = new LinkedHashSet<>();
        collectOperationRefs(compiled.expression(), refs);
        return refs;
    }


    private static void collectOperationRefs(Expr e, Set<String> refs)
    {
        switch (e)
        {
        case Expr.And a -> a.parts().forEach(p -> collectOperationRefs(p, refs));
        case Expr.Or o -> o.parts().forEach(p -> collectOperationRefs(p, refs));
        case Expr.Not n -> collectOperationRefs(n.inner(), refs);
        case Expr.Binary b ->
        {
            collectOperationRefs(b.left(), refs);
            collectOperationRefs(b.right(), refs);
        }
        case Expr.Lit lit ->
        {
            if (lit.kind() == Expr.LitKind.LIST)
            {
                @SuppressWarnings("unchecked")
                List<Expr> items = (List<Expr>) lit.value();
                items.forEach(i -> collectOperationRefs(i, refs));
            }
        }
        case Expr.Ref r ->
        {
            if (r.name().startsWith("$"))
            {
                refs.add(r.name());
            }
        }
        case Expr.Call c ->
        {
            c.args().forEach(a -> collectOperationRefs(a, refs));
            c.kwargs().values().forEach(k -> collectOperationRefs(k, refs));
        }
        }
    }


    /**
     * The {@code Match_Datasets} checks decidable from the expression alone (all armed, all
     * measured zero over both populations — see each kind): per flag reference, {@code _matched_}
     * under an inner join (D88e) or with no usable defining entry / in value position
     * ({@link StageAErrorKind#MATCHED_FLAG_INVALID}); per dotted operand, a qualifier naming no
     * entry ({@link StageAErrorKind#DOTTED_REF_UNDECLARED} — the value-read sibling of that same
     * dangling-qualifier arm) or a {@code Child: true} entry
     * ({@link StageAErrorKind#DOTTED_REF_CHILD_ENTRY}); and the D62 unqualified-merged-column
     * heuristic (observe-only by ruling — D62a's three {@code SUPPAE} rules read {@code AESMIE}
     * bare and work today).
     */
    private void checkMatchDatasets(java.util.Collection<Expr> roots)
    {
        List<MatchDataset> matches = rule.getMatchDatasets();
        checkMatchedFlags(roots, matches);
        // ⛔ Before the early return, not after: a rule with NO Match_Datasets at all and a dotted
        // operand is the *typical* shape of the authoring error this check exists for.
        checkDottedRefs(roots, matches);
        // Its own call, not a tail of checkDottedRefs: that check returns early when the Check
        // has no dotted operand, and CG0043's shape (bare Check, dotted output) is exactly that.
        checkDottedOutputVariables(matches == null ? List.of() : matches, roots);
        if (matches == null || matches.isEmpty())
        {
            return;
        }
        // ⭐ Phase 6b (D110g(iii) — the ambiguity load-error D88b routed to the binding model):
        // `RuleRunner.buildJoinedDatasets` keys the join lookups BY NAME, last-wins, so a rule
        // declaring two entries under one Name silently reads only the surviving entry — with the
        // 5b-J `_matched_` flag now one more reader of the survivor. Armed: 0 duplicates across
        // the authored corpus (4 349 rule files, measured 2026-09-16).
        Set<String> seenNames = new HashSet<>();
        for (MatchDataset match : matches)
        {
            String dupName = match.getName();
            if (dupName != null && !dupName.isEmpty() && !seenNames.add(dupName))
            {
                find(StageAErrorKind.DUPLICATE_MATCH_DATASET_NAME,
                        "two Match_Datasets entries share the Name '" + dupName
                                + "'; the join lookup is name-keyed, so only the LAST entry"
                                + " would take effect — merge the entries or rename one");
            }
        }
        for (MatchDataset match : matches)
        {
            checkFilter(match);
            String name = match.getName();
            if (name == null || name.isEmpty()
                    || !name.equals(name.toUpperCase(java.util.Locale.ROOT)) || name.contains("-")
                    || name.contains("*"))
            {
                continue;
            }
            for (String ref : referencedNames)
            {
                if (ref.length() > name.length() && ref.startsWith(name) && !ref.contains(".")
                        && !ref.contains("$"))
                {
                    find(StageAErrorKind.MERGED_COLUMN_UNQUALIFIED,
                            "'" + ref + "' looks like an "
                                    + "unqualified reference to a column of matched dataset '"
                                    + name + "' (D62: a merged column is always qualified)");
                }
            }
        }
    }


    /**
     * The {@code Filter} checks of phase 5b-J (spec §3.3 / §9), per {@code Match_Datasets} entry: a
     * filter on an entry the engine cannot pre-filter, unparseable filter text or a wildcard
     * reference ({@link StageAErrorKind#FILTER_INVALID}), and any reference that is not a plain
     * right-side column — dotted, {@code $}, {@code _matched_}
     * ({@link StageAErrorKind#FILTER_LEFT_REFERENCE}). Column <em>resolvability</em> is stage B's
     * (D89 — it needs the joined dataset's inventory); everything here is decidable from the rule
     * text alone.
     */
    private void checkFilter(MatchDataset match)
    {
        String text = match.getFilter();
        if (text == null || text.isBlank())
        {
            return;
        }
        String name = String.valueOf(match.getName());
        if (Boolean.TRUE.equals(match.getChild()) || "RELREC".equalsIgnoreCase(name)
                || match.getKeys() == null || match.getKeys().isEmpty())
        {
            find(StageAErrorKind.FILTER_INVALID, "the Filter on Match_Datasets entry " + name
                    + " can never be applied: filters need a plain keyed join (no Child: true, "
                    + "no RELREC, non-empty Keys)");
            return;
        }
        Expr parsed;
        try
        {
            parsed = match.filterExpr();
        }
        catch (ExpressionException ex)
        {
            find(StageAErrorKind.FILTER_INVALID, "the Filter on Match_Datasets entry " + name
                    + " does not parse: " + ex.getMessage());
            return;
        }
        if (parsed != null)
        {
            checkFilterRefs(parsed, name);
        }
    }


    /** The reference-kind walk of {@link #checkFilter}, per spec §3.3's right-side-only choice. */
    private void checkFilterRefs(Expr e, String entryName)
    {
        switch (e)
        {
        case Expr.And a -> a.parts().forEach(p -> checkFilterRefs(p, entryName));
        case Expr.Or o -> o.parts().forEach(p -> checkFilterRefs(p, entryName));
        case Expr.Not n -> checkFilterRefs(n.inner(), entryName);
        case Expr.Binary b ->
        {
            checkFilterRefs(b.left(), entryName);
            checkFilterRefs(b.right(), entryName);
        }
        case Expr.Lit lit ->
        {
            if (lit.kind() == Expr.LitKind.LIST && lit.value() instanceof List<?> items)
            {
                for (Object item : items)
                {
                    if (item instanceof Expr inner)
                    {
                        checkFilterRefs(inner, entryName);
                    }
                }
            }
        }
        case Expr.Ref r ->
        {
            switch (r.kind())
            {
            case DOTTED_REF, OPERATION_REF, MATCHED_FLAG -> find(
                    StageAErrorKind.FILTER_LEFT_REFERENCE,
                    "the Filter on Match_Datasets entry " + entryName + " references '" + r.name()
                            + "' — a filter is evaluated on " + entryName
                            + "'s OWN rows before the join and may only reference its plain "
                            + "columns (spec §3.3: a left-side reference would be a correlated "
                            + "sub-join)");
            case WILDCARD_COLUMN -> find(StageAErrorKind.FILTER_INVALID,
                    "the Filter on Match_Datasets entry " + entryName + " references the "
                            + "wildcard '" + r.name() + "' — filters are never specialised, so "
                            + "wildcards cannot resolve there");
            case COLUMN, BUILTIN ->
            {
                // plain right-side column (stage B checks resolvability, D89); builtins read
                // the joined dataset's own facts and are legitimate
            }
            }
        }
        case Expr.Call c ->
        {
            if ("colref".equals(c.name()) && c.args().size() == 1)
            {
                checkWrittenColrefInFilter(c.args().get(0), entryName);
            }
            c.args().forEach(a -> checkFilterRefs(a, entryName));
            c.kwargs().values().forEach(a -> checkFilterRefs(a, entryName));
        }
        }
    }


    /**
     * {@code PLAN-dynamic-column-functions} review round 3: a WRITTEN dotted {@code colref} name in
     * a Filter ({@code colref("DM.SEX")}, or such a member of a list literal) is the same left-side
     * reference as the authored {@code DM.SEX} — the filter runs on the entry's own rows before the
     * join, where no other dataset is joined — so it is the same {@code FILTER_LEFT_REFERENCE} load
     * error rather than a silent all-rows-out default. A computed name is only known per row and
     * stays out.
     */
    private void checkWrittenColrefInFilter(Expr arg, String entryName)
    {
        List<?> items = arg instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.LIST
                && lit.value() instanceof List<?> members ? members : List.of(arg);
        for (Object item : items)
        {
            if (item instanceof Expr.Lit name && name.kind() == Expr.LitKind.STRING
                    && String.valueOf(name.value()).indexOf('.') > 0)
            {
                find(StageAErrorKind.FILTER_LEFT_REFERENCE,
                        "the Filter on Match_Datasets entry " + entryName + " references '"
                                + name.value() + "' through colref — a filter is evaluated on "
                                + entryName + "'s OWN rows before the join and may only reference"
                                + " its plain columns (spec §3.3: a left-side reference would be a"
                                + " correlated sub-join)");
            }
        }
    }


    /**
     * The join-match-flag checks of phase 5b-J (spec §3.3, D88/D88e), per {@code _matched_}
     * reference: value position, no defining entry, a {@code Child: true} or keyless entry, or an
     * inner join. A flag whose qualifier matches no entry is silently deferred when any entry name
     * is still a template ({@code --} / {@code *} / {@code ${} / {@code &}) — this checker also
     * runs at package load, before specialisation binds those names, and the specialised
     * per-dataset pass re-checks with concrete names. ⚠ The qualifier is resolved by {@link
     * #entryFor}, which also matches an instance of a {@code --} template ({@code SUPPAE._matched_}
     * names a {@code SUPP--} entry): a {@code Child: true} entry keeps its template name through
     * specialisation, so an exact comparison let {@code SUPPAE._matched_} slip past the Child arm
     * at both passes and read a SUPPAE self-join at run time (the Stage-A hole of {@code
     * PLAN-hashed-join-arm-absent-columns} §2b, closed 2026-09-25).
     */
    private void checkMatchedFlags(java.util.Collection<Expr> roots,
            @Nullable List<MatchDataset> matches)
    {
        Set<String> flags = new LinkedHashSet<>();
        Set<String> misused = new LinkedHashSet<>();
        for (Expr root : roots)
        {
            collectMatchedFlags(root, true, flags, misused);
        }
        for (String flag : misused)
        {
            find(StageAErrorKind.MATCHED_FLAG_INVALID,
                    flag + " is a boolean condition, not a " + "value (D88b: `not " + flag
                            + "` needs no comparison) — it may only stand "
                            + "where a boolean is expected (bare, or under and/or/not)");
        }
        if (flags.isEmpty())
        {
            return;
        }
        List<MatchDataset> entries = matches == null ? List.of() : matches;
        boolean templates = anyTemplateEntryName(matches);
        for (String flag : flags)
        {
            String qualifier = flag.substring(0, flag.indexOf('.'));
            MatchDataset entry = entryFor(qualifier, entries);
            if (entry == null)
            {
                if (!templates)
                {
                    find(StageAErrorKind.MATCHED_FLAG_INVALID,
                            flag + " references no " + "Match_Datasets entry named " + qualifier
                                    + " — the flag is defined only by a declared join (spec §3.3)");
                }
                continue;
            }
            if (Boolean.TRUE.equals(entry.getChild()))
            {
                find(StageAErrorKind.MATCHED_FLAG_INVALID, flag + " names a Child: true entry — "
                        + "a pre-merged parent join carries no match flag");
                continue;
            }
            if (entry.getKeys() == null || entry.getKeys().isEmpty())
            {
                find(StageAErrorKind.MATCHED_FLAG_INVALID, flag + " names an entry with no Keys "
                        + "— without join keys there is nothing to match");
                continue;
            }
            String joinType = entry.getJoinType();
            if (joinType == null || "inner".equalsIgnoreCase(joinType))
            {
                find(StageAErrorKind.MATCHED_FLAG_INNER_JOIN,
                        flag + " with an inner join is "
                                + "constant-true (D88e): inner drops the unmatched rows — declare "
                                + "Join_Type: \"left\" on the " + qualifier + " entry");
            }
        }
    }


    /**
     * The {@code Match_Datasets} entry a qualifier names: the entry whose {@code Name} equals it,
     * else an entry whose {@code --} template it instantiates ({@code SUPPAE} names
     * {@code SUPP--}). ⭐ ONE resolution, shared by {@link #checkMatchedFlags} and
     * {@link #checkDottedRefs} — a second copy is how the two qualifier checks would drift apart.
     * The template arm exists because a {@code Child: true} entry keeps its template name through
     * specialisation ({@code RuleSpecialiser} leaves a Child name for the per-row pointer, and
     * {@code DatasetIdentity.resolveWildcard} binds it only at run time), so an exact comparison
     * alone found no entry for {@code SUPPAE._matched_} at either pass, deferred it as unbound, and
     * let it read a SUPPAE self-join at run time (closed 2026-09-25,
     * {@code PLAN-hashed-join-arm-absent-columns} §3 follow-up 2). An exact match wins over a
     * template match.
     *
     * @param qualifier
     *            the dotted operand's qualifier — the text before the first {@code .}
     * @param entries
     *            the rule's {@code Match_Datasets}, never {@code null}
     *
     * @return the entry, or {@code null} when none is named
     */
    private static @Nullable MatchDataset entryFor(String qualifier, List<MatchDataset> entries)
    {
        for (MatchDataset m : entries)
        {
            if (qualifier.equals(m.getName()))
            {
                return m;
            }
        }
        for (MatchDataset m : entries)
        {
            if (instantiatesTemplate(m.getName(), qualifier))
            {
                return m;
            }
        }
        return null;
    }


    /**
     * Whether {@code qualifier} is an instance of the {@code --} template {@code name}: the same
     * text before and after the {@code --}, with a non-empty domain in its place — exactly the
     * shape {@code DatasetIdentity.resolveWildcard} produces ({@code SUPP--} → {@code SUPPAE}). A
     * name with no {@code --} is not a template of anything.
     *
     * @param name
     *            an entry name, possibly {@code null}
     * @param qualifier
     *            the concrete qualifier
     *
     * @return whether the qualifier instantiates the template
     */
    static boolean instantiatesTemplate(@Nullable String name, String qualifier)
    {
        if (name == null)
        {
            return false;
        }
        int at = name.indexOf("--");
        if (at < 0)
        {
            return false;
        }
        String prefix = name.substring(0, at);
        String suffix = name.substring(at + 2);
        return qualifier.length() > prefix.length() + suffix.length()
                && qualifier.startsWith(prefix) && qualifier.endsWith(suffix);
    }


    /**
     * Whether any {@code Match_Datasets} entry name is <b>still a template</b> — {@code null},
     * {@code --}, {@code *}, {@code ${} or {@code &}. This checker also runs at <b>package load,
     * pre-specialisation</b>, where such a name is not yet the dataset it will bind to, so a
     * qualifier that matches nothing may simply be matching a name that does not exist yet; the
     * specialised per-dataset pass re-checks with concrete names. ⭐ ONE mechanism, shared by {@link
     * #checkMatchedFlags} and {@link #checkDottedRefs} — the two qualifier checks must defer on
     * exactly the same rules, and a second copy of this predicate is how they would drift apart.
     *
     * @param matches the rule's {@code Match_Datasets}, possibly {@code null}
     *
     * @return {@code true} when at least one entry name is not yet concrete
     */
    private static boolean anyTemplateEntryName(@Nullable List<MatchDataset> matches)
    {
        return matches != null && matches.stream()
                .anyMatch(m -> m.getName() == null || m.getName().contains("--")
                        || m.getName().contains("*") || m.getName().contains("${")
                        || m.getName().contains("&"));
    }


    /**
     * The <b>value-read</b> sibling of {@link #checkMatchedFlags}' dangling-qualifier arm: a plain
     * {@code DOTTED_REF} operand whose qualifier names no {@code Match_Datasets} entry
     * ({@link StageAErrorKind#DOTTED_REF_UNDECLARED}). Checked over the Check levels and the
     * {@code Bindings} expressions; a {@code Filter} is {@link #checkFilterRefs}' ground, which
     * rejects a dotted reference there outright, so it is deliberately not re-judged here.
     *
     * <p>
     * ⭐ Why this is validation and not an evaluation concern: {@code RuleRunner}'s
     * {@code lookup == null} state has <b>two structurally different causes</b> that one
     * {@code null} conflates — a DECLARED join whose dataset is absent from the run (legitimate; it
     * takes the rule-expected default, {@code ExprCompiler.dottedNotSuppliedDefault}) and a
     * qualifier that was never declared (an authoring error). For the second, no join is ever
     * built, so the operand reads its type default on every row and the check can never fire: a
     * silent {@code PASS} for a rule that never ran. Separating the causes here is what lets the
     * evaluation default stay right for the first (§9c of
     * {@code plans/PLAN-null-free-value-channel.md}).
     * </p>
     *
     * <p>
     * ⛔⛔ <b>Only a VALUE read is judged.</b> A dotted name standing as the argument of an
     * exists-family presence call ({@code var_exists(DM.ARM)}) is a pure metadata question,
     * resolved against the named dataset's own inventory with <b>no join and no
     * {@code Match_Datasets} entry required</b> — {@code StudyRuleClassifier} and
     * {@code AbsentDatasetSkip} both have machinery built for exactly that shape. ⚠ The
     * generalisation is not complete: the authoritative name-vs-value signal is a parameter
     * declared {@link ExprType.Primitive#COLUMN_REFERENCE} (which, per §1.2, does <em>not</em>
     * dereference), and binding arguments to parameters here would duplicate {@code ExprCompiler}'s
     * {@code namePosition} analysis. The exists family is the only name-position shape a dotted
     * operand takes in either measured population; any further name-position call is a REPORTED
     * gap, not a handled case.
     * </p>
     *
     * <p>
     * ⚠ A <b>templated</b> dotted name cannot reach this check at all, and that is a property of
     * the classifier rather than an exclusion here: {@code OperandClassifier.isWildcard} is tested
     * <em>first</em>, so any token containing {@code *}, {@code --}, {@code ${} or an ADaM capture
     * letter is a {@code WILDCARD_COLUMN}, and {@code DOTTED}'s own pattern ({@code
     * ^[A-Z][A-Z0-9]*\.[A-Z][A-Z0-9_]*$}) admits no {@code &} either. {@code ADSL.PH${*}SDT} is
     * therefore never a {@code DOTTED_REF}. The template deferral this method does apply is for the
     * other side — a concrete qualifier against an entry name that is not concrete yet.
     * </p>
     *
     * <p>
     * ⭐ And one refusal that is <b>not</b> deferred: a dotted read whose qualifier resolves ({@link
     * #entryFor}) to a {@code Child: true} entry ({@link StageAErrorKind#DOTTED_REF_CHILD_ENTRY},
     * armed) — a {@code DOTTED_REF} operand, a {@code WILDCARD_COLUMN} operand with a judgeable
     * qualifier ({@link #hasJudgeableQualifier}), a {@code Bindings} expression, or an {@code
     * Output_Variables} entry ({@link #checkDottedOutputVariables}). Owner ruling 2026-09-25: a
     * Child entry is joined only through its pointer and builds no direct lookup, so the read has
     * nothing to read — the parent's columns are merged in bare.
     * </p>
     */
    private void checkDottedRefs(java.util.Collection<Expr> roots,
            @Nullable List<MatchDataset> matches)
    {
        Set<String> dotted = new LinkedHashSet<>();
        Set<String> qualifiedWildcards = new LinkedHashSet<>();
        for (Expr root : roots)
        {
            collectDottedRefs(root, dotted, qualifiedWildcards);
        }
        collectBindingDottedRefs(dotted, qualifiedWildcards);
        // PLAN-dynamic-column-functions §2.3 / §3.1 (H2): a colref / find_vars name whose
        // qualifier is statically known (a literal, or a literal prefix) — judged exactly like the
        // authored DS.X (UNIFORMITY): by the undeclared arm (observe-only) and, since QNU N26, by
        // the Child arm (armed) — it used to skip the Child arm, so colref("AE.AESMIE") on a
        // Child: true entry loaded clean and read the not-supplied default on every row.
        Set<String> dynamic = new LinkedHashSet<>();
        for (Expr root : roots)
        {
            collectDynamicNames(root, dynamic);
        }
        List<CompiledBinding> compiledBindings = rule.getCompiledBindings();
        if (compiledBindings != null)
        {
            compiledBindings.forEach(b -> collectDynamicNames(b.expression(), dynamic));
        }
        List<MatchDataset> entries = matches == null ? List.of() : matches;
        // ⭐ A WILDCARD_COLUMN operand with a JUDGEABLE qualifier (non-empty, no `${`, no `&`:
        // `AE.**SMIE`, `AE.${X}`, `SUPP--.QVAL`, even `*.X`) is judged by the Child arm, and —
        // since QNU N27 — by the undeclared arm too when its qualifier is CONCRETE. It is not a
        // DOTTED_REF, so the dotted-reference walk below does not see it; but ExprCompiler
        // compiles it to a per-row dotted plan that reads
        // through the joined lookup, and with no lookup built for a Child entry that read is the
        // not-supplied default on every row — SILENT (measured 2026-09-25: `AE.**SMIE != "Y"` on
        // a Child entry EXECUTED and fired every row; `"Y" in AE.**SMIE` EXECUTED with none).
        // Only the `${*}` list-operand shape (`X in AE.AES${*}`) is loud at run time
        // (ValueResolver's SubstitutionException) — and it is judged here all the same.
        boolean templates = anyTemplateEntryName(matches);
        for (String ref : qualifiedWildcards)
        {
            String qualifier = ref.substring(0, ref.indexOf('.'));
            MatchDataset entry = entryFor(qualifier, entries);
            if (entry != null && Boolean.TRUE.equals(entry.getChild()))
            {
                find(StageAErrorKind.DOTTED_REF_CHILD_ENTRY, ref + " reads a Child: true entry"
                        + " — a Child entry is joined only through its pointer (RDOMAIN / IDVAR /"
                        + " IDVARVAL) and builds no direct lookup, so a qualified wildcard read of"
                        + " it answers the not-supplied default on every row; the parent row's"
                        + " columns are merged into the primary and are read bare");
            }
            else if (entry == null && !templates && isConcreteQualifier(qualifier)
                    && entries.stream().noneMatch(m -> qualifier.equalsIgnoreCase(m.getName())))
            {
                // QNU N27: a qualified TEMPLATE operand (DM.${V}, ADSL.X${*}, AE.**TERM) reads
                // (A qualifier whose case alone differs from an entry's is owner-pending N2, not
                // judged here — the same exemption the Output_Variables arm carries.)
                // through the join of its CONCRETE qualifier exactly as DM.X does, so an
                // undeclared one is the same authoring error — judged by the same observe-only
                // kind. A `--` / `*` qualifier is resolved later (ExprPrefixResolver) and is not
                // judged here: SUPP--.QVAL against an entry SUPPAE is declared.
                find(StageAErrorKind.DOTTED_REF_UNDECLARED, ref + " references no Match_Datasets"
                        + " entry named " + qualifier + " — a qualified template reads a column"
                        + " of a DECLARED join only; undeclared, no join is ever built for "
                        + qualifier + ", so the operand reads the not-supplied default on every"
                        + " row (or a ${*} list operand fails at run time)");
            }
        }
        if (dotted.isEmpty() && dynamic.isEmpty())
        {
            return;
        }
        for (String name : dynamic)
        {
            String qualifier = name.substring(0, name.indexOf('.'));
            MatchDataset entry = entryFor(qualifier, entries);
            if (entry != null && Boolean.TRUE.equals(entry.getChild()))
            {
                find(StageAErrorKind.DOTTED_REF_CHILD_ENTRY, "the column name " + name
                        + " (a colref / find_vars argument) reads a Child: true entry — a Child"
                        + " entry is joined only through its pointer (RDOMAIN / IDVAR / IDVARVAL)"
                        + " and builds no direct lookup, so the name reads the not-supplied"
                        + " default on every row; the parent row's columns are merged into the"
                        + " primary and are named bare, never as " + qualifier + ".<column>");
                continue;
            }
            if (templates || entry != null)
            {
                continue;
            }
            find(StageAErrorKind.DOTTED_REF_UNDECLARED,
                    "the column name " + name + " (a colref / find_vars argument) references no"
                            + " Match_Datasets entry named " + qualifier + " — undeclared, no join"
                            + " is ever built for " + qualifier + ", so the name reads the"
                            + " not-supplied default on every row, exactly as an authored "
                            + qualifier + ".<column> would");
        }
        for (String ref : dotted)
        {
            String qualifier = ref.substring(0, ref.indexOf('.'));
            MatchDataset entry = entryFor(qualifier, entries);
            if (entry != null)
            {
                // ⭐ Owner ruling 2026-09-25 (PLAN-hashed-join-arm-absent-columns §3, follow-up
                // 1): a Child: true entry is joined only through its pointer, its parent columns
                // arrive BARE through the pre-merge, and no direct lookup is built for it
                // (RuleRunner.buildJoinedDatasets) — so a dotted read of it has nothing to read.
                // Refused at load like the flag, and judged even while a template name remains:
                // the entry was resolved, exactly or as an instance of its own template, so this
                // is not the guess the deferral below exists to avoid.
                if (Boolean.TRUE.equals(entry.getChild()))
                {
                    find(StageAErrorKind.DOTTED_REF_CHILD_ENTRY, ref + " reads a Child: true "
                            + "entry — a Child entry is joined only through its pointer (RDOMAIN /"
                            + " IDVAR / IDVARVAL) and builds no direct lookup; the parent row's "
                            + "columns are merged into the primary and are read bare, never as "
                            + qualifier + ".<column>");
                }
                continue;
            }
            if (templates)
            {
                continue; // a concrete qualifier against a name that is not concrete yet
            }
            find(StageAErrorKind.DOTTED_REF_UNDECLARED,
                    ref + " references no Match_Datasets entry named " + qualifier
                            + " — a dotted reference reads a column of a DECLARED join only (spec"
                            + " §3.3); undeclared, no join is ever built for " + qualifier + ", so"
                            + " the operand reads its type default on every row and the check can"
                            + " never fire");
        }
    }


    /**
     * Whether a wildcard operand name carries a qualifier this checker can judge — the ONE
     * predicate for every surface of the Child arm ({@link #judgeableQualifier}).
     * {@code AE.**SMIE}, {@code AE.${X}} and {@code SUPP--.QVAL} / {@code SUPP--.**X} are judged
     * (the last two because a {@code --} qualifier names its own Child entry exactly, and
     * {@code ExprPrefixResolver} later rewrites it to {@code SUPPAE.QVAL} — a silent not-supplied
     * default on a Child entry, review round 2 M1); {@code ${DS}.X} and {@code &DOM&.X} are not.
     *
     * @param name
     *            the operand name
     *
     * @return whether the text before the first {@code .} is a judgeable qualifier
     */
    private static boolean hasJudgeableQualifier(String name)
    {
        int dot = name.indexOf('.');
        return dot > 0 && judgeableQualifier(name.substring(0, dot));
    }


    /**
     * The one rule, for a Check operand, a {@code Bindings} expression and an
     * {@code Output_Variables} entry alike: a qualifier is judged unless it is itself a
     * {@code ${...}} substitution or an {@code &TOKEN&} — those are bound at run time / expansion
     * and are judged nowhere: on a Child entry the substituted read is silent (the not-supplied
     * default on every row), an accepted gap with zero carriers (review round 2, L6).
     *
     * @param qualifier
     *            the text before the first {@code .}, non-empty
     *
     * @return whether the Child arm judges it
     */
    private static boolean judgeableQualifier(String qualifier)
    {
        return !qualifier.isEmpty() && !qualifier.contains("${") && !qualifier.contains("&");
    }


    /** A qualifier that names one dataset as written — no {@code --}, {@code *}, token. */
    private static boolean isConcreteQualifier(String qualifier)
    {
        return qualifier.matches("[A-Za-z][A-Za-z0-9_]*");
    }


    /**
     * The {@code Outcome.Output_Variables} half of the Child arm ({@code
     * PLAN-hashed-join-arm-absent-columns} review round 1, M2): a dotted output naming a
     * {@code Child: true} entry is refused at load like a dotted operand. Without this,
     * {@code CDISC-CG0043} with {@code Output_Variables: [AE.AESMIE]} loaded clean and
     * {@code RuleRunner}'s violation builder silently dropped the column — no lookup, no value, no
     * message. The authored list is read with its {@code !X} exclusions applied, so an excluded
     * name is not judged. A {@code ${...}} or {@code &TOKEN&} <b>qualifier</b> is bound at run time
     * / expansion and is judged nowhere — silent at run time (the not-supplied default), an
     * accepted gap ({@link #judgeableQualifier}); a judgeable qualifier before such a suffix
     * ({@code AE.${X}}, {@code AE.**TERM}, {@code SUPP--.QVAL}) is judged.
     *
     * @param entries
     *            the rule's {@code Match_Datasets}, never {@code null}
     */
    private void checkDottedOutputVariables(List<MatchDataset> entries,
            java.util.Collection<Expr> roots)
    {
        Outcome outcome = rule.getOutcome();
        boolean templates = anyTemplateEntryName(entries);
        Boolean variableDomain = null;
        for (String name : OutputVariableToken
                .applyExclusions(outcome == null ? null : outcome.getOutputVariables()))
        {
            int dot = name.indexOf('.');
            if (dot <= 0)
            {
                continue;
            }
            String qualifier = name.substring(0, dot);
            if (!judgeableQualifier(qualifier))
            {
                continue; // a ${…} / &TOKEN& qualifier: judged nowhere, silent at run time (L6)
            }
            MatchDataset entry = entryFor(qualifier, entries);
            if (entry != null && Boolean.TRUE.equals(entry.getChild()))
            {
                find(StageAErrorKind.DOTTED_REF_CHILD_ENTRY, "Output_Variables entry " + name
                        + " reads a Child: true entry — a Child entry is joined only through its"
                        + " pointer (RDOMAIN / IDVAR / IDVARVAL) and builds no direct lookup, so"
                        + " the column would be silently omitted from every finding; the parent"
                        + " row's columns are merged into the primary and are reported bare,"
                        + " never as " + qualifier + ".<column>");
                continue;
            }
            if (entry != null || templates || !isConcreteQualifier(qualifier)
                    || entries.stream().anyMatch(m -> qualifier.equalsIgnoreCase(m.getName())))
            {
                // Declared — or a qualifier whose case alone differs from an entry's, which is
                // owner-pending N2 (a template entry matches it ignoring case at run time, a
                // literal one exactly), and is not judged here either way.
                continue;
            }
            if (variableDomain == null)
            {
                variableDomain = evaluatesPerVariable(roots);
            }
            if (!variableDomain)
            {
                // QNU N17: the Check side files DOTTED_REF_UNDECLARED for a dotted read of an
                // undeclared dataset, the output side filed nothing — and at run time the entry
                // is omitted from every finding (RuleRunner.extractOutputValues: no join, no
                // value). Same observe-only kind, same reason. ⚠ A rule evaluated per VARIABLE is
                // exempt: there the runner reports the dotted entry as its own identifier (Fix
                // #18's `<DOMAIN>.<KEY>=<VALUE>` filter form; no shipped rule carries one since
                // CDISC-AD0640–0645 dropped their `AE.<X>` entries, T2 r1), so nothing is omitted.
                find(StageAErrorKind.DOTTED_REF_UNDECLARED, "Output_Variables entry " + name
                        + " references no Match_Datasets entry named " + qualifier
                        + " — a dotted output reads a column of a DECLARED join only; undeclared,"
                        + " no join is ever built for " + qualifier + ", so the entry is silently"
                        + " omitted from every finding");
            }
        }
    }


    /**
     * Whether the rule's Check levels evaluate per VARIABLE ({@code DomainScan}'s cursor, joined
     * over the levels as {@code RuleRunner.buildLevelPlans} joins them) — the domain in which
     * {@code RuleRunner.extractOutputValues} reports an undeclared dotted output as its own
     * identifier rather than omitting it.
     */
    private boolean evaluatesPerVariable(java.util.Collection<Expr> roots)
    {
        net.cumba.corej.core.expr.eval.Domain domain = null;
        net.cumba.corej.core.expr.eval.BindingDomains kinds = net.cumba.corej.core.expr.eval.BindingDomains
                .forRule(rule);
        for (Expr root : roots)
        {
            net.cumba.corej.core.expr.eval.Domain level = net.cumba.corej.core.expr.eval.DomainScan
                    .infer(root, kinds);
            domain = domain == null ? level : domain.join(level);
        }
        return net.cumba.corej.core.expr.eval.Domain.VARIABLE.equals(domain);
    }


    /**
     * Adds the dotted references of the rule's compiled bindings to {@code dotted}
     * ({@code PLAN-binding-expressions} R26: a compiled binding's dotted references are qualifiers
     * like the Check's own).
     *
     * @param dotted
     *            the accumulating set of dotted operand names
     */
    private void collectBindingDottedRefs(Set<String> dotted, Set<String> qualifiedWildcards)
    {
        // PLAN-binding-expressions R26: a compiled binding's dotted references are qualifiers
        // like the Check's own.
        List<CompiledBinding> compiled = rule.getCompiledBindings();
        if (compiled != null)
        {
            for (CompiledBinding binding : compiled)
            {
                collectDottedRefs(binding.expression(), dotted, qualifiedWildcards);
            }
        }
    }


    /**
     * Collects every {@link net.cumba.corej.core.expr.OperandKind#DOTTED_REF} reference of
     * {@code e} into {@code dotted}. Deliberately the same walk shape as
     * {@link #collectMatchedFlags}, minus its boolean-position tracking — a value read has no
     * position rule.
     *
     * @param e
     *            the expression to walk
     * @param dotted
     *            the accumulating set of dotted operand names
     */
    private static void collectDottedRefs(Expr e, Set<String> dotted, Set<String> wildcards)
    {
        switch (e)
        {
        case Expr.And a -> a.parts().forEach(p -> collectDottedRefs(p, dotted, wildcards));
        case Expr.Or o -> o.parts().forEach(p -> collectDottedRefs(p, dotted, wildcards));
        case Expr.Not n -> collectDottedRefs(n.inner(), dotted, wildcards);
        case Expr.Binary b ->
        {
            collectDottedRefs(b.left(), dotted, wildcards);
            collectDottedRefs(b.right(), dotted, wildcards);
        }
        case Expr.Lit lit ->
        {
            if (lit.kind() == Expr.LitKind.LIST && lit.value() instanceof List<?> items)
            {
                for (Object item : items)
                {
                    if (item instanceof Expr inner)
                    {
                        collectDottedRefs(inner, dotted, wildcards);
                    }
                }
            }
        }
        case Expr.Ref r ->
        {
            if (r.kind() == net.cumba.corej.core.expr.OperandKind.DOTTED_REF)
            {
                dotted.add(r.name());
            }
            else if (r.kind() == net.cumba.corej.core.expr.OperandKind.WILDCARD_COLUMN
                    && hasJudgeableQualifier(r.name()))
            {
                wildcards.add(r.name());
            }
        }
        case Expr.Call c ->
        {
            // ⛔⛔ An exists-family presence call is a NAME read, not a value read, and a dotted
            // name there is LEGITIMATE WITHOUT ANY Match_Datasets ENTRY — measured, not reasoned:
            // three of this repo's own tests pin exactly that shape, two of them with the reason
            // written out (StudyRuleClassifier: "presence of a named dataset's column is a pure
            // metadata question"; AbsentDatasetSkipTest's F2 case: "a rule that reads the split
            // domain ONLY through a dotted Check reference (no Match_Datasets) is runnable too —
            // the dotted var_exists unions"). No join is built for it and none is needed, so the
            // undeclared-qualifier reasoning does not apply. BroadcastFold.isExistsCall is the
            // single shared source for that shape — ⛔ do not re-spell it here.
            if (BroadcastFold.isExistsCall(c))
            {
                // `return`, not `break`: an arrow-form switch statement admits no break, and this
                // walk is per-node, so returning skips exactly this call's subtree.
                return;
            }
            c.args().forEach(a -> collectDottedRefs(a, dotted, wildcards));
            c.kwargs().values().forEach(a -> collectDottedRefs(a, dotted, wildcards));
        }
        }
    }


    /**
     * The statically-qualified names a {@code colref} / {@code find_vars} call is handed — a string
     * literal argument, or the leading string literal of a {@code concat(…)} argument (a literal
     * prefix, {@code concat("ADSL.AP", …)}) — whose text carries a judgeable qualifier before its
     * first {@code .}. A {@code find_vars} entry that is a whole-entry {@code /regex/} is never
     * split ({@code ScopeVariableEntry.parse}: a regex contains dots by construction), so it names
     * no qualifier here. A computed qualifier cannot be judged and is not collected.
     */
    private static void collectDynamicNames(Expr e, Set<String> out)
    {
        switch (e)
        {
        case Expr.And a -> a.parts().forEach(p -> collectDynamicNames(p, out));
        case Expr.Or o -> o.parts().forEach(p -> collectDynamicNames(p, out));
        case Expr.Not n -> collectDynamicNames(n.inner(), out);
        case Expr.Binary b ->
        {
            collectDynamicNames(b.left(), out);
            collectDynamicNames(b.right(), out);
        }
        case Expr.Lit lit ->
        {
            if (lit.kind() == Expr.LitKind.LIST && lit.value() instanceof List<?> items)
            {
                for (Object item : items)
                {
                    if (item instanceof Expr inner)
                    {
                        collectDynamicNames(inner, out);
                    }
                }
            }
        }
        case Expr.Ref _ ->
        {
            // a reference names no dynamic column
        }
        case Expr.Call c ->
        {
            boolean findVars = "find_vars".equals(c.name());
            if (("colref".equals(c.name()) || findVars) && c.args().size() == 1)
            {
                String text = staticNameText(c.args().get(0));
                if (text != null && !(findVars && text.startsWith("/")))
                {
                    int dot = text.indexOf('.');
                    if (dot > 0 && text.substring(0, dot).matches("[A-Za-z][A-Za-z0-9_]*"))
                    {
                        out.add(text);
                    }
                }
            }
            c.args().forEach(a -> collectDynamicNames(a, out));
            c.kwargs().values().forEach(a -> collectDynamicNames(a, out));
        }
        }
    }


    /** A string literal's text, or the leading string literal of a {@code concat(…)}. */
    private static @Nullable String staticNameText(Expr arg)
    {
        if (arg instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.STRING)
        {
            return String.valueOf(lit.value());
        }
        if (arg instanceof Expr.Call c && "concat".equals(c.name()) && !c.args().isEmpty()
                && c.args().get(0) instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.STRING)
        {
            return String.valueOf(lit.value());
        }
        return null;
    }


    /**
     * Collects every {@link net.cumba.corej.core.expr.OperandKind#MATCHED_FLAG} reference of
     * {@code e} into {@code flags}, and those standing outside boolean position (anything but
     * bare-at-root or under {@code and}/{@code or}/{@code not}) additionally into {@code misused}.
     */
    private static void collectMatchedFlags(Expr e, boolean boolPosition, Set<String> flags,
            Set<String> misused)
    {
        switch (e)
        {
        case Expr.And a -> a.parts().forEach(p -> collectMatchedFlags(p, true, flags, misused));
        case Expr.Or o -> o.parts().forEach(p -> collectMatchedFlags(p, true, flags, misused));
        case Expr.Not n -> collectMatchedFlags(n.inner(), true, flags, misused);
        case Expr.Binary b ->
        {
            collectMatchedFlags(b.left(), false, flags, misused);
            collectMatchedFlags(b.right(), false, flags, misused);
        }
        case Expr.Lit lit ->
        {
            if (lit.kind() == Expr.LitKind.LIST && lit.value() instanceof List<?> items)
            {
                for (Object item : items)
                {
                    if (item instanceof Expr inner)
                    {
                        collectMatchedFlags(inner, false, flags, misused);
                    }
                }
            }
        }
        case Expr.Ref r ->
        {
            if (r.kind() == net.cumba.corej.core.expr.OperandKind.MATCHED_FLAG)
            {
                flags.add(r.name());
                if (!boolPosition)
                {
                    misused.add(r.name());
                }
            }
        }
        case Expr.Call c ->
        {
            c.args().forEach(a -> collectMatchedFlags(a, false, flags, misused));
            c.kwargs().values().forEach(a -> collectMatchedFlags(a, false, flags, misused));
        }
        }
    }


    private static boolean isCurrentVariableName(Expr e)
    {
        return (e instanceof Expr.Ref ref && "variable_name".equals(ref.name()))
                || (e instanceof Expr.Call c && "varname".equals(c.name()) && c.args().isEmpty()
                        && c.kwargs().isEmpty());
    }

}
