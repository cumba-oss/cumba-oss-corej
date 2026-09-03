package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.BitSet;
import java.util.Map;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.RuleDefinitionException;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Verdict pins for {@link ExprCompiler}'s broadcast metadata / presence plans:
 * {@code var_*(dataset=)} cross-dataset reads and the §9.D keyword validation, {@code var_is_null},
 * {@code max_value_length}, the {@code varname()} cursor form of {@code var_exists}, and the
 * compile-time rejection of malformed {@code ds_exists} names. The keyword-validation cases pin
 * behaviour PIT reports as uncovered ({@code metadataPlan}'s kwarg guards): a mutant that lets a
 * bad keyword through silently reads the wrong metadata source for a correct rule.
 */
class ExprCompilerMetadataExistsPlansTest
{

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


    /** A one-row AE table; DM resolvable through the DatasetResolver with column metadata. */
    private static EvaluationContext aeWithDm()
    {
        IDataTable ae = MockTable.of().name("AE").col("AETERM", "HEADACHE").build();
        IDataTable dm = MockTable.of().name("DM").col("AGE", "34").colMeta("AGE", "Age", 8, "3.")
                .build();
        DatasetResolver resolver = name -> "DM".equals(name) ? dm : null;
        return EvaluationContext.builder().table(ae).domainName("AE").datasetResolver(resolver)
                .build();
    }

    // ---- var_*(dataset=) cross-dataset DATA reads -----------------------------


    @Test
    void datasetKeywordReadsTheForeignVariablesMetadata()
    {
        EvaluationContext c = aeWithDm();
        assertEquals(bits(0), eval("var_label(\"AGE\", \"DATA\", dataset=\"DM\") == \"Age\"", c),
                "dataset=\"DM\" must read AGE's label from DM, not from AE");
        assertEquals(bits(), eval("var_label(\"AGE\", \"DATA\", dataset=\"DM\") == \"Wrong\"", c),
                "the foreign label must compare as its real value, not fire vacuously");
        assertEquals(bits(), eval("var_label(\"AGE\", \"DATA\") == \"Age\"", c),
                "without dataset= the read is against AE, where AGE does not exist");
    }


    @Test
    void metadataKeywordValidationRejectsEveryBadShape()
    {
        EvaluationContext c = aeWithDm();
        assertThrows(RuleDefinitionException.class,
                () -> eval("var_label(\"AGE\", \"DATA\", bogus=\"DM\") == \"Age\"", c),
                "an unknown keyword must be a rule-definition error");
        assertThrows(RuleDefinitionException.class,
                () -> eval("ds_label(\"DATA\", dataset=\"DM\") == \"x\"", c),
                "dataset= on a DATASET-scope accessor must be rejected");
        assertThrows(RuleDefinitionException.class,
                () -> eval("var_label(\"AGE\", \"DEFINE\", dataset=\"DM\") == \"Age\"", c),
                "dataset= is DATA-level only");
        assertThrows(RuleDefinitionException.class,
                () -> eval("var_role(\"AGE\", \"DATA\", dataset=\"DM\") == \"x\"", c),
                "dataset= is only for the four cross_dataset_variable_metadata fields");
        assertThrows(RuleDefinitionException.class,
                () -> eval("var_label(\"A\", \"B\", \"DATA\") == \"x\"", c),
                "a third positional operand must be rejected");
    }

    // ---- var_is_null ------------------------------------------------------------


    @Test
    void varIsNullBroadcastVerdicts()
    {
        IDataTable t = MockTable.of().name("AE").col("P", "x", "").col("E", "", "").build();
        EvaluationContext c = EvaluationContext.builder().table(t).build();
        assertEquals(bits(0, 1), eval("var_is_null(\"E\")", c),
                "an all-blank column is null — every row fires");
        assertEquals(bits(), eval("var_is_null(\"P\")", c),
                "one populated value means NOT null — no row fires");
        assertEquals(bits(0, 1), eval("var_is_null(\"ZZ\")", c),
                "an absent column is null by definition");
        assertEquals(bits(), eval("not var_is_null(\"E\")", c),
                "the negation is the exact complement");
    }


    @Test
    void varIsNullResolvesTheVarnameCursor()
    {
        IDataTable t = MockTable.of().name("AE").col("P", "x").col("E", "").build();
        EvaluationContext empty = EvaluationContext.builder().table(t)
                .variables(Map.of("variable_name", "E")).build();
        EvaluationContext populated = EvaluationContext.builder().table(t)
                .variables(Map.of("variable_name", "P")).build();
        assertEquals(bits(0), eval("var_is_null(varname())", empty),
                "the cursor must resolve to the CURRENT variable (all-blank E)");
        assertEquals(bits(), eval("var_is_null(varname())", populated),
                "the same expression over a populated cursor must not fire");
    }

    // ---- max_value_length ----------------------------------------------------------


    @Test
    void maxValueLengthBroadcastsTheColumnsMaxLength()
    {
        IDataTable t = MockTable.of().name("AE").col("M", "ab", "abcd", "").build();
        EvaluationContext c = EvaluationContext.builder().table(t)
                .variables(Map.of("variable_name", "M")).build();
        assertEquals(bits(0, 1, 2), eval("max_value_length(\"M\") == \"4\"", c),
                "the max non-missing length (4) must broadcast to every row");
        assertEquals(bits(), eval("max_value_length(\"M\") == \"3\"", c),
                "a wrong length must not fire — the value is real, not vacuous");
        assertEquals(bits(0, 1, 2), eval("max_value_length(varname()) == \"4\"", c),
                "the cursor form must read the same column");
    }

    // ---- var_exists(varname()) cursor form --------------------------------------------


    @Test
    void varExistsCursorFormReadsTheCurrentVariable()
    {
        IDataTable t = MockTable.of().name("AE").col("AETERM", "x", "y").build();
        EvaluationContext present = EvaluationContext.builder().table(t)
                .variables(Map.of("variable_name", "AETERM")).build();
        EvaluationContext absent = EvaluationContext.builder().table(t)
                .variables(Map.of("variable_name", "NOSUCH")).build();
        assertEquals(bits(0, 1), eval("var_exists(varname())", present),
                "the cursor variable exists — broadcast fire");
        assertEquals(bits(), eval("var_exists(varname())", absent),
                "an absent cursor variable must not fire");
        assertEquals(bits(0, 1), eval("var_not_exists(varname())", absent),
                "the negated twin fires on the absent cursor variable");
        assertEquals(bits(), eval("var_not_exists(varname())", present),
                "the negated twin must not fire on a present cursor variable");
    }

    // ---- malformed ds_exists names are compile-time errors --------------------------------


    @Test
    void dsExistsRejectsNonPlainDatasetNames()
    {
        IDataTable t = MockTable.of().name("AE").col("AETERM", "x").build();
        EvaluationContext c = EvaluationContext.builder().table(t).build();
        assertThrows(ExpressionException.class, () -> eval("ds_exists(\"DM.AGE\")", c),
                "a dotted name is not a dataset-presence test");
        assertThrows(ExpressionException.class, () -> eval("ds_exists(\".DM\")", c),
                "a LEADING dot must be rejected too (index-0 boundary)");
        assertThrows(ExpressionException.class, () -> eval("ds_exists(\"=DM\")", c),
                "a LEADING equals sign must be rejected too (index-0 boundary)");
        assertThrows(ExpressionException.class, () -> eval("ds_exists(\"SUPP--\")", c),
                "a --prefix has no dataset-presence meaning");
    }

    // ---- misc boolean builtins with kwargs ---------------------------------------------------


    @Test
    void invalidDurationHonoursTheNegativeKwarg()
    {
        IDataTable t = MockTable.of().name("AE").col("DUR", "-P1D", "P1D", "xx").build();
        EvaluationContext c = EvaluationContext.builder().table(t).build();
        assertEquals(bits(2), eval("invalid_duration(DUR)", c),
                "default negative handling accepts a signed duration — only the garbage fires");
        assertEquals(bits(0, 2), eval("invalid_duration(DUR, negative=false)", c),
                "negative=false must reject the signed duration as well");
        assertEquals(bits(2), eval("invalid_duration(DUR, negative=true)", c),
                "negative=true is the explicit spelling of the default");
    }


    @Test
    void lengthEqualityOperatorsFoldMissingToLengthZero()
    {
        // A missing cell folds to length 0 (the len()-comparison contract CheckToExpr lowers
        // these operators to), so the not-equal polarity FIRES on a blank while the equal one
        // does not. ⚠ compileLengthEquality's javadoc claims "missing ⇒ no violation on both
        // polarities" — the shipped verdict below is the real contract on both engines.
        IDataTable t = MockTable.of().name("AE").col("L", "ab", "abc", "").build();
        EvaluationContext c = EvaluationContext.builder().table(t).build();
        assertEquals(bits(1, 2), eval("has_not_equal_length(L, 2)", c),
                "wrong-length cells fire, and a missing cell counts as length 0");
        assertEquals(bits(0), eval("has_equal_length(L, 2)", c),
                "only a populated cell of the right length fires");
        assertEquals(bits(2), eval("has_equal_length(L, 0)", c),
                "length 0 matches exactly the missing cell — the fold is real, not vacuous");
    }


    @Test
    void doesNotEqualStringPartComparesTheExtractedGroup()
    {
        IDataTable t = MockTable.of().name("AE").col("PART", "01", "02").col("SRC", "X-01", "X-01")
                .build();
        EvaluationContext c = EvaluationContext.builder().table(t).build();
        // The regex must match the WHOLE source value; the verdict compares capture group 1.
        assertEquals(bits(1),
                eval("does_not_equal_string_part(PART, SRC, regex=\"X-([0-9]+)\")", c),
                "the verdict must compare against capture group 1, not the whole value");
        assertEquals(bits(), eval("does_not_equal_string_part(PART, SRC, regex=\"-([0-9]+)\")", c),
                "a regex that does not match the whole source makes no decision");
    }

}
