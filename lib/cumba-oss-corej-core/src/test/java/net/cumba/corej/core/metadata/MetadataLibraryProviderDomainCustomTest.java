package net.cumba.corej.core.metadata;

import static net.cumba.datatable.testkit.TestMetadataFixtures.column;
import static net.cumba.datatable.testkit.TestMetadataFixtures.lib;
import static net.cumba.datatable.testkit.TestMetadataFixtures.table;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * F-corej-L2-08 — {@link MetadataLibraryProvider#isDomainCustom} in the product-less (Define-XML /
 * degraded) configuration. The product-backed branch answers "custom" for a domain in neither the
 * IG nor the Model; the library-backed fallback must give the same answer for a domain the library
 * knows nothing about, instead of collapsing "unknown" and "known standard" into {@code false}.
 */
class MetadataLibraryProviderDomainCustomTest
{

    private static MetadataProvider defineProvider()
    {
        return MetadataLibraryProvider
                .forDefine(
                        lib("define")
                                .table(table("DM")
                                        .column(column("USUBJID", 0, DataValueType.STRING)
                                                .label("Unique Subject Identifier").build())
                                        .build())
                                .table(table("ZZFLAGGED")
                                        .meta(MetadataKeys.IS_CUSTOM_DOMAIN, Boolean.TRUE)
                                        .column(column("USUBJID", 0, DataValueType.STRING).build())
                                        .build())
                                .build());
    }


    @Test
    void unknownDomainIsCustom_matchingTheProductBackedBranch()
    {
        // The product-backed branch answers true for a domain in neither product; the
        // library-backed branch must agree for a domain absent from the library rather than
        // answer "standard" for a table it knows nothing about.
        assertTrue(defineProvider().isDomainCustom("ZZCUSTOM"),
                "a domain the library knows nothing about is custom, not standard");
    }


    @Test
    void presentTableWithoutTheFlagStaysStandard()
    {
        assertFalse(defineProvider().isDomainCustom("DM"),
                "a declared table without IsCustomDomain keeps answering standard");
    }


    @Test
    void presentTableWithTheFlagIsCustom()
    {
        assertTrue(defineProvider().isDomainCustom("ZZFLAGGED"),
                "a declared table carrying IsCustomDomain=true answers custom");
    }
}
