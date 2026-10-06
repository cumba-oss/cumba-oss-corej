package net.cumba.corej.authoring;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Properties;
import net.cumba.corej.core.expr.convert.CheckLevelNodes;
import org.junit.jupiter.api.Test;

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
