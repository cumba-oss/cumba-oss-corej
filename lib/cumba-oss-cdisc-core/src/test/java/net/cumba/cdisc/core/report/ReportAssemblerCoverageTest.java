package net.cumba.cdisc.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.report.ReportAssembler.Conformance;
import net.cumba.datatable.report.ValidationReport;
import org.junit.jupiter.api.Test;

/**
 * Additional coverage for {@link ReportAssembler}, hitting every {@link Conformance.Builder}
 * setter.
 *
 * <p>
 * The {@code writeTo(Path)} cases that used to live here moved to {@code ServiceReportManagerTest}:
 * writing to a file — including creating the missing parent directory — is now
 * {@link net.cumba.cdisc.core.report.ReportManager}'s convenience over a writer's stream contract,
 * not a JSON-writer method (Fix #224).
 * </p>
 */
class ReportAssemblerCoverageTest
{

    @Test
    void libraryMetadataBasis_absentOnAHealthyRun_presentWhenDegraded()
    {
        // Fix #369. The ReportAssembler comment leans on "no healthy report gains a key" — that is
        // what keeps the FROZEN v1 Conformance_Details shape unchanged for every existing consumer,
        // so it needs an assertion rather than a comment. And the present-case matters just as
        // much: without it the opt-in merely RELOCATES the silent substitution it exists to expose.
        Map<String, Object> healthy = conformanceDetails(Conformance.builder().standard("sdtmig"));
        assertFalse(healthy.containsKey("Library_Metadata_Basis"),
                "a run whose Library answered normally must not gain the key at all");

        Map<String, Object> degraded = conformanceDetails(Conformance.builder().standard("sdtmig")
                .libraryMetadataBasis("Define-XML (sponsor declarations) — …"));
        assertEquals("Define-XML (sponsor declarations) — …",
                degraded.get("Library_Metadata_Basis"));
    }


    private static Map<String, Object> conformanceDetails(Conformance.Builder builder)
    {
        Map<String, Object> export = new ReportAssembler()
                .report(ValidationReport.builder().members(List.of()).build())
                .conformance(builder.build()).sections().toExportDocument();
        @SuppressWarnings("unchecked")
        Map<String, Object> conformanceMap = (Map<String, Object>) export
                .get("Conformance_Details");
        assertNotNull(conformanceMap);
        return conformanceMap;
    }


    @Test
    void conformanceBuilder_allSetters()
    {
        Conformance c = Conformance.builder().reportGeneration("2026-05-18T10:00:00")
                .totalRuntimeSeconds(12.34).coreEngineVersion("0.5.0").issueLimitPerRule(50)
                .issueLimitPerDataset(true).standard("sdtmig").subStandard("safety").version("3-4")
                .tigUseCase("INDH").ctVersion("2024-09-26").defineXmlVersion("2.0")
                .uniiVersion("2024-01").medRtVersion("2024-02").meddraVersion("27.0")
                .whodrugVersion("2024 MAR 1").snomedVersion("2024-01-31").loincVersion("2.78")
                .build();
        assertNotNull(c);

        ReportAssembler writer = new ReportAssembler()
                .report(ValidationReport.builder().members(List.of()).build()).conformance(c);

        Map<String, Object> export = writer.sections().toExportDocument();
        @SuppressWarnings("unchecked")
        Map<String, Object> conformanceMap = (Map<String, Object>) export
                .get("Conformance_Details");
        assertNotNull(conformanceMap);
        assertEquals("2026-05-18T10:00:00", conformanceMap.get("Report_Generation"));
        assertEquals("SDTMIG", conformanceMap.get("Standard"));
        assertEquals("V3.4", conformanceMap.get("Version"));
        assertEquals("safety", conformanceMap.get("Sub_Standard"));
        assertEquals("INDH", conformanceMap.get("TIG_Use_Case"));
        assertEquals("2024-09-26", conformanceMap.get("CT_Version"));
        assertEquals("2.0", conformanceMap.get("Define_XML_Version"));
        assertEquals("2024-01", conformanceMap.get("UNII_Version"));
        assertEquals("2024-02", conformanceMap.get("Med_RT_Version"));
        assertEquals("27.0", conformanceMap.get("MedDRA_Version"));
        assertEquals("2024 MAR 1", conformanceMap.get("WHODRUG_Version"));
        assertEquals("2024-01-31", conformanceMap.get("SNOMED_Version"));
        assertEquals("2.78", conformanceMap.get("LOINC_Version"));
        assertEquals("0.5.0", conformanceMap.get("CORE_Engine_Version"));
        // totalRuntimeSeconds formats to "%.2f seconds"
        assertEquals("12.34 seconds", conformanceMap.get("Total_Runtime"));
        // issueLimitPerRule prints the toString of the value
        assertEquals("50", conformanceMap.get("Issue_Limit_Per_Rule"));
        assertEquals("True", conformanceMap.get("Issue_Limit_Per_Dataset"));
    }


    @Test
    void conformanceBuilder_defaults()
    {
        // Minimum: build with no setters. toExport should still produce a Conformance_Details map.
        Conformance c = Conformance.builder().build();
        ReportAssembler writer = new ReportAssembler()
                .report(ValidationReport.builder().members(List.of()).build()).conformance(c);

        Map<String, Object> export = writer.sections().toExportDocument();
        @SuppressWarnings("unchecked")
        Map<String, Object> conformanceMap = (Map<String, Object>) export
                .get("Conformance_Details");
        assertNotNull(conformanceMap);
        // Report_Generation defaults to "now"-time when null.
        assertNotNull(conformanceMap.get("Report_Generation"));
        // issueLimitPerRule defaults to "None" when null.
        assertEquals("None", conformanceMap.get("Issue_Limit_Per_Rule"));
        assertEquals("None", conformanceMap.get("Issue_Limit_Per_Dataset"));
    }

}
