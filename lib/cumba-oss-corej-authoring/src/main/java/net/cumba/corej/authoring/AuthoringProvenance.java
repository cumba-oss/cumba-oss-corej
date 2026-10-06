package net.cumba.corej.authoring;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Optional;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import net.cumba.corej.core.expr.convert.CheckLevelNodes;

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
 *
 * <p>
 * ⚠ Two jars, both checked (pack-2 review 8a-A F5): the assembly runs in this module <em>and</em>
 * in the engine's core module ({@code CheckLevelNodes}, the loader), installed separately — a fresh
 * authoring jar over a stale core one is the same hazard one level down. And a jar built from a
 * working tree with uncommitted changes carries its HEAD's id but not its HEAD's bytes, so
 * {@code git.dirty} is recorded (the parent pom includes it) and a dirty jar is refused. ⚠ For
 * git-commit-id an <b>untracked</b> file makes the tree dirty too, so a stray scratch file in the
 * {@code cumba-corej} checkout builds a jar this refuses under
 * {@code -Dcorej.authoring.expectedCommit} — the refusal says "WITH uncommitted changes"; commit or
 * remove the file and install again (confirmation look, F5 follow-up).
 * </p>
 */
public final class AuthoringProvenance
{

    /** The system property naming the commit the corpus generator expects this jar to carry. */
    public static final String EXPECTED_COMMIT_PROPERTY = "corej.authoring.expectedCommit";

    private static final String RESOURCE_NAME = "git.properties";

    private static final String COMMIT = "git.commit.id";

    private static final String DIRTY = "git.dirty";

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
        return commitOf(gitPropertiesOf(AuthoringProvenance.class));
    }


    /** Whether this jar was built from a working tree with uncommitted changes. */
    public static boolean dirty()
    {
        return isDirty(gitPropertiesOf(AuthoringProvenance.class));
    }


    private static Optional<String> commitOf(Properties aProps)
    {
        String id = aProps.getProperty(COMMIT);
        return id == null || id.isBlank() ? Optional.empty() : Optional.of(id.strip());
    }


    private static boolean isDirty(Properties aProps)
    {
        return Boolean.parseBoolean(String.valueOf(aProps.getProperty(DIRTY)).strip());
    }


    /**
     * The {@code git.properties} that sits beside {@code aClass} — in its jar, or in its
     * {@code target/classes} directory — never the first one on the classpath. Empty when there is
     * none, or when the code source is no file this can open (a class nested in a fat jar).
     *
     * <p>
     * ⚠ {@code getResourceAsStream("/git.properties")} is wrong here, measured 2026-10-06: the
     * corpus build generates its own {@code target/classes/git.properties} (git-commit-id runs in
     * that repository too) and it precedes every jar on the test classpath, so the generator
     * printed the <em>corpus</em> commit as the engine's. The code source is the one location that
     * cannot be shadowed.
     * </p>
     */
    static Properties gitPropertiesOf(Class<?> aClass)
    {
        Properties props = new Properties();
        try
        {
            CodeSource source = aClass.getProtectionDomain().getCodeSource();
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
        catch (IOException | URISyntaxException | IllegalArgumentException
                | FileSystemNotFoundException | UnsupportedOperationException | SecurityException _)
        {
            // FileSystemNotFoundException / IllegalArgumentException for a URI no default file
            // system serves (review 8a-A F5), UnsupportedOperationException from toFile() when the
            // code source is a file inside an open zip file system (a jar nested in a jar;
            // confirmation look): no provenance, not a crash
            return new Properties();
        }
    }


    /** The abbreviated commit id ({@code -dirty} appended for a dirty build), or "unknown". */
    public static String describe()
    {
        return commitId().map(
                id -> (id.length() > 12 ? id.substring(0, 12) : id) + (dirty() ? "-dirty" : ""))
                .orElse("unknown");
    }


    /**
     * Refuse to proceed unless both engine jars the assembly runs in — this one and the core
     * module's — were built from {@code aExpectedCommit}, from a clean tree. A full or abbreviated
     * id is accepted (prefix match, at least 7 characters).
     *
     * @param aExpectedCommit
     *            the commit the caller requires
     * @throws IllegalStateException
     *             when a jar carries no provenance, names another commit, or was built dirty
     */
    public static void requireCommit(String aExpectedCommit)
    {
        check(aExpectedCommit, "authoring", gitPropertiesOf(AuthoringProvenance.class));
        check(aExpectedCommit, "core", gitPropertiesOf(CheckLevelNodes.class));
    }


    /**
     * The check {@link #requireCommit} runs per jar, over its {@code git.properties}.
     *
     * @param aExpectedCommit
     *            the commit the caller requires
     * @param aJar
     *            which engine jar ({@code authoring} / {@code core}), for messages
     * @param aProps
     *            its {@code git.properties}
     * @throws IllegalStateException
     *             as {@link #requireCommit}
     */
    static void check(String aExpectedCommit, String aJar, Properties aProps)
    {
        String expected = aExpectedCommit.strip();
        if (expected.length() < 7)
        {
            throw new IllegalStateException("expected commit '" + expected
                    + "' is too short to identify a commit (at least 7 characters)");
        }
        String actual = commitOf(aProps).orElseThrow(() -> new IllegalStateException("the engine's "
                + aJar
                + " jar carries no git.properties, so its engine commit cannot be verified against "
                + expected));
        if (!actual.startsWith(expected) && !expected.startsWith(actual))
        {
            throw new IllegalStateException("the engine's " + aJar + " jar on the classpath was"
                    + " built from commit " + actual + ", not the expected " + expected
                    + " — install the current engine before regenerating the corpus");
        }
        if (isDirty(aProps))
        {
            throw new IllegalStateException("the engine's " + aJar + " jar on the classpath was"
                    + " built from commit " + actual
                    + " WITH uncommitted changes, so its bytes are not that"
                    + " commit's — commit the engine and install it again before regenerating the"
                    + " corpus");
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
