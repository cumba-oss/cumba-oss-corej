package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.gen.GeneratedRulePackage;
import net.cumba.corej.core.gen.SkippedSourceRule;
import net.cumba.corej.core.gen.WildcardExpander;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DatasetRuleResolverTest
{

    private static net.cumba.corej.core.model.CheckConditionExpression expr(String source)
    {
        return new net.cumba.corej.core.model.CheckConditionExpression(
                net.cumba.corej.core.expr.CheckExpressionParser.parse(source), source);
    }

    private MockLibraryProvider library;

    private DatasetRuleResolver generator;

    @BeforeEach
    void setUp()
    {
        library = new MockLibraryProvider();
        generator = new DatasetRuleResolver(library);
    }


    /** Helper: configure generator and generate. */
    private GeneratedRulePackage gen(DatasetRuleResolver gen, IDataTable table, String domain,
            String className)
    {
        gen.setDomainName(domain);
        gen.setClassName(className);
        return gen.generate(table);
    }


    private GeneratedRulePackage gen(IDataTable table, String domain, String className)
    {
        return gen(generator, table, domain, className);
    }

    // ---- Category 1: Variable Label ----


    /**
     * ⭐ Re-aimed by {@code plans/PLAN-remove-rule-generator.md}, not deleted.
     *
     * <p>
     * This used to lean on the {@code DISALLOWED_VARIABLE} generator minting
     * {@code GEN-DISALLOW-DM} so the package would be non-empty. That generator is gone — but <b>id
     * determinism is a property of the surviving delivery path too</b>: since D77 the path is
     * {@code specialiseStaticRules} → {@code RuleSpecialiser}, whose full copy carries the source
     * rule's identity fields verbatim, so the same rule must yield the same id on every run, or
     * findings stop being comparable across runs. Deleting the test with its old fixture would have
     * dropped that coverage silently, which is the shape this plan exists to avoid.
     * </p>
     */
    @Test
    void expandedRuleIdsAreDeterministic()
    {
        generator.setStaticRules(List.of(dashDtcRule("CDISC-CG0176")));
        IDataTable table = MockTable.of().name("AE").col("STUDYID", "S001")
                .col("AEDTC", "2024-01-01").build();

        GeneratedRulePackage pkg1 = gen(table, "AE", "EVENTS");

        DatasetRuleResolver second = new DatasetRuleResolver(library);
        second.setStaticRules(List.of(dashDtcRule("CDISC-CG0176")));
        GeneratedRulePackage pkg2 = gen(second, table, "AE", "EVENTS");

        assertFalse(pkg1.getRules().isEmpty(), "nothing was delivered — the pin would be vacuous");
        assertEquals(pkg1.getRules().size(), pkg2.getRules().size());

        assertEquals(pkg1.getRules().getFirst().getId(), pkg2.getRules().getFirst().getId(),
                "the expanded rule's UUID must be deterministic across generator instances");
        assertEquals(pkg1.getRules().getFirst().getCore().getId(),
                pkg2.getRules().getFirst().getCore().getId());
    }


    /** A minimal SDTM {@code --}-prefix rule, the delivery path's canonical input. */
    private static Rule dashDtcRule(String coreId)
    {
        Rule r = new Rule();
        RuleCore core = new RuleCore();
        core.setId(coreId);
        r.setCore(core);
        r.setDescription("--DTC must not be empty");
        r.setSensitivity(Sensitivity.RECORD);
        r.setExecutability(net.cumba.corej.core.model.Executability.FULLY_EXECUTABLE);
        r.setCheck(expr("empty(--DTC)"));
        Outcome outcome = new Outcome();
        outcome.setMessage("--DTC is empty");
        outcome.setOutputVariables(List.of("--DTC"));
        r.setOutcome(outcome);
        return r;
    }

    // ---- Category 2: Variable Type ----

    // ---- Category 3: Required Variable ----

    // ---- Report ----

    // ---- Category 6: Codelist Value ----

    // ---- Report markdown ----

    // ---- Category selection ----

    // ---- Category 24: Indexed Variable Rules ----

    // ---- Treatment presence check ----
    // generateTreatmentPresenceCheck retired 2026-04-28; replaced by JSON rule
    // CDISC-AD9001-TRTPRES (rules-adamig-1-3-additions.json) using the
    // variable_count Operation with name_pattern. The execution behaviour is
    // covered by the rule-execution integration tests rather than by asserting
    // on the rule-generation pipeline.

    // ---- Cross-dataset metadata checks ----

    // ---- Flag presence check ----
    // generateFlagPresenceCheck retired 2026-04-28; replaced by JSON rule
    // CDISC-AD0048 using the variable_count Operation with name_pattern. The
    // rule is now Fully Executable and surfaces under its real Core ID rather
    // than via a synthetic IDX-FLPRES-ADSL rule.

    // ---- Category 25: SDTM -- prefix expansion ----


    @Test
    void testSdtmPrefixExpansion()
    {
        // Create a static rule with -- prefix
        net.cumba.corej.core.model.Rule staticRule = new net.cumba.corej.core.model.Rule();
        net.cumba.corej.core.model.RuleCore core = new net.cumba.corej.core.model.RuleCore();
        core.setId("CDISC-CG0176");
        staticRule.setCore(core);
        staticRule.setDescription("--DTC must not be empty");
        staticRule.setSensitivity(net.cumba.corej.core.model.Sensitivity.RECORD);
        staticRule.setExecutability(net.cumba.corej.core.model.Executability.FULLY_EXECUTABLE);
        staticRule.setCheck(expr("empty(--DTC)"));
        net.cumba.corej.core.model.Outcome outcome = new net.cumba.corej.core.model.Outcome();
        outcome.setMessage("--DTC is empty");
        outcome.setOutputVariables(java.util.List.of("--DTC"));
        staticRule.setOutcome(outcome);

        generator.setStaticRules(java.util.List.of(staticRule));

        IDataTable table = MockTable.of().name("AE").col("STUDYID", "S001")
                .col("AEDTC", "2024-01-01").build();

        GeneratedRulePackage pkg = gen(table, "AE", "EVENTS");

        // The expansion keeps the base rule id verbatim (no GEN-EXP-<domain> prefix) — matching
        // the Python engine, which does not append the domain code.
        List<Rule> expanded = pkg.getRules().stream()
                .filter(r -> "CDISC-CG0176".equals(r.getCore().getId())).toList();
        assertEquals(1, expanded.size());

        Rule exp = expanded.getFirst();
        assertEquals("CDISC-CG0176", exp.getCore().getId());
        // D77: the specialised copy keeps the AUTHORED Core verbatim — the old expander stamped
        // Status "Generated" over whatever the rule declared, which misreported every shipped
        // `--` rule's status in anything that read the per-dataset copy.
        assertNull(exp.getCore().getStatus(), "the fixture authored no Status, so none appears");

        // Description is kept domain-neutral: the `--` token is preserved, NOT substituted to the
        // domain prefix (AEDTC). Only the functional Check substitution below resolves the prefix.
        assertTrue(exp.getDescription().contains("--DTC"));
        assertFalse(exp.getDescription().contains("AEDTC"));

        // The Check condition IS substituted (-- → AE prefix) — that is the functional expansion.
        String checkStr = new com.fasterxml.jackson.databind.ObjectMapper()
                .valueToTree(exp.getCheck()).toString();
        assertTrue(checkStr.contains("AEDTC"));
        assertFalse(checkStr.contains("--DTC"));
    }


    @Test
    void testSdtmPrefixExpansion_skipsNonPrefixRules()
    {
        // A rule without -- should not be expanded
        net.cumba.corej.core.model.Rule staticRule = new net.cumba.corej.core.model.Rule();
        net.cumba.corej.core.model.RuleCore core = new net.cumba.corej.core.model.RuleCore();
        core.setId("CDISC-CG0208");
        staticRule.setCore(core);
        staticRule.setDescription("STUDYID must exist");
        staticRule.setCheck(expr("var_exists(\"STUDYID\")"));

        generator.setStaticRules(java.util.List.of(staticRule));

        IDataTable table = MockTable.of().name("DM").col("STUDYID", "S001").build();

        GeneratedRulePackage pkg = gen(table, "DM", "SPECIAL PURPOSE");

        // No -- expansion: the rule has no `--`, so no "Generated"-status expansion is produced
        // for CDISC-CG0208. (After the rename an expansion would share the bare base id, so the
        // "Generated" status — not the id — is what distinguishes an expansion from a passthrough.)
        assertTrue(pkg.getRules().stream().noneMatch(r -> "CDISC-CG0208".equals(r.getCore().getId())
                && "Generated".equals(r.getCore().getStatus())));
    }

    // ---- Fix #117/#118: Scope.Data_Structures / Scope.Subclasses gates ----


    private static Rule structureScopedRule(java.util.List<String> structureInclude,
            java.util.List<String> subclassInclude)
    {
        Rule rule = new Rule();
        rule.setId("44444444-4444-4444-4444-444444444444");
        net.cumba.corej.core.model.RuleCore core = new net.cumba.corej.core.model.RuleCore();
        core.setId("TEST-DSGATE");
        core.setStatus("Published");
        core.setVersion("1");
        rule.setCore(core);
        rule.setSensitivity(net.cumba.corej.core.model.Sensitivity.RECORD);
        net.cumba.corej.core.model.Scope scope = new net.cumba.corej.core.model.Scope();
        if (structureInclude != null)
        {
            net.cumba.corej.core.model.DataStructureScope ds = new net.cumba.corej.core.model.DataStructureScope();
            ds.setInclude(structureInclude);
            scope.setDataStructures(ds);
        }
        if (subclassInclude != null)
        {
            net.cumba.corej.core.model.SubclassScope sc = new net.cumba.corej.core.model.SubclassScope();
            sc.setInclude(subclassInclude);
            scope.setSubclasses(sc);
        }
        rule.setScope(scope);
        rule.setCheck(expr("empty(STUDYID)"));
        net.cumba.corej.core.model.Outcome outcome = new net.cumba.corej.core.model.Outcome();
        outcome.setMessage("gate test");
        rule.setOutcome(outcome);
        return rule;
    }


    @Test
    void dataStructureScope_gatesAtGenerationTime()
    {
        DatasetRuleResolver gen = new DatasetRuleResolver(new AdamMockLibraryProvider());
        gen.setStaticRules(java.util.List
                .of(structureScopedRule(java.util.List.of("BASIC DATA STRUCTURE"), null)));

        // BDS dataset (PARAMCD/AVAL): the rule is admitted.
        GeneratedRulePackage bds = gen(gen, MockTable.of().name("ADLBC").col("STUDYID", "S1")
                .col("PARAMCD", "P").col("AVAL", "1").build(), "ADLBC", "BASIC DATA STRUCTURE");
        assertTrue(
                bds.getRules().stream().anyMatch(r -> "TEST-DSGATE".equals(r.getCore().getId())));

        // Structure-less dataset: skipped with the structure reason.
        DatasetRuleResolver gen2 = new DatasetRuleResolver(new AdamMockLibraryProvider());
        gen2.setStaticRules(java.util.List
                .of(structureScopedRule(java.util.List.of("BASIC DATA STRUCTURE"), null)));
        GeneratedRulePackage other = gen(gen2,
                MockTable.of().name("ADXX").col("STUDYID", "S1").build(), "ADXX",
                "BASIC DATA STRUCTURE");
        List<SkippedSourceRule> skipped = other.getSkippedSourceRules().stream()
                .filter(s -> "TEST-DSGATE".equals(s.rule().getCore().getId())).toList();
        assertEquals(1, skipped.size());
        assertEquals("data structure ADAM OTHER not in Scope.Data_Structures.Include"
                + " [BASIC DATA STRUCTURE]", skipped.getFirst().reason());
    }


    @Test
    void subclassScope_gatesAtGenerationTime()
    {
        DatasetRuleResolver gen = new DatasetRuleResolver(new AdamMockLibraryProvider());
        gen.setStaticRules(
                java.util.List.of(structureScopedRule(null, java.util.List.of("TIME-TO-EVENT"))));

        // BDS + CNSR: detected TIME-TO-EVENT — admitted.
        GeneratedRulePackage tte = gen(gen,
                MockTable.of().name("ADTTE").col("STUDYID", "S1").col("PARAMCD", "P")
                        .col("AVAL", "1").col("CNSR", "0").build(),
                "ADTTE", "BASIC DATA STRUCTURE");
        assertTrue(
                tte.getRules().stream().anyMatch(r -> "TEST-DSGATE".equals(r.getCore().getId())));

        // Plain BDS: no subclass detected — skipped (Q1 null semantics).
        DatasetRuleResolver gen2 = new DatasetRuleResolver(new AdamMockLibraryProvider());
        gen2.setStaticRules(
                java.util.List.of(structureScopedRule(null, java.util.List.of("TIME-TO-EVENT"))));
        GeneratedRulePackage plain = gen2.generate(MockTable.of().name("ADLBC").col("STUDYID", "S1")
                .col("PARAMCD", "P").col("AVAL", "1").build());
        List<SkippedSourceRule> skipped = plain.getSkippedSourceRules().stream()
                .filter(s -> "TEST-DSGATE".equals(s.rule().getCore().getId())).toList();
        assertEquals(1, skipped.size());
        assertEquals("no subclass detected but rule has Scope.Subclasses.Include [TIME-TO-EVENT]",
                skipped.getFirst().reason());
    }

    // ---- Wildcard markers in Requirements.Variables (scope gate vs expansion) ----


    /**
     * Template mirroring shipped CDISC-AD0424: wildcard markers both in the Check and in
     * {@code Requirements.Variables.All}. The generator's scope gate must treat the marker entries
     * as at-least-one-column patterns — matching them literally would skip the template before
     * {@link WildcardExpander#tryExpand} ever runs, so the rule could never fire.
     */
    private static Rule wildcardScopedTemplate()
    {
        Rule template = new Rule();
        template.setId("22222222-2222-2222-2222-222222222222");
        net.cumba.corej.core.model.RuleCore core = new net.cumba.corej.core.model.RuleCore();
        core.setId("TEST-WCSCOPE");
        core.setStatus("Published");
        core.setVersion("1");
        template.setCore(core);
        template.setDescription("TRTPGy populated but TRTPGyN missing");
        template.setSensitivity(net.cumba.corej.core.model.Sensitivity.RECORD);

        net.cumba.corej.core.model.Scope scope = new net.cumba.corej.core.model.Scope();
        net.cumba.corej.core.model.ClassScope classes = new net.cumba.corej.core.model.ClassScope();
        classes.setInclude(java.util.List.of("BASIC DATA STRUCTURE"));
        scope.setClasses(classes);
        template.setScope(scope);
        net.cumba.corej.core.model.VariableRequirement variables = new net.cumba.corej.core.model.VariableRequirement();
        variables.setAll(java.util.List.of("TRTPGy", "TRTPGyN"));
        net.cumba.corej.core.model.Requirements requirements = new net.cumba.corej.core.model.Requirements();
        requirements.setVariables(variables);
        template.setRequirements(requirements);

        template.setCheck(new net.cumba.corej.core.model.CheckConditionAll(
                java.util.List.of(expr("not empty(TRTPGy)"), expr("empty(TRTPGyN)"))));

        net.cumba.corej.core.model.Outcome outcome = new net.cumba.corej.core.model.Outcome();
        outcome.setMessage("TRTPGy populated but TRTPGyN missing");
        template.setOutcome(outcome);
        return template;
    }


    @Test
    void wildcardScopeVariables_templateExpandsWhenConcreteColumnExists()
    {
        DatasetRuleResolver gen = new DatasetRuleResolver(new AdamMockLibraryProvider());
        gen.setStaticRules(java.util.List.of(wildcardScopedTemplate()));

        GeneratedRulePackage pkg = gen(gen,
                MockTable.withColumns("STUDYID", "USUBJID", "TRTPG1", "TRTPG1N"), "ADLBC",
                "BASIC DATA STRUCTURE");

        // The template expanded against TRTPG1/TRTPG1N and was NOT skipped by the scope gate.
        List<Rule> expanded = pkg.getRules().stream()
                .filter(r -> "TEST-WCSCOPE-TRTPG1".equals(r.getCore().getId())).toList();
        assertEquals(1, expanded.size());
        assertTrue(pkg.getSkippedSourceRules().stream()
                .noneMatch(s -> "TEST-WCSCOPE".equals(s.rule().getCore().getId())));

        // The expansion's own requirement is concrete, so the runtime variable gate passes too.
        net.cumba.corej.core.model.VariableRequirement expandedVars = expanded.getFirst()
                .effectiveVariableRequirement();
        assertNotNull(expandedVars);
        assertEquals(java.util.List.of("TRTPG1", "TRTPG1N"), expandedVars.getAll());
    }


    @Test
    void wildcardScopeVariables_templateSkippedNamingEntryWhenNoColumnMatches()
    {
        DatasetRuleResolver gen = new DatasetRuleResolver(new AdamMockLibraryProvider());
        gen.setStaticRules(java.util.List.of(wildcardScopedTemplate()));

        GeneratedRulePackage pkg = gen(gen, MockTable.withColumns("STUDYID", "USUBJID", "AVAL"),
                "ADLBC", "BASIC DATA STRUCTURE");

        assertTrue(pkg.getRules().stream()
                .noneMatch(r -> String.valueOf(r.getCore().getId()).startsWith("TEST-WCSCOPE")));
        List<SkippedSourceRule> skipped = pkg.getSkippedSourceRules().stream()
                .filter(s -> "TEST-WCSCOPE".equals(s.rule().getCore().getId())).toList();
        assertEquals(1, skipped.size());
        assertEquals(
                "no variable matching Requirements.Variables.All entry TRTPGy present in dataset",
                skipped.getFirst().reason());
    }

    // ---- Phase G7: Deduplication ----

    // ---- Category 4: Expected Variable ----

    // ---- Category 5: Variable Order ----

    // ---- Category 7: TESTCD/TEST Consistency — RETIRED (Fix #366) ----

    // ---- Category 8: TSPARMCD/TSPARM Consistency — RETIRED (Fix #366) ----

    // ---- Category 9: Flag/Numeric Consistency — RETIRED (Fix #366) ----

    // ---- Category 10: Pair One-to-One ----

    // ---- Category 11: Dataset Label ----

    // ---- Category 12: Disallowed Variable ----

    // ---- Category 13: MedDRA Validation ----

    // ---- Category 14: WHO Drug Validation ----

    // ---- Phase 4 (PLAN-extend-expression-engine): Requirements.Variables patterns, -- ----


    /** Builds a minimal static rule with a {@code Requirements.Variables.All} list. */
    private static Rule variableScopedRule(String coreId, String... includeEntries)
    {
        Rule rule = new Rule();
        rule.setId(coreId);
        net.cumba.corej.core.model.RuleCore core = new net.cumba.corej.core.model.RuleCore();
        core.setId(coreId);
        rule.setCore(core);
        net.cumba.corej.core.model.VariableRequirement vr = new net.cumba.corej.core.model.VariableRequirement();
        vr.setAll(java.util.List.of(includeEntries));
        net.cumba.corej.core.model.Requirements req = new net.cumba.corej.core.model.Requirements();
        req.setVariables(vr);
        rule.setRequirements(req);
        rule.setCheck(expr("not empty(STUDYID)"));
        return rule;
    }


    @Test
    void testVariablesPatternScope_generatedWhenAColumnMatches()
    {
        generator.setStaticRules(java.util.List.of(variableScopedRule("CORE-P4-VARS", "*DY")));
        IDataTable table = MockTable.of().name("AE").col("STUDYID", "S001").col("AESTDY", "5")
                .build();

        GeneratedRulePackage pkg = gen(table, "AE", "EVENTS");

        assertTrue(
                pkg.getRules().stream().anyMatch(r -> "CORE-P4-VARS".equals(r.getCore().getId())));
        assertTrue(pkg.getSkippedSourceRules().isEmpty());
    }


    @Test
    void testVariablesPatternScope_skipReasonNamesThePattern()
    {
        generator.setStaticRules(java.util.List.of(variableScopedRule("CORE-P4-VARS", "*DY")));
        IDataTable table = MockTable.of().name("AE").col("STUDYID", "S001")
                .col("AESTDTC", "2024-01-01").build();

        GeneratedRulePackage pkg = gen(table, "AE", "EVENTS");

        assertTrue(
                pkg.getRules().stream().noneMatch(r -> "CORE-P4-VARS".equals(r.getCore().getId())));
        assertEquals(1, pkg.getSkippedSourceRules().size());
        assertEquals("no variable matching Requirements.Variables.All entry *DY present in dataset",
                pkg.getSkippedSourceRules().getFirst().reason());
    }


    @Test
    void testVariablesDashDashScope_resolvedViaFirstRowDomainColumn()
    {
        generator.setStaticRules(java.util.List.of(variableScopedRule("CORE-P4-SEQ", "--SEQ")));
        // Split dataset AE1: the prefix comes from the first-row DOMAIN value ("AE"),
        // so the Requirements.Variables entry --SEQ resolves to AESEQ.
        IDataTable table = MockTable.of().name("AE1").col("DOMAIN", "AE").col("AESEQ", "1").build();

        GeneratedRulePackage pkg = gen(table, "AE1", "EVENTS");

        assertTrue(
                pkg.getRules().stream().anyMatch(r -> "CORE-P4-SEQ".equals(r.getCore().getId())));
        assertTrue(pkg.getSkippedSourceRules().isEmpty());
    }


    @Test
    void testVariablesDashDashScope_unresolvablePrefixSkips()
    {
        generator.setStaticRules(java.util.List.of(variableScopedRule("CORE-P4-SEQ", "--SEQ")));
        // No DOMAIN column and a 4-char table name: no 2-char prefix can be derived, so the
        // entry keeps its raw --SEQ form and the lookup misses even though AESEQ is present.
        IDataTable table = MockTable.of().name("ADAE").col("AESEQ", "1").build();

        GeneratedRulePackage pkg = gen(table, "ADAE", "EVENTS");

        assertTrue(
                pkg.getRules().stream().noneMatch(r -> "CORE-P4-SEQ".equals(r.getCore().getId())));
        assertEquals(1, pkg.getSkippedSourceRules().size());
        assertTrue(pkg.getSkippedSourceRules().getFirst().reason().contains("--SEQ"));
    }

    // ---- Built-in templates: retired (Fix #366) ------------------------------

    // Two end-to-end pins lived here and were removed with their subject:
    // suffixLabelTemplateStillExpandsAfterTheTemplatesFileRename asserted that
    // rules-templates.json's renamed steering keys still bound and still produced
    // GEN-SLBL-DTF-ASTDTF / GEN-STYP-DTF-ASTDTF, and suffixLabelTemplateIsGatedByItsCategory
    // asserted the SUFFIX_LABEL_TYPE gate suppressed them. The templates file is deleted, the
    // three steering fields are gone from the Rule model, and applyTemplatePostFilters no longer
    // carries a family gate — there is nothing left for either pin to hold.
    // ⚑ noBuiltInRuleIsMergedIntoTheExecutedSet (above) is what replaces them: it asserts the
    // generator adds no id the caller did not hand it.

    // ---- applyTemplatePostFilters: which expansions survive ----
    //
    // 19 mutants survived in applyTemplatePostFilters. It is the filter that decides which
    // wildcard expansions are KEPT, and its central rule is subtle: on a two-character SDTM
    // domain, the expansion whose column is exactly `<domain><wildcard suffix>` is dropped,
    // because the `--` prefix expansion already produces that rule. Nothing pinned it, so the
    // filter could have dropped everything, or nothing, unnoticed.
    //
    // ⚠ The template below is SYNTHETIC and its id is deliberately outside every shipped
    // authority namespace (`TEST-`, like TEST-DSGATE / TEST-WCSCOPE above). It was `CORE-900001`
    // until the CORE family was retired; a shipped id must not be substituted for it, because no
    // shipped rule has this shape — the `*DTC` suffix wildcard over a bare `empty(...)` Check is
    // an expansion shape these two tests construct. Naming a real rule would make the expansion
    // ids asserted below (`-AESTDTC` and friends) a false claim about that rule: CDISC-CG0468, the
    // id that stood here briefly, is `var_exists("--TPT") and not var_exists("--TPTNUM")` and has
    // no `*` wildcard to expand at all.


    private static Rule wildcardTemplate(String coreId, String wildcardName)
    {
        Rule tpl = new Rule();
        RuleCore core = new RuleCore();
        core.setId(coreId);
        tpl.setCore(core);
        tpl.setDescription(wildcardName + " must not be empty");
        tpl.setSensitivity(Sensitivity.RECORD);
        tpl.setCheck(expr("empty(" + wildcardName + ")"));
        Outcome outcome = new Outcome();
        outcome.setMessage("m");
        outcome.setOutputVariables(List.of("USUBJID"));
        tpl.setOutcome(outcome);
        return tpl;
    }


    private List<String> expansionIdsFor(String wildcardName, String domain, IDataTable table)
    {
        generator.setStaticRules(List.of(wildcardTemplate("TEST-WCEXPAND", wildcardName)));
        return gen(table, domain, "EVENTS").getRules().stream().map(r -> r.getCore().getId())
                .filter(id -> id != null && id.startsWith("TEST-WCEXPAND")).sorted().toList();
    }


    @Test
    void testTemplatePostFilters_theDomainOwnSuffixColumnIsDroppedButItsSiblingsSurvive()
    {
        // AE + wildcard suffix DTC == AEDTC, which the `--DTC` prefix expansion already covers,
        // so AEDTC is filtered out; AESTDTC and AEENDTC are not the domain's own suffix column
        // and must survive.
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S1").col("AEDTC", "")
                .col("AESTDTC", "").col("AEENDTC", "").build();

        List<String> ids = expansionIdsFor("*DTC", "AE", ae);

        assertFalse(ids.contains("TEST-WCEXPAND-AEDTC"),
                "the domain's own suffix column must be dropped: " + ids);
        assertTrue(ids.contains("TEST-WCEXPAND-AESTDTC"), ids.toString());
        assertTrue(ids.contains("TEST-WCEXPAND-AEENDTC"), ids.toString());
    }


    @Test
    void testTemplatePostFilters_aNonSdtmDomainKeepsEvenItsOwnSuffixColumn()
    {
        // The drop is gated on a two-character upper-case SDTM domain. ADSL is four characters,
        // so nothing is filtered.
        IDataTable adsl = MockTable.of().name("ADSL").col("USUBJID", "S1").col("ADSLDTC", "")
                .col("TRTSDTC", "").build();

        List<String> ids = expansionIdsFor("*DTC", "ADSL", adsl);

        assertTrue(ids.contains("TEST-WCEXPAND-ADSLDTC"),
                "a non-SDTM domain must not trigger the prefix drop: " + ids);
    }

    // ---- Mock Library Provider ----

    private static class MockLibraryProvider implements net.cumba.corej.core.exec.MetadataProvider
    {

        @Override
        public List<Map<String, String>> getDomainVariables(String domain)
        {
            if ("DM".equals(domain))
            {
                return List.of(
                        Map.of("name", "STUDYID", "label", "Study Identifier", "simpleDatatype",
                                "Char", "core", "Req"),
                        Map.of("name", "USUBJID", "label", "Unique Subject Identifier",
                                "simpleDatatype", "Char", "core", "Req"),
                        Map.of("name", "SEX", "label", "Sex", "simpleDatatype", "Char", "core",
                                "Req", "codelist", "SEX"));
            }
            return List.of();
        }


        @Override
        public List<String> getRequiredVariables(String domain)
        {
            return List.of();
        }


        @Override
        public List<String> getExpectedVariables(String domain)
        {
            return List.of();
        }


        @Override
        public List<String> getColumnOrder(String domain)
        {
            return List.of();
        }


        @Override
        public List<String> getModelColumnOrder(String domain)
        {
            return List.of();
        }


        @Override
        public boolean isDomainCustom(String domain)
        {
            return false;
        }


        @Override
        public Map<String, String> getVariableMetadata(String d, String v)
        {
            return Map.of();
        }


        @Override
        public Map<String, String> getDatasetMetadata(String domain)
        {
            return Map.of();
        }


        @Override
        public java.util.Optional<Boolean> isCodelistExtensible(String cl)
        {
            return java.util.Optional.of(!"SEX".equals(cl)); // SEX is non-extensible in mock
        }


        @Override
        public List<String> getCodelistTerms(String code)
        {
            if ("SEX".equals(code))
            {
                return List.of("M", "F", "U", "UNDIFFERENTIATED");
            }
            return List.of();
        }


        @Override
        public Map<String, String> getCodelistTermMappings(String cl)
        {
            return Map.of();
        }


        @Override
        public String getStandard()
        {
            return "SDTMIG";
        }


        @Override
        public String getVersion()
        {
            return "3.4";
        }
    }


    /**
     * Mock library provider that returns "ADaMIG" as the standard. Used for testing ADaM-specific
     * rule generation (treatment presence, flag presence, etc.).
     */
    private static class AdamMockLibraryProvider extends MockLibraryProvider
    {

        @Override
        public String getStandard()
        {
            return "ADaMIG";
        }


        @Override
        public String getVersion()
        {
            return "1.3";
        }
    }

    /** Builds a minimal static rule scoped by {@code Scope.Datasets}. */
    private static Rule datasetScopedRule(String coreId, java.util.List<String> include,
            java.util.@org.jspecify.annotations.Nullable List<String> exclude)
    {
        Rule rule = new Rule();
        rule.setId(coreId);
        net.cumba.corej.core.model.RuleCore core = new net.cumba.corej.core.model.RuleCore();
        core.setId(coreId);
        rule.setCore(core);
        net.cumba.corej.core.model.Scope scope = new net.cumba.corej.core.model.Scope();
        net.cumba.corej.core.model.DatasetScope ds = new net.cumba.corej.core.model.DatasetScope();
        ds.setInclude(include);
        ds.setExclude(exclude);
        scope.setDatasets(ds);
        rule.setScope(scope);
        rule.setCheck(expr("not empty(STUDYID)"));
        return rule;
    }


    @Test
    void scopeDatasets_generatedWhenTheMemberNameMatches()
    {
        generator.setStaticRules(
                java.util.List.of(datasetScopedRule("CORE-DS-IN", java.util.List.of("AE"), null)));
        IDataTable table = MockTable.of().name("AE").col("STUDYID", "S001").build();

        GeneratedRulePackage pkg = gen(table, "AE", "EVENTS");

        assertTrue(pkg.getRules().stream().anyMatch(r -> "CORE-DS-IN".equals(r.getCore().getId())));
        assertTrue(pkg.getSkippedSourceRules().isEmpty());
    }


    @Test
    void scopeDatasets_skipReasonNamesTheAxis()
    {
        generator.setStaticRules(java.util.List
                .of(datasetScopedRule("CORE-DS-IN", java.util.List.of("ADSL"), null)));
        IDataTable table = MockTable.of().name("AE").col("STUDYID", "S001").build();

        GeneratedRulePackage pkg = gen(table, "AE", "EVENTS");

        assertTrue(
                pkg.getRules().stream().noneMatch(r -> "CORE-DS-IN".equals(r.getCore().getId())));
        assertEquals(1, pkg.getSkippedSourceRules().size());
        assertEquals("dataset AE not in Scope.Datasets.Include [ADSL]",
                pkg.getSkippedSourceRules().getFirst().reason());
    }


    /**
     * ⚠⚠ The axis matches the <b>member file name</b>, never the domain code — and on a split
     * dataset those differ. {@code Scope.Domains: ["AE"]} covers the member {@code AE1} through its
     * data-derived base; {@code Scope.Datasets: ["AE"]} does not, and must not: it means the file.
     * A test using an unsplit dataset cannot tell the two readings apart, which is why this one is
     * split.
     */
    @Test
    void scopeDatasets_matchesTheMemberNameNotTheDomainCode()
    {
        generator.setStaticRules(
                java.util.List.of(datasetScopedRule("CORE-DS-MEM", java.util.List.of("AE"), null)));
        IDataTable split = MockTable.of().name("AE1").col("DOMAIN", "AE").col("STUDYID", "S001")
                .build();

        GeneratedRulePackage pkg = gen(split, "AE", "EVENTS");

        assertTrue(
                pkg.getRules().stream().noneMatch(r -> "CORE-DS-MEM".equals(r.getCore().getId())),
                "the member is AE1, so an AE name-scope does not select it");
        assertEquals("dataset AE1 not in Scope.Datasets.Include [AE]",
                pkg.getSkippedSourceRules().getFirst().reason());

        // Control: the same member IS selected when the entry names the file.
        generator.setStaticRules(java.util.List
                .of(datasetScopedRule("CORE-DS-MEM", java.util.List.of("AE1"), null)));
        GeneratedRulePackage byMember = gen(split, "AE", "EVENTS");
        assertTrue(byMember.getRules().stream()
                .anyMatch(r -> "CORE-DS-MEM".equals(r.getCore().getId())));
    }


    @Test
    void scopeDatasets_excludeSkipsWithTheEntryNamed()
    {
        generator.setStaticRules(java.util.List
                .of(datasetScopedRule("CORE-DS-EX", java.util.List.of(), java.util.List.of("AE"))));
        IDataTable table = MockTable.of().name("AE").col("STUDYID", "S001").build();

        GeneratedRulePackage pkg = gen(table, "AE", "EVENTS");

        assertTrue(
                pkg.getRules().stream().noneMatch(r -> "CORE-DS-EX".equals(r.getCore().getId())));
        assertEquals("dataset AE matches Scope.Datasets.Exclude entry AE",
                pkg.getSkippedSourceRules().getFirst().reason());
    }

}
