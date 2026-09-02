package net.cumba.cdisc.core.exec;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import lombok.Builder;
import lombok.Value;
import net.cumba.cdisc.core.metadata.RuntimeDictionaryProvider;
import net.cumba.cdisc.core.metadata.VlmResolver;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.report.Severity;
import org.jspecify.annotations.Nullable;

// @Builder.Default's generated all-args constructor trips NullAway's init check (NullAway#917);
// Lombok's @Value still enforces the field set at build() time.
@SuppressWarnings("NullAway.Init")
@Value
@Builder(toBuilder = true)
public class EvaluationContext
{

    IDataTable table;

    /**
     * Per-rule findings cap for this execution: the maximum number of {@link Violation}s the rule
     * materialises before truncating (the true count is still tracked — see {@link ViolationSink}).
     * Defaults to {@link Integer#MAX_VALUE} (unlimited) so contexts built outside the
     * rule-execution path — partial-evaluation probes, tests — are never bounded.
     */
    @Builder.Default
    int maxErrorsPerRule = Integer.MAX_VALUE;

    @Builder.Default
    Map<String, Object> variables = Map.of();

    @Builder.Default
    DatasetResolver datasetResolver = name -> null;

    /**
     * The CORE id (or fallback rule id) of the rule currently being evaluated. Used purely for
     * diagnostic logging — every WARN / INFO emitted from the evaluation pipeline should prefix
     * with {@code [<ruleId>]} so the user can trace the offending rule. May be {@code null} for
     * synthetic / probe contexts (e.g. partial-evaluation contexts built outside rule execution).
     */
    @Nullable
    String ruleId;

    /**
     * The dataset's CDISC domain code (e.g. {@code "AE"}, {@code "SUPPDM"}, {@code "APMH"}), used
     * to substitute {@code --} in an Operation's {@code domain:} — a <em>dataset-name</em> wildcard
     * (Fix #59, Fix #33). <b>Not</b> the variable-name replacement: see
     * {@link #variableWildcardPrefix} and EC-36.
     */
    @Nullable
    String domainPrefix;

    /**
     * The prefix that substitutes {@code --} in a <em>variable name</em>, from
     * {@code OperationExecutor.variableWildcardPrefix} — Python's {@code wildcard_replacement}.
     * Differs from {@link #domainPrefix} only for AP datasets (the 2-character parent suffix,
     * because {@code APMH} holds {@code MHTERM}) and SUPP/SQ datasets ({@code ""}, because
     * {@code --QNAM} is {@code QNAM}).
     * <p>
     * Derived from the caller-supplied domain code, so for every dataset outside those two families
     * it simply equals {@link #domainPrefix}. {@code null} only when there is no domain code at all
     * (a degraded or synthetic context); callers then substitute nothing.
     * </p>
     */
    @Nullable
    String variableWildcardPrefix;

    /** The domain name (e.g., "AE", "SUPPDM") of the table being evaluated. */
    @Nullable
    String domainName;

    /** The observation class (e.g., "EVENTS", "SPECIAL PURPOSE"). */
    @Nullable
    String className;

    /**
     * Joined datasets from {@code Match_Datasets}. Keyed by dataset name, each value is a lookup
     * map from join-key to column-value maps.
     */
    @Builder.Default
    Map<String, JoinLookup> joinedDatasets = Map.of();

    /**
     * Optional CDISC Library metadata provider for Library-dependent Operations. May be
     * {@code null} if Library access is not available.
     */
    @Nullable
    MetadataProvider libraryProvider;

    /**
     * Optional per-dataset cross-rule expression-result cache
     * ({@code plans/done/PLAN-dataset-expression-cache.md}). Created once per dataset and shared
     * (thread-safe) across the dataset's rule executions; {@code null} disables caching (tests /
     * embedding / partial-evaluation probes). Mirrors {@link #libraryProvider}: a plain
     * {@code @Nullable} field, no {@code @Builder.Default}.
     */
    @Nullable
    ExpressionResultCache exprCache;

    /**
     * Optional sponsor Define-XML metadata provider — the "define" level of the three-level
     * metadata model (data / define / library). May be {@code null} when no Define-XML is present.
     * Carried here for the {@code define_*} operand family; consumed by the metadata-check
     * evaluation path.
     */
    @Nullable
    MetadataProvider defineProvider;

    /**
     * Optional per-record Define-XML value-level metadata resolver — the "VLM" surface consumed by
     * the {@code vlm_*(varname())} accessors ({@code Value Check against Define XML VLM} rule
     * type). For a given (domain, variable, record) it selects the applicable value-level
     * {@code ItemDef} by evaluating the linked {@code WhereClauseDef} predicate against the row.
     * Built alongside {@link #defineProvider} from the same parsed Define-XML; {@code null} when no
     * Define-XML is present (VLM rules then SKIP via the
     * {@link net.cumba.cdisc.core.expr.eval.MetadataLevel#DEFINE} provider gate, exactly like
     * {@code defineProvider == null}).
     */
    @Nullable
    VlmResolver vlmResolver;

    /**
     * T1 — the runtime external-dictionary provider (MedDRA / WHODrug / LOINC / UNII / SNOMED /
     * NEOPLASM value-maps), consulted by the {@code valid_external_dictionary_*} operations and the
     * {@code dictionary_available} skip-gate. {@code null} when no dictionaries are supplied —
     * every dictionary-dependent rule then SKIPs, never false-passing: a declared ({@code $}-ref)
     * operation through {@link RuleRunner}'s eager dictionary arm ({@code Fix #268}), an inlined
     * one through the {@code dictionary_available(<type>)} precondition the converter injects for
     * it. The same holds for a provider that is present but does not hold the rule's own
     * {@code external_dictionary_type}.
     */
    @Nullable
    RuntimeDictionaryProvider dictionaryProvider;

    /**
     * The evaluation domain of the rule being evaluated ({@code Rule.evaluationDomain}) — what the
     * Check varies over. Read by the output-value projection: a domain with no row cursor has no
     * per-row context, so a dotted output variable reports its own identifier.
     */
    net.cumba.cdisc.core.expr.eval.@Nullable Domain evaluationDomain;

    /**
     * {@code Fix #222} (step 3 of {@code plans/PLAN-absent-required-dataset-skip.md}) — the
     * dependency-scoped rewrite of the rule's Check for <em>this</em> (rule, dataset) execution,
     * with every boolean leaf that reads an absent-and-already-reported foreign dataset folded to
     * {@code false}. Non-null only when {@link AbsentDatasetSkip#decide} actually suppressed
     * something and the rule did not collapse entirely (a collapse returns {@code SKIPPED} before a
     * context is ever built).
     *
     * <p>
     * It lives on the <em>context</em> rather than on the {@link net.cumba.cdisc.core.model.Rule}
     * because a Rule instance is shared across datasets and across the parallel dataset fan-out:
     * which datasets are absent is a property of the run's data, not of the rule, and mutating the
     * rule would race. Every {@code checkExpr} read in {@code RuleRunner} goes through
     * {@code RuleRunner.checkExprOf(rule, ctx)}, which prefers this override.
     * </p>
     */
    net.cumba.cdisc.core.expr.ast.@Nullable Expr checkExprOverride;

    /**
     * The run's <b>severity threshold</b> (Plan C &#167;3.4, ruling 4): the weakest rung this run
     * evaluates. A rule's declared levels below it are not evaluated at all, and a rule whose every
     * declared level is below it reports {@code SKIPPED} with that reason — never {@code EXECUTED}
     * with zero violations.
     *
     * <p>
     * <b>Default {@link Severity#WARNING}</b>, so a default run evaluates {@code REJECT},
     * {@code ERROR} and {@code WARNING} and excludes {@code INFO}. {@code INFO} is the "a reviewer
     * should look at this" rung; turning it on corpus-wide by default would be a finding-mover
     * disguised as a default.
     * </p>
     *
     * <p>
     * &#9873; It is a <b>run</b> option and nothing else — the CLI's {@code --severity-level}, the
     * REST {@code CheckRunRequest} field and the {@code .cdt} {@code #runLevel} directive all set
     * this one value. No rule package and no rule may carry one ({@code RulePackageLoader} rejects
     * both), because a rule declares which levels <em>exist</em>, never which levels <em>run</em>.
     * </p>
     */
    @Builder.Default
    Severity severityThreshold = EngineLimits.DEFAULT_SEVERITY_THRESHOLD;

    /**
     * The rung currently being evaluated, for a <b>multi-level</b> rule only — {@code null} for
     * every single-level rule, i.e. the entire shipped corpus, whose execution reads the rule's own
     * fields exactly as it did before per-level Checks existed.
     *
     * <p>
     * It lives on the context, not the rule, for the same reason {@link #checkExprOverride} does: a
     * {@link net.cumba.cdisc.core.model.Rule} instance is shared across datasets and across the
     * parallel dataset fan-out, so which rung is running is a property of <em>this</em> execution.
     * </p>
     */
    @Nullable
    CheckLevelPlan levelPlan;

    public int rowCount()
    {
        return Math.toIntExact(table.getRowCount());
    }

    /**
     * EC-43: the columns this evaluation folded to all-missing because they are absent from the
     * dataset. Accumulated by {@code ExprCompiler.nameRefPlan} and drained by
     * {@code RuleRunner.logAbsentColumnFolds}, which emits <b>one</b> aggregated INFO line per
     * (rule, dataset) — never per evaluation or per row.
     *
     * <p>
     * It lives on the <em>context</em> rather than in the compiled plan for a load-bearing reason:
     * {@code NativeExprEvaluator} caches one {@code ExprProgram} per {@code Expr} in a static map
     * shared across datasets and across the cohort fan-out, so a latch captured in the plan's
     * closure would log the first dataset only <em>and</em> race. A context is per (rule, dataset),
     * which is exactly the granularity the log wants. The set is concurrent because the cohort
     * fan-out may evaluate rows on several threads against one context.
     * </p>
     */
    @Builder.Default
    Set<String> absentColumnFolds = ConcurrentHashMap.newKeySet();

    /** Records that {@code column} was absent and evaluated as all-missing (EC-43). */
    public void noteAbsentColumnFold(String column)
    {
        getAbsentColumnFolds().add(column);
    }


    /**
     * Resolves a single variable by id, transparently unwrapping {@link LazyValue} wrappers.
     * Operation results live in the variables map as {@code LazyValue<Object>} instances (Fix #36)
     * so a never-read Operation never runs. Use this method for single-key reads; enumeration loops
     * should keep using {@link #getVariables()} directly and unwrap per entry only when the entry's
     * type is actually examined, otherwise iterating the map forces every Operation prematurely.
     */
    public @Nullable Object resolveVariable(@Nullable String id)
    {
        // A null id names no variable. The guard is load-bearing: `variables` defaults to
        // Map.of(), and immutable maps throw NullPointerException on a null key instead of
        // returning null like a HashMap. Callers legitimately pass null (an unresolved cursor
        // has no name), so probing with null must be a miss, not a crash.
        if (id == null)
        {
            return null;
        }
        Object raw = getVariables().get(id);
        if (raw instanceof LazyValue<?> lv)
        {
            return lv.get();
        }
        return raw;
    }

}
