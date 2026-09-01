package net.cumba.cdisc.core.exec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

import net.cumba.cdisc.core.gen.WildcardExpander;
import net.cumba.cdisc.core.model.ClassScope;
import net.cumba.cdisc.core.model.DatasetScope;
import net.cumba.cdisc.core.model.DomainScope;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.Scope;
import net.cumba.cdisc.core.model.VariableRequirement;
import net.cumba.datatable.DataTableMeta;
import org.jspecify.annotations.Nullable;

/**
 * Checks whether a rule's {@link Scope} matches a given domain name, observation class, and/or use
 * case. Rules without a scope (or without domain/class constraints) are considered to match all
 * datasets.
 */
public final class ScopeMatcher
{

    /** Wildcard value meaning "all domains" or "all classes". */
    private static final String ALL = "ALL";

    /** Placeholder in Exclude meaning "exclude nothing" (no-op). */
    private static final String NONE = "NONE";

    /** Wildcard suffix in domain patterns (e.g., {@code SUPP--}, {@code AP--}). */
    private static final String WILDCARD = "--";

    /** {@link #normalize Normalised} class name for {@code FINDINGS ABOUT}. */
    private static final String FINDINGS_ABOUT_NORM = "FINDINGSABOUT";

    /** {@link #normalize Normalised} class name for {@code FINDINGS}. */
    private static final String FINDINGS_NORM = "FINDINGS";

    private ScopeMatcher()
    {
    }


    /**
     * Returns {@code true} if the rule applies to the given domain.
     * <p>
     * Supports the {@code ALL} wildcard in Include lists, and the {@code --} wildcard pattern
     * (e.g., {@code SUPP--} matches any 6-character domain starting with {@code SUPP}; {@code AP--}
     * matches any 4-character domain starting with {@code AP}). The {@code --} contract is
     * <b>strict</b>: exactly two characters, never "any suffix" — longer split forms are reached
     * through the callers' data-derived split-base re-test, not by relaxing the token. See
     * {@link #describeDomainMismatch(Rule, String, String)}.
     * </p>
     * <p>
     * ⚠ This is the <em>table-less</em> API: it resolves the split base from the name alone. See
     * {@link #describeDomainMismatch(Rule, String)} for what that costs on a SUPP/AP letter-suffix
     * split, and prefer the three-argument describer when a dataset is available.
     * </p>
     *
     * @param rule
     *            the rule to check
     * @param domainName
     *            the domain name of the target table (e.g. "DM", "AE")
     * @return true if the rule should be executed against this domain
     */
    public static boolean matchesDomain(Rule rule, String domainName)
    {
        return describeDomainMismatch(rule, domainName) == null;
    }


    /**
     * Reason-bearing variant of {@link #matchesDomain}: returns {@code null} when the rule's domain
     * scope matches the dataset, or a short human-readable message naming the failing criterion and
     * the responsible scope entry (e.g. {@code "domain EX not in Scope.Domains.Include [AE, CM]"}
     * or {@code "domain SUPPAE matches Scope.Domains.Exclude entry SUPP--"}). The boolean API is
     * implemented on top of this method, so the two can never diverge.
     * <p>
     * ⚠ <b>Prefer {@link #describeDomainMismatch(Rule, String, String)} whenever a dataset is in
     * hand.</b> This table-less overload has no data to read, so it derives the split base from the
     * <em>name</em> via {@link SplitDatasetUtil#unsplitName}, and the two do not always agree. The
     * name heuristic strips a single trailing letter, the data path resolves the real parent:
     * {@code SUPPLBHM} becomes {@code SUPPLBH} (7 characters) here but {@code SUPPLB} (6) from
     * {@code RDOMAIN=LB} — and the strict {@code --} contract wants exactly 6, so a rule scoped
     * {@code Include: ["SUPP--"]} <b>silently misses</b> that dataset on this path and matches it
     * on the other. The divergence needs a SUPP/AP letter-suffix split to appear; digit-suffix
     * splits ({@code LB1}) agree on both paths.
     * <p>
     * Nothing in the engine's own execution path is affected: {@code RuleGenerator} computes
     * {@code unsplitName} from the dataset ({@code OperationExecutor.unsplitNameFromData}) and
     * calls the three-argument overload. This overload survives for callers that genuinely have
     * only a name — it is <em>not</em> a shorthand for the data-driven one. No guard is placed on
     * it deliberately: the shortened base is a legitimate answer to the question actually asked
     * ("what does this name look like?"), and there is no signal at this point that would let a
     * guard distinguish a caller that has no table from one that merely forgot to pass it.
     * </p>
     *
     * @param rule
     *            the rule to check
     * @param domainName
     *            the domain name of the target table (e.g. "DM", "AE")
     * @return {@code null} when matching, otherwise the mismatch description
     */
    public static @Nullable String describeDomainMismatch(Rule rule, String domainName)
    {
        // Table-less callers fall back to the name-pattern base. SplitDatasetUtil.unsplitName
        // shortens the name exactly when SplitDatasetUtil.isSplitDataset is true, so deriving
        // isSplit from `!domainName.equals(base)` reproduces the legacy heuristic precisely.
        return describeDomainMismatch(rule, domainName,
                domainName == null ? null : SplitDatasetUtil.unsplitName(domainName));
    }


    /**
     * Data-driven variant of {@link #describeDomainMismatch(Rule, String)} that takes the dataset's
     * canonical unsplit (base) name — computed from the {@code DOMAIN}/{@code RDOMAIN} columns via
     * {@link OperationExecutor#unsplitNameFromData} — rather than guessing it from the name. This
     * is what mirrors Python's {@code SDTMDatasetMetadata.is_split}/{@code unsplit_name}: a dataset
     * named {@code FAAE} carrying {@code DOMAIN=FA} has {@code unsplitName="FA"} and is therefore a
     * split of FA, which the name-only heuristic ({@link SplitDatasetUtil#isSplitDataset}) misses.
     * The dataset is a split iff {@code domainName} differs from {@code unsplitName}, and the base
     * used for Include/Exclude split re-tests is {@code unsplitName}.
     * <p>
     * <b>There is no SUPP/AP "family wildcard".</b> Fix #34 used to add a third leg here —
     * {@code firstMatchingSuppApFamilyEntry} — under which <em>any</em> of {@code SUPP--},
     * {@code SQ--}, {@code AP--}, {@code APFA--} in the list matched <em>any</em> dataset whose
     * name began with {@code SUPP} / {@code SQ} / {@code AP}, regardless of length and regardless
     * of which family the token named. Its warrant was *"a known Python design quirk that Java
     * mirrors for parity"*; java-first (2026-08-03) removed parity as a constraint, and the quirk's
     * only unique contribution was <em>cross-family</em> reach — {@code Exclude: ["SUPP--"]}
     * silently excluding {@code APMH}, and {@code Include: ["AP--"]} silently including
     * {@code SUPPLB}. It is deleted; the four tokens are independent and each means exactly what
     * the strict {@code --} contract says.
     * </p>
     * <p>
     * Nothing is lost on the split forms, because they were never the family wildcard's work: the
     * base re-test above already covers them, and it does so <em>from the data</em> rather than by
     * guessing from the name. {@code SUPPLBHM} carrying {@code RDOMAIN=LB} resolves to the base
     * {@code SUPPLB} (6 characters ⇒ strict {@code SUPP--} matches); an {@code SQ…} dataset
     * resolves to {@code SQ} + {@code RDOMAIN} (e.g. {@code SQLB} ⇒ strict {@code SQ--} matches);
     * {@code APMH1} resolves to {@code APMH} (⇒ strict {@code AP--} matches). The one shape strict
     * {@code SUPP--} cannot express, {@code SUPPAPFAMH} (base {@code SUPPAPFA}, 8 characters), is
     * precisely the shape <b>SDTMIG v3.4 §8.4.2</b> requires to be renamed {@code SQAPFAMH}.
     * </p>
     * <p>
     * ⚠ A {@code --} token whose prefix is itself a 2-character <em>domain code</em> (e.g.
     * {@code FA--}) is broken by construction and must never be authored: it demands a 4-character
     * name, so it catches {@code FALB} but misses the split {@code FALBHM}, whose data-derived base
     * is the 2-character {@code FA}. {@code RulePackageLoader} emits a load warning for such a
     * token; the correct scope is the plain domain code, {@code Include: ["FA"]}.
     * </p>
     * <p>
     * <b>{@code include_split_datasets} is CONJUNCTIVE.</b> {@code true} means "splits only" — the
     * dataset must be a split <em>and</em> satisfy Include/Exclude; {@code false} means "non-splits
     * only"; absent means no split filtering. ⚠ This is a deliberate <b>java-only</b> divergence
     * from Python's {@code rule_processor._handle_split_domains}, which applies {@code true}
     * <em>additively</em>: there, a non-empty Include list is overridden for every split dataset in
     * the study, so {@code Include: ["AP--"] + include_split_datasets: true} (CDISC-CG0650 /
     * CORE-000778) makes the {@code AP--} token inert and runs the rule study-wide. Python's
     * <em>no-Include</em> branch is already the conjunctive gate; the divergence is only that Java
     * now applies the same gate when an Include list is present.
     * </p>
     *
     * @param rule
     *            the rule to check
     * @param domainName
     *            the dataset name (e.g. "DM", "FAAE")
     * @param unsplitName
     *            the dataset's canonical base name (e.g. "FA" for "FAAE"); when {@code null} the
     *            dataset is treated as not a split
     * @return {@code null} when matching, otherwise the mismatch description
     */
    public static @Nullable String describeDomainMismatch(Rule rule, String domainName,
            @Nullable String unsplitName)
    {
        if (domainName == null)
        {
            return null;
        }
        Scope scope = rule.getScope();
        if (scope == null)
        {
            return null;
        }
        DomainScope domains = scope.getDomains();
        if (domains == null)
        {
            return null;
        }
        List<String> include = domains.getInclude();
        List<String> exclude = domains.getExclude();

        // Data-driven split detection (mirrors Python is_split): the dataset is a split iff its
        // name differs from its canonical base. `base` is the data-derived unsplit name used for
        // the Include/Exclude split re-tests.
        boolean isSplit = unsplitName != null && !domainName.equals(unsplitName);
        String base = unsplitName != null ? unsplitName : domainName;

        boolean hasInclude = include != null && !include.isEmpty();
        boolean hasExclude = exclude != null && !exclude.isEmpty();
        Boolean splitFilter = domains.getIncludeSplitDatasets();

        // `include_split_datasets` is a CONJUNCTIVE tri-state gate applied on top of the
        // Include/Exclude decision, never an additive one:
        //
        // null — no split filtering;
        // true — the dataset must be a split AND must satisfy Include/Exclude;
        // false — the dataset must NOT be a split, and must satisfy Include/Exclude.
        //
        // ⚠ This is a deliberate JAVA-ONLY divergence from Python's
        // rule_processor._handle_split_domains, which applies `true` ADDITIVELY — there, a
        // non-empty Include list is overridden for every split dataset in the study, so
        // `Include: [AP--] + include_split_datasets: true` (CDISC-CG0650 / CORE-000778) runs
        // study-wide and the `AP--` token is inert. Java previously mirrored that; java-first
        // (2026-08-03) removed parity as a constraint. Two independent authorities settle the
        // reading: CDISC-CG0650's `Source` block carries `Class: "AP"` (the family restriction is
        // authored, exactly as sibling CDISC-CG0017 carries `Class: "NOT (AP)"`), and the rule's
        // `<= 4` lower bound is only coherent when the population really is AP splits. The two
        // legs Python gets wrong are (a) a non-split that matches Include staying in scope under
        // `true`, and (b) a split that misses Include being force-included.
        //
        // Note the Python no-Include branch (`if include_split_datasets is True and not is_split:
        // return False`) is already exactly this conjunctive gate; the divergence is only that
        // Java now applies the SAME gate when an Include list is present.

        // _is_domain_name_included
        boolean matchedInclude;
        if (!hasInclude || include == null)
        {
            matchedInclude = true;
        }
        else
        {
            // Include list present: match the name or the split base (Python's `domain` /
            // `unsplit_name in included`). There is no third, family-wildcard leg: `SUPP--`,
            // `SQ--`, `AP--` and `APFA--` are four INDEPENDENT strict `--` tokens.
            matchedInclude = firstMatchingDomainEntry(include, domainName) != null
                    || (isSplit && firstMatchingDomainEntry(include, base) != null);
        }

        // _is_domain_name_excluded
        String excludeEntry = null;
        if (hasExclude && exclude != null)
        {
            excludeEntry = firstMatchingDomainEntry(exclude, domainName);
            if (excludeEntry == null && isSplit)
            {
                // Python: `unsplit_name in excluded` — exclude a split whose base matches.
                excludeEntry = firstMatchingDomainEntry(exclude, base);
            }
        }
        boolean excluded = excludeEntry != null;

        // The split gate. `true` demands split-ness conjunctively (see the note above); `false`
        // rejects splits through the exclusion channel so the reason message names the flag.
        boolean splitGateFailed = Boolean.TRUE.equals(splitFilter) && !isSplit;
        boolean included = matchedInclude && !splitGateFailed;
        if (Boolean.FALSE.equals(splitFilter) && isSplit)
        {
            // false excludes split datasets.
            excluded = true;
        }

        // return is_included and not is_excluded
        if (!included)
        {
            if (!matchedInclude)
            {
                return "domain " + domainName + " not in Scope.Domains.Include " + include;
            }
            // Reachable whenever include_split_datasets=true meets a non-split dataset, whether or
            // not an Include list is present.
            return "domain " + domainName
                    + " is not a split dataset but Scope.Domains.Include_Split_Datasets is true";
        }
        if (excluded)
        {
            if (excludeEntry != null)
            {
                return "domain " + domainName + " matches Scope.Domains.Exclude entry "
                        + excludeEntry;
            }
            // Excluded purely because include_split_datasets=false rejects splits.
            return "domain " + domainName
                    + " is a split dataset but Scope.Domains.Include_Split_Datasets is false";
        }
        // Included and not excluded
        return null;
    }


    /**
     * Returns {@code true} if the rule's {@code Scope.Datasets} admits this dataset name. See
     * {@link #describeDatasetMismatch}.
     *
     * @param rule
     *            the rule to check
     * @param datasetName
     *            the MEMBER dataset name (the file), not the domain code
     * @return whether the rule is in scope for this dataset name
     */
    public static boolean matchesDatasets(Rule rule, @Nullable String datasetName)
    {
        return describeDatasetMismatch(rule, datasetName) == null;
    }


    /**
     * Reason-bearing {@code Scope.Datasets} matcher (owner requirement #5,
     * {@code plans/PLAN-scope-requirements-split.md} &#167;4.6) — <b>{@code Scope.Domains} minus
     * the split-base re-test</b>.
     *
     * <p>
     * ⚠⚠ The absence of that re-test <b>is</b> the feature, and it is the trap to document rather
     * than fix. On a split submission {@code Scope.Domains: ["LB"]} selects {@code LB1}/{@code LB2}
     * through the data-derived unsplit name; {@code Scope.Datasets: ["LB"]} selects <b>nothing</b>;
     * and {@code ds_exists("LB")} answers <b>true</b> (widened since {@code Fix #358}). Three
     * vocabularies, three answers — {@code Fix #358} widened <em>presence</em>, not <em>name
     * matching</em>, so the axes do not meet and there is no conflict to resolve.
     * </p>
     *
     * <p>
     * ⚠ It matches the <b>member file name</b>, not the domain code, so a caller must pass
     * {@code meta.getName()} where it has one. Entry vocabulary is {@code Scope.Domains}' —
     * {@link #firstMatchingDomainEntry}: the {@code ALL}/{@code NONE} sentinels, {@code /regex/},
     * glob, the strict {@code --} token and literal equality after {@link #normalize}. Glob,
     * {@code /regex/} and {@code NONE} are coreJ-only and not upstream-portable.
     * </p>
     *
     * <p>
     * ⚠ {@code include_split_datasets} is deliberately <b>not</b> offered: it is a statement about
     * domain families and has no meaning on a name axis. A rule wanting split parts by name writes
     * {@code LB?} or a whole-entry regex. Consequently a {@code SUPP--} entry, which matches a
     * 6-character name exactly, matches <b>nothing</b> on a real split submission whose member is
     * the 8-character {@code SUPPLBCH} — such a rule needs a glob or must stay on
     * {@code Scope.Domains}.
     * </p>
     *
     * @param rule
     *            the rule to check
     * @param datasetName
     *            the MEMBER dataset name
     * @return {@code null} when matching, otherwise the mismatch description
     */
    public static @Nullable String describeDatasetMismatch(Rule rule, @Nullable String datasetName)
    {
        Scope scope = rule.getScope();
        if (datasetName == null || scope == null || scope.getDatasets() == null)
        {
            return null;
        }
        DatasetScope datasets = scope.getDatasets();
        List<String> include = datasets.getInclude();
        List<String> exclude = datasets.getExclude();
        if (include != null && !include.isEmpty()
                && firstMatchingDomainEntry(include, datasetName) == null)
        {
            return "dataset " + datasetName + " not in Scope.Datasets.Include " + include;
        }
        if (exclude != null && !exclude.isEmpty())
        {
            String hit = firstMatchingDomainEntry(exclude, datasetName);
            if (hit != null)
            {
                return "dataset " + datasetName + " matches Scope.Datasets.Exclude entry " + hit;
            }
        }
        return null;
    }


    /**
     * Returns {@code true} if the rule applies to the given observation class.
     * <p>
     * Supports the {@code ALL} wildcard in Include lists.
     * </p>
     *
     * @param rule
     *            the rule to check
     * @param className
     *            the observation class (e.g. "EVENTS", "SPECIAL PURPOSE")
     * @return true if the rule's class scope matches
     */
    public static boolean matchesClass(Rule rule, @Nullable String className)
    {
        return describeClassMismatch(rule, className) == null;
    }


    /**
     * Reason-bearing variant of {@link #matchesClass}: returns {@code null} when the rule's class
     * scope matches, or a short human-readable message naming the failing criterion and the
     * responsible scope entry (e.g. {@code "class EVENTS not in Scope.Classes.Include [FINDINGS]"}
     * or {@code "dataset class undetermined but rule has a Classes scope"}). The boolean API is
     * implemented on top of this method, so the two can never diverge.
     *
     * @param rule
     *            the rule to check
     * @param className
     *            the observation class (e.g. "EVENTS", "SPECIAL PURPOSE"), or {@code null} when
     *            undetermined
     * @return {@code null} when matching, otherwise the mismatch description
     */
    public static @Nullable String describeClassMismatch(Rule rule, @Nullable String className)
    {
        Scope scope = rule.getScope();
        if (scope == null)
        {
            return null;
        }
        ClassScope classes = scope.getClasses();
        if (classes == null)
        {
            return null;
        }
        if (className == null)
        {
            // Fix #41: strict-on-null. Mirrors Python's
            // rule_processor.rule_applies_to_class:255 — when the dataset's class can't be
            // determined and the rule carries an Include or Exclude class scope, the rule is
            // rejected. Permissive only when neither list is set (i.e., the rule isn't
            // class-scoped at all). RuleGenerator emits a one-time WARN per dataset listing how
            // many rules were skipped due to this path so the change is discoverable.
            boolean hasInclude = classes.getInclude() != null && !classes.getInclude().isEmpty();
            boolean hasExclude = classes.getExclude() != null && !classes.getExclude().isEmpty();
            if (hasInclude || hasExclude)
            {
                return "dataset class undetermined but rule has a Classes scope";
            }
            return null;
        }
        List<String> include = classes.getInclude();
        List<String> exclude = classes.getExclude();
        boolean isFindingsAbout = FINDINGS_ABOUT_NORM.equals(normalize(className));
        if (include != null && !include.isEmpty()
                && firstMatchingClassEntry(include, className) == null
                && !(isFindingsAbout && containsFindings(include)))
        {
            return "class " + className + " not in Scope.Classes.Include " + include;
        }
        if (exclude != null && !exclude.isEmpty())
        {
            String entry = firstMatchingClassEntry(exclude, className);
            if (entry == null && isFindingsAbout)
            {
                // FINDINGS ABOUT datasets are subsumed under FINDINGS-scoped excludes.
                entry = firstFindingsEntry(exclude);
            }
            if (entry != null)
            {
                return "class " + className + " matches Scope.Classes.Exclude entry " + entry;
            }
        }
        return null;
    }


    /**
     * Returns {@code true} if {@code patterns} contains an entry that {@link #normalize normalises}
     * to {@link #FINDINGS_NORM}. Mirrors Python's {@code rule_processor.py} subsumption rule
     * whereby {@code FINDINGS ABOUT} datasets satisfy {@code FINDINGS}- scoped rules — applied
     * symmetrically to both Include and Exclude lists.
     */
    private static boolean containsFindings(List<String> patterns)
    {
        return firstFindingsEntry(patterns) != null;
    }


    /**
     * Returns the first entry that {@link #normalize normalises} to {@link #FINDINGS_NORM}, or
     * {@code null} when none does. Entry-returning core of {@link #containsFindings}, used by
     * {@link #describeClassMismatch} to name the responsible Exclude entry.
     */
    private static @Nullable String firstFindingsEntry(List<String> patterns)
    {
        for (String p : patterns)
        {
            if (FINDINGS_NORM.equals(normalize(p)))
            {
                return p;
            }
        }
        return null;
    }


    /**
     * Returns {@code true} if the rule applies to the given use case.
     * <p>
     * Rules without a {@code Use_Case} scope apply to all use cases. The {@code Use_Case} field is
     * a comma-separated string of codes (e.g., {@code "INDH, PROD"}).
     * </p>
     *
     * @param rule
     *            the rule to check
     * @param useCase
     *            the use case code (e.g. "INDH", "PROD", "NONCLIN")
     * @return true if the rule applies to the given use case
     */
    public static boolean matchesUseCase(Rule rule, String useCase)
    {
        if (useCase == null)
        {
            return true;
        }
        Scope scope = rule.getScope();
        if (scope == null || scope.getUseCase() == null || scope.getUseCase().isEmpty())
        {
            return true;
        }
        return Arrays.stream(scope.getUseCase().split(",")).map(String::trim)
                .anyMatch(useCase::equalsIgnoreCase);
    }


    /**
     * Filters a collection of rules to those applicable to the given use case. Intended to be
     * invoked at the application boundary (the caller of
     * {@link RuleRunner#execute(Rule, net.cumba.datatable.IDataTable, DatasetResolver, String, MetadataProvider)}),
     * not inside {@code RuleRunner} itself — keeps use- case selection in the scope layer where it
     * belongs. A {@code null} {@code useCase} returns all rules unchanged.
     * <p>
     * Fix #9.a.
     * </p>
     *
     * @param rules
     *            the rules to filter (not {@code null})
     * @param useCase
     *            the selected use case (e.g. {@code "INDH"}, {@code "PROD"}, {@code "NONCLIN"}), or
     *            {@code null} to disable use-case filtering
     * @return rules whose {@code Use_Case} includes {@code useCase} (case-insensitive), or all
     *         rules unchanged when {@code useCase} is {@code null}
     */
    public static List<Rule> filterByUseCase(Collection<Rule> rules, String useCase)
    {
        if (useCase == null)
        {
            return new ArrayList<>(rules);
        }
        List<Rule> out = new ArrayList<>(rules.size());
        for (Rule rule : rules)
        {
            if (matchesUseCase(rule, useCase))
            {
                out.add(rule);
            }
        }
        return out;
    }


    /**
     * Returns {@code true} if the rule applies to a dataset with the given metadata.
     * <p>
     * When the rule declares a {@link VariableRequirement}, the dataset must contain <b>all</b>
     * variables listed in {@code All}, at least one of {@code Any}, and <b>none</b> of the
     * variables listed in {@code None}. Rules without a variable requirement match all datasets.
     * </p>
     *
     * @param rule
     *            the rule to check
     * @param meta
     *            the dataset metadata (provides column names via
     *            {@link DataTableMeta#getColumnIndex(String)})
     * @return true if the dataset satisfies the rule's variable scope
     */
    public static boolean matchesVariables(Rule rule, DataTableMeta meta)
    {
        return describeVariablesMismatch(rule, meta, null) == null;
    }


    /**
     * Variant of {@link #matchesVariables(Rule, DataTableMeta)} that resolves {@code --}-prefix
     * entries against the dataset's variable wildcard prefix (e.g. {@code --SEQ} → {@code AESEQ}
     * when {@code domainPrefix} is {@code "AE"}) before matching. See
     * {@link #describeVariablesMismatch(Rule, DataTableMeta, String)} for the entry semantics.
     *
     * @param rule
     *            the rule to check
     * @param meta
     *            the dataset metadata (provides column names via
     *            {@link DataTableMeta#getColumnIndex(String)})
     * @param domainPrefix
     *            the variable wildcard prefix used to resolve a leading {@code --} ("" for SUPP/SQ,
     *            the AP parent suffix for AP, else the domain code), or {@code null} when
     *            unresolved (entries are then looked up verbatim)
     * @return true if the dataset satisfies the rule's variable scope
     */
    public static boolean matchesVariables(Rule rule, DataTableMeta meta,
            @Nullable String domainPrefix)
    {
        return describeVariablesMismatch(rule, meta, domainPrefix) == null;
    }


    /**
     * Reason-bearing variant of {@link #matchesVariables}: returns {@code null} when the rule's
     * variable scope matches the dataset, or a short human-readable message naming the failing
     * criterion and the responsible variable (e.g. {@code "Requirements.Variables.All variable
     * AESTDTC not present in dataset"}). The boolean API is implemented on top of this method, so
     * the two can never diverge.
     *
     * @param rule
     *            the rule to check
     * @param meta
     *            the dataset metadata (provides column names via
     *            {@link DataTableMeta#getColumnIndex(String)})
     * @return {@code null} when matching, otherwise the mismatch description
     */
    public static @Nullable String describeVariablesMismatch(Rule rule, DataTableMeta meta)
    {
        return describeVariablesMismatch(rule, meta, null);
    }


    /**
     * Variant of {@link #describeVariablesMismatch(Rule, DataTableMeta)} that resolves
     * {@code --}-prefix entries against the dataset's variable wildcard prefix and supports
     * glob/regex pattern entries ({@link #scopePattern}). Per entry:
     * <ul>
     * <li>a leading {@code --} is first replaced by {@code domainPrefix} when it is exactly two
     * characters (mirroring the expression language's {@code --} resolution, e.g. {@code --SEQ} →
     * {@code AESEQ}); otherwise the entry keeps its raw form and the lookup simply misses;</li>
     * <li>a pattern entry ({@code *}/{@code ?} glob or {@code /…/} regex) is satisfied when <b>at
     * least one</b> column name matches (anchored full match, case-insensitive) — so an
     * {@code Exclude} pattern rejects the dataset when <em>any</em> column matches;</li>
     * <li>an entry carrying the wildcard markers ({@code xx}, {@code zz}, {@code y}, {@code w} —
     * e.g. {@code TRTxxP}, see
     * {@link net.cumba.cdisc.core.gen.WildcardExpander#scopeVariableWildcardPattern}) is likewise
     * satisfied when at least one column matches the marker pattern (anchored, case-sensitive — the
     * same regex the wildcard expansion matches against the Check), so a template scoped to
     * {@code TRTxxP} applies when {@code TRT01P} exists and is skipped — naming the entry — when no
     * concrete column matches;</li>
     * <li>a literal entry keeps the exact-lookup semantics
     * ({@link DataTableMeta#getColumnIndex(String)}).</li>
     * </ul>
     * {@code --} resolution happens before pattern detection, so {@code --*DT} (prefix + glob)
     * composes naturally.
     * <p>
     * <b>This overload is qualified-blind.</b> A cross-dataset entry ({@code DM.ARM}, Fix #124)
     * needs foreign metadata that only a {@link ScopeVariableSource} can supply, so this signature
     * <em>ignores</em> such entries — the rule is never skipped on their account. Production paths
     * must call
     * {@link #describeVariablesMismatch(Rule, DataTableMeta, String, ScopeVariableSource)}.
     * </p>
     *
     * @param rule
     *            the rule to check
     * @param meta
     *            the dataset metadata (provides column names via
     *            {@link DataTableMeta#getColumnIndex(String)})
     * @param domainPrefix
     *            the variable wildcard prefix used to resolve a leading {@code --} ("" for SUPP/SQ,
     *            the AP parent suffix for AP, else the domain code), or {@code null} when
     *            unresolved
     * @return {@code null} when matching, otherwise the mismatch description
     */
    public static @Nullable String describeVariablesMismatch(Rule rule, DataTableMeta meta,
            @Nullable String domainPrefix)
    {
        return describeVariablesMismatch(rule, meta, domainPrefix, null);
    }


    /**
     * Fix #124 variant of {@link #describeVariablesMismatch(Rule, DataTableMeta, String)} that can
     * also decide <b>qualified</b> entries — {@code DATASET.VARIABLE} forms naming a variable in
     * another dataset ({@code DM.ARM}, {@code ADSL.TRTxxPN}, {@code SUPP--.QVAL}). An entry is
     * qualified per {@link ScopeVariableEntry#parse}; the variable half keeps every semantic the
     * unqualified form has (literal, glob, {@code /…/} regex, wildcard-marker template), while the
     * qualifier is resolved through {@code foreign}.
     * <p>
     * Include requires the foreign dataset to exist <em>and</em> to carry the variable; Exclude
     * rejects only when both hold — so a rule guarded by {@code Include: [DM.ARM]} is skipped (with
     * a reason naming the dataset) when DM is absent, instead of silently evaluating against an
     * unresolved join.
     * </p>
     * <p>
     * When {@code foreign} is {@code null} the resolver in effect cannot enumerate datasets (see
     * {@link ScopeVariableSource#of}) and qualified entries are <b>ignored</b> — the rule is not
     * skipped. Without an inventory there is no way to tell "the dataset is absent" from "this
     * resolver cannot see other datasets", and skipping on the latter would silence every qualified
     * rule on the resolver-less preview paths. Callers surface a one-time WARN instead.
     * </p>
     *
     * @param rule
     *            the rule to check
     * @param meta
     *            the primary dataset's metadata
     * @param domainPrefix
     *            the variable wildcard prefix used to resolve a leading {@code --} in an
     *            <em>unqualified</em> entry, or {@code null}
     * @param foreign
     *            the foreign-metadata source, or {@code null} when qualified entries cannot be
     *            evaluated
     * @return {@code null} when matching, otherwise the mismatch description
     */
    public static @Nullable String describeVariablesMismatch(Rule rule, DataTableMeta meta,
            @Nullable String domainPrefix, @Nullable ScopeVariableSource foreign)
    {
        if (meta == null)
        {
            return null;
        }
        // ⚠ Read through effectiveVariableRequirement(): it is the single documented reader of
        // the variable requirement. Scope carries no Variables property any more — a surviving one
        // is loader gate R1's error, not a field to fall back on.
        VariableRequirement required = rule.effectiveVariableRequirement();
        if (required == null)
        {
            return null;
        }
        List<String> all = required.getAll();
        if (all != null && !all.isEmpty())
        {
            for (String varName : all)
            {
                String reason = describeIncludeEntry(varName, meta, domainPrefix, foreign);
                if (reason != null)
                {
                    return reason;
                }
            }
        }
        List<String> any = required.getAny();
        if (any != null && !any.isEmpty())
        {
            String reason = describeAnyLeg(any, meta, domainPrefix, foreign);
            if (reason != null)
            {
                return reason;
            }
        }
        List<String> none = required.getNone();
        if (none != null && !none.isEmpty())
        {
            for (String varName : none)
            {
                String reason = describeExcludeEntry(varName, meta, domainPrefix, foreign);
                if (reason != null)
                {
                    return reason;
                }
            }
        }
        return null;
    }


    /**
     * {@code Any} leg: satisfied as soon as ONE entry is present. Returns a mismatch description
     * only when EVERY entry is absent — no single entry is at fault, so the message names the list.
     *
     * <p>
     * Entry disposition is {@link #describeIncludeEntry}'s, <b>unchanged</b>: {@code Any} and
     * {@code All} share one matcher path, so the two can never disagree about what "present" means.
     * A qualified entry whose dataset is unavailable is a mismatch there (the
     * {@code metas.isEmpty()} arm, after the SUPP-QNAM pivot), which is exactly the "counts as
     * absent" behaviour a disjunction needs.
     * </p>
     *
     * <p>
     * ⚠ It <b>short-circuits</b> on the first satisfied entry rather than counting nulls. The
     * difference is not stylistic: a two-entry {@code Any} whose FIRST entry is absent must still
     * be satisfied by the second, so a fixture that only ever removes the second entry cannot tell
     * a correct implementation from one that answers on entry 1 — the mirror image of
     * {@code M3-J.5}'s conjunction trap, and both fixtures are written.
     * </p>
     *
     * <p>
     * ⭐ <b>The one residual, stated because it has zero carriers today.</b> Under
     * {@code foreign == null} — generation time, where {@code RuleGenerator.describeScopeSkip}
     * deliberately passes null so a resolver-less preview cannot skip every qualified rule —
     * {@link #describeIncludeEntry} answers "satisfied" for <em>every</em> qualified entry. Because
     * this leg is a disjunction that short-circuits, <b>one</b> qualified entry anywhere in the
     * list makes the whole leg vacuously satisfied there — not merely a qualified-only list, which
     * is how {@code plans/PLAN-scope-requirements-split.md} &#167;4.3 words it. ({@code All} does
     * not widen the same way: it must satisfy every entry, so an unqualified sibling still decides
     * it.) That is the same conservative direction {@code All} takes and it is deliberate — it
     * prevents generation-time skips — and none of the ten rules adopting {@code Any} carries a
     * qualified entry, so it is written down rather than discovered.
     * </p>
     */
    private static @Nullable String describeAnyLeg(List<String> any, DataTableMeta meta,
            @Nullable String domainPrefix, @Nullable ScopeVariableSource foreign)
    {
        for (String varName : any)
        {
            if (describeIncludeEntry(varName, meta, domainPrefix, foreign) == null)
            {
                return null; // short-circuit: one present entry satisfies the whole leg
            }
        }
        return "no variable of Requirements.Variables.Any " + any + " present in dataset";
    }


    /**
     * Fix #124: returns {@code true} when the rule's variable requirement carries at least one
     * qualified ({@code DATASET.VARIABLE}) entry — i.e. when deciding its scope needs metadata from
     * a dataset other than the one under validation.
     * <p>
     * Callers use this to build a {@link ScopeVariableSource} <b>lazily</b>: resolving foreign
     * datasets costs a resolver round-trip (and, for a split domain, a walk of the whole
     * inventory), and the overwhelming majority of scoped rules address only the primary dataset.
     * </p>
     *
     * @param rule
     *            the rule to inspect
     * @return whether any Include/Exclude entry is qualified
     */
    public static boolean hasQualifiedVariableScope(Rule rule)
    {
        VariableRequirement required = rule.effectiveVariableRequirement();
        if (required == null)
        {
            return false;
        }
        // All THREE facets, or the lazy ScopeVariableSource is not built for a rule that needs it
        // and every qualified entry in the unscanned facet silently answers "satisfied".
        return anyQualified(required.getAll()) || anyQualified(required.getAny())
                || anyQualified(required.getNone());
    }


    /** Whether any non-null entry in the list parses as qualified. */
    private static boolean anyQualified(@Nullable List<String> entries)
    {
        if (entries == null)
        {
            return false;
        }
        for (String entry : entries)
        {
            if (entry != null && ScopeVariableEntry.parse(entry).isQualified())
            {
                return true;
            }
        }
        return false;
    }


    /**
     * Include leg for one entry: {@code null} when the entry is satisfied, otherwise the mismatch
     * description. Splits the qualified case off first; the unqualified path is byte-for-byte the
     * pre-Fix-#124 logic.
     */
    private static @Nullable String describeIncludeEntry(String varName, DataTableMeta meta,
            @Nullable String domainPrefix, @Nullable ScopeVariableSource foreign)
    {
        ScopeVariableEntry entry = ScopeVariableEntry.parse(varName);
        String qualifier = entry.qualifier();
        if (qualifier != null)
        {
            if (foreign == null)
            {
                return null;
            }
            // Name the RESOLVED dataset in every message (SUPP-- -> SUPPAE), so the reader is
            // told which dataset was actually looked for.
            String dataset = foreign.resolvedQualifier(qualifier);
            List<DataTableMeta> metas = foreign.metasOf(qualifier);
            if (metas.isEmpty())
            {
                // Review H2: consult the SUPP pivot BEFORE declaring the dataset unavailable.
                // OperatorRegistry.existsAsDottedDatasetColumn falls through to the SUPP<DOMAIN>
                // scan when resolve() returns null, so a study carrying SUPPAE but no AE answers
                // `exists AE.AETRTEM` true. Returning early here would make the scope gate skip
                // where the Check-side guard runs — breaking exactly the hoist equivalence the
                // migration plan relies on.
                if (scopeEntryPattern(entry.variable()) == null
                        && foreign.existsViaSuppQnam(qualifier, entry.variable()))
                {
                    return null;
                }
                return "Requirements.Variables.All variable " + varName + " not present — dataset "
                        + dataset + " not available";
            }
            Pattern pattern = scopeEntryPattern(entry.variable());
            if (pattern != null)
            {
                if (firstColumnMatching(metas, pattern) == null)
                {
                    return "no variable matching Requirements.Variables.All entry " + varName
                            + " present in dataset " + dataset;
                }
            }
            else if (!anyHasColumn(metas, entry.variable())
                    && !foreign.existsViaSuppQnam(qualifier, entry.variable()))
            {
                return "Requirements.Variables.All variable " + varName + " not present in dataset "
                        + dataset;
            }
            return null;
        }
        String resolved = resolveScopeVariable(varName, domainPrefix);
        Pattern pattern = scopeEntryPattern(resolved);
        if (pattern != null)
        {
            if (firstColumnMatching(meta, pattern) == null)
            {
                // no dataset variable matches the required pattern
                return "no variable matching Requirements.Variables.All entry "
                        + entryLabel(varName, resolved) + " present in dataset";
            }
        }
        else if (meta.getColumnIndex(resolved) < 0)
        {
            // required variable missing
            return "Requirements.Variables.All variable " + entryLabel(varName, resolved)
                    + " not present in dataset";
        }
        return null;
    }


    /**
     * Exclude leg for one entry — the mirror image of {@link #describeIncludeEntry}: the entry
     * rejects the dataset when the named variable <em>is</em> present. A qualified entry whose
     * dataset is unavailable excludes nothing.
     */
    private static @Nullable String describeExcludeEntry(String varName, DataTableMeta meta,
            @Nullable String domainPrefix, @Nullable ScopeVariableSource foreign)
    {
        ScopeVariableEntry entry = ScopeVariableEntry.parse(varName);
        String qualifier = entry.qualifier();
        if (qualifier != null)
        {
            if (foreign == null)
            {
                return null;
            }
            String dataset = foreign.resolvedQualifier(qualifier);
            List<DataTableMeta> metas = foreign.metasOf(qualifier);
            if (metas.isEmpty())
            {
                // Review H2 mirror: the dataset itself is absent, but a SUPP qualifier row can
                // still deliver the variable — and if it does, Exclude must reject.
                if (scopeEntryPattern(entry.variable()) == null
                        && foreign.existsViaSuppQnam(qualifier, entry.variable()))
                {
                    return "Requirements.Variables.None variable " + varName
                            + " present as a supplemental qualifier of " + dataset;
                }
                // dataset absent -> the excluded variable cannot be present
                return null;
            }
            Pattern pattern = scopeEntryPattern(entry.variable());
            if (pattern != null)
            {
                String hit = firstColumnMatching(metas, pattern);
                if (hit != null)
                {
                    return "variable " + hit + " matches Requirements.Variables.None entry "
                            + varName + " in dataset " + dataset;
                }
            }
            else if (anyHasColumn(metas, entry.variable())
                    || foreign.existsViaSuppQnam(qualifier, entry.variable()))
            {
                return "Requirements.Variables.None variable " + varName + " present in dataset "
                        + dataset;
            }
            return null;
        }
        String resolved = resolveScopeVariable(varName, domainPrefix);
        Pattern pattern = scopeEntryPattern(resolved);
        if (pattern != null)
        {
            String hit = firstColumnMatching(meta, pattern);
            if (hit != null)
            {
                // a dataset variable matches the rejecting pattern
                return "variable " + hit + " matches Requirements.Variables.None entry "
                        + entryLabel(varName, resolved);
            }
        }
        else if (meta.getColumnIndex(resolved) >= 0)
        {
            // excluded variable is present
            return "Requirements.Variables.None variable " + entryLabel(varName, resolved)
                    + " present in dataset";
        }
        return null;
    }


    /**
     * Returns {@code true} if the rule applies to a dataset with the given detected ADaM data
     * structure. See {@link #describeDataStructureMismatch(Rule, String)}.
     */
    public static boolean matchesDataStructure(Rule rule, @Nullable String detectedStructure)
    {
        return describeDataStructureMismatch(rule, detectedStructure) == null;
    }


    /**
     * Set-valued variant of {@link #matchesDataStructure(Rule, String)}. See
     * {@link #describeDataStructureMismatch(Rule, List)}.
     */
    public static boolean matchesDataStructure(Rule rule, List<String> detectedStructures)
    {
        return describeDataStructureMismatch(rule, detectedStructures) == null;
    }


    /**
     * Reason-bearing {@code Scope.Data_Structures} matcher. {@code detectedStructure} is the
     * dataset's structure token from
     * {@link net.cumba.cdisc.core.metadata.AdamDataStructureDetector#detect} (total — never
     * {@code null} from that detector; a {@code null} argument is handled defensively as
     * "undetermined" and rejected by an Include list). Semantics mirror the Python engine's
     * {@code rule_applies_to_data_structure} with two documented house deviations:
     * <ul>
     * <li>an <b>Exclude-only</b> scope excludes exactly the listed structures (upstream's missing
     * {@code if included:} guard makes an Exclude-only scope match nothing — an evident defect we
     * do not mirror);</li>
     * <li>{@code ALL} in Include still honours Exclude (upstream returns early), consistent with
     * this class's Domains/Classes matchers.</li>
     * </ul>
     * <b>Both deviations are conditional on {@code Data_Structures.Exclude}, which no shipped rule
     * authors</b>, so neither is corpus-exercised today: the first needs an Exclude-only scope, and
     * the second only diverges from upstream on a rule carrying <em>both</em> {@code ALL} and an
     * Exclude. {@code ALL}-in-Include <em>is</em> authored (the PMDA ADaM rules) but behaves
     * identically to upstream while Exclude is unused. ⚠ The conclusion therefore rests on one
     * remaining zero, not two — the first rule to author an Exclude makes both deviations live, and
     * that is the change to look for here. (The clause this replaced said "no shipped rule authors
     * the field yet"; the field itself has been authored in bulk since — the ADaM migration — while
     * Exclude stayed at zero. Triage finding S3.) Tokens are compared via {@link #normalize}
     * (case/separator-insensitive), consistent with the class matcher.
     *
     * <p>
     * <b>Single-token convenience since Fix #179</b> — equivalent to
     * {@link #describeDataStructureMismatch(Rule, List)} with a one-element set, i.e. it does
     * <em>not</em> apply the structure hierarchy. Use it only where the caller genuinely holds one
     * token and no is-a relation applies (the parity mirror, which runs the column heuristic alone
     * and so can never produce a medical-device specialisation, and unit tests). Production callers
     * pass the set from {@link net.cumba.cdisc.core.metadata.AdamDataStructureDetector#detectAll}.
     * </p>
     *
     * @param rule
     *            the rule to check
     * @param detectedStructure
     *            the dataset's detected structure token, or {@code null} when undetermined
     * @return {@code null} when matching, otherwise the mismatch description
     */
    public static @Nullable String describeDataStructureMismatch(Rule rule,
            @Nullable String detectedStructure)
    {
        return describeDataStructureMismatch(rule,
                detectedStructure == null ? List.of() : List.of(detectedStructure));
    }


    /**
     * <b>Fix #179 — the set-valued {@code Scope.Data_Structures} matcher, and the one production
     * callers use.</b> A dataset carries a <em>set</em> of structures, most-specific first
     * ({@link net.cumba.cdisc.core.metadata.AdamDataStructureDetector#detectAll}): a medical-device
     * BDS dataset is {@code [MEDICAL DEVICE BASIC DATA STRUCTURE, BASIC DATA STRUCTURE]}. Semantics
     * are those of {@link #describeDataStructureMismatch(Rule, String)}, lifted over the set
     * exactly as {@link #describeSubclassMismatch(Rule, List)} lifts the subclass gate:
     * <ul>
     * <li>{@code Include} (without {@code ALL}) is satisfied when <b>any</b> detected token is in
     * the list — so an {@code Include:[BASIC DATA STRUCTURE]} rule covers a device BDS dataset,
     * while an {@code Include:[MEDICAL DEVICE BASIC DATA STRUCTURE]} rule does not cover a plain
     * BDS one;</li>
     * <li>{@code Exclude} rejects when <b>any</b> detected token matches — so
     * {@code Exclude:[BASIC DATA STRUCTURE]} also excludes device BDS datasets. ⚠ This subtype
     * exclusion is <b>deliberate and owner-decided</b> (2026-08-08): {@code Exclude} is symmetric
     * with {@code Include}, and the asymmetric reading ("only the plain ones") is what an author
     * would otherwise assume. It is stated in
     * {@code documentation/CORE-RULES-AUTHORING-GUIDELINES.md} §4.8;</li>
     * <li>an <b>empty</b> {@code detectedStructures} means "undetermined" and is rejected by an
     * Include list, exactly as a {@code null} token was.</li>
     * </ul>
     *
     * <p>
     * ⚑ {@link #firstNormalizedEntry} stays an <b>exact</b> (normalised) token match — the is-a
     * relation is data held by
     * {@link net.cumba.cdisc.core.metadata.AdamDataStructureDetector#structureSet}, never
     * subsumption logic in this matcher. That is what keeps the 78 shipped
     * {@code BASIC DATA STRUCTURE} / {@code OCCURRENCE DATA STRUCTURE} entries covering
     * medical-device datasets <em>by construction</em>.
     * </p>
     *
     * <p>
     * The mismatch message names the <b>most specific</b> detected token — the set's first element,
     * i.e. what the sponsor declared — and, when the set has more than one token, appends the full
     * set so the reader can see why a supertype-scoped rule would have matched. A single-token set
     * renders exactly as before Fix #179, so every existing skip reason is unchanged.
     * </p>
     *
     * @param rule
     *            the rule to check
     * @param detectedStructures
     *            the dataset's detected structure set, most-specific first; empty when undetermined
     * @return {@code null} when matching, otherwise the mismatch description
     */
    public static @Nullable String describeDataStructureMismatch(Rule rule,
            List<String> detectedStructures)
    {
        Scope scope = rule.getScope();
        if (scope == null || scope.getDataStructures() == null)
        {
            return null;
        }
        List<String> include = scope.getDataStructures().getInclude();
        List<String> exclude = scope.getDataStructures().getExclude();
        if (include != null && !include.isEmpty() && !include.contains(ALL))
        {
            if (detectedStructures.isEmpty())
            {
                return "dataset data structure undetermined but rule has a"
                        + " Scope.Data_Structures.Include " + include;
            }
            boolean anyMatch = false;
            for (String detected : detectedStructures)
            {
                if (firstNormalizedEntry(include, detected) != null)
                {
                    anyMatch = true;
                    break;
                }
            }
            if (!anyMatch)
            {
                return "data structure " + describeDetectedStructures(detectedStructures)
                        + " not in Scope.Data_Structures.Include " + include;
            }
        }
        if (exclude != null && !exclude.isEmpty())
        {
            for (String detected : detectedStructures)
            {
                String entry = firstNormalizedEntry(exclude, detected);
                if (entry != null)
                {
                    return "data structure " + describeDetectedStructures(detectedStructures)
                            + " matches Scope.Data_Structures.Exclude entry " + entry;
                }
            }
        }
        return null;
    }


    /**
     * Fix #179: renders a detected structure set for a mismatch message — the most specific token
     * alone when that is all there is, otherwise the most specific token plus the full set, e.g.
     * {@code "MEDICAL DEVICE BASIC DATA STRUCTURE (also BASIC DATA STRUCTURE)"}. Keeping the
     * single-token rendering byte-identical to the pre-Fix-#175 message is deliberate: these
     * strings are the user-visible {@code SKIPPED} reasons, and every rule authored against the
     * four original tokens must keep reporting exactly what it reported before.
     */
    private static String describeDetectedStructures(List<String> detectedStructures)
    {
        String mostSpecific = detectedStructures.getFirst();
        if (detectedStructures.size() == 1)
        {
            return mostSpecific;
        }
        return mostSpecific + " (also "
                + String.join(", ", detectedStructures.subList(1, detectedStructures.size())) + ")";
    }


    /**
     * Returns {@code true} if the rule applies to a dataset with the given detected ADaM subclass.
     * See {@link #describeSubclassMismatch}.
     */
    public static boolean matchesSubclass(Rule rule, @Nullable String detectedSubclass)
    {
        return describeSubclassMismatch(rule, detectedSubclass) == null;
    }


    /**
     * Reason-bearing {@code Scope.Subclasses} matcher. {@code detectedSubclass} is the dataset's
     * subclass token from {@link net.cumba.cdisc.core.metadata.AdamSubclassDetector#detect}, or
     * {@code null} when the dataset has no detectable subclass — the normal case for a plain
     * BDS/OCCDS/ADSL dataset. Null-detection semantics (decided 2026-07-26):
     * <ul>
     * <li>{@code Include} (without {@code ALL}) requires a positively detected subclass in the list
     * — a null-detected dataset is skipped with a reason naming the Include list;</li>
     * <li>{@code Exclude} rejects only on a positive match — a null-detected dataset passes an
     * Exclude-only scope.</li>
     * </ul>
     * No engine-side Python counterpart exists upstream (schema-only field); the house parity fork
     * carries a twin gate with identical semantics. Tokens compare via {@link #normalize}.
     *
     * @param rule
     *            the rule to check
     * @param detectedSubclass
     *            the dataset's detected subclass token, or {@code null} when none
     * @return {@code null} when matching, otherwise the mismatch description
     */
    public static @Nullable String describeSubclassMismatch(Rule rule,
            @Nullable String detectedSubclass)
    {
        return describeSubclassMismatch(rule,
                detectedSubclass == null ? List.of() : List.of(detectedSubclass));
    }


    /**
     * Multi-token variant of {@link #describeSubclassMismatch(Rule, String)}: a dataset may carry
     * several subclasses (Define-XML allows multiple {@code <def:SubClass>} declarations —
     * {@link net.cumba.cdisc.core.metadata.AdamSubclassDetector#resolve}). {@code Include} (without
     * {@code ALL}) is satisfied when <b>any</b> detected token is in the list; {@code Exclude}
     * rejects when any detected token matches; an empty {@code detectedSubclasses} means "no
     * subclass" with the semantics of the single-token variant.
     *
     * @param rule
     *            the rule to check
     * @param detectedSubclasses
     *            the dataset's detected/declared subclass tokens, empty when none
     * @return {@code null} when matching, otherwise the mismatch description
     */
    public static @Nullable String describeSubclassMismatch(Rule rule,
            List<String> detectedSubclasses)
    {
        Scope scope = rule.getScope();
        if (scope == null || scope.getSubclasses() == null)
        {
            return null;
        }
        List<String> include = scope.getSubclasses().getInclude();
        List<String> exclude = scope.getSubclasses().getExclude();
        if (include != null && !include.isEmpty() && !include.contains(ALL))
        {
            if (detectedSubclasses.isEmpty())
            {
                return "no subclass detected but rule has Scope.Subclasses.Include " + include;
            }
            boolean anyMatch = false;
            for (String detected : detectedSubclasses)
            {
                if (firstNormalizedEntry(include, detected) != null)
                {
                    anyMatch = true;
                    break;
                }
            }
            if (!anyMatch)
            {
                return "subclass " + String.join(", ", detectedSubclasses)
                        + " not in Scope.Subclasses.Include " + include;
            }
        }
        if (exclude != null && !exclude.isEmpty())
        {
            for (String detected : detectedSubclasses)
            {
                String entry = firstNormalizedEntry(exclude, detected);
                if (entry != null)
                {
                    return "subclass " + detected + " matches Scope.Subclasses.Exclude entry "
                            + entry;
                }
            }
        }
        return null;
    }


    /**
     * Returns the first entry that {@link #normalize normalises} equal to {@code name}, or
     * {@code null} when none does. Shared by the Data_Structures / Subclasses matchers ({@code ALL}
     * is handled by the callers; {@code NONE} is not part of these vocabularies).
     */
    private static @Nullable String firstNormalizedEntry(List<String> entries, String name)
    {
        String normalized = normalize(name);
        for (String entry : entries)
        {
            if (normalize(entry).equals(normalized))
            {
                return entry;
            }
        }
        return null;
    }


    /**
     * Pattern for a variable-requirement entry, or {@code null} for a literal. A glob / regex entry
     * ({@link #scopePattern}) takes precedence; otherwise an entry carrying the wildcard markers
     * ({@code xx}, {@code zz}, {@code y}, {@code w} — e.g. {@code TRTxxP}) compiles via
     * {@link WildcardExpander#scopeVariableWildcardPattern} so it matches any concrete column
     * (at-least-one semantics, mirroring the Check-side wildcard expansion). Without the marker
     * branch, a template's variable scope would be tested literally and the rule skipped even when
     * a matching concrete column (e.g. {@code TRT01P} for {@code TRTxxP}) exists.
     */
    private static @Nullable Pattern scopeEntryPattern(String resolved)
    {
        Pattern pattern = scopePattern(resolved);
        return pattern != null ? pattern : WildcardExpander.scopeVariableWildcardPattern(resolved);
    }


    /**
     * Renders a variable-requirement entry for a mismatch message: the raw entry, plus the
     * {@code --}-resolved form when resolution changed it (e.g. {@code "--SEQ (resolved AESEQ)"}).
     */
    private static String entryLabel(String rawEntry, String resolved)
    {
        return resolved.equals(rawEntry) ? rawEntry : rawEntry + " (resolved " + resolved + ")";
    }


    /**
     * Resolves a leading {@code --} domain placeholder in a variable-requirement entry against the
     * <em>variable</em> wildcard prefix, mirroring the expression language's resolution
     * ({@code ExprCompiler.resolveDomainPrefix}). EC-36: substitution is unconditional once a
     * prefix exists — the guard and the Check MUST resolve identically, or a rule passes its guard
     * and then evaluates a different column. Only a {@code null} prefix returns the raw entry.
     */
    private static String resolveScopeVariable(String entry, @Nullable String domainPrefix)
    {
        if (!entry.startsWith(WILDCARD))
        {
            return entry;
        }
        // EC-36: the caller now passes the VARIABLE wildcard prefix (Python's
        // wildcard_replacement), so an EMPTY prefix is legitimate — a SUPP/SQ dataset resolves
        // --QNAM to QNAM. The old `length() == 2` gate treated "" as "no prefix" and left the
        // entry as the literal "--QNAM", which no dataset carries, so every such rule was skipped.
        // A 2-character AP suffix (APMH -> MH) also has to pass, and does.
        return domainPrefix != null ? domainPrefix + entry.substring(WILDCARD.length()) : entry;
    }


    /**
     * Returns the first column name in {@code meta} that fully matches the pattern, or {@code null}
     * when none does.
     */
    private static @Nullable String firstColumnMatching(DataTableMeta meta, Pattern pattern)
    {
        for (int i = 0; i < meta.getColumnCount(); i++)
        {
            String column = meta.getColumn(i).getName();
            if (pattern.matcher(column).matches())
            {
                return column;
            }
        }
        return null;
    }


    /**
     * Fix #124: multi-table variant of {@link #firstColumnMatching(DataTableMeta, Pattern)} — a
     * qualified entry may resolve to several tables when its qualifier is an SDTM domain split
     * across members ({@code LB} → {@code lbch}/{@code lbhe}/{@code lbur}). Returns the first
     * matching column in table order, or {@code null} when none matches.
     */
    private static @Nullable String firstColumnMatching(List<DataTableMeta> metas, Pattern pattern)
    {
        for (DataTableMeta meta : metas)
        {
            String hit = firstColumnMatching(meta, pattern);
            if (hit != null)
            {
                return hit;
            }
        }
        return null;
    }


    /**
     * Fix #124: whether any of the tables backing a qualified entry carries {@code column}. Routed
     * through each table's own {@link DataTableMeta#getColumnIndex}, so the per-table
     * case-sensitivity policy is honoured exactly as it is for the primary dataset — a flattened
     * name set would silently impose one policy on all of them.
     */
    private static boolean anyHasColumn(List<DataTableMeta> metas, String column)
    {
        for (DataTableMeta meta : metas)
        {
            if (meta.getColumnIndex(column) >= 0)
            {
                return true;
            }
        }
        return false;
    }


    /**
     * Returns the first pattern in the list that matches the given name, or {@code null} when none
     * does. Handles:
     * <ul>
     * <li>{@code ALL} — matches everything</li>
     * <li>{@code NONE} — matches nothing (no-op placeholder)</li>
     * <li>{@code SUPP--}, {@code AP--} — wildcard patterns where {@code --} represents exactly 2
     * characters</li>
     * <li>Exact match — literal domain/class name, compared via {@link #normalize} so that casing
     * differences (e.g. CDISC Library {@code "Events"} vs rule {@code "EVENTS"}) and separator
     * differences (e.g. {@code "Special-Purpose"} vs {@code "SPECIAL PURPOSE"}) both resolve to a
     * match.</li>
     * </ul>
     */
    private static @Nullable String firstMatchingClassEntry(List<String> patterns, String name)
    {
        String normalizedName = normalize(name);
        for (String pattern : patterns)
        {
            if (ALL.equals(pattern))
            {
                return pattern;
            }
            if (NONE.equals(pattern))
            {
                continue;
            }
            if (pattern.contains(WILDCARD))
            {
                String prefix = pattern.replace(WILDCARD, "");
                int expectedLength = prefix.length() + 2;
                if (name != null && name.length() == expectedLength
                        && normalize(name.substring(0, prefix.length())).equals(normalize(prefix)))
                {
                    return pattern;
                }
            }
            else if (normalize(pattern).equals(normalizedName))
            {
                return pattern;
            }
        }
        return null;
    }


    /**
     * Domain-pattern matcher used by {@link #matchesDomain} / {@link #describeDomainMismatch}.
     * Literal entries match the dataset name by <em>exact</em> equality after {@link #normalize
     * normalisation} ({@link #matchesDomainLiteral}), mirroring the reference Python engine's
     * {@code rule_processor._is_domain_name_included} / {@code _is_domain_name_excluded}, which are
     * plain list-membership tests
     * ({@code dataset_metadata.domain in included_domains or dataset_metadata.name in
     * included_domains}) with no prefix logic. Class-level matching ({@link #matchesClass})
     * continues to use {@link #firstMatchingClassEntry}; the two now differ only in this method's
     * glob / regex support. Returns the matching entry (so mismatch describers can name it), or
     * {@code null} when no entry matches.
     * <p>
     * Extended-name and split-form datasets are reached through the <em>callers'</em> split-base
     * re-test, not through this method: {@link #describeDomainMismatch(Rule, String, String)}
     * re-tests the dataset's canonical unsplit name — read from the {@code DOMAIN} /
     * {@code RDOMAIN} columns by {@link OperationExecutor#unsplitNameFromData} — so
     * {@code Domains.Include = ["LB"]} still covers {@code LB1} and {@code LBCHEM} when they carry
     * {@code DOMAIN=LB}, exactly as Python's {@code SDTMDatasetMetadata.unsplit_name} does. A rule
     * that genuinely wants family-prefix breadth (e.g. every {@code ADLB*} dataset) declares it
     * explicitly with a glob or {@code /…/} regex entry — see {@link #scopePattern}.
     * </p>
     * <p>
     * Additionally supports glob ({@code *} / {@code ?}) and {@code /…/} regex entries (see
     * {@link #scopePattern}) which match the <em>raw</em> dataset name as an anchored,
     * case-insensitive full match — tried before the {@code --} wildcard branch (review F6: an
     * entry mixing {@code --} with pattern metacharacters is loader-validated as a pattern and must
     * match as one) and before the literal-equality fallback. The callers' split-base re-test
     * applies to pattern entries exactly as to literals, so a pattern matching {@code LB} also
     * covers {@code LB1}.
     * </p>
     * <p>
     * The {@code ALL}, {@code NONE}, and {@code --} wildcard sentinels keep their existing meaning.
     * Empty/null entries are not expected at runtime —
     * {@link net.cumba.cdisc.core.RulePackageLoader} rejects them at load time, since a zero-length
     * entry is not a meaningful dataset name. Pattern and dataset name are compared via
     * {@link #normalize} so that lowercase filename-derived dataset names (e.g. {@code "ae"}) match
     * upper-cased rule scopes.
     * </p>
     */
    private static @Nullable String firstMatchingDomainEntry(List<String> patterns, String name)
    {
        for (String pattern : patterns)
        {
            if (ALL.equals(pattern))
            {
                return pattern;
            }
            if (NONE.equals(pattern))
            {
                continue;
            }
            // Review F6: pattern detection takes precedence over the `--` wildcard branch. An
            // entry mixing `--` with glob/regex metacharacters (e.g. "SUPP--*" or "/^SUPP--$/")
            // is validated as a PATTERN at load time (RulePackageLoader → scopePattern); consuming
            // it as a `--` two-char wildcard here would silently split the loader and matcher
            // semantics. Only a literal entry (scopePattern == null) may take the `--` branch.
            Pattern compiled = scopePattern(pattern);
            if (compiled != null)
            {
                // Glob / regex entry: anchored full match against the raw dataset name
                // (normalize would corrupt the pattern's metacharacters). The split-base
                // re-test in the callers covers split datasets, exactly as for literals.
                if (name != null && compiled.matcher(name).matches())
                {
                    return pattern;
                }
                continue;
            }
            if (pattern.contains(WILDCARD))
            {
                String prefix = pattern.replace(WILDCARD, "");
                int expectedLength = prefix.length() + 2;
                if (name != null && name.length() == expectedLength
                        && normalize(name.substring(0, prefix.length())).equals(normalize(prefix)))
                {
                    return pattern;
                }
                continue;
            }
            if (matchesDomainLiteral(pattern, name))
            {
                return pattern;
            }
        }
        return null;
    }


    /**
     * Compiled regex for a glob / regex scope entry, or {@code null} for a literal entry. Two
     * pattern forms are recognised:
     * <ul>
     * <li>{@code /…/} — the text between the slashes is compiled as a regular expression (entry
     * length must exceed 2, so a literal {@code "/"} or {@code "//"} stays literal);</li>
     * <li>glob — an entry containing {@code *} (any run of characters, including empty) or
     * {@code ?} (exactly one character); literal runs are regex-quoted.</li>
     * </ul>
     * Both compile {@link Pattern#CASE_INSENSITIVE} and are matched as <b>anchored full matches</b>
     * ({@code matcher().matches()}) against the raw, un-normalized name. Shared with
     * {@link net.cumba.cdisc.core.RulePackageLoader}, which pre-compiles every
     * {@code Scope.Domains} / variable-requirement entry at load time and turns a
     * {@link java.util.regex.PatternSyntaxException} into a rule load error — so the exception this
     * method may throw for an invalid {@code /…/} entry never reaches the matchers at runtime.
     *
     * @param entry
     *            the scope entry to inspect
     * @return the compiled pattern, or {@code null} when the entry is a literal
     * @throws java.util.regex.PatternSyntaxException
     *             when a {@code /…/} entry encloses an invalid regular expression
     */
    public static @Nullable Pattern scopePattern(String entry)
    {
        if (entry.length() > 2 && entry.startsWith("/") && entry.endsWith("/"))
        {
            return Pattern.compile(entry.substring(1, entry.length() - 1),
                    Pattern.CASE_INSENSITIVE);
        }
        if (entry.indexOf('*') >= 0 || entry.indexOf('?') >= 0)
        {
            return Pattern.compile(globToRegex(entry), Pattern.CASE_INSENSITIVE);
        }
        return null;
    }


    /**
     * Translates a glob entry into a regex: {@code *} → {@code .*}, {@code ?} → {@code .}, every
     * literal run {@link Pattern#quote quoted}. Cannot produce an invalid regex.
     */
    private static String globToRegex(String glob)
    {
        StringBuilder sb = new StringBuilder(glob.length() + 8);
        int literalStart = 0;
        for (int i = 0; i < glob.length(); i++)
        {
            char c = glob.charAt(i);
            if (c == '*' || c == '?')
            {
                if (literalStart < i)
                {
                    sb.append(Pattern.quote(glob.substring(literalStart, i)));
                }
                sb.append(c == '*' ? ".*" : ".");
                literalStart = i + 1;
            }
        }
        if (literalStart < glob.length())
        {
            sb.append(Pattern.quote(glob.substring(literalStart)));
        }
        return sb.toString();
    }


    /**
     * Exact match between a literal {@code Scope.Domains.Include} / {@code Exclude} entry and a
     * candidate dataset name: {@code true} when the two are equal after {@link #normalize
     * normalisation} of both sides, so {@code "AE"} matches {@code "ae"} and {@code "A-E"} but
     * <em>not</em> {@code "AESI"}. This is the reference Python engine's plain membership test.
     * <p>
     * The prefix relaxation this method used to implement (Fix #38, {@code startsWith}) selected
     * {@code RELREC} / {@code RELSUB} / {@code RELSPEC} / {@code RELREF} for
     * {@code Include = ["RE"]} and every {@code SUPPxx} dataset for {@code Include = ["SU"]},
     * producing false findings from CDISC-SEND-0289(-1), CDISC-SEND-0338 and FDA-SE2306. Split and
     * extended forms are covered by the callers' unsplit-name re-test instead (see
     * {@link #firstMatchingDomainEntry}); breadth that the unsplit name cannot express is declared
     * explicitly with a glob / regex entry.
     * </p>
     * <p>
     * An entry that normalises to the empty string never matches — a zero-length entry is not a
     * dataset name, and {@link net.cumba.cdisc.core.RulePackageLoader} already rejects a literal
     * {@code ""} at load time.
     * </p>
     */
    private static boolean matchesDomainLiteral(String entry, String datasetName)
    {
        String normEntry = normalize(entry);
        return !normEntry.isEmpty() && normEntry.equals(normalize(datasetName));
    }


    /**
     * Returns {@code name} uppercased and stripped of every character that isn't an ASCII letter or
     * digit. Mirrors Python's normalisation strategy at the data-service boundary (see
     * {@code convert_library_class_name_to_ct_class}) and additionally bridges the separator drift
     * we hit at the matcher boundary: rule scopes use {@code "SPECIAL
     * PURPOSE"} (space) while the CDISC Library returns {@code "Special-Purpose"} (hyphen); both
     * collapse to {@code "SPECIALPURPOSE"} here. Domain casing is handled by the same pass so
     * lower-cased filename-derived dataset names match upper-cased rule scopes.
     */
    private static String normalize(String name)
    {
        if (name == null)
        {
            return "";
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++)
        {
            char c = name.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))
            {
                sb.append(c);
            }
            else if (c >= 'a' && c <= 'z')
            {
                sb.append((char) (c - ('a' - 'A')));
            }
        }
        return sb.toString();
    }

}
