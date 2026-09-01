package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.expr.eval.Domain;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.VariableUniverse;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Phases 4 and 5 of {@code PLAN-leaf-scope-domain-inference.md}: {@code RuleRunner} dispatches on
 * the inferred evaluation domain, the §3.5 mixes load and evaluate, the §3.3 domain × sensitivity
 * matrix holds (ruling 5: {@code Dataset} collapses uniformly to the first firing point), and the
 * §3.7 {@code Variable_Universe} semantics — the two load errors, the two universes, the
 * {@code var_exists(varname())} discriminator — are pinned.
 */
class LeafScopeDispatchTest
{

    private static Rule load(String id, String check, String extra) throws Exception
    {
        String body = "{\"Core\":{\"Id\":\"" + id + "\"}," + extra + "\"Scope\":{\"Domains\":{"
                + "\"Include\":[\"EX\"]}},\"Check\":{\"expression\":\""
                + check.replace("\"", "\\\"") + "\"},\"Outcome\":{\"Message\":\"m\"}}";
        return RulePackageLoader.loadFromString("{\"rules\":{\"" + id + "\":" + body + "}}")
                .getRules().get(id);
    }


    private static IDataTable ex()
    {
        return MockTable.of().name("EX").col("EXDOSE", "1", "0", "2").col("EXTRT", "A", "", "C")
                .colMeta("EXDOSE", "Dose", 8, "").colMeta("EXTRT", "Treatment", 40, "").build();
    }


    private static DatasetResolver with(IDataTable... tables)
    {
        return name ->
        {
            for (IDataTable t : tables)
            {
                if (t.getMetaData().getName().equals(name))
                {
                    return t;
                }
            }
            return null;
        };
    }

    // ---- §3.5 — the mixes ----------------------------------------------------------------


    @Test
    void studyFactGuardBesideRowReadsIsRowDomainAndFiresPerRow() throws Exception
    {
        Rule r = load("MIX1", "ds_exists(\"EX\") and EXDOSE > 0", "");
        assertNull(r.getLoadError());
        assertEquals(Domain.ROW, r.getEvaluationDomain());
        RuleExecutionResult res = RuleRunner.execute(r, ex(), with(ex()));
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus());
        assertEquals(List.of(0L, 2L), res.getViolations().stream().map(Violation::getRow).toList());
        // The guard is a study fact: EX absent from the submission ⇒ nothing fires.
        assertEquals(0, RuleRunner.execute(r, ex(), _ -> null).getViolationCount());
    }


    @Test
    void datasetFactPlusVariableMetadataPlusCellIsCellDomain() throws Exception
    {
        Rule r = load("MIX2",
                "record_count() > 0 and var_label(varname(), \"DATA\") != \"\" and value() != \"\"",
                "");
        assertNull(r.getLoadError());
        assertEquals(Domain.CELL, r.getEvaluationDomain());
        RuleExecutionResult res = RuleRunner.execute(r, ex(), with(ex()));
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus());
        // two labelled columns × the non-empty cells: EXDOSE 3 rows, EXTRT rows 0 and 2
        assertEquals(5, res.getViolationCount());
    }


    @Test
    void datasetAndColumnPresenceInOneRuleIsOneDatasetVerdict() throws Exception
    {
        Rule r = load("MIX3", "ds_exists(\"SUPPAE\") and var_exists(\"EX.EXDOSE\")", "");
        assertNull(r.getLoadError());
        assertEquals(Domain.DATASET, r.getEvaluationDomain());
        IDataTable suppae = MockTable.of().name("SUPPAE").col("QNAM", "X").build();
        RuleExecutionResult both = RuleRunner.execute(r, ex(), with(ex(), suppae));
        assertEquals(1, both.getViolationCount(), "both facts hold ⇒ one dataset-level finding");
        assertEquals(0L, both.getViolations().getFirst().getRow());
        assertEquals(0, RuleRunner.execute(r, ex(), with(ex())).getViolationCount(),
                "SUPPAE absent ⇒ nothing fires");
    }

    // ---- §3.3 — domain × sensitivity -----------------------------------------------------


    @Test
    void variableDomainEmitsPerVariableUnderRecordAndFirstVariableUnderDataset() throws Exception
    {
        String check = "var_label(\"DATA\") != \"\"";
        Rule record = load("V-REC", check, "\"Sensitivity\":\"Record\",");
        assertEquals(Domain.VARIABLE, record.getEvaluationDomain());
        RuleExecutionResult perVariable = RuleRunner.execute(record, ex(), with(ex()));
        assertEquals(2, perVariable.getViolationCount(), "one finding per labelled variable");
        assertEquals(List.of(0L, 1L),
                perVariable.getViolations().stream().map(Violation::getRow).toList(),
                "violationRow is the column index on the per-variable path");

        Rule dataset = load("V-DS", check, "\"Sensitivity\":\"Dataset\",");
        RuleExecutionResult collapsed = RuleRunner.execute(dataset, ex(), with(ex()));
        assertEquals(1, collapsed.getViolationCount(), "Dataset collapses to the first variable");
        assertEquals(0L, collapsed.getViolations().getFirst().getRow());
    }


    @Test
    void rowDomainEmitsPerRowUnderRecordAndFirstRowUnderDataset() throws Exception
    {
        Rule record = load("R-REC", "EXDOSE >= 1", "\"Sensitivity\":\"Record\",");
        assertEquals(Domain.ROW, record.getEvaluationDomain());
        assertEquals(2, RuleRunner.execute(record, ex(), with(ex())).getViolationCount());

        Rule dataset = load("R-DS", "EXDOSE >= 1", "\"Sensitivity\":\"Dataset\",");
        RuleExecutionResult collapsed = RuleRunner.execute(dataset, ex(), with(ex()));
        assertEquals(1, collapsed.getViolationCount());
        assertEquals(0L, collapsed.getViolations().getFirst().getRow(), "the first firing row");
    }


    @Test
    void cellDomainCollapsesUniformlyUnderDatasetSensitivity() throws Exception
    {
        // Ruling 5 (2026-08-13): the {VAR,ROW} × Dataset cell — corpus-empty — is DEFINED as the
        // first-firing-point collapse, like every other domain.
        String check = "var_label(varname(), \"DATA\") != \"\" and value() != \"\"";
        Rule record = load("C-REC", check, "\"Sensitivity\":\"Record\",");
        assertEquals(Domain.CELL, record.getEvaluationDomain());
        assertEquals(5, RuleRunner.execute(record, ex(), with(ex())).getViolationCount());

        Rule dataset = load("C-DS", check, "\"Sensitivity\":\"Dataset\",");
        RuleExecutionResult collapsed = RuleRunner.execute(dataset, ex(), with(ex()));
        assertEquals(1, collapsed.getViolationCount(), "one (variable, row) point");
        assertEquals("EXDOSE",
                collapsed.getViolations().getFirst().getValues().get("variable_name"));
        assertEquals(0L, collapsed.getViolations().getFirst().getRow());
    }

    // ---- §3.7 — Variable_Universe -------------------------------------------------------


    @Test
    void unrecognisedUniverseIsALoadError() throws Exception
    {
        Rule r = load("U-BAD", "var_label(\"DATA\") != \"\"", "\"Variable_Universe\":\"Both\",");
        assertNotNull(r.getLoadError());
        assertTrue(r.getLoadError().contains("Invalid Variable_Universe 'Both'"), r.getLoadError());
        assertTrue(r.getLoadError().contains("Data, Define"), r.getLoadError());
    }


    @Test
    void defineUniverseOnARuleWithoutAVariableCursorIsALoadError() throws Exception
    {
        Rule r = load("U-NOVAR", "EXDOSE > 0", "\"Variable_Universe\":\"Define\",");
        assertNotNull(r.getLoadError());
        assertTrue(r.getLoadError().contains("no variable cursor"), r.getLoadError());
        assertTrue(r.getLoadError().contains("{ROW}"), r.getLoadError());
        // The same Check with a cursor is fine.
        Rule ok = load("U-VAR", "var_label(\"DATA\") != \"\"", "\"Variable_Universe\":\"Define\",");
        assertNull(ok.getLoadError());
        assertEquals(VariableUniverse.DEFINE, ok.getVariableUniverse());
    }


    @Test
    void theDataUniverseIteratesColumnsAndTheDefineUniverseIteratesItemDefs() throws Exception
    {
        // EX carries EXDOSE and EXTRT; the Define declares EXDOSE and EXDOSU (no EXTRT).
        MetadataProvider define = new StubMetadataProvider()
                .variable("EX", Map.of("name", "EXDOSE", "label", "Dose"))
                .variable("EX", Map.of("name", "EXDOSU", "label", "Dose Units"));
        String check = "not var_exists(varname())";
        // Data universe: every cursor variable is a column ⇒ nothing fires.
        Rule data = load("U-DATA", check, "\"Sensitivity\":\"Record\",");
        assertEquals(Domain.VARIABLE, data.getEvaluationDomain());
        assertEquals(0, RuleRunner.execute(data, ex(), with(ex()), "EX", null, null, define)
                .getViolationCount());
        // Define universe: EXDOSU is declared but absent from the data ⇒ the discriminator fires
        // for it and only it.
        Rule def = load("U-DEF", check,
                "\"Sensitivity\":\"Record\",\"Variable_Universe\":\"Define\",");
        RuleExecutionResult res = RuleRunner.execute(def, ex(), with(ex()), "EX", null, null,
                define);
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus());
        assertEquals(1, res.getViolationCount());
        assertEquals("EXDOSU", res.getViolations().getFirst().getValues().get("variable_name"));
        // No Define-XML provider ⇒ the Define universe is unreachable ⇒ SKIPPED.
        Rule noProvider = load("U-DEF2", "var_label(\"DEFINE\") != \"\"",
                "\"Variable_Universe\":\"Define\",");
        assertEquals(RuleExecutionStatus.SKIPPED,
                RuleRunner.execute(noProvider, ex(), with(ex())).getStatus());
        // ... even when no leaf reads the DEFINE level (the discriminator reads DATA only): the
        // universe itself needs the provider, and must never fall back to the data columns
        // (review finding 4, 2026-08-22).
        RuleExecutionResult noDef = RuleRunner.execute(def, ex(), with(ex()), "EX", null, null,
                (MetadataProvider) null);
        assertEquals(RuleExecutionStatus.SKIPPED, noDef.getStatus(),
                () -> noDef.getStatusMessage() + " / " + noDef.getViolations());
    }


    @Test
    void defineUniverseOnAPerVariableRowCheckIsALoadError() throws Exception
    {
        // {VAR,ROW} iterates the data columns' cells; an ItemDef absent from the data has no
        // cells, so the universe is rejected there rather than silently ignored (review finding
        // 5, 2026-08-22).
        Rule r = load("U-CELL", "var_label(\"DATA\") != \"\" and value() == \"\"",
                "\"Variable_Universe\":\"Define\",");
        assertEquals(Domain.CELL, r.getEvaluationDomain());
        assertNotNull(r.getLoadError());
        assertTrue(r.getLoadError().contains("also carries the row cursor"), r.getLoadError());
    }
}
