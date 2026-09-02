package net.cumba.cdisc.core.exec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.cdisc.core.expr.OperandKind;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.model.CheckCondition;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.MatchDataset;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.cdisc.core.model.Rule;
import org.jspecify.annotations.Nullable;

/**
 * Step 3 of {@code plans/PLAN-absent-required-dataset-skip.md} (`Fix #222`) — <b>an absent dataset
 * whose absence the run already reports must SILENCE its dependants instead of flooding.</b>
 *
 * <h2>The ruling</h2> A rule that reads a foreign dataset <i>D</i> yields no finding from that
 * reading when <i>D</i> is absent — <b>iff the run already ships a rule whose whole Check is a bare
 * presence test on <i>D</i></b>. Then, and only then, the absence is reported exactly once by the
 * rule that exists to report it, instead of N times by every dependant that could not be evaluated.
 *
 * <h2>The precondition is DERIVED, never listed</h2> {@link #reportedDatasets(Collection)} walks
 * the rules of the run and asks each one <i>"is your entire Check a bare presence test, and on
 * which dataset?"</i> ({@link #barePresenceDataset(Rule)}). A hard-coded dataset table would rot
 * the moment a package changes; this cannot. The presence fact the guarantee rests on is literally
 * the same predicate the presence rule evaluates —
 * {@link OperatorRegistry#existsAsDataset(EvaluationContext, String)} is
 * {@code resolver.resolve(name) != null} — widened by {@code Fix #358} (D7) so a <em>split</em>
 * domain counts as present on <b>both</b> sides ({@code SplitDomainResolution.isPresentAsDomain};
 * here scoped to the candidates whose readers were widened: {@code Match_Datasets} names and dotted
 * Check references) — which is what {@link #decide} tests — so SKIP engages exactly when the
 * presence rule fires, never when it does not. <b>One stated exception:</b> a candidate referenced
 * <em>only</em> via {@code Operations[].domain} keeps the exact-name presence test (operations
 * still resolve exact — a Fix #358 non-goal), so on a split submission such a rule stays SKIPPED
 * while the presence rule no longer fires; the follow-up that widens operation domain resolution
 * must widen this predicate with it (mirror, never weaken —
 * {@code PLAN-match-datasets-split-union.md} §9).
 *
 * <h2>⚠ SKIP is scoped to the DEPENDENCY, not to the rule (K5b)</h2> Skipping the whole rule would
 * discard branches that never touched the absent dataset. The worked example is
 * {@code CDISC-CG0007} / {@code FDA-SD1085} / {@code CORE-000138}:
 *
 * <pre>
 * all[ any[ (--DTC incomplete) , (DM.RFSTDTC incomplete) ] , --DY non_empty ]
 * </pre>
 *
 * The first disjunct is entirely local — <i>"--DY is populated but --DTC is an incomplete date"</i>
 * is a real defect with nothing to do with DM — so a rule-granular SKIP would delete a genuine
 * finding. {@link #suppress} therefore rewrites only the boolean leaves that <em>read</em> the
 * absent dataset to {@code false} (<i>"this branch cannot contribute a finding"</i>) and folds the
 * tree:
 * <ul>
 * <li>an {@code and} with a suppressed conjunct collapses to {@code false} — the rule could not be
 * evaluated at all, so it reports {@link RuleExecutionStatus#SKIPPED};</li>
 * <li>an {@code or} simply drops its suppressed disjuncts — surviving siblings still evaluate, and
 * the rule reports normally.</li>
 * </ul>
 * A {@code not} is opaque: if anything under it reads the absent dataset the whole negation is
 * suppressed, because {@code not(unevaluable)} must not be read as {@code true}.
 *
 * <h2>The intent opt-out (K5c)</h2> Four rules declare <i>"this datum must exist somewhere"</i>,
 * where the foreign dataset is a <b>disjunct being tested</b> rather than a precondition — SKIP
 * would destroy the finding. They are excluded wholesale; see {@link #INTENT_OPT_OUT_RULE_IDS}.
 *
 * <h2>{@code Fix #218} — the CROSS-STANDARD arm</h2>
 * {@code plans/PLAN-cross-standard-absence-skip.md}. The coverage precondition above is
 * <b>package-scoped</b> and therefore structurally incapable of expressing a dependency on
 * <em>another CDISC standard</em>: no ADaM package reports {@code DM}, and per the owner's
 * invocation ruling (<i>"when ADaM is validated, SDTM is made available for the cross-standard
 * checks ONLY"</i>) none may start to. So an ADaM run without SDTM evaluated {@code CDISC-AD0204}
 * (<code>var_exists(DM.AGE) and AGE != DM.AGE</code>) to <b>no findings</b> and reported
 * <b>PASS</b> — a false assurance, because the truth is <i>"could not check"</i>.
 *
 * <p>
 * {@code crossStandardDatasets} is the run-level fact: the dataset names belonging to a standard
 * this run does <b>not</b> validate (on an ADaM-family run, the companion SDTM domain catalogue —
 * see {@code StudyValidationService}). Two properties are load-bearing:
 * </p>
 * <ul>
 * <li>the absence predicate stays {@code resolver.resolve(D) == null} — <i>"was it loaded at
 * all"</i> (since {@code Fix #358}: "…not even as a split domain", for {@code Match_Datasets} and
 * dotted-Check candidates) — and <b>never</b> a target-ness test. Under {@code --dataset} filtering
 * a co-located SDTM dataset loads as a {@code ReferenceDataset}: visible, resolvable, not
 * validated. A target-ness test would skip precisely the rules this arm exists to run;</li>
 * <li>the cross-standard arm acts <b>only</b> when the rewrite collapses the whole Check. If a
 * branch survives, the rule could still have fired, so it is left completely alone.</li>
 * </ul>
 *
 * <h2>⚠⚠ What the collapse gate does and does <b>not</b> guarantee ({@code W34-C1})</h2> An earlier
 * wording of the bullet above concluded <i>"therefore this arm changes no findings"</i>. ⛔ <b>That
 * is false, and it was refuted by run</b> (wave-34 lane C,
 * {@code plans/done/PLAN-fix218-behavioural-verification.md}): <b>19 findings are removed</b>, all
 * in {@code rules-core-tig-1-0.json}, none in an adamig package. The collapse gate is real, but it
 * bounds the <em>kind</em> of finding removed, not the count.
 *
 * <p>
 * <b>The theorem the code actually supports:</b> <i>the arm removes only <b>artefacts of
 * absence</b> — it converts a vacuous {@code PASS}, <b>or a flood</b>, into
 * {@link RuleExecutionStatus#SKIPPED}.</i> Because the whole Check must fold to {@code false},
 * every satisfying assignment necessarily required a leaf that <em>reads</em> the absent dataset.
 * All 19 collapse on the shape {@code x not in $op(domain=D)} with {@code D} absent, where <i>"not
 * in the empty set"</i> is vacuously TRUE ⇒ the direction is <b>flood → SKIPPED</b>, never <i>true
 * finding → silence</i>; supply {@code TA} and {@code CORE-000271}'s two findings vanish.
 * </p>
 *
 * <p>
 * ⛔ <b>Do not narrow the arm to "guarded" shapes to make the old sentence true</b> — that would
 * restore the {@code CORE-000269}/{@code -000270}/{@code -000271} flood, un-fixing a real defect.
 * </p>
 */
public final class AbsentDatasetSkip
{

    private AbsentDatasetSkip()
    {
    }

    /**
     * The intent opt-out population — rules for which an absent foreign dataset is one of the
     * things the rule <em>tests</em>, so SKIP must never apply (§5.1 / {@code K5c}).
     *
     * <p>
     * These four encode a <b>disjunctive-availability</b> requirement: SENDIG-DART v1.2 §7.2 —
     * <i>"If this variable is excluded in the DM domain, the information must be present at a
     * higher level (either Trial Sets or Trial Summary)"</i>. A TS presence rule reports <i>"TS is
     * missing"</i>; it does <b>not</b> report <i>"the species of the study animals is unknown"</i>,
     * and the second is the finding a reviewer needs.
     * </p>
     *
     * <p>
     * ⚠ This is a <b>pinned id set, deliberately not a re-derived predicate</b>. {@code Fix #207}
     * (step 1) ships the derivation as {@code IntentAbsenceOptOut} in {@code corej-cdisc-rules}'s
     * test sources, with its population landed at
     * {@code documentation/derivation/intent-absence-opt-out.tsv} and asserted by
     * {@code IntentAbsenceOptOutLintTest} on every build. That predicate is <b>structural over the
     * AUTHORED view and is not implementable here</b>: the shipped corpus lowers {@code Check} to
     * an expression string and injects an absent-column guard, so a shipped-view re-implementation
     * silently drops {@code -0105-1} and {@code -0106-1} — half the set, and the half that also
     * depends on TX (step 1's §S1.7). Consuming the derived id set is therefore the correct
     * coupling, not a shortcut.
     * </p>
     */
    public static final Set<String> INTENT_OPT_OUT_RULE_IDS = Set.of("CDISC-SEND-0105",
            "CDISC-SEND-0105.1", "CDISC-SEND-0106", "CDISC-SEND-0106.1");

    /**
     * Calls whose argument names a <em>dataset</em> rather than reading its contents. A dataset
     * presence test is never a "reference" to that dataset — it is the very question the presence
     * rule answers, so suppressing it would make the mechanism silence its own precondition.
     */
    private static final Set<String> PRESENCE_CALLS = Set.of("ds_exists", "ds_not_exists");

    /**
     * Calls whose first argument is a <em>name</em> (bareword or the equivalent string literal)
     * rather than a value — so a dotted argument names a foreign dataset's column. Mirrors
     * {@code ExprLowering.EXISTS_PREDICATES} minus the dataset-presence pair above.
     */
    private static final Set<String> NAME_ARG_CALLS = Set.of("exists", "not_exists", "var_exists",
            "var_not_exists", "var_is_null");

    // ---------------------------------------------------------------- coverage derivation (K5)

    /**
     * The datasets whose absence <b>this run already reports</b> — derived, per {@code K5}, by
     * asking every rule of the run whether its whole Check is a bare dataset-presence test.
     *
     * <p>
     * Deriving from the run's <em>effective</em> rule list rather than from the package file is
     * deliberate and strictly safer: a run may select several family packages, and
     * {@code --include-rules} / {@code --exclude-rules} can drop the presence rule from a run that
     * still executes its dependants. The plan's precondition is <i>"the run already reports D's
     * absence"</i>; for the default single-family unfiltered run the two readings coincide exactly.
     * </p>
     *
     * @param rules
     *            every rule selected for this run
     * @return the upper-cased dataset names covered by a bare presence rule (never {@code null})
     */
    public static Set<String> reportedDatasets(@Nullable Collection<Rule> rules)
    {
        if (rules == null || rules.isEmpty())
        {
            return Set.of();
        }
        Set<String> out = new TreeSet<>();
        for (Rule rule : rules)
        {
            String dataset = barePresenceDataset(rule);
            if (dataset != null)
            {
                out.add(dataset);
            }
        }
        return out.isEmpty() ? Set.of() : Set.copyOf(out);
    }


    /**
     * The dataset this rule asserts the presence of, or {@code null} when it is not a <b>bare</b>
     * presence rule.
     *
     * <p>
     * "Bare" means the rule's <em>entire</em> Check is the single assertion <i>"dataset D is
     * absent"</i> — {@code not ds_exists("D")} in the shipped expression form. Three neighbouring
     * shapes are deliberately rejected, each of which a naive walker would accept:
     * </p>
     * <ul>
     * <li>⚠ <b>polarity</b> — a bare {@code ds_exists("TT")} ({@code CDISC-CG0647},
     * {@code CORE-000042}, …) is a <i>prohibition</i>: it fires when the dataset IS present. It
     * reports nothing about absence and must never satisfy the precondition;</li>
     * <li>⚠ <b>conditional</b> presence rules ({@code CG0407} — EX, but only when TA exists) do not
     * guarantee the run reports D's absence, so they cannot satisfy it either;</li>
     * <li>⚠ a <b>scoped</b> presence rule, which would only run on the datasets its scope names —
     * so its verdict is not a study-wide guarantee.
     * {@link StudyRuleClassifier#hasUnrestrictedScope} is the same predicate the study-anchor pass
     * uses.</li>
     * </ul>
     *
     * @param rule
     *            the candidate rule
     * @return the upper-cased dataset name, or {@code null}
     */
    public static @Nullable String barePresenceDataset(@Nullable Rule rule)
    {
        if (rule == null || rule.getLoadError() != null
                || !StudyRuleClassifier.hasUnrestrictedScope(rule))
        {
            return null;
        }
        // Plan C §3.3: a rule DECLARING A LEVEL MAP is never a bare presence rule — a one-entry
        // map included, for consistency with RulePackageLoader.installCompiledLevels and
        // RuleCohortGrouper (a declared map always takes the per-level machinery: level stamping,
        // the level's own Message). With two levels the exclusion is also semantic: the strictest
        // level could read `ds_not_exists(D)` while a weaker level says something else entirely,
        // so the rule does not report D's absence unconditionally — and this method's whole
        // contract is that it does. ⚑ Vacuous on the shipped corpus.
        if (rule.getCheckLevels() != null && !rule.getCheckLevels().isEmpty())
        {
            return null;
        }
        Expr expr = rule.getCheckExpr();
        if (expr != null)
        {
            return barePresenceDataset(expr);
        }
        // Fallback for a rule that never acquired a native form (externally supplied / synthetic):
        // the authored operator leaf — only the unambiguous `ds_not_exists` counts (the generic
        // `not_exists`, whose meaning the Rule_Type used to decide, is retired and rejected at
        // load).
        return barePresenceDataset(rule.getCheck());
    }


    private static @Nullable String barePresenceDataset(Expr expr)
    {
        Expr.Call call;
        boolean negated;
        if (expr instanceof Expr.Not not && not.inner() instanceof Expr.Call inner)
        {
            call = inner;
            negated = true;
        }
        else if (expr instanceof Expr.Call bare)
        {
            call = bare;
            negated = false;
        }
        else
        {
            return null;
        }
        if (!PRESENCE_CALLS.contains(call.name()) || call.args().size() != 1
                || !call.kwargs().isEmpty())
        {
            return null;
        }
        // Assert-absence iff (the call says "not exists") XOR (a `not` wraps it).
        if ("ds_not_exists".equals(call.name()) == negated)
        {
            return null;
        }
        return upper(nameOfArg(call.args().get(0)));
    }


    private static @Nullable String barePresenceDataset(@Nullable CheckCondition check)
    {
        CheckCondition node = check;
        if (node instanceof CheckConditionAll all && all.getConditions().size() == 1)
        {
            node = all.getConditions().get(0);
        }
        if (!(node instanceof CheckConditionLeaf leaf))
        {
            return null;
        }
        if (!"ds_not_exists".equals(leaf.getOperator()))
        {
            return null;
        }
        String name = leaf.getName();
        return name == null || name.indexOf('.') >= 0 || name.startsWith("$") ? null : upper(name);
    }

    // ------------------------------------------------------------------- the per-rule decision

    /**
     * What step 3 decided for one (rule, dataset) execution.
     *
     * @param effectiveCheckExpr
     *            the Check expression to evaluate — the rule's own when nothing was suppressed,
     *            otherwise the dependency-scoped rewrite
     * @param suppressedDatasets
     *            the absent-and-reported datasets whose readings were suppressed, in stable order
     *            ({@code Fix #222} — <i>the run already reports this dataset's absence</i>)
     * @param unsuppliedDatasets
     *            the absent <b>cross-standard</b> datasets, in stable order ({@code Fix #218} —
     *            <i>this dataset belongs to a standard the run did not receive</i>). Kept apart
     *            from {@code suppressedDatasets} because the two are different facts about the run:
     *            one is a property of the <b>data</b>, the other of the <b>invocation</b>, and a
     *            reviewer needs to tell them apart.
     * @param collapsed
     *            {@code true} when the whole Check collapsed, i.e. the rule could not be evaluated
     *            at all and must report {@link RuleExecutionStatus#SKIPPED}
     */
    public record Decision(@Nullable Expr effectiveCheckExpr, List<String> suppressedDatasets,
            List<String> unsuppliedDatasets, boolean collapsed)
    {

        public Decision
        {
            // Defensive copy: the record is handed out of the engine and its accessors are public.
            suppressedDatasets = List.copyOf(suppressedDatasets);
            unsuppliedDatasets = List.copyOf(unsuppliedDatasets);
        }

        /** Nothing was suppressed — the rule runs exactly as it did before step 3. */
        public static final Decision NONE = new Decision(null, List.of(), List.of(), false);

        /** Whether this decision changes anything about the execution. */
        public boolean applies()
        {
            return !suppressedDatasets.isEmpty() || !unsuppliedDatasets.isEmpty();
        }


        /**
         * The {@code statusMessage} for a collapsed rule — names every responsible dataset, and
         * says <em>why</em> each one is responsible. {@code Fix #222}'s clause is reproduced
         * verbatim so no consumer reading that wording breaks.
         *
         * @return the human-readable skip reason
         */
        public String skipReason()
        {
            StringBuilder out = new StringBuilder("Rule skipped — ");
            if (!suppressedDatasets.isEmpty())
            {
                out.append(String.join(", ", suppressedDatasets))
                        .append(suppressedDatasets.size() == 1 ? " is" : " are")
                        .append(" not present in the study and reported by a dataset-presence rule");
            }
            if (!unsuppliedDatasets.isEmpty())
            {
                if (!suppressedDatasets.isEmpty())
                {
                    out.append("; ");
                }
                out.append(String.join(", ", unsuppliedDatasets))
                        .append(unsuppliedDatasets.size() == 1 ? " was" : " were")
                        .append(" not supplied to this run (a dependency on another CDISC "
                                + "standard)");
            }
            return out.toString();
        }
    }

    /**
     * Decides whether — and how far — this rule must be silenced on {@code table}.
     *
     * @param rule
     *            the rule about to be executed
     * @param resolver
     *            the run's dataset resolver; {@code resolve(D) == null} <em>is</em> the absence
     *            fact the presence rule reports
     * @param reportedDatasets
     *            the run's coverage, from {@link #reportedDatasets(Collection)}
     * @param primaryDataset
     *            the name of the dataset under evaluation, so a rule can never suppress readings of
     *            the very table it is running against
     * @param domainPrefix
     *            the dataset's CDISC domain code, used to reject an unresolved {@code --} domain
     * @return the decision, never {@code null}
     */
    public static Decision decide(Rule rule, DatasetResolver resolver, Set<String> reportedDatasets,
            @Nullable String primaryDataset, @Nullable String domainPrefix)
    {
        return decide(rule, resolver, reportedDatasets, Set.of(), primaryDataset, domainPrefix);
    }


    /**
     * {@code Fix #218} — the terminal {@link #decide} additionally carrying the run's
     * <b>cross-standard</b> coverage.
     *
     * @param rule
     *            the rule about to be executed
     * @param resolver
     *            the run's dataset resolver; {@code resolve(D) == null} <em>is</em> the "not
     *            supplied to this run" fact. ⚠ Never a target-ness test — a reference dataset
     *            resolves through exactly this call, which is the point.
     * @param reportedDatasets
     *            the run's presence-rule coverage, from {@link #reportedDatasets(Collection)}
     * @param crossStandardDatasets
     *            upper-cased dataset names belonging to a CDISC standard this run does <b>not</b>
     *            validate. Empty ⇒ behaviour identical to {@code Fix #222} alone.
     * @param primaryDataset
     *            the name of the dataset under evaluation, so a rule can never suppress readings of
     *            the very table it is running against
     * @param domainPrefix
     *            the dataset's CDISC domain code, used to reject an unresolved {@code --} domain
     * @return the decision, never {@code null}
     */
    public static Decision decide(Rule rule, DatasetResolver resolver, Set<String> reportedDatasets,
            Set<String> crossStandardDatasets, @Nullable String primaryDataset,
            @Nullable String domainPrefix)
    {
        return decide(rule, rule.getCheckExpr(), resolver, reportedDatasets, crossStandardDatasets,
                primaryDataset, domainPrefix);
    }


    /**
     * Plan C &#167;3.4 — {@link #decide} against <b>one declared check level's</b> expression
     * instead of the rule's strictest.
     *
     * <p>
     * &#9873; <b>What the suppression means per level, and why.</b> Which datasets are absent is a
     * property of the <em>run's data</em>, so it is the same fact at every level. The
     * <em>rewrite</em>, though, is a property of the level's own tree: dependency-scoped
     * suppression (K5b) folds exactly the boolean leaves that read the absent dataset, and only
     * this level's leaves are in this level's tree. So each level is decided on its own expression,
     * and:
     * </p>
     * <ul>
     * <li>a level whose Check <b>collapses</b> is constant-{@code false} — it can claim nothing, so
     * it is not evaluated;</li>
     * <li>the <b>rule</b> reports {@code SKIPPED} only when <em>every</em> runnable level collapsed
     * — which, for a single-level rule, is exactly the pre-Plan-C condition and the pre-Plan-C skip
     * reason;</li>
     * <li>a level that survives evaluates its own rewritten expression, so a rule can legitimately
     * lose its {@code ERROR} rung to an absent dataset and still report at {@code INFO}.</li>
     * </ul>
     *
     * <p>
     * The dataset candidates ({@code Match_Datasets} names, {@code Operations[].domain}, dotted
     * Check references) are read from the rule <em>and</em> from {@code checkExpr}, exactly as the
     * single-expression form does — a candidate the level never dereferences is dropped from the
     * reported set by the same {@code readsAny} filter.
     * </p>
     *
     * @param rule
     *            the rule about to be executed
     * @param checkExpr
     *            the level's compiled expression; {@code null} yields {@link Decision#NONE}
     * @param resolver
     *            the run's dataset resolver
     * @param reportedDatasets
     *            the run's presence-rule coverage
     * @param crossStandardDatasets
     *            dataset names belonging to a standard this run does not validate
     * @param primaryDataset
     *            the dataset under evaluation
     * @param domainPrefix
     *            the dataset's CDISC domain code
     * @return the decision for this level, never {@code null}
     */
    public static Decision decide(Rule rule, @Nullable Expr checkExpr, DatasetResolver resolver,
            Set<String> reportedDatasets, Set<String> crossStandardDatasets,
            @Nullable String primaryDataset, @Nullable String domainPrefix)
    {
        if (reportedDatasets.isEmpty() && crossStandardDatasets.isEmpty())
        {
            return Decision.NONE;
        }
        String ruleId = rule.effectiveId();
        if (ruleId != null && INTENT_OPT_OUT_RULE_IDS.contains(ruleId))
        {
            return Decision.NONE;
        }
        Expr check = checkExpr;
        if (check == null)
        {
            return Decision.NONE;
        }
        Set<String> candidates = referencedDatasets(rule, check);
        if (candidates.isEmpty())
        {
            return Decision.NONE;
        }
        String primary = upper(primaryDataset);
        String prefix = upper(domainPrefix);
        Set<String> splitWidened = splitWidenedCandidates(rule, check);
        Set<String> reportedAbsent = new LinkedHashSet<>();
        Set<String> unsupplied = new LinkedHashSet<>();
        for (String candidate : candidates)
        {
            if (candidate.equals(primary) || candidate.equals(prefix))
            {
                continue;
            }
            boolean covered = reportedDatasets.contains(candidate);
            // A dataset the run already reports stays on the Fix #222 arm even when it is also
            // named by the cross-standard catalogue: its absence IS reported, so the more specific
            // (and more informative) reason wins.
            if (!covered && !crossStandardDatasets.contains(candidate))
            {
                continue;
            }
            if (resolver.resolve(candidate) != null)
            {
                continue;
            }
            // Fix #358 (D7): a split domain (lbch/lbhe/lbur with no standalone LB) counts as
            // PRESENT — every reader whose resolution was widened to the union can genuinely run,
            // so the dependency must not be silenced. Scoped to the candidates whose readers WERE
            // widened: Match_Datasets names (the join sites) and dotted Check references (the
            // OperatorRegistry/ValueResolver sites). An Operations[].domain-only candidate keeps
            // the exact-name test — OperationExecutor still resolves exact (a plan non-goal), so
            // widening its presence would un-skip a rule whose operation then aggregates over
            // nothing (the W34-C1 flood shape). Bounded to two-character domain codes inside
            // SplitDomainResolution (an `adsl` member carrying DOMAIN=ADSL must never make "ADSL"
            // present — no inventory walk happens for names longer than a domain code).
            if (splitWidened.contains(candidate)
                    && SplitDomainResolution.isPresentAsDomain(resolver, candidate))
            {
                continue;
            }
            (covered ? reportedAbsent : unsupplied).add(candidate);
        }
        // A Match_Datasets join / an Operation domain only makes a dataset a CANDIDATE; the
        // reported set must name what was actually silenced, so drop the candidates the Check
        // never dereferences before the message is built.
        reportedAbsent.removeIf(dataset -> !readsAny(check, rule, Set.of(dataset)));
        unsupplied.removeIf(dataset -> !readsAny(check, rule, Set.of(dataset)));

        if (!unsupplied.isEmpty())
        {
            // Fix #218's self-limiting gate: suppressing the whole set must collapse the Check; if
            // any branch survives, the rule could still have fired, so the cross-standard half is
            // dropped entirely and we fall back to the Fix #222 decision below. ⚠ That bounds the
            // KIND of finding removed — an artefact of absence, i.e. a vacuous PASS or a flood —
            // NOT the count: measured, 19 findings go (W34-C1; see the class javadoc).
            Set<String> both = new LinkedHashSet<>(reportedAbsent);
            both.addAll(unsupplied);
            Expr collapsedExpr = suppress(check, rule, both);
            if (isFalse(collapsedExpr))
            {
                return new Decision(collapsedExpr, sorted(reportedAbsent), sorted(unsupplied),
                        true);
            }
        }
        if (reportedAbsent.isEmpty())
        {
            return Decision.NONE;
        }
        Expr rewritten = suppress(check, rule, reportedAbsent);
        return new Decision(rewritten, sorted(reportedAbsent), List.of(), isFalse(rewritten));
    }


    private static List<String> sorted(Set<String> names)
    {
        return names.isEmpty() ? List.of() : List.copyOf(new TreeSet<>(names));
    }

    // ------------------------------------------------------------------------ the rewrite (K5b)


    /**
     * Rewrites {@code expr} so that every boolean leaf reading one of {@code datasets} yields
     * {@code false} ("this branch cannot contribute a finding"), folding {@code and}/{@code or} as
     * it goes. Only {@code and} / {@code or} are descended into; everything else — including
     * {@code not} — is atomic, so {@code not(<reads D>)} suppresses as a whole rather than
     * inverting to a spurious {@code true}.
     *
     * @param expr
     *            the rule's Check expression
     * @param rule
     *            the owning rule, for {@code $}-operation domain resolution
     * @param datasets
     *            the upper-cased absent-and-reported dataset names
     * @return the rewritten expression; {@code Lit(BOOL,false)} when the whole Check collapsed
     */
    // RefactorSwitch (new in Error Prone 2.50.0) suggests reshaping this pattern-matching
    // switch for readability. It is a style-only suggestion over load-bearing rewrite logic,
    // and the arm-per-Expr-kind shape is deliberate and mirrored by its siblings.
    @SuppressWarnings("RefactorSwitch")
    static Expr suppress(Expr expr, Rule rule, Set<String> datasets)
    {
        switch (expr)
        {
        case Expr.And and ->
        {
            List<Expr> parts = new ArrayList<>(and.parts().size());
            for (Expr part : and.parts())
            {
                Expr rewritten = suppress(part, rule, datasets);
                if (isFalse(rewritten))
                {
                    return FALSE;
                }
                parts.add(rewritten);
            }
            return parts.equals(and.parts()) ? and
                    : parts.size() == 1 ? parts.get(0) : new Expr.And(parts);
        }
        case Expr.Or or ->
        {
            List<Expr> parts = new ArrayList<>(or.parts().size());
            for (Expr part : or.parts())
            {
                Expr rewritten = suppress(part, rule, datasets);
                if (!isFalse(rewritten))
                {
                    parts.add(rewritten);
                }
            }
            if (parts.isEmpty())
            {
                return FALSE;
            }
            return parts.equals(or.parts()) ? or
                    : parts.size() == 1 ? parts.get(0) : new Expr.Or(parts);
        }
        default ->
        {
            return readsAny(expr, rule, datasets) ? FALSE : expr;
        }
        }
    }

    /**
     * The suppression marker: <i>"this branch cannot contribute a finding"</i>. It only ever
     * survives at the <b>root</b> — an {@code and} holding it collapses to it and an {@code or}
     * drops it — and a root {@code false} is exactly the collapse that returns {@code SKIPPED}
     * before any evaluation. So the native compiler never sees a bare boolean literal from here.
     */
    private static final Expr FALSE = new Expr.Lit(Expr.LitKind.BOOL, false);

    private static boolean isFalse(Expr expr)
    {
        return expr instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.BOOL
                && Boolean.FALSE.equals(lit.value());
    }

    // ------------------------------------------------------------------- foreign-dataset reading


    /**
     * The candidates whose presence test is widened to split domains (Fix #358, D7): the
     * upper-cased {@code Match_Datasets} names (the join sites resolve a split domain to its union)
     * and the dotted Check references (the {@code OperatorRegistry} / {@code ValueResolver} dotted
     * forms union too — review F2), so a rule that reads {@code LB} through either surface is
     * genuinely runnable on a split submission and must not be silenced. An
     * {@code Operations[].domain}-only candidate is deliberately NOT here: it still resolves by
     * exact name downstream, so its candidates keep the exact-name presence test — the one stated
     * exception to the skip-predicate/{@code ds_exists} lockstep (see the class Javadoc).
     */
    private static Set<String> splitWidenedCandidates(Rule rule, Expr check)
    {
        Set<String> out = new LinkedHashSet<>();
        List<MatchDataset> matches = rule.getMatchDatasets();
        if (matches != null)
        {
            for (MatchDataset match : matches)
            {
                addDataset(out, match.getName());
            }
        }
        collectDotted(check, out);
        return out;
    }


    /**
     * Whether this rule reaches {@code name} <b>only</b> through a surface that still resolves by
     * <em>exact</em> name downstream — in which case a {@code Requirements.Datasets} declaration on
     * it must use the exact-name predicate, not the widened {@code isPresentAsDomain}.
     *
     * <p>
     * ⚠⚠ <b>Why this exists rather than one unconditional predicate.</b>
     * {@code Requirements.Datasets} is checked <em>before</em> {@link #decide}, so a declared
     * requirement bypasses this class entirely. If the requirement gated on the <b>widened</b> fact
     * while the rule's actual reader still resolved <b>exact</b>, the rule would un-skip on a split
     * submission and then evaluate against an empty operand — the {@code W34-C1} flood shape, which
     * is precisely what {@link #splitWidenedCandidates}' exclusion of {@code Operations[].domain}
     * exists to prevent ({@code Fix #358} / D7's one stated exception; see the class javadoc). The
     * live instance is {@code CORE-000208}, whose entire {@code TA} dependency is
     * {@code Operations[0].domain} and whose {@code distinct} operation still resolves {@code TA}
     * exactly.
     * </p>
     *
     * <p>
     * ⭐ The partition is <b>recomputed from this class's own two sets</b> rather than authored, so
     * it cannot drift: the day the widening follow-up admits {@code Operations[].domain}, both move
     * together. "Mirror, never weaken" ({@code PLAN-match-datasets-split-union.md} &#167;9).
     * </p>
     *
     * <p>
     * A name the rule does not reach through <em>any</em> of the three surfaces answers
     * {@code false} — the widened predicate. That is the right answer, and for a reason the surface
     * dichotomy cannot express: the ten {@code ds_exists}-guarded migration candidates name their
     * dataset only inside the presence call, which {@link #collectDotted} steps over by design
     * ({@code PRESENCE_CALLS}). For them {@code Requirements.Datasets} evaluates
     * {@code SplitDomainResolution.isPresentAsDomain} and {@code ds_exists} evaluates
     * {@code OperatorRegistry.existsAsDataset}, which <em>is</em> that same method since
     * {@code Fix #358} — so the move is predicate-identical by construction, on split and unsplit
     * submissions alike.
     * </p>
     *
     * @param rule
     *            the declaring rule
     * @param name
     *            the declared dataset name, already upper-cased
     * @return {@code true} when the exact-name predicate must be used for this entry
     */
    static boolean resolvesExactOnly(Rule rule, String name)
    {
        Expr check = rule.getCheckExpr();
        Set<String> referenced = check == null ? referencedDatasetsWithoutCheck(rule)
                : referencedDatasets(rule, check);
        if (!referenced.contains(name))
        {
            return false;
        }
        Set<String> widened = check == null ? splitWidenedCandidatesWithoutCheck(rule)
                : splitWidenedCandidates(rule, check);
        return !widened.contains(name);
    }


    /**
     * {@link #referencedDatasets} for a rule with no native {@code checkExpr} (an externally
     * supplied or legacy-only rule): the declared surfaces only, since the dotted walk needs a
     * raised expression. Conservative in the safe direction — a name reached by a dotted read this
     * cannot see falls back to the widened predicate, matching what that read resolves.
     */
    private static Set<String> referencedDatasetsWithoutCheck(Rule rule)
    {
        Set<String> out = new LinkedHashSet<>();
        List<MatchDataset> matches = rule.getMatchDatasets();
        if (matches != null)
        {
            for (MatchDataset match : matches)
            {
                addDataset(out, match.getName());
            }
        }
        List<Operation> operations = rule.getOperations();
        if (operations != null)
        {
            for (Operation operation : operations)
            {
                addDataset(out, operation.getDomain());
            }
        }
        return out;
    }


    /** {@link #splitWidenedCandidates} for a rule with no native {@code checkExpr}. */
    private static Set<String> splitWidenedCandidatesWithoutCheck(Rule rule)
    {
        Set<String> out = new LinkedHashSet<>();
        List<MatchDataset> matches = rule.getMatchDatasets();
        if (matches != null)
        {
            for (MatchDataset match : matches)
            {
                addDataset(out, match.getName());
            }
        }
        return out;
    }


    /**
     * Every foreign dataset this rule could read, across all three surfaces the corpus uses: a
     * {@code Match_Datasets} join, an {@code Operations} entry's {@code domain:}, and a dotted
     * reference in the Check. Declared surfaces are included so a Match_Datasets/Operations name is
     * a <em>candidate</em>; whether the Check actually dereferences it is settled by
     * {@link #suppress}.
     */
    static Set<String> referencedDatasets(Rule rule, Expr check)
    {
        Set<String> out = new LinkedHashSet<>();
        List<MatchDataset> matches = rule.getMatchDatasets();
        if (matches != null)
        {
            for (MatchDataset match : matches)
            {
                addDataset(out, match.getName());
            }
        }
        List<Operation> operations = rule.getOperations();
        if (operations != null)
        {
            for (Operation operation : operations)
            {
                addDataset(out, operation.getDomain());
            }
        }
        collectDotted(check, out);
        return out;
    }


    private static void collectDotted(Expr expr, Set<String> out)
    {
        switch (expr)
        {
        case Expr.Lit lit ->
        {
            if (lit.value() instanceof List<?> elements)
            {
                for (Object element : elements)
                {
                    if (element instanceof Expr nested)
                    {
                        collectDotted(nested, out);
                    }
                }
            }
        }
        case Expr.Ref ref ->
        {
            if (ref.kind() == OperandKind.DOTTED_REF)
            {
                addDataset(out, qualifierOf(ref.name()));
            }
        }
        case Expr.Not not -> collectDotted(not.inner(), out);
        case Expr.And and -> and.parts().forEach(p -> collectDotted(p, out));
        case Expr.Or or -> or.parts().forEach(p -> collectDotted(p, out));
        case Expr.Binary binary ->
        {
            collectDotted(binary.left(), out);
            collectDotted(binary.right(), out);
        }
        case Expr.Call call ->
        {
            if (!PRESENCE_CALLS.contains(call.name()))
            {
                if (NAME_ARG_CALLS.contains(call.name()) && !call.args().isEmpty())
                {
                    addDataset(out, qualifierOf(nameOfArg(call.args().get(0))));
                }
                if (call.kwargs().get("domain") instanceof Expr.Lit lit
                        && lit.kind() == Expr.LitKind.STRING)
                {
                    addDataset(out, String.valueOf(lit.value()));
                }
                call.args().forEach(a -> collectDotted(a, out));
                call.kwargs().values().forEach(a -> collectDotted(a, out));
            }
        }
        }
    }


    /**
     * Whether this (sub-)expression reads any of {@code datasets}. Mirrors {@link #collectDotted}'s
     * surfaces and additionally resolves {@code $}-operation references back to their
     * {@code Operations} entry's {@code domain:} — the arm ~80 % of the dependent population lives
     * on, and the one a dotted-only walker cannot see.
     */
    static boolean readsAny(Expr expr, Rule rule, Set<String> datasets)
    {
        return switch (expr)
        {
        case Expr.Lit lit -> lit.value() instanceof List<?> elements && elements.stream()
                .anyMatch(e -> e instanceof Expr nested && readsAny(nested, rule, datasets));
        case Expr.Ref ref -> refReads(ref, rule, datasets, new ArrayList<>());
        case Expr.Not not -> readsAny(not.inner(), rule, datasets);
        case Expr.And and -> and.parts().stream().anyMatch(p -> readsAny(p, rule, datasets));
        case Expr.Or or -> or.parts().stream().anyMatch(p -> readsAny(p, rule, datasets));
        case Expr.Binary binary -> readsAny(binary.left(), rule, datasets)
                || readsAny(binary.right(), rule, datasets);
        case Expr.Call call -> callReads(call, rule, datasets);
        };
    }


    private static boolean callReads(Expr.Call call, Rule rule, Set<String> datasets)
    {
        if (PRESENCE_CALLS.contains(call.name()))
        {
            // The dataset-presence question itself — never a "reading" of the dataset, and
            // suppressing it would silence the very rule that reports the absence.
            return false;
        }
        if (NAME_ARG_CALLS.contains(call.name()) && !call.args().isEmpty()
                && matches(datasets, qualifierOf(nameOfArg(call.args().get(0)))))
        {
            return true;
        }
        if (call.kwargs().get("domain") instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.STRING
                && matches(datasets, String.valueOf(lit.value())))
        {
            return true;
        }
        return call.args().stream().anyMatch(a -> readsAny(a, rule, datasets))
                || call.kwargs().values().stream().anyMatch(a -> readsAny(a, rule, datasets));
    }


    /**
     * @param seen
     *            operation ids already visited, so a cyclic {@code minus} chain cannot recurse
     *            forever (same guard as {@code StudyRuleClassifier})
     */
    private static boolean refReads(Expr.Ref ref, Rule rule, Set<String> datasets,
            List<String> seen)
    {
        return switch (ref.kind())
        {
        case DOTTED_REF -> matches(datasets, qualifierOf(ref.name()));
        case OPERATION_REF -> operationReads(ref.name(), rule, datasets, seen);
        case COLUMN, WILDCARD_COLUMN, BUILTIN -> false;
        };
    }


    private static boolean operationReads(String opRef, Rule rule, Set<String> datasets,
            List<String> seen)
    {
        if (seen.contains(opRef))
        {
            return false;
        }
        seen.add(opRef);
        List<Operation> operations = rule.getOperations();
        if (operations == null)
        {
            return false;
        }
        for (Operation operation : operations)
        {
            if (!opRef.equals(operation.getId()))
            {
                continue;
            }
            if (matches(datasets, operation.getDomain()))
            {
                return true;
            }
            // `minus` composes two other operations by name/subtract rather than by group; a
            // set difference over a suppressed operand is itself suppressed.
            for (String operand : List.of(nullToEmpty(operation.getName()),
                    nullToEmpty(operation.getSubtract())))
            {
                if (operand.startsWith("$") && operationReads(operand, rule, datasets, seen))
                {
                    return true;
                }
                if (matches(datasets, qualifierOf(operand)))
                {
                    return true;
                }
            }
        }
        return false;
    }

    // --------------------------------------------------------------------------------- helpers


    private static boolean matches(Set<String> datasets, @Nullable String name)
    {
        String upper = upper(name);
        // An unresolved `--` domain names no concrete dataset; never suppress on a guess.
        return upper != null && !upper.contains("--") && datasets.contains(upper);
    }


    private static void addDataset(Set<String> out, @Nullable String name)
    {
        String upper = upper(name);
        if (upper != null && !upper.isEmpty() && !upper.contains("--") && !upper.startsWith("$")
                && !"*".equals(upper))
        {
            out.add(upper);
        }
    }


    private static @Nullable String qualifierOf(@Nullable String name)
    {
        if (name == null || name.startsWith("$"))
        {
            return null;
        }
        int dot = name.indexOf('.');
        return dot <= 0 ? null : name.substring(0, dot);
    }


    private static @Nullable String nameOfArg(Expr arg)
    {
        return switch (arg)
        {
        case Expr.Ref ref -> ref.name();
        case Expr.Lit lit when lit.kind() == Expr.LitKind.STRING -> String.valueOf(lit.value());
        default -> null;
        };
    }


    private static String nullToEmpty(@Nullable String value)
    {
        return value == null ? "" : value;
    }


    private static @Nullable String upper(@Nullable String value)
    {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

}
