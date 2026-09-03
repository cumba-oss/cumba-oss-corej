package net.cumba.corej.ruletest.cdt.ruletest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestScenario.Verdict;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Edge-case coverage for {@link RuleTestCdt} on top of {@link RuleTestCdtTest}: malformed
 * directives, tokenisation rules, line-number accuracy, the {@code #library} directive
 * parser/writer, and load/resource-loader paths.
 */
class RuleTestCdtEdgeCaseTest
{
    // ---- Helpers ------------------------------------------------------------------

    private static final String MIN_DATASET = """
            dataset AE
            col A type=Char
            ---
            x
            ---
            """;

    private static String scenario(String aDirectives)
    {
        return "#!RuleTest\n" + aDirectives + "\n" + MIN_DATASET;
    }

    @Nested
    class MalformedDirectives
    {

        static Stream<Arguments> malformedDirectiveCases()
        {
            return Stream.of(Arguments.of("duplicate #test", """
                    #test CORE-1 expect=violation domain=AE
                    #test CORE-2 expect=violation domain=AE""", "duplicate #test"),
                    Arguments.of("duplicate #note", """
                            #test CORE-1 expect=violation domain=AE
                            #note "first"
                            #note "second\"""", "duplicate #note"),
                    Arguments.of("empty domain value", "#test CORE-1 expect=violation domain=",
                            "domain"),
                    Arguments.of("positional token after core id",
                            "#test CORE-1 EXTRA expect=violation domain=AE", "positional"),
                    Arguments.of("#note without text", """
                            #test CORE-1 expect=violation domain=AE
                            #note""", "#note"));
        }


        @ParameterizedTest(name = "{0}")
        @MethodSource("malformedDirectiveCases")
        void malformedDirective_isRejected(String aLabel, String aDirectives,
                String aExpectedSubstring)
        {
            String content = scenario(aDirectives);
            RuleTestCdtException ex = assertThrows(RuleTestCdtException.class,
                    () -> RuleTestCdt.parse(content, "t"));
            assertTrue(ex.getMessage().contains(aExpectedSubstring), ex.getMessage());
        }


        @ParameterizedTest
        @ValueSource(strings =
        {
                "maybe", "yes", "true", "Violation_", ""
        })
        void invalidExpectValue_isRejected(String aBadValue)
        {
            String content = scenario("#test CORE-1 expect=" + aBadValue + " domain=AE");
            RuleTestCdtException ex = assertThrows(RuleTestCdtException.class,
                    () -> RuleTestCdt.parse(content, "t"));
            assertTrue(ex.getMessage().contains("expect"), ex.getMessage());
        }


        @Test
        void emptyDirective_afterStandaloneHash_isSkippedAsComment()
        {
            // A standalone hash on its own line is treated as a regular comment
            // because nothing follows the hash, so parsing succeeds.
            String content = """
                    #!RuleTest
                    #test CORE-1 expect=violation domain=AE
                    #
                    """ + MIN_DATASET;
            RuleTestScenario s = RuleTestCdt.parse(content, "t");
            assertEquals("CORE-1", s.getCoreId());
        }


        @Test
        void emptyContent_isRejected()
        {
            RuleTestCdtException ex = assertThrows(RuleTestCdtException.class,
                    () -> RuleTestCdt.parse(null, "t"));
            assertTrue(ex.getMessage().contains("empty"), ex.getMessage());
        }


        @ParameterizedTest
        @ValueSource(strings =
        {
                "setup", "teardown"
        })
        void reservedDirectives_areRejected(String aKeyword)
        {
            String content = scenario("""
                    #test CORE-1 expect=violation domain=AE
                    #""" + aKeyword + " foo");
            RuleTestCdtException ex = assertThrows(RuleTestCdtException.class,
                    () -> RuleTestCdt.parse(content, "t"));
            assertTrue(ex.getMessage().contains("reserved"), ex.getMessage());
        }
    }


    @Nested
    class LocationDirectives
    {

        static Stream<Arguments> invalidLocationCases()
        {
            return Stream.of(Arguments.of("count not an integer", """
                    #test CORE-1 expect=violation domain=AE
                    #expectViolationCount two""", "non-negative integer"),
                    Arguments.of("duplicate count", """
                            #test CORE-1 expect=violation domain=AE
                            #expectViolationCount 1
                            #expectViolationCount 2""", "duplicate #expectViolationCount"),
                    Arguments.of("empty at", """
                            #test CORE-1 expect=violation domain=AE
                            #expectViolationAt""", "at least one"),
                    Arguments.of("non-positive row", """
                            #test CORE-1 expect=violation domain=AE
                            #expectViolationAt row=0""", "1-based integer"),
                    Arguments.of("non-numeric row", """
                            #test CORE-1 expect=violation domain=AE
                            #expectViolationAt row=x""", "1-based integer"),
                    Arguments.of("duplicate row", """
                            #test CORE-1 expect=violation domain=AE
                            #expectViolationAt row=1 row=2""", "duplicate row="),
                    Arguments.of("at without value", """
                            #test CORE-1 expect=violation domain=AE
                            #expectViolationAt USUBJID""", "key=value"),
                    Arguments.of("at requires violation verdict", """
                            #test CORE-1 expect=noViolation domain=AE
                            #expectViolationAt row=1""", "require expect=violation"),
                    Arguments.of("count>=1 requires violation verdict", """
                            #test CORE-1 expect=noViolation domain=AE
                            #expectViolationCount 1""", "require expect=violation"),
                    Arguments.of("count 0 requires noViolation verdict", """
                            #test CORE-1 expect=violation domain=AE
                            #expectViolationCount 0""", "requires expect=noViolation"),
                    Arguments.of("count 0 forbids at", """
                            #test CORE-1 expect=violation domain=AE
                            #expectViolationCount 0
                            #expectViolationAt row=1""", "forbids #expectViolationAt"),
                    Arguments.of("count disagrees with at lines", """
                            #test CORE-1 expect=violation domain=AE
                            #expectViolationCount 2
                            #expectViolationAt row=1""", "disagrees"));
        }


        @ParameterizedTest(name = "{0}")
        @MethodSource("invalidLocationCases")
        void invalidLocationDirective_isRejected(String aLabel, String aDirectives,
                String aExpectedSubstring)
        {
            String content = scenario(aDirectives);
            RuleTestCdtException ex = assertThrows(RuleTestCdtException.class,
                    () -> RuleTestCdt.parse(content, "t"));
            assertTrue(ex.getMessage().contains(aExpectedSubstring), ex.getMessage());
        }
    }


    @Nested
    class TokenisationEdgeCases
    {

        static Stream<Arguments> noteParseCases()
        {
            return Stream.of(
                    // Quoted note keeps embedded equals signs and inner whitespace.
                    Arguments.of("quoted note with equals and spaces", """
                            #test CORE-1 expect=violation domain=AE
                            #note "a=b c=d e\"""", "a=b c=d e"),
                    // Backslash escapes inside a quoted note are unescaped
                    // (backslash-quote becomes quote, backslash-backslash becomes
                    // backslash).
                    Arguments.of("quoted note with backslash escapes", """
                            #test CORE-1 expect=violation domain=AE
                            #note "a\\"b\\\\c\"""", "a\"b\\c"),
                    // The tokenizer collapses runs of whitespace and the note
                    // payload joins the remaining tokens with a single space.
                    Arguments.of("unquoted multi-word note collapses whitespace", """
                            #test CORE-1 expect=violation domain=AE
                            #note one    two   three""", "one two three"));
        }


        @ParameterizedTest(name = "{0}")
        @MethodSource("noteParseCases")
        void noteDirective_isParsedAsExpected(String aLabel, String aDirectives,
                String aExpectedNote)
        {
            String content = scenario(aDirectives);
            RuleTestScenario s = RuleTestCdt.parse(content, "t");
            assertEquals(aExpectedNote, s.getNote());
        }


        @Test
        void unterminatedQuote_inDirective_isRejected()
        {
            String content = scenario("""
                    #test CORE-1 expect=violation domain=AE
                    #note "broken""");
            assertThrows(RuleTestCdtException.class, () -> RuleTestCdt.parse(content, "t"));
        }


        @Test
        void leadingBlankLinesBeforeShebang_areTolerated()
        {
            String content = "\n\n   \n" + scenario("""
                    #test CORE-1 expect=violation domain=AE""");
            assertEquals("CORE-1", RuleTestCdt.parse(content, "t").getCoreId());
        }
    }


    @Nested
    class ErrorLineNumbers
    {

        @Test
        void directiveErrorOnLine3_reportsLine3()
        {
            // 1: shebang, 2: bad #test, 3: dataset, ...
            String content = """
                    #!RuleTest
                    #test CORE-1 expect=maybe domain=AE
                    dataset AE
                    col A
                    ---
                    x
                    ---
                    """;
            RuleTestCdtException ex = assertThrows(RuleTestCdtException.class,
                    () -> RuleTestCdt.parse(content, "scenario.cdt"));
            assertTrue(ex.getMessage().contains("scenario.cdt:2:"), ex.getMessage());
        }


        @Test
        void unknownDirectiveErrorOnLine4_reportsLine4()
        {
            String content = """
                    #!RuleTest
                    #test CORE-1 expect=violation domain=AE
                    # plain comment
                    #frobnicate yes
                    dataset AE
                    col A
                    ---
                    x
                    ---
                    """;
            RuleTestCdtException ex = assertThrows(RuleTestCdtException.class,
                    () -> RuleTestCdt.parse(content, "f"));
            assertTrue(ex.getMessage().contains("f:4:"), ex.getMessage());
        }
    }


    @Nested
    class LibraryDirective
    {

        @Test
        void library_scalarLine_setsStandardAndVersion()
        {
            String content = scenario("""
                    #test CORE-1 expect=violation domain=AE
                    #library standard=sdtmig version=3-4""");
            RuleTestScenario s = RuleTestCdt.parse(content, "t");

            MapBackedLibraryMetadataProvider lib = s.getLibrary();
            assertNotNull(lib);
            assertEquals("sdtmig", lib.getStandard());
            assertEquals("3-4", lib.getVersion());
        }


        @Test
        void library_requiredVariables_storedPerDomain()
        {
            String content = scenario("""
                    #test CORE-1 expect=violation domain=AE
                    #library required-variables AE STUDYID USUBJID""");
            RuleTestScenario s = RuleTestCdt.parse(content, "t");

            assertEquals(List.of("STUDYID", "USUBJID"), s.getLibrary().getRequiredVariables("AE"));
        }


        @Test
        void library_expectedAndColumnOrder_storedPerDomain()
        {
            String content = scenario("""
                    #test CORE-1 expect=violation domain=AE
                    #library expected-variables AE AESEV AESER
                    #library column-order AE STUDYID USUBJID
                    #library model-column-order AE STUDYID""");
            RuleTestScenario s = RuleTestCdt.parse(content, "t");

            assertEquals(List.of("AESEV", "AESER"), s.getLibrary().getExpectedVariables("AE"));
            assertEquals(List.of("STUDYID", "USUBJID"), s.getLibrary().getColumnOrder("AE"));
            assertEquals(List.of("STUDYID"), s.getLibrary().getModelColumnOrder("AE"));
        }


        @Test
        void library_customDomain_acceptsMultipleDomains()
        {
            String content = scenario("""
                    #test CORE-1 expect=violation domain=AE
                    #library custom-domain XX YY""");
            RuleTestScenario s = RuleTestCdt.parse(content, "t");

            assertTrue(s.getLibrary().isDomainCustom("XX"));
            assertTrue(s.getLibrary().isDomainCustom("YY"));
        }


        @Test
        void library_codelistTerms_stored()
        {
            String content = scenario("""
                    #test CORE-1 expect=violation domain=AE
                    #library codelist-terms NY Y N""");
            RuleTestScenario s = RuleTestCdt.parse(content, "t");

            assertEquals(List.of("Y", "N"), s.getLibrary().getCodelistTerms("NY"));
        }


        @Test
        void library_publishedCtPackages_stored()
        {
            String content = scenario("""
                    #test CORE-1 expect=violation domain=AE
                    #library published-ct-packages sdtmct-2023-10-26 sdtmct-2024-03-29""");
            RuleTestScenario s = RuleTestCdt.parse(content, "t");

            assertEquals(List.of("sdtmct-2023-10-26", "sdtmct-2024-03-29"),
                    s.getLibrary().getPublishedCtPackages());
        }


        @Test
        void library_datasetClass_setsClassName()
        {
            String content = scenario("""
                    #test CORE-1 expect=violation domain=AE
                    #library dataset-class AE EVENTS""");
            RuleTestScenario s = RuleTestCdt.parse(content, "t");

            assertEquals("EVENTS", s.getLibrary().getDatasetMetadata("AE").get("className"));
        }


        @Test
        void library_domainAndModelVariables_acceptNameRolePairs()
        {
            String content = scenario("""
                    #test CORE-1 expect=violation domain=AE
                    #library domain-variables AE STUDYID:Topic USUBJID:Topic
                    #library model-variables AE AETERM:Topic""");
            RuleTestScenario s = RuleTestCdt.parse(content, "t");

            List<java.util.Map<String, String>> dvars = s.getLibrary().getDomainVariables("AE");
            assertEquals(2, dvars.size());
            assertEquals("STUDYID", dvars.get(0).get("name"));
            assertEquals("Topic", dvars.get(0).get("role"));

            assertEquals(1, s.getLibrary().getModelVariables("AE").size());
            assertEquals("AETERM", s.getLibrary().getModelVariables("AE").get(0).get("name"));
        }


        /**
         * EC-85 — {@code model-class-variables} is CLASS-keyed where {@code model-variables} is
         * DOMAIN-keyed; a quoted token carries a space in the role; names stay unsubstituted; a
         * class declared twice is replaced, not appended; an undeclared class is empty.
         */
        @Test
        void library_modelClassVariables_areClassKeyedAndUnsubstituted()
        {
            String content = scenario("""
                    #test CORE-1 expect=violation domain=AE
                    #library model-class-variables events --TERM:Topic "--DECOD:Synonym Qualifier"
                    #library model-class-variables FINDINGS --TESTCD:Topic
                    #library model-class-variables FINDINGS --TESTCD:Topic --ORRES:Result
                    #library model-variables AE AETERM:Topic""");
            RuleTestScenario s = RuleTestCdt.parse(content, "t");
            MapBackedLibraryMetadataProvider lib = s.getLibrary();

            List<java.util.Map<String, String>> events = lib.getModelVariablesForClass("EVENTS");
            assertEquals(2, events.size());
            assertEquals("--TERM", events.get(0).get("name"));
            assertEquals("Topic", events.get(0).get("role"));
            assertEquals("--DECOD", events.get(1).get("name"));
            assertEquals("Synonym Qualifier", events.get(1).get("role"));
            assertEquals(2, lib.getModelVariablesForClass("findings").size(),
                    "a second declaration replaces the first");
            assertTrue(lib.getModelVariablesForClass("INTERVENTIONS").isEmpty());
            // The two maps do not bleed into each other.
            assertTrue(lib.getModelVariables("EVENTS").isEmpty());
            assertTrue(lib.getModelVariablesForClass("AE").isEmpty());
            assertEquals(1, lib.getModelVariables("AE").size());
            assertFalse(lib.isEmpty());
        }


        @Test
        void library_codelistExtensible_storedAndDefaultsTrue()
        {
            String content = scenario("""
                    #test CORE-1 expect=violation domain=AE
                    #library codelist-extensible C66742 false""");
            RuleTestScenario s = RuleTestCdt.parse(content, "t");

            assertFalse(s.getLibrary().isCodelistExtensible("C66742"));
            // An unmentioned codelist still defaults to extensible=true.
            assertTrue(s.getLibrary().isCodelistExtensible("C12345"));
        }


        @Test
        void library_codelistExtensible_acceptsTrue()
        {
            String content = scenario("""
                    #test CORE-1 expect=violation domain=AE
                    #library codelist-extensible C66742 TRUE""");
            RuleTestScenario s = RuleTestCdt.parse(content, "t");

            assertTrue(s.getLibrary().isCodelistExtensible("C66742"));
        }


        @Test
        void library_codelistTermMappings_stored()
        {
            String content = scenario("""
                    #test CORE-1 expect=violation domain=AE
                    #library codelist-term-mappings NY Y=Yes N=No""");
            RuleTestScenario s = RuleTestCdt.parse(content, "t");

            java.util.Map<String, String> m = s.getLibrary().getCodelistTermMappings("NY");
            assertEquals("Yes", m.get("Y"));
            assertEquals("No", m.get("N"));
        }


        @Test
        void library_variableMetadata_storedWithQuotedValues()
        {
            String content = scenario("#test CORE-1 expect=violation domain=AE\n"
                    + "#library variable-metadata AE AETERM label=\"My Label\""
                    + " simpleDatatype=Char core=Exp");
            RuleTestScenario s = RuleTestCdt.parse(content, "t");

            java.util.Map<String, String> vm = s.getLibrary().getVariableMetadata("AE", "AETERM");
            assertEquals("My Label", vm.get("label"));
            assertEquals("Char", vm.get("simpleDatatype"));
            assertEquals("Exp", vm.get("core"));
        }


        @Test
        void library_datasetMetadata_storedAlongsideDatasetClass()
        {
            String content = scenario("""
                    #test CORE-1 expect=violation domain=AE
                    #library dataset-metadata AE structure="One record per event"
                    #library dataset-class DM EVENTS""");
            RuleTestScenario s = RuleTestCdt.parse(content, "t");

            assertEquals("One record per event",
                    s.getLibrary().getDatasetMetadata("AE").get("structure"));
            // dataset-class still maps to className for a different domain.
            assertEquals("EVENTS", s.getLibrary().getDatasetMetadata("DM").get("className"));
        }


        /** CT2003 — the code map behind {@code library_variable_code_pair_matches}. */
        @Test
        void library_codelistCodes_storedPerDomainAndVariable()
        {
            String content = scenario("""
                    #test CORE-1 expect=violation domain=AE
                    #library codelist-codes LB LBTESTCD ALB=C64431 BILI=C64433
                    #library codelist-codes lb LBTEST Albumin=C64431 Bilirubin=C64433""");
            RuleTestScenario s = RuleTestCdt.parse(content, "t");

            java.util.Map<String, String> codes = s.getLibrary().getCodelistCodeMap("LB",
                    "LBTESTCD");
            assertEquals("C64431", codes.get("ALB"));
            assertEquals("C64433", codes.get("BILI"));
            // The domain key folds; the term keys do not.
            assertEquals("C64431",
                    s.getLibrary().getCodelistCodeMap("LB", "LBTEST").get("Albumin"));
            assertTrue(s.getLibrary().getCodelistCodeMap("LB", "LBORRES").isEmpty());
        }


        /** A second declaration for the same (domain, variable) replaces the first. */
        @Test
        void library_codelistCodes_duplicateDeclaration_replaces()
        {
            String content = scenario("""
                    #test CORE-1 expect=violation domain=AE
                    #library codelist-codes LB LBTESTCD ALB=C64431
                    #library codelist-codes LB LBTESTCD BILI=C64433""");
            RuleTestScenario s = RuleTestCdt.parse(content, "t");

            assertEquals(java.util.Map.of("BILI", "C64433"),
                    s.getLibrary().getCodelistCodeMap("LB", "LBTESTCD"));
        }

        // --- Rejections --------------------------------------------------------


        static Stream<Arguments> libraryRejectionCases()
        {
            return Stream.of(Arguments.of("empty tokens", """
                    #test CORE-1 expect=violation domain=AE
                    #library""", "#library"), Arguments.of("unknown scalar key", """
                    #test CORE-1 expect=violation domain=AE
                    #library flavor=spicy""", "flavor"),
                    // A scalar key=value followed by a bare token triggers
                    // the "expected key=value" complaint.
                    Arguments.of("mixing scalar and non-scalar", """
                            #test CORE-1 expect=violation domain=AE
                            #library standard=sdtmig version""", "key=value"),
                    Arguments.of("unknown kind", """
                            #test CORE-1 expect=violation domain=AE
                            #library not-a-real-kind AE STUDYID""", "unknown kind"),
                    Arguments.of("required-variables missing domain", """
                            #test CORE-1 expect=violation domain=AE
                            #library required-variables""", "missing domain"),
                    Arguments.of("custom-domain missing domain", """
                            #test CORE-1 expect=violation domain=AE
                            #library custom-domain""", "custom-domain"),
                    Arguments.of("dataset-class missing args", """
                            #test CORE-1 expect=violation domain=AE
                            #library dataset-class AE""", "DOMAIN CLASSNAME"),
                    Arguments.of("domain-variables missing colon", """
                            #test CORE-1 expect=violation domain=AE
                            #library domain-variables AE BAD_TOKEN""", "NAME:ROLE"),
                    Arguments.of("domain-variables missing domain", """
                            #test CORE-1 expect=violation domain=AE
                            #library domain-variables""", "missing domain"),
                    Arguments.of("codelist-extensible wrong arity", """
                            #test CORE-1 expect=violation domain=AE
                            #library codelist-extensible C66742""", "CODELIST true|false"),
                    Arguments.of("codelist-extensible bad bool", """
                            #test CORE-1 expect=violation domain=AE
                            #library codelist-extensible C66742 maybe""", "true|false"),
                    Arguments.of("codelist-term-mappings missing codelist", """
                            #test CORE-1 expect=violation domain=AE
                            #library codelist-term-mappings""", "missing codelist"),
                    Arguments.of("codelist-term-mappings bad pair", """
                            #test CORE-1 expect=violation domain=AE
                            #library codelist-term-mappings NY badtoken""", "key=value"),
                    Arguments.of("variable-metadata missing var", """
                            #test CORE-1 expect=violation domain=AE
                            #library variable-metadata AE""", "DOMAIN VAR"),
                    Arguments.of("variable-metadata bad pair", """
                            #test CORE-1 expect=violation domain=AE
                            #library variable-metadata AE AETERM nothing""", "key=value"),
                    Arguments.of("dataset-metadata missing domain", """
                            #test CORE-1 expect=violation domain=AE
                            #library dataset-metadata""", "missing domain"),
                    Arguments.of("codelist-codes missing variable", """
                            #test CORE-1 expect=violation domain=AE
                            #library codelist-codes LB""", "DOMAIN VAR TERM=CODE"),
                    Arguments.of("codelist-codes bad pair", """
                            #test CORE-1 expect=violation domain=AE
                            #library codelist-codes LB LBTESTCD ALB""", "key=value"),
                    Arguments.of("model-class-variables missing class", """
                            #test CORE-1 expect=violation domain=AE
                            #library model-class-variables""", "missing class"),
                    Arguments.of("model-class-variables bad token", """
                            #test CORE-1 expect=violation domain=AE
                            #library model-class-variables EVENTS AETERM""", "NAME:ROLE"));
        }


        @ParameterizedTest(name = "{0}")
        @MethodSource("libraryRejectionCases")
        void libraryDirective_isRejected(String aLabel, String aDirectives,
                String aExpectedSubstring)
        {
            String content = scenario(aDirectives);
            RuleTestCdtException ex = assertThrows(RuleTestCdtException.class,
                    () -> RuleTestCdt.parse(content, "t"));
            assertTrue(ex.getMessage().contains(aExpectedSubstring), ex.getMessage());
        }

        // --- Round-trip --------------------------------------------------------


        /**
         * Fix #147 — {@code standard-domains} must round-trip its EMPTY state, not just its
         * populated one. For a {@code known_domain_only} expansion, "the library attests no
         * domains" (decidable, filters everything) and "no list served" (undecidable, skip with a
         * reason) are different verdicts, so a writer that dropped the empty declaration would
         * silently flip a scenario's expected outcome.
         */
        @Test
        void roundTrip_standardDomains_preservesTheEmptyDeclaration()
        {
            String content = scenario("""
                    #test CORE-1 expect=violation domain=AE
                    #library standard=adamig version=1-2
                    #library standard-domains""");
            String out = RuleTestCdt.toString(RuleTestCdt.parse(content, "orig"));
            assertTrue(out.contains("#library standard-domains"), out);

            MapBackedLibraryMetadataProvider lib = RuleTestCdt.parse(out, "rt").getLibrary();
            assertNotNull(lib);
            assertEquals(List.of(), lib.getStandardDatasetNames(),
                    "an empty declaration must survive as empty, never as null");
        }


        @Test
        void roundTrip_libraryDirective_omitsStandardDomainsWhenNeverDeclared()
        {
            String content = scenario("""
                    #test CORE-1 expect=violation domain=AE
                    #library standard=adamig version=1-2""");
            String out = RuleTestCdt.toString(RuleTestCdt.parse(content, "orig"));
            assertFalse(out.contains("standard-domains"), out);

            MapBackedLibraryMetadataProvider lib = RuleTestCdt.parse(out, "rt").getLibrary();
            assertNotNull(lib);
            assertNull(lib.getStandardDatasetNames(),
                    "an undeclared list must stay null — that is what makes the filter undecidable");
        }


        @Test
        void roundTrip_libraryDirective_preservesAllKinds()
        {
            String content = scenario("""
                    #test CORE-1 expect=violation domain=AE
                    #library standard=sdtmig version=3-4
                    #library required-variables AE STUDYID USUBJID
                    #library expected-variables AE AESEV
                    #library column-order AE STUDYID USUBJID
                    #library model-column-order AE STUDYID
                    #library custom-domain XX
                    #library codelist-terms NY Y N
                    #library published-ct-packages pkg1 pkg2
                    #library standard-domains AE CM DM
                    #library dataset-class AE EVENTS
                    #library domain-variables AE STUDYID:Topic
                    #library model-variables AE AETERM:Topic
                    #library model-class-variables EVENTS --TERM:Topic "--DECOD:Synonym Qualifier"
                    #library codelist-extensible C66742 false
                    #library codelist-term-mappings NY Y=Yes N=No
                    #library variable-metadata AE AETERM label="My Label" simpleDatatype=Char
                    #library codelist-codes LB LBTESTCD ALB=C64431
                    #library dataset-metadata DM structure="One per subject\"""");
            RuleTestScenario original = RuleTestCdt.parse(content, "orig");
            String out = RuleTestCdt.toString(original);
            assertTrue(out.contains("#library standard="), out);
            assertTrue(out.contains("#library required-variables AE"), out);
            assertTrue(out.contains("#library custom-domain"), out);
            assertTrue(out.contains("#library dataset-class AE EVENTS"), out);
            assertTrue(out.contains("#library codelist-extensible C66742 false"), out);
            assertTrue(out.contains("#library codelist-term-mappings NY"), out);
            assertTrue(out.contains("#library variable-metadata AE AETERM"), out);
            assertTrue(out.contains("#library dataset-metadata DM"), out);
            assertTrue(out.contains("#library standard-domains AE CM DM"), out);
            assertTrue(out.contains("#library codelist-codes LB LBTESTCD ALB=C64431"), out);
            assertTrue(out.contains(
                    "#library model-class-variables EVENTS --TERM:Topic \"--DECOD:Synonym Qualifier\""),
                    out);

            RuleTestScenario rt = RuleTestCdt.parse(out, "rt");
            MapBackedLibraryMetadataProvider lib = rt.getLibrary();
            assertNotNull(lib);
            assertEquals("sdtmig", lib.getStandard());
            assertEquals("3-4", lib.getVersion());
            assertEquals(List.of("STUDYID", "USUBJID"), lib.getRequiredVariables("AE"));
            assertEquals(List.of("AESEV"), lib.getExpectedVariables("AE"));
            assertEquals(List.of("STUDYID", "USUBJID"), lib.getColumnOrder("AE"));
            assertEquals(List.of("STUDYID"), lib.getModelColumnOrder("AE"));
            assertTrue(lib.isDomainCustom("XX"));
            assertEquals(List.of("Y", "N"), lib.getCodelistTerms("NY"));
            assertEquals(List.of("pkg1", "pkg2"), lib.getPublishedCtPackages());
            assertEquals(List.of("AE", "CM", "DM"), lib.getStandardDatasetNames());
            assertEquals("EVENTS", lib.getDatasetMetadata("AE").get("className"));
            assertEquals(1, lib.getDomainVariables("AE").size());
            assertEquals(1, lib.getModelVariables("AE").size());
            assertEquals(2, lib.getModelVariablesForClass("EVENTS").size());
            assertEquals("Synonym Qualifier",
                    lib.getModelVariablesForClass("EVENTS").get(1).get("role"));
            assertFalse(lib.isCodelistExtensible("C66742"));
            assertEquals("Yes", lib.getCodelistTermMappings("NY").get("Y"));
            assertEquals("No", lib.getCodelistTermMappings("NY").get("N"));
            assertEquals("My Label", lib.getVariableMetadata("AE", "AETERM").get("label"));
            assertEquals("Char", lib.getVariableMetadata("AE", "AETERM").get("simpleDatatype"));
            assertEquals("One per subject", lib.getDatasetMetadata("DM").get("structure"));
            assertEquals("C64431", lib.getCodelistCodeMap("LB", "LBTESTCD").get("ALB"));
        }


        @Test
        void writer_emitsEmptyLibraryAsShellWithStandardVersion()
        {
            // An empty library (the builder has just standard/version) still round-trips
            // as a non-null #library scalar line.
            RuleTestScenario scenarioWithEmptyLib = RuleTestScenario.builder().coreId("CORE-1")
                    .expect(Verdict.VIOLATION).domain("AE").dataset(makeAeTable()).source("mem")
                    .library(MapBackedLibraryMetadataProvider.empty()).build();

            String out = RuleTestCdt.toString(scenarioWithEmptyLib);
            assertTrue(out.contains("#library standard="), out);

            RuleTestScenario rt = RuleTestCdt.parse(out, "rt");
            assertNotNull(rt.getLibrary());
        }
    }


    @Nested
    class LibraryInclude
    {

        private static final String CDT_BODY = "dataset AE\ncol A type=Char\n---\nx\n---\n";

        private Path writePair(Path aDir, String aCdt, String aYaml) throws IOException
        {
            Files.writeString(aDir.resolve("lib.yaml"), aYaml);
            Path cdt = aDir.resolve("s.cdt");
            Files.writeString(cdt, aCdt);
            return cdt;
        }


        @Test
        void include_fromFilesystem_mergesSidecar(@TempDir Path aDir) throws IOException
        {
            Path cdt = writePair(aDir, """
                    #!RuleTest
                    #test CORE-1 expect=violation domain=AE
                    #library-include lib.yaml
                    """ + CDT_BODY, """
                    standard: sdtmig
                    version: "3-4"
                    domains:
                      AE:
                        required-variables: [ STUDYID, USUBJID ]
                        variable-metadata:
                          AETERM: { label: "Reported Term", simpleDatatype: Char }
                        codelist-codes:
                          AEDECOD: { "Headache": C123 }
                    codelists:
                      NY:
                        terms: [ Y, N ]
                        extensible: false
                        term-mappings: { Y: "Yes", N: "No" }
                    """);
            RuleTestScenario s = RuleTestCdt.load(cdt);
            MapBackedLibraryMetadataProvider lib = s.getLibrary();
            assertNotNull(lib);
            assertEquals("sdtmig", lib.getStandard());
            assertEquals("3-4", lib.getVersion());
            assertEquals(List.of("STUDYID", "USUBJID"), lib.getRequiredVariables("AE"));
            assertEquals("Reported Term", lib.getVariableMetadata("AE", "AETERM").get("label"));
            assertEquals("C123", lib.getCodelistCodeMap("AE", "AEDECOD").get("Headache"));
            assertEquals(List.of("Y", "N"), lib.getCodelistTerms("NY"));
            assertFalse(lib.isCodelistExtensible("NY"));
            assertEquals("Yes", lib.getCodelistTermMappings("NY").get("Y"));
        }


        @Test
        void include_fromClasspath_mergesSibling() throws IOException
        {
            RuleTestScenario s = RuleTestCdt
                    .loadResource("net/cumba/corej/ruletest/include_fixtures/include-sample.cdt");
            MapBackedLibraryMetadataProvider lib = s.getLibrary();
            assertNotNull(lib);
            assertEquals("sdtmig", lib.getStandard());
            assertEquals(List.of("sdtmct-2023-12-13"), lib.getPublishedCtPackages());
            assertTrue(lib.isDomainCustom("ZZ"));
            assertEquals("EVENTS", lib.getDatasetMetadata("AE").get("className"));
            assertEquals("One record per event", lib.getDatasetMetadata("AE").get("structure"));
            assertEquals(1, lib.getDomainVariables("AE").size());
            assertEquals("Req", lib.getVariableMetadata("AE", "AETERM").get("core"));
            assertFalse(lib.isCodelistExtensible("NY"));
        }


        @Test
        void inline_overridesSidecar_whenIncludeFirst(@TempDir Path aDir) throws IOException
        {
            Path cdt = writePair(aDir, """
                    #!RuleTest
                    #test CORE-1 expect=violation domain=AE
                    #library-include lib.yaml
                    #library version=3-4
                    #library required-variables AE C
                    """ + CDT_BODY, """
                    version: "1-0"
                    domains:
                      AE:
                        required-variables: [ A, B ]
                    """);
            MapBackedLibraryMetadataProvider lib = RuleTestCdt.load(cdt).getLibrary();
            assertNotNull(lib);
            assertEquals("3-4", lib.getVersion());
            assertEquals(List.of("C"), lib.getRequiredVariables("AE"));
        }


        @Test
        void inline_overridesSidecar_whenIncludeLast(@TempDir Path aDir) throws IOException
        {
            // Even when the include line comes AFTER the inline directives, inline still wins:
            // sidecars are always merged first, then inline applied (Decision D2).
            Path cdt = writePair(aDir, """
                    #!RuleTest
                    #test CORE-1 expect=violation domain=AE
                    #library version=3-4
                    #library required-variables AE C
                    #library-include lib.yaml
                    """ + CDT_BODY, """
                    version: "1-0"
                    domains:
                      AE:
                        required-variables: [ A, B ]
                    """);
            MapBackedLibraryMetadataProvider lib = RuleTestCdt.load(cdt).getLibrary();
            assertNotNull(lib);
            assertEquals("3-4", lib.getVersion());
            assertEquals(List.of("C"), lib.getRequiredVariables("AE"));
        }


        @Test
        void include_withoutBaseLocation_isRejected()
        {
            String content = scenario("""
                    #test CORE-1 expect=violation domain=AE
                    #library-include lib.yaml""");
            RuleTestCdtException ex = assertThrows(RuleTestCdtException.class,
                    () -> RuleTestCdt.parse(content, "t"));
            assertTrue(ex.getMessage().contains("requires loading from a file"), ex.getMessage());
        }


        @Test
        void include_missingSidecar_isRejected(@TempDir Path aDir) throws IOException
        {
            Path cdt = aDir.resolve("s.cdt");
            Files.writeString(cdt, """
                    #!RuleTest
                    #test CORE-1 expect=violation domain=AE
                    #library-include nope.yaml
                    """ + CDT_BODY);
            RuleTestCdtException ex = assertThrows(RuleTestCdtException.class,
                    () -> RuleTestCdt.load(cdt));
            assertTrue(ex.getMessage().contains("nope.yaml"), ex.getMessage());
        }


        @Test
        void include_extraToken_isRejected()
        {
            String content = scenario("""
                    #test CORE-1 expect=violation domain=AE
                    #library-include a.yaml b.yaml""");
            RuleTestCdtException ex = assertThrows(RuleTestCdtException.class,
                    () -> RuleTestCdt.parse(content, "t", _ -> null));
            assertTrue(ex.getMessage().contains("exactly one path"), ex.getMessage());
        }


        @Test
        void include_unknownYamlKey_isRejected(@TempDir Path aDir) throws IOException
        {
            Path cdt = writePair(aDir, """
                    #!RuleTest
                    #test CORE-1 expect=violation domain=AE
                    #library-include lib.yaml
                    """ + CDT_BODY, "flavour: spicy\n");
            RuleTestCdtException ex = assertThrows(RuleTestCdtException.class,
                    () -> RuleTestCdt.load(cdt));
            assertTrue(ex.getMessage().contains("unknown top-level key 'flavour'"),
                    ex.getMessage());
        }


        @Test
        void include_nonMappingRoot_isRejected(@TempDir Path aDir) throws IOException
        {
            Path cdt = writePair(aDir, """
                    #!RuleTest
                    #test CORE-1 expect=violation domain=AE
                    #library-include lib.yaml
                    """ + CDT_BODY, "- just\n- a\n- list\n");
            RuleTestCdtException ex = assertThrows(RuleTestCdtException.class,
                    () -> RuleTestCdt.load(cdt));
            assertTrue(ex.getMessage().contains("must be a YAML mapping"), ex.getMessage());
        }


        @Test
        void include_emptyDocument_yieldsNonNullLibrary(@TempDir Path aDir) throws IOException
        {
            Path cdt = writePair(aDir, """
                    #!RuleTest
                    #test CORE-1 expect=violation domain=AE
                    #library-include lib.yaml
                    """ + CDT_BODY, "");
            assertNotNull(RuleTestCdt.load(cdt).getLibrary());
        }


        static Stream<Arguments> malformedYamlCases()
        {
            return Stream.of(
                    Arguments.of("standard not scalar", "standard: [ a, b ]\n",
                            "standard must be a scalar value"),
                    Arguments.of("custom-domains not a list", "custom-domains: foo\n",
                            "custom-domains must be a list"),
                    Arguments.of("custom-domains non-scalar entry",
                            "custom-domains: [ { a: b } ]\n",
                            "custom-domains entries must be scalar values"),
                    Arguments.of("domains not a mapping", "domains: [ 1, 2 ]\n",
                            "'domains' must be a mapping"),
                    Arguments.of("domain not a mapping", "domains:\n  AE: nope\n",
                            "domain 'AE' must be a mapping"),
                    Arguments.of("variables not a list", "domains:\n  AE:\n    variables: foo\n",
                            "AE.variables must be a list"),
                    Arguments.of("variable-metadata not a mapping",
                            "domains:\n  AE:\n    variable-metadata: [ 1 ]\n",
                            "AE.variable-metadata must be a mapping"),
                    Arguments.of("codelist-codes not a mapping",
                            "domains:\n  AE:\n    codelist-codes: [ 1 ]\n",
                            "AE.codelist-codes must be a mapping"),
                    Arguments.of("codelist-codes non-scalar code",
                            "domains:\n  AE:\n    codelist-codes: { AEDECOD: { T: [ 1 ] } }\n",
                            "AE.codelist-codes.AEDECOD.T must be a scalar value"),
                    Arguments.of("dataset-metadata not a mapping",
                            "domains:\n  AE:\n    dataset-metadata: [ 1 ]\n",
                            "AE.dataset-metadata must be a mapping"),
                    Arguments.of("dataset-metadata non-scalar value",
                            "domains:\n  AE:\n    dataset-metadata: { k: [ 1, 2 ] }\n",
                            "AE.dataset-metadata.k must be a scalar value"),
                    Arguments.of("codelist not a mapping", "codelists:\n  NY: nope\n",
                            "codelist 'NY' must be a mapping"),
                    Arguments.of("extensible not boolean",
                            "codelists:\n  NY:\n    extensible: maybe\n",
                            "extensible must be true or false"));
        }


        @ParameterizedTest(name = "{0}")
        @MethodSource("malformedYamlCases")
        void include_malformedYaml_isRejected(String aLabel, String aYaml,
                String aExpectedSubstring, @TempDir Path aDir)
            throws IOException
        {
            Path cdt = writePair(aDir, """
                    #!RuleTest
                    #test CORE-1 expect=violation domain=AE
                    #library-include lib.yaml
                    """ + CDT_BODY, aYaml);
            RuleTestCdtException ex = assertThrows(RuleTestCdtException.class,
                    () -> RuleTestCdt.load(cdt));
            assertTrue(ex.getMessage().contains(aExpectedSubstring), ex.getMessage());
        }


        @Test
        void include_keyWithWhitespace_roundTripsThroughWriter(@TempDir Path aDir)
            throws IOException
        {
            Path cdt = writePair(aDir, """
                    #!RuleTest
                    #test CORE-1 expect=violation domain=AE
                    #library-include lib.yaml
                    """ + CDT_BODY, """
                    domains:
                      AE:
                        dataset-metadata: { "my key": value }
                    """);
            RuleTestScenario s = RuleTestCdt.load(cdt);
            assertEquals("value", s.getLibrary().getDatasetMetadata("AE").get("my key"));
            // Flattened to an inline #library directive on write, it must re-parse identically.
            RuleTestScenario rt = RuleTestCdt.parse(RuleTestCdt.toString(s), "rt");
            assertEquals("value", rt.getLibrary().getDatasetMetadata("AE").get("my key"));
        }


        @Test
        void include_keyWithEquals_isRejected(@TempDir Path aDir) throws IOException
        {
            Path cdt = writePair(aDir, """
                    #!RuleTest
                    #test CORE-1 expect=violation domain=AE
                    #library-include lib.yaml
                    """ + CDT_BODY, """
                    domains:
                      AE:
                        dataset-metadata: { "a=b": v }
                    """);
            RuleTestCdtException ex = assertThrows(RuleTestCdtException.class,
                    () -> RuleTestCdt.load(cdt));
            assertTrue(ex.getMessage().contains("must not contain '='"), ex.getMessage());
        }
    }


    @Nested
    class LoaderPaths
    {

        @Test
        void loadResource_reads_existingClasspathFile()
        {
            // loadResource takes a classpath path and we cannot inject a fresh
            // classpath resource from a test, so this case only verifies the
            // negative path where the resource is missing. The positive path is
            // exercised by other tests that load via Path instead.
            assertThrows(IOException.class, () -> RuleTestCdt
                    .loadResource("net/cumba/corej/ruletest/cdt/ruletest/does-not-exist.cdt"));
        }


        @Test
        void load_andWriteThenLoad_roundTripFromDisk(@TempDir Path tmp) throws IOException
        {
            Path file = tmp.resolve("sc.cdt");
            Files.writeString(file, """
                    #!RuleTest
                    #test CORE-1 expect=violation domain=AE
                    """ + MIN_DATASET, StandardCharsets.UTF_8);

            RuleTestScenario s = RuleTestCdt.load(file);
            assertEquals("CORE-1", s.getCoreId());

            Path out = tmp.resolve("out.cdt");
            RuleTestCdt.write(s, out);
            assertTrue(Files.exists(out));
            assertTrue(Files.readString(out, StandardCharsets.UTF_8).startsWith("#!RuleTest"));

            // round-trip read of the freshly-written file
            RuleTestScenario reloaded = RuleTestCdt.load(out);
            assertEquals(s.getCoreId(), reloaded.getCoreId());
            assertEquals(s.getDomain(), reloaded.getDomain());
        }


        @Test
        void load_overwritesExistingFile(@TempDir Path tmp) throws IOException
        {
            Path file = tmp.resolve("sc.cdt");
            // Pre-create with old content; write() must overwrite.
            Files.writeString(file, "old content\n", StandardCharsets.UTF_8);

            RuleTestScenario s = RuleTestScenario.builder().coreId("CORE-2")
                    .expect(Verdict.NO_VIOLATION).domain("AE").dataset(makeAeTable()).source("mem")
                    .build();
            RuleTestCdt.write(s, file);

            String onDisk = Files.readString(file, StandardCharsets.UTF_8);
            assertFalse(onDisk.contains("old content"), onDisk);
            assertTrue(onDisk.startsWith("#!RuleTest"), onDisk);
        }
    }


    @Nested
    class MultiDataset
    {

        @Test
        void duplicateDatasetNames_caseInsensitive_isRejected()
        {
            String content = """
                    #!RuleTest
                    #test CORE-1 expect=violation domain=AE
                    dataset AE
                    col A
                    ---
                    x
                    ---

                    dataset Ae
                    col B
                    ---
                    y
                    ---
                    """;
            RuleTestCdtException ex = assertThrows(RuleTestCdtException.class,
                    () -> RuleTestCdt.parse(content, "t"));
            assertTrue(ex.getMessage().contains("duplicate dataset name"), ex.getMessage());
        }


        @Test
        void domainCaseInsensitiveMatching_works()
        {
            String content = """
                    #!RuleTest
                    #test CORE-1 expect=violation domain=ae
                    dataset AE
                    col A
                    ---
                    x
                    ---
                    """;
            RuleTestScenario s = RuleTestCdt.parse(content, "t");
            assertEquals("ae", s.getDomain());
            assertNotNull(s.primaryTable());
        }
    }


    @Nested
    class VerdictParser
    {

        @ParameterizedTest
        @ValueSource(strings =
        {
                "violation", "VIOLATION", "Violation"
        })
        void verdict_violation_parsedCaseInsensitively(String aToken)
        {
            String content = scenario("#test CORE-1 expect=" + aToken + " domain=AE");
            assertEquals(Verdict.VIOLATION, RuleTestCdt.parse(content, "t").getExpect());
        }


        @ParameterizedTest
        @ValueSource(strings =
        {
                "noViolation", "NOVIOLATION", "no_violation", "NO_VIOLATION"
        })
        void verdict_noViolation_parsedCaseInsensitively(String aToken)
        {
            String content = scenario("#test CORE-1 expect=" + aToken + " domain=AE");
            assertEquals(Verdict.NO_VIOLATION, RuleTestCdt.parse(content, "t").getExpect());
        }


        @Test
        void verdict_valuesAndValueOf_basicEnumContract()
        {
            assertEquals(3, Verdict.values().length);
            assertEquals(Verdict.VIOLATION, Verdict.valueOf("VIOLATION"));
            assertEquals(Verdict.NO_VIOLATION, Verdict.valueOf("NO_VIOLATION"));
            assertEquals(Verdict.SKIPPED, Verdict.valueOf("SKIPPED"));
        }


        @Test
        void verdict_tokenRoundTripsThroughParseForEveryConstant()
        {
            // token() is the writer's inverse of parse(): every constant must survive a
            // write/read cycle, or rewriting a scenario would silently change its contract.
            for (Verdict v : Verdict.values())
            {
                String content = """
                        #!RuleTest
                        #test CORE-1 expect=%s domain=AE
                        dataset AE
                        col A
                        ---
                        x
                        ---
                        """.formatted(v.token());
                assertEquals(v, RuleTestCdt.parse(content, "t").getExpect(), v.token());
            }
        }
    }

    // ---- helpers --------------------------------------------------------------------

    private static net.cumba.datatable.impl.support.OverlayDataTable makeAeTable()
    {
        net.cumba.datatable.impl.support.OverlayDataTable t = net.cumba.datatable.impl.support.OverlayDataTable
                .empty("AE", "AE", 1);
        t.addColumn("A");
        t.setValue(0, "A", "x");
        return t;
    }
}
