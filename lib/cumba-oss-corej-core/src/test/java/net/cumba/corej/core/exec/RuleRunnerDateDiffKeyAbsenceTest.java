package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.TextNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.expr.CheckToExpr;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Operation;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Scope;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * EC-45 — {@code date_diff_days} Mode 2, measured <b>end to end through {@link RuleRunner}</b> on
 * the shipped {@code CDISC-SEND-0202} shape: its {@code Match_Datasets} entry, its {@code
 * Operations} block and its three-leaf {@code Check}, with the {@code Sensitivity} and the native
 * {@code checkExpr} the package loader derives.
 *
 * <p>
 * ⚠ <b>Deliberately WITHOUT the rule's {@code Scope} block</b>, including the
 * {@code Scope.Variables.Include: [EX.EXSTDTC]} gate EC-45 itself added. That gate makes the
 * shipped rule SKIP on exactly the B4/B5 studies below — which is the point of it — so keeping it
 * here would make the engine path these tests exist to measure unreachable. What is pinned is the
 * <em>engine</em> contract that the scope gate then sits in front of: without a gate, an
 * unresolvable operation fires rather than skipping. Read the two together.
 * </p>
 *
 * <p>
 * ⚠ The measurement level is the whole point of this class. Mode 2 sits behind a
 * {@code Match_Datasets} entry that the package loader normalises to an {@code inner} join, and
 * {@link KeyMatchRowExpander} replaces the evaluation table <em>before</em> Operations run. A probe
 * that calls {@link OperationExecutor} directly therefore sees causes the shipped pipeline cannot
 * reach — an earlier revision of this plan built a whole three-cause taxonomy on exactly that
 * mistake, and two of the three causes turned out to be structurally unreachable. Every scenario
 * below goes through {@code RuleRunner.execute}.
 * </p>
 *
 * <p>
 * The failure direction is <b>over-firing</b>, not silence: an unusable reference date makes the
 * {@code $}-ref resolve to "no value", the comparison folds that to {@code ""}, and
 * {@code TFDETECT not_equal_to ""} fires on every populated row. EC-45's ruling is that this is
 * <em>correct</em> — a populated derived value whose inputs cannot support it is unverifiable and
 * worth reporting — so these tests pin the firing rather than trying to suppress it. Applicability
 * (the reference dataset or column not being submitted at all) belongs to {@code Scope.Variables},
 * where it is a visible and countable SKIP.
 * </p>
 *
 * <p>
 * ⚠ {@code KeyMatchRowExpander} row-multiplies the evaluation table before Operations run and
 * {@code ViolationSink} does no dedup, so raw finding counts are inflated by the child cardinality.
 * Every fixture here therefore carries exactly one {@code EX} row per subject unless the scenario
 * is about the extreme itself.
 * </p>
 */
class RuleRunnerDateDiffKeyAbsenceTest
{

    /** The shipped CDISC-SEND-0202 shape, parameterised only by the group key list. */
    private static Rule send0202(List<String> group)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-SEND-0202");
        rule.setCore(core);
        rule.setScope(new Scope());
        // The shipped rule derives Sensitivity=Record (RuleClassifier does it at load time), so
        // each unmatched row is its own finding. A hand-built Rule has to say so.
        rule.setSensitivity(Sensitivity.RECORD);
        Outcome outcome = new Outcome();
        outcome.setMessage("TFDETECT does not equal the computed interval.");
        outcome.setOutputVariables(List.of("USUBJID", "$days_from_first_dose", "TFDETECT"));
        rule.setOutcome(outcome);

        MatchDataset md = new MatchDataset();
        md.setName("EX");
        md.setKeys(List.of("USUBJID"));
        md.setJoinType("inner"); // RulePackageLoader.normalizeJoinTypes injects this at load
        rule.setMatchDatasets(List.of(md));

        Operation op = new Operation();
        op.setId("$days_from_first_dose");
        op.setOperator("date_diff_days");
        op.setDomain("EX");
        op.setGroup(group);
        op.setName("--DTC");
        op.setOffset("1");
        op.setReference("EXSTDTC");
        rule.setOperations(List.of(op));

        rule.setCheck(new CheckConditionAll(List.of(
                CheckConditionLeaf.builder().name("--DTC").operator("is_complete_date").build(),
                CheckConditionLeaf.builder().name("TFDETECT").operator("non_empty").build(),
                CheckConditionLeaf.builder().name("TFDETECT").operator("not_equal_to")
                        .value(TextNode.valueOf("$days_from_first_dose")).build())));
        // The legacy evaluator is retired; the native path is the only path. RulePackageLoader
        // installs this at load time — a hand-built Rule has to do it explicitly.
        rule.setCheckExpr(CheckToExpr.toExpr(rule.getCheck()));
        return rule;
    }


    private static DatasetResolver.WithInventory inventory(Map<String, IDataTable> byName)
    {
        return new DatasetResolver.WithInventory()
        {

            @Override
            public @Nullable IDataTable resolve(String name)
            {
                return name == null ? null : byName.get(name);
            }


            @Override
            public Set<String> availableDatasets()
            {
                return byName.keySet();
            }
        };
    }


    private static Map<String, IDataTable> study(IDataTable... tables)
    {
        Map<String, IDataTable> m = new LinkedHashMap<>();
        for (IDataTable t : tables)
        {
            m.put(t.getMetaData().getName(), t);
        }
        return m;
    }


    /**
     * Two subjects, both with a complete {@code TFDTC} and a populated {@code TFDETECT}. S1's
     * detection is 10 days after 2020-01-01 (+1 offset ⇒ 11); S2's is 5 days after (⇒ 6).
     */
    private static IDataTable tf(String s1Detect, String s2Detect)
    {
        return MockTable.of().name("TF").col("USUBJID", "S1", "S2")
                .col("TFDTC", "2020-01-11", "2020-01-06").col("TFDETECT", s1Detect, s2Detect)
                .build();
    }


    private static RuleExecutionResult run(Rule rule, Map<String, IDataTable> tables)
    {
        IDataTable tf = tables.get("TF");
        assertTrue(tf != null, "fixture must carry TF");
        return RuleRunner.execute(rule, tf, inventory(tables), "TF", null);
    }


    private static List<String> firedSubjects(RuleExecutionResult res)
    {
        return res.getViolations().stream().map(v -> v.getValues().get("USUBJID")).toList();
    }

    // ------------------------------------------------------------------
    // The conformant baseline
    // ------------------------------------------------------------------


    /** Conformant data: both subjects' TFDETECT match the computed interval, nothing fires. */
    @Test
    void conformantStudyFiresNothing()
    {
        IDataTable ex = MockTable.of().name("EX").col("USUBJID", "S1", "S2")
                .col("EXSTDTC", "2020-01-01", "2020-01-01").build();
        RuleExecutionResult res = run(send0202(List.of("USUBJID")), study(tf("11", "6"), ex));
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus());
        assertEquals(List.of(), firedSubjects(res));
    }


    /** A genuinely wrong TFDETECT fires — the rule still does its job. */
    @Test
    void wrongIntervalFires()
    {
        IDataTable ex = MockTable.of().name("EX").col("USUBJID", "S1", "S2")
                .col("EXSTDTC", "2020-01-01", "2020-01-01").build();
        RuleExecutionResult res = run(send0202(List.of("USUBJID")), study(tf("99", "6"), ex));
        assertEquals(List.of("S1"), firedSubjects(res));
    }

    // ------------------------------------------------------------------
    // B1 / B2 — the reference date is unusable on the partner row
    // ------------------------------------------------------------------


    /**
     * B1 — {@code EXSTDTC} blank for S1. The subject <em>has</em> its partner row, so the inner
     * join keeps it; the producer simply has no candidate, publishes no key, and S1 reads "no
     * value". EC-45: the check fires, because a populated TFDETECT the inputs cannot support is
     * unverifiable. S2 is unaffected — B1 is a per-row cause, not a whole-operation one.
     */
    @Test
    void b1_blankReferenceDateOnThePartnerRow_firesForThatSubjectOnly()
    {
        IDataTable ex = MockTable.of().name("EX").col("USUBJID", "S1", "S2")
                .col("EXSTDTC", "", "2020-01-01").build();
        RuleExecutionResult res = run(send0202(List.of("USUBJID")), study(tf("11", "6"), ex));
        assertEquals(List.of("S1"), firedSubjects(res));
    }


    /**
     * B2 — {@code EXSTDTC} is a partial date ({@code 2020-01}) for S1, so no {@code yyyy-MM-dd} can
     * be parsed. Same shape as B1 and the same verdict.
     */
    @Test
    void b2_partialReferenceDate_firesForThatSubjectOnly()
    {
        IDataTable ex = MockTable.of().name("EX").col("USUBJID", "S1", "S2")
                .col("EXSTDTC", "2020-01", "2020-01-01").build();
        RuleExecutionResult res = run(send0202(List.of("USUBJID")), study(tf("11", "6"), ex));
        assertEquals(List.of("S1"), firedSubjects(res));
    }


    /**
     * The authored {@code is_complete_date(--DTC)} guard covers the operation's own minuend column,
     * and {@code all:} intersects independently-evaluated per-leaf bitsets — so a row whose OWN
     * date is defective is masked and never reaches the comparison leaf. This is what lets EC-45
     * fold all the per-row causes into one result without widening: only the foreign-side causes
     * produce findings.
     */
    @Test
    void ownRowDefectIsMaskedByTheAuthoredCompleteDateGuard()
    {
        IDataTable tf = MockTable.of().name("TF").col("USUBJID", "S1", "S2")
                .col("TFDTC", "2020-01", "2020-01-06") // S1's OWN date is partial
                .col("TFDETECT", "11", "6").build();
        IDataTable ex = MockTable.of().name("EX").col("USUBJID", "S1", "S2")
                .col("EXSTDTC", "2020-01-01", "2020-01-01").build();
        assertEquals(List.of(), firedSubjects(run(send0202(List.of("USUBJID")), study(tf, ex))));
    }

    // ------------------------------------------------------------------
    // B4 / B5 — whole-operation absence
    // ------------------------------------------------------------------


    /**
     * B4 — {@code EX} carries no {@code EXSTDTC} column at all. The operation is unresolvable, so
     * every populated row reads "no value" and fires. ⚠ This is NOT a SKIP: only
     * {@code LIBRARY_NOT_AVAILABLE} skips a rule, and a null operation result is not that.
     * Suppressing it belongs in {@code Scope.Variables} ({@code EX.EXSTDTC}), never in the algebra.
     */
    @Test
    void b4_referenceColumnAbsentFromTheForeignDataset_firesEveryPopulatedRow()
    {
        IDataTable ex = MockTable.of().name("EX").col("USUBJID", "S1", "S2").build();
        RuleExecutionResult res = run(send0202(List.of("USUBJID")), study(tf("11", "6"), ex));
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus(), "unresolvable is not SKIPPED");
        assertEquals(List.of("S1", "S2"), firedSubjects(res));
    }


    /**
     * B5 — no {@code EX} dataset in the study at all. With nothing to join to there is no
     * expansion, the rows survive, and the same "no value" fires. Same remedy as B4: one
     * {@code Scope.Variables.Include: EX.EXSTDTC} entry covers both, because the qualified form
     * SKIPs for dataset-absent and column-absent alike.
     */
    @Test
    void b5_foreignDatasetAbsentEntirely_firesEveryPopulatedRow()
    {
        RuleExecutionResult res = run(send0202(List.of("USUBJID")), study(tf("11", "6")));
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus(), "unresolvable is not SKIPPED");
        assertEquals(List.of("S1", "S2"), firedSubjects(res));
    }

    // ------------------------------------------------------------------
    // §4.2 — coupling the two key bases by removing the producer guard
    // ------------------------------------------------------------------


    /**
     * §4.2, the case the removed guard used to destroy: {@code group: [USUBJID, RPHASE]} where
     * {@code RPHASE} is absent from <b>both</b> tables. The absent column renders as {@code ""} on
     * either side, so it cancels and the join proceeds on {@code USUBJID} — every subject gets its
     * own reference date and only the genuinely wrong TFDETECT fires.
     *
     * <p>
     * Before EC-45 the all-or-nothing producer guard returned {@code null} for the whole operation
     * here, so <em>both</em> subjects fired: one column nobody could have keyed on anyway killed a
     * computation that was perfectly well-defined without it.
     * </p>
     */
    @Test
    void groupColumnAbsentFromBothSidesCancelsAndTheJoinProceeds()
    {
        IDataTable ex = MockTable.of().name("EX").col("USUBJID", "S1", "S2")
                .col("EXSTDTC", "2020-01-01", "2020-01-01").build();
        RuleExecutionResult res = run(send0202(List.of("USUBJID", "RPHASE")),
                study(tf("99", "6"), ex));
        assertEquals(List.of("S1"), firedSubjects(res), "only the wrong one fires");
    }


    /**
     * §4.2, the other half: a group column present on one side only is <b>unmeetable in either
     * direction</b>. The producer keys on the real {@code RPHASE} value, the consumer on
     * {@code ""}, no key ever matches, and every populated row reads "no value" and fires. Removing
     * the guard did not change this — it is what the guard already produced, reached honestly.
     */
    @Test
    void groupColumnPresentOnOneSideOnlyMatchesNothing()
    {
        IDataTable ex = MockTable.of().name("EX").col("USUBJID", "S1", "S2")
                .col("RPHASE", "P1", "P1").col("EXSTDTC", "2020-01-01", "2020-01-01").build();
        RuleExecutionResult res = run(send0202(List.of("USUBJID", "RPHASE")),
                study(tf("11", "6"), ex));
        assertEquals(List.of("S1", "S2"), firedSubjects(res));
    }


    /**
     * §4.2's carve-out: when no declared group column is present on the <b>foreign</b> dataset
     * there is no key basis at all, and one foreign extreme would broadcast onto every evaluation
     * row — a plausible wrong number in place of "no value", which is strictly worse than the
     * defect. The operation yields nothing instead, and the rows fire.
     *
     * <p>
     * ⚠ The test is deliberately one-sided. An earlier draft also accepted a column present only on
     * the <em>evaluation</em> side, which defeats the carve-out: the producer would still collapse
     * to a single all-{@code ""} bucket and hand it to every blank-keyed row. A key basis the
     * foreign dataset cannot express is no key basis, whatever the evaluation table carries.
     * </p>
     */
    @Test
    void noGroupColumnOnTheForeignDatasetDoesNotBroadcastOneExtreme()
    {
        // Both TF rows carry a BLANK USUBJID, so they key "" — the same key the keyless producer
        // would build for every EX row. This is the fixture that discriminates: accept a group
        // column present only on the EVALUATION side and these two rows join the collapsed bucket
        // and silently pass on a study-wide dose date.
        IDataTable tf = MockTable.of().name("TF").col("USUBJID", "", "")
                .col("TFDTC", "2020-01-11", "2020-01-06").col("TFDETECT", "11", "6").build();
        IDataTable ex = MockTable.of().name("EX").col("SPARE", "x", "y")
                .col("EXSTDTC", "2020-01-01", "2020-01-01").build();
        Rule rule = send0202(List.of("USUBJID"));
        rule.setMatchDatasets(null); // the inner join would otherwise empty the evaluation table
        RuleExecutionResult res = run(rule, study(tf, ex));
        assertEquals(2, res.getViolations().size(),
                "both rows must read 'no value' rather than a broadcast extreme");
    }


    /**
     * ⚠ <b>The conflation the coupling rests on, pinned as a decision rather than left as an
     * accident.</b> {@code GroupedResult.buildKey} renders an <em>absent column</em> and a
     * <em>literal-{@code ""} value</em> identically as {@code ""}, so when a group column is absent
     * from the foreign dataset only, an evaluation row whose own value for it is {@code ""}
     * <b>does</b> match the collapsed foreign bucket and receives the aggregate over the whole
     * foreign column.
     *
     * <p>
     * That is the reachable half of the EC-43 contract — an absent column is a column whose values
     * are all missing — so the absent-column case behaves exactly as a present-but-all-{@code ""}
     * column. ⚠ Scoped by {@code W38-A1} (Fix #249): a <em>marker-missing</em> evaluation cell now
     * renders its own identity token and no longer matches the collapsed {@code ""} bucket — a
     * {@code MissingValue} equals no string key (ruling part 4) — so this pin holds for {@code ""}
     * blanks, which is what this fixture carries. Here {@code RPHASE} is absent from {@code EX}: S1
     * has a populated {@code RPHASE} and can never match, so it fires; S2's is {@code ""}, so it
     * joins to the study-wide earliest {@code EXSTDTC} and its correct {@code TFDETECT} passes.
     * Tightening this back into the all-or-nothing guard means re-opening Q2.
     * </p>
     */
    @Test
    void aBlankKeyComponentJoinsTheCollapsedForeignBucket()
    {
        IDataTable tf = MockTable.of().name("TF").col("USUBJID", "S1", "S2").col("RPHASE", "P1", "")
                .col("TFDTC", "2020-01-11", "2020-01-06").col("TFDETECT", "11", "6").build();
        IDataTable ex = MockTable.of().name("EX").col("USUBJID", "S1", "S2")
                .col("EXSTDTC", "2020-01-01", "2020-01-01").build();
        assertEquals(List.of("S1"),
                firedSubjects(run(send0202(List.of("USUBJID", "RPHASE")), study(tf, ex))));
    }
}
