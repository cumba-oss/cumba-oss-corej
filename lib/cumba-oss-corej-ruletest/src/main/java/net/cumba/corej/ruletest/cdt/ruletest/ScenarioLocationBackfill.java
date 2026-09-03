package net.cumba.corej.ruletest.cdt.ruletest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.cumba.corej.core.exec.Violation;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestScenario.Verdict;
import net.cumba.corej.ruletest.cdt.ruletest.ViolationLocationCheck.Expectations;

/**
 * Opt-in batch helper that enriches existing {@code #!RuleTest} scenario files in place with the
 * location directives ({@code #expectViolationCount} / {@code #expectViolationAt}) the engine
 * currently produces. Activated by {@code -Dbackfill.locations=true}; with
 * {@code -Dbackfill.dryRun=true} it reports what it would write without touching the files.
 *
 * <p>
 * Driven from the scenario factories' {@code runScenario} so the rule runs through the
 * <em>exact</em> production path (wildcard expansion, library wiring) — the captured locations
 * therefore match what {@link ViolationLocationCheck} will later verify.
 * </p>
 *
 * <p>
 * The edit is a <em>text insertion</em>, not a load/round-trip rewrite: only the directive lines
 * are inserted into the original file (just before the first {@code #library} directive, or the
 * dataset block when there is none). This preserves every comment (e.g.
 * {@code # Rule:Description: …}) and the data rows' exact formatting, so the diff is minimal and
 * additive. It is idempotent: any pre-existing {@code #expectViolation*} lines are stripped first,
 * so a re-run replaces rather than appends.
 * </p>
 *
 * <h2>What it skips (left verdict-only)</h2>
 * <ul>
 * <li>{@code expect=noViolation} scenarios — nothing fires.</li>
 * <li>{@code #library-ref} scenarios — they need a live CDISC Library and are not part of automated
 * regression.</li>
 * <li>Scenarios where the rule fired zero times despite {@code expect=violation} — a pre-existing
 * engine anomaly; writing {@code #expectViolationCount 0} would contradict the verdict (and fail
 * the parser). These are logged so they surface for follow-up.</li>
 * </ul>
 */
public final class ScenarioLocationBackfill
{

    /** {@code -Dbackfill.locations=true} enables the back-fill branch in the factories. */
    public static final String FLAG = "backfill.locations";

    /** {@code -Dbackfill.dryRun=true} reports the directives without writing the files. */
    public static final String DRY_RUN = "backfill.dryRun";

    private ScenarioLocationBackfill()
    {
    }


    /** True iff {@code -Dbackfill.locations=true} was passed on the command line. */
    public static boolean isEnabled()
    {
        return Boolean.getBoolean(FLAG);
    }


    /**
     * Insert the location directives derived from {@code aObserved} into {@code aFile}. See the
     * class javadoc for the skip rules. No-op (but logged) for anomalies.
     *
     * @param aValueBased
     *            whether the rule's evaluation domain carries the ROW cursor — record-level rules
     *            are pinned by {@code row=}, per-domain rules by their projected output variables.
     */
    public static void run(Path aFile, RuleTestScenario aScenario, List<Violation> aObserved,
            long aCount, boolean aTruncated, boolean aValueBased)
        throws IOException
    {
        if (aScenario.getExpect() != Verdict.VIOLATION || aScenario.getLibraryRef() != null)
        {
            return;
        }
        if (aCount == 0)
        {
            System.out.println("backfill SKIP (zero fires but expect=violation): " + aFile);
            return;
        }
        Expectations e = ViolationLocationCheck.toExpectations(aObserved, aCount, aTruncated,
                aValueBased, aScenario.getDomain());
        List<String> directiveLines = renderDirectives(aScenario, e);
        if (Boolean.getBoolean(DRY_RUN))
        {
            System.out.println(
                    "backfill DRY " + aFile + " -> count=" + e.count() + " ats=" + e.ats().size());
            return;
        }
        String original = Files.readString(aFile, StandardCharsets.UTF_8);
        String updated = insertDirectives(original, directiveLines);
        Files.writeString(aFile, updated, StandardCharsets.UTF_8);
        System.out.println(
                "backfill WROTE " + aFile + " -> count=" + e.count() + " ats=" + e.ats().size());
    }


    /**
     * Render the {@code #expectViolation*} directive lines using {@link RuleTestCdt}'s own writer
     * (so quoting / ordering match exactly), by serialising a throwaway scenario and extracting
     * just those lines.
     */
    private static List<String> renderDirectives(RuleTestScenario aScenario, Expectations aExp)
    {
        RuleTestScenario tmp = aScenario.toBuilder().expectViolationCount(aExp.count())
                .clearExpectedViolations().expectedViolations(aExp.ats()).build();
        List<String> out = new ArrayList<>();
        for (String line : RuleTestCdt.toString(tmp).split("\n", -1))
        {
            if (line.startsWith("#expectViolation"))
            {
                out.add(line);
            }
        }
        return out;
    }


    /**
     * Return {@code aOriginal} with any existing {@code #expectViolation*} lines removed and
     * {@code aDirectiveLines} inserted at the canonical position (before the first {@code #library}
     * directive, else before the dataset block). Trailing newline is preserved.
     */
    private static String insertDirectives(String aOriginal, List<String> aDirectiveLines)
    {
        // Preserve the file's line terminator so a CRLF fixture stays CRLF (and the inserted
        // directives get the same terminator) instead of gaining mixed endings. "\r\n" / "\n" are
        // valid literal regexes (no metacharacters), so they double as the split pattern.
        String nl = aOriginal.indexOf("\r\n") >= 0 ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>();
        for (String line : aOriginal.split(nl, -1))
        {
            String t = stripBom(line.strip());
            if (t.startsWith("#expectViolationCount") || t.startsWith("#expectViolationAt"))
            {
                continue; // idempotent: drop prior directives before re-inserting
            }
            lines.add(line);
        }
        lines.addAll(insertIndex(lines), aDirectiveLines);
        return String.join(nl, lines);
    }


    /** First index that is a {@code #library} directive or the dataset block start. */
    private static int insertIndex(List<String> aLines)
    {
        for (int i = 0; i < aLines.size(); i++)
        {
            String t = stripBom(aLines.get(i).strip());
            if (t.isEmpty())
            {
                continue;
            }
            if (t.startsWith("#library"))
            {
                return i;
            }
            if (t.charAt(0) != '#')
            {
                return i; // dataset block (first non-comment, non-blank line)
            }
        }
        return aLines.size();
    }


    private static String stripBom(String aValue)
    {
        return aValue.startsWith("\uFEFF") ? aValue.substring(1) : aValue;
    }
}
