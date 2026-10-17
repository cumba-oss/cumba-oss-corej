package net.cumba.corej.ruletest.cdt.ruletest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.RuleExecutionStatus;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.ruletest.cdt.ruletest.RunEnvironment.StoreAvailability;
import net.cumba.corej.ruletest.cdt.ruletest.ScenarioRunner.Outcome;
import net.cumba.corej.ruletest.cdt.ruletest.ScenarioRunner.ScenarioRunResult;
import org.junit.jupiter.api.Test;

/**
 * {@code #reported-absent DS [DS ...]} — the {@code .cdt} channel for the engine's
 * {@code reportedDatasets} ({@code Fix #222}; {@code PLAN-ruletest-scenario-channels} item 2): a
 * dataset the scenario declares absent and whose absence the run reports. The parser, the writer
 * round trip, and the end-to-end run of the {@code FDA-SE1148} shape (a TS read on EX), which SKIPs
 * only with the directive.
 */
class RuleTestCdtReportedAbsentTest
{

    private static final String EX = """
            dataset EX class=INTERVENTIONS label="Exposure"
            col USUBJID type=Char
            col EXSTDTC type=Char
            ---
            S1 | 2020-01-05
            ---
            """;

    private static final String DM = """

            dataset DM label="Demographics"
            col USUBJID type=Char
            ---
            S1
            ---
            """;

    /** The FDA-SE1148 shape: EX read against a TS parameter, TS not submitted. */
    private static final String PACKAGE = """
            {"rules":{"FDA-XX1148":{"Core":{"Id":"FDA-XX1148","Status":"Published",
            "Version":"1"},"Executability":"Fully Executable",
            "Scope":{"Domains":{"Include":["EX"]}},
            "Bindings":[{"name":"$exp_start","expression":"read_value(TSVAL, domain=TS, filter=(TSPARMCD==\\"EXPSTDTC\\"), mode=\\"FIRST\\")"}],
            "Check":{"expression":"not empty(EXSTDTC) and date(EXSTDTC) < date($exp_start)"},
            "Outcome":{"Message":"exposure before the planned start"}},
            "FDA-XX0001":{"Core":{"Id":"FDA-XX0001","Status":"Published",
            "Version":"1"},"Executability":"Fully Executable",
            "Scope":{"Domains":{"Include":["EX"]}},
            "Check":{"expression":"empty(EXSTDTC)"},
            "Outcome":{"Message":"no start"}}}}
            """;

    private static final StoreAvailability NO_STORE = StoreAvailability.unavailable("no store",
            "no CDISC metadata store in this test", false);

    private static RuleTestScenario parse(String aPrelude, String aBody)
    {
        return RuleTestCdt.parse("#!RuleTest\n" + aPrelude + aBody, "inline.cdt", null);
    }


    private static Rule rule(String aId) throws IOException
    {
        Rule rule = RulePackageLoader.loadFromString(PACKAGE).getRules().get(aId);
        assertNotNull(rule);
        assertNull(rule.getLoadError(), rule.getLoadError());
        return rule;
    }


    private static ScenarioRunResult run(String aId, String aPrelude) throws IOException
    {
        return ScenarioRunner.run(rule(aId), parse(aPrelude, EX), null,
                new RunEnvironment(NO_STORE, null, null), null);
    }

    // ---- parser and writer ---------------------------------------------------------------------


    @Test
    void theNamesAreUpperCasedAndWrittenBackInFileOrder()
    {
        String written = RuleTestCdt.toString(parse("""
                #test FDA-XX1148 expect=skipped domain=EX
                #reported-absent ts poolDef
                """, EX));
        assertTrue(written.contains("\n#reported-absent TS POOLDEF\n"), written);
        RuleTestScenario reparsed = RuleTestCdt.parse(written, "written.cdt", null);
        assertEquals(written, RuleTestCdt.toString(reparsed),
                "the writer's output is a fixed point");
    }


    @Test
    void aScenarioWithoutTheDirectiveWritesNoLine()
    {
        String written = RuleTestCdt
                .toString(parse("#test FDA-XX1148 expect=skipped domain=EX\n", EX));
        assertFalse(written.contains("#reported-absent"), written);
    }


    @Test
    void aMalformedDirectiveIsALoadErrorNamingItsLine()
    {
        String test = "#test FDA-XX1148 expect=skipped domain=EX\n";
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("#reported-absent\n", "inline.cdt:3: #reported-absent expects one or more");
        cases.put("#reported-absent TS ts\n",
                "inline.cdt:3: #reported-absent: duplicate dataset TS");
        cases.put("#reported-absent \"\"\n", "inline.cdt:3: #reported-absent: empty dataset name");
        cases.put("#reported-absent TS\n#reported-absent TA\n",
                "inline.cdt:4: duplicate #reported-absent directive");
        for (Map.Entry<String, String> c : cases.entrySet())
        {
            RuleTestCdtException e = assertThrows(RuleTestCdtException.class,
                    () -> parse(test + c.getKey(), EX), c.getKey());
            assertTrue(String.valueOf(e.getMessage()).startsWith(c.getValue()), e.getMessage());
        }
    }


    @Test
    void aDatasetTheScenarioSuppliesCannotBeDeclaredAbsent()
    {
        // The primary and a secondary dataset alike, case-insensitively: the engine silences only
        // a reading of a dataset that does not resolve, so the line would be a silent no-op.
        for (String name : new String[]
        {
                "ex", "DM"
        })
        {
            RuleTestCdtException e = assertThrows(RuleTestCdtException.class, () -> parse(
                    "#test FDA-XX1148 expect=skipped domain=EX\n#reported-absent TS " + name + "\n",
                    EX + DM));
            assertTrue(String.valueOf(e.getMessage())
                    .startsWith("inline.cdt:3: #reported-absent "
                            + name.toUpperCase(java.util.Locale.ROOT)
                            + ": the scenario supplies this dataset"),
                    e.getMessage());
        }
    }

    /** A split LB: two members, no standalone LB, each member's DOMAIN cell {@code LB}. */
    private static final String SPLIT_LB = """

            dataset LBCH label="Laboratory Test Results - Chemistry"
            col USUBJID type=Char
            col DOMAIN type=Char
            ---
            S1 | LB
            ---

            dataset LBHE label="Laboratory Test Results - Hematology"
            col USUBJID type=Char
            col DOMAIN type=Char
            ---
            S1 | LB
            ---
            """;

    /** A split SUPPAE family: two members, no standalone SUPPAE, each with {@code RDOMAIN = AE}. */
    private static final String SPLIT_SUPPAE = """

            dataset SUPPAE1 label="Supplemental Qualifiers for AE"
            col USUBJID type=Char
            col RDOMAIN type=Char
            col QNAM type=Char
            ---
            S1 | AE | AETRTEM
            ---

            dataset SUPPAE2 label="Supplemental Qualifiers for AE"
            col USUBJID type=Char
            col RDOMAIN type=Char
            col QNAM type=Char
            ---
            S1 | AE | AESOSP
            ---
            """;

    @Test
    void aSplitDomainTheScenarioSuppliesCannotBeDeclaredAbsent()
    {
        // LBCH + LBHE make LB present (Fix #358): the engine treats the split as supplied, so the
        // directive would silence nothing — the same silent no-op as an exactly supplied name.
        RuleTestCdtException e = assertThrows(RuleTestCdtException.class,
                () -> parse("#test FDA-XX1148 expect=skipped domain=EX\n#reported-absent TS lb\n",
                        EX + SPLIT_LB));
        assertTrue(String.valueOf(e.getMessage()).startsWith("inline.cdt:3: #reported-absent LB: "
                + "the scenario supplies this dataset as a split domain or supplemental family"),
                e.getMessage());
    }


    @Test
    void aSplitSupplementalFamilyTheScenarioSuppliesCannotBeDeclaredAbsent()
    {
        // SUPPAE1 + SUPPAE2 make SUPPAE present (PLAN-split-supp-resolution D2 / D3).
        RuleTestCdtException e = assertThrows(RuleTestCdtException.class,
                () -> parse("#test FDA-XX1148 expect=skipped domain=EX\n#reported-absent SUPPAE\n",
                        EX + SPLIT_SUPPAE));
        assertTrue(String.valueOf(e.getMessage()).startsWith("inline.cdt:3: #reported-absent "
                + "SUPPAE: the scenario supplies this dataset as a split domain or supplemental family"),
                e.getMessage());
    }


    @Test
    void aSplitOfAnotherDatasetDoesNotRejectTheDirective()
    {
        // The over-fire arm: the splits make LB and SUPPAE present, nothing else — TS is still
        // absent, so the directive loads and names it.
        RuleTestScenario s = parse(
                "#test FDA-XX1148 expect=skipped domain=EX\n#reported-absent TS SUPPLB\n",
                EX + SPLIT_LB + SPLIT_SUPPAE);
        assertEquals(java.util.List.of("TS", "SUPPLB"), s.getReportedAbsent());
    }

    // ---- end to end ----------------------------------------------------------------------------


    @Test
    void aReportedAbsentDatasetSilencesItsDependant() throws IOException
    {
        ScenarioRunResult r = run("FDA-XX1148", """
                #test FDA-XX1148 expect=skipped domain=EX
                #reported-absent TS
                """);
        assertEquals(Outcome.PASS, r.outcome(), r.detail());
        assertEquals(1, r.results().size());
        assertEquals(RuleExecutionStatus.SKIPPED, r.results().get(0).getStatus());
        String reason = String.valueOf(r.results().get(0).getStatusMessage());
        assertTrue(reason.contains("TS"), reason);
    }


    @Test
    void withoutTheDirectiveTheDependantRunsOnTheAbsentDataset() throws IOException
    {
        // The sensitivity arm: the same scenario minus the directive — the engine is given no
        // coverage, so the TS read answers a missing and the rule EXECUTES (no finding): the
        // Fix #222 SKIP is unreachable from a scenario without the channel.
        ScenarioRunResult r = run("FDA-XX1148", "#test FDA-XX1148 expect=skipped domain=EX\n");
        assertEquals(Outcome.FAIL, r.outcome(), r.detail());
        assertEquals("VERDICT", r.code(), r.detail());
        ScenarioRunResult ran = run("FDA-XX1148",
                "#test FDA-XX1148 expect=noViolation domain=EX\n");
        assertEquals(Outcome.PASS, ran.outcome(), ran.detail());
    }


    @Test
    void aRuleThatDoesNotReadTheDatasetStillRuns() throws IOException
    {
        // The SKIP is scoped to the dependency (K5b), never to the run: a rule with no TS reading
        // executes as before under the same directive.
        ScenarioRunResult r = run("FDA-XX0001", """
                #test FDA-XX0001 expect=noViolation domain=EX
                #reported-absent TS
                """);
        assertEquals(Outcome.PASS, r.outcome(), r.detail());
        assertEquals(RuleExecutionStatus.EXECUTED, r.results().get(0).getStatus());
    }
}
