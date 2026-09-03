package net.cumba.corej.core.report.json;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.report.ReportFormat;
import net.cumba.corej.core.report.ReportWriter;
import net.cumba.corej.core.report.ReportWriterSupplier;
import net.cumba.datatable.io.Property;

/**
 * Registers the {@code json} report format — the frozen v1 schema — with the engine's report SPI.
 *
 * <p>
 * Discovered through {@code META-INF/services/net.cumba.corej.core.report.ReportWriterSupplier};
 * the public no-argument constructor is required by the project SPI factory.
 * </p>
 */
public final class JsonReportWriterSupplier implements ReportWriterSupplier
{

    private static final ReportFormat FORMAT = new ReportFormat("json",
            "CORE-parity JSON validation report (v1, frozen schema)", "json", ".json");

    @Override
    public ReportFormat getReportFormat()
    {
        return FORMAT;
    }


    @Override
    public List<Property> getWriterProperties()
    {
        return List.of();
    }


    @Override
    public ReportWriter getReportWriter(Map<Property, String> aProperties)
    {
        return new JsonReportWriter(false);
    }
}
