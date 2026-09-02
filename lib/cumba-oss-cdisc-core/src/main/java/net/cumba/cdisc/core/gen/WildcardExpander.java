package net.cumba.cdisc.core.gen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.CustomLog;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.exec.ScopeVariableEntry;
import net.cumba.cdisc.core.expr.ExpressionPrinter;
import net.cumba.cdisc.core.expr.OperandClassifier;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.model.CheckCondition;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionAny;
import net.cumba.cdisc.core.model.CheckConditionConstant;
import net.cumba.cdisc.core.model.CheckConditionExpression;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.CheckConditionNot;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.cdisc.core.model.Outcome;
import net.cumba.cdisc.core.model.OutputVariableToken;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RuleCore;
import net.cumba.datatable.DataTableMeta;
import org.jspecify.annotations.Nullable;

/**
 * Expands template rules containing wildcard variable names into concrete rules by matching against
 * actual dataset columns.
 * <p>
 * Wildcard markers in variable names (lowercase characters in otherwise uppercase CDISC names):
 * <ul>
 * <li>{@code *} (prefix) — any characters (root), regex {@code (.+)}</li>
 * <li>{@code xx} — zero-padded 2-digit index, regex {@code (\d{2})}</li>
 * <li>{@code zz} — zero-padded 2-digit index, regex {@code (\d{2})}</li>
 * <li>{@code y} — non-zero-padded integer index, regex {@code (\d+)}</li>
 * <li>{@code w} — single digit index, regex {@code (\d)}</li>
 * </ul>
 * A name is a wildcard if it starts with {@code *} or contains any lowercase letter. Since CDISC
 * variable names are always uppercase, there is no ambiguity.
 * <p>
 * Expansion works on both the legacy operator-leaf Check model and a <strong>native
 * expression</strong> Check ({@code CheckConditionExpression}). For a native expression, every bare
 * wildcard column reference ({@code TRTxxPN}, {@code *FL}, {@code *GRyN}, …) is expanded wherever
 * it occurs — exists-family operands ({@code var_exists(TRTxxPN)}), boolean/value function
 * arguments ({@code not empty(*FN)}), group-operator name operands
 * ({@code has_multiple_values_for(TRTxxPN,
 * TRTxxP)}, {@code is_unique_relationship(...)}), comparison / membership operands
 * ({@code *FN not in [0, 1]}), and {@code group=}/{@code within=} list keywords. A wildcard
 * <em>string literal</em> is expanded only as an exists-family name operand
 * ({@code var_exists("TRTxxPN")}, {@link #EXISTS_NAME_CALLS}); value-position strings and regex
 * literals are never expanded, and the engine's own {@code ${*}} membership and {@code --}-prefix
 * domain wildcards are out of scope ({@link #isWildcard} excludes them). Production loads the
 * native {@code rules/} corpus, so without this path the shipped wildcard rules would test
 * literally-named columns ({@code TRTxxPN}, {@code *FN}, …) that never exist.
 */
@CustomLog
public final class WildcardExpander
{

    private WildcardExpander()
    {
    }

    /**
     * Outcome of {@link #tryExpand(Rule, DataTableMeta)}. Tells the caller whether the rule carries
     * real wildcard tokens that need column-name expansion, and if so whether any dataset columns
     * matched. Distinguishing these three states lets {@code RuleGenerator} route each rule to
     * exactly one downstream pipeline (run as concrete rule, run the expansions, or skip with an
     * audit row) — replacing the previous heuristic-based filters that conflated "looks like a
     * wildcard" with "is a column-name template".
     */
    public sealed interface ExpansionResult
            permits
            ExpansionResult.Expanded,
            ExpansionResult.NotApplicable,
            ExpansionResult.NoMatch
    {

        /**
         * Rule was a template and produced one or more concrete expanded rules (the dataset has
         * columns matching every wildcard pattern). The caller adds {@code rules} directly to the
         * executed set; the source rule itself is not separately executed.
         */
        record Expanded(List<Rule> rules) implements ExpansionResult
        {
        }


        /**
         * Rule has no real wildcard tokens in its Check (parsing showed every lowercase run was an
         * "unknown marker → literal"). The caller treats the rule as a normal concrete rule and
         * runs it as-is. This is the path for the literal-mixed-case false positives the legacy
         * heuristic flagged (e.g. CORE-000115's {@code "Screen Failure"}, {@code "Char"}, regex
         * literals).
         */
        record NotApplicable() implements ExpansionResult
        {
        }


        /**
         * Rule has real wildcards but no dataset column matches. The caller drops the rule with an
         * audit reason; running the un-expanded template would silently produce zero violations on
         * a non-existent column reference.
         */
        record NoMatch(String reason) implements ExpansionResult
        {
        }
    }

    /**
     * Single source-of-truth wildcard classifier. Walks the rule's Check tree, parses each
     * candidate name into a {@link WildcardPattern}, and decides:
     * <ul>
     * <li>If no candidate has any real wildcard tokens (every lowercase run parsed as an "unknown
     * marker → literal"), returns {@link ExpansionResult.NotApplicable}.</li>
     * <li>Otherwise calls {@link #expand(Rule, DataTableMeta)}; non-empty result is wrapped as
     * {@link ExpansionResult.Expanded}, empty result becomes {@link ExpansionResult.NoMatch}.</li>
     * </ul>
     * Replaces the legacy {@code containsWildcards} filter predicate: callers no longer guess
     * whether a rule is a template, they ask the expander directly and act on the returned variant.
     */
    public static ExpansionResult tryExpand(Rule rule, DataTableMeta meta)
    {
        // ⚑ Plan C §3.3: a marker in ANY declared level makes the rule a template — the
        // substitution below rewrites every level, so the detection must span every level too, or
        // a weaker level would keep its unresolved token as a literal column name.
        if (rule.getCheck() == null
                || rule.checkConditions().stream().noneMatch(WildcardExpander::hasRealWildcards))
        {
            return new ExpansionResult.NotApplicable();
        }
        List<Rule> expanded = expand(rule, meta);
        if (expanded.isEmpty())
        {
            return new ExpansionResult.NoMatch("Template did not match any columns in dataset");
        }
        return new ExpansionResult.Expanded(expanded);
    }


    /**
     * Fix #147 — the expansion entry point that can also read data <em>outside</em> the dataset
     * under validation. A rule carrying an {@code Expansion:} block is routed to
     * {@link TokenExpander}; every other rule takes the two-arg path unchanged.
     *
     * <p>
     * The two mechanisms are deliberately separate walks over the same rule and are rejected in
     * combination at load ({@code RulePackageLoader.validateExpansionDirectives}). They differ in
     * kind, not degree: the engine-owned {@code xx}/{@code y}/{@code zz}/{@code w} markers are
     * fixed, match <em>inside</em> a name and read only this dataset's columns, whereas a declared
     * token is an exact string whose values may come from anywhere {@link TokenExpander.Context}
     * reaches.
     * </p>
     *
     * @param rule
     *            the rule to expand
     * @param meta
     *            the metadata of the dataset under validation
     * @param ctx
     *            the foreign-data inputs a declared-token source may read
     * @return the expansion outcome
     */
    public static ExpansionResult tryExpand(Rule rule, DataTableMeta meta,
            TokenExpander.Context ctx)
    {
        if (rule.getCheck() != null && rule.getExpansion() != null
                && !rule.getExpansion().isEmpty())
        {
            return TokenExpander.tryExpand(rule, meta, ctx);
        }
        return tryExpand(rule, meta);
    }


    /**
     * Whether {@code check} carries at least one <b>real</b> engine-owned wildcard marker — i.e. a
     * candidate name whose lowercase runs parse to an actual capture group ({@code xx}, {@code zz},
     * {@code y}, {@code w}) or that starts with {@code *}. False for the literal mixed-case names
     * the loose {@link #isWildcard} heuristic flags ({@code "Char"}, {@code "Screen Failure"},
     * {@code "TRTyyP"}).
     *
     * @param check
     *            the Check tree to inspect
     * @return whether any name is a real column-name template
     */
    public static boolean hasRealWildcards(CheckCondition check)
    {
        for (String candidate : collectWildcardNames(check))
        {
            if (!WildcardPattern.parse(candidate).groupNames().isEmpty())
            {
                return true;
            }
        }
        return false;
    }


    /**
     * Returns {@code true} if the rule's Check tree contains any wildcard variable names that
     * should be expanded — checks both leaf {@code name} and leaf {@code value} positions.
     * <p>
     * Note: this uses the loose {@link #isWildcard} heuristic which can flag literal mixed- case
     * strings (e.g. {@code "Char"}, {@code "Screen Failure"}) as wildcards. Prefer
     * {@link #tryExpand(Rule, DataTableMeta)} when the goal is to decide whether a rule is a
     * column-name template — that method runs the parser and only treats real wildcard tokens
     * ({@code *}, {@code xx}, {@code zz}, {@code y}, {@code w}) as wildcards.
     */
    public static boolean containsWildcards(Rule rule)
    {
        if (rule.getCheck() == null)
        {
            return false;
        }
        Set<String> wildcards = collectWildcardNames(rule);
        return !wildcards.isEmpty();
    }


    /**
     * Compiled column-matching regex for a {@code Requirements.Variables} entry written with the
     * wildcard markers ({@code xx}, {@code zz}, {@code y}, {@code w}, or a {@code *} root prefix),
     * or {@code null} when the entry carries no real marker (a concrete uppercase name, or a
     * mixed-case literal whose lowercase runs are all "unknown marker → literal", e.g.
     * {@code "Char"} or {@code "TRTyyP"} — {@code yy} is not a marker). The regex is the same
     * case-sensitive anchored pattern {@link #expand} matches against the dataset columns, so the
     * scope gate and the Check-side expansion agree on which concrete columns satisfy a template.
     * Used by {@link net.cumba.cdisc.core.exec.ScopeMatcher} to give wildcard scope entries
     * at-least-one-column matching semantics — without it the gate tests the marker text literally
     * ({@code TRTxxP}) and skips the rule even when a concrete column ({@code TRT01P}) exists.
     *
     * @param entry
     *            the {@code Requirements.Variables} All/Any/None entry to inspect
     * @return the compiled anchored pattern, or {@code null} when the entry is a literal
     */
    public static @Nullable Pattern scopeVariableWildcardPattern(String entry)
    {
        if (!isWildcard(entry))
        {
            return null;
        }
        WildcardPattern pat = WildcardPattern.parse(entry);
        return pat.groupNames().isEmpty() ? null : pat.regex();
    }


    /**
     * Expands a template rule into concrete rules by matching wildcard names against the dataset's
     * column names.
     *
     * @param templateRule
     *            the template rule with wildcard names in its Check
     * @param meta
     *            the dataset metadata to match columns against
     * @return list of expanded rules (may be empty if no columns match)
     */
    public static List<Rule> expand(Rule templateRule, DataTableMeta meta)
    {
        Set<String> wildcardNames = collectWildcardNames(templateRule);
        if (wildcardNames.isEmpty())
        {
            return List.of();
        }

        // Collect all column names from the dataset
        List<String> columns = new ArrayList<>(meta.getColumnCount());
        for (int i = 0; i < meta.getColumnCount(); i++)
        {
            columns.add(meta.getColumn(i).getName());
        }

        // Build regex patterns for each wildcard name.
        Map<String, WildcardPattern> patterns = new LinkedHashMap<>();
        for (String wc : wildcardNames)
        {
            patterns.put(wc, WildcardPattern.parse(wc));
        }

        // Fix #84 (Group B / B4) — empty-suffix pairing setup. A bare "*" leaf parses to
        // ^(.+)$ and would seed one candidate tuple per column (unbounded explosion). It is
        // allowed ONLY when co-anchored by a sibling "*N"/"*C" leaf sharing the root; the tuples
        // are then seeded from the ANCHORED side (Step 3 skips the bare-* pattern). A bare "*"
        // with no "*N"/"*C" anchor is rejected here (guarded) so it can never explode.
        //
        // The bare-* machinery keys off a "*" in a NAME (target-variable) position only. A literal
        // "*" appearing in a leaf VALUE / RHS position (e.g. the pre-existing
        // `library_variable_label does_not_contain "*"` leaves of CDISC-AD0018/0708/0709 and
        // PMDA-AD0018) is NOT a bare-* target and must neither trigger the guard/WARNING nor change
        // the seed set — those rules expand exactly as they did before Fix #84.
        boolean hasBareStar = templateRule.checkConditions().stream()
                .anyMatch(WildcardExpander::hasNamePositionBareStar);
        if (hasBareStar)
        {
            boolean hasPairAnchor = patterns.values().stream()
                    .anyMatch(p -> "*N".equals(p.original()) || "*C".equals(p.original()));
            if (!hasPairAnchor)
            {
                String id = templateRule.effectiveId();
                LOGGER.log(System.Logger.Level.WARNING, "Wildcard template " + id
                        + " has a bare '*' leaf with no '*N'/'*C' anchor"
                        + " sibling — refusing to expand (would seed one tuple per column)");
                return List.of();
            }
        }

        // Fix #84 — anchored-secondary filters. wildcardExclude drops secondary columns matching
        // any exclusion pattern (blacklist, AD0376/AD1011); wildcardPairCatalogue keeps only
        // catalogued CDISC-standard secondaries (whitelist, AD1012A). Both apply to the non-bare
        // (anchored *N/*C) patterns only — the bare "*" primary resolves per tuple and is never
        // filtered.
        List<Pattern> excludePatterns = new ArrayList<>();
        List<String> excludeNames = templateRule.getWildcardExclude();
        if (excludeNames != null)
        {
            for (String ex : excludeNames)
            {
                if (ex != null && !ex.isBlank())
                {
                    excludePatterns.add(WildcardPattern.parse(ex).regex());
                }
            }
        }
        boolean useCatalogue = Boolean.TRUE.equals(templateRule.getWildcardPairCatalogue());

        // Fix #23 — mixed-group support. Earlier the expander required every
        // wildcard in one rule to share the same capture-group list and bailed
        // when a rule mixed e.g. {@code TRTxxP} ({@code [xx]}) with
        // {@code TRxxPGy} ({@code [xx, y]}). The new algorithm:
        // 1. Collects per-pattern matches as instances (column, captured
        // group→value).
        // 2. Identifies the union of all group names across all patterns.
        // 3. Seeds candidate tuples (full assignments over the union) from
        // patterns whose own group set equals the union — those are the
        // only patterns whose individual matches directly determine a
        // full tuple. (Same-group multi-wildcard rules: every pattern is
        // a seed; mixed-group rules: the wider patterns seed.)
        // 4. For each candidate tuple, computes each pattern's concrete
        // column by either looking up an instance whose partial capture
        // agrees with the tuple, or — when none — calling
        // {@link WildcardPattern#buildConcreteName} with the tuple's
        // values projected onto the pattern's own group list. The latter
        // handles {@code not_exists} leaves whose target column doesn't
        // exist in the dataset but should still be checked.
        // 5. Falls back to a cross-join across all anchored patterns when
        // no single pattern covers the union (disjoint-group rules — not
        // exercised by shipped rules today, but kept for completeness).

        // Step 1 — per-pattern instances.
        Map<String, net.cumba.cdisc.core.model.WildcardFilter> filters = templateRule
                .getWildcards();
        Map<String, List<Map.Entry<String, Map<String, String>>>> instances = new LinkedHashMap<>();
        for (var entry : patterns.entrySet())
        {
            String wcName = entry.getKey();
            WildcardPattern pat = entry.getValue();
            // Fix #84 — the anchored-secondary filters apply to non-bare patterns only. The bare
            // "*" primary must keep matching every column so it can resolve to the primary of an
            // anchored tuple; it never seeds (Step 3), so it can't explode.
            boolean isBareStar = "*".equals(pat.original());
            List<Map.Entry<String, Map<String, String>>> list = new ArrayList<>();
            for (String col : columns)
            {
                Matcher m = pat.regex().matcher(col);
                if (m.matches())
                {
                    // Fix #84 — drop excluded / non-catalogued secondary columns before they can
                    // seed a tuple.
                    if (!isBareStar && isExcludedSecondary(col, excludePatterns, useCatalogue))
                    {
                        continue;
                    }
                    Map<String, String> captured = new LinkedHashMap<>();
                    for (int g = 0; g < pat.groupNames().size(); g++)
                    {
                        captured.put(pat.groupNames().get(g), m.group(g + 1));
                    }
                    // Fix #24: drop instances whose captured group values fall outside the
                    // rule's wildcards filter. Numeric markers (xx, zz, y, w) parse as
                    // integers; the * group (root) is never numeric and so is never filtered.
                    if (filters != null && !filters.isEmpty()
                            && !satisfiesFilters(captured, filters))
                    {
                        continue;
                    }
                    list.add(Map.entry(col, captured));
                }
            }
            instances.put(wcName, list);
        }
        boolean anyAnchor = false;
        for (List<?> list : instances.values())
        {
            if (!list.isEmpty())
            {
                anyAnchor = true;
                break;
            }
        }
        if (!anyAnchor)
        {
            return List.of();
        }

        // Step 2 — union of all group names.
        Set<String> unionGroups = new LinkedHashSet<>();
        for (WildcardPattern pat : patterns.values())
        {
            unionGroups.addAll(pat.groupNames());
        }

        // Step 3 — seed candidate tuples from patterns whose own group set
        // equals the union. Each such instance is itself a full assignment
        // over the union groups. Dedupe (different patterns may produce the
        // same group-value assignment); preserve the first-seen order so the
        // output is stable.
        List<Map<String, String>> candidateTuples = new ArrayList<>();
        Set<Map<String, String>> seenTuples = new LinkedHashSet<>();
        for (var entry : patterns.entrySet())
        {
            WildcardPattern pat = entry.getValue();
            // Fix #84 — never seed candidate tuples from a NAME-position bare "*" leaf (^(.+)$
            // matches every column). It is resolved per tuple in Step 4 to the primary of an
            // anchored (*N/*C) pairing; the anchored patterns do the seeding. Gated on
            // hasBareStar so a value-position literal "*" (not a bare-* target) still seeds
            // exactly as before Fix #84.
            if (hasBareStar && "*".equals(pat.original()))
            {
                continue;
            }
            if (!new LinkedHashSet<>(pat.groupNames()).equals(unionGroups))
            {
                continue;
            }
            var entryInstances = instances.get(entry.getKey());
            if (entryInstances == null)
            {
                continue;
            }
            for (var inst : entryInstances)
            {
                Map<String, String> captured = inst.getValue();
                if (seenTuples.add(captured))
                {
                    candidateTuples.add(captured);
                }
            }
        }

        // Step 5 (fallback) — if no full-group pattern is anchored, cross-join
        // anchored partial-group patterns on shared groups to assemble full
        // tuples. Drops any partial assignment that doesn't cover every union
        // group after the join. No shipped rule needs this branch today.
        if (candidateTuples.isEmpty())
        {
            List<Map<String, String>> joined = new ArrayList<>();
            joined.add(new LinkedHashMap<>());
            for (var entry : patterns.entrySet())
            {
                // Fix #84 — a NAME-position bare "*" leaf never participates in seeding (here or in
                // Step 3); it matches every column and would explode the cross-join. Gated on
                // hasBareStar so a value-position literal "*" still cross-joins as before Fix #84.
                if (hasBareStar && "*".equals(entry.getValue().original()))
                {
                    continue;
                }
                var patInstances = instances.get(entry.getKey());
                if (patInstances == null || patInstances.isEmpty())
                {
                    continue;
                }
                List<Map<String, String>> next = new ArrayList<>();
                for (Map<String, String> existing : joined)
                {
                    for (var inst : patInstances)
                    {
                        Map<String, String> partial = inst.getValue();
                        boolean consistent = true;
                        for (var e : partial.entrySet())
                        {
                            String existingVal = existing.get(e.getKey());
                            if (existingVal != null && !existingVal.equals(e.getValue()))
                            {
                                consistent = false;
                                break;
                            }
                        }
                        if (!consistent)
                        {
                            continue;
                        }
                        Map<String, String> merged = new LinkedHashMap<>(existing);
                        merged.putAll(partial);
                        next.add(merged);
                    }
                }
                joined = next;
            }
            for (Map<String, String> tup : joined)
            {
                if (tup.keySet().containsAll(unionGroups) && seenTuples.add(tup))
                {
                    candidateTuples.add(tup);
                }
            }
        }

        // Step 4 — for each candidate tuple, resolve every pattern's column.
        List<Rule> expanded = new ArrayList<>();
        for (Map<String, String> tuple : candidateTuples)
        {
            Map<String, String> allConcreteNames = new LinkedHashMap<>();
            for (var entry : patterns.entrySet())
            {
                String wcName = entry.getKey();
                WildcardPattern pat = entry.getValue();
                String matchedCol = null;
                var wcInstances = instances.get(wcName);
                if (wcInstances == null)
                {
                    continue;
                }
                for (var inst : wcInstances)
                {
                    Map<String, String> partial = inst.getValue();
                    boolean fits = true;
                    for (var e : partial.entrySet())
                    {
                        if (!e.getValue().equals(tuple.get(e.getKey())))
                        {
                            fits = false;
                            break;
                        }
                    }
                    if (fits)
                    {
                        matchedCol = inst.getKey();
                        break;
                    }
                }
                if (matchedCol != null)
                {
                    allConcreteNames.put(wcName, matchedCol);
                    continue;
                }
                String concreteName = concreteFromTuple(pat, tuple);
                if (concreteName != null)
                {
                    allConcreteNames.put(wcName, concreteName);
                }
            }
            if (allConcreteNames.isEmpty())
            {
                continue;
            }
            Rule expandedRule = expandRule(templateRule, allConcreteNames, tuple);
            // P5 (PLAN-native-engine-full-coverage): an expansion is a fresh concrete Rule that
            // bypassed RulePackageLoader — give it the loader's per-leaf compiled state and the
            // SAME single native-retention decision, so it executes on the native engine exactly
            // like a loader-loaded concrete rule. Identical concrete expansions share one compiled
            // program in the NativeExprEvaluator cache (Expr records compare structurally).
            RulePackageLoader.installNativeExpr(expandedRule);
            // EC-37: an expanded rule gets the same effective-Output_Variables derivation as a
            // loader-loaded one, computed on the post-expansion concrete Check.
            RulePackageLoader.deriveOutputVariables(expandedRule);
            expanded.add(expandedRule);
        }

        return expanded;
    }


    /**
     * Fix #24: returns the set of capture-group tokens (e.g. {@code "xx"}, {@code "y"},
     * {@code "*"}) used by any wildcard name in {@code condition}. Used by rule-load validation to
     * verify that a rule's {@code wildcards} filter keys reference real groups.
     */
    public static Set<String> collectAvailableCaptureGroups(CheckCondition condition)
    {
        Set<String> groups = new LinkedHashSet<>();
        for (String wc : collectWildcardNames(condition))
        {
            groups.addAll(WildcardPattern.parse(wc).groupNames());
        }
        return groups;
    }


    /**
     * Fix #24: returns {@code true} when every entry in the rule's {@code wildcards} filter map
     * accepts the captured value of the corresponding group. Capture values that don't parse as
     * integers are silently passed through (the {@code *} root group is never numeric; only
     * {@code xx}, {@code zz}, {@code y}, {@code w} would carry a digit string).
     */
    private static boolean satisfiesFilters(Map<String, String> captured,
            Map<String, net.cumba.cdisc.core.model.WildcardFilter> filters)
    {
        for (var entry : filters.entrySet())
        {
            net.cumba.cdisc.core.model.WildcardFilter filter = entry.getValue();
            if (filter == null)
            {
                continue;
            }
            String captureValue = captured.get(entry.getKey());
            if (captureValue == null)
            {
                // Filter targets a group that isn't in this pattern's captures.
                // Defensive — rule-load validation should have rejected this; if it slips
                // through, leave the instance accepted to avoid silently dropping rows.
                continue;
            }
            int numeric;
            try
            {
                numeric = Integer.parseInt(captureValue);
            }
            catch (NumberFormatException _)
            {
                // Non-numeric capture (would be the * root) — bypass the filter.
                continue;
            }
            if (!filter.accepts(numeric))
            {
                return false;
            }
        }
        return true;
    }


    /**
     * Fix #84 (Group B / B4): returns {@code true} when an anchored secondary column ({@code *N} /
     * {@code *C} match) must be dropped before it can seed a pairing tuple — either because it
     * matches a {@code wildcardExclude} pattern (blacklist, AD0376 / AD1011) or because
     * {@code wildcardPairCatalogue} is active and the column is not a catalogued CDISC-standard
     * secondary (whitelist, AD1012A). Only ever called for non-bare patterns.
     *
     * @param col
     *            the concrete secondary column name
     * @param excludePatterns
     *            compiled {@code wildcardExclude} regexes (may be empty)
     * @param useCatalogue
     *            {@code true} when {@code wildcardPairCatalogue} is set
     * @return {@code true} if the column must not seed a tuple
     */
    private static boolean isExcludedSecondary(String col, List<Pattern> excludePatterns,
            boolean useCatalogue)
    {
        for (Pattern ex : excludePatterns)
        {
            if (ex.matcher(col).matches())
            {
                return true;
            }
        }
        return useCatalogue && !WildcardPairCatalogue.isCataloguedSecondary(col);
    }


    /**
     * Fix #84 (Group B / B4): returns {@code true} when a bare {@code "*"} wildcard appears in a
     * NAME (target-variable) position of the Check tree — a leaf {@code name}, a native column
     * {@link Expr.Ref}, or an exists-family string-literal name operand — as opposed to a leaf
     * {@code value} / comparison RHS / membership-list position. Only a name-position bare
     * {@code *} engages the empty-suffix pairing machinery (guard, seed-skip, fallback-skip); a
     * value-position literal {@code "*"} (e.g. the pre-existing
     * {@code library_variable_label does_not_contain "*"} leaves of CDISC-AD0018 / 0708 / 0709 and
     * PMDA-AD0018) must not.
     */
    private static boolean hasNamePositionBareStar(@Nullable CheckCondition condition)
    {
        return switch (condition)
        {
        case CheckConditionAll all -> all.getConditions().stream()
                .anyMatch(WildcardExpander::hasNamePositionBareStar);
        case CheckConditionAny any -> any.getConditions().stream()
                .anyMatch(WildcardExpander::hasNamePositionBareStar);
        case CheckConditionNot not -> hasNamePositionBareStar(not.getCondition());
        case CheckConditionLeaf leaf -> "*".equals(leaf.getName());
        case CheckConditionExpression expr -> exprHasNamePositionBareStar(expr.expr());
        case null -> false;
        case CheckConditionConstant _ -> false;
        };
    }


    /**
     * Native-expression analogue of {@link #hasNamePositionBareStar}: a column {@link Expr.Ref} or
     * an exists-family string-literal name operand equal to {@code "*"}. Value-position literals
     * (comparison RHS, membership / list-literal elements) are never counted.
     */
    private static boolean exprHasNamePositionBareStar(Expr e)
    {
        return switch (e)
        {
        case Expr.And a -> a.parts().stream()
                .anyMatch(WildcardExpander::exprHasNamePositionBareStar);
        case Expr.Or o -> o.parts().stream()
                .anyMatch(WildcardExpander::exprHasNamePositionBareStar);
        case Expr.Not n -> exprHasNamePositionBareStar(n.inner());
        case Expr.Binary b -> exprHasNamePositionBareStar(b.left())
                || exprHasNamePositionBareStar(b.right());
        case Expr.Call c -> (EXISTS_NAME_CALLS.contains(c.name()) && !c.args().isEmpty()
                && c.args().get(0) instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.STRING
                && "*".equals(lit.value()))
                || c.args().stream().anyMatch(WildcardExpander::exprHasNamePositionBareStar)
                || c.kwargs().values().stream()
                        .anyMatch(WildcardExpander::exprHasNamePositionBareStar);
        case Expr.Ref r -> "*".equals(r.name());
        case Expr.Lit _ -> false;
        };
    }

    // ---- Wildcard name collection ----


    public static Set<String> collectWildcardNames(@Nullable CheckCondition condition)
    {
        Set<String> names = new LinkedHashSet<>();
        collectWildcardNamesRecursive(condition, names);
        return names;
    }


    /**
     * The wildcard names of <b>every</b> declared check level of {@code rule}, in ladder order
     * (Plan C &#167;3.3).
     *
     * <p>
     * The expansion substitutes over every level, so it must see every level's markers: a template
     * whose weaker level names {@code AyLO} while its strictest does not would otherwise expand the
     * strictest and ship the token verbatim in the other.
     * </p>
     *
     * @param rule
     *            the template rule
     * @return the union of the levels' wildcard names
     */
    public static Set<String> collectWildcardNames(Rule rule)
    {
        Set<String> names = new LinkedHashSet<>();
        for (CheckCondition condition : rule.checkConditions())
        {
            collectWildcardNamesRecursive(condition, names);
        }
        return names;
    }


    private static void collectWildcardNamesRecursive(@Nullable CheckCondition condition,
            Set<String> names)
    {
        switch (condition)
        {
        case CheckConditionAll all -> all.getConditions()
                .forEach(c -> collectWildcardNamesRecursive(c, names));
        case CheckConditionAny any -> any.getConditions()
                .forEach(c -> collectWildcardNamesRecursive(c, names));
        case CheckConditionNot not -> collectWildcardNamesRecursive(not.getCondition(), names);
        case CheckConditionLeaf leaf ->
        {
            String leafName = leaf.getName();
            if (leafName != null && isWildcard(leafName))
            {
                names.add(leafName);
            }
            // Also check value field for wildcard references (unless literal)
            if (!Boolean.TRUE.equals(leaf.getValueIsLiteral()) && leaf.getValue() != null)
            {
                if (leaf.getValue().isTextual())
                {
                    String valText = leaf.getValue().asText();
                    if (isWildcard(valText))
                    {
                        names.add(valText);
                    }
                }
                else if (leaf.getValue().isArray())
                {
                    for (JsonNode element : leaf.getValue())
                    {
                        if (element.isTextual() && isWildcard(element.asText()))
                        {
                            names.add(element.asText());
                        }
                    }
                }
            }
        }
        case null ->
        {
            // null tree — nothing to collect
        }
        case CheckConditionConstant _ ->
        {
            // constants have no wildcard names to collect
        }
        case CheckConditionExpression expr -> collectWildcardNamesFromExpr(expr.expr(), names);
        }
    }

    /**
     * The exists-family callables whose first positional argument is a NAME operand that may be
     * written as a <em>string literal</em> ({@code var_exists("TRTxxPN")}). A bare-reference
     * wildcard is expanded wherever it appears (a {@code WILDCARD_COLUMN} ref is unambiguously a
     * column), so this allowlist exists only to safely expand a quoted-literal name without ever
     * expanding a value-position string (e.g. the {@code "*"} in
     * {@code contains(var_label("LIBRARY"), "*")}).
     */
    private static final Set<String> EXISTS_NAME_CALLS = Set.of("ds_exists", "ds_not_exists",
            "var_exists", "var_not_exists");

    /**
     * Collects wildcard names from a native expression tree — the {@link Expr} analogue of
     * {@link #collectWildcardNamesRecursive}. A bare {@link Expr.Ref} that {@link #isWildcard} (a
     * column-name template such as {@code TRTxxPN}, {@code *FL}, {@code *GRyN}) is collected
     * wherever it occurs — comparison operands, function arguments, group-operator name operands,
     * membership operands, and list / keyword positions. The engine's own {@code ${*}} membership
     * wildcards and {@code --}-prefix domain wildcards are excluded by {@link #isWildcard} (no
     * lowercase / not a bare {@code *}). A wildcard <em>string literal</em> is collected only as
     * the name operand of an exists-family call ({@link #EXISTS_NAME_CALLS}), never in a value
     * position.
     *
     * @param e
     *            the expression node to walk
     * @param names
     *            the accumulating wildcard-name set
     */
    private static void collectWildcardNamesFromExpr(Expr e, Set<String> names)
    {
        switch (e)
        {
        case Expr.And a -> a.parts().forEach(p -> collectWildcardNamesFromExpr(p, names));
        case Expr.Or o -> o.parts().forEach(p -> collectWildcardNamesFromExpr(p, names));
        case Expr.Not n -> collectWildcardNamesFromExpr(n.inner(), names);
        case Expr.Binary b ->
        {
            collectWildcardNamesFromExpr(b.left(), names);
            collectWildcardNamesFromExpr(b.right(), names);
        }
        case Expr.Call c ->
        {
            if (EXISTS_NAME_CALLS.contains(c.name()) && !c.args().isEmpty()
                    && c.args().get(0) instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.STRING
                    && isWildcard((String) lit.value()))
            {
                names.add((String) lit.value());
            }
            c.args().forEach(a -> collectWildcardNamesFromExpr(a, names));
            c.kwargs().values().forEach(a -> collectWildcardNamesFromExpr(a, names));
        }
        case Expr.Ref r ->
        {
            if (isWildcard(r.name()))
            {
                names.add(r.name());
            }
        }
        case Expr.Lit l ->
        {
            // Recurse into a list literal's elements (e.g. wildcard refs inside a group=/within=
            // list); scalar string/regex/number/bool value literals are never expanded.
            if (l.kind() == Expr.LitKind.LIST)
            {
                listElements(l).forEach(el -> collectWildcardNamesFromExpr(el, names));
            }
        }
        }
    }


    @SuppressWarnings("unchecked")
    private static List<Expr> listElements(Expr.Lit listLit)
    {
        return (List<Expr>) listLit.value();
    }


    /**
     * Returns {@code true} if the name contains wildcard markers. A name is a wildcard if it starts
     * with {@code *} or contains any lowercase letter. Engine metadata names (variable_name, etc.)
     * contain underscores and lowercase but are not wildcards — they never start with an uppercase
     * letter or {@code *}.
     */
    static boolean isWildcard(String name)
    {
        if (name == null || name.isEmpty())
        {
            return false;
        }
        if (name.startsWith("*"))
        {
            return true;
        }
        // Engine metadata/operation names start with lowercase or $
        if (name.charAt(0) == '$' || Character.isLowerCase(name.charAt(0)))
        {
            return false;
        }
        // CDISC variable names are uppercase. Any lowercase = wildcard marker.
        for (int i = 0; i < name.length(); i++)
        {
            if (Character.isLowerCase(name.charAt(i)))
            {
                return true;
            }
        }
        return false;
    }

    // ---- Pattern parsing ----

    /**
     * Parsed wildcard pattern: the original name, a compiled regex, and the ordered list of capture
     * group names (*, xx, zz, y, w).
     *
     * <p>
     * A fourth {@code literals} component used to track the literal segments between the capture
     * groups. Nothing ever read it — the accessor had no caller anywhere in the reactor — so it was
     * removed along with the bookkeeping that maintained it; the compiled {@code regex} is the only
     * product of that walk.
     * </p>
     */
    record WildcardPattern(String original, Pattern regex, List<String> groupNames)
    {

        /**
         * Parses a wildcard name into a regex pattern.
         * <p>
         * The name is scanned left to right. Uppercase characters, digits, and underscores are
         * literal. Lowercase sequences and {@code *} are replaced with capture groups.
         */
        static WildcardPattern parse(String name)
        {
            StringBuilder regex = new StringBuilder("^");
            List<String> groups = new ArrayList<>();
            int i = 0;

            if (name.startsWith("*"))
            {
                regex.append("(.+)");
                groups.add("*");
                i = 1;
            }

            while (i < name.length())
            {
                char c = name.charAt(i);
                if (Character.isLowerCase(c))
                {
                    // Collect the full lowercase sequence
                    int start = i;
                    while (i < name.length() && Character.isLowerCase(name.charAt(i)))
                    {
                        i++;
                    }
                    String marker = name.substring(start, i);
                    switch (marker)
                    {
                    case "xx", "zz" ->
                    {
                        regex.append("(\\d{2})");
                        groups.add(marker);
                    }
                    case "y" ->
                    {
                        regex.append("(\\d+)");
                        groups.add("y");
                    }
                    case "w" ->
                    {
                        regex.append("(\\d)");
                        groups.add("w");
                    }
                    default ->
                    {
                        // Unknown lowercase sequence — treat as literal
                        // (should not happen with well-formed ADaM wildcards)
                        regex.append(Pattern.quote(marker));
                    }
                    }
                }
                else
                {
                    regex.append(Pattern.quote(String.valueOf(c)));
                    i++;
                }
            }
            regex.append("$");
            return new WildcardPattern(name, Pattern.compile(regex.toString()), groups);
        }


        /**
         * Reconstructs a concrete variable name from a tuple key. The tuple key is the capture
         * group values joined by {@code '\0'}.
         */
        @Nullable
        String buildConcreteName(String tupleKey)
        {
            String[] values = tupleKey.split("\0", -1);
            if (values.length != groupNames.size())
            {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            int groupIdx = 0;
            int i = 0;

            if (original.startsWith("*"))
            {
                sb.append(values[groupIdx++]);
                i = 1;
            }

            while (i < original.length())
            {
                char c = original.charAt(i);
                if (Character.isLowerCase(c))
                {
                    int start = i;
                    while (i < original.length() && Character.isLowerCase(original.charAt(i)))
                    {
                        i++;
                    }
                    String marker = original.substring(start, i);
                    if ("xx".equals(marker) || "zz".equals(marker) || "y".equals(marker)
                            || "w".equals(marker))
                    {
                        if (groupIdx < values.length)
                        {
                            sb.append(values[groupIdx++]);
                        }
                    }
                    else
                    {
                        sb.append(marker);
                    }
                }
                else
                {
                    sb.append(c);
                    i++;
                }
            }
            return sb.toString();
        }
    }

    // ---- Rule expansion ----

    /**
     * The concrete column name {@code pat} takes under {@code tuple}, or {@code null} when the
     * tuple does not bind every capture group the pattern needs. Extracted from {@link #expand}'s
     * Step 4 so the Check-side expansion and the Fix #124 qualified-scope substitution
     * ({@link #substituteNameList}) derive names the same way and cannot drift apart.
     *
     * @param pat
     *            the parsed wildcard pattern
     * @param tuple
     *            the candidate tuple, group name → captured value
     * @return the concrete name, or {@code null} when the tuple is missing a needed group
     */
    private static @Nullable String concreteFromTuple(WildcardPattern pat,
            Map<String, String> tuple)
    {
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < pat.groupNames().size(); i++)
        {
            String g = pat.groupNames().get(i);
            String v = tuple.get(g);
            if (v == null)
            {
                return null;
            }
            if (i > 0)
            {
                key.append('\0');
            }
            key.append(v);
        }
        return pat.buildConcreteName(key.toString());
    }


    private static Rule expandRule(Rule template, Map<String, String> wildcardToColumn,
            Map<String, String> tuple)
    {
        // Determine the primary concrete name for the Core ID suffix
        String primaryCol = wildcardToColumn.values().iterator().next();

        String origCoreId = template.effectiveId();
        String expandedCoreId = origCoreId + "-" + primaryCol;

        // Fix #152 (engine-gap ADaM-G6) — the substitution map the whole rule body shares.
        //
        // `wildcardToColumn` is keyed by the templates the CHECK names, because that is the only
        // tree `collectWildcardNames` walks. An `Operations[]` entry names columns too, and its
        // template is routinely a DIFFERENT one: CDISC-AD0353/0354/0702/0703/0790 all pair a Check
        // over `ByIND` with `max("AyIND", filter=ABLFL="Y", group=[…])`. `AyIND` is not a key here,
        // so a whole-name lookup leaves it alone — which is exactly how those five shipped rules
        // came to read a column named literally "AyIND": absent ⇒ `max` yields nothing ⇒ the
        // `not_equal_to $baseline_ayind` comparison fired on every populated row. Same hole for the
        // `AyIND` entry in AD0790's `Output_Variables` and for the `AyIND` occurrences in every one
        // of the five Descriptions / Outcome messages.
        //
        // The fix binds those operation-side templates the way a qualified scope entry is bound
        // (Fix #124): derive the concrete name from THIS expansion tuple, so `y=1` maps `AyIND` and
        // `ByIND` onto `A1IND` / `B1IND` together and the two sides can never disagree. Folding
        // them into one map keeps a single substitution surface for the Check, the Scope, the
        // Output_Variables, the free text and the operations.
        Map<String, String> nameMap = new LinkedHashMap<>(wildcardToColumn);
        for (String opTemplate : operationNames(template.getOperations()))
        {
            if (nameMap.containsKey(opTemplate) || !isWildcard(opTemplate))
            {
                continue;
            }
            WildcardPattern pat = WildcardPattern.parse(opTemplate);
            if (pat.groupNames().isEmpty())
            {
                // A mixed-case literal the loose `isWildcard` heuristic flags but the parser finds
                // no marker in ("Char", "TRTyyP"). Never rewrite it.
                continue;
            }
            String concrete = concreteFromTuple(pat, tuple);
            if (concrete != null)
            {
                nameMap.put(opTemplate, concrete);
            }
        }

        // Whole-name map lookup: the engine-owned markers bind a complete template name to a
        // complete column name, so a name that is not a key is carried over untouched.
        java.util.function.UnaryOperator<String> rename = n -> nameMap.getOrDefault(n, n);

        // Transform the Check tree. expandRule is only reached for templates that carry a
        // wildcard-bearing Check (collectWildcardNames walked it upstream), so getCheck is
        // non-null.
        CheckCondition expandedCheck = substituteNames(
                Objects.requireNonNull(template.getCheck(), "wildcard template has no Check"),
                rename);

        // Expand Outcome
        String message = template.getOutcome() != null ? template.getOutcome().getMessage()
                : origCoreId;
        List<String> outputVars = template.getOutcome() != null
                ? template.getOutcome().getOutputVariables()
                : null;
        if (outputVars != null)
        {
            // Fix #356: rename the NAME inside the token (see TokenExpander) — a raw `!AyLO` is
            // not a key of the whole-name map and would survive unresolved.
            outputVars = outputVars.stream().map(v -> OutputVariableToken.mapName(v, rename))
                    .toList();
        }

        // Expand description
        String desc = template.getDescription() != null
                ? substituteInText(template.getDescription(), nameMap)
                : expandedCoreId;

        // Build the expanded rule
        Rule rule = new Rule();
        rule.setId(deterministicUuid(expandedCoreId));

        RuleCore core = new RuleCore();
        core.setId(expandedCoreId);
        core.setStatus("Generated");
        core.setVersion("1");
        rule.setCore(core);

        rule.setDescription(desc);
        rule.setVariableUniverse(template.getVariableUniverse());
        rule.setSensitivity(template.getSensitivity());
        // ⚠⚠ Plan C: carry Severity onto every expanded child. This method builds the child field
        // by field from a fresh `new Rule()`, so an unnamed top-level field is SILENTLY DROPPED —
        // invisible to the loader, the schemas and the writer, because the TEMPLATE still has it.
        // Measured when this line was missing: 15 `--`-wildcard rules / 944 finding rows reported
        // ERROR while their template was authored Warning.
        rule.setSeverity(template.getSeverity());
        rule.setCheck(expandedCheck);
        // ⚠⚠ Plan C: the level-keyed Check is rebuilt with the SAME rename, for the same reason
        // Severity is copied above — a top-level field this method does not name is silently
        // dropped from every expanded child, and the drop is invisible to the loader, the schemas
        // and the writer because the TEMPLATE still carries it. A level left un-substituted would
        // ship the template's wildcard tokens (`AyLO`) as literal column names.
        rule.setCheckLevels(net.cumba.cdisc.core.model.LevelCheck
                .mapConditions(template.getCheckLevels(), c -> substituteNames(c, rename)));

        Outcome outcome = new Outcome();
        outcome.setMessage(substituteInText(message, nameMap));
        if (outputVars != null)
        {
            outcome.setOutputVariables(outputVars);
        }
        rule.setOutcome(outcome);

        // Scope carries over BY REFERENCE: since PLAN-scope-requirements-split phase 5 every
        // remaining Scope axis (Classes, Domains, Datasets, Use_Case, Data_Structures, Subclasses)
        // is a closed vocabulary or a dataset-name pattern, none of which can hold a wildcard
        // token. The wildcard substitution that used to live here moved with the field it served,
        // into expandRequirements — otherwise the concrete rule keeps the template's literal
        // wildcard tokens (e.g. "AyLO") and the RuleRunner requirement gate skips it, since
        // ScopeMatcher matches those tokens literally, never as wildcards.
        rule.setScope(template.getScope());
        rule.setRequirements(expandRequirements(template.getRequirements(), nameMap, tuple));
        rule.setOperations(renameOperationNames(template.getOperations(), rename));
        rule.setMatchDatasets(template.getMatchDatasets());
        rule.setGroupingVariables(template.getGroupingVariables());
        rule.setGrouping(template.getGrouping());
        // The expanded rule inherits the template's Check, so its Sensitivity is derivable from the
        // rule body like any other (PLAN-derive-rule-type-sensitivity phase 7). The old blanket
        // `Sensitivity.RECORD` fallback predates the classifier and was wrong for every template
        // whose Check yields a single dataset-level verdict. Derivation runs after setCheck because
        // it reads the expanded tree, not the template's.
        RulePackageLoader.deriveOmittedFields(rule);

        return rule;
    }


    /**
     * Every <em>column-position</em> string of {@code operations}, in encounter order.
     * <p>
     * Derived by running {@link #renameOperationNames} with a recording identity operator rather
     * than by a second field walk, so the collector and the rewriter cannot drift apart — the same
     * "name in, name out" contract {@link #substituteNames} has for the Check tree. Non-column
     * positions ({@code id}, {@code subtract}, {@code domain}, {@code operator}, filter
     * <em>values</em>, …) are not visited and therefore never reported.
     * </p>
     *
     * @param operations
     *            the template's operations, possibly {@code null}
     * @return the column-position names, never {@code null}
     */
    private static Set<String> operationNames(@Nullable List<Operation> operations)
    {
        if (operations == null || operations.isEmpty())
        {
            return Set.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        renameOperationNames(operations, n ->
        {
            seen.add(n);
            return n;
        });
        return seen;
    }


    /**
     * Returns {@code operations} with {@code rename} applied to every column-position string.
     * <p>
     * Fix #152 (engine-gap ADaM-G6). Before it, {@code expandRule} handed the template's operations
     * to the expanded rule verbatim, so an operation naming a wildcard template
     * ({@code max("AyIND", …)}) survived expansion pointing at a column that does not exist. This
     * is the third hand-written field-by-field {@link Operation} copy in the engine, alongside
     * {@code OperationExecutor.resolvePrefixes} and {@code OperationExecutor.expandGroupRefs}; all
     * three are guarded reflectively by {@code OperationFieldRegistrationTest}, because a field
     * this routine forgets is silently dropped from every expanded rule.
     * </p>
     * <p>
     * The column positions are {@code name}, {@code names}, {@code group}, {@code reference},
     * {@code ordering}, {@code offset}, {@code minuend_match},
     * {@code external_dictionary_term_variable}, {@code dictionary_parent},
     * {@code qualifying_any_populated} and the <b>keys</b> of {@code filter}. Everything else is a
     * literal, a dataset name, an operator name or a {@code $}-reference and is copied unchanged —
     * in particular {@code id}, {@code subtract} and the {@code minus} {@code value} list, which
     * name operation results rather than columns, and the filter <em>values</em>, which are data.
     * </p>
     * <p>
     * Returns the argument itself when {@code rename} changed nothing, so the overwhelmingly common
     * no-operation-template case keeps sharing the template's list exactly as before.
     * </p>
     *
     * @param operations
     *            the template's operations, possibly {@code null}
     * @param rename
     *            the name rewriter; must return its argument unchanged when there is nothing to do
     * @return the rewritten operations ({@code null} in, {@code null} out)
     */
    static @Nullable List<Operation> renameOperationNames(@Nullable List<Operation> operations,
            java.util.function.UnaryOperator<String> rename)
    {
        if (operations == null || operations.isEmpty())
        {
            return operations;
        }
        List<Operation> copies = operations.stream().map(op -> renameOperation(op, rename))
                .toList();
        // Operation is a Lombok @Data, so equals() covers every field: an all-identity rewrite is
        // detected structurally and the shared template list is handed back untouched.
        return copies.equals(operations) ? operations : copies;
    }


    /** Single-operation core of {@link #renameOperationNames}. */
    private static Operation renameOperation(Operation op,
            java.util.function.UnaryOperator<String> rename)
    {
        Operation copy = new Operation();
        // --- column positions: rewritten ---
        copy.setName(renameOne(op.getName(), rename));
        copy.setNames(renameEach(op.getNames(), rename));
        copy.setGroup(renameEach(op.getGroup(), rename));
        copy.setReference(renameOne(op.getReference(), rename));
        copy.setOrdering(renameOne(op.getOrdering(), rename));
        copy.setOffset(renameOne(op.getOffset(), rename));
        copy.setMinuendMatch(renameEach(op.getMinuendMatch(), rename));
        copy.setExternalDictionaryTermVariable(
                renameOne(op.getExternalDictionaryTermVariable(), rename));
        copy.setDictionaryParent(renameOne(op.getDictionaryParent(), rename));
        copy.setQualifyingAnyPopulated(renameEach(op.getQualifyingAnyPopulated(), rename));
        copy.setFilter(renameFilterKeys(op.getFilter(), rename));
        // --- everything else: copied verbatim ---
        copy.setId(op.getId());
        copy.setOperator(op.getOperator());
        copy.setExpression(op.getExpression());
        copy.setSubtract(op.getSubtract());
        copy.setValue(op.getValue());
        copy.setDomain(op.getDomain());
        copy.setDelimiter(op.getDelimiter());
        copy.setReferenceExtreme(op.getReferenceExtreme());
        copy.setMissingValues(op.getMissingValues());
        copy.setKeepMissings(op.getKeepMissings());
        copy.setMinuendDomain(op.getMinuendDomain());
        copy.setCodelists(op.getCodelists());
        copy.setLevel(op.getLevel());
        copy.setReturntype(op.getReturntype());
        copy.setKeyName(op.getKeyName());
        copy.setKeyValue(op.getKeyValue());
        copy.setModelClass(op.getModelClass());
        copy.setCtAttribute(op.getCtAttribute());
        copy.setVersion(op.getVersion());
        copy.setCtPackageTypes(op.getCtPackageTypes());
        copy.setRegex(op.getRegex());
        copy.setNamePattern(op.getNamePattern());
        copy.setValueIsReference(op.getValueIsReference());
        copy.setMinLength(op.getMinLength());
        copy.setExternalDictionaryType(op.getExternalDictionaryType());
        copy.setDictionaryTermType(op.getDictionaryTermType());
        copy.setCaseSensitive(op.getCaseSensitive());
        copy.setOriginalName(op.getOriginalName());
        return copy;
    }


    private static @Nullable String renameOne(@Nullable String name,
            java.util.function.UnaryOperator<String> rename)
    {
        return name == null ? null : rename.apply(name);
    }


    private static @Nullable List<String> renameEach(@Nullable List<String> names,
            java.util.function.UnaryOperator<String> rename)
    {
        return names == null ? null
                : names.stream().map(n -> n == null ? null : rename.apply(n)).toList();
    }


    /**
     * Rewrites the <b>keys</b> of a row-filter map; the values are data literals and are carried
     * over untouched. Mirrors {@code OperationExecutor.resolveFilterKeys} (EC-28(b) / Fix #131),
     * which established that a filter key is a column position.
     */
    private static @Nullable Map<String, Object> renameFilterKeys(@Nullable Map<String, Object> f,
            java.util.function.UnaryOperator<String> rename)
    {
        if (f == null)
        {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : f.entrySet())
        {
            out.put(e.getKey() == null ? null : rename.apply(e.getKey()), e.getValue());
        }
        return out;
    }


    /**
     * Returns a concrete copy of {@code template}'s {@code Requirements} with the wildcard tokens
     * in {@code Variables.All} / {@code .Any} / {@code .None} substituted per
     * {@code wildcardToColumn}. It is the successor of the retired {@code expandScope}, and
     * load-bearing for the same reason: an unexpanded token in a requirement is matched literally
     * by a gate that runs before expansion, so the expanded rule is skipped for every dataset.
     *
     * <p>
     * Returns the template block as-is when there is nothing to substitute, so a requirement with
     * no variable facet keeps the shared reference. ⚠ <b>Two-branch hazard:</b> every field
     * survives the identity return BY IDENTITY, so a field added to
     * {@link net.cumba.cdisc.core.model.Requirements} without a copy line below is dropped only on
     * the OTHER branch — a test that drives one branch passes while the bug ships.
     * {@code WildcardExpanderScopeCompletenessTest} drives both.
     * </p>
     */
    private static net.cumba.cdisc.core.model.@Nullable Requirements expandRequirements(
            net.cumba.cdisc.core.model.@Nullable Requirements template,
            Map<String, String> wildcardToColumn, Map<String, String> tuple)
    {
        if (template == null)
        {
            return null;
        }
        net.cumba.cdisc.core.model.VariableRequirement variables = template.getVariables();
        if (variables == null || (variables.getAll() == null && variables.getAny() == null
                && variables.getNone() == null))
        {
            return template;
        }
        net.cumba.cdisc.core.model.Requirements copy = new net.cumba.cdisc.core.model.Requirements();
        copy.setDatasets(template.getDatasets());
        copy.setLibrary(template.getLibrary());
        copy.setDefine(template.getDefine());
        copy.setDictionary(template.getDictionary());
        net.cumba.cdisc.core.model.VariableRequirement expanded = new net.cumba.cdisc.core.model.VariableRequirement();
        expanded.setAll(substituteNameList(variables.getAll(), wildcardToColumn, tuple));
        expanded.setAny(substituteNameList(variables.getAny(), wildcardToColumn, tuple));
        expanded.setNone(substituteNameList(variables.getNone(), wildcardToColumn, tuple));
        copy.setVariables(expanded);
        return copy;
    }


    /**
     * Maps each entry through {@code wildcardToColumn} (identity for non-wildcard entries).
     * <p>
     * Fix #124 — a <b>qualified</b> entry ({@code ADSL.TRTxxPN}) is split first, and only its
     * variable half is substituted, because the whole entry can never be a key of
     * {@code wildcardToColumn} (which is keyed by the Check's template names). Two levels of
     * substitution are tried, in order:
     * </p>
     * <ol>
     * <li>the Check uses the very same template token ⇒ reuse its concrete column, so both sides
     * agree exactly;</li>
     * <li>otherwise derive the name from this tuple via {@link #concreteFromTuple} — the case that
     * actually matters: a Check over {@code TRTxxP} paired with a scope entry {@code ADSL.TRTxxPN}
     * must bind the <em>same</em> {@code xx}, so the {@code xx=01} expansion requires
     * {@code ADSL.TRT01PN} and is not satisfied by {@code ADSL.TRT02PN}.</li>
     * </ol>
     * <p>
     * When the tuple binds none of the groups the entry needs, the entry is left as a template and
     * falls through to {@code ScopeMatcher}'s at-least-one marker matching. Unqualified entries
     * keep the exact pre-Fix-#124 behaviour (whole-entry map lookup only) — extending the
     * tuple-derived form to them would change how the ~883 rules already carrying a variable scope
     * are expanded.
     * </p>
     */
    private static @Nullable List<String> substituteNameList(@Nullable List<String> names,
            Map<String, String> wildcardToColumn, Map<String, String> tuple)
    {
        if (names == null)
        {
            return null;
        }
        return names.stream().map(n -> substituteScopeEntry(n, wildcardToColumn, tuple)).toList();
    }


    /**
     * Single-entry core of {@link #substituteNameList}.
     * <p>
     * Review H1: the tuple-derived fallback must mirror {@code ScopeMatcher.scopeEntryPattern}'s
     * <b>precedence</b> — there, a glob / {@code /…/} regex half
     * ({@code ScopeMatcher.scopePattern}) wins over the wildcard-marker reading. Running
     * {@link WildcardPattern#parse} on such a half instead would capture the lowercase runs that
     * occur inside globs and regexes by construction and rewrite them: {@code DM.*DY} would
     * collapse to the literal {@code DM.AESTDY} (losing the glob), and {@code DM./^\w+DTC$/} would
     * become {@code DM./^\3+DTC$/} — an invalid regex that throws at match time, since an expansion
     * bypasses {@code RulePackageLoader} and is never re-validated. Both halves are load-clean
     * shapes, so this is a supported-entry bug, not an authoring one.
     * </p>
     * <p>
     * The whole-entry map lookup still applies to a pattern half: that mirrors what the unqualified
     * path does with the same token and means "the Check bound this very template to a concrete
     * column".
     * </p>
     */
    private static String substituteScopeEntry(String entry, Map<String, String> wildcardToColumn,
            Map<String, String> tuple)
    {
        ScopeVariableEntry parsed = ScopeVariableEntry.parse(entry);
        if (!parsed.isQualified())
        {
            return wildcardToColumn.getOrDefault(entry, entry);
        }
        String variable = parsed.variable();
        String substituted = wildcardToColumn.get(variable);
        if (substituted == null && !isScopePatternHalf(variable))
        {
            substituted = concreteFromTuple(WildcardPattern.parse(variable), tuple);
        }
        return substituted == null ? entry : parsed.qualifier() + "." + substituted;
    }


    /**
     * Whether the variable half of a qualified scope entry is a glob / {@code /…/} regex, i.e. a
     * shape {@code ScopeMatcher.scopeEntryPattern} resolves ahead of the wildcard markers. A
     * malformed regex cannot reach here (the loader compiles every entry and tags a load error),
     * but the compile is guarded anyway so expansion can never throw.
     */
    private static boolean isScopePatternHalf(String variable)
    {
        try
        {
            return net.cumba.cdisc.core.exec.ScopeMatcher.scopePattern(variable) != null;
        }
        catch (PatternSyntaxException _)
        {
            // Unreachable for a loaded rule; treat as a pattern so we never rewrite it.
            return true;
        }
    }


    /**
     * Rewrites every name-position string of a Check tree through {@code rename}, leaving
     * value-position scalar literals alone. Both expansion mechanisms share this walk: the
     * engine-owned wildcard markers pass a whole-name map lookup, a declared {@code Expansion:}
     * token passes a substring substitution. The walk itself is deliberately ignorant of which —
     * "name in, name out" is the whole contract.
     *
     * @param condition
     *            the Check tree to rewrite
     * @param rename
     *            the name rewriter; must return its argument unchanged when there is nothing to do
     * @return a fresh, rewritten tree
     */
    static CheckCondition substituteNames(CheckCondition condition,
            java.util.function.UnaryOperator<String> rename)
    {
        return switch (condition)
        {
        case CheckConditionAll all -> new CheckConditionAll(
                all.getConditions().stream().map(c -> substituteNames(c, rename)).toList());
        case CheckConditionAny any -> new CheckConditionAny(
                any.getConditions().stream().map(c -> substituteNames(c, rename)).toList());
        case CheckConditionNot not -> new CheckConditionNot(
                substituteNames(not.getCondition(), rename));
        case CheckConditionLeaf leaf -> substituteLeaf(leaf, rename);
        case CheckConditionConstant c -> c;
        case CheckConditionExpression e ->
        {
            Expr substituted = substituteExpr(e.expr(), rename);
            yield new CheckConditionExpression(substituted, ExpressionPrinter.print(substituted));
        }
        };
    }


    /**
     * Rewrites the wildcards of a native expression tree to their concrete columns for one
     * expansion tuple, mirroring {@link #collectWildcardNamesFromExpr}: every wildcard bare
     * {@link Expr.Ref} is rewritten wherever it occurs (and re-classified so a former
     * {@code WILDCARD_COLUMN} becomes a plain {@code COLUMN}); an exists-family string-literal name
     * operand is rewritten to a concrete string literal; list-literal elements are rewritten
     * recursively; every other node is structurally copied unchanged so value-position scalar
     * literals are never substituted.
     *
     * @param e
     *            the expression node to rewrite
     * @param rename
     *            the name rewriter for one expansion binding
     * @return the rewritten expression (a fresh tree)
     */
    private static Expr substituteExpr(Expr e, java.util.function.UnaryOperator<String> rename)
    {
        return switch (e)
        {
        case Expr.And a -> new Expr.And(
                a.parts().stream().map(p -> substituteExpr(p, rename)).toList());
        case Expr.Or o -> new Expr.Or(
                o.parts().stream().map(p -> substituteExpr(p, rename)).toList());
        case Expr.Not n -> new Expr.Not(substituteExpr(n.inner(), rename));
        case Expr.Binary b -> new Expr.Binary(b.op(), substituteExpr(b.left(), rename),
                substituteExpr(b.right(), rename));
        case Expr.Call c -> substituteCall(c, rename);
        case Expr.Ref r -> substituteRef(r, rename);
        case Expr.Lit l -> substituteLit(l, rename);
        };
    }


    /**
     * Rewrites a template bare ref to its concrete column (re-classified from the concrete text, so
     * a former {@code WILDCARD_COLUMN} becomes {@code COLUMN} and a substituted {@code ADSL.&VAR}
     * becomes a {@code DOTTED_REF}); else unchanged.
     */
    private static Expr substituteRef(Expr.Ref r, java.util.function.UnaryOperator<String> rename)
    {
        String concrete = rename.apply(r.name());
        if (concrete != null && !concrete.equals(r.name()))
        {
            return new Expr.Ref(concrete, OperandClassifier.classify(concrete, -1));
        }
        return r;
    }


    /** Recurses into a list literal's elements; a scalar value literal is returned unchanged. */
    private static Expr substituteLit(Expr.Lit l, java.util.function.UnaryOperator<String> rename)
    {
        if (l.kind() != Expr.LitKind.LIST)
        {
            return l;
        }
        return new Expr.Lit(Expr.LitKind.LIST,
                listElements(l).stream().map(el -> substituteExpr(el, rename)).toList());
    }


    /**
     * Rewrites a call: an exists-family string-literal name operand (arg&nbsp;0) becomes a concrete
     * string literal; every other arg and every keyword value is rewritten via the generic walk (so
     * bare wildcard refs anywhere — including group-operator name operands and {@code group=}/
     * {@code within=} list kwargs — are expanded).
     */
    private static Expr substituteCall(Expr.Call c, java.util.function.UnaryOperator<String> rename)
    {
        List<Expr> newArgs = new ArrayList<>(c.args().size());
        for (int i = 0; i < c.args().size(); i++)
        {
            Expr arg = c.args().get(i);
            if (i == 0 && EXISTS_NAME_CALLS.contains(c.name()) && arg instanceof Expr.Lit lit
                    && lit.kind() == Expr.LitKind.STRING)
            {
                String original = (String) lit.value();
                String concrete = rename.apply(original);
                newArgs.add(concrete != null && !concrete.equals(original)
                        ? new Expr.Lit(Expr.LitKind.STRING, concrete)
                        : arg);
            }
            else
            {
                newArgs.add(substituteExpr(arg, rename));
            }
        }
        Map<String, Expr> newKwargs = c.kwargs();
        if (!newKwargs.isEmpty())
        {
            Map<String, Expr> rebuilt = new LinkedHashMap<>();
            for (Map.Entry<String, Expr> kw : newKwargs.entrySet())
            {
                rebuilt.put(kw.getKey(), substituteExpr(kw.getValue(), rename));
            }
            newKwargs = rebuilt;
        }
        return new Expr.Call(c.name(), newArgs, newKwargs);
    }


    // identity check intentional — substituteLeafValue returns the same node when nothing changed
    @SuppressWarnings("ReferenceEquality")
    private static CheckConditionLeaf substituteLeaf(CheckConditionLeaf leaf,
            java.util.function.UnaryOperator<String> rename)
    {
        String originalName = leaf.getName();
        String newName = originalName != null ? rename.apply(originalName) : null;

        JsonNode newValue = substituteLeafValue(leaf, rename);

        if (Objects.equals(newName, leaf.getName()) && newValue == leaf.getValue())
        {
            return leaf;
        }
        return CheckConditionLeaf.builder().name(newName).operator(leaf.getOperator())
                .value(newValue).valueIsLiteral(leaf.getValueIsLiteral())
                .valueIsReference(leaf.getValueIsReference())
                .typeInsensitive(leaf.getTypeInsensitive()).negative(leaf.getNegative())
                .regex(leaf.getRegex()).prefix(leaf.getPrefix()).suffix(leaf.getSuffix())
                .within(leaf.getWithin()).ordering(leaf.getOrdering())
                // ⚠ The grouping-key disposition must survive the rebuild, or a rule declaring
                // keep_missings silently loses it the moment its name needs resolving — the exact
                // silent-loss failure mode the parameter's validation exists to prevent.
                .keepMissings(leaf.getKeepMissings())
                // Fix #121's include_empty has exactly that shape and was dropped here until now:
                // an authored `include_empty` was silently lost the moment the leaf's name needed
                // resolving, so the expanded rule judged blanks differently from the rule its
                // author reviewed — invisible downstream, because the rule still ran and still
                // reported.
                .includeEmpty(leaf.getIncludeEmpty())
                // The composite tuple-membership target (T3) is the same shape again. A `names`
                // leaf carries a null `name`, so it only reaches this rebuild when its VALUE holds
                // a wildcard — and dropping `names` would leave the leaf with no target at all.
                .names(leaf.getNames())
                // EC-87: the next-record comparison relation has the same silent-loss shape.
                .relation(leaf.getRelation()).build();
    }


    /**
     * Substitutes wildcards inside the {@code value} of a check leaf when the value is not flagged
     * as a literal. Handles both textual and array shapes; returns the original node when nothing
     * changes.
     */
    private static @Nullable JsonNode substituteLeafValue(CheckConditionLeaf leaf,
            java.util.function.UnaryOperator<String> rename)
    {
        JsonNode newValue = leaf.getValue();
        if (Boolean.TRUE.equals(leaf.getValueIsLiteral()) || newValue == null)
        {
            return newValue;
        }
        if (newValue.isTextual())
        {
            String mapped = rename.apply(newValue.asText());
            if (mapped != null && !mapped.equals(newValue.asText()))
            {
                return new TextNode(mapped);
            }
            return newValue;
        }
        if (newValue.isArray())
        {
            return substituteLeafArrayValue(newValue, rename);
        }
        return newValue;
    }


    private static JsonNode substituteLeafArrayValue(JsonNode aArray,
            java.util.function.UnaryOperator<String> rename)
    {
        // Expand wildcards inside array elements
        boolean changed = false;
        var arr = new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode();
        for (JsonNode element : aArray)
        {
            if (element.isTextual())
            {
                String mapped = rename.apply(element.asText());
                if (mapped != null && !mapped.equals(element.asText()))
                {
                    arr.add(mapped);
                    changed = true;
                    continue;
                }
            }
            arr.add(element);
        }
        return changed ? arr : aArray;
    }


    /**
     * Substitutes template names inside free text (description / outcome message). Plain textual
     * replacement, shared by both expansion mechanisms.
     *
     * @param text
     *            the text to rewrite, possibly {@code null}
     * @param substitutions
     *            the template-name → concrete-text map, applied longest key first so a key that
     *            contains another cannot make the result order-dependent
     * @return the rewritten text, or {@code null} when {@code text} was {@code null}
     */
    static @Nullable String substituteInText(@Nullable String text,
            Map<String, String> substitutions)
    {
        if (text == null)
        {
            return null;
        }
        String result = text;
        for (var entry : substitutions.entrySet().stream().sorted(java.util.Comparator
                .comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed())
                .toList())
        {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }


    private static String deterministicUuid(String input)
    {
        return java.util.UUID
                .nameUUIDFromBytes(input.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
    }

}
