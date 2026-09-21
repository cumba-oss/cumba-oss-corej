package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Scope;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for the {@code Match_Datasets} split-domain union (Fix #358,
 * {@code plans/done/PLAN-match-datasets-split-union.md}), measured through
 * {@link RuleRunner#execute}:
 *
 * <ul>
 * <li>the CDISC-AD0898 mechanism — a key join naming a split {@code LB} reaches rows of
 * <em>either</em> member, and the Check-side dotted {@code var_exists} sees the union (before the
 * fix it short-circuited to {@code false} and the rule was a silent no-op);</li>
 * <li>the exact-name path stays byte-identical (a {@code DM} join over a submission that also
 * splits LB);</li>
 * <li>the §3.1 semantics-fork guard — {@link KeyMatchRowExpander} and
 * {@code RuleRunner.buildJoinedDatasets} bind the same joined rows for the same entry;</li>
 * <li>ruling 1 — a split whose members clash on a column type reports
 * {@link RuleExecutionStatus#ERROR} with the {@code __error__} sentinel, on the key path, the Child
 * path and the joined single-leaf equality shape alike.</li>
 * </ul>
 */
class RuleRunnerSplitJoinTest
{

    private static net.cumba.corej.core.model.CheckConditionExpression expr(String source)
    {
        return new net.cumba.corej.core.model.CheckConditionExpression(
                net.cumba.corej.core.expr.CheckExpressionParser.parse(source), source);
    }

    // ------------------------------------------------------------------------------ fixtures


    private static IDataTable lbch()
    {
        return RealTables.of("lbch").str("DOMAIN", "LB").str("USUBJID", "U1", "U1")
                .str("LBSEQ", "1", "2").str("LBORRES", "res-ch-1", "res-ch-2").build();
    }


    private static IDataTable lbhe()
    {
        return RealTables.of("lbhe").str("DOMAIN", "LB").str("USUBJID", "U2").str("LBSEQ", "9")
                .str("LBORRES", "res-he-9").build();
    }


    private static IDataTable lbchClash()
    {
        return RealTables.of("lbch").str("DOMAIN", "LB").str("USUBJID", "U1").lng("LBSTRESN", 1L)
                .str("LBSEQ", "1").build();
    }


    private static IDataTable lbheClash()
    {
        return RealTables.of("lbhe").str("DOMAIN", "LB").str("USUBJID", "U2")
                .str("LBSTRESN", "high").str("LBSEQ", "9").build();
    }


    /** ADaM-BDS-like primary: one row per (USUBJID, LBSEQ) claim against the parent LB. */
    private static IDataTable adlb()
    {
        return RealTables.of("ADLB").str("USUBJID", "U1", "U2", "U3").str("LBSEQ", "1", "9", "7")
                .build();
    }


    private static MatchDataset md(String name, String joinType, String... keys)
    {
        MatchDataset m = new MatchDataset();
        m.setName(name);
        m.setKeys(List.of(keys));
        m.setJoinType(joinType);
        return m;
    }


    /**
     * The concretised CDISC-AD0898 shape: {@code var_exists(`<dom>.<seq>`) and
     * empty(`<dom>.<seq>`)} behind a left key join on {@code [USUBJID, <seq>]}.
     */
    private static Rule ad0898Like(String dom, String seqVar)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-AD0898-" + dom);
        rule.setCore(core);
        rule.setScope(new Scope());
        rule.setSensitivity(Sensitivity.RECORD);
        Outcome outcome = new Outcome();
        outcome.setMessage("The ADaM --SEQ value is not present in the parent SDTM domain.");
        outcome.setOutputVariables(List.of("USUBJID", seqVar));
        rule.setOutcome(outcome);
        rule.setMatchDatasets(List.of(md(dom, "left", "USUBJID", seqVar)));
        String dotted = dom + "." + seqVar;
        rule.setCheck(new CheckConditionAll(
                List.of(expr("var_exists(" + dotted + ")"), expr("empty(" + dotted + ")"))));
        RulePackageLoader.installNativeExpr(rule);
        return rule;
    }


    private static List<Long> rows(RuleExecutionResult result)
    {
        return result.getViolations().stream().map(Violation::getRow).sorted().toList();
    }

    // ------------------------------------------------- the AD0898 mechanism (2b + 2f together)


    @Test
    void splitDomainKeyJoin_firesOnlyWhereNoMemberMatches()
    {
        DatasetResolver.WithInventory inv = RealTables.inventoryOf(adlb(), lbch(), lbhe());
        RuleExecutionResult res = RuleRunner.execute(ad0898Like("LB", "LBSEQ"), adlb(), inv, null,
                null);
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus());
        // U1/1 matches lbch, U2/9 matches lbhe, U3/7 matches neither — the ONLY finding.
        assertEquals(List.of(2L), rows(res));
    }


    @Test
    void splitDomainKeyJoin_withoutTheUnion_wasASilentNoOp()
    {
        // Control for the mechanism: a resolver that cannot enumerate the inventory (the pre-fix
        // observable state for a split submission) leaves var_exists false — zero findings even
        // for the orphan row. This is exactly the CDISC-AD0898 silent no-op of plan §1.
        Map<String, IDataTable> tables = Map.of("LBCH", lbch(), "LBHE", lbhe());
        DatasetResolver plain = name -> name == null ? null : tables.get(name);
        RuleExecutionResult res = RuleRunner.execute(ad0898Like("LB", "LBSEQ"), adlb(), plain, null,
                null);
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus());
        assertEquals(List.of(), rows(res), "without an inventory the rule stays a no-op");
    }

    // --------------------------------------------------------------- exact path byte-identical


    @Test
    void exactNameJoin_isUnchangedOnASplitSubmission()
    {
        IDataTable dm = RealTables.of("DM").str("DOMAIN", "DM").str("USUBJID", "U1").str("ARM", "A")
                .build();
        IDataTable ae = RealTables.of("AE").str("DOMAIN", "AE").str("USUBJID", "U1", "U2")
                .str("AETERM", "H", "F").build();
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-DM-EXACT");
        rule.setCore(core);
        rule.setScope(new Scope());
        rule.setSensitivity(Sensitivity.RECORD);
        Outcome outcome = new Outcome();
        outcome.setMessage("subject not in DM");
        outcome.setOutputVariables(List.of("USUBJID"));
        rule.setOutcome(outcome);
        rule.setMatchDatasets(List.of(md("DM", "left", "USUBJID")));
        rule.setCheck(
                new CheckConditionAll(List.of(expr("var_exists(DM.ARM)"), expr("empty(DM.ARM)"))));
        RulePackageLoader.installNativeExpr(rule);
        // Same rule, same primary — once through the WithInventory resolver of a submission that
        // also splits LB, once through a plain exact-name lambda. Identical findings = the exact
        // path is untouched by the union fallback.
        RuleExecutionResult viaInventory = RuleRunner.execute(rule, ae,
                RealTables.inventoryOf(ae, dm, lbch(), lbhe()), null, null);
        Map<String, IDataTable> flat = Map.of("DM", dm, "AE", ae);
        RuleExecutionResult viaLambda = RuleRunner.execute(rule, ae,
                name -> name == null ? null : flat.get(name), null, null);
        assertEquals(rows(viaLambda), rows(viaInventory));
        assertEquals(viaLambda.getStatus(), viaInventory.getStatus());
    }

    // ----------------------------------------------------------- §3.1 semantics-fork guard


    @Test
    void keyMatchExpanderAndBuildJoinedDatasets_bindTheSameJoinedRows()
    {
        DatasetResolver.WithInventory inv = RealTables.inventoryOf(lbch(), lbhe());
        IDataTable primary = adlb();
        MatchDataset entry = md("LB", "left", "USUBJID", "LBSEQ");

        // Path A — the row expander (the corpus path, 231/260 entries).
        KeyMatchRowExpander.KeyMatchExpansion expansion = KeyMatchRowExpander.expand(primary,
                List.of(entry), inv, "R");
        assertNotNull(expansion);
        List<String> viaExpander = new ArrayList<>();
        IDataTable expanded = expansion.table();
        JoinLookup boundLookup = expansion.lookups().get("LB");
        for (long i = 0; i < expanded.getRowCount(); i++)
        {
            viaExpander.add(
                    expanded.getRealRowIndex(i) + ":" + boundLookup.lookup(expanded, i, "LBORRES"));
        }

        // Path B — the fallback key join.
        JoinLookup direct = RuleRunner.buildJoinedDatasets(List.of(entry), primary, inv, null, "R")
                .get("LB");
        assertNotNull(direct);
        List<String> viaLookup = new ArrayList<>();
        for (long r = 0; r < primary.getRowCount(); r++)
        {
            viaLookup.add(r + ":" + direct.lookup(primary, r, "LBORRES"));
        }

        // Unique keys, left join ⇒ one row per primary record on both paths, same bound values.
        assertEquals(viaLookup, viaExpander,
                "the two key-join paths must agree on the bound child rows (§3.1)");
        assertEquals(List.of("0:res-ch-1", "1:res-he-9", "2:null"), viaLookup);
    }


    @Test
    void sidedKeysJoin_resolvesTheSplitUnion() throws Exception
    {
        // No shipped rule uses sided keys yet; pin that the sided branch resolves the union too.
        MatchDataset sided = new ObjectMapper().readValue(
                "{\"Name\":\"LB\",\"Keys\":[{\"left\":\"SUBJ\",\"right\":\"USUBJID\"}]}",
                MatchDataset.class);
        assertTrue(sided.hasSidedKeys());
        IDataTable primary = RealTables.of("ADLB").str("SUBJ", "U1", "U2").build();
        JoinLookup lookup = RuleRunner.buildJoinedDatasets(List.of(sided), primary,
                RealTables.inventoryOf(lbch(), lbhe()), null, "R").get("LB");
        assertNotNull(lookup, "the sided branch must resolve the split domain");
        assertEquals("res-ch-1", lookup.lookup(primary, 0, "LBORRES"));
        assertEquals("res-he-9", lookup.lookup(primary, 1, "LBORRES"));
    }

    // --------------------------------------------------------------- ruling 1: the ERROR shape


    @Test
    void typeClashAcrossMembers_reportsRuleError_keyPath()
    {
        DatasetResolver.WithInventory inv = RealTables.inventoryOf(adlb(), lbchClash(),
                lbheClash());
        RuleExecutionResult res = RuleRunner.execute(ad0898Like("LB", "LBSEQ"), adlb(), inv, null,
                null);
        assertEquals(RuleExecutionStatus.ERROR, res.getStatus());
        assertEquals(1, res.getViolations().size());
        String sentinel = res.getViolations().get(0).getValues().get("__error__");
        assertNotNull(sentinel);
        assertTrue(sentinel.contains("LBSTRESN"), sentinel);
        assertTrue(sentinel.contains("lbch"), sentinel);
        assertTrue(sentinel.contains("lbhe"), sentinel);
        assertTrue(sentinel.contains("cannot be joined"), sentinel);
        assertEquals(sentinel, res.getStatusMessage());
    }


    @Test
    void typeClashAcrossMembers_reportsRuleError_childPath()
    {
        IDataTable supplbch = RealTables.of("SUPPLBCH").str("RDOMAIN", "LB").str("USUBJID", "U1")
                .str("IDVAR", "LBSEQ").str("IDVARVAL", "1").build();
        MatchDataset child = new MatchDataset();
        child.setName("SUPP--");
        child.setChild(true);
        child.setKeys(List.of("USUBJID", "IDVAR", "IDVARVAL"));

        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-000206");
        rule.setCore(core);
        rule.setScope(new Scope());
        rule.setSensitivity(Sensitivity.RECORD);
        Outcome outcome = new Outcome();
        outcome.setMessage("orphan SUPP row");
        outcome.setOutputVariables(List.of("USUBJID"));
        rule.setOutcome(outcome);
        rule.setMatchDatasets(List.of(child));
        rule.setCheck(new CheckConditionAll(List.of(expr("not empty(IDVARVAL)"))));
        RulePackageLoader.installNativeExpr(rule);

        RuleExecutionResult res = RuleRunner.execute(rule, supplbch,
                RealTables.inventoryOf(supplbch, lbchClash(), lbheClash()), null, null);
        assertEquals(RuleExecutionStatus.ERROR, res.getStatus());
        String sentinel = res.getViolations().get(0).getValues().get("__error__");
        assertNotNull(sentinel);
        assertTrue(sentinel.contains("LBSTRESN"), sentinel);
    }


    /**
     * The joined single-leaf equality shape — {@code <col> != LB.<col>} over a key-based
     * {@code Match_Datasets} — maps {@code InvalidJoinedDomainException} to that rule's own ERROR,
     * with the same sentinel shape as the Child path above.
     *
     * <p>
     * ⚑ This was a <em>cohort</em> test: it pinned that the exception produced one ERROR per member
     * rather than one for the whole cohort (the hazard {@code ColumnTypeMismatchException}'s
     * javadoc describes). With the cohort runner retired ({@code PLAN-retire-cohort-runner.md})
     * per-rule is structural, so what survives is the shape's own ERROR mapping — which the
     * Child-path test beside it does not cover.
     * </p>
     */
    @Test
    void typeClashOnJoinedEqualityShape_reportsRuleErrorPerRule()
    {
        Rule a = joinedEqualityRule("TEST-CH-A", "LBSEQ");
        Rule b = joinedEqualityRule("TEST-CH-B", "USUBJID");

        IDataTable primary = adlb();
        DatasetResolver resolver = RealTables.inventoryOf(primary, lbchClash(), lbheClash());
        for (Rule rule : List.of(a, b))
        {
            RuleExecutionResult res = RuleRunner.execute(rule, primary, resolver, null, null);
            assertEquals(RuleExecutionStatus.ERROR, res.getStatus(), rule.effectiveId());
            String sentinel = res.getViolations().get(0).getValues().get("__error__");
            assertNotNull(sentinel);
            assertTrue(sentinel.contains("LBSTRESN"), sentinel);
        }
    }


    /** Single-leaf equality vs a foreign dataset over a key-based join. */
    private static Rule joinedEqualityRule(String id, String var)
    {
        Rule r = new Rule();
        r.setId(id);
        RuleCore core = new RuleCore();
        core.setId(id);
        r.setCore(core);
        Outcome outcome = new Outcome();
        outcome.setMessage("m " + id);
        outcome.setOutputVariables(List.of("USUBJID", var));
        r.setOutcome(outcome);
        r.setCheck(new CheckConditionAll(List.of(expr(var + " != LB." + var))));
        MatchDataset m = new MatchDataset();
        m.setName("LB");
        m.setKeys(List.of("USUBJID"));
        r.setMatchDatasets(List.of(m));
        r.setSensitivity(Sensitivity.RECORD);
        RulePackageLoader.installNativeExpr(r);
        return r;
    }

    // -------------------------------------------------------- 2f unit probes (dotted / ds_exists)


    @Test
    void dottedVarExists_seesTheSplitUnion()
    {
        EvaluationContext ctx = EvaluationContext.builder().table(adlb())
                .datasetResolver(RealTables.inventoryOf(lbch(), lbhe())).build();
        assertTrue(OperatorRegistry.existsAsVariable(ctx, "LB.LBSEQ"),
                "the AD0898 mechanism: dotted var_exists must union a split domain");
        assertFalse(OperatorRegistry.existsAsVariable(ctx, "LB.NOSUCH"));
        assertFalse(OperatorRegistry.existsAsVariable(ctx, "ZZ.LBSEQ"));
    }


    @Test
    void dsExists_countsASplitDomainAsPresent_boundedToDomainCodes()
    {
        IDataTable adsl = RealTables.of("adsl2").str("DOMAIN", "ADSL").str("TRT01P", "A").build();
        EvaluationContext ctx = EvaluationContext.builder().table(adlb())
                .datasetResolver(RealTables.inventoryOf(lbch(), lbhe(), adsl)).build();
        assertTrue(OperatorRegistry.existsAsDataset(ctx, "LB"),
                "D7 widen-both: a split LB is part of the submission");
        assertFalse(OperatorRegistry.existsAsDataset(ctx, "ZZ"));
        assertFalse(OperatorRegistry.existsAsDataset(ctx, "ADSL"),
                "the two-character bound: no domain fallback for a 4-character name");
    }


    @Test
    void outputVariableWildcard_expandsAgainstTheSplitUnion()
    {
        // Site 11: an Output_Variables ${*} wildcard (digits — the TRT01PN shape) over a split
        // foreign domain expands against the union's column set instead of being dropped
        // entirely (pre-fix: the foreign table resolved null and every LB.* output was omitted).
        IDataTable primary = RealTables.of("ADLB").str("USUBJID", "U1").str("LBSEQ", "1").build();
        IDataTable member1 = RealTables.of("lbch").str("DOMAIN", "LB").str("USUBJID", "U1")
                .str("LBSEQ", "1").str("TRT01PN", "d1").build();
        IDataTable member2 = RealTables.of("lbhe").str("DOMAIN", "LB").str("USUBJID", "U2")
                .str("LBSEQ", "9").str("TRT02PN", "d2").build();

        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-OV-WILDCARD");
        rule.setCore(core);
        rule.setScope(new Scope());
        rule.setSensitivity(Sensitivity.RECORD);
        Outcome outcome = new Outcome();
        outcome.setMessage("fires everywhere");
        outcome.setOutputVariables(List.of("USUBJID", "LB.TRT${*}PN"));
        rule.setOutcome(outcome);
        rule.setMatchDatasets(List.of(md("LB", "left", "USUBJID", "LBSEQ")));
        rule.setCheck(new CheckConditionAll(List.of(expr("not empty(USUBJID)"))));
        RulePackageLoader.installNativeExpr(rule);

        RuleExecutionResult res = RuleRunner.execute(rule, primary,
                RealTables.inventoryOf(primary, member1, member2), null, null);
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus());
        assertEquals(1, res.getViolations().size());
        Map<String, String> values = res.getViolations().get(0).getValues();
        assertEquals("d1", values.get("LB.TRT01PN"),
                "the wildcard must expand against the union's columns and read the matched"
                        + " member's value: " + values);
    }


    @Test
    void unqualifiedJoinedColumnReference_doesNOTseeTheSplitUnion()
    {
        // ⚠ The paragraph that stood here described `joinedColumnVector` re-resolving the join by
        // name. That method is DELETED (2026-09-21) and the method name above was inverted with the
        // assertion: an unqualified name does NOT see the union, because it means the primary.
        IDataTable primary = RealTables.of("ADLB").str("USUBJID", "U1", "U3").str("LBSEQ", "1", "7")
                .build();
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-UNQUALIFIED");
        rule.setCore(core);
        rule.setScope(new Scope());
        rule.setSensitivity(Sensitivity.RECORD);
        Outcome outcome = new Outcome();
        outcome.setMessage("joined LBORRES is empty");
        outcome.setOutputVariables(List.of("USUBJID"));
        rule.setOutcome(outcome);
        rule.setMatchDatasets(List.of(md("LB", "left", "USUBJID", "LBSEQ")));
        rule.setCheck(new CheckConditionAll(List.of(expr("empty(LBORRES)"))));
        RulePackageLoader.installNativeExpr(rule);

        RuleExecutionResult res = RuleRunner.execute(rule, primary,
                RealTables.inventoryOf(primary, lbch(), lbhe()), null, null);
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus());
        // ⭐⭐ INVERTED 2026-09-21 (D3 of PLAN-unqualified-name-primary-only). This asserted
        // List.of(1L) with the message "the unqualified joined-column read must see the union, not
        // fold to ALL_MISSING" -- the ruling's negation, added by an earlier review as a
        // requirement. Under the owner's ruling the rule in this fixture is itself a RULE ERROR:
        // `empty(LBORRES)` where only the joined LB carries LBORRES means the PRIMARY's LBORRES,
        // which ADLB does not have.
        // ⇒ LBORRES folds to the absent-column constant "" (D34 #3) on every row, and `empty("")`
        // is true, so BOTH rows fire. Derived from the contract; the split union is irrelevant to a
        // name that never reaches it.
        // ⭐⭐ AND THE GRANULARITY CHANGES, which is site S4's doing and is the half my own
        // contract derivation first got wrong: I predicted [0, 1] (both rows firing record-level)
        // from the VALUE contract alone. The level calculus now answers DATASET_ABSENT for a bare
        // name the primary lacks, so the leaf folds once for the dataset instead of per row, and a
        // single finding replaces N. ⇒ ONE entry, not two.
        // ⚑ Recorded because it is the plan's "granularity" attribution category observed in the
        // wild: S4 does not change what a leaf reads, it changes how many findings a rule emits.
        assertEquals(1, rows(res).size(),
                "an unqualified name means the PRIMARY's column, absent here, so the leaf folds at"
                        + " DATASET level and the rule emits one finding, not one per row");
    }


    /**
     * ⭐ THE MECHANISM, not just the verdict — review round 1 of the plan required this. The test
     * above would also pass if the bare name merely failed to resolve (the unresolved-operand
     * channel, where {@code empty} takes its all-rows branch). That is the wrong reason for the
     * right answer, so this pins that the QUALIFIED form still reads the split union: if the join
     * had silently stopped working, this test fails while the one above still passes.
     */
    @Test
    void theQualifiedFormStillReadsTheSplitUnion()
    {
        IDataTable primary = RealTables.of("ADLB").str("USUBJID", "U1", "U3").str("LBSEQ", "1", "7")
                .build();
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-QUALIFIED");
        rule.setCore(core);
        rule.setScope(new Scope());
        rule.setSensitivity(Sensitivity.RECORD);
        Outcome outcome = new Outcome();
        outcome.setMessage("joined LB.LBORRES is empty");
        outcome.setOutputVariables(List.of("USUBJID"));
        rule.setOutcome(outcome);
        rule.setMatchDatasets(List.of(md("LB", "left", "USUBJID", "LBSEQ")));
        rule.setCheck(new CheckConditionAll(List.of(expr("empty(LB.LBORRES)"))));
        RulePackageLoader.installNativeExpr(rule);

        RuleExecutionResult res = RuleRunner.execute(rule, primary,
                RealTables.inventoryOf(primary, lbch(), lbhe()), null, null);
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus());
        // U1/1 binds lbch's res-ch-1 (non-empty); U3/7 binds no member -> missing -> fires.
        assertEquals(List.of(1L), rows(res),
                "the QUALIFIED read still sees the union -- that path is deliberately unchanged");
    }


    @Test
    void invalidJoinedDomainException_carriesDomainAndMessage()
    {
        InvalidJoinedDomainException e = new InvalidJoinedDomainException("LB", "boom");
        assertEquals("LB", e.domain());
        assertEquals("boom", e.getMessage());
        assertNull(e.getCause());
    }

}
