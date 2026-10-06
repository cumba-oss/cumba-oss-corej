package net.cumba.corej.ruletest.cdt.ruletest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.ruletest.cdt.ruletest.RunEnvironment.StoreAvailability;
import net.cumba.corej.ruletest.cdt.ruletest.ScenarioRunner.Outcome;
import net.cumba.corej.ruletest.cdt.ruletest.ScenarioRunner.ScenarioRunResult;
import net.cumba.corej.ruletest.cdt.ruletest.ScenarioRunner.SkipReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ScenarioRunner} over an inline rule and inline scenarios: the three outcomes and the
 * mapping table on the class — every row the old JUnit harness reached through an assertion or an
 * assumption is an explicit outcome here.
 */
class ScenarioRunnerTest
{

    private static final String PACKAGE = """
            {"rules":{"CDISC-XX0001":{"Core":{"Id":"CDISC-XX0001","Status":"Published",
            "Version":"1"},"Executability":"Fully Executable",
            "Scope":{"Domains":{"Include":["AE"]}},
            "Check":{"expression":"AESEV == \\"MILD\\" and AESER == \\"Y\\""},
            "Outcome":{"Message":"a mild serious event"}}}}
            """;

    private static final String AE = """
            dataset AE label="Adverse Events"
            col STUDYID type=Char
            col USUBJID type=Char
            col AESEV type=Char
            col AESER type=Char
            ---
            S1 | 01 | MILD | Y
            S1 | 02 | SEVERE | N
            ---
            """;

    private static Rule rule() throws IOException
    {
        Rule rule = RulePackageLoader.loadFromString(PACKAGE).getRules().get("CDISC-XX0001");
        assertNotNull(rule);
        assertNull(rule.getLoadError(), rule.getLoadError());
        return rule;
    }


    private static RuleTestScenario scenario(String aPrelude, String aBody)
    {
        return RuleTestCdt.parse("#!RuleTest\n" + aPrelude + aBody, "inline.cdt", null);
    }


    private static RunEnvironment environment(StoreAvailability aStore)
    {
        return new RunEnvironment(aStore, null, null);
    }

    private static final StoreAvailability NO_STORE = StoreAvailability.unavailable("no store",
            "no CDISC metadata store in this test", false);

    @Test
    void anExpectedViolationPasses() throws IOException
    {
        List<String> seen = new ArrayList<>();
        ScenarioRunResult r = ScenarioRunner.run(rule(),
                scenario("#test CDISC-XX0001 expect=violation domain=AE\n", AE), null,
                environment(NO_STORE), (ctx, rule, result) -> seen.add(ctx));
        assertEquals(Outcome.PASS, r.outcome(), r.detail());
        assertEquals("OK", r.code());
        assertTrue(r.scopeGate().inScope());
        assertNotNull(r.evaluation());
        assertTrue(r.evaluation().violated());
        assertEquals(1, r.evaluation().totalCount());
        assertEquals(1, r.results().size());
        assertNull(r.skip());
        assertEquals(List.of("scenario AE / AE"), seen, "the observer saw the one execution");
    }


    @Test
    void aWrongExpectationFailsWithTheVerdictDetail() throws IOException
    {
        ScenarioRunResult r = ScenarioRunner.run(rule(),
                scenario("#test CDISC-XX0001 expect=noViolation domain=AE\n", AE), null,
                environment(NO_STORE), null);
        assertEquals(Outcome.FAIL, r.outcome());
        assertEquals("VERDICT", r.code());
        assertNotNull(r.verdict());
        assertFalse(r.verdict().pass());
        assertEquals(r.verdict().detail(), r.detail());
    }


    @Test
    void aMissingPrimaryDatasetFails() throws IOException
    {
        // The parser refuses a domain= that names no dataset, so the runner's own guard is only
        // reachable with a scenario rebuilt around the parser: a NO_PRIMARY stop, never a crash.
        RuleTestScenario s = scenario("#test CDISC-XX0001 expect=violation domain=AE\n", AE)
                .toBuilder().domain("DM").build();
        ScenarioRunResult r = ScenarioRunner.run(rule(), s, null, environment(NO_STORE), null);
        assertEquals(Outcome.FAIL, r.outcome());
        assertEquals("NO_PRIMARY", r.code());
        assertTrue(r.detail().contains("declares domain=DM"), r.detail());
    }


    @Test
    void theScopeGateRejectsViolationAndNoViolationAndAcceptsSkipped() throws IOException
    {
        String cm = AE.replace("dataset AE label=\"Adverse Events\"", "dataset CM label=\"CM\"");
        ScenarioRunResult violation = ScenarioRunner.run(rule(),
                scenario("#test CDISC-XX0001 expect=violation domain=CM\n", cm), null,
                environment(NO_STORE), null);
        assertEquals(Outcome.FAIL, violation.outcome());
        assertEquals("SCOPE", violation.code());
        assertFalse(violation.scopeGate().inScope());
        assertNotNull(violation.scopeGate().mismatch());

        ScenarioRunResult none = ScenarioRunner.run(rule(),
                scenario("#test CDISC-XX0001 expect=noViolation domain=CM\n", cm), null,
                environment(NO_STORE), null);
        assertEquals(Outcome.FAIL, none.outcome());
        assertEquals("SCOPE", none.code());
        assertTrue(none.detail().contains("declare 'expect=skipped'"), none.detail());

        ScenarioRunResult skipped = ScenarioRunner.run(rule(),
                scenario("#test CDISC-XX0001 expect=skipped domain=CM\n", cm), null,
                environment(NO_STORE), null);
        assertEquals(Outcome.PASS, skipped.outcome());
        assertFalse(skipped.scopeGate().inScope());
        assertNotNull(skipped.skip());
        assertEquals(SkipReason.Kind.SCOPE, skipped.skip().kind());
        assertNull(skipped.evaluation(), "the rule never ran");
    }


    /**
     * ⚠ The pre-existing hole, preserved so the lift moves nothing: an out-of-scope primary with
     * {@code expect=executionError} passes without the rule running.
     * {@code PLAN-scenario-scope-gate-execution-error} closes it; when it does, this test flips to
     * {@code FAIL} / {@code SCOPE}.
     */
    @Test
    void theScopeGateStillAcceptsExecutionErrorOnAnOutOfScopePrimary() throws IOException
    {
        String cm = AE.replace("dataset AE label=\"Adverse Events\"", "dataset CM label=\"CM\"");
        ScenarioRunResult r = ScenarioRunner.run(rule(),
                scenario("#test CDISC-XX0001 expect=executionError domain=CM\n", cm), null,
                environment(NO_STORE), null);
        assertEquals(Outcome.PASS, r.outcome());
        assertFalse(r.scopeGate().inScope());
    }


    @Test
    void aLibraryRefWithoutAStoreAbortsAndAFatalStoreFails() throws IOException
    {
        String ref = "#test CDISC-XX0001 expect=violation domain=AE\n"
                + "#library-ref standard=sdtmig version=3-4\n";
        ScenarioRunResult aborted = ScenarioRunner.run(rule(), scenario(ref, AE), null,
                environment(NO_STORE), null);
        assertEquals(Outcome.ABORTED, aborted.outcome());
        assertEquals("NO_STORE", aborted.code());
        assertTrue(aborted.detail().endsWith("— skipping CDISC-XX0001"), aborted.detail());

        ScenarioRunResult failed = ScenarioRunner.run(rule(), scenario(ref, AE), null,
                environment(StoreAvailability.unavailable("the configured store x",
                        "the configured store x cannot serve #library-ref scenarios", true)),
                null);
        assertEquals(Outcome.FAIL, failed.outcome());
        assertEquals("STORE", failed.code());
        assertTrue(failed.detail().endsWith("— failing CDISC-XX0001"), failed.detail());

        // a plain scenario never consults the store
        ScenarioRunResult plain = ScenarioRunner.run(rule(),
                scenario("#test CDISC-XX0001 expect=violation domain=AE\n", AE), null,
                environment(StoreAvailability.unavailable("x", "y", true)), null);
        assertEquals(Outcome.PASS, plain.outcome());
    }


    @Test
    void anUnresolvableDefineXmlSidecarFails(@TempDir Path aDir) throws IOException
    {
        Path file = aDir.resolve("s.cdt");
        Files.writeString(file, "#!RuleTest\n", StandardCharsets.UTF_8);
        RuleTestScenario s = scenario(
                "#test CDISC-XX0001 expect=violation domain=AE\n#define-xml define.xml\n", AE);
        // no sidecar resolver at all
        ScenarioRunResult none = ScenarioRunner.run(rule(), s, file, environment(NO_STORE), null);
        assertEquals(Outcome.FAIL, none.outcome());
        assertEquals("SIDECAR", none.code());
        // a resolver, but the sidecar is absent
        ScenarioRunResult absent = ScenarioRunner.run(rule(), s, file,
                new RunEnvironment(NO_STORE, null, SidecarResolver.rootedAt(aDir)), null);
        assertEquals(Outcome.FAIL, absent.outcome());
        assertEquals("SIDECAR", absent.code());
        assertTrue(absent.detail().contains("define.xml"), absent.detail());
    }


    @Test
    void aDictionaryScenarioWithoutABundleFails() throws IOException
    {
        ScenarioRunResult r = ScenarioRunner.run(rule(),
                scenario("#test CDISC-XX0001 expect=violation domain=AE\n#dictionaries dummy\n",
                        AE),
                null, environment(NO_STORE), null);
        assertEquals(Outcome.FAIL, r.outcome());
        assertEquals("DICTIONARIES", r.code());
    }


    @Test
    void skipReasonsAreClassifiedFromTheEnginesWords()
    {
        assertEquals(SkipReason.Kind.LIBRARY_UNAVAILABLE,
                SkipReason.classify("SKIPPED: Rule skipped — no Library access", false).kind());
        assertEquals(SkipReason.Kind.DEFINE_UNAVAILABLE, SkipReason.classify(
                "SKIPPED: Rule skipped — no Define-XML metadata (rule requires define_* operands)",
                false).kind());
        assertEquals(SkipReason.Kind.DICTIONARY_UNAVAILABLE, SkipReason
                .classify("SKIPPED: x (the rule needs this external dictionary)", false).kind());
        assertEquals(SkipReason.Kind.NO_DATA, SkipReason
                .classify("SKIPPED: Rule skipped — library returned no data for x", false).kind());
        assertEquals(SkipReason.Kind.LEVEL, SkipReason.classify(
                "SKIPPED: Rule skipped — every declared check level (Error) is below the run"
                        + " severity threshold Warning",
                false).kind());
        assertEquals(SkipReason.Kind.PRECONDITION,
                SkipReason.classify("SKIPPED: Rule skipped — precondition not met", false).kind());
        assertEquals(SkipReason.Kind.REQUIREMENTS,
                SkipReason.classify(
                        "SKIPPED: Rule skipped — Requirements.Variables.All: AESEQ is absent",
                        false).kind());
        assertEquals(SkipReason.Kind.EXPANSION, SkipReason
                .classify("SKIPPED: no column matches the wildcard --STRESN", false).kind());
        assertEquals(SkipReason.Kind.ERROR,
                SkipReason.classify("ERROR: Cardinality n:1: DM has duplicate key", true).kind());
        assertEquals(SkipReason.Kind.OTHER,
                SkipReason.classify("SKIPPED: something", false).kind());
    }
}
