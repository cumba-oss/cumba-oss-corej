package net.cumba.corej.core.exec;

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
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.eval.Primitives;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.support.OverlayDataTable;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.DataValueMissing;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-member-set-identity-hardening} review round 2 — a missing value inside a composite (a
 * {@code tuple(…)} reference set, a group-unit stamp) is a {@link Primitives.MissingMember}
 * <b>object</b>: equal only to the same marker, and printed as that marker if it ever reaches a
 * report.
 */
class MissingMemberIdentityTest
{

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** A reference dataset whose DAY column is missing on the W1 row. */
    private static IDataTable reference()
    {
        return MockTable.of().name("TV").col("VISIT", "W1", "W2").colSasMissing("DAY", null, "1")
                .build();
    }


    /**
     * {@code distinct([…])} as the registry function it is since runbook W7 ({@code Distinct}): the
     * dataset-level tuple list, read over {@code aTable} with the rule's numeric expectations.
     */
    private static List<?> tuples(IDataTable aTable, Set<String> aNumericExpected, String... aNames)
    {
        EvaluationContext ctx = EvaluationContext.builder().table(aTable)
                .numericExpectedColumns(aNumericExpected).build();
        net.cumba.corej.core.expr.eval.Vector v = net.cumba.corej.core.expr.eval.ExprCompiler
                .evaluateValueExpression(net.cumba.corej.core.expr.CheckExpressionParser
                        .parse("distinct([" + String.join(", ", aNames) + "])"), ctx);
        assertNotNull(v);
        return (List<?>) v.value(0).resolved();
    }


    /**
     * M1 — a {@code $}-tuple set named in {@code Output_Variables} prints a missing component as
     * its marker; the control-character identity token of round 1 leaked into the report here.
     */
    @Test
    void aTupleSetInOutputVariablesPrintsTheMarkerNeverAControlToken()
    {
        List<?> tuples = tuples(reference(), Set.of(), "VISIT", "DAY");
        IDataTable primary = MockTable.of().name("SV").col("USUBJID", "S1").build();
        EvaluationContext ctx = EvaluationContext.builder().table(primary)
                .variables(Map.of("$tuples", tuples)).build();
        String rendered = RuleRunner.extractOutputValues(primary, ctx, List.of("$tuples"), 0)
                .get("$tuples");
        assertTrue(rendered.contains("[W1, .]"),
                "the missing DAY prints as '.' inside its tuple: " + rendered);
        assertTrue(rendered.contains("[W2, 1]"), rendered);
        assertFalse(rendered.contains("\u0001"), "no control-character token may reach a report");
    }


    /** M1 — the reference set's component is an identity: not "", not ".", only the same marker. */
    @Test
    void aMissingReferenceComponentIsTheMissingMemberIdentity()
    {
        List<?> tuples = tuples(reference(), Set.of(), "VISIT", "DAY");
        assertTrue(tuples.contains(List.of("W1", new Primitives.MissingMember(MissingValue.MIS))));
        assertFalse(tuples.contains(List.of("W1", ".")), "a present '.' is not the missing");
        assertFalse(tuples.contains(List.of("W1", "")), "a present blank is not the missing");
    }


    /**
     * L3 — an ABSENT reference column is the NVE constant of its type's default: {@code ""} for
     * character, {@code MIS} for a column the rule expects numeric — the constant the
     * {@code tuple(…)} probe side's operand plan folds an absent column to.
     */
    @Test
    void anAbsentReferenceColumnTakesItsTypeDefault()
    {
        List<?> charDefault = tuples(reference(), Set.of(), "VISIT", "ARMCD");
        assertEquals(List.of(List.of("W1", ""), List.of("W2", "")), charDefault,
                "absent, no numeric expectation: the character default \"\"");
        List<?> numericDefault = tuples(reference(), Set.of("ARMCD"), "VISIT", "ARMCD");
        Primitives.MissingMember mis = new Primitives.MissingMember(MissingValue.MIS);
        assertEquals(List.of(List.of("W1", mis), List.of("W2", mis)), numericDefault,
                "absent, numeric expectation: MIS — it was \"\" on this side only until round 2");
    }


    /**
     * L4 — the group-unit stamp is exact. Group {@code MIS} is claimed by the ERROR rung; group
     * {@code MIS_A} is flagged only by the INFO rung. With the old {@code null}-for-any-missing
     * stamp both blocks stamped the same unit, so the INFO finding was swallowed by first-claim.
     */
    @Test
    void twoGroupsDifferingOnlyByTheirMissingMarkerBothReport() throws IOException
    {
        Rule rule = YAML.readValue("""
                Core:
                  Id: "T-STAMP"
                Description: "d"
                Grouping:
                  Variables: ["G"]
                  keep_missings: true
                Check:
                  ERROR:
                    expression: >-
                      A == "a"
                  INFO:
                    expression: >-
                      A == "a" or A == "x"
                """, Rule.class);
        rule.setSensitivity(Sensitivity.GROUP);
        Outcome outcome = new Outcome();
        outcome.setMessage("m");
        outcome.setOutputVariables(List.of("A"));
        rule.setOutcome(outcome);
        RulePackageLoader.installNativeExpr(rule);
        assertNull(rule.getLoadError(), "fixture must load clean");

        OverlayDataTable t = OverlayDataTable.empty("AE", "AE", 2);
        t.addColumn("G", DataValueType.STRING, "G");
        t.addColumn("A", DataValueType.STRING, "A");
        int g = t.getMetaData().getColumnIndex("G");
        t.setDataValue(0, g, new DataValueMissing(MissingValue.MIS));
        t.setDataValue(1, g, new DataValueMissing(MissingValue.MIS_A));
        t.setValue(0, "A", "a");
        t.setValue(1, "A", "x");
        assertEquals(MissingValue.MIS_A, t.getColumn(g).getDataValue(1L).getValue(),
                "fixture control: row 1's key must be the .A missing");

        RuleExecutionResult r = RuleRunnerCalls.execute(rule, t, _ -> null, "AE", null, null, null,
                Integer.MAX_VALUE, null, null, null, Set.of(), Set.of(), Severity.INFO);
        assertEquals(2, r.getViolationCount(),
                "the MIS and the .A group are two units (D11) — one finding each");
        assertEquals(Set.of(Severity.ERROR, Severity.INFO),
                Set.of(r.getViolations().get(0).getLevel(), r.getViolations().get(1).getLevel()));
    }
}
