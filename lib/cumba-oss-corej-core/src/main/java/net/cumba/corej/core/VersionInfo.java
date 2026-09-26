package net.cumba.corej.core;

import java.io.IOException;
import java.net.URL;
import java.util.Properties;

/**
 * Build/version metadata loaded from a module's filtered {@code version.properties}.
 *
 * <p>
 * Every corej jar carries its own {@code /version.properties} (filtered from the Maven
 * {@code ${revision}} at build time; the git-commit-id values it also carries were never read and
 * left the record with PLAN-retire-dead-multi-match-lookup U12). Because they all sit at the same
 * classpath location, {@link #forArtifact(String)} scans every copy on the classpath and selects
 * the one whose {@code artifactId} key matches, so each jar stays self-describing even on a mixed
 * classpath. {@link #forClass(Class)} selects instead the copy that sits in the same code location
 * as a given class, so a module can describe itself without naming its own artifact id — which
 * matters where byte-identical source is built under two artifact ids (a module and its OSS twin).
 *
 * <p>
 * Any field that is missing, blank, or still an unresolved Maven placeholder (for example a build
 * outside a git checkout, or an IDE build that did not filter resources) is reported as
 * {@code "unknown"}, so callers never deal with {@code null} or raw {@code ${...}} text.
 */
public record VersionInfo(String artifactId, String version)
{

    private static final String UNKNOWN = "unknown";

    private static final String RESOURCE = "version.properties";

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
     *            the Maven artifact id whose metadata to load (for example this module's own
     *            artifact id)
     * @return the matching metadata, or all-{@code "unknown"} when no jar on the classpath declares
     *         that artifact id
     */
    public static VersionInfo forArtifact(String anArtifactId)
    {
        try
        {
            var resources = VersionInfo.class.getClassLoader().getResources(RESOURCE);
            while (resources.hasMoreElements())
            {
                Properties p = load(resources.nextElement());
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


    /**
     * The metadata recorded in the {@code version.properties} that sits at the root of the same
     * code location (jar or class directory) as {@code aClass} — whatever artifact id it declares.
     * Lets byte-identical code in differently named artifacts (a module and its OSS twin) report
     * its own version without naming itself.
     *
     * <p>
     * The location is found by URL comparison through the class's own loader: the URL of
     * {@code aClass}'s {@code .class} resource, with the class path replaced by
     * {@code version.properties}, must equal one of the URLs the loader enumerates for that
     * resource. That needs no jar-URL construction or escaping, and it holds for a plain jar, an
     * exploded {@code target/classes} directory and a nested Spring Boot jar alike. ⚠ It is
     * <b>not</b> "the first {@code version.properties} on the classpath": a class from another jar
     * resolves to that jar's copy, or to {@code "unknown"} when that jar carries none.
     * </p>
     *
     * @param aClass
     *            the class whose code location is asked about
     * @return that metadata, or all-{@code "unknown"} when the class has no loader (bootstrap
     *         classes), its location cannot be determined, or that location carries no
     *         {@code version.properties}
     */
    public static VersionInfo forClass(Class<?> aClass)
    {
        ClassLoader loader = aClass.getClassLoader();
        String classFile = aClass.getName().replace('.', '/') + ".class";
        URL self = loader == null ? null : loader.getResource(classFile);
        if (self == null)
        {
            return UNKNOWN_INFO;
        }
        String s = self.toString();
        if (!s.endsWith(classFile))
        {
            return UNKNOWN_INFO;
        }
        String expected = s.substring(0, s.length() - classFile.length()) + RESOURCE;
        try
        {
            var resources = loader.getResources(RESOURCE);
            while (resources.hasMoreElements())
            {
                URL u = resources.nextElement();
                if (expected.equals(u.toString()))
                {
                    return of(load(u));
                }
            }
        }
        catch (IOException _)
        {
            return UNKNOWN_INFO;
        }
        return UNKNOWN_INFO;
    }


    /**
     * Reads one {@code version.properties}. The single stream-opening site of this class: the
     * resource is part of the running code's own artifact, never user data, so the URL stream's
     * cache is harmless here (see the meta repo's {@code check_url_streams.py}).
     */
    private static Properties load(URL aResource) throws IOException
    {
        Properties p = new Properties();
        try (var in = aResource.openStream())
        {
            p.load(in);
        }
        return p;
    }


    // package-private: lets tests exercise the placeholder/blank/null fallbacks directly
    static VersionInfo of(Properties aProps)
    {
        return new VersionInfo(prop(aProps, "artifactId"), prop(aProps, "version"));
    }


    private static String prop(Properties aProps, String aKey)
    {
        String v = aProps.getProperty(aKey);
        // null / blank / an unfiltered ${...} placeholder all count as absent
        return v == null || v.isBlank() || v.contains("${") ? UNKNOWN : v;
    }
}
