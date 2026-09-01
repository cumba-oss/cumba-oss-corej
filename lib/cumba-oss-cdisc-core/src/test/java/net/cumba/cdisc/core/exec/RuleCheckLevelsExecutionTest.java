package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.model.Outcome;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.Sensitivity;
import net.cumba.cdisc.core.report.ValidationReportBuilder;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.report.ValidationFinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plan C phase 4 — per-level evaluation: first claim, ladder order, the run threshold, and the
 * per-level {@code Message} (§3.4 / §3.6).
 *
 * <p>
 * ⭐ Every rule here is <b>multi-level</b> on purpose. The single-level path is asserted by the
 * other 4 289 tests of this module and by the byte-identical findings snapshot; what needs pinning
 * is the behaviour that only exists once a rule declares a second rung.
 * </p>
 */
class RuleCheckLevelsExecutionTest
{

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** A four-row table whose single column separates the four ladder rungs. */
    private static IDataTable table()
    {
        return MockTable.of().name("AE").col("A", "a", "b", "c", "d").build();
    }


    private static Rule rule(String aBody) throws IOException
    {
        Rule rule = YAML.readValue("""
                Core:
                  Id: "T-LEVELS"
                Description: "Raise an error when something is wrong."
                """ + aBody, Rule.class);
        rule.setSensitivity(Sensitivity.RECORD);
        Outcome outcome = rule.getOutcome();
        if (outcome == null)
        {
            outcome = new Outcome();
            outcome.setMessage("the rule message");
            rule.setOutcome(outcome);
        }
        outcome.setOutputVariables(List.of("A"));
        // The §3.3 grammar gates themselves are pinned by RuleCheckLevelsLoadTest; here the
        // assertion only guards the fixtures from a silent typo.
        assertNull(rule.getRawCheckLevels(), "fixture must parse clean");
        RulePackageLoader.installNativeExpr(rule);
        assertNull(rule.getLoadError(), "fixture must load clean");
        return rule;
    }


    private static RuleExecutionResult run(Rule aRule, Severity aThreshold)
    {
        return run(aRule, table(), aThreshold);
    }


    private static RuleExecutionResult run(Rule aRule, IDataTable aTable, Severity aThreshold)
    {
        return RuleRunner.execute(aRule, aTable, _ -> null, "AE", null, null, null,
                Integer.MAX_VALUE, null, null, null, Set.of(), Set.of(), aThreshold);
    }


    /** The rule of the worked example: ERROR is entailed by INFO, so first-claim decides. */
    private static Rule entailedPair() throws IOException
    {
        return rule("""
                Check:
                  ERROR:
                    expression: >-
                      A == "a"
                  INFO:
                    expression: >-
                      A == "a" or A == "b"
                """);
    }

    // ------------------------------------------------------------------ first claim


    @Test
    @DisplayName("a two-level rule fires ONCE per row, at the stricter level")
    void firstClaimWins() throws IOException
    {
        RuleExecutionResult r = run(entailedPair(), Severity.INFO);

        assertEquals(2, r.getViolationCount(),
                "row 'a' satisfies BOTH levels and must be reported once, not twice");
        assertEquals(List.of(0L, 1L), r.getViolations().stream().map(Violation::getRow).toList());
        assertEquals(Severity.ERROR, r.getViolations().get(0).getLevel(),
                "the stricter level claims the row it fires on");
        assertEquals(Severity.INFO, r.getViolations().get(1).getLevel(),
                "the weaker level keeps the rows the stricter one did not claim");
    }


    @Test
    @DisplayName("levels are tried REJECT → ERROR → WARNING → INFO, whatever the file order")
    void ladderOrderDecidesTheClaimant() throws IOException
    {
        // Authored weakest-first on purpose; each level's predicate subsumes the stricter ones, so
        // the level that claims each row is decided by the ladder alone.
        Rule rule = rule("""
                Severity: "Reject"
                Check:
                  INFO:
                    expression: >-
                      A == "a" or A == "b" or A == "c" or A == "d"
                  WARNING:
                    expression: >-
                      A == "a" or A == "b" or A == "c"
                  ERROR:
                    expression: >-
                      A == "a" or A == "b"
                  REJECT:
                    expression: >-
                      A == "a"
                """);
        RuleExecutionResult r = run(rule, Severity.INFO);

        assertEquals(4, r.getViolationCount(), "four rows, four findings — never sixteen");
        assertEquals(List.of(Severity.REJECT, Severity.ERROR, Severity.WARNING, Severity.INFO),
                r.getViolations().stream().map(Violation::getLevel).toList(),
                "the ladder, not the authored order");
    }

    // ------------------------------------------------------------------ the run threshold


    @Test
    @DisplayName("the DEFAULT threshold (Warning) evaluates REJECT+ERROR+WARNING and excludes INFO")
    void defaultThresholdExcludesInfo() throws IOException
    {
        RuleExecutionResult r = run(entailedPair(),
                net.cumba.cdisc.core.exec.EngineLimits.DEFAULT_SEVERITY_THRESHOLD);

        assertEquals(1, r.getViolationCount(),
                "the INFO level was not evaluated at all, so row 'b' is not reported");
        assertEquals(Severity.ERROR, r.getViolations().getFirst().getLevel());
    }


    @Test
    @DisplayName("a threshold above a rule's ONLY level SKIPs it with a reason — not EXECUTED/0")
    void thresholdAboveTheOnlyLevelSkipsWithAReason() throws IOException
    {
        Rule infoOnly = rule("""
                Severity: "Info"
                Check:
                  expression: >-
                    A == "a"
                """);
        RuleExecutionResult r = run(infoOnly,
                net.cumba.cdisc.core.exec.EngineLimits.DEFAULT_SEVERITY_THRESHOLD);

        assertEquals(RuleExecutionStatus.SKIPPED, r.getStatus(),
                "a rule that reports PASS when it was never evaluated is a false assurance");
        assertNotNull(r.getStatusMessage());
        assertTrue(r.getStatusMessage().contains("below the run severity threshold"),
                r.getStatusMessage());
        assertTrue(r.getStatusMessage().contains("Warning"),
                () -> "the reason names the threshold: " + r.getStatusMessage());
        assertFalse(r.hasViolations());

        assertEquals(RuleExecutionStatus.EXECUTED, run(infoOnly, Severity.INFO).getStatus(),
                "and it runs normally once the run asks for its rung");
    }


    @Test
    @DisplayName("a threshold BELOW a rule's declared levels changes nothing")
    void thresholdBelowEveryLevelIsANoOp() throws IOException
    {
        Rule errorOnly = rule("""
                Check:
                  expression: >-
                    A == "a"
                """);
        assertEquals(1, run(errorOnly, Severity.INFO).getViolationCount());
        assertEquals(1,
                run(errorOnly, net.cumba.cdisc.core.exec.EngineLimits.DEFAULT_SEVERITY_THRESHOLD)
                        .getViolationCount());
    }

    // ------------------------------------------------------------------ the per-level Message


    @Test
    @DisplayName("a level's own Message is reported; a level without one falls back to Outcome")
    void perLevelMessageIsResolvedFromTheClaimingLevel() throws IOException
    {
        Rule rule = rule("""
                Outcome:
                  Message: "the rule message"
                Check:
                  ERROR:
                    expression: >-
                      A == "a"
                    Message: "definitely wrong"
                  INFO:
                    expression: >-
                      A == "a" or A == "b"
                """);
        RuleExecutionResult result = run(rule, Severity.INFO);

        List<ValidationFinding> findings = new ValidationReportBuilder()
                .add("AE", "ae.xpt", rule, result).build().getMembers().getFirst().getFindings();

        assertEquals(2, findings.size(),
                "⛔ the LEVEL is part of the grouping key: two levels, two findings, even though "
                        + "both groups share the same collapsed schema");
        Map<Severity, String> byLevel = findings.stream().collect(java.util.stream.Collectors
                .toMap(ValidationFinding::getSeverity, ValidationFinding::getMessage));
        assertEquals("definitely wrong", byLevel.get(Severity.ERROR),
                "the claiming level's own Message");
        assertEquals("the rule message", byLevel.get(Severity.INFO),
                "a level that declares none falls back to Outcome.Message, resolved at report time");
    }


    @Test
    @DisplayName("the level rides on the finding's severity, not on the rule's")
    void findingSeverityIsTheClaimingLevel() throws IOException
    {
        Rule rule = rule("""
                Severity: "Reject"
                Check:
                  REJECT:
                    expression: >-
                      A == "a"
                  WARNING:
                    expression: >-
                      A == "a" or A == "b"
                """);
        RuleExecutionResult result = run(rule, Severity.INFO);
        List<ValidationFinding> findings = new ValidationReportBuilder()
                .add("AE", "ae.xpt", rule, result).build().getMembers().getFirst().getFindings();

        assertEquals(Set.of(Severity.REJECT, Severity.WARNING),
                findings.stream().map(ValidationFinding::getSeverity)
                        .collect(java.util.stream.Collectors.toSet()),
                "the rule's own Severity (Reject) does not label the WARNING row");
    }

    // ------------------------------------------------------------------ cohorting (D9)


    @Test
    @DisplayName("a multi-level rule is never cohorted")
    void multiLevelRuleIsNotCohorted() throws IOException
    {
        Rule single = rule("""
                Check:
                  expression: >-
                    A == "a"
                """);
        Rule multi = entailedPair();

        assertNull(RuleCohortGrouper.cohortKey(multi),
                "a cohort shares ONE evaluation across its members; a multi-level rule needs one "
                        + "per level plus the first-claim merge, which neither cohort path does");
        assertEquals(
                2, RuleCohortGrouper
                        .group(List.of(single, multi), table().getMetaData(), _ -> false).size(),
                "so it is demoted to a singleton and runs through RuleRunner");
    }

    // ------------------------------------------------------------------ finding units (F1–F3)


    @Test
    @DisplayName("a grouped rule reports a failing group ONCE, even when the rungs flag different rows")
    void groupedRuleReportsTheGroupOnceAcrossLevels() throws IOException
    {
        Rule rule = rule("""
                Grouping_Variables: ["G"]
                Check:
                  ERROR:
                    expression: >-
                      A == "a"
                  INFO:
                    expression: >-
                      A == "a" or A == "x"
                """);
        rule.setSensitivity(Sensitivity.GROUP);
        // Group g1 = rows 0+1: the INFO rung's first flagged row is 0, the ERROR rung's is 1 —
        // two DIFFERENT anchor rows for the SAME group. Group g2 (row 2) fires nothing.
        IDataTable grouped = MockTable.of().name("AE").col("G", "g1", "g1", "g2")
                .col("A", "x", "a", "n").build();
        RuleExecutionResult r = run(rule, grouped, Severity.INFO);

        assertEquals(1, r.getViolationCount(),
                "⛔ one failing group is ONE finding — keyed on the group's identity, never on the "
                        + "per-level anchor row, which differs between the rungs here");
        assertEquals(Severity.ERROR, r.getViolations().getFirst().getLevel(),
                "and the stricter level claims it");
        assertEquals(1L, r.getViolations().getFirst().getRow(),
                "anchored at the claiming (ERROR) rung's first flagged row");
    }


    @Test
    @DisplayName("a broadcast rung and a row rung do not collide on a USUBJID-less domain")
    void broadcastAndRowRungsDoNotCollide() throws IOException
    {
        Rule rule = rule("""
                Check:
                  ERROR:
                    expression: >-
                      var_not_exists(B)
                  INFO:
                    expression: >-
                      A == "a"
                """);
        // No USUBJID, no <DOMAIN>SEQ: the row rung's violation has null identity, and both rungs
        // project the same Output_Variables at row 0 — the materialised tuples are identical.
        IDataTable bare = MockTable.of().name("AE").col("A", "a").build();
        RuleExecutionResult r = run(rule, bare, Severity.INFO);

        assertEquals(2, r.getViolationCount(),
                "⛔ the dataset verdict (B is absent) and the row finding (row 0) are DIFFERENT "
                        + "units — on a USUBJID-less domain their materialised tuples collide and "
                        + "the genuine row-0 finding used to be silently lost");
        assertEquals(List.of(Severity.ERROR, Severity.INFO),
                r.getViolations().stream().map(Violation::getLevel).toList());
    }


    @Test
    @DisplayName("two columns firing on one row are two units, even with variable_name unprojected")
    void twoColumnsOnOneRowAreTwoUnits() throws IOException
    {
        Rule rule = rule("""
                Check:
                  ERROR:
                    expression: >-
                      value() == "x"
                  INFO:
                    expression: >-
                      value() == "x" or value() == "a"
                """);
        // Output_Variables is ["A"] (the rule() fixture), so variable_name is NOT projected and
        // the violations of columns B and C materialise identical values maps.
        IDataTable threeCols = MockTable.of().name("AE").col("A", "a").col("B", "x").col("C", "x")
                .build();
        RuleExecutionResult r = run(rule, threeCols, Severity.INFO);

        assertEquals(3, r.getViolationCount(),
                "⛔ ERROR on column B, ERROR on column C and INFO on column A are THREE findings — "
                        + "the column identity is part of the unit even when the author excludes "
                        + "variable_name from the projection");
        assertEquals(List.of(Severity.ERROR, Severity.ERROR, Severity.INFO),
                r.getViolations().stream().map(Violation::getLevel).toList());
    }

    // ------------------------------------------------------------------ within-level claims (5b)


    /**
     * A one-primary-row key join that expands to <b>two</b> evaluation rows, both firing. Both
     * expanded rows map back to real row 0, so both violations carry the same
     * {@code Violation.Unit.Row(0)} stamp — the shape that first-claim must NOT deduplicate.
     */
    private static IDataTable joinPrimary()
    {
        return MockTable.of().name("DM").col("USUBJID", "P1", "P2").build();
    }


    private static DatasetResolver joinChild()
    {
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "P1", "P1", "P2")
                .col("AESDTH", "Y", "Y", "N").build();
        return name -> "AE".equals(name) ? ae : null;
    }


    private static RuleExecutionResult runJoined(Rule aRule, Severity aThreshold)
    {
        return RuleRunner.execute(aRule, joinPrimary(), joinChild(), "DM", null, null, null,
                Integer.MAX_VALUE, null, null, null, Set.of(), Set.of(), aThreshold);
    }


    /** The joined fixture as a rule body: {@code Match_Datasets} plus the firing predicate. */
    private static Rule joinedRule(String aCheckBody) throws IOException
    {
        Rule rule = rule("""
                Match_Datasets:
                  - Name: "AE"
                    Keys: ["USUBJID"]
                """ + aCheckBody);
        Outcome outcome = rule.getOutcome();
        assertNotNull(outcome, "fixture must carry an Outcome");
        outcome.setOutputVariables(List.of("USUBJID"));
        return rule;
    }


    @Test
    @DisplayName("⛔ ONE level reporting two units with the SAME key reports BOTH, never one")
    void oneLevelNeverDeduplicatesAgainstItself() throws IOException
    {
        // A one-entry level map takes the per-level loop (there is no `levels.size() > 1` install
        // boundary), so this is the plain single-level semantics running through executeLevels.
        RuleExecutionResult levelled = runJoined(joinedRule("""
                Check:
                  ERROR:
                    expression: >-
                      AE.AESDTH == "Y"
                """), Severity.INFO);

        assertEquals(2, levelled.getViolationCount(),
                "⛔ two expanded join rows of ONE primary row are TWO findings — first claim is a "
                        + "cross-level rule and must never deduplicate a level against itself");
        assertEquals(List.of(0L, 0L),
                levelled.getViolations().stream().map(Violation::getRow).toList(),
                "both findings sit on the same primary row — that is the colliding unit key");
        assertEquals(List.of(Severity.ERROR, Severity.ERROR),
                levelled.getViolations().stream().map(Violation::getLevel).toList());

        // ...and the pre-Plan-C path, which has no claimed set at all, agrees exactly. This is the
        // regression's oracle: a level map must not change what a rule reports.
        RuleExecutionResult plain = runJoined(joinedRule("""
                Check:
                  expression: >-
                    AE.AESDTH == "Y"
                """), Severity.INFO);
        assertEquals(plain.getViolationCount(), levelled.getViolationCount(),
                "the single-level path and a one-entry level map must report the same count");
        assertEquals(plain.getViolations().stream().map(Violation::getRow).toList(),
                levelled.getViolations().stream().map(Violation::getRow).toList());
    }


    @Test
    @DisplayName("a weaker rung still does not re-report a unit a stricter rung claimed")
    void aWeakerRungStillDoesNotReReportAStricterRungsUnit() throws IOException
    {
        // Row 'a' satisfies both rungs; row 'b' only the weaker one. Cross-level suppression is
        // exactly what first-claim IS, and widening the within-level behaviour must not lose it.
        RuleExecutionResult r = run(entailedPair(), Severity.INFO);

        assertEquals(2, r.getViolationCount(),
                "row 'a' is claimed once (by ERROR) and row 'b' once (by INFO) — never three");
        assertEquals(List.of(0L, 1L), r.getViolations().stream().map(Violation::getRow).toList());
        assertEquals(List.of(Severity.ERROR, Severity.INFO),
                r.getViolations().stream().map(Violation::getLevel).toList(),
                "the stricter rung keeps row 'a'; the weaker rung must not report it a second time");
    }


    @Test
    @DisplayName("N expanded join rows are N findings at the claiming rung, and the weaker rung adds none")
    void expandedJoinRowsAreNFindingsAndTheWeakerRungAddsNone() throws IOException
    {
        // ERROR fires on both expanded rows of primary row 0; INFO's predicate subsumes it, so it
        // re-derives the SAME unit. The two halves must both hold: two findings within ERROR, and
        // nothing extra from INFO.
        RuleExecutionResult r = runJoined(joinedRule("""
                Check:
                  ERROR:
                    expression: >-
                      AE.AESDTH == "Y"
                  INFO:
                    expression: >-
                      AE.AESDTH == "Y" or AE.AESDTH == "N"
                """), Severity.INFO);

        List<Severity> levels = r.getViolations().stream().map(Violation::getLevel).toList();
        assertEquals(2, levels.stream().filter(l -> l == Severity.ERROR).count(),
                "\u26D4 both expanded rows of primary row 0 are genuine ERROR findings: " + levels);
        assertEquals(0, infoOnRow(r, 0L),
                "the INFO rung must not re-report primary row 0 \u2014 ERROR claimed it: "
                        + levels);
        assertEquals(3, r.getViolationCount(),
                "two ERROR findings on primary row 0 plus the one INFO finding on primary row 1 "
                        + "(P2, AESDTH=N): " + levels);
    }


    /** INFO-stamped findings sitting on {@code aRow}. */
    private static long infoOnRow(RuleExecutionResult aResult, long aRow)
    {
        return aResult.getViolations().stream()
                .filter(v -> v.getLevel() == Severity.INFO && v.getRow() == aRow).count();
    }

    // ------------------------------------------------------------------ one-entry level maps (M2)


    @Test
    @DisplayName("a ONE-entry level map still runs the per-level machinery — its Message included")
    void oneEntryLevelMapKeepsItsMessage() throws IOException
    {
        Rule rule = rule("""
                Severity: "Warning"
                Outcome:
                  Message: "the rule message"
                Check:
                  WARNING:
                    expression: >-
                      A == "a"
                    Message: "the level message"
                """);
        RuleExecutionResult result = run(rule,
                net.cumba.cdisc.core.exec.EngineLimits.DEFAULT_SEVERITY_THRESHOLD);

        assertEquals(1, result.getViolationCount());
        assertEquals(Severity.WARNING, result.getViolations().getFirst().getLevel(),
                "the claiming level is stamped even when the map declares only one level");

        List<ValidationFinding> findings = new ValidationReportBuilder()
                .add("AE", "ae.xpt", rule, result).build().getMembers().getFirst().getFindings();
        assertEquals(1, findings.size());
        assertEquals("the level message", findings.getFirst().getMessage(),
                "⛔ a one-entry map's Message must not silently fall back to Outcome.Message — "
                        + "that was the `levels.size() > 1` install boundary");
        assertEquals(Severity.WARNING, findings.getFirst().getSeverity());
    }

}
