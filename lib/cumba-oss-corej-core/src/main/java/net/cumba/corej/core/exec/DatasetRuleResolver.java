package net.cumba.corej.core.exec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.CustomLog;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.gen.GeneratedRuleInfo;
import net.cumba.corej.core.gen.GeneratedRulePackage;
import net.cumba.corej.core.gen.RuleCategory;
import net.cumba.corej.core.gen.RuleGenerationReport;
import net.cumba.corej.core.gen.SkippedSourceRule;
import net.cumba.corej.core.gen.TokenExpander;
import net.cumba.corej.core.gen.WildcardExpander;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.OutputVariableToken;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Scope;
import net.cumba.corej.core.model.Sensitivity;
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
 * {@link WildcardExpander}, substitutes the SDTM {@code --} domain prefix
 * ({@link #expandSdtmPrefixRules}), and records everything it dropped as a
 * {@link SkippedSourceRule}. Every rule it returns traces back to a rule the caller handed it in
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
        // heuristic
        // misses. Passed into ScopeMatcher.describeDomainMismatch below.
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
        // rules, and expandSdtmPrefixRules passes the scoped static rules through, `--`-substituted
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

        // SDTM `--` prefix expansion, and the pass-through that puts every scoped static rule into
        // the executed set — expanded or unchanged. ⚠ This call IS the corpus delivery path; if it
        // stops running, no rule executes at all.
        expandSdtmPrefixRules(meta, domName, scopedStaticRules, rules, report);

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
    // Cat 2: CORE-001082 (rules-sdtmig-3-4.json)
    // Cat 3: CORE-000355 (rules-sdtmig-3-4.json)
    // Cat 4: CORE-000334 (rules-sdtmig-3-4.json)

    // generateVariableOrderRule removed — handled by static rule CORE-000852
    // (Operations: get_column_order_from_library + get_column_order_from_dataset,
    // Check: is_not_ordered_subset_of)

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
     * Expands static rules that contain {@code --} prefix patterns into concrete rules for the
     * given domain. For example, a rule checking {@code --DTC} becomes a rule checking
     * {@code AEDTC} when the domain is {@code AE}.
     * <p>
     * Only rules whose Check condition tree contains {@code --} prefixed variable names are
     * expanded. Rules without {@code --} are skipped (they don't need expansion).
     * </p>
     */
    void expandSdtmPrefixRules(DataTableMeta meta, String domain, List<Rule> scopedStaticRules,
            List<Rule> rules, RuleGenerationReport report)
    {
        if (scopedStaticRules.isEmpty() || domain == null || domain.isEmpty())
        {
            return;
        }

        String prefix = domain.length() >= 2 ? domain.substring(0, 2) : domain;

        for (Rule staticRule : scopedStaticRules)
        {
            // Review F4: never rewrite a load-error-tagged rule — the `--` expansion below
            // builds a fresh Rule and would drop the loadError. Pass it through unmodified
            // (even with a null Check) so RuleRunner.execute emits its ERROR sentinel.
            if (staticRule.getLoadError() != null)
            {
                rules.add(staticRule);
                continue;
            }
            if (staticRule.getCheck() == null)
            {
                continue;
            }

            // Rules without -- patterns pass through unchanged.
            // ⚑ Plan C §3.3: a `--` in ANY declared level makes the rule expandable — the
            // expansion below resolves every level, so the detection must span every level too, or
            // a weaker level ships the literal "--DTC" and matches no column.
            if (staticRule.checkConditions().stream()
                    .noneMatch(DatasetRuleResolver::containsDashPrefix))
            {
                rules.add(staticRule);
                continue;
            }

            // The per-domain expansion keeps the base rule's CORE id verbatim (e.g.
            // CORE-000767) — base-rule-first, no GEN-EXP-<domain> prefix — so the IDs match the
            // Python CORE engine (which does not append the domain code) and the per-domain rows
            // roll up onto the one base id in the report. The id also tags any prefix-resolution
            // WARN (only fires when prefix is null/non-2-char on a wildcard-bearing Check).
            String origCoreId = staticRule.effectiveId();
            String expandedCoreId = origCoreId;

            // Expand the Check condition tree
            net.cumba.corej.core.model.CheckCondition expandedCheck = CheckConditionTransformer
                    .resolvePrefixes(staticRule.getCheck(), prefix, expandedCoreId);

            // Outcome message and description are kept domain-neutral (the `--` token is NOT
            // substituted) so the single retained Rules_Report instance and the per-domain finding
            // text read uniformly across domains and bundle cleanly. Only the output variables are
            // substituted — they must name the concrete column whose values the finding surfaces.
            String message = staticRule.getOutcome() != null ? staticRule.getOutcome().getMessage()
                    : null;
            List<String> outputVars = staticRule.getOutcome() != null
                    ? staticRule.getOutcome().getOutputVariables()
                    : null;
            if (outputVars != null)
            {
                // Fix #356: resolve the wildcard INSIDE the token. This map used to test the raw
                // entry (`v.startsWith("--")`), so a `!--X` exclusion — whose first character is
                // the `!` marker, not `-` — passed through UNRESOLVED while the Check above was
                // resolved. The `RulePackageLoader.deriveOutputVariables` pass that generate()
                // runs over every produced rule then failed E-3 check 1 on the stale `!--X`
                // ("names nothing the rule derives" — judged against the RESOLVED derived set),
                // tagged a loadError, and the rule reported ENGINE_ERROR on every dataset it
                // targeted.
                outputVars = outputVars.stream()
                        .map(v -> OutputVariableToken.mapName(v,
                                name -> name.startsWith("--") ? prefix + name.substring(2) : name))
                        .toList();
            }

            // Description kept verbatim (domain-neutral, `--` preserved)
            String desc = staticRule.getDescription() != null ? staticRule.getDescription()
                    : origCoreId;

            // buildRule needs the field non-null, so seed it and clear again below when the
            // source rule did not actually author it — the derivation then supplies it.
            Rule expanded = buildRule(expandedCoreId, desc,
                    staticRule.getSensitivity() != null ? staticRule.getSensitivity()
                            : Sensitivity.RECORD,
                    expandedCheck, message, outputVars, domain);
            expanded.setVariableUniverse(staticRule.getVariableUniverse());
            // ⚠⚠ Plan C: the SOURCE rule's Severity must ride onto the expanded child. `buildRule`
            // starts from a fresh `new Rule()`, so any top-level field not named in this block is
            // SILENTLY DROPPED from every `--`-prefix expansion — and the drop is invisible to the
            // loader, both schemas and the writer, because the SOURCE rule still carries the field.
            // Measured when this line was missing: 15 rules / 944 finding rows reported ERROR while
            // the authored rule said Warning. ⇒ a new top-level Rule field must be added HERE as
            // well as at the registration surfaces.
            expanded.setSeverity(staticRule.getSeverity());
            // ⚠⚠ Plan C: and the level-keyed Check with it. `buildRule` above installed only the
            // strictest level's expanded condition; a level map left un-expanded would carry the
            // template's unresolved `--` names into the concrete rule, and — like the Severity drop
            // this comment's neighbour records — the loss is INVISIBLE to the loader, both schemas
            // and the writer, because the SOURCE rule still carries the field. `setCheckLevels`
            // re-derives `check` from the strictest entry, so the two cannot disagree.
            expanded.setCheckLevels(net.cumba.corej.core.model.LevelCheck.mapConditions(
                    staticRule.getCheckLevels(),
                    c -> CheckConditionTransformer.resolvePrefixes(c, prefix, expandedCoreId)));

            // Copy Operations if any
            expanded.setOperations(staticRule.getOperations());
            expanded.setMatchDatasets(staticRule.getMatchDatasets());
            expanded.setGroupingVariables(staticRule.getGroupingVariables());
            expanded.setGrouping(staticRule.getGrouping());
            // Derive whatever the source rule left out, instead of the old blanket
            // Record Data / Record fallback (PLAN-derive-rule-type-sensitivity phase 7). Run after
            // the Operations and Grouping_Variables are attached: both feed the derivation —
            // Grouping_Variables decides `Group`, and a grouped operation makes the rule
            // record-scoped.
            if (staticRule.getSensitivity() == null)
            {
                expanded.setSensitivity(null);
            }
            RulePackageLoader.deriveOmittedFields(expanded);

            rules.add(expanded);
            report.addGenerated(
                    new GeneratedRuleInfo(expandedCoreId, RuleCategory.SDTM_PREFIX_EXPANSION, null,
                            desc, "Expanded from " + origCoreId + " with prefix " + prefix));
        }
    }


    /**
     * Returns {@code true} if the Check condition tree contains any {@code --} prefixed variable
     * names.
     */
    private static boolean containsDashPrefix(net.cumba.corej.core.model.CheckCondition condition)
    {
        return switch (condition)
        {
        case CheckConditionAll all -> all.getConditions().stream()
                .anyMatch(DatasetRuleResolver::containsDashPrefix);
        case net.cumba.corej.core.model.CheckConditionAny any -> any.getConditions().stream()
                .anyMatch(DatasetRuleResolver::containsDashPrefix);
        case net.cumba.corej.core.model.CheckConditionNot not -> containsDashPrefix(
                not.getCondition());
        case CheckConditionLeaf leaf -> (leaf.getName() != null && leaf.getName().startsWith("--"))
                || (leaf.getValue() != null && leaf.getValue().isTextual()
                        && leaf.getValue().asText().contains("--"));
        case net.cumba.corej.core.model.CheckConditionConstant _ -> false;
        case net.cumba.corej.core.model.CheckConditionExpression _ -> false;
        };
    }

    // ---- Define-XML categories 15-23 ----

    // ---- Rule builders ----


    Rule buildRule(@Nullable String coreId, @Nullable String description, Sensitivity sensitivity,
            net.cumba.corej.core.model.CheckCondition check, @Nullable String outcomeMessage,
            @Nullable List<String> outputVars, @Nullable String domain)
    {
        Rule rule = new Rule();
        rule.setId(coreId != null ? deterministicUuid(coreId) : null);

        RuleCore core = new RuleCore();
        core.setId(coreId);
        core.setStatus("Generated");
        core.setVersion("1");
        rule.setCore(core);

        rule.setDescription(description);
        rule.setSensitivity(sensitivity);
        rule.setCheck(check);

        Outcome outcome = new Outcome();
        outcome.setMessage(outcomeMessage);
        if (outputVars != null)
        {
            outcome.setOutputVariables(outputVars);
        }
        rule.setOutcome(outcome);

        if (domain != null)
        {
            Scope scope = new Scope();
            net.cumba.corej.core.model.DomainScope ds = new net.cumba.corej.core.model.DomainScope();
            ds.setInclude(List.of(domain));
            scope.setDomains(ds);
            rule.setScope(scope);
        }

        return rule;
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
        String reason = ScopeMatcher.describeDomainMismatch(r, domName, unsplitName);
        if (reason != null)
        {
            return reason;
        }
        // Scope.Datasets (owner requirement #5) — evaluated immediately after Domains because both
        // are name-level; order only decides which reason a multiply-mismatched rule reports.
        // ⭐ It matches the MEMBER file name, never the domain code, and deliberately WITHOUT the
        // split-base re-test describeDomainMismatch applies: `Domains: ["LB"]` covers LB1/LB2,
        // `Datasets: ["LB"]` covers the file called LB and nothing else. Same expression as the
        // caller's own `scopeDatasetName`, so the two cannot disagree about which name is meant.
        reason = ScopeMatcher.describeDatasetMismatch(r,
                meta.getName() != null ? meta.getName() : domName);
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


    String deterministicUuid(String coreId)
    {
        return UUID.nameUUIDFromBytes(coreId.getBytes(StandardCharsets.UTF_8)).toString();
    }

}
