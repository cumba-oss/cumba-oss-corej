package net.cumba.cdisc.core;

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
        VersionInfo info = VersionInfo.forArtifact("corej-cdisc-core");

        assertEquals("corej-cdisc-core", info.artifactId());
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
        assertEquals("unknown", info.gitCommitHashShort());
        assertEquals("unknown", info.gitBranch());
        assertEquals("unknown", info.buildTimestamp());
    }


    @Test
    void of_resolvedPropertiesArePassedThrough()
    {
        Properties p = new Properties();
        p.setProperty("artifactId", "demo");
        p.setProperty("version", "1.2.3");
        p.setProperty("gitCommitHashShort", "abc1234");
        p.setProperty("gitBranch", "main");
        p.setProperty("buildTimestamp", "2026-06-13T00:00:00Z");

        VersionInfo info = VersionInfo.of(p);

        assertEquals("demo", info.artifactId());
        assertEquals("1.2.3", info.version());
        assertEquals("abc1234", info.gitCommitHashShort());
        assertEquals("main", info.gitBranch());
        assertEquals("2026-06-13T00:00:00Z", info.buildTimestamp());
    }


    @Test
    void of_unresolvedPlaceholderBlankAndMissingCountAsUnknown()
    {
        Properties p = new Properties();
        p.setProperty("artifactId", "${project.artifactId}"); // unfiltered placeholder
        p.setProperty("version", "   "); // blank
        // gitCommitHashShort, gitBranch, buildTimestamp absent entirely

        VersionInfo info = VersionInfo.of(p);

        assertEquals("unknown", info.artifactId());
        assertEquals("unknown", info.version());
        assertEquals("unknown", info.gitCommitHashShort());
        assertEquals("unknown", info.gitBranch());
        assertEquals("unknown", info.buildTimestamp());
    }


    @Test
    void of_emptyPropertiesYieldsAllUnknown()
    {
        VersionInfo info = VersionInfo.of(new Properties());

        assertTrue("unknown".equals(info.artifactId()) && "unknown".equals(info.version()));
    }
}
