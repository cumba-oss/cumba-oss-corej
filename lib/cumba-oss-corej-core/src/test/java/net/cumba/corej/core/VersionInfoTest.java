package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VersionInfo}: classpath lookup by artifactId, lookup by a class's own code
 * location, and the "unknown" fallbacks.
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

    // ------------------------------------------------------------------
    // forClass — PLAN-report-conformance-fields D4 (P1)
    // ------------------------------------------------------------------


    /**
     * P1a/b: the engine's own class resolves to the engine's own metadata — the artifact id is this
     * module's (asserted by suffix, so the test reads the same in the OSS twin) and the version is
     * the same one {@link VersionInfo#forArtifact} finds under that id.
     */
    @Test
    void forClass_resolvesTheJarContainingTheClass()
    {
        VersionInfo own = VersionInfo
                .forClass(net.cumba.corej.core.run.StudyValidationService.class);

        assertTrue(own.artifactId().endsWith("corej-core"),
                "expected this module's artifact id, got " + own.artifactId());
        assertNotEquals("unknown", own.version(), "version should be the real build version");
        assertEquals(VersionInfo.forArtifact(own.artifactId()).version(), own.version());
    }


    /**
     * P1c — the discriminating control: a class from ANOTHER jar does not answer with the engine's
     * metadata. {@code ValidationReport} lives in the datatable jar, whose
     * {@code version.properties} is unfiltered internally (so it reads {@code "unknown"}) and
     * filtered to its own id in the OSS stack; either way it is not the engine's. A lookup that
     * took "the first {@code version.properties} on the classpath" instead of the class's own
     * location fails here (verified by sabotage, see the plan's status record).
     */
    @Test
    void forClass_aClassFromAnotherJarDoesNotAnswerWithTheEnginesMetadata()
    {
        VersionInfo engine = VersionInfo
                .forClass(net.cumba.corej.core.run.StudyValidationService.class);
        VersionInfo other = VersionInfo.forClass(net.cumba.datatable.report.ValidationReport.class);

        assertNotEquals("unknown", engine.artifactId(),
                "CONTROL FAILED: the engine did not resolve");
        assertNotEquals(engine.artifactId(), other.artifactId(),
                "a datatable class must not resolve to the engine's version.properties");
    }


    /** P1d: a bootstrap-loaded class has no loader and no location — all-unknown, never an NPE. */
    @Test
    void forClass_bootstrapClassYieldsAllUnknown()
    {
        VersionInfo info = VersionInfo.forClass(String.class);

        assertEquals("unknown", info.artifactId());
        assertEquals("unknown", info.version());
    }
}
