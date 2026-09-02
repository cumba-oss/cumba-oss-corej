package net.cumba.cdisc.core.metadata;

import static net.cumba.datatable.testkit.TestMetadataFixtures.column;
import static net.cumba.datatable.testkit.TestMetadataFixtures.lib;
import static net.cumba.datatable.testkit.TestMetadataFixtures.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.core.metadata.MetadataLibraryProvider.DeclaredAdamProduct;
import net.cumba.cdisc.library.api.model.adam.AdamProduct;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 of {@code plans/PLAN-metadata-product-selection.md} — {@link MetadataLibraryProvider}
 * holds an <b>ordered list</b> of declared ADaM products (ruling 1: first-match-wins on the user's
 * precedence order), and every structure-keyed answer is traceable to the product that supplied it
 * (provenance: the {@code standards/...} cache key).
 */
class MetadataLibraryProviderMultiProductTest
{

    // ------------------------------------------------------------------
    // Fixtures — minimal ADaM products via MapResource
    // ------------------------------------------------------------------

    private static Map<String, Object> adamVar(String name, String ordinal, String core)
    {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("name", name);
        v.put("label", name);
        v.put("ordinal", ordinal);
        v.put("simpleDatatype", "Char");
        v.put("core", core);
        return v;
    }


    private static Map<String, Object> structure(String name, String className,
            List<Map<String, Object>> vars)
    {
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("name", "Variables");
        set.put("ordinal", "1");
        set.put("analysisVariables", vars);

        Map<String, Object> ds = new LinkedHashMap<>();
        ds.put("name", name);
        ds.put("label", name);
        ds.put("class", className);
        ds.put("analysisVariableSets", List.of(set));
        return ds;
    }


    @SafeVarargs
    private static AdamProduct product(String name, Map<String, Object>... structures)
    {
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", name);
        product.put("version", "1-0");
        product.put("dataStructures", List.of(structures));
        return net.cumba.web.api.dev.MapResource.of(product, AdamProduct.class);
    }


    /** Product with an AE structure of class OCCURRENCE DATA STRUCTURE requiring AEDECOD. */
    private static AdamProduct occdsSupplement()
    {
        return product("adam-occds", structure("AE", "OCCURRENCE DATA STRUCTURE",
                List.of(adamVar("AEDECOD", "1", "Req"), adamVar("ONTRTFL", "2", "Cond"))));
    }


    /** Product with a base OCCDS structure requiring only USUBJID. */
    private static AdamProduct occdsBase()
    {
        return product("adamig", structure("OCCDS", "OCCURRENCE DATA STRUCTURE",
                List.of(adamVar("USUBJID", "1", "Req"), adamVar("AEDECOD", "2", "Cond"))));
    }


    private static IMetadataLibrary study()
    {
        return lib("study").table(
                table("ADAE").column(column("USUBJID", 0, DataValueType.STRING).build()).build())
                .build();
    }


    private static MetadataLibraryProvider provider(DeclaredAdamProduct... declared)
    {
        return new MetadataLibraryProvider(study(), List.of(declared), "adamig", "1-3");
    }

    // ------------------------------------------------------------------
    // First-match-wins (ruling 1)
    // ------------------------------------------------------------------


    @Test
    void firstDeclaredProductWins_precedenceIsTheUsersOrder()
    {
        DeclaredAdamProduct supplement = new DeclaredAdamProduct("standards/adam/adam-occds-1-1",
                occdsSupplement());
        DeclaredAdamProduct base = new DeclaredAdamProduct("standards/adam/adamig-1-3",
                occdsBase());

        // Supplement first: AE's Req set answers; the base product is NOT consulted, so its
        // Req USUBJID must not leak in (that would be the old union, resurrected).
        assertEquals(List.of("AEDECOD"), provider(supplement, base)
                .getRequiredVariablesForStructure("OCCURRENCE DATA STRUCTURE"));

        // Base first: the opposite answer — order is the user's, not the engine's.
        assertEquals(List.of("USUBJID"), provider(base, supplement)
                .getRequiredVariablesForStructure("OCCURRENCE DATA STRUCTURE"));
    }


    @Test
    void laterProductSuppliesTokensTheFirstDoesNotDefine()
    {
        DeclaredAdamProduct bdsOnly = new DeclaredAdamProduct("standards/adam/adam-tte-1-0",
                product("adam-tte", structure("TTE", "BASIC DATA STRUCTURE",
                        List.of(adamVar("CNSR", "1", "Req")))));
        DeclaredAdamProduct base = new DeclaredAdamProduct("standards/adam/adamig-1-3",
                occdsBase());

        MetadataLibraryProvider p = provider(bdsOnly, base);
        // First product answers its own token...
        assertEquals(List.of("CNSR"), p.getRequiredVariablesForStructure("BASIC DATA STRUCTURE"));
        // ...and the chain falls through to the later product for a token it lacks.
        assertEquals(List.of("USUBJID"),
                p.getRequiredVariablesForStructure("OCCURRENCE DATA STRUCTURE"));
        // A token no declared product defines is null — "no such structure", never empty.
        assertNull(p.getRequiredVariablesForStructure("SUBJECT LEVEL ANALYSIS DATASET"));
    }

    // ------------------------------------------------------------------
    // Empty list / compatibility
    // ------------------------------------------------------------------


    @Test
    void emptyProductListDoesNotSupportStructureKeyedVariables()
    {
        MetadataLibraryProvider p = new MetadataLibraryProvider(study(), List.of(), "adamig",
                "1-3");
        assertFalse(p.supportsStructureKeyedVariables());
        assertNull(p.getRequiredVariablesForStructure("OCCURRENCE DATA STRUCTURE"));
        assertEquals(List.of(), p.declaredStructureKeyedProducts());
    }


    @Test
    void singleProductConstructorDerivesItsCacheKeyFromStandardAndVersion()
    {
        MetadataLibraryProvider p = new MetadataLibraryProvider(study(), occdsBase(), "adamig",
                "1-3");
        assertTrue(p.supportsStructureKeyedVariables());
        assertEquals(List.of("standards/adam/adamig-1-3"), p.declaredStructureKeyedProducts());
    }

    // ------------------------------------------------------------------
    // Provenance
    // ------------------------------------------------------------------


    @Test
    void singleProductConstructorWithoutStandardContextUsesAPlaceholderKey()
    {
        MetadataLibraryProvider p = new MetadataLibraryProvider(study(), occdsBase(), null, null);
        assertEquals(List.of("<undeclared adam product>"), p.declaredStructureKeyedProducts());
    }


    @Test
    void classNameWalkAcrossProductsReturnsNothingForAnUnknownClass()
    {
        // The declared study class matches no structure in any declared product: the class-name
        // walk exhausts the ordered list and the ADaM model-variable resolution yields nothing.
        IMetadataLibrary study = lib("study")
                .table(table("ADXX").className("NO SUCH CLASS")
                        .column(column("USUBJID", 0, DataValueType.STRING).build()).build())
                .build();
        MetadataLibraryProvider p = new MetadataLibraryProvider(study,
                List.of(new DeclaredAdamProduct("standards/adam/adamig-1-3", occdsBase())),
                "adamig", "1-3");
        assertEquals(List.of(), p.getStandardModelVariables(mockTable("ADXX"), null));
    }


    private static net.cumba.datatable.IDataTable mockTable(String name)
    {
        net.cumba.datatable.IDataTable table = org.mockito.Mockito
                .mock(net.cumba.datatable.IDataTable.class);
        net.cumba.datatable.DataTableMeta meta = org.mockito.Mockito
                .mock(net.cumba.datatable.DataTableMeta.class);
        org.mockito.Mockito.lenient().when(meta.getName()).thenReturn(name);
        org.mockito.Mockito.lenient().when(table.getMetaData()).thenReturn(meta);
        return table;
    }


    @Test
    void declaredProductsAreReportedInPrecedenceOrder()
    {
        MetadataLibraryProvider p = provider(
                new DeclaredAdamProduct("standards/adam/adam-occds-1-1", occdsSupplement()),
                new DeclaredAdamProduct("standards/adam/adamig-1-3", occdsBase()));
        assertEquals(List.of("standards/adam/adam-occds-1-1", "standards/adam/adamig-1-3"),
                p.declaredStructureKeyedProducts());
    }


    @Test
    void companionDecoratorDelegatesProvenanceToTheRunProvider()
    {
        MetadataLibraryProvider base = provider(
                new DeclaredAdamProduct("standards/adam/adamig-1-3", occdsBase()));
        MetadataProvider companion = new MetadataLibraryProvider(study());
        CompanionDomainsProvider wrapped = new CompanionDomainsProvider(base, companion);
        assertEquals(List.of("standards/adam/adamig-1-3"),
                wrapped.declaredStructureKeyedProducts());
    }


    @Test
    void multiStructureUnionWithinOneProductLogsTheSupplyingCacheKey()
    {
        // One product, two structures of the same class: the equal-specificity union survives
        // (Phase 3 narrows it) and the INFO line now names the supplying product's cache key.
        AdamProduct twoStructures = product("adam-occds",
                structure("AE", "OCCURRENCE DATA STRUCTURE",
                        List.of(adamVar("AEDECOD", "1", "Req"))),
                structure("CM", "OCCURRENCE DATA STRUCTURE",
                        List.of(adamVar("CMTRT", "1", "Req"))));
        MetadataLibraryProvider p = provider(
                new DeclaredAdamProduct("standards/adam/adam-occds-1-1", twoStructures));

        Logger logger = Logger.getLogger(MetadataLibraryProvider.class.getName());
        CapturingHandler handler = new CapturingHandler();
        handler.setLevel(Level.ALL);
        Level previous = logger.getLevel();
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        try
        {
            assertEquals(List.of("AEDECOD", "CMTRT"),
                    p.getRequiredVariablesForStructure("OCCURRENCE DATA STRUCTURE"));
        }
        finally
        {
            logger.removeHandler(handler);
            logger.setLevel(previous);
        }
        assertTrue(
                handler.formatted().stream()
                        .anyMatch(m -> m.contains("standards/adam/adam-occds-1-1")),
                () -> "the union INFO line must name the supplying product's cache key; got: "
                        + handler.formatted());
    }

    /** Collects the records the provider's {@link System.Logger} routes through JUL. */
    private static final class CapturingHandler extends Handler
    {

        private final List<LogRecord> records = new ArrayList<>();

        List<String> formatted()
        {
            return records.stream()
                    .map(r -> MessageFormat.format(r.getMessage(), r.getParameters())).toList();
        }


        @Override
        public void publish(LogRecord logRecord)
        {
            records.add(logRecord);
        }


        @Override
        public void flush()
        {
            // nothing buffered
        }


        @Override
        public void close()
        {
            // nothing to release
        }


        @Override
        public boolean isLoggable(LogRecord logRecord)
        {
            return logRecord.getLevel().intValue() >= Level.INFO.intValue();
        }
    }
}
