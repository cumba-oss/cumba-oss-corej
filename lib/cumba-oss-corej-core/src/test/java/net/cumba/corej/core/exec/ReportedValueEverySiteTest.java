package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.eval.TypedValue;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * ⭐ {@code PLAN-member-set-identity-hardening} review round 1, R1 — every site that renders a cell
 * into a finding prints a missing cell as its <b>marker</b>, through the one helper
 * {@link RuleRunner#reportedValue}.
 *
 * <p>
 * Owner 2026-09-25 (§7 Q1): <i>"print the marker. Accepted that this might look like a
 * literal."</i> — reaching every rendering site by {@code D72} / {@code NF §9c} (a merged column
 * behaves in every respect like a primary one; wherever they are used they behave identically). The
 * first pass routed four sites; the review found the report still rendered a missing cell four
 * other ways ({@code ""}, {@code null}, or omitted). Each is pinned here beside a present-blank
 * control, so the two readings of "blank" stay apart.
 * </p>
 */
class ReportedValueEverySiteTest
{

    private static Rule load(String ruleJson) throws Exception
    {
        RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"R1\":" + ruleJson + "}}");
        Rule rule = pkg.getRules().get("R1");
        assertNull(rule.getLoadError(), "rule must load cleanly: " + rule.getLoadError());
        return rule;
    }


    /** Row identity: a missing USUBJID / SEQ prints its marker; a present blank stays "". */
    @Test
    void rowIdentityPrintsAMissingCellsMarker()
    {
        IDataTable table = MockTable.of().colSasMissing("USUBJID", "S1", null, "")
                .colSasMissing("AESEQ", "1", null, "2").build();
        RuleRunner.RowIdentity missing = RuleRunner.readRowIdentity(table, "AE", 1);
        assertEquals(".", missing.usubjid(), "a missing USUBJID printed \"\" until 2026-09-26");
        assertEquals(".", missing.seq(), "a missing AESEQ printed \"\" until 2026-09-26");
        RuleRunner.RowIdentity blank = RuleRunner.readRowIdentity(table, "AE", 2);
        assertEquals("", blank.usubjid(), "control: a present blank USUBJID stays \"\"");
    }


    /**
     * {@code variable_value} with Output_Variables authored: a missing cell prints its marker, a
     * present blank stays {@code ""} (both fire {@code empty(value())}).
     */
    @Test
    void variableValuePrintsAMissingCellsMarkerInTheAuthoredArm() throws Exception
    {
        Rule rule = load("{\"Core\":{\"Id\":\"R1\"},\"Sensitivity\":\"Record\","
                + "\"Check\":{\"expression\": \"varname() == \\\"CODE\\\" and empty(value())\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":"
                + "[\"variable_name\",\"variable_value\"]}}");
        IDataTable t = MockTable.of().name("ADSL").colSasMissing("CODE", "7", null, "")
                .col("USUBJID", "S1", "S2", "S3").build();
        RuleExecutionResult r = RuleRunnerCalls.execute(rule, t, _ -> null, "ADSL", null, null,
                null);
        assertEquals(2, r.getViolations().size(), "rows 1 (missing) and 2 (blank) fire");
        assertEquals(".", r.getViolations().get(0).getValues().get("variable_value"),
                "a missing variable_value prints its marker");
        assertEquals("", r.getViolations().get(1).getValues().get("variable_value"),
                "control: a present blank stays \"\"");
    }


    /**
     * The default arm (no Output_Variables authored): a missing cell used to be OMITTED from the
     * finding while the authored arm printed it — the two arms now agree.
     */
    @Test
    void variableValuePrintsAMissingCellsMarkerInTheDefaultArm() throws Exception
    {
        Rule rule = load("{\"Core\":{\"Id\":\"R1\"},\"Sensitivity\":\"Record\","
                + "\"Check\":{\"expression\": \"varname() == \\\"CODE\\\" and empty(value())\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"!variable_name\"]}}");
        IDataTable t = MockTable.of().name("ADSL").colSasMissing("CODE", "7", null, "")
                .col("USUBJID", "S1", "S2", "S3").build();
        RuleExecutionResult r = RuleRunnerCalls.execute(rule, t, _ -> null, "ADSL", null, null,
                null);
        assertEquals(2, r.getViolations().size(), "rows 1 (missing) and 2 (blank) fire");
        assertEquals(".", r.getViolations().get(0).getValues().get("variable_value"),
                "the default arm printed nothing for a missing cell until 2026-09-26");
        assertEquals("", r.getViolations().get(1).getValues().get("variable_value"),
                "control: a present blank is reported as \"\"");
    }


    /** A Group rule over USUBJID projecting CODE, firing on every row. */
    private static Rule groupRule()
    {
        Rule rule = new Rule();
        rule.setId("TEST-G1");
        rule.setSensitivity(Sensitivity.GROUP);
        rule.setGroupingVariables(List.of("USUBJID"));
        rule.setCheckExpr(CheckExpressionParser.parse("FLAG == \"Y\""));
        rule.setCheck(new net.cumba.corej.core.model.CheckConditionExpression(
                CheckExpressionParser.parse("FLAG == \"Y\""), "FLAG == \"Y\""));
        Outcome outcome = new Outcome();
        outcome.setOutputVariables(List.of("CODE"));
        rule.setOutcome(outcome);
        return rule;
    }


    /** A group's constant missing value prints its marker (D94b's degenerate distinct set). */
    @Test
    void aGroupOfMissingsPrintsTheMarker()
    {
        IDataTable t = MockTable.of().name("AE").col("USUBJID", "A", "A").col("FLAG", "Y", "Y")
                .colSasMissing("CODE", null, null).build();
        RuleExecutionResult r = RuleRunnerCalls.execute(groupRule(), t);
        assertEquals(1, r.getViolations().size());
        assertEquals(".", r.getViolations().get(0).getValues().get("CODE"),
                "{MIS, MIS} is one distinct value, printed as its marker");
    }


    /**
     * ⚠ A SHAPE CHANGE, recorded in the plan: a group holding a missing AND a present blank used to
     * render both as "" — one distinct value, so the degenerate "" — and now renders two distinct
     * values, the distinct-set shape {@code "[., ]"}.
     */
    @Test
    void aGroupOfAMissingAndABlankRendersTheDistinctSet()
    {
        IDataTable t = MockTable.of().name("AE").col("USUBJID", "A", "A").col("FLAG", "Y", "Y")
                .colSasMissing("CODE", null, "").build();
        RuleExecutionResult r = RuleRunnerCalls.execute(groupRule(), t);
        assertEquals(1, r.getViolations().size());
        assertEquals("[., ]", r.getViolations().get(0).getValues().get("CODE"),
                "a missing and a blank are two different values (D12), so the set is not"
                        + " degenerate");
    }


    /**
     * The group-finding KEY is rendered for the report, so a missing key cell prints its marker;
     * the first-claim STAMP is an identity, so it carries the KeyPart token instead.
     */
    @Test
    void aMissingGroupKeyPrintsItsMarker()
    {
        Rule rule = groupRule();
        rule.setGroupingVariables(null);
        net.cumba.corej.core.model.GroupingSpec grouping = new net.cumba.corej.core.model.GroupingSpec();
        grouping.setVariables(List.of("GRP"));
        grouping.setKeepMissings(Boolean.TRUE); // a missing key forms a group only when kept
        rule.setGrouping(grouping);
        IDataTable t = MockTable.of().name("AE").colSasMissing("GRP", null, null)
                .col("FLAG", "Y", "Y").col("CODE", "x", "x").col("USUBJID", "A", "A").build();
        RuleExecutionResult r = RuleRunnerCalls.execute(rule, t);
        assertEquals(1, r.getViolations().size());
        assertEquals(".", r.getViolations().get(0).getGroupKey().get("GRP"),
                "a missing grouping key printed null until 2026-09-26");
    }


    /** The one rendering, directly: a present value verbatim, a missing its marker, "" stays. */
    @Test
    void reportedValueRendersEachKindOfCell()
    {
        IDataTable t = MockTable.of().colSasMissing("X", "v", null, "").build();
        assertTrue(TypedValue.missingIdentityOf(t.getColumn(0).getDataValue(1L)) != null,
                "fixture control: row 1 must be a genuine missing");
        assertEquals("v", RuleRunner.reportedValue(t.getColumn(0).getDataValue(0L)));
        assertEquals(".", RuleRunner.reportedValue(t.getColumn(0).getDataValue(1L)));
        assertEquals("", RuleRunner.reportedValue(t.getColumn(0).getDataValue(2L)));
    }
}
