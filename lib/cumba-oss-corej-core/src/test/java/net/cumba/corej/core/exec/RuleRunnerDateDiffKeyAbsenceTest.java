package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * EC-45 — {@code date_diff_days} with a grouped foreign reference, measured <b>end to end through
 * {@link RuleRunner}</b> on the shipped {@code CDISC-SEND-0202} shape: its {@code Match_Datasets}
 * entry, its binding and its three-conjunct {@code Check}, loaded through {@link RulePackageLoader}
 * (so the {@code Sensitivity} is the loader's; the join is attached afterwards, below). Since
 * runbook W2b ({@code PLAN-operation-replacements} §8) the binding is the composed form
 * {@code date_diff_days(--DTC, min_date(EXSTDTC, domain=EX, group=[…])) + 1} — the retired
 * operation's Mode 2 is a named {@code min_date} argument ({@code GroupedAggregate}), its
 * {@code offset} plain arithmetic — and every claim below is the operation's, re-measured on the
 * function.
 *
 * <p>
 * ⚠ <b>Deliberately WITHOUT the rule's {@code Requirements} block</b> ({@code EX.EXSTDTC} and the
 * {@code USUBJID} / {@code EX.USUBJID} pair). Those gates make the shipped rule SKIP on exactly the
 * B4/B5 studies below — which is the point of them — so keeping them here would make the engine
 * path these tests exist to measure unreachable. What is pinned is the <em>engine</em> contract
 * that the gates then sit in front of: without a gate, an unresolvable reference fires rather than
 * skipping.
 * </p>
 *
 * <p>
 * The failure direction is <b>over-firing</b>, not silence: an unusable reference date makes the
 * day count a missing, and {@code TFDETECT != $days} fires on every populated row. EC-45's ruling
 * is that this is <em>correct</em> — a populated derived value whose inputs cannot support it is
 * unverifiable and worth reporting. Applicability (the reference dataset or column not being
 * submitted at all) belongs to {@code Requirements}, where it is a visible and countable SKIP.
 * </p>
 *
 * <p>
 * ⚠ The {@code Match_Datasets} join row-multiplies the evaluation table and {@code ViolationSink}
 * does no dedup, so raw finding counts are inflated by the child cardinality. Every fixture here
 * therefore carries exactly one {@code EX} row per subject unless the scenario is about the extreme
 * itself.
 * </p>
 */
class RuleRunnerDateDiffKeyAbsenceTest
{

    /**
     * The shipped CDISC-SEND-0202 shape, parameterised by the group key list and whether the
     * {@code Match_Datasets} EX join is declared. The join is attached AFTER loading, with the
     * {@code inner} type the loader would stamp: through the loader a keyed join needs its key in
     * {@code Requirements} (the join-key authoring gate), which is exactly the gate this class
     * stands in front of.
     */
    private static Rule send0202(List<String> group, boolean join)
    {
        String json = "{\"rules\":{\"TEST-SEND-0202\":{" + "\"Core\":{\"Id\":\"TEST-SEND-0202\"},"
                + "\"Bindings\":[{\"name\":\"$days_from_first_dose\",\"expression\":"
                + "\"date_diff_days(--DTC, min_date(EXSTDTC, domain=EX, group=["
                + String.join(", ", group) + "])) + 1\"}],"
                + "\"Check\":{\"expression\":\"is_complete_date(--DTC) and not empty(TFDETECT)"
                + " and TFDETECT != $days_from_first_dose\"},"
                + "\"Outcome\":{\"Message\":\"TFDETECT does not equal the computed interval.\","
                + "\"Output_Variables\":[\"USUBJID\",\"$days_from_first_dose\",\"TFDETECT\"]}}}}";
        try
        {
            Rule rule = RulePackageLoader.loadFromString(json).getRules().get("TEST-SEND-0202");
            assertNull(rule.getLoadError(), rule.getLoadError());
            if (join)
            {
                MatchDataset md = new MatchDataset();
                md.setName("EX");
                md.setKeys(List.of("USUBJID"));
                md.setJoinType("inner"); // RulePackageLoader.normalizeJoinTypes stamps this at load
                rule.setMatchDatasets(List.of(md));
            }
            return rule;
        }
        catch (java.io.IOException e)
        {
            throw new IllegalArgumentException("bad test fixture", e);
        }
    }


    private static Rule send0202(List<String> group)
    {
        return send0202(group, true);
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
        return RealTables.of("TF").str("USUBJID", "S1", "S2")
                .str("TFDTC", "2020-01-11", "2020-01-06").str("TFDETECT", s1Detect, s2Detect)
                .build();
    }


    private static RuleExecutionResult run(Rule rule, Map<String, IDataTable> tables)
    {
        IDataTable tf = tables.get("TF");
        assertTrue(tf != null, "fixture must carry TF");
        return RuleRunnerCalls.execute(rule, tf, inventory(tables), "TF", null);
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
        IDataTable ex = RealTables.of("EX").str("USUBJID", "S1", "S2")
                .str("EXSTDTC", "2020-01-01", "2020-01-01").build();
        RuleExecutionResult res = run(send0202(List.of("USUBJID")), study(tf("11", "6"), ex));
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus());
        assertEquals(List.of(), firedSubjects(res));
    }


    /** A genuinely wrong TFDETECT fires — the rule still does its job. */
    @Test
    void wrongIntervalFires()
    {
        IDataTable ex = RealTables.of("EX").str("USUBJID", "S1", "S2")
                .str("EXSTDTC", "2020-01-01", "2020-01-01").build();
        RuleExecutionResult res = run(send0202(List.of("USUBJID")), study(tf("99", "6"), ex));
        assertEquals(List.of("S1"), firedSubjects(res));
    }

    // ------------------------------------------------------------------
    // B1 / B2 — the reference date is unusable on the partner row
    // ------------------------------------------------------------------


    /**
     * B1 — {@code EXSTDTC} blank for S1. The subject <em>has</em> its partner row, so the inner
     * join keeps it; the aggregate simply has no candidate, S1's reference is a missing and so is
     * its day count. EC-45: the check fires, because a populated TFDETECT the inputs cannot support
     * is unverifiable. S2 is unaffected — B1 is a per-row cause, not a whole-operation one.
     */
    @Test
    void b1_blankReferenceDateOnThePartnerRow_firesForThatSubjectOnly()
    {
        IDataTable ex = RealTables.of("EX").str("USUBJID", "S1", "S2")
                .str("EXSTDTC", "", "2020-01-01").build();
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
        IDataTable ex = RealTables.of("EX").str("USUBJID", "S1", "S2")
                .str("EXSTDTC", "2020-01", "2020-01-01").build();
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
        IDataTable tf = RealTables.of("TF").str("USUBJID", "S1", "S2")
                .str("TFDTC", "2020-01", "2020-01-06") // S1's OWN date is partial
                .str("TFDETECT", "11", "6").build();
        IDataTable ex = RealTables.of("EX").str("USUBJID", "S1", "S2")
                .str("EXSTDTC", "2020-01-01", "2020-01-01").build();
        assertEquals(List.of(), firedSubjects(run(send0202(List.of("USUBJID")), study(tf, ex))));
    }

    // ------------------------------------------------------------------
    // B4 / B5 — whole-operation absence
    // ------------------------------------------------------------------


    /**
     * B4 — {@code EX} carries no {@code EXSTDTC} column at all. The reference is unresolvable, so
     * every populated row reads a missing day count and fires. ⚠ This is NOT a SKIP: a missing
     * answer is not an unavailable provider. Suppressing it belongs in {@code Requirements}
     * ({@code EX.EXSTDTC}), never in the algebra.
     */
    @Test
    void b4_referenceColumnAbsentFromTheForeignDataset_firesEveryPopulatedRow()
    {
        IDataTable ex = RealTables.of("EX").str("USUBJID", "S1", "S2").build();
        RuleExecutionResult res = run(send0202(List.of("USUBJID")), study(tf("11", "6"), ex));
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus(), "unresolvable is not SKIPPED");
        assertEquals(List.of("S1", "S2"), firedSubjects(res));
    }


    /**
     * B5 — no {@code EX} dataset in the study at all. With nothing to join to there is no
     * expansion, the rows survive, and the same missing fires. Same remedy as B4: one
     * {@code Requirements} entry {@code EX.EXSTDTC} covers both, because the qualified form SKIPs
     * for dataset-absent and column-absent alike.
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
        IDataTable ex = RealTables.of("EX").str("USUBJID", "S1", "S2")
                .str("EXSTDTC", "2020-01-01", "2020-01-01").build();
        RuleExecutionResult res = run(send0202(List.of("USUBJID", "RPHASE")),
                study(tf("99", "6"), ex));
        assertEquals(List.of("S1"), firedSubjects(res), "only the wrong one fires");
    }


    /**
     * §4.2, the other half — <b>changed by {@code REG-Q4} QK14 (b)</b> (owner 2026-10-09: a
     * {@code domain=} group member present on one side only leaves the key on both sides, with the
     * WARNING, as a join key does under {@code REG-Q3}). {@code RPHASE} is on EX only, so the
     * foreign extreme groups by {@code USUBJID}: S1's TFDETECT is right and passes, S2's wrong one
     * fires. Until then the producer keyed on the real {@code RPHASE}, the consumer on {@code ""},
     * nothing matched and every populated row fired.
     */
    @Test
    void groupColumnPresentOnOneSideOnlyLeavesTheKeyWithOneWarning()
    {
        IDataTable ex = RealTables.of("EX").str("USUBJID", "S1", "S2").str("RPHASE", "P1", "P1")
                .str("EXSTDTC", "2020-01-01", "2020-01-01").build();
        List<RuleExecutionResult> res = new ArrayList<>();
        List<String> warnings = JoinKeyDropWarningTest.warnings(GroupedAggregate.class, () -> res
                .add(run(send0202(List.of("USUBJID", "RPHASE")), study(tf("11", "99"), ex))));
        assertEquals(List.of("S2"), firedSubjects(res.get(0)),
                "[S1, S2] is the retired \"matches nothing\"");
        assertEquals(
                List.of("[TEST-SEND-0202] min_date group=: "
                        + net.cumba.datatable.merge.KeyDropWarning.format("EX", "RPHASE", "EX")),
                warnings, "exactly one WARNING, naming EX as the side that carries RPHASE");
    }


    /**
     * §4.2's carve-out — <b>RETIRED by runbook W2b</b> (D-W2b-8). The operation refused to key a
     * foreign extreme when <em>no</em> declared group column existed on the foreign dataset, so a
     * blank-keyed evaluation row read "no value" instead of the one whole-dataset extreme. The
     * composed form's reference is a {@code min_date} call, and a named aggregation answers alike
     * at every call site (D5 / D7): with every group column absent from its dataset it forms one
     * whole-table group (EC-44 / {@code Fix #134}, {@code GroupedAggregate} since W5), whose key
     * ({@code ""} for each absent column) a row matches only when its own key is blank too.
     * Unreachable through the shipped rules, whose {@code Requirements} demand the foreign
     * {@code USUBJID} ({@code All_Or_None} with the primary's, which is in {@code All}).
     *
     * <p>
     * Here both TF rows carry a BLANK {@code USUBJID}, so they meet the whole-table group and
     * receive the study-wide earliest {@code EXSTDTC}; their TFDETECT values are the correct day
     * counts against it, so nothing fires — where the operation's carve-out fired both.
     * </p>
     */
    @Test
    void aGroupColumnOnTheRecordSideOnlyAsTheWholeKeyIsARuleError()
    {
        IDataTable tf = RealTables.of("TF").str("USUBJID", "", "")
                .str("TFDTC", "2020-01-11", "2020-01-06").str("TFDETECT", "11", "6").build();
        IDataTable ex = RealTables.of("EX").str("SPARE", "x", "y")
                .str("EXSTDTC", "2020-01-01", "2020-01-01").build();
        // no Match_Datasets: the inner join would otherwise empty the evaluation table
        RuleExecutionResult res = run(send0202(List.of("USUBJID"), false), study(tf, ex));
        assertEquals(RuleExecutionStatus.ERROR, res.getStatus(),
                "QK16 (b): USUBJID is on TF only, so no member is left on both sides — the rule"
                        + " errors, never the study-wide earliest EXSTDTC (W2b's whole-table"
                        + " group, which blank-keyed rows used to meet)");
        assertTrue(String.valueOf(res.getStatusMessage())
                .contains("min_date group= over 'EX' has no usable key"), res.getStatusMessage());
    }


    /**
     * The conflation the W8 key derivation carries — an absent column and a literal {@code ""}
     * render alike — is no longer reached through a one-side column: {@code RPHASE} on TF only
     * leaves the key ({@code REG-Q4} QK14 (b)), so S1's populated and S2's blank {@code RPHASE}
     * both key on {@code USUBJID} and their correct TFDETECT values pass. (Until then S1 could
     * never match and fired, S2's {@code ""} met the collapsed {@code ""} bucket.)
     */
    @Test
    void aBlankAndAPopulatedValueOfAOneSideColumnKeyAlike()
    {
        IDataTable tf = RealTables.of("TF").str("USUBJID", "S1", "S2").str("RPHASE", "P1", "")
                .str("TFDTC", "2020-01-11", "2020-01-06").str("TFDETECT", "11", "6").build();
        IDataTable ex = RealTables.of("EX").str("USUBJID", "S1", "S2")
                .str("EXSTDTC", "2020-01-01", "2020-01-01").build();
        assertEquals(List.of(),
                firedSubjects(run(send0202(List.of("USUBJID", "RPHASE")), study(tf, ex))));
    }


    /**
     * Combined review of runbook W2–W8, W2 M5, re-answered by QK16 (b): a TF row with a populated
     * {@code USUBJID} and one with a blank one over an EX without {@code USUBJID} — the call's only
     * member is on one side only, so the rule ERRORs whatever the rows hold (it used to fire the
     * populated row and match the blank one against the whole-table group).
     */
    @Test
    void aPopulatedAndABlankKeyOverAForeignDatasetLackingTheGroupColumnErrorTheRule()
    {
        IDataTable tf = RealTables.of("TF").str("USUBJID", "S1", "")
                .str("TFDTC", "2020-01-11", "2020-01-06").str("TFDETECT", "11", "6").build();
        IDataTable ex = RealTables.of("EX").str("SPARE", "x", "y")
                .str("EXSTDTC", "2020-01-01", "2020-01-01").build();
        RuleExecutionResult res = run(send0202(List.of("USUBJID"), false), study(tf, ex));
        assertEquals(RuleExecutionStatus.ERROR, res.getStatus());
        assertEquals(res.getStatusMessage(),
                res.getViolations().get(0).getValues().get("__error__"));
    }
}
