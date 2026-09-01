package net.cumba.cdisc.core.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.exec.MockTable;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RuleGeneratorTest
{

    private MockLibraryProvider library;

    private RuleGenerator generator;

    @BeforeEach
    void setUp()
    {
        library = new MockLibraryProvider();
        generator = new RuleGenerator(library, "sdtmct-2025-09-26");
    }


    /** Helper: configure generator and generate. */
    private GeneratedRulePackage gen(RuleGenerator gen, IDataTable table, String domain,
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


    /** Helper: generate using an ADaM-standard generator (for ADaM-specific rules). */
    private GeneratedRulePackage genAdam(IDataTable table, String domain, String className)
    {
        RuleGenerator adamGen = new RuleGenerator(new AdamMockLibraryProvider(), null);
        return gen(adamGen, table, domain, className);
    }

    // ---- Category 1: Variable Label ----


    @Test
    void testLabelRule_nowStaticNotGenerated()
    {
        // The generator has produced no per-variable label rules since category 1 was retired.
        // Its GEN-VMCALM-LBL built-in template carrier went with rules-templates.json (Fix #366);
        // the surviving corpus carriers of label-vs-library are CDISC-CG0303 / FDA-SD0063 / … .
        IDataTable table = MockTable.of().name("DM").col("STUDYID", "S001").col("USUBJID", "SUBJ01")
                .build();

        GeneratedRulePackage pkg = gen(table, "DM", "SPECIAL PURPOSE");

        List<Rule> labelRules = pkg.getRules().stream()
                .filter(r -> r.getCore().getId().startsWith("GEN-LBL-")).toList();
        assertTrue(labelRules.isEmpty());
    }


    @Test
    void testGeneratedRuleIdsAreDeterministic()
    {
        // ⚑ SPONSORX is not in the mock Library, so DISALLOWED_VARIABLE mints GEN-DISALLOW-DM.
        // Before Fix #366 the built-in templates alone filled this package and a bare STUDYID
        // table sufficed; with rules-templates.json gone the fixture has to give a live generator
        // something to do, or the package is empty and this asserts nothing.
        IDataTable table = MockTable.of().name("DM").col("STUDYID", "S001").col("SPONSORX", "x")
                .build();

        GeneratedRulePackage pkg1 = gen(table, "DM", "SPECIAL PURPOSE");
        GeneratedRulePackage pkg2 = gen(table, "DM", "SPECIAL PURPOSE");

        assertFalse(pkg1.getRules().isEmpty(), "nothing was generated — the pin would be vacuous");

        assertEquals(pkg1.getRules().get(0).getId(), pkg2.getRules().get(0).getId());
        assertEquals(pkg1.getRules().get(0).getCore().getId(),
                pkg2.getRules().get(0).getCore().getId());
    }

    // ---- Category 2: Variable Type ----


    @Test
    void testTypeRule_nowStaticNotGenerated()
    {
        // Cat 2 type rules are now handled by static VMCALM rule CORE-001082
        IDataTable table = MockTable.of().name("DM").col("STUDYID", "S001").build();

        GeneratedRulePackage pkg = gen(table, "DM", "SPECIAL PURPOSE");

        List<Rule> typeRules = pkg.getRules().stream()
                .filter(r -> r.getCore().getId().startsWith("GEN-TYP-")).toList();
        assertTrue(typeRules.isEmpty());
    }

    // ---- Category 3: Required Variable ----


    @Test
    void testRequiredRule_nowStaticNotGenerated()
    {
        // Cat 3 required rules are now handled by static rule CORE-000355
        IDataTable table = MockTable.of().name("DM").col("STUDYID", "S001").build();

        GeneratedRulePackage pkg = gen(table, "DM", "SPECIAL PURPOSE");

        List<Rule> reqRules = pkg.getRules().stream()
                .filter(r -> r.getCore().getId().startsWith("GEN-REQ-")).toList();
        assertTrue(reqRules.isEmpty());
    }


    @Test
    void testRequiredRule_notGenerated_whenPresent()
    {
        // All required variables (STUDYID, USUBJID, SEX) are present
        IDataTable table = MockTable.of().name("DM").col("STUDYID", "S001").col("USUBJID", "SUBJ01")
                .col("SEX", "M").build();

        GeneratedRulePackage pkg = gen(table, "DM", "SPECIAL PURPOSE");

        List<Rule> reqRules = pkg.getRules().stream()
                .filter(r -> r.getCore().getId().startsWith("GEN-REQ-")).toList();
        // All required variables are present, so no REQ rules
        assertTrue(reqRules.isEmpty());
    }

    // ---- Report ----


    @Test
    void testReport_generated()
    {
        IDataTable table = MockTable.of().name("DM").col("STUDYID", "S001").col("SEX", "M").build();

        GeneratedRulePackage pkg = gen(table, "DM", "SPECIAL PURPOSE");
        RuleGenerationReport report = pkg.getReport();

        // With Cat 1-4 removed, remaining generators (codelist, dataset label, etc.)
        // still produce rules. SEX has a non-extensible codelist.
        assertTrue(report.getGeneratedCount() > 0);
        String md = report.toMarkdown();
        assertTrue(md.contains("DM"));
    }

    // ---- Category 6: Codelist Value ----


    @Test
    void testCodelistRule_nonExtensible()
    {
        IDataTable table = MockTable.of().name("DM").col("STUDYID", "S001").col("USUBJID", "SUBJ01")
                .col("SEX", "M").build();

        GeneratedRulePackage pkg = gen(table, "DM", "SPECIAL PURPOSE");

        List<Rule> clRules = pkg.getRules().stream()
                .filter(r -> r.getCore().getId().startsWith("GEN-CL-")).toList();
        // SEX has non-extensible codelist
        assertEquals(1, clRules.size());
        assertEquals("GEN-CL-DM-SEX", clRules.getFirst().getCore().getId());
    }


    @Test
    void testCodelistRule_extensible_skipped()
    {
        IDataTable table = MockTable.of().name("DM").col("STUDYID", "S001").col("USUBJID", "SUBJ01")
                .col("SEX", "M").build();

        GeneratedRulePackage pkg = gen(table, "DM", "SPECIAL PURPOSE");
        RuleGenerationReport report = pkg.getReport();

        // SEX is non-extensible, so it should be generated, not skipped for codelist
        assertTrue(report.getSkippedRules().stream().noneMatch(
                s -> "SEX".equals(s.variable()) && s.category() == RuleCategory.CODELIST_VALUE));
    }

    // ---- Report markdown ----


    @Test
    void testReport_markdown_containsAllSections()
    {
        IDataTable table = MockTable.of().name("DM").col("STUDYID", "S001").col("SEX", "M")
                .col("CUSTOM", "X").build();

        GeneratedRulePackage pkg = gen(table, "DM", "SPECIAL PURPOSE");
        String md = pkg.getReport().toMarkdown();

        assertTrue(md.contains("# Rule Generation Report"));
        assertTrue(md.contains("SDTMIG"));
        assertTrue(md.contains("VARIABLE_LABEL"));
        assertTrue(md.contains("CODELIST_VALUE"));
        assertTrue(md.contains("DISALLOWED_VARIABLE"));
        assertTrue(md.contains("CUSTOM"));
    }

    // ---- Category selection ----


    @Test
    void testDisabledCategories()
    {
        RuleGenerator labelOnly = new RuleGenerator(library, null, null, "sdtmct-2025-09-26",
                EnumSet.of(RuleCategory.VARIABLE_LABEL));

        IDataTable table = MockTable.of().name("DM").col("STUDYID", "S001").col("USUBJID", "SUBJ01")
                .build();

        GeneratedRulePackage pkg = gen(labelOnly, table, "DM", "SPECIAL PURPOSE");

        assertTrue(
                pkg.getRules().stream().allMatch(r -> r.getCore().getId().startsWith("GEN-LBL-")));
    }

    // ---- Category 24: Indexed Variable Rules ----


    @Test
    void testIndexedVar_trtPairing()
    {
        IDataTable table = MockTable.of().name("ADSL").col("STUDYID", "S001")
                .col("TRT01P", "Drug A").col("TRT02P", "Drug B").col("TRT02PN", "2").build();

        GeneratedRulePackage pkg = gen(table, "ADSL", "SUBJECT LEVEL ANALYSIS DATASET");

        List<Rule> idxRules = pkg.getRules().stream()
                .filter(r -> r.getCore().getId().startsWith("GEN-IDX-")).toList();
        // TRT02P ↔ TRT02PN one-to-one
        assertTrue(idxRules.stream().anyMatch(r -> r.getCore().getId().contains("TRT02P")));
        // TRT01PN missing (TRT01P exists but no TRT01PN) — NOT a sequence error
        // TRT02PN exists but TRT02P also exists — no missing pair rule
    }


    @Test
    void testIndexedVar_trtSequencing()
    {
        // TRT02P exists but TRT01P is missing — sequencing violation
        IDataTable table = MockTable.of().name("ADSL").col("STUDYID", "S001")
                .col("TRT02P", "Drug B").build();

        GeneratedRulePackage pkg = gen(table, "ADSL", "SUBJECT LEVEL ANALYSIS DATASET");

        List<Rule> seqRules = pkg.getRules().stream()
                .filter(r -> r.getCore().getId().contains("TRTSEQ")).toList();
        assertEquals(1, seqRules.size());
        assertTrue(seqRules.getFirst().getDescription().contains("TRT01P"));
    }


    /**
     * The inversion of {@code testIndexedVar_anlFlags}. {@code ANLzzFL} / {@code ANLzzFN} had three
     * built-in template carriers ({@code GEN-ANLVAL} / {@code GEN-ANLFNVAL} / {@code GEN-ANLPAIR});
     * all three went with {@code rules-templates.json} (Fix #366) and the generator has no
     * {@code PAT_ANL_ZZ} of its own.
     */
    @Test
    void indexedVarAnlFlagBuiltInsAreRetired()
    {
        IDataTable table = MockTable.of().name("ADTTE").col("STUDYID", "S001").col("ANL01FL", "Y")
                .col("ANL01FN", "1").build();

        GeneratedRulePackage pkg = gen(table, "ADTTE", "BASIC DATA STRUCTURE");

        List<Rule> anlRules = pkg.getRules().stream()
                .filter(r -> r.getCore().getId().contains("ANL")).toList();
        assertTrue(anlRules.isEmpty(), () -> "ANL rules are back: "
                + anlRules.stream().map(r -> r.getCore().getId()).toList());
    }


    @Test
    void testIndexedVar_critPairing()
    {
        // CRIT1FL exists but CRIT1 missing
        IDataTable table = MockTable.of().name("ADVS").col("STUDYID", "S001").col("CRIT1FL", "Y")
                .build();

        GeneratedRulePackage pkg = gen(table, "ADVS", "BASIC DATA STRUCTURE");

        List<Rule> critRules = pkg.getRules().stream()
                .filter(r -> r.getCore().getId().contains("CRIT")).toList();
        assertTrue(critRules.stream().anyMatch(r -> r.getDescription().contains("CRIT1")
                && r.getDescription().contains("missing")));
    }


    @Test
    void testIndexedVar_deterministicIds()
    {
        IDataTable table = MockTable.of().name("ADSL").col("STUDYID", "S001")
                .col("TRT01P", "Drug A").col("TRT01PN", "1").build();

        GeneratedRulePackage pkg1 = gen(table, "ADSL", "SUBJECT LEVEL ANALYSIS DATASET");
        GeneratedRulePackage pkg2 = gen(table, "ADSL", "SUBJECT LEVEL ANALYSIS DATASET");

        List<Rule> idx1 = pkg1.getRules().stream()
                .filter(r -> r.getCore().getId().startsWith("GEN-IDX-")).toList();
        List<Rule> idx2 = pkg2.getRules().stream()
                .filter(r -> r.getCore().getId().startsWith("GEN-IDX-")).toList();
        assertEquals(idx1.size(), idx2.size());
        for (int i = 0; i < idx1.size(); i++)
        {
            assertEquals(idx1.get(i).getId(), idx2.get(i).getId());
        }
    }

    // ---- Treatment presence check ----
    // generateTreatmentPresenceCheck retired 2026-04-28; replaced by JSON rule
    // CDISC-AD9001-TRTPRES (rules-adamig-1-3-additions.json) using the
    // variable_count Operation with name_pattern. The execution behaviour is
    // covered by the rule-execution integration tests rather than by asserting
    // on the rule-generation pipeline.

    // ---- Cross-dataset metadata checks ----


    @Test
    void testCrossDatasetMetadata_labelMismatch()
    {
        // ADSL has STUDYID with label "Study Identifier"
        IDataTable adsl = MockTable.of().name("ADSL").col("STUDYID", "S001").build();
        // Set label on ADSL's STUDYID column via the mock
        // (MockTable doesn't set labels, so labels will be null — no mismatch)

        // BDS dataset has STUDYID
        IDataTable bds = MockTable.of().name("ADVS").col("STUDYID", "S001").col("PARAMCD", "SYSBP")
                .build();

        generator.setDatasetResolver(domainName -> "ADSL".equals(domainName) ? adsl : null);

        GeneratedRulePackage pkg = gen(bds, "ADVS", "BASIC DATA STRUCTURE");

        // With null labels, no mismatch rules generated
        assertTrue(pkg.getRules().stream()
                .noneMatch(r -> r.getCore().getId().startsWith("GEN-XDLBL-")));
    }


    @Test
    void testCrossDatasetMetadata_noResolver()
    {
        // Cross-dataset metadata checks are now template-driven (CDISC-AD0085/0086/0590).
        // Without a resolver, the cross_dataset_variable_metadata Operations return
        // empty results, so the rules produce no violations (not skipped, just no matches).
        IDataTable table = MockTable.of().name("ADVS").col("STUDYID", "S001").build();

        GeneratedRulePackage pkg = gen(table, "ADVS", "BASIC DATA STRUCTURE");
        assertNotNull(pkg);
    }


    @Test
    void testCrossDatasetMetadata_notAppliedToADSL()
    {
        IDataTable adsl = MockTable.of().name("ADSL").col("STUDYID", "S001").build();

        generator.setDatasetResolver(domainName -> "ADSL".equals(domainName) ? adsl : null);

        GeneratedRulePackage pkg = gen(adsl, "ADSL", "SUBJECT LEVEL ANALYSIS DATASET");

        // ADSL doesn't compare against itself — no cross-dataset rules
        assertTrue(pkg.getRules().stream().noneMatch(r ->
        {
            String id = r.getCore().getId();
            return id.startsWith("GEN-XDLBL-") || id.startsWith("GEN-XDTYP-")
                    || id.startsWith("GEN-XDFMT-") || id.startsWith("GEN-XDVAL-");
        }));
    }

    // ---- Flag presence check ----
    // generateFlagPresenceCheck retired 2026-04-28; replaced by JSON rule
    // CDISC-AD0048 using the variable_count Operation with name_pattern. The
    // rule is now Fully Executable and surfaces under its real Core ID rather
    // than via a synthetic IDX-FLPRES-ADSL rule.


    @Test
    void testNoSyntheticFlPresenceRuleGenerated()
    {
        // IDX-FLPRES synthetic rule no longer exists — the JSON rule
        // CDISC-AD0048 carries the same intent.
        IDataTable table = MockTable.of().name("ADSL").col("STUDYID", "S001")
                .col("USUBJID", "SUBJ01").col("TRT01P", "Drug A").build();

        GeneratedRulePackage pkg = genAdam(table, "ADSL", "SUBJECT LEVEL ANALYSIS DATASET");

        assertTrue(
                pkg.getRules().stream().noneMatch(r -> r.getCore().getId().contains("IDX-FLPRES")));
    }


    @Test
    void testNoSyntheticTreatmentPresenceRuleGenerated()
    {
        // IDX-TRTPRES synthetic rule no longer exists — the JSON rule
        // CDISC-AD9001-TRTPRES carries the same intent.
        IDataTable table = MockTable.of().name("ADBDS").col("STUDYID", "S001")
                .col("USUBJID", "SUBJ01").col("PARAMCD", "PARAM1").build();

        GeneratedRulePackage pkg = genAdam(table, "ADBDS", "BASIC DATA STRUCTURE");

        assertTrue(pkg.getRules().stream()
                .noneMatch(r -> r.getCore().getId().contains("IDX-TRTPRES")));
    }

    // ---- Category 25: SDTM -- prefix expansion ----


    @Test
    void testSdtmPrefixExpansion()
    {
        // Create a static rule with -- prefix
        net.cumba.cdisc.core.model.Rule staticRule = new net.cumba.cdisc.core.model.Rule();
        net.cumba.cdisc.core.model.RuleCore core = new net.cumba.cdisc.core.model.RuleCore();
        core.setId("CORE-000001");
        staticRule.setCore(core);
        staticRule.setDescription("--DTC must not be empty");
        staticRule.setSensitivity(net.cumba.cdisc.core.model.Sensitivity.RECORD);
        staticRule.setExecutability(net.cumba.cdisc.core.model.Executability.FULLY_EXECUTABLE);
        staticRule.setCheck(net.cumba.cdisc.core.model.CheckConditionLeaf.builder().name("--DTC")
                .operator("empty").build());
        net.cumba.cdisc.core.model.Outcome outcome = new net.cumba.cdisc.core.model.Outcome();
        outcome.setMessage("--DTC is empty");
        outcome.setOutputVariables(java.util.List.of("--DTC"));
        staticRule.setOutcome(outcome);

        generator.setStaticRules(java.util.List.of(staticRule));

        IDataTable table = MockTable.of().name("AE").col("STUDYID", "S001")
                .col("AEDTC", "2024-01-01").build();

        GeneratedRulePackage pkg = gen(table, "AE", "EVENTS");

        // The expansion keeps the base CORE id verbatim (no GEN-EXP-<domain> prefix) — matching
        // the Python engine, which does not append the domain code.
        List<Rule> expanded = pkg.getRules().stream()
                .filter(r -> "CORE-000001".equals(r.getCore().getId())).toList();
        assertEquals(1, expanded.size());

        Rule exp = expanded.getFirst();
        assertEquals("CORE-000001", exp.getCore().getId());
        assertEquals("Generated", exp.getCore().getStatus());

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
        net.cumba.cdisc.core.model.Rule staticRule = new net.cumba.cdisc.core.model.Rule();
        net.cumba.cdisc.core.model.RuleCore core = new net.cumba.cdisc.core.model.RuleCore();
        core.setId("CORE-000002");
        staticRule.setCore(core);
        staticRule.setDescription("STUDYID must exist");
        staticRule.setCheck(net.cumba.cdisc.core.model.CheckConditionLeaf.builder().name("STUDYID")
                .operator("var_exists").build());

        generator.setStaticRules(java.util.List.of(staticRule));

        IDataTable table = MockTable.of().name("DM").col("STUDYID", "S001").build();

        GeneratedRulePackage pkg = gen(table, "DM", "SPECIAL PURPOSE");

        // No -- expansion: the rule has no `--`, so no "Generated"-status expansion is produced
        // for CORE-000002. (After the rename an expansion would share the bare CORE id, so the
        // "Generated" status — not the id — is what distinguishes an expansion from a passthrough.)
        assertTrue(pkg.getRules().stream().noneMatch(r -> "CORE-000002".equals(r.getCore().getId())
                && "Generated".equals(r.getCore().getStatus())));
    }

    // ---- Fix #117/#118: Scope.Data_Structures / Scope.Subclasses gates ----


    private static Rule structureScopedRule(java.util.List<String> structureInclude,
            java.util.List<String> subclassInclude)
    {
        Rule rule = new Rule();
        rule.setId("44444444-4444-4444-4444-444444444444");
        net.cumba.cdisc.core.model.RuleCore core = new net.cumba.cdisc.core.model.RuleCore();
        core.setId("TEST-DSGATE");
        core.setStatus("Published");
        core.setVersion("1");
        rule.setCore(core);
        rule.setSensitivity(net.cumba.cdisc.core.model.Sensitivity.RECORD);
        net.cumba.cdisc.core.model.Scope scope = new net.cumba.cdisc.core.model.Scope();
        if (structureInclude != null)
        {
            net.cumba.cdisc.core.model.DataStructureScope ds = new net.cumba.cdisc.core.model.DataStructureScope();
            ds.setInclude(structureInclude);
            scope.setDataStructures(ds);
        }
        if (subclassInclude != null)
        {
            net.cumba.cdisc.core.model.SubclassScope sc = new net.cumba.cdisc.core.model.SubclassScope();
            sc.setInclude(subclassInclude);
            scope.setSubclasses(sc);
        }
        rule.setScope(scope);
        rule.setCheck(net.cumba.cdisc.core.model.CheckConditionLeaf.builder().name("STUDYID")
                .operator("empty").build());
        net.cumba.cdisc.core.model.Outcome outcome = new net.cumba.cdisc.core.model.Outcome();
        outcome.setMessage("gate test");
        rule.setOutcome(outcome);
        return rule;
    }


    @Test
    void dataStructureScope_gatesAtGenerationTime()
    {
        RuleGenerator gen = new RuleGenerator(new AdamMockLibraryProvider(), null);
        gen.setStaticRules(java.util.List
                .of(structureScopedRule(java.util.List.of("BASIC DATA STRUCTURE"), null)));

        // BDS dataset (PARAMCD/AVAL): the rule is admitted.
        GeneratedRulePackage bds = gen(gen, MockTable.of().name("ADLBC").col("STUDYID", "S1")
                .col("PARAMCD", "P").col("AVAL", "1").build(), "ADLBC", "BASIC DATA STRUCTURE");
        assertTrue(
                bds.getRules().stream().anyMatch(r -> "TEST-DSGATE".equals(r.getCore().getId())));

        // Structure-less dataset: skipped with the structure reason.
        RuleGenerator gen2 = new RuleGenerator(new AdamMockLibraryProvider(), null);
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
        RuleGenerator gen = new RuleGenerator(new AdamMockLibraryProvider(), null);
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
        RuleGenerator gen2 = new RuleGenerator(new AdamMockLibraryProvider(), null);
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
        net.cumba.cdisc.core.model.RuleCore core = new net.cumba.cdisc.core.model.RuleCore();
        core.setId("TEST-WCSCOPE");
        core.setStatus("Published");
        core.setVersion("1");
        template.setCore(core);
        template.setDescription("TRTPGy populated but TRTPGyN missing");
        template.setSensitivity(net.cumba.cdisc.core.model.Sensitivity.RECORD);

        net.cumba.cdisc.core.model.Scope scope = new net.cumba.cdisc.core.model.Scope();
        net.cumba.cdisc.core.model.ClassScope classes = new net.cumba.cdisc.core.model.ClassScope();
        classes.setInclude(java.util.List.of("BASIC DATA STRUCTURE"));
        scope.setClasses(classes);
        template.setScope(scope);
        net.cumba.cdisc.core.model.VariableRequirement variables = new net.cumba.cdisc.core.model.VariableRequirement();
        variables.setAll(java.util.List.of("TRTPGy", "TRTPGyN"));
        net.cumba.cdisc.core.model.Requirements requirements = new net.cumba.cdisc.core.model.Requirements();
        requirements.setVariables(variables);
        template.setRequirements(requirements);

        template.setCheck(new net.cumba.cdisc.core.model.CheckConditionAll(java.util.List.of(
                net.cumba.cdisc.core.model.CheckConditionLeaf.builder().name("TRTPGy")
                        .operator("non_empty").build(),
                net.cumba.cdisc.core.model.CheckConditionLeaf.builder().name("TRTPGyN")
                        .operator("empty").build())));

        net.cumba.cdisc.core.model.Outcome outcome = new net.cumba.cdisc.core.model.Outcome();
        outcome.setMessage("TRTPGy populated but TRTPGyN missing");
        template.setOutcome(outcome);
        return template;
    }


    @Test
    void wildcardScopeVariables_templateExpandsWhenConcreteColumnExists()
    {
        RuleGenerator gen = new RuleGenerator(new AdamMockLibraryProvider(), null);
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
        net.cumba.cdisc.core.model.VariableRequirement expandedVars = expanded.getFirst()
                .effectiveVariableRequirement();
        assertNotNull(expandedVars);
        assertEquals(java.util.List.of("TRTPG1", "TRTPG1N"), expandedVars.getAll());
    }


    @Test
    void wildcardScopeVariables_templateSkippedNamingEntryWhenNoColumnMatches()
    {
        RuleGenerator gen = new RuleGenerator(new AdamMockLibraryProvider(), null);
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


    @Test
    void testDeduplication_skipsExistingRules()
    {
        IDataTable table = MockTable.of().name("DM").col("STUDYID", "S001").col("USUBJID", "SUBJ01")
                .col("SEX", "M").build();

        // First generate without dedup
        GeneratedRulePackage withoutDedup = gen(table, "DM", "SPECIAL PURPOSE");
        int totalWithout = withoutDedup.getRules().size();
        assertTrue(totalWithout > 0, "Should have at least one generated rule");

        // Pick an actual generated rule ID to dedup against
        String firstRuleId = withoutDedup.getRules().getFirst().getCore().getId();
        generator.setExistingRuleIds(java.util.Set.of(firstRuleId));
        GeneratedRulePackage withDedup = gen(table, "DM", "SPECIAL PURPOSE");
        int totalWith = withDedup.getRules().size();

        assertEquals(totalWithout - 1, totalWith);
        assertTrue(withDedup.getRules().stream()
                .noneMatch(r -> r.getCore().getId().equals(firstRuleId)));
    }

    // ---- Category 4: Expected Variable ----


    @Test
    void testExpectedVariable_nowStaticNotGenerated()
    {
        // Cat 4 expected variable rules are now handled by static rule CORE-000334
        IDataTable table = MockTable.of().name("VS").col("STUDYID", "S001").col("USUBJID", "SUBJ01")
                .build();

        RuleGenerator gen = new RuleGenerator(new ExtendedMockLibrary(), "sdtmct-2025-09-26");
        GeneratedRulePackage pkg = gen(gen, table, "VS", "FINDINGS");

        // The removed Cat 4 generator emitted expected-variable IDs of the form
        // GEN-EXP-VS-VSORRES. No such ID is produced any more (and the GEN-EXP- expansion scheme
        // itself is retired — SDTM `--` expansions now keep their bare base CORE id).
        List<Rule> expRules = pkg.getRules().stream()
                .filter(r -> r.getCore().getId().matches("GEN-EXP-VS-[A-Z]+$")).toList();
        assertTrue(expRules.isEmpty());
    }


    @Test
    void testExpectedVariable_presentExpected_noRule()
    {
        IDataTable table = MockTable.of().name("VS").col("STUDYID", "S001").col("USUBJID", "SUBJ01")
                .col("VSORRES", "120").build();

        RuleGenerator gen = new RuleGenerator(new ExtendedMockLibrary(), "sdtmct-2025-09-26");
        GeneratedRulePackage pkg = gen(gen, table, "VS", "FINDINGS");

        assertTrue(pkg.getRules().stream()
                .noneMatch(r -> r.getCore().getId().equals("GEN-EXP-VS-VSORRES")));
    }

    // ---- Category 5: Variable Order ----


    @Test
    void testVariableOrder_correctOrder()
    {
        // Library defines: STUDYID, USUBJID, VSTESTCD, VSORRES
        IDataTable table = MockTable.of().name("VS").col("STUDYID", "S001").col("USUBJID", "SUBJ01")
                .col("VSTESTCD", "SYSBP").col("VSORRES", "120").build();

        RuleGenerator gen = new RuleGenerator(new ExtendedMockLibrary(), "sdtmct-2025-09-26");
        GeneratedRulePackage pkg = gen(gen, table, "VS", "FINDINGS");

        assertTrue(pkg.getRules().stream().noneMatch(r -> r.getCore().getId().contains("ORD")));
    }


    @Test
    void testVariableOrder_wrongOrder()
    {
        // Variable order checking is now handled by static rule CORE-000852
        // (get_column_order_from_library + is_not_ordered_subset_of).
        // The hardcoded generateVariableOrderRule has been removed.
        // This test verifies that the generator no longer produces GEN-ORD rules.
        IDataTable table = MockTable.of().name("VS").col("STUDYID", "S001").col("VSTESTCD", "SYSBP")
                .col("USUBJID", "SUBJ01").col("VSORRES", "120").build();

        RuleGenerator gen = new RuleGenerator(new ExtendedMockLibrary(), "sdtmct-2025-09-26");
        GeneratedRulePackage pkg = gen(gen, table, "VS", "FINDINGS");

        assertFalse(pkg.getRules().stream().anyMatch(r -> r.getCore().getId().contains("GEN-ORD")));
    }

    // ---- Category 7: TESTCD/TEST Consistency — RETIRED (Fix #366) ----


    /**
     * The inversion of {@code testTestCdTestConsistency}. The exact input that used to produce
     * {@code GEN-TCTST} — a VS dataset carrying a {@code VSTESTCD}/{@code VSTEST} pair, with the
     * codelist-bearing library the built-in needed — now produces nothing: the check lived in the
     * engine's {@code rules-templates.json}, which belongs to no package and is deleted.
     */
    @Test
    void testCdTestBuiltInIsRetired()
    {
        IDataTable table = MockTable.of().name("VS").col("STUDYID", "S001").col("VSTESTCD", "SYSBP")
                .col("VSTEST", "Systolic Blood Pressure").build();

        ExtendedMockLibrary lib = new ExtendedMockLibrary();
        RuleGenerator gen = new RuleGenerator(lib, "sdtmct-2025-09-26");
        GeneratedRulePackage pkg = gen(gen, table, "VS", "FINDINGS");

        assertTrue(pkg.getRules().stream().noneMatch(r -> r.getCore().getId().contains("TCTST")));
    }

    // ---- Category 8: TSPARMCD/TSPARM Consistency — RETIRED (Fix #366) ----


    /** The inversion of {@code testTsParmConsistency}; see {@link #testCdTestBuiltInIsRetired}. */
    @Test
    void tsParmBuiltInIsRetired()
    {
        IDataTable table = MockTable.of().name("TS").col("STUDYID", "S001")
                .col("TSPARMCD", "SSTDTC").col("TSPARM", "Study Start Date").build();

        GeneratedRulePackage pkg = gen(table, "TS", "TRIAL DESIGN");

        assertTrue(pkg.getRules().stream().noneMatch(r -> r.getCore().getId().contains("TSPC")));
    }

    // ---- Category 9: Flag/Numeric Consistency — RETIRED (Fix #366) ----


    /**
     * The inversion of {@code testFlagNumericConsistency}; see {@link #testCdTestBuiltInIsRetired}.
     */
    @Test
    void flagNumericBuiltInIsRetired()
    {
        IDataTable table = MockTable.of().name("ADSL").col("STUDYID", "S001").col("SAFFL", "Y")
                .col("SAFFN", "1").col("TRT01P", "Drug A").build();

        GeneratedRulePackage pkg = gen(table, "ADSL", "SUBJECT LEVEL ANALYSIS DATASET");

        assertTrue(pkg.getRules().stream().noneMatch(r -> r.getCore().getId().contains("FLFN")));
    }


    @Test
    void testFlagNumericConsistency_noPair()
    {
        IDataTable table = MockTable.of().name("ADSL").col("STUDYID", "S001").col("SAFFL", "Y")
                .col("TRT01P", "Drug A").build();

        GeneratedRulePackage pkg = gen(table, "ADSL", "SUBJECT LEVEL ANALYSIS DATASET");

        // SAFFL exists but SAFFN doesn't → no FL/FN rule
        assertTrue(pkg.getRules().stream().noneMatch(r -> r.getCore().getId().contains("FLFN")));
    }

    // ---- Category 10: Pair One-to-One ----


    @Test
    void testPairOneToOne()
    {
        IDataTable table = MockTable.of().name("ADVS").col("STUDYID", "S001")
                .col("PARAM", "Systolic").col("PARAMN", "1").build();

        GeneratedRulePackage pkg = gen(table, "ADVS", "BASIC DATA STRUCTURE");

        assertTrue(pkg.getRules().stream().anyMatch(r -> r.getCore().getId().contains("121")
                && r.getDescription().contains("PARAM") && r.getDescription().contains("PARAMN")));
    }

    // ---- Category 11: Dataset Label ----


    @Test
    void testDatasetLabel_mismatch()
    {
        IDataTable table = MockTable.of().name("VS").col("STUDYID", "S001").label("Wrong Label")
                .build();

        ExtendedMockLibrary lib = new ExtendedMockLibrary();
        RuleGenerator gen = new RuleGenerator(lib, "sdtmct-2025-09-26");
        GeneratedRulePackage pkg = gen(gen, table, "VS", "FINDINGS");

        assertTrue(pkg.getRules().stream().anyMatch(r -> r.getCore().getId().contains("DSLBL")));
    }


    @Test
    void testDatasetLabel_matches()
    {
        IDataTable table = MockTable.of().name("VS").col("STUDYID", "S001").label("Vital Signs")
                .build();

        ExtendedMockLibrary lib = new ExtendedMockLibrary();
        RuleGenerator gen = new RuleGenerator(lib, "sdtmct-2025-09-26");
        GeneratedRulePackage pkg = gen(gen, table, "VS", "FINDINGS");

        assertTrue(pkg.getRules().stream().noneMatch(r -> r.getCore().getId().contains("DSLBL")));
    }

    // ---- Category 12: Disallowed Variable ----


    @Test
    void testDisallowedVariable()
    {
        IDataTable table = MockTable.of().name("DM").col("STUDYID", "S001").col("USUBJID", "SUBJ01")
                .col("SEX", "M").col("CUSTOM_VAR", "X").build();

        GeneratedRulePackage pkg = gen(table, "DM", "SPECIAL PURPOSE");

        assertTrue(pkg.getRules().stream().anyMatch(r -> r.getCore().getId().contains("DISALLOW")
                && r.getDescription().contains("CUSTOM_VAR")));
    }


    @Test
    void testDisallowedVariable_allKnown()
    {
        IDataTable table = MockTable.of().name("DM").col("STUDYID", "S001").col("USUBJID", "SUBJ01")
                .col("SEX", "M").build();

        GeneratedRulePackage pkg = gen(table, "DM", "SPECIAL PURPOSE");

        assertTrue(
                pkg.getRules().stream().noneMatch(r -> r.getCore().getId().contains("DISALLOW")));
    }

    // ---- Category 13: MedDRA Validation ----


    @Test
    void testMedDRA_noDictionary_skipped()
    {
        IDataTable table = MockTable.of().name("AE").col("STUDYID", "S001")
                .col("AEPTCD", "10019211").col("AEDECOD", "Headache").build();

        // No dictionary provider
        RuleGenerator gen = new RuleGenerator(library, "sdtmct-2025-09-26");
        GeneratedRulePackage pkg = gen(gen, table, "AE", "EVENTS");

        assertTrue(pkg.getReport().getSkippedRules().stream()
                .anyMatch(s -> s.category() == RuleCategory.MEDDRA_VALIDATION
                        && s.reason().contains("MedDRA dictionary not available")));
    }


    @Test
    void testMedDRA_withDictionary()
    {
        IDataTable table = MockTable.of().name("AE").col("STUDYID", "S001")
                .col("AEPTCD", "10019211").col("AEDECOD", "Headache").build();

        MockDictionaryProvider dictProvider = new MockDictionaryProvider();
        ExtendedMockLibrary lib = new ExtendedMockLibrary();
        RuleGenerator gen = new RuleGenerator(lib, dictProvider, "sdtmct-2025-09-26");
        GeneratedRulePackage pkg = gen(gen, table, "AE", "EVENTS");

        assertTrue(pkg.getRules().stream().anyMatch(
                r -> r.getCore().getId().contains("MED") && r.getDescription().contains("AEPTCD")));
    }


    @Test
    void testMedDRA_nonAeDomain_ignored()
    {
        IDataTable table = MockTable.of().name("DM").col("STUDYID", "S001").build();

        MockDictionaryProvider dictProvider = new MockDictionaryProvider();
        RuleGenerator gen = new RuleGenerator(library, dictProvider, "sdtmct-2025-09-26");
        GeneratedRulePackage pkg = gen(gen, table, "DM", "SPECIAL PURPOSE");

        assertTrue(pkg.getRules().stream().noneMatch(r -> r.getCore().getId().contains("MED")));
    }

    // ---- Category 14: WHO Drug Validation ----


    @Test
    void testWHODrug_noDictionary_skipped()
    {
        IDataTable table = MockTable.of().name("CM").col("STUDYID", "S001")
                .col("CMDECOD", "ASPIRIN").build();

        RuleGenerator gen = new RuleGenerator(library, "sdtmct-2025-09-26");
        GeneratedRulePackage pkg = gen(gen, table, "CM", "INTERVENTIONS");

        assertTrue(pkg.getReport().getSkippedRules().stream()
                .anyMatch(s -> s.category() == RuleCategory.WHODD_VALIDATION
                        && s.reason().contains("WHO Drug dictionary not available")));
    }


    @Test
    void testWHODrug_withDictionary()
    {
        IDataTable table = MockTable.of().name("CM").col("STUDYID", "S001")
                .col("CMDECOD", "ASPIRIN").col("CMCLASCD", "N02BA")
                .col("CMCLAS", "Salicylic acid derivatives").build();

        MockDictionaryProvider dictProvider = new MockDictionaryProvider();
        ExtendedMockLibrary lib = new ExtendedMockLibrary();
        RuleGenerator gen = new RuleGenerator(lib, dictProvider, "sdtmct-2025-09-26");
        GeneratedRulePackage pkg = gen(gen, table, "CM", "INTERVENTIONS");

        assertTrue(pkg.getRules().stream().anyMatch(r -> r.getCore().getId().contains("WHO")));
    }


    @Test
    void testWHODrug_nonCmDomain_ignored()
    {
        IDataTable table = MockTable.of().name("AE").col("STUDYID", "S001").build();

        MockDictionaryProvider dictProvider = new MockDictionaryProvider();
        RuleGenerator gen = new RuleGenerator(library, dictProvider, "sdtmct-2025-09-26");
        GeneratedRulePackage pkg = gen(gen, table, "AE", "EVENTS");

        assertTrue(pkg.getRules().stream().noneMatch(r -> r.getCore().getId().contains("WHO")));
    }

    // ---- Phase 4 (PLAN-extend-expression-engine): Requirements.Variables patterns, -- ----


    /** Builds a minimal static rule with a {@code Requirements.Variables.All} list. */
    private static Rule variableScopedRule(String coreId, String... includeEntries)
    {
        Rule rule = new Rule();
        rule.setId(coreId);
        net.cumba.cdisc.core.model.RuleCore core = new net.cumba.cdisc.core.model.RuleCore();
        core.setId(coreId);
        rule.setCore(core);
        net.cumba.cdisc.core.model.VariableRequirement vr = new net.cumba.cdisc.core.model.VariableRequirement();
        vr.setAll(java.util.List.of(includeEntries));
        net.cumba.cdisc.core.model.Requirements req = new net.cumba.cdisc.core.model.Requirements();
        req.setVariables(vr);
        rule.setRequirements(req);
        rule.setCheck(net.cumba.cdisc.core.model.CheckConditionLeaf.builder().name("STUDYID")
                .operator("non_empty").build());
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


    /**
     * The inversion of the two pins removed below. Before Fix #366 this same input produced
     * {@code GEN-SLBL-DTF-ASTDTF} and {@code GEN-STYP-DTF-ASTDTF} from the engine's built-in
     * {@code rules-templates.json} — two rules the caller never supplied and no package contains.
     * The generator now emits nothing the caller did not hand it plus, with categories enabled, its
     * own {@code GEN-*} generator output; what it may never do again is merge a file of its own.
     */
    @Test
    void noBuiltInRuleIsMergedIntoTheExecutedSet()
    {
        IDataTable table = MockTable.of().name("DM").col("STUDYID", "S001").col("ASTDTF", "D")
                .build();

        GeneratedRulePackage pkg = gen(table, "DM", "SPECIAL PURPOSE");

        java.util.List<String> ids = pkg.getRules().stream().map(r -> r.getCore().getId()).toList();
        for (String templateId : List.of("GEN-SLBL-DTF-ASTDTF", "GEN-STYP-DTF-ASTDTF",
                "GEN-VMCALM-LBL", "GEN-TCTST", "GEN-TSPC", "GEN-FLFN"))
        {
            assertFalse(ids.stream().anyMatch(id -> id.startsWith(templateId)),
                    () -> "built-in template rule " + templateId + " is back in the executed set: "
                            + ids);
        }
        // …and the resource itself is gone, so nothing can reload it.
        assertNull(RuleGenerator.class.getResourceAsStream("/rules-templates.json"));
    }

    // Two end-to-end pins lived here and were removed with their subject:
    // suffixLabelTemplateStillExpandsAfterTheTemplatesFileRename asserted that
    // rules-templates.json's renamed steering keys still bound and still produced
    // GEN-SLBL-DTF-ASTDTF / GEN-STYP-DTF-ASTDTF, and suffixLabelTemplateIsGatedByItsCategory
    // asserted the SUFFIX_LABEL_TYPE gate suppressed them. The templates file is deleted, the
    // three steering fields are gone from the Rule model, and applyTemplatePostFilters no longer
    // carries a family gate — there is nothing left for either pin to hold.
    // ⚑ noBuiltInRuleIsMergedIntoTheExecutedSet (above) is what replaces them: it asserts the
    // generator adds no id the caller did not hand it.

    // ---- Mock Library Provider ----

    private static class MockLibraryProvider implements net.cumba.cdisc.core.exec.MetadataProvider
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
        public boolean isCodelistExtensible(String cl)
        {
            return !"SEX".equals(cl); // SEX is non-extensible in mock
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
     * Extended mock that supports VS, AE, CM domains for testing additional categories.
     */
    private static class ExtendedMockLibrary extends MockLibraryProvider
    {

        @Override
        public List<Map<String, String>> getDomainVariables(String domain)
        {
            if ("DM".equals(domain))
            {
                return super.getDomainVariables(domain);
            }
            if ("VS".equals(domain))
            {
                return List.of(
                        Map.of("name", "STUDYID", "label", "Study Identifier", "simpleDatatype",
                                "Char", "core", "Req"),
                        Map.of("name", "USUBJID", "label", "Unique Subject Identifier",
                                "simpleDatatype", "Char", "core", "Req"),
                        Map.of("name", "VSTESTCD", "label", "Vital Signs Test Short Name",
                                "simpleDatatype", "Char", "core", "Req", "codelist", "VSTESTCD"),
                        Map.of("name", "VSORRES", "label", "Result or Finding in Original Units",
                                "simpleDatatype", "Char", "core", "Exp"));
            }
            if ("AE".equals(domain))
            {
                return List.of(
                        Map.of("name", "STUDYID", "label", "Study Identifier", "simpleDatatype",
                                "Char", "core", "Req"),
                        Map.of("name", "AEPTCD", "label", "Preferred Term Code", "simpleDatatype",
                                "Num", "core", "Exp"),
                        Map.of("name", "AEDECOD", "label", "Dictionary-Derived Term",
                                "simpleDatatype", "Char", "core", "Exp"));
            }
            if ("CM".equals(domain))
            {
                return List.of(
                        Map.of("name", "STUDYID", "label", "Study Identifier", "simpleDatatype",
                                "Char", "core", "Req"),
                        Map.of("name", "CMDECOD", "label", "Standardized Drug Name",
                                "simpleDatatype", "Char", "core", "Exp"),
                        Map.of("name", "CMCLASCD", "label", "Drug Class Code", "simpleDatatype",
                                "Char", "core", "Perm"),
                        Map.of("name", "CMCLAS", "label", "Drug Class", "simpleDatatype", "Char",
                                "core", "Perm"));
            }
            return List.of();
        }


        @Override
        public Map<String, String> getDatasetMetadata(String domain)
        {
            if ("VS".equals(domain))
            {
                return Map.of("label", "Vital Signs");
            }
            return Map.of();
        }


        @Override
        public Map<String, String> getCodelistTermMappings(String cl)
        {
            if ("VSTESTCD".equals(cl))
            {
                return Map.of("SYSBP", "Systolic Blood Pressure", "DIABP",
                        "Diastolic Blood Pressure", "HR", "Heart Rate");
            }
            return Map.of();
        }
    }


    /**
     * Mock dictionary provider for MedDRA and WHO Drug testing.
     */
    private static class MockDictionaryProvider implements DictionaryProvider
    {

        @Override
        public MedDRAProvider getMedDRA()
        {
            return new MedDRAProvider()
            {

                @Override
                public String getVersion()
                {
                    return "26.0";
                }


                @Override
                public boolean isValidPTCode(String code)
                {
                    return true;
                }


                @Override
                public String getPreferredTerm(String code)
                {
                    return "Headache";
                }


                @Override
                public String getPreferredTermCode(String ptName)
                {
                    return "10019211";
                }


                @Override
                public boolean isValidPreferredTerm(String ptName)
                {
                    return true;
                }


                @Override
                public String getLLTName(String code)
                {
                    return "Headache NOS";
                }


                @Override
                public boolean isLLTUnderPT(String lltCode, String ptCode)
                {
                    return true;
                }


                @Override
                public String getHLTName(String code)
                {
                    return "Headaches NEC";
                }


                @Override
                public boolean isPTUnderHLT(String ptCode, String hltCode)
                {
                    return true;
                }


                @Override
                public String getHLGTName(String code)
                {
                    return "Cranial nerve disorders NEC";
                }


                @Override
                public String getSOCName(String code)
                {
                    return "Nervous system disorders";
                }


                @Override
                public boolean isPTUnderSOC(String ptCode, String socCode)
                {
                    return true;
                }
            };
        }


        @Override
        public WHODrugProvider getWHODrug()
        {
            return new WHODrugProvider()
            {

                @Override
                public String getVersion()
                {
                    return "C3-2024-03";
                }


                @Override
                public boolean isValidDrugName(String name)
                {
                    return true;
                }


                @Override
                public List<String> getATCCodes(String drugName)
                {
                    return List.of("N02BA");
                }


                @Override
                public boolean isValidATCCode(String code)
                {
                    return true;
                }


                @Override
                public String getATCText(String code)
                {
                    return "Salicylic acid derivatives";
                }


                @Override
                public boolean isDrugUnderATC(String drugName, String atcCode)
                {
                    return true;
                }
            };
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
        net.cumba.cdisc.core.model.RuleCore core = new net.cumba.cdisc.core.model.RuleCore();
        core.setId(coreId);
        rule.setCore(core);
        net.cumba.cdisc.core.model.Scope scope = new net.cumba.cdisc.core.model.Scope();
        net.cumba.cdisc.core.model.DatasetScope ds = new net.cumba.cdisc.core.model.DatasetScope();
        ds.setInclude(include);
        ds.setExclude(exclude);
        scope.setDatasets(ds);
        rule.setScope(scope);
        rule.setCheck(net.cumba.cdisc.core.model.CheckConditionLeaf.builder().name("STUDYID")
                .operator("non_empty").build());
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
