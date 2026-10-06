package net.cumba.corej.ruletest.cdt.ruletest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SidecarResolver}: a sidecar resolves beside its scenario and nowhere else — {@code ..}, an
 * absolute path and a symbolic link are refused, and no refusal quotes file content
 * ({@code PLAN-rule-authoring-api} §3.1, editor review H1).
 */
class SidecarResolverTest
{

    private static Path tree(Path aRoot) throws IOException
    {
        Path scenarios = aRoot.resolve("suites").resolve("cdisc").resolve("CDISC-XX0001");
        Files.createDirectories(scenarios);
        Files.writeString(scenarios.resolve("scenario.cdt"), "#!RuleTest\n",
                StandardCharsets.UTF_8);
        Files.writeString(scenarios.resolve("lib.yaml"), "standard: sdtmig\n",
                StandardCharsets.UTF_8);
        Files.writeString(scenarios.resolve("define.xml"), "<ODM/>", StandardCharsets.UTF_8);
        Files.writeString(aRoot.resolve("secret.txt"), "SECRET CONTENT", StandardCharsets.UTF_8);
        return scenarios.resolve("scenario.cdt");
    }


    @Test
    void aSidecarBesideTheScenarioResolves(@TempDir Path aRoot) throws IOException
    {
        Path scenario = tree(aRoot);
        SidecarResolver r = SidecarResolver.rootedAt(aRoot.resolve("suites"));
        assertEquals(scenario.resolveSibling("lib.yaml").toRealPath(),
                r.resolve(scenario, "lib.yaml"));
        try (InputStream in = r.open(scenario, "define.xml"))
        {
            assertEquals("<ODM/>", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        try (InputStream in = r.includeResolver(scenario).open("lib.yaml"))
        {
            assertNotNull(in);
        }
    }


    @Test
    void aMissingIncludeAnswersNullAsTheParserExpects(@TempDir Path aRoot) throws IOException
    {
        Path scenario = tree(aRoot);
        SidecarResolver r = SidecarResolver.rootedAt(aRoot.resolve("suites"));
        assertNull(r.includeResolver(scenario).open("nothing.yaml"));
        IOException e = assertThrows(IOException.class, () -> r.resolve(scenario, "nothing.yaml"));
        assertTrue(e.getMessage().startsWith("sidecar not found"), e.getMessage());
    }


    @Test
    void dotDotCannotClimbOutOfTheTree(@TempDir Path aRoot) throws IOException
    {
        Path scenario = tree(aRoot);
        SidecarResolver r = SidecarResolver.rootedAt(aRoot.resolve("suites"));
        IOException e = assertThrows(IOException.class,
                () -> r.resolve(scenario, "../../../secret.txt"));
        assertTrue(e.getMessage().contains("escapes the scenario tree"), e.getMessage());
        assertFalse(e.getMessage().contains("SECRET"), "no file content in a message");
        // climbing inside the tree is fine
        Files.writeString(aRoot.resolve("suites").resolve("shared.yaml"), "x: 1\n",
                StandardCharsets.UTF_8);
        assertNotNull(r.resolve(scenario, "../../shared.yaml"));
    }


    @Test
    void anAbsolutePathIsRefused(@TempDir Path aRoot) throws IOException
    {
        Path scenario = tree(aRoot);
        SidecarResolver r = SidecarResolver.rootedAt(aRoot.resolve("suites"));
        String absolute = aRoot.resolve("secret.txt").toAbsolutePath().toString();
        IOException e = assertThrows(IOException.class, () -> r.resolve(scenario, absolute));
        assertTrue(e.getMessage().contains("must be relative"), e.getMessage());
        assertFalse(e.getMessage().contains("SECRET"));
    }


    @Test
    void aSymbolicLinkIsRefusedEvenInsideTheTree(@TempDir Path aRoot) throws IOException
    {
        Path scenario = tree(aRoot);
        assumeTrue(link(scenario.resolveSibling("link.yaml"), aRoot.resolve("secret.txt")),
                "this file system cannot create a symbolic link");
        SidecarResolver r = SidecarResolver.rootedAt(aRoot.resolve("suites"));
        IOException e = assertThrows(IOException.class, () -> r.resolve(scenario, "link.yaml"));
        assertTrue(e.getMessage().contains("symbolic link"), e.getMessage());
        assertFalse(e.getMessage().contains("SECRET"));
    }


    @Test
    void theRootMustBeADirectory(@TempDir Path aRoot) throws IOException
    {
        Path file = aRoot.resolve("f.txt");
        Files.writeString(file, "x", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> SidecarResolver.rootedAt(file));
        assertThrows(IOException.class, () -> SidecarResolver.rootedAt(aRoot.resolve("absent")));
        assertEquals(aRoot.toRealPath(), SidecarResolver.rootedAt(aRoot).root());
    }


    /** A symbolic link, or {@code false} on a file system that has none. */
    private static boolean link(Path aLink, Path aTarget)
    {
        try
        {
            Files.createSymbolicLink(aLink, aTarget);
            return true;
        }
        catch (IOException | UnsupportedOperationException _)
        {
            return false;
        }
    }


    /**
     * Review 8a-B H1, measured: only the LAST component was checked for a link, so a directory link
     * inside the tree led straight out of it ({@code link-dir -> /outside}, then
     * {@code link-dir/secret.txt} was accepted and read). Every component is judged now, and a link
     * is refused even where it points back into the tree — confinement is a property of the path
     * the scenario names, not of where a link happens to lead today.
     */
    @Test
    void aLinkAnywhereOnThePathIsRefused(@TempDir Path aRoot) throws IOException
    {
        Path scenario = tree(aRoot);
        Path outside = Files.createDirectories(aRoot.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "SECRET CONTENT", StandardCharsets.UTF_8);
        Path inside = Files.createDirectories(aRoot.resolve("suites").resolve("shared"));
        Files.writeString(inside.resolve("lib.yaml"), "standard: sdtmig\n", StandardCharsets.UTF_8);
        assumeTrue(
                link(scenario.resolveSibling("link-dir"), outside)
                        && link(scenario.resolveSibling("inner-link"), inside),
                "this file system cannot create a symbolic link");
        SidecarResolver r = SidecarResolver.rootedAt(aRoot.resolve("suites"));
        IOException e = assertThrows(IOException.class,
                () -> r.resolve(scenario, "link-dir/secret.txt"));
        assertTrue(e.getMessage().contains("symbolic link"), e.getMessage());
        assertFalse(e.getMessage().contains("SECRET"));
        assertThrows(IOException.class, () -> r.open(scenario, "link-dir/secret.txt"));
        assertThrows(IOException.class,
                () -> r.includeResolver(scenario).open("link-dir/secret.txt"));
        assertThrows(IOException.class, () -> r.resolve(scenario, "inner-link/lib.yaml"));
        // the same file named through real directories resolves
        assertNotNull(r.resolve(scenario, "../../shared/lib.yaml"));
    }


    /**
     * Review 8a-B M3, measured: the root was canonicalised and the scenario's directory was not, so
     * a checkout reached through a link refused every legitimate sidecar (112 {@code #define-xml}
     * and 14 {@code #library-include} scenarios red). Both sides are real paths now.
     */
    @Test
    void aTreeReachedThroughALinkResolvesItsOwnSidecars(@TempDir Path aRoot) throws IOException
    {
        Path real = Files.createDirectories(aRoot.resolve("t"));
        Path scenarioReal = tree(real);
        Path linked = aRoot.resolve("tlink");
        assumeTrue(link(linked, real), "this file system cannot create a symbolic link");
        Path scenario = linked.resolve(real.relativize(scenarioReal));
        SidecarResolver r = SidecarResolver.rootedAt(linked.resolve("suites"));
        assertEquals(scenarioReal.resolveSibling("lib.yaml").toRealPath(),
                r.resolve(scenario, "lib.yaml"));
        try (InputStream in = r.open(scenario, "define.xml"))
        {
            assertEquals("<ODM/>", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertThrows(IOException.class, () -> r.resolve(scenario, "../../../secret.txt"));
    }


    /**
     * Review 8a-B L4: a payload that is no path at all (a NUL, or on Windows one of {@code *?<>|"})
     * threw {@code InvalidPathException} past the runner's three-outcome contract.
     */
    @Test
    void aPayloadThatIsNoPathIsAnIOException(@TempDir Path aRoot) throws IOException
    {
        Path scenario = tree(aRoot);
        SidecarResolver r = SidecarResolver.rootedAt(aRoot.resolve("suites"));
        IOException e = assertThrows(IOException.class, () -> r.resolve(scenario, "lib\0.yaml"));
        assertTrue(e.getMessage().contains("not a valid path"), e.getMessage());
        assertThrows(IOException.class, () -> r.includeResolver(scenario).open("lib\0.yaml"));
    }


    /**
     * Review 8a-B L5: a refusal names the payload and the scenario's directory relative to the
     * tree, never an absolute server path.
     */
    @Test
    void aRefusalNamesNoAbsolutePath(@TempDir Path aRoot) throws IOException
    {
        Path scenario = tree(aRoot);
        SidecarResolver r = SidecarResolver.rootedAt(aRoot.resolve("suites"));
        for (String payload : new String[]
        {
                "../../../secret.txt", "nothing.yaml"
        })
        {
            IOException e = assertThrows(IOException.class, () -> r.resolve(scenario, payload));
            assertFalse(e.getMessage().contains(aRoot.toString()), e.getMessage());
            assertFalse(e.getMessage().contains(aRoot.toRealPath().toString()), e.getMessage());
            assertTrue(e.getMessage().contains(payload), e.getMessage());
        }
    }


    /** A sidecar is a regular file: a directory (or a device, a pipe) is refused. */
    @Test
    void aDirectoryIsNoSidecar(@TempDir Path aRoot) throws IOException
    {
        Path scenario = tree(aRoot);
        SidecarResolver r = SidecarResolver.rootedAt(aRoot.resolve("suites"));
        IOException e = assertThrows(IOException.class, () -> r.resolve(scenario, "."));
        assertTrue(e.getMessage().contains("not a regular file"), e.getMessage());
    }


    /**
     * Review 8a-B M2: {@link RuleTestCdt#load(Path)}'s default resolver was unconfined —
     * {@code base.resolve(rel)}, so {@code ..} climbed and an absolute path won. It confines to the
     * scenario's own directory now (every corpus sidecar sits beside its scenario).
     */
    @Test
    void loadConfinesItsIncludesToTheScenarioDirectory(@TempDir Path aRoot) throws IOException
    {
        Path scenario = tree(aRoot);
        Files.writeString(aRoot.resolve("suites").resolve("cdisc").resolve("outside.yaml"),
                "standard: sdtmig\nversion: \"3-4\"\n", StandardCharsets.UTF_8);
        String body = "dataset AE\ncol A type=Char\n---\nx\n---\n";
        Files.writeString(scenario, "#!RuleTest\n#test CDISC-XX0001 expect=violation domain=AE\n"
                + "#library-include ../outside.yaml\n" + body, StandardCharsets.UTF_8);
        RuleTestCdtException e = assertThrows(RuleTestCdtException.class,
                () -> RuleTestCdt.load(scenario));
        assertTrue(e.getMessage().contains("escapes the scenario tree"), e.getMessage());
        // beside the scenario still loads
        Files.writeString(scenario.resolveSibling("lib.yaml"),
                "standard: sdtmig\nversion: \"3-4\"\n", StandardCharsets.UTF_8);
        Files.writeString(scenario, "#!RuleTest\n#test CDISC-XX0001 expect=violation domain=AE\n"
                + "#library-include lib.yaml\n" + body, StandardCharsets.UTF_8);
        assertNotNull(RuleTestCdt.load(scenario).getLibrary());
    }


    /**
     * Confirmation look L-2: a redirection {@code isSymbolicLink} does not report — a Windows
     * junction — is caught only by comparing each component's real path with the walked one. No
     * Linux file system has such a thing, so the real-path reader is injected: it answers for the
     * directory {@code junction} what a junction's target would be. Delete the equality branch and
     * this goes red.
     */
    @Test
    void aRedirectionNoLinkTestSeesIsRefusedByTheRealPathTest(@TempDir Path aRoot)
        throws IOException
    {
        Path scenario = tree(aRoot);
        Path junction = Files.createDirectories(scenario.resolveSibling("junction"));
        Files.writeString(junction.resolve("lib.yaml"), "standard: sdtmig\n",
                StandardCharsets.UTF_8);
        Path elsewhere = Files.createDirectories(aRoot.resolve("outside")).toRealPath();
        SidecarResolver r = SidecarResolver.rootedAt(aRoot.resolve("suites"),
                p -> "junction".equals(String.valueOf(p.getFileName())) ? elsewhere
                        : p.toRealPath());
        IOException e = assertThrows(IOException.class,
                () -> r.resolve(scenario, "junction/lib.yaml"));
        assertTrue(e.getMessage().contains("symbolic link, junction or other redirection"),
                e.getMessage());
        assertFalse(e.getMessage().contains(aRoot.toRealPath().toString()), e.getMessage());
        assertThrows(IOException.class, () -> r.open(scenario, "junction/lib.yaml"));
        // the walk's other components are untouched by the seam
        assertNotNull(r.resolve(scenario, "lib.yaml"));
    }


    /**
     * The same branch's other answer: on a case-insensitive file system a payload that differs in
     * case from the file on disk resolves to a real path that differs in its last name only.
     */
    @Test
    void aPayloadDifferingInCaseFromTheDiskIsRefused(@TempDir Path aRoot) throws IOException
    {
        Path scenario = tree(aRoot);
        SidecarResolver r = SidecarResolver.rootedAt(aRoot.resolve("suites"),
                p -> "lib.yaml".equals(String.valueOf(p.getFileName()))
                        ? p.toRealPath().resolveSibling("LIB.yaml")
                        : p.toRealPath());
        IOException e = assertThrows(IOException.class, () -> r.resolve(scenario, "lib.yaml"));
        assertTrue(e.getMessage().contains("differs in case"), e.getMessage());
    }


    /**
     * Confirmation look L-1: only "not found" was reworded, so an unreadable sidecar (or a
     * directory the server may not enter) surfaced {@code AccessDeniedException}'s message — the
     * absolute path — in the SIDECAR and PARSE details. A mode-000 file and a mode-000 directory
     * now say {@code cannot read sidecar} with the path relative to the tree, and the include
     * resolver does not mistake either for "absent". POSIX only; skipped visibly elsewhere, and
     * where the process can read a mode-000 file anyway (root).
     */
    @Test
    void anUnreadableSidecarNamesNoAbsolutePath(@TempDir Path aRoot) throws IOException
    {
        Path scenario = tree(aRoot);
        assumeTrue(
                Files.getFileStore(aRoot).supportsFileAttributeView(PosixFileAttributeView.class),
                "no POSIX permissions on this file system");
        Path locked = scenario.resolveSibling("locked.yaml");
        Files.writeString(locked, "SECRET CONTENT", StandardCharsets.UTF_8);
        Path lockedDir = Files.createDirectories(scenario.resolveSibling("locked-dir"));
        Files.writeString(lockedDir.resolve("lib.yaml"), "x: 1\n", StandardCharsets.UTF_8);
        Set<PosixFilePermission> dirModes = Files.getPosixFilePermissions(lockedDir);
        Files.setPosixFilePermissions(locked, Set.of());
        Files.setPosixFilePermissions(lockedDir, Set.of());
        try
        {
            assumeFalse(Files.isReadable(locked), "this process reads a mode-000 file (root)");
            SidecarResolver r = SidecarResolver.rootedAt(aRoot.resolve("suites"));
            String[] absolute =
            {
                    aRoot.toString(), aRoot.toRealPath().toString()
            };
            for (String payload : new String[]
            {
                    "locked.yaml", "locked-dir/lib.yaml"
            })
            {
                IOException e = assertThrows(IOException.class, () -> r.open(scenario, payload));
                assertTrue(e.getMessage().startsWith("cannot read sidecar: " + payload),
                        e.getMessage());
                assertTrue(e.getMessage().contains("AccessDeniedException"), e.getMessage());
                for (String abs : absolute)
                {
                    assertFalse(e.getMessage().contains(abs), e.getMessage());
                }
                assertFalse(e.getMessage().contains("SECRET"), e.getMessage());
                assertNotNull(e.getCause(), "the original stays the cause");
                IOException viaInclude = assertThrows(IOException.class,
                        () -> r.includeResolver(scenario).open(payload));
                assertEquals(e.getMessage(), viaInclude.getMessage());
            }
        }
        finally
        {
            Files.setPosixFilePermissions(lockedDir, dirModes);
            Files.setPosixFilePermissions(locked,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
    }
}
