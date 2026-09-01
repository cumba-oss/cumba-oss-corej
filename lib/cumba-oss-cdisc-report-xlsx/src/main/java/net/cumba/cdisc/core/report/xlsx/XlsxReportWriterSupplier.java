package net.cumba.cdisc.core.report.xlsx;

import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.report.ReportFormat;
import net.cumba.cdisc.core.report.ReportWriter;
import net.cumba.cdisc.core.report.ReportWriterSupplier;
import net.cumba.datatable.io.Property;
import org.jspecify.annotations.Nullable;

/**
 * Registers the {@code xlsx} report format with the engine's report SPI.
 *
 * <p>
 * Discovered through {@code META-INF/services/net.cumba.cdisc.core.report.ReportWriterSupplier};
 * the public no-argument constructor is required by the project SPI factory.
 * </p>
 */
public final class XlsxReportWriterSupplier implements ReportWriterSupplier
{

    /** The option name a client looks for when it wants to set the per-sheet row cap. */
    public static final String MAX_ROWS_PER_SHEET = "maxRowsPerSheet";

    private static final ReportFormat FORMAT = new ReportFormat("xlsx",
            "Excel workbook (Python CORE parity, one sheet per report section)", "xlsx", ".xlsx");

    /**
     * The per-sheet row cap, declared so a CLI or UI can discover it instead of hard-coding a flag.
     * The value crosses the SPI as a string, as every {@link Property} value does.
     */
    private static final Property MAX_ROWS = Property.forInteger(MAX_ROWS_PER_SHEET,
            "Per-sheet row cap for the list sheets; 0 = unlimited",
            XlsxReportWriter.DEFAULT_MAX_ROWS);

    @Override
    public ReportFormat getReportFormat()
    {
        return FORMAT;
    }


    @Override
    public List<Property> getWriterProperties()
    {
        return List.of(MAX_ROWS);
    }


    /**
     * {@inheritDoc}
     *
     * <p>
     * The supplied {@code maxRowsPerSheet} is combined with the {@code MAX_REPORT_ROWS} environment
     * variable by {@link XlsxReportWriter#resolveMaxRows}, preserving the Python engine's
     * resolution rules. An absent property means "no caller-supplied limit" — <em>not</em> the
     * declared default — so the environment variable alone still wins, exactly as it did when the
     * CLI passed {@code null} for an unset {@code --max-report-rows}.
     * </p>
     */
    @Override
    public ReportWriter getReportWriter(Map<Property, String> aProperties)
    {
        return new XlsxReportWriter(XlsxReportWriter.resolveMaxRows(maxRows(aProperties)));
    }


    /**
     * Reads the {@code maxRowsPerSheet} value out of the property map. Matching is by property
     * <b>name</b> rather than by map lookup on the declared instance, so a caller that rebuilt an
     * equal {@link Property} from a wire payload still configures the writer.
     */
    private static @Nullable Integer maxRows(Map<Property, String> aProperties)
    {
        for (Map.Entry<Property, String> e : aProperties.entrySet())
        {
            if (MAX_ROWS_PER_SHEET.equals(e.getKey().name()) && e.getValue() != null
                    && !e.getValue().isBlank())
            {
                try
                {
                    return Integer.valueOf(e.getValue().trim());
                }
                catch (NumberFormatException _)
                {
                    // A malformed value is treated as "not supplied", matching the environment
                    // variable's own tolerance in resolveMaxRows.
                    return null;
                }
            }
        }
        return null;
    }
}
