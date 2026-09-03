package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.expr.RuleDefinitionException;
import net.cumba.corej.core.model.Operation;
import org.junit.jupiter.api.Test;

/**
 * {@link OperationExpressionParser}: the function-call authoring form (Form B) lowers to the same
 * field-form {@link Operation} the executor consumes. Each operator's parameters round-trip, the
 * coercions (filter marker, list, boolean, key pairs) are exercised, and every authoring error
 * raises {@link RuleDefinitionException}.
 */
class OperationExpressionParserTest
{

    private static Operation normalize(String expression)
    {
        Operation op = new Operation();
        op.setId("$RES");
        op.setExpression(expression);
        return OperationExpressionParser.normalize(op);
    }


    @Test
    void fieldFormPassesThroughUnchanged()
    {
        Operation op = new Operation();
        op.setId("$V");
        op.setOperator("variable_count");
        op.setName("--LNKGRP");
        assertSame(op, OperationExpressionParser.normalize(op));
    }


    @Test
    void positionalNameAndPreservedId()
    {
        Operation op = normalize("variable_count(--LNKGRP)");
        assertEquals("variable_count", op.getOperator());
        assertEquals("--LNKGRP", op.getName());
        assertEquals("$RES", op.getId());
        assertNull(op.getExpression());
    }


    @Test
    void equalsHandWrittenFieldForm()
    {
        Operation expected = new Operation();
        expected.setId("$RES");
        expected.setOperator("variable_value_count");
        expected.setName("--LNKGRP");
        assertEquals(expected, normalize("variable_value_count(--LNKGRP)"));
    }


    @Test
    void filterMarkerBecomesMap()
    {
        Operation op = normalize("record_count(filter=filter(TSPARMCD=\"INDIC\", TSVALNF=\"NA\"))");
        assertEquals("record_count", op.getOperator());
        assertEquals(Map.of("TSPARMCD", "INDIC", "TSVALNF", "NA"), op.getFilter());
    }


    @Test
    void groupListBecomesList()
    {
        Operation op = normalize("record_count(group=[USUBJID, --TESTCD])");
        assertEquals(List.of("USUBJID", "--TESTCD"), op.getGroup());
    }


    @Test
    void valueIsReferenceBoolean()
    {
        Operation op = normalize("distinct(IDVAR, value_is_reference=true)");
        assertEquals("IDVAR", op.getName());
        assertEquals(true, op.getValueIsReference());
    }


    @Test
    void keyNameKeyValuePair()
    {
        Operation op = normalize(
                "get_dataset_filtered_variables(key_name=\"role\", key_value=\"Timing\")");
        assertEquals("role", op.getKeyName());
        assertEquals("Timing", op.getKeyValue());
    }


    @Test
    void namePatternAndNoPositionalName()
    {
        Operation op = normalize("variable_count(name_pattern=\".+FL$\")");
        assertNull(op.getName());
        assertEquals(".+FL$", op.getNamePattern());
    }


    @Test
    void codelistsList()
    {
        Operation op = normalize("codelist_terms(codelists=[DOMAIN])");
        assertEquals(List.of("DOMAIN"), op.getCodelists());
    }


    @Test
    void constantStringLiteralName()
    {
        Operation op = normalize("constant(\"Y\")");
        assertEquals("Y", op.getName());
    }


    @Test
    void unknownFunctionRejected()
    {
        RuleDefinitionException ex = assertThrows(RuleDefinitionException.class,
                () -> normalize("not_an_operation(X)"));
        assertTrue(ex.getMessage().contains("unknown operation function"));
    }


    @Test
    void unknownKwargRejected()
    {
        RuleDefinitionException ex = assertThrows(RuleDefinitionException.class,
                () -> normalize("variable_count(bogus=\"x\")"));
        assertTrue(ex.getMessage().contains("unknown argument"));
    }


    @Test
    void filterMustBeMarkerCall()
    {
        assertThrows(RuleDefinitionException.class, () -> normalize("record_count(filter=[A, B])"));
    }


    @Test
    void multiplePositionalArgsRejected()
    {
        assertThrows(RuleDefinitionException.class, () -> normalize("max(A, B)"));
    }


    @Test
    void valueIsReferenceMustBeBoolean()
    {
        assertThrows(RuleDefinitionException.class,
                () -> normalize("distinct(IDVAR, value_is_reference=\"yes\")"));
    }


    @Test
    void bothExpressionAndOperatorRejected()
    {
        Operation op = new Operation();
        op.setId("$X");
        op.setOperator("variable_count");
        op.setExpression("variable_count(--LNKGRP)");
        RuleDefinitionException ex = assertThrows(RuleDefinitionException.class,
                () -> OperationExpressionParser.normalize(op));
        assertTrue(ex.getMessage().contains("both"));
    }


    @Test
    void notASingleCallRejected()
    {
        assertThrows(RuleDefinitionException.class, () -> normalize("$A < 2"));
    }


    @Test
    void syntacticallyInvalidExpressionRejected()
    {
        assertThrows(RuleDefinitionException.class, () -> normalize("variable_count(--LNKGRP"));
    }

}
