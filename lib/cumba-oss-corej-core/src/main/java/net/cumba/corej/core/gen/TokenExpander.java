package net.cumba.corej.core.gen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.CustomLog;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.exec.ScopeVariableSource;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.ExpansionDirective;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Operation;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.OutputVariableToken;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.datatable.DataTableMeta;
import org.jspecify.annotations.Nullable;

/**
 * Fix #147 — expands a template rule that carries an {@code Expansion:} block into one concrete
 * rule per token binding.
 *
 * <h2>What this is, and what it is not</h2>
 *
 * <p>
 * It is <b>not</b> a cross-dataset join. The join already ships: {@code CDISC-CG0218}
 * ({@code EPOCH != SE.EPOCH} after a {@code Match_Datasets} merge on {@code USUBJID}),
 * {@code PMDA-AD0208}, {@code CDISC-AD0053} / {@code PMDA-AD0258}. What a template expands
 * <em>into</em> is therefore an already-valid shipping shape needing no new operator, syntax or
 * join — only the <em>expansion source</em> is new.
 * </p>
 *
 * <h2>The two halves, and why they are kept apart</h2>
 *
 * <ol>
 * <li><b>Binding</b> — {@code over:} names a source ({@link net.cumba.corej.core.model
 * .ExpansionSource}) which yields the values a token takes. This is the extension point: a third
 * source must cost one new {@code over:} value and its resolver here, and nothing else.</li>
 * <li><b>Substitution</b> — token in, string out. It is handed a {@code token -> value} map and
 * rewrites the rule; it never learns how the values were derived. Every "how do I substitute an X"
 * question is answered once, for every present and future source.</li>
 * </ol>
 *
 * <h2>Silence is the failure mode this guards against</h2>
 *
 * <p>
 * {@code CDISC-AD0591} and {@code CDISC-AD0898} shipped for months loading cleanly, passing every
 * gate and checking nothing. So a source that cannot be read never expands to zero rules quietly:
 * it returns {@link WildcardExpander.ExpansionResult.NoMatch} with a stated reason, which
 * {@code RuleGenerator} turns into a SKIPPED audit row. The same discipline applies at the far end
 * — a token surviving into a resolved rule means substitution failed, so that rule is dropped with
 * a reason rather than evaluated against a column named {@code &VAR}.
 * </p>
 */
@CustomLog
public final class TokenExpander
{

    /** Shared, thread-safe mapper for the structural (Match_Datasets / Operations) rewrite. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TokenExpander()
    {
    }

    /**
     * Everything a binding source may read. Deliberately a single value object rather than a
     * growing parameter list: a new source declares its need here and every call site keeps
     * compiling.
     *
     * @param foreign
     *            the foreign-dataset metadata source built at {@code RuleGenerator}'s own call site
     *            (Fix #124), or {@code null} when this generator has no inventory-capable resolver
     *            — in which case "the dataset is absent" and "this resolver is blind" are
     *            indistinguishable, so a source that needs it must skip rather than guess
     * @param provider
     *            the run's metadata provider; {@code getStandardDatasetNames()} is what
     *            {@code known_domain_only} consults (an ADaM run wraps its provider in
     *            {@code CompanionDomainsProvider} so that accessor answers with the SDTM domain
     *            list)
     * @param primaryName
     *            the name of the dataset under validation, used to drop a self-referential
     *            expansion
     */
    public record Context(@Nullable ScopeVariableSource foreign,
            @Nullable MetadataProvider provider, @Nullable String primaryName)
    {
    }


    /**
     * One token bound to one value, plus the dataset column that produced it — the column, not the
     * value, names the expansion, so {@code CDISC-AD0898} over {@code AESEQ} becomes
     * {@code CDISC-AD0898-AESEQ} rather than {@code CDISC-AD0898-AE}.
     */
    private record Binding(String token, String value, String idSuffix)
    {
    }

    /**
     * Expands {@code rule} against {@code meta}, or explains why it cannot.
     *
     * @param rule
     *            the template rule; must carry a non-empty {@code Expansion:} block
     * @param meta
     *            the metadata of the dataset under validation
     * @param ctx
     *            the binding sources' inputs
     * @return {@link WildcardExpander.ExpansionResult.Expanded} with one rule per binding tuple, or
     *         {@link WildcardExpander.ExpansionResult.NoMatch} carrying the audit reason
     */
    public static WildcardExpander.ExpansionResult tryExpand(Rule rule, DataTableMeta meta,
            Context ctx)
    {
        List<ExpansionDirective> directives = Objects.requireNonNull(rule.getExpansion(),
                "TokenExpander reached for a rule with no Expansion block");
        List<String> columns = columnsOf(meta);
        List<String> reasons = new ArrayList<>();

        // Bind each directive independently, then take the cross product. Both shipped templates
        // declare exactly one directive; the product costs nothing and keeps the list shape honest.
        List<List<Binding>> perDirective = new ArrayList<>(directives.size());
        for (ExpansionDirective directive : directives)
        {
            // A FRESH reason list per directive: the audit row names the directive that failed, so
            // folding in reasons accumulated by an earlier directive would attribute one
            // directive's drop to another.
            List<String> directiveReasons = new ArrayList<>();
            List<Binding> bindings = bind(rule, directive, columns, ctx, directiveReasons);
            if (bindings.isEmpty())
            {
                return new WildcardExpander.ExpansionResult.NoMatch(
                        joinReasons(rule, directive, directiveReasons));
            }
            reasons.addAll(directiveReasons);
            perDirective.add(bindings);
        }
        if (!reasons.isEmpty())
        {
            // Some candidates survived, some did not. The survivors still expand, but the drops
            // must not vanish — they are the "cannot check this one" cases.
            LOGGER.log(System.Logger.Level.WARNING, "Expansion of " + rule.effectiveId()
                    + " dropped some candidates: " + String.join("; ", reasons));
        }

        List<Rule> expanded = new ArrayList<>();
        for (List<Binding> tuple : crossProduct(perDirective))
        {
            Rule concrete = buildExpansion(rule, tuple);
            String survivor = firstSurvivingToken(concrete, tuple);
            if (survivor != null)
            {
                // A token that reached a RESOLVED rule means substitution missed a position.
                // Evaluating it would test a column that cannot exist; drop it loudly instead.
                LOGGER.log(System.Logger.Level.WARNING,
                        "Expansion " + concrete.effectiveId() + " still carries the token '"
                                + survivor + "' after substitution — dropped, not evaluated");
                continue;
            }
            String unjoinable = firstAbsentMergeKey(concrete, meta);
            if (unjoinable != null)
            {
                // The join key is missing from the PRIMARY. RuleRunner still registers the lookup
                // (only a failure on the JOINED side self-skips), so every primary row would fail
                // to match and an absence-shaped Check — `var_exists(AE.AESEQ) and
                // empty(AE.AESEQ)`,
                // the PMDA-AD0258 shape — would fire on EVERY RECORD. A flood of false positives
                // is the one outcome worse than silence.
                String message = "Expansion " + concrete.effectiveId() + " joins on '" + unjoinable
                        + "', which the dataset under validation does not carry — dropped rather"
                        + " than fired on every record";
                LOGGER.log(System.Logger.Level.WARNING, message);
                reasons.add(message);
                continue;
            }
            expanded.add(concrete);
        }
        if (expanded.isEmpty())
        {
            return new WildcardExpander.ExpansionResult.NoMatch(reasons.isEmpty()
                    ? "Expansion produced no rule whose tokens were all substituted"
                    : "Expansion produced no usable rule (" + String.join("; ", reasons)
                            + "); not expanded for");
        }
        return new WildcardExpander.ExpansionResult.Expanded(List.copyOf(expanded));
    }

    // ------------------------------------------------------------------
    // Half 1 — binding. Each `over:` value gets exactly one method here.
    // ------------------------------------------------------------------


    /** Dispatches one directive to its source; appends any per-candidate drop reason. */
    private static List<Binding> bind(Rule rule, ExpansionDirective directive, List<String> columns,
            Context ctx, List<String> reasons)
    {
        if (directive.getOver() == null || directive.getToken() == null)
        {
            // Rejected at load (RulePackageLoader.validateExpansionDirectives); a rule carrying a
            // load error never reaches the expander, so this is belt-and-braces only.
            reasons.add("directive is incomplete");
            return List.of();
        }
        return switch (directive.getOver())
        {
        case SHARED_VARIABLES -> bindSharedVariables(rule, directive, columns, ctx, reasons);
        case DOMAIN_FROM_VARIABLE -> bindDomainFromVariable(directive, columns, ctx, reasons);
        };
    }


    /**
     * {@code over: shared_variables} — every variable this dataset shares by name with
     * {@code with:}.
     *
     * <p>
     * The rule's own {@code Match_Datasets} keys for that dataset are excluded: a merge key
     * compared against itself after merging on it is equal by construction, so expanding over it
     * would emit a rule that can never fire. That exclusion is derived from the rule, not from a
     * maintained list, and it is not a narrowing of "every shared variable" — it drops exactly the
     * bindings whose check is a tautology.
     * </p>
     */
    private static List<Binding> bindSharedVariables(Rule rule, ExpansionDirective directive,
            List<String> columns, Context ctx, List<String> reasons)
    {
        String token = Objects.requireNonNull(directive.getToken());
        String with = directive.getWith();
        if (with == null || with.isBlank())
        {
            // Enforced at load; re-checked because tryExpand is public (see
            // bindDomainFromVariable).
            reasons.add("the directive names no 'with' dataset");
            return List.of();
        }
        if (with.equalsIgnoreCase(ctx.primaryName()))
        {
            // The dataset under validation IS the foreign dataset. Every expansion would compare a
            // column with itself after a self-join and could never fire — and on a wide ADSL that
            // is hundreds of vacuous rules per run, not one. Both templates ship
            // Scope.Domains.Include: [ALL], so this case is reached on every real ADaM package.
            reasons.add("the dataset under validation IS '" + with
                    + "', so every shared variable would be compared with itself");
            return List.of();
        }
        if (ctx.foreign() == null)
        {
            reasons.add("no inventory-capable dataset resolver, so the variables of '" + with
                    + "' cannot be enumerated (an absent dataset is indistinguishable from a blind"
                    + " resolver)");
            return List.of();
        }
        List<DataTableMeta> metas = ctx.foreign().metasOf(with);
        if (metas.isEmpty())
        {
            reasons.add("dataset '" + ctx.foreign().resolvedQualifier(with)
                    + "' is not among the loaded datasets");
            return List.of();
        }
        Set<String> mergeKeys = mergeKeysFor(rule, with);
        List<Binding> bindings = new ArrayList<>();
        for (String column : columns)
        {
            if (sharedWithAny(metas, column) && !containsIgnoreCase(mergeKeys, column))
            {
                bindings.add(new Binding(token, column, column));
            }
        }
        if (bindings.isEmpty())
        {
            reasons.add("no variable is shared by name with '" + with
                    + "' beyond the columns the rule already merges on");
        }
        return bindings;
    }


    /**
     * The rule's own {@code Match_Datasets} join keys for {@code datasetName}, both sides.
     *
     * <p>
     * These are excluded from a {@code shared_variables} binding, and that is a tautology filter
     * rather than a narrowing of "every shared variable": after merging on {@code USUBJID}, the
     * expansion {@code USUBJID != ADSL.USUBJID} is false by construction on every row, so it can
     * only cost execution time. The set is read off the rule itself — there is no list to maintain.
     * </p>
     */
    private static Set<String> mergeKeysFor(Rule rule, String datasetName)
    {
        Set<String> keys = new LinkedHashSet<>();
        List<MatchDataset> matchDatasets = rule.getMatchDatasets();
        if (matchDatasets == null)
        {
            return keys;
        }
        for (MatchDataset md : matchDatasets)
        {
            if (md.getName() == null || !md.getName().equalsIgnoreCase(datasetName))
            {
                continue;
            }
            if (md.getKeys() != null)
            {
                keys.addAll(md.getKeys());
            }
            if (md.getRightKeys() != null)
            {
                keys.addAll(md.getRightKeys());
            }
        }
        return keys;
    }


    /**
     * {@code over: domain_from_variable} — every column matching {@code pattern:}, with the token
     * bound to the text at the token's position.
     *
     * <p>
     * {@code known_domain_only: true} keeps only captures the library attests as dataset names.
     * That filter is not optional in practice. On the SDTM/SEND side the {@code <XX>SEQ} ⇒ domain
     * {@code <XX>} convention is exact (measured: 380/380 across sdtmig, sendig and tig, zero
     * exceptions), but the ADaM side carries 22 {@code *SEQ} variables that are <em>not</em> parent
     * references — {@code ASEQ} (ADaM's own sequence), {@code SRCSEQ} (whose parent is named by
     * {@code SRCDOM}, not by the variable name), {@code RECSEQ}, and a literal {@code --SEQ} in the
     * OCCDS model. Without the filter those expand to {@code ASEQ ∈ AS.ASEQ} and
     * {@code SRCSEQ ∈ SR.SRCSEQ}.
     * </p>
     */
    private static List<Binding> bindDomainFromVariable(ExpansionDirective directive,
            List<String> columns, Context ctx, List<String> reasons)
    {
        String token = Objects.requireNonNull(directive.getToken());
        String pattern = directive.getPattern();
        // Both invariants are enforced at load, and a load-error rule never reaches the expander.
        // They are re-checked because tryExpand is public: a future caller must get an audit
        // reason, not a NullPointerException or a StringIndexOutOfBoundsException.
        if (pattern == null || pattern.indexOf(token) < 0)
        {
            reasons.add("the directive has no 'pattern' containing its token '" + token + "'");
            return List.of();
        }
        int at = pattern.indexOf(token);
        String prefix = pattern.substring(0, at);
        String suffix = pattern.substring(at + token.length());

        if (ctx.foreign() == null)
        {
            // Symmetric with shared_variables, and for the same reason: without an inventory
            // "the parent domain is absent" and "this resolver cannot see other datasets" are
            // indistinguishable, so binding anyway would emit a rule joining to a dataset there is
            // no evidence exists. Skip with a reason rather than guess.
            reasons.add("no inventory-capable dataset resolver, so a captured parent domain cannot"
                    + " be confirmed to be among the loaded datasets");
            return List.of();
        }
        boolean knownOnly = Boolean.TRUE.equals(directive.getKnownDomainOnly());
        List<String> attested = knownOnly ? standardDomains(ctx) : List.of();
        if (attested == null)
        {
            reasons.add("known_domain_only is set but the library serves no standard dataset list,"
                    + " so a captured prefix cannot be told apart from ADaM's own ASEQ / SRCSEQ /"
                    + " RECSEQ");
            return List.of();
        }
        List<String> knownDomains = attested;

        List<Binding> bindings = new ArrayList<>();
        boolean anyCandidate = false;
        for (String column : columns)
        {
            String captured = capture(column, prefix, suffix);
            if (captured == null)
            {
                continue;
            }
            anyCandidate = true;
            if (knownOnly && !containsIgnoreCase(knownDomains, captured))
            {
                // Not a parent reference at all — not a defect, so no reason is recorded.
                continue;
            }
            if (captured.equalsIgnoreCase(ctx.primaryName()))
            {
                // The dataset under validation IS the inferred parent: the expansion would join a
                // dataset to itself on its own key and can never fire.
                continue;
            }
            if (ctx.foreign().metasOf(captured).isEmpty())
            {
                reasons.add(column + " names parent domain '" + captured
                        + "', which is not among the loaded datasets");
                continue;
            }
            bindings.add(new Binding(token, captured, column));
        }
        if (bindings.isEmpty() && !anyCandidate)
        {
            reasons.add("no column matches the pattern '" + pattern + "'");
        }
        else if (bindings.isEmpty())
        {
            reasons.add("every column matching '" + pattern + "' was filtered out");
        }
        return bindings;
    }


    /**
     * The library's canonical dataset names, or {@code null} when it serves none. An ADaM run's own
     * provider carries no SDTM product, which is why {@code StudyValidationService} wraps it in
     * {@code CompanionDomainsProvider} — this accessor is exactly the one that decorator overrides.
     */
    private static @Nullable List<String> standardDomains(Context ctx)
    {
        return ctx.provider() != null ? ctx.provider().getStandardDatasetNames() : null;
    }


    /**
     * The text between {@code prefix} and {@code suffix}, or {@code null} when it does not fit.
     *
     * <p>
     * The literal halves are matched <b>case-insensitively</b>, for the same reason
     * {@link #sharedWithAny} resolves through {@code getColumnIndex}: a table's column names carry
     * whatever casing the reader produced, and every other column lookup in the engine is
     * case-insensitive by default. The <em>captured</em> text is returned verbatim, so the
     * expansion still names the domain as the data spells it.
     * </p>
     */
    private static @Nullable String capture(String column, String prefix, String suffix)
    {
        if (column.length() <= prefix.length() + suffix.length()
                || !column.regionMatches(true, 0, prefix, 0, prefix.length())
                || !column.regionMatches(true, column.length() - suffix.length(), suffix, 0,
                        suffix.length()))
        {
            return null;
        }
        return column.substring(prefix.length(), column.length() - suffix.length());
    }


    /**
     * Whether {@code column} is carried by any of the foreign tables.
     *
     * <p>
     * Resolution goes through each table's own {@link DataTableMeta#getColumnIndex}, not through a
     * flattened name set, because that accessor honours the table's per-table case-sensitivity flag
     * — which defaults to <b>insensitive</b>. {@code ScopeVariableSource.metasOf} returns metadata
     * rather than a flat set for exactly this reason, and its javadoc says so: flattening would
     * hard-code one casing policy and diverge from every other column lookup in the engine. A
     * submission whose ADSL is read with different casing from its ADAE must still expand.
     * </p>
     */
    private static boolean sharedWithAny(List<DataTableMeta> metas, String column)
    {
        for (DataTableMeta foreignMeta : metas)
        {
            if (foreignMeta.getColumnIndex(column) >= 0)
            {
                return true;
            }
        }
        return false;
    }


    private static boolean containsIgnoreCase(java.util.Collection<String> values, String candidate)
    {
        for (String value : values)
        {
            if (value != null && value.equalsIgnoreCase(candidate))
            {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Half 2 — substitution. Token in, string out; source-blind by design.
    // ------------------------------------------------------------------


    /** Builds one concrete rule from one full tuple of token bindings. */
    private static Rule buildExpansion(Rule template, List<Binding> tuple)
    {
        // Longest token first, so a declared token that contains another cannot make the result
        // depend on iteration order. (The load-time validation rejects that shape outright; the
        // ordering here means the mechanism is well-defined even for a rule loaded past it.)
        Map<String, String> substitutions = new LinkedHashMap<>();
        tuple.stream().sorted(Comparator.comparingInt((Binding b) -> b.token().length()).reversed())
                .forEach(b -> substitutions.put(b.token(), b.value()));
        java.util.function.UnaryOperator<String> rename = n -> substitute(n, substitutions);

        String origCoreId = template.effectiveId();
        StringBuilder id = new StringBuilder(origCoreId);
        for (Binding b : tuple)
        {
            id.append('-').append(b.idSuffix());
        }
        String expandedCoreId = id.toString();

        Rule rule = new Rule();
        rule.setId(deterministicUuid(expandedCoreId));
        RuleCore core = new RuleCore();
        core.setId(expandedCoreId);
        core.setStatus("Generated");
        core.setVersion("1");
        rule.setCore(core);

        CheckCondition check = Objects.requireNonNull(template.getCheck(),
                "expansion template has no Check");
        rule.setCheck(WildcardExpander.substituteNames(check, rename));
        // ⚠⚠ Plan C: every level gets the same substitution — see the Severity note below for why
        // an unnamed top-level field is silently and invisibly dropped here.
        rule.setCheckLevels(net.cumba.corej.core.model.LevelCheck.mapConditions(
                template.getCheckLevels(), c -> WildcardExpander.substituteNames(c, rename)));
        if (template.getPrecondition() != null)
        {
            rule.setPrecondition(
                    WildcardExpander.substituteNames(template.getPrecondition(), rename));
        }
        rule.setDescription(template.getDescription() != null
                ? WildcardExpander.substituteInText(template.getDescription(), substitutions)
                : expandedCoreId);
        rule.setVariableUniverse(template.getVariableUniverse());
        rule.setSensitivity(template.getSensitivity());
        // ⚠⚠ Plan C: Severity is copied EXPLICITLY, for the same reason Requirements is (below).
        // This method builds the expanded rule field by field from a fresh `new Rule()`, so a
        // top-level field that is not named here is SILENTLY DROPPED from every expanded child —
        // and the drop is invisible to the loader, the schemas and the writer, because the
        // TEMPLATE still carries the field. Measured when it was missing: 15 class-scoped rules /
        // 944 finding rows reported ERROR while their template was authored Warning.
        rule.setSeverity(template.getSeverity());

        Outcome outcome = new Outcome();
        String message = template.getOutcome() != null ? template.getOutcome().getMessage()
                : origCoreId;
        outcome.setMessage(WildcardExpander.substituteInText(message, substitutions));
        List<String> outputVars = template.getOutcome() != null
                ? template.getOutcome().getOutputVariables()
                : null;
        if (outputVars != null)
        {
            // Fix #356: rename the NAME inside the token — `rename` is a whole-name map lookup, so
            // a raw `!AyLO` misses every key and would survive the expansion unresolved while the
            // Check resolved, which E-3.1 rejects at load (ENGINE_ERROR on every dataset).
            outcome.setOutputVariables(
                    outputVars.stream().map(v -> OutputVariableToken.mapName(v, rename)).toList());
        }
        rule.setOutcome(outcome);

        // Scope and Requirements carry over verbatim: a declared expansion token is barred from
        // Requirements.Variables by loader gate R6 (the requirement gate runs before expansion and
        // would match the token literally), and no other axis of either block can hold one — so
        // there is nothing in them to rewrite.
        // ⚠ Requirements is copied EXPLICITLY. It is a top-level block like Scope, and an
        // expansion template that declares one would otherwise lose it silently — the expanded
        // rule would run with its requirement deleted, which is not a skipped rule but an
        // unguarded one (PLAN-scope-requirements-split.md §9 trap 3).
        rule.setScope(template.getScope());
        rule.setRequirements(template.getRequirements());
        rule.setMatchDatasets(substituteMatchDatasets(template.getMatchDatasets(), substitutions));
        rule.setOperations(substituteOperations(template.getOperations(), substitutions));
        rule.setGroupingVariables(template.getGroupingVariables() != null
                ? template.getGroupingVariables().stream().map(rename).toList()
                : null);
        // The Grouping: block carries the same key list and needs the same token rename; its
        // keep_missings disposition is a plain copy. Without this the block would be dropped
        // silently on every expanded rule (the flat form above is renamed, so a template that
        // migrated to the block would lose its grouping entirely).
        if (template.getGrouping() != null)
        {
            net.cumba.corej.core.model.GroupingSpec src = template.getGrouping();
            net.cumba.corej.core.model.GroupingSpec copy = new net.cumba.corej.core.model.GroupingSpec();
            copy.setVariables(
                    src.getVariables() != null ? src.getVariables().stream().map(rename).toList()
                            : null);
            copy.setKeepMissings(src.getKeepMissings());
            rule.setGrouping(copy);
        }

        // Same post-processing a loader-loaded concrete rule gets, in the SAME ORDER
        // WildcardExpander applies it: an expansion bypassed RulePackageLoader entirely, so it
        // needs the derived omitted fields (Rule_Type / Sensitivity, read off the post-substitution
        // Check), the compiled native program (P5) and the derived effective Output_Variables
        // (EC-37). Keeping the order identical means the two expansion mechanisms cannot drift into
        // producing differently-derived rules from the same concrete Check.
        RulePackageLoader.deriveOmittedFields(rule);
        RulePackageLoader.installNativeExpr(rule);
        RulePackageLoader.deriveOutputVariables(rule);
        return rule;
    }


    /** The one and only substitution primitive: replace each token with its bound value. */
    private static String substitute(String text, Map<String, String> substitutions)
    {
        String result = text;
        for (Map.Entry<String, String> e : substitutions.entrySet())
        {
            result = result.replace(e.getKey(), e.getValue());
        }
        return result;
    }


    /**
     * Rewrites {@code Match_Datasets} through the JSON tree rather than the typed accessors, so the
     * sided {@code {left, right}} key shape (EC-18) survives untouched alongside the bare-string
     * one — {@code MatchDataset.setKeys} can only write bare strings.
     */
    private static @Nullable List<MatchDataset> substituteMatchDatasets(
            @Nullable List<MatchDataset> matchDatasets, Map<String, String> substitutions)
    {
        if (matchDatasets == null)
        {
            return null;
        }
        List<MatchDataset> out = new ArrayList<>(matchDatasets.size());
        for (MatchDataset md : matchDatasets)
        {
            out.add(MAPPER.convertValue(substituteTree(MAPPER.valueToTree(md), substitutions),
                    MatchDataset.class));
        }
        return List.copyOf(out);
    }


    /** Same JSON-tree rewrite for {@code Operations}, so a future template can tokenise one. */
    private static @Nullable List<Operation> substituteOperations(
            @Nullable List<Operation> operations, Map<String, String> substitutions)
    {
        if (operations == null)
        {
            return null;
        }
        List<Operation> out = new ArrayList<>(operations.size());
        for (Operation op : operations)
        {
            out.add(MAPPER.convertValue(substituteTree(MAPPER.valueToTree(op), substitutions),
                    Operation.class));
        }
        return List.copyOf(out);
    }


    /** Substitutes inside every textual node of a JSON tree, structure preserved. */
    private static JsonNode substituteTree(JsonNode node, Map<String, String> substitutions)
    {
        if (node.isTextual())
        {
            return new TextNode(substitute(node.asText(), substitutions));
        }
        if (node.isArray())
        {
            ArrayNode arr = MAPPER.createArrayNode();
            for (JsonNode element : node)
            {
                arr.add(substituteTree(element, substitutions));
            }
            return arr;
        }
        if (node.isObject())
        {
            ObjectNode obj = MAPPER.createObjectNode();
            node.properties()
                    .forEach(e -> obj.set(e.getKey(), substituteTree(e.getValue(), substitutions)));
            return obj;
        }
        return node;
    }


    /**
     * The first {@code Match_Datasets} join key the dataset under validation does not carry, or
     * {@code null} when every key resolves.
     *
     * <p>
     * This is a <b>false-positive</b> guard, not a silence guard, and it is the mirror image of the
     * self-skip the authored shape already has. {@code RuleRunner.buildJoinedDatasets} registers
     * the lookup unless the JOINED side fails to build, so an absence-shaped Check whose primary
     * lacks a key sees an unmatched row every time and fires on all of them. The hand-authored
     * {@code PMDA-AD0258} is insulated only by its {@code Data_Structures}/{@code Subclasses}
     * scope, which guarantees {@code STUDYID}/{@code USUBJID}; {@code CDISC-AD0898} ships
     * {@code Domains: Include: [ALL]} and has no such guarantee. Guarding here rather than adding
     * {@code exists} conjuncts to the template keeps the expansion byte-identical to the shape
     * {@code PMDA-AD0258} proves.
     * </p>
     */
    private static @Nullable String firstAbsentMergeKey(Rule concrete, DataTableMeta meta)
    {
        List<MatchDataset> matchDatasets = concrete.getMatchDatasets();
        if (matchDatasets == null)
        {
            return null;
        }
        for (MatchDataset md : matchDatasets)
        {
            List<String> keys = md.getKeys();
            if (keys == null)
            {
                continue;
            }
            for (String key : keys)
            {
                if (key != null && meta.getColumnIndex(key) < 0)
                {
                    return key;
                }
            }
        }
        return null;
    }


    /**
     * The post-expansion assertion: the first declared token still present anywhere in the resolved
     * rule, or {@code null} when substitution was complete.
     */
    private static @Nullable String firstSurvivingToken(Rule concrete, List<Binding> tuple)
    {
        String serialised;
        try
        {
            serialised = MAPPER.writeValueAsString(concrete);
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException _)
        {
            // A rule that cannot be serialised cannot be inspected; treat as a failed expansion
            // rather than assume it is clean.
            return tuple.isEmpty() ? "?" : tuple.get(0).token();
        }
        for (Binding b : tuple)
        {
            if (serialised.contains(b.token()))
            {
                return b.token();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Small shared helpers
    // ------------------------------------------------------------------


    private static List<String> columnsOf(DataTableMeta meta)
    {
        List<String> columns = new ArrayList<>(meta.getColumnCount());
        for (int i = 0; i < meta.getColumnCount(); i++)
        {
            columns.add(meta.getColumn(i).getName());
        }
        return columns;
    }


    /** Cartesian product over the per-directive binding lists, in declaration order. */
    private static List<List<Binding>> crossProduct(List<List<Binding>> perDirective)
    {
        List<List<Binding>> tuples = new ArrayList<>();
        tuples.add(List.of());
        for (List<Binding> bindings : perDirective)
        {
            List<List<Binding>> next = new ArrayList<>(tuples.size() * bindings.size());
            for (List<Binding> prefix : tuples)
            {
                for (Binding b : bindings)
                {
                    List<Binding> extended = new ArrayList<>(prefix);
                    extended.add(b);
                    next.add(List.copyOf(extended));
                }
            }
            tuples = next;
        }
        return tuples;
    }


    /** The audit reason a caller renders on a SKIPPED row. */
    private static String joinReasons(Rule rule, ExpansionDirective directive, List<String> reasons)
    {
        String over = directive.getOverJson() != null ? directive.getOverJson()
                : String.valueOf(directive.getOver());
        return "Expansion of " + rule.effectiveId() + " over '" + over + "' yielded no binding ("
                + (reasons.isEmpty() ? "no candidates" : String.join("; ", reasons))
                + "); not expanded for";
    }


    private static String deterministicUuid(String input)
    {
        return java.util.UUID
                .nameUUIDFromBytes(input.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
    }

}
