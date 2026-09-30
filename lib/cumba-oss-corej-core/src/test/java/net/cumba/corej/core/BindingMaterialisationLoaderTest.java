package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * Loader-level wiring of {@code Bindings:} ({@code RulePackageLoader.materialiseBindings}): an
 * entry is parsed once into a compiled binding at load, and one whose expression does not parse is
 * filed on the rule's {@code loadError} channel (runbook W8 D-W8-4 — the load error the retired
 * operation path used to file, spelled for a binding).
 */
class BindingMaterialisationLoaderTest
{

    private static Rule loadRule(String ruleBody) throws IOException
    {
        RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"x\":" + ruleBody + "}}");
        assertNotNull(pkg.getRules());
        return pkg.getRules().values().iterator().next();
    }


    @Test
    void aBindingIsMaterialisedAtLoad() throws IOException
    {
        Rule rule = loadRule("""
                {"Core":{"Id":"T-OP1","Status":"Draft","Version":"1"},
                 "Check":{"expression":"--STDTC not in $STARTS"},
                 "Bindings":[{"name":"$STARTS",
                                "expression":"distinct(RFSTDTC, domain=\\"DM\\")"}]}""");
        assertNull(rule.getLoadError());
        assertEquals(1, rule.bindingOrder().size());
        assertEquals("$STARTS", rule.getCompiledBindings().get(0).name());
        assertEquals("distinct(RFSTDTC, domain=\"DM\")", net.cumba.corej.core.expr.ExpressionPrinter
                .print(rule.getCompiledBindings().get(0).expression()));
    }


    @Test
    void anUnknownFunctionInABindingSetsLoadError() throws IOException
    {
        Rule rule = loadRule("""
                {"Core":{"Id":"T-OP2","Status":"Draft","Version":"1"},
                 "Check":{"expression":"$X < 2"},
                 "Bindings":[{"name":"$X","expression":"bogus_operation(X)"}]}""");
        assertNotNull(rule.getLoadError());
    }


    @Test
    void anUnparseableBindingExpressionSetsLoadError() throws IOException
    {
        // Until W8 this rode the operation path ("invalid operation expression"); the same
        // channel, the same rule, the noun corrected (D-W8-4).
        Rule rule = loadRule("""
                {"Core":{"Id":"T-OP3","Status":"Draft","Version":"1"},
                 "Check":{"expression":"$X < 2"},
                 "Bindings":[{"name":"$X","expression":"record_count(domain=\\"DM\\""}]}""");
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().contains("invalid binding expression"), rule.getLoadError());
    }

}
