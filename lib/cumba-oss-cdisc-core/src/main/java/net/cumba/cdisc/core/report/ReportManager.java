package net.cumba.cdisc.core.report;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.cumba.datatable.io.Property;
import org.jspecify.annotations.Nullable;

/**
 * Routes a report to a registered {@link ReportWriter} <b>by format name</b> — the report-side
 * counterpart of {@code IDataTableManager.getDataTableRef(URI, FileInfo)}, and the only thing a
 * consumer needs to know about report writing.
 *
 * <p>
 * The engine ships this interface and its {@link ServiceReportManager} implementation but <b>no
 * writers</b>: a deployment with no report module on the classpath validates normally and answers
 * {@code -of xlsx} with a named error rather than a stack trace.
 * </p>
 *
 * <p>
 * ⚠ That error has to be raised <em>here</em>. The underlying
 * {@link net.cumba.datatable.io.GenericServiceFactory} swallows every discovery failure into a log
 * line and returns whatever it managed to load, so a mis-registered module yields an empty registry
 * and nothing below this layer will ever throw.
 * </p>
 */
public interface ReportManager
{

    /**
     * Every registered format, in discovery order.
     *
     * @return the supported formats; may be empty (an engine with no writer module), never
     *         {@code null}
     */
    List<ReportFormat> getSupportedReportFormats();


    /**
     * Looks a format up by its {@link ReportFormat#name()}.
     *
     * @param aName
     *            the format name; matched case-insensitively, {@code null} yields {@code null}
     * @return the format, or {@code null} when no writer is registered for it
     */
    @Nullable
    ReportFormat findReportFormat(@Nullable String aName);


    /**
     * Builds a configured writer for the given format.
     *
     * @param aFormat
     *            the format to write
     * @param aProperties
     *            option values for the format's declared properties; empty means "all defaults"
     * @return the writer; never {@code null}
     * @throws IllegalStateException
     *             when no writer is registered for the format — the message names the format and
     *             what to add to the classpath
     */
    ReportWriter getReportWriter(ReportFormat aFormat, Map<Property, String> aProperties);


    /**
     * Writes the sections in the given format with all options at their defaults.
     *
     * @param aSections
     *            the assembled report sections
     * @param aOut
     *            the destination stream; not closed
     * @param aFormat
     *            the format to write
     * @throws IOException
     *             on write failure
     * @throws IllegalStateException
     *             when no writer is registered for the format
     */
    void writeReport(ReportSections aSections, OutputStream aOut, ReportFormat aFormat)
        throws IOException;


    /**
     * Writes the sections to a file, creating missing parent directories.
     *
     * <p>
     * The {@link Path} lives here and deliberately <b>not</b> in {@link ReportWriter}: a writer
     * that only knows a stream is testable without a filesystem, and the REST layer never wants a
     * file at all. This is the single place the "create the parent directory first" behaviour the
     * CLI depends on is implemented.
     * </p>
     *
     * @param aSections
     *            the assembled report sections
     * @param aPath
     *            the destination file; overwritten if it exists
     * @param aFormat
     *            the format to write
     * @param aProperties
     *            option values for the format's declared properties; empty means "all defaults"
     * @throws IOException
     *             on write failure
     * @throws IllegalStateException
     *             when no writer is registered for the format
     */
    default void writeReport(ReportSections aSections, Path aPath, ReportFormat aFormat,
            Map<Property, String> aProperties)
        throws IOException
    {
        ReportWriter writer = getReportWriter(aFormat, aProperties);
        Path parent = aPath.getParent();
        if (parent != null)
        {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(aPath))
        {
            writer.write(aSections, out);
        }
    }
}
