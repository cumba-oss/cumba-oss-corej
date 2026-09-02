package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.DataStructureScope;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RuleCore;
import net.cumba.cdisc.core.model.Scope;
import net.cumba.cdisc.core.model.Sensitivity;
import net.cumba.cdisc.core.model.SubclassScope;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Fix #117/#118: the runtime {@code Scope.Data_Structures} / {@code Scope.Subclasses} gates in
 * {@link RuleRunner#execute} — the counterpart of the generation-time gate, exercised by
 * direct-execution paths (the .cdt ruletest harness).
 */
class RuleRunnerStructureScopeTest
{

    private static final DatasetResolver NO_DATASETS = _ -> null;

    private static Rule rule(@Nullable List<String> structureInclude,
            @Nullable List<String> subclassInclude)
    {
        Rule rule = new Rule();
        rule.setId("33333333-3333-3333-3333-333333333333");
        RuleCore core = new RuleCore();
        core.setId("TEST-STRUCT-GATE");
        core.setStatus("Published");
        core.setVersion("1");
        rule.setCore(core);
        rule.setSensitivity(Sensitivity.RECORD);
        Scope scope = new Scope();
        if (structureInclude != null)
        {
            DataStructureScope ds = new DataStructureScope();
            ds.setInclude(structureInclude);
            scope.setDataStructures(ds);
        }
        if (subclassInclude != null)
        {
            SubclassScope sc = new SubclassScope();
            sc.setInclude(subclassInclude);
            scope.setSubclasses(sc);
        }
        rule.setScope(scope);
        rule.setCheck(CheckConditionLeaf.builder().name("STUDYID").operator("empty").build());
        // Hand-built rules bypass RulePackageLoader — give the Check its compiled/native form so
        // the matching cases actually execute (same treatment WildcardExpander gives expansions).
        net.cumba.cdisc.core.RulePackageLoader.installNativeExpr(rule);
        return rule;
    }


    @Test
    void structureMismatch_skippedWithReason()
    {
        Rule rule = rule(List.of("BASIC DATA STRUCTURE"), null);
        IDataTable table = MockTable.of().name("ADXX").col("STUDYID", "S1").build();
        RuleExecutionResult result = RuleRunner.execute(rule, table, NO_DATASETS);
        assertTrue(result.isSkipped());
        assertEquals("Rule skipped — data structure ADAM OTHER not in Scope.Data_Structures.Include"
                + " [BASIC DATA STRUCTURE]", result.getStatusMessage());
    }


    @Test
    void structureMatch_executes()
    {
        Rule rule = rule(List.of("BASIC DATA STRUCTURE"), null);
        IDataTable table = MockTable.of().name("ADLBC").col("STUDYID", "S1").col("PARAMCD", "P1")
                .col("AVAL", "1").build();
        RuleExecutionResult result = RuleRunner.execute(rule, table, NO_DATASETS);
        assertFalse(result.isSkipped());
        assertFalse(result.isError());
    }


    @Test
    void subclassMismatch_skippedWithReason()
    {
        // Plain BDS dataset (no CNSR) — a TTE-scoped rule must skip (Q1 null semantics).
        Rule rule = rule(null, List.of("TIME-TO-EVENT"));
        IDataTable table = MockTable.of().name("ADLBC").col("STUDYID", "S1").col("PARAMCD", "P1")
                .col("AVAL", "1").build();
        RuleExecutionResult result = RuleRunner.execute(rule, table, NO_DATASETS);
        assertTrue(result.isSkipped());
        assertEquals("Rule skipped — no subclass detected but rule has Scope.Subclasses.Include"
                + " [TIME-TO-EVENT]", result.getStatusMessage());
    }


    @Test
    void subclassMatch_executes()
    {
        Rule rule = rule(null, List.of("TIME-TO-EVENT"));
        IDataTable table = MockTable.of().name("ADTTE").col("STUDYID", "S1").col("PARAMCD", "P1")
                .col("AVAL", "1").col("CNSR", "0").build();
        RuleExecutionResult result = RuleRunner.execute(rule, table, NO_DATASETS);
        assertFalse(result.isSkipped());
        assertFalse(result.isError());
    }


    @Test
    void noStructureScopes_detectorsNotConsulted()
    {
        // A rule without either scope executes as before on any dataset shape.
        Rule rule = rule(null, null);
        IDataTable table = MockTable.of().name("ADXX").col("STUDYID", "S1").build();
        RuleExecutionResult result = RuleRunner.execute(rule, table, NO_DATASETS);
        assertFalse(result.isSkipped());
    }

    // ---- Fix #119: the DECLARED channel through the defineProvider parameter ----


    /** Declared-value stub — only the Fix #119 accessors matter, the rest are inert. */
    private static MetadataProvider declaring(String declaredClass, List<String> declaredSubClasses)
    {
        return new MetadataProvider()
        {

            @Override
            public @Nullable String getDeclaredDatasetClass(String datasetName)
            {
                return declaredClass;
            }


            @Override
            public List<String> getDeclaredSubClasses(String datasetName)
            {
                return declaredSubClasses;
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
            public List<String> getCodelistTerms(String codelistCode)
            {
                return List.of();
            }


            @Override
            public java.util.Map<String, String> getVariableMetadata(String domain, String variable)
            {
                return java.util.Map.of();
            }


            @Override
            public List<java.util.Map<String, String>> getDomainVariables(String domain)
            {
                return List.of();
            }


            @Override
            public java.util.Map<String, String> getDatasetMetadata(String domain)
            {
                return java.util.Map.of();
            }


            @Override
            public boolean isCodelistExtensible(String codelistName)
            {
                return false;
            }


            @Override
            public java.util.Map<String, String> getCodelistTermMappings(String codelistName)
            {
                return java.util.Map.of();
            }


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
        };
    }


    private static RuleExecutionResult executeWithDefine(Rule rule, IDataTable table,
            MetadataProvider defineProvider)
    {
        return RuleRunner.execute(rule, table, NO_DATASETS, null, null, null, defineProvider);
    }


    @Test
    void declaredStructure_fallsBackWhenHeuristicYieldsOther()
    {
        // Heuristic says ADAM OTHER (no indicator columns), the declared BDS supplies the verdict
        // — the rule executes instead of skipping. True in both preference modes.
        Rule rule = rule(List.of("BASIC DATA STRUCTURE"), null);
        IDataTable table = MockTable.of().name("ADXX").col("STUDYID", "S1").build();
        RuleExecutionResult result = executeWithDefine(rule, table,
                declaring("BASIC DATA STRUCTURE", List.of()));
        assertFalse(result.isSkipped(),
                "declared class must fill in when the heuristic yields OTHER: "
                        + result.getStatusMessage());
    }


    @Test
    void declaredStructure_preferredUnderDefineFirst()
    {
        // defineFirst=true: the declared OCCDS beats the heuristic BDS (PARAMCD/AVAL present).
        Rule rule = rule(List.of("BASIC DATA STRUCTURE"), null);
        IDataTable table = MockTable.of().name("ADLBC").col("STUDYID", "S1").col("PARAMCD", "P1")
                .col("AVAL", "1").build();
        System.setProperty(DEFINE_FIRST, "true");
        try
        {
            RuleExecutionResult result = executeWithDefine(rule, table,
                    declaring("OCCURRENCE DATA STRUCTURE", List.of()));
            assertTrue(result.isSkipped());
            assertEquals(
                    "Rule skipped — data structure OCCURRENCE DATA STRUCTURE not in"
                            + " Scope.Data_Structures.Include [BASIC DATA STRUCTURE]",
                    result.getStatusMessage());
        }
        finally
        {
            System.clearProperty(DEFINE_FIRST);
        }
    }


    @Test
    void declaredSubclass_fallsBackWhenHeuristicYieldsNothing()
    {
        // Plain BDS dataset (heuristic: no subclass) with a declared TIME-TO-EVENT: the
        // declaration supplies the verdict and the TTE-scoped rule executes.
        Rule rule = rule(null, List.of("TIME-TO-EVENT"));
        IDataTable table = MockTable.of().name("ADLBC").col("STUDYID", "S1").col("PARAMCD", "P1")
                .col("AVAL", "1").build();
        RuleExecutionResult result = executeWithDefine(rule, table,
                declaring(null, List.of("TIME-TO-EVENT")));
        assertFalse(result.isSkipped(),
                "declared SubClass must fill in when the heuristic detects nothing: "
                        + result.getStatusMessage());
    }

    // ---- Fix #154: the flipped default, the library tier and the name tier ------

    private static final String DEFINE_FIRST = "corej.defineFirst";

    /**
     * Fix #154's actual behaviour change at the gate: with <b>no</b> system property set, a
     * declaration that contradicts a confident column detection now WINS. Before Fix #154 the same
     * inputs produced an executing rule (heuristic BDS), which is precisely the masking this
     * decision rejects — the disagreement between declaration and data is itself a finding.
     */
    @Test
    void declaredStructure_beatsTheColumnsByDefault_fix154()
    {
        assertNull(System.getProperty(DEFINE_FIRST), "test must run with the shipped default");
        Rule rule = rule(List.of("BASIC DATA STRUCTURE"), null);
        IDataTable table = MockTable.of().name("ADLBC").col("STUDYID", "S1").col("PARAMCD", "P1")
                .col("AVAL", "1").build();

        RuleExecutionResult result = executeWithDefine(rule, table,
                declaring("OCCURRENCE DATA STRUCTURE", List.of()));

        assertTrue(result.isSkipped(), "Fix #154: the declaration wins with no property set: "
                + result.getStatusMessage());
        assertEquals(
                "Rule skipped — data structure OCCURRENCE DATA STRUCTURE not in"
                        + " Scope.Data_Structures.Include [BASIC DATA STRUCTURE]",
                result.getStatusMessage());
    }


    /** The documented opt-out: {@code -Dcorej.defineFirst=false} restores columns-first. */
    @Test
    void defineFirstFalse_restoresColumnsFirst_fix154()
    {
        Rule rule = rule(List.of("BASIC DATA STRUCTURE"), null);
        IDataTable table = MockTable.of().name("ADLBC").col("STUDYID", "S1").col("PARAMCD", "P1")
                .col("AVAL", "1").build();
        System.setProperty(DEFINE_FIRST, "false");
        try
        {
            RuleExecutionResult result = executeWithDefine(rule, table,
                    declaring("OCCURRENCE DATA STRUCTURE", List.of()));
            assertFalse(result.isSkipped(), "columns-first: the confident BDS detection wins: "
                    + result.getStatusMessage());
        }
        finally
        {
            System.clearProperty(DEFINE_FIRST);
        }
    }


    /**
     * Fix #154 tier 2. Before it, {@code defineProvider != null ? defineProvider : libraryProvider}
     * meant a define that declared <em>nothing</em> for the dataset silently suppressed a library
     * declaration that did. The tiers are now chained: an answer from the define wins, an absence
     * falls through to the library.
     */
    @Test
    void libraryDeclarationIsConsultedWhenTheDefineDeclaresNothing_fix154()
    {
        Rule rule = rule(null, List.of("TIME-TO-EVENT"));
        IDataTable table = MockTable.of().name("ADLBC").col("STUDYID", "S1").col("PARAMCD", "P1")
                .col("AVAL", "1").build();
        MetadataProvider silentDefine = declaring(null, List.of());
        MetadataProvider libraryDeclaring = declaring(null, List.of("TIME-TO-EVENT"));

        RuleExecutionResult result = RuleRunner.execute(rule, table, NO_DATASETS, null,
                libraryDeclaring, null, silentDefine);
        assertFalse(result.isSkipped(),
                "the library's declaration must be reached past a silent define: "
                        + result.getStatusMessage());

        // Counter-proof that the fixture is not vacuous: with neither provider declaring anything
        // the same plain-BDS dataset has no subclass and the rule skips.
        RuleExecutionResult noDeclaration = RuleRunner.execute(rule, table, NO_DATASETS, null,
                declaring(null, List.of()), null, silentDefine);
        assertTrue(noDeclaration.isSkipped(), noDeclaration.getStatusMessage());

        // …and that the define still WINS when it does declare: a contradicting define beats the
        // library, so the chain is ordered, not a union.
        RuleExecutionResult defineWins = RuleRunner.execute(rule, table, NO_DATASETS, null,
                libraryDeclaring, null, declaring(null, List.of("NON-COMPARTMENTAL ANALYSIS")));
        assertTrue(defineWins.isSkipped(), defineWins.getStatusMessage());
    }


    /**
     * Fix #154 tier 3c, end to end at the gate: no provider at all, a BDS dataset whose columns
     * carry no popPK signature, and a name that does. This is the local-only fallback the decision
     * exists for — and it is what makes {@code CDISC-AD0887}/{@code AD0889} (which test for the
     * absence of {@code EVID}/{@code MDV}, the very columns the heuristic keys on) reachable
     * without a Define-XML.
     */
    @Test
    void nameTierReachesAPopPkDatasetWithNoControlColumn_fix154()
    {
        Rule rule = rule(List.of("BASIC DATA STRUCTURE"),
                List.of("POPULATION PHARMACOKINETIC ANALYSIS"));
        IDataTable table = MockTable.of().name("ADPPK").col("STUDYID", "S1").col("PARAMCD", "P1")
                .col("AVAL", "1").build();

        RuleExecutionResult result = RuleRunner.execute(rule, table, NO_DATASETS);
        assertFalse(result.isSkipped(),
                "the ADPPK name is the last-resort popPK signal: " + result.getStatusMessage());

        // Discriminator: the identical dataset under a sponsor name outside the prefix set stays
        // out of scope, so the verdict above is the NAME's doing and nothing else's.
        IDataTable sponsorNamed = MockTable.of().name("ADPK01").col("STUDYID", "S1")
                .col("PARAMCD", "P1").col("AVAL", "1").build();
        RuleExecutionResult skipped = RuleRunner.execute(rule, sponsorNamed, NO_DATASETS);
        assertTrue(skipped.isSkipped(), skipped.getStatusMessage());
    }

    // ---- Fix #179: the structure is a SET, end to end through RuleRunner ----------


    /**
     * ⚑⚑ <b>Fix #179 end to end, and the runtime N1 neuter target.</b> One dataset, declared
     * {@code MEDICAL DEVICE BASIC DATA STRUCTURE}, run against three rules:
     *
     * <ol>
     * <li>scoped to the <b>base</b> — executes (the safety property: the 78 shipped
     * {@code Data_Structures} entries keep covering medical-device datasets);</li>
     * <li>scoped to the <b>specialisation</b> — executes. ⚠ <b>This is the assertion that reddens
     * if the set is collapsed back to a single folded value</b>, and the only thing the
     * pre-Fix-#175 fold made impossible;</li>
     * <li>scoped to the <b>sibling</b> specialisation — skips, with the mismatch naming the most
     * specific detected token and the full set.</li>
     * </ol>
     *
     * <p>
     * The dataset's columns carry no BDS indicator, so the verdict is the <em>declaration's</em>
     * doing throughout —
     * {@link #declaredStructure_devicePlainBdsIsNotCoveredByAVariantScope_fix179} supplies the
     * discriminator in the other direction.
     * </p>
     */
    @Test
    void declaredDeviceStructure_matchesBothItsOwnTokenAndItsBase_fix179()
    {
        IDataTable table = MockTable.of().name("ADMDX").col("STUDYID", "S1").build();
        MetadataProvider define = declaring("MEDICAL DEVICE BASIC DATA STRUCTURE", List.of());

        RuleExecutionResult base = executeWithDefine(rule(List.of("BASIC DATA STRUCTURE"), null),
                table, define);
        assertFalse(base.isSkipped(),
                "a BASIC DATA STRUCTURE rule must still reach a medical-device BDS dataset: "
                        + base.getStatusMessage());

        RuleExecutionResult variant = executeWithDefine(
                rule(List.of("MEDICAL DEVICE BASIC DATA STRUCTURE"), null), table, define);
        assertFalse(variant.isSkipped(),
                "Fix #179: the specialisation is now expressible and must match: "
                        + variant.getStatusMessage());

        RuleExecutionResult sibling = executeWithDefine(
                rule(List.of("MEDICAL DEVICE OCCURRENCE DATA STRUCTURE"), null), table, define);
        assertTrue(sibling.isSkipped());
        assertEquals(
                "Rule skipped — data structure MEDICAL DEVICE BASIC DATA STRUCTURE (also BASIC DATA"
                        + " STRUCTURE) not in Scope.Data_Structures.Include"
                        + " [MEDICAL DEVICE OCCURRENCE DATA STRUCTURE]",
                sibling.getStatusMessage());
    }


    /**
     * The discriminator for
     * {@link #declaredDeviceStructure_matchesBothItsOwnTokenAndItsBase_fix179}: the identical rule
     * against a <em>plain</em> BDS dataset skips, so the match above is the device declaration's
     * doing and not a rule that matches everything.
     */
    @Test
    void declaredStructure_devicePlainBdsIsNotCoveredByAVariantScope_fix179()
    {
        Rule rule = rule(List.of("MEDICAL DEVICE BASIC DATA STRUCTURE"), null);
        IDataTable table = MockTable.of().name("ADLBC").col("STUDYID", "S1").col("PARAMCD", "P1")
                .col("AVAL", "1").build();
        RuleExecutionResult result = RuleRunner.execute(rule, table, NO_DATASETS);
        assertTrue(result.isSkipped());
        assertEquals(
                "Rule skipped — data structure BASIC DATA STRUCTURE not in"
                        + " Scope.Data_Structures.Include [MEDICAL DEVICE BASIC DATA STRUCTURE]",
                result.getStatusMessage());
    }


    /**
     * Fix #179 must not disturb the subclass tier: a declared device BDS dataset carrying
     * {@code CNSR} still detects {@code TIME-TO-EVENT}, exactly as it did when the declaration
     * folded onto {@code BASIC DATA STRUCTURE}. This is {@code AdamSubclassDetector}'s
     * contains-check observed through the production gate rather than in a unit.
     */
    @Test
    void declaredDeviceStructure_stillReachesTheBdsSubclassTier_fix179()
    {
        Rule rule = rule(null, List.of("TIME-TO-EVENT"));
        IDataTable table = MockTable.of().name("ADMDTTE").col("STUDYID", "S1").col("CNSR", "0")
                .build();
        RuleExecutionResult result = executeWithDefine(rule, table,
                declaring("MEDICAL DEVICE BASIC DATA STRUCTURE", List.of()));
        assertFalse(result.isSkipped(),
                "a device BDS dataset must still satisfy the BDS subclass precondition: "
                        + result.getStatusMessage());
    }

}
