package net.cumba.corej.ruletest.cdt.ruletest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestScenario.Verdict;
import net.cumba.datatable.impl.support.OverlayDataTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RuleTestCdtTest
{

    // ---- hasShebang ---------------------------------------------------------------

    @Test
    void hasShebang_true_onFirstLine()
    {
        assertTrue(RuleTestCdt.hasShebang("#!RuleTest\ndataset X\ncol A\n---\n1\n"));
    }


    @Test
    void hasShebang_true_withBom()
    {
        assertTrue(RuleTestCdt.hasShebang("\uFEFF#!RuleTest\ndataset X\ncol A\n---\n1\n"));
    }


    @Test
    void hasShebang_true_afterLeadingBlankLines()
    {
        assertTrue(RuleTestCdt.hasShebang("\n\n   \n#!RuleTest\ndataset X\ncol A\n---\n1\n"));
    }


    @Test
    void hasShebang_false_plainCdt()
    {
        assertFalse(RuleTestCdt.hasShebang("dataset ADSL\ncol USUBJID\n---\n01-001\n"));
    }


    @Test
    void hasShebang_false_comment()
    {
        assertFalse(RuleTestCdt.hasShebang("# just a comment\ndataset X\ncol A\n---\n1\n"));
    }


    @Test
    void hasShebang_false_null()
    {
        assertFalse(RuleTestCdt.hasShebang(null));
    }


    @Test
    void hasShebang_false_empty()
    {
        assertFalse(RuleTestCdt.hasShebang(""));
    }

    // ---- parse: single-dataset ----------------------------------------------------


    @Test
    void parse_singleDataset_minimal()
    {
        String content = """
                #!RuleTest
                #test CORE-000012 expect=violation domain=AE
                #note "AEOCCUR must not exist in AE dataset"
                dataset AE
                col STUDYID type=Char
                col USUBJID type=Char
                col AEOCCUR type=Char
                ---
                CDISC01 | 01-701-1015 | Y
                ---
                """;
        RuleTestScenario s = RuleTestCdt.parse(content, "test.cdt");

        assertEquals("CORE-000012", s.getCoreId());
        assertEquals(Verdict.VIOLATION, s.getExpect());
        assertEquals("AE", s.getDomain());
        assertEquals("AEOCCUR must not exist in AE dataset", s.getNote());
        assertEquals(1, s.getDatasets().size());
        assertEquals("AE", s.getDatasets().get(0).getMetaData().getName());
        assertEquals(1, s.getDatasets().get(0).getRowCount());
        assertEquals("test.cdt", s.getSource());
    }


    @Test
    void parse_noViolation_canonicalised()
    {
        String content = """
                #!RuleTest
                #test CORE-000012 expect=noViolation domain=AE
                dataset AE
                col STUDYID type=Char
                ---
                CDISC01
                ---
                """;
        RuleTestScenario s = RuleTestCdt.parse(content, "t");
        assertEquals(Verdict.NO_VIOLATION, s.getExpect());
    }


    @Test
    void parse_skipped_verdict()
    {
        // The third verdict: the rule is expected NOT to execute. Its purpose is that a rule
        // gated off by Requirements.Variables reports zero violations, so before `skipped` existed
        // such
        // a scenario had to borrow `noViolation` — which asserted nothing at all.
        String content = """
                #!RuleTest
                #test CORE-000012 expect=skipped domain=AE
                dataset AE
                col STUDYID type=Char
                ---
                CDISC01
                ---
                """;
        RuleTestScenario s = RuleTestCdt.parse(content, "t");
        assertEquals(Verdict.SKIPPED, s.getExpect());
    }


    @Test
    void parse_skipped_caseInsensitive()
    {
        String content = """
                #!RuleTest
                #test CORE-000012 expect=SKIPPED domain=AE
                dataset AE
                col STUDYID type=Char
                ---
                CDISC01
                ---
                """;
        assertEquals(Verdict.SKIPPED, RuleTestCdt.parse(content, "t").getExpect());
    }


    @Test
    void roundTrip_skippedVerdict_preservesToken()
    {
        // The writer must emit a token `parse` accepts, or rewriting a scenario (the trimmer, the
        // location backfill) would silently downgrade its contract.
        String content = """
                #!RuleTest
                #test CORE-000012 expect=skipped domain=AE
                #note "AEOCCUR is absent, so the Requirements.Variables gate skips the rule"
                dataset AE
                col STUDYID type=Char
                ---
                CDISC01
                ---
                """;
        RuleTestScenario original = RuleTestCdt.parse(content, "t");
        String out = RuleTestCdt.toString(original);
        assertTrue(out.contains("expect=skipped"), "writer emits the skipped token: " + out);
        assertEquals(Verdict.SKIPPED, RuleTestCdt.parse(out, "rt").getExpect());
    }


    @Test
    void parse_invalidExpect_namesAllThreeTokens()
    {
        String content = """
                #!RuleTest
                #test CORE-000012 expect=maybe domain=AE
                dataset AE
                col STUDYID type=Char
                ---
                CDISC01
                ---
                """;
        RuleTestCdtException ex = assertThrows(RuleTestCdtException.class,
                () -> RuleTestCdt.parse(content, "t"));
        assertTrue(ex.getMessage().contains("skipped"),
                "the error lists the accepted tokens: " + ex.getMessage());
    }


    @Test
    void parse_expect_caseInsensitive()
    {
        String content = """
                #!RuleTest
                #test CORE-1 expect=VIOLATION domain=AE
                dataset AE
                col A
                ---
                x
                ---
                """;
        assertEquals(Verdict.VIOLATION, RuleTestCdt.parse(content, "t").getExpect());
    }


    @Test
    void parse_note_optional()
    {
        String content = """
                #!RuleTest
                #test CORE-1 expect=violation domain=AE
                dataset AE
                col A
                ---
                x
                ---
                """;
        assertNull(RuleTestCdt.parse(content, "t").getNote());
    }


    @Test
    void parse_bomTolerated()
    {
        String content = """
                \uFEFF#!RuleTest
                #test CORE-1 expect=violation domain=AE
                dataset AE
                col A
                ---
                x
                ---
                """;
        assertEquals("CORE-1", RuleTestCdt.parse(content, "t").getCoreId());
    }


    @Test
    void parse_shebangAfterBlankLines()
    {
        String content = """


                #!RuleTest
                #test CORE-1 expect=violation domain=AE
                dataset AE
                col A
                ---
                x
                ---
                """;
        assertEquals("CORE-1", RuleTestCdt.parse(content, "t").getCoreId());
    }


    @Test
    void parse_regularCommentInDirectiveBlock_isIgnored()
    {
        // A `# ...` line (hash+space) is a regular comment, not a directive.
        String content = """
                #!RuleTest
                # this is a comment and should be skipped
                #test CORE-1 expect=violation domain=AE
                # another comment
                #note "actual note"
                dataset AE
                col A
                ---
                x
                ---
                """;
        RuleTestScenario s = RuleTestCdt.parse(content, "t");
        assertEquals("CORE-1", s.getCoreId());
        assertEquals("actual note", s.getNote());
    }

    // ---- parse: multi-dataset -----------------------------------------------------


    @Test
    void parse_multiDataset_crossDomain()
    {
        String content = """
                #!RuleTest
                #test CORE-000008 expect=violation domain=DM
                #note "DM.DTHFL must be 'Y' when SS.SSSTRESC = 'DEAD'"

                dataset DM
                col STUDYID type=Char
                col USUBJID type=Char
                col DTHFL type=Char
                ---
                CDISC01 | 01-701-1015 | N
                ---

                dataset SS
                col STUDYID type=Char
                col USUBJID type=Char
                col SSSTRESC type=Char
                ---
                CDISC01 | 01-701-1015 | DEAD
                ---
                """;
        RuleTestScenario s = RuleTestCdt.parse(content, "t");

        assertEquals("DM", s.getDomain());
        assertEquals(2, s.getDatasets().size());
        assertEquals("DM", s.getDatasets().get(0).getMetaData().getName());
        assertEquals("SS", s.getDatasets().get(1).getMetaData().getName());
    }

    // ---- primaryTable + resolver --------------------------------------------------


    @Test
    void primaryTable_returnsDomainMatch()
    {
        String content = """
                #!RuleTest
                #test CORE-1 expect=violation domain=DM
                dataset DM
                col USUBJID
                ---
                U1
                ---

                dataset SS
                col USUBJID
                ---
                U1
                ---
                """;
        RuleTestScenario s = RuleTestCdt.parse(content, "t");
        OverlayDataTable primary = s.primaryTable();
        assertNotNull(primary);
        assertEquals("DM", primary.getMetaData().getName());
    }


    @Test
    void primaryTable_caseInsensitive()
    {
        String content = """
                #!RuleTest
                #test CORE-1 expect=violation domain=dm
                dataset DM
                col USUBJID
                ---
                U1
                ---
                """;
        RuleTestScenario s = RuleTestCdt.parse(content, "t");
        assertNotNull(s.primaryTable());
        assertEquals("DM", s.primaryTable().getMetaData().getName());
    }


    @Test
    void resolver_uppercaseLookup()
    {
        String content = """
                #!RuleTest
                #test CORE-1 expect=violation domain=DM
                dataset DM
                col USUBJID
                ---
                U1
                ---

                dataset SS
                col USUBJID
                ---
                U1
                ---
                """;
        RuleTestScenario s = RuleTestCdt.parse(content, "t");
        DatasetResolver.WithInventory r = s.resolver();
        assertNotNull(r.resolve("DM"));
        assertNotNull(r.resolve("dm"));
        assertNotNull(r.resolve("Ss"));
        assertEquals(Set.of("DM", "SS"), r.availableDatasets());
    }


    @Test
    void resolver_unknownDomain_returnsNull_noFallThrough()
    {
        String content = """
                #!RuleTest
                #test CORE-1 expect=violation domain=DM
                dataset DM
                col USUBJID
                ---
                U1
                ---
                """;
        RuleTestScenario s = RuleTestCdt.parse(content, "t");
        DatasetResolver.WithInventory r = s.resolver();
        assertNull(r.resolve("AE"));
        assertNull(r.resolve(null));
        assertEquals(Set.of("DM"), r.availableDatasets());
    }

    // ---- Error paths --------------------------------------------------------------


    private static Stream<Arguments> rejectionScenarios()
    {
        return Stream.of(Arguments.of("missingShebang", """
                dataset AE
                col A
                ---
                x
                ---
                """, "missing '#!RuleTest'"),
                Arguments.of("blankFile", "\n\n\n", "missing '#!RuleTest'"),
                Arguments.of("unknownDirective", """
                        #!RuleTest
                        #test CORE-1 expect=violation domain=AE
                        #frobnicate yes
                        dataset AE
                        col A
                        ---
                        x
                        ---
                        """, "unknown directive"),
                // #library is now implemented (Phase 3); pick a still-reserved keyword instead.
                Arguments.of("reservedDirective", """
                        #!RuleTest
                        #test CORE-1 expect=violation domain=AE
                        #setup whatever
                        dataset AE
                        col A
                        ---
                        x
                        ---
                        """, "reserved"), Arguments.of("duplicateTestDirective", """
                        #!RuleTest
                        #test CORE-1 expect=violation domain=AE
                        #test CORE-2 expect=violation domain=AE
                        dataset AE
                        col A
                        ---
                        x
                        ---
                        """, null), Arguments.of("missingTestDirective", """
                        #!RuleTest
                        #note "nope"
                        dataset AE
                        col A
                        ---
                        x
                        ---
                        """, "missing required #test"), Arguments.of("missingExpect", """
                        #!RuleTest
                        #test CORE-1 domain=AE
                        dataset AE
                        col A
                        ---
                        x
                        ---
                        """, null), Arguments.of("missingDomain", """
                        #!RuleTest
                        #test CORE-1 expect=violation
                        dataset AE
                        col A
                        ---
                        x
                        ---
                        """, null), Arguments.of("missingCoreId", """
                        #!RuleTest
                        #test expect=violation domain=AE
                        dataset AE
                        col A
                        ---
                        x
                        ---
                        """, "coreId"), Arguments.of("unknownTestKey", """
                        #!RuleTest
                        #test CORE-1 expect=violation domain=AE flavor=spicy
                        dataset AE
                        col A
                        ---
                        x
                        ---
                        """, "flavor"), Arguments.of("invalidExpectValue", """
                        #!RuleTest
                        #test CORE-1 expect=maybe domain=AE
                        dataset AE
                        col A
                        ---
                        x
                        ---
                        """, null));
    }


    @ParameterizedTest(name = "parse_{0}_rejected")
    @MethodSource("rejectionScenarios")
    void parse_rejected(String aScenario, String aContent, String aExpectedSubstring)
    {
        RuleTestCdtException ex = assertThrows(RuleTestCdtException.class,
                () -> RuleTestCdt.parse(aContent, "t"));
        if (aExpectedSubstring != null)
        {
            assertTrue(ex.getMessage().contains(aExpectedSubstring),
                    "scenario=" + aScenario + " message=" + ex.getMessage());
        }
        if ("unknownDirective".equals(aScenario))
        {
            assertTrue(ex.getMessage().contains("#frobnicate"), ex.getMessage());
        }
    }


    @Test
    void parse_domainDoesNotMatchAnyDataset_rejected()
    {
        String content = """
                #!RuleTest
                #test CORE-1 expect=violation domain=XX
                dataset AE
                col A
                ---
                x
                ---
                """;
        RuleTestCdtException ex = assertThrows(RuleTestCdtException.class,
                () -> RuleTestCdt.parse(content, "t"));
        assertTrue(ex.getMessage().contains("does not match any dataset"), ex.getMessage());
    }


    @Test
    void parse_duplicateDatasetNames_rejected()
    {
        String content = """
                #!RuleTest
                #test CORE-1 expect=violation domain=AE
                dataset AE
                col A
                ---
                x
                ---

                dataset ae
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
    void parse_errorLineNumber_pointsAtOriginalFileLine()
    {
        // The CDT body error is on line 7 of the original file (1-indexed):
        // 1: #!RuleTest
        // 2: #test CORE-1 expect=violation domain=AE
        // 3: (blank)
        // 4: dataset AE
        // 5: col A
        // 6: ---
        // 7: x | extraneous | columns
        // 8: ---
        String content = """
                #!RuleTest
                #test CORE-1 expect=violation domain=AE

                dataset AE
                col A
                ---
                x | extra | columns
                ---
                """;
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> RuleTestCdt.parse(content, "scenario.cdt"));
        // CdtLoader wraps CdtParseException in its own class, but the format is the same.
        assertTrue(ex.getMessage().contains("scenario.cdt:7:"),
                "expected line 7 in error, got: " + ex.getMessage());
    }


    @Test
    void parse_unterminatedQuotedString_rejected()
    {
        String content = """
                #!RuleTest
                #test CORE-1 expect=violation domain=AE
                #note "unterminated
                dataset AE
                col A
                ---
                x
                ---
                """;
        assertThrows(RuleTestCdtException.class, () -> RuleTestCdt.parse(content, "t"));
    }

    // ---- Round-trip via toString / parse ------------------------------------------


    @Test
    void roundTrip_singleDataset_preservesDirectivesAndData()
    {
        RuleTestScenario original = RuleTestCdt.parse("""
                #!RuleTest
                #test CORE-000012 expect=violation domain=AE
                #note "AEOCCUR must not exist"
                dataset AE
                col STUDYID type=Char
                col USUBJID type=Char
                col AEOCCUR type=Char
                ---
                CDISC01 | 01-701-1015 | Y
                ---
                """, "orig");

        String out = RuleTestCdt.toString(original);
        // Shebang + #test directive + #note directive are present in the output.
        assertTrue(out.startsWith("#!RuleTest\n"), out);
        assertTrue(out.contains("#test CORE-000012 expect=violation domain=AE"), out);
        assertTrue(out.contains("#note \"AEOCCUR must not exist\""), out);

        RuleTestScenario roundTripped = RuleTestCdt.parse(out, "rt");
        assertEquals(original.getCoreId(), roundTripped.getCoreId());
        assertEquals(original.getExpect(), roundTripped.getExpect());
        assertEquals(original.getDomain(), roundTripped.getDomain());
        assertEquals(original.getNote(), roundTripped.getNote());
        assertEquals(original.getDatasets().size(), roundTripped.getDatasets().size());

        OverlayDataTable a = original.getDatasets().get(0);
        OverlayDataTable b = roundTripped.getDatasets().get(0);
        assertEquals(a.getMetaData().getName(), b.getMetaData().getName());
        assertEquals(a.getRowCount(), b.getRowCount());
        assertEquals(a.getMetaData().getColumnCount(), b.getMetaData().getColumnCount());
        for (int c = 0; c < a.getMetaData().getColumnCount(); c++)
        {
            assertEquals(a.getMetaData().getColumn(c).getName(),
                    b.getMetaData().getColumn(c).getName());
            assertEquals(a.getValue(0, c), b.getValue(0, c));
        }
    }


    @Test
    void roundTrip_multiDataset_preservesOrderAndNames()
    {
        String content = """
                #!RuleTest
                #test CORE-000008 expect=noViolation domain=DM
                dataset DM
                col USUBJID type=Char
                col DTHFL type=Char
                ---
                U1 | Y
                ---

                dataset SS
                col USUBJID type=Char
                col SSSTRESC type=Char
                ---
                U1 | DEAD
                ---
                """;
        RuleTestScenario original = RuleTestCdt.parse(content, "orig");
        RuleTestScenario roundTripped = RuleTestCdt.parse(RuleTestCdt.toString(original), "rt");

        assertEquals(2, roundTripped.getDatasets().size());
        assertEquals("DM", roundTripped.getDatasets().get(0).getMetaData().getName());
        assertEquals("SS", roundTripped.getDatasets().get(1).getMetaData().getName());
        assertEquals(Verdict.NO_VIOLATION, roundTripped.getExpect());
        assertEquals("DM", roundTripped.getDomain());
    }


    @Test
    void roundTrip_noNote_doesNotEmitNoteDirective()
    {
        RuleTestScenario s = RuleTestCdt.parse("""
                #!RuleTest
                #test CORE-1 expect=violation domain=AE
                dataset AE
                col A
                ---
                x
                ---
                """, "t");
        String out = RuleTestCdt.toString(s);
        assertFalse(out.contains("#note"), out);
    }


    @Test
    void roundTrip_valueWithSpace_isQuoted()
    {
        // domain can't contain spaces in practice, but the note can.
        RuleTestScenario original = RuleTestScenario.builder().coreId("CORE-1")
                .expect(Verdict.VIOLATION).domain("AE").note("hello  world")
                .dataset(makeSingletonTable("AE")).source("mem").build();
        String out = RuleTestCdt.toString(original);
        assertTrue(out.contains("#note \"hello  world\""), out);
        RuleTestScenario rt = RuleTestCdt.parse(out, "rt");
        assertEquals("hello  world", rt.getNote());
    }


    @Test
    void plainCdtParser_stillParses_extendedFileAsMultiDataset()
    {
        // Guarantees §9.3 acceptance: extended-CDT files remain valid plain CDT.
        String content = """
                #!RuleTest
                #test CORE-1 expect=violation domain=AE
                #note "x"
                dataset AE
                col STUDYID type=Char
                ---
                CDISC01
                ---
                """;
        List<?> datasets = net.cumba.datatable.provider.cdt.CdtParser.parseAll(content, "t");
        assertEquals(1, datasets.size());
    }

    // ---- load / write round-trip --------------------------------------------------


    @Test
    void writeThenLoad_roundTrip(@TempDir Path tmp) throws Exception
    {
        RuleTestScenario original = RuleTestCdt.parse("""
                #!RuleTest
                #test CORE-000012 expect=violation domain=AE
                #note "something"
                dataset AE
                col USUBJID type=Char
                ---
                U1
                ---
                """, "orig");
        Path file = tmp.resolve("scenario.cdt");
        RuleTestCdt.write(original, file);

        assertTrue(Files.exists(file));
        String onDisk = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(onDisk.startsWith("#!RuleTest\n"), onDisk);

        RuleTestScenario loaded = RuleTestCdt.load(file);
        assertEquals(original.getCoreId(), loaded.getCoreId());
        assertEquals(original.getExpect(), loaded.getExpect());
        assertEquals(original.getDomain(), loaded.getDomain());
        assertEquals(original.getNote(), loaded.getNote());
        assertEquals(original.getDatasets().size(), loaded.getDatasets().size());
        assertEquals(file.toString(), loaded.getSource());
    }

    // ---- helpers ------------------------------------------------------------------


    private static OverlayDataTable makeSingletonTable(String aName)
    {
        OverlayDataTable t = OverlayDataTable.empty(aName, aName, 1);
        t.addColumn("A");
        t.setValue(0, "A", "x");
        return t;
    }


    // Guard against silent aliasing between tests (scenarios must not share dataset
    // lists). Not a correctness test, just a sanity check that parse() returns fresh
    // datasets each time.
    @Test
    void parse_returnsIndependentDatasets()
    {
        String content = """
                #!RuleTest
                #test CORE-1 expect=violation domain=AE
                dataset AE
                col A
                ---
                x
                ---
                """;
        RuleTestScenario a = RuleTestCdt.parse(content, "t");
        RuleTestScenario b = RuleTestCdt.parse(content, "t");
        assertNotNull(a.getDatasets().get(0));
        assertNotNull(b.getDatasets().get(0));
        // Different instances of OverlayDataTable
        assertNotSame(a.getDatasets().get(0), b.getDatasets().get(0));
        // …but equality of the resolver instance is not asserted (fresh each call).
        assertNotSame(a.resolver(), b.resolver());
    }

    // ---- Location directives (#expectViolationCount / #expectViolationAt) ----------

    private static final String LOC_PRELUDE = "#!RuleTest\n#test CORE-1 expect=violation domain=AE\n";

    private static final String LOC_DATASET = """
            dataset AE
            col USUBJID type=Char
            col AESEQ   type=Num
            col AESER   type=Char
            ---
            001 | 1 | Maybe
            ---
            """;

    private static RuleTestScenario parseLoc(String aDirectives)
    {
        return RuleTestCdt.parse(LOC_PRELUDE + aDirectives + "\n" + LOC_DATASET, "loc");
    }


    @Test
    void parse_expectViolationCount_only()
    {
        RuleTestScenario s = parseLoc("#expectViolationCount 3");
        assertEquals(Integer.valueOf(3), s.getExpectViolationCount());
        assertTrue(s.getExpectedViolations().isEmpty());
    }


    @Test
    void parse_absent_locationDirectives_leaveNulls()
    {
        RuleTestScenario s = parseLoc("#note plain");
        assertNull(s.getExpectViolationCount());
        assertTrue(s.getExpectedViolations().isEmpty());
    }


    @Test
    void parse_expectViolationAt_positionalRow()
    {
        RuleTestScenario s = parseLoc("#expectViolationAt row=3");
        assertEquals(1, s.getExpectedViolations().size());
        ExpectedViolation ev = s.getExpectedViolations().get(0);
        assertEquals(Integer.valueOf(3), ev.getRow());
        assertTrue(ev.getConstraints().isEmpty());
    }


    @Test
    void parse_expectViolationAt_valuePins()
    {
        RuleTestScenario s = parseLoc("#expectViolationAt USUBJID=003 AESEQ=1");
        ExpectedViolation ev = s.getExpectedViolations().get(0);
        assertNull(ev.getRow());
        assertEquals("003", ev.getConstraints().get("USUBJID"));
        assertEquals("1", ev.getConstraints().get("AESEQ"));
    }


    @Test
    void parse_expectViolationAt_rowPlusPins()
    {
        RuleTestScenario s = parseLoc("#expectViolationAt row=4 AESER=Maybe");
        ExpectedViolation ev = s.getExpectedViolations().get(0);
        assertEquals(Integer.valueOf(4), ev.getRow());
        assertEquals("Maybe", ev.getConstraints().get("AESER"));
    }


    @Test
    void parse_expectViolationAt_quotedValue()
    {
        RuleTestScenario s = parseLoc("#expectViolationAt VAR=\"a b\"");
        assertEquals("a b", s.getExpectedViolations().get(0).getConstraints().get("VAR"));
    }


    @Test
    void parse_multipleAt_withMatchingCount()
    {
        RuleTestScenario s = parseLoc("""
                #expectViolationCount 2
                #expectViolationAt row=1
                #expectViolationAt row=2""");
        assertEquals(Integer.valueOf(2), s.getExpectViolationCount());
        assertEquals(2, s.getExpectedViolations().size());
    }


    @Test
    void roundTrip_locationDirectives_preserved()
    {
        RuleTestScenario original = parseLoc("""
                #expectViolationCount 2
                #expectViolationAt row=2 AESER=Maybe
                #expectViolationAt USUBJID=003""");
        RuleTestScenario rt = RuleTestCdt.parse(RuleTestCdt.toString(original), "rt");
        assertEquals(original.getExpectViolationCount(), rt.getExpectViolationCount());
        // The writer sorts expectations, so compare order-independently.
        assertEquals(Set.copyOf(original.getExpectedViolations()),
                Set.copyOf(rt.getExpectedViolations()));
    }
}
