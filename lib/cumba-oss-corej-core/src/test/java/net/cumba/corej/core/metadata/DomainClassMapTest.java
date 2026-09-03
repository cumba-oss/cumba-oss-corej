package net.cumba.corej.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link DomainClassMap} — the offline domain&rarr;class fallback. Tests build
 * instances via the hermetic {@link DomainClassMap#loadFrom(Path)} factory so they never touch the
 * process-wide singleton or system properties (keeping them isolated from other test classes).
 */
class DomainClassMapTest
{

    @TempDir
    Path tempDir;

    @Test
    void bundledMapResolvesKnownStandardDomains()
    {
        DomainClassMap map = DomainClassMap.loadFrom(null);
        assertEquals("SPECIAL PURPOSE", map.classFor("sdtm", "DM"));
        assertEquals("FINDINGS", map.classFor("sdtm", "LB"));
        assertEquals("INTERVENTIONS", map.classFor("sdtm", "EX"));
        // Case-insensitive on the domain code.
        assertEquals("EVENTS", map.classFor("sdtm", "ae"));
    }


    @Test
    void unknownDomainReturnsNull()
    {
        assertNull(DomainClassMap.loadFrom(null).classFor("sdtm", "ZZ"));
    }


    @Test
    void nullStandardScansAllNamespacesFirstMatchWins()
    {
        DomainClassMap map = DomainClassMap.loadFrom(null);
        // DM exists in both sdtm and send (both SPECIAL PURPOSE); a null family scans all.
        assertEquals("SPECIAL PURPOSE", map.classFor(null, "DM"));
        // BW is only in the send namespace, still found via the cross-namespace scan.
        assertEquals("FINDINGS", map.classFor(null, "BW"));
    }


    @Test
    void knownFamilyIsAuthoritativeAndDoesNotMatchOtherFamilies()
    {
        DomainClassMap map = DomainClassMap.loadFrom(null);
        // BW exists only in the send family. With a known sdtm family, the domain is treated as
        // not-in-sdtm (left to the sniffer) rather than borrowing send's mapping.
        assertNull(map.classFor("sdtm", "BW"));
        // But within its own family it resolves.
        assertEquals("FINDINGS", map.classFor("send", "BW"));
    }


    @Test
    void externalOverrideExtendsAndOverridesDefaults() throws IOException
    {
        Path override = tempDir.resolve("override.json");
        Files.writeString(override, """
                { "sdtm": { "DM": "OVERRIDDEN", "ZZ": "CUSTOM CLASS" } }
                """);

        DomainClassMap map = DomainClassMap.loadFrom(override);
        assertEquals("OVERRIDDEN", map.classFor("sdtm", "DM")); // override wins per key
        assertEquals("CUSTOM CLASS", map.classFor("sdtm", "ZZ")); // new entry added
        assertEquals("FINDINGS", map.classFor("sdtm", "LB")); // untouched defaults remain
    }


    @Test
    void missingExternalFileKeepsBundledDefaults()
    {
        DomainClassMap map = DomainClassMap.loadFrom(tempDir.resolve("does-not-exist.json"));
        assertEquals("SPECIAL PURPOSE", map.classFor("sdtm", "DM"));
    }


    @Test
    void loadHonoursSystemPropertyOverride() throws IOException
    {
        // Exercises the env/system-property resolution in load(). Sequential-safe: the property is
        // set and cleared within this method, and load() does not mutate the singleton.
        Path override = tempDir.resolve("sp-override.json");
        Files.writeString(override, """
                { "sdtm": { "DM": "FROM-SYSPROP" } }
                """);
        String previous = System.getProperty(DomainClassMap.SP_OVERRIDE);
        System.setProperty(DomainClassMap.SP_OVERRIDE, override.toString());
        try
        {
            assertEquals("FROM-SYSPROP", DomainClassMap.load().classFor("sdtm", "DM"));
        }
        finally
        {
            if (previous == null)
            {
                System.clearProperty(DomainClassMap.SP_OVERRIDE);
            }
            else
            {
                System.setProperty(DomainClassMap.SP_OVERRIDE, previous);
            }
        }
    }
}
