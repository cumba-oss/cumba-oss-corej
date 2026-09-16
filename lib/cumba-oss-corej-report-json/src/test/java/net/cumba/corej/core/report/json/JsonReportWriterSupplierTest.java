package net.cumba.corej.core.report.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import net.cumba.corej.core.report.ReportWriter;
import net.cumba.datatable.io.Property;
import org.junit.jupiter.api.Test;

/**
 * Wave 5 of the clinical-path campaign — the supplier's own three methods, called directly.
 *
 * <p>
 * This module's single undetected mutant was a {@code null} substituted for the return of
 * {@link JsonReportWriterSupplier#getReportWriter(Map)}, reported NO_COVERAGE: the existing suite
 * reaches the writer through {@code ServiceReportManager}, which exercises <em>registration</em>,
 * and through the golden test, which constructs {@code JsonReportWriter} itself. Neither calls the
 * factory method, so a supplier that handed back {@code null} would have shipped green.
 * </p>
 *
 * <p>
 * ⚠ Reaching it through the manager instead would have been the tempting fix and a worse test: it
 * would assert the SPI wiring a second time — already covered by
 * {@code JsonReportWriterSpiRegistrationTest} — while still not pinning that this class's own
 * factory returns anything.
 * </p>
 */
class JsonReportWriterSupplierTest
{

    @Test
    void theFactoryReturnsAFreshJsonWriter()
    {
        JsonReportWriterSupplier supplier = new JsonReportWriterSupplier();

        ReportWriter first = supplier.getReportWriter(Map.<Property, String> of());
        assertNotNull(first, "the supplier must hand back a writer, not null");
        assertTrue(first instanceof JsonReportWriter,
                "and it must be this module's writer, not some other format's: "
                        + first.getClass().getName());

        // A supplier is consulted once per run, so a shared instance would silently couple two
        // reports' state. Pin that each call builds its own.
        ReportWriter second = supplier.getReportWriter(Map.<Property, String> of());
        assertNotSame(first, second, "each call builds its own writer");
    }


    @Test
    void theDeclaredFormatIsTheFrozenV1Json()
    {
        JsonReportWriterSupplier supplier = new JsonReportWriterSupplier();
        assertEquals("json", supplier.getReportFormat().name());
        assertEquals("json", supplier.getReportFormat().fileExtension());
        assertEquals(".json", supplier.getReportFormat().fileSuffix());
        // No writer properties: the v1 schema is frozen, so there is nothing to tune. An empty
        // list is the contract, not an oversight — a future property must be a deliberate edit
        // here as well as in the supplier.
        assertEquals(0, supplier.getWriterProperties().size());
    }
}
