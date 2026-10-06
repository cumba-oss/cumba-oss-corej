package net.cumba.corej.authoring;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.cumba.corej.core.expr.convert.CheckLevelNodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link AuthoringProvenance} reads the {@code git.properties} the parent pom's git-commit-id
 * plugin writes into {@code target/classes}; under Surefire that file is on the classpath, so the
 * commit of this very tree is readable here.
 */
class AuthoringProvenanceTest
{

    private static final String ID = "0123456789abcdef0123456789abcdef01234567";

    private static Properties props(String aId, String aDirty)
    {
        Properties p = new Properties();
        if (aId != null)
        {
            p.setProperty("git.commit.id", aId);
        }
        if (aDirty != null)
        {
            p.setProperty("git.dirty", aDirty);
        }
        return p;
    }


    @Test
    void theCommitOfThisTreeIsReadable()
    {
        Optional<String> id = AuthoringProvenance.commitId();
        assertTrue(id.isPresent(), "git.properties is generated at initialize; a build outside a"
                + " git checkout has none");
        assertTrue(id.get().matches("[0-9a-f]{40}"), id.get());
        assertTrue(AuthoringProvenance.describe().startsWith(id.get().substring(0, 12)),
                AuthoringProvenance.describe());
    }


    /**
     * Pack-2 review 8a-A F5: the parent pom records {@code git.dirty} (it used to drop it), and the
     * core jar's provenance is read from its own code source too.
     */
    @Test
    void bothJarsRecordTheirCommitAndWhetherTheTreeWasDirty()
    {
        Properties own = AuthoringProvenance.gitPropertiesOf(AuthoringProvenance.class);
        assertTrue(own.containsKey("git.dirty"), own.toString());
        Properties core = AuthoringProvenance.gitPropertiesOf(CheckLevelNodes.class);
        assertTrue(core.getProperty("git.commit.id", "").matches("[0-9a-f]{40}"), core.toString());
        assertTrue(core.containsKey("git.dirty"), core.toString());
        assertEquals(new Properties(), AuthoringProvenance.gitPropertiesOf(String.class),
                "a class from no file location carries no provenance, and does not throw");
    }

    /** A class to define again under a code source of the test's choosing. */
    static final class Probe
    {
    }


    /** Defines {@link Probe} with a given code source. */
    private static final class CodeSourceLoader extends ClassLoader
    {

        CodeSourceLoader()
        {
            super(AuthoringProvenanceTest.class.getClassLoader());
        }


        Class<?> probeAt(URL aLocation) throws IOException
        {
            byte[] bytes;
            try (InputStream in = AuthoringProvenanceTest.class
                    .getResourceAsStream("AuthoringProvenanceTest$Probe.class"))
            {
                bytes = in.readAllBytes();
            }
            ProtectionDomain domain = new ProtectionDomain(
                    new CodeSource(aLocation, (Certificate[]) null), null, this, null);
            return defineClass(Probe.class.getName(), bytes, 0, bytes.length, domain);
        }
    }

    /**
     * Confirmation look on the fold: a code source that is a file <em>inside</em> an open zip file
     * system (a jar nested in a jar) is no directory and a regular file, and {@code Path.toFile()}
     * on it throws {@code UnsupportedOperationException} — which escaped "no provenance, not a
     * crash".
     */
    @Test
    void aCodeSourceInsideAZipFileSystemCarriesNoProvenance(@TempDir Path aDir) throws IOException
    {
        Path outer = aDir.resolve("outer.zip");
        try (OutputStream out = Files.newOutputStream(outer);
                ZipOutputStream zip = new ZipOutputStream(out))
        {
            zip.putNextEntry(new ZipEntry("inner.jar"));
            zip.write(new byte[]
            {
                    'P', 'K'
            });
            zip.closeEntry();
        }
        URI uri = URI.create("jar:" + outer.toUri() + "!/inner.jar");
        // newFileSystem(URI, …) — not (Path, …) — registers the file system, so Path.of(jar:…)
        // and the provenance read find it
        try (FileSystem zipFs = FileSystems.newFileSystem(URI.create("jar:" + outer.toUri()),
                Map.of()))
        {
            assertTrue(Files.isRegularFile(Path.of(uri)),
                    "the nested entry is a file of the open" + " zip file system: " + zipFs);
            Class<?> probe = new CodeSourceLoader().probeAt(uri.toURL());
            assertEquals(new Properties(), AuthoringProvenance.gitPropertiesOf(probe));
        }
    }


    @Test
    void theCheckAcceptsTheCommitAndItsPrefixFromACleanTree()
    {
        assertDoesNotThrow(() -> AuthoringProvenance.check(ID, "j", props(ID, "false")));
        assertDoesNotThrow(
                () -> AuthoringProvenance.check(ID.substring(0, 8), "j", props(ID, "false")));
    }


    @Test
    void theCheckRefusesAnotherCommitADirtyTreeAndNoProvenance()
    {
        IllegalStateException other = assertThrows(IllegalStateException.class,
                () -> AuthoringProvenance.check("0000000deadbeef", "j", props(ID, "false")));
        assertTrue(other.getMessage().contains("install the current engine"), other.getMessage());
        IllegalStateException dirty = assertThrows(IllegalStateException.class,
                () -> AuthoringProvenance.check(ID, "j", props(ID, "true")));
        assertTrue(dirty.getMessage().contains("uncommitted changes"), dirty.getMessage());
        assertThrows(IllegalStateException.class,
                () -> AuthoringProvenance.check(ID, "j", props(null, null)));
        assertThrows(IllegalStateException.class,
                () -> AuthoringProvenance.check("abc", "j", props(ID, "false")));
    }


    @Test
    void theConfiguredCheckIsANoOpWhenUnset()
    {
        String before = System.getProperty(AuthoringProvenance.EXPECTED_COMMIT_PROPERTY);
        try
        {
            System.clearProperty(AuthoringProvenance.EXPECTED_COMMIT_PROPERTY);
            AuthoringProvenance.requireExpectedCommitIfConfigured();
            System.setProperty(AuthoringProvenance.EXPECTED_COMMIT_PROPERTY, "0000000deadbeef");
            assertThrows(IllegalStateException.class,
                    AuthoringProvenance::requireExpectedCommitIfConfigured);
        }
        finally
        {
            if (before == null)
            {
                System.clearProperty(AuthoringProvenance.EXPECTED_COMMIT_PROPERTY);
            }
            else
            {
                System.setProperty(AuthoringProvenance.EXPECTED_COMMIT_PROPERTY, before);
            }
        }
    }
}
