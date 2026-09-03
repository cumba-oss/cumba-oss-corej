package net.cumba.corej.core.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefineXMLRuleGeneratorTest
{

    private RuleGenerator generator;

    private MockDefineXML defineXML;

    @BeforeEach
    void setUp()
    {
        defineXML = new MockDefineXML();
        generator = new RuleGenerator(new MinimalLibrary(), null, defineXML, "sdtmct-2025-09-26");
    }


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

    // ---- Category 15: Variable Presence ----


    @Test
    void testDefineVariablePresence_missingVariable()
    {
        IDataTable table = MockTable.of().name("DM").col("STUDYID", "S001").build();

        GeneratedRulePackage pkg = gen(table, "DM", "SPECIAL PURPOSE");

        List<Rule> presRules = pkg.getRules().stream()
                .filter(r -> r.getCore().getId().startsWith("GEN-DXPRES-")).toList();
        // USUBJID is in Define-XML but missing from table
        assertTrue(presRules.stream()
                .anyMatch(r -> r.getCore().getId().equals("GEN-DXPRES-DM-USUBJID")));
    }

    // ---- Category 16: No Extra Variables ----


    @Test
    void testDefineNoExtraVariables()
    {
        IDataTable table = MockTable.of().name("DM").col("STUDYID", "S001").col("USUBJID", "SUBJ01")
                .col("CUSTOM1", "X").build();

        GeneratedRulePackage pkg = gen(table, "DM", "SPECIAL PURPOSE");

        List<Rule> extraRules = pkg.getRules().stream()
                .filter(r -> r.getCore().getId().startsWith("GEN-DXEXTRA-")).toList();
        assertEquals(1, extraRules.size());
        assertTrue(extraRules.getFirst().getDescription().contains("CUSTOM1"));
    }

    // ---- Category 17: Variable Label ----


    @Test
    void testDefineVariableLabel()
    {
        IDataTable table = MockTable.of().name("DM").col("STUDYID", "S001").col("USUBJID", "SUBJ01")
                .build();

        GeneratedRulePackage pkg = gen(table, "DM", "SPECIAL PURPOSE");

        List<Rule> labelRules = pkg.getRules().stream()
                .filter(r -> r.getCore().getId().startsWith("GEN-DXLBL-")).toList();
        assertEquals(2, labelRules.size());
    }

    // ---- Category 18: Variable Type ----


    @Test
    void testDefineVariableType()
    {
        IDataTable table = MockTable.of().name("DM").col("STUDYID", "S001").build();

        GeneratedRulePackage pkg = gen(table, "DM", "SPECIAL PURPOSE");

        List<Rule> typeRules = pkg.getRules().stream()
                .filter(r -> r.getCore().getId().startsWith("GEN-DXTYP-")).toList();
        assertFalse(typeRules.isEmpty());
    }

    // ---- Category 19: Variable Length ----


    @Test
    void testDefineVariableLength()
    {
        IDataTable table = MockTable.of().name("DM").col("STUDYID", "S001").col("USUBJID", "SUBJ01")
                .build();

        GeneratedRulePackage pkg = gen(table, "DM", "SPECIAL PURPOSE");

        List<Rule> lenRules = pkg.getRules().stream()
                .filter(r -> r.getCore().getId().startsWith("GEN-DXLEN-")).toList();
        // STUDYID has length 12, USUBJID has length 20
        assertEquals(2, lenRules.size());
    }

    // ---- Category 20: Codelist Values ----


    @Test
    void testDefineCodelistValues()
    {
        IDataTable table = MockTable.of().name("DM").col("STUDYID", "S001").col("USUBJID", "SUBJ01")
                .col("SEX", "M").build();

        // Add SEX to Define-XML with codelist
        defineXML.addVariable("DM", Map.of("name", "SEX", "label", "Sex", "dataType", "Char",
                "length", "2", "codelist", "CL.SEX", "codelistName", "SEX"));

        GeneratedRulePackage pkg = gen(table, "DM", "SPECIAL PURPOSE");

        List<Rule> clRules = pkg.getRules().stream()
                .filter(r -> r.getCore().getId().startsWith("GEN-DXCL-")).toList();
        assertEquals(1, clRules.size());
        assertEquals("GEN-DXCL-DM-SEX", clRules.getFirst().getCore().getId());
    }

    // ---- Category 21: Key Uniqueness ----


    @Test
    void testDefineKeyUniqueness()
    {
        IDataTable table = MockTable.of().name("DM").col("STUDYID", "S001", "S001")
                .col("USUBJID", "SUBJ01", "SUBJ02").build();

        GeneratedRulePackage pkg = gen(table, "DM", "SPECIAL PURPOSE");

        List<Rule> keyRules = pkg.getRules().stream()
                .filter(r -> r.getCore().getId().startsWith("GEN-DXKEY-")).toList();
        assertEquals(1, keyRules.size());
    }

    // ---- Category 23: Value-Level Metadata ----


    @Test
    void testDefineValueLevelMetadata()
    {
        IDataTable table = MockTable.of().name("ADVS").col("PARAMCD", "SYSBP", "DIABP")
                .col("AVAL", "120", "80").col("AVALU", "mmHg", "mmHg").build();

        // Set up a Define-XML with VLM for AVALU
        MockDefineXML vlmDefine = new MockDefineXML()
        {

            @Override
            public List<Map<String, String>> getVariables(String ds)
            {
                if ("ADVS".equals(ds))
                {
                    return List.of(
                            Map.of("name", "PARAMCD", "label", "Parameter Code", "dataType", "Char",
                                    "length", "8"),
                            Map.of("name", "AVAL", "label", "Analysis Value", "dataType", "Num",
                                    "length", "8"),
                            Map.of("name", "AVALU", "label", "Analysis Value Unit", "dataType",
                                    "Char", "length", "20"));
                }
                return List.of();
            }


            @Override
            public List<String> getKeyVariables(String ds)
            {
                return "ADVS".equals(ds) ? List.of("PARAMCD") : List.of();
            }


            @Override
            public List<Map<String, String>> getValueLevelMetadata(String ds, String variableName)
            {
                if ("ADVS".equals(ds) && "AVALU".equals(variableName))
                {
                    return List.of(Map.of("whereClauseOID", "WC.SYSBP", "variable", "AVALU",
                            "codelist", "CL.UNIT.SYSBP"));
                }
                return List.of();
            }


            @Override
            public List<Map<String, String>> getWhereClauseConditions(String wcOID)
            {
                if ("WC.SYSBP".equals(wcOID))
                {
                    return List.of(
                            Map.of("variable", "PARAMCD", "comparator", "EQ", "values", "SYSBP"));
                }
                return List.of();
            }


            @Override
            public List<Map<String, String>> getCodelistTerms(String clOID)
            {
                if ("CL.UNIT.SYSBP".equals(clOID))
                {
                    return List.of(Map.of("codedValue", "mmHg"), Map.of("codedValue", "kPa"));
                }
                return List.of();
            }
        };

        RuleGenerator vlmGen = new RuleGenerator(new MinimalLibrary(), null, vlmDefine,
                "sdtmct-2025-09-26");

        GeneratedRulePackage pkg = gen(vlmGen, table, "ADVS", "BASIC DATA STRUCTURE");

        List<Rule> vlmRules = pkg.getRules().stream()
                .filter(r -> r.getCore().getId().startsWith("GEN-DXVLM-")).toList();
        assertEquals(1, vlmRules.size());
        assertTrue(vlmRules.getFirst().getDescription().contains("AVALU"));
    }

    // ---- Silent skip when no Define-XML ----


    @Test
    void testNoDefineXML_silentlySkipped()
    {
        RuleGenerator noDefine = new RuleGenerator(new MinimalLibrary(), "sdtmct-2025-09-26");

        IDataTable table = MockTable.of().name("DM").col("STUDYID", "S001").build();

        GeneratedRulePackage pkg = gen(noDefine, table, "DM", "SPECIAL PURPOSE");

        // No Define-XML rules generated
        assertTrue(
                pkg.getRules().stream().noneMatch(r -> r.getCore().getId().startsWith("GEN-DX")));

        // But skips are recorded
        assertTrue(pkg.getReport().getSkippedRules().stream()
                .anyMatch(s -> s.reason().contains("Define-XML not available")));
    }

    // ---- Report includes Define-XML info ----


    @Test
    void testReport_includesDefineXMLCategories()
    {
        IDataTable table = MockTable.of().name("DM").col("STUDYID", "S001").col("USUBJID", "SUBJ01")
                .build();

        GeneratedRulePackage pkg = gen(table, "DM", "SPECIAL PURPOSE");
        String md = pkg.getReport().toMarkdown();

        assertTrue(md.contains("DEFINE_VARIABLE_LABEL") || md.contains("DEFINE_VARIABLE_PRESENCE")
                || md.contains("DEFINE_KEY_UNIQUENESS"));
    }

    // ---- Deterministic IDs ----


    @Test
    void testDefineRules_deterministicIds()
    {
        IDataTable table = MockTable.of().name("DM").col("STUDYID", "S001").col("USUBJID", "SUBJ01")
                .build();

        GeneratedRulePackage pkg1 = gen(table, "DM", "SPECIAL PURPOSE");
        GeneratedRulePackage pkg2 = gen(table, "DM", "SPECIAL PURPOSE");

        assertEquals(pkg1.getRules().size(), pkg2.getRules().size());
        for (int i = 0; i < pkg1.getRules().size(); i++)
        {
            assertEquals(pkg1.getRules().get(i).getId(), pkg2.getRules().get(i).getId());
        }
    }

    // ---- rule/report pairing across the whole Define-XML family ----
    //
    // testReport_includesDefineXMLCategories asserts an `||` chain over the report markdown, so it
    // stays green while any ONE category is still emitted. That is why deleting an individual
    // `report.addGenerated(...)` survived in nine separate Define generators. The invariant below
    // is the one that actually binds them: every GEN-DX rule the generator produces must also be
    // recorded in the report, under its own category.


    /** A DM fixture that lights up presence / extra / label / type / length / codelist / key. */
    private IDataTable richDm()
    {
        defineXML.addVariable("DM", Map.of("name", "SEX", "label", "Sex", "dataType", "Char",
                "length", "2", "codelist", "CL.SEX", "codelistName", "SEX"));
        // AGE is declared in Define-XML but deliberately absent from the dataset, so the
        // variable-PRESENCE family fires too; CUSTOM1 is the mirror case for NO_EXTRA_VARIABLES.
        defineXML.addVariable("DM",
                Map.of("name", "AGE", "label", "Age", "dataType", "Num", "length", "3"));
        return MockTable.of().name("DM").col("STUDYID", "S001", "S001")
                .col("USUBJID", "SUBJ01", "SUBJ02").col("SEX", "M", "F").col("CUSTOM1", "X", "Y")
                .build();
    }


    @Test
    void everyGeneratedDefineRuleIsAlsoRecordedInTheReport()
    {
        GeneratedRulePackage pkg = gen(richDm(), "DM", "SPECIAL PURPOSE");

        List<String> ruleIds = pkg.getRules().stream().map(r -> r.getCore().getId())
                .filter(id -> id != null && id.startsWith("GEN-DX")).sorted().toList();
        List<String> reportedIds = pkg.getReport().getGeneratedRules().stream()
                .map(GeneratedRuleInfo::ruleId).filter(id -> id != null && id.startsWith("GEN-DX"))
                .sorted().toList();

        assertFalse(ruleIds.isEmpty(), "the fixture must produce Define-XML rules");
        assertTrue(reportedIds.containsAll(ruleIds),
                "every generated Define rule must be reported; missing="
                        + ruleIds.stream().filter(id -> !reportedIds.contains(id)).toList());
    }


    @Test
    void eachDefineFamilyIsReportedUnderItsOwnCategory()
    {
        Map<String, RuleCategory> expected = Map.of("GEN-DXPRES-",
                RuleCategory.DEFINE_VARIABLE_PRESENCE, "GEN-DXEXTRA-",
                RuleCategory.DEFINE_NO_EXTRA_VARIABLES, "GEN-DXLBL-",
                RuleCategory.DEFINE_VARIABLE_LABEL, "GEN-DXTYP-", RuleCategory.DEFINE_VARIABLE_TYPE,
                "GEN-DXLEN-", RuleCategory.DEFINE_VARIABLE_LENGTH, "GEN-DXCL-",
                RuleCategory.DEFINE_CODELIST_VALUES, "GEN-DXKEY-",
                RuleCategory.DEFINE_KEY_UNIQUENESS, "GEN-DXSTRUCT-",
                RuleCategory.DEFINE_DATASET_STRUCTURE);

        List<GeneratedRuleInfo> gens = gen(richDm(), "DM", "SPECIAL PURPOSE").getReport()
                .getGeneratedRules();

        for (Map.Entry<String, RuleCategory> e : expected.entrySet())
        {
            List<GeneratedRuleInfo> family = gens.stream()
                    .filter(g -> g.ruleId() != null && g.ruleId().startsWith(e.getKey())).toList();
            assertFalse(family.isEmpty(), e.getKey() + " produced no report entry");
            assertTrue(family.stream().allMatch(g -> g.category() == e.getValue()),
                    e.getKey() + " must be reported as " + e.getValue() + ", got " + family);
        }
    }

    // ---- Category 22: Dataset Structure (previously untested) ----


    @Test
    void testDefineDatasetStructure_structureTokenBecomesAKeyUniquenessRule()
    {
        // DM's Define-XML structure is "One record per subject"; the `subject` token maps to
        // USUBJID, which the dataset has.
        List<Rule> structRules = gen(richDm(), "DM", "SPECIAL PURPOSE").getRules().stream()
                .filter(r -> r.getCore().getId().startsWith("GEN-DXSTRUCT-")).toList();

        assertEquals(1, structRules.size());
        assertEquals("GEN-DXSTRUCT-DM", structRules.getFirst().getCore().getId());
    }


    @Test
    void testDefineDatasetStructure_aStructureWhoseKeyColumnIsAbsentIsReportedAsSkipped()
    {
        // "One record per subject" still parses, but USUBJID is not in this dataset, so no key
        // survives and the generator must SAY so rather than silently emit nothing.
        IDataTable noKey = MockTable.of().name("DM").col("STUDYID", "S001").build();

        GeneratedRulePackage pkg = gen(noKey, "DM", "SPECIAL PURPOSE");

        assertTrue(pkg.getRules().stream()
                .noneMatch(r -> r.getCore().getId().startsWith("GEN-DXSTRUCT-")));
        assertTrue(
                pkg.getReport().getSkippedRules().stream()
                        .anyMatch(sk -> sk.category() == RuleCategory.DEFINE_DATASET_STRUCTURE
                                && sk.reason().contains("Could not parse structure")),
                pkg.getReport().getSkippedRules().toString());
    }


    @Test
    void testDefineDatasetStructure_theKeyColumnIsFoundEvenAtColumnZero()
    {
        // parseStructureKeys probes with `meta.getColumnIndex(var) >= 0`; with USUBJID leading the
        // dataset the index is 0, which is where `>= 0` and `> 0` disagree.
        IDataTable usubjidFirst = MockTable.of().name("DM").col("USUBJID", "SUBJ01", "SUBJ02")
                .col("STUDYID", "S001", "S001").build();

        assertTrue(gen(usubjidFirst, "DM", "SPECIAL PURPOSE").getRules().stream()
                .anyMatch(r -> r.getCore().getId().startsWith("GEN-DXSTRUCT-")));
    }

    // ---- Mock providers ----

    private static class MockDefineXML implements DefineXMLProvider
    {

        private final java.util.Map<String, java.util.List<Map<String, String>>> variables = new java.util.HashMap<>();

        MockDefineXML()
        {
            // Default DM variables
            variables.put("DM",
                    new java.util.ArrayList<>(List.of(
                            Map.of("name", "STUDYID", "label", "Study Identifier", "dataType",
                                    "Char", "length", "12"),
                            Map.of("name", "USUBJID", "label", "Unique Subject Identifier",
                                    "dataType", "Char", "length", "20"))));
        }


        void addVariable(String dataset, Map<String, String> variable)
        {
            variables.computeIfAbsent(dataset, _ -> new java.util.ArrayList<>()).add(variable);
        }


        @Override
        public Map<String, String> getDatasetMetadata(String ds)
        {
            if ("DM".equals(ds))
            {
                return Map.of("name", "DM", "label", "Demographics", "structure",
                        "One record per subject");
            }
            return Map.of();
        }


        @Override
        public List<Map<String, String>> getVariables(String ds)
        {
            return variables.getOrDefault(ds, List.of());
        }


        @Override
        public List<Map<String, String>> getValueLevelMetadata(String ds, String variableName)
        {
            return List.of();
        }


        @Override
        public List<Map<String, String>> getWhereClauseConditions(String wcOID)
        {
            return List.of();
        }


        @Override
        public List<Map<String, String>> getCodelistTerms(String clOID)
        {
            if ("CL.SEX".equals(clOID))
            {
                return List.of(Map.of("codedValue", "M", "decode", "Male"),
                        Map.of("codedValue", "F", "decode", "Female"));
            }
            return List.of();
        }


        @Override
        public List<String> getDatasetNames()
        {
            return List.of("DM");
        }


        @Override
        public List<String> getKeyVariables(String ds)
        {
            if ("DM".equals(ds))
            {
                return List.of("STUDYID", "USUBJID");
            }
            return List.of();
        }
    }


    private static class MinimalLibrary implements MetadataProvider
    {

        @Override
        public List<String> getRequiredVariables(String d)
        {
            return List.of();
        }


        @Override
        public List<String> getExpectedVariables(String d)
        {
            return List.of();
        }


        @Override
        public List<String> getColumnOrder(String d)
        {
            return List.of();
        }


        @Override
        public List<String> getModelColumnOrder(String d)
        {
            return List.of();
        }


        @Override
        public boolean isDomainCustom(String d)
        {
            return false;
        }


        @Override
        public List<String> getCodelistTerms(String c)
        {
            return List.of();
        }


        @Override
        public Map<String, String> getVariableMetadata(String d, String v)
        {
            return Map.of();
        }


        @Override
        public List<Map<String, String>> getDomainVariables(String d)
        {
            return List.of();
        }


        @Override
        public Map<String, String> getDatasetMetadata(String d)
        {
            return Map.of();
        }


        @Override
        public boolean isCodelistExtensible(String cl)
        {
            return true;
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

}
