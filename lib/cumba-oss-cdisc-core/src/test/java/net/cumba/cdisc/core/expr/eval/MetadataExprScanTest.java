package net.cumba.cdisc.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import net.cumba.cdisc.core.expr.CheckExpressionParser;
import net.cumba.cdisc.core.expr.ast.Expr;
import org.junit.jupiter.api.Test;

/** Phase 2b — static analysis of a checkExpr for the metadata accessors. */
class MetadataExprScanTest
{

    private static Expr parse(String s)
    {
        return CheckExpressionParser.parse(s);
    }


    @Test
    void detectsMetadataFunctions()
    {
        assertTrue(MetadataExprScan.containsMetadataFunction(parse(
                "var_label(variable_name, \"DEFINE\") != var_label(variable_name, \"DATA\")")));
        assertTrue(MetadataExprScan
                .containsMetadataFunction(parse("ds_class(\"DEFINE\") == \"Events\"")));
        assertFalse(MetadataExprScan.containsMetadataFunction(parse("AGE == \"56\"")));
    }


    @Test
    void distinguishesVariableFromDatasetScope()
    {
        assertTrue(MetadataExprScan
                .usesVariableScope(parse("var_role(variable_name, \"DEFINE\") == \"Identifier\"")));
        assertFalse(
                MetadataExprScan.usesVariableScope(parse("ds_class(\"DEFINE\") == \"Events\"")));
        // mixed: a var_* anywhere makes it variable-scope
        assertTrue(MetadataExprScan.usesVariableScope(parse(
                "ds_class(\"DEFINE\") == \"x\" || var_label(variable_name, \"DATA\") == \"y\"")));
    }


    @Test
    void collectsRequiredProviderLevels()
    {
        assertEquals(Set.of(MetadataLevel.DEFINE, MetadataLevel.LIBRARY),
                MetadataExprScan.providerLevelsUsed(parse(
                        "var_role(variable_name, \"DEFINE\") != var_role(variable_name, \"LIBRARY\")")));
        // DATA needs no provider
        assertEquals(Set.of(), MetadataExprScan
                .providerLevelsUsed(parse("var_label(variable_name, \"DATA\") == \"x\"")));
        assertEquals(Set.of(MetadataLevel.DEFINE),
                MetadataExprScan.providerLevelsUsed(parse("ds_structure(\"DEFINE\") == \"x\"")));
    }
}
