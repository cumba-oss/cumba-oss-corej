package net.cumba.corej.core.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.CheckConditionNot;
import org.junit.jupiter.api.Test;

/**
 * {@link ExprLowering#toCheckConditionForConversion(Expr)} — the offline mode that restores the
 * legacy-representable form before lowering (R1: the regex&rarr;predicate table read backwards; R2:
 * per-record accessor calls demoted to their bare metadata operands).
 *
 * <p>
 * ⚠⚠ Every case here is paired with the assertion that the <b>strict</b>
 * {@link ExprLowering#toCheckCondition(Expr)} still <em>rejects</em> it. That pairing is the point:
 * the strict lowering is the production load path ({@code CheckConditionDeserializer}), where a
 * rejection is what keeps the rule on the native evaluator. If conversion mode ever leaks into it,
 * these tests are what says so.
 * </p>
 */
class ExprLoweringConversionModeTest
{

    private static CheckConditionLeaf leafOf(String source)
    {
        CheckCondition c = ExprLowering
                .toCheckConditionForConversion(CheckExpressionParser.parse(source));
        if (c instanceof CheckConditionAll all && all.getConditions().size() == 1)
        {
            return (CheckConditionLeaf) all.getConditions().get(0);
        }
        return assertInstanceOf(CheckConditionLeaf.class, c);
    }


    private static void strictRejects(String source)
    {
        Expr e = CheckExpressionParser.parse(source);
        assertThrows(ExpressionException.class, () -> ExprLowering.toCheckCondition(e),
                "the STRICT (load-path) lowering must still reject: " + source);
    }


    @Test
    void isNumericRestoresThePositiveRegexLeaf()
    {
        strictRejects("is_numeric(LBSTRESC)");
        CheckConditionLeaf leaf = leafOf("is_numeric(LBSTRESC)");
        assertEquals("LBSTRESC", leaf.getName());
        assertEquals("matches_regex", leaf.getOperator());
        assertEquals("^-?(0|[1-9]\\d*)(\\.\\d+)?$", leaf.getValue().asText());
    }


    @Test
    void negatedIsNumericRestoresTheNegativeRegexLeaf()
    {
        strictRejects("not is_numeric(LBSTRESC)");
        CheckConditionLeaf leaf = leafOf("not is_numeric(LBSTRESC)");
        assertEquals("not_matches_regex", leaf.getOperator());
        assertEquals("^-?(0|[1-9]\\d*)(\\.\\d+)?$", leaf.getValue().asText());
    }


    @Test
    void theOtherRegexTablePredicatesRestoreTheirPatterns()
    {
        assertEquals(".*[a-zA-Z].*", leafOf("has_alpha(LBTOXGR)").getValue().asText());
        assertEquals(".*[0-9].*", leafOf("has_digit(LBTOXGR)").getValue().asText());
        assertEquals("^[A-Z_][A-Z0-9_]{0,7}$",
                leafOf("not is_valid_name(QNAM)").getValue().asText());
        assertEquals("^[a-zA-Z_][a-zA-Z0-9_]{0,7}$",
                leafOf("not is_valid_testcd(IETESTCD)").getValue().asText());
        strictRejects("has_alpha(LBTOXGR)");
        strictRejects("not is_valid_name(QNAM)");
    }


    @Test
    void theFirstLetterRangeTestRestoresItsRegex()
    {
        strictRejects("not between(char(PARAMCD), char(\"A\"), char(\"Z\"))");
        CheckConditionLeaf leaf = leafOf("not between(char(PARAMCD), char(\"A\"), char(\"Z\"))");
        assertEquals("PARAMCD", leaf.getName());
        assertEquals("not_matches_regex", leaf.getOperator());
        assertEquals("^[A-Z]", leaf.getValue().asText());
    }


    @Test
    void aBetweenOverOtherBoundsIsNotMistakenForTheFirstLetterTest()
    {
        // Only char("A")..char("Z") is the optimiser's shape; anything else has no regex
        // equivalent and must stay native-only in BOTH modes.
        Expr e = CheckExpressionParser.parse("not between(char(X), char(\"B\"), char(\"Z\"))");
        assertThrows(ExpressionException.class,
                () -> ExprLowering.toCheckConditionForConversion(e));
        Expr numeric = CheckExpressionParser.parse("not between(TSVAL, 0, 1)");
        assertThrows(ExpressionException.class,
                () -> ExprLowering.toCheckConditionForConversion(numeric));
    }


    @Test
    void vlmAccessorsDemoteToTheirAuthoredOperands()
    {
        strictRejects("vlm_mandatory(variable_name) == \"Yes\"");
        CheckConditionLeaf leaf = leafOf("vlm_mandatory(variable_name) == \"Yes\"");
        assertEquals("define_vlm_mandatory", leaf.getName());
        assertEquals("equal_to", leaf.getOperator());

        strictRejects("vlm_value_length(variable_name) > vlm_length(variable_name)");
        CheckConditionLeaf pair = leafOf(
                "vlm_value_length(variable_name) > vlm_length(" + "variable_name)");
        assertEquals("variable_value_length", pair.getName());
        assertEquals("greater_than", pair.getOperator());
        assertEquals("define_vlm_length", pair.getValue().asText());
    }


    @Test
    void maxValueLengthAndThePairedMatchersDemote()
    {
        strictRejects("var_length(\"DATA\") != max_value_length(variable_name)");
        CheckConditionLeaf leaf = leafOf("var_length(\"DATA\") != max_value_length(variable_name)");
        assertEquals("variable_max_size", leaf.getValue().asText());

        strictRejects("library_variable_code_pair_matches(variable_name) == false");
        assertEquals("library_variable_code_pair_matches",
                leafOf("library_variable_code_pair_matches(variable_name) == false").getName());
        assertEquals("define_variable_decode_matches",
                leafOf("define_variable_decode_matches(variable_name) == false").getName());
    }


    @Test
    void theCursorCallsDemoteSoTheAffixFamilyBecomesReachable()
    {
        // prefix(varname(), 2) is rejected strictly: ExprLowering.affixCall requires a bare Ref as
        // the affix subject. Demoting varname() to the variable_name operand makes it an ordinary
        // prefix_is_not_contained_by leaf — which is exactly what CDISC-CG0349 authored.
        strictRejects("prefix(varname(), 2) not in $domain_list");
        CheckConditionLeaf leaf = leafOf("prefix(varname(), 2) not in $domain_list");
        assertEquals("variable_name", leaf.getName());
        assertEquals("prefix_is_not_contained_by", leaf.getOperator());
        assertEquals(2, leaf.getPrefix());
        assertEquals("$domain_list", leaf.getValue().asText());
    }


    @Test
    void endsWithOnVarnameIsDeliberatelyNotReversed()
    {
        // P2: CheckToExpr.pureSuffix maps three fills (^.+SUF$ / ^.*SUF$ / SUF$) onto ONE call, and
        // all three are populated in rules-src. Restoring any one of them would corrupt the others,
        // so conversion mode must leave the ends_with leaf exactly as the strict mode produces it.
        CheckConditionLeaf leaf = leafOf("ends_with(varname(), \"FL\")");
        assertEquals("ends_with", leaf.getOperator());
        assertEquals("variable_name", leaf.getName());
        assertEquals("FL", leaf.getValue().asText());
    }


    @Test
    void aStructurallyNativeOnlyConstructIsStillRejectedInConversionMode()
    {
        Expr tuple = CheckExpressionParser.parse("tuple(USUBJID, VISIT) not in $keys");
        assertThrows(ExpressionException.class,
                () -> ExprLowering.toCheckConditionForConversion(tuple));
        Expr split = CheckExpressionParser
                .parse("not contains_all($terms, split_by(PPSPEC, \";\"))");
        assertThrows(ExpressionException.class,
                () -> ExprLowering.toCheckConditionForConversion(split));
    }


    @Test
    void restorationRecursesIntoNestedStructure()
    {
        CheckCondition c = ExprLowering.toCheckConditionForConversion(
                CheckExpressionParser.parse("not empty(QNAM) and not is_valid_name(QNAM)"));
        CheckConditionAll all = assertInstanceOf(CheckConditionAll.class, c);
        assertEquals(2, all.getConditions().size());
        assertEquals("not_matches_regex",
                ((CheckConditionLeaf) all.getConditions().get(1)).getOperator());

        // Inside an `or`, and inside a structural `not` that is NOT one of the table predicates.
        CheckCondition nested = ExprLowering.toCheckConditionForConversion(CheckExpressionParser
                .parse("is_numeric(A) or not (has_alpha(B) and has_digit(B))"));
        assertInstanceOf(net.cumba.corej.core.model.CheckConditionAny.class, nested);
        assertInstanceOf(CheckConditionNot.class,
                ((net.cumba.corej.core.model.CheckConditionAny) nested).getConditions().get(1));
    }


    @Test
    void anExpressionWithNothingToRestoreIsLoweredIdentically() throws Exception
    {
        ObjectMapper mapper = new ObjectMapper();
        String source = "not empty(AESEV) and AESEV != \"Y\" and AEDECOD in [\"A\", \"B\"]";
        Expr e = CheckExpressionParser.parse(source);
        assertEquals(mapper.writeValueAsString(ExprLowering.toCheckCondition(e)),
                mapper.writeValueAsString(ExprLowering.toCheckConditionForConversion(e)));
    }


    @Test
    void restoreLegacyFormsLeavesARefAloneAndRebuildsLists()
    {
        Expr ref = new Expr.Ref("AESEV", OperandKind.COLUMN);
        assertSame(ref, ExprLowering.restoreLegacyForms(ref));

        Expr list = CheckExpressionParser.parse("AESEV in [\"A\", \"B\"]");
        assertTrue(ExprLowering.restoreLegacyForms(list) instanceof Expr.Binary);
    }


    @Test
    void theLoadPathStillKeepsANativeOnlyExpressionNative() throws Exception
    {
        CheckCondition c = new ObjectMapper()
                .readValue("{\"expression\": \"is_numeric(LBSTRESC)\"}", CheckCondition.class);
        assertInstanceOf(CheckConditionExpression.class, c,
                "CheckConditionDeserializer must keep calling the STRICT lowering — otherwise "
                        + "shipped rules silently move off the native evaluator");
    }

}
