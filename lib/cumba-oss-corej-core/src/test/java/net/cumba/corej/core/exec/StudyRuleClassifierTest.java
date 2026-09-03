package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * {@link StudyRuleClassifier} — the gate deciding whether a study rule may run once against the
 * study anchor instead of once per dataset.
 *
 * <p>
 * The positive cases mirror the real migration candidates (anchorless {@code ds_exists} absence
 * assertions scoped {@code [ALL]}). The negative cases are the three shapes that must never take
 * the fast path: a rule gated on a dataset that must be <em>present</em>, a rule already scoped to
 * a domain, and a rule whose check reads the dataset under evaluation.
 * </p>
 */
class StudyRuleClassifierTest
{

    private static Rule rule(String body) throws Exception
    {
        String json = "{\"rules\": {\"TEST-RULE\": {\"Core\": {\"Id\": \"TEST-RULE\"}, " + body
                + "}}}";
        RulePackage pkg = RulePackageLoader.loadFromString(json);
        return pkg.getRules().get("TEST-RULE");
    }


    /**
     * A Study rule with the given Check.
     *
     * <p>
     * Asserts the Check actually compiled. Without that guard a mistyped or type-incompatible
     * expression yields {@code checkExpr == null}, which {@link StudyRuleClassifier} rejects at its
     * conservative null guard — so a test expecting "rejected" would pass for entirely the wrong
     * reason and cover none of the walk it was written to exercise.
     * </p>
     */
    private static Rule studyRule(String check) throws Exception
    {
        Rule r = rule("\"Sensitivity\": \"Study\","
                + " \"Scope\": {\"Domains\": {\"Include\": [\"ALL\"]}},"
                + " \"Check\": {\"expression\": \"" + check + "\"},"
                + " \"Outcome\": {\"Message\": \"m\", \"Output_Variables\": [\"USUBJID\"]}");
        assertNotNull(r.getCheckExpr(),
                "fixture must compile, else the classifier rejects it for the wrong reason: "
                        + check);
        return r;
    }

    // ---- criterion 1: sensitivity ----------------------------------------------------------


    @Test
    void nonStudySensitivityIsNeverAnchorEligible() throws Exception
    {
        Rule r = rule("\"Sensitivity\": \"Dataset\","
                + " \"Scope\": {\"Domains\": {\"Include\": [\"ALL\"]}},"
                + " \"Check\": {\"expression\": \"not ds_exists(\\\"DM\\\")\"},"
                + " \"Outcome\": {\"Message\": \"m\"}");
        assertFalse(StudyRuleClassifier.isAnchorEligible(r),
                "the anchor pass only ever runs Sensitivity=Study rules");
    }

    // ---- the real migration shapes ---------------------------------------------------------


    @Test
    void anchorlessAbsenceAssertionsAreEligible() throws Exception
    {
        assertTrue(StudyRuleClassifier.isAnchorEligible(studyRule("not ds_exists(\\\"DM\\\")")),
                "not ds_exists(DM) — the FDA-SD1020 shape");
        assertTrue(StudyRuleClassifier.isAnchorEligible(studyRule("ds_not_exists(\\\"ADSL\\\")")),
                "ds_not_exists(ADSL) — the CDISC-AD0001 shape");
        assertTrue(
                StudyRuleClassifier.isAnchorEligible(
                        studyRule("not ds_exists(\\\"TT\\\") or not ds_exists(\\\"TP\\\")"
                                + " or not ds_exists(\\\"SJ\\\")")),
                "or-combined absence — the CDISC-SEND-0400 shape");
    }


    @Test
    void aRuleWithNoScopeBlockAtAllIsEligible() throws Exception
    {
        Rule r = rule("\"Sensitivity\": \"Study\","
                + " \"Check\": {\"expression\": \"not ds_exists(\\\"DM\\\")\"},"
                + " \"Outcome\": {\"Message\": \"m\"}");
        assertTrue(StudyRuleClassifier.isAnchorEligible(r), "no Scope means no restriction");
    }


    /** A {@code Use_Case} facet filters per run, not per dataset, so it must not disqualify. */
    @Test
    void useCaseFacetDoesNotDisqualify() throws Exception
    {
        Rule r = rule("\"Sensitivity\": \"Study\","
                + " \"Scope\": {\"Domains\": {\"Include\": [\"ALL\"]}, \"Use_Case\": \"NONCLIN\"},"
                + " \"Check\": {\"expression\": \"not ds_exists(\\\"TS\\\")\"},"
                + " \"Outcome\": {\"Message\": \"m\"}");
        assertTrue(StudyRuleClassifier.isAnchorEligible(r),
                "Use_Case is a per-run filter, not a dataset filter");
    }

    // ---- criterion 3: scope --------------------------------------------------------------


    /** Group C: already domain-scoped, so its finding belongs to that dataset. */
    @Test
    void domainScopedRuleIsRejected() throws Exception
    {
        Rule r = rule("\"Sensitivity\": \"Study\","
                + " \"Scope\": {\"Domains\": {\"Include\": [\"MS\"]}},"
                + " \"Check\": {\"expression\": \"not ds_exists(\\\"MB\\\")\"},"
                + " \"Outcome\": {\"Message\": \"m\"}");
        assertFalse(StudyRuleClassifier.isAnchorEligible(r),
                "a domain-scoped rule executes on that domain");
    }


    @Test
    void classStructureSubclassAndVariableFacetsAllDisqualify() throws Exception
    {
        String check = " \"Check\": {\"expression\": \"not ds_exists(\\\"DM\\\")\"},"
                + " \"Outcome\": {\"Message\": \"m\"}";
        String head = "\"Sensitivity\": \"Study\",";

        assertFalse(
                StudyRuleClassifier.isAnchorEligible(rule(
                        head + " \"Scope\": {\"Classes\": {\"Include\": [\"EVENTS\"]}}," + check)),
                "Classes facet");
        assertFalse(StudyRuleClassifier.isAnchorEligible(
                rule(head + " \"Scope\": {\"Data_Structures\": {\"Include\": [\"BASIC DATA"
                        + " STRUCTURE\"]}}," + check)),
                "Data_Structures facet");
        assertFalse(StudyRuleClassifier.isAnchorEligible(rule(head
                + " \"Scope\": {\"Subclasses\": {\"Include\": [\"TIME-TO-EVENT\"]}}," + check)),
                "Subclasses facet");
        assertFalse(
                StudyRuleClassifier.isAnchorEligible(rule(head
                        + " \"Requirements\": {\"Variables\": {\"All\": [\"USUBJID\"]}}," + check)),
                "Requirements.Variables facet");
        assertFalse(StudyRuleClassifier.isAnchorEligible(rule(
                head + " \"Scope\": {\"Domains\": {\"Include\": [\"ALL\"], \"Exclude\": [\"DM\"]}},"
                        + check)),
                "Domains.Exclude");
    }

    // ---- criterion 2: does the check read the dataset under evaluation? ---------------------


    /** Group B: gated on a dataset that must be present — the finding belongs to that dataset. */
    @Test
    void positiveAnchorShapeIsStillEligibleOnScopeButPinnedByAuthoring() throws Exception
    {
        // ds_exists(TA) and not ds_exists(EX) reads no column, so criterion 2 passes; it is the
        // authoring scope that must pin such a rule to TA. Documented explicitly so the gate's
        // division of labour is not mistaken for an oversight.
        Rule r = studyRule("ds_exists(\\\"TA\\\") and not ds_exists(\\\"EX\\\")");
        assertTrue(StudyRuleClassifier.isAnchorEligible(r),
                "criterion 2 concerns dataset reads; attachment is enforced by Scope authoring");
    }


    @Test
    void aCheckReadingALocalColumnIsRejected() throws Exception
    {
        assertFalse(StudyRuleClassifier.isAnchorEligible(studyRule("empty(USUBJID)")),
                "a bare column names the dataset under evaluation");
    }


    @Test
    void aBareVarExistsIsRejectedButADottedOneIsNot() throws Exception
    {
        assertFalse(StudyRuleClassifier.isAnchorEligible(studyRule("var_exists(AESEQ)")),
                "a bare var_exists asks about the dataset under evaluation");
        assertTrue(StudyRuleClassifier.isAnchorEligible(studyRule("var_exists(DM.ARM)")),
                "a dotted var_exists names its own dataset");
    }


    @Test
    void aBuiltinMetadataRefIsRejected() throws Exception
    {
        assertFalse(
                StudyRuleClassifier.isAnchorEligible(studyRule("variable_name == \\\"USUBJID\\\"")),
                "every variable_/dataset_ fact is relative to the dataset under evaluation");
    }

    // ---- code-review findings: shapes that must NOT reach the 0-column anchor ---------------


    /**
     * A dotted ref in <em>value</em> position is a per-primary-row join lookup, not a metadata
     * question — {@code BroadcastFold.readsRowData} classifies it the same way. Evaluated against
     * the 0-column anchor every such lookup would silently resolve to {@code null} and the rule
     * would quietly pass.
     */
    @Test
    void aDottedRefInValuePositionIsRejected() throws Exception
    {
        assertFalse(StudyRuleClassifier.isAnchorEligible(studyRule("DM.DTHDTC != \\\"\\\"")),
                "a dotted ref is resolved per primary row when used as a value");
    }


    /** …while the same ref inside a presence call stays study-safe. */
    @Test
    void aDottedRefInsideAPresenceCallIsStillSafe() throws Exception
    {
        assertTrue(StudyRuleClassifier.isAnchorEligible(studyRule("var_exists(DM.ARM)")),
                "presence of a named dataset's column is a pure metadata question");
    }


    /**
     * A grouped aggregate is resolved per primary row ({@code GroupedResult.getForRow} reads the
     * evaluation table's group columns), so a pinned {@code domain} does not make it study-safe.
     */
    @Test
    void aGroupedOperationIsRejectedEvenWithAPinnedDomain() throws Exception
    {
        Rule r = ruleWithOperation("\"operator\": \"record_count\", \"domain\": \"TS\","
                + " \"group\": [\"USUBJID\"]");
        assertFalse(StudyRuleClassifier.isAnchorEligible(r),
                "a grouped aggregate resolves per primary row");
    }


    /** An operator outside the allowlist must be assumed to read the record under evaluation. */
    @Test
    void anOperatorOutsideTheAllowlistIsRejected() throws Exception
    {
        Rule r = ruleWithOperation("\"operator\": \"dy\", \"domain\": \"TS\"");
        assertFalse(StudyRuleClassifier.isAnchorEligible(r),
                "the operation gate is an allowlist, not a denylist");
    }


    /** A pinned, allowlisted aggregate remains eligible. */
    @Test
    void aPinnedAllowlistedOperationIsAccepted() throws Exception
    {
        Rule r = ruleWithOperation("\"operator\": \"record_count\", \"domain\": \"TS\"");
        assertTrue(StudyRuleClassifier.isAnchorEligible(r),
                "record_count pinned to a concrete domain is a study-level fact");
    }


    /**
     * {@code --} is resolved against the dataset under evaluation in several operation fields, not
     * just {@code domain}. The anchor pass runs with no domain prefix, so an unresolved token would
     * reach the executor as a non-existent column.
     */
    @Test
    void anOperationWithAWildcardOutsideDomainIsRejected() throws Exception
    {
        Rule r = ruleWithOperation(
                "\"operator\": \"distinct\", \"domain\": \"TS\", \"name\": \"--TESTCD\"");
        assertFalse(StudyRuleClassifier.isAnchorEligible(r),
                "an unresolved -- token in `name` still reads the primary dataset");
    }


    /** A list literal can hold column refs, which the operand walk must descend into. */
    @Test
    void aColumnRefInsideAListLiteralIsRejected() throws Exception
    {
        assertFalse(
                StudyRuleClassifier.isAnchorEligible(
                        studyRule("not ds_exists(\\\"DM\\\") or \\\"Y\\\" in [DTHFL]")),
                "a column ref hidden in a list literal still reads the dataset");
    }


    /**
     * Builds a Study rule whose Check is a single `$op` comparison, with that Operation attached.
     */
    private static Rule ruleWithOperation(String operationFields) throws Exception
    {
        return rule("\"Sensitivity\": \"Study\","
                + " \"Scope\": {\"Domains\": {\"Include\": [\"ALL\"]}},"
                + " \"Operations\": [{\"id\": \"$op\", " + operationFields + "}],"
                + " \"Check\": {\"expression\": \"$op > 0\"},"
                + " \"Outcome\": {\"Message\": \"m\"}");
    }

    // ---- remaining branches of the gate ----------------------------------------------------


    /**
     * A <em>raised</em> Precondition that reads the dataset disqualifies even when the Check is
     * study-safe.
     *
     * <p>
     * Only a fold-equivalent (broadcast) Precondition is compiled into {@code preconditionExpr};
     * the engine evaluates nothing else, so a row-level Precondition is a runtime no-op and is
     * correctly ignored here. `var_exists` on a bare column is such a broadcast fact <em>and</em>
     * reads the dataset under evaluation, which is exactly the shape that must disqualify.
     * </p>
     */
    @Test
    void aDatasetReadingPreconditionIsRejected() throws Exception
    {
        Rule r = rule("\"Sensitivity\": \"Study\","
                + " \"Scope\": {\"Domains\": {\"Include\": [\"ALL\"]}},"
                + " \"Check\": {\"expression\": \"not ds_exists(\\\"DM\\\")\"},"
                + " \"Outcome\": {\"Message\": \"m\"}");
        // ⚠ Installed on the engine-internal tier, not authored: gate R8 closed the authoring
        // surface (owner ruling Q3). The raise is the loader's own, so the state under test is
        // identical to what an authored Precondition produced before.
        RulePackageLoader.installEngineInternalPrecondition(r,
                new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                        "{\"expression\": \"var_exists(AESEQ)\"}",
                        net.cumba.corej.core.model.CheckCondition.class));
        assertNotNull(r.getPreconditionExpr(), "precondition: the engine raised it");
        assertFalse(StudyRuleClassifier.isAnchorEligible(r),
                "the Precondition is evaluated against the same dataset as the Check");
    }


    /**
     * An {@code Output_Variables} entry naming a dataset-reading operation disqualifies: it is
     * executed and rendered into the finding, so on the anchor it would emit an empty value.
     */
    @Test
    void aDatasetReadingOutputVariableIsRejected() throws Exception
    {
        Rule r = rule("\"Sensitivity\": \"Study\","
                + " \"Scope\": {\"Domains\": {\"Include\": [\"ALL\"]}},"
                + " \"Operations\": [{\"id\": \"$rows\", \"operator\": \"record_count\"}],"
                + " \"Check\": {\"expression\": \"not ds_exists(\\\"DM\\\")\"},"
                + " \"Outcome\": {\"Message\": \"m\"," + " \"Output_Variables\": [\"$rows\"]}");
        assertFalse(StudyRuleClassifier.isAnchorEligible(r),
                "an unpinned record_count output variable reads the dataset under evaluation");
    }


    /** A study-level output variable is fine. */
    @Test
    void aStudyLevelOutputVariableIsAccepted() throws Exception
    {
        Rule r = rule("\"Sensitivity\": \"Study\","
                + " \"Scope\": {\"Domains\": {\"Include\": [\"ALL\"]}},"
                + " \"Operations\": [{\"id\": \"$names\", \"operator\": \"dataset_names\"}],"
                + " \"Check\": {\"expression\": \"not ds_exists(\\\"DM\\\")\"},"
                + " \"Outcome\": {\"Message\": \"m\"," + " \"Output_Variables\": [\"$names\"]}");
        assertTrue(StudyRuleClassifier.isAnchorEligible(r), "dataset_names is a study-level fact");
    }


    /** `minus` composes two operands; both must themselves be study-level. */
    @Test
    void minusIsStudySafeOnlyWhenBothOperandsAre() throws Exception
    {
        Rule safe = ruleWithOperations("{\"id\": \"$all\", \"operator\": \"dataset_names\"},"
                + " {\"id\": \"$def\", \"operator\": \"define_dataset_names\"},"
                + " {\"id\": \"$gap\", \"operator\": \"minus\","
                + " \"name\": \"$all\", \"subtract\": \"$def\"}", "$gap");
        assertTrue(StudyRuleClassifier.isAnchorEligible(safe),
                "minus over two study-level operands stays study-level");

        Rule unsafe = ruleWithOperations(
                "{\"id\": \"$def\", \"operator\": \"define_dataset_names\"},"
                        + " {\"id\": \"$gap\", \"operator\": \"minus\","
                        + " \"name\": \"USUBJID\", \"subtract\": \"$def\"}",
                "$gap");
        assertFalse(StudyRuleClassifier.isAnchorEligible(unsafe),
                "a bare column operand names the dataset under evaluation");
    }


    /**
     * An operation ref that resolves to nothing gets the conservative answer.
     *
     * <p>
     * ⚠ <b>The fixture must reach the classifier with a compiled {@code checkExpr} and no
     * {@code loadError}</b>, or {@link StudyRuleClassifier} rejects it at its conservative null
     * guard and the operand walk this test exercises is never reached — the exact wrong-reason pass
     * {@link #studyRule(String)}'s guard exists to prevent. Since
     * {@code PLAN-dangling-operation-reference-load-check} a Check referencing a {@code $}-operand
     * that no {@code Operations} entry defines is a {@code loadError}, and
     * {@code RulePackageLoader.installNativeExpr} installs no {@code checkExpr} on a rule carrying
     * one. So the fixture is loaded <em>with</em> the operation declared — a clean load — and the
     * {@code Operations} block is dropped afterwards, leaving exactly the state the walk must
     * handle: a compiled {@code $}-ref with nothing to resolve it against.
     * </p>
     *
     * <p>
     * ⚠⚠ The former workaround — declaring {@code Executability: "Not Executable"} to buy the
     * warning channel — no longer exists: since {@code Fix #159} that declaration <em>parks</em>
     * the rule, so {@code loadFromString} would return an empty package and the fixture would be
     * {@code null}.
     * </p>
     */
    @Test
    void anUnresolvableOperationRefIsRejected() throws Exception
    {
        Rule r = rule("\"Sensitivity\": \"Study\","
                + " \"Scope\": {\"Domains\": {\"Include\": [\"ALL\"]}},"
                + " \"Operations\": [{\"id\": \"$nosuchop\", \"operator\": \"record_count\","
                + " \"domain\": \"DM\"}]," + " \"Check\": {\"expression\": \"$nosuchop > 0\"},"
                + " \"Outcome\": {\"Message\": \"m\", \"Output_Variables\": [\"USUBJID\"]}");
        assertNull(r.getLoadError(), "fixture must load cleanly: " + r.getLoadError());
        assertNotNull(r.getCheckExpr(),
                "fixture must compile, else the classifier rejects it for the wrong reason");
        // ⚠⚠ THE CONTROL IS THE WHOLE TEST. `record_count` is a DOMAIN_PINNED_OPERATOR, so an
        // operation with NO `domain` already reads the primary dataset and the rule is rejected
        // while its Operations block is still present — deleting the setOperations(null) below
        // would leave the assertion green and pin nothing. Pinning `domain: DM` makes the rule
        // genuinely eligible first, so the flip below can only come from the unresolvable ref.
        assertTrue(StudyRuleClassifier.isAnchorEligible(r),
                "control: a domain-pinned record_count is study-safe while the ref resolves");
        r.setOperations(null);
        assertFalse(StudyRuleClassifier.isAnchorEligible(r),
                "a rule with no Operations block cannot be inspected");
    }


    /** A Domains block with an empty or absent Include places no restriction. */
    @Test
    void anEmptyDomainsIncludeIsUnrestricted() throws Exception
    {
        Rule r = rule("\"Sensitivity\": \"Study\","
                + " \"Scope\": {\"Domains\": {\"include_split_datasets\": true}},"
                + " \"Check\": {\"expression\": \"not ds_exists(\\\"DM\\\")\"},"
                + " \"Outcome\": {\"Message\": \"m\"}");
        assertTrue(StudyRuleClassifier.hasUnrestrictedScope(r),
                "a Domains block with no Include restricts nothing");
    }


    /** Builds a Study rule with the given Operations and a `$ref`-driven Check. */
    private static Rule ruleWithOperations(String operations, String ref) throws Exception
    {
        return rule("\"Sensitivity\": \"Study\","
                + " \"Scope\": {\"Domains\": {\"Include\": [\"ALL\"]}}," + " \"Operations\": ["
                + operations + "]," + " \"Check\": {\"expression\": \"\\\"DM\\\" in " + ref + "\"},"
                + " \"Outcome\": {\"Message\": \"m\"}");
    }

    // ---- Q12: the loader rejects the contradiction outright ---------------------------------


    /**
     * {@code Sensitivity: Study} plus a restricted {@code Scope} is an authoring contradiction: the
     * finding claims to belong to the study while the scope claims it only applies to some
     * datasets. The loader must fail loud rather than let the rule slip onto the per-dataset
     * fallback, where the contradiction would never surface.
     */
    @Test
    void loaderRejectsStudySensitivityWithARestrictedScope() throws Exception
    {
        Rule scoped = rule("\"Sensitivity\": \"Study\","
                + " \"Scope\": {\"Domains\": {\"Include\": [\"MS\"]}},"
                + " \"Check\": {\"expression\": \"not ds_exists(\\\"MB\\\")\"},"
                + " \"Outcome\": {\"Message\": \"m\"}");

        assertTrue(scoped.getLoadError() != null && scoped.getLoadError().contains("Sensitivity"),
                "a scoped study rule carries a load error, was: " + scoped.getLoadError());
    }


    @Test
    void loaderAcceptsStudySensitivityWithAnUnrestrictedScope() throws Exception
    {
        assertTrue(studyRule("not ds_exists(\\\"DM\\\")").getLoadError() == null,
                "an unrestricted study rule loads cleanly");
    }


    @Test
    void aRuleWithNoCompiledExpressionIsRejected()
    {
        Rule r = new Rule();
        r.setSensitivity(net.cumba.corej.core.model.Sensitivity.STUDY);
        assertTrue(StudyRuleClassifier.readsPrimaryDataset(r),
                "an uninspectable rule gets the conservative answer");
        assertFalse(StudyRuleClassifier.isAnchorEligible(r), "and is therefore not eligible");
    }

    // ---- PLAN-scope-requirements-split — the two NEW restricting facets ---------------------


    /**
     * ⚠⚠ {@link StudyRuleClassifier#hasUnrestrictedScope} is a <b>negative</b> predicate feeding a
     * <b>load error</b>. If it stops seeing a restriction nothing goes red: rules start
     * <em>passing</em> a gate they should fail, which is the silent-weakening shape
     * {@code [[merged-gate-catches-cross-lane-conflicts]]} names. The migration moved the variable
     * restriction out of {@code Scope} and added a dataset-name axis, so both must be pinned here
     * or the gate quietly loses two thirds of its reach.
     */
    @Test
    void aRequirementsVariablesFacetRestrictsTheScope() throws Exception
    {
        for (String facet : java.util.List.of("\"All\": [\"USUBJID\"]",
                "\"Any\": [\"USUBJID\", \"STUDYID\"]", "\"None\": [\"POOLID\"]"))
        {
            Rule r = rule("\"Sensitivity\": \"Study\","
                    + " \"Scope\": {\"Domains\": {\"Include\": [\"ALL\"]}},"
                    + " \"Requirements\": {\"Variables\": {" + facet + "}},"
                    + " \"Check\": {\"expression\": \"not ds_exists(\\\"DM\\\")\"},"
                    + " \"Outcome\": {\"Message\": \"m\"}");
            assertFalse(StudyRuleClassifier.hasUnrestrictedScope(r),
                    facet + " restricts which datasets the rule runs on");
            assertNotNull(r.getLoadError(),
                    facet + " must make the Study declaration a load error");
            assertTrue(r.getLoadError().contains("Sensitivity"), r.getLoadError());
        }
    }


    /**
     * ⛔ <b>Re-armed, not deleted.</b> This used to pin that the legacy {@code Scope.Variables}
     * spelling reached {@link StudyRuleClassifier#hasUnrestrictedScope} through the dual-read shim.
     * Phase 5 deleted the binding and the shim, so the retired spelling now binds to
     * <em>nothing</em> and the classifier correctly reports "unrestricted" — there is no
     * restriction left to see.
     *
     * <p>
     * That is not a hole in the classifier: it is the exact premise loader gate R1 exists on, and
     * pinning both halves here is what keeps the pair honest. If R1 were ever relaxed, a
     * {@code Sensitivity: Study} rule carrying the old spelling would sail through this classifier
     * — so the load error is asserted, by message, alongside the classifier's blindness. The
     * <em>live</em> spelling's rejection is pinned by
     * {@link #aRequirementsVariablesFacetRestrictsTheScope()}, which is unchanged.
     * </p>
     */
    @Test
    void theRetiredScopeVariablesSpellingIsCaughtByTheLoaderNotTheClassifier() throws Exception
    {
        Rule r = rule("\"Sensitivity\": \"Study\","
                + " \"Scope\": {\"Domains\": {\"Include\": [\"ALL\"]},"
                + " \"Variables\": {\"Include\": [\"USUBJID\"]}},"
                + " \"Check\": {\"expression\": \"not ds_exists(\\\"DM\\\")\"},"
                + " \"Outcome\": {\"Message\": \"m\"}");
        assertNull(r.effectiveVariableRequirement(),
                "fixture precondition: the retired spelling must bind to nothing, or this test is"
                        + " asserting the shim it was written to retire");
        assertTrue(StudyRuleClassifier.hasUnrestrictedScope(r),
                "⛔ the classifier is blind to the retired spelling — the hole loader gate R1"
                        + " exists to close, pinned here so relaxing R1 goes red");
        assertNotNull(r.getLoadError(), "gate R1 must reject the rule outright");
        assertTrue(r.getLoadError().contains("Scope.Variables"), r.getLoadError());
        assertTrue(r.getLoadError().contains("Requirements.Variables"), r.getLoadError());
    }


    @Test
    void aScopeDatasetsFacetRestrictsTheScope() throws Exception
    {
        Rule include = rule("\"Sensitivity\": \"Study\","
                + " \"Scope\": {\"Domains\": {\"Include\": [\"ALL\"]},"
                + " \"Datasets\": {\"Include\": [\"ADSL\"]}},"
                + " \"Check\": {\"expression\": \"not ds_exists(\\\"DM\\\")\"},"
                + " \"Outcome\": {\"Message\": \"m\"}");
        assertFalse(StudyRuleClassifier.hasUnrestrictedScope(include),
                "Scope.Datasets names the file the rule runs on — a dataset restriction");
        assertNotNull(include.getLoadError());

        Rule exclude = rule("\"Sensitivity\": \"Study\","
                + " \"Scope\": {\"Domains\": {\"Include\": [\"ALL\"]},"
                + " \"Datasets\": {\"Exclude\": [\"ADSL\"]}},"
                + " \"Check\": {\"expression\": \"not ds_exists(\\\"DM\\\")\"},"
                + " \"Outcome\": {\"Message\": \"m\"}");
        assertFalse(StudyRuleClassifier.hasUnrestrictedScope(exclude));
    }


    /**
     * The control for the two tests above: an <em>empty</em> block of either kind restricts
     * nothing, so the predicate must not answer "restricted" merely because the key is present.
     * Without this a gate that rejected every rule carrying the block at all would pass them.
     */
    @Test
    void emptyNewFacetsRestrictNothing() throws Exception
    {
        Rule r = rule("\"Sensitivity\": \"Study\","
                + " \"Scope\": {\"Domains\": {\"Include\": [\"ALL\"]}, \"Datasets\": {}},"
                + " \"Requirements\": {\"Variables\": {}, \"Datasets\": [\"DM\"]},"
                + " \"Check\": {\"expression\": \"not ds_exists(\\\"DM\\\")\"},"
                + " \"Outcome\": {\"Message\": \"m\"}");
        assertTrue(StudyRuleClassifier.hasUnrestrictedScope(r),
                "an empty facet — and Requirements.Datasets, which is a capability statement and"
                        + " not a dataset selection — restrict nothing");
        assertNull(r.getLoadError(), "and the rule loads cleanly: " + r.getLoadError());
    }

}
