package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-present-empty-column-fold} §4.3 on the runner — the present-but-empty COLLAPSE (owner
 * D5 / D6 / D8 / D9, C5): a record-level rule declaring {@code Outcome.Message_Folded} reports its
 * per-row findings as ONE dataset-level finding when an {@code empty}-family leaf reads a present
 * column that is blank on every row; a rule without the key is untouched (the D111 per-row
 * behaviour it pins stays, {@link AbsentColumnDatasetFoldTest}).
 *
 * <p>
 * The owner's example: AE with {@code AEBDSYCD} empty on every record — one finding, not one per
 * record. Pinned per outcome of D5 (residual FALSE / TRUE / row-dependent), per truth-table cell
 * (incl. the {@code is_missing} controls, {@code D50}), the 2-2 gate shape (D8), the Type 1 opt-out
 * (D9, incl. a {@code --} template), 0 rows (C5), the D2 control ({@code !=} never folds), the
 * absent column (BroadcastFold's, under {@code Message_Folded} — the wider scope), P3's absent
 * column inside the collapse ({@code PLAN-message-folded-corpus-alignment}), a dotted and a
 * SUPP-delivered argument (R5), and a level-keyed rule (per level, first claim).
 * </p>
 */
class EmptyColumnCollapseRunnerTest
{

    private static final String FOLDED = "AEBDSYCD is not populated in this dataset";

    private static CheckCondition expression(String text)
    {
        return new CheckConditionExpression(CheckExpressionParser.parse(text), text);
    }


    private static Rule rule(String id, String check, @Nullable String messageFolded,
            List<String> keepRowFindings)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId(id);
        rule.setCore(core);
        rule.setCheck(expression(check));
        rule.setSensitivity(Sensitivity.RECORD);
        Outcome outcome = new Outcome();
        outcome.setMessage("per-row message");
        outcome.setMessageFolded(messageFolded);
        outcome.setKeepRowFindings(keepRowFindings.isEmpty() ? null : keepRowFindings);
        outcome.setOutputVariables(List.of("AEBDSYCD"));
        rule.setOutcome(outcome);
        RulePackageLoader.installNativeExpr(rule);
        assertNull(rule.getLoadError(), rule.getLoadError());
        return rule;
    }


    private static Rule optedIn(String check)
    {
        return rule("C7-IN", check, FOLDED, List.of());
    }


    private static Rule notOptedIn(String check)
    {
        return rule("C7-OUT", check, null, List.of());
    }


    /** AE, three records, {@code AEBDSYCD} present and {@code ""} on every one. */
    private static IDataTable aeAllBlank()
    {
        return MockTable.of().name("AE").col("USUBJID", "01", "02", "03")
                .col("AEBODSYS", "Cardiac", "", "Renal").col("AEBDSYCD", "", "", "").build();
    }


    private static RuleExecutionResult run(Rule rule, IDataTable table)
    {
        return RuleRunnerCalls.execute(rule, table, _ -> null, "AE", null, null, null,
                Integer.MAX_VALUE, null, null, null, Set.of(), Set.of());
    }


    private static void assertCollapsed(RuleExecutionResult result, long anchorRow)
    {
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(1, result.getViolationCount(), "one dataset finding: " + result);
        Violation v = result.getViolations().getFirst();
        // A plain rule stamps no unit (stamp() answers null without a level plan — the tuple is
        // the unit); a level-keyed rule stamps the folded dataset unit
        // (levelKeyedRuleCollapsesPerLevel).
        assertTrue(v.getUnit() == null || v.getUnit() == Violation.Unit.DATASET_FOLDED,
                "never a row unit: " + v.getUnit());
        assertEquals(anchorRow, v.getRow(), "anchored at the first firing row");
        assertEquals(FOLDED, result.getMessage(), "reported under Message_Folded (D7)");
        assertTrue(v.getNamedColumns().contains("AEBDSYCD"),
                "5c: the raw column is listed by name, no row value: " + v);
        assertNull(v.getValues().get("AEBDSYCD"), "no row-0 cell printed (D4 = like 5c)");
    }


    private static void assertPerRow(RuleExecutionResult result, int rows)
    {
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(rows, result.getViolationCount(), "one finding per firing record: " + result);
        for (Violation v : result.getViolations())
        {
            assertFalse(v.getUnit() instanceof Violation.Unit.Dataset,
                    "a row finding, never the dataset unit");
        }
        assertEquals("per-row message", result.getMessage());
    }

    // ---- S1: the empty leaf is the only row-level leaf --------------------------------------


    @Test
    @DisplayName("S1 — empty(X) over a present all-blank X: ONE dataset finding under Message_Folded")
    void s1CollapsesToOneFinding()
    {
        assertCollapsed(run(optedIn("empty(AEBDSYCD)"), aeAllBlank()), 0L);
    }


    @Test
    @DisplayName("the same rule WITHOUT Message_Folded keeps one finding per record (D6: opt-in only)")
    void aNonOptedRuleIsUnchanged()
    {
        assertPerRow(run(notOptedIn("empty(AEBDSYCD)"), aeAllBlank()), 3);
    }

    // ---- S2: the empty leaf beside a row-dependent leaf — D5's three outcomes --------------


    @Test
    @DisplayName("S2 row-dependent residual: not empty(AEBODSYS) and empty(AEBDSYCD) — one finding iff some row fires")
    void s2RowDependentResidualCollapsesWhenAnyRowFires()
    {
        // AEBODSYS is populated on rows 0 and 2: the residual holds there, so one finding,
        // anchored at row 0 (the first firing row).
        assertCollapsed(run(optedIn("not empty(AEBODSYS) and empty(AEBDSYCD)"), aeAllBlank()), 0L);
    }


    @Test
    @DisplayName("S2 residual constant FALSE: every AEBODSYS blank too — no finding")
    void s2ResidualFalseReportsNothing()
    {
        IDataTable t = MockTable.of().name("AE").col("USUBJID", "01", "02").col("AEBODSYS", "", "")
                .col("AEBDSYCD", "", "").build();
        RuleExecutionResult r = run(optedIn("not empty(AEBODSYS) and empty(AEBDSYCD)"), t);
        assertEquals(RuleExecutionStatus.EXECUTED, r.getStatus());
        assertEquals(0, r.getViolationCount(), "no row fires: zero findings, never one");
    }


    @Test
    @DisplayName("S2 residual constant TRUE: every AEBODSYS populated — the S1 case, one finding")
    void s2ResidualTrueIsOneFinding()
    {
        IDataTable t = MockTable.of().name("AE").col("USUBJID", "01", "02")
                .col("AEBODSYS", "Cardiac", "Renal").col("AEBDSYCD", "", "").build();
        assertCollapsed(run(optedIn("not empty(AEBODSYS) and empty(AEBDSYCD)"), t), 0L);
    }


    @Test
    @DisplayName("the anchor is the FIRST firing row, not row 0")
    void anchoredAtTheFirstFiringRow()
    {
        IDataTable t = MockTable.of().name("AE").col("USUBJID", "01", "02", "03")
                .col("AEBODSYS", "", "", "Renal").col("AEBDSYCD", "", "", "").build();
        assertCollapsed(run(optedIn("not empty(AEBODSYS) and empty(AEBDSYCD)"), t), 2L);
    }

    // ---- D8, D9, C5, D2 ----------------------------------------------------------------------


    @Test
    @DisplayName("D8 — a 2-2 gate (empty leaf alone, no sister) gives ONE finding when opted in")
    void twoTwoGateGivesOneFinding()
    {
        // Same shape as S1 from the engine's side: the gate is Requirements, not the Check.
        assertCollapsed(run(optedIn("empty(AEBDSYCD)"), aeAllBlank()), 0L);
    }


    @Test
    @DisplayName("D9 — Keep_Row_Findings on the column keeps the per-row findings")
    void keepRowFindingsOptsTheColumnOut()
    {
        Rule type1 = rule("C7-T1", "empty(AEBDSYCD)", FOLDED, List.of("AEBDSYCD"));
        assertPerRow(run(type1, aeAllBlank()), 3);
    }


    @Test
    @DisplayName("D9 — a --template in Keep_Row_Findings resolves like any variable reference")
    void keepRowFindingsResolvesTheDomainPrefix()
    {
        Rule type1 = rule("C7-T1W", "empty(--BDSYCD)", FOLDED, List.of("--BDSYCD"));
        assertPerRow(run(type1, aeAllBlank()), 3);
        Rule other = rule("C7-T1O", "empty(--BDSYCD)", FOLDED, List.of("--BODSYS"));
        assertCollapsed(run(other, aeAllBlank()), 0L);
    }


    @Test
    @DisplayName("Keep_Row_Findings compares names case-insensitively")
    void keepRowFindingsIgnoresCase()
    {
        Rule type1 = rule("C7-T1C", "empty(AEBDSYCD)", FOLDED, List.of("aebdsycd"));
        assertPerRow(run(type1, aeAllBlank()), 3);
    }


    @Test
    @DisplayName("C5 — a 0-row table never collapses: zero findings, never one")
    void zeroRowsNeverCollapse()
    {
        IDataTable empty = MockTable.of().name("AE").col("USUBJID").col("AEBDSYCD").build();
        assertEquals(0L, empty.getRowCount(), "the fixture must have no rows");
        RuleExecutionResult r = run(optedIn("empty(AEBDSYCD)"), empty);
        assertEquals(RuleExecutionStatus.EXECUTED, r.getStatus(), r.getStatusMessage());
        assertEquals(0, r.getViolationCount());
    }


    @Test
    @DisplayName("D2 control — a non-empty-family operator over the same blank column never folds")
    void notEqualOverABlankColumnStaysPerRow()
    {
        // (The loader refuses Message_Folded on such a rule, R2 (a); this pins the runtime side
        // for a rule that bypassed the gate.)
        Rule r = rule("C7-D2", "AEBDSYCD != \"ABC\"", FOLDED, List.of());
        RuleExecutionResult result = run(r, aeAllBlank());
        assertEquals(3, result.getViolationCount(), "\"\" != literal fires per row, no collapse");
        assertEquals("per-row message", result.getMessage());
    }


    @Test
    @DisplayName("a FILLED column is never a trigger: the same rule over populated data stays per row")
    void aFilledColumnStaysPerRow()
    {
        IDataTable t = MockTable.of().name("AE").col("USUBJID", "01", "02", "03")
                .col("AEBODSYS", "a", "b", "c").col("AEBDSYCD", "", "10001", "").build();
        assertPerRow(run(optedIn("empty(AEBDSYCD)"), t), 2);
    }

    // ---- the truth table -----------------------------------------------------------------


    @Test
    @DisplayName("non_empty / present / is_present over an all-blank column fold FALSE: no finding")
    void negatedFamilyFoldsFalse()
    {
        for (String leaf : List.of("non_empty(AEBDSYCD)", "present(AEBDSYCD)",
                "is_present(AEBDSYCD)", "not empty(AEBDSYCD)"))
        {
            RuleExecutionResult r = run(optedIn(leaf), aeAllBlank());
            assertEquals(0, r.getViolationCount(), leaf + ": folds FALSE on every row");
        }
    }


    @Test
    @DisplayName("is_missing over an all-\"\" column folds FALSE (D50: \"\" is not missing) — no finding")
    void isMissingOverAllEmptyStringFoldsFalse()
    {
        RuleExecutionResult r = run(optedIn("is_missing(AEBDSYCD)"), aeAllBlank());
        assertEquals(0, r.getViolationCount());
    }


    @Test
    @DisplayName("is_missing over an all-missing column folds TRUE — one finding")
    void isMissingOverAllMissingCollapses()
    {
        IDataTable t = MockTable.of().name("AE").col("USUBJID", "01", "02")
                .col("AEBODSYS", "a", "b").colSasMissing("AEBDSYCD", null, null).build();
        assertCollapsed(run(optedIn("is_missing(AEBDSYCD)"), t), 0L);
    }


    @Test
    @DisplayName("is_missing over \"\" mixed with missing is row-dependent: NOT a trigger, per row")
    void isMissingOverMixedBlanksStaysPerRow()
    {
        IDataTable t = MockTable.of().name("AE").col("USUBJID", "01", "02")
                .col("AEBODSYS", "a", "b").colSasMissing("AEBDSYCD", "", null).build();
        RuleExecutionResult r = run(optedIn("is_missing(AEBDSYCD)"), t);
        assertPerRow(r, 1);
        assertEquals(1L, r.getViolations().getFirst().getRow(), "only the missing row fires");
    }


    @Test
    @DisplayName("empty over \"\" mixed with missing (EMPTY + MULTI_VALUES) folds TRUE — one finding")
    void emptyOverMixedBlanksCollapses()
    {
        IDataTable t = MockTable.of().name("AE").col("USUBJID", "01", "02")
                .col("AEBODSYS", "a", "b").colSasMissing("AEBDSYCD", "", null).build();
        assertCollapsed(run(optedIn("empty(AEBDSYCD)"), t), 0L);
    }

    // ---- R3 / R5 / the wider scope -----------------------------------------------------------


    @Test
    @DisplayName("an ABSENT column stays BroadcastFold's — one finding, now under Message_Folded (wider scope)")
    void absentColumnFoldsUnderMessageFolded()
    {
        IDataTable noCode = MockTable.of().name("AE").col("USUBJID", "01", "02")
                .col("AEBODSYS", "a", "b").build();
        RuleExecutionResult r = run(optedIn("empty(AEBDSYCD)"), noCode);
        assertEquals(1, r.getViolationCount(), "the absent-column fold (D111), unchanged");
        assertEquals(FOLDED, r.getMessage(), "D7: a declared Message_Folded names the fold too");
        RuleExecutionResult unkeyed = run(notOptedIn("empty(AEBDSYCD)"), noCode);
        assertEquals("per-row message", unkeyed.getMessage(),
                "no key: per row, under Message (Q6)");
        assertEquals(2, unkeyed.getViolationCount(), "no key: one finding per record (Q6)");
    }


    /**
     * The collapse over an ABSENT trigger column: one finding, anchored at the first firing row.
     */
    private static void assertCollapsedOnAbsent(RuleExecutionResult result, long anchorRow)
    {
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(1, result.getViolationCount(), "one dataset finding: " + result);
        Violation v = result.getViolations().getFirst();
        assertTrue(v.getUnit() == null || v.getUnit() == Violation.Unit.DATASET_FOLDED,
                "never a row unit: " + v.getUnit());
        assertEquals(anchorRow, v.getRow(), "anchored at the first firing row");
        assertEquals(FOLDED, result.getMessage(), "reported under Message_Folded (D7)");
    }


    @Test
    @DisplayName("OWNER 2026-10-07 — an ABSENT column triggers iff its empty-family leaf is polarity-live")
    void absentColumnTriggersIffItsLeafIsPolarityLive()
    {
        // The owner's CG0114 walkthrough: `empty(--DOSU) and (not empty(--DOSE) or
        // not empty(--DOSTXT))`. CMDOSTXT absent makes `not empty(CMDOSTXT)` FALSE and decides
        // nothing — no trigger; CMDOSU present and filled on most records is no trigger either:
        // the per-record findings stay per record.
        String cg0114 = "empty(CMDOSU) and (not empty(CMDOSE) or not empty(CMDOSTXT))";
        IDataTable dostxtAbsent = MockTable.of().name("CM").col("USUBJID", "01", "02", "03", "04")
                .col("CMDOSE", "10", "20", "", "40").col("CMDOSU", "mg", "", "", "").build();
        assertPerRow(run(rule("C7-CG0114", cg0114, FOLDED, List.of()), dostxtAbsent), 2);
        // CMDOSU ABSENT: `empty(CMDOSU)` is TRUE on every record and DECIDES the rule — the
        // polarity-live leaf's column is absent, and the collapse reports once (the fold is
        // UNKNOWN: the residual is per row).
        IDataTable dosuAbsent = MockTable.of().name("CM").col("USUBJID", "01", "02", "03", "04")
                .col("CMDOSE", "10", "20", "", "40").build();
        assertCollapsedOnAbsent(run(rule("C7-CG0114", cg0114, FOLDED, List.of()), dosuAbsent), 0);
        assertPerRow(run(rule("C7-CG0114-OUT", cg0114, null, List.of()), dosuAbsent), 3);
        // The plain residual shape: absent trigger column, per-row residual — once, not per row.
        IDataTable noCode = MockTable.of().name("AE").col("USUBJID", "01", "02", "03")
                .col("AESEV", "SEVERE", "MILD", "SEVERE").build();
        String check = "empty(AEBDSYCD) and AESEV == \"SEVERE\"";
        assertCollapsedOnAbsent(run(optedIn(check), noCode), 0);
        assertPerRow(run(notOptedIn(check), noCode), 2);
        // is_missing over an absent CHAR column reads "" (D76): the leaf is FALSE on every row,
        // not live — and no row fires either way.
        assertEquals(0, run(optedIn("is_missing(AEBDSYCD) and AESEV == \"SEVERE\""), noCode)
                .getViolationCount());
        // The negated family over an absent column is FALSE on every row: the full fold decides
        // FALSE, 0 both ways.
        assertEquals(0, run(optedIn("non_empty(AEBDSYCD) and AESEV == \"SEVERE\""), noCode)
                .getViolationCount());
        // Any other operator over an absent column stays PER ROW, opted in or not.
        assertPerRow(run(optedIn("AEBDSYCD != \"X\" and AESEV == \"SEVERE\""), noCode), 2);
        assertPerRow(run(notOptedIn("AEBDSYCD != \"X\" and AESEV == \"SEVERE\""), noCode), 2);
    }


    @Test
    @DisplayName("OWNER refinement 2026-10-07 — a collapse only when EVERY polarity-live leaf is decided")
    void collapseOnlyWhenEveryLiveLeafIsDecided()
    {
        // FDA-SD0021 on AE: AEENRF / AEENRTPT / AEDUR absent (decided), AEENDTC present and
        // filled on some records (varies) — the rule stays PER ROW, count unchanged; the text
        // "… absent or not populated in any record …" would be false here and never prints.
        String sd0021 = "empty(AEENDTC) and empty(AEENRF) and empty(AEENRTPT) and empty(AEDUR)";
        IDataTable endtcVaries = MockTable.of().name("AE").col("USUBJID", "01", "02", "03", "04")
                .col("AEENDTC", "2020-01-01", "", "", "2020-02-02").build();
        assertPerRow(run(rule("C7-SD0021", sd0021, FOLDED, List.of()), endtcVaries), 2);
        // Every end column absent: every live leaf decided — one finding, the text true.
        IDataTable allAbsent = MockTable.of().name("MH").col("USUBJID", "01", "02", "03").build();
        assertCollapsedOnAbsent(run(rule("C7-SD0021", sd0021, FOLDED, List.of()), allAbsent), 0);
        // Present and blank on every record counts as decided too (mixed with absent).
        IDataTable endtcBlank = MockTable.of().name("AE").col("USUBJID", "01", "02", "03")
                .col("AEENDTC", "", "", "").build();
        assertCollapsedOnAbsent(run(rule("C7-SD0021", sd0021, FOLDED, List.of()), endtcBlank), 0);
        // CDISC-CG0082 on MH: `not empty(--BODSYS)` is not live, `empty(--BDSYCD)` is the only
        // live leaf and its column is absent — decided — one finding (the control).
        IDataTable mh = MockTable.of().name("MH").col("USUBJID", "01", "02", "03")
                .col("MHBODSYS", "Cardiac", "", "Renal").build();
        assertCollapsedOnAbsent(
                run(rule("C7-CG0082", "not empty(MHBODSYS) and empty(MHBDSYCD)", FOLDED, List.of()),
                        mh),
                0);
        // Two live leaves in an Or, one varying: per row.
        IDataTable orVaries = MockTable.of().name("AE").col("USUBJID", "01", "02", "03")
                .col("AEBDSYCD", "", "x", "").build();
        assertPerRow(run(rule("C7-OR", "empty(AEBDSYCD) or empty(AESOCCD)", FOLDED, List.of()),
                orVaries), 3);
    }


    @Test
    @DisplayName("PACK 4 confirmation MED-1 — a leaf in a branch a presence fact kills is not live")
    void aLeafInADeadBranchNeverDecidesTheCollapse()
    {
        // The CDISC-CG0655 / FDA-SD1017 shape: the SVUPDES branch is guarded by
        // `not var_exists("SVPRESP")`. With SVPRESP PRESENT that guard is FALSE for the whole
        // dataset, so empty(SVUPDES) — absent here — can never make the Check true: it decides
        // nothing, and the rule must not collapse "because another column is absent" (owner
        // 2026-10-07). The SVPRESP == "Y" branch decides per row: two firing records, two findings.
        String check = "(var_exists(\"SVPRESP\") and SVPRESP == \"Y\" or not var_exists(\"SVPRESP\")"
                + " and empty(SVUPDES)) and VISITNUM != \"1\"";
        IDataTable presp = MockTable.of().name("SV").col("USUBJID", "01", "02", "03", "04")
                .col("VISITNUM", "2", "3", "4", "1").col("SVPRESP", "Y", "Y", "N", "Y").build();
        assertPerRow(runOn(rule("MED1-SV", check, FOLDED, List.of()), presp, "SV"), 2);
        // The control: SVPRESP ABSENT — the guard is TRUE, empty(SVUPDES) is the only live leaf
        // and its column is absent: decided, one finding under Message_Folded.
        IDataTable noPresp = MockTable.of().name("SV").col("USUBJID", "01", "02", "03")
                .col("VISITNUM", "2", "3", "1").build();
        assertCollapsedOnAbsent(runOn(rule("MED1-SV", check, FOLDED, List.of()), noPresp, "SV"), 0);
    }


    private static RuleExecutionResult runOn(Rule rule, IDataTable table, String domain)
    {
        return RuleRunnerCalls.execute(rule, table, _ -> null, domain, null, null, null,
                Integer.MAX_VALUE, null, null, null, Set.of(), Set.of());
    }


    @Test
    @DisplayName("HIGH-1 (polarity) — a leaf constant FALSE over a blank column decides nothing and is no trigger")
    void aNegatedLeafOverABlankColumnIsNoTrigger()
    {
        // AEBODSYS present and blank everywhere: `not empty(AEBODSYS)` is FALSE on every row. In
        // the Or it decides nothing — AEBDSYCD decides per row — so the per-row findings stand
        // (the leaf used to trigger on its fill alone and fold the two rows to one).
        IDataTable t = MockTable.of().name("AE").col("USUBJID", "01", "02", "03")
                .col("AEBODSYS", "", "", "").col("AEBDSYCD", "", "x", "").build();
        assertPerRow(run(optedIn("empty(AEBDSYCD) or not empty(AEBODSYS)"), t), 2);
        // In an And it kills the rule: no row fires, no finding (0 either way).
        assertEquals(0,
                run(optedIn("empty(AEBDSYCD) and not empty(AEBODSYS)"), t).getViolationCount());
        // Negation through a Not of the empty leaf itself: `not non_empty(X)` over blank X is TRUE
        // on every row — polarity-live, and the collapse reports once.
        IDataTable blank = MockTable.of().name("AE").col("USUBJID", "01", "02", "03")
                .col("AEBODSYS", "Cardiac", "", "Renal").col("AEBDSYCD", "", "", "").build();
        assertCollapsed(run(optedIn("not non_empty(AEBDSYCD)"), blank), 0L);
    }


    @Test
    @DisplayName("P3 — Keep_Row_Findings keeps an absent column's row findings (R1)")
    void keepRowFindingsKeepsAnAbsentColumnPerRow()
    {
        IDataTable noCode = MockTable.of().name("AE").col("USUBJID", "01", "02", "03")
                .col("AESEV", "SEVERE", "MILD", "SEVERE").build();
        String check = "empty(AEBDSYCD) and AESEV == \"SEVERE\"";
        assertPerRow(run(rule("C7-KEEP", check, FOLDED, List.of("AEBDSYCD")), noCode), 2);
        // ⚠ The sensitivity control: the same rule without the opt-out collapses (the absent
        // trigger column's leaf is polarity-live, owner 2026-10-07).
        assertCollapsedOnAbsent(run(rule("C7-KEEP", check, FOLDED, List.of()), noCode), 0);
    }


    @Test
    @DisplayName("R5 — a dotted argument is never a trigger: empty(DM.X) over an all-blank joined X stays per row")
    void dottedArgumentNeverTriggers() throws Exception
    {
        String json = "{\"rules\":{\"T\":{\"Core\":{\"Id\":\"C7-DOT\"},\"Sensitivity\":\"Record\","
                + "\"Requirements\":{\"Variables\":{\"All\":[\"USUBJID\"],"
                + "\"All_Or_None\":[[\"USUBJID\",\"DM.USUBJID\"]]}},"
                + "\"Match_Datasets\":[{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"],"
                + "\"Join_Type\":\"left\"}],"
                + "\"Check\":{\"expression\":\"empty(DM.ARMCD) and non_empty(AEBDSYCD)\"},"
                + "\"Outcome\":{\"Message\":\"per-row message\",\"Message_Folded\":\"" + FOLDED
                + "\",\"Output_Variables\":[\"AEBDSYCD\"]}}}}";
        Rule rule = RulePackageLoader.loadFromString(json).getRules().get("T");
        assertNull(rule.getLoadError(), rule.getLoadError());
        IDataTable ae = RealTableFixture.of("AE").str("STUDYID", "S1", "S1")
                .str("DOMAIN", "AE", "AE").str("USUBJID", "01", "02").lng("AESEQ", 1L, 1L)
                .str("AEBDSYCD", "10001", "10002").build();
        IDataTable dm = RealTableFixture.of("DM").str("STUDYID", "S1", "S1")
                .str("USUBJID", "01", "02").str("ARMCD", "", "").build();
        RuleExecutionResult r = RuleRunnerCalls.execute(rule, ae, RealTables.inventoryOf(dm, ae));
        assertEquals(RuleExecutionStatus.EXECUTED, r.getStatus(), r.getStatusMessage());
        // AEBDSYCD is FILLED (no trigger) and DM.ARMCD, blank on every joined row, is a dotted
        // read — not a column of the evaluation table — so nothing collapses.
        assertEquals(2, r.getViolationCount(), "per row: " + r);
        assertEquals("per-row message", r.getMessage());
    }


    @Test
    @DisplayName("R5 — a SUPP-delivered qualifier is not a column of the table: never a trigger")
    void suppQualifierNeverTriggers() throws Exception
    {
        String json = "{\"rules\":{\"T\":{\"Core\":{\"Id\":\"C7-SUPP\"},\"Sensitivity\":\"Record\","
                + "\"Requirements\":{\"Variables\":{\"All\":[\"USUBJID\"]}},"
                + "\"Check\":{\"expression\":\"empty(AETRTEM)\"},"
                + "\"Outcome\":{\"Message\":\"per-row message\",\"Message_Folded\":\"" + FOLDED
                + "\",\"Output_Variables\":[\"AETRTEM\"]}}}}";
        Rule rule = RulePackageLoader.loadFromString(json).getRules().get("T");
        assertNull(rule.getLoadError(), rule.getLoadError());
        IDataTable ae = RealTableFixture.of("AE").str("STUDYID", "S1", "S1")
                .str("DOMAIN", "AE", "AE").str("USUBJID", "01", "01").lng("AESEQ", 1L, 2L).build();
        IDataTable suppae = RealTableFixture.of("SUPPAE").str("STUDYID", "S1", "S1")
                .str("RDOMAIN", "AE", "AE").str("USUBJID", "01", "01")
                .str("IDVAR", "AESEQ", "AESEQ").str("IDVARVAL", "1", "2")
                .str("QNAM", "AETRTEM", "AETRTEM").str("QVAL", "", "").build();
        RuleExecutionResult r = RuleRunnerCalls.execute(rule, ae,
                RealTables.inventoryOf(suppae, ae));
        assertEquals(RuleExecutionStatus.EXECUTED, r.getStatus(), r.getStatusMessage());
        assertEquals(2, r.getViolationCount(), "read per record through the pivot: " + r);
        assertEquals("per-row message", r.getMessage());
    }

    // ---- level-keyed rules -------------------------------------------------------------------


    @Test
    @DisplayName("a level-keyed rule collapses PER LEVEL; the DATASET unit's first claim keeps the strictest")
    void levelKeyedRuleCollapsesPerLevel() throws Exception
    {
        String json = "{\"rules\":{\"T\":{\"Core\":{\"Id\":\"C7-LEVELS\"},\"Sensitivity\":\"Record\","
                + "\"Severity\":\"Error\","
                + "\"Check\":{\"ERROR\":{\"expression\":\"empty(AEBDSYCD)\",\"Message\":\"level message\"},"
                + "\"WARNING\":{\"expression\":\"empty(AEBODSYS)\"}},"
                + "\"Outcome\":{\"Message\":\"per-row message\",\"Message_Folded\":\"" + FOLDED
                + "\",\"Output_Variables\":[\"AEBDSYCD\"]}}}}";
        Rule rule = RulePackageLoader.loadFromString(json).getRules().get("T");
        assertNull(rule.getLoadError(), rule.getLoadError());
        IDataTable t = MockTable.of().name("AE").col("USUBJID", "01", "02").col("AEBODSYS", "", "")
                .col("AEBDSYCD", "", "").build();
        RuleExecutionResult r = run(rule, t);
        assertEquals(RuleExecutionStatus.EXECUTED, r.getStatus(), r.getStatusMessage());
        assertEquals(1, r.getViolationCount(), "both levels collapse; one DATASET unit: " + r);
        Violation v = r.getViolations().getFirst();
        assertSame(Violation.Unit.DATASET_FOLDED, v.getUnit(), "a collapse is a FOLDED unit (Q6)");
        assertEquals(Severity.ERROR, v.getLevel(), "first claim: the strictest level's");
        assertEquals(FOLDED, r.getMessage(), "every contributor folded: the merged message");
    }
}
