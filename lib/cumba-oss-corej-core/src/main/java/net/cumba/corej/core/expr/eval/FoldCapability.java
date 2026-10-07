package net.cumba.corej.core.expr.eval;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.typed.TypeExpectations;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionAny;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.CheckConditionNot;
import net.cumba.corej.core.model.LevelCheck;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.corej.core.model.VariableRequirement;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;

/**
 * The one static classifier of what a rule's Check can fold —
 * {@code PLAN-message-folded-corpus-alignment} row 1 (P2, P5), moved out of
 * {@code EmptyColumnCollapse} (C7 R2) and {@code RulePackageLoader}. Three questions, no data in
 * the first two:
 *
 * <ul>
 * <li>{@link #collapsible(Rule)} — C7 R2: the present-but-empty collapse can apply (a plain
 * {@code empty}-family leaf outside {@code Keep_Row_Findings}, a {@code Record} rule, no variable
 * cursor).</li>
 * <li>{@link #mayAbsentFold(Rule)} — P2: some column the Check reads is not guaranteed present by
 * the rule's own {@code Requirements.Variables} gate ({@code All}, or a one-member {@code Any}
 * group), so the absent-column fold can decide the rule on an absent column at run time.</li>
 * <li>{@link #absentTrueCapable(Rule, EvaluationContext)} — P5, the census: whether the fold can
 * decide the Check <b>TRUE</b> with every ungated column absent — the one evaluation that bounds
 * every run-time absent fold (Kleene monotonicity: a leaf decides only once every column it reads
 * is absent, and refining an undecided leaf never flips a decided result). Dataset-constant leaves
 * (presence facts, dataset facts, {@code $}-references, library gates) are not absent-dependent and
 * are enumerated over both truth values; above {@link #ENUMERATION_CAP} such leaves the rule counts
 * as capable.</li>
 * </ul>
 *
 * <p>
 * {@link #refusals(Rule)} is the load gate: {@code Outcome.Message_Folded} is refused on a rule
 * that can never fold <em>or</em> collapse (P2 widens C7's "never collapse"), on a
 * non-{@code Record} rule (C7 R2 b, P1: a dataset-sensitivity rule keeps {@code Message}) and on a
 * variable-cursor Check (C7 R2 c). The corpus coverage lint reads {@link #absentTrueCapable} and
 * {@link #collapsible} to demand the key on every rule that can fold — after the switch (Q6) a rule
 * without the key reports per row on an absent column, with no error anywhere, so that lint is what
 * keeps the corpus folding.
 * </p>
 */
public final class FoldCapability
{

    /**
     * Above this many enumerated (dataset-constant) leaves the census stops enumerating and counts
     * the rule as absent-TRUE-capable — the safe direction (the key is demanded, never withheld).
     */
    public static final int ENUMERATION_CAP = 10;

    private FoldCapability()
    {
    }

    // ---- load time (R2 / P2) --------------------------------------------------------------


    /**
     * Whether a rule declaring {@code Message_Folded} can collapse at all (C7 R2 a–c).
     *
     * @param rule
     *            the loaded rule (Sensitivity derived, native expression installed)
     * @return {@code true} when nothing refuses the collapse
     */
    public static boolean collapsible(Rule rule)
    {
        return !collapsibleLeafNames(rule).isEmpty() && rule.getSensitivity() == Sensitivity.RECORD
                && !readsVariableCursor(rule);
    }


    /**
     * P2 — whether the absent-column fold can ever decide this rule on an absent column: some
     * column its Check reads (bare, {@code --}-templated or dotted, as written; the name arguments
     * of the presence and column-status predicates are not reads) is outside the columns its own
     * {@code Requirements.Variables} gate guarantees present ({@code All}, and any one-member
     * {@code Any} group), compared as written and case-insensitively, exactly as the Type 1 lint
     * compares.
     *
     * @param rule
     *            the loaded rule
     * @return {@code true} when at least one read column may be absent at run time
     */
    public static boolean mayAbsentFold(Rule rule)
    {
        Set<String> guaranteed = guaranteedPresent(rule, null);
        for (CheckConditionExpression ce : authoredConditions(rule))
        {
            for (String name : readColumnNames(ce.expr()))
            {
                if (!containsIgnoreCase(guaranteed, name))
                {
                    return true;
                }
            }
        }
        return false;
    }


    /**
     * The load-time refusals of {@code Outcome.Message_Folded}, each as one error text without the
     * rule-id prefix; empty when the key is accepted. Read <b>after</b> Sensitivity derivation and
     * after the native expression (and its evaluation domain) is installed.
     *
     * @param rule
     *            the loaded rule
     * @return the refusals, in order: (a) can never fold or collapse, (b) Sensitivity, (c) variable
     *         cursor
     */
    public static List<String> refusals(Rule rule)
    {
        List<String> out = new ArrayList<>();
        Set<String> keep = keepRowFindingsAsWritten(rule.getOutcome());
        if (collapsibleLeafNames(rule).isEmpty() && !mayAbsentFold(rule))
        {
            out.add("Outcome.Message_Folded is declared but the rule can never fold or collapse:"
                    + " the Check holds no empty-family leaf (empty / is_missing / non_empty /"
                    + " present / is_present) over a plain column reference"
                    + (keep.isEmpty() ? "" : " outside Keep_Row_Findings")
                    + ", and every column it reads is required present by"
                    + " Requirements.Variables, so no absent column can decide it (D6, P2) —"
                    + " remove the key, or write the check on the column");
        }
        Sensitivity sensitivity = rule.getSensitivity();
        if (sensitivity != Sensitivity.RECORD)
        {
            out.add("Outcome.Message_Folded is the message of a RECORD-level rule folded to one"
                    + " dataset finding (D7); this rule's Sensitivity is "
                    + (sensitivity == null ? "undeclared and underived"
                            : sensitivity.getJsonValue())
                    + ", which never reaches the fold or the collapse — remove the key");
        }
        if (readsVariableCursor(rule))
        {
            out.add("Outcome.Message_Folded is declared but the Check reads the variable cursor"
                    + " (varname() / value() / a variable-metadata binding), so it is evaluated"
                    + " per variable, where neither fold runs — remove the key");
        }
        return out;
    }


    /**
     * The {@code Keep_Row_Findings} entries the Check does not read, compared as written
     * ({@code --} templates included, names case-insensitive) against every column reference of
     * every authored level; and the blank entries.
     *
     * @param rule
     *            the loaded rule
     * @return one error text per offending entry, without the rule-id prefix
     */
    public static List<String> keepRowFindingsErrors(Rule rule)
    {
        List<String> out = new ArrayList<>();
        Outcome outcome = rule.getOutcome();
        if (outcome == null || outcome.getKeepRowFindings() == null)
        {
            return out;
        }
        Set<String> read = new LinkedHashSet<>();
        for (CheckConditionExpression ce : authoredConditions(rule))
        {
            read.addAll(referencedColumnNames(ce.expr()));
        }
        for (String entry : outcome.getKeepRowFindings())
        {
            if (entry == null || entry.isBlank())
            {
                out.add("Outcome.Keep_Row_Findings contains an empty entry");
            }
            else if (!containsIgnoreCase(read, entry.trim()))
            {
                out.add("Outcome.Keep_Row_Findings names '" + entry.trim()
                        + "', which the Check does not read (compared as written, --"
                        + " included) — a column the rule never tests cannot keep its row"
                        + " findings");
            }
        }
        return out;
    }


    /**
     * The columns the rule's {@code Requirements.Variables} gate guarantees present before the
     * Check runs: every {@code All} entry and every one-member {@code Any} group, as written, with
     * a leading {@code --} resolved against {@code prefix} when one is given (the gate's own
     * resolution, {@code ScopeMatcher.resolveScopeVariable}).
     *
     * @param rule
     *            the loaded rule
     * @param prefix
     *            the variable wildcard prefix to resolve {@code --} with, or {@code null} to keep
     *            the entries as written
     * @return the guaranteed names, in declaration order
     */
    public static Set<String> guaranteedPresent(Rule rule, @Nullable String prefix)
    {
        Set<String> out = new LinkedHashSet<>();
        VariableRequirement required = rule.effectiveVariableRequirement();
        if (required == null)
        {
            return out;
        }
        if (required.getAll() != null)
        {
            for (String entry : required.getAll())
            {
                out.add(resolvePrefix(entry.trim(), prefix));
            }
        }
        if (required.getAnyGroups() != null)
        {
            for (List<String> group : required.getAnyGroups())
            {
                if (group != null && group.size() == 1)
                {
                    out.add(resolvePrefix(group.getFirst().trim(), prefix));
                }
            }
        }
        return out;
    }

    // ---- the census (P5) ---------------------------------------------------------------------

    /**
     * The outcome of {@link #absentTrueCapable} with every ungated column absent:
     * <ul>
     * <li>{@code capable} — some assignment of the enumerated leaves makes the fold TRUE <b>with an
     * absent column deciding it</b> (the same assignment is not TRUE absent-blind, P4): the
     * absent-column fold can report this rule, under {@code Message_Folded};</li>
     * <li>{@code collapsibleByAbsence} — some {@code empty}-family leaf over an absent column is
     * decided while the whole Check stays UNKNOWN for some assignment: the rule reaches the row
     * path and the P3 collapse can report it once ({@code EmptyColumnCollapse});</li>
     * <li>{@code absentLeafColumns} — the ungated columns the decided leaves read, as the Check
     * names them (what a folded message names);</li>
     * <li>{@code enumeratedLeaves} / {@code capped} — how many dataset-constant leaves were
     * enumerated, and whether the enumeration was cut off at {@link #ENUMERATION_CAP} (then both
     * verdicts are {@code true} by policy, not by evaluation — provided some leaf is decided by an
     * absent column at all).</li>
     * </ul>
     *
     * @param capable
     *            whether the absent fold can decide the rule TRUE through an absent column
     * @param collapsibleByAbsence
     *            whether the P3 collapse can report the rule on absent columns
     * @param absentLeafColumns
     *            the absent columns the decided leaves read
     * @param enumeratedLeaves
     *            the number of dataset-constant leaves enumerated
     * @param capped
     *            whether the enumeration was skipped for exceeding the cap
     */
    public record AbsentFoldCensus(boolean capable, boolean collapsibleByAbsence,
            Set<String> absentLeafColumns, int enumeratedLeaves, boolean capped)
    {

        /** Defensive, unmodifiable copy of the column set (SpotBugs EI_EXPOSE_REP). */
        public AbsentFoldCensus
        {
            absentLeafColumns = java.util.Collections
                    .unmodifiableSet(new LinkedHashSet<>(absentLeafColumns));
        }


        /** Whether a record-level rule of this census must declare {@code Message_Folded}. */
        public boolean demandsKey()
        {
            return capable || collapsibleByAbsence;
        }
    }

    /**
     * P5 — the census verdict for one rule, evaluated against a context whose table carries
     * <b>exactly</b> the columns the rule's gate guarantees ({@link #guaranteedPresent}) and
     * nothing else, so that every other column the Check reads is absent. The rule must be the
     * specialised one (names concrete, {@code RuleSpecialiser.specialise} with the same prefix the
     * context carries) and the context is built by {@link #censusContext}.
     *
     * <p>
     * Leaf classes: a leaf the level calculus classifies {@code DATASET_WITH_ABSENT} on this
     * context (every column it reads absent, every other operand a literal or a pure function of
     * those) is <b>evaluated</b> exactly as the run-time fold evaluates it; a leaf that reads a
     * present column, a wildcard capture, the cursor or the joined rows is <b>undecided</b>; every
     * other leaf — a presence fact, a dataset fact, a {@code $}-reference, a library gate, a
     * column-status predicate over an absent column — is <b>enumerated</b> over both truth values,
     * which over-approximates every run-time context (the safe direction: a key demanded, never
     * withheld).
     * </p>
     *
     * @param specialised
     *            the specialised rule
     * @param ctx
     *            the census context ({@link #censusContext})
     * @return the census verdict
     */
    public static AbsentFoldCensus absentTrueCapable(Rule specialised, EvaluationContext ctx)
    {
        List<Expr> roots = compiledRoots(specialised);
        boolean capable = false;
        boolean collapsible = false;
        Set<String> columns = new LinkedHashSet<>();
        int enumerated = 0;
        boolean capped = false;
        for (Expr root : roots)
        {
            Census census = new Census(ctx);
            census.classify(root);
            enumerated = Math.max(enumerated, census.enumerated.size());
            for (Map.Entry<Expr, Census.LeafClass> e : census.classes.entrySet())
            {
                if (e.getValue() == Census.LeafClass.EVALUATED
                        || e.getValue() == Census.LeafClass.ENUMERATED_ABSENT)
                {
                    columns.addAll(census.absentColumnsOf(e.getKey()));
                }
            }
            if (census.enumerated.size() > ENUMERATION_CAP)
            {
                // Capped: both verdicts by policy — but only where an absent column can decide
                // a leaf at all; a Check of presence facts and per-row reads never absent-folds.
                capped = true;
                capable |= census.anyAbsentLeaf(false);
                collapsible |= census.anyAbsentLeaf(true);
                continue;
            }
            Census.Verdicts outcome = census.verdicts(root);
            capable |= outcome.absentTrue();
            collapsible |= outcome.unknown() && census.anyAbsentLeaf(true);
        }
        return new AbsentFoldCensus(capable, collapsible, Set.copyOf(columns), enumerated, capped);
    }

    /**
     * The per-column trigger analysis — what the collapse can actually <b>report</b> for this rule
     * (the message generator's and the coverage lint's population; C7 R2's {@code collapsible} is
     * the loader's weaker "has a leaf" shape):
     * <ul>
     * <li>{@code blank}: a plain {@code empty}-family leaf column X (outside
     * {@code keepRowFindings}) whose leaves, folded to their truth-table constant for a column
     * blank on every record ({@code empty} / {@code is_missing} TRUE, {@code non_empty} /
     * {@code present} / {@code is_present} FALSE; every other leaf undecided), leave the Check TRUE
     * (every record fires: one finding, no residual) or UNKNOWN (a residual some record must meet).
     * A column whose blankness makes the Check FALSE — the {@code not empty(X)} guard — never
     * reports and is not a trigger: only a polarity-live leaf (its constant, negated once per
     * enclosing {@code Not}, TRUE) triggers, exactly as at data time
     * ({@code EmptyColumnCollapse.collectLive}).</li>
     * </ul>
     * An ABSENT column triggers exactly like a blank one when its leaf is polarity-live (owner
     * 2026-10-07); on the census context every ungated column is absent, so {@code blank} covers
     * both: the generator phrases an ungated trigger "absent or not populated".
     *
     * @param blank
     *            trigger column → TRUE (no residual) or UNKNOWN (a residual remains)
     */
    public record Triggers(Map<String, BroadcastFold.Verdict> blank)
    {

        /** Defensive, unmodifiable copy (SpotBugs EI_EXPOSE_REP). */
        public Triggers
        {
            blank = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(blank));
        }


        /** Whether the collapse can report this rule at all. */
        public boolean any()
        {
            return !blank.isEmpty();
        }
    }

    /**
     * The per-column trigger analysis of {@link Triggers}, on the census context
     * ({@link #censusContext}: the gated columns present, every other column absent).
     *
     * @param specialised
     *            the specialised rule
     * @param ctx
     *            the census context
     * @param keepRowFindings
     *            the resolved {@code Keep_Row_Findings} names
     * @return the triggers
     */
    public static Triggers triggers(Rule specialised, EvaluationContext ctx,
            Set<String> keepRowFindings)
    {
        Map<String, BroadcastFold.Verdict> blank = new java.util.LinkedHashMap<>();
        List<Expr> roots = compiledRoots(specialised);
        for (Expr root : roots)
        {
            // the same polarity-aware walk the runtime takes (EmptyColumnCollapse.collectLive),
            // every plain empty-family leaf column assumed blank on every record
            // every plain empty-family leaf column assumed blank on every record: every live leaf
            // is decided, and the columns named are ALL the live leaves — the refinement (owner
            // 2026-10-07) folds only when every one of them is decided at data time
            EmptyColumnCollapse.LiveLeaves acc = new EmptyColumnCollapse.LiveLeaves();
            EmptyColumnCollapse.collectLive(root, false, keepRowFindings,
                    (c, name) -> "empty".equals(c.name()) || "is_missing".equals(c.name())
                            ? BroadcastFold.Verdict.TRUE
                            : BroadcastFold.Verdict.FALSE,
                    // MED-1: a branch is dead for the census only where it is dead in EVERY
                    // dataset the rule runs on — a presence fact about a gated column
                    branch -> foldWith(branch, leaf -> gatedPresenceFact(leaf, ctx)), acc);
            for (String x : acc.columns)
            {
                if (blank.containsKey(x))
                {
                    continue;
                }
                BroadcastFold.Verdict v = foldWith(root, leaf -> blankLeafVerdict(leaf, x));
                if (v != BroadcastFold.Verdict.FALSE)
                {
                    blank.put(x, v);
                }
            }
        }
        return new Triggers(blank);
    }


    /**
     * The compiled roots the census and the trigger analysis fold: the per-level expressions where
     * the rule declares check levels, else the single compiled Check. Always the compiled form
     * (PACK 4 review LOW-2): a nested {@code all} / {@code any} / {@code not} authored Check keeps
     * its combinators and its negation here, which the flattened {@link #authoredConditions name
     * walk} loses — that walk serves name collection only.
     */
    static List<Expr> compiledRoots(Rule specialised)
    {
        List<Expr> roots = new ArrayList<>();
        if (specialised.getCheckLevelExprs() != null)
        {
            roots.addAll(specialised.getCheckLevelExprs().values());
        }
        else if (specialised.getCheckExpr() != null)
        {
            roots.add(specialised.getCheckExpr());
        }
        if (roots.isEmpty())
        {
            // no compiled form (a fixture the native compiler declined): the authored leaves, as
            // before — the shipped corpus never takes this branch (every shipped rule compiles)
            for (CheckConditionExpression ce : authoredConditions(specialised))
            {
                roots.add(ce.expr());
            }
        }
        return roots;
    }


    /**
     * The verdict of a {@code var_exists} / {@code var_not_exists} leaf over a column the rule
     * GATES (present on the census context, so present in every dataset the rule runs on), else
     * {@code UNKNOWN}: a presence fact about an ungated column differs from dataset to dataset and
     * must not kill a branch for the census (PACK 4 confirmation MED-1).
     */
    private static BroadcastFold.Verdict gatedPresenceFact(Expr leaf, EvaluationContext ctx)
    {
        if (!(leaf instanceof Expr.Call c) || !BroadcastFold.isExistsCall(c)
                || !c.name().startsWith("var_"))
        {
            return BroadcastFold.Verdict.UNKNOWN;
        }
        String name = switch (c.args().get(0))
        {
        case Expr.Ref r -> r.name();
        case Expr.Lit lit when lit.value() instanceof String str -> str;
        default -> null;
        };
        if (name == null || name.contains("${") || name.indexOf('*') >= 0
                || BroadcastFold.bindColumnLevel(name, ctx) != BroadcastFold.BindColumnLevel.ROW)
        {
            return BroadcastFold.Verdict.UNKNOWN;
        }
        return BroadcastFold.fold(c, ctx, false, true);
    }


    /** A plain empty-family leaf over {@code x}: its truth-table constant for a blank column. */
    private static BroadcastFold.Verdict blankLeafVerdict(Expr leaf, String x)
    {
        if (leaf instanceof Expr.Call c)
        {
            String name = EmptyColumnCollapse.plainColumnLeaf(c);
            if (name != null && name.equalsIgnoreCase(x))
            {
                return "empty".equals(c.name()) || "is_missing".equals(c.name())
                        ? BroadcastFold.Verdict.TRUE
                        : BroadcastFold.Verdict.FALSE;
            }
        }
        return BroadcastFold.Verdict.UNKNOWN;
    }


    /** Kleene fold of a tree with a leaf oracle. */
    private static BroadcastFold.Verdict foldWith(Expr e,
            java.util.function.Function<Expr, BroadcastFold.Verdict> leaf)
    {
        return switch (e)
        {
        case Expr.And and ->
        {
            boolean unknown = false;
            for (Expr p : and.parts())
            {
                BroadcastFold.Verdict v = foldWith(p, leaf);
                if (v == BroadcastFold.Verdict.FALSE)
                {
                    yield BroadcastFold.Verdict.FALSE;
                }
                unknown |= v == BroadcastFold.Verdict.UNKNOWN;
            }
            yield unknown ? BroadcastFold.Verdict.UNKNOWN : BroadcastFold.Verdict.TRUE;
        }
        case Expr.Or or ->
        {
            boolean unknown = false;
            for (Expr p : or.parts())
            {
                BroadcastFold.Verdict v = foldWith(p, leaf);
                if (v == BroadcastFold.Verdict.TRUE)
                {
                    yield BroadcastFold.Verdict.TRUE;
                }
                unknown |= v == BroadcastFold.Verdict.UNKNOWN;
            }
            yield unknown ? BroadcastFold.Verdict.UNKNOWN : BroadcastFold.Verdict.FALSE;
        }
        case Expr.Not not -> switch (foldWith(not.inner(), leaf))
        {
        case TRUE -> BroadcastFold.Verdict.FALSE;
        case FALSE -> BroadcastFold.Verdict.TRUE;
        case UNKNOWN -> BroadcastFold.Verdict.UNKNOWN;
        };
        default -> leaf.apply(e);
        };
    }


    /**
     * The evaluation context the census runs in: {@code table} (the gated columns only, any row
     * count), the rule's own type expectations (so an absent numeric-expected column reads
     * {@code MIS}, D76), the wildcard prefixes, no variables and no joins.
     *
     * @param specialised
     *            the specialised rule
     * @param table
     *            a table carrying exactly {@link #guaranteedPresent}'s columns
     * @param prefix
     *            the variable wildcard prefix the rule was specialised with
     * @return the context
     */
    public static EvaluationContext censusContext(Rule specialised, IDataTable table, String prefix)
    {
        TypeExpectations expectations = TypeExpectations.forRule(specialised);
        return EvaluationContext.builder().table(table)
                .numericExpectedColumns(expectations.numericDefaultColumns())
                .numericExpectedDynamicSites(expectations.numericDynamicSites())
                .domainPrefix(prefix).variableWildcardPrefix(prefix)
                .domainName(table.getMetaData().getName())
                .evaluationDomain(specialised.getEvaluationDomain()).build();
    }

    /** One census evaluation over one root: leaf classification, memoised leaf verdicts. */
    private static final class Census
    {

        enum LeafClass
        {
            /** Decided by absent columns alone: evaluated as the runtime fold evaluates it. */
            EVALUATED,
            /** Reads a present column, a capture or a cursor: never decided here. */
            UNDECIDED,
            /** A dataset fact reading no column: enumerated, and decided absent-blind too. */
            ENUMERATED,
            /**
             * Enumerated, but reads an absent column beside a {@code $}-reference or a fact: at run
             * time it is the absent arm's leaf (a scalar binding makes it dataset-level by
             * absence), so absent-blind it is UNKNOWN.
             */
            ENUMERATED_ABSENT
        }

        private final EvaluationContext ctx;

        private final IdentityHashMap<Expr, LeafClass> classes = new IdentityHashMap<>();

        private final IdentityHashMap<Expr, Boolean> evaluated = new IdentityHashMap<>();

        /** The enumerated leaves in encounter order — the bit positions of an assignment. */
        private final List<Expr> enumerated = new ArrayList<>();

        Census(EvaluationContext ctx)
        {
            this.ctx = ctx;
        }


        void classify(Expr e)
        {
            switch (e)
            {
            case Expr.And and -> and.parts().forEach(this::classify);
            case Expr.Or or -> or.parts().forEach(this::classify);
            case Expr.Not not -> classify(not.inner());
            default ->
            {
                LeafClass c = classifyLeaf(e);
                classes.put(e, c);
                if (c == LeafClass.ENUMERATED || c == LeafClass.ENUMERATED_ABSENT)
                {
                    enumerated.add(e);
                }
            }
            }
        }


        private LeafClass classifyLeaf(Expr leaf)
        {
            if (BroadcastFold.absentColumnLeafLevel(leaf,
                    ctx) == BroadcastFold.AbsentLeafLevel.DATASET_WITH_ABSENT
                    && NativeExprEvaluator.isSupported(leaf))
            {
                evaluated.put(leaf, NativeExprEvaluator.evaluateBroadcast(leaf, ctx));
                return LeafClass.EVALUATED;
            }
            if (readsPresentOrCursor(leaf))
            {
                return LeafClass.UNDECIDED;
            }
            return absentColumnsOf(leaf).isEmpty() ? LeafClass.ENUMERATED
                    : LeafClass.ENUMERATED_ABSENT;
        }


        /** The columns the leaf reads that are absent on this context. */
        Set<String> absentColumnsOf(Expr leaf)
        {
            Set<String> out = new LinkedHashSet<>();
            for (String name : readColumnNames(leaf))
            {
                if (name.indexOf('*') < 0 && BroadcastFold.bindColumnLevel(name,
                        ctx) == BroadcastFold.BindColumnLevel.DATASET_ABSENT)
                {
                    out.add(name);
                }
            }
            return out;
        }


        /**
         * Whether some {@code empty}-family leaf over a plain absent column is decided here — the
         * P3 trigger shape (C7 D9 read literally); a {@code !=} / {@code ==} absent leaf is
         * evaluated by the fold but is no collapse trigger.
         */
        boolean anyAbsentLeaf(boolean emptyFamilyOnly)
        {
            for (var e : classes.entrySet())
            {
                boolean absentDecided = e.getValue() == LeafClass.EVALUATED
                        || e.getValue() == LeafClass.ENUMERATED_ABSENT;
                boolean emptyFamily = e.getKey() instanceof Expr.Call c
                        && EmptyColumnCollapse.plainColumnLeaf(c) != null;
                if (absentDecided && (!emptyFamilyOnly || emptyFamily))
                {
                    return true;
                }
            }
            return false;
        }


        /**
         * Whether the leaf reads something that is per row (or per variable) on this context: a
         * bare column the table carries (or a name the level calculus leaves at row level), a
         * wildcard capture, the cursor, the joined rows, or a column-status / whole-column verdict
         * over a present column. A presence predicate's argument is a name, not a read.
         */
        private boolean readsPresentOrCursor(Expr e)
        {
            return switch (e)
            {
            case Expr.Ref r -> switch (r.kind())
            {
            case COLUMN, DOTTED_REF -> BroadcastFold.bindColumnLevel(r.name(),
                    ctx) == BroadcastFold.BindColumnLevel.ROW;
            case OPERATION_REF -> false; // a $-binding: a scalar at run time, enumerated
            // a wildcard capture, the matched flag, a builtin cursor: per row (or per variable)
            case WILDCARD_COLUMN, MATCHED_FLAG, BUILTIN -> true;
            };
            case Expr.Lit lit -> lit.kind() == Expr.LitKind.LIST
                    && lit.value() instanceof List<?> items
                    && items.stream().anyMatch(i -> i instanceof Expr x && readsPresentOrCursor(x));
            case Expr.Not not -> readsPresentOrCursor(not.inner());
            case Expr.And and -> and.parts().stream().anyMatch(this::readsPresentOrCursor);
            case Expr.Or or -> or.parts().stream().anyMatch(this::readsPresentOrCursor);
            case Expr.Binary b -> readsPresentOrCursor(b.left()) || readsPresentOrCursor(b.right());
            case Expr.Call c ->
            {
                String name = c.name();
                boolean cursorNullary = ("value".equals(name) || "varname".equals(name))
                        && c.args().isEmpty() && c.kwargs().isEmpty();
                if (cursorNullary || net.cumba.corej.core.exec.JoinedRowFold.isQuantifier(name))
                {
                    yield true;
                }
                if (BroadcastFold.isExistsCall(c))
                {
                    yield false; // a name argument, decided by presence — enumerated
                }
                if (BroadcastFold.isBroadcastColumnPredicate(c)
                        || BroadcastFold.isWholeColumnVerdictCall(c))
                {
                    // one dataset-wide fact about a column: per row only when the column is there
                    yield c.args().stream().anyMatch(this::readsPresentOrCursor);
                }
                yield c.args().stream().anyMatch(this::readsPresentOrCursor)
                        || c.kwargs().values().stream().anyMatch(this::readsPresentOrCursor);
            }
            };
        }

        /**
         * Over every assignment of the enumerated leaves: whether some assignment is TRUE with the
         * evaluated (absent-decided) leaves counted and not TRUE with them blind — an absent column
         * decided it (P4's discriminator) — and whether some assignment leaves the Check UNKNOWN
         * (the row path, where P3 collapses).
         */
        record Verdicts(boolean absentTrue, boolean unknown)
        {
        }

        Verdicts verdicts(Expr root)
        {
            int m = enumerated.size();
            boolean absentTrue = false;
            boolean unknown = false;
            for (long bits = 0; bits < (1L << m); bits++)
            {
                BroadcastFold.Verdict full = fold(root, bits, false);
                if (full == BroadcastFold.Verdict.TRUE
                        && fold(root, bits, true) != BroadcastFold.Verdict.TRUE)
                {
                    absentTrue = true;
                }
                unknown |= full == BroadcastFold.Verdict.UNKNOWN;
            }
            return new Verdicts(absentTrue, unknown);
        }


        private BroadcastFold.Verdict fold(Expr e, long bits, boolean blind)
        {
            return switch (e)
            {
            case Expr.And and ->
            {
                boolean unknown = false;
                for (Expr p : and.parts())
                {
                    BroadcastFold.Verdict v = fold(p, bits, blind);
                    if (v == BroadcastFold.Verdict.FALSE)
                    {
                        yield BroadcastFold.Verdict.FALSE;
                    }
                    unknown |= v == BroadcastFold.Verdict.UNKNOWN;
                }
                yield unknown ? BroadcastFold.Verdict.UNKNOWN : BroadcastFold.Verdict.TRUE;
            }
            case Expr.Or or ->
            {
                boolean unknown = false;
                for (Expr p : or.parts())
                {
                    BroadcastFold.Verdict v = fold(p, bits, blind);
                    if (v == BroadcastFold.Verdict.TRUE)
                    {
                        yield BroadcastFold.Verdict.TRUE;
                    }
                    unknown |= v == BroadcastFold.Verdict.UNKNOWN;
                }
                yield unknown ? BroadcastFold.Verdict.UNKNOWN : BroadcastFold.Verdict.FALSE;
            }
            case Expr.Not not -> switch (fold(not.inner(), bits, blind))
            {
            case TRUE -> BroadcastFold.Verdict.FALSE;
            case FALSE -> BroadcastFold.Verdict.TRUE;
            case UNKNOWN -> BroadcastFold.Verdict.UNKNOWN;
            };
            default -> switch (java.util.Objects.requireNonNull(classes.get(e),
                    "unclassified leaf"))
            {
            case EVALUATED -> blind ? BroadcastFold.Verdict.UNKNOWN
                    : Boolean.TRUE.equals(evaluated.get(e)) ? BroadcastFold.Verdict.TRUE
                            : BroadcastFold.Verdict.FALSE;
            case ENUMERATED -> ((bits >> enumerated.indexOf(e)) & 1L) == 1L
                    ? BroadcastFold.Verdict.TRUE
                    : BroadcastFold.Verdict.FALSE;
            case ENUMERATED_ABSENT -> blind ? BroadcastFold.Verdict.UNKNOWN
                    : ((bits >> enumerated.indexOf(e)) & 1L) == 1L ? BroadcastFold.Verdict.TRUE
                            : BroadcastFold.Verdict.FALSE;
            case UNDECIDED -> BroadcastFold.Verdict.UNKNOWN;
            };
            };
        }
    }

    // ---- expression walks (shared with EmptyColumnCollapse) ---------------------------------

    /**
     * The {@code empty}-family leaves over a plain column reference (bare or {@code --}-templated,
     * never dotted, never a {@code $}-binding) of every authored level, minus the columns
     * {@code Keep_Row_Findings} names as written — the collapse triggers C7 R2 (a) asks for.
     */
    private static Set<String> collapsibleLeafNames(Rule rule)
    {
        Set<String> keep = keepRowFindingsAsWritten(rule.getOutcome());
        Set<String> leaves = new LinkedHashSet<>();
        for (CheckConditionExpression ce : authoredConditions(rule))
        {
            for (String name : plainColumnLeafNames(ce.expr()))
            {
                if (!containsIgnoreCase(keep, name))
                {
                    leaves.add(name);
                }
            }
        }
        return leaves;
    }


    private static boolean readsVariableCursor(Rule rule)
    {
        Domain domain = rule.getEvaluationDomain();
        return domain != null && domain.varCursor();
    }


    /**
     * The column names (as written) of every {@code empty}-family leaf whose argument is a plain
     * column reference — bare or {@code --}-templated, never dotted, never a {@code $}-binding.
     *
     * @param e
     *            the expression
     * @return the names, in encounter order
     */
    public static Set<String> plainColumnLeafNames(Expr e)
    {
        Set<String> out = new LinkedHashSet<>();
        collectLeafNames(e, out);
        return out;
    }


    private static void collectLeafNames(Expr e, Set<String> out)
    {
        switch (e)
        {
        case Expr.Not not -> collectLeafNames(not.inner(), out);
        case Expr.And and -> and.parts().forEach(p -> collectLeafNames(p, out));
        case Expr.Or or -> or.parts().forEach(p -> collectLeafNames(p, out));
        case Expr.Binary b ->
        {
            collectLeafNames(b.left(), out);
            collectLeafNames(b.right(), out);
        }
        case Expr.Call c ->
        {
            if (net.cumba.corej.core.exec.JoinedRowFold.isQuantifier(c.name())
                    || EmptyColumnCollapse.readsAnotherTable(c))
            {
                // the same exclusions as the data-time walk (EmptyColumnCollapse): a quantifier
                // body, and a target-table reader's arguments (MED-2)
                return;
            }
            String name = EmptyColumnCollapse.plainColumnLeaf(c);
            if (name != null)
            {
                out.add(name);
            }
            c.args().forEach(a -> collectLeafNames(a, out));
            c.kwargs().values().forEach(a -> collectLeafNames(a, out));
        }
        default ->
        {
            // a reference or a literal
        }
        }
    }


    /**
     * Every column reference an expression reads, as written: bare, {@code --}-templated and dotted
     * names alike, the name arguments of the presence predicates included (for the
     * {@code Keep_Row_Findings} read check, which compares against everything the Check names).
     *
     * @param e
     *            the expression
     * @return the names, in encounter order
     */
    public static Set<String> referencedColumnNames(Expr e)
    {
        Set<String> out = new LinkedHashSet<>();
        collectReferences(e, out, false);
        return out;
    }


    /**
     * Every column the expression <b>reads</b>, as written — {@link #referencedColumnNames} minus
     * the name arguments of the presence and column-status predicates, which test a name rather
     * than read a column (P2's "every column its Check reads").
     *
     * @param e
     *            the expression
     * @return the names, in encounter order
     */
    public static Set<String> readColumnNames(Expr e)
    {
        Set<String> out = new LinkedHashSet<>();
        collectReferences(e, out, true);
        return out;
    }


    private static void collectReferences(Expr e, Set<String> out, boolean readsOnly)
    {
        switch (e)
        {
        case Expr.Ref r ->
        {
            if (r.kind() == OperandKind.COLUMN || r.kind() == OperandKind.WILDCARD_COLUMN
                    || r.kind() == OperandKind.DOTTED_REF)
            {
                out.add(r.name());
            }
        }
        case Expr.Not not -> collectReferences(not.inner(), out, readsOnly);
        case Expr.And and -> and.parts().forEach(p -> collectReferences(p, out, readsOnly));
        case Expr.Or or -> or.parts().forEach(p -> collectReferences(p, out, readsOnly));
        case Expr.Binary b ->
        {
            collectReferences(b.left(), out, readsOnly);
            collectReferences(b.right(), out, readsOnly);
        }
        case Expr.Call c ->
        {
            if (readsOnly && (BroadcastFold.isExistsCall(c)
                    || BroadcastFold.isBroadcastColumnPredicate(c)))
            {
                return;
            }
            c.args().forEach(a -> collectReferences(a, out, readsOnly));
            c.kwargs().values().forEach(a -> collectReferences(a, out, readsOnly));
        }
        default ->
        {
            // a literal
        }
        }
    }


    /**
     * The authored expression leaves, nested forms flattened — for NAME collection only
     * ({@link #mayAbsentFold}, {@link #keepRowFindingsErrors}); anything that folds reads
     * {@link #compiledRoots} (PACK 4 review LOW-2).
     */
    static List<CheckConditionExpression> authoredConditions(Rule rule)
    {
        List<CheckConditionExpression> out = new ArrayList<>();
        for (LevelCheck level : rule.effectiveCheckLevels().values())
        {
            collectExpressions(level.condition(), out);
        }
        return out;
    }


    /**
     * Every expression leaf of an authored condition, the nested {@code all} / {@code any} /
     * {@code not} forms walked (the shipped corpus is expression-only since typed-expression phase
     * 7, but a fixture or a custom package may still nest, and a nested Check read as "reads no
     * column" refused {@code Message_Folded} on a rule that folds — measured 2026-10-07,
     * {@code NativeGuardFallthroughParityTest}).
     */
    private static void collectExpressions(@Nullable CheckCondition condition,
            List<CheckConditionExpression> out)
    {
        switch (condition)
        {
        case CheckConditionExpression ce -> out.add(ce);
        case CheckConditionAll all -> all.getConditions().forEach(c -> collectExpressions(c, out));
        case CheckConditionAny any -> any.getConditions().forEach(c -> collectExpressions(c, out));
        case CheckConditionNot not -> collectExpressions(not.getCondition(), out);
        case null ->
                {
                }
        }
    }


    static Set<String> keepRowFindingsAsWritten(@Nullable Outcome outcome)
    {
        Set<String> out = new LinkedHashSet<>();
        if (outcome != null && outcome.getKeepRowFindings() != null)
        {
            for (String entry : outcome.getKeepRowFindings())
            {
                if (entry != null && !entry.isBlank())
                {
                    out.add(entry.trim());
                }
            }
        }
        return out;
    }


    static boolean containsIgnoreCase(Collection<String> names, String name)
    {
        for (String n : names)
        {
            if (n.equalsIgnoreCase(name))
            {
                return true;
            }
        }
        return false;
    }


    private static String resolvePrefix(String entry, @Nullable String prefix)
    {
        return prefix != null && entry.startsWith("--") ? prefix + entry.substring(2) : entry;
    }
}
