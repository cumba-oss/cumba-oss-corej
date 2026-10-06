package net.cumba.corej.authoring;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Optional;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The build provenance of this module's jar — the engine commit it was built from, read from the
 * {@code git.properties} the parent pom's git-commit-id plugin writes into every module.
 *
 * <p>
 * ⭐ <b>Why this exists.</b> Since {@code PLAN-rule-authoring-api} the assembled corpus bytes depend
 * on this jar: the {@code Authorities} join and the release shaping run here, so a corpus
 * regenerated against a stale installed engine can differ from the same YAML regenerated against
 * the current one — silently. {@code scripts/lib/corpus-gen-env.sh} in {@code cumba-corej-rules}
 * retired its stale-engine guard on 2026-09-08 precisely because nothing engine-derived remained in
 * the package; that reasoning died with this module, so the corpus generator asks this class which
 * commit produced its assembler and records it. With {@code -Dcorej.authoring.expectedCommit} set,
 * {@link #requireCommit(String)} refuses a mismatch before a single file is written.
 * </p>
 */
public final class AuthoringProvenance
{

    /** The system property naming the commit the corpus generator expects this jar to carry. */
    public static final String EXPECTED_COMMIT_PROPERTY = "corej.authoring.expectedCommit";

    private static final String RESOURCE_NAME = "git.properties";

    private AuthoringProvenance()
    {
    }


    /**
     * The full commit id of the {@code cumba-corej} tree this jar was built from.
     *
     * @return the id, or empty when the jar carries no {@code git.properties} (a build outside a
     *         git checkout) or it names no commit
     */
    public static Optional<String> commitId()
    {
        Properties props;
        try
        {
            props = loadOwnGitProperties();
        }
        catch (IOException | URISyntaxException _)
        {
            return Optional.empty();
        }
        String id = props.getProperty("git.commit.id");
        return id == null || id.isBlank() ? Optional.empty() : Optional.of(id.strip());
    }


    /**
     * Load the {@code git.properties} that sits beside THIS class — in its jar, or in its
     * {@code target/classes} directory — never the first one on the classpath. Empty when there is
     * none.
     *
     * <p>
     * ⚠ {@code getResourceAsStream("/git.properties")} is wrong here, measured 2026-10-06: the
     * corpus build generates its own {@code target/classes/git.properties} (git-commit-id runs in
     * that repository too) and it precedes every jar on the test classpath, so the generator
     * printed the <em>corpus</em> commit as the engine's. The code source is the one location that
     * cannot be shadowed.
     * </p>
     */
    private static Properties loadOwnGitProperties() throws IOException, URISyntaxException
    {
        Properties props = new Properties();
        CodeSource source = AuthoringProvenance.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null)
        {
            return props;
        }
        Path location = Path.of(source.getLocation().toURI());
        if (Files.isDirectory(location))
        {
            Path file = location.resolve(RESOURCE_NAME);
            if (Files.isRegularFile(file))
            {
                try (InputStream in = Files.newInputStream(file))
                {
                    props.load(in);
                }
            }
            return props;
        }
        if (!Files.isRegularFile(location))
        {
            return props;
        }
        try (JarFile jar = new JarFile(location.toFile()))
        {
            JarEntry entry = jar.getJarEntry(RESOURCE_NAME);
            if (entry != null)
            {
                try (InputStream in = jar.getInputStream(entry))
                {
                    props.load(in);
                }
            }
        }
        return props;
    }


    /** The abbreviated commit id, or {@code "unknown"}. */
    public static String describe()
    {
        return commitId().map(id -> id.length() > 12 ? id.substring(0, 12) : id).orElse("unknown");
    }


    /**
     * Refuse to proceed unless this jar was built from {@code aExpectedCommit}. A full or
     * abbreviated id is accepted (prefix match, at least 7 characters).
     *
     * @param aExpectedCommit
     *            the commit the caller requires
     * @throws IllegalStateException
     *             when the jar carries no provenance or names another commit
     */
    public static void requireCommit(String aExpectedCommit)
    {
        String expected = aExpectedCommit.strip();
        if (expected.length() < 7)
        {
            throw new IllegalStateException("expected commit '" + expected
                    + "' is too short to identify a commit (at least 7 characters)");
        }
        String actual = commitId().orElseThrow(() -> new IllegalStateException(
                "the cumba-corej-authoring jar carries no git.properties, so its engine"
                        + " commit cannot be verified against " + expected));
        if (!actual.startsWith(expected) && !expected.startsWith(actual))
        {
            throw new IllegalStateException("the cumba-corej-authoring jar on the classpath was"
                    + " built from commit " + actual + ", not the expected " + expected
                    + " — install the current engine before regenerating the corpus");
        }
    }


    /**
     * {@link #requireCommit(String)} when {@link #EXPECTED_COMMIT_PROPERTY} is set; a no-op
     * otherwise.
     */
    public static void requireExpectedCommitIfConfigured()
    {
        String expected = System.getProperty(EXPECTED_COMMIT_PROPERTY);
        if (expected != null && !expected.isBlank())
        {
            requireCommit(expected);
        }
    }
}
