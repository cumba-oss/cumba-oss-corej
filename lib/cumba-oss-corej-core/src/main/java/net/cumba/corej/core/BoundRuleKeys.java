package net.cumba.corej.core;

import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.model.Authority;
import net.cumba.corej.core.model.AuthorityStandard;
import net.cumba.corej.core.model.Binding;
import net.cumba.corej.core.model.Citation;
import net.cumba.corej.core.model.ClassScope;
import net.cumba.corej.core.model.DataStructureScope;
import net.cumba.corej.core.model.DatasetScope;
import net.cumba.corej.core.model.DomainScope;
import net.cumba.corej.core.model.ExecutabilityHint;
import net.cumba.corej.core.model.ExpansionDirective;
import net.cumba.corej.core.model.GroupingSpec;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Reference;
import net.cumba.corej.core.model.Requirements;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.RuleIdentifier;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.corej.core.model.Scope;
import net.cumba.corej.core.model.StandardRef;
import net.cumba.corej.core.model.SubclassScope;
import net.cumba.corej.core.model.VariableRequirement;
import net.cumba.corej.core.model.WildcardFilter;
import org.jspecify.annotations.Nullable;

/**
 * The JSON keys the rule model binds, per class — the rosters behind the loader's unknown-key gate
 * ({@code PLAN-rule-unknown-keys-gate} &#167;5.3 / &#167;7.2).
 *
 * <p>
 * ⭐ Each roster is <b>pinned</b> by {@code BoundKeysRosterTest} against what Jackson's
 * <em>built</em> deserializer for the class actually routes a key to (setter, field, creator
 * parameter, getter-as-setter, plus {@code @JsonAlias} spellings) — equality, not a subset. A key
 * bound on the model without a roster edit reds the build, and the roster's javadoc in the test
 * names the production reader of every key, so a bound-but-unread key (the retired
 * {@code Match_Datasets.Wildcard} defect) has to be argued for in a place a reviewer sees.
 * </p>
 *
 * <p>
 * The same sets feed {@link #hint}: when an unknown key equals exactly one bound key ignoring case,
 * the load error says <i>did you mean 'Outcome'?</i> (T1-3 a). Because the hint reads the pinned
 * roster and the roster is pinned to the model, the hint cannot drift from what binds.
 * </p>
 */
final class BoundRuleKeys
{

    /** Top level of a rule — {@link Rule}. */
    static final Set<String> RULE = Set.of("id", "Core", "Description", "ExecutabilityHint",
            "Authorities", "Scope", "Requirements", "Outcome", "Bindings", "Match_Datasets",
            "Grouping_Variables", "Grouping", "Precondition", "Expansion", "skipIfLibraryDefined",
            "wildcards", "wildcardExclude", "wildcardPairCatalogue", "Check", "Rule_Type",
            "Sensitivity", "Severity", "Executability", "Variable_Universe", "Operations");

    /** A package — {@link RulePackage}. */
    static final Set<String> RULE_PACKAGE = Set.of("rules", "standards");

    /** A {@code standards[]} entry — {@link StandardRef}, collected by {@link RulePackage}. */
    static final Set<String> STANDARD_REF = RulePackage.STANDARD_REF_KEYS;

    static final Set<String> CORE = Set.of("Id", "Status", "Version");

    static final Set<String> OUTCOME = Set.of("Message", "Output_Variables");

    static final Set<String> EXECUTABILITY_HINT = Set.of("Category", "Detail");

    static final Set<String> AUTHORITY = Set.of("Organization", "Standards", "Rule_Ids");

    static final Set<String> AUTHORITY_STANDARD = Set.of("Name", "Version", "Substandard",
            "References");

    static final Set<String> REFERENCE = Set.of("Origin", "Version", "Rule_Identifier",
            "Citations");

    static final Set<String> RULE_IDENTIFIER = Set.of("Id", "Version");

    static final Set<String> CITATION = Set.of("Cited_Guidance", "Document", "Item", "Section");

    /**
     * {@code Scope} — {@code Data Structures} is the {@code @JsonAlias} of {@code Data_Structures}.
     */
    static final Set<String> SCOPE = Set.of("Classes", "Domains", "Datasets", "Use_Case",
            "Data_Structures", "Data Structures", "Subclasses");

    static final Set<String> CLASS_SCOPE = Set.of("Include", "Exclude");

    static final Set<String> DOMAIN_SCOPE = Set.of("Include", "Exclude", "include_split_datasets");

    static final Set<String> DATASET_SCOPE = Set.of("Include", "Exclude");

    static final Set<String> DATA_STRUCTURE_SCOPE = Set.of("Include", "Exclude");

    static final Set<String> SUBCLASS_SCOPE = Set.of("Include", "Exclude");

    static final Set<String> REQUIREMENTS = Set.of("Variables", "Datasets", "Library", "Define",
            "Dictionary");

    static final Set<String> VARIABLE_REQUIREMENT = Set.of("All", "Any", "None", "All_Or_None");

    static final Set<String> BINDING = Set.of("name", "expression");

    /** {@code Match_Datasets[]} — the loader's own list, which its error message quotes. */
    static final Set<String> MATCH_DATASET = Set.copyOf(RulePackageLoader.MATCH_DATASET_KEYS);

    /** A sided {@code Match_Datasets[].Keys[]} element — a {@code JsonNode} shape, not a class. */
    static final Set<String> MATCH_KEY_ELEMENT = Set.of("left", "right");

    static final Set<String> GROUPING = Set.of("Variables", "keep_missings");

    static final Set<String> EXPANSION = Set.of("token", "over", "with", "pattern",
            "known_domain_only");

    static final Set<String> WILDCARD_FILTER = Set.of("min", "max");

    /**
     * Every bean class a rule package deserializes into, with its roster — the population
     * {@code BoundKeysRosterTest} walks. The custom-deserialized {@code RuleCheck} /
     * {@code CheckCondition} have no bean roster (their grammar is
     * {@code CheckConditionDeserializer.DISPATCH_ORDER} plus {@code Message} on a level entry).
     */
    static final Map<Class<?>, Set<String>> BY_CLASS = Map.ofEntries(
            Map.entry(RulePackage.class, RULE_PACKAGE), Map.entry(StandardRef.class, STANDARD_REF),
            Map.entry(Rule.class, RULE), Map.entry(RuleCore.class, CORE),
            Map.entry(Outcome.class, OUTCOME),
            Map.entry(ExecutabilityHint.class, EXECUTABILITY_HINT),
            Map.entry(Authority.class, AUTHORITY),
            Map.entry(AuthorityStandard.class, AUTHORITY_STANDARD),
            Map.entry(Reference.class, REFERENCE), Map.entry(RuleIdentifier.class, RULE_IDENTIFIER),
            Map.entry(Citation.class, CITATION), Map.entry(Scope.class, SCOPE),
            Map.entry(ClassScope.class, CLASS_SCOPE), Map.entry(DomainScope.class, DOMAIN_SCOPE),
            Map.entry(DatasetScope.class, DATASET_SCOPE),
            Map.entry(DataStructureScope.class, DATA_STRUCTURE_SCOPE),
            Map.entry(SubclassScope.class, SUBCLASS_SCOPE),
            Map.entry(Requirements.class, REQUIREMENTS),
            Map.entry(VariableRequirement.class, VARIABLE_REQUIREMENT),
            Map.entry(Binding.class, BINDING), Map.entry(MatchDataset.class, MATCH_DATASET),
            Map.entry(GroupingSpec.class, GROUPING), Map.entry(ExpansionDirective.class, EXPANSION),
            Map.entry(WildcardFilter.class, WILDCARD_FILTER));

    /**
     * Bound keys never offered as a hint: the retired spellings, which are themselves rejected on
     * the next load (review L1), and {@code id}, which is the loader's / the harness's synthetic
     * identity — an author's {@code Id} belongs under {@code Core}.
     */
    static final Set<String> NEVER_HINTED = Set.of("Operations", "Rule_Type", "id");

    private BoundRuleKeys()
    {
    }


    /**
     * The one bound key {@code key} is a near miss of, or {@code null} when none or several are.
     *
     * <p>
     * A near miss is the same spelling ignoring case (T1-3 a: {@code outcome} → {@code Outcome}) or
     * one edit away ignoring case — one character dropped, added, changed or transposed
     * ({@code Outcom}, {@code Mesage}, {@code Versoin}); the plan's own case table expects those
     * hints. Two or more candidates give no hint rather than a wrong one.
     * </p>
     *
     * @param key
     *            the unknown key
     * @param bound
     *            the roster of the block the key was found under
     * @return the hinted spelling, or {@code null}
     */
    static @Nullable String hint(String key, Set<String> bound)
    {
        String found = null;
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        for (String candidate : bound)
        {
            if (NEVER_HINTED.contains(candidate))
            {
                continue;
            }
            if (editDistance(lower, candidate.toLowerCase(java.util.Locale.ROOT)) <= 1)
            {
                if (found != null)
                {
                    return null;
                }
                found = candidate;
            }
        }
        return found;
    }


    /** Damerau–Levenshtein distance (optimal string alignment), capped at 2. */
    static int editDistance(String a, String b)
    {
        if (Math.abs(a.length() - b.length()) > 1)
        {
            return 2;
        }
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++)
        {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++)
        {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++)
        {
            for (int j = 1; j <= b.length(); j++)
            {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int best = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1),
                        d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2)
                        && a.charAt(i - 2) == b.charAt(j - 1))
                {
                    best = Math.min(best, d[i - 2][j - 2] + 1);
                }
                d[i][j] = best;
            }
        }
        return Math.min(d[a.length()][b.length()], 2);
    }
}
