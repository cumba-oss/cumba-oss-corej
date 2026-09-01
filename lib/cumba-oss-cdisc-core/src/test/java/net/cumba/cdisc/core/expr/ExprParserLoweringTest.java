package net.cumba.cdisc.core.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.cumba.cdisc.core.model.CheckCondition;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionAny;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.CheckConditionNot;
import org.junit.jupiter.api.Test;

class ExprParserLoweringTest
{

    private static CheckCondition lower(String s)
    {
        return ExprLowering.toCheckCondition(CheckExpressionParser.parse(s));
    }


    private static CheckConditionLeaf leaf(String s)
    {
        return assertInstanceOf(CheckConditionLeaf.class, lower(s));
    }


    @Test
    void equalityLiteral()
    {
        CheckConditionLeaf l = leaf("DTHFL == \"Y\"");
        assertEquals("DTHFL", l.getName());
        assertEquals("equal_to", l.getOperator());
        assertEquals("Y", l.getValue().asText());
        assertEquals(true, l.getValueIsLiteral());
    }


    @Test
    void inequalityReference()
    {
        CheckConditionLeaf l = leaf("DSSTDTC != DM.DTHDTC");
        assertEquals("DSSTDTC", l.getName());
        assertEquals("not_equal_to", l.getOperator());
        assertEquals("DM.DTHDTC", l.getValue().asText());
        // A reference operand must NOT be flagged literal (engine defaults textual to reference).
        assertNull(l.getValueIsLiteral());
    }


    @Test
    void dateComparisonSelectsDateFamily()
    {
        CheckConditionLeaf l = leaf("date(MHSTDTC) >= date(DM.RFSTDTC)");
        assertEquals("MHSTDTC", l.getName());
        assertEquals("date_greater_than_or_equal_to", l.getOperator());
        assertEquals("DM.RFSTDTC", l.getValue().asText());
        assertNull(l.getValueIsLiteral());
    }


    @Test
    void numericComparison()
    {
        CheckConditionLeaf l = leaf("AGE > 5");
        assertEquals("greater_than", l.getOperator());
        assertEquals(5, l.getValue().asInt());
    }


    @Test
    void lengthComparison()
    {
        CheckConditionLeaf l = leaf("len(variable_label) > 40");
        assertEquals("variable_label", l.getName());
        assertEquals("longer_than", l.getOperator());
        assertEquals(40, l.getValue().asInt());
    }


    @Test
    void regexMatch()
    {
        CheckConditionLeaf l = leaf("dataset_name =~ /^AD.*$/");
        assertEquals("dataset_name", l.getName());
        assertEquals("matches_regex", l.getOperator());
        assertEquals("^AD.*$", l.getValue().asText());
        assertEquals(true, l.getValueIsLiteral());
    }


    @Test
    void regexNonMatch()
    {
        assertEquals("not_matches_regex", leaf("DOMAIN !~ /^A/").getOperator());
    }


    @Test
    void membershipLiteralList()
    {
        CheckConditionLeaf l = leaf("DTHFL in [\"Y\", \"\"]");
        assertEquals("is_contained_by", l.getOperator());
        assertTrue(l.getValue().isArray());
        assertEquals(2, l.getValue().size());
        assertEquals("Y", l.getValue().get(0).asText());
    }


    @Test
    void notInOperationReference()
    {
        CheckConditionLeaf l = leaf("VISITNUM not in $tv_visitnum");
        assertEquals("is_not_contained_by", l.getOperator());
        assertEquals("$tv_visitnum", l.getValue().asText());
        assertNull(l.getValueIsLiteral());
    }


    @Test
    void existsPredicate()
    {
        CheckConditionLeaf l = leaf("var_exists(AEOCCUR)");
        assertEquals("AEOCCUR", l.getName());
        assertEquals("var_exists", l.getOperator());
    }


    @Test
    void notExistsWrapsInNot()
    {
        CheckCondition c = lower("not var_exists(TRLOC)");
        CheckConditionNot not = assertInstanceOf(CheckConditionNot.class, c);
        CheckConditionLeaf inner = assertInstanceOf(CheckConditionLeaf.class, not.getCondition());
        assertEquals("var_exists", inner.getOperator());
    }


    @Test
    void substringPredicate()
    {
        CheckConditionLeaf l = leaf("contains(PARAM, \"mmHg\")");
        assertEquals("contains", l.getOperator());
        assertEquals("mmHg", l.getValue().asText());
        assertEquals(true, l.getValueIsLiteral());
    }


    @Test
    void equalsIgnoreCaseFunction()
    {
        CheckConditionLeaf l = leaf("equalsIgnoreCase(--CAT, --DECOD)");
        assertEquals("--CAT", l.getName());
        assertEquals("equal_to_case_insensitive", l.getOperator());
        assertEquals("--DECOD", l.getValue().asText());
        assertNull(l.getValueIsLiteral());
    }


    @Test
    void lowcaseBothSidesCollapses()
    {
        CheckConditionLeaf l = leaf("lowcase(ARM) == lowcase(ACTARM)");
        assertEquals("ARM", l.getName());
        assertEquals("equal_to_case_insensitive", l.getOperator());
        assertEquals("ACTARM", l.getValue().asText());
    }


    @Test
    void conjunction()
    {
        CheckCondition c = lower("IECAT == \"INCLUSION\" && IEORRES != \"N\"");
        CheckConditionAll all = assertInstanceOf(CheckConditionAll.class, c);
        assertEquals(2, all.getConditions().size());
        assertEquals("equal_to",
                assertInstanceOf(CheckConditionLeaf.class, all.getConditions().get(0))
                        .getOperator());
    }


    @Test
    void disjunction()
    {
        assertInstanceOf(CheckConditionAny.class, lower("A == \"1\" || B == \"2\""));
    }


    @Test
    void parenthesesGroupAcrossPrecedence()
    {
        CheckCondition c = lower("(A == \"1\" || B == \"2\") && C == \"3\"");
        CheckConditionAll all = assertInstanceOf(CheckConditionAll.class, c);
        assertInstanceOf(CheckConditionAny.class, all.getConditions().get(0));
    }


    @Test
    void oneSidedLowcaseRejected()
    {
        ExpressionException ex = assertThrows(ExpressionException.class,
                () -> lower("lowcase(A) == B"));
        assertTrue(ex.getMessage().contains("native evaluator"));
    }


    @Test
    void unknownFunctionRejected()
    {
        assertThrows(ExpressionException.class, () -> lower("frobnicate(X)"));
    }


    @Test
    void unknownBuiltinOperandRejectedAtParse()
    {
        assertThrows(ExpressionException.class, () -> lower("mystery_thing == \"X\""));
    }


    @Test
    void trailingInputRejected()
    {
        assertThrows(ExpressionException.class, () -> CheckExpressionParser.parse("A == \"X\" B"));
    }


    @Test
    void emptyExpressionRejected()
    {
        ExpressionException ex = assertThrows(ExpressionException.class,
                () -> CheckExpressionParser.parse(""));
        assertTrue(ex.getMessage().contains("must not be empty"));
    }


    @Test
    void blankExpressionRejected()
    {
        ExpressionException ex = assertThrows(ExpressionException.class,
                () -> CheckExpressionParser.parse("   "));
        assertTrue(ex.getMessage().contains("must not be empty"));
    }


    @Test
    void nullExpressionRejected()
    {
        ExpressionException ex = assertThrows(ExpressionException.class,
                () -> CheckExpressionParser.parse(null));
        assertTrue(ex.getMessage().contains("must not be empty"));
    }

}
