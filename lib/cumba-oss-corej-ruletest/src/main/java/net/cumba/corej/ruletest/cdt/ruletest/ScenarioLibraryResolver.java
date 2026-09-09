package net.cumba.corej.ruletest.cdt.ruletest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import lombok.CustomLog;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.metadata.MetadataProductKeys;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;

/**
 * Turns a declarative {@link LibraryRef} into a real {@link MetadataProvider} built from the
 * <b>unified metadata store</b> (cache plan P4 — the CDISC Library API path this resolver used
 * until then no longer exists), or signals that no library metadata is available.
 *
 * <p>
 * Availability gate: {@link StoreMetadataProviderFactory#resolveConfiguredFile} — empty when no
 * {@code CDISC_METADATA_STORE} (env) / {@code cdisc.metadata.store} (sysprop) names a store file.
 * Callers should treat {@link Optional#empty()} as "skip this scenario", exactly as they did for
 * the missing-API-key gate this replaces.
 * </p>
 *
 * <p>
 * The provider is the pure product-derived library — the store factory builds over
 * {@code CdiscLibraryMetadataLibrary} with no study overlay, matching the old
 * {@code libraryAsStudy()} mode — so the verdict is judged against the real Library products (IG /
 * Model / pinned CT) plus the scenario's own declared columns and data.
 * </p>
 */
@CustomLog
public final class ScenarioLibraryResolver
{

    private ScenarioLibraryResolver()
    {
    }


    /**
     * Resolve {@code aRef} to a real provider, or {@link Optional#empty()} when no metadata store
     * is configured, it cannot be opened, or it lacks the referenced product. Never throws — an
     * unreadable store is logged and treated as unavailable (the caller's skip gate), preserving
     * the no-throw contract of the {@code buildOrDegraded()} call this replaces.
     */
    public static Optional<MetadataProvider> resolve(LibraryRef aRef)
    {
        Path file = StoreMetadataProviderFactory.resolveConfiguredFile(null);
        if (file == null)
        {
            return Optional.empty();
        }
        StoreMetadataProviderFactory factory;
        try
        {
            factory = StoreMetadataProviderFactory.open(file);
        }
        catch (IOException e)
        {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Configured metadata store {0} cannot be opened for a #library-ref scenario "
                            + "({1}); the scenario will be skipped.",
                    file, e.getMessage());
            return Optional.empty();
        }
        String standard = aRef.getStandard().toLowerCase(Locale.ROOT);
        if (standard.startsWith("adam"))
        {
            // The single declared product an omitted --metadata-products implies for this pair.
            List<String> declared = List
                    .of(MetadataProductKeys.standardsKey(aRef.getStandard(), aRef.getVersion()));
            return factory.forAdam(aRef.getStandard(), aRef.getVersion(), declared,
                    ctIdsWithPrefix(aRef.getCtPackages(), "adamct"),
                    ctIdsWithPrefix(aRef.getCtPackages(), "sdtmct"));
        }
        // SDTM family — a SEND-family ref's own CT root precedes the sdtmct fallback root,
        // mirroring StudyValidationService.tryStoreProvider.
        List<String> ctIds = new ArrayList<>();
        if (standard.startsWith("send"))
        {
            ctIds.addAll(ctIdsWithPrefix(aRef.getCtPackages(), "sendct"));
        }
        ctIds.addAll(ctIdsWithPrefix(aRef.getCtPackages(), "sdtmct"));
        return factory.forSdtm(aRef.getStandard(), aRef.getVersion(), ctIds);
    }


    /** Every id starting with {@code aPrefix}, newest first (CT ids end in an ISO date). */
    private static List<String> ctIdsWithPrefix(List<String> aIds, String aPrefix)
    {
        List<String> out = new ArrayList<>();
        for (String id : aIds)
        {
            if (id != null && id.startsWith(aPrefix))
            {
                out.add(id);
            }
        }
        out.sort(Comparator.reverseOrder());
        return out;
    }
}
