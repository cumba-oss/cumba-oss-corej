package net.cumba.corej.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.expr.BuiltinRegistry;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.MetadataOperandMapping;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.DatasetExpressionCache;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.expr.eval.FunctionRegistry;
import net.cumba.corej.core.expr.eval.MetadataAttribute;
import net.cumba.corej.core.expr.eval.MetadataLevel;
import net.cumba.corej.core.expr.eval.Parameter;
import net.cumba.corej.core.expr.typed.ExprType.Primitive;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-library-template-label-resolution} D6 (b) / D7 / D8 / D11 — the label-template
 * grammar, and the plumbing that makes the pair visible to a rule and to a finding.
 */
class LabelTemplateMatchTest
{

    @Test
    void fixedTextMatchesExactlyLetterCaseIncluded()
    {
        assertTrue(LibraryTemplates.matches("Study Identifier", "Study Identifier"));
        assertFalse(LibraryTemplates.matches("Study identifier", "Study Identifier"));
        assertFalse(LibraryTemplates.matches("Study Identifier ", "Study Identifier"),
                "no trimming: the exact-label rules compare exactly");
        assertFalse(LibraryTemplates.matches("Planned Treatment for Period 1",
                "Planned Treatment for Period 01"));
        assertTrue(LibraryTemplates.matches("", ""));
    }


    @Test
    void anEllipsisIsAnyTextIncludingNoneWithOptionalWhitespace()
    {
        // D8 as D11: the descriptive tail of a y / zz name
        assertTrue(LibraryTemplates.matches("Analysis Category 1", "Analysis Category 1 ..."));
        assertTrue(LibraryTemplates.matches("Analysis Category 1 (Low/Normal/High)",
                "Analysis Category 1 ..."));
        assertFalse(LibraryTemplates.matches("Category 1 Analysis", "Analysis Category 1 ..."));
        // D11: the IG's own ellipsis, ADaMIG 1.0 stems and OCCDS AOCCzzFL
        assertTrue(LibraryTemplates.matches("Start Date of First Dose", "Start Date of ..."));
        assertTrue(LibraryTemplates.matches("Start Date of", "Start Date of ..."));
        assertFalse(LibraryTemplates.matches("First Dose Start Date", "Start Date of ..."));
        assertTrue(
                LibraryTemplates.matches("1st Occurrence of Any AE Flag", "1st Occurrence of ..."));
        // fixed text after the ellipsis binds too
        assertTrue(
                LibraryTemplates.matches("Pooled Site Group 1 Code", "Pooled Site Group ... Code"));
        assertFalse(LibraryTemplates.matches("Pooled Site Group 1", "Pooled Site Group ... Code"));
    }


    /**
     * T2 review M1 / L5: free text glued to a letter or digit of the fixed text is not free text —
     * it changes the word or the index the fixed text ends in. {@code Analysis Value Category 12}
     * is AVALCAT12's label, not AVALCAT1's with a descriptive tail (D8: the IG allows descriptive
     * text AT THE END of the label, i.e. after it).
     */
    @Test
    void anEllipsisBesideALetterOrDigitNeedsABoundary()
    {
        assertFalse(LibraryTemplates.matches("Analysis Value Category 12",
                "Analysis Value Category 1 ..."), "a glued digit is another index");
        assertFalse(LibraryTemplates.matches("Analysis Flag 012", "Analysis Flag 01 ..."));
        assertFalse(LibraryTemplates.matches("Analysis Flag 01a", "Analysis Flag 01 ..."),
                "a glued letter too");
        assertFalse(LibraryTemplates.matches("Start Date ofFirst Dose", "Start Date of ..."));
        // the boundary is any non-alphanumeric character, not only a space
        assertTrue(LibraryTemplates.matches("Analysis Value Category 1",
                "Analysis Value Category 1 ..."));
        assertTrue(LibraryTemplates.matches("Analysis Value Category 1 (Low/High)",
                "Analysis Value Category 1 ..."));
        assertTrue(LibraryTemplates.matches("Analysis Flag 01, Primary", "Analysis Flag 01 ..."));
        assertTrue(LibraryTemplates.matches("Analysis Flag 01-Primary", "Analysis Flag 01 ..."));
        // an inner ellipsis between two alphanumerics: separated on both sides, never glued
        assertFalse(
                LibraryTemplates.matches("Pooled Site Group1 Code", "Pooled Site Group ... Code"));
        assertFalse(
                LibraryTemplates.matches("Pooled Site Group 1Code", "Pooled Site Group ... Code"));
        assertFalse(
                LibraryTemplates.matches("Pooled Site GroupCode", "Pooled Site Group ... Code"));
        assertTrue(LibraryTemplates.matches("Pooled Site Group Code", "Pooled Site Group ... Code"),
                "the empty free text, its two separators collapsed into one");
        // a leading ellipsis before an alphanumeric
        assertFalse(LibraryTemplates.matches("XFlag", "... Flag"));
        assertTrue(LibraryTemplates.matches("Any Flag", "... Flag"));
        assertTrue(LibraryTemplates.matches("Flag", "... Flag"));
        // fixed text that ends in a non-alphanumeric needs no extra boundary
        assertTrue(LibraryTemplates.matches("Duration (N)x", "Duration (N) ..."));
    }


    @Test
    void aBracePhraseMustOccurSomewhereAndTheLabelIsOtherwiseFree()
    {
        assertTrue(LibraryTemplates.matches("Analysis Start Date", "{Start Date}"));
        assertTrue(LibraryTemplates.matches("Start Date of Rescue Med", "{Start Date}"),
                "ADaMIG 1.3's own example");
        assertTrue(LibraryTemplates.matches("Rescue Med Start Date", "{Start Date}"));
        assertFalse(LibraryTemplates.matches("Start of Rescue Med Date", "{Start Date}"),
                "the phrase, contiguous");
        assertFalse(LibraryTemplates.matches("start date", "{Start Date}"), "letter case");
        assertTrue(LibraryTemplates.matches("B then A", "{A} and {B}"), "every phrase, any order");
        assertFalse(LibraryTemplates.matches("only A", "{A} and {B}"));
        assertEquals(List.of("Start Date Imputation Flag"),
                LibraryTemplates.bracePhrases("{Start Date Imputation Flag} ..."));
    }


    @Test
    void imputIsImputationOnBothSidesAndNothingElseIsAbbreviated()
    {
        // D7: the IG itself publishes "Imput." (24 labels, metadata issue #97)
        assertTrue(LibraryTemplates.matches("Date of First Exposure Imputation Flag",
                "Date of First Exposure Imput. Flag"));
        assertTrue(LibraryTemplates.matches("Date of First Exposure Imput. Flag",
                "Date of First Exposure Imputation Flag"));
        assertTrue(LibraryTemplates.matches("Analysis Start Date Imput. Flag",
                "{Start Date Imputation Flag}"));
        assertFalse(LibraryTemplates.matches("Date of First Exposure Imp. Flag",
                "Date of First Exposure Imput. Flag"), "no other abbreviation");
        assertEquals("Imputation Flag", LibraryTemplates.normaliseImputation("Imput. Flag"));
    }


    @Test
    void theAccessorTheFunctionAndTheOperandAreWiredTogether()
    {
        MetadataAttribute attr = MetadataAttribute.fromFunction("label_template");
        assertNotNull(attr);
        assertEquals(LibraryTemplates.LABEL_TEMPLATE_KEY, attr.providerKey());
        assertTrue(attr.supports(MetadataLevel.LIBRARY));
        assertFalse(attr.supports(MetadataLevel.DATA));
        assertFalse(attr.supports(MetadataLevel.DEFINE));
        FunctionDescriptor matcher = FunctionRegistry.descriptor("matches_label_template");
        assertNotNull(matcher);
        assertEquals(List.of("label", "template"),
                matcher.parameters().stream().map(Parameter::name).toList());
        assertEquals(List.of(Primitive.STRING, Primitive.STRING),
                matcher.parameters().stream().map(Parameter::type).toList());
        assertTrue(BuiltinRegistry.isBuiltin("library_variable_label_template"),
                "D6 (b): the template is visible in Output_Variables");
        Expr forwarded = MetadataOperandMapping.forwardOperand("library_variable_label_template");
        assertEquals(CheckExpressionParser.parse("label_template(variable_name, \"LIBRARY\")"),
                forwarded, "the operand reads the accessor at LIBRARY");
        // and back: the classifier names the accessor's operand through this reverse mapping, so
        // a rule reading label_template stays a per-variable LIBRARY check. Stripping a var_
        // prefix the name does not have produced `library_variable_el_template` — unplaceable —
        // and every rewritten label rule executed with zero findings.
        assertEquals("library_variable_label_template", MetadataOperandMapping.reverseToOperand(
                CheckExpressionParser.parse("label_template(variable_name, \"LIBRARY\")")));
        assertEquals("library_variable_label_template", MetadataOperandMapping
                .reverseToOperand(CheckExpressionParser.parse("label_template(\"LIBRARY\")")));
        assertTrue(
                DatasetExpressionCache.isPure(
                        CheckExpressionParser.parse("matches_label_template(AELABEL, \"x\")")),
                "review L2: pure");
    }
}
