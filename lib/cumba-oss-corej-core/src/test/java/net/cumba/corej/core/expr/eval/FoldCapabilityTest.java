package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.RuleSpecialiser;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.typed.TypeExpectations;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * {@link FoldCapability} — {@code PLAN-message-folded-corpus-alignment} rows 1 and 6: the P2 load
 * gate per shape (accept / refuse), {@code mayAbsentFold} against the gate, the moved R2 classifier
 * and {@code Keep_Row_Findings} check, the walks, and the P5 census — per shape, with enumerated
 * dataset-constant leaves, and as a property over random Kleene trees: the fold's verdict on
 * <em>every</em> subset of absent columns is bounded by the all-absent census.
 */
class FoldCapabilityTest
{

    private static Rule load(String members) throws IOException
    {
        String json = "{\"rules\":{\"x\":{\"Core\":{\"Id\":\"T-FC\"}," + members + "}}}";
        Rule rule = RulePackageLoader.loadFromString(json).getRules().get("x");
        assertNotNull(rule, "the fixture must bind");
        return rule;
    }


    /**
     * A Record rule on AE with the given Check, Requirements block (or empty) and Outcome extras.
     */
    private static Rule record(String check, String requirements, String outcomeExtra)
        throws IOException
    {
        return load("\"Sensitivity\":\"Record\",\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},"
                + (requirements.isEmpty() ? ""
                        : "\"Requirements\":{\"Variables\":" + requirements + "},")
                + "\"Check\":{\"expression\":\"" + check + "\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AETERM\"]" + outcomeExtra
                + "}");
    }

    private static final String FOLDED = ",\"Message_Folded\":\"folded\"";

    // ---- P2: the load gate -------------------------------------------------------------------

    @Test
    void anAbsentOnlyRuleWithAnUngatedReadIsAccepted() throws IOException
    {
        Rule rule = record("AEBDSYCD != \\\"X\\\"", "", FOLDED);
        assertNull(rule.getLoadError(), rule.getLoadError());
        assertTrue(FoldCapability.mayAbsentFold(rule));
        assertFalse(FoldCapability.collapsible(rule), "no empty-family leaf: fold only (V5)");
        assertEquals(List.of(), FoldCapability.refusals(rule));
    }


    @Test
    void anAbsentOnlyRuleWhoseEveryReadIsGatedIsRefused() throws IOException
    {
        Rule rule = record("AEBDSYCD != \\\"X\\\" and --TERM == \\\"y\\\"",
                "{\"All\":[\"aebdsycd\",\"--TERM\"]}", FOLDED);
        String error = rule.getLoadError();
        assertNotNull(error, "every read column is required present: nothing can be absent");
        assertTrue(error.contains("can never fold or collapse")
                && error.contains("required present by Requirements.Variables"), error);
        assertFalse(FoldCapability.mayAbsentFold(rule));
    }


    @Test
    void aOneMemberAnyGroupGuaranteesPresenceButALargerOneDoesNot() throws IOException
    {
        Rule one = record("AEBDSYCD != \\\"X\\\"", "{\"Any\":[[\"AEBDSYCD\"]]}", "");
        assertFalse(FoldCapability.mayAbsentFold(one), "Any with one member is All");
        Rule two = record("AEBDSYCD != \\\"X\\\"", "{\"Any\":[\"AEBDSYCD\",\"AETERM\"]}", "");
        assertTrue(FoldCapability.mayAbsentFold(two), "either may be absent");
        assertEquals(Set.of("AEBDSYCD"), FoldCapability.guaranteedPresent(one, null));
        assertEquals(Set.of(), FoldCapability.guaranteedPresent(two, null));
        Rule templated = record("empty(--BDSYCD)", "{\"All\":[\"--BDSYCD\"]}", "");
        assertEquals(Set.of("--BDSYCD"), FoldCapability.guaranteedPresent(templated, null));
        assertEquals(Set.of("AEBDSYCD"), FoldCapability.guaranteedPresent(templated, "AE"));
    }


    @Test
    void aPresencePredicateArgumentIsNotARead() throws IOException
    {
        Rule rule = record("var_exists(AEBDSYCD) and empty(--TERM)", "{\"All\":[\"--TERM\"]}", "");
        assertFalse(FoldCapability.mayAbsentFold(rule),
                "var_exists names AEBDSYCD, it does not read it; --TERM is gated");
        Expr check = ((CheckConditionExpression) rule.getCheck()).expr();
        assertEquals(Set.of("--TERM"), FoldCapability.readColumnNames(check));
        assertEquals(Set.of("AEBDSYCD", "--TERM"), FoldCapability.referencedColumnNames(check));
    }


    @Test
    void aCollapsibleRuleIsAcceptedWhateverItsGate() throws IOException
    {
        Rule rule = record("empty(AEBDSYCD)", "{\"All\":[\"AEBDSYCD\"]}", FOLDED);
        assertNull(rule.getLoadError(), rule.getLoadError());
        assertTrue(FoldCapability.collapsible(rule));
        assertFalse(FoldCapability.mayAbsentFold(rule));
    }


    @Test
    void nonRecordAndVariableCursorStayRefused() throws IOException
    {
        Rule dataset = load(
                "\"Sensitivity\":\"Dataset\",\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},"
                        + "\"Check\":{\"expression\":\"AEBDSYCD != \\\"X\\\"\"},"
                        + "\"Outcome\":{\"Message\":\"m\"" + FOLDED + "}");
        assertNotNull(dataset.getLoadError());
        assertTrue(dataset.getLoadError().contains("RECORD-level"), dataset.getLoadError());
        Rule cursor = record("AEBDSYCD != \\\"X\\\" and varname() == \\\"AETERM\\\"", "", FOLDED);
        assertNotNull(cursor.getLoadError());
        assertTrue(cursor.getLoadError().contains("variable cursor"), cursor.getLoadError());
    }


    @Test
    void keepRowFindingsMustNameAColumnTheCheckReads() throws IOException
    {
        Rule rule = record("empty(AEBDSYCD)", "", FOLDED + ",\"Keep_Row_Findings\":[\"AETERM\"]");
        List<String> errors = FoldCapability.keepRowFindingsErrors(rule);
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.getFirst().contains("'AETERM'"), errors.getFirst());
        assertTrue(
                FoldCapability
                        .keepRowFindingsErrors(record("empty(--BDSYCD) and DM.ARMCD == \\\"x\\\"",
                                "", FOLDED + ",\"Keep_Row_Findings\":[\"--bdsycd\",\"DM.ARMCD\"]"))
                        .isEmpty(),
                "compared as written, case-insensitively, dotted included");
    }


    @Test
    void plainColumnLeafNamesTakeOnlyEmptyFamilyLeavesOverPlainReferences()
    {
        Set<String> names = FoldCapability.plainColumnLeafNames(CheckExpressionParser.parse(
                "not empty(A) and is_missing(--B) and non_empty(DM.C) and present($d) and E != \"\""
                        + " and is_present(F) and any(joined(empty(G)))"));
        assertEquals(Set.of("A", "--B", "F"), names, names.toString());
    }

    // ---- P5: the census ----------------------------------------------------------------------


    /** The census of a Record rule on AE: specialised with prefix AE, the gated columns present. */
    private static FoldCapability.AbsentFoldCensus census(Rule rule)
    {
        Rule spec = Objects.requireNonNull(RuleSpecialiser.specialise(rule, null, "AE"));
        // The anchor column: a table needs one, and no rule reads this name.
        MockTable table = MockTable.of().name("AE").col("ZZZCENSUS", "v");
        for (String gated : FoldCapability.guaranteedPresent(spec, "AE"))
        {
            table.col(gated, "v");
        }
        return FoldCapability.absentTrueCapable(spec,
                FoldCapability.censusContext(spec, table.build(), "AE"));
    }


    @Test
    void censusPerShape() throws IOException
    {
        assertTrue(census(record("AEBDSYCD != \\\"X\\\"", "", "")).capable(), "T1: != on absent");
        assertFalse(census(record("AEBDSYCD == \\\"X\\\"", "", "")).capable(),
                "== on absent: FALSE");
        assertTrue(census(record("empty(--BDSYCD)", "", "")).capable(), "empty on absent: TRUE");
        assertFalse(census(record("empty(AEBDSYCD)", "{\"All\":[\"AEBDSYCD\"]}", "")).capable(),
                "gated: present in the census table, the leaf is per row");
        assertFalse(census(record("not empty(AEBDSYCD)", "", "")).capable(), "FALSE on absent");
        FoldCapability.AbsentFoldCensus residual = census(
                record("empty(AEBDSYCD) and AETERM == \\\"x\\\"", "{\"All\":[\"AETERM\"]}", ""));
        assertFalse(residual.capable(),
                "a per-row residual leaves the conjunction UNKNOWN (S2: collapse, not fold)");
        assertTrue(residual.collapsibleByAbsence(), "…which the P3 collapse reports once");
        assertEquals(Set.of("AEBDSYCD"), residual.absentLeafColumns());
        FoldCapability.AbsentFoldCensus neverFires = census(record(
                "AEBDSYCD == \\\"X\\\" and AETERM == \\\"x\\\"", "{\"All\":[\"AETERM\"]}", ""));
        assertFalse(neverFires.capable());
        assertFalse(neverFires.collapsibleByAbsence(), "FALSE on absence: no row ever fires");
        assertFalse(neverFires.demandsKey());
        assertFalse(
                census(record("AEBDSYCD != \\\"X\\\" and AETERM == \\\"x\\\"",
                        "{\"All\":[\"AETERM\"]}", "")).collapsibleByAbsence(),
                "P3 narrowed to the empty family: a != absent leaf stays per row (D111)");
        assertTrue(census(
                record("empty(AEBDSYCD) or AETERM == \\\"x\\\"", "{\"All\":[\"AETERM\"]}", ""))
                        .capable(),
                "any[TRUE, …] decides TRUE");
        FoldCapability.AbsentFoldCensus numeric = census(record("num(AEDUR) > 5", "", ""));
        assertFalse(numeric.capable(), "MIS > 5 is FALSE");
        assertTrue(census(record("not (num(AEDUR) > 5)", "", "")).capable(),
                "not (MIS > 5) is TRUE");
    }


    @Test
    void datasetConstantLeavesAreEnumerated() throws IOException
    {
        FoldCapability.AbsentFoldCensus c = census(
                record("var_exists(AESEV) and AEBDSYCD != \\\"X\\\"", "", ""));
        assertTrue(c.capable(), "var_exists may be TRUE on some dataset");
        assertFalse(
                census(record("var_not_exists(AESEV) or AEBDSYCD == \\\"X\\\"", "", "")).capable(),
                "TRUE only through the presence fact (TRUE absent-blind too): a"
                        + " dataset-constant verdict reports under Message, no key demanded (P4)");
        assertEquals(1, c.enumeratedLeaves());
        assertFalse(c.capped());
        FoldCapability.AbsentFoldCensus facts = census(record(
                "ds_name(\\\"DATA\\\") == \\\"AE\\\" and record_count() > 0 and AEBDSYCD == \\\"X\\\"",
                "", ""));
        assertFalse(facts.capable(), "the absent leaf is FALSE whatever the facts are");
        assertEquals(2, facts.enumeratedLeaves());
        FoldCapability.AbsentFoldCensus binding = census(
                record("AEBDSYCD != $expected", "", ",\"Operations\":[]"));
        assertTrue(binding.capable(), "a $-reference may be a scalar at run time: enumerated");
    }


    @Test
    void aboveTheCapTheRuleCountsAsCapable() throws IOException
    {
        StringBuilder check = new StringBuilder("AEBDSYCD == \\\"X\\\"");
        for (int i = 0; i <= FoldCapability.ENUMERATION_CAP; i++)
        {
            check.append(" and var_exists(C").append(i).append(")");
        }
        FoldCapability.AbsentFoldCensus c = census(record(check.toString(), "", ""));
        assertTrue(c.capped());
        assertTrue(c.capable(), "capped ⇒ capable by policy (the key is demanded)");
        assertEquals(FoldCapability.ENUMERATION_CAP + 1, c.enumeratedLeaves());
        // …unless no leaf can be decided by an absent column at all (PMDA-AD0221's shape: presence
        // facts around a per-row predicate): then the policy has nothing to be cautious about.
        StringBuilder noAbsent = new StringBuilder("len(AETERM) > 1");
        for (int i = 0; i <= FoldCapability.ENUMERATION_CAP; i++)
        {
            noAbsent.append(" and var_exists(C").append(i).append(")");
        }
        FoldCapability.AbsentFoldCensus n = census(
                record(noAbsent.toString(), "{\"All\":[\"AETERM\"]}", ""));
        assertTrue(n.capped());
        assertFalse(n.capable());
        assertFalse(n.collapsibleByAbsence());
    }


    @Test
    void aLeafOverTheCursorIsUndecidedAndAQuantifierLeafFollowsTheRuntimeFold() throws IOException
    {
        // ⚑ A quantifier leaf whose inner condition reads only an absent primary column is
        // classified DATASET_WITH_ABSENT by BroadcastFold.callLevel (the quantifier names are not
        // in its exclusion list), so the runtime fold evaluates it — and the census mirrors the
        // runtime fold exactly, whatever one thinks of that classification (the level calculus is
        // read, never changed here; reported to the coordinator as drift to look at).
        Rule quantified = record("any(joined(AEBDSYCD != \\\"X\\\"))", "", "");
        Rule qspec = Objects.requireNonNull(RuleSpecialiser.specialise(quantified, null, "AE"));
        EvaluationContext qctx = FoldCapability.censusContext(qspec,
                MockTable.of().name("AE").col("ZZZCENSUS", "v").build(), "AE");
        // (no Match_Datasets declared: the loader refuses the join, so the authored tree is the
        // one both the runtime fold and the census read)
        Expr qexpr = qspec.getCheckExpr() != null ? qspec.getCheckExpr()
                : ((CheckConditionExpression) qspec.getCheck()).expr();
        boolean runtimeTrue = BroadcastFold.fold(qexpr, qctx, false) == BroadcastFold.Verdict.TRUE;
        assertEquals(runtimeTrue, census(quantified).capable(),
                "the census answers what the runtime fold answers");
        Rule cursor = record("len(value()) > 1", "", "");
        Rule spec = Objects.requireNonNull(RuleSpecialiser.specialise(cursor, null, "AE"));
        assertFalse(census(cursor).capable(), "loadError=" + cursor.getLoadError() + " checkExpr="
                + spec.getCheckExpr() + " domain=" + spec.getEvaluationDomain());
    }

    // ---- the per-column triggers (what the collapse can report) ------------------------------


    private static FoldCapability.Triggers triggers(Rule rule)
    {
        Rule spec = Objects.requireNonNull(RuleSpecialiser.specialise(rule, null, "AE"));
        MockTable table = MockTable.of().name("AE").col("ZZZCENSUS", "v");
        for (String gated : FoldCapability.guaranteedPresent(spec, "AE"))
        {
            table.col(gated, "v");
        }
        EvaluationContext ctx = FoldCapability.censusContext(spec, table.build(), "AE");
        return FoldCapability.triggers(spec, ctx,
                EmptyColumnCollapse.resolveKeepRowFindings(spec.getOutcome(), ctx));
    }


    @Test
    void aGuardColumnIsNeverATriggerAndTheEmptySideIs() throws IOException
    {
        // The reviewers' finding (phase 2): `not empty(X) and X not in [...]` can never print a
        // folded message — X blank everywhere makes the Check FALSE.
        FoldCapability.Triggers guard = triggers(
                record("not empty(AETERM) and AETERM not in [\\\"Y\\\", \\\"N\\\"]",
                        "{\"All\":[\"AETERM\"]}", ""));
        assertFalse(guard.any(), guard.toString());
        // `not empty(X) and empty(Y)`: Y blank everywhere reports iff some record has X populated.
        FoldCapability.Triggers pair = triggers(
                record("not empty(AETERM) and empty(AEBDSYCD)", "{\"All\":[\"AETERM\"]}", ""));
        assertEquals(Map.of("AEBDSYCD", BroadcastFold.Verdict.UNKNOWN), pair.blank());
        // A lone `empty(X)`: TRUE, no residual.
        assertEquals(Map.of("AEBDSYCD", BroadcastFold.Verdict.TRUE),
                triggers(record("empty(AEBDSYCD)", "", "")).blank());
        // Either of two blank columns reports (an Or): both are triggers.
        FoldCapability.Triggers either = triggers(record("empty(AEBDSYCD) or empty(AETERM)",
                "{\"All\":[\"AETERM\",\"AEBDSYCD\"]}", ""));
        assertEquals(Set.of("AEBDSYCD", "AETERM"), either.blank().keySet());
        // Keep_Row_Findings removes the trigger; a != absent leaf is no trigger (empty family
        // only).
        assertFalse(triggers(record("empty(AEBDSYCD)", "{\"All\":[\"AEBDSYCD\"]}",
                ",\"Keep_Row_Findings\":[\"AEBDSYCD\"]")).any());
        // HIGH-1 (polarity): a leaf constant FALSE over a blank column decides nothing — the
        // negated family in an Or is no trigger; negated through a Not of the leaf it is TRUE.
        assertEquals(Set.of("AEBDSYCD"), triggers(record("empty(AEBDSYCD) or not empty(AETERM)",
                "{\"All\":[\"AETERM\",\"AEBDSYCD\"]}", "")).blank().keySet());
        assertEquals(Map.of("AEBDSYCD", BroadcastFold.Verdict.TRUE),
                triggers(record("not non_empty(AEBDSYCD)", "", "")).blank());
    }


    @Test
    void aLeafInABranchAGatedPresenceFactKillsIsNoTrigger() throws IOException
    {
        // PACK 4 confirmation MED-1, the census side: a presence fact about a GATED column holds in
        // every dataset the rule runs on, so a branch it kills is dead everywhere — its leaves are
        // no trigger (the key would be inert there).
        assertFalse(triggers(record("not var_exists(\\\"AETERM\\\") and empty(AEBDSYCD)",
                "{\"All\":[\"AETERM\"]}", "")).any(), "And with a FALSE sibling");
        assertFalse(triggers(record("var_exists(\\\"AETERM\\\") or empty(AEBDSYCD)",
                "{\"All\":[\"AETERM\"]}", "")).any(), "Or with a TRUE sibling");
        // A presence fact about an UNGATED column differs from dataset to dataset: the census
        // keeps the leaf a trigger (live wherever the column is absent) — the CG0655 / SD1017
        // shape keeps its key for the SVPRESP-absent study.
        assertEquals(Set.of("AEBDSYCD"), triggers(record(
                "var_exists(\\\"AESEV\\\") and AESEV == \\\"Y\\\" or not var_exists(\\\"AESEV\\\")"
                        + " and empty(AEBDSYCD)",
                "", "")).blank().keySet());
    }


    @Test
    void theCensusFoldsTheCompiledCheckSoANestedFormKeepsItsNegation() throws IOException
    {
        // PACK 4 review LOW-2: `not(all[AEXX == "Y"])` with AEXX absent is TRUE on every row
        // (absent reads "", "" != "Y"); a census over the FLATTENED authored leaves saw only
        // `AEXX == "Y"` (FALSE) and called the rule not capable.
        Rule nested = load("\"Sensitivity\":\"Record\","
                + "\"Check\":{\"not\":{\"all\":[{\"expression\":\"AEXX == \\\"Y\\\"\"}]}},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}");
        assertTrue(census(nested).capable(), "the compiled Not(And(==)) folds TRUE on absent AEXX");
    }

    // ---- P5: the monotonicity property ------------------------------------------------------

    private static final String[] COLUMNS =
    {
            "A", "B", "C", "D", "E"
    };

    private static final String[] LEAVES =
    {
            "A != \\\"x\\\"", "B == \\\"y\\\"", "empty(C)", "not empty(D)", "len(E) > 1", "A == B",
            "upper(C) != \\\"Q\\\"", "D in [\\\"p\\\", \\\"q\\\"]", "var_exists(F)",
            "E not in [\\\"\\\"]"
    };

    private static String randomTree(Random random, int depth)
    {
        if (depth == 0 || random.nextInt(3) == 0)
        {
            return LEAVES[random.nextInt(LEAVES.length)];
        }
        return switch (random.nextInt(3))
        {
        case 0 -> "(" + randomTree(random, depth - 1) + " and " + randomTree(random, depth - 1)
                + ")";
        case 1 -> "(" + randomTree(random, depth - 1) + " or " + randomTree(random, depth - 1)
                + ")";
        default -> "not " + randomTree(random, depth - 1);
        };
    }


    /**
     * For random Kleene trees over ungated columns: whenever the run-time fold decides TRUE with
     * some subset of the columns absent, the census (every column absent, dataset-constant leaves
     * enumerated) is capable. The converse direction is not claimed (the census over-approximates).
     */
    @Test
    void theAllAbsentCensusBoundsEverySubsetOfAbsentColumns() throws IOException
    {
        Random random = new Random(20261007L);
        int trueFolds = 0;
        for (int t = 0; t < 60; t++)
        {
            String check = randomTree(random, 3);
            Rule rule = record(check, "", "");
            assertNull(rule.getLoadError(), check + ": " + rule.getLoadError());
            boolean capable = census(rule).capable();
            Rule spec = Objects.requireNonNull(RuleSpecialiser.specialise(rule, null, "AE"));
            Expr expr = Objects.requireNonNull(spec.getCheckExpr());
            TypeExpectations expectations = TypeExpectations.forRule(spec);
            for (int mask = 0; mask < (1 << COLUMNS.length); mask++)
            {
                MockTable table = MockTable.of().name("AE").col("ZZZCENSUS", "v", "w");
                for (int i = 0; i < COLUMNS.length; i++)
                {
                    if ((mask & (1 << i)) != 0)
                    {
                        table.col(COLUMNS[i], "x", "");
                    }
                }
                IDataTable built = table.build();
                EvaluationContext ctx = EvaluationContext.builder().table(built)
                        .numericExpectedColumns(expectations.numericDefaultColumns())
                        .numericExpectedDynamicSites(expectations.numericDynamicSites())
                        .domainPrefix("AE").variableWildcardPrefix("AE").domainName("AE").build();
                BroadcastFold.Verdict v = BroadcastFold.fold(expr, ctx, false);
                if (v == BroadcastFold.Verdict.TRUE)
                {
                    trueFolds++;
                    assertTrue(capable, "fold TRUE with mask " + mask + " on " + check
                            + " but the census says not capable");
                }
            }
        }
        assertTrue(trueFolds > 20, "the property must be exercised: " + trueFolds + " TRUE folds");
    }


    @Test
    void theCensusIsSensitiveToTheVerdict() throws IOException
    {
        // The planted control: the same shape flips with the operator, so a census answering a
        // constant would red one of the two.
        assertTrue(census(record("AEBDSYCD != \\\"X\\\"", "", "")).capable());
        assertFalse(census(record("AEBDSYCD == \\\"X\\\"", "", "")).capable());
    }
}
