package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * {@code Supp_Merge} through {@link RuleRunner} ({@code PLAN-operation-replacements} §2.3): the
 * rule-level flag, its default, and the one meaning it has on the value path (the merged column),
 * the existence path ({@code var_exists}, the qualified requirement) and the Requirements gate.
 */
class RuleRunnerSuppMergeTest
{

    private static Rule load(String ruleJson)
    {
        try
        {
            RulePackage pkg = RulePackageLoader
                    .loadFromString("{\"rules\":{\"X-1\":" + ruleJson + "}}");
            Rule rule = pkg.getRules().get("X-1");
            assertNull(rule.getLoadError(), "fixture must load: " + rule.getLoadError());
            return rule;
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("bad test fixture: " + ruleJson, e);
        }
    }


    private static String pcRule(String check, String extra)
    {
        return "{\"Core\":{\"Id\":\"X-1\"}," + extra + "\"Check\":{\"expression\":\"" + check
                + "\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"PCSTRESC\",\"PCCALCN\"]}}";
    }


    private static IDataTable pc()
    {
        return RealTableFixture.of("PC").str("DOMAIN", "PC", "PC", "PC")
                .str("USUBJID", "S1", "S1", "S2").lng("PCSEQ", 1L, 2L, 1L)
                .str("PCSTRESC", "BLQ", "BLQ", "BLQ").build();
    }


    private static IDataTable suppPc()
    {
        return RealTableFixture.of("SUPPPC").str("RDOMAIN", "PC").str("USUBJID", "S1")
                .str("IDVAR", "PCSEQ").str("IDVARVAL", "1").str("QNAM", "PCCALCN")
                .str("QVAL", "0.5").build();
    }


    /** An inventory-capable resolver, so qualified Requirements entries can be decided. */
    private static DatasetResolver.WithInventory inventory(IDataTable... tables)
    {
        return new DatasetResolver.WithInventory()
        {

            @Override
            public @Nullable IDataTable resolve(String domainName)
            {
                for (IDataTable t : tables)
                {
                    if (t.getMetaData().getName().equalsIgnoreCase(domainName))
                    {
                        return t;
                    }
                }
                return null;
            }


            @Override
            public Set<String> availableDatasets()
            {
                Set<String> out = new java.util.LinkedHashSet<>();
                for (IDataTable t : tables)
                {
                    out.add(t.getMetaData().getName());
                }
                return out;
            }
        };
    }


    @Test
    void theMergedQualifierIsReadByTheCheckAndReportedByDefault()
    {
        Rule rule = load(pcRule("PCSTRESC == \\\"BLQ\\\" and empty(PCCALCN)", ""));
        IDataTable pc = pc();
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, pc, inventory(pc, suppPc()));
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus());
        assertEquals(List.of(2L, 3L),
                result.getViolations().stream().map(Violation::getRowNumber).toList(),
                "S1/PCSEQ=1 carries PCCALCN and is silent; the other two records fire");
        assertEquals("", result.getViolations().get(0).getValues().get("PCCALCN"),
                "a record without the qualifier reports the present blank");
    }


    @Test
    void theMergedValueIsReported()
    {
        Rule rule = load(pcRule("PCSTRESC == \\\"BLQ\\\" and not empty(PCCALCN)", ""));
        IDataTable pc = pc();
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, pc, inventory(pc, suppPc()));
        assertEquals(1, result.getViolations().size());
        assertEquals(1L, result.getViolations().get(0).getRowNumber());
        assertEquals("0.5", result.getViolations().get(0).getValues().get("PCCALCN"),
                "the finding reports the SUPP row's QVAL as the record's own column");
    }


    @Test
    void suppMergeFalseEvaluatesTheRawDataset()
    {
        Rule rule = load(
                pcRule("PCSTRESC == \\\"BLQ\\\" and empty(PCCALCN)", "\"Supp_Merge\": false,"));
        IDataTable pc = pc();
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, pc, inventory(pc, suppPc()));
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus());
        assertEquals(3, result.getViolations().size(),
                "PCCALCN is absent from the raw PC, so every BLQ record fires");
    }


    @Test
    void theExistenceFactFollowsTheFlag()
    {
        // Row-based on purpose (PCSTRESC): a Check reading no column is a dataset-level fact that
        // RuleRunner reports once, which would hide the per-row count this test reads.
        String check = "PCSTRESC == \\\"BLQ\\\" and var_exists(\\\"PCCALCN\\\")";
        IDataTable pc = pc();
        RuleExecutionResult on = RuleRunnerCalls.execute(load(pcRule(check, "")), pc,
                inventory(pc, suppPc()));
        assertEquals(3, on.getViolations().size(), "the merged column exists on every row");
        RuleExecutionResult off = RuleRunnerCalls.execute(
                load(pcRule(check, "\"Supp_Merge\": false,")), pc, inventory(pc, suppPc()));
        assertEquals(0, off.getViolations().size(), "the raw PC has no PCCALCN column");
    }


    @Test
    void aRequirementOnTheQualifierIsMetByTheMergeAndSkipsWithoutIt()
    {
        String requirement = "\"Requirements\":{\"Variables\":{\"All\":[\"PCCALCN\"]}},";
        String check = "PCSTRESC == \\\"BLQ\\\" and empty(PCCALCN)";
        IDataTable pc = pc();
        RuleExecutionResult on = RuleRunnerCalls.execute(load(pcRule(check, requirement)), pc,
                inventory(pc, suppPc()));
        assertEquals(RuleExecutionStatus.EXECUTED, on.getStatus());
        assertEquals(2, on.getViolations().size());
        RuleExecutionResult off = RuleRunnerCalls.execute(
                load(pcRule(check, requirement + "\"Supp_Merge\": false,")), pc,
                inventory(pc, suppPc()));
        assertEquals(RuleExecutionStatus.SKIPPED, off.getStatus(),
                "without the merge the required column is absent: " + off.getStatusMessage());
    }


    @Test
    void theQualifiedForeignRequirementPivotFollowsTheFlag()
    {
        // CDISC-AD0640's shape: AETRTEM reaches the requirement only through SUPPAE.
        String adae = "{\"Core\":{\"Id\":\"X-1\"},%s"
                + "\"Requirements\":{\"Variables\":{\"All\":[\"AE.AETRTEM\"]}},"
                + "\"Check\":{\"expression\":\"not var_exists(\\\"AETRTEM\\\")\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AETERM\"]}}";
        IDataTable primary = RealTableFixture.of("ADAE").str("USUBJID", "S1").lng("ASEQ", 1L)
                .str("AETERM", "HEADACHE").build();
        IDataTable ae = RealTableFixture.of("AE").str("DOMAIN", "AE").str("USUBJID", "S1")
                .lng("AESEQ", 1L).str("AETERM", "HEADACHE").build();
        IDataTable suppAe = RealTableFixture.of("SUPPAE").str("RDOMAIN", "AE").str("USUBJID", "S1")
                .str("IDVAR", "AESEQ").str("IDVARVAL", "1").str("QNAM", "AETRTEM").str("QVAL", "Y")
                .build();
        RuleExecutionResult on = RuleRunnerCalls.execute(load(adae.formatted("")), primary,
                inventory(primary, ae, suppAe));
        assertEquals(RuleExecutionStatus.EXECUTED, on.getStatus(), on.getStatusMessage());
        assertEquals(1, on.getViolations().size(), "AETRTEM is delivered, ADAE lacks it");
        RuleExecutionResult off = RuleRunnerCalls.execute(
                load(adae.formatted("\"Supp_Merge\": false,")), primary,
                inventory(primary, ae, suppAe));
        assertEquals(RuleExecutionStatus.SKIPPED, off.getStatus(),
                "with the merge off the pivot is off too: " + off.getStatusMessage());
    }


    /**
     * C1 ruled (a), owner 2026-09-29: <i>"Supplementals are used to give a place for non standard
     * variables."</i> A qualifier is readable per record (above) but it is NOT a variable of the
     * parent for the variable-metadata surface. The naive table augmentation reported the merged
     * QNAM columns as variables of AE / DM / DS in 11 study rules (CG0013, CG0351, CG0664, FDA /
     * PMDA-SD0058 / SD0060 / SD1079 / SD1082 — the measured red-before); the pivot by reference
     * leaves the table's column set alone, so the per-variable iteration never visits PCCALCN.
     */
    @Test
    void aQualifierIsNotAVariableOfTheDatasetForThePerVariableIteration()
    {
        // A Variable Metadata Check: one visit per column of the dataset, firing on the name.
        String rule = "{\"Core\":{\"Id\":\"X-1\"},"
                + "\"Check\":{\"expression\":\"varname() == \\\"PCCALCN\\\"\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"variable_name\"]}}";
        IDataTable pc = pc();
        RuleExecutionResult withSupp = RuleRunnerCalls.execute(load(rule), pc,
                inventory(pc, suppPc()));
        assertEquals(RuleExecutionStatus.EXECUTED, withSupp.getStatus(),
                withSupp.getStatusMessage());
        assertEquals(0, withSupp.getViolations().size(),
                "PCCALCN is delivered by SUPPPC, so it is not a variable of PC");
        // The control: the same rule sees a REAL PCCALCN column.
        IDataTable pcWithColumn = RealTableFixture.of("PC").str("DOMAIN", "PC", "PC", "PC")
                .str("USUBJID", "S1", "S1", "S2").lng("PCSEQ", 1L, 2L, 1L)
                .str("PCSTRESC", "BLQ", "BLQ", "BLQ").str("PCCALCN", "0.5", "", "").build();
        RuleExecutionResult real = RuleRunnerCalls.execute(load(rule), pcWithColumn,
                inventory(pcWithColumn));
        assertEquals(1, real.getViolations().size(), "a real column IS visited");
        assertEquals("PCCALCN", real.getViolations().get(0).getValues().get("variable_name"));
    }


    @Test
    void aQualifierIsAbsentFromTheColumnOrderAndCountSurfaces()
    {
        // get_column_order_from_dataset / variable_count read the dataset's own columns.
        String rule = "{\"Core\":{\"Id\":\"X-1\"},"
                + "\"Bindings\":[{\"name\":\"$cols\",\"expression\":\"get_column_order_from_dataset()\"},"
                + "{\"name\":\"$n\",\"expression\":\"variable_count()\"}],"
                + "\"Check\":{\"expression\":\"PCSTRESC == \\\"BLQ\\\" and not empty(PCCALCN)\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"$cols\",\"$n\",\"PCCALCN\"]}}";
        IDataTable pc = pc();
        RuleExecutionResult result = RuleRunnerCalls.execute(load(rule), pc,
                inventory(pc, suppPc()));
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(1, result.getViolations().size(), "the qualifier value IS read per record");
        java.util.Map<String, String> values = result.getViolations().get(0).getValues();
        assertEquals("0.5", values.get("PCCALCN"), "and reported");
        assertEquals("[DOMAIN, USUBJID, PCSEQ, PCSTRESC]", values.get("$cols"),
                "the column order of the dataset does not carry the qualifier");
        assertEquals("4", values.get("$n"), "nor does the variable count");
    }


    @Test
    void theFlagDefaultsToTrueAndRoundTripsAsDeclared()
    {
        assertTrue(load(pcRule("empty(PCCALCN)", "")).isSuppMergeEnabled());
        assertNull(load(pcRule("empty(PCCALCN)", "")).getSuppMerge(), "absent stays absent");
        assertFalse(load(pcRule("empty(PCCALCN)", "\"Supp_Merge\": false,")).isSuppMergeEnabled());
        assertTrue(load(pcRule("empty(PCCALCN)", "\"Supp_Merge\": true,")).isSuppMergeEnabled());
    }


    /**
     * ⚑ {@code PLAN-dynamic-column-functions} deviation 10, PINNED as it stands (review round 1,
     * lane B L6; the direction is owner-pending as lane C F1): the authored bare name reads the
     * merged SUPP qualifier, {@code colref} over the same name does NOT — the pivot is not a column
     * of the evaluation table ({@code DynamicColumnRead}), and {@code find_vars} does not return
     * pivoted names either (owner Q12). If the owner rules that {@code colref} reads the pivot,
     * this pin flips with the fix.
     */
    @Test
    void colrefDoesNotReadTheMergedQualifierTheAuthoredNameReads()
    {
        IDataTable pc = pc();
        RuleExecutionResult authored = RuleRunnerCalls.execute(
                load(pcRule("PCSTRESC == \\\"BLQ\\\" and not empty(PCCALCN)", "")), pc,
                inventory(pc, suppPc()));
        assertEquals(List.of(1L),
                authored.getViolations().stream().map(Violation::getRowNumber).toList());
        RuleExecutionResult dynamic = RuleRunnerCalls.execute(
                load(pcRule("PCSTRESC == \\\"BLQ\\\" and not empty(colref(\\\"PCCALCN\\\"))", "")),
                pc, inventory(pc, suppPc()));
        assertEquals(RuleExecutionStatus.EXECUTED, dynamic.getStatus(), dynamic.getStatusMessage());
        assertEquals(List.of(),
                dynamic.getViolations().stream().map(Violation::getRowNumber).toList(),
                "colref reads the evaluation table only: PCCALCN is no column there");
    }
}
