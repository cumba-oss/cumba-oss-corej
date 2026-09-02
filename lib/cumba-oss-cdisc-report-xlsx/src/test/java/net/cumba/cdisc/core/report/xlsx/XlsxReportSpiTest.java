package net.cumba.cdisc.core.report.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.report.ReportFormat;
import net.cumba.cdisc.core.report.ReportSections;
import net.cumba.cdisc.core.report.ServiceReportManager;
import net.cumba.datatable.io.Property;
import net.cumba.datatable.io.PropertyType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * The SPI side of the xlsx module: registration, the declared {@code maxRowsPerSheet} property, and
 * two acceptance gates.
 *
 * <ul>
 * <li><b>R4</b> — this classpath carries the xlsx module and <em>not</em> the json module, so
 * {@code xlsx} works and {@code json} is unavailable. That is what makes the split real rather than
 * a package rename.</li>
 * <li><b>R5</b> — {@link StubCsvReportWriterSupplier}, contributed from a test source root with no
 * engine edit whatsoever, shows up in the registry alongside {@code xlsx}.</li>
 * </ul>
 */
class XlsxReportSpiTest
{

    @Test
    void xlsxRegistersAndJsonDoesNot()
    {
        ServiceReportManager manager = new ServiceReportManager();
        assertNotNull(manager.findReportFormat("xlsx"));
        assertNull(manager.findReportFormat("json"),
                "R4: the json module is not on this classpath, so its format must be unavailable");
        assertNull(manager.findReportFormat("json-2"));
    }


    @Test
    void aWriterAddedFromOutsideTheEngineIsDiscovered() throws IOException
    {
        ServiceReportManager manager = new ServiceReportManager();
        List<String> names = manager.getSupportedReportFormats().stream().map(ReportFormat::name)
                .sorted().toList();
        assertEquals(List.of(StubCsvReportWriterSupplier.NAME, "xlsx"), names,
                "R5: a format contributed purely through META-INF/services must appear with no "
                        + "engine change");

        ReportFormat csv = manager.findReportFormat(StubCsvReportWriterSupplier.NAME);
        assertNotNull(csv);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manager.writeReport(sections(), out, csv);
        assertEquals("core_id,status\nCORE-000001,SUCCESS\n", out.toString(StandardCharsets.UTF_8));
    }


    @Test
    void theXlsxFormatDeclaresItsRowCapAsAProperty()
    {
        ServiceReportManager manager = new ServiceReportManager();
        ReportFormat xlsx = manager.findReportFormat("xlsx");
        assertNotNull(xlsx);
        assertEquals("xlsx", xlsx.fileExtension());
        assertEquals(".xlsx", xlsx.fileSuffix());

        List<Property> properties = manager.getWriterProperties(xlsx);
        assertEquals(1, properties.size());
        Property maxRows = properties.get(0);
        assertEquals(XlsxReportWriterSupplier.MAX_ROWS_PER_SHEET, maxRows.name());
        assertEquals(PropertyType.INTEGER, maxRows.type());
        assertEquals("10000", maxRows.defaultValue(), "the value crosses the SPI as a string");
        assertNull(maxRows.maxLength(), "a row cap is not a length-limited value");
    }


    @Test
    void theRowCapPropertyReachesTheWriter() throws IOException
    {
        ServiceReportManager manager = new ServiceReportManager();
        ReportFormat xlsx = manager.findReportFormat("xlsx");
        assertNotNull(xlsx);
        Property maxRows = manager.getWriterProperties(xlsx).get(0);

        // 0 == unlimited, which the workbook renders as the literal "None" in Conformance row 7.
        assertEquals("None", issueLimitPerSheet(manager, xlsx, Map.of(maxRows, "0")));
        assertEquals("25", issueLimitPerSheet(manager, xlsx, Map.of(maxRows, "25")));
        // Absent property == "no caller-supplied limit" == the writer's own default.
        assertEquals("10000", issueLimitPerSheet(manager, xlsx, Map.of()));
        // A malformed value degrades to "not supplied" rather than throwing mid-report.
        assertEquals("10000", issueLimitPerSheet(manager, xlsx, Map.of(maxRows, "not-a-number")));
    }


    @Test
    void anUnregisteredFormatStillFailsWithTheNamedError()
    {
        ServiceReportManager manager = new ServiceReportManager();
        ReportFormat json = new ReportFormat("json", "JSON", "json", ".json");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> manager.getReportWriter(json, Map.of()));
        assertTrue(e.getMessage().contains("cumba-oss-cdisc-report-json"), e.getMessage());
    }


    private static String issueLimitPerSheet(ServiceReportManager manager, ReportFormat format,
            Map<Property, String> properties)
        throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manager.getReportWriter(format, properties).write(sections(), out);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray())))
        {
            return wb.getSheet("Conformance Details").getRow(6).getCell(1).getStringCellValue();
        }
    }


    private static ReportSections sections()
    {
        return new ReportSections(Map.of("Standard", "SDTMIG"), List.of(), List.of(), List.of(),
                List.of(Map.of("core_id", "CORE-000001", "status", "SUCCESS")), List.of());
    }
}
