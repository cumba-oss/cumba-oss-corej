package net.cumba.cdisc.core.report.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.report.ReportFormat;
import net.cumba.cdisc.core.report.ServiceReportManager;
import org.junit.jupiter.api.Test;

/**
 * <b>R4 — the modules are independent, not a package split.</b> This module's test classpath
 * carries {@code corej-cdisc-report-json} and <em>not</em> {@code corej-cdisc-report-xlsx}, so the
 * registry must offer exactly {@code json} and {@code json-2}, and {@code xlsx} must be absent with
 * the named error rather than silently missing.
 */
class JsonReportSpiTest
{

    @Test
    void bothJsonFormatsRegisterAndXlsxDoesNot()
    {
        ServiceReportManager manager = new ServiceReportManager();
        List<String> names = manager.getSupportedReportFormats().stream().map(ReportFormat::name)
                .sorted().toList();
        assertEquals(List.of("json", "json-2"), names,
                "one module, two formats — and nothing from the xlsx module");
        assertNull(manager.findReportFormat("xlsx"),
                "R4: the xlsx module is not on this classpath, so its format must be unavailable");
    }


    @Test
    void theFormatsDeclareTheirFileNaming()
    {
        ServiceReportManager manager = new ServiceReportManager();
        ReportFormat v1 = manager.findReportFormat("json");
        ReportFormat v2 = manager.findReportFormat("json-2");
        assertNotNull(v1);
        assertNotNull(v2);
        assertEquals("json", v1.fileExtension());
        assertEquals(".json", v1.fileSuffix());
        // Same extension, different suffix — the v2 double extension is data on the format.
        assertEquals("json", v2.fileExtension());
        assertEquals(".v2.json", v2.fileSuffix());
        assertTrue(manager.getWriterProperties(v1).isEmpty(), "JSON has no options today");
        assertTrue(manager.getWriterProperties(v2).isEmpty());
    }


    @Test
    void theLegacyJson2SpellingIsNotAFormatName()
    {
        // Deliberate: "json2" is a CLI-level alias, translated before the lookup. Registering it as
        // a second name here would make two identities for one writer and defeat R8.
        assertNull(new ServiceReportManager().findReportFormat("json2"));
    }


    @Test
    void routingThroughTheManagerProducesTheSameBytesAsTheWriter() throws IOException
    {
        ServiceReportManager manager = new ServiceReportManager();
        ByteArrayOutputStream viaManager = new ByteArrayOutputStream();
        manager.writeReport(JsonReportGoldenTest.fixture(), viaManager,
                assertNotNullFormat(manager.findReportFormat("json-2")));

        ByteArrayOutputStream direct = new ByteArrayOutputStream();
        new JsonReportWriter(true).write(JsonReportGoldenTest.fixture(), direct);

        assertEquals(direct.toString(StandardCharsets.UTF_8),
                viaManager.toString(StandardCharsets.UTF_8));
    }


    @Test
    void anUnknownFormatStillFailsWithTheNamedError()
    {
        ServiceReportManager manager = new ServiceReportManager();
        ReportFormat xlsx = new ReportFormat("xlsx", "Excel", "xlsx", ".xlsx");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> manager.getReportWriter(xlsx, Map.of()));
        assertTrue(e.getMessage().contains("corej-cdisc-report-xlsx"), e.getMessage());
        assertTrue(e.getMessage().contains("Registered formats: json, json-2"), e.getMessage());
    }


    private static ReportFormat assertNotNullFormat(ReportFormat format)
    {
        assertNotNull(format);
        return format;
    }
}
