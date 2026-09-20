package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.CustomLog;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.gen.GeneratedRuleInfo;
import net.cumba.corej.core.gen.GeneratedRulePackage;
import net.cumba.corej.core.gen.RuleCategory;
import net.cumba.corej.core.gen.RuleGenerationReport;
import net.cumba.corej.core.gen.SkippedSourceRule;
import net.cumba.corej.core.gen.TokenExpander;
import net.cumba.corej.core.gen.WildcardExpander;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;

/**
 * Resolves, per dataset, which of the caller's loaded rules apply to it, and materialises their
 * concrete forms.
 *
 * <p>
 * ⚠⚠ <b>Despite the name, this class generates nothing.</b> It is the <i>delivery</i> path for
 * rules the user selected: it gates each loaded rule against the dataset's domain / class /
 * variables ({@link #describeScopeSkip}), expands wildcard templates through
 * {@link WildcardExpander}, specialises the SDTM {@code --} domain prefix
 * ({@link #specialiseStaticRules} → {@link RuleSpecialiser}), and records everything it dropped as
 * a {@link SkippedSourceRule}. Every rule it returns traces back to a rule the caller handed it in
 * {@link #setStaticRules}.
 * </p>
 *
 * <p>
 * It once also <b>minted</b> rules in Java from CDISC Library metadata — twenty categories of them,
 * emitting {@code GEN-*} identities that carried no {@code Standards} block and therefore belonged
 * to no rule package, so they fired regardless of which packages the user had selected. Fix #366
 * disabled them; {@code plans/PLAN-remove-rule-generator.md} deleted them. <b>Rules now come only
 * from rule files.</b>
 * </p>
 *
 * <p>
 * ⚑ Concrete rules produced here bypass {@link RulePackageLoader#load}, so {@code doGenerate}
 * installs the native expression and derives output variables through the loader's own entry points
 * before returning — see the tail of that method.
 * </p>
 */
@CustomLog
public class DatasetRuleResolver
{

    private final MetadataProvider provider;

    // ---- Configurable context (set via setters before calling generate) ----

    /**
     * Fix #119: whether declared Define-XML values (def:Class / def:SubClass) are PREFERRED over
     * the column heuristics for the Scope.Data_Structures / Scope.Subclasses determination
     * ({@code true}), or used only as a fallback ({@code false}). Initialised from
     * {@link net.cumba.corej.core.metadata.AdamDataStructureDetector#defineFirstPreference()} —
     * <b>{@code true} by default since Fix #154</b> (the CLI's {@code --define-first} forces it,
     * {@code -Dcorej.defineFirst=false} opts out); overridable per instance via
     * {@link #setDefineFirst} for tests / embedders.
     */
    private boolean defineFirst = net.cumba.corej.core.metadata.AdamDataStructureDetector
            .defineFirstPreference();

    /** Static rules to expand/pass through. */
    private List<Rule> staticRulesForExpansion = List.of();

    /** Resolves dataset names to data tables for cross-dataset checks. */
    private @Nullable DatasetResolver datasetResolver;

    /** The domain name (e.g., "AE", "DM", "ADSL"). Used for scope filtering and -- expansion. */
    private @Nullable String domainName;

    /**
     * The observation class (e.g., "BASIC DATA STRUCTURE"). Used for scope filtering. Null = no
     * filtering.
     */
    private @Nullable String className;

    /**
     * @param provider
     *            the CDISC Library metadata provider, used for scope resolution and ADaM
     *            data-structure detection
     */
    public DatasetRuleResolver(MetadataProvider provider)
    {
        this.provider = provider;
    }

    // ---- Setters ----


    /**
     * Sets the static rules to expand and pass through. The generator filters these by scope
     * (domain + class) and expands {@code --} prefixes. Non-{@code --} rules pass through
     * unchanged.
     *
     * @param staticRules
     *            rules loaded from static JSON files
     */
    public void setStaticRules(List<Rule> staticRules)
    {
        this.staticRulesForExpansion = staticRules != null ? staticRules : List.of();
    }


    /**
     * Sets the dataset resolver for cross-dataset checks.
     */
    public void setDatasetResolver(DatasetResolver resolver)
    {
        this.datasetResolver = resolver;
    }


    /**
     * Sets the domain name for scope filtering and {@code --} prefix expansion.
     *
     * @param domainName
     *            the domain name (e.g., "AE", "DM", "ADSL")
     */
    public void setDomainName(String domainName)
    {
        this.domainName = domainName;
    }


    /**
     * Sets the observation class for scope filtering. If {@code null}, no class filtering is
     * applied.
     *
     * @param className
     *            the class name (e.g., "BASIC DATA STRUCTURE", "SUBJECT LEVEL ANALYSIS DATASET")
     */
    public void setClassName(String className)
    {
        this.className = className;
    }


    /**
     * Fix #119: overrides the {@code corej.defineFirst} preference for this generator instance —
     * {@code true} prefers declared Define-XML class/subclass values over the column heuristics for
     * the {@code Scope.Data_Structures} / {@code Scope.Subclasses} determination.
     *
     * @param defineFirst
     *            {@code true} to prefer declared Define-XML values
     */
    public void setDefineFirst(boolean defineFirst)
    {
        this.defineFirst = defineFirst;
    }

    // ---- Generate ----


    /**
     * Generates a complete rule package for the given dataset.
     * <p>
     * The package includes:
     * <ul>
     * <li>Loaded rules filtered by scope (domain + class) and passed through</li>
     * <li>Loaded rules with {@code --} prefixes expanded for the domain</li>
     * <li>Wildcard templates from the loaded rules, expanded per matching column</li>
     * </ul>
     * <p>
     * Configure context before calling: {@link #setDomainName}, {@link #setClassName},
     * {@link #setStaticRules}, {@link #setDatasetResolver}.
     *
     * @param table
     *            the dataset to resolve rules for
     * @return the rules that apply to this dataset, ready for execution
     */
    public GeneratedRulePackage generate(IDataTable table)
    {
        String domName;
        if (this.domainName != null)
        {
            domName = this.domainName;
        }
        else
        {
            String tableName = table.getMetaData().getName();
            domName = tableName != null ? tableName : "";
        }
        return doGenerate(table, domName);
    }


    private GeneratedRulePackage doGenerate(IDataTable table, String domName)
    {
        RuleGenerationReport report = new RuleGenerationReport();

        DataTableMeta meta = table.getMetaData();

        // Phase 4 (PLAN-extend-expression-engine): domain prefix for resolving `--` placeholders
        // in Scope.Variables entries (first-row DOMAIN value, falling back to the unsplit table
        // name) — derived once per dataset, mirroring execution-time resolution.
        // EC-36: Scope.Variables entries are variable names -> variable prefix.
        String scopeDomainPrefix = Objects.requireNonNullElse(OperationExecutor
                .variableWildcardPrefix(table, OperationExecutor.domainPrefix(table)), "");

        // Data-driven canonical base name for split detection in domain scope matching (mirrors
        // Python SDTMDatasetMetadata.unsplit_name): reads the DOMAIN/RDOMAIN columns so a dataset
        // named FAAE carrying DOMAIN=FA is recognised as a split of FA — which a name-only
        // heuristic misses. Passed into ScopeMatcher.describeDomainMismatch below as the BASE,
        // against the MEMBER name — see the D125 note in describeScopeSkip for why that pairing is
        // load-bearing and what it cost while the member name never reached the matcher.
        String scopeUnsplitName = OperationExecutor.unsplitNameFromData(table);

        // Fix #117/#118/#119: per-dataset ADaM data-structure + subclass determination for the
        // Scope.Data_Structures / Scope.Subclasses gates. Computed once per dataset; the
        // structure detector mirrors Python's get_data_structure, the subclass detector is the
        // house heuristic, and declared Define-XML values (def:Class / def:SubClass via the
        // provider's declared accessors) participate under the corej.defineFirst preference (see
        // the detector javadocs). Named by the dataset's member name when available (ADSL
        // detection), falling back to the resolved domain name.
        List<String> scopeColumnNames = new ArrayList<>(meta.getColumnCount());
        for (int i = 0; i < meta.getColumnCount(); i++)
        {
            scopeColumnNames.add(meta.getColumn(i).getName());
        }
        String scopeDatasetName = meta.getName() != null ? meta.getName() : domName;
        String declaredClass = provider.getDeclaredDatasetClass(scopeDatasetName);
        List<String> declaredSubClasses = provider.getDeclaredSubClasses(scopeDatasetName);
        // Fix #179: the structure is a SET (most-specific first) — a medical-device BDS dataset is
        // [MEDICAL DEVICE BASIC DATA STRUCTURE, BASIC DATA STRUCTURE], so a rule scoped to the base
        // still covers it while a rule scoped to the variant does not cover a plain BDS dataset.
        List<String> detectedStructures = net.cumba.corej.core.metadata.AdamDataStructureDetector
                .detectAll(scopeDatasetName, scopeColumnNames, declaredClass, defineFirst);
        List<String> detectedSubclasses = net.cumba.corej.core.metadata.AdamSubclassDetector
                .resolve(scopeDatasetName, detectedStructures, scopeColumnNames, declaredSubClasses,
                        defineFirst);
        // Unrecognised declared tokens are ignored by the resolvers — surface them (once per
        // generate() call for this dataset) so a typo'd define declaration doesn't disappear
        // silently. Review finding 9: an unrecognised def:Class gets the same treatment — a
        // declared SDTM class (EVENTS, FINDINGS, …) is expected and stays silent, anything
        // outside both vocabularies is warned.
        for (String declared : declaredSubClasses)
        {
            if (declared != null
                    && !net.cumba.corej.core.metadata.AdamSubclassDetector.SUBCLASS_TOKENS
                            .contains(declared.trim().toUpperCase(Locale.ROOT)))
            {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Dataset {0}: unrecognised Define-XML SubClass declaration \"{1}\""
                                + " ignored (known: {2})",
                        scopeDatasetName, declared,
                        net.cumba.corej.core.metadata.AdamSubclassDetector.SUBCLASS_TOKENS);
            }
        }
        if (declaredClass != null
                && net.cumba.corej.core.metadata.AdamDataStructureDetector
                        .structureTokenFromDeclaredClass(declaredClass) == null
                && !net.cumba.corej.core.metadata.AdamDataStructureDetector.SDTM_CLASS_TOKENS
                        .contains(declaredClass.trim().toUpperCase(Locale.ROOT)))
        {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Dataset {0}: unrecognised Define-XML Class declaration \"{1}\" ignored"
                            + " for data-structure detection",
                    scopeDatasetName, declaredClass);
        }

        // The rules this generator considers: exactly the SELECTED packages' rules the caller
        // handed in. Nothing is merged in behind the caller's back — the engine's built-in
        // rules-templates.json was retired by PLAN-retire-engine-generated-rules.md (Fix #366)
        // precisely because it bypassed package selection.
        // ⚑ Keep the local: the classifier loop below consumes it as its own list.
        List<Rule> allStaticRules = new ArrayList<>(staticRulesForExpansion);

        // Skipped-source-rule audit (Phase 5+): every static input rule the generator considers
        // but doesn't admit to the executed set is recorded here. This is what
        // LibraryValidator turns into SKIPPED runtime listener events so the runtime report can
        // prove every input rule was considered for every dataset.
        // The set keys on Rule#effectiveId() (Core.Id first): file-loaded rules carry no `id` at
        // all since the rule packages became Core.Id-keyed, so keying on the raw `id` left the set
        // empty and the whole audit silently inert.
        List<SkippedSourceRule> skippedSourceRules = new ArrayList<>();
        Set<String> staticRuleSourceIds = java.util.HashSet
                .newHashSet(staticRulesForExpansion.size());
        for (Rule r : staticRulesForExpansion)
        {
            if (r != null && r.effectiveId() != null)
            {
                staticRuleSourceIds.add(r.effectiveId());
            }
        }

        // Single-pass classifier driven by WildcardExpander.tryExpand. Each input rule lands in
        // exactly one bucket:
        // - Expanded(rs) → concrete expansions go to templateExpansions (after the
        // per-template post-filters below). The source template itself
        // is not separately executed.
        // - NotApplicable → rule has no real wildcard tokens (parsing showed every
        // lowercase run was an "unknown marker → literal" — covers
        // literal mixed-case values like "Char" or "Screen Failure").
        // Goes to scopedStaticRules for normal execution.
        // - NoMatch → rule has real wildcards but no dataset column matches.
        // Recorded as a skipped source rule so the runtime listener
        // can render a SKIPPED audit row.
        //
        // Scope (domain + class + variables) is checked first; mismatches go to
        // skippedSourceRules regardless of template-vs-concrete. Executability is not consulted
        // — Python records it on the validation result without affecting execution and Java
        // mirrors that.
        List<Map<String, String>> libVars = provider.getDomainVariables(domName);
        if (libVars.isEmpty())
        {
            LOGGER.log(System.Logger.Level.DEBUG, "No Library variables found for domain {0}",
                    domName);
        }
        // Compute libraryDefinedVars up front — the input to the skipIfLibraryDefined
        // post-expansion filter that fires inside the unified loop below.
        Set<String> libraryDefinedVars = new java.util.HashSet<>();
        for (Map<String, String> lv : libVars)
        {
            String vn = lv.get("name");
            if (vn != null)
            {
                libraryDefinedVars.add(vn);
            }
        }
        List<Rule> scopedStaticRules = new ArrayList<>(allStaticRules.size());
        List<Rule> templateExpansions = new ArrayList<>();
        // Fix #41: count rules skipped specifically because the dataset's class could not be
        // determined and the rule carries a class scope. Used to emit a single discoverable WARN
        // per dataset listing the affected rule count, so the strict-on-null behaviour change
        // doesn't disappear silently into per-rule SKIPPED audit rows.
        int nullClassSkipCount = 0;
        // Fix #124: foreign-metadata source for qualified Scope.Variables entries (DM.ARM),
        // built once per dataset and memoised inside. Null when this generator has no
        // inventory-capable resolver — qualified entries are then ignored (they cannot be decided:
        // without an inventory "dataset absent" is indistinguishable from "resolver blind"), and
        // the count below turns that into one discoverable WARN per dataset.
        ScopeVariableSource scopeForeign = ScopeVariableSource.of(this.datasetResolver, table);
        // Fix #147: the same foreign-metadata source, now also handed to the expander — that
        // omission was the whole of the T3 "shared with ADSL" gap. Built here rather than inside
        // the loop so the Fix #124 memo is shared by the scope gate and the expansion.
        TokenExpander.Context expansionContext = new TokenExpander.Context(scopeForeign,
                this.provider, domName);
        int ignoredQualifiedScopeCount = 0;
        for (Rule r : allStaticRules)
        {
            // Review F4: a load-error-tagged rule (invalid enum value, malformed operand
            // template, invalid scope pattern, …) must surface as an ERROR execution — never
            // silently expand. WildcardExpander.tryExpand builds FRESH Rule objects that would
            // drop the loadError, and the template itself never executes; pass the tagged rule
            // through unmodified so RuleRunner.execute emits its ERROR sentinel (one per
            // dataset). Checked BEFORE scope matching: a loadError may itself record an invalid
            // Scope pattern entry, which the matchers must never re-compile.
            if (r.getLoadError() != null)
            {
                scopedStaticRules.add(r);
                continue;
            }
            String scopeReason = describeScopeSkip(r, domName, meta, scopeDomainPrefix,
                    scopeUnsplitName, detectedStructures, detectedSubclasses, scopeForeign);
            // Review L6: count only rules that actually go on to RUN with their cross-dataset
            // gate ignored. Counting before the scope check inflated the figure with rules that
            // were skipped for an unrelated reason (domain / class / structure) and so never ran
            // unguarded — contradicting the WARN's own wording.
            if (scopeReason == null && scopeForeign == null
                    && ScopeMatcher.hasQualifiedVariableScope(r))
            {
                ignoredQualifiedScopeCount++;
            }
            if (scopeReason != null)
            {
                if (this.className == null && scopeReason.startsWith("dataset class undetermined"))
                {
                    nullClassSkipCount++;
                }
                if (staticRuleSourceIds.contains(r.effectiveId()))
                {
                    skippedSourceRules.add(new SkippedSourceRule(r, scopeReason));
                }
                continue;
            }
            WildcardExpander.ExpansionResult expansion = WildcardExpander.tryExpand(r, meta,
                    expansionContext);
            switch (expansion)
            {
            case WildcardExpander.ExpansionResult.Expanded(List<Rule> expanded) ->
            {
                List<Rule> filtered = applyTemplatePostFilters(r, expanded, domName,
                        libraryDefinedVars, report);
                if (filtered.isEmpty())
                {
                    if (staticRuleSourceIds.contains(r.effectiveId()))
                    {
                        skippedSourceRules.add(new SkippedSourceRule(r,
                                "Template expansions all filtered out by per-rule constraints"));
                    }
                }
                else
                {
                    templateExpansions.addAll(filtered);
                }
            }
            case WildcardExpander.ExpansionResult.NoMatch(String reason) ->
            {
                if (staticRuleSourceIds.contains(r.effectiveId()))
                {
                    skippedSourceRules.add(new SkippedSourceRule(r, reason + " " + domName));
                }
            }
            default ->
                    // NotApplicable — concrete rule, run as-is.
                    scopedStaticRules.add(r);
            }
        }

        // Fix #41: surface the strict-on-null behaviour change once per dataset. Without this
        // log, a class-scoped rule disappearing because the dataset's class is unresolvable
        // would only show up scattered through per-rule SKIPPED audit rows. Logged at WARNING
        // — INFO would be drowned out at typical CLI verbosity, ERROR is too strong because
        // the per-rule path is functioning as designed.
        // Fix #60: the message names the dataset by its library member name (e.g. {@code LBHE})
        // and the CDISC domain code (e.g. {@code LB}) separately, because for split datasets
        // those differ and conflating them in earlier wording made the log ambiguous.
        if (nullClassSkipCount > 0 && this.className == null)
        {
            String memberName = meta.getName() != null ? meta.getName() : domName;
            LOGGER.log(System.Logger.Level.WARNING,
                    "Dataset {0}: {1} class-scoped rule(s) skipped because the domain class for "
                            + "{2} could not be determined. See SKIPPED entries in the runtime "
                            + "report for the full list.",
                    memberName, nullClassSkipCount, domName);
        }

        // Fix #124: the companion discoverability WARN for qualified Requirements.Variables entries
        // that could not be decided. Same shape and rationale as the Fix #41 log above: a rule
        // whose
        // cross-dataset gate does not apply would otherwise leave no trace, since the rule simply
        // runs as if it had no such entry.
        //
        // ⭐⭐ NARROWED 2026-09-10 by the SKIP policy, and the narrowing is the point of the WARN's
        // new wording. The counter's guard is `scopeReason == null`, so a rule counted here RAN. An
        // undecidable entry in `All` or `None` now makes `scopeReason` non-null — the rule is
        // skipped and visible in `skippedSourceRules` — so it can no longer be counted here at all.
        // What remains is exactly the `Any`-leg residual: every qualified entry undecided, yet an
        // unqualified sibling satisfied the leg, so the rule ran with its cross-dataset gate
        // inapplicable. Zero corpus carriers today (no `Any` rule authors a qualified entry), which
        // is why the count is expected to be 0 and why a non-zero one is worth a WARN.
        // ⛔ Do not "simplify" this away as dead code: it becomes live the day an `Any` rule authors
        // a qualified entry, which is precisely the shape nothing else in the stack would report.
        if (ignoredQualifiedScopeCount > 0)
        {
            String memberName = meta.getName() != null ? meta.getName() : domName;
            LOGGER.log(System.Logger.Level.WARNING,
                    "Dataset {0}: {1} rule(s) ran with a qualified Requirements.Variables.Any entry "
                            + "that could not be decided — the dataset resolver cannot enumerate "
                            + "other datasets, and an unqualified sibling satisfied the leg, so the "
                            + "cross-dataset gate did not apply.",
                    memberName, ignoredQualifiedScopeCount);
        }

        List<Rule> rules = new ArrayList<>();

        // ⭐ NOTHING IS GENERATED HERE ANY MORE. Every rule in the returned package came from a
        // package the caller selected: `templateExpansions` holds the wildcard children of those
        // rules, and specialiseStaticRules passes the scoped static rules through, `--`-substituted
        // or unchanged.
        //
        // The twenty in-Java generators that used to run at this point — codelist, pair-one-to-one,
        // dataset label, disallowed variable, MedDRA, WHO Drug, the indexed-variable family,
        // cross-dataset, and the nine Define-XML categories — were deleted by
        // `plans/PLAN-remove-rule-generator.md`, completing the half Fix #366 deferred. They minted
        // `GEN-*` identities carrying no `Standards` block, so they belonged to no package and
        // fired
        // regardless of what the user had selected.

        // Wildcard expansion — already done by the WildcardExpander.tryExpand pass at the top of
        // this method. Drain the precomputed expansions in here rather than at the point of
        // expansion, so wildcard children still precede the `--` substitutions: LibraryValidator's
        // Issue_Summary bundling reads that relative order.
        rules.addAll(templateExpansions);

        // SDTM `--` specialisation (D77), and the pass-through that puts every scoped static rule
        // into the executed set — specialised or unchanged. ⚠ This call IS the corpus delivery
        // path; if it stops running, no rule executes at all.
        specialiseStaticRules(table, domName, scopedStaticRules, rules, report);

        // P5 (PLAN-native-engine-full-coverage): every concrete rule this generator produced —
        // wildcard-template expansions, placeholder substitutions, SDTM-prefix copies —
        // bypassed RulePackageLoader and so carries no native
        // checkExpr. Install it here through the SAME single retention decision the loader uses
        // (and pre-compile the per-leaf state for parity with loader-loaded rules). Loader-loaded
        // pass-through rules already carry their expression — installNativeExpr is a no-op for
        // them. Expr is a record tree (structural equality), so identical concrete expansions
        // share one compiled program in the NativeExprEvaluator cache across datasets.
        for (Rule rule : rules)
        {
            if (rule != null && rule.getCheckExpr() == null)
            {
                RulePackageLoader.installNativeExpr(rule);
            }
            // EC-37: generated rules get the same effective-Output_Variables derivation as
            // loader-loaded ones (idempotent re-derivation for loader-loaded pass-throughs).
            RulePackageLoader.deriveOutputVariables(rule);
        }

        return new GeneratedRulePackage(rules, skippedSourceRules, report);
    }

    // ---- Category 1-3: label, type, required ----

    // Categories 1-4 removed — handled by corpus rules:
    // Cat 1: retired with rules-templates.json (Fix #366)
    // Cat 2: CDISC-CG0012
    // Cat 3: CDISC-CG0014
    // Cat 4: CDISC-CG0016

    // generateVariableOrderRule removed — handled by static rule CDISC-CG0330, which binds
    // $model_column_order = get_model_column_order() and
    // $column_order_from_dataset = get_column_order_from_dataset(), and checks
    // not empty($model_column_order)
    // and not is_ordered_subset_of($column_order_from_dataset, $model_column_order)
    // ⚠ This comment used to name `get_column_order_from_library` and `is_not_ordered_subset_of`.
    // Both operators are real — `get_column_order_from_library()` is bound by ~20 CDISC-AD/SEND
    // rules and `is_not_ordered_subset_of` is a live engine operator (probed by
    // CompilerDispatchDriftGateTest) — but neither is what CG0330 authors. Corrected against
    // rules-src/checks/CDISC/CDISC-CG0330.yaml, 2026-09-19.

    // Categories 7-9 (TESTCD/TEST, TSPARMCD/TSPARM, FL/FN) have no generator here; their
    // built-in template carriers were retired with rules-templates.json (Fix #366).

    // ---- Category 24: Indexed variable rules ----

    // PAT_ANL_ZZ removed; its ANLzzFL/ANLzzFN template carriers were retired with
    // rules-templates.json (Fix #366).

    // ---- The corpus delivery path ----
    //
    // ⚑ Everything between the fields above and applyTemplatePostFilters below used to be in-Java
    // rule generators — twenty categories of them, plus the regex constants and message fragments
    // that served only those. All deleted by plans/PLAN-remove-rule-generator.md. What remains is
    // the delivery path: wildcard post-filtering, `--` prefix expansion, and the scope gate.


    /**
     * Per-template post-expansion filters that run on the rule list returned by
     * {@link WildcardExpander#tryExpand}. Applies {@code skipIfLibraryDefined} and SDTM
     * domain-prefix filtering. Each surviving expansion gets a
     * {@link RuleGenerationReport#addGenerated} entry tagged with
     * {@link RuleCategory#WILDCARD_EXPANSION}. Returns the filtered list, possibly empty when every
     * expansion was rejected.
     *
     * <p>
     * ⚑ Three filters were removed with {@code rules-templates.json} (Fix #366): the family gate
     * ({@code SUFFIX_LABEL} / {@code SUFFIX_TYPE} vs the former
     * {@code RuleCategory.SUFFIX_LABEL_TYPE}, an enum value itself deleted by
     * {@code plans/PLAN-remove-rule-generator.md}), {@code suffixExclusions} and
     * {@code requireAllWildcardsInDataset}. All three steered <em>built-in</em> templates and had
     * zero corpus carriers — measured 2026-08-26 over all 3&nbsp;804 {@code rules-src/checks} files
     * and all shipped {@code rules/} records. {@code skipIfLibraryDefined} (11 corpus carriers) and
     * {@code wildcardExclude} (2) are corpus-legal and stay.
     * </p>
     */
    private List<Rule> applyTemplatePostFilters(Rule template, List<Rule> expansions, String domain,
            Set<String> libraryDefinedVars, RuleGenerationReport report)
    {
        String origCoreId = template.effectiveId();
        Set<String> wildcardNames = WildcardExpander.collectWildcardNames(template);
        String firstWildcard = wildcardNames.isEmpty() ? null : wildcardNames.iterator().next();
        String wildcardSuffix = firstWildcard != null && firstWildcard.startsWith("*")
                ? firstWildcard.substring(1)
                : null;

        boolean isSdtmDomain = domain != null && domain.length() == 2
                && domain.chars().allMatch(Character::isUpperCase);

        List<Rule> kept = new ArrayList<>(expansions.size());
        for (Rule exp : expansions)
        {
            RuleCore expCore = exp.getCore();
            String expCoreId = expCore != null ? expCore.getId() : null;
            if (expCoreId == null || origCoreId == null)
            {
                kept.add(exp);
                continue;
            }
            String primaryCol = expCoreId.substring(origCoreId.length() + 1);

            if (Boolean.TRUE.equals(template.getSkipIfLibraryDefined())
                    && libraryDefinedVars.contains(primaryCol))
            {
                continue;
            }

            if (isSdtmDomain && primaryCol.startsWith(domain)
                    && primaryCol.length() > domain.length())
            {
                String effective = primaryCol.substring(domain.length());
                if (wildcardSuffix != null && wildcardSuffix.equals(effective))
                {
                    continue;
                }
            }

            kept.add(exp);
            report.addGenerated(new GeneratedRuleInfo(expCoreId, RuleCategory.WILDCARD_EXPANSION,
                    primaryCol, exp.getDescription(), "Expanded from " + origCoreId));
        }
        return kept;
    }


    /**
     * The bind-time specialisation pass (D77) over the scoped static rules — the corpus delivery
     * path. Every rule is handed to {@link RuleSpecialiser#specialise}, which resolves everything
     * decidable from (rule &times; dataset metadata) under the single D77c prefix policy: the Check
     * tree and every declared level, the Precondition, the native expressions, Operations,
     * non-{@code Child} {@code Match_Datasets}, {@code Grouping} and the {@code Output_Variables}.
     * Rules that need nothing pass through as the very same instance.
     *
     * <p>
     * &#9888; The former in-house expansion here computed its prefix as
     * {@code domain.substring(0, 2)} while the runtime correction used the EC-36
     * {@code variableWildcardPrefix} — and because this pass ran first and left no {@code --}
     * behind, the correction could never fire (F1 / D77f: {@code APQS} datasets resolved
     * {@code --DTC} to {@code APDTC} instead of {@code QSDTC}). Delegating to the one authority
     * deletes that weaker policy; the {@code LibraryValidator} Fix #59 record documents the same
     * mistake being fixed for Operations.
     * </p>
     */
    void specialiseStaticRules(IDataTable table, String domain, List<Rule> scopedStaticRules,
            List<Rule> rules, RuleGenerationReport report)
    {
        if (scopedStaticRules.isEmpty() || domain == null || domain.isEmpty())
        {
            return;
        }

        for (Rule staticRule : scopedStaticRules)
        {
            // Review F4: never rewrite a load-error-tagged rule — a specialised copy would be a
            // fresh object and the ERROR sentinel contract wants the tagged instance itself.
            // (RuleSpecialiser also guards this; the explicit branch keeps the audit readable.)
            if (staticRule.getLoadError() != null)
            {
                rules.add(staticRule);
                continue;
            }
            if (staticRule.getCheck() == null)
            {
                continue;
            }

            // specialise answers null only for a null rule (its @return); staticRule is not one.
            Rule specialised = Objects
                    .requireNonNull(RuleSpecialiser.specialise(staticRule, table, domain));
            rules.add(specialised);
            if (specialised != staticRule)
            {
                // Derive whatever the source rule left out (PLAN-derive-rule-type-sensitivity
                // phase 7) — a no-op for loader-loaded rules, whose omitted fields were already
                // derived at load and rode over on the copy, but a hand-built or foreign source
                // rule still gets the classifier instead of a blanket default.
                RulePackageLoader.deriveOmittedFields(specialised);
                // The per-domain concrete rule keeps the base rule's CORE id verbatim
                // (base-rule-first, no per-domain suffix) so the IDs match the Python CORE engine
                // and per-domain rows roll up onto the one base id in the report.
                report.addGenerated(new GeneratedRuleInfo(specialised.effectiveId(),
                        RuleCategory.SDTM_PREFIX_EXPANSION, null,
                        specialised.getDescription() != null ? specialised.getDescription()
                                : specialised.effectiveId(),
                        "Specialised from " + staticRule.effectiveId() + " for domain " + domain));
            }
        }
    }

    // ---- Helpers ----


    /**
     * Returns {@code null} if the rule's scope matches this dataset (and is therefore eligible for
     * execution), or a short human-readable reason string if it should be skipped because of domain
     * / dataset-name / class / variable mismatch. Executability is not consulted — every
     * Executability value is treated as eligible, mirroring Python. {@code domainPrefix} (Phase 4)
     * resolves {@code --} placeholders in variable-requirement entries.
     */
    private @Nullable String describeScopeSkip(Rule r, String domName, DataTableMeta meta,
            @Nullable String domainPrefix, String unsplitName, List<String> detectedStructures,
            List<String> detectedSubclasses, @Nullable ScopeVariableSource scopeForeign)
    {
        // ⭐⭐ D125 (PLAN-typed-expression-engine phase 7c): describeDomainMismatch's FIRST argument
        // is the MEMBER name (`FAAE`, `LBCHEM`), its second the dataset's canonical base (`FA`,
        // `LB`) — the matcher derives `isSplit = !domainName.equals(unsplitName)` from exactly that
        // pair. This site used to pass `domName`, which on the production path is
        // LibraryValidator's `CdiscDomainResolver.cdiscDomainOf(table)` (:1080 `setDomainName`).
        // ⛔ BOTH that resolver and `OperationExecutor.unsplitNameFromData` read the row-0 `DOMAIN`
        // cell FIRST and return it, so for every dataset carrying a DOMAIN column the two arguments
        // were equal BY CONSTRUCTION and `isSplit` was permanently FALSE: `Include_Split_Datasets`
        // could never match on either leg, and the member-name leg of Include/Exclude
        // (`Include: ["LB1"]`, a glob `LB*`, `AP--` against `APAE`) was unreachable. The split-base
        // re-test still answered correctly only because `domName` already WAS the base.
        // ⚑ The member name is the same expression `describeDatasetMismatch` needs below, so the
        // two name axes now share one local and cannot drift apart again. The non-vacuity guard
        // that keeps this path reachable is
        // `net.cumba.corej.core.report.SplitScopeProductionPathTest`
        // — it reds, naming this site, if the split branch ever stops being reachable in
        // production.
        String memberName = meta.getName() != null ? meta.getName() : domName;
        String reason = ScopeMatcher.describeDomainMismatch(r, memberName, unsplitName);
        if (reason != null)
        {
            return reason;
        }
        // Scope.Datasets (owner requirement #5) — evaluated immediately after Domains because both
        // are name-level; order only decides which reason a multiply-mismatched rule reports.
        // ⭐ It matches the MEMBER file name, never the domain code, and deliberately WITHOUT the
        // split-base re-test describeDomainMismatch applies: `Domains: ["LB"]` covers LB1/LB2,
        // `Datasets: ["LB"]` covers the file called LB and nothing else. Same local as the domain
        // axis above, so the two cannot disagree about which name is meant.
        reason = ScopeMatcher.describeDatasetMismatch(r, memberName);
        if (reason != null)
        {
            return reason;
        }
        // Fix #41: no {@code className != null} guard here so the strict-on-null contract in
        // {@link ScopeMatcher#describeClassMismatch} can fire when the dataset's class can't be
        // determined — the describer's message names the undetermined-class case explicitly.
        reason = ScopeMatcher.describeClassMismatch(r, this.className);
        if (reason != null)
        {
            return reason;
        }
        // Fix #117/#118: ADaM data-structure + subclass scope gates (Scope.Data_Structures /
        // Scope.Subclasses). Checked after class, before variables — order only decides which
        // reason a multiply-mismatched rule reports.
        reason = ScopeMatcher.describeDataStructureMismatch(r, detectedStructures);
        if (reason != null)
        {
            return reason;
        }
        reason = ScopeMatcher.describeSubclassMismatch(r, detectedSubclasses);
        if (reason != null)
        {
            return reason;
        }
        // Fix #124: `scopeForeign` lets a qualified entry (DM.ARM) be decided against the foreign
        // dataset. It is null when this generator has no inventory-capable resolver — and since
        // 2026-09-10 that is a SKIP, not an ignore (see the policy argument below). The one-time
        // WARN the caller emits therefore now covers only the `Any`-leg residual; its text says so.
        // ⭐ SKIP mirrors RuleRunner's own gate (owner ruling 2026-09-10, disposition (b) of
        // plans/PLAN-qualified-requirements-cross-standard.md §8.4). The two gates evaluate the
        // SAME predicate at two moments, so a split policy between them would be drift by
        // construction: a rule the generator let through would then be skipped at execution, and
        // the audit trail would name two different reasons for one fact.
        return ScopeMatcher.describeVariablesMismatch(r, meta, domainPrefix, scopeForeign,
                ScopeMatcher.QualifiedEntryPolicy.SKIP);
    }

}
