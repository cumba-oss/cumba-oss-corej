package net.cumba.corej.core.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.cumba.corej.core.model.CheckConditionLeaf;
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
        net.cumba.corej.core.model.Rule staticRule = new net.cumba.corej.core.model.Rule();
        net.cumba.corej.core.model.RuleCore core = new net.cumba.corej.core.model.RuleCore();
        core.setId("CORE-000001");
        staticRule.setCore(core);
        staticRule.setDescription("--DTC must not be empty");
        staticRule.setSensitivity(net.cumba.corej.core.model.Sensitivity.RECORD);
        staticRule.setExecutability(net.cumba.corej.core.model.Executability.FULLY_EXECUTABLE);
        staticRule.setCheck(net.cumba.corej.core.model.CheckConditionLeaf.builder().name("--DTC")
                .operator("empty").build());
        net.cumba.corej.core.model.Outcome outcome = new net.cumba.corej.core.model.Outcome();
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
        net.cumba.corej.core.model.Rule staticRule = new net.cumba.corej.core.model.Rule();
        net.cumba.corej.core.model.RuleCore core = new net.cumba.corej.core.model.RuleCore();
        core.setId("CORE-000002");
        staticRule.setCore(core);
        staticRule.setDescription("STUDYID must exist");
        staticRule.setCheck(net.cumba.corej.core.model.CheckConditionLeaf.builder().name("STUDYID")
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
        rule.setCheck(net.cumba.corej.core.model.CheckConditionLeaf.builder().name("STUDYID")
                .operator("empty").build());
        net.cumba.corej.core.model.Outcome outcome = new net.cumba.corej.core.model.Outcome();
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

        template.setCheck(new net.cumba.corej.core.model.CheckConditionAll(java.util.List.of(
                net.cumba.corej.core.model.CheckConditionLeaf.builder().name("TRTPGy")
                        .operator("non_empty").build(),
                net.cumba.corej.core.model.CheckConditionLeaf.builder().name("TRTPGyN")
                        .operator("empty").build())));

        net.cumba.corej.core.model.Outcome outcome = new net.cumba.corej.core.model.Outcome();
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
        net.cumba.corej.core.model.VariableRequirement expandedVars = expanded.getFirst()
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
        net.cumba.corej.core.model.RuleCore core = new net.cumba.corej.core.model.RuleCore();
        core.setId(coreId);
        rule.setCore(core);
        net.cumba.corej.core.model.VariableRequirement vr = new net.cumba.corej.core.model.VariableRequirement();
        vr.setAll(java.util.List.of(includeEntries));
        net.cumba.corej.core.model.Requirements req = new net.cumba.corej.core.model.Requirements();
        req.setVariables(vr);
        rule.setRequirements(req);
        rule.setCheck(net.cumba.corej.core.model.CheckConditionLeaf.builder().name("STUDYID")
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

    // ---- SMQzz indexed-variable family ----
    //
    // This family had no coverage at all: the word "SMQ" did not appear in this file, and
    // mutation testing reported all 8 mutants of generateSmqZzRules surviving. Every branch
    // below could have been deleted, or its guard inverted, without a red test. The negative
    // cases carry as much weight as the positive ones — the NAM branch is conditional on the
    // sibling CD column existing, and a lone CD column must generate nothing at all.


    private List<Rule> smqRules(IDataTable table)
    {
        return gen(table, "AE", "EVENTS").getRules().stream()
                .filter(r -> r.getCore().getId().contains("SMQ")).toList();
    }


    private static IDataTable aeWith(String... cols)
    {
        MockTable t = MockTable.of().name("AE").col("STUDYID", "S001");
        for (String c : cols)
        {
            t = t.col(c, "X");
        }
        return t.build();
    }


    private static List<String> ruleIds(List<Rule> rules)
    {
        return rules.stream().map(r -> r.getCore().getId()).toList();
    }


    private static boolean anySmqId(List<Rule> rules, String fragment)
    {
        return rules.stream().anyMatch(r -> r.getCore().getId().contains(fragment));
    }


    @Test
    void testSmqZz_namWithSiblingCdGeneratesThePopulationPairingRule()
    {
        List<Rule> rules = smqRules(aeWith("SMQ01NAM", "SMQ01CD"));

        assertTrue(anySmqId(rules, "SMQPAIR"), "expected the NAM->CD pairing rule");
        assertTrue(
                rules.stream()
                        .anyMatch(r -> "When SMQ01NAM is populated, SMQ01CD must be populated."
                                .equals(r.getDescription())),
                "pairing rule must name both variables");
    }


    @Test
    void testSmqZz_namWithoutASiblingCdGeneratesNoPairingRule()
    {
        // Guards `allCols.contains(cdVar)`: without it a lone NAM emits a rule referencing a
        // column the dataset does not have.
        assertFalse(anySmqId(smqRules(aeWith("SMQ01NAM")), "SMQPAIR"));
    }


    @Test
    void testSmqZz_scGeneratesTheBroadOrNarrowValueRule()
    {
        List<Rule> rules = smqRules(aeWith("SMQ01SC"));

        assertTrue(anySmqId(rules, "SMQSCVAL"));
        assertTrue(rules.stream()
                .anyMatch(r -> "SMQ01SC must be BROAD or NARROW.".equals(r.getDescription())));
    }


    @Test
    void testSmqZz_scnGeneratesTheOneOrTwoValueRule()
    {
        List<Rule> rules = smqRules(aeWith("SMQ01SCN"));

        assertTrue(anySmqId(rules, "SMQSCNVAL"));
        assertTrue(rules.stream()
                .anyMatch(r -> "SMQ01SCN must be 1 or 2.".equals(r.getDescription())));
    }


    @Test
    void testSmqZz_scAlongsideScnAlsoGeneratesTheOneToOnePairing()
    {
        List<Rule> rules = smqRules(aeWith("SMQ01SC", "SMQ01SCN"));

        assertTrue(anySmqId(rules, "SMQSCVAL"));
        assertTrue(anySmqId(rules, "SMQSCNVAL"));
        assertTrue(anySmqId(rules, "SMQzz121"), "expected the SC<->SCN 1:1 rule");
    }


    @Test
    void testSmqZz_aCdColumnOnItsOwnGeneratesNothing()
    {
        // CD matches the SMQzz pattern but drives none of the three branches.
        assertEquals(List.of(), ruleIds(smqRules(aeWith("SMQ01CD"))));
    }


    @Test
    void testSmqZz_aMalformedIndexIsNotAnSmqVariable()
    {
        // The index is exactly two digits — SMQ1SC and SMQ001SC are not SMQzz variables.
        assertEquals(List.of(), ruleIds(smqRules(aeWith("SMQ1SC", "SMQ001SC"))));
    }

    // ---- CRITy indexed-variable family ----
    //
    // Only the "CRIT1FL present, CRIT1 missing" arm was pinned (testIndexedVar_critPairing);
    // the CRITy->FL arm, the FN->FL arm and the FL<->FN 1:1 pairing were not, and each of the
    // three `allCols.contains(...)` guards could be inverted without a red test.


    private List<Rule> critRules(IDataTable table)
    {
        return gen(table, "ADVS", "BASIC DATA STRUCTURE").getRules().stream()
                .filter(r -> r.getCore().getId().contains("CRIT")).toList();
    }


    private static IDataTable advsWith(String... cols)
    {
        MockTable t = MockTable.of().name("ADVS").col("STUDYID", "S001");
        for (String c : cols)
        {
            t = t.col(c, "Y");
        }
        return t.build();
    }


    @Test
    void testCritY_critWithoutItsFlagDemandsTheFlag()
    {
        List<Rule> rules = critRules(advsWith("CRIT1"));

        assertTrue(rules.stream().anyMatch(r -> r.getCore().getId().contains("CRITPAIR")));
        assertTrue(rules.stream().anyMatch(
                r -> "CRIT1 is present but CRIT1FL is missing.".equals(r.getDescription())));
    }


    @Test
    void testCritY_critWithItsFlagPresentDemandsNothing()
    {
        // Guards `!allCols.contains(flVar)` on the suffix==null arm.
        assertFalse(critRules(advsWith("CRIT1", "CRIT1FL")).stream()
                .anyMatch(r -> r.getCore().getId().contains("CRITPAIR")));
    }


    @Test
    void testCritY_flagWithoutItsCriterionDemandsTheCriterion()
    {
        assertTrue(critRules(advsWith("CRIT1FL")).stream().anyMatch(
                r -> "CRIT1FL is present but CRIT1 is missing.".equals(r.getDescription())));
    }


    @Test
    void testCritY_flagAndNumericFlagFormAOneToOnePair()
    {
        List<Rule> rules = critRules(advsWith("CRIT1", "CRIT1FL", "CRIT1FN"));

        assertTrue(rules.stream().anyMatch(r -> r.getCore().getId().contains("CRITy121")),
                "expected the CRIT1FL <-> CRIT1FN 1:1 rule");
    }


    @Test
    void testCritY_numericFlagWithoutTheCharacterFlagDemandsIt()
    {
        // The FN arm fires only when CRITyFL is absent.
        List<Rule> rules = critRules(advsWith("CRIT1", "CRIT1FN"));

        assertTrue(rules.stream().anyMatch(r -> r.getCore().getId().contains("CRITFNPAIR")));
        assertTrue(rules.stream().anyMatch(
                r -> "CRIT1FN is present but CRIT1FL is missing.".equals(r.getDescription())));
    }

    // ---- TRxx treatment-period date family ----
    //
    // generateTrXxDateRules had 3 mutants, all surviving: the start/end suffix mapping and the
    // sibling-column guard were entirely unpinned.


    private List<Rule> trDateRules(IDataTable table)
    {
        return gen(table, "ADSL", "SUBJECT LEVEL ANALYSIS DATASET").getRules().stream()
                .filter(r -> r.getCore().getId().contains("TRDT")).toList();
    }


    private static IDataTable adslWith(String... cols)
    {
        MockTable t = MockTable.of().name("ADSL").col("STUDYID", "S001");
        for (String c : cols)
        {
            t = t.col(c, "2024-01-01");
        }
        return t.build();
    }


    @Test
    void testTrXxDate_startDateIsOrderedAgainstItsEndDate()
    {
        List<Rule> rules = trDateRules(adslWith("TR01SDT", "TR01EDT"));

        assertEquals(1, rules.size());
        assertEquals("TR01SDT must not be greater than TR01EDT.",
                rules.getFirst().getDescription());
    }


    @Test
    void testTrXxDate_theDatetimeAndTimeVariantsMapToTheirOwnEndVariable()
    {
        assertEquals("TR01SDTM must not be greater than TR01EDTM.",
                trDateRules(adslWith("TR01SDTM", "TR01EDTM")).getFirst().getDescription());
        assertEquals("TR01STM must not be greater than TR01ETM.",
                trDateRules(adslWith("TR01STM", "TR01ETM")).getFirst().getDescription());
    }


    @Test
    void testTrXxDate_aStartDateWithoutItsEndDateIsNotOrdered()
    {
        // Guards `allCols.contains(endVar)`.
        assertEquals(List.of(), trDateRules(adslWith("TR01SDT")));
    }


    @Test
    void testTrXxDate_anEndDateAloneGeneratesNothing()
    {
        // EDT/EDTM/ETM match the pattern but map to a null end suffix.
        assertEquals(List.of(), trDateRules(adslWith("TR01EDT", "TR01ETM")));
    }

    // ---- MedDRA / WHO Drug: which PAIR, not merely "a rule was made" ----
    //
    // testMedDRA_withDictionary and testWHODrug_withDictionary assert only that *some* rule
    // carries "MED"/"WHO" in its id. That holds however many pairs fire and whichever columns
    // they name, which is why 13/19 (MedDRA) and 12/17 (WHO Drug) mutants survived. These pin
    // the per-pair guards: a pair fires only when BOTH its code and term columns exist.


    private List<String> dictDescriptions(IDataTable table, String domain, String className,
            String idFragment)
    {
        RuleGenerator gen = new RuleGenerator(new ExtendedMockLibrary(),
                new MockDictionaryProvider(), "sdtmct-2025-09-26");
        return gen(gen, table, domain, className).getRules().stream()
                .filter(r -> r.getCore().getId().contains(idFragment)).map(Rule::getDescription)
                .toList();
    }


    private static IDataTable domainTable(String name, String... cols)
    {
        MockTable t = MockTable.of().name(name).col("STUDYID", "S001");
        for (String c : cols)
        {
            t = t.col(c, "V");
        }
        return t.build();
    }


    @Test
    void testMedDRA_eachCodeTermPairYieldsItsOwnLevelledRule()
    {
        List<String> descs = dictDescriptions(
                domainTable("AE", "AEPTCD", "AEDECOD", "AELLTCD", "AELLT"), "AE", "EVENTS", "MED");

        assertEquals(2, descs.size(), descs.toString());
        assertTrue(
                descs.contains(
                        "AEPTCD and AEDECOD must be consistent per MedDRA Preferred " + "Term."),
                descs.toString());
        assertTrue(
                descs.contains(
                        "AELLTCD and AELLT must be consistent per MedDRA Lowest Level " + "Term."),
                descs.toString());
    }


    @Test
    void testMedDRA_aTermWithoutItsCodeYieldsNoRule()
    {
        // Guards `codeVar >= 0 && termVar >= 0` — a lone term must not produce a pairing rule.
        assertEquals(List.of(),
                dictDescriptions(domainTable("AE", "AEDECOD"), "AE", "EVENTS", "MED"));
        assertEquals(List.of(),
                dictDescriptions(domainTable("AE", "AEPTCD"), "AE", "EVENTS", "MED"));
    }


    @Test
    void testMedDRA_appliesToMhAndCeNotOnlyAe()
    {
        assertEquals(List.of("MHPTCD and MHDECOD must be consistent per MedDRA Preferred Term."),
                dictDescriptions(domainTable("MH", "MHPTCD", "MHDECOD"), "MH", "EVENTS", "MED"));
        assertEquals(List.of("CEPTCD and CEDECOD must be consistent per MedDRA Preferred Term."),
                dictDescriptions(domainTable("CE", "CEPTCD", "CEDECOD"), "CE", "EVENTS", "MED"));
    }


    @Test
    void testWHODrug_theTwoConsistencyRulesAreIndependentlyGuarded()
    {
        List<String> both = dictDescriptions(domainTable("CM", "CMDECOD", "CMCLASCD", "CMCLAS"),
                "CM", "INTERVENTIONS", "WHO");
        assertEquals(2, both.size(), both.toString());
        assertTrue(
                both.contains("CMDECOD and CMCLASCD must be consistent per WHO Drug dictionary."),
                both.toString());
        assertTrue(both.contains("CMCLASCD and CMCLAS must be consistent per WHO Drug dictionary."),
                both.toString());
    }


    @Test
    void testWHODrug_eachRuleNeedsItsOwnColumnPair()
    {
        assertEquals(List.of("CMDECOD and CMCLASCD must be consistent per WHO Drug dictionary."),
                dictDescriptions(domainTable("CM", "CMDECOD", "CMCLASCD"), "CM", "INTERVENTIONS",
                        "WHO"));
        assertEquals(List.of("CMCLASCD and CMCLAS must be consistent per WHO Drug dictionary."),
                dictDescriptions(domainTable("CM", "CMCLASCD", "CMCLAS"), "CM", "INTERVENTIONS",
                        "WHO"));
        assertEquals(List.of(),
                dictDescriptions(domainTable("CM", "CMDECOD"), "CM", "INTERVENTIONS", "WHO"));
    }

    // ---- the generation REPORT, not only the rules ----
    //
    // Every generator pairs `rules.add(rule)` with `report.addGenerated(...)`, and the report is a
    // real output (it drives the markdown summary), not bookkeeping. Because the tests above assert
    // only `pkg.getRules()`, deleting the paired `report.addGenerated` call survived in ~20
    // generators. These pin the pairing for the families this file covers: a rule that is
    // generated must also be reported, with its category and its variable.


    private List<GeneratedRuleInfo> generatedFor(IDataTable table, String domain, String className,
            String idFragment)
    {
        RuleGenerator gen = new RuleGenerator(new ExtendedMockLibrary(),
                new MockDictionaryProvider(), "sdtmct-2025-09-26");
        return gen(gen, table, domain, className).getReport().getGeneratedRules().stream()
                .filter(g -> g.ruleId() != null && g.ruleId().contains(idFragment)).toList();
    }


    /** Same, but through the plain generator the non-dictionary family tests above use. */
    private List<GeneratedRuleInfo> generatedForPlain(IDataTable table, String domain,
            String className, String idFragment)
    {
        return gen(table, domain, className).getReport().getGeneratedRules().stream()
                .filter(g -> g.ruleId() != null && g.ruleId().contains(idFragment)).toList();
    }


    @Test
    void testReport_smqRulesAreRecordedAsIndexedVariableRules()
    {
        List<GeneratedRuleInfo> gens = generatedForPlain(aeWith("SMQ01SC", "SMQ01SCN"), "AE",
                "EVENTS", "IDX-SMQ");

        assertFalse(gens.isEmpty(), "the SMQ rules must appear in the report");
        assertTrue(
                gens.stream().allMatch(g -> g.category() == RuleCategory.INDEXED_VARIABLE_RULES));
        assertTrue(gens.stream().anyMatch(g -> "SMQ01SC".equals(g.variable())));
        assertTrue(gens.stream().anyMatch(g -> "SMQ01SCN".equals(g.variable())));
    }


    @Test
    void testReport_critRulesAreRecordedAsIndexedVariableRules()
    {
        List<GeneratedRuleInfo> gens = generatedForPlain(advsWith("CRIT1"), "ADVS",
                "BASIC DATA STRUCTURE", "IDX-CRIT");

        assertFalse(gens.isEmpty());
        assertTrue(
                gens.stream().allMatch(g -> g.category() == RuleCategory.INDEXED_VARIABLE_RULES));
        assertTrue(gens.stream().anyMatch(g -> "CRIT1".equals(g.variable())));
    }


    @Test
    void testReport_treatmentDateRuleIsRecorded()
    {
        List<GeneratedRuleInfo> gens = generatedForPlain(adslWith("TR01SDT", "TR01EDT"), "ADSL",
                "SUBJECT LEVEL ANALYSIS DATASET", "TRDT");

        assertEquals(1, gens.size());
        assertEquals(RuleCategory.INDEXED_VARIABLE_RULES, gens.getFirst().category());
        assertEquals("TR01SDT", gens.getFirst().variable());
    }


    @Test
    void testReport_whoDrugRulesAreRecordedWithTheirDictionarySource()
    {
        List<GeneratedRuleInfo> gens = generatedFor(
                domainTable("CM", "CMDECOD", "CMCLASCD", "CMCLAS"), "CM", "INTERVENTIONS", "WHO");

        assertEquals(2, gens.size());
        assertTrue(gens.stream().allMatch(g -> g.category() == RuleCategory.WHODD_VALIDATION));
        // The librarySource names the dictionary the rule was derived from.
        assertTrue(gens.stream().allMatch(
                g -> g.librarySource() != null && g.librarySource().startsWith("WHO Drug ")),
                gens.toString());
    }


    @Test
    void testReport_medDraRulesAreRecordedWithTheirDictionarySource()
    {
        List<GeneratedRuleInfo> gens = generatedFor(domainTable("AE", "AEPTCD", "AEDECOD"), "AE",
                "EVENTS", "MED");

        assertEquals(1, gens.size());
        assertEquals(RuleCategory.MEDDRA_VALIDATION, gens.getFirst().category());
        assertEquals("AEPTCD", gens.getFirst().variable());
        assertTrue(Objects.requireNonNull(gens.getFirst().librarySource()).startsWith("MedDRA "),
                gens.toString());
    }


    @Test
    void testReport_theOneToOnePairingRuleIsRecorded()
    {
        // generatePairingRule is shared by the SMQ and CRIT families and reports separately.
        List<GeneratedRuleInfo> gens = generatedForPlain(advsWith("CRIT1", "CRIT1FL", "CRIT1FN"),
                "ADVS", "BASIC DATA STRUCTURE", "CRITy121");

        assertEquals(1, gens.size());
        assertEquals("CRIT1FL", gens.getFirst().variable());
    }

    // ---- dictionary families: the column-index boundary ----
    //
    // Both generators probe columns with `meta.getColumnIndex(x) >= 0` (and one `< 0`). Every
    // fixture above puts STUDYID first, so the probed column is always at index >= 1 and the
    // `>= 0` / `> 0` boundary is unobservable — which is why six ConditionalsBoundary mutants
    // survived across the two methods. These put the probed column AT INDEX 0, where the
    // boundary decides the answer.


    /** Like domainTable but with no leading STUDYID, so the first named column is index 0. */
    private static IDataTable tableHeadedBy(String name, String... cols)
    {
        MockTable t = MockTable.of().name(name);
        for (String c : cols)
        {
            t = t.col(c, "V");
        }
        return t.build();
    }


    private RuleGenerationReport reportWithoutDictionary(IDataTable table, String domain,
            String className)
    {
        return gen(new RuleGenerator(new ExtendedMockLibrary(), "sdtmct-2025-09-26"), table, domain,
                className).getReport();
    }


    @Test
    void testMedDRA_noDictionary_skipIsRecordedForAVariableAtColumnZero()
    {
        RuleGenerationReport report = reportWithoutDictionary(tableHeadedBy("AE", "AEDECOD"), "AE",
                "EVENTS");

        assertTrue(
                report.getSkippedRules().stream()
                        .anyMatch(sk -> sk.category() == RuleCategory.MEDDRA_VALIDATION
                                && "AEDECOD".equals(sk.variable())),
                report.getSkippedRules().toString());
    }


    @Test
    void testMedDRA_noDictionary_noSkipWhenTheDomainCarriesNoMedDraVariable()
    {
        assertTrue(reportWithoutDictionary(tableHeadedBy("AE", "AESEQ"), "AE", "EVENTS")
                .getSkippedRules().stream()
                .noneMatch(sk -> sk.category() == RuleCategory.MEDDRA_VALIDATION));
    }


    @Test
    void testMedDRA_aPairHeadingTheDatasetStillGeneratesItsRule()
    {
        // AEPTCD is column 0 here; `getColumnIndex(codeVar) >= 0` must still admit it.
        assertEquals(List.of("AEPTCD and AEDECOD must be consistent per MedDRA Preferred Term."),
                dictDescriptions(tableHeadedBy("AE", "AEPTCD", "AEDECOD"), "AE", "EVENTS", "MED"));
    }


    @Test
    void testWHODrug_noDictionary_skipIsRecordedForAVariableAtColumnZero()
    {
        RuleGenerationReport report = reportWithoutDictionary(tableHeadedBy("CM", "CMDECOD"), "CM",
                "INTERVENTIONS");

        assertTrue(
                report.getSkippedRules().stream()
                        .anyMatch(sk -> sk.category() == RuleCategory.WHODD_VALIDATION
                                && "CMDECOD".equals(sk.variable())),
                report.getSkippedRules().toString());
    }


    @Test
    void testWHODrug_noDictionary_aNonCmDomainRecordsNoSkipEvenCarryingCmColumns()
    {
        // The skip loop is gated on the CM domain, not on the columns.
        assertTrue(reportWithoutDictionary(tableHeadedBy("AE", "CMDECOD"), "AE", "EVENTS")
                .getSkippedRules().stream()
                .noneMatch(sk -> sk.category() == RuleCategory.WHODD_VALIDATION));
    }


    @Test
    void testWHODrug_aPairHeadingTheDatasetStillGeneratesItsRule()
    {
        assertEquals(List.of("CMDECOD and CMCLASCD must be consistent per WHO Drug dictionary."),
                dictDescriptions(tableHeadedBy("CM", "CMDECOD", "CMCLASCD"), "CM", "INTERVENTIONS",
                        "WHO"));
        assertEquals(List.of("CMCLASCD and CMCLAS must be consistent per WHO Drug dictionary."),
                dictDescriptions(tableHeadedBy("CM", "CMCLASCD", "CMCLAS"), "CM", "INTERVENTIONS",
                        "WHO"));
    }

    // ---- applyTemplatePostFilters: which expansions survive ----
    //
    // 19 mutants survived in applyTemplatePostFilters. It is the filter that decides which
    // wildcard expansions are KEPT, and its central rule is subtle: on a two-character SDTM
    // domain, the expansion whose column is exactly `<domain><wildcard suffix>` is dropped,
    // because the `--` prefix expansion already produces that rule. Nothing pinned it, so the
    // filter could have dropped everything, or nothing, unnoticed.


    private static Rule wildcardTemplate(String coreId, String wildcardName)
    {
        Rule tpl = new Rule();
        RuleCore core = new RuleCore();
        core.setId(coreId);
        tpl.setCore(core);
        tpl.setDescription(wildcardName + " must not be empty");
        tpl.setSensitivity(Sensitivity.RECORD);
        tpl.setCheck(CheckConditionLeaf.builder().name(wildcardName).operator("empty").build());
        Outcome outcome = new Outcome();
        outcome.setMessage("m");
        outcome.setOutputVariables(List.of("USUBJID"));
        tpl.setOutcome(outcome);
        return tpl;
    }


    private List<String> expansionIdsFor(String wildcardName, String domain, IDataTable table)
    {
        generator.setStaticRules(List.of(wildcardTemplate("CORE-900001", wildcardName)));
        return gen(table, domain, "EVENTS").getRules().stream().map(r -> r.getCore().getId())
                .filter(id -> id != null && id.startsWith("CORE-900001")).sorted().toList();
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

        assertFalse(ids.contains("CORE-900001-AEDTC"),
                "the domain's own suffix column must be dropped: " + ids);
        assertTrue(ids.contains("CORE-900001-AESTDTC"), ids.toString());
        assertTrue(ids.contains("CORE-900001-AEENDTC"), ids.toString());
    }


    @Test
    void testTemplatePostFilters_aNonSdtmDomainKeepsEvenItsOwnSuffixColumn()
    {
        // The drop is gated on a two-character upper-case SDTM domain. ADSL is four characters,
        // so nothing is filtered.
        IDataTable adsl = MockTable.of().name("ADSL").col("USUBJID", "S1").col("ADSLDTC", "")
                .col("TRTSDTC", "").build();

        List<String> ids = expansionIdsFor("*DTC", "ADSL", adsl);

        assertTrue(ids.contains("CORE-900001-ADSLDTC"),
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
        net.cumba.corej.core.model.RuleCore core = new net.cumba.corej.core.model.RuleCore();
        core.setId(coreId);
        rule.setCore(core);
        net.cumba.corej.core.model.Scope scope = new net.cumba.corej.core.model.Scope();
        net.cumba.corej.core.model.DatasetScope ds = new net.cumba.corej.core.model.DatasetScope();
        ds.setInclude(include);
        ds.setExclude(exclude);
        scope.setDatasets(ds);
        rule.setScope(scope);
        rule.setCheck(net.cumba.corej.core.model.CheckConditionLeaf.builder().name("STUDYID")
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
