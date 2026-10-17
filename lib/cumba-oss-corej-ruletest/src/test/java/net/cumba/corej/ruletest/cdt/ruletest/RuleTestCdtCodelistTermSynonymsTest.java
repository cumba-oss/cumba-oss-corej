package net.cumba.corej.ruletest.cdt.ruletest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.metadata.MetadataKeys;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.ruletest.cdt.ruletest.RunEnvironment.StoreAvailability;
import net.cumba.corej.ruletest.cdt.ruletest.ScenarioRunner.Outcome;
import net.cumba.corej.ruletest.cdt.ruletest.ScenarioRunner.ScenarioRunResult;
import net.cumba.datatable.metadata.ICodeList;
import net.cumba.datatable.metadata.ICodelistEntry;
import org.junit.jupiter.api.Test;

/**
 * {@code #library codelist-term-synonyms CODELIST TERM SYNONYM [SYNONYM ...]} — the {@code .cdt}
 * channel for a CT term's CDISC synonyms ({@code PLAN-ruletest-scenario-channels} item 1): the
 * parser, the post-merge term check, the writer round trip, and the end-to-end run of a rule that
 * reads {@code codelist_terms(level="term", returntype="synonym")} (the {@code CDISC-SEND-9453}
 * shape), which SKIPs without the directive.
 */
class RuleTestCdtCodelistTermSynonymsTest
{

    private static final String DATA = """
            dataset BW class=FINDINGS label="Body Weights"
            col USUBJID type=Char
            col BWORRESU type=Char
            ---
            S1 | Gram
            S2 | g
            S3 | lb
            ---
            """;

    /** The CDISC-SEND-9453 shape: a unit that is a synonym of a UNIT term but not a value. */
    private static final String PACKAGE = """
            {"rules":{"CDISC-XX9453":{"Core":{"Id":"CDISC-XX9453","Status":"Published",
            "Version":"1"},"Executability":"Fully Executable",
            "Scope":{"Domains":{"Include":["BW"]}},
            "Bindings":[
            {"name":"$unit_values","expression":"codelist_terms(codelists=[\\"UNIT\\"], level=\\"term\\", returntype=\\"value\\")"},
            {"name":"$unit_synonyms","expression":"codelist_terms(codelists=[\\"UNIT\\"], level=\\"term\\", returntype=\\"synonym\\")"}],
            "Check":{"expression":"not empty(BWORRESU) and BWORRESU not in $unit_values and BWORRESU in $unit_synonyms"},
            "Outcome":{"Message":"a unit synonym"}}}}
            """;

    private static final StoreAvailability NO_STORE = StoreAvailability.unavailable("no store",
            "no CDISC metadata store in this test", false);

    private static RuleTestScenario parse(String aPrelude)
    {
        return RuleTestCdt.parse("#!RuleTest\n" + aPrelude + DATA, "inline.cdt", null);
    }


    private static ICodeList unit(RuleTestScenario aScenario)
    {
        MapBackedLibraryMetadataProvider library = aScenario.getLibrary();
        assertNotNull(library);
        Optional<ICodeList> codelist = library.getCodelist("UNIT");
        assertTrue(codelist.isPresent(), "UNIT is declared");
        return codelist.get();
    }


    /** Term submission value → its TERM_SYNONYMS meta value (absent: no entry). */
    private static Map<String, Object> synonymsByTerm(ICodeList aCodelist)
    {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (ICodelistEntry e : aCodelist.getEntries())
        {
            e.getMetaValue(MetadataKeys.TERM_SYNONYMS).ifPresent(v -> out.put(e.getCodeValue(), v));
        }
        return out;
    }


    private static Rule rule() throws IOException
    {
        Rule rule = RulePackageLoader.loadFromString(PACKAGE).getRules().get("CDISC-XX9453");
        assertNotNull(rule);
        assertNull(rule.getLoadError(), rule.getLoadError());
        return rule;
    }


    private static ScenarioRunResult run(String aPrelude) throws IOException
    {
        return ScenarioRunner.run(rule(), parse(aPrelude), null,
                new RunEnvironment(NO_STORE, null, null), null);
    }

    // ---- parser --------------------------------------------------------------------------------


    @Test
    void aLinePerTermServesThatTermsSynonymsInDeclaredOrder()
    {
        ICodeList unit = unit(parse("""
                #test CDISC-XX9453 expect=violation domain=BW
                #library codelist-terms UNIT g kg mg
                #library codelist-term-synonyms UNIT g Gram "gram unit"
                #library codelist-term-synonyms UNIT kg Kilogram
                """));
        assertEquals(Map.of("g", List.of("Gram", "gram unit"), "kg", List.of("Kilogram")),
                synonymsByTerm(unit));
        // A term without a line carries no meta value at all — the unpublished shape of the store
        // (CdiscLibraryMetadataLibrary), so the synonym projection contributes nothing for it.
        ICodelistEntry mg = unit.getEntries().get(2);
        assertEquals("mg", mg.getCodeValue());
        assertEquals(Set.of(), mg.getMetaKeys());
        assertEquals(Optional.empty(), mg.getMetaValue(MetadataKeys.TERM_SYNONYMS));
        assertEquals(Set.of(MetadataKeys.TERM_SYNONYMS), unit.getEntries().get(0).getMetaKeys());
    }


    @Test
    void theTermCheckRunsAfterEveryLineSoLineOrderDoesNotMatter()
    {
        ICodeList unit = unit(parse("""
                #test CDISC-XX9453 expect=violation domain=BW
                #library codelist-term-synonyms UNIT g Gram
                #library codelist-terms UNIT g
                """));
        assertEquals(Map.of("g", List.of("Gram")), synonymsByTerm(unit));
    }


    @Test
    void aSecondLineForTheSameTermReplacesItsSynonyms()
    {
        ICodeList unit = unit(parse("""
                #test CDISC-XX9453 expect=violation domain=BW
                #library codelist-terms UNIT g
                #library codelist-term-synonyms UNIT g Gram
                #library codelist-term-synonyms UNIT g Grams
                """));
        assertEquals(Map.of("g", List.of("Grams")), synonymsByTerm(unit));
    }


    @Test
    void aMalformedOrUnservableLineIsALoadErrorNamingItsLine()
    {
        String test = "#test CDISC-XX9453 expect=violation domain=BW\n"
                + "#library codelist-terms UNIT g\n";
        // Line 4 of the file: the shebang, #test, codelist-terms, then the line under test.
        Map<String, String> cases = new java.util.LinkedHashMap<>();
        cases.put("#library codelist-term-synonyms\n", "expected CODELIST TERM SYNONYM");
        cases.put("#library codelist-term-synonyms UNIT\n", "expected CODELIST TERM SYNONYM");
        cases.put("#library codelist-term-synonyms UNIT g\n", "expected CODELIST TERM SYNONYM");
        cases.put("#library codelist-term-synonyms XYZ g Gram\n", "unknown codelist 'XYZ'");
        cases.put("#library codelist-term-synonyms UNIT mg Milligram\n",
                "'mg' is not a term of codelist 'UNIT'");
        for (Map.Entry<String, String> c : cases.entrySet())
        {
            RuleTestCdtException e = assertThrows(RuleTestCdtException.class,
                    () -> parse(test + c.getKey()), c.getKey());
            String message = String.valueOf(e.getMessage());
            assertTrue(message.startsWith("inline.cdt:4: #library codelist-term-synonyms"),
                    message);
            assertTrue(message.contains(c.getValue()), message);
        }
    }


    @Test
    void theTermMatchIsExactAsTheEngineReadsSubmissionValues()
    {
        // Term keys are submission values, kept verbatim: 'G' is not the term 'g'.
        RuleTestCdtException e = assertThrows(RuleTestCdtException.class, () -> parse("""
                #test CDISC-XX9453 expect=violation domain=BW
                #library codelist-terms UNIT g
                #library codelist-term-synonyms unit G Gram
                """));
        assertTrue(String.valueOf(e.getMessage()).contains("'G' is not a term of codelist"),
                e.getMessage());
    }

    // ---- writer --------------------------------------------------------------------------------


    @Test
    void theWriterRoundTripsTheSynonymsOneLinePerTerm()
    {
        RuleTestScenario parsed = parse("""
                #test CDISC-XX9453 expect=violation domain=BW
                #library codelist-terms UNIT kg g
                #library codelist-term-synonyms UNIT kg Kilogram
                #library codelist-term-synonyms unit g Gram "gram unit"
                """);
        String written = RuleTestCdt.toString(parsed);
        // Codelist key upper-cased, terms sorted, synonyms in declared order, quoted when needed.
        assertTrue(written.contains("#library codelist-term-synonyms UNIT g Gram \"gram unit\"\n"
                + "#library codelist-term-synonyms UNIT kg Kilogram\n"), written);
        RuleTestScenario reparsed = RuleTestCdt.parse(written, "written.cdt", null);
        assertEquals(synonymsByTerm(unit(parsed)), synonymsByTerm(unit(reparsed)));
        assertEquals(written, RuleTestCdt.toString(reparsed),
                "the writer's output is a fixed point");
    }


    @Test
    void aScenarioWithoutSynonymsWritesNoSynonymLine()
    {
        String written = RuleTestCdt.toString(parse("""
                #test CDISC-XX9453 expect=violation domain=BW
                #library codelist-terms UNIT g
                """));
        assertTrue(!written.contains("codelist-term-synonyms"), written);
    }

    // ---- end to end ----------------------------------------------------------------------------


    @Test
    void theSynonymRuleFiresOnTheSynonymRowWithTheDirective() throws IOException
    {
        ScenarioRunResult r = run("""
                #test CDISC-XX9453 expect=violation domain=BW
                #expectViolationAt row=1 BWORRESU=Gram
                #library codelist-terms UNIT g kg
                #library codelist-term-synonyms UNIT g Gram
                """);
        assertEquals(Outcome.PASS, r.outcome(), r.detail());
        assertNotNull(r.evaluation());
        assertEquals(1, r.evaluation().totalCount(),
                "only the synonym row: g is a value, lb is " + "neither");
    }


    @Test
    void withoutTheDirectiveTheSynonymProjectionIsEmptyAndTheRuleSkips() throws IOException
    {
        // The sensitivity arm: the very same scenario minus the synonym line cannot express the
        // violation — codelist_terms(returntype="synonym") resolves nothing and the rule SKIPs.
        ScenarioRunResult r = run("""
                #test CDISC-XX9453 expect=violation domain=BW
                #library codelist-terms UNIT g kg
                """);
        assertEquals(Outcome.FAIL, r.outcome(), r.detail());
        assertEquals("VERDICT", r.code(), r.detail());
        assertTrue(r.detail().contains("SKIPPED"), r.detail());
        ScenarioRunResult skipped = run("""
                #test CDISC-XX9453 expect=skipped domain=BW
                #library codelist-terms UNIT g kg
                """);
        assertEquals(Outcome.PASS, skipped.outcome(), skipped.detail());
    }
}
