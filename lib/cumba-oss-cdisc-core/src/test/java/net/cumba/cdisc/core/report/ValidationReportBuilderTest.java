package net.cumba.cdisc.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.exec.RuleExecutionResult;
import net.cumba.cdisc.core.exec.RuleExecutionStatus;
import net.cumba.cdisc.core.exec.Violation;
import net.cumba.cdisc.core.model.Executability;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RuleCore;
import net.cumba.datatable.report.FindingKind;
import net.cumba.datatable.report.FindingScope;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.report.SkippedRuleEntry;
import net.cumba.datatable.report.ValidationFinding;
import net.cumba.datatable.report.ValidationReport;
import net.cumba.datatable.report.ValidationReportMember;
import org.junit.jupiter.api.Test;

class ValidationReportBuilderTest
{

    // ------------------------------------------------------------------
    // Fixture helpers
    // ------------------------------------------------------------------

    private static Rule rule(String coreId)
    {
        Rule r = new Rule();
        r.setId("uuid-" + coreId);
        RuleCore core = new RuleCore();
        core.setId(coreId);
        r.setCore(core);
        return r;
    }


    /** A rule whose cached evaluation domain is {@code aDomain} — what the scope projects from. */
    private static Rule ruleWithDomain(String coreId, net.cumba.cdisc.core.expr.eval.Domain aDomain)
    {
        Rule r = rule(coreId);
        r.setEvaluationDomain(aDomain);
        return r;
    }


    private static RuleExecutionResult withViolations(String ruleId, String message,
            List<Violation> violations)
    {
        return RuleExecutionResult.builder().ruleId(ruleId).message(message).violations(violations)
                .totalRows(100).status(RuleExecutionStatus.EXECUTED).build();
    }


    private static RuleExecutionResult cleanResult(String ruleId)
    {
        return RuleExecutionResult.builder().ruleId(ruleId).message("ok").violations(List.of())
                .totalRows(100).status(RuleExecutionStatus.EXECUTED).build();
    }


    private static RuleExecutionResult errorResult(String ruleId, String message)
    {
        return RuleExecutionResult.builder().ruleId(ruleId).message("").violations(List.of())
                .totalRows(0).status(RuleExecutionStatus.ERROR).statusMessage(message).build();
    }


    private static RuleExecutionResult skippedResult(String ruleId)
    {
        return RuleExecutionResult.builder().ruleId(ruleId).message("").violations(List.of())
                .totalRows(0).status(RuleExecutionStatus.SKIPPED)
                .statusMessage("library not available").build();
    }


    private static Violation v(long row, String... kv)
    {
        Map<String, String> vars = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2)
        {
            vars.put(kv[i], kv[i + 1]);
        }
        return new Violation(row, vars);
    }

    // ------------------------------------------------------------------
    // Happy path
    // ------------------------------------------------------------------


    @Test
    void noFindingsProducesEmptyReport()
    {
        ValidationReportBuilder builder = new ValidationReportBuilder();
        ValidationReport report = builder.build();
        assertNotNull(report);
        assertEquals(List.of(), report.getMembers());
    }


    @Test
    void cleanResultDoesNotProduceFindings()
    {
        ValidationReport report = new ValidationReportBuilder()
                .add("AE", "ae.xpt", rule("CORE-001"), cleanResult("CORE-001")).build();
        assertEquals(List.of(), report.getMembers());
    }


    @Test
    void skippedResultDoesNotProduceFindings()
    {
        ValidationReport report = new ValidationReportBuilder()
                .add("AE", "ae.xpt", rule("CORE-001"), skippedResult("CORE-001")).build();
        assertEquals(List.of(), report.getMembers());
    }

    // ------------------------------------------------------------------
    // Skipped rules
    // ------------------------------------------------------------------


    @Test
    void skippedResultLandsInSkippedRulesWithStatusMessage()
    {
        ValidationReport report = new ValidationReportBuilder()
                .add("AE", "ae.xpt", rule("CORE-001"), skippedResult("CORE-001")).build();

        assertEquals(1, report.getSkippedRules().size());
        SkippedRuleEntry entry = report.getSkippedRules().get(0);
        assertEquals("CORE-001", entry.getCoreId());
        assertEquals("AE", entry.getDataset());
        // The execution-time status message is kept verbatim.
        assertEquals("library not available", entry.getReason());
    }


    @Test
    void skippedRuleMethodRecordsGenerationTimeSkip()
    {
        ValidationReport report = new ValidationReportBuilder().skippedRule("EX", "ex.xpt",
                rule("CORE-000351"), "domain EX not in Scope.Domains.Include [AE]").build();

        assertEquals(List.of(), report.getMembers(), "skips are never findings");
        assertEquals(1, report.getSkippedRules().size());
        SkippedRuleEntry entry = report.getSkippedRules().get(0);
        assertEquals("CORE-000351", entry.getCoreId());
        assertEquals("EX", entry.getDataset());
        assertEquals("domain EX not in Scope.Domains.Include [AE]", entry.getReason());
    }


    @Test
    void skippedRulesAccumulatePerDatasetInInsertionOrder()
    {
        // Skipping is a per-dataset verdict — the same rule yields one entry per dataset.
        ValidationReport report = new ValidationReportBuilder()
                .add("EX", "ex.xpt", rule("CORE-001"), skippedResult("CORE-001"))
                .skippedRule("SUPPEX", null, rule("CORE-001"),
                        "domain SUPPEX not in Scope.Domains.Include [AE]")
                .build();

        assertEquals(2, report.getSkippedRules().size());
        assertEquals("EX", report.getSkippedRules().get(0).getDataset());
        assertEquals("SUPPEX", report.getSkippedRules().get(1).getDataset());
    }


    @Test
    void skippedRuleWithoutCoreIdUsesRuleId()
    {
        Rule r = new Rule();
        r.setId("uuid-xyz");
        ValidationReport report = new ValidationReportBuilder().skippedRule("DM", null, r, "reason")
                .build();
        assertEquals("uuid-xyz", report.getSkippedRules().get(0).getCoreId());
    }


    @Test
    void executedAndErrorResultsDoNotProduceSkippedRules()
    {
        ValidationReport report = new ValidationReportBuilder()
                .add("DM", "dm.xpt", rule("CORE-001"),
                        withViolations("CORE-001", "fail", List.of(v(0))))
                .add("DM", "dm.xpt", rule("CORE-002"), cleanResult("CORE-002"))
                .add("AE", "ae.xpt", rule("CORE-003"), errorResult("CORE-003", "boom")).build();

        assertEquals(List.of(), report.getSkippedRules());
        // EXECUTED/ERROR behaviour unchanged: violation + engine error findings still appear.
        assertEquals(2, report.getMembers().size());
    }


    @Test
    void skippedRuleRejectsNullArguments()
    {
        ValidationReportBuilder builder = new ValidationReportBuilder();
        Rule r = rule("CORE-001");
        assertThrows(NullPointerException.class, () -> builder.skippedRule(null, "f", r, "x"));
        assertThrows(NullPointerException.class, () -> builder.skippedRule("DM", "f", null, "x"));
        assertThrows(NullPointerException.class, () -> builder.skippedRule("DM", "f", r, null));
    }


    @Test
    void violationsBecomeFindingsOnMember()
    {
        RuleExecutionResult result = withViolations("CORE-001", "USUBJID must not be null",
                List.of(v(2, "USUBJID", ""), v(5, "USUBJID", "")));
        ValidationReport report = new ValidationReportBuilder()
                .add("DM", "dm.xpt", rule("CORE-001"), result).build();

        assertEquals(1, report.getMembers().size());
        ValidationReportMember dm = report.getMembers().get(0);
        assertEquals("DM", dm.getDomain());
        assertEquals("dm.xpt", dm.getFileName());

        assertEquals(1, dm.getFindings().size());
        ValidationFinding finding = dm.getFindings().get(0);
        assertEquals(ValidationReportBuilder.SOURCE, finding.getSource());
        assertEquals("CORE-001", finding.getRuleId());
        assertEquals("USUBJID must not be null", finding.getMessage());
        assertEquals(FindingKind.RULE_VIOLATION, finding.getKind());
        assertEquals(Severity.ERROR, finding.getSeverity());
        assertEquals(2, finding.getRowCount());
        int[] rows = finding.rowIndices().toArray();
        assertEquals(2, rows[0]);
        assertEquals(5, rows[1]);
    }


    @Test
    void rowFindingVariablesAreCarriedThrough()
    {
        RuleExecutionResult result = withViolations("CORE-001", "fail",
                List.of(v(0, "USUBJID", "SUBJ-001", "AETERM", "Headache")));
        ValidationReport report = new ValidationReportBuilder()
                .add("AE", "ae.xpt", rule("CORE-001"), result).build();

        ValidationFinding f = report.getMembers().get(0).getFindings().get(0);
        assertEquals(List.of("USUBJID", "AETERM"), f.getVariableNames());
        Map<String, String> rowMap = f.getFirstRowValues();
        assertEquals("SUBJ-001", rowMap.get("USUBJID"));
        assertEquals("Headache", rowMap.get("AETERM"));
        // The location uses the library member name (the domain passed to add()), and its columns
        // are the real data columns.
        assertEquals("AE", f.getLocation().getDataset());
        assertEquals(List.of("USUBJID", "AETERM"), f.getLocation().getVariableNames());
    }


    @Test
    void locationDatasetIsTheMemberNameNotCdiscDomain()
    {
        // Split-domain member name (e.g. LBHE) is what the engine reports — the location.dataset
        // must equal that member name, never the CDISC domain code (LB).
        RuleExecutionResult result = withViolations("CORE-001", "fail",
                List.of(v(0, "LBORRES", "5")));
        ValidationReport report = new ValidationReportBuilder()
                .add("LBHE", "lbhe.xpt", rule("CORE-001"), result).build();

        ValidationFinding f = report.getMembers().get(0).getFindings().get(0);
        assertEquals("LBHE", f.getLocation().getDataset());
    }


    @Test
    void engineErrorFindingCarriesLocationDataset()
    {
        ValidationReport report = new ValidationReportBuilder()
                .add("DM", "dm.xpt", rule("CORE-009"), errorResult("CORE-009", "boom")).build();
        ValidationFinding f = report.getMembers().get(0).getFindings().get(0);
        assertEquals("DM", f.getLocation().getDataset());
        assertEquals(List.of(), f.getLocation().getVariableNames());
    }


    @Test
    void datasetLoadErrorFindingCarriesLocationDataset()
    {
        ValidationReport report = new ValidationReportBuilder()
                .datasetLoadError("AE", "ae.xpt", "could not open").build();
        ValidationFinding f = report.getMembers().get(0).getFindings().get(0);
        assertEquals("AE", f.getLocation().getDataset());
        assertEquals(List.of(), f.getLocation().getVariableNames());
    }


    @Test
    void multipleRulesAccumulateOnSameMember()
    {
        ValidationReport report = new ValidationReportBuilder()
                .add("DM", "dm.xpt", rule("CORE-001"),
                        withViolations("CORE-001", "msg1", List.of(v(0))))
                .add("DM", "dm.xpt", rule("CORE-002"),
                        withViolations("CORE-002", "msg2", List.of(v(1))))
                .build();

        assertEquals(1, report.getMembers().size());
        ValidationReportMember dm = report.getMembers().get(0);
        assertEquals(2, dm.getFindings().size());
        assertEquals("CORE-001", dm.getFindings().get(0).getRuleId());
        assertEquals("CORE-002", dm.getFindings().get(1).getRuleId());
    }


    @Test
    void separateMembersAreKeptDistinct()
    {
        ValidationReport report = new ValidationReportBuilder()
                .add("DM", "dm.xpt", rule("CORE-001"),
                        withViolations("CORE-001", "dm fail", List.of(v(0))))
                .add("AE", "ae.xpt", rule("CORE-001"),
                        withViolations("CORE-001", "ae fail", List.of(v(0))))
                .build();

        assertEquals(2, report.getMembers().size());
        assertNotNull(report.getForMember("DM"));
        assertNotNull(report.getForMember("AE"));
    }


    @Test
    void domainLookupIsCaseInsensitive()
    {
        ValidationReport report = new ValidationReportBuilder().add("DM", "dm.xpt",
                rule("CORE-001"), withViolations("CORE-001", "fail", List.of(v(0)))).build();
        assertNotNull(report.getForMember("dm"));
        assertNotNull(report.getForMember("Dm"));
        assertNotNull(report.getForMember("DM"));
    }

    // ------------------------------------------------------------------
    // Engine errors
    // ------------------------------------------------------------------


    @Test
    void errorResultBecomesEngineErrorFinding()
    {
        ValidationReport report = new ValidationReportBuilder().add("AE", "ae.xpt",
                rule("CORE-042"), errorResult("CORE-042", "NullPointerException in operator foo"))
                .build();

        ValidationReportMember ae = report.getMembers().get(0);
        assertEquals(1, ae.getFindings().size());
        ValidationFinding f = ae.getFindings().get(0);
        assertEquals(Severity.ERROR, f.getSeverity());
        assertEquals(FindingKind.ENGINE_ERROR, f.getKind());
        assertEquals("CORE-042", f.getRuleId());
        assertEquals("NullPointerException in operator foo", f.getMessage());
        // Engine errors have no row findings (dataset-level).
        assertEquals(0, f.getRowCount());
    }


    @Test
    void errorResultWithoutMessageGetsDefault()
    {
        ValidationReport report = new ValidationReportBuilder()
                .add("AE", "ae.xpt", rule("CORE-042"), errorResult("CORE-042", null)).build();
        ValidationFinding f = report.getMembers().get(0).getFindings().get(0);
        assertEquals("Rule engine error", f.getMessage());
    }

    // ------------------------------------------------------------------
    // Library-level warnings
    // ------------------------------------------------------------------


    @Test
    void libraryWarningGoesToSyntheticMember()
    {
        ValidationReport report = new ValidationReportBuilder()
                .libraryUri("file:///study/define.xml")
                .libraryWarning("Study standard does not match caller choice").build();

        assertEquals(1, report.getMembers().size());
        ValidationReportMember lib = report.getMembers().get(0);
        assertEquals(ValidationReportBuilder.LIBRARY_LEVEL_DOMAIN, lib.getDomain());
        assertEquals("file:///study/define.xml", lib.getFileName());

        ValidationFinding f = lib.getFindings().get(0);
        assertEquals(Severity.WARNING, f.getSeverity());
        assertEquals(FindingKind.LIBRARY_WARNING, f.getKind());
        assertEquals("Study standard does not match caller choice", f.getMessage());
    }


    @Test
    void libraryWarningWithoutUriStillWorks()
    {
        ValidationReport report = new ValidationReportBuilder().libraryWarning("oops").build();
        assertEquals(1, report.getMembers().size());
        assertNull(report.getMembers().get(0).getFileName());
    }


    @Test
    void libraryWarningsCanCoexistWithDatasetFindings()
    {
        ValidationReport report = new ValidationReportBuilder()
                .libraryUri("file:///study/define.xml")
                .add("DM", "dm.xpt", rule("CORE-001"),
                        withViolations("CORE-001", "fail", List.of(v(0))))
                .libraryWarning("standard mismatch").build();

        assertEquals(2, report.getMembers().size());
    }

    // ------------------------------------------------------------------
    // Robustness
    // ------------------------------------------------------------------


    @Test
    void nullArgumentsRejected()
    {
        ValidationReportBuilder builder = new ValidationReportBuilder();
        Rule r = rule("CORE-001");
        RuleExecutionResult ok = cleanResult("CORE-001");
        assertThrows(NullPointerException.class, () -> builder.add(null, "f", r, ok));
        assertThrows(NullPointerException.class, () -> builder.add("DM", "f", null, ok));
        assertThrows(NullPointerException.class, () -> builder.add("DM", "f", r, null));
        assertThrows(NullPointerException.class, () -> builder.libraryWarning(null));
    }


    @Test
    void buildCanBeCalledMultipleTimes()
    {
        ValidationReportBuilder builder = new ValidationReportBuilder().add("DM", "dm.xpt",
                rule("CORE-001"), withViolations("CORE-001", "fail", List.of(v(0))));
        ValidationReport first = builder.build();
        ValidationReport second = builder.build();
        assertEquals(first.getMembers().size(), second.getMembers().size());
    }


    @Test
    void ruleWithoutCoreIdUsesRuleId()
    {
        Rule r = new Rule();
        r.setId("uuid-xyz");
        // no Core set
        ValidationReport report = new ValidationReportBuilder()
                .add("DM", "dm.xpt", r, withViolations("uuid-xyz", "fail", List.of(v(0)))).build();
        assertEquals("uuid-xyz", report.getMembers().get(0).getFindings().get(0).getRuleId());
    }


    @Test
    void rowFindingsAreSortedByIndex()
    {
        RuleExecutionResult result = withViolations("CORE-001", "fail",
                List.of(v(5), v(2), v(8), v(0)));
        ValidationReport report = new ValidationReportBuilder()
                .add("DM", "dm.xpt", rule("CORE-001"), result).build();
        int[] rows = report.getMembers().get(0).getFindings().get(0).rowIndices().toArray();
        assertEquals(0, rows[0]);
        assertEquals(2, rows[1]);
        assertEquals(5, rows[2]);
        assertEquals(8, rows[3]);
    }


    /**
     * §3.3 of {@code PLAN-leaf-scope-domain-inference.md}: the scope is a pure projection of the
     * rule's evaluation domain — a row-bearing domain is RECORD, {@code {VAR}} is VARIABLE,
     * {@code {}} is DATASET, and a rule with no domain (never compiled natively) stamps none, so
     * the finding's own shape decides.
     */
    @Test
    void scopeIsAProjectionOfTheEvaluationDomain()
    {
        assertEquals(FindingScope.RECORD, scopeFor(net.cumba.cdisc.core.expr.eval.Domain.ROW));
        assertEquals(FindingScope.RECORD, scopeFor(net.cumba.cdisc.core.expr.eval.Domain.CELL));
        assertEquals(FindingScope.VARIABLE,
                scopeFor(net.cumba.cdisc.core.expr.eval.Domain.VARIABLE));
        assertEquals(FindingScope.DATASET, scopeFor(net.cumba.cdisc.core.expr.eval.Domain.DATASET));
        ValidationReport untyped = new ValidationReportBuilder().add("DM", "dm.xpt",
                rule("CORE-009"), withViolations("CORE-009", "fail", List.of(v(0)))).build();
        assertNull(untyped.getMembers().get(0).getFindings().get(0).getScope());
    }


    private static FindingScope scopeFor(net.cumba.cdisc.core.expr.eval.Domain domain)
    {
        ValidationReport report = new ValidationReportBuilder()
                .add("DM", "dm.xpt", ruleWithDomain("CORE-001", domain),
                        withViolations("CORE-001", "fail", List.of(v(0))))
                .build();
        return report.getMembers().get(0).getFindings().get(0).getScope();
    }


    @Test
    void engineErrorScopeIsDataset()
    {
        ValidationReport report = new ValidationReportBuilder()
                .add("AE", "ae.xpt", rule("CORE-042"), errorResult("CORE-042", "boom")).build();
        ValidationFinding f = report.getMembers().get(0).getFindings().get(0);
        assertEquals(FindingScope.DATASET, f.getScope());
    }


    @Test
    void libraryWarningScopeIsDataset()
    {
        ValidationReport report = new ValidationReportBuilder().libraryUri("file:///lib")
                .libraryWarning("boom").build();
        ValidationFinding f = report.getMembers().get(0).getFindings().get(0);
        assertEquals(FindingScope.DATASET, f.getScope());
    }


    @Test
    void fileNameBackfilledWhenFirstAddMissingIt()
    {
        ValidationReport report = new ValidationReportBuilder()
                .add("DM", null, rule("CORE-001"),
                        withViolations("CORE-001", "fail", List.of(v(0))))
                .add("DM", "dm.xpt", rule("CORE-002"),
                        withViolations("CORE-002", "fail", List.of(v(1))))
                .build();
        assertEquals(2, report.getMembers().get(0).getFindings().size());
        assertEquals("dm.xpt", report.getMembers().get(0).getFileName());
    }


    // Regression for ADAM-000470: a Variable Metadata Check rule reports one violation per
    // column, each with its own (variable_name, variable_label) pair. Before the fix, the engine
    // produced a single ValidationFinding whose schema was a sparse union of every variable name
    // (AGEGR1, TRTPN, …) and each row had exactly one non-null value. After the fix each column
    // becomes its own finding with a dense single-column schema.
    @Test
    void variableMetadataViolationsSplitPerColumn()
    {
        RuleExecutionResult result = withViolations("CORE-000594",
                "Variable label is not in title case.",
                List.of(v(0, "variable_name", "AGEGR1", "variable_label", "age group 1"),
                        v(0, "variable_name", "TRTPN", "variable_label", "trt n"),
                        v(0, "variable_name", "RACEN", "variable_label", "race n")));

        ValidationReport report = new ValidationReportBuilder()
                .add("DM", "dm.xpt", rule("CORE-000594"), result).build();

        List<ValidationFinding> findings = report.getMembers().get(0).getFindings();
        assertEquals(3, findings.size(), "one finding per column — dense schemas, no sparse union");

        for (ValidationFinding f : findings)
        {
            assertEquals(1, f.getVariableNames().size(),
                    "each finding's schema is a single column");
            assertEquals(1, f.getRows().rowCount());
            String colName = f.getVariableNames().get(0);
            String label = f.getRows().valueAt(0, 0);
            assertNotNull(label, "the one slab value must be the column's label");
            assertTrue(List.of("AGEGR1", "TRTPN", "RACEN").contains(colName));
        }
    }


    @Test
    void recordLevelViolationsOnSameColumnMergeIntoOneFinding()
    {
        // For record-level rules where every violation targets the same column (typical case —
        // e.g. "USUBJID must not be null"), the grouping collapses into one finding with many
        // rows, not many findings.
        RuleExecutionResult result = withViolations("CORE-001", "USUBJID must not be null",
                List.of(v(2, "variable_name", "USUBJID", "variable_value", ""),
                        v(5, "variable_name", "USUBJID", "variable_value", ""),
                        v(9, "variable_name", "USUBJID", "variable_value", "")));

        ValidationReport report = new ValidationReportBuilder()
                .add("DM", "dm.xpt", rule("CORE-001"), result).build();

        List<ValidationFinding> findings = report.getMembers().get(0).getFindings();
        assertEquals(1, findings.size(), "same column across violations → one finding, many rows");
        assertEquals(List.of("USUBJID"), findings.get(0).getVariableNames());
        assertEquals(3, findings.get(0).getRows().rowCount());
    }


    // Regression for CDISC-AD0086: the output variables are
    // [variable_name, variable_format, $adsl_format]
    // Before the fix, variable_format was not a registered partner → no collapse → the schema was
    // the literal engine keys and no actual column name reached variableNames, so the UI could
    // not flag the target column.
    @Test
    void variableFormatRuleFlagsTargetColumn()
    {
        RuleExecutionResult result = withViolations("CDISC-AD0086",
                "A variable is present with the same name as a variable present in ADSL but the "
                        + "variables do not have identical formats",
                List.of(v(0, "variable_name", "TRTP", "variable_format", "DATE9.", "$adsl_format",
                        "DATE11."),
                        v(1, "variable_name", "AGE", "variable_format", "BEST8.", "$adsl_format",
                                "BEST12.")));

        ValidationReport report = new ValidationReportBuilder()
                .add("ADVS", "advs.xpt", rule("CDISC-AD0086"), result).build();

        List<ValidationFinding> findings = report.getMembers().get(0).getFindings();
        // One finding per target column (schemas differ because variableNames[0] is the column).
        assertEquals(2, findings.size());

        for (ValidationFinding f : findings)
        {
            List<String> schema = f.getVariableNames();
            assertEquals(2, schema.size(),
                    "schema = [<colname>, $adsl_format] — partner collapse hides variable_format");
            String colName = schema.get(0);
            assertTrue(List.of("TRTP", "AGE").contains(colName),
                    "first schema entry must be the actual target column name, got: " + colName);
            assertEquals("$adsl_format", schema.get(1));
            assertEquals(1, f.getRows().rowCount());
            // The column's slot carries the format value from the partner collapse.
            String fmt = f.getRows().valueAt(0, 0);
            assertTrue("DATE9.".equals(fmt) || "BEST8.".equals(fmt));
            // The $adsl_format slot carries the expected value.
            String expected = f.getRows().valueAt(0, 1);
            assertTrue("DATE11.".equals(expected) || "BEST12.".equals(expected));
        }
    }


    @Test
    void executabilityIsRenderedAsPythonValueOnRuleViolation()
    {
        Rule r = rule("CORE-000019");
        r.setExecutability(Executability.PARTIALLY_EXECUTABLE_POSSIBLE_OVERREPORTING);
        ValidationReport report = new ValidationReportBuilder()
                .add("DM", "dm.xpt", r, withViolations("CORE-000019", "fail", List.of(v(0))))
                .build();
        ValidationFinding f = report.getMembers().get(0).getFindings().get(0);
        assertEquals("partially executable - possible overreporting", f.getExecutability());
    }


    @Test
    void executabilityIsRenderedOnEngineErrorToo()
    {
        // Python-parity: Issue_Details rows for engine errors carry the rule's declared
        // executability (e.g. "fully executable") just like rule-violation rows.
        Rule r = rule("CORE-000019");
        r.setExecutability(Executability.FULLY_EXECUTABLE);
        ValidationReport report = new ValidationReportBuilder()
                .add("AE", "ae.xpt", r, errorResult("CORE-000019", "boom")).build();
        ValidationFinding f = report.getMembers().get(0).getFindings().get(0);
        assertEquals(FindingKind.ENGINE_ERROR, f.getKind());
        assertEquals("fully executable", f.getExecutability());
    }


    @Test
    void executabilityIsNullWhenRuleDeclaresNone()
    {
        // Some synthetic generated rules (GEN-*) carry no Executability — emit null so the
        // JsonReportWriter renders the field as JSON null, matching the existing behaviour
        // of un-set rules.
        ValidationReport report = new ValidationReportBuilder().add("DM", "dm.xpt", rule("GEN-FOO"),
                withViolations("GEN-FOO", "fail", List.of(v(0)))).build();
        ValidationFinding f = report.getMembers().get(0).getFindings().get(0);
        assertNull(f.getExecutability());
    }


    @Test
    void variableNameOnlyRuleFlagsColumn()
    {
        // Defensive: when the output has only variable_name (no partner), the column name must
        // still appear in the schema so downstream consumers can locate the target column.
        RuleExecutionResult result = withViolations("SOMERULE", "Something",
                List.of(v(0, "variable_name", "USUBJID")));

        ValidationReport report = new ValidationReportBuilder()
                .add("DM", "dm.xpt", rule("SOMERULE"), result).build();

        ValidationFinding f = report.getMembers().get(0).getFindings().get(0);
        assertEquals(List.of("USUBJID"), f.getVariableNames());
    }


    @Test
    void datasetLoadErrorAttachesErrorFindingToDomain()
    {
        ValidationReport report = new ValidationReportBuilder()
                .datasetLoadError("LB", "lb.xpt", "could not open").build();

        assertEquals(1, report.getMembers().size());
        ValidationReportMember member = report.getMembers().get(0);
        assertEquals("LB", member.getDomain());
        assertEquals(1, member.getFindings().size());

        ValidationFinding finding = member.getFindings().get(0);
        assertEquals(ValidationReportBuilder.DATASET_LOAD_ERROR_RULE_ID, finding.getRuleId());
        assertEquals(FindingKind.ENGINE_ERROR, finding.getKind());
        assertEquals(Severity.ERROR, finding.getSeverity());
        assertEquals(FindingScope.DATASET, finding.getScope());
        assertTrue(finding.getMessage().contains("could not open"));
    }

    // ------------------------------------------------------------------
    // Fix #225 — the executed set: the half of the picture skippedRules cannot carry
    // ------------------------------------------------------------------


    @Test
    void cleanExecutionIsRecordedInTheExecutedSet()
    {
        // The case the report used to drop entirely: no violations means no finding, so without
        // this set "ran and found nothing" is indistinguishable from "never ran".
        ValidationReport report = new ValidationReportBuilder()
                .add("DM", "dm.xpt", rule("CORE-001"), cleanResult("CORE-001")).build();

        assertEquals(List.of("CORE-001"), report.getExecutedCoreIds());
        assertTrue(report.getMembers().isEmpty(), "a clean run still produces no member");
    }


    @Test
    void violationsAndErrorsAlsoCountAsExecutions()
    {
        ValidationReport report = new ValidationReportBuilder()
                .add("DM", "dm.xpt", rule("CORE-001"),
                        withViolations("CORE-001", "fail", List.of(v(0))))
                .add("AE", "ae.xpt", rule("CORE-002"), errorResult("CORE-002", "boom")).build();

        assertEquals(List.of("CORE-001", "CORE-002"), report.getExecutedCoreIds());
    }


    @Test
    void skippedExecutionIsNotRecordedAsExecuted()
    {
        ValidationReport report = new ValidationReportBuilder()
                .add("DM", "dm.xpt", rule("CORE-001"), skippedResult("CORE-001")).build();

        assertEquals(List.of(), report.getExecutedCoreIds());
        assertEquals(1, report.getSkippedRules().size());
    }


    @Test
    void generationTimeSkipIsNotRecordedAsExecuted()
    {
        ValidationReport report = new ValidationReportBuilder()
                .skippedRule("EX", "ex.xpt", rule("CORE-001"), "domain EX not in scope").build();

        assertEquals(List.of(), report.getExecutedCoreIds());
        assertEquals(1, report.getSkippedRules().size());
    }


    @Test
    void aRuleSkippedOnOneDatasetAndRunOnAnotherIsBothSkippedAndExecuted()
    {
        // The partial case, at the builder level: BOTH facts are recorded, and it is the reader's
        // job (JsonReportWriter) to prefer "executed".
        ValidationReport report = new ValidationReportBuilder()
                .add("EX", "ex.xpt", rule("CORE-001"), skippedResult("CORE-001"))
                .add("DM", "dm.xpt", rule("CORE-001"), cleanResult("CORE-001")).build();

        assertEquals(List.of("CORE-001"), report.getExecutedCoreIds());
        assertEquals(1, report.getSkippedRules().size());
        assertEquals("EX", report.getSkippedRules().get(0).getDataset());
    }


    @Test
    void theExecutedSetDeduplicatesAcrossDatasets()
    {
        ValidationReport report = new ValidationReportBuilder()
                .add("DM", "dm.xpt", rule("CORE-001"), cleanResult("CORE-001"))
                .add("AE", "ae.xpt", rule("CORE-001"), cleanResult("CORE-001")).build();

        assertEquals(List.of("CORE-001"), report.getExecutedCoreIds());
    }


    @Test
    void anIdentifierlessRuleNeverEntersTheExecutedSet()
    {
        // effectiveId() is null for a synthetic rule without any identifier; a null must not be
        // added (List.copyOf would throw, and the set is looked up by id anyway).
        ValidationReport report = new ValidationReportBuilder()
                .add("DM", "dm.xpt", new Rule(), cleanResult("anon")).build();

        assertEquals(List.of(), report.getExecutedCoreIds());
    }

}
