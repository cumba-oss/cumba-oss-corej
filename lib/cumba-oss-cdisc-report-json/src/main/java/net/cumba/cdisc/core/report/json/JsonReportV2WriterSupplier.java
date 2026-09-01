package net.cumba.cdisc.core.report.json;

import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.report.ReportFormat;
import net.cumba.cdisc.core.report.ReportWriter;
import net.cumba.cdisc.core.report.ReportWriterSupplier;
import net.cumba.datatable.io.Property;

/**
 * Registers the {@code json-2} report format — the combined-finding v2 report — with the engine's
 * report SPI.
 *
 * <p>
 * A second supplier rather than a second name on {@link JsonReportWriterSupplier}, because the
 * manager routes by {@link ReportFormat#name()} and one supplier can declare only one name. It
 * shares this module (and its mapper and document projection) with v1: two formats in one module is
 * the honest granularity, since both serialise the same assembled sections.
 * </p>
 *
 * <p>
 * The file suffix is {@code .v2.json}, not {@code .json}: a combined {@code -of json,json-2} run
 * writes {@code <base>.json} and {@code <base>.v2.json} without collision. Both formats share the
 * <em>extension</em> {@code json}, which is expected — nothing selects a format by extension.
 * </p>
 */
public final class JsonReportV2WriterSupplier implements ReportWriterSupplier
{

    private static final ReportFormat FORMAT = new ReportFormat("json-2",
            "Combined-finding JSON validation report (v2; one object per finding)", "json",
            ".v2.json");

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
        return new JsonReportWriter(true);
    }
}
