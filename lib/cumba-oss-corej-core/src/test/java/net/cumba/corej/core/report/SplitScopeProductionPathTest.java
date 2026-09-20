package net.cumba.corej.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.exec.OperationExecutor;
import net.cumba.corej.core.metadata.CdiscDomainResolver;
import net.cumba.corej.core.metadata.MetadataKeys;
import net.cumba.corej.core.metadata.MetadataLibraryProvider;
import net.cumba.corej.core.model.DomainScope;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Scope;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.corej.core.run.DatasetExecutionSummary;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.testkit.TestMetadataFixtures;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * <b>Split detection on the PRODUCTION call path</b> — {@code PLAN-typed-expression-engine.md}
 * phase 7c, rulings {@code D125}–{@code D125e}.
 *
 * <h2>Why this class exists — it is the shape of the defect, not just the defect</h2>
 *
 * <p>
 * {@code ScopeMatcher.describeDomainMismatch(rule, domainName, unsplitName)} derives
 * {@code isSplit = !domainName.equals(unsplitName)}: its first argument means the <b>member</b>
 * name ({@code LBCHEM}), its second the dataset's <b>canonical base</b> ({@code LB}). The function
 * was always correct and always covered — {@code ScopeMatcherDomainPrefixTest},
 * {@code ScopeMatcherSuppApFamilyTest} and {@code DefineSplitDatasetContractTest} all call it
 * <em>directly</em>, each with two <em>different</em> values.
 * </p>
 *
 * <p>
 * ⛔ <b>Production could never reach that path.</b> {@code LibraryValidator} resolved one name —
 * {@code CdiscDomainResolver.cdiscDomainOf(table)} — and handed it to
 * {@code DatasetRuleResolver.setDomainName}; the resolver passed <em>that</em> value as
 * {@code domainName} and {@code OperationExecutor.unsplitNameFromData(table)} as
 * {@code unsplitName}. <b>Both derivations read the row-0 {@code DOMAIN} cell first and return
 * it</b>, so for every dataset carrying a {@code DOMAIN} column the two arguments were equal by
 * construction: {@code isSplit} was permanently {@code false}, {@code Include_Split_Datasets} could
 * never match on either leg, and the member-name leg of {@code Include}/{@code Exclude} was dead.
 * No test saw it, because no test went through the caller.
 * </p>
 *
 * <p>
 * ⚑ Every assertion here therefore runs {@link LibraryValidator} — the real entry point, deriving
 * its own domain code from the table exactly as production does. Nothing in this class calls
 * {@code ScopeMatcher} directly; that is the whole point. The direct-call tests stay where they
 * are, pinning the function's contract, which was never the defect.
 * </p>
 *
 * <p>
 * ⭐ {@link #splitDetectionMustStayReachableThroughProduction()} is the <b>non-vacuity guard</b>: it
 * reds, naming the conflation, if the split branch ever stops being reachable through the caller —
 * the {@code D122b} discipline applied to the one defect that was already dead when it was found.
 * </p>
 */
class SplitScopeProductionPathTest
{

    /** A split member: its own name, carrying {@code DOMAIN=LB} rows. */
    private static final String SPLIT_MEMBER = "LBCHEM";

    /** The CDISC domain code both fixtures carry in their {@code DOMAIN} column. */
    private static final String DOMAIN_CODE = "LB";

    /** The non-split control: member name and domain code coincide. */
    private static final String NON_SPLIT_MEMBER = "LB";

    /** {@code Include: [LB]} — the ordinary domain-scoped rule; must run on both fixtures. */
    private static final String BASE_SCOPED = "TEST-D125-BASE";

    /** {@code Include: [LB] + Include_Split_Datasets: true} — splits only. */
    private static final String SPLITS_ONLY = "TEST-D125-SPLITS-ONLY";

    /** {@code Include: [LB] + Include_Split_Datasets: false} — non-splits only. */
    private static final String NON_SPLITS_ONLY = "TEST-D125-NONSPLITS-ONLY";

    /** {@code Include: [LBCHEM]} — the MEMBER name as a literal scope entry. */
    private static final String MEMBER_LITERAL = "TEST-D125-MEMBER";

    /**
     * The failure text every assertion below carries: a red here means the call site was
     * re-conflated, and the reader needs to be told where and why rather than shown a bare
     * {@code expected EXECUTED but was SKIPPED}.
     */
    private static final String WHY = """
            ⛔ D125: split detection is unreachable on the PRODUCTION path again.
            DatasetRuleResolver.describeScopeSkip must pass (MEMBER name, canonical base) to
            ScopeMatcher.describeDomainMismatch. Passing LibraryValidator's cdiscDomain (the
            value it hands to setDomainName) as BOTH arguments makes them equal by construction
            for every dataset with a DOMAIN column — isSplit is then permanently false,
            Include_Split_Datasets can never match, and the member-name leg of Include/Exclude
            is dead. ⚠ Nothing else in the suite can see this: every other test calls
            describeDomainMismatch DIRECTLY, with two different values.
            """;

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    /**
     * A two-row LB-family table named {@code memberName}, always carrying {@code DOMAIN=LB}. For
     * {@link #SPLIT_MEMBER} that makes it a genuine split — the member name and the data-derived
     * domain code differ, which {@link #productionDerivesTwoDifferentNamesForASplitMember()} pins.
     */
    private static IDataTable table(String memberName)
    {
        return MockTable.of().name(memberName).col("STUDYID", "S1", "S1")
                .col("USUBJID", "S1-001", "S1-002").col("DOMAIN", DOMAIN_CODE, DOMAIN_CODE)
                .col("LBTESTCD", "GLUC", "ALB").build();
    }


    /**
     * A library that knows the {@code LB} domain. The rules under test carry no {@code Classes}
     * scope, so nothing here participates in the verdict — the provider exists because
     * {@link LibraryValidator} requires one.
     */
    private static MetadataProvider provider()
    {
        IMetadataLibrary lib = TestMetadataFixtures.lib("study")
                .meta(MetadataKeys.STANDARD_NAME, "sdtmig")
                .meta(MetadataKeys.STANDARD_VERSION, "3-4")
                .table(TestMetadataFixtures.table("LB").label("Laboratory Test Results")
                        .className("Findings").structure("One record per lab test per subject")
                        .column(TestMetadataFixtures.column("STUDYID", 0, DataValueType.STRING)
                                .label("Study Identifier").core("Req").role("Identifier").build())
                        .column(TestMetadataFixtures.column("USUBJID", 1, DataValueType.STRING)
                                .label("Unique Subject Identifier").core("Req").role("Identifier")
                                .build())
                        .build())
                .build();
        return new MetadataLibraryProvider(lib);
    }


    private static Rule scopedRule(String coreId, List<String> include, Boolean splitFilter)
    {
        Rule rule = new Rule();
        rule.setId("uuid-" + coreId);
        RuleCore core = new RuleCore();
        core.setId(coreId);
        rule.setCore(core);
        rule.setDescription(coreId);
        rule.setSensitivity(Sensitivity.RECORD);
        // Matches on every row (STUDYID is always populated), so an in-scope rule really executes
        // and really reports — "no finding" can never be mistaken for "in scope but silent".
        rule.setCheck(new net.cumba.corej.core.model.CheckConditionExpression(
                net.cumba.corej.core.expr.CheckExpressionParser.parse("not empty(STUDYID)"),
                "not empty(STUDYID)"));
        net.cumba.corej.core.model.Outcome outcome = new net.cumba.corej.core.model.Outcome();
        outcome.setMessage("D125 scope probe");
        rule.setOutcome(outcome);
        Scope scope = new Scope();
        DomainScope domains = new DomainScope();
        domains.setInclude(include);
        if (splitFilter != null)
        {
            domains.setIncludeSplitDatasets(splitFilter);
        }
        scope.setDomains(domains);
        rule.setScope(scope);
        return rule;
    }


    private static List<Rule> allFourRules()
    {
        return List.of(scopedRule(BASE_SCOPED, List.of(DOMAIN_CODE), null),
                scopedRule(SPLITS_ONLY, List.of(DOMAIN_CODE), Boolean.TRUE),
                scopedRule(NON_SPLITS_ONLY, List.of(DOMAIN_CODE), Boolean.FALSE),
                scopedRule(MEMBER_LITERAL, List.of(SPLIT_MEMBER), null));
    }


    /**
     * Validates the named dataset through {@link LibraryValidator} with all four rules selected,
     * and returns the per-rule outcome keyed by CORE id. ⚑ The validator derives the domain code
     * itself ({@code CdiscDomainResolver.cdiscDomainOf}) — this harness never supplies it, so the
     * conflation under test is reproduced faithfully rather than simulated.
     */
    private static Map<String, DatasetExecutionSummary.RuleExecution> validate(String memberName)
    {
        String file = memberName.toLowerCase(Locale.ROOT) + ".xpt";
        LibraryValidator validator = LibraryValidator.builder().provider(provider())
                .rules(allFourRules()).libraryUri("file:///study/" + file)
                .targetDataset(memberName, file, table(memberName)).build();
        validator.validate();
        List<DatasetExecutionSummary> summaries = validator.getExecutionSummaries();
        assertEquals(1, summaries.size(), "one target dataset was validated");
        Map<String, DatasetExecutionSummary.RuleExecution> byCoreId = new LinkedHashMap<>();
        for (DatasetExecutionSummary.RuleExecution rx : summaries.get(0).ruleExecutions())
        {
            byCoreId.put(rx.coreId(), rx);
        }
        assertEquals(4, byCoreId.size(),
                "every selected rule must be accounted for on " + memberName + ": " + byCoreId);
        return byCoreId;
    }


    private static void assertExecuted(Map<String, DatasetExecutionSummary.RuleExecution> outcomes,
            String coreId, String why)
    {
        DatasetExecutionSummary.RuleExecution rx = outcomes.get(coreId);
        assertNotNull(rx, coreId + " produced no outcome at all");
        assertEquals("EXECUTED", rx.status(),
                why + "\nreason given: " + rx.notExecutedReason() + "\n" + WHY);
    }


    private static String assertSkipped(Map<String, DatasetExecutionSummary.RuleExecution> outcomes,
            String coreId, String why)
    {
        DatasetExecutionSummary.RuleExecution rx = outcomes.get(coreId);
        assertNotNull(rx, coreId + " produced no outcome at all");
        assertEquals("SKIPPED", rx.status(), why + "\n" + WHY);
        String reason = rx.notExecutedReason();
        assertNotNull(reason, coreId + " was skipped without a reason");
        return reason;
    }

    // -----------------------------------------------------------------------
    // The fixture itself — a split that isn't one measures nothing
    // -----------------------------------------------------------------------


    /**
     * The premise of the whole class, and of {@code D125a}: the two names production derives for a
     * split member are <b>different</b> (member {@code LBCHEM} vs. domain code {@code LB}) — and
     * the two <em>derivations</em> the caller had available answer the <b>same</b> thing, which is
     * exactly why feeding one of them into both parameters killed split detection silently.
     */
    @Test
    void productionDerivesTwoDifferentNamesForASplitMember()
    {
        IDataTable split = table(SPLIT_MEMBER);
        assertEquals(SPLIT_MEMBER, split.getMetaData().getName());
        assertEquals(DOMAIN_CODE, CdiscDomainResolver.cdiscDomainOf(split),
                "the DOMAIN column is what makes LBCHEM a split of LB");
        assertEquals(DOMAIN_CODE, OperationExecutor.unsplitNameFromData(split),
                "the canonical base is data-derived, not guessed from the name");
        assertNotEquals(CdiscDomainResolver.cdiscDomainOf(split), split.getMetaData().getName(),
                "the fixture must be a REAL split — if these coincide this class measures nothing");
        // ⛔ D125a in one assertion: the two derivations are interchangeable, so a caller that used
        // either one twice got isSplit == false for free.
        assertEquals(OperationExecutor.unsplitNameFromData(split),
                CdiscDomainResolver.cdiscDomainOf(split),
                "both derivations read row-0 DOMAIN first — that is why the conflation was silent");

        IDataTable plain = table(NON_SPLIT_MEMBER);
        assertEquals(CdiscDomainResolver.cdiscDomainOf(plain), plain.getMetaData().getName(),
                "the control must NOT be a split");
    }

    // -----------------------------------------------------------------------
    // The two Include_Split_Datasets legs, through the caller
    // -----------------------------------------------------------------------


    @Test
    void splitsOnlyRuleReachesASplitMemberAndOnlyASplitMember()
    {
        assertExecuted(validate(SPLIT_MEMBER), SPLITS_ONLY,
                "Include_Split_Datasets: true must admit the split member LBCHEM");

        String reason = assertSkipped(validate(NON_SPLIT_MEMBER), SPLITS_ONLY,
                "Include_Split_Datasets: true must reject the non-split LB");
        assertTrue(reason.contains("is not a split dataset"),
                "the skip must name the split gate, was: " + reason);
    }


    @Test
    void nonSplitsOnlyRuleIsRemovedFromASplitMember()
    {
        String reason = assertSkipped(validate(SPLIT_MEMBER), NON_SPLITS_ONLY,
                "Include_Split_Datasets: false must remove the split member LBCHEM");
        assertTrue(reason.contains(SPLIT_MEMBER),
                "the reason must name the MEMBER, which is the value that now reaches the matcher,"
                        + " was: " + reason);
        assertTrue(reason.contains("is a split dataset"),
                "the skip must name the split gate, was: " + reason);

        assertExecuted(validate(NON_SPLIT_MEMBER), NON_SPLITS_ONLY,
                "Include_Split_Datasets: false must keep the non-split LB");
    }

    // -----------------------------------------------------------------------
    // The member-name leg of Include, and the ordinary path it must not break
    // -----------------------------------------------------------------------


    @Test
    void includeMatchesTheMemberNameOfASplit()
    {
        assertExecuted(validate(SPLIT_MEMBER), MEMBER_LITERAL,
                "Include: [LBCHEM] names the member file and must reach it");

        String reason = assertSkipped(validate(NON_SPLIT_MEMBER), MEMBER_LITERAL,
                "Include: [LBCHEM] must NOT reach the unrelated member LB");
        assertTrue(reason.contains("not in Scope.Domains.Include"),
                "the skip must name the Include list, was: " + reason);
    }


    /**
     * The regression half: the base-scoped rule — the shape essentially the whole corpus uses —
     * must keep running on both, whichever leg carries it. Before the fix it matched through
     * {@code domainName} (which already <em>was</em> the base); afterwards it matches the split
     * member through the split-base re-test. Same verdict, different path, and the path is now the
     * one the matcher documents.
     */
    @Test
    void baseScopedRuleStillRunsOnBothMembers()
    {
        assertExecuted(validate(SPLIT_MEMBER), BASE_SCOPED,
                "Include: [LB] must keep covering the split member LBCHEM");
        assertExecuted(validate(NON_SPLIT_MEMBER), BASE_SCOPED,
                "Include: [LB] must keep covering the plain LB");
    }

    // -----------------------------------------------------------------------
    // ⭐ The non-vacuity guard
    // -----------------------------------------------------------------------


    /**
     * <b>The guard {@code D125e} asks for.</b> Everything above would still pass if {@code isSplit}
     * became unreachable in a <em>new</em> way and both split-filtered rules silently agreed with
     * the base-scoped one — so this test asserts the two legs of the gate answer <b>differently on
     * the same dataset</b>, and that the reason text names the MEMBER rather than the domain code.
     * Both are impossible when the caller passes one value twice: with {@code isSplit} pinned
     * {@code false}, {@code true} always skips and {@code false} always executes, on <em>every</em>
     * dataset, and the message says {@code LB}.
     */
    @Test
    void splitDetectionMustStayReachableThroughProduction()
    {
        Map<String, DatasetExecutionSummary.RuleExecution> split = validate(SPLIT_MEMBER);
        Map<String, DatasetExecutionSummary.RuleExecution> plain = validate(NON_SPLIT_MEMBER);

        String splitsOnlyOnSplit = split.get(SPLITS_ONLY).status();
        String nonSplitsOnlyOnSplit = split.get(NON_SPLITS_ONLY).status();
        assertNotEquals(splitsOnlyOnSplit, nonSplitsOnlyOnSplit,
                "the two Include_Split_Datasets legs must disagree on LBCHEM — if they agree, the"
                        + " gate is stuck and the split branch is dead.\n" + WHY);
        assertEquals("EXECUTED", splitsOnlyOnSplit, WHY);

        // …and the gate must be capable of the opposite answer, so "always true" is excluded too.
        assertNotEquals(splitsOnlyOnSplit, plain.get(SPLITS_ONLY).status(),
                "the same splits-only rule must answer differently on the non-split LB — otherwise"
                        + " the gate is stuck ON.\n" + WHY);

        // The reason text is the direct evidence that the MEMBER name reached the matcher: under
        // the conflation this string named the domain code LB and never the member.
        String reason = split.get(NON_SPLITS_ONLY).notExecutedReason();
        assertNotNull(reason, WHY);
        assertTrue(reason.startsWith("domain " + SPLIT_MEMBER + " "),
                "the matcher must have been given the MEMBER name; reason was: " + reason + "\n"
                        + WHY);
    }
}
