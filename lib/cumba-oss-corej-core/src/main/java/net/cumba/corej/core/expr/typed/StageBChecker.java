package net.cumba.corej.core.expr.typed;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import net.cumba.corej.core.exec.ScopeVariableEntry;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.ColumnTypeGate;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Operation;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.VariableRequirement;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;

/**
 * The stage-B checker of the typed-expression specification
 * ({@code plans/SPEC-typed-expression-engine.md} §2): runs <b>once per (rule × dataset), at bind
 * time, knowing the dataset's columns and their types</b> (D10), on the <b>specialised</b> rule
 * (D77 — the engine receives a concrete rule). It is {@link StageAChecker}'s per-dataset sibling:
 * armed findings file a bind error for this (rule, dataset) — never per row (D35) — detected and
 * named <b>per binding</b> (D41: the message says which variable), observe-only findings are logged
 * and offered to the measurement observer.
 *
 * <p>
 * ⭐⭐ <b>D15 — this checker ABSORBS the shipped {@code ColumnTypeGate}, it does not grow a second
 * gate beside it.</b> The division of labour, stated once: the <b>gate</b> stays the armed
 * detection of a column-type mismatch — it raises from the evaluation plans against the actually
 * resolved vectors (joined dotted references included, J7), and {@code RuleRunner}'s single catch
 * site (D74a) turns the first raise into the per-(rule, dataset) {@code ERROR}. The <b>checker</b>
 * contributes what the throw-on-first-mismatch shape structurally cannot: per-binding accumulation
 * over the {@link TypeExpectations gate's own expectation notions} (D76a), the D76 absent-column
 * default those expectations decide (the one case the gate deliberately skips — "an absent-column
 * fold never set an expectation"), and the bind-time home the remaining stage-B rows of spec §9
 * grow into. Dotted joined references are deliberately <b>not</b> shadowed here (no resolver at
 * this seam) — they stay gate-only until phase 5b.
 * </p>
 *
 * <p>
 * <b>Phase 4 contract: no verdict moves.</b> Every armed kind is measured at zero over the shipped
 * corpus runs (pinned and HEAD) <b>and</b> the rules-repo build (the {@code rulespec/} parity
 * harness is a second population the corpus census never sees — D102c), so the armed paths below
 * are live machinery with an empty population, not behaviour changes.
 * </p>
 */
public final class StageBChecker
{

    private static final System.Logger LOGGER = System.getLogger(StageBChecker.class.getName());

    /**
     * Measurement hook: when set, every checked (rule × dataset)'s report is offered to the
     * observer. Used by the corpus measurement runs that gate arming (see {@link StageBErrorKind});
     * never set in production.
     */
    private static final AtomicReference<@Nullable BiConsumer<Rule, StageBReport>> OBSERVER = new AtomicReference<>();

    private StageBChecker()
    {
    }


    /** Sets (or clears) the measurement observer. Test / measurement use only. */
    public static void setObserver(@Nullable BiConsumer<Rule, StageBReport> observer)
    {
        OBSERVER.set(observer);
    }


    /**
     * Checks one specialised rule against one dataset and reports: armed findings are the caller's
     * bind error (spec §9 — bind error, once per (rule, dataset), per binding), observe-only
     * findings are logged at DEBUG. Never throws: a checker failure is itself a finding
     * ({@link StageBErrorKind#CHECKER_FAILURE}) and never errors the execution.
     *
     * @param rule
     *            the rule, after {@code RuleSpecialiser.specialise}
     * @param table
     *            the primary dataset the rule will execute against
     * @param concreteContract
     *            whether specialisation was applicable here (a resolvable domain context) — only
     *            then does D77b's "no {@code --} survives" contract hold and get asserted
     * @return the report
     */
    public static StageBReport runAndApply(Rule rule, IDataTable table, boolean concreteContract)
    {
        return runAndApply(rule, table, concreteContract, null, Set.of());
    }


    /**
     * The widened seam of phase 5b-J (D104d): {@link #runAndApply(Rule, IDataTable, boolean)} plus
     * the run's foreign-dataset inventory and the datasets whose readings {@code AbsentDatasetSkip}
     * already suppressed for this execution — the two inputs the {@code Filter} (D89) and
     * {@code _matched_} rows of spec §9 need and the primary table cannot supply.
     */
    public static StageBReport runAndApply(Rule rule, IDataTable table, boolean concreteContract,
            @Nullable ForeignDatasetInventory foreignInventory, Set<String> suppressedDatasets)
    {
        StageBReport report = check(rule, table, concreteContract, foreignInventory,
                suppressedDatasets);
        if (!report.observedFindings().isEmpty() && LOGGER.isLoggable(System.Logger.Level.DEBUG))
        {
            LOGGER.log(System.Logger.Level.DEBUG, "stage B observations for {0} on {1}: {2}",
                    rule.effectiveId(), table.getMetaData().getName(), report.observedFindings());
        }
        BiConsumer<Rule, StageBReport> observer = OBSERVER.get();
        if (observer != null)
        {
            observer.accept(rule, report);
        }
        return report;
    }


    /** Checks one specialised rule against one dataset without logging or observers. */
    public static StageBReport check(Rule rule, IDataTable table, boolean concreteContract)
    {
        return check(rule, table, concreteContract, null, Set.of());
    }


    /**
     * The widened-seam variant (D104d, phase 5b-J) — see
     * {@link #runAndApply(Rule, IDataTable, boolean, ForeignDatasetInventory, Set)}. A {@code null}
     * {@code foreignInventory} disables the checks that need it (the Filter and {@code _matched_}
     * bindings) rather than mis-reporting every foreign dataset as unresolvable.
     */
    public static StageBReport check(Rule rule, IDataTable table, boolean concreteContract,
            @Nullable ForeignDatasetInventory foreignInventory, Set<String> suppressedDatasets)
    {
        List<StageBFinding> findings = new ArrayList<>();
        List<String> skips = new ArrayList<>();
        try
        {
            List<Expr> roots = expressionRoots(rule);
            DataTableMeta meta = table.getMetaData();
            checkColumnTypes(rule, roots, meta, findings);
            if (concreteContract)
            {
                checkUnresolvedWildcards(rule, roots, findings);
            }
            if (foreignInventory != null)
            {
                checkFilterBindings(rule, foreignInventory, findings, skips);
                checkMatchedFlagBindings(rule, roots, foreignInventory, suppressedDatasets,
                        findings, skips);
            }
        }
        catch (RuntimeException ex)
        {
            // A checker defect must never error an execution — that would be an evaluation
            // change caused by the instrument (the phase-2 constraint, unchanged in phase 4).
            findings.add(new StageBFinding(StageBErrorKind.CHECKER_FAILURE, "-",
                    "stage-B checker failed on rule " + rule.effectiveId() + ": " + ex));
        }
        return new StageBReport(findings, skips);
    }


    /**
     * The specialised expression surfaces stage B walks: every level's raised {@code Expr} (the
     * strictest-level {@code checkExpr} for a single-level rule) plus the precondition. A rule with
     * no native form contributes nothing — the checker can only get quieter, never wrong.
     *
     * <p>
     * Public since the D76 absent-column default landed in the engine: {@code RuleRunner} computes
     * the rule's {@link TypeExpectations#numericDefaultColumns()} over exactly these roots when it
     * builds the {@code EvaluationContext}, so the checker's <em>report</em> of the default and the
     * engine's <em>application</em> of it read one and the same root collection.
     * </p>
     */
    public static List<Expr> expressionRoots(Rule rule)
    {
        List<Expr> roots = new ArrayList<>();
        Map<net.cumba.datatable.report.Severity, Expr> levels = rule.getCheckLevelExprs();
        if (levels != null)
        {
            roots.addAll(levels.values());
        }
        else if (rule.getCheckExpr() != null)
        {
            roots.add(rule.getCheckExpr());
        }
        if (rule.getPreconditionExpr() != null)
        {
            roots.add(rule.getPreconditionExpr());
        }
        return roots;
    }

    // ------------------------------------------------------------------
    // D15 / D76 — column types and absent columns
    // ------------------------------------------------------------------


    /**
     * The column-type half: for every binding carrying a {@link TypeExpectations gate expectation},
     * a resolved primary column of the conflicting kind is a
     * {@link StageBErrorKind#COLUMN_TYPE_MISMATCH} finding naming that binding (observe-only — the
     * armed half is the gate, see the class Javadoc), a numeric column under a
     * {@code date()}/{@code time()} conversion is the D55 observation, and an <b>absent</b>
     * foldable column is recorded with its D76 default — numeric-expected ⇒ {@code number} ⇒
     * {@code MissingValue.MIS}, otherwise {@code string} ⇒ {@code ""} (D34 #3/#4). The expectation
     * is the stage-A fact (D76c) — nothing here infers a type from the dataset.
     */
    private static void checkColumnTypes(Rule rule, List<Expr> roots, DataTableMeta meta,
            List<StageBFinding> findings)
    {
        if (roots.isEmpty())
        {
            return;
        }
        TypeExpectations expectations = TypeExpectations.of(roots);
        boolean hasJoins = rule.getMatchDatasets() != null && !rule.getMatchDatasets().isEmpty();
        for (String column : expectations.valueReadColumns())
        {
            int idx = meta.getColumnIndex(column);
            if (idx < 0)
            {
                // The absent-column fold candidate (EC-38). An unqualified name may still
                // resolve through a Match_Datasets join — resolution this seam cannot see — so
                // the record says so rather than overclaiming.
                String defaultType = expectations.numericExpected(column)
                        ? "number (missing = MissingValue.MIS)"
                        : "string (missing = \"\")";
                findings.add(new StageBFinding(StageBErrorKind.ABSENT_COLUMN, column,
                        "absent from " + meta.getName() + "; D76 default type " + defaultType
                                + (hasJoins ? " (unless a Match_Datasets join carries it)" : "")));
                continue;
            }
            ColumnTypeGate.Kind kind = ColumnTypeGate.kindOf(meta.getColumn(idx).getType());
            if (kind == null)
            {
                continue;
            }
            Set<TypeExpectations.Expectation> expected = expectations.expectationsOf(column);
            if (kind == ColumnTypeGate.Kind.CHARACTER
                    && expected.contains(TypeExpectations.Expectation.NUMERIC))
            {
                findings.add(new StageBFinding(StageBErrorKind.COLUMN_TYPE_MISMATCH, column,
                        column + " is declared Char but a numeric-expected position reads it — "
                                + "author num(" + column + ") if the rule means a numeric read"));
            }
            if (kind == ColumnTypeGate.Kind.NUMERIC
                    && expected.contains(TypeExpectations.Expectation.CHARACTER))
            {
                findings.add(new StageBFinding(StageBErrorKind.COLUMN_TYPE_MISMATCH, column,
                        column + " is declared Num but a character-expected position reads it — "
                                + "a numeric column's text form depends on formatting"));
            }
            if (kind == ColumnTypeGate.Kind.NUMERIC
                    && expected.contains(TypeExpectations.Expectation.ISO_TEXT))
            {
                findings.add(new StageBFinding(StageBErrorKind.DATE_CONVERSION_OVER_NUMERIC, column,
                        column + " is declared Num but date()/time() reads it as ISO-8601 text "
                                + "(D55) — date_from_sas_days(...) / date_from_sas_datetime(...) "
                                + "is the ruled rewrite once those functions exist"));
            }
        }
        for (TypeExpectations.EqualityPair pair : expectations.equalityPairs())
        {
            int li = meta.getColumnIndex(pair.left());
            int ri = meta.getColumnIndex(pair.right());
            if (li < 0 || ri < 0)
            {
                continue;
            }
            ColumnTypeGate.Kind lk = ColumnTypeGate.kindOf(meta.getColumn(li).getType());
            ColumnTypeGate.Kind rk = ColumnTypeGate.kindOf(meta.getColumn(ri).getType());
            if (lk != null && rk != null && lk != rk)
            {
                String charSide = lk == ColumnTypeGate.Kind.CHARACTER ? pair.left() : pair.right();
                findings.add(new StageBFinding(StageBErrorKind.COLUMN_TYPE_MISMATCH, pair.left(),
                        pair.left() + " and " + pair.right() + " compare with disagreeing "
                                + "declared kinds — author num(" + charSide
                                + ") if the rule means a numeric comparison"));
            }
        }
    }

    // ------------------------------------------------------------------
    // D77b — no `--` survives specialisation
    // ------------------------------------------------------------------


    /**
     * D77b's output contract, asserted per (rule × dataset): after specialisation no {@code --}
     * remains in the expression surfaces, the {@code resolvePrefixes}-owned Operation name fields,
     * non-{@code Child} {@code Match_Datasets} names, {@code Grouping} or {@code Output_Variables}.
     * Exempt by ruling: {@code name_pattern=} contents (D92b — a regex, not a wildcard), the
     * {@code variable_count} family's name operand (D92a — the template is stashed in
     * {@code originalName}, which is not scanned), {@code Child: true} match names (D93b —
     * patterns, not references) and {@code minuend_match} tokens (resolved per side at evaluation).
     * Free-text surfaces (data literals outside name positions, operation {@code expression} text)
     * are deliberately not scanned — the EC-36/D2' review measured that text-level {@code --} scans
     * delete genuine findings on literals like {@code "DOSE NOT CHANGED--SEE CRF"}.
     */
    private static void checkUnresolvedWildcards(Rule rule, List<Expr> roots,
            List<StageBFinding> findings)
    {
        Set<String> hits = new LinkedHashSet<>();
        for (Expr root : roots)
        {
            collectExprWildcards(root, hits);
        }
        List<Operation> operations = rule.getOperations();
        if (operations != null)
        {
            for (Operation op : operations)
            {
                if (op == null)
                {
                    continue;
                }
                addWildcard(hits, op.getName());
                addWildcard(hits, op.getDomain());
                addWildcards(hits, op.getGroup());
                addWildcards(hits, op.getNames());
                addWildcard(hits, op.getExternalDictionaryTermVariable());
                addWildcards(hits, op.getQualifyingAnyPopulated());
                addWildcard(hits, op.getDictionaryParent());
                Map<String, Object> filter = op.getFilter();
                if (filter != null)
                {
                    filter.keySet().forEach(k -> addWildcard(hits, k));
                }
            }
        }
        List<MatchDataset> matches = rule.getMatchDatasets();
        if (matches != null)
        {
            for (MatchDataset match : matches)
            {
                if (!Boolean.TRUE.equals(match.getChild()))
                {
                    addWildcard(hits, match.getName());
                }
            }
        }
        addWildcards(hits, rule.effectiveGroupingVariables());
        addWildcards(hits, rule.effectiveOutputVariablesOrAuthored());
        for (String hit : hits)
        {
            findings.add(new StageBFinding(StageBErrorKind.UNRESOLVED_WILDCARD, hit,
                    "'" + hit + "' still carries a `--` wildcard after specialisation — a "
                            + "specialiser defect (D77b), not an authoring one"));
        }
    }


    private static void collectExprWildcards(Expr e, Set<String> hits)
    {
        switch (e)
        {
        case Expr.And a -> a.parts().forEach(p -> collectExprWildcards(p, hits));
        case Expr.Or o -> o.parts().forEach(p -> collectExprWildcards(p, hits));
        case Expr.Not n -> collectExprWildcards(n.inner(), hits);
        case Expr.Binary b ->
        {
            collectExprWildcards(b.left(), hits);
            collectExprWildcards(b.right(), hits);
        }
        case Expr.Lit lit ->
        {
            if (lit.kind() == Expr.LitKind.LIST)
            {
                @SuppressWarnings("unchecked")
                List<Expr> items = (List<Expr>) lit.value();
                items.forEach(i -> collectExprWildcards(i, hits));
            }
        }
        case Expr.Ref r ->
        {
            if (r.kind() == OperandKind.WILDCARD_COLUMN && r.name().contains("--"))
            {
                // ⚠ WILDCARD_COLUMN alone is not enough: the kind also covers `*`/`**` and ADaM
                // capture letters, which are resolved by other machinery — only the `--` prefix
                // family is the D77b contract.
                hits.add(r.name());
            }
        }
        case Expr.Call c ->
        {
            // D92a: the variable_count family's first argument is the pre-resolution template on
            // purpose; D92b: name_pattern= contents are a regex, not a wildcard.
            boolean countFamily = "variable_count".equals(c.name())
                    || "variable_value_count".equals(c.name());
            List<Expr> args = c.args();
            for (int i = countFamily ? 1 : 0; i < args.size(); i++)
            {
                collectExprWildcards(args.get(i), hits);
            }
            // D93c's positive counterpart: a name-shaped string literal in a presence probe
            // (var_exists("--X"), 309 corpus sites) IS specialised, so one surviving here is the
            // same defect. Free-text literals elsewhere are deliberately not scanned — see the
            // method Javadoc.
            if (net.cumba.corej.core.expr.eval.BroadcastFold.isExistsCall(c)
                    && args.get(0) instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.STRING
                    && ((String) lit.value()).contains("--"))
            {
                hits.add((String) lit.value());
            }
            c.kwargs().forEach((key, value) ->
            {
                if (!"name_pattern".equals(key))
                {
                    collectExprWildcards(value, hits);
                }
            });
        }
        }
    }


    private static void addWildcard(Set<String> hits, @Nullable String candidate)
    {
        if (candidate != null && candidate.contains("--"))
        {
            hits.add(candidate);
        }
    }


    private static void addWildcards(Set<String> hits, @Nullable List<String> candidates)
    {
        if (candidates != null)
        {
            candidates.forEach(c -> addWildcard(hits, c));
        }
    }

    // ------------------------------------------------------------------
    // D89 / D89a — the Match_Datasets Filter binding (future-armed seam)
    // ------------------------------------------------------------------


    /**
     * The production half of the D89 seam, wired by phase 5b-J: per {@code Match_Datasets} entry
     * carrying a {@code Filter}, parse it, collect its plain column references, resolve the joined
     * dataset's inventory through the widened seam (D104d) and route each reference through
     * {@link #checkFilterBinding}. An entry whose dataset does not resolve at all yields an
     * <b>empty</b> inventory here — then every filter column is unresolvable, which is D89a's
     * <i>"one declaration covers both absences"</i>: the qualified {@code Requirements.Variables}
     * entry decides skip-vs-error for the absent column and the absent dataset alike.
     */
    private static void checkFilterBindings(Rule rule, ForeignDatasetInventory foreignInventory,
            List<StageBFinding> findings, List<String> skips)
    {
        List<MatchDataset> matches = rule.getMatchDatasets();
        if (matches == null)
        {
            return;
        }
        for (MatchDataset match : matches)
        {
            List<String> filterColumns = filterColumnReferences(match);
            if (!filterColumns.isEmpty())
            {
                String name = String.valueOf(match.getName());
                Set<String> rightColumns = foreignInventory.columnsOf(name);
                checkFilterBinding(name, filterColumns,
                        rightColumns == null ? Set.of() : rightColumns,
                        rule.effectiveVariableRequirement(), findings, skips);
            }
        }
    }


    /**
     * The plain column references of one {@code Match_Datasets} entry's parsed {@code Filter}
     * (phase 5b-J). Non-column reference kinds (dotted, {@code $}, wildcard, the flag) are stage
     * A's load errors ({@code FILTER_LEFT_REFERENCE} / {@code FILTER_INVALID}) and are not
     * re-reported here; an unparseable filter is likewise already parked at load, so it contributes
     * nothing.
     */
    private static List<String> filterColumnReferences(MatchDataset match)
    {
        Expr parsed;
        try
        {
            parsed = match.filterExpr();
        }
        catch (net.cumba.corej.core.expr.ExpressionException ex)
        {
            return List.of(); // parked at load (stage A FILTER_INVALID); nothing to bind-check
        }
        if (parsed == null)
        {
            return List.of();
        }
        Set<String> columns = new LinkedHashSet<>();
        collectPlainColumns(parsed, columns);
        return List.copyOf(columns);
    }


    private static void collectPlainColumns(Expr e, Set<String> out)
    {
        switch (e)
        {
        case Expr.And a -> a.parts().forEach(p -> collectPlainColumns(p, out));
        case Expr.Or o -> o.parts().forEach(p -> collectPlainColumns(p, out));
        case Expr.Not n -> collectPlainColumns(n.inner(), out);
        case Expr.Binary b ->
        {
            collectPlainColumns(b.left(), out);
            collectPlainColumns(b.right(), out);
        }
        case Expr.Lit lit ->
        {
            if (lit.kind() == Expr.LitKind.LIST && lit.value() instanceof List<?> items)
            {
                for (Object item : items)
                {
                    if (item instanceof Expr inner)
                    {
                        collectPlainColumns(inner, out);
                    }
                }
            }
        }
        case Expr.Ref r ->
        {
            if (r.kind() == OperandKind.COLUMN)
            {
                out.add(r.name());
            }
        }
        case Expr.Call c ->
        {
            c.args().forEach(a -> collectPlainColumns(a, out));
            c.kwargs().values().forEach(a -> collectPlainColumns(a, out));
        }
        }
    }


    /**
     * The {@code _matched_} binding check of phase 5b-J: a flag whose joined dataset does not
     * resolve in this run must never reach evaluation silently — an empty match verdict under
     * {@code not} is the absent-dataset flood the flag exists to remove (the 2026-08 plan's Q1).
     * Precedence, mirroring the run's own gates: a dataset {@code AbsentDatasetSkip} already
     * suppressed needs nothing here (the reader is folded and the absence is reported once, by the
     * presence rule); a dataset the rule <b>declares</b> — {@code Requirements.Datasets}, or any
     * qualified {@code Requirements.Variables} entry on it — skips cleanly (D89a; in practice the
     * upstream Requirements gates skip first and this branch is the belt behind them); anything
     * else is the armed {@link StageBErrorKind#MATCHED_FLAG_UNRESOLVABLE} bind error. <i>Declare ⇒
     * skip; don't declare ⇒ error. Never silent</i> (D89b).
     */
    private static void checkMatchedFlagBindings(Rule rule, List<Expr> roots,
            ForeignDatasetInventory foreignInventory, Set<String> suppressedDatasets,
            List<StageBFinding> findings, List<String> skips)
    {
        Set<String> flags = new LinkedHashSet<>();
        for (Expr root : roots)
        {
            collectMatchedFlagRefs(root, flags);
        }
        for (String flag : flags)
        {
            String dataset = flag.substring(0, flag.indexOf('.'));
            if (foreignInventory.columnsOf(dataset) != null)
            {
                continue; // resolvable — the join will be built and the flag answered
            }
            if (suppressedDatasets.contains(dataset))
            {
                continue; // reader already folded; the absence is reported by the presence rule
            }
            if (declaresDataset(rule, dataset))
            {
                skips.add("Rule skipped — " + flag + "'s joined dataset " + dataset
                        + " is not available and the rule declares it in Requirements (D89a)");
            }
            else
            {
                findings.add(new StageBFinding(StageBErrorKind.MATCHED_FLAG_UNRESOLVABLE, flag,
                        flag + " needs dataset " + dataset + ", which this run cannot resolve — "
                                + "declare it in Requirements.Datasets (or a qualified "
                                + "Requirements.Variables entry) to skip instead (D89b: declare "
                                + "⇒ skip; don't declare ⇒ error)"));
            }
        }
    }


    private static void collectMatchedFlagRefs(Expr e, Set<String> out)
    {
        switch (e)
        {
        case Expr.And a -> a.parts().forEach(p -> collectMatchedFlagRefs(p, out));
        case Expr.Or o -> o.parts().forEach(p -> collectMatchedFlagRefs(p, out));
        case Expr.Not n -> collectMatchedFlagRefs(n.inner(), out);
        case Expr.Binary b ->
        {
            collectMatchedFlagRefs(b.left(), out);
            collectMatchedFlagRefs(b.right(), out);
        }
        case Expr.Lit lit ->
        {
            if (lit.kind() == Expr.LitKind.LIST && lit.value() instanceof List<?> items)
            {
                for (Object item : items)
                {
                    if (item instanceof Expr inner)
                    {
                        collectMatchedFlagRefs(inner, out);
                    }
                }
            }
        }
        case Expr.Ref r ->
        {
            if (r.kind() == OperandKind.MATCHED_FLAG)
            {
                out.add(r.name());
            }
        }
        case Expr.Call c ->
        {
            c.args().forEach(a -> collectMatchedFlagRefs(a, out));
            c.kwargs().values().forEach(a -> collectMatchedFlagRefs(a, out));
        }
        }
    }


    /**
     * Whether the rule declares a dependency on {@code dataset}: {@code Requirements.Datasets}
     * names it, or {@code Requirements.Variables} (All &#8746; Any) carries any qualified
     * {@code dataset.<VAR>} entry — D89a's "one declaration covers both absences", read at dataset
     * granularity.
     */
    private static boolean declaresDataset(Rule rule, String dataset)
    {
        net.cumba.corej.core.model.Requirements requirements = rule.getRequirements();
        if (requirements != null && requirements.getDatasets() != null
                && requirements.getDatasets().contains(dataset))
        {
            return true;
        }
        VariableRequirement variables = rule.effectiveVariableRequirement();
        if (variables == null)
        {
            return false;
        }
        String prefix = dataset + ".";
        return startsWithAny(variables.getAll(), prefix)
                || startsWithAny(variables.anyUnion(), prefix);
    }


    private static boolean startsWithAny(@Nullable List<String> entries, String prefix)
    {
        return entries != null && entries.stream().anyMatch(e -> e != null && e.startsWith(prefix));
    }


    /**
     * D89/D89a/D89b for one filter binding: a filter column absent from the right-side dataset is a
     * bind error ({@link StageBErrorKind#FILTER_UNRESOLVABLE}, armed) <b>unless</b> the rule
     * declares the qualified name in {@code Requirements.Variables} — then the (rule, dataset)
     * <b>skips</b> cleanly with a reason. <i>"Declare ⇒ skip; don't declare ⇒ error. Never
     * silent."</i>
     *
     * @param matchName
     *            the joined dataset's name (the filter evaluates on its own rows, D88c)
     * @param filterColumns
     *            the column names the filter references
     * @param rightColumns
     *            the joined dataset's column inventory
     * @param declared
     *            the rule's {@code Requirements.Variables}, or {@code null}
     * @param findings
     *            receives the armed findings, per binding
     * @param skips
     *            receives the declared-skip reasons
     */
    static void checkFilterBinding(String matchName, Collection<String> filterColumns,
            Set<String> rightColumns, @Nullable VariableRequirement declared,
            List<StageBFinding> findings, List<String> skips)
    {
        for (String column : filterColumns)
        {
            if (rightColumns.contains(column))
            {
                continue;
            }
            String qualified = matchName + "." + column;
            if (declaresVariable(declared, qualified))
            {
                skips.add("Rule skipped — filter column " + qualified + " is not present and is "
                        + "declared in Requirements.Variables (D89a)");
            }
            else
            {
                findings.add(new StageBFinding(StageBErrorKind.FILTER_UNRESOLVABLE, qualified,
                        "the Match_Datasets filter for " + matchName + " references " + column
                                + ", which " + matchName + " does not carry — declare " + qualified
                                + " in Requirements.Variables to skip instead (D89)"));
            }
        }
    }


    private static boolean declaresVariable(@Nullable VariableRequirement declared,
            String qualified)
    {
        if (declared == null)
        {
            return false;
        }
        return contains(declared.getAll(), qualified) || contains(declared.anyUnion(), qualified);
    }


    /**
     * Whether any entry <b>names</b> {@code qualified} — compared on the entry's variable identity,
     * never on its raw text.
     *
     * <p>
     * ⛔⛔ <b>This used to be a bare {@code entries.contains(qualified)}, and a type-tagged entry
     * broke it silently</b> ({@code PLAN-variable-type-requirements}, review finding H1). A rule
     * filtering on {@code AE.AESEV} and declaring
     * {@code Requirements.Variables.All: ["AE.AESEV:C"]} stopped being seen as declaring it, so
     * D89's <i>"declare ⇒ skip"</i> contract broke the wrong way: the rule emitted an armed
     * {@code FILTER_UNRESOLVABLE} finding where the author had asked for a clean skip. Nothing
     * failed — only the disposition changed, which is the worst shape a defect can take here.
     * </p>
     *
     * <p>
     * ⚑ {@code declaresDataset} needs no such fix: its {@code startsWithAny(dataset + ".")} tests
     * the FRONT of the entry, which a trailing tag cannot disturb.
     * </p>
     *
     * @param entries
     *            the facet's entries, as authored
     * @param qualified
     *            the {@code DATASET.COLUMN} name being looked for
     * @return whether an entry names it, tag or no tag
     */
    private static boolean contains(@Nullable List<String> entries, String qualified)
    {
        if (entries == null)
        {
            return false;
        }
        for (String entry : entries)
        {
            if (entry == null)
            {
                continue;
            }
            if (entry.equals(qualified) || identityOf(entry).equals(qualified))
            {
                return true;
            }
        }
        return false;
    }


    /** An entry's {@code DATASET.COLUMN} identity — the parsed halves, with any type tag gone. */
    private static String identityOf(String entry)
    {
        ScopeVariableEntry parsed = ScopeVariableEntry.parse(entry);
        return parsed.isQualified() ? parsed.qualifier() + "." + parsed.variable()
                : parsed.variable();
    }

}
