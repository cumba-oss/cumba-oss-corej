package net.cumba.corej.core.metadata.pickle;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The outcome of a {@link PickleCacheSeeder#seed(SeedOptions)} run.
 *
 * <p>
 * {@link #written()} and {@link #skipped()} carry endpoint paths (cache keys), so a caller can
 * assert exactly which entries a run touched — which is what makes a dry run worth trusting before
 * a real one.
 * </p>
 *
 * @param written
 *            endpoint paths written (or, on a dry run, that would be written).
 * @param skipped
 *            endpoint paths left alone because they were already present and
 *            {@code overwriteExisting} was off.
 * @param warnings
 *            non-fatal problems: a missing {@code _links.self.href}, an unparseable CT package id,
 *            an unreadable pickle entry.
 * @param standardsWritten
 *            count of {@code standards/*} entries in {@link #written()}.
 * @param modelsWritten
 *            count of {@code models/*} entries.
 * @param ctPackagesWritten
 *            count of CT package entries.
 * @param ctIndexWritten
 *            whether {@code /mdr/ct/packages} was (re)written.
 * @param sourceRef
 *            what the run seeded <em>from</em>, as reported by {@link PickleSource#provenance()} —
 *            for the HTTP archive source the repository, the resolved ref and, when the forge
 *            advertised it, HEAD's commit id. {@code null} when the source cannot identify itself
 *            (a plain local directory). A seeded cache carries no origin marker of its own, so
 *            without this the run leaves no record of which upstream revision it reproduced.
 */
public record SeedReport(List<String> written, List<String> skipped, List<String> warnings,
        int standardsWritten, int modelsWritten, int ctPackagesWritten, boolean ctIndexWritten,
        @Nullable String sourceRef)
{

    /** Defensively copies the lists. */
    public SeedReport
    {
        written = List.copyOf(Objects.requireNonNull(written, "written"));
        skipped = List.copyOf(Objects.requireNonNull(skipped, "skipped"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }


    /**
     * A one-line summary suitable for CLI output or a log line.
     *
     * @return the summary.
     */
    public String summary()
    {
        return "seeded %d standards, %d models, %d CT packages%s (%d skipped, %d warnings)%s"
                .formatted(standardsWritten, modelsWritten, ctPackagesWritten,
                        ctIndexWritten ? " + CT index" : "", skipped.size(), warnings.size(),
                        sourceRef == null ? "" : " from " + sourceRef);
    }
}
