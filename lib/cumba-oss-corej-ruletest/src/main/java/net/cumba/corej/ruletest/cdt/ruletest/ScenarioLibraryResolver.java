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
 * Two entry points. {@link #resolve(LibraryRef)} finds the store itself through
 * {@link StoreMetadataProviderFactory#resolveConfiguredFile} — empty when no
 * {@code CDISC_METADATA_STORE} (env) / {@code cdisc.metadata.store} (sysprop) names a store file —
 * and opens it per call. {@link #resolve(LibraryRef, StoreMetadataProviderFactory)} takes a store
 * the caller already holds and reads no ambient setting at all: the path for a caller that owns its
 * store (the rule corpus's suites seed one per JVM), so it never has to publish that store into the
 * global system property and never reopens it per scenario. Callers should treat
 * {@link Optional#empty()} as "skip this scenario", exactly as they did for the missing-API-key
 * gate this replaces.
 * </p>
 *
 * <p>
 * ⚠ A CT package the ref pins ({@code ct=}) but the store lacks does <b>not</b> empty the result:
 * the factory substitutes an empty package for the ref's own CT root (logged as a WARNING) and
 * drops a missing {@code sdtmct} fallback of an ADaM ref, so the rule runs against no terms of that
 * package. A caller for which that would be a vacuous pass checks
 * {@link StoreMetadataProviderFactory#presence} for each pinned id first.
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
     * is configured, it cannot be opened, or it lacks the referenced product. An unreadable store
     * is logged and treated as unavailable (the caller's skip gate), preserving the no-throw
     * contract of the {@code buildOrDegraded()} call this replaces — with ONE exception: an
     * OLD-FORMAT store THROWS (PLAN-define-ct-evaluation T1-10 a / S17). Under a stale store
     * "unavailable" would skip every {@code #library-ref} scenario and leave the corpus gate green
     * over a store nothing can read; a format bump is a stop, not a skip.
     *
     * @throws java.io.UncheckedIOException
     *             when the configured store is of another format version, naming the store and the
     *             re-seed
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
        catch (net.cumba.corej.core.metadata.store.StoreFormatException e)
        {
            throw new java.io.UncheckedIOException("Configured metadata store " + file
                    + " cannot serve #library-ref scenarios: " + e.getMessage()
                    + " (an old-format store fails the scenario; it never skips it)", e);
        }
        catch (IOException e)
        {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Configured metadata store {0} cannot be opened for a #library-ref scenario "
                            + "({1}); the scenario will be skipped.",
                    file, e.getMessage());
            return Optional.empty();
        }
        return resolve(aRef, factory);
    }


    /**
     * Resolve {@code aRef} against a store the caller has already opened. Reads no environment
     * variable and no system property, and neither opens nor closes the store — the caller owns it,
     * so one factory can serve every scenario of a run.
     *
     * @param aRef
     *            the scenario's {@code #library-ref}
     * @param aFactory
     *            a factory over the store to resolve against
     * @return the provider, or {@link Optional#empty()} when the store lacks the referenced product
     *         (IG, or a declared ADaM product). A pinned CT package the store lacks is NOT a reason
     *         for empty — see the class javadoc.
     */
    public static Optional<MetadataProvider> resolve(LibraryRef aRef,
            StoreMetadataProviderFactory aFactory)
    {
        String standard = aRef.getStandard().toLowerCase(Locale.ROOT);
        if (standard.startsWith("adam"))
        {
            // The single declared product an omitted --metadata-products implies for this pair.
            List<String> declared = List
                    .of(MetadataProductKeys.standardsKey(aRef.getStandard(), aRef.getVersion()));
            return aFactory.forAdam(aRef.getStandard(), aRef.getVersion(), declared,
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
        return aFactory.forSdtm(aRef.getStandard(), aRef.getVersion(), ctIds);
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
