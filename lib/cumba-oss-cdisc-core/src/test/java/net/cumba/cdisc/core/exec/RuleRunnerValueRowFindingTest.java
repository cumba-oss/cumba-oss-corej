package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Outcome pins for the per-(variable, row) value-check path — {@code evaluateVariableValueNative} →
 * {@code addVariableRowViolations} — and the row-path truncation accounting. These decide which
 * (column, row) a value finding points at, which cell value / metadata it reports, whether the
 * provider gates SKIP (instead of silently passing) and whether the TRUE violation count survives
 * the per-rule cap. A mutant in any of them ships a plausible-looking report that points at the
 * wrong cell, silently drops fields, or under-counts the defects.
 */
class RuleRunnerValueRowFindingTest
{

    private static Rule load(String ruleJson) throws Exception
    {
        RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"R1\":" + ruleJson + "}}");
        Rule rule = pkg.getRules().get("R1");
        assertNull(rule.getLoadError(), "rule must load cleanly: " + rule.getLoadError());
        return rule;
    }


    /** DESC (deliberately at COLUMN INDEX 0) with two long cells; USUBJID for delegation. */
    private static IDataTable adsl()
    {
        return MockTable.of().name("ADSL").col("DESC", "longvalue1", "ok", "longvalue2")
                .colMeta("DESC", "Description", 0, null)
                .col("USUBJID", "S1-001", "S1-002", "S1-003").build();
    }


    /**
     * The authored projection of a value finding resolves each entry from its own source:
     * variable_name / variable_label from the column, variable_value from the CELL AT THE FIRING
     * ROW (the column sits at index 0 — the boundary a {@code colIdx > 0} mutant breaks),
     * variable_data_type from the per-variable metadata, and any other name (USUBJID) through the
     * standard row-level resolution. Two firing rows → two findings at exactly those rows.
     */
    @Test
    void authoredProjectionResolvesEachEntryFromItsSource() throws Exception
    {
        Rule rule = load("{\"Core\":{\"Id\":\"R1\"},\"Sensitivity\":\"Record\","
                + "\"Check\":{\"all\":["
                + "{\"name\":\"variable_name\",\"operator\":\"matches_regex\","
                + "\"value\":\"^DESC$\"},"
                + "{\"name\":\"variable_value\",\"operator\":\"longer_than\",\"value\":5}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"variable_name\","
                + "\"variable_label\",\"variable_value\",\"variable_data_type\",\"USUBJID\"]}}");

        RuleExecutionResult r = RuleRunner.execute(rule, adsl(), _ -> null, "ADSL", null, null,
                null);

        assertEquals(2, r.getViolations().size(), "rows 0 and 2 exceed the length");
        Violation first = r.getViolations().get(0);
        assertEquals(0L, first.getRow());
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("variable_name", "DESC");
        expected.put("variable_label", "Description");
        expected.put("variable_value", "longvalue1");
        expected.put("variable_data_type", "Char");
        expected.put("USUBJID", "S1-001");
        assertEquals(expected, first.getValues());

        Violation second = r.getViolations().get(1);
        assertEquals(2L, second.getRow());
        assertEquals("longvalue2", second.getValues().get("variable_value"));
        assertEquals("S1-003", second.getValues().get("USUBJID"));
    }


    /**
     * The no-Output_Variables default branch of a value finding, reached by excluding every derived
     * entry (varname()/value() always derive {@code variable_name}/{@code
     * variable_value}, so E-2 exclusions are the only loader-constructible route): the finding
     * still carries the column's label, and the exclusions themselves are honoured. The excluded
     * names' own default-branch emission is unobservable by construction on this route — see the
     * survivor notes — but the label gate and the E-2 removal are pinned exactly.
     */
    @Test
    void defaultProjectionCarriesLabelAndHonoursExclusions() throws Exception
    {
        Rule present = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Check":{"expression":"varname() == \\"DESC\\" and not empty(value()) \
                and value() != \\"ok\\""},
                 "Outcome":{"Message":"m","Output_Variables":
                   ["!variable_name","!variable_value"]}}""");

        RuleExecutionResult r = RuleRunner.execute(present, adsl(), _ -> null, "ADSL", null, null,
                null);
        assertEquals(2, r.getViolations().size());
        assertEquals(0L, r.getViolations().get(0).getRow());
        assertEquals(2L, r.getViolations().get(1).getRow());
        assertEquals(Map.of("variable_label", "Description"), r.getViolations().get(0).getValues(),
                "the default projection keeps the label and honours the exclusions");
    }


    /**
     * The per-rule findings cap truncates what is MATERIALISED but never what is COUNTED: three
     * firing (variable, row) pairs under a cap of 1 report one stored violation and a true count of
     * 3. Dropping the skipped-row accounting under-reports the defect burden of the dataset.
     */
    @Test
    void valuePathCapKeepsTrueViolationCount() throws Exception
    {
        Rule rule = load("{\"Core\":{\"Id\":\"R1\"},\"Sensitivity\":\"Record\","
                + "\"Check\":{\"all\":["
                + "{\"name\":\"variable_name\",\"operator\":\"matches_regex\","
                + "\"value\":\"^DESC$\"},"
                + "{\"name\":\"variable_value\",\"operator\":\"longer_than\",\"value\":1}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"variable_value\"]}}");
        IDataTable t = MockTable.of().name("ADSL").col("DESC", "aaa", "bbb", "ccc").build();

        RuleExecutionResult r = RuleRunner.execute(rule, t, _ -> null, "ADSL", null, null, null, 1,
                null, null, null, Set.of(), Set.of(), Severity.WARNING);

        assertEquals(1, r.getViolations().size(), "the cap materialises one");
        assertEquals(3, r.getViolationCount(), "the TRUE count survives the cap");
        assertTrue(r.isTruncated());
    }


    /** The plain row path (executeUnified) keeps the same cap-vs-count contract. */
    @Test
    void rowPathCapKeepsTrueViolationCount() throws Exception
    {
        Rule rule = load("{\"Core\":{\"Id\":\"R1\"},\"Sensitivity\":\"Record\","
                + "\"Check\":{\"all\":[{\"name\":\"AESEV\",\"operator\":\"equal_to\","
                + "\"value\":\"BAD\",\"value_is_literal\":true}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AESEV\"]}}");
        IDataTable t = MockTable.of().name("AE").col("AESEV", "BAD", "BAD", "BAD").build();

        RuleExecutionResult r = RuleRunner.execute(rule, t, _ -> null, "AE", null, null, null, 1,
                null, null, null, Set.of(), Set.of(), Severity.WARNING);

        assertEquals(1, r.getViolations().size());
        assertEquals(3, r.getViolationCount());
        assertTrue(r.isTruncated());
    }


    /**
     * Sensitivity picks the row path's shape: RECORD emits one finding per firing row WITH the row
     * identity (USUBJID/AESEQ); DATASET collapses to a single finding at the first firing row
     * WITHOUT row identity. Negating the row-basedness either floods a dataset verdict or hides all
     * but one record defect.
     */
    @Test
    void recordEmitsPerRowWithIdentityDatasetCollapsesWithout() throws Exception
    {
        String check = "\"Check\":{\"all\":[{\"name\":\"AESEV\",\"operator\":\"equal_to\","
                + "\"value\":\"BAD\",\"value_is_literal\":true}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AESEV\"]}";
        Rule record = load("{\"Core\":{\"Id\":\"R1\"},\"Sensitivity\":\"Record\"," + check + "}");
        Rule dataset = load("{\"Core\":{\"Id\":\"R1\"},\"Sensitivity\":\"Dataset\"," + check + "}");
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S1", "S2", "S3")
                .col("AESEQ", "1", "2", "3").col("AESEV", "OK", "BAD", "BAD").build();

        RuleExecutionResult rec = RuleRunner.execute(record, ae, _ -> null, "AE", null, null, null);
        assertEquals(2, rec.getViolations().size(), "one finding per firing record");
        assertEquals(1L, rec.getViolations().get(0).getRow());
        assertEquals("S2", rec.getViolations().get(0).getUsubjid());
        assertEquals("2", rec.getViolations().get(0).getSeq());
        assertEquals(2L, rec.getViolations().get(1).getRow());

        RuleExecutionResult ds = RuleRunner.execute(dataset, ae, _ -> null, "AE", null, null, null);
        assertEquals(1, ds.getViolations().size(), "a dataset verdict collapses to one");
        assertEquals(1L, ds.getViolations().get(0).getRow(), "anchored at the first firing row");
        assertNull(ds.getViolations().get(0).getUsubjid(),
                "a dataset verdict carries no per-row identity");
    }


    /**
     * The provider gates of the value path SKIP — with the exact reason — instead of silently
     * evaluating against nothing: an expression Check reading DEFINE-level metadata without a
     * Define-XML, and one reading LIBRARY-level metadata without a library. A degraded
     * (present-but-unanswerable) library states that it could not be consulted, which is a
     * different fact than having none — the sponsor reads that sentence. Expression-form checks on
     * purpose: the operator-form {@code define_*}/{@code library_*} operands are intercepted by the
     * earlier operand gate, so only accessor expressions reach these gates.
     */
    @Test
    void valuePathSkipsWithExactReasonWhenProviderMissing() throws Exception
    {
        // upper(...) on both sides keeps the loader from lowering the expression to operand
        // leaves, so the rule reaches the VALUE-PATH gates rather than the earlier operand gate.
        Rule needsDefine = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Check":{"expression":"varname() == \\"AGE\\" and \
                upper(value()) != upper(var_role(\\"DEFINE\\"))"},
                 "Outcome":{"Message":"m","Output_Variables":["variable_name"]}}""");
        IDataTable dm = MockTable.of().name("DM").col("AGE", "56").build();

        RuleExecutionResult noDefine = RuleRunner.execute(needsDefine, dm, _ -> null, "DM", null,
                null, null);
        assertTrue(noDefine.isSkipped());
        assertEquals("Rule skipped — no Define-XML metadata available",
                noDefine.getStatusMessage());
        assertTrue(noDefine.getViolations().isEmpty());

        Rule needsLibrary = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Check":{"expression":"varname() == \\"AGE\\" and \
                upper(value()) != upper(var_role(\\"LIBRARY\\"))"},
                 "Outcome":{"Message":"m","Output_Variables":["variable_name"]}}""");

        RuleExecutionResult noLibrary = RuleRunner.execute(needsLibrary, dm, _ -> null, "DM", null,
                null, null);
        assertTrue(noLibrary.isSkipped());
        assertEquals("Rule skipped — no Library metadata available", noLibrary.getStatusMessage());

        MetadataProvider degraded = org.mockito.Mockito.mock(MetadataProvider.class);
        org.mockito.Mockito.when(degraded.isLibraryUnavailable()).thenReturn(true);
        RuleExecutionResult degradedRun = RuleRunner.execute(needsLibrary, dm, _ -> null, "DM",
                degraded, null, null);
        assertTrue(degradedRun.isSkipped());
        assertEquals(
                "Rule skipped — the CDISC Library could not be consulted for this run, and "
                        + "LIBRARY-level metadata may not be answered from a non-library source",
                degradedRun.getStatusMessage());
    }


    /**
     * A Check that reads BOTH provider levels enriches the finding from BOTH: the projected
     * library_variable_role / define_variable_role come from the per-variable metadata the
     * providers served. A mutant nulling either provider keying strips that half from every value
     * finding.
     */
    @Test
    void valueFindingIsEnrichedFromBothProviderLevels() throws Exception
    {
        Rule rule = load("{\"Core\":{\"Id\":\"R1\"},\"Sensitivity\":\"Record\","
                + "\"Check\":{\"all\":["
                + "{\"name\":\"variable_name\",\"operator\":\"matches_regex\","
                + "\"value\":\"^AGE$\"},"
                + "{\"name\":\"variable_value\",\"operator\":\"not_equal_to\","
                + "\"value\":\"library_variable_role\"},"
                + "{\"name\":\"variable_value\",\"operator\":\"not_equal_to\","
                + "\"value\":\"define_variable_role\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"variable_name\","
                + "\"variable_value\",\"library_variable_role\",\"define_variable_role\"]}}");

        MetadataProvider library = new StubMetadataProvider().variable("DM",
                Map.of("name", "AGE", "role", "Identifier"));
        MetadataProvider define = new StubMetadataProvider().variable("DM",
                Map.of("name", "AGE", "role", "Qualifier"));
        IDataTable dm = MockTable.of().name("DM").col("AGE", "56").build();

        RuleExecutionResult r = RuleRunner.execute(rule, dm, _ -> null, "DM", library, null,
                define);

        assertFalse(r.isSkipped(), () -> String.valueOf(r.getStatusMessage()));
        assertEquals(1, r.getViolations().size());
        Map<String, String> values = r.getViolations().get(0).getValues();
        assertEquals("Identifier", values.get("library_variable_role"),
                "the LIBRARY half of the enrichment");
        assertEquals("Qualifier", values.get("define_variable_role"),
                "the DEFINE half of the enrichment");
        assertEquals("56", values.get("variable_value"));
    }
}
