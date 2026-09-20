package net.cumba.corej.core.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.cumba.corej.core.expr.ast.Expr;
import org.junit.jupiter.api.Test;

/** Phase 4 — operand to {@code var_*}/{@code ds_*} migration (forward + reverse round-trip). */
class MetadataOperandMappingTest
{

    @Test
    void forwardMapsOperandsToAccessors()
    {
        assertEquals(CheckExpressionParser.parse("var_role(variable_name, \"LIBRARY\")"),
                MetadataOperandMapping.forwardOperand("library_variable_role"));
        assertEquals(CheckExpressionParser.parse("var_type(variable_name, \"DATA\")"),
                MetadataOperandMapping.forwardOperand("variable_data_type"));
        assertEquals(CheckExpressionParser.parse("ds_class(\"DEFINE\")"),
                MetadataOperandMapping.forwardOperand("define_dataset_class"));
    }


    @Test
    void forwardKeepsNonMigratableOperands()
    {
        assertNull(MetadataOperandMapping.forwardOperand("variable_name"));
        assertNull(MetadataOperandMapping.forwardOperand("variable_value"));
        assertNull(MetadataOperandMapping.forwardOperand("dataset_metadata"));
        assertNull(MetadataOperandMapping.forwardOperand("AETERM")); // a plain column
    }


    @Test
    void forwardMapsFormerTierBOperands()
    {
        // R-P3 (PLAN-native-engine-residuals): the Tier-B define operands now map to their
        // accessors (CDISC-CG0001).
        assertEquals(CheckExpressionParser.parse("var_ccode(variable_name, \"DEFINE\")"),
                MetadataOperandMapping.forwardOperand("define_variable_ccode"));
        assertEquals(
                CheckExpressionParser.parse("var_codelist_coded_codes(variable_name, \"DEFINE\")"),
                MetadataOperandMapping.forwardOperand("define_variable_codelist_coded_codes"));
    }


    @Test
    void reverseIsTheInverseForAnchoredForms()
    {
        for (String operand : new String[]
        {
                "library_variable_role", "define_variable_core", "variable_data_type",
                "variable_label", "library_variable_length", "dataset_name",
                "library_dataset_label", "define_dataset_class"
        })
        {
            Expr call = MetadataOperandMapping.forwardOperand(operand);
            assertEquals(operand, MetadataOperandMapping.reverseToOperand(call),
                    "round-trip operand <-> accessor for " + operand);
        }
    }


    @Test
    void reverseAcceptsLevelOnlyAndVarnameAnchoredVariableForms()
    {
        // change #6: the level-only overload var_<attr>(level) and the varname()-anchored form
        // reverse to the SAME operand as var_<attr>(variable_name, level), so #6 round-trips.
        assertEquals("variable_label", MetadataOperandMapping
                .reverseToOperand(CheckExpressionParser.parse("var_label(\"DATA\")")));
        assertEquals("define_variable_core", MetadataOperandMapping
                .reverseToOperand(CheckExpressionParser.parse("var_core(\"DEFINE\")")));
        assertEquals("variable_label", MetadataOperandMapping
                .reverseToOperand(CheckExpressionParser.parse("var_label(varname(), \"DATA\")")));
    }


    @Test
    void reverseRejectsArbitraryLiteralAndNamedForms()
    {
        // arbitrary-literal variable name has no operand (native-only)
        assertNull(MetadataOperandMapping.reverseToOperand(
                CheckExpressionParser.parse("var_label(\"AESTDTC\", \"DEFINE\")")));
        // named dataset (2-arg ds_*) has no operand
        assertNull(MetadataOperandMapping
                .reverseToOperand(CheckExpressionParser.parse("ds_class(\"AE\", \"DEFINE\")")));
    }
}
