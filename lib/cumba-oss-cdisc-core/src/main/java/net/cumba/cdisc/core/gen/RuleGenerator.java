package net.cumba.cdisc.core.gen;

import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.CustomLog;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.core.exec.OperationExecutor;
import net.cumba.cdisc.core.exec.ScopeMatcher;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.Outcome;
import net.cumba.cdisc.core.model.OutputVariableToken;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RuleCore;
import net.cumba.cdisc.core.model.Scope;
import net.cumba.cdisc.core.model.Sensitivity;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;

/**
 * Generates conformance rules on the fly from CDISC Library metadata.
 * <p>
 * The generated rules use the same {@link Rule} model as static JSON rules and are executed by the
 * same engine. Rule IDs are deterministic based on domain + variable + category, so results can be
 * compared across runs.
 * </p>
 */
@CustomLog
public class RuleGenerator
{

    private static final String OP_NON_EMPTY = "non_empty";

    private static final String OP_NOT_EXISTS = "var_not_exists";

    private static final String OP_EXISTS = "var_exists";

    private static final String OP_NOT_EQUAL_TO = "not_equal_to";

    private static final String OP_IS_NOT_CONTAINED_BY = "is_not_contained_by";

    private static final String OP_IS_NOT_UNIQUE_RELATIONSHIP = "is_not_unique_relationship";

    private static final String OP_IS_NOT_UNIQUE_SET = "is_not_unique_set";

    private static final String VAR_CMCLAS = "CMCLAS";

    private static final String VAR_CMCLASCD = "CMCLASCD";

    private static final String VAR_CMDECOD = "CMDECOD";

    private static final String VAR_STUDYID = "STUDYID";

    private static final String VAR_USUBJID = "USUBJID";

    private static final String KEY_CODELIST = "codelist";

    private static final String CITED_DEFINE_XML = "Define-XML, ";

    private static final String MSG_VARIABLE_PREFIX = "Variable ";

    private static final String MSG_MUST_BE_PRESENT_WHEN = " must be present when ";

    private static final String MSG_IS_PRESENT_BUT = " is present but ";

    private static final String MSG_IS_MISSING_DOT = " is missing.";

    private static final String MSG_EXISTS_DOT = " exists.";

    private static final String MSG_AND_SEP = " and ";

    private final MetadataProvider provider;

    private final @Nullable DictionaryProvider dictionaryProvider;

    private final @Nullable DefineXMLProvider defineXMLProvider;

    private final EnumSet<RuleCategory> enabledCategories;

    private final @Nullable String ctPackageVersion;

    // ---- Configurable context (set via setters before calling generate) ----

    /**
     * Fix #119: whether declared Define-XML values (def:Class / def:SubClass) are PREFERRED over
     * the column heuristics for the Scope.Data_Structures / Scope.Subclasses determination
     * ({@code true}), or used only as a fallback ({@code false}). Initialised from
     * {@link net.cumba.cdisc.core.metadata.AdamDataStructureDetector#defineFirstPreference()} —
     * <b>{@code true} by default since Fix #154</b> (the CLI's {@code --define-first} forces it,
     * {@code -Dcorej.defineFirst=false} opts out); overridable per instance via
     * {@link #setDefineFirst} for tests / embedders.
     */
    private boolean defineFirst = net.cumba.cdisc.core.metadata.AdamDataStructureDetector
            .defineFirstPreference();

    /** Static rules to expand/pass through. */
    private List<Rule> staticRulesForExpansion = List.of();

    /** Resolves dataset names to data tables for cross-dataset checks. */
    private net.cumba.cdisc.core.exec.@Nullable DatasetResolver datasetResolver;

    /** The domain name (e.g., "AE", "DM", "ADSL"). Used for scope filtering and -- expansion. */
    private @Nullable String domainName;

    /**
     * The observation class (e.g., "BASIC DATA STRUCTURE"). Used for scope filtering. Null = no
     * filtering.
     */
    private @Nullable String className;

    /** Core IDs of existing rules for deduplication. Null = no deduplication. */
    private @Nullable Set<String> existingRuleIds;

    public RuleGenerator(MetadataProvider provider, @Nullable String ctPackageVersion)
    {
        this(provider, null, null, ctPackageVersion, EnumSet.allOf(RuleCategory.class));
    }


    public RuleGenerator(MetadataProvider provider, @Nullable DictionaryProvider dictionaryProvider,
            @Nullable String ctPackageVersion)
    {
        this(provider, dictionaryProvider, null, ctPackageVersion,
                EnumSet.allOf(RuleCategory.class));
    }


    public RuleGenerator(MetadataProvider provider, @Nullable DictionaryProvider dictionaryProvider,
            @Nullable DefineXMLProvider defineXMLProvider, @Nullable String ctPackageVersion)
    {
        this(provider, dictionaryProvider, defineXMLProvider, ctPackageVersion,
                EnumSet.allOf(RuleCategory.class));
    }


    public RuleGenerator(MetadataProvider provider, @Nullable DictionaryProvider dictionaryProvider,
            @Nullable DefineXMLProvider defineXMLProvider, @Nullable String ctPackageVersion,
            EnumSet<RuleCategory> categories)
    {
        this.provider = provider;
        this.dictionaryProvider = dictionaryProvider;
        this.defineXMLProvider = defineXMLProvider;
        this.ctPackageVersion = ctPackageVersion;
        this.enabledCategories = EnumSet.copyOf(categories);
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
    public void setDatasetResolver(net.cumba.cdisc.core.exec.DatasetResolver resolver)
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


    /**
     * Sets existing rule Core IDs for deduplication. Generated rules whose Core ID matches an
     * existing rule are skipped. If {@code null}, no deduplication is applied.
     *
     * @param existingRuleIds
     *            set of Core IDs to deduplicate against
     */
    public void setExistingRuleIds(Set<String> existingRuleIds)
    {
        this.existingRuleIds = existingRuleIds;
    }

    // ---- Generate ----


    /**
     * Generates a complete rule package for the given dataset.
     * <p>
     * The package includes:
     * <ul>
     * <li>Static rules filtered by scope (domain + class) and passed through</li>
     * <li>Static rules with {@code --} prefixes expanded for the domain</li>
     * <li>Generated rules from Library metadata, indexed variables, etc.</li>
     * </ul>
     * <p>
     * Configure context before calling: {@link #setDomainName}, {@link #setClassName},
     * {@link #setStaticRules}, {@link #setDatasetResolver}, {@link #setExistingRuleIds}.
     *
     * @param table
     *            the dataset to generate rules for
     * @return generated rules ready for execution
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
        RuleGenerationReport report = new RuleGenerationReport(domName, provider.getStandard(),
                provider.getVersion(), ctPackageVersion);

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
        List<String> detectedStructures = net.cumba.cdisc.core.metadata.AdamDataStructureDetector
                .detectAll(scopeDatasetName, scopeColumnNames, declaredClass, defineFirst);
        List<String> detectedSubclasses = net.cumba.cdisc.core.metadata.AdamSubclassDetector
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
                    && !net.cumba.cdisc.core.metadata.AdamSubclassDetector.SUBCLASS_TOKENS
                            .contains(declared.trim().toUpperCase(Locale.ROOT)))
            {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Dataset {0}: unrecognised Define-XML SubClass declaration \"{1}\""
                                + " ignored (known: {2})",
                        scopeDatasetName, declared,
                        net.cumba.cdisc.core.metadata.AdamSubclassDetector.SUBCLASS_TOKENS);
            }
        }
        if (declaredClass != null
                && net.cumba.cdisc.core.metadata.AdamDataStructureDetector
                        .structureTokenFromDeclaredClass(declaredClass) == null
                && !net.cumba.cdisc.core.metadata.AdamDataStructureDetector.SDTM_CLASS_TOKENS
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
        boolean wildcardCategoryEnabled = isEnabled(RuleCategory.WILDCARD_EXPANSION);
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
        net.cumba.cdisc.core.exec.ScopeVariableSource scopeForeign = net.cumba.cdisc.core.exec.ScopeVariableSource
                .of(this.datasetResolver, table);
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
                if (!wildcardCategoryEnabled)
                {
                    // Wildcard category disabled — silently drop, mirroring legacy behaviour
                    // when expandWildcardRules was guarded by isEnabled(WILDCARD_EXPANSION).
                    //
                    // Fix #147 — an Expansion:-bearing rule is NOT silently dropped here. That
                    // mechanism postdates the legacy behaviour this branch mirrors, and its whole
                    // point is that a template which checks nothing must say so.
                    if (r.getExpansion() != null && !r.getExpansion().isEmpty()
                            && staticRuleSourceIds.contains(r.effectiveId()))
                    {
                        skippedSourceRules.add(new SkippedSourceRule(r,
                                "Expansion produced rules but the WILDCARD_EXPANSION rule"
                                        + " category is disabled"));
                    }
                    continue;
                }
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

        // Fix #124: the companion discoverability WARN for qualified Scope.Variables entries that
        // could not be decided. Same shape and rationale as the Fix #41 log above: a rule whose
        // cross-dataset gate is silently ignored would otherwise leave no trace at all, since the
        // rule simply runs as if it had no such entry.
        if (ignoredQualifiedScopeCount > 0)
        {
            String memberName = meta.getName() != null ? meta.getName() : domName;
            LOGGER.log(System.Logger.Level.WARNING,
                    "Dataset {0}: {1} rule(s) carry a qualified Scope.Variables entry but the "
                            + "dataset resolver cannot enumerate other datasets — those entries "
                            + "were ignored and the rules ran unguarded.",
                    memberName, ignoredQualifiedScopeCount);
        }

        List<Rule> rules = new ArrayList<>();

        // Categories 1-4 handled by corpus rules:
        // Cat 1 (label): retired with rules-templates.json (Fix #366) — the corpus carriers are
        // CDISC-CG0303 / FDA-SD0063 / … , none of them bound to SDTMIG 3.3 or 3.4
        // Cat 2 (type): CORE-001082 in rules-sdtmig-3-4.json (VMCALM rule type)
        // Cat 3 (required): CORE-000355 in rules-sdtmig-3-4.json (required_variables Operation)
        // Cat 4 (expected): CORE-000334 in rules-sdtmig-3-4.json (expected_variables Operation)
        // Cat 5 (order): CORE-000852 in rules-sdtmig-3-4.json (get_column_order_from_library)

        // Category 6: non-extensible codelist values (require Library)
        if (!libVars.isEmpty() && isEnabled(RuleCategory.CODELIST_VALUE))
        {
            generateCodelistRules(libVars, meta, domName, rules, report);
        }

        // Categories 7-9 (TESTCD/TEST, TSPARMCD/TSPARM, FL/FN) had built-in template carriers
        // (GEN-TCTST / GEN-TSPC / GEN-FLFN); all three were retired with rules-templates.json
        // (Fix #366). A corpus rule carrying the same check reaches the executed set through
        // Category 25 (-- prefix) or Category 27 (wildcard expansion) exactly as before.

        // Category 10: char/numeric pair one-to-one
        if (isEnabled(RuleCategory.PAIR_ONE_TO_ONE))
        {
            generatePairOneToOneRules(libVars, meta, domName, rules, report);
        }

        // Category 11: dataset label (require Library)
        if (!libVars.isEmpty() && isEnabled(RuleCategory.DATASET_LABEL))
        {
            generateDatasetLabelRule(meta, domName, rules, report);
        }

        // Category 12: disallowed variables (require Library)
        if (!libVars.isEmpty() && isEnabled(RuleCategory.DISALLOWED_VARIABLE))
        {
            generateDisallowedVariableRule(libVars, meta, domName, rules, report);
        }

        // Category 13: MedDRA (optional)
        if (isEnabled(RuleCategory.MEDDRA_VALIDATION))
        {
            generateMedDRARules(meta, domName, rules, report);
        }

        // Category 14: WHO Drug (optional)
        if (isEnabled(RuleCategory.WHODD_VALIDATION))
        {
            generateWHODrugRules(meta, domName, rules, report);
        }

        // Category 24: Indexed variable rules (TRTxxP, ANLzzFL, CRITy, etc.)
        if (isEnabled(RuleCategory.INDEXED_VARIABLE_RULES))
        {
            generateIndexedVariableRules(meta, domName, rules, report, libraryDefinedVars);
        }

        // Category 26: Cross-dataset checks (vs ADSL)
        // Metadata checks (label, type, format) are now template-driven via
        // CDISC-AD0085/0086/0590 with cross_dataset_variable_metadata Operations.
        if (isEnabled(RuleCategory.CROSS_DATASET_METADATA) && !"ADSL".equals(domName))
        {
            generateCrossDatasetValueRules(meta, domName, rules, report);
        }

        // Category 27: Wildcard expansion — already done by the WildcardExpander.tryExpand
        // pass at the top of this method. Drain the precomputed expansions into the rule set
        // here to preserve the report's relative ordering (post-IDX/CROSS_DATASET, pre-SDTM-
        // prefix-substitution).
        rules.addAll(templateExpansions);

        // Category 25: SDTM -- prefix expansion + static rule pass-through
        if (isEnabled(RuleCategory.SDTM_PREFIX_EXPANSION))
        {
            expandSdtmPrefixRules(meta, domName, scopedStaticRules, rules, report);
        }

        // Categories 15-23: Define-XML based (optional)
        generateDefineXMLRules(meta, domName, rules, report);

        // Phase G7: Deduplication with static rules
        if (existingRuleIds != null && !existingRuleIds.isEmpty())
        {
            List<Rule> deduped = new ArrayList<>();
            for (Rule rule : rules)
            {
                RuleCore core = rule.getCore();
                String coreId = core != null ? core.getId() : null;
                if (!existingRuleIds.contains(coreId))
                {
                    deduped.add(rule);
                }
                else
                {
                    report.addSkipped(new SkippedRuleInfo(RuleCategory.VARIABLE_LABEL, // category
                                                                                       // doesn't
                                                                                       // matter for
                                                                                       // dedup
                            coreId, "Skipped: already covered by static rule " + coreId));
                }
            }
            rules = deduped;
        }

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


    void generateCodelistRules(List<Map<String, String>> libVars, DataTableMeta meta, String domain,
            List<Rule> rules, RuleGenerationReport report)
    {
        for (Map<String, String> libVar : libVars)
        {
            String varName = libVar.get("name");
            String codelist = libVar.get(KEY_CODELIST);
            if (varName == null || codelist == null || codelist.isEmpty())
            {
                continue;
            }
            if (meta.getColumnIndex(varName) < 0)
            {
                continue; // variable not in dataset
            }

            String src = provider.getStandard() + " " + provider.getVersion() + ", " + domain + "."
                    + varName + ", codelist " + codelist;

            if (provider.isCodelistExtensible(codelist))
            {
                report.addSkipped(new SkippedRuleInfo(RuleCategory.CODELIST_VALUE, varName,
                        "Codelist " + codelist + " is extensible"));
                continue;
            }

            List<String> terms = provider.getCodelistTerms(codelist);
            if (terms.isEmpty())
            {
                report.addSkipped(new SkippedRuleInfo(RuleCategory.CODELIST_VALUE, varName,
                        "No terms found for codelist " + codelist));
                continue;
            }

            String coreId = coreId("CL", domain, varName);
            String desc = "Value of " + varName + " must be from non-extensible codelist "
                    + codelist + ".";

            // Check: variable non-empty AND value not in terms list
            CheckConditionLeaf nonEmpty = CheckConditionLeaf.builder().name(varName)
                    .operator(OP_NON_EMPTY).build();

            com.fasterxml.jackson.databind.node.ArrayNode termArray = new com.fasterxml.jackson.databind.ObjectMapper()
                    .createArrayNode();
            for (String t : terms)
            {
                termArray.add(t);
            }
            CheckConditionLeaf notInList = CheckConditionLeaf.builder().name(varName)
                    .operator(OP_IS_NOT_CONTAINED_BY).value(termArray).build();

            Rule rule = buildRule(coreId, desc, Sensitivity.RECORD,
                    new CheckConditionAll(List.of(nonEmpty, notInList)),
                    varName + " contains a value not in codelist " + codelist + ".",
                    List.of(varName), domain);
            rules.add(rule);
            report.addGenerated(
                    new GeneratedRuleInfo(coreId, RuleCategory.CODELIST_VALUE, varName, desc, src));
        }
    }

    // Categories 7-9 (TESTCD/TEST, TSPARMCD/TSPARM, FL/FN) have no generator here; their
    // built-in template carriers were retired with rules-templates.json (Fix #366).


    void generatePairOneToOneRules(List<Map<String, String>> libVars, DataTableMeta meta,
            String domain, List<Rule> rules, RuleGenerationReport report)
    {
        // Find character/numeric pairs: X / XN where both exist in the dataset
        int colCount = meta.getColumnCount();
        for (int c = 0; c < colCount; c++)
        {
            String name = meta.getColumn(c).getName();
            if (name.endsWith("N") || name.endsWith("FL") || name.endsWith("FN"))
            {
                continue; // skip numeric counterparts and flags (handled by FL/FN)
            }
            String nName = name + "N";
            if (meta.getColumnIndex(nName) < 0)
            {
                continue;
            }
            // Skip if already handled as FL/FN
            if (name.endsWith("FL"))
            {
                continue;
            }

            String coreId = coreId("121", domain, name);
            String desc = name + MSG_AND_SEP + nName + " must have a one-to-one relationship.";
            String src = domain + "." + name + " / " + nName;

            CheckConditionLeaf check = CheckConditionLeaf.builder().name(name)
                    .operator(OP_IS_NOT_UNIQUE_RELATIONSHIP).value(new TextNode(nName)).build();

            Rule rule = buildRule(coreId, desc, Sensitivity.RECORD, check,
                    name + MSG_AND_SEP + nName + " do not have a 1:1 relationship.",
                    List.of(name, nName), domain);
            rules.add(rule);
            report.addGenerated(
                    new GeneratedRuleInfo(coreId, RuleCategory.PAIR_ONE_TO_ONE, name, desc, src));
        }
    }


    void generateDatasetLabelRule(DataTableMeta meta, String domain, List<Rule> rules,
            RuleGenerationReport report)
    {
        Map<String, String> dsMeta = provider.getDatasetMetadata(domain);
        String expectedLabel = dsMeta.get("label");
        if (expectedLabel == null || expectedLabel.isEmpty())
        {
            return;
        }
        String actualLabel = meta.getLabel();
        if (expectedLabel.equals(actualLabel))
        {
            return; // matches
        }

        String coreId = coreId("DSLBL", domain);
        String desc = "Dataset label must be '" + expectedLabel + "'.";
        String src = provider.getStandard() + " " + provider.getVersion() + ", " + domain;

        // Use a simple check that always triggers (label already known to mismatch)
        CheckConditionLeaf check = CheckConditionLeaf.builder().name(VAR_STUDYID)
                .operator(OP_EXISTS).build();

        Rule rule = buildRule(coreId, desc, Sensitivity.DATASET, check,
                "Dataset label is '" + (actualLabel != null ? actualLabel : "") + "' but expected '"
                        + expectedLabel + "'.",
                null, domain);
        rules.add(rule);
        report.addGenerated(
                new GeneratedRuleInfo(coreId, RuleCategory.DATASET_LABEL, null, desc, src));
    }


    void generateDisallowedVariableRule(List<Map<String, String>> libVars, DataTableMeta meta,
            String domain, List<Rule> rules, RuleGenerationReport report)
    {
        // Collect Library-defined variable names
        Set<String> libVarNames = new java.util.HashSet<>();
        for (Map<String, String> lv : libVars)
        {
            String name = lv.get("name");
            if (name != null)
            {
                libVarNames.add(name);
            }
        }

        // Find dataset variables not in Library
        List<String> unexpected = new ArrayList<>();
        int colCount = meta.getColumnCount();
        for (int c = 0; c < colCount; c++)
        {
            String name = meta.getColumn(c).getName();
            if (!libVarNames.contains(name))
            {
                unexpected.add(name);
            }
        }

        if (unexpected.isEmpty())
        {
            return;
        }

        String coreId = coreId("DISALLOW", domain);
        String varList = String.join(", ", unexpected);
        String desc = unexpected.size() + " unexpected variable(s) in " + domain + ": " + varList;
        String src = provider.getStandard() + " " + provider.getVersion() + ", " + domain;

        // Use a simple check that always triggers
        CheckConditionLeaf check = CheckConditionLeaf.builder().name(VAR_STUDYID)
                .operator(OP_EXISTS).build();

        Rule rule = buildRule(coreId, desc, Sensitivity.DATASET, check,
                "Unexpected variables in " + domain + ": " + varList
                        + ". These variables are not defined in the " + provider.getStandard() + " "
                        + provider.getVersion() + " specification.",
                null, domain);
        rules.add(rule);
        report.addGenerated(
                new GeneratedRuleInfo(coreId, RuleCategory.DISALLOWED_VARIABLE, null, desc, src));
    }


    void generateMedDRARules(DataTableMeta meta, String domain, List<Rule> rules,
            RuleGenerationReport report)
    {
        MedDRAProvider meddra = dictionaryProvider != null ? dictionaryProvider.getMedDRA() : null;
        if (meddra == null)
        {
            // Silently skip — record in report
            String[] meddraVars =
            {
                    "DECOD", "LLT", "HLT", "HLGT", "SOC", "BODSYS"
            };
            // EC-36: use the variable prefix rather than the old `length() == 2 ? domain : ""`
            // gate, which produced a BARE suffix (e.g. "DECOD") for every AP/SUPP/long domain code
            // in this "dictionary not available" skip report. Only the metadata is in scope here,
            // so the AP branch — which needs the APID column — cannot run; an AP dataset therefore
            // reports the full code (APMHDECOD) rather than the parent suffix (MHDECOD). Report
            // text only; no rule outcome depends on it.
            String prefix = Objects
                    .requireNonNullElse(OperationExecutor.variableWildcardPrefix(null, domain), "");
            for (String suffix : meddraVars)
            {
                String varName = prefix + suffix;
                if (meta.getColumnIndex(varName) >= 0 || meta.getColumnIndex("--" + suffix) >= 0)
                {
                    report.addSkipped(new SkippedRuleInfo(RuleCategory.MEDDRA_VALIDATION, varName,
                            "MedDRA dictionary not available"));
                }
            }
            return;
        }

        // Only applicable to AE, MH, CE domains
        if (!"AE".equals(domain) && !"MH".equals(domain) && !"CE".equals(domain))
        {
            return;
        }

        String prefix = domain;
        String src = "MedDRA " + meddra.getVersion() + ", " + domain;

        // Code/term pair definitions
        record MedDRAPair(String codeVar, String termVar, String level)
        {
        }
        List<MedDRAPair> pairs = List.of(
                new MedDRAPair(prefix + "PTCD", prefix + "DECOD", "Preferred Term"),
                new MedDRAPair(prefix + "LLTCD", prefix + "LLT", "Lowest Level Term"),
                new MedDRAPair(prefix + "HLTCD", prefix + "HLT", "High Level Term"),
                new MedDRAPair(prefix + "HLGTCD", prefix + "HLGT", "High Level Group Term"),
                new MedDRAPair(prefix + "SOCCD", prefix + "SOC", "System Organ Class"));

        for (MedDRAPair pair : pairs)
        {
            if (meta.getColumnIndex(pair.codeVar) < 0 && meta.getColumnIndex(pair.termVar) < 0)
            {
                continue; // neither code nor term variable present
            }

            // Generate one-to-one consistency check between code and term
            if (meta.getColumnIndex(pair.codeVar) >= 0 && meta.getColumnIndex(pair.termVar) >= 0)
            {
                String coreId = coreId("MED", domain, pair.codeVar);
                String desc = pair.codeVar + MSG_AND_SEP + pair.termVar
                        + " must be consistent per MedDRA " + pair.level + ".";

                CheckConditionLeaf check = CheckConditionLeaf.builder().name(pair.codeVar)
                        .operator(OP_IS_NOT_UNIQUE_RELATIONSHIP).value(new TextNode(pair.termVar))
                        .build();

                Rule rule = buildRule(
                        coreId, desc, Sensitivity.RECORD, check, pair.codeVar + MSG_AND_SEP
                                + pair.termVar + " are not consistent (MedDRA " + pair.level + ").",
                        List.of(pair.codeVar, pair.termVar), domain);
                rules.add(rule);
                report.addGenerated(new GeneratedRuleInfo(coreId, RuleCategory.MEDDRA_VALIDATION,
                        pair.codeVar, desc, src));
            }
        }
    }


    void generateWHODrugRules(DataTableMeta meta, String domain, List<Rule> rules,
            RuleGenerationReport report)
    {
        WHODrugProvider whodd = dictionaryProvider != null ? dictionaryProvider.getWHODrug() : null;
        if (whodd == null)
        {
            // Silently skip
            if ("CM".equals(domain))
            {
                for (String variable : List.of(VAR_CMDECOD, VAR_CMCLAS, VAR_CMCLASCD))
                {
                    if (meta.getColumnIndex(variable) >= 0)
                    {
                        report.addSkipped(new SkippedRuleInfo(RuleCategory.WHODD_VALIDATION,
                                variable, "WHO Drug dictionary not available"));
                    }
                }
            }
            return;
        }

        if (!"CM".equals(domain))
        {
            return;
        }

        String src = "WHO Drug " + whodd.getVersion() + ", CM";

        // CMDECOD / CMCLAS consistency
        if (meta.getColumnIndex(VAR_CMDECOD) >= 0 && meta.getColumnIndex(VAR_CMCLASCD) >= 0)
        {
            String coreId = coreId("WHO", domain, VAR_CMDECOD);
            String desc = "CMDECOD and CMCLASCD must be consistent per WHO Drug dictionary.";

            CheckConditionLeaf check = CheckConditionLeaf.builder().name(VAR_CMDECOD)
                    .operator(OP_IS_NOT_UNIQUE_RELATIONSHIP).value(new TextNode(VAR_CMCLASCD))
                    .build();

            Rule rule = buildRule(coreId, desc, Sensitivity.RECORD, check,
                    "CMDECOD and CMCLASCD are not consistent.", List.of(VAR_CMDECOD, VAR_CMCLASCD),
                    domain);
            rules.add(rule);
            report.addGenerated(new GeneratedRuleInfo(coreId, RuleCategory.WHODD_VALIDATION,
                    VAR_CMDECOD, desc, src));
        }

        // CMCLASCD / CMCLAS consistency
        if (meta.getColumnIndex(VAR_CMCLASCD) >= 0 && meta.getColumnIndex(VAR_CMCLAS) >= 0)
        {
            String coreId = coreId("WHO", domain, VAR_CMCLASCD);
            String desc = "CMCLASCD and CMCLAS must be consistent per WHO Drug dictionary.";

            CheckConditionLeaf check = CheckConditionLeaf.builder().name(VAR_CMCLASCD)
                    .operator(OP_IS_NOT_UNIQUE_RELATIONSHIP).value(new TextNode(VAR_CMCLAS))
                    .build();

            Rule rule = buildRule(coreId, desc, Sensitivity.RECORD, check,
                    "CMCLASCD and CMCLAS are not consistent.", List.of(VAR_CMCLASCD, VAR_CMCLAS),
                    domain);
            rules.add(rule);
            report.addGenerated(new GeneratedRuleInfo(coreId, RuleCategory.WHODD_VALIDATION,
                    VAR_CMCLASCD, desc, src));
        }
    }

    // ---- Category 24: Indexed variable rules ----

    /** Regex patterns for indexed variable families. */
    private static final java.util.regex.Pattern PAT_TRT_XX = java.util.regex.Pattern
            .compile("^TRT(\\d{2})([PAN]{1,2})$");

    private static final java.util.regex.Pattern PAT_TR_XX_DATE = java.util.regex.Pattern
            .compile("^TR(\\d{2})(SDT|EDT|SDTM|EDTM|STM|ETM)$");

    // PAT_ANL_ZZ removed; its ANLzzFL/ANLzzFN template carriers were retired with
    // rules-templates.json (Fix #366).

    private static final java.util.regex.Pattern PAT_SMQ_ZZ = java.util.regex.Pattern
            .compile("^SMQ(\\d{2})(CD|SC|NAM|SCN)$");

    private static final java.util.regex.Pattern PAT_CRIT_Y = java.util.regex.Pattern
            .compile("^CRIT(\\d+)(FL|FN)?$");

    private static final java.util.regex.Pattern PAT_GROUPING = java.util.regex.Pattern.compile(
            "^(REGION|SHIFT|SEVGR|RELGR|TOXGGR|DEVGR|DEVTYG|MODELG|AVALCAT|BASECAT|CHGCAT|PCHGCAT|BCHGCAT|PBCHGCA|PARCAT)(\\d+)(N)?$");

    private static final java.util.regex.Pattern PAT_TR_XX_PG = java.util.regex.Pattern
            .compile("^TR(\\d{2})([PA])G(\\d+)(N)?$");

    private static final java.util.regex.Pattern PAT_TRTPG_Y = java.util.regex.Pattern
            .compile("^TRT([PA])G(\\d+)(N)?$");

    private static final java.util.regex.Pattern PAT_STRAT = java.util.regex.Pattern
            .compile("^STRAT(\\d)([RV])(N)?$");

    private static final java.util.regex.Pattern PAT_TSEQ = java.util.regex.Pattern
            .compile("^TSEQ([PA])G(\\d+)(N)?$");

    private static final java.util.regex.Pattern PAT_TRCMP = java.util.regex.Pattern
            .compile("^TRCMPG(\\d+)(N)?$");

    // The AOCC pattern and the suffix label/type maps moved to rules-templates.json and were
    // retired with it (Fix #366). Category 27 (WILDCARD_EXPANSION) still serves CORPUS wildcard
    // rules — it is the corpus delivery path, not a generator.

    void generateIndexedVariableRules(DataTableMeta meta, String domain, List<Rule> rules,
            RuleGenerationReport report)
    {
        generateIndexedVariableRules(meta, domain, rules, report, Set.of());
    }


    // TODO: libraryDefinedVars is captured but not yet consulted while generating rules.
    // Either wire it up (skip rules for vars that the library already defines) or drop
    // the parameter and the corresponding convenience overload.
    @SuppressWarnings(
    {
            "PMD.UnusedFormalParameter", "unused"
    })
    private void generateIndexedVariableRules(DataTableMeta meta, String domain, List<Rule> rules,
            RuleGenerationReport report, Set<String> libraryDefinedVars)
    {
        int colCount = meta.getColumnCount();
        Set<String> allCols = new java.util.LinkedHashSet<>();
        for (int c = 0; c < colCount; c++)
        {
            allCols.add(meta.getColumn(c).getName());
        }

        for (String col : allCols)
        {
            java.util.regex.Matcher m;

            // Family 1: TRTxxP/A/N
            m = PAT_TRT_XX.matcher(col);
            if (m.matches())
            {
                String xx = m.group(1);
                String suffix = m.group(2);
                generateTrtXxRules(col, xx, suffix, allCols, domain, rules, report);
                continue;
            }

            // Family 2: TRxxSDT/EDT — Treatment Period Dates
            m = PAT_TR_XX_DATE.matcher(col);
            if (m.matches())
            {
                String xx = m.group(1);
                String dateSuffix = m.group(2); // SDT, EDT, SDTM, EDTM, STM, ETM
                generateTrXxDateRules(col, xx, dateSuffix, allCols, domain, rules, report);
                continue;
            }

            // Family 3: ANLzzFL/FN — no carrier; retired with rules-templates.json (Fix #366)

            // Family 4: SMQzzCD/SC/NAM/SCN
            m = PAT_SMQ_ZZ.matcher(col);
            if (m.matches())
            {
                String zz = m.group(1);
                String suffix = m.group(2);
                generateSmqZzRules(col, zz, suffix, allCols, domain, rules, report);
                continue;
            }

            // Family 5: CRITy/yFL/yFN
            m = PAT_CRIT_Y.matcher(col);
            if (m.matches())
            {
                String y = m.group(1);
                String suffix = m.group(2); // null, "FL", or "FN"
                generateCritYRules(col, y, suffix, allCols, domain, rules, report);
                continue;
            }

            // Family 6: Grouping variables
            m = PAT_GROUPING.matcher(col);
            if (m.matches())
            {
                String prefix = m.group(1);
                String y = m.group(2);
                generateGroupingRules(col, prefix, y, allCols, domain, rules, report);
                continue;
            }

            // Family 7a: TRxxPGy/AGy
            m = PAT_TR_XX_PG.matcher(col);
            if (m.matches())
            {
                String charVar = "TR" + m.group(1) + m.group(2) + "G" + m.group(3);
                String numVar = charVar + "N";
                generatePairingRule(col, charVar, numVar, allCols, domain, "TRxxGy", rules, report);
                continue;
            }

            // Family 7b: TRTPGy/TRTAGy
            m = PAT_TRTPG_Y.matcher(col);
            if (m.matches())
            {
                String charVar = "TRT" + m.group(1) + "G" + m.group(2);
                String numVar = charVar + "N";
                generatePairingRule(col, charVar, numVar, allCols, domain, "TRTPGy", rules, report);
                continue;
            }

            // Family 8a: STRATwR/V
            m = PAT_STRAT.matcher(col);
            if (m.matches())
            {
                String charVar = "STRAT" + m.group(1) + m.group(2);
                String numVar = charVar + "N";
                generatePairingRule(col, charVar, numVar, allCols, domain, "STRAT", rules, report);
                continue;
            }

            // Family 8b: TSEQPGy/TSEQAGy
            m = PAT_TSEQ.matcher(col);
            if (m.matches())
            {
                String charVar = "TSEQ" + m.group(1) + "G" + m.group(2);
                String numVar = charVar + "N";
                generatePairingRule(col, charVar, numVar, allCols, domain, "TSEQ", rules, report);
                continue;
            }

            // Family 8c: TRCMPGy
            m = PAT_TRCMP.matcher(col);
            if (m.matches())
            {
                String charVar = "TRCMPG" + m.group(1);
                String numVar = charVar + "N";
                generatePairingRule(col, charVar, numVar, allCols, domain, "TRCMP", rules, report);
            }

            // AOCC flags had an AOCCzzFL template carrier, retired with rules-templates.json
            // (Fix #366)
        }

        // Suffix-based label and type checks had SUFFIX_LABEL / SUFFIX_TYPE template carriers,
        // retired with rules-templates.json (Fix #366). RuleCategory.SUFFIX_LABEL_TYPE now gates
        // nothing — the enum value is kept until the generator-code deletion is reviewed.

        // CDISC-AD0048 (population flag presence in ADSL) and CDISC-AD9001-TRTPRES (treatment
        // variable presence in any ADaM dataset) are now JSON-authored rules using the
        // ``variable_count`` Operation with ``name_pattern``. The corresponding
        // ``generateFlagPresenceCheck`` and ``generateTreatmentPresenceCheck`` handlers
        // (and their synthetic ``IDX-FLPRES``/``IDX-TRTPRES`` rules) were retired
        // 2026-04-28 — the rule body is the spec, no engine indirection.
    }

    // Suffix label/type generation moved to rules-templates.json and was retired with it
    // (Fix #366). What survives in applyTemplatePostFilters is the CORPUS-facing half:
    // skipIfLibraryDefined and SDTM domain-prefix filtering.


    private void generateTrtXxRules(String col, String xx, String suffix, Set<String> allCols,
            String domain, List<Rule> rules, RuleGenerationReport report)
    {
        int idx = Integer.parseInt(xx);

        // Pairing: TRTxxP ↔ TRTxxPN, TRTxxA ↔ TRTxxAN
        if (suffix.equals("P") || suffix.equals("A"))
        {
            String paired = "TRT" + xx + suffix + "N";
            generatePairingRule(col, col, paired, allCols, domain, "TRTxx", rules, report);

            // Sequencing: if xx > 01, TRT{xx-1}P must exist
            if (idx > 1)
            {
                String prev = String.format("TRT%02d%s", idx - 1, suffix);
                if (!allCols.contains(prev))
                {
                    String id = coreId("IDX-TRTSEQ", domain, col);
                    String desc = col + MSG_IS_PRESENT_BUT + prev + MSG_IS_MISSING_DOT;
                    CheckConditionLeaf check = CheckConditionLeaf.builder().name(prev)
                            .operator(OP_NOT_EXISTS).build();
                    Rule rule = buildRule(id, desc, Sensitivity.DATASET, check,
                            prev + MSG_MUST_BE_PRESENT_WHEN + col + MSG_EXISTS_DOT, List.of(col),
                            domain);
                    rules.add(rule);
                    report.addGenerated(new GeneratedRuleInfo(id,
                            RuleCategory.INDEXED_VARIABLE_RULES, col, desc, domain));
                }
            }
        }
        else if (suffix.equals("PN") || suffix.equals("AN"))
        {
            // Reverse pairing: TRTxxPN exists → TRTxxP must exist
            String charVar = "TRT" + xx + suffix.substring(0, 1);
            if (!allCols.contains(charVar))
            {
                String id = coreId("IDX-TRTPAIR", domain, col);
                String desc = col + MSG_IS_PRESENT_BUT + charVar + MSG_IS_MISSING_DOT;
                CheckConditionLeaf check = CheckConditionLeaf.builder().name(charVar)
                        .operator(OP_NOT_EXISTS).build();
                Rule rule = buildRule(id, desc, Sensitivity.DATASET, check,
                        charVar + MSG_MUST_BE_PRESENT_WHEN + col + MSG_EXISTS_DOT, List.of(col),
                        domain);
                rules.add(rule);
                report.addGenerated(new GeneratedRuleInfo(id, RuleCategory.INDEXED_VARIABLE_RULES,
                        col, desc, domain));
            }
        }
    }


    private void generateTrXxDateRules(String col, String xx, String dateSuffix,
            Set<String> allCols, String domain, List<Rule> rules, RuleGenerationReport report)
    {
        // Start ≤ End: TRxxSDT ≤ TRxxEDT (and SDTM/EDTM, STM/ETM)
        String endSuffix = switch (dateSuffix)
        {
        case "SDT" -> "EDT";
        case "SDTM" -> "EDTM";
        case "STM" -> "ETM";
        default -> null;
        };
        if (endSuffix != null)
        {
            String endVar = "TR" + xx + endSuffix;
            if (allCols.contains(endVar))
            {
                String id = coreId("IDX-TRDT", domain, col);
                String desc = col + " must not be greater than " + endVar + ".";
                Rule rule = buildRule(id, desc, Sensitivity.RECORD, new CheckConditionAll(List.of(
                        CheckConditionLeaf.builder().name(col).operator(OP_NON_EMPTY).build(),
                        CheckConditionLeaf.builder().name(endVar).operator(OP_NON_EMPTY).build(),
                        CheckConditionLeaf.builder().name(col).operator("greater_than")
                                .value(new TextNode(endVar)).build())),
                        col + " is after " + endVar + ".", List.of(col, endVar), domain);
                rules.add(rule);
                report.addGenerated(new GeneratedRuleInfo(id, RuleCategory.INDEXED_VARIABLE_RULES,
                        col, desc, domain));
            }
        }

    }

    // generateAnlZzRules removed; its template carriers were retired with rules-templates.json
    // (Fix #366)


    private void generateSmqZzRules(String col, String zz, String suffix, Set<String> allCols,
            String domain, List<Rule> rules, RuleGenerationReport report)
    {
        String namVar = "SMQ" + zz + "NAM";
        String cdVar = "SMQ" + zz + "CD";
        String scVar = "SMQ" + zz + "SC";
        String scnVar = "SMQ" + zz + "SCN";

        // Pairing: NAM populated → CD and SC must be populated
        if ("NAM".equals(suffix) && allCols.contains(cdVar))
        {
            String id = coreId("IDX-SMQPAIR", domain, col + "-CD");
            String desc = "When " + namVar + " is populated, " + cdVar + " must be populated.";
            Rule rule = buildRule(id, desc, Sensitivity.RECORD,
                    new CheckConditionAll(List.of(
                            CheckConditionLeaf.builder().name(namVar).operator(OP_NON_EMPTY)
                                    .build(),
                            CheckConditionLeaf.builder().name(cdVar).operator("empty").build())),
                    desc, List.of(namVar, cdVar), domain);
            rules.add(rule);
            report.addGenerated(new GeneratedRuleInfo(id, RuleCategory.INDEXED_VARIABLE_RULES, col,
                    desc, domain));
        }

        // Value: SC must be BROAD or NARROW
        if ("SC".equals(suffix))
        {
            String id = coreId("IDX-SMQSCVAL", domain, col);
            String desc = col + " must be BROAD or NARROW.";
            com.fasterxml.jackson.databind.node.ArrayNode vals = new com.fasterxml.jackson.databind.ObjectMapper()
                    .createArrayNode();
            vals.add("BROAD");
            vals.add("NARROW");
            Rule rule = buildRule(id, desc, Sensitivity.RECORD,
                    new CheckConditionAll(List.of(
                            CheckConditionLeaf.builder().name(col).operator(OP_NON_EMPTY).build(),
                            CheckConditionLeaf.builder().name(col).operator(OP_IS_NOT_CONTAINED_BY)
                                    .value(vals).build())),
                    col + " must be BROAD or NARROW.", List.of(col), domain);
            rules.add(rule);
            report.addGenerated(new GeneratedRuleInfo(id, RuleCategory.INDEXED_VARIABLE_RULES, col,
                    desc, domain));
            // SC ↔ SCN pairing
            generatePairingRule(col, scVar, scnVar, allCols, domain, "SMQzz", rules, report);
        }

        // Value: SCN must be 1 or 2
        if ("SCN".equals(suffix))
        {
            String id = coreId("IDX-SMQSCNVAL", domain, col);
            String desc = col + " must be 1 or 2.";
            com.fasterxml.jackson.databind.node.ArrayNode vals = new com.fasterxml.jackson.databind.ObjectMapper()
                    .createArrayNode();
            vals.add(1);
            vals.add(2);
            Rule rule = buildRule(id, desc, Sensitivity.RECORD,
                    new CheckConditionAll(List.of(
                            CheckConditionLeaf.builder().name(col).operator(OP_NON_EMPTY).build(),
                            CheckConditionLeaf.builder().name(col).operator(OP_IS_NOT_CONTAINED_BY)
                                    .value(vals).build())),
                    col + " must be 1 or 2.", List.of(col), domain);
            rules.add(rule);
            report.addGenerated(new GeneratedRuleInfo(id, RuleCategory.INDEXED_VARIABLE_RULES, col,
                    desc, domain));
        }
    }


    private void generateCritYRules(String col, String y, String suffix, Set<String> allCols,
            String domain, List<Rule> rules, RuleGenerationReport report)
    {
        String critVar = "CRIT" + y;
        String flVar = "CRIT" + y + "FL";
        String fnVar = "CRIT" + y + "FN";

        if (suffix == null && !allCols.contains(flVar))
        {
            // CRITy exists → CRITyFL must exist
            String id = coreId("IDX-CRITPAIR", domain, col);
            String desc = col + MSG_IS_PRESENT_BUT + flVar + MSG_IS_MISSING_DOT;
            Rule rule = buildRule(id, desc, Sensitivity.DATASET,
                    CheckConditionLeaf.builder().name(flVar).operator(OP_NOT_EXISTS).build(),
                    flVar + MSG_MUST_BE_PRESENT_WHEN + col + MSG_EXISTS_DOT, List.of(col), domain);
            rules.add(rule);
            report.addGenerated(new GeneratedRuleInfo(id, RuleCategory.INDEXED_VARIABLE_RULES, col,
                    desc, domain));
        }
        else if ("FL".equals(suffix))
        {
            // CRITyFL exists → CRITy must exist
            if (!allCols.contains(critVar))
            {
                String id = coreId("IDX-CRITFLPAIR", domain, col);
                String desc = col + MSG_IS_PRESENT_BUT + critVar + MSG_IS_MISSING_DOT;
                Rule rule = buildRule(id, desc, Sensitivity.DATASET,
                        CheckConditionLeaf.builder().name(critVar).operator(OP_NOT_EXISTS).build(),
                        critVar + MSG_MUST_BE_PRESENT_WHEN + col + MSG_EXISTS_DOT, List.of(col),
                        domain);
                rules.add(rule);
                report.addGenerated(new GeneratedRuleInfo(id, RuleCategory.INDEXED_VARIABLE_RULES,
                        col, desc, domain));
            }
            // CRITyFL ↔ CRITyFN pairing
            generatePairingRule(col, flVar, fnVar, allCols, domain, "CRITy", rules, report);
        }
        else if ("FN".equals(suffix) && !allCols.contains(flVar))
        {
            // CRITyFN exists → CRITyFL must exist
            String id = coreId("IDX-CRITFNPAIR", domain, col);
            String desc = col + MSG_IS_PRESENT_BUT + flVar + MSG_IS_MISSING_DOT;
            Rule rule = buildRule(id, desc, Sensitivity.DATASET,
                    CheckConditionLeaf.builder().name(flVar).operator(OP_NOT_EXISTS).build(),
                    flVar + MSG_MUST_BE_PRESENT_WHEN + col + MSG_EXISTS_DOT, List.of(col), domain);
            rules.add(rule);
            report.addGenerated(new GeneratedRuleInfo(id, RuleCategory.INDEXED_VARIABLE_RULES, col,
                    desc, domain));
        }
    }


    private void generateGroupingRules(String col, String prefix, String y, Set<String> allCols,
            String domain, List<Rule> rules, RuleGenerationReport report)
    {
        String charVar = prefix + y;
        String numVar = prefix + y + "N";
        generatePairingRule(col, charVar, numVar, allCols, domain, "GRP", rules, report);
    }


    /**
     * Generic pairing rule: if charVar exists and numVar exists, generate one-to-one check. If one
     * exists without the other, generate presence check.
     */
    private void generatePairingRule(String triggerCol, String charVar, String numVar,
            Set<String> allCols, String domain, String familyId, List<Rule> rules,
            RuleGenerationReport report)
    {
        boolean charExists = allCols.contains(charVar);
        boolean numExists = allCols.contains(numVar);

        if (charExists && numExists)
        {
            // One-to-one relationship
            String id = coreId("IDX-" + familyId + "121", domain, charVar);
            // Avoid duplicate — only generate from the char variable
            if (triggerCol.equals(charVar))
            {
                String desc = charVar + MSG_AND_SEP + numVar + " must have a 1:1 relationship.";
                Rule rule = buildRule(id, desc, Sensitivity.RECORD,
                        CheckConditionLeaf.builder().name(charVar)
                                .operator(OP_IS_NOT_UNIQUE_RELATIONSHIP).value(new TextNode(numVar))
                                .build(),
                        desc, List.of(charVar, numVar), domain);
                rules.add(rule);
                report.addGenerated(new GeneratedRuleInfo(id, RuleCategory.INDEXED_VARIABLE_RULES,
                        charVar, desc, domain));
            }
        }
        else if (numExists && !charExists && triggerCol.equals(numVar))
        {
            // Numeric exists but character missing
            String id = coreId("IDX-" + familyId + "PAIR", domain, numVar);
            String desc = numVar + MSG_IS_PRESENT_BUT + charVar + MSG_IS_MISSING_DOT;
            Rule rule = buildRule(id, desc, Sensitivity.DATASET,
                    CheckConditionLeaf.builder().name(charVar).operator(OP_NOT_EXISTS).build(),
                    charVar + MSG_MUST_BE_PRESENT_WHEN + numVar + MSG_EXISTS_DOT, List.of(numVar),
                    domain);
            rules.add(rule);
            report.addGenerated(new GeneratedRuleInfo(id, RuleCategory.INDEXED_VARIABLE_RULES,
                    numVar, desc, domain));
        }
    }

    // generateFlagPresenceCheck and generateTreatmentPresenceCheck retired 2026-04-28.
    // Their effect is now expressed by the JSON-authored rules CDISC-AD0048 (population flag
    // presence in ADSL) and CDISC-AD9001-TRTPRES (treatment variable presence in any ADaM
    // dataset), both using the variable_count Operation with name_pattern.

    // Cross-dataset metadata checks (label, type, format vs ADSL) have been migrated to
    // template rules CDISC-AD0085/0086/0590 with cross_dataset_variable_metadata Operations.


    /**
     * For each variable shared between the current dataset and ADSL (excluding key variables like
     * STUDYID, USUBJID), generates a rule checking that values are identical when joined on
     * USUBJID. ADSL has one record per subject, so the join is straightforward.
     */
    void generateCrossDatasetValueRules(DataTableMeta meta, String domain, List<Rule> rules,
            RuleGenerationReport report)
    {
        if (datasetResolver == null)
        {
            return; // already reported as skipped in metadata check
        }

        IDataTable adslTable = datasetResolver.resolve("ADSL");
        if (adslTable == null)
        {
            return;
        }

        DataTableMeta adslMeta = adslTable.getMetaData();
        // Variables that are keys/identifiers — don't compare values
        Set<String> skipVars = Set.of(VAR_STUDYID, VAR_USUBJID, "SUBJID", "SITEID");

        int colCount = meta.getColumnCount();
        for (int c = 0; c < colCount; c++)
        {
            var colMeta = meta.getColumn(c);
            String varName = colMeta.getName();
            if (skipVars.contains(varName))
            {
                continue;
            }
            if (adslMeta.getOptionalColumn(varName) == null)
            {
                continue; // not in ADSL
            }

            String id = coreId("XDVAL", domain, varName);
            String desc = varName + " values must match ADSL." + varName + " for the same USUBJID.";
            String src = domain + "." + varName + " vs ADSL." + varName;

            // Check: join on USUBJID, compare values
            CheckConditionLeaf check = CheckConditionLeaf.builder().name(varName)
                    .operator(OP_NOT_EQUAL_TO).value(new TextNode("ADSL." + varName)).build();

            Rule rule = buildRule(id, desc, Sensitivity.RECORD, check, desc, List.of(varName),
                    domain);

            // Add Match_Datasets for ADSL join
            net.cumba.cdisc.core.model.MatchDataset match = new net.cumba.cdisc.core.model.MatchDataset();
            match.setName("ADSL");
            match.setKeys(List.of(VAR_USUBJID));
            rule.setMatchDatasets(List.of(match));

            rules.add(rule);
            report.addGenerated(new GeneratedRuleInfo(id, RuleCategory.CROSS_DATASET_METADATA,
                    varName, desc, src));
        }
    }

    // ---- Category 25: SDTM -- prefix expansion ----


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
     * ({@code SUFFIX_LABEL} / {@code SUFFIX_TYPE} vs {@link RuleCategory#SUFFIX_LABEL_TYPE}),
     * {@code suffixExclusions} and {@code requireAllWildcardsInDataset}. All three steered
     * <em>built-in</em> templates and had zero corpus carriers — measured 2026-08-26 over all
     * 3&nbsp;804 {@code rules-src/checks} files and all shipped {@code rules/} records.
     * {@code skipIfLibraryDefined} (11 corpus carriers) and {@code wildcardExclude} (2) are
     * corpus-legal and stay.
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
            if (staticRule.checkConditions().stream().noneMatch(RuleGenerator::containsDashPrefix))
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
            net.cumba.cdisc.core.model.CheckCondition expandedCheck = net.cumba.cdisc.core.exec.CheckConditionTransformer
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
            expanded.setCheckLevels(
                    net.cumba.cdisc.core.model.LevelCheck.mapConditions(staticRule.getCheckLevels(),
                            c -> net.cumba.cdisc.core.exec.CheckConditionTransformer
                                    .resolvePrefixes(c, prefix, expandedCoreId)));

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
    private static boolean containsDashPrefix(net.cumba.cdisc.core.model.CheckCondition condition)
    {
        return switch (condition)
        {
        case CheckConditionAll all -> all.getConditions().stream()
                .anyMatch(RuleGenerator::containsDashPrefix);
        case net.cumba.cdisc.core.model.CheckConditionAny any -> any.getConditions().stream()
                .anyMatch(RuleGenerator::containsDashPrefix);
        case net.cumba.cdisc.core.model.CheckConditionNot not -> containsDashPrefix(
                not.getCondition());
        case CheckConditionLeaf leaf -> (leaf.getName() != null && leaf.getName().startsWith("--"))
                || (leaf.getValue() != null && leaf.getValue().isTextual()
                        && leaf.getValue().asText().contains("--"));
        case net.cumba.cdisc.core.model.CheckConditionConstant _ -> false;
        case net.cumba.cdisc.core.model.CheckConditionExpression _ -> false;
        };
    }

    // ---- Define-XML categories 15-23 ----


    private void generateDefineXMLRules(DataTableMeta meta, String domain, List<Rule> rules,
            RuleGenerationReport report)
    {
        if (defineXMLProvider == null)
        {
            skipDefineCategory(report);
            return;
        }

        List<Map<String, String>> defVars = defineXMLProvider.getVariables(domain);
        if (defVars.isEmpty())
        {
            return;
        }

        if (isEnabled(RuleCategory.DEFINE_VARIABLE_PRESENCE))
        {
            generateDefineVariablePresence(defVars, meta, domain, rules, report);
        }
        if (isEnabled(RuleCategory.DEFINE_NO_EXTRA_VARIABLES))
        {
            generateDefineNoExtraVariables(defVars, meta, domain, rules, report);
        }
        if (isEnabled(RuleCategory.DEFINE_VARIABLE_LABEL))
        {
            generateDefineVariableLabel(defVars, meta, domain, rules, report);
        }
        if (isEnabled(RuleCategory.DEFINE_VARIABLE_TYPE))
        {
            generateDefineVariableType(defVars, meta, domain, rules, report);
        }
        if (isEnabled(RuleCategory.DEFINE_VARIABLE_LENGTH))
        {
            generateDefineVariableLength(defVars, meta, domain, rules, report);
        }
        if (isEnabled(RuleCategory.DEFINE_CODELIST_VALUES))
        {
            generateDefineCodelistValues(defVars, meta, domain, rules, report);
        }
        if (isEnabled(RuleCategory.DEFINE_KEY_UNIQUENESS))
        {
            generateDefineKeyUniqueness(meta, domain, rules, report);
        }
        if (isEnabled(RuleCategory.DEFINE_DATASET_STRUCTURE))
        {
            generateDefineDatasetStructure(meta, domain, rules, report);
        }
        if (isEnabled(RuleCategory.DEFINE_VALUE_LEVEL_METADATA))
        {
            generateDefineValueLevelMetadata(defVars, meta, domain, rules, report);
        }
    }


    private void skipDefineCategory(RuleGenerationReport report)
    {
        // Record that Define-XML categories were skipped
        for (RuleCategory cat : List.of(RuleCategory.DEFINE_VARIABLE_PRESENCE,
                RuleCategory.DEFINE_NO_EXTRA_VARIABLES, RuleCategory.DEFINE_VARIABLE_LABEL,
                RuleCategory.DEFINE_VARIABLE_TYPE, RuleCategory.DEFINE_VARIABLE_LENGTH,
                RuleCategory.DEFINE_CODELIST_VALUES, RuleCategory.DEFINE_KEY_UNIQUENESS,
                RuleCategory.DEFINE_DATASET_STRUCTURE, RuleCategory.DEFINE_VALUE_LEVEL_METADATA))
        {
            if (isEnabled(cat))
            {
                report.addSkipped(new SkippedRuleInfo(cat, null, "Define-XML not available"));
            }
        }
    }


    // Category 15: Define-XML variable presence
    private void generateDefineVariablePresence(List<Map<String, String>> defVars,
            DataTableMeta meta, String domain, List<Rule> rules, RuleGenerationReport report)
    {
        for (Map<String, String> dv : defVars)
        {
            String varName = dv.get("name");
            if (varName == null || meta.getColumnIndex(varName) >= 0)
            {
                continue;
            }
            String coreId = coreId("DXPRES", domain, varName);
            String desc = MSG_VARIABLE_PREFIX + varName + " declared in Define-XML is missing.";
            Rule rule = buildRequiredVariableRule(coreId, desc, varName, domain);
            rules.add(rule);
            report.addGenerated(new GeneratedRuleInfo(coreId, RuleCategory.DEFINE_VARIABLE_PRESENCE,
                    varName, desc, CITED_DEFINE_XML + domain + "." + varName));
        }
    }


    // Category 16: No extra variables
    private void generateDefineNoExtraVariables(List<Map<String, String>> defVars,
            DataTableMeta meta, String domain, List<Rule> rules, RuleGenerationReport report)
    {
        Set<String> defVarNames = new java.util.HashSet<>();
        for (Map<String, String> dv : defVars)
        {
            String name = dv.get("name");
            if (name != null)
            {
                defVarNames.add(name);
            }
        }

        List<String> undeclared = new ArrayList<>();
        int colCount = meta.getColumnCount();
        for (int c = 0; c < colCount; c++)
        {
            String name = meta.getColumn(c).getName();
            if (!defVarNames.contains(name))
            {
                undeclared.add(name);
            }
        }

        if (undeclared.isEmpty())
        {
            return;
        }

        String coreId = coreId("DXEXTRA", domain);
        String varList = String.join(", ", undeclared);
        String desc = undeclared.size() + " variable(s) in " + domain
                + " not declared in Define-XML: " + varList;

        CheckConditionLeaf check = CheckConditionLeaf.builder().name(VAR_STUDYID)
                .operator(OP_EXISTS).build();
        Rule rule = buildRule(coreId, desc, Sensitivity.DATASET, check, desc, null, domain);
        rules.add(rule);
        report.addGenerated(new GeneratedRuleInfo(coreId, RuleCategory.DEFINE_NO_EXTRA_VARIABLES,
                null, desc, CITED_DEFINE_XML + domain));
    }


    // Category 17: Variable label (Define-XML)
    private void generateDefineVariableLabel(List<Map<String, String>> defVars, DataTableMeta meta,
            String domain, List<Rule> rules, RuleGenerationReport report)
    {
        for (Map<String, String> dv : defVars)
        {
            String varName = dv.get("name");
            String defLabel = dv.get("label");
            if (varName == null || defLabel == null || meta.getColumnIndex(varName) < 0)
            {
                continue;
            }
            String coreId = coreId("DXLBL", domain, varName);
            String desc = MSG_VARIABLE_PREFIX + varName + " label must match Define-XML: '"
                    + defLabel + "'.";
            Rule rule = buildVariableMetadataRule(coreId, desc, varName, "variable_label", defLabel,
                    domain);
            rules.add(rule);
            report.addGenerated(new GeneratedRuleInfo(coreId, RuleCategory.DEFINE_VARIABLE_LABEL,
                    varName, desc, CITED_DEFINE_XML + domain + "." + varName));
        }
    }


    // Category 18: Variable type (Define-XML)
    private void generateDefineVariableType(List<Map<String, String>> defVars, DataTableMeta meta,
            String domain, List<Rule> rules, RuleGenerationReport report)
    {
        for (Map<String, String> dv : defVars)
        {
            String varName = dv.get("name");
            String defType = dv.get("dataType");
            if (varName == null || defType == null || meta.getColumnIndex(varName) < 0)
            {
                continue;
            }
            String mappedType = mapSimpleDatatype(defType);
            String coreId = coreId("DXTYP", domain, varName);
            String desc = MSG_VARIABLE_PREFIX + varName + " type must match Define-XML: "
                    + mappedType + ".";
            Rule rule = buildVariableMetadataRule(coreId, desc, varName, "variable_data_type",
                    mappedType, domain);
            rules.add(rule);
            report.addGenerated(new GeneratedRuleInfo(coreId, RuleCategory.DEFINE_VARIABLE_TYPE,
                    varName, desc, CITED_DEFINE_XML + domain + "." + varName));
        }
    }


    // Category 19: Variable length (Define-XML)
    private void generateDefineVariableLength(List<Map<String, String>> defVars, DataTableMeta meta,
            String domain, List<Rule> rules, RuleGenerationReport report)
    {
        for (Map<String, String> dv : defVars)
        {
            String varName = dv.get("name");
            String lenStr = dv.get("length");
            String defType = dv.get("dataType");
            if (varName == null || lenStr == null || meta.getColumnIndex(varName) < 0)
            {
                continue;
            }
            // Only check length for character variables
            String mappedType = mapSimpleDatatype(defType);
            if (!"Char".equals(mappedType))
            {
                continue;
            }

            int maxLen;
            try
            {
                maxLen = Integer.parseInt(lenStr);
            }
            catch (NumberFormatException _)
            {
                continue;
            }

            String coreId = coreId("DXLEN", domain, varName);
            String desc = varName + " values must not exceed Define-XML length " + maxLen + ".";

            CheckConditionLeaf nonEmpty = CheckConditionLeaf.builder().name(varName)
                    .operator(OP_NON_EMPTY).build();
            CheckConditionLeaf tooLong = CheckConditionLeaf.builder().name(varName)
                    .operator("longer_than")
                    .value(new com.fasterxml.jackson.databind.node.IntNode(maxLen)).build();

            Rule rule = buildRule(coreId, desc, Sensitivity.RECORD,
                    new CheckConditionAll(List.of(nonEmpty, tooLong)),
                    varName + " value exceeds Define-XML declared length of " + maxLen + ".",
                    List.of(varName), domain);
            rules.add(rule);
            report.addGenerated(
                    new GeneratedRuleInfo(coreId, RuleCategory.DEFINE_VARIABLE_LENGTH, varName,
                            desc, CITED_DEFINE_XML + domain + "." + varName + " length=" + maxLen));
        }
    }


    // Category 20: Codelist values (Define-XML)
    private void generateDefineCodelistValues(List<Map<String, String>> defVars, DataTableMeta meta,
            String domain, List<Rule> rules, RuleGenerationReport report)
    {
        // Only reached from generateDefineXMLRules after its defineXMLProvider != null guard.
        DefineXMLProvider dxp = Objects.requireNonNull(defineXMLProvider);
        for (Map<String, String> dv : defVars)
        {
            String varName = dv.get("name");
            String codelistOID = dv.get(KEY_CODELIST);
            if (varName == null || codelistOID == null || codelistOID.isEmpty()
                    || meta.getColumnIndex(varName) < 0)
            {
                continue;
            }

            List<Map<String, String>> terms = dxp.getCodelistTerms(codelistOID);
            if (terms.isEmpty())
            {
                continue;
            }

            List<String> codedValues = new ArrayList<>();
            for (Map<String, String> term : terms)
            {
                String cv = term.get("codedValue");
                if (cv != null && !cv.isEmpty())
                {
                    codedValues.add(cv);
                }
            }
            if (codedValues.isEmpty())
            {
                continue;
            }

            String coreId = coreId("DXCL", domain, varName);
            String codelistName = dv.getOrDefault("codelistName", codelistOID);
            String desc = varName + " values must be in Define-XML codelist " + codelistName + ".";

            CheckConditionLeaf nonEmpty = CheckConditionLeaf.builder().name(varName)
                    .operator(OP_NON_EMPTY).build();

            com.fasterxml.jackson.databind.node.ArrayNode termArray = new com.fasterxml.jackson.databind.ObjectMapper()
                    .createArrayNode();
            for (String cv : codedValues)
            {
                termArray.add(cv);
            }
            CheckConditionLeaf notInList = CheckConditionLeaf.builder().name(varName)
                    .operator(OP_IS_NOT_CONTAINED_BY).value(termArray).build();

            Rule rule = buildRule(coreId, desc, Sensitivity.RECORD,
                    new CheckConditionAll(List.of(nonEmpty, notInList)),
                    varName + " value not in Define-XML codelist " + codelistName + ".",
                    List.of(varName), domain);
            rules.add(rule);
            report.addGenerated(new GeneratedRuleInfo(coreId, RuleCategory.DEFINE_CODELIST_VALUES,
                    varName, desc,
                    CITED_DEFINE_XML + domain + "." + varName + ", codelist " + codelistName));
        }
    }


    // Category 21: Key variable uniqueness
    private void generateDefineKeyUniqueness(DataTableMeta meta, String domain, List<Rule> rules,
            RuleGenerationReport report)
    {
        // Only reached from generateDefineXMLRules after its defineXMLProvider != null guard.
        List<String> keys = Objects.requireNonNull(defineXMLProvider).getKeyVariables(domain);
        if (keys == null || keys.isEmpty())
        {
            return;
        }

        // Verify all key variables exist in the dataset
        for (String key : keys)
        {
            if (meta.getColumnIndex(key) < 0)
            {
                return; // can't check uniqueness if key column is missing
            }
        }

        String coreId = coreId("DXKEY", domain);
        String keyList = String.join(", ", keys);
        String desc = "Records must be unique by Define-XML keys: " + keyList + ".";

        // Use is_not_unique_set. For single-key, the value list contains no additional columns
        // (the tuple is just the name column). For multi-key, the remaining keys are in the value
        // list, forming a composite tuple.
        CheckConditionLeaf check;
        if (keys.size() == 1)
        {
            check = CheckConditionLeaf.builder().name(keys.getFirst())
                    .operator(OP_IS_NOT_UNIQUE_SET).build();
        }
        else
        {
            com.fasterxml.jackson.databind.node.ArrayNode keyArray = new com.fasterxml.jackson.databind.ObjectMapper()
                    .createArrayNode();
            for (int i = 1; i < keys.size(); i++)
            {
                keyArray.add(keys.get(i));
            }
            check = CheckConditionLeaf.builder().name(keys.getFirst())
                    .operator(OP_IS_NOT_UNIQUE_SET).value(keyArray).build();
        }

        Rule rule = buildRule(coreId, desc, Sensitivity.RECORD, check,
                "Duplicate records found for key variables: " + keyList + ".", keys, domain);
        rules.add(rule);
        report.addGenerated(new GeneratedRuleInfo(coreId, RuleCategory.DEFINE_KEY_UNIQUENESS, null,
                desc, CITED_DEFINE_XML + domain + ", keys=" + keyList));
    }


    // Category 22: Dataset structure
    private void generateDefineDatasetStructure(DataTableMeta meta, String domain, List<Rule> rules,
            RuleGenerationReport report)
    {
        // Only reached from generateDefineXMLRules after its defineXMLProvider != null guard.
        Map<String, String> dsMeta = Objects.requireNonNull(defineXMLProvider)
                .getDatasetMetadata(domain);
        String structure = dsMeta.get("structure");
        if (structure == null || structure.isEmpty())
        {
            return;
        }

        // Parse "One record per subject" or similar into key variables
        // This is heuristic — extract variable-like tokens after "per"
        List<String> structKeys = parseStructureKeys(structure, meta);
        if (structKeys.isEmpty())
        {
            report.addSkipped(new SkippedRuleInfo(RuleCategory.DEFINE_DATASET_STRUCTURE, null,
                    "Could not parse structure: " + structure));
            return;
        }

        // Reuse key uniqueness check
        String coreId = coreId("DXSTRUCT", domain);
        String desc = "Dataset structure '" + structure + "' implies uniqueness by "
                + String.join(", ", structKeys) + ".";

        CheckConditionLeaf check;
        if (structKeys.size() == 1)
        {
            check = CheckConditionLeaf.builder().name(structKeys.getFirst())
                    .operator(OP_IS_NOT_UNIQUE_SET).build();
        }
        else
        {
            com.fasterxml.jackson.databind.node.ArrayNode keyArray = new com.fasterxml.jackson.databind.ObjectMapper()
                    .createArrayNode();
            for (int i = 1; i < structKeys.size(); i++)
            {
                keyArray.add(structKeys.get(i));
            }
            check = CheckConditionLeaf.builder().name(structKeys.getFirst())
                    .operator(OP_IS_NOT_UNIQUE_SET).value(keyArray).build();
        }

        Rule rule = buildRule(coreId, desc, Sensitivity.RECORD, check,
                "Dataset structure violation: expected '" + structure + "'.", structKeys, domain);
        rule.setExecutability(net.cumba.cdisc.core.model.Executability.PARTIALLY_EXECUTABLE);
        rules.add(rule);
        report.addGenerated(new GeneratedRuleInfo(coreId, RuleCategory.DEFINE_DATASET_STRUCTURE,
                null, desc, CITED_DEFINE_XML + domain + ", structure='" + structure + "'"));
    }


    /**
     * Heuristically extracts key variable names from a structure description like "One record per
     * subject per parameter per analysis visit".
     */
    private List<String> parseStructureKeys(String structure, DataTableMeta meta)
    {
        // Map common structure tokens to variable names
        Map<String, String> tokenToVar = Map.ofEntries(Map.entry("subject", VAR_USUBJID),
                Map.entry("parameter", "PARAMCD"), Map.entry("visit", "AVISIT"),
                Map.entry("analysis visit", "AVISIT"), Map.entry("timepoint", "ATPT"),
                Map.entry("analysis timepoint", "ATPT"), Map.entry("event", "AETERM"),
                Map.entry("medication", "CMTRT"), Map.entry("period", "APERIOD"),
                Map.entry("basetype", "BASETYPE"), Map.entry("test", "TESTCD"),
                Map.entry("specimen", "SPEC"));

        List<String> result = new ArrayList<>();
        String lower = structure.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : tokenToVar.entrySet())
        {
            if (lower.contains(entry.getKey()) && meta.getColumnIndex(entry.getValue()) >= 0)
            {
                result.add(entry.getValue());
            }
        }
        return result;
    }


    // Category 23: Value-level metadata
    private void generateDefineValueLevelMetadata(List<Map<String, String>> defVars,
            DataTableMeta meta, String domain, List<Rule> rules, RuleGenerationReport report)
    {
        // Only reached from generateDefineXMLRules after its defineXMLProvider != null guard.
        DefineXMLProvider dxp = Objects.requireNonNull(defineXMLProvider);
        for (Map<String, String> dv : defVars)
        {
            String varName = dv.get("name");
            if (varName == null || meta.getColumnIndex(varName) < 0)
            {
                continue;
            }

            List<Map<String, String>> vlmEntries = dxp.getValueLevelMetadata(domain, varName);
            if (vlmEntries.isEmpty())
            {
                continue;
            }

            for (Map<String, String> vlm : vlmEntries)
            {
                String wcOID = vlm.get("whereClauseOID");
                if (wcOID == null)
                {
                    continue;
                }

                List<Map<String, String>> conditions = defineXMLProvider
                        .getWhereClauseConditions(wcOID);
                if (conditions.isEmpty())
                {
                    continue;
                }

                // Build the where-clause as Check conditions
                List<net.cumba.cdisc.core.model.CheckCondition> whereChecks = buildWhereClauseChecks(
                        conditions);
                if (whereChecks.isEmpty())
                {
                    continue;
                }

                // Check codelist if specified in the VLM entry
                String vlmCodelist = vlm.get(KEY_CODELIST);
                if (vlmCodelist != null && !vlmCodelist.isEmpty())
                {
                    List<Map<String, String>> terms = defineXMLProvider
                            .getCodelistTerms(vlmCodelist);
                    if (!terms.isEmpty())
                    {
                        List<String> codedValues = new ArrayList<>();
                        for (Map<String, String> t : terms)
                        {
                            String cv = t.get("codedValue");
                            if (cv != null && !cv.isEmpty())
                            {
                                codedValues.add(cv);
                            }
                        }
                        if (!codedValues.isEmpty())
                        {
                            String coreId = coreId("DXVLM", domain, varName + "-" + wcOID);
                            String desc = varName + " value-level codelist check for " + wcOID
                                    + ".";

                            // Combine: where-clause AND (non-empty AND not-in-list)
                            List<net.cumba.cdisc.core.model.CheckCondition> allChecks = new ArrayList<>(
                                    whereChecks);
                            allChecks.add(CheckConditionLeaf.builder().name(varName)
                                    .operator(OP_NON_EMPTY).build());
                            com.fasterxml.jackson.databind.node.ArrayNode arr = new com.fasterxml.jackson.databind.ObjectMapper()
                                    .createArrayNode();
                            for (String cv : codedValues)
                            {
                                arr.add(cv);
                            }
                            allChecks.add(CheckConditionLeaf.builder().name(varName)
                                    .operator(OP_IS_NOT_CONTAINED_BY).value(arr).build());

                            Rule rule = buildRule(coreId, desc, Sensitivity.RECORD,
                                    new CheckConditionAll(allChecks),
                                    varName + " value not in value-level codelist for " + wcOID
                                            + ".",
                                    List.of(varName), domain);
                            rules.add(rule);
                            report.addGenerated(new GeneratedRuleInfo(coreId,
                                    RuleCategory.DEFINE_VALUE_LEVEL_METADATA, varName, desc,
                                    CITED_DEFINE_XML + domain + "." + varName + ", VLM " + wcOID));
                        }
                    }
                }
            }
        }
    }


    /**
     * Converts Define-XML where-clause conditions (RangeChecks) into CheckCondition objects.
     * Multiple conditions are AND-ed.
     */
    private List<net.cumba.cdisc.core.model.CheckCondition> buildWhereClauseChecks(
            List<Map<String, String>> conditions)
    {
        List<net.cumba.cdisc.core.model.CheckCondition> checks = new ArrayList<>();
        for (Map<String, String> cond : conditions)
        {
            String variable = cond.get("variable");
            String comparator = cond.get("comparator");
            String values = cond.get("values");
            if (variable == null || comparator == null)
            {
                continue;
            }

            String operator = mapComparator(comparator);
            if (operator == null)
            {
                continue;
            }

            if ("IN".equalsIgnoreCase(comparator) || "NOTIN".equalsIgnoreCase(comparator))
            {
                // Multiple values
                String[] vals = values != null ? values.split(",") : new String[0];
                com.fasterxml.jackson.databind.node.ArrayNode arr = new com.fasterxml.jackson.databind.ObjectMapper()
                        .createArrayNode();
                for (String v : vals)
                {
                    arr.add(v.trim());
                }
                checks.add(CheckConditionLeaf.builder().name(variable).operator(operator).value(arr)
                        .build());
            }
            else
            {
                // Single value
                String val = values != null ? values.trim() : "";
                checks.add(CheckConditionLeaf.builder().name(variable).operator(operator)
                        .value(new TextNode(val)).valueIsLiteral(true).build());
            }
        }
        return checks;
    }


    /**
     * Maps Define-XML RangeCheck comparators to CORE Check operators.
     */
    private static @Nullable String mapComparator(String comparator)
    {
        if (comparator == null)
        {
            return null;
        }
        return switch (comparator.toUpperCase(Locale.ROOT))
        {
        case "EQ" -> "equal_to";
        case "NE" -> OP_NOT_EQUAL_TO;
        case "LT" -> "less_than";
        case "LE" -> "less_than_or_equal_to";
        case "GT" -> "greater_than";
        case "GE" -> "greater_than_or_equal_to";
        case "IN" -> "is_contained_by";
        case "NOTIN" -> OP_IS_NOT_CONTAINED_BY;
        default -> null;
        };
    }

    // ---- Rule builders ----


    private Rule buildVariableMetadataRule(@Nullable String coreId, @Nullable String description,
            String varName, String metadataField, @Nullable String expectedValue,
            @Nullable String domain)
    {
        // Use variable_name to restrict the VMC iteration to the target column.
        // An OP_EXISTS check is a dataset-level fact that would be true for ALL columns
        // in the iteration — causing the metadata comparison to fire on every column
        // whose label/type doesn't match the expected value, not just the target column.
        CheckConditionLeaf nameCheck = CheckConditionLeaf.builder().name("variable_name")
                .operator("equal_to").value(new TextNode(varName)).valueIsLiteral(true).build();
        CheckConditionLeaf valueCheck = CheckConditionLeaf.builder().name(metadataField)
                .operator(OP_NOT_EQUAL_TO).value(new TextNode(expectedValue)).valueIsLiteral(true)
                .build();

        return buildRule(coreId, description, Sensitivity.DATASET,
                new CheckConditionAll(List.of(nameCheck, valueCheck)),
                MSG_VARIABLE_PREFIX + varName + " does not match Library: " + description,
                List.of(varName), domain);
    }


    private Rule buildRequiredVariableRule(String coreId, String description, String varName,
            String domain)
    {
        CheckConditionLeaf check = CheckConditionLeaf.builder().name(varName)
                .operator(OP_NOT_EXISTS).build();

        return buildRule(coreId, description, Sensitivity.DATASET, check,
                "Required variable " + varName + " is missing from dataset.", null, domain);
    }


    Rule buildRule(@Nullable String coreId, @Nullable String description, Sensitivity sensitivity,
            net.cumba.cdisc.core.model.CheckCondition check, @Nullable String outcomeMessage,
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
            net.cumba.cdisc.core.model.DomainScope ds = new net.cumba.cdisc.core.model.DomainScope();
            ds.setInclude(List.of(domain));
            scope.setDomains(ds);
            rule.setScope(scope);
        }

        return rule;
    }

    // ---- Helpers ----


    boolean isEnabled(RuleCategory category)
    {
        return enabledCategories.contains(category);
    }


    /**
     * Returns {@code null} if the rule's scope matches this dataset (and is therefore eligible for
     * execution), or a short human-readable reason string if it should be skipped because of domain
     * / dataset-name / class / variable mismatch. Executability is not consulted — every
     * Executability value is treated as eligible, mirroring Python. {@code domainPrefix} (Phase 4)
     * resolves {@code --} placeholders in variable-requirement entries.
     */
    private @Nullable String describeScopeSkip(Rule r, String domName, DataTableMeta meta,
            @Nullable String domainPrefix, String unsplitName, List<String> detectedStructures,
            List<String> detectedSubclasses,
            net.cumba.cdisc.core.exec.@Nullable ScopeVariableSource scopeForeign)
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
        // dataset. It is null when this generator has no inventory-capable resolver, in which case
        // qualified entries are ignored and the caller emits a one-time WARN.
        return ScopeMatcher.describeVariablesMismatch(r, meta, domainPrefix, scopeForeign);
    }

    /**
     * Mapping from RuleGenerator category codes to ADaM Conformance Rule IDs. When a generated rule
     * corresponds to a known conformance rule, the conformance ID is used as a prefix for
     * traceability.
     */
    private static final Map<String, String> CONFORMANCE_RULE_MAP = Map.ofEntries(
            Map.entry("LBL", "CDISC-AD0709"), // Variable label vs IG
            Map.entry("TYP", "CDISC-AD0200"), // Variable type vs IG
            Map.entry("XDLBL", "CDISC-AD0085"), // Variable label vs ADSL
            Map.entry("XDTYP", "CDISC-AD0590"), // Variable type vs ADSL
            Map.entry("XDFMT", "CDISC-AD0086"), // Variable format vs ADSL
            Map.entry("XDVAL", "CDISC-AD0591"), // Variable values vs ADSL
            Map.entry("IDX-TRTPRES", "CDISC-AD0719"), // Treatment variable presence
            Map.entry("IDX-FLPRES", "CDISC-AD0048") // Population flag presence
    );

    String coreId(String categoryCode, String domain, String variable)
    {
        // Only use ADaM conformance rule ID mappings when validating ADaM standards.
        // For SDTM (or any other standard), use the generic GEN- prefix to avoid
        // producing misleading CDISC-AD* rule IDs in non-ADaM validation reports.
        if (isAdamStandard())
        {
            String crId = CONFORMANCE_RULE_MAP.get(categoryCode);
            if (crId != null)
            {
                return crId + "-" + domain + "-" + variable;
            }
        }
        return "GEN-" + categoryCode + "-" + domain + "-" + variable;
    }


    String coreId(String categoryCode, String domain)
    {
        if (isAdamStandard())
        {
            String crId = CONFORMANCE_RULE_MAP.get(categoryCode);
            if (crId != null)
            {
                return crId + "-" + domain;
            }
        }
        return "GEN-" + categoryCode + "-" + domain;
    }


    private boolean isAdamStandard()
    {
        String std = provider != null ? provider.getStandard() : null;
        return std != null && std.toLowerCase(Locale.ROOT).contains("adam");
    }


    String deterministicUuid(String coreId)
    {
        return UUID.nameUUIDFromBytes(coreId.getBytes(StandardCharsets.UTF_8)).toString();
    }


    MetadataProvider getProvider()
    {
        return provider;
    }


    @Nullable
    DictionaryProvider getDictionaryProvider()
    {
        return dictionaryProvider;
    }


    @Nullable
    String getCtPackageVersion()
    {
        return ctPackageVersion;
    }


    static @Nullable String mapSimpleDatatype(@Nullable String libraryType)
    {
        if (libraryType == null)
        {
            return null;
        }
        return switch (libraryType.toLowerCase(Locale.ROOT))
        {
        case "char", "text", "varchar" -> "Char";
        case "num", "integer", "float", "double" -> "Num";
        default -> libraryType;
        };
    }

}
