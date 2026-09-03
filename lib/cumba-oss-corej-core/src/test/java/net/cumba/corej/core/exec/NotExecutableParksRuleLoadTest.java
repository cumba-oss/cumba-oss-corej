package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * {@code plans/done/PLAN-not-executable-parks-a-rule.md} — {@code Fix #159}.
 *
 * <p>
 * {@code Executability: "Not Executable"} used to be a load-guard <b>severity switch</b> and
 * nothing else: a rule could declare itself not executable and then load, execute, report findings
 * and be counted. It now <b>parks</b> the rule — {@code RulePackageLoader.removeParkedRules} drops
 * it from the package before any other load pass runs, so it never normalises, never validates and
 * never executes.
 * </p>
 *
 * <p>
 * ⚠ <b>Every fixture here is otherwise completely clean.</b> Not one carries a dangling
 * {@code $}-operand, an unresolved {@code --} wildcard, an invalid enum or a missing required field
 * — a rule that would <em>also</em> be rejected for a second reason makes this test and that gate
 * mutually untestable, and cannot distinguish "parked" from "rejected". The
 * {@link #anOtherwiseIdenticalExecutableRuleLoadsAndRuns} control is the same rule byte-for-byte
 * apart from the {@code Executability} value, and it must load <em>and produce a violation</em>: if
 * it did not, an empty package would prove nothing about the parking.
 * </p>
 */
class NotExecutableParksRuleLoadTest
{

    /** The prefix {@code removeParkedRules} puts on both its log lines. */
    private static final String MARKER = "[parked]";

    /**
     * A complete, load-clean rule body. Only {@code Core.Id}, the {@code Executability} block and
     * the flagged column vary between fixtures.
     */
    private static String ruleJson(String id, String executabilityBlock, String column)
    {
        return "{\"Core\": {\"Id\": \"" + id + "\"}," + executabilityBlock
                + " \"Sensitivity\": \"Record\"," + " \"Check\": {\"all\": [{\"name\": \"" + column
                + "\", \"operator\": \"non_empty\"}]},"
                + " \"Outcome\": {\"Message\": \"m\", \"Output_Variables\": [\"" + column + "\"]}}";
    }


    private static IDataTable table()
    {
        return MockTable.of().col("USUBJID", "S1").col("AETERM", "HEADACHE").name("ADAE").build();
    }


    private static RulePackage load(String... ruleBodies) throws IOException
    {
        StringBuilder sb = new StringBuilder("{\"rules\": {");
        for (int i = 0; i < ruleBodies.length; i++)
        {
            sb.append(i == 0 ? "" : ", ").append('"').append("k").append(i).append("\": ")
                    .append(ruleBodies[i]);
        }
        return RulePackageLoader.loadFromString(sb.append("}}").toString());
    }

    // -----------------------------------------------------------------------
    // The control — the same rule, executable, must load AND fire
    // -----------------------------------------------------------------------


    @Test
    void anOtherwiseIdenticalExecutableRuleLoadsAndRuns() throws IOException
    {
        RulePackage pkg = load(
                ruleJson("TEST-PARK-CTL", " \"Executability\": \"Fully Executable\",", "AETERM"));

        assertEquals(1, pkg.getRules().size(), "the control rule loads");
        Rule rule = pkg.getRules().values().iterator().next();
        assertNull(rule.getLoadError(), () -> "fixture must be load-clean: " + rule.getLoadError());
        assertNull(rule.getLoadWarning(),
                () -> "fixture must be load-clean: " + rule.getLoadWarning());
        // ⚠ Not just "loads": it must actually check something, or "the parked twin produced no
        // violation" would be a fact about the fixture rather than about the parking.
        RuleExecutionResult result = RuleRunner.execute(rule, table());
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus());
        assertEquals(1, result.getViolationCount(), "the control fires on the fixture table");
    }

    // -----------------------------------------------------------------------
    // The parking itself
    // -----------------------------------------------------------------------


    @Test
    void aNotExecutableRuleIsNotLoaded() throws IOException
    {
        RulePackage pkg = load(
                ruleJson("TEST-PARK-1", " \"Executability\": \"Not Executable\",", "AETERM"));

        assertTrue(pkg.getRules().isEmpty(),
                () -> "the parked rule must not be in the package: " + pkg.getRules().keySet());
    }


    @Test
    void theRestOfThePackageStillLoads() throws IOException
    {
        RulePackage pkg = load(
                ruleJson("TEST-PARK-2A", " \"Executability\": \"Not Executable\",", "AETERM"),
                ruleJson("TEST-PARK-2B", " \"Executability\": \"Fully Executable\",", "AETERM"),
                ruleJson("TEST-PARK-2C", "", "AETERM"));

        assertEquals(2, pkg.getRules().size(),
                () -> "only the parked rule is dropped: " + pkg.getRules().keySet());
        List<String> ids = pkg.getRules().values().stream().map(r -> r.getCore().getId()).sorted()
                .toList();
        assertEquals(List.of("TEST-PARK-2B", "TEST-PARK-2C"), ids);
    }


    @Test
    void everyOtherExecutabilityValueStillLoads() throws IOException
    {
        // ⚠ Only the full declaration parks. A partially-executable rule still claims that what it
        // does check, it checks — and an absent Executability is not a declaration of anything.
        RulePackage pkg = load(
                ruleJson("TEST-PARK-3A", " \"Executability\": \"Partially Executable\",", "AETERM"),
                ruleJson("TEST-PARK-3B",
                        " \"Executability\":"
                                + " \"Partially Executable - Possible Underreporting\",",
                        "AETERM"),
                ruleJson("TEST-PARK-3C",
                        " \"Executability\":"
                                + " \"Partially Executable - Possible Overreporting\",",
                        "AETERM"),
                ruleJson("TEST-PARK-3D", " \"Executability\": \"Fully Executable\",", "AETERM"),
                ruleJson("TEST-PARK-3E", "", "AETERM"));

        // All four non-parking enum values plus the absent case, so the claim is about the whole
        // enum rather than about the subset that happened to be listed.
        assertEquals(5, pkg.getRules().size(),
                () -> "nothing but \"Not Executable\" parks: " + pkg.getRules().keySet());
    }

    // -----------------------------------------------------------------------
    // The warning channel — one summary per package, detail at DEBUG
    // -----------------------------------------------------------------------


    @Test
    void oneWarningPerPackage_namingTheCountAndTheIds() throws IOException
    {
        // ⚠ 4B is authored BEFORE 4A so the `.sorted()` in the summary is observable: with the
        // package's own order the two ids would come out reversed, and dropping the sort would
        // leave this test green.
        List<String> lines = capture(Level.WARNING, () -> load(
                ruleJson("TEST-PARK-4B", " \"Executability\": \"Not Executable\",", "AETERM"),
                ruleJson("TEST-PARK-4A", " \"Executability\": \"Not Executable\",", "AETERM"),
                ruleJson("TEST-PARK-4C", " \"Executability\": \"Fully Executable\",", "AETERM")));

        assertEquals(1, lines.size(),
                () -> "two parked rules must still be ONE warning line: " + lines);
        String line = lines.getFirst();
        assertTrue(line.contains("2 rules"), () -> "the summary carries the count: " + line);
        assertTrue(line.contains("TEST-PARK-4A") && line.contains("TEST-PARK-4B"),
                () -> "the summary names every parked id: " + line);
        assertTrue(line.indexOf("TEST-PARK-4A") < line.indexOf("TEST-PARK-4B"),
                () -> "the ids are sorted, so the line is deterministic: " + line);
        assertFalse(line.contains("TEST-PARK-4C"),
                () -> "a loaded rule is not named as parked: " + line);
    }


    @Test
    void aPackageThatParksNothingIsSilent() throws IOException
    {
        assertEquals(List.of(), capture(Level.ALL, () -> load(
                ruleJson("TEST-PARK-5", " \"Executability\": \"Fully Executable\",", "AETERM"))));
    }


    @Test
    void theDebugLineCarriesTheExecutabilityHintDetail() throws IOException
    {
        String hint = "needs two-hop foreign date arithmetic the engine cannot express";
        List<String> lines = capture(Level.ALL, () -> load("{\"Core\": {\"Id\": \"TEST-PARK-6\"},"
                + " \"Executability\": \"Not Executable\","
                + " \"ExecutabilityHint\": {\"Category\": \"not executable\"," + " \"Detail\": \""
                + hint + "\"}," + " \"Sensitivity\": \"Record\","
                + " \"Check\": {\"all\": [{\"name\": \"AETERM\","
                + " \"operator\": \"equal_to\", \"value\": \"HEADACHE\"}]},"
                + " \"Outcome\": {\"Message\": \"m\"," + " \"Output_Variables\": [\"AETERM\"]}}"));

        String detail = lines.stream().filter(l -> l.contains("TEST-PARK-6") && l.contains(hint))
                .findFirst().orElse(null);
        assertNotNull(detail, () -> "the per-rule DEBUG line must carry the hint detail: " + lines);
    }


    @Test
    void aParkedRuleWithNoHintSaysSo() throws IOException
    {
        // Without a hint the field degrades into a silent delete. Naming the omission is what makes
        // that visible in the run.
        List<String> lines = capture(Level.ALL, () -> load(
                ruleJson("TEST-PARK-7", " \"Executability\": \"Not Executable\",", "AETERM")));

        assertTrue(
                lines.stream()
                        .anyMatch(l -> l.contains("TEST-PARK-7")
                                && l.contains("no ExecutabilityHint declared")),
                () -> "a hintless parked rule must say so: " + lines);
    }


    @Test
    void aBlankDetailFallsBackToTheHintCategory() throws IOException
    {
        // A hint object that carries only a Category still says more than nothing, so the line
        // reports the Category rather than claiming no hint was declared. ⚠ Blank, not absent:
        // an empty string must not be echoed as the reason.
        List<String> lines = capture(Level.ALL, () -> load(hintedRule("TEST-PARK-8",
                "\"Category\": \"not executable\", \"Detail\": \"   \"")));

        assertTrue(
                lines.stream()
                        .anyMatch(l -> l.contains("TEST-PARK-8")
                                && l.contains("ExecutabilityHint Category: not executable")),
                () -> "a blank Detail falls back to the Category: " + lines);
    }


    @Test
    void aHintWithNeitherFieldUsableIsReportedAsNoHint() throws IOException
    {
        // Present-but-empty is indistinguishable from absent for the reader, so it gets the same
        // wording — the alternative is a line that trails off after the em dash.
        List<String> lines = capture(Level.ALL,
                () -> load(hintedRule("TEST-PARK-9", "\"Category\": \"\", \"Detail\": \"\"")));

        assertTrue(
                lines.stream()
                        .anyMatch(l -> l.contains("TEST-PARK-9")
                                && l.contains("no ExecutabilityHint declared")),
                () -> "an empty hint reads as no hint: " + lines);
    }


    @Test
    void aParkedRuleWithNoCoreIdFallsBackToTheMapKey() throws IOException
    {
        // ⚠ Core.Id first, map key only as a fallback — the two are NOT interchangeable. On the
        // CDISC-Library path the key is the rule's UUID, so keying the summary off it would name
        // something the reader cannot act on. But a body with no `Core` at all would otherwise
        // read "<unknown>", and a two-rule summary would degrade to "<unknown>, <unknown>".
        List<String> lines = capture(Level.WARNING,
                () -> RulePackageLoader.loadFromString("{\"rules\": {\"the-map-key\": {"
                        + " \"Executability\": \"Not Executable\","
                        + " \"Sensitivity\": \"Record\","
                        + " \"Check\": {\"all\": [{\"name\": \"AETERM\","
                        + " \"operator\": \"non_empty\"}]}," + " \"Outcome\": {\"Message\": \"m\","
                        + " \"Output_Variables\": [\"AETERM\"]}}}}"));

        assertEquals(1, lines.size(), () -> "one summary line: " + lines);
        assertTrue(lines.getFirst().contains("the-map-key"),
                () -> "an id-less rule falls back to the map key: " + lines);
        assertFalse(lines.getFirst().contains("<unknown>"),
                () -> "and never reports the <unknown> literal: " + lines);
    }


    /** A parked rule carrying the given raw {@code ExecutabilityHint} body. */
    private static String hintedRule(String id, String hintBody)
    {
        return "{\"Core\": {\"Id\": \"" + id + "\"}," + " \"Executability\": \"Not Executable\","
                + " \"ExecutabilityHint\": {" + hintBody + "}," + " \"Sensitivity\": \"Record\","
                + " \"Check\": {\"all\": [{\"name\": \"AETERM\", \"operator\": \"non_empty\"}]},"
                + " \"Outcome\": {\"Message\": \"m\", \"Output_Variables\": [\"AETERM\"]}}";
    }

    /** A body that may throw the checked {@link IOException} the loader declares. */
    @FunctionalInterface
    private interface LoadingBody
    {

        void run() throws IOException;

    }

    /**
     * Runs {@code body} with a handler attached to {@link RulePackageLoader}'s logger at
     * {@code level}, and returns the {@link #MARKER}-carrying lines it emitted. Filtering on the
     * marker keeps unrelated loader chatter out of the counts.
     */
    private static List<String> capture(Level level, LoadingBody body) throws IOException
    {
        CapturingHandler handler = new CapturingHandler();
        handler.setLevel(Level.ALL);
        Logger juli = Logger.getLogger(RulePackageLoader.class.getName());
        Level previous = juli.getLevel();
        juli.addHandler(handler);
        juli.setLevel(level);
        try
        {
            body.run();
        }
        finally
        {
            juli.removeHandler(handler);
            juli.setLevel(previous);
        }
        return handler.formatted().stream().filter(l -> l.contains(MARKER)).toList();
    }

    /** Collects the {@link LogRecord}s {@link RulePackageLoader}'s class logger emits. */
    private static final class CapturingHandler extends Handler
    {

        private final List<LogRecord> records = new ArrayList<>();

        /** The captured records with their {@code {0}} placeholders substituted. */
        List<String> formatted()
        {
            return records.stream()
                    .map(r -> MessageFormat.format(r.getMessage(), r.getParameters())).toList();
        }


        @Override
        public void publish(LogRecord logRecord)
        {
            records.add(logRecord);
        }


        @Override
        public void flush()
        {
            // no-op
        }


        @Override
        public void close()
        {
            // no-op
        }

    }

}
