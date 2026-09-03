package net.cumba.corej.ruletest.cdt.ruletest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import net.cumba.corej.core.CoreLibraryAccess;
import net.cumba.corej.core.exec.MetadataProvider;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link ScenarioLibraryResolver} availability gate. The "not configured" path is
 * hermetic (no network); the "configured" path self-skips when no CDISC Library credentials are
 * present, so the suite stays green on credential-less CI.
 */
class ScenarioLibraryResolverTest
{

    private static LibraryRef adamRef()
    {
        return LibraryRef.builder().standard("adamig").version("1-3").build();
    }


    @Test
    void resolve_noKeyConfigured_returnsEmpty()
    {
        // Hermetic only when neither the env var nor the sysprop carries a key.
        Assumptions.assumeTrue(
                System.getenv("CDISC_API_KEY") == null
                        && System.getProperty("cdisc.library.api.key") == null,
                "a CDISC Library key is configured in this environment");

        assertTrue(ScenarioLibraryResolver.resolve(adamRef()).isEmpty(),
                "no key configured => resolver must signal unavailable");
    }


    @Test
    void resolve_keyConfigured_returnsProvider()
    {
        // Self-skips unless a CDISC Library is actually configured (dev machine / cache + key).
        Assumptions.assumeTrue(CoreLibraryAccess.openIfConfigured().isPresent(),
                "no CDISC Library configured (set CDISC_API_KEY)");

        Optional<MetadataProvider> provider = ScenarioLibraryResolver.resolve(adamRef());
        assertTrue(provider.isPresent(), "key configured => resolver must return a provider");
        assertNotNull(provider.orElseThrow());
    }
}
