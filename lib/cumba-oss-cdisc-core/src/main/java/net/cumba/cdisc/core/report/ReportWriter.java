package net.cumba.cdisc.core.report;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Turns assembled {@link ReportSections} into bytes in one concrete format.
 *
 * <p>
 * A writer is obtained from {@link ReportManager}, never constructed by a consumer, so a format can
 * be added to a deployment by putting a module on the classpath. Implementations live in their own
 * modules ({@code corej-cdisc-report-json}, {@code corej-cdisc-report-xlsx}) and see nothing of the
 * engine beyond this package plus {@code net.cumba.datatable.io.Property}.
 * </p>
 *
 * <p>
 * The contract is a stream, never a {@link java.nio.file.Path}: a writer that only knows a stream
 * is testable without a filesystem, and the REST layer already streams into a byte array. Writing
 * to a file is a {@link ReportManager} convenience layered over this method.
 * </p>
 */
@FunctionalInterface
public interface ReportWriter
{

    /**
     * Writes the given sections to the stream. The stream is <b>not</b> closed — the caller owns
     * it.
     *
     * @param aSections
     *            the assembled report sections
     * @param aOut
     *            the destination stream
     * @throws IOException
     *             on write failure
     */
    void write(ReportSections aSections, OutputStream aOut) throws IOException;
}
