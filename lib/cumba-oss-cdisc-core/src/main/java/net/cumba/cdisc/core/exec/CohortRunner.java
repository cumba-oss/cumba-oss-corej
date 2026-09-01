package net.cumba.cdisc.core.exec;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;

import net.cumba.cdisc.core.model.CheckCondition;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * Executes a cohort of rules with a single shared row pass through the primary table.
 *
 * <h2>What is co-iteration?</h2> Today the engine evaluates each rule independently — for {@code N}
 * cohort-eligible rules sharing the same {@code Match_Datasets} join (e.g. 11 instances of
 * {@code CDISC-AD0591-ADLBC-<column>}), the row loop runs {@code N} times. The cohort runner runs
 * the loop once and per row evaluates every rule's leaf using the shared primary→joined-row index.
 * Each rule still gets its own {@link RuleExecutionResult}; output values, message, and
 * {@code ruleId} are unchanged.
 *
 * <h2>V1 scope — equality cohorts only</h2> Cohort eligibility is enforced by
 * {@link RuleCohortGrouper}. In V1 only unmodified equality / not-equal-to leaves with
 * foreign-dataset value references qualify (the bulk of the cost in real-world ADaM workloads — see
 * {@code PLAN-rule-parallelism.md}).
 *
 * <h2>Parity contract</h2> For every cohort-eligible rule, this runner MUST produce a
 * {@link RuleExecutionResult} that is byte-for-byte equal to what {@link RuleRunner#execute} would
 * produce for the same rule run standalone — {@code ruleId} included: both paths derive it from
 * {@link Rule#effectiveId()}. The per-row predicate replicates the engine's equality semantics
 * ({@link net.cumba.cdisc.core.expr.eval.Primitives#equality} / {@link ScalarSemantics}) — any
 * change there must be mirrored here, and the regression test
 * {@code RuleCohortGrouperTest.cohortAndPerRuleProduceIdenticalResults} guards the contract.
 */
public final class CohortRunner
{

    private CohortRunner()
    {
    }

    /**
     * Per-rule precomputed evaluation context. All fields stay constant across the row loop, so we
     * resolve them once and reuse on every iteration.
     */
    private record MemberCtx(int primaryColIdx, JoinLookup foreignLookup, String foreignCol,
            boolean negate, boolean caseInsensitive, boolean typeInsensitive, BitSet violations,
            CheckCondition resolvedCheck)
    {
    }

    /**
     * Runs a cohort of rules in a single row pass. Dispatches on cohort kind detected from the
     * first rule's shape:
     * <ul>
     * <li>{@link RuleCohortGrouper.CohortKind#EQUALITY} — single-leaf equality vs
     * foreign-dataset.</li>
     * <li>{@link RuleCohortGrouper.CohortKind#MEMBERSHIP} — two-leaf
     * {@code non_empty + is_not_contained_by(literal-array)}.</li>
     * </ul>
     * Returned list is parallel to {@code cohortRules} — one {@link RuleExecutionResult} per cohort
     * rule, in the same order.
     */
    public static List<RuleExecutionResult> executeCohort(List<Rule> cohortRules, IDataTable table,
            DatasetResolver resolver, @Nullable String domainPrefix,
            @Nullable MetadataProvider libraryProvider, @Nullable JoinCache joinCache)
    {
        return executeCohort(cohortRules, table, resolver, domainPrefix, libraryProvider, joinCache,
                Integer.MAX_VALUE);
    }


    /**
     * As
     * {@link #executeCohort(List, IDataTable, DatasetResolver, String, MetadataProvider, JoinCache)}
     * with an explicit per-rule findings cap applied to every cohort member (see
     * {@link EngineLimits} / {@link ViolationSink}). {@link Integer#MAX_VALUE} means unlimited.
     */
    public static List<RuleExecutionResult> executeCohort(List<Rule> cohortRules, IDataTable table,
            DatasetResolver resolver, @Nullable String domainPrefix,
            @Nullable MetadataProvider libraryProvider, @Nullable JoinCache joinCache,
            int maxErrorsPerRule)
    {
        return executeCohort(cohortRules, table, resolver, domainPrefix, libraryProvider, joinCache,
                maxErrorsPerRule, null);
    }


    /**
     * As
     * {@link #executeCohort(List, IDataTable, DatasetResolver, String, MetadataProvider, JoinCache, int)}
     * with the per-dataset {@link ExpressionResultCache}
     * ({@code plans/PLAN-dataset-expression-cache.md}), threaded onto the shared cohort
     * {@link EvaluationContext}. {@code null} disables caching.
     */
    public static List<RuleExecutionResult> executeCohort(List<Rule> cohortRules, IDataTable table,
            DatasetResolver resolver, @Nullable String domainPrefix,
            @Nullable MetadataProvider libraryProvider, @Nullable JoinCache joinCache,
            int maxErrorsPerRule, @Nullable ExpressionResultCache exprCache)
    {
        if (cohortRules == null || cohortRules.isEmpty())
        {
            return List.of();
        }
        Rule first = cohortRules.get(0);
        RuleCohortGrouper.CohortKey key = RuleCohortGrouper.cohortKey(first);
        if (key == null)
        {
            throw new IllegalStateException(
                    "CohortRunner received non-cohort-eligible rule: " + first.effectiveId());
        }
        try
        {
            return switch (key.kind())
            {
            case EQUALITY -> executeEqualityCohort(cohortRules, table, resolver, domainPrefix,
                    libraryProvider, joinCache, maxErrorsPerRule, exprCache);
            case MEMBERSHIP -> executeMembershipCohort(cohortRules, table, resolver, domainPrefix,
                    libraryProvider, maxErrorsPerRule, exprCache);
            };
        }
        catch (InvalidJoinedDomainException e)
        {
            // Fix #358 (ruling 1): a Match_Datasets name resolved to a split domain whose members
            // cannot be unioned. Every cohort member shares the Match_Datasets list (grouper
            // invariant), so all of them report the same ERROR — the same "__error__" sentinel
            // shape RuleRunner.execute produces on its path.
            String errorMsg = String.valueOf(e.getMessage());
            List<RuleExecutionResult> out = new ArrayList<>(cohortRules.size());
            for (Rule rule : cohortRules)
            {
                Violation sentinel = new Violation(0, Map.of("__error__", errorMsg));
                out.add(RuleExecutionResult.builder().ruleId(rule.effectiveId())
                        .message(rule.getOutcome() != null ? rule.getOutcome().getMessage() : null)
                        .violations(List.of(sentinel)).totalRows(table.getRowCount())
                        .status(RuleExecutionStatus.ERROR).statusMessage(errorMsg)
                        .severity(rule.effectiveSeverity()).build());
            }
            return out;
        }
    }


    /**
     * Evaluates {@code rule} natively when it carries a native-supported {@code Expr}, returning
     * the violation {@link BitSet}; {@code null} only when there is no native form, so the caller
     * uses the shared batch predicate (byte-for-byte equal by construction — shared
     * {@link ScalarSemantics}).
     *
     * <p>
     * P7 (decision 2 — NO FALLBACK): a native evaluation error is NOT silently re-run on the batch
     * path. It propagates and the upstream contract converts it into that member's ERROR result —
     * surfacing the native bug instead of masking it.
     * </p>
     */
    private static @Nullable BitSet nativeBitsOrNull(Rule rule, EvaluationContext ctx)
    {
        if (rule.getCheckExpr() == null)
        {
            return null;
        }
        return net.cumba.cdisc.core.expr.eval.NativeExprEvaluator.evaluate(rule.getCheckExpr(),
                ctx);
    }


    private static List<RuleExecutionResult> executeEqualityCohort(List<Rule> cohortRules,
            IDataTable table, DatasetResolver resolver, @Nullable String domainPrefix,
            @Nullable MetadataProvider libraryProvider, @Nullable JoinCache joinCache,
            int maxErrorsPerRule, @Nullable ExpressionResultCache exprCache)
    {
        Rule first = cohortRules.get(0);

        // Phase 2a.5 (mirror of RuleRunner.execute): pre-merge Child:true parent columns. The
        // grouper rejects Child=true Match_Datasets so this is a no-op for any cohort, but
        // calling it keeps a future relaxation symmetric with RuleRunner.
        IDataTable evalTable = ChildMatchPreMerger.preMerge(table, first.getMatchDatasets(),
                resolver, first.effectiveId(), joinCache);

        // Phase 2b: build join lookups once (shared across all cohort rules). All cohort rules
        // share Match_Datasets per the grouper, so taking the first rule's list is sufficient.
        Map<String, JoinLookup> joinedDatasets = RuleRunner.buildJoinedDatasets(
                first.getMatchDatasets(), evalTable, resolver, joinCache, first.effectiveId());

        // Phase 2c: per-rule, resolve `--` prefix substitutions in the rule's leaf and resolve
        // its column indexes / foreign lookup.
        DataTableMeta evalMeta = evalTable.getMetaData();
        List<MemberCtx> members = new ArrayList<>(cohortRules.size());
        for (Rule rule : cohortRules)
        {
            CheckCondition resolvedCheck = rule.getCheck();
            if (resolvedCheck == null)
            {
                throw new IllegalStateException(
                        "CohortRunner received rule with no Check: " + rule.effectiveId());
            }
            // EC-36: Check leaves are variable names, so they take the variable wildcard prefix
            // (AP parent suffix / "" for SUPP), not the CDISC domain code.
            String varPrefix = OperationExecutor.variableWildcardPrefix(table, domainPrefix);
            if (varPrefix != null)
            {
                resolvedCheck = CheckConditionTransformer.resolvePrefixes(resolvedCheck, varPrefix,
                        domainPrefix, rule.effectiveId());
            }
            CheckConditionLeaf leaf = RuleCohortGrouper.extractSingleLeaf(resolvedCheck);
            if (leaf == null || leaf.getName() == null || leaf.getValue() == null)
            {
                throw new IllegalStateException(
                        "CohortRunner received rule with non-single-leaf Check: "
                                + rule.effectiveId());
            }
            String leafName = leaf.getName();
            int primaryIdx = evalMeta.getColumnIndex(leafName);
            if (primaryIdx < 0)
            {
                // Defensive — the grouper requires a plain primary-table column, but if a rule
                // slips through whose column doesn't exist on this dataset we bail loudly so
                // the dispatcher can be fixed rather than silently dropping violations.
                throw new IllegalStateException("Cohort rule " + rule.effectiveId()
                        + " references column '" + leafName + "' which is not present in dataset "
                        + evalTable.getMetaData().getName());
            }
            String foreignRef = leaf.getValue().asText();
            int dot = foreignRef.indexOf('.');
            String foreignDs = foreignRef.substring(0, dot);
            String foreignCol = foreignRef.substring(dot + 1);
            JoinLookup lookup = joinedDatasets.get(foreignDs);
            if (lookup == null)
            {
                throw new IllegalStateException("Cohort rule " + rule.effectiveId() + " references "
                        + foreignDs + ".* but no Match_Datasets join was built for " + foreignDs);
            }
            String op = leaf.getOperator();
            boolean caseInsensitive = "equal_to_case_insensitive".equals(op)
                    || "not_equal_to_case_insensitive".equals(op);
            boolean negate = "not_equal_to".equals(op)
                    || "not_equal_to_case_insensitive".equals(op);
            boolean typeInsensitive = Boolean.TRUE.equals(leaf.getTypeInsensitive())
                    || caseInsensitive;
            members.add(new MemberCtx(primaryIdx, lookup, foreignCol, negate, caseInsensitive,
                    typeInsensitive, new BitSet(), resolvedCheck));
        }

        // Shared EvaluationContext — used by extractOutputValues in phase 4.
        EvaluationContext ctx = EvaluationContext.builder().table(evalTable).variables(Map.of())
                .datasetResolver(resolver).domainPrefix(domainPrefix)
                .variableWildcardPrefix(
                        OperationExecutor.variableWildcardPrefix(evalTable, domainPrefix))
                .domainName(evalTable.getMetaData().getName()).joinedDatasets(joinedDatasets)
                .evaluationDomain(first.getEvaluationDomain()).maxErrorsPerRule(maxErrorsPerRule)
                .libraryProvider(libraryProvider).exprCache(exprCache).build();

        // Native pass: any member carrying a native-supported Expr is evaluated by the native
        // backend against the shared ctx; the rest take the batch row loop below.
        boolean[] nativeMember = new boolean[members.size()];
        for (int i = 0; i < members.size(); i++)
        {
            BitSet nb = nativeBitsOrNull(cohortRules.get(i), ctx);
            NativeExecutionRecorder.record(cohortRules.get(i).effectiveId(),
                    NativeExecutionRecorder.Backend.NATIVE);
            if (nb != null)
            {
                members.get(i).violations().or(nb);
                nativeMember[i] = true;
            }
        }

        // Phase 3: single row loop over the remaining (batch) members.
        int rowCount = Math.toIntExact(evalTable.getRowCount());
        for (int r = 0; r < rowCount; r++)
        {
            for (int i = 0; i < members.size(); i++)
            {
                if (nativeMember[i])
                {
                    continue;
                }
                MemberCtx mc = members.get(i);
                if (evaluatesAsViolation(evalTable, mc, r))
                {
                    mc.violations().set(r);
                }
            }
        }

        return materialiseResults(cohortRules, evalTable, ctx,
                members.stream().map(MemberCtx::violations).toList(),
                members.stream().map(MemberCtx::resolvedCheck).toList());
    }


    /**
     * Runs a membership cohort. Each rule has its own primary column and its own term list, but all
     * share the row pass — per row we read the cell once for each cohort rule and check membership
     * in that rule's pre-built term Set.
     *
     * <p>
     * The per-row predicate is "value present and not in the term set". Same drift contract as the
     * equality runner.
     */
    private static List<RuleExecutionResult> executeMembershipCohort(List<Rule> cohortRules,
            IDataTable table, DatasetResolver resolver, @Nullable String domainPrefix,
            @Nullable MetadataProvider libraryProvider, int maxErrorsPerRule,
            @Nullable ExpressionResultCache exprCache)
    {
        Rule first = cohortRules.get(0);
        DataTableMeta meta = table.getMetaData();
        int rowCount = Math.toIntExact(table.getRowCount());

        // Per-rule context: primary column index + term Set (pre-built, mirrors
        // the same $-reference contract the compiled plan uses).
        record MembershipMemberCtx(int primaryColIdx, java.util.Set<String> terms,
                BitSet violations, CheckCondition resolvedCheck)
        {
        }

        List<MembershipMemberCtx> members = new ArrayList<>(cohortRules.size());
        for (Rule rule : cohortRules)
        {
            CheckCondition resolvedCheck = rule.getCheck();
            if (resolvedCheck == null)
            {
                throw new IllegalStateException(
                        "CohortRunner (membership) received rule with no Check: "
                                + rule.effectiveId());
            }
            // EC-36: Check leaves are variable names, so they take the variable wildcard prefix
            // (AP parent suffix / "" for SUPP), not the CDISC domain code.
            String varPrefix = OperationExecutor.variableWildcardPrefix(table, domainPrefix);
            if (varPrefix != null)
            {
                resolvedCheck = CheckConditionTransformer.resolvePrefixes(resolvedCheck, varPrefix,
                        domainPrefix, rule.effectiveId());
            }
            // After `--` resolution we still expect a 2-leaf all() with the same shape — the
            // grouper guarantees this, but defensive checks here surface drift loudly.
            if (!(resolvedCheck instanceof CheckConditionAll all) || all.getConditions().size() != 2
                    || !(all.getConditions().get(0) instanceof CheckConditionLeaf nonEmptyLeaf)
                    || !(all.getConditions().get(1) instanceof CheckConditionLeaf membershipLeaf)
                    || nonEmptyLeaf.getName() == null)
            {
                throw new IllegalStateException(
                        "CohortRunner (membership) received rule whose Check is not a 2-leaf"
                                + " all(): " + rule.effectiveId());
            }
            String columnName = nonEmptyLeaf.getName();
            int primaryIdx = meta.getColumnIndex(columnName);
            if (primaryIdx < 0)
            {
                throw new IllegalStateException("Cohort rule " + rule.effectiveId() + " references"
                        + " column '" + columnName + "' which is not present in dataset "
                        + meta.getName());
            }
            // Build the term set from the literal JSON array, using the same $-reference
            // contract the compiled plan uses.
            com.fasterxml.jackson.databind.JsonNode value = membershipLeaf.getValue();
            if (value == null)
            {
                throw new IllegalStateException(
                        "Cohort membership rule " + rule.effectiveId() + " has no value list");
            }
            java.util.Set<String> terms = java.util.HashSet.newHashSet(value.size());
            for (com.fasterxml.jackson.databind.JsonNode element : value)
            {
                terms.add(element.asText());
            }
            members.add(new MembershipMemberCtx(primaryIdx, terms, new BitSet(), resolvedCheck));
        }

        // Shared EvaluationContext — used by extractOutputValues in materialisation.
        EvaluationContext ctx = EvaluationContext.builder().table(table).variables(Map.of())
                .datasetResolver(resolver).domainPrefix(domainPrefix)
                .variableWildcardPrefix(
                        OperationExecutor.variableWildcardPrefix(table, domainPrefix))
                .domainName(meta.getName()).joinedDatasets(Map.of())
                .evaluationDomain(first.getEvaluationDomain()).maxErrorsPerRule(maxErrorsPerRule)
                .libraryProvider(libraryProvider).exprCache(exprCache).build();

        // Native pass: members carrying a native-supported Expr evaluate natively; the rest below.
        boolean[] nativeMember = new boolean[members.size()];
        for (int i = 0; i < members.size(); i++)
        {
            BitSet nb = nativeBitsOrNull(cohortRules.get(i), ctx);
            NativeExecutionRecorder.record(cohortRules.get(i).effectiveId(),
                    NativeExecutionRecorder.Backend.NATIVE);
            if (nb != null)
            {
                members.get(i).violations().or(nb);
                nativeMember[i] = true;
            }
        }

        // Single row loop over the remaining (batch) members.
        for (int r = 0; r < rowCount; r++)
        {
            for (int i = 0; i < members.size(); i++)
            {
                if (nativeMember[i])
                {
                    continue;
                }
                MembershipMemberCtx mc = members.get(i);
                IDataValue dv = table.getColumn(mc.primaryColIdx()).getDataValue(r);
                if (OperatorRegistry.isMissing(dv))
                {
                    continue; // non_empty fails → no violation, regardless of membership leaf
                }
                String s = dv.getValueAsString();
                if (!mc.terms().contains(s))
                {
                    mc.violations().set(r);
                }
            }
        }

        return materialiseResults(cohortRules, table, ctx,
                members.stream().map(MembershipMemberCtx::violations).toList(),
                members.stream().map(MembershipMemberCtx::resolvedCheck).toList());
    }


    /**
     * Shared post-loop step: build {@link RuleExecutionResult} objects from the per-rule violation
     * BitSets, materialising output values with {@link RuleRunner#extractOutputValues} — same call
     * as the per-rule path so output maps are byte-identical.
     */
    private static List<RuleExecutionResult> materialiseResults(List<Rule> cohortRules,
            IDataTable evalTable, EvaluationContext ctx, List<BitSet> perRuleViolations,
            List<CheckCondition> resolvedChecks)
    {
        List<RuleExecutionResult> results = new ArrayList<>(cohortRules.size());
        long totalRows = evalTable.getRowCount();
        DataTableMeta evalMeta = evalTable.getMetaData();
        for (int i = 0; i < cohortRules.size(); i++)
        {
            Rule rule = cohortRules.get(i);
            String ruleId = rule.effectiveId();
            String message = rule.getOutcome() != null ? rule.getOutcome().getMessage() : null;
            List<String> outputVars = outputVarsOf(rule, resolvedChecks.get(i), evalMeta);
            BitSet bits = perRuleViolations.get(i);
            // Count every violating row but materialise at most the per-rule cap (see
            // ViolationSink).
            ViolationSink violations = new ViolationSink(ctx.getMaxErrorsPerRule());
            int card = bits.cardinality();
            int taken = 0;
            for (int r = bits.nextSetBit(0); r >= 0
                    && violations.wantsMore(); r = bits.nextSetBit(r + 1))
            {
                Map<String, String> values = RuleRunner.extractOutputValues(evalTable, ctx,
                        outputVars, r);
                violations.store(new Violation(evalTable.getRealRowIndex(r), values));
                taken++;
            }
            violations.recordSkipped(card - taken);
            // ⚠ Plan C: the cohort path builds its results from scratch rather than going through
            // RuleRunner.execute, so RuleRunner.stampSeverity never sees them. Without this the
            // rule's authored Severity is silently lost and every cohort-executed finding falls
            // back to ERROR — measured: 15 rules / 944 finding rows on testdata/study.
            results.add(RuleExecutionResult.builder().ruleId(ruleId).message(message)
                    .violations(violations.stored()).totalViolationCount(violations.total())
                    .totalRows(totalRows).status(RuleExecutionStatus.EXECUTED)
                    .severity(rule.effectiveSeverity()).build());
        }
        return results;
    }


    /**
     * Replicates the engine's per-row equality predicate.
     * <p>
     * <strong>Drift warning:</strong> any change to
     * {@link net.cumba.cdisc.core.expr.eval.Primitives#equality} or its dependents
     * ({@link OperatorRegistry#isMissing(IDataValue)}, {@link ScalarSemantics}) must be reflected
     * here. The cohort/per-rule parity test in
     * {@code RuleCohortGrouperTest.cohortAndPerRuleProduceIdenticalResults} guards the contract.
     */
    private static boolean evaluatesAsViolation(IDataTable evalTable, MemberCtx mc, int row)
    {
        IDataValue dv = evalTable.getColumn(mc.primaryColIdx()).getDataValue(row);
        String target = mc.foreignLookup().lookup(evalTable, row, mc.foreignCol());

        boolean dvMissing = OperatorRegistry.isMissing(dv);
        boolean targetMissing = (target == null) || target.isEmpty();
        // Both missing → equal → not a violation for not_equal_to, no fire for equal_to either.
        if (dvMissing && targetMissing)
        {
            return false;
        }
        // Exactly one missing → not equal → violation iff this is a not_equal_to operator.
        if (dvMissing || targetMissing)
        {
            return mc.negate();
        }
        boolean equal;
        if (!mc.typeInsensitive())
        {
            // ScalarSemantics: numeric compare if target is a Number, else
            // string compare. JoinLookup.lookup always returns String, so this collapses to
            // dv.getValueAsString().equals(target).
            equal = dv.getValueAsString().equals(target);
        }
        else
        {
            String a = dv.getValueAsString();
            equal = mc.caseInsensitive() ? a.equalsIgnoreCase(target) : a.equals(target);
        }
        return mc.negate() != equal;
    }


    /**
     * The projected output-variable list for one cohort member — the SAME computation the per-rule
     * path runs, delegated rather than restated so the byte-identity contract cannot drift again.
     *
     * <p>
     * ⚠ This used to be {@code rule.effectiveOutputVariablesOrAuthored()} alone, which silently
     * dropped the {@code Fix #15} inference fallback and its E-2 exclusion filter: a rule whose
     * effective list is empty after exclusions projected {@code {}} here and the exclusion-filtered
     * Check-leaf inference on the per-rule path. Found by the {@code Fix #354} review (2026-08-23).
     * The check handed in must be the member's <em>resolved</em> check, as
     * {@link RuleRunner#projectedOutputVariables} requires.
     */
    private static List<String> outputVarsOf(Rule rule, CheckCondition resolvedCheck,
            DataTableMeta meta)
    {
        return RuleRunner.projectedOutputVariables(rule, resolvedCheck, meta);
    }

}
