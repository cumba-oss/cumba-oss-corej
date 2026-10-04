package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.RuleDefinitionException;
import net.cumba.corej.core.metadata.LibraryTemplates;
import net.cumba.corej.core.metadata.LibraryTemplates.Candidates;
import net.cumba.corej.core.metadata.LibraryTemplates.Entry;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-library-template-label-resolution} round-2 ruling R2-3 — the stem-selecting form
 * {@code label_template([x,] "LIBRARY", stem="*DT")}, evaluated end to end: a stem column answers
 * the NAMED stem's phrase (each stem rule judges every column its suffix matches with its own
 * phrase), a library literal or a stem the name does not end in answers a missing, and the
 * {@code stem=} value is checked at load.
 */
class LabelTemplateStemFormTest
{

    private static Entry e(String name, String label)
    {
        return new Entry(name, Map.of("label", label));
    }

    private static final Candidates BDS = new Candidates(
            List.of(e("ASTDT", "Analysis Start Date"),
                    e("PxxSwSDT", "Period xx Subperiod w Start Date")),
            List.of(e("TRTSDT", "Date of First Exposure to Treatment")),
            List.of(e("*DT", "{Date}"), e("*SDT", "{Start Date}"), e("*EDT", "{End Date}"),
                    e("*TM", "{Time}"), e("*DTM", "{Datetime}")));

    /** A domain-keyed provider answering the template leg, as the {@code .cdt} harness does. */
    private static final MetadataProvider LIBRARY = new TemplateLeg();

    /** Answers {@link LibraryTemplates#resolve} over {@link #BDS}; every other read is empty. */
    private static final class TemplateLeg implements MetadataProvider
    {

        @Override
        public Map<String, String> getVariableMetadata(String aDomain, String aVariable)
        {
            return LibraryTemplates.resolve(aVariable, BDS);
        }


        @Override
        public List<String> getRequiredVariables(String aDomain)
        {
            return List.of();
        }


        @Override
        public List<String> getExpectedVariables(String aDomain)
        {
            return List.of();
        }


        @Override
        public List<String> getColumnOrder(String aDomain)
        {
            return List.of();
        }


        @Override
        public boolean isDomainCustom(String aDomain)
        {
            return false;
        }


        @Override
        public List<String> getCodelistTerms(String aCodelistCode)
        {
            return List.of();
        }


        @Override
        public List<Map<String, String>> getDomainVariables(String aDomain)
        {
            return List.of();
        }


        @Override
        public Map<String, String> getDatasetMetadata(String aDomain)
        {
            return Map.of();
        }


        @Override
        public Optional<Boolean> isCodelistExtensible(String aCodelistName)
        {
            return Optional.empty();
        }


        @Override
        public String getStandard()
        {
            return "adamig";
        }
    }

    private static final IDataTable ADLB = MockTable.of().name("ADLB").col("RANDSDT", "2024-01-01")
            .col("XXDT", "2024-01-01").col("ASTDT", "2024-01-01").col("TRTSDT", "2024-01-01")
            .col("P01S1SDT", "2024-01-01").col("XXDTM", "x").build();

    private static BitSet eval(String expr, String variable)
    {
        EvaluationContext ctx = EvaluationContext.builder().table(ADLB).domainName("ADLB")
                .variables(Map.of("variable_name", variable)).libraryProvider(LIBRARY).build();
        return NativeExprEvaluator.evaluate(CheckExpressionParser.parse(expr), ctx);
    }


    private static boolean holds(String expr, String variable)
    {
        return eval(expr, variable).get(0);
    }


    @Test
    void aNestedStemColumnAnswersEachStemItEndsInWithThatStemsOwnPhrase()
    {
        assertTrue(holds("label_template(\"LIBRARY\", stem=\"*DT\") == \"{Date}\"", "RANDSDT"));
        assertTrue(
                holds("label_template(\"LIBRARY\", stem=\"*SDT\") == \"{Start Date}\"", "RANDSDT"));
        assertTrue(holds("label_template(\"LIBRARY\") == \"{Start Date}\"", "RANDSDT"),
                "the plain form stays the longest stem's");
        assertTrue(holds("label_template(varname(), \"LIBRARY\", stem=\"*dt\") == \"{Date}\"",
                "RANDSDT"), "the explicit-name form, and the stem case-folded");
        assertTrue(holds("empty(label_template(\"LIBRARY\", stem=\"*EDT\"))", "RANDSDT"),
                "a stem the name does not end in");
        assertTrue(holds("label_template(\"LIBRARY\", stem=\"*TM\") == \"{Time}\"", "XXDTM"),
                "*TM fits a *DTM name too: the rule, not the engine, decides whether to judge it");
    }


    @Test
    void aLibraryLiteralOrIndexTemplateIsNoStemCase()
    {
        assertTrue(holds("empty(label_template(\"LIBRARY\", stem=\"*DT\"))", "ASTDT"),
                "an own-structure literal ending in DT");
        assertTrue(holds("empty(label_template(\"LIBRARY\", stem=\"*SDT\"))", "TRTSDT"),
                "an ADSL literal copied into BDS (D9)");
        assertTrue(holds("empty(label_template(\"LIBRARY\", stem=\"*SDT\"))", "P01S1SDT"),
                "an index template");
        assertTrue(holds("label_template(\"LIBRARY\") == \"Analysis Start Date\"", "ASTDT"),
                "control: the plain form serves the literal's label");
    }


    @Test
    void theStemRulesShapeFiresOnlyWhereTheNamedStemsPhraseIsAbsent()
    {
        // AD0503 after R2-3: judged with *DT's phrase; AD0509 with *SDT's. A conforming nested
        // label satisfies both; a label without "Date" fails both — the second report.
        String ad0503 = "ends_with(upper(varname()), \"DT\")"
                + " and not empty(label_template(\"LIBRARY\", stem=\"*DT\"))"
                + " and not matches_label_template(%s, label_template(\"LIBRARY\", stem=\"*DT\"))";
        String ad0509 = ad0503.replace("\"DT\")", "\"SDT\")").replace("*DT", "*SDT");
        String good = "\"Randomization Start Date\"";
        String bad = "\"Foo\"";
        assertFalse(holds(ad0503.formatted(good), "RANDSDT"));
        assertFalse(holds(ad0509.formatted(good), "RANDSDT"));
        assertTrue(holds(ad0503.formatted(bad), "RANDSDT"),
                "the second report: *DT's rule judges the nested column too");
        assertTrue(holds(ad0509.formatted(bad), "RANDSDT"));
        assertFalse(holds(ad0503.formatted(bad), "TRTSDT"),
                "the literal skip is structural: an ADSL literal is no stem case");
        assertFalse(holds(ad0509.formatted(bad), "XXDT"), "XXDT does not end in SDT");
    }


    /**
     * The finding's {@code library_variable_label_template} reports the template the Check judged
     * ({@code RuleRunner}): {@link MetadataExprScan#labelTemplateStem} names the one stem every
     * {@code label_template} read selects, and answers {@code null} for a plain read, a mix, or two
     * different stems.
     */
    @Test
    void theJudgedStemIsTheOneStemEveryReadSelects()
    {
        assertEquals("*DT", MetadataExprScan.labelTemplateStem(CheckExpressionParser.parse(
                "ends_with(upper(varname()), \"DT\") and not empty(label_template(\"LIBRARY\","
                        + " stem=\"*dt\")) and not matches_label_template(var_label(\"DATA\"),"
                        + " label_template(varname(), \"LIBRARY\", stem=\"*DT\"))")));
        assertNull(MetadataExprScan.labelTemplateStem(CheckExpressionParser.parse(
                "not matches_label_template(var_label(\"DATA\"), label_template(\"LIBRARY\"))")),
                "the plain form");
        assertNull(MetadataExprScan.labelTemplateStem(CheckExpressionParser
                .parse("not empty(label_template(\"LIBRARY\", stem=\"*DT\")) and not"
                        + " empty(label_template(\"LIBRARY\"))")),
                "a mix");
        assertNull(
                MetadataExprScan.labelTemplateStem(CheckExpressionParser
                        .parse("not empty(label_template(\"LIBRARY\", stem=\"*DT\")) and not"
                                + " empty(label_template(\"LIBRARY\", stem=\"*SDT\"))")),
                "two stems");
        assertNull(
                MetadataExprScan.labelTemplateStem(CheckExpressionParser
                        .parse("var_label(\"DATA\") != var_label(\"LIBRARY\")")),
                "no label_template read");
    }


    @Test
    void theStemArgumentIsCheckedAtLoad()
    {
        for (String bad : List.of("label_template(\"LIBRARY\", stem=\"DT\")",
                "label_template(\"LIBRARY\", stem=\"*\")", "label_template(\"LIBRARY\", stem=5)",
                "label_template(\"LIBRARY\", stem=upper(X))",
                "var_label(\"LIBRARY\", stem=\"*DT\")",
                "label_template(\"LIBRARY\", stam=\"*DT\")"))
        {
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> NativeExprEvaluator.bindingProgram(CheckExpressionParser.parse(bad)),
                    bad);
            assertEquals(RuleDefinitionException.class, ex.getClass(), bad + ": " + ex);
        }
    }
}
