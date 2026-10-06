package net.cumba.corej.ruletest.cdt.ruletest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Resolves a scenario's sidecars — {@code #library-include} and {@code #define-xml} — <b>inside a
 * tree it was given</b>, and nothing else.
 *
 * <p>
 * ⛔ Confinement is a requirement of the scenario runner, not of its callers
 * ({@code PLAN-rule-authoring-api} §3.1, editor review H1). Until this class the two directives
 * resolved {@code base.resolve(rel)} with no containment — an absolute path won, {@code ..} climbed
 * — which was harmless for a corpus checked out by its own developers and is not for an editor that
 * runs user-edited buffers, also anonymously. So: a relative path only, normalised, its real path
 * ({@code NOFOLLOW_LINKS}) inside the root, never a symbolic link, and no file content in any
 * message. The corpus passes a resolver rooted at its suites tree (every corpus sidecar sits beside
 * its scenario), so its behaviour is unchanged.
 * </p>
 */
public final class SidecarResolver
{

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
            throw new IOException("sidecar root is not a directory: " + aRoot);
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
     *            scenario held in memory passes the file it would have been saved as
     * @param aRelative
     *            the directive's payload
     * @return the sidecar's path, inside the root
     * @throws IOException
     *             when the payload is absolute, escapes the root, is a symbolic link, or does not
     *             exist
     */
    public Path resolve(Path aScenarioFile, String aRelative) throws IOException
    {
        Objects.requireNonNull(aRelative, "sidecar path");
        Path rel = Path.of(aRelative);
        if (rel.isAbsolute() || aRelative.startsWith("/") || aRelative.startsWith("\\"))
        {
            throw new IOException("sidecar path must be relative: " + aRelative);
        }
        Path base = aScenarioFile.toAbsolutePath().getParent();
        if (base == null)
        {
            throw new IOException("scenario file has no parent directory: " + aScenarioFile);
        }
        Path candidate = base.resolve(rel).normalize();
        if (!candidate.startsWith(root))
        {
            throw new IOException("sidecar path escapes the scenario tree: " + aRelative + " (from "
                    + base + ")");
        }
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS))
        {
            throw new IOException("sidecar not found: " + aRelative + " (from " + base + ")");
        }
        if (Files.isSymbolicLink(candidate))
        {
            throw new IOException("sidecar must not be a symbolic link: " + aRelative);
        }
        Path real = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!real.startsWith(root))
        {
            throw new IOException("sidecar path escapes the scenario tree: " + aRelative);
        }
        return real;
    }


    /**
     * The {@code #library-include} resolver for a scenario at {@code aScenarioFile}: opens the
     * sidecar through {@link #resolve}; a missing sidecar answers {@code null} as the parser's
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
            Path file;
            try
            {
                file = resolve(aScenarioFile, rel);
            }
            catch (IOException e)
            {
                if (e.getMessage() != null && e.getMessage().startsWith("sidecar not found"))
                {
                    return null;
                }
                throw e;
            }
            return Files.newInputStream(file);
        };
    }


    /**
     * The sidecar's bytes.
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
        return Files.newInputStream(resolve(aScenarioFile, aRelative));
    }


    @Override
    public String toString()
    {
        return "SidecarResolver[" + root + "]";
    }
}
