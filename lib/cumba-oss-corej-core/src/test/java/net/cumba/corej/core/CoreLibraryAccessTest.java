package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.Optional;
import net.cumba.cdisc.library.api.client.CdiscLibraryClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for {@link CoreLibraryAccess} and {@link CoreLibraryAccessImpl}. Uses Mockito to drive the
 * wrapped {@link CdiscLibraryClient} via the visible-for-testing
 * {@link CoreLibraryAccessImpl#forTesting(CdiscLibraryClient)} factory.
 *
 * <p>
 * Some tests skip when the {@code CDISC_API_KEY} environment variable is set on the developer's
 * machine: the env var bypasses the sysprop, which would defeat the "no key configured → empty"
 * assertion. In CI the env is unset and the tests run.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class CoreLibraryAccessTest
{

    private static final String SP_KEY = CdiscLibraryClient.SP_CDISC_API_KEY;

    private String savedSysprop;

    @BeforeEach
    void saveSysprop()
    {
        savedSysprop = System.getProperty(SP_KEY);
        System.clearProperty(SP_KEY);
    }


    @AfterEach
    void restoreSysprop()
    {
        if (savedSysprop != null)
        {
            System.setProperty(SP_KEY, savedSysprop);
        }
        else
        {
            System.clearProperty(SP_KEY);
        }
    }

    // --------------------------------------------------------------------
    // openIfConfigured / open / openWithApiKey
    // --------------------------------------------------------------------


    @Test
    void openIfConfigured_returnsEmptyWhenNoKey()
    {
        Assumptions.assumeTrue(
                System.getenv(CdiscLibraryClient.ENV_CDISC_API_KEY) == null
                        || System.getenv(CdiscLibraryClient.ENV_CDISC_API_KEY).isBlank(),
                "Test requires CDISC_API_KEY env var to be unset");
        // Sysprop already cleared in @BeforeEach.
        assertEquals(Optional.empty(), CoreLibraryAccess.openIfConfigured());
    }


    @Test
    void openIfConfigured_returnsPresentWhenSyspropSet()
    {
        System.setProperty(SP_KEY, "test-key-abc");
        Optional<CoreLibraryAccess> access = CoreLibraryAccess.openIfConfigured();
        assertTrue(access.isPresent());
    }


    @Test
    void openIfConfigured_withCacheDir_acceptsOverride(@TempDir Path cacheDir)
    {
        System.setProperty(SP_KEY, "test-key-cache");
        Optional<CoreLibraryAccess> access = CoreLibraryAccess.openIfConfigured(cacheDir);
        assertTrue(access.isPresent());
    }


    @Test
    void openIfConfigured_withCacheDir_returnsEmptyWhenNoKey(@TempDir Path cacheDir)
    {
        Assumptions.assumeTrue(
                System.getenv(CdiscLibraryClient.ENV_CDISC_API_KEY) == null
                        || System.getenv(CdiscLibraryClient.ENV_CDISC_API_KEY).isBlank(),
                "Test requires CDISC_API_KEY env var to be unset");
        assertFalse(CoreLibraryAccess.openIfConfigured(cacheDir).isPresent());
    }


    @Test
    void openWithApiKey_nullStillReturnsAnAccess()
    {
        // The "dummy" substitution lets the CLI construct an access even when no key is set.
        // We can't inspect the resulting CdiscLibraryClient's apiKey (no accessor on the
        // instance — apiKey is consumed by the HTTP header layer and not exposed), so the
        // observable contract here is just "non-null access returned, no NPE."
        CoreLibraryAccess access = CoreLibraryAccess.openWithApiKey(null);
        assertNotNull(access);
    }


    @Test
    void openWithApiKey_blankStillReturnsAnAccess()
    {
        assertNotNull(CoreLibraryAccess.openWithApiKey("   "));
    }


    @Test
    void openWithApiKey_realKeyReturnsAnAccess()
    {
        assertNotNull(CoreLibraryAccess.openWithApiKey("real-key-xyz"));
    }


    @Test
    void open_nullBaseUrlFallsBackToDefault()
    {
        CoreLibraryAccess access = CoreLibraryAccess.open("k", null, null);
        // baseUrl() is the AbstractApiClient instance accessor and strips trailing slash.
        String expected = CdiscLibraryClient.DEFAULT_BASE_URL.endsWith("/")
                ? CdiscLibraryClient.DEFAULT_BASE_URL.substring(0,
                        CdiscLibraryClient.DEFAULT_BASE_URL.length() - 1)
                : CdiscLibraryClient.DEFAULT_BASE_URL;
        assertEquals(expected, ((CoreLibraryAccessImpl) access).client().baseUrl());
    }


    @Test
    void open_explicitBaseUrlIsKept()
    {
        CoreLibraryAccess access = CoreLibraryAccess.open("k", "https://example.test/api/", null);
        // baseUrl() strips trailing slash.
        assertEquals("https://example.test/api",
                ((CoreLibraryAccessImpl) access).client().baseUrl());
    }


    @Test
    void open_explicitCacheDirIsAccepted(@TempDir Path cacheDir)
    {
        assertNotNull(CoreLibraryAccess.open("k", null, cacheDir));
    }

    // --------------------------------------------------------------------
    // forTesting + client()
    // --------------------------------------------------------------------


    @Test
    void forTesting_wrapsMockAndExposesItViaClient()
    {
        CdiscLibraryClient mockClient = mock(CdiscLibraryClient.class);
        CoreLibraryAccess access = CoreLibraryAccessImpl.forTesting(mockClient);
        assertSame(mockClient, ((CoreLibraryAccessImpl) access).client());
    }
}
