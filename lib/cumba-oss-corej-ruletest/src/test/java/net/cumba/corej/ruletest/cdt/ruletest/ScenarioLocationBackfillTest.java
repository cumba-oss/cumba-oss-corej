package net.cumba.corej.ruletest.cdt.ruletest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.Violation;
import net.cumba.datatable.report.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Coverage for {@link ScenarioLocationBackfill}'s in-place text insertion: directives land in the
 * canonical position, comments and data rows are preserved verbatim, the skip rules hold, and a
 * re-run replaces rather than appends.
 */
class ScenarioLocationBackfillTest
{

    private static final String VIOLATION = """
            #!RuleTest
            #test CORE-1 expect=violation domain=AE
            # Rule:Description: keep me verbatim
            #note "a note"
            dataset AE
            col USUBJID type=Char
            col AESEQ   type=Num
            ---
            001 |    1
            002 |    2
            ---
            """;

    private static Path write(Path aDir, String aContent) throws IOException
    {
        Path f = aDir.resolve("scenario.cdt");
        Files.writeString(f, aContent);
        return f;
    }


    @Test
    void valueBased_insertsDirectives_preservingCommentsAndData(@TempDir Path dir)
        throws IOException
    {
        Path f = write(dir, VIOLATION);
        RuleTestScenario s = RuleTestCdt.parse(VIOLATION, f.toString());
        Violation v = new Violation(0, Map.of(), "001", "1");

        ScenarioLocationBackfill.run(f, s, List.of(v), 1, false, true);

        String out = Files.readString(f);
        assertTrue(out.contains("#expectViolationCount 1"), out);
        assertTrue(out.contains("#expectViolationAt row=1"), out);
        // Comment and data rows survive verbatim (the round-trip writer would have
        // dropped/reformatted
        // these — text insertion must not).
        assertTrue(out.contains("# Rule:Description: keep me verbatim"), out);
        assertTrue(out.contains("001 |    1"), out);
        // Directives sit between the prelude and the dataset block.
        assertTrue(out.indexOf("#expectViolationCount") < out.indexOf("dataset AE"), out);
        assertTrue(out.indexOf("#note") < out.indexOf("#expectViolationCount"), out);
        // The result re-parses cleanly and verifies against the same violation.
        RuleTestScenario reparsed = RuleTestCdt.parse(out, f.toString());
        assertTrue(ViolationLocationCheck.verify(reparsed, List.of(v), 1, false, s.primaryTable())
                .pass());
    }


    /**
     * ⛔⛔ <b>F6 — a back-fill run must not erase a {@code severity=} pin, and must not lose
     * {@code #runLevel}.</b>
     *
     * <p>
     * {@link ScenarioLocationBackfill} <em>strips</em> every {@code #expectViolationAt} line and
     * re-emits from {@link ViolationLocationCheck#toExpectations}, which did not carry the
     * violation's level — so one opt-in run would have deleted every {@code severity=} pin in the
     * corpus. Nothing would have gone red: a {@code null} expected severity means "not pinned", so
     * the weakened fixture still passes. This test is the pin on the pin.
     * </p>
     */
    @Test
    void backfillRoundTripsTheSeverityPinAndTheRunLevel(@TempDir Path dir) throws IOException
    {
        String pinned = """
                #!RuleTest
                #test CORE-1 expect=violation domain=AE
                #runLevel Info
                #expectViolationCount 2
                #expectViolationAt row=1 severity=ERROR
                #expectViolationAt row=2 severity=INFO
                dataset AE
                col USUBJID type=Char
                col AESEQ   type=Num
                ---
                001 |    1
                002 |    2
                ---
                """;
        Path f = write(dir, pinned);
        RuleTestScenario s = RuleTestCdt.parse(pinned, f.toString());
        assertEquals(Severity.INFO, s.getRunLevel(), "fixture must carry the run level");

        List<Violation> observed = List.of(
                new Violation(0, Map.of(), "001", "1", Map.of(), Severity.ERROR, null),
                new Violation(1, Map.of(), "002", "2", Map.of(), Severity.INFO, null));
        ScenarioLocationBackfill.run(f, s, observed, 2, false, true);

        String out = Files.readString(f);
        assertEquals(2, out.split("severity=", -1).length - 1,
                () -> "both severity pins must survive the back-fill:\n" + out);
        assertTrue(out.contains("#runLevel Info"),
                () -> "#runLevel is not an #expectViolation* line and must survive verbatim:\n"
                        + out);

        // ...and the rewritten fixture still verifies against the very violations it was written
        // from — a pin that no longer discriminates would pass this too, which is why the count of
        // `severity=` tokens above is asserted separately.
        RuleTestScenario reparsed = RuleTestCdt.parse(out, f.toString());
        assertTrue(ViolationLocationCheck.verify(reparsed, observed, 2, false, s.primaryTable())
                .pass(), out);

        // The teeth: the re-emitted pins still REJECT the wrong level.
        List<Violation> swapped = List.of(
                new Violation(0, Map.of(), "001", "1", Map.of(), Severity.INFO, null),
                new Violation(1, Map.of(), "002", "2", Map.of(), Severity.ERROR, null));
        assertFalse(
                ViolationLocationCheck.verify(reparsed, swapped, 2, false, s.primaryTable()).pass(),
                "a back-filled severity= pin that matches anything is an erased pin with extra"
                        + " steps");
    }


    /** A single-level rule stamps no level, so no {@code severity=} is invented for it. */
    @Test
    void backfillInventsNoSeverityForAnUnstampedViolation(@TempDir Path dir) throws IOException
    {
        Path f = write(dir, VIOLATION);
        RuleTestScenario s = RuleTestCdt.parse(VIOLATION, f.toString());

        ScenarioLocationBackfill.run(f, s, List.of(new Violation(0, Map.of(), "001", "1")), 1,
                false, true);

        String out = Files.readString(f);
        assertFalse(out.contains("severity="),
                () -> "the ~8 800 single-level fixtures must be byte-unaffected:\n" + out);
    }


    @Test
    void rerun_isIdempotent(@TempDir Path dir) throws IOException
    {
        Path f = write(dir, VIOLATION);
        RuleTestScenario s = RuleTestCdt.parse(VIOLATION, f.toString());
        Violation v = new Violation(0, Map.of(), "001", "1");

        ScenarioLocationBackfill.run(f, s, List.of(v), 1, false, true);
        String first = Files.readString(f);
        // Re-parse and re-run: a second pass must replace, not append.
        RuleTestScenario s2 = RuleTestCdt.parse(first, f.toString());
        ScenarioLocationBackfill.run(f, s2, List.of(v), 1, false, true);
        String second = Files.readString(f);

        assertEquals(first, second);
        assertEquals(1, second.split("#expectViolationCount", -1).length - 1);
    }


    @Test
    void noViolationScenario_isUnchanged(@TempDir Path dir) throws IOException
    {
        String content = VIOLATION.replace("expect=violation", "expect=noViolation");
        Path f = write(dir, content);
        RuleTestScenario s = RuleTestCdt.parse(content, f.toString());

        ScenarioLocationBackfill.run(f, s, List.of(), 0, false, true);

        assertEquals(content, Files.readString(f));
    }


    @Test
    void crlfFile_keepsCrlf_noMixedEndings(@TempDir Path dir) throws IOException
    {
        String crlf = VIOLATION.replace("\n", "\r\n");
        Path f = write(dir, crlf);
        RuleTestScenario s = RuleTestCdt.parse(crlf, f.toString());
        Violation v = new Violation(0, Map.of(), "001", "1");

        ScenarioLocationBackfill.run(f, s, List.of(v), 1, false, true);

        String out = Files.readString(f);
        assertTrue(out.contains("#expectViolationCount 1"), out);
        // Every newline is CRLF — no lone "\n" introduced by the inserted directives.
        assertFalse(out.replace("\r\n", "").contains("\n"), "found a bare LF: " + out);
    }


    @Test
    void zeroFire_isSkipped(@TempDir Path dir) throws IOException
    {
        Path f = write(dir, VIOLATION);
        RuleTestScenario s = RuleTestCdt.parse(VIOLATION, f.toString());

        // expect=violation but the rule fired nothing — writing count 0 would be invalid, so skip.
        ScenarioLocationBackfill.run(f, s, List.of(), 0, false, true);

        String out = Files.readString(f);
        assertEquals(VIOLATION, out);
        assertFalse(out.contains("#expectViolation"), out);
    }
}
