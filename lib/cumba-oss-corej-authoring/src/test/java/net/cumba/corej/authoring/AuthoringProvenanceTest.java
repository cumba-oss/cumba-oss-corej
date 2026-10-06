package net.cumba.corej.authoring;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link AuthoringProvenance} reads the {@code git.properties} the parent pom's git-commit-id
 * plugin writes into {@code target/classes}; under Surefire that file is on the classpath, so the
 * commit of this very tree is readable here.
 */
class AuthoringProvenanceTest
{

    @Test
    void theCommitOfThisTreeIsReadable()
    {
        Optional<String> id = AuthoringProvenance.commitId();
        assertTrue(id.isPresent(), "git.properties is generated at initialize; a build outside a"
                + " git checkout has none");
        assertTrue(id.get().matches("[0-9a-f]{40}"), id.get());
        assertEquals(id.get().substring(0, 12), AuthoringProvenance.describe());
    }


    @Test
    void requireCommitAcceptsTheOwnCommitAndItsPrefix()
    {
        String id = AuthoringProvenance.commitId().orElseThrow();
        assertDoesNotThrow(() -> AuthoringProvenance.requireCommit(id));
        assertDoesNotThrow(() -> AuthoringProvenance.requireCommit(id.substring(0, 8)));
    }


    @Test
    void requireCommitRefusesAnotherCommit()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AuthoringProvenance.requireCommit("0000000deadbeef"));
        assertTrue(e.getMessage().contains("install the current engine"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> AuthoringProvenance.requireCommit("abc"));
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
