package net.cumba.corej.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.report.ReportAssembler.Conformance;
import net.cumba.datatable.report.ValidationReport;
import org.junit.jupiter.api.Test;

/**
 * Additional coverage for {@link ReportAssembler}, hitting every {@link Conformance.Builder}
 * setter.
 *
 * <p>
 * The {@code writeTo(Path)} cases that used to live here moved to {@code ServiceReportManagerTest}:
 * writing to a file — including creating the missing parent directory — is now
 * {@link net.cumba.corej.core.report.ReportManager}'s convenience over a writer's stream contract,
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


    @Test
    void ctDeclarationMismatch_absentWhenDeclarationAndRunAgree()
    {
        // Define-ct plan §4.2 — same contract as Library_Metadata_Basis: no key on a run with no
        // divergence to report, so the frozen v1 Conformance_Details shape is unchanged for every
        // existing consumer; the note appears only when the define and the run disagree.
        Map<String, Object> agreeing = conformanceDetails(Conformance.builder().standard("sdtmig"));
        assertFalse(agreeing.containsKey("CT_Declaration_Mismatch"),
                "a run with no CT divergence must not gain the key at all");

        Map<String, Object> divergent = conformanceDetails(Conformance.builder().standard("sdtmig")
                .ctDeclarationMismatch("define declares sdtmct-2023-12-15; run used none"));
        assertEquals("define declares sdtmct-2023-12-15; run used none",
                divergent.get("CT_Declaration_Mismatch"));

        // The accessor is the P7 surface hook (CLI stderr / REST) — pin it like dictionaryBasis().
        assertEquals("x",
                Conformance.builder().ctDeclarationMismatch("x").build().ctDeclarationMismatch());
        assertNull(Conformance.builder().build().ctDeclarationMismatch());
    }


    @Test
    void dictionaryBasis_absentOnAHealthyRun_presentWhenDegraded()
    {
        // D13 item 1 — same contract as Library_Metadata_Basis: no healthy report gains a key
        // (the frozen v1 shape holds), and a degraded run's report says so itself rather than
        // leaving the truth to the log.
        Map<String, Object> healthy = conformanceDetails(Conformance.builder().standard("sdtmig"));
        assertFalse(healthy.containsKey("Dictionary_Basis"),
                "a run whose every dictionary rule was answerable must not gain the key");
        assertFalse(healthy.containsKey("Neoplasm_Version"),
                "an unloaded neoplasm dictionary emits no version key, like its six siblings");

        Map<String, Object> degraded = conformanceDetails(Conformance.builder().standard("sdtmig")
                .dictionaryBasis("external dictionaries degraded: 0 of 98 dictionary rules …"));
        assertEquals("external dictionaries degraded: 0 of 98 dictionary rules …",
                degraded.get("Dictionary_Basis"));

        // The accessor is the CLI's (Phase 6b) stderr hook — pin that it returns the same line.
        assertEquals("external dictionaries degraded: 0 of 98 dictionary rules …",
                Conformance.builder()
                        .dictionaryBasis(
                                "external dictionaries degraded: 0 of 98 dictionary rules …")
                        .build().dictionaryBasis());
        assertNull(Conformance.builder().build().dictionaryBasis());
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
                .tigUseCase("INDH").ctVersion("2024-09-26")
                .ctDeclarationMismatch("define declares sdtmct-2023-12-15; run used none")
                .defineXmlVersion("2.0").uniiVersion("2024-01").medRtVersion("2024-02")
                .meddraVersion("27.0").whodrugVersion("2024 MAR 1").snomedVersion("2024-01-31")
                .loincVersion("2.78").neoplasmVersion("2026-03-27")
                .dictionaryBasis("external dictionaries degraded: …").build();
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
        assertEquals("define declares sdtmct-2023-12-15; run used none",
                conformanceMap.get("CT_Declaration_Mismatch"));
        assertEquals("2.0", conformanceMap.get("Define_XML_Version"));
        assertEquals("2024-01", conformanceMap.get("UNII_Version"));
        assertEquals("2024-02", conformanceMap.get("Med_RT_Version"));
        assertEquals("27.0", conformanceMap.get("MedDRA_Version"));
        assertEquals("2024 MAR 1", conformanceMap.get("WHODRUG_Version"));
        assertEquals("2024-01-31", conformanceMap.get("SNOMED_Version"));
        assertEquals("2.78", conformanceMap.get("LOINC_Version"));
        // §2.5 — seven dictionary types, seven fields: neoplasm no longer lacks one.
        assertEquals("2026-03-27", conformanceMap.get("Neoplasm_Version"));
        assertEquals("external dictionaries degraded: …", conformanceMap.get("Dictionary_Basis"));
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
