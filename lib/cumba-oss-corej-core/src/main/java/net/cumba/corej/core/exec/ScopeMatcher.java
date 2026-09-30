package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;

import net.cumba.corej.core.expr.eval.ColumnTypeGate;
import net.cumba.corej.core.gen.WildcardExpander;
import net.cumba.corej.core.model.ClassScope;
import net.cumba.corej.core.model.DatasetScope;
import net.cumba.corej.core.model.DomainScope;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Scope;
import net.cumba.corej.core.model.VariableRequirement;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.values.DataValueType;
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
     * The {@code Scope.Domains} matcher: returns {@code null} when the rule's domain scope matches
     * the dataset, or a short human-readable message naming the failing criterion and the
     * responsible scope entry (e.g. {@code "domain EX not in Scope.Domains.Include [AE, CM]"} or
     * {@code "domain SUPPAE matches Scope.Domains.Exclude entry SUPP--"}). Supports the {@code ALL}
     * wildcard in Include lists, and the {@code --} wildcard pattern (e.g. {@code SUPP--} matches
     * any 6-character domain starting with {@code SUPP}; {@code AP--} any 4-character domain
     * starting with {@code AP}). The {@code --} contract is <b>strict</b>: exactly two characters,
     * never "any suffix" — longer split forms are reached through the data-derived split-base
     * re-test below, not by relaxing the token.
     *
     * <p>
     * It is <b>data-driven</b>: it takes the dataset's canonical unsplit (base) name — computed
     * from the {@code DOMAIN}/{@code RDOMAIN} columns via
     * {@link DatasetIdentity#unsplitNameFromData} — rather than guessing it from the name (a
     * table-less overload that derived the base through {@link SplitDatasetUtil#unsplitName} was
     * retired 2026-09-25, U1 / A5: the name heuristic strips a single trailing letter, so
     * {@code SUPPLBHM} became {@code SUPPLBH} there and {@code SUPPLB} here, and a strict
     * {@code SUPP--} scope silently missed the dataset on the name path). This is what mirrors
     * Python's {@code SDTMDatasetMetadata.is_split}/{@code unsplit_name}: a dataset named
     * {@code FAAE} carrying {@code DOMAIN=FA} has {@code unsplitName="FA"} and is therefore a split
     * of FA, which a name-only heuristic misses. The dataset is a split iff {@code domainName}
     * differs from {@code unsplitName}, and the base used for Include/Exclude split re-tests is
     * {@code unsplitName}.
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
     * the study, so {@code Include: ["AP--"] + include_split_datasets: true} (CDISC-CG0650) makes
     * the {@code AP--} token inert and runs the rule study-wide. Python's <em>no-Include</em>
     * branch is already the conjunctive gate; the divergence is only that Java now applies the same
     * gate when an Include list is present.
     * </p>
     *
     * <p>
     * ⛔⛔ <b>The two arguments must come from two DIFFERENT derivations, or this method is
     * inert.</b> {@code isSplit} is {@code !domainName.equals(unsplitName)} and nothing else, so a
     * caller that resolves one name and passes it twice gets {@code isSplit == false} for every
     * dataset — silently, with every test still green, because the split legs simply never run.
     * {@code DatasetRuleResolver} did exactly that until 2026-09-17: it passed
     * {@code LibraryValidator}'s {@code CdiscDomainResolver.cdiscDomainOf(table)}, and that
     * resolver and {@code DatasetIdentity.unsplitNameFromData} <b>both read the row-0
     * {@code DOMAIN} cell first</b>. ⇒ {@code domainName} is the <b>member</b> name
     * ({@code meta.getName()}), {@code unsplitName} the data-derived base. See D125 in
     * {@code plans/PLAN-typed-expression-engine.md} and the guard
     * {@code net.cumba.corej.core.report.SplitScopeProductionPathTest}, which exercises this method
     * only through its production caller.
     * </p>
     *
     * @param rule
     *            the rule to check
     * @param domainName
     *            the dataset's MEMBER name (e.g. "DM", "FAAE") — never the CDISC domain code when a
     *            member name is available; see the warning above
     * @param unsplitName
     *            the dataset's canonical base name (e.g. "FA" for "FAAE"), derived from the data by
     *            {@link DatasetIdentity#unsplitNameFromData}; when {@code null} the dataset is
     *            treated as not a split
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
        // `Include: [AP--] + include_split_datasets: true` (CDISC-CG0650) runs
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
     * Reason-bearing {@code Scope.Datasets} matcher (owner requirement #5,
     * {@code plans/done/PLAN-scope-requirements-split.md} &#167;4.6) — <b>{@code Scope.Domains}
     * minus the split-base re-test</b>.
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
     * The {@code Scope.Classes} matcher: returns {@code null} when the rule's class scope matches,
     * or a short human-readable message naming the failing criterion and the responsible scope
     * entry (e.g. {@code "class EVENTS not in Scope.Classes.Include [FINDINGS]"} or
     * {@code "dataset class undetermined but rule has a Classes scope"}). Supports the {@code ALL}
     * wildcard in Include lists.
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
            // class-scoped at all). DatasetRuleResolver emits a one-time WARN per dataset listing
            // how
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
     * The {@code Scope.Use_Case} matcher (owner ruling X1, {@code PLAN-use-case-scope-filter}):
     * returns {@code null} when the rule applies to the run's use case, or the reason it does not
     * (e.g. {@code "use case NONCLIN not in Scope.Use_Case [INDH]"}).
     * <ul>
     * <li>no use case given ({@code null} or blank) — every rule applies;</li>
     * <li>a rule that declares no use case (no {@code Use_Case}, or one holding no code) applies to
     * every use case;</li>
     * <li>a rule that declares use cases applies when <b>any</b> of its codes equals the given one,
     * case-insensitively (ruling T1-1: {@code "INDH, PROD"} runs under {@code PROD}), and is
     * excluded only when none does.</li>
     * </ul>
     * <p>
     * Unlike the dataset axes this one is a property of the <em>run</em>, the same for every
     * dataset. It has two callers, and both are needed:
     * {@code DatasetRuleResolver.describeScopeSkip} (per dataset, where the reason becomes a
     * {@code SKIPPED} row) and {@code LibraryValidator.anchorEligibleRules} (the study-anchor pass,
     * which never reaches the per-dataset gate). A rule's malformed {@code Use_Case} never reaches
     * here on a loaded rule: the loader turns it into a load error (ruling T1-4).
     * </p>
     *
     * @param rule
     *            the rule to check
     * @param useCase
     *            the run's use case (e.g. {@code "INDH"}, {@code "PROD"}, {@code "NONCLIN"}), or
     *            {@code null} when none was given
     * @return {@code null} when the rule applies, otherwise the mismatch description
     */
    public static @Nullable String describeUseCaseMismatch(Rule rule, @Nullable String useCase)
    {
        if (useCase == null || useCase.isBlank())
        {
            return null;
        }
        Scope scope = rule.getScope();
        List<String> codes = scope == null ? List.of() : useCaseCodes(scope.getUseCase());
        if (codes.isEmpty())
        {
            return null;
        }
        String given = useCase.strip();
        for (String code : codes)
        {
            if (code.equalsIgnoreCase(given))
            {
                return null;
            }
        }
        return "use case " + given + " not in Scope.Use_Case " + codes;
    }


    /**
     * The codes of a {@code Scope.Use_Case} value: split on {@code ,}, each code stripped, empty
     * codes dropped. Empty for {@code null} or a value holding no code ({@code ""}, {@code " "},
     * {@code ","}), which is why such a rule reads as declaring no use case. The loader's R-4.10
     * gate reads the value through this same method, so gate and matcher agree on what a code is.
     *
     * @param raw
     *            the rule's {@code Use_Case} string, or {@code null}
     * @return the codes in authored order, never {@code null}
     */
    public static List<String> useCaseCodes(@Nullable String raw)
    {
        if (raw == null)
        {
            return List.of();
        }
        List<String> codes = new ArrayList<>();
        for (String part : raw.split(",", -1))
        {
            String code = part.strip();
            if (!code.isEmpty())
            {
                codes.add(code);
            }
        }
        return List.copyOf(codes);
    }

    /**
     * The authored shape of a rule's {@code Scope.Use_Case} (R-4.10): one or more upper-case codes
     * separated by commas, with optional spaces around each comma.
     */
    private static final Pattern USE_CASE_VALUE_SHAPE = Pattern
            .compile("[A-Z]+(?:\\s*,\\s*[A-Z]+)*");

    /**
     * Whether {@code raw} is a well-formed rule {@code Scope.Use_Case} value: <b>R-4.10</b>
     * (upper-case codes separated by commas, optional spaces around each comma, no empty code, no
     * surrounding blanks) and <b>R-4.10a</b> (no code twice). The ONE definition:
     * {@code RulePackageLoader}'s load gate rejects exactly the values this rejects, and the REST
     * picker offers codes only from values this accepts, so a code is never offered from a rule
     * that cannot load.
     *
     * @param raw
     *            the rule's {@code Use_Case} string, or {@code null}
     * @return {@code true} when well-formed; {@code false} for {@code null}
     */
    public static boolean isWellFormedUseCaseValue(@Nullable String raw)
    {
        if (raw == null || !USE_CASE_VALUE_SHAPE.matcher(raw).matches())
        {
            return false;
        }
        List<String> codes = useCaseCodes(raw);
        return codes.stream().distinct().count() == codes.size();
    }


    /**
     * The rules the run's use case reaches (owner ruling X1): every rule when no use case was given
     * ({@code null} or blank), otherwise those {@link #matchesUseCase} admits plus every load-error
     * rule, which reports its ERROR regardless of scope (Review F4). The ONE definition of "the
     * effective rule list under a use case", shared by {@code StudyValidationService} (the pre-run
     * forecasts) and {@code LibraryValidator} (Fix #222's presence coverage).
     *
     * @param rules
     *            the selected rules
     * @param useCase
     *            the run's use case, or {@code null}
     * @return the reachable rules; {@code rules} itself when nothing is excluded
     */
    public static List<Rule> rulesInUseCase(List<Rule> rules, @Nullable String useCase)
    {
        if (useCase == null || useCase.isBlank())
        {
            return rules;
        }
        List<Rule> in = new ArrayList<>(rules.size());
        for (Rule r : rules)
        {
            if (r.getLoadError() != null || matchesUseCase(r, useCase))
            {
                in.add(r);
            }
        }
        return in.size() == rules.size() ? rules : in;
    }


    /**
     * Returns {@code true} if the rule applies to the given use case —
     * {@link #describeUseCaseMismatch} answering {@code null}.
     *
     * @param rule
     *            the rule to check
     * @param useCase
     *            the run's use case, or {@code null} when none was given
     * @return true if the rule applies to the given use case
     */
    public static boolean matchesUseCase(Rule rule, @Nullable String useCase)
    {
        return describeUseCaseMismatch(rule, useCase) == null;
    }


    /**
     * The {@code Requirements.Variables} matcher: returns {@code null} when the rule's variable
     * scope matches the dataset, or a short human-readable message naming the failing criterion and
     * the responsible variable (e.g.
     * {@code "Requirements.Variables.All variable AESTDTC not present
     * in dataset"}). When the rule declares a {@link VariableRequirement}, the dataset must contain
     * <b>all</b> variables listed in {@code All}, at least one of {@code Any}, and <b>none</b> of
     * the variables listed in {@code None}; rules without a variable requirement match all
     * datasets. Per entry:
     * <ul>
     * <li>a leading {@code --} is first replaced by {@code domainPrefix} whenever that is non-null
     * ({@link #resolveScopeVariable}, mirroring the expression language's {@code --} resolution):
     * the domain code ({@code --SEQ} → {@code AESEQ}), {@code ""} for a SUPP / SQ dataset
     * ({@code --QNAM} → {@code QNAM}) or an AP dataset's suffix ({@code APMH} → {@code MHSEQ}), of
     * whatever length. Only a {@code null} prefix leaves the entry raw, and the lookup of the raw
     * {@code --} name then misses (in {@code All_Or_None} such an entry is undecidable instead,
     * {@link #resolveEntryNames});</li>
     * <li>a pattern entry ({@code *}/{@code ?} glob or {@code /…/} regex, {@link #scopePattern}) is
     * satisfied when <b>at least one</b> column name matches (anchored full match,
     * case-insensitive) — so an {@code Exclude} pattern rejects the dataset when <em>any</em>
     * column matches;</li>
     * <li>an entry carrying the wildcard markers ({@code xx}, {@code zz}, {@code y}, {@code w} —
     * e.g. {@code TRTxxP}, see
     * {@link net.cumba.corej.core.gen.WildcardExpander#scopeVariableWildcardPattern}) is likewise
     * satisfied when at least one column matches the marker pattern (anchored, case-insensitive
     * since the 2026-09-28 ruling — the same regex the wildcard expansion matches against the
     * Check), so a template scoped to {@code TRTxxP} applies when {@code TRT01P} exists and is
     * skipped — naming the entry — when no concrete column matches;</li>
     * <li>a literal entry is a name lookup ({@link DataTableMeta#getColumnIndex(String)}, which
     * ignores letter case) and, for an untyped entry, the SUPP-QNAM pivot of the primary
     * ({@link ScopeVariableSource#localQualifier}, W2a ruling C1 (a)) — uniformly in {@code All},
     * {@code Any}, {@code All_Or_None} and {@code None}, so every facet agrees with
     * {@code var_exists} about what "present" means;</li>
     * <li>a <b>qualified</b> entry — {@code DATASET.VARIABLE}, naming a variable in another dataset
     * ({@code DM.ARM}, {@code ADSL.TRTxxPN}, {@code SUPP--.QVAL}; Fix #124, parsed per
     * {@link ScopeVariableEntry#parse}) — keeps every semantic above on its variable half while the
     * qualifier is resolved through {@code foreign}. Include requires the foreign dataset to exist
     * <em>and</em> to carry the variable; Exclude rejects only when both hold — so a rule guarded
     * by {@code Include: [DM.ARM]} is skipped (with a reason naming the dataset) when DM is absent,
     * instead of silently evaluating against an unresolved join. When {@code foreign} is
     * {@code null} the resolver in effect cannot enumerate datasets (see
     * {@link ScopeVariableSource#of}), so a qualified entry cannot be <em>decided</em> and is a
     * mismatch (the paragraph below).</li>
     * </ul>
     * {@code --} resolution happens before pattern detection, so {@code --*DT} (prefix + glob)
     * composes naturally.
     *
     * <p>
     * ⚠⚠ An undecidable qualified entry is a <b>mismatch</b>, with a reason naming the resolver
     * rather than the dataset, so the report cannot read it as "the column was absent" (owner
     * ruling 2026-09-10, {@code plans/PLAN-qualified-requirements-cross-standard.md} §8.4
     * disposition (b)). The alternative — treating it as satisfied — let a rule whose
     * {@code var_exists(DM.ARM)} guard was hoisted into {@code Requirements} run with nothing in
     * the guard's place, which is the flood the hoist was supposed to make auditable. ⚑ That
     * alternative existed as a {@code QualifiedEntryPolicy.IGNORE} option for the qualified-blind
     * conveniences until 2026-09-25; both were retired (U1 / A7, K6) and there is one policy now.
     * </p>
     *
     * @param rule
     *            the rule to check
     * @param meta
     *            the primary dataset's metadata
     * @param domainPrefix
     *            the variable wildcard prefix resolving a leading {@code --} in an unqualified
     *            entry, or {@code null}
     * @param foreign
     *            the foreign-metadata source, or {@code null} when qualified entries cannot be
     *            evaluated (every qualified entry is then undecidable)
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
                EntryMismatch mismatch = describeIncludeEntry(varName, meta, domainPrefix, foreign,
                        "All");
                if (mismatch != null)
                {
                    return mismatch.reason();
                }
            }
        }
        List<List<String>> anyGroups = required.getAnyGroups();
        if (anyGroups != null && !anyGroups.isEmpty())
        {
            // The groups are ANDed; each group is its own disjunction. D5: the FIRST unmet
            // group's reason is returned — later groups are not even inspected, exactly as the
            // first unmet All entry wins above.
            for (int g = 0; g < anyGroups.size(); g++)
            {
                String reason = describeAnyLeg(anyGroups.get(g), g + 1, meta, domainPrefix,
                        foreign);
                if (reason != null)
                {
                    return reason;
                }
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
        List<List<String>> allOrNoneGroups = required.getAllOrNoneGroups();
        if (allOrNoneGroups != null && !allOrNoneGroups.isEmpty())
        {
            // ANDed like the Any groups, first unmet group wins; evaluated LAST so that a rule
            // whose All/Any/None is unmet keeps reporting exactly the reason it reported before
            // the facet existed.
            for (int g = 0; g < allOrNoneGroups.size(); g++)
            {
                String reason = describeAllOrNoneGroup(allOrNoneGroups.get(g), g + 1, meta,
                        domainPrefix, foreign);
                if (reason != null)
                {
                    return reason;
                }
            }
        }
        return null;
    }


    /**
     * ONE {@code All_Or_None} group ({@code PLAN-join-key-pairing}, ruling D1 (A)): satisfied when
     * <b>every</b> entry is present or <b>none</b> is; a mixed group is the mismatch, and its
     * reason names both halves so the reader sees which side of a join lacks the column.
     *
     * <p>
     * Presence is decided per entry by {@link #resolveEntryNames} — the same vocabulary and the
     * same resolution order as {@link #describeIncludeEntry} (qualifier → split-domain members →
     * SUPP-QNAM pivot; {@code --} resolved against the primary; glob / regex / marker template
     * matched against the column inventory), minus the type arm, which loader gate R9 keeps out of
     * this facet. ⭐ <b>Wildcard entries are compared RESOLVED</b> (owner, 2026-09-25): an
     * all-present group whose pattern entries match <em>different</em> concrete column sets — the
     * primary carries {@code TRT01P} and {@code TRT02P}, ADSL only {@code TRT01P} — is a mismatch
     * too, because "all exist in both" is the claim the facet makes. Literal entries take part by
     * presence only, so {@code [["VISITDY", "TV.VISITDY"]]} needs no set comparison and a group
     * pairing two differently named literals is legal.
     * </p>
     *
     * <p>
     * ⚠ An undecidable entry — a qualified one with no foreign source, or a {@code --} entry with
     * no domain prefix to resolve it against — is reported as undecidable, never folded into
     * "absent": with a {@code null} source the group could otherwise read as "all absent" and let
     * the rule run on exactly the unresolved join the facet exists to stop. And an absent qualified
     * entry whose whole dataset is missing says so in its label ("dataset TV not available"), as
     * {@link #describeIncludeEntry} does, so the reader can tell a missing column from a missing
     * dataset.
     * </p>
     *
     * <p>
     * ⚑ Pattern entries in one group are held to the <b>same shape</b> by loader gate R4 (review
     * round 1, M3): {@code [["TRTxxP", "TRTxxPN"]]} can never resolve to equal name sets and would
     * skip on every conformant ADSL, so it is a load error; whether such a pair should compare
     * <em>bound values</em> ({@code xx}) instead is an open question the plan files.
     * </p>
     */
    private static @Nullable String describeAllOrNoneGroup(List<String> group, int groupIndex,
            DataTableMeta meta, @Nullable String domainPrefix,
            @Nullable ScopeVariableSource foreign)
    {
        List<String> present = new ArrayList<>();
        List<String> absent = new ArrayList<>();
        // the first pattern entry's resolved names, and the first pattern entry disagreeing
        String referenceEntry = null;
        SortedSet<String> referenceNames = null;
        String unequalEntry = null;
        SortedSet<String> unequalNames = null;
        for (String varName : group)
        {
            EntryNames resolved = resolveEntryNames(varName, meta, domainPrefix, foreign);
            if (resolved.undecidable() != null)
            {
                return resolved.undecidable();
            }
            (resolved.names().isEmpty() ? absent : present).add(resolved.label());
            if (resolved.pattern() && !resolved.names().isEmpty())
            {
                if (referenceNames == null)
                {
                    referenceEntry = resolved.label();
                    referenceNames = resolved.names();
                }
                else if (unequalEntry == null && !referenceNames.equals(resolved.names()))
                {
                    unequalEntry = resolved.label();
                    unequalNames = resolved.names();
                }
            }
        }
        String prefix = "Requirements.Variables.All_Or_None group " + groupIndex + " " + group;
        if (!present.isEmpty() && !absent.isEmpty())
        {
            return prefix + " is only partly present — present: " + present + ", absent: " + absent;
        }
        if (unequalEntry != null)
        {
            return prefix + " resolves unequally — " + referenceEntry + " matches " + referenceNames
                    + " but " + unequalEntry + " matches " + unequalNames;
        }
        return null;
    }

    /**
     * One {@code All_Or_None} entry, resolved to the concrete column names it matches.
     *
     * @param label
     *            the entry for messages — raw, plus the {@code --}-resolved form when that differs
     *            ({@link #entryLabel})
     * @param names
     *            the matched column names, upper-cased and sorted; empty when the entry is absent
     * @param pattern
     *            whether the entry is a glob / regex / marker template (compared as a set) rather
     *            than a literal (compared by presence only)
     * @param undecidable
     *            the undecidable reason — a qualified entry with no foreign source, or a {@code --}
     *            entry with no domain prefix to resolve it against — else {@code null}
     */
    record EntryNames(String label, SortedSet<String> names, boolean pattern,
            @Nullable String undecidable)
    {
    }

    /**
     * The concrete columns one {@code Requirements.Variables} entry matches. ⚑ Package-private
     * since {@code PLAN-dynamic-column-functions} phase 3: {@code FindVarsTest}'s cross-surface
     * assertion compares {@code find_vars(e)} with this for the same entry and table, so the two
     * surfaces cannot drift unseen.
     */
    static EntryNames resolveEntryNames(String varName, DataTableMeta meta,
            @Nullable String domainPrefix, @Nullable ScopeVariableSource foreign)
    {
        ScopeVariableEntry entry = ScopeVariableEntry.parse(varName);
        String qualifier = entry.qualifier();
        SortedSet<String> names = new TreeSet<>();
        if (qualifier != null)
        {
            if (foreign == null)
            {
                return new EntryNames(varName, names, false,
                        undecidableQualifiedReason("All_Or_None", varName));
            }
            List<DataTableMeta> metas = foreign.metasOf(qualifier);
            Pattern pattern = scopeEntryPattern(entry.variable());
            boolean isPattern = pattern != null;
            if (pattern != null)
            {
                for (DataTableMeta member : metas)
                {
                    addColumnsMatching(member, pattern, names);
                }
            }
            // Literal: the member tables first, then the SUPP-QNAM pivot — the order
            // describeIncludeEntry uses, so the two facets agree about what "present" means.
            else if (anyHasColumn(metas, entry.variable())
                    || foreign.existsViaSuppQnam(qualifier, entry.variable()))
            {
                names.add(entry.variable().toUpperCase(Locale.ROOT));
            }
            // Name the RESOLVED dataset when the whole dataset is what is missing, so "the
            // column is not there" and "the dataset is not there" read apart (review L2).
            String label = names.isEmpty() && metas.isEmpty() ? varName + " (dataset "
                    + foreign.resolvedQualifier(qualifier) + " not available)" : varName;
            return new EntryNames(label, names, isPattern, null);
        }
        if (domainPrefix == null && entry.variable().startsWith(WILDCARD))
        {
            // A `--` entry with nothing to resolve it against would be tested as the literal
            // "--STDTC", which no dataset carries, and read as ABSENT — letting an all-`--`
            // group pass as all-absent. Undecidable instead (review L3).
            return new EntryNames(varName, names, false, "Requirements.Variables.All_Or_None entry "
                    + varName + " could not be decided — no domain prefix to resolve `--` against");
        }
        String resolved = resolveScopeVariable(entry.variable(), domainPrefix);
        String label = entryLabel(varName, entry.variable(), resolved);
        Pattern pattern = scopeEntryPattern(resolved);
        if (pattern != null)
        {
            addColumnsMatching(meta, pattern, names);
            return new EntryNames(label, names, true, null);
        }
        // W2a (C1 ruled (a)) — the bare arm mirrors describeIncludeEntry's: a column SUPP<domain>
        // of the primary delivers as a qualifier is present, so All_Or_None, All and
        // var_exists agree about what "present" means (combined review W2 M4).
        if (meta.getColumnIndex(resolved) >= 0
                || (foreign != null && foreign.localQualifier(resolved)))
        {
            names.add(resolved.toUpperCase(Locale.ROOT));
        }
        return new EntryNames(label, names, false, null);
    }


    /** Every column name in {@code meta} fully matching the pattern, upper-cased, into the set. */
    private static void addColumnsMatching(DataTableMeta meta, Pattern pattern,
            SortedSet<String> into)
    {
        for (int i = 0; i < meta.getColumnCount(); i++)
        {
            String column = meta.getColumn(i).getName();
            if (pattern.matcher(column).matches())
            {
                into.add(column.toUpperCase(Locale.ROOT));
            }
        }
    }


    /**
     * ONE {@code Any} group: satisfied as soon as ONE of its entries is present. Returns a mismatch
     * description only when EVERY entry of the group is absent — no single entry is at fault, so
     * the message names the group's list <b>and its 1-based index</b>: the caller iterates the
     * groups and returns the first unmet one (D5), and without the index a reader of a per-group
     * message could not tell group 2's mismatch from group 1's when both print similar entries.
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
     * ⚠ It <b>short-circuits</b> on the first satisfied entry <em>within the group</em> rather than
     * counting nulls. The difference is not stylistic: a two-entry group whose FIRST entry is
     * absent must still be satisfied by the second, so a fixture that only ever removes the second
     * entry cannot tell a correct implementation from one that answers on entry 1 — the mirror
     * image of {@code M3-J.5}'s conjunction trap. <b>With groups the trap gains a second axis</b>:
     * the groups are ANDed by the caller, so a fixture that only ever empties group 1 cannot tell a
     * correct implementation from one that answers on group 1 alone. All four fixtures are written
     * ({@code ScopeMatcherRequirementsTest}, {@code ScopeMatcherAnyGroupsTest} — the group-2-unmet
     * fixture is the load-bearing one).
     * </p>
     *
     * <p>
     * ⚑ History: until 2026-09-25 a {@code QualifiedEntryPolicy.IGNORE} option made
     * {@link #describeIncludeEntry} answer "satisfied" for every qualified entry under
     * {@code foreign == null}, so one qualified entry anywhere in a group satisfied <em>that
     * group</em> vacuously. That option had no production caller after the qualified-blind
     * overloads went (U1 / A7) and was retired with them (K6); an undecidable entry is now a
     * mismatch in every leg, and the per-group memory below is what keeps its reason honest.
     * </p>
     */
    private static @Nullable String describeAnyLeg(List<String> group, int groupIndex,
            DataTableMeta meta, @Nullable String domainPrefix,
            @Nullable ScopeVariableSource foreign)
    {
        // ⚠ An undecidable qualified entry is a mismatch like any other, so it does not satisfy
        // the group vacuously (the retired IGNORE option the javadoc above records). The group must
        // then NOT report "no variable present" — that would say "absent" where the truth is
        // "could not be decided" — so the undecidable reason is remembered and reported instead.
        // ⚠⚠ The memory is PER GROUP by construction (one call per group, one local): an
        // undecidable entry in group 1 must never decorate group 2's genuinely-absent answer, and
        // an undecidable group must never be reported as merely absent.
        String undecidable = null;
        String wrongType = null;
        for (String varName : group)
        {
            EntryMismatch mismatch = describeIncludeEntry(varName, meta, domainPrefix, foreign,
                    "Any");
            if (mismatch == null)
            {
                return null; // short-circuit: one present entry satisfies the whole group
            }
            if (undecidable == null && isUndecidableQualifiedEntry(varName, foreign))
            {
                undecidable = mismatch.reason();
            }
            // ⚠⚠ M7: a group whose entries are all PRESENT but wrongly typed must not fall
            // through to the "present in dataset" wording below — that says "absent" about a
            // column the reader can see in the dataset, which is simply false. Remembered the
            // same way the undecidable case is, and per group for the same reason.
            //
            // ⛔⛔ This used to test `reason.contains(" is required to be ")`. Review round 1,
            // finding 1: the diff introduces FOUR mismatch messages and only TWO carry that
            // phrase — the two PATTERN arms say "<col> matches the name but is Character"
            // instead. So a group of pattern entries, all present by name and all wrongly typed,
            // fell straight through to the absence wording this arm exists to suppress. The flag
            // is now set where the verdict is MADE and cannot drift when a message is reworded.
            if (wrongType == null && mismatch.typeMismatch())
            {
                wrongType = mismatch.reason();
            }
        }
        if (undecidable != null)
        {
            return undecidable;
        }
        if (wrongType != null)
        {
            return "no variable of Requirements.Variables.Any group " + groupIndex + " " + group
                    + " is of the required type — " + wrongType;
        }
        return "no variable of Requirements.Variables.Any group " + groupIndex + " " + group
                + " present in dataset";
    }


    /**
     * Whether {@code varName} is a qualified entry that is a mismatch purely because it cannot be
     * decided (no foreign-metadata source could be built). Shared by the three legs so they cannot
     * disagree about which entries are undecidable.
     *
     * @param varName
     *            the entry as authored
     * @param foreign
     *            the foreign-metadata source, or {@code null} when none could be built
     * @return whether the entry is undecidable
     */
    private static boolean isUndecidableQualifiedEntry(String varName,
            @Nullable ScopeVariableSource foreign)
    {
        return foreign == null && ScopeVariableEntry.parse(varName).isQualified();
    }


    /**
     * The mismatch reason for an entry that could not be decided. It names the <b>resolver</b>, not
     * the dataset, deliberately: a reader of the report must be able to tell "I could not look"
     * from "I looked and the column was not there", and the two are one word apart in a log.
     *
     * @param facet
     *            the requirement facet the entry belongs to ({@code All} / {@code Any} /
     *            {@code None})
     * @param varName
     *            the entry as authored
     * @return the reason string
     */
    private static String undecidableQualifiedReason(String facet, String varName)
    {
        return "Requirements.Variables." + facet + " entry " + varName
                + " could not be decided — the dataset resolver cannot enumerate other datasets";
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
        // All FOUR facets, or the lazy ScopeVariableSource is not built for a rule that needs it
        // and every qualified entry in the unscanned facet is undecidable — for All_Or_None that
        // would be a permanent SKIP of a rule whose foreign side could have been read.
        return anyQualified(required.getAll()) || anyQualified(required.anyUnion())
                || anyQualified(required.getNone()) || anyQualified(required.allOrNoneUnion());
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
     *
     * <p>
     * ⚠ {@code facet} names the CALLER's facet ({@code All} or {@code Any}) and appears in every
     * message built here. {@code All} and {@code Any} deliberately share one matcher so they can
     * never disagree about what "present" means — but that also means the leg cannot be inferred
     * inside it, and a hard-coded label is wrong half the time.
     * </p>
     */
    private record EntryMismatch(String reason, boolean typeMismatch)
    {

        /** A mismatch that is about PRESENCE — absent, or a dataset that could not be reached. */
        static EntryMismatch absent(String reason)
        {
            return new EntryMismatch(reason, false);
        }


        /** A mismatch that is about the column's TYPE — the column is there, of the wrong kind. */
        static EntryMismatch wrongType(String reason)
        {
            return new EntryMismatch(reason, true);
        }
    }

    /**
     * Lifts a nullable type-mismatch reason into the record; {@code null} stays "satisfied".
     *
     * <p>
     * ⚑ Every caller of this is a site that produced the reason through {@link #typeMismatch} or
     * {@link #pivotTypeMismatch}, so the {@code true} flag is set where the verdict is MADE. That
     * is the whole point of the record: the previous design recovered the same fact by testing the
     * message for a substring, and silently covered only half the message shapes.
     * </p>
     */
    private static @Nullable EntryMismatch asTypeMismatch(@Nullable String reason)
    {
        return reason == null ? null : EntryMismatch.wrongType(reason);
    }


    private static @Nullable EntryMismatch describeIncludeEntry(String varName, DataTableMeta meta,
            @Nullable String domainPrefix, @Nullable ScopeVariableSource foreign, String facet)
    {
        ScopeVariableEntry entry = ScopeVariableEntry.parse(varName);
        // ⭐ PLAN-variable-type-requirements: the demanded type, or null for an untagged entry —
        // in which case every branch below behaves exactly as it did before the feature (D5).
        ColumnTypeGate.Kind required = entry.requiredKind();
        String qualifier = entry.qualifier();
        if (qualifier != null)
        {
            if (foreign == null)
            {
                // ⚠ The facet comes from the CALLER, never from this method. `All` and `Any`
                // share this matcher, and describeAnyLeg propagates the string it returns as the
                // leg's own answer — so a hard-coded "All" made an `Any` rule report a facet it
                // does not declare. Review finding 1, 2026-09-10.
                return EntryMismatch.absent(undecidableQualifiedReason(facet, varName));
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
                    return asTypeMismatch(pivotTypeMismatch(facet, varName, dataset, required,
                            foreign, qualifier));
                }
                return EntryMismatch.absent("Requirements.Variables." + facet + " variable "
                        + varName + " not present — dataset " + dataset + " not available");
            }
            Pattern pattern = scopeEntryPattern(entry.variable());
            if (pattern != null)
            {
                String nameHit = firstColumnMatching(metas, pattern);
                if (nameHit == null)
                {
                    return EntryMismatch.absent("no variable matching Requirements.Variables."
                            + facet + " entry " + varName + " present in dataset " + dataset);
                }
                if (required != null && firstColumnMatching(metas, pattern, required) == null)
                {
                    return EntryMismatch.wrongType("no variable matching Requirements.Variables."
                            + facet + " entry " + varName + " present in dataset " + dataset + " — "
                            + nameHit + " matches the name but is "
                            + describeKind(kindOf(metas, nameHit)));
                }
            }
            else if (anyHasColumn(metas, entry.variable()))
            {
                // ⭐ D8: the type a JOIN would define for this qualifier. For a split domain that
                // is the UNIONED type, so every member carrying the column must agree —
                // agreedKind reproduces UnionDataTable's own comparison without calling
                // SplitDomainResolution.resolveTableOrThrow, which THROWS and which this gate
                // must not (the constraint JoinLookup.declaredTypeOf's decision D1 records).
                // Disagreement therefore yields null = undecidable, the entry does not block, and
                // the domain's own InvalidJoinedDomainException keeps reporting the clash.
                return asTypeMismatch(typeMismatch(facet, varName, required,
                        agreedKind(metas, entry.variable()), " in dataset " + dataset));
            }
            else if (foreign.existsViaSuppQnam(qualifier, entry.variable()))
            {
                return asTypeMismatch(
                        pivotTypeMismatch(facet, varName, dataset, required, foreign, qualifier));
            }
            else
            {
                return EntryMismatch.absent("Requirements.Variables." + facet + " variable "
                        + varName + " not present in dataset " + dataset);
            }
            return null;
        }
        // ⚠⚠ H3: this arm never used to touch the parsed record — it passed the RAW entry to
        // resolveScopeVariable and entryLabel. With a tag that resolves `--ORRES:N` to
        // `AEORRES:N`, which matches no column, so the rule would skip everywhere, silently.
        // Resolution runs on the VARIABLE half; the LABEL keeps the raw entry, tag included (M10).
        String resolved = resolveScopeVariable(entry.variable(), domainPrefix);
        Pattern pattern = scopeEntryPattern(resolved);
        if (pattern != null)
        {
            String nameHit = firstColumnMatching(meta, pattern);
            if (nameHit == null)
            {
                // no dataset variable matches the required pattern
                return EntryMismatch.absent("no variable matching Requirements.Variables." + facet
                        + " entry " + entryLabel(varName, entry.variable(), resolved)
                        + " present in dataset");
            }
            if (required != null && firstColumnMatching(meta, pattern, required) == null)
            {
                // ⚠ The pattern is satisfied by NAME but by no column of the demanded type. Said
                // apart from plain absence on purpose: "nothing matched" and "the match is the
                // wrong type" send an author to different places.
                return EntryMismatch.wrongType("no variable matching Requirements.Variables."
                        + facet + " entry " + entryLabel(varName, entry.variable(), resolved)
                        + " present in dataset — " + nameHit + " matches the name but is "
                        + describeKind(kindOf(meta, nameHit)));
            }
        }
        else if (meta.getColumnIndex(resolved) < 0)
        {
            // W2a (C1 ruled (a)): a bare entry SUPP<domain> delivers as a qualifier is present
            // for the existence surface. An untagged entry only — a typed requirement on a
            // qualifier is not a shipped shape and keeps the absent answer.
            if (required == null && foreign != null && foreign.localQualifier(resolved))
            {
                return null;
            }
            // required variable missing
            return EntryMismatch.absent("Requirements.Variables." + facet + " variable "
                    + entryLabel(varName, entry.variable(), resolved) + " not present in dataset");
        }
        else
        {
            return asTypeMismatch(
                    typeMismatch(facet, entryLabel(varName, entry.variable(), resolved), required,
                            kindOf(meta, resolved), ""));
        }
        return null;
    }


    /**
     * The type-mismatch reason for a present column, or {@code null} when the entry is satisfied.
     *
     * <p>
     * ⛔ Ruling <b>D2</b> lives here: an {@code actual} of {@code null} — a column whose
     * {@link DataValueType} {@link ColumnTypeGate#kindOf} does not classify, or a split domain
     * whose members disagree — is <b>not decidable</b> and therefore never blocks. The entry only
     * fails on a positively contradicted type.
     * </p>
     *
     * @param facet
     *            the requirement facet, for the message
     * @param label
     *            the entry as it should be shown — the RAW entry, tag included (M10)
     * @param required
     *            the demanded kind, {@code null} for an untagged entry
     * @param actual
     *            the column's kind, {@code null} when not decidable
     * @param where
     *            a trailing location clause, or {@code ""}
     * @return the reason, or {@code null}
     */
    private static @Nullable String typeMismatch(String facet, String label,
            ColumnTypeGate.@Nullable Kind required, ColumnTypeGate.@Nullable Kind actual,
            String where)
    {
        if (required == null || actual == null || required == actual)
        {
            return null;
        }
        return "Requirements.Variables." + facet + " variable " + label + " is required to be "
                + describeKind(required) + " but is " + describeKind(actual) + where;
    }


    /**
     * Ruling <b>D9</b> — the type of a variable delivered by the SUPP-QNAM pivot rather than by a
     * column. The pivot ({@code OperatorRegistry.existsInSuppQnam}) answers existence only: the
     * variable arrives as a {@code QNAM} <em>row</em>, so it has no column and no
     * {@link DataValueType} of its own. Its values are delivered through {@code QVAL}, so
     * {@code QVAL}'s declared type is the delivered variable's type — read from the SUPP table's
     * own metadata (<b>D6</b>), never asserted from the standard. No {@code QVAL} column at all
     * means no type, which is D2's undecidable and does not block.
     *
     * @return the reason, or {@code null} when the entry is satisfied
     */
    private static @Nullable String pivotTypeMismatch(String facet, String varName, String dataset,
            ColumnTypeGate.@Nullable Kind required, ScopeVariableSource foreign, String qualifier)
    {
        if (required == null)
        {
            return null;
        }
        ColumnTypeGate.Kind actual = ColumnTypeGate.kindOf(foreign.suppQvalType(qualifier));
        if (actual == null || actual == required)
        {
            return null;
        }
        return "Requirements.Variables." + facet + " variable " + varName + " is required to be "
                + describeKind(required) + " but is delivered as a supplemental qualifier of "
                + dataset + ", whose QVAL is " + describeKind(actual);
    }


    /** The author-facing spelling of a kind — the tags are N/C, so the words are the long ones. */
    private static String describeKind(ColumnTypeGate.@Nullable Kind kind)
    {
        if (kind == null)
        {
            return "of no decidable type";
        }
        return kind == ColumnTypeGate.Kind.NUMERIC ? "Numeric" : "Character";
    }


    /** The gate-relevant kind of one column of one table, or {@code null} when not decidable. */
    private static ColumnTypeGate.@Nullable Kind kindOf(DataTableMeta meta, String column)
    {
        int idx = meta.getColumnIndex(column);
        return idx < 0 ? null : ColumnTypeGate.kindOf(meta.getColumn(idx).getType());
    }


    /** Multi-table variant: the kind of the first table carrying the column, for a message only. */
    private static ColumnTypeGate.@Nullable Kind kindOf(List<DataTableMeta> metas, String column)
    {
        for (DataTableMeta meta : metas)
        {
            int idx = meta.getColumnIndex(column);
            if (idx >= 0)
            {
                return ColumnTypeGate.kindOf(meta.getColumn(idx).getType());
            }
        }
        return null;
    }


    /**
     * Ruling <b>D8</b> — the kind every member of a (possibly split) qualifier agrees on, or
     * {@code null} when they disagree, when no member carries the column, or when the agreed type
     * is one {@link ColumnTypeGate#kindOf} does not classify.
     *
     * <p>
     * ⚠ The comparison is on the raw {@link DataValueType}, not on the kind, because that is what
     * {@code UnionDataTable}'s split constructor compares before refusing to union — <i>"a type
     * clash is a submission defect the sponsor must see"</i>. Comparing kinds instead would let
     * {@code LONG} and {@code DOUBLE} members union here while the engine refuses them.
     * </p>
     */
    private static ColumnTypeGate.@Nullable Kind agreedKind(List<DataTableMeta> metas,
            String column)
    {
        DataValueType agreed = null;
        for (DataTableMeta meta : metas)
        {
            int idx = meta.getColumnIndex(column);
            if (idx < 0)
            {
                continue;
            }
            DataValueType type = meta.getColumn(idx).getType();
            // ⚠ Review round 1, finding 3: a member whose declared type is null used to leave
            // `agreed` null, so the NEXT member's type was adopted as "agreed" and the null member
            // dropped silently out of the comparison — where UnionDataTable compares against the
            // first occurrence unconditionally and would refuse the union. THIS early return is
            // what keeps "no member carries it" and "a member has no type" apart.
            // ⚑ Round 2's nit: a `seen` flag was added alongside and was dead state — with the
            // early return, `agreed == null` and `!seen` coincide everywhere. Removed, because two
            // mechanisms claiming one mechanism's job is what later invites simplifying the wrong
            // half.
            // ⚑ DataTableColumnMeta.type is not @Nullable, so this is a contract-defensive branch,
            // not a demonstrated input — but ScopeVariableSource.suppQvalType already guards the
            // same field, and the two must not hold different beliefs about it.
            if (type == null)
            {
                return null;
            }
            if (agreed == null)
            {
                agreed = type;
            }
            else if (agreed != type)
            {
                return null;
            }
        }
        return agreed == null ? null : ColumnTypeGate.kindOf(agreed);
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
                // ⚠ Undecidable applies to None as well, and for the same reason: "no entry may
                // be present" is as undecidable as "every entry must be", so answering "excludes
                // nothing" is an answer the resolver has not earned. Zero corpus carriers today
                // (VariableRequirement's javadoc records that), so this arm is gate-tested only.
                return undecidableQualifiedReason("None", varName);
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
        // ⚑ Reads the variable half for the same reason the include arm does, even though ruling
        // D1 makes a type tag in `None` a load error: if one ever reaches here, resolving the raw
        // entry would silently match nothing and the exclusion would quietly stop excluding.
        String resolved = resolveScopeVariable(entry.variable(), domainPrefix);
        Pattern pattern = scopeEntryPattern(resolved);
        if (pattern != null)
        {
            String hit = firstColumnMatching(meta, pattern);
            if (hit != null)
            {
                // a dataset variable matches the rejecting pattern
                return "variable " + hit + " matches Requirements.Variables.None entry "
                        + entryLabel(varName, entry.variable(), resolved);
            }
        }
        else if (meta.getColumnIndex(resolved) >= 0)
        {
            // excluded variable is present
            return "Requirements.Variables.None variable "
                    + entryLabel(varName, entry.variable(), resolved) + " present in dataset";
        }
        else if (foreign != null && foreign.localQualifier(resolved))
        {
            // W2a (C1 ruled (a)) — the mirror of describeIncludeEntry's bare arm: a variable
            // SUPP<domain> delivers as a qualifier is present for the existence surface, so
            // None must exclude it exactly when var_exists would answer true (combined review
            // W2 M4).
            return "Requirements.Variables.None variable "
                    + entryLabel(varName, entry.variable(), resolved)
                    + " present as a supplemental qualifier of the dataset";
        }
        return null;
    }


    /**
     * <b>Fix #179 — the set-valued {@code Scope.Data_Structures} matcher, and the one production
     * callers use.</b> A dataset carries a <em>set</em> of structures, most-specific first
     * ({@link net.cumba.corej.core.metadata.AdamDataStructureDetector#detectAll}): a medical-device
     * BDS dataset is {@code [MEDICAL DEVICE BASIC DATA STRUCTURE, BASIC DATA STRUCTURE]}. An empty
     * set means "undetermined" and is rejected by an Include list. Semantics mirror the Python
     * engine's {@code rule_applies_to_data_structure} with two documented house deviations:
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
     * (case/separator-insensitive), consistent with the class matcher. Lifted over the set exactly
     * as {@link #describeSubclassMismatch(Rule, List)} lifts the subclass gate:
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
     * {@link net.cumba.corej.core.metadata.AdamDataStructureDetector#structureSet}, never
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
     * The {@code Scope.Subclasses} matcher. {@code detectedSubclasses} are the dataset's subclass
     * tokens from {@link net.cumba.corej.core.metadata.AdamSubclassDetector#resolve} (Define-XML
     * allows multiple {@code <def:SubClass>} declarations), empty when the dataset has no
     * detectable subclass — the normal case for a plain BDS/OCCDS/ADSL dataset. {@code Include}
     * (without {@code ALL}) requires a positively detected subclass in the list — with none
     * detected the dataset is skipped with a reason naming the Include list; it is satisfied when
     * <b>any</b> detected token is in the list. {@code Exclude} rejects only on a positive match,
     * when any detected token matches — a dataset with no subclass passes an Exclude-only scope
     * (decided 2026-07-26). No engine-side Python counterpart exists upstream (schema-only field).
     * Tokens compare via {@link #normalize}.
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
     * Whether a requirement entry's <em>variable half</em> is a pattern — glob, {@code /regex/} or
     * a wildcard-marker template — rather than a literal name. The loader's {@code All_Or_None}
     * same-shape gate reads it; the answer is exactly {@link #scopeEntryPattern}'s, so the gate and
     * the matcher cannot disagree about which entries are compared as sets.
     *
     * @param variable
     *            the entry's variable half (qualifier and type suffix already removed)
     * @return whether it compiles to a pattern
     * @throws java.util.regex.PatternSyntaxException
     *             for an invalid {@code /…/} entry — the pattern gate reports that one
     */
    public static boolean isPatternEntry(String variable)
    {
        return scopeEntryPattern(variable) != null;
    }


    /**
     * Pattern for a variable-requirement entry, or {@code null} for a literal. A glob / regex entry
     * ({@link #scopePattern}) takes precedence; otherwise an entry carrying the wildcard markers
     * ({@code xx}, {@code zz}, {@code y}, {@code w} — e.g. {@code TRTxxP}) compiles via
     * {@link WildcardExpander#scopeVariableWildcardPattern} so it matches any concrete column
     * (at-least-one semantics, mirroring the Check-side wildcard expansion). Without the marker
     * branch, a template's variable scope would be tested literally and the rule skipped even when
     * a matching concrete column (e.g. {@code TRT01P} for {@code TRTxxP}) exists.
     *
     * <p>
     * ⭐ Package-private, not private, since {@code PLAN-dynamic-column-functions} §2.4: it is the
     * ONE matcher {@code Requirements.Variables}, {@code find_vars} ({@link FindVars}) and the
     * {@code Output_Variables} pattern step share, so the three surfaces cannot drift. Behaviour
     * unchanged. It takes the entry's VARIABLE half —
     * {@link WildcardExpander#scopeVariableWildcardPattern} answers {@code null} for a qualified
     * entry.
     * </p>
     */
    static @Nullable Pattern scopeEntryPattern(String resolved)
    {
        Pattern pattern = scopePattern(resolved);
        return pattern != null ? pattern : WildcardExpander.scopeVariableWildcardPattern(resolved);
    }


    /**
     * Renders a variable-requirement entry for a mismatch message: the raw entry, plus the
     * {@code --}-resolved form when resolution changed it (e.g. {@code "--SEQ (resolved AESEQ)"}).
     *
     * <p>
     * ⚠⚠ The "did resolution change it?" test is against the entry's <b>variable half</b>, not
     * against the raw entry. Comparing against the raw made a type-tagged entry claim a resolution
     * that never happened — {@code "AETERM:N (resolved AETERM)"} on a dataset with no {@code --}
     * anywhere, because the tag alone made the two strings differ. Found by
     * {@code ScopeMatcherTypeRequirementTest}.
     * </p>
     *
     * @param rawEntry
     *            the entry exactly as authored, tag included — what the reader typed
     * @param variable
     *            its variable half, tag stripped, before {@code --} resolution
     * @param resolved
     *            the variable half after {@code --} resolution
     */
    private static String entryLabel(String rawEntry, String variable, String resolved)
    {
        return resolved.equals(variable) ? rawEntry : rawEntry + " (resolved " + resolved + ")";
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
     * Kind-aware variant: the first column matching the pattern <b>and</b> acceptable for
     * {@code required}. ⛔ It must scan on rather than answering about the first NAME match: a
     * dataset carrying both {@code AEORRES} (Char) and {@code AEORRESN} (Num) satisfies
     * {@code /^..ORRES.?$/:N} through the second, and testing only the first would skip it. A
     * column whose kind is not decidable is accepted (D2).
     */
    private static @Nullable String firstColumnMatching(DataTableMeta meta, Pattern pattern,
            ColumnTypeGate.Kind required)
    {
        for (int i = 0; i < meta.getColumnCount(); i++)
        {
            String column = meta.getColumn(i).getName();
            if (!pattern.matcher(column).matches())
            {
                continue;
            }
            ColumnTypeGate.Kind kind = ColumnTypeGate.kindOf(meta.getColumn(i).getType());
            if (kind == null || kind == required)
            {
                return column;
            }
        }
        return null;
    }


    /** Multi-table kind-aware variant — see {@link #firstColumnMatching(List, Pattern)}. */
    private static @Nullable String firstColumnMatching(List<DataTableMeta> metas, Pattern pattern,
            ColumnTypeGate.Kind required)
    {
        for (DataTableMeta meta : metas)
        {
            String hit = firstColumnMatching(meta, pattern, required);
            if (hit != null)
            {
                return hit;
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
     * Domain-pattern matcher used by {@link #describeDomainMismatch}. Literal entries match the
     * dataset name by <em>exact</em> equality after {@link #normalize normalisation}
     * ({@link #matchesDomainLiteral}), mirroring the reference Python engine's
     * {@code rule_processor._is_domain_name_included} / {@code _is_domain_name_excluded}, which are
     * plain list-membership tests
     * ({@code dataset_metadata.domain in included_domains or dataset_metadata.name in
     * included_domains}) with no prefix logic. Class-level matching
     * ({@link #describeClassMismatch}) continues to use {@link #firstMatchingClassEntry}; the two
     * now differ only in this method's glob / regex support. Returns the matching entry (so
     * mismatch describers can name it), or {@code null} when no entry matches.
     * <p>
     * Extended-name and split-form datasets are reached through the <em>callers'</em> split-base
     * re-test, not through this method: {@link #describeDomainMismatch(Rule, String, String)}
     * re-tests the dataset's canonical unsplit name — read from the {@code DOMAIN} /
     * {@code RDOMAIN} columns by {@link DatasetIdentity#unsplitNameFromData} — so
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
     * {@link net.cumba.corej.core.RulePackageLoader} rejects them at load time, since a zero-length
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
     * {@link net.cumba.corej.core.RulePackageLoader}, which pre-compiles every
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
     * dataset name, and {@link net.cumba.corej.core.RulePackageLoader} already rejects a literal
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
