package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleRunner;
import net.cumba.corej.core.model.OperationType;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * {@code variable_exists} as a reportable operation — the {@code Fix #181} reporting warrant
 * applied to the one operator it could not reach.
 *
 * <p>
 * The operator has an {@link OperationType} again, but <b>only as the reporting carriage of the
 * {@code var_exists(X)} check function</b>. Two consequences are pinned here, and they are the
 * whole of the change:
 * </p>
 * <ul>
 * <li>a rule that declares the operation in Form B <b>loads</b> — before the {@link OperationType}
 * existed this was the {@code unknown operation function} landmine that cost 21 shipped rules their
 * load when the corpus lowering was gated off;</li>
 * <li>the lowering still rewrites {@code $X == true} to {@code var_exists(X)} — so no verdict moves
 * — but an operation whose {@code $}-id the rule <b>reports</b> now survives, and
 * {@link RuleRunner} materialises its value into the finding.</li>
 * </ul>
 */
class VariableExistsReportingRetentionTest
{

    /** Loads a one-rule package through the production loader and returns the rule. */
    private static Rule load(String ruleJson)
    {
        try
        {
            RulePackage pkg = RulePackageLoader
                    .loadFromString("{\"rules\":{\"X-1\":" + ruleJson + "}}");
            return pkg.getRules().get("X-1");
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("bad test fixture: " + ruleJson, e);
        }
    }


    /** The CORE-000291 shape in org form: {@code $X == true} plus a declared output variable. */
    private static String orgFormRule(String outputVariables)
    {
        return "{\"Core\":{\"Id\":\"X-1\"},"
                + "\"Operations\":[{\"id\":\"$EXVAMT_EXISTS\",\"operator\":\"variable_exists\","
                + "\"name\":\"EXVAMT\"}],"
                + "\"Check\":{\"expression\":\"$EXVAMT_EXISTS == true and ds_exists(\\\"EC\\\")\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":" + outputVariables + "}}";
    }


    /**
     * ⚠⚠ The landmine. Every retained operation ships as a Form B function call (since
     * {@code PLAN-retire-corpus-transforms} phase 8 it is authored that way; the retired
     * {@code OperationFormRewriter} used to render it), and
     * {@code OperationExpressionParser.fromCall} rejects a call whose name is not an
     * {@link OperationType} — which is exactly how gating the corpus lowering off produced 21
     * {@code unknown operation function `variable_exists`} loadErrors. Neuter-and-watch: delete the
     * {@code VARIABLE_EXISTS} constant and this case reports that message.
     */
    @Test
    void formBVariableExistsOperationLoadsWithoutAnUnknownFunctionError()
    {
        Rule rule = load("{\"Core\":{\"Id\":\"X-1\"},"
                + "\"Operations\":[{\"id\":\"$EXVAMT_EXISTS\","
                + "\"expression\":\"variable_exists(\\\"EXVAMT\\\")\"}],"
                + "\"Check\":{\"expression\":\"var_exists(\\\"EXVAMT\\\") and "
                + "ds_exists(\\\"EC\\\")\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"$EXVAMT_EXISTS\"]}}");
        assertNull(rule.getLoadError(), "Form B variable_exists must load");
        assertNotNull(rule.getOperations());
        assertEquals(1, rule.getOperations().size());
        assertEquals(OperationType.VARIABLE_EXISTS, rule.getOperations().get(0).getOperationType());
        assertEquals(List.of("$EXVAMT_EXISTS"), rule.getOutcome().getOutputVariables());
    }


    /**
     * The loader-side lowering (the org-form / parity fixture path) rewrites the Check but keeps a
     * reported operation. Neuter-and-watch: drop the {@code reported.contains(...)} arms in
     * {@code RulePackageLoader.inlineVariableExistsOps} and both the operation and the output
     * variable vanish.
     */
    @Test
    void loaderLowersTheCheckButRetainsAReportedOperation()
    {
        Rule rule = load(orgFormRule("[\"$EXVAMT_EXISTS\"]"));
        assertNull(rule.getLoadError());
        assertNotNull(rule.getCheckExpr(), "the lowered Check still compiles natively");
        assertTrue(rule.getCheck().toString().contains("var_exists"),
                "the verdict stays on the var_exists function: " + rule.getCheck());
        assertNotNull(rule.getOperations(), "a reported variable_exists operation is retained");
        assertEquals(1, rule.getOperations().size());
        assertEquals(List.of("$EXVAMT_EXISTS"), rule.getOutcome().getOutputVariables());
    }


    /** The complement: nothing reports the id, so the operation is still dropped. */
    @Test
    void loaderStillDropsAnUnreportedOperation()
    {
        Rule rule = load(orgFormRule("[\"EXVAMT\"]"));
        assertNull(rule.getLoadError());
        assertNull(rule.getOperations(), "an unreported variable_exists operation is dropped");
        assertEquals(List.of("EXVAMT"), rule.getOutcome().getOutputVariables());
    }


    /**
     * End-to-end: the retained operation's {@code $}-result reaches the finding. This is the whole
     * prize — the rule already fired correctly, what was missing was the reported value.
     */
    @Test
    void theRetainedOperationsValueIsReportedInTheFinding()
    {
        Rule rule = load(orgFormRule("[\"$EXVAMT_EXISTS\"]"));
        IDataTable ex = MockTable.of().name("EX").col("USUBJID", "S1").col("EXVAMT", "5").build();
        IDataTable ec = MockTable.of().name("EC").col("USUBJID", "S1").build();
        DatasetResolver resolver = Map.of("EX", ex, "EC", ec)::get;

        RuleExecutionResult result = RuleRunner.execute(rule, ex, resolver, null, null);

        assertEquals(1, result.getViolationCount(),
                "EXVAMT present and EC present ⇒ the rule fires, exactly as before");
        assertEquals("true", result.getViolations().get(0).getValues().get("$EXVAMT_EXISTS"),
                "the reported $-id carries the operation's value");
    }
}
