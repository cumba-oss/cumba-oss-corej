package net.cumba.corej.ruletest.cdt.ruletest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Resolves a scenario's sidecars — {@code #library-include} and {@code #define-xml} — <b>inside a
 * tree it was given</b>, and nothing else.
 *
 * <p>
 * ⛔ Confinement is a requirement of the scenario runner, not of its callers
 * ({@code PLAN-rule-authoring-api} §3.1, editor review H1). Until this class the two directives
 * resolved {@code base.resolve(rel)} with no containment — an absolute path won, {@code ..} climbed
 * — which was harmless for a corpus checked out by its own developers and is not for an editor that
 * runs user-edited buffers, also anonymously. The corpus passes a resolver rooted at its suites
 * tree (every corpus sidecar sits beside its scenario), so its behaviour is unchanged.
 * </p>
 *
 * <h2>The rules (pack-2 review 8a-B H1 / M3 / L4 / L5)</h2>
 *
 * <ul>
 * <li>the payload is a <b>valid, relative</b> path — an invalid one (a NUL, on Windows one of
 * {@code *?<>|"}) is an {@link IOException} like every other refusal, never an
 * {@code InvalidPathException};</li>
 * <li>both sides are <b>real paths</b>: the root once, the scenario's directory per call — so a
 * checkout reached through a link resolves its own sidecars (M3), and a scenario directory that is
 * itself a link out of the tree is refused;</li>
 * <li>the lexically normalised candidate must lie under the root, and <b>every component</b> from
 * the root down to it is checked: no symbolic link (read without following), and its real path
 * (links, junctions and other reparse points followed) must be the very path that was walked — a
 * junction is no symbolic link to {@code isSymbolicLink}, but it is caught by the second test
 * (H1);</li>
 * <li>the sidecar is a regular file, opened with {@link LinkOption#NOFOLLOW_LINKS}, and the walk is
 * repeated after the open: a component swapped for a link between the check and the open is refused
 * rather than read. ⚠ That narrows the race, it cannot close it — Java offers no
 * {@code openat}-style handle on every platform. A host whose tree is writable by the people whose
 * buffers it runs must not rely on path checks alone;</li>
 * <li>no message names file content or an absolute path — paths are shown relative to the root
 * (L5).</li>
 * </ul>
 */
public final class SidecarResolver
{

    /** The prefix of the one refusal {@link #includeResolver} turns into "absent". */
    static final String NOT_FOUND = "sidecar not found";

    private final Path root;

    private SidecarResolver(Path aRoot)
    {
        root = aRoot;
    }


    /**
     * A resolver confined to {@code aRoot} (a directory; resolved to its real path once).
     *
     * @param aRoot
     *            the scenario tree
     * @return the resolver
     * @throws IOException
     *             when the root is not an existing directory
     */
    public static SidecarResolver rootedAt(Path aRoot) throws IOException
    {
        Path real = aRoot.toRealPath();
        if (!Files.isDirectory(real))
        {
            throw new IOException("sidecar root is not a directory: " + aRoot.getFileName());
        }
        return new SidecarResolver(real);
    }


    /** The confining tree (real path). */
    public Path root()
    {
        return root;
    }


    /**
     * Resolve a sidecar named by a scenario at {@code aScenarioFile}.
     *
     * @param aScenarioFile
     *            the scenario's own file (its parent is the base the sidecar is relative to); a
     *            scenario held in memory passes the file it would have been saved as — its
     *            directory must exist
     * @param aRelative
     *            the directive's payload
     * @return the sidecar's real path, inside the root
     * @throws IOException
     *             when the payload is no valid relative path, escapes the root, passes through a
     *             link, is not a regular file, or does not exist
     */
    public Path resolve(Path aScenarioFile, String aRelative) throws IOException
    {
        Objects.requireNonNull(aRelative, "sidecar path");
        Path rel;
        try
        {
            rel = Path.of(aRelative);
        }
        catch (InvalidPathException _)
        {
            throw new IOException(
                    "sidecar path is not a valid path: " + aRelative.replace('\0', '?'));
        }
        if (aRelative.isEmpty() || rel.isAbsolute() || rel.getRoot() != null
                || aRelative.startsWith("/") || aRelative.startsWith("\\"))
        {
            throw new IOException("sidecar path must be relative: " + aRelative);
        }
        Path base = realBase(aScenarioFile, aRelative);
        Path candidate = base.resolve(rel).normalize();
        if (!candidate.startsWith(root))
        {
            throw new IOException("sidecar path escapes the scenario tree: " + aRelative + " (from "
                    + shown(base) + ")");
        }
        verifyWalk(candidate, aRelative, base);
        return candidate;
    }


    /** The scenario's directory as a real path inside the root. */
    private Path realBase(Path aScenarioFile, String aRelative) throws IOException
    {
        Path parent = aScenarioFile.toAbsolutePath().getParent();
        if (parent == null)
        {
            throw new IOException(
                    "scenario file has no parent directory: " + aScenarioFile.getFileName());
        }
        Path base;
        try
        {
            base = parent.toRealPath();
        }
        catch (NoSuchFileException _)
        {
            throw new IOException(
                    "the scenario's directory does not exist: " + aScenarioFile.getFileName());
        }
        if (!base.startsWith(root))
        {
            throw new IOException("sidecar path escapes the scenario tree: " + aRelative
                    + " (the scenario's directory is outside it)");
        }
        return base;
    }


    /**
     * Every component from the root down to {@code aCandidate}: present, no link, and its real path
     * the walked one; the last a regular file.
     */
    private void verifyWalk(Path aCandidate, String aRelative, Path aBase) throws IOException
    {
        Path current = root;
        Path tail = root.relativize(aCandidate);
        int count = tail.getNameCount();
        for (int i = 0; i < count; i++)
        {
            current = current.resolve(tail.getName(i));
            BasicFileAttributes attrs;
            try
            {
                attrs = Files.readAttributes(current, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
            }
            catch (NoSuchFileException _)
            {
                throw new IOException(
                        NOT_FOUND + ": " + aRelative + " (from " + shown(aBase) + ")");
            }
            if (attrs.isSymbolicLink())
            {
                throw new IOException("sidecar path must not pass through a symbolic link: "
                        + aRelative + " (" + shown(current) + ")");
            }
            Path real = current.toRealPath();
            if (!current.equals(real))
            {
                Path realParent = real.getParent();
                boolean caseOnly = realParent != null && realParent.equals(current.getParent())
                        && String.valueOf(real.getFileName())
                                .equalsIgnoreCase(String.valueOf(current.getFileName()));
                throw new IOException(caseOnly
                        ? "sidecar path differs in case from the file on disk: " + aRelative + " ("
                                + shown(real) + ")"
                        : "sidecar path must not pass through a symbolic link, junction or"
                                + " other redirection: " + aRelative + " (" + shown(current) + ")");
            }
            if (i == count - 1 && !attrs.isRegularFile())
            {
                throw new IOException("sidecar is not a regular file: " + aRelative);
            }
        }
        if (count == 0)
        {
            throw new IOException("sidecar is not a regular file: " + aRelative);
        }
    }


    /** A path inside the root, shown relative to it ({@code .} for the root itself). */
    private String shown(Path aPath)
    {
        if (!aPath.startsWith(root))
        {
            return String.valueOf(aPath.getFileName());
        }
        String rel = root.relativize(aPath).toString().replace('\\', '/');
        return rel.isEmpty() ? "." : rel;
    }


    /**
     * The scenario's path for messages: relative to the root when it lies inside it, else its file
     * name — never an absolute server path.
     *
     * @param aScenarioFile
     *            the scenario's file
     * @return the label
     */
    public String label(Path aScenarioFile)
    {
        Path abs = aScenarioFile.toAbsolutePath().normalize();
        Path parent = abs.getParent();
        Path name = abs.getFileName();
        Path realParent = parent == null || name == null ? null : realPathOrNull(parent);
        if (realParent != null && name != null && realParent.startsWith(root))
        {
            return shown(realParent.resolve(name));
        }
        return String.valueOf(name);
    }


    /** The real path, or {@code null} when it cannot be resolved (the label then falls back). */
    private static @Nullable Path realPathOrNull(Path aPath)
    {
        try
        {
            return aPath.toRealPath();
        }
        catch (IOException _)
        {
            return null;
        }
    }


    /**
     * The {@code #library-include} resolver for a scenario at {@code aScenarioFile}: opens the
     * sidecar through {@link #open}; a missing sidecar answers {@code null} as the parser's
     * contract says, every other refusal is an {@link IOException}.
     *
     * @param aScenarioFile
     *            the scenario's file
     * @return the resolver the parser takes
     */
    public RuleTestCdt.IncludeResolver includeResolver(Path aScenarioFile)
    {
        return rel ->
        {
            try
            {
                return open(aScenarioFile, rel);
            }
            catch (IOException e)
            {
                if (e.getMessage() != null && e.getMessage().startsWith(NOT_FOUND))
                {
                    return null;
                }
                throw e;
            }
        };
    }


    /**
     * The sidecar's bytes: resolved, opened without following a final link, and the path verified
     * again once the stream is open.
     *
     * @param aScenarioFile
     *            the scenario's file
     * @param aRelative
     *            the directive's payload
     * @return an open stream the caller closes
     * @throws IOException
     *             as {@link #resolve}
     */
    public InputStream open(Path aScenarioFile, String aRelative) throws IOException
    {
        Path file = resolve(aScenarioFile, aRelative);
        InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
        try
        {
            Path parent = file.getParent();
            verifyWalk(file, aRelative, parent == null ? root : parent);
        }
        catch (IOException e)
        {
            in.close();
            throw e;
        }
        return in;
    }


    @Override
    public String toString()
    {
        return "SidecarResolver[" + root + "]";
    }
}
