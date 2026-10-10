package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.RuleDefinitionException;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.ExprCompiler;
import net.cumba.corej.core.expr.eval.NativeExprEvaluator;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.dictionary.house.RuntimeDictionaryProvider;
import net.cumba.datatable.dictionary.house.ValueMapDictionary;
import org.junit.jupiter.api.Test;

/**
 * Runbook W1a fold, R5 review A-L3 / A-L4: the readers of a parameter read once per call
 * ({@code ListFunctionSupport.staticString(s)}, the dictionary functions' type and
 * {@code case_sensitive}) read its value through {@code Minus.rawOperand} — a constant's member
 * value, else row 0, and never row 0 of a zero-row table — and, at a parameter W1a BS1 widened to
 * computed values, refuse a computed value of the wrong type as the rule's ERROR (as {@code union}
 * refuses a scalar operand) where stage A does not type the call.
 */
class StaticParameterReadTest
{

    /** AE with no rows. */
    private static IDataTable empty()
    {
        return RealTables.of("AE").str("DOMAIN").str("AEDECOD").build();
    }


    private static IDataTable oneRow()
    {
        return RealTables.of("AE").str("DOMAIN", "AE").str("AEDECOD", "HEADACHE").build();
    }


    @Test
    void aZeroRowTableReadsAStaticParameterAsAbsentNeverRowZero() throws Exception
    {
        EvaluationContext ctx = EvaluationContext.builder().table(empty()).build();
        // a non-constant operand (the level seams refuse it at load; a direct evaluation does
        // not): the zero-row read is absent, never an out-of-range row 0
        Vector v = ExprCompiler.evaluateValueExpression(
                CheckExpressionParser.parse("extract_metadata(name=lower(DOMAIN))"), ctx);
        assertNotNull(v);
        RuntimeDictionaryProvider meddra = new RuntimeDictionaryProvider(
                Map.of("meddra", ValueMapDictionary.load(Paths.get("dictionaries/meddra.json"))));
        EvaluationContext dictionary = EvaluationContext.builder().table(empty())
                .dictionaryProvider(meddra).build();
        BitSet verdict = NativeExprEvaluator.evaluate(CheckExpressionParser.parse(
                "valid_external_dictionary_value(AEDECOD, external_dictionary_type=\"meddra\","
                        + " dictionary_term_type=\"PT\", case_sensitive=AEDECOD)"),
                dictionary);
        assertEquals(0, verdict.cardinality());
    }


    @Test
    void aWrongTypedComputedValueAtAWidenedStringParameterIsTheRulesError()
    {
        EvaluationContext ctx = EvaluationContext.builder().table(oneRow()).build();
        RuleDefinitionException e = assertThrows(RuleDefinitionException.class,
                () -> ExprCompiler.evaluateValueExpression(
                        CheckExpressionParser.parse("extract_metadata(name=1 + 1)"), ctx));
        assertTrue(e.getMessage().contains("argument 'name' of 'extract_metadata' takes a string"),
                e.getMessage());
        // control: a computed string is read
        assertNotNull(ExprCompiler.evaluateValueExpression(
                CheckExpressionParser.parse("extract_metadata(name=lower(\"DATASET_NAME\"))"),
                ctx));
    }


    @Test
    void aComputedScalarAtAWidenedListParameterIsTheRulesError()
    {
        RuleDefinitionException e = assertThrows(RuleDefinitionException.class,
                () -> ListFunctionSupport.staticStrings(ConstVector.of("A"), 1,
                        LibraryLists.CODELIST_TERMS, LibraryLists.CODELISTS_PARAMETER));
        assertTrue(
                e.getMessage().contains(
                        "argument 'codelists' of 'codelist_terms' takes a list of strings"),
                e.getMessage());
        // controls: a list is read, an absent one is empty, and the literal-only reader is untyped
        assertEquals(List.of("A", "B"),
                ListFunctionSupport.staticStrings(ConstVector.of(List.of("A", "B")), 1,
                        LibraryLists.CODELIST_TERMS, LibraryLists.CODELISTS_PARAMETER));
        assertEquals(List.of(), ListFunctionSupport.staticStrings(null, 1,
                LibraryLists.CODELIST_TERMS, LibraryLists.CODELISTS_PARAMETER));
        assertEquals(List.of(), ListFunctionSupport.staticStrings(ConstVector.of(List.of()), 0));
    }
}
