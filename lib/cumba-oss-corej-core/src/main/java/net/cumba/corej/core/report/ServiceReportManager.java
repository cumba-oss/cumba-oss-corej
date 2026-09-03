package net.cumba.corej.core.report;

import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.cumba.datatable.io.GenericServiceFactory;
import net.cumba.datatable.io.Property;
import org.jspecify.annotations.Nullable;

/**
 * The {@link ReportManager} over the project SPI: it discovers {@link ReportWriterSupplier}s
 * through {@link GenericServiceFactory} — <b>not</b> {@link java.util.ServiceLoader}, which this
 * project does not use — exactly as {@code FunctionRegistry.ProviderFactory} discovers
 * native-evaluator functions, down to the subclass that widens {@code getSuppliers()} from
 * {@code protected}.
 *
 * <h2>Two behaviours of the underlying factory this class exists to compensate for</h2>
 * <ol>
 * <li><b>It never throws.</b> An unloadable class or a missing no-argument constructor is logged
 * and skipped, so a mis-registered writer module produces an empty registry and a log line. The
 * <em>"no writer registered for format 'xlsx'"</em> error is therefore raised here, in
 * {@link #getReportWriter}, and nowhere below.</li>
 * <li><b>Suppliers are loaded once</b> and cached for the life of the instance. There is no runtime
 * classpath re-scan; a test that arranges a new registration needs a new manager.</li>
 * </ol>
 *
 * <h2>Duplicate names fail loudly</h2>
 *
 * <p>
 * {@link ReportFormat#name()} is the routing identity, so two suppliers claiming one name is not a
 * situation with a defensible winner — iteration order would decide which writer a user's
 * {@code -of json} reaches. The registry rejects it with both class names in the message. (The
 * datatable registry tolerates the analogous collision because it routes on URI plus info identity,
 * not on a name.)
 * </p>
 */
public final class ServiceReportManager implements ReportManager
{

    /**
     * Widens {@link GenericServiceFactory#getSuppliers()} to public, the one wrinkle in reusing the
     * generic factory. {@code P} is unreferenced by the factory body, and {@code S} is unbounded,
     * so {@link ReportWriterSupplier} implements no datatable interface.
     */
    private static final class SupplierFactory
            extends GenericServiceFactory<ReportWriterSupplier, Object>
    {

        private SupplierFactory()
        {
            super(ReportWriterSupplier.class);
        }


        @Override
        public List<ReportWriterSupplier> getSuppliers()
        {
            return super.getSuppliers();
        }
    }


    private static final class Holder
    {

        private static final ServiceReportManager INSTANCE = new ServiceReportManager();

        private Holder()
        {
        }
    }

    /** Format name (lower-cased) → supplier, in discovery order. */
    private final Map<String, ReportWriterSupplier> suppliers;

    /**
     * Discovers every {@link ReportWriterSupplier} on the classpath. Prefer {@link #getInstance()};
     * this constructor exists so a test can force a fresh scan.
     *
     * @throws IllegalStateException
     *             when two suppliers declare the same {@link ReportFormat#name()}
     */
    public ServiceReportManager()
    {
        this(new SupplierFactory().getSuppliers());
    }


    /**
     * Registry over an explicit supplier list, bypassing classpath discovery. Package-private: it
     * is the seam the registration tests use to exercise duplicate detection without arranging two
     * real modules.
     *
     * @param aSuppliers
     *            the suppliers to register
     * @throws IllegalStateException
     *             when two suppliers declare the same {@link ReportFormat#name()}
     */
    ServiceReportManager(List<ReportWriterSupplier> aSuppliers)
    {
        Map<String, ReportWriterSupplier> byName = new LinkedHashMap<>();
        for (ReportWriterSupplier supplier : aSuppliers)
        {
            String key = key(supplier.getReportFormat().name());
            ReportWriterSupplier previous = byName.putIfAbsent(key, supplier);
            if (previous != null)
            {
                throw new IllegalStateException("Duplicate report format '"
                        + supplier.getReportFormat().name() + "' declared by both "
                        + previous.getClass().getName() + " and " + supplier.getClass().getName()
                        + " — a format name is the routing identity and must be unique.");
            }
        }
        suppliers = byName;
    }


    /**
     * The shared manager, discovering suppliers on first use.
     *
     * @return the singleton manager
     */
    public static ServiceReportManager getInstance()
    {
        return Holder.INSTANCE;
    }


    @Override
    public List<ReportFormat> getSupportedReportFormats()
    {
        return suppliers.values().stream().map(ReportWriterSupplier::getReportFormat).toList();
    }


    @Override
    public @Nullable ReportFormat findReportFormat(@Nullable String aName)
    {
        if (aName == null)
        {
            return null;
        }
        ReportWriterSupplier supplier = suppliers.get(key(aName));
        return supplier != null ? supplier.getReportFormat() : null;
    }


    /**
     * The {@link Property} declarations of one format, for a client that wants to enumerate the
     * options it may pass to {@link #getReportWriter}.
     *
     * @param aFormat
     *            the format to describe
     * @return the declared properties, or an empty list when the format is not registered
     */
    public List<Property> getWriterProperties(ReportFormat aFormat)
    {
        ReportWriterSupplier supplier = suppliers.get(key(aFormat.name()));
        return supplier != null ? List.copyOf(supplier.getWriterProperties()) : List.of();
    }


    @Override
    public ReportWriter getReportWriter(ReportFormat aFormat, Map<Property, String> aProperties)
    {
        ReportWriterSupplier supplier = suppliers.get(key(aFormat.name()));
        if (supplier == null)
        {
            throw new IllegalStateException(noWriterMessage(aFormat.name()));
        }
        return supplier.getReportWriter(aProperties);
    }


    @Override
    public void writeReport(ReportSections aSections, OutputStream aOut, ReportFormat aFormat)
        throws IOException
    {
        getReportWriter(aFormat, Map.of()).write(aSections, aOut);
    }


    /**
     * The error a consumer sees when the requested format has no writer — the whole reason this
     * class raises it rather than the SPI factory, which only logs.
     */
    private String noWriterMessage(String aName)
    {
        String registered = suppliers.isEmpty() ? "none" : String.join(", ", suppliers.keySet());
        return "No report writer registered for format '" + aName
                + "' — add a report-writer module to the classpath (cumba-oss-corej-report-json "
                + "provides 'json' and 'json-2', cumba-oss-corej-report-xlsx provides 'xlsx'). "
                + "Registered formats: " + registered + ".";
    }


    private static String key(String aName)
    {
        return aName.toLowerCase(Locale.ROOT);
    }
}
