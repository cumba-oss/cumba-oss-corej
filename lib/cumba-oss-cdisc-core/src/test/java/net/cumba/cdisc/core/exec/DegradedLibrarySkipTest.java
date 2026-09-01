package net.cumba.cdisc.core.exec;

import static net.cumba.cdisc.core.metadata.TestMetadataFixtures.column;
import static net.cumba.cdisc.core.metadata.TestMetadataFixtures.lib;
import static net.cumba.cdisc.core.metadata.TestMetadataFixtures.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.metadata.CompanionDomainsProvider;
import net.cumba.cdisc.core.metadata.MetadataLibraryProvider;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Fix #369</b> — a CDISC Library that could not be consulted SKIPs the rule, on every standard.
 *
 * <h2>Why these tests are the evidence and the corpus is not</h2>
 *
 * <p>
 * This tree runs against a <em>cached</em> Library, so the degraded path is never taken on a normal
 * run and {@code findings-snapshot.tsv} cannot show this change at all — it is the
 * <em>prediction</em> (byte-unchanged), not the proof. The only way to observe the behaviour is to
 * force the Library load to fail, which is what every test here does via
 * {@link MetadataLibraryProvider#degraded(IMetadataLibrary, Throwable)}.
 * </p>
 *
 * <p>
 * ⚠ Each assertion is on the <b>shipped verdict</b> ({@link RuleExecutionStatus}) rather than on
 * {@code evalLibrary}'s return value. A test that only checks the sentinel proves the mapping, not
 * that a rule skips — and the mapping was never the part that was broken.
 * </p>
 *
 * <h2>The defect these pin</h2>
 *
 * <p>
 * {@code libraryFailed} gated ten class-hierarchy accessors and <b>none</b> of the variable-list
 * ones, so those fell through to the study library; when the study library could not answer either
 * the result was {@code List.of()}, {@code contains_all(cols, [])} was vacuously true, and the rule
 * reported {@code SUCCESS} with 0 issues and 0 skips. Measured end to end on an ordinary HTTP 401 —
 * the state of any run without a valid subscription key, not an outage.
 * </p>
 */
class DegradedLibrarySkipTest
{

    private static final String FALLBACK_PROPERTY = OperationExecutor.DEGRADED_DEFINE_FALLBACK_PROPERTY;

    /**
     * ⚠ The opt-in is a process-wide system property. Clear it after every test, or the first test
     * that sets it silently re-runs every later one under the fallback.
     */
    @AfterEach
    void clearFallbackProperty()
    {
        System.clearProperty(FALLBACK_PROPERTY);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------


    /**
     * The rule shape of {@code PMDA-AD0047} / {@code CDISC-AD9704}, reduced to what matters here: a
     * {@code required_variables()} operation, the dataset's own columns, and a {@code contains_all}
     * check that is <b>vacuously true</b> when the required list is empty. That vacuity is the
     * whole defect.
     */
    private static Rule requiredVariablesRule() throws Exception
    {
        String pkg = """
                {"rules":{"x":{
                  "Core":{"Id":"T-REQ"},
                  "Operations":[
                    {"id":"$required_variables","expression":"required_variables()"},
                    {"id":"$dataset_variables","expression":"get_column_order_from_dataset()"}
                  ],
                  "Check":{"expression":"not contains_all($dataset_variables, $required_variables)"},
                  "Outcome":{"Message":"A Required variable is not present."}
                }}}""";
        Rule r = RulePackageLoader.loadFromString(pkg).getRules().get("x");
        assertNotNull(r);
        assertNull(r.getLoadError());
        return r;
    }


    private static Rule ruleOn(String operationExpression, String check) throws Exception
    {
        String pkg = "{\"rules\":{\"x\":{\"Core\":{\"Id\":\"T\"},\"Operations\":[{\"id\":\"$v\","
                + "\"expression\":\"" + operationExpression + "\"}],\"Check\":{\"expression\":\""
                + check + "\"},\"Outcome\":{\"Message\":\"m\"}}}}";
        Rule r = RulePackageLoader.loadFromString(pkg).getRules().get("x");
        assertNotNull(r);
        assertNull(r.getLoadError(), "rule failed to load: " + r.getLoadError());
        return r;
    }


    /** An LB dataset that is missing the Required {@code USUBJID}. */
    private static IDataTable lbMissingUsubjid()
    {
        return MockTable.of().col("STUDYID", "S1").col("DOMAIN", "LB").name("LB").build();
    }


    /**
     * A study library carrying no {@code core} attribute at all — the XPT / CSV / sas7bdat case,
     * and the one measured against a real 401. {@code getRequiredVariables} returns
     * {@code List.of()} here, which is exactly the vacuity the fix exists to stop.
     */
    private static IMetadataLibrary dataDerivedStudy()
    {
        return lib("study")
                .table(table("LB").column(column("STUDYID", 0, DataValueType.STRING).build())
                        .column(column("DOMAIN", 1, DataValueType.STRING).build()).build())
                .build();
    }


    /**
     * A Define-XML-backed study library: it publishes {@code DefineVersion}, which is the exact,
     * already-existing test for "the fallback is a define" — {@code DefineMetadataLibrary} is the
     * only implementation in the repository that publishes that key — and it declares
     * {@code USUBJID} as {@code Req}, i.e. it <b>could</b> answer the rule.
     */
    private static IMetadataLibrary defineBackedStudy()
    {
        return lib("study").meta(IMetadataLibrary.META_KEY_DEFINE_VERSION, "2.1.0")
                .table(table("LB")
                        .column(column("STUDYID", 0, DataValueType.STRING).core("Req").build())
                        .column(column("USUBJID", 1, DataValueType.STRING).core("Req").build())
                        .build())
                .build();
    }


    private static MetadataLibraryProvider degraded(IMetadataLibrary study)
    {
        return MetadataLibraryProvider.degraded(study, new IOException("HTTP 401"));
    }


    private static RuleExecutionResult run(Rule rule, IDataTable table, MetadataProvider provider)
    {
        return RuleRunner.execute(rule, table, name -> "LB".equals(name) ? table : null, "LB",
                provider);
    }

    // ------------------------------------------------------------------
    // §6 instrument 1 — the three forced-failure tests
    // ------------------------------------------------------------------


    @Test
    @DisplayName("degraded library + data-derived study ⇒ SKIPPED (was a vacuous SUCCESS)")
    void degradedLibrary_dataDerivedStudy_skips() throws Exception
    {
        RuleExecutionResult result = run(requiredVariablesRule(), lbMissingUsubjid(),
                degraded(dataDerivedStudy()));

        assertEquals(RuleExecutionStatus.SKIPPED, result.getStatus(),
                "a rule that cites the Library must not pass when the Library was never consulted");
        assertNotNull(result.getStatusMessage());
        assertTrue(result.getStatusMessage().contains("CDISC Library could not be consulted"),
                "the skip must name the unavailable Library, not read as 'asked and got nothing': "
                        + result.getStatusMessage());
    }


    @Test
    @DisplayName("degraded library + Define-backed study, opt-in OFF ⇒ SKIPPED — the owner's ruling")
    void degradedLibrary_defineBackedStudy_optInOff_stillSkips() throws Exception
    {
        // ⭐ THIS is the test that encodes the ruling. The fallback CAN answer here — the study
        // declares USUBJID as Req — and is deliberately not used, because a Define-XML
        // ItemRef/@Mandatory is the SPONSOR's declaration, not the STANDARD's Required list, and a
        // rule that says it checks the standard has not done so if it read the sponsor's file.
        // Without this test someone restores the old behaviour as an "optimisation" ("don't throw
        // away a good answer") and every other test still passes.
        RuleExecutionResult result = run(requiredVariablesRule(), lbMissingUsubjid(),
                degraded(defineBackedStudy()));

        assertEquals(RuleExecutionStatus.SKIPPED, result.getStatus(),
                "a fallback that COULD answer must still skip while the opt-in is off");
    }


    @Test
    @DisplayName("degraded library + Define-backed study, opt-in ON ⇒ the rule executes")
    void degradedLibrary_defineBackedStudy_optInOn_executes() throws Exception
    {
        System.setProperty(FALLBACK_PROPERTY, "true");

        RuleExecutionResult result = run(requiredVariablesRule(), lbMissingUsubjid(),
                degraded(defineBackedStudy()));

        assertNotEquals(RuleExecutionStatus.SKIPPED, result.getStatus(),
                "the user opted in and the define can answer this arm — it must execute");
        assertFalse(result.getViolations().isEmpty(),
                "USUBJID is declared Req in the define and absent from the data — the rule fires");
    }

    // ------------------------------------------------------------------
    // The three conditions of the gate, individually
    // ------------------------------------------------------------------


    @Test
    @DisplayName("opt-in ON but the fallback is not a define ⇒ still SKIPPED (condition 2)")
    void optInOn_withoutADefine_stillSkips() throws Exception
    {
        System.setProperty(FALLBACK_PROPERTY, "true");

        // Nothing to fall back TO. A data-derived answer must never be admitted under a flag named
        // for Define-XML — which is what makes "which arms does the opt-in cover?" a non-question.
        RuleExecutionResult result = run(requiredVariablesRule(), lbMissingUsubjid(),
                degraded(dataDerivedStudy()));

        assertEquals(RuleExecutionStatus.SKIPPED, result.getStatus());
    }


    @Test
    @DisplayName("a working library is completely unaffected")
    void workingLibrary_unchanged() throws Exception
    {
        // The control. Every degraded assertion above is worthless if the healthy path moved too:
        // 99+ corpus rules ride on it. A non-degraded provider over the same study answers exactly
        // as before, and the rule fires on the genuinely-missing USUBJID.
        MetadataProvider healthy = MetadataLibraryProvider.forDefine(defineBackedStudy());
        assertFalse(healthy.isLibraryUnavailable());

        RuleExecutionResult result = run(requiredVariablesRule(), lbMissingUsubjid(), healthy);

        assertNotEquals(RuleExecutionStatus.SKIPPED, result.getStatus());
        assertFalse(result.getViolations().isEmpty());
    }

    // ------------------------------------------------------------------
    // §4b — the two arms whose result type cannot express "no answer"
    // ------------------------------------------------------------------


    @Test
    @DisplayName("domain_is_custom: NEVER answers when degraded, even under the opt-in")
    void domainIsCustom_neverAnswersWhenDegraded() throws Exception
    {
        // `false` ("not custom") is a real answer AND the one that lets a rule fire, so it is
        // indistinguishable from "could not tell". A define cannot supply it either: "custom"
        // means "not in the standard", and the standard is precisely what is missing.
        Rule rule = ruleOn("domain_is_custom()", "$v == true");
        System.setProperty(FALLBACK_PROPERTY, "true");

        RuleExecutionResult result = run(rule, lbMissingUsubjid(), degraded(defineBackedStudy()));

        assertEquals(RuleExecutionStatus.SKIPPED, result.getStatus(),
                "domain_is_custom must skip on a degraded library even with a define present");
    }


    @Test
    @DisplayName("a TEXT arm: null still means 'genuinely custom' on a WORKING library")
    void textArm_bothHalves() throws Exception
    {
        // ⚑ Fix #371 — this pinned `domain_label()` until that operation was retired. Its subject
        // was never `domain_label` itself but the TEXT LibraryArmAnswer contract, so it is
        // RE-POINTED at the surviving TEXT arm rather than deleted: `dataset_class_from_library`
        // has the identical shape (read the Library keyed by `cdiscDomainOf`, null for a
        // genuinely-custom domain).
        Rule rule = ruleOn("dataset_class_from_library()", "non_empty($v)");

        // Half 1 — degraded: the two readings of null ("custom" vs "could not ask") are
        // indistinguishable, so it skips.
        assertEquals(RuleExecutionStatus.SKIPPED,
                run(rule, lbMissingUsubjid(), degraded(defineBackedStudy())).getStatus(),
                "a degraded TEXT arm must skip");

        // Half 2 — working library, no class for this domain: null keeps its documented meaning
        // and the rule is NOT skipped by the degraded gate. ⚠ Pin both halves or the next reader
        // "simplifies" the guard into an unconditional null-skip and silently re-widens it.
        RuleExecutionResult healthy = run(rule, lbMissingUsubjid(),
                MetadataLibraryProvider.forDefine(defineBackedStudy()));
        assertNotEquals(RuleExecutionStatus.SKIPPED, healthy.getStatus(),
                "a working library's null answer must not be turned into a skip");
    }

    // ------------------------------------------------------------------
    // ⛔ The five arms that do NOT funnel through evalLibrary
    // ------------------------------------------------------------------


    @Test
    @DisplayName("the arms that hand-roll their own provider check also skip when degraded")
    void handRolledArms_alsoSkipWhenDegraded() throws Exception
    {
        // ⚠⚠ The plan this fix implements asserted that every library arm funnels through
        // evalLibrary. It does not — 12 of the 17 do. These five hand-roll the `provider == null`
        // check and would have kept silently passing on exactly the degraded runs the fix exists
        // to catch: 19 corpus rules between them. If one of these ever goes green-without-skipping
        // again, a gate was added to evalLibrary and not to degradedSkip.
        MetadataProvider provider = degraded(dataDerivedStudy());
        record Arm(String expression, String check)
        {
        }
        for (Arm arm : new Arm[]
        {
                new Arm("get_model_filtered_variables(key_name=\\\"role\\\", key_value=\\\"Timing\\\")",
                        "non_empty($v)"),
                new Arm("get_dataset_filtered_variables(key_name=\\\"role\\\", key_value=\\\"Timing\\\")",
                        "non_empty($v)"),
                new Arm("natural_key_variables()", "non_empty($v)"),
                new Arm("get_parent_model_column_order()", "non_empty($v)"),
                new Arm("valid_codelist_dates()", "non_empty($v)")
        })
        {
            String pkg = "{\"rules\":{\"x\":{\"Core\":{\"Id\":\"T\"},\"Operations\":["
                    + "{\"id\":\"$v\",\"expression\":\"" + arm.expression() + "\"},"
                    + "{\"id\":\"$dataset_variables\",\"expression\":"
                    + "\"get_column_order_from_dataset()\"}],\"Check\":{\"expression\":\""
                    + arm.check() + "\"},\"Outcome\":{\"Message\":\"m\"}}}}";
            Rule rule = RulePackageLoader.loadFromString(pkg).getRules().get("x");
            assertNotNull(rule, arm.expression());
            assertNull(rule.getLoadError(), arm.expression() + " -> " + rule.getLoadError());

            assertEquals(RuleExecutionStatus.SKIPPED,
                    run(rule, lbMissingUsubjid(), provider).getStatus(),
                    arm.expression() + " must SKIP on a degraded library");
        }
    }

    // ------------------------------------------------------------------
    // ⛔⛔ The OPERAND surface — disjoint from the operation surface above
    // ------------------------------------------------------------------


    @Test
    @DisplayName("a rule reading a LIBRARY-level operand skips — it has NO operation at all")
    void libraryOperandRule_withNoOperationAtAll_skips() throws Exception
    {
        // ⚠⚠ THE CENSUS WAS THE WRONG POPULATION. Everything above gates library *operations*.
        // 30 corpus rules read the Library through *operands* and carry no Operations block at
        // all — CDISC-CG0010's entire Check is this shape — so nothing in OperationExecutor could
        // ever see them. Those operands resolve through ExprCompiler.readProviderLevel, gated only
        // by `libraryProvider == null`, and a DEGRADED provider is non-null: the rule silently
        // read the STUDY library and, for CG0010, compared the define against itself.
        String pkg = """
                {"rules":{"x":{
                  "Core":{"Id":"T-OPERAND"},
                  "Check":{"expression":"not empty(var_role(\\"LIBRARY\\"))"},
                  "Outcome":{"Message":"m"}
                }}}""";
        Rule rule = RulePackageLoader.loadFromString(pkg).getRules().get("x");
        assertNotNull(rule);
        assertNull(rule.getLoadError(), String.valueOf(rule.getLoadError()));

        assertEquals(RuleExecutionStatus.SKIPPED,
                run(rule, lbMissingUsubjid(), degraded(defineBackedStudy())).getStatus(),
                "a LIBRARY-level operand read must skip on a degraded library");
    }


    @Test
    @DisplayName("...and a working library still answers the same operand")
    void libraryOperandRule_workingLibrary_unchanged() throws Exception
    {
        // The control for the gate above: it must key on isLibraryUnavailable(), not merely on
        // "a LIBRARY operand was read", or every library_* rule skips on every run.
        String pkg = """
                {"rules":{"x":{
                  "Core":{"Id":"T-OPERAND"},
                  "Check":{"expression":"not empty(var_role(\\"LIBRARY\\"))"},
                  "Outcome":{"Message":"m"}
                }}}""";
        Rule rule = RulePackageLoader.loadFromString(pkg).getRules().get("x");
        assertNotNull(rule);
        assertNull(rule.getLoadError(), String.valueOf(rule.getLoadError()));

        assertNotEquals(RuleExecutionStatus.SKIPPED,
                run(rule, lbMissingUsubjid(),
                        MetadataLibraryProvider.forDefine(defineBackedStudy())).getStatus(),
                "a healthy library must keep answering LIBRARY-level operands");
    }

    // ------------------------------------------------------------------
    // §4e — the decorator trap, behaviourally
    // ------------------------------------------------------------------


    @Test
    @DisplayName("a decorator over a degraded provider still skips the rule")
    void decoratorOverDegradedProvider_stillSkips() throws Exception
    {
        // CompanionDomainsProvider wraps the run provider on EVERY validation. Fix #368 lost a
        // full cycle to it inheriting a new default: the engine change was complete, its unit
        // tests green, and the end-to-end run byte-identical to the broken baseline. This asserts
        // the behaviour, not merely that the source text carries a delegation (which is what
        // MetadataProviderDecoratorDelegationGuardTest asserts, structurally and repo-wide).
        MetadataProvider wrapped = new CompanionDomainsProvider(degraded(dataDerivedStudy()),
                MetadataLibraryProvider.forDefine(defineBackedStudy()));
        assertTrue(wrapped.isLibraryUnavailable(),
                "the decorator must not answer 'library fine' for a provider that failed");

        assertEquals(RuleExecutionStatus.SKIPPED,
                run(requiredVariablesRule(), lbMissingUsubjid(), wrapped).getStatus());
    }

    // ------------------------------------------------------------------
    // The predicates themselves
    // ------------------------------------------------------------------


    @Test
    void libraryAnswerable_coversEveryBranch()
    {
        assertFalse(OperationExecutor.libraryAnswerable(null), "no provider");
        assertTrue(
                OperationExecutor
                        .libraryAnswerable(MetadataLibraryProvider.forDefine(defineBackedStudy())),
                "a healthy provider is always answerable");

        MetadataProvider deg = degraded(defineBackedStudy());
        assertFalse(OperationExecutor.libraryAnswerable(deg), "degraded, opt-in off");

        System.setProperty(FALLBACK_PROPERTY, "true");
        assertTrue(OperationExecutor.libraryAnswerable(deg), "degraded, opted in, define present");
        assertFalse(OperationExecutor.libraryAnswerable(degraded(dataDerivedStudy())),
                "degraded, opted in, but nothing to fall back TO");
    }


    @Test
    void defineFallbackPreference_defaultsOff()
    {
        assertFalse(OperationExecutor.defineFallbackPreference(),
                "the substitution must never be an accident of an expired subscription key");
        System.setProperty(FALLBACK_PROPERTY, "TRUE");
        assertTrue(OperationExecutor.defineFallbackPreference(), "case-insensitive true");
        System.setProperty(FALLBACK_PROPERTY, "yes");
        assertFalse(OperationExecutor.defineFallbackPreference(),
                "anything but an explicit true leaves it off");
    }

    // ------------------------------------------------------------------
    // Condition 3, on both gate paths — "opted in, define present, this arm got nothing"
    // ------------------------------------------------------------------


    @Test
    @DisplayName("opt-in ON: an arm the define CANNOT serve still skips (condition 3)")
    void optInOn_armTheDefineCannotServe_stillSkips() throws Exception
    {
        // get_model_filtered_variables asks for SDTM *Model* concepts. A Define-XML has no model,
        // so even a fully opted-in run with a define present must skip this arm — which is exactly
        // what "the rules that CAN answer from the define" means, arrived at without any per-arm
        // list. ⚠ This is the degradedAnswerOrSkip half of condition 3 (the hand-rolled arms);
        // `degradedLibrary_defineBackedStudy_optInOn_executes` covers the evalLibrary half.
        String pkg = "{\"rules\":{\"x\":{\"Core\":{\"Id\":\"T\"},\"Operations\":[{\"id\":\"$v\","
                + "\"expression\":\"get_model_filtered_variables(key_name=\\\"role\\\","
                + " key_value=\\\"Timing\\\")\"}],\"Check\":{\"expression\":\"non_empty($v)\"},"
                + "\"Outcome\":{\"Message\":\"m\"}}}}";
        Rule rule = RulePackageLoader.loadFromString(pkg).getRules().get("x");
        assertNotNull(rule);
        assertNull(rule.getLoadError(), String.valueOf(rule.getLoadError()));

        System.setProperty(FALLBACK_PROPERTY, "true");

        assertEquals(RuleExecutionStatus.SKIPPED,
                run(rule, lbMissingUsubjid(), degraded(defineBackedStudy())).getStatus(),
                "a define cannot answer an SDTM-Model arm — it must skip even when opted in");
    }


    @Test
    @DisplayName("opt-in ON: a TEXT arm executes when the define carries a value (TEXT contract)")
    void optInOn_textArm_executesWhenTheDefineHasAValue() throws Exception
    {
        // The TEXT arm's positive branch: a non-blank String IS an answer, so the rule runs.
        // Pairs with textArm_bothHalves, which covers null ⇒ skip.
        // ⚑ Fix #371 — re-pointed from `domain_label()` when that operation was retired; the
        // contract under test is unchanged.
        IMetadataLibrary classed = lib("study")
                .meta(IMetadataLibrary.META_KEY_DEFINE_VERSION, "2.1.0")
                .table(table("LB").label("Laboratory Test Results").className("Findings")
                        .column(column("STUDYID", 0, DataValueType.STRING).build())
                        .column(column("DOMAIN", 1, DataValueType.STRING).build()).build())
                .build();
        Rule rule = ruleOn("dataset_class_from_library()", "non_empty($v)");
        System.setProperty(FALLBACK_PROPERTY, "true");

        assertNotEquals(RuleExecutionStatus.SKIPPED,
                run(rule, lbMissingUsubjid(), degraded(classed)).getStatus(),
                "a non-blank class from the define is a real answer under the opt-in");
    }


    @Test
    @DisplayName("DefineXmlMetadataProvider delegates, and reports false with no fallback")
    void defineXmlMetadataProvider_delegatesBothWays()
    {
        // With no fallback this provider is pure Define-XML: there is no CDISC Library behind it to
        // have failed, so `false` is the correct answer and not merely the inherited default.
        net.cumba.cdisc.core.gen.DefineXMLProvider define = org.mockito.Mockito
                .mock(net.cumba.cdisc.core.gen.DefineXMLProvider.class);
        assertFalse(new net.cumba.cdisc.core.metadata.DefineXmlMetadataProvider(define)
                .isLibraryUnavailable(), "no fallback ⇒ no Library to have failed");
        assertTrue(
                new net.cumba.cdisc.core.metadata.DefineXmlMetadataProvider(define,
                        degraded(dataDerivedStudy())).isLibraryUnavailable(),
                "a degraded fallback must not be reported as healthy");
    }


    @Test
    @DisplayName("⚑ Fix #371 — isLibraryDependent is the ONE predicate again")
    void libraryDependentIsTheSolePredicate()
    {
        // `isLibraryBacked` existed only because `domain_label()` read the Library while sitting
        // OUTSIDE `isLibraryDependent` — and it had to stay outside, because that predicate is
        // materialised into the shipped corpus as Requirements.Library and drives precondition
        // injection, so widening it MOVED the corpus (measured during Fix #369: CDISC-CG0336
        // gained "Requirements": {"Library": true} and NativeCorpusRoundTripTest went red).
        // With `domain_label()` retired there is no such operation left and the second predicate
        // dissolved with it. This pins that the remaining one still classifies the library arms.
        assertTrue(OperationExecutor
                .isLibraryDependent(net.cumba.cdisc.core.model.OperationType.REQUIRED_VARIABLES));
        assertFalse(OperationExecutor
                .isLibraryDependent(net.cumba.cdisc.core.model.OperationType.DISTINCT));
    }
}
