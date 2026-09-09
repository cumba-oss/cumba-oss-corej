package net.cumba.corej.core.metadata.store.seed;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import net.cumba.corej.core.metadata.store.StoreProvenance;
import org.jspecify.annotations.Nullable;

/**
 * Inputs for one store seed run ({@link PickleStoreSeeder#seed(StoreSeedOptions)} /
 * {@link WebApiStoreSeeder#seed(StoreSeedOptions)}).
 *
 * @param target
 *            the store file to (re)build, e.g. {@code ~/.cumbaDataBrowser/metadata-cache.zip}. An
 *            existing store here is the re-seed baseline (plan §5.2): content the source already
 *            provided is carried forward instead of re-fetched, and the file is only ever replaced
 *            atomically as a whole.
 * @param refresh
 *            {@code true} ignores the existing store entirely — every entry is re-acquired from the
 *            source. This is the {@code --refresh} of plan §5.2; the default {@code false} skips
 *            re-fetching CT packages already present (they are immutable once published).
 * @param fetchedAt
 *            the acquisition timestamp recorded in the manifest's provenance line, as a
 *            caller-supplied display string. The seeders never read a clock themselves — that is
 *            what keeps a seed from identical input byte-identical (plan §5.1); the default is the
 *            empty string. Pass e.g. {@code Instant.now().toString()} when provenance should carry
 *            a date.
 * @param provenanceOverride
 *            when non-null, recorded in the manifest VERBATIM in place of the provenance the seeder
 *            would derive (its own source line merged with the existing store's). This is for
 *            callers that know the origin better than the seeder does — and it is what lets the
 *            byte-identity conformance test drive both seeders to a fully identical file,
 *            provenance included.
 */
public record StoreSeedOptions(Path target, boolean refresh, String fetchedAt,
        @Nullable List<StoreProvenance> provenanceOverride)
{

    /** Validates the mandatory fields and defensively copies the override. */
    public StoreSeedOptions
    {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(fetchedAt, "fetchedAt");
        provenanceOverride = provenanceOverride == null ? null : List.copyOf(provenanceOverride);
    }


    /**
     * Options with the defaults: no refresh, empty {@code fetchedAt}, seeder-derived provenance.
     *
     * @param aTarget
     *            the store file to (re)build
     * @return the options
     */
    public static StoreSeedOptions of(Path aTarget)
    {
        return new StoreSeedOptions(aTarget, false, "", null);
    }


    /**
     * These options with {@code refresh} replaced.
     *
     * @param aRefresh
     *            whether to ignore the existing store
     * @return the derived options
     */
    public StoreSeedOptions withRefresh(boolean aRefresh)
    {
        return new StoreSeedOptions(target, aRefresh, fetchedAt, provenanceOverride);
    }


    /**
     * These options with {@code fetchedAt} replaced.
     *
     * @param aFetchedAt
     *            the provenance timestamp display string
     * @return the derived options
     */
    public StoreSeedOptions withFetchedAt(String aFetchedAt)
    {
        return new StoreSeedOptions(target, refresh, aFetchedAt, provenanceOverride);
    }


    /**
     * These options with the provenance override replaced.
     *
     * @param aProvenance
     *            the manifest provenance to record verbatim, or {@code null} to derive
     * @return the derived options
     */
    public StoreSeedOptions withProvenanceOverride(@Nullable List<StoreProvenance> aProvenance)
    {
        return new StoreSeedOptions(target, refresh, fetchedAt, aProvenance);
    }
}
