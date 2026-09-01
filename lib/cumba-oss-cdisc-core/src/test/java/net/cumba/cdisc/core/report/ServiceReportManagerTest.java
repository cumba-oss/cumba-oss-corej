package net.cumba.cdisc.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.cumba.datatable.io.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The report registry, and with it two of the plan's acceptance gates.
 *
 * <ul>
 * <li><b>R3 — the plain engine.</b> This module is the engine and its test classpath carries no
 * writer module, so {@link ServiceReportManager}'s classpath scan must find <em>nothing</em> and a
 * lookup must fail with a named, actionable error rather than a {@code NullPointerException} or a
 * {@code NoClassDefFoundError}. ⚠ That is asserted against the <b>project SPI</b>
 * ({@code GenericServiceFactory}), which is what writer modules actually register with — an
 * assertion phrased against {@code java.util.ServiceLoader} would pass while proving nothing,
 * because this repository has no {@code ServiceLoader.load} call in any {@code src/main}.</li>
 * <li><b>R8 — duplicate names fail loudly</b>, with both class names in the message.</li>
 * </ul>
 */
class ServiceReportManagerTest
{

    private static final ReportFormat STUB_FORMAT = new ReportFormat("stub", "A test format",
            "stub", ".stub");

    /** A writer that records what it was configured with, so routing can be observed. */
    private record StubWriter(String marker) implements ReportWriter
    {

        @Override
        public void write(ReportSections aSections, OutputStream aOut) throws IOException
        {
            aOut.write((marker + ":" + aSections.rulesReport().size())
                    .getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final Property MARKER = Property.forString("marker", "Recorded by the writer",
            "default");

    private static class StubSupplier implements ReportWriterSupplier
    {

        private final ReportFormat format;

        StubSupplier(ReportFormat aFormat)
        {
            format = aFormat;
        }


        @Override
        public ReportFormat getReportFormat()
        {
            return format;
        }


        @Override
        public List<Property> getWriterProperties()
        {
            return List.of(MARKER);
        }


        @Override
        public ReportWriter getReportWriter(Map<Property, String> aProperties)
        {
            return new StubWriter(aProperties.getOrDefault(MARKER, MARKER.defaultValue()));
        }
    }


    /** A distinct class so the duplicate-name message can name two different types. */
    private static final class OtherStubSupplier extends StubSupplier
    {

        OtherStubSupplier(ReportFormat aFormat)
        {
            super(aFormat);
        }
    }

    private static ReportSections sections()
    {
        return new ReportSections(Map.of(), List.of(), List.of(),
                List.of(Map.of("core_id", "CORE-000001")), List.of(Map.of("core_id", "X")),
                List.of());
    }

    // ------------------------------------------------------------------
    // R3 — the plain engine ships no writers, and says so
    // ------------------------------------------------------------------


    @Test
    void theEngineAloneRegistersNoWriters()
    {
        assertTrue(new ServiceReportManager().getSupportedReportFormats().isEmpty(),
                "corej-cdisc-core must ship no report writer of its own — a non-empty registry "
                        + "here means a writer module leaked onto the engine's own classpath");
    }


    @Test
    void theSharedInstanceIsTheSameRegistry()
    {
        assertSame(ServiceReportManager.getInstance(), ServiceReportManager.getInstance());
        assertTrue(ServiceReportManager.getInstance().getSupportedReportFormats().isEmpty());
    }


    @Test
    void anUnregisteredFormatFailsWithANamedError()
    {
        ReportManager manager = new ServiceReportManager();
        ReportFormat xlsx = new ReportFormat("xlsx", "Excel", "xlsx", ".xlsx");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> manager.getReportWriter(xlsx, Map.of()));
        assertTrue(e.getMessage().contains("'xlsx'"), e.getMessage());
        assertTrue(e.getMessage().contains("corej-cdisc-report-xlsx"), e.getMessage());
        assertTrue(e.getMessage().contains("Registered formats: none"), e.getMessage());
    }


    @Test
    void lookupOfAnAbsentOrNullNameYieldsNull()
    {
        ReportManager manager = new ServiceReportManager();
        assertNull(manager.findReportFormat("json"));
        assertNull(manager.findReportFormat(null));
    }

    // ------------------------------------------------------------------
    // R8 — a duplicate format name is a registration failure
    // ------------------------------------------------------------------


    @Test
    void twoSuppliersClaimingOneNameFailWithBothClassNames()
    {
        List<ReportWriterSupplier> clashing = List.of(new StubSupplier(STUB_FORMAT),
                new OtherStubSupplier(STUB_FORMAT));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new ServiceReportManager(clashing));
        assertTrue(e.getMessage().contains("stub"), e.getMessage());
        assertTrue(e.getMessage().contains(StubSupplier.class.getName()), e.getMessage());
        assertTrue(e.getMessage().contains(OtherStubSupplier.class.getName()), e.getMessage());
    }


    @Test
    void aDuplicateThatDiffersOnlyInCaseIsStillADuplicate()
    {
        List<ReportWriterSupplier> clashing = List.of(new StubSupplier(STUB_FORMAT),
                new OtherStubSupplier(new ReportFormat("STUB", "A test format", "stub", ".stub")));
        assertThrows(IllegalStateException.class, () -> new ServiceReportManager(clashing));
    }

    // ------------------------------------------------------------------
    // Routing
    // ------------------------------------------------------------------


    @Test
    void aRegisteredFormatRoutesToItsSupplier() throws IOException
    {
        ReportManager manager = new ServiceReportManager(List.of(new StubSupplier(STUB_FORMAT)));

        assertEquals(List.of(STUB_FORMAT), manager.getSupportedReportFormats());
        assertEquals(STUB_FORMAT, manager.findReportFormat("stub"));
        assertEquals(STUB_FORMAT, manager.findReportFormat("STUB"),
                "names match case-insensitively");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manager.writeReport(sections(), out, STUB_FORMAT);
        assertEquals("default:1", out.toString(StandardCharsets.UTF_8),
                "writeReport uses the declared defaults");
    }


    @Test
    void writerPropertiesReachTheSupplier() throws IOException
    {
        ServiceReportManager manager = new ServiceReportManager(
                List.of(new StubSupplier(STUB_FORMAT)));
        assertEquals(List.of(MARKER), manager.getWriterProperties(STUB_FORMAT));
        assertTrue(manager.getWriterProperties(new ReportFormat("nope", "d", "n", ".n")).isEmpty());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manager.getReportWriter(STUB_FORMAT, Map.of(MARKER, "configured")).write(sections(), out);
        assertEquals("configured:1", out.toString(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // The Path convenience — the "create the parent directory" behaviour the CLI relies on, which
    // used to sit on JsonReportWriter.writeTo(Path) before the writers left the engine.
    // ------------------------------------------------------------------


    @Test
    void writeReportToAPathCreatesMissingParentDirectories(@TempDir Path tmp) throws IOException
    {
        ReportManager manager = new ServiceReportManager(List.of(new StubSupplier(STUB_FORMAT)));
        Path out = tmp.resolve("nested").resolve("deeply").resolve("report.stub");
        assertFalse(Files.exists(out.getParent()));

        manager.writeReport(sections(), out, STUB_FORMAT, Map.of());

        assertTrue(Files.exists(out));
        assertEquals("default:1", Files.readString(out, StandardCharsets.UTF_8));
    }


    @Test
    void writeReportToAPathInAnExistingDirectory(@TempDir Path tmp) throws IOException
    {
        ReportManager manager = new ServiceReportManager(List.of(new StubSupplier(STUB_FORMAT)));
        Path out = tmp.resolve("report.stub");
        manager.writeReport(sections(), out, STUB_FORMAT, Map.of(MARKER, "p"));
        assertEquals("p:1", Files.readString(out, StandardCharsets.UTF_8));
    }


    @Test
    void writeReportToAPathFailsNamedWhenTheFormatIsUnregistered(@TempDir Path tmp)
    {
        ReportManager manager = new ServiceReportManager(List.of());
        Path out = tmp.resolve("report.stub");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> manager.writeReport(sections(), out, STUB_FORMAT, Map.of()));
        assertTrue(e.getMessage().contains("'stub'"), e.getMessage());
        assertFalse(Files.exists(out), "nothing is created when the format cannot be served");
    }

    // ------------------------------------------------------------------
    // ReportFormat's own invariants
    // ------------------------------------------------------------------


    @Test
    void aFormatComponentMayNotBeBlank()
    {
        assertNotNull(STUB_FORMAT.description());
        assertThrows(IllegalArgumentException.class, () -> new ReportFormat(" ", "d", "e", ".s"));
        assertThrows(IllegalArgumentException.class, () -> new ReportFormat("n", "", "e", ".s"));
        assertThrows(IllegalArgumentException.class, () -> new ReportFormat("n", "d", "", ".s"));
        assertThrows(IllegalArgumentException.class, () -> new ReportFormat("n", "d", "e", ""));
    }
}
