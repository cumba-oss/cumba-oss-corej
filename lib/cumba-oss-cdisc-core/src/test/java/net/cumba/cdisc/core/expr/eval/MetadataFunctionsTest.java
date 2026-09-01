package net.cumba.cdisc.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.BitSet;
import java.util.Map;
import net.cumba.cdisc.core.exec.EvaluationContext;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.core.exec.MockTable;
import net.cumba.cdisc.core.expr.CheckExpressionParser;
import net.cumba.cdisc.core.expr.RuleDefinitionException;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 — the {@code var_*} / {@code ds_*} metadata accessors evaluated end-to-end through the
 * native backend: DATA / DEFINE / LIBRARY sourcing, normalization, the missing contract, and the
 * compile-time {@link RuleDefinitionException} for an unsupported {@code (attribute, level)} pair.
 */
class MetadataFunctionsTest
{

    /** A one-row AE table with column metadata; AESEQ numeric, AETERM character. */
    private static IDataTable aeTable()
    {
        return MockTable.of().name("AE").label("Adverse Events").colLong("AESEQ", 1L)
                .col("AETERM", "HEADACHE").colMeta("AESEQ", "Sequence Number", 8, "8.")
                .colMeta("AETERM", "Reported Term", 200, null).build();
    }


    private static MetadataProvider defineProvider()
    {
        MetadataProvider p = mock(MetadataProvider.class);
        lenient().when(p.getVariableMetadata("AE", "AESEV"))
                .thenReturn(Map.of("label", "Severity", "simpleDatatype", "text", "role",
                        "identifier", "core", "Req", "mandatory", "Yes", "codelist", "C66769",
                        "ordinal", "5", "format", "$8.", "length", "20"));
        lenient().when(p.getDatasetMetadata("AE"))
                .thenReturn(Map.of("name", "AE", "label", "Adverse Events", "className", "Events",
                        "datasetStructure", "One record per event"));
        return p;
    }


    private static EvaluationContext ctx(MetadataProvider define, MetadataProvider library)
    {
        return EvaluationContext.builder().table(aeTable()).domainName("AE").className("EVENTS")
                .variables(Map.of("variable_name", "AESEV")).defineProvider(define)
                .libraryProvider(library).build();
    }


    private static BitSet eval(String expr, EvaluationContext ctx)
    {
        return NativeExprEvaluator.evaluate(CheckExpressionParser.parse(expr), ctx);
    }


    private static BitSet bits(int... set)
    {
        BitSet b = new BitSet();
        for (int i : set)
        {
            b.set(i);
        }
        return b;
    }

    // -- DATA level ------------------------------------------------------------


    @Test
    void dataLevelVariableAndDatasetAttributes()
    {
        EvaluationContext ctx = ctx(null, null);
        assertEquals(bits(0), eval("var_name(\"AETERM\", \"DATA\") == \"AETERM\"", ctx));
        assertEquals(bits(0), eval("var_label(\"AETERM\", \"DATA\") == \"Reported Term\"", ctx));
        assertEquals(bits(0), eval("var_type(\"AETERM\", \"DATA\") == \"Char\"", ctx));
        assertEquals(bits(0), eval("var_type(\"AESEQ\", \"DATA\") == \"Num\"", ctx));
        assertEquals(bits(0), eval("var_length(\"AESEQ\", \"DATA\") == \"8\"", ctx));
        assertEquals(bits(0), eval("var_format(\"AESEQ\", \"DATA\") == \"8.\"", ctx));
        // MockTable orders String columns before numeric ones: AETERM is index 0, AESEQ index 1.
        assertEquals(bits(0), eval("var_ordinal(\"AETERM\", \"DATA\") == \"0\"", ctx));
        assertEquals(bits(0), eval("var_ordinal(\"AESEQ\", \"DATA\") == \"1\"", ctx));
        assertEquals(bits(0), eval("ds_name(\"DATA\") == \"AE\"", ctx));
        assertEquals(bits(0), eval("ds_label(\"DATA\") == \"Adverse Events\"", ctx));
        assertEquals(bits(0), eval("ds_class(\"DATA\") == \"EVENTS\"", ctx));
    }


    @Test
    void missingColumnIsMissingNoViolation()
    {
        EvaluationContext ctx = ctx(null, null);
        assertEquals(bits(), eval("var_label(\"NOSUCH\", \"DATA\") == \"x\"", ctx));
    }

    // -- DEFINE / LIBRARY levels + normalization -------------------------------


    @Test
    void defineLevelReadsAndNormalizes()
    {
        EvaluationContext ctx = ctx(defineProvider(), null);
        assertEquals(bits(0), eval("var_label(\"AESEV\", \"DEFINE\") == \"Severity\"", ctx));
        // ODM datatype "text" normalizes to Char.
        assertEquals(bits(0), eval("var_type(\"AESEV\", \"DEFINE\") == \"Char\"", ctx));
        // role "identifier" title-cases to Identifier.
        assertEquals(bits(0), eval("var_role(\"AESEV\", \"DEFINE\") == \"Identifier\"", ctx));
        assertEquals(bits(0), eval("var_core(\"AESEV\", \"DEFINE\") == \"Req\"", ctx));
        assertEquals(bits(0), eval("var_mandatory(\"AESEV\", \"DEFINE\") == \"Yes\"", ctx));
        assertEquals(bits(0), eval("var_codelist(\"AESEV\", \"DEFINE\") == \"C66769\"", ctx));
    }


    @Test
    void datasetAttributesFromProvider()
    {
        EvaluationContext ctx = ctx(defineProvider(), null);
        // 2-arg named dataset
        assertEquals(bits(0), eval("ds_class(\"AE\", \"DEFINE\") == \"Events\"", ctx));
        assertEquals(bits(0),
                eval("ds_structure(\"AE\", \"DEFINE\") == \"One record per event\"", ctx));
        // 1-arg uses the current dataset (domainName = AE)
        assertEquals(bits(0), eval("ds_label(\"DEFINE\") == \"Adverse Events\"", ctx));
    }


    @Test
    void variableNameOperandResolvesTheCurrentColumn()
    {
        EvaluationContext ctx = ctx(defineProvider(), null);
        // variable_name resolves to "AESEV" from the context variables.
        assertEquals(bits(0), eval("var_label(variable_name, \"DEFINE\") == \"Severity\"", ctx));
    }


    @Test
    void levelOnlyOverloadResolvesTheCurrentVariable()
    {
        // change #6: var_<attr>(level) defaults the name to the current variable (variable_name =
        // "AESEV"), so it resolves identically to var_<attr>(variable_name, level).
        EvaluationContext ctx = ctx(defineProvider(), null);
        assertEquals(bits(0), eval("var_label(\"DEFINE\") == \"Severity\"", ctx));
        assertEquals(bits(0), eval("var_core(\"DEFINE\") == \"Req\"", ctx));
        // an explicit varname() name resolves the current variable the same way
        assertEquals(bits(0), eval("var_label(varname(), \"DEFINE\") == \"Severity\"", ctx));
    }


    @Test
    void providerAbsentIsMissing()
    {
        // No define provider configured -> the value is missing -> the comparison does not fire.
        EvaluationContext ctx = ctx(null, null);
        assertEquals(bits(), eval("var_label(\"AESEV\", \"DEFINE\") == \"Severity\"", ctx));
    }

    // -- compile-time rule-definition errors -----------------------------------


    @Test
    void unsupportedAttributeLevelIsRuleDefinitionError()
    {
        EvaluationContext ctx = ctx(defineProvider(), null);
        // role / core have no DATA cell; format has no LIBRARY cell.
        assertThrows(RuleDefinitionException.class,
                () -> eval("var_role(\"AESEV\", \"DATA\") == \"x\"", ctx));
        assertThrows(RuleDefinitionException.class,
                () -> eval("var_format(\"AESEV\", \"LIBRARY\") == \"x\"", ctx));
    }


    @Test
    void unknownLevelOrBadShapeIsRuleDefinitionError()
    {
        EvaluationContext ctx = ctx(defineProvider(), null);
        assertThrows(RuleDefinitionException.class,
                () -> eval("var_label(\"AESEV\", \"FOO\") == \"x\"", ctx));
        // arity-1 now means (level); "AESEV" is not a valid level, so it is still a rule error
        assertThrows(RuleDefinitionException.class,
                () -> eval("var_label(\"AESEV\") == \"x\"", ctx));
        // too many arguments for a variable accessor
        assertThrows(RuleDefinitionException.class,
                () -> eval("var_label(\"AESEV\", \"DATA\", \"extra\") == \"x\"", ctx));
        // name operand that is neither a string literal nor the current-variable form
        assertThrows(RuleDefinitionException.class,
                () -> eval("var_label(AETERM, \"DATA\") == \"x\"", ctx));
        // level must be a string literal, not a bare reference
        assertThrows(RuleDefinitionException.class,
                () -> eval("var_label(\"AESEV\", DEFINE) == \"x\"", ctx));
    }

}
