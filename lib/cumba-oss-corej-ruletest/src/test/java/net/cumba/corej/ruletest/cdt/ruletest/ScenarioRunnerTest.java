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
import java.util.Optional;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.metadata.store.MetadataStore;
import net.cumba.corej.core.metadata.store.Presence;
import net.cumba.corej.core.metadata.store.StoreManifest;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.cumba.corej.core.metadata.store.StoredClass;
import net.cumba.corej.core.metadata.store.StoredCtPackage;
import net.cumba.corej.core.metadata.store.StoredDataStructure;
import net.cumba.corej.core.metadata.store.StoredDataset;
import net.cumba.corej.core.metadata.store.StoredProduct;
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
     * {@code PLAN-scenario-scope-gate-execution-error} C1: an out-of-scope primary with
     * {@code expect=executionError} is a {@code SCOPE} failure — the rule never runs, so it cannot
     * error; before the plan it passed while asserting nothing (the hole this test used to pin).
     */
    @Test
    void theScopeGateRejectsExecutionErrorOnAnOutOfScopePrimary() throws IOException
    {
        String cm = AE.replace("dataset AE label=\"Adverse Events\"", "dataset CM label=\"CM\"");
        ScenarioRunResult r = ScenarioRunner.run(rule(),
                scenario("#test CDISC-XX0001 expect=executionError domain=CM\n", cm), null,
                environment(NO_STORE), null);
        assertEquals(Outcome.FAIL, r.outcome(), r.detail());
        assertEquals("SCOPE", r.code());
        assertFalse(r.scopeGate().inScope());
        assertTrue(r.detail().contains("declares expect=executionError"), r.detail());
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


    /**
     * Review 8a-B L5: a refused {@code #define-xml} sidecar used to surface as "could not parse
     * #define-xml sidecar /abs/path" — the refusal's reason hidden, a server path shown. The
     * resolver's own words pass through now, tree-relative.
     */
    @Test
    void aRefusedDefineXmlSidecarSaysWhyAndNamesNoAbsolutePath(@TempDir Path aDir)
        throws IOException
    {
        Path dir = Files.createDirectories(aDir.resolve("suites").resolve("fam").resolve("R1"));
        Files.writeString(aDir.resolve("define.xml"), "<ODM/>", StandardCharsets.UTF_8);
        RuleTestScenario s = scenario("#test CDISC-XX0001 expect=violation domain=AE\n"
                + "#define-xml ../../../define.xml\n", AE);
        ScenarioRunResult r = ScenarioRunner.run(rule(), s, dir.resolve("s.cdt"),
                new RunEnvironment(NO_STORE, null,
                        SidecarResolver.rootedAt(aDir.resolve("suites"))),
                null);
        assertEquals(Outcome.FAIL, r.outcome());
        assertEquals("SIDECAR", r.code());
        assertTrue(r.detail().contains("escapes the scenario tree"), r.detail());
        assertFalse(r.detail().contains(aDir.toString()), r.detail());
        assertFalse(r.detail().contains(aDir.toRealPath().toString()), r.detail());
    }

    /** A store that serves one SDTMIG product (no datasets) and no CT package. */
    private static final class OneProductStore implements MetadataStore
    {

        private final boolean failing;

        OneProductStore(boolean aFailing)
        {
            failing = aFailing;
        }


        @Override
        public Optional<StoredProduct> product(String aKey)
        {
            if (failing)
            {
                throw new IllegalStateException("store defect in this test");
            }
            if (!"standards/sdtmig/3-4".equals(aKey))
            {
                return Optional.empty();
            }
            return Optional.of(StoredProduct.builder().key(aKey).name("SDTMIG").version("3.4")
                    .classes(List.<StoredClass> of()).datasets(List.<StoredDataset> of())
                    .dataStructures(List.<StoredDataStructure> of()).build());
        }


        @Override
        public Optional<StoredCtPackage> ctPackage(String aId)
        {
            return Optional.empty();
        }


        @Override
        public List<String> publishedCtPackages()
        {
            return List.of();
        }


        @Override
        public Presence presence(String aCtPackageId)
        {
            return Presence.ABSENT;
        }


        @Override
        public List<String> productCatalogue()
        {
            return List.of("standards/sdtmig/3-4");
        }


        @Override
        public StoreManifest manifest()
        {
            throw new UnsupportedOperationException("no manifest in the fake");
        }


        @Override
        public void close()
        {
            // nothing to release
        }
    }

    private static StoreAvailability store(boolean aFailing)
    {
        return StoreAvailability.of(new StoreMetadataProviderFactory(new OneProductStore(aFailing)),
                "the test store");
    }


    /** Review 8a-B L6: the library-axis stops, each one reached. */
    @Test
    void theLibraryAxisStopsAreEachReached() throws IOException
    {
        ScenarioRunResult notServed = ScenarioRunner.run(rule(),
                scenario("#test CDISC-XX0001 expect=violation domain=AE\n"
                        + "#library-ref standard=sdtmig version=3-3\n", AE),
                null, environment(store(false)), null);
        assertEquals(Outcome.ABORTED, notServed.outcome(), notServed.detail());
        assertEquals("PRODUCT_NOT_SERVED", notServed.code());
        assertTrue(notServed.detail().contains("the test store does not serve sdtmig 3-3"),
                notServed.detail());

        ScenarioRunResult ct = ScenarioRunner.run(rule(),
                scenario(
                        "#test CDISC-XX0001 expect=violation domain=AE\n"
                                + "#library-ref standard=sdtmig version=3-4 ct=sdtmct-2024-09-27\n",
                        AE),
                null, environment(store(false)), null);
        assertEquals(Outcome.FAIL, ct.outcome(), ct.detail());
        assertEquals("CT_PACKAGE", ct.code());
        assertTrue(ct.detail().contains("pins ct=sdtmct-2024-09-27"), ct.detail());
    }


    /**
     * Review 8a-B L6: a {@code #library-ref} scenario whose rule does not execute — and did not
     * error (the degraded-Library shape: the rule reports SKIPPED) — aborts
     * ({@code REF_NOT_EXECUTED}). An errored run is judged instead
     * ({@link #aLibraryRefScenarioWhoseRuleErroredIsJudged}).
     */
    @Test
    void aLibraryRefScenarioWhoseRuleDidNotRunAborts() throws IOException
    {
        Rule needsAbsent = RulePackageLoader
                .loadFromString(PACKAGE.replace("\"Check\":",
                        "\"Requirements\":{\"Variables\":{\"All\":[\"AEXXX\"]}},\"Check\":"))
                .getRules().get("CDISC-XX0001");
        assertNull(needsAbsent.getLoadError(), needsAbsent.getLoadError());
        ScenarioRunResult r = ScenarioRunner.run(needsAbsent,
                scenario("#test CDISC-XX0001 expect=violation domain=AE\n"
                        + "#library-ref standard=sdtmig version=3-4\n", AE),
                null, environment(store(false)), null);
        assertEquals(Outcome.ABORTED, r.outcome(), r.detail());
        assertEquals("REF_NOT_EXECUTED", r.code());
        assertNotNull(r.skip());
        assertEquals(SkipReason.Kind.REQUIREMENTS, r.skip().kind(), r.skip().text());
    }


    /**
     * {@code PLAN-scenario-scope-gate-execution-error} C6 / C7: the {@code #library-ref} rescue
     * aborts only a run that neither executed nor errored. A rule that ERRORED is judged: an
     * {@code expect=executionError} scenario passes (before the plan it was aborted
     * {@code REF_NOT_EXECUTED} and could never pass), an {@code expect=violation} one fails the
     * verdict (before, an ERROR showed as a skip, not a red).
     */
    @Test
    void aLibraryRefScenarioWhoseRuleErroredIsJudged() throws IOException
    {
        Rule errors = erroringRule();
        ScenarioRunResult plain = ScenarioRunner.run(errors,
                scenario("#test CDISC-XX0001 expect=executionError domain=AE\n", AE), null,
                environment(NO_STORE), null);
        assertEquals(Outcome.PASS, plain.outcome(),
                "the control: the rule errors " + plain.detail());

        ScenarioRunResult error = ScenarioRunner.run(errors,
                scenario("#test CDISC-XX0001 expect=executionError domain=AE\n"
                        + "#library-ref standard=sdtmig version=3-4\n", AE),
                null, environment(store(false)), null);
        assertEquals(Outcome.PASS, error.outcome(), error.code() + " " + error.detail());
    }


    /** C7's second arm: an errored {@code #library-ref} run fails a violation verdict. */
    @Test
    void aLibraryRefScenarioWhoseRuleErroredFailsAViolationVerdict() throws IOException
    {
        ScenarioRunResult violation = ScenarioRunner.run(erroringRule(),
                scenario("#test CDISC-XX0001 expect=violation domain=AE\n"
                        + "#library-ref standard=sdtmig version=3-4\n", AE),
                null, environment(store(false)), null);
        assertEquals(Outcome.FAIL, violation.outcome(),
                violation.code() + " " + violation.detail());
        assertEquals("VERDICT", violation.code());
        assertTrue(violation.detail().contains("but the rule ERRORED"), violation.detail());
    }


    /** A numeric function over a Char column: a column-type mismatch, so the rule ERRORS. */
    private static Rule erroringRule() throws IOException
    {
        Rule errors = RulePackageLoader.loadFromString(
                PACKAGE.replace("AESEV == \\\"MILD\\\" and AESER == \\\"Y\\\"", "abs(AESEV) > 1"))
                .getRules().get("CDISC-XX0001");
        assertNull(errors.getLoadError(), errors.getLoadError());
        return errors;
    }


    /** Review 8a-B L6: an ADaM axis declared with no carrier block is a NO_CARRIER failure. */
    @Test
    void aDeclaredStructureWithoutACarrierFails() throws IOException
    {
        ScenarioRunResult r = ScenarioRunner.run(rule(),
                scenario("#test CDISC-XX0001 expect=violation domain=AE\n",
                        AE.replace("label=\"Adverse Events\"",
                                "label=\"Adverse Events\" structure=OCCDS")),
                null, environment(NO_STORE), null);
        assertEquals(Outcome.FAIL, r.outcome(), r.detail());
        assertEquals("NO_CARRIER", r.code());
        assertTrue(r.detail().contains("Add a carrier block"), r.detail());
    }


    /** Review 8a-B L6: a location assertion that does not hold is a LOCATION failure. */
    @Test
    void aWrongLocationAssertionFails() throws IOException
    {
        ScenarioRunResult r = ScenarioRunner
                .run(rule(),
                        scenario("#test CDISC-XX0001 expect=violation domain=AE\n"
                                + "#expectViolationCount 2\n", AE),
                        null, environment(NO_STORE), null);
        assertEquals(Outcome.FAIL, r.outcome(), r.detail());
        assertEquals("LOCATION", r.code());
        assertNotNull(r.locations());
        assertFalse(r.locations().pass());
        assertNotNull(r.verdict());
        assertTrue(r.verdict().pass(), "the verdict held; the location did not");
    }


    /**
     * Review 8a-B L7: {@code run} is the editor host's entry and never throws for what a store, the
     * expander or the engine raises — a FAIL {@code INTERNAL} with a generic message instead (the
     * cause goes to the log, not to the anonymous reader).
     */
    @Test
    void aRuntimeFailureInsideTheRunIsAnInternalFailure() throws IOException
    {
        ScenarioRunResult r = ScenarioRunner.run(rule(),
                scenario("#test CDISC-XX0001 expect=violation domain=AE\n"
                        + "#library-ref standard=sdtmig version=3-4\n", AE),
                null, environment(store(true)), null);
        assertEquals(Outcome.FAIL, r.outcome(), r.detail());
        assertEquals("INTERNAL", r.code());
        assertFalse(r.detail().contains("store defect in this test"), r.detail());
    }


    /**
     * Review 8a-B M2: the runner's text entry confines the includes the PARSER resolves, so a host
     * holding scenario text never has to remember to — an include that climbs out of the tree is a
     * PARSE failure naming no absolute path; one beside the scenario runs.
     */
    @Test
    void runTextConfinesTheIncludesItParses(@TempDir Path aDir) throws IOException
    {
        Path dir = Files.createDirectories(aDir.resolve("suites").resolve("fam").resolve("R1"));
        Files.writeString(aDir.resolve("outside.yaml"), "standard: sdtmig\nversion: \"3-4\"\n",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("lib.yaml"), "standard: sdtmig\nversion: \"3-4\"\n",
                StandardCharsets.UTF_8);
        RunEnvironment env = new RunEnvironment(NO_STORE, null,
                SidecarResolver.rootedAt(aDir.resolve("suites")));
        Path file = dir.resolve("s.cdt");

        ScenarioRunResult escaped = ScenarioRunner.runText(rule(),
                "#!RuleTest\n#test CDISC-XX0001 expect=violation domain=AE\n"
                        + "#library-include ../../../outside.yaml\n" + AE,
                file, env, null);
        assertEquals(Outcome.FAIL, escaped.outcome(), escaped.detail());
        assertEquals("PARSE", escaped.code());
        assertTrue(escaped.detail().contains("escapes the scenario tree"), escaped.detail());
        assertTrue(escaped.detail().startsWith("fam/R1/s.cdt:"), escaped.detail());
        assertFalse(escaped.detail().contains(aDir.toString()), escaped.detail());

        ScenarioRunResult beside = ScenarioRunner.runText(rule(),
                "#!RuleTest\n#test CDISC-XX0001 expect=violation domain=AE\n"
                        + "#library-include lib.yaml\n" + AE,
                file, env, null);
        assertEquals(Outcome.PASS, beside.outcome(), beside.detail());

        ScenarioRunResult unconfined = ScenarioRunner
                .runText(rule(),
                        "#!RuleTest\n#test CDISC-XX0001 expect=violation domain=AE\n"
                                + "#library-include lib.yaml\n" + AE,
                        file, environment(NO_STORE), null);
        assertEquals("PARSE", unconfined.code(), "no resolver: an include cannot be resolved");
        assertTrue(unconfined.detail().contains("requires loading from a file"),
                unconfined.detail());

        ScenarioRunResult broken = ScenarioRunner.runText(rule(), "not a scenario", file, env,
                null);
        assertEquals("PARSE", broken.code());
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
