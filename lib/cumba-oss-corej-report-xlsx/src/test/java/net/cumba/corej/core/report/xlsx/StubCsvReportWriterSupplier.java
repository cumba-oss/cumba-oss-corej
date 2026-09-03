package net.cumba.corej.core.report.xlsx;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.report.ReportFormat;
import net.cumba.corej.core.report.ReportSections;
import net.cumba.corej.core.report.ReportWriter;
import net.cumba.corej.core.report.ReportWriterSupplier;
import net.cumba.datatable.io.Property;

/**
 * <b>R5 — a new report format, added without touching the engine.</b>
 *
 * <p>
 * This is the acceptance criterion of the whole change, made executable and kept, rather than
 * demonstrated once and deleted: a deliberately trivial CSV writer, living entirely in a test
 * source root, registered through nothing but
 * {@code src/test/resources/META-INF/services/net.cumba.corej.core.report.ReportWriterSupplier}. No
 * engine class was edited to make {@code csv-stub} appear in
 * {@link net.cumba.corej.core.report.ReportManager#getSupportedReportFormats()} —
 * {@code XlsxReportSpiTest} asserts that it does.
 * </p>
 *
 * <p>
 * Note what it imports: the four SPI types, {@link Property}, and the JDK. That is the entire
 * surface a format author has to learn, and {@code WriterModuleImportGuardTest} keeps the shipped
 * writers to the same list.
 * </p>
 */
public final class StubCsvReportWriterSupplier implements ReportWriterSupplier
{

    /** The format name this stub claims; deliberately not a real format anyone ships. */
    public static final String NAME = "csv-stub";

    private static final Property SEPARATOR = Property.forString("separator",
            "Field separator for the stub CSV output", ",");

    @Override
    public ReportFormat getReportFormat()
    {
        return new ReportFormat(NAME, "Test-only CSV stub proving a writer needs only the SPI",
                "csv", ".csv");
    }


    @Override
    public List<Property> getWriterProperties()
    {
        return List.of(SEPARATOR);
    }


    @Override
    public ReportWriter getReportWriter(Map<Property, String> aProperties)
    {
        String separator = aProperties.getOrDefault(SEPARATOR, SEPARATOR.defaultValue());
        return (ReportSections aSections, OutputStream aOut) ->
        {
            StringBuilder sb = new StringBuilder("core_id").append(separator).append("status\n");
            for (Map<String, Object> row : aSections.rulesReport())
            {
                sb.append(row.get("core_id")).append(separator).append(row.get("status"))
                        .append('\n');
            }
            write(aOut, sb.toString());
        };
    }


    private static void write(OutputStream aOut, String aText) throws IOException
    {
        aOut.write(aText.getBytes(StandardCharsets.UTF_8));
    }
}
