package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpressionPrinter;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.NameSlots;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-string-literal-prefix-rewrite} N4 / N7: a STRING literal is specialised as a name
 * <b>only at a name slot</b> (S3). A data literal, a data argument, a {@code regex=} value, a
 * {@code keys=} member and the member of a data LIST keep their spelling verbatim — before the plan
 * the shared text policy rewrote every one of them on its shape alone ({@code "DOSE NOT
 * CHANGED--SEE CRF."} became {@code "DOSE NOT CHANGEDAESEE CRF."} on an AE rule). Every marked name
 * slot still rewrites, the D92a / D92b exemptions still keep their template, and a
 * {@code $}-binding holding a name answers at a name slot as the name written there (S8, G1).
 */
class ExprPrefixResolverTest
{

    private static String resolved(String source)
    {
        return ExpressionPrinter
                .print(ExprPrefixResolver.resolve(CheckExpressionParser.parse(source), "AE", "AE"));
    }


    private static String printed(String source)
    {
        return ExpressionPrinter.print(CheckExpressionParser.parse(source));
    }


    /** The literal kept verbatim: the resolved expression prints as the source does. */
    private static void assertKept(String source)
    {
        assertEquals(printed(source), resolved(source), source);
    }

    // ------------------------------------------------------------------ data literals: kept


    @Test
    void aComparisonOperandKeepsItsLiteral()
    {
        assertKept("AEACN == \"DOSE NOT CHANGED--SEE CRF.\"");
        assertKept("AEACN != \"--SEQ\"");
        assertKept("AETERM == \"A**B\"");
    }


    @Test
    void aDataArgumentKeepsItsLiteral()
    {
        assertKept("does_not_equal_string_part(AETERM, \"A--B\", regex=\"(a)\")");
        assertKept("has_equal_length(AETERM, \"--X\")");
        assertKept("starts_with(AETERM, \"--X\")");
        assertKept("concat(\"--\", AETERM) == \"x\"");
    }


    @Test
    void aRegexValueKeepsItsLiteral()
    {
        assertKept("does_not_equal_string_part(AETERM, AEDECOD, regex=\"^(.*)\\\\.--\")");
        assertKept("is_unique_set([USUBJID, AETERM], regex=\"^(.*)\\\\.--\")");
        assertKept("record_count(group=[USUBJID], regex=\"^(.*)\\\\.--\") > 1");
    }


    @Test
    void aKeysMemberAndADataListMemberKeepTheirLiterals()
    {
        assertKept("not_contains_all(AETERM, keys=[\"--SEQ\", \"--X\"])");
        assertKept("AETERM in [\"--SEQ\", \"A.--B\"]");
    }


    @Test
    void theLevelOfAMetadataAccessorIsNotANameSlot()
    {
        // var_label(level): argument 0 of the ONE-argument form is the level (S6)
        assertKept("var_label(\"--X\") == \"x\"");
    }

    // ------------------------------------------------------------------ name slots: rewritten


    @Test
    void everyMarkedNameSlotStillRewrites()
    {
        assertEquals(printed("var_exists(\"AEX\")"), resolved("var_exists(\"--X\")"));
        assertEquals(printed("not var_not_exists(\"AEX\")"),
                resolved("not var_not_exists(\"--X\")"));
        assertEquals(printed("var_is_empty(\"AEX\")"), resolved("var_is_empty(\"--X\")"));
        assertEquals(printed("colref(\"AESEQ\") == 1"), resolved("colref(\"--SEQ\") == 1"));
        assertEquals(printed("colref([\"AESEQ\", \"AEX\"]) == 1"),
                resolved("colref([\"--SEQ\", \"--X\"]) == 1"), "S4: a LIST at a name slot");
        assertEquals(printed("max_value_length(\"AEX\") > 1"),
                resolved("max_value_length(\"--X\") > 1"));
        assertEquals(printed("vlm_length(\"AEX\") > 1"), resolved("vlm_length(\"--X\") > 1"));
        assertEquals(printed("var_label(\"AETRT\", \"DATA\") == \"x\""),
                resolved("var_label(\"--TRT\", \"DATA\") == \"x\""));
        assertEquals(
                printed("var_label(\"X\", \"DATA\", dataset=\"" + datasetHalf("SUPP--")
                        + "\") == \"x\""),
                resolved("var_label(\"X\", \"DATA\", dataset=\"SUPP--\") == \"x\""));
        assertEquals(printed("record_count(domain=\"" + datasetHalf("SUPP--") + "\") > 1"),
                resolved("record_count(domain=\"SUPP--\") > 1"));
        assertEquals(
                printed("cross_dataset_variable_metadata(\"label\", \"" + datasetHalf("SUPP--")
                        + "\") == \"x\""),
                resolved("cross_dataset_variable_metadata(\"label\", \"SUPP--\") == \"x\""));
    }


    /**
     * Planted control: the resolver asks {@link NameSlots} and nothing else — with
     * {@code var_exists} unmarked in the lookup it hands the resolver, the very literal the test
     * above sees rewritten stays verbatim, while {@code colref} (still marked) rewrites.
     */
    @Test
    void unmarkingASlotInTheLookupStopsItsRewrite()
    {
        NameSlots.Lookup withoutVarExists = new NameSlots.Lookup()
        {

            @Override
            public boolean isNameSlot(Expr.Call call, int position)
            {
                return !"var_exists".equals(call.name())
                        && NameSlots.REGISTRY.isNameSlot(call, position);
            }


            @Override
            public boolean isNameSlot(Expr.Call call, String keyword)
            {
                return !"var_exists".equals(call.name())
                        && NameSlots.REGISTRY.isNameSlot(call, keyword);
            }
        };
        Expr source = CheckExpressionParser.parse("var_exists(\"--X\") and colref(\"--X\") == 1");
        assertEquals(printed("var_exists(\"--X\") and colref(\"AEX\") == 1"), ExpressionPrinter
                .print(ExprPrefixResolver.resolve(source, "AE", "AE", withoutVarExists)));
        assertEquals(printed("var_exists(\"AEX\") and colref(\"AEX\") == 1"), ExpressionPrinter
                .print(ExprPrefixResolver.resolve(source, "AE", "AE", NameSlots.REGISTRY)));
    }


    /**
     * What the shared text policy makes of a literal at a name slot (the resolver's own policy).
     */
    private static String datasetHalf(String text)
    {
        String r = CheckConditionTransformer.resolveTextWildcard(text, "AE", "AE");
        return r == null ? text : r;
    }


    @Test
    void theInventoryFoldTemplateAndNamePatternKeepTheirTemplates()
    {
        // D92a: variable_count's template re-resolves per iterated dataset
        assertKept("variable_count(\"--LNKGRP\") < 2");
        assertKept("variable_count(name=\"--LNKGRP\") < 2");
        // D92b: name_pattern= is a regex selector
        assertKept("variable_count(name_pattern=\"^--.*$\") < 2");
        assertKept("row_max(name_pattern=\"^--.*$\") > 1");
    }

    // ------------------------------------------------------------------ S8: bindings (G1)


    /** AE: row 1 AETERM "--SEQ", row 2 AETERM "AESEQ"; AESEQ 1, 2. */
    private static IDataTable ae()
    {
        return RealTables.of("AE").str("USUBJID", "S1", "S2").lng("AESEQ", 1L, 2L)
                .str("AETERM", "--SEQ", "AESEQ").build();
    }


    private static String esc(String s)
    {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }


    private static List<Long> fired(String check, String bindingName, String bindingExpr)
    {
        String json = "{\"rules\":{\"R\":{\"Core\":{\"Id\":\"T-SLP\"},\"Sensitivity\":\"Record\","
                + "\"Bindings\":[{\"name\":\"" + bindingName + "\",\"expression\":\""
                + esc(bindingExpr) + "\"}],\"Check\":{\"expression\":\""
                + esc("not empty(USUBJID) and (" + check + ")")
                + "\"},\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}}}";
        Rule r;
        try
        {
            r = RulePackageLoader.loadFromString(json).getRules().get("R");
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
        assertNotNull(r);
        assertNull(r.getLoadError(), r.getLoadError());
        IDataTable primary = ae();
        RuleExecutionResult result = RuleRunnerCalls.execute(r, primary,
                RealTables.inventoryOf(primary), "AE");
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        List<Long> rows = new ArrayList<>();
        result.getViolations().forEach(v -> rows.add(v.getRowNumber()));
        return rows;
    }


    @Test
    void aBoundNameAtANameSlotAnswersAsTheNameWrittenThere()
    {
        // colref($c) with $c: "--SEQ" reads AESEQ, as colref("--SEQ") does (S8, G1)
        assertEquals(List.of(1L), fired("colref($c) == 1", "$c", "\"--SEQ\""));
        assertEquals(fired("colref(\"--SEQ\") == 1", "$c", "\"--SEQ\""),
                fired("colref($c) == 1", "$c", "\"--SEQ\""));
    }


    @Test
    void aBoundLiteralOutsideANameSlotIsDataAsTheInlineLiteralIs()
    {
        // X == $c compares against "--SEQ" verbatim, exactly as X == "--SEQ" does (G1, S3)
        assertEquals(List.of(1L), fired("AETERM == $c", "$c", "\"--SEQ\""));
        assertEquals(fired("AETERM == \"--SEQ\"", "$c", "\"--SEQ\""),
                fired("AETERM == $c", "$c", "\"--SEQ\""));
    }
}
