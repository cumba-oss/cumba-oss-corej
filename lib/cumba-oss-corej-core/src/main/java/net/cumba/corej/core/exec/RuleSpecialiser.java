package net.cumba.corej.core.exec;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.LevelCheck;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.OutputVariableToken;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.report.Severity;
import org.jspecify.annotations.Nullable;

/**
 * The bind-time rule specialisation stage (D77): everything about a rule that is decidable from
 * <em>(rule &times; dataset metadata)</em> alone is resolved here, <b>before</b> execution, so the
 * engine receives a <em>concrete</em> rule and asserts concreteness instead of resolving
 * ({@code ExprCompiler.resolveDomainPrefix} throws on an unresolved {@code --}).
 *
 * <p>
 * <b>One prefix policy (D77c), computed once per (rule &times; dataset):</b>
 * {@link DatasetIdentity#variableWildcardPrefix} for every <em>variable-name</em> position (EC-36:
 * {@code ""} for SUPP/SQ, the 2-character parent suffix for AP), and the full CDISC <em>domain
 * code</em> for dataset-name positions ({@code SUPP--.QVAL}, a registry call's {@code domain=} —
 * Fix #59/#33). This is the single authority; the former competing {@code DatasetRuleResolver}
 * policy ({@code domain.substring(0, 2)}) is deleted — that deletion <em>is</em> the F1 fix (D77f).
 * </p>
 *
 * <p>
 * <b>Coverage (D77b):</b> the Check tree, every declared level's Check, the Precondition, the
 * native expressions ({@code checkExpr}, per-level exprs, {@code preconditionExpr} — via
 * {@link ExprPrefixResolver}), the compiled bindings (R11), non-{@code Child}
 * {@code Match_Datasets} names, {@code Grouping} / {@code Grouping_Variables}, and the authored,
 * effective and excluded {@code Output_Variables}.
 * </p>
 *
 * <p>
 * <b>What deliberately stays runtime- or gate-side:</b>
 * </p>
 * <ul>
 * <li>{@code Scope} / {@code Requirements} entries — they are consumed only by the bind-time gates
 * ({@code ScopeMatcher}), which already take the same variable prefix from the same authority; the
 * entries themselves stay authored so the gates keep reporting the authored spelling.</li>
 * <li>{@code Child: true} {@code Match_Datasets} entries ({@code Name: "SUPP--"}) — the name is a
 * <em>pattern matched against the primary dataset's own name</em>
 * ({@code ChildEntries.applicableEntry}), split-aware: {@code SUPP--} must keep matching a split
 * member such as {@code SUPPLBCH}, which a resolved {@code SUPPLB} would not. A pattern is not a
 * reference; resolving it would break split-SUPP child matching.</li>
 * <li>The D77d per-row forms ({@code ${VAR[:fmt]}}, a dot-qualified {@code **}) and the D92a / D92b
 * constructs — see {@link ExprPrefixResolver}.</li>
 * <li>Every STRING literal outside a name slot ({@code PLAN-string-literal-prefix-rewrite} S3) — a
 * binding's own literal included: {@code $c: "--SEQ"} stays {@code "--SEQ"}, so {@code X == $c}
 * compares against it verbatim as {@code X == "--SEQ"} does, while {@code colref($c)} was already
 * rewritten to {@code colref("--SEQ")} by the loader
 * ({@code BindingInliner.inlineNameSlotBindings}, S8) and is specialised here at its name slot
 * (G1). A {@code $} member of a splice list holding a written name is inlined at that SITE only
 * ({@link #inlineSplicedNames}): the binding itself keeps its literal.</li>
 * <li>Record keys — the {@code --} candidates there are engine constants, not rule fields, and
 * {@code RecordKeyResolver} already resolves them through this same prefix authority.</li>
 * </ul>
 *
 * <p>
 * Copy-on-write: when nothing needs resolving the <b>same instance</b> is returned (so pass-through
 * identity pins hold and re-specialising an already-concrete rule is cheap); when anything does, a
 * <em>full shallow copy</em> is taken first — every field, reflectively — so a new top-level
 * {@code Rule} field can never be silently dropped the way the retired field-by-field clone sites
 * dropped {@code Severity} and the level map (the &#167;2.1 hazard both {@code DatasetRuleResolver}
 * and {@code WildcardExpander} document).
 * </p>
 */
public final class RuleSpecialiser
{

    private RuleSpecialiser()
    {
    }


    /**
     * Specialises {@code rule} for {@code (table, domainCode)}, returning the concrete rule — or
     * the same instance when the rule is already concrete, carries a {@code loadError} (it must
     * surface its ERROR sentinel unmodified — review F4), or no domain code is available (a
     * degraded / synthetic context, where nothing can be resolved).
     *
     * @param rule
     *            the rule to specialise (may be {@code null})
     * @param table
     *            the dataset the rule will execute against, consulted for the AP {@code APID} gate
     *            and the SUPP parent-prefix nuance (may be {@code null})
     * @param domainCode
     *            the dataset's CDISC domain code as supplied by the caller, or {@code null} when
     *            there is none
     * @return the concrete rule, or {@code rule} itself when nothing needed resolving. ⚑
     *         {@code null} for exactly one input — a {@code null} {@code rule}; the two engine call
     *         sites assert that rather than null-checking a case they cannot reach
     */
    public static @Nullable Rule specialise(@Nullable Rule rule, @Nullable IDataTable table,
            @Nullable String domainCode)
    {
        if (rule == null || rule.getLoadError() != null)
        {
            return rule;
        }
        String variablePrefix = DatasetIdentity.variableWildcardPrefix(table, domainCode);
        if (variablePrefix == null)
        {
            return rule;
        }
        // ⚑ Non-null by the line above, not by assumption: variableWildcardPrefix answers null for
        // EXACTLY one input — a null domainCode (its first statement) — so surviving that guard is
        // the proof. Asserted rather than annotated because NullAway cannot see the implication
        // across the call, and a @Nullable datasetPrefix would be a lie every resolver below would
        // then have to carry.
        String datasetPrefix = java.util.Objects.requireNonNull(domainCode,
                "variableWildcardPrefix answered non-null for a null domain code");

        // ---- compute every resolved field, tracking change per field ----

        // the bindings a splice-list `$` member may inline (review MED-1 / MED-A)
        Map<String, net.cumba.corej.core.expr.ast.Expr> bound = net.cumba.corej.core.expr.convert.BindingInliner
                .compiledExpressions(rule);
        SequencedMap<Severity, LevelCheck> levels = rule.getCheckLevels();
        // Phase 7d (D121): the typed Check / Precondition trees are wildcard-resolved on their
        // EXPRESSIONS — the leaf-tree transformer is retired with the operator-leaf model, and a
        // CheckConditionExpression node now resolves through the same ExprPrefixResolver the
        // compiled checkExpr resolves through, so the typed tree and the compiled expression
        // cannot disagree about a `--` name. (The retired transformer returned expression nodes
        // UNTOUCHED, which left rendered/serialised Checks holding `--` templates while the
        // compiled expression was concrete — the D77 "the declared tree IS the concrete tree"
        // contract only actually held for the retired leaf shapes.)
        boolean[] levelsChanged =
        {
                false
        };
        SequencedMap<Severity, LevelCheck> newLevels = LevelCheck.mapConditions(levels, c ->
        {
            CheckCondition resolved = resolveTree(c, bound, variablePrefix, datasetPrefix);
            if (resolved != c)
            {
                levelsChanged[0] = true;
            }
            return resolved;
        });

        CheckCondition check = rule.getCheck();
        CheckCondition newCheck = check == null ? null
                : resolveTree(check, bound, variablePrefix, datasetPrefix);

        CheckCondition precondition = rule.getPrecondition();
        CheckCondition newPrecondition = precondition == null ? null
                : resolveTree(precondition, bound, variablePrefix, datasetPrefix);

        net.cumba.corej.core.expr.ast.Expr checkExpr = rule.getCheckExpr();
        net.cumba.corej.core.expr.ast.Expr newCheckExpr = checkExpr == null ? null
                : specialiseExpr(checkExpr, bound, variablePrefix, datasetPrefix);

        Map<Severity, net.cumba.corej.core.expr.ast.Expr> levelExprs = rule.getCheckLevelExprs();
        Map<Severity, net.cumba.corej.core.expr.ast.Expr> newLevelExprs = levelExprs;
        if (levelExprs != null)
        {
            Map<Severity, net.cumba.corej.core.expr.ast.Expr> resolved = new LinkedHashMap<>();
            boolean changed = false;
            for (Map.Entry<Severity, net.cumba.corej.core.expr.ast.Expr> e : levelExprs.entrySet())
            {
                net.cumba.corej.core.expr.ast.Expr r = specialiseExpr(e.getValue(), bound,
                        variablePrefix, datasetPrefix);
                changed |= r != e.getValue();
                resolved.put(e.getKey(), r);
            }
            if (changed)
            {
                newLevelExprs = resolved;
            }
        }

        net.cumba.corej.core.expr.ast.Expr preExpr = rule.getPreconditionExpr();
        net.cumba.corej.core.expr.ast.Expr newPreExpr = preExpr == null ? null
                : specialiseExpr(preExpr, bound, variablePrefix, datasetPrefix);

        // PLAN-binding-expressions R11: a compiled binding's expression is resolved exactly as the
        // Check's is — through ExprPrefixResolver — and SET on the copy below: shallowCopy copies
        // the list by reference, unresolved, so nothing would fail if this were forgotten.
        List<net.cumba.corej.core.model.CompiledBinding> compiledBindings = rule
                .getCompiledBindings();
        List<net.cumba.corej.core.model.CompiledBinding> newCompiledBindings = compiledBindings;
        if (compiledBindings != null)
        {
            List<net.cumba.corej.core.model.CompiledBinding> resolved = new ArrayList<>(
                    compiledBindings.size());
            boolean changed = false;
            for (net.cumba.corej.core.model.CompiledBinding binding : compiledBindings)
            {
                net.cumba.corej.core.expr.ast.Expr r = specialiseExpr(binding.expression(), bound,
                        variablePrefix, datasetPrefix);
                changed |= r != binding.expression();
                resolved.add(r == binding.expression() ? binding : binding.withExpression(r));
            }
            if (changed)
            {
                newCompiledBindings = resolved;
            }
        }

        List<MatchDataset> matchDatasets = rule.getMatchDatasets();
        List<MatchDataset> newMatchDatasets = matchDatasets;
        if (matchDatasets != null && table != null)
        {
            List<MatchDataset> resolved = new ArrayList<>(matchDatasets.size());
            boolean changed = false;
            for (MatchDataset md : matchDatasets)
            {
                MatchDataset r = resolveMatchDataset(md, table);
                changed |= r != md;
                resolved.add(r);
            }
            if (changed)
            {
                newMatchDatasets = resolved;
            }
        }

        List<String> groupingVariables = rule.getGroupingVariables();
        List<String> newGroupingVariables = resolveVariableNames(groupingVariables, variablePrefix);

        net.cumba.corej.core.model.GroupingSpec grouping = rule.getGrouping();
        net.cumba.corej.core.model.GroupingSpec newGrouping = grouping;
        if (grouping != null)
        {
            List<String> vars = resolveVariableNames(grouping.getVariables(), variablePrefix);
            if (vars != grouping.getVariables())
            {
                newGrouping = shallowCopy(grouping, net.cumba.corej.core.model.GroupingSpec::new);
                newGrouping.setVariables(vars);
            }
        }

        Outcome outcome = rule.getOutcome();
        Outcome newOutcome = outcome;
        if (outcome != null && outcome.getOutputVariables() != null)
        {
            List<String> vars = resolveOutputVariableTokens(outcome.getOutputVariables(),
                    variablePrefix, datasetPrefix);
            if (vars != outcome.getOutputVariables())
            {
                newOutcome = shallowCopy(outcome, Outcome::new);
                newOutcome.setOutputVariables(vars);
            }
        }

        List<String> effectiveOut = rule.getEffectiveOutputVariables();
        List<String> newEffectiveOut = resolveNames(effectiveOut, variablePrefix, datasetPrefix);

        Set<String> excludedOut = rule.getExcludedOutputVariables();
        Set<String> newExcludedOut = excludedOut;
        if (excludedOut != null)
        {
            Set<String> resolved = new LinkedHashSet<>();
            boolean changed = false;
            for (String name : excludedOut)
            {
                String r = resolveName(name, variablePrefix, datasetPrefix);
                changed |= !java.util.Objects.equals(r, name);
                resolved.add(r);
            }
            if (changed)
            {
                newExcludedOut = resolved;
            }
        }

        boolean changed = newCheck != check || levelsChanged[0] || newPrecondition != precondition
                || newCheckExpr != checkExpr || newLevelExprs != levelExprs || newPreExpr != preExpr
                || newCompiledBindings != compiledBindings || newMatchDatasets != matchDatasets
                || newGroupingVariables != groupingVariables || newGrouping != grouping
                || newOutcome != outcome || newEffectiveOut != effectiveOut
                || newExcludedOut != excludedOut;
        if (!changed)
        {
            return rule;
        }

        // ---- copy-on-write: full shallow copy, then overwrite the resolved fields ----
        Rule copy = shallowCopy(rule, Rule::new);
        if (levels != null)
        {
            // setCheckLevels re-derives `check` from the strictest entry, so the two cannot
            // disagree (the same invariant the model setters maintain for the loader).
            copy.setCheckLevels(newLevels);
        }
        else
        {
            copy.setCheck(newCheck);
        }
        copy.setPrecondition(newPrecondition);
        copy.setCheckExpr(newCheckExpr);
        copy.setCheckLevelExprs(newLevelExprs);
        copy.setPreconditionExpr(newPreExpr);
        copy.setCompiledBindings(newCompiledBindings);
        copy.setMatchDatasets(newMatchDatasets);
        copy.setGroupingVariables(newGroupingVariables);
        copy.setGrouping(newGrouping);
        copy.setOutcome(newOutcome);
        copy.setEffectiveOutputVariables(newEffectiveOut);
        copy.setExcludedOutputVariables(newExcludedOut);
        return copy;
    }


    /**
     * {@code e} specialised: its splice-list {@code $} members holding a written name inlined
     * ({@link #inlineSplicedNames}), then {@link ExprPrefixResolver#resolve}.
     */
    private static net.cumba.corej.core.expr.ast.Expr specialiseExpr(
            net.cumba.corej.core.expr.ast.Expr e,
            Map<String, net.cumba.corej.core.expr.ast.Expr> bound, String variablePrefix,
            String datasetPrefix)
    {
        net.cumba.corej.core.expr.ast.Expr inlined = bound.isEmpty() ? e
                : inlineSplicedNames(e, bound);
        return ExprPrefixResolver.resolve(inlined, variablePrefix, datasetPrefix);
    }


    /**
     * ⭐ {@code PLAN-string-literal-prefix-rewrite} review MED-1 (confirmation look MED-A): a
     * {@code $} member of a splice list ({@code GroupSplice.spliceLists} —
     * {@code is_(not_)unique_set([…, $k])}, {@code record_count(group=[…, $k])}) is read as column
     * NAMES at run time; when its binding holds a WRITTEN name — a string literal, or a list of
     * them — the member is that name written in the list, so {@code $k: "--TESTCD"} answers as
     * {@code [USUBJID, --TESTCD]} does (G1) and is specialised at its place in the list. Only the
     * splice SITE changes: the binding keeps its literal, so {@code AETERM == $k} and an
     * {@code Output_Variables} entry read {@code "--TESTCD"} verbatim (S3). Never under
     * {@code domain=}: there a spliced {@code --} name must keep meeting {@code GroupSplice}'s
     * refusal (it would resolve against the evaluated dataset, not the domain). A binding of a
     * binding ({@code $j: $k}) is not written there and stays a run-time splice — a {@code --}
     * reaching it is the loud D77b error.
     */
    private static net.cumba.corej.core.expr.ast.Expr inlineSplicedNames(
            net.cumba.corej.core.expr.ast.Expr e,
            Map<String, net.cumba.corej.core.expr.ast.Expr> bound)
    {
        return switch (e)
        {
        case net.cumba.corej.core.expr.ast.Expr.Call c ->
        {
            List<net.cumba.corej.core.expr.ast.Expr> lists = c.kwargs().containsKey(
                    GroupedAggregate.DOMAIN_PARAMETER) ? List.of() : GroupSplice.spliceLists(c);
            boolean changed = false;
            List<net.cumba.corej.core.expr.ast.Expr> args = new ArrayList<>();
            for (net.cumba.corej.core.expr.ast.Expr a : c.args())
            {
                net.cumba.corej.core.expr.ast.Expr r = lists.contains(a) ? spliceList(a, bound)
                        : inlineSplicedNames(a, bound);
                changed |= r != a;
                args.add(r);
            }
            Map<String, net.cumba.corej.core.expr.ast.Expr> kwargs = new LinkedHashMap<>();
            for (Map.Entry<String, net.cumba.corej.core.expr.ast.Expr> kw : c.kwargs().entrySet())
            {
                net.cumba.corej.core.expr.ast.Expr v = kw.getValue();
                net.cumba.corej.core.expr.ast.Expr r = lists.contains(v) ? spliceList(v, bound)
                        : inlineSplicedNames(v, bound);
                changed |= r != v;
                kwargs.put(kw.getKey(), r);
            }
            yield changed ? new net.cumba.corej.core.expr.ast.Expr.Call(c.name(), args, kwargs) : c;
        }
        case net.cumba.corej.core.expr.ast.Expr.Binary b ->
        {
            net.cumba.corej.core.expr.ast.Expr l = inlineSplicedNames(b.left(), bound);
            net.cumba.corej.core.expr.ast.Expr r = inlineSplicedNames(b.right(), bound);
            yield l == b.left() && r == b.right() ? b
                    : new net.cumba.corej.core.expr.ast.Expr.Binary(b.op(), l, r);
        }
        case net.cumba.corej.core.expr.ast.Expr.And a ->
        {
            List<net.cumba.corej.core.expr.ast.Expr> parts = a.parts().stream()
                    .map(p -> inlineSplicedNames(p, bound)).toList();
            yield parts.equals(a.parts()) ? a : new net.cumba.corej.core.expr.ast.Expr.And(parts);
        }
        case net.cumba.corej.core.expr.ast.Expr.Or o ->
        {
            List<net.cumba.corej.core.expr.ast.Expr> parts = o.parts().stream()
                    .map(p -> inlineSplicedNames(p, bound)).toList();
            yield parts.equals(o.parts()) ? o : new net.cumba.corej.core.expr.ast.Expr.Or(parts);
        }
        case net.cumba.corej.core.expr.ast.Expr.Not n ->
        {
            net.cumba.corej.core.expr.ast.Expr inner = inlineSplicedNames(n.inner(), bound);
            yield inner == n.inner() ? n : new net.cumba.corej.core.expr.ast.Expr.Not(inner);
        }
        case net.cumba.corej.core.expr.ast.Expr.Lit lit -> lit;
        case net.cumba.corej.core.expr.ast.Expr.Ref ref -> ref;
        };
    }


    /** A splice list with each {@code $} member holding written names replaced by those names. */
    private static net.cumba.corej.core.expr.ast.Expr spliceList(
            net.cumba.corej.core.expr.ast.Expr list,
            Map<String, net.cumba.corej.core.expr.ast.Expr> bound)
    {
        List<?> members = (List<?>) ((net.cumba.corej.core.expr.ast.Expr.Lit) list).value();
        List<net.cumba.corej.core.expr.ast.Expr> out = new ArrayList<>();
        boolean changed = false;
        for (Object o : members)
        {
            if (!(o instanceof net.cumba.corej.core.expr.ast.Expr member))
            {
                return list;
            }
            List<net.cumba.corej.core.expr.ast.Expr> names = writtenNames(member, bound);
            if (names == null)
            {
                out.add(member);
            }
            else
            {
                out.addAll(names);
                changed = true;
            }
        }
        return changed
                ? new net.cumba.corej.core.expr.ast.Expr.Lit(
                        net.cumba.corej.core.expr.ast.Expr.LitKind.LIST, out)
                : list;
    }


    /**
     * The names a {@code $} member's binding WRITES — a string literal, or a non-empty list of them
     * — as references; {@code null} when the member is not such a binding reference, or a name does
     * not classify (it then stays a run-time splice).
     */
    private static @Nullable List<net.cumba.corej.core.expr.ast.Expr> writtenNames(
            net.cumba.corej.core.expr.ast.Expr member,
            Map<String, net.cumba.corej.core.expr.ast.Expr> bound)
    {
        if (!(member instanceof net.cumba.corej.core.expr.ast.Expr.Ref ref)
                || ref.kind() != net.cumba.corej.core.expr.OperandKind.OPERATION_REF
                || !(bound.get(ref.name()) instanceof net.cumba.corej.core.expr.ast.Expr.Lit lit))
        {
            return null;
        }
        List<?> texts;
        if (lit.kind() == net.cumba.corej.core.expr.ast.Expr.LitKind.STRING)
        {
            texts = List.of(lit);
        }
        else if (lit.kind() == net.cumba.corej.core.expr.ast.Expr.LitKind.LIST
                && lit.value() instanceof List<?> items && !items.isEmpty())
        {
            if (net.cumba.corej.core.expr.eval.NameListBinding.isNameList(lit))
            {
                // PLAN-elementwise-list-functions D1-A (runbook W1a E2): the name-list binding's
                // members ARE the written names — spliced as they stand, so the classifier sees
                // the columns and a `--` member is resolved by the caller like a written one
                List<net.cumba.corej.core.expr.ast.Expr> written = new ArrayList<>(items.size());
                for (Object o : items)
                {
                    written.add((net.cumba.corej.core.expr.ast.Expr) o);
                }
                return written;
            }
            texts = items;
        }
        else
        {
            return null;
        }
        List<net.cumba.corej.core.expr.ast.Expr> refs = new ArrayList<>(texts.size());
        for (Object t : texts)
        {
            if (!(t instanceof net.cumba.corej.core.expr.ast.Expr.Lit s)
                    || s.kind() != net.cumba.corej.core.expr.ast.Expr.LitKind.STRING
                    || !(s.value() instanceof String name))
            {
                return null;
            }
            try
            {
                refs.add(new net.cumba.corej.core.expr.ast.Expr.Ref(name,
                        net.cumba.corej.core.expr.OperandClassifier.classify(name, -1)));
            }
            catch (net.cumba.corej.core.expr.ExpressionException _)
            {
                return null;
            }
        }
        return refs;
    }


    /** Resolves a Check / Precondition tree's expressions, preserving identity when unchanged. */
    private static CheckCondition resolveTree(CheckCondition condition,
            Map<String, net.cumba.corej.core.expr.ast.Expr> bound, String variablePrefix,
            String datasetPrefix)
    {
        return switch (condition)
        {
        case net.cumba.corej.core.model.CheckConditionAll all ->
        {
            List<CheckCondition> resolved = resolveTreeList(all.getConditions(), bound,
                    variablePrefix, datasetPrefix);
            yield resolved == all.getConditions() ? all
                    : new net.cumba.corej.core.model.CheckConditionAll(resolved);
        }
        case net.cumba.corej.core.model.CheckConditionAny any ->
        {
            List<CheckCondition> resolved = resolveTreeList(any.getConditions(), bound,
                    variablePrefix, datasetPrefix);
            yield resolved == any.getConditions() ? any
                    : new net.cumba.corej.core.model.CheckConditionAny(resolved);
        }
        case net.cumba.corej.core.model.CheckConditionNot not ->
        {
            CheckCondition inner = resolveTree(not.getCondition(), bound, variablePrefix,
                    datasetPrefix);
            yield inner == not.getCondition() ? not
                    : new net.cumba.corej.core.model.CheckConditionNot(inner);
        }
        case net.cumba.corej.core.model.CheckConditionExpression expression ->
        {
            net.cumba.corej.core.expr.ast.Expr resolved = specialiseExpr(expression.expr(), bound,
                    variablePrefix, datasetPrefix);
            yield resolved == expression.expr() ? expression
                    : new net.cumba.corej.core.model.CheckConditionExpression(resolved,
                            net.cumba.corej.core.expr.ExpressionPrinter.print(resolved));
        }
        };
    }


    private static List<CheckCondition> resolveTreeList(List<CheckCondition> conditions,
            Map<String, net.cumba.corej.core.expr.ast.Expr> bound, String variablePrefix,
            String datasetPrefix)
    {
        List<CheckCondition> out = null;
        for (int i = 0; i < conditions.size(); i++)
        {
            CheckCondition resolved = resolveTree(conditions.get(i), bound, variablePrefix,
                    datasetPrefix);
            if (out == null && resolved != conditions.get(i))
            {
                out = new ArrayList<>(conditions.subList(0, i));
            }
            if (out != null)
            {
                out.add(resolved);
            }
        }
        return out == null ? conditions : out;
    }


    /**
     * Resolves a non-{@code Child} {@code Match_Datasets} name through the same data-driven
     * substitution the join builder applies ({@link DatasetIdentity#resolveDatasetWildcard}:
     * {@code SUPP--} / {@code SQAP--} is the supplemental family of the primary's domain code —
     * {@code SUPPLB} on {@code LBCH}, {@code SQAPMH} on {@code APMH}
     * ({@code PLAN-split-supp-resolution} Q6, D6, D10) — Fix #33 SUPP/SQAP parent-stripping
     * included), so the two can never disagree. {@code Child: true} entries are primary-name
     * <em>patterns</em> and stay verbatim (see the class javadoc).
     */
    private static MatchDataset resolveMatchDataset(MatchDataset md, IDataTable table)
    {
        if (md == null || Boolean.TRUE.equals(md.getChild()))
        {
            return md;
        }
        String name = md.getName();
        if (name == null || !name.contains("--"))
        {
            return md;
        }
        String resolved = DatasetIdentity.resolveDatasetWildcard(name, table);
        if (java.util.Objects.equals(resolved, name))
        {
            return md;
        }
        MatchDataset copy = shallowCopy(md, MatchDataset::new);
        copy.setName(resolved);
        return copy;
    }


    /**
     * Resolves a list of plain variable names (grouping keys): a leading non-dotted {@code --}
     * takes the variable prefix, mirroring the runtime grouping resolution it replaces. Returns the
     * same instance when nothing changes.
     */
    private static @Nullable List<String> resolveVariableNames(@Nullable List<String> names,
            String variablePrefix)
    {
        if (names == null)
        {
            return null;
        }
        List<String> out = null;
        for (int i = 0; i < names.size(); i++)
        {
            String name = names.get(i);
            String resolved = name != null && name.startsWith("--") && name.indexOf('.') < 0
                    ? variablePrefix + name.substring(2)
                    : name;
            if (out == null && !java.util.Objects.equals(resolved, name))
            {
                out = new ArrayList<>(names.subList(0, i));
            }
            if (out != null)
            {
                out.add(resolved);
            }
        }
        return out == null ? names : out;
    }


    /** Marker-aware {@code Output_Variables} resolution ({@code !--X} resolves its remainder). */
    private static @Nullable List<String> resolveOutputVariableTokens(@Nullable List<String> names,
            String variablePrefix, String datasetPrefix)
    {
        if (names == null)
        {
            return null;
        }
        List<String> out = null;
        for (int i = 0; i < names.size(); i++)
        {
            String name = names.get(i);
            String resolved = name == null ? null
                    : OutputVariableToken.mapName(name,
                            n -> resolveKnownName(n, variablePrefix, datasetPrefix));
            if (out == null && !java.util.Objects.equals(resolved, name))
            {
                out = new ArrayList<>(names.subList(0, i));
            }
            if (out != null)
            {
                out.add(resolved);
            }
        }
        return out == null ? names : out;
    }


    /** Plain-name list resolution (derived / excluded output variables). */
    private static @Nullable List<String> resolveNames(@Nullable List<String> names,
            String variablePrefix, String datasetPrefix)
    {
        if (names == null)
        {
            return null;
        }
        List<String> out = null;
        for (int i = 0; i < names.size(); i++)
        {
            String name = names.get(i);
            String resolved = resolveName(name, variablePrefix, datasetPrefix);
            if (out == null && !java.util.Objects.equals(resolved, name))
            {
                out = new ArrayList<>(names.subList(0, i));
            }
            if (out != null)
            {
                out.add(resolved);
            }
        }
        return out == null ? names : out;
    }


    /** One name through the shared text policy; identity when nothing changes. */
    private static @Nullable String resolveName(@Nullable String name, String variablePrefix,
            String datasetPrefix)
    {
        return name == null ? null : resolveKnownName(name, variablePrefix, datasetPrefix);
    }


    /**
     * {@link #resolveName} for a name that is known to be there — the same policy, stated as the
     * total function it has always been, so the callers that cannot produce a null (the
     * {@code Output_Variables} token mapper, whose {@code UnaryOperator<String>} contract forbids
     * one) do not have to launder a {@code @Nullable} they can never receive.
     *
     * @param aName
     *            the name to resolve
     * @param aVariablePrefix
     *            the {@code --} substitution for variable names
     * @param aDatasetPrefix
     *            the dataset's own domain, for dataset-qualified names
     * @return the resolved name, or {@code aName} itself when nothing changed
     */
    private static String resolveKnownName(String aName, String aVariablePrefix,
            String aDatasetPrefix)
    {
        if (aName.contains("${"))
        {
            return aName;
        }
        String resolved = CheckConditionTransformer.resolveTextWildcard(aName, aVariablePrefix,
                aDatasetPrefix);
        return resolved == null ? aName : resolved;
    }


    /**
     * A reflective shallow copy of a mutable model bean — <b>every</b> non-static field, so a field
     * added to the model later is carried automatically instead of being silently dropped (the
     * documented hazard of the field-by-field clone sites). Final instance fields cannot be
     * reassigned; the ones on these models are unknown-key collectors ({@code Rule.unknownKeys},
     * {@code MatchDataset.unknownKeys}), whose <em>content</em> is copied into the fresh instance —
     * any other final field fails loudly here rather than being skipped silently.
     */
    private static <T> T shallowCopy(T source, java.util.function.Supplier<T> constructor)
    {
        T copy = constructor.get();
        for (Class<?> k = source.getClass(); k != null && k != Object.class; k = k.getSuperclass())
        {
            for (Field f : k.getDeclaredFields())
            {
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod))
                {
                    continue;
                }
                try
                {
                    f.setAccessible(true);
                    if (Modifier.isFinal(mod))
                    {
                        // A final field cannot be reassigned; the ones on these models are
                        // mutable collections (Rule.unknownKeys, MatchDataset.unknownKeys), whose
                        // CONTENT is copied into the fresh instance's own collection. Anything else
                        // fails loudly rather than
                        // being skipped silently.
                        if (f.get(copy) instanceof java.util.Collection<?> target
                                && f.get(source) instanceof java.util.Collection<?> content)
                        {
                            @SuppressWarnings("unchecked")
                            java.util.Collection<Object> mutable = (java.util.Collection<Object>) target;
                            mutable.addAll(content);
                            continue;
                        }
                        throw new IllegalStateException("RuleSpecialiser.shallowCopy cannot copy"
                                + " final field " + k.getName() + "." + f.getName()
                                + " — teach it the field explicitly");
                    }
                    f.set(copy, f.get(source));
                }
                catch (ReflectiveOperationException e)
                {
                    throw new IllegalStateException("RuleSpecialiser.shallowCopy failed on "
                            + k.getName() + "." + f.getName(), e);
                }
            }
        }
        return copy;
    }

}
