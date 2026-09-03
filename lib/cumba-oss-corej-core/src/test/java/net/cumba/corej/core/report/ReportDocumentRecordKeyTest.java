package net.cumba.corej.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.datatable.report.FindingKind;
import net.cumba.datatable.report.RowFindingSlab;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.report.ValidationFinding;
import net.cumba.datatable.report.ValidationFindingLocation;
import net.cumba.datatable.report.ValidationReport;
import net.cumba.datatable.report.ValidationReportMember;
import org.junit.jupiter.api.Test;

/**
 * EC-40 coverage for {@link ReportAssembler}: the v2 {@code Findings} entries carry the record key
 * on their location and on each row, and — decision D6, the hard gate — the v1
 * {@code Issue_Details} output is byte-identical whether or not a key is present.
 */
class ReportDocumentRecordKeyTest
{

    private static final String CORE_ID = "CORE-000252";

    private static final List<String> KEY_NAMES = List.of("RDOMAIN", "IDVAR", "IDVARVAL", "QNAM");

    @Test
    void v2Finding_carriesKeyVariablesKeySourceAndPerRowKeys()
    {
        Map<String, Object> export = new ReportAssembler().report(report(true)).sections()
                .toCombinedExportDocument();
        Map<String, Object> finding = firstFinding(export);

        @SuppressWarnings("unchecked")
        Map<String, Object> location = (Map<String, Object>) finding.get("location");
        assertEquals("SUPPAE", location.get("dataset"));
        assertEquals(List.of("QVAL"), location.get("variables"));
        assertEquals(KEY_NAMES, location.get("keyVariables"));
        assertEquals("STRUCTURAL", location.get("keySource"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) finding.get("rows");
        assertEquals(2, rows.size());
        assertEquals(Map.of("RDOMAIN", "AE", "IDVAR", "AESEQ", "IDVARVAL", "3", "QNAM", "AESOSP"),
                rows.get(0).get("keys"));
        assertEquals(Map.of("RDOMAIN", "AE", "IDVAR", "AESEQ", "IDVARVAL", "7", "QNAM", "AESOSP"),
                rows.get(1).get("keys"));
        // USUBJID / SEQ are untouched alongside the key (D1).
        assertEquals("SUBJ-001", rows.get(0).get("USUBJID"));
        assertEquals(List.of("Y"), rows.get(0).get("values"));
    }


    @Test
    void v2Finding_omitsTheKeyFieldsEntirelyWhenNoKeyResolved()
    {
        // D12: under the default corej.findingKeys=off nothing resolves, and the v2 object must
        // then be byte-identical to a pre-EC-40 build — absent fields, not empty ones.
        Map<String, Object> finding = firstFinding(
                new ReportAssembler().report(report(false)).sections().toCombinedExportDocument());

        @SuppressWarnings("unchecked")
        Map<String, Object> location = (Map<String, Object>) finding.get("location");
        assertFalse(location.containsKey("keyVariables"));
        assertFalse(location.containsKey("keySource"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) finding.get("rows");
        assertFalse(rows.get(0).containsKey("keys"));
        assertEquals(List.of("row", "USUBJID", "SEQ", "values"), List.copyOf(rows.get(0).keySet()),
                "row object key order is unchanged");
    }


    @Test
    void v1IssueDetailsAreByteIdenticalWithAndWithoutARecordKey()
    {
        // D6: v1 is the Python-compatible surface. A record key must not reach it at all —
        // not as a column, not as a variable, not as a reordering.
        List<Map<String, Object>> withKey = new ReportAssembler().report(report(true)).sections()
                .issueDetails();
        List<Map<String, Object>> withoutKey = new ReportAssembler().report(report(false))
                .sections().issueDetails();

        assertEquals(withoutKey, withKey);
        // Explicitly: the fixed v1 column set, in order, with no key field anywhere.
        // ⚠ `domain` is NOT a record-key leak — it is the long-standing additive Java extension the
        // v1 JSON has always carried on every Issue_Details row. It is asserted here because the
        // sections must reproduce the JSON row exactly; a sections->document mapping built from a
        // list that omitted it would silently drop the field.
        assertEquals(List.of("core_id", "message", "executability", "dataset", "USUBJID", "row",
                "SEQ", "variables", "values", "domain"), List.copyOf(withKey.get(0).keySet()));
        for (Map<String, Object> row : withKey)
        {
            assertFalse(row.containsKey("keys"));
            assertFalse(row.containsKey("keyVariables"));
            @SuppressWarnings("unchecked")
            List<String> variables = (List<String>) row.get("variables");
            assertTrue(KEY_NAMES.stream().noneMatch(variables::contains),
                    "no key column may leak into the v1 variables list");
        }
    }


    private static Map<String, Object> firstFinding(Map<String, Object> aExport)
    {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) aExport.get("Findings");
        return findings.getFirst();
    }


    /**
     * A SUPPAE finding flagging {@code QVAL} on two rows, optionally carrying the STRUCTURAL record
     * key. Both variants are otherwise identical, so any v1 difference is the key's fault.
     */
    private static ValidationReport report(boolean aWithKey)
    {
        List<String> names = List.of("USUBJID", "SEQ", "QVAL");
        RowFindingSlab slab = RowFindingSlab.builder(names.size()).addRow(6, new String[]
        {
                "SUBJ-001", "", "Y"
        }).addRow(9, new String[]
        {
                "SUBJ-002", "", "N"
        }).build();

        ValidationFindingLocation.ValidationFindingLocationBuilder location = ValidationFindingLocation
                .builder().dataset("SUPPAE").variableNames(List.of("QVAL"));
        RowFindingSlab keySlab = RowFindingSlab.EMPTY;
        if (aWithKey)
        {
            location.keyVariableNames(KEY_NAMES).keySource("STRUCTURAL");
            keySlab = RowFindingSlab.builder(KEY_NAMES.size()).addRow(6, new String[]
            {
                    "AE", "AESEQ", "3", "AESOSP"
            }).addRow(9, new String[]
            {
                    "AE", "AESEQ", "7", "AESOSP"
            }).build();
        }

        ValidationFinding finding = ValidationFinding.builder().source("cumba.core").ruleId(CORE_ID)
                .severity(Severity.ERROR).kind(FindingKind.RULE_VIOLATION)
                .executability("fully executable").message("QVAL must be populated")
                .variableNames(names).location(location.build()).rows(slab).keyRows(keySlab)
                .build();

        ValidationReportMember member = ValidationReportMember.builder().domain("SUPPAE")
                .fileName("suppae.xpt").findings(List.of(finding)).build();
        return ValidationReport.builder().members(List.of(member)).build();
    }

}
