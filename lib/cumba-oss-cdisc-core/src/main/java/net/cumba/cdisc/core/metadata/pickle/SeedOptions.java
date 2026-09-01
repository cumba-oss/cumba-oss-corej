package net.cumba.cdisc.core.metadata.pickle;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Inputs for {@link PickleCacheSeeder#seed(SeedOptions)}.
 *
 * @param source
 *            supplies the directory of {@code *.pkl} files.
 * @param targetCacheDir
 *            the web-api cache directory to write into (the same directory {@code GzipFileApiCache}
 *            reads).
 * @param apiBaseUrl
 *            the effective CDISC Library base URL. Only its <em>path</em> matters: it becomes the
 *            cache-key prefix, so {@code https://api.library.cdisc.org/api/} makes
 *            {@code /mdr/sdtmig/3-4} land in {@code api_mdr_sdtmig_3-4%3Fexpand%3Dtrue.json.gz} —
 *            the encoded query is there because the seeder keys by the request CoreJ issues, not by
 *            the bare path the pickle was fetched under. Never hardcode the prefix — a different
 *            base URL must produce different file names.
 * @param overwriteExisting
 *            {@code false} (default) skips endpoints already present, so a seeded run never
 *            rewrites a real API response; {@code true} replaces them.
 * @param writeMeta
 *            write the {@code .meta} sidecar carrying {@code x-cache-source: seeded-from-pickle},
 *            making provenance auditable per entry.
 * @param dryRun
 *            report what would be written without touching the filesystem.
 */
public record SeedOptions(PickleSource source, Path targetCacheDir, String apiBaseUrl,
        boolean overwriteExisting, boolean writeMeta, boolean dryRun)
{

    /** Validates the mandatory fields. */
    public SeedOptions
    {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(targetCacheDir, "targetCacheDir");
        Objects.requireNonNull(apiBaseUrl, "apiBaseUrl");
    }


    /**
     * Starts a builder with the defaults: skip-existing, write {@code .meta}, not a dry run.
     *
     * @param aSource
     *            the pickle source.
     * @param aTargetCacheDir
     *            the cache directory to write into.
     * @param aApiBaseUrl
     *            the effective base URL (see {@link #apiBaseUrl()}).
     * @return the builder.
     */
    public static Builder builder(PickleSource aSource, Path aTargetCacheDir, String aApiBaseUrl)
    {
        return new Builder(aSource, aTargetCacheDir, aApiBaseUrl);
    }

    /** Fluent builder for {@link SeedOptions}. */
    public static final class Builder
    {

        private final PickleSource source;

        private final Path targetCacheDir;

        private final String apiBaseUrl;

        private boolean overwriteExisting;

        private boolean writeMeta = true;

        private boolean dryRun;

        private Builder(PickleSource aSource, Path aTargetCacheDir, String aApiBaseUrl)
        {
            source = aSource;
            targetCacheDir = aTargetCacheDir;
            apiBaseUrl = aApiBaseUrl;
        }


        /**
         * Replaces entries already present instead of skipping them.
         *
         * @param aOverwrite
         *            whether to replace entries already present.
         * @return this builder.
         */
        public Builder overwriteExisting(boolean aOverwrite)
        {
            overwriteExisting = aOverwrite;
            return this;
        }


        /**
         * Controls whether {@code .meta} sidecars are written alongside each entry.
         *
         * @param aWriteMeta
         *            whether to write {@code .meta} sidecars.
         * @return this builder.
         */
        public Builder writeMeta(boolean aWriteMeta)
        {
            writeMeta = aWriteMeta;
            return this;
        }


        /**
         * Reports what would be written without touching the filesystem.
         *
         * @param aDryRun
         *            whether to report without writing.
         * @return this builder.
         */
        public Builder dryRun(boolean aDryRun)
        {
            dryRun = aDryRun;
            return this;
        }


        /**
         * Builds the immutable options.
         *
         * @return the immutable options.
         */
        public SeedOptions build()
        {
            return new SeedOptions(source, targetCacheDir, apiBaseUrl, overwriteExisting, writeMeta,
                    dryRun);
        }
    }
}
