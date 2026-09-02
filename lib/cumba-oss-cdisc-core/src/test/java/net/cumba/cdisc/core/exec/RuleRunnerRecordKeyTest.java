package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.Outcome;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RuleCore;
import net.cumba.cdisc.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * End-to-end coverage for the EC-40 record key on the rule-execution path: the key is attached to
 * each {@link Violation} from the resolved {@link RecordKeyResolver.RowKeySpec}, and — the point of
 * decision D2/D10 — {@link Violation#getValues()} is completely unaffected by it.
 */
class RuleRunnerRecordKeyTest
{

    /**
     * SUPPAE-shaped table: no sequence variable, so the STRUCTURAL tier is what identifies rows.
     */
    private static IDataTable suppTable()
    {
        return MockTable.of().col("STUDYID", "S", "S").col("RDOMAIN", "AE", "AE")
                .col("USUBJID", "SUBJ-001", "SUBJ-002").col("IDVAR", "AESEQ", "AESEQ")
                .col("IDVARVAL", "3", "7").col("QNAM", "AESOSP", "AESOSP").col("QVAL", "", "Y")
                .name("SUPPAE").build();
    }


    private static Rule qvalNonEmptyRule()
    {
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("QVAL").operator("empty")
                .build();
        return buildRule("CORE-KEY-1", "QVAL must be populated",
                new CheckConditionAll(List.of(leaf)), List.of("QVAL"));
    }


    /**
     * Runs the rule with {@code corej.findingKeys} set to {@code aMode}, restoring the previous
     * value afterwards.
     */
    private static RuleExecutionResult runWithMode(Rule aRule, IDataTable aTable, String aMode)
    {
        String previous = System.getProperty(FindingKeyMode.PROP);
        try
        {
            if (aMode == null)
            {
                System.clearProperty(FindingKeyMode.PROP);
            }
            else
            {
                System.setProperty(FindingKeyMode.PROP, aMode);
            }
            return RuleRunner.execute(aRule, aTable, _ -> null, "SUPPAE");
        }
        finally
        {
            if (previous != null)
            {
                System.setProperty(FindingKeyMode.PROP, previous);
            }
            else
            {
                System.clearProperty(FindingKeyMode.PROP);
            }
        }
    }


    @Test
    void defaultOffMode_attachesNoKeysAtAll()
    {
        RuleExecutionResult result = runWithMode(qvalNonEmptyRule(), suppTable(), null);

        assertTrue(result.hasViolations());
        for (Violation v : result.getViolations())
        {
            assertTrue(v.getKeys().isEmpty(), "D12: off is the default, so no key is resolved");
        }
    }


    @Test
    void defineMode_attachesTheStructuralKeyToEachViolation()
    {
        RuleExecutionResult result = runWithMode(qvalNonEmptyRule(), suppTable(), "define");

        assertEquals(1, result.getViolationCount());
        Violation v = result.getViolations().get(0);
        // Row 0 is the empty QVAL. SUPPAE has no <D>SEQ, so before EC-40 this finding was
        // identified by USUBJID alone.
        assertEquals(Map.of("RDOMAIN", "AE", "IDVAR", "AESEQ", "IDVARVAL", "3", "QNAM", "AESOSP"),
                v.getKeys());
        assertEquals("SUBJ-001", v.getUsubjid());
    }


    @Test
    void valuesMapIsByteIdenticalWithKeysOffAndOn()
    {
        // D2 / D10: `values` is the parity contract (ViolationNormaliser reads it verbatim as
        // output_variables). Record-key resolution must not add, remove or reorder a single entry.
        Rule rule = qvalNonEmptyRule();

        List<Map<String, String>> off = valuesOf(runWithMode(rule, suppTable(), "off"));
        List<Map<String, String>> define = valuesOf(runWithMode(rule, suppTable(), "define"));
        List<Map<String, String>> full = valuesOf(runWithMode(rule, suppTable(), "full"));

        assertEquals(off, define);
        assertEquals(off, full);
        // Entry order matters too — the report writer projects positionally.
        assertEquals(off.stream().map(m -> new ArrayList<>(m.keySet())).toList(),
                full.stream().map(m -> new ArrayList<>(m.keySet())).toList());
        // And the key really was resolved in the non-off runs, so this is not a vacuous pass.
        assertFalse(runWithMode(rule, suppTable(), "define").getViolations().get(0).getKeys()
                .isEmpty());
    }


    @Test
    void identityFieldsAreUnchangedByKeyResolution()
    {
        // D1: USUBJID / SEQ stay exactly as they were, in every mode.
        Rule rule = qvalNonEmptyRule();

        Violation off = runWithMode(rule, suppTable(), "off").getViolations().get(0);
        Violation full = runWithMode(rule, suppTable(), "full").getViolations().get(0);

        assertEquals(off.getUsubjid(), full.getUsubjid());
        assertEquals(off.getSeq(), full.getSeq());
        assertEquals(off.getRow(), full.getRow());
    }


    @Test
    void defineKeyTierWinsOverStructuralWhenASponsorKeyIsDeclared()
    {
        MetadataProvider define = mock(MetadataProvider.class);
        lenient().when(define.getKeyVariables(anyString())).thenReturn(List.of());
        lenient().when(define.getKeyVariables("SUPPAE"))
                .thenReturn(List.of("USUBJID", "QNAM", "IDVARVAL"));

        String previous = System.getProperty(FindingKeyMode.PROP);
        try
        {
            System.setProperty(FindingKeyMode.PROP, "define");
            RuleExecutionResult result = RuleRunner.execute(qvalNonEmptyRule(), suppTable(),
                    _ -> null, "SUPPAE", null, null, define);

            Violation v = result.getViolations().get(0);
            // Sponsor order preserved, USUBJID subtracted — and NOT the structural RDOMAIN/IDVAR.
            assertEquals(List.of("QNAM", "IDVARVAL"), List.copyOf(v.getKeys().keySet()));
        }
        finally
        {
            if (previous != null)
            {
                System.setProperty(FindingKeyMode.PROP, previous);
            }
            else
            {
                System.clearProperty(FindingKeyMode.PROP);
            }
        }
    }


    @Test
    void aDatasetWithNoResolvableKeyStillReportsNormally()
    {
        // Plain AE: USUBJID + AESEQ are both carried on their own fields, no Define, no Library,
        // so nothing is left for the key — and the finding is otherwise completely unaffected.
        IDataTable table = MockTable.of().col("USUBJID", "SUBJ-001").col("AESEQ", "1")
                .col("AETERM", "").name("AE").build();
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("AETERM").operator("empty")
                .build();
        Rule rule = buildRule("CORE-KEY-2", "AETERM must be populated",
                new CheckConditionAll(List.of(leaf)), List.of("AETERM"));

        String previous = System.getProperty(FindingKeyMode.PROP);
        try
        {
            System.setProperty(FindingKeyMode.PROP, "full");
            RuleExecutionResult result = RuleRunner.execute(rule, table, _ -> null, "AE");

            assertEquals(1, result.getViolationCount());
            Violation v = result.getViolations().get(0);
            assertTrue(v.getKeys().isEmpty());
            assertEquals("SUBJ-001", v.getUsubjid());
            assertEquals("1", v.getSeq());
        }
        finally
        {
            if (previous != null)
            {
                System.setProperty(FindingKeyMode.PROP, previous);
            }
            else
            {
                System.clearProperty(FindingKeyMode.PROP);
            }
        }
    }


    private static List<Map<String, String>> valuesOf(RuleExecutionResult aResult)
    {
        return aResult.getViolations().stream().map(Violation::getValues).toList();
    }


    private static Rule buildRule(String aCoreId, String aMessage, CheckConditionAll aCheck,
            List<String> aOutputVars)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId(aCoreId);
        rule.setCore(core);
        Outcome outcome = new Outcome();
        outcome.setMessage(aMessage);
        outcome.setOutputVariables(aOutputVars);
        rule.setOutcome(outcome);
        rule.setCheck(aCheck);
        // Sensitivity.RECORD is what selects the per-row violation path in executeUnified
        // (`rowBased`). Without it the rule folds to a single dataset-level violation, which by
        // design carries neither row identity nor a record key.
        rule.setSensitivity(Sensitivity.RECORD);
        net.cumba.cdisc.core.RulePackageLoader.installNativeExpr(rule);
        return rule;
    }

}
