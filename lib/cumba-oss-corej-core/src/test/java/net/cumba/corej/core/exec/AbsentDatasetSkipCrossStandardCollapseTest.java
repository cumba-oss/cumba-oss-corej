package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Wave 34, lane C — {@code plans/done/PLAN-fix218-behavioural-verification.md}. The
 * <b>characterisation</b> of what {@code Fix #218}'s cross-standard arm actually does to findings,
 * established by <em>running</em> the corpus rather than by reading the rewrite.
 *
 * <h2>⛔ The shipped javadoc's guarantee is wrong as written</h2> {@link AbsentDatasetSkip}'s class
 * javadoc says the cross-standard arm <i>"changes <b>no findings</b> — only a vacuous {@code PASS}
 * into {@code SKIPPED}"</i>. Measured on 2026-08-11 over every rule of every ADaM-family package:
 * <b>19 rules of {@code rules-core-tig-1-0.json} DO lose findings</b> when the arm engages.
 * {@code isFalse(suppress(check, …))} is a statement about a <em>rewrite</em>; it does not say the
 * suppressed leaves <em>evaluate</em> to {@code false}, and on an absent dataset several standard
 * shapes evaluate to {@code true} for every row:
 * <ul>
 * <li>{@code X not in $op(domain=<absent>)} — the operation yields the empty set and {@code not in}
 * an empty set is vacuously TRUE. This is the collapsing leaf in <b>all 19</b>, and the same defect
 * the board already records against {@code PMDA-AD0253};</li>
 * <li>{@code empty(<absent>.COL)} — an absent column is handled as a present column whose values
 * are all missing, so {@code empty} is TRUE for every row ({@code CORE-000177} /
 * {@code CORE-000262} — both already silenced by {@code Fix #222}, so outside the 19);</li>
 * <li>{@code not var_exists(<absent>.COL)} — TRUE, and when its {@code or}-sibling reads the same
 * dataset the whole disjunction still collapses ({@code CORE-000269} / {@code CORE-000270}, which
 * carry this <em>in addition to</em> the first shape).</li>
 * </ul>
 *
 * <h2>✅ But the failure mode the wave-33 review feared is NOT realised</h2> The arm engages only
 * when suppressing the unsupplied datasets folds the <em>whole</em> Check to {@code false} — i.e.
 * when <b>every</b> satisfying assignment of the Check needs at least one leaf that reads the
 * absent dataset to be true. So any finding the arm removes <em>necessarily</em> depended on a
 * reading of a dataset that was not supplied: it is an <b>artefact of absence</b>, never a finding
 * the run could soundly have made. The direction is therefore <b>flood → SKIPPED</b>, not
 * <i>true-finding → silence</i> — which is the same direction {@code Fix #222} exists to enforce.
 * {@link #theRemovedFindingsAreArtefactsOfAbsence()} demonstrates that empirically: supply the
 * dataset and the very same rows produce nothing.
 *
 * <h2>Scope of the refutation</h2> The whole ADaM-package half of the population
 * ({@code CDISC-AD0053} · {@code AD0204}–{@code AD0210} · {@code AD0367} · {@code AD0899} ·
 * {@code AD0640}–{@code AD0646} and the {@code PMDA-AD} twins — 27 rules) behaves exactly as
 * claimed: {@code EXECUTED}/0 findings → {@code SKIPPED}/0 findings, every one. 26 of the 27 are
 * {@code var_exists}-guarded in the top-level {@code and}; the 27th ({@code CDISC-AD0646}) is
 * unguarded but safe by evaluation, because {@code record_count} over an absent domain is 0 and
 * {@code 0 > 0} is false. Only the TIG package contains unguarded collapse shapes that fire.
 * {@code CrossStandardCollapseCorpusTest} in {@code cumba-oss-corej-rules} asserts that half over
 * the whole shipped corpus.
 */
class AbsentDatasetSkipCrossStandardCollapseTest
{

    /**
     * {@code CDISC-AD0053} as shipped in {@code rules/rules-cdisc-adamig-1-3.json} (read
     * 2026-08-11). The {@code var_exists} conjunct sits in the <b>top-level</b> {@code and}, so on
     * an absent {@code DM} the rule really is vacuous.
     */
    private static final String AD0053 = """
            {"Core":{"Id":"CDISC-AD0053"},"Sensitivity":"Record",
             "Scope":{"Domains":{"Include":["ALL"]}},
             "Match_Datasets":[{"Name":"DM","Keys":["USUBJID"],"Join_Type":"left"}],
             "Check":{"expression":"var_exists(DM.USUBJID) and empty(DM.USUBJID)"},
             "Outcome":{"Message":"m","Output_Variables":["USUBJID"]}}""";

    /**
     * The same rule with its top-level guard deleted — the <b>vacuity control</b> for the test
     * above. If this shape did not move, the harness could not see a finding change at all and the
     * green above would be worthless.
     */
    private static final String AD0053_UNGUARDED = """
            {"Core":{"Id":"CDISC-AD0053"},"Sensitivity":"Record",
             "Scope":{"Domains":{"Include":["ALL"]}},
             "Match_Datasets":[{"Name":"DM","Keys":["USUBJID"],"Join_Type":"left"}],
             "Check":{"expression":"empty(DM.USUBJID)"},
             "Outcome":{"Message":"m","Output_Variables":["USUBJID"]}}""";

    /**
     * {@code CORE-000271} as shipped in {@code rules/rules-core-tig-1-0.json} (read 2026-08-11) —
     * the 16-rule {@code not in $op} majority of the refutation, and one of the shapes actually
     * reachable on a TIG-ADaM run ({@code Domains.Include ALL}, and ADaM datasets carry
     * {@code EPOCH}). The {@code EPOCH} requirement is authored in its post-phase-5 host,
     * {@code Requirements.Variables.All} — the shipped rule's {@code Scope.Variables.Include}
     * migrated there unchanged.
     */
    private static final String CORE_000271 = """
            {"Core":{"Id":"CORE-000271"},"Sensitivity":"Record",
             "Scope":{"Domains":{"Include":["ALL"]}},
             "Requirements":{"Variables":{"All":["EPOCH"]}},
             "Operations":[{"id":"$ta_epoch","expression":"distinct(EPOCH, domain=\\"TA\\")"}],
             "Check":{"expression":"EPOCH not in $ta_epoch and not empty(EPOCH)"},
             "Outcome":{"Message":"m","Output_Variables":["EPOCH"]}}""";

    private static Rule load(String body) throws Exception
    {
        RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"R1\":" + body + "}}");
        Rule rule = pkg.getRules().get("R1");
        assertNull(rule.getLoadError(), "rule must load cleanly: " + rule.getLoadError());
        assertNotNull(rule.getCheckExpr(), "the native Check expression is what decide() walks");
        return rule;
    }


    /** Two ADaM rows carrying a populated {@code USUBJID} and {@code EPOCH}. */
    private static IDataTable adsl()
    {
        return MockTable.of().name("ADSL").col("USUBJID", "S1", "S2")
                .col("EPOCH", "TREATMENT", "FOLLOW-UP").build();
    }


    private static RuleExecutionResult run(Rule rule, IDataTable primary, DatasetResolver resolver,
            Set<String> crossStandard)
    {
        return RuleRunner.execute(rule, primary, resolver, primary.getMetaData().getName(), null,
                null, null, Integer.MAX_VALUE, null, null, null, Set.of(), crossStandard);
    }

    // ------------------------------------------------------- the guarded half: as claimed, exactly


    @Test
    void aTopLevelGuardMakesTheRuleVacuousSoNoFindingMoves() throws Exception
    {
        Rule rule = load(AD0053);
        IDataTable adsl = adsl();
        DatasetResolver noDm = _ -> null;

        RuleExecutionResult before = run(rule, adsl, noDm, Set.of());
        RuleExecutionResult after = run(rule, adsl, noDm, Set.of("DM"));

        assertEquals(RuleExecutionStatus.EXECUTED, before.getStatus());
        assertEquals(0, before.getViolations().size(),
                "the top-level var_exists guard is false, so the rule cannot fire");
        assertEquals(RuleExecutionStatus.SKIPPED, after.getStatus());
        assertEquals(0, after.getViolations().size(),
                "PASS -> SKIPPED with zero findings either side: the claimed behaviour");
    }

    // ------------------------------------------------ ⚑ the vacuity check: the harness CAN go red


    @Test
    void removingThatGuardMakesTheSameRuleFloodInstead() throws Exception
    {
        // ⚑ Without this the test above is not evidence: a harness that cannot observe a finding
        // difference would report "no findings moved" for every input. Deleting the guard leaves
        // `empty(DM.USUBJID)`, which on an absent DM is TRUE for EVERY row — so the control fires
        // twice and the treated run silences both.
        Rule rule = load(AD0053_UNGUARDED);
        IDataTable adsl = adsl();
        DatasetResolver noDm = _ -> null;

        RuleExecutionResult before = run(rule, adsl, noDm, Set.of());
        RuleExecutionResult after = run(rule, adsl, noDm, Set.of("DM"));

        assertEquals(RuleExecutionStatus.EXECUTED, before.getStatus());
        assertEquals(2, before.getViolations().size(),
                "an absent column is all-missing, so `empty` is true on every row");
        assertEquals(RuleExecutionStatus.SKIPPED, after.getStatus());
        assertEquals(0, after.getViolations().size(),
                "the cross-standard arm DOES change findings on an unguarded collapse shape");
    }

    // ------------------------------------ ⛔ the shipped counterexample: findings really do move


    @Test
    void aNotInOnAnUnsuppliedDomainFloodsAndTheArmSilencesIt() throws Exception
    {
        // CORE-000271, shipped. TA is not supplied, so `$ta_epoch` is the empty set and
        // `EPOCH not in {}` is vacuously TRUE for every row. This is a real finding change, and it
        // is what refutes the class javadoc's "this arm changes no findings".
        Rule rule = load(CORE_000271);
        IDataTable adsl = adsl();
        DatasetResolver noTa = _ -> null;

        RuleExecutionResult before = run(rule, adsl, noTa, Set.of());
        RuleExecutionResult after = run(rule, adsl, noTa, Set.of("TA"));

        assertEquals(RuleExecutionStatus.EXECUTED, before.getStatus());
        assertEquals(2, before.getViolations().size(),
                "pre-Fix #218: `x not in {}` fires on every row — a flood, not a pass");
        assertEquals(RuleExecutionStatus.SKIPPED, after.getStatus());
        assertEquals(0, after.getViolations().size());
        assertNotNull(after.getStatusMessage());
        assertTrue(after.getStatusMessage().contains("not supplied to this run"),
                after.getStatusMessage());
    }

    // ---------------------------------- ✅ …but every removed finding was an artefact of absence


    @Test
    void theRemovedFindingsAreArtefactsOfAbsence() throws Exception
    {
        // The empirical half of the theorem. The arm engages only when suppressing the unsupplied
        // datasets folds the WHOLE Check to false — i.e. every satisfying assignment needs a leaf
        // that reads the absent dataset to be true. So a removed finding cannot survive the
        // dataset being supplied. Supply TA with the very EPOCH values the rows carry and the same
        // two rows produce nothing: the pre-Fix #218 findings existed only because TA was missing.
        Rule rule = load(CORE_000271);
        IDataTable adsl = adsl();
        IDataTable ta = MockTable.of().name("TA").col("EPOCH", "TREATMENT", "FOLLOW-UP").build();
        DatasetResolver withTa = Map.of("ADSL", adsl, "TA", ta)::get;

        RuleExecutionResult supplied = run(rule, adsl, withTa, Set.of("TA"));

        assertEquals(RuleExecutionStatus.EXECUTED, supplied.getStatus(),
                "TA resolves, so the arm must leave the rule running");
        assertEquals(0, supplied.getViolations().size(),
                "with TA supplied the rows are conformant — the 2 findings above were absence "
                        + "artefacts, so the arm removed a flood and not a real defect");
    }
}
