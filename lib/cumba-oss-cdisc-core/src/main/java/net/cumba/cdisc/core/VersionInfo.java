package net.cumba.cdisc.core;

import java.io.IOException;
import java.util.Properties;

/**
 * Build/version metadata loaded from a module's filtered {@code version.properties}.
 *
 * <p>
 * Every corej jar carries its own {@code /version.properties} (filtered from the Maven
 * {@code ${revision}} and the git-commit-id values at build time). Because they all sit at the same
 * classpath location, {@link #forArtifact(String)} scans every copy on the classpath and selects
 * the one whose {@code artifactId} key matches, so each jar stays self-describing even on a mixed
 * classpath.
 *
 * <p>
 * Any field that is missing, blank, or still an unresolved Maven placeholder (for example a build
 * outside a git checkout, or an IDE build that did not filter resources) is reported as
 * {@code "unknown"}, so callers never deal with {@code null} or raw {@code ${...}} text.
 */
public record VersionInfo(String artifactId, String version, String gitCommitHashShort,
        String gitBranch, String buildTimestamp)
{

    private static final String UNKNOWN = "unknown";

    /**
     * All-{@code "unknown"} metadata, returned when no match is found or the classpath is
     * unreadable.
     */
    private static final VersionInfo UNKNOWN_INFO = of(new Properties());

    /**
     * Loads the metadata of {@code anArtifactId} by scanning every {@code version.properties} on
     * the classpath and matching its recorded artifact id. Returns all-{@code "unknown"} metadata
     * when no match is found.
     *
     * @param anArtifactId
     *            the Maven artifact id whose metadata to load (for example
     *            {@code "corej-cdisc-core"})
     * @return the matching metadata, or all-{@code "unknown"} when no jar on the classpath declares
     *         that artifact id
     */
    public static VersionInfo forArtifact(String anArtifactId)
    {
        try
        {
            var resources = VersionInfo.class.getClassLoader().getResources("version.properties");
            while (resources.hasMoreElements())
            {
                Properties p = new Properties();
                try (var in = resources.nextElement().openStream())
                {
                    p.load(in);
                }
                if (anArtifactId.equals(p.getProperty("artifactId")))
                {
                    return of(p);
                }
            }
        }
        catch (IOException _)
        {
            return UNKNOWN_INFO;
        }
        return UNKNOWN_INFO;
    }


    // package-private: lets tests exercise the placeholder/blank/null fallbacks directly
    static VersionInfo of(Properties aProps)
    {
        return new VersionInfo(prop(aProps, "artifactId"), prop(aProps, "version"),
                prop(aProps, "gitCommitHashShort"), prop(aProps, "gitBranch"),
                prop(aProps, "buildTimestamp"));
    }


    private static String prop(Properties aProps, String aKey)
    {
        String v = aProps.getProperty(aKey);
        // null / blank / an unfiltered ${...} placeholder all count as absent
        return v == null || v.isBlank() || v.contains("${") ? UNKNOWN : v;
    }
}
