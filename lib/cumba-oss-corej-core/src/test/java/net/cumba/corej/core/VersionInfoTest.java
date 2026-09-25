package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VersionInfo}: classpath lookup by artifactId and the "unknown" fallbacks.
 */
class VersionInfoTest
{

    @Test
    void forArtifact_loadsThisModulesFilteredVersion()
    {
        VersionInfo info = VersionInfo.forArtifact("cumba-oss-corej-core");

        assertEquals("cumba-oss-corej-core", info.artifactId());
        // version is filtered from ${project.version} (always resolved); never blank or a
        // placeholder
        assertFalse(info.version().isBlank(), "version should be filtered");
        assertFalse(info.version().contains("${"), "version placeholder should be resolved");
        assertFalse("unknown".equals(info.version()), "version should be the real build version");
    }


    @Test
    void forArtifact_unknownArtifactYieldsAllUnknown()
    {
        VersionInfo info = VersionInfo.forArtifact("no-such-artifact-" + getClass().getName());

        assertEquals("unknown", info.artifactId());
        assertEquals("unknown", info.version());
    }


    @Test
    void of_resolvedPropertiesArePassedThrough()
    {
        Properties p = new Properties();
        p.setProperty("artifactId", "demo");
        p.setProperty("version", "1.2.3");

        VersionInfo info = VersionInfo.of(p);

        assertEquals("demo", info.artifactId());
        assertEquals("1.2.3", info.version());
    }


    @Test
    void of_unresolvedPlaceholderBlankAndMissingCountAsUnknown()
    {
        Properties p = new Properties();
        p.setProperty("artifactId", "${project.artifactId}"); // unfiltered placeholder
        p.setProperty("version", "   "); // blank

        VersionInfo info = VersionInfo.of(p);

        assertEquals("unknown", info.artifactId());
        assertEquals("unknown", info.version());
    }


    @Test
    void of_emptyPropertiesYieldsAllUnknown()
    {
        VersionInfo info = VersionInfo.of(new Properties());

        assertTrue("unknown".equals(info.artifactId()) && "unknown".equals(info.version()));
    }
}
