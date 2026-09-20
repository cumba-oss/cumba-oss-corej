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
import net.cumba.corej.core.exec.OperationExecutor;
import net.cumba.corej.core.exec.OperationExecutor.ResultKind;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.convert.OperationExpressionParser;
import net.cumba.corej.core.expr.eval.ArgumentBinder;
import net.cumba.corej.core.expr.eval.BroadcastFold;
import net.cumba.corej.core.expr.eval.ExprCompiler;
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
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Operation;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.report.Severity;
import org.jspecify.annotations.Nullable;

/**
 * The stage-A checker of the typed-expression specification
 * ({@code plans/SPEC-typed-expression-engine.md} §2): runs <b>once, at load, knowing the expression
 * only</b>, builds the {@link TypedExpr typed AST} — every node carrying
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

    /** Names whose call form anchors on the variable cursor unless given an explicit name. */
    private static final Set<String> VARNAME_ANCHORED_CALLS = Set.of("max_value_length",
            "library_variable_code_pair_matches", "define_variable_decode_matches");

    /**
     * The one REMAINING erased mode tag (D91f (i)): {@code str}, which the compiler still
     * special-cases and which is only free as a name after phase 7 (D97c). Phase 3b retired the
     * other three from this set: {@code date} is a real conversion typed {@code date}, and
     * {@code date_part}/{@code time_part} — still erased tags in the compiler until the 3c corpus
     * rewrite (D98b(ii)) — are typed here as the ordinary date-consuming functions D20 makes them.
     */
    private static final Set<String> MODE_TAGS = Set.of("str");

    /** The date-family predicates of SPEC §5.3 (D27), each taking two same-base operands. */
    private static final Set<String> DATE_PREDICATES = Set.of("date_contains", "date_overlaps");

    /** The time-family predicates of SPEC §5.3 (D27). */
    private static final Set<String> TIME_PREDICATES = Set.of("time_contains", "time_overlaps");

    /** The {@code by=[asc("col"), desc("col")]} sort-key descriptor calls of is_sorted_by. */
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
     * Phase 6, D106d: the run's dataset inventory, for the bind-time refinement of
     * {@code $}-binding levels — a cross-dataset operation whose foreign domain is absent
     * degenerates to a scalar at run time, so its binding's level is {@code dataset}, whatever its
     * result kind or grouping says. {@code null} at load (stage A proper) and on the plain
     * {@code deriveTyped} path, where no dataset is known and the declaration-derived level stands
     * as the upper bound.
     */
    private final @Nullable ForeignDatasetInventory foreignDatasets;

    /**
     * The statically-known result type of each {@code $}-binding, derived from its operation —
     * phase 3b: {@code min_date}/{@code max_date} are the corpus' two date-valued operations
     * (verified against {@code OperationType}, which declares only an {@code EmptyResult}, never a
     * result type) and type {@code date}; everything else stays {@link Unknown#UNKNOWN}. ⚠
     * {@code row_max}/{@code row_min}/{@code ts_parameter_value} are date-<i>shaped</i> but
     * string-typed by ruling and are deliberately NOT here.
     */
    private final Map<String, ExprType> bindingTypes;

    private final List<StageAFinding> findings = new ArrayList<>();

    /** Every reference name seen while walking, for the match-dataset checks. */
    private final List<String> referencedNames = new ArrayList<>();

    private StageAChecker(Rule rule)
    {
        this(rule, ColumnLevelResolver.STAGE_A);
    }


    private StageAChecker(Rule rule, ColumnLevelResolver columnLevels)
    {
        this(rule, columnLevels, null);
    }


    private StageAChecker(Rule rule, ColumnLevelResolver columnLevels,
            @Nullable ForeignDatasetInventory foreignDatasets)
    {
        this.rule = rule;
        this.columnLevels = columnLevels;
        this.foreignDatasets = foreignDatasets;
        this.bindingLevels = new HashMap<>();
        this.bindingTypes = new HashMap<>();
        scanBindings(rule.getOperations());
    }


    /**
     * Sets (or clears) the measurement observer. Test / measurement use only.
     */
    public static void setObserver(@Nullable BiConsumer<Rule, StageAReport> observer)
    {
        OBSERVER.set(observer);
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
        StageAReport report = check(rule, levels);
        List<StageAFinding> armed = report.armedFindings();
        if (!armed.isEmpty())
        {
            String joined = String.join("; ", armed.stream().map(StageAFinding::toString).toList());
            String error = "stage A: " + joined;
            rule.setLoadError(
                    rule.getLoadError() == null ? error : rule.getLoadError() + "; " + error);
        }
        if (!report.observedFindings().isEmpty() && LOGGER.isLoggable(System.Logger.Level.DEBUG))
        {
            LOGGER.log(System.Logger.Level.DEBUG, "stage A observations for {0}: {1}", rule.getId(),
                    report.observedFindings());
        }
        BiConsumer<Rule, StageAReport> observer = OBSERVER.get();
        if (observer != null)
        {
            observer.accept(rule, report);
        }
        return report;
    }


    /**
     * Checks one rule's raised level expressions without applying anything to the rule.
     */
    public static StageAReport check(Rule rule, SequencedMap<Severity, Expr> levels)
    {
        StageAChecker checker = new StageAChecker(rule);
        SequencedMap<Severity, TypedExpr> typed = new LinkedHashMap<>();
        try
        {
            for (Map.Entry<Severity, Expr> level : levels.entrySet())
            {
                TypedExpr root = checker.walk(level.getValue());
                if (root.type().dereference() != Unknown.UNKNOWN
                        && root.type().dereference() != Primitive.BOOLEAN)
                {
                    checker.find(StageAErrorKind.PARAMETER_TYPE,
                            "the Check root must be boolean, " + "not " + root.type().describe());
                }
                typed.put(level.getKey(), root);
            }
            checker.checkBindingOrder();
            checker.checkMatchDatasets(levels.values());
        }
        catch (RuntimeException ex)
        {
            // A checker defect must never park a rule — that would be an evaluation change
            // caused by the instrument (phase 2's headline constraint).
            checker.findings.add(new StageAFinding(StageAErrorKind.CHECKER_FAILURE,
                    "stage-A checker failed on rule " + rule.getId() + ": " + ex));
        }
        return new StageAReport(typed, checker.findings);
    }


    private void find(StageAErrorKind kind, String message)
    {
        findings.add(new StageAFinding(kind, message));
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
            requireBoolean(child, "an operand of '" + op + "'");
            level = join(level, child.level());
            children.add(child);
        }
        return new TypedExpr(node, Primitive.BOOLEAN, level, children);
    }


    private TypedExpr not(Expr.Not n)
    {
        TypedExpr inner = walk(n.inner());
        requireBoolean(inner, "the operand of 'not'");
        return new TypedExpr(n, Primitive.BOOLEAN, inner.level(), List.of(inner));
    }


    private void requireBoolean(TypedExpr operand, String position)
    {
        ExprType t = operand.type().dereference();
        if (t != Unknown.UNKNOWN && t != Primitive.BOOLEAN)
        {
            find(StageAErrorKind.PARAMETER_TYPE,
                    position + " must be boolean, not " + t.describe());
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
        case IN, NOT_IN -> membership(left, right);
        case ADD, SUB, MUL, DIV ->
        {
            requireNumber(left, "the left operand of arithmetic");
            requireNumber(right, "the right operand of arithmetic");
            return new TypedExpr(b, Primitive.NUMBER, level, children);
        }
        }
        return new TypedExpr(b, Primitive.BOOLEAN, level, children);
    }


    private void comparison(Expr.Binary b, TypedExpr left, TypedExpr right)
    {
        ExprType lt = left.type().dereference();
        ExprType rt = right.type().dereference();
        if (mixedTemporalString(lt, rt) || mixedTemporalString(rt, lt))
        {
            // §5.2: the operator applies only when BOTH sides are date (or both time); a mixed
            // temporal/string pair is the type error that forces the phase-3c corpus rewrite
            // (D71b) — never a reinterpretation of the untyped side (the plan's §1(4) defect).
            // Phase 3b types the temporal producers, so this is no longer vacuous; it stays
            // armed on the strength of the corpus measurement recorded on the kind.
            find(StageAErrorKind.MIXED_DATE_STRING_COMPARISON,
                    "a comparison must not mix date/time and string operands (" + lt.describe()
                            + " " + b.op() + " " + rt.describe()
                            + ") — convert the string side with date(...) / time(...)");
        }
        else if (!ExprType.compatible(lt, rt))
        {
            find(StageAErrorKind.PARAMETER_TYPE, "comparison operands disagree: " + lt.describe()
                    + " " + b.op() + " " + rt.describe());
        }
    }


    /** §5.2's mixed pair, one direction: a temporal side against a statically-known string. */
    private static boolean mixedTemporalString(ExprType a, ExprType b)
    {
        return (a == Primitive.DATE || a == Primitive.TIME) && b == Primitive.STRING;
    }


    private void regexMatch(Expr.Binary b, TypedExpr left, TypedExpr right)
    {
        requireStringish(left, "the left operand of " + b.op());
        // §1.1: regex is literal-only, never computed — the right-hand side must be a /…/ or
        // string literal.
        if (!(right.node() instanceof Expr.Lit lit)
                || (lit.kind() != Expr.LitKind.REGEX && lit.kind() != Expr.LitKind.STRING))
        {
            find(StageAErrorKind.PARAMETER_TYPE,
                    "the right-hand side of " + b.op() + " must be a regex literal");
        }
    }


    private void membership(TypedExpr left, TypedExpr right)
    {
        ExprType lt = left.type().dereference();
        ExprType rt = right.type().dereference();
        if (rt != Unknown.UNKNOWN && !rt.isCollection())
        {
            find(StageAErrorKind.PARAMETER_TYPE,
                    "the right operand of 'in' must be a list or set, not " + rt.describe());
            return;
        }
        if (lt.isCollection())
        {
            // D81d: list-LHS membership is a COLLECTION shape (each left element probes the
            // right set — the corpus has 10 rules of it, e.g. [A, B] in $set), legal but never
            // absorbed by the scalar `==`-disjunction lowering. Element-wise compatibility is
            // the check.
            if (!ExprType.compatible(ExprType.elementOf(lt), ExprType.elementOf(rt)))
            {
                find(StageAErrorKind.PARAMETER_TYPE, "membership element type "
                        + ExprType.elementOf(lt).describe() + " disagrees with " + rt.describe());
            }
            return;
        }
        if (!ExprType.compatible(lt, ExprType.elementOf(rt)))
        {
            find(StageAErrorKind.PARAMETER_TYPE, "membership element type " + lt.describe()
                    + " disagrees with " + rt.describe());
        }
    }


    private void requireNumber(TypedExpr operand, String position)
    {
        ExprType t = operand.type().dereference();
        if (t != Unknown.UNKNOWN && t != Primitive.NUMBER)
        {
            find(StageAErrorKind.PARAMETER_TYPE,
                    position + " must be a number, not " + t.describe());
        }
    }


    private void requireStringish(TypedExpr operand, String position)
    {
        ExprType t = operand.type().dereference();
        if (t != Unknown.UNKNOWN && t != Primitive.STRING)
        {
            find(StageAErrorKind.PARAMETER_TYPE,
                    position + " must be a string, not " + t.describe());
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
     * constant). Findings the walk records are deliberately discarded — this derivation feeds the
     * level instrument and the effective-granularity observation, never the park channel — and a
     * checker failure yields {@code null} rather than propagating, for the same reason
     * {@link StageAErrorKind#CHECKER_FAILURE} exists on the load path.
     *
     * @param rule
     *            the (specialised) rule the expression belongs to — supplies the {@code $}-binding
     *            levels
     * @param expr
     *            the expression to type
     * @param columnLevels
     *            the bind-time column-level refinement
     * @return the typed root, or {@code null} when the walk failed
     */
    public static @Nullable TypedExpr deriveTyped(Rule rule, Expr expr,
            ColumnLevelResolver columnLevels)
    {
        return deriveTyped(rule, expr, columnLevels, null);
    }


    /**
     * The phase-6 widening of the phase-5 entry point (D106d): the same typed tree, with the
     * {@code $}-binding levels additionally refined from the run's dataset inventory — a
     * cross-dataset operation over an absent foreign domain is typed at {@code dataset}, matching
     * its runtime degeneration to a scalar (see {@link #degeneratesToScalar}). {@code null}
     * inventory means "no refinement": the declaration-derived level stands as the upper bound.
     *
     * @param rule
     *            the (specialised) rule the expression belongs to
     * @param expr
     *            the expression to type
     * @param columnLevels
     *            the bind-time column-level refinement (D39a)
     * @param foreignDatasets
     *            the run's dataset inventory, or {@code null}
     * @return the typed root, or {@code null} when the walk failed
     */
    public static @Nullable TypedExpr deriveTyped(Rule rule, Expr expr,
            ColumnLevelResolver columnLevels, @Nullable ForeignDatasetInventory foreignDatasets)
    {
        try
        {
            return new StageAChecker(rule, columnLevels, foreignDatasets).walk(expr);
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
            // §1.2 / §7.3: per-row column selection — a string → column-reference at record
            // level, one of the three dynamic name forms (D62b).
            if (c.args().size() != 1 || !c.kwargs().isEmpty())
            {
                find(StageAErrorKind.ARITY, "colref takes exactly one argument");
            }
            return new TypedExpr(c, Primitive.COLUMN_REFERENCE, Level.RECORD, children);
        }
        if (MODE_TAGS.contains(name) && c.args().size() == 1 && c.kwargs().isEmpty())
        {
            // D91f (i): str is the one remaining erased identity (live until phase 7, D97c) —
            // typed as passthrough.
            TypedExpr inner = children.get(0);
            return new TypedExpr(c, inner.type(), inner.level(), children);
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
        if (ExprCompiler.isInlineOperation(c))
        {
            return inlineOperation(c, children);
        }
        FunctionDescriptor descriptor = FunctionRegistry.descriptor(name);
        if (descriptor != null)
        {
            return registered(c, descriptor, children);
        }
        return unregistered(c, children);
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
     * Parameter findings here are {@link StageAErrorKind#PARAMETER_TYPE} (observe-only): a
     * statically NUMBER argument to {@code date()} is D55's {@code date(NUM)} shape — the stage-B
     * bind gate is the armed half; this is its static shadow, with the {@code date_from_sas_days} /
     * {@code date_from_sas_datetime} rewrite named in the message.
     * </p>
     */
    private @Nullable TypedExpr temporalCall(Expr.Call c, List<TypedExpr> children)
    {
        String name = c.name();
        boolean conversion = "date".equals(name) || "time".equals(name);
        boolean part = "date_part".equals(name) || "time_part".equals(name);
        boolean bound = "earliest_possible".equals(name) || "latest_possible".equals(name);
        if (!conversion && !part && !bound)
        {
            return null;
        }
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
                        + "(...) or date_from_sas_datetime(...) for a numeric date column");
            }
            else if (at != Unknown.UNKNOWN && at != Primitive.STRING && at != resultOf(name))
            {
                // The conversion is idempotent on its own base; any other known type is not a
                // temporal text.
                find(StageAErrorKind.PARAMETER_TYPE,
                        name + "() converts a string, not " + at.describe());
            }
            result = resultOf(name);
        }
        else if (part)
        {
            if (at != Unknown.UNKNOWN && at != Primitive.DATE)
            {
                find(StageAErrorKind.PARAMETER_TYPE, name + " takes a date (D20), not "
                        + at.describe() + " — convert with date(...) first (SPEC §1.2)");
            }
            result = "date_part".equals(name) ? Primitive.DATE : Primitive.TIME;
        }
        else
        {
            if (at != Unknown.UNKNOWN && at != Primitive.DATE && at != Primitive.TIME)
            {
                find(StageAErrorKind.PARAMETER_TYPE, name + " takes a date or time (D22), not "
                        + at.describe() + " — convert with date(...) / time(...) first");
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


    /**
     * A Form-A inline operation: the arguments are operation parameters (names, keywords), not
     * reads, so the result kind — refined by an explicit {@code group=} onto {@code group(K)}
     * granularity — decides the level alone, exactly as for the declared {@code $} form.
     */
    private TypedExpr inlineOperation(Expr.Call c, List<TypedExpr> children)
    {
        Set<String> groupKeys = groupKeys(c.kwargs().get("group"));
        ResultKind kind;
        try
        {
            kind = OperationExecutor.resultKind(OperationExpressionParser.fromCall(c, null));
        }
        catch (RuntimeException _)
        {
            kind = ResultKind.SCALAR;
        }
        return new TypedExpr(c, ElementTable.resultType(c.name()), operationLevel(kind, groupKeys),
                children);
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
                                    + (base == Primitive.DATE ? "date(...)" : "time(...)"));
                }
            }
        }
        checkParameterBinding(c, descriptor, children);
        ExprType type = descriptor.kind() == FunctionKind.BOOLEAN ? Primitive.BOOLEAN
                : ElementTable.resultType(c.name());
        // record_count() folds the row axis (§1.4): the one registry entry that raises.
        Level level = "record_count".equals(c.name()) ? Level.DATASET : joinChildren(children);
        return new TypedExpr(c, type, level, children);
    }


    /**
     * Phase 6b (D16/D19a): binds the call against the one descriptor's parameter list and checks
     * each statically-known argument type against its parameter's declared type. A binding
     * violation — wrong argument count, an unknown argument name, D19a's rebinding of a
     * positionally-bound parameter — is the {@link StageAErrorKind#ARITY} kind (armed: the pre-6b
     * {@code (name, arity)} registry expressed the same contract, so the corpus measures 0 newly
     * parked); a known-vs-known parameter type conflict stays
     * {@link StageAErrorKind#PARAMETER_TYPE} (observe — the declared types are still partial,
     * D91d).
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
        IdentityHashMap<Expr, ExprType> typesByIdentity = new IdentityHashMap<>();
        int i = 0;
        for (Expr arg : c.args())
        {
            typesByIdentity.put(arg, children.get(i++).type());
        }
        for (Expr kwarg : c.kwargs().values())
        {
            typesByIdentity.put(kwarg, children.get(i++).type());
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
            ExprType argType = typesByIdentity.get(arg);
            if (argType == null)
            {
                continue;
            }
            // A column reference dereferences implicitly in value position (§1.2), so only a
            // column-reference-typed parameter sees the reference itself.
            ExprType actual = declared == Primitive.COLUMN_REFERENCE ? argType
                    : argType.dereference();
            if (actual != Unknown.UNKNOWN && !ExprType.compatible(actual, declared))
            {
                find(StageAErrorKind.PARAMETER_TYPE,
                        "argument '" + param.name() + "' of '" + c.name() + "' takes "
                                + declared.describe() + ", not " + actual.describe());
            }
        }
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
            // asc("col") / desc("col") inside an is_sorted_by by=[…] list: a sort-key
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
     * Phase 6, D106d — <b>the level is an upper bound on runtime decidability, and here the bound
     * tightens</b>: a cross-dataset operation whose {@code domain=} names a dataset this run cannot
     * resolve degenerates to a scalar at execution ({@code OperationExecutor.
     * resolveTargetTable} yields {@code null} and the operation folds to one value), so the
     * binding's true level is {@code dataset} whatever the declaration says. Applied only when a
     * {@link ForeignDatasetInventory} is supplied (the bind-time {@code deriveTyped} path — the
     * load-path checker has no dataset and keeps the declaration-derived level). Mirrors the
     * executor's own exemptions: {@code date_diff_days} keeps the primary as its target, and a
     * {@code *} wildcard or unspecialised {@code --} domain is not a concrete reference. The
     * split-SUPP self-reference redirect (J7) is the inventory implementation's to answer — the
     * instrument's inventory reports such a domain as resolvable.
     */
    private boolean degeneratesToScalar(Operation op)
    {
        if (foreignDatasets == null
                || op.getOperationType() == net.cumba.corej.core.model.OperationType.DATE_DIFF_DAYS)
        {
            return false;
        }
        String domain = op.getDomain();
        if (domain == null || domain.isEmpty() || "*".equals(domain) || domain.contains("--"))
        {
            return false;
        }
        return foreignDatasets.columnsOf(domain) == null;
    }


    private Level operationLevel(ResultKind kind, Set<String> groupKeys)
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
        return switch (kind)
        {
        case PER_ROW -> Level.RECORD;
        case PER_VARIABLE -> Level.VARIABLE_METADATA;
        case SCALAR -> Level.DATASET;
        };
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
     * The level and result type of each {@code $}-binding, derived from its declaration (spec §3.2
     * — a binding's level is derived, never a source): {@code group} keys give {@code group(K)},
     * otherwise the operation's result kind decides, the same classification {@code DomainScan}
     * routes on. The result type (phase 3b) comes from the operation's function name — see
     * {@link #bindingTypes}.
     */
    private void scanBindings(@Nullable List<Operation> operations)
    {
        if (operations == null || operations.isEmpty())
        {
            return;
        }
        for (Operation op : operations)
        {
            String id = op.getId();
            if (id == null)
            {
                continue;
            }
            Operation normalized;
            ResultKind kind;
            try
            {
                normalized = OperationExpressionParser.normalize(op);
                kind = OperationExecutor.resultKind(normalized);
            }
            catch (RuntimeException _)
            {
                // A declaration the parser rejects is reported on the loader's own channel.
                bindingLevels.put(id, Level.DATASET);
                continue;
            }
            List<String> group = normalized.getGroup();
            Set<String> keys = group == null ? Set.of() : new LinkedHashSet<>(group);
            bindingLevels.put(id,
                    degeneratesToScalar(normalized) ? Level.DATASET : operationLevel(kind, keys));
            bindingTypes.put(id, ElementTable
                    .resultType(normalized.getOperator() == null ? "" : normalized.getOperator()));
        }
    }


    /**
     * Spec §3.2: a binding is visible to every <b>later</b> binding; a forward or self reference is
     * a stage-A error (and over the declaration order a cycle implies a forward edge, so one check
     * covers both).
     */
    private void checkBindingOrder()
    {
        List<Operation> operations = rule.getOperations();
        if (operations == null || operations.isEmpty())
        {
            return;
        }
        Map<String, Integer> declaredAt = new HashMap<>();
        for (int i = 0; i < operations.size(); i++)
        {
            String id = operations.get(i).getId();
            if (id != null)
            {
                declaredAt.putIfAbsent(id, i);
            }
        }
        for (int i = 0; i < operations.size(); i++)
        {
            Operation op = operations.get(i);
            for (String ref : referencedBindings(op))
            {
                Integer target = declaredAt.get(ref);
                if (target != null && target >= i)
                {
                    find(StageAErrorKind.FORWARD_OR_CYCLIC_BINDING,
                            "binding " + (op.getId() == null ? "#" + i : op.getId())
                                    + " references " + ref
                                    + (target == i ? " (itself)" : ", which is declared later"));
                }
            }
        }
    }


    /** The {@code $}-references one binding makes, from its expression and its name fields. */
    private static Set<String> referencedBindings(Operation op)
    {
        Set<String> refs = new LinkedHashSet<>();
        String expression = op.getExpression();
        if (expression != null)
        {
            try
            {
                collectOperationRefs(CheckExpressionParser.parse(expression), refs);
            }
            catch (ExpressionException ex)
            {
                // A malformed operation expression is reported on the loader's own channel.
                LOGGER.log(System.Logger.Level.TRACE,
                        "skipping unparseable binding expression: {0}", ex.getMessage());
            }
        }
        addDollarRef(refs, op.getName());
        addDollarRef(refs, op.getReference());
        addDollarRef(refs, op.getKeyValue());
        List<String> group = op.getGroup();
        if (group != null)
        {
            group.forEach(g -> addDollarRef(refs, g));
        }
        List<String> value = op.getValue();
        if (value != null)
        {
            value.forEach(v -> addDollarRef(refs, v));
        }
        return refs;
    }


    private static void addDollarRef(Set<String> refs, @Nullable String candidate)
    {
        if (candidate != null && candidate.startsWith("$") && !candidate.startsWith("${"))
        {
            refs.add(candidate);
        }
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
     * dangling-qualifier arm); and the D62 unqualified-merged-column heuristic (observe-only by
     * ruling — D62a's three {@code SUPPAE} rules read {@code AESMIE} bare and work today).
     */
    private void checkMatchDatasets(java.util.Collection<Expr> roots)
    {
        List<MatchDataset> matches = rule.getMatchDatasets();
        checkMatchedFlags(roots, matches);
        // ⛔ Before the early return, not after: a rule with NO Match_Datasets at all and a dotted
        // operand is the *typical* shape of the authoring error this check exists for.
        checkDottedRefs(roots, matches);
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
            c.args().forEach(a -> checkFilterRefs(a, entryName));
            c.kwargs().values().forEach(a -> checkFilterRefs(a, entryName));
        }
        }
    }


    /**
     * The join-match-flag checks of phase 5b-J (spec §3.3, D88/D88e), per {@code _matched_}
     * reference: value position, no defining entry, a {@code Child: true} or keyless entry, or an
     * inner join. A flag whose qualifier matches no entry is silently deferred when any entry name
     * is still a template ({@code --} / {@code *} / {@code ${} / {@code &}) — this checker also
     * runs at package load, before specialisation binds those names, and the specialised
     * per-dataset pass re-checks with concrete names.
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
            MatchDataset entry = entries.stream().filter(m -> qualifier.equals(m.getName()))
                    .findFirst().orElse(null);
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
     */
    private void checkDottedRefs(java.util.Collection<Expr> roots,
            @Nullable List<MatchDataset> matches)
    {
        Set<String> dotted = new LinkedHashSet<>();
        for (Expr root : roots)
        {
            collectDottedRefs(root, dotted);
        }
        collectBindingDottedRefs(dotted);
        if (dotted.isEmpty() || anyTemplateEntryName(matches))
        {
            return;
        }
        List<MatchDataset> entries = matches == null ? List.of() : matches;
        for (String ref : dotted)
        {
            String qualifier = ref.substring(0, ref.indexOf('.'));
            if (entries.stream().anyMatch(m -> qualifier.equals(m.getName())))
            {
                continue;
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
     * Adds the dotted references of the rule's {@code Bindings} expressions to {@code dotted}. An
     * unparseable expression is skipped for the same reason {@link #referencedBindings} skips one:
     * the loader has its own channel for that, and a parser failure must not become a qualifier
     * finding.
     *
     * @param dotted
     *            the accumulating set of dotted operand names
     */
    private void collectBindingDottedRefs(Set<String> dotted)
    {
        List<Operation> operations = rule.getOperations();
        if (operations == null)
        {
            return;
        }
        for (Operation op : operations)
        {
            String expression = op.getExpression();
            if (expression == null)
            {
                continue;
            }
            try
            {
                collectDottedRefs(CheckExpressionParser.parse(expression), dotted);
            }
            catch (ExpressionException ex)
            {
                LOGGER.log(System.Logger.Level.TRACE,
                        "skipping unparseable binding expression: {0}", ex.getMessage());
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
    private static void collectDottedRefs(Expr e, Set<String> dotted)
    {
        switch (e)
        {
        case Expr.And a -> a.parts().forEach(p -> collectDottedRefs(p, dotted));
        case Expr.Or o -> o.parts().forEach(p -> collectDottedRefs(p, dotted));
        case Expr.Not n -> collectDottedRefs(n.inner(), dotted);
        case Expr.Binary b ->
        {
            collectDottedRefs(b.left(), dotted);
            collectDottedRefs(b.right(), dotted);
        }
        case Expr.Lit lit ->
        {
            if (lit.kind() == Expr.LitKind.LIST && lit.value() instanceof List<?> items)
            {
                for (Object item : items)
                {
                    if (item instanceof Expr inner)
                    {
                        collectDottedRefs(inner, dotted);
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
            c.args().forEach(a -> collectDottedRefs(a, dotted));
            c.kwargs().values().forEach(a -> collectDottedRefs(a, dotted));
        }
        }
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
        if (e instanceof Expr.Ref ref && "variable_name".equals(ref.name()))
        {
            return true;
        }
        return e instanceof Expr.Call c && "varname".equals(c.name()) && c.args().isEmpty()
                && c.kwargs().isEmpty();
    }

}
