package net.cumba.dataviewer.examples.cdt.ruletest;

import java.util.Optional;

import net.cumba.cdisc.core.CoreLibraryAccess;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.core.metadata.CdiscLibraryProviderBuilder;
import net.cumba.datatable.impl.metadata.DataTableLibraryMetadataAdapter;

/**
 * Turns a declarative {@link LibraryRef} into a real {@link MetadataProvider} built from the CDISC
 * Library API, or signals that the Library is unavailable.
 *
 * <p>
 * Availability gate: {@link CoreLibraryAccess#openIfConfigured()} — empty when no
 * {@code CDISC_API_KEY} (env) / {@code cdisc.library.api.key} (sysprop) is set. The cache directory
 * / base URL come from the environment too. Callers should treat {@link Optional#empty()} as "skip
 * this scenario".
 * </p>
 *
 * <p>
 * The study side is {@link DataTableLibraryMetadataAdapter#empty()} — the verdict is judged against
 * the real Library products (IG / Model / pinned CT) plus the scenario's own declared columns and
 * data (supplied through the resolver), with no define.xml/study-derived enrichment.
 * </p>
 */
public final class ScenarioLibraryResolver
{

    private ScenarioLibraryResolver()
    {
    }


    /**
     * Resolve {@code aRef} to a real provider, or {@link Optional#empty()} when no CDISC Library is
     * configured. {@code buildOrDegraded()} never throws — a configured-but-failing Library yields
     * a degraded provider whose Library-dependent rules report {@code SKIPPED} (the caller's second
     * skip gate).
     */
    public static Optional<MetadataProvider> resolve(LibraryRef aRef)
    {
        return CoreLibraryAccess.openIfConfigured()
                .map(access -> CdiscLibraryProviderBuilder.from(access)
                        .study(DataTableLibraryMetadataAdapter.empty())
                        // Use the real Library product as the metadata source (no study overlay) so
                        // variable-level rules see the IG's published metadata. Requires a pinned
                        // CT package (the scenario's ct=).
                        .libraryAsStudy().standard(aRef.getStandard()).version(aRef.getVersion())
                        .ctPackageIds(aRef.getCtPackages()).buildOrDegraded());
    }
}
