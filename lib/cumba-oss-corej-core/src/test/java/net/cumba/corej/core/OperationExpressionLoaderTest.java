package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import net.cumba.corej.core.model.Operation;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * Loader-level wiring for the function-call operation form (Form B): an {@code Operation} carrying
 * an {@code expression} is normalised to field form at load, and a malformed one is filed on the
 * rule's {@code loadError} channel.
 */
class OperationExpressionLoaderTest
{

    private static Rule loadRule(String ruleBody) throws IOException
    {
        RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"x\":" + ruleBody + "}}");
        assertNotNull(pkg.getRules());
        return pkg.getRules().values().iterator().next();
    }


    @Test
    void expressionOperationNormalisedAtLoad() throws IOException
    {
        Rule rule = loadRule("""
                {"Core":{"Id":"T-OP1","Status":"Draft","Version":"1"},
                 "Check":{"expression":"var_exists(--LNKGRP) and $VARIABLE_COUNT < 2"},
                 "Bindings":[{"name":"$VARIABLE_COUNT",
                                "expression":"variable_count(--LNKGRP)"}]}""");
        assertNull(rule.getLoadError());
        assertNotNull(rule.getOperations());
        Operation op = rule.getOperations().get(0);
        assertEquals("$VARIABLE_COUNT", op.getId());
        assertEquals("variable_count", op.getOperator());
        assertEquals("--LNKGRP", op.getName());
        assertNull(op.getExpression());
    }


    @Test
    void malformedExpressionOperationSetsLoadError() throws IOException
    {
        Rule rule = loadRule("""
                {"Core":{"Id":"T-OP2","Status":"Draft","Version":"1"},
                 "Check":{"expression":"$X < 2"},
                 "Bindings":[{"name":"$X","expression":"bogus_operation(X)"}]}""");
        assertNotNull(rule.getLoadError());
    }


    /**
     * A field-form {@link Operation} constructed <em>programmatically</em> (the executor-internal
     * record — no YAML/JSON surface produces one since phase 7b) passes through the normalise pass
     * untouched.
     */
    @Test
    void programmaticFieldFormOperationUntouched()
    {
        Rule rule = new Rule();
        Operation op = new Operation();
        op.setId("$VARIABLE_COUNT");
        op.setOperator("variable_count");
        op.setName("--LNKGRP");
        rule.setOperations(new java.util.ArrayList<>(java.util.List.of(op)));
        RulePackageLoader.normalizeOperations(rule);
        assertNull(rule.getLoadError());
        assertEquals("variable_count", rule.getOperations().get(0).getOperator());
        assertEquals("--LNKGRP", rule.getOperations().get(0).getName());
    }

}
