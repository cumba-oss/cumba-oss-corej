package net.cumba.corej.core.metadata.store.seed;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The outcome of one store seed run.
 *
 * <p>
 * The fetched/carried split is what makes the incremental acquisition of plan §5.2 observable: a
 * re-seed that fetched everything it could have carried is a defect this report makes visible, and
 * the tests assert on exactly these counters.
 * </p>
 *
 * @param productsSeeded
 *            products (IG + model) projected fresh from the source
 * @param productsCarried
 *            products carried forward unchanged from the existing store because the source no
 *            longer offers their key
 * @param ctPackagesFetched
 *            CT packages acquired from the source this run
 * @param ctPackagesCarried
 *            CT packages carried forward from the existing store without touching the source — they
 *            are immutable once published, so presence is sufficient (plan §5.2)
 * @param ctPackagesMissed
 *            ids that are published (and therefore enumerated in the manifest) but that neither the
 *            source could deliver nor the existing store held — the store answers ABSENT for these,
 *            and a later run naming one aborts rather than degrades
 * @param warnings
 *            non-fatal problems: unreadable source entries, malformed ids, empty projections
 * @param sourceRef
 *            what was seeded from, as far as the source can say (a resolved archive ref, a base
 *            URL, a directory); {@code null} when it cannot
 * @param target
 *            the store file written
 * @param storeBytes
 *            the written store's size in bytes
 */
public record StoreSeedReport(int productsSeeded, int productsCarried, int ctPackagesFetched,
        int ctPackagesCarried, List<String> ctPackagesMissed, List<String> warnings,
        @Nullable String sourceRef, Path target, long storeBytes)
{

    /** Defensively copies the lists. */
    public StoreSeedReport
    {
        ctPackagesMissed = List
                .copyOf(Objects.requireNonNull(ctPackagesMissed, "ctPackagesMissed"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        Objects.requireNonNull(target, "target");
    }


    /**
     * A one-line summary suitable for CLI output or a log line.
     *
     * @return the summary
     */
    public String summary()
    {
        return ("seeded %d products (%d carried), %d CT packages fetched, %d carried, %d missing"
                + " -> %s (%d bytes, %d warnings)%s").formatted(productsSeeded, productsCarried,
                        ctPackagesFetched, ctPackagesCarried, ctPackagesMissed.size(), target,
                        storeBytes, warnings.size(), sourceRef == null ? "" : " from " + sourceRef);
    }
}
