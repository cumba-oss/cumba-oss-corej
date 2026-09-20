package net.cumba.corej.core.report.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.cumba.corej.core.report.ReportSections;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * Wave 5 of the clinical-path campaign — the behaviours this module implements and documents but
 * did not assert.
 *
 * <p>
 * Every test here was written against a named surviving or uncovered mutant, not by reading the
 * code and guessing what might be worth covering. The three clusters, and why each was invisible:
 * </p>
 *
 * <ul>
 * <li><b>The {@code MAX_REPORT_ROWS} precedence rule.</b> Unreachable while {@code System.getenv}
 * was called inline — a test cannot set its own process's environment — so the blank check, the
 * malformed-value fallback and the "larger of the two wins" rule were asserted nowhere. Reached
 * through the seam added beside them.</li>
 * <li><b>Truncation.</b> The cap is applied through a private method, but it is reachable from the
 * public {@code write} with a directly-constructed writer, so no seam was needed — only a fixture
 * that actually exceeds the cap.</li>
 * <li><b>The truncation WARNING.</b> Observable on no other channel, which is why a mutant that
 * deleted the log call survived. Asserted in both directions: it fires when rows are dropped and it
 * does <em>not</em> fire when the row count merely equals the cap.</li>
 * </ul>
 *
 * <p>
 * ⚠ The last of those is the one to keep in mind when editing: "rows == cap" and "rows &gt; cap"
 * return the same <em>content</em>, so a test that only inspects the workbook cannot tell them
 * apart. The log is the only difference, and asserting its absence is as load-bearing as asserting
 * its presence.
 * </p>
 */
class XlsxReportWriterHardeningTest
{

    // ------------------------------------------------------------------
    // resolveMaxRows — the MAX_REPORT_ROWS environment contract
    // ------------------------------------------------------------------

    @Test
    void envIsIgnoredWhenUnsetBlankOrMalformed()
    {
        // All four spellings of "no usable environment value" must fall through to the argument.
        assertEquals(250, XlsxReportWriter.resolveMaxRows(250, null));
        assertEquals(250, XlsxReportWriter.resolveMaxRows(250, ""));
        assertEquals(250, XlsxReportWriter.resolveMaxRows(250, "   "));
        assertEquals(250, XlsxReportWriter.resolveMaxRows(250, "not-a-number"));
        // ...and with no argument either, to the documented default rather than to null.
        assertEquals(XlsxReportWriter.DEFAULT_MAX_ROWS,
                XlsxReportWriter.resolveMaxRows(null, "not-a-number"));
    }


    @Test
    void envAloneSetsTheCap()
    {
        assertEquals(500, XlsxReportWriter.resolveMaxRows(null, "500"));
        // Surrounding whitespace is trimmed before parsing — the value is read from a shell.
        assertEquals(500, XlsxReportWriter.resolveMaxRows(null, "  500  "));
    }


    /**
     * ⭐ The precedence rule the javadoc states — "the larger of the two wins" — asserted in both
     * directions, because a test of only one direction cannot distinguish {@code max} from either
     * operand winning outright.
     */
    @Test
    void envAndArgumentTogetherTakeTheLarger()
    {
        assertEquals(500, XlsxReportWriter.resolveMaxRows(250, "500"));
        assertEquals(250, XlsxReportWriter.resolveMaxRows(250, "100"));
        assertEquals(250, XlsxReportWriter.resolveMaxRows(250, "250"));
    }


    @Test
    void zeroMeansUnlimitedAndNegativeMeansDefault_fromTheEnvironmentToo()
    {
        // The same two escapes the argument has, reached through the environment branch.
        assertNull(XlsxReportWriter.resolveMaxRows(null, "0"));
        assertEquals(XlsxReportWriter.DEFAULT_MAX_ROWS,
                XlsxReportWriter.resolveMaxRows(null, "-5"));
        // ⚠ max() runs BEFORE the 0/negative escapes, so a 0 environment value does not force
        // unlimited when an argument is present — it loses to the larger argument.
        assertEquals(250, XlsxReportWriter.resolveMaxRows(250, "0"));
    }


    @Test
    void theEnvironmentOverloadIsWhatThePublicOneDelegatesTo()
    {
        // Pins that the public entry point is a pure delegation: with MAX_REPORT_ROWS almost
        // certainly unset in this JVM, both spellings must agree. If someone re-inlines the
        // getenv call, this stays green — it is the tests above that would go uncoverable again,
        // so this exists to document the relationship, not to guard it alone.
        assertEquals(XlsxReportWriter.resolveMaxRows(250, System.getenv("MAX_REPORT_ROWS")),
                XlsxReportWriter.resolveMaxRows(250));
    }

    // ------------------------------------------------------------------
    // truncate — the cap, and the warning that says it was applied
    // ------------------------------------------------------------------


    @Test
    void aZeroCapOnADirectlyConstructedWriterDropsEveryRow() throws Exception
    {
        // resolveMaxRows turns 0 into null (unlimited), so this state is only reachable by
        // constructing the writer directly — which the constructor allows and the truncate()
        // comment explicitly anticipates. 0 must mean "no rows", not "no truncation": the
        // difference is invisible unless the fixture has rows to lose.
        try (XSSFWorkbook wb = render(sectionsWithIssueRows(3), 0))
        {
            assertEquals(0, dataRowCount(wb.getSheet("Issue Details")),
                    "a cap of 0 truncates to nothing");
        }
    }


    @Test
    void aNegativeCapOnADirectlyConstructedWriterTruncatesNothing() throws Exception
    {
        // The other half of the guard: subList(0, -1) would throw, so a stray negative must be
        // treated as "no truncation" rather than reaching subList at all.
        try (XSSFWorkbook wb = render(sectionsWithIssueRows(3), -1))
        {
            assertEquals(3, dataRowCount(wb.getSheet("Issue Details")),
                    "a negative cap leaves the rows alone instead of throwing");
        }
    }


    @Test
    void rulesReportSheetHandlesEveryTemplateShape()
    {
        // A workbook without the sheet at all (a torn-down template): null, no throw.
        try (XSSFWorkbook empty = new XSSFWorkbook())
        {
            assertNull(XlsxReportWriter.rulesReportSheet(empty));

            // A sheet with NO header row (row 0 absent): the row is created and headed.
            empty.createSheet("Rules Report");
            Sheet headed = XlsxReportWriter.rulesReportSheet(empty);
            assertNotNull(headed);
            assertEquals("Executed", headed.getRow(0).getCell(6).getStringCellValue());

            // Second call: the headers are already present (non-blank string value), so the
            // sheet is used as-is — the branch a future template shipping the headers takes.
            // ⚠ This is the guard that regressed once: the shipped template carries
            // styled-but-EMPTY placeholder cells beyond the six real headers, and a
            // cell-non-null presence check skipped the append silently.
            Sheet again = XlsxReportWriter.rulesReportSheet(empty);
            assertSame(headed, again);
            assertEquals("Executed", again.getRow(0).getCell(6).getStringCellValue());
        }
        catch (java.io.IOException e)
        {
            throw new java.io.UncheckedIOException(e);
        }
    }


    @Test
    void rowsAreTruncatedToTheCapAndTheDropIsLogged()
    {
        List<String> warnings = capturingXlsxWarnings(() ->
        {
            try (XSSFWorkbook wb = render(sectionsWithIssueRows(5), 2))
            {
                assertEquals(2, dataRowCount(wb.getSheet("Issue Details")));
            }
            catch (Exception e)
            {
                throw new IllegalStateException(e);
            }
        });
        assertTrue(warnings.stream().anyMatch(w -> w.contains("truncated")),
                "dropping rows must say so: " + warnings);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("5")),
                "the warning must carry the true total, not the truncated count: " + warnings);
    }


    /**
     * ⛔ The asymmetric half, and the reason this test exists at all: when the row count
     * <em>equals</em> the cap, nothing is dropped and nothing may be logged. The returned content
     * is identical either way — {@code subList(0, size)} is the whole list — so the workbook cannot
     * distinguish the two and only the absence of a warning can. A boundary mutant that turned
     * {@code <=} into {@code <} survived precisely here.
     */
    @Test
    void aRowCountEqualToTheCapIsNotTruncatedAndIsNotLogged()
    {
        List<String> warnings = capturingXlsxWarnings(() ->
        {
            try (XSSFWorkbook wb = render(sectionsWithIssueRows(2), 2))
            {
                assertEquals(2, dataRowCount(wb.getSheet("Issue Details")));
            }
            catch (Exception e)
            {
                throw new IllegalStateException(e);
            }
        });
        assertFalse(warnings.stream().anyMatch(w -> w.contains("truncated")),
                "nothing was dropped, so nothing may claim it was: " + warnings);
    }

    // ------------------------------------------------------------------
    // skippedRulesSheet — the reuse branch the shipped template never takes
    // ------------------------------------------------------------------


    @Test
    void anExistingSkippedRulesSheetIsReusedRatherThanRecreated()
    {
        try (XSSFWorkbook wb = new XSSFWorkbook())
        {
            Sheet created = XlsxReportWriter.skippedRulesSheet(wb);
            assertNotNull(created);
            assertEquals(1, wb.getNumberOfSheets());
            // Mark the sheet so reuse is provable by more than identity.
            created.getRow(0).createCell(9).setCellValue("sentinel");

            Sheet again = XlsxReportWriter.skippedRulesSheet(wb);
            assertSame(created, again, "the second call must return the existing sheet");
            assertEquals(1, wb.getNumberOfSheets(), "and must not append a second one");
            assertEquals("sentinel", again.getRow(0).getCell(9).getStringCellValue(),
                    "reuse means the caller's content survives — a recreate would lose it");
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------
    // fillConformance — the row-creation branch the shipped template never takes
    // ------------------------------------------------------------------


    /**
     * A conformance key whose row is <em>absent</em> from the sheet is written at the right index.
     *
     * <p>
     * ⚠ The shipped template carries Conformance rows 1–23 and every {@code CONFORMANCE_ROWS} value
     * falls inside that range, so through the public {@code write} path {@code getRow} always hits
     * and the {@code createRow} branch beside it is dead — measured NO_COVERAGE. It is not dead
     * code: it is what keeps the writer working if a future template drops a row. Driving it needs
     * a sheet the template did not build.
     * </p>
     * <p>
     * ⭐ The assertion is on the row INDEX, not merely on the value being present somewhere:
     * {@code CONFORMANCE_ROWS} is 1-based and the POI API is 0-based, so the branch carries a
     * {@code - 1} that a mutant flipped to {@code + 1} with nothing noticing. Asserting "row 8
     * holds Standard" is what makes the off-by-two visible.
     * </p>
     */
    @Test
    void aConformanceRowMissingFromTheSheetIsCreatedAtTheRightIndex()
    {
        try (XSSFWorkbook wb = new XSSFWorkbook())
        {
            Sheet sheet = wb.createSheet("Conformance Details");
            // Deliberately empty: no rows at all, so every key takes the createRow branch.
            assertEquals(-1, sheet.getLastRowNum(), "fixture precondition: the sheet has no rows");

            new XlsxReportWriter(null).fillConformance(sheet,
                    new ReportSections(Map.of("Standard", "SDTMIG"), List.of(), List.of(),
                            List.of(), List.of(), List.of()));

            // "Standard" is row 9 in the 1-based template map, hence index 8 here.
            assertNotNull(sheet.getRow(8), "the missing row was created");
            assertEquals("SDTMIG", sheet.getRow(8).getCell(1).getStringCellValue());
            assertNull(sheet.getRow(10), "and only the row that was asked for");
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------


    private static XSSFWorkbook render(ReportSections aSections, Integer aMaxRows) throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new XlsxReportWriter(aMaxRows).write(aSections, out);
        return new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()));
    }


    /** Data rows only — row 0 is the template's header. */
    private static int dataRowCount(Sheet aSheet)
    {
        int n = 0;
        for (int r = 1; r <= aSheet.getLastRowNum(); r++)
        {
            if (aSheet.getRow(r) != null && aSheet.getRow(r).getCell(0) != null
                    && !aSheet.getRow(r).getCell(0).getStringCellValue().isEmpty())
            {
                n++;
            }
        }
        return n;
    }


    private static ReportSections sectionsWithIssueRows(int aCount)
    {
        List<Map<String, Object>> details = new ArrayList<>();
        for (int i = 0; i < aCount; i++)
        {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("core_id", "CORE-" + (100 + i));
            row.put("message", "finding " + i);
            row.put("dataset", "AE");
            row.put("domain", "AE");
            details.add(row);
        }
        return new ReportSections(Map.of("Standard", "SDTMIG"), List.of(), List.of(), details,
                List.of(), List.of());
    }


    /** Drains WARNING records from the writer's own logger while {@code aBody} runs. */
    private static List<String> capturingXlsxWarnings(Runnable aBody)
    {
        CapturingHandler handler = new CapturingHandler();
        handler.setLevel(Level.ALL);
        Logger juli = Logger.getLogger(XlsxReportWriter.class.getName());
        Level previous = juli.getLevel();
        juli.addHandler(handler);
        juli.setLevel(Level.ALL);
        try
        {
            aBody.run();
        }
        finally
        {
            juli.removeHandler(handler);
            juli.setLevel(previous);
        }
        return handler.formatted();
    }

    private static final class CapturingHandler extends Handler
    {

        private final List<String> records = new ArrayList<>();

        @Override
        public void publish(LogRecord aRecord)
        {
            Object[] params = aRecord.getParameters();
            String msg = aRecord.getMessage();
            if (params != null)
            {
                msg = java.text.MessageFormat.format(msg, params);
            }
            records.add(aRecord.getLevel() + " " + msg);
        }


        @Override
        public void flush()
        {
            // Nothing buffered.
        }


        @Override
        public void close()
        {
            // Nothing to release.
        }


        List<String> formatted()
        {
            return List.copyOf(records);
        }
    }
}
