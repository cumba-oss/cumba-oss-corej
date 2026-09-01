package net.cumba.cdisc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.library.api.client.CdiscLibraryClient;
import net.cumba.cdisc.library.api.model.ct.CtPackage;
import net.cumba.cdisc.library.api.model.ct.CtPackageList;
import net.cumba.cdisc.library.api.model.rules.RuleMap;
import net.cumba.cdisc.library.api.model.rules.RulePackage;
import net.cumba.web.api.Link;
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

    // --------------------------------------------------------------------
    // loadRules
    // --------------------------------------------------------------------


    @Test
    void loadRules_nullApiPackageReturnsEmpty() throws IOException
    {
        CdiscLibraryClient mockClient = mock(CdiscLibraryClient.class);
        when(mockClient.getRules(anyString(), anyString())).thenReturn(null);
        CoreLibraryAccess access = CoreLibraryAccessImpl.forTesting(mockClient);
        assertTrue(access.loadRules("sdtmig", "3-4").isEmpty());
    }


    @Test
    void loadRules_emptyRulesReturnsEmpty() throws IOException
    {
        CdiscLibraryClient mockClient = mock(CdiscLibraryClient.class);
        RulePackage apiPkg = mock(RulePackage.class);
        RuleMap ruleMap = mock(RuleMap.class);
        when(ruleMap.keys()).thenReturn(Set.of());
        when(apiPkg.rules()).thenReturn(Optional.of(ruleMap));
        when(mockClient.getRules(anyString(), anyString())).thenReturn(apiPkg);
        CoreLibraryAccess access = CoreLibraryAccessImpl.forTesting(mockClient);
        List<Rule> result = access.loadRules("sdtmig", "3-4");
        assertTrue(result.isEmpty());
    }


    @Test
    void loadRules_ioExceptionIsWrappedWithStandardAndVersion() throws IOException
    {
        CdiscLibraryClient mockClient = mock(CdiscLibraryClient.class);
        when(mockClient.getRules(anyString(), anyString())).thenThrow(new IOException("HTTP 503"));
        CoreLibraryAccess access = CoreLibraryAccessImpl.forTesting(mockClient);
        IOException ex = assertThrows(IOException.class, () -> access.loadRules("sdtmig", "3-4"));
        // Regression guard: the user-visible message must name standard, version, and
        // include the underlying cause. This is the message that lived in
        // CoreEngineRunner.loadBaseFromApi before the migration; the user sees identical
        // error text after.
        assertTrue(ex.getMessage().contains("sdtmig"), "message: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("3-4"), "message: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("Failed to fetch rules"),
                "message: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("HTTP 503"), "message: " + ex.getMessage());
        assertNotNull(ex.getCause());
    }

    // --------------------------------------------------------------------
    // listCtPackageIds
    // --------------------------------------------------------------------


    @Test
    void listCtPackageIds_nullListReturnsEmpty() throws IOException
    {
        CdiscLibraryClient mockClient = mock(CdiscLibraryClient.class);
        when(mockClient.getCtPackages()).thenReturn(null);
        assertTrue(CoreLibraryAccessImpl.forTesting(mockClient).listCtPackageIds().isEmpty());
    }


    @Test
    void listCtPackageIds_ioExceptionReturnsEmpty() throws IOException
    {
        CdiscLibraryClient mockClient = mock(CdiscLibraryClient.class);
        when(mockClient.getCtPackages()).thenThrow(new IOException("network down"));
        assertTrue(CoreLibraryAccessImpl.forTesting(mockClient).listCtPackageIds().isEmpty());
    }


    @Test
    void listCtPackageIds_extractsIdsAndSkipsBlanks() throws IOException
    {
        CdiscLibraryClient mockClient = mock(CdiscLibraryClient.class);
        CtPackageList list = mock(CtPackageList.class);
        Link withId = mock(Link.class);
        Link withBlankId = mock(Link.class);
        Link withoutId = mock(Link.class);
        when(withId.id()).thenReturn(Optional.of("sdtmct-2024-09-27"));
        when(withBlankId.id()).thenReturn(Optional.of("   "));
        when(withoutId.id()).thenReturn(Optional.empty());
        when(list.packageLinks()).thenReturn(List.of(withId, withBlankId, withoutId));
        when(mockClient.getCtPackages()).thenReturn(list);
        List<String> ids = CoreLibraryAccessImpl.forTesting(mockClient).listCtPackageIds();
        assertEquals(List.of("sdtmct-2024-09-27"), ids);
    }

    // --------------------------------------------------------------------
    // fetchCtPackage
    // --------------------------------------------------------------------


    @Test
    void fetchCtPackage_successReturnsThePackage() throws IOException
    {
        CdiscLibraryClient mockClient = mock(CdiscLibraryClient.class);
        CtPackage pkg = mock(CtPackage.class);
        when(mockClient.getCtPackage(anyString(), anyBoolean())).thenReturn(pkg);
        CoreLibraryAccessImpl impl = (CoreLibraryAccessImpl) CoreLibraryAccessImpl
                .forTesting(mockClient);
        assertSame(pkg, impl.fetchCtPackage("sdtmct-2024-09-27"));
    }


    @Test
    void fetchCtPackage_ioExceptionReturnsNull() throws IOException
    {
        CdiscLibraryClient mockClient = mock(CdiscLibraryClient.class);
        when(mockClient.getCtPackage(anyString(), anyBoolean()))
                .thenThrow(new IOException("HTTP 404"));
        CoreLibraryAccessImpl impl = (CoreLibraryAccessImpl) CoreLibraryAccessImpl
                .forTesting(mockClient);
        assertNull(impl.fetchCtPackage("missing-pkg"));
    }
}
