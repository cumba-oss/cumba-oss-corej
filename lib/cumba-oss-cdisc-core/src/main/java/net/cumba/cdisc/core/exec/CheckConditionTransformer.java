package net.cumba.cdisc.core.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.List;
import java.util.Objects;
import lombok.CustomLog;
import net.cumba.cdisc.core.model.CheckCondition;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionAny;
import net.cumba.cdisc.core.model.CheckConditionConstant;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.CheckConditionNot;
import org.jspecify.annotations.Nullable;

/**
 * Transforms a {@link CheckCondition} tree by resolving {@code --} prefixed variable names to the
 * actual domain-prefixed names.
 * <p>
 * For example, when the domain prefix is {@code "AE"}, the name {@code "--STDTC"} is resolved to
 * {@code "AESTDTC"}. A dot-qualified {@code **} such as {@code "RELREC.**DECOD"} is deliberately
 * PRESERVED (Fix #5) for per-row resolution by {@code RelrecExpandedLookup}; only the dataset half
 * of such a reference is resolved here.
 * </p>
 */
@CustomLog
public final class CheckConditionTransformer
{

    private static final String WILDCARD = "--";

    private static final String DOUBLE_WILDCARD = "**";

    private CheckConditionTransformer()
    {
    }


    /**
     * Backwards-compatible overload — see {@link #resolvePrefixes(CheckCondition, String, String)}.
     * {@code ruleId} defaults to {@code null}; the malformed-prefix WARN renders with {@code [?]}
     * for the rule context.
     */
    public static CheckCondition resolvePrefixes(CheckCondition condition, String domainPrefix)
    {
        return resolvePrefixes(condition, domainPrefix, null);
    }


    /**
     * Resolves all {@code --} prefixed names in the condition tree.
     *
     * @param condition
     *            the condition tree to transform
     * @param domainPrefix
     *            the variable wildcard prefix (e.g. "AE"; "" for SUPP/SQ; the 2-character parent
     *            suffix for an AP dataset) — see {@code OperationExecutor.variableWildcardPrefix}
     * @param ruleId
     *            CORE id of the rule whose Check is being prepared, used as a leading
     *            {@code [<ruleId>]} prefix on the malformed-prefix WARN. {@code null} renders as
     *            {@code [?]}.
     * @return a new condition tree with all wildcards resolved
     */
    public static CheckCondition resolvePrefixes(CheckCondition condition, String domainPrefix,
            @Nullable String ruleId)
    {
        return resolvePrefixes(condition, domainPrefix, domainPrefix, ruleId);
    }


    /**
     * Two-prefix form (EC-36). {@code variablePrefix} substitutes {@code --} in every
     * <em>variable-name</em> position — leaf {@code name}, a plain {@code --}/{@code **} value, and
     * the column half of a dot-qualified reference. {@code domainCodePrefix} substitutes the
     * <em>dataset-name</em> half of a dot-qualified value ({@code SUPP--.QVAL}), which is a dataset
     * identity and must keep the full CDISC domain code: on an {@code APMH} primary the
     * supplemental dataset is {@code SUPPAPMH}, not {@code SUPPMH} — a different dataset that can
     * genuinely exist.
     *
     * @param condition
     *            the condition tree to transform
     * @param variablePrefix
     *            the variable wildcard prefix ("" for SUPP/SQ, the AP parent suffix for AP)
     * @param domainCodePrefix
     *            the CDISC domain code, for dataset-name wildcards; {@code null} falls back to
     *            {@code variablePrefix}
     * @param ruleId
     *            CORE id for diagnostics, or {@code null}
     * @return a new condition tree with all wildcards resolved
     */
    public static CheckCondition resolvePrefixes(CheckCondition condition, String variablePrefix,
            @Nullable String domainCodePrefix, @Nullable String ruleId)
    {
        String domainPrefix = variablePrefix;
        if (domainPrefix == null)
        {
            return condition;
        }
        // EC-36: callers pass the VARIABLE wildcard prefix, where "" (SUPP/SQ) and a 2-character AP
        // parent suffix are both correct. The old WARN fired on prefix.length() != 2 and would now
        // shout on exactly the cases this change fixed, so it is gated on a genuinely odd prefix:
        // longer than a domain code and not empty.
        if (domainPrefix.length() > 2 && containsWildcard(condition))
        {
            LOGGER.log(System.Logger.Level.DEBUG,
                    "[{0}] Domain prefix \"{1}\" is longer than a 2-character domain code; "
                            + "-- substitution may produce unexpected variable names",
                    ruleId != null ? ruleId : "?", domainPrefix);
        }
        return transform(condition, domainPrefix,
                domainCodePrefix != null ? domainCodePrefix : variablePrefix);
    }


    private static CheckCondition transform(CheckCondition condition, String prefix,
            String datasetPrefix)
    {
        return switch (condition)
        {
        case CheckConditionAll all -> new CheckConditionAll(
                transformList(all.getConditions(), prefix, datasetPrefix));
        case CheckConditionAny any -> new CheckConditionAny(
                transformList(any.getConditions(), prefix, datasetPrefix));
        case CheckConditionNot not -> new CheckConditionNot(
                transform(not.getCondition(), prefix, datasetPrefix));
        case CheckConditionLeaf leaf -> transformLeaf(leaf, prefix, datasetPrefix);
        case CheckConditionConstant c -> c;
        // Native-only expression: names are already resolved in the Expr — no prefix to apply.
        case net.cumba.cdisc.core.model.CheckConditionExpression e -> e;
        };
    }


    private static List<CheckCondition> transformList(List<CheckCondition> conditions,
            String prefix, String datasetPrefix)
    {
        return conditions.stream().map(c -> transform(c, prefix, datasetPrefix)).toList();
    }


    /**
     * Resolves {@code --} in a leaf's {@code name} and {@code value}.
     * <p>
     * ⚠ <b>{@code within} and {@code ordering} are copied verbatim, so a {@code --} in either is
     * NOT resolved here.</b> {@code within} is a grouping key (a column name, a list of them, or a
     * list of coalesce components — see {@link CheckConditionLeaf#getWithinColumns()}) and
     * {@code ordering} is a sort column; both are column references and both would need the same
     * substitution {@code name} gets. The authored corpus does use the shape:
     * {@code CDISC-SEND-0290} and {@code CDISC-SEND-0292} carry {@code within: ["--TESTCD"]} in
     * {@code rules-src/checks}. Left unresolved, the group key would name a column no dataset has,
     * and the grouped operator would degrade to the missing-key path instead of grouping by
     * {@code LBTESTCD} / {@code BWTESTCD} / … — a silent under-report, not an error.
     * </p>
     * <p>
     * <b>It is unreachable from the shipped corpus, and deliberately left unguarded.</b> Every
     * {@code Check} in {@code lib/corej-cdisc-rules/rules/} is in native expression form (measured
     * 2026-08-08: 14039 of 14039), and {@link #transform} returns a
     * {@code CheckConditionExpression} untouched — so no shipped rule ever reaches this method, and
     * the expression pipeline does its own wildcard expansion over the {@code within=} keyword
     * ({@code WildcardExpander}). The legacy leaf form with the wildcard survives only in
     * {@code rules-legacy/}, which is the fork-facing view and is not loaded by this engine. A
     * guard added here could therefore never be exercised through {@code RuleRunner} on the shipped
     * corpus; it would be pinned only by a synthetic fixture asserting the guard against itself.
     * The trap is recorded rather than fenced: <b>anything that re-introduces the legacy leaf path
     * — a corpus that stops lowering to expressions, or a caller building a
     * {@link CheckConditionLeaf} programmatically — must resolve these two fields here first.</b>
     * </p>
     */
    private static CheckConditionLeaf transformLeaf(CheckConditionLeaf leaf, String prefix,
            String datasetPrefix)
    {
        String newName = resolveWildcard(leaf.getName(), prefix);
        JsonNode newValue = resolveValueWildcard(leaf.getValue(), prefix, datasetPrefix);
        if (Objects.equals(newName, leaf.getName()) && Objects.equals(newValue, leaf.getValue()))
        {
            return leaf; // no change
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
                // ⚠⚠ NOTE: `includeEmpty` is NOT copied here and never has been. That is a
                // PRE-EXISTING gap with the same shape (Fix #121's include_empty is dropped by this
                // rebuild), left untouched deliberately because it is outside this change's scope —
                // it is reported, not silently fixed.
                .keepMissings(leaf.getKeepMissings())
                // EC-87: the next-record comparison relation has the same silent-loss shape.
                .relation(leaf.getRelation()).build();
    }


    /**
     * Resolves {@code "--"} in a variable name. {@code "--STDTC"} with prefix {@code "AE"} becomes
     * {@code "AESTDTC"}.
     */
    static @Nullable String resolveWildcard(@Nullable String name, String prefix)
    {
        if (name == null || !name.startsWith(WILDCARD))
        {
            return name;
        }
        return prefix + name.substring(WILDCARD.length());
    }


    /**
     * Resolves wildcards in the value field. Handles both {@code "--"} and {@code "**"} within
     * dot-qualified references.
     * <p>
     * Fix #5: For dot-qualified values whose {@code **} appears <em>after</em> the {@code "."}
     * (e.g. {@code "RELREC.**DECOD"}), the {@code **} is preserved so that the downstream
     * {@link RelrecExpandedLookup} can resolve it per-row against the paired parent domain — a
     * RELREC row that pairs FA↔AE uses {@code AEDECOD}, whereas FA↔CM uses {@code CMDECOD}. Pre-
     * resolving to a single domain here would collapse cross-domain relationships into one.
     * </p>
     * <p>
     * Plain {@code **}-prefixed values (no dot qualifier) are still substituted to the primary
     * prefix. {@code --} inside dot-qualified values (e.g. {@code "SUPP--.QNAM"}) is still resolved
     * because the SUPP dataset name is derived from the primary domain, not from a per-row RDOMAIN.
     * </p>
     */
    private static @Nullable JsonNode resolveValueWildcard(@Nullable JsonNode value, String prefix,
            String datasetPrefix)
    {
        if (value == null)
        {
            return value;
        }
        // Handle arrays of objects (e.g., target_is_not_sorted_by sort descriptors)
        if (value.isArray())
        {
            boolean changed = false;
            com.fasterxml.jackson.databind.node.ArrayNode arr = new com.fasterxml.jackson.databind.ObjectMapper()
                    .createArrayNode();
            for (JsonNode element : value)
            {
                if (element.isObject() && element.has("name"))
                {
                    String name = element.get("name").asText();
                    String resolved = resolveWildcard(name, prefix);
                    if (!Objects.equals(resolved, name))
                    {
                        com.fasterxml.jackson.databind.node.ObjectNode copy = element.deepCopy();
                        copy.put("name", resolved);
                        arr.add(copy);
                        changed = true;
                        continue;
                    }
                }
                else if (element.isTextual())
                {
                    String resolvedText = resolveTextWildcard(element.asText(), prefix,
                            datasetPrefix);
                    if (resolvedText != null)
                    {
                        arr.add(new TextNode(resolvedText));
                        changed = true;
                        continue;
                    }
                }
                arr.add(element);
            }
            return changed ? arr : value;
        }
        if (!value.isTextual())
        {
            return value;
        }
        String resolved = resolveTextWildcard(value.asText(), prefix, datasetPrefix);
        return resolved == null ? value : new TextNode(resolved);
    }


    /**
     * Resolves the wildcards in one textual operand, or returns {@code null} when nothing changes.
     * Shared by the scalar and array paths so the same string can never resolve two different ways
     * depending on whether it sits in {@code value} or inside {@code value[]} (EC-36 — the array
     * branch previously had its own, narrower copy that left dot-qualified and non-dot {@code **}
     * elements untouched).
     */
    private static @Nullable String resolveTextWildcard(String text, String prefix,
            String datasetPrefix)
    {
        // A dot-qualified reference has TWO independent halves: the dataset half is an identity and
        // keeps the CDISC domain code, the column half is a variable name. Resolving each on its
        // own prefix — rather than picking one and `replace`-ing the whole string — is what makes
        // `SUPP--.--QVAL` become `SUPPAPMH.MHQVAL` instead of `SUPPAPMH.APMHQVAL`.
        int dot = text.indexOf('.');
        if (dot >= 0 && (text.contains(WILDCARD) || text.contains(DOUBLE_WILDCARD)))
        {
            String dsHalf = text.substring(0, dot);
            String colHalf = text.substring(dot + 1);
            String newDs = dsHalf.replace(WILDCARD, datasetPrefix).replace(DOUBLE_WILDCARD,
                    datasetPrefix);
            // Fix #5: a `**` in the COLUMN half is deferred to RelrecExpandedLookup, which resolves
            // it per row against the RELREC-paired parent — a rule-prep substitution would collapse
            // cross-domain relationships into one. A `--` there is an ordinary variable name.
            String newCol = colHalf.contains(DOUBLE_WILDCARD) ? colHalf
                    : colHalf.replace(WILDCARD, prefix);
            return dsHalf.equals(newDs) && colHalf.equals(newCol) ? null : newDs + "." + newCol;
        }
        if (text.contains(DOUBLE_WILDCARD))
        {
            return text.replace(DOUBLE_WILDCARD, prefix);
        }
        if (text.startsWith(WILDCARD))
        {
            return prefix + text.substring(WILDCARD.length());
        }
        return null;
    }


    private static boolean containsWildcard(CheckCondition condition)
    {
        return switch (condition)
        {
        case CheckConditionAll all -> all.getConditions().stream()
                .anyMatch(CheckConditionTransformer::containsWildcard);
        case CheckConditionAny any -> any.getConditions().stream()
                .anyMatch(CheckConditionTransformer::containsWildcard);
        case CheckConditionNot not -> containsWildcard(not.getCondition());
        case CheckConditionLeaf leaf -> (leaf.getName() != null
                && leaf.getName().startsWith(WILDCARD))
                || (leaf.getValue() != null && leaf.getValue().isTextual()
                        && (leaf.getValue().asText().contains(DOUBLE_WILDCARD)
                                || leaf.getValue().asText().contains(WILDCARD)));
        case CheckConditionConstant _ -> false;
        case net.cumba.cdisc.core.model.CheckConditionExpression _ -> false;
        };
    }

}
