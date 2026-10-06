package net.cumba.corej.ruletest.cdt.ruletest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        Path link = scenario.resolveSibling("link.yaml");
        try
        {
            Files.createSymbolicLink(link, aRoot.resolve("secret.txt"));
        }
        catch (IOException | UnsupportedOperationException _)
        {
            return; // a file system without symlinks has nothing to refuse
        }
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
}
