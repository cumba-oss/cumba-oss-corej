package net.cumba.cdisc.core.metadata;

import static net.cumba.datatable.testkit.TestMetadataFixtures.codelist;
import static net.cumba.datatable.testkit.TestMetadataFixtures.column;
import static net.cumba.datatable.testkit.TestMetadataFixtures.lib;
import static net.cumba.datatable.testkit.TestMetadataFixtures.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.datatable.metadata.ICodeList;
import net.cumba.datatable.metadata.IColumnMetadata;
import net.cumba.datatable.metadata.IDataTableMetadata;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

class EnrichedMetadataLibraryTest
{

    // ------------------------------------------------------------------
    // Basic merge behaviour
    // ------------------------------------------------------------------

    @Test
    void nameAlwaysFromPrimary()
    {
        IMetadataLibrary primary = lib("Study").build();
        IMetadataLibrary fallback = lib("SDTMIG").build();
        EnrichedMetadataLibrary enriched = new EnrichedMetadataLibrary(primary, fallback);
        assertEquals("Study", enriched.getName());
    }


    @Test
    void versionPrimaryWinsElseFallback()
    {
        IMetadataLibrary primary = lib("Study").version("v1").build();
        IMetadataLibrary fallback = lib("SDTMIG").version("3-4").build();
        assertEquals("v1", new EnrichedMetadataLibrary(primary, fallback).getVersion());

        primary = lib("Study").build();
        assertEquals("3-4", new EnrichedMetadataLibrary(primary, fallback).getVersion());
    }


    @Test
    void libraryMetaValuesMerged()
    {
        IMetadataLibrary primary = lib("Study").meta("A", "p-a").build();
        IMetadataLibrary fallback = lib("SDTMIG").meta(MetadataKeys.STANDARD_NAME, "sdtmig")
                .meta(MetadataKeys.STANDARD_VERSION, "3-4").meta("A", "f-a") // should not win
                .build();
        EnrichedMetadataLibrary enriched = new EnrichedMetadataLibrary(primary, fallback);

        assertEquals("p-a", enriched.getMetaValue("A").orElse(null));
        assertEquals("sdtmig", enriched.getMetaValue(MetadataKeys.STANDARD_NAME).orElse(null));
        assertEquals("3-4", enriched.getMetaValue(MetadataKeys.STANDARD_VERSION).orElse(null));
    }


    @Test
    void nullArgumentsThrow()
    {
        IMetadataLibrary lib = lib("X").build();
        assertThrows(NullPointerException.class, () -> new EnrichedMetadataLibrary(null, lib));
        assertThrows(NullPointerException.class, () -> new EnrichedMetadataLibrary(lib, null));
    }

    // ------------------------------------------------------------------
    // Tables: only primary tables, enriched with fallback attrs
    // ------------------------------------------------------------------


    @Test
    void tablesComeFromPrimaryOnly()
    {
        IMetadataLibrary primary = lib("Study").table(table("DM").build()).build();
        IMetadataLibrary fallback = lib("SDTMIG").table(table("DM").build())
                .table(table("AE").build()) // NOT exposed — not in primary
                .build();
        EnrichedMetadataLibrary enriched = new EnrichedMetadataLibrary(primary, fallback);

        List<IDataTableMetadata> tables = enriched.getDataTables();
        assertEquals(1, tables.size());
        assertEquals("DM", tables.get(0).getName());
        assertTrue(enriched.getDataTable("AE").isEmpty());
    }


    @Test
    void tableLabelFallsBack()
    {
        IMetadataLibrary primary = lib("Study").table(table("DM").build()).build();
        IMetadataLibrary fallback = lib("SDTMIG").table(table("DM").label("Demographics").build())
                .build();
        IDataTableMetadata dm = new EnrichedMetadataLibrary(primary, fallback).getDataTable("DM")
                .orElseThrow();
        assertEquals("Demographics", dm.getLabel());
    }


    @Test
    void tableClassNameAndStructureFallBack()
    {
        IMetadataLibrary primary = lib("Study").table(table("AE").build()).build();
        IMetadataLibrary fallback = lib("SDTMIG").table(
                table("AE").className("Events").structure("One record per adverse event").build())
                .build();
        IDataTableMetadata ae = new EnrichedMetadataLibrary(primary, fallback).getDataTable("AE")
                .orElseThrow();
        assertEquals("Events", ae.getClassName());
        assertEquals("One record per adverse event", ae.getStructure());
    }

    // ------------------------------------------------------------------
    // IsCustomDomain derivation
    // ------------------------------------------------------------------


    @Test
    void isCustomDomainIsFalseWhenTableIsInFallback()
    {
        IMetadataLibrary primary = lib("Study").table(table("DM").build()).build();
        IMetadataLibrary fallback = lib("SDTMIG").table(table("DM").build()).build();
        IDataTableMetadata dm = new EnrichedMetadataLibrary(primary, fallback).getDataTable("DM")
                .orElseThrow();
        assertEquals(false, dm.getMetaValue(MetadataKeys.IS_CUSTOM_DOMAIN).orElseThrow());
    }


    @Test
    void isCustomDomainIsTrueWhenTableIsNotInFallback()
    {
        IMetadataLibrary primary = lib("Study").table(table("MYDOMAIN").build()).build();
        IMetadataLibrary fallback = lib("SDTMIG").build();
        IDataTableMetadata custom = new EnrichedMetadataLibrary(primary, fallback)
                .getDataTable("MYDOMAIN").orElseThrow();
        assertEquals(true, custom.getMetaValue(MetadataKeys.IS_CUSTOM_DOMAIN).orElseThrow());
    }


    @Test
    void primaryCanExplicitlyOverrideIsCustomDomain()
    {
        // Primary marks a standard-sounding name as custom (edge case)
        IMetadataLibrary primary = lib("Study")
                .table(table("DM").meta(MetadataKeys.IS_CUSTOM_DOMAIN, true).build()).build();
        IMetadataLibrary fallback = lib("SDTMIG").table(table("DM").build()).build();
        IDataTableMetadata dm = new EnrichedMetadataLibrary(primary, fallback).getDataTable("DM")
                .orElseThrow();
        assertEquals(true, dm.getMetaValue(MetadataKeys.IS_CUSTOM_DOMAIN).orElseThrow());
    }


    @Test
    void isCustomDomainKeyAppearsInMetaKeySet()
    {
        IMetadataLibrary primary = lib("Study").table(table("DM").build()).build();
        IMetadataLibrary fallback = lib("SDTMIG").table(table("DM").build()).build();
        IDataTableMetadata dm = new EnrichedMetadataLibrary(primary, fallback).getDataTable("DM")
                .orElseThrow();
        assertTrue(dm.getMetaKeys().contains(MetadataKeys.IS_CUSTOM_DOMAIN));
    }

    // ------------------------------------------------------------------
    // Columns: primary wins, fallback fills gaps
    // ------------------------------------------------------------------


    @Test
    void columnLabelAndCoreFallBack()
    {
        IMetadataLibrary primary = lib("Study").table(
                table("DM").column(column("STUDYID", 0, DataValueType.STRING).build()).build())
                .build();
        IMetadataLibrary fallback = lib("SDTMIG")
                .table(table("DM").column(column("STUDYID", 0, DataValueType.STRING)
                        .label("Study Identifier").core("Req").role("Identifier").build()).build())
                .build();
        IDataTableMetadata dm = new EnrichedMetadataLibrary(primary, fallback).getDataTable("DM")
                .orElseThrow();
        IColumnMetadata studyid = dm.getColumn("STUDYID").orElseThrow();
        assertEquals("Study Identifier", studyid.getLabel());
        assertEquals("Req", studyid.getCore());
        assertEquals("Identifier", studyid.getRole());
    }


    @Test
    void columnLabelPrimaryWins()
    {
        IMetadataLibrary primary = lib("Study").table(table("DM")
                .column(column("STUDYID", 0, DataValueType.STRING).label("Custom study id").build())
                .build()).build();
        IMetadataLibrary fallback = lib("SDTMIG").table(table("DM").column(
                column("STUDYID", 0, DataValueType.STRING).label("Study Identifier").build())
                .build()).build();
        IColumnMetadata studyid = new EnrichedMetadataLibrary(primary, fallback).getDataTable("DM")
                .orElseThrow().getColumn("STUDYID").orElseThrow();
        assertEquals("Custom study id", studyid.getLabel());
    }


    @Test
    void columnsOnlyFromPrimary()
    {
        IMetadataLibrary primary = lib("Study").table(
                table("DM").column(column("STUDYID", 0, DataValueType.STRING).build()).build())
                .build();
        IMetadataLibrary fallback = lib("SDTMIG")
                .table(table("DM").column(column("STUDYID", 0, DataValueType.STRING).build())
                        .column(column("USUBJID", 1, DataValueType.STRING).build()).build())
                .build();
        List<IColumnMetadata> cols = new EnrichedMetadataLibrary(primary, fallback)
                .getDataTable("DM").orElseThrow().getColumns();
        assertEquals(1, cols.size());
        assertEquals("STUDYID", cols.get(0).getName());
    }


    @Test
    void customColumnNotInFallbackWorks()
    {
        IMetadataLibrary primary = lib("Study")
                .table(table("DM").column(column("MYVAR", 0, DataValueType.STRING).build()).build())
                .build();
        IMetadataLibrary fallback = lib("SDTMIG").table(table("DM").build()).build();
        IColumnMetadata myvar = new EnrichedMetadataLibrary(primary, fallback).getDataTable("DM")
                .orElseThrow().getColumn("MYVAR").orElseThrow();
        assertEquals("MYVAR", myvar.getName());
        assertNull(myvar.getLabel());
    }

    // ------------------------------------------------------------------
    // Codelists: union, primary wins on collision, fallback-only exposed
    // ------------------------------------------------------------------


    @Test
    void codelistUnionExposedViaGetCodelists()
    {
        IMetadataLibrary primary = lib("Study")
                .codelist(codelist("CUSTOMCL").entry("X", "X-val").build()).build();
        IMetadataLibrary fallback = lib("SDTMIG")
                .codelist(codelist("SEX").entry("M", "Male").entry("F", "Female").build())
                .codelist(codelist("NY").entry("Y", "Yes").entry("N", "No").build()).build();

        List<ICodeList> all = new EnrichedMetadataLibrary(primary, fallback).getCodelists();
        assertEquals(3, all.size());
        assertTrue(all.stream().anyMatch(c -> "CUSTOMCL".equals(c.getName())));
        assertTrue(all.stream().anyMatch(c -> "SEX".equals(c.getName())));
        assertTrue(all.stream().anyMatch(c -> "NY".equals(c.getName())));
    }


    @Test
    void fallbackOnlyCodelistFoundByGetCodelist()
    {
        IMetadataLibrary primary = lib("Study").build();
        IMetadataLibrary fallback = lib("SDTMIG")
                .codelist(codelist("SEX").entry("M", "Male").entry("F", "Female").build()).build();
        EnrichedMetadataLibrary enriched = new EnrichedMetadataLibrary(primary, fallback);
        assertTrue(enriched.getCodelist("SEX").isPresent());
        assertEquals(2, enriched.getCodelist("SEX").orElseThrow().getEntries().size());
    }


    @Test
    void primaryCodelistWinsOnCollision()
    {
        IMetadataLibrary primary = lib("Study")
                .codelist(codelist("SEX").entry("M", "PRIMARY-Male").build()).build();
        IMetadataLibrary fallback = lib("SDTMIG")
                .codelist(codelist("SEX").entry("M", "Male").entry("F", "Female").build()).build();
        ICodeList sex = new EnrichedMetadataLibrary(primary, fallback).getCodelist("SEX")
                .orElseThrow();
        assertEquals(1, sex.getEntries().size());
        assertEquals("PRIMARY-Male", sex.getEntries().get(0).getDecodeValue());
    }


    @Test
    void unknownCodelistIsEmpty()
    {
        EnrichedMetadataLibrary enriched = new EnrichedMetadataLibrary(lib("Study").build(),
                lib("SDTMIG").build());
        assertTrue(enriched.getCodelist("UNKNOWN").isEmpty());
    }

    // ------------------------------------------------------------------
    // Integration with MetadataLibraryProvider
    // ------------------------------------------------------------------


    @Test
    void integratesWithMetadataLibraryProvider()
    {
        // Study knows structure + some labels
        IMetadataLibrary study = lib("Study")
                .table(table("DM").label("Custom Demographics")
                        .column(column("STUDYID", 0, DataValueType.STRING).build())
                        .column(column("USUBJID", 1, DataValueType.STRING).build())
                        .column(column("MYVAR", 2, DataValueType.STRING).build()).build())
                .table(table("MYDOMAIN").column(column("STUDYID", 0, DataValueType.STRING).build())
                        .build())
                .build();

        // Standards knows core attrs, class info, extra codelists
        IMetadataLibrary standards = lib("SDTMIG").meta(MetadataKeys.STANDARD_NAME, "sdtmig")
                .meta(MetadataKeys.STANDARD_VERSION, "3-4")
                .table(table("DM").className("Special-Purpose").structure("One record per subject")
                        .column(column("STUDYID", 0, DataValueType.STRING).label("Study Identifier")
                                .core("Req").role("Identifier").build())
                        .column(column("USUBJID", 1, DataValueType.STRING)
                                .label("Unique Subject Identifier").core("Req").role("Identifier")
                                .build())
                        .build())
                .codelist(codelist("SEX").entry("M", "Male").entry("F", "Female").build()).build();

        EnrichedMetadataLibrary enriched = new EnrichedMetadataLibrary(study, standards);
        MetadataLibraryProvider provider = new MetadataLibraryProvider(enriched);

        // Standard/version came from standards
        assertEquals("sdtmig", provider.getStandard());
        assertEquals("3-4", provider.getVersion());

        // DM is not custom
        assertFalse(provider.isDomainCustom("DM"));
        // MYDOMAIN is custom (not in standards)
        assertTrue(provider.isDomainCustom("MYDOMAIN"));

        // DM required variables come from enriched column core
        assertEquals(List.of("STUDYID", "USUBJID"), provider.getRequiredVariables("DM"));

        // Custom column MYVAR is in the column list
        assertEquals(List.of("STUDYID", "USUBJID", "MYVAR"), provider.getColumnOrder("DM"));

        // SEX codelist visible via fallback
        assertEquals(List.of("M", "F"), provider.getCodelistTerms("SEX"));
    }

}
