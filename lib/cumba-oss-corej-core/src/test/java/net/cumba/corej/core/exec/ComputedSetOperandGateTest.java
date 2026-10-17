package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PACK I E2 (PI-C3): the set operators' <b>source</b> operand is fixed by the compiler — the
 * contains family ({@code contains_all} / {@code not contains_all}) takes a column, a
 * {@code $}-binding or a list-valued {@code var_*} accessor <em>with a second operand</em>; the
 * ordered-subset family takes a reference on both sides. Any other shape (a computed operand such
 * as {@code upper(…)} or {@code split_by(…)}) used to load silently and ERROR at run: the compile
 * refused it, the loader installed no {@code checkExpr}, nothing told the author. The refusal is
 * now ONE predicate ({@code ExprCompiler.setSourceRefusal}) shared by the compiler and an armed
 * stage-A kind ({@code COMPUTED_SET_OPERAND}), so the shape is a load error naming the function as
 * written and the {@code $}-binding route.
 */
class ComputedSetOperandGateTest
{

    private static Rule load(String check) throws java.io.IOException
    {
        String body = "{\"Core\":{\"Id\":\"CSO-1\"},\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},"
                + "\"Bindings\":[{\"name\":\"$x\",\"expression\":\"[\\\"A\\\", \\\"B\\\"]\"},"
                + "{\"name\":\"$y\",\"expression\":\"[\\\"A\\\"]\"},"
                + "{\"name\":\"$src\",\"expression\":\"upper(var_codelist_coded_values(\\\"LIBRARY\\\"))\"}],"
                + "\"Check\":{\"expression\":\"" + check.replace("\"", "\\\"")
                + "\"},\"Outcome\":{\"Message\":\"m\"}}";
        return RulePackageLoader.loadFromString("{\"rules\":{\"CSO-1\":" + body + "}}").getRules()
                .get("CSO-1");
    }


    @Test
    @DisplayName("a computed source operand is a load error naming the function as written")
    void aComputedSourceOperandIsALoadError() throws Exception
    {
        Map<String, String> refused = new LinkedHashMap<>();
        refused.put("contains_all(upper($x), $y)", "contains_all's source list");
        // `not contains_all(…)` parses as not(contains_all(…)): the operator as written
        refused.put("not contains_all(upper(var_codelist_coded_values(\"LIBRARY\")), $y)",
                "contains_all's source list");
        refused.put("not contains_all(split_by(AETERM, \";\"), $y)", "contains_all's source list");
        // the function spelling the guide documents is named as such
        refused.put("not_contains_all(upper($x), $y)", "not_contains_all's source list");
        refused.put("is_ordered_subset_of(upper($x), $y)", "is_ordered_subset_of's name operand");
        refused.put("is_ordered_subset_of($x, upper($y))", "is_ordered_subset_of's value operand");
        refused.put("is_not_ordered_subset_of(upper($x), $y)",
                "is_not_ordered_subset_of's name operand");
        for (Map.Entry<String, String> e : refused.entrySet())
        {
            Rule r = load(e.getKey());
            assertNotNull(r.getLoadError(), e.getKey() + " must fail to load");
            assertTrue(r.getLoadError().contains(e.getValue()),
                    e.getKey() + ": " + r.getLoadError());
            assertTrue(r.getLoadError().contains("bind it first"),
                    e.getKey() + ": " + r.getLoadError());
            assertNull(r.getCheckExpr(), e.getKey() + " must not install a checkExpr");
        }
    }


    /**
     * Review L6: a list-valued accessor as the source with {@code keys=} instead of a second
     * operand is the shape the compiler's {@code args.size() >= 2} arm refuses — a load error too.
     */
    @Test
    @DisplayName("a list accessor source without a second operand (keys=) is a load error")
    void aListAccessorSourceWithKeysIsALoadError() throws Exception
    {
        Rule r = load("contains_all(var_codelist_coded_values(\"LIBRARY\"), keys=[\"A\"])");
        assertNotNull(r.getLoadError(), "must fail to load");
        assertTrue(r.getLoadError().contains("contains_all's source list"), r.getLoadError());
        assertTrue(r.getLoadError().contains("second operand"), r.getLoadError());
    }


    @Test
    @DisplayName("the supported shapes load and compile")
    void theSupportedShapesLoad() throws Exception
    {
        for (String check : List.of("not contains_all(var_codelist_coded_values(\"LIBRARY\"), $y)",
                "contains_all($x, $y)", "contains_all(AETERM, $y)",
                "contains_all(AETERM, keys=[\"A\"])",
                // CDISC-SEND-0049's shape: a per-row token producer as the REQUIRED side
                "not contains_all(var_codelist_coded_values(\"LIBRARY\"), split_by(value(), \";\"))",
                // the advised route
                "contains_all($src, $y)", "is_ordered_subset_of($x, $y)",
                "is_not_ordered_subset_of(AETERM, $y)",
                // PI-C3: the membership over a computed list compiles natively
                "upper(value()) in upper(var_codelist_coded_values(\"LIBRARY\"))"))
        {
            Rule r = load(check);
            assertNull(r.getLoadError(), check + ": " + r.getLoadError());
            assertNotNull(r.getCheckExpr(), check + " must install a checkExpr");
        }
    }

    // ------------------------------------------------------------------
    // the advised route, executed (review L8)
    // ------------------------------------------------------------------


    private static Rule variableRule(String check) throws Exception
    {
        String json = "{\"Core\":{\"Id\":\"CSO-2\"},\"Sensitivity\":\"Dataset\","
                + "\"Bindings\":[{\"name\":\"$req\",\"expression\":\"[\\\"SUPINE\\\"]\"},"
                + "{\"name\":\"$src\",\"expression\":\"upper(var_codelist_coded_values(\\\"LIBRARY\\\"))\"}],"
                + "\"Check\":{\"expression\":\"" + check.replace("\"", "\\\"") + "\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"variable_name\"]}}";
        Rule r = RulePackageLoader.loadFromString("{\"rules\":{\"CSO-2\":" + json + "}}").getRules()
                .get("CSO-2");
        assertNull(r.getLoadError(), check + ": " + r.getLoadError());
        assertNotNull(r.getCheckExpr(), check + " must compile");
        return r;
    }


    /** Library provider: VS.VSPOS carries a lower-case spelled codelist. */
    private static MetadataProvider library()
    {
        return new StubMetadataProvider().variable("VS",
                Map.of("name", "VSPOS", "codelist_coded_values", "[\"supine\",\"standing\"]"));
    }


    private static RuleExecutionResult run(Rule rule)
    {
        IDataTable vs = MockTable.of().name("VS").col("VSPOS", "SUPINE").col("VSTEST", "Height")
                .build();
        return RuleRunnerCalls.execute(rule, vs, _ -> null, "VS", library(), null, null);
    }


    /**
     * The route the load error advises, executed on a variable-scoped accessor: the binding
     * {@code $src: upper(var_codelist_coded_values("LIBRARY"))} resolves per variable under
     * iteration like the inline accessor would, so {@code contains_all($src, $req)} answers what
     * the refused {@code contains_all(upper(var_codelist_coded_values("LIBRARY")), $req)} intends —
     * VSPOS's upper-cased codelist contains {@code SUPINE}, the rule fires for VSPOS and for no
     * other variable. The inline control (no fold) pins the baseline the route is measured against.
     */
    @Test
    @DisplayName("the advised $src route answers the inline intent on a variable-scoped accessor")
    void theAdvisedBindingRouteExecutes() throws Exception
    {
        RuleExecutionResult route = run(variableRule("contains_all($src, $req)"));
        assertEquals(RuleExecutionStatus.EXECUTED, route.getStatus(), route.getStatusMessage());
        assertTrue(route.hasViolations(), "VSPOS's folded codelist contains SUPINE");
        assertEquals(1, route.getViolations().size(), route.getViolations().toString());
        assertTrue(route.getViolations().getFirst().toString().contains("VSPOS"),
                route.getViolations().getFirst().toString());

        // control: without the fold the lower-case codelist does not contain "SUPINE"
        RuleExecutionResult inline = run(
                variableRule("contains_all(var_codelist_coded_values(\"LIBRARY\"), $req)"));
        assertEquals(RuleExecutionStatus.EXECUTED, inline.getStatus(), inline.getStatusMessage());
        assertFalse(inline.hasViolations(), inline.getViolations().toString());
    }
}
