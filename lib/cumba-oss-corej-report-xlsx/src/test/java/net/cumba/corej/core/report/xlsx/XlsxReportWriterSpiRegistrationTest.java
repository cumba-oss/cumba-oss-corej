package net.cumba.corej.core.report.xlsx;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.report.ReportFormat;
import net.cumba.corej.core.report.ServiceReportManager;
import org.junit.jupiter.api.Test;

/**
 * Guards the SPI registration in
 * {@code META-INF/services/net.cumba.corej.core.report.ReportWriterSupplier}.
 *
 * <p>
 * A stale fully-qualified class name in that services file is invisible to the compiler and to
 * every other test in this module: {@code ServiceReportManager} would simply find no supplier, the
 * report format would be absent at runtime, and the build would stay green. This test is the only
 * thing that fails in that case.
 * </p>
 *
 * <p>
 * ⚠ This matters more here than the compiler suggests: nothing in the product compiles against
 * these writers. The engine resolves a format by NAME through the service loader, so a dropped
 * registration surfaces as "unknown report format", not as a build error.
 * </p>
 */
class XlsxReportWriterSpiRegistrationTest
{

    @Test
    void xlsxReportWriterSupplierIsDiscoverableThroughTheSpi()
    {
        ReportFormat expected = new XlsxReportWriterSupplier().getReportFormat();
        assertFalse(expected.name().isBlank(),
                "XlsxReportWriterSupplier must declare a report-format name, otherwise this guard "
                        + "would pass vacuously");

        List<ReportFormat> registered = ServiceReportManager.getInstance()
                .getSupportedReportFormats();

        assertTrue(registered.stream().anyMatch(f -> f.name().equals(expected.name())),
                () -> "XlsxReportWriterSupplier must be registered in META-INF/services — format \""
                        + expected.name() + "\" was not discovered through the SPI; found "
                        + registered.stream().map(ReportFormat::name).toList());
    }
}
