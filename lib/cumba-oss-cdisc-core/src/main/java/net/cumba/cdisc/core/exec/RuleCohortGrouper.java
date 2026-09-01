package net.cumba.cdisc.core.exec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.cumba.cdisc.core.model.CheckCondition;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionAny;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.MatchDataset;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.datatable.DataTableMeta;
import org.jspecify.annotations.Nullable;

/**
 * Groups rules into cohorts that can share a single row pass through the primary table.
 *
 * <h2>What is a cohort?</h2> A cohort is a group of two or more rules that share the same Check
 * shape modulo column names. Two shapes are recognised:
 *
 * <ul>
 * <li><b>{@link CohortKind#EQUALITY}</b> — single-leaf {@code equal_to}/{@code not_equal_to}
 * comparing a primary column against a foreign-dataset reference (e.g.
 * {@code CDISC-AD0591-<dataset>-<column>} family: TRTSDT vs ADSL.TRTSDT, AGE vs ADSL.AGE, …, joined
 * by USUBJID).</li>
 * <li><b>{@link CohortKind#MEMBERSHIP}</b> — two-leaf
 * {@code all([non_empty(var), is_not_contained_by(var, [terms])])} per-row codelist check (e.g.
 * {@code GEN-CL-<dataset>-<column>} family). Each rule has its own term list; only the row pass is
 * shared.</li>
 * </ul>
 *
 * Today such rules each rescan the entire dataset independently. As a cohort they share one scan;
 * per row each cohort rule's leaf is evaluated against the same row read.
 *
 * <h2>Eligibility — equality cohorts</h2> A rule is in an EQUALITY cohort when:
 * <ul>
 * <li>It has exactly one Check leaf (the Check itself is a {@link CheckConditionLeaf}, or an
 * {@link CheckConditionAll}/{@link CheckConditionAny} containing exactly one leaf).</li>
 * <li>The leaf operator is one of: {@code equal_to}, {@code not_equal_to},
 * {@code equal_to_case_insensitive}, {@code not_equal_to_case_insensitive}.</li>
 * <li>It carries at least one {@code Match_Datasets} entry with non-empty Keys.</li>
 * <li>The leaf value is a textual foreign-dataset reference of the form {@code "<dsName>.<col>"}
 * where {@code <dsName>} matches one of the rule's {@code Match_Datasets} entries.</li>
 * <li>The leaf name is a plain primary-table column (no {@code $} prefix, no {@code .}, no
 * operand-template placeholder).</li>
 * <li>The leaf has no modifiers active and the rule has no Operations / Grouping / Precondition
 * (see source for full list).</li>
 * </ul>
 *
 * <h2>Eligibility — membership cohorts</h2> A rule is in a MEMBERSHIP cohort when:
 * <ul>
 * <li>The Check is a {@link CheckConditionAll} containing exactly two leaves.</li>
 * <li>Leaf #1: operator {@code non_empty}, no modifiers, no value.</li>
 * <li>Leaf #2: operator {@code is_not_contained_by}, value is a non-empty JSON array of textual
 * terms, no modifiers, {@code valueIsLiteral} not set, no {@code $}-grouped reference.</li>
 * <li>Both leaves reference the same plain primary-table column
 * ({@code leaf1.name == leaf2.name}).</li>
 * <li>No {@code Match_Datasets}, no Operations, no Grouping, no Precondition.</li>
 * </ul>
 *
 * <h2>Clustering rule</h2> Two eligible rules join the same cohort iff their {@link CohortKey}s are
 * equal — see the record for the key fields. Cohort kinds are mutually exclusive: an equality
 * cohort and a membership cohort never merge even if they share a key prefix.
 *
 * <h2>Result</h2> The grouper preserves <em>input order</em> end-to-end: the returned list contains
 * groups in the order rules first appear in the input, and rules within a group keep their relative
 * input order. Singletons (eligible rules that don't cluster, plus any ineligible rules) come back
 * as length-1 groups so the caller can iterate uniformly. The dispatcher is expected to
 * short-circuit length-1 groups onto {@link RuleRunner#execute} and only invoke
 * {@link CohortRunner} for groups of size {@code >= 2}.
 */
public final class RuleCohortGrouper
{

    /** Discriminator on the cohort's per-row predicate shape. Stored in {@link CohortKey}. */
    public enum CohortKind
    {

        /** Single-leaf equality vs foreign-dataset reference (e.g. CDISC-AD0591). */
        EQUALITY,
        /** Two-leaf {@code non_empty + is_not_contained_by(literal-array)} (e.g. GEN-CL). */
        MEMBERSHIP
    }

    private static final Set<String> EQUALITY_OPERATORS = Set.of("equal_to", "not_equal_to",
            "equal_to_case_insensitive", "not_equal_to_case_insensitive");

    private RuleCohortGrouper()
    {
    }


    /**
     * Backwards-compatible overload — see {@link #group(List, DataTableMeta)}. Skips the
     * column-presence pre-screen ({@code meta} defaults to {@code null}); a rule whose primary leaf
     * references an absent column will still cluster, and the {@link CohortRunner} will bail with
     * {@link IllegalStateException} when it discovers the missing column. Suitable for unit tests
     * that don't have a real dataset; production code should pass the table meta via
     * {@link #group(List, DataTableMeta)}.
     */
    public static List<List<Rule>> group(List<Rule> rules)
    {
        return group(rules, null);
    }


    /**
     * Groups the given rules into cohorts. Each returned list is a cohort; length-1 groups are
     * rules that didn't cluster (singletons or ineligible rules).
     *
     * <p>
     * When {@code meta} is non-null, rules whose primary leaf column is absent from the dataset are
     * demoted to singletons regardless of cohort eligibility. They run through the per-rule path
     * where {@code CheckConditionOptimizer}'s row-level column-presence folding (Fix #40) handles
     * the missing column gracefully — without this pre-screen the cohort dispatcher would discover
     * the missing column at execution time and bail with {@link IllegalStateException}, forcing the
     * entire cohort to fall back to per-rule.
     * </p>
     */
    public static List<List<Rule>> group(List<Rule> rules, @Nullable DataTableMeta meta)
    {
        return group(rules, meta, _ -> false);
    }


    /**
     * As {@link #group(List, DataTableMeta)}, with an extra caller-supplied demotion predicate: a
     * rule for which {@code demote} returns {@code true} is forced to its own singleton however
     * cohort-eligible its shape is.
     *
     * <p>
     * {@code Fix #222} is the reason it exists, and {@code Fix #218} widened what it must catch: a
     * <em>cross-standard</em> dependency that the run cannot meet collapses the same way, and
     * {@code CDISC-AD0204}–{@code AD0210} share an identical Check shape, so they cohort. A caller
     * that does not widen its predicate would let them keep reporting PASS. The
     * {@link CohortRunner} evaluates a cohort with a shared row pass that reads
     * {@code Rule.getCheckExpr()} directly, so it cannot see the dependency-scoped suppression
     * {@link AbsentDatasetSkip} applies per (rule, dataset). Demoting a suppressed rule routes it
     * back through {@code RuleRunner.execute}, which owns that decision — and cohort-eligible
     * shapes (a single foreign-dataset equality leaf) always collapse to {@code SKIPPED} anyway, so
     * nothing is lost but the batching.
     * </p>
     *
     * @param demote
     *            {@code true} for a rule that must not be cohorted
     */
    public static List<List<Rule>> group(List<Rule> rules, @Nullable DataTableMeta meta,
            java.util.function.Predicate<Rule> demote)
    {
        // First pass: classify each rule. Eligible rules get a cohort key; ineligible rules get
        // null (treated as their own singleton).
        Map<CohortKey, List<Rule>> byKey = new LinkedHashMap<>();
        List<Object> orderTokens = new ArrayList<>();
        Map<CohortKey, Integer> firstAppearance = new LinkedHashMap<>();

        for (Rule r : rules)
        {
            CohortKey key = cohortKey(r);
            if (key != null && demote.test(r))
            {
                // Fix #222: the caller has a per-(rule, dataset) decision the cohort runner
                // cannot honour — run this one through the per-rule path.
                key = null;
            }
            if (key != null && meta != null && !primaryLeafColumnPresent(r, meta))
            {
                // Eligible by shape but the primary leaf column isn't in this dataset — demote
                // to singleton so the per-rule path (with Fix #40 column-presence folding) runs
                // it without the cohort runner's hard-throw.
                key = null;
            }
            if (key != null)
            {
                byKey.computeIfAbsent(key, _ -> new ArrayList<>()).add(r);
                firstAppearance.putIfAbsent(key, orderTokens.size());
                orderTokens.add(key);
            }
            else
            {
                orderTokens.add(r);
            }
        }

        // Second pass: emit groups in input order. Each cohort is emitted at the first input
        // position of any of its members; subsequent members are skipped (already in the cohort).
        List<List<Rule>> out = new ArrayList<>();
        Set<CohortKey> emitted = new java.util.HashSet<>();
        for (Object token : orderTokens)
        {
            if (token instanceof CohortKey ck)
            {
                if (emitted.add(ck))
                {
                    out.add(List.copyOf(byKey.get(ck)));
                }
                // else: this rule already emitted as part of its cohort
            }
            else
            {
                out.add(List.of((Rule) token));
            }
        }
        return out;
    }


    /**
     * Returns the cohort key for an eligible rule, or {@code null} if the rule is ineligible. Rules
     * with the same key cluster together. Tries the equality predicate first, then membership; the
     * first non-null wins.
     */
    static @Nullable CohortKey cohortKey(Rule rule)
    {
        if (rule == null || rule.getCheck() == null)
        {
            return null;
        }
        // A rule flagged with a load error (e.g. a definitionally-invalid var_*/ds_* accessor) must
        // not be batched: the cohort backend would report it EXECUTED and discard the error. Demote
        // it to a singleton so RuleRunner's load-error short-circuit reports it as ERROR.
        if (rule.getLoadError() != null)
        {
            return null;
        }
        // Plan C §3.4 (D9) — a rule DECLARING A LEVEL MAP is never cohorted, a one-entry map
        // included. A cohort shares ONE evaluation across rules with identical Checks and projects
        // it back per member; a level-mapped rule needs the per-level machinery (level stamping,
        // the level's own Message, first-claim across rungs), and neither cohort path (equality or
        // membership) replicates any of it. Excluding it is the same stance this method already
        // takes for Operations / Grouping / Precondition: anything with per-rule evaluation
        // semantics is demoted to a singleton and runs through RuleRunner. Kept consistent with
        // RulePackageLoader.installCompiledLevels and AbsentDatasetSkip.barePresenceDataset.
        // ⚑ Vacuous on the shipped corpus — no rule declares a level map — and pinned by test
        // so it cannot lapse when one does.
        if (rule.getCheckLevels() != null && !rule.getCheckLevels().isEmpty())
        {
            return null;
        }
        // Common "anything that defeats both predicates" gates. Operations / Grouping /
        // Precondition all bring per-rule semantics that neither cohort path replicates.
        if (rule.getOperations() != null && !rule.getOperations().isEmpty())
        {
            return null;
        }
        List<String> cohortGrouping = rule.effectiveGroupingVariables();
        if (cohortGrouping != null && !cohortGrouping.isEmpty())
        {
            return null;
        }
        if (rule.getPrecondition() != null)
        {
            return null;
        }
        CohortKey eq = equalityCohortKey(rule);
        if (eq != null)
        {
            return eq;
        }
        return membershipCohortKey(rule);
    }


    /**
     * Equality cohort eligibility — single-leaf {@code equal_to}/{@code not_equal_to} vs a
     * foreign-dataset reference. Returns the cohort key or {@code null} if not eligible.
     */
    private static @Nullable CohortKey equalityCohortKey(Rule rule)
    {
        List<MatchDataset> mds = rule.getMatchDatasets();
        if (mds == null || mds.isEmpty())
        {
            return null;
        }
        // Canonicalise Match_Datasets — every entry must have a Name and non-empty Keys (the
        // foreign-ref lookup requires a key-based join).
        List<CanonMatch> canon = new ArrayList<>(mds.size());
        for (MatchDataset md : mds)
        {
            if (md == null || md.getName() == null || md.getKeys() == null
                    || md.getKeys().isEmpty())
            {
                return null;
            }
            // Reject anything beyond a plain key-based join — Child/Wildcard/Join_Type all
            // change row semantics in ways the cohort runner doesn't replicate.
            if (Boolean.TRUE.equals(md.getChild()) || md.getWildcard() != null
                    || md.getJoinType() != null)
            {
                return null;
            }
            canon.add(new CanonMatch(md.getName(), List.copyOf(md.getKeys())));
        }

        CheckConditionLeaf leaf = extractSingleLeaf(rule.getCheck());
        if (leaf == null)
        {
            return null;
        }
        String op = leaf.getOperator();
        if (op == null || !EQUALITY_OPERATORS.contains(op))
        {
            return null;
        }
        if (hasActiveModifier(leaf))
        {
            return null;
        }
        if (Boolean.TRUE.equals(leaf.getValueIsLiteral())
                || Boolean.TRUE.equals(leaf.getNegative()))
        {
            return null;
        }
        // Name must be a plain primary-table column.
        String name = leaf.getName();
        if (!isPlainColumn(name))
        {
            return null;
        }
        // Value must be a textual foreign-dataset reference whose dataset matches one of the
        // rule's Match_Datasets entries.
        String foreignRef = textValue(leaf.getValue());
        if (foreignRef == null)
        {
            return null;
        }
        int dot = foreignRef.indexOf('.');
        if (dot <= 0 || dot >= foreignRef.length() - 1)
        {
            return null;
        }
        String foreignDs = foreignRef.substring(0, dot);
        boolean matchesJoin = false;
        for (CanonMatch m : canon)
        {
            if (m.dsName().equalsIgnoreCase(foreignDs))
            {
                matchesJoin = true;
                break;
            }
        }
        if (!matchesJoin)
        {
            return null;
        }
        if (OperandSubstitutor.hasPlaceholder(foreignRef))
        {
            return null;
        }
        String foreignCol = foreignRef.substring(dot + 1);
        if (foreignCol.contains(".") || foreignCol.startsWith("$"))
        {
            return null;
        }

        return new CohortKey(CohortKind.EQUALITY, canon, op,
                Boolean.TRUE.equals(leaf.getTypeInsensitive()),
                foreignDs.toUpperCase(java.util.Locale.ROOT));
    }


    /**
     * Membership cohort eligibility — two-leaf
     * {@code all([non_empty(var), is_not_contained_by(var, [literal terms])])}. Each rule has its
     * own primary column and its own term list; the cohort key only encodes the kind + operator
     * pair, so different (column, terms) combos cluster as long as the shape matches.
     */
    private static @Nullable CohortKey membershipCohortKey(Rule rule)
    {
        // Membership rules don't use Match_Datasets — they're pure per-row, primary-table only.
        if (rule.getMatchDatasets() != null && !rule.getMatchDatasets().isEmpty())
        {
            return null;
        }
        if (!(rule.getCheck() instanceof CheckConditionAll all) || all.getConditions().size() != 2)
        {
            return null;
        }
        CheckCondition c1 = all.getConditions().get(0);
        CheckCondition c2 = all.getConditions().get(1);
        if (!(c1 instanceof CheckConditionLeaf leaf1) || !(c2 instanceof CheckConditionLeaf leaf2))
        {
            return null;
        }
        // Leaf #1 must be non_empty, no modifiers, no value, on a plain primary column.
        if (!"non_empty".equals(leaf1.getOperator()))
        {
            return null;
        }
        if (hasActiveModifier(leaf1) || Boolean.TRUE.equals(leaf1.getValueIsLiteral())
                || Boolean.TRUE.equals(leaf1.getNegative()))
        {
            return null;
        }
        if (leaf1.getValue() != null && !leaf1.getValue().isNull())
        {
            return null;
        }
        if (!isPlainColumn(leaf1.getName()))
        {
            return null;
        }
        // Leaf #2 must be is_not_contained_by, no modifiers, value an array of textual terms.
        if (!"is_not_contained_by".equals(leaf2.getOperator()))
        {
            return null;
        }
        if (hasActiveModifier(leaf2) || Boolean.TRUE.equals(leaf2.getValueIsLiteral())
                || Boolean.TRUE.equals(leaf2.getNegative()))
        {
            return null;
        }
        if (!isPlainColumn(leaf2.getName()))
        {
            return null;
        }
        // Both leaves reference the same column (both names non-null: isPlainColumn passed above).
        if (!Objects.equals(leaf1.getName(), leaf2.getName()))
        {
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode value = leaf2.getValue();
        if (value == null || !value.isArray() || value.isEmpty())
        {
            return null;
        }
        // Reject if any element is non-textual — the cohort runner uses the textual term list
        // verbatim (the same $-reference contract the compiled plan uses).
        for (com.fasterxml.jackson.databind.JsonNode element : value)
        {
            if (element == null || !element.isTextual())
            {
                return null;
            }
        }

        // Cohort key: kind + operator. Different (column, terms) combos cluster together; the
        // CohortRunner reads each member's column / term list at execution time.
        return new CohortKey(CohortKind.MEMBERSHIP, List.of(), "is_not_contained_by", false, "");
    }


    private static boolean hasActiveModifier(CheckConditionLeaf leaf)
    {
        return leaf.getRegex() != null || leaf.getPrefix() != null || leaf.getSuffix() != null
                || leaf.getOrdering() != null
                || (leaf.getWithin() != null && !leaf.getWithin().isNull());
    }


    /**
     * Returns {@code true} when the rule's primary leaf column exists on the given dataset meta.
     * Used by {@link #group(List, DataTableMeta)} to demote cohort-eligible rules whose primary
     * column is absent from the target dataset down to singletons. Both cohort kinds — equality
     * (single-leaf) and membership (two-leaf, both leaves on the same column) — read a single
     * primary-table column, so we just look at the first leaf and check its name. Returns
     * {@code true} when the rule's shape isn't recognised here so eligibility decisions made
     * elsewhere aren't second-guessed.
     */
    private static boolean primaryLeafColumnPresent(Rule rule, DataTableMeta meta)
    {
        CheckCondition check = rule != null ? rule.getCheck() : null;
        String name = null;
        // Equality / single-leaf path
        CheckConditionLeaf single = extractSingleLeaf(check);
        if (single != null)
        {
            name = single.getName();
        }
        // Membership / two-leaf path
        else if (check instanceof CheckConditionAll all && all.getConditions().size() == 2
                && all.getConditions().get(0) instanceof CheckConditionLeaf leaf1)
        {
            name = leaf1.getName();
        }
        if (name == null)
        {
            return true; // shape not recognised — leave eligibility decision to the caller
        }
        return meta.getColumnIndex(name) >= 0;
    }


    private static boolean isPlainColumn(@Nullable String name)
    {
        return name != null && !name.isEmpty() && !name.startsWith("$") && !name.contains(".")
                && !OperandSubstitutor.hasPlaceholder(name);
    }


    /**
     * Returns the single leaf from a Check tree if and only if the tree is exactly one leaf (either
     * a bare {@link CheckConditionLeaf} or a one-child All/Any wrapper). Returns {@code null} for
     * everything else.
     */
    static @Nullable CheckConditionLeaf extractSingleLeaf(@Nullable CheckCondition check)
    {
        if (check instanceof CheckConditionLeaf leaf)
        {
            return leaf;
        }
        if (check instanceof CheckConditionAll all && all.getConditions().size() == 1
                && all.getConditions().get(0) instanceof CheckConditionLeaf leaf)
        {
            return leaf;
        }
        if (check instanceof CheckConditionAny any && any.getConditions().size() == 1
                && any.getConditions().get(0) instanceof CheckConditionLeaf leaf)
        {
            return leaf;
        }
        return null;
    }


    private static @Nullable String textValue(
            com.fasterxml.jackson.databind.@Nullable JsonNode node)
    {
        if (node == null || node.isNull() || !node.isTextual())
        {
            return null;
        }
        String t = node.asText();
        return (t == null || t.isEmpty()) ? null : t;
    }

    /**
     * Cohort grouping key. Equal keys cluster.
     * <ul>
     * <li>For {@link CohortKind#EQUALITY}: {@code matchDatasets} carries the canonical join,
     * {@code operator} is the equality operator, {@code foreignDataset} is the joined-side dataset
     * name.</li>
     * <li>For {@link CohortKind#MEMBERSHIP}: {@code matchDatasets} is empty, {@code operator} is
     * {@code "is_not_contained_by"}, {@code foreignDataset} is the empty string. Different per-rule
     * {@code (column, terms)} combos all share this key — the {@link CohortRunner} reads each
     * member's leaves at execution time.</li>
     * </ul>
     */
    record CohortKey(CohortKind kind, List<CanonMatch> matchDatasets, String operator,
            boolean typeInsensitive, String foreignDataset)
    {

        CohortKey
        {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(matchDatasets, "matchDatasets");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(foreignDataset, "foreignDataset");
            matchDatasets = List.copyOf(matchDatasets);
        }
    }


    /** Canonicalised single Match_Datasets entry — only the fields that affect the join. */
    record CanonMatch(String dsName, List<String> keys)
    {

        CanonMatch
        {
            Objects.requireNonNull(dsName, "dsName");
            Objects.requireNonNull(keys, "keys");
            keys = List.copyOf(keys);
        }
    }
}
