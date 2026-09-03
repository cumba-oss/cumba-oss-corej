package net.cumba.corej.core.report;

import java.util.List;
import java.util.Map;
import net.cumba.datatable.io.Property;

/**
 * The service interface a report-writer module registers: it declares one {@link ReportFormat}, the
 * options that format understands, and builds a configured {@link ReportWriter}.
 *
 * <h2>Registration</h2>
 *
 * <p>
 * Discovery goes through the project SPI ({@link net.cumba.datatable.io.GenericServiceFactory}),
 * <b>not</b> {@link java.util.ServiceLoader} — the same mechanism
 * {@code FunctionRegistry.ProviderFactory} uses for native-evaluator functions. The resource format
 * is identical: a module ships
 * {@code META-INF/services/net.cumba.corej.core.report.ReportWriterSupplier} listing one
 * fully-qualified implementation class per line ({@code #} comments ignored), and every
 * implementation <b>must</b> have a public no-argument constructor — the factory instantiates via
 * {@code getConstructor().newInstance()} and merely logs the failure otherwise.
 * </p>
 *
 * <p>
 * One supplier declares exactly one format. A module serving two formats — as
 * {@code cumba-oss-corej-report-json} does with {@code json} and {@code json-2} — registers two
 * suppliers, because {@link ReportManager} routes by {@link ReportFormat#name()} and a supplier
 * with two names could not be looked up.
 * </p>
 *
 * <h2>No capability query</h2>
 *
 * <p>
 * Unlike the datatable suppliers this pattern is modelled on, there is no {@code canProvideFor(…)}:
 * those sniff a file, whereas a report writer is chosen by an explicit format name.
 * {@link #getReportFormat()} is the whole selection surface.
 * </p>
 */
public interface ReportWriterSupplier
{

    /**
     * The single format this supplier serves.
     *
     * @return the format; never {@code null}
     */
    ReportFormat getReportFormat();


    /**
     * The options this format understands, as self-describing {@link Property} declarations so a
     * CLI or UI can enumerate them instead of hard-coding flags. Values are always carried as
     * strings (see {@link Property}), which the writer parses.
     *
     * @return the declared properties; may be empty, never {@code null}
     */
    List<Property> getWriterProperties();


    /**
     * Builds a writer configured with the given option values.
     *
     * @param aProperties
     *            option values keyed by the {@link Property} declarations from
     *            {@link #getWriterProperties()}; unknown keys are ignored and absent ones fall back
     *            to {@link Property#defaultValue()}. An empty map means "all defaults".
     * @return the configured writer; never {@code null}
     */
    ReportWriter getReportWriter(Map<Property, String> aProperties);
}
