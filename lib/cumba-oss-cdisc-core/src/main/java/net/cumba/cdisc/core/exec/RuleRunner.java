package net.cumba.cdisc.core.exec;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import lombok.CustomLog;
import net.cumba.cdisc.core.expr.eval.ExprCompiler;
import net.cumba.cdisc.core.expr.eval.MetadataNormalizer;
import net.cumba.cdisc.core.expr.eval.MetadataNormalizer.Normalization;
import net.cumba.cdisc.core.metadata.RuntimeDictionaryProvider;
import net.cumba.cdisc.core.metadata.VlmResolver;
import net.cumba.cdisc.core.model.CheckCondition;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionAny;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.CheckConditionNot;
import net.cumba.cdisc.core.model.LevelCheck;
import net.cumba.cdisc.core.model.MatchDataset;
import net.cumba.cdisc.core.model.OperationType;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.Sensitivity;
import net.cumba.datatable.DataTableColumnMeta;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

@CustomLog
public final class RuleRunner
{

    private static final String RELREC = "RELREC";

    /**
     * EC-12: compiled column-match {@link Pattern} per {@code ${*}} Output_Variables template.
     * Keyed by the template string so the same Pattern object reaches
     * {@link WildcardForeignColumnCache} across rows (that cache is keyed on Pattern identity),
     * making the foreign-column enumeration effectively once-per-execution rather than per-row.
     */
    private static final ConcurrentHashMap<String, Pattern> OV_WILDCARD_PATTERNS = new ConcurrentHashMap<>();

    private static final String VARIABLE_NAME = "variable_name";

    private static final String VARIABLE_LABEL = "variable_label";

    private RuleRunner()
    {
    }


    public static @Nullable RuleExecutionResult execute(Rule rule, IDataTable table)
    {
        return execute(rule, table, _ -> null);
    }


    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver)
    {
        return execute(rule, table, resolver, null);
    }


    /**
     * Executes a rule against a data table.
     *
     * @param rule
     *            the rule to execute
     * @param table
     *            the data table to check
     * @param resolver
     *            resolves cross-dataset references
     * @param domainPrefix
     *            the 2-character domain prefix for {@code --} substitution (e.g., "AE"). May be
     *            {@code null} if the rule does not use prefix wildcards.
     * @return the execution result
     */
    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix)
    {
        return execute(rule, table, resolver, domainPrefix, (MetadataProvider) null);
    }


    /**
     * Executes a rule against a data table with configurable literal fallback and an optional
     * {@link MetadataProvider} for rules that depend on CDISC Library metadata (e.g., operations
     * {@code domain_is_custom}, {@code required_variables}, {@code expected_variables},
     * {@code get_column_order_from_library}, {@code codelist_terms}).
     *
     * @param rule
     *            the rule to execute
     * @param table
     *            the data table to check
     * @param resolver
     *            resolves cross-dataset references
     * @param domainPrefix
     *            the 2-character domain prefix for {@code --} substitution (e.g., "AE"). May be
     *            {@code null}.
     * @param libraryProvider
     *            provides CDISC Library metadata for Library-dependent operations. When
     *            {@code null}, rules that use such operations are skipped with a warning.
     * @return the execution result
     */
    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, @Nullable MetadataProvider libraryProvider)
    {
        return execute(rule, table, resolver, domainPrefix, libraryProvider, null);
    }


    /**
     * Executes a rule with a {@link JoinCache} for reusing cross-dataset join structures across
     * multiple rule executions against the same primary table. When many rules join the same
     * reference dataset (e.g., DM by USUBJID), the join index and row map are built once and reused
     * for all subsequent rules.
     * <p>
     * <strong>Thread-safety contract:</strong> {@code rule}, {@code table}, {@code resolver},
     * {@code libraryProvider} and {@code joinCache} are all read-only during execution and may be
     * shared across concurrent invocations on different threads. The {@link Rule} model objects
     * (and nested {@link net.cumba.cdisc.core.model.Operation},
     * {@link net.cumba.cdisc.core.model.CheckCondition}, …) are Lombok {@code @Data} beans with
     * setters for deserialisation; do <em>not</em> mutate any field on a {@code Rule} after it has
     * been handed to {@code execute}, or thread safety is lost.
     *
     * @param rule
     *            the rule to execute
     * @param table
     *            the data table to check
     * @param resolver
     *            resolves cross-dataset references
     * @param domainPrefix
     *            the 2-character domain prefix for {@code --} substitution (e.g., "AE"). May be
     *            {@code null}.
     * @param libraryProvider
     *            provides CDISC Library metadata for Library-dependent operations. When
     *            {@code null}, rules that use such operations are skipped with a warning.
     * @param joinCache
     *            optional cache for reusing join structures across rule executions. When
     *            {@code null}, join structures are built fresh for each rule.
     * @return the execution result
     */
    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, @Nullable MetadataProvider libraryProvider,
            @Nullable JoinCache joinCache)
    {
        return execute(rule, table, resolver, domainPrefix, libraryProvider, joinCache, null);
    }


    /**
     * As {@link #execute(Rule, IDataTable, DatasetResolver, String, MetadataProvider, JoinCache)}
     * with an additional sponsor Define-XML metadata provider — the "define" level of the
     * three-level metadata model. {@code defineProvider} is {@code null} when no Define-XML is
     * available; it is carried read-only on the {@link EvaluationContext} for the {@code define_*}
     * operand family.
     *
     * @param defineProvider
     *            sponsor Define-XML metadata, or {@code null} when no define is present
     * @return the execution result
     */
    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, @Nullable MetadataProvider libraryProvider,
            @Nullable JoinCache joinCache, @Nullable MetadataProvider defineProvider)
    {
        return execute(rule, table, resolver, domainPrefix, libraryProvider, joinCache,
                defineProvider, Integer.MAX_VALUE);
    }


    /**
     * As
     * {@link #execute(Rule, IDataTable, DatasetResolver, String, MetadataProvider, JoinCache, MetadataProvider)}
     * with an explicit per-rule findings cap. At most {@code maxErrorsPerRule} violations are
     * materialised into the result; the true total is still reported via
     * {@link RuleExecutionResult#getViolationCount()} and surfaced as truncated when it exceeds the
     * cap. {@link Integer#MAX_VALUE} means unlimited. See {@link EngineLimits} /
     * {@link ViolationSink}.
     *
     * @param maxErrorsPerRule
     *            maximum findings to materialise for this rule on this dataset
     * @return the execution result
     */
    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, @Nullable MetadataProvider libraryProvider,
            @Nullable JoinCache joinCache, @Nullable MetadataProvider defineProvider,
            int maxErrorsPerRule)
    {
        return execute(rule, table, resolver, domainPrefix, libraryProvider, joinCache,
                defineProvider, maxErrorsPerRule, null);
    }


    /**
     * As
     * {@link #execute(Rule, IDataTable, DatasetResolver, String, MetadataProvider, JoinCache, MetadataProvider, int)}
     * with the per-dataset {@link ExpressionResultCache}
     * ({@code plans/done/PLAN-dataset-expression-cache.md}), threaded onto the
     * {@link EvaluationContext} so pure expression leaves can be reused across the dataset's rules.
     * {@code null} disables caching (every lookup falls through to compute) and is
     * behaviour-identical to the prior path.
     *
     * @param exprCache
     *            the per-dataset expression-result cache, or {@code null} to disable caching
     * @return the execution result
     */
    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, @Nullable MetadataProvider libraryProvider,
            @Nullable JoinCache joinCache, @Nullable MetadataProvider defineProvider,
            int maxErrorsPerRule, @Nullable ExpressionResultCache exprCache)
    {
        return execute(rule, table, resolver, domainPrefix, libraryProvider, joinCache,
                defineProvider, maxErrorsPerRule, exprCache, null);
    }


    /**
     * As
     * {@link #execute(Rule, IDataTable, DatasetResolver, String, MetadataProvider, JoinCache, MetadataProvider)}
     * with the per-record Define-XML value-level metadata resolver ({@code Value Check against
     * Define XML VLM}). {@code vlmResolver} is {@code null} when no Define-XML value-level metadata
     * is present; it is carried read-only on the {@link EvaluationContext} for the {@code vlm_*}
     * accessors. Convenience overload for tests / embedding (unlimited findings, no caches).
     *
     * @param vlmResolver
     *            the per-record value-level metadata resolver, or {@code null}
     * @return the execution result
     */
    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, @Nullable MetadataProvider libraryProvider,
            @Nullable JoinCache joinCache, @Nullable MetadataProvider defineProvider,
            @Nullable VlmResolver vlmResolver)
    {
        return execute(rule, table, resolver, domainPrefix, libraryProvider, joinCache,
                defineProvider, Integer.MAX_VALUE, null, null, vlmResolver);
    }


    /**
     * T1 overload carrying the runtime
     * {@link net.cumba.cdisc.core.metadata.RuntimeDictionaryProvider} consulted by the
     * {@code valid_external_dictionary_*} operations and the {@code dictionary_available}
     * skip-gate. {@code null} on every non-dictionary path.
     *
     * @param dictionaryProvider
     *            the external-dictionary provider, or {@code null} when no dictionaries are
     *            supplied
     * @return the execution result
     */
    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, @Nullable MetadataProvider libraryProvider,
            @Nullable JoinCache joinCache, @Nullable MetadataProvider defineProvider,
            int maxErrorsPerRule, @Nullable ExpressionResultCache exprCache,
            @Nullable RuntimeDictionaryProvider dictionaryProvider)
    {
        return execute(rule, table, resolver, domainPrefix, libraryProvider, joinCache,
                defineProvider, maxErrorsPerRule, exprCache, dictionaryProvider, null);
    }


    /**
     * Terminal {@code execute} overload additionally carrying the per-record Define-XML value-level
     * metadata resolver ({@code vlmResolver}) — the "VLM" surface for the {@code vlm_*} accessors
     * ({@code Value Check against Define XML VLM} rule type). {@code null} on every non-VLM path.
     *
     * @param vlmResolver
     *            the per-record value-level metadata resolver, or {@code null}
     * @return the execution result
     */
    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, @Nullable MetadataProvider libraryProvider,
            @Nullable JoinCache joinCache, @Nullable MetadataProvider defineProvider,
            int maxErrorsPerRule, @Nullable ExpressionResultCache exprCache,
            @Nullable RuntimeDictionaryProvider dictionaryProvider,
            @Nullable VlmResolver vlmResolver)
    {
        return execute(rule, table, resolver, domainPrefix, libraryProvider, joinCache,
                defineProvider, maxErrorsPerRule, exprCache, dictionaryProvider, vlmResolver,
                Set.of());
    }


    /**
     * Terminal {@code execute} overload additionally carrying the run's <b>dataset-presence
     * coverage</b> — {@code Fix #222}, step 3 of
     * {@code plans/PLAN-absent-required-dataset-skip.md}.
     *
     * <p>
     * {@code reportedDatasets} names the datasets whose absence <em>this run already reports</em>,
     * derived mechanically by {@link AbsentDatasetSkip#reportedDatasets(java.util.Collection)} from
     * the run's own rule list (never a hard-coded table — {@code K5}). For a rule that reads such a
     * dataset while it is absent, the readings are suppressed rather than allowed to flood; when
     * that leaves the rule with nothing evaluable it reports {@link RuleExecutionStatus#SKIPPED}
     * instead of a silent pass. An empty set is behaviour-identical to the pre-{@code Fix #222}
     * engine.
     * </p>
     *
     * @param reportedDatasets
     *            upper-cased dataset names covered by a bare presence rule in this run
     * @return the execution result
     */
    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, @Nullable MetadataProvider libraryProvider,
            @Nullable JoinCache joinCache, @Nullable MetadataProvider defineProvider,
            int maxErrorsPerRule, @Nullable ExpressionResultCache exprCache,
            @Nullable RuntimeDictionaryProvider dictionaryProvider,
            @Nullable VlmResolver vlmResolver, Set<String> reportedDatasets)
    {
        return execute(rule, table, resolver, domainPrefix, libraryProvider, joinCache,
                defineProvider, maxErrorsPerRule, exprCache, dictionaryProvider, vlmResolver,
                reportedDatasets, Set.of());
    }


    /**
     * Stamps the rule's effective severity onto a finished result, unless the execution already
     * resolved one.
     *
     * <p>
     * ⚑ Applied at the single terminal {@code execute} overload rather than at the 26
     * {@code RuleExecutionResult.builder()} sites inside this class — every public entry funnels
     * through here, so one stamp covers them all and no future builder site can forget it. In phase
     * 2 the value is simply {@code rule.effectiveSeverity()}; per-level claiming (phase 4) will set
     * {@link Violation#getLevel()} per row, which wins over this rule-level value.
     * </p>
     */
    private static RuleExecutionResult stampSeverity(Rule rule, RuleExecutionResult result)
    {
        return result.getSeverity() != null ? result
                : result.toBuilder().severity(rule.effectiveSeverity()).build();
    }


    /**
     * Terminal {@code execute} overload additionally carrying the run's <b>cross-standard
     * coverage</b> — {@code Fix #218}, {@code plans/PLAN-cross-standard-absence-skip.md}.
     *
     * <p>
     * {@code crossStandardDatasets} names the datasets belonging to a CDISC standard this run does
     * <b>not</b> validate — on an ADaM-family run, the companion SDTM domain catalogue. A rule
     * whose whole Check depends on such a dataset while it was <em>not supplied to the run at
     * all</em> reports {@link RuleExecutionStatus#SKIPPED} rather than the vacuous PASS it produced
     * before. ⚠ The absence test is {@code resolver.resolve(D) == null} and never a target-ness
     * test: a co-located SDTM dataset supplied as a <em>reference</em> resolves, and must keep the
     * rule running. An empty set is behaviour-identical to the pre-{@code Fix #218} engine.
     * </p>
     *
     * @param crossStandardDatasets
     *            upper-cased dataset names belonging to a standard this run does not validate
     * @return the execution result
     */
    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, @Nullable MetadataProvider libraryProvider,
            @Nullable JoinCache joinCache, @Nullable MetadataProvider defineProvider,
            int maxErrorsPerRule, @Nullable ExpressionResultCache exprCache,
            @Nullable RuntimeDictionaryProvider dictionaryProvider,
            @Nullable VlmResolver vlmResolver, Set<String> reportedDatasets,
            Set<String> crossStandardDatasets)
    {
        return execute(rule, table, resolver, domainPrefix, libraryProvider, joinCache,
                defineProvider, maxErrorsPerRule, exprCache, dictionaryProvider, vlmResolver,
                reportedDatasets, crossStandardDatasets, EngineLimits.DEFAULT_SEVERITY_THRESHOLD);
    }


    /**
     * The terminal {@code execute} overload, additionally carrying the run's <b>severity
     * threshold</b> — Plan C &#167;3.4, ruling 4.
     *
     * <p>
     * The threshold is the weakest rung this run evaluates. A rule's declared check levels below it
     * are not evaluated at all; a rule whose <em>every</em> declared level is below it reports
     * {@link RuleExecutionStatus#SKIPPED} with that reason, never {@code EXECUTED} with zero
     * violations — the difference between "this rule had nothing to say" and "this rule was not
     * asked".
     * </p>
     *
     * <p>
     * &#9873; It is a <b>run</b> option and nothing else: the CLI's {@code --severity-level}, the
     * REST {@code CheckRunRequest} field and the {@code .cdt} {@code #runLevel} directive all set
     * this one value, and {@code RulePackageLoader} rejects a package or rule that tries to carry
     * one.
     * </p>
     *
     * <p>
     * &#9940;&#9940; <b>The shipped corpus is NOT threshold-invariant</b> — this paragraph asserted
     * the opposite until 2026-08-26, when it was measured. The default is
     * {@link EngineLimits#DEFAULT_SEVERITY_THRESHOLD} = {@code WARNING}, and Plan C phase 5b
     * authored <b>9</b> level-keyed rules ({@code CDISC-CG0078}, {@code CDISC-CG0218},
     * {@code CDISC-CG0233}, {@code CDISC-CG0235}, {@code CDISC-SEND-0283}, {@code CORE-000097},
     * {@code CORE-000250}, {@code CORE-000572}, {@code CORE-000710}) whose weaker rung is
     * {@code INFO}. They expand to <b>33</b> shipped rule records, so a default run drops <b>33
     * declared rungs</b> that an {@code Info} run evaluates. That is exactly &#167;3.4's specified
     * behaviour and the deliberate reason {@code INFO} is out of the default (turning it on
     * corpus-wide would be a finding-mover disguised as a default) &mdash; but any instrument that
     * must see every declared level has to say so: {@code FindingsSnapshot} pins
     * {@code severityThreshold = Info} for precisely this reason, and records it in its banner.
     * </p>
     *
     * @param severityThreshold
     *            the weakest rung to evaluate
     * @return the execution result
     */
    public static RuleExecutionResult execute(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, @Nullable MetadataProvider libraryProvider,
            @Nullable JoinCache joinCache, @Nullable MetadataProvider defineProvider,
            int maxErrorsPerRule, @Nullable ExpressionResultCache exprCache,
            @Nullable RuntimeDictionaryProvider dictionaryProvider,
            @Nullable VlmResolver vlmResolver, Set<String> reportedDatasets,
            Set<String> crossStandardDatasets, Severity severityThreshold)
    {
        try
        {
            return stampSeverity(rule,
                    executeResolved(rule, table, resolver, domainPrefix, libraryProvider, joinCache,
                            defineProvider, maxErrorsPerRule, exprCache, dictionaryProvider,
                            vlmResolver, reportedDatasets, crossStandardDatasets,
                            severityThreshold));
        }
        catch (InvalidJoinedDomainException e)
        {
            // Fix #358 (ruling 1): a Match_Datasets name / RDOMAIN value resolved to a split
            // domain whose members cannot be unioned (e.g. a column type clash). A malformed
            // split is a submission defect the sponsor must see, so the rule reports ERROR with
            // the same "__error__" sentinel shape as the unsupported RELREC×key-expansion
            // combination — never a silent skip, never a coerced union. The throwing resolution
            // sites are spread across the whole execution body (preMerge, the RELREC / key-match
            // expanders, buildJoinedDatasets, and the Check-side dotted resolvers), which is why
            // the catch wraps the body rather than any one block.
            String errorMsg = String.valueOf(e.getMessage());
            LOGGER.log(System.Logger.Level.WARNING, "[{0}] {1}",
                    rule.effectiveId() != null ? rule.effectiveId() : "?", errorMsg);
            Violation sentinel = new Violation(0, Map.of("__error__", errorMsg));
            return stampSeverity(rule, RuleExecutionResult.builder().ruleId(rule.effectiveId())
                    .message(rule.getOutcome() != null ? rule.getOutcome().getMessage() : null)
                    .violations(List.of(sentinel))
                    .totalRows(table != null ? table.getRowCount() : 0L)
                    .status(RuleExecutionStatus.ERROR).statusMessage(errorMsg).build());
        }
    }


    /**
     * The rule-execution body — everything behind the {@link InvalidJoinedDomainException} →
     * {@code ERROR} mapping of the terminal {@code execute} overload above (Fix #358).
     */
    private static RuleExecutionResult executeResolved(Rule rule, IDataTable table,
            DatasetResolver resolver, @Nullable String domainPrefix,
            @Nullable MetadataProvider libraryProvider, @Nullable JoinCache joinCache,
            @Nullable MetadataProvider defineProvider, int maxErrorsPerRule,
            @Nullable ExpressionResultCache exprCache,
            @Nullable RuntimeDictionaryProvider dictionaryProvider,
            @Nullable VlmResolver vlmResolver, Set<String> reportedDatasets,
            Set<String> crossStandardDatasets, Severity severityThreshold)
    {
        String ruleId = rule.effectiveId();
        String message = rule.getOutcome() != null ? rule.getOutcome().getMessage() : null;

        // Fix #37: invalid-rule sentinel. RulePackageLoader walks every leaf at load-time and
        // tags malformed operand-substitution syntax / off-diagonal operators on the rule. When
        // the tag is present, return a single sentinel violation carrying the message so the
        // user-visible report surfaces the load-time problem without ever evaluating data.
        if (rule.getLoadError() != null)
        {
            Violation sentinel = new Violation(0, Map.of("__error__", rule.getLoadError()));
            return RuleExecutionResult.builder().ruleId(ruleId).message(message)
                    .violations(List.of(sentinel))
                    .totalRows(table != null ? table.getRowCount() : 0L)
                    .status(RuleExecutionStatus.ERROR).statusMessage(rule.getLoadError()).build();
        }

        if (rule.getCheck() == null)
        {
            return RuleExecutionResult.builder().ruleId(ruleId).message(message)
                    .violations(List.of()).totalRows(table.getRowCount()).build();
        }

        // Plan C §3.4 step 2 — the run's severity threshold. A rule whose EVERY declared level is
        // below it was not asked, so it reports SKIPPED with a stated reason rather than EXECUTED
        // with zero violations: a rule that reports PASS when it was never evaluated is a false
        // assurance, the same argument the absent-dataset and provider gates below already make.
        // Decided here, before any of the machinery, because it is a property of (rule × run)
        // alone — no dataset, no join and no Operation can change it. ⚑ Vacuous at the default
        // threshold: every level any shipped rule declares is at or above WARNING.
        SequencedMap<Severity, LevelCheck> declaredLevels = rule.effectiveCheckLevels();
        List<Severity> runnableLevels = declaredLevels.keySet().stream()
                .filter(level -> level.compareTo(severityThreshold) <= 0).toList();
        if (runnableLevels.isEmpty())
        {
            String reason = "Rule skipped — every declared check level ("
                    + declaredLevels.keySet().stream().map(Severity::getJsonValue)
                            .collect(java.util.stream.Collectors.joining(", "))
                    + ") is below the run severity threshold " + severityThreshold.getJsonValue();
            LOGGER.log(System.Logger.Level.DEBUG, "[{0}] {1}", ruleId != null ? ruleId : "?",
                    reason);
            return RuleExecutionResult.builder().ruleId(ruleId).message(message)
                    .violations(List.of()).totalRows(table.getRowCount())
                    .status(RuleExecutionStatus.SKIPPED).statusMessage(reason).build();
        }

        // Variable-scope gate (Scope.Variables). A rule whose Scope.Variables.Include names a
        // column absent from this dataset — or whose Exclude names one present — is not applicable
        // here: report SKIPPED naming the responsible variable, so an absent *required* column
        // surfaces as "skipped" rather than a silent no-finding. This is the runtime counterpart of
        // the generation-time filter in RuleGenerator and intentionally mirrors the existing
        // metadata/precondition SKIPPED gates below. The primary (pre-merge) `table` metadata is
        // used so a foreign column pulled in by a later Match_Datasets merge cannot falsely satisfy
        // an Include; `domainPrefix` resolves a leading `--` (e.g. --OCCUR -> AEOCCUR) per dataset.
        // Fix #124: a qualified entry (DM.ARM) additionally consults the FOREIGN dataset, so the
        // rule can declare "skip me when the dataset my Match_Datasets join / Operation domain
        // depends on does not carry this variable" — today that dependency fails silently (a
        // missing join is a DEBUG no-op) and the rule evaluates against nulls. The source is built
        // only when the rule actually carries a qualified entry, so the ~883 rules with an
        // unqualified variable scope pay nothing. It is null when the resolver cannot enumerate
        // datasets (see ScopeVariableSource.of), in which case qualified entries are ignored.
        ScopeVariableSource scopeForeign = null;
        if (ScopeMatcher.hasQualifiedVariableScope(rule))
        {
            scopeForeign = ScopeVariableSource.of(resolver, table);
            if (scopeForeign == null)
            {
                LOGGER.log(System.Logger.Level.DEBUG,
                        "[{0}] qualified Scope.Variables entry ignored — the dataset resolver"
                                + " cannot enumerate other datasets",
                        ruleId != null ? ruleId : "?");
            }
        }
        // EC-36: the prefix that substitutes `--` in a VARIABLE name — Python's
        // wildcard_replacement. Distinct from `domainPrefix`, which is the dataset's CDISC domain
        // code and stays the substitution for `--` in an Operation's `domain:` (Fix #59/#33) and
        // for the ds_domain("DATA") fact (formerly the injected DOMAIN value, Fix #10). They
        // differ only for AP datasets (APMH holds
        // MHTERM, not APMHTERM) and SUPP/SQ (--QNAM is QNAM).
        String varWildcardPrefix = OperationExecutor.variableWildcardPrefix(table, domainPrefix);

        // EC-36 / D2' — REMOVED after the 2026-07-29 code review. The gate skipped a rule when
        // the prefix was unresolvable, but its predicate (RuleWildcardUsage) was hand-written
        // rather than derived from the resolvers, so it was wrong in BOTH directions: a `--`
        // inside a data literal ("DOSE NOT CHANGED--SEE CRF") or a dot-qualified `RELREC.**DECOD`
        // made whole rules skip and DELETED genuine findings (CORE-000744, CDISC-CG0174,
        // CG0601-0603), while `within` / array-valued `value` / Grouping_Variables were invisible
        // to it. Now that the prefix is anchored to the caller-supplied domain code it is null
        // only when there is no domain at all — a degraded or synthetic context — where the
        // callers already substitute nothing, exactly as before EC-36. Re-introducing a skip is a
        // separate change and needs a predicate DERIVED from the resolvers; see the plan's §10.4.

        String variableScopeMismatch = ScopeMatcher.describeVariablesMismatch(rule,
                table.getMetaData(), varWildcardPrefix, scopeForeign);
        if (variableScopeMismatch != null)
        {
            return RuleExecutionResult.builder().ruleId(ruleId).message(message)
                    .violations(List.of()).totalRows(table.getRowCount())
                    .status(RuleExecutionStatus.SKIPPED)
                    .statusMessage("Rule skipped — " + variableScopeMismatch).build();
        }

        // Requirements.Datasets (PLAN-scope-requirements-split §4.4): a declared dataset the run
        // does not ship makes the rule unanswerable as a whole. Checked HERE — before
        // AbsentDatasetSkip.decide below — because that is the intended relationship: a declared
        // requirement says the rule cannot answer at all, which is strictly stronger than
        // "this branch cannot contribute", so the rule skips whole and never enters the
        // dependency-scoped suppression path.
        // ⚠ Order within this block: the variable requirement is judged first, so a rule failing
        // both keeps reporting the column reason it reports today. Order decides only which reason
        // a multiply-unmet rule names.
        String datasetRequirementMismatch = describeMissingRequiredDataset(rule, table, resolver);
        if (datasetRequirementMismatch != null)
        {
            return RuleExecutionResult.builder().ruleId(ruleId).message(message)
                    .violations(List.of()).totalRows(table.getRowCount())
                    .status(RuleExecutionStatus.SKIPPED)
                    .statusMessage("Rule skipped — " + datasetRequirementMismatch).build();
        }

        // Fix #117/#118/#119/#154: ADaM data-structure / subclass scope gates
        // (Scope.Data_Structures / Scope.Subclasses) — runtime counterpart of the generation-time
        // gate in RuleGenerator, so direct-execution paths (the .cdt ruletest harness, suites)
        // enforce them too. The detectors run only when the rule actually carries one of the
        // scopes. Fix #154 (decision D21-remainder) fixes the tier order as
        // 1. Define-XML declaration (def:Class / def:SubClass), if it folds to a token
        // 2. the metadata library's declaration, if the define has none
        // 3. local-only: ADSL-by-name, then column signals, then dataset name (last resort)
        // Tiers 1/2 beat tier 3 because corej.defineFirst now defaults to TRUE: reclassifying a
        // dataset by its columns HIDES a defect — a declaration that disagrees with the data is
        // itself a conformance finding, and an engine must report it rather than quietly
        // re-declare on the sponsor's behalf. -Dcorej.defineFirst=false restores columns-first.
        if (rule.getScope() != null && (rule.getScope().getDataStructures() != null
                || rule.getScope().getSubclasses() != null))
        {
            var meta = table.getMetaData();
            // Fix #179: the structure is a SET, most-specific first — [MEDICAL DEVICE BASIC DATA
            // STRUCTURE, BASIC DATA STRUCTURE] for a declared device BDS dataset. Include/Exclude
            // both match on any member, so a base-scoped rule keeps covering the specialisation.
            // ⚠ Derived through AdamStructureContext, NOT inline: OperationExecutor keys the ADaM
            // required/expected operations by the same set, and the gate and the operation must
            // not be able to disagree about what this dataset is.
            List<String> structures = AdamStructureContext.detectAll(meta, defineProvider,
                    libraryProvider);
            String structureMismatch = ScopeMatcher.describeDataStructureMismatch(rule, structures);
            if (structureMismatch == null)
            {
                // ⚠ Same reason on the subclass axis, and since Phase 3 of
                // PLAN-metadata-product-selection it is load-bearing twice over: this very set
                // now also selects which of a token's data structures governs the published
                // variable list, so the gate's verdict and the operation's lookup are literally
                // the same derivation (AdamStructureContext.detectSubclasses).
                structureMismatch = ScopeMatcher.describeSubclassMismatch(rule, AdamStructureContext
                        .detectSubclasses(meta, defineProvider, libraryProvider, structures));
            }
            if (structureMismatch != null)
            {
                return RuleExecutionResult.builder().ruleId(ruleId).message(message)
                        .violations(List.of()).totalRows(table.getRowCount())
                        .status(RuleExecutionStatus.SKIPPED)
                        .statusMessage("Rule skipped — " + structureMismatch).build();
            }
        }

        // Fix #222 (step 3 of PLAN-absent-required-dataset-skip): an absent foreign dataset whose
        // absence THIS RUN already reports must silence its dependants rather than flood. Decided
        // here — before the joins and Operations run — because a rule that collapses entirely has
        // no reason to pay for either. `reportedDatasets` is derived from the run's rule list, so
        // the guarantee cannot silently lapse when a package changes (K5); the absence fact tested
        // is `resolver.resolve(D) == null`, which is literally what the presence rule's
        // ds_exists(D) evaluates (OperatorRegistry.existsAsDataset), so SKIP engages exactly when
        // the presence rule fires. Fix #358 (D7) widens BOTH halves: a split domain
        // (lbch/lbhe/lbur, no standalone LB) counts as present for ds_exists AND — for the
        // Match_Datasets and dotted-Check candidates, whose readers now resolve the union — for
        // the skip predicate, so the lockstep holds on split submissions too. One stated
        // exception: an Operations[].domain-only candidate keeps the exact presence test
        // (operations still resolve exact — a Fix #358 non-goal); see AbsentDatasetSkip's class
        // Javadoc.
        //
        // ⚠ Dependency-scoped, NOT rule-scoped (K5b): only the boolean leaves that READ the absent
        // dataset are suppressed. `all[ any[ local , DM.x ] , local ]` keeps its local disjunct —
        // 25 rules / 27 (rule, dataset) pairs would otherwise lose purely-local findings, 18 of
        // the pairs on DM. Only when the whole Check collapses does the rule report SKIPPED, and
        // SKIPPED (not a silent pass) is the point: a rule that reports PASS when it could not be
        // evaluated is a false assurance.
        //
        // ⚠ Fix #218 widens the COVERAGE half only: a dependency on a dataset of a standard this
        // run did not receive is a SKIP reason too, and it must be, because a package-scoped
        // precondition structurally cannot express one (no ADaM package reports DM, and per the
        // owner's invocation ruling none may). The absence half is unchanged — `resolve(D) == null`
        // — so an SDTM dataset supplied as a REFERENCE keeps the rule running.
        AbsentDatasetSkip.Decision absentSkip = AbsentDatasetSkip.decide(rule, resolver,
                reportedDatasets, crossStandardDatasets, table.getMetaData().getName(),
                domainPrefix);
        // Plan C §3.4: on a MULTI-level rule the same decision is taken per level, against that
        // level's own expression. The absence facts are a property of the run's data — identical at
        // every level — but the dependency-scoped REWRITE folds only the leaves of the tree it is
        // given, and each level has its own tree. `levelSkips` is null for a single-level rule, so
        // everything below reads `absentSkip` exactly as it did before this plan.
        Map<Severity, AbsentDatasetSkip.Decision> levelSkips = null;
        if (rule.getCheckLevelExprs() != null)
        {
            levelSkips = new LinkedHashMap<>();
            for (Severity level : runnableLevels)
            {
                levelSkips.put(level,
                        AbsentDatasetSkip.decide(rule, levelExprOf(rule, level), resolver,
                                reportedDatasets, crossStandardDatasets,
                                table.getMetaData().getName(), domainPrefix));
            }
        }
        // The RULE skips only when every runnable level collapsed: a collapsed level is
        // constant-false and can claim nothing, but a surviving level still can. For a single-level
        // rule this is `absentSkip.collapsed()` verbatim, with `absentSkip`'s own skip reason.
        AbsentDatasetSkip.Decision collapsedDecision = levelSkips == null
                ? (absentSkip.collapsed() ? absentSkip : null)
                : allLevelsCollapsed(levelSkips);
        if (collapsedDecision != null)
        {
            LOGGER.log(System.Logger.Level.DEBUG, "[{0}] {1}", ruleId != null ? ruleId : "?",
                    collapsedDecision.skipReason());
            return RuleExecutionResult.builder().ruleId(ruleId).message(message)
                    .violations(List.of()).totalRows(table.getRowCount())
                    .status(RuleExecutionStatus.SKIPPED)
                    .statusMessage(collapsedDecision.skipReason()).build();
        }
        if (absentSkip.applies())
        {
            LOGGER.log(System.Logger.Level.DEBUG,
                    "[{0}] readings of absent, already-reported dataset(s) {1} suppressed; "
                            + "the remaining branches still evaluate",
                    ruleId != null ? ruleId : "?", absentSkip.suppressedDatasets());
        }

        // -----------------------------------------------------------------------
        // Phase ordering (see `core-engine-update-plan.md` §2 Cluster C):
        // 2a.5–2a.7 — ChildMatchPreMerger / RELREC / key-match row expansion produce the merged,
        // row-expanded `evalTable` (every later phase uses `evalTable`, not `table`). These run
        // BEFORE Operations, mirroring Python (preprocess precedes perform_rule_operations), so an
        // Operation aggregating over the primary sees the expanded row set.
        // 2a — Execute Operations (Fix #1 stashes `originalName` during resolveOperationPrefix)
        // 2b — Build Match_Datasets join lookups (Fix #7 multi-row, Fix #5 per-row RELREC)
        // 2c — CheckConditionTransformer resolves `--` prefixes (Fix #5 preserves `**`)
        // 2e — Evaluate Rule.Precondition (Fix #13 — currently deferred per OQ#4)
        // 3 — Check evaluation
        // -----------------------------------------------------------------------
        IDataTable evalTable = table;

        // Operand-based not-available gate (PLAN-coreJ-cdisc-provider). A rule whose Check
        // references define_* / library_* operands needs the matching provider. Operand-only rules
        // (no library-dependent Operation, e.g. CORE-001081) are not caught by the operation-based
        // probe below, so detect them here by scanning the Check tree and report SKIPPED when the
        // referenced level's provider is absent.
        // ⚑ Plan C §3.3: EVERY declared level. A define_*/library_* operand in a weaker level
        // needs its provider just as much as one in the strictest — reading getCheck() alone would
        // let the rule run with no provider and report a silent, false PASS.
        for (CheckCondition operandCheck : rule.checkConditions())
        {
            if (defineProvider == null && referencesOperandPrefix(operandCheck, "define_"))
            {
                return RuleExecutionResult.builder().ruleId(ruleId).message(message)
                        .violations(List.of()).totalRows(evalTable.getRowCount())
                        .status(RuleExecutionStatus.SKIPPED)
                        .statusMessage("Rule skipped — no Define-XML metadata "
                                + "(rule requires define_* operands)")
                        .build();
            }
            // Fix #369 — libraryAnswerable, not `== null`. A DEGRADED provider is non-null, so a
            // bare null-check let every library_* operand rule fall through and read the STUDY
            // library instead: `var_role("DEFINE") != var_role("LIBRARY")` then compares the define
            // against itself and reports SUCCESS. ⚠⚠ This surface is DISJOINT from the operation
            // surface the rest of Fix #369 gates — 30 corpus rules read library_* operands and
            // carry no library Operation at all, so nothing in OperationExecutor can see them.
            if (!OperationExecutor.libraryAnswerable(libraryProvider)
                    && referencesOperandPrefix(operandCheck, "library_"))
            {
                return RuleExecutionResult.builder().ruleId(ruleId).message(message)
                        .violations(List.of()).totalRows(evalTable.getRowCount())
                        .status(RuleExecutionStatus.SKIPPED)
                        .statusMessage(libraryProvider != null
                                ? "Rule skipped — the CDISC Library could not be consulted for this"
                                        + " run, and library_* operands may not be answered from a"
                                        + " non-library source"
                                : "Rule skipped — no Library metadata "
                                        + "(rule requires library_* operands)")
                        .build();
            }
        }

        // Phase 2a.5–2a.7: dataset merges / row expansions run BEFORE Operations, mirroring Python
        // (DatasetPreprocessor.preprocess precedes perform_rule_operations —
        // rules_engine.py:377-378)
        // so an Operation that aggregates over the primary sees the merged/expanded row set.

        // Phase 2a.5: Pre-merge Child:true parent columns (Fix #6 — CORE-000206 and siblings).
        // Every later phase uses the augmented evalTable so plain-name references in the Check can
        // reach parent columns.
        evalTable = ChildMatchPreMerger.preMerge(evalTable, rule.getMatchDatasets(), resolver,
                ruleId, joinCache);

        // Phase 2a.6: Forward RELREC row expansion (dataset-level + one-to-many). Replaces the
        // primary eval table with one expanded row per (primary record, related record) pair
        // (inner-join), mirroring the Python engine's merge_relrec_datasets preprocessing. The
        // related columns are reached via an expanded-row-aware RELREC JoinLookup (below).
        List<MatchDataset> joinMatchDatasets = rule.getMatchDatasets();
        RelrecRowExpander.RelrecExpansion relrecExpansion = null;
        MatchDataset forwardRelrec = RelrecRowExpander.findForwardRelrec(rule.getMatchDatasets());
        if (forwardRelrec != null)
        {
            relrecExpansion = RelrecRowExpander.expand(evalTable, rule.getMatchDatasets(), resolver,
                    ruleId);
            if (relrecExpansion != null)
            {
                evalTable = relrecExpansion.table();
                // Exclude the forward RELREC entry; its columns are served by the expanded RELREC
                // lookup.
                joinMatchDatasets = new ArrayList<>(rule.getMatchDatasets());
                joinMatchDatasets.remove(relrecExpansion.forwardEntry());
            }
        }

        // Phase 2a.7: key-based Match_Datasets row expansion (mirrors Python merge_datasets). One
        // expanded row per (primary, matched child) pair, honoring join_type (default left); each
        // expanded row binds its matching child, served by the KeyMatchExpandedLookup added below —
        // so a dot-qualified AE.AESDTH predicate sees the matching child, not a first-wins guess.
        KeyMatchRowExpander.KeyMatchExpansion keyExpansion = KeyMatchRowExpander.expand(evalTable,
                joinMatchDatasets, resolver, ruleId);
        if (keyExpansion != null)
        {
            if (relrecExpansion != null)
            {
                // A forward RELREC expansion and a key-based row expansion on the same rule would
                // each re-index the eval table independently: the RELREC JoinLookup is built
                // against the RELREC-expanded row indices, but the key-match expansion replaces the
                // table with its own (further-expanded) indices, so the RELREC lookup would be read
                // with the wrong row index and serve corrupted related-record values. No bundled
                // rule combines the two; surface a rule-execution ERROR ("virtual finding") rather
                // than silently producing a wrong result — same sentinel pattern as the load-error
                // path above.
                String comboMsg = "forward RELREC join combined with a key-based Match_Datasets row "
                        + "expansion is not supported on the same rule";
                LOGGER.log(System.Logger.Level.WARNING, "[{0}] {1}", ruleId != null ? ruleId : "?",
                        comboMsg);
                Violation sentinel = new Violation(0, Map.of("__error__", comboMsg));
                return RuleExecutionResult.builder().ruleId(ruleId).message(message)
                        .violations(List.of(sentinel)).totalRows(table.getRowCount())
                        .status(RuleExecutionStatus.ERROR).statusMessage(comboMsg).build();
            }
            evalTable = keyExpansion.table();
            // Expanded entries are served by the bound-child lookups; drop them from key-join
            // build.
            List<MatchDataset> remaining = new ArrayList<>(joinMatchDatasets);
            remaining.removeAll(keyExpansion.expandedEntries());
            joinMatchDatasets = remaining;
        }

        // Phase 2a (Fix #36): wrap every Operation result in a LazyValue<Object> so the supplier
        // only runs on first read. When the Check tree folds to a dataset-level constant via an
        // unrelated leaf (e.g. an APHASEN guard), Operation suppliers tied to never-read
        // $variables stay cold. Memoisation lifetime is the EvaluationContext (one rule
        // execution); cross-rule caching is out of scope.
        //
        // Library-dependent Operations preserve the eager early-skip semantic: if any op in the
        // rule needs a MetadataProvider and none is configured, the rule reports SKIPPED
        // before any supplier fires. Detected via OperationExecutor.isLibraryDependent without
        // running the dispatch.
        Map<String, Object> variables = Map.of();
        if (rule.getOperations() != null && !rule.getOperations().isEmpty())
        {
            List<net.cumba.cdisc.core.model.Operation> resolvedOps = rule.getOperations();
            if (domainPrefix != null)
            {
                resolvedOps = resolvedOps.stream()
                        .map(op -> resolveOperationPrefix(op, domainPrefix, varWildcardPrefix))
                        .toList();
            }
            if (libraryProvider == null)
            {
                for (net.cumba.cdisc.core.model.Operation op : resolvedOps)
                {
                    if (OperationExecutor.isLibraryDependent(op.getOperationType()))
                    {
                        return RuleExecutionResult.builder().ruleId(ruleId).message(message)
                                .violations(List.of()).totalRows(evalTable.getRowCount())
                                .status(RuleExecutionStatus.SKIPPED)
                                .statusMessage("Rule skipped — no Library access").build();
                    }
                }
            }
            // T2-residual: a define-set operation (define_variable_names / define_key_variables)
            // needs the sponsor Define-XML overlay. With no Define-XML supplied the rule SKIPs
            // (never PASS/FAIL) before any supplier fires — the same input-availability discipline
            // as the Library gate above and the define_* operand-prefix gate for the attribute
            // compares.
            if (defineProvider == null)
            {
                for (net.cumba.cdisc.core.model.Operation op : resolvedOps)
                {
                    if (OperationExecutor.isDefineDependent(op.getOperationType()))
                    {
                        return RuleExecutionResult.builder().ruleId(ruleId).message(message)
                                .violations(List.of()).totalRows(evalTable.getRowCount())
                                .status(RuleExecutionStatus.SKIPPED)
                                .statusMessage("Rule skipped — no Define-XML metadata "
                                        + "(rule requires define_* operations)")
                                .build();
                    }
                }
            }
            // KDICT-F1 / Fix #268: an external-dictionary operation (valid_external_dictionary_*
            // / dictionary_has_decode) can only answer when a dictionary of its
            // `external_dictionary_type` is loaded. With none loaded every one of those arms
            // returns null (they all test `dictionaryProvider != null && isAvailable(type)`), the
            // null $-ref broadcasts and no row fires — so the rule EXECUTES and reports a silent
            // false PASS. This arm restores the input-availability discipline of the Library and
            // Define arms above for the DECLARED ($-ref) form, which is the form the entire
            // shipped corpus uses: RulePackageLoader.injectInlineOperationGates and
            // OperationInliner only ever emit the `dictionary_available(<type>)` Precondition gate
            // for INLINED operation calls, and no shipped rule inlines one.
            //
            // Mirrors that injected gate exactly:
            // • one required type per dictionary operation, ANDed — a rule whose types are only
            // partly loaded still SKIPs (the inliner emits one gate term per distinct type, and a
            // conjunction folds false when any term does);
            // • availability is the TYPE test, not merely a non-null provider — a MedDRA-only
            // bundle leaves a UNII rule just as unanswerable as no bundle at all;
            // • DICTIONARY_AVAILABLE is excluded: that operation IS the gate. Its executor arm is
            // total (it yields Boolean.FALSE, never null, with no provider), so eager-skipping on
            // it would destroy the very reporting it exists for.
            // An operation with no (or blank) external_dictionary_type is not this arm's case: no
            // install could ever satisfy it, so it is an authoring defect, tagged as a loadError
            // by RulePackageLoader.validateDictionaryOperationTypes — such a rule reports ERROR
            // through the loadError sentinel above and never reaches this arm (D13 item 3).
            //
            // D13 item 2 — the SKIP names the state, not just the type: "not installed",
            // "installed but unusable" and "no/absent version selected" demand three different
            // operator actions, and one catch-all message actively misleads the operator who DID
            // install the dictionary. The provider carries the diagnosis recorded by whoever
            // declined to load the type (RuntimeDictionaryProvider.loadDirectory's content guard,
            // DictionaryStore's version binding); with no provider at all, every type is simply
            // not installed.
            Set<String> unavailableDictionaryTypes = new LinkedHashSet<>();
            for (net.cumba.cdisc.core.model.Operation op : resolvedOps)
            {
                OperationType opType = op.getOperationType();
                if (opType == OperationType.DICTIONARY_AVAILABLE
                        || !OperationExecutor.isDictionaryDependent(opType))
                {
                    continue;
                }
                String dictionaryType = op.getExternalDictionaryType();
                if (dictionaryType == null || dictionaryType.isBlank())
                {
                    continue; // loadError at load time — unreachable via any loader path
                }
                if (dictionaryProvider == null || !dictionaryProvider.isAvailable(dictionaryType))
                {
                    unavailableDictionaryTypes.add(dictionaryType);
                }
            }
            if (!unavailableDictionaryTypes.isEmpty())
            {
                StringBuilder skipReason = new StringBuilder("Rule skipped — ");
                for (String type : unavailableDictionaryTypes)
                {
                    if (skipReason.length() > "Rule skipped — ".length())
                    {
                        skipReason.append("; ");
                    }
                    skipReason.append("external dictionary ").append(type).append(' ')
                            .append(dictionaryProvider == null
                                    ? RuntimeDictionaryProvider.notInstalledDetail()
                                    : dictionaryProvider.unavailabilityDetail(type));
                }
                skipReason.append(" (rule requires valid_external_dictionary_* operations)");
                return RuleExecutionResult.builder().ruleId(ruleId).message(message)
                        .violations(List.of()).totalRows(evalTable.getRowCount())
                        .status(RuleExecutionStatus.SKIPPED).statusMessage(skipReason.toString())
                        .build();
            }
            // Build the lazy variables map. The supplier closes over the variables map itself so
            // an op's supplier can read prior op results — forcing only the LazyValues it
            // actually depends on (via expandGroupRefs's $variable refs in op.group). Maintain
            // insertion order so prior-op references resolve consistently.
            Map<String, Object> lazyVars = new LinkedHashMap<>();
            final IDataTable lazyTable = evalTable;
            final DatasetResolver lazyResolver = resolver;
            final MetadataProvider lazyLibrary = libraryProvider;
            final RuntimeDictionaryProvider lazyDict = dictionaryProvider;
            final MetadataProvider lazyDefine = defineProvider;
            for (net.cumba.cdisc.core.model.Operation op : resolvedOps)
            {
                String opId = op.getId();
                if (opId == null || op.getOperationType() == null)
                {
                    // Unidentified or unknown-type op — eager-run for its side effects
                    // (typed-null type logs a WARN; null id has nowhere to land its result).
                    // Materialise only the prior LazyValue entries referenced via this op's
                    // group list to avoid forcing unrelated ops or creating cycles.
                    Map<String, Object> resolved = new LinkedHashMap<>();
                    List<String> opGroup = op.getGroup();
                    if (opGroup != null)
                    {
                        for (String g : opGroup)
                        {
                            if (g == null || !g.startsWith("$"))
                            {
                                continue;
                            }
                            Object v = lazyVars.get(g);
                            if (v instanceof LazyValue<?> lv)
                            {
                                v = lv.get();
                            }
                            if (v != null)
                            {
                                resolved.put(g, v);
                            }
                        }
                    }
                    // minus references its operands via name/subtract (not group); force those
                    // prior-op $-refs too so set-difference sees their resolved lists.
                    forceOperandRefs(op, opId, lazyVars, resolved);
                    OperationExecutor.executeOne(op, lazyTable, lazyResolver, lazyLibrary, resolved,
                            ruleId, lazyDict, lazyDefine);
                    continue;
                }
                final net.cumba.cdisc.core.model.Operation finalOp = op;
                LazyValue<Object> lazy = new LazyValue<>(() ->
                {
                    // Materialise only the prior LazyValue entries this op actually depends on
                    // via its group list ($variable refs), so independent ops don't fan out
                    // and create cycles among each other. expandGroupRefs reads
                    // priorResults.get(g) only for group entries that startsWith("$"); other
                    // entries stay lazy and downstream readers unwrap via ctx.resolveVariable.
                    Map<String, Object> resolved = new LinkedHashMap<>();
                    List<String> opGroup = finalOp.getGroup();
                    if (opGroup != null)
                    {
                        for (String g : opGroup)
                        {
                            if (g == null || !g.startsWith("$") || g.equals(opId))
                            {
                                continue;
                            }
                            Object v = lazyVars.get(g);
                            if (v instanceof LazyValue<?> lv)
                            {
                                v = lv.get();
                            }
                            if (v != null)
                            {
                                resolved.put(g, v);
                            }
                        }
                    }
                    // minus references its operands via name/subtract (not group); force those
                    // prior-op $-refs too so set-difference sees their resolved lists.
                    forceOperandRefs(finalOp, opId, lazyVars, resolved);
                    return OperationExecutor.executeOne(finalOp, lazyTable, lazyResolver,
                            lazyLibrary, resolved, ruleId, lazyDict, lazyDefine);
                });
                lazyVars.put(opId, lazy);
            }
            variables = lazyVars;

            // Phase 2a.1 (Fix #42 phase 1 — defensive): force every library-dependent
            // Operation eagerly and check for LIBRARY_NOT_AVAILABLE. The Phase 2a lazy
            // mechanism (Fix #36) explicitly preserves the eager skip semantic for
            // library-dependent ops; this widens that gate from "no provider at all" to
            // "provider configured but returned no usable data for this domain", so a rule
            // like CORE-000550 doesn't fan out one violation per column when the model
            // metadata is missing. The full parity port (richer get_variables_metadata_from
            // _standard_model with model-class fallback) is tracked as Fix #42.
            if (libraryProvider != null)
            {
                for (net.cumba.cdisc.core.model.Operation op : resolvedOps)
                {
                    String opId = op.getId();
                    // Fix #371 — plain isLibraryDependent again. Fix #369 needed the wider
                    // `isLibraryBacked` only because `domain_label()` read the Library while
                    // sitting outside `isLibraryDependent` (which is materialised into the shipped
                    // corpus as Requirements.Library, so it could not simply be widened). With
                    // `domain_label()` retired there is no such operation left, and the second
                    // predicate dissolved with it.
                    if (opId == null
                            || !OperationExecutor.isLibraryDependent(op.getOperationType()))
                    {
                        continue;
                    }
                    Object value = lazyVars.get(opId);
                    if (value instanceof LazyValue<?> lv)
                    {
                        value = lv.get();
                    }
                    if (value == OperationExecutor.LIBRARY_NOT_AVAILABLE)
                    {
                        // Fix #369 — name the CDISC Library explicitly when it could not be
                        // consulted at all. "returned no data" is true but misleading there: it
                        // reads as "the Library was asked and had nothing", when in fact it was
                        // never reachable (typically an expired subscription key), and the two
                        // call for different action from whoever reads the report.
                        return RuleExecutionResult.builder().ruleId(ruleId).message(message)
                                .violations(List.of()).totalRows(evalTable.getRowCount())
                                .status(RuleExecutionStatus.SKIPPED)
                                .statusMessage(libraryProvider.isLibraryUnavailable()
                                        ? "Rule skipped — the CDISC Library could not be consulted"
                                                + " for this run, and " + op.getOperationType()
                                                + " may not be answered from a non-library source"
                                        : "Rule skipped — library returned no data for "
                                                + op.getOperationType())
                                .build();
                    }
                }
            }
            // Phase 2a.1 (define analog, M4): a define-dependent op that resolved but returned no
            // usable data — e.g. define_key_variables against a Define whose KeySequence is empty
            // for this dataset — yields LIBRARY_NOT_AVAILABLE. Skip the rule rather than let the
            // empty key set collapse an is_not_unique_set to the constant STUDYID anchor and flag
            // every record as a duplicate (PMDA-SD1152). Mirrors the Python operation, which raises
            // DefineXMLNotProvidedError → SKIPPED on an empty key sequence.
            if (defineProvider != null)
            {
                for (net.cumba.cdisc.core.model.Operation op : resolvedOps)
                {
                    String opId = op.getId();
                    if (opId == null || !OperationExecutor.isDefineDependent(op.getOperationType()))
                    {
                        continue;
                    }
                    Object value = lazyVars.get(opId);
                    if (value instanceof LazyValue<?> lv)
                    {
                        value = lv.get();
                    }
                    if (value == OperationExecutor.LIBRARY_NOT_AVAILABLE)
                    {
                        return RuleExecutionResult.builder().ruleId(ruleId).message(message)
                                .violations(List.of()).totalRows(evalTable.getRowCount())
                                .status(RuleExecutionStatus.SKIPPED)
                                .statusMessage("Rule skipped — Define-XML declares no key "
                                        + "variables for " + op.getOperationType())
                                .build();
                    }
                }
            }
        }

        // (Phase 2a2 — the Dataset-Metadata-Check variable injection of dataset_name /
        // dataset_label / record_count / dataset_domain / define_dataset_* / library_dataset_* /
        // DOMAIN — is gone: phase 6 of PLAN-leaf-scope-domain-inference.md. Every bareword
        // canonicalises to its ds_* accessor for every rule, the output-value projection reads the
        // same accessors (ExprCompiler.datasetScopeOperandValue), and OperationExecutor never read
        // the injected entries. Measured 2026-08-22: no shipped rule reads any of those names as a
        // context variable, and the SD0004 DOMAIN-column shape evaluates identically with and
        // without the injection.)

        // Phase 2b: Build joined dataset lookups from Match_Datasets
        Map<String, JoinLookup> joinedDatasets = buildJoinedDatasets(joinMatchDatasets, evalTable,
                resolver, joinCache, ruleId);
        if (relrecExpansion != null)
        {
            joinedDatasets = new LinkedHashMap<>(joinedDatasets);
            joinedDatasets.put(RELREC, relrecExpansion.lookup());
        }
        if (keyExpansion != null)
        {
            joinedDatasets = new LinkedHashMap<>(joinedDatasets);
            joinedDatasets.putAll(keyExpansion.lookups());
        }

        // Phase 2c: Resolve -- prefix in Check conditions. EC-36: Check leaves are variable names,
        // so this is job (b) and takes varWildcardPrefix (Python's wildcard_replacement) — NOT
        // domainPrefix. For an AP dataset that means --TERM -> MHTERM (not APMHTERM), and for a
        // SUPP dataset --QNAM -> QNAM. A null prefix means there is no domain code at all
        // (degraded / synthetic context); resolvePrefixes then returns the tree untouched,
        // exactly as before EC-36.
        CheckCondition check = rule.getCheck();
        if (varWildcardPrefix != null)
        {
            check = CheckConditionTransformer.resolvePrefixes(check, varWildcardPrefix,
                    domainPrefix, ruleId);
        }

        EvaluationContext ctx = EvaluationContext.builder().table(evalTable).variables(variables)
                .defineProvider(defineProvider).vlmResolver(vlmResolver).ruleId(ruleId)
                .datasetResolver(resolver).domainPrefix(domainPrefix)
                .variableWildcardPrefix(varWildcardPrefix)
                .domainName(evalTable.getMetaData().getName()).joinedDatasets(joinedDatasets)
                .evaluationDomain(rule.getEvaluationDomain()).maxErrorsPerRule(maxErrorsPerRule)
                .libraryProvider(libraryProvider).dictionaryProvider(dictionaryProvider)
                .exprCache(exprCache).checkExprOverride(absentSkip.effectiveCheckExpr())
                .severityThreshold(severityThreshold).build();
        try
        {
            // ⭐ `null` for every single-level rule — the entire shipped corpus — so executeAgainst
            // takes the same branch, with the same arguments, that it took before this plan.
            List<CheckLevelPlan> levels = levelSkips == null ? null
                    : buildLevelPlans(rule, runnableLevels, declaredLevels, levelSkips, check,
                            varWildcardPrefix, domainPrefix, ruleId, ctx);
            return executeAgainst(rule, ruleId, message, check, ctx, levels);
        }
        finally
        {
            // EC-43: exactly one aggregated INFO line per (rule, dataset), whatever the outcome
            // (finding, SKIPPED, or a propagating evaluation error). See logAbsentColumnFolds.
            logAbsentColumnFolds(ruleId, evalTable, ctx);
        }
    }


    /**
     * The Check-evaluation tail of {@link #execute}: everything from the built
     * {@link EvaluationContext} onwards. Split out so the caller can drain the context's EC-43
     * absent-column record in a {@code finally} — every path out of this method is the end of one
     * (rule, dataset) execution.
     */
    private static RuleExecutionResult executeAgainst(Rule rule, @Nullable String ruleId,
            @Nullable String message, CheckCondition check, EvaluationContext initialCtx,
            @Nullable List<CheckLevelPlan> levels)
    {
        EvaluationContext ctx = initialCtx;
        IDataTable evalTable = ctx.getTable();

        // Phase 2e: Precondition guard (Fix #13). When present, evaluate against dataset-level
        // context; false means skip the rule entirely. Scalar $-variables and dataset-level
        // metadata (dataset_name, record_count, DOMAIN from Fix #10) are all available at this
        // point. All currently-shipping rules leave Precondition null → no-op.
        // P6b + guard-residual D3: native precondition evaluation. The loader raises a
        // fold-equivalent (broadcast-verdict) Precondition to a transient preconditionExpr; the
        // tri-state BroadcastFold decides the skip: FALSE ⇒ skip; TRUE ⇒ continue; UNKNOWN (a
        // runtime GroupedResult/VariableMetadataResult $-ref the fold short-circuits around but
        // cannot decide) ⇒ continue with the main Check — mirroring Python's approach of always
        // attempting the check. A null preconditionExpr means the loader classified the
        // precondition as non-broadcast (row-level): the retired legacy fold could never decide
        // those either ("not fully resolvable ⇒ continue"), so continuing with the main Check
        // preserves the exact pre-retirement (and Python) semantics.
        if (rule.getPrecondition() != null && rule.getPreconditionExpr() != null
                && net.cumba.cdisc.core.expr.eval.BroadcastFold.fold(rule.getPreconditionExpr(),
                        ctx, false) == net.cumba.cdisc.core.expr.eval.BroadcastFold.Verdict.FALSE)
        {
            return RuleExecutionResult.builder().ruleId(ruleId).message(message)
                    .violations(List.of()).totalRows(evalTable.getRowCount())
                    .status(RuleExecutionStatus.SKIPPED)
                    .statusMessage("Rule skipped — precondition not met").build();
        }

        // Group sensitivity is orthogonal — handle separately.
        // Grouping_Variables is the single driver (plan §2.4, Q5): load gate 3b makes the two
        // fields agree by construction — Group without grouping variables, or grouping variables
        // under any other Sensitivity, is now a load error — so testing Sensitivity as well would
        // only restate what the gate already guarantees, and would silently ignore the grouping on
        // a rule whose Sensitivity was derived rather than authored.
        List<String> ruleGrouping = rule.effectiveGroupingVariables();
        boolean grouped = ruleGrouping != null && !ruleGrouping.isEmpty();

        // ⭐ Plan C §3.4 — the SINGLE-LEVEL path is not "a loop that happens to run once": it is
        // literally the pre-Plan-C call, with the pre-Plan-C arguments, on a context carrying no
        // level plan. Every shipped rule takes it.
        if (levels == null)
        {
            if (grouped)
            {
                return executeGrouped(rule, ruleId, message, check, ctx);
            }
            return executeUnified(rule, ruleId, message, check, ctx);
        }
        // Both the Precondition gate above and the grouping routing decision have now been made
        // ONCE, before any level — §3.4: they are properties of the rule, not of a level.
        return executeLevels(rule, ruleId, message, ctx, levels, grouped);
    }


    /**
     * The multi-level evaluation of Plan C &#167;3.4 — an <b>outer loop</b> over the rule's
     * declared levels, strictest first, around the unchanged single-level chain.
     *
     * <p>
     * <b>Why a loop here rather than per-level threading inside the three entry points.</b>
     * {@code executeUnified} / {@code evaluateVariableValueNative} / {@code executeGrouped} are
     * hot, deeply woven and shared with {@link CohortRunner}; threading a level through them would
     * make the single-level path a special case of new code. Looping here makes the single-level
     * path <em>provably</em> the old code (see {@link #executeAgainst}) and each level's evaluation
     * exactly what a one-level rule with that Check would have produced.
     * </p>
     *
     * <p>
     * <b>First claim wins, per finding unit</b> (&#167;3.4 step 4): a unit a stricter level already
     * claimed is not re-reported by a weaker one, and the surviving {@link Violation} is stamped
     * with the claiming level. The unit key is {@link FindingUnit}.
     * </p>
     *
     * <p>
     * &#9940;&#9940; <b>First claim is CROSS-level only — it never deduplicates within one
     * level.</b> The claimed set consulted while a level runs holds the units of the levels
     * <em>already finished</em>; this level's own units join it only once the level completes. A
     * level's violations are distinct findings by construction, and several of them can share one
     * unit stamp — every expanded row of a {@code KeyMatchRowExpander} join maps back to the same
     * primary row, so N genuine findings carry one {@link Violation.Unit.Row}. Consulting a set
     * this level was still adding to dropped all but the first of them, which the single-level path
     * (no claimed set at all) had always reported.
     * </p>
     *
     * <p>
     * <b>The findings cap is per RULE, not per level</b> ({@link ViolationSink}): each level
     * materialises at most the cap, the levels are merged, and the merged list is capped once. When
     * no level truncated — every case that matters — {@code totalViolationCount} is exactly the
     * merged size; when a level did truncate, first-claim can only be applied to the part it
     * materialised, so the count is that level's true total minus the duplicates actually seen,
     * i.e. an upper bound. Stated rather than hidden.
     * </p>
     *
     * <p>
     * <b>Status.</b> An evaluation ERROR at any level is the rule's ERROR (it is a defect, not a
     * verdict). A level that SKIPs — a provider it needs is absent — contributes nothing; the rule
     * reports SKIPPED only when no level evaluated at all, naming the first reason.
     * </p>
     */
    private static RuleExecutionResult executeLevels(Rule rule, @Nullable String ruleId,
            @Nullable String message, EvaluationContext ctx, List<CheckLevelPlan> levels,
            boolean grouped)
    {
        List<Violation> merged = new ArrayList<>();
        Set<FindingUnit> claimed = new LinkedHashSet<>();
        long total = 0;
        long totalRows = ctx.getTable().getRowCount();
        RecordKeyResolver.KeySource keySource = RecordKeyResolver.KeySource.NONE;
        String skipReason = null;
        int evaluated = 0;

        for (CheckLevelPlan level : levels)
        {
            if (level.collapsed())
            {
                // Fix #222 folded this level's whole Check to false: it can claim nothing, so
                // evaluating it would be a guaranteed-empty pass over the dataset.
                continue;
            }
            EvaluationContext levelCtx = ctx.toBuilder().checkExprOverride(level.expr())
                    .levelPlan(level).build();
            RuleExecutionResult result = grouped
                    ? executeGrouped(rule, ruleId, message, level.condition(), levelCtx)
                    : executeUnified(rule, ruleId, message, level.condition(), levelCtx);
            // ⚑ Read through isError()/isSkipped(), never `getStatus() == RuleExecutionStatus.
            // ERROR`: the rulespec suite's ExpectedErrorsTest counts the lines of this file that
            // name that constant, as its census of the engine's ERROR-PRODUCING sites. This loop
            // produces no new ERROR — it propagates a level's — and must not inflate that census.
            if (result.isError())
            {
                return result;
            }
            if (result.isSkipped())
            {
                if (skipReason == null)
                {
                    skipReason = result.getStatusMessage();
                }
                continue;
            }
            evaluated++;
            totalRows = Math.max(totalRows, result.getTotalRows());
            if (keySource == RecordKeyResolver.KeySource.NONE)
            {
                keySource = result.getKeySource();
            }
            long dropped = 0;
            // ⛔⛔ First-claim is a CROSS-level rule and NOTHING ELSE. The set consulted below is
            // the set of units claimed by the levels ALREADY DONE; this level's own units are
            // accumulated into `claimedHere` and folded in only once the level completes. Adding
            // them as they are seen would deduplicate a level against ITSELF, and a level's own
            // violations are distinct findings by construction — two expanded rows of one
            // KeyMatchRowExpander join share a primary row index, hence one Unit.Row stamp, and
            // the second was being silently dropped. Pinned by RuleCheckLevelsExecutionTest's
            // oneLevelNeverDeduplicatesAgainstItself,
            // aWeakerRungStillDoesNotReReportAStricterRungsUnit and
            // expandedJoinRowsAreNFindingsAndTheWeakerRungAddsNone.
            List<FindingUnit> claimedHere = new ArrayList<>();
            for (Violation v : result.getViolations())
            {
                FindingUnit unit = FindingUnit.of(v);
                if (claimed.contains(unit))
                {
                    dropped++;
                    continue;
                }
                claimedHere.add(unit);
                merged.add(new Violation(v.getRow(), v.getValues(), v.getUsubjid(), v.getSeq(),
                        v.getKeys(), level.level(), v.getUnit()));
            }
            claimed.addAll(claimedHere);
            // `dropped` counts only what a STRICTER level had already claimed, so the arithmetic
            // still subtracts exactly the cross-level re-reports and never a within-level pair.
            total += Math.max(0, result.getViolationCount() - dropped);
        }

        if (evaluated == 0)
        {
            return RuleExecutionResult.builder().ruleId(ruleId).message(message)
                    .violations(List.of()).totalRows(totalRows).status(RuleExecutionStatus.SKIPPED)
                    .statusMessage(skipReason != null ? skipReason
                            : "Rule skipped — no declared check level could be evaluated on this "
                                    + "dataset")
                    .build();
        }
        // Math.max mirrors ViolationSink's defence: a raw negative cap would make subList throw.
        int cap = Math.max(0, ctx.getMaxErrorsPerRule());
        List<Violation> capped = merged.size() <= cap ? merged : merged.subList(0, cap);
        return RuleExecutionResult.builder().ruleId(ruleId).message(message)
                .violations(List.copyOf(capped)).totalViolationCount(Math.max(total, merged.size()))
                .totalRows(totalRows).keySource(keySource).build();
    }

    /**
     * The <b>finding unit</b> first-claim is keyed on (Plan C &#167;3.4 step 4): the
     * {@linkplain Violation.Unit unit discriminator} the <em>producing</em> site stamped on the
     * violation, when it stamped one; the materialised tuple {@code (row, values, usubjid, seq)}
     * otherwise.
     *
     * <p>
     * &#9733; <b>Why the stamp, not the tuple.</b> The tuple is the finding as materialised, and
     * three producing paths materialise the <em>same</em> unit differently — or different units
     * identically:
     * </p>
     * <ul>
     * <li><b>Grouped rules</b> ({@code executeGrouped}) anchor a failing group at <em>the running
     * level's</em> first flagged row. Two levels flagging different rows of one group would give
     * two keys — the same group reported twice, once per level. The stamp is the group's resolved
     * key tuple, which every level of one rule shares (the {@code Grouping} block is
     * rule-level).</li>
     * <li><b>Dataset verdicts</b> ({@code datasetBroadcastResult}, the collapsed {@code {}} branch)
     * emit {@code (0, values)} with no row identity, while the row path emits the real row with
     * {@code usubjid}/{@code seq} — colliding with a genuine row-0 finding on a
     * {@code USUBJID}-less domain and double-reporting elsewhere. The stamps are disjoint types
     * ({@code Dataset} vs {@code Row}), so neither can happen.</li>
     * <li><b>Per-variable paths</b> ({@code buildVariableViolation},
     * {@code addVariableRowViolations}) carry the column identity in {@code values} only when the
     * author projects {@code variable_name}; excluded, two columns firing on one row collapse to
     * one key and a finding is silently lost. The stamp is (column, row) regardless of the
     * projection.</li>
     * </ul>
     *
     * <p>
     * The tuple fallback exists for violations no producing site stamped — external producers and
     * any path added without a stamp — where it degrades to the pre-stamp behaviour rather than
     * failing.
     * </p>
     *
     * <p>
     * &#9888; The EC-40 record {@code keys} are deliberately <b>out</b> of the fallback key: they
     * are resolved per (rule, dataset) from one {@code RowKeySpec}, so they are a function of
     * {@code row} and adding them could only make two identical units compare unequal if a level
     * resolved a different key tier — which would itself be a defect.
     * </p>
     */
    private record FindingUnit(Violation.@Nullable Unit unit, long row, Map<String, String> values,
            @Nullable String usubjid, @Nullable String seq)
    {

        static FindingUnit of(Violation v)
        {
            if (v.getUnit() != null)
            {
                // Normalise the tuple half so equal stamps compare equal whatever the violation
                // happened to materialise around them.
                return new FindingUnit(v.getUnit(), 0L, Map.of(), null, null);
            }
            return new FindingUnit(null, v.getRow(), v.getValues(), v.getUsubjid(), v.getSeq());
        }
    }

    /**
     * The finding-unit stamp for <em>this</em> execution: {@code unit}'s value on a per-level
     * execution, {@code null} on the single-level path.
     *
     * <p>
     * The stamp is consulted only by {@link #executeLevels}' first-claim, and the single-level path
     * — which never reaches that loop — must stay <em>provably</em> the pre-Plan-C code, so its
     * violations are left bit-identical (no stamp) rather than carrying a field nothing reads.
     * </p>
     */
    private static Violation.@Nullable Unit stamp(EvaluationContext ctx,
            java.util.function.Supplier<Violation.Unit> unit)
    {
        return ctx.getLevelPlan() == null ? null : unit.get();
    }


    /**
     * The compiled expression of one declared check level; only ever called on a rule that declared
     * a level map ({@link Rule#getCheckLevelExprs()} non-null — a one-entry map included).
     */
    private static net.cumba.cdisc.core.expr.ast.Expr levelExprOf(Rule rule, Severity level)
    {
        return Objects.requireNonNull(
                Objects.requireNonNull(rule.getCheckLevelExprs(), "multi-level rule").get(level),
                "compiled expression for level " + level);
    }


    /**
     * The merged {@code Fix #222} decision when <b>every</b> runnable level collapsed, else
     * {@code null}.
     *
     * <p>
     * The reason names the union of the responsible datasets across the levels, so a reader is told
     * everything that contributed rather than whichever level happened to be first.
     * </p>
     */
    private static AbsentDatasetSkip.@Nullable Decision allLevelsCollapsed(
            Map<Severity, AbsentDatasetSkip.Decision> levelSkips)
    {
        if (levelSkips.isEmpty())
        {
            return null;
        }
        Set<String> suppressed = new LinkedHashSet<>();
        Set<String> unsupplied = new LinkedHashSet<>();
        for (AbsentDatasetSkip.Decision decision : levelSkips.values())
        {
            if (!decision.collapsed())
            {
                return null;
            }
            suppressed.addAll(decision.suppressedDatasets());
            unsupplied.addAll(decision.unsuppliedDatasets());
        }
        return new AbsentDatasetSkip.Decision(null, List.copyOf(suppressed),
                List.copyOf(unsupplied), true);
    }


    /**
     * Builds one {@link CheckLevelPlan} per runnable level (Plan C &#167;3.4) — the per-level facts
     * {@link #executeLevels} and the entry points read in place of the rule's own fields.
     *
     * <p>
     * Two things are computed <b>once, across the levels</b>, and shared by all of them:
     * </p>
     * <ul>
     * <li>the projected {@code Output_Variables} — &#167;3.3 makes {@code Outcome.Output_Variables}
     * a property of the rule, so a finding must carry the same columns whichever level claims
     * it;</li>
     * <li>the evaluation domain — the join over the levels' <em>effective</em> expressions, so the
     * cursor shape (and therefore the finding unit) does not change between levels of one rule. The
     * join is taken over the post-suppression expressions for the same reason {@link #domainOf}
     * re-infers on a {@code Fix #222} override: a folded leaf can take a cursor with it.</li>
     * </ul>
     */
    private static List<CheckLevelPlan> buildLevelPlans(Rule rule, List<Severity> runnable,
            SequencedMap<Severity, LevelCheck> declared,
            Map<Severity, AbsentDatasetSkip.Decision> skips, CheckCondition strictestResolved,
            @Nullable String varWildcardPrefix, @Nullable String domainPrefix,
            @Nullable String ruleId, EvaluationContext ctx)
    {
        Severity strictest = declared.firstEntry().getKey();
        Map<Severity, CheckCondition> conditions = new LinkedHashMap<>();
        Map<Severity, net.cumba.cdisc.core.expr.ast.Expr> effective = new LinkedHashMap<>();
        for (Severity level : runnable)
        {
            CheckCondition condition = levelOf(declared, level).condition();
            if (level == strictest)
            {
                // Already resolved by the caller — reuse it rather than resolve the same tree
                // twice.
                condition = strictestResolved;
            }
            else if (varWildcardPrefix != null)
            {
                condition = CheckConditionTransformer.resolvePrefixes(condition, varWildcardPrefix,
                        domainPrefix, ruleId);
            }
            conditions.put(level, condition);
            AbsentDatasetSkip.Decision decision = decisionOf(skips, level);
            net.cumba.cdisc.core.expr.ast.Expr override = decision.effectiveCheckExpr();
            effective.put(level, override != null ? override : levelExprOf(rule, level));
        }

        List<String> outputVars = rule.effectiveOutputVariablesOrAuthored();
        if (outputVars.isEmpty())
        {
            java.util.SequencedSet<String> inferred = new LinkedHashSet<>();
            for (CheckCondition condition : conditions.values())
            {
                inferred.addAll(collectCheckLeafColumns(condition, ctx.getTable().getMetaData()));
            }
            List<String> projected = new ArrayList<>(inferred);
            projected.removeAll(rule.excludedOutputVariablesOrAuthored());
            outputVars = List.copyOf(projected);
        }

        net.cumba.cdisc.core.expr.eval.Domain domain = null;
        for (net.cumba.cdisc.core.expr.ast.Expr levelExpr : effective.values())
        {
            net.cumba.cdisc.core.expr.eval.Domain levelDomain = net.cumba.cdisc.core.expr.eval.DomainScan
                    .infer(levelExpr, net.cumba.cdisc.core.expr.eval.OperationKinds.forRule(rule));
            domain = domain == null ? levelDomain : domain.join(levelDomain);
        }

        Set<Severity> broadcast = rule.getBroadcastCheckLevels() == null ? Set.of()
                : rule.getBroadcastCheckLevels();
        List<CheckLevelPlan> plans = new ArrayList<>(runnable.size());
        for (Severity level : runnable)
        {
            plans.add(new CheckLevelPlan(level,
                    Objects.requireNonNull(conditions.get(level), "resolved condition"),
                    Objects.requireNonNull(effective.get(level), "effective expression"),
                    broadcast.contains(level), decisionOf(skips, level).collapsed(),
                    Objects.requireNonNull(domain, "at least one runnable level"), outputVars,
                    levelOf(declared, level).message()));
        }
        return plans;
    }


    /** The declared level, which {@code runnable} guarantees is present. */
    private static LevelCheck levelOf(SequencedMap<Severity, LevelCheck> aDeclared, Severity aLevel)
    {
        return Objects.requireNonNull(aDeclared.get(aLevel), "declared level " + aLevel);
    }


    /** The level's {@code Fix #222} decision, which {@code runnable} guarantees is present. */
    private static AbsentDatasetSkip.Decision decisionOf(
            Map<Severity, AbsentDatasetSkip.Decision> aSkips, Severity aLevel)
    {
        return Objects.requireNonNull(aSkips.get(aLevel), "absent-dataset decision for " + aLevel);
    }

    // ---- Unified three-level execution ----


    /**
     * The Check expression to evaluate for <em>this</em> (rule, dataset) execution: the context's
     * {@code checkExprOverride} when {@code Fix #222} suppressed a reading of an absent,
     * already-reported foreign dataset, else the rule's own compiled {@code checkExpr}.
     *
     * <p>
     * Every {@code checkExpr} read on the execution path goes through this accessor — reading
     * {@link Rule#getCheckExpr()} directly would evaluate the unsuppressed tree and re-introduce
     * the flood the fix removes. The override lives on the context, not the rule, because a Rule
     * instance is shared across datasets and across the parallel dataset fan-out.
     * </p>
     */
    private static net.cumba.cdisc.core.expr.ast.@Nullable Expr checkExprOf(Rule rule,
            EvaluationContext ctx)
    {
        net.cumba.cdisc.core.expr.ast.Expr override = ctx.getCheckExprOverride();
        return override != null ? override : rule.getCheckExpr();
    }


    /**
     * Whether the expression <em>this</em> execution evaluates is a fold-equivalent broadcast
     * verdict: the running level's flag on a multi-level rule (Plan C &#167;3.3), else the rule's
     * own {@link Rule#isBroadcastCheckExpr()}.
     */
    private static boolean broadcastOf(Rule rule, EvaluationContext ctx)
    {
        CheckLevelPlan plan = ctx.getLevelPlan();
        return plan != null ? plan.broadcast() : rule.isBroadcastCheckExpr();
    }


    /**
     * The <b>authored</b> Check condition this execution evaluates — the running level's on a
     * multi-level rule, else {@link Rule#getCheck()}. Read where the shape of the authored tree
     * decides routing (the {@code CheckConditionExpression} carve-out in the dataset-level fold),
     * never where the compiled form is wanted; that is {@link #checkExprOf}.
     */
    private static @Nullable CheckCondition authoredCheckOf(Rule rule, EvaluationContext ctx)
    {
        CheckLevelPlan plan = ctx.getLevelPlan();
        return plan != null ? plan.condition() : rule.getCheck();
    }


    /**
     * The {@code Output_Variables} this execution projects: the level plan's shared list on a
     * multi-level rule — &#167;3.3 makes {@code Outcome.Output_Variables} a property of the rule,
     * so a finding carries the same columns whichever level claimed it — else the per-execution
     * projection off {@code check}.
     */
    private static List<String> outputVariablesOf(Rule rule, CheckCondition check,
            EvaluationContext ctx)
    {
        CheckLevelPlan plan = ctx.getLevelPlan();
        return plan != null ? plan.outputVariables()
                : projectedOutputVariables(rule, check, ctx.getTable().getMetaData());
    }


    /**
     * The evaluation domain of the Check <em>this</em> execution evaluates: the rule's cached
     * {@link Rule#getEvaluationDomain()} for its own {@code checkExpr}, re-inferred for a
     * {@code Fix #222} override (the suppressed tree may have lost a cursor with its folded leaf).
     */
    private static net.cumba.cdisc.core.expr.eval.Domain domainOf(Rule rule, EvaluationContext ctx,
            net.cumba.cdisc.core.expr.ast.Expr checkExpr)
    {
        CheckLevelPlan plan = ctx.getLevelPlan();
        if (plan != null)
        {
            // Plan C §3.3 step 2: every level of one rule evaluates over the SAME cursor shape —
            // the join, computed once in buildLevelPlans — so first-claim compares like with like
            // instead of a row against a cell.
            return plan.domain();
        }
        net.cumba.cdisc.core.expr.eval.Domain cached = rule.getEvaluationDomain();
        if (cached != null && checkExpr == rule.getCheckExpr())
        {
            return cached;
        }
        return net.cumba.cdisc.core.expr.eval.DomainScan.infer(checkExpr,
                net.cumba.cdisc.core.expr.eval.OperationKinds.forRule(rule));
    }


    /**
     * Row-level native evaluation (the legacy {@code CheckEvaluator} is retired): the compiled
     * {@code checkExpr} — guaranteed non-null by {@code executeUnified}'s no-native-form gate —
     * evaluates over the run's row range. P3c: the gate no longer keys on row-basedness — for a
     * non-row-based rule the caller collapses the bits to ONE violation via {@code nextSetBit(0)}.
     * P7 (decision 2 — NO FALLBACK): a native evaluation error propagates; the upstream contract
     * (LibraryValidator / StudyValidationService catch RuntimeException) converts it into a rule
     * ERROR result — surfacing the native bug instead of masking it.
     */
    private static BitSet evaluateRowLevel(Rule rule, EvaluationContext evalCtx)
    {
        BitSet bits = net.cumba.cdisc.core.expr.eval.NativeExprEvaluator
                .evaluate(Objects.requireNonNull(checkExprOf(rule, evalCtx),
                        "checkExpr (guarded by executeUnified)"), evalCtx);
        NativeExecutionRecorder.record(evalCtx.getRuleId(), NativeExecutionRecorder.Backend.NATIVE);
        return bits;
    }


    private static RuleExecutionResult executeUnified(Rule rule, @Nullable String ruleId,
            @Nullable String message, CheckCondition check, EvaluationContext ctx)
    {
        List<String> outputVars = outputVariablesOf(rule, check, ctx);
        ViolationSink violations = new ViolationSink(ctx.getMaxErrorsPerRule());
        DataTableMeta meta = ctx.getTable().getMetaData();

        // Guard-residual D2/D2b — the general native dataset-level fold, the native sibling of
        // the legacy Step-1 partialEvaluateDataset fold-to-constant return below. The tri-state
        // BroadcastFold evaluates dataset-constant leaves (presence facts, dataset facts,
        // runtime-scalar $-comparisons) natively, applies the legacy missing-column fold mirror,
        // and short-circuits with exact Kleene logic — so it DECIDES precisely where the legacy
        // fold decides (one dataset-level violation on TRUE, none on FALSE), even around a
        // runtime GroupedResult/VariableMetadataResult $-ref it cannot evaluate (UNKNOWN). On
        // UNKNOWN the regular dispatch continues: the row / per-variable native paths evaluate
        // the same full checkExpr (per-row GroupedResult resolution included). Native-authored
        // CheckConditionExpression checks are folded only when broadcast-flagged: the legacy
        // fold treats them as opaque (no Step-1 fold), so general folding would change their
        // documented per-variable semantics. Honoured only when the nativeEval flag is on;
        // otherwise the rule takes the legacy path verbatim.
        if (checkExprOf(rule, ctx) != null && (broadcastOf(rule, ctx) || !(authoredCheckOf(rule,
                ctx) instanceof net.cumba.cdisc.core.model.CheckConditionExpression)))
        {
            net.cumba.cdisc.core.expr.eval.BroadcastFold.Verdict v = net.cumba.cdisc.core.expr.eval.BroadcastFold
                    .fold(Objects.requireNonNull(checkExprOf(rule, ctx)), ctx, false);
            if (v != net.cumba.cdisc.core.expr.eval.BroadcastFold.Verdict.UNKNOWN)
            {
                return datasetBroadcastResult(ruleId, message, outputVars, ctx,
                        v == net.cumba.cdisc.core.expr.eval.BroadcastFold.Verdict.TRUE);
            }
        }

        // Leaf-scope dispatch (PLAN-leaf-scope-domain-inference.md §3.2, phase 4): the rule's
        // evaluation domain — the join of its Check leaves' cursor demands, cached at load — picks
        // the native loop. {VAR,ROW} ⇒ per-(variable, row); {VAR} ⇒ per variable; {} ⇒ one
        // broadcast verdict; {ROW} ⇒ the row path below. The retired Rule_Type gates
        // (isVariableValueCheckType / isMetadataCheckType) and the D4/S4 runtime-kind re-routing
        // were all restatements of exactly this: value() is the cell cursor, a var_* accessor /
        // varname() / the variable_name anchor / a VariableMetadataResult $-ref is the variable
        // cursor, a column / dotted / GroupedResult read is the row cursor (DomainScan, with
        // OperationKinds mirroring BroadcastFold's runtime instanceof tests).
        net.cumba.cdisc.core.expr.ast.Expr checkExpr = checkExprOf(rule, ctx);
        net.cumba.cdisc.core.expr.eval.Domain domain = checkExpr == null ? null
                : domainOf(rule, ctx, checkExpr);
        if (checkExpr != null && domain != null)
        {
            if (domain.varCursor() && domain.rowCursor())
            {
                NativeExecutionRecorder.record(ruleId, NativeExecutionRecorder.Backend.NATIVE);
                return evaluateVariableValueNative(rule, ruleId, message, outputVars, ctx, meta);
            }
            if (domain.varCursor())
            {
                NativeExecutionRecorder.record(ruleId, NativeExecutionRecorder.Backend.NATIVE);
                return evaluateMetadataNative(rule, ruleId, message, outputVars, ctx, meta, true);
            }
            // {} — a dataset-level verdict the fold above could not decide: a metadata-accessor
            // Check evaluates once (evaluateBroadcast); a Check with row-operand whole-column
            // verdicts or $-set operators takes the row path and collapses below — exactly the
            // two paths the retired type gates routed these shapes to.
            if (domain.isBroadcast() && (net.cumba.cdisc.core.expr.eval.MetadataExprScan
                    .containsMetadataFunction(checkExpr)
                    || net.cumba.cdisc.core.expr.eval.MetadataExprScan.containsVarname(checkExpr)
                    || net.cumba.cdisc.core.expr.eval.MetadataExprScan
                            .containsVariableNameAnchor(checkExpr))
                    && net.cumba.cdisc.core.expr.eval.MetadataExprScan.isPureMetadata(checkExpr))
            {
                NativeExecutionRecorder.record(ruleId, NativeExecutionRecorder.Backend.NATIVE);
                return evaluateMetadataNative(rule, ruleId, message, outputVars, ctx, meta, false);
            }
        }

        // === Step 1: Dataset-level partial evaluation ===
        // The legacy Step-1/Step-3/Step-4 cascade (partialEvaluateDataset fold, per-variable
        // partialEvaluateVariable loop, CheckEvaluator row residue) is retired: the native
        // routing gates above cover every bundled rule (NativeCorpusFullCoverageTest, 0-legacy
        // static + dynamic). A rule reaching this point without a compiled checkExpr can only be
        // an externally-supplied rule whose Check has no native form — reported as a per-rule
        // ERROR rather than silently evaluated on a removed engine.
        if (checkExprOf(rule, ctx) == null)
        {
            return RuleExecutionResult.builder().ruleId(ruleId).message(message)
                    .violations(List.of()).totalRows(ctx.getTable().getRowCount())
                    .status(RuleExecutionStatus.ERROR)
                    .statusMessage("Rule error — the Check has no native expression form; the "
                            + "legacy evaluator has been retired")
                    .build();
        }

        // === No variable cursor — straight to row evaluation ===
        // {ROW} per row, {} collapsed. Row-basedness decides per-row findings vs the collapse; it
        // stays keyed on Sensitivity, conjoined with the row cursor — proved equivalent to the
        // retired `RuleType.isValueBased()` conjunct on all 3 797 shipped ids (phase 0, Q9/Q14).
        boolean rowBased = rule.getSensitivity() == Sensitivity.RECORD
                && (domain == null || domain.rowCursor());

        // Zero-row datasets (Fix #349 / EC-89, PLAN-zero-row-routing-rekey.md, owner rulings Q1 /
        // Q5 / 6c-1): a rule still executing here must READ A ROW to decide — every rule that can
        // decide correctly without one (metadata, Define, library-level) has already returned
        // upstream: BroadcastFold's datasetBroadcastResult (where every "no records" twin and all
        // 29 pinned zero-row firers return), the leaf-scope {VAR,ROW} / {VAR} / pure-metadata
        // dispatch, and executeGrouped. So on an empty dataset the rule has nothing to check and
        // falls through to the real 0-row table: evaluateRowLevel yields no bits and both
        // branches below add nothing — EXECUTED with zero findings, never SKIPPED (SKIPPED means
        // "a provider or dataset was absent" and the rulespec status channel reads it that way).
        //
        // The synthetic 1-row ZERO-COLUMN table that used to be substituted here for a
        // non-row-based rule is retired. Measured (GROUNDING-zero-row-rekey-2026-08-23): 23
        // shipped rules reached it and 13 of them — all DOMAIN-column rules — reported a SPURIOUS
        // violation, because on a table with no columns DOMAIN folds absent (EC-43 / R14) and both
        // len(DOMAIN) != n and prefix(dataset_name, n) != DOMAIN become true. The key is the row
        // count alone: "row-reading" is a structural property of having got here, not a fact to
        // re-derive from the domain or an enumerated id set.

        // Backend selection: a rule that retained a native-supported Expr evaluates on the native
        // backend when the flag is on; else the legacy engine. There is NO runtime fallback (P7,
        // decision 2) — a native error propagates and surfaces as the rule's ERROR result.
        BitSet violationBits = evaluateRowLevel(rule, ctx);

        // EC-40: resolved once for the dataset, reused for every violating row (D8). Only the
        // row-based branch has per-row identity to key, and only a rule that actually fires needs
        // a key at all — resolving eagerly would pay a Define lookup (and, in FULL mode, a whole
        // library variable walk) plus an INFO line for every rule x dataset that reports nothing.
        RecordKeyResolver.RowKeySpec keySpec = rowBased && !violationBits.isEmpty()
                ? keySpecFor(ctx)
                : RecordKeyResolver.RowKeySpec.NONE;

        if (rowBased)
        {
            // Per-row findings: count every violating row (cardinality) but materialise at most the
            // cap, so a high-cardinality rule cannot exhaust the heap (see ViolationSink).
            int card = violationBits.cardinality();
            int taken = 0;
            for (int r = violationBits.nextSetBit(0); r >= 0
                    && violations.wantsMore(); r = violationBits.nextSetBit(r + 1))
            {
                Map<String, String> values = extractOutputValues(ctx.getTable(), ctx, outputVars,
                        r);
                RowIdentity ri = readRowIdentity(ctx.getTable(), ctx.getDomainName(), r);
                long realRow = ctx.getTable().getRealRowIndex(r);
                violations.store(new Violation(realRow, values, ri.usubjid(), ri.seq(),
                        RecordKeyResolver.readRowKeys(ctx.getTable(), keySpec, r), null,
                        stamp(ctx, () -> new Violation.Unit.Row(realRow))));
                taken++;
            }
            violations.recordSkipped(card - taken);
        }
        else
        {
            // Dataset-level: single violation if any row fires
            int r = violationBits.nextSetBit(0);
            if (r >= 0)
            {
                Map<String, String> values = extractOutputValues(ctx.getTable(), ctx, outputVars,
                        r);
                // r is a real row of the real table: on a 0-row dataset the BitSet is empty and
                // this branch is never entered (Fix #349 retired the synthetic 1-row table).
                // The stamp is the DATASET unit, not the anchor row: this branch is a collapsed
                // {} verdict, and another level of the same rule may reach the same verdict via
                // datasetBroadcastResult or anchor at a different first firing row.
                violations.add(new Violation(ctx.getTable().getRealRowIndex(r), values, null, null,
                        Map.of(), null, stamp(ctx, () -> Violation.Unit.DATASET)));
            }
        }

        return RuleExecutionResult.builder().ruleId(ruleId).message(message)
                .violations(violations.stored()).totalViolationCount(violations.total())
                .totalRows(ctx.getTable().getRowCount()).keySource(keySpec.source()).build();
    }


    /**
     * EC-43 visibility: emit <b>exactly one</b> aggregated INFO line naming the rule, the dataset
     * and every column this execution folded to all-missing because the dataset does not carry it.
     * Absence is legitimate — the operator computes its own polarity over the folded column, which
     * is the house contract — so this is INFO, never WARNING, mirroring the fork's
     * {@code logger.info} in {@code dataframe_operators._absent_target_as_missing} and Fix #133's
     * dropped-group-column log.
     *
     * <p>
     * <b>The bound is one line per (rule, dataset), never per evaluation or per row.</b> That is
     * why the record lives on the {@link EvaluationContext} and is drained here rather than being
     * logged where the fold happens: {@code NativeExprEvaluator} caches one compiled program per
     * {@code Expr} in a <em>static</em> map shared across datasets and threads, so a latch in the
     * plan's closure would log the first dataset only <em>and</em> race, while no latch at all
     * would log once per (dataset × rule × leaf × row-batch). Every context this execution derives
     * — the per-column projections and the Domain-Presence rebuild — shares this one record, so a
     * rule that folds the same column at ten leaves still logs once.
     * </p>
     */
    private static void logAbsentColumnFolds(@Nullable String ruleId, IDataTable evalTable,
            EvaluationContext ctx)
    {
        Set<String> folded = ctx.getAbsentColumnFolds();
        if (folded.isEmpty() || !LOGGER.isLoggable(System.Logger.Level.INFO))
        {
            return;
        }
        // Sorted so the line is stable across runs — the fold record is a concurrent set whose
        // iteration order is neither insertion nor deterministic.
        List<String> columns = new ArrayList<>(folded);
        Collections.sort(columns);
        LOGGER.log(System.Logger.Level.INFO,
                "[{0}] column(s) {1} absent from dataset {2} — evaluated as all-missing (EC-43);"
                        + " a leaf that must not fire there needs a guard",
                ruleId != null ? ruleId : "?", columns, evalTable.getMetaData().getName());
    }


    /**
     * The first {@code Requirements.Datasets} entry this run does not ship, as a skip reason, or
     * {@code null} when every declared dataset is present.
     *
     * <p>
     * ⭐ <b>The predicate is per entry, not per axis</b>, and it is derived rather than authored:
     * {@link AbsentDatasetSkip#resolvesExactOnly} decides whether this rule reaches that name only
     * through a surface that still resolves by exact name. Everything else uses the <b>widened</b>
     * presence fact {@code SplitDomainResolution.isPresentAsDomain} — the same fact
     * {@code ds_exists} and {@code AbsentDatasetSkip} have tested since {@code Fix #358} (D7), so
     * moving a {@code ds_exists("D")} guard into a declaration is predicate-identical by
     * construction.
     * </p>
     *
     * <p>
     * ⚠⚠ Two things this must NOT do, each of which converts a skip into something else:
     * </p>
     * <ul>
     * <li>it must not inherit {@code SplitDomainResolution.resolveTableOrThrow}'s
     * {@code InvalidJoinedDomainException} — that would turn an unmet requirement into a rule
     * ERROR. The {@code DomainResolution} disposition is therefore stated explicitly: {@code Table}
     * ⇒ met, {@code Absent} ⇒ unmet, {@code Invalid} (split parts exist but cannot be unioned) ⇒
     * <b>met</b>, because the data ships and only the union fails;</li>
     * <li>it must not gate on the widened fact where the rule's own reader resolves exact — that
     * un-skips the rule into the {@code W34-C1} flood. See {@code resolvesExactOnly}.</li>
     * </ul>
     *
     * <p>
     * Entries accept the {@code --} wildcard, resolved against the primary exactly as an
     * {@code Operation}'s {@code domain:} is, so {@code SUPP--} is expressible.
     * </p>
     */
    private static @Nullable String describeMissingRequiredDataset(Rule rule, IDataTable table,
            DatasetResolver resolver)
    {
        net.cumba.cdisc.core.model.Requirements requirements = rule.getRequirements();
        if (requirements == null || requirements.getDatasets() == null)
        {
            return null;
        }
        for (String entry : requirements.getDatasets())
        {
            if (entry == null || entry.isBlank())
            {
                continue;
            }
            // resolveWildcard returns its argument unchanged when there is no `--` to substitute
            // and when the primary's name cannot supply a prefix, so it is null only for a null
            // argument — which the guard above has already excluded. The fallback keeps that
            // contract local rather than assumed.
            String resolved = OperationExecutor.resolveWildcard(entry, table);
            String name = resolved != null ? resolved : entry;
            boolean present = AbsentDatasetSkip.resolvesExactOnly(rule,
                    name.toUpperCase(java.util.Locale.ROOT)) ? resolver.resolve(name) != null
                            : SplitDomainResolution.isPresentAsDomain(resolver, name);
            if (!present)
            {
                return "Requirements.Datasets dataset " + name + " not available";
            }
        }
        return null;
    }


    /**
     * Whether any leaf in the {@code Check} tree references a builtin operand with the given prefix
     * ({@code "define_"} / {@code "library_"}), on either the name or value side (a leaf such as
     * {@code define_variable_role != library_variable_role} references both). Drives the
     * operand-based not-available skip gate.
     *
     * <p>
     * Package-private rather than private so {@link ProviderRequirements} can read the
     * <em>same</em> predicate for surface 2 of the {@code Requirements.Library} / {@code .Define}
     * derivation ({@code plans/PLAN-scope-requirements-split.md} &#167;4.5). Copying it there would
     * give the runtime arm and the declared-vs-derived gate two chances to disagree, which is
     * exactly what gate R5 exists to prevent.
     * </p>
     */
    static boolean referencesOperandPrefix(CheckCondition check, String prefix)
    {
        return switch (check)
        {
        case CheckConditionAll all -> all.getConditions().stream()
                .anyMatch(c -> referencesOperandPrefix(c, prefix));
        case CheckConditionAny any -> any.getConditions().stream()
                .anyMatch(c -> referencesOperandPrefix(c, prefix));
        case CheckConditionNot not -> referencesOperandPrefix(not.getCondition(), prefix);
        case CheckConditionLeaf leaf -> leafReferencesPrefix(leaf, prefix);
        default -> false;
        };
    }


    private static boolean leafReferencesPrefix(CheckConditionLeaf leaf, String prefix)
    {
        String name = leaf.getName();
        if (name != null && name.startsWith(prefix))
        {
            return true;
        }
        // The value side can carry a builtin operand (e.g. value: "library_variable_role"), but
        // only when it is NOT a string literal — a genuine literal that happens to start with the
        // prefix must not trip the gate (mirrors the operand-vs-literal guard in
        // CheckConditionOptimizer.evaluateLeafAgainstMetadata).
        var value = leaf.getValue();
        return value != null && value.isTextual() && !Boolean.TRUE.equals(leaf.getValueIsLiteral())
                && value.asText().startsWith(prefix);
    }


    /**
     * Native evaluation of a metadata-check rule whose {@code checkExpr} uses the {@code var_*} /
     * {@code ds_*} accessors. A variable-scope rule is evaluated once per column (with
     * {@code variable_name} bound to that column) and yields one finding per failing variable; a
     * dataset-scope rule is evaluated once. Both use
     * {@link net.cumba.cdisc.core.expr.eval.NativeExprEvaluator#evaluateBroadcast} so the verdict
     * is row-count-independent. A required-but-absent DEFINE / LIBRARY provider yields SKIPPED
     * (D7).
     */
    private static RuleExecutionResult evaluateMetadataNative(Rule rule, @Nullable String ruleId,
            @Nullable String message, List<String> outputVars, EvaluationContext ctx,
            DataTableMeta meta, boolean perVariable)
    {
        net.cumba.cdisc.core.expr.ast.Expr checkExpr = Objects
                .requireNonNull(checkExprOf(rule, ctx));
        var needed = net.cumba.cdisc.core.expr.eval.MetadataExprScan.providerLevelsUsed(checkExpr);
        // The Define universe iterates the ItemDefs, so it needs the provider even when no leaf
        // reads the DEFINE level (the `var_exists(varname())` discriminator reads DATA only):
        // without one the rule is SKIPPED, never silently run over the data columns.
        boolean defineUniverse = rule
                .getVariableUniverse() == net.cumba.cdisc.core.model.VariableUniverse.DEFINE;
        if ((needed.contains(net.cumba.cdisc.core.expr.eval.MetadataLevel.DEFINE) || defineUniverse)
                && ctx.getDefineProvider() == null)
        {
            return metadataSkipped(ruleId, message, ctx, "no Define-XML metadata available");
        }
        // Fix #369 — libraryAnswerable, not `== null`: a degraded provider is non-null and would
        // otherwise serve var_*("LIBRARY") / ds_*("LIBRARY") from the STUDY library. See the
        // library_* operand gate above for why this surface needs its own check.
        if (needed.contains(net.cumba.cdisc.core.expr.eval.MetadataLevel.LIBRARY)
                && !OperationExecutor.libraryAnswerable(ctx.getLibraryProvider()))
        {
            return metadataSkipped(ruleId, message, ctx,
                    ctx.getLibraryProvider() != null
                            ? "the CDISC Library could not be consulted for this run, and LIBRARY-"
                                    + "level metadata may not be answered from a non-library source"
                            : "no Library metadata available");
        }

        List<Violation> violations = new ArrayList<>();
        if (perVariable)
        {
            // §3.3: finding enrichment with the library / define variable metadata is keyed on
            // what the expression READS (providerLevelsUsed), not on a rule-type family. A
            // provider the Check never reads contributes nothing to the finding.
            MetadataProvider libProvider = needed.contains(
                    net.cumba.cdisc.core.expr.eval.MetadataLevel.LIBRARY) ? ctx.getLibraryProvider()
                            : null;
            MetadataProvider defProvider = needed.contains(
                    net.cumba.cdisc.core.expr.eval.MetadataLevel.DEFINE) ? ctx.getDefineProvider()
                            : null;
            boolean isMetadataCheck = true;
            String domainName = ctx.getDomainName();
            int colCount = meta.getColumnCount();
            // DATASET-sensitivity collapse: Python's COREActions.generate_targeted_error_object
            // emits exactly ONE error for a Sensitivity.DATASET rule — `errors_df.iloc[0]`, the
            // FIRST failing row of the per-variable metadata frame (frame is in dataset column
            // order). The per-variable loop here mirrors that frame, so for DATASET sensitivity we
            // stop after the first firing column. RECORD-sensitivity rules keep emitting one
            // finding per failing variable (Python's _generate_errors_by_target_presence).
            boolean datasetSensitivity = rule.getSensitivity() == Sensitivity.DATASET;
            Set<String> derivedOutputVars = derivedOnlyOutputVars(rule);
            Set<String> excludedOutputVars = rule.excludedOutputVariablesOrAuthored();
            // D1 of PLAN-define-item-metadata-parity-929-1081: a
            // DEFINE_ITEM_METADATA_CHECK_AGAINST_LIBRARY rule's row universe is the define.xml
            // ItemDefs (Python's DefineVariablesWithLibraryMetadataDatasetBuilder drives rows from
            // get_define_xml_variables_metadata, left-joined to library on name). Iterate the
            // define
            // provider's variables in ItemDef order rather than the dataset's columns: a
            // define-declared variable absent from the data is still checked, and a data column
            // absent from the define is not. Every other metadata-check family keeps iterating the
            // dataset columns (their Python builders drive rows from the data-variable frame).
            if (rule.getVariableUniverse() == net.cumba.cdisc.core.model.VariableUniverse.DEFINE
                    && ctx.getDefineProvider() != null && domainName != null)
            {
                MetadataProvider universe = ctx.getDefineProvider();
                int defineIndex = 0;
                for (Map<String, String> defVar : universe.getDomainVariables(domainName))
                {
                    String varName = defVar.get("name");
                    if (varName == null)
                    {
                        defineIndex++;
                        continue;
                    }
                    Map<String, Object> perColVars = projectVariablesForColumn(ctx.getVariables(),
                            varName);
                    EvaluationContext colCtx = ctx.toBuilder().variables(perColVars).build();
                    if (net.cumba.cdisc.core.expr.eval.NativeExprEvaluator
                            .evaluateBroadcast(checkExpr, colCtx))
                    {
                        violations.add(buildDefineVariableViolation(defineIndex, varName,
                                outputVars, libProvider, defProvider, domainName,
                                ctx.getVariables(), datasetSensitivity, ctx, derivedOutputVars,
                                excludedOutputVars));
                        if (datasetSensitivity)
                        {
                            break;
                        }
                    }
                    defineIndex++;
                }
            }
            else
            {
                // ⭐ R11 / Plan 2 Phase 6 — the carry-over operands must reach the EVALUATION
                // context, not merely the finding. The verdict below is decided from colCtx, and
                // buildVariableMetadata runs only AFTER a check has already fired (it builds the
                // finding), so an operand populated only there can never influence the verdict.
                // Gated on the expression actually naming one: the candidate lookup costs a
                // library round-trip per column, and no other metadata rule reads them.
                boolean carryOver = isMetadataCheck && referencesCarryOverOperand(checkExpr);
                for (int c = 0; c < colCount; c++)
                {
                    DataTableColumnMeta colMeta = meta.getColumn(c);
                    Map<String, Object> perColVars = projectVariablesForColumn(ctx.getVariables(),
                            colMeta.getName());
                    if (carryOver && libProvider != null && domainName != null)
                    {
                        putCarryOverIfUndefined(perColVars, libProvider, ctx.getTable(), domainName,
                                colMeta.getName());
                    }
                    EvaluationContext colCtx = ctx.toBuilder().variables(perColVars).build();
                    if (net.cumba.cdisc.core.expr.eval.NativeExprEvaluator
                            .evaluateBroadcast(checkExpr, colCtx))
                    {
                        Map<String, Object> varMeta = buildVariableMetadata(colMeta,
                                isMetadataCheck, libProvider, defProvider, domainName,
                                ctx.getTable());
                        violations.add(buildVariableViolation(c, colMeta, varMeta, outputVars,
                                isMetadataCheck, libProvider, defProvider, domainName,
                                ctx.getVariables(), datasetSensitivity, ctx, derivedOutputVars,
                                excludedOutputVars));
                        if (datasetSensitivity)
                        {
                            break;
                        }
                    }
                }
            }
        }
        else if (net.cumba.cdisc.core.expr.eval.NativeExprEvaluator.evaluateBroadcast(checkExpr,
                ctx))
        {
            Map<String, String> values = extractOutputValues(ctx.getTable(), ctx, outputVars, 0);
            violations.add(new Violation(0, values, null, null, Map.of(), null,
                    stamp(ctx, () -> Violation.Unit.DATASET)));
        }
        return RuleExecutionResult.builder().ruleId(ruleId).message(message).violations(violations)
                .totalRows(ctx.getTable().getRowCount()).build();
    }


    /**
     * P3a (generalising Epic B5) + guard-residual D2/D2b — the dataset-level result of a native
     * {@code BroadcastFold} decision. The verdict was computed by the tri-state fold (which
     * evaluates exactly the leaves the legacy {@code partialEvaluateDataset} fold evaluates,
     * natively — presence facts via {@code OperatorRegistry.exists}, dataset facts, runtime-scalar
     * {@code $}-comparisons, the missing-column fold mirror — and short-circuits with the same
     * Kleene collapse). A true verdict emits exactly ONE dataset-level violation with the same
     * output projection ({@link #extractOutputValues} at row 0) as the legacy fold-to-constant
     * path. This is bit-for-bit faithful to the legacy dataset-level verdict.
     */
    private static RuleExecutionResult datasetBroadcastResult(@Nullable String ruleId,
            @Nullable String message, List<String> outputVars, EvaluationContext ctx,
            boolean verdict)
    {
        NativeExecutionRecorder.record(ruleId, NativeExecutionRecorder.Backend.NATIVE);
        List<Violation> violations = new ArrayList<>();
        if (verdict)
        {
            Map<String, String> values = extractOutputValues(ctx.getTable(), ctx, outputVars, 0);
            violations.add(new Violation(0, values, null, null, Map.of(), null,
                    stamp(ctx, () -> Violation.Unit.DATASET)));
        }
        return RuleExecutionResult.builder().ruleId(ruleId).message(message).violations(violations)
                .totalRows(ctx.getTable().getRowCount()).build();
    }


    /**
     * The per-column variable view for the native per-variable paths (P4): a copy of the context
     * variables with every {@link VariableMetadataResult} entry projected to its value FOR the
     * current column ({@code vmr.getForVariable(colName)}) and the {@code variable_name} cursor set
     * — mirroring the legacy Step-3 loop ({@code executeUnified}, Fix #36/#64: LazyValue entries
     * are unwrapped first so the projection sees the materialised type, exactly like the legacy
     * per-variable copy).
     */
    private static Map<String, Object> projectVariablesForColumn(Map<String, Object> variables,
            String colName)
    {
        Map<String, Object> perColVars = LinkedHashMap.newLinkedHashMap(variables.size() + 1);
        for (Map.Entry<String, Object> ve : variables.entrySet())
        {
            Object entryVal = ve.getValue();
            if (entryVal instanceof LazyValue<?> lv)
            {
                entryVal = lv.get();
            }
            if (entryVal instanceof VariableMetadataResult vmr)
            {
                perColVars.put(ve.getKey(), vmr.getForVariable(colName));
            }
            else
            {
                perColVars.put(ve.getKey(), entryVal);
            }
        }
        perColVars.put(VARIABLE_NAME, colName);
        return perColVars;
    }


    private static RuleExecutionResult metadataSkipped(@Nullable String ruleId,
            @Nullable String message, EvaluationContext ctx, String reason)
    {
        return RuleExecutionResult.builder().ruleId(ruleId).message(message).violations(List.of())
                .totalRows(ctx.getTable().getRowCount()).status(RuleExecutionStatus.SKIPPED)
                .statusMessage("Rule skipped — " + reason).build();
    }


    /**
     * The study variable's data type ({@code Char} / {@code Num}) from the column's post-load
     * {@link net.cumba.datatable.values.DataValueType} — the authoritative type, never inferred
     * from {@code nativeType} (a passive source-format record). The Python parity harness mirrors
     * this by giving each study variable the same loaded type, so {@code variable_data_type} agrees
     * across engines.
     */
    private static @Nullable String declaredDataType(DataTableColumnMeta colMeta)
    {
        net.cumba.datatable.values.DataValueType type = colMeta.getType();
        if (type == null)
        {
            return null;
        }
        return type == net.cumba.datatable.values.DataValueType.STRING ? "Char" : "Num";
    }


    /**
     * <b>R11 / Phase 6</b> — publishes the companion's carry-over candidates for one variable name
     * as {@code library_variable_label_values} and {@code library_variable_data_type_values}.
     *
     * <p>
     * ⛔⛔ <b>A "candidate" is a distinct {@code (label, simpleDatatype)} PAIR, never an
     * occurrence.</b> This is the single decision that makes the rule usable: counted per
     * occurrence, the high-multiplicity names ({@code STUDYID}, {@code DOMAIN}, {@code USUBJID})
     * appear in dozens of domains apiece, so the INFO lane would fire on essentially every ADaM
     * dataset. Deduped on the pair, only a handful of names in the entire product have more than
     * one.
     * </p>
     *
     * <p>
     * ⚠⚠ <b>Review finding R-20 — the exact figures this comment used to quote were measured
     * against the WRONG POPULATION and have been removed rather than patched.</b> They came from
     * reading {@code variables_metadata.pkl} directly, while the shipped path goes through
     * {@code getVariableMetadata}, whose Algorithm-B fallback merges the SDTM Model's
     * general-observation variables into every domain. Re-measured through
     * {@code getPublishedVariablesByName} on a real {@code sdtmig/3-4} provider: <b>72</b> domains
     * (not 63), {@code USUBJID} in <b>56</b> (not 55), and <b>four</b> names with more than one
     * pair — {@code IETESTCD}, {@code NHOID}, {@code SREL}, {@code TAETORD} — not two. The DECISION
     * is unaffected (a handful, not dozens); the numbers were not. Quote a figure here only if you
     * measured it through the accessor this method actually calls.
     * </p>
     *
     * <p>
     * ⛔ <b>Whitespace is normalised before dedup and before comparison.</b> Measured: 55 of 56
     * ADaM↔SDTM name collisions agree exactly, and the single failure is {@code CMTRT} —
     * {@code 'Reported Name of Drug, Med, or Therapy '} against {@code '…Therapy'}, differing only
     * by a trailing space. Without this, CDISC's own published metadata raises a false ERROR.
     * </p>
     *
     * <p>
     * ⚑ An empty result publishes NOTHING, so the rule sees no operand and takes its not-applicable
     * row. That is the common case by a wide margin: only 10 of 332 {@code adamig-1-3} variables
     * have any SDTM counterpart at all.
     * </p>
     *
     * @param varMeta
     *            the per-variable metadata map being built
     * @param libProvider
     *            the run's library provider (a companion-wrapped one answers; others return empty)
     * @param variableName
     *            the dataset variable's name
     */
    static void putCarryOverCandidates(Map<String, Object> varMeta, MetadataProvider libProvider,
            String variableName)
    {
        List<MetadataProvider.PublishedVariable> published = libProvider
                .getPublishedVariablesByName(variableName);
        if (published.isEmpty())
        {
            return;
        }
        java.util.SequencedSet<String> pairs = new LinkedHashSet<>();
        java.util.SequencedSet<String> labels = new LinkedHashSet<>();
        java.util.SequencedSet<String> types = new LinkedHashSet<>();
        for (MetadataProvider.PublishedVariable pv : published)
        {
            String label = normaliseLabel(pv.label());
            // ⚠ The TYPE is NOT case-folded. `Char` / `Num` are canonical CDISC tokens with no
            // case variants in the published data, and folding them to `CHAR` / `NUM` would break
            // any comparison against the canonical spelling that var_data_type("DATA") yields —
            // a new trap in exchange for nothing. Case folding is a LABEL problem (R-4).
            String type = normaliseSpace(pv.dataType());
            if (!pairs.add(label + "\u0000" + type))
            {
                continue;
            }
            if (!label.isEmpty())
            {
                labels.add(label);
            }
            if (!type.isEmpty())
            {
                types.add(type);
            }
        }
        if (!labels.isEmpty())
        {
            varMeta.put("library_variable_label_values", List.copyOf(labels));
        }
        if (!types.isEmpty())
        {
            varMeta.put("library_variable_data_type_values", List.copyOf(types));
        }
    }

    /**
     * The bare operands {@link #putCarryOverCandidates} publishes. They are plain
     * {@link net.cumba.cdisc.core.expr.ast.Expr.Ref}s, not {@code var_*} accessor calls, so they
     * resolve at evaluation time through {@code EvaluationContext.resolveVariable} — which is why
     * they have to be in the per-column variable map before the verdict is taken.
     */
    private static final Set<String> CARRY_OVER_OPERANDS = Set.of("library_variable_label_values",
            "library_variable_data_type_values");

    /**
     * <b>R11 / Plan 2 Phase 6</b> — publishes the carry-over candidates for {@code variableName}
     * into {@code target} <em>only when the run's own library has no definition for it</em>. That
     * gate is the "carried over" discriminator: a variable the ADaM standard itself defines is not
     * a carry-over, and its own definition governs.
     *
     * <p>
     * ⚠ This mirrors the {@code else} arm of {@link #buildVariableMetadata}, which publishes the
     * same operands onto the FINDING so {@code Output_Variables} can report them. The two must stay
     * in step: the evaluation copy decides the verdict, the finding copy decides what the report
     * shows.
     * </p>
     */
    static void putCarryOverIfUndefined(Map<String, Object> target, MetadataProvider libProvider,
            @Nullable IDataTable libraryTable, String domainName, String variableName)
    {
        Map<String, String> libMeta = ExprCompiler.libraryVariableMetadata(libProvider,
                libraryTable, domainName, variableName);
        if (libMeta == null || libMeta.isEmpty())
        {
            putCarryOverCandidates(target, libProvider, variableName);
        }
    }


    /**
     * Whether {@code e} names one of {@link #CARRY_OVER_OPERANDS} anywhere in its tree. Used to
     * keep the per-column candidate lookup off every other metadata rule's hot path.
     */
    static boolean referencesCarryOverOperand(net.cumba.cdisc.core.expr.ast.Expr e)
    {
        return switch (e)
        {
        case net.cumba.cdisc.core.expr.ast.Expr.Ref r -> CARRY_OVER_OPERANDS.contains(r.name());
        case net.cumba.cdisc.core.expr.ast.Expr.Binary b -> referencesCarryOverOperand(b.left())
                || referencesCarryOverOperand(b.right());
        case net.cumba.cdisc.core.expr.ast.Expr.And a -> a.parts().stream()
                .anyMatch(RuleRunner::referencesCarryOverOperand);
        case net.cumba.cdisc.core.expr.ast.Expr.Or o -> o.parts().stream()
                .anyMatch(RuleRunner::referencesCarryOverOperand);
        case net.cumba.cdisc.core.expr.ast.Expr.Not n -> referencesCarryOverOperand(n.inner());
        case net.cumba.cdisc.core.expr.ast.Expr.Call c -> c.args().stream()
                .anyMatch(RuleRunner::referencesCarryOverOperand)
                || c.kwargs().values().stream().anyMatch(RuleRunner::referencesCarryOverOperand);
        // A list literal holds Exprs, so `x in [library_variable_label_values]` is reachable in
        // principle; every other literal kind holds a scalar and has no nested reference.
        case net.cumba.cdisc.core.expr.ast.Expr.Lit lit -> lit
                .kind() == net.cumba.cdisc.core.expr.ast.Expr.LitKind.LIST
                && ((List<?>) lit.value()).stream()
                        .anyMatch(m -> m instanceof net.cumba.cdisc.core.expr.ast.Expr member
                                && referencesCarryOverOperand(member));
        };
    }


    /**
     * The candidate-identity normalisation: trim, collapse internal runs of whitespace, and
     * upper-case. {@code null} becomes empty.
     *
     * <p>
     * ⛔⛔ <b>Review finding R-4 — CASE was missing, and CDISC's own metadata needs it.</b> Measured
     * through the shipped {@code getPublishedVariablesByName} on a real {@code sdtmig/3-4}
     * provider: {@code TAETORD} publishes <i>"Planned Order of Element Within Arm"</i> and
     * <i>"…within Arm"</i>, and {@code NHOID} publishes both <i>"Non-Host"</i> and
     * <i>"Non-host"</i>. Without case folding those are two "distinct" candidates from one logical
     * label, and an ADaM copy differing only in case takes the ERROR lane — a false positive on
     * CDISC's own published data. The trailing-space case ({@code CMTRT}) was fixed and the case
     * case was not; the data demonstrably has both.
     * </p>
     *
     * <p>
     * ⚠⚠ <b>This must stay EXACTLY the normalisation an authored rule can express</b>, or the
     * comparison is asymmetric and false-positives again (R-12). Today that is
     * {@code upper(normalize_space(x))} — the {@code normalize_space} builtin exists precisely so
     * this method has an expressible twin. Change one side and you must change the other.
     * </p>
     */
    private static String normaliseLabel(@Nullable String value)
    {
        return normaliseSpace(value).toUpperCase(java.util.Locale.ROOT);
    }


    /** Trims and collapses internal runs of whitespace; {@code null} becomes empty. */
    private static String normaliseSpace(@Nullable String value)
    {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
    }


    /**
     * Builds the per-variable metadata map for variable-level partial evaluation.
     */
    private static Map<String, Object> buildVariableMetadata(DataTableColumnMeta colMeta,
            boolean isMetadataCheck, @Nullable MetadataProvider libProvider,
            @Nullable MetadataProvider defProvider, @Nullable String domainName,
            @Nullable IDataTable libraryTable)
    {
        Map<String, Object> varMeta = new LinkedHashMap<>();
        varMeta.put(VARIABLE_NAME, colMeta.getName());
        varMeta.put(VARIABLE_LABEL, colMeta.getLabel());
        varMeta.put("variable_data_type", declaredDataType(colMeta));
        varMeta.put("variable_length", colMeta.getLength());
        varMeta.put("variable_format", colMeta.getDisplayFormat());

        // For metadata-check rules, inject the CDISC Library metadata (the "library" level),
        // keyed library_variable_*.
        if (isMetadataCheck && libProvider != null && domainName != null)
        {
            // Fix #373 — LIBRARY-level reads are DOMAIN-keyed; domainName is the MEMBER name, so a
            // split member resolved to nothing. The DEFINE block below deliberately keeps
            // domainName: a Define-XML declares one ItemGroupDef per dataset FILE.
            Map<String, String> libMeta = ExprCompiler.libraryVariableMetadata(libProvider,
                    libraryTable, domainName, colMeta.getName());
            if (libMeta != null && !libMeta.isEmpty())
            {
                for (Map.Entry<String, String> e : libMeta.entrySet())
                {
                    varMeta.put("library_variable_" + e.getKey(), e.getValue());
                }
                String simpleDatatype = libMeta.get("simpleDatatype");
                if (simpleDatatype != null)
                {
                    varMeta.put("library_variable_data_type", simpleDatatype);
                }
            }
            else
            {
                // ⭐ R11 — the SDTM carry-over lane. Reached ONLY when the run's own library has no
                // definition for this variable, which is exactly the carried-over case: an ADaM
                // dataset holding a variable ADaM itself does not define. The companion product is
                // then asked by NAME rather than by domain (an ADaM dataset's domain is ADAE/ADSL,
                // never an SDTM domain, so a domain-keyed lookup would find nothing).
                putCarryOverCandidates(varMeta, libProvider, colMeta.getName());
            }
        }
        // …and the sponsor Define-XML metadata (the "define" level), keyed define_variable_*.
        if (isMetadataCheck && defProvider != null && domainName != null)
        {
            Map<String, String> dfMeta = defProvider.getVariableMetadata(domainName,
                    colMeta.getName());
            if (dfMeta != null && !dfMeta.isEmpty())
            {
                for (Map.Entry<String, String> e : dfMeta.entrySet())
                {
                    varMeta.put("define_variable_" + e.getKey(), e.getValue());
                }
                String simpleDatatype = dfMeta.get("simpleDatatype");
                if (simpleDatatype != null)
                {
                    // EC-2 (Q-6b): normalize the raw Define vocab (e.g. "integer") to the
                    // canonical Num/Char class so the legacy kill-switch lane compares like the
                    // native default lane, which already normalizes both operands. Idempotent for
                    // the datatable fallback (Num/Char pass through unchanged).
                    varMeta.put("define_variable_data_type",
                            MetadataNormalizer.normalize(Normalization.TYPE, simpleDatatype));
                }
            }
        }
        return varMeta;
    }


    /**
     * Builds a violation for a variable-level finding.
     */
    private static Violation buildVariableViolation(int colIndex, DataTableColumnMeta colMeta,
            Map<String, Object> varMeta, List<String> outputVars, boolean isMetadataCheck,
            @Nullable MetadataProvider libProvider, @Nullable MetadataProvider defProvider,
            @Nullable String domainName, Map<String, Object> contextVariables,
            boolean datasetSensitivity, EvaluationContext ctx, Set<String> derivedOutputVars,
            Set<String> excludedOutputVars)
    {
        // A variable-metadata violation is not tied to a data row. For DATASET-sensitivity rules
        // the engine's dataset-level convention is row 0 (mirrored by Python's
        // _row_key_from_engine_error, which reports the first row's key); for RECORD-sensitivity
        // rules we keep the column index — it is out of the data-row range, so the canonical
        // row_key resolves to null on both engines. Using colIndex for the dataset case would read
        // an out-of-range row and emit a spurious null row_key (CORE-000550).
        long violationRow = datasetSensitivity ? 0L : colIndex;
        Map<String, String> values = new LinkedHashMap<>();
        values.put(VARIABLE_NAME, colMeta.getName());
        if (colMeta.getLabel() != null)
        {
            values.put(VARIABLE_LABEL, colMeta.getLabel());
        }
        // Include library + define metadata in output for diagnostic purposes. (The synthesized
        // *_variable_data_type alias is operand-side only — see buildVariableMetadata — so it is
        // not
        // re-derived here; an Output_Variables request for it is still served from varMeta below.)
        if (isMetadataCheck && libProvider != null && domainName != null)
        {
            // Fix #373 — same domain-keyed resolution as buildVariableMetadata. This is the
            // Output_Variables PROJECTION: CDISC-CG0010 reports library_variable_role, so leaving
            // it member-keyed would ship a rule that FIRES with an empty reported cell.
            Map<String, String> libMeta = ExprCompiler.libraryVariableMetadata(libProvider,
                    ctx.getTable(), domainName, colMeta.getName());
            if (libMeta != null)
            {
                for (Map.Entry<String, String> e : libMeta.entrySet())
                {
                    values.put("library_variable_" + e.getKey(), e.getValue());
                }
            }
        }
        if (isMetadataCheck && defProvider != null && domainName != null)
        {
            Map<String, String> dfMeta = defProvider.getVariableMetadata(domainName,
                    colMeta.getName());
            if (dfMeta != null)
            {
                for (Map.Entry<String, String> e : dfMeta.entrySet())
                {
                    values.put("define_variable_" + e.getKey(), e.getValue());
                }
            }
        }
        // Filter to Output_Variables if specified
        if (outputVars != null && !outputVars.isEmpty())
        {
            Map<String, String> filtered = new LinkedHashMap<>();
            for (String ov : outputVars)
            {
                if (varMeta.containsKey(ov))
                {
                    Object val = varMeta.get(ov);
                    putUnlessDerivedNull(filtered, ov, val != null ? val.toString() : null,
                            derivedOutputVars);
                }
                else if (ov.startsWith("$") && contextVariables != null
                        && contextVariables.containsKey(ov))
                {
                    Object val = contextVariables.get(ov);
                    if (val instanceof LazyValue<?> lv)
                    {
                        val = lv.get();
                    }
                    putUnlessDerivedNull(filtered, ov, val != null ? scalarToString(val) : null,
                            derivedOutputVars);
                }
                else
                {
                    // EC-37 Phase 3: dataset-scope virtuals resolve from the context (same
                    // sources as the ds_* accessors); anything else falls back to the values
                    // map, where a derived entry that resolves to nothing is omitted rather
                    // than inserted as a null-valued key (authored entries keep the historical
                    // null so no existing fixture flips).
                    String resolved = "record_count".equals(ov)
                            ? Long.toString(ctx.getTable().getRowCount())
                            : ExprCompiler.datasetScopeOperandValue(ctx, ov);
                    putUnlessDerivedNull(filtered, ov, resolved != null ? resolved : values.get(ov),
                            derivedOutputVars);
                }
            }
            return new Violation(violationRow, filtered, null, null, Map.of(), null,
                    stamp(ctx, () -> new Violation.Unit.Column(colMeta.getName(), violationRow)));
        }
        // No Output_Variables left to project (none authored, or every entry excluded): the
        // metadata default still honours the author's exclusions (E-2).
        values.keySet().removeAll(excludedOutputVars);
        return new Violation(violationRow, values, null, null, Map.of(), null,
                stamp(ctx, () -> new Violation.Unit.Column(colMeta.getName(), violationRow)));
    }


    /**
     * EC-37 Phase 3.3 — the omit-don't-null policy, applied to <b>derived</b> effective
     * Output_Variables only: a derived entry that resolves to nothing is dropped from the finding
     * (matching {@code extractOutputValues}' "unresolved entries are intentionally omitted"), while
     * an authored entry keeps today's null-valued-key behaviour so no existing fixture flips.
     */
    private static void putUnlessDerivedNull(Map<String, String> out, String key,
            @Nullable String value, Set<String> derivedOutputVars)
    {
        if (value != null || !derivedOutputVars.contains(key))
        {
            out.put(key, value);
        }
    }


    /** The effective-OV entries that are derived, not authored (EC-37) — the omit-on-null set. */
    private static Set<String> derivedOnlyOutputVars(Rule rule)
    {
        List<String> effective = rule.getEffectiveOutputVariables();
        if (effective == null)
        {
            return Set.of();
        }
        List<String> authored = rule.getOutcome() != null
                && rule.getOutcome().getOutputVariables() != null
                        ? rule.getOutcome().getOutputVariables()
                        : List.of();
        Set<String> derived = new java.util.HashSet<>(effective);
        authored.forEach(derived::remove);
        return derived;
    }


    /**
     * Formats a define-variable attribute value for the violation output. A list-valued attribute
     * ({@code codelist_coded_codes} / {@code codelist_coded_values}) is carried JSON-encoded
     * through the provider channel; emit it instead in the canonical {@code [a, b, c]} form —
     * sorted, no inner quotes — matching the Python reference engine's list stringification (see
     * {@code engine_adapter._engine_value_to_str}, which mirrors {@code java.util.List.toString()}
     * and sorts). Scalar values pass through.
     */
    private static @Nullable String formatDefineOutput(String key, @Nullable String value)
    {
        if (value == null || !("codelist_coded_codes".equals(key)
                || "codelist_coded_values".equals(key) || "codelist_extended_values".equals(key)))
        {
            return value;
        }
        List<String> codes = new ArrayList<>(
                net.cumba.cdisc.core.metadata.DefineMetadataListCodec.decode(value));
        codes.sort(null);
        return "[" + String.join(", ", codes) + "]";
    }


    /**
     * Builds a violation for a define-ItemDef-level finding (D1 path). Unlike
     * {@link #buildVariableViolation}, the finding is keyed on a define variable that need not
     * exist as a dataset column, so the output is sourced from the define provider (the
     * {@code define_variable_*} fields) left-joined to the library provider (the
     * {@code library_variable_*} fields), plus the {@code $}-operation results from the rule
     * context — mirroring Python's merged define-with-library frame.
     */
    private static Violation buildDefineVariableViolation(int defineIndex, String varName,
            List<String> outputVars, @Nullable MetadataProvider libProvider,
            @Nullable MetadataProvider defProvider, @Nullable String domainName,
            Map<String, Object> contextVariables, boolean datasetSensitivity, EvaluationContext ctx,
            Set<String> derivedOutputVars, Set<String> excludedOutputVars)
    {
        // Same row-key convention as buildVariableViolation: DATASET sensitivity reports row 0; a
        // RECORD-sensitivity finding keeps the define index (out of the data-row range, so the
        // canonical row_key resolves to null on both engines).
        long violationRow = datasetSensitivity ? 0L : defineIndex;
        Map<String, String> values = new LinkedHashMap<>();
        values.put(VARIABLE_NAME, varName);
        if (defProvider != null && domainName != null)
        {
            Map<String, String> dfMeta = defProvider.getVariableMetadata(domainName, varName);
            if (dfMeta != null)
            {
                for (Map.Entry<String, String> e : dfMeta.entrySet())
                {
                    values.put("define_variable_" + e.getKey(),
                            formatDefineOutput(e.getKey(), e.getValue()));
                }
                String label = dfMeta.get("label");
                if (label != null)
                {
                    values.put(VARIABLE_LABEL, label);
                }
            }
        }
        if (libProvider != null && domainName != null)
        {
            // Fix #373 — LIBRARY half only; the DEFINE block above keeps the member name.
            Map<String, String> libMeta = ExprCompiler.libraryVariableMetadata(libProvider,
                    ctx.getTable(), domainName, varName);
            if (libMeta != null)
            {
                for (Map.Entry<String, String> e : libMeta.entrySet())
                {
                    values.put("library_variable_" + e.getKey(), e.getValue());
                }
            }
        }
        if (outputVars == null || outputVars.isEmpty())
        {
            values.keySet().removeAll(excludedOutputVars); // E-2, as in buildVariableViolation
            return new Violation(violationRow, values, null, null, Map.of(), null,
                    stamp(ctx, () -> new Violation.Unit.Column(varName, violationRow)));
        }
        Map<String, String> filtered = new LinkedHashMap<>();
        for (String ov : outputVars)
        {
            if (ov.startsWith("$") && contextVariables != null && contextVariables.containsKey(ov))
            {
                Object val = contextVariables.get(ov);
                if (val instanceof LazyValue<?> lv)
                {
                    val = lv.get();
                }
                putUnlessDerivedNull(filtered, ov, val != null ? scalarToString(val) : null,
                        derivedOutputVars);
            }
            else
            {
                // EC-37 Phase 3 — same dataset-scope resolution + omit-don't-null policy as
                // buildVariableViolation.
                String resolved = "record_count".equals(ov)
                        ? Long.toString(ctx.getTable().getRowCount())
                        : ExprCompiler.datasetScopeOperandValue(ctx, ov);
                putUnlessDerivedNull(filtered, ov, resolved != null ? resolved : values.get(ov),
                        derivedOutputVars);
            }
        }
        return new Violation(violationRow, filtered, null, null, Map.of(), null,
                stamp(ctx, () -> new Violation.Unit.Column(varName, violationRow)));
    }


    /**
     * Appends one per-(variable, row) violation for every set bit in {@code rowBits}, resolving the
     * output projection exactly as the legacy per-variable cascade's row block does. Shared by the
     * legacy cascade ({@code executeUnified} Step 4) and the native {@code value()} path
     * ({@link #evaluateVariableValueNative}) so the two produce byte-identical findings (row index
     * + output values incl. {@code variable_name} / {@code variable_label} /
     * {@code variable_value}).
     */
    private static void addVariableRowViolations(ViolationSink violations, BitSet rowBits,
            DataTableColumnMeta colMeta, int colIdx, Map<String, Object> varMeta,
            List<String> outputVars, EvaluationContext ctx, Set<String> derivedOutputVars,
            Set<String> excludedOutputVars)
    {
        // r comes from rowBits, derived from evaluating the real table's rows, so the loop is
        // skipped (no findings) when the dataset has 0 rows; the empty-dataset case is handled by
        // the dataset-level branch in executeUnified, not here. Count every set bit but materialise
        // at most the per-rule cap, shared across this rule's variables (see ViolationSink).
        int card = rowBits.cardinality();
        int taken = 0;
        for (int r = rowBits.nextSetBit(0); r >= 0
                && violations.wantsMore(); r = rowBits.nextSetBit(r + 1))
        {
            Map<String, String> values = new LinkedHashMap<>();
            if (!outputVars.isEmpty())
            {
                // Resolve output variables, handling variable-level contextual names
                for (String ov : outputVars)
                {
                    if (VARIABLE_NAME.equals(ov))
                    {
                        values.put(ov, colMeta.getName());
                    }
                    else if (VARIABLE_LABEL.equals(ov))
                    {
                        putUnlessDerivedNull(values, ov, colMeta.getLabel(), derivedOutputVars);
                    }
                    else if ("variable_value".equals(ov))
                    {
                        // Resolve actual cell value for this variable at this row
                        if (colIdx >= 0)
                        {
                            IDataValue dv = ctx.getTable().getColumn(colIdx).getDataValue(r);
                            values.put(ov, dv.isMissingOrInvalid() ? "" : dv.getValueAsString());
                        }
                    }
                    else if (varMeta.containsKey(ov))
                    {
                        Object val = varMeta.get(ov);
                        putUnlessDerivedNull(values, ov, val != null ? val.toString() : null,
                                derivedOutputVars);
                    }
                    else
                    {
                        // Delegate to standard row-level resolution
                        Map<String, String> rowVal = extractOutputValues(ctx.getTable(), ctx,
                                List.of(ov), r);
                        values.putAll(rowVal);
                    }
                }
            }
            else
            {
                // No Output_Variables specified — include variable metadata + cell value
                values.put(VARIABLE_NAME, colMeta.getName());
                if (colMeta.getLabel() != null)
                {
                    values.put(VARIABLE_LABEL, colMeta.getLabel());
                }
                if (colIdx >= 0)
                {
                    IDataValue dv = ctx.getTable().getColumn(colIdx).getDataValue(r);
                    if (!dv.isMissingOrInvalid())
                    {
                        values.put("variable_value", dv.getValueAsString());
                    }
                }
                values.keySet().removeAll(excludedOutputVars); // E-2, as in buildVariableViolation
            }
            // The stamp carries the (column, row) pair even when the author's Output_Variables
            // exclude variable_name — without it, two columns firing on one row materialise the
            // same values map and first-claim would collapse them into one unit.
            long realRow = ctx.getTable().getRealRowIndex(r);
            violations.store(new Violation(realRow, values, null, null, Map.of(), null,
                    stamp(ctx, () -> new Violation.Unit.Column(colMeta.getName(), realRow))));
            taken++;
        }
        violations.recordSkipped(card - taken);
    }


    /**
     * Native per-(variable, row) evaluation of a {@code value()}-using metadata / value-check rule
     * — the native sibling of the legacy per-variable cascade (Step 3/4 of {@code executeUnified}).
     * For each column it sets the "current variable" cursor ({@code variable_name} → the column
     * name) into a per-column {@link EvaluationContext}, evaluates the rule's {@code checkExpr}
     * row-level via {@link net.cumba.cdisc.core.expr.eval.NativeExprEvaluator}, and appends the
     * per-row findings through {@link #addVariableRowViolations} so the output matches the legacy
     * cascade byte-for-byte. {@code varname()} / metadata operands resolve as broadcast-constant
     * per column (a guard that fails for a variable yields an all-false BitSet ⇒ no findings for
     * it); {@code value()} resolves to that column's cells. A required-but-absent DEFINE / LIBRARY
     * provider yields SKIPPED, matching {@link #evaluateMetadataNative}.
     */
    private static RuleExecutionResult evaluateVariableValueNative(Rule rule,
            @Nullable String ruleId, @Nullable String message, List<String> outputVars,
            EvaluationContext ctx, DataTableMeta meta)
    {
        net.cumba.cdisc.core.expr.ast.Expr checkExpr = Objects
                .requireNonNull(checkExprOf(rule, ctx));
        var needed = net.cumba.cdisc.core.expr.eval.MetadataExprScan.providerLevelsUsed(checkExpr);
        if (needed.contains(net.cumba.cdisc.core.expr.eval.MetadataLevel.DEFINE)
                && ctx.getDefineProvider() == null)
        {
            return metadataSkipped(ruleId, message, ctx, "no Define-XML metadata available");
        }
        // Fix #369 — libraryAnswerable, not `== null`: a degraded provider is non-null and would
        // otherwise serve var_*("LIBRARY") / ds_*("LIBRARY") from the STUDY library. See the
        // library_* operand gate above for why this surface needs its own check.
        if (needed.contains(net.cumba.cdisc.core.expr.eval.MetadataLevel.LIBRARY)
                && !OperationExecutor.libraryAnswerable(ctx.getLibraryProvider()))
        {
            return metadataSkipped(ruleId, message, ctx,
                    ctx.getLibraryProvider() != null
                            ? "the CDISC Library could not be consulted for this run, and LIBRARY-"
                                    + "level metadata may not be answered from a non-library source"
                            : "no Library metadata available");
        }

        // §3.3: enrichment keyed on what the expression reads, never on a rule-type family.
        boolean isMetadataCheck = true;
        MetadataProvider libProvider = needed.contains(
                net.cumba.cdisc.core.expr.eval.MetadataLevel.LIBRARY) ? ctx.getLibraryProvider()
                        : null;
        MetadataProvider defProvider = needed.contains(
                net.cumba.cdisc.core.expr.eval.MetadataLevel.DEFINE) ? ctx.getDefineProvider()
                        : null;
        String domainName = ctx.getDomainName();
        ViolationSink violations = new ViolationSink(ctx.getMaxErrorsPerRule());
        // Guard-residual D4 projection rule: legacy is position-dependent for a per-variable
        // VariableMetadataResult $-ref. A $-NAME-side VMR leaf is folded at Step 3 against the
        // PER-COLUMN PROJECTION, while a textual "$vmr" in a row-leaf VALUE position reaches
        // Step 4's ValueResolver, which has no VMR branch and yields the raw object (P9 review
        // finding 6). So: project the VMR entries per column iff every VMR ref sits in guard
        // position; otherwise keep the raw (unprojected) view, matching the Step-4 contract.
        boolean projectVmr = net.cumba.cdisc.core.expr.eval.BroadcastFold
                .hasVariableMetadataRef(checkExpr, ctx)
                && net.cumba.cdisc.core.expr.eval.BroadcastFold
                        .vmrRefsOnlyInGuardPosition(checkExpr, ctx);
        Set<String> derivedOutputVars = derivedOnlyOutputVars(rule);
        Set<String> excludedOutputVars = rule.excludedOutputVariablesOrAuthored();
        boolean datasetSensitivity = rule.getSensitivity() == Sensitivity.DATASET;
        int colCount = meta.getColumnCount();
        for (int c = 0; c < colCount; c++)
        {
            DataTableColumnMeta colMeta = meta.getColumn(c);
            Map<String, Object> perColVars;
            if (projectVmr)
            {
                perColVars = projectVariablesForColumn(ctx.getVariables(), colMeta.getName());
            }
            else
            {
                perColVars = new LinkedHashMap<>(ctx.getVariables());
                perColVars.put(VARIABLE_NAME, colMeta.getName());
            }
            EvaluationContext colCtx = ctx.toBuilder().variables(perColVars).build();
            BitSet rowBits = net.cumba.cdisc.core.expr.eval.NativeExprEvaluator.evaluate(checkExpr,
                    colCtx);
            if (rowBits.isEmpty())
            {
                continue;
            }
            // §3.3 (owner ruling 5, 2026-08-13): Dataset sensitivity collapses the {VAR,ROW}
            // domain uniformly to its FIRST firing point — one (variable, row) — like every other
            // domain. Corpus-empty combination, defined rather than preserved.
            if (datasetSensitivity)
            {
                BitSet first = new BitSet();
                first.set(rowBits.nextSetBit(0));
                rowBits = first;
            }
            Map<String, Object> varMeta = buildVariableMetadata(colMeta, isMetadataCheck,
                    libProvider, defProvider, domainName, ctx.getTable());
            int colIdx = meta.getColumnIndex(colMeta.getName());
            addVariableRowViolations(violations, rowBits, colMeta, colIdx, varMeta, outputVars, ctx,
                    derivedOutputVars, excludedOutputVars);
            if (datasetSensitivity)
            {
                break;
            }
        }
        return RuleExecutionResult.builder().ruleId(ruleId).message(message)
                .violations(violations.stored()).totalViolationCount(violations.total())
                .totalRows(ctx.getTable().getRowCount()).build();
    }


    /**
     * Executes a rule with Group sensitivity. Iterates unique groups defined by Grouping_Variables,
     * evaluates the Check once per group using aggregated $-variables (via GroupedResult), and
     * produces one violation per failing group.
     */
    private static RuleExecutionResult executeGrouped(Rule rule, @Nullable String ruleId,
            @Nullable String message, CheckCondition check, EvaluationContext ctx)
    {
        // Caller (execute) only routes GROUP-sensitivity rules with non-null, non-empty
        // groupingVariables here. Resolve the -- domain wildcard (e.g. --TESTCD -> LBTESTCD)
        // against the context's domain prefix BEFORE building the grouping index: the table
        // carries domain-resolved column names, so a raw --prefix name matches no column and
        // createIndex would return null — silently yielding zero violations. Mirrors
        // ExprCompiler.resolveDomainPrefix, which every other evaluation path already applies.
        List<String> groupVars = resolveGroupingPrefixes(
                Objects.requireNonNull(rule.effectiveGroupingVariables(), "grouping variables"),
                ctx.getVariableWildcardPrefix() != null ? ctx.getVariableWildcardPrefix()
                        : ctx.getDomainPrefix());
        GroupKeyPolicy groupingPolicy = GroupKeyPolicy.DROP_MISSING_KEYS
                .withDeclared(rule.groupingKeepMissings());
        IDataTable table = ctx.getTable();
        DataTableMeta meta = table.getMetaData();
        long rowCount = table.getRowCount();

        ViolationSink violations = new ViolationSink(ctx.getMaxErrorsPerRule());
        List<String> outputVars = outputVariablesOf(rule, check, ctx);

        // Python parity (mirrors the operator-level validGroupCols filter in
        // GroupSemantics.inconsistentAcrossDatasetViolations): silently drop grouping columns
        // absent from this dataset rather than letting createIndex return null on the full list —
        // which would zero out the rule whenever a single permissible grouping variable (e.g.
        // --SCAT) is absent. When no grouping column survives, the whole dataset is one group
        // (pandas groupby of an empty key list).
        List<String> presentGroupVars = new ArrayList<>(groupVars.size());
        for (String g : groupVars)
        {
            if (g != null && meta.getColumnIndex(g) >= 0)
            {
                presentGroupVars.add(g);
            }
        }

        // The Check is loop-invariant (same table + context for every block; the per-block verdict
        // is any-row-fires, anchored at the block's first flagged row), so evaluate it once on the
        // native
        // backend (B3 — group-sensitivity is native; the legacy evaluator is retired). A missing
        // checkExpr can only be an externally-supplied rule without a native form.
        if (checkExprOf(rule, ctx) == null)
        {
            return RuleExecutionResult.builder().ruleId(ruleId).message(message)
                    .violations(List.of()).totalRows(ctx.getTable().getRowCount())
                    .status(RuleExecutionStatus.ERROR)
                    .statusMessage("Rule error — the Check has no native expression form; the "
                            + "legacy evaluator has been retired")
                    .build();
        }
        BitSet result = evaluateRowLevel(rule, ctx);
        // EC-40: resolved once for the dataset, reused by every group's anchored violation (D8),
        // and only when some row actually fired (see executeUnified for why).
        RecordKeyResolver.RowKeySpec keySpec = result.isEmpty() ? RecordKeyResolver.RowKeySpec.NONE
                : keySpecFor(ctx);

        if (presentGroupVars.isEmpty())
        {
            // No grouping column present: the entire dataset is a single group. A group must
            // never swallow a flagged row (a minority-flagging operator like
            // is_inconsistent_across_dataset marks only the outlier rows, which are rarely
            // first): the group fires when ANY row's verdict fires, anchored at the first
            // flagged row.
            int flagged = result.nextSetBit(0);
            if (rowCount > 0 && flagged >= 0)
            {
                storeGroupedViolation(table, ctx, outputVars, flagged, violations, keySpec,
                        groupUnitOf(ctx, table, null, flagged));
            }
            return RuleExecutionResult.builder().ruleId(ruleId).message(message)
                    .violations(violations.stored()).totalViolationCount(violations.total())
                    .totalRows(rowCount).keySource(keySpec.source()).build();
        }

        int[] keyColIndices = IndexHelper.resolveColumnIndices(meta, presentGroupVars);
        net.cumba.datatable.index.IDataTableIndex index = IndexHelper.createIndex(table,
                presentGroupVars.toArray(String[]::new));
        if (index == null)
        {
            return RuleExecutionResult.builder().ruleId(ruleId).message(message)
                    .violations(violations.stored()).totalViolationCount(violations.total())
                    .totalRows(rowCount).keySource(keySpec.source()).build();
        }

        long blockCount = index.getBlockCount();
        for (long b = 0; b < blockCount; b++)
        {
            net.cumba.datatable.view.IDataTableView block = index.getBlock(b);

            // The rule-level grouping surface. Its shipped default discards a block whose key
            // carries a genuine missing marker; an authored `Grouping.keep_missings: true` keeps
            // it.
            if (keyColIndices != null && !groupingPolicy.keepMissings()
                    && IndexHelper.isBlockKeyMissing(block, table, keyColIndices, groupingPolicy))
            {
                continue;
            }

            // The group fires when ANY of its rows' verdicts fire — not just the block's
            // first (representative) row. A minority-flagging operator marks only the outlier
            // rows of an inconsistent group; under representative-row collection a group whose
            // majority comes first reported nothing, making the outcome depend on physical
            // record order. Anchor the single group-level violation at the first flagged row
            // (an actually-offending record).
            long blockRows = block.getRowCount(table);
            for (long i = 0; i < blockRows; i++)
            {
                long row = block.getRealRow(table, i);
                if (result.get((int) row))
                {
                    storeGroupedViolation(table, ctx, outputVars, row, violations, keySpec,
                            groupUnitOf(ctx, table, keyColIndices, row));
                    break;
                }
            }
        }

        return RuleExecutionResult.builder().ruleId(ruleId).message(message)
                .violations(violations.stored()).totalViolationCount(violations.total())
                .totalRows(rowCount).keySource(keySpec.source()).build();
    }


    /**
     * Records one Group-sensitivity violation anchored at {@code row} (the first flagged row of a
     * failing group), or a skipped-count when the per-rule error cap is reached. Shared by
     * {@link #executeGrouped}'s per-block path and its all-rows single-group fallback.
     *
     * <p>
     * {@code unit} is the group's identity as resolved by the <em>caller</em> (which knows the
     * block), or {@code null} on the single-level path — never derived from the anchor row here:
     * the anchor is the running level's first flagged row, so two levels flagging different rows of
     * one group would key the same group twice.
     * </p>
     */
    private static void storeGroupedViolation(IDataTable table, EvaluationContext ctx,
            List<String> outputVars, long row, ViolationSink violations,
            RecordKeyResolver.RowKeySpec keySpec, Violation.@Nullable Unit unit)
    {
        if (violations.wantsMore())
        {
            Map<String, String> values = extractOutputValues(table, ctx, outputVars, row);
            RowIdentity ri = readRowIdentity(table, ctx.getDomainName(), row);
            violations.store(new Violation(table.getRealRowIndex(row), values, ri.usubjid(),
                    ri.seq(), RecordKeyResolver.readRowKeys(table, keySpec, row), null, unit));
        }
        else
        {
            violations.recordSkipped(1);
        }
    }


    /**
     * The {@linkplain Violation.Unit.Group group unit} of the block containing {@code row}: the
     * resolved grouping-key tuple, read from the flagged row itself (the key columns are constant
     * across a block, so any row of the block yields the block's key). Empty when no grouping
     * column is present — the whole dataset is then one group. {@code null} on the single-level
     * path, like every other stamp.
     */
    private static Violation.@Nullable Unit groupUnitOf(EvaluationContext ctx, IDataTable table,
            int @Nullable [] keyColIndices, long row)
    {
        if (ctx.getLevelPlan() == null)
        {
            return null;
        }
        if (keyColIndices == null || keyColIndices.length == 0)
        {
            return new Violation.Unit.Group(List.of());
        }
        List<String> key = new ArrayList<>(keyColIndices.length);
        for (int c : keyColIndices)
        {
            IDataValue dv = table.getColumn(c).getDataValue(row);
            key.add(dv.isMissingOrInvalid() ? null : dv.getValueAsString());
        }
        // Group's compact constructor takes the defensive unmodifiable copy.
        return new Violation.Unit.Group(key);
    }


    /**
     * Resolves the {@code --} domain-prefix wildcard in a rule's {@code Grouping_Variables} against
     * the evaluation context's VARIABLE wildcard prefix (e.g. {@code --TESTCD} -> {@code LBTESTCD}
     * for domain {@code LB}; {@code MHTESTCD} on an AP dataset; {@code TESTCD} on SUPP), mirroring
     * {@code ExprCompiler.resolveDomainPrefix} — the grouping index and the Check MUST resolve
     * identically or the rule silently under-reports. A non-wildcard name (no leading {@code --},
     * or a dotted reference) or an absent prefix is returned unchanged. Used by
     * {@link #executeGrouped} so the grouping index is built over the domain-resolved column names
     * the table actually carries.
     */
    private static List<String> resolveGroupingPrefixes(List<String> names,
            @Nullable String domainPrefix)
    {
        // EC-36: substitution is unconditional once a prefix exists. The old `length() == 2` gate
        // left --TESTCD raw for every AP dataset (4-char code) and every SUPP dataset (""), so the
        // grouping index collapsed to a single group while the Check resolved correctly — the rule
        // then under-reported instead of failing loudly. Callers now pass the VARIABLE prefix.
        boolean usable = domainPrefix != null;
        List<String> out = new ArrayList<>(names.size());
        for (String name : names)
        {
            if (usable && name != null && name.startsWith("--") && name.indexOf('.') < 0)
            {
                out.add(domainPrefix + name.substring(2));
            }
            else
            {
                out.add(name);
            }
        }
        return out;
    }


    /**
     * Resolves {@code --} prefix wildcards in an {@link net.cumba.cdisc.core.model.Operation}'s
     * {@code name}, {@code domain}, and {@code group} fields. For example, if the domain prefix is
     * {@code "AE"}, {@code "--DTC"} becomes {@code "AEDTC"}.
     * <p>
     * Only the {@code --} SDTM prefix is resolved here — any {@code **} wildcard (used for per-row
     * cross-dataset column references in Match_Datasets contexts, see Fix #5) is deliberately
     * preserved so that downstream per-row resolvers can finish the substitution against the paired
     * dataset at evaluation time.
     * </p>
     * <p>
     * When rewriting occurs, the pre-resolution {@code name} is stashed on the returned operation's
     * {@link net.cumba.cdisc.core.model.Operation#getOriginalName() originalName} field so
     * study-wide operations (e.g. {@code variable_count}, {@code variable_value_count}) can
     * re-resolve the template per iterated dataset.
     * </p>
     * <p>
     * Returns the same operation if no {@code --} wildcards are present.
     * </p>
     */
    private static net.cumba.cdisc.core.model.Operation resolveOperationPrefix(
            net.cumba.cdisc.core.model.Operation op, String prefix, @Nullable String variablePrefix)
    {
        // Delegates to the shared resolver so the legacy operation path and the native
        // inline-operation path (ExprCompiler.inlineOperationResult) apply identical
        // `--`-resolution semantics (including the SUPP/SQAP parent-prefix nuance).
        return OperationExecutor.resolvePrefixes(op, prefix, variablePrefix);
    }


    /**
     * Backwards-compatible overload — see
     * {@link #buildJoinedDatasets(List, IDataTable, DatasetResolver, JoinCache, String)}.
     * {@code ruleId} defaults to {@code null}; per-Match_Dataset DEBUG logs render with {@code [?]}
     * for the rule context.
     */
    static Map<String, JoinLookup> buildJoinedDatasets(@Nullable List<MatchDataset> matchDatasets,
            IDataTable primaryTable, DatasetResolver resolver, @Nullable JoinCache joinCache)
    {
        return buildJoinedDatasets(matchDatasets, primaryTable, resolver, joinCache, null);
    }


    /**
     * Builds lookup indexes for all Match_Datasets entries. When a {@link JoinCache} is provided,
     * previously built lookups are reused — avoiding repeated index construction when multiple
     * rules join the same reference datasets.
     *
     * @param ruleId
     *            CORE id of the rule whose joins are being prepared, used as a leading
     *            {@code [<ruleId>]} prefix on the per-Match_Dataset DEBUG diagnostics. {@code null}
     *            renders as {@code [?]}.
     */
    static Map<String, JoinLookup> buildJoinedDatasets(@Nullable List<MatchDataset> matchDatasets,
            IDataTable primaryTable, DatasetResolver resolver, @Nullable JoinCache joinCache,
            @Nullable String ruleId)
    {
        if (matchDatasets == null || matchDatasets.isEmpty())
        {
            return Map.of();
        }
        Map<String, JoinLookup> result = new LinkedHashMap<>();
        for (MatchDataset md : matchDatasets)
        {
            String originalName = md.getName();
            if (originalName == null)
            {
                continue;
            }
            // Resolve -- wildcard in dataset name (e.g., SUPP-- → SUPPAE). resolveWildcard only
            // returns null for a null input; originalName is non-null here (guarded above).
            String dsName = Objects.requireNonNull(
                    OperationExecutor.resolveWildcard(originalName, primaryTable),
                    "resolved dataset name");
            String resultKey = originalName.contains("--") ? dsName : originalName;

            if (md.getKeys() == null || md.getKeys().isEmpty())
            {
                // No join keys. Forward RELREC joins are handled by row expansion
                // (RelrecRowExpander) before this method and removed from the list; any other
                // keyless Match_Dataset has no usable join key and is skipped.
                LOGGER.log(System.Logger.Level.DEBUG,
                        "[{0}] Match_Dataset {1} has no join keys, skipping",
                        ruleId != null ? ruleId : "?", dsName);
                continue;
            }

            // Sided-key join (EC-18 / P5c): the primary side matches on the left names and the
            // joined side on the right names. No shipped rule uses this shape yet, so it bypasses
            // the same-named JoinCache (whose SharedJoinedIndex is keyed on one column-name list)
            // and builds a fresh sided lookup — leaving the byte-identical same-named path below
            // untouched.
            if (md.hasSidedKeys())
            {
                IDataTable joined = SplitDomainResolution.resolveTableOrThrow(resolver, dsName,
                        ruleId);
                List<String> leftKeys = md.getKeys();
                List<String> rightKeys = md.getRightKeys();
                if (joined == null || leftKeys == null || rightKeys == null)
                {
                    LOGGER.log(System.Logger.Level.DEBUG,
                            "[{0}] Match_Dataset {1} not available (sided keys)",
                            ruleId != null ? ruleId : "?", dsName);
                    continue;
                }
                DatasetLookup lookup = DatasetLookup.build(dsName, joined, leftKeys, rightKeys);
                if (lookup != null)
                {
                    result.put(resultKey, lookup);
                }
                continue;
            }

            // Key-based join — use cache if available. Fix #358: the joined side resolves via
            // SplitDomainResolution (exact name first, else the split-domain union, memoised on
            // the resolver so the identity-keyed lookup caches see one stable union instance).
            if (joinCache != null)
            {
                DatasetLookup lookup = joinCache.getOrBuildLookup(dsName,
                        SplitDomainResolution.resolveTableOrThrow(resolver, dsName, ruleId),
                        md.getKeys());
                if (lookup != null)
                {
                    result.put(resultKey, lookup);
                }
            }
            else
            {
                IDataTable joined = SplitDomainResolution.resolveTableOrThrow(resolver, dsName,
                        ruleId);
                if (joined == null)
                {
                    LOGGER.log(System.Logger.Level.DEBUG, "[{0}] Match_Dataset {1} not available",
                            ruleId != null ? ruleId : "?", dsName);
                    continue;
                }
                DatasetLookup lookup = DatasetLookup.build(dsName, joined, md.getKeys());
                if (lookup != null)
                {
                    result.put(resultKey, lookup);
                }
            }
        }
        return result;
    }


    /**
     * Fix #15: infers Output_Variables from Check leaves when {@code Outcome.Output_Variables} is
     * absent or empty. Mirrors Python's {@code RuleProcessor._extract_targets_from_conditions}
     * (cdisc_rules_engine/utilities/rule_processor.py:707) — walks the condition tree and, per
     * leaf, contributes the leaf's target column with three Python-parity rules:
     * <ol>
     * <li><b>{@code not_exists} leaves contribute nothing</b> — you do not report a value for a
     * column asserted absent.</li>
     * <li>Every other leaf contributes its target column ({@code name}); this is Python's
     * {@code value.target} (the variable the operator checks), which equals the CORE leaf
     * name.</li>
     * <li>{@code additional_columns_empty} / {@code additional_columns_not_empty} expand to every
     * dataset column matching {@code ^<name>\d+$} (e.g. {@code TSVAL1, TSVAL2, …}).</li>
     * </ol>
     * <p>
     * Preserves first-encounter order, deduplicates. {@code $}-prefixed scalar references are kept
     * (resolved via the {@code $}-branch of {@link #extractOutputValues}); leaves with a null name
     * (e.g. {@code get_dataset} operators) contribute nothing.
     */
    static java.util.SequencedSet<String> collectCheckLeafColumns(CheckCondition check,
            DataTableMeta meta)
    {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        collectCheckLeafColumnsRecursive(check, meta, out);
        return out;
    }


    /**
     * The output-variable list every projection path must use for {@code rule}: the effective
     * (authored + derived) list, or — when that list is empty — the {@code Fix #15} inference from
     * {@code check}'s leaves minus every name the author excluded.
     *
     * <p>
     * EC-37 keeps the inference alive for the legacy {@code additional_columns_*} shape, whose
     * numeric-suffix expansion needs table metadata and is therefore never derived at load time.
     * E-2 is the second half: the inference must not re-project what the author excluded — a list
     * that is empty <em>because</em> of {@code !X} entries stays without X ("absent on every
     * projection path"), so a fully-excluded rule projects the inference minus the exclusions, not
     * the raw inference and not nothing.
     *
     * <p>
     * ⚠ This is the ONE place the pair is computed. It was duplicated across
     * {@link #executeUnified} and the grouped path, and {@link CohortRunner} carried neither half —
     * so a fully-excluded rule projected {@code {}} on the cohort path and the exclusion-filtered
     * inference on the per-rule path, breaking {@code CohortRunner}'s byte-identity contract (found
     * by the {@code Fix #354} review, 2026-08-23). {@code check} must be the <em>resolved</em>
     * check — post {@code --}-prefix substitution — or the inferred names are the unresolved
     * wildcards.
     */
    static List<String> projectedOutputVariables(Rule rule, CheckCondition check,
            DataTableMeta meta)
    {
        List<String> outputVars = rule.effectiveOutputVariablesOrAuthored();
        if (!outputVars.isEmpty())
        {
            return outputVars;
        }
        List<String> inferred = new ArrayList<>(collectCheckLeafColumns(check, meta));
        inferred.removeAll(rule.excludedOutputVariablesOrAuthored());
        return inferred;
    }


    private static void collectCheckLeafColumnsRecursive(CheckCondition check, DataTableMeta meta,
            java.util.SequencedSet<String> out)
    {
        switch (check)
        {
        case CheckConditionAll all ->
        {
            for (var c : all.getConditions())
            {
                collectCheckLeafColumnsRecursive(c, meta, out);
            }
        }
        case CheckConditionAny any ->
        {
            for (var c : any.getConditions())
            {
                collectCheckLeafColumnsRecursive(c, meta, out);
            }
        }
        case CheckConditionNot not -> collectCheckLeafColumnsRecursive(not.getCondition(), meta,
                out);
        case CheckConditionLeaf leaf -> collectLeafTarget(leaf, meta, out);
        default ->
        { // CheckConditionConstant — no column reference
        }
        }
    }


    /** Per-leaf inferred-output-variable contribution; see {@link #collectCheckLeafColumns}. */
    private static void collectLeafTarget(CheckConditionLeaf leaf, DataTableMeta meta,
            java.util.SequencedSet<String> out)
    {
        String name = leaf.getName();
        if (name == null || name.isEmpty())
        {
            return;
        }
        String op = leaf.getOperator();
        if ("var_not_exists".equals(op) || "ds_not_exists".equals(op) || "ds_exists".equals(op))
        {
            return;
        }
        if (("additional_columns_empty".equals(op) || "additional_columns_not_empty".equals(op))
                && meta != null)
        {
            Pattern pat = Pattern.compile("^" + Pattern.quote(name) + "\\d+$");
            for (int i = 0; i < meta.getColumnCount(); i++)
            {
                String col = meta.getColumn(i).getName();
                if (pat.matcher(col).matches())
                {
                    out.add(col);
                }
            }
            return;
        }
        out.add(name);
    }


    /**
     * Fix #17: pre-resolves {@code --} wildcards in output-variable names against the primary
     * domain's prefix. Mirrors Python's {@code _extract_targets_from_output_variables} →
     * {@code _replace_variable_wildcards}. When no domain prefix is available (e.g. Domain Presence
     * Check rules), wildcards are left literal and the downstream column lookup falls through as
     * before.
     *
     * <p>
     * ⚠ This is the raw-entry shape {@code Fix #356} fixed at the three <em>generation</em> sites
     * ({@code RuleGenerator#expandSdtmPrefixRules}, {@code TokenExpander},
     * {@code WildcardExpander}) — and it is deliberately NOT routed through
     * {@link net.cumba.cdisc.core.model.OutputVariableToken#mapName}, because an
     * {@code OutputVariableToken} can never reach here. Every list that arrives is a
     * {@link #projectedOutputVariables} result (:1051, :2065, {@code CohortRunner#outputVarsOf}),
     * i.e. either {@code Rule#effectiveOutputVariablesOrAuthored} — the derived list, whose
     * authored half went through {@code OutputVariableToken.includes} and whose exclusions were
     * subtracted, or the kill-switch fallback's {@code applyExclusions} — or the Fix #15 inference
     * over Check leaf column names, also exclusion-filtered. Marker-bearing entries are gone before
     * resolution, never after it. Re-verify this if a caller ever hands {@code extractOutputValues}
     * a raw {@code Outcome.getOutputVariables()}.
     * </p>
     */
    private static List<String> resolveOutputVarWildcards(List<String> outputVars,
            @Nullable String domainPrefix, @Nullable String datasetPrefix)
    {
        // EC-36: an EMPTY prefix is legitimate (SUPP/SQ: --QNAM -> QNAM), so only a null prefix
        // means "nothing to resolve against".
        if (outputVars == null || outputVars.isEmpty() || domainPrefix == null)
        {
            return outputVars;
        }
        boolean needsResolve = false;
        for (String name : outputVars)
        {
            if (name != null && name.contains("--"))
            {
                needsResolve = true;
                break;
            }
        }
        if (!needsResolve)
        {
            return outputVars;
        }
        List<String> resolved = new ArrayList<>(outputVars.size());
        for (String name : outputVars)
        {
            resolved.add(resolveOutputVarName(name, domainPrefix, datasetPrefix));
        }
        return resolved;
    }


    /**
     * EC-36: an {@code Output_Variables} entry splits the same way a Check value does — the dataset
     * half of a dot-qualified entry keeps the CDISC domain code, the column half takes the variable
     * prefix. Without the split the identical string resolved to {@code SUPPAPMH.QVAL} in the Check
     * and {@code SUPPMH.QVAL} in the finding, so a violation named a different dataset than the one
     * that was actually read.
     */
    private static @Nullable String resolveOutputVarName(@Nullable String name,
            @Nullable String variablePrefix, @Nullable String datasetPrefix)
    {
        if (name == null || !name.contains("--") || variablePrefix == null)
        {
            return name;
        }
        String dsPrefix = datasetPrefix != null ? datasetPrefix : variablePrefix;
        int dot = name.indexOf('.');
        if (dot < 0)
        {
            return name.replace("--", variablePrefix);
        }
        return name.substring(0, dot).replace("--", dsPrefix) + "."
                + name.substring(dot + 1).replace("--", variablePrefix);
    }


    /**
     * EC-12 (Option A): expands any {@code ${*}} wildcard Output_Variables entry to one concrete
     * {@code <foreign>.<column>} entry per matching foreign-dataset column. The expansion is
     * row-invariant (the foreign table's column set is fixed for the run), so the resulting
     * concrete entries then resolve through the existing dot-branch of
     * {@link #extractOutputValues}, and finding-combination folds all of them into a single
     * combined finding.
     *
     * <p>
     * Only driver-free wildcards are expanded here — a wildcard that also carries a {@code ${VAR}}
     * driver is row-dependent and is left untouched for the scalar path. Entries with no
     * placeholder pass through unchanged. When the foreign dataset is not joined/resolvable the
     * wildcard entry is dropped (the unresolved-Output_Variables policy). The common case (no
     * wildcard entry at all) returns the input list without allocating.
     * </p>
     */
    private static List<String> expandOutputVarWildcards(List<String> outputVars,
            EvaluationContext ctx)
    {
        boolean anyWildcard = false;
        for (String v : outputVars)
        {
            if (v != null && OperandSubstitutor.hasPlaceholder(v))
            {
                anyWildcard = true;
                break;
            }
        }
        if (!anyWildcard)
        {
            return outputVars;
        }
        List<String> expanded = new ArrayList<>(outputVars.size());
        for (String v : outputVars)
        {
            if (v == null || !OperandSubstitutor.hasPlaceholder(v))
            {
                expanded.add(v);
                continue;
            }
            OperandSubstitutor.ParsedOperand parsed = OperandSubstitutor.parse(v);
            // Driver-free ${*} wildcard only; drivers => leave for the scalar path.
            if (!(parsed instanceof OperandSubstitutor.Wildcard w) || w.hasDrivers())
            {
                expanded.add(v);
                continue;
            }
            String foreign = w.foreignDataset();
            // Fix #358: a foreign wildcard over a split domain expands against the union's
            // column set instead of nothing.
            IDataTable ft = foreign != null
                    ? SplitDomainResolution.resolveTableOrThrow(ctx.getDatasetResolver(), foreign,
                            ctx.getRuleId())
                    : ctx.getTable();
            if (ft == null)
            {
                continue; // no joined dataset -> omit (unresolved-OV policy)
            }
            // Row-independent: driver-free wildcard, null ctx accepted by toColumnPattern.
            Pattern p = OV_WILDCARD_PATTERNS.computeIfAbsent(v,
                    _ -> OperandSubstitutor.toColumnPattern(w, null, 0L));
            DataTableMeta fm = ft.getMetaData();
            for (int c : WildcardForeignColumnCache.matchingColumns(ft, p))
            {
                String col = fm.getColumn(c).getName();
                expanded.add(foreign != null ? foreign + '.' + col : col);
            }
        }
        return expanded;
    }


    static Map<String, String> extractOutputValues(IDataTable table, EvaluationContext ctx,
            List<String> outputVars, long row)
    {
        if (outputVars == null || outputVars.isEmpty())
        {
            return new LinkedHashMap<>();
        }
        // EC-36: Output_Variables are variable names -> variable prefix (AP suffix / "" for SUPP),
        // so the reported column matches the one the Check actually read.
        outputVars = resolveOutputVarWildcards(outputVars,
                ctx.getVariableWildcardPrefix() != null ? ctx.getVariableWildcardPrefix()
                        : ctx.getDomainPrefix(),
                ctx.getDomainPrefix());
        outputVars = expandOutputVarWildcards(outputVars, ctx);
        DataTableMeta meta = table.getMetaData();
        Map<String, String> values = new LinkedHashMap<>();
        for (String varName : outputVars)
        {
            // Handle $-prefixed variables from Operations
            if (varName.startsWith("$"))
            {
                Object val = ctx.resolveVariable(varName);
                if (val instanceof GroupedResult grouped)
                {
                    // Report the same absent-key default the firing logic used (0 for
                    // record_count, null otherwise) so the rendered $var matches the evaluated
                    // value instead of showing empty for a count that fired as 0.
                    Object groupVal = grouped.getForRowOrDefault(ctx, row);
                    values.put(varName, scalarToString(groupVal));
                }
                else
                {
                    values.put(varName, scalarToString(val));
                }
                continue;
            }
            // EC-37 Phase 3 — dataset-scope virtual variables resolve from the evaluation
            // context, mirroring the ds_* accessor sources exactly (same readMetadata path), so
            // the reported value equals the evaluated one. record_count is the builtin row
            // count. Resolved BEFORE the column lookup because the compiled Check reads the
            // builtin regardless of a like-named column.
            if ("record_count".equals(varName))
            {
                values.put(varName, Long.toString(table.getRowCount()));
                continue;
            }
            String datasetFact = ExprCompiler.datasetScopeOperandValue(ctx, varName);
            if (datasetFact != null)
            {
                values.put(varName, datasetFact);
                continue;
            }
            // Handle dot-qualified references (e.g., DM.DTHDTC)
            int dotIdx = varName.indexOf('.');
            if (dotIdx > 0)
            {
                String dsName = varName.substring(0, dotIdx);
                String colName = varName.substring(dotIdx + 1);
                JoinLookup lookup = ctx.getJoinedDatasets().get(dsName);
                if (lookup != null)
                {
                    String val = lookup.lookup(table, row, colName);
                    // Python omits a dot-qualified joined output variable whose target column does
                    // not exist in the merged frame (e.g. RELREC.**TRT when the parent has no
                    // AETRT); a present-but-missing value is kept as an empty/null value.
                    if (val == null && !lookup.hasColumn(table, row, colName))
                    {
                        continue;
                    }
                    values.put(varName, val != null ? val : "");
                    continue;
                }
                // Fix #18 — a rule evaluated per variable with no row cursor (the {VAR}
                // domain, the former Variable Metadata Check) has no per-row context; the
                // violation reports on the variable identifier itself. Same handling for the
                // filter form <DOMAIN>.<KEY>=<VALUE> (e.g., SUPPAE.QNAM=AETRTEM in
                // CDISC-AD0640): the leaf identifier is its own "value". A {} rule keeps the
                // pre-leaf-scope behaviour (no such output) — CORE-000292's rulespec pins it.
                // The running level's domain when a level plan is present (each rung's plan
                // carries the levels' join), else the rule-level context domain — reading the
                // context alone on a per-level execution would consult the rule's own cached
                // domain instead of the plan the rung actually evaluated under.
                net.cumba.cdisc.core.expr.eval.Domain evalDomain = ctx.getLevelPlan() != null
                        ? ctx.getLevelPlan().domain()
                        : ctx.getEvaluationDomain();
                if (net.cumba.cdisc.core.expr.eval.Domain.VARIABLE.equals(evalDomain))
                {
                    values.put(varName, varName);
                }
                continue;
            }
            // Standard column lookup
            int colIdx = meta.getColumnIndex(varName);
            if (colIdx >= 0)
            {
                if (row >= table.getRowCount())
                {
                    // Dataset-level violation on an empty dataset: the column exists in the
                    // metadata but there is no row to read a value from. Emit empty rather than
                    // dereferencing a non-existent row.
                    values.put(varName, "");
                    continue;
                }
                IDataValue dv = table.getColumn(colIdx).getDataValue(row);
                values.put(varName, dv.isMissingOrInvalid() ? "" : dv.getValueAsString());
                continue;
            }
            // Fallback: try joined datasets for unqualified names (e.g., RFSTDTC
            // which lives in DM but is referenced without the DM. prefix)
            for (JoinLookup lookup : ctx.getJoinedDatasets().values())
            {
                String val = lookup.lookup(table, row, varName);
                if (val != null)
                {
                    values.put(varName, val);
                    break;
                }
            }
            // Unresolved Output_Variables are intentionally omitted from the values map.
            // Historical rationale (the Python lane and its parity adapter were removed in
            // wave 33): Python's actions.py:272 emitted "Not in dataset" into
            // ValidationErrorEntity.value; the adapter (engine_adapter.py:639) stripped it, so
            // the canonical contract was fixed as "post-strip Python" output and is kept.
            // Sentinel emission, when the report needs it, is the JsonReportWriter's concern,
            // not the engine's.
        }
        return values;
    }


    /**
     * Reads the row's {@code USUBJID} and {@code <DOMAIN>SEQ} so callers can attach them as the
     * {@link Violation#getUsubjid()} / {@link Violation#getSeq()} identity fields, mirroring
     * Python's {@code ValidationErrorEntity.USUBJID}/{@code SEQ} top-level fields (Python
     * {@code actions.py:283-302}).
     *
     * <p>
     * Callers restrict invocation to row-iterating paths (record-level / grouped) — dataset- and
     * variable-metadata-level violations have no per-row identity and pass
     * {@link RowIdentity#NONE}. The two fields are gated independently (mirroring Python's separate
     * {@code USUBJID} / {@code _sequence_exists} checks): each is populated when its column is
     * present and left {@code null} otherwise. Returns {@link RowIdentity#NONE} only when neither
     * column is present on the dataset.
     * </p>
     */
    static RowIdentity readRowIdentity(IDataTable table, @Nullable String domainName, long row)
    {
        if (table == null || row < 0)
        {
            return RowIdentity.NONE;
        }
        DataTableMeta meta = table.getMetaData();
        int usubjidCol = meta.getColumnIndex("USUBJID");
        String seqColName = (domainName != null && !domainName.isEmpty()
                ? domainName.toUpperCase(java.util.Locale.ROOT)
                : "") + "SEQ";
        int seqCol = !seqColName.equals("SEQ") ? meta.getColumnIndex(seqColName) : -1;
        if (seqCol < 0)
        {
            // EC-37 D5b — ADaM fallback: the analysis-dataset sequence variable is ASEQ, not
            // <DOMAIN>SEQ. Without this the D5 location-variable exclusion would silently drop
            // the ADaM sequence from findings. Java-only divergence from Python
            // (actions.py _sequence_exists only knows <domain>SEQ), filed under EC-37.
            seqCol = meta.getColumnIndex("ASEQ");
        }
        if (usubjidCol < 0 && seqCol < 0)
        {
            return RowIdentity.NONE; // neither identity column present
        }
        String usubjid = null;
        if (usubjidCol >= 0)
        {
            IDataValue uVal = table.getColumn(usubjidCol).getDataValue(row);
            usubjid = uVal.isMissingOrInvalid() ? "" : uVal.getValueAsString();
        }
        String seq = null;
        if (seqCol >= 0)
        {
            IDataValue sVal = table.getColumn(seqCol).getDataValue(row);
            seq = sVal.isMissingOrInvalid() ? "" : sVal.getValueAsString();
        }
        return new RowIdentity(usubjid, seq);
    }

    /** Identity pair for a violating row. {@link #NONE} represents "neither field applicable". */
    record RowIdentity(@Nullable String usubjid, @Nullable String seq)
    {

        static final RowIdentity NONE = new RowIdentity(null, null);
    }

    /**
     * Resolves the dataset's EC-40 record key <b>once per rule × dataset</b>.
     *
     * <p>
     * Hoisting matters: {@link RecordKeyResolver.RowKeySpec} carries pre-resolved column indices,
     * so calling this per violating row would repeat both the provider lookups and a
     * {@code getColumnIndex} scan per key column on every row. Callers must invoke it outside their
     * violation loop and pass the spec in.
     * </p>
     *
     * @param aCtx
     *            the evaluation context, supplying the table, domain name and providers.
     * @return the resolved spec; {@link RecordKeyResolver.RowKeySpec#NONE} under the default
     *         {@code corej.findingKeys=off} or when nothing resolves.
     */
    static RecordKeyResolver.RowKeySpec keySpecFor(EvaluationContext aCtx)
    {
        return RecordKeyResolver.resolve(aCtx.getTable(), aCtx.getDomainName(),
                FindingKeyMode.configured(), aCtx.getDefineProvider(), aCtx.getLibraryProvider(),
                aCtx.getDatasetResolver(), aCtx.getRuleId());
    }


    /**
     * Forces the prior-operation {@code $}-refs an operation reads through its {@code name} /
     * {@code subtract} fields (the {@code minus} operands), materialising the referenced
     * {@link LazyValue}s into {@code resolved}. Group {@code $}-refs are handled separately by
     * {@code expandGroupRefs}; this covers the operand fields that gathering only the {@code group}
     * list would miss, so set-difference sees its operands' resolved lists. A self-reference
     * ({@code ref.equals(opId)}) is skipped so the in-flight {@link LazyValue} is never forced.
     */
    private static void forceOperandRefs(net.cumba.cdisc.core.model.Operation op,
            @Nullable String opId, Map<String, Object> lazyVars, Map<String, Object> resolved)
    {
        for (String ref : new String[]
        {
                op.getName(), op.getSubtract()
        })
        {
            if (ref == null || !ref.startsWith("$") || ref.equals(opId))
            {
                continue;
            }
            Object v = lazyVars.get(ref);
            if (v instanceof LazyValue<?> lv)
            {
                v = lv.get();
            }
            if (v != null)
            {
                resolved.put(ref, v);
            }
        }
    }


    /**
     * Safely render an Operation-resolved $-variable value as a short String suitable for a
     * finding's output column.
     *
     * <p>
     * Guards against catastrophic memory usage for values that are {@link java.util.Collection}s or
     * {@link java.util.Map}s. Rendering a multi-million-element collection via
     * {@link Object#toString()} would eagerly concatenate every element — easily exceeding the
     * heap. We cap the rendered size and append an ellipsis plus the element count instead.
     * </p>
     *
     * <p>
     * {@code null} → empty string (matches the prior call sites).
     * </p>
     */
    private static String scalarToString(@Nullable Object aValue)
    {
        if (aValue == null)
        {
            return "";
        }
        if (aValue instanceof Number n)
        {
            // EC-39 (Fix #129): render a numeric operation result the way coreJ renders a numeric
            // CELL. `DataValueDouble.getValueAsString()` trims a whole double to "3", and
            // `extractOutputValues` reports plain columns through exactly that path — so without
            // this, the same column reported "10" as a cell but "10.0" as a `max`, which is
            // incoherent inside one finding.
            //
            // Deliberately NOT keyed on the source column's declared type. coreJ stores numerics
            // as DOUBLE by design — `XptTableProvider` because SAS has a single 8-byte float type,
            // `DsjTableProvider` (J2) because an integer-DECLARED Dataset-JSON column may hold a
            // decimal in non-conformant data and must not be truncated — so a type-keyed rule
            // would leave both of those rendering "10.0" while every cell around them renders
            // "10", and would additionally diverge whenever pandas upcasts int64 to float64 on a
            // single missing value.
            return ExprCompiler.canonicalNumberText(n);
        }
        if (aValue instanceof CharSequence || aValue instanceof Boolean)
        {
            return aValue.toString();
        }
        if (aValue instanceof java.util.Collection<?> c)
        {
            return renderCollection(c);
        }
        if (aValue instanceof Map<?, ?> m)
        {
            return renderCollection(m.entrySet());
        }
        if (aValue.getClass().isArray())
        {
            return renderCollection(java.util.Arrays.asList((Object[]) aValue));
        }
        // Fallback for opaque types — bound the length defensively.
        String s = aValue.toString();
        return s.length() > 1024 ? s.substring(0, 1024) + "…" : s;
    }

    // Operation-result Output_Variables (e.g. $allowed_variables from get_model_column_order,
    // $VALID_TERM_CODES from get_codelist_attributes) are rendered through this path and must
    // reproduce the Python engine's full list for parity and report fidelity — Python never
    // truncates. The cap exists only as an OOM backstop for pathological (multi-million-element)
    // collections; it is set far above any realistic list. get_codelist_attributes "Term CCODE"
    // over a full CT package legitimately yields ~25k codes (CORE-001080: 25150 for
    // sdtmct-2024-09-27), so the cap is sized well above that while still bounding a runaway
    // collection.
    private static final int MAX_COLLECTION_RENDER_ELEMENTS = 1_000_000;

    private static String renderCollection(java.util.Collection<?> aCollection)
    {
        int size = aCollection.size();
        if (size == 0)
        {
            return "[]";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        int i = 0;
        for (Object element : aCollection)
        {
            if (i == MAX_COLLECTION_RENDER_ELEMENTS)
            {
                sb.append(", … (").append(size - i).append(" more)");
                break;
            }
            if (i > 0)
            {
                sb.append(", ");
            }
            String rendered = element == null ? "null" : element.toString();
            if (rendered.length() > 64)
            {
                rendered = rendered.substring(0, 64) + "…";
            }
            sb.append(rendered);
            i++;
        }
        sb.append(']');
        return sb.toString();
    }

}
