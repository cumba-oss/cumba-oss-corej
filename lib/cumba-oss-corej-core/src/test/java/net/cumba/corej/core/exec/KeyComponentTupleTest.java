package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.eval.Primitives;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * Residual I6 ({@code FINDINGS-unowned-residuals} §I6), closed by runbook W7
 * ({@code PLAN-distinct-function} D-W7-11): a tuple element of a {@code $}-list compares
 * <b>structurally</b> in {@link Primitives#keyComponent} — the list of its components' key
 * components — never by its rendering. RED before the fix: {@code [S1, MIS]} and {@code [S1, "."]}
 * both rendered {@code "[S1, .]"}, and {@code ["A, B", "C"]} met {@code ["A", "B, C"]}.
 */
class KeyComponentTupleTest
{

    @Test
    void aMissingComponentIsNotThePresentDotText()
    {
        Object missing = Primitives.keyComponent(List.of("S1", MissingValue.MIS));
        Object dot = Primitives.keyComponent(List.of("S1", "."));
        assertNotEquals(missing, dot, "a missing component keeps its identity one level down");
        assertEquals(
                Primitives.keyComponent(
                        List.of("S1", new Primitives.MissingMember(MissingValue.MIS))),
                missing,
                "the MissingMember and the MissingValue spellings of one missing are one component");
        assertNotEquals(Primitives.keyComponent(List.of("S1", MissingValue.MIS_A)), missing,
                "two different missings are two components (D34 #5-2)");
        assertEquals(List.of("S1", "."), dot, "a present \".\" stays the text it is");
    }


    @Test
    void aCommaInsideAComponentDoesNotCollideWithAComponentBoundary()
    {
        assertNotEquals(Primitives.keyComponent(List.of("A, B", "C")),
                Primitives.keyComponent(List.of("A", "B, C")));
    }


    private static Rule load(String have, String need, String check)
    {
        try
        {
            RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"X-1\":{"
                    + "\"Core\":{\"Id\":\"X-1\"},"
                    + "\"Bindings\":[{\"name\":\"$have\",\"expression\":\"" + have + "\"},"
                    + "{\"name\":\"$need\",\"expression\":\"" + need + "\"}],"
                    + "\"Check\":{\"expression\":\"" + check + "\"},"
                    + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}}}");
            Rule rule = pkg.getRules().get("X-1");
            assertNull(rule.getLoadError(), rule.getLoadError());
            return rule;
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("bad test fixture", e);
        }
    }


    @Test
    void containsAllOverTwoTupleSetsComparesComponentsNotRenderings()
    {
        // The PMDA-AD0253 shape: `not contains_all($have, $need)` over two distinct tuple sets.
        // The primary's VISIT is the present text "."; the other dataset's VISIT is MISSING. Under
        // the rendering both tuples were "[S1, .]" and the Check stayed silent.
        IDataTable primary = RealTables.of("ADLB").str("DOMAIN", "LB").str("USUBJID", "S1")
                .str("VISIT", ".").build();
        IDataTable other = RealTables.of("LB").str("DOMAIN", "LB").str("USUBJID", "S1")
                .str("VISIT", (String) null).build();
        Rule rule = load("distinct([USUBJID, VISIT], domain=LB)", "distinct([USUBJID, VISIT])",
                "not contains_all($have, $need)");
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, primary,
                name -> "LB".equalsIgnoreCase(name) ? other : null);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(1, result.getViolations().size(),
                "(S1, \".\") is not contained in {(S1, missing)}");
        // the control: the same present text on both sides IS contained
        IDataTable same = RealTables.of("LB").str("DOMAIN", "LB").str("USUBJID", "S1")
                .str("VISIT", ".").build();
        RuleExecutionResult control = RuleRunnerCalls.execute(rule, primary,
                name -> "LB".equalsIgnoreCase(name) ? same : null);
        assertEquals(0, control.getViolations().size());
    }
}
