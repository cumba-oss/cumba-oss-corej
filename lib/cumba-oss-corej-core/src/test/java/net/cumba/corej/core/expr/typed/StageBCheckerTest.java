package net.cumba.corej.core.expr.typed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Operation;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.VariableRequirement;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The stage-B checker (spec §2, phase 4): per-(rule × dataset) findings named per binding (D41),
 * the D76 absent-column default, the D77b unresolved-wildcard assertion with its D92a/D92b/D93b
 * carve-outs, the D89/D89a filter seam, and the phase-4 constraint that only measured-zero kinds
 * are armed while the column-type mismatch stays the {@code ColumnTypeGate}'s to error (D15).
 */
class StageBCheckerTest
{

    @AfterEach
    void clearObserver()
    {
        StageBChecker.setObserver(null);
    }


    private static Rule rule(String expression)
    {
        Rule rule = new Rule();
        rule.setCheckExpr(CheckExpressionParser.parse(expression));
        return rule;
    }


    private static StageBReport check(String expression, IDataTable table)
    {
        return StageBChecker.check(rule(expression), table, true);
    }


    private static List<StageBFinding> of(StageBReport report, StageBErrorKind kind)
    {
        return report.findings().stream().filter(f -> f.kind() == kind).toList();
    }

    // ------------------------------------------------------------------
    // D15 — the column-type mismatch shadow (observe-only; the gate stays armed)
    // ------------------------------------------------------------------


    @Test
    void anOrderComparisonOverACharColumnIsAMismatchNamingTheBinding()
    {
        IDataTable table = MockTable.of().col("AESEQ", "1", "2").build();
        StageBReport report = check("AESEQ > 5", table);
        List<StageBFinding> mismatches = of(report, StageBErrorKind.COLUMN_TYPE_MISMATCH);
        assertEquals(1, mismatches.size());
        assertEquals("AESEQ", mismatches.get(0).binding());
        assertTrue(mismatches.get(0).message().contains("num(AESEQ)"));
        // D15: the mismatch kind must NOT be armed — erroring stays the ColumnTypeGate's.
        assertEquals(List.of(), report.armedFindings());
    }


    @Test
    void aNumConversionSatisfiesTheNumericDirection()
    {
        IDataTable table = MockTable.of().col("AESEQ", "1", "2").build();
        StageBReport report = check("num(AESEQ) > 5", table);
        assertEquals(List.of(), of(report, StageBErrorKind.COLUMN_TYPE_MISMATCH));
    }


    @Test
    void equalityAgainstAStringLiteralOverANumColumnIsAMismatch()
    {
        IDataTable table = MockTable.of().colDouble("AGE", 64.0).build();
        StageBReport report = check("AGE == \"64\"", table);
        List<StageBFinding> mismatches = of(report, StageBErrorKind.COLUMN_TYPE_MISMATCH);
        assertEquals(1, mismatches.size());
        assertEquals("AGE", mismatches.get(0).binding());
    }


    @Test
    void equalityAgainstANumericLiteralOverACharColumnIsAMismatch()
    {
        IDataTable table = MockTable.of().col("RPRFDY", "1", "abc").build();
        StageBReport report = check("RPRFDY == 1", table);
        assertEquals(1, of(report, StageBErrorKind.COLUMN_TYPE_MISMATCH).size());
    }


    @Test
    void columnVsColumnEqualityWithDisagreeingKindsIsAMismatchNamingBoth()
    {
        IDataTable table = MockTable.of().col("AVALC", "1").colDouble("AVAL", 1.0).build();
        StageBReport report = check("AVALC == AVAL", table);
        List<StageBFinding> mismatches = of(report, StageBErrorKind.COLUMN_TYPE_MISMATCH);
        assertEquals(1, mismatches.size());
        assertTrue(mismatches.get(0).message().contains("AVALC"));
        assertTrue(mismatches.get(0).message().contains("AVAL"));
        assertTrue(mismatches.get(0).message().contains("num(AVALC)"));
    }


    @Test
    void membershipProbesFollowTheListLiteralKind()
    {
        IDataTable table = MockTable.of().col("RPRFDY", "0").colDouble("CNSR", 0.0).build();
        assertEquals(1,
                of(check("RPRFDY in [0, 1]", table), StageBErrorKind.COLUMN_TYPE_MISMATCH).size());
        assertEquals(1,
                of(check("CNSR in [\"0\", \"1\"]", table), StageBErrorKind.COLUMN_TYPE_MISMATCH)
                        .size());
        // A matching kind gates nothing.
        assertEquals(List.of(),
                of(check("RPRFDY in [\"0\", \"1\"]", table), StageBErrorKind.COLUMN_TYPE_MISMATCH));
    }


    @Test
    void aRegexSubjectOverANumColumnIsAMismatch()
    {
        IDataTable table = MockTable.of().colDouble("CNSR", 0.0).build();
        StageBReport report = check("CNSR =~ /^\\d+$/", table);
        assertEquals(1, of(report, StageBErrorKind.COLUMN_TYPE_MISMATCH).size());
    }


    @Test
    void aTemporalFamilyComparisonSetsNoPlainExpectation()
    {
        IDataTable table = MockTable.of().col("AESTDTC", "2020").col("AEENDTC", "2021").build();
        StageBReport report = check("date(AESTDTC) < date(AEENDTC)", table);
        assertEquals(List.of(), of(report, StageBErrorKind.COLUMN_TYPE_MISMATCH));
    }


    @Test
    void arithmeticOperandsAreNumericExpected()
    {
        IDataTable table = MockTable.of().col("AVALC", "1").colDouble("AVAL", 2.0).build();
        StageBReport report = check("AVAL != AVALC / 2", table);
        List<StageBFinding> mismatches = of(report, StageBErrorKind.COLUMN_TYPE_MISMATCH);
        assertEquals(1, mismatches.size());
        assertEquals("AVALC", mismatches.get(0).binding());
    }

    // ------------------------------------------------------------------
    // D55 — date(NUM), observe-only
    // ------------------------------------------------------------------


    @Test
    void dateOverANumericColumnIsTheD55ObservationAndNeverArmed()
    {
        IDataTable table = MockTable.of().colDouble("ASTDT", 20000.0).build();
        StageBReport report = check("date(ASTDT) < date(\"2020-01-01\")", table);
        List<StageBFinding> observed = of(report, StageBErrorKind.DATE_CONVERSION_OVER_NUMERIC);
        assertEquals(1, observed.size());
        assertEquals("ASTDT", observed.get(0).binding());
        assertTrue(observed.get(0).message().contains("date_from_sas_days"));
        assertEquals(List.of(), report.armedFindings());
    }


    @Test
    void dateOverACharColumnObservesNothing()
    {
        IDataTable table = MockTable.of().col("AESTDTC", "2020-01-01").build();
        StageBReport report = check("date(AESTDTC) < date(\"2021-01-01\")", table);
        assertEquals(List.of(), of(report, StageBErrorKind.DATE_CONVERSION_OVER_NUMERIC));
    }

    // ------------------------------------------------------------------
    // D76 — the absent-column default from the rule's own expectation
    // ------------------------------------------------------------------


    @Test
    void anAbsentNumericExpectedColumnDefaultsToNumber()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S1").build();
        StageBReport report = check("ECDOSTOT < 5", table);
        List<StageBFinding> absent = of(report, StageBErrorKind.ABSENT_COLUMN);
        assertEquals(1, absent.size());
        assertEquals("ECDOSTOT", absent.get(0).binding());
        assertTrue(absent.get(0).message().contains("MissingValue.MIS"));
        assertEquals(List.of(), report.armedFindings());
    }


    @Test
    void anAbsentColumnWithoutANumericExpectationDefaultsToString()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S1").build();
        // D76a: "otherwise char" absorbs character-expected AND no expectation.
        List<StageBFinding> charExpected = of(check("AEOUT == \"FATAL\"", table),
                StageBErrorKind.ABSENT_COLUMN);
        assertEquals(1, charExpected.size());
        assertTrue(charExpected.get(0).message().contains("string"));
        List<StageBFinding> noExpectation = of(check("empty(AEOUT)", table),
                StageBErrorKind.ABSENT_COLUMN);
        assertEquals(1, noExpectation.size());
        assertTrue(noExpectation.get(0).message().contains("string"));
    }


    @Test
    void aPresenceProbeArgumentIsNotAnAbsentColumnRead()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S1").build();
        StageBReport report = check("var_exists(\"AEOUT\") and not var_exists(AESMIE)", table);
        assertEquals(List.of(), of(report, StageBErrorKind.ABSENT_COLUMN));
    }


    @Test
    void aDottedReferenceFilesNoStageBFindingAlthoughItNowRecordsAnExpectation()
    {
        // ⚑ TARGET-INVARIANT(null-free-value-channel), PLAN-null-free-value-channel phase 4's
        // BLAST-RADIUS PIN. TypeExpectations now records the dotted form's expectation (owner
        // ruling 2026-09-18 — a dotted variable is a first-class variable), and that recording
        // feeds THIS gate. It must file nothing: a dotted name is resolved through the
        // Match_Datasets join map at evaluation time, and this seam holds only the PRIMARY
        // dataset's metadata, in which "DM.AGE" is never a column. Were the dotted name admitted
        // to valueReadColumns(), every dotted reference in every rule would be reported ABSENT
        // from the primary dataset — false positives, not defects.
        Rule rule = rule("DM.AGE > 30 and DM.ARM == \"PLACEBO\"");
        MatchDataset match = new MatchDataset();
        match.setName("DM");
        rule.setMatchDatasets(List.of(match));
        IDataTable table = MockTable.of().col("USUBJID", "S1").build();
        StageBReport report = StageBChecker.check(rule, table, true);
        assertEquals(List.of(), of(report, StageBErrorKind.ABSENT_COLUMN),
                "a dotted reference is not an absent PRIMARY column");
        assertEquals(List.of(), of(report, StageBErrorKind.COLUMN_TYPE_MISMATCH),
                "nor can its declared kind be judged here — the joined metadata is not in hand");
        assertEquals(List.of(), of(report, StageBErrorKind.DATE_CONVERSION_OVER_NUMERIC));
        assertEquals(List.of(), report.armedFindings());
        // ⭐ CONTROL: the very same gate, over the very same table, DOES file for the bare form of
        // the same names — so the four emptinesses above are the dotted exclusion, not a checker
        // that has stopped checking.
        assertEquals(1, of(StageBChecker.check(rule("AGE > 30"), table, true),
                StageBErrorKind.ABSENT_COLUMN).size());
    }


    @Test
    void aJoinCarryingRuleSaysAbsenceMayResolveViaTheJoin()
    {
        Rule rule = rule("DMAGE < 5");
        MatchDataset match = new MatchDataset();
        match.setName("DM");
        rule.setMatchDatasets(List.of(match));
        StageBReport report = StageBChecker.check(rule, MockTable.of().col("USUBJID", "S1").build(),
                true);
        List<StageBFinding> absent = of(report, StageBErrorKind.ABSENT_COLUMN);
        assertEquals(1, absent.size());
        assertTrue(absent.get(0).message().contains("Match_Datasets"));
    }

    // ------------------------------------------------------------------
    // D77b — no `--` survives specialisation
    // ------------------------------------------------------------------


    @Test
    void aSurvivingWildcardReferenceIsAnArmedSpecialiserDefect()
    {
        IDataTable table = MockTable.of().col("AEOCCUR", "N").build();
        StageBReport report = check("--OCCUR != \"N\"", table);
        List<StageBFinding> wildcards = of(report, StageBErrorKind.UNRESOLVED_WILDCARD);
        assertEquals(1, wildcards.size());
        assertEquals("--OCCUR", wildcards.get(0).binding());
        assertTrue(report.armedFindings().contains(wildcards.get(0)));
    }


    @Test
    void withoutAConcreteContractTheWildcardAssertionDoesNotApply()
    {
        // A degraded / synthetic context resolves nothing on purpose (RuleSpecialiser returns
        // the rule unchanged when no domain code is available) — D77b's contract does not hold
        // there and the checker must not pretend it does.
        IDataTable table = MockTable.of().col("AEOCCUR", "N").build();
        StageBReport report = StageBChecker.check(rule("--OCCUR != \"N\""), table, false);
        assertEquals(List.of(), of(report, StageBErrorKind.UNRESOLVED_WILDCARD));
    }


    @Test
    void aSurvivingExistsLiteralWildcardIsCaught()
    {
        // D93c: var_exists("--X") IS specialised (309 corpus sites), so one surviving is the
        // same specialiser defect as a surviving wildcard reference.
        IDataTable table = MockTable.of().col("AEOCCUR", "N").build();
        List<StageBFinding> wildcards = of(check("var_exists(\"--OCCUR\")", table),
                StageBErrorKind.UNRESOLVED_WILDCARD);
        assertEquals(1, wildcards.size());
        assertEquals("--OCCUR", wildcards.get(0).binding());
    }


    @Test
    void theWildcardCarveOutsAreExempt()
    {
        IDataTable table = MockTable.of().col("QNAM", "X").build();
        // D92a: the variable_count family's first argument is the pre-resolution template —
        // exempt both as a quoted literal (not an exists probe) and as a bare operand.
        assertEquals(List.of(), of(check("variable_count(\"--DTC\") > 0", table),
                StageBErrorKind.UNRESOLVED_WILDCARD));
        assertEquals(List.of(),
                of(check("variable_count(--DTC) > 0", table), StageBErrorKind.UNRESOLVED_WILDCARD));
        // D92b: name_pattern= contents are a regex, not a wildcard.
        assertEquals(List.of(), of(check("row_max(QNAM, name_pattern=\"--STDTC\") == \"X\"", table),
                StageBErrorKind.UNRESOLVED_WILDCARD));
        // D93b: a Child:true Match_Datasets name is a pattern, not a reference.
        Rule childRule = rule("QNAM == \"X\"");
        MatchDataset child = new MatchDataset();
        child.setName("SUPP--");
        child.setChild(Boolean.TRUE);
        childRule.setMatchDatasets(List.of(child));
        assertEquals(List.of(), of(StageBChecker.check(childRule, table, true),
                StageBErrorKind.UNRESOLVED_WILDCARD));
        // ... while the same name on a non-Child entry is the defect.
        Rule plainRule = rule("QNAM == \"X\"");
        MatchDataset plain = new MatchDataset();
        plain.setName("SUPP--");
        plainRule.setMatchDatasets(List.of(plain));
        assertEquals(1,
                of(StageBChecker.check(plainRule, table, true), StageBErrorKind.UNRESOLVED_WILDCARD)
                        .size());
    }


    @Test
    void aSurvivingOperationWildcardIsCaught()
    {
        Rule rule = rule("$max_dy > 0");
        Operation op = new Operation();
        op.setId("$max_dy");
        op.setOperator("max");
        op.setName("--DY");
        rule.setOperations(List.of(op));
        StageBReport report = StageBChecker.check(rule, MockTable.of().col("AEDY", "1").build(),
                true);
        List<StageBFinding> wildcards = of(report, StageBErrorKind.UNRESOLVED_WILDCARD);
        assertEquals(1, wildcards.size());
        assertEquals("--DY", wildcards.get(0).binding());
    }

    // ------------------------------------------------------------------
    // D89 / D89a — the filter seam (future-armed; MatchDataset has no Filter yet)
    // ------------------------------------------------------------------


    @Test
    void anUndeclaredUnresolvableFilterColumnIsAnArmedBindError()
    {
        List<StageBFinding> findings = new ArrayList<>();
        List<String> skips = new ArrayList<>();
        StageBChecker.checkFilterBinding("AE", List.of("AEOUT"), Set.of("USUBJID", "AESEQ"), null,
                findings, skips);
        assertEquals(1, findings.size());
        assertEquals(StageBErrorKind.FILTER_UNRESOLVABLE, findings.get(0).kind());
        assertEquals("AE.AEOUT", findings.get(0).binding());
        assertTrue(findings.get(0).kind().armed());
        assertEquals(List.of(), skips);
    }


    @Test
    void aDeclaredUnresolvableFilterColumnSkipsWithAReason()
    {
        VariableRequirement declared = new VariableRequirement();
        declared.setAll(List.of("AE.AEOUT"));
        List<StageBFinding> findings = new ArrayList<>();
        List<String> skips = new ArrayList<>();
        StageBChecker.checkFilterBinding("AE", List.of("AEOUT"), Set.of("USUBJID"), declared,
                findings, skips);
        assertEquals(List.of(), findings);
        assertEquals(1, skips.size());
        assertTrue(skips.get(0).contains("AE.AEOUT"));
    }


    /**
     * D89a reads the {@code Any} facet through {@code anyUnion()} since {@code Any} became groups
     * ({@code plans/done/PLAN-any-variable-sets.md} phase 4): the declaration is about which
     * qualified names the rule owns up to, not about the disjunction, so an entry in group TWO must
     * count exactly like one in group one — or like one in {@code All}.
     */
    @Test
    void aFilterColumnDeclaredInAnySecondGroupSkipsWithAReason()
    {
        VariableRequirement declared = new VariableRequirement();
        declared.setAnyGroups(
                List.of(List.of("DM.ARM", "DM.ARMCD"), List.of("AE.AEOUT", "AE.AESEV")));
        List<StageBFinding> findings = new ArrayList<>();
        List<String> skips = new ArrayList<>();
        StageBChecker.checkFilterBinding("AE", List.of("AEOUT"), Set.of("USUBJID"), declared,
                findings, skips);
        assertEquals(List.of(), findings,
                "a qualified entry in group 2 must reach declaresVariable through anyUnion()");
        assertEquals(1, skips.size());
        assertTrue(skips.get(0).contains("AE.AEOUT"));
    }


    @Test
    void aResolvableFilterColumnPassesSilently()
    {
        List<StageBFinding> findings = new ArrayList<>();
        List<String> skips = new ArrayList<>();
        StageBChecker.checkFilterBinding("AE", List.of("AEOUT"), Set.of("AEOUT"), null, findings,
                skips);
        assertEquals(List.of(), findings);
        assertEquals(List.of(), skips);
    }

    // ------------------------------------------------------------------
    // 5b-J — the widened seam (D104d): Filter wiring and the _matched_ binding
    // ------------------------------------------------------------------


    private static Rule ruleWithFilteredJoin(String expression, String filter)
    {
        Rule rule = rule(expression);
        MatchDataset match = new MatchDataset();
        match.setName("AE");
        match.setKeys(List.of("USUBJID"));
        match.setJoinType("left");
        match.setFilter(filter);
        rule.setMatchDatasets(List.of(match));
        return rule;
    }


    @Test
    void theWiredFilterCheckResolvesTheRightInventory()
    {
        Rule rule = ruleWithFilteredJoin("not AE._matched_", "AEOUT == \"FATAL\"");
        IDataTable primary = MockTable.of().col("USUBJID", "P1").build();
        // AE carries AEOUT -> clean; carries only AESEQ -> armed FILTER_UNRESOLVABLE naming it
        StageBReport clean = StageBChecker.check(rule, primary, true,
                ds -> "AE".equals(ds) ? Set.of("USUBJID", "AEOUT") : null, Set.of());
        assertEquals(List.of(), clean.findings());
        StageBReport missing = StageBChecker.check(rule, primary, true,
                ds -> "AE".equals(ds) ? Set.of("USUBJID", "AESEQ") : null, Set.of());
        List<StageBFinding> armed = of(missing, StageBErrorKind.FILTER_UNRESOLVABLE);
        assertEquals(1, armed.size());
        assertEquals("AE.AEOUT", armed.get(0).binding());
    }


    @Test
    void anUnresolvableFilterDatasetMakesEveryFilterColumnUnresolvable()
    {
        // D89a: "one declaration covers both absences" — the absent DATASET flows through the
        // same per-column decision as the absent column.
        Rule rule = ruleWithFilteredJoin("not AE._matched_", "AEOUT == \"FATAL\"");
        IDataTable primary = MockTable.of().col("USUBJID", "P1").build();
        StageBReport report = StageBChecker.check(rule, primary, true, ds -> null, Set.of());
        assertEquals(1, of(report, StageBErrorKind.FILTER_UNRESOLVABLE).size());
        // and the flag over the same unresolvable dataset files its own armed error
        assertEquals(1, of(report, StageBErrorKind.MATCHED_FLAG_UNRESOLVABLE).size());
    }


    @Test
    void theNullInventoryOverloadKeepsTheChecksOff()
    {
        // The 3-arg overload has no inventory: the Filter/_matched_ checks must stay OFF, not
        // misreport every foreign dataset as unresolvable.
        Rule rule = ruleWithFilteredJoin("not AE._matched_", "AEOUT == \"FATAL\"");
        IDataTable primary = MockTable.of().col("USUBJID", "P1").build();
        StageBReport report = StageBChecker.check(rule, primary, true);
        assertEquals(List.of(), of(report, StageBErrorKind.FILTER_UNRESOLVABLE));
        assertEquals(List.of(), of(report, StageBErrorKind.MATCHED_FLAG_UNRESOLVABLE));
    }


    @Test
    void anUnresolvableFlagDatasetIsAnArmedBindError()
    {
        Rule rule = rule("not AE._matched_");
        MatchDataset match = new MatchDataset();
        match.setName("AE");
        match.setKeys(List.of("USUBJID"));
        match.setJoinType("left");
        rule.setMatchDatasets(List.of(match));
        IDataTable primary = MockTable.of().col("USUBJID", "P1").build();
        StageBReport report = StageBChecker.check(rule, primary, true, ds -> null, Set.of());
        List<StageBFinding> armed = of(report, StageBErrorKind.MATCHED_FLAG_UNRESOLVABLE);
        assertEquals(1, armed.size());
        assertEquals("AE._matched_", armed.get(0).binding());
        assertTrue(armed.get(0).kind().armed());
    }


    @Test
    void aDeclaredFlagDatasetSkipsInsteadOfErroring()
    {
        // D89a — Requirements.Datasets naming the dataset, or any qualified Variables entry on
        // it, is the author's declared intent: skip, never error.
        Rule rule = rule("not AE._matched_");
        net.cumba.corej.core.model.Requirements requirements = new net.cumba.corej.core.model.Requirements();
        requirements.setDatasets(List.of("AE"));
        rule.setRequirements(requirements);
        IDataTable primary = MockTable.of().col("USUBJID", "P1").build();
        StageBReport report = StageBChecker.check(rule, primary, true, ds -> null, Set.of());
        assertEquals(List.of(), report.findings());
        assertEquals(1, report.skips().size());
        assertTrue(report.skips().get(0).contains("AE._matched_"));
    }


    @Test
    void aQualifiedVariablesEntryAlsoCountsAsDeclared()
    {
        Rule rule = rule("not AE._matched_");
        net.cumba.corej.core.model.Requirements requirements = new net.cumba.corej.core.model.Requirements();
        VariableRequirement variables = new VariableRequirement();
        variables.setAll(List.of("AE.AESEQ"));
        requirements.setVariables(variables);
        rule.setRequirements(requirements);
        IDataTable primary = MockTable.of().col("USUBJID", "P1").build();
        StageBReport report = StageBChecker.check(rule, primary, true, ds -> null, Set.of());
        assertEquals(List.of(), report.findings());
        assertEquals(1, report.skips().size());
    }


    @Test
    void aSuppressedFlagDatasetNeedsNothingHere()
    {
        // AbsentDatasetSkip already folded the reader and the absence is reported once by the
        // presence rule (K5b) — stage B must neither error nor skip.
        Rule rule = rule("not AE._matched_");
        IDataTable primary = MockTable.of().col("USUBJID", "P1").build();
        StageBReport report = StageBChecker.check(rule, primary, true, ds -> null, Set.of("AE"));
        assertEquals(List.of(), report.findings());
        assertEquals(List.of(), report.skips());
    }


    @Test
    void aResolvableFlagDatasetPassesSilently()
    {
        Rule rule = rule("not AE._matched_");
        IDataTable primary = MockTable.of().col("USUBJID", "P1").build();
        StageBReport report = StageBChecker.check(rule, primary, true,
                ds -> Set.of("USUBJID", "AESEQ"), Set.of());
        assertEquals(List.of(), report.findings());
        assertEquals(List.of(), report.skips());
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------


    @Test
    void runAndApplyOffersTheReportToTheObserver()
    {
        AtomicReference<StageBReport> seen = new AtomicReference<>();
        StageBChecker.setObserver((rule, report) -> seen.set(report));
        StageBChecker.runAndApply(rule("AESEQ > 5"), MockTable.of().col("AESEQ", "1").build(),
                true);
        assertNotNull(seen.get());
        assertEquals(1, seen.get().observedFindings().size());
    }


    @Test
    void aCheckerFailureIsAFindingNeverAThrowAndNeverArmed()
    {
        Rule broken = new Rule()
        {

            @Override
            public List<MatchDataset> getMatchDatasets()
            {
                throw new IllegalStateException("boom");
            }
        };
        broken.setCheckExpr(CheckExpressionParser.parse("AESEQ > 5"));
        StageBReport report = StageBChecker.check(broken, MockTable.of().col("AESEQ", "1").build(),
                true);
        List<StageBFinding> failures = of(report, StageBErrorKind.CHECKER_FAILURE);
        assertEquals(1, failures.size());
        assertEquals(List.of(), report.armedFindings());
    }


    @Test
    void aRuleWithoutANativeExpressionReportsNothing()
    {
        StageBReport report = StageBChecker.check(new Rule(),
                MockTable.of().col("AESEQ", "1").build(), true);
        assertEquals(List.of(), report.findings());
        assertEquals(List.of(), report.skips());
    }

}
